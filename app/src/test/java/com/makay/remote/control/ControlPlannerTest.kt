package com.makay.remote.control

import com.makay.remote.rtc.ControlPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Uzak kontrol planlayicisi — Windows -> Android girdi donusumu.
 *
 * Bu sinif framework'ten bagimsiz oldugu icin tamami JVM'de dogrulanabilir;
 * gercek gesture enjeksiyonu (`RemoteControlService`) cihaz testi gerektirir.
 *
 * `clock` enjekte edilir; boylece uzun basma / surukleme SINIRLARI
 * zaman-muhafizakar olarak test edilir.
 */
class ControlPlannerTest {

    private var now = 10_000L
    private val planner = ControlPlanner { now }

    private fun down(x: Double, y: Double, b: String = "left") =
        planner.onEvent(ControlPayload(ControlPayload.MOUSE_DOWN, x = x, y = y, b = b))

    private fun move(x: Double, y: Double) =
        planner.onEvent(ControlPayload(ControlPayload.MOUSE_MOVE, x = x, y = y))

    private fun up(x: Double, y: Double, b: String = "left") =
        planner.onEvent(ControlPayload(ControlPayload.MOUSE_UP, x = x, y = y, b = b))

    // ---------------- dokunma siniflandirma ----------------

    @Test
    fun quickPressWithoutMovement_isTap() {
        down(0.5, 0.4)
        now += 80
        val plan = up(0.5, 0.4)
        assertTrue("Beklenen Tap, gelen: $plan", plan is ControlPlanner.Plan.Tap)
        assertEquals(0.5, (plan as ControlPlanner.Plan.Tap).x, 0.0)
        assertEquals(0.4, plan.y, 0.0)
    }

    @Test
    fun pressBeyondThreshold_isLongPress() {
        down(0.3, 0.3)
        now += ControlPlanner.LONG_PRESS_MS + 50
        val plan = up(0.3, 0.3)
        assertTrue("Beklenen LongPress, gelen: $plan", plan is ControlPlanner.Plan.LongPress)
    }

    /**
     * Masaustu `onCtrlContext` sag tik icin AYNI anda mousedown+mouseup gonderir.
     * Android'de sag tik -> uzun basma (baglam menusu) olur.
     */
    @Test
    fun rightButton_isLongPressEvenWhenQuick() {
        down(0.6, 0.6, b = "right")
        now += 40
        val plan = up(0.6, 0.6, b = "right")
        assertTrue("Beklenen LongPress, gelen: $plan", plan is ControlPlanner.Plan.LongPress)
    }

    @Test
    fun movementBeyondThreshold_isSwipe() {
        down(0.1, 0.1)
        now += 30
        move(0.2, 0.2)
        now += 30
        move(0.6, 0.7)
        now += 30
        val plan = up(0.6, 0.7)
        assertTrue("Beklenen Swipe, gelen: $plan", plan is ControlPlanner.Plan.Swipe)
        val s = plan as ControlPlanner.Plan.Swipe
        assertEquals(0.1, s.fromX, 0.0)
        assertEquals(0.7, s.toY, 0.0)
    }

    /** Hareket var ama noktaya geri donduyse surukleme degil dokunmadir. */
    @Test
    fun circularMotionEndingAtStart_isTapAtEnd() {
        down(0.5, 0.5)
        now += 30
        move(0.9, 0.9) // esik asildi -> moved = true
        now += 30
        move(0.5, 0.5)
        now += 30
        val plan = up(0.5, 0.5)
        assertTrue("Beklenen Tap, gelen: $plan", plan is ControlPlanner.Plan.Tap)
    }

    @Test
    fun mouseUpWithoutMouseDown_producesNoPlan() {
        now += 500
        val plan = up(0.5, 0.5)
        assertTrue("Baslamayan birakma plan uretmemeli", plan is ControlPlanner.Plan.None)
    }

    @Test
    fun mouseMoveWithoutPress_producesNoPlan() {
        val plan = move(0.4, 0.4)
        assertTrue("Basilmayan hareket Android'de imlecsiz oldugundan plansizdir",
            plan is ControlPlanner.Plan.None)
    }

    @Test
    fun reset_clearsPressState() {
        down(0.2, 0.2)
        planner.reset()
        now += 900
        val plan = up(0.2, 0.2)
        assertTrue(plan is ControlPlanner.Plan.None)
    }

    // ---------------- tekerlek ----------------

    @Test
    fun positiveWheel_isDownwardScroll() {
        val plan = planner.onEvent(ControlPayload(ControlPayload.WHEEL, d = 30))
        assertTrue("Beklenen Scroll, gelen: $plan", plan is ControlPlanner.Plan.Scroll)
        assertEquals(ControlPlanner.ScrollDir.DOWN, (plan as ControlPlanner.Plan.Scroll).dir)
    }

    @Test
    fun negativeWheel_isUpwardScroll() {
        val plan = planner.onEvent(ControlPayload(ControlPayload.WHEEL, d = -30))
        assertEquals(ControlPlanner.ScrollDir.UP, (plan as ControlPlanner.Plan.Scroll).dir)
    }

    /** Masaustu delta sinirlamaz; Android `RemoteGuard` ile ayni ±40'a kisitlar. */
    @Test
    fun wheelDelta_isClampedToRemoteGuardLimit() {
        val huge = planner.onEvent(ControlPayload(ControlPayload.WHEEL, d = 5000))
        val max = planner.onEvent(ControlPayload(ControlPayload.WHEEL, d = ControlPlanner.MAX_WHEEL))
        assertTrue(huge is ControlPlanner.Plan.Scroll)
        assertEquals(
            (max as ControlPlanner.Plan.Scroll).scale.toDouble(),
            (huge as ControlPlanner.Plan.Scroll).scale.toDouble(),
            0.0001
        )
    }

