package com.makay.remote.signal

import android.util.Log
import com.google.gson.Gson
import com.makay.remote.core.Primitives
import com.makay.remote.core.RemoteGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.eclipse.paho.mqttv5.client.IMqttMessageListener
import org.eclipse.paho.mqttv5.client.IMqttToken
import org.eclipse.paho.mqttv5.client.MqttActionListener
import org.eclipse.paho.mqttv5.client.MqttAsyncClient
import org.eclipse.paho.mqttv5.client.MqttCallback
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions
import org.eclipse.paho.mqttv5.client.MqttConnectionOptionsBuilder
import org.eclipse.paho.mqttv5.client.MqttDisconnectResponse
import org.eclipse.paho.mqttv5.common.MqttException
import org.eclipse.paho.mqttv5.common.MqttMessage
import org.eclipse.paho.mqttv5.common.MqttSubscription
import org.eclipse.paho.mqttv5.common.packet.MqttProperties
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MAKAY Remote — bulut eslesme (MQTT/WSS) istemcisi.
 *
 * Windows tarafiyla **ayni protokol**:
 *   - Konu   : `makay/aio/v1/<ODA>`  (ODA: buyuk harf, sadece 0-9A-Z)
 *   - Broker: wss://broker.emqx.io:8084/mqtt, wss://test.mosquitto.org:8081/mqtt
 *   - Yetki : her zarfta `pinHash` = SHA-256(oturum sifresi) eslesmeli
 *
 * NOT: `broker.hivemq.com` bu listede YOKTUR — Paho v5 MQTT-5 CONNECT
 * gonderir, hivemq'un wss ucunda CONNACK gelmez (olcum: ~10 sn timeout),
 * yani Android'de hic calismaz. Masaustu köprüsü (cloud-signal-bridge.js)
 * MQTT 3.1.1 konustugu icin orada listelenmeye devam eder.
 */
class MqttSignalClient(private val customBroker: String? = null) : SignalChannel {

    private val gson = com.makay.remote.core.WireJson.gson
    private val clients = mutableListOf<MqttAsyncClient>()
    private val closed = AtomicBoolean(false)

    private var topic: String? = null
    private var pinHash: String = ""
    private var roomKey: String = ""
    private var listener: SignalChannel.Listener? = null
    private var closeListener: SignalChannel.CloseListener? = null

    /** Windows cloud-signal-bridge ile ayni PIN rate gate. */
    private val pinGate = RemoteGuard.PinGate(windowMs = 60_000L, maxAttempts = 10)

    /** Kendi yayinlarimizi yoksayabilmek icin. */
    private val selfTag: String = java.util.UUID.randomUUID().toString().take(8)
    private val seen = LinkedHashSet<String>()
    /** Monotonik yayin sirasi — yineleme filtresi icin (bkz. SignalEnvelope.seq). */
    private val seqCounter = java.util.concurrent.atomic.AtomicLong(0L)

    private val messageListener = IMqttMessageListener { _, message -> handleRaw(message) }

