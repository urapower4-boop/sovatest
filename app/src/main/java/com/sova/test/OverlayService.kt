package com.sova.test

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.*
import android.view.*
import android.view.WindowManager.LayoutParams as WMP
import android.widget.*
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

class OverlayService : Service() {

    companion object {
        const val ACTION_STOP = "sova.stop"
        const val CHANNEL_ID = "sova_overlay"
        const val NOTIF_ID = 7701
    }

    private lateinit var wm: WindowManager
    private lateinit var panel: View
    private lateinit var answerView: AnswerOverlayView
    private lateinit var panelParams: WMP
    private lateinit var answerParams: WMP

    private lateinit var btnToggle: Button
    private lateinit var btnClose: Button
    private lateinit var btnMin: Button
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var resizeHandle: View

    private var scanning = false
    private var miniBubble: ImageView? = null

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var scanJob: Job? = null
    private var gemini: GeminiClient? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        gemini = GeminiClient(getString(R.string.gemini_api_key))
        startInForeground()
        buildPanel()
        buildAnswerLayer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        try { wm.removeView(panel) } catch (_: Exception) {}
        try { wm.removeView(answerView) } catch (_: Exception) {}
        miniBubble?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        super.onDestroy()
    }

    // ---------- FOREGROUND ----------
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
            startForeground(
                NOTIF_ID, notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else startForeground(NOTIF_ID, notif)
    }

    // ---------- PANEL ----------
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

        panel.setOnTouchListener(DragListener(panelParams) {
            wm.updateViewLayout(panel, panelParams)
        })

        resizeHandle.setOnTouchListener(ResizeListener(panelParams) {
            wm.updateViewLayout(panel, panelParams)
        })

        log("[готовий] натисни СКАНУВАТИ на сторінці тесту")
    }

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
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = 0 }
        wm.addView(answerView, answerParams)
    }

    // ---------- ACTIONS ----------
    private fun toggleScan() {
        if (scanning) stopScan() else startScan()
    }

    private fun startScan() {
        scanning = true
        btnToggle.text = "■ СТОП"
        btnToggle.backgroundTintList =
            android.content.res.ColorStateList.valueOf(0xFFAA0000.toInt())
        answerView.clear()
        log("[скан] знімаю екран…")

        scanJob = scope.launch {
            try {
                // Пауза: даём оверлею исчезнуть с кадра
                answerView.visibility = View.INVISIBLE
                panel.visibility = View.INVISIBLE
                delay(300)

                val cap = ScreenCaptureService.instance
                val bmp = withContext(Dispatchers.IO) { cap?.grab() }

                panel.visibility = View.VISIBLE
                answerView.visibility = View.VISIBLE

                if (bmp == null) { log("[помилка] нема кадру"); stopScan(); return@launch }

                log("[скан] шлю в Gemini…")
                val res = gemini!!.analyze(bmp)

                if (res.correctIndex < 0) {
                    log("[увага] ${res.explanation.ifEmpty { "не знайшов відповідь" }}")
                } else {
                    log("✅ №${res.correctIndex + 1}: ${res.correctText}")
                    log("💬 ${res.explanation}")
                    res.bbox?.let {
                        answerView.show(
                            Rect(it[0], it[1], it[0] + it[2], it[1] + it[3]),
                            "№${res.correctIndex + 1}"
                        )
                    }
                }
            } catch (e: Exception) {
                log("[err] ${e.message}")
            } finally {
                stopScan()
            }
        }
    }

    private fun stopScan() {
        scanning = false
        scanJob?.cancel()
        btnToggle.text = "● СКАНУВАТИ"
        btnToggle.backgroundTintList =
            android.content.res.ColorStateList.valueOf(0xFF00AA55.toInt())
    }

    private fun minimize() {
        panel.visibility = View.GONE
        answerView.clear()
        if (miniBubble == null) {
            miniBubble = ImageView(this).apply {
                setImageResource(R.drawable.ic_owl)
                setBackgroundColor(0xCC000000.toInt())
                setPadding(16, 16, 16, 16)
            }
            val bp = WMP(
                dp(56), dp(56),
                overlayType(),
                WMP.FLAG_NOT_FOCUSABLE or WMP.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.START; x = panelParams.x; y = panelParams.y }
            wm.addView(miniBubble, bp)
            miniBubble!!.setOnTouchListener(DragListener(bp) {
                wm.updateViewLayout(miniBubble, bp)
            })
            miniBubble!!.setOnClickListener { restore() }
        } else miniBubble!!.visibility = View.VISIBLE
    }

    private fun restore() {
        miniBubble?.visibility = View.GONE
        panel.visibility = View.VISIBLE
    }

    // ---------- HELPERS ----------
    private fun log(msg: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        logView.append("[$ts] $msg\n")
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WMP.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WMP.TYPE_PHONE

    // ---------- TOUCH ----------
    private class DragListener(
        private val p: WMP,
        private val apply: () -> Unit
    ) : View.OnTouchListener {
        private var dx = 0; private var dy = 0
        private var rx = 0f; private var ry = 0f
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { dx = p.x; dy = p.y; rx = e.rawX; ry = e.rawY }
                MotionEvent.ACTION_MOVE -> {
                    p.x = dx + (e.rawX - rx).toInt()
                    p.y = dy + (e.rawY - ry).toInt()
                    apply()
                }
            }
            return false
        }
    }

    private class ResizeListener(
        private val p: WMP,
        private val apply: () -> Unit
    ) : View.OnTouchListener {
        private var startW = 0; private var startH = 0
        private var rx = 0f; private var ry = 0f
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startW = if (p.width > 0) p.width else v.rootView.width
                    startH = if (p.height > 0) p.height else v.rootView.height
                    rx = e.rawX; ry = e.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    p.width = (startW + (e.rawX - rx).toInt()).coerceAtLeast(200)
                    p.height = (startH + (e.rawY - ry).toInt()).coerceAtLeast(150)
                    apply()
                }
            }
            return true
        }
    }
}
