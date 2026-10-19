package com.makay.remote.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.provider.OpenableColumns
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.makay.remote.rtc.ControlPayload
import com.makay.remote.session.SessionController
import com.makay.remote.session.SessionViewModel
import com.makay.remote.ui.theme.MakayTheme
import kotlinx.coroutines.delay
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Bagli oturum ekrani: uzak video + sohbet + kontrol + dosya gonderimi.
 *
 * - Host (asHost=true): kendi ekranini paylasir (MediaProjection Adim 2d),
 *   karsi tarafdaki kontrol istemini onaylar.
 * - Teknisyen (asHost=false): uzak videoyu gorur, dokunarak mouse, yazarak
 *   klavye kontrolu gonderir.
 */
@Composable
fun SessionScreen(vm: SessionViewModel, peerName: String, asHost: Boolean) {
    val c = MakayTheme.c
    val chat by vm.chat.collectAsState()
    val controlState by vm.controlState.collectAsState()
    val remoteVideo by vm.remoteVideo.collectAsState()
    val ctrlSent by vm.ctrlSent.collectAsState()
    val fileProgress by vm.fileProgress.collectAsState()
    val peerMic by vm.peerMicState.collectAsState()
    val linkQuality by vm.linkQuality.collectAsState()
    val context = LocalContext.current
    var showKeyStrip by remember { mutableStateOf(false) }

    // --- pano / ses durumlari ---
    val inboundClip by vm.inboundClipboard.collectAsState()
    val micOn by vm.micState.collectAsState()
    val speakerOn by vm.speakerOn.collectAsState()

    var input by remember { mutableStateOf("") }
    var touchMode by remember { mutableStateOf(TouchMode.NORMAL) }
    /* Dogudan: dokundugun yer = tik (Explorer klasor/disk acmak icin dogal) */
    var inputMode by remember { mutableStateOf(InputMode.DIRECT) }
    /* Misafir: baglaninca immersif; video ASLA agactan cikmaz (SurfaceView kaybi) */
    var fullscreen by remember { mutableStateOf(!asHost) }
    var showHint by remember { mutableStateOf(true) }
    var showKeyboard by remember { mutableStateOf(false) }
    var keyboardText by remember { mutableStateOf("") }
    var showChat by remember { mutableStateOf(false) }
    var toolsExpanded by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val view = LocalView.current
    /* Fit sifirlamak icin RemoteVideoBox'a sinyal */
    var fitNonce by remember { mutableStateOf(0) }

    /* Geri: once tam ekrandan cik, sonra baglantiyi kopar (uygulamadan cikma) */
    BackHandler {
        when {
            showKeyboard -> showKeyboard = false
            showChat -> showChat = false
            fullscreen && !asHost -> fullscreen = false
            else -> vm.disconnect()
        }
    }

    /* Jest ipucu: baglantidan birka sn sonra gizle */
    LaunchedEffect(controlState) {
        if (controlState == SessionController.ControlState.GRANTED) {
            showHint = true
            delay(3500)
            showHint = false
        }
    }

    /* Tam ekran: sistem cubuklarini gizle; video boyutunu bozma */
    DisposableEffect(fullscreen) {
        val window = (view.context as? android.app.Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        if (fullscreen && !asHost) {
            controller?.hide(WindowInsetsCompat.Type.systemBars())
            controller?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // --- mikrofon izni (yalnizca acilista istenir) ---
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) vm.toggleMic()
        else android.widget.Toast.makeText(
            context, "Mikrofon izni verilmedi", android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    LaunchedEffect(chat.size) {
        if (chat.isNotEmpty()) listState.animateScrollToItem(chat.size - 1)
    }

    // --- ekran paylasimi (yalnizca host) ---
    var sharing by remember { mutableStateOf(false) }
    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
            com.makay.remote.service.ScreenShareService.start(
                context, result.resultCode, result.data!!
            )
            sharing = true
        }
    }
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Izin verilmese de servis calisir; sadece bildirim gorunurlugu etkilenir
        val mpm = context.getSystemService(android.content.Context.MEDIA_PROJECTION_SERVICE)
            as android.media.projection.MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    // --- dosya secici ---
    val pickFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val displayName = queryDisplayName(context, uri) ?: "dosya"
            val tmp = copyToCache(context, uri, displayName) ?: return@rememberLauncherForActivityResult
            val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
            vm.sendFile(tmp, mime)
        }
    }

    /* Video her zaman alt katmanda kalir — tam ekran ac/kapa SurfaceView'i yok etmez */
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .imePadding()
    ) {
        RemoteVideoBox(
            track = remoteVideo,
            eglContext = vm.eglContext(),
            controlEnabled = controlState == SessionController.ControlState.GRANTED,
            touchMode = touchMode,
            inputMode = inputMode,
            fitNonce = fitNonce,
            onControl = { payload ->
                if (payload.x != null && payload.y != null) {
                    vm.notePointer(payload.x!!, payload.y!!)
                }
                vm.sendControl(payload)
            },
            modifier = Modifier.fillMaxSize()
        )

        /* Kisa jest ipucu — baglanti sonrasi otomatik gizlenir */
        if (!asHost && showHint && controlState == SessionController.ControlState.GRANTED) {
            Text(
                text = when {
                    touchMode == TouchMode.DRAG -> "Sürükle · basılı kaydırın"
                    inputMode == InputMode.TOUCHPAD ->
                        "Touchpad · kaydır=imleç · dokun=tık · çift dokun=aç · basılı=sağ tık"
                    else -> "Doğrudan · dokun=tık · çift dokun=klasör/disk aç · basılı=sağ tık"
                },
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }

        /* Baglanti kalitesi rozeti */
        if (linkQuality.level != "unknown" && linkQuality.label.isNotBlank()) {
            val qColor = when (linkQuality.level) {
                "good" -> Color(0xFF4CAF50)
                "ok" -> Color(0xFFFFC107)
                "poor" -> Color(0xFFF44336)
                else -> Color.White
            }
            Text(
                text = linkQuality.label,
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(start = 8.dp, top = 48.dp)
                    .background(qColor.copy(alpha = 0.85f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }

        /* Ust cubuk: her zaman gorunur (yatayda X kaybolmasin) */
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(4.dp)
                .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(20.dp))
                .padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!asHost && controlState == SessionController.ControlState.GRANTED) {
                IconButton(
                    onClick = {
                        inputMode = if (inputMode == InputMode.TOUCHPAD)
                            InputMode.DIRECT else InputMode.TOUCHPAD
                    },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        if (inputMode == InputMode.TOUCHPAD) Icons.Filled.TouchApp else Icons.Filled.Mouse,
                        contentDescription = if (inputMode == InputMode.TOUCHPAD) "Touchpad" else "Doğrudan",
                        tint = Color.White
                    )
                }
                IconButton(
                    onClick = { fitNonce++ },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(Icons.Filled.FitScreen, contentDescription = "Sığdır", tint = Color.White)
                }
            }
            IconButton(
                onClick = { fullscreen = !fullscreen },
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    if (fullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                    contentDescription = if (fullscreen) "Paneli aç" else "Tam ekran",
                    tint = Color.White
                )
            }
            IconButton(
                onClick = { vm.disconnect() },
                modifier = Modifier.size(40.dp)
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Bağlantıyı kapat", tint = c.danger)
            }
        }

        fileProgress?.let { fp ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(8.dp)
                    .fillMaxWidth()
                    .background(c.card.copy(alpha = 0.92f), RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        fp.name,
                        style = MaterialTheme.typography.labelMedium,
                        color = c.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    LinearProgressIndicator(
                        progress = { (fp.percent / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                    )
                    Text("%${fp.percent}", style = MaterialTheme.typography.labelSmall, color = c.muted)
                }
                TextButton(onClick = { vm.cancelFileTransfer(fp.fid) }) {
                    Text("İptal", color = c.danger)
                }
            }
        }

        /* Kompakt alt cubuk — tam ekranda gizli; video yerinde kalir */
        if (!fullscreen) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .background(c.bg.copy(alpha = 0.92f))
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                Text(
                    peerName,
                    style = MaterialTheme.typography.labelMedium,
                    color = c.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                /* Tek satir ikon cubugu */
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (asHost) {
                        CompactIconBtn(
                            icon = Icons.Filled.Fullscreen,
                            tint = if (sharing) c.warn else c.accent,
                            desc = if (sharing) "Paylaşımı durdur" else "Ekranı paylaş"
                        ) {
                            if (sharing) {
                                vm.stopScreenShare()
                                sharing = false
                            } else if (android.os.Build.VERSION.SDK_INT >= 33) {
                                notifPermissionLauncher.launch(
                                    android.Manifest.permission.POST_NOTIFICATIONS
                                )
                            } else {
                                val mpm = context.getSystemService(
                                    android.content.Context.MEDIA_PROJECTION_SERVICE
                                ) as android.media.projection.MediaProjectionManager
                                projectionLauncher.launch(mpm.createScreenCaptureIntent())
                            }
                        }
                    } else {
                        CompactIconBtn(
                            icon = Icons.Filled.Mouse,
                            tint = when (controlState) {
                                SessionController.ControlState.GRANTED -> c.accent
                                SessionController.ControlState.REQUESTED -> c.warn
                                else -> c.muted
                            },
                            desc = "Kontrol"
                        ) {
                            when (controlState) {
                                SessionController.ControlState.GRANTED -> vm.stopControl()
                                SessionController.ControlState.REQUESTED -> Unit
                                else -> vm.requestControl()
                            }
                        }
                    }

                    if (!asHost && controlState == SessionController.ControlState.GRANTED) {
                        CompactIconBtn(
                            icon = Icons.Filled.TouchApp,
                            tint = if (touchMode == TouchMode.DRAG) c.warn else c.muted,
                            desc = "Sürükle"
                        ) {
                            touchMode = if (touchMode == TouchMode.DRAG)
                                TouchMode.NORMAL else TouchMode.DRAG
                        }
                        CompactIconBtn(
                            icon = Icons.Filled.Keyboard,
                            tint = if (showKeyStrip) c.accent else c.muted,
                            desc = "Klavye"
                        ) {
                            showKeyStrip = !showKeyStrip
                            if (!showKeyStrip) showKeyboard = false
                        }
                    }

                    CompactIconBtn(
                        icon = if (micOn) Icons.Filled.Mic else Icons.Filled.MicOff,
                        tint = if (micOn) c.accent else c.muted,
                        desc = "Mikrofon"
                    ) {
                        if (micOn) vm.toggleMic()
                        else if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                            != android.content.pm.PackageManager.PERMISSION_GRANTED
                        ) {
                            micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                        } else vm.toggleMic()
                    }

                    CompactIconBtn(
                        icon = if (speakerOn) Icons.AutoMirrored.Filled.VolumeUp
                        else Icons.AutoMirrored.Filled.VolumeOff,
                        tint = if (speakerOn) c.accent else c.muted,
                        desc = "Hoparlör"
                    ) { vm.setSpeaker(!speakerOn) }

                    CompactIconBtn(
                        icon = Icons.Filled.AttachFile,
                        tint = c.muted,
                        desc = "Dosya"
                    ) { pickFile.launch(arrayOf("*/*")) }

                    CompactIconBtn(
                        icon = Icons.AutoMirrored.Filled.Chat,
                        tint = if (showChat) c.accent else c.muted,
                        desc = "Sohbet"
                    ) { showChat = !showChat }

                    CompactIconBtn(
                        icon = Icons.Filled.ContentPaste,
                        tint = if (toolsExpanded) c.accent else c.muted,
                        desc = "Diğer"
                    ) { toolsExpanded = !toolsExpanded }
                }

                if (showKeyStrip && !asHost && controlState == SessionController.ControlState.GRANTED) {
                    Spacer(Modifier.height(4.dp))
                    KeyStrip(
                        onKey = { vm.sendNamedKey(it) },
                        onChord = { keys -> vm.sendKeyChord(*keys) },
                        onType = { showKeyboard = true },
                        c = c
                    )
                }

                if (toolsExpanded && !asHost && controlState == SessionController.ControlState.GRANTED) {
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedButton(
                            onClick = { vm.sendRightClick() },
                            modifier = Modifier.weight(1f)
                        ) { Text("Sağ tık", style = MaterialTheme.typography.labelSmall) }
                        OutlinedButton(
                            onClick = { vm.sendCopy() },
                            modifier = Modifier.weight(1f)
                        ) { Text("Kopyala", style = MaterialTheme.typography.labelSmall) }
                        OutlinedButton(
                            onClick = { vm.sendPaste() },
                            modifier = Modifier.weight(1f)
                        ) { Text("Yapıştır", style = MaterialTheme.typography.labelSmall) }
                        OutlinedButton(
                            onClick = {
                                val cm = context.getSystemService(
                                    android.content.Context.CLIPBOARD_SERVICE
                                ) as ClipboardManager
                                val clip = cm.primaryClip
                                val text = if (clip != null && clip.itemCount > 0) {
                                    clip.getItemAt(0).coerceToText(context).toString()
                                } else ""
                                vm.sendClipboard(text)
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("Pano", style = MaterialTheme.typography.labelSmall) }
                    }
                }

                if (showChat) {
                    Spacer(Modifier.height(6.dp))
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .background(c.card, RoundedCornerShape(10.dp))
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(chat) { m -> ChatBubble(m, mine = m.mine, c = c) }
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            modifier = Modifier.weight(1f).height(48.dp),
                            placeholder = { Text("Mesaj…", color = c.muted) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = c.accent,
                                unfocusedBorderColor = c.cardBorder,
                                focusedTextColor = c.text,
                                unfocusedTextColor = c.text
                            )
                        )
                        Spacer(Modifier.width(6.dp))
                        IconButton(
                            onClick = { if (vm.sendChat(input)) input = "" },
                            modifier = Modifier
                                .size(40.dp)
                                .background(c.primary, CircleShape)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Gönder",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    } // Box

    if (showKeyboard) {
        AlertDialog(
            onDismissRequest = { showKeyboard = false },
            containerColor = c.bg2,
            title = { Text("Uzak klavye", color = c.text) },
            text = {
                OutlinedTextField(
                    value = keyboardText,
                    onValueChange = { keyboardText = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Yazıp gönderin…", color = c.muted) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = c.accent,
                        unfocusedBorderColor = c.cardBorder,
                        focusedTextColor = c.text,
                        unfocusedTextColor = c.text
                    )
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (keyboardText.isNotEmpty()) {
                            vm.typeText(keyboardText)
                            keyboardText = ""
                        }
                        showKeyboard = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = c.primary)
                ) { Text("Gönder") }
            },
            dismissButton = {
                TextButton(onClick = { showKeyboard = false }) {
                    Text("Kapat", color = c.muted)
                }
            }
        )
    }

    // ---------- gelen pano onayi (masaustu clipModal karsiligi) ----------
    if (inboundClip != null) {
        val shown = inboundClip!!.let { if (it.length > 240) it.slice(0 until 240) + "…" else it }
        AlertDialog(
            onDismissRequest = { vm.clearInboundClipboard() },
            containerColor = c.bg2,
            title = { Text("Pano içeriği", color = c.text) },
            text = {
                Column {
                    Text(
                        "Karşı taraf panonuza metin yazmak istiyor:",
                        color = c.muted,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        color = c.card,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            shown,
                            color = c.text,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("MAKAY", inboundClip))
                        vm.clearInboundClipboard()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = c.success)
                ) { Text("Panoya Yaz") }
            },
            dismissButton = {
                TextButton(onClick = { vm.clearInboundClipboard() }) {
                    Text("Reddet", color = c.danger)
                }
            }
        )
    }
}

