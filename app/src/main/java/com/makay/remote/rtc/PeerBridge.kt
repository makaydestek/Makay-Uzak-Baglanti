package com.makay.remote.rtc

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.makay.remote.core.IceConfig
import com.makay.remote.core.Primitives
import com.makay.remote.core.SdpCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.AudioDeviceModule
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer
import kotlin.coroutines.resume

/**
 * MAKAY Remote — WebRTC P2P koprusu.
 *
 * Masaustu `engine.js:createPeer` ile ayni sozlesme:
 *   - DataChannel adi: `makay`
 *   - Kanal acilir acilmaz `hello {sid,name,h=sha256(sifre)}`
 *   - Butun mesajlar JSON zarf `{t:...}`
 *   - SDP teklif/cevabi disarida uretilir (`SdpCode`), ICE adaylari
 *     `sendSignal` geri cagrisiyla bulut eslesme kanalindan gecer.
 */
class PeerBridge(
    private val appContext: Context,
    private val role: Role,
    private val deviceName: String,
    private val password: String,
    private val iceServers: List<IceConfig.Server>,
    private val scope: CoroutineScope,
    /** Bulut eslesme uzerinden gonderilen sinyal (JSON metni). */
    private val sendSignal: suspend (String) -> Unit
) {

    enum class Role { OFFERER, ANSWERER }

    // ---------------- olaylar ----------------
    interface Listener {
        fun onState(state: String) {}
        fun onReady(peerName: String) {}
        fun onText(text: String, ts: Long) {}
        fun onControlRequest() {}
        fun onControlGranted() {}
        fun onControlDenied() {}
        fun onControlStopped() {}
        fun onControl(ev: ControlPayload) {}
        fun onClipboard(text: String) {}
        fun onMicToggle(enabled: Boolean) {}
        fun onRemoteVideo(track: VideoTrack?) {}
        fun onRemoteAudio(track: AudioTrack?) {}
        fun onVideoStopped() {}
        fun onBye(reason: String) {}
        fun onError(message: String) {}
        fun onFileProgress(p: FileTransferEngine.Progress) {}
        fun onFileComplete(file: FileTransferEngine.Received) {}
        fun onFileCancel(fid: String) {}
    }

    var listener: Listener? = null
    val files = FileTransferEngine()

    private val gson = com.makay.remote.core.WireJson.gson
    private var factory: PeerConnectionFactory? = null
    private var audioDeviceModule: AudioDeviceModule? = null
    private var pc: PeerConnection? = null
    private var channel: DataChannel? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null

    var sessionId: String = Primitives.newSessionId()
        private set
    var peerName: String = ""
        private set
    var peerReady: Boolean = false
        private set

    @Volatile private var closed = false
    @Volatile private var makingOffer = false
    private val pendingRemoteIces = ArrayList<IceCandidate>()
    @Volatile private var firstCandidateSeen = false
    private var gatheringLatch: java.util.concurrent.CountDownLatch? = null

    private val eglBase: EglBase by lazy { EglBase.create() }

    // ---------------- fabrika ----------------

    private fun ensureFactory(): PeerConnectionFactory {
        factory?.let { return it }
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appContext)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )
        val adm = JavaAudioDeviceModule.builder(appContext)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()
        audioDeviceModule = adm

        val f = PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .setVideoEncoderFactory(
                DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
            )
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
        factory = f
        return f
    }

    private fun buildIceServers(): List<PeerConnection.IceServer> {
        val out = ArrayList<PeerConnection.IceServer>()
        val stun = iceServers.filter { it.urls.all { u -> u.startsWith("stun:") } }
        val turn = iceServers.filter { it.urls.any { u -> u.startsWith("turn") } }

        if (stun.isNotEmpty()) {
            val urls = stun.flatMap { it.urls }
            out.add(PeerConnection.IceServer.builder(urls).createIceServer())
        }
        if (turn.isNotEmpty()) {
            val t = turn.first()
            out.add(
                PeerConnection.IceServer.builder(t.urls)
                    .setUsername(t.username ?: "")
                    .setPassword(t.credential ?: "")
                    .setTlsCertPolicy(PeerConnection.TlsCertPolicy.TLS_CERT_POLICY_SECURE)
                    .createIceServer()
            )
        }
        return out
    }

    private fun rtcConfig(): PeerConnection.RTCConfiguration {
        val cfg = PeerConnection.RTCConfiguration(buildIceServers())
        cfg.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        cfg.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        cfg.tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
        return cfg
    }

    private fun createPc(): PeerConnection {
        val created = ensureFactory().createPeerConnection(rtcConfig(), pcObserver)
        return created ?: throw IllegalStateException("PeerConnection oluşturulamadı")
    }

    private val pcObserver = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            when (state) {
                PeerConnection.IceConnectionState.FAILED ->
                    listener?.onError("Ağ tünelleri kurulamadı — Ayarlar’dan «Yalnızca TURN» deneyin")
                PeerConnection.IceConnectionState.DISCONNECTED ->
                    listener?.onError("Bağlantı koptu — yeniden bağlanılıyor")
                PeerConnection.IceConnectionState.COMPLETED,
                PeerConnection.IceConnectionState.CHECKING -> listener?.onState("connecting")
                else -> Unit
            }
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit

        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
            if (state == PeerConnection.IceGatheringState.COMPLETE) gatheringLatch?.countDown()
        }

        override fun onIceCandidate(candidate: IceCandidate?) {
            if (candidate == null) return
            firstCandidateSeen = true
            val msg = WireEnvelope(
                type = WireEnvelope.ICE,
                mid = candidate.sdpMid ?: "",
                mline = candidate.sdpMLineIndex,
                cand = candidate.sdp,
                ufrag = ""
            )
            scope.launch(Dispatchers.IO) {
                runCatching { sendSignal(gson.toJson(msg)) }
            }
        }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit

        override fun onAddStream(stream: org.webrtc.MediaStream?) {
            stream?.videoTracks?.firstOrNull()?.let { listener?.onRemoteVideo(it) }
            stream?.audioTracks?.firstOrNull()?.let { listener?.onRemoteAudio(it) }
        }

        override fun onRemoveStream(stream: org.webrtc.MediaStream?) {
            listener?.onRemoteVideo(null)
        }

        override fun onDataChannel(ch: DataChannel?) {
            ch?.let { bindChannel(it) }
        }

        override fun onRenegotiationNeeded() = negotiate()

        override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out org.webrtc.MediaStream>?) {
            when (val t = receiver?.track()) {
                is VideoTrack -> listener?.onRemoteVideo(t)
                is AudioTrack -> listener?.onRemoteAudio(t)
                else -> Unit
            }
        }

        override fun onTrack(transceiver: RtpTransceiver?) {
            when (val t = transceiver?.receiver?.track()) {
                is VideoTrack -> listener?.onRemoteVideo(t)
                is AudioTrack -> listener?.onRemoteAudio(t)
                else -> Unit
            }
        }

        override fun onConnectionChange(state: PeerConnection.PeerConnectionState?) {
            when (state) {
                PeerConnection.PeerConnectionState.CONNECTED -> listener?.onState("ready")
                PeerConnection.PeerConnectionState.FAILED ->
                    listener?.onError("Bağlantı kurulamadı — aktarma yolu deneniyor olabilir")
                PeerConnection.PeerConnectionState.DISCONNECTED ->
                    listener?.onError("Bağlantı koptu")
                else -> Unit
            }
        }
    }

    // ---------------- ICE toplama ----------------

    /**
     * ICE adayi toplama bitene kadar bekler.
     * `engine.js:waitGathering` ile ayni: ilk adaydan 350 ms sonra SDP gider,
     * kalan adaylar trickle ile sinyale tasinir.
     */
    private suspend fun waitGathering(timeoutMs: Long = 8_000L) {
        firstCandidateSeen = false
        val latch = java.util.concurrent.CountDownLatch(1)
        gatheringLatch = latch
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && !latch.await(200, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            if (firstCandidateSeen) {
                latch.await(350, java.util.concurrent.TimeUnit.MILLISECONDS)
                break
            }
        }
        gatheringLatch = null
    }

    // ---------------- teklif / cevap ----------------

    /** Baglatan taraf: davet kodu uretir. */
    suspend fun start(): String = withContext(Dispatchers.Default) {
        val p = createPc()
        pc = p
        val init = DataChannel.Init().also { it.ordered = true }
        channel = p.createDataChannel(CHANNEL, init)?.also { bindChannel(it) }

        val offer = createOfferSdp(p)
        setLocalSdp(p, SessionDescription(SessionDescription.Type.OFFER, offer.description))
        waitGathering()
        SdpCode.encode(p.localDescription?.description ?: offer.description, deviceName, sessionId, "O")
    }

    /** Katilan taraf: davet kodunu isler, cevap kodu dondurur. */
    suspend fun acceptOffer(code: String): String = withContext(Dispatchers.Default) {
        val dec = SdpCode.decode(code)
        sessionId = dec.sessionId.ifEmpty { sessionId }
        peerName = dec.name

        val p = createPc()
        pc = p
        setRemoteSdp(p, SessionDescription(SessionDescription.Type.OFFER, dec.sdp))
        flushRemoteIces()
        val answer = createAnswerSdp(p)
        setLocalSdp(p, SessionDescription(SessionDescription.Type.ANSWER, answer.description))
        waitGathering()
        SdpCode.encode(p.localDescription?.description ?: answer.description, deviceName, sessionId, "A")
    }

    /** Baglatan taraf: cevap kodunu uygular. */
    suspend fun acceptAnswer(code: String) = withContext(Dispatchers.Default) {
        val dec = SdpCode.decode(code)
        if (dec.kind != "A") throw IllegalArgumentException("Bu kod cevap kodu değil")
        val p = pc ?: throw IllegalStateException("Bağlantı yok")
        setRemoteSdp(p, SessionDescription(SessionDescription.Type.ANSWER, dec.sdp))
        flushRemoteIces()
    }

    fun addRemoteCandidate(mid: String?, mline: Int?, cand: String?) {
        if (cand.isNullOrEmpty()) return
        val p = pc ?: return
        val ice = IceCandidate(cand, mline ?: 0, mid ?: "")
        if (p.remoteDescription == null) {
            if (pendingRemoteIces.size < 200) pendingRemoteIces.add(ice)
            return
        }
        runCatching { p.addIceCandidate(ice) }
    }

    private fun flushRemoteIces() {
        if (pc?.remoteDescription == null || pendingRemoteIces.isEmpty()) return
        val q = ArrayList(pendingRemoteIces)
        pendingRemoteIces.clear()
        q.forEach { runCatching { pc?.addIceCandidate(it) } }
    }

    // ---------------- veri kanali ----------------

    private fun bindChannel(ch: DataChannel) {
        channel = ch
        ch.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit

            override fun onStateChange() {
                when (ch.state()) {
                    DataChannel.State.OPEN -> sendHello()
                    DataChannel.State.CLOSED -> if (!closed) {
                        closed = true
                        listener?.onState("closed")
                    }
                    else -> Unit
                }
            }

            override fun onMessage(buffer: DataChannel.Buffer?) {
                if (buffer == null) return
                val data = buffer.data
                val bytes = ByteArray(data.remaining())
                data.get(bytes)
                if (buffer.binary) {
                    files.onBinaryChunk(bytes)
                } else {
                    val env = runCatching {
                        gson.fromJson(String(bytes, Charsets.UTF_8), WireEnvelope::class.java)
                    }.getOrNull()
                    if (env != null) onEnvelope(env)
                }
            }
        })
    }

    private fun sendHello() {
        send(WireEnvelope(
            type = WireEnvelope.HELLO,
            sid = sessionId,
            name = deviceName,
            hash = Primitives.sha256Hex(password)
        ))
    }

    private fun onEnvelope(env: WireEnvelope) {
        when (env.type) {
            WireEnvelope.HELLO -> {
                if (env.sid != sessionId) {
                    listener?.onError("Oturum kimliği uyuşmuyor")
                    close(); return
                }
                if (env.hash != Primitives.sha256Hex(password)) {
                    listener?.onError("Oturum şifresi uyuşmuyor")
                    close(); return
                }
                peerName = env.name.orEmpty().ifEmpty { "Karşı taraf" }
                peerReady = true
                listener?.onReady(peerName)
            }

            WireEnvelope.TEXT ->
                listener?.onText(env.text.orEmpty(), env.ts ?: System.currentTimeMillis())

            WireEnvelope.SDP -> handleSdp(env)

            WireEnvelope.ICE -> addRemoteCandidate(env.mid, env.mline, env.cand)

            WireEnvelope.VIDEO_STOP -> listener?.onVideoStopped()

            WireEnvelope.CTRL_REQ -> listener?.onControlRequest()
            WireEnvelope.CTRL_OK -> listener?.onControlGranted()
            WireEnvelope.CTRL_DENY -> listener?.onControlDenied()
            WireEnvelope.CTRL_STOP -> listener?.onControlStopped()

            WireEnvelope.CTRL -> env.ev?.let { listener?.onControl(it) }

            WireEnvelope.CLIP -> listener?.onClipboard(env.text.orEmpty())

            WireEnvelope.MIC -> listener?.onMicToggle(env.enabled == true)

            WireEnvelope.BYE -> {
                listener?.onBye(env.reason.orEmpty())
                close()
            }

            WireEnvelope.FILE_META, WireEnvelope.FILE_DONE, WireEnvelope.FILE_CANCEL ->
                files.onTextEnvelope(env)

            else -> Unit
        }
    }

    private fun handleSdp(env: WireEnvelope) {
        val p = pc ?: return
        val type = env.sdpType ?: return
        val sdp = env.sdp ?: return
        scope.launch(Dispatchers.Default) {
            runCatching {
                when (type) {
                    "offer" -> {
                        setRemoteSdp(p, SessionDescription(SessionDescription.Type.OFFER, sdp))
                        flushRemoteIces()
                        val answer = createAnswerSdp(p)
                        setLocalSdp(p, SessionDescription(SessionDescription.Type.ANSWER, answer.description))
                        waitGathering()
                        send(WireEnvelope(type = WireEnvelope.SDP, sdpType = "answer", sdp = p.localDescription?.description))
                    }

                    "answer" -> {
                        setRemoteSdp(p, SessionDescription(SessionDescription.Type.ANSWER, sdp))
                        flushRemoteIces()
                    }
                }
            }.onFailure { listener?.onError(it.message ?: "SDP hatası") }
        }
    }

    /** Izinli durumda yeniden pazarlik (medya eklendi/kaldirildiginda). */
    fun negotiate() {
        val p = pc ?: return
        val ch = channel ?: return
        if (ch.state() != DataChannel.State.OPEN || makingOffer || closed) return
        makingOffer = true
        scope.launch(Dispatchers.Default) {
            runCatching {
                val offer = createOfferSdp(p)
                setLocalSdp(p, SessionDescription(SessionDescription.Type.OFFER, offer.description))
                waitGathering()
                send(WireEnvelope(type = WireEnvelope.SDP, sdpType = "offer", sdp = p.localDescription?.description))
            }.onFailure { listener?.onError(it.message ?: "Pazarlık hatası") }
            makingOffer = false
        }
    }

    // ---------------- gonderim ----------------

    private fun send(env: WireEnvelope): Boolean {
        val ch = channel ?: return false
        if (ch.state() != DataChannel.State.OPEN) return false
        return try {
            ch.send(DataChannel.Buffer(ByteBuffer.wrap(gson.toJson(env).toByteArray(Charsets.UTF_8)), false))
            true
        } catch (e: Exception) {
            Log.w(TAG, "Gonderim hatasi: ${e.message}")
            false
        }
    }

    private fun sendBinary(bytes: ByteArray): Boolean {
        val ch = channel ?: return false
        if (ch.state() != DataChannel.State.OPEN) return false
        return try {
            ch.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), true))
            true
        } catch (e: Exception) {
            false
        }
    }

    // ---------------- dis API ----------------

    fun sendText(text: String): Boolean =
        send(WireEnvelope(type = WireEnvelope.TEXT, text = text, ts = System.currentTimeMillis()))

    suspend fun sendFile(file: java.io.File, mime: String): String =
        /* Kayit (markSending) artik motorun icinde, gonderim BASLARKEN yapiliyor. */
        files.sendFile(file, mime) { sendBinary(it) }

    fun cancelFileSend(fid: String) {
        files.cancelSend(fid)
        send(WireEnvelope(type = WireEnvelope.FILE_CANCEL, fid = fid))
    }

    fun requestControl(): Boolean = send(WireEnvelope(type = WireEnvelope.CTRL_REQ))
    fun grantControl(): Boolean = send(WireEnvelope(type = WireEnvelope.CTRL_OK))
    fun denyControl(): Boolean = send(WireEnvelope(type = WireEnvelope.CTRL_DENY))
    fun stopControl(): Boolean = send(WireEnvelope(type = WireEnvelope.CTRL_STOP))

    fun sendControl(payload: ControlPayload): Boolean =
        send(WireEnvelope(type = WireEnvelope.CTRL, ev = payload))

    fun sendClipboard(text: String): Boolean = send(WireEnvelope(type = WireEnvelope.CLIP, text = text))

    fun sendMicState(enabled: Boolean): Boolean {
        localAudioTrack?.setEnabled(enabled)
        return send(WireEnvelope(type = WireEnvelope.MIC, enabled = enabled))
    }

    // ---------------- medya ----------------

    fun newSurfaceTextureHelper(): SurfaceTextureHelper =
        SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)

    /**
     * Android ekran paylasimini (MediaProjection) ekler.
     * @param videoSource `factory.createVideoSource(false)` ile uretilmis kaynak
     *
     * Not: WebRTC'de `VideoSource.capturerObserver` SALT OKUNURDUR; kareler
     * `ScreenCapturer(mediaProjection, videoSource.capturerObserver)` ile
     * bu observer'a aktilir. Bu fonksiyon yalnizca track'i ekler ve yeniden
     * pazarligi baslatir — capturer kurulumu cagiran tarafta yapilir.
     */
    fun addScreenShareTrack(
        videoSource: VideoSource,
        helper: SurfaceTextureHelper,
        width: Int = 1280,
        height: Int = 720
    ) {
        val p = pc ?: return
        val f = ensureFactory()
        videoSource.setIsScreencast(true)
        helper.setTextureSize(width, height)
        val track = f.createVideoTrack("makay-screen", videoSource)
        localVideoTrack = track
        p.addTrack(track, listOf(STREAM_ID))
        negotiate()
    }

    /** Mikrofon akisini ekler. */
    fun addMicrophoneTrack(audioSource: AudioSource) {
        val p = pc ?: return
        val track = ensureFactory().createAudioTrack("makay-mic", audioSource)
        localAudioTrack = track
        p.addTrack(track, listOf(STREAM_ID))
        negotiate()
    }

    fun removeVideoTrack() {
        val p = pc ?: return
        p.senders.filter {
            it.track()?.kind() == MediaStreamTrack.VIDEO_TRACK_KIND
        }.forEach { runCatching { p.removeTrack(it) } }
        negotiate()
    }

    fun stopVideoShare() {
        send(WireEnvelope(type = WireEnvelope.VIDEO_STOP))
        removeVideoTrack()
    }

    fun createVideoSource(): VideoSource = ensureFactory().createVideoSource(false)

    fun createAudioSource(): AudioSource =
        ensureFactory().createAudioSource(MediaConstraints())

    fun close(reason: String = "") {
        if (closed) return
        closed = true
        runCatching {
            channel?.send(
                DataChannel.Buffer(
                    ByteBuffer.wrap(gson.toJson(WireEnvelope(type = WireEnvelope.BYE, reason = reason)).toByteArray(Charsets.UTF_8)),
                    false
                )
            )
        }
        runCatching { channel?.close() }
        runCatching { pc?.close() }
        runCatching { pc?.dispose() }
        runCatching { factory?.dispose() }
        runCatching { audioDeviceModule?.release() }
        runCatching { eglBase.release() }
        channel = null
        pc = null
        factory = null
        listener?.onState("closed")
    }

    /** Ekranda gosterilecek uzak video renderer icin EglBase. */
    fun eglContext(): EglBase.Context = eglBase.eglBaseContext

    companion object {
        private const val TAG = "MakayPeer"
        const val CHANNEL = "makay"
        private const val STREAM_ID = "MAKAY-STREAM"
    }
}

