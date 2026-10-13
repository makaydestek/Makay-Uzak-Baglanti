package com.makay.remote.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.makay.remote.BuildConfig
import com.makay.remote.session.SessionController
import com.makay.remote.session.SessionViewModel
import com.makay.remote.ui.screens.ConnectingScreen
import com.makay.remote.ui.screens.ErrorScreen
import com.makay.remote.ui.screens.HostScreen
import com.makay.remote.ui.screens.JoinFormScreen
import com.makay.remote.ui.screens.JoinPendingScreen
import com.makay.remote.ui.screens.SessionScreen
import com.makay.remote.ui.screens.SettingsScreen
import com.makay.remote.ui.theme.MakayTheme

/**
 * Uygulama kabugu: tek Activity, Compose ile ekran durumlarini yonetir.
 * Ekran gecisleri SessionController.State makinesinden turetilir.
 */
@Composable
fun RemoteApp(vm: SessionViewModel) {
    val c = MakayTheme.c
    val state by vm.state.collectAsState()
    val toasts = remember { SnackbarHostState() }

    // Toast akisi -> snackbar
    LaunchedEffect(Unit) {
        vm.toasts.collect { t ->
            toasts.showSnackbar(
                message = t.message,
                actionLabel = null,
                duration = if (t.kind == "error") androidx.compose.material3.SnackbarDuration.Long
                else androidx.compose.material3.SnackbarDuration.Short
            )
        }
    }

    // Davet metni yakalama (Host ekraninda paylasim icin)
    var inviteText by remember { mutableStateOf<String?>(null) }

    // Ayarlar ekrani acik mi? Yalnizca Idle durumunda erisilebilir.
    var showSettings by remember { mutableStateOf(false) }
    // Idle'a donecegimizde ayarlar ekranini kapat.
    LaunchedEffect(state) { if (state !is SessionController.State.Idle) showSettings = false }

    // Guncelleme denetimi — yalnizca Idle'da; ic seferi orande (6 saat) bir kez.
    // Cevrimdisi olursa sonuc null kalir ve hicbir UI gosterilmez.
    val update by vm.updateInfo.collectAsState()
    LaunchedEffect(state) {
        if (state is SessionController.State.Idle) vm.checkForUpdate()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(toasts) },
        containerColor = Color.Transparent
    ) { pad ->
        Box(
            Modifier
                .fillMaxSize()
                .background(c.bg)
                .padding(pad)
        ) {
            when (val s = state) {
                is SessionController.State.Idle ->
                    if (showSettings) {
                        SettingsScreen(
                            deviceName = vm.settings.deviceName,
                            themeMode = vm.themeMode.collectAsState().value,
                            requireControlApproval = vm.settings.requireControlApproval,
                            mqttBroker = vm.settings.mqttBroker,
                            publicTurnFallback = vm.settings.publicTurnFallback,
                            preferRelay = vm.settings.preferRelay,
                            turnUrl = vm.settings.turnUrl,
                            turnUser = vm.settings.turnUser,
                            turnPass = vm.settings.turnPass,
                            onDeviceName = { vm.settings.deviceName = it },
                            onThemeMode = { vm.setThemeMode(it) },
                            onRequireControlApproval = { vm.settings.requireControlApproval = it },
                            onMqttBroker = { vm.settings.mqttBroker = it },
                            onPublicTurnFallback = { vm.settings.publicTurnFallback = it },
                            onPreferRelay = { vm.settings.preferRelay = it },
                            onTurnUrl = { vm.settings.turnUrl = it },
                            onTurnUser = { vm.settings.turnUser = it },
                            onTurnPass = { vm.settings.turnPass = it },
                            onBack = { showSettings = false }
                        )
                    } else {
                        JoinFormScreen(
                            onHost = { vm.host() },
                            onJoin = { invite, pwd -> vm.join(invite, pwd) },
                            onSettings = { showSettings = true }
                        )
                    }

                is SessionController.State.Hosting -> {
                    inviteText = s.inviteText
                    HostScreen(
                        room = s.room,
                        password = s.password,
                        inviteText = s.inviteText,
                        onDisconnect = { vm.disconnect() }
                    )
                }

                is SessionController.State.AwaitingApproval ->
                    HostScreen(
                        room = "",
                        password = "",
                        inviteText = inviteText.orEmpty(),
                        awaitingName = s.requesterName,
                        onAllow = { vm.allowJoin() },
                        onDeny = { vm.denyJoin() },
                        onDisconnect = { vm.disconnect() }
                    )

                is SessionController.State.Joining ->
                    JoinPendingScreen(room = s.room, onCancel = { vm.backToIdle() })

                is SessionController.State.Connecting ->
                    ConnectingScreen(asHost = s.asHost, onCancel = { vm.disconnect() })

                is SessionController.State.Connected ->
                    SessionScreen(vm = vm, peerName = s.peerName, asHost = s.asHost)

                is SessionController.State.Failed ->
                    ErrorScreen(message = s.message, onRetry = { vm.backToIdle() })
            }

            // Guncelleme varsa bilgilendirme — yalnizca Idle'da gosterilir.
            update?.let { info ->
                val ctx = androidx.compose.ui.platform.LocalContext.current
                AlertDialog(
                    onDismissRequest = { vm.dismissUpdate() },
                    containerColor = c.bg2,
                    title = { Text("Güncelleme yayınlandı", color = c.text) },
                    text = {
                        Column {
                            Text(
                                text = "Yeni sürüm: ${info.version}",
                                color = c.text
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "Kurulu sürüm: ${BuildConfig.VERSION_NAME}",
                                color = c.muted,
                                style = MaterialTheme.typography.bodySmall
                            )
                            info.notes?.let { n ->
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    text = n.take(500),
                                    color = c.muted,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            val url = info.url
                            if (url != null) {
                                runCatching {
                                    ctx.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                    )
                                }
                            }
                            vm.dismissUpdate()
                        }) { Text("İndir") }
                    },
                    dismissButton = {
                        TextButton(onClick = { vm.dismissUpdate() }) {
                            Text("Şimdi değil")
                        }
                    }
                )
            }
        }
    }
}
