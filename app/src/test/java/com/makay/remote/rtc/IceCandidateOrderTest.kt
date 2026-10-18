package com.makay.remote.rtc

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regresyon testi — org.webrtc.IceCandidate yapici sirasi.
 *
 * Uzak ICE adaylarini eklerken `IceCandidate(cand, mline, mid)` gibi ters bir
 * siralama kullanilabiliyor; bu durumda `sdp` alani "0" gibi gecersiz bir deger
 * aliyor ve `addIceCandidate` adayi sessizce reddediyor. Sonuc olarak hicbir uzak
 * ICE adayi uygulanamiyor ve WebRTC baglantisi (ICE) hic kurulamiyordu.
 *
 * Dogru cagri: `IceCandidate(sdpMid, sdpMLineIndex, sdp)`.
 */
class IceCandidateOrderTest {

    @Test
    fun `constructor sdpMid sdpMLineIndex sdp sirasini takip eder`() {
        val sdpLine = "candidate:842163049 1 udp 1677729535 192.168.68.52 55527 typ host generation 0"
        val c = org.webrtc.IceCandidate("0", 0, sdpLine)

        assertEquals("0", c.sdpMid)
        assertEquals(0, c.sdpMLineIndex)
        assertEquals(sdpLine, c.sdp)
    }

    @Test
    fun `ters siralama sdp alanini gecersiz yapar - duzeltme bunu onluyor`() {
        val sdpLine = "candidate:842163049 1 udp 1677729535 192.168.68.52 55527 typ host generation 0"

        // Hatali kalinan duzene gore: IceCandidate(cand, mline, mid)
        val wrong = org.webrtc.IceCandidate(sdpLine, 0, "0")

        // Alanlarin tersine oturdugunu goruyoruz — bu duzen adayi gecersiz kilar.
        assertEquals(sdpLine, wrong.sdpMid)
        assertEquals("0", wrong.sdp)
    }

    @Test
    fun `bos sdpMid ile aday olusturulur - mline indeksi anahtardir`() {
        val c = org.webrtc.IceCandidate("", 1, "candidate:1 1 udp 1 192.168.1.2 9 typ host")
        assertEquals("", c.sdpMid)
        assertEquals(1, c.sdpMLineIndex)
    }
}
