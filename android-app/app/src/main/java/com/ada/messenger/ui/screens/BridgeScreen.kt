package com.ada.messenger.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ada.messenger.R
import com.ada.messenger.core.AdaCoreViewModel
import kotlinx.coroutines.launch

// ─────────────────────────────────────────────────────────────────────────────
// BridgeScreen — censorship-resistance and bridge configuration
// Navigation destination: "bridge"
// ─────────────────────────────────────────────────────────────────────────────

private data class ModeInfo(val key: String, val labelRes: Int, val descRes: Int)
private data class ProfileInfo(val key: String, val labelRes: Int, val descRes: Int)

private val CONNECTION_PROFILES = listOf(
    ProfileInfo("auto", R.string.bridge_profile_auto, R.string.bridge_profile_auto_desc),
    ProfileInfo("normal", R.string.bridge_profile_normal, R.string.bridge_profile_normal_desc),
    ProfileInfo("mobile_saver", R.string.bridge_profile_mobile_saver, R.string.bridge_profile_mobile_saver_desc),
    ProfileInfo("censored_light", R.string.bridge_profile_censored_light, R.string.bridge_profile_censored_light_desc),
    ProfileInfo("censored_heavy", R.string.bridge_profile_censored_heavy, R.string.bridge_profile_censored_heavy_desc),
    ProfileInfo("allowlist_only", R.string.bridge_profile_allowlist_only, R.string.bridge_profile_allowlist_only_desc),
    ProfileInfo("incident_safe", R.string.bridge_profile_incident_safe, R.string.bridge_profile_incident_safe_desc),
)

