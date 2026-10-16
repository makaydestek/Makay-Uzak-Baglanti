package com.makay.remote.ui.screens

import android.app.Application
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.viewmodel.compose.viewModel
import com.makay.remote.BuildConfig
import com.makay.remote.R
import com.makay.remote.update.AppUpdateUiState
import com.makay.remote.update.AppUpdateViewModel
import com.makay.remote.update.findActivity
import com.makay.remote.ui.theme.MakayColors
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

    // Guncelleme ekrani (AppUpdateHost) ile AYNI ViewModel ornegi kullanilir;
    // ViewModel Activity'ye baglidir, boylece buradan yapilan kontrol sonucu
    // MainActivity'deki ayni diyalogda gosterilir.
    val ctx = LocalContext.current
    val app = ctx.applicationContext as Application
    val updateVm: AppUpdateViewModel? = (ctx.findActivity() as? FragmentActivity)?.let { fa ->
        viewModel(viewModelStoreOwner = fa, factory = AppUpdateViewModel.Factory(app))
    }
    val updateState = updateVm?.state?.collectAsState()?.value

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

        // ---------- Güncelleme ----------
        // Makay ekosistemindeki diger uygulamalarla (Cleaner, File Manager,
        // Launcher) ayni akis: kontrol -> "Guncelle / Ertele" diyalogu ->
        // uygulama ici indirme + imza/SHA-256 dogrulamasi -> sistem kurulum
        // ekrani. Tarayiciya YONLENDIRME YAPILMAZ.
        UpdateCheckButton(c, updateVm, updateState)

        Spacer(Modifier.height(16.dp))

        OutlinedButton(
            onClick = onBack,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            Text("Kaydet ve dön", style = MaterialTheme.typography.titleMedium, color = c.text)
        }

        Spacer(Modifier.height(32.dp))

        // ---------- Hakkında ----------
        // Makay eko sistemindeki diger uygulamalarla (Makay Cleaner, Makay
        // FileManager) birebir ayni yapidadir: baslik + kart icinde
        // uygulama adi / surum / gelistirici / telif metni.
        AboutSection(c)

        Spacer(Modifier.height(24.dp))
    }
}

/**
 * "Güncelleme kontrol et" düğmesi — Makay ekosistemindeki diğer uygulamaların
 * Ayarlar'daki güncelleme satırıyla aynı işlevi görür.
 *
 * Basıldığında `AppUpdateViewModel.checkManual()` çağrılır; sonuç, uygulama
 * açılışında MainActivity'nin `AppUpdateHost`'una bağladığı AYNI diyalogda
 * gösterilir. İndirme + doğrulama + kurulum uygulama içinden yapılır,
 * tarayıcı açılmaz.
 */
@Composable
private fun UpdateCheckButton(
    c: MakayColors,
    updateVm: AppUpdateViewModel?,
    updateState: AppUpdateUiState?
) {
    val busy = updateState?.checking == true || updateState?.downloading == true

    OutlinedButton(
        onClick = { updateVm?.checkManual() },
        enabled = updateVm != null && !busy,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
    ) {
        Text(
            text = when {
                updateState?.checking == true -> "🔄 Kontrol ediliyor…"
                updateState?.downloading == true -> "%${updateState.progress} indiriliyor…"
                else -> "🔄 Güncelleme kontrol et"
            },
            style = MaterialTheme.typography.titleMedium,
            color = c.text
        )
    }

    val msg = updateState?.statusMessage ?: updateState?.error
    if (msg != null) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = msg,
            style = MaterialTheme.typography.bodySmall,
            color = if (updateState?.error != null) c.danger else c.muted
        )
    }
}

/**
 * "Hakkında" bolumu — eko sistemin ortak Ayarlar>Hakkinda yapisi.
 *
 *  - Uygulama adi  : R.string.app_name (basligiyla senkron)
 *  - Surum         : BuildConfig.VERSION_NAME (VERSION.txt ile ayni)
 *  - Gelistirici   : ortak Makay metni
 *  - Telif         : ortak Makay metni
 */
@Composable
private fun AboutSection(c: MakayColors) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Rozet: resimdekiyle ayni sekilde yuksel kontrastli — acik temada
        // koyu kutu + acik "i", koyu temada acik kutu + koyu "i".
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(c.text),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
                tint = c.bg,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = "Hakkında",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = c.text
        )
    }

    Spacer(Modifier.height(12.dp))

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = c.card),
        border = BorderStroke(1.dp, c.cardBorder)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = c.text
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Sürüm: ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodyMedium,
                color = c.muted
            )
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = c.cardBorder)
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Geliştirici",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = c.text
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Bu uygulamanın tasarımı ve geliştirmesi Murat AKAY tarafından yapılmıştır.",
                style = MaterialTheme.typography.bodyMedium,
                color = c.muted
            )
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = c.cardBorder)
            Spacer(Modifier.height(12.dp))
            Text(
                text = "© 2026 Tüm hakları saklıdır. Sideload / yerel dağıtım için hazırlanmıştır.",
                style = MaterialTheme.typography.bodySmall,
                color = c.muted
            )
        }
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
    c: MakayColors,
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
    c: MakayColors
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
