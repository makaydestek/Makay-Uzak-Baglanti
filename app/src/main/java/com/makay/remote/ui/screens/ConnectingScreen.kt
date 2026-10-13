package com.makay.remote.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.makay.remote.ui.theme.MakayTheme

/** offer/answer + ICE kuruluyor. */
@Composable
fun ConnectingScreen(asHost: Boolean, onCancel: () -> Unit) {
    val c = MakayTheme.c
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(color = c.accent2, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(20.dp))
        Text("Bağlanıyor…", style = MaterialTheme.typography.headlineSmall, color = c.text)
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (asHost) "Bağlantı kuruluyor — ekranınız paylaşılacak"
            else "Uzak ekran alınıyor…",
            style = MaterialTheme.typography.bodyMedium,
            color = c.muted,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(28.dp))
        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
            Text("Vazgeç", color = c.danger)
        }
    }
}
