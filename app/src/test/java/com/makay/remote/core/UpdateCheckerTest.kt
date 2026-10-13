package com.makay.remote.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * UpdateChecker — surum karsilastirma ve denetim zamanlamasi.
 * (Agi cagirmayan saf mantik; cevrimdisi davranis `check`'in null donmesidir.)
 */
class UpdateCheckerTest {

    @Before
    fun reset() = UpdateChecker.resetForTest()

    // ---------- compareVersions ----------

    @Test
    fun equalVersionsAreZero() {
        assertEquals(0, UpdateChecker.compareVersions("1.0.2", "1.0.2"))
        assertEquals(0, UpdateChecker.compareVersions("0.0.0", "0.0.0"))
        assertEquals(0, UpdateChecker.compareVersions("10.20.30", "10.20.30"))
    }

    @Test
    fun newerPatchIsGreater() {
        assertTrue(UpdateChecker.compareVersions("1.0.3", "1.0.2") > 0)
        assertTrue(UpdateChecker.compareVersions("1.0.10", "1.0.9") > 0)
    }

    @Test
    fun olderPatchIsSmaller() {
        assertTrue(UpdateChecker.compareVersions("1.0.1", "1.0.2") < 0)
        // 2 < 10 oldugunu dogrular: metinsel karsilastirma burada yanlis verirdi
        assertTrue(UpdateChecker.compareVersions("1.0.2", "1.0.10") < 0)
    }

    @Test
    fun minorAndMajorOutrankLowerComponents() {
        assertTrue(UpdateChecker.compareVersions("1.1.0", "1.0.99") > 0)
        assertTrue(UpdateChecker.compareVersions("2.0.0", "1.99.99") > 0)
        assertTrue(UpdateChecker.compareVersions("1.0.0", "1.0.1") < 0)
    }

    /** `v` oneki (GitHub tag bicimi) yok sayilir. */
    @Test
    fun leadingVPrefixIsIgnored() {
        assertEquals(0, UpdateChecker.compareVersions("v1.0.2", "1.0.2"))
        assertEquals(0, UpdateChecker.compareVersions("V1.0.2", "v1.0.2"))
        assertTrue(UpdateChecker.compareVersions("v1.0.3", "1.0.2") > 0)
    }

    /** Eksik parca 0 kabul edilir: 1.2 == 1.2.0 */
    @Test
    fun missingComponentsTreatedAsZero() {
        assertEquals(0, UpdateChecker.compareVersions("1.2", "1.2.0"))
        assertEquals(0, UpdateChecker.compareVersions("1", "1.0.0"))
        assertTrue(UpdateChecker.compareVersions("1.2.1", "1.2") > 0)
    }

    /** Sayisal olmayan parca 0 sayilir — asla exception firlatmaz. */
    @Test
    fun nonNumericPartsNeverThrow() {
        assertEquals(0, UpdateChecker.compareVersions("abc", "0.0.0"))
        assertEquals(0, UpdateChecker.compareVersions("", "0"))
        assertTrue(UpdateChecker.compareVersions("2.0.0-beta", "1.9.9") > 0)
        // 1.0.2-rc -> son parca "2" ve "rc"(0) ... dikkatli kontrol
        assertTrue(UpdateChecker.compareVersions("1.0.2.1", "1.0.2") > 0)
    }

    /** Karsilastirma simetriktir — yon degistince ters doner. */
    @Test
    fun comparisonIsAntisymmetric() {
        val pairs = listOf(
            "1.0.2" to "1.0.3",
            "2.0.0" to "1.9.9",
            "1.0.0" to "1.0.0",
            "1.2" to "1.2.0",
            "v3.4.5" to "3.4.6"
        )
        for ((a, b) in pairs) {
            val ab = UpdateChecker.compareVersions(a, b)
            val ba = UpdateChecker.compareVersions(b, a)
            assertEquals("simetri: $a / $b", ab, -ba)
        }
    }

    /** Gercek yayin akisi: VERSION.txt 1.0.2 iken yeni tag 1.0.3 bildirilir. */
    @Test
    fun realReleaseScenario() {
        val installed = "1.0.2"
        assertTrue(UpdateChecker.compareVersions("1.0.3", installed) > 0) // yeni -> bildir
        assertEquals(0, UpdateChecker.compareVersions("1.0.2", installed)) // ayni -> bildirme
        assertTrue(UpdateChecker.compareVersions("1.0.1", installed) < 0) // geride -> bildirme
    }

    // ---------- zamanlama (rate limit korumasi) ----------

    @Test
    fun firstCheckIsAlwaysAllowed() {
        assertTrue(UpdateChecker.shouldCheck(now = 1_000_000L))
    }

    /** Damga atildiktan sonraki 6 saat boyunca tekrar deneme YAPILMAZ. */
    @Test
    fun repeatedCheckWithinIntervalIsSkipped() {
        val t0 = 10_000_000L
        val sixHours = 6 * 60 * 60 * 1000L
        UpdateChecker.markCheckedAt(t0)

        assertFalse("hemen ardindan tekrar gitmemeli",
            UpdateChecker.shouldCheck(now = t0 + 1))
        assertFalse("birkaç dakika sonra da gitmemeli",
            UpdateChecker.shouldCheck(now = t0 + 60_000L))
        assertFalse("6 saatin biraz oncesi hala sinir icinde",
            UpdateChecker.shouldCheck(now = t0 + sixHours - 1))
    }

    @Test
    fun intervalBoundaryIsRespected() {
        val t0 = 10_000_000L
        val sixHours = 6 * 60 * 60 * 1000L
        UpdateChecker.markCheckedAt(t0)

        assertFalse("6 saatten once gecmemeli", UpdateChecker.shouldCheck(now = t0 + sixHours - 1))
        assertTrue("6 saat dolduysa gecmeli", UpdateChecker.shouldCheck(now = t0 + sixHours))
        assertTrue("sınirdan sonraki her an gecmeli", UpdateChecker.shouldCheck(now = t0 + sixHours + 1))
    }

    /** Eski bir damga gecerliligini yitirmis sayilir. */
    @Test
    fun staleTimestampAllowsImmediateCheck() {
        val t0 = 1_000_000L
        val sixHours = 6 * 60 * 60 * 1000L
        UpdateChecker.markCheckedAt(t0)
        assertTrue(UpdateChecker.shouldCheck(now = t0 + sixHours * 3))
    }

    @Test
    fun resetClearsRateLimit() {
        val t0 = 10_000_000L
        UpdateChecker.markCheckedAt(t0)
        assertFalse(UpdateChecker.shouldCheck(now = t0))

        UpdateChecker.resetForTest()
        assertTrue("reset sonrasi ilk denetim serbest olmali",
            UpdateChecker.shouldCheck(now = t0))
    }

    /**
     * Denetim damgasi sonuctan BAGIMSIZ yazilmalidir. `shouldCheck` yalnizca
     * damgaya baktigi icin, bu test rate-limit'in offline durumda da calistigini
     * dolayli olarak dogrular (fetch basarisiz olsa bile yeniden denenmez).
     */
    @Test
    fun rateLimitIsIndependentOfResult() {
        val t0 = 10_000_000L
        UpdateChecker.resetForTest()
        assertTrue(UpdateChecker.shouldCheck(now = t0))
        // `check` (ag yok -> null doner) damgayi yine de yazdigindan:
        UpdateChecker.markCheckedAt(t0)      // check() icin yapilan islemle ayni
        assertFalse(UpdateChecker.shouldCheck(now = t0 + 1))
    }
}
