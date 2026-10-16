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

    private val prefs: SharedPreferences =
        context.getSharedPreferences("makay_remote", Context.MODE_PRIVATE)

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
        private const val KEY_LAST_ROOM = "last_room"
        private const val KEY_LAST_PASSWORD = "last_password"

        fun versionName(): String = BuildConfig.VERSION_NAME
    }
}
