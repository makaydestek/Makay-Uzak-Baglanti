package com.makay.remote.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Primitives — masaustu `apps/remote/engine.js` ile birebir eslesme testleri.
 *
 * ALTIN VEKTORLER Node `engine.js` uzerinden uretilmistir. Android'in
 * urettigi degerler bunlarla KARAKTER DUZEYINDE ayni olmak zorundadir;
 * fark SDP kodunun "Kod bozulmus" diye reddedilmesine yol acar.
 */
class PrimitivesTest {

    // ---- checksum32 (engine.js:checksum32) ----

    /**
     * KRITIK: `checksum32` masaustu tarafinda JAVASCRIPT `double` ile hesaplanir
     * (`(h * 0x01000193) >>> 0`). Carpim ~7.2e16'ya ciktigindan 2^53 ustu
     * bitler bilincli kayar. `Long` ile tam hassasiyet FARKLI sonuc verir.
     */
    @Test
    fun checksum32_matchesEngineGoldenVectors() {
        // engine.js: e.checksum32(...)
        assertEquals("811c9dc5", Primitives.checksum32(""))
        assertEquals("a82fb4a1", Primitives.checksum32("hello"))
        assertEquals("2e60d588", Primitives.checksum32("MAKAY1:O:00000000"))
        assertEquals("37d1e56c", Primitives.checksum32("0DH8H0C0"))
        assertEquals("938e3d90", Primitives.checksum32("TUFLQVk"))
        assertEquals("7bb7d994", Primitives.checksum32("-_--"))
        assertEquals("f36b9e7c", Primitives.checksum32("0DH8H0C0x"))
        assertEquals("6f70625c", Primitives.checksum32("12345678"))
    }

    @Test
    fun checksum32_isStableOnSdpSizedBase64Payload() {
        // Gercek SDP olcusunde base64url — double kaybini tetikleyen uzunluk
        val b64 = "eyJzZHAiOiJ2PTBcclxubz0tIDEgMSBJTiBJUDQgMTI3LjAuMC4xXHJcbnM9LVxyXG50PTAg" +
            "MFxyXG4iLCJuYW1lIjoiVEVLTklLU1lFTiIsInNpZCI6ImFiYzEyM2RlZjQ1NmFiYzAiLCJrIjoiTyJ9"
        assertEquals("03b6f763", Primitives.checksum32(b64))
    }

    /** FNV-1a her girdide ayni olmali (determinizm) — eslesme testi. */
    @Test
    fun checksum32_isDeterministic() {
        val input = "MAKAY1:O:03b6f763:" + "x".repeat(400)
        val first = Primitives.checksum32(input)
        repeat(50) { assertEquals(first, Primitives.checksum32(input)) }
        assertEquals(8, first.length)
        assertTrue(first.all { it in "0123456789abcdef" })
    }

    /** Boya gore farkli sonuc — collison kontrolü (zayif hash oldugu icin 8 hane). */
    @Test
    fun checksum32_differsByContent() {
        val seen = HashSet<String>()
        for (i in 0 until 300) {
            assertTrue(Primitives.checksum32("payload-$i").let { seen.add(it) })
        }
    }

    // ---- sha256Hex (engine.js:sha256Hex / node crypto) ----

