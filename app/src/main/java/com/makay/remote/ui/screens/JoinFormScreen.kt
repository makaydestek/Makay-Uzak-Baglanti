package com.makay.remote.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ScreenShare
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.makay.remote.core.InviteParser
import com.makay.remote.ui.theme.MakayTheme

/**
 * Ana ekran: "Baglantiya Ac" (benim ekranimi paylas) ve "Baglan" (karsi
 * bilgisayari izle/kontrol et) secenekleri + davet metni yapistirma alani.
 */
@Composable
fun JoinFormScreen(
    onHost: () -> Unit,
    onJoin: (invite: String, password: String) -> Unit,
    onSettings: () -> Unit = {}
) {
    val c = MakayTheme.c
    var invite by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = "MAKAY Uzaktan Bağlantı",
                style = MaterialTheme.typography.headlineSmall,
                color = c.text
            )
            Spacer(Modifier.size(6.dp))
            IconButton(onClick = onSettings, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.Settings, contentDescription = "Ayarlar", tint = c.muted)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Sorun yaşayan bilgisayarı açın, teknisyen bağlanır",
            style = MaterialTheme.typography.bodyMedium,
            color = c.muted,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(32.dp))

        // --- Baglantiya Ac (OFFERER) ---
        Button(
            onClick = onHost,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = c.primary)
        ) {
            Icon(Icons.AutoMirrored.Filled.ScreenShare, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.size(8.dp))
            Text("Bağlantıya Aç", style = MaterialTheme.typography.titleMedium)
        }

        Spacer(Modifier.height(12.dp))
        Text(
            text = "Bu cihazın ekranı paylaşılır",
            style = MaterialTheme.typography.bodySmall,
            color = c.muted
        )

        Spacer(Modifier.height(28.dp))

        OutlinedTextField(
            value = invite,
            onValueChange = { invite = it },
            label = { Text("Davet metnini yapıştırın") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            maxLines = 4,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = c.accent,
                unfocusedBorderColor = c.cardBorder,
                focusedTextColor = c.text,
                unfocusedTextColor = c.text
            )
        )

        Spacer(Modifier.height(4.dp))
        Text(
            text = "İpucu: Davet metninin tamamını (Kimlik, Şifre ve varsa Sinyal satırı) yapıştırın — bağlantı en sağlam yoldan kurulur.",
            style = MaterialTheme.typography.bodySmall,
            color = c.muted
        )

        Spacer(Modifier.height(10.dp))

        // --- Kullanilacak baglanti yolu gostergesi ---
        // Windows'taki "Uzak konum / Yerel ag" secimine karsilik gelir:
        // davet metninde "Sinyal: http://..." satiri VARSA ayni agdaki
        // Windows bilgisayarina yerel HTTP sinyal sunucusu uzerinden
        // dogrudan baglanilir (internet gerekmez); satir YOKSA bulut
        // eslesmesi (MQTT) kullanilir. Her iki yol da ayni PeerBridge /
        // WebRTC katmanini paylasir, boylece her ikisi de calisir.
        val parsedInvite = remember(invite) { InviteParser.parse(invite) }
        val isLanPath = invite.isNotBlank() && parsedInvite.signalUrl.isNotEmpty()
        val pathText = when {
            invite.isBlank() -> "Davet metnini yapıştırın — yol otomatik seçilir"
            isLanPath -> "Yerel ağ — aynı Wi‑Fi üzerinde doğrudan bağlanılacak"
            else -> "Bulut eşleşmesi (MQTT) — internet bağlantısı kullanılır"
        }
        Text(
            text = "Bağlantı yolu: $pathText",
            style = MaterialTheme.typography.bodySmall,
            color = if (isLanPath) c.accent else c.muted,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Şifre (boş bırakılırsa metinden okunur)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = c.accent,
                unfocusedBorderColor = c.cardBorder,
                focusedTextColor = c.text,
                unfocusedTextColor = c.text
            )
        )

        Spacer(Modifier.height(20.dp))

        OutlinedButton(
            onClick = { onJoin(invite, password) },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Icon(Icons.Filled.Computer, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.size(8.dp))
            Text("Bağlan", style = MaterialTheme.typography.titleMedium, color = c.text)
        }
    }
}
