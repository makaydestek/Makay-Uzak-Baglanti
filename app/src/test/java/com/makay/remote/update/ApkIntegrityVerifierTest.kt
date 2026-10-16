package com.makay.remote.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guvenilir indirme adresi ve SHA-256 parmak izi cozumleme.
 *
 * Buradaki kontroller guncelleme akisinin GUVENLIK SINIRIDIR: yanlis depoya
 * veya sahte bir adrese yonlendirilen bir APK asla indirilmez.
 */
class ApkIntegrityVerifierTest {

    @Test
    fun testTrustedGitHubReleaseUrl() {
        // Varsayilan owner "makaydestek", repo "Makay-Uzak-Baglanti"
        assertTrue(
            ApkIntegrityVerifier.isTrustedGitHubReleaseUrl(
                "https://github.com/makaydestek/Makay-Uzak-Baglanti/releases/download/v1.0.14/MakayRemote_v1.0.14.apk"
            )
        )
        assertTrue(
            ApkIntegrityVerifier.isTrustedGitHubReleaseUrl(
                "https://github.com/makaydestek/makay-uzak-baglanti/releases/download/1.0.14/MakayRemote_v1.0.14.apk"
            )
        )
        assertTrue(
            ApkIntegrityVerifier.isTrustedGitHubReleaseUrl(
                "https://objects.githubusercontent.com/some/asset"
            )
        )
    }

    @Test
    fun untrustedUrlsAreRejected() {
        // https zorunlu
        assertFalse(
            ApkIntegrityVerifier.isTrustedGitHubReleaseUrl(
                "http://github.com/makaydestek/Makay-Uzak-Baglanti/releases/download/v1.0.14/MakayRemote_v1.0.14.apk"
            )
        )
        // sahte host
        assertFalse(
            ApkIntegrityVerifier.isTrustedGitHubReleaseUrl(
                "https://evil.com/makaydestek/Makay-Uzak-Baglanti/releases/download/v1.0.14/MakayRemote_v1.0.14.apk"
            )
        )
        // baska sahiplik — baska birinin reposundan indirilemez
        assertFalse(
            ApkIntegrityVerifier.isTrustedGitHubReleaseUrl(
                "https://github.com/otherowner/Makay-Uzak-Baglanti/releases/download/v1.0.14/MakayRemote_v1.0.14.apk"
            )
        )
        // baska repo adi
        assertFalse(
            ApkIntegrityVerifier.isTrustedGitHubReleaseUrl(
                "https://github.com/makaydestek/some-other-repo/releases/download/v1.0.14/app.apk"
            )
        )
        // yukleme yolu olmayan address
        assertFalse(
            ApkIntegrityVerifier.isTrustedGitHubReleaseUrl(
                "https://github.com/makaydestek/Makay-Uzak-Baglanti/issues/1"
            )
        )
        // bos / bozuk
        assertFalse(ApkIntegrityVerifier.isTrustedGitHubReleaseUrl(""))
        assertFalse(ApkIntegrityVerifier.isTrustedGitHubReleaseUrl("not a url"))
    }

    @Test
    fun explicitOwnerRepoOverridesDefaults() {
        assertTrue(
            ApkIntegrityVerifier.isTrustedGitHubReleaseUrl(
                url = "https://github.com/someone/app/releases/download/v1/app.apk",
                owner = "Someone",
                repo = "App"
            )
        )
        assertFalse(
            ApkIntegrityVerifier.isTrustedGitHubReleaseUrl(
                url = "https://github.com/someone/app/releases/download/v1/app.apk",
                owner = "makaydestek",
                repo = "Makay-Uzak-Baglanti"
            )
        )
    }

    @Test
    fun testParseSha256Fingerprint() {
        val content = """
            abc123def456
            e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855  MakayRemote_v1.0.14.apk
            other text
        """.trimIndent()

        val sha = ApkIntegrityVerifier.parseSha256Fingerprint(content, "MakayRemote_v1.0.14.apk")
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", sha)
    }

    @Test
    fun parseSha256FindsLabeledFingerprint() {
        val content = "SHA-256: 9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"
        assertEquals(
            "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
            ApkIntegrityVerifier.parseSha256Fingerprint(content)
        )
    }

    @Test
    fun parseSha256RejectsAmbiguousOrMissing() {
        // tek hash yok, birden cok hash var -> null (guvenli taraf)
        val two = """
            aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
            bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
        """.trimIndent()
        assertEquals(null, ApkIntegrityVerifier.parseSha256Fingerprint(two))
        assertEquals(null, ApkIntegrityVerifier.parseSha256Fingerprint("burada hash yok"))
    }
}
