package com.kaan.auroramobile

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.content.res.ColorStateList
import android.widget.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.abs

/** User-started, draggable overlay. All network actions reuse the PC-paired session. */
class FloatingAssistantService : Service() {
    private lateinit var wm: WindowManager
    private val io = Executors.newSingleThreadExecutor()
    private var bubble: TextView? = null
    private var panel: LinearLayout? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var expanded = false
    private var transcriptView: TextView? = null
    private var lastSeq = 0
    private var pollRunning = false
    private val prefs by lazy { getSharedPreferences(MainActivity.SESSION_PREFS, MODE_PRIVATE) }

    override fun onCreate() {
        super.onCreate()
        AuroraLog.write(this, "overlay.service.start")
        startForeground(NOTIFICATION_ID, makeNotification())
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        createBubble()
    }

    private fun makeNotification(): Notification {
        val channel = "aurora_overlay"
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(channel, "AURORA floating assistant", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, FloatingAssistantService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, channel).setContentTitle("AURORA is ready").setContentText("Floating assistant is active")
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentIntent(open).addAction(android.app.Notification.Action.Builder(null, "Stop bubble", stop).build()).setOngoing(true).build()
    }

    private fun lp(width: Int, height: Int, gravity: Int = Gravity.TOP or Gravity.START) = WindowManager.LayoutParams(
        width, height, if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply { this.gravity = gravity }

    private fun createBubble() {
        val b = TextView(this).apply {
            text = "Au"; textSize = 19f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setTextColor(Color.rgb(190, 250, 255)); background = shape(Color.rgb(4, 33, 51), Color.rgb(67, 225, 255), 100)
            elevation = dp(10).toFloat(); contentDescription = "AURORA floating assistant. Tap to open, drag to move."
        }
        val p = lp(dp(58), dp(58)).apply { x = dp(16); y = dp(180) }
        var sx = 0f; var sy = 0f; var startX = 0; var startY = 0; var dragged = false
        b.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; startX = p.x; startY = p.y; dragged = false; true }
                MotionEvent.ACTION_MOVE -> { val dx = (e.rawX - sx).toInt(); val dy = (e.rawY - sy).toInt(); if (abs(dx) + abs(dy) > dp(5)) dragged = true; p.x = (startX + dx).coerceAtLeast(0); p.y = (startY + dy).coerceAtLeast(0); runCatching { wm.updateViewLayout(b, p) }; true }
                MotionEvent.ACTION_UP -> { if (!dragged) togglePanel(); true }
                else -> true
            }
        }
        bubble = b; bubbleParams = p
        runCatching { wm.addView(b, p) }.onFailure { AuroraLog.write(this, "overlay.attach.error", it.javaClass.simpleName); stopSelf() }
    }

    private fun togglePanel() { if (expanded) closePanel() else openPanel() }
    private fun openPanel() {
        if (expanded) return
        val base = prefs.getString("baseUrl", "").orEmpty(); val session = prefs.getString("session", "").orEmpty()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12)); background = shape(Color.rgb(3, 15, 27), Color.rgb(46, 190, 220), 22); elevation = dp(16).toFloat() }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; orientation = LinearLayout.HORIZONTAL }
        val title = TextView(this).apply { text = "AURORA // MINI"; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.rgb(180, 245, 255)) }
        val close = TextView(this).apply { text = "  ×  "; textSize = 24f; setTextColor(Color.WHITE); gravity = Gravity.CENTER }
        header.addView(title, LinearLayout.LayoutParams(0, dp(38), 1f)); header.addView(close, LinearLayout.LayoutParams(dp(40), dp(38))); box.addView(header)
        val transcript = TextView(this).apply { text = if (session.isBlank()) "Önce ana uygulamadan PC'yi eşleştir." else "PC bağlantısı hazır. Komutunu yaz."; setTextColor(Color.rgb(210, 235, 245)); textSize = 13f; setPadding(dp(8), dp(8), dp(8), dp(8)); maxLines = 5 }
        transcriptView = transcript
        box.addView(transcript, LinearLayout.LayoutParams(-1, dp(104)))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val input = EditText(this).apply { hint = "Komut yaz…"; textSize = 13f; setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); setSingleLine(true); setPadding(dp(8), 0, dp(8), 0); background = shape(Color.rgb(12, 32, 45), Color.rgb(30, 85, 105), 12) }
        val send = Button(this).apply { text = "GÖNDER"; textSize = 10f; setTextColor(Color.WHITE); backgroundTintList = ColorStateList.valueOf(Color.rgb(0, 92, 118)) }
        row.addView(input, LinearLayout.LayoutParams(0, dp(48), 1f)); row.addView(send, LinearLayout.LayoutParams(dp(86), dp(48))); box.addView(row)
        send.setOnClickListener {
            val text = input.text.toString().trim(); if (text.isBlank()) return@setOnClickListener
            if (base.isBlank() || session.isBlank()) { transcript.text = "PC eşleştirmesi bulunamadı. Ana uygulamayı açıp QR ile eşleştir."; return@setOnClickListener }
            send.isEnabled = false; transcript.text = "Gönderiliyor…"; input.setText("")
            io.execute {
                val result = runCatching {
                    val url = URL("$base/api/message")
                    require(isPrivateLanHost(url.host)) { "LAN-only connection blocked" }
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"; conn.connectTimeout = 3500; conn.readTimeout = 5500; conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    conn.outputStream.use { it.write(JSONObject().put("session", session).put("text", text).toString().toByteArray(Charsets.UTF_8)) }
                    val code = conn.responseCode; val body = (if (code in 200..299) conn.inputStream else conn.errorStream).bufferedReader().use { it.readText() }
                    conn.disconnect()
                    if (code !in 200..299 || !JSONObject(body).optBoolean("accepted")) error("PC isteği kabul etmedi (HTTP $code)")
                    AuroraLog.write(this@FloatingAssistantService, "overlay.command.sent", "accepted by desktop endpoint")
                    "Komut gönderildi. PC yanıtı burada görünecek."
                }.getOrElse { AuroraLog.write(this@FloatingAssistantService, "overlay.command.error", it.javaClass.simpleName); "Gönderilemedi: ${it.message ?: "bağlantı hatası"}" }
                android.os.Handler(mainLooper).post { transcript.text = result; send.isEnabled = true }
            }
        }
        close.setOnClickListener { closePanel() }
        val panelWidth = (resources.displayMetrics.widthPixels - dp(24)).coerceAtLeast(dp(240))
        val p = lp(panelWidth, WindowManager.LayoutParams.WRAP_CONTENT).apply {
            val bp = bubbleParams; x = (bp?.x ?: dp(12)).coerceIn(0, (resources.displayMetrics.widthPixels - panelWidth).coerceAtLeast(0)); y = ((bp?.y ?: dp(180)) - dp(250)).coerceAtLeast(dp(30))
            flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        }
        panel = box; panelParams = p
        runCatching { wm.addView(box, p); expanded = true; startEventPolling() }.onFailure {
            AuroraLog.write(this, "overlay.panel.attach.error", it.javaClass.simpleName)
            panel = null; panelParams = null; transcriptView = null; expanded = false
        }
    }

    private fun startEventPolling() {
        if (pollRunning) return
        lastSeq = prefs.getInt("overlayLastSeq", 0)
        pollRunning = true
        fun poll() {
            if (!expanded || !pollRunning) return
            val base = prefs.getString("baseUrl", "").orEmpty()
            val session = prefs.getString("session", "").orEmpty()
            if (base.isBlank() || session.isBlank()) {
                android.os.Handler(mainLooper).post { transcriptView?.text = "PC eşleştirmesi yok. Ana uygulamadan QR ile bağlan." }
                android.os.Handler(mainLooper).postDelayed({ poll() }, 1800)
                return
            }
            io.execute {
                val update = runCatching {
                    val target = URL("$base/api/events?session=${android.net.Uri.encode(session)}&since=$lastSeq")
                    require(isPrivateLanHost(target.host)) { "LAN-only connection blocked" }
                    val c = target.openConnection() as HttpURLConnection
                    c.requestMethod = "GET"; c.connectTimeout = 2500; c.readTimeout = 3000
                    c.setRequestProperty("Accept", "application/json")
                    val code = c.responseCode
                    if (code !in 200..299) error("HTTP $code")
                    val json = c.inputStream.bufferedReader().use { it.readText() }
                    c.disconnect()
                    val arr = JSONObject(json).optJSONArray("messages")
                    val received = mutableListOf<String>()
                    if (arr != null) for (i in 0 until arr.length()) {
                        val item = arr.getJSONObject(i)
                        val seq = item.optInt("seq")
                        if (seq > lastSeq) {
                            lastSeq = maxOf(lastSeq, seq)
                            prefs.edit().putInt("overlayLastSeq", lastSeq).apply()
                            val role = item.optString("role", "assistant").uppercase()
                            val content = item.optString("content").take(400)
                            if (content.isNotBlank()) received.add("$role: $content")
                        }
                    }
                    received
                }.getOrElse {
                    AuroraLog.write(this@FloatingAssistantService, "overlay.events.error", it.javaClass.simpleName)
                    emptyList()
                }
                android.os.Handler(mainLooper).post {
                    if (expanded && update.isNotEmpty()) {
                        val existing = transcriptView?.text?.toString().orEmpty()
                        val fresh = update.joinToString("\n")
                        val combined = (if (existing.startsWith("PC bağlantısı hazır") || existing.startsWith("Komut gönderildi")) "" else existing + "\n") + fresh
                        transcriptView?.text = combined.lines().takeLast(6).joinToString("\n")
                    }
                    if (expanded && pollRunning) android.os.Handler(mainLooper).postDelayed({ poll() }, 1000)
                }
            }
        }
        poll()
    }

    private fun isPrivateLanHost(host: String): Boolean {
        return try {
            // Accept only literal private IPv4 addresses from the pairing QR.
            if (!host.matches(Regex("^(?:[0-9]{1,3}\\.){3}[0-9]{1,3}$"))) false
            else {
                val parts = host.split(".").map { it.toIntOrNull() ?: return false }
                if (parts.any { it !in 0..255 }) false
                else parts[0] == 10 || (parts[0] == 172 && parts[1] in 16..31) ||
                    (parts[0] == 192 && parts[1] == 168)
            }
        } catch (_: Exception) { false }
    }

    private fun closePanel() { pollRunning = false; panel?.let { runCatching { wm.removeView(it) } }; panel = null; panelParams = null; transcriptView = null; expanded = false }
    private fun shape(fill: Int, stroke: Int, radius: Int) = GradientDrawable().apply { setColor(fill); setStroke(dp(1), stroke); cornerRadius = dp(radius).toFloat() }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { AuroraLog.write(this, "overlay.stop"); stopSelf(); return START_NOT_STICKY }
        return START_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { closePanel(); bubble?.let { runCatching { wm.removeView(it) } }; io.shutdownNow(); super.onDestroy() }
    companion object { const val NOTIFICATION_ID = 4217; const val ACTION_STOP = "com.kaan.auroramobile.STOP_OVERLAY" }
}
