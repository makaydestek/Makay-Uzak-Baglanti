package com.makay.remote.update

import android.app.Activity
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class AppUpdateUiState(
    val checking: Boolean = false,
    val downloading: Boolean = false,
    val progress: Int = 0,
    val available: AvailableUpdate? = null,
    val dialogVisible: Boolean = false,
    val statusMessage: String? = null,
    val error: String? = null,
    val needsInstallPermission: Boolean = false,
    val downloadedApk: File? = null
)

class AppUpdateViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = AppUpdateRepository(application)

    private val _state = MutableStateFlow(AppUpdateUiState())
    val state: StateFlow<AppUpdateUiState> = _state.asStateFlow()

    private var postponedThisSession = false
    private var launchChecked = false

    fun checkOnLaunch() {
        if (launchChecked) return
        launchChecked = true
        viewModelScope.launch {
            _state.update { it.copy(checking = true, error = null) }
            val result = runCatching {
                // Uygulama her yeni acilisinda GitHub Releases'i kontrol et.
                // Otomatik kontrol araligi yalnizca arka plan/diger tetikleyicilere uygulanir.
                withContext(Dispatchers.IO) { repository.checkForUpdate(force = true) }
            }
            val update = result.getOrNull()
            if (update != null && !postponedThisSession) {
                runCatching { repository.showUpdateNotification(update) }
                _state.update {
                    it.copy(
                        checking = false,
                        available = update,
                        dialogVisible = true,
                        statusMessage = "Yeni sürüm: ${update.versionName}",
                        error = null
                    )
                }
            } else {
                _state.update {
                    it.copy(
                        checking = false,
                        available = update,
                        dialogVisible = false,
                        statusMessage = null,
                        error = null
                    )
                }
            }
        }
    }

    fun handleNotificationOpen(autoDownload: Boolean, activity: Activity?) {
        viewModelScope.launch {
            postponedThisSession = false
            _state.update { it.copy(checking = true, error = null) }
            val update = _state.value.available
                ?: runCatching {
                    withContext(Dispatchers.IO) { repository.checkForUpdate(force = true) }
                }.getOrNull()
            if (update == null) {
                _state.update {
                    it.copy(
                        checking = false,
                        dialogVisible = false,
                        statusMessage = "Uygulama güncel",
                        error = null
                    )
                }
                return@launch
            }
            _state.update {
                it.copy(
                    checking = false,
                    available = update,
                    dialogVisible = true,
                    statusMessage = "Yeni sürüm: ${update.versionName}",
                    error = null
                )
            }
            if (autoDownload && activity != null) {
                startDownloadAndInstall(activity)
            }
        }
    }

    fun checkManual() {
        viewModelScope.launch {
            postponedThisSession = false
            _state.update {
                it.copy(checking = true, error = null, statusMessage = "Güncelleme kontrol ediliyor…")
            }
            val result = runCatching {
                withContext(Dispatchers.IO) { repository.checkForUpdate(force = true) }
            }
            val update = result.getOrNull()
            val err = result.exceptionOrNull()?.localizedMessage ?: result.exceptionOrNull()?.message
            when {
                update != null -> {
                    runCatching { repository.showUpdateNotification(update) }
                    _state.update {
                        it.copy(
                            checking = false,
                            available = update,
                            dialogVisible = true,
                            statusMessage = "Yeni sürüm: ${update.versionName}",
                            error = null
                        )
                    }
                }
                err != null -> {
                    _state.update {
                        it.copy(checking = false, statusMessage = null, error = "Kontrol başarısız: $err")
                    }
                }
                else -> {
                    _state.update {
                        it.copy(
                            checking = false,
                            available = null,
                            dialogVisible = false,
                            statusMessage = "Uygulama güncel",
                            error = null
                        )
                    }
                }
            }
        }
    }

    fun dismissDialog(postpone: Boolean) {
        if (postpone) postponedThisSession = true
        _state.update { it.copy(dialogVisible = false) }
    }

    fun reportNoActivity() {
        _state.update {
            it.copy(error = "Kurulum başlatılamadı. Uygulamayı yeniden açıp tekrar deneyin.")
        }
    }

    fun clearStatus() {
        _state.update { it.copy(statusMessage = null, error = null) }
    }

    fun startDownloadAndInstall(activity: Activity) {
        val update = _state.value.available ?: return
        viewModelScope.launch {
            if (!repository.canRequestInstall()) {
                _state.update {
                    it.copy(
                        needsInstallPermission = true,
                        dialogVisible = true,
                        error = "Ayarlardan “Bilinmeyen uygulamalar” iznini açın, sonra tekrar deneyin."
                    )
                }
                runCatching { activity.startActivity(repository.unknownSourcesSettingsIntent()) }
                return@launch
            }
            _state.update {
                it.copy(
                    downloading = true,
                    progress = 0,
                    error = null,
                    dialogVisible = true,
                    statusMessage = "APK indiriliyor…"
                )
            }
            val download = runCatching {
                withContext(Dispatchers.IO) {
                    repository.downloadApk(update) { pct ->
                        _state.update { s ->
                            s.copy(
                                progress = pct,
                                statusMessage = if (pct >= 100) {
                                    "İmza ve bütünlük doğrulanıyor…"
                                } else {
                                    "APK indiriliyor… %$pct"
                                }
                            )
                        }
                    }
                }
            }
            val file = download.getOrNull()
            if (file == null) {
                val raw = download.exceptionOrNull()?.localizedMessage
                    ?: download.exceptionOrNull()?.message
                    ?: "İndirme başarısız"
                _state.update {
                    it.copy(downloading = false, dialogVisible = true, error = raw, statusMessage = null)
                }
                return@launch
            }
            _state.update {
                it.copy(
                    downloading = false,
                    progress = 100,
                    downloadedApk = file,
                    statusMessage = "Doğrulandı — kurulum başlatılıyor…",
                    dialogVisible = true
                )
            }
            runCatching {
                activity.startActivity(repository.installIntent(file))
            }.onFailure { e ->
                _state.update {
                    it.copy(dialogVisible = true, error = e.localizedMessage ?: "Kurulum ekranı açılamadı")
                }
            }
        }
    }

    fun retryInstallAfterPermission(activity: Activity) {
        if (!repository.canRequestInstall()) return
        val file = _state.value.downloadedApk
        if (file != null && file.exists()) {
            runCatching { activity.startActivity(repository.installIntent(file)) }
            _state.update { it.copy(needsInstallPermission = false, error = null) }
            return
        }
        _state.update { it.copy(needsInstallPermission = false, error = null) }
        if (_state.value.available != null) startDownloadAndInstall(activity)
    }

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(AppUpdateViewModel::class.java)) {
                return AppUpdateViewModel(application) as T
            }
            throw IllegalArgumentException("Unknown ViewModel")
        }
    }
}
