package com.ada.messenger.desktop.core

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

private const val DEFAULT_CONNECTION_PROFILE = "normal"

data class DesktopBridgeOperationResult(
    val success: Boolean,
    val error: String? = null,
)

data class DesktopCallStartResult(
    val callId: String? = null,
    val error: String? = null,
) {
    val success: Boolean get() = !callId.isNullOrBlank()
}

data class DesktopCallControlResult(
    val success: Boolean,
    val error: String? = null,
)

class DesktopAdaCore private constructor(private val handle: Long) {
    companion object {
        @JvmStatic
        private external fun nativeInitTracing(dataDir: String, isMobile: Boolean)

        @JvmStatic
        private external fun nativeEncodeShortLink(json: String): String?

        @JvmStatic
        private external fun nativeDecodeShortLink(url: String): String?

        @JvmStatic
        private external fun nativeCreateFromPattern(
            cells: ByteArray,
            displayName: String,
            dataDir: String,
            connectionProfile: String,
        ): Long

        @JvmStatic
        private external fun nativeCreateFromSnapshotWithPattern(
            cells: ByteArray,
            snapshotJson: String,
            dataDir: String,
            connectionProfile: String,
        ): Long

        @JvmStatic
        private external fun nativeFree(handle: Long)

        fun initTracing(dataDir: String) {
            DesktopAdaCoreLoader.ensureLoaded()
            nativeInitTracing(dataDir, false)
        }

        fun createFromPattern(
            cells: ByteArray,
            displayName: String,
            dataDir: String,
            connectionProfile: String = DEFAULT_CONNECTION_PROFILE,
        ): DesktopAdaCore? {
            require(cells.size == 32) { "expected 32 pattern bytes" }
            DesktopAdaCoreLoader.ensureLoaded()
            val coreHandle = nativeCreateFromPattern(cells, displayName, dataDir, connectionProfile)
            return if (coreHandle == 0L) null else DesktopAdaCore(coreHandle)
        }

        /**
         * Create a desktop core from a phone snapshot + user pattern.
         *
         * - The database is encrypted with a key derived from [cells] (same Argon2id as [createFromPattern]).
         * - The identity (peer_id) is taken from the snapshot, not derived from the pattern.
         * - All contacts, ratchet sessions, and recent messages are imported from the snapshot.
         * - Future [createFromPattern] logins will use the snapshot identity automatically.
         */
        fun createFromSnapshot(
            cells: ByteArray,
            snapshotJson: String,
            dataDir: String,
            connectionProfile: String = DEFAULT_CONNECTION_PROFILE,
        ): DesktopAdaCore? {
            require(cells.size == 32) { "expected 32 pattern bytes" }
            DesktopAdaCoreLoader.ensureLoaded()
            val handle = nativeCreateFromSnapshotWithPattern(cells, snapshotJson, dataDir, connectionProfile)
            return if (handle == 0L) null else DesktopAdaCore(handle)
        }

        fun encodeContactShortLink(json: String): String? {
            DesktopAdaCoreLoader.ensureLoaded()
            return nativeEncodeShortLink(json)
        }

        fun decodeContactShortLink(url: String): String? {
            DesktopAdaCoreLoader.ensureLoaded()
            return nativeDecodeShortLink(url)
        }
    }

    @Volatile
    private var closed = false

