package com.makay.remote.rtc

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.makay.remote.core.IceConfig
import com.makay.remote.core.Primitives
import com.makay.remote.core.SdpCode
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
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
    /** close() dispose tamamlandi mi — CLOSED erken closed=true yapmasin diye ayri. */
    @Volatile private var disposed = false
    @Volatile private var makingOffer = false
    /** acceptOffer/start sirasinda artar; close ile stale continuation engellenir. */
    @Volatile private var setupGen = 0
    private val pendingRemoteIces = ArrayList<IceCandidate>()

    // ---------------- tani sayaclari ----------------
    // Baglanti "Baglaniyor" ekraninda takildiginda ne oldugunu gormek icin.
    // ConnectingScreen bunlari okuyup ekranda gosterir; boylece "hangi adimda
    // kaldi?" sorusu ekrandan cevaplanir.
    @Volatile var localCandidateCount: Int = 0
        private set
    @Volatile var remoteCandidateCount: Int = 0
        private set
    @Volatile var pendingRemoteCount: Int = 0
        private set
    @Volatile var iceState: String = "new"
        private set
    @Volatile var connState: String = "new"
        private set
    @Volatile var channelState: String = "new"
        private set
    @Volatile var lastDiag: String = ""
        private set

    /**
     * HELLO el sikismasi durumu.
     *
     * Baglanti "Baglaniyor" ekraninda takildiginda bunlar gorulur: ICE ve
     * veri kanali bile acik olabilir, ancak HELLO el sikismasi tamamlanmadiysa
     * karsi tarafta `onReady` hic calismaz ve oturum hic "Bagli" durumuna gecmez.
     */
    @Volatile var helloSent: Boolean = false
        private set
    @Volatile var helloReceived: Boolean = false
        private set
    @Volatile private var helloPumpStarted = false
    /** UI listener cagrilari icin ana looper (Compose). */
    private val mainHandler = Handler(Looper.getMainLooper())
    private val sendLock = Any()

    /** Baglanti ekranda gosterilen tek satirlik tani ozeti. */
    fun diagText(): String =
        "ICE=$iceState · bağlantı=$connState · kanal=$channelState · " +
            "yerel aday=$localCandidateCount · uzak aday=$remoteCandidateCount" +
            (if (pendingRemoteCount > 0) " (bekleyen $pendingRemoteCount)" else "") +
            " · hello " +
            (if (helloSent) "gönderildi" else "GÖNDERİLMEDİ") +
            " / " +
            (if (helloReceived) "karşı geldi" else "karşı HELLO YOK") +
            (if (lastDiag.isNotEmpty()) " · $lastDiag" else "")

    data class LinkStats(
        val rttMs: Int = -1,
        val bitrateKbps: Int = -1,
        val packetsLost: Int = 0,
        val candidateType: String = "",
        /** good | ok | poor | unknown */
        val quality: String = "unknown"
    )

    @Volatile var lastLinkStats: LinkStats = LinkStats()
        private set

    /**
     * WebRTC getStats — RTT / bitrate / kayıp. Adaptive ve baglanti rozeti icin.
     * RTC thread'de calisir; sonuc main'de lastLinkStats'e yazilir.
     */
    fun sampleLinkStats(onDone: ((LinkStats) -> Unit)? = null) {
        val p = pc
        if (p == null) {
            val empty = LinkStats()
            lastLinkStats = empty
            onDone?.let { mainHandler.post { it(empty) } }
            return
        }
        RtcEngine.post {
            p.getStats { report ->
                var rtt = -1
                var bitrate = -1
                var lost = 0
                var cand = ""
                report.statsMap.values.forEach { s ->
                    val t = s.type
                    val m = s.members
                    when {
                        t == "candidate-pair" && m["state"] == "succeeded" -> {
                            val r = m["currentRoundTripTime"] as? Number
                            if (r != null) rtt = (r.toDouble() * 1000).toInt()
                            val avail = m["availableOutgoingBitrate"] as? Number
                            if (avail != null && bitrate < 0) bitrate = (avail.toDouble() / 1000).toInt()
                        }
                        t == "inbound-rtp" && m["kind"] == "video" -> {
                            val br = m["bytesReceived"] as? Number
                            /* anlik bitrate yoksa framesDecoded ile yetin */
                            val pl = m["packetsLost"] as? Number
                            if (pl != null) lost += pl.toInt()
                        }
                        t == "outbound-rtp" && m["kind"] == "video" -> {
                            val pl = m["packetsLost"] as? Number
                            if (pl != null) lost += pl.toInt()
                            val tbr = m["targetBitrate"] as? Number
                            if (tbr != null) bitrate = (tbr.toDouble() / 1000).toInt()
                        }
                        t == "remote-candidate" || t == "local-candidate" -> {
                            val ct = m["candidateType"] as? String
                            if (!ct.isNullOrBlank() && cand.isEmpty()) cand = ct
                        }
                    }
                }
                val q = when {
                    rtt < 0 && bitrate < 0 -> "unknown"
                    rtt in 0..120 && lost < 8 -> "good"
                    rtt in 0..280 && lost < 40 -> "ok"
                    else -> "poor"
                }
                val stats = LinkStats(rtt, bitrate, lost, cand, q)
                lastLinkStats = stats
                mainHandler.post { onDone?.invoke(stats) }
            }
        }
    }

    /** Cikis video bitrate tavanı (adaptive). null = sinir kaldir. */
    fun setVideoMaxBitrateBps(maxBps: Int?) {
        RtcEngine.post {
            val p = pc ?: return@post
            p.senders.forEach { sender ->
                if (sender.track()?.kind() != MediaStreamTrack.VIDEO_TRACK_KIND) return@forEach
                val params = sender.parameters
                params.encodings?.forEach { enc ->
                    enc.maxBitrateBps = maxBps
                }
                runCatching { sender.parameters = params }
            }
        }
    }

    // ---------------- fabrika (surec boyu paylasilan RtcEngine) ----------------

    private fun ensureFactory(): PeerConnectionFactory = RtcEngine.factory(appContext)

    private fun eglBase(): EglBase = RtcEngine.egl(appContext)

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
            iceState = state?.name?.lowercase() ?: "unknown"
            when (state) {
                PeerConnection.IceConnectionState.FAILED ->
                    notifyListener { onError("Ağ tünelleri kurulamadı — Ayarlar’dan «Yalnızca TURN» deneyin") }
                PeerConnection.IceConnectionState.DISCONNECTED ->
                    notifyListener { onError("Bağlantı koptu — yeniden bağlanılıyor") }
                PeerConnection.IceConnectionState.COMPLETED,
                PeerConnection.IceConnectionState.CHECKING -> notifyListener { onState("connecting") }
                else -> Unit
            }
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit

        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) = Unit

        override fun onIceCandidate(candidate: IceCandidate?) {
            if (candidate == null || closed) return
            localCandidateCount++
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
            stream?.videoTracks?.firstOrNull()?.let { t -> notifyListener { onRemoteVideo(t) } }
            stream?.audioTracks?.firstOrNull()?.let { t -> notifyListener { onRemoteAudio(t) } }
        }

        override fun onRemoveStream(stream: org.webrtc.MediaStream?) {
            notifyListener { onRemoteVideo(null) }
        }

        override fun onDataChannel(ch: DataChannel?) {
            if (closed || ch == null) return
            RtcEngine.post { if (!closed) bindChannel(ch) }
        }

        override fun onRenegotiationNeeded() {
            if (!closed) negotiate()
        }

        override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out org.webrtc.MediaStream>?) {
            when (val t = receiver?.track()) {
                is VideoTrack -> notifyListener { onRemoteVideo(t) }
                is AudioTrack -> notifyListener { onRemoteAudio(t) }
                else -> Unit
            }
        }

        override fun onTrack(transceiver: RtpTransceiver?) {
            when (val t = transceiver?.receiver?.track()) {
                is VideoTrack -> notifyListener { onRemoteVideo(t) }
                is AudioTrack -> notifyListener { onRemoteAudio(t) }
                else -> Unit
            }
        }

        override fun onConnectionChange(state: PeerConnection.PeerConnectionState?) {
            connState = state?.name?.lowercase() ?: "unknown"
            when (state) {
                PeerConnection.PeerConnectionState.CONNECTED -> notifyListener { onState("ready") }
                PeerConnection.PeerConnectionState.FAILED -> {
                    lastDiag = "peer connection failed (ice=$iceState)"
                    notifyListener { onError("Bağlantı kurulamadı — aktarma yolu deneniyor olabilir") }
                }
                PeerConnection.PeerConnectionState.DISCONNECTED ->
                    notifyListener { onError("Bağlantı koptu") }
                else -> Unit
            }
        }
    }

    private fun checkAlive(p: PeerConnection, gen: Int) {
        if (closed || disposed || pc !== p || gen != setupGen) {
            throw IllegalStateException("Bağlantı kurulurken kapatıldı")
        }
    }

    // ---------------- ICE toplama ----------------

    /**
     * ICE adayi toplama bitene kadar bekler.
     *
     * MASASTU `engine.js:waitGathering` ile AYNI SEMANTIK: donmeden once
     * SDP'de EN AZ BIR `a=candidate:` satiri oldugunu DOGRULAR.
     *
     * Eski surum yalnizca `onIceCandidate` cagrisinin gelip gelmedigine
     * bakiyordu (`firstCandidateSeen`). Ancak libwebrtc'in
     * `getLocalDescription()` snapshot'i aday toplandigi anda hemen
     * guncellenmeyebilir; boylece cevap SDP'si ADAYSIZ gidebiliyordu.
     * Baglanti boylece tamamen trickle ICE'e baglaniyordu; trickle'da adaylar
     * kaybolursa (yineleme filtresi hatasi) baglanti HIC kurulamiyordu.
     * Boylece "Onaylandi -> Baglaniyor" asamasinda takilip kaliyordu.
     */
    private suspend fun waitGathering(timeoutMs: Long = 8_000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && !sdpHasCandidate()) {
            delay(50)
        }
        /* Ilk aday girdikten sonra birkac aday daha yerlessin (350 ms). */
        if (sdpHasCandidate()) delay(350)
    }

    /** Yerel SDP'nin su an en az bir ICE adayi icerip icermedigini soyler. */
    private fun sdpHasCandidate(): Boolean {
        val sdp = pc?.localDescription?.description ?: return false
        return sdp.contains("a=candidate:")
    }

    // ---------------- teklif / cevap ----------------

    /** Baglatan taraf: davet kodu uretir. */
    suspend fun start(): String = RtcEngine.withRtc {
        val gen = ++setupGen
        CrashLog.breadcrumb("start createPc")
        val p = createPc()
        pc = p
        val init = DataChannel.Init().also { it.ordered = true }
        channel = p.createDataChannel(CHANNEL, init)?.also { bindChannel(it) }

        val offer = createOfferSdp(p)
        checkAlive(p, gen)
        setLocalSdp(p, SessionDescription(SessionDescription.Type.OFFER, offer.description))
        checkAlive(p, gen)
        waitGathering()
        checkAlive(p, gen)
        SdpCode.encode(p.localDescription?.description ?: offer.description, deviceName, sessionId, "O")
    }

    /**
     * Katilan taraf: davet kodunu isler, cevap kodu dondurur.
     * Tum PeerConnection / DataChannel native islemleri tek RTC thread'de
     * (RtcEngine) — Izin Ver sonrasi ani kapanmayi onlemek icin.
     */
    suspend fun acceptOffer(code: String): String {
        val dec = withContext(Dispatchers.Default) { SdpCode.decode(code) }
        return RtcEngine.withRtc {
            val gen = ++setupGen
            CrashLog.breadcrumb("acceptOffer createPc sid=${dec.sessionId.take(8)}")
            sessionId = dec.sessionId.ifEmpty { sessionId }
            peerName = dec.name

            val p = createPc()
            pc = p
            CrashLog.breadcrumb("acceptOffer setRemote")
            setRemoteSdp(p, SessionDescription(SessionDescription.Type.OFFER, dec.sdp))
            checkAlive(p, gen)
            flushRemoteIces()
            CrashLog.breadcrumb("acceptOffer createAnswer")
            val answer = createAnswerSdp(p)
            checkAlive(p, gen)
            setLocalSdp(p, SessionDescription(SessionDescription.Type.ANSWER, answer.description))
            checkAlive(p, gen)
            waitGathering()
            checkAlive(p, gen)
            CrashLog.breadcrumb("acceptOffer answer hazir")
            val local = p.localDescription?.description ?: answer.description
            withContext(Dispatchers.Default) {
                SdpCode.encode(local, deviceName, sessionId, "A")
            }
        }
    }

    /** Baglatan taraf: cevap kodunu uygular. */
    suspend fun acceptAnswer(code: String) {
        val dec = withContext(Dispatchers.Default) { SdpCode.decode(code) }
        if (dec.kind != "A") throw IllegalArgumentException("Bu kod cevap kodu değil")
        RtcEngine.withRtc {
            val p = pc ?: throw IllegalStateException("Bağlantı yok")
            val gen = setupGen
            setRemoteSdp(p, SessionDescription(SessionDescription.Type.ANSWER, dec.sdp))
            checkAlive(p, gen)
            flushRemoteIces()
        }
    }

    fun addRemoteCandidate(mid: String?, mline: Int?, cand: String?) {
        if (cand.isNullOrEmpty()) return
        // org.webrtc.IceCandidate yapicisi (sdpMid, sdpMLineIndex, sdp) sirasindadir.
        // Daha once argumanlar ters verildigi icin `sdp` alani "0" gibi gecersiz bir
        // deger aliyor ve addIceCandidate adayi sessizce reddediyordu; sonuc olarak
        // hicbir uzak ICE adayi uygulanamiyor ve baglanti (ICE) hic kurulamiyordu.
        val ice = IceCandidate(mid ?: "", mline ?: 0, cand)
        /* PeerConnection henuz yoksa (IceInbox flush, createPeer hemen sonrasi)
           adaylari tamponla — eski kod `pc ?: return` ile bunlari DUSURUYORDU
           ve `uzak aday=0` ile tekliften onceki trickle tamamen kayboluyordu. */
        val p = pc
        if (p == null) {
            synchronized(pendingRemoteIces) {
                if (pendingRemoteIces.size < 200) pendingRemoteIces.add(ice)
                pendingRemoteCount = pendingRemoteIces.size
            }
            return
        }
        RtcEngine.post {
            val conn = pc
            if (conn == null || conn.remoteDescription == null) {
                synchronized(pendingRemoteIces) {
                    if (pendingRemoteIces.size < 200) pendingRemoteIces.add(ice)
                    pendingRemoteCount = pendingRemoteIces.size
                }
                return@post
            }
            runCatching { conn.addIceCandidate(ice) }
                .onSuccess { remoteCandidateCount++ }
                .onFailure { lastDiag = "addIceCandidate hatasi: ${it.message}" }
        }
    }

    /** RTC thread'de cagrilmali. */
    private fun flushRemoteIces() {
        val q = synchronized(pendingRemoteIces) {
            if (pc?.remoteDescription == null || pendingRemoteIces.isEmpty()) return
            val copy = ArrayList(pendingRemoteIces)
            pendingRemoteIces.clear()
            pendingRemoteCount = 0
            copy
        }
        q.forEach {
            runCatching { pc?.addIceCandidate(it) }
                .onSuccess { remoteCandidateCount++ }
                .onFailure { lastDiag = "flush addIceCandidate hatasi: ${it.message}" }
        }
    }

    // ---------------- veri kanali ----------------

    private fun bindChannel(ch: DataChannel) {
        channel = ch
        ch.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit

            override fun onStateChange() {
                channelState = ch.state()?.name?.lowercase() ?: "unknown"
                when (ch.state()) {
                    DataChannel.State.OPEN -> RtcEngine.post { maybeSendHello(ch) }
                    /* Eski hata: closed=true yapip dispose ATLAMAK — sonraki
                       PeerConnection native crash uretiyordu. Tam close(). */
                    DataChannel.State.CLOSED -> if (!closed && !disposed) {
                        close("channel-closed")
                    }
                    else -> Unit
                }
            }

            override fun onMessage(buffer: DataChannel.Buffer?) {
                if (buffer == null) return
                val data = buffer.data
                val bytes = ByteArray(data.remaining())
                data.get(bytes)
                /*
                 * KRITIK (Android ↔ Electron): bazi yiginlar metin zarflarini
                 * (hello/sdp/ctrl…) binary kare olarak iletir. Eski kod
                 * `buffer.binary == true` ise dogrudan dosya parcasi sayiyordu;
                 * HELLO sessizce kayboluyor, ICE/kanal acik kalmasına ragmen
                 * oturum hic "Bagli" olmuyordu. Once JSON zarf dene; degilse
                 * dosya parcasi olarak isle.
                 */
                if (tryHandleWireBytes(bytes)) return
                if (buffer.binary) {
                    files.onBinaryChunk(bytes)
                }
            }
        })
        /*
         * KRITIK: katilan (answerer) tarafta kanal, karsinin olusturdugu
         * `onDataChannel` uzerinden gelir ve bu sirada kanal ZATEN acik olabilir.
         * libwebrtc boyle bir kanal icin `onStateChange(OPEN)` olayini geriye
         * donuk CAGRILMAZ. Ayrica Electron↔Android'de ilk `send()` bazen
         * OPEN iken bile false doner — HELLO pompasi yeniden dener.
         */
        maybeSendHello(ch)
        startHelloPump(ch)
    }

    /** Kanal aciksa HELLO gondermeyi dener (basarisizsa pompa yeniden dener). */
    private fun maybeSendHello(ch: DataChannel) {
        if (closed || helloSent) return
        if (ch.state() != DataChannel.State.OPEN) return
        if (sendHello()) {
            helloSent = true
            lastDiag = "HELLO gönderildi"
        } else {
            lastDiag = "HELLO gönderilemedi (kanal=${ch.state()})"
        }
    }

    /**
     * OPEN gorunup `send()` false dondugunde HELLO'yu periyodik yeniden dener.
     * KRITIK: DataChannel.send YALNIZCA RtcEngine thread'inde — baska thread'den
     * native cagri sureci dusuruyordu (uygulama ani kapanma).
     */
    private fun startHelloPump(ch: DataChannel) {
        if (helloPumpStarted || closed) return
        helloPumpStarted = true
        scope.launch(RtcEngine.dispatcher) {
            var attempt = 0
            while (!closed && !helloSent && attempt < 40) {
                delay(250)
                if (closed || helloSent) break
                attempt++
                val state = runCatching { ch.state() }.getOrNull()
                if (state != DataChannel.State.OPEN) {
                    lastDiag = "HELLO bekliyor (kanal=$state, deneme=$attempt)"
                    continue
                }
                if (sendHello()) {
                    helloSent = true
                    lastDiag = "HELLO gönderildi (deneme=$attempt)"
                    Log.i(TAG, lastDiag)
                    CrashLog.breadcrumb(lastDiag)
                    break
                }
                lastDiag = "HELLO gönderilemedi (kanal=$state, deneme=$attempt)"
            }
        }
    }

    /** Veri kanalina HELLO gonderir; gercek gonderim sonucunu dondurur. */
    private fun sendHello(): Boolean {
        return send(WireEnvelope(
            type = WireEnvelope.HELLO,
            sid = sessionId,
            name = deviceName,
            hash = Primitives.sha256Hex(password)
        ))
    }

    private fun onEnvelope(env: WireEnvelope) {
        when (env.type) {
            WireEnvelope.HELLO -> {
                /* Windows HELLO'yu birden fazla kez yeniden gonderebilir — ikinci
                   kez onReady/UI cagrisi yapma (Compose yeniden olusum / crash). */
                if (helloReceived || peerReady) return
                if (env.sid != sessionId) {
                    lastDiag = "HELLO sid uyuşmadı (beklenen=$sessionId gelen=${env.sid})"
                    CrashLog.breadcrumb(lastDiag)
                    notifyListener { onError("Oturum kimliği uyuşmuyor") }
                    /* soft: once UI, sonra teardown — send/dispose yarisi azaltilir */
                    scope.launch {
                        delay(50)
                        close("hello-sid")
                    }
                    return
                }
                val localHash = Primitives.sha256Hex(password)
                if (env.hash != localHash) {
                    lastDiag = "HELLO şifre uyuşmadı (beklenen=${localHash.take(12)}… gelen=${env.hash?.take(12) ?: "boş"}…)"
                    CrashLog.breadcrumb(lastDiag)
                    notifyListener { onError("Oturum şifresi uyuşmuyor") }
                    scope.launch {
                        delay(50)
                        close("hello-hash")
                    }
                    return
                }
                helloReceived = true
                peerName = env.name.orEmpty().ifEmpty { "Karşı taraf" }
                peerReady = true
                CrashLog.breadcrumb("HELLO OK peer=$peerName")
                /* Kendi HELLO'muz henuz gitmediyse hemen dene (Windows ready bekler). */
                channel?.let { maybeSendHello(it) }
                notifyListener { onReady(peerName) }
            }

            WireEnvelope.TEXT -> {
                val text = env.text.orEmpty()
                val ts = env.ts ?: System.currentTimeMillis()
                notifyListener { onText(text, ts) }
            }

            WireEnvelope.SDP -> handleSdp(env)

            WireEnvelope.ICE -> addRemoteCandidate(env.mid, env.mline, env.cand)

            WireEnvelope.VIDEO_STOP -> notifyListener { onVideoStopped() }

            WireEnvelope.CTRL_REQ -> notifyListener { onControlRequest() }
            WireEnvelope.CTRL_OK -> notifyListener { onControlGranted() }
            WireEnvelope.CTRL_DENY -> notifyListener { onControlDenied() }
            WireEnvelope.CTRL_STOP -> notifyListener { onControlStopped() }

            WireEnvelope.CTRL -> env.ev?.let { ev -> notifyListener { onControl(ev) } }

            WireEnvelope.CLIP -> notifyListener { onClipboard(env.text.orEmpty()) }

            WireEnvelope.MIC -> {
                val en = env.enabled == true
                notifyListener { onMicToggle(en) }
            }

            WireEnvelope.BYE -> {
                val reason = env.reason.orEmpty()
                notifyListener { onBye(reason) }
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
        scope.launch(RtcEngine.dispatcher) {
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
            }.onFailure { notifyListener { onError(it.message ?: "SDP hatası") } }
        }
    }

    /** Izinli durumda yeniden pazarlik (medya eklendi/kaldirildiginda). */
    fun negotiate() {
        val p = pc ?: return
        val ch = channel ?: return
        if (runCatching { ch.state() }.getOrNull() != DataChannel.State.OPEN || makingOffer || closed) return
        makingOffer = true
        scope.launch(RtcEngine.dispatcher) {
            runCatching {
                val offer = createOfferSdp(p)
                setLocalSdp(p, SessionDescription(SessionDescription.Type.OFFER, offer.description))
                waitGathering()
                send(WireEnvelope(type = WireEnvelope.SDP, sdpType = "offer", sdp = p.localDescription?.description))
            }.onFailure { e ->
                notifyListener { onError(e.message ?: "Pazarlık hatası") }
            }
            makingOffer = false
        }
    }

    // ---------------- gonderim ----------------

    /** Gelen baytlari WireEnvelope olarak yorumlar; degilse false. */
    private fun tryHandleWireBytes(bytes: ByteArray): Boolean {
        val env = WireMessageCodec.tryParseEnvelope(bytes) ?: return false
        onEnvelope(env)
        return true
    }

    /** Listener cagrilarini ana thread'e tasir (Compose / UI guvenligi). */
    private fun notifyListener(block: Listener.() -> Unit) {
        val l = listener ?: return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runCatching { l.block() }
        } else {
            mainHandler.post { runCatching { l.block() } }
        }
    }

    /**
     * Wire zarfini DataChannel'a yazar.
     *
     * - HELLO/SDP: once binary (Electron↔Android uyumu).
     * - ctrl / ctrl-req / clip / text: once METIN karesi — Windows `engine.js`
     *   `channel.send(string)` ile ayni; bazi Chromium yollarinda binary ctrl
     *   dosya parcasi sanilip dusebiliyordu.
     * - Native send her zaman RtcEngine thread'inde.
     */
    private fun send(env: WireEnvelope): Boolean {
        val bytes = gson.toJson(env).toByteArray(Charsets.UTF_8)
        val preferText = when (env.type) {
            WireEnvelope.CTRL, WireEnvelope.CTRL_REQ, WireEnvelope.CTRL_OK,
            WireEnvelope.CTRL_DENY, WireEnvelope.CTRL_STOP,
            WireEnvelope.TEXT, WireEnvelope.CLIP, WireEnvelope.MIC -> true
            else -> false
        }
        return doSendBytes(bytes, preferBinary = !preferText)
    }

    private fun sendBinary(bytes: ByteArray): Boolean = doSendBytes(bytes, preferBinary = true)

    /**
     * DataChannel gönderimi — UI/ANR güvenli.
     * Ana iş parçacığında `runOnRtcBlocking` YOK: kuyruğa alıp RTC thread'de boşaltır.
     * Kanal açıksa hemen true döner (dokunuş yolu bloklanmaz).
     */
    private fun doSendBytes(bytes: ByteArray, preferBinary: Boolean): Boolean {
        if (channel == null || closed) return false
        if (RtcEngine.isOnRtcThread()) {
            return sendBytesOnRtc(bytes, preferBinary)
        }
        val ch = channel ?: return false
        val state = runCatching { ch.state() }.getOrNull() ?: return false
        if (state != DataChannel.State.OPEN) return false
        sendQueue.offer(QueuedSend(bytes.copyOf(), preferBinary))
        scheduleSendDrain()
        return true
    }

    private data class QueuedSend(val bytes: ByteArray, val preferBinary: Boolean)

    private val sendQueue = java.util.concurrent.ConcurrentLinkedQueue<QueuedSend>()
    private val drainScheduled = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun scheduleSendDrain() {
        if (!drainScheduled.compareAndSet(false, true)) return
        RtcEngine.post {
            try {
                while (true) {
                    val item = sendQueue.poll() ?: break
                    sendBytesOnRtc(item.bytes, item.preferBinary)
                }
            } finally {
                drainScheduled.set(false)
                if (sendQueue.isNotEmpty()) scheduleSendDrain()
            }
        }
    }

    private fun sendBytesOnRtc(bytes: ByteArray, preferBinary: Boolean): Boolean {
        return try {
            val ch = channel ?: return false
            synchronized(sendLock) {
                if (closed) return false
                val state = runCatching { ch.state() }.getOrNull() ?: return false
                if (state != DataChannel.State.OPEN) return false
                val order = if (preferBinary) listOf(true, false) else listOf(false, true)
                for (asBinary in order) {
                    if (trySendBytes(ch, bytes, asBinary)) return true
                }
                false
            }
        } catch (t: Throwable) {
            CrashLog.write("doSendBytes: ${t.message}")
            false
        }
    }

    private fun trySendBytes(ch: DataChannel, bytes: ByteArray, asBinary: Boolean): Boolean {
        return try {
            val buf = ByteBuffer.wrap(bytes.copyOf())
            val ok = ch.send(DataChannel.Buffer(buf, asBinary))
            if (!ok) Log.w(TAG, "DataChannel.send false (binary=$asBinary, n=${bytes.size})")
            ok
        } catch (t: Throwable) {
            Log.e(TAG, "Gonderim hatasi (binary=$asBinary): ${t.message}", t)
            CrashLog.write("trySendBytes: ${t.message}")
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
        SurfaceTextureHelper.create("CaptureThread", eglBase().eglBaseContext)

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
        RtcEngine.post {
            val p = pc ?: return@post
            val f = ensureFactory()
            videoSource.setIsScreencast(true)
            helper.setTextureSize(width, height)
            val track = f.createVideoTrack("makay-screen", videoSource)
            localVideoTrack = track
            p.addTrack(track, listOf(STREAM_ID))
            negotiate()
        }
    }

    /** Mikrofon akisini ekler. */
    fun addMicrophoneTrack(audioSource: AudioSource) {
        RtcEngine.post {
            val p = pc ?: return@post
            val track = ensureFactory().createAudioTrack("makay-mic", audioSource)
            localAudioTrack = track
            p.addTrack(track, listOf(STREAM_ID))
            negotiate()
        }
    }

    /** Mikrofon track'i zaten kurulu mu? (ilk acilista olusturulur) */
    fun hasLocalMic(): Boolean = localAudioTrack != null

    /**
     * Mikrofon track'ini (varsa) olusturur ve pazara girer.
     *
     * Yol `addScreenShareTrack` ile BIREBIR aynidir (`addTrack` ->
     * `negotiate()`); bu yol uygulamada zaten ekran paylasiminda calisiyor.
     * Track hic silinmez — yalnizca `setEnabled` ile susturulur, boylece
     * yeniden pazarlik her acilista tekrarlanmaz.
     *
     * `RECORD_AUDIO` izni cagiran tarafta alinmis olmalidir.
     */
    fun ensureMicrophone() {
        if (localAudioTrack != null) return
        if (pc == null) return
        addMicrophoneTrack(createAudioSource())
    }

    fun removeVideoTrack() {
        RtcEngine.post {
            val p = pc ?: return@post
            p.senders.filter {
                it.track()?.kind() == MediaStreamTrack.VIDEO_TRACK_KIND
            }.forEach { runCatching { p.removeTrack(it) } }
            negotiate()
        }
    }

    fun stopVideoShare() {
        send(WireEnvelope(type = WireEnvelope.VIDEO_STOP))
        removeVideoTrack()
    }

    fun createVideoSource(): VideoSource = ensureFactory().createVideoSource(false)

    fun createAudioSource(): AudioSource =
        ensureFactory().createAudioSource(MediaConstraints())

    fun close(reason: String = "") {
        if (disposed) return
        closed = true
        setupGen++
        helloPumpStarted = false
        CrashLog.breadcrumb("close reason=$reason")
        RtcEngine.post {
            if (disposed) return@post
            disposed = true
            runCatching {
                val ch = channel
                if (ch != null && ch.state() == DataChannel.State.OPEN) {
                    val bytes = gson.toJson(WireEnvelope(type = WireEnvelope.BYE, reason = reason))
                        .toByteArray(Charsets.UTF_8)
                    trySendBytes(ch, bytes, asBinary = true)
                }
            }
            runCatching { channel?.unregisterObserver() }
            runCatching { channel?.close() }
            runCatching { localVideoTrack?.dispose() }
            runCatching { localAudioTrack?.dispose() }
            localVideoTrack = null
            localAudioTrack = null
            runCatching { pc?.close() }
            runCatching { pc?.dispose() }
            /* RtcEngine (factory/egl/audio) SUREC BOYU yasatir — dispose ETME. */
            channel = null
            pc = null
            notifyListener { onState("closed") }
        }
    }

    /** Ekranda gosterilecek uzak video renderer icin EglBase (paylasilan). */
    fun eglContext(): EglBase.Context? =
        if (closed) null
        else runCatching { eglBase().eglBaseContext }.getOrNull()

    companion object {
        private const val TAG = "MakayPeer"
        const val CHANNEL = "makay"
        private const val STREAM_ID = "MAKAY-STREAM"
    }
}

// ---------------- SDP suspend yardimcilari ----------------

/**
 * Unified Plan'da OfferToReceive* (Plan B) bazi libwebrtc surumlerinde
 * native crash / bos m-line uretiyordu — bos constraints kullan.
 */
private val emptyMc = MediaConstraints()

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
        }, emptyMc)
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
        }, emptyMc)
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