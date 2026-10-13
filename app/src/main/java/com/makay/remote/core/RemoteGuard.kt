package com.makay.remote.core

/**
 * MAKAY Remote — kontrol/aktarim guvenlik denetleyicisi (TEL SINIRI).
 *
 * Buradaki `sanitizeControl` KANONIK TEL SOZLUGUNDE calisir (`ctrl` zarfindaki
 * `ev` payload'i): mousemove/mousedown/mouseup/wheel/keydown/keyup. Ayni
 * sinirlar masaustu `apps/remote/remote-guard.js` (relay siniri) ve
 * `app.js:onControl` (tel siniri) ile uyumludur. Buradan gecen bir olay
 * masaustunda da kabul edilir.
 */
object RemoteGuard {

    private val ALLOWED_BUTTONS = setOf("left", "right", "middle")

    /**
     * KANONIK TEL (wire) SOZLUGU — `ctrl` zarfindaki `ev.type` degerleri.
     * Masaustu `app.js:onControl` bu tipleri okur; Android `ControlPayload`
     * ile bu tipleri üretir/alır. Yön (basili/birakildi) type icindedir
     * (mousedown/keyup); `d` ALANININ TELDE ANLAMI yalniz wheel delta'dir.
     */
    private val ALLOWED_TYPES = setOf(
        "mousemove", "mousedown", "mouseup", "wheel", "keydown", "keyup"
    )

    /**
     * Tuş adı (k): 1-32 karakter, kontrol karakterleri ve JSON ayraclari
     * (, : " \ { }) yok. Masaustu `remote-guard.js KEY_NAME_RE` ile ayni kural.
     */
    private val KEY_NAME = Regex("^[^\\x00-\\x1f\",:{}\\\\]{1,32}$")

    const val MAX_FILE_BYTES = 100L * 1024L * 1024L

    /** Windows tarafinda calistirilabilir dosya turleri gonderilemez. */
    private val BLOCKED_EXT =
        Regex("\\.(exe|bat|cmd|ps1|msi|scr|com|vbs|js|jse|wsf|dll)$", RegexOption.IGNORE_CASE)

    /**
     * 0..1 oran veya piksel. `InputRelay.cs` her ikisini de kabul eder.
     * Gecersizse null doner (olay dusurulur).
     */
    fun sanitizeCoord(value: Double?): Double? {
        val v = value ?: return null
        if (v.isNaN() || v.isInfinite()) return null
        return when {
            v >= 0.0 && v <= 1.0 -> v
            v <= 16000.0 -> Math.round(v).toDouble()
            else -> null
        }
    }

    sealed interface ControlEvent {
        data class Move(val x: Double, val y: Double) : ControlEvent
        data class Button(
            val button: String,
            val down: Boolean,
            val x: Double?,
            val y: Double?
        ) : ControlEvent

        data class Wheel(val delta: Int) : ControlEvent
        data class Key(val name: String, val down: Boolean) : ControlEvent
    }

    /**
     * `ctrl` zarfindaki `ev` payload'ini (tel bicimi) dogrular ve normalize eder.
     * Gecerli degilse null doner (olay dusurulur).
     *
     * Turler ve alanlar kanonik tel sozlugundedir:
     *  - mousemove: x, y zorunlu (0..1 oran veya piksel)
     *  - mousedown/mouseup: b (left/right/middle, opsiyonel -> left), x/y opsiyonel
     *  - wheel: d (±40 ile sinirlanir)
     *  - keydown/keyup: k (tuş adi), yon type'tan gelir
     */
    fun sanitizeControl(
        type: String?,
        x: Double?,
        y: Double?,
        button: String?,
        delta: Int?,
        keyName: String?
    ): ControlEvent? {
        val t = type.orEmpty()
        if (t !in ALLOWED_TYPES) return null

        return when (t) {
            "mousemove" -> {
                val sx = sanitizeCoord(x) ?: return null
                val sy = sanitizeCoord(y) ?: return null
                ControlEvent.Move(sx, sy)
            }

            "mousedown", "mouseup" -> {
                val b = (button ?: "left").lowercase()
                if (b !in ALLOWED_BUTTONS) return null
                val sx = sanitizeCoord(x)
                val sy = sanitizeCoord(y)
                ControlEvent.Button(b, t == "mousedown", sx, sy)
            }

            "wheel" -> {
                val d = (delta ?: 0).coerceIn(-40, 40)
                ControlEvent.Wheel(d)
            }

            "keydown", "keyup" -> {
                val k = keyName.orEmpty()
                if (!KEY_NAME.matches(k)) return null
                ControlEvent.Key(k, t == "keydown")
            }

            else -> null
        }
    }

    data class FileCheck(val ok: Boolean, val error: String? = null, val name: String? = null, val size: Long = 0, val mime: String = "application/octet-stream")

    fun validateFileTransfer(name: String?, size: Long, mime: String?): FileCheck {
        val n = name ?: "dosya"
        if (size <= 0L) return FileCheck(false, "Boş dosya")
        if (size > MAX_FILE_BYTES) return FileCheck(false, "Dosya 100 MB sınırını aşıyor")
        if (BLOCKED_EXT.containsMatchIn(n)) return FileCheck(false, "Bu dosya türü uzaktan gönderilemez")
        return FileCheck(
            ok = true,
            name = n.take(180),
            size = size,
            mime = (mime ?: "application/octet-stream").take(120)
        )
    }

    /**
     * Oda basina deneme limiti (PIN rate gate).
     * Windows tarafinda ayni pencere/limit ile uygulanir.
     */
    class PinGate(
        private val windowMs: Long = 60_000L,
        private val maxAttempts: Int = 8
    ) {
        private data class Entry(var t0: Long, var n: Int)
        private val map = HashMap<String, Entry>()

        @Synchronized
        fun allow(key: String): Boolean {
            val now = System.currentTimeMillis()
            val e = map[key]
            if (e == null || now - e.t0 > windowMs) {
                map[key] = Entry(now, 1)
                return true
            }
            e.n += 1
            return e.n <= maxAttempts
        }

        @Synchronized
        fun reset(key: String) {
            map.remove(key)
        }
    }
}