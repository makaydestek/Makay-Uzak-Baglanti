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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.makay.remote.ui.theme.MakayTheme

/**
 * Ayarlar ekrani — masaustu Ayarlar penceresinin Android karsiligi.
 *
 * Burada tanimlanan TUM alanlar gercekten kullanilir:
 *   - deviceName             -> hello zarfinda `name`
 *   - themeMode              -> MainActivity'de `MakayTheme(dark=...)`
 *   - mqttBroker             -> MqttSignalClient broker sirasi (one alinir)
 *   - publicTurnFallback / preferRelay / turn* -> ICE sunucu yapilandirmasi
 *
 * NOT: Masaustunun "ayni ag (LAN)" eslesmesi Android'de yoktur; o alanlar
 * SettingsStore'da bilincli olarak tanimlanmaz.
 */
@Composable
fun SettingsScreen(
    deviceName: String,
    themeMode: String,
    mqttBroker: String,
    publicTurnFallback: Boolean,
    preferRelay: Boolean,
    turnUrl: String,
    turnUser: String,
    turnPass: String,
    onDeviceName: (String) -> Unit,
    onThemeMode: (String) -> Unit,
    onMqttBroker: (String) -> Unit,
    onPublicTurnFallback: (Boolean) -> Unit,
    onPreferRelay: (Boolean) -> Unit,
    onTurnUrl: (String) -> Unit,
    onTurnUser: (String) -> Unit,
    onTurnPass: (String) -> Unit,
    onBack: () -> Unit
) {
    val c = MakayTheme.c

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Geri", tint = c.text)
            }
            Text(
                text = "Ayarlar",
                style = MaterialTheme.typography.headlineSmall,
                color = c.text
            )
        }

        Spacer(Modifier.height(8.dp))

        // ---------- Genel ----------
        SectionTitle("Genel")

        OutlinedTextField(
            value = deviceName,
            onValueChange = onDeviceName,
            label = { Text("Cihaz adı") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = fieldColors(c)
        )

        Spacer(Modifier.height(16.dp))

        Text("Tema", style = MaterialTheme.typography.bodyMedium, color = c.muted)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeChip("Sistem", themeMode == "system", Modifier.weight(1f), c) { onThemeMode("system") }
            ThemeChip("Koyu", themeMode == "dark", Modifier.weight(1f), c) { onThemeMode("dark") }
            ThemeChip("Açık", themeMode == "light", Modifier.weight(1f), c) { onThemeMode("light") }
        }

        Spacer(Modifier.height(24.dp))

        // ---------- Baglanti ----------
        SectionTitle("Bağlantı")

        OutlinedTextField(
            value = mqttBroker,
            onValueChange = onMqttBroker,
            label = { Text("Özel MQTT broker (boş = halka açık)") },
            placeholder = { Text("wss://broker.ornek.com:8084/mqtt") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = fieldColors(c)
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Girilirse önce denenir, erişilemezse halka açık broker'lara düşer.",
            style = MaterialTheme.typography.bodySmall,
            color = c.muted
        )

        Spacer(Modifier.height(16.dp))

        SwitchRow(
            title = "Yalnızca TURN kullan",
            subtitle = "Doğrudan eşleşme atlanır, aktarım sunucusu üzerinden gidilir",
            checked = preferRelay,
            onCheckedChange = onPreferRelay,
            c = c
        )

        Spacer(Modifier.height(12.dp))

        SwitchRow(
            title = "Açık TURN yedeği",
            subtitle = "Özel TURN yoksa halka açık TURN sunucusu eklenir",
            checked = publicTurnFallback,
            onCheckedChange = onPublicTurnFallback,
            c = c
        )

        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = turnUrl,
            onValueChange = onTurnUrl,
            label = { Text("TURN sunucusu (boş = yok)") },
            placeholder = { Text("turn:ornek.com:3478") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = fieldColors(c)
        )

        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = turnUser,
                onValueChange = onTurnUser,
                label = { Text("TURN kullanıcı") },
                modifier = Modifier.weight(1f),
                singleLine = true,
                colors = fieldColors(c)
            )
            OutlinedTextField(
                value = turnPass,
                onValueChange = onTurnPass,
                label = { Text("TURN şifre") },
                modifier = Modifier.weight(1f),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                colors = fieldColors(c)
            )
        }

        Spacer(Modifier.height(24.dp))

        OutlinedButton(
            onClick = onBack,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            Text("Kaydet ve dön", style = MaterialTheme.typography.titleMedium, color = c.text)
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MakayTheme.c.accent,
        modifier = Modifier.padding(bottom = 12.dp)
    )
}

@Composable
private fun ThemeChip(
    label: String,
    selected: Boolean,
    modifier: Modifier,
    c: com.makay.remote.ui.theme.MakayColors,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(44.dp),
        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) c.accent.copy(alpha = 0.18f) else androidx.compose.ui.graphics.Color.Transparent
        ),
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = if (selected) c.accent else c.cardBorder
        )
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) c.accent else c.text
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    c: com.makay.remote.ui.theme.MakayColors
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = c.text)
            Spacer(Modifier.size(2.dp))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.muted)
        }
        Spacer(Modifier.size(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = c.accent,
                uncheckedTrackColor = c.input
            )
        )
    }
}

@Composable
private fun fieldColors(c: com.makay.remote.ui.theme.MakayColors) =
    OutlinedTextFieldDefaults.colors(
        focusedBorderColor = c.accent,
        unfocusedBorderColor = c.cardBorder,
        focusedTextColor = c.text,
        unfocusedTextColor = c.text,
        cursorColor = c.accent
    )
