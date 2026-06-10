package com.ada.messenger.core

import kotlinx.coroutines.yield
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import com.ada.messenger.R

private const val TAG = "AdaCoreViewModel"

// ── Data models ───────────────────────────────────────────────────────────────

/** M-5: Typed call state machine replacing string-based states. */
enum class CallState {
    Initiating,   // Outgoing call being set up (before SDP offer sent)
    Ringing,      // Offer sent, waiting for answer
    Connecting,   // Answer received, ICE negotiation in progress
    Active,       // Media flowing
    Reconnecting, // ICE restart in progress
    Ended,        // Call terminated
    Failed;       // Unrecoverable error

    companion object {
        fun fromString(s: String): CallState = when (s) {
            "Initiating" -> Initiating
            "Ringing" -> Ringing
            "Connecting" -> Connecting
            "Active" -> Active
            "Reconnecting" -> Reconnecting
            "Ended" -> Ended
            "Failed" -> Failed
            else -> {
                android.util.Log.w("CallState", "Unknown call state '$s', treating as Failed")
                Failed
            }
        }
    }
}

data class IncomingCallInfo(
    val callIdHex: String,
    val peerIdB64: String,
    val hasVideo: Boolean,
    val offerSdp: String = "",
    val displayName: String = "",
    val groupIdHex: String? = null,
    val callSessionId: String? = null,
    val participants: List<String> = emptyList(),
)
data class ActiveCallInfo(
    val callIdHex: String,
    val peerIdB64: String,
    val hasVideo: Boolean,
    val isOutgoing: Boolean,
    val state: CallState,
    val displayName: String = "",
    val groupIdHex: String? = null,
    val callSessionId: String? = null,
    val participants: List<String> = emptyList(),
)
data class TransferItem(val id: String, val peer: String, val fileName: String, val fileSize: Long, val mimeType: String, val progress: Float, val isOutbound: Boolean)
data class TransportOutcomeSummary(
    val route: String,
    val messageIdHex: String,
    val queueDepth: Int?,
    val latencyMs: Long?,
) {
    val isLocalMesh: Boolean get() = route == "local_mesh"
}
data class BridgeCapabilitiesSummary(
    val bridgeLiveDelivery: Boolean,
    val mailboxDelivery: Boolean,
    val realtimeCalls: Boolean,
    val largeAttachments: Boolean,
    val maxAttachmentBytes: Long,
)
data class BridgeManifestSummary(
    val version: Long,
    val issuedAtMs: Long,
    val ttlSecs: Long,
    val bridgeCount: Int,
    val supportsRealtimeCalls: Boolean,
    val maxAttachmentBytes: Long?,
    val source: String?,
)
data class BridgeStatus(
    val mode: String,
    val connectionProfile: String,
    val bridges: List<JSONObject>,
    val hasWorking: Boolean,
    val relayOnly: Boolean,
    val irohReady: Boolean,
    val transportStack: String,
    val routeGranularity: String,
    val relayOnlyScope: String,
    val bridgeListenerConnected: Boolean,
    val bridgeListenerRoute: String?,
    val bridgeMailboxDepth: Int,
    val capabilities: BridgeCapabilitiesSummary,
    val manifest: BridgeManifestSummary?,
    val lastOutcome: TransportOutcomeSummary?,
)
data class BridgeImportNotice(
    val message: String,
    val isError: Boolean,
)

private data class PendingBridgeManifestImport(
    val manifestJson: String? = null,
    val manifestUrl: String? = null,
    val trustedPublicKeyHex: String?,
    val source: String,
    val noticeLabel: String,
)

/**
 * Shared ViewModel for the ADA messenger Android app.
 *
 * Responsibilities are split across focused managers:
 * - [IdentityManager] — authentication & identity persistence
 * - [CallManager] — WebRTC call lifecycle
 * - [TransferManager] — file transfer state
 * - [NotificationHelper] — notification construction
 *
 * This class retains: conversation/message state, event routing, groups, bridge,
 * avatar preference, voice playback, and app-lock coordination.
 */
class AdaCoreViewModel(private val appContext: Context) : ViewModel() {

    // ── Delegated managers ────────────────────────────────────────────────

    val identity = IdentityManager(appContext)
    val appLock  = AppLockManager(appContext)
    private val contactAliasStore = ContactAliasStore(appContext)

    /** Publicly exposed pattern loading/error from [IdentityManager]. */
    val patternLoading: StateFlow<Boolean> get() = identity.patternLoading
    val patternError: StateFlow<String?> get() = identity.patternError

    @Volatile var core: AdaCore? = null
        private set
    private var webRtc: WebRTCBridge? = null
    @Volatile private var reusedLiveCore = false
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    val callManager: CallManager by lazy {
        CallManager(
            scope = viewModelScope,
            coreProvider = { core },
            webRtcProvider = { webRtc },
            conversationsProvider = { _conversations.value },
            groupInfoProvider = { _currentGroupInfo.value },
            myPeerIdProvider = { _myPeerId.value },
            onCallError = { _sendError.value = it },
            callUnavailableMessageProvider = { appContext.getString(R.string.call_unavailable_network) },
        )
    }

    val transferManager: TransferManager by lazy {
        TransferManager(appContext, viewModelScope, coreProvider = { core })
    }

    val localMeshManager: LocalMeshManager by lazy {
        LocalMeshManager.getInstance(appContext, core)
    }

    // ── Pattern cells (in-memory only, zeroed on lock/clear) ─────────────

    @Volatile var lastPatternCells: ByteArray? = null
    private val cellsLock = Any()

    // ── Clean-mode flag ──────────────────────────────────────────────────

    private val _isCleanMode = MutableStateFlow(false)
    val isCleanMode: StateFlow<Boolean> = _isCleanMode.asStateFlow()

    // ── Core state ───────────────────────────────────────────────────────

    private val _initialized = MutableStateFlow(false)
    val initialized: StateFlow<Boolean> = _initialized.asStateFlow()

    private val _myPeerId = MutableStateFlow<String?>(null)
    val myPeerId: StateFlow<String?> = _myPeerId.asStateFlow()

    private val _myDisplayName = MutableStateFlow<String?>(null)
    val myDisplayName: StateFlow<String?> = _myDisplayName.asStateFlow()

    private val _conversations = MutableStateFlow<List<ConversationItem>>(emptyList())
    val conversations: StateFlow<List<ConversationItem>> = _conversations.asStateFlow()

    private val _searchResults = MutableStateFlow<List<ConversationItem>>(emptyList())
    val searchResults: StateFlow<List<ConversationItem>> = _searchResults.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    // ── Online presence ──────────────────────────────────────────────────
    private val _onlinePeers = MutableStateFlow<Set<String>>(emptySet())
    val onlinePeers: StateFlow<Set<String>> = _onlinePeers.asStateFlow()

    // V-11: Peer typing indicator (populated when protocol sends typing events)
    private val _peerTyping = MutableStateFlow(false)
    val peerTyping: StateFlow<Boolean> = _peerTyping.asStateFlow()

    // ── Delegate accessors (call state) ──────────────────────────────────

    val incomingCall: StateFlow<IncomingCallInfo?> get() = callManager.incomingCall
    val activeCall: StateFlow<ActiveCallInfo?> get() = callManager.activeCall
    val isScreenSharing: StateFlow<Boolean> get() = callManager.isScreenSharing

    // ── Call history ─────────────────────────────────────────────────────

    private val _callHistory = MutableStateFlow<List<CallLogEntry>>(emptyList())
    val callHistory: StateFlow<List<CallLogEntry>> = _callHistory.asStateFlow()