    private val actionListener = object : MqttActionListener {
        override fun onSuccess(asyncActionToken: IMqttToken?) = Unit
        override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
            Log.w(TAG, "Broker islem basarisiz: ${exception?.message}")
        }
    }

    private val closeCheckLock = Any()
    private var closeCheckPending = false

    private fun scheduleCloseCheck() {
        synchronized(closeCheckLock) {
            if (closeCheckPending || closed.get()) return
            closeCheckPending = true
        }
        Thread {
            try {
                Thread.sleep(CLOSE_DEBOUNCE_MS)
            } catch (_: InterruptedException) {
                return@Thread
            }
            synchronized(closeCheckLock) { closeCheckPending = false }
            if (closed.get()) return@Thread
            val alive = clients.any { runCatching { it.isConnected }.getOrDefault(false) }
            if (!alive) {
                Log.w(TAG, "Tum broker'lar dustu — onClosed")
                closeListener?.onClosed()
            } else {
                Log.i(TAG, "En az bir broker ayakta — onClosed atlandi")
            }
        }.also { it.isDaemon = true; it.start() }
    }

    /**
     * Odaya abone olur. Broker'a baglanir ve konuya yazilir.
     *
     * @throws Exception eslesme agina ulasilamazsa
     */
    override suspend fun subscribe(
        room: String,
        password: String,
        onMessage: SignalChannel.Listener,
        onClose: SignalChannel.CloseListener?
    ) {
        this.listener = onMessage
        this.closeListener = onClose
        this.pinHash = Primitives.sha256Hex(password)
        this.roomKey = room.trim().uppercase()
        this.topic = topicFor(room)
        if (!pinGate.allow("$roomKey:open")) {
            error("Çok fazla eşleşme denemesi — bir dakika bekleyin")
        }

        /* FAN-OUT (masaustu köprüsüyle ayni strateji): ULASTIGIMIZ TUM
           broker'lara baglanip hepsine yayin yapariz.
           Neden: Windows köprüsü (cloud-signal-bridge) emqx+hivemq+mosquitto
           ucunun TUMUNE baglanir; Android ise eskiden YALNIZCA ilk basarili
           broker'a baglaniyordu. Iki taraf FARKLI broker'lara dustugunde
           (or. masaustunun kurumsal agdan emqx'e erisemeyip mosquitto'ya
           duserken telefonun emqx'i secmesi) join-request karsi tarafa HIC
           ulasmiyordu — "Windows'ta onay istegi gorunmuyor" belirtisinin
           en olasi nedeni budur. Artik ortak EN AZ BIR broker'da bulusmak
           garanti altina alinir.
           Ayni mesaj birden fazla broker'dan gelse de handleRaw'daki
           (from|ts|type) tekilleştirmesi mukeerrerleri eler; publish de
           bagli TUM broker'lara yazar. */
        val errors = java.util.Collections.synchronizedList(mutableListOf<Exception>())
        val results = coroutineScope {
            buildBrokerList(customBroker).map { broker ->
                async(Dispatchers.IO) {
                    if (closed.get()) return@async null
                    try {
                        val c = connectOne(broker)
                        try {
                            subscribeTopic(c)
                            c
                        } catch (e: Exception) {
                            Log.w(TAG, "Abone olunamadi ($broker): ${e.message}")
                            errors.add(e)
                            runCatching { c.disconnectForcibly(200L, 200L, false) }
                            runCatching { c.close() }
                            null
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Broker erisilemedi: $broker (${e.message})")
                        errors.add(e)
                        null
                    }
                }
            }.awaitAll()
        }

        val ok = results.filterNotNull()
        if (ok.isEmpty()) {
            Log.w(TAG, "Eslesme agina ulasilamadi (hicbir broker yanit vermedi)")
            throw errors.firstOrNull()
                ?: MqttException(MqttException.REASON_CODE_CLIENT_EXCEPTION.toInt())
        }
        Log.i(TAG, "Eslestirme: ${ok.size} broker'a baglandi")
        clients += ok
    }

    private fun subscribeTopic(client: MqttAsyncClient) {
        val t = topic ?: return
        if (!client.isConnected) return
        try {
            /* KRITIK (Paho 1.2.5 hatasi): `subscribe(MqttSubscription, Object,
               MqttActionListener, IMqttMessageListener, MqttProperties)` asiri
               yuklemesi BOZUKTUR — iceride kosulsuz olarak
               `subscriptionProperties.getSubscriptionIdentifiers().get(0)`
               cagirir; varsayilan (bos) MqttProperties'te bu liste BOS oldugu
               icin `IndexOutOfBoundsException` firlatir. Bu hata MqttException
               OLMADIGI icin eski `catch (e: MqttException)` tarafindan
               yakalanmaz, yukari sizar ve kullaniciya yaniltici bicimde
               "Eslesme agina ulasilamadi — internet baglantinizi kontrol edin"
               olarak gosterilirdi (yani aslinda AG degil KOD hatasi).
               Bu yuzden DIZI tabanli asiri yukleme kullanilir: get(0) cagirmaz
               ve mesaj dinleyicisini konuya baglar. */
            client.subscribe(
                arrayOf(MqttSubscription(t, QOS)),
                null,
                null,
                arrayOf<IMqttMessageListener>(messageListener),
                MqttProperties()
            )
            Log.i(TAG, "Abone olundu: $t")
        } catch (e: Exception) {
            Log.w(TAG, "Abone hatasi: ${e.message}")
            throw MqttException(MqttException.REASON_CODE_CLIENT_EXCEPTION.toInt()).apply { initCause(e) }
        }
    }

    private suspend fun connectOne(url: String): MqttAsyncClient = withContext(Dispatchers.IO) {
        val clientId = "makay-" + java.util.UUID.randomUUID().toString().take(12)
        /* 3. parametre BILEREK null (MqttClientPersistence) — Paho bu durumda
           bellek kaliciligi (MemoryPersistence) kullanir. 2 parametreli yapici
           MqttDefaultFilePersistence kullanir ve `user.dir` altinda
           `makay-<clientId>/` klasoru acmaya calisir. Android'de `user.dir`
           cogunlukla `/` (yazilamaz) oldugu icin bu yapici
           MqttPersistenceException firlatabilir ve istemci hic kurulamaz
           ("Eslesme agina ulasilamadi" ile ayni yaniltici hata). Ayrica
           cleanStart(true) kullandigimiz icin diske yazmak gereksizdir. */
        val client = MqttAsyncClient(url, clientId, null)

        val options: MqttConnectionOptions = MqttConnectionOptionsBuilder()
            .serverURI(url)
            .automaticReconnect(true)
            .automaticReconnectDelay(2, 30)
            .keepAliveInterval(30)
            .connectionTimeout(10)
            .cleanStart(true)
            .sessionExpiryInterval(0L)
            .build()
        /* wss:// icin ek socketFactory GEREKMEZ: Paho v5 URI semasine gore
           NetworkModuleFactory'yi SPI ile kendisi secer
           (WebSocketSecureNetworkModuleFactory kayitlidir). */

        client.setCallback(object : MqttCallback {
            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                Log.i(TAG, "Broker baglandi: $serverURI (reconnect=$reconnect)")
                /* Yeniden baglanmada aboneligi tazele; hata callback is parcigini
                   oldurmesin. */
                runCatching { subscribeTopic(client) }
                    .onFailure { Log.w(TAG, "Yeniden abone olamadi: ${it.message}") }
            }

            override fun disconnected(response: MqttDisconnectResponse?) {
                Log.w(TAG, "Broker baglantisi koptu: ${response?.reasonString}")
                /* Halka acik broker'lar sikca kisa dusup kalkar. Aninda
                   onClosed → SessionController tam join reset → Windows'ta
                   İzin Ver hic gorunmez, telefonda "tekrarlanıyor" olur.
                   Windows köprüsü gibi birkaç sn bekle; biri ayaktaysa sus. */
                scheduleCloseCheck()
            }

            override fun mqttErrorOccurred(exception: MqttException?) {
                Log.w(TAG, "Broker hatasi: ${exception?.message}")
            }

            override fun messageArrived(topic: String?, message: MqttMessage?) = Unit

            override fun deliveryComplete(token: IMqttToken?) = Unit

            override fun authPacketArrived(reasonCode: Int, properties: MqttProperties?) = Unit
        })

        val token = client.connect(options, null, actionListener)
        /* `delay` zorunlu: `Thread.sleep` askiya alma noktasi degildir, bu yuzden
           `withTimeoutOrNull` iptali hic gozlemleyemez ve broker hic yanit
           vermezse dongu sonsuza kadar donerdi. `delay` ayni anda hem is parcigini
           bosaltir hem timeout'un calismasini saglar. */
        withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
            while (!token.isComplete) delay(120)
        }
        if (!client.isConnected) {
            runCatching { client.close() }
            Log.w(TAG, "Baglanti tamamlanmadi: $url")
            throw MqttException(MqttException.REASON_CODE_CLIENT_EXCEPTION.toInt())
        }
        client
    }

    private fun handleRaw(message: MqttMessage?) {
        if (closed.get() || message == null) return
        val env = try {
            gson.fromJson(
                String(message.payload ?: ByteArray(0), StandardCharsets.UTF_8),
                SignalEnvelope::class.java
            )
        } catch (e: Exception) {
            Log.w(TAG, "Zarf cozulemedi: ${e.message}")
            return
        } ?: return
        if (env.type.isEmpty()) return

        // Kendi mesajimizi yoksay
        if (env.from != null && env.from == selfTag) return

        // PIN dogrulama (+ rate gate — yanlis pin flood)
        if (pinHash.isNotEmpty()) {
            val remote = env.pinHash?.lowercase()
            if (remote.isNullOrEmpty() || remote != pinHash) {
                pinGate.allow("$roomKey:badpin")
                return
            }
            pinGate.reset("$roomKey:badpin")
        }

        // Yinelenen mesajlari ele — gonderen bazinda `seq` tabanli (bkz.
        // SignalEnvelope.dedupeKey). Eski `from|ts|type` anahtari ICE
        // adaylari toplu (ayni milisaniyede) geldigi icin ikinci adayi
        // "mukerrer" sanip SESSIZCE DUSURUYORDU.
        val key = env.dedupeKey()
        synchronized(seen) {
            if (seen.contains(key)) return
            seen.add(key)
            if (seen.size > 200) {
                val first = seen.firstOrNull()
                if (first != null) seen.remove(first)
            }
        }

        listener?.onMessage(env)
    }

    /**
     * Odaya sinyal gonderir.
     * @throws MqttException eslesme agina yazilamazsa
     */
    override suspend fun publish(payload: SignalEnvelope) = withContext(Dispatchers.IO) {
        if (closed.get()) {
            Log.w(TAG, "Eslesme oturumu yok")
            throw MqttException(MqttException.REASON_CODE_CLIENT_EXCEPTION.toInt())
        }
        val msg = payload.copy(
            version = 1,
            pinHash = pinHash,
            ts = System.currentTimeMillis(),
            from = selfTag,
            /* Monotonik siralama: `ts` milisaniye cozunurlugunde oldugu icin ayni
               anda yayinlanan ICE adaylari (ayni milisaniye duser) eski
               `from|ts|t` yineleme anahtariyla DUSURULUYORDU. `seq` benzersizdir. */
            seq = seqCounter.incrementAndGet()
        )
        val raw = gson.toJson(msg).toByteArray(StandardCharsets.UTF_8)

        var any = false
        var lastError: Exception? = null
        for (c in clients) {
            if (!c.isConnected) continue
            try {
                val token = c.publish(topic, raw, QOS, false, null, actionListener)
                // `delay` — ayni gerekce: timeout'un iptali gozlemleyebilmesi icin
                // askiya alma noktasi gerekiyor (bkz. connectOne).
                withTimeoutOrNull(PUBLISH_TIMEOUT_MS) { while (!token.isComplete) delay(60) }
                if (token.isComplete && token.exception == null) any = true
                else lastError = token.exception
            } catch (e: Exception) {
                Log.w(TAG, "Yayin basarisiz: ${e.message}")
                lastError = e
            }
        }
        if (!any) {
            Log.w(TAG, "Eslesme agina yazilamadi: ${lastError?.message}")
            throw lastError ?: MqttException(MqttException.REASON_CODE_CLIENT_EXCEPTION.toInt())
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        closeListener = null
        listener = null
        clients.forEach {
            runCatching { if (it.isConnected) it.disconnectForcibly(200L, 200L, false) }
            runCatching { it.close() }
        }
        clients.clear()
    }

    companion object {
        private const val TAG = "MakayMqtt"
        private const val QOS = 1
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val PUBLISH_TIMEOUT_MS = 8_000L
        private const val CLOSE_DEBOUNCE_MS = 5_000L

        /**
         * MQTT-5 ile calisan halka acik broker'lar (WSS).
         *
         * Windows `cloud-signal-bridge.js:BROKERS` dizisinden farkli: orada
         * MQTT 3.1.1 konusan broker.hivemq.com da vardir; Android Paho v5
         * MQTT-5 gonderdigi ve hivemq wss ucunda CONNACK gelmedigi icin
         * (10 sn timeout) listeden cikarildi — erisilemeyen broker sona
         * birakilmaz, ilk iki broker hizli gevis saglar.
         */
        val DEFAULT_BROKERS = listOf(
            "wss://broker.emqx.io:8084/mqtt",
            "wss://test.mosquitto.org:8081/mqtt"
        )

        fun topicFor(room: String): String =
            "makay/aio/v1/" + room.uppercase().filter { it in '0'..'9' || it in 'A'..'Z' }

        /**
         * Denenecek broker sirasi.
         *
         * Kullanici Ayarlar'dan kendi broker'ini girdiyse ONE alinir, sonra
         * halka acik yedekler denenir. Boylece ozel broker birincil olur ama
         * erisilemezse baglanti yine kurulur (uygulamanin temel amaci).
         * `wss://`/`ws://` semasi dogrulanir; gecersiz giris yok sayilir.
         */
        internal fun buildBrokerList(custom: String?): List<String> {
            val cleaned = custom?.trim()?.trimEnd('/')
                ?.takeIf { it.startsWith("wss://") || it.startsWith("ws://") }
                ?: return DEFAULT_BROKERS
            return if (DEFAULT_BROKERS.contains(cleaned)) DEFAULT_BROKERS
            else listOf(cleaned) + DEFAULT_BROKERS
        }
    }
}