// ---------------- video kutusu ----------------

/**
 * Uzak video + kontrol.
 *
 * Windows `app.js:mapCoords` ile ayni mantik: object-fit:contain letterbox
 * hesabı. Ic kutu video en-boyuna gore boyutlanir (gorev cubugu / X kesilmez);
 * pinch zoom bu kutuyu buyutur; 2 parmak kaydirir.
 */
private enum class TouchMode { NORMAL, DRAG }

/** AnyDesk: TOUCHPAD = kaydirarak imlec (kucuk ekran); DIRECT = dokundugun yer. */
private enum class InputMode { TOUCHPAD, DIRECT }

private data class ContentRect(val left: Float, val top: Float, val w: Float, val h: Float)

@Composable
private fun CompactIconBtn(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    desc: String,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick, modifier = Modifier.size(42.dp)) {
        Icon(icon, contentDescription = desc, tint = tint, modifier = Modifier.size(22.dp))
    }
}

/** AnyDesk tarzı hızlı tuş şeridi — InputRelay adlarıyla birebir. */
@Composable
private fun KeyStrip(
    onKey: (String) -> Unit,
    onChord: (Array<String>) -> Unit,
    onType: () -> Unit,
    c: com.makay.remote.ui.theme.MakayColors
) {
    val keys = listOf(
        "Esc" to "escape",
        "Tab" to "tab",
        "Win" to "win",
        "⌫" to "backspace",
        "↵" to "enter",
        "↑" to "arrowup",
        "↓" to "arrowdown",
        "←" to "arrowleft",
        "→" to "arrowright"
    )
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            keys.forEach { (label, name) ->
                OutlinedButton(
                    onClick = { onKey(name) },
                    modifier = Modifier.weight(1f),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                ) {
                    Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            OutlinedButton(
                onClick = { onChord(arrayOf("control", "c")) },
                modifier = Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)
            ) { Text("Ctrl+C", style = MaterialTheme.typography.labelSmall) }
            OutlinedButton(
                onClick = { onChord(arrayOf("control", "v")) },
                modifier = Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)
            ) { Text("Ctrl+V", style = MaterialTheme.typography.labelSmall) }
            OutlinedButton(
                onClick = { onChord(arrayOf("control", "a")) },
                modifier = Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)
            ) { Text("Ctrl+A", style = MaterialTheme.typography.labelSmall) }
            OutlinedButton(
                onClick = { onChord(arrayOf("control", "z")) },
                modifier = Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)
            ) { Text("Ctrl+Z", style = MaterialTheme.typography.labelSmall) }
            OutlinedButton(
                onClick = onType,
                modifier = Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)
            ) { Text("Yaz…", style = MaterialTheme.typography.labelSmall, color = c.accent) }
        }
    }
}

