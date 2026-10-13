package com.makay.remote.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * SdpCode — masaustu `engine.js:encodeSdp/decodeSdp` ile birebir eslesme.
 *
 * ALTIN VEKTORLER Node `engine.js` uzerinden uretilmistir. Android'in
 * urettigi kod Windows `decodeSdp` tarafindan cozulebilmelidir; aksi halde
 * eslesme "Kod bozulmus" hatasiyla biter.
 */
class SdpCodeTest {

    private val sdpO = "v=0\r\no=- 1 1 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\n"
    private val goldenO =
        "MAKAY1:O:03b6f763:eyJzZHAiOiJ2PTBcclxubz0tIDEgMSBJTiBJUDQgMTI3LjAuMC4xXHJcbnM9LVxyXG50PTAg" +
            "MFxyXG4iLCJuYW1lIjoiVEVLTklLU1lFTiIsInNpZCI6ImFiYzEyM2RlZjQ1NmFiYzAiLCJrIjoiTyJ9"

    private val sdpA = "v=0\r\no=- 9 9 IN IP4 0.0.0.0\r\ns=-\r\nt=0 0\r\n"
    private val goldenA =
        "MAKAY1:A:3ba7074c:eyJzZHAiOiJ2PTBcclxubz0tIDkgOSBJTiBJUDQgMC4wLjAuMFxyXG5zPS1cclxudD0wIDBcclxu" +
            "IiwibmFtZSI6Ik1VU1RFUkkiLCJzaWQiOiJmZWRjYmE5ODc2NTQzMjEwIiwiayI6IkEifQ"

    /** engine.js `encodeSdp` ciktisi ile birebir ayni olmali. */
    @Test
    fun encode_producesEngineGoldenOffer() {
        val code = SdpCode.encode(sdpO, "TEKNIKSYEN", "abc123def456abc0", "O")
        assertEquals(goldenO, code)
    }

    @Test
    fun encode_producesEngineGoldenAnswer() {
        val code = SdpCode.encode(sdpA, "MUSTERI", "fedcba9876543210", "A")
        assertEquals(goldenA, code)
    }

    /** Android'in urettigi kodu Windows `decodeSdp` benzeri kural ile dogrula. */
    @Test
    fun decode_verifiesChecksumLikeEngine() {
        val code = SdpCode.encode(sdpO, "TEKNIKSYEN", "abc123def456abc0", "O")
        val parts = code.split(':')
        assertEquals(4, parts.size)
        assertEquals("MAKAY1", parts[0])
        assertEquals("O", parts[1])
        // engine.js: checksum32(b64) === check
        assertEquals(parts[2], Primitives.checksum32(parts[3]))
    }

    @Test
    fun roundTrip_preservesAllFields() {
        for (kind in listOf("O", "A")) {
            val code = SdpCode.encode(sdpO, "Teknisyen PC", "0123456789abcdef", kind)
            val d = SdpCode.decode(code)
            assertEquals(sdpO, d.sdp)
            assertEquals("Teknisyen PC", d.name)
            assertEquals("0123456789abcdef", d.sessionId)
            assertEquals(kind, d.kind)
        }
    }

    /** engine.js icinde `name` 60 karakterle sinirlanir. */
    @Test
    fun encode_truncatesNameTo60() {
        val longName = "N".repeat(200)
        val d = SdpCode.decode(SdpCode.encode(sdpO, longName, "sid", "O"))
        assertEquals(60, d.name.length)
    }

    @Test
    fun encode_kindFallsBackToOffer() {
        val d = SdpCode.decode(SdpCode.encode(sdpO, "n", "sid", "X"))
        assertEquals("O", d.kind)
    }

    /** Baslik `:` ile ayrildigi icin sdp'de `:` olmasi bolme sayisini bozmamali. */
    @Test
    fun decode_handlesColonInsideSdp() {
        val tricky = "v=0\r\nc=IN IP4 10.0.0.1\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=rtpmap:111 opus/48000\r\n"
        val d = SdpCode.decode(SdpCode.encode(tricky, "ad", "sid", "O"))
        assertEquals(tricky, d.sdp)
        assertEquals("O", d.kind)
    }

