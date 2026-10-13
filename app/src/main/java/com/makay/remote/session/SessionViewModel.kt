package com.makay.remote.session

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.makay.remote.data.SettingsStore
import com.makay.remote.rtc.ControlPayload
import com.makay.remote.rtc.FileTransferEngine
import kotlinx.coroutines.flow.MutableSharedFlow
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

    val state = controller.state
    val chat = controller.chat
    val controlState = controller.controlState
    val pendingControlRequest = controller.pendingControlRequest
    val remoteVideo = controller.remoteVideo

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
                    is SessionController.Event.ControlRequest -> Unit // UI dialog kendi durumundan okur
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
    fun grantControl() = controller.grantControl()
    fun denyControl() = controller.denyControl()
    fun stopControl() = controller.stopControl()

    /** Teknisyen → host kontrol olaylari. */
    fun sendControl(payload: ControlPayload) = controller.sendControl(payload)

    fun sendFile(file: File, mime: String) = viewModelScope.launch { controller.sendFile(file, mime) }

    fun eglContext() = controller.eglContext()

    /** Ekran paylasimini durdurur (servisi kapatir). */
    fun stopScreenShare() {
        com.makay.remote.service.ScreenShareService.stop(getApplication())
    }

    override fun onCleared() {
        controller.shutdown()
        super.onCleared()
    }
}
