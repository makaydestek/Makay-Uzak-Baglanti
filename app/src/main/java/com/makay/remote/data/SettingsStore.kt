package com.makay.remote.data

import android.content.Context
import android.content.SharedPreferences
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
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("makay_remote", Context.MODE_PRIVATE)

    // ---------- gorunum ----------
    /** "system" | "dark" | "light" — varsayilan koyu (masaustu Ayarlar ile ayni). */
    var themeMode: String
        get() = prefs.getString(KEY_THEME, "dark") ?: "dark"
        set(v) = prefs.edit().putString(KEY_THEME, v).apply()

    // ---------- cihaz ----------
    /** Bu cihazin goruntulenecek adi. */
    var deviceName: String
        get() = prefs.getString(KEY_DEVICE_NAME, defaultDeviceName()) ?: defaultDeviceName()
        set(v) = prefs.edit().putString(KEY_DEVICE_NAME, v).apply()

    // ---------- baglanti ----------
    // NOT: Masaustundaki "connMode / lanSignal / signalUrl / internetSignalUrl"
    // alanlari Android'de BILINCLI olarak yoktur: Android her zaman bulut
    // (MQTT) eslesmesi kullanir, ayni ag yerel HTTP sinyal sunucusu barindirmez.
    // Bu yuzden o alanlar burada tutulmaz (oluk kod).

    // ---------- TURN / MQTT ----------
    var preferRelay: Boolean
        get() = prefs.getBoolean(KEY_PREFER_RELAY, false)
        set(v) = prefs.edit().putBoolean(KEY_PREFER_RELAY, v).apply()

    var publicTurnFallback: Boolean
        get() = prefs.getBoolean(KEY_PUBLIC_TURN, false)
        set(v) = prefs.edit().putBoolean(KEY_PUBLIC_TURN, v).apply()

    var mqttBroker: String
        get() = prefs.getString(KEY_MQTT_BROKER, "") ?: ""
        set(v) = prefs.edit().putString(KEY_MQTT_BROKER, v.trim()).apply()

    var turnUrl: String
        get() = prefs.getString(KEY_TURN_URL, "") ?: ""
        set(v) = prefs.edit().putString(KEY_TURN_URL, v.trim()).apply()

    var turnUser: String
        get() = prefs.getString(KEY_TURN_USER, "") ?: ""
        set(v) = prefs.edit().putString(KEY_TURN_USER, v.trim()).apply()

    var turnPass: String
        get() = prefs.getString(KEY_TURN_PASS, "") ?: ""
        set(v) = prefs.edit().putString(KEY_TURN_PASS, v).apply()

    // ---------- guvenlik ----------
    /** Kontrol izni zorunlu (masaustu `requireControlApproval`). */
    var requireControlApproval: Boolean
        get() = prefs.getBoolean(KEY_REQUIRE_APPROVAL, true)
        set(v) = prefs.edit().putBoolean(KEY_REQUIRE_APPROVAL, v).apply()

    // ---------- oturum hafizasi ----------
    var lastRoom: String
        get() = prefs.getString(KEY_LAST_ROOM, "") ?: ""
        set(v) = prefs.edit().putString(KEY_LAST_ROOM, v).apply()

    var lastPassword: String
        get() = prefs.getString(KEY_LAST_PASSWORD, "") ?: ""
        set(v) = prefs.edit().putString(KEY_LAST_PASSWORD, v).apply()

    fun clearSession() {
        prefs.edit().remove(KEY_LAST_ROOM).remove(KEY_LAST_PASSWORD).apply()
    }

    private fun defaultDeviceName(): String = "${android.os.Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${android.os.Build.MODEL}"
        .take(40)

    companion object {
        private const val KEY_THEME = "theme"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_PREFER_RELAY = "prefer_relay"
        private const val KEY_PUBLIC_TURN = "public_turn"
        private const val KEY_MQTT_BROKER = "mqtt_broker"
        private const val KEY_TURN_URL = "turn_url"
        private const val KEY_TURN_USER = "turn_user"
        private const val KEY_TURN_PASS = "turn_pass"
        private const val KEY_REQUIRE_APPROVAL = "require_control_approval"
        private const val KEY_LAST_ROOM = "last_room"
        private const val KEY_LAST_PASSWORD = "last_password"

        fun versionName(): String = BuildConfig.VERSION_NAME
    }
}