// ---------------- SDP suspend yardimcilari ----------------

private fun mc(vararg pairs: Pair<String, String>): MediaConstraints =
    MediaConstraints().apply { pairs.forEach { (k, v) -> mandatory.add(MediaConstraints.KeyValuePair(k, v)) } }

private suspend fun createOfferSdp(p: PeerConnection): SessionDescription =
    suspendCancellableCoroutine { cont ->
        p.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) { if (sdp != null) cont.resume(sdp) }
            override fun onCreateFailure(e: String?) {
                if (cont.isActive) cont.resumeWith(Result.failure(IllegalStateException(e ?: "Offer hatası")))
            }
            override fun onSetSuccess() = Unit
            override fun onSetFailure(e: String?) {
                if (cont.isActive) cont.resumeWith(Result.failure(IllegalStateException(e ?: "Offer hatası")))
            }
        }, mc("OfferToReceiveAudio" to "true", "OfferToReceiveVideo" to "true"))
    }

private suspend fun createAnswerSdp(p: PeerConnection): SessionDescription =
    suspendCancellableCoroutine { cont ->
        p.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) { if (sdp != null) cont.resume(sdp) }
            override fun onCreateFailure(e: String?) {
                if (cont.isActive) cont.resumeWith(Result.failure(IllegalStateException(e ?: "Answer hatası")))
            }
            override fun onSetSuccess() = Unit
            override fun onSetFailure(e: String?) {
                if (cont.isActive) cont.resumeWith(Result.failure(IllegalStateException(e ?: "Answer hatası")))
            }
        }, mc("OfferToReceiveAudio" to "true", "OfferToReceiveVideo" to "true"))
    }

