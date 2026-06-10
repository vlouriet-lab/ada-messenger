package com.ada.messenger.core

import android.util.Log

private const val TAG = "AdaCore"

/**
 * Kotlin wrapper around the Rust ada_core native library.
 * All native calls are synchronous (the Rust side uses a blocking Tokio runtime).
 * Heavy operations (create, send) should be called from a coroutine / worker thread.
 */
class AdaCore private constructor(private val handle: Long) {

    data class BridgeOperationResult(
        val success: Boolean,
        val error: String? = null,
    )

    companion object {
        init {
            System.loadLibrary("ada_core")
        }

        private const val TAG = "AdaCore"

        // ── Lifecycle ──────────────────────────────────────────────────────
        @JvmStatic private external fun nativeInitTracing(dataDir: String, isMobile: Boolean)
        
        fun initTracing(dataDir: String, isMobile: Boolean) {
            nativeInitTracing(dataDir, isMobile)
        }
        
        @JvmStatic private external fun nativeCreate(displayName: String, dataDir: String, connectionProfile: String): Long
        @JvmStatic private external fun nativeFree(handle: Long)
        fun create(
            displayName: String,
            dataDir: String,
            connectionProfile: String = AdaConfig.DEFAULT_CONNECTION_PROFILE,
        ): AdaCore? {
            Log.d(TAG, "create(displayName=${displayName.take(20)}, dataDir=$dataDir, profile=$connectionProfile)")
            val h = nativeCreate(displayName, dataDir, connectionProfile)
            return if (h == 0L) {
                Log.e(TAG, "nativeCreate returned 0 — Rust init failed")
                null
            } else {
                Log.i(TAG, "create OK handle=$h")
                AdaCore(h)
            }
        }

        // ── Pattern authentication ─────────────────────────────────────────
        /** Create a new identity derived deterministically from a 16-cell pattern. */
        @JvmStatic private external fun nativeCreateFromPattern(
            cells: ByteArray, displayName: String, dataDir: String, connectionProfile: String
        ): Long

        /**
         * Register a new ADA Core instance using a visual pattern password.
         *
         * @param cells   Exactly 32 bytes in canonical format: [idx0, color0, idx1, color1, …]
         *                sorted by cell index — mirrors PatternKey::to_password_bytes() on the Rust side.
         * @param name    Display name (nickname) for the identity.
         * @param dataDir Path to the app's private files directory.
         * @return        A ready `AdaCore` instance, or `null` on failure.
         */
        fun createFromPattern(
            cells: ByteArray,
            name: String,
            dataDir: String,
            connectionProfile: String = AdaConfig.DEFAULT_CONNECTION_PROFILE,
        ): AdaCore? {
            if (cells.size != 32) {
                Log.e(TAG, "createFromPattern: expected 32 bytes ([idx,color]×16), got ${cells.size}")
                return null
            }
            Log.d(TAG, "createFromPattern(name=${name.take(20)}, dataDir=$dataDir, profile=$connectionProfile)")
            val h = nativeCreateFromPattern(cells, name, dataDir, connectionProfile)
            return if (h == 0L) {
                Log.e(TAG, "nativeCreateFromPattern returned 0")
                null
            } else {
                Log.i(TAG, "createFromPattern OK handle=$h")
                AdaCore(h)
            }
        }
    }

    // ── Identity ───────────────────────────────────────────────────────────
    private external fun nativeGetPeerId(handle: Long): String?
    private external fun nativeGetDisplayName(handle: Long): String?

    fun getPeerId(): String? = withHandle { nativeGetPeerId(it) }
    fun getDisplayName(): String? = withHandle { nativeGetDisplayName(it) }

    /**
     * Export the running identity's secret key material as a JSON string.
     *
     * The returned JSON contains private key bytes and must be treated as a
     * secret. It is intended only for the Bluetooth desktop-link flow — never
     * store it to disk or log it.
     *
     * Must be called from a background thread (the Rust side blocks while
     * serialising the identity).
     *
     * @return JSON string on success, or null on failure.
     */
    private external fun nativeExportIdentityJson(handle: Long): String?