    private external fun nativeGetPeerId(handle: Long): String?
    private external fun nativeGetDisplayName(handle: Long): String?
    private external fun nativeExportIdentityJson(handle: Long): String?
    private external fun nativeExportSnapshot(handle: Long): String?
    private external fun nativeImportSnapshotData(handle: Long, snapshotJson: String): Int
    private external fun nativeGetLinkKeyHex(handle: Long): String?
    private external fun nativeHandleSyncPush(handle: Long, linkKeyHex: String, dataB64: String): Int
    private external fun nativeSendText(handle: Long, peerIdB64: String, text: String): Int
    private external fun nativeSendGroupText(handle: Long, groupIdHex: String, text: String): Int
    private external fun nativeGetConversationsJson(handle: Long): String?
    private external fun nativeSearchConversationsJson(handle: Long, query: String): String?
    private external fun nativeGetMessagesJson(handle: Long, convId: String, limit: Int): String?
    private external fun nativeMarkRead(handle: Long, convId: String): Int
    private external fun nativeDeleteMessage(handle: Long, msgIdHex: String): Int
    private external fun nativeDeleteMessageForEveryone(handle: Long, peerIdB64: String, msgIdHex: String): Int
    private external fun nativeCallAudio(handle: Long, peerIdB64: String, offerSdp: String): String?
    private external fun nativeCallVideo(handle: Long, peerIdB64: String, offerSdp: String): String?
    private external fun nativeAnswerCall(handle: Long, callIdHex: String, peerIdB64: String, answerSdp: String): Int
    private external fun nativeHangup(handle: Long, callIdHex: String, peerIdB64: String): Int
    private external fun nativeDeclineCall(handle: Long, callIdHex: String, peerIdB64: String): Int
    private external fun nativeHangupGroupCallRoom(handle: Long, sessionIdHex: String): Int
    private external fun nativeSendIceCandidate(
        handle: Long,
        callIdHex: String,
        peerIdB64: String,
        candidate: String,
        sdpMid: String,
        sdpMlineIndex: Int,
    ): Int
    private external fun nativeSendIceRestartOffer(handle: Long, callIdHex: String, peerIdB64: String, offerSdp: String): Int
    private external fun nativeSendIceRestartAnswer(handle: Long, callIdHex: String, peerIdB64: String, answerSdp: String): Int
    private external fun nativeStartWebRtcProxy(handle: Long, peerIdB64: String): Int
    private external fun nativeStopWebRtcProxy(handle: Long, peerIdB64: String)
    private external fun nativePollEventJson(handle: Long, timeoutMs: Int): String?
    private external fun nativeGetActiveCallsJson(handle: Long): String?
    private external fun nativeGetCallAvailabilityJson(handle: Long): String?
    private external fun nativeGetCallHistoryJson(handle: Long, limit: Int): String?
    private external fun nativeSendFileFromPath(
        handle: Long,
        peerIdB64: String,
        fileName: String,
        mimeType: String,
        filePath: String,
    ): String?
    private external fun nativeSaveTransferToFile(handle: Long, transferIdHex: String, filePath: String): String?
    private external fun nativeFetchBlobToFile(handle: Long, peerIdB64: String, hashHex: String, filePath: String): Boolean
    private external fun nativeAddBridge(handle: Long, bridgeLine: String): Int
    private external fun nativeTakeLastErrorMessage(handle: Long): String?
    private external fun nativeGetBridgeStatusJson(handle: Long): String?
    private external fun nativeGetMetricsSnapshot(handle: Long): String?
    private external fun nativeDetectCensorshipJson(handle: Long): String?
    private external fun nativeGetContactCardJson(handle: Long): String?
    private external fun nativeAddContactJson(handle: Long, contactCardJson: String): Int
    private external fun nativeAddRelayNode(handle: Long, relayUrl: String): Int
    private external fun nativeSetBridgeMode(handle: Long, modeStr: String): Int
    private external fun nativeSetRelayOnly(handle: Long, enabled: Int): Int
    private external fun nativeSetConnectionProfile(handle: Long, connectionProfile: String): Int
    private external fun nativeImportBridgeManifestJson(
        handle: Long,
        manifestJson: String,
        source: String,
        trustedPublicKeyHex: String,
    ): Int
    private external fun nativeImportBridgeManifestUrl(
        handle: Long,
        manifestUrl: String,
        trustedPublicKeyHex: String,
    ): Int

    fun getPeerId(): String? = withHandle { nativeGetPeerId(it) }

    fun getDisplayName(): String? = withHandle { nativeGetDisplayName(it) }

    fun exportIdentityJson(): String? = withHandle { nativeExportIdentityJson(it) }

    fun exportSnapshot(): String? = withHandle { nativeExportSnapshot(it) }

    fun importSnapshotData(snapshotJson: String): Boolean =
        withHandle { nativeImportSnapshotData(it, snapshotJson) } == 0

    fun getLinkKeyHex(): String? = withHandle { nativeGetLinkKeyHex(it) }

    fun handleSyncPush(linkKeyHex: String, dataB64: String): Boolean =
        withHandle { nativeHandleSyncPush(it, linkKeyHex, dataB64) } == 0

