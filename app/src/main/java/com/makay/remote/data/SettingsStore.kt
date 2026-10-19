package com.makay.remote.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import com.makay.remote.BuildConfig

/**
 * MAKAY Remote — kalici ayarlar ve cihaz kimligi.
 *
 * Masaustu Ayarlar penceresiyle ortak alanlar:
 *   - Tema (system / dark / light)
 *   - Cihaz adi
 *   - Kontrol izni zorunlu mu
 *   - Ozel MQTT broker
 *   - Yalnizca TURN / acik TURN yedek
 *   - TURN sunucu / kullanici / sifre
 *
 * Android'de masaustunun "ayni ag (LAN)" eslesmesi YOKTUR; burada bulut
 * (MQTT) eslesmesi kullanilir. Bu yuzden LAN'a ait alanlar tanimlanmaz.
 *
 * REAKTIFLIK: gorunum alanlari Compose snapshot durumu (`mutableStateOf`)
 * ile tutulur; deger yazilinca hem SharedPreferences'e kalici yazilir hem
 * de state guncellenir — boylece Ayarlar ekranindaki switch/metin alanlari
 * GERI yapilmadan (ekran yeniden cizilmeden) aninda guncellenir.
 */
class SettingsStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences("makay_remote", Context.MODE_PRIVATE)

    /** Sifreler icin sifreli depo; basarisizsa plain prefs'e duser. */
    private val securePrefs: SharedPreferences = runCatching {
        val master = androidx.security.crypto.MasterKey.Builder(appContext)
            .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
            .build()
        androidx.security.crypto.EncryptedSharedPreferences.create(
            appContext,
            "makay_remote_secure",
            master,
            androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }.getOrElse { prefs }

    init {
        scrubDeadTurnServers()
        migratePasswordsToSecure()
        /* Windows her Bağlantıya Aç'ta yeni kimlik üretir — eski kimlik geçmişi gereksiz */
        prefs.edit().remove(KEY_RECENTS).putBoolean(KEY_REMEMBER_PASS, false).apply()
    }

    /** Eski plain lastPassword / recents sifrelerini secure prefs'e tasir. */
    private fun migratePasswordsToSecure() {
        if (securePrefs === prefs) return
        val plainLast = prefs.getString(KEY_LAST_PASSWORD, null)
        if (!plainLast.isNullOrEmpty() && securePrefs.getString(KEY_LAST_PASSWORD, null).isNullOrEmpty()) {
            securePrefs.edit().putString(KEY_LAST_PASSWORD, plainLast).apply()
            prefs.edit().remove(KEY_LAST_PASSWORD).apply()
        }
    }

    /**
     * Olmus / kara delik TURN adreslerini acilista temizler.
     *
     * `openrelay.metered.ca` (Open Relay) 2026'da kapandi ve artik
     * 192.0.2.1 (RFC5737 belge adresi) gibi ulasilamaz bir adrese cozuluyor.
     * Boyle bir TURN girdigi zaman libwebrtc'in ICE toplamasi/ICE kontrol
     * testleri felce ugruyor ve baglanti "Baglaniyor" ekraninda takilabiliyor
     * — ayni agda bile. Masaustu tarafinda ayni temizlik `app.js:applyTurnDefaults`
     * (`_v:4`) ile yapilir; Android'de de yapilmasi gerekir cunku eski
     * varsayilan deger bir kez kaydedilmisti.
     *
     * Kullanici kendi TURN'unu girmisse (cerceveli TURN/TURNs adresi) ve
     * olmus listede degilse DOKUNULMAZ.
     */
    private fun scrubDeadTurnServers() {
        val raw = prefs.getString(KEY_TURN_URL, "").orEmpty()
        if (raw.isBlank()) return
        val entries = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val alive = entries.filter { u ->
            DEAD_TURN_MARKERS.none { m -> u.contains(m, ignoreCase = true) }
        }
        if (alive.size != entries.size) {
            prefs.edit().putString(KEY_TURN_URL, alive.joinToString(",")).apply()
        }
    }

    private fun stringState(key: String, default: String): MutableState<String> =
        mutableStateOf(prefs.getString(key, default) ?: default)

    private fun boolState(key: String, default: Boolean): MutableState<Boolean> =
        mutableStateOf(prefs.getBoolean(key, default))

    // ---------- gorunum ----------
    /** "system" | "dark" | "light" — varsayilan koyu (masaustu Ayarlar ile ayni). */
    private val themeState = stringState(KEY_THEME, "dark")
    var themeMode: String
        get() = themeState.value
        set(v) {
            themeState.value = v
            prefs.edit().putString(KEY_THEME, v).apply()
        }

    // ---------- cihaz ----------
    /** Bu cihazin goruntulenecek adi. */
    private val deviceNameState = stringState(KEY_DEVICE_NAME, defaultDeviceName())
    var deviceName: String
        get() = deviceNameState.value
        set(v) {
            deviceNameState.value = v
            prefs.edit().putString(KEY_DEVICE_NAME, v).apply()
        }

    /**
     * Kalici cihaz UUID'si — kurulumda bir kez uretilir, join-request ile
     * karsi tarafa gider. Host bu id'yi "guvenli cihaz" listesine kaydederse
     * sonraki baglantilarda İzin Ver gerekmez.
     */
    val deviceId: String
        get() {
            val existing = prefs.getString(KEY_DEVICE_ID, null)
            if (!existing.isNullOrBlank()) return existing
            val id = java.util.UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_ID, id).apply()
            return id
        }

    data class TrustedDevice(
        val id: String,
        val name: String,
        /** epoch ms; gecmise dustuyse guven dusurulur. 0 = suresiz (eski kayit). */
        val expiresAt: Long = 0L
    )

    /** Guven suresi: 30 gun (AnyDesk tarzi "bir sure hatirla"). */
    private val TRUST_TTL_MS = 30L * 24 * 60 * 60 * 1000

    fun trustedDevices(): List<TrustedDevice> {
        val now = System.currentTimeMillis()
        val raw = prefs.getString(KEY_TRUSTED, "") ?: ""
        if (raw.isBlank()) return emptyList()
        val parsed = raw.split('\n').mapNotNull { line ->
            val p = line.split('|')
            when {
                p.size >= 3 && p[0].isNotBlank() ->
                    TrustedDevice(p[0], p[1], p[2].toLongOrNull() ?: 0L)
                p.size == 2 && p[0].isNotBlank() ->
                    TrustedDevice(p[0], p[1], 0L)
                else -> null
            }
        }
        val alive = parsed.filter { it.expiresAt == 0L || it.expiresAt > now }
        if (alive.size != parsed.size) saveTrusted(alive)
        return alive
    }

    fun isTrusted(deviceId: String?): Boolean {
        val id = deviceId?.trim().orEmpty()
        if (id.isEmpty()) return false
        return trustedDevices().any { it.id == id }
    }

    fun trustDevice(deviceId: String, name: String) {
        val id = deviceId.trim()
        if (id.isEmpty()) return
        val list = trustedDevices().filter { it.id != id }.toMutableList()
        list.add(
            0,
            TrustedDevice(
                id,
                name.trim().ifEmpty { "Cihaz" }.take(40),
                System.currentTimeMillis() + TRUST_TTL_MS
            )
        )
        saveTrusted(list.take(40))
    }

    fun untrustDevice(deviceId: String) {
        saveTrusted(trustedDevices().filter { it.id != deviceId.trim() })
    }

    fun clearTrustedDevices() {
        prefs.edit().remove(KEY_TRUSTED).apply()
    }

    private fun saveTrusted(list: List<TrustedDevice>) {
        val raw = list.joinToString("\n") { "${it.id}|${it.name}|${it.expiresAt}" }
        prefs.edit().putString(KEY_TRUSTED, raw).apply()
    }

    // ---------- son baglantilar / sifre hatirla ----------
    data class RecentRoom(val code: String, val password: String, val label: String, val ts: Long)

    private val rememberPassState = boolState(KEY_REMEMBER_PASS, false)
    var rememberPassword: Boolean
        get() = rememberPassState.value
        set(v) {
            rememberPassState.value = v
            prefs.edit().putBoolean(KEY_REMEMBER_PASS, v).apply()
            if (!v) {
                recentRooms().forEach { securePrefs.edit().remove(securePwdKey(it.code)).apply() }
                lastPassword = ""
            }
        }

    /** auto | high | medium | low — ekran paylasimi kalitesi. */
    private val videoQualityState = stringState(KEY_VIDEO_QUALITY, "auto")
    var videoQuality: String
        get() = videoQualityState.value
        set(v) {
            val n = when (v) {
                "high", "medium", "low", "auto" -> v
                else -> "auto"
            }
            videoQualityState.value = n
            prefs.edit().putString(KEY_VIDEO_QUALITY, n).apply()
        }

    fun recentRooms(): List<RecentRoom> {
        val raw = prefs.getString(KEY_RECENTS, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split('\n').mapNotNull { line ->
            val p = line.split('|')
            if (p.size >= 4 && p[0].length == 8) {
                val code = p[0]
                /* Sifre secure prefs'te; eski kayitlarda p[1] fallback */
                val pwd = if (rememberPassword) {
                    securePrefs.getString(securePwdKey(code), null) ?: p[1]
                } else ""
                RecentRoom(code, pwd, p[2], p[3].toLongOrNull() ?: 0L)
            } else null
        }.sortedByDescending { it.ts }.take(12)
    }

    fun rememberRoom(code: String, password: String, label: String = "") {
        val c = code.filter { it.isLetterOrDigit() }.uppercase().take(8)
        if (c.length != 8) return
        val list = recentRooms().filter { it.code != c }.toMutableList()
        list.add(0, RecentRoom(c, "", label.take(40), System.currentTimeMillis()))
        saveRecents(list.take(12))
        if (rememberPassword && password.isNotEmpty()) {
            securePrefs.edit().putString(securePwdKey(c), password).apply()
            lastPassword = password
        } else {
            securePrefs.edit().remove(securePwdKey(c)).apply()
            lastPassword = ""
        }
        lastRoom = c
    }

    private fun saveRecents(list: List<RecentRoom>) {
        /* Metadata plain; sifreler securePrefs'te */
        val raw = list.joinToString("\n") { "${it.code}||${it.label}|${it.ts}" }
        prefs.edit().putString(KEY_RECENTS, raw).apply()
    }

    fun clearRecents() {
        recentRooms().forEach { securePrefs.edit().remove(securePwdKey(it.code)).apply() }
        prefs.edit().remove(KEY_RECENTS).apply()
    }

    private fun securePwdKey(code: String) = "rp_$code"

    // ---------- baglanti ----------
    // NOT: Masaustundaki "connMode / lanSignal / signalUrl / internetSignalUrl"
    // alanlari Android'de BILINCLI olarak yoktur: Android her zaman bulut
    // (MQTT) eslesmesi kullanir, ayni ag yerel HTTP sinyal sunucusu barindirmez.
    // Bu yuzden o alanlar burada tutulmaz (oluk kod).

    // ---------- TURN / MQTT ----------
    private val preferRelayState = boolState(KEY_PREFER_RELAY, false)
    var preferRelay: Boolean
        get() = preferRelayState.value
        set(v) {
            preferRelayState.value = v
            prefs.edit().putBoolean(KEY_PREFER_RELAY, v).apply()
        }

    private val publicTurnState = boolState(KEY_PUBLIC_TURN, false)
    var publicTurnFallback: Boolean
        get() = publicTurnState.value
        set(v) {
            publicTurnState.value = v
            prefs.edit().putBoolean(KEY_PUBLIC_TURN, v).apply()
        }

    private val mqttBrokerState = stringState(KEY_MQTT_BROKER, "")
    var mqttBroker: String
        get() = mqttBrokerState.value
        set(v) {
            mqttBrokerState.value = v
            prefs.edit().putString(KEY_MQTT_BROKER, v.trim()).apply()
        }

    private val turnUrlState = stringState(KEY_TURN_URL, "")
    var turnUrl: String
        get() = turnUrlState.value
        set(v) {
            turnUrlState.value = v
            prefs.edit().putString(KEY_TURN_URL, v.trim()).apply()
        }

    private val turnUserState = stringState(KEY_TURN_USER, "")
    var turnUser: String
        get() = turnUserState.value
        set(v) {
            turnUserState.value = v
            prefs.edit().putString(KEY_TURN_USER, v.trim()).apply()
        }

    private val turnPassState = stringState(KEY_TURN_PASS, "")
    var turnPass: String
        get() = turnPassState.value
        set(v) {
            turnPassState.value = v
            prefs.edit().putString(KEY_TURN_PASS, v).apply()
        }

    // ---------- oturum hafizasi ----------
    // Ekran disinda kaldigi icin plain prefs yeterli (state gerekmez).
    var lastRoom: String
        get() = prefs.getString(KEY_LAST_ROOM, "") ?: ""
        set(v) = prefs.edit().putString(KEY_LAST_ROOM, v).apply()

    var lastPassword: String
        get() = securePrefs.getString(KEY_LAST_PASSWORD, null)
            ?: prefs.getString(KEY_LAST_PASSWORD, "") ?: ""
        set(v) {
            securePrefs.edit().putString(KEY_LAST_PASSWORD, v).apply()
            /* Plain kopyayi sil — sifre yalnizca secure'da kalsin */
            if (prefs.contains(KEY_LAST_PASSWORD)) {
                prefs.edit().remove(KEY_LAST_PASSWORD).apply()
            }
        }

    fun clearSession() {
        prefs.edit().remove(KEY_LAST_ROOM).remove(KEY_LAST_PASSWORD).apply()
        securePrefs.edit().remove(KEY_LAST_PASSWORD).apply()
    }

    private fun defaultDeviceName(): String = "${android.os.Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${android.os.Build.MODEL}"
        .take(40)

    companion object {
        private const val KEY_THEME = "theme"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_TRUSTED = "trusted_devices"
        private const val KEY_RECENTS = "recent_rooms"
        private const val KEY_REMEMBER_PASS = "remember_password"
        private const val KEY_VIDEO_QUALITY = "video_quality"
        private const val KEY_PREFER_RELAY = "prefer_relay"
        private const val KEY_PUBLIC_TURN = "public_turn"
        private const val KEY_MQTT_BROKER = "mqtt_broker"
        private const val KEY_TURN_URL = "turn_url"
        private const val KEY_TURN_USER = "turn_user"
        private const val KEY_TURN_PASS = "turn_pass"
        private const val KEY_LAST_ROOM = "last_room"
        private const val KEY_LAST_PASSWORD = "last_password"

        /**
         * Olmus / ulasilamaz oldugu bilinen TURN adresi isaretleri.
         * Bunlarin bulundugu `turn_url` kayitlari acilista temizlenir.
         * (openrelay.metered.ca 2026'da kapandi -> 192.0.2.1 kara deligi)
         */
        private val DEAD_TURN_MARKERS = listOf(
            "openrelay.metered.ca",
            "metered.ca",
            "freeturn.net",
            "192.0.2.1"
        )

        fun versionName(): String = BuildConfig.VERSION_NAME
    }
}
