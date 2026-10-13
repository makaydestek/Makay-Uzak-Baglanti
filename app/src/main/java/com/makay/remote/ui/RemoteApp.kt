package com.makay.remote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Scaffold
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
import com.makay.remote.session.SessionController
import com.makay.remote.session.SessionViewModel
import com.makay.remote.ui.screens.ConnectingScreen
import com.makay.remote.ui.screens.ErrorScreen
import com.makay.remote.ui.screens.HostScreen
import com.makay.remote.ui.screens.JoinFormScreen
import com.makay.remote.ui.screens.JoinPendingScreen
import com.makay.remote.ui.screens.SessionScreen
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
                    JoinFormScreen(
                        onHost = { vm.host() },
                        onJoin = { invite, pwd -> vm.join(invite, pwd) }
                    )

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
        }
    }
}
