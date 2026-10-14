package com.makay.remote.control

import com.makay.remote.rtc.ControlPayload
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Gelen `ctrl` olaylarini Android girdi PLANINA cevirir.
 *
 * SAF MANTIK: Android framework'unden tamamen bagimsizdir (GestureDescription,
 * AccessibilityNodeInfo yok) — bu yuzden JVM unit test ile dogrulanabilir.
 * Gercek enjeksiyon `RemoteControlService`'te yapilir.
 *
 * Masaustu `app.js:onControl` gonderdigi tel bicimi:
 *   - mousemove: x, y (0..1)          -> Android'de imleç YOKTUR, yalnizca
 *                                         surukleme sirasinda konum gunceller
 *   - mousedown/mouseup: b, x, y      -> dokunma (asagidaki kurallar)
 *   - wheel: d (±40)                  -> kaydirma (swipe)
 *   - keydown/keyup: k                -> yalnizca KEY_DOWN plan uretir
 *
 * Dokunma siniflandirma (mouseup aninda):
 *   1. baslangiç -> bitis mesafesi >= ESIK  => SURUKLEME (swipe)
 *   2. hareket var ama geri dondu           => DOKUNMA (bitis noktasi)
 *   3. sag tik (b=right)                    => UZUN BASMA (baglam menusu)
 *   4. sure >= LONG_PRESS_MS                => UZUN BASMA
 *   5. diger                                => DOKUNMA
 */
