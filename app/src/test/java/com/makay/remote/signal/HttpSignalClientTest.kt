package com.makay.remote.signal

import com.google.gson.JsonParser
import com.makay.remote.core.Primitives
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.Closeable
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * HttpSignalClient — sahte `server/index.js` (mini HTTP sunucu) uzerinde protokol testi.
 *
 * NOT: Sunucu `java.net.ServerSocket` ile yazildi — `com.sun.net.httpserver`
 * Android birim test derleme sinif yolunda YOKTUR (android.jar sinirli).
 *
 * Dogrulanan akis (masaustu app.js HTTP misafiri ile ayni):
 *   POST /api/join  -> pending mesajlari teslim + since imleci
 *   GET  /api/poll  -> kuyruktaki mesajlar (nesne YA DA string biciminde)
 *   POST /api/send  -> oda + pinHash + zarf (t/pinHash/ts/from dolu)
 *
 * Ayrica: hatali pinHash'li zarf elenir, 403/404 uygun hata mesaji firlatir,
 * close() poll dongusunu durdurur.
 */
class HttpSignalClientTest {

    private companion object {
        const val ROOM = "0DH8H0C0"
        const val PWD = "123456"
    }

    private lateinit var server: MiniHttpServer
    private lateinit var baseUrl: String
    private lateinit var scope: CoroutineScope

    private val pendingQueue = ConcurrentLinkedQueue<String>()
    private val sentBodies = ConcurrentLinkedQueue<String>()
    private val joinBodies = ConcurrentLinkedQueue<String>()
    private val pollQueries = ConcurrentLinkedQueue<String>()
    private val seq = AtomicLong(0)

    @Volatile private var joinStatus = 200

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        pendingQueue.clear()
        sentBodies.clear()
        joinBodies.clear()
        pollQueries.clear()
        seq.set(0)
        joinStatus = 200

