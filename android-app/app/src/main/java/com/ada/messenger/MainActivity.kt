package com.ada.messenger

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.LocaleListCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavType
import kotlinx.coroutines.launch
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ada.messenger.core.AdaCoreViewModel
import com.ada.messenger.ui.components.AdaHexBackground
import com.ada.messenger.service.ADANotificationService
import com.ada.messenger.service.BatteryOptimizationHelper
import com.ada.messenger.ui.components.BatteryOptimizationDialog
import com.ada.messenger.ui.theme.ADAMessengerTheme
import com.ada.messenger.ui.theme.ThemeMode
import com.ada.messenger.ui.screens.BridgeScreen
import com.ada.messenger.ui.screens.CallScreen
import com.ada.messenger.ui.screens.ChatScreen
import com.ada.messenger.ui.screens.DesktopLinkScreen
import com.ada.messenger.ui.screens.MainScreen
import com.ada.messenger.ui.screens.OnboardingScreen
import com.ada.messenger.ui.screens.PatternRegistrationScreen
import com.ada.messenger.ui.screens.RecoveryImportScreen
import com.ada.messenger.ui.screens.PatternLoginScreen
import com.ada.messenger.ui.screens.PinLoginScreen
import com.ada.messenger.ui.screens.QrScannerScreen
import com.ada.messenger.ui.screens.SecureWebViewScreen
import com.ada.messenger.ui.screens.SettingsScreen

private const val TAG = "MainActivity"

class MainActivity : AppCompatActivity() {

