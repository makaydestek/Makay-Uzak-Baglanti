package com.makay.remote.core

/**
 * MAKAY Remote — ICE sunucu yapilandirmasi.
 * Masaustu `engine.js:parseIceServers` ile ayni davranis.
 */
object IceConfig {

    val DEFAULT_STUN = listOf(
        "stun:stun.l.google.com:19302",
        "stun:stun1.l.google.com:19302",
        "stun:stun2.l.google.com:19302",
        "stun:stun3.l.google.com:19302",
        "stun:stun4.l.google.com:19302",
        "stun:stun.cloudflare.com:3478"
    )

    /**
     * Yedek acik TURN. NOT: Eski varsayilan `openrelay.metered.ca` (Open Relay)
     * 2026'da kapandi ve artik kara delik bir adrese (192.0.2.1) cozuldugu icin
     * baglanti hic kurulamiyordu. Artik kullanici Ayarlar'dan kendi TURN
     * sunucusunu girmelidir; burada varsayilan TURN tanimlanmaz.
     */
    private val DEFAULT_TURN_URLS: List<String> = emptyList()
    private const val DEFAULT_TURN_USER = ""
    private const val DEFAULT_TURN_PASS = ""

    data class Server(
        val urls: List<String>,
        val username: String? = null,
        val credential: String? = null
    )

    /**
     * @param turnUrl virgulle ayrilmis bir veya birden fazla TURN adresi
     * @param includeDefaultTurn yalnizca kullanici ayarlardan aciksa true
     */
    fun parse(
        turnUrl: String? = null,
        turnUsername: String? = null,
        turnPassword: String? = null,
        includeDefaultTurn: Boolean = false
    ): List<Server> {
        val out = ArrayList<Server>()
        DEFAULT_STUN.forEach { out.add(Server(listOf(it))) }

        var hasTurn = false
        val raw = turnUrl?.trim().orEmpty()
        if (raw.isNotEmpty()) {
            var urls = if (raw.contains(',')) {
                raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            } else {
                listOf(raw)
            }
            // `turn:host:port` -> otomatik `turns:...tcp` yedegi ekle
            if (urls.size == 1 &&
                urls[0].startsWith("turn:", ignoreCase = true) &&
                !urls[0].contains("?transport=")
            ) {
                val base = urls[0]
                urls = listOf(base, base.replaceFirst("turn:", "turns:") + "?transport=tcp")
            }
            out.add(
                Server(
                    urls = urls,
                    username = turnUsername?.takeIf { it.isNotBlank() },
                    credential = turnPassword?.takeIf { it.isNotBlank() }
                )
            )
            hasTurn = true
        }

        // Varsayilan TURN listesi bos — bos Server eklemek ICE'yi bozar.
        if (!hasTurn && includeDefaultTurn && DEFAULT_TURN_URLS.isNotEmpty()) {
            out.add(Server(DEFAULT_TURN_URLS, DEFAULT_TURN_USER, DEFAULT_TURN_PASS))
        }
        return out
    }

    /** Kullaniciya sunulacak halka TURN var mi? (su an yok). */
    fun hasBuiltInTurnFallback(): Boolean = DEFAULT_TURN_URLS.isNotEmpty()
}