    // ---- hata yollari (engine.js decodeSdp ayni mesajlarla reddeder) ----

    private fun assertRejects(raw: String, reason: String) {
        try {
            SdpCode.decode(raw)
            fail("reddedilmeliydi ($reason): $raw")
        } catch (e: IllegalArgumentException) {
            // beklenen
        }
    }

    @Test
    fun decode_rejectsMalformed() {
        assertRejects("", "bos")
        assertRejects("MAKAY1:O:03b6f763", "eksik bolum")
        assertRejects("MAKAY1:O:03b6f763:aaa:bbb", "fazla bolum")
        assertRejects("MAKAY2:O:03b6f763:aaa", "yanlis surum")
        assertRejects("MAKAY1:X:03b6f763:aaa", "yanlis tur")
        assertRejects("MAKAY1:O:deadbeef:aaa", "checksum uyusmuyor")
    }

    /** Checksum'u kirpilmis payload'u reddetmeli (engine.js "Kod bozulmus"). */
    @Test
    fun decode_rejectsTamperedPayload() {
        val code = SdpCode.encode(sdpO, "n", "sid", "O")
        val parts = code.split(':')
        val tampered = parts[3].dropLast(2) + "xx"
        assertRejects("${parts[0]}:${parts[1]}:${parts[2]}:$tampered", "kirik payload")
    }

    @Test
    fun decode_rejectsJsonButValidChecksum() {
        // Checksum dogru ama govde gecersiz JSON
        val payload = Primitives.bytesToBase64Url("bozuk json".toByteArray(Charsets.UTF_8))
        val bad = "MAKAY1:O:${Primitives.checksum32(payload)}:$payload"
        assertRejects(bad, "gecersiz json")
    }

    @Test
    fun decode_rejectsJsonWithoutSdp() {
        val payload = Primitives.bytesToBase64Url(
            "{\"name\":\"x\",\"sid\":\"y\",\"k\":\"O\"}".toByteArray(Charsets.UTF_8)
        )
        val bad = "MAKAY1:O:${Primitives.checksum32(payload)}:$payload"
        assertRejects(bad, "sdp yok")
    }

    /** engine.js: `payload.sdp.length < 10` -> "Kod icerigi gecersiz" */
    @Test
    fun decode_rejectsTooShortSdp() {
        val payload = Primitives.bytesToBase64Url(
            "{\"sdp\":\"123456789\",\"name\":\"\",\"sid\":\"\",\"k\":\"O\"}".toByteArray(Charsets.UTF_8)
        )
        val bad = "MAKAY1:O:${Primitives.checksum32(payload)}:$payload"
        assertRejects(bad, "sdp cok kisa")
    }

    @Test
    fun decode_trimsSurroundingWhitespace() {
        val code = SdpCode.encode(sdpO, "n", "sid", "O")
        val d = SdpCode.decode("  \n$code\t ")
        assertEquals(sdpO, d.sdp)
    }

    @Test
    fun decode_acceptsGoldenOfferFromEngine() {
        val d = SdpCode.decode(goldenO)
        assertEquals(sdpO, d.sdp)
        assertEquals("TEKNIKSYEN", d.name)
        assertEquals("abc123def456abc0", d.sessionId)
        assertEquals("O", d.kind)
    }

    @Test
    fun decode_acceptsGoldenAnswerFromEngine() {
        val d = SdpCode.decode(goldenA)
        assertEquals(sdpA, d.sdp)
        assertEquals("MUSTERI", d.name)
        assertEquals("fedcba9876543210", d.sessionId)
        assertEquals("A", d.kind)
    }

    @Test
    fun codePrefixAndVersion_matchWindows() {
        assertEquals("MAKAY", Primitives.CODE_PREFIX)
        assertEquals(1, Primitives.CODE_VERSION)
        assertTrue(SdpCode.encode(sdpO, "n", "sid", "O").startsWith("MAKAY1:"))
    }
}