@Composable
private fun RemoteVideoBox(
    track: VideoTrack?,
    eglContext: EglBase.Context?,
    controlEnabled: Boolean,
    touchMode: TouchMode,
    inputMode: InputMode,
    fitNonce: Int = 0,
    onControl: (ControlPayload) -> Unit,
    modifier: Modifier = Modifier
) {
    val c = MakayTheme.c
    val controlBorder = if (controlEnabled) Modifier.border(2.dp, c.primary) else Modifier
    val holder = remember { VideoBoxHolder() }
    holder.touchMode = touchMode
    holder.inputMode = inputMode

    LaunchedEffect(fitNonce) {
        if (fitNonce > 0) {
            holder.fitReset()
            holder.syncCursorView()
        }
    }

    Box(
        modifier
            .then(controlBorder)
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        if (track != null && eglContext != null) {
            DisposableEffect(track) {
                holder.bindTrack(track)
                onDispose { holder.unbindTrack(track) }
            }

            androidx.compose.ui.viewinterop.AndroidView(
                factory = { ctx ->
                    FrameLayout(ctx).also { viewport ->
                        viewport.clipChildren = true
                        viewport.clipToPadding = true
                        viewport.setBackgroundColor(android.graphics.Color.BLACK)

                        val inner = FrameLayout(ctx)
                        val r = SurfaceViewRenderer(ctx)
                        val ok = runCatching {
                            r.init(
                                eglContext,
                                object : RendererCommon.RendererEvents {
                                    override fun onFirstFrameRendered() = Unit
                                    override fun onFrameResolutionChanged(
                                        videoWidth: Int,
                                        videoHeight: Int,
                                        rotation: Int
                                    ) {
                                        val rot = ((rotation % 360) + 360) % 360
                                        if (rot == 90 || rot == 270) {
                                            holder.frameW = videoHeight.coerceAtLeast(1)
                                            holder.frameH = videoWidth.coerceAtLeast(1)
                                        } else {
                                            holder.frameW = videoWidth.coerceAtLeast(1)
                                            holder.frameH = videoHeight.coerceAtLeast(1)
                                        }
                                        viewport.post { holder.applyViewTransform() }
                                    }
                                }
                            )
                            /* Ic kutu zaten video oraninda — FILL = tam kare, kirpma yok */
                            r.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                            r.setEnableHardwareScaler(true)
                            r.setZOrderOnTop(false)
                            r.setZOrderMediaOverlay(false)
                            inner.addView(
                                r,
                                FrameLayout.LayoutParams(
                                    FrameLayout.LayoutParams.MATCH_PARENT,
                                    FrameLayout.LayoutParams.MATCH_PARENT
                                )
                            )
                            track.addSink(r)
                            holder.renderer = r
                            holder.initOk = true
                            holder.sinkTrack = track
                        }.isSuccess
                        holder.initOk = ok

                        viewport.addView(
                            inner,
                            FrameLayout.LayoutParams(0, 0)
                        )
                        holder.inner = inner
                        holder.root = viewport

                        val touch = View(ctx)
                        touch.isClickable = true
                        touch.isFocusable = true
                        viewport.addView(
                            touch,
                            FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.MATCH_PARENT
                            )
                        )
                        holder.touch = touch

                        /* Touchpad imlec: ince halka — merkez = gonderilen (x,y), Windows imleciyle hizali */
                        val cursor = View(ctx).apply {
                            background = android.graphics.drawable.GradientDrawable().apply {
                                shape = android.graphics.drawable.GradientDrawable.OVAL
                                setColor(android.graphics.Color.TRANSPARENT)
                                setStroke(3, android.graphics.Color.argb(230, 0, 180, 255))
                            }
                            visibility = View.GONE
                        }
                        val cursorLp = FrameLayout.LayoutParams(22, 22)
                        viewport.addView(cursor, cursorLp)
                        holder.cursorView = cursor

                        viewport.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                            holder.applyViewTransform()
                            holder.syncCursorView()
                        }
                        viewport.post {
                            holder.applyViewTransform()
                            holder.syncCursorView()
                        }
                    }
                },
                update = { _ ->
                    holder.touchMode = touchMode
                    holder.inputMode = inputMode
                    holder.touch?.setOnTouchListener(
                        if (controlEnabled) controlTouchListener(onControl, holder)
                        else null
                    )
                    /* Dogudan modda Windows imleci videoda gorunur — mavi halka sadece touchpad */
                    holder.cursorView?.visibility =
                        if (controlEnabled && inputMode == InputMode.TOUCHPAD) View.VISIBLE
                        else View.GONE
                    holder.applyViewTransform()
                    holder.syncCursorView()
                },
                onRelease = { _ ->
                    holder.touch?.setOnTouchListener(null)
                    holder.release()
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            androidx.compose.ui.viewinterop.AndroidView(
                factory = { ctx -> View(ctx) },
                update = { v ->
                    holder.touchMode = touchMode
                    v.setOnTouchListener(
                        if (controlEnabled) controlTouchListener(onControl, holder)
                        else null
                    )
                },
                modifier = Modifier.fillMaxSize()
            )
            Text(
                text = when {
                    !controlEnabled && track == null -> "Bağlantı hazır — görüntülü ekran bekleniyor…"
                    controlEnabled && track == null -> "Kontrol açık — ekran görüntüsü bekleniyor\n(Windows ekran paylaşımını açmış olmalı)"
                    else -> "Video hazırlanıyor…"
                },
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}

private class VideoBoxHolder {
    var root: FrameLayout? = null
    var inner: FrameLayout? = null
    var renderer: SurfaceViewRenderer? = null
    var touch: View? = null
    var cursorView: View? = null
    var sinkTrack: VideoTrack? = null
    var initOk: Boolean = false
    var touchMode: TouchMode = TouchMode.NORMAL
    var inputMode: InputMode = InputMode.TOUCHPAD
    /** Uzak kare boyutu (RendererEvents). Bilinmiyorsa 16:9. */
    var frameW: Int = 1920
    var frameH: Int = 1080
    /** Touchpad imlec konumu (uzak masaustu 0..1). */
    var cursorX: Float = 0.5f
    var cursorY: Float = 0.5f
    val gesture = ControlGestureState()

    fun syncCursorView() {
        val viewport = root ?: return
        val cur = cursorView ?: return
        val vw = viewport.width.toFloat()
        val vh = viewport.height.toFloat()
        if (vw < 1f || vh < 1f) return
        val c = contentRect(vw, vh)
        val px = c.left + cursorX * c.w - cur.layoutParams.width / 2f
        val py = c.top + cursorY * c.h - cur.layoutParams.height / 2f
        cur.translationX = px
        cur.translationY = py
    }

    fun bindTrack(track: VideoTrack) {
        val r = renderer ?: return
        val old = sinkTrack
        if (old != null && old !== track) runCatching { old.removeSink(r) }
        if (initOk) {
            runCatching { track.addSink(r) }
            sinkTrack = track
        }
    }

    fun unbindTrack(track: VideoTrack) {
        val r = renderer ?: return
        runCatching { track.removeSink(r) }
        if (sinkTrack === track) sinkTrack = null
    }

    /**
     * Windows mapCoords / object-fit:contain ile ayni:
     * 1x = tum masaustu (X + gorev cubugu) viewport'a sigar.
     * zoom > 1 = ayni oranda buyur; pan ile kenara kay.
     */
    fun contentRect(viewW: Float, viewH: Float): ContentRect {
        val g = gesture
        val zoom = g.scale.coerceIn(1f, 5f)
        val aspect = frameW.toFloat() / frameH.toFloat().coerceAtLeast(1f)
        var cw = viewW
        var ch = viewW / aspect
        if (ch > viewH) {
            ch = viewH
            cw = viewH * aspect
        }
        cw *= zoom
        ch *= zoom
        clampPan(viewW, viewH, cw, ch)
        val left = (viewW - cw) / 2f + g.panX
        val top = (viewH - ch) / 2f + g.panY
        return ContentRect(left, top, cw, ch)
    }

    fun applyViewTransform() {
        val viewport = root ?: return
        val box = inner ?: return
        val vw = viewport.width.toFloat()
        val vh = viewport.height.toFloat()
        if (vw < 1f || vh < 1f) return
        val c = contentRect(vw, vh)
        val lp = (box.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(c.w.toInt(), c.h.toInt())
        lp.width = c.w.toInt().coerceAtLeast(1)
        lp.height = c.h.toInt().coerceAtLeast(1)
        lp.leftMargin = c.left.toInt()
        lp.topMargin = c.top.toInt()
        lp.rightMargin = 0
        lp.bottomMargin = 0
        box.layoutParams = lp
    }

    fun clampPan(viewW: Float, viewH: Float, contentW: Float = -1f, contentH: Float = -1f) {
        val g = gesture
        val c = if (contentW > 0f) ContentRect(0f, 0f, contentW, contentH) else {
            val aspect = frameW.toFloat() / frameH.toFloat().coerceAtLeast(1f)
            val zoom = g.scale.coerceIn(1f, 5f)
            var cw = viewW
            var ch = viewW / aspect
            if (ch > viewH) {
                ch = viewH
                cw = viewH * aspect
            }
            ContentRect(0f, 0f, cw * zoom, ch * zoom)
        }
        val maxX = ((c.w - viewW) / 2f).coerceAtLeast(0f)
        val maxY = ((c.h - viewH) / 2f).coerceAtLeast(0f)
        g.panX = g.panX.coerceIn(-maxX, maxX)
        g.panY = g.panY.coerceIn(-maxY, maxY)
        if (g.scale <= 1.01f) {
            g.scale = 1f
            g.panX = 0f
            g.panY = 0f
        }
    }

    /** Viewport dokunmasi → uzak masaustu [0,1] (letterbox/zoom/pan dahil). */
    fun mapToRemote(viewX: Float, viewY: Float, viewW: Float, viewH: Float): Pair<Double, Double> {
        val c = contentRect(viewW, viewH)
        if (c.w < 1f || c.h < 1f) return 0.5 to 0.5
        val nx = ((viewX - c.left) / c.w).coerceIn(0f, 1f).toDouble()
        val ny = ((viewY - c.top) / c.h).coerceIn(0f, 1f).toDouble()
        return nx to ny
    }

    fun fitReset() {
        gesture.scale = 1f
        gesture.panX = 0f
        gesture.panY = 0f
        applyViewTransform()
    }

    fun release() {
        val r = renderer
        val t = sinkTrack
        if (r != null && t != null) runCatching { t.removeSink(r) }
        if (initOk && r != null) runCatching { r.release() }
        renderer = null
        touch = null
        cursorView = null
        inner = null
        root = null
        sinkTrack = null
        initOk = false
        frameW = 1920
        frameH = 1080
        cursorX = 0.5f
        cursorY = 0.5f
        gesture.resetAll()
    }
}

private class ControlGestureState {
    var downX = 0f
    var downY = 0f
    var movedFar = false
    var dragArmed = false
    var viewPanMode = false
    var multiTouch = false
    var wheelMode = false
    var scale = 1f
    var panX = 0f
    var panY = 0f
    var lastPanX = 0f
    var lastPanY = 0f
    var lastTapUpAt = 0L
    var lastTapX = 0.5f
    var lastTapY = 0.5f
    var longPressPosted = false
    var pendingSingleClick: Runnable? = null

    fun reset() {
        movedFar = false
        dragArmed = false
        viewPanMode = false
        multiTouch = false
        wheelMode = false
        longPressPosted = false
    }

    fun resetAll() {
        reset()
        scale = 1f
        panX = 0f
        panY = 0f
        lastTapUpAt = 0L
        pendingSingleClick = null
    }
}

private const val TAP_SLOP_PX = 48f
/** Touchpad: ekran genisliginin tamamini kaydirmak ≈ masaustunun bu kadari. */
private const val TOUCHPAD_GAIN = 2.4f
private const val LONG_PRESS_MS = 480L
private const val DOUBLE_TAP_MS = 320L
private const val DOUBLE_CLICK_GAP_MS = 45L
private const val DOUBLE_TAP_POS = 0.045f

/**
 * AnyDesk / TeamViewer mobil jestleri:
 * - Touchpad: kaydir = imlec (goreli), dokun = sol tik, cift dokun = cift tik (klasor ac),
 *   basili tut = sag tik, 2 parmak kaydir = tekerlek, pinch = zoom
 * - Dogrudan: dokundugun yer = imlec (mutlak) — Windows imleciyle ayni nokta
 * - Surukle modu: basili sol tus
 */
private fun controlTouchListener(
    onControl: (ControlPayload) -> Unit,
    holder: VideoBoxHolder
): View.OnTouchListener {
    val g = holder.gesture
    var scaleDetector: ScaleGestureDetector? = null
    val longPress = Runnable {
        if (!g.movedFar && !g.multiTouch && holder.touchMode == TouchMode.NORMAL) {
            g.longPressPosted = false
            g.movedFar = true /* UP'ta sol tik atmasin */
            g.pendingSingleClick?.let { holder.touch?.removeCallbacks(it) }
            g.pendingSingleClick = null
            val x = holder.cursorX.toDouble()
            val y = holder.cursorY.toDouble()
            onControl(ControlPayload(ControlPayload.MOUSE_MOVE, x = x, y = y))
            onControl(ControlPayload(ControlPayload.MOUSE_DOWN, x = x, y = y, b = "right"))
            onControl(ControlPayload(ControlPayload.MOUSE_UP, x = x, y = y, b = "right"))
        }
    }

    fun pointer(): Pair<Double, Double> =
        holder.cursorX.toDouble() to holder.cursorY.toDouble()

    fun setCursorFromDirect(viewX: Float, viewY: Float, viewW: Float, viewH: Float) {
        val (nx, ny) = holder.mapToRemote(viewX, viewY, viewW, viewH)
        holder.cursorX = nx.toFloat()
        holder.cursorY = ny.toFloat()
        holder.syncCursorView()
    }

    fun moveTouchpad(dxPx: Float, dyPx: Float, viewW: Float, viewH: Float) {
        holder.cursorX = (holder.cursorX + dxPx / viewW * TOUCHPAD_GAIN).coerceIn(0f, 1f)
        holder.cursorY = (holder.cursorY + dyPx / viewH * TOUCHPAD_GAIN).coerceIn(0f, 1f)
        holder.syncCursorView()
    }

    fun fireLeftClick(x: Double, y: Double) {
        onControl(ControlPayload(ControlPayload.MOUSE_MOVE, x = x, y = y))
        onControl(ControlPayload(ControlPayload.MOUSE_DOWN, x = x, y = y, b = "left"))
        onControl(ControlPayload(ControlPayload.MOUSE_UP, x = x, y = y, b = "left"))
    }

    fun fireDoubleClick(v: View, x: Double, y: Double) {
        fireLeftClick(x, y)
        v.postDelayed({ fireLeftClick(x, y) }, DOUBLE_CLICK_GAP_MS)
    }

    return View.OnTouchListener { v, event ->
        val w = v.width.coerceAtLeast(1).toFloat()
        val h = v.height.coerceAtLeast(1).toFloat()

        if (scaleDetector == null) {
            scaleDetector = ScaleGestureDetector(
                v.context,
                object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                        g.multiTouch = true
                        g.viewPanMode = true
                        v.removeCallbacks(longPress)
                        if (g.dragArmed) {
                            val (x, y) = pointer()
                            onControl(ControlPayload(ControlPayload.MOUSE_UP, x = x, y = y, b = "left"))
                            g.dragArmed = false
                        }
                        return true
                    }

                    override fun onScale(detector: ScaleGestureDetector): Boolean {
                        val fx = detector.focusX
                        val fy = detector.focusY
                        val (ux, uy) = holder.mapToRemote(fx, fy, w, h)
                        g.scale = (g.scale * detector.scaleFactor).coerceIn(1f, 5f)
                        val aspect = holder.frameW.toFloat() / holder.frameH.toFloat().coerceAtLeast(1f)
                        var cw = w
                        var ch = w / aspect
                        if (ch > h) {
                            ch = h
                            cw = h * aspect
                        }
                        cw *= g.scale
                        ch *= g.scale
                        g.panX = fx - (w - cw) / 2f - ux.toFloat() * cw
                        g.panY = fy - (h - ch) / 2f - uy.toFloat() * ch
                        holder.clampPan(w, h, cw, ch)
                        holder.applyViewTransform()
                        holder.syncCursorView()
                        return true
                    }
                }
            )
        }
        scaleDetector!!.onTouchEvent(event)

        val mode = holder.touchMode
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val (cx, cy) = pointer()
                onControl(ControlPayload(ControlPayload.MOUSE_UP, x = cx, y = cy, b = "left"))
                g.downX = event.x
                g.downY = event.y
                g.lastPanX = event.x
                g.lastPanY = event.y
                g.movedFar = false
                g.dragArmed = false
                g.viewPanMode = false
                g.wheelMode = false
                g.multiTouch = false
                g.longPressPosted = false

                if (holder.inputMode == InputMode.DIRECT) {
                    setCursorFromDirect(event.x, event.y, w, h)
                    val (x, y) = pointer()
                    onControl(ControlPayload(ControlPayload.MOUSE_MOVE, x = x, y = y))
                }

                if (mode == TouchMode.DRAG) {
                    g.dragArmed = true
                    val (x, y) = pointer()
                    onControl(ControlPayload(ControlPayload.MOUSE_DOWN, x = x, y = y, b = "left"))
                } else {
                    g.longPressPosted = true
                    v.postDelayed(longPress, LONG_PRESS_MS)
                }
                true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                g.multiTouch = true
                v.removeCallbacks(longPress)
                g.longPressPosted = false
                if (g.dragArmed) {
                    val (x, y) = pointer()
                    onControl(ControlPayload(ControlPayload.MOUSE_UP, x = x, y = y, b = "left"))
                    g.dragArmed = false
                }
                if (g.scale > 1.05f) {
                    g.viewPanMode = true
                } else {
                    g.wheelMode = true
                }
                g.lastPanX = (event.getX(0) + event.getX(1)) / 2f
                g.lastPanY = (event.getY(0) + event.getY(1)) / 2f
                true
            }
            MotionEvent.ACTION_MOVE -> {
                if (scaleDetector!!.isInProgress) return@OnTouchListener true

                if (event.pointerCount >= 2) {
                    g.multiTouch = true
                    val x0 = (event.getX(0) + event.getX(1)) / 2f
                    val y0 = (event.getY(0) + event.getY(1)) / 2f
                    val dx = x0 - g.lastPanX
                    val dy = y0 - g.lastPanY
                    if (dx * dx + dy * dy > 1f) {
                        if (g.viewPanMode && g.scale > 1.05f) {
                            g.panX += dx
                            g.panY += dy
                            holder.clampPan(w, h)
                            holder.applyViewTransform()
                            holder.syncCursorView()
                        } else if (g.wheelMode || g.scale <= 1.05f) {
                            val delta = (-dy / 28f).toInt().coerceIn(-8, 8)
                            if (delta != 0) {
                                onControl(ControlPayload(ControlPayload.WHEEL, d = delta))
                            }
                        }
                        g.movedFar = true
                    }
                    g.lastPanX = x0
                    g.lastPanY = y0
                    return@OnTouchListener true
                }

                val dx = event.x - g.lastPanX
                val dy = event.y - g.lastPanY
                val fromDownX = event.x - g.downX
                val fromDownY = event.y - g.downY
                if (fromDownX * fromDownX + fromDownY * fromDownY > TAP_SLOP_PX * TAP_SLOP_PX) {
                    if (!g.movedFar) {
                        g.movedFar = true
                        v.removeCallbacks(longPress)
                        g.longPressPosted = false
                    }
                }

                if (g.movedFar || mode == TouchMode.DRAG) {
                    when {
                        holder.inputMode == InputMode.TOUCHPAD -> {
                            moveTouchpad(dx, dy, w, h)
                            val (x, y) = pointer()
                            onControl(ControlPayload(ControlPayload.MOUSE_MOVE, x = x, y = y))
                        }
                        else -> {
                            setCursorFromDirect(event.x, event.y, w, h)
                            val (x, y) = pointer()
                            onControl(ControlPayload(ControlPayload.MOUSE_MOVE, x = x, y = y))
                        }
                    }
                }
                g.lastPanX = event.x
                g.lastPanY = event.y
                true
            }
            MotionEvent.ACTION_UP -> {
                v.removeCallbacks(longPress)
                val (x, y) = pointer()
                when {
                    g.multiTouch && g.movedFar -> Unit
                    mode == TouchMode.DRAG && g.dragArmed -> {
                        onControl(ControlPayload(ControlPayload.MOUSE_UP, x = x, y = y, b = "left"))
                    }
                    !g.movedFar && mode == TouchMode.NORMAL -> {
                        val now = android.os.SystemClock.uptimeMillis()
                        val nearPrev = abs(holder.cursorX - g.lastTapX) < DOUBLE_TAP_POS &&
                            abs(holder.cursorY - g.lastTapY) < DOUBLE_TAP_POS
                        val isDouble = now - g.lastTapUpAt < DOUBLE_TAP_MS && nearPrev
                        if (isDouble) {
                            g.pendingSingleClick?.let { v.removeCallbacks(it) }
                            g.pendingSingleClick = null
                            g.lastTapUpAt = 0L
                            /* Explorer klasor/disk: iki tik kisa aralikla */
                            fireDoubleClick(v, x, y)
                        } else {
                            g.lastTapUpAt = now
                            g.lastTapX = holder.cursorX
                            g.lastTapY = holder.cursorY
                            /* Cift dokunus beklerken tek tiki geciktir — yoksa Explorer acmaz */
                            g.pendingSingleClick?.let { v.removeCallbacks(it) }
                            val clickX = x
                            val clickY = y
                            val pending = Runnable {
                                g.pendingSingleClick = null
                                fireLeftClick(clickX, clickY)
                            }
                            g.pendingSingleClick = pending
                            v.postDelayed(pending, DOUBLE_TAP_MS)
                        }
                    }
                }
                g.reset()
                true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount >= 2) {
                    val idx = if (event.actionIndex == 0) 1 else 0
                    g.lastPanX = event.getX(idx)
                    g.lastPanY = event.getY(idx)
                }
                true
            }
            MotionEvent.ACTION_CANCEL -> {
                v.removeCallbacks(longPress)
                if (g.dragArmed) {
                    val (x, y) = pointer()
                    onControl(ControlPayload(ControlPayload.MOUSE_UP, x = x, y = y, b = "left"))
                }
                g.reset()
                true
            }
            else -> false
        }
    }
}

