package com.example.qrhealth

import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.Handler
import android.service.wallpaper.WallpaperService
import android.text.TextPaint
import android.util.Log
import android.view.SurfaceHolder
import androidx.annotation.UiThread
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import net.glxn.qrgen.android.QRCode
import java.util.*
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.min

val ERROR_PAINT by lazy {
    val out = TextPaint(Paint(R.color.error_text))
    out.textSize = 18.0f
    out
}
val SOURCE_SIZE = 1000

class QriticalInfoWallpaper : WallpaperService(), SharedPreferences.OnSharedPreferenceChangeListener {
    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, p1: String?) {
        if (defaultFilePrefs == sharedPreferences) {
            updateQrCode()
        }
    }

    private val lastUpdated = AtomicReference(MINIMUM_DATE)
    private val defaultFilePrefs by lazy {
        getSharedPreferences(getString(R.string.chosen_file_key), Context.MODE_PRIVATE)
    }
    private val handler by lazy { Handler() }
    var display by AtomicReferenceObservable<Bitmap?>(null) { old, _ -> old?.recycle() }

    var callback by AtomicReferenceObservable<DrawRunnable?>(null) {old, _ ->
        if (old != null) {
            handler.removeCallbacks(old)
        }
    }

    @Volatile private var width = 0
    @Volatile private var height = 0
    @Volatile internal var haveSurface = false

    companion object {
        const val ACTION_REDRAW = "com.example.qriticalinfo.Redraw"
        const val ACTION_SET_GOOGLE_ACCOUNT = "com.example.qriticalinfo.SetGoogleAccount"
        const val REFRESH_DELAY_MS = 10_000L
        val MINIMUM_DATE = GregorianCalendar(0, 1, 1)
    }

    internal fun updateQrCode() {
        val curWidth = width
        val curHeight = height
        val webLink = defaultFilePrefs.getString("share", null)
        val out = Bitmap.createBitmap(curWidth, curHeight, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.WHITE)
        if (webLink == null) {
            c.drawText(getString(R.string.not_configured), width * 0.5f, height * 0.5f, ERROR_PAINT)
        } else {
            Log.d(getString(R.string.logTag), "Generating a QR code for $webLink")
            val smallerDim = min(width, height)
            val largerDim = max(width, height)
            val qrCodeSize = min(largerDim / 3, smallerDim)
            val qrCode = QRCode.from(webLink)
                .withColor(Color.BLACK, Color.WHITE)
                .withErrorCorrection(ErrorCorrectionLevel.H)
                .withSize(qrCodeSize, qrCodeSize)
                .bitmap()
            if (width > height) {
                // 3 copies in row
                val yOffset = (height - qrCodeSize) * 0.5f
                c.drawBitmap(qrCode, 0.0f, yOffset, null)
                c.drawBitmap(qrCode, qrCodeSize.toFloat(), yOffset, null)
                c.drawBitmap(qrCode, qrCodeSize * 2.0f, yOffset, null)
            } else {
                // 3 copies in column
                val xOffset = (width - qrCodeSize) * 0.5f
                c.drawBitmap(qrCode, xOffset, 0.0f, null)
                c.drawBitmap(qrCode, xOffset, qrCodeSize.toFloat(), null)
                c.drawBitmap(qrCode, xOffset, qrCodeSize * 2.0f, null)
            }
        }
        display = out
    }

    override fun onCreateEngine(): Engine {
        val wallpaperManager = WallpaperManager.getInstance(applicationContext)
        width = wallpaperManager.desiredMinimumWidth
        height = wallpaperManager.desiredMinimumHeight
        defaultFilePrefs.registerOnSharedPreferenceChangeListener(this)
        if (BuildConfig.DEBUG) {
            val currentPrefs = defaultFilePrefs.all
            if (currentPrefs.isEmpty()) {
                Log.d(getString(R.string.logTag), "Shared prefs are empty in wallpaper")
            }
            for ((key, value) in currentPrefs) {
                Log.d(getString(R.string.logTag), "Shared pref in wallpaper: $key = $value")
            }
        }
        LocalBroadcastManager.getInstance(applicationContext)
            .registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    Log.d(getString(R.string.logTag), "onReceive")
                    updateQrCode()
                }
            }, IntentFilter(Context.WALLPAPER_SERVICE))
        return object : Engine() {
            override fun onSurfaceRedrawNeeded(holder: SurfaceHolder?) {
                super.onSurfaceRedrawNeeded(holder)
                Log.d(getString(R.string.logTag), "onSurfaceRedrawNeeded")
                draw(holder)
            }

            override fun onSurfaceChanged(holder: SurfaceHolder?, format: Int, width: Int, height: Int) {
                super.onSurfaceChanged(holder, format, width, height)
                resizeIfNecessary(surfaceHolder)
                Log.d(getString(R.string.logTag), "onSurfaceChanged")
                if (holder == null) {
                    callback = null
                } else {
                    callback = DrawRunnable(holder)
                    draw(holder)
                }
            }

            override fun onSurfaceDestroyed(holder: SurfaceHolder?) {
                super.onSurfaceDestroyed(holder)
                Log.d(getString(R.string.logTag), "onSurfaceDestroyed")
                haveSurface = false
            }

            override fun onCreate(surfaceHolder: SurfaceHolder?) {
                super.onCreate(surfaceHolder)
                Log.d(getString(R.string.logTag), "onCreate")
                if (surfaceHolder != null) {
                    resizeIfNecessary(surfaceHolder)
                    updateQrCode()
                    draw(surfaceHolder)
                }
            }

            override fun onCommand(
                action: String?,
                x: Int,
                y: Int,
                z: Int,
                extras: Bundle?,
                resultRequested: Boolean
            ): Bundle? {
                val out = super.onCommand(action, x, y, z, extras, resultRequested)
                if (BuildConfig.DEBUG) {
                    Log.d(getString(R.string.logTag), "onCommand($action,$x,$y,$z,$extras,$resultRequested)")
                }
                when (action) {
                    ACTION_REDRAW -> {
                        updateQrCode()
                    }
                }
                return out
            }

            override fun onSurfaceCreated(holder: SurfaceHolder?) {
                super.onSurfaceCreated(holder)
                resizeIfNecessary(holder)
                Log.d(getString(R.string.logTag), "onSurfaceCreated")
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

    internal fun resizeIfNecessary(newHolder: SurfaceHolder?) {
        newHolder ?: return
        val frame = newHolder.surfaceFrame
        val newWidth = frame.width()
        val newHeight = frame.height()
        if (newWidth > 0 && newHeight > 0 && (width != newWidth || height != newHeight)) {
            width = newWidth
            height = newHeight
            updateQrCode()
        }
    }

    @UiThread
    internal fun draw(surfaceHolder: SurfaceHolder?) {
        if (surfaceHolder == null) return
        if (!haveSurface) return
        resizeIfNecessary(surfaceHolder)
        val dest = surfaceHolder.surfaceFrame
        val currentQrCode: Bitmap? = display
        if (currentQrCode == null) {
            Log.e(getString(R.string.logTag), "currentQrCode not set")
            return
        }
        val canvas = surfaceHolder.lockCanvas()
        if (canvas == null) {
            Log.w(getString(R.string.logTag), "lockCanvas failed")
            return
        }
        canvas.drawBitmap(currentQrCode, null, dest, null)
        surfaceHolder.unlockCanvasAndPost(canvas)
        val currentCallback = callback
        if (currentCallback?.holder == surfaceHolder) {
            handler.postDelayed(currentCallback, REFRESH_DELAY_MS)
        }
    }

}