    fun refreshCallHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            _callHistory.value = loadCallHistoryFromCore(_conversations.value)
        }
    }

    fun getContactAlias(peerIdB64: String): String = contactAliasStore.getAlias(peerIdB64).orEmpty()

    fun setContactAlias(peerIdB64: String, alias: String) {
        viewModelScope.launch(Dispatchers.IO) {
            contactAliasStore.setAlias(peerIdB64, alias)

            val conversations = loadConversationsFromCore()
            _conversations.value = conversations

            _searchResults.value = if (lastSearchQuery.isBlank()) {
                emptyList()
            } else {
                searchConversationsFromCore(lastSearchQuery)
            }

            activeConvId?.let { convId ->
                _messages.value = loadMessagesFromCore(convId)
            }

            _currentGroupInfo.value?.id?.let { groupIdHex ->
                _currentGroupInfo.value = loadGroupInfoFromCore(groupIdHex)
            }

            _callHistory.value = loadCallHistoryFromCore(conversations)
        }
    }

    // ── Delegate accessors (transfer state) ──────────────────────────────

    val transfers: StateFlow<List<TransferItem>> get() = transferManager.transfers
    val savedFiles: StateFlow<Map<String, String>> get() = transferManager.savedFiles

    // ── Connection health indicator ──────────────────────────────────────

    /**
     * Network health level driving the connection status bar.
        * GREEN  — local live transport is ready; the device itself is online.
        * YELLOW — connected only via bridge fallback with realtime delivery.
     * ORANGE — only bridge/mailbox, no live delivery, heavy features (calls) unavailable.
     * RED    — no connectivity at all: iroh failed, no bridge, no mailbox.
     */
    enum class ConnectionLevel { GREEN, YELLOW, ORANGE, RED }

    /** Whether iroh QUIC endpoint started successfully. Set by NetworkConnected event. */
    private val _irohUp = MutableStateFlow(false)
    /** Tracks whether bridge listener has a live connection. */
    private val _bridgeLive = MutableStateFlow(false)
    /** Tracks whether mailbox delivery is available. */
    private val _mailboxAvailable = MutableStateFlow(false)
    /** Tracks whether bridge supports realtime calls. */
    private val _bridgeRealtimeCalls = MutableStateFlow(false)
    /** Tracks whether the latest delivery proved a local BLE/Wi-Fi Direct mesh path. */
    private val _localMeshAvailable = MutableStateFlow(false)
    private val _lastTransportOutcome = MutableStateFlow<TransportOutcomeSummary?>(null)
    val lastTransportOutcome: StateFlow<TransportOutcomeSummary?> = _lastTransportOutcome.asStateFlow()

    /**
     * Computed connection level. Recalculated whenever any of its inputs change.
     * This drives the connection status bar across all screens.
     */
    @Suppress("OPT_IN_USAGE")
    val connectionLevel: StateFlow<ConnectionLevel> = kotlinx.coroutines.flow.combine(
        _initialized, _irohUp, _onlinePeers, _bridgeLive, _mailboxAvailable, _bridgeRealtimeCalls, _localMeshAvailable,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val initialized = values[0] as Boolean
        val irohUp = values[1] as Boolean
        val bridgeLive = values[3] as Boolean
        val mailbox = values[4] as Boolean
        val rtcCalls = values[5] as Boolean
        val localMesh = values[6] as Boolean

        when {
            !initialized -> ConnectionLevel.RED
            // GREEN means the local node is online and has a live direct transport.
            // Peer availability is shown separately in chat headers and must not delay
            // the global connection bar until the first PeerOnline event arrives.
            irohUp -> ConnectionLevel.GREEN
            // YELLOW: no direct transport, but the bridge can still carry live traffic.
            bridgeLive && rtcCalls -> ConnectionLevel.YELLOW
            // ORANGE: limited — calls unavailable
            bridgeLive -> ConnectionLevel.ORANGE
            mailbox -> ConnectionLevel.ORANGE
            localMesh -> ConnectionLevel.ORANGE
            // RED: no connectivity
            else -> ConnectionLevel.RED
        }
    }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, ConnectionLevel.RED)

    // ── Send error ───────────────────────────────────────────────────────

    private val _sendError = MutableStateFlow<String?>(null)
    val sendError: StateFlow<String?> = _sendError.asStateFlow()
    fun clearSendError() { _sendError.value = null }

    // ── Bridge / censorship ──────────────────────────────────────────────

    private val _bridgeStatus = MutableStateFlow<BridgeStatus?>(null)
    val bridgeStatus: StateFlow<BridgeStatus?> = _bridgeStatus.asStateFlow()

    private val _connectionProfile = MutableStateFlow(loadSavedConnectionProfile())
    val connectionProfile: StateFlow<String> = _connectionProfile.asStateFlow()

    private val _bridgeImportNotice = MutableStateFlow<BridgeImportNotice?>(null)
    val bridgeImportNotice: StateFlow<BridgeImportNotice?> = _bridgeImportNotice.asStateFlow()
    fun clearBridgeImportNotice() { _bridgeImportNotice.value = null }

    private val _censorshipLevel = MutableStateFlow("None")
    val censorshipLevel: StateFlow<String> = _censorshipLevel.asStateFlow()

    // ── Groups ───────────────────────────────────────────────────────────

    private val _groups = MutableStateFlow<List<GroupInfo>>(emptyList())
    val groups: StateFlow<List<GroupInfo>> = _groups.asStateFlow()

    private val _currentGroupInfo = MutableStateFlow<GroupInfo?>(null)
    val currentGroupInfo: StateFlow<GroupInfo?> = _currentGroupInfo.asStateFlow()

    // ── Voice playback ───────────────────────────────────────────────────

    private val _playingVoiceId = MutableStateFlow<String?>(null)
    val playingVoiceId: StateFlow<String?> = _playingVoiceId.asStateFlow()
    /** Current playback progress 0.0..1.0 */
    private val _voiceProgress = MutableStateFlow(0f)
    val voiceProgress: StateFlow<Float> = _voiceProgress.asStateFlow()
    /** Total duration of the currently playing voice in milliseconds */
    private val _voiceDurationMs = MutableStateFlow(0)
    val voiceDurationMs: StateFlow<Int> = _voiceDurationMs.asStateFlow()
    private var mediaPlayer: MediaPlayer? = null
    private var progressJob: kotlinx.coroutines.Job? = null
    private var searchJob: Job? = null
    @Volatile private var lastSearchQuery: String = ""

    // ── Navigation ───────────────────────────────────────────────────────

    @Volatile private var activeConvId: String? = null

    private val _pendingNavConv = MutableStateFlow<Pair<String, String>?>(null)
    val pendingNavConv: StateFlow<Pair<String, String>?> = _pendingNavConv.asStateFlow()

    private val _pendingNavCall = MutableStateFlow<IncomingCallInfo?>(null)
    val pendingNavCall: StateFlow<IncomingCallInfo?> = _pendingNavCall.asStateFlow()

    private val _pendingOpenCallScreen = MutableStateFlow(false)
    val pendingOpenCallScreen: StateFlow<Boolean> = _pendingOpenCallScreen.asStateFlow()

    private data class PendingGroupCallLink(
        val groupIdHex: String,
        val sessionIdHex: String,
        val hasVideo: Boolean,
    )

    fun openFromNotification(convId: String, convName: String) { _pendingNavConv.value = Pair(convId, convName) }
    fun clearPendingNav() { _pendingNavConv.value = null }
    fun clearPendingOpenCallScreen() { _pendingOpenCallScreen.value = false }

    fun openFromCallNotification(callIdHex: String, peerIdB64: String, hasVideo: Boolean) {
        // Do not recreate incoming call if a call is already active or in progress
        if (callManager.activeCall.value == null) {
            if (callManager.incomingCall.value?.callIdHex != callIdHex) {
                callManager.onIncomingCallEvent(callIdHex, peerIdB64, hasVideo, offerSdp = "")
            }
            _pendingNavCall.value = callManager.incomingCall.value
        }
    }
    fun clearPendingCallNav() { _pendingNavCall.value = null }

    // ── Avatar preference ────────────────────────────────────────────────

    private val _myAvatarIndex = MutableStateFlow(0)
    val myAvatarIndex: StateFlow<Int> = _myAvatarIndex.asStateFlow()

    init {
        val prefs = appContext.getSharedPreferences(AdaConfig.IDENTITY_PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getInt(AdaConfig.KEY_AVATAR_INDEX, -1)
        if (stored == -1) {
            val random = kotlin.random.Random.nextInt(AdaConfig.AVATAR_COUNT)
            prefs.edit().putInt(AdaConfig.KEY_AVATAR_INDEX, random).apply()
            _myAvatarIndex.value = random
        } else {
            _myAvatarIndex.value = stored
        }
        registerSystemNetworkCallback()
    }

    private fun hasActiveInternetNetwork(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun registerSystemNetworkCallback() {
        if (networkCallback != null) return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (_initialized.value && core != null) {
                    // Notify Rust core so iroh re-probes interfaces and triggers an
                    // immediate pkarr republish, bypassing the exponential backoff.
                    core?.notifyNetworkAvailable()
                    if (reusedLiveCore && !_irohUp.value) {
                        Log.d(TAG, "system network restored while reusing live core -> promoting _irohUp")
                        _irohUp.value = true
                    }
                    loadBridgeStatus()
                }
            }

            override fun onLost(network: Network) {
                if (_initialized.value) {
                    // iroh QUIC connections from the lost network are always stale.
                    // Clear peer presence immediately so the UI doesn't show false-online
                    // indicators during Wi-Fi ↔ LTE handoff; the maintenance cycle or the
                    // next PeerOnline event will repopulate.
                    _onlinePeers.value = emptySet()
                    // Let iroh drop its stale QUIC connection cache immediately.
                    core?.notifyNetworkLost()
                    if (!hasActiveInternetNetwork()) {
                        Log.d(TAG, "system network lost -> clearing connectivity flags")
                        _irohUp.value = false
                        _bridgeLive.value = false
                        _mailboxAvailable.value = false
                        _bridgeRealtimeCalls.value = false
                    }
                }
            }
        }
        networkCallback = callback
        runCatching { connectivityManager.registerDefaultNetworkCallback(callback) }
            .onFailure {
                networkCallback = null
                Log.w(TAG, "registerDefaultNetworkCallback failed", it)
            }
    }

    fun setMyAvatarIndex(index: Int) {
        appContext.getSharedPreferences(AdaConfig.IDENTITY_PREFS, Context.MODE_PRIVATE)
            .edit().putInt(AdaConfig.KEY_AVATAR_INDEX, index).apply()
        _myAvatarIndex.value = index
    }

    // ══════════════════════════════════════════════════════════════════════
    //  IDENTITY — delegates to IdentityManager

    fun createFromPattern(cells: ByteArray, displayName: String) {
        viewModelScope.launch {
            try {
                val result = identity.createFromPattern(cells, displayName, _connectionProfile.value) ?: return@launch
                onCoreInitialized(result)
            } catch (e: Exception) {
                Log.e(TAG, "createFromPattern failed", e)
            }
        }
    }

    fun loginWithPattern(cells: ByteArray) {
        viewModelScope.launch {
            try {
                val result = identity.loginWithPattern(cells, _connectionProfile.value) ?: return@launch
                onCoreInitialized(result)
            } catch (e: Exception) {
                Log.e(TAG, "loginWithPattern failed", e)
            }
        }
    }

    fun loginWithCells(cells: ByteArray) {
        viewModelScope.launch {
            try {
                val result = identity.loginWithCells(cells, _connectionProfile.value) ?: return@launch
                onCoreInitialized(result)
            } catch (e: Exception) {
                Log.e(TAG, "loginWithCells failed", e)
            }
        }
    }

    fun clearPatternError() {
        identity.clearPatternError()
    }

    suspend fun exportRecoveryBundle(uri: Uri, password: String): String =
        withRecoveryMaintenance {
            identity.exportRecoveryBundle(uri, password)
        }

    suspend fun importRecoveryBundle(uri: Uri, password: String): String {
        val displayName = identity.importRecoveryBundle(uri, password)
        val restoredAvatar = appContext.getSharedPreferences(AdaConfig.IDENTITY_PREFS, Context.MODE_PRIVATE)
            .getInt(AdaConfig.KEY_AVATAR_INDEX, _myAvatarIndex.value)
        _myAvatarIndex.value = restoredAvatar
        return displayName
    }

    suspend fun importRecoveryCode(code: String, displayName: String): String {
        val restoredName = identity.importRecoveryCode(code, displayName)
        val restoredAvatar = appContext.getSharedPreferences(AdaConfig.IDENTITY_PREFS, Context.MODE_PRIVATE)
            .getInt(AdaConfig.KEY_AVATAR_INDEX, _myAvatarIndex.value)
        _myAvatarIndex.value = restoredAvatar
        return restoredName
    }

    fun getRecoveryCode(): String = identity.generateRecoveryCode()

    fun create(displayName: String) {
        viewModelScope.launch {
            val result = identity.createSimple(displayName, _connectionProfile.value) ?: return@launch
            onCoreInitialized(result)
        }
    }

    /**
     * Common post-initialization path for all login/create variants.
     * Wires the [AdaCore] instance into the rest of the app.
     */
    private fun onCoreInitialized(result: IdentityManager.InitResult) {
        bindInitializedCore(result, allowReplace = false)
    }

    private fun bindInitializedCore(result: IdentityManager.InitResult, allowReplace: Boolean) {
        // Guard against double initialization (e.g. automatic re-login racing with manual login).
        // A second call would replace `core`, leaving the first core's iroh connection
        // active but its event channel unpolled — messages arrive but never appear in the UI.
        if (_initialized.value && !allowReplace) {
            Log.w(TAG, "onCoreInitialized: already initialized — closing duplicate core")
            result.core.close()
            return
        }
        core = result.core
        result.core.setConnectionProfile(_connectionProfile.value)
        AdaCoreHolder.instance = result.core
        reusedLiveCore = result.reusedLiveCore
        _onlinePeers.value = emptySet()
        _bridgeStatus.value = null
        _bridgeLive.value = false
        _mailboxAvailable.value = false
        _bridgeRealtimeCalls.value = false
        _localMeshAvailable.value = false
        _lastTransportOutcome.value = null
        _irohUp.value = result.reusedLiveCore && hasActiveInternetNetwork()
        if (_irohUp.value) {
            Log.d(TAG, "bindInitializedCore: reusing live core with active network -> promoting _irohUp")
        }
        if (webRtc == null) {
            webRtc = WebRTCBridge(appContext, this)
        }
        _myPeerId.value = result.peerId
        _myDisplayName.value = result.displayName
        if (result.patternCells.isNotEmpty()) {
            lastPatternCells = result.patternCells
        }
        _initialized.value = true
        startEventPolling()
        refreshConversations()
        refreshGroups()
        refreshCallHistory()
        transferManager.refresh()
        loadBridgeStatus()
        processPendingDeepLink()
        detectCensorship()
    }

    private suspend fun <T> withRecoveryMaintenance(block: suspend () -> T): T =
        withContext(Dispatchers.IO) {
            val existingCore = core ?: return@withContext block()
            val recoveryCells = synchronized(cellsLock) {
                lastPatternCells?.copyOf()
            } ?: identity.loadBackgroundCells()
            ?: throw IllegalStateException(
                "Recovery export requires a fresh unlock. Re-enter your pattern and try again."
            )

            eventPollingJob?.cancel()
            eventPollingJob = null
            AdaCoreHolder.isViewModelActive = false
            appContext.stopService(Intent(appContext, com.ada.messenger.service.AdaForegroundService::class.java))
            if (AdaCoreHolder.instance === existingCore) AdaCoreHolder.instance = null
            core = null
            existingCore.close()

            var result: T? = null
            var failure: Throwable? = null
            try {
                result = block()
            } catch (t: Throwable) {
                failure = t
            }

            val reopenFailure = runCatching {
                val reopened = identity.loginWithCells(recoveryCells, _connectionProfile.value)
                    ?: throw IllegalStateException(
                        "Recovery export finished, but the account could not be reopened automatically. Re-enter your pattern."
                    )
                withContext(Dispatchers.Main) {
                    bindInitializedCore(reopened, allowReplace = true)
                }
            }.exceptionOrNull()

            if (failure != null) {
                if (reopenFailure != null) {
                    failure.addSuppressed(reopenFailure)
                }
                throw failure
            }
            if (reopenFailure != null) {
                throw reopenFailure
            }

            @Suppress("UNCHECKED_CAST")
            result as T
        }

    /** Pattern verification (used for re-entry check). */
    suspend fun verifyPattern(cells: ByteArray): Boolean = withContext(Dispatchers.IO) {
        core?.verifyPattern(cells) ?: false
    }

    fun hasStoredIdentity(): Boolean = identity.hasStoredIdentity()
    fun getStoredDisplayName(): String? = identity.getStoredDisplayName()

    // ══════════════════════════════════════════════════════════════════════
    //  CONTACTS & DEEP LINKS
    // ══════════════════════════════════════════════════════════════════════

    fun getContactCardJson(): String? = core?.getContactCardJson()
    fun createIncognitoChat(peerIdB64: String): String? = core?.createIncognitoChat(peerIdB64)

    fun addContactFromQr(contactCardJson: String): Boolean {
        if (_isCleanMode.value) return false
        Log.i(TAG, "addContactFromQr len=${contactCardJson.length}")
        return runCatching { core?.addContactFromJson(contactCardJson) ?: false }
            .onFailure { Log.e(TAG, "addContactFromQr failed", it) }
            .getOrDefault(false)
    }

    fun generateContactLink(): String? {
        val c = core ?: return null
        val json = c.getContactCardJson() ?: return null
        // Prefer the opaque short-link (ada://s/<token>); fall back to legacy
        // ada://add-contact?card=<base64url-json> if the native call fails.
        return c.encodeContactShortLink(json) ?: run {
            val encoded = android.util.Base64.encodeToString(
                json.toByteArray(Charsets.UTF_8),
                android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP,
            )
            "ada://add-contact?card=$encoded"
        }
    }

    /** Decode an `ada://s/<token>` short-link into a contact-card JSON string. */
    fun decodeShortLink(url: String): String? = core?.decodeContactShortLink(url)

    @Volatile private var pendingDeepLinkCard: String? = null
    @Volatile private var pendingBridgeManifestImport: PendingBridgeManifestImport? = null
    @Volatile private var pendingGroupCallLink: PendingGroupCallLink? = null

    fun buildGroupCallDeepLink(groupIdHex: String, sessionIdHex: String, hasVideo: Boolean): String {
        return Uri.Builder()
            .scheme("ada")
            .authority("call")
            .appendQueryParameter("group", groupIdHex)
            .appendQueryParameter("session", sessionIdHex)
            .appendQueryParameter("video", if (hasVideo) "1" else "0")
            .build()
            .toString()
    }

    private fun parseGroupCallDeepLink(input: String): PendingGroupCallLink? {
        val normalized = input.trim()
            .removePrefix("<")
            .removeSuffix(">")
            .trim()
        val uri = runCatching { Uri.parse(normalized) }.getOrNull() ?: return null
        if (uri.scheme != "ada" || uri.host != "call") {
            return null
        }
        val groupIdHex = uri.getQueryParameter("group")?.trim().orEmpty()
        val sessionIdHex = uri.getQueryParameter("session")?.trim().orEmpty()
        if (groupIdHex.length != 32 || sessionIdHex.length != 32) {
            return null
        }
        if (!groupIdHex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' } ||
            !sessionIdHex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            return null
        }
        val hasVideo = when (uri.getQueryParameter("video")?.trim()?.lowercase()) {
            "1", "true", "yes" -> true
            else -> false
        }
        return PendingGroupCallLink(groupIdHex.lowercase(), sessionIdHex.lowercase(), hasVideo)
    }

    fun queueGroupCallDeepLink(input: String) {
        if (_isCleanMode.value) return
        val payload = parseGroupCallDeepLink(input) ?: run {
            _sendError.value = "Невалидная ссылка на звонок"
            return
        }
        if (core != null) {
            joinGroupCallFromDeepLink(payload)
        } else {
            pendingGroupCallLink = payload
        }
    }

    private fun joinGroupCallFromDeepLink(payload: PendingGroupCallLink) {
        joinGroupCall(payload.groupIdHex, payload.sessionIdHex, payload.hasVideo) {
            _pendingOpenCallScreen.value = true
        }
    }

    fun queueContactDeepLink(json: String) {
        if (_isCleanMode.value) return
        if (core != null) addContactFromDeepLink(json)
        else pendingDeepLinkCard = json
    }

    fun addContactFromDeepLink(json: String) {
        if (_isCleanMode.value) return
        viewModelScope.launch(Dispatchers.IO) {
            val peerId = runCatching { JSONObject(json).getString("id") }.getOrNull()
            val ok = core?.addContactFromJson(json) ?: false
            if (ok && peerId != null) {
                val name = runCatching { JSONObject(json).optString("name", "").ifBlank { null } }.getOrNull()
                val convId = "d:$peerId"
                refreshConversations()
                _pendingNavConv.value = Pair(convId, name ?: peerId.take(8) + "…")
            }
        }
    }

    /** Accept contact pasted as text: ada:// short-link, legacy add-contact link, or raw contact JSON. */
    fun importContactFromText(input: String): Boolean {
        if (_isCleanMode.value) return false
        val normalized = input.trim()
            .removePrefix("<")
            .removeSuffix(">")
            .trim()
        val contactJson = when {
            normalized.startsWith("ada://s/") -> decodeShortLink(normalized)
            normalized.startsWith("ada://add-contact") -> {
                val uri = runCatching { Uri.parse(normalized) }.getOrNull()
                val encoded = uri?.getQueryParameter("card").orEmpty()
                if (encoded.isNotBlank()) {
                    runCatching {
                        String(
                            android.util.Base64.decode(
                                encoded,
                                android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP,
                            ),
                            Charsets.UTF_8,
                        )
                    }.getOrNull()
                } else null
            }
            normalized.startsWith("{") -> runCatching {
                JSONObject(normalized).getString("id")
                normalized
            }.getOrNull()
            else -> null
        } ?: return false

        queueContactDeepLink(contactJson)
        return true
    }

    private fun normalizeManifestPublicKeyHex(value: String?): String? {
        val normalized = value?.trim()?.replace(":", "")?.uppercase().orEmpty()
        return normalized.ifBlank { null }
    }

    private fun sourceLabelForManifestUrl(url: String): String {
        val host = runCatching { Uri.parse(url).host }.getOrNull().orEmpty()
        return host.ifBlank { url }
    }

    private fun parseBridgeManifestImportInput(
        input: String,
        trustedPublicKeyHex: String? = null,
        sourceHint: String = appContext.getString(R.string.bridge_manifest_import_source_manual),
    ): PendingBridgeManifestImport? {
        val normalized = input.trim()
            .removePrefix("<")
            .removeSuffix(">")
            .trim()
        val fallbackKey = normalizeManifestPublicKeyHex(trustedPublicKeyHex)

        if (normalized.startsWith("ada://bridge-manifest")) {
            val uri = runCatching { Uri.parse(normalized) }.getOrNull() ?: return null
            if (uri.scheme != "ada" || uri.host != "bridge-manifest") {
                return null
            }

            val linkKey = normalizeManifestPublicKeyHex(uri.getQueryParameter("key")) ?: fallbackKey
            val encodedData = uri.getQueryParameter("data")
                ?.takeIf { it.isNotBlank() }
                ?: uri.getQueryParameter("manifest")?.takeIf { it.isNotBlank() }
            val manifestUrl = uri.getQueryParameter("url")?.trim().orEmpty()

            if (manifestUrl.startsWith("https://") || manifestUrl.startsWith("http://")) {
                return PendingBridgeManifestImport(
                    manifestUrl = manifestUrl,
                    trustedPublicKeyHex = linkKey,
                    source = manifestUrl,
                    noticeLabel = sourceLabelForManifestUrl(manifestUrl),
                )
            }

            if (!encodedData.isNullOrBlank()) {
                val manifestJson = runCatching {
                    String(
                        android.util.Base64.decode(
                            encodedData,
                            android.util.Base64.URL_SAFE or
                                android.util.Base64.NO_PADDING or
                                android.util.Base64.NO_WRAP,
                        ),
                        Charsets.UTF_8,
                    )
                }.getOrNull() ?: return null

                return PendingBridgeManifestImport(
                    manifestJson = manifestJson,
                    trustedPublicKeyHex = linkKey,
                    source = "deeplink",
                    noticeLabel = appContext.getString(R.string.bridge_manifest_import_source_deeplink),
                )
            }

            return null
        }

        if (normalized.startsWith("https://") || normalized.startsWith("http://")) {
            return PendingBridgeManifestImport(
                manifestUrl = normalized,
                trustedPublicKeyHex = fallbackKey,
                source = normalized,
                noticeLabel = sourceLabelForManifestUrl(normalized),
            )
        }

        if (!normalized.startsWith("{")) {
            return null
        }

        val manifestJson = runCatching {
            val obj = JSONObject(normalized)
            if (!obj.has("payload_json") || !obj.has("signature_hex")) {
                null
            } else {
                normalized
            }
        }.getOrNull() ?: return null

        return PendingBridgeManifestImport(
            manifestJson = manifestJson,
            trustedPublicKeyHex = fallbackKey,
            source = sourceHint,
            noticeLabel = sourceHint,
        )
    }

    private suspend fun readBridgeStatusSnapshot(): BridgeStatus? {
        val json = core?.getBridgeStatusJson() ?: "{}"
        return parseBridgeStatusJson(json)
    }

    private suspend fun importBridgeManifestPayload(
        payload: PendingBridgeManifestImport,
        emitNotice: Boolean = true,
    ): Boolean {
        val activeCore = core
        if (activeCore == null) {
            if (emitNotice) {
                _bridgeImportNotice.value = BridgeImportNotice(
                    appContext.getString(R.string.bridge_manifest_import_failed),
                    isError = true,
                )
            }
            return false
        }

        val trustedKey = payload.trustedPublicKeyHex.orEmpty()
        val result = when {
            payload.manifestJson != null -> activeCore.importBridgeManifestJsonDetailed(
                payload.manifestJson,
                payload.source,
                trustedKey,
            )
            payload.manifestUrl != null -> activeCore.importBridgeManifestUrlDetailed(
                payload.manifestUrl,
                trustedKey,
            )
            else -> AdaCore.BridgeOperationResult(false)
        }
        val ok = result.success

        if (ok) {
            _bridgeStatus.value = readBridgeStatusSnapshot()
            refreshBridgeFlags()
            if (emitNotice) {
                _bridgeImportNotice.value = BridgeImportNotice(
                    appContext.getString(R.string.bridge_manifest_import_success, payload.noticeLabel),
                    isError = false,
                )
            }
            return true
        }

        if (emitNotice) {
            _bridgeImportNotice.value = BridgeImportNotice(
                formatBridgeFailure(
                    result.error,
                    R.string.bridge_manifest_import_failed,
                    R.string.bridge_manifest_import_failed_with_reason,
                ),
                isError = true,
            )
        }
        return false
    }

    fun queueBridgeManifestDeepLink(input: String, trustedPublicKeyHex: String? = null) {
        if (_isCleanMode.value) {
            _bridgeImportNotice.value = BridgeImportNotice(
                "В чистом режиме импорт bridge-манифеста недоступен",
                isError = true,
            )
            return
        }
        val payload = parseBridgeManifestImportInput(
            input = input,
            trustedPublicKeyHex = trustedPublicKeyHex,
            sourceHint = appContext.getString(R.string.bridge_manifest_import_source_deeplink),
        )
        if (payload == null) {
            _bridgeImportNotice.value = BridgeImportNotice(
                appContext.getString(R.string.bridge_manifest_import_invalid),
                isError = true,
            )
            return
        }

        if (core != null) {
            viewModelScope.launch(Dispatchers.IO) {
                importBridgeManifestPayload(payload)
            }
        } else {
            pendingBridgeManifestImport = payload
        }
    }

    fun importBridgeManifestFromText(
        input: String,
        trustedPublicKeyHex: String? = null,
        sourceHint: String = appContext.getString(R.string.bridge_manifest_import_source_manual),
    ): Boolean {
        if (_isCleanMode.value) {
            _bridgeImportNotice.value = BridgeImportNotice(
                "В чистом режиме импорт bridge-манифеста недоступен",
                isError = true,
            )
            return false
        }
        val payload = parseBridgeManifestImportInput(input, trustedPublicKeyHex, sourceHint)
        if (payload == null) {
            _bridgeImportNotice.value = BridgeImportNotice(
                appContext.getString(R.string.bridge_manifest_import_invalid),
                isError = true,
            )
            return false
        }

        viewModelScope.launch(Dispatchers.IO) {
            importBridgeManifestPayload(payload)
        }
        return true
    }

    suspend fun readTextFromUri(uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                val bytes = input.readBytes()
                if (bytes.size > 512 * 1024) {
                    throw IllegalStateException("bridge manifest file is too large")
                }
                String(bytes, Charsets.UTF_8)
            }
        }.getOrNull()
    }

    suspend fun importBridgeManifestFromUri(
        uri: Uri,
        trustedPublicKeyHex: String? = null,
    ): Boolean {
        val text = readTextFromUri(uri)
        if (text == null) {
            _bridgeImportNotice.value = BridgeImportNotice(
                appContext.getString(R.string.bridge_manifest_import_read_failed),
                isError = true,
            )
            return false
        }

        val sourceLabel = uri.lastPathSegment
            ?.substringAfterLast('/')
            ?.ifBlank { null }
            ?: appContext.getString(R.string.bridge_manifest_import_source_file)
        val payload = parseBridgeManifestImportInput(
            input = text,
            trustedPublicKeyHex = trustedPublicKeyHex,
            sourceHint = "file:$sourceLabel",
        )?.copy(noticeLabel = sourceLabel)

        if (payload == null) {
            _bridgeImportNotice.value = BridgeImportNotice(
                appContext.getString(R.string.bridge_manifest_import_invalid),
                isError = true,
            )
            return false
        }

        return importBridgeManifestPayload(payload)
    }

    suspend fun importCustomBridgeBootstrap(
        manifestUrl: String,
        trustedPublicKeyHex: String,
    ): AdaCore.BridgeOperationResult = withContext(Dispatchers.IO) {
        if (_isCleanMode.value) {
            return@withContext AdaCore.BridgeOperationResult(
                false,
                appContext.getString(R.string.bridge_manifest_import_clean_unavailable),
            )
        }

        val normalizedUrl = manifestUrl.trim()
            .removePrefix("<")
            .removeSuffix(">")
            .trim()
        if (!normalizedUrl.startsWith("https://") && !normalizedUrl.startsWith("http://")) {
            return@withContext AdaCore.BridgeOperationResult(
                false,
                appContext.getString(R.string.bridge_manifest_import_invalid),
            )
        }

        val activeCore = core ?: return@withContext AdaCore.BridgeOperationResult(
            false,
            appContext.getString(R.string.dialog_profile_not_loaded),
        )

        val result = activeCore.importBridgeManifestUrlDetailed(
            normalizedUrl,
            trustedPublicKeyHex.trim(),
        )

        if (result.success) {
            _bridgeStatus.value = readBridgeStatusSnapshot()
            refreshBridgeFlags()
            result
        } else {
            AdaCore.BridgeOperationResult(
                false,
                formatBridgeFailure(
                    result.error,
                    R.string.bridge_manifest_import_failed,
                    R.string.bridge_manifest_import_failed_with_reason,
                ),
            )
        }
    }

    private fun processPendingDeepLink() {
        pendingDeepLinkCard?.let { card ->
            pendingDeepLinkCard = null
            addContactFromDeepLink(card)
        }

        pendingBridgeManifestImport?.let { manifestImport ->
            pendingBridgeManifestImport = null
            viewModelScope.launch(Dispatchers.IO) {
                importBridgeManifestPayload(manifestImport)
            }
        }

        pendingGroupCallLink?.let { groupCallLink ->
            pendingGroupCallLink = null
            joinGroupCallFromDeepLink(groupCallLink)
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  CONVERSATIONS & MESSAGES
    // ══════════════════════════════════════════════════════════════════════

    private fun resolveContactDisplayName(peerIdB64: String?, fallback: String): String {
        val peerId = peerIdB64?.takeIf { it.isNotBlank() } ?: return fallback
        return contactAliasStore.getAlias(peerId) ?: fallback
    }

    private fun applyConversationAliases(conversations: List<ConversationItem>): List<ConversationItem> =
        conversations.map { conversation ->
            val peerId = conversation.peerIdB64 ?: return@map conversation
            conversation.copy(displayName = resolveContactDisplayName(peerId, conversation.displayName))
        }

    private fun applyMessageAliases(messages: List<ChatMessage>): List<ChatMessage> =
        messages.map { message ->
            if (message.sender.isBlank() || message.isMine) return@map message
            val alias = contactAliasStore.getAlias(message.sender) ?: return@map message
            if (message.senderName == alias) message else message.copy(senderName = alias)
        }

    private fun applyGroupInfoAliases(groupInfo: GroupInfo?): GroupInfo? =
        groupInfo?.copy(
            members = groupInfo.members.map { member ->
                member.copy(displayName = resolveContactDisplayName(member.peerIdB64, member.displayName))
            },
        )

    private suspend fun loadConversationsFromCore(): List<ConversationItem> {
        val json = core?.getConversationsJson() ?: return emptyList()
        return applyConversationAliases(parseConversations(json))
    }

    private suspend fun searchConversationsFromCore(query: String): List<ConversationItem> {
        val json = core?.searchConversationsJson(query) ?: return emptyList()
        return applyConversationAliases(parseConversations(json))
    }

    private suspend fun loadMessagesFromCore(convId: String): List<ChatMessage> {
        val json = core?.getMessagesJson(convId, 100) ?: return emptyList()
        return applyMessageAliases(parseMessages(json))
    }

    private suspend fun loadGroupInfoFromCore(groupIdHex: String): GroupInfo? {
        val json = core?.getGroupInfoJson(groupIdHex) ?: return null
        return applyGroupInfoAliases(parseGroupInfo(json))
    }

    private suspend fun loadCallHistoryFromCore(conversations: List<ConversationItem>): List<CallLogEntry> {
        val json = core?.getCallHistoryJson(200) ?: return emptyList()
        return parseCallHistory(json, conversations)
    }

    fun refreshConversations() {
        if (_isCleanMode.value) {
            _conversations.value = emptyList()
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            _conversations.value = loadConversationsFromCore()
        }
    }

    fun searchConversations(query: String) {
        searchJob?.cancel()
        lastSearchQuery = query.trim()
        if (_isCleanMode.value || lastSearchQuery.isBlank()) {
            lastSearchQuery = ""
            _searchResults.value = emptyList()
            return
        }

        searchJob = viewModelScope.launch(Dispatchers.IO) {
            delay(120L)
            _searchResults.value = searchConversationsFromCore(lastSearchQuery)
        }
    }

    fun openConversation(convId: String) {
        if (_isCleanMode.value) return
        activeConvId = convId
        core?.markRead(convId)
        refreshMessages(convId)
    }

    fun closeConversation() {
        activeConvId = null
        _messages.value = emptyList()
    }

    private fun refreshMessages(convId: String) {
        if (_isCleanMode.value) {
            _messages.value = emptyList()
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            _messages.value = loadMessagesFromCore(convId)
        }
    }

    fun refreshChatMessages(convId: String) = refreshMessages(convId)

    fun sendText(convId: String, text: String, expiresInSecs: Int? = null) {
        if (_isCleanMode.value) return
        val myId = _myPeerId.value ?: return
        if (text.length > AdaConfig.MAX_TEXT_LENGTH) {
            _sendError.value = appContext.getString(R.string.error_message_too_long, AdaConfig.MAX_TEXT_LENGTH)
            return
        }
        val optimistic = ChatMessage(
            id = java.util.UUID.randomUUID().toString(),
            sender = myId, senderName = "",
            text = text, timestampMs = System.currentTimeMillis(),
            isMine = true, status = "sending", kind = "text",
        )
        _messages.value = _messages.value + optimistic

        viewModelScope.launch(Dispatchers.IO) {
            if (convId.startsWith("g:")) {
                val gid = convId.removePrefix("g:")
                val ok = core?.sendGroupText(gid, text) ?: false
                _messages.update { list -> list.filter { it.id != optimistic.id } }
                if (!ok) {
                    _sendError.value = appContext.getString(R.string.error_send_group_message_failed)
                }
                refreshMessages(convId)
                refreshConversations()
                return@launch
            }
            val peerIdB64 = if (convId.startsWith("d:")) convId.removePrefix("d:") else {
                _messages.update { list -> list.filter { it.id != optimistic.id } }
                return@launch
            }
            val ok = try {
                core?.sendText(peerIdB64, text, expiresInSecs) ?: false
            } catch (e: Exception) {
                Log.e(TAG, "sendText JNI exception: ${e.message}", e)
                false
            }
            if (!ok) {
                _messages.update { list -> list.filter { it.id != optimistic.id } }
                _sendError.value = appContext.getString(R.string.error_send_message_no_key_exchange)
                loadBridgeStatus()
                return@launch
            }
            refreshMessages(convId)
            refreshConversations()
        }
    }

    /** Send a text reply referencing an earlier message (Telegram-style quoting). */
    fun sendReply(convId: String, text: String, replyToMsgId: String) {
        if (_isCleanMode.value) return
        val myId = _myPeerId.value ?: return
        if (text.length > AdaConfig.MAX_TEXT_LENGTH) {
            _sendError.value = appContext.getString(R.string.error_message_too_long, AdaConfig.MAX_TEXT_LENGTH)
            return
        }
        val optimistic = ChatMessage(
            id = java.util.UUID.randomUUID().toString(),
            sender = myId, senderName = "",
            text = text, timestampMs = System.currentTimeMillis(),
            isMine = true, status = "sending", kind = "text",
            replyToId = replyToMsgId,
            replyToText = _messages.value.find { it.id == replyToMsgId }?.text?.take(80),
        )
        _messages.value = _messages.value + optimistic

        viewModelScope.launch(Dispatchers.IO) {
            val peerIdB64 = if (convId.startsWith("d:")) convId.removePrefix("d:") else null
            val groupIdHex = if (convId.startsWith("g:")) convId.removePrefix("g:") else null
            
            if (peerIdB64 == null && groupIdHex == null) {
                _messages.update { list -> list.filter { it.id != optimistic.id } }
                return@launch
            }

            val ok = try {
                if (peerIdB64 != null) {
                    core?.sendReply(peerIdB64, text, replyToMsgId) ?: false
                } else if (groupIdHex != null) {
                    core?.sendGroupReply(groupIdHex, text, replyToMsgId) ?: false
                } else false
            } catch (e: Exception) {
                Log.e(TAG, "sendReply JNI exception: ${e.message}", e)
                false
            }
            if (!ok) {
                _messages.update { list -> list.filter { it.id != optimistic.id } }
                _sendError.value = appContext.getString(R.string.error_send_message_no_key_exchange)
                loadBridgeStatus()
                return@launch
            }
            refreshMessages(convId)
            refreshConversations()
        }
    }

    fun editMessage(convId: String, msgId: String, newText: String) {
        if (_isCleanMode.value) return
        if (newText.length > AdaConfig.MAX_TEXT_LENGTH) {
            _sendError.value = appContext.getString(R.string.error_message_too_long, AdaConfig.MAX_TEXT_LENGTH)
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            val ok = try {
                when {
                    convId.startsWith("d:") -> {
                        val peerIdB64 = convId.removePrefix("d:")
                        core?.editMessage(peerIdB64, msgId, newText) ?: false
                    }
                    convId.startsWith("g:") -> {
                        val groupIdHex = convId.removePrefix("g:")
                        core?.editGroupMessage(groupIdHex, msgId, newText) ?: false
                    }
                    else -> false
                }
            } catch (e: Exception) {
                Log.e(TAG, "editMessage JNI exception: ${e.message}", e)
                false
            }

            if (!ok) {
                _sendError.value = appContext.getString(R.string.error_edit_message_failed)
                loadBridgeStatus()
                return@launch
            }

            refreshMessages(convId)
            refreshConversations()
        }
    }

    fun deleteMessage(convId: String, msgId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            core?.deleteMessage(msgId)
            refreshMessages(convId)
            refreshConversations()
        }
    }

    /**
     * V-12: Toggle emoji reaction on a message.
     * Sending same emoji again cancels it (Rust FFI counts odd/even).
     * Max 3 distinct active reactions per user per message.
     */
    fun toggleReaction(convId: String, msgId: String, emoji: String, currentMyReactions: Set<String>) {
        val peerIdB64 = if (convId.startsWith("d:")) convId.removePrefix("d:") else null
        val groupIdHex = if (convId.startsWith("g:")) convId.removePrefix("g:") else null
        if (peerIdB64 == null && groupIdHex == null) return
        // If already reacted with this emoji → always allow (it will remove it)
        // If not → only allow if under 3 distinct reactions
        if (emoji !in currentMyReactions && currentMyReactions.size >= 3) return
        viewModelScope.launch(Dispatchers.IO) {
            val ok = if (peerIdB64 != null) {
                core?.sendReaction(peerIdB64, msgId, emoji) ?: false
            } else if (groupIdHex != null) {
                core?.sendGroupReaction(groupIdHex, msgId, emoji) ?: false
            } else false
            if (ok) refreshMessages(convId)
            else Log.w("AdaCoreViewModel", "toggleReaction failed for msgId=$msgId")
        }
    }

    fun deleteMessageForEveryone(convId: String, peerIdB64: String, msgId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            core?.deleteMessageForEveryone(peerIdB64, msgId)
            refreshMessages(convId)
            refreshConversations()
        }
    }

    fun deleteConversation(convId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            core?.deleteConversation(convId)
            _messages.value = emptyList()
            refreshConversations()
        }
    }

    fun clearConversation(convId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            core?.clearConversationMessages(convId)
            _messages.value = emptyList()
            refreshConversations()
        }
    }

    fun addRelayNode(relayUrl: String): Boolean = core?.addRelayNode(relayUrl) ?: false

    // ══════════════════════════════════════════════════════════════════════
    //  CALLS — delegates to CallManager
    // ══════════════════════════════════════════════════════════════════════

    fun startAudioCall(peerIdB64: String, onCallId: (String?) -> Unit) = callManager.startAudioCall(peerIdB64, onCallId)
    fun startVideoCall(peerIdB64: String, onCallId: (String?) -> Unit) = callManager.startVideoCall(peerIdB64, onCallId)
    fun answerCall(callIdHex: String, peerIdB64: String) {
        NotificationHelper.cancelCallNotification(appContext)
        callManager.answerCall(callIdHex, peerIdB64)
    }
    fun sendRustAnswer(callIdHex: String, peerIdB64: String, answerSdp: String) = callManager.sendRustAnswer(callIdHex, peerIdB64, answerSdp)
    fun hangup(callIdHex: String, peerIdB64: String) = callManager.hangup(callIdHex, peerIdB64)
    fun declineCall() {
        NotificationHelper.cancelCallNotification(appContext)
        callManager.declineCall()
    }
    fun setMuted(muted: Boolean) = callManager.setMuted(muted)
    fun setSpeaker(speakerOn: Boolean) = callManager.setSpeaker(speakerOn)
    fun switchCamera() = callManager.switchCamera()
    fun setRemoteVideoSink(sink: org.webrtc.VideoSink?) = callManager.setRemoteVideoSink(sink)
    fun selectRemoteVideoPeer(peerIdB64: String?) = callManager.selectRemoteVideoPeer(peerIdB64)
    fun setLocalVideoSink(sink: org.webrtc.VideoSink?) = callManager.setLocalVideoSink(sink)
    fun setVideoEnabled(enabled: Boolean) = callManager.setVideoEnabled(enabled)
    fun startScreenShare(resultData: android.content.Intent) = callManager.startScreenShare(resultData)
    fun stopScreenShare() = callManager.stopScreenShare()
    fun onScreenShareStateChanged(sharing: Boolean) = callManager.onScreenShareStateChanged(sharing)
    fun sendIceCandidate(callIdHex: String, peerIdB64: String, candidate: String, sdpMid: String, sdpMlineIndex: Int) =
        callManager.sendIceCandidate(callIdHex, peerIdB64, candidate, sdpMid, sdpMlineIndex)
    fun sendIceRestartOffer(callIdHex: String, peerIdB64: String, offerSdp: String) = callManager.sendIceRestartOffer(callIdHex, peerIdB64, offerSdp)
    fun sendIceRestartAnswer(callIdHex: String, peerIdB64: String, answerSdp: String) = callManager.sendIceRestartAnswer(callIdHex, peerIdB64, answerSdp)
    fun refreshActiveCalls() = callManager.refreshActiveCalls()
    // refreshCallHistory() is defined in the call-history section above

    fun startGroupAudioCall(groupIdHex: String, onStarted: () -> Unit) = callManager.startGroupAudioCall(groupIdHex, onStarted)
    fun startGroupVideoCall(groupIdHex: String, onStarted: () -> Unit) {
        val memberCount = _currentGroupInfo.value?.memberCount ?: 0
        callManager.startGroupVideoCall(groupIdHex, memberCount, onStarted) { _sendError.value = it }
    }
    fun joinGroupCall(groupIdHex: String, sessionIdHex: String, hasVideo: Boolean, onStarted: () -> Unit) {
        callManager.joinGroupCall(groupIdHex, sessionIdHex, hasVideo, onStarted) { _sendError.value = it }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  FILE TRANSFER — delegates to TransferManager
    // ══════════════════════════════════════════════════════════════════════

    fun sendFile(convId: String, fileName: String, filePath: String) = transferManager.sendFile(convId, fileName, filePath)

    fun sendAttachment(convId: String, fileName: String, mimeType: String, filePath: String) {
        val error = transferManager.sendAttachment(convId, fileName, mimeType, filePath) {
            activeConvId?.let { refreshMessages(it) }
            refreshConversations()
        }
        if (error != null) _sendError.value = error
    }

    fun cancelTransfer(transferIdHex: String) = transferManager.cancelTransfer(transferIdHex)
    fun refreshTransfers() = transferManager.refresh()
    fun getMediaCacheSize(): Long = transferManager.cacheSize()
    fun clearMediaCache() {
        transferManager.clearCache()
    }

    /**
     * Return a File path suitable for passing to Coil / ExoPlayer / FileProvider.
     *
     * • Plain files (not encrypted) are returned as-is.
     * • Encrypted `.enc` files are decrypted to a session-scoped temp copy in
     *   [cacheDir]/tmp_attachments/ and the temp path is returned.
     *
     * Temp files are purged on the next app start via [TransferManager.purgeTempAttachments].
     * This method is safe to call from any coroutine context (performs I/O on Dispatchers.IO).
     */
    suspend fun getAttachmentForDisplay(path: String): String? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            if (path.endsWith(".enc")) transferManager.decryptToTempFile(path)?.absolutePath
            else if (java.io.File(path).isFile) path
            else null
        }

    // ══════════════════════════════════════════════════════════════════════
    //  BRIDGE / CENSORSHIP
    // ══════════════════════════════════════════════════════════════════════

    private fun profilePrefs() = appContext.getSharedPreferences(AdaConfig.PROFILE_PREFS, Context.MODE_PRIVATE)

    private fun loadSavedConnectionProfile(): String = AdaConfig.normalizeConnectionProfile(
        profilePrefs().getString(AdaConfig.KEY_CONNECTION_PROFILE, AdaConfig.DEFAULT_CONNECTION_PROFILE)
    )

    fun loadBridgeStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            val status = readBridgeStatusSnapshot()
            val statusProfile = status?.connectionProfile
            if (statusProfile != null && statusProfile != _connectionProfile.value) {
                _connectionProfile.value = statusProfile
            }
            _bridgeStatus.value = status
            _lastTransportOutcome.value = status?.lastOutcome
            _localMeshAvailable.value = status?.lastOutcome?.isLocalMesh == true
            refreshBridgeFlags()
        }
    }

    /** Push bridge state snapshot into the atomic flags that feed [connectionLevel]. */
    private fun refreshBridgeFlags() {
        val bs = _bridgeStatus.value
        Log.d(TAG, "refreshBridgeFlags: irohReady=${bs?.irohReady}, _irohUp=${_irohUp.value}, bridgeLive=${bs?.bridgeListenerConnected}, mailbox=${bs?.capabilities?.mailboxDelivery}")
        _bridgeLive.value = bs?.bridgeListenerConnected == true && bs.capabilities.bridgeLiveDelivery
        _mailboxAvailable.value = bs?.capabilities?.mailboxDelivery == true
        _bridgeRealtimeCalls.value = bs?.capabilities?.realtimeCalls == true

        // Sync _irohUp with the authoritative Rust-side iroh_ready flag.
        // This handles the case where the ViewModel reuses a live core from
        // AdaCoreHolder (created by ForegroundService): the NetworkConnected
        // event was already consumed before the ViewModel's polling started,
        // so _irohUp would stay false without this check.
        if (bs?.irohReady == true && !_irohUp.value) {
            _irohUp.value = true
        }

        // If the last delivery outcome used a non-iroh route while iroh was supposedly up,
        // it means iroh failed for at least this send cycle.  Downgrade _irohUp so the
        // connection indicator reflects reality instead of optimistic stale state.
        // But only do this if iroh_ready is also false (Rust confirms iroh is really down).
        // Otherwise a stale lastOutcome from a previous session could wrongly kill the indicator.
        val lastRoute = bs?.lastOutcome?.route
        if (_irohUp.value && bs?.irohReady != true && lastRoute != null && lastRoute != "iroh_live" && lastRoute != "offline_queue") {
            _irohUp.value = false
        }
    }

    private fun parseBridgeStatusJson(json: String): BridgeStatus? {
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val bridgesArr = obj.optJSONArray("bridges") ?: JSONArray()
        val bridges = (0 until bridgesArr.length()).map { bridgesArr.getJSONObject(it) }
        val lastOutcomeObj = obj.optJSONObject("last_outcome")
        val capabilitiesObj = obj.optJSONObject("capabilities") ?: JSONObject()
        val manifestObj = obj.optJSONObject("manifest")
        return BridgeStatus(
            mode = obj.optString("mode", "auto"),
            connectionProfile = AdaConfig.normalizeConnectionProfile(obj.optString("connection_profile", AdaConfig.DEFAULT_CONNECTION_PROFILE)),
            bridges = bridges,
            hasWorking = obj.optBoolean("has_working"),
            relayOnly = obj.optBoolean("relay_only"),
            irohReady = obj.optBoolean("iroh_ready"),
            transportStack = obj.optString("transport_stack", "unknown"),
            routeGranularity = obj.optString("route_granularity", "unknown"),
            relayOnlyScope = obj.optString("relay_only_scope", "disabled"),
            bridgeListenerConnected = obj.optBoolean("bridge_listener_connected"),
            bridgeListenerRoute = obj.optString("bridge_listener_route", "").ifBlank { null },
            bridgeMailboxDepth = obj.optInt("bridge_mailbox_depth", 0),
            capabilities = BridgeCapabilitiesSummary(
                bridgeLiveDelivery = capabilitiesObj.optBoolean("bridge_live_delivery"),
                mailboxDelivery = capabilitiesObj.optBoolean("mailbox_delivery"),
                realtimeCalls = capabilitiesObj.optBoolean("realtime_calls"),
                largeAttachments = capabilitiesObj.optBoolean("large_attachments"),
                maxAttachmentBytes = capabilitiesObj.optLong("max_attachment_bytes", 0L),
            ),
            manifest = manifestObj?.let {
                BridgeManifestSummary(
                    version = it.optLong("version", 0L),
                    issuedAtMs = it.optLong("issued_at_ms", 0L),
                    ttlSecs = it.optLong("ttl_secs", 0L),
                    bridgeCount = it.optInt("bridge_count", 0),
                    supportsRealtimeCalls = it.optBoolean("supports_realtime_calls"),
                    maxAttachmentBytes = if (it.has("max_attachment_bytes") && !it.isNull("max_attachment_bytes")) it.optLong("max_attachment_bytes") else null,
                    source = it.optString("source", "").ifBlank { null },
                )
            },
            lastOutcome = lastOutcomeObj?.let {
                TransportOutcomeSummary(
                    route = it.optString("route", "unknown"),
                    messageIdHex = it.optString("message_id", ""),
                    queueDepth = if (it.has("queue_depth") && !it.isNull("queue_depth")) it.optInt("queue_depth") else null,
                    latencyMs = if (it.has("latency_ms") && !it.isNull("latency_ms")) it.optLong("latency_ms") else null,
                )
            },
        )
    }

    private fun parseTransportOutcomeEvent(event: AdaEvent): TransportOutcomeSummary {
        val raw = event.raw
        return TransportOutcomeSummary(
            route = raw.optString("route", "unknown"),
            messageIdHex = raw.optString("message_id", ""),
            queueDepth = if (raw.has("queue_depth") && !raw.isNull("queue_depth")) raw.optInt("queue_depth") else null,
            latencyMs = if (raw.has("latency_ms") && !raw.isNull("latency_ms")) raw.optLong("latency_ms") else null,
        )
    }

    fun addBridge(bridgeLine: String) {
        val line = bridgeLine.trim()
        if (line.isBlank() || line.length > 512) {
            _bridgeImportNotice.value = BridgeImportNotice(
                appContext.getString(R.string.bridge_add_invalid),
                isError = true,
            )
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            val result = core?.addBridgeDetailed(line) ?: AdaCore.BridgeOperationResult(false)
            val ok = result.success
            loadBridgeStatus()
            _bridgeImportNotice.value = BridgeImportNotice(
                if (ok) {
                    appContext.getString(R.string.bridge_add_success)
                } else {
                    formatBridgeFailure(result.error, R.string.bridge_add_failed, R.string.bridge_add_failed_with_reason)
                },
                isError = !ok,
            )
        }
    }

    private fun formatBridgeFailure(error: String?, fallbackRes: Int, withReasonRes: Int): String {
        val reason = error
            ?.trim()
            ?.removePrefix("Bridge error: ")
            ?.takeIf { it.isNotBlank() }
            ?.take(240)
        return if (reason == null) {
            appContext.getString(fallbackRes)
        } else {
            appContext.getString(withReasonRes, reason)
        }
    }

    fun setBridgeMode(modeStr: String) {
        viewModelScope.launch(Dispatchers.IO) { core?.setBridgeMode(modeStr); loadBridgeStatus() }
    }

    fun setRelayOnly(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) { core?.setRelayOnly(enabled); loadBridgeStatus() }
    }

    fun setConnectionProfile(profile: String) {
        val normalized = AdaConfig.normalizeConnectionProfile(profile)
        _connectionProfile.value = normalized
        profilePrefs().edit().putString(AdaConfig.KEY_CONNECTION_PROFILE, normalized).apply()
        viewModelScope.launch(Dispatchers.IO) {
            core?.setConnectionProfile(normalized)
            loadBridgeStatus()
        }
    }

    fun detectCensorship() {
        viewModelScope.launch(Dispatchers.IO) {
            val json = core?.detectCensorshipJson() ?: "{\"level\":\"None\"}"
            val obj = runCatching { JSONObject(json) }.getOrNull()
            _censorshipLevel.value = obj?.optString("level", "None") ?: "None"
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  GROUPS
    // ══════════════════════════════════════════════════════════════════════

    fun refreshGroups() {
        viewModelScope.launch(Dispatchers.IO) {
            val json = core?.getGroupsJson() ?: "[]"
            _groups.value = parseGroups(json)
        }
    }

    fun loadGroupInfo(groupIdHex: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _currentGroupInfo.value = loadGroupInfoFromCore(groupIdHex)
        }
    }

    fun createGroup(name: String, onResult: (String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val groupIdHex = core?.createGroup(name)
            withContext(Dispatchers.Main) { onResult(groupIdHex) }
            if (groupIdHex != null) { refreshGroups(); refreshConversations() }
        }
    }

    fun inviteToGroup(groupIdHex: String, peerIdB64: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val info = _currentGroupInfo.value
            if (info != null && info.memberCount >= AdaConfig.MAX_GROUP_SIZE) {
                _sendError.value = appContext.getString(R.string.error_group_full, AdaConfig.MAX_GROUP_SIZE)
                return@launch
            }
            val ok = core?.inviteToGroup(groupIdHex, peerIdB64) ?: false
            if (!ok) _sendError.value = appContext.getString(R.string.error_add_member_failed)
            loadGroupInfo(groupIdHex)
        }
    }

    fun leaveGroup(groupIdHex: String) {
        viewModelScope.launch(Dispatchers.IO) {
            core?.leaveGroup(groupIdHex)
            refreshGroups()
            refreshConversations()
            _currentGroupInfo.value = null
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  EVENT POLLING
    // ══════════════════════════════════════════════════════════════════════

    private var eventPollingJob: Job? = null

    private fun startEventPolling() {
        eventPollingJob?.cancel()
        AdaCoreHolder.isViewModelActive = true
        eventPollingJob = viewModelScope.launch(Dispatchers.IO) {
            AdaCoreHolder.events.collect { json ->
                val event = parseEvent(json)
                if (event != null) {
                    handleEvent(event)
                }
            }
        }
    }

    /**
     * Fire-and-forget: encrypt the newly received message and POST it to the
     * paired desktop sync server (if configured).
     *
     * This runs on [Dispatchers.IO] and never throws to the caller.
     */
    private fun pushSyncToDesktop(eventRaw: org.json.JSONObject) {
        val c = core ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val syncUrl = c.getLinkedDeviceSyncUrl() ?: return@launch
                // Reject non-http:// URLs to prevent SSRF if the stored sync URL
                // were ever set to a file://, https:// or other dangerous scheme.
                if (!syncUrl.startsWith("http://")) {
                    Log.w(TAG, "pushSyncToDesktop: unexpected syncUrl scheme, skipping")
                    return@launch
                }
                val linkKeyHex = c.getLinkKeyHex() ?: return@launch

                // Build a minimal ChatMessage JSON from the event fields.
                val messageId = eventRaw.optString("message_id", "")
                val sender = eventRaw.optString("sender", "")
                val text = eventRaw.optString("text", "")
                val timestampMs = eventRaw.optLong("timestamp_ms", System.currentTimeMillis())
                if (messageId.isBlank() || sender.isBlank()) return@launch

                val chatMsgJson = org.json.JSONObject().apply {
                    put("message_id", messageId)
                    put("peer_id", sender)
                    put("is_outgoing", false)
                    put("kind", "text")
                    put("body_text", text)
                    put("status", "delivered")
                    put("created_at", timestampMs / 1000)
                }.toString()

                val sealedB64 = c.sealSyncPushJson(chatMsgJson) ?: return@launch

                // POST to desktop sync server.
                val conn = (java.net.URL(syncUrl).openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/octet-stream")
                    connectTimeout = 5_000
                    readTimeout = 10_000
                    doOutput = true
                }
                try {
                    val bodyBytes = sealedB64.toByteArray(Charsets.UTF_8)
                    DesktopSyncAuth.applyHeaders(conn, linkKeyHex, "POST", "/sync", sealedB64)
                    conn.setRequestProperty("Content-Length", bodyBytes.size.toString())
                    conn.outputStream.use { it.write(bodyBytes) }
                    val code = conn.responseCode
                    Log.d(TAG, "sync push to desktop: HTTP $code")
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                Log.w(TAG, "pushSyncToDesktop failed: ${e.message}")
            }
        }
    }

    private fun drainEvents(timeoutMs: Int = 0) {
        val c = core ?: return
        var count = 0
        
        // Loop continuously to drain multiple incoming events rapidly.
        // First poll waits `timeoutMs`, subsequent polls in the same drain batch wait 0 (non-blocking).
        while (count < AdaConfig.MAX_EVENTS_PER_DRAIN) {
            val waitTime = if (count == 0) timeoutMs else 0
            val json = c.pollEventJson(waitTime) ?: break
            count++
            val event = parseEvent(json) ?: continue
            handleEvent(event)
        }
        if (count > 0) Log.d(TAG, "drainEvents: processed $count events")
    }

    private fun handleEvent(event: AdaEvent) {
        Log.d(TAG, "handleEvent type=${event.type}")
        when (event.type) {
            "NetworkConnected" -> {
                _irohUp.value = true
                refreshBridgeFlags()
                transferManager.retryPendingBlobs {
                    activeConvId?.let { refreshMessages(it) }
                }
                refreshConversations()
            }
            "NetworkDisconnected" -> {
                _irohUp.value = false
                // Iroh is down — all peer presence info is stale.
                _onlinePeers.value = emptySet()
                refreshConversations()
            }
            "ContactUpdated" -> {
                refreshConversations()
                // Also refresh messages if it's the active direct conversation
                val updatedPeer = event.raw.optString("peer_id")
                if (activeConvId == "d:$updatedPeer") {
                    refreshMessages(activeConvId!!)
                }
            }
            "MessageReceived" -> {
                val sender = event.raw.optString("sender")
                val directConvId = "d:$sender"
                val groupConvId = event.raw.optString("group_id", "")
                    .takeIf { it.isNotBlank() }?.let { "g:$it" }
                val relevantConv = groupConvId ?: directConvId
                if (relevantConv == activeConvId) {
                    activeConvId?.let { refreshMessages(it) }
                } else {
                    val senderName = event.raw.optString("sender_name", "").ifBlank { sender.take(8) + "…" }
                    val text = event.raw.optString("text", appContext.getString(R.string.notification_default_message)).let {
                        if (it.length > 80) it.take(80) + "…" else it
                    }
                    NotificationHelper.postMessage(appContext, senderName, text, relevantConv, appLock.notificationShowContent)
                }
                refreshConversations()
                // Push the new message to the paired desktop (fire-and-forget).
                pushSyncToDesktop(event.raw)
            }
            "MessageStatusChanged" -> activeConvId?.let { refreshMessages(it) }
            "MessageEdited" -> {
                activeConvId?.let { refreshMessages(it) }
                refreshConversations()
            }
            "MessageRouteChanged" -> {
                val outcome = parseTransportOutcomeEvent(event)
                _lastTransportOutcome.value = outcome
                _localMeshAvailable.value = outcome.isLocalMesh
                loadBridgeStatus()
            }
            "IncomingCall" -> {
                val callId = event.raw.optString("call_id")
                val peer   = event.raw.optString("peer")
                val video  = event.raw.optBoolean("has_video")
                val offer  = event.raw.optString("offer_sdp", "")
                val groupIdHex = event.raw.optString("group_id").takeIf { it.isNotBlank() }
                val sessionIdHex = event.raw.optString("call_session_id").takeIf { it.isNotBlank() }
                val participants = buildList {
                    val rawParticipants = event.raw.optJSONArray("participants")
                    if (rawParticipants != null) {
                        for (index in 0 until rawParticipants.length()) {
                            rawParticipants.optString(index)
                                .takeIf { it.isNotBlank() }
                                ?.let(::add)
                        }
                    }
                }
                callManager.onIncomingCallEvent(callId, peer, video, offer, groupIdHex, sessionIdHex, participants)
                val dispName = callManager.incomingCall.value?.displayName ?: peer
                NotificationHelper.postIncomingCall(appContext, callId, dispName.ifBlank { peer }, video, appLock.notificationShowContent)
            }
            "IceCandidate" -> callManager.onIceCandidateEvent(
                event.raw.optString("call_id", ""),
                event.raw.optString("peer", ""),
                event.raw.optString("candidate", ""),
                event.raw.optString("sdp_mid", ""),
                event.raw.optInt("sdp_mline_index", 0),
            )
            "IceRestartOffer" -> callManager.onIceRestartOfferEvent(
                event.raw.optString("call_id", ""),
                event.raw.optString("peer", ""),
                event.raw.optString("offer_sdp", ""),
            )
            "IceRestartAnswer" -> callManager.onIceRestartAnswerEvent(
                event.raw.optString("call_id", ""),
                event.raw.optString("answer_sdp", ""),
            )
            "CallStateChanged" -> {
                callManager.onCallStateChangedEvent(event.raw)
                val stateStr = event.raw.optString("state", "")
                if (stateStr == "Ended" || stateStr == "Failed") refreshCallHistory()
            }
            "TransferEvent" -> {
                val evType     = event.raw.optString("event", "")
                val transferId = event.raw.optString("transfer_id", "")
                val fileName   = event.raw.optString("file_name", "")
                if (evType == "completed" && transferId.isNotBlank()) {
                    val safeName = fileName.ifBlank { "attachment_${transferId.take(8)}.bin" }
                    transferManager.onTransferCompleted(transferId, safeName) {
                        activeConvId?.let { refreshMessages(it) }
                    }
                } else if (evType == "failed") {
                    val reason = event.raw.optString("reason", "")
                    Log.w(TAG, "Transfer failed id=$transferId reason=$reason")
                }
                transferManager.refresh()
            }
            "BlobAvailable" -> {
                val fileId   = event.raw.optString("file_id", "")
                val fromPeer = event.raw.optString("from", "")
                val hashHex  = event.raw.optString("hash", "")
                val fileName = event.raw.optString("file_name", "")
                if (fileId.isNotBlank() && fromPeer.isNotBlank() && hashHex.length == 64) {
                    val safeName = fileName.ifBlank { "attachment_${fileId.take(8)}.bin" }
                    transferManager.onBlobAvailable(fileId, fromPeer, hashHex, safeName) {
                        activeConvId?.let { refreshMessages(it) }
                    }
                }
            }
            "PeerOnline" -> {
                // A peer came online via iroh — iroh is definitely working.
                if (!_irohUp.value) {
                    _irohUp.value = true
                    Log.d(TAG, "PeerOnline received while _irohUp=false → promoting to true")
                }
                val peerB64 = event.raw.optString("peer_id", "")
                if (peerB64.isNotBlank()) {
                    _onlinePeers.value = _onlinePeers.value + peerB64
                }
                refreshConversations()
                transferManager.retryPendingBlobs { activeConvId?.let { refreshMessages(it) } }
            }
            "PeerDiscovered" -> {
                refreshConversations()
            }
            "PeerOffline" -> {
                val peerB64 = event.raw.optString("peer_id", "")
                if (peerB64.isNotBlank()) {
                    _onlinePeers.value = _onlinePeers.value - peerB64
                }
                refreshConversations()
            }
            "SendViaLocalMesh" -> {
                val peerB64 = event.raw.optString("peer", "")
                val payloadB64 = event.raw.optString("payload", "")
                if (peerB64.isNotBlank() && payloadB64.isNotBlank()) {
                    try {
                        val bytes = android.util.Base64.decode(payloadB64, android.util.Base64.DEFAULT)
                        Log.d(TAG, "LocalMesh: request to send ${bytes.size} bytes to $peerB64")
                        
                        localMeshManager.sendBytes(peerB64, bytes)
                    } catch (e: Exception) {
                        Log.e(TAG, "LocalMesh: Failed to decode b64 payload", e)
                    }
                }
            }
            "GroupInviteReceived", "GroupJoined" -> { refreshConversations(); refreshGroups() }
            else -> { /* ignore */ }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  VOICE PLAYBACK
    // ══════════════════════════════════════════════════════════════════════

    fun playVoice(transferId: String, filePath: String) {
        stopVoice()
        _playingVoiceId.value = transferId
        _voiceProgress.value = 0f
        val mp = MediaPlayer()
        mediaPlayer = mp
        try {
            mp.setDataSource(filePath)
            mp.setOnCompletionListener { stopVoice() }
            mp.setOnErrorListener { _, _, _ -> stopVoice(); true }
            mp.prepare()
            _voiceDurationMs.value = mp.duration
            mp.start()
            // Track progress every 100ms
            progressJob = viewModelScope.launch {
                while (mp.isPlaying) {
                    _voiceProgress.value = if (mp.duration > 0) mp.currentPosition.toFloat() / mp.duration else 0f
                    kotlinx.coroutines.delay(100L)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "playVoice failed: ${e.message}")
            stopVoice()
        }
    }

    fun stopVoice() {
        progressJob?.cancel()
        progressJob = null
        mediaPlayer?.runCatching { stop(); release() }
        mediaPlayer = null
        _playingVoiceId.value = null
        _voiceProgress.value = 0f
        _voiceDurationMs.value = 0
    }

    // ══════════════════════════════════════════════════════════════════════
    //  APP LOCK & WIPE
    // ══════════════════════════════════════════════════════════════════════

    fun zeroPatternCells() {
        synchronized(cellsLock) {
            lastPatternCells?.fill(0)
            lastPatternCells = null
        }
    }

    private fun dropActiveSession(cleanMode: Boolean, initialized: Boolean) {
        eventPollingJob?.cancel()
        eventPollingJob = null
        AdaCoreHolder.isViewModelActive = false
        appContext.stopService(Intent(appContext, com.ada.messenger.service.AdaForegroundService::class.java))
        if (AdaCoreHolder.instance === core) AdaCoreHolder.instance = null
        core?.close()
        core = null
        reusedLiveCore = false
        activeConvId = null
        pendingDeepLinkCard = null
        pendingBridgeManifestImport = null
        _pendingNavConv.value = null
        _pendingNavCall.value = null
        _sendError.value = null
        _bridgeImportNotice.value = null
        _myPeerId.value = null
        _myDisplayName.value = null
        _initialized.value = initialized
        _isCleanMode.value = cleanMode
        _conversations.value = emptyList()
        _messages.value = emptyList()
        _groups.value = emptyList()
        _currentGroupInfo.value = null
        _callHistory.value = emptyList()
        _onlinePeers.value = emptySet()
        _peerTyping.value = false
        _bridgeStatus.value = null
        _bridgeLive.value = false
        _mailboxAvailable.value = false
        _bridgeRealtimeCalls.value = false
        _localMeshAvailable.value = false
        _lastTransportOutcome.value = null
        _irohUp.value = false
        transferManager.clearCache()
    }

    private fun resetConnectivityState() {
        _onlinePeers.value = emptySet()
        _peerTyping.value = false
        _bridgeStatus.value = null
        _bridgeLive.value = false
        _mailboxAvailable.value = false
        _bridgeRealtimeCalls.value = false
        _localMeshAvailable.value = false
        _lastTransportOutcome.value = null
        _irohUp.value = false
    }

    fun lockApp() {
        zeroPatternCells()
        stopVoice()
        resetConnectivityState()
        dropActiveSession(cleanMode = false, initialized = false)
    }

    fun enterCleanMode() {
        zeroPatternCells()
        stopVoice()
        resetConnectivityState()
        dropActiveSession(cleanMode = true, initialized = true)
    }

    fun executeKillCode() {
        Log.w(TAG, "Kill code triggered — wiping all data")
        zeroPatternCells()
        stopVoice()
        resetConnectivityState()
        dropActiveSession(cleanMode = false, initialized = false)
        viewModelScope.launch(Dispatchers.IO) {
            val dataDir = appContext.filesDir
            dataDir.listFiles()?.forEach { it.deleteRecursively() }
            val prefsDir = java.io.File(dataDir.parent, "shared_prefs")
            prefsDir.listFiles()?.forEach { it.delete() }
            withContext(Dispatchers.Main) {
                appContext.getSharedPreferences(AdaConfig.IDENTITY_PREFS, Context.MODE_PRIVATE)
                    .edit().clear().commit()
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  LIFECYCLE
    // ══════════════════════════════════════════════════════════════════════

    override fun onCleared() {
        super.onCleared()
        Log.i(TAG, "onCleared — stopping service and closing core")
        stopVoice()
        zeroPatternCells()
        resetConnectivityState()
        callManager.release()
        AdaCoreHolder.isViewModelActive = false
        appContext.stopService(Intent(appContext, com.ada.messenger.service.AdaForegroundService::class.java))
        if (AdaCoreHolder.instance === core) AdaCoreHolder.instance = null
        core?.close()
        core = null
        networkCallback?.let {
            runCatching { connectivityManager.unregisterNetworkCallback(it) }
            networkCallback = null
        }
    }

    // ── Factory ───────────────────────────────────────────────────────────

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AdaCoreViewModel(context.applicationContext) as T
    }
}
