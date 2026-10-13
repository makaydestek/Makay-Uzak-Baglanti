package com.makay.remote.core

import com.google.gson.annotations.SerializedName
import com.makay.remote.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Uygulama ici guncelleme denetimi.
 *
 * `BuildConfig.UPDATE_GITHUB_OWNER` / `UPDATE_GITHUB_REPO` deposunun en son
 * release'ini okur; etiket (tag) surumden yeniysa bilgi dondurur. UI'da
 * bilgilendirme dialog'u olarak gosterilir — APK'yi indirme/yukleme islemi
 * YAPILMAZ, yalnizca yonlendirme gosterilir.
 *
 * Tasarim notlari:
 *  - Hata/offline/asiri istek durumunda sessizce `null` doner; uygulama
 *    akisi ASLA bu yuzden durmaz.
 *  - GitHub API kimliksiz isteklerde saatte 60 istekle sinirlidir; bu yuzden
 *    en fazla [MIN_INTERVAL_MS] aralikla bir kez denetlenir.
 *  - GitHub API `User-Agent` basligi olmadan yanit vermez.
 */
object UpdateChecker {

    const val TAG = "MakayUpdate"

    private const val MIN_INTERVAL_MS = 6 * 60 * 60 * 1000L // 6 saat
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 8_000

    @Volatile
    private var lastCheckAt = 0L

    /** Son denetim damgasini sifirlar — yalnizca testlerde. */
    fun resetForTest() { lastCheckAt = 0L }

    /**
     * Testlerin zaman sinirini dogrulayabilmesi icin son denetim damgasini
     * ayarlar. Uygulama kodunda KULLANILMAZ.
     */
    fun markCheckedAt(t: Long) { lastCheckAt = t }

    /**
     * Son denetimden bu yana yeterli sure gecti mi?
     *
     * Damga HER denetim denemesinde atilir (sonuclindan bagimsiz). Boylece
     * cevrimdisi bir kullanici da her ekran girisinde 8 sn'lik timeout'a
     * takilmaz; en fazla [MIN_INTERVAL_MS] aralikla bir kez denenir.
     *
     * `lastCheckAt == 0` BILINCLI olarak "hiç denetlenmedi" anlamina gelir;
     * bunu epoch damgasi olarak okumak ilk acilista denetimi atlamaya yol
     * acardı. Bu yuzden ayrica test edilir.
     */
    fun shouldCheck(now: Long = System.currentTimeMillis()): Boolean {
        val last = lastCheckAt
        if (last == 0L) return true
        return now - last >= MIN_INTERVAL_MS
    }

    data class UpdateInfo(
        val version: String,
        val url: String?,
        val notes: String?
    )

    // GitHub `releases/latest` yanitindan yalnizca ihtiyac olan alanlar.
    // @SerializedName zorunlu: R8 alanlari yeniden adlandirir; anahtarlar
    // boylece JSON'da dogru kalir. (bkz. app/proguard-rules.pro)
    private data class Release(
        @SerializedName("tag_name") val tagName: String? = null,
        @SerializedName("html_url") val htmlUrl: String? = null,
        @SerializedName("body") val body: String? = null
    )

    /**
     * Sadece [current]`'ten yeni bir surum varsa dondurur.
     *
     * @return guncelleme varsa bilgi, yoksa/hata olursa `null`
     */
    suspend fun check(
        current: String = BuildConfig.VERSION_NAME,
        owner: String = BuildConfig.UPDATE_GITHUB_OWNER,
        repo: String = BuildConfig.UPDATE_GITHUB_REPO,
        now: Long = System.currentTimeMillis(),
        force: Boolean = false
    ): UpdateInfo? {
        if (!force && !shouldCheck(now)) return null

        // Damga DENEMENIN kendisine yazilir (sonuc ne olursa olsun): boylece
        // cevrimdisi kullanici her Idle girisinde 8 sn'lik timeout'a takilmaz
        // ve GitHub API'nin saatte 60 isteklik kimliksiz limiti asilmaz.
        lastCheckAt = now
        return withContext(Dispatchers.IO) { fetch(owner, repo, current) }
    }

    private fun fetch(owner: String, repo: String, current: String): UpdateInfo? {
        if (owner.isBlank() || repo.isBlank()) return null
        return try {
            val url = URL("https://api.github.com/repos/$owner/$repo/releases/latest")
            val conn = url.openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = CONNECT_TIMEOUT_MS
                conn.readTimeout = READ_TIMEOUT_MS
                conn.requestMethod = "GET"
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                // GitHub API'siz User-Agent basligina 403 dondurur.
                conn.setRequestProperty("User-Agent", "MakayRemote/${BuildConfig.VERSION_NAME}")

                val code = conn.responseCode
                // 404 = release yok; 403/429 = asiri istek. Hicbiri hata degil.
                if (code !in 200..299) return null

                val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val rel = WireJson.gson.fromJson(body, Release::class.java) ?: return null
                val tag = rel.tagName?.trim()?.trimStart('v', 'V').orEmpty()
                if (tag.isEmpty()) return null

                // Yalnizca GERCEKTEN yeni surumde bildir; ayni/geride kalan
                // surum icin kullaniciyi uyarmak yanlistir.
                if (compareVersions(tag, current) <= 0) return null

                UpdateInfo(
                    version = tag,
                    url = rel.htmlUrl?.trim()?.takeIf { it.isNotEmpty() },
                    notes = rel.body?.trim()?.takeIf { it.isNotEmpty() }
                )
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            // Cevrimdisi / ag hatasi / JSON hatasi -> sessizce yok say.
            null
        }
    }

    /**
     * `major.minor.patch` benzeri iki surumu karsilastirir.
     *
     * Sayisal olmayan parcalar 0 sayilir; birkac parca eksiysen eksik olan
     * kisim 0 kabul edilir (`1.2` == `1.2.0`).
     *
     * @return a > b ise +1, a == b ise 0, a < b ise -1
     */
    fun compareVersions(a: String, b: String): Int {
        val pa = splitParts(a)
        val pb = splitParts(b)
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val x = pa.getOrElse(i) { 0L }
            val y = pb.getOrElse(i) { 0L }
            if (x != y) return if (x > y) 1 else -1
        }
        return 0
    }

    private fun splitParts(v: String): List<Long> =
        v.trim()
            .trimStart('v', 'V')
            .split('.', '-', '+')
            .map { it.takeWhile { c -> c.isDigit() }.toLongOrNull() ?: 0L }
}
