package com.makay.remote.signal

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.makay.remote.core.Primitives
import com.makay.remote.core.WireJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MAKAY Remote — HTTP sinyal istemcisi (Cloudflare tüneli / LAN sinyal sunucusu).
 *
 * Windows `server/index.js` ile AYNI protokol (app.js'in HTTP misafiri gibi):
 *
 *   POST /api/join  {room, pinHash}            -> {ok, since, pending[]}
 *   GET  /api/poll  ?room&since&pinHash        -> {ok, since, messages[]}
 *   POST /api/send  {room, pinHash, msg}       -> {ok}
 *
 * Davet metninde "Sinyal: https://…" satiri varsa [SessionController.join]
 * bu istemciyi MQTT'den ONCE dener; boylece telefonun halka acik MQTT
 * broker'larina erisemedigi mobil aglarda bile (masaustu Cloudflare
 * tuneli uzerinden 443 ile) eslesme kurulur.
 *
 * Tünel yenilenirse (masaustu yeniden baslatilirsa) 404 gelir ve bu istemci
 * "koptu" bildirimi yapar; SessionController alternatif yola (MQTT) geri
 * doner.
 *
 * NOT: `android.util.Log` bilincli olarak KULLANILMAZ — saf JVM birim
 * testlerinde `Log` "not mocked" firlatir; gunlukleme [logger] ile yapilir.
 */