    fun sendText(peerIdB64: String, text: String): Boolean =
        withHandle { nativeSendText(it, peerIdB64, text) == 0 } ?: false

    fun sendGroupText(groupIdHex: String, text: String): Boolean =
        withHandle { nativeSendGroupText(it, groupIdHex, text) == 0 } ?: false

    fun getConversationsJson(): String = withHandle { nativeGetConversationsJson(it) } ?: "[]"

    fun searchConversationsJson(query: String): String =
        withHandle { nativeSearchConversationsJson(it, query) } ?: "[]"

    fun getMessagesJson(convId: String, limit: Int = 100): String =
        withHandle { nativeGetMessagesJson(it, convId, limit) } ?: "[]"

    fun markRead(convId: String) {
        withHandle { nativeMarkRead(it, convId) }
    }

    fun deleteMessage(msgIdHex: String): Boolean =
        withHandle { nativeDeleteMessage(it, msgIdHex) == 0 } ?: false

    fun deleteMessageForEveryone(peerIdB64: String, msgIdHex: String): Boolean =
        withHandle { nativeDeleteMessageForEveryone(it, peerIdB64, msgIdHex) == 0 } ?: false

    fun callAudioDetailed(peerIdB64: String, offerSdp: String): DesktopCallStartResult =
        withHandle { activeHandle ->
            val callId = nativeCallAudio(activeHandle, peerIdB64, offerSdp)
            DesktopCallStartResult(
                callId = callId,
                error = if (callId.isNullOrBlank()) nativeTakeLastErrorMessage(activeHandle) else null,
            )
        } ?: DesktopCallStartResult(error = "Desktop runtime недоступен.")

    fun callAudio(peerIdB64: String, offerSdp: String): String? =
        callAudioDetailed(peerIdB64, offerSdp).callId

    fun callVideoDetailed(peerIdB64: String, offerSdp: String): DesktopCallStartResult =
        withHandle { activeHandle ->
            val callId = nativeCallVideo(activeHandle, peerIdB64, offerSdp)
            DesktopCallStartResult(
                callId = callId,
                error = if (callId.isNullOrBlank()) nativeTakeLastErrorMessage(activeHandle) else null,
            )
        } ?: DesktopCallStartResult(error = "Desktop runtime недоступен.")

    fun callVideo(peerIdB64: String, offerSdp: String): String? =
        callVideoDetailed(peerIdB64, offerSdp).callId

    fun answerCallDetailed(callIdHex: String, peerIdB64: String, answerSdp: String): DesktopCallControlResult =
        withHandle { activeHandle ->
            val ok = nativeAnswerCall(activeHandle, callIdHex, peerIdB64, answerSdp) == 0
            DesktopCallControlResult(
                success = ok,
                error = if (ok) null else nativeTakeLastErrorMessage(activeHandle),
            )
        } ?: DesktopCallControlResult(success = false, error = "Desktop runtime недоступен.")

    fun answerCall(callIdHex: String, peerIdB64: String, answerSdp: String): Boolean =
        answerCallDetailed(callIdHex, peerIdB64, answerSdp).success

    fun hangupDetailed(callIdHex: String, peerIdB64: String): DesktopCallControlResult =
        withHandle { activeHandle ->
            val ok = nativeHangup(activeHandle, callIdHex, peerIdB64) == 0
            DesktopCallControlResult(
                success = ok,
                error = if (ok) null else nativeTakeLastErrorMessage(activeHandle),
            )
        } ?: DesktopCallControlResult(success = false, error = "Desktop runtime недоступен.")

    fun hangup(callIdHex: String, peerIdB64: String): Boolean =
        hangupDetailed(callIdHex, peerIdB64).success

    fun hangupGroupCallRoom(sessionIdHex: String): Boolean =
        withHandle { nativeHangupGroupCallRoom(it, sessionIdHex) == 0 } ?: false

    fun declineCallDetailed(callIdHex: String, peerIdB64: String): DesktopCallControlResult =
        withHandle { activeHandle ->
            val ok = nativeDeclineCall(activeHandle, callIdHex, peerIdB64) == 0
            DesktopCallControlResult(
                success = ok,
                error = if (ok) null else nativeTakeLastErrorMessage(activeHandle),
            )
        } ?: DesktopCallControlResult(success = false, error = "Desktop runtime недоступен.")

