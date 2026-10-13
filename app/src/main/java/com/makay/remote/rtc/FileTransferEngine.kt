package com.makay.remote.rtc

import com.makay.remote.core.Primitives
import java.io.File

/**
 * Dosya aktarim motoru.
 *
 * Bicim masaustu `engine.js:packChunk/unpackChunk/createFileTransfer` ile ayni:
 *
 *     [ utf8(JSON baslik) ][ 0x00 ][ parca baytlari ]
 *
 * Akis: `file-meta` -> N adet ikili parca -> `file-done`
 */
class FileTransferEngine {

    data class Progress(
        val fid: String,
        val name: String,
        val transferred: Long,
        val size: Long,
        val percent: Int
    )

    data class Received(
        val fid: String,
        val name: String,
        val mime: String,
        val size: Long,
        val bytes: ByteArray
    ) {
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    interface Listener {
        fun onProgress(p: Progress)
        fun onComplete(file: Received)
        fun onCancel(fid: String)
        fun onError(message: String)
    }

    var listener: Listener? = null

    private val incoming = HashMap<String, Entry>()
    private val sending = HashMap<String, Boolean>()

    private class Entry(
        val name: String,
        val size: Long,
        val mime: String,
        val total: Int
    ) {
        /* Sabit uzunluklu dizi yerine dinamik liste: parca sirasi disindan
           gelirse veya toplam bilinmiyorsa AIOOBE uretmez. */
        val chunks = ArrayList<ByteArray?>(total.coerceAtLeast(0))
        var count = 0
    }

    /** Ikili parca ayristirir. Baslik yoksa null. */
    fun parseChunk(buf: ByteArray): Pair<Map<String, Any>, ByteArray>? {
        var sep = -1
        for (i in buf.indices) {
            if (buf[i].toInt() == 0) {
                sep = i
                break
            }
        }
        if (sep <= 0) return null
        val headJson = String(buf, 0, sep, Charsets.UTF_8)
        @Suppress("UNCHECKED_CAST")
        val header = com.makay.remote.core.WireJson.gson.fromJson(headJson, Map::class.java) as Map<String, Any>
        val payload = buf.copyOfRange(sep + 1, buf.size)
        return header to payload
    }

    /** Ikili parca olusturur (Windows'in okuyabilecegi birebir bicim). */
    fun buildChunk(fid: String, seq: Int, total: Int, payload: ByteArray): ByteArray {
        val head = "{\"t\":\"chunk\",\"fid\":\"$fid\",\"seq\":$seq,\"total\":$total}"
        val headBytes = head.toByteArray(Charsets.UTF_8)
        val out = ByteArray(headBytes.size + 1 + payload.size)
        System.arraycopy(headBytes, 0, out, 0, headBytes.size)
        out[headBytes.size] = 0
        System.arraycopy(payload, 0, out, headBytes.size + 1, payload.size)
        return out
    }

    fun chunkCount(size: Long, chunkSize: Int = Primitives.DEFAULT_CHUNK_SIZE): Int =
        if (size <= 0) 0 else ((size + chunkSize - 1) / chunkSize).toInt()

    fun newFid(): String {
        val hex = "0123456789abcdef"
        val sb = StringBuilder(8)
        repeat(8) { sb.append(hex[java.security.SecureRandom().nextInt(16)]) }
        return sb.toString()
    }

    // ---------------- gonderim ----------------

    /** Dosyayi parca parca gonderir. `emit` her parca icin cagrilir. */
    suspend fun sendFile(
        file: File,
        mime: String,
        emit: suspend (ByteArray) -> Unit
    ): String {
        val check = com.makay.remote.core.RemoteGuard.validateFileTransfer(file.name, file.length(), mime)
        if (!check.ok) {
            listener?.onError(check.error ?: "Dosya reddedildi")
            return ""
        }

        val fid = newFid()
        /* Gonderim baslar baslamaz kaydet: cancelSend(fid) gonderim sirasinda
           iptal edebilsin. (Eski kod markSending'i PeerBridge gonderimden SONRA
           cagiriyordu — iptal hiic zamaninda yakalanamazdi.) */
        sending[fid] = false
        val total = chunkCount(file.length())
        /* FILE_META'da `name` ZORUNLU: alici dosyayi bu adla kaydeder. */
        emit(gsonOf(WireEnvelope(type = WireEnvelope.FILE_META, fid = fid, name = file.name, size = file.length(), mime = mime)).toByteArray())

        file.inputStream().use { input ->
            val buffer = ByteArray(Primitives.DEFAULT_CHUNK_SIZE)
            var seq = 0
            var transferred = 0L
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                val slice = if (read == buffer.size) buffer else buffer.copyOf(read)
                emit(buildChunk(fid, seq, total, slice))
                transferred += read
                seq++
                listener?.onProgress(Progress(fid, file.name, transferred, file.length(), percent(transferred, file.length())))
                if (sending[fid] == true) { // iptal edildi
                    sending.remove(fid)
                    return fid
                }
            }
        }

        /* FILE_DONE daima gonderilir: eski kod yalnizca IPTAL edilmisse
           gonderiyordu (kosul ters) — alici dosyayi hic toparlayamazdi. */
        emit(gsonOf(WireEnvelope(type = WireEnvelope.FILE_DONE, fid = fid)).toByteArray())
        sending.remove(fid)
        return fid
    }