class HttpSignalClient(
    private val baseUrl: String,
    injectedScope: CoroutineScope? = null,
    private val pollIntervalMs: Long = POLL_INTERVAL_MS,
    private val retryIntervalMs: Long = RETRY_INTERVAL_MS,
    private val maxPollFailures: Int = MAX_POLL_FAILURES,
    private val logger: ((String) -> Unit)? = null
) : SignalChannel {

    private val ownsScope = injectedScope == null
    private val scope: CoroutineScope =
        injectedScope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val closed = AtomicBoolean(false)
    private var apiBase: String = ""
    private var roomCode: String = ""
    private var pinHash: String = ""
    private var since: Long = 0L
    private var listener: SignalChannel.Listener? = null
    private var closeListener: SignalChannel.CloseListener? = null
    private var pollJob: Job? = null

    /** Kendi yayinlarimizi yoksayabilmek icin (sunucu gondereni ayirt edemez). */
    private val selfTag: String = java.util.UUID.randomUUID().toString().take(8)

    /**
     * Odaya HTTP üzerinden katilir: /api/join + poll dongusu baslatilir.
     *
     * @throws Exception odaya basilamazsa (404/403/ag hatasi)
     */
    override suspend fun subscribe(
        room: String,
        password: String,
        onMessage: SignalChannel.Listener,
        onClose: SignalChannel.CloseListener?
    ) {
        listener = onMessage
        closeListener = onClose
        pinHash = Primitives.sha256Hex(password)
        roomCode = room.uppercase().filter { it in '0'..'9' || it in 'A'..'Z' }
        apiBase = baseUrl.trim().trimEnd('/')
        if (apiBase.isEmpty()) throw Exception("Sinyal adresi boş")
        if (roomCode.length != 8) throw Exception("Geçersiz oda kimliği")

        val resp = withContext(Dispatchers.IO) {
            request("POST", "$apiBase/api/join", authBody())
        }
        when (resp.code) {
            404 -> throw Exception("Oda bulunamadı")
            403 -> throw Exception("Şifre hatalı")
            429 -> throw Exception("Çok fazla istek — biraz bekleyin")
        }
        if (resp.code != 200) throw Exception("Sunucu hatası (HTTP ${resp.code})")
        val obj = parseObject(resp.body) ?: throw Exception("Geçersiz sunucu yanıtı")

        /* pending: baglanmadan once gonderilmis mesajlar (offer vb.);
           `since` imleci pending'i kapsayacak sekilde alinir ki ayni mesaj
           poll'de ikinci kez gelmesin. */
        deliverArray(obj.get("pending"))
        readSince(obj)
        log("Odaya katildi: $roomCode (since=$since)")

        pollJob = scope.launch { pollLoop() }
    }

    override suspend fun publish(payload: SignalEnvelope) {
        if (closed.get() || apiBase.isEmpty()) throw Exception("Eşleşme oturumu yok")
        val msg = payload.copy(
            version = 1,
            pinHash = pinHash,
            ts = System.currentTimeMillis(),
            from = selfTag
        )
        val root = JsonObject().apply {
            addProperty("room", roomCode)
            addProperty("pinHash", pinHash)
            add("msg", WireJson.gson.toJsonTree(msg))
        }
        val resp = withContext(Dispatchers.IO) {
            request("POST", "$apiBase/api/send", root.toString())
        }
        when (resp.code) {
            200 -> return
            404 -> throw Exception("Oda bulunamadı")
            403 -> throw Exception("Şifre hatalı")
            429 -> throw Exception("Çok fazla istek — biraz bekleyin")
            else -> throw Exception("Sunucu hatası (HTTP ${resp.code})")
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        pollJob?.cancel()
        pollJob = null
        if (ownsScope) scope.cancel()
    }

    // ---------------- poll dongusu ----------------

    private suspend fun pollLoop() {
        var failures = 0
        while (true) {
            try {
                val resp = withContext(Dispatchers.IO) {
                    request(
                        "GET",
                        "$apiBase/api/poll?room=$roomCode&since=$since&pinHash=$pinHash"
                    )
                }
                if (resp.code == 404 || resp.code == 403) {
                    /* Oda silindi / tunel yenilendi: geri donulemez */
                    log("Oda artik yok (HTTP ${resp.code})")
                    fatalClose()
                    return
                }
                if (resp.code != 200) throw Exception("HTTP ${resp.code}")
                val obj = parseObject(resp.body) ?: throw Exception("Geçersiz yanıt")
                deliverArray(obj.get("messages"))
                readSince(obj)
                failures = 0
                delay(pollIntervalMs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failures += 1
                log("Poll hatasi ($failures/$maxPollFailures): ${e.message}")
                if (failures >= maxPollFailures) {
                    fatalClose()
                    return
                }
                delay(retryIntervalMs)
            }
        }
    }

    /** Geri donulemez kopma: [SignalChannel.CloseListener] bir kez cagrilir. */
    private fun fatalClose() {
        if (!closed.compareAndSet(false, true)) return
        pollJob?.cancel()
        try { closeListener?.onClosed() } catch (e: Exception) { /* yut */ }
    }

    // ---------------- mesaj isleme ----------------

    private fun deliverArray(el: JsonElement?) {
        if (el == null || !el.isJsonArray) return
        for (item in el.asJsonArray) {
            val env = parseEnvelope(item) ?: continue
            if (!accept(env)) continue
            try {
                listener?.onMessage(env)
            } catch (e: Exception) {
                log("Mesaj islenemedi: ${e.message}")
            }
        }
    }

    private fun parseEnvelope(el: JsonElement): SignalEnvelope? = try {
        if (el.isJsonPrimitive && el.asJsonPrimitive.isString) {
            /* Eski/bicim degistirilmis sunucu: string olarak saklanmis zarf */
            WireJson.gson.fromJson(el.asString, SignalEnvelope::class.java)
        } else {
            WireJson.gson.fromJson(el, SignalEnvelope::class.java)
        }
    } catch (e: Exception) {
        null
    }

    /**
     * Kabul kurallari — MQTT yolundan bilerek farklidir:
     *
     *  - **pinHash**: istek seviyesinde sunucu dogrular (`/api/send|poll|join`);
     *    masaustu HTTP mesajlarinda zarf ICINDE pinHash yoktur — yalnizca
     *    zarfta mevcutsa eslestirilir.
     *  - **Yinelenme (seen)**: YOKTUR. HTTP mesajlari since-imleciyle bir kez
     *    teslim edilir; masaustu ICE mesajlarinda `ts` olmadigi icin
     *    (from|ts|type) anahtari ikinci ICE adayini dusururdu.
     */
    private fun accept(env: SignalEnvelope): Boolean {
        if (env.type.isEmpty()) return false
        if (env.from != null && env.from == selfTag) return false
        val remote = env.pinHash
        if (!remote.isNullOrEmpty() && remote.lowercase() != pinHash) return false
        return true
    }

    private fun readSince(obj: JsonObject) {
        obj.get("since")?.let { el -> runCatching { since = el.asLong }.getOrNull() }
    }

    private fun authBody(): String {
        val root = JsonObject()
        root.addProperty("room", roomCode)
        root.addProperty("pinHash", pinHash)
        return root.toString()
    }

    private fun parseObject(body: String?): JsonObject? {
        if (body.isNullOrBlank()) return null
        return runCatching {
            WireJson.gson.fromJson(body, JsonObject::class.java)
        }.getOrNull()
    }

    private fun log(msg: String) {
        try { logger?.invoke("[HttpSignal] $msg") } catch (e: Exception) { /* yut */ }
    }

    // ---------------- HTTP ----------------

    private data class Resp(val code: Int, val body: String)

    private fun request(method: String, url: String, body: String? = null): Resp {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            /* Sunucu long-poll ~4 sn tutar; o yuzden okuma timeout'u genis
               (HttpURLConnection Int milisaniye alir) */
            conn.readTimeout = READ_TIMEOUT_MS
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..399) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return Resp(code, text)
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    companion object {
        /* Varsayilanlar masaustu app.js polling'i ile ayni ritimde */
        private const val POLL_INTERVAL_MS = 1200L
        private const val RETRY_INTERVAL_MS = 2500L
        private const val MAX_POLL_FAILURES = 8
        private const val CONNECT_TIMEOUT_MS = 8000
        private const val READ_TIMEOUT_MS = 15_000
    }
}