    fun declineCall(callIdHex: String, peerIdB64: String): Boolean =
        declineCallDetailed(callIdHex, peerIdB64).success

    fun sendIceCandidate(
        callIdHex: String,
        peerIdB64: String,
        candidate: String,
        sdpMid: String = "",
        sdpMlineIndex: Int = 0,
    ): Boolean = withHandle {
        nativeSendIceCandidate(it, callIdHex, peerIdB64, candidate, sdpMid, sdpMlineIndex) == 0
    } ?: false

    fun sendIceRestartOffer(callIdHex: String, peerIdB64: String, offerSdp: String): Boolean =
        withHandle { nativeSendIceRestartOffer(it, callIdHex, peerIdB64, offerSdp) == 0 } ?: false

    fun sendIceRestartAnswer(callIdHex: String, peerIdB64: String, answerSdp: String): Boolean =
        withHandle { nativeSendIceRestartAnswer(it, callIdHex, peerIdB64, answerSdp) == 0 } ?: false

    fun startWebRtcProxy(peerIdB64: String): Int? =
        withHandle { nativeStartWebRtcProxy(it, peerIdB64) }

    fun stopWebRtcProxy(peerIdB64: String) {
        withHandle { nativeStopWebRtcProxy(it, peerIdB64) }
    }

    fun pollEventJson(timeoutMs: Int = 0): String? = withHandle { nativePollEventJson(it, timeoutMs) }

    fun getActiveCallsJson(): String = withHandle { nativeGetActiveCallsJson(it) } ?: "[]"

    fun getCallAvailabilityJson(): String =
        withHandle { nativeGetCallAvailabilityJson(it) } ?: "{\"available\":false}"

    fun getCallHistoryJson(limit: Int = 100): String =
        withHandle { nativeGetCallHistoryJson(it, limit) } ?: "[]"

    fun sendFileFromPath(peerIdB64: String, fileName: String, mimeType: String, filePath: String): String? =
        withHandle { nativeSendFileFromPath(it, peerIdB64, fileName, mimeType, filePath) }

    /** Copies a completed inbound file transfer to [filePath]. Returns meta JSON on success, null on failure. */
    fun saveTransferToFile(transferIdHex: String, filePath: String): String? =
        withHandle { nativeSaveTransferToFile(it, transferIdHex, filePath) }

    /** Fetches a blob_ref from peer [peerIdB64] identified by [hashHex] (32-byte blake3 hex) and saves it to [filePath]. */
    fun fetchBlobToFile(peerIdB64: String, hashHex: String, filePath: String): Boolean =
        withHandle { nativeFetchBlobToFile(it, peerIdB64, hashHex, filePath) } ?: false

    fun addBridgeDetailed(bridgeLine: String): DesktopBridgeOperationResult =
        withHandle { activeHandle ->
            val ok = nativeAddBridge(activeHandle, bridgeLine) == 0
            DesktopBridgeOperationResult(
                success = ok,
                error = if (ok) null else nativeTakeLastErrorMessage(activeHandle),
            )
        } ?: DesktopBridgeOperationResult(false)

    fun getBridgeStatusJson(): String = withHandle { nativeGetBridgeStatusJson(it) } ?: "{}"

    fun getMetricsSnapshot(): String = withHandle { nativeGetMetricsSnapshot(it) } ?: "{}"

    fun detectCensorshipJson(): String =
        withHandle { nativeDetectCensorshipJson(it) } ?: "{\"level\":\"None\"}"

    fun getContactCardJson(): String? = withHandle { nativeGetContactCardJson(it) }

    fun addContactFromJson(contactCardJson: String): Boolean =
        withHandle { nativeAddContactJson(it, contactCardJson) == 0 } ?: false

    fun addRelayNode(relayUrl: String): Boolean =
        withHandle { nativeAddRelayNode(it, relayUrl) == 0 } ?: false

    fun setBridgeMode(modeStr: String): Boolean =
        withHandle { nativeSetBridgeMode(it, modeStr) == 0 } ?: false

