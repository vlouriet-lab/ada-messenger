package com.ada.messenger.desktop.core

import com.ada.messenger.desktop.model.DesktopBridgeCapabilities
import com.ada.messenger.desktop.model.DesktopBridgeNode
import com.ada.messenger.desktop.model.DesktopBridgeStatus
import com.ada.messenger.desktop.model.DesktopCallAvailability
import com.ada.messenger.desktop.model.DesktopActiveCall
import com.ada.messenger.desktop.model.DesktopChatMessage
import com.ada.messenger.desktop.model.DesktopCallLogEntry
import com.ada.messenger.desktop.model.DesktopConversationItem
import com.ada.messenger.desktop.model.DesktopIncomingCall
import org.json.JSONArray
import org.json.JSONObject

internal fun parseConversations(json: String): List<DesktopConversationItem> =
    runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).map { index ->
            val item = arr.getJSONObject(index)
            DesktopConversationItem(
                id = item.getString("id"),
                displayName = item.optString("display_name", item.getString("id")),
                lastMessage = item.optString("last_message", ""),
                lastActivityMs = item.optLong("last_activity_ms", 0L),
                unreadCount = item.optInt("unread_count", 0),
            )
        }
    }.getOrDefault(emptyList())

internal fun parseMessages(json: String): List<DesktopChatMessage> =
    runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { index ->
            val item = arr.getJSONObject(index)
            val kind = item.optString("kind", "text")
            if (
                kind == "file_chunk" ||
                kind == "chunk_request" ||
                kind == "group_join_accept" ||
                kind == "delete_request"
            ) {
                return@mapNotNull null
            }

            val renderedText = when (kind) {
                "call" -> if (item.optBoolean("is_mine", false)) "Исходящий звонок" else "Входящий звонок"
                "group_call" -> if (item.optBoolean("has_video", false)) "Групповой видеозвонок" else "Групповой звонок"
                "file" -> "Файл: " + item.optString("text", "")
                "blob_ref" -> "Файл: " + item.optString("text", "")
                "group_invite" -> "Приглашение в группу: " + item.optString("text", "")
                else -> item.optString("text", "")
            }

            DesktopChatMessage(
                id = item.getString("id"),
                sender = item.optString("sender", ""),
                senderName = item.optString("sender_name", ""),
                text = renderedText,
                timestampMs = item.optLong("timestamp_ms", 0L),
                isMine = item.optBoolean("is_mine", false),
                status = item.optString("status", "sent"),
                kind = kind,
                mimeType = item.optString("mime_type", ""),
                fileId = item.optString("file_id", ""),
                fileSize = item.optLong("file_size", 0L),
            )
        }
    }.getOrDefault(emptyList())

internal fun parseCallHistory(
    json: String,
    conversations: List<DesktopConversationItem>,
): List<DesktopCallLogEntry> {
    val nameMap = conversations
        .filter { it.isDirect }
        .associate { it.id.removePrefix("d:") to it.displayName }

    return runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).map { index ->
            val item = arr.getJSONObject(index)
            val peerId = item.optString("peer_id", "")
            DesktopCallLogEntry(
                callId = item.optString("call_id", ""),
                peerId = peerId,
                displayName = nameMap[peerId] ?: peerId.take(8),
                direction = item.optString("direction", "incoming"),
                hasVideo = item.optInt("has_video", 0) != 0,
                durationSecs = item.optLong("duration_secs", 0L),
                startedAtMs = item.optLong("started_at", 0L) * 1000L,
                endedAtMs = item.optLong("ended_at", 0L) * 1000L,
                reason = item.optString("reason", "hung_up"),
            )
        }
    }.getOrDefault(emptyList())
}

internal fun parseCallAvailability(json: String): DesktopCallAvailability? =
    runCatching {
        val obj = JSONObject(json)
        val available = obj.optBoolean("available", false)
        DesktopCallAvailability(
            available = available,
            reason = obj.optString("reason", "").ifBlank { null },
            detail = obj.optString(
                "detail",
                if (available) {
                    "Маршрут для realtime-звонков готов."
                } else {
                    "Realtime-звонки недоступны на текущем маршруте."
                },
            ),
            relayOnly = obj.optBoolean("relay_only"),
            bridgeListenerConnected = obj.optBoolean("bridge_listener_connected"),
        )
    }.getOrNull()

internal fun parseActiveCalls(
    json: String,
    conversations: List<DesktopConversationItem>,
): List<DesktopActiveCall> {
    val directNameMap = conversations
        .filter { it.isDirect }
        .associate { it.id.removePrefix("d:") to it.displayName }
    val groupNameMap = conversations
        .filter { it.isGroup }
        .associate { it.id.removePrefix("g:") to it.displayName }

    return runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).map { index ->
            val item = arr.getJSONObject(index)
            val peerId = item.optString("peer", "")
            val groupId = item.optString("group_id", "").ifBlank { null }
            DesktopActiveCall(
                callId = item.optString("call_id", ""),
                peerId = peerId,
                displayName = when {
                    groupId != null -> groupNameMap[groupId] ?: "Групповой звонок"
                    peerId.isNotBlank() -> directNameMap[peerId] ?: shortCallPeerId(peerId)
                    else -> "Звонок"
                },
                hasVideo = item.optBoolean("has_video", false),
                isOutgoing = item.optBoolean("outgoing", false),
                state = item.optString("state", "Unknown"),
                groupId = groupId,
                callSessionId = item.optString("call_session_id", "").ifBlank { null },
                participants = parseParticipants(item.optJSONArray("participants")),
            )
        }
    }.getOrDefault(emptyList())
}

