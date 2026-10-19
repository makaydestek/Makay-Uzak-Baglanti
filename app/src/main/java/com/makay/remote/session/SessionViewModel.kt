package com.makay.remote.session

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.makay.remote.data.SettingsStore
import com.makay.remote.rtc.ControlPayload
import com.makay.remote.rtc.FileTransferEngine
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * UI (Compose) ile SessionController arasindaki kopruler.
 * Tum aksiyonlar Main thread'den calisir; ag isleri controller icinde IO'ya duser.
 */
class SessionViewModel(app: Application) : AndroidViewModel(app) {

    val controller = SessionController(app, SettingsStore(app), viewModelScope)

    /** Ayar deposu — Ayarlar ekranindan dogrudan okunur/yazilir. */
    val settings = controller.settings

    /** Ayarlar ekraninda aninda gorunmesi icin tema durumu. */
    val themeMode = MutableStateFlow(settings.themeMode)

    val state = controller.state
    val chat = controller.chat
    val controlState = controller.controlState
    val remoteVideo = controller.remoteVideo

    /** Baglanti sirasindaki WebRTC tani ozeti (tek satir, "Baglaniyor" ekraninda). */
    val diagnostics = controller.diagnostics

    /** Onay beklerken join-request durum ipucu. */
    val joinHint = controller.joinHint

    // ---------------- pano / ses ----------------

    /** Gelen pano metni (onay dialoju icin). */
    val inboundClipboard = controller.inboundClipboard

    val micState = controller.micState
    val peerMicState = controller.peerMicState
    val speakerOn = controller.speakerOn

    private val _toasts = MutableSharedFlow<SessionController.Event.Toast>(extraBufferCapacity = 8)
    val toasts: SharedFlow<SessionController.Event.Toast> = _toasts.asSharedFlow()

    init {
        viewModelScope.launch {
            controller.events.collect { ev ->
                when (ev) {
                    is SessionController.Event.Toast -> _toasts.tryEmit(ev)
                    is SessionController.Event.FileReceived ->
                        _toasts.tryEmit(SessionController.Event.Toast("Dosya alındı: ${ev.name}", "success"))
                    is SessionController.Event.FileSent ->
                        _toasts.tryEmit(SessionController.Event.Toast("Dosya gönderildi: ${ev.name}", "success"))
                }
            }
        }
    }

    fun host() = viewModelScope.launch { controller.host() }
    fun join(invite: String, password: String) = viewModelScope.launch { controller.join(invite, password) }
    fun allowJoin() = controller.allowJoin()
    fun denyJoin() = controller.denyJoin()
    fun backToIdle() = controller.backToIdle()
    fun disconnect() = controller.disconnect()

    fun sendChat(text: String) = controller.sendChat(text)
    fun requestControl() = controller.requestControl()
    fun stopControl() = controller.stopControl()

    /** Teknisyen → host kontrol olaylari. */
    fun sendControl(payload: ControlPayload) = controller.sendControl(payload)

    // ---------------- pano ----------------

    /** Kendi panomuzdaki metni karsi tarafa gonderir. */
    fun sendClipboard(text: String) = controller.sendClipboard(text)

    /** Gelen pano icin kullanici karar verdi (onayla/reddet). */
    fun clearInboundClipboard() = controller.clearInboundClipboard()

    // ---------------- ses (telefon gorusmesi yapisi) ----------------

    /**
     * Mikrofonu ac/kapat. `RECORD_AUDIO` izni UI'da alinmamissa izin
     * istenir ve karar asagidaki geri cagriya bildirilir.
     */
    fun toggleMic() = controller.toggleMic()

    fun setSpeaker(on: Boolean) = controller.setSpeaker(on)

    fun sendFile(file: File, mime: String) = viewModelScope.launch { controller.sendFile(file, mime) }

    fun eglContext() = controller.eglContext()

    /** Tema: "system" | "dark" | "light". */
    fun setThemeMode(mode: String) {
        settings.themeMode = mode
        themeMode.value = mode
    }

    /** Ekran paylasimini durdurur (servisi kapatir). */
    fun stopScreenShare() {
        com.makay.remote.service.ScreenShareService.stop(getApplication())
    }

    override fun onCleared() {
        controller.shutdown()
        super.onCleared()
    }
}
