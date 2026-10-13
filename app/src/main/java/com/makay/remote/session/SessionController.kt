package com.makay.remote.session

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.google.gson.Gson
import com.makay.remote.core.IceConfig
import com.makay.remote.core.Primitives
import com.makay.remote.core.RemoteGuard
import com.makay.remote.data.SettingsStore
import com.makay.remote.rtc.ControlPayload
import com.makay.remote.rtc.FileTransferEngine
import com.makay.remote.rtc.PeerBridge
import com.makay.remote.rtc.WireEnvelope
import com.makay.remote.signal.MqttSignalClient
import com.makay.remote.signal.SignalEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.webrtc.AudioTrack
import org.webrtc.EglBase
import org.webrtc.VideoTrack
import java.io.File

/**
 * MAKAY Remote — oturum orkestratörü.
 *
 * Masaustu `apps/remote/app.js` icindeki oda akisinin (createRoom / join /
 * allowJoin / handleRoomMsg) birebir Kotlin karsiligidir ve MQTT zarflari
 * Windows `electron/cloud-signal-bridge.js` ile AYNIDIR:
 *
 *   Oluşturan (OFFERER, ekranini paylasan):
 *     oda uret -> MQTT'ye abone ol -> davet metni goster
 *     <- {t:'join-request', name}   (her 2 sn'de bir)
 *     kullanicinin onayi ile: peer.start() -> {t:'offer', code}
 *     -> {t:'answer', code} gelince acceptAnswer
 *
 *   Katilan (ANSWERER, teknisyen):
 *     kod+sifre -> MQTT'ye abone ol -> {t:'join-request', name} (2 sn'de bir)
 *     <- {t:'offer', code} gelince peer.acceptOffer -> {t:'answer', code} gonder
 *
 *   Iki taraf da: {t:'ice', mid, mline, cand, ufrag} karsilidir.
 *
 * NOT: Peer, offerer'da ONAY ANINDA yaratilir (masaustuyla ayni): erken
 * uretilen ICE adaylari NAT'ta bayatlar.
 */
