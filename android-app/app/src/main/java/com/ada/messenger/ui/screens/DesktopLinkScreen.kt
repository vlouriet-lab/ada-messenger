package com.ada.messenger.ui.screens

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.os.Build
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.PhonelinkSetup
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ada.messenger.core.AdaCoreViewModel
import com.ada.messenger.core.DesktopLinkManager
import com.ada.messenger.core.WifiDesktopLinkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "DesktopLinkScreen"

// в”Ђв”Ђ Shared step states (reused by both BT and Wi-Fi) в”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђ

private sealed class LinkStep {
    object PickDevice : LinkStep()   // BT: device picker  | WiFi: QR prompt
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackState = remember { SnackbarHostState() }

    // в”Ђв”Ђ Tab: 0 = Wi-Fi QR (primary), 1 = Bluetooth (fallback) в”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђ
    var selectedTab by remember { mutableIntStateOf(0) }

    // в”Ђв”Ђ Wi-Fi state в”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђ
    val wifiManager = remember { WifiDesktopLinkManager() }
    var wifiStep by remember { mutableStateOf<LinkStep>(LinkStep.PickDevice) }
    var showQrScanner by remember { mutableStateOf(false) }
    var scannedUrl by remember { mutableStateOf("") }

    // в”Ђв”Ђ Bluetooth state в”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђ
    val btManager = remember { DesktopLinkManager(context) }
    var btStep by remember { mutableStateOf<LinkStep>(LinkStep.PickDevice) }
    var pairingCode by remember { mutableStateOf("") }
    var selectedDevice by remember { mutableStateOf<BluetoothDevice?>(null) }
    var pairedDevices by remember { mutableStateOf<List<BluetoothDevice>>(emptyList()) }
    var btPermissionGranted by remember { mutableStateOf(false) }

    val btPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results.values.all { it }
        btPermissionGranted = granted
        if (granted) {
            pairedDevices = btManager.getPairedDevices()
        } else {
            scope.launch { snackState.showSnackbar("Для Bluetooth нужны разрешения") }
        }
    }

    // Load BT paired devices when the BT tab is first selected
    LaunchedEffect(selectedTab) {
        if (selectedTab == 1 && !btPermissionGranted) {
            val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
            } else {
                listOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN)
            }
            val missing = required.filter {
                context.checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
            }
            if (missing.isEmpty()) {
                btPermissionGranted = true
                pairedDevices = btManager.getPairedDevices()
            } else {
                btPermLauncher.launch(missing.toTypedArray())
            }
        }
    }

    // в”Ђв”Ђ QR scanner overlay в”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђ
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
                .padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // в”Ђв”Ђ Tab row в”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђ
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = {
                        selectedTab = 0
                        wifiStep = LinkStep.PickDevice
                        scannedUrl = ""
                    },
                    icon = { Icon(Icons.Outlined.Wifi, contentDescription = null) },
                    text = { Text("Wi-Fi QR") },
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = {
                        selectedTab = 1
                        btStep = LinkStep.PickDevice
                        pairingCode = ""
                        selectedDevice = null
                    },
                    icon = { Icon(Icons.Outlined.Bluetooth, contentDescription = null) },
                    text = { Text("Bluetooth") },
                )
            }

            // в”Ђв”Ђ Tab content в”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђ
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (selectedTab == 0) {
                    // в”Ђв”Ђ Wi-Fi QR tab в”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђ
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
                } else {
                    // в”Ђв”Ђ Bluetooth tab в”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђ
                    when (val s = btStep) {
                        is LinkStep.PickDevice -> PickDeviceContent(
                            pairedDevices = pairedDevices,
                            selectedDevice = selectedDevice,
                            pairingCode = pairingCode,
                            onDeviceSelected = { selectedDevice = it },
                            onCodeChanged = { v ->
                                if (v.length <= 6 && v.all { it.isDigit() }) pairingCode = v
                            },
                            onSend = {
                                val device = selectedDevice
                                if (device == null) {
                                    scope.launch { snackState.showSnackbar("Выберите устройство ПК из списка") }
                                    return@PickDeviceContent
                                }
                                if (pairingCode.length != 6) {
                                    scope.launch { snackState.showSnackbar("Введите 6-значный код с экрана ПК") }
                                    return@PickDeviceContent
                                }
                                scope.launch {
                                    btStep = LinkStep.Sending
                                    try {
                                        val snapshotJson = withContext(Dispatchers.IO) {
                                            viewModel.core?.exportSnapshot()
                                        }
                                        if (snapshotJson == null) {
                                            btStep = LinkStep.Error("Не удалось получить снапшот аккаунта")
                                            return@launch
                                        }
                                        val result = btManager.sendIdentityToPC(device, pairingCode, snapshotJson)
                                        btStep = when (result) {
                                            is DesktopLinkManager.LinkResult.Success -> LinkStep.Success
                                            is DesktopLinkManager.LinkResult.Failure -> LinkStep.Error(result.reason)
                                        }
                                    } catch (e: Exception) {
                                        Log.e(TAG, "BT link failed", e)
                                        btStep = LinkStep.Error(e.localizedMessage ?: "Ошибка передачи")
                                    }
                                }
                            },
                        )

                        is LinkStep.Sending -> SendingContent()

                        is LinkStep.Success -> SuccessContent(onBack = onBack)

                        is LinkStep.Error -> ErrorContent(
                            message = s.message,
                            onRetry = {
                                btStep = LinkStep.PickDevice
                                pairingCode = ""
                                selectedDevice = null
                            },
                            onBack = onBack,
                        )
                    }
                }
            }
        }
    }
}

// в”Ђв”Ђ Sub-composables в”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђ

// в”Ђв”Ђ Wi-Fi QR picker в”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђ
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
@Suppress("MissingPermission")
private fun PickDeviceContent(
    pairedDevices: List<BluetoothDevice>,
    selectedDevice: BluetoothDevice?,
    pairingCode: String,
    onDeviceSelected: (BluetoothDevice) -> Unit,
    onCodeChanged: (String) -> Unit,
    onSend: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(8.dp))

        Icon(
            Icons.Outlined.PhonelinkSetup,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.primary,
        )

        Spacer(Modifier.height(12.dp))

        Text(
            "Привязать ПК по Bluetooth",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(4.dp))

        Text(
            "Откройте ADA на ПК и нажмите «Войти с телефона». " +
                "Выберите компьютер из списка и введите 6-значный код.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(20.dp))

        // Device list
        Text(
            "Сопряжённые Bluetooth-устройства",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))

        if (pairedDevices.isEmpty()) {
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Нет сопряжённых устройств.\nСначала создайте пару с ПК в настройках Bluetooth телефона.",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(pairedDevices) { device ->
                        val isSelected = device.address == selectedDevice?.address
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onDeviceSelected(device) }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Outlined.Bluetooth,
                                contentDescription = null,
                                tint = if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = device.name ?: device.address,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = device.address,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (isSelected) {
                                Icon(
                                    Icons.Outlined.Check,
                                    contentDescription = "Выбрано",
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        if (device != pairedDevices.last()) {
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Code input
        OutlinedTextField(
            value = pairingCode,
            onValueChange = onCodeChanged,
            label = { Text("Код с экрана ПК") },
            placeholder = { Text("• • • • • •") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = onSend,
            modifier = Modifier.fillMaxWidth(),
            enabled = pairingCode.length == 6 && selectedDevice != null,
        ) {
            Icon(Icons.Outlined.PhonelinkSetup, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Передать аккаунт на ПК")
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
