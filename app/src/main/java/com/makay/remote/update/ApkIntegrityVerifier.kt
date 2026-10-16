package com.makay.remote.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RequiresApi
import com.makay.remote.BuildConfig
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.jar.JarFile

/** Guncelleme APK butunlugu: guvenilir URL, SHA-256, paket adi, imza. */
class ApkIntegrityVerifier(context: Context) {

    private val appContext = context.applicationContext

    sealed class Result {
        data object Ok : Result()
        data class Failed(val message: String) : Result()
    }

    fun assertTrustedDownloadUrl(url: String): Result {
        if (!isTrustedGitHubReleaseUrl(url)) {
            return Result.Failed(
                "Güncelleme adresi güvenilir değil. Yalnızca resmi GitHub Releases kabul edilir."
            )
        }
        return Result.Ok
    }

    fun verifyDownloadedApk(
        apkFile: File,
        expectedSha256: String?,
        expectedSizeBytes: Long? = null
    ): Result {
        if (!apkFile.isFile || apkFile.length() < MIN_APK_BYTES) {
            return Result.Failed("İndirilen APK eksik veya bozuk.")
        }
        if (expectedSizeBytes != null && expectedSizeBytes > 0 &&
            apkFile.length() != expectedSizeBytes
        ) {
            return Result.Failed(
                "APK boyutu eşleşmedi (beklenen $expectedSizeBytes, gelen ${apkFile.length()})."
            )
        }

        val expected = expectedSha256?.trim()?.lowercase()
            ?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
            ?: return Result.Failed("Yayın SHA-256 özeti bulunamadı veya geçersiz; kurulum iptal edildi.")
        val actual = sha256Hex(apkFile)
        if (!actual.equals(expected, ignoreCase = true)) {
            return Result.Failed(
                "SHA-256 doğrulaması başarısız. Dosya değiştirilmiş olabilir; kurulum iptal edildi."
            )
        }

        val archiveInfo = readArchivePackageInfo(apkFile)
            ?: return Result.Failed("APK okunamadı (paket bilgisi yok).")

        val apkPackage = archiveInfo.packageName.orEmpty()
        if (apkPackage.isBlank() || apkPackage != appContext.packageName) {
            return Result.Failed(
                "Paket adı uyuşmuyor ($apkPackage). Resmi uygulama değil; kurulum iptal edildi."
            )
        }

        val installedDigests = installedSigningDigests()
        if (installedDigests.isEmpty()) {
            return Result.Failed("Yüklü uygulamanın imzası okunamadı.")
        }
        var apkDigests = archiveSigningDigests(archiveInfo)
        if (apkDigests.isEmpty()) {
            apkDigests = fallbackJarCertDigests(apkFile)
        }
        if (apkDigests.isEmpty()) {
            return Result.Failed("İndirilen APK’nın imzası okunamadı.")
        }
        if (installedDigests.intersect(apkDigests).isEmpty()) {
            return Result.Failed(
                "İmza doğrulaması başarısız. Bu APK mevcut kurulumla aynı anahtarla imzalanmamış."
            )
        }
        return Result.Ok
    }