internal fun parseIncomingCallEvent(
    json: String,
    conversations: List<DesktopConversationItem>,
    aliases: Map<String, String> = emptyMap(),
): DesktopIncomingCall? = runCatching {
    val obj = JSONObject(json)
    if (obj.optString("type") != "IncomingCall") {
        return@runCatching null
    }

    val directNameMap = conversations
        .filter { it.isDirect }
        .associate { it.id.removePrefix("d:") to it.displayName }
    val groupNameMap = conversations
        .filter { it.isGroup }
        .associate { it.id.removePrefix("g:") to it.displayName }
    val peerId = obj.optString("peer", "")
    val groupId = obj.optString("group_id", "").ifBlank { null }

    DesktopIncomingCall(
        callId = obj.optString("call_id", ""),
        peerId = peerId,
        displayName = when {
            groupId != null -> groupNameMap[groupId] ?: "Групповой звонок"
            peerId.isNotBlank() -> aliases[peerId] ?: directNameMap[peerId] ?: shortCallPeerId(peerId)
            else -> "Неизвестный контакт"
        },
        hasVideo = obj.optBoolean("has_video", false),
        offerSdp = obj.optString("offer_sdp", ""),
        groupId = groupId,
        callSessionId = obj.optString("call_session_id", "").ifBlank { null },
        participants = parseParticipants(obj.optJSONArray("participants")),
    )
}.getOrNull()

internal fun parseBridgeStatus(json: String): DesktopBridgeStatus? =
    runCatching {
        val obj = JSONObject(json)
        val bridgesArr = obj.optJSONArray("bridges") ?: JSONArray()
        val capabilitiesObj = obj.optJSONObject("capabilities") ?: JSONObject()
        val manifestObj = obj.optJSONObject("manifest")
        val lastOutcomeObj = obj.optJSONObject("last_outcome")

        DesktopBridgeStatus(
            mode = obj.optString("mode", "auto"),
            connectionProfile = normalizeConnectionProfile(
                obj.optString("connection_profile", DEFAULT_DESKTOP_CONNECTION_PROFILE),
            ),
            bridges = (0 until bridgesArr.length()).map { index ->
                val bridge = bridgesArr.getJSONObject(index)
                DesktopBridgeNode(
                    address = bridge.optString("address", "unknown"),
                    protocol = bridge.optString("protocol", ""),
                    reachable = bridge.optBoolean("reachable"),
                )
            },
            hasWorking = obj.optBoolean("has_working"),
            relayOnly = obj.optBoolean("relay_only"),
            irohReady = obj.optBoolean("iroh_ready"),
            transportStack = obj.optString("transport_stack", "unknown"),
            routeGranularity = obj.optString("route_granularity", "unknown"),
            relayOnlyScope = obj.optString("relay_only_scope", "disabled"),
            bridgeListenerConnected = obj.optBoolean("bridge_listener_connected"),
            bridgeListenerRoute = obj.optString("bridge_listener_route", "").ifBlank { null },
            bridgeMailboxDepth = obj.optInt("bridge_mailbox_depth", 0),
            manifestSource = manifestObj?.optString("source", "")?.ifBlank { null },
            lastRoute = lastOutcomeObj?.optString("route", "")?.ifBlank { null },
            capabilities = DesktopBridgeCapabilities(
                bridgeLiveDelivery = capabilitiesObj.optBoolean("bridge_live_delivery"),
                mailboxDelivery = capabilitiesObj.optBoolean("mailbox_delivery"),
                realtimeCalls = capabilitiesObj.optBoolean("realtime_calls"),
                largeAttachments = capabilitiesObj.optBoolean("large_attachments"),
                maxAttachmentBytes = capabilitiesObj.optLong("max_attachment_bytes", 0L),
            ),
        )
    }.getOrNull()

internal const val DEFAULT_DESKTOP_CONNECTION_PROFILE = "normal"

private fun parseParticipants(participants: JSONArray?): List<String> {
    if (participants == null) return emptyList()
    return buildList(participants.length()) {
        for (index in 0 until participants.length()) {
            val peerId = participants.optString(index)
            if (peerId.isNotBlank()) {
                add(peerId)
            }
        }
    }
}

private fun shortCallPeerId(peerId: String): String =
    peerId.take(8).ifBlank { "unknown" }

internal fun normalizeConnectionProfile(value: String?): String = when (value?.trim()?.lowercase()) {
    "auto" -> "auto"
    "normal" -> "normal"
    "mobile_saver", "battery_saver" -> "mobile_saver"
    "censored_light" -> "censored_light"
    "censored_heavy", "censored" -> "censored_heavy"
    "allowlist_only", "allowlist", "whitelist", "whitelist_only", "https_only" -> "allowlist_only"
    "incident_safe", "incident" -> "incident_safe"
    else -> DEFAULT_DESKTOP_CONNECTION_PROFILE
}