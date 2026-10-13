package com.makay.remote.rtc

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WireEnvelope / ControlPayload — masaustu `engine.js:envelope` ile ayni
 * JSON alan adlari ve zarf semantigi.
 *
 * Alan adlari R8/proguard altinda `@SerializedName` ile korunur; bu test hem
 * protokol uyumunu hem de serializasyonun ayakta kaldigini dogrular.
 */
class WireEnvelopeTest {

    private val gson = Gson()

    @Test
    fun helloEnvelope_usesWindowsFieldNames() {
        val json = gson.toJson(
            WireEnvelope(
                type = WireEnvelope.HELLO,
                sid = "abc123",
                name = "Teknisyen",
                hash = "e3b0c442"
            )
        )
        val map = gson.fromJson(json, Map::class.java)
        assertEquals("hello", map["t"])
        assertEquals("abc123", map["sid"])
        assertEquals("Teknisyen", map["name"])
        assertEquals("e3b0c442", map["h"])
        // Windows `envelope(t,obj)` `t` anahtarini ekler — bos alanlar yayilmaz
        assertFalse(map.containsKey("text"))
        assertFalse(map.containsKey("ev"))
    }

    @Test
    fun textEnvelope_carriesTextAndTs() {
        val json = gson.toJson(
            WireEnvelope(type = WireEnvelope.TEXT, text = "merhaba", ts = 1700000000000L)
        )
        val map = gson.fromJson(json, Map::class.java)
        assertEquals("text", map["t"])
        assertEquals("merhaba", map["text"])
        assertEquals(1700000000000.0, (map["ts"] as Number).toDouble(), 0.0)
    }

    /**
     * KRITIK: `sdp` zarfinda `t` (zarf tipi) ve `type` (offer/answer) AYRI
     * anahtarlardir. Windows `app.js` ikisini de okur; karistirilirsa
     * yeniden pazarlik (renegotiation) bozulur.
     */
    @Test
    fun sdpEnvelope_keepsTAndTypeSeparate() {
        val json = gson.toJson(
            WireEnvelope(type = WireEnvelope.SDP, sdpType = "offer", sdp = "v=0\r\n...")
        )
        val map = gson.fromJson(json, Map::class.java)
        assertEquals("sdp", map["t"])
        assertEquals("offer", map["type"])
        assertEquals("v=0\r\n...", map["sdp"])
    }

    @Test
    fun controlEnvelope_usesEvPayloadWithWireTypeNames() {
        val json = gson.toJson(
            WireEnvelope(
                type = WireEnvelope.CTRL,
                ev = ControlPayload(type = ControlPayload.MOUSE_MOVE, x = 0.5, y = 0.25)
            )
        )
        val map = gson.fromJson(json, Map::class.java)
        assertEquals("ctrl", map["t"])
        val ev = map["ev"] as Map<*, *>
        assertEquals("mousemove", ev["type"])
        assertEquals(0.5, (ev["x"] as Number).toDouble(), 0.0)
        assertEquals(0.25, (ev["y"] as Number).toDouble(), 0.0)
        // Kanonik tel: yon type'tadir, `d` yalniz wheel'de anlamli
        assertNull(ev["d"])
        assertNull(ev["k"])
    }

    @Test
    fun controlPayload_keyEventUsesKField() {
        val json = gson.toJson(
            ControlPayload(type = ControlPayload.KEY_DOWN, k = "enter")
        )
        val map = gson.fromJson(json, Map::class.java)
        assertEquals("keydown", map["type"])
        assertEquals("enter", map["k"])
        assertFalse(map.containsKey("d"))
    }

    @Test
    fun controlPayload_wheelUsesDField() {
        val json = gson.toJson(
            ControlPayload(type = ControlPayload.WHEEL, d = 40)
        )
        val map = gson.fromJson(json, Map::class.java)
        assertEquals("wheel", map["type"])
        assertEquals(40.0, (map["d"] as Number).toDouble(), 0.0)
        assertFalse(map.containsKey("k"))
    }

    @Test
    fun controlPayload_buttonUsesBField() {
        val json = gson.toJson(
            ControlPayload(type = ControlPayload.MOUSE_DOWN, b = "right", x = 10.0, y = 20.0)
        )
        val map = gson.fromJson(json, Map::class.java)
        assertEquals("mousedown", map["type"])
        assertEquals("right", map["b"])
        assertNull(map["d"])
    }

