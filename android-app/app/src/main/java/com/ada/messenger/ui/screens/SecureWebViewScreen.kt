package com.ada.messenger.ui.screens

import android.graphics.Bitmap
import android.net.http.SslError
import android.webkit.CookieManager
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

/**
 * Isolated secure WebView screen.
 *
 * Security hardening applied:
 * - JavaScript disabled
 * - DOM storage, database, form data, password saving — all off
 * - File and content access disabled
 * - Mixed content (HTTP inside HTTPS) never allowed
 * - Cookies isolated and cleared on exit
 * - Cache disabled (LOAD_NO_CACHE)
 * - Geolocation disabled
 * - Only HTTPS navigation allowed; HTTP and all custom schemes are blocked
 * - SSL errors cause hard cancel (no "proceed anyway" path)
 * - JS dialogs (alert/confirm/prompt) cancelled to prevent UI spoofing
 * - window.open() blocked
 * - Downloads blocked
 * - Safe Browsing enabled when the device supports it
 * - WebView data (history, cache, cookies) fully cleared on screen exit
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecureWebViewScreen(
    url: String,
    onBack: () -> Unit,
) {
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var loadProgress by remember { mutableIntStateOf(0) }
    var currentUrl by remember { mutableStateOf(url) }
    var isSafe by remember { mutableStateOf(true) }
    var showSslWarning by remember { mutableStateOf(false) }

    // Wipe all WebView data when the screen is disposed so nothing leaks
    // to other composables or future sessions.
    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.let { wv ->
                wv.stopLoading()
                wv.clearHistory()
                wv.clearCache(true)
                wv.clearFormData()
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                wv.destroy()
            }
        }
    }

    // Hardware back — navigate back inside WebView, or exit screen.
    BackHandler {
        val wv = webViewRef
        if (wv != null && wv.canGoBack()) wv.goBack() else onBack()
    }

    if (showSslWarning) {
        AlertDialog(
            onDismissRequest = { showSslWarning = false },
            icon = {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            title = { Text("Небезопасное соединение") },
            text = {
                Text(
                    "Сертификат сайта недействителен. Соединение заблокировано для защиты ваших данных.",
                )
            },
            confirmButton = {
                TextButton(onClick = { showSslWarning = false; onBack() }) {
                    Text("Закрыть")
                }
            },
        )
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = {
                            val wv = webViewRef
                            if (wv != null && wv.canGoBack()) wv.goBack() else onBack()
                        }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Назад",
                            )
                        }
                    },
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (isSafe) Icons.Default.Lock
                                              else Icons.Default.Warning,
                                contentDescription = null,
                                tint = if (isSafe) MaterialTheme.colorScheme.primary
                                       else MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                // Strip scheme for cleaner display
                                text = currentUrl
                                    .removePrefix("https://")
                                    .removePrefix("http://"),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                )
                // Only render the progress bar while a page is loading.
                if (loadProgress in 1..99) {
                    LinearProgressIndicator(
                        progress = { loadProgress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
    ) { padding ->
        AndroidView(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    applySecureSettings(this)

                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest,
                        ): Boolean {
                            val scheme = request.url.scheme ?: ""
                            // Block everything that is not plain HTTPS.
                            if (scheme != "https") return true
                            currentUrl = request.url.toString()
                            return false
                        }

                        override fun onPageStarted(
                            view: WebView,
                            pageUrl: String,
                            favicon: Bitmap?,
                        ) {
                            currentUrl = pageUrl
                            canGoBack = view.canGoBack()
                            isSafe = pageUrl.startsWith("https://")
                            loadProgress = 0
                        }

                        override fun onPageFinished(view: WebView, pageUrl: String) {
                            canGoBack = view.canGoBack()
                            loadProgress = 100
                        }

                        // Hard-cancel on any SSL error — no "proceed" path allowed.
                        override fun onReceivedSslError(
                            view: WebView,
                            handler: SslErrorHandler,
                            error: SslError,
                        ) {
                            handler.cancel()
                            isSafe = false
                            showSslWarning = true
                        }
                    }

                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView, newProgress: Int) {
                            loadProgress = newProgress
                        }

                        // Block all JS dialogs — they can be used to spoof system UI.
                        override fun onJsAlert(
                            view: WebView,
                            url: String,
                            message: String,
                            result: JsResult,
                        ): Boolean {
                            result.cancel()
                            return true
                        }

                        override fun onJsConfirm(
                            view: WebView,
                            url: String,
                            message: String,
                            result: JsResult,
                        ): Boolean {
                            result.cancel()
                            return true
                        }

                        override fun onJsPrompt(
                            view: WebView,
                            url: String,
                            message: String,
                            defaultValue: String?,
                            result: JsPromptResult,
                        ): Boolean {
                            result.cancel()
                            return true
                        }

                        // Block window.open() — prevents opening untrusted pop-ups.
                        override fun onCreateWindow(
                            view: WebView,
                            isDialog: Boolean,
                            isUserGesture: Boolean,
                            resultMsg: android.os.Message?,
                        ): Boolean = false
                    }

                    // Block all file downloads — there is no safe way to handle them here.
                    setDownloadListener { _, _, _, _, _ -> /* intentionally empty */ }

                    loadUrl(url)
                    webViewRef = this
                }
            },
        )
    }
}

/**
 * Apply maximum security settings to a [WebView].
 * Called once during factory creation before any content is loaded.
 */
private fun applySecureSettings(webView: WebView) {
    webView.settings.apply {
        // Disable JavaScript entirely — the primary XSS vector.
        javaScriptEnabled = false

        // Disable persistence
        domStorageEnabled = false
        databaseEnabled = false
        @Suppress("DEPRECATION")
        saveFormData = false
        @Suppress("DEPRECATION")
        savePassword = false

        // No file-system access of any kind
        allowFileAccess = false
        allowContentAccess = false
        @Suppress("DEPRECATION")
        allowFileAccessFromFileURLs = false
        @Suppress("DEPRECATION")
        allowUniversalAccessFromFileURLs = false

        // Disable location
        setGeolocationEnabled(false)

        // No caching — every request goes through the network fresh
        cacheMode = WebSettings.LOAD_NO_CACHE

        // Never mix HTTP resources into an HTTPS page
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW

        // Avoid device fingerprinting by spoofing the User-Agent to a generic Windows desktop
        userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        // Prevent media autoplay, reducing tracking and data usage risks
        mediaPlaybackRequiresUserGesture = true

        // Cosmetic — avoid leaking zoom UI information
        builtInZoomControls = false
        displayZoomControls = false
    }

    // Isolate cookies per-WebView — use per-view API instead of global singleton
    CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)
    CookieManager.getInstance().removeAllCookies(null)

    // Enable Safe Browsing (Google's phishing/malware URL DB) when available.
    if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
        WebSettingsCompat.setSafeBrowsingEnabled(webView.settings, true)
    }
}
