package com.makay.remote.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AppUpdateRepository.compareVersions — surum karsilastirma.
 *
 * Guncelleme akisindaki en kritik saf mantik budur: GitHub tag'i ile kurulu
 * surum karsilastirilir ve "yeni surum var mi" karari buradan cikar.
 * `1.0.10 > 1.0.9` gibi durumlarda metinsel karsilastirma yanlis verirdi;
 * ayni seyi `1.1.0 > 1.0.99` (Makay surum dizisi) icin de dogrular.
 */
class AppUpdateRepositoryTest {

    // ---------- compareVersions ----------

    @Test
    fun equalVersionsAreZero() {
        assertEquals(0, AppUpdateRepository.compareVersions("1.0.2", "1.0.2"))
        assertEquals(0, AppUpdateRepository.compareVersions("0.0.0", "0.0.0"))
        assertEquals(0, AppUpdateRepository.compareVersions("10.20.30", "10.20.30"))
    }

    @Test
    fun newerPatchIsGreater() {
        assertTrue(AppUpdateRepository.compareVersions("1.0.3", "1.0.2") > 0)
        assertTrue(AppUpdateRepository.compareVersions("1.0.10", "1.0.9") > 0)
    }

    @Test
    fun olderPatchIsSmaller() {
        assertTrue(AppUpdateRepository.compareVersions("1.0.1", "1.0.2") < 0)
        // 2 < 10 oldugunu dogrular: metinsel karsilastirma burada yanlis verirdi
        assertTrue(AppUpdateRepository.compareVersions("1.0.2", "1.0.10") < 0)
    }

    @Test
    fun minorAndMajorOutrankLowerComponents() {
        assertTrue(AppUpdateRepository.compareVersions("1.1.0", "1.0.99") > 0)
        assertTrue(AppUpdateRepository.compareVersions("2.0.0", "1.99.99") > 0)
        assertTrue(AppUpdateRepository.compareVersions("1.0.0", "1.0.1") < 0)
    }

    /** `v` oneki (GitHub tag bicimi) yok sayilir. */
    @Test
    fun leadingVPrefixIsIgnored() {
        assertEquals(0, AppUpdateRepository.compareVersions("v1.0.2", "1.0.2"))
        assertEquals(0, AppUpdateRepository.compareVersions("V1.0.2", "v1.0.2"))
        assertTrue(AppUpdateRepository.compareVersions("v1.0.3", "1.0.2") > 0)
    }

    /** Eksik parca 0 kabul edilir: 1.2 == 1.2.0 */
    @Test
    fun missingComponentsTreatedAsZero() {
        assertEquals(0, AppUpdateRepository.compareVersions("1.2", "1.2.0"))
        assertEquals(0, AppUpdateRepository.compareVersions("1", "1.0.0"))
        assertTrue(AppUpdateRepository.compareVersions("1.2.1", "1.2") > 0)
    }

    /** Sayisal olmayan parca yok sayilir — asla exception firlatmaz. */
    @Test
    fun nonNumericPartsNeverThrow() {
        assertEquals(0, AppUpdateRepository.compareVersions("abc", "0.0.0"))
        assertEquals(0, AppUpdateRepository.compareVersions("", "0"))
        assertTrue(AppUpdateRepository.compareVersions("2.0.0-beta", "1.9.9") > 0)
        assertTrue(AppUpdateRepository.compareVersions("1.0.2.1", "1.0.2") > 0)
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
            val ab = AppUpdateRepository.compareVersions(a, b)
            val ba = AppUpdateRepository.compareVersions(b, a)
            assertEquals("simetri: $a / $b", ab, -ba)
        }
    }

    /** Gercek yayin akisi: kurulu surum 1.0.2 iken yeni tag 1.0.3 bildirilir. */
    @Test
    fun realReleaseScenario() {
        val installed = "1.0.2"
        assertTrue(AppUpdateRepository.compareVersions("1.0.3", installed) > 0) // yeni -> bildir
        assertEquals(0, AppUpdateRepository.compareVersions("1.0.2", installed)) // ayni -> bildirme
        assertTrue(AppUpdateRepository.compareVersions("1.0.1", installed) < 0) // geride -> bildirme
    }

    /** Eger kurulu surumla ayni ya da ondaysa GUNCELLEME YOK sayilir. */
    @Test
    fun sameOrNewerInstalledMeansNoUpdate() {
        val releaseTag = "1.0.14"
        // ayni surum: guncelleme cagirilmaz
        assertTrue(AppUpdateRepository.compareVersions(releaseTag, "1.0.14") <= 0)
        // kurulu surum daha yeniyse (yanlis etiket/geri dusus) yine bildirilmez
        assertTrue(AppUpdateRepository.compareVersions("1.0.9", "1.0.14") <= 0)
        // kurulu surum gerideyse bildirilir
        assertTrue(AppUpdateRepository.compareVersions("1.0.15", "1.0.14") > 0)
    }

    // ---------- normalizeVersion ----------

    @Test
    fun normalizeVersionStripsVPrefixAndWhitespace() {
        assertEquals("1.0.14", AppUpdateRepository.normalizeVersion("v1.0.14"))
        assertEquals("1.0.14", AppUpdateRepository.normalizeVersion(" V1.0.14 "))
        assertEquals("1.0.14", AppUpdateRepository.normalizeVersion("1.0.14"))
    }
}
