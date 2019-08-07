package com.example.qriticalinfo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.SurfaceHolder
import androidx.annotation.UiThread
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.api.services.drive.Drive
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import net.glxn.qrgen.android.QRCode
import java.util.*
import java.util.concurrent.atomic.AtomicReference

class QriticalInfoWallpaper : WallpaperService() {

    var account by AtomicReferenceObservable<GoogleSignInAccount?>(null) {_, new ->
        if (new == null) {
            drive = null
        } else {
            getDriveForAccount(new, applicationContext)
        }
    }

    var drive by AtomicReferenceObservable<Drive?>(null) {_, _ ->
        lastUpdated.set(MINIMUM_DATE)
        updateQrCode()
    }
    private val lastUpdated = AtomicReference(MINIMUM_DATE)
    private val defaultFilePrefs by lazy {
        getSharedPreferences(getString(R.string.chosen_file_key), Context.MODE_PRIVATE)
    }
    private val handler by lazy { Handler() }
    var qrCode by AtomicReferenceObservable<Bitmap?>(null) {old, _ -> old?.recycle() }

    var callback by AtomicReferenceObservable<DrawRunnable?>(null) {old, _ ->
        if (old != null) {
            handler.removeCallbacks(old)
        }
    }

    @Volatile private var width = 0
    @Volatile private var height = 0
    @Volatile private var haveSurface = false

    companion object {
        const val ACTION_REDRAW = "com.example.qriticalinfo.Redraw"
        const val ACTION_SET_GOOGLE_ACCOUNT = "com.example.qriticalinfo.SetGoogleAccount"
        const val REFRESH_DELAY_MS = 10_000L
        val MINIMUM_DATE = GregorianCalendar(0, 1, 1)
    }

    internal fun updateQrCode() {
        val currentDrive = drive ?: return
        val fileId = getFileId()
        val webLink = currentDrive.Files().get(fileId)["webViewLink"].toString()
        qrCode = QRCode.from(webLink)
            .withErrorCorrection(ErrorCorrectionLevel.H)
            .withSize(width, height)
            .bitmap()
    }

    private fun getFileId(): String? {
        val currentAccount = account
        return if (currentAccount != null) {
            defaultFilePrefs.getString(currentAccount.idToken, null)
        } else null
    }

    override fun onCreateEngine(): Engine? {
        LocalBroadcastManager.getInstance(applicationContext)
            .registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    val newAccount = intent?.extras?.get("account")
                    if (newAccount != null) {
                        account = newAccount as GoogleSignInAccount
                    } else {
                        updateQrCode()
                    }
                }
            }, IntentFilter(Context.WALLPAPER_SERVICE))
        account = GoogleSignIn.getLastSignedInAccount(applicationContext)
        return object : Engine() {
            override fun onSurfaceRedrawNeeded(holder: SurfaceHolder?) {
                super.onSurfaceRedrawNeeded(holder)
                draw(holder)
            }

            override fun onSurfaceChanged(holder: SurfaceHolder?, format: Int, width: Int, height: Int) {
                super.onSurfaceChanged(holder, format, width, height)
                if (holder == null) {
                    callback = null
                } else {
                    callback = DrawRunnable(holder)
                    draw(holder)
                }
            }

            override fun onSurfaceDestroyed(holder: SurfaceHolder?) {
                super.onSurfaceDestroyed(holder)
                haveSurface = false
            }

            override fun onCreate(surfaceHolder: SurfaceHolder?) {
                super.onCreate(surfaceHolder)
                draw(surfaceHolder)
            }

            override fun onCommand(
                action: String?,
                x: Int,
                y: Int,
                z: Int,
                extras: Bundle?,
                resultRequested: Boolean
            ): Bundle? {
                when (action) {
                    ACTION_REDRAW -> {
                        updateQrCode()
                    }
                }
                return super.onCommand(action, x, y, z, extras, resultRequested)
            }

            override fun onSurfaceCreated(holder: SurfaceHolder?) {
                super.onSurfaceCreated(holder)
                haveSurface = true
                draw(holder)
            }
        }
    }

    inner class DrawRunnable(val holder: SurfaceHolder) : Runnable {
        override fun run() {
            draw(holder)
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as DrawRunnable

            if (holder != other.holder) return false

            return true
        }

        override fun hashCode(): Int {
            return holder.hashCode()
        }
    }

    @UiThread
    internal fun draw(surfaceHolder: SurfaceHolder?) {
        if (surfaceHolder == null) return
        if (!haveSurface) return
        val frame = surfaceHolder.surfaceFrame
        val newWidth = frame.width()
        val newHeight = frame.height()
        if (width != newWidth || height != newHeight) {
            width = newWidth
            height = newHeight
            updateQrCode()
        }
        val dest = Rect(0, 0, newWidth, newHeight)
        val currentQrCode: Bitmap? = qrCode
        val canvas = surfaceHolder.lockCanvas()
        if (canvas == null) {
            if (Looper.getMainLooper().thread != Thread.currentThread()) {
                Log.e(getString(R.string.logTag), "Called from non-UI thread", IllegalStateException())
            }
            Log.wtf(getString(R.string.logTag), "lockCanvas failed")
            return
        }
        try {
            if (currentQrCode == null) {
                // TODO: Error message
            } else {
                canvas.drawBitmap(currentQrCode, null, dest, null)
            }
        } finally {
            surfaceHolder.unlockCanvasAndPost(canvas)
        }
        val currentCallback = callback
        if (currentCallback?.holder == surfaceHolder) {
            handler.postDelayed(currentCallback, REFRESH_DELAY_MS)
        }
    }

}