    private fun fallbackJarCertDigests(apkFile: File): Set<String> {
        return runCatching {
            JarFile(apkFile, true).use { jar ->
                val buffer = ByteArray(8 * 1024)
                val entries = jar.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory) continue
                    jar.getInputStream(entry).use { input ->
                        while (input.read(buffer) != -1) { }
                    }
                    val certs = entry.certificates ?: continue
                    if (certs.isEmpty()) continue
                    return@use certs.mapNotNull { cert ->
                        val encoded = (cert as? X509Certificate)?.encoded ?: cert.encoded
                        runCatching { sha256Hex(encoded) }.getOrNull()
                    }.toSet()
                }
                emptySet()
            }
        }.getOrDefault(emptySet())
    }

    private fun installedSigningDigests(): Set<String> {
        return try {
            val pm = appContext.packageManager
            val pkg = appContext.packageName
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
                digestsFromSigningInfo(info.signingInfo)
            } else {
                @Suppress("DEPRECATION")
                val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES)
                @Suppress("DEPRECATION")
                digestsFromLegacySignatures(info.signatures)
            }
        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun readArchivePackageInfo(apkFile: File): android.content.pm.PackageInfo? {
        val pm = appContext.packageManager
        val path = apkFile.absolutePath
        val info = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                pm.getPackageArchiveInfo(
                    path,
                    PackageManager.PackageInfoFlags.of(
                        PackageManager.GET_SIGNING_CERTIFICATES.toLong()
                    )
                )
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> {
                @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNING_CERTIFICATES)
            }
            else -> {
                @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNATURES)
            }
        } ?: return null
        info.applicationInfo?.apply {
            sourceDir = path
            publicSourceDir = path
        }
        return info
    }

    private fun archiveSigningDigests(info: android.content.pm.PackageInfo): Set<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            digestsFromSigningInfo(info.signingInfo)
        } else {
            @Suppress("DEPRECATION")
            digestsFromLegacySignatures(info.signatures)
        }
    }

    /**
     * [android.content.pm.SigningInfo] yalnizca API 28+ (Android 9) ile
     * tanimlandi. Bu yardimci yalnizca `SDK_INT >= P` korumasi altindaki
     * cagiricilardan kullanilir; anotasyon hem lint'i (NewApi) hem de
     * okuyucuyu korumanin zorunlu oldugu konusunda uyarir.
     */
    @RequiresApi(Build.VERSION_CODES.P)
    private fun digestsFromSigningInfo(
        signingInfo: android.content.pm.SigningInfo?
    ): Set<String> {
        if (signingInfo == null) return emptySet()
        val certs = if (signingInfo.hasMultipleSigners()) {
            signingInfo.apkContentsSigners
        } else {
            signingInfo.signingCertificateHistory
        }
        return certs?.mapNotNull { sig ->
            runCatching { sha256Hex(sig.toByteArray()) }.getOrNull()
        }?.toSet().orEmpty()
    }

    @Suppress("DEPRECATION")
    private fun digestsFromLegacySignatures(
        signatures: Array<android.content.pm.Signature>?
    ): Set<String> =
        signatures?.mapNotNull { sig ->
            runCatching { sha256Hex(sig.toByteArray()) }.getOrNull()
        }?.toSet().orEmpty()

    companion object {
        private const val MIN_APK_BYTES = 1_000L

        fun sha256Hex(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n <= 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().joinToString("") { b -> "%02x".format(b) }
        }

        fun sha256Hex(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(bytes)
            return digest.digest().joinToString("") { b -> "%02x".format(b) }
        }

        fun isTrustedGitHubReleaseUrl(
            url: String,
            owner: String = BuildConfig.UPDATE_GITHUB_OWNER,
            repo: String = BuildConfig.UPDATE_GITHUB_REPO
        ): Boolean {
            val trimmed = url.trim()
            if (!trimmed.startsWith("https://", ignoreCase = true)) return false
            val parsed = runCatching { java.net.URI(trimmed) }.getOrNull() ?: return false
            val host = parsed.host?.lowercase(java.util.Locale.US) ?: return false
            val path = parsed.path.orEmpty()
            val o = owner.lowercase(java.util.Locale.US)
            val r = repo.lowercase(java.util.Locale.US)
            return when (host) {
                "github.com" -> {
                    val expected = "/$o/$r/releases/download/"
                    path.lowercase(java.util.Locale.US).startsWith(expected)
                }
                "objects.githubusercontent.com",
                "release-assets.githubusercontent.com",
                "github-releases.githubusercontent.com" -> true
                else -> false
            }
        }

        fun parseSha256Fingerprint(text: String, apkName: String = ""): String? {
            val hex = Regex("""\b([a-fA-F0-9]{64})\b""")
            if (apkName.isNotBlank()) {
                text.lineSequence().forEach { line ->
                    if (line.contains(apkName, ignoreCase = true)) {
                        hex.find(line)?.groupValues?.get(1)?.lowercase()?.let { return it }
                    }
                }
            }
            val labeled = Regex(
                """(?i)(?:sha-?256|checksum)\s*[:=]?\s*([a-fA-F0-9]{64})"""
            ).find(text)
            if (labeled != null) return labeled.groupValues[1].lowercase()
            val all = hex.findAll(text).map { it.groupValues[1].lowercase() }.distinct().toList()
            return all.singleOrNull()
        }
    }
}
