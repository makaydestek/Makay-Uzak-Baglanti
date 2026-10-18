package com.makay.remote.signal

import com.google.gson.annotations.SerializedName

/**
 * Bulut eslesme (MQTT) sinyal zarfı.
 *
 * Windows `electron/cloud-signal-bridge.js` ile ayni sekil:
 *   zarf = { v, pinHash, ts, from, t, ...yük }
 * Alıcı taraf `pinHash` ve `from` alanlarını zorunlu tutar.
 */
data class SignalEnvelope(
    @SerializedName("v") val version: Int = 1,
    @SerializedName("pinHash") val pinHash: String? = null,
    @SerializedName("ts") val ts: Long = 0L,
    @SerializedName("from") val from: String? = null,
    /**
     * Gonderen bazinda monotonik sira numarasi (her yayinda +1).
     *
     * TELEFONUN ICE ADAYLARININ KAYBINI ONLER: `ts` milisaniye cozunurlugundedir
     * ve ICE adaylari toplu halinde (ayni milisaniyede) gelir. Eski yineleme
     * anahtari `from|ts|t` oldugu icin ayni milisaniyede yayinlanan IKINCI aday
     * "mukerrer" sanilip SESSIZCE DUSURULUYORDU — sonuc olarak baglanti hic
     * kurulamiyordu (HTTP yolunda bu hata biliniyordu ve seen kaldirilmisti;
     * MQTT yolunda kalmisti). Artik yineleme anahtari `from|seq` olur.
     */
    @SerializedName("seq") val seq: Long = 0L,

    @SerializedName("t") val type: String = "",

    // SDP eslesme
    @SerializedName("code") val code: String? = null,

    // Trickle ICE
    @SerializedName("mid") val mid: String? = null,
    @SerializedName("mline") val mline: Int? = null,
    @SerializedName("cand") val cand: String? = null,
    @SerializedName("ufrag") val ufrag: String? = null,

    // Katilim / istek
    @SerializedName("name") val name: String? = null
) {
    /**
     * Yineleme (dedupe) anahtari.
     *
     * `seq` (gonderen bazinda monotonik) varsa onu kullanir — ICE adaylari
     * toplu halinde geldigi icin `ts` (milisaniye) eslestirmeleri adaylari
     * sessizce dusururdu. `seq` yoksa (eski istemci) eski `from|ts|t`
     * anahtarina donulur.
     *
     * Windows `electron/cloud-signal-bridge.js` birebir ayni mantigi uygular.
     */
    fun dedupeKey(): String =
        if (from != null && seq > 0) "$from|$seq" else (from ?: "") + "|" + ts + "|" + type

    companion object {
        const val TYPE_OFFER = "offer"
        const val TYPE_ANSWER = "answer"
        const val TYPE_ICE = "ice"
        const val TYPE_JOIN_REQUEST = "join-request"
        const val TYPE_JOIN_DENIED = "join-denied"
        /** Oturum kapanisi (masaustu `cloudSig.publish({t:'bye'})`). */
        const val TYPE_BYE = "bye"
    }
}