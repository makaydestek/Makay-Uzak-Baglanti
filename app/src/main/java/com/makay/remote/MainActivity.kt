package com.makay.remote

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.makay.remote.session.SessionController
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
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        /* WebRTC fabrikasini ana thread'de onceden kur — "Izin Ver" aninda
           arka planda initialize/egl crash riskini azaltir. */
        runCatching { com.makay.remote.rtc.RtcEngine.ensure(applicationContext) }
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

    @Deprecated("Compose BackHandler oturumda tam ekran/koparma öncelikli")
    override fun onBackPressed() {
        val state = vm.state.value
        /* Connected: SessionScreen BackHandler (once FS çık → kopar) işler.
           Buraya düşerse Compose tüketmemiştir — uygulamadan çıkış onayı. */
        when (state) {
            is SessionController.State.Connected -> {
                /* SessionScreen yoksa (nadir) — oturumu kopar, uygulamayı kapatma */
                vm.disconnect()
            }
            else -> {
                showYesNoDialog(
                    "Uygulamadan Çıkış Yapmak İstiyor musunuz?",
                    { vm.controller.shutdownNow(); finish() },
                    { }
                )
            }
        }
    }

    private fun showYesNoDialog(
        title: String,
        onYes: () -> Unit,
        onNo: () -> Unit
    ) {
        // Activity içinde Dialog göstermek için temel bir approach.
        // android.app.AlertDialog base sınıfı, ekstra appcompat bağımlılığı gerektirmeden
        // kullanılabilir; Material Design diyalogu projede Material3 kullanıyorsak
        // uygun MaterialDialog e geçilebilir.
        AlertDialog.Builder(this)
            .setTitle("Uyarı")
            .setMessage(title)
            .setPositiveButton("Evet") { _, _ -> onYes() }
            .setNegativeButton("Hayır") { _, _ -> onNo() }
            .show()
    }

    override fun onDestroy() {
        if (isFinishing) vm.controller.shutdownNow()
        super.onDestroy()
    }
}