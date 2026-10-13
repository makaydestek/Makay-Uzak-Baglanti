package com.makay.remote.core

import com.google.gson.Gson
import com.google.gson.GsonBuilder

/**
 * MAKAY Remote — TEL (wire) JSON yapilandirmasi.
 *
 * Tek noktadan tanimlanir; tum gonderim yollari (SDP kodu, DataChannel
 * zarflari, MQTT sinyalleri) bunu kullanir. Boylece cikti masaustu
 * `engine.js` `JSON.stringify` ile birebir ayni olur.
 *
 * KRITIK: Gson'un varsayilani HTML-safe kacisidir; `=`, `<`, `>`, `&`,
 * `'` ve backtick karakterlerini `\uXXXX` bicimine cevirir. JS
 * `JSON.stringify` bu karakterleri ASLA kacirmaz. Fark SDP kodunda taban64
 * govdesini ve checksum'u masaustunden farkli hale getirir ("Kod bozulmus"
 * hatasi) ve chat/dosya adi gibi alanlarda gorunur bozulma yapar.
 *
 * Boylece Gson kacirir: `"` `\` ve < 0x20 araligindaki kontrol karakterleri
 * (kisa formlar `\b \t \n \f \r`, digerleri `\uXXXX`) — JS ile AYNI.
 */
object WireJson {

    /** Masaustu `JSON.stringify` ile birebir ayni kacis davranisi. */
    val gson: Gson = GsonBuilder()
        .disableHtmlEscaping()
        .create()
}
