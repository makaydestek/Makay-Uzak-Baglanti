package com.makay.remote.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.makay.remote.rtc.PeerBridge
import com.makay.remote.rtc.PeerRegistry
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource

/**
 * Ekran paylasim foreground servisi (MediaProjection).
 *
 * Android 14+ (targetSdk 34+) MediaProjection'un bir foreground service
 * icinde yasamasini ZORUNLU kilar (`foregroundServiceType="mediaProjection"`).
 * Bu servis:
 *   1. bildirim kanali + kalici bildirim olusturur,
 *   2. MediaProjection token'ini alir,
 *   3. `ScreenCapturer` -> `VideoSource.capturerObserver` uzerinden WebRTC'ye
 *      kareleri akir,
 *   4. PeerRegistry'den aktif PeerBridge'e track'i ekler.
 *
 * Kullanim:
 *   1) Activity `MediaProjectionManager.createScreenCaptureIntent()` gonderir,
 *   2) izin alinca `Intent`'i (resultCode + data) bu servise verip
 *      `startForegroundService` ile baslatir.
 */
class ScreenShareService : Service() {

    private var capturer: ScreenCapturerAndroid? = null
    private var videoSource: VideoSource? = null
    private var helper: SurfaceTextureHelper? = null
    private var running = false
    private var baseW = 1280
    private var baseH = 720
    private var baseFps = 24

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this
        if (running) {
            Log.i(TAG, "Zaten calisiyor")
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultCode == 0 || resultData == null) {
            Log.w(TAG, "Gecerli MediaProjection sonucu yok — servis duruyor")
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundWithNotification()

        val peer = PeerRegistry.current
        if (peer == null) {
            Log.w(TAG, "Aktif baglanti yok — servis duruyor")
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            startSharing(resultCode, resultData, peer)
            running = true
            Log.i(TAG, "Ekran paylasimi basladi")
        } catch (e: Exception) {
            Log.w(TAG, "Paylasim baslatilamadi: ${e.message}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startSharing(resultCode: Int, data: Intent, peer: PeerBridge) {
        val metrics = displayMetrics()
        val (width, height, fps) = captureProfile(metrics)

        val source = peer.createVideoSource()
        videoSource = source

        /* ScreenCapturerAndroid MediaProjection'u Intent icinden KENDISI olusturur;
           resultCode'ya ihtiyac duymaz, yalnizca kullanici izin niyeti (data) gerekir. */
        val capt = ScreenCapturerAndroid(data, object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "Sistemden paylasim durduruldu")
                stopSharing()
                stopSelf()
            }
        })
        capturer = capt

        // SurfaceTextureHelper WebRTC capture thread'inde kareleri isler
        val h = peer.newSurfaceTextureHelper()
        helper = h

        capt.initialize(h, this, source.capturerObserver)
        baseW = width
        baseH = height
        baseFps = fps
        capt.startCapture(width, height, fps)
        Log.i(TAG, "Capture $width×$height @ ${fps}fps")

        // PeerBridge track'i ekler ve yeniden pazarligi baslatir
        peer.addScreenShareTrack(source, h, width, height)
    }

    private fun applyTier(tier: Int) {
        val capt = capturer ?: return
        val scale = when (tier) {
            2 -> 0.5f
            1 -> 0.75f
            else -> 1f
        }
        val fps = when (tier) {
            2 -> (baseFps * 0.5f).toInt().coerceAtLeast(8)
            1 -> (baseFps * 0.75f).toInt().coerceAtLeast(10)
            else -> baseFps
        }
        val w = (baseW * scale).toInt().coerceAtLeast(320).let { it - it % 2 }
        val h = (baseH * scale).toInt().coerceAtLeast(240).let { it - it % 2 }
        runCatching { capt.changeCaptureFormat(w, h, fps) }
        Log.i(TAG, "Adaptive tier=$tier → $w×$h @$fps")
    }

    /**
     * Ayarlar > videoQuality: auto|high|medium|low.
     * Mobil bantta native 30 fps pahali; olcek / fps dusurulur.
     */
    private fun captureProfile(m: DisplayMetrics): Triple<Int, Int, Int> {
        val quality = runCatching {
            com.makay.remote.data.SettingsStore(this).videoQuality
        }.getOrDefault("auto")
        val scale = when (quality) {
            "high" -> 1.0f
            "medium" -> 0.75f
            "low" -> 0.5f
            else -> { /* auto: genis ekranda hafif dusur */
                if (m.widthPixels * m.heightPixels > 2_073_600) 0.75f else 1.0f
            }
        }
        val fps = when (quality) {
            "high" -> 30
            "medium" -> 20
            "low" -> 12
            else -> 24
        }
        val w = (m.widthPixels * scale).toInt().coerceAtLeast(320).let { it - it % 2 }
        val h = (m.heightPixels * scale).toInt().coerceAtLeast(240).let { it - it % 2 }
        return Triple(w, h, fps)
    }

    private fun displayMetrics(): DisplayMetrics {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val m = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            m.widthPixels = b.width()
            m.heightPixels = b.height()
            m.densityDpi = resources.displayMetrics.densityDpi
        } else {
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(m)
        }
        return m
    }

    private fun stopSharing() {
        runCatching { capturer?.stopCapture() }
        runCatching { capturer?.dispose() }
        runCatching { videoSource?.dispose() }
        runCatching { helper?.dispose() }
        capturer = null
        videoSource = null
        helper = null
        running = false

        PeerRegistry.current?.let { runCatching { it.removeVideoTrack() } }
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        stopSharing()
        super.onDestroy()
    }

    private fun startForegroundWithNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Ekran paylaşımı",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Uzaktan bağlantı ekran paylaşımı"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MAKAY Uzaktan Bağlantı")
            .setContentText("Ekranınız paylaşılıyor")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val TAG = "MakayScreenShare"
        private const val CHANNEL_ID = "makay_screen_share"
        private const val NOTIFICATION_ID = 4711

        const val EXTRA_RESULT_CODE = "makay.result_code"
        const val EXTRA_RESULT_DATA = "makay.result_data"

        @Volatile private var instance: ScreenShareService? = null

        /** Adaptive: 0=tam, 1=%75, 2=%50 — host ekran paylasirken. */
        fun applyAdaptiveTier(tier: Int) {
            instance?.applyTier(tier.coerceIn(0, 2))
        }

        /**
         * Servisi baslatir.
         * @param resultCode/kullanici izni `createScreenCaptureIntent` sonucu
         */
        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, ScreenShareService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ScreenShareService::class.java))
        }
    }
}
