package com.makay.remote.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * InviteParser — masaustu `app.js:parseInvitePaste` ile AYNI kurallar.
 *
 * Davet metni bicimi (Windows `buildInviteShareText`):
 *     MAKAY Uzaktan Bağlantı / Kimlik: XXXX XXXX / Şifre: 123456
 */
class InviteParserTest {

    @Test
    fun parsesStandardInviteText() {
        val raw = "MAKAY Uzaktan Bağlantı\nKimlik: 0DH8 H0C0\nŞifre: 123456"
        val p = InviteParser.parse(raw)
        assertEquals("0DH8H0C0", p.code)
        assertEquals("123456", p.password)
    }

    @Test
    fun parsesBareSpacedCode() {
        val p = InviteParser.parse("0DH8 H0C0")
        assertEquals("0DH8H0C0", p.code)
    }

    @Test
    fun parsesBareEightCharCode() {
        val p = InviteParser.parse("0DH8H0C0")
        assertEquals("0DH8H0C0", p.code)
    }

    @Test
    fun parsesBaglantiKoduLabel() {
        val p = InviteParser.parse("Bağlantı Kodu: 0DH8 H0C0\nŞifre: 654321")
        assertEquals("0DH8H0C0", p.code)
        assertEquals("654321", p.password)
    }

    @Test
    fun parsesDavetKoduLabel() {
        val p = InviteParser.parse("Davet Kodu: ABCD EFGH\nŞifre: 111222")
        assertEquals("ABCDEFGH", p.code)
        assertEquals("111222", p.password)
    }

    /** Kimlik etiketi en ustte oldugundan onceliklidir. */
    @Test
    fun kimlikLabelHasPriority() {
        val raw = "Kimlik: ABCD EFGH\nBağlantı Kodu: 1234 5678\nŞifre: 999888"
        val p = InviteParser.parse(raw)
        assertEquals("ABCDEFGH", p.code)
        assertEquals("999888", p.password)
    }

    @Test
    fun parsesTurkishAndAsciiPasswordLabels() {
        assertEquals("123456", InviteParser.parse("Kimlik: ABCD EFGH\nŞifre: 123456").password)
        assertEquals("123456", InviteParser.parse("Kimlik: ABCD EFGH\nSifre: 123456").password)
        assertEquals("123456", InviteParser.parse("Kimlik: ABCD EFGH\nOturum Şifresi: 123456").password)
        assertEquals("123456", InviteParser.parse("Kimlik: ABCD EFGH\nOturum sifresi: 123456").password)
    }

    @Test
    fun passwordIgnoresTrailingComment() {
        val p = InviteParser.parse("Kimlik: ABCD EFGH\nŞifre: 123456 (paylasiniz)")
        assertEquals("123456", p.password.trim().substringBefore(' '))
    }

    @Test
    fun emptyInputYieldsEmptyFields() {
        assertEquals("", InviteParser.parse("").code)
        assertEquals("", InviteParser.parse("").password)
        assertEquals("", InviteParser.parse("   ").code)
        assertEquals("", InviteParser.parse("merhaba dunya").code)
    }

    /** Kod 8 haneyi asmamali (Windows `take(8)` ile ayni). */
    @Test
    fun codeIsCappedAtEightChars() {
        val p = InviteParser.parse("Kimlik: ABCDEFGH1234")
        assertEquals(8, p.code.length)
        assertEquals("ABCDEFGH", p.code)
    }

    @Test
    fun codeIsUppercased() {
        assertEquals("abcdEFGH".uppercase(), InviteParser.parse("Kimlik: abcd EFGH").code)
    }

    /** Kucuk/bosluk/tire ayraclari temizlenir (normalizeRoom ile ayni yapi). */
    @Test
    fun codeStripsSeparators() {
        val p = InviteParser.parse("Kimlik: 0dh8-h0c0")
        assertEquals("0DH8H0C0", p.code)
    }

    /** Davet metnindeki "MAKAY Uzaktan Baglantı" satiri kod olarak okunmamali. */
    @Test
    fun inviteHeaderIsNotMistakenForCode() {
        val raw = "MAKAY Uzaktan Bağlantı\nKimlik: 1234 5678\nŞifre: 100000"
        val p = InviteParser.parse(raw)
        assertEquals("12345678", p.code)
        assertEquals("100000", p.password)
    }

    /** Windows tarafindaki `fmtRoom` ciktisi "XXXX XXXX" olarak gelir. */
    @Test
    fun parsesFormattedRoomFromClipboard() {
        val p = InviteParser.parse("MAKAY Uzaktan Bağlantı\nKimlik: 0123 4567\nŞifre: 500000")
        assertEquals("01234567", p.code)
        assertEquals("500000", p.password)
    }

    // ---------------- "Sinyal" satiri (HTTP tünel/LAN oda) ----------------

    /** Windows `buildInviteShareText`: "Sinyal: https://…" satiri. */
    @Test
    fun parsesSinyalUrlFromFullInvite() {
        val raw = "MAKAY Uzaktan Bağlantı\n" +
            "Kimlik: 0DH8 H0C0\n" +
            "Şifre: 123456\n" +
            "Sinyal: https://abc-def.trycloudflare.com"
        val p = InviteParser.parse(raw)
        assertEquals("0DH8H0C0", p.code)
        assertEquals("123456", p.password)
        assertEquals("https://abc-def.trycloudflare.com", p.signalUrl)
    }

    /** Sunucu etiketi "Sinyal Sunucusu:" da yazilabilir (Windows regex'i ile ayni). */
    @Test
    fun parsesSinyalSunucusuLabelAndStripsTrailingSlash() {
        val p = InviteParser.parse(
            "Kimlik: 0DH8 H0C0\nŞifre: 123456\nSinyal Sunucusu: http://192.168.1.20:3000/"
        )
        assertEquals("http://192.168.1.20:3000", p.signalUrl)
    }

    /** Bulut davetinde Sinyal satiri yoktur → signalUrl bos kalir (MQTT kullanilir). */
    @Test
    fun cloudInviteHasEmptySignalUrl() {
        val p = InviteParser.parse("MAKAY Uzaktan Bağlantı\nKimlik: 0DH8 H0C0\nŞifre: 123456")
        assertEquals("0DH8H0C0", p.code)
        assertEquals("123456", p.password)
        assertEquals("", p.signalUrl)
    }

    /** URL satir sonunda kesilir; satir devamindaki (diger satirlar) dahil edilmez. */
    @Test
    fun signalUrlStopsAtLineEnd() {
        val p = InviteParser.parse(
            "Kimlik: 0DH8 H0C0\nŞifre: 123456\nSinyal: https://ornek.trycloudflare.com\nNot: burasi dahil degil"
        )
        assertEquals("https://ornek.trycloudflare.com", p.signalUrl)
    }
}