    fun exportIdentityJson(): String? = withHandle { nativeExportIdentityJson(it) }

    /**
     * Export a full device snapshot (identity + contacts + ratchet states + recent messages)
     * as a JSON string.  Send this to the desktop via the Wi-Fi link flow.
     * @return JSON string on success, or null on failure.
     */
    private external fun nativeExportSnapshot(handle: Long): String?

    fun exportSnapshot(): String? = withHandle { nativeExportSnapshot(it) }

    private external fun nativeGetLinkKeyHex(handle: Long): String?
    private external fun nativeStoreLinkSyncUrl(handle: Long, url: String): Int
    private external fun nativeGetLinkedDeviceSyncUrl(handle: Long): String?
    private external fun nativeSealSyncPushJson(handle: Long, msgJson: String): String?

    fun getLinkKeyHex(): String? = withHandle { nativeGetLinkKeyHex(it) }

    fun storeLinkSyncUrl(url: String): Boolean =
        withHandle { nativeStoreLinkSyncUrl(it, url) } == 0

    fun getLinkedDeviceSyncUrl(): String? = withHandle { nativeGetLinkedDeviceSyncUrl(it) }

    fun sealSyncPushJson(msgJson: String): String? =
        withHandle { nativeSealSyncPushJson(it, msgJson) }

    /**
     * Import snapshot data (contacts, ratchet states, messages) from a JSON string
     * into this running core.  Returns true on success.
     */
    private external fun nativeImportSnapshotData(handle: Long, snapshotJson: String): Int

    fun importSnapshotData(snapshotJson: String): Boolean =
        withHandle { nativeImportSnapshotData(it, snapshotJson) } == 0

    // ── Messaging ──────────────────────────────────────────────────────────
    /** Returns 0 on success. */
    private external fun nativeSendText(handle: Long, peerIdB64: String, text: String): Int
    private external fun nativeSendEphemeralText(handle: Long, peerIdB64: String, text: String, expiresInSecs: Int): Int
    private external fun nativeSendReply(handle: Long, peerIdB64: String, text: String, replyToMsgIdHex: String): Int
    private external fun nativeEditMessage(handle: Long, peerIdB64: String, targetMsgIdHex: String, newText: String): Int
    private external fun nativeSendReaction(handle: Long, peerIdB64: String, targetMsgIdHex: String, emoji: String): Int
    private external fun nativeGetConversationsJson(handle: Long): String?
    private external fun nativeSearchConversationsJson(handle: Long, query: String): String?
    private external fun nativeGetMessagesJson(handle: Long, convId: String, limit: Int): String?
    private external fun nativeMarkRead(handle: Long, convId: String): Int
    private external fun nativeDeleteMessage(handle: Long, msgIdHex: String): Int
    private external fun nativeDeleteConversation(handle: Long, convId: String): Int
    private external fun nativeClearConversationMessages(handle: Long, convId: String): Int
    private external fun nativeDeleteMessageForEveryone(handle: Long, peerIdB64: String, msgIdHex: String): Int

    fun sendText(peerIdB64: String, text: String, expiresInSecs: Int? = null): Boolean {
        Log.d(TAG, "sendText to=${peerIdB64.take(12)}, len=${text.length}, ttl=$expiresInSecs")
        val ok = if (expiresInSecs != null) {
            withHandle { nativeSendEphemeralText(it, peerIdB64, text, expiresInSecs) == 0 } ?: false
        } else {
            withHandle { nativeSendText(it, peerIdB64, text) == 0 } ?: false
        }
        if (!ok) Log.w(TAG, "sendText failed for peer=${peerIdB64.take(12)}")
        return ok
    }

    fun sendReply(peerIdB64: String, text: String, replyToMsgIdHex: String): Boolean {
        Log.d(TAG, "sendReply to=${peerIdB64.take(12)}, replyTo=${replyToMsgIdHex.take(12)}")
        val ok = withHandle { nativeSendReply(it, peerIdB64, text, replyToMsgIdHex) == 0 } ?: false
        if (!ok) Log.w(TAG, "sendReply failed for peer=${peerIdB64.take(12)}")
        return ok
    }