private val MODES = listOf(
    ModeInfo("auto",      R.string.bridge_mode_auto,      R.string.bridge_mode_auto_desc),
    ModeInfo("none",      R.string.bridge_mode_none,      R.string.bridge_mode_none_desc),
    ModeInfo("padding",   R.string.bridge_mode_padding,   R.string.bridge_mode_padding_desc),
    ModeInfo("shaping",   R.string.bridge_mode_shaping,   R.string.bridge_mode_shaping_desc),
    ModeInfo("websocket", R.string.bridge_mode_websocket, R.string.bridge_mode_websocket_desc),
    ModeInfo("fronting",  R.string.bridge_mode_fronting,  R.string.bridge_mode_fronting_desc),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BridgeScreen(
    viewModel: AdaCoreViewModel,
    onBack: () -> Unit = {},
    onOpenManifestQrScanner: () -> Unit = {},
) {

    val bridgeStatus   by viewModel.bridgeStatus.collectAsState()
    val connectionProfile by viewModel.connectionProfile.collectAsState()
    val censorshipLevel by viewModel.censorshipLevel.collectAsState()
    val bridgeImportNotice by viewModel.bridgeImportNotice.collectAsState()

    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var showAddDialog  by remember { mutableStateOf(false) }
    var newBridgeLine  by remember { mutableStateOf("") }
    var showManifestImportDialog by remember { mutableStateOf(false) }
    var manifestImportText by remember { mutableStateOf("") }
    var manifestTrustedKey by remember { mutableStateOf("") }
    var manifestSourceLabel by remember { mutableStateOf("") }

    val manualSourceLabel = stringResource(R.string.bridge_manifest_import_source_manual)
    val fileSourceLabel = stringResource(R.string.bridge_manifest_import_source_file)
    val readFailedLabel = stringResource(R.string.bridge_manifest_import_read_failed)
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val text = viewModel.readTextFromUri(uri)
                if (text == null) {
                    snackbarHostState.showSnackbar(readFailedLabel)
                } else {
                    manifestImportText = text
                    manifestSourceLabel = uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { fileSourceLabel } ?: fileSourceLabel
                    showManifestImportDialog = true
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.loadBridgeStatus()
        viewModel.detectCensorship()
    }

    LaunchedEffect(bridgeImportNotice) {
        val notice = bridgeImportNotice ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(notice.message)
        viewModel.clearBridgeImportNotice()
    }

    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                    actionIconContentColor = MaterialTheme.colorScheme.onBackground,
                ),
                title = { Text(stringResource(R.string.bridge_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.button_back))
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.bridge_add_button))
            }
        }
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // ── Tip card ─────────────────────────────────────────────────
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(20.dp))
                        Text(
                            stringResource(R.string.bridge_tip),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }

            // ── Censorship level card ────────────────────────────────────
            item {
                CensorshipCard(level = censorshipLevel) {
                    viewModel.detectCensorship()
                }
            }

            item {
                ConnectionProfileSelector(
                    currentProfile = connectionProfile,
                    onProfileSelected = { viewModel.setConnectionProfile(it) },
                )
            }

            item {
                val profileForcesRelayOnly = connectionProfile in listOf(
                    "censored_heavy", "allowlist_only", "incident_safe"
                )
                RelayOnlyCard(
                    enabled = bridgeStatus?.relayOnly ?: false,
                    locked = profileForcesRelayOnly,
                    onToggle = { viewModel.setRelayOnly(it) }
                )
            }

            item {
                RuntimeStatusCard(status = bridgeStatus)
            }

            item {
                ManifestImportCard(
                    onPasteOrLink = {
                        manifestImportText = ""
                        manifestSourceLabel = manualSourceLabel
                        showManifestImportDialog = true
                    },
                    onPickFile = {
                        filePicker.launch(arrayOf("application/json", "text/*", "*/*"))
                    },
                    onScanQr = onOpenManifestQrScanner,
                )
            }

            // ── Mode selector ────────────────────────────────────────────
            item {
                ModeSelector(
                    currentMode = bridgeStatus?.mode ?: "auto",
                    onModeSelected = { viewModel.setBridgeMode(it) }
                )
            }

            // ── Bridge list ──────────────────────────────────────────────
            item {
                Text(stringResource(R.string.bridge_bridges_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }

            val bridges = bridgeStatus?.bridges ?: emptyList()
            if (bridges.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.bridge_no_bridges),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(bridges) { bridgeObj ->
                    BridgeRow(
                        address = bridgeObj.optString("address", "unknown"),
                        protocol = bridgeObj.optString("protocol", ""),
                        reachable = bridgeObj.optBoolean("reachable")
                    )
                }
            }
        }
    }

    // ── Add Bridge dialog ────────────────────────────────────────────────────
    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false; newBridgeLine = "" },
            title = { Text(stringResource(R.string.bridge_add_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.bridge_add_example),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = newBridgeLine,
                        onValueChange = { newBridgeLine = it },
                        label = { Text(stringResource(R.string.bridge_add_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = false,
                        maxLines = 4,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newBridgeLine.isNotBlank() && newBridgeLine.length <= 512) {
                            viewModel.addBridge(newBridgeLine.trim())
                        }
                        showAddDialog = false
                        newBridgeLine = ""
                    },
                    enabled = newBridgeLine.isNotBlank() && newBridgeLine.length <= 512,
                ) { Text(stringResource(R.string.bridge_add_button)) }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false; newBridgeLine = "" }) {
                    Text(stringResource(R.string.button_cancel))
                }
            }
        )
    }

    if (showManifestImportDialog) {
        AlertDialog(
            onDismissRequest = {
                showManifestImportDialog = false
                manifestImportText = ""
                manifestSourceLabel = ""
            },
            title = { Text(stringResource(R.string.bridge_manifest_import_dialog_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.bridge_manifest_import_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (manifestSourceLabel.isNotBlank()) {
                        Text(
                            stringResource(R.string.bridge_manifest_import_loaded_from, manifestSourceLabel),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedTextField(
                        value = manifestImportText,
                        onValueChange = { manifestImportText = it },
                        label = { Text(stringResource(R.string.bridge_manifest_import_input_label)) },
                        supportingText = {
                            Text(stringResource(R.string.bridge_manifest_import_input_hint))
                        },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 5,
                        maxLines = 8,
                    )
                    OutlinedTextField(
                        value = manifestTrustedKey,
                        onValueChange = { manifestTrustedKey = it },
                        label = { Text(stringResource(R.string.bridge_manifest_import_key_label)) },
                        supportingText = {
                            Text(stringResource(R.string.bridge_manifest_import_key_hint))
                        },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 3,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val ok = viewModel.importBridgeManifestFromText(
                            input = manifestImportText,
                            trustedPublicKeyHex = manifestTrustedKey,
                            sourceHint = manifestSourceLabel.ifBlank { manualSourceLabel },
                        )
                        if (ok) {
                            showManifestImportDialog = false
                            manifestImportText = ""
                            manifestTrustedKey = ""
                            manifestSourceLabel = ""
                        }
                    },
                    enabled = manifestImportText.isNotBlank(),
                ) {
                    Text(stringResource(R.string.bridge_manifest_import_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showManifestImportDialog = false
                    manifestImportText = ""
                    manifestTrustedKey = ""
                    manifestSourceLabel = ""
                }) {
                    Text(stringResource(R.string.button_cancel))
                }
            },
        )
    }
}

@Composable
private fun ManifestImportCard(
    onPasteOrLink: () -> Unit,
    onPickFile: () -> Unit,
    onScanQr: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stringResource(R.string.bridge_manifest_import_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(R.string.bridge_manifest_import_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onPasteOrLink, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.bridge_manifest_import_paste_button))
            }
            OutlinedButton(onClick = onPickFile, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.bridge_manifest_import_file_button))
            }
            OutlinedButton(onClick = onScanQr, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.bridge_manifest_import_qr_button))
            }
        }
    }
}