class SessionController(
    private val appContext: Context,
    private val settings: SettingsStore,
    parentScope: CoroutineScope
) {

    // ---------------- durum ----------------

    sealed class State {
        object Idle : State()

        /** Oda acildi, davet paylasiliyor, join-request bekleniyor (OFFERER). */
        data class Hosting(val room: String, val password: String, val inviteText: String) : State()

        /** join-request gonderiliyor, offer bekleniyor (ANSWERER). */
        data class Joining(val room: String, val password: String) : State()

        /** Karsi taraftan join-request geldi, kullanicinin onayi bekleniyor. */
        data class AwaitingApproval(val requesterName: String) : State()

        /** offer/answer takasi + ICE kuruluyor. */
        data class Connecting(val asHost: Boolean) : State()

        /** hello dogrulandi, veri kanali acik. */
        data class Connected(val peerName: String, val asHost: Boolean) : State()

        data class Failed(val message: String) : State()
    }

    data class ChatMessage(val text: String, val mine: Boolean, val ts: Long)

    /** Tek seferlik olaylar (toast / kontrol istemi / dosya). */
    sealed class Event {
        data class Toast(val message: String, val kind: String = "info") : Event()
        /** Karsi tarafin bize kontrol istedigi (host tarafinda onay dialogu). */
        data class ControlRequest(val from: String) : Event()
        /** Karsi tarafa gonderilen dosya tamamlandi. */
        data class FileSent(val name: String) : Event()
        /** Gelen dosya indirildi. */
        data class FileReceived(val name: String, val uri: Uri?) : Event()
    }

    /** Teknisyen tarafindaki kontrol izni durumu. */
    enum class ControlState { NONE, REQUESTED, GRANTED, DENIED }

    // ---------------- akislar ----------------

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 32)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    private val _chat = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chat: StateFlow<List<ChatMessage>> = _chat.asStateFlow()

    private val _controlState = MutableStateFlow(ControlState.NONE)
    val controlState: StateFlow<ControlState> = _controlState.asStateFlow()

    private val _pendingControlRequest = MutableStateFlow<String?>(null)
    val pendingControlRequest: StateFlow<String?> = _pendingControlRequest.asStateFlow()

    private val _remoteVideo = MutableStateFlow<VideoTrack?>(null)
    val remoteVideo: StateFlow<VideoTrack?> = _remoteVideo.asStateFlow()

    private val _remoteAudio = MutableStateFlow<AudioTrack?>(null)
    val remoteAudio: StateFlow<AudioTrack?> = _remoteAudio.asStateFlow()

    // ---------------- ic durum ----------------

    private val gson = Gson()
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob())

    private var mqtt: MqttSignalClient? = null
    private var peer: PeerBridge? = null
    private var joinRetryJob: Job? = null
    private var connectTimeoutJob: Job? = null

    private var asHost = false
    private var room: String = ""
    private var password: String = ""
    private var lastJoinRequestAt = 0L
    private var intentionalClose = false

    /** Uzak videoyu gosteren renderer icin Egl baglami. */
    fun eglContext(): EglBase.Context? = peer?.eglContext()

    // ---------------- oda kurma (OFFERER) ----------------

    /** Yeni oda acar ve davet metnini dondurur. */
    suspend fun host(): String {
        reset(keepMqtt = false)
        val pwd = Primitives.generateSessionPassword()
        val code = Primitives.generateRoom()
        asHost = true
        room = code
        password = pwd

        val invite = Primitives.buildInviteText(code, pwd)
        _state.value = State.Hosting(code, pwd, invite)

        val client = MqttSignalClient()
        mqtt = client
        try {
            client.subscribe(
                code, pwd,
                MqttSignalClient.Listener { onSignal(it) },
                MqttSignalClient.CloseListener { onMqttClosed() }
            )
        } catch (e: Exception) {
            Log.w(TAG, "Eslesme agina ulasilamadi: ${e.message}")
            reset(keepMqtt = false)
            _state.value = State.Failed("Eşleşme ağına ulaşılamadı — internet bağlantınızı kontrol edin")
            return ""
        }

        settings.lastRoom = code
        settings.lastPassword = pwd
        _state.value = State.Hosting(code, pwd, invite)
        return code
    }

    /** Kullanici bir join-request'i onayladiginda cagrilir. */
    fun allowJoin() {
        if (!asHost || _state.value !is State.AwaitingApproval) return
        val existing = peer
        if (existing != null) {
            emitToast("Bağlantı zaten kuruluyor — bekleyin", "info")
            return
        }
        _state.value = State.Connecting(asHost = true)
        startConnectTimeout()

        val p = createPeer(PeerBridge.Role.OFFERER)
        scope.launch(Dispatchers.Default) {
            try {
                val offerCode = p.start()
                mqtt?.publish(SignalEnvelope(type = SignalEnvelope.TYPE_OFFER, code = offerCode))
            } catch (e: Exception) {
                Log.w(TAG, "Offer gonderilemedi: ${e.message}")
                _state.value = State.Failed("Bağlantı teklifi oluşturulamadı: ${e.message}")
            }
        }
    }

    /** Host, gelen istegi reddettiginde cagrilir. */
    fun denyJoin() {
        if (!asHost) return
        val hosting = _state.value as? State.Hosting
        _state.value = State.AwaitingApproval("")
        scope.launch {
            runCatching { mqtt?.publish(SignalEnvelope(type = SignalEnvelope.TYPE_JOIN_DENIED)) }
            if (hosting != null) {
                _state.value = hosting
            } else {
                _state.value = State.Hosting(room, password, Primitives.buildInviteText(room, password))
            }
        }
    }

    // ---------------- odaya katilma (ANSWERER) ----------------

    /**
     * Davet metni (ya da 8 haneli kimlik) + sifre ile odaya katilir.
     * Metin birebir Windows `parseInvitePaste` kurallariyla cozulur.
     */
    suspend fun join(inviteText: String, passwordInput: String) {
        reset(keepMqtt = false)
        val parsed = parseInvite(inviteText)
        val code = parsed.code
        val pwd = parsed.password.ifEmpty { passwordInput.trim() }

        if (code.length != 8) {
            _state.value = State.Failed("8 haneli partner kimliğini girin")
            return
        }
        if (pwd.isEmpty()) {
            _state.value = State.Failed("Şifreyi girin")
            return
        }

        asHost = false
        room = code
        password = pwd
        _state.value = State.Joining(code, pwd)

        val client = MqttSignalClient()
        mqtt = client
        try {
            client.subscribe(
                code, pwd,
                MqttSignalClient.Listener { onSignal(it) },
                MqttSignalClient.CloseListener { onMqttClosed() }
            )
        } catch (e: Exception) {
            Log.w(TAG, "Eslesme agina ulasilamadi: ${e.message}")
            reset(keepMqtt = false)
            _state.value = State.Failed("Eşleşme ağına ulaşılamadı — internet bağlantınızı kontrol edin")
            return
        }

        settings.lastRoom = code
        settings.lastPassword = pwd
        startJoinRetries()
    }

    /** Masaustu `startJoinRetries`: peer yokken her 2 sn'de bir join-request. */
    private fun startJoinRetries() {
        joinRetryJob?.cancel()
        joinRetryJob = scope.launch {
            while (isActive) {
                val st = _state.value
                if (st !is State.Joining) break
                if (peer != null) break
                runCatching {
                    mqtt?.publish(
                        SignalEnvelope(
                            type = SignalEnvelope.TYPE_JOIN_REQUEST,
                            name = settings.deviceName
                        )
                    )
                }
                delay(JOIN_RETRY_MS)
            }
        }
    }

    // ---------------- sinyal isleme ----------------

    private fun onSignal(env: SignalEnvelope) {
        when (env.type) {
            SignalEnvelope.TYPE_JOIN_REQUEST -> {
                // Masaustu: yalnizca OFFERER, peer yokken ve 1200 ms throttle ile
                if (!asHost) return
                if (peer != null) return
                val now = System.currentTimeMillis()
                if (now - lastJoinRequestAt < JOIN_THROTTLE_MS) return
                lastJoinRequestAt = now
                val st = _state.value
                if (st is State.Hosting || st is State.AwaitingApproval) {
                    _state.value = State.AwaitingApproval(env.name ?: "Bilgisayar")
                }
            }

            SignalEnvelope.TYPE_JOIN_DENIED -> {
                joinRetryJob?.cancel()
                reset(keepMqtt = true)
                emitToast("Bağlantı isteği reddedildi", "warning")
            }

            SignalEnvelope.TYPE_OFFER -> {
                // Host kendi rolünde offer almaz; peer zaten varsa yoksay
                if (asHost) return
                if (peer != null) return
                val code = env.code ?: return
                joinRetryJob?.cancel()
                clearConnectTimeout()
                _state.value = State.Connecting(asHost = false)
                startConnectTimeout()

                val p = createPeer(PeerBridge.Role.ANSWERER)
                scope.launch(Dispatchers.Default) {
                    try {
                        val answerCode = p.acceptOffer(code)
                        mqtt?.publish(SignalEnvelope(type = SignalEnvelope.TYPE_ANSWER, code = answerCode))
                    } catch (e: Exception) {
                        Log.w(TAG, "Offer islenemedi: ${e.message}")
                        _state.value = State.Failed("Bağlantı teklifi işlenemedi: ${e.message}")
                    }
                }
            }

            SignalEnvelope.TYPE_ANSWER -> {
                if (!asHost) return
                val code = env.code ?: return
                val p = peer ?: return
                scope.launch(Dispatchers.Default) {
                    runCatching { p.acceptAnswer(code) }
                        .onFailure { _state.value = State.Failed("Cevap işlenemedi: ${it.message}") }
                }
            }

            SignalEnvelope.TYPE_ICE -> {
                peer?.addRemoteCandidate(env.mid, env.mline, env.cand)
            }

            SignalEnvelope.TYPE_BYE -> {
                val wasConnected = _state.value is State.Connected
                reset(keepMqtt = true)
                if (wasConnected) emitToast("Karşı taraf bağlantıyı kapattı", "warning")
            }
        }
    }

    private fun onMqttClosed() {
        if (intentionalClose) return
        val st = _state.value
        if (st is State.Idle || st is State.Failed) return
        Log.w(TAG, "Eslesme agi koptu")
        emitToast("Eşleşme ağı koptu — yeniden bağlanın", "warning")
    }

    // ---------------- WebRTC koprusu ----------------

    private fun createPeer(role: PeerBridge.Role): PeerBridge {
        val p = PeerBridge(
            appContext = appContext,
            role = role,
            deviceName = settings.deviceName,
            password = password,
            iceServers = buildIceServers(),
            scope = scope,
            sendSignal = { raw -> relayIceSignal(raw) }
        )
        p.listener = peerListener
        peer = p
        com.makay.remote.rtc.PeerRegistry.current = p
        return p
    }

    /**
     * PeerBridge'ten gelen sinyal (yalnizca ICE) MQTT'ye aktarilir.
     * Masaustu `createPeer.sendSignal`: `obj.t !== 'ice'` ise yok sayilir,
     * ayni JSON dogrudan yayinlanir. Hata yutulur (`.catch(function(){})`).
     */
    private suspend fun relayIceSignal(raw: String) {
        val w = runCatching { gson.fromJson(raw, WireEnvelope::class.java) }.getOrNull() ?: return
        if (w.type != WireEnvelope.ICE) return
        runCatching {
            mqtt?.publish(
                SignalEnvelope(
                    type = SignalEnvelope.TYPE_ICE,
                    mid = w.mid,
                    mline = w.mline,
                    cand = w.cand,
                    ufrag = w.ufrag
                )
            )
        }.onFailure { Log.w(TAG, "ICE yayinlanamadi: ${it.message}") }
    }

    private fun buildIceServers(): List<IceConfig.Server> {
        val servers = IceConfig.parse(
            turnUrl = settings.turnUrl.ifEmpty { null },
            turnUsername = settings.turnUser.ifEmpty { null },
            turnPassword = settings.turnPass.ifEmpty { null },
            includeDefaultTurn = settings.publicTurnFallback
        )
        // "Yalnizca TURN" kipi: yalnizca relay sunuculari birakilir
        if (settings.preferRelay) {
            val relayOnly = servers.filter { s -> s.urls.any { it.startsWith("turn", ignoreCase = true) } }
            if (relayOnly.isNotEmpty()) return relayOnly
        }
        return servers
    }

    private val peerListener = object : PeerBridge.Listener {
        override fun onState(state: String) {
            if (state == "closed" && !intentionalClose) {
                reset(keepMqtt = true)
            }
        }

        override fun onReady(peerName: String) {
            clearConnectTimeout()
            joinRetryJob?.cancel()
            _controlState.value = ControlState.NONE
            _state.value = State.Connected(peerName, asHost)
        }

        override fun onText(text: String, ts: Long) {
            _chat.update { it + ChatMessage(text, mine = false, ts = ts) }
        }

        override fun onControlRequest() {
            _pendingControlRequest.value = peer?.peerName ?: "Karşı taraf"
            _events.tryEmit(Event.ControlRequest(peer?.peerName ?: "Karşı taraf"))
        }

        override fun onControlGranted() {
            _controlState.value = ControlState.GRANTED
            emitToast("Kontrol izni verildi", "success")
        }

        override fun onControlDenied() {
            _controlState.value = ControlState.DENIED
            emitToast("Kontrol izni verilmedi", "warning")
        }

        override fun onControlStopped() {
            if (_controlState.value == ControlState.GRANTED) {
                _controlState.value = ControlState.NONE
                emitToast("Kontrol sonlandırıldı", "info")
            }
        }

        override fun onClipboard(text: String) {
            // Panoya yapistirma UI katmaninda yapilir (etkinlik akisi gerekir)
        }

        override fun onRemoteVideo(track: VideoTrack?) {
            _remoteVideo.value = track
        }

        override fun onRemoteAudio(track: AudioTrack?) {
            _remoteAudio.value = track
        }

        override fun onVideoStopped() {
            _remoteVideo.value = null
        }

        override fun onBye(reason: String) {
            reset(keepMqtt = true)
            emitToast(if (reason.isEmpty()) "Bağlantı kapatıldı" else "Bağlantı kapatıldı: $reason", "warning")
        }

        override fun onError(message: String) {
            emitToast(message, "error")
        }

        override fun onFileProgress(p: FileTransferEngine.Progress) = Unit

        override fun onFileComplete(file: FileTransferEngine.Received) {
            val uri = saveToDownloads(file.name, file.mime, file.bytes)
            _events.tryEmit(Event.FileReceived(file.name, uri))
        }

        override fun onFileCancel(fid: String) {
            emitToast("Dosya aktarımı iptal edildi", "warning")
        }
    }

    // ---------------- eylemler ----------------

    fun sendChat(text: String): Boolean {
        val p = peer ?: return false
        val t = text.trim()
        if (t.isEmpty()) return false
        if (!p.sendText(t)) {
            emitToast("Mesaj gönderilemedi — bağlantı yok", "error")
            return false
        }
        _chat.update { it + ChatMessage(t, mine = true, ts = System.currentTimeMillis()) }
        return true
    }

    /** Dosya gonderir; basarili ise fid dondurur. */
    suspend fun sendFile(file: File, mime: String): String {
        val p = peer ?: return ""
        if (_state.value !is State.Connected) {
            emitToast("Bağlantı yok — dosya gönderilemedi", "error")
            return ""
        }
        val fid = p.sendFile(file, mime)
        if (fid.isNotEmpty()) _events.tryEmit(Event.FileSent(file.name))
        return fid
    }

    fun requestControl() {
        val p = peer ?: return
        if (p.requestControl()) _controlState.value = ControlState.REQUESTED
    }

    fun grantControl() {
        _pendingControlRequest.value = null
        peer?.grantControl()
    }

    fun denyControl() {
        _pendingControlRequest.value = null
        peer?.denyControl()
    }

    fun stopControl() {
        if (peer?.stopControl() == true) _controlState.value = ControlState.NONE
    }

    /**
     * Kontrol olayini gonderir. Cikis hizi RemoteGuard ile TEMIZLENIR
     * (tel siniri): gecersiz olay dusurulur, koordinatlar sinirlanir.
     */
    fun sendControl(payload: ControlPayload): Boolean {
        val p = peer ?: return false
        if (_controlState.value != ControlState.GRANTED) return false
        val clean = sanitize(payload) ?: return false
        return p.sendControl(clean)
    }

    private fun sanitize(p: ControlPayload): ControlPayload? {
        val ev = RemoteGuard.sanitizeControl(p.type, p.x, p.y, p.b, p.d, p.k) ?: return null
        return when (ev) {
            is RemoteGuard.ControlEvent.Move ->
                ControlPayload(ControlPayload.MOUSE_MOVE, x = ev.x, y = ev.y)
            is RemoteGuard.ControlEvent.Button ->
                ControlPayload(
                    if (ev.down) ControlPayload.MOUSE_DOWN else ControlPayload.MOUSE_UP,
                    x = ev.x, y = ev.y, b = ev.button
                )
            is RemoteGuard.ControlEvent.Wheel ->
                ControlPayload(ControlPayload.WHEEL, d = ev.delta)
            is RemoteGuard.ControlEvent.Key ->
                ControlPayload(if (ev.down) ControlPayload.KEY_DOWN else ControlPayload.KEY_UP, k = ev.name)
        }
    }

    /** Oturumu kapatir (oda dahil). */
    fun disconnect() {
        intentionalClose = true
        val hostingRoom = room
        scope.launch {
            if (asHost && hostingRoom.isNotEmpty()) {
                runCatching { mqtt?.publish(SignalEnvelope(type = SignalEnvelope.TYPE_BYE)) }
            }
            reset(keepMqtt = false)
            settings.clearSession()
            intentionalClose = false
        }
    }

    /** Hata ekranindan ana ekrana donerken cagrilir. */
    fun backToIdle() {
        intentionalClose = true
        reset(keepMqtt = false)
        intentionalClose = false
        _state.value = State.Idle
    }

    /** Uygulama kapanirken (Activity.onDestroy) cagrilir. */
    fun shutdown() {
        intentionalClose = true
        joinRetryJob?.cancel()
        clearConnectTimeout()
        scope.launch {
            runCatching { peer?.close("shutdown") }
            runCatching { mqtt?.close() }
        }
    }

    // ---------------- yardimcilar ----------------

    private fun reset(keepMqtt: Boolean) {
        joinRetryJob?.cancel()
        joinRetryJob = null
        clearConnectTimeout()
        peer?.let { runCatching { it.close("reset") } }
        peer = null
        com.makay.remote.rtc.PeerRegistry.current = null
        if (!keepMqtt) {
            mqtt?.close()
            mqtt = null
            room = ""
            password = ""
            asHost = false
        }
        lastJoinRequestAt = 0L
        _chat.value = emptyList()
        _controlState.value = ControlState.NONE
        _pendingControlRequest.value = null
        _remoteVideo.value = null
        _remoteAudio.value = null
        if (_state.value !is State.Failed) _state.value = State.Idle
    }

    private fun startConnectTimeout() {
        clearConnectTimeout()
        connectTimeoutJob = scope.launch {
            delay(CONNECT_TIMEOUT_MS)
            val st = _state.value
            if (st is State.Connecting) {
                // Masaustu ile ayni uyari; TURN yeniden denemesi su an yapilmaz
                emitToast("Bağlantı uzun sürüyor — aktarma yolu deneniyor olabilir", "warning")
            }
        }
    }

    private fun clearConnectTimeout() {
        connectTimeoutJob?.cancel()
        connectTimeoutJob = null
    }

    private fun emitToast(message: String, kind: String) {
        _events.tryEmit(Event.Toast(message, kind))
    }

    private fun saveToDownloads(name: String, mime: String, bytes: ByteArray): Uri? =
        runCatching {
            val safe = name.replace('/', '_').replace('\\', '_').take(180)
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, safe)
                    put(MediaStore.Downloads.MIME_TYPE, mime.ifEmpty { "application/octet-stream" })
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = appContext.contentResolver
                    .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
                appContext.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                appContext.contentResolver.update(uri, values, null, null)
                uri
            } else {
                @Suppress("DEPRECATION")
                val dir = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS
                )
                dir.mkdirs()
                var f = File(dir, safe)
                var i = 1
                while (f.exists()) {
                    f = File(dir, "($i) $safe")
                    i++
                }
                f.writeBytes(bytes)
                Uri.fromFile(f)
            }
        }.getOrNull()

    // ---------------- davet cozme ----------------

    data class ParsedInvite(val code: String, val password: String)

    /**
     * Windows `parseInvitePaste` ile AYNI kurallar:
     *  1) "Kimlik: XXXX XXXX"
     *  2) "Bağlantı/Davet Kodu: ..."
     *  3) duz "XXXX XXXX" ya da "XXXXXXXX"
     *  sifre: "Şifre:" / "Oturum Şifresi:" satiri
     */
    fun parseInvite(raw: String): ParsedInvite {
        val text = raw.trim()
        var code = ""

        KIMLIK_RE.find(text)?.let { code = it.groupValues[1] }
        if (code.isEmpty()) KOD_RE.find(text)?.let { code = it.groupValues[1] }
        if (code.isEmpty()) BARE_SPACED_RE.find(text)?.let { code = it.groupValues[1] }
        if (code.isEmpty()) BARE_RE.find(text)?.let { code = it.groupValues[1] }

        val clean = code.filter { it.isLetterOrDigit() }.uppercase().take(8)
        val pwd = PASSWORD_RE.find(text)?.groupValues?.get(1)?.trim().orEmpty()
        return ParsedInvite(clean, pwd)
    }

    companion object {
        private const val TAG = "MakaySession"
        private const val JOIN_RETRY_MS = 2000L
        private const val JOIN_THROTTLE_MS = 1200L
        private const val CONNECT_TIMEOUT_MS = 35_000L

        // Windows parseInvitePaste regexleri ile ayni anlam
        private val KIMLIK_RE = Regex("Kimlik\\s*:?\\s*([0-9A-Za-z][0-9A-Za-z\\s\\-]{2,14})", RegexOption.IGNORE_CASE)
        private val KOD_RE = Regex("(?:Bağlantı|Davet)\\s*Kodu\\s*:?\\s*([0-9A-Za-z][0-9A-Za-z\\s\\-]{2,14})", RegexOption.IGNORE_CASE)
        private val BARE_SPACED_RE = Regex("\\b([0-9A-Z]{4}\\s*[0-9A-Z]{4})\\b", RegexOption.IGNORE_CASE)
        private val BARE_RE = Regex("\\b([0-9A-Z]{8})\\b")
        private val PASSWORD_RE = Regex(
            "(?:Sifre|Şifre|Oturum\\s*[SsŞş]ifresi)\\s*:?\\s*([^\\r\\n]+)",
            RegexOption.IGNORE_CASE
        )
    }
}
