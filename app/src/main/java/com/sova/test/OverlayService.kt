package com.sova.test

import android.app.*
import android.content.Intent
import android.graphics.PixelFormat
import android.os.*
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import android.view.WindowManager.LayoutParams as WMP

class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var panel: View

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        panel = LayoutInflater.from(this).inflate(R.layout.overlay_panel, null)
        val params = WMP(WMP.WRAP_CONTENT, WMP.WRAP_CONTENT, 
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WMP.TYPE_APPLICATION_OVERLAY else WMP.TYPE_PHONE,
            WMP.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT)
        wm.addView(panel, params)
        
        startForeground(7701, NotificationCompat.Builder(this, "sova").setSmallIcon(R.drawable.ic_owl).setContentTitle("SovaTest").build())
    }

    override fun onDestroy() {
        wm.removeView(panel)
        super.onDestroy()
    }
}
