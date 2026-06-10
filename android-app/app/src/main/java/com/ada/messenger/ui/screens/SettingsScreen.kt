package com.ada.messenger.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import com.ada.messenger.BuildConfig
import com.ada.messenger.R
import com.ada.messenger.core.AdaConfig
import com.ada.messenger.core.AdaCoreViewModel
import com.ada.messenger.core.LocalMeshManager
import com.ada.messenger.core.PinCheckResult
import com.ada.messenger.ui.components.AvatarPickerDialog
import com.ada.messenger.ui.components.OwnAvatar
import com.ada.messenger.ui.theme.ThemeMode
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings screen.
 * Currently exposes:
 * - PIN code management (set / change / disable)
 *
 * Passed [patternCells] are the cells that were used to login in this session
 * (held in memory, never persisted as plaintext). They're needed to encrypt a new
 * PIN store without asking the user to re-enter the pattern.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: AdaCoreViewModel,
    patternCells: ByteArray?,
    onBack: () -> Unit,
    onLinkDesktop: () -> Unit = {},
    currentThemeMode: ThemeMode = ThemeMode.SYSTEM,
    currentDynamicColor: Boolean = true,
    onThemeModeChange: (ThemeMode) -> Unit = {},
    onDynamicColorChange: (Boolean) -> Unit = {},
    currentLocaleTag: String = "",
    onLocaleChange: (String) -> Unit = {},
    onScreenshotPolicyChange: () -> Unit = {},
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val scope   = rememberCoroutineScope()
    val appLock = viewModel.appLock

    // Local states that mirror appLock (re-read after changes)
    var pinEnabled       by remember { mutableStateOf(appLock.isPinEnabled) }
    var cleanPinEnabled  by remember { mutableStateOf(appLock.isCleanPinEnabled) }
    var killPinEnabled   by remember { mutableStateOf(appLock.isKillPinEnabled) }
    var notifContent     by remember { mutableStateOf(appLock.notificationShowContent) }
    var allowScreenshots by remember { mutableStateOf(appLock.allowScreenshots) }
    
    // Data & Retention settings
    var autoWipeDays by remember { mutableStateOf(appLock.autoWipeDays) }
    var cacheSizeMb by remember { mutableStateOf(appLock.cacheSizeMb) }
    var attachmentAgeDays by remember { mutableStateOf(appLock.attachmentAgeDays) }

    var showAutoWipeDialog by remember { mutableStateOf(false) }
    var showCacheSizeDialog by remember { mutableStateOf(false) }
    var showAttachmentAgeDialog by remember { mutableStateOf(false) }
    val autoWipeSummary = if (autoWipeDays <= 0) {
        stringResource(R.string.settings_auto_wipe_desc) + " (Отключено)"
    } else {
        stringResource(R.string.settings_auto_wipe_desc) + " ($autoWipeDays)"
    }
    
    // Connectivity — persisted across settings screen open/close
    val meshPrefs = remember { context.getSharedPreferences("ada_mesh", android.content.Context.MODE_PRIVATE) }
    var meshEnabled by remember { mutableStateOf(meshPrefs.getBoolean("mesh_enabled", false)) }
    val bootstrapPrefs = remember { context.getSharedPreferences(AdaConfig.BOOTSTRAP_PREFS, android.content.Context.MODE_PRIVATE) }
    var customManifestUrl by remember { mutableStateOf(bootstrapPrefs.getString(AdaConfig.KEY_CUSTOM_MANIFEST_URL, "").orEmpty()) }
    var customManifestPublicKey by remember { mutableStateOf(bootstrapPrefs.getString(AdaConfig.KEY_CUSTOM_MANIFEST_PUBLIC_KEY, "").orEmpty()) }
    var showCustomBootstrapDialog by remember { mutableStateOf(false) }

    // PIN dialog state
    var showPinDialog    by remember { mutableStateOf(false) }
    var pinDialogMode    by remember { mutableStateOf(PinDialogMode.SET) } // SET | CHANGE | DISABLE_CONFIRM

    // Clean/Kill PIN dialog state
    var showCleanDialog  by remember { mutableStateOf(false) }
    var cleanDialogMode  by remember { mutableStateOf(ExtraDialogMode.SET) }
    var showKillDialog   by remember { mutableStateOf(false) }
    var killDialogMode   by remember { mutableStateOf(ExtraDialogMode.SET) }

    // Снэкбар
    val snackState = remember { SnackbarHostState() }

    // Аватарка
    val myAvatarIndex by viewModel.myAvatarIndex.collectAsState()
    var showAvatarPicker by remember { mutableStateOf(false) }

    // Recovery export
    var showRecoveryCodeDialog by remember { mutableStateOf(false) }
    var recoveryCode by remember { mutableStateOf("") }
    var showRecoveryDialog by remember { mutableStateOf(false) }
    var recoveryPassword by remember { mutableStateOf("") }
    var showRecoveryPassword by remember { mutableStateOf(false) }
    var pendingRecoveryPassword by remember { mutableStateOf<String?>(null) }

    fun reload() {
        pinEnabled      = appLock.isPinEnabled
        cleanPinEnabled = appLock.isCleanPinEnabled
        killPinEnabled  = appLock.isKillPinEnabled
        notifContent    = appLock.notificationShowContent
        allowScreenshots = appLock.allowScreenshots
    }

    // Zero pattern cells when the user leaves Settings to minimise in-memory exposure.
    DisposableEffect(Unit) {
        onDispose { viewModel.zeroPatternCells() }
    }

    val exportRecoveryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val password = pendingRecoveryPassword
        pendingRecoveryPassword = null
        if (uri == null || password == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            try {
                viewModel.exportRecoveryBundle(uri, password)
                withContext(Dispatchers.Main) {
                    snackState.showSnackbar(context.getString(R.string.recovery_export_saved))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    snackState.showSnackbar(
                        e.message ?: context.getString(R.string.recovery_export_failed)
                    )
                }
            }
        }
    }

    val meshPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        val meshMgr = LocalMeshManager.getInstance(context, viewModel.core)
        if (allGranted) {
            meshEnabled = true
            meshPrefs.edit().putBoolean("mesh_enabled", true).apply()
            meshMgr.start()
            scope.launch {
                val p2pOn = meshMgr.isWifiDirectActive
                val btOn = try {
                    (context.getSystemService(android.content.Context.BLUETOOTH_SERVICE)
                        as? android.bluetooth.BluetoothManager)?.adapter?.isEnabled == true
                } catch (e: Exception) { false }
                when {
                    !btOn && !p2pOn -> snackState.showSnackbar("Mesh готов. Включите Bluetooth или отключите точку доступа для Wi-Fi Direct")
                    !btOn -> snackState.showSnackbar("Mesh активен (Wi-Fi Direct). Включите Bluetooth для BLE-канала")
                    !p2pOn -> snackState.showSnackbar("Mesh активен (BLE). Wi-Fi Direct недоступен — активна точка доступа")
                    else -> snackState.showSnackbar("Mesh-сеть активирована (BLE + Wi-Fi Direct)")
                }
            }
        } else {
            meshEnabled = false
            meshPrefs.edit().putBoolean("mesh_enabled", false).apply()
            scope.launch { snackState.showSnackbar("Разрешения Bluetooth не предоставлены — mesh недоступен") }
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                    actionIconContentColor = MaterialTheme.colorScheme.onBackground,
                ),
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.button_back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            // ── Section: Avatar ──────────────────────────────────────────────
            SectionLabel(stringResource(R.string.settings_section_avatar))
            SettingsRow(
                icon    = { OwnAvatar(index = myAvatarIndex, size = 32.dp) },
                title   = stringResource(R.string.settings_my_avatar),
                subtitle = stringResource(R.string.settings_my_avatar_desc),
                trailing = {
                    Icon(
                        Icons.Outlined.Edit,
                        contentDescription = "Изменить",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
                onClick = { showAvatarPicker = true },
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // ── Section: App lock ──────────────────────────────────────────
            SectionLabel(stringResource(R.string.settings_section_lock))

            // PIN
            SettingsRow(
                icon    = { Icon(Icons.Outlined.Pin, contentDescription = null) },
                title   = if (pinEnabled) stringResource(R.string.settings_pin_set) else stringResource(R.string.settings_pin),
                subtitle = if (pinEnabled) stringResource(R.string.settings_pin_set_desc) else stringResource(R.string.settings_pin_unset_desc),
                trailing = {
                    if (pinEnabled) {
                        Icon(
                            Icons.Outlined.Edit,
                            contentDescription = "Изменить",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        Switch(
                            checked = false,
                            onCheckedChange = {
                                if (patternCells != null) {
                                    pinDialogMode = PinDialogMode.SET
                                    showPinDialog = true
                                } else {
                                    scope.launch {
                                        snackState.showSnackbar(context.getString(R.string.settings_pin_need_pattern))
                                    }
                                }
                            },
                        )
                    }
                },
                onClick = {
                    if (pinEnabled) {
                        pinDialogMode = PinDialogMode.CHANGE
                        showPinDialog = true
                    } else if (patternCells != null) {
                        pinDialogMode = PinDialogMode.SET
                        showPinDialog = true
                    } else {
                        scope.launch {
                            snackState.showSnackbar(context.getString(R.string.settings_pin_need_pattern))
                        }
                    }
                },
            )

            if (pinEnabled) {
                TextButton(
                    onClick = {
                        pinDialogMode = PinDialogMode.DISABLE_CONFIRM
                        showPinDialog = true
                    },
                    modifier = Modifier.padding(start = 56.dp, bottom = 4.dp),
                ) {
                    Text(stringResource(R.string.settings_pin_disable), color = MaterialTheme.colorScheme.error)
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // ── Section: Notifications ──────────────────────────────────────────────
            SectionLabel(stringResource(R.string.settings_section_notifications))
            SettingsRow(
                icon    = { Icon(Icons.Outlined.Notifications, contentDescription = null) },
                title   = stringResource(R.string.settings_notif_content),
                subtitle = stringResource(R.string.settings_notif_content_desc),
                trailing = {
                    Switch(
                        checked = notifContent,
                        onCheckedChange = {
                            appLock.notificationShowContent = it
                            notifContent = it
                        },
                    )
                },
                onClick = {
                    val newVal = !notifContent
                    appLock.notificationShowContent = newVal
                    notifContent = newVal
                },
            )

            SettingsRow(
                icon    = { Icon(Icons.Outlined.Visibility, contentDescription = null) },
                title   = stringResource(R.string.settings_screenshots),
                subtitle = stringResource(R.string.settings_screenshots_desc),
                trailing = {
                    Switch(
                        checked = allowScreenshots,
                        onCheckedChange = {
                            appLock.allowScreenshots = it
                            allowScreenshots = it
                            onScreenshotPolicyChange()
                        },
                    )
                },
                onClick = {
                    val newVal = !allowScreenshots
                    appLock.allowScreenshots = newVal
                    allowScreenshots = newVal
                    onScreenshotPolicyChange()
                },
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // ── Section: Connectivity & Network ─────────────────────────────────
            SectionLabel(stringResource(R.string.settings_section_communication))
            SettingsRow(
                icon    = { Icon(Icons.Outlined.Bluetooth, contentDescription = null) },
                title   = "Off-Grid Mesh Mode",
                subtitle = stringResource(R.string.settings_mesh_subtitle),
                trailing = {
                    Switch(
                        checked = meshEnabled,
                        onCheckedChange = { isChecked ->
                            val meshMgr = LocalMeshManager.getInstance(context, viewModel.core)
                            if (!isChecked) {
                                meshEnabled = false
                                meshPrefs.edit().putBoolean("mesh_enabled", false).apply()
                                meshMgr.stop()
                            } else {
                                val missing = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                                    listOf(
                                        android.Manifest.permission.BLUETOOTH_SCAN,
                                        android.Manifest.permission.BLUETOOTH_ADVERTISE,
                                        android.Manifest.permission.BLUETOOTH_CONNECT,
                                    ).filter { context.checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
                                } else emptyList()
                                if (missing.isNotEmpty()) {
                                    meshPermLauncher.launch(missing.toTypedArray())
                                } else {
                                    meshEnabled = true
                                    meshPrefs.edit().putBoolean("mesh_enabled", true).apply()
                                    meshMgr.start()
                                    scope.launch {
                                        val p2pOn = meshMgr.isWifiDirectActive
                                        val btOn = try {
                                            (context.getSystemService(android.content.Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter?.isEnabled == true
                                        } catch (e: Exception) { false }
                                        when {
                                            !btOn && !p2pOn -> snackState.showSnackbar("Mesh готов. Включите Bluetooth или отключите точку доступа для Wi-Fi Direct")
                                            !btOn -> snackState.showSnackbar("Mesh активен (Wi-Fi Direct). Включите Bluetooth для BLE-канала")
                                            !p2pOn -> snackState.showSnackbar("Mesh активен (BLE). Wi-Fi Direct недоступен — активна точка доступа")
                                            else -> snackState.showSnackbar("Mesh-сеть активирована (BLE + Wi-Fi Direct)")
                                        }
                                    }
                                }
                            }
                        },
                    )
                },
                onClick = {
                    val meshMgr = LocalMeshManager.getInstance(context, viewModel.core)
                    if (meshEnabled) {
                        meshEnabled = false
                        meshPrefs.edit().putBoolean("mesh_enabled", false).apply()
                        meshMgr.stop()
                    } else {
                        val missing = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                            listOf(
                                android.Manifest.permission.BLUETOOTH_SCAN,
                                android.Manifest.permission.BLUETOOTH_ADVERTISE,
                                android.Manifest.permission.BLUETOOTH_CONNECT,
                            ).filter { context.checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
                        } else emptyList()
                        if (missing.isNotEmpty()) {
                            meshPermLauncher.launch(missing.toTypedArray())
                        } else {
                            meshEnabled = true
                            meshPrefs.edit().putBoolean("mesh_enabled", true).apply()
                            meshMgr.start()
                            scope.launch {
                                val p2pOn = meshMgr.isWifiDirectActive
                                val btOn = try {
                                    (context.getSystemService(android.content.Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter?.isEnabled == true
                                } catch (e: Exception) { false }
                                when {
                                    !btOn && !p2pOn -> snackState.showSnackbar("Mesh готов. Включите Bluetooth или отключите точку доступа для Wi-Fi Direct")
                                    !btOn -> snackState.showSnackbar("Mesh активен (Wi-Fi Direct). Включите Bluetooth для BLE-канала")
                                    !p2pOn -> snackState.showSnackbar("Mesh активен (BLE). Wi-Fi Direct недоступен — активна точка доступа")
                                    else -> snackState.showSnackbar("Mesh-сеть активирована (BLE + Wi-Fi Direct)")
                                }
                            }
                        }
                    }
                },
            )

            SettingsRow(
                icon = { Icon(Icons.Outlined.PhonelinkSetup, contentDescription = null) },
                title = "Привязать ПК",
                subtitle = "Войти в ADA на компьютере через Bluetooth",
                trailing = {
                    Icon(
                        Icons.Outlined.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                onClick = onLinkDesktop,
            )

            val bootstrapSubtitle = if (customManifestUrl.isBlank()) {
                stringResource(R.string.settings_custom_bootstrap_desc)
            } else {
                stringResource(R.string.settings_custom_bootstrap_configured, customManifestUrl)
            }
            SettingsRow(
                icon = { Icon(Icons.Outlined.Public, contentDescription = null) },
                title = stringResource(R.string.settings_custom_bootstrap),
                subtitle = bootstrapSubtitle,
                trailing = {
                    Icon(
                        Icons.Outlined.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                onClick = { showCustomBootstrapDialog = true },
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // ── Section: Data & Retention ──────────────────────────────────────────────────
            SectionLabel(stringResource(R.string.settings_section_data_retention))
            SettingsRow(
                icon = { Icon(Icons.Outlined.Timer, contentDescription = null) },
                title = stringResource(R.string.settings_auto_wipe),
                subtitle = autoWipeSummary,
                trailing = {},
                onClick = { showAutoWipeDialog = true },
            )
            SettingsRow(
                icon = { Icon(Icons.Outlined.Storage, contentDescription = null) },
                title = stringResource(R.string.settings_cache_limit),
                subtitle = stringResource(R.string.settings_cache_limit_desc) + " ($cacheSizeMb)",
                trailing = {},
                onClick = { showCacheSizeDialog = true },
            )
            SettingsRow(
                icon = { Icon(Icons.Outlined.History, contentDescription = null) },
                title = stringResource(R.string.settings_attachment_age),
                subtitle = stringResource(R.string.settings_attachment_age_desc) + " ($attachmentAgeDays)",
                trailing = {},
                onClick = { showAttachmentAgeDialog = true },
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // ── Section: Clean PIN ────────────────────────────────────────────────────
            SectionLabel(stringResource(R.string.settings_section_clean_pin))
            SettingsRow(
                icon    = { Icon(Icons.Outlined.Shield, contentDescription = null) },
                title   = if (cleanPinEnabled) stringResource(R.string.settings_clean_pin_active) else stringResource(R.string.settings_clean_pin),
                subtitle = stringResource(R.string.settings_clean_pin_desc),
                trailing = {
                    if (cleanPinEnabled) {
                        Icon(
                            Icons.Outlined.Edit,
                            contentDescription = "Изменить",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        Switch(
                            checked = false,
                            onCheckedChange = {
                                cleanDialogMode = ExtraDialogMode.SET
                                showCleanDialog = true
                            },
                        )
                    }
                },
                onClick = {
                    if (cleanPinEnabled) {
                        cleanDialogMode = ExtraDialogMode.CHANGE
                        showCleanDialog = true
                    } else {
                        cleanDialogMode = ExtraDialogMode.SET
                        showCleanDialog = true
                    }
                },
            )
            if (cleanPinEnabled) {
                TextButton(
                    onClick = {
                        cleanDialogMode = ExtraDialogMode.DISABLE_CONFIRM
                        showCleanDialog = true
                    },
                    modifier = Modifier.padding(start = 56.dp, bottom = 4.dp),
                ) {
                    Text(stringResource(R.string.settings_clean_pin_disable), color = MaterialTheme.colorScheme.error)
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // ── Section: Kill PIN ──────────────────────────────────────────────────────
            SectionLabel(stringResource(R.string.settings_section_kill_pin))
            SettingsRow(
                icon    = { Icon(Icons.Outlined.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                title   = if (killPinEnabled) stringResource(R.string.settings_kill_pin_active) else stringResource(R.string.settings_kill_pin),
                subtitle = stringResource(R.string.settings_kill_pin_desc),
                trailing = {
                    if (killPinEnabled) {
                        Icon(
                            Icons.Outlined.Edit,
                            contentDescription = "Изменить",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        Switch(
                            checked = false,
                            onCheckedChange = {
                                killDialogMode = ExtraDialogMode.SET
                                showKillDialog = true
                            },
                        )
                    }
                },
                onClick = {
                    if (killPinEnabled) {
                        killDialogMode = ExtraDialogMode.CHANGE
                        showKillDialog = true
                    } else {
                        killDialogMode = ExtraDialogMode.SET
                        showKillDialog = true
                    }
                },
            )
            if (killPinEnabled) {
                TextButton(
                    onClick = {
                        killDialogMode = ExtraDialogMode.DISABLE_CONFIRM
                        showKillDialog = true
                    },
                    modifier = Modifier.padding(start = 56.dp, bottom = 4.dp),
                ) {
                    Text(stringResource(R.string.settings_kill_pin_disable), color = MaterialTheme.colorScheme.error)
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // ── Section: Appearance ─────────────────────────────────────────────
            SectionLabel(stringResource(R.string.settings_section_appearance))

            // Theme mode selector
            var showThemeDialog by remember { mutableStateOf(false) }
            val themeModeLabel = when (currentThemeMode) {
                ThemeMode.SYSTEM -> stringResource(R.string.settings_theme_system)
                ThemeMode.LIGHT  -> stringResource(R.string.settings_theme_light)
                ThemeMode.DARK   -> stringResource(R.string.settings_theme_dark)
            }
            SettingsRow(
                icon     = { Icon(Icons.Outlined.Palette, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                title    = stringResource(R.string.settings_theme),
                subtitle = themeModeLabel,
                trailing = {
                    Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                },
                onClick  = { showThemeDialog = true },
            )

            // Dynamic color toggle (Android 12+)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                SettingsRow(
                    icon     = { Icon(Icons.Outlined.ColorLens, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    title    = stringResource(R.string.settings_dynamic_color),
                    subtitle = stringResource(R.string.settings_dynamic_color_desc),
                    trailing = {
                        Switch(
                            checked = currentDynamicColor,
                            onCheckedChange = { onDynamicColorChange(it) },
                        )
                    },
                    onClick  = { onDynamicColorChange(!currentDynamicColor) },
                )
            }

            // Theme picker dialog
            if (showThemeDialog) {
                AlertDialog(
                    onDismissRequest = { showThemeDialog = false },
                    title = { Text(stringResource(R.string.settings_theme_dialog_title)) },
                    text = {
                        Column {
                            ThemeMode.entries.forEach { mode ->
                                val label = when (mode) {
                                    ThemeMode.SYSTEM -> stringResource(R.string.settings_theme_system)
                                    ThemeMode.LIGHT  -> stringResource(R.string.settings_theme_light)
                                    ThemeMode.DARK   -> stringResource(R.string.settings_theme_dark)
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                        .padding(horizontal = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(
                                        selected = mode == currentThemeMode,
                                        onClick = {
                                            onThemeModeChange(mode)
                                            showThemeDialog = false
                                        },
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(label, style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showThemeDialog = false }) {
                            Text(stringResource(R.string.button_cancel))
                        }
                    },
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // ── Section: Language ────────────────────────────────────────────────
            SectionLabel(stringResource(R.string.settings_section_language))

            val languageOptions = remember {
                listOf(
                    "" to "System",       // tag "" = follow system
                    "ru" to "Русский",
                    "en" to "English",
                    "es" to "Español",
                    "fr" to "Français",
                )
            }
            val currentLangLabel = languageOptions.firstOrNull { it.first == currentLocaleTag }?.second
                ?: stringResource(R.string.settings_language_system)

            var showLangDialog by remember { mutableStateOf(false) }

            SettingsRow(
                icon     = { Icon(Icons.Outlined.Language, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                title    = stringResource(R.string.settings_language),
                subtitle = currentLangLabel,
                trailing = {
                    Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                },
                onClick  = { showLangDialog = true },
            )

            if (showLangDialog) {
                AlertDialog(
                    onDismissRequest = { showLangDialog = false },
                    title = { Text(stringResource(R.string.settings_language_dialog_title)) },
                    text = {
                        Column {
                            languageOptions.forEach { (tag, label) ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                        .padding(horizontal = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(
                                        selected = tag == currentLocaleTag,
                                        onClick = {
                                            onLocaleChange(tag)
                                            showLangDialog = false
                                        },
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(label, style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showLangDialog = false }) {
                            Text(stringResource(R.string.button_cancel))
                        }
                    },
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // ── Section: Storage ─────────────────────────────────────────────
            SectionLabel(stringResource(R.string.settings_section_storage))
            run {
                var cacheBytes by remember { mutableStateOf(0L) }
                var cacheCleared by remember { mutableStateOf(false) }
                LaunchedEffect(cacheCleared) {
                    cacheBytes = withContext(Dispatchers.IO) { viewModel.getMediaCacheSize() }
                }
                val sizeText = remember(cacheBytes) { android.text.format.Formatter.formatShortFileSize(context, cacheBytes) }
                SettingsRow(
                    icon = { Icon(Icons.Outlined.FolderOpen, contentDescription = null) },
                    title = stringResource(R.string.settings_cache_label),
                    subtitle = stringResource(R.string.settings_cache_desc) + "\n" +
                        stringResource(R.string.settings_cache_size, sizeText),
                    trailing = {
                        TextButton(
                            onClick = {
                                scope.launch(Dispatchers.IO) {
                                    viewModel.clearMediaCache()
                                    cacheCleared = !cacheCleared
                                    withContext(Dispatchers.Main) {
                                        snackState.showSnackbar(context.getString(R.string.settings_cache_cleared))
                                    }
                                }
                            },
                            enabled = cacheBytes > 0,
                        ) {
                            Text(stringResource(R.string.settings_cache_clear))
                        }
                    },
                    onClick = {},
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // ── Section: Recovery ───────────────────────────────────────────
            SectionLabel(stringResource(R.string.settings_section_recovery))
            SettingsRow(
                icon = { Icon(Icons.Outlined.Password, contentDescription = null) },
                title = stringResource(R.string.settings_recovery_code),
                subtitle = stringResource(R.string.settings_recovery_code_desc),
                trailing = {
                    Icon(
                        Icons.Outlined.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
                onClick = {
                    try {
                        recoveryCode = viewModel.getRecoveryCode()
                        showRecoveryCodeDialog = true
                    } catch (e: Exception) {
                        scope.launch {
                            snackState.showSnackbar(
                                e.message ?: context.getString(R.string.recovery_import_failed)
                            )
                        }
                    }
                },
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            SettingsRow(
                icon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
                title = stringResource(R.string.settings_recovery_export),
                subtitle = stringResource(R.string.settings_recovery_export_desc),
                trailing = {
                    Icon(
                        Icons.Outlined.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
                onClick = {
                    recoveryPassword = ""
                    showRecoveryPassword = false
                    showRecoveryDialog = true
                },
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // ── Section: About (V-31) ───────────────────────────────────────────
            SectionLabel(stringResource(R.string.settings_section_about))
            ListItem(
                headlineContent = { Text("ADA Messenger", fontWeight = FontWeight.Medium) },
                supportingContent = {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            stringResource(R.string.settings_about_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "v${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                leadingContent = {
                    Icon(
                        Icons.Outlined.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    // ── PIN dialog ─────────────────────────────────────────────────────────
    if (showPinDialog) {
        PinSetDialog(
            mode        = pinDialogMode,
            patternCells = patternCells,
            appLock     = appLock,
            onDismiss   = { showPinDialog = false },
            onSuccess   = { msg ->
                showPinDialog = false
                reload()
                scope.launch { snackState.showSnackbar(msg) }
            },
        )
    }
    // ── Clean PIN dialog ───────────────────────────────────────────────
    if (showCleanDialog) {
        ExtraPinDialog(
            mode      = cleanDialogMode,
            title     = stringResource(R.string.settings_clean_pin),
            appLock   = appLock,
            isKill    = false,
            onDismiss = { showCleanDialog = false },
            onSuccess = { msg ->
                showCleanDialog = false
                reload()
                scope.launch { snackState.showSnackbar(msg) }
            },
        )
    }

    // ── Kill PIN dialog ──────────────────────────────────────────────────
    if (showKillDialog) {
        ExtraPinDialog(
            mode      = killDialogMode,
            title     = stringResource(R.string.settings_kill_pin),
            appLock   = appLock,
            isKill    = true,
            onDismiss = { showKillDialog = false },
            onSuccess = { msg ->
                showKillDialog = false
                reload()
                scope.launch { snackState.showSnackbar(msg) }
            },
        )
    }

    // ── Avatar picker dialog ─────────────────────────────────────────────
    if (showAvatarPicker) {
        AvatarPickerDialog(
            currentIndex = myAvatarIndex,
            onSelect = { index ->
                viewModel.setMyAvatarIndex(index)
                showAvatarPicker = false
            },
            onDismiss = { showAvatarPicker = false },
        )
    }

    if (showRecoveryDialog) {
        AlertDialog(
            onDismissRequest = { showRecoveryDialog = false },
            title = { Text(stringResource(R.string.recovery_export_dialog_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.recovery_export_dialog_text),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = recoveryPassword,
                        onValueChange = { recoveryPassword = it },
                        label = { Text(stringResource(R.string.recovery_password_label)) },
                        supportingText = {
                            Text(stringResource(R.string.recovery_password_hint))
                        },
                        singleLine = true,
                        visualTransformation = if (showRecoveryPassword) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            IconButton(onClick = { showRecoveryPassword = !showRecoveryPassword }) {
                                Icon(
                                    if (showRecoveryPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                    contentDescription = null,
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingRecoveryPassword = recoveryPassword
                        showRecoveryDialog = false
                        exportRecoveryLauncher.launch("ada-recovery.adarec")
                    },
                    enabled = recoveryPassword.length >= 8,
                ) {
                    Text(stringResource(R.string.button_export))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRecoveryDialog = false }) {
                    Text(stringResource(R.string.button_cancel))
                }
            },
        )
    }

    if (showRecoveryCodeDialog) {
        AlertDialog(
            onDismissRequest = { showRecoveryCodeDialog = false },
            title = { Text(stringResource(R.string.recovery_code_dialog_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        stringResource(R.string.recovery_code_dialog_text),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SelectionContainer {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                        ) {
                            Text(
                                text = recoveryCode,
                                modifier = Modifier.padding(16.dp),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(recoveryCode))
                        scope.launch {
                            snackState.showSnackbar(context.getString(R.string.recovery_code_copied))
                        }
                    },
                ) {
                    Text(stringResource(R.string.button_copy))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRecoveryCodeDialog = false }) {
                    Text(stringResource(R.string.button_close))
                }
            },
        )
    }

    if (showCustomBootstrapDialog) {
        var dialogManifestUrl by remember(customManifestUrl) { mutableStateOf(customManifestUrl) }
        var dialogPublicKey by remember(customManifestPublicKey) { mutableStateOf(customManifestPublicKey) }
        val trimmedManifestUrl = dialogManifestUrl.trim()
        val canImport = trimmedManifestUrl.startsWith("https://") || trimmedManifestUrl.startsWith("http://")
        AlertDialog(
            onDismissRequest = { showCustomBootstrapDialog = false },
            title = { Text(stringResource(R.string.settings_custom_bootstrap_dialog_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        stringResource(R.string.settings_custom_bootstrap_dialog_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = dialogManifestUrl,
                        onValueChange = { dialogManifestUrl = it },
                        label = { Text(stringResource(R.string.settings_custom_bootstrap_url_label)) },
                        supportingText = { Text(stringResource(R.string.settings_custom_bootstrap_url_hint)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = dialogPublicKey,
                        onValueChange = { dialogPublicKey = it },
                        label = { Text(stringResource(R.string.settings_custom_bootstrap_key_label)) },
                        supportingText = { Text(stringResource(R.string.settings_custom_bootstrap_key_hint)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Done),
                        minLines = 2,
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(
                        onClick = {
                            bootstrapPrefs.edit()
                                .remove(AdaConfig.KEY_CUSTOM_MANIFEST_URL)
                                .remove(AdaConfig.KEY_CUSTOM_MANIFEST_PUBLIC_KEY)
                                .apply()
                            customManifestUrl = ""
                            customManifestPublicKey = ""
                            dialogManifestUrl = ""
                            dialogPublicKey = ""
                            scope.launch { snackState.showSnackbar(context.getString(R.string.settings_custom_bootstrap_cleared)) }
                        },
                        enabled = customManifestUrl.isNotBlank() || customManifestPublicKey.isNotBlank(),
                    ) {
                        Text(stringResource(R.string.button_clear))
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val url = dialogManifestUrl.trim()
                        val key = dialogPublicKey.trim()
                        bootstrapPrefs.edit()
                            .putString(AdaConfig.KEY_CUSTOM_MANIFEST_URL, url)
                            .putString(AdaConfig.KEY_CUSTOM_MANIFEST_PUBLIC_KEY, key)
                            .apply()
                        customManifestUrl = url
                        customManifestPublicKey = key
                        scope.launch {
                            val result = viewModel.importCustomBridgeBootstrap(url, key)
                            snackState.showSnackbar(
                                if (result.success) {
                                    context.getString(R.string.settings_custom_bootstrap_saved)
                                } else {
                                    result.error ?: context.getString(R.string.bridge_manifest_import_failed)
                                }
                            )
                        }
                        showCustomBootstrapDialog = false
                    },
                    enabled = canImport,
                ) {
                    Text(stringResource(R.string.bridge_manifest_import_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCustomBootstrapDialog = false }) {
                    Text(stringResource(R.string.button_cancel))
                }
            },
        )
    }

    if (showAutoWipeDialog) {
        var input by remember { mutableStateOf(autoWipeDays.toString()) }
        var error by remember { mutableStateOf<String?>(null) }
        val autoWipeErrorMsg = stringResource(R.string.settings_auto_wipe_dialog_error)
        AlertDialog(
            onDismissRequest = { showAutoWipeDialog = false },
            title = { Text(stringResource(R.string.settings_auto_wipe)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.settings_auto_wipe_dialog_hint))
                    OutlinedTextField(
                        value = input,
                        onValueChange = {
                            input = it.filter(Char::isDigit)
                            error = null
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                    error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val parsed = input.toIntOrNull()
                    if (parsed == null) {
                        error = autoWipeErrorMsg
                        return@Button
                    }
                    appLock.autoWipeDays = parsed
                    autoWipeDays = parsed
                    showAutoWipeDialog = false
                }) { Text(stringResource(R.string.button_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showAutoWipeDialog = false }) { Text(stringResource(R.string.button_cancel)) }
            }
        )
    }
    
    if (showCacheSizeDialog) {
        var input by remember { mutableStateOf(cacheSizeMb.toString()) }
        AlertDialog(
            onDismissRequest = { showCacheSizeDialog = false },
            title = { Text(stringResource(R.string.settings_cache_limit)) },
            text = {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
            },
            confirmButton = {
                Button(onClick = {
                    input.toIntOrNull()?.let {
                        appLock.cacheSizeMb = it
                        cacheSizeMb = it
                    }
                    showCacheSizeDialog = false
                }) { Text(stringResource(R.string.button_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showCacheSizeDialog = false }) { Text(stringResource(R.string.button_cancel)) }
            }
        )
    }
    
    if (showAttachmentAgeDialog) {
        var input by remember { mutableStateOf(attachmentAgeDays.toString()) }
        AlertDialog(
            onDismissRequest = { showAttachmentAgeDialog = false },
            title = { Text(stringResource(R.string.settings_attachment_age)) },
            text = {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
            },
            confirmButton = {
                Button(onClick = {
                    input.toIntOrNull()?.let {
                        appLock.attachmentAgeDays = it
                        attachmentAgeDays = it
                    }
                    showAttachmentAgeDialog = false
                }) { Text(stringResource(R.string.button_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showAttachmentAgeDialog = false }) { Text(stringResource(R.string.button_cancel)) }
            }
        )
    }
}

// ── PIN dialog ────────────────────────────────────────────────────────────────

private enum class PinDialogMode { SET, CHANGE, DISABLE_CONFIRM }

@Composable
private fun PinSetDialog(
    mode: PinDialogMode,
    patternCells: ByteArray?,
    appLock: com.ada.messenger.core.AppLockManager,
    onDismiss: () -> Unit,
    onSuccess: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()

    var currentPin  by remember { mutableStateOf("") }
    var newPin      by remember { mutableStateOf("") }
    var confirmPin  by remember { mutableStateOf("") }
    var errorMsg    by remember { mutableStateOf<String?>(null) }
    var showCurrent by remember { mutableStateOf(false) }
    var showNew     by remember { mutableStateOf(false) }
    var showConfirm by remember { mutableStateOf(false) }
    var saving      by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when (mode) {
                    PinDialogMode.SET             -> stringResource(R.string.settings_pin_dialog_set)
                    PinDialogMode.CHANGE          -> stringResource(R.string.settings_pin_dialog_change)
                    PinDialogMode.DISABLE_CONFIRM -> stringResource(R.string.settings_pin_dialog_disable)
                },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (mode == PinDialogMode.DISABLE_CONFIRM) {
                    // Ask to confirm current PIN before disabling
                    Text(stringResource(R.string.settings_pin_enter_current))
                    PinTextField(
                        value = currentPin,
                        onValueChange = { if (it.length <= 4) currentPin = it },
                        label = stringResource(R.string.settings_pin_label_current),
                        showText = showCurrent,
                        onToggleShow = { showCurrent = !showCurrent },
                    )
                } else {
                    if (mode == PinDialogMode.CHANGE) {
                        PinTextField(
                            value = currentPin,
                            onValueChange = { if (it.length <= 4) currentPin = it },
                            label = stringResource(R.string.settings_pin_label_current),
                            showText = showCurrent,
                            onToggleShow = { showCurrent = !showCurrent },
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    PinTextField(
                        value = newPin,
                        onValueChange = { if (it.length <= 4) newPin = it },
                        label = stringResource(R.string.settings_pin_label_new),
                        showText = showNew,
                        onToggleShow = { showNew = !showNew },
                    )
                    PinTextField(
                        value = confirmPin,
                        onValueChange = { if (it.length <= 4) confirmPin = it },
                        label = stringResource(R.string.settings_pin_label_repeat),
                        showText = showConfirm,
                        onToggleShow = { showConfirm = !showConfirm },
                    )
                }

                errorMsg?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !saving,
                onClick = {
                scope.launch {
                    if (saving) return@launch
                    saving = true
                    try {
                    when (mode) {
                        PinDialogMode.DISABLE_CONFIRM -> {
                            val valid = withContext(Dispatchers.IO) {
                                appLock.verifyPinFast(currentPin)
                            }
                            if (!valid) {
                                errorMsg = "Неверный PIN"
                                saving = false
                                return@launch
                            }
                            withContext(Dispatchers.IO) { appLock.disablePin() }
                            saving = false
                            onSuccess("PIN отключён")
                        }
                        PinDialogMode.CHANGE -> {
                            if (newPin.length != 4) { errorMsg = "PIN должен быть ровно 4 цифры"; saving = false; return@launch }
                            if (newPin != confirmPin) { errorMsg = "PIN-коды не совпадают"; saving = false; return@launch }
                            // decryptCellsWithPin verifies the current PIN via PBKDF2 + AES-GCM
                            // in a single IO-bound call — null means wrong PIN.
                            val cells = withContext(Dispatchers.IO) {
                                appLock.decryptCellsWithPin(currentPin)
                            } ?: run { errorMsg = "Неверный текущий PIN"; saving = false; return@launch }
                            withContext(Dispatchers.IO) { appLock.enablePin(newPin, cells) }
                            cells.fill(0) // M2: zero cells after use
                            saving = false
                            onSuccess("PIN изменён")
                        }
                        PinDialogMode.SET -> {
                            if (newPin.length != 4) { errorMsg = "PIN должен быть ровно 4 цифры"; saving = false; return@launch }
                            if (newPin != confirmPin) { errorMsg = "PIN-коды не совпадают"; saving = false; return@launch }
                            val sourceCells = patternCells ?: run { errorMsg = "Нет данных — войдите по узору"; saving = false; return@launch }
                            // Use an isolated copy so we never wipe shared ViewModel state.
                            val cells = sourceCells.copyOf()
                            withContext(Dispatchers.IO) { appLock.enablePin(newPin, cells) }
                            cells.fill(0)
                            saving = false
                            onSuccess("PIN установлен")
                        }
                    }
                    } catch (e: Exception) {
                        errorMsg = e.message ?: "Ошибка сохранения PIN"
                        saving = false
                    }
                }
            }
            ) {
                if (saving) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.button_save))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.button_cancel)) }
        },
        shape = MaterialTheme.shapes.medium,
    )
}

@Composable
private fun PinTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    showText: Boolean,
    onToggleShow: () -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.NumberPassword,
            imeAction    = ImeAction.Next,
        ),
        visualTransformation = if (showText) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = onToggleShow) {
                Icon(
                    if (showText) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = null,
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

// ── UI helpers ────────────────────────────────────────────────────────────────

// V-30: Improved section header with colored accent
@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.8.sp,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

// V-28: M3 ListItem-style settings row
@Composable
private fun SettingsRow(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    trailing: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
        tonalElevation = 0.dp,
    ) {
        ListItem(
            headlineContent = { Text(title, fontWeight = FontWeight.Medium) },
            supportingContent = {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            leadingContent = icon,
            trailingContent = trailing,
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
        )
    }
}

// ── Clean / Kill PIN dialog ───────────────────────────────────────────────────

private enum class ExtraDialogMode { SET, CHANGE, DISABLE_CONFIRM }

/**
 * Reusable dialog for setting/changing/disabling a Clean PIN or Kill PIN.
 * [isKill] == true → Kill PIN (uses [AppLockManager.enableKillPin] / [verifyKillPin] / [disableKillPin])
 * [isKill] == false → Clean PIN
 */
@Composable
private fun ExtraPinDialog(
    mode: ExtraDialogMode,
    title: String,
    appLock: com.ada.messenger.core.AppLockManager,
    isKill: Boolean,
    onDismiss: () -> Unit,
    onSuccess: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()

    var currentPin  by remember { mutableStateOf("") }
    var newPin      by remember { mutableStateOf("") }
    var confirmPin  by remember { mutableStateOf("") }
    var errorMsg    by remember { mutableStateOf<String?>(null) }
    var showCurrent by remember { mutableStateOf(false) }
    var showNew     by remember { mutableStateOf(false) }
    var showConfirm by remember { mutableStateOf(false) }

    val verify: (String) -> Boolean = { pin ->
        if (isKill) appLock.verifyKillPin(pin) else appLock.verifyCleanPin(pin)
    }
    val enable: (String) -> Unit = { pin ->
        if (isKill) appLock.enableKillPin(pin) else appLock.enableCleanPin(pin)
        Unit
    }
    val disable: () -> Unit = {
        if (isKill) appLock.disableKillPin() else appLock.disableCleanPin()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when (mode) {
                    ExtraDialogMode.SET             -> "Установить $title"
                    ExtraDialogMode.CHANGE          -> "Изменить $title"
                    ExtraDialogMode.DISABLE_CONFIRM -> "Отключить $title"
                },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (mode) {
                    ExtraDialogMode.DISABLE_CONFIRM -> {
                        Text("Введите текущий $title для подтверждения:")
                        PinTextField(
                            value = currentPin,
                            onValueChange = { if (it.length <= 4) currentPin = it },
                            label = "Текущий PIN",
                            showText = showCurrent,
                            onToggleShow = { showCurrent = !showCurrent },
                        )
                    }
                    ExtraDialogMode.CHANGE -> {
                        PinTextField(
                            value = currentPin,
                            onValueChange = { if (it.length <= 4) currentPin = it },
                            label = "Текущий PIN",
                            showText = showCurrent,
                            onToggleShow = { showCurrent = !showCurrent },
                        )
                        Spacer(Modifier.height(4.dp))
                        PinTextField(
                            value = newPin,
                            onValueChange = { if (it.length <= 4) newPin = it },
                            label = stringResource(R.string.settings_pin_label_new),
                            showText = showNew,
                            onToggleShow = { showNew = !showNew },
                        )
                        PinTextField(
                            value = confirmPin,
                            onValueChange = { if (it.length <= 4) confirmPin = it },
                            label = stringResource(R.string.settings_pin_label_repeat),
                            showText = showConfirm,
                            onToggleShow = { showConfirm = !showConfirm },
                        )
                    }
                    ExtraDialogMode.SET -> {
                        PinTextField(
                            value = newPin,
                            onValueChange = { if (it.length <= 4) newPin = it },
                            label = stringResource(R.string.settings_pin_label_4digits),
                            showText = showNew,
                            onToggleShow = { showNew = !showNew },
                        )
                        PinTextField(
                            value = confirmPin,
                            onValueChange = { if (it.length <= 4) confirmPin = it },
                            label = stringResource(R.string.settings_pin_label_repeat),
                            showText = showConfirm,
                            onToggleShow = { showConfirm = !showConfirm },
                        )
                    }
                }
                errorMsg?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                scope.launch {
                    errorMsg = null
                    when (mode) {
                        ExtraDialogMode.DISABLE_CONFIRM -> {
                            val valid = withContext(Dispatchers.IO) { verify(currentPin) }
                            if (!valid) { errorMsg = "Неверный PIN"; return@launch }
                            withContext(Dispatchers.IO) { disable() }
                            onSuccess("$title отключён")
                        }
                        ExtraDialogMode.CHANGE -> {
                            if (newPin.length != 4) { errorMsg = "PIN должен быть ровно 4 цифры"; return@launch }
                            if (newPin != confirmPin) { errorMsg = "PIN-коды не совпадают"; return@launch }
                            val valid = withContext(Dispatchers.IO) { verify(currentPin) }
                            if (!valid) { errorMsg = "Неверный текущий PIN"; return@launch }
                            val enableError = withContext(Dispatchers.IO) {
                                runCatching { enable(newPin) }.exceptionOrNull()?.message
                            }
                            if (enableError != null) { errorMsg = enableError; return@launch }
                            onSuccess("$title изменён")
                        }
                        ExtraDialogMode.SET -> {
                            if (newPin.length != 4) { errorMsg = "PIN должен быть ровно 4 цифры"; return@launch }
                            if (newPin != confirmPin) { errorMsg = "PIN-коды не совпадают"; return@launch }
                            val enableError = withContext(Dispatchers.IO) {
                                runCatching { enable(newPin) }.exceptionOrNull()?.message
                            }
                            if (enableError != null) { errorMsg = enableError; return@launch }
                            onSuccess("$title установлен")
                        }
                    }
                }
            }) {
                Text(stringResource(R.string.button_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.button_cancel)) }
        },
        shape = MaterialTheme.shapes.medium,
    )
}