    /** Shared ViewModel created at Activity scope — accessible from lifecycle methods. */
    private lateinit var sharedViewModel: AdaCoreViewModel
    private lateinit var appLock: com.ada.messenger.core.AppLockManager
    private val appLockPrefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        applyScreenshotPolicy()
    }

    /** Android 13+ runtime permission launcher for POST_NOTIFICATIONS. */
    private val notificationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Log.i(TAG, "POST_NOTIFICATIONS granted=$granted")
    }

    companion object {
        const val EXTRA_OPEN_CONV      = "ada_open_conv"
        const val EXTRA_OPEN_CONV_NAME = "ada_open_conv_name"
        const val EXTRA_OPEN_CALL      = "ada_open_call"
        const val EXTRA_CALL_ID        = "ada_call_id"
        const val EXTRA_CALL_PEER      = "ada_call_peer"
        const val EXTRA_CALL_VIDEO     = "ada_call_video"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // V-5: Splash Screen API — must be called before super.onCreate
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        Log.i(TAG, "onCreate")

        // M1 fix: prevent screenshots and task-switcher previews from capturing
        // message content and QR codes, unless user allowed it.
        appLock = com.ada.messenger.core.AppLockManager(applicationContext)
        
        // Auto-Wipe check (Roadmap 2.0 Self-Destruct feature)
        val now = System.currentTimeMillis()
        val lastActive = appLock.lastActiveMs
        val wipeDays = appLock.autoWipeDays
        if (wipeDays > 0) {
            val maxInactivityMs = wipeDays * 86400000L
            if (now - lastActive > maxInactivityMs) {
                Log.w(TAG, "Self-Destruct Triggered: Inactive for more than $wipeDays days.")
                appLock.resetFailedPinAttempts()
                // executeKillCode usually exists in ViewModel, we can just delete files here or call a helper
                appLock.disableCleanPin()
                appLock.disableKillPin()
                appLock.disablePin()
                appLock.clearLegacyQuickUnlockState()
                val dataDir = applicationContext.filesDir
                dataDir.deleteRecursively()
                val prefsDir = java.io.File(applicationContext.applicationInfo.dataDir, "shared_prefs")
                prefsDir.deleteRecursively()
                finishAffinity()
                stopService(Intent(this, com.ada.messenger.service.AdaForegroundService::class.java))
                System.exit(0)
                return
            }
        }
        
        applyScreenshotPolicy()
        appLock.registerPreferenceListener(appLockPrefsListener)

        // Setup structured Rust logging
        com.ada.messenger.core.AdaCore.initTracing(filesDir.absolutePath, isMobile = true)

        sharedViewModel = ViewModelProvider(
            this,
            AdaCoreViewModel.Factory(applicationContext),
        )[AdaCoreViewModel::class.java]

        // Handle deep-link from notification (app launched from notification)
        handleDeepLinkIntent(intent)

        // Request POST_NOTIFICATIONS on Android 13+ (needed to show any notification)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Schedule background event polling for notifications
        ADANotificationService.schedule(applicationContext)
        
        // Background cache cleanup
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            com.ada.messenger.core.AttachmentCacheManager.cleanupOldAndOversized(applicationContext)
        }
        
        setContent {
            val themePrefs = remember {
                applicationContext.getSharedPreferences("ada_theme", android.content.Context.MODE_PRIVATE)
            }
            val themeMode = remember {
                val saved = themePrefs.getString("theme_mode", "SYSTEM") ?: "SYSTEM"
                mutableStateOf(
                    runCatching { ThemeMode.valueOf(saved) }.getOrDefault(ThemeMode.SYSTEM)
                )
            }
            val dynamicColor = remember {
                mutableStateOf(themePrefs.getBoolean("dynamic_color", false))
            }
            // Per-app language (empty string = system default)
            val localeTag = remember {
                mutableStateOf(themePrefs.getString("locale_tag", "") ?: "")
            }
            // Apply saved locale on first composition
            LaunchedEffect(Unit) {
                val saved = localeTag.value
                if (saved.isNotEmpty()) {
                    AppCompatDelegate.setApplicationLocales(
                        LocaleListCompat.forLanguageTags(saved)
                    )
                }
            }
            ADAMessengerTheme(
                themeMode = themeMode.value,
                dynamicColor = dynamicColor.value,
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Transparent,
                ) {
                    AdaHexBackground {
                        AppNavigation(
                            viewModel = sharedViewModel,
                            onThemeModeChange = { mode ->
                                themeMode.value = mode
                                themePrefs.edit().putString("theme_mode", mode.name).apply()
                            },
                            onDynamicColorChange = { enabled ->
                                dynamicColor.value = enabled
                                themePrefs.edit().putBoolean("dynamic_color", enabled).apply()
                            },
                            currentThemeMode = themeMode.value,
                            currentDynamicColor = dynamicColor.value,
                            currentLocaleTag = localeTag.value,
                            onLocaleChange = { tag ->
                                localeTag.value = tag
                                themePrefs.edit().putString("locale_tag", tag).apply()
                                val locales = if (tag.isEmpty()) {
                                    LocaleListCompat.getEmptyLocaleList()
                                } else {
                                    LocaleListCompat.forLanguageTags(tag)
                                }
                                AppCompatDelegate.setApplicationLocales(locales)
                            },
                            onScreenshotPolicyChange = { applyScreenshotPolicy() },
                        )
                    }
                }
            }
        }
    }

    /** Called when the activity is already running and a new intent arrives (e.g. tapping a notification). */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLinkIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume")
        applyScreenshotPolicy()
        appLock.lastActiveMs = System.currentTimeMillis()
        
        if (::sharedViewModel.isInitialized && sharedViewModel.core != null) {
            sharedViewModel.core?.setAppBackgroundState(false)
        }
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause")
        if (::sharedViewModel.isInitialized && sharedViewModel.core != null) {
            sharedViewModel.core?.setAppBackgroundState(true)
        }
    }

    private fun applyScreenshotPolicy() {
        if (!::appLock.isInitialized) return
        if (appLock.allowScreenshots) {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    override fun onDestroy() {
        if (::appLock.isInitialized) {
            appLock.unregisterPreferenceListener(appLockPrefsListener)
        }
        super.onDestroy()
        Log.i(TAG, "onDestroy")
    }

    private fun handleDeepLinkIntent(intent: Intent?) {
        val uri = intent?.data

        // ── ada://s/<token> — new opaque short-link scheme ────────────────
        if (uri?.scheme == "ada" && uri.host == "s") {
            val token = uri.pathSegments.firstOrNull() ?: ""
            // Reconstruct the full URL for the decode function.
            val shortUrl = "ada://s/$token"
            // Guard against oversized payloads before calling into Rust.
            if (token.length in 1..6_000) {
                val contactJson = runCatching { sharedViewModel.decodeShortLink(shortUrl) }.getOrNull()
                if (contactJson != null) {
                    Log.i(TAG, "ada://s/ short-link received, json len=${contactJson.length}")
                    sharedViewModel.queueContactDeepLink(contactJson)
                } else {
                    Log.w(TAG, "ada://s/ short-link: decode failed (malformed or tampered token)")
                }
            } else if (token.isNotBlank()) {
                Log.w(TAG, "ada://s/ token length ${token.length} out of range — ignoring")
            }
            intent.data = null
            return
        }

        // ── ada://bridge-manifest?... — signed bridge bootstrap link ─────
        if (uri?.scheme == "ada" && uri.host == "bridge-manifest") {
            sharedViewModel.queueBridgeManifestDeepLink(uri.toString())
            intent.data = null
            return
        }

        if (uri?.scheme == "ada" && uri.host == "call") {
            sharedViewModel.queueGroupCallDeepLink(uri.toString())
            intent.data = null
            return
        }

        // ── ada://add-contact?card=<base64url> legacy deep link ───────────
        if (uri?.scheme == "ada" && uri.host == "add-contact") {
            val encoded = uri.getQueryParameter("card") ?: ""
            // Guard against crafted oversized payloads (a real contact card is ~400 chars encoded)
            if (encoded.length in 1..8_000) {
                val contactJson = runCatching {
                    String(
                        android.util.Base64.decode(
                            encoded,
                            android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP,
                        ),
                        Charsets.UTF_8,
                    )
                }.getOrNull()
                if (contactJson != null) {
                    Log.i(TAG, "ada://add-contact deep link received, json len=${contactJson.length}")
                    sharedViewModel.queueContactDeepLink(contactJson)
                }
            } else if (encoded.isNotBlank()) {
                Log.w(TAG, "ada://add-contact: card param length ${encoded.length} out of range — ignoring")
            }
            intent.data = null
            return
        }

        // Conversation deep-link
        val convId = intent?.getStringExtra(EXTRA_OPEN_CONV)
        if (convId != null) {
            val convName = intent.getStringExtra(EXTRA_OPEN_CONV_NAME) ?: "Chat"
            Log.i(TAG, "deep-link from notification: conv=$convId name=$convName")
            sharedViewModel.openFromNotification(convId, convName)
            intent.removeExtra(EXTRA_OPEN_CONV)
            return
        }
        // Incoming call deep-link (from background notification)
        val openCall = intent?.getBooleanExtra(EXTRA_OPEN_CALL, false) ?: false
        if (openCall) {
            val callId  = intent?.getStringExtra(EXTRA_CALL_ID) ?: ""
            val peer    = intent?.getStringExtra(EXTRA_CALL_PEER) ?: ""
            val video   = intent?.getBooleanExtra(EXTRA_CALL_VIDEO, false) ?: false
            Log.i(TAG, "deep-link incoming call: id=$callId peer=${peer.take(12)} video=$video")
            sharedViewModel.openFromCallNotification(callId, peer, video)
            intent?.removeExtra(EXTRA_OPEN_CALL)
        }
    }
}

@Composable
fun AppNavigation(
    viewModel: AdaCoreViewModel,
    onThemeModeChange: (ThemeMode) -> Unit = {},
    onDynamicColorChange: (Boolean) -> Unit = {},
    currentThemeMode: ThemeMode = ThemeMode.SYSTEM,
    currentDynamicColor: Boolean = true,
    currentLocaleTag: String = "",
    onLocaleChange: (String) -> Unit = {},
    onScreenshotPolicyChange: () -> Unit = {},
) {
    val navController = rememberNavController()
    val context = androidx.compose.ui.platform.LocalContext.current

    // Battery optimization dialog — shown once after first login.
    var showBatteryDialog by remember {
        mutableStateOf(BatteryOptimizationHelper.shouldAsk(context))
    }
    if (showBatteryDialog) {
        // Delay until the user has initialized (is on main screen)
        val initialized by viewModel.initialized.collectAsState()
        if (initialized) {
            BatteryOptimizationDialog(onDismiss = { showBatteryDialog = false })
        }
    }

    // Choose start screen
    // L1: If pattern-only and the user unlocked recently, skip directly to main
    val PATTERN_GRACE_MS = 5 * 60 * 1000L // 5 minutes
    val startDestination = when {
        !viewModel.hasStoredIdentity() -> "pattern_register"
        viewModel.appLock.isPinEnabled -> "quick_unlock"
        else -> {
            val elapsed = System.currentTimeMillis() - viewModel.appLock.lastUnlockMs
            if (elapsed < PATTERN_GRACE_MS) "main" else "pattern_login"
        }
    }
    Log.i(TAG, "AppNavigation start: startDestination=$startDestination")

    // ── Global incoming call watcher ──────────────────────────────────────
    // Must live OUTSIDE composable("main") so it fires from ANY screen.
    val incomingCallGlobal by viewModel.incomingCall.collectAsState()
    val pendingNav by viewModel.pendingNavConv.collectAsState()
    val pendingCall by viewModel.pendingNavCall.collectAsState()
    val pendingOpenCallScreen by viewModel.pendingOpenCallScreen.collectAsState()
    val isInitialized by viewModel.initialized.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStackEntry?.destination?.route
    val appLockEnabled = viewModel.appLock.isPinEnabled
    var requireReauthOnForeground by rememberSaveable { mutableStateOf(false) }
    var lastStopTimeMs by rememberSaveable { mutableStateOf(0L) }
    // L1: grace period — don't demand PIN if the app was only backgrounded briefly
    val PIN_LOCK_GRACE_MS = 30_000L // 30 seconds

    DisposableEffect(lifecycleOwner, isInitialized, appLockEnabled) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    val activity = context as? Activity
                    if (activity?.isChangingConfigurations != true && isInitialized && appLockEnabled) {
                        lastStopTimeMs = System.currentTimeMillis()
                        requireReauthOnForeground = true
                    }
                }

                Lifecycle.Event.ON_START -> {
                    if (requireReauthOnForeground && isInitialized && appLockEnabled) {
                        requireReauthOnForeground = false
                        val elapsed = System.currentTimeMillis() - lastStopTimeMs
                        // Skip lock for incoming calls or brief background stays
                        val hasIncomingCall = viewModel.incomingCall.value != null ||
                            viewModel.pendingNavCall.value != null
                        if (elapsed > PIN_LOCK_GRACE_MS && !hasIncomingCall) {
                            if (currentRoute != "quick_reauth" && currentRoute != "pattern_reauth") {
                                navController.navigate("quick_reauth") { launchSingleTop = true }
                            }
                        }
                    }
                }

                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(incomingCallGlobal) {
        if (incomingCallGlobal != null) {
            // Only navigate if we are not already on the call screen
            if (currentRoute != "call") {
                navController.navigate("call") { launchSingleTop = true }
            }
        }
    }

    LaunchedEffect(pendingCall, pendingOpenCallScreen) {
        if (pendingCall != null) {
            viewModel.clearPendingCallNav()
            if (currentRoute != "call") {
                navController.navigate("call") { launchSingleTop = true }
            }
        } else if (pendingOpenCallScreen) {
            viewModel.clearPendingOpenCallScreen()
            if (currentRoute != "call") {
                navController.navigate("call") { launchSingleTop = true }
            }
        }
    }

    LaunchedEffect(pendingNav, currentRoute) {
        val (cid, name) = pendingNav ?: return@LaunchedEffect
        // Do not bypass any authentication screen — wait until the user completes login
        if (currentRoute in setOf("quick_unlock", "quick_reauth", "pattern_login", "pattern_reauth", "pattern_register")) {
            return@LaunchedEffect
        }
        viewModel.clearPendingNav()
        val encodedCid = java.net.URLEncoder.encode(cid, "UTF-8")
        val encoded = java.net.URLEncoder.encode(name, "UTF-8")
        navController.navigate("chat/$encodedCid?name=$encoded")
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
        enterTransition = {
            slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(280)) +
                fadeIn(animationSpec = tween(280))
        },
        exitTransition = {
            slideOutHorizontally(targetOffsetX = { -it / 3 }, animationSpec = tween(280)) +
                fadeOut(animationSpec = tween(200))
        },
        popEnterTransition = {
            slideInHorizontally(initialOffsetX = { -it / 3 }, animationSpec = tween(280)) +
                fadeIn(animationSpec = tween(280))
        },
        popExitTransition = {
            slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(280)) +
                fadeOut(animationSpec = tween(200))
        },
    ) {

        // ── Pattern registration (first launch) ─────────────────────────────
        composable("pattern_register") {
            PatternRegistrationScreen(
                viewModel = viewModel,
                onFinished = {
                    navController.navigate("main") {
                        popUpTo("pattern_register") { inclusive = true }
                    }
                },
                onImportRecovery = {
                    navController.navigate("recovery_import")
                },
            )
        }

        // ── PIN quick unlock ─────────────────────────────────────────────────
        composable("quick_unlock") {
            PinLoginScreen(
                viewModel = viewModel,
                onSuccess = {
                    viewModel.appLock.lastUnlockMs = System.currentTimeMillis()
                    navController.navigate("main") {
                        popUpTo("quick_unlock") { inclusive = true }
                    }
                },
                onUsePattern = {
                    navController.navigate("pattern_login") {
                        popUpTo("quick_unlock") { inclusive = true }
                    }
                },
            )
        }

        composable("quick_reauth") {
            PinLoginScreen(
                viewModel = viewModel,
                reauthMode = true,
                onSuccess = {
                    viewModel.appLock.lastUnlockMs = System.currentTimeMillis()
                    if (!navController.popBackStack()) {
                        navController.navigate("main") { launchSingleTop = true }
                    }
                },
                onUsePattern = {
                    navController.navigate("pattern_reauth") {
                        popUpTo("quick_reauth") { inclusive = true }
                    }
                },
            )
        }

        // ── Pattern login (fallback) ──────────────────────────────────────────
        composable("pattern_login") {
            PatternLoginScreen(
                viewModel = viewModel,
                onSuccess = {
                    viewModel.appLock.lastUnlockMs = System.currentTimeMillis()
                    navController.navigate("main") {
                        popUpTo("pattern_login") { inclusive = true }
                    }
                },
                onForgot = {
                    navController.navigate("pattern_register") {
                        popUpTo("pattern_login") { inclusive = true }
                    }
                },
            )
        }

        composable("pattern_reauth") {
            PatternLoginScreen(
                viewModel = viewModel,
                reauthMode = true,
                onSuccess = {
                    viewModel.appLock.lastUnlockMs = System.currentTimeMillis()
                    if (!navController.popBackStack()) {
                        navController.navigate("main") { launchSingleTop = true }
                    }
                },
                onForgot = null,
            )
        }

        composable("recovery_import") {
            RecoveryImportScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onImported = {
                    navController.navigate("pattern_login") {
                        popUpTo("recovery_import") { inclusive = true }
                    }
                },
            )
        }

        // ── Legacy onboarding (nickname-only, kept for compatibility) ─────────
        composable("onboarding") {
            OnboardingScreen(
                viewModel = viewModel,
                onIdentityCreated = {
                    navController.navigate("main") {
                        popUpTo("onboarding") { inclusive = true }
                    }
                },
            )
        }
        composable("main") {
            // Auto-navigate to call screen when an incoming call arrives in foreground
            // NOTE: Global watcher above handles navigation from ANY screen.
            // This block is intentionally removed to avoid duplicate navigations.
            MainScreen(
                viewModel = viewModel,
                onChatSelected = { convId, displayName ->
                    val encodedCid = java.net.URLEncoder.encode(convId, "UTF-8")
                    val encoded = java.net.URLEncoder.encode(displayName, "UTF-8")
                    navController.navigate("chat/$encodedCid?name=$encoded")
                },
                onOpenBridge    = { navController.navigate("bridge") },
                onOpenCall      = { navController.navigate("call") },
                onOpenQrScanner = { navController.navigate("qr_scanner") },
                onOpenSettings  = { navController.navigate("settings") },
            )
        }
        composable(
            route = "chat/{convId}?name={name}",
            arguments = listOf(
                navArgument("convId") { type = NavType.StringType },
                navArgument("name")   { type = NavType.StringType; defaultValue = "" },
            ),
        ) { back ->
            // Navigation Compose 2.5+ auto-decodes path/query args — no manual URLDecoder needed
            val convId = back.arguments?.getString("convId") ?: return@composable
            val name   = back.arguments?.getString("name")
                ?.takeIf { it.isNotEmpty() }
                ?: convId.take(12)

            ChatScreen(
                convId = convId,
                displayName = name,
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onStartCall = { navController.navigate("call") },
                onOpenUrl = { url ->
                    val encoded = java.net.URLEncoder.encode(url, "UTF-8")
                    navController.navigate("webview?url=$encoded")
                },
                onOpenAdaLink = { url ->
                    // Re-use the same parsing logic as handleDeepLinkIntent so that
                    // ada:// contact cards shared in chat open the add-contact flow.
                    val uri = android.net.Uri.parse(url)
                    when {
                        uri.scheme == "ada" && uri.host == "bridge-manifest" -> {
                            if (viewModel.importBridgeManifestFromText(
                                    url,
                                    sourceHint = context.getString(R.string.bridge_manifest_import_source_deeplink),
                                )) {
                                navController.navigate("bridge") { launchSingleTop = true }
                            }
                        }
                        uri.scheme == "ada" && uri.host == "call" -> {
                            viewModel.queueGroupCallDeepLink(url)
                        }
                        // ── New opaque short-link ─────────────────────────────
                        uri.scheme == "ada" && uri.host == "s" -> {
                            val token = uri.pathSegments.firstOrNull() ?: ""
                            val shortUrl = "ada://s/$token"
                            if (token.length in 1..6_000) {
                                val contactJson = runCatching { viewModel.decodeShortLink(shortUrl) }.getOrNull()
                                if (contactJson != null) viewModel.queueContactDeepLink(contactJson)
                            }
                        }
                        // ── Legacy ada://add-contact?card= ────────────────────
                        uri.scheme == "ada" && uri.host == "add-contact" -> {
                            val encoded = uri.getQueryParameter("card") ?: ""
                            if (encoded.length in 1..8_000) {
                                val contactJson = runCatching {
                                    String(
                                        android.util.Base64.decode(
                                            encoded,
                                            android.util.Base64.URL_SAFE or
                                                android.util.Base64.NO_PADDING or
                                                android.util.Base64.NO_WRAP,
                                        ),
                                        Charsets.UTF_8,
                                    )
                                }.getOrNull()
                                if (contactJson != null) {
                                    viewModel.queueContactDeepLink(contactJson)
                                }
                            }
                        }
                    }
                },
            )
        }
        composable("call") {
            CallScreen(
                viewModel = viewModel,
                onFinish = { navController.popBackStack() },
            )
        }
        composable("bridge") {
            BridgeScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onOpenManifestQrScanner = { navController.navigate("qr_scanner") },
            )
        }
        // ── Secure isolated WebView ─────────────────────────────────────────────
        composable(
            route = "webview?url={url}",
            arguments = listOf(
                navArgument("url") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { back ->
            // Navigation Compose 2.5+ auto-decodes query args — no manual URLDecoder needed
            val decodedUrl = back.arguments?.getString("url") ?: return@composable
            // Only open URLs with HTTPS scheme — silently drop anything else.
            if (!decodedUrl.startsWith("https://")) {
                navController.popBackStack()
                return@composable
            }
            SecureWebViewScreen(
                url = decodedUrl,
                onBack = { navController.popBackStack() },
            )
        }
        // ── Settings ────────────────────────────────────────────────────────────
        composable("settings") {
            SettingsScreen(
                viewModel            = viewModel,
                patternCells         = viewModel.lastPatternCells,
                onBack               = { navController.popBackStack() },
                onLinkDesktop        = { navController.navigate("desktop_link") },
                currentThemeMode     = currentThemeMode,
                currentDynamicColor  = currentDynamicColor,
                onThemeModeChange    = onThemeModeChange,
                onDynamicColorChange = onDynamicColorChange,
                currentLocaleTag     = currentLocaleTag,
                onLocaleChange       = onLocaleChange,
                onScreenshotPolicyChange = onScreenshotPolicyChange,
            )
        }
        // ── Desktop link (Bluetooth phone-to-PC account sync) ─────────────────────
        composable("desktop_link") {
            DesktopLinkScreen(
                viewModel = viewModel,
                onBack    = { navController.popBackStack() },
            )
        }
        // ── QR contact scanner ──────────────────────────────────────────────────
        composable("qr_scanner") {
            QrScannerScreen(
                onResult = { raw ->
                    Log.i(TAG, "QR scanned, raw length=${raw.length}")
                    if (viewModel.importBridgeManifestFromText(
                            raw,
                            sourceHint = context.getString(R.string.bridge_manifest_import_source_qr),
                        )) {
                        navController.popBackStack()
                        navController.navigate("bridge") { launchSingleTop = true }
                        return@QrScannerScreen
                    }
                    if (viewModel.importContactFromText(raw)) {
                        // Re-use the same safe import/navigation flow as deep links:
                        // save the contact on IO, then let pendingNavConv navigate to chat
                        // after we return to the main destination.
                        Log.d(TAG, "QR: contact payload accepted, returning to main")
                        if (!navController.popBackStack("main", false)) {
                            navController.navigate("main") { launchSingleTop = true }
                        }
                    } else {
                        Log.w(TAG, "QR: unsupported payload — popping back")
                        navController.popBackStack()
                    }
                },
                onClose = {
                    Log.d(TAG, "QR scanner closed by user")
                    navController.popBackStack()
                },
            )
        }
    }
}




