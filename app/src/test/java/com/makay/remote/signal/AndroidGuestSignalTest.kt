package com.makay.remote.signal

import com.makay.remote.core.Primitives
import com.makay.remote.core.SdpCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assume
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * CANLI KONUK (guest) TANSIYONU — yalnizca elle calistirilir.
 *
 * Burada TELEFONUN GERCEK sinyal katmani calisir: [MqttSignalClient]
 * (gercek Paho MQTT-5) + [SdpCode] (gercek SDP eslestirme kodu). Yalnizca
 * WebRTC motoru (libwebrtc) yoktur — onun yerine teklif gelince cevap uretir
 * ve gercege yakin bir cevap SDP'si ile trickle ICE adaylarini YAYINLAR.
 *
 * Amaç: "telefon cevabini/ICE adaylarini yayinlayabiliyor mu, Windows bunlari
 * alabiliyor mu?" sorusunu kesinlestirmek. Boylece hata sinyal katmaninda mi
 * (burada gorulur) yoksa libwebrtc/PeerBridge katmaninda mi (burada gecmez)
 * oldugu ayrilir.
 *
 *   $env:MAKAY_ROOM="XXXXXXXX"; $env:MAKAY_PWD="123456"; \
 *   .\gradlew.bat :app:testDebugUnitTest --tests "*AndroidGuestSignalTest*"
 */
class AndroidGuestSignalTest {

    private fun enabled(): Boolean =
        System.getProperty("makay.liveProbe") == "true" || System.getenv("MAKAY_LIVE_PROBE") == "true"

    /**
     * Teklif SDP'sinden gercege yakin bir CEVAP SDP'si uretir.
     * (Bu test WebRTC motoru barindirmez; amac sinyal YOLUNU sinamaktir.)
     */
    private fun toAnswerSdp(offerSdp: String): String = offerSdp
        .replace("a=setup:actpass", "a=setup:active")
        .replace("a=actpass", "a=active")
        .lineSequence()
        .filter { !it.startsWith("a=candidate:") }
        .joinToString("\r\n")

    @Test
    fun joinRealHostAndDeliverAnswer() = runBlocking {
        val room = System.getenv("MAKAY_ROOM")
        val pwd = System.getenv("MAKAY_PWD")
        Assume.assumeTrue(
            "atlandi (MAKAY_ROOM + MAKAY_PWD gerekli)",
            enabled() && !room.isNullOrBlank() && !pwd.isNullOrBlank()
        )

        val offerCount = AtomicInteger(0)
        val answerSent = AtomicBooleanLike()
        val received = java.util.Collections.synchronizedList(mutableListOf<String>())

        val client = MqttSignalClient(null)
        client.subscribe(room!!, pwd!!, SignalChannel.Listener { env ->
            received.add("type=${env.type} from=${env.from} code=${(env.code ?: "").take(30)} mid=${env.mid} cand=${(env.cand ?: "").take(28)}")
            if (env.type == SignalEnvelope.TYPE_OFFER && !env.code.isNullOrBlank()) {
                offerCount.incrementAndGet()
                runCatching {
                    /* 1) GERCEK Android kod-cozucu ile teklifi coz */
                    val decoded = SdpCode.decode(env.code)
                    println("GUEST TEKLIF COZULDU: kind=${decoded.kind} sdpLen=${decoded.sdp.length} " +
                        "aday=${decoded.sdp.split("a=candidate:").size - 1} name=${decoded.name.take(20)}")

                    /* 2) GERCEK Android kodlayici ile cevap uret */
                    val answerSdp = toAnswerSdp(decoded.sdp)
                    val answerCode = SdpCode.encode(answerSdp, "AndroidGuest", Primitives.generateRoom(), "A")
                    println("GUEST CEVAP URETILDI: sdpLen=${answerSdp.length} codeLen=${answerCode.length}")

                    /* 3) GERCEK Android MQTT istemcisiyle cevabi yayinla */
                    runBlocking {
                        client.publish(SignalEnvelope(type = SignalEnvelope.TYPE_ANSWER, code = answerCode))
                    }
                    answerSent.set(true)
                    println("GUEST CEVAP YAYINLANDI")

                    /* 4) Trickle ICE adaylarini da yayinla (ayni milisaniyede = eski hatayi tetikler) */
                    val now = System.currentTimeMillis()
                    repeat(4) { i ->
                        runBlocking {
                            client.publish(
                                SignalEnvelope(
                                    type = SignalEnvelope.TYPE_ICE,
                                    mid = "0",
                                    mline = 0,
                                    cand = "candidate:${i + 1} 1 udp 2122260223 192.168.68.52 ${50000 + i} typ host generation 0"
                                )
                            )
                        }
                        println("GUEST ICE YAYINLANDI #$i ts=$now")
                    }
                }.onFailure {
                    println("GUEST HATA: ${it.javaClass.simpleName}: ${it.message}")
                }
            }
        }, null)
        println("GUEST abone olundu: room=$room pin=${Primitives.sha256Hex(pwd)}")

        val end = System.currentTimeMillis() + 60000
        var waited = 0
        while (System.currentTimeMillis() < end && offerCount.get() == 0) {
            runCatching {
                client.publish(SignalEnvelope(type = SignalEnvelope.TYPE_JOIN_REQUEST, name = "AndroidGuestTest"))
            }.onFailure { println("GUEST join-request yayin hatasi: ${it.message}") }
            delay(2000); waited += 2000
        }
        println("GUEST teklif sayisi=${offerCount.get()} (bekleme=${waited}ms) cevapGonderildi=${answerSent.get()}")
        delay(6000)
        client.close()

        println("===== GUEST ALDIKLARI =====")
        synchronized(received) { received.forEach { println("  " + it) } }
        println("===== ================== =====")
    }

    private class AtomicBooleanLike {
        private val b = java.util.concurrent.atomic.AtomicBoolean(false)
        fun set(v: Boolean) = b.set(v)
        fun get(): Boolean = b.get()
    }
}
