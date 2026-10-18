package com.makay.remote.signal

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CANLI TANI testi — yalnizca elle calistirilir (ag erisimi gerektirir).
 *
 * Amac: Android `MqttSignalClient`'in JVM'de gercek broker'a baglanip
 * baglanamadigini ve konuya abone olup mesaj ALIP ALAMADIGINI olcmek.
 *
 *   $env:MAKAY_LIVE_PROBE="true"; .\gradlew.bat :app:testDebugUnitTest --tests "*MqttLiveProbeTest*"
 *
 * Varsayilan olarak atlanir.
 */
class MqttLiveProbeTest {

    private fun enabled(): Boolean =
        System.getProperty("makay.liveProbe") == "true" ||
            System.getenv("MAKAY_LIVE_PROBE") == "true"

    @Test
    fun emqx_roundtrip() = runBlocking {
        org.junit.Assume.assumeTrue(
            "MqttLiveProbeTest atlandi (MAKAY_LIVE_PROBE=true ile calistirin)",
            enabled()
        )
        val room = "TEST" + (1000..9999).random().toString()
        val pwd = "123456"

        val got = AtomicBoolean(false)

        // 1) Once ALICI abone olur (retained yok; yayin sonra gelmeli).
        val subscriber = MqttSignalClient(null)
        subscriber.subscribe(room, pwd, SignalChannel.Listener { env ->
            println("PROBE mesaj alindi: type=${env.type} from=${env.from}")
            got.set(true)
        }, null)
        println("PROBE alici abone oldu: $room")
        delay(1500)

        // 2) GONDEREN baglanir.
        val publisher = MqttSignalClient(null)
        publisher.subscribe(room, pwd, SignalChannel.Listener { }, null)
        println("PROBE gonderen baglandi")
        delay(500)

        // 3) Alici alana kadar periyodik yayin.
        var waited = 0
        while (!got.get() && waited < 20000) {
            runCatching {
                publisher.publish(SignalEnvelope(type = SignalEnvelope.TYPE_JOIN_REQUEST, name = "probe"))
            }.onFailure { println("PROBE yayin hatasi: ${it.message}") }
            delay(700)
            waited += 700
        }
        publisher.close()
        subscriber.close()
        println("PROBE sonuc: alindi=${got.get()} (bekleme=${waited}ms)")
        assertTrue("Broker round-trip basarisiz", got.get())
    }

    /**
     * CANLI UCTAN UCA: GERCEK bir Windows host odasina (MAKAY_ROOM/MAKAY_PWD)
     * Android istemcisiyle (MqttSignalClient) katilir; periyodik join-request
     * yayinlar ve offer gelip gelmedigini olcer. Amac: "telefon mu baglanamiyor,
     * Android istemcisi mi Windows'a ulasamiyor?" sorusunu kesinlestirmek.
     *
     *   $env:MAKAY_ROOM="P6VT29QA"; $env:MAKAY_PWD="919316"; \
     *   .\gradlew.bat :app:testDebugUnitTest --tests "*MqttLiveProbeTest*"
     */
    @Test
    fun joinLiveHost() = runBlocking {
        val room = System.getenv("MAKAY_ROOM")
        val pwd = System.getenv("MAKAY_PWD")
        org.junit.Assume.assumeTrue(
            "joinLiveHost atlandi (MAKAY_ROOM + MAKAY_PWD gerekli)",
            !room.isNullOrBlank() && !pwd.isNullOrBlank()
        )
        println("LIVEJOIN room=$room pwd=$pwd pin=${com.makay.remote.core.Primitives.sha256Hex(pwd!!)}")

        val gotOffer = AtomicBoolean(false)
        val client = MqttSignalClient(null)
        client.subscribe(room!!, pwd, SignalChannel.Listener { env ->
            println("LIVEJOIN GOT: type=${env.type} from=${env.from} code=${env.code?.take(40)}")
            if (env.type == SignalEnvelope.TYPE_OFFER) gotOffer.set(true)
        }, null)
        println("LIVEJOIN abone olundu; join-request gonderiliyor")

        val end = System.currentTimeMillis() + 45000
        while (System.currentTimeMillis() < end) {
            runCatching {
                client.publish(SignalEnvelope(type = SignalEnvelope.TYPE_JOIN_REQUEST, name = "AndroidLiveTest"))
            }.onFailure { println("LIVEJOIN PUB HATA: ${it.message}") }
            delay(2000)
        }
        client.close()
        println("LIVEJOIN sonuc: offer=${gotOffer.get()}")
    }
}