    fun setRelayOnly(enabled: Boolean): Boolean =
        withHandle { nativeSetRelayOnly(it, if (enabled) 1 else 0) == 0 } ?: false

    fun setConnectionProfile(connectionProfile: String): Boolean =
        withHandle { nativeSetConnectionProfile(it, connectionProfile) == 0 } ?: false

    fun importBridgeManifestJsonDetailed(
        manifestJson: String,
        source: String,
        trustedPublicKeyHex: String = "",
    ): DesktopBridgeOperationResult = withHandle { activeHandle ->
        val ok = nativeImportBridgeManifestJson(activeHandle, manifestJson, source, trustedPublicKeyHex) == 0
        DesktopBridgeOperationResult(
            success = ok,
            error = if (ok) null else nativeTakeLastErrorMessage(activeHandle),
        )
    } ?: DesktopBridgeOperationResult(false)

    fun importBridgeManifestUrlDetailed(
        manifestUrl: String,
        trustedPublicKeyHex: String = "",
    ): DesktopBridgeOperationResult = withHandle { activeHandle ->
        val ok = nativeImportBridgeManifestUrl(activeHandle, manifestUrl, trustedPublicKeyHex) == 0
        DesktopBridgeOperationResult(
            success = ok,
            error = if (ok) null else nativeTakeLastErrorMessage(activeHandle),
        )
    } ?: DesktopBridgeOperationResult(false)

    fun close() {
        synchronized(this) {
            if (closed) return
            nativeFree(handle)
            closed = true
        }
    }

    private inline fun <T> withHandle(block: (Long) -> T): T? = synchronized(this) {
        if (closed) null else block(handle)
    }
}

private object DesktopAdaCoreLoader {
    @Volatile
    private var loaded = false

    @Synchronized
    fun ensureLoaded() {
        if (loaded) return

        var lastError: Throwable? = null
        for (candidate in candidateLibraries()) {
            if (!Files.exists(candidate)) continue
            try {
                System.load(candidate.toAbsolutePath().normalize().toString())
                loaded = true
                return
            } catch (error: Throwable) {
                lastError = error
            }
        }

        try {
            System.loadLibrary("ada_core")
            loaded = true
            return
        } catch (error: Throwable) {
            lastError = error
        }

        val searched = candidateLibraries()
            .map { it.toAbsolutePath().normalize().toString() }
            .distinct()
            .joinToString(separator = "; ")
        val suffix = lastError?.message?.takeIf { it.isNotBlank() }?.let { " Last error: $it" }.orEmpty()
        throw UnsatisfiedLinkError(
            "Native ada_core library not found. Build it with cargo build --manifest-path ada-core/Cargo.toml --no-default-features --features mobile-dev and either put the resulting library on java.library.path or set ADA_CORE_DESKTOP_LIB / ADA_CORE_LIB / -Dada.core.lib to its absolute path. Searched: $searched.$suffix",
        )
    }

    private fun candidateLibraries(): List<Path> {
        val explicit = sequenceOf(
            System.getProperty("ada.core.lib"),
            System.getenv("ADA_CORE_DESKTOP_LIB"),
            System.getenv("ADA_CORE_LIB"),
        )
            .filterNotNull()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .map(Paths::get)
            .toList()

        val names = when {
            isWindows() -> listOf("ada_core.dll")
            isMac() -> listOf("libada_core.dylib", "ada_core.dylib")
            else -> listOf("libada_core.so")
        }

        val searchRoots = mutableListOf<Path>()
        var current = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize()
        repeat(5) {
            searchRoots.add(current)
            current.parent?.let { parent -> current = parent }
        }

        val repoCandidates = searchRoots.flatMap { root ->
            listOf(
                root.resolve("ada-core").resolve("target").resolve("debug"),
                root.resolve("ada-core").resolve("target").resolve("release"),
                root.resolve("..").resolve("ada-core").resolve("target").resolve("debug"),
                root.resolve("..").resolve("ada-core").resolve("target").resolve("release"),
            ).flatMap { dir -> names.map(dir::resolve) }
        }

        return (explicit + repoCandidates)
            .map { it.normalize() }
            .distinct()
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name", "").lowercase().contains("win")

    private fun isMac(): Boolean =
        System.getProperty("os.name", "").lowercase().contains("mac")
}