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
}