private suspend fun setLocalSdp(p: PeerConnection, sdp: SessionDescription) =
    suspendCancellableCoroutine { cont ->
        p.setLocalDescription(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) = Unit
            override fun onCreateFailure(e: String?) {
                if (cont.isActive) cont.resumeWith(Result.failure(IllegalStateException(e ?: "setLocal onCreateFailure")))
            }
            override fun onSetSuccess() { if (cont.isActive) cont.resume(Unit) }
            override fun onSetFailure(e: String?) {
                if (cont.isActive) cont.resumeWith(Result.failure(IllegalStateException(e ?: "setLocal başarısız")))
            }
        }, sdp)
    }

private suspend fun setRemoteSdp(p: PeerConnection, sdp: SessionDescription) =
    suspendCancellableCoroutine { cont ->
        p.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) = Unit
            override fun onCreateFailure(e: String?) {
                if (cont.isActive) cont.resumeWith(Result.failure(IllegalStateException(e ?: "setRemote onCreateFailure")))
            }
            override fun onSetSuccess() { if (cont.isActive) cont.resume(Unit) }
            override fun onSetFailure(e: String?) {
                if (cont.isActive) cont.resumeWith(Result.failure(IllegalStateException(e ?: "setRemote başarısız")))
            }
        }, sdp)
    }