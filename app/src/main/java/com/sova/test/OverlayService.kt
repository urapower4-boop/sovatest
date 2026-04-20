package com.sova.test

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import android.view.WindowManager.LayoutParams as WMP

class OverlayService : Service() {

    companion object {
        const val ACTION_STOP = "sova.stop"
        const val CH_ID = "sova_overlay"
        const val NOTIF_ID = 7701
    }

    private lateinit var wm: WindowManager

    private lateinit var panel: View
    private lateinit var panelParams: WMP

    private lateinit var answerView: AnswerOverlayView
    private lateinit var answerParams: WMP

    private lateinit var btnToggle: Button
    private lateinit var btnClose: Button
    private lateinit var btnMin: Button
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var resizeHandle: View

    private var scanning = false
    private var miniBubble: View? = null

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var scanJob: Job? = null
    private var gemini: GeminiClient? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        gemini = GeminiClient(getString(R.string.gemini_api_key))
        startInForeground()
        buildAnswerLayer()
        buildPanel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CH_ID, "SovaTest", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stopPI = PendingIntent.getService(
            this, 0,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notif = NotificationCompat.Builder(this, CH_ID)
            .setContentTitle("🦉 SovaTest активна")
            .setContentText("Оверлей працює")
            .setSmallIcon(R.dr