    fun editMessage(peerIdB64: String, targetMsgIdHex: String, newText: String): Boolean {
        Log.d(TAG, "editMessage to=${peerIdB64.take(12)}, target=${targetMsgIdHex.take(12)}, len=${newText.length}")
        val ok = withHandle { nativeEditMessage(it, peerIdB64, targetMsgIdHex, newText) == 0 } ?: false
        if (!ok) Log.w(TAG, "editMessage failed for peer=${peerIdB64.take(12)}")
        return ok
    }

    fun sendReaction(peerIdB64: String, targetMsgIdHex: String, emoji: String): Boolean {
        Log.d(TAG, "sendReaction to=${peerIdB64.take(12)}, target=${targetMsgIdHex.take(12)}, emoji=$emoji")
        val ok = withHandle { nativeSendReaction(it, peerIdB64, targetMsgIdHex, emoji) == 0 } ?: false
        if (!ok) Log.w(TAG, "sendReaction failed for peer=${peerIdB64.take(12)}")
        return ok
    }

    /** Raw JSON array string — parse in ViewModel with kotlinx.serialization or Gson. */
    fun getConversationsJson(): String = withHandle { nativeGetConversationsJson(it) } ?: "[]"

    fun searchConversationsJson(query: String): String =
        withHandle { nativeSearchConversationsJson(it, query) } ?: "[]"

    /** Raw JSON array string for messages in one conversation. */
    fun getMessagesJson(convId: String, limit: Int = 50): String =
        withHandle { nativeGetMessagesJson(it, convId, limit) } ?: "[]"

    fun markRead(convId: String) { withHandle { nativeMarkRead(it, convId) } }

    fun deleteMessage(msgIdHex: String): Boolean = withHandle { nativeDeleteMessage(it, msgIdHex) == 0 } ?: false

    fun deleteConversation(convId: String): Boolean = withHandle { nativeDeleteConversation(it, convId) == 0 } ?: false

    fun clearConversationMessages(convId: String): Boolean = withHandle { nativeClearConversationMessages(it, convId) == 0 } ?: false

    fun deleteMessageForEveryone(peerIdB64: String, msgIdHex: String): Boolean =
        withHandle { nativeDeleteMessageForEveryone(it, peerIdB64, msgIdHex) == 0 } ?: false

    // ── Event polling ──────────────────────────────────────────────────────
    /**
     * Returns one pending event as a JSON string, or null if the queue is empty.
     * Call repeatedly (e.g. from a coroutine loop) to drain all pending events.
     */
    private external fun nativePollEventJson(handle: Long, timeoutMs: Int): String?

    fun pollEventJson(timeoutMs: Int = 0): String? = withHandle { nativePollEventJson(it, timeoutMs) }
    
    // ── Mesh Integration ───────────────────────────────────────────────────
    private external fun nativeReceiveMeshBytes(handle: Long, peerB64: String, bytes: ByteArray): Int
    private external fun nativeMeshPeerDisconnected(handle: Long, peerB64: String): Int
    
    fun receiveMeshBytes(peerB64: String, bytes: ByteArray): Boolean =
        withHandle { nativeReceiveMeshBytes(it, peerB64, bytes) == 0 } ?: false

    fun meshPeerDisconnected(peerB64: String): Boolean =
        withHandle { nativeMeshPeerDisconnected(it, peerB64) == 0 } ?: false

    // ── Calls ──────────────────────────────────────────────────────────────
    /** Returns call-id hex string or null on error. */
    private external fun nativeCallAudio(handle: Long, peerIdB64: String, offerSdp: String): String?
    private external fun nativeCallInGroupRoom(handle: Long, peerIdB64: String, offerSdp: String, groupIdHex: String, sessionIdHex: String, hasVideo: Int): String?
    private external fun nativeHangup(handle: Long, callIdHex: String, peerIdB64: String): Int
    private external fun nativeDeclineCall(handle: Long, callIdHex: String, peerIdB64: String): Int
    private external fun nativeAnswerCall(handle: Long, callIdHex: String, peerIdB64: String, answerSdp: String): Int
    private external fun nativeCallVideo(handle: Long, peerIdB64: String, offerSdp: String): String?
    private external fun nativeGetActiveCallsJson(handle: Long): String?
    private external fun nativeGetCallAvailabilityJson(handle: Long): String?
    private external fun nativeGetCallHistoryJson(handle: Long, limit: Int): String?
    private external fun nativeHangupGroupCallRoom(handle: Long, sessionIdHex: String): Int
    private external fun nativeSendIceCandidate(
        handle: Long,
        callIdHex: String,
        peerIdB64: String,
        candidate: String,
        sdpMid: String,
        sdpMlineIndex: Int,
    ): Int

