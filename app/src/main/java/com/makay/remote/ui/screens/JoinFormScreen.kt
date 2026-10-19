package com.makay.remote.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ScreenShare
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.makay.remote.core.InviteParser
import com.makay.remote.ui.theme.MakayTheme

/** Uzun yapıştırma / davet metni mi? */
private fun looksLikeInvitePaste(raw: String): Boolean =
    raw.length > 12 ||
        raw.contains('\n') ||
        raw.contains("Kimlik", ignoreCase = true) ||
        raw.contains("Şifre", ignoreCase = true) ||
        raw.contains("Sifre", ignoreCase = true) ||
        raw.contains("Sinyal", ignoreCase = true)

/**
 * Ana ekran: "Bağlantıya Aç" + kimlik/şifre ile "Bağlan".
 *
 * Klavye akışı: Kimlik → İleri → Şifre → Bağlan (Go).
 * Şifrede göz ikonu ile göster/gizle.
 */
@Composable
fun JoinFormScreen(
    onHost: () -> Unit,
    onJoin: (invite: String, password: String) -> Unit,
    onSettings: () -> Unit = {}
) {
    val c = MakayTheme.c
    val focusManager = LocalFocusManager.current
    val clipboard = LocalClipboardManager.current
    val passwordFocus = remember { FocusRequester() }

    /* Windows her «Bağlantıya Aç»da yeni kimlik üretir — geçmiş/ön doldurma yok */
    var roomCode by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var signalUrl by remember { mutableStateOf("") }
    var pasteHint by remember { mutableStateOf<String?>(null) }

    fun applyInviteText(raw: String) {
        val parsed = InviteParser.parse(raw)
        if (parsed.code.isNotEmpty()) {
            // Ham 8 karakter — bosluk VisualTransformation ile gosterilir
            roomCode = parsed.code.filter { it.isLetterOrDigit() }.uppercase().take(8)
        } else {
            roomCode = raw.filter { it.isLetterOrDigit() }.uppercase().take(8)
        }
        if (parsed.password.isNotEmpty()) password = parsed.password
        signalUrl = parsed.signalUrl
        pasteHint = when {
            parsed.code.isNotEmpty() && parsed.password.isNotEmpty() ->
                "Davet yapıştırıldı — kimlik ve şifre dolduruldu"
            parsed.code.isNotEmpty() ->
                "Kimlik alındı — şifreyi girin"
            else -> null
        }
    }

    fun submitJoin() {
        val digits = roomCode.filter { it.isLetterOrDigit() }
        if (digits.length < 8 || password.isBlank()) return
        focusManager.clearFocus()
        val invite = buildString {
            append(digits)
            if (signalUrl.isNotEmpty()) {
                append("\nSinyal: ")
                append(signalUrl)
            }
        }
        onJoin(invite, password)
    }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = c.accent,
        unfocusedBorderColor = c.cardBorder,
        focusedTextColor = c.text,
        unfocusedTextColor = c.text,
        focusedLabelColor = c.accent,
        unfocusedLabelColor = c.muted,
        cursorColor = c.accent2,
        focusedContainerColor = c.input,
        unfocusedContainerColor = c.input
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "MAKAY Uzaktan Bağlantı",
                    style = MaterialTheme.typography.headlineSmall,
                    color = c.text
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Kimlik ve şifre ile bağlanın",
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.muted
                )
            }
            IconButton(onClick = onSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "Ayarlar", tint = c.muted)
            }
        }

        Spacer(Modifier.height(20.dp))

        Button(
            onClick = onHost,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = c.primary)
        ) {
            Icon(Icons.AutoMirrored.Filled.ScreenShare, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.size(8.dp))
            Text("Bağlantıya Aç", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Bu cihazın ekranı paylaşılır",
            style = MaterialTheme.typography.bodySmall,
            color = c.muted
        )

        Spacer(Modifier.height(22.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HorizontalDivider(modifier = Modifier.weight(1f), color = c.cardBorder)
            Text(
                text = "  veya bağlan  ",
                style = MaterialTheme.typography.labelMedium,
                color = c.muted
            )
            HorizontalDivider(modifier = Modifier.weight(1f), color = c.cardBorder)
        }
        Spacer(Modifier.height(16.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(c.card, RoundedCornerShape(16.dp))
                .border(1.dp, c.cardBorder, RoundedCornerShape(16.dp))
                .padding(16.dp)
        ) {
            Text(
                text = "Karşı bilgisayara bağlan",
                style = MaterialTheme.typography.titleMedium,
                color = c.text
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Windows’taki kimlik ve şifreyi girin",
                style = MaterialTheme.typography.bodySmall,
                color = c.muted
            )

            Spacer(Modifier.height(14.dp))

            OutlinedTextField(
                value = roomCode,
                onValueChange = { raw ->
                    if (looksLikeInvitePaste(raw)) {
                        applyInviteText(raw)
                        if (InviteParser.parse(raw).password.isNotEmpty()) {
                            passwordFocus.requestFocus()
                        }
                    } else {
                        // Yalnizca harf/rakam; bosluk EKLENMEZ (imlec kaymasin)
                        roomCode = raw.filter { it.isLetterOrDigit() }.uppercase().take(8)
                        pasteHint = null
                    }
                },
                label = { Text("Kimlik") },
                placeholder = { Text("Örn. AB12 CD34") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = RoomCodeVisualTransformation,
                textStyle = MaterialTheme.typography.titleMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Start
                ),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Next,
                    autoCorrectEnabled = false
                ),
                keyboardActions = KeyboardActions(
                    onNext = { passwordFocus.requestFocus() }
                ),
                colors = fieldColors,
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Şifre") },
                placeholder = { Text("Oturum şifresi") },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(passwordFocus),
                singleLine = true,
                visualTransformation = if (passwordVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            imageVector = if (passwordVisible) Icons.Filled.VisibilityOff
                            else Icons.Filled.Visibility,
                            contentDescription = if (passwordVisible) "Şifreyi gizle" else "Şifreyi göster",
                            tint = c.muted
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Go
                ),
                keyboardActions = KeyboardActions(
                    onGo = { submitJoin() }
                ),
                colors = fieldColors,
                shape = RoundedCornerShape(12.dp)
            )

            if (pasteHint != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = pasteHint!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = c.accent2
                )
            }

            val pathText = when {
                roomCode.isBlank() -> null
                signalUrl.isNotEmpty() -> "Yerel ağ — aynı Wi‑Fi üzerinden"
                else -> "Bulut eşleşmesi — internet kullanılır"
            }
            if (pathText != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = pathText,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (signalUrl.isNotEmpty()) c.accent else c.muted
                )
            }

            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = {
                    val clip = clipboard.getText()?.text.orEmpty()
                    if (clip.isNotBlank()) {
                        applyInviteText(clip)
                        passwordFocus.requestFocus()
                    } else {
                        pasteHint = "Panoda metin yok — önce daveti kopyalayın"
                    }
                },
                modifier = Modifier.align(Alignment.Start)
            ) {
                Icon(Icons.Filled.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(6.dp))
                Text("Daveti yapıştır", color = c.accent2)
            }

            Spacer(Modifier.height(8.dp))

            OutlinedButton(
                onClick = { submitJoin() },
                enabled = roomCode.length >= 8 && password.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Filled.Computer, contentDescription = null, modifier = Modifier.size(20.dp), tint = c.text)
                Spacer(Modifier.size(8.dp))
                Text("Bağlan", style = MaterialTheme.typography.titleMedium, color = c.text)
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(
            text = "Windows’ta her «Bağlantıya Aç» yeni kimlik üretir — daveti yapıştırın veya yazın.",
            style = MaterialTheme.typography.bodySmall,
            color = c.muted,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
    }
}
