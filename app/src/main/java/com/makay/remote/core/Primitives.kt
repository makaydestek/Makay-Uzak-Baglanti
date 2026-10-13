package com.makay.remote.core

import java.security.MessageDigest

/**
 * MAKAY Remote — bilesik (primitive) protokol islevleri.
 *
 * Bu dosya, masaustu `apps/remote/engine.js` icindeki saf JS yardimcilarin
 * birebir Kotlin karsiligidir. Cikti baytlari Windows tarafiyla
 * karakter duzeyinde ayni olmak ZORUNDADIR.
 */
object Primitives {

    /** Oda karakterleri — 0/O ve 1/I karistirmasin diye I ve O haric. */
    const val ROOM_ALPHA = "0123456789ABCDEFGHJKLMNPQRSTUVWXYZ"

    const val CODE_PREFIX = "MAKAY"
    const val CODE_VERSION = 1

    const val DEFAULT_CHUNK_SIZE = 64 * 1024

    /**
     * FNV-1a 32-bit kontrol toplami.
     *
     * KRITIK: Masaustu `engine.js` bu islemi JAVASCRIPT `double` ile yapar:
     * `h = (h * 0x01000193) >>> 0`. Carpim ~7.2e16'ya kadar cikar; JS'in tam
     * integer siniri 2^53 (~9.0e15) oldugundan alt bitler bilincli olarak
     * yuvarlanir. `Long` ile TAM hassasiyet hesaplamak baska sonuc verir ve
     * Android'in urettigi SDP kodu masaustunde "Kod bozulmus" diye reddedilir.
     *
     * Bu yuzden carpim `Double` uzerinden yapilir, ardindan JS `>>> 0`
     * (ToUint32: sifira dogru kirp + mod 2^32) taklit edilir. Cikti baytlari
     * masaustuyla karakter duzeyinde ayni olmak ZORUNDADIR.
     *
     * Altin vektorler `engine.js`'ten uretilir (PrimitivesChecksumTest).
     */
    fun checksum32(input: String): String {
        var h = 0x811c9dc5L
        for (ch in input) {
            // JS `h ^= c` — ToInt32 sonucu SIGNED int32 dondurur; alt 32 bit
            // duzlemi XOR ile ayni oldugundan once maskeylenir, sonra signed'a cevrilir.
            val bits = (h and 0xFFFFFFFFL) xor (ch.code.toLong() and 0xFFFFFFFFL)
            val signed = if (bits >= 0x80000000L) bits - 0x100000000L else bits
            // JS `h * 0x01000193` — ayni IEEE-754 double carpimi (kayipli).
            // signed'lar ~2^55, uctsuzlar ~2^64 mertebesinde yuvarlanir;
            // ikisini de ayni buyuklukte tutmadan sonuc farkli olur.
            // (0x01000193 = 16777619; Kotlin hex ondalik literal kabul etmez)
            val product = signed.toDouble() * 16777619.0
            // JS `>>> 0` = ToUint32: sifira dogru kirp, sonra mod 2^32
            h = product.toLong() and 0xFFFFFFFFL
        }
        var hex = java.lang.Long.toHexString(h)
        while (hex.length < 8) hex = "0$hex"
        return hex
    }

    /** URL-safe base64, dolgusuz — `engine.js:bytesToBase64Url` ile ayni. */
    fun bytesToBase64Url(bytes: ByteArray): String =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    /** `engine.js:base64UrlToBytes` ile ayni. */
    fun base64UrlToBytes(b64: String): ByteArray {
        val normalized = b64.trim()
        return if (normalized.isEmpty()) ByteArray(0)
        else java.util.Base64.getUrlDecoder().decode(normalized)
    }

    /** Kucuk harf 64 haneli SHA-256 — `engine.js:sha256Hex` ile ayni. */
    fun sha256Hex(value: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(value.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            sb.append("0123456789abcdef"[v ushr 4])
            sb.append("0123456789abcdef"[v and 0x0F])
        }
        return sb.toString()
    }

    /** 8 haneli oda kodu uretir. */
    fun generateRoom(random: java.util.Random = java.security.SecureRandom()): String {
        val sb = StringBuilder(8)
        repeat(8) { sb.append(ROOM_ALPHA[random.nextInt(ROOM_ALPHA.length)]) }
        return sb.toString()
    }

    /** 16 haneli oturum kimligi (JS `makeFid()+makeFid()`). */
    fun newSessionId(random: java.util.Random = java.security.SecureRandom()): String {
        val sb = StringBuilder(16)
        repeat(16) { sb.append("0123456789abcdef"[random.nextInt(16)]) }
        return sb.toString()
    }

    /** 6 haneli oturum sifresi (JS `100000 + rand(900000)`). */
    fun generateSessionPassword(random: java.util.Random = java.security.SecureRandom()): String =
        (100000 + random.nextInt(900000)).toString()

    /** Oda kodunu gosterim icin "0DH8 H0C0" bicimine cevirir. */
    fun formatRoom(code: String): String {
        val clean = normalizeRoom(code)
        if (clean.length != 8) return clean.ifEmpty { "— — — —" }
        return clean.substring(0, 4) + " " + clean.substring(4)
    }

    /** Bosluk/tire dahil her girdiyi 8 haneye indirger. */
    fun normalizeRoom(raw: String?): String {
        val s = raw.orEmpty().uppercase().filter { it.isLetterOrDigit() }
        return if (s.length > 8) s.substring(0, 8) else s
    }

    /** Kimlik + sifreden davet metni uretir (Windows `buildInviteShareText`). */
    fun buildInviteText(roomCode: String, password: String): String =
        "MAKAY Uzaktan Bağlantı\n" +
            "Kimlik: ${formatRoom(roomCode)}\n" +
            "Şifre: $password"
}