    fun callAudio(peerIdB64: String, offerSdp: String): String? = withHandle { nativeCallAudio(it, peerIdB64, offerSdp) }
    fun callInGroupRoom(peerIdB64: String, offerSdp: String, groupIdHex: String, sessionIdHex: String, hasVideo: Boolean): String? =
        withHandle {
            nativeCallInGroupRoom(
                it,
                peerIdB64,
                offerSdp,
                groupIdHex,
                sessionIdHex,
                if (hasVideo) 1 else 0,
            )
        }
    fun callVideo(peerIdB64: String, offerSdp: String): String? = withHandle { nativeCallVideo(it, peerIdB64, offerSdp) }
    fun answerCall(callIdHex: String, peerIdB64: String, answerSdp: String): Boolean =
        withHandle { nativeAnswerCall(it, callIdHex, peerIdB64, answerSdp) == 0 } ?: false
    fun hangup(callIdHex: String, peerIdB64: String): Boolean =
        withHandle { nativeHangup(it, callIdHex, peerIdB64) == 0 } ?: false
    fun hangupGroupCallRoom(sessionIdHex: String): Boolean =
        withHandle { nativeHangupGroupCallRoom(it, sessionIdHex) == 0 } ?: false
    fun declineCall(callIdHex: String, peerIdB64: String): Boolean =
        withHandle { nativeDeclineCall(it, callIdHex, peerIdB64) == 0 } ?: false
    fun getActiveCallsJson(): String = withHandle { nativeGetActiveCallsJson(it) } ?: "[]"
    fun getCallAvailabilityJson(): String = withHandle { nativeGetCallAvailabilityJson(it) } ?: "{\"available\":false}"
    fun getCallHistoryJson(limit: Int = 100): String =
        withHandle { nativeGetCallHistoryJson(it, limit) } ?: "[]"

    /**
     * Send a local ICE candidate (discovered by Android WebRTC) to the remote peer
     * via Rust signaling.  Returns true on success.
     */
    fun sendIceCandidate(
        callIdHex: String,
        peerIdB64: String,
        candidate: String,
        sdpMid: String = "",
        sdpMlineIndex: Int = 0,
    ): Boolean = withHandle { nativeSendIceCandidate(it, callIdHex, peerIdB64, candidate, sdpMid, sdpMlineIndex) == 0 } ?: false

    private external fun nativeSendIceRestartOffer(handle: Long, callIdHex: String, peerIdB64: String, offerSdp: String): Int
    private external fun nativeSendIceRestartAnswer(handle: Long, callIdHex: String, peerIdB64: String, answerSdp: String): Int

    fun sendIceRestartOffer(callIdHex: String, peerIdB64: String, offerSdp: String): Boolean =
        withHandle { nativeSendIceRestartOffer(it, callIdHex, peerIdB64, offerSdp) == 0 } ?: false
    fun sendIceRestartAnswer(callIdHex: String, peerIdB64: String, answerSdp: String): Boolean =
        withHandle { nativeSendIceRestartAnswer(it, callIdHex, peerIdB64, answerSdp) == 0 } ?: false

    // ── File Transfer ──────────────────────────────────────────────────────
    /** Returns hex transfer-id string or null on error. */
    private external fun nativeSendFileBytes(handle: Long, peerIdB64: String, fileName: String, mimeType: String, data: ByteArray): String?
    private external fun nativeSendFileFromPath(handle: Long, peerIdB64: String, fileName: String, mimeType: String, filePath: String): String?
    private external fun nativeGetTransfersJson(handle: Long): String?
    private external fun nativeCancelTransfer(handle: Long, transferIdHex: String): Int

