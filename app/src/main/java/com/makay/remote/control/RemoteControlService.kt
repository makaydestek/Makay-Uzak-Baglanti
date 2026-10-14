package com.makay.remote.control

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.makay.remote.rtc.ControlPayload
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Windows -> Android YONUNDE UZAK KONTROL.
 *
 * Masaustu `app.js` `ctrl` zarfini gonderir; `PeerBridge` bunu
 * `SessionController.onControl` uzerinden buraya yonlendirir. Bu servis
 * normalize (0..1) olaylari Android girdisine cevirir:
 *
 *  - dokunma/surukleme/uzun basma -> `GestureDescription.dispatchGesture`
 *  - kaydirma (wheel)             -> dikey swipe
 *  - gezinme tuslari              -> `performGlobalAction` (geri/ana sayfa)
 *  - metin tuslari                -> odakli alana `ACTION_SET_TEXT`
 *
 * NEDEN ACCESSIBILITYSERVICE?
 * Android'de uygulama disi girdi enjeksiyonu icin yalnizca iki yol vardir:
 * `INJECT_EVENTS` izni yalnizca SISTEM imzasiyla verilir; uygulamalarin
 * erisebildigi tek mekanizma kullanici tarafindan acilan
 * AccessibilityService'tir. Bu yuzden kullanicinin Ayarlar ->
 * Erisilebilirlik ekranindan "MAKAY Uzak Kontrol" servisini ACMASI gerekir.
 *
 * ORL (birakma) SINIRI: `dispatchGesture` ayni anda TEK gesture kabul eder;
 * calisan bir gesture varken yeni istek `false` dondurur (kayip kabul edilir).
 * Bu bilincli bir sinirdir — ana sayi gelen olaylaridir.
 */
