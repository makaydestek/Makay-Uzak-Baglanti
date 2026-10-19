package com.makay.remote.session

import android.content.ContentValues
import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.makay.remote.core.IceConfig
import com.makay.remote.core.InviteParser
import com.makay.remote.core.Primitives
import com.makay.remote.core.RemoteGuard
import com.makay.remote.core.WireJson
import com.makay.remote.data.SettingsStore
import com.makay.remote.rtc.ControlPayload
import com.makay.remote.rtc.FileTransferEngine
import com.makay.remote.rtc.PeerBridge
import com.makay.remote.rtc.WireEnvelope
import com.makay.remote.signal.HttpSignalClient
import com.makay.remote.signal.IceInbox
import com.makay.remote.signal.MqttSignalClient
import com.makay.remote.signal.SignalChannel
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.makay.remote.rtc.CrashLog
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
 *     oda uret -> sinyal kanalina abone ol -> davet metni goster
 *     <- {t:'join-request', name}   (her 2 sn'de bir)
 *     kullanicinin onayi ile: peer.start() -> {t:'offer', code}
 *     -> {t:'answer', code} gelince acceptAnswer
 *
 *   Katilan (ANSWERER, teknisyen):
 *     kod+sifre -> sinyal kanalina abone ol -> {t:'join-request', name} (2 sn'de bir)
 *     <- {t:'offer', code} gelince peer.acceptOffer -> {t:'answer', code} gonder
 *
 *   Iki taraf da: {t:'ice', mid, mline, cand, ufrag} karsilidir.
 *
 *   Sinyal kanali (bkz. `signal/SignalChannel.kt`):
 *     - Davet metninde "Sinyal: <adres>" SATIRI VARSA HTTP (Cloudflare
 *       tüneli / LAN sinyal sunucusu) — [HttpSignalClient]; mobil aglarda
 *       halka MQTT broker'larina erisilemese de calisir.
 *     - YOKSA (yalnizca kimlik+sifre) bulut eslesme (MQTT) — [MqttSignalClient].
 *     - Android'in KURDUGU odalar her zaman MQTT'dir (Android'de HTTP
 *       sinyal sunucusu yoktur); masaustu taraf bulut koprusuyle katilir.
 *
 * NOT: Peer, offerer'da ONAY ANINDA yaratilir (masaustuyla ayni): erken
 * uretilen ICE adaylari NAT'ta bayatlar.
 */
class SessionController(
    private val appContext: Context,
    val settings: SettingsStore,
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
        data class AwaitingApproval(
            val requesterName: String,
            val deviceId: String = ""
        ) : State()

        /** offer/answer takasi + ICE kuruluyor. */
        data class Connecting(val asHost: Boolean) : State()

        /** hello dogrulandi, veri kanali acik. */
        data class Connected(val peerName: String, val asHost: Boolean) : State()

        data class Failed(val message: String) : State()
    }

    data class ChatMessage(val text: String, val mine: Boolean, val ts: Long)

    /** Tek seferlik olaylar (toast / dosya). */
    sealed class Event {
        data class Toast(val message: String, val kind: String = "info") : Event()
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

    /**
     * Baglanti sirasindaki WebRTC tani ozeti (tek satir).
     *
     * Baglanti "Baglaniyor" ekraninda takildiginda kullaniciya hangi adimda
     * kaldigini gosterir ve destek icin ekran goruntusu olarak yeterlidir:
     * ICE durumu, yerel/uzak aday sayilari ve veri kanali durumu.
     */
    private val _diagnostics = MutableStateFlow("")
    val diagnostics: StateFlow<String> = _diagnostics.asStateFlow()
    private var diagJob: Job? = null

    /** Bagli oturumda baglanti kalitesi: good|ok|poor|unknown (+RTT metni). */
    data class LinkQuality(val level: String = "unknown", val label: String = "")
    private val _linkQuality = MutableStateFlow(LinkQuality())
    val linkQuality: StateFlow<LinkQuality> = _linkQuality.asStateFlow()
    private var linkJob: Job? = null
    private var adaptiveTier = 0

    /** Onay beklerken kullaniciya gosterilen kisa durum (join-request yayini). */
    private val _joinHint = MutableStateFlow("")
    val joinHint: StateFlow<String> = _joinHint.asStateFlow()

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 32)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    private val _chat = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chat: StateFlow<List<ChatMessage>> = _chat.asStateFlow()

    private val _controlState = MutableStateFlow(ControlState.NONE)
    val controlState: StateFlow<ControlState> = _controlState.asStateFlow()

    private val _remoteVideo = MutableStateFlow<VideoTrack?>(null)
    val remoteVideo: StateFlow<VideoTrack?> = _remoteVideo.asStateFlow()

    private val _remoteAudio = MutableStateFlow<AudioTrack?>(null)
    val remoteAudio: StateFlow<AudioTrack?> = _remoteAudio.asStateFlow()

    /** Karsi taraftan gelen pano metni; UI onay verince yazilir/silinir. */
    private val _inboundClipboard = MutableStateFlow<String?>(null)
    val inboundClipboard: StateFlow<String?> = _inboundClipboard.asStateFlow()

    /** Bu cihazin mikrofon durumu (kendi track'imiz). */
    private val _micState = MutableStateFlow(false)
    val micState: StateFlow<Boolean> = _micState.asStateFlow()

    /** Karsi tarafin (masaustunun) mikrofon durumu — yalnizca bilgi amacli. */
    private val _peerMicState = MutableStateFlow<Boolean?>(null)
    val peerMicState: StateFlow<Boolean?> = _peerMicState.asStateFlow()

    /** Hoparlör yonlendirmesi. Varsayilan acik; hic dokunulmazsa davranis DEGISMES. */
    private val _speakerOn = MutableStateFlow(true)
    val speakerOn: StateFlow<Boolean> = _speakerOn.asStateFlow()

    /** Dosya aktarim ilerlemesi (giden/gelen). */
    private val _fileProgress = MutableStateFlow<FileTransferEngine.Progress?>(null)
    val fileProgress: StateFlow<FileTransferEngine.Progress?> = _fileProgress.asStateFlow()

    // ---------------- ic durum ----------------

    private val gson = WireJson.gson
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob())

    private var signal: SignalChannel? = null
    private var peer: PeerBridge? = null
    private var joinRetryJob: Job? = null
    private var connectTimeoutJob: Job? = null
    private var acceptOfferJob: Job? = null
    private var reconnectJob: Job? = null
    private var softResubJob: Job? = null
    /** MQTT fan-out callback'lerini tek siraya al (offer + bye yarisi). */
    private val signalMutex = Mutex()

    private var asHost = false
    private var room: String = ""
    private var password: String = ""
    private var lastJoinRequestAt = 0L
    private var intentionalClose = false

    /* Yeniden baglanma / otomatik TURN */
    private var reconnectInvite: String = ""
    private var reconnectPassword: String = ""
    private var reconnectAttempts = 0
    private var autoTurnTried = false
    private var connectedOnce = false

    /**
     * TEKLIFTEN ONCE GELEN UZAK ICE ADAYLARI tamponu — bkz. [IceInbox].
     *
     * Masaustu adaylari toplama sirasinda yayar, teklifi toplama bitince
     * gonderir; boylece telefonda yayin sirasi ONCE ICE, SONRA OFFER'dir
     * (canli olcum: Windows'tan gelen 9 adayin tamami tekliften once geldi).
     * Eski kod `peer?.addRemoteCandidate(...)` ile bu adaylari SESSIZCE
     * DUSURUYORDU; simdi tamponlanip peer olusturulunca uygulanir.
     */
    private val pendingIceBeforePeer = IceInbox()

    /** Uzak videoyu gosteren renderer icin Egl baglami. */
    fun eglContext(): EglBase.Context? = peer?.eglContext()

    // ---------------- oda kurma (OFFERER) ----------------

    /** Yeni oda acar ve davet metnini dondurur. */
    suspend fun host(): String {
        reset(keepSignal = false)
        val pwd = Primitives.generateSessionPassword()
        val code = Primitives.generateRoom()
        asHost = true
        room = code
        password = pwd

        val invite = Primitives.buildInviteText(code, pwd)
        _state.value = State.Hosting(code, pwd, invite)

        /* Android oda KURUCU olarak her zaman bulut (MQTT) kullanir —
           Android'de HTTP sinyal sunucusu YOKTUR; masaustu misafirler
           bulut koprusu (cloud-signal-bridge) ile bu odaya katilir. */
        val client = MqttSignalClient(settings.mqttBroker.ifEmpty { null })
        signal = client
        try {
            client.subscribe(
                code, pwd,
                SignalChannel.Listener { enqueueSignal(it) },
                SignalChannel.CloseListener { onSignalClosed() }
            )
        } catch (e: Exception) {
            Log.w(TAG, "Eslesme agina ulasilamadi: ${e.message}")
            reset(keepSignal = false)
            _state.value = State.Failed("Eşleşme ağına ulaşılamadı — internet bağlantınızı kontrol edin")
            return ""
        }

        _state.value = State.Hosting(code, pwd, invite)
        return code
    }

    /**
     * Kullanici bir join-request'i onayladiginda cagrilir.
     * @param trustDevice true ise bu cihaz sonraki baglantilarda otomatik izin alir.
     */
    fun allowJoin(trustDevice: Boolean = false) {
        val awaiting = _state.value as? State.AwaitingApproval ?: return
        if (!asHost) return
        val existing = peer
        if (existing != null) {
            emitToast("Bağlantı zaten kuruluyor — bekleyin", "info")
            return
        }
        if (trustDevice && awaiting.deviceId.isNotBlank()) {
            settings.trustDevice(awaiting.deviceId, awaiting.requesterName)
            emitToast("«${awaiting.requesterName}» güvenli cihaz olarak kaydedildi", "success")
        }
        startOfferAsHost()
    }

    private fun startOfferAsHost() {
        _state.value = State.Connecting(asHost = true)
        startConnectTimeout()
        val p = createPeer(PeerBridge.Role.OFFERER)
        scope.launch(Dispatchers.Default) {
            try {
                val offerCode = p.start()
                signal?.publish(SignalEnvelope(type = SignalEnvelope.TYPE_OFFER, code = offerCode))
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
            runCatching { signal?.publish(SignalEnvelope(type = SignalEnvelope.TYPE_JOIN_DENIED)) }
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
        reset(keepSignal = false)
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

        /* Tansiyon secimi:
           1) Davet metninde "Sinyal: <adres>" satiri VARSA HTTP (Cloudflare
              tüneli / LAN) ile katil — telefonun halka acik MQTT broker'larina
              erisemedigi mobil aglarda da calisir.
           2) Adres YOKSA (yalnizca kimlik+sifre girildiginde) ya da HTTP
              basarisizsa (olmus tünel,404…) bulut eslesme (MQTT) kullanilir —
              masaustunun HTTP odasina acigi yedek kopru dahil iki yontem de
              ayni odayi dinler. */
        var joined = false
        val signalUrl = parsed.signalUrl
        if (signalUrl.isNotEmpty()) {
            val http = HttpSignalClient(signalUrl) { m -> Log.i(TAG, m) }
            try {
                http.subscribe(
                    code, pwd,
                    SignalChannel.Listener { enqueueSignal(it) },
                    SignalChannel.CloseListener { onSignalClosed() }
                )
                signal = http
                joined = true
                Log.i(TAG, "HTTP sinyal adresiyle katildi: $signalUrl")
            } catch (e: Exception) {
                Log.w(TAG, "HTTP sinyal adresine basilamadi ($signalUrl): ${e.message} — MQTT'ye donuluyor")
                runCatching { http.close() }
            }
        }

        if (!joined) {
            val client = MqttSignalClient(settings.mqttBroker.ifEmpty { null })
            try {
                client.subscribe(
                    code, pwd,
                    SignalChannel.Listener { enqueueSignal(it) },
                    SignalChannel.CloseListener { onSignalClosed() }
                )
                signal = client
            } catch (e: Exception) {
                Log.w(TAG, "Eslesme agina ulasilamadi: ${e.message}")
                reset(keepSignal = false)
                _state.value = State.Failed("Eşleşme ağına ulaşılamadı — internet bağlantınızı kontrol edin")
                return
            }
        }

        /* Windows oturum kimligi her seferinde yeni — geçmişe kaydetme */
        settings.clearRecents()
        settings.clearSession()
        reconnectInvite = if (parsed.signalUrl.isNotEmpty())
            "Kimlik: $code\nŞifre: $pwd\nSinyal: ${parsed.signalUrl}"
        else code
        reconnectPassword = pwd
        reconnectAttempts = 0
        autoTurnTried = false
        connectedOnce = false
        startJoinRetries()
    }

    /** Masaustu `startJoinRetries`: peer yokken her 2 sn'de bir join-request. */
    private fun startJoinRetries() {
        joinRetryJob?.cancel()
        joinRetryJob = scope.launch {
            /* Broker aboneliginin yerlesmesi icin kisa bekleme — bazi halka
               acik MQTT'lerde hemen yayinlanan ilk join-request kaybolabiliyor. */
            /* Broker aboneligi + karsi taraf dinlemeye hazir olsun */
            delay(900)
            var sent = 0
            var failures = 0
            while (isActive) {
                val st = _state.value
                if (st !is State.Joining) break
                if (peer != null) break
                val ch = signal
                if (ch == null) {
                    failures++
                    _joinHint.value = "Eşleşme ağı hazır değil…"
                    delay(JOIN_RETRY_MS)
                    continue
                }
                val ok = runCatching {
                    ch.publish(
                        SignalEnvelope(
                            type = SignalEnvelope.TYPE_JOIN_REQUEST,
                            name = settings.deviceName.ifBlank { "Android" },
                            deviceId = settings.deviceId
                        )
                    )
                }.isSuccess
                if (ok) {
                    sent++
                    failures = 0
                    Log.i(TAG, "join-request yayinlandi #$sent room=$room")
                    _joinHint.value = when {
                        sent == 1 -> "Onay isteği gönderildi — Windows’ta «İzin Ver» bekleniyor"
                        sent < 8 -> "Onay isteği yeniden gönderildi ($sent) — Windows’ta pencereyi kontrol edin"
                        else -> "Hâlâ yanıt yok — Windows’ta Bağlantıya Aç açık mı? Kimlik/şifre aynı mı?"
                    }
                } else {
                    failures++
                    Log.w(TAG, "join-request yayinlanamadi (deneme=$failures)")
                    _joinHint.value = "İstek gönderilemedi — internet bağlantısını kontrol edin"
                    if (failures >= 3) {
                        emitToast("Eşleşme ağına yazılamıyor — Wi‑Fi / mobil veriyi kontrol edin", "warning")
                    }
                }
                delay(JOIN_RETRY_MS)
            }
        }
    }

    // ---------------- sinyal isleme ----------------

    private fun enqueueSignal(env: SignalEnvelope) {
        scope.launch {
            signalMutex.withLock { onSignal(env) }
        }
    }

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
                if (st !is State.Hosting && st !is State.AwaitingApproval) return
                val name = env.name ?: "Bilgisayar"
                val did = env.deviceId.orEmpty()
                /* Guvenli cihaz: İzin Ver olmadan dogrudan offer */
                if (did.isNotEmpty() && settings.isTrusted(did)) {
                    emitToast("Güvenli cihaz «$name» — otomatik bağlanıyor", "success")
                    _state.value = State.AwaitingApproval(name, did)
                    startOfferAsHost()
                    return
                }
                _state.value = State.AwaitingApproval(name, did)
            }

            SignalEnvelope.TYPE_JOIN_DENIED -> {
                joinRetryJob?.cancel()
                reset(keepSignal = true)
                emitToast("Bağlantı isteği reddedildi", "warning")
            }

            SignalEnvelope.TYPE_OFFER -> {
                // Host kendi rolünde offer almaz; peer zaten varsa yoksay
                if (asHost) return
                if (peer != null) return
                val code = env.code ?: return
                joinRetryJob?.cancel()
                acceptOfferJob?.cancel()
                _joinHint.value = ""
                clearConnectTimeout()
                _state.value = State.Connecting(asHost = false)
                startConnectTimeout()

                val p = createPeer(PeerBridge.Role.ANSWERER)
                /* Tekliften ONCE gelen uzak adaylari PeerBridge tamponuna ver
                   (pc henuz yokken de saklanir). acceptOffer setRemoteSdp
                   sonrasinda flushRemoteIces() uygular. */
                flushPendingIce(p)
                CrashLog.breadcrumb("OFFER alindi, acceptOffer basliyor")
                acceptOfferJob = scope.launch {
                    try {
                        val answerCode = p.acceptOffer(code)
                        flushPendingIce(p)
                        signal?.publish(SignalEnvelope(type = SignalEnvelope.TYPE_ANSWER, code = answerCode))
                        CrashLog.breadcrumb("ANSWER yayinlandi")
                    } catch (e: Throwable) {
                        Log.e(TAG, "Offer islenemedi", e)
                        CrashLog.write("acceptOffer: ${e.stackTraceToString()}")
                        if (peer === p) {
                            runCatching { p.close("offer-fail") }
                            peer = null
                        }
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
                val p = peer
                if (p != null) {
                    p.addRemoteCandidate(env.mid, env.mline, env.cand)
                } else {
                    // peer henuz yok (tekliften onceki adaylar) — tamponla,
                    // peer olusturulunca uygula. bkz. pendingIceBeforePeer.
                    pendingIceBeforePeer.add(env)
                }
            }

            SignalEnvelope.TYPE_BYE -> {
                val wasConnected = _state.value is State.Connected
                reset(keepSignal = true)
                if (wasConnected) emitToast("Karşı taraf bağlantıyı kapattı", "warning")
            }
        }
    }

    private fun onSignalClosed() {
        if (intentionalClose) return
        val st = _state.value
        if (st is State.Idle || st is State.Failed) return
        Log.w(TAG, "Eslesme agi koptu (state=$st, connectedOnce=$connectedOnce)")
        /* Onay / eslesme asamasinda tam join reset YAPMA — Windows'ta İzin Ver
           kaybolur, telefonda surekli "tekrarlanıyor" olur. Yalnizca MQTT'yi
           ayni odaya yumusak yenile. */
        if (!connectedOnce && (
                st is State.Joining ||
                    st is State.Connecting ||
                    st is State.Hosting ||
                    st is State.AwaitingApproval
                )
        ) {
            softResubscribeSignal()
            return
        }
        scheduleReconnect("Eşleşme ağı koptu")
    }

    /** MQTT'yi ayni oda/sifre ile yeniden kur; peer/state sifirlanmaz. */
    private fun softResubscribeSignal() {
        if (softResubJob?.isActive == true) return
        val code = room
        val pwd = password
        if (code.isEmpty() || pwd.isEmpty()) return
        softResubJob = scope.launch {
            _joinHint.value = "Eşleşme ağı yenileniyor…"
            runCatching { signal?.close() }
            signal = null
            delay(800)
            if (intentionalClose) return@launch
            val st = _state.value
            if (st is State.Idle || st is State.Failed) return@launch
            try {
                val client = MqttSignalClient(settings.mqttBroker.ifEmpty { null })
                client.subscribe(
                    code, pwd,
                    SignalChannel.Listener { enqueueSignal(it) },
                    SignalChannel.CloseListener { onSignalClosed() }
                )
                signal = client
                Log.i(TAG, "MQTT yumusak yenilendi")
                if (st is State.Joining) {
                    startJoinRetries()
                }
                _joinHint.value = "Onay isteği yeniden gönderiliyor…"
            } catch (e: Exception) {
                Log.w(TAG, "MQTT yenilenemedi: ${e.message}")
                _joinHint.value = "Eşleşme ağı yok — interneti kontrol edin"
                emitToast("Eşleşme ağına ulaşılamadı", "warning")
            }
        }
    }

    /**
     * Beklenmeyen kopmada (MQTT / peer) otomatik yeniden baglanmayi dener.
     * Yalnizca bir kez baglandiktan sonra (connectedOnce) kullanilir.
     */
    private fun scheduleReconnect(reason: String) {
        if (intentionalClose) return
        if (reconnectJob?.isActive == true) return
        if (reconnectAttempts >= MAX_RECONNECT) {
            emitToast("$reason — yeniden bağlanılamadı", "error")
            _state.value = State.Failed("$reason. Lütfen tekrar bağlanın.")
            return
        }
        reconnectAttempts++
        val attempt = reconnectAttempts
        val wasHost = asHost
        val code = room
        val pwd = password
        val invite = reconnectInvite.ifEmpty { code }
        reconnectJob = scope.launch {
            emitToast("$reason — yeniden deneniyor ($attempt/$MAX_RECONNECT)…", "warning")
            delay(1500L * attempt)
            if (intentionalClose) return@launch
            runCatching { peer?.close("reconnect") }
            peer = null
            com.makay.remote.rtc.PeerRegistry.current = null
            if (wasHost && code.isNotEmpty() && pwd.isNotEmpty()) {
                if (signal == null) {
                    runCatching {
                        val client = MqttSignalClient(settings.mqttBroker.ifEmpty { null })
                        client.subscribe(
                            code, pwd,
                            SignalChannel.Listener { enqueueSignal(it) },
                            SignalChannel.CloseListener { onSignalClosed() }
                        )
                        signal = client
                    }
                }
                asHost = true
                room = code
                password = pwd
                _state.value = State.Hosting(code, pwd, Primitives.buildInviteText(code, pwd))
                emitToast("Oda yeniden açıldı — bağlantı bekleniyor", "info")
            } else if (!wasHost && invite.isNotEmpty() && pwd.isNotEmpty()) {
                join(invite, pwd)
            } else {
                _state.value = State.Failed("$reason")
            }
        }
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

    /** Tekliften once gelen uzak ICE adaylarini yeni olusturulan peer'a devreder. */
    private fun flushPendingIce(p: PeerBridge) {
        pendingIceBeforePeer.drain().forEach { p.addRemoteCandidate(it.mid, it.mline, it.cand) }
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
            signal?.publish(
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
                if (connectedOnce) {
                    scheduleReconnect("Bağlantı koptu")
                } else {
                    reset(keepSignal = true)
                }
            }
        }

        override fun onReady(peerName: String) {
            clearConnectTimeout(stopDiag = false)
            joinRetryJob?.cancel()
            reconnectAttempts = 0
            connectedOnce = true
            /* Bagli oturumda ICE/debug satiri gosterme — ekrani kaplar */
            stopDiagSampler()
            _diagnostics.value = ""
            _state.value = State.Connected(peerName, asHost)
            startLinkMonitor()
            /* Katilan (teknisyen): Windows offerer zaten control.sharer=true —
               onay modalı yok, ctrl-ok gelmez. Kontrolü hemen etkinleştir;
               ctrl-req Windows'a bildirilir (badge / input-relay zaten acik). */
            if (!asHost) {
                val p = peer
                if (p != null) {
                    p.requestControl()
                    _controlState.value = ControlState.GRANTED
                    emitToast("Kontrol etkin — ekrana dokunarak bilgisayarı yönetin", "success")
                } else {
                    _controlState.value = ControlState.NONE
                }
            } else {
                _controlState.value = ControlState.NONE
            }
        }

        override fun onText(text: String, ts: Long) {
            _chat.update { it + ChatMessage(text, mine = false, ts = ts) }
        }

        /**
         * Windows -> Android KONTROL YONU KALDIRILDI.
         *
         * Bu surum yalnizca Android -> Windows yonunu destekler: bu cihaz
         * ekranini paylasir (izlenir) ve KARSI tarafin bilgisayarini kontrol
         * eder. Karşı tarafin bizi kontrol etmesi icin yalnizca
         * AccessibilityService gider — Android girdi enjeksiyonuna baska yol
         * tanimaz — ve Google, yandan yuklemede accessibility servisi bildiren
         * uygulamalari Play Protect ile kurulmasina kadar ENGELLIYOR.
         * Bu yuzden istek otomatik reddedilir; masaustu taraf `ctrl-deny`
         * alip kendi arayuzunde net bir bilgi gosterir.
         */
        override fun onControlRequest() {
            val name = peer?.peerName ?: "Karşı taraf"
            peer?.denyControl()
            emitToast("Bu sürümde karşı tarafın sizi kontrol etmesi desteklenmiyor ($name)", "info")
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

        /* Gelen `ctrl` olaylari (Windows -> Android) icin override bilincli
           olarak YOK: `PeerBridge` arayuzunun bos govdesi olaylari yutuyor.
           Bu yon kaldirildi — girdi enjeksiyonu yalnizca AccessibilityService
           ile mumkun, o da yandan yuklemede Play Protect tarafindan engelleniyor. */

        override fun onClipboard(text: String) {
            // Masaustu `onClipboard` ile AYNI davranis: dogrudan yazilmaz,
            // onay dialoju gosterilir (clipModal karsiligi).
            if (text.isEmpty()) return
            _inboundClipboard.value = text
            emitToast("Karşı taraf panoya metin gönderdi — onay bekleniyor", "info")
        }

        /** Masaustu `mic` durum YAYINIDIR; kendi mikrofonumuza dokunmayiz. */
        override fun onMicToggle(enabled: Boolean) {
            _peerMicState.value = enabled
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
            /* Karsi taraf bilerek kapatti — otomatik reconnect yapma */
            intentionalClose = true
            reset(keepSignal = true)
            intentionalClose = false
            emitToast(if (reason.isEmpty()) "Bağlantı kapatıldı" else "Bağlantı kapatıldı: $reason", "warning")
        }

        override fun onError(message: String) {
            emitToast(message, "error")
        }

        override fun onFileProgress(p: FileTransferEngine.Progress) {
            _fileProgress.value = p
        }

        override fun onFileComplete(file: FileTransferEngine.Received) {
            _fileProgress.value = null
            val uri = saveToDownloads(file.name, file.mime, file.bytes)
            _events.tryEmit(Event.FileReceived(file.name, uri))
        }

        override fun onFileCancel(fid: String) {
            _fileProgress.value = null
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

    /**
     * Kontrol iste — Android -> Windows yonu (biz odaya KATILAN tarafiz).
     *
     * MASAUSTU HER ZAMAN IZIN VERIR: odayi acan (offerer) baglanir baglanmaz
     * `control.sharer = true` yapar (app.js:876); gelen `ctrl-req` icin onay
     * modalini ACMAZ ve `ctrl-ok` GONDERMEZ (app.js `onControlRequest`:
     * `if (control.pending || control.sharer) return`). Yani istek tek
     * yonludur; yetki hicbir zaman bize geri donmez.
     *
     * Bu yuzden `REQUESTED`'da BEKLENECEGINE yetki aninda bize gecer. Aksi
     * halde arayuz sonsuza dek "Onay Bekleniyor…" gosterir ve dokunma
     * katmani (`controlEnabled = state == GRANTED`) hic kurulmaz — yani
     * Android bilgisayari hic kontrol edemezdi. Tasarim da bunu istiyor:
     * "teknisyen hicbir 'Kontrol iste / Izin ver' adimi olmadan dogrudan
     * yonetsin" (app.js:874).
     */
    fun requestControl() {
        val p = peer ?: return
        if (!p.requestControl()) return
        _controlState.value = ControlState.GRANTED
        emitToast("Kontrol etkin — ekrana dokunarak bilgisayarı yönetin", "success")
    }

    fun stopControl() {
        if (peer?.stopControl() == true) _controlState.value = ControlState.NONE
    }

    // ---------------- pano (iki yon) ----------------

    /**
     * Kendi panomuzdaki metni karsi tarafa gonderir.
     * Masaustu `App.sendClipboard` ile ayni akis.
     */
    fun sendClipboard(text: String): Boolean {
        val p = peer ?: return false
        if (_state.value !is State.Connected) {
            emitToast("Bağlantı yok — pano gönderilemedi", "error")
            return false
        }
        if (text.isEmpty()) {
            emitToast("Pano boş — önce metin kopyalayın", "warning")
            return false
        }
        if (!p.sendClipboard(text)) {
            emitToast("Pano gönderilemedi — bağlantı yok", "error")
            return false
        }
        emitToast("Pano gönderildi — karşı taraf onaylayacak", "success")
        return true
    }

    /** Gelen pano icin kullanici karari verdi (yazilsin/yazilmasin). */
    fun clearInboundClipboard() {
        _inboundClipboard.value = null
    }

    // ---------------- ses (telefon gorusmesi yapisi) ----------------

    /**
     * Mikrofonu acar/kapatir.
     *
     * `RECORD_AUDIO` izni UI katmaninda ALINMIS OLMALIDIR; track ilk acilista
     * olusturulur ve masaustu `addScreenShareTrack` ile AYNI kalip olan
     * `addTrack + negotiate()` yoluyla pazara girer (kanitlanmis yol).
     * Varsayilan KAPALIDIR — mikrofon izinsiz acilmaz.
     */
    fun toggleMic(): Boolean {
        val p = peer ?: return false
        if (_state.value !is State.Connected) {
            emitToast("Bağlantı yok", "error")
            return false
        }
        val target = !_micState.value
        if (target) {
            val ok = runCatching {
                if (!p.hasLocalMic()) p.ensureMicrophone()
                p.sendMicState(true)
            }.getOrElse {
                Log.w(TAG, "Mikrofon acilamadi: ${it.message}")
                emitToast("Mikrofon açılamadı", "error")
                return false
            }
            if (!ok) {
                emitToast("Mikrofon açılamadı — izin verilmiş mi?", "error")
                return false
            }
        } else {
            p.sendMicState(false)
        }
        _micState.value = target
        emitToast(if (target) "Mikrofon açıldı" else "Mikrofon kapatıldı", "info")
        return true
    }

    /**
     * Hoparlör yonlendirmesini degistirir (telefon gorusmesindeki Hoparlör).
     *
     * DAVRANIS BILGISI: yonlendirme yalnizca kullanici dugmeye bastiginda
     * degistirilir; uygulama hic dokunmazsa ses yonlendirmesi DEGISENDEGILDIR.
     */
    fun setSpeaker(on: Boolean) {
        runCatching {
            val am = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = on
        }.onFailure {
            Log.w(TAG, "Hoparlör yönü değiştirilemedi: ${it.message}")
        }
        _speakerOn.value = on
        emitToast(if (on) "Hoparlör açık" else "Hoparlör kapalı", "info")
    }

    /** Basarili gonderilen ctrl olay sayisi (UI tani). */
    private val _ctrlSent = MutableStateFlow(0)
    val ctrlSent: StateFlow<Int> = _ctrlSent.asStateFlow()

    /**
     * Kontrol olayini gonderir. Cikis hizi RemoteGuard ile TEMIZLENIR
     * (tel siniri): gecersiz olay dusurulur, koordinatlar sinirlanir.
     */
    fun sendControl(payload: ControlPayload): Boolean {
        val p = peer ?: return false
        if (_controlState.value != ControlState.GRANTED) {
            /* Otomatik yeniden dene: baglanti hazirsa yetkiyi ac */
            if (_state.value is State.Connected && !asHost) {
                p.requestControl()
                _controlState.value = ControlState.GRANTED
            } else return false
        }
        val clean = sanitize(payload) ?: return false
        val ok = p.sendControl(clean)
        if (ok) _ctrlSent.update { it + 1 }
        else if (_ctrlSent.value == 0) {
            emitToast("Kontrol gönderilemedi — kanal kapalı olabilir", "error")
        }
        return ok
    }

    /**
     * Klavye akoru: or. Ctrl+C → control down, c down, c up, control up.
     * Dosya kopyala/yapıştır (Explorer) icin.
     */
    fun sendKeyChord(vararg keys: String): Boolean {
        if (keys.isEmpty()) return false
        var ok = true
        for (k in keys) {
            if (!sendControl(ControlPayload(ControlPayload.KEY_DOWN, k = k))) ok = false
        }
        for (k in keys.reversed()) {
            if (!sendControl(ControlPayload(ControlPayload.KEY_UP, k = k))) ok = false
        }
        return ok
    }

    fun sendCopy(): Boolean {
        val ok = sendKeyChord("control", "c")
        if (ok) emitToast("Kopyala (Ctrl+C) gönderildi", "success")
        return ok
    }

    fun sendPaste(): Boolean {
        val ok = sendKeyChord("control", "v")
        if (ok) emitToast("Yapıştır (Ctrl+V) gönderildi", "success")
        return ok
    }

    /** Son imlec konumunda sag tik (AnyDesk tarzi — jest degil, dugme). */
    @Volatile var lastPointerX: Double = 0.5
        private set
    @Volatile var lastPointerY: Double = 0.5
        private set

    fun notePointer(x: Double, y: Double) {
        lastPointerX = x.coerceIn(0.0, 1.0)
        lastPointerY = y.coerceIn(0.0, 1.0)
    }

    fun sendRightClickAtPointer(): Boolean {
        val x = lastPointerX
        val y = lastPointerY
        val a = sendControl(
            ControlPayload(ControlPayload.MOUSE_MOVE, x = x, y = y)
        )
        val b = sendControl(
            ControlPayload(ControlPayload.MOUSE_DOWN, x = x, y = y, b = "right")
        )
        val c = sendControl(
            ControlPayload(ControlPayload.MOUSE_UP, x = x, y = y, b = "right")
        )
        if (b && c) emitToast("Sağ tık gönderildi", "info")
        return a && b && c
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
        reconnectJob?.cancel()
        softResubJob?.cancel()
        val hostingRoom = room
        scope.launch {
            if (asHost && hostingRoom.isNotEmpty()) {
                runCatching { signal?.publish(SignalEnvelope(type = SignalEnvelope.TYPE_BYE)) }
            }
            reset(keepSignal = false)
            if (!settings.rememberPassword) settings.clearSession()
            intentionalClose = false
        }
    }

    /** Soft keyboard: metni Windows'a tek tek unicode karakter olarak gonderir. */
    fun typeText(text: String): Boolean {
        if (text.isEmpty()) return false
        var ok = true
        for (ch in text) {
            val k = ch.toString()
            if (!sendControl(ControlPayload(ControlPayload.KEY_DOWN, k = k))) ok = false
            if (!sendControl(ControlPayload(ControlPayload.KEY_UP, k = k))) ok = false
        }
        return ok
    }

    /** InputRelay adli tus: escape, enter, win, arrowup… */
    fun sendNamedKey(name: String): Boolean {
        val k = name.trim().lowercase()
        if (k.isEmpty()) return false
        val a = sendControl(ControlPayload(ControlPayload.KEY_DOWN, k = k))
        val b = sendControl(ControlPayload(ControlPayload.KEY_UP, k = k))
        return a && b
    }

    fun cancelFileTransfer(fid: String) {
        peer?.cancelFileSend(fid)
        _fileProgress.value = null
    }

    /** Hata ekranindan ana ekrana donerken cagrilir. */
    fun backToIdle() {
        intentionalClose = true
        reset(keepSignal = false)
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
            runCatching { signal?.close() }
        }
    }

    /**
     * Geri -> "Evet" ile TAM kapanis icin SENKRON yol.
     *
     * `shutdown()` coroutine icinde calistigi icin surec bitene kadar MQTT /
     * ekran paylasim servisi yasayabilir (arka planda calisma + pil harcama).
     * Burada her sey senkron kapatilir: peer'a BYE gonderilir, MQTT broker
     * baglantilari disconnectForcibly ile kesilir ve MediaProjection
     * foreground servisi durdurulur. Cagiran taraf sureci de sonlandirir.
     */
    fun shutdownNow() {
        intentionalClose = true
        joinRetryJob?.cancel()
        clearConnectTimeout()
        runCatching { peer?.close("shutdown") }
        peer = null
        runCatching { signal?.close() }
        signal = null
        runCatching { com.makay.remote.service.ScreenShareService.stop(appContext) }
    }

    // ---------------- yardimcilar ----------------

    private fun reset(keepSignal: Boolean) {
        joinRetryJob?.cancel()
        joinRetryJob = null
        acceptOfferJob?.cancel()
        acceptOfferJob = null
        softResubJob?.cancel()
        softResubJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        stopLinkMonitor()
        clearConnectTimeout()
        connectedOnce = false
        _joinHint.value = ""
        _fileProgress.value = null
        /* Once UI track'leri kopar, sonra native dispose — SurfaceView yarisi onlenir. */
        _remoteVideo.value = null
        _remoteAudio.value = null
        peer?.let { runCatching { it.close("reset") } }
        peer = null
        com.makay.remote.rtc.PeerRegistry.current = null
        if (!keepSignal) {
            signal?.close()
            signal = null
            room = ""
            password = ""
            asHost = false
        }
        lastJoinRequestAt = 0L
        pendingIceBeforePeer.clear()
        _chat.value = emptyList()
        _controlState.value = ControlState.NONE
        _inboundClipboard.value = null
        _peerMicState.value = null
        _micState.value = false
        _ctrlSent.value = 0
        if (_state.value !is State.Failed) _state.value = State.Idle
    }

    private fun startConnectTimeout() {
        clearConnectTimeout(stopDiag = false)
        startDiagSampler()
        connectTimeoutJob = scope.launch {
            delay(CONNECT_TIMEOUT_MS)
            val st = _state.value
            if (st !is State.Connecting) return@launch
            _diagnostics.value = buildString {
                append(peer?.diagText() ?: "peer yok")
                append(" · 35 sn'de tamamlanamadı")
            }
            /* Misafir: bir kez otomatik TURN + yeniden join */
            if (!asHost && !autoTurnTried && reconnectPassword.isNotEmpty()) {
                autoTurnTried = true
                settings.preferRelay = true
                settings.publicTurnFallback = true
                emitToast("Aktarma yolu (TURN) deneniyor…", "info")
                val inv = reconnectInvite.ifEmpty { room }
                val pwd = reconnectPassword
                intentionalClose = true
                reset(keepSignal = false)
                intentionalClose = false
                join(inv, pwd)
            } else {
                emitToast("Bağlantı uzun sürüyor — Ayarlar’dan «Yalnızca TURN» deneyin", "warning")
            }
        }
    }

    /** Baglanti / oturum sirasinda WebRTC tani ozetini UI'a akar. */
    private fun startDiagSampler() {
        diagJob?.cancel()
        diagJob = scope.launch {
            while (isActive) {
                val base = peer?.diagText() ?: "eşleşme ağına bağlanılıyor"
                _diagnostics.value = if (connectedOnce && _state.value is State.Connected) {
                    "● $base"
                } else base
                delay(900)
            }
        }
    }

    private fun stopDiagSampler() {
        diagJob?.cancel()
        diagJob = null
        if (_state.value !is State.Connected) _diagnostics.value = ""
    }

    /** Bagli iken RTT/bitrate izle + adaptive bitrate (host). */
    private fun startLinkMonitor() {
        linkJob?.cancel()
        adaptiveTier = 0
        linkJob = scope.launch {
            while (isActive && _state.value is State.Connected) {
                val p = peer ?: break
                p.sampleLinkStats { stats ->
                    val label = buildString {
                        when (stats.quality) {
                            "good" -> append("İyi")
                            "ok" -> append("Orta")
                            "poor" -> append("Zayıf")
                            else -> append("…")
                        }
                        if (stats.rttMs >= 0) append(" · ${stats.rttMs} ms")
                        if (stats.candidateType.isNotBlank()) {
                            append(" · ")
                            append(
                                when (stats.candidateType) {
                                    "relay" -> "TURN"
                                    "srflx" -> "STUN"
                                    "host" -> "P2P"
                                    else -> stats.candidateType
                                }
                            )
                        }
                    }
                    _linkQuality.value = LinkQuality(stats.quality, label)
                    if (asHost) {
                        val want = when (stats.quality) {
                            "poor" -> 2
                            "ok" -> 1
                            "good" -> 0
                            else -> adaptiveTier
                        }
                        if (want != adaptiveTier) {
                            adaptiveTier = want
                            com.makay.remote.service.ScreenShareService.applyAdaptiveTier(want)
                            val bps = when (want) {
                                2 -> 400_000
                                1 -> 1_200_000
                                else -> null
                            }
                            p.setVideoMaxBitrateBps(bps)
                        }
                    }
                }
                delay(2000)
            }
        }
    }

    private fun stopLinkMonitor() {
        linkJob?.cancel()
        linkJob = null
        _linkQuality.value = LinkQuality()
        adaptiveTier = 0
    }

    private fun clearConnectTimeout(stopDiag: Boolean = true) {
        connectTimeoutJob?.cancel()
        connectTimeoutJob = null
        if (stopDiag) stopDiagSampler()
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

    data class ParsedInvite(val code: String, val password: String, val signalUrl: String)

    /**
     * Windows `parseInvitePaste` ile AYNI kurallar.
     *
     * Ayistirma mantigi saf `InviteParser` nesnesindedir (JVM test edilebilir);
     * bu metot yalnizca ayni donus tipini koruyarak onu cagirir.
     * `signalUrl`: davet metninde "Sinyal: <adres>" satiri varsa o adres
     * (HTTP tünel/LAN odaya oradan katilinir — bkz. join).
     */
    fun parseInvite(raw: String): ParsedInvite =
        InviteParser.parse(raw).let { ParsedInvite(it.code, it.password, it.signalUrl) }

    companion object {
        private const val TAG = "MakaySession"
        private const val JOIN_RETRY_MS = 2000L
        private const val JOIN_THROTTLE_MS = 1200L
        private const val CONNECT_TIMEOUT_MS = 35_000L
        private const val MAX_RECONNECT = 3
    }
}
