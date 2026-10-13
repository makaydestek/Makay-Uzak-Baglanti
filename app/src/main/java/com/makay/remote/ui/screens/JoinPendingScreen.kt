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
import com.makay.remote.core.Primitives
import com.makay.remote.ui.theme.MakayTheme

/** Katilimci: join-request gonderiliyor, host onayi bekleniyor. */
@Composable
fun JoinPendingScreen(room: String, onCancel: () -> Unit) {
    val c = MakayTheme.c
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(color = c.accent, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(20.dp))
        Text("Onay bekleniyor…", style = MaterialTheme.typography.headlineSmall, color = c.text)
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Karşı tarafta «İzin Ver» onayı bekleniyor",
            style = MaterialTheme.typography.bodyMedium,
            color = c.muted,
            textAlign = TextAlign.Center
        )
        if (room.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Kimlik: ${Primitives.formatRoom(room)}",
                style = MaterialTheme.typography.bodyMedium,
                color = c.muted
            )
        }
        Spacer(Modifier.height(28.dp))
        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
            Text("Vazgeç", color = c.danger)
        }
    }
}
