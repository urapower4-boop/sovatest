package com.sova.test

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import android.view.WindowManager.LayoutParams as WMP

class OverlayService : Service() {

    companion object {
        const val ACTION_STOP = "sova.stop"
        const val CHANNEL_ID = "sova_overlay"
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
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        return START_STICKY
    }

    // ---------- Foreground ----------
    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "SovaTest", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stopPI = PendingIntent.getService(
            this, 0,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🦉 SovaTest активна")
            .setContentText("Оверлей працює")
            .setSmallIcon(R.drawable.ic_owl)
            .addAction(0, "Стоп", stopPI)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    // ---------- Answer layer (прозрачный слой с рамкой) ----------
    private fun buildAnswerLayer() {
        answerView = AnswerOverlayView(this)
        answerParams = WMP(
            WMP.MATCH_PARENT, WMP.MATCH_PARENT,
            overlayType(),
            WMP.FLAG_NOT_FOCUSABLE or
                WMP.FLAG_NOT_TOUCHABLE or
                WMP.FLAG_LAYOUT_IN_SCREEN or
                WMP.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        wm.addView(answerView, answerParams)
    }

    // ---------- Panel ----------
    private fun buildPanel() {
        panel = LayoutInflater.from(this).inflate(R.layout.overlay_panel, null)

        btnToggle = panel.findViewById(R.id.btnToggle)
        btnClose = panel.findViewById(R.id.btnClose)
        btnMin = panel.findViewById(R.id.btnMin)
        logView = panel.findViewById(R.id.log)
        logScroll = panel.findViewById(R.id.logScroll)
        resizeHandle = panel.findViewById(R.id.resizeHandle)

        panelParams = WMP(
            dp(260), WMP.WRAP_CONTENT,
            overlayType(),
            WMP.FLAG_NOT_FOCUSABLE or WMP.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40; y = 200
        }
        wm.addView(panel, panelParams)

        btnClose.setOnClickListener { stopSelf() }
        btnMin.setOnClickListener { minimize() }
        btnToggle.setOnClickListener { toggleScan() }

        attachDrag(panel, panelParams)
        attachResize(resizeHandle, panel, panelParams)
    }

    // ---------- Drag & Resize ----------
    private fun attachDrag(view: View, lp: WMP) {
        var startX = 0; var startY = 0
        var touchX = 0f; var touchY = 0f
        view.setOnTouchListener { v, e ->
            // drag не запускаем если палец на кнопке
            if (e.action == MotionEvent.ACTION_DOWN) {
                val inButton = hitButton(e.rawX, e.rawY)
                if (inButton) return@setOnTouchListener false
            }
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x; startY = lp.y
                    touchX = e.rawX; touchY = e.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = startX + (e.rawX - touchX).toInt()
                    lp.y = startY + (e.rawY - touchY).toInt()
                    wm.updateViewLayout(panel, lp)
                    true
                }
                else -> false
            }
        }
    }

    private fun hitButton(rx: Float, ry: Float): Boolean {
        for (b in listOf<View>(btnToggle, btnClose, btnMin)) {
            val loc = IntArray(2); b.getLocationOnScreen(loc)
            val r = Rect(loc[0], loc[1], loc[0] + b.width, loc[1] + b.height)
            if (r.contains(rx.toInt(), ry.toInt())) return true
        }
        return false
    }

    private fun attachResize(handle: View, target: View, lp: WMP) {
        var startW = 0; var startH = 0
        var touchX = 0f; var touchY = 0f
        handle.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startW = target.width
                    startH = target.height
                    touchX = e.rawX; touchY = e.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val nw = (startW + (e.rawX - touchX)).toInt().coerceAtLeast(dp(180))
                    val nh = (startH + (e.rawY - touchY)).toInt().coerceAtLeast(dp(160))
                    lp.width = nw
                    lp.height = nh
                    wm.updateViewLayout(panel, lp)
                    true
                }
                else -> false
            }
        }
    }

    // ---------- Minimize ----------
    private fun minimize() {
        if (miniBubble != null) return
        panel.visibility = View.GONE

        val bubble = ImageView(this).apply {
            setImageResource(R.drawable.ic_owl)
            setBackgroundColor(0xCC000000.toInt())
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val lp = WMP(
            dp(56), dp(56),
            overlayType(),
            WMP.FLAG_NOT_FOCUSABLE or WMP.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = panelParams.x; y = panelParams.y
        }
        wm.addView(bubble, lp)

        var sx = 0; var sy = 0; var tx = 0f; var ty = 0f; var moved = false
        bubble.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = lp.x; sy = lp.y; tx = e.rawX; ty = e.rawY; moved = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = sx + (e.rawX - tx).toInt()
                    lp.y = sy + (e.rawY - ty).toInt()
                    if (Math.abs(e.rawX - tx) > 10 || Math.abs(e.rawY - ty) > 10) moved = true
                    wm.updateViewLayout(bubble, lp); true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        panelParams.x = lp.x; panelParams.y = lp.y
                        wm.removeView(bubble); miniBubble = null
                        panel.visibility = View.VISIBLE
                        wm.updateViewLayout(panel, panelParams)
                    }
                    true
                }
                else -> false
            }
        }
        miniBubble = bubble
    }

    // ---------- Scan loop ----------
    private fun toggleScan() {
        if (scanning) {
            scanning = false
            scanJob?.cancel()
            btnToggle.text = "● СКАНУВАТИ"
            btnToggle.setBackgroundColor(0xFF00AA55.toInt())
            log("[стоп]")
        } else {
            scanning = true
            btnToggle.text = "■ ЗУПИНИТ