@Composable
private fun RuntimeStatusCard(status: com.ada.messenger.core.BridgeStatus?) {
    val stackText = when (status?.transportStack) {
        "iroh_only" -> stringResource(R.string.bridge_runtime_stack_iroh_only)
        "iroh_bridge_mailbox" -> stringResource(R.string.bridge_runtime_stack_iroh_bridge_mailbox)
        else -> stringResource(R.string.bridge_runtime_unknown)
    }
    val routeVisibilityText = when (status?.routeGranularity) {
        "iroh_live_or_offline_queue" -> stringResource(R.string.bridge_runtime_visibility_iroh_live_or_offline_queue)
        "iroh_bridge_mailbox_or_offline_queue" -> stringResource(R.string.bridge_runtime_visibility_iroh_bridge_mailbox_or_offline_queue)
        else -> stringResource(R.string.bridge_runtime_unknown)
    }
    val relayOnlyScopeText = when (status?.relayOnlyScope) {
        "queue_only" -> stringResource(R.string.bridge_relay_only_scope_queue_only)
        "bridge_or_mailbox_only" -> stringResource(R.string.bridge_relay_only_scope_bridge_or_mailbox_only)
        "disabled" -> stringResource(R.string.bridge_relay_only_scope_disabled)
        else -> stringResource(R.string.bridge_runtime_unknown)
    }
    val lastOutcomeText = when (status?.lastOutcome?.route) {
        "iroh_live" -> stringResource(R.string.bridge_last_outcome_iroh_live)
        "bridge_websocket_tls" -> stringResource(R.string.bridge_last_outcome_bridge_websocket_tls)
        "bridge_domain_front" -> stringResource(R.string.bridge_last_outcome_bridge_domain_front)
        "bridge_meek" -> stringResource(R.string.bridge_last_outcome_bridge_meek)
        "bridge_obfs4" -> stringResource(R.string.bridge_last_outcome_bridge_obfs4)
        "local_mesh" -> stringResource(R.string.bridge_last_outcome_local_mesh)
        "mailbox_bridge" -> stringResource(R.string.bridge_last_outcome_mailbox_bridge)
        "offline_queue" -> stringResource(R.string.bridge_last_outcome_offline_queue)
        "relay_only_deferred" -> stringResource(R.string.bridge_last_outcome_relay_only_deferred)
        "failed" -> stringResource(R.string.bridge_last_outcome_failed)
        else -> stringResource(R.string.bridge_last_outcome_none)
    }
    val listenerRouteText = when (status?.bridgeListenerRoute) {
        "bridge_websocket_tls" -> stringResource(R.string.bridge_last_outcome_bridge_websocket_tls)
        "bridge_domain_front" -> stringResource(R.string.bridge_last_outcome_bridge_domain_front)
        "bridge_meek" -> stringResource(R.string.bridge_last_outcome_bridge_meek)
        "bridge_obfs4" -> stringResource(R.string.bridge_last_outcome_bridge_obfs4)
        "mailbox_bridge" -> stringResource(R.string.bridge_last_outcome_mailbox_bridge)
        else -> stringResource(R.string.bridge_runtime_unknown)
    }
    val listenerText = if (status?.bridgeListenerConnected == true) {
        stringResource(R.string.bridge_listener_connected, listenerRouteText)
    } else {
        stringResource(R.string.bridge_listener_disconnected)
    }
    val callCapabilityText = if (status?.capabilities?.realtimeCalls == true) {
        stringResource(R.string.bridge_capability_calls_available)
    } else {
        stringResource(R.string.bridge_capability_calls_unavailable)
    }
    val attachmentCapabilityText = if (status?.capabilities?.largeAttachments == true) {
        stringResource(R.string.bridge_capability_attachments_available)
    } else {
        stringResource(
            R.string.bridge_capability_attachments_limited,
            status?.capabilities?.maxAttachmentBytes?.toString() ?: "0"
        )
    }
    val manifestText = status?.manifest?.let {
        stringResource(
            R.string.bridge_manifest_summary,
            it.version.toString(),
            it.bridgeCount.toString(),
            it.source ?: stringResource(R.string.bridge_runtime_unknown),
        )
    } ?: stringResource(R.string.bridge_manifest_absent)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                stringResource(R.string.bridge_runtime_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(R.string.bridge_runtime_profile_label, profileLabel(status?.connectionProfile)),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(R.string.bridge_runtime_stack_label, stackText),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(R.string.bridge_runtime_visibility_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(routeVisibilityText, style = MaterialTheme.typography.bodySmall)
            Text(
                stringResource(R.string.bridge_relay_only_scope_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(relayOnlyScopeText, style = MaterialTheme.typography.bodySmall)
            Text(
                stringResource(R.string.bridge_last_outcome_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(lastOutcomeText, style = MaterialTheme.typography.bodySmall)
            Text(
                stringResource(R.string.bridge_listener_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(listenerText, style = MaterialTheme.typography.bodySmall)
            Text(
                stringResource(R.string.bridge_mailbox_depth_label, status?.bridgeMailboxDepth ?: 0),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                stringResource(R.string.bridge_capability_calls_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(callCapabilityText, style = MaterialTheme.typography.bodySmall)
            Text(
                stringResource(R.string.bridge_capability_attachments_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(attachmentCapabilityText, style = MaterialTheme.typography.bodySmall)
            Text(
                stringResource(R.string.bridge_manifest_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(manifestText, style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Censorship level card
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun CensorshipCard(level: String, onRecheck: () -> Unit) {
    val isDark = isSystemInDarkTheme()
    val (color, descRes) = when (level) {
        "None"     -> (if (isDark) Color(0xFF81C784) else Color(0xFF2E7D32)) to R.string.bridge_level_none
        "Light"    -> (if (isDark) Color(0xFFFFF59D) else Color(0xFFF57F17)) to R.string.bridge_level_light
        "Moderate" -> (if (isDark) Color(0xFFFFCC80) else Color(0xFFE65100)) to R.string.bridge_level_moderate
        "Heavy"    -> (if (isDark) Color(0xFFEF9A9A) else Color(0xFFC62828)) to R.string.bridge_level_heavy
        "Extreme"  -> (if (isDark) Color(0xFFCE93D8) else Color(0xFF6A1B9A)) to R.string.bridge_level_extreme
        else       -> MaterialTheme.colorScheme.outline to R.string.bridge_level_unknown
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(Icons.Default.Security, contentDescription = null, tint = color, modifier = Modifier.size(32.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.bridge_censorship_level, level), fontWeight = FontWeight.Bold)
                Text(stringResource(descRes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onRecheck) { Text(stringResource(R.string.bridge_recheck)) }
        }
    }
}

@Composable
private fun RelayOnlyCard(enabled: Boolean, locked: Boolean, onToggle: (Boolean) -> Unit) {
    var localEnabled by remember(enabled) { mutableStateOf(enabled) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    Icons.Default.Security,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.bridge_relay_only_title),
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        stringResource(R.string.bridge_relay_only_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = if (locked) true else localEnabled,
                    onCheckedChange = { v ->
                        if (!locked) {
                            localEnabled = v
                            onToggle(v)
                        }
                    },
                    enabled = !locked,
                )
            }
            if (locked) {
                Text(
                    stringResource(R.string.bridge_relay_only_profile_forced),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun profileLabel(profile: String?): String {
    val resId = when (profile) {
        "normal" -> R.string.bridge_profile_normal
        "mobile_saver" -> R.string.bridge_profile_mobile_saver
        "censored_light" -> R.string.bridge_profile_censored_light
        "censored_heavy" -> R.string.bridge_profile_censored_heavy
        "allowlist_only" -> R.string.bridge_profile_allowlist_only
        "incident_safe" -> R.string.bridge_profile_incident_safe
        else -> R.string.bridge_profile_auto
    }
    return stringResource(resId)
}

@Composable
private fun ConnectionProfileSelector(
    currentProfile: String,
    onProfileSelected: (String) -> Unit,
) {
    var localSelectedProfile by remember(currentProfile) { mutableStateOf(currentProfile) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.bridge_profile_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            CONNECTION_PROFILES.forEach { profile ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            localSelectedProfile = profile.key
                            onProfileSelected(profile.key)
                        }
                        .padding(vertical = 4.dp)
                ) {
                    RadioButton(
                        selected = localSelectedProfile == profile.key,
                        onClick = null,
                    )
                    Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                        Text(
                            stringResource(profile.labelRes),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            stringResource(profile.descRes),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Mode selector
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ModeSelector(currentMode: String, onModeSelected: (String) -> Unit) {
    var localSelectedMode by remember(currentMode) { mutableStateOf(currentMode) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.bridge_mode_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            MODES.forEach { mode ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            localSelectedMode = mode.key
                            onModeSelected(mode.key)
                        }
                        .padding(vertical = 4.dp)
                ) {
                    RadioButton(
                        selected = localSelectedMode == mode.key,
                        onClick = null
                    )
                    Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                        Text(
                            stringResource(mode.labelRes),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            stringResource(mode.descRes),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Single bridge row
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun BridgeRow(address: String, protocol: String, reachable: Boolean) {
    val isDark = isSystemInDarkTheme()
    val reachableColor = if (isDark) Color(0xFF81C784) else Color(0xFF2E7D32)
    val unreachableColor = if (isDark) Color(0xFFEF9A9A) else Color(0xFFC62828)
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = if (reachable) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = if (reachable) reachableColor else unreachableColor,
                modifier = Modifier.size(20.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(address, style = MaterialTheme.typography.bodyMedium)
                if (protocol.isNotBlank()) {
                    Text(protocol, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(
                stringResource(if (reachable) R.string.bridge_status_online else R.string.bridge_status_offline),
                style = MaterialTheme.typography.labelSmall,
                color = if (reachable) reachableColor else unreachableColor
            )
        }
    }
}