    fun sendFileBytes(peerIdB64: String, fileName: String, mimeType: String, data: ByteArray): String? =
        withHandle { nativeSendFileBytes(it, peerIdB64, fileName, mimeType, data) }
    fun sendFileFromPath(peerIdB64: String, fileName: String, mimeType: String, filePath: String): String? =
        withHandle { nativeSendFileFromPath(it, peerIdB64, fileName, mimeType, filePath) }
    fun getTransfersJson(): String = withHandle { nativeGetTransfersJson(it) } ?: "[]"
    fun cancelTransfer(transferIdHex: String): Boolean =
        withHandle { nativeCancelTransfer(it, transferIdHex) == 0 } ?: false

    /**
     * Save a completed inbound transfer to [filePath] and return its metadata JSON.
     * The parent directory of [filePath] must exist before calling this.
     * @return JSON `{"file_name":"...","mime_type":"...","file_size":N}` or null on failure.
     */
    private external fun nativeSaveTransferToFile(handle: Long, transferIdHex: String, filePath: String): String?
    private external fun nativeFetchBlobToFile(handle: Long, fromPeerB64: String, hashHex: String, filePath: String): Boolean

    fun saveTransferToFile(transferIdHex: String, filePath: String): String? =
        withHandle { nativeSaveTransferToFile(it, transferIdHex, filePath) }

    fun fetchBlobToFile(fromPeerB64: String, hashHex: String, filePath: String): Boolean =
        withHandle { nativeFetchBlobToFile(it, fromPeerB64, hashHex, filePath) } ?: false

    // ── Bridge / Censorship ────────────────────────────────────────────────
    private external fun nativeAddBridge(handle: Long, bridgeLine: String): Int
    private external fun nativeTakeLastErrorMessage(handle: Long): String?
    private external fun nativeGetBridgeStatusJson(handle: Long): String?
    private external fun nativeGetMetricsSnapshot(handle: Long): String?
    private external fun nativeDetectCensorshipJson(handle: Long): String?
    private external fun nativeSetBridgeMode(handle: Long, modeStr: String): Int
    private external fun nativeSetRelayOnly(handle: Long, enabled: Int): Int
    private external fun nativeSetConnectionProfile(handle: Long, connectionProfile: String): Int
    private external fun nativeSetAppBackgroundState(handle: Long, inBackground: Int): Int
    private external fun nativeImportBridgeManifestJson(handle: Long, manifestJson: String, source: String, trustedPublicKeyHex: String): Int
    private external fun nativeImportBridgeManifestUrl(handle: Long, manifestUrl: String, trustedPublicKeyHex: String): Int