    @Test
    fun sha256Hex_matchesKnownVectors() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Primitives.sha256Hex("")
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Primitives.sha256Hex("abc")
        )
        assertEquals(
            "8d969eef6ecad3c29a3a629280e686cf0c3f5d5a86aff3ca12020c923adc6c92",
            Primitives.sha256Hex("123456") // sifre dogrulamasi (hello dunn)
        )
        assertEquals(
            "e43ab4de98c9f400e1a0a98d5bb4ecf09c65b475c867625530f2a4fec3330112",
            Primitives.sha256Hex("makay")
        )
    }

    // ---- base64url (engine.js:bytesToBase64Url) ----

    @Test
    fun base64Url_matchesEngineGoldenVectors() {
        assertEquals("TUFLQVk", Primitives.bytesToBase64Url("MAKAY".toByteArray(Charsets.UTF_8)))
        // URL-safe alfabe: '+'/'/' yerine '-'/'_', dolgu yok
        val binary = byteArrayOf(0xFB.toByte(), 0xFF.toByte(), 0xBE.toByte())
        assertEquals("-_--", Primitives.bytesToBase64Url(binary))
    }

    @Test
    fun base64Url_roundTrip() {
        val samples = listOf("", "a", "ab", "abc", "MAKAY Uzaktan Bağlantı", "{\"t\":\"hello\"}")
        for (s in samples) {
            val encoded = Primitives.bytesToBase64Url(s.toByteArray(Charsets.UTF_8))
            val decoded = String(Primitives.base64UrlToBytes(encoded), Charsets.UTF_8)
            assertEquals(s, decoded)
        }
        // URL-safe alfabe disina cikan karakter olmamali (SDP kodu `:` ile ayrildigi icin)
        val encoded = Primitives.bytesToBase64Url(ByteArray(256) { it.toByte() })
        assertTrue(encoded.none { it == '+' || it == '/' || it == '=' })
        assertEquals(256, Primitives.base64UrlToBytes(encoded).size)
    }

    // ---- oda / sifre uretimi (Windows birebir) ----

    @Test
    fun generateRoom_usesSafeAlphabetAndLength() {
        val random = java.util.Random(42)
        repeat(500) {
            val room = Primitives.generateRoom(random)
            assertEquals(8, room.length)
            assertTrue("oda karakteri guvensiz: $room", room.all { it in Primitives.ROOM_ALPHA })
            // 0/O ve 1/I hariç tutuldu mu
            assertTrue(room.none { it in "OoIi" })
        }
    }

    @Test
    fun generateSessionPassword_isSixDigitsInRange() {
        val random = java.util.Random(7)
        repeat(500) {
            val pwd = Primitives.generateSessionPassword(random)
            assertEquals(6, pwd.length)
            val n = pwd.toInt()
            assertTrue("sifre aralik disi: $pwd", n in 100000..999999)
        }
    }

    @Test
    fun newSessionId_is16HexChars() {
        val random = java.util.Random(1)
        repeat(200) {
            val sid = Primitives.newSessionId(random)
            assertEquals(16, sid.length)
            assertTrue(sid.all { it in "0123456789abcdef" })
        }
    }

    // ---- oda gosterimi ----

    @Test
    fun formatRoom_insertsSpaceAfterFourthChar() {
        assertEquals("0DH8 H0C0", Primitives.formatRoom("0DH8H0C0"))
        assertEquals("0DH8 H0C0", Primitives.formatRoom("0DH8 H0C0"))
    }

    @Test
    fun normalizeRoom_stripsSeparatorsAndUppercases() {
        assertEquals("0DH8H0C0", Primitives.normalizeRoom("0dh8 h0c0"))
        assertEquals("0DH8H0C0", Primitives.normalizeRoom("0DH8-H0C0"))
        assertEquals("0DH8H0C0", Primitives.normalizeRoom("0DH8_H0C0"))
        assertEquals("", Primitives.normalizeRoom(null))
        // 8 haneden uzunsa kirpilir
        assertEquals("0DH8H0C0", Primitives.normalizeRoom("0DH8H0C0XYZ"))
    }

    /** Oda alfabesi Windows `app.js` ile ayni olmali (0/O ve 1/I disinda). */
    @Test
    fun roomAlphabet_matchesWindows() {
        assertEquals("0123456789ABCDEFGHJKLMNPQRSTUVWXYZ", Primitives.ROOM_ALPHA)
        assertEquals(34, Primitives.ROOM_ALPHA.length)
    }

    // ---- davet metni ----

    @Test
    fun buildInviteText_matchesWindowsFormat() {
        assertEquals(
            "MAKAY Uzaktan Bağlantı\nKimlik: 0DH8 H0C0\nŞifre: 123456",
            Primitives.buildInviteText("0DH8H0C0", "123456")
        )
    }
}
