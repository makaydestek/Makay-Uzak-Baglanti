package com.makay.remote.core

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.annotations.SerializedName

/**
 * MAKAY Remote — SDP eslesme kodu.
 *
 * Bicim (masaustu `engine.js:encodeSdp/decodeSdp` ile birebir):
 *
 *     MAKAY1:<O|A>:<checksum32(base64)>:<base64url(JSON)>
 *
 * JSON govdesi: `{"sdp":..., "name":..., "sid":..., "k":"O|A"}`
 *
 * Windows tarafi bu kodu dogrudan `decodeSdp` ile cozer; bu yuzden
 * anahtar sirasi, JSON bicimi ve kontrol toplami ayni kalmalidir.
 */
object SdpCode {

    private val gson = WireJson.gson

    private data class Payload(
        @SerializedName("sdp") val sdp: String? = null,
        @SerializedName("name") val name: String? = null,
        @SerializedName("sid") val sid: String? = null,
        @SerializedName("k") val k: String? = null
    )

    data class Decoded(
        val sdp: String,
        val name: String,
        val sessionId: String,
        val kind: String
    )

    /**
     * @param kind "O" = teklif (baglatan), "A" = cevap (katilan)
     */
    fun encode(sdp: String, name: String, sessionId: String, kind: String): String {
        val k = if (kind == "A") "A" else "O"
        // Anahtar sirasi engine.js ile ayni: sdp, name, sid, k
        val payload = "{\"sdp\":${gson.toJson(sdp)}," +
            "\"name\":${gson.toJson(name.take(60))}," +
            "\"sid\":${gson.toJson(sessionId)}," +
            "\"k\":\"$k\"}"
        val b64 = Primitives.bytesToBase64Url(payload.toByteArray(Charsets.UTF_8))
        return "${Primitives.CODE_PREFIX}${Primitives.CODE_VERSION}:$k:${Primitives.checksum32(b64)}:$b64"
    }

    /**
     * @throws IllegalArgumentException kod bozulmus/eksik/gecersizse
     */
    fun decode(rawCode: String): Decoded {
        val code = rawCode.trim()
        val parts = code.split(':')
        if (parts.size != 4) throw IllegalArgumentException("Kod formatı geçersiz")
        val pre = parts[0]
        val kind = parts[1]
        val check = parts[2]
        val b64 = parts[3]

        if (pre != Primitives.CODE_PREFIX + Primitives.CODE_VERSION)
            throw IllegalArgumentException("Bilinmeyen kod sürümü")
        if (kind != "O" && kind != "A")
            throw IllegalArgumentException("Geçersiz kod türü")
        if (Primitives.checksum32(b64) != check)
            throw IllegalArgumentException("Kod bozulmuş veya eksik — tekrar kopyalayın")

        val json = String(Primitives.base64UrlToBytes(b64), Charsets.UTF_8)
        val parsed = try {
            gson.fromJson(json, Payload::class.java)
        } catch (e: JsonSyntaxException) {
            throw IllegalArgumentException("Kod çözülemedi")
        } ?: throw IllegalArgumentException("Kod içeriği geçersiz")

        val sdp = parsed.sdp
        if (sdp.isNullOrEmpty() || sdp.length < 10)
            throw IllegalArgumentException("Kod içeriği geçersiz")

        return Decoded(
            sdp = sdp,
            name = parsed.name.orEmpty(),
            sessionId = parsed.sid.orEmpty(),
            kind = kind
        )
    }
}