    fun addBridge(bridgeLine: String): Boolean = addBridgeDetailed(bridgeLine).success
    fun addBridgeDetailed(bridgeLine: String): BridgeOperationResult =
        withHandle { activeHandle ->
            val ok = nativeAddBridge(activeHandle, bridgeLine) == 0
            BridgeOperationResult(
                success = ok,
                error = if (ok) null else nativeTakeLastErrorMessage(activeHandle),
            )
        } ?: BridgeOperationResult(false)
    fun getBridgeStatusJson(): String = withHandle { nativeGetBridgeStatusJson(it) } ?: "{}"
    fun getMetricsSnapshot(): String = withHandle { nativeGetMetricsSnapshot(it) } ?: "{}"
    fun detectCensorshipJson(): String = withHandle { nativeDetectCensorshipJson(it) } ?: "{\"level\":\"None\"}"
    fun setBridgeMode(modeStr: String): Boolean =
        withHandle { nativeSetBridgeMode(it, modeStr) == 0 } ?: false
    fun setRelayOnly(enabled: Boolean): Boolean =
        withHandle { nativeSetRelayOnly(it, if (enabled) 1 else 0) == 0 } ?: false
    fun setConnectionProfile(connectionProfile: String): Boolean =
        withHandle { nativeSetConnectionProfile(it, connectionProfile) == 0 } ?: false
    fun setAppBackgroundState(inBackground: Boolean): Boolean =
        withHandle { nativeSetAppBackgroundState(it, if (inBackground) 1 else 0) == 0 } ?: false
    fun importBridgeManifestJson(manifestJson: String, source: String, trustedPublicKeyHex: String = ""): Boolean =
        importBridgeManifestJsonDetailed(manifestJson, source, trustedPublicKeyHex).success
    fun importBridgeManifestJsonDetailed(
        manifestJson: String,
        source: String,
        trustedPublicKeyHex: String = "",
    ): BridgeOperationResult = withHandle { activeHandle ->
        val ok = nativeImportBridgeManifestJson(activeHandle, manifestJson, source, trustedPublicKeyHex) == 0
        BridgeOperationResult(
            success = ok,
            error = if (ok) null else nativeTakeLastErrorMessage(activeHandle),
        )
    } ?: BridgeOperationResult(false)
    fun importBridgeManifestUrl(manifestUrl: String, trustedPublicKeyHex: String = ""): Boolean =
        importBridgeManifestUrlDetailed(manifestUrl, trustedPublicKeyHex).success
    fun importBridgeManifestUrlDetailed(
        manifestUrl: String,
        trustedPublicKeyHex: String = "",
    ): BridgeOperationResult = withHandle { activeHandle ->
        val ok = nativeImportBridgeManifestUrl(activeHandle, manifestUrl, trustedPublicKeyHex) == 0
        BridgeOperationResult(
            success = ok,
            error = if (ok) null else nativeTakeLastErrorMessage(activeHandle),
        )
    } ?: BridgeOperationResult(false)

    // ── Pattern authentication ─────────────────────────────────────────────
    private external fun nativeVerifyPattern(handle: Long, cells: ByteArray): Boolean
    private external fun nativeGetContactCardJson(handle: Long): String?

    /**
     * Verify that the given 16-cell pattern matches this identity's peer_id.
     * Use on a new device: re-enter the original pattern to regain access.
     */
    fun verifyPattern(cells: ByteArray): Boolean = withHandle { nativeVerifyPattern(it, cells) } ?: false

    /**
     * Returns a JSON contact card (public keys only — cannot reconstruct the pattern).
     * Use this payload to generate a QR code for contact exchange.
     */
    fun getContactCardJson(): String? = withHandle { nativeGetContactCardJson(it) }

    // ── Contact short-link ─────────────────────────────────────────────────

    /**
     * Encode [contactCardJson] into an opaque `ada://s/<token>` URL.
     * The token is XChaCha20-Poly1305 ciphertext — the peer ID and key
     * material are not visible in the resulting link (obfuscation).
     */
    private external fun nativeEncodeShortLink(json: String): String?

    /** Decode an `ada://s/<token>` URL back to a contact-card JSON string. */
    private external fun nativeDecodeShortLink(url: String): String?

    fun encodeContactShortLink(json: String): String? = nativeEncodeShortLink(json)
    fun decodeContactShortLink(url: String): String? = nativeDecodeShortLink(url)

    /**
     * Save a peer's public bundle from their QR contact card JSON.
     * Must be called before sending the first message to a new contact.
     * @return true on success
     */
    private external fun nativeAddContactJson(handle: Long, contactCardJson: String): Int

    fun addContactFromJson(contactCardJson: String): Boolean {
        Log.d(TAG, "addContactFromJson len=${contactCardJson.length}")
        val ok = withHandle { nativeAddContactJson(it, contactCardJson) == 0 } ?: false
        Log.i(TAG, "addContactFromJson result=$ok")
        return ok
    }

    /**
     * Dial a bootstrap or relay node at runtime.
     * Use this to connect to a custom relay server for cross-network messaging.
     * @param relayUrl iroh relay URL, e.g. "https://relay.example.com"
     * @return true on success
     */
    private external fun nativeAddRelayNode(handle: Long, relayUrl: String): Int

    fun addRelayNode(relayUrl: String): Boolean {
        Log.i(TAG, "addRelayNode: ${relayUrl.take(60)}")
        return withHandle { nativeAddRelayNode(it, relayUrl) == 0 } ?: false
    }

