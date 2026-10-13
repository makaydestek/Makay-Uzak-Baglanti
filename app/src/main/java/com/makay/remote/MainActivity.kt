package com.makay.remote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.makay.remote.session.SessionViewModel
import com.makay.remote.ui.RemoteApp
import com.makay.remote.ui.theme.MakayTheme

class MainActivity : ComponentActivity() {

    private val vm: SessionViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            // Tema tercihi: "system" sistem ayarini, digerleri zorlar.
            // Varsayilan "dark" oldugu icin mevcut davranis (koyu) korunur.
            val mode by vm.themeMode.collectAsState()
            val dark = when (mode) {
                "light" -> false
                "dark" -> true
                else -> null // system
            }
            MakayTheme(dark = dark) {
                RemoteApp(vm)
            }
        }
    }

    override fun onDestroy() {
        if (isFinishing) vm.controller.shutdown()
        super.onDestroy()
    }
}