class ControlPlanner(
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    // ---- fare durumu ----
    private var pressed = false
    private var startX = 0.0
    private var startY = 0.0
    private var lastX = 0.0
    private var lastY = 0.0
    private var downAt = 0L
    private var moved = false

    /** Bir gelen `ctrl` olayini isler; doneen plan uygulanmak uzere servise gider. */
    fun onEvent(ev: ControlPayload): Plan {
        val x = ev.x
        val y = ev.y
        return when (ev.type) {
            ControlPayload.MOUSE_MOVE -> {
                if (pressed && x != null && y != null) {
                    lastX = x
                    lastY = y
                    if (hypot(x - startX, y - startY) >= DRAG_THRESHOLD) moved = true
                }
                Plan.None
            }

            ControlPayload.MOUSE_DOWN -> {
                pressed = true
                moved = false
                downAt = clock()
                startX = x ?: lastX
                startY = y ?: lastY
                lastX = startX
                lastY = startY
                Plan.None
            }

            ControlPayload.MOUSE_UP -> {
                val upX = x ?: lastX
                val upY = y ?: lastY
                val wasPressed = pressed
                val duration = clock() - downAt
                val right = ev.b.equals("right", ignoreCase = true)
                // Baslamamis bir birakma planlanmaz (sürpriz dokunma olmasin)
                if (!wasPressed) {
                    pressed = false
                    return Plan.None
                }
                pressed = false
                planRelease(startX, startY, upX, upY, duration, moved, right)
            }

            ControlPayload.WHEEL -> planWheel(ev.d ?: 0)

            ControlPayload.KEY_DOWN -> planKey(ev.k.orEmpty())

            // Yon zaten type'ta; tekrar planlanmaz
            ControlPayload.KEY_UP -> Plan.None

            else -> Plan.None
        }
    }

    private fun planRelease(
        sx: Double, sy: Double,
        ex: Double, ey: Double,
        durationMs: Long,
        wasMoved: Boolean,
        rightButton: Boolean
    ): Plan {
        val dist = hypot(ex - sx, ey - sy)
        return when {
            dist >= DRAG_THRESHOLD -> Plan.Swipe(sx, sy, ex, ey)
            wasMoved -> Plan.Tap(ex, ey)
            rightButton -> Plan.LongPress(sx, sy)
            durationMs >= LONG_PRESS_MS -> Plan.LongPress(sx, sy)
            else -> Plan.Tap(sx, sy)
        }
    }

    /** Sadece KEY_DOWN icin plan uretir (KEY_UP tekrarlama yapmaz). */
    fun planKey(raw: String): Plan {
        val k = raw.trim()
        if (k.isEmpty()) return Plan.None
        val lower = k.lowercase()
        return when (lower) {
            // ---- gezinme (Android global aksiyonlari) ----
            "esc", "escape", "back" -> Plan.Nav(NavAction.BACK)
            "home", "win" -> Plan.Nav(NavAction.HOME)
            "apps", "contextmenu" -> Plan.Nav(NavAction.RECENTS)

            // ---- metin duzenleme ----
            "backspace", "delete" -> Plan.DeleteLast
            "enter", "return" -> Plan.Text("\n")
            "space" -> Plan.Text(" ")

            // ---- yazilabilir tek karakter ----
            else -> if (k.length == 1 && !k[0].isISOControl()) Plan.Text(k) else Plan.None
        }
    }

    /** Tekerlek olayini dikey kaydirma (swipe) planina cevirir. */
    fun planWheel(delta: Int): Plan {
        val d = delta.coerceIn(-MAX_WHEEL, MAX_WHEEL)
        if (d == 0) return Plan.None
        // Buyuk delta daha uzun kaydirma yapar (0.6f .. 1.0f)
        val scale = 0.6f + 0.4f * (abs(d).toFloat() / MAX_WHEEL)
        return Plan.Scroll(if (d > 0) ScrollDir.DOWN else ScrollDir.UP, scale)
    }

    /** Yeni oturum: sonraki baglanti icin fare durumunu sifirlar. */
    fun reset() {
        pressed = false
        moved = false
        downAt = 0L
        startX = 0.0
        startY = 0.0
        lastX = 0.0
        lastY = 0.0
    }

    /** Android tarafinda yapilacak somut girdi eylemi (0..1 normalize koordinat). */
    sealed class Plan {
        data class Tap(val x: Double, val y: Double) : Plan()
        data class LongPress(val x: Double, val y: Double) : Plan()
        data class Swipe(val fromX: Double, val fromY: Double, val toX: Double, val toY: Double) : Plan()
        data class Scroll(val dir: ScrollDir, val scale: Float) : Plan()
        data class Nav(val action: NavAction) : Plan()
        data class Text(val text: String) : Plan()
        object DeleteLast : Plan()
        object None : Plan()
    }

    enum class ScrollDir { UP, DOWN }
    enum class NavAction { BACK, HOME, RECENTS }

    companion object {
        /** 0..1 cinsinden surukleme esigi (ekranin ~%2.5'i). */
        const val DRAG_THRESHOLD = 0.025

        /** Uzun basma suresi (Android `ViewConfiguration.getLongPressTimeout` ~500ms). */
        const val LONG_PRESS_MS = 600L

        /** Tekerlek delta siniri — `RemoteGuard` ile ayni. */
        const val MAX_WHEEL = 40

        /**
         * Tel sozlesmesi `x`/`y` icin **IKI** bicim kabul eder: 0..1 ORAN ya da
         * PIKSEL (`RemoteGuard.sanitizeCoord` ikisini de birakir — masaustu
         * `InputRelay.cs` piksel de gonderir).
         *
         * Bu planlayici yalnizca 0..1 ile calistir; piksel degerleri burada
         * normalize edilir. Boylece `DRAG_THRESHOLD` her iki bicimde de dogru
         * calisir — normalize edilmeseydi 10 piksellik bir hareket
         * 10.0 >> 0.025 oldugu icin her tiksama SURUKLEME sanilirdi.
         */
        fun normalizeCoord(value: Double?, size: Float): Double? {
            val v = value ?: return null
            if (v.isNaN() || v.isInfinite() || size <= 0f) return 0.0
            val n = if (v > 1.0) (v / size) else v
            return n.coerceIn(0.0, 1.0)
        }
    }
}
