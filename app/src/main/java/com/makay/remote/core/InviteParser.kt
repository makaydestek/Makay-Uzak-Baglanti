package com.makay.remote.core

/**
 * MAKAY Remote — davet metni ayristirici (SAF, JVM test edilebilir).
 *
 * Windows `app.js:parseInvitePaste` ile AYNI kurallar:
 *  1) "Kimlik: XXXX XXXX"
 *  2) "Baglanti/Davet Kodu: ..."
 *  3) duz "XXXX XXXX" ya da "XXXXXXXX"
 *  sifre: "Sifre:" / "Oturum Sifresi:" satiri
 *  sinyal: "Sinyal: https://..." satiri (varsa — HTTP tünel/LAN odaya
 *          o adresten katilinir; bkz. SessionController.join)
 *
 * Ornek metot `SessionController.parseInvite` bu sinifi cagirir; ayristirma
 * mantigi TEK yerdedir ve masaustu regex karsilklariyla birebir test edilir.
 */
object InviteParser {

    data class Parsed(val code: String, val password: String, val signalUrl: String = "")

    // Windows parseInvitePaste regexleri ile ayni anlam
    private val KIMLIK_RE = Regex(
        "Kimlik\\s*:?\\s*([0-9A-Za-z][0-9A-Za-z\\s\\-]{2,14})",
        RegexOption.IGNORE_CASE
    )
    private val KOD_RE = Regex(
        "(?:Bağlantı|Davet)\\s*Kodu\\s*:?\\s*([0-9A-Za-z][0-9A-Za-z\\s\\-]{2,14})",
        RegexOption.IGNORE_CASE
    )
    private val BARE_SPACED_RE = Regex("\\b([0-9A-Z]{4}\\s*[0-9A-Z]{4})\\b", RegexOption.IGNORE_CASE)
    private val BARE_RE = Regex("\\b([0-9A-Z]{8})\\b")
    private val PASSWORD_RE = Regex(
        "(?:Sifre|Şifre|Oturum\\s*[SsŞş]ifresi)\\s*:?\\s*([^\\r\\n]+)",
        RegexOption.IGNORE_CASE
    )
    // Windows app.js:696 — /Sinyal\s*(?:Sunucusu)?\s*:?\s*(https?:\/\/[^\s\r\n]+)/i
    private val SINYAL_RE = Regex(
        "Sinyal\\s*(?:Sunucusu)?\\s*:?\\s*(https?://[^\\s\\r\\n]+)",
        RegexOption.IGNORE_CASE
    )

    fun parse(raw: String): Parsed {
        val text = raw.trim()
        var code = ""

        KIMLIK_RE.find(text)?.let { code = it.groupValues[1] }
        if (code.isEmpty()) KOD_RE.find(text)?.let { code = it.groupValues[1] }
        if (code.isEmpty()) BARE_SPACED_RE.find(text)?.let { code = it.groupValues[1] }
        if (code.isEmpty()) BARE_RE.find(text)?.let { code = it.groupValues[1] }

        val clean = code.filter { it.isLetterOrDigit() }.uppercase().take(8)
        val pwd = PASSWORD_RE.find(text)?.groupValues?.get(1)?.trim().orEmpty()
        val signal = SINYAL_RE.find(text)?.groupValues?.get(1)?.trim()?.trimEnd('/').orEmpty()
        return Parsed(clean, pwd, signal)
    }
}
