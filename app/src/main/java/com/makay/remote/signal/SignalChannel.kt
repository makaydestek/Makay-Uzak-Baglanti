package com.makay.remote.signal

/**
 * Sinyal kanali sozlesmesi — iki yontem de bunu uygular:
 *
 *  1. [MqttSignalClient] — bulut eslesme (halka acik MQTT broker'lari, WSS)
 *  2. [HttpSignalClient] — HTTP sinyal sunucusu (Cloudflare tüneli / LAN)
 *
 * Secim kurali ([SessionController.join]): davet metninde "Sinyal: https://…"
 * satiri VARSA HTTP ile katilinir (telefonun halka broker'lara erisemedigi
 * mobil aglarda da calisir), YOKSA (yalnizca kimlik+sifre girildiginde ya da
 * Android'in actigi odada) MQTT kullanilir.
 */
interface SignalChannel {

    fun interface Listener {
        fun onMessage(env: SignalEnvelope)
    }

    fun interface CloseListener {
        fun onClosed()
    }

    /**
     * Odaya abone olur ve gelen mesajlari [onMessage] ile teslim eder.
     *
     * HTTP: POST /api/join + poll dongusu. MQTT: broker baglantisi + konu.
     *
     * @throws Exception odaya/aga basilamazsa (yogunlastirma, 404, 403…)
     */
    suspend fun subscribe(
        room: String,
        password: String,
        onMessage: Listener,
        onClose: CloseListener?
    )

    /**
     * Odaya sinyal gonderir (zarf [SignalEnvelope]; version/pinHash/ts/from
     * doldurulur).
     *
     * @throws Exception yazma basarisiz olursa
     */
    suspend fun publish(payload: SignalEnvelope)

    /** Oturumu kapatir (tekrar cagrilmasi guvenlidir). */
    fun close()
}
