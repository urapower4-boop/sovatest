package com.sova.test

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat

class ScreenCaptureService : Service() {

    companion object {
        @Volatile var instance: ScreenCaptureService? = null
        private const val CH_ID = "sova_cap"
        private const val NOTIF_ID = 1001
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var sw = 0
    private var sh = 0
    private var dpi = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CH_ID, "SovaTest Capture", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
        val n = NotificationCompat.Builder(this, CH_ID)
            .setContentTitle("SovaTest")
            .setContentText("Готова сканувати")
            .setSmallIcon(R.drawable.ic_owl)
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, n)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val code = intent?.getIntExtra("code", -1) ?: -1
        val data = intent?.getParcelableExtra<Intent>("data")
        if (code != -1 && data != null && projection == null) {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = mpm.getMediaProjection(code, data)
            setupVirtualDisplay()
        }
        return START_STICKY
    }

    private fun setupVirtualDisplay() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(metrics)
        sw = metrics.widthPixels
        sh = metrics.heightPixels
        dpi = metrics.densityDpi

        imageReader = ImageReader.newInstance(sw, sh, PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection?.createVirtualDisplay(
            "sova-cap", sw, sh, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, Handler(Looper.getMainLooper())
        )
    }

    fun screenWidth(): Int = sw
    fun screenHeight(): Int = sh

    fun grab(): Bitmap? {
        val reader = imageReader ?: return null
        var image: Image? = null
        return try {
            val start = System.currentTimeMillis()
            while (image == null && System.currentTimeMillis() - start < 800) {
                image = reader.acquireLatestImage()
                if (image == null) Thread.sleep(30)
            }
            val img = image ?: return null
            val plane = img.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * sw
            val full = Bitmap.createBitmap(
                sw + rowPadding / pixelStride, sh, Bitmap.Config.ARGB_8888
            )
            full.copyPixelsFromBuffer(buffer)
            Bitmap.createBitmap(full, 0, 0, sw, sh)
        } catch (e: Exception) {
            null
        } finally {
            image?.close()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        virtualDisplay?.release()
        imageReader?.close()
        projection?.stop()
        instance = null
    }
}
