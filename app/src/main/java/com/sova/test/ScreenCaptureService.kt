package com.sova.test

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
    private var width = 0
    private var height = 0
    private var density = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        startForegroundSafe()
    }

    private fun startForegroundSafe() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CH_ID, "SovaTest Capture", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
        val n = NotificationCompat.Builder(this, CH_ID)
            .setContentTitle("SovaTest")
            .setContentText("Готовий сканувати")
            .setSmallIcon(R.drawable.ic_owl)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, n)
        }
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
        width = metrics.widthPixels
        height = metrics.heightPixels
        density = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection?.createVirtualDisplay(
            "sova-cap", width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, Handler(Looper.getMainLooper())
        )
    }

    fun screenWidth(): Int = width
    fun screenHeight(): Int = height

    /** Захват одного кадра в Bitmap. В галерею не пишется — живёт только в памяти процесса. */
    fun grab(): Bitmap? {
        val reader = imageReader ?: return null
        var image: Image? = null
        return try {
            val start = System.currentTimeMillis()
            while (image == null && System.currentTimeMillis() - start < 800) {
                image = reader.acquireLatestImage()
                if (image == null) Thread.sleep(30)
            }
            image ?: return null
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * width
            val full = Bitmap.createBitmap(
                width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888
            )
            full.copyPixelsFromBuffer(buffer)
            // Обрезаем паддинг справа
            Bitmap.createBitmap(full, 0, 0, width, height)
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
