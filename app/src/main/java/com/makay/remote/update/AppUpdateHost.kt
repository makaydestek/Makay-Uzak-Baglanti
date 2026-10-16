package com.makay.remote.update

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.makay.remote.ui.theme.MakayTheme

fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return ctx as? Activity
}

/**
 * Guncelleme ekrani — Makay ekosistemindeki diger uygulamalarla (Cleaner,
 * File Manager, Launcher) birebir ayni davranis:
 *   kontrol -> diyalog -> "Guncelle" -> uygulama ici indirme (yuzde gorunur)
 *   -> SHA-256 + paket adi + imza dogrulamasi -> sistem kurulum ekrani.
 *
 * Tarayiciya yonlendirme YOK; indirme ve kurulum uygulama icinden yapilir.
 */
@Composable
fun AppUpdateHost(
    openFromNotification: Boolean = false,
    autoDownload: Boolean = false,
    promptToken: Long = 0L
) {
    val context = LocalContext.current
    val app = context.applicationContext as Application
    val activity = context.findActivity()
    // ViewModel'i Activity'nin ViewModelStore'una bagla; Ayarlar ekranindaki
    // "Guncelleme kontrol et" dugmesi de AYNI ornegi kullanir, boylece
    // dugmeden cagirilan kontrol buradaki diyalogu acar.
    //
    // NOT: MainActivity `ComponentActivity`'dir, `FragmentActivity` DEGIL.
    // Referanstaki `as? FragmentActivity` dokuumu bu projede hep null donerdi.
    val viewModel: AppUpdateViewModel = (activity as? ViewModelStoreOwner)?.let { owner ->
        viewModel(viewModelStoreOwner = owner, factory = AppUpdateViewModel.Factory(app))
    } ?: viewModel(factory = AppUpdateViewModel.Factory(app))
    val state by viewModel.state.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    val c = MakayTheme.c

    LaunchedEffect(Unit) {
        viewModel.checkOnLaunch()
    }
    LaunchedEffect(promptToken, openFromNotification, autoDownload) {
        if (promptToken == 0L && !openFromNotification && !autoDownload) return@LaunchedEffect
        viewModel.handleNotificationOpen(autoDownload = autoDownload, activity = activity)
    }

    DisposableEffect(lifecycleOwner, activity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME &&
                state.needsInstallPermission &&
                activity != null
            ) {
                viewModel.retryInstallAfterPermission(activity)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (state.dialogVisible && state.available != null) {
        val update = state.available!!
        AlertDialog(
            onDismissRequest = {
                if (!state.downloading) viewModel.dismissDialog(postpone = true)
            },
            containerColor = c.bg2,
            title = { Text("Yeni sürüm: ${update.versionName}", color = c.text) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Uygulamayı güncellemek ister misiniz?",
                        color = c.text
                    )
                    Text(
                        "Güncelleme yalnızca resmi GitHub Releases’ten indirilir. " +
                            "Kurulumdan önce paket adı, imza ve (varsa) SHA-256 doğrulanır.",
                        color = c.muted
                    )
                    if (update.releaseNotes.isNotBlank()) {
                        Text(
                            update.releaseNotes,
                            color = c.muted
                        )
                    }
                    if (state.downloading) {
                        LinearProgressIndicator(
                            progress = { state.progress / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            if (state.progress >= 100) "Bütünlük kontrol ediliyor…"
                            else "%${state.progress} indiriliyor…",
                            color = c.text
                        )
                    }
                    state.error?.let {
                        Text(it, color = c.danger)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (activity != null) viewModel.startDownloadAndInstall(activity)
                        else viewModel.reportNoActivity()
                    },
                    enabled = !state.downloading
                ) {
                    Text(
                        if (state.downloading) "İndiriliyor…" else "Güncelle",
                        color = if (state.downloading) c.muted else c.accent
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { viewModel.dismissDialog(postpone = true) },
                    enabled = !state.downloading
                ) { Text("Ertele", color = c.muted) }
            }
        )
    }
}