    // ── Incognito chats ────────────────────────────────────────────────────

    /**
     * Open an incognito chat with [peerIdB64] and return a v3 contact card JSON.
     *
     * The JSON contains an ephemeral X25519 IK unique to this conversation.
     * Display it as a QR code for the peer to scan.  Sessions started from this
     * card cannot be linked to the standard QR by a passive observer.
     *
     * @return JSON string, or null on error.
     */
    private external fun nativeCreateIncognitoChat(handle: Long, peerIdB64: String): String?

    fun createIncognitoChat(peerIdB64: String): String? {
        Log.d(TAG, "createIncognitoChat peer=${peerIdB64.take(12)}")
        return withHandle { nativeCreateIncognitoChat(it, peerIdB64) }
    }

    // ── Group chat ─────────────────────────────────────────────────────────
    private external fun nativeCreateGroup(handle: Long, groupName: String): String?
    private external fun nativeSendGroupText(handle: Long, groupIdHex: String, text: String): Int
    private external fun nativeSendGroupReply(handle: Long, groupIdHex: String, text: String, replyToMsgIdHex: String): Int
    private external fun nativeEditGroupMessage(handle: Long, groupIdHex: String, targetMsgIdHex: String, newText: String): Int
    private external fun nativeSendGroupReaction(handle: Long, groupIdHex: String, targetMsgIdHex: String, emoji: String): Int
    private external fun nativeInviteToGroup(handle: Long, groupIdHex: String, peerIdB64: String): Int
    private external fun nativeLeaveGroup(handle: Long, groupIdHex: String): Int
    private external fun nativeGetGroupsJson(handle: Long): String?
    private external fun nativeStartWebRtcProxy(handle: Long, peerB64: String): Int
    private external fun nativeStopWebRtcProxy(handle: Long, peerB64: String)

    fun startWebRtcProxy(peerB64: String): Int? = withHandle { h ->
        nativeStartWebRtcProxy(h, peerB64)
    }

    fun stopWebRtcProxy(peerB64: String) = withHandle { h ->
        nativeStopWebRtcProxy(h, peerB64)
    }

    private external fun nativeGetGroupInfoJson(handle: Long, groupIdHex: String): String?
    private external fun nativeStartGroupCall(handle: Long, groupIdHex: String, offerSdp: String, hasVideo: Int): String?
    private external fun nativeJoinGroupCall(handle: Long, groupIdHex: String, sessionIdHex: String, offerSdp: String, hasVideo: Int): String?
    private external fun nativeAttachCallToGroupRoom(handle: Long, callIdHex: String, groupIdHex: String, sessionIdHex: String, hasVideo: Int): Int

    fun createGroup(name: String): String? {
        Log.d(TAG, "createGroup name=${name.take(32)}")
        return withHandle { nativeCreateGroup(it, name) }
    }

    fun sendGroupText(groupIdHex: String, text: String): Boolean {
        Log.d(TAG, "sendGroupText group=${groupIdHex.take(16)}, len=${text.length}")
        return withHandle { nativeSendGroupText(it, groupIdHex, text) == 0 } ?: false
    }
    
    fun sendGroupReply(groupIdHex: String, text: String, replyToMsgIdHex: String): Boolean {
        Log.d(TAG, "sendGroupReply group=${groupIdHex.take(16)}, len=${text.length}")
        return withHandle { nativeSendGroupReply(it, groupIdHex, text, replyToMsgIdHex) == 0 } ?: false
    }

    fun editGroupMessage(groupIdHex: String, targetMsgIdHex: String, newText: String): Boolean {
        Log.d(TAG, "editGroupMessage group=${groupIdHex.take(16)}, target=${targetMsgIdHex.take(12)}, len=${newText.length}")
        return withHandle { nativeEditGroupMessage(it, groupIdHex, targetMsgIdHex, newText) == 0 } ?: false
    }

    fun sendGroupReaction(groupIdHex: String, targetMsgIdHex: String, emoji: String): Boolean {
        Log.d(TAG, "sendGroupReaction group=${groupIdHex.take(16)}, emoji=$emoji")
        return withHandle { nativeSendGroupReaction(it, groupIdHex, targetMsgIdHex, emoji) == 0 } ?: false
    }

