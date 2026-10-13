package com.makay.remote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.makay.remote.session.SessionViewModel
import com.makay.remote.ui.RemoteApp
import com.makay.remote.ui.theme.MakayTheme

class MainActivity : ComponentActivity() {

    private val vm: SessionViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MakayTheme(dark = true) {
                RemoteApp(vm)
            }
        }
    }

    override fun onDestroy() {
        if (isFinishing) vm.controller.shutdown()
        super.onDestroy()
    }
}
