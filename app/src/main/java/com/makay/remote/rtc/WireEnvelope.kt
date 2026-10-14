package com.makay.remote.rtc

import com.google.gson.TypeAdapter
import com.google.gson.annotations.JsonAdapter
import com.google.gson.annotations.SerializedName
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter

/**
 * Veri kanali zarfı — masaustu `engine.js:envelope/parseEnvelope` ile ayni.
 *
 * Metin olarak gonderilir: `{"t":"<tur>", ...}`
 * Dosya parcalari ise ikili (binary) gonderilir.
 */
data class WireEnvelope(
    @SerializedName("t") val type: String = "",

    // hello
    @SerializedName("sid") val sid: String? = null,
    @SerializedName("name") val name: String? = null,
    @SerializedName("h") val hash: String? = null,

    // text
    @SerializedName("text") val text: String? = null,
    @SerializedName("ts") val ts: Long? = null,

    // sdp (yeniden pazarlik) — ic ice JSON'da `t` ile `type` AYRI anahtarlardir
    @SerializedName("type") val sdpType: String? = null,
    @SerializedName("sdp") val sdp: String? = null,

    // dosya meta
    @SerializedName("fid") val fid: String? = null,
    @SerializedName("size") val size: Long? = null,
    @SerializedName("mime") val mime: String? = null,

    // kontrol
    @SerializedName("ev") val ev: ControlPayload? = null,
    @SerializedName("enabled") val enabled: Boolean? = null,

    // ice (data channel uzerinden)
    @SerializedName("mid") val mid: String? = null,
    @SerializedName("mline") val mline: Int? = null,
    @SerializedName("cand") val cand: String? = null,
    @SerializedName("ufrag") val ufrag: String? = null,

    // bye
    @SerializedName("reason") val reason: String? = null
) {
    companion object {
        const val HELLO = "hello"
        const val TEXT = "text"
        const val SDP = "sdp"
        const val ICE = "ice"
        const val BYE = "bye"
        const val VIDEO_STOP = "video-stop"
        const val CTRL_REQ = "ctrl-req"
        const val CTRL_OK = "ctrl-ok"
        const val CTRL_DENY = "ctrl-deny"
        const val CTRL_STOP = "ctrl-stop"
        const val CTRL = "ctrl"
        const val CLIP = "clip"
        const val MIC = "mic"
        const val FILE_META = "file-meta"
        const val FILE_DONE = "file-done"
        const val FILE_CANCEL = "file-cancel"
    }
}

/**
 * Kontrol girdisi — KANONIK TEL BICIMI. Masaustu `app.js:onControl` ve Android
 * `RemoteGuard.sanitizeControl` bu alanlari bekler:
 *   ev.type ∈ mousemove | mousedown | mouseup | wheel | keydown | keyup
 *  - mousemove: x, y (0..1 oran)
 *  - mousedown/mouseup: b (left|right|middle), x, y opsiyonel
 *  - wheel: d (tekerlek delta, ±40)
 *  - keydown/keyup: k (tuş adi; yon type'tan gelir)
 * `d` alaninin ANLAMI yalniz wheel delta'dir; tus basili durumu type'tadir.
 */
data class ControlPayload(
    @SerializedName("type") val type: String,
    @SerializedName("x") val x: Double? = null,
    @SerializedName("y") val y: Double? = null,
    @SerializedName("b") val b: String? = null,
    @JsonAdapter(DeltaJsonAdapter::class)
    @SerializedName("d") val d: Int? = null,
    @SerializedName("k") val k: String? = null
) {
    companion object {
        const val MOUSE_MOVE = "mousemove"
        const val MOUSE_DOWN = "mousedown"
        const val MOUSE_UP = "mouseup"
        const val WHEEL = "wheel"
        const val KEY_DOWN = "keydown"
        const val KEY_UP = "keyup"
    }
}

/**
 * `d` ALANI ICIN IKI BICIMLI OKUYUCU.
 *
 * TEL UYUMU KRITIK: masaustu `app.js:onCtrlWheel` `d`'yi SAYI, ama
 * `onCtrlKeyDown/Up` `d`'yi BOOLEAN (`d: true/false`) gonderir. Alan `Int?`
 * okunursa Gson `boolean`->`int` donusumunde hata verir ve
 * `PeerBridge.onMessage` icindeki `runCatching{}.getOrNull()` bunu NULL'a
 * cevirir — yani tus olayi SESSIZCE KAYBOLUR (test
 * `windowsKeyEnvelope_booleanDDoesNotDropEvent` bunu belgeler).
 *
 * Bu adapter gelen her iki bicimi de `Int?`'ye indirger:
 *  - NUMBER -> delta (wheel)
 *  - BOOLEAN -> 1/0 (Windows tus olayi; yon zaten `type`'ta oldugu icin
 *    yalnizca tolerans amaclidir)
 */
class DeltaJsonAdapter : TypeAdapter<Int?>() {
    override fun write(out: JsonWriter, value: Int?) {
        if (value == null) out.nullValue() else out.value(value)
    }

    override fun read(inp: JsonReader): Int? = when (inp.peek()) {
        JsonToken.NULL -> { inp.nextNull(); null }
        JsonToken.BOOLEAN -> if (inp.nextBoolean()) 1 else 0
        JsonToken.NUMBER -> inp.nextInt()
        JsonToken.STRING -> inp.nextString().toIntOrNull()
        else -> { inp.skipValue(); null }
    }
}