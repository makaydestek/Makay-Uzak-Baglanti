package com.makay.remote.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import androidx.core.content.getSystemService
import com.makay.remote.BuildConfig
import com.makay.remote.MainActivity
import com.makay.remote.R
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Makay ekosistemi ile ayni mantik (Cleaner / File Manager / Launcher ile birebir):
 * GitHub Releases -> acilis kontrolu -> bildirim + diyalog (Guncelle / Ertele)
 *   -> guvenilir URL + SHA-256 + paket adi + imza dogrulamasi -> kur.
 * Kaynak: https://github.com/makaydestek/Makay-Uzak-Baglanti/releases
 */
class AppUpdateRepository(context: Context) {

    private val appContext = context.applicationContext
    private val integrity = ApkIntegrityVerifier(appContext)
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val updateDir = File(appContext.cacheDir, "updates").also { it.mkdirs() }

    private val owner get() = BuildConfig.UPDATE_GITHUB_OWNER
    private val repo get() = BuildConfig.UPDATE_GITHUB_REPO
    private val apiLatest get() = "https://api.github.com/repos/$owner/$repo/releases/latest"
    private val appName get() = appContext.getString(R.string.app_name)
    private val userAgent get() = "MakayRemote/${BuildConfig.VERSION_NAME}"

    fun shouldCheckAutomatically(minIntervalMs: Long = DEFAULT_CHECK_INTERVAL_MS): Boolean {
        val last = prefs.getLong(KEY_LAST_CHECK_MS, 0L)
        return System.currentTimeMillis() - last >= minIntervalMs
    }

    fun markCheckedNow() {
        prefs.edit().putLong(KEY_LAST_CHECK_MS, System.currentTimeMillis()).apply()
    }

    fun checkForUpdate(force: Boolean = false): AvailableUpdate? {
        if (!force && !shouldCheckAutomatically()) return null
        val release = fetchLatestReleaseJson()
        markCheckedNow()
        if (release.optBoolean("draft") || release.optBoolean("prerelease")) return null
        val tag = release.optString("tag_name", "")
        val versionName = normalizeVersion(tag)
        if (versionName.isBlank()) return null
        if (compareVersions(versionName, BuildConfig.VERSION_NAME) <= 0) return null

        val assets = release.optJSONArray("assets") ?: return null
        var apkName = ""
        var apkUrl = ""
        var apkSize = 0L
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val name = a.optString("name", "")
            val url = a.optString("browser_download_url", "")
            if (!name.endsWith(".apk", true) || url.isBlank()) continue
            val preferred = name.startsWith("MakayRemote", true) ||
                name.startsWith("Makay-Remote", true)
            if (apkUrl.isBlank() || preferred) {
                apkName = name
                apkUrl = url
                apkSize = a.optLong("size", 0L)
                if (preferred) break
            }
        }
        if (apkUrl.isBlank()) return null

        when (val urlCheck = integrity.assertTrustedDownloadUrl(apkUrl)) {
            is ApkIntegrityVerifier.Result.Failed -> error(urlCheck.message)
            ApkIntegrityVerifier.Result.Ok -> Unit
        }

        val expectedSha = resolveExpectedSha256(release, apkName)

