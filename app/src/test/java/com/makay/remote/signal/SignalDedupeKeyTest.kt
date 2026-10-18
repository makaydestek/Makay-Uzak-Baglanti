package com.makay.remote.signal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * MQTT yineleme (dedupe) anahtari — TELEFONUN ICE ADAYLARININ KAYBI regresyonu.
 *
 * Kutuphaneyi gercek MQTT broker'ina karsilik gelen TEK oturumda birden cok
 * kez kullanir. `cloud-signal-bridge.js` bu yuzden gelen mesajlari yinelemek
 * icin filtreler (fan-out broker'lardan ayni mesaj birkac kez gelir).
 *
 * Eski anahtar `from|ts|t` idi. `ts` MILISANIYE cozunurlugundedir ve WebRTC
 * ICE adaylari toplu halinde — cogu ayni milisaniyede — gelir. Boylece
 * farkli ICE adaylari ayni anahtari uretir ve ikincisi "mukerrer" sanilip
 * SESSIZCE DUSURULURDU.
 *
 * Sonuc ciddiydi: telefondan masaustune hicbir ICE adayi ulasmaz (veya
 * yalnizca ilki), ICE tamamlanamaz ve uygulama "Onaylandi -> Baglaniyor"
 * asamasinda takilip kalirdi. HTTP yolunda bu hata biliniyordu
 * (HttpSignalClient: "seen YOKTUR … (from|ts|type) anahtari ikinci ICE
 * adayini dusururdu") ama MQTT yolunda kalmisti.
 *
 * Artik `seq` (gonender bazinda monotonik) anahtar uretir.
 */
class SignalDedupeKeyTest {

    private fun ice(from: String, ts: Long, seq: Long, cand: String) = SignalEnvelope(
        pinHash = "abc",
        ts = ts,
        from = from,
        seq = seq,
        type = SignalEnvelope.TYPE_ICE,
        mid = "0",
        mline = 0,
        cand = cand
    )

    @Test
    fun `ayni milisaniyedeki farkli ice adaylari farkli anahtar uretir`() {
        // Gercek senaryo: Chromium/libwebrtc ICE adaylarini toplu firlatir;
        // birkaci ayni milisaniyeye duser.
        val a = ice(from = "phone42", ts = 1_700_000_000_123L, seq = 1L, cand = "candidate:1 1 UDP 1 192.168.68.52 50000 typ host")
        val b = ice(from = "phone42", ts = 1_700_000_000_123L, seq = 2L, cand = "candidate:2 1 UDP 1 192.168.68.52 50001 typ host")

        assertNotEquals(
            "Ayni milisaniyedeki iki farkli ICE adayi AYNI anahtari uretmemeli",
            a.dedupeKey(),
            b.dedupeKey()
        )
    }

    @Test
    fun `eski anahtar ayni milisaniyedeki ikinci adayi dusuruyordu`() {
        // Eski mantik — duzeltmeden onceki davranisi belgeler.
        fun legacyKey(from: String?, ts: Long, t: String) = (from ?: "") + "|" + ts + "|" + t

        val a = ice(from = "phone42", ts = 1_700_000_000_123L, seq = 1L, cand = "candidate:1")
        val b = ice(from = "phone42", ts = 1_700_000_000_123L, seq = 2L, cand = "candidate:2")

        // Eski anahtar eslestirir (hata), yeni anahtar ayirt eder (duzeltme).
        assertEquals(legacyKey(a.from, a.ts, a.type), legacyKey(b.from, b.ts, b.type))
        assertNotEquals(a.dedupeKey(), b.dedupeKey())
    }

    @Test
    fun `seq olmadiginda eski anahtara donulur (eski istemci uyumlulugu)`() {
        val msg = SignalEnvelope(
            ts = 99L,
            from = "win99",
            seq = 0L,
            type = SignalEnvelope.TYPE_ANSWER
        )
        assertEquals("win99|99|answer", msg.dedupeKey())
    }

    @Test
    fun `ayni mesajin broker tekrari ayni anahtari uretir (fan-out temizligi)`() {
        // MQTT fan-out: ayni mesaj birkac broker'dan birkac kez gelir; bunlar
        // yineleme sayilmali (hala filtrelenir).
        val first = ice(from = "pc7", ts = 5L, seq = 12L, cand = "candidate:9")
        val echo = first.copy()
        assertEquals(first.dedupeKey(), echo.dedupeKey())
    }

    @Test
    fun `seq json anahtari dogru serialize edilir`() {
        val msg = ice(from = "phone42", ts = 7L, seq = 5L, cand = "candidate:1")
        val json = com.google.gson.Gson().toJson(msg)
        org.junit.Assert.assertTrue(
            "seq JSON'da \"seq\":5 olarak yer almalidir: $json",
            json.contains("\"seq\":5")
        )
    }
}
