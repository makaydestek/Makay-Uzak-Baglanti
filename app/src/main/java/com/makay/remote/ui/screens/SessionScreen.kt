package com.makay.remote.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.gson.Gson
import com.makay.remote.rtc.ControlPayload
import com.makay.remote.rtc.FileTransferEngine
import com.makay.remote.session.SessionController
import com.makay.remote.session.SessionViewModel
import com.makay.remote.ui.theme.MakayTheme
import org.webrtc.EglBase
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    val context = LocalContext.current

    // --- pano / ses durumlari ---
    val inboundClip by vm.inboundClipboard.collectAsState()
    val micOn by vm.micState.collectAsState()
    val speakerOn by vm.speakerOn.collectAsState()

    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

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
            val tmp = copyToCache(context, uri) ?: return@rememberLauncherForActivityResult
            val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
            vm.sendFile(tmp, mime)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
            .imePadding()
    ) {
        // ---------- ust bar ----------
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(peerName, style = MaterialTheme.typography.titleMedium, color = c.text)
                Text(
                    text = if (asHost) "Ekranınız paylaşılıyor" else "Uzak ekran izleniyor",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.muted
                )
            }
            IconButton(onClick = { vm.disconnect() }) {
                Icon(Icons.Filled.Close, contentDescription = "Bağlantıyı kapat", tint = c.danger)
            }
        }

        Spacer(Modifier.height(8.dp))

        // ---------- video ----------
        RemoteVideoBox(
            track = remoteVideo,
            eglContext = vm.eglContext(),
            controlEnabled = controlState == SessionController.ControlState.GRANTED,
            onControl = { payload -> vm.sendControl(payload) },
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
        )

        Spacer(Modifier.height(8.dp))

        // ---------- kontrol cubugu ----------
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Host: ekran paylasimini baslat/durdur
            if (asHost) {
                if (!sharing) {
                    Button(
                        onClick = {
                            if (android.os.Build.VERSION.SDK_INT >= 33) {
                                notifPermissionLauncher.launch(
                                    android.Manifest.permission.POST_NOTIFICATIONS
                                )
                            } else {
                                val mpm = context.getSystemService(android.content.Context.MEDIA_PROJECTION_SERVICE)
                                    as android.media.projection.MediaProjectionManager
                                projectionLauncher.launch(mpm.createScreenCaptureIntent())
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = c.primary)
                    ) { Text("Ekranı Paylaş") }
                } else {
                    Button(
                        onClick = {
                            vm.stopScreenShare()
                            sharing = false
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = c.warn)
                    ) { Text("Paylaşımı Durdur") }
                }
            }

            if (!asHost) {
                when (controlState) {
                    SessionController.ControlState.NONE ->
                        OutlinedButton(onClick = { vm.requestControl() }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.Mouse, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Kontrol İste")
                        }
                    SessionController.ControlState.REQUESTED ->
                        OutlinedButton(onClick = { }, enabled = false, modifier = Modifier.weight(1f)) {
                            Text("Onay Bekleniyor…")
                        }
                    SessionController.ControlState.GRANTED ->
                        Button(
                            onClick = { vm.stopControl() },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = c.warn)
                        ) { Text("Kontrolü Bırak") }
                    SessionController.ControlState.DENIED ->
                        OutlinedButton(onClick = { vm.requestControl() }, modifier = Modifier.weight(1f)) {
                            Text("Tekrar Dene")
                        }
                }
            }

            OutlinedButton(
                onClick = { pickFile.launch(arrayOf("*/*")) },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Filled.AttachFile, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Dosya Gönder")
            }
        }

        Spacer(Modifier.height(8.dp))

        // ---------- ses + pano (telefon gorusmesi yapisi) ----------
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    if (micOn) {
                        vm.toggleMic()
                    } else if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED
                    ) {
                        micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                    } else {
                        vm.toggleMic()
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    if (micOn) Icons.Filled.Mic else Icons.Filled.MicOff,
                    contentDescription = if (micOn) "Mikrofon açık" else "Mikrofon kapalı",
                    modifier = Modifier.size(16.dp),
                    tint = if (micOn) c.accent else c.muted
                )
                Spacer(Modifier.width(4.dp))
                Text("Mikrofon")
            }

            OutlinedButton(
                onClick = { vm.setSpeaker(!speakerOn) },
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    if (speakerOn) Icons.AutoMirrored.Filled.VolumeUp
                    else Icons.AutoMirrored.Filled.VolumeOff,
                    contentDescription = if (speakerOn) "Hoparlör açık" else "Hoparlör kapalı",
                    modifier = Modifier.size(16.dp),
                    tint = if (speakerOn) c.accent else c.muted
                )
                Spacer(Modifier.width(4.dp))
                Text("Hoparlör")
            }

            OutlinedButton(
                onClick = {
                    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as ClipboardManager
                    val clip = cm.primaryClip
                    val text = if (clip != null && clip.itemCount > 0) {
                        clip.getItemAt(0).coerceToText(context).toString()
                    } else ""
                    vm.sendClipboard(text)
                },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Filled.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Pano")
            }
        }

        Spacer(Modifier.height(8.dp))

        // ---------- sohbet ----------
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(c.card, RoundedCornerShape(12.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(chat) { m ->
                ChatBubble(m, mine = m.mine, c = c)
            }
        }

        Spacer(Modifier.height(8.dp))

        // ---------- mesaj girdisi ----------
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Mesaj yazın…", color = c.muted) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = c.accent,
                    unfocusedBorderColor = c.cardBorder,
                    focusedTextColor = c.text,
                    unfocusedTextColor = c.text
                )
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    if (vm.sendChat(input)) input = ""
                },
                colors = ButtonDefaults.buttonColors(containerColor = c.primary)
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Gönder")
            }
        }
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
 * Uzak videoyu gosterir; kontrol izni varken dokunma/hareket kontrollerini
 * KANONIK wire bicimine cevirip `onControl` uzerinden gonderir.
 *
 * Sink yonetimi: renderer bir kez olusturulur, track degisince eski track'ten
 * `removeSink`, yenisine `addSink` yapilir (recomposition'da tekrarlanmaz).
 */
@Composable
private fun RemoteVideoBox(
    track: VideoTrack?,
    eglContext: EglBase.Context?,
    controlEnabled: Boolean,
    onControl: (ControlPayload) -> Unit,
    modifier: Modifier = Modifier
) {
    val c = MakayTheme.c

    Box(modifier.background(Color.Black, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
        if (track != null && eglContext != null) {
            // Renderer'i yalnizca bir kez olustur; track'i effect'te bagla
            val rendererHolder = remember { arrayOfNulls<SurfaceViewRenderer>(1) }
            var sinkTrack by remember { mutableStateOf<VideoTrack?>(null) }

            DisposableEffect(track) {
                val r = rendererHolder[0]
                val old = sinkTrack
                if (old != null && r != null) old.removeSink(r)
                if (r != null) track.addSink(r)
                sinkTrack = track
                onDispose { }
            }

            androidx.compose.ui.viewinterop.AndroidView(
                factory = { ctx ->
                    SurfaceViewRenderer(ctx).also { r ->
                        r.init(eglContext, null)
                        r.setEnableHardwareScaler(true)
                        rendererHolder[0] = r
                        track.addSink(r)
                        sinkTrack = track
                    }
                },
                onRelease = { r ->
                    val t = sinkTrack
                    if (t != null) t.removeSink(r)
                    sinkTrack = null
                    rendererHolder[0] = null
                    r.release()
                },
                modifier = Modifier.fillMaxSize()
            )

            // kontrol katmani: izin varsa dokunma -> mouse olaylari
            if (controlEnabled) {
                var dragStart by remember { mutableStateOf<Pair<Float, Float>?>(null) }
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(controlEnabled) {
                            detectTapGestures(
                                onPress = { /* basildi -> asagida drag ile ele alinir */ },
                                onTap = { offset ->
                                    val nx = (offset.x / size.width).coerceIn(0f, 1f).toDouble()
                                    val ny = (offset.y / size.height).coerceIn(0f, 1f).toDouble()
                                    onControl(ControlPayload(ControlPayload.MOUSE_DOWN, x = nx, y = ny, b = "left"))
                                    onControl(ControlPayload(ControlPayload.MOUSE_UP, x = nx, y = ny, b = "left"))
                                }
                            )
                        }
                        .pointerInput(controlEnabled) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    dragStart = offset.x to offset.y
                                    val nx = (offset.x / size.width).coerceIn(0f, 1f).toDouble()
                                    val ny = (offset.y / size.height).coerceIn(0f, 1f).toDouble()
                                    onControl(ControlPayload(ControlPayload.MOUSE_DOWN, x = nx, y = ny, b = "left"))
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    val nx = (change.position.x / size.width).coerceIn(0f, 1f).toDouble()
                                    val ny = (change.position.y / size.height).coerceIn(0f, 1f).toDouble()
                                    onControl(ControlPayload(ControlPayload.MOUSE_MOVE, x = nx, y = ny))
                                },
                                onDragEnd = {
                                    dragStart = null
                                    onControl(ControlPayload(ControlPayload.MOUSE_UP, b = "left"))
                                },
                                onDragCancel = {
                                    dragStart = null
                                    onControl(ControlPayload(ControlPayload.MOUSE_UP, b = "left"))
                                }
                            )
                        }
                )
            }
        } else {
            Text(
                text = if (track == null) "Bağlantı hazır — görüntülü ekran bekleniyor…"
                else "Video hazırlanıyor…",
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
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

private fun copyToCache(context: android.content.Context, uri: Uri): File? = runCatching {
    val name = "gonderilen-${System.currentTimeMillis()}"
    val tmp = File(context.cacheDir, name)
    context.contentResolver.openInputStream(uri)?.use { input ->
        tmp.outputStream().use { output -> input.copyTo(output) }
    } ?: return null
    tmp
}.getOrNull()
