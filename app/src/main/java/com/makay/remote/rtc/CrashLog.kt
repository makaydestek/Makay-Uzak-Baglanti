package com.makay.remote.rtc

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Java çökmelerini ve adim izlerini dosyaya yazar.
 * Yol: uygulama dosyalari / makay-crash.log
 * (Ayarlar → Uygulamalar → MAKAY → Depolama ile erisilebilir;
 *  veya `adb shell run-as … cat files/makay-crash.log`)
 */
object CrashLog {
    private const val TAG = "MakayCrash"
    private const val FILE = "makay-crash.log"

    @Volatile private var file: File? = null

    fun install(context: Context) {
        val f = File(context.applicationContext.filesDir, FILE)
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
            file?.appendText(line, Charsets.UTF_8)
        }
    }

    private fun ts(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
}
