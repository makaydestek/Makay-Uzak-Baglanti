package com.makay.remote.rtc

import com.makay.remote.core.Primitives
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FileTransferEngine — masaustu `engine.js:packChunk/unpackChunk/chunkCount`
 * ile birebir eslesme.
 *
 * Ikili parca bicimi: <JSON baslik><0x00 ayirici><payload>
 * Baslik anahtar sirasi Windows ile AYNI olmak zorundadir (naive parser riski).
 */
class FileTransferEngineTest {

    private val engine = FileTransferEngine()

    // ---- pack/unpack ----

    @Test
    fun buildChunk_producesWindowsHeaderFormat() {
        val payload = byteArrayOf(1, 2, 3, 4)
        val out = engine.buildChunk("abcd1234", 0, 5, payload)

        val sep = out.indexOfFirst { it.toInt() == 0 }
        assertTrue("ayirici yok", sep > 0)
        val header = String(out, 0, sep, Charsets.UTF_8)

        // Windows: JSON.stringify({t:'chunk', fid, seq, total})
        assertEquals("""{"t":"chunk","fid":"abcd1234","seq":0,"total":5}""", header)
        // ayiricidan sonrasi payload
        assertArrayEquals(payload, out.copyOfRange(sep + 1, out.size))
    }

    @Test
    fun buildParse_roundTripAcrossSeqRange() {
        for (seq in 0..20) {
            val payload = ByteArray(100 + seq) { it.toByte() }
            val packed = engine.buildChunk("fid$seq", seq, 21, payload)
            val parsed = engine.parseChunk(packed)
            assertNotNull("seq=$seq", parsed)
            val (header, data) = parsed!!
            assertEquals("fid$seq", header["fid"])
            assertEquals(seq.toDouble(), (header["seq"] as Number).toDouble(), 0.0)
            assertEquals(21.0, (header["total"] as Number).toDouble(), 0.0)
            assertEquals("chunk", header["t"])
            assertArrayEquals(payload, data)
        }
    }

    /** Payload'un icinde 0x00 gecmesi ayrismayi bozmamali (ilk sifir baslikta). */
    @Test
    fun buildParse_payloadMayContainNulBytes() {
        val payload = ByteArray(64) { 0 }
        val packed = engine.buildChunk("f", 1, 2, payload)
        val parsed = engine.parseChunk(packed)!!
        assertArrayEquals(payload, parsed.second)
        assertEquals("f", parsed.first["fid"])
    }

    @Test
    fun buildParse_emptyPayload() {
        val packed = engine.buildChunk("f", 0, 1, ByteArray(0))
        val parsed = engine.parseChunk(packed)!!
        assertEquals(0, parsed.second.size)
    }

    /** Windows `unpackChunk`: `sep <= 0` -> "Bozuk parca" (null). */
    @Test
    fun parseChunk_rejectsMissingSeparator() {
        assertNull(engine.parseChunk(ByteArray(0)))
        assertNull(engine.parseChunk(byteArrayOf(65, 66, 67))) // hic sifir yok
        assertNull(engine.parseChunk(byteArrayOf(0, 65, 66)))  // ayirici ilk baytta
    }

    @Test
    fun parseChunk_rejectsInvalidHeaderJson() {
        val broken = "bozuk-json".toByteArray(Charsets.UTF_8) + byteArrayOf(0) + byteArrayOf(1)
        // Gson atar -> tasinan exception "Bozuk parca basligi" karsiligi
        try {
            engine.parseChunk(broken)
        } catch (e: Exception) {
            return // beklenen
        }
        // Gson bazi bozuk girdilerde null donebilir; o durumda parse edilmis sayilmaz
        val out = engine.parseChunk(broken)
        assertTrue(out == null || out.first.isEmpty())
    }

    // ---- chunkCount (engine.js:chunkCount) ----

    @Test
    fun chunkCount_matchesWindowsFormula() {
        val cs = Primitives.DEFAULT_CHUNK_SIZE
        assertEquals(0, engine.chunkCount(0))
        assertEquals(0, engine.chunkCount(-1))
        assertEquals(1, engine.chunkCount(1))
        assertEquals(1, engine.chunkCount(cs.toLong()))
        assertEquals(2, engine.chunkCount(cs.toLong() + 1))
        assertEquals(2, engine.chunkCount(cs.toLong() * 2))
        assertEquals(3, engine.chunkCount(cs.toLong() * 2 + 1))
        // tam bolunme
        assertEquals(10, engine.chunkCount(cs.toLong() * 10))
    }

    @Test
    fun chunkCount_is64kibDefault() {
        assertEquals(64 * 1024, Primitives.DEFAULT_CHUNK_SIZE)
    }

    @Test
    fun chunkCount_largeFileDoesNotOverflow() {
        // 100 MB (MAX_FILE_BYTES) — Int bolumunde tasma olmamali
        val size = RemoteGuardMaxFileBytes()
        val cs = Primitives.DEFAULT_CHUNK_SIZE
        val expected = ((size + cs - 1) / cs).toInt()
        assertEquals(expected, engine.chunkCount(size, cs))
        assertTrue(engine.chunkCount(size, cs) > 1000)
    }

    private fun RemoteGuardMaxFileBytes() =
        com.makay.remote.core.RemoteGuard.MAX_FILE_BYTES

    // ---- fid ----

    @Test
    fun newFid_isEightHexCharsAndVaries() {
        val seen = HashSet<String>()
        repeat(500) {
            val fid = engine.newFid()
            assertEquals("fid uzunlugu", 8, fid.length)
            assertTrue("fid hex degil: $fid", fid.all { it in "0123456789abcdef" })
            assertTrue("fid tekrari: $fid", seen.add(fid))
        }
    }
}
