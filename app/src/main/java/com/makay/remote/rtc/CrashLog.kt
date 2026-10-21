package com.makay.remote.rtc

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Java cokmelerini ve adim izlerini dosyaya yazar.
 * Yol: uygulama dosyalari / makay-crash.log
 * Boyut ~512 KB ustunde dondurulur; Ayarlar'dan paylasilabilir.
 */
object CrashLog {
    private const val TAG = "MakayCrash"
    private const val FILE = "makay-crash.log"
    private const val MAX_BYTES = 512 * 1024L

    @Volatile private var file: File? = null
    @Volatile private var appContext: Context? = null

    fun install(context: Context) {
        val app = context.applicationContext
        appContext = app
        val f = File(app.filesDir, FILE)
        file = f
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            write("FATAL thread=${t.name} ${e.javaClass.name}: ${e.message}\n${e.stackTraceToString()}")
            prev?.uncaughtException(t, e)
        }
        breadcrumb("CrashLog kuruldu")
    }

    fun breadcrumb(msg: String) {
        write("STEP $msg")
    }

    fun write(msg: String) {
        val line = "${ts()} $msg\n"
        Log.e(TAG, msg.take(500))
        runCatching {
            val f = file ?: return@runCatching
            if (f.length() > MAX_BYTES) {
                val keep = f.readText(Charsets.UTF_8).takeLast((MAX_BYTES / 2).toInt())
                f.writeText("…truncated…\n$keep", Charsets.UTF_8)
            }
            f.appendText(line, Charsets.UTF_8)
        }
    }

    fun readTail(maxChars: Int = 8_000): String {
        val f = file ?: return ""
        if (!f.isFile) return ""
        return runCatching {
            val text = f.readText(Charsets.UTF_8)
            if (text.length <= maxChars) text else text.takeLast(maxChars)
        }.getOrDefault("")
    }

    fun clear() {
        runCatching { file?.writeText("", Charsets.UTF_8) }
    }

    /** Sistem paylasim sayfasi — destek icin. */
    fun shareIntent(context: Context): Intent? {
        val f = file ?: return null
        if (!f.isFile || f.length() == 0L) return null
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            f
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Makay Remote crash log")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun ts(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
}
