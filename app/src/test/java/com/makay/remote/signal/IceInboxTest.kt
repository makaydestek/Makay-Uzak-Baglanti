package com.makay.remote.signal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [IceInbox] — tekliften ONCE gelen uzak ICE adaylarinin tamponlanmasi.
 *
 * Bu regresyonun konusu (canli olcumle dogrulandi): masaustu, ICE adaylarini
 * `waitGathering` sirasinda toplama yaparken hemen yayar; SDP'yi yalnizca
 * toplama bitince gonderir. Boylece telefonda yayin sirasi ONCE ICE,
 * SONRA OFFER olur — bir Windows host'undan gelen 9 adayin tamami tekliften
 * once geldi.
 *
 * Eski kod `peer?.addRemoteCandidate(...)` idi; peer o sirada null oldugu icin
 * bu adaylar SESSIZCE DUSULUYORDU ve geri getirilemiyordu. Sonuc: uzak adaylar
 * yalnizca teklifte GOMULU olanlarla sinirli kaliyor, ICE hic tamamlanamiyor
 * ve uygulama "Baglaniyor" ekraninda takiliyordu.
 */
class IceInboxTest {

    private fun ice(mid: String, cand: String) = SignalEnvelope(
        type = SignalEnvelope.TYPE_ICE, mid = mid, mline = 0, cand = cand
    )

    @Test
    fun `tekliften once gelen adaylar saklanir ve peer olusturulunca tam olarak cikar`() {
        val inbox = IceInbox()
        // Gercek sirayla: Windows once adaylari, sonra teklifi yayar.
        inbox.add(ice("0", "candidate:1 1 udp 1 192.168.68.52 50000 typ host"))
        inbox.add(ice("0", "candidate:2 1 udp 1 192.168.68.52 50001 typ host"))
        inbox.add(ice("0", "candidate:3 1 udp 1 172.19.64.1  50002 typ host"))

        assertEquals("Tekliften once gelen adaylar kaybolmamali", 3, inbox.size())

        val drained = inbox.drain()

        assertEquals(3, drained.size)
        assertEquals("candidate:1 1 udp 1 192.168.68.52 50000 typ host", drained[0].cand)
        assertEquals("candidate:3 1 udp 1 172.19.64.1  50002 typ host", drained[2].cand)
        assertTrue("Drain sonrasi kutu bos olmali", inbox.isEmpty())
    }

    @Test
    fun `drain sonrasi ikinci cagri bos doner (cift uygulama olmaz)`() {
        val inbox = IceInbox()
        inbox.add(ice("0", "candidate:1"))
        inbox.drain()
        assertTrue(inbox.drain().isEmpty())
    }

    @Test
    fun `kapasite asilmaz`() {
        val inbox = IceInbox(capacity = 5)
        repeat(50) { inbox.add(ice("0", "candidate:$it")) }
        assertEquals("Kapasite siniri korunmali", 5, inbox.size())
        // En yeniler kalmali — eski adaylar atilmali.
        val drained = inbox.drain()
        assertEquals("candidate:45", drained.first().cand)
        assertEquals("candidate:49", drained.last().cand)
    }

    @Test
    fun `reset ile temizlenir (eski oturumun adaylari yeni oturuma tasimaz)`() {
        val inbox = IceInbox()
        inbox.add(ice("0", "candidate:eski-oturum"))
        inbox.clear()
        assertTrue(inbox.isEmpty())
        assertTrue(inbox.drain().isEmpty())
    }

    @Test
    fun `bos kutuda drain bos liste dondurur`() {
        assertTrue(IceInbox().drain().isEmpty())
    }

    @Test
    fun `mid ve mline alanlari korunur`() {
        val inbox = IceInbox()
        inbox.add(SignalEnvelope(type = SignalEnvelope.TYPE_ICE, mid = "1", mline = 2, cand = "candidate:x"))
        val d = inbox.drain().single()
        assertEquals("1", d.mid)
        assertEquals(2, d.mline)
        assertEquals(SignalEnvelope.TYPE_ICE, d.type)
    }
}
