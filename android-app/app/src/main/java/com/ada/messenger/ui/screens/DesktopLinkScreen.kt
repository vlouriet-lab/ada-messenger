package com.ada.messenger.ui.screens

import android.util.Log
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ada.messenger.core.AdaCoreViewModel
import com.ada.messenger.core.WifiDesktopLinkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "DesktopLinkScreen"

private sealed class LinkStep {
    object PickDevice : LinkStep()   // WiFi: QR prompt
    object Sending : LinkStep()
    object Success : LinkStep()
    data class Error(val message: String) : LinkStep()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DesktopLinkScreen(
    viewModel: AdaCoreViewModel,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val snackState = remember { SnackbarHostState() }

    // ── Wi-Fi state ──────────────────────────────────────────────
    val wifiManager = remember { WifiDesktopLinkManager() }
    var wifiStep by remember { mutableStateOf<LinkStep>(LinkStep.PickDevice) }
    var showQrScanner by remember { mutableStateOf(false) }
    var scannedUrl by remember { mutableStateOf("") }

    // ── QR scanner overlay ───────────────────────────────────────
    if (showQrScanner) {
        QrScannerScreen(
            promptText = "Наведите камеру на QR-код с экрана ADA ПК",
            onResult = { url ->
                showQrScanner = false
                scannedUrl = url
                // Auto-send immediately after scan
                scope.launch {
                    wifiStep = LinkStep.Sending
                    try {
                        val snapshotJson = withContext(Dispatchers.IO) {
                            viewModel.core?.exportSnapshot()
                        }
                        if (snapshotJson == null) {
                            wifiStep = LinkStep.Error("Не удалось получить снапшот аккаунта")
                            return@launch
                        }
                        val result = wifiManager.sendIdentityToPC(url, snapshotJson)
                        when (result) {
                            is WifiDesktopLinkManager.WifiLinkResult.Success -> {
                                // Persist the desktop sync URL so MessageReceived events can push to it.
                                if (result.syncPort > 0) {
                                    withContext(Dispatchers.IO) {
                                        try {
                                            val parsedUrl = java.net.URL(url)
                                            val syncUrl = "http://${parsedUrl.host}:${result.syncPort}/sync"
                                            viewModel.core?.storeLinkSyncUrl(syncUrl)
                                            Log.i(TAG, "Desktop sync URL stored")
                                        } catch (e: Exception) {
                                            Log.w(TAG, "Failed to store sync URL", e)
                                        }
                                    }
                                }
                                wifiStep = LinkStep.Success
                            }
                            is WifiDesktopLinkManager.WifiLinkResult.Failure ->
                                wifiStep = LinkStep.Error(result.reason)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Wi-Fi link failed", e)
                        wifiStep = LinkStep.Error(e.localizedMessage ?: "Ошибка передачи")
                    }
                }
            },
            onClose = { showQrScanner = false },
        )
        return  // Don't render Scaffold underneath
    }

    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackState) },
        topBar = {
            TopAppBar(
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                ),
                title = { Text("Привязать ПК") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Назад")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (val s = wifiStep) {
                is LinkStep.PickDevice -> WifiPickContent(
                    onScanQr = { showQrScanner = true },
                )
                is LinkStep.Sending -> SendingContent()
                is LinkStep.Success -> SuccessContent(onBack = onBack)
                is LinkStep.Error -> ErrorContent(
                    message = s.message,
                    onRetry = {
                        wifiStep = LinkStep.PickDevice
                        scannedUrl = ""
                    },
                    onBack = onBack,
                )
            }
        }
    }
}

// ── Sub-composables ──────────────────────────────────────────────────────────

@Composable
private fun WifiPickContent(onScanQr: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(32.dp))

        Icon(
            Icons.Outlined.QrCodeScanner,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary,
        )

        Spacer(Modifier.height(16.dp))

        Text(
            "Привязать ПК по Wi-Fi",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(8.dp))

        Text(
            "Откройте ADA на ПК, нажмите «Войти с телефона (Wi-Fi)».\n" +
                "QR-код появится на экране ПК — отсканируйте его телефоном.\n\n" +
                "ПК и телефон должны быть в одной Wi-Fi сети.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(32.dp))

        Button(
            onClick = onScanQr,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Outlined.QrCodeScanner, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Сканировать QR с экрана ПК")
        }
    }
}

@Composable
private fun SendingContent() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(64.dp))
        Spacer(Modifier.height(24.dp))
        Text(
            "Подключение и передача данных…",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Text(
            "Держите телефон рядом с компьютером",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SuccessContent(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Card(
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ),
            modifier = Modifier.size(80.dp),
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Outlined.Check,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(
            "Аккаунт успешно передан!",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Войдите в ADA на ПК — ваш аккаунт уже там.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(32.dp))
        Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("Готово")
        }
    }
}

@Composable
private fun ErrorContent(message: String, onRetry: () -> Unit, onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Outlined.ErrorOutline,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "Ошибка передачи",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(32.dp))
        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
            Text("Попробовать снова")
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("Назад")
        }
    }
}
