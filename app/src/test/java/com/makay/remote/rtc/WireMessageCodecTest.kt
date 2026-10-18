package com.makay.remote.rtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Binary kare olarak gelen HELLO/SDP zarflari dosya parcasi sanilmamali.
 * Bu, Android↔Windows "kanal=open ama bagli degil" regresyonunun korumasidir.
 */
class WireMessageCodecTest {

    @Test
    fun helloJson_withoutNullByte_parsesAsEnvelope() {
        val json = """{"t":"hello","sid":"abc","name":"PC","h":"deadbeef"}"""
        val env = WireMessageCodec.tryParseEnvelope(json.toByteArray(Charsets.UTF_8))
        assertNotNull(env)
        assertEquals(WireEnvelope.HELLO, env!!.type)
        assertEquals("abc", env.sid)
        assertEquals("deadbeef", env.hash)
    }

    @Test
    fun fileChunk_withNullSeparator_isNotEnvelope() {
        val head = """{"t":"chunk","fid":"x","seq":0,"total":1}"""
        val bytes = head.toByteArray(Charsets.UTF_8) + byteArrayOf(0) + byteArrayOf(1, 2, 3)
        assertNull(WireMessageCodec.tryParseEnvelope(bytes))
    }

    @Test
    fun nonJson_isNotEnvelope() {
        assertNull(WireMessageCodec.tryParseEnvelope(byteArrayOf(0x01, 0x02)))
        assertNull(WireMessageCodec.tryParseEnvelope(ByteArray(0)))
    }

    @Test
    fun sdpRenegotiation_parsesWithSeparateTypeField() {
        val json = """{"t":"sdp","type":"offer","sdp":"v=0"}"""
        val env = WireMessageCodec.tryParseEnvelope(json.toByteArray(Charsets.UTF_8))
        assertNotNull(env)
        assertEquals(WireEnvelope.SDP, env!!.type)
        assertEquals("offer", env.sdpType)
    }
}