        server = MiniHttpServer()
        server.handler = { method, target, body ->
            when {
                method == "POST" && target.startsWith("/api/join") -> {
                    joinBodies.add(body)
                    if (joinStatus != 200) {
                        joinStatus to """{"ok":false,"error":"x"}"""
                    } else {
                        200 to """{"ok":true,"since":${seq.get()},"pending":[${drain()}]}"""
                    }
                }

                method == "GET" && target.startsWith("/api/poll") -> {
                    pollQueries.add(target.substringAfter('?'))
                    200 to """{"ok":true,"since":${seq.get()},"messages":[${drain()}]}"""
                }

                method == "POST" && target.startsWith("/api/send") -> {
                    sentBodies.add(body)
                    200 to """{"ok":true}"""
                }

                else -> 404 to """{"ok":false}"""
            }
        }
        baseUrl = "http://127.0.0.1:${server.port}"
    }

    @After
    fun tearDown() {
        runCatching { server.close() }
        scope.cancel()
    }

    // ---------------- testler ----------------

    /** /api/join pending'i (offer) aninda teslim edilir; join body'sinde room+pinHash. */
    @Test
    fun subscribeDeliversPendingOffer() = runBlocking {
        enqueue("""{"t":"offer","code":"OFFER123"}""")

        val received = ConcurrentLinkedQueue<SignalEnvelope>()
        val latch = CountDownLatch(1)
        val client = newClient()
        try {
            client.subscribe(
                ROOM, PWD,
                SignalChannel.Listener { env -> received.add(env); latch.countDown() },
                null
            )
            assertTrue("offer pending'den gelmeli", latch.await(5, TimeUnit.SECONDS))
            val env = received.first()
            assertEquals(SignalEnvelope.TYPE_OFFER, env.type)
            assertEquals("OFFER123", env.code)

            val join = joinBodies.first()
            assertTrue("join body oda icermeli: $join", join.contains(ROOM))
            assertTrue(
                "join body pinHash icermeli: $join",
                join.contains(Primitives.sha256Hex(PWD))
            )
        } finally {
            client.close()
        }
    }

    /** publish: POST /api/send — oda + pinHash + zarf (t/ts/from) gonderilir. */
    @Test
    fun publishPostsEnvelopeWithAuthFields() = runBlocking {
        val client = newClient()
        try {
            client.subscribe(ROOM, PWD, SignalChannel.Listener { }, null)
            client.publish(SignalEnvelope(type = SignalEnvelope.TYPE_ANSWER, code = "ANSCODE"))

            val body = sentBodies.poll() ?: throw AssertionError("send cagrisi olmamali")
            val root = JsonParser.parseString(body).asJsonObject
            assertEquals(ROOM, root.get("room").asString)
            assertEquals(Primitives.sha256Hex(PWD), root.get("pinHash").asString)

            val msg = root.getAsJsonObject("msg")
            assertEquals(SignalEnvelope.TYPE_ANSWER, msg.get("t").asString)
            assertEquals("ANSCODE", msg.get("code").asString)
            assertNotNull("from etiketi zorunlu (kendi mesaji suzmek icin)", msg.get("from"))
            assertTrue("ts > 0 olmali", msg.get("ts").asLong > 0)
            assertEquals(1, msg.get("v").asInt)
        } finally {
            client.close()
        }
    }

    /** Poll: kuyruk hem nesne hem string (eski sunucu) biciminde teslim edilir. */
    @Test
    fun pollDeliversObjectAndStringMessages() = runBlocking {
        val received = ConcurrentLinkedQueue<SignalEnvelope>()
        val latch = CountDownLatch(2)
        val client = newClient()
        try {
            client.subscribe(
                ROOM, PWD,
                SignalChannel.Listener { env -> received.add(env); latch.countDown() },
                null
            )
            enqueue("""{"t":"ice","mid":"0","mline":0,"cand":"candidate:1 udp","from":"peer1"}""")
            enqueue(
                "\"" + """{"t":"offer","code":"STRINGFORM"}""".replace("\"", "\\\"") + "\""
            )

            assertTrue(
                "poll mesajlari gelmeli",
                latch.await(5, TimeUnit.SECONDS)
            )
            val list = received.toList()
            assertEquals(2, list.size)
            assertEquals(SignalEnvelope.TYPE_ICE, list[0].type)
            assertEquals("candidate:1 udp", list[0].cand)
            assertEquals(SignalEnvelope.TYPE_OFFER, list[1].type)
            assertEquals("STRINGFORM", list[1].code)

            /* Ilk poll istegi gelene kadar bekle (join sirasinda olmayabilir) */
            val deadline = System.currentTimeMillis() + 3000
            while (pollQueries.isEmpty() && System.currentTimeMillis() < deadline) delay(50)
            assertTrue("poll istegi gonderilmeli", pollQueries.isNotEmpty())
            val query = pollQueries.first()
            assertTrue(
                "poll query oda+pinHash+since icermeli: $query",
                query.contains(ROOM) &&
                    query.contains(Primitives.sha256Hex(PWD)) &&
                    query.contains("since=")
            )
        } finally {
            client.close()
        }
    }

    /** PinHash'i tutmayan zarf teslim edilmez; saglikli mesaj yine gelir. */
    @Test
    fun mismatchedPinHashEnvelopeIsDropped() = runBlocking {
        val received = ConcurrentLinkedQueue<SignalEnvelope>()
        val latch = CountDownLatch(1)
        val client = newClient()
        try {
            client.subscribe(
                ROOM, PWD,
                SignalChannel.Listener { env -> received.add(env); latch.countDown() },
                null
            )
            enqueue("""{"t":"ice","mid":"0","mline":0,"cand":"x","pinHash":"${"a".repeat(64)}"}""")
            enqueue("""{"t":"offer","code":"GOOD"}""")

            assertTrue(latch.await(5, TimeUnit.SECONDS))
            delay(400) // yanlis pinHash'li mesaj gec kalirsa diye
            assertEquals(1, received.size)
            assertEquals(SignalEnvelope.TYPE_OFFER, received.first().type)
        } finally {
            client.close()
        }
    }

    /** Kendi gonderdigimiz mesaj poll ile geri gelirse elenir (from == selfTag). */
    @Test
    fun ownEchoedMessageIsIgnored() = runBlocking {
        val client = newClient()
        val received = ConcurrentLinkedQueue<SignalEnvelope>()
        val latch = CountDownLatch(1)
        try {
            client.subscribe(
                ROOM, PWD,
                SignalChannel.Listener { env -> received.add(env); latch.countDown() },
                null
            )
            client.publish(SignalEnvelope(type = SignalEnvelope.TYPE_ICE, mid = "0", mline = 0, cand = "self"))

            val sent = JsonParser.parseString(sentBodies.first()).asJsonObject
                .getAsJsonObject("msg")
            val selfTag = sent.get("from").asString
            assertTrue("selfTag (UUID-8) olmali", selfTag.length == 8)

            /* Sunucu kendi mesajimizi bize geri yollar (gonderen ayirt edilemez) */
            enqueue(
                """{"t":"ice","mid":"0","mline":0,"cand":"self","from":"$selfTag","ts":${sent.get("ts").asLong}}"""
            )
            enqueue("""{"t":"offer","code":"AFTERSELF"}""")

            assertTrue(latch.await(5, TimeUnit.SECONDS))
            delay(300)
            assertEquals("kendi mesaji elenmeli", 1, received.size)
            assertEquals(SignalEnvelope.TYPE_OFFER, received.first().type)
        } finally {
            client.close()
        }
    }

    /** 403 -> "Şifre hatalı" */
    @Test
    fun joinForbiddenThrowsPasswordError() = runBlocking {
        joinStatus = 403
        val client = newClient()
        try {
            val err = runCatching {
                client.subscribe(ROOM, PWD, SignalChannel.Listener { }, null)
            }.exceptionOrNull()
            assertNotNull(err)
            assertTrue("mesaj: ${err?.message}", err?.message?.contains("Şifre hatalı") == true)
        } finally {
            client.close()
        }
    }

    /** 404 -> "Oda bulunamadı" (olmus tünel / kapanmis oda) */
    @Test
    fun joinMissingRoomThrowsNotFound() = runBlocking {
        joinStatus = 404
        val client = newClient()
        try {
            val err = runCatching {
                client.subscribe(ROOM, PWD, SignalChannel.Listener { }, null)
            }.exceptionOrNull()
            assertNotNull(err)
            assertTrue("mesaj: ${err?.message}", err?.message?.contains("Oda bulunamadı") == true)
        } finally {
            client.close()
        }
    }

    /** close() sonrasi poll istekleri durur. */
    @Test
    fun closeStopsPolling() = runBlocking {
        val client = newClient()
        client.subscribe(ROOM, PWD, SignalChannel.Listener { }, null)
        delay(200)
        val atClose = pollQueries.size
        client.close()
        delay(500)
        assertTrue(
            "close sonrasi yeni poll olmamali (onceki=$atClose sonraki=${pollQueries.size})",
            pollQueries.size <= atClose + 1
        )
    }

    // ---------------- yardimcilar ----------------

    private fun newClient() = HttpSignalClient(
        baseUrl = baseUrl,
        injectedScope = scope,
        pollIntervalMs = 60,
        retryIntervalMs = 60
    )

    /** Kuyruga mesaj ekler (sunucu sirayla, bir kez teslim eder). */
    private fun enqueue(json: String) {
        pendingQueue.add(json)
        seq.incrementAndGet()
    }

    /** Kuyrugu bosaltir ve JSON dizisi metni dondurur. */
    private fun drain(): String {
        val items = mutableListOf<String>()
        while (true) {
            val item = pendingQueue.poll() ?: break
            items.add(item)
        }
        return items.joinToString(",")
    }

    /**
     * Minimal HTTP/1.1 sunucu — yalnizca `java.net` kullanir
     * (android.jar'da com.sun.* yoktur). Her baglanti ayrı is parciginda
     * islenir; yanit `Connection: close` icerir.
     */
    private class MiniHttpServer : Closeable {
        private val serverSocket = ServerSocket(0)
        private val running = AtomicBoolean(true)
        private val pool = Executors.newCachedThreadPool { r ->
            Thread(r, "mini-http").apply { isDaemon = true }
        }

        val port: Int get() = serverSocket.localPort

        /** (method, target, body) -> (status, responseBody) */
        @Volatile
        var handler: (String, String, String) -> Pair<Int, String> =
            { _, _, _ -> 404 to """{"ok":false}""" }

        init {
            Thread({
                while (running.get()) {
                    val sock = try {
                        serverSocket.accept()
                    } catch (e: Exception) {
                        break
                    }
                    pool.execute { handle(sock) }
                }
            }, "mini-http-accept").apply {
                isDaemon = true
                start()
            }
        }

        private fun handle(sock: Socket) {
            try {
                sock.use { s ->
                    val raw = s.getInputStream()
                    val requestLine = readLine(raw) ?: return
                    val parts = requestLine.split(' ')
                    if (parts.size < 2) return
                    val method = parts[0]
                    val target = parts[1]

                    var contentLength = 0
                    while (true) {
                        val line = readLine(raw) ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Content-Length:", ignoreCase = true)) {
                            contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                        }
                    }

                    val bodyBytes = if (contentLength > 0) {
                        raw.readNBytes(contentLength)
                    } else {
                        ByteArray(0)
                    }
                    val (status, respBody) = handler(method, target, bodyBytes.toString(Charsets.UTF_8))

                    val respBytes = respBody.toByteArray(Charsets.UTF_8)
                    val head = "HTTP/1.1 $status ${reason(status)}\r\n" +
                        "Content-Type: application/json; charset=utf-8\r\n" +
                        "Content-Length: ${respBytes.size}\r\n" +
                        "Connection: close\r\n" +
                        "\r\n"
                    val out = s.getOutputStream()
                    out.write(head.toByteArray(Charsets.US_ASCII))
                    out.write(respBytes)
                    out.flush()
                }
            } catch (e: Exception) {
                runCatching { sock.close() }
            }
        }

        /** Byte-bazli satir okuma: BufferedReader gibi fazla tamponlamaz. */
        private fun readLine(raw: InputStream): String? {
            val sb = StringBuilder()
            while (true) {
                val b = raw.read()
                if (b == -1) return if (sb.isEmpty()) null else sb.toString()
                if (b == '\n'.code) break
                if (b != '\r'.code) sb.append(b.toChar())
            }
            return sb.toString()
        }

        private fun reason(code: Int): String = when (code) {
            200 -> "OK"
            403 -> "Forbidden"
            404 -> "Not Found"
            429 -> "Too Many Requests"
            else -> "Error"
        }

        override fun close() {
            running.set(false)
            runCatching { serverSocket.close() }
            pool.shutdownNow()
        }
    }
}