    /** Wire tip sabitleri Windows `app.js` karsilklariyla ayni olmali. */
    @Test
    fun wireTypeConstants_matchWindows() {
        assertEquals("hello", WireEnvelope.HELLO)
        assertEquals("text", WireEnvelope.TEXT)
        assertEquals("sdp", WireEnvelope.SDP)
        assertEquals("ice", WireEnvelope.ICE)
        assertEquals("bye", WireEnvelope.BYE)
        assertEquals("ctrl-req", WireEnvelope.CTRL_REQ)
        assertEquals("ctrl-ok", WireEnvelope.CTRL_OK)
        assertEquals("ctrl-deny", WireEnvelope.CTRL_DENY)
        assertEquals("ctrl-stop", WireEnvelope.CTRL_STOP)
        assertEquals("ctrl", WireEnvelope.CTRL)
        assertEquals("clip", WireEnvelope.CLIP)
        assertEquals("mic", WireEnvelope.MIC)
        assertEquals("file-meta", WireEnvelope.FILE_META)
        assertEquals("file-done", WireEnvelope.FILE_DONE)
        assertEquals("file-cancel", WireEnvelope.FILE_CANCEL)
        assertEquals("video-stop", WireEnvelope.VIDEO_STOP)
    }

    @Test
    fun controlTypeConstants_matchWindows() {
        assertEquals("mousemove", ControlPayload.MOUSE_MOVE)
        assertEquals("mousedown", ControlPayload.MOUSE_DOWN)
        assertEquals("mouseup", ControlPayload.MOUSE_UP)
        assertEquals("wheel", ControlPayload.WHEEL)
        assertEquals("keydown", ControlPayload.KEY_DOWN)
        assertEquals("keyup", ControlPayload.KEY_UP)
    }

    @Test
    fun fileMetaEnvelope_carriesNameSizeMime() {
        val json = gson.toJson(
            WireEnvelope(
                type = WireEnvelope.FILE_META,
                fid = "abcd1234",
                name = "rapor.pdf",
                size = 2048L,
                mime = "application/pdf"
            )
        )
        val map = gson.fromJson(json, Map::class.java)
        assertEquals("file-meta", map["t"])
        assertEquals("abcd1234", map["fid"])
        assertEquals("rapor.pdf", map["name"])
        assertEquals(2048.0, (map["size"] as Number).toDouble(), 0.0)
        assertEquals("application/pdf", map["mime"])
    }

    @Test
    fun roundTrip_preservesOptionalNullOmission() {
        val original = WireEnvelope(type = WireEnvelope.BYE, reason = "kullanici")
        val json = gson.toJson(original)
        val back = gson.fromJson(json, WireEnvelope::class.java)
        assertEquals(original, back)
        assertNull(back.ev)
        assertNull(back.sdp)
    }

    @Test
    fun unknownIncomingFieldsDoNotBreakParsing() {
        // Windows'tan gelebilecek fazladan alanlar Gson tarafinda yok sayilir
        val json = """{"t":"ice","mid":"0","mline":0,"cand":"candidate:...","future":"x"}"""
        val env = gson.fromJson(json, WireEnvelope::class.java)
        assertNotNull(env)
        assertEquals("ice", env!!.type)
        assertEquals("0", env.mid)
        assertEquals("candidate:...", env.cand)
        assertTrue(env.ev == null)
    }

    @Test
    fun iceEnvelope_carriesAllRenegotiationFields() {
        val json = gson.toJson(
            WireEnvelope(
                type = WireEnvelope.ICE,
                mid = "0",
                mline = 0,
                cand = "candidate:1 1 udp 2122260223 192.168.1.5 54321 typ host",
                ufrag = "abcd"
            )
        )
        val map = gson.fromJson(json, Map::class.java)
        assertEquals("ice", map["t"])
        assertEquals("0", map["mid"])
        assertEquals(0.0, (map["mline"] as Number).toDouble(), 0.0)
        assertNotNull(map["cand"])
        assertEquals("abcd", map["ufrag"])
    }
}