    fun inviteToGroup(groupIdHex: String, peerIdB64: String): Boolean {
        Log.d(TAG, "inviteToGroup group=${groupIdHex.take(16)}, peer=${peerIdB64.take(12)}")
        return withHandle { nativeInviteToGroup(it, groupIdHex, peerIdB64) == 0 } ?: false
    }

    fun leaveGroup(groupIdHex: String): Boolean {
        Log.d(TAG, "leaveGroup group=${groupIdHex.take(16)}")
        return withHandle { nativeLeaveGroup(it, groupIdHex) == 0 } ?: false
    }

    fun getGroupsJson(): String = withHandle { nativeGetGroupsJson(it) } ?: "[]"

    fun getGroupInfoJson(groupIdHex: String): String? = withHandle { nativeGetGroupInfoJson(it, groupIdHex) }

    fun startGroupCall(groupIdHex: String, offerSdp: String, hasVideo: Boolean): String? {
        Log.d(TAG, "startGroupCall group=${groupIdHex.take(16)}, hasVideo=$hasVideo")
        return withHandle { nativeStartGroupCall(it, groupIdHex, offerSdp, if (hasVideo) 1 else 0) }
    }

    fun joinGroupCall(groupIdHex: String, sessionIdHex: String, offerSdp: String, hasVideo: Boolean): String? {
        Log.d(TAG, "joinGroupCall group=${groupIdHex.take(16)}, session=${sessionIdHex.take(16)}, hasVideo=$hasVideo")
        return withHandle {
            nativeJoinGroupCall(it, groupIdHex, sessionIdHex, offerSdp, if (hasVideo) 1 else 0)
        }
    }

    fun attachCallToGroupRoom(callIdHex: String, groupIdHex: String, sessionIdHex: String, hasVideo: Boolean): Boolean {
        return withHandle {
            nativeAttachCallToGroupRoom(it, callIdHex, groupIdHex, sessionIdHex, if (hasVideo) 1 else 0) == 0
        } ?: false
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    /**
     * Notify the Rust core that the Android network interface has been restored.
     * Call from [ConnectivityManager.NetworkCallback.onAvailable] to trigger an
     * immediate iroh pkarr republish instead of waiting for the backoff timer.
     */
    private external fun nativeNotifyNetworkAvailable(handle: Long)
    fun notifyNetworkAvailable() {
        withHandle { nativeNotifyNetworkAvailable(it) }
    }

    /**
     * Notify the Rust core that the current network interface has been lost.
     * Call from [ConnectivityManager.NetworkCallback.onLost] so iroh can
     * discard stale QUIC connections before the new interface comes up.
     */
    private external fun nativeNotifyNetworkLost(handle: Long)
    fun notifyNetworkLost() {
        withHandle { nativeNotifyNetworkLost(it) }
    }

    fun close() {
        if (freed.compareAndSet(false, true)) {
            val writeLock = handleLock.writeLock()
            writeLock.lock()
            try {
                Log.i(TAG, "close() — freeing handle=$handle")
                nativeFree(handle)
            } finally {
                writeLock.unlock()
            }
        } else {
            Log.w(TAG, "close() called but already freed — handle=$handle")
        }
    }

    /** Guard against double-free: Activity rotation, Service + ViewModel both calling close(). */
    private val freed = java.util.concurrent.atomic.AtomicBoolean(false)

    private val handleLock = java.util.concurrent.locks.ReentrantReadWriteLock()

    /**
     * Executes [block] with the native handle, or returns `null` if [close] was
     * already called. Uses activeCallCount to prevent close() from freeing the
     * handle while a JNI call is in progress.
     */
    private inline fun <T> withHandle(block: (Long) -> T): T? {
        val readLock = handleLock.readLock()
        readLock.lock()
        try {
            if (freed.get()) {
                Log.w(TAG, "withHandle: native call after close() — ignoring (handle=$handle)")
                return null
            }
            return block(handle)
        } finally {
            readLock.unlock()
        }
    }
}


