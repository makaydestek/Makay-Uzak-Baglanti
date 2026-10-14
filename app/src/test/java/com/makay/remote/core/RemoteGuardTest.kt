package com.makay.remote.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `RemoteGuard.sanitizeControl` — KONTROL OLAYI TEL SINIRI.
 *
 * Bu sinir IKI yonde de calisir:
 *  - CIKIS: Android -> Windows (`SessionController.sendControl`)
 *  - GELIS : Windows -> Android (`SessionController.onControl`)
 *
 * GELIS yonu ozel olarak kritiktir: telefonu uzaktan kontrol eden tarafin
 * gonderdigi her olay buradan gecer. Asagi testler sinirin hem asiri degerleri
 * dusurdugunu hem de GECERLI olaylari (ozellikle koordinatsiz tiklama)
 * dusurmedigini belgeler.
 */
class RemoteGuardTest {

    // ---------------- gelen yonun kritik davranisi ----------------

    /**
     * KRITIK: koordinatsiz `mousedown`/`mouseup` KABUL EDILIR.
     *
     * `RemoteControlService` icin bu hayati onemdedir — bu degerler dusseydi
     * tiklama planlamasi (`ControlPlanner`) hic mouseup gormez ve DOKUNMALAR
     * HICBIR ZAMAN CALISMAZDI.
     */
    @Test
    fun buttonEvents_withoutCoordinates_areAccepted() {
        val down = RemoteGuard.sanitizeControl("mousedown", null, null, null, null, null)
        val up = RemoteGuard.sanitizeControl("mouseup", null, null, null, null, null)
        assertNotNull("koordinatsiz mousedown kabul edilmeli", down)
        assertNotNull("koordinatsiz mouseup kabul edilmeli", up)

        val d = down as RemoteGuard.ControlEvent.Button
        assertEquals("left", d.button)
        assertEquals(true, d.down)
        assertNull(d.x)
        assertNull(d.y)

        val u = up as RemoteGuard.ControlEvent.Button
        assertEquals(false, u.down)
    }

    /** `mousemove` koordinatsiz OLAMAZ (imlecsiz Android'de zaten anlamsiz). */
    @Test
    fun moveEvent_withoutCoordinates_isDropped() {
        assertNull(
            RemoteGuard.sanitizeControl("mousemove", 0.5, null, null, null, null)
        )
        assertNull(
            RemoteGuard.sanitizeControl("mousemove", null, null, null, null, null)
        )
    }

    // ---------------- koordinatlar ----------------

    /** 0..1 oran aynen gecer; piksel (>1) de gecer (masaustu InputRelay.cs). */
    @Test
    fun coordinates_acceptBothRatioAndPixelForms() {
        val ratio = RemoteGuard.sanitizeControl("mousemove", 0.5, 0.25, null, null, null)
            as RemoteGuard.ControlEvent.Move
        assertEquals(0.5, ratio.x, 0.0)
        assertEquals(0.25, ratio.y, 0.0)

        val pixel = RemoteGuard.sanitizeControl("mousemove", 1080.0, 1920.0, null, null, null)
            as RemoteGuard.ControlEvent.Move
        assertEquals(1080.0, pixel.x, 0.0)
        assertEquals(1920.0, pixel.y, 0.0)
    }

    /** Negatif / sonsuz / NaN / 16000 ustu degerler olayi dusurur. */
    @Test
    fun coordinates_outOfRangeAreDropped() {
        assertNull(RemoteGuard.sanitizeControl("mousemove", -0.1, 0.5, null, null, null))
        assertNull(RemoteGuard.sanitizeControl("mousemove", 0.5, 16001.0, null, null, null))
        assertNull(RemoteGuard.sanitizeControl("mousemove", Double.NaN, 0.5, null, null, null))
        assertNull(
            RemoteGuard.sanitizeControl("mousemove", Double.POSITIVE_INFINITY, 0.5, null, null, null)
        )
    }

    // ---------------- tekerlek ----------------

    /** Masaustu delta SINIRLAMAZ; bu sinir ±40'a kisitlar (ControlPlanner ile ayni). */
    @Test
    fun wheel_isClampedToForty() {
        val big = RemoteGuard.sanitizeControl("wheel", null, null, null, 5000, null)
            as RemoteGuard.ControlEvent.Wheel
        assertEquals(40, big.delta)

        val neg = RemoteGuard.sanitizeControl("wheel", null, null, null, -999, null)
            as RemoteGuard.ControlEvent.Wheel
        assertEquals(-40, neg.delta)

        val none = RemoteGuard.sanitizeControl("wheel", null, null, null, null, null)
            as RemoteGuard.ControlEvent.Wheel
        assertEquals(0, none.delta)
    }

    // ---------------- tuslar ----------------

    /** Yon type'tadir; `d` alani bu sinirdan gecmez (tusta anlamsizdir). */
    @Test
    fun key_eventsKeepDirectionFromType() {
        val down = RemoteGuard.sanitizeControl("keydown", null, null, null, null, "esc")
            as RemoteGuard.ControlEvent.Key
        assertEquals("esc", down.name)
        assertEquals(true, down.down)

        val up = RemoteGuard.sanitizeControl("keyup", null, null, null, null, "esc")
            as RemoteGuard.ControlEvent.Key
        assertEquals(false, up.down)
    }

    /** Tuş adi 1-32 karakter, JSON ayraci ve kontrol karakteri icermemeli. */
    @Test
    fun key_namesAreValidated() {
        assertNull(
            "33 karakter asilirsa dusmeli",
            RemoteGuard.sanitizeControl("keydown", null, null, null, null, "a".repeat(33))
        )
        assertNull(
            "JSON ayraci icermemeli",
            RemoteGuard.sanitizeControl("keydown", null, null, null, null, "\",{}")
        )
        assertNull(
            "bos olamaz",
            RemoteGuard.sanitizeControl("keydown", null, null, null, null, "")
        )
        assertNotNull(
            RemoteGuard.sanitizeControl("keydown", null, null, null, null, "a".repeat(32))
        )
        assertNotNull(
            RemoteGuard.sanitizeControl("keydown", null, null, null, null, "Enter")
        )
    }

    // ---------------- tur / dugme ----------------

    @Test
    fun unknownEventType_isDropped() {
        assertNull(RemoteGuard.sanitizeControl("shutdown", null, null, null, null, null))
        assertNull(RemoteGuard.sanitizeControl("", null, null, null, null, null))
        assertNull(RemoteGuard.sanitizeControl(null, null, null, null, null, null))
    }

    @Test
    fun buttonName_isValidatedOrDefaulted() {
        val right = RemoteGuard.sanitizeControl("mousedown", null, null, "RIGHT", null, null)
            as RemoteGuard.ControlEvent.Button
        assertEquals("right", right.button)

        assertNull(
            RemoteGuard.sanitizeControl("mousedown", null, null, "x", null, null)
        )
    }
}
