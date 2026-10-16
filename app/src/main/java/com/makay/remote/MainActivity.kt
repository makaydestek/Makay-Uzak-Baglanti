package com.makay.remote

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.makay.remote.session.SessionViewModel
import com.makay.remote.update.AppUpdateHost
import com.makay.remote.update.AppUpdateRepository
import com.makay.remote.ui.RemoteApp
import com.makay.remote.ui.theme.MakayTheme
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.atomic.AtomicLong

class MainActivity : ComponentActivity() {

    private val vm: SessionViewModel by viewModels()

    // Guncelleme bildirimine tiklaninca AppUpdateHost'a tasinan niyet durumu.
    private val _updatePromptToken = AtomicLong(0L)
    private val _updateFromNotification = MutableStateFlow(false)
    private val _updateAutoDownload = MutableStateFlow(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        consumeUpdateIntent(intent)
        setContent {
            // Tema tercihi: "system" sistem ayarini, digerleri zorlar.
            // Varsayilan "dark" oldugu icin mevcut davranis (koyu) korunur.
            val mode by vm.themeMode.collectAsState()
            val dark = when (mode) {
                "light" -> false
                "dark" -> true
                else -> null // system
            }
            val promptToken = _updatePromptToken.get()
            val fromNotif by _updateFromNotification.collectAsState()
            val autoDl by _updateAutoDownload.collectAsState()
            MakayTheme(dark = dark) {
                RemoteApp(vm)
                // Guncelleme ekrani — Makay ekosistemdekilerle birebir ayni akis.
                // Uygulama acilisinda kendiliginden kontrol eder; guncelleme
                // varsa "Guncelle / Ertele" diyalogu cikar, indirme uygulama
                // icinden yapilir (tarayiciya yonlendirme yapilmaz).
                AppUpdateHost(
                    openFromNotification = fromNotif,
                    autoDownload = autoDl,
                    promptToken = promptToken
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeUpdateIntent(intent)
    }

    /** Bildirimden gelen guncelleme niyetini tek kullanimlik olarak tuket. */
    private fun consumeUpdateIntent(intent: Intent?) {
        if (intent == null) return
        val show = intent.getBooleanExtra(AppUpdateRepository.EXTRA_SHOW_UPDATE, false)
        if (!show) return
        val auto = intent.getBooleanExtra(AppUpdateRepository.EXTRA_AUTO_DOWNLOAD, false)
        intent.removeExtra(AppUpdateRepository.EXTRA_SHOW_UPDATE)
        intent.removeExtra(AppUpdateRepository.EXTRA_AUTO_DOWNLOAD)
        intent.removeExtra(AppUpdateRepository.EXTRA_UPDATE_TAG)
        _updateFromNotification.value = true
        _updateAutoDownload.value = auto
        _updatePromptToken.set(System.currentTimeMillis())
    }

    override fun onDestroy() {
        if (isFinishing) vm.controller.shutdown()
        super.onDestroy()
    }
}
