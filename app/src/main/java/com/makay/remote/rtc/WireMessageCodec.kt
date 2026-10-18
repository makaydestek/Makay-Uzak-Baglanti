package com.makay.remote.rtc

import com.google.gson.Gson
import com.makay.remote.core.WireJson

/**
 * DataChannel baytlarini WireEnvelope olarak cozer.
 *
 * Electron/Android arasinda metin zarflari bazen binary kare olarak gelir.
 * Dosya parcalari ise `JSON\0payload` bicimindedir — onlari zarf sayma.
 */
object WireMessageCodec {

    private val gson: Gson = WireJson.gson

    /**
     * @return gecerli zarf, veya dosya parcasi / gecersiz veri ise null
     */
    fun tryParseEnvelope(bytes: ByteArray): WireEnvelope? {
        if (bytes.isEmpty() || bytes[0] != '{'.code.toByte()) return null
        for (b in bytes) {
            if (b.toInt() == 0) return null
        }
        val env = runCatching {
            gson.fromJson(String(bytes, Charsets.UTF_8), WireEnvelope::class.java)
        }.getOrNull() ?: return null
        if (env.type.isEmpty()) return null
        return env
    }
}