class RemoteControlService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val planner = ControlPlanner()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _available.value = true
        Log.i(TAG, "Uzak kontrol servisi baglandi")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) {
            instance = null
            _available.value = false
        }
        planner.reset()
        super.onDestroy()
    }

    /**
     * Bir `ctrl` olayini uygular. Ana thread'e devredilir (WebRTC callback'i
     * farkli bir thread'den gelir). Servis yokken false doner.
     */
    fun handle(ev: ControlPayload): Boolean {
        handler.post { runCatching { execute(ev) }.onFailure { Log.w(TAG, "Girdi uygulanamadi: ${it.message}") } }
        return true
    }

    private fun execute(ev: ControlPayload) {
        val (w, h) = screenSize()
        if (w <= 0f || h <= 0f) return
        val plan = planner.onEvent(normalize(ev, w, h))
        if (plan is ControlPlanner.Plan.None) return
        when (plan) {
            is ControlPlanner.Plan.Tap -> tap(plan.x, plan.y, TAP_MS)
            is ControlPlanner.Plan.LongPress -> tap(plan.x, plan.y, LONG_PRESS_MS)
            is ControlPlanner.Plan.Swipe -> swipe(plan.fromX, plan.fromY, plan.toX, plan.toY)
            is ControlPlanner.Plan.Scroll -> scroll(plan.dir, plan.scale)
            is ControlPlanner.Plan.Nav -> nav(plan.action)
            is ControlPlanner.Plan.Text -> insertText(plan.text)
            is ControlPlanner.Plan.DeleteLast -> deleteLast()
            is ControlPlanner.Plan.None -> Unit
        }
    }

    // ---------------- koordinat ----------------

    private fun screenSize(): Pair<Float, Float> {
        val dm = resources.displayMetrics
        return dm.widthPixels.toFloat() to dm.heightPixels.toFloat()
    }

    /**
     * Gelen `x`/`y`'yi 0..1'e indirger.
     *
     * Tel sozlesmesi **hem ORAN hem PIKSEL** kabul eder (`RemoteGuard.sanitizeCoord`
     * ikisini de birakir). Planlayici ise surukleme esigi de dahil yalnizca 0..1
     * ile calisir — normalize edilmeseydi piksel gonderen bir taraf her tiklamayi
     * surukleme sanirdi.
     */
    private fun normalize(ev: ControlPayload, w: Float, h: Float): ControlPayload =
        ev.copy(
            x = ControlPlanner.normalizeCoord(ev.x, w),
            y = ControlPlanner.normalizeCoord(ev.y, h)
        )

    private fun px(x: Double, y: Double, w: Float, h: Float): Pair<Float, Float> {
        // 0..1 normalize -> piksel. Buraya hep normalize edilmis deger gelir;
        // coerceIn son bir savunma katmanidir.
        val nx = x.coerceIn(0.0, 1.0).toFloat()
        val ny = y.coerceIn(0.0, 1.0).toFloat()
        return (nx * w) to (ny * h)
    }

    // ---------------- gesture ----------------

    private fun tap(nx: Double, ny: Double, durationMs: Long) {
        val (w, h) = screenSize()
        if (w <= 0f || h <= 0f) return
        val (x, y) = px(nx, ny, w, h)
        val path = Path().apply { moveTo(x, y) }
        dispatch(path, durationMs)
    }

    private fun swipe(fx: Double, fy: Double, tx: Double, ty: Double) {
        val (w, h) = screenSize()
        if (w <= 0f || h <= 0f) return
        val (x1, y1) = px(fx, fy, w, h)
        val (x2, y2) = px(tx, ty, w, h)
        if (x1 == x2 && y1 == y2) {
            tap(fx, fy, TAP_MS)
            return
        }
        // Mesafe ile sure olceklensin (yavas surukleme hissi)
        val dist = kotlin.math.hypot((x2 - x1).toDouble(), (y2 - y1).toDouble())
        val duration = (SWIPE_MIN_MS.toDouble() + dist / SWIPE_PX_PER_MS)
            .coerceIn(SWIPE_MIN_MS.toDouble(), SWIPE_MAX_MS.toDouble())
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        dispatch(path, duration.toLong())
    }

    /**
     * Tekerlek -> dikey kaydirma.
     *
     * `ScrollDir.DOWN` (tekerlek asagi / icerik asagi dogru) parmak YUKARI
     * kayar: ekranda icerigin yukari akmasini saglar — dokunmatiktekiyle ayni.
     */
    private fun scroll(dir: ControlPlanner.ScrollDir, scale: Float) {
        val (w, h) = screenSize()
        if (w <= 0f || h <= 0f) return
        val travel = (h * SCROLL_TRAVEL_FRACTION * scale.coerceIn(0.3f, 1f))
        val cx = w * 0.5f
        val cy = h * 0.55f
        val (y1, y2) = when (dir) {
            ControlPlanner.ScrollDir.DOWN -> (cy + travel / 2f) to (cy - travel / 2f)
            ControlPlanner.ScrollDir.UP -> (cy - travel / 2f) to (cy + travel / 2f)
        }
        val path = Path().apply {
            moveTo(cx, y1)
            lineTo(cx, y2)
        }
        dispatch(path, SCROLL_MS)
    }

    private fun dispatch(path: Path, durationMs: Long) {
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        val ok = dispatchGesture(gesture, null, null)
        if (!ok) Log.d(TAG, "Gesture reddedildi (devam eden gesture olabilir)")
    }

    private fun nav(action: ControlPlanner.NavAction) {
        val id = when (action) {
            ControlPlanner.NavAction.BACK -> GLOBAL_ACTION_BACK
            ControlPlanner.NavAction.HOME -> GLOBAL_ACTION_HOME
            ControlPlanner.NavAction.RECENTS -> GLOBAL_ACTION_RECENTS
        }
        performGlobalAction(id)
    }

    // ---------------- metin ----------------

    private fun focusedInput(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
        if (!f.isEditable) return null
        f.refresh()
        return f
    }

    private fun setText(node: AccessibilityNodeInfo, value: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /** Odakli metin alanina karakter ekler (sona). */
    private fun insertText(s: String): Boolean {
        val node = focusedInput() ?: return false
        val current = node.text?.toString() ?: ""
        return setText(node, current + s)
    }

    /** Odakli metin alanindan son karakteri siler. */
    private fun deleteLast(): Boolean {
        val node = focusedInput() ?: return false
        val current = node.text?.toString() ?: ""
        if (current.isEmpty()) return false
        return setText(node, current.dropLast(1))
    }

    companion object {
        private const val TAG = "MakayRemoteControl"

        private const val TAP_MS = 60L
        private const val LONG_PRESS_MS = 750L
        private const val SWIPE_MIN_MS = 180L
        private const val SWIPE_MAX_MS = 700L
        private const val SWIPE_PX_PER_MS = 4.0
        private const val SCROLL_MS = 220L
        private const val SCROLL_TRAVEL_FRACTION = 0.45f

        @Volatile
        private var instance: RemoteControlService? = null

        private val _available = MutableStateFlow(false)

        /** Servis kullanici tarafindan acildiginda true olur. */
        val available: StateFlow<Boolean> = _available.asStateFlow()

        fun isAvailable(): Boolean = instance != null

        /**
         * Bir `ctrl` olayini uygular. Servis kapaliysa false doner — cagiran
         * kullanicinin "Erisilebilirlik" ayarini acmasi gerektigini soyleyebilir.
         */
        fun apply(ev: ControlPayload): Boolean {
            val svc = instance ?: return false
            return svc.handle(ev)
        }
    }
}
