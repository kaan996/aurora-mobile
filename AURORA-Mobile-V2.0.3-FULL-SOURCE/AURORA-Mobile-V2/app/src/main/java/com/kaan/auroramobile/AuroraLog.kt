package com.kaan.auroramobile

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small, private diagnostic log. Never writes message bodies, session tokens, or API keys. */
object AuroraLog {
    @Synchronized fun write(context: Context, event: String, detail: String = "") {
        val safeEvent = event.replace(Regex("[^A-Za-z0-9_. -]"), "_").take(80)
        val safeDetail = detail.replace(Regex("[\\r\\n\\t]"), " ").take(180)
        val line = "${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())} [$safeEvent] $safeDetail\n"
        Log.i("AURORA", "[$safeEvent] $safeDetail")
        runCatching {
            val file = File(context.filesDir, "aurora-diagnostics.log")
            if (file.exists() && file.length() > 512 * 1024) {
                val old = file.readLines().takeLast(1500)
                file.writeText(old.joinToString("\n", postfix = "\n"))
            }
            file.appendText(line)
        }
    }
}
