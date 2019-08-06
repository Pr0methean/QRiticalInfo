package com.example.qriticalinfo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.SurfaceHolder
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

    companion object {
        const val ACTION_REDRAW = "com.example.qriticalinfo.Redraw"
        const val ACTION_SET_GOOGLE_ACCOUNT = "com.example.qriticalinfo.SetGoogleAccount"
        val MINIMUM_DATE = GregorianCalendar(0, 1, 1)
        val JSON_FACTORY = GsonFactory()
        val HTTP_TRANSPORT by lazy { NetHttpTransport() }
        fun getDriveForAccount(
            value: GoogleSignInAccount,
            context: Context
        ): Drive {
            val credential =
                GoogleAccountCredential.usingOAuth2(context, Collections.singleton(DriveScopes.DRIVE_FILE))
            credential.selectedAccount = value.account
            return Drive.Builder(HTTP_TRANSPORT, JSON_FACTORY, credential)
                .setApplicationName(context.getString(R.string.app_name))
                .build()
        }
    }

    private fun updateQrCode() {
        val currentDrive = drive ?: return
        val fileId = getFileId(currentDrive)
        val webLink = currentDrive.Files().get(fileId)["webViewLink"]
        // TODO
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
                    if (intent == null) return
                    account = (intent.extras?.get("account")) as? GoogleSignInAccount
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
                    ACTION_REDRAW -> {
                        account = (extras?.get("account")) as? GoogleSignInAccount
                    }
                }
                return super.onCommand(action, x, y, z, extras, resultRequested)
            }

            override fun onSurfaceCreated(holder: SurfaceHolder?) {
                super.onSurfaceCreated(holder)
                draw(holder)
            }
        }
    }

    internal fun draw(surfaceHolder: SurfaceHolder?) {
        if (surfaceHolder == null) return
        val frame = surfaceHolder.surfaceFrame
        val width = frame.width()
        val height = frame.height()
        val dest = Rect(0, 0, width, height)
        val currentAccount = account
        val bitmap: Bitmap? = currentAccount?.let {
            QRCode.from(createUrl(currentAccount))
                .withErrorCorrection(ErrorCorrectionLevel.H)
                .withSize(width, height)
                .bitmap()
        }
        val canvas = surfaceHolder.lockCanvas()
        if (canvas == null) {
            Log.wtf(getString(R.string.logTag), "lockCanvas failed")
            return
        }
        try {
            if (bitmap == null) {
                // TODO: Error message
            } else {
                canvas.drawBitmap(bitmap, null, dest, null)
            }
        } finally {
            surfaceHolder.unlockCanvasAndPost(canvas)
        }
    }

    private fun createUrl(account: GoogleSignInAccount): String {
        lastUpdated.set(GregorianCalendar())
        return "TODO $account" // TODO
    }
}
