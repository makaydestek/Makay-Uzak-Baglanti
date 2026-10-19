package com.makay.remote.rtc

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory
import org.webrtc.SoftwareVideoDecoderFactory
import org.webrtc.SoftwareVideoEncoderFactory
import org.webrtc.audio.AudioDeviceModule
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

/**
 * Surec boyu TEK WebRTC fabrikasi + TEK RTC thread.
 *
 * [initOnMain]: PeerConnectionFactory.initialize + EglBase — ANA thread (libwebrtc
 * bazi OEM'lerde arka plan thread'de initialize edilince ilk PC'de SIGSEGV).
 *
 * Tum PeerConnection / DataChannel native cagrilari [dispatcher] uzerinden.
 * Donanim video codec yerine yazilimsal encoder/decoder.
 */
object RtcEngine {
    private const val TAG = "MakayRtc"

    private val lock = Any()
    private val initialized = AtomicBoolean(false)

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "makay-rtc").apply { isDaemon = true }
    }
    val dispatcher: CoroutineContext = executor.asCoroutineDispatcher()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var factory: PeerConnectionFactory? = null
    @Volatile private var eglBase: EglBase? = null
    @Volatile private var audioModule: AudioDeviceModule? = null

    /**
     * Application.onCreate (ana thread) icinden cagir.
     * Yalnizca initialize + EglBase; PeerConnectionFactory RTC thread'de uretilir.
     */
    fun initOnMain(appContext: Context) {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "RtcEngine.initOnMain ana thread'de cagrilmali"
        }
        synchronized(lock) {
            if (initialized.get()) return
            val ctx = appContext.applicationContext
            CrashLog.breadcrumb("RtcEngine.initOnMain")
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(ctx)
                    .setEnableInternalTracer(false)
                    .createInitializationOptions()
            )
            initialized.set(true)
            if (eglBase == null) {
                eglBase = EglBase.create()
                CrashLog.breadcrumb("EglBase OK (main)")
            }
            CrashLog.breadcrumb("PeerConnectionFactory.initialize OK (main)")
        }
    }

    fun ensure(appContext: Context) {
        if (!initialized.get()) {
            // Application kacirirsa: initialize'i ana thread'e zorla
            if (Looper.myLooper() == Looper.getMainLooper()) {
                initOnMain(appContext)
            } else {
                val latch = CountDownLatch(1)
                var err: Throwable? = null
                mainHandler.post {
                    runCatching { initOnMain(appContext) }.onFailure { err = it }
                    latch.countDown()
                }
                latch.await(15, TimeUnit.SECONDS)
                err?.let { throw it }
            }
        }
        if (factory != null) return
        runOnRtcBlocking {
            synchronized(lock) {
                if (factory != null) return@runOnRtcBlocking
                val ctx = appContext.applicationContext
                CrashLog.breadcrumb("RtcEngine.ensure factory")
                if (eglBase == null) {
                    eglBase = EglBase.create()
                    CrashLog.breadcrumb("EglBase OK (rtc)")
                }
                if (audioModule == null) {
                    /* RECORD_AUDIO yokken bile playout-only ADM; HW AEC/NS kapal. */
                    audioModule = JavaAudioDeviceModule.builder(ctx)
                        .setUseHardwareAcousticEchoCanceler(false)
                        .setUseHardwareNoiseSuppressor(false)
                        .createAudioDeviceModule()
                    CrashLog.breadcrumb("AudioDeviceModule OK")
                }
                factory = PeerConnectionFactory.builder()
                    .setAudioDeviceModule(audioModule)
                    .setVideoEncoderFactory(SoftwareVideoEncoderFactory())
                    .setVideoDecoderFactory(SoftwareVideoDecoderFactory())
                    .createPeerConnectionFactory()
                CrashLog.breadcrumb("PeerConnectionFactory OK")
                Log.i(TAG, "PeerConnectionFactory hazir")
            }
        }
    }

    fun factory(appContext: Context): PeerConnectionFactory {
        ensure(appContext)
        return factory ?: error("PeerConnectionFactory yok")
    }

    fun egl(appContext: Context): EglBase {
        ensure(appContext)
        return eglBase ?: error("EglBase yok")
    }

    fun isOnRtcThread(): Boolean =
        Thread.currentThread().name == "makay-rtc"

    fun post(block: () -> Unit) {
        if (isOnRtcThread()) {
            runCatching(block).onFailure { CrashLog.write("RtcPost: ${it.stackTraceToString()}") }
            return
        }
        executor.execute {
            runCatching(block).onFailure { CrashLog.write("RtcPost: ${it.stackTraceToString()}") }
        }
    }

    fun <T> runOnRtcBlocking(timeoutSec: Long = 20, block: () -> T): T {
        if (isOnRtcThread()) return block()
        val fut = executor.submit<T> { block() }
        return try {
            fut.get(timeoutSec, TimeUnit.SECONDS)
        } catch (e: Exception) {
            CrashLog.write("runOnRtcBlocking: ${e.message}")
            throw e
        }
    }

    suspend fun <T> withRtc(block: suspend () -> T): T =
        withContext(dispatcher) { block() }
}
