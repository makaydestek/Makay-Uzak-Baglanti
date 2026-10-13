package com.makay.remote.signal

import android.util.Log
import com.google.gson.Gson
import com.makay.remote.core.Primitives
import kotlinx.coroutines.Dispatchers
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
 *   - Broker: wss://broker.emqx.io:8084/mqtt, wss://broker.hivemq.com:8884/mqtt,
 *              wss://test.mosquitto.org:8081/mqtt
 *   - Yetki : her zarfta `pinHash` = SHA-256(oturum sifresi) eslesmeli
 */
class MqttSignalClient {

    fun interface Listener {
        fun onMessage(env: SignalEnvelope)
    }

    fun interface CloseListener {
        fun onClosed()
    }

    private val gson = Gson()
    private val clients = mutableListOf<MqttAsyncClient>()
    private val closed = AtomicBoolean(false)

    private var topic: String? = null
    private var pinHash: String = ""
    private var listener: Listener? = null
    private var closeListener: CloseListener? = null

    /** Kendi yayinlarimizi yoksayabilmek icin. */
    private val selfTag: String = java.util.UUID.randomUUID().toString().take(8)
    private val seen = LinkedHashSet<String>()

    private val messageListener = IMqttMessageListener { _, message -> handleRaw(message) }

    private val actionListener = object : MqttActionListener {
        override fun onSuccess(asyncActionToken: IMqttToken?) = Unit
        override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
            Log.w(TAG, "Broker islem basarisiz: ${exception?.message}")
        }
    }

    /**
     * Odaya abone olur. Broker'a baglanir ve konuya yazilir.
     *
     * @throws Exception eslesme agina ulasilamazsa
     */
    suspend fun subscribe(
        room: String,
        password: String,
        onMessage: Listener,
        onClose: CloseListener?
    ) {
        this.listener = onMessage
        this.closeListener = onClose
        this.pinHash = Primitives.sha256Hex(password)
        this.topic = topicFor(room)

        var lastError: Exception? = null
        var connected: MqttAsyncClient? = null

        for (broker in brokerList()) {
            if (closed.get()) break
            val c = try {
                connectOne(broker)
            } catch (e: Exception) {
                Log.w(TAG, "Broker erisilemedi: $broker (${e.message})")
                lastError = e
                null
            }
            if (c != null) {
                connected = c
                break
            }
        }

        if (connected == null) {
            Log.w(TAG, "Eslesme agina ulasilamadi: ${lastError?.message}")
            throw lastError ?: MqttException(MqttException.REASON_CODE_CLIENT_EXCEPTION.toInt())
        }
        clients += connected
        subscribeTopic(connected)
    }

    private fun subscribeTopic(client: MqttAsyncClient) {
        val t = topic ?: return
        if (!client.isConnected) return
        try {
            client.subscribe(MqttSubscription(t, QOS), null, null, messageListener, MqttProperties())
            Log.i(TAG, "Abone olundu: $t")
        } catch (e: MqttException) {
            Log.w(TAG, "Abone hatasi: ${e.message}")
        }
    }

    private suspend fun connectOne(url: String): MqttAsyncClient = withContext(Dispatchers.IO) {
        val clientId = "makay-" + java.util.UUID.randomUUID().toString().take(12)
        val client = MqttAsyncClient(url, clientId)

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
                subscribeTopic(client)
            }

            override fun disconnected(response: MqttDisconnectResponse?) {
                Log.w(TAG, "Broker baglantisi koptu: ${response?.reasonString}")
                if (!closed.get() && clients.none { it.isConnected }) {
                    closeListener?.onClosed()
                }
            }

            override fun mqttErrorOccurred(exception: MqttException?) {
                Log.w(TAG, "Broker hatasi: ${exception?.message}")
            }

            override fun messageArrived(topic: String?, message: MqttMessage?) = Unit

            override fun deliveryComplete(token: IMqttToken?) = Unit

            override fun authPacketArrived(reasonCode: Int, properties: MqttProperties?) = Unit
        })

        val token = client.connect(options, null, actionListener)
        withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
            while (!token.isComplete) Thread.sleep(120)
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

        // PIN dogrulama
        if (pinHash.isNotEmpty()) {
            val remote = env.pinHash?.lowercase()
            if (remote.isNullOrEmpty() || remote != pinHash) return
        }

        // Yinelenen mesajlari ele
        val key = (env.from ?: "") + "|" + env.ts + "|" + env.type
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
    suspend fun publish(payload: SignalEnvelope) = withContext(Dispatchers.IO) {
        if (closed.get()) {
            Log.w(TAG, "Eslesme oturumu yok")
            throw MqttException(MqttException.REASON_CODE_CLIENT_EXCEPTION.toInt())
        }
        val msg = payload.copy(
            version = 1,
            pinHash = pinHash,
            ts = System.currentTimeMillis(),
            from = selfTag
        )
        val raw = gson.toJson(msg).toByteArray(StandardCharsets.UTF_8)

        var any = false
        var lastError: Exception? = null
        for (c in clients) {
            if (!c.isConnected) continue
            try {
                val token = c.publish(topic, raw, QOS, false, null, actionListener)
                withTimeoutOrNull(PUBLISH_TIMEOUT_MS) { while (!token.isComplete) Thread.sleep(60) }
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
        true
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
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

        /** Windows `cloud-signal-bridge.js:BROKERS` ile ayni sira. */
        val DEFAULT_BROKERS = listOf(
            "wss://broker.emqx.io:8084/mqtt",
            "wss://broker.hivemq.com:8884/mqtt",
            "wss://test.mosquitto.org:8081/mqtt"
        )

        fun topicFor(room: String): String =
            "makay/aio/v1/" + room.uppercase().filter { it in '0'..'9' || it in 'A'..'Z' }

        private fun brokerList(): List<String> = DEFAULT_BROKERS
    }
}