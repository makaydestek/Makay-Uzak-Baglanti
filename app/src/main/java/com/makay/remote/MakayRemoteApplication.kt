package com.makay.remote

import android.app.Application
import com.makay.remote.rtc.CrashLog
import com.makay.remote.rtc.RtcEngine

class MakayRemoteApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        /* libwebrtc initialize + EglBase ANA thread'de olmali — arka planda
           yapilirsa bazi cihazlarda ilk PeerConnection'da surec dusuyor. */
        runCatching {
            RtcEngine.initOnMain(this)
            CrashLog.breadcrumb("Application.onCreate initOnMain OK")
        }.onFailure {
            CrashLog.write("Application.onCreate: ${it.stackTraceToString()}")
        }
    }
}
