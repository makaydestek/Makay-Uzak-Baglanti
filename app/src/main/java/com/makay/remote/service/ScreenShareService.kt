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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
        val width = metrics.widthPixels
        val height = metrics.heightPixels

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
        capt.startCapture(width, height, 30)

        // PeerBridge track'i ekler ve yeniden pazarligi baslatir
        peer.addScreenShareTrack(source, h, width, height)
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
