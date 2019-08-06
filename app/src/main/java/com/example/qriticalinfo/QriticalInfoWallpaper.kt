package com.example.qriticalinfo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.Looper
import android.os.Message
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.SurfaceHolder
import androidx.annotation.UiThread
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import net.glxn.qrgen.android.QRCode
import java.util.*
import java.util.concurrent.atomic.AtomicReference

class QriticalInfoWallpaper : WallpaperService() {

    private val _account = AtomicReference<GoogleSignInAccount?>()
    var account : GoogleSignInAccount?
        get() = _account.get()
        set(value) {
            if (_account.getAndSet(value) == value) {
                return
            }
            if (value == null) {
                drive = null
            } else {
                getDriveForAccount(value, applicationContext)
            }
        }

    private val _drive = AtomicReference<Drive?>()
    var drive : Drive?
        get() = _drive.get()
        set(value) {
            if (_drive.getAndSet(value) != value) {
                lastUpdated.set(MINIMUM_DATE)
                updateQrCode()
            }
        }
    val lastUpdated = AtomicReference(MINIMUM_DATE)
    private val defaultFilePrefs by lazy {
        getSharedPreferences(getString(R.string.chosen_file_key), Context.MODE_PRIVATE)
    }
    @Volatile private var qrCode: Bitmap? = null
    @Volatile private var width = 0
    @Volatile private var height = 0

    companion object {
        const val ACTION_REDRAW = "com.example.qriticalinfo.Redraw"
        const val ACTION_SET_GOOGLE_ACCOUNT = "com.example.qriticalinfo.SetGoogleAccount"
        val MINIMUM_DATE = GregorianCalendar(0, 1, 1)
    }

    private fun updateQrCode() {
        val currentDrive = drive ?: return
        val fileId = getFileId(currentDrive)
        val webLink = currentDrive.Files().get(fileId)["webViewLink"].toString()
        qrCode = QRCode.from(webLink)
            .withErrorCorrection(ErrorCorrectionLevel.H)
            .withSize(width, height)
            .bitmap()
    }

    private fun getFileId(currentDrive: Drive): String? {
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
            ): Bundle {
                when (action) {
                    ACTION_REDRAW -> updateQrCode()
                }
                return super.onCommand(action, x, y, z, extras, resultRequested)
            }

            override fun onSurfaceCreated(holder: SurfaceHolder?) {
                super.onSurfaceCreated(holder)
                draw(holder)
            }
        }
    }

    @UiThread
    internal fun draw(surfaceHolder: SurfaceHolder?) {
        if (surfaceHolder == null) return
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
            if (Looper.getMainLooper().getThread() != Thread.currentThread()) {
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
    }

}
