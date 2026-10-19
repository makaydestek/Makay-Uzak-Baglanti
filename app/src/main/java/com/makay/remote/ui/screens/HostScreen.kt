package com.makay.remote.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.makay.remote.core.Primitives
import com.makay.remote.ui.theme.MakayTheme

/**
 * Host (Bağlantıya Aç) ekranı: oda kodu + şifre + davet metni.
 * `awaitingName` dolu ise join-request onay dialogu gosterilir.
 */
@Composable
fun HostScreen(
    room: String,
    password: String,
    inviteText: String,
    awaitingName: String? = null,
    onAllow: ((trustDevice: Boolean) -> Unit)? = null,
    onDeny: (() -> Unit)? = null,
    onDisconnect: () -> Unit
) {
    val c = MakayTheme.c
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Bağlantı bekleniyor",
            style = MaterialTheme.typography.headlineSmall,
            color = c.text
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Kimlik ve şifreyi karşı tarafa gönderin",
            style = MaterialTheme.typography.bodyMedium,
            color = c.muted,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(24.dp))

        if (room.isNotEmpty()) {
            Text("Kimlik", style = MaterialTheme.typography.labelLarge, color = c.muted)
            Text(
                text = Primitives.formatRoom(room),
                style = MaterialTheme.typography.displaySmall,
                color = c.accent,
                fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.height(12.dp))
            Text("Şifre", style = MaterialTheme.typography.labelLarge, color = c.muted)
            Text(
                text = password,
                style = MaterialTheme.typography.headlineMedium,
                color = c.text,
                fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.height(20.dp))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = { copyToClipboard(context, inviteText) },
                enabled = inviteText.isNotEmpty()
            ) {
                Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("Kopyala")
            }
            OutlinedButton(
                onClick = { shareInvite(context, inviteText) },
                enabled = inviteText.isNotEmpty()
            ) {
                Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("Paylaş")
            }
        }

        Spacer(Modifier.height(16.dp))

        Text(
            text = if (awaitingName == null) "Karşı taraf bağlanana kadar bekleyin…"
            else "İstek bekliyor",
            style = MaterialTheme.typography.bodyMedium,
            color = c.muted,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(24.dp))

        OutlinedButton(
            onClick = onDisconnect,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Bağlantıyı Kapat", color = c.danger)
        }
    }

    if (awaitingName != null && onAllow != null && onDeny != null) {
        var trust by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { },
            containerColor = c.bg2,
            title = { Text("Bağlantı isteği", color = c.text) },
            text = {
                Column {
                    Text(
                        "«$awaitingName» bu cihaza bağlanmak istiyor.",
                        color = c.text
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = trust, onCheckedChange = { trust = it })
                        Text(
                            "Güvenli cihaz olarak kaydet (isteğe bağlı)\nSonraki bağlantılarda İzin Ver gerekmez · 30 gün",
                            color = c.muted,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { onAllow(trust) },
                    colors = ButtonDefaults.buttonColors(containerColor = c.success)
                ) { Text("İzin Ver") }
            },
            dismissButton = {
                TextButton(onClick = onDeny) { Text("Reddet", color = c.danger) }
            }
        )
    }
}

private fun copyToClipboard(context: Context, text: String) {
    if (text.isEmpty()) return
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("MAKAY davet", text))
}

private fun shareInvite(context: Context, text: String) {
    if (text.isEmpty()) return
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, "Daveti paylaş"))
}