        return AvailableUpdate(
            tag = tag,
            versionName = versionName,
            apkUrl = apkUrl,
            apkName = apkName,
            releaseNotes = release.optString("body", "").trim().take(1200),
            htmlUrl = release.optString("html_url", "https://github.com/$owner/$repo/releases/latest"),
            expectedSha256 = expectedSha,
            expectedSizeBytes = apkSize.takeIf { it > 0 }
        )
    }

    private fun resolveExpectedSha256(release: JSONObject, apkName: String): String? {
        val assets = release.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val name = a.optString("name", "").lowercase()
            val url = a.optString("browser_download_url", "")
            if (url.isBlank()) continue
            if (!(name.endsWith(".sha256") || name == "sha256sums" || name == "checksums.txt")) {
                continue
            }
            if (!ApkIntegrityVerifier.isTrustedGitHubReleaseUrl(url)) continue
            val text = runCatching { fetchText(url) }.getOrNull() ?: continue
            ApkIntegrityVerifier.parseSha256Fingerprint(text, apkName)?.let { return it }
        }
        return ApkIntegrityVerifier.parseSha256Fingerprint(
            release.optString("body", ""),
            apkName
        )
    }

    fun downloadApk(update: AvailableUpdate, onProgress: (Int) -> Unit = {}): File {
        when (val urlCheck = integrity.assertTrustedDownloadUrl(update.apkUrl)) {
            is ApkIntegrityVerifier.Result.Failed -> error(urlCheck.message)
            ApkIntegrityVerifier.Result.Ok -> Unit
        }

        updateDir.listFiles()?.forEach { runCatching { it.delete() } }
        val safeName = update.apkName
            .substringAfterLast('/')
            .ifBlank { "MakayRemote-update.apk" }
            .replace(Regex("""[^\w.\-]+"""), "_")
        val target = File(updateDir, safeName)

        try {
            val conn = (URL(update.apkUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 10 * 60_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", "application/octet-stream,*/*")
            }
            try {
                check(conn.responseCode in 200..299) { "İndirme HTTP ${conn.responseCode}" }
                val total = conn.contentLengthLong.takeIf { it > 0 }
                    ?: update.expectedSizeBytes
                    ?: -1L
                var lastPct = -1
                val raw: InputStream = conn.inputStream
                val counted = object : FilterInputStream(raw) {
                    private var written = 0L
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        val n = super.read(b, off, len)
                        if (n > 0) {
                            written += n
                            if (total > 0) {
                                val pct = ((written * 100) / total).toInt().coerceIn(0, 100)
                                if (pct != lastPct) {
                                    lastPct = pct
                                    onProgress(pct)
                                }
                            }
                        }
                        return n
                    }
                }
                FileOutputStream(target).use { out -> counted.copyTo(out) }
            } finally {
                conn.disconnect()
            }
            onProgress(100)

            when (
                val verified = integrity.verifyDownloadedApk(
                    apkFile = target,
                    expectedSha256 = update.expectedSha256,
                    expectedSizeBytes = update.expectedSizeBytes
                )
            ) {
                is ApkIntegrityVerifier.Result.Failed -> {
                    runCatching { target.delete() }
                    error(verified.message)
                }
                ApkIntegrityVerifier.Result.Ok -> return target
            }
        } catch (e: Exception) {
            runCatching { target.delete() }
            throw e
        }
    }

    /**
     * Kuruluma izin verilmis mi? `canRequestPackageInstalls()` API 26+ icin
     * tanimlidir; minSdk 26 oldugu icin surum dallanmasi gerekmez (Onceki
     * `else true` dalina artik ulasilamazdi).
     */
    fun canRequestInstall(): Boolean = appContext.packageManager.canRequestPackageInstalls()

    /**
     * "Bilinmeyen uygulamalar" izin ekrani.
     *
     * [Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES] API 26+ ile tanimlidir;
     * minSdk 26 oldugu icin eski `ACTION_SECURITY_SETTINGS` dalina artik
     * ulasilamiyordu (olu kod).
     */
    fun unknownSourcesSettingsIntent(): Intent = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:${appContext.packageName}")
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun installIntent(file: File): Intent {
        val uri = FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.fileprovider",
            file
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun showUpdateNotification(update: AvailableUpdate) {
        val nm = appContext.getSystemService<NotificationManager>() ?: return
        ensureChannel(nm)
        val open = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_SHOW_UPDATE, true)
            putExtra(EXTRA_AUTO_DOWNLOAD, true)
            putExtra(EXTRA_UPDATE_TAG, update.tag)
        }
        val pending = PendingIntent.getActivity(
            appContext,
            NOTIFICATION_ID,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Yeni sürüm hazır")
            .setContentText("$appName ${update.versionName} — Dokunun, güncelleyin veya erteleyin")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun ensureChannel(nm: NotificationManager) {
        // createNotificationChannel API 26+ ile tanimlidir; minSdk 26
        // oldugu icin `SDK_INT < O` kontrolu gereksizdi.
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Uygulama güncellemeleri",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Yeni $appName sürüm bildirimleri"
            }
        )
    }

    private fun fetchLatestReleaseJson(): JSONObject {
        val conn = (URL(apiLatest).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", userAgent)
        }
        try {
            if (conn.responseCode == 404) error("Henüz GitHub Release yok")
            check(conn.responseCode in 200..299) { "GitHub API ${conn.responseCode}" }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            return JSONObject(body)
        } finally {
            conn.disconnect()
        }
    }

    private fun fetchText(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", userAgent)
        }
        try {
            check(conn.responseCode in 200..299) { "Hash HTTP ${conn.responseCode}" }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        const val DEFAULT_CHECK_INTERVAL_MS = 12L * 60L * 60L * 1000L
        const val CHANNEL_ID = "app_updates"
        const val NOTIFICATION_ID = 46046
        const val EXTRA_SHOW_UPDATE = "show_app_update"
        const val EXTRA_AUTO_DOWNLOAD = "auto_download_update"
        const val EXTRA_UPDATE_TAG = "app_update_tag"
        private const val PREFS = "app_update"
        private const val KEY_LAST_CHECK_MS = "last_check_ms"

        fun normalizeVersion(raw: String): String =
            raw.trim().removePrefix("v").removePrefix("V").trim()

        fun compareVersions(a: String, b: String): Int {
            val pa = normalizeVersion(a).split('.', '-', '_').mapNotNull { it.toIntOrNull() }
            val pb = normalizeVersion(b).split('.', '-', '_').mapNotNull { it.toIntOrNull() }
            val n = maxOf(pa.size, pb.size)
            for (i in 0 until n) {
                val x = pa.getOrElse(i) { 0 }
                val y = pb.getOrElse(i) { 0 }
                if (x != y) return x.compareTo(y)
            }
            return 0
        }
    }
}
