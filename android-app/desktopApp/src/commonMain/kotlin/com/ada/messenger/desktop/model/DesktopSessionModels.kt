package com.ada.messenger.desktop.model

import com.ada.messenger.desktop.ui.theme.ThemeMode

enum class DesktopAuthMode {
    Register,
    PatternLogin,
    PinLogin,
    /** Desktop is displaying a QR for the phone to scan — waiting for snapshot. */
    WifiLinkWaiting,
    /** Snapshot received; user must set a pattern to protect the desktop database. */
    SnapshotPatternSetup,
}

data class DesktopConversationItem(
    val id: String,
    val displayName: String,
    val lastMessage: String,
    val lastActivityMs: Long,
    val unreadCount: Int,
) {
    val isDirect: Boolean get() = id.startsWith("d:")
    val isGroup: Boolean get() = id.startsWith("g:")
}

data class DesktopChatMessage(
    val id: String,
    val sender: String,
    val senderName: String,
    val text: String,
    val timestampMs: Long,
    val isMine: Boolean,
    val status: String,
    val kind: String,
    val mimeType: String = "",
    val fileId: String = "",
    val fileSize: Long = 0L,
)

data class DesktopCallLogEntry(
    val callId: String,
    val peerId: String,
    val displayName: String,
    val direction: String,
    val hasVideo: Boolean,
    val durationSecs: Long,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val reason: String,
)

data class DesktopCallAvailability(
    val available: Boolean,
    val reason: String?,
    val detail: String,
    val relayOnly: Boolean,
    val bridgeListenerConnected: Boolean,
)

data class DesktopActiveCall(
    val callId: String,
    val peerId: String,
    val displayName: String,
    val hasVideo: Boolean,
    val isOutgoing: Boolean,
    val state: String,
    val groupId: String? = null,
    val callSessionId: String? = null,
    val participants: List<String> = emptyList(),
)

data class DesktopIncomingCall(
    val callId: String,
    val peerId: String,
    val displayName: String,
    val hasVideo: Boolean,
    val offerSdp: String = "",
    val groupId: String? = null,
    val callSessionId: String? = null,
    val participants: List<String> = emptyList(),
)

data class DesktopBridgeNode(
    val address: String,
    val protocol: String,
    val reachable: Boolean,
)

data class DesktopBridgeCapabilities(
    val bridgeLiveDelivery: Boolean,
    val mailboxDelivery: Boolean,
    val realtimeCalls: Boolean,
    val largeAttachments: Boolean,
    val maxAttachmentBytes: Long,
)

data class DesktopBridgeStatus(
    val mode: String,
    val connectionProfile: String,
    val bridges: List<DesktopBridgeNode>,
    val hasWorking: Boolean,
    val relayOnly: Boolean,
    val irohReady: Boolean,
    val transportStack: String,
    val routeGranularity: String,
    val relayOnlyScope: String,
    val bridgeListenerConnected: Boolean,
    val bridgeListenerRoute: String?,
    val bridgeMailboxDepth: Int,
    val manifestSource: String?,
    val lastRoute: String?,
    val capabilities: DesktopBridgeCapabilities,
)

data class DesktopSessionUiState(
    val authMode: DesktopAuthMode = DesktopAuthMode.Register,
    val initialized: Boolean = false,
    val patternLoading: Boolean = false,
    val patternError: String? = null,
    val pinEnabled: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val storedDisplayName: String? = null,
    val displayName: String? = null,
    val peerId: String? = null,
    val dataDirPath: String = "",
    val logFilePath: String = "",
    val customBootstrapUrl: String = "",
    val customBootstrapPublicKey: String = "",
    val identityExportJson: String? = null,
    val conversations: List<DesktopConversationItem> = emptyList(),
    val activeConversationId: String? = null,
    val messages: List<DesktopChatMessage> = emptyList(),
    val contactCardJson: String? = null,
    val contactShareLink: String? = null,
    val bridgeStatus: DesktopBridgeStatus? = null,
    val censorshipLevel: String = "None",
    val runtimeMetricsJson: String = "{}",
    val callHistory: List<DesktopCallLogEntry> = emptyList(),
    val callAvailability: DesktopCallAvailability? = null,
    val activeCalls: List<DesktopActiveCall> = emptyList(),
    val incomingCall: DesktopIncomingCall? = null,
    val draft: String = "",
    val sendError: String? = null,
    val actionMessage: String? = null,
    val actionMessageIsError: Boolean = false,
    val downloadableFileIds: Set<String> = emptySet(),
    // ── Wi-Fi link (phone → desktop sync) ────────────────────────────────
    /** URL to display as QR code on the desktop for the phone to scan. */
    val wifiLinkUrl: String? = null,
    /** True while the Wi-Fi link server is running and waiting for a connection. */
    val wifiLinkActive: Boolean = false,
)