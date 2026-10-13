package com.makay.remote.rtc

/**
 * Aktif PeerBridge referansi (tek oturum).
 *
 * `ScreenShareService` gibi arka plan bileşenleri baglanti sirasinda
 * PeerBridge'e erismek icin bunu okur. SessionController baglanti
 * kuruldugunda yazar, sifirladiginda null'lar.
 */
object PeerRegistry {
    @Volatile
    var current: PeerBridge? = null
}