    @Test
    fun zeroWheel_producesNoPlan() {
        val plan = planner.onEvent(ControlPayload(ControlPayload.WHEEL, d = 0))
        assertTrue(plan is ControlPlanner.Plan.None)
    }

    // ---------------- tuslar ----------------

    /**
     * Masaustu `onCtrlKeyDown/Up` IKISINI de gonderir. Plan yalnizca
     * keydown'da uretilmelidir — aksi halde her tus iki kez islenir
     * (iki kez geri gitmek gibi).
     */
    @Test
    fun keyProducesPlanOnlyOnKeyDown() {
        val downPlan = planner.onEvent(ControlPayload(ControlPayload.KEY_DOWN, k = "a"))
        val upPlan = planner.onEvent(ControlPayload(ControlPayload.KEY_UP, k = "a"))
        assertEquals(ControlPlanner.Plan.Text("a"), downPlan)
        assertTrue("keyup tekrar plan uretmemeli", upPlan is ControlPlanner.Plan.None)
    }

    @Test
    fun escapeAndBackMapToAndroidBack() {
        assertEquals(ControlPlanner.Plan.Nav(ControlPlanner.NavAction.BACK),
            planner.planKey("esc"))
        assertEquals(ControlPlanner.Plan.Nav(ControlPlanner.NavAction.BACK),
            planner.planKey("Escape"))
        assertEquals(ControlPlanner.Plan.Nav(ControlPlanner.NavAction.BACK),
            planner.planKey("back"))
    }

    @Test
    fun specialKeysMapToEditingOrNothing() {
        assertEquals(ControlPlanner.Plan.DeleteLast, planner.planKey("backspace"))
        assertEquals(ControlPlanner.Plan.DeleteLast, planner.planKey("delete"))
        assertEquals(ControlPlanner.Plan.Text("\n"), planner.planKey("enter"))
        assertEquals(ControlPlanner.Plan.Text(" "), planner.planKey("space"))
        // Windows `normalizeKey` gecirdigi icin "ArrowUp" degil "up" gelir;
        // Android'de karsiligi yok -> yok sayilir (hatali kaydirma olmasin)
        assertTrue(planner.planKey("up") is ControlPlanner.Plan.None)
        assertTrue(planner.planKey("shift") is ControlPlanner.Plan.None)
        assertTrue(planner.planKey("F5") is ControlPlanner.Plan.None)
        assertTrue(planner.planKey("") is ControlPlanner.Plan.None)
    }

    @Test
    fun printableCharactersBecomeText() {
        assertEquals(ControlPlanner.Plan.Text("a"), planner.planKey("a"))
        assertEquals(ControlPlanner.Plan.Text("A"), planner.planKey("A"))
        assertEquals(ControlPlanner.Plan.Text("1"), planner.planKey("1"))
        assertEquals(ControlPlanner.Plan.Text("ğ"), planner.planKey("ğ"))
    }

    // ---------------- koordinat normalizasyonu ----------------

    /**
     * Tel sozlesmesi x/y icin HEM 0..1 ORAN hem PIKSEL kabul eder
     * (`RemoteGuard.sanitizeCoord` ikisini de birakir). Planlayici yalnizca
     * 0..1 ile calistigi icin piksel burada indirgenir — ayni degerler
     * normalize edilmeden `DRAG_THRESHOLD` (0.025) karsisinda aninda
     * "surukleme" sanilirdi.
     */
    @Test
    fun normalizeCoord_acceptsBothRatioAndPixel() {
        val screen = 1000f
        assertEquals(0.5, ControlPlanner.normalizeCoord(0.5, screen)!!, 1e-9)
        assertEquals(1.0, ControlPlanner.normalizeCoord(1.0, screen)!!, 1e-9)
        // 500 piksel / 1000 px ekran = 0.5
        assertEquals(0.5, ControlPlanner.normalizeCoord(500.0, screen)!!, 1e-9)
        // ekran disina tasan piksel sinirlandirilir
        assertEquals(1.0, ControlPlanner.normalizeCoord(2500.0, screen)!!, 1e-9)
    }

    @Test
    fun normalizeCoord_handlesNullAndUnusableValues() {
        assertNull(ControlPlanner.normalizeCoord(null, 1000f))
        // gecersiz degerler sifirlanir; asla NaN/sonsuz disari sismaz
        assertEquals(0.0, ControlPlanner.normalizeCoord(Double.NaN, 1000f)!!, 1e-9)
        assertEquals(0.0, ControlPlanner.normalizeCoord(0.5, 0f)!!, 1e-9)
    }

    /**
     * Uc nokta piksel gonderirse tiklama hala TIKLAMA kalir; esik artik
     * piksel olcusunde degerlendirilmedigi icin tiksama/surukleme ayrimi
     * her iki bicimde de tutarlidir.
     */
    @Test
    fun pixelCoordinates_stillProduceTapForTinyMovement() {
        // 10 piksellik hareket, 1000 px ekran -> 0.01 (esigin altinda = tiklama)
        val dx = ControlPlanner.normalizeCoord(500.0, 1000f)!! -
            ControlPlanner.normalizeCoord(490.0, 1000f)!!
        assertTrue("kucuk piksel hareketi esigi gecmemeli", dx < ControlPlanner.DRAG_THRESHOLD)
    }
}
