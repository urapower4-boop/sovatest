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
    private var minimized = false
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
        startForeground(NOTIF_ID, notif)
    }

    // ---------- PANEL ----------
    private fun buildPanel() {
        panel = LayoutInflater.from(this).inflate(R.layout.overlay_panel, null)

        btnToggle = panel.findViewById(R.id.btnToggle)
        btnClose = panel.findViewById(R.id.btnClose)
        btnMin = panel.findViewById(R.id.btnMin)
        logView = panel.findViewById(R.id.logView)
        logScroll = panel.findViewById(R.id.logScroll)
        resizeHandle = panel.findViewById(R.id.resizeHandle)

        panelParams = WMP(
            WMP.WRAP_CONTENT, WMP.WRAP_CONTENT,
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

        // Drag по шапке (весь panel кроме кнопок — но проще: весь panel)
        panel.setOnTouchListener(DragListener(panelParams) { wm.updateViewLayout(panel, panelParams) })

        // Resize уголком
        resizeHandle.setOnTouchListener(ResizeListener(panel, panelParams) {
            wm.updateViewLayout(panel, panelParams)
        })

        log("🦉 Сова готова. Натисни СКАНУВАТИ на питанні тесту.")
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
        )
        wm.addView(answerView, answerParams)
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WMP.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WMP.TYPE_PHONE

    // ---------- MINIMIZE ----------
    private fun minimize() {
        if (minimized) return
        minimized = true
        panel.visibility = View.GONE
        answerView.clear()

        val bubble = ImageView(this).apply {
            setImageResource(R.drawable.ic_owl)
            setBackgroundColor(0x99000000.toInt())
        }
        val bp = WMP(
            140, 140,
            overlayType(),
            WMP.FLAG_NOT_FOCUSABLE or WMP.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 20; y = 300 }

        bubble.setOnTouchListener(DragListener(bp) { wm.updateViewLayout(bubble, bp) })
        bubble.setOnClickListener { restore() }

        wm.addView(bubble, bp)
        miniBubble = bubble
    }

    private fun restore() {
        miniBubble?.let { wm.removeView(it) }
        miniBubble = null
        minimized = false
        panel.visibility = View.VISIBLE
    }

    // ---------- SCAN ----------
    private fun toggleScan() {
        if (scanning) stopScan() else startScan()
    }

    private fun startScan() {
        if (ScreenCaptureService.imageReader == null) {
            log("⛔ Немає дозволу на захоплення екрану. Перезапусти ПУСК.")
            return
        }
        scanning = true
        btnToggle.text = "■ СТОП"
        btnToggle.backgroundTintList =
            android.content.res.ColorStateList.valueOf(0xFFAA0000.toInt())
        log("🔍 Скануємо...")

        // Прячем нашу рамку перед скрином, иначе попадёт в кадр
        answerView.clear()

        scanJob = scope.launch {
            delay(200) // дать отрисоваться "чистому" состоянию
            val bmp = withContext(Dispatchers.IO) { ScreenCaptureService.captureNow() }
            if (bmp == null) {
                log("⚠ Не вдалось зробити скрін.")
                stopScan()
                return@launch
            }
            log("📸 Скрін ${bmp.width}x${bmp.height}. Надсилаю у Gemini...")

            val g = gemini ?: return@launch
            val res = try { g.analyze(bmp) } catch (e: Exception) {
                log("❌ Gemini помилка: ${e.message}"); stopScan(); return@launch
            }

            when {
                res.correctIndex == -1 && res.explanation == "no_test" ->
                    log("🤷 На скріні немає тесту.")
                res.correctIndex == -1 ->
                    log("❌ ${res.explanation}")
                else -> {
                    log("✅ ${res.correctText}")
                    if (res.explanation.isNotBlank())
                        log("ℹ ${res.explanation}")
                    res.bbox?.let { drawBox(it, bmp.width, bmp.height, res.correctText) }
                        ?: log("ℹ Координати не визначені — обведення не буде.")
                }
            }
            stopScan()
        }
    }

    private fun stopScan() {
        scanning = false
        btnToggle.text = "● СКАНУВАТИ"
        btnToggle.backgroundTintList =
            android.content.res.ColorStateList.valueOf(0xFF00AA55.toInt())
        scanJob?.cancel()
    }

    private fun drawBox(bbox: IntArray, srcW: Int, srcH: Int, label: String) {
        // Gemini получал уменьшенный до 1280 скрин — но и координаты он даёт в ЕГО системе.
        // Поэтому пересчёт: сначала поняли реальный размер экрана, потом маппим.
        val dm = resources.displayMetrics
        val scaleX = dm.widthPixels.toFloat() / srcW
        val scaleY = dm.heightPixels.toFloat() / srcH

        // bbox модель давала относительно уменьшенного в GeminiClient (1280 по ширине).
        // Но мы не знаем, ужала она или нет — проще считать, что bbox в координатах bmp (srcW x srcH).
        // Если GeminiClient ужимал перед отправкой — скорректируем там же (см. ниже).
        val x = (bbox[0] * scaleX).toInt()
        val y = (bbox[1] * scaleY).toInt()
        val w = (bbox[2] * scaleX).toInt()
        val h = (bbox[3] * scaleY).toInt()

        answerView.show(Rect(x, y, x + w, y + h), "✓ $label")
    }

    // ---------- LOG ----------
    private fun log(msg: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        logView.append("[$t] $msg\n")
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        try { wm.removeView(panel) } catch (_: Exception) {}
        try { wm.removeView(answerView) } catch (_: Exception) {}
        miniBubble?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        ScreenCaptureService.stopCapture()
    }

    // ---------- helpers: drag & resize ----------
    private class DragListener(
        private val p: WMP,
        private val onUpdate: () -> Unit
    ) : View.OnTouchListener {
        private var ix = 0; private var iy = 0
        private var tx = 0f; private var ty = 0f
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    ix = p.x; iy = p.y; tx = e.rawX; ty = e.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    p.x = ix + (e.rawX - tx).toInt()
                    p.y = iy + (e.rawY - ty).toInt()
                    onUpdate()
                }
                MotionEvent.ACTION_UP -> {
                    if (Math.abs(e.rawX - tx) < 10 && Math.abs(e.rawY - ty) < 10) v.performClick()
                }
            }
            return true
        }
    }

    private class ResizeListener(
        private val target: View,
        private val p: WMP,
        private val onUpdate: () -> Unit
    ) : View.OnTouchListener {
        private var iw = 0; private var ih = 0
        private var tx = 0f; private var ty = 0f
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    iw = target.width; ih = target.height
                    tx = e.rawX; ty = e.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    val nw = (iw + (e.rawX - tx)).toInt().coerceAtLeast(220)
                    val nh = (ih + (e.rawY - ty)).toInt().coerceAtLeast(200)
                    p.width = nw; p.height = nh
                    onUpdate()
                }
            }
            return true
        }
    }
}
