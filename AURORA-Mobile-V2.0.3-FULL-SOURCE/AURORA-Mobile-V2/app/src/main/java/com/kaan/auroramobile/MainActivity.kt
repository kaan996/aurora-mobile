package com.kaan.auroramobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.content.ActivityNotFoundException
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var core: AuroraCoreView
    private lateinit var messages: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var scanButton: Button
    private lateinit var sendButton: Button
    private lateinit var pttButton: Button
    private lateinit var bubbleButton: Button
    private lateinit var quickRow: LinearLayout
    private var baseUrl = ""
    private var session = ""
    private var pairId = ""
    private var lastSeq = 0
    private var pollGeneration = 0
    private var speech: SpeechRecognizer? = null
    private var waitingForOverlayPermission = false
    private var overlayMarkedActive = false
    private var pendingUpdateUri: Uri? = null
    private val updateApkPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            pendingUpdateUri = uri
            installSelectedUpdateIfAllowed()
        }
    }

    private val scanner = registerForActivityResult(ScanContract()) { result ->
        val text = result.contents ?: return@registerForActivityResult
        beginPairing(text)
    }
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) openScanner() else toast("Camera permission is required for QR pairing.") }
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) startPtt() else toast("Microphone permission is required for PTT.") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AuroraLog.write(this, "app.start", "version=2.0.3")
        buildUi()
        restoreSession()
        intent?.data?.toString()?.takeIf { it.startsWith("jarvis://pair") }?.let(::beginPairing)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.toString()?.takeIf { it.startsWith("jarvis://pair") }?.let(::beginPairing)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(Color.rgb(1, 6, 12))
        }
        val title = TextView(this).apply { text = "AURORA MOBILE // V2"; setTextColor(Color.rgb(191, 245, 255)); textSize = 18f; letterSpacing = .13f }
        status = TextView(this).apply { text = "OFFLINE"; setTextColor(Color.rgb(93, 255, 199)); textSize = 11f; gravity = Gravity.END }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; addView(title, LinearLayout.LayoutParams(0, dp(42), 1f)); addView(status, LinearLayout.LayoutParams(dp(120), dp(42))) }
        root.addView(head)
        core = AuroraCoreView(this)
        root.addView(core, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(230)))
        scanButton = button("PC QR OKUT") { ensureCamera() }
        root.addView(scanButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)))
        val toolsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, dp(6)) }
        bubbleButton = button("YÜZEN BALON") { toggleFloatingAssistant() }
        val settingsButton = button("AYARLAR / GÜNCELLEME") { showSettingsAndUpdate() }
        toolsRow.addView(bubbleButton, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(5) })
        toolsRow.addView(settingsButton, LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(5) })
        root.addView(toolsRow)
        scroll = ScrollView(this).apply { setBackgroundColor(Color.rgb(2, 13, 23)) }
        messages = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(10), dp(10), dp(10)) }
        scroll.addView(messages)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        quickRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; visibility = View.GONE }
        listOf("FILM" to "Film zamanı", "VOL+" to "TV sesini yükselt", "VOL−" to "TV sesini azalt", "HOME" to "TV ana ekrana dön").forEach { (label, command) ->
            quickRow.addView(button(label) { sendCommand(command) }, LinearLayout.LayoutParams(0, dp(42), 1f))
        }
        root.addView(quickRow)
        val composer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        input = EditText(this).apply {
            hint = "AURORA'ya yaz…"; setHintTextColor(Color.rgb(72, 125, 143)); setTextColor(Color.WHITE); setBackgroundColor(Color.rgb(3, 22, 36)); setPadding(dp(12), 0, dp(12), 0); imeOptions = EditorInfo.IME_ACTION_SEND; isSingleLine = true
            setOnEditorActionListener { _, actionId, _ -> if (actionId == EditorInfo.IME_ACTION_SEND) { sendFromInput(); true } else false }
        }
        pttButton = button("PTT") { ensureMic() }
        sendButton = button("SEND") { sendFromInput() }
        composer.addView(input, LinearLayout.LayoutParams(0, dp(52), 1f))
        composer.addView(pttButton, LinearLayout.LayoutParams(dp(70), dp(52)))
        composer.addView(sendButton, LinearLayout.LayoutParams(dp(78), dp(52)))
        root.addView(composer)
        setContentView(root)
        setConnectedUi(false)
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; setTextColor(Color.rgb(183, 242, 255)); setBackgroundColor(Color.rgb(4, 42, 62)); textSize = 10f; setOnClickListener { onClick() }
    }

    private fun toggleFloatingAssistant() {
        if (overlayMarkedActive) {
            stopService(Intent(this, FloatingAssistantService::class.java).setAction(FloatingAssistantService.ACTION_STOP))
            overlayMarkedActive = false
            bubbleButton.text = "YÜZEN BALON"
            AuroraLog.write(this, "overlay.stop.requested", "stopped from main screen")
            toast("Yüzen balon durduruldu.")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            waitingForOverlayPermission = true
            toast("Yüzen balon için diğer uygulamaların üzerinde gösterme iznini aç.")
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        startFloatingAssistant()
    }

    private fun startFloatingAssistant() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            waitingForOverlayPermission = true
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        waitingForOverlayPermission = false
        AuroraLog.write(this, "overlay.start", "user enabled floating assistant")
        val intent = Intent(this, FloatingAssistantService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        overlayMarkedActive = true
        bubbleButton.text = "BALONU DURDUR"
        toast("AURORA balonu başlatılıyor.")
    }

    override fun onResume() {
        super.onResume()
        if (waitingForOverlayPermission) {
            waitingForOverlayPermission = false
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) startFloatingAssistant()
            else toast("İzin verilmedi; balon başlatılmadı.")
        }
        if (pendingUpdateUri != null && (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls())) {
            installSelectedUpdateIfAllowed()
        }
    }

    private fun showSettingsAndUpdate() {
        val version = packageManager.getPackageInfo(packageName, 0).versionName ?: "2.0"
        AuroraLog.write(this, "settings.open", "update settings viewed")
        val text = "AURORA Mobile V2\nSürüm: $version\n\nGüncelleme modu: otomatik sürüm sunucusu henüz yapılandırılmadı. Şimdilik güvenilir kaynaktan aldığın tam APK dosyasını seçerek Android'in resmi kurulum ekranını açabilirsin. APK aynı uygulama imzasıyla oluşturulmuş olmalı.\n\nKurulumdan önce sürümün ve kaynağın doğru olduğundan emin ol. Android kurulum onayı isteyecektir."
        android.app.AlertDialog.Builder(this).setTitle("AURORA // AYARLAR VE GÜNCELLEME").setMessage(text)
            .setPositiveButton("APK DOSYASI SEÇ") { _, _ -> updateApkPicker.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream")) }
            .setNeutralButton(if (overlayMarkedActive) "BALONU DURDUR" else "BALONU BAŞLAT") { _, _ -> toggleFloatingAssistant() }
            .setNegativeButton("KAPAT", null).show()
    }

    private fun installSelectedUpdateIfAllowed() {
        val uri = pendingUpdateUri ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            toast("APK kurulumu için AURORA'ya bu izin verilmeli.")
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        try {
            val install = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(install)
            AuroraLog.write(this, "update.installer.open", "user selected local APK; Android installer opened")
            pendingUpdateUri = null
        } catch (e: ActivityNotFoundException) {
            AuroraLog.write(this, "update.installer.error", e.javaClass.simpleName)
            toast("Bu cihazda APK kurulum ekranı açılamadı.")
        } catch (e: SecurityException) {
            AuroraLog.write(this, "update.installer.error", e.javaClass.simpleName)
            toast("APK dosyasına erişilemiyor. Dosyayı tekrar seç.")
        }
    }

    private fun beginPairing(payload: String) {
        // Invalidate old polling before accepting a new QR; otherwise old and new sessions can race.
        pollGeneration++
        pairId = ""
        session = ""
        lastSeq = 0
        getSharedPreferences(SESSION_PREFS, MODE_PRIVATE).edit()
            .remove("session").remove("lastSeq").apply()
        setConnectedUi(false)
        try {
            val uri = Uri.parse(payload.trim())
            require(uri.scheme == "jarvis" && uri.host == "pair") { "Invalid JARVIS QR" }
            val host = uri.getQueryParameter("host") ?: error("Missing host")
            val port = uri.getQueryParameter("port")?.toIntOrNull() ?: error("Missing port")
            val token = uri.getQueryParameter("token") ?: error("Missing token")
            require(port in 1..65535) { "Invalid PC port" }
            require(isPrivateLanHost(host)) { "PC address must be a private IPv4 LAN address" }
            baseUrl = "http://$host:$port"
            AuroraLog.write(this, "pair.request", "private LAN pairing initiated")
            status("PAIRING // PC APPROVAL")
            io.execute {
                try {
                    val body = JSONObject().put("token", token).put("deviceName", android.os.Build.MODEL ?: "Android Phone")
                    val response = request("POST", "$baseUrl/api/pair-request", body)
                    pairId = response.optString("id")
                    if (pairId.isBlank()) error(response.optString("error", "Pair request rejected"))
                    ui.post { addMessage("system", "Pair request sent. Approve this phone on the PC.") }
                    pollPairing()
                } catch (e: Exception) { ui.post { fail("Eşleştirme başarısız: ${e.message}") } }
            }
        } catch (e: Exception) { fail(e.message ?: "Invalid QR") }
    }

    private fun pollPairing() {
        val generation = ++pollGeneration
        fun next() {
            if (generation != pollGeneration || pairId.isBlank()) return
            io.execute {
                try {
                    val j = request("GET", "$baseUrl/api/pair-status?id=${Uri.encode(pairId)}")
                    if (j.optString("status") == "connected") {
                        session = j.optString("session")
                        if (session.isBlank()) error("Session missing")
                        getSharedPreferences(SESSION_PREFS, MODE_PRIVATE).edit().putString("baseUrl", baseUrl).putString("session", session).putInt("lastSeq", lastSeq).apply()
                        AuroraLog.write(this@MainActivity, "pair.connected", "desktop session established")
                        ui.post { status("CONNECTED"); setConnectedUi(true); addMessage("system", "AURORA CONNECTED // PC JARVIS online."); pollEvents() }
                    } else ui.postDelayed({ next() }, 850)
                } catch (_: Exception) { ui.postDelayed({ next() }, 1100) }
            }
        }
        next()
    }

    private fun pollEvents() {
        val generation = ++pollGeneration
        fun next() {
            if (generation != pollGeneration || session.isBlank()) return
            io.execute {
                try {
                    val j = request("GET", "$baseUrl/api/events?session=${Uri.encode(session)}&since=$lastSeq")
                    val arr = j.optJSONArray("messages")
                    if (arr != null) for (i in 0 until arr.length()) {
                        val item = arr.getJSONObject(i)
                        val seq = item.optInt("seq")
                        lastSeq = maxOf(lastSeq, seq)
                        val role = item.optString("role", "system")
                        val content = item.optString("content")
                        ui.post { addMessage(role, content) }
                    }
                    getSharedPreferences(SESSION_PREFS, MODE_PRIVATE).edit().putInt("lastSeq", lastSeq).apply()
                    ui.postDelayed({ next() }, 850)
                } catch (e: Exception) {
                    ui.post { status("RECONNECTING") }
                    ui.postDelayed({ next() }, 1400)
                }
            }
        }
        next()
    }

    private fun restoreSession() {
        val prefs = getSharedPreferences(SESSION_PREFS, MODE_PRIVATE)
        baseUrl = prefs.getString("baseUrl", "") ?: ""
        session = prefs.getString("session", "") ?: ""
        lastSeq = prefs.getInt("lastSeq", 0)
        if (baseUrl.isBlank() || session.isBlank()) return
        status("RECONNECTING")
        setConnectedUi(true)
        pollEvents()
    }

    private fun sendFromInput() { val text = input.text.toString().trim(); if (text.isNotEmpty()) { input.setText(""); addMessage("user", text); sendCommand(text) } }
    private fun sendCommand(text: String) {
        if (session.isBlank()) { toast("Önce PC ile QR üzerinden eşleştir."); return }
        io.execute {
            try { val j = request("POST", "$baseUrl/api/message", JSONObject().put("session", session).put("text", text)); if (!j.optBoolean("accepted")) error(j.optString("error", "Rejected")) }
            catch (e: Exception) { AuroraLog.write(this@MainActivity, "message.error", e.javaClass.simpleName); ui.post { addMessage("system", "GÖNDERİLEMEDİ // ${e.message}") } }
        }
    }

    private fun ensureCamera() { if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) openScanner() else cameraPermission.launch(Manifest.permission.CAMERA) }
    private fun openScanner() { scanner.launch(ScanOptions().setPrompt("Bilgisayardaki AURORA QR kodunu okut").setBeepEnabled(false).setOrientationLocked(true).setDesiredBarcodeFormats(ScanOptions.QR_CODE)) }
    private fun ensureMic() { if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startPtt() else micPermission.launch(Manifest.permission.RECORD_AUDIO) }
    private fun startPtt() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { toast("Speech recognition is not available on this phone."); return }
        speech?.destroy(); speech = SpeechRecognizer.createSpeechRecognizer(this)
        speech?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { pttButton.text = "LISTEN"; status("LISTENING") }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { pttButton.text = "PTT" }
            override fun onError(error: Int) { pttButton.text = "PTT"; status(if (session.isBlank()) "OFFLINE" else "CONNECTED") }
            override fun onResults(results: Bundle?) { val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty(); pttButton.text = "PTT"; status("CONNECTED"); if (text.isNotBlank()) { addMessage("user", text); sendCommand(text) } }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply { putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM); putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR"); putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false) }
        speech?.startListening(intent)
    }

    private fun request(method: String, url: String, body: JSONObject? = null): JSONObject {
        val target = URL(url)
        require(isPrivateLanHost(target.host)) { "Non-LAN address blocked" }
        val c = target.openConnection() as HttpURLConnection
        c.requestMethod = method; c.connectTimeout = 3500; c.readTimeout = 5500; c.setRequestProperty("User-Agent", "AURORA-Mobile/2.0.3"); c.setRequestProperty("Accept", "application/json")
        if (body != null) { c.doOutput = true; c.setRequestProperty("Content-Type", "application/json; charset=utf-8"); c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) } }
        val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
        val text = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
        if (c.responseCode !in 200..299) throw IllegalStateException(runCatching { JSONObject(text).optString("error") }.getOrDefault("HTTP ${c.responseCode}"))
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }

    private fun isPrivateLanHost(host: String): Boolean {
        return try {
            // QR pairing expects a literal IPv4 address; reject DNS names to avoid hostname rebinding.
            if (!host.matches(Regex("^(?:[0-9]{1,3}\\.){3}[0-9]{1,3}$"))) false
            else {
                val parts = host.split(".").map { it.toIntOrNull() ?: return false }
                if (parts.any { it !in 0..255 }) false
                else parts[0] == 10 || (parts[0] == 172 && parts[1] in 16..31) ||
                    (parts[0] == 192 && parts[1] == 168)
            }
        } catch (_: Exception) { false }
    }

    private fun addMessage(role: String, text: String) {
        if (text.isBlank()) return
        val view = TextView(this).apply { setPadding(dp(10), dp(9), dp(10), dp(9)); setTextColor(if (role == "system") Color.rgb(255, 222, 151) else Color.rgb(214, 248, 255)); textSize = 13f; this.text = "${role.uppercase()} // $text"; setBackgroundColor(if (role == "user") Color.rgb(5, 37, 31) else Color.rgb(4, 27, 43)) }
        messages.addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) })
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }
    private fun setConnectedUi(connected: Boolean) { input.isEnabled = connected; sendButton.isEnabled = connected; pttButton.isEnabled = connected; quickRow.visibility = if (connected) View.VISIBLE else View.GONE; scanButton.text = if (connected) "BAŞKA PC EŞLEŞTİR" else "PC QR OKUT" }
    private fun status(text: String) { status.text = text; core.setState(text) }
    private fun fail(text: String) {
        status("ERROR")
        setConnectedUi(false)
        addMessage("system", text)
        toast(text)
    }
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    override fun onDestroy() { super.onDestroy(); pollGeneration++; speech?.destroy(); io.shutdownNow() }

    companion object { const val SESSION_PREFS = "aurora_session" }
}