    fun cancelSend(fid: String) {
        sending[fid] = true
    }

    fun markSending(fid: String) {
        sending[fid] = false
    }

    fun cancelRecv(fid: String) {
        incoming.remove(fid)
    }

    // ---------------- alim ----------------

    fun onTextEnvelope(env: WireEnvelope) {
        when (env.type) {
            WireEnvelope.FILE_META -> {
                val size = env.size ?: 0L
                incoming[env.fid.orEmpty()] = Entry(
                    env.name.orEmpty(), size, env.mime.orEmpty(),
                    chunkCount(size)
                )
                if (size == 0L) {
                    incoming.remove(env.fid)?.let { listener?.onComplete(Received(env.fid.orEmpty(), it.name, it.mime, 0, ByteArray(0))) }
                } else {
                    listener?.onProgress(Progress(env.fid.orEmpty(), env.name.orEmpty(), 0, size, 0))
                }
            }

            WireEnvelope.FILE_CANCEL -> {
                incoming.remove(env.fid)
                listener?.onCancel(env.fid.orEmpty())
            }

            WireEnvelope.FILE_DONE -> {
                val e = incoming[env.fid] ?: return
                /* Siparişli-güvenilir kanalda FILE_DONE geldiginde tum parcalar
                   gelmis olur; yine de tamamlanmadıysa bekle. */
                if (e.count >= e.total) assemble(env.fid!!)
            }

            else -> Unit
        }
    }

    fun onBinaryChunk(buf: ByteArray) {
        val parsed = parseChunk(buf) ?: return
        val (header, payload) = parsed
        val fid = header["fid"] as? String ?: return
        val seq = (header["seq"] as? Double)?.toInt() ?: return
        val headerTotal = (header["total"] as? Double)?.toInt() ?: return
        val e = incoming[fid] ?: return
        /* Basliktaki toplam kayitla ayni olmali (tutarlilik kontrolu). */
        if (headerTotal != e.total) return
        synchronized(e) {
            /* Parca sirasinin disindan gelirse dinamik genislet (eski kod
               ArrayList'e sinir disi index ile yaziyordu — IndexOutOfBounds).
               Parca basligindaki total yerine kayitli toplam kullanilir. */
            while (e.chunks.size <= seq) e.chunks.add(null)
            if (e.chunks[seq] != null) return // tekrar gelen parca
            e.chunks[seq] = payload
            e.count++

            var transferred = 0L
            for (c in e.chunks) if (c != null) transferred += c.size
            transferred = minOf(e.size, transferred)
            listener?.onProgress(Progress(fid, e.name, transferred, e.size, percent(transferred, e.size)))

            if (e.count >= e.total) assemble(fid)
        }
    }

    private fun assemble(fid: String) {
        val e = incoming.remove(fid) ?: return
        val buf = ByteArray(e.size.toInt())
        var pos = 0
        for (c in e.chunks) {
            if (c == null) continue
            System.arraycopy(c, 0, buf, pos, c.size)
            pos += c.size
        }
        listener?.onComplete(Received(fid, e.name, e.mime, e.size, buf))
    }

    private fun percent(transferred: Long, size: Long): Int =
        if (size > 0) minOf(100, ((transferred * 100) / size).toInt()) else 100

    companion object {
        private val gson = com.makay.remote.core.WireJson.gson
        fun gsonOf(env: WireEnvelope): String = gson.toJson(env)
    }
}