// ---------------- sohbet balonu ----------------

@Composable
private fun ChatBubble(m: SessionController.ChatMessage, mine: Boolean, c: com.makay.remote.ui.theme.MakayColors) {
    val time = remember(m.ts) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(m.ts))
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = if (mine) c.primary.copy(alpha = 0.25f) else c.bg2,
            shape = RoundedCornerShape(10.dp)
        ) {
            Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                Text(m.text, color = c.text, style = MaterialTheme.typography.bodyMedium)
                Text(
                    time,
                    color = c.muted,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.End)
                )
            }
        }
    }
}

// ---------------- dosya kopyalama ----------------

private fun queryDisplayName(context: android.content.Context, uri: Uri): String? {
    return runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0) c.getString(i) else null
                } else null
            }
    }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: uri.lastPathSegment?.substringAfterLast('/')
}

private fun copyToCache(context: android.content.Context, uri: Uri, displayName: String): File? =
    runCatching {
        val safe = displayName.replace(Regex("""[\\/:*?"<>|]"""), "_").take(120)
            .ifBlank { "dosya-${System.currentTimeMillis()}" }
        val tmp = File(context.cacheDir, safe)
        context.contentResolver.openInputStream(uri)?.use { input ->
            tmp.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        tmp
    }.getOrNull()
