package com.makay.remote.signal

/**
 * TEKLIFTEN ONCE GELEN UZAK ICE ADAYLARI tamponu (SAF, JVM test edilebilir).
 *
 * NEDEN GEREKLI (canli olcumle dogrulandi):
 * Masaustu `engine.js:waitGathering` sirasinda `onIceCandidate` ile adaylari
 * hemen yayar; SDP yalnizca toplama bitince gonderilir. Boylece yayin sirasi
 * KARSI tarafta once ICE, sonra OFFER seklindedir. Gercek olcum: bir Windows
 * host'undan gelen 9 adayin tamami TEKLIFTEN ONCE geldi.
 *
 * Eskiden SessionController `peer?.addRemoteCandidate(...)` cagiriyordu ve o
 * sirada `peer` henuz olusturulmadigi icin bu adaylar SESSIZCE DUSULUYORDU.
 * Sonuc: uzak adaylar yalnizca teklifte GOMULU olanlarla sinirli kaliyordu;
 * toplama yavissa (cok adapter, TURN gecikmesi) uzak aday HIC kalmiyor ve
 * baglanti "Baglaniyor" ekraninda hic kurulamiyordu.
 *
 * Bu sinif adaylari toplar; peer olusturulur olusturulmaz [drain] edilir ve
 * PeerBridge'e verilir (orada remoteDescription gelene dek bekler,
 * `flushRemoteIces()` ile uygulanir).
 */
class IceInbox(private val capacity: Int = 200) {

    private val items = ArrayDeque<SignalEnvelope>()

    /** Adayi koyar; kapasite asilmissa eskisini atarak sinirlar. */
    @Synchronized
    fun add(env: SignalEnvelope) {
        if (items.size >= capacity) items.removeFirst()
        items.addLast(env)
    }

    /** Biriktirilen tum adaylari alir ve kutuyu bosaltir. */
    @Synchronized
    fun drain(): List<SignalEnvelope> {
        if (items.isEmpty()) return emptyList()
        val out = items.toList()
        items.clear()
        return out
    }

    @Synchronized
    fun clear() = items.clear()

    @Synchronized
    fun size(): Int = items.size

    @Synchronized
    fun isEmpty(): Boolean = items.isEmpty()
}
