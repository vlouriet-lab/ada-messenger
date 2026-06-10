package com.ada.messenger.core

import org.json.JSONArray
import org.json.JSONObject

// ── Domain models ─────────────────────────────────────────────────────────────

data class ConversationItem(
    val id: String,           // "d:BASE64" or "g:HEX"
    val displayName: String,
    val lastMessage: String,
    val lastKind: String = "",
    val lastMimeType: String = "",
    val hasMessages: Boolean = lastMessage.isNotBlank(),
    val lastActivityMs: Long,
    val unreadCount: Int,
) {
    val isDirect: Boolean get() = id.startsWith("d:")
    val isGroup: Boolean get() = id.startsWith("g:")
    val peerIdB64: String? get() = if (isDirect) id.removePrefix("d:") else null
    val groupIdHex: String? get() = if (isGroup) id.removePrefix("g:") else null
}

data class ChatMessage(
    val id: String,
    val sender: String,       // base64 peer id
    val senderName: String,   // display name (for group chats)
    val text: String,
    val timestampMs: Long,
    val isMine: Boolean,
    val status: String,       // "sending"|"sent"|"delivered"|"read"|"failed"
    val kind: String,         // "text"|"file"|"call"|"group_invite"|...
    val mimeType: String = "",
    val fileId: String = "",
    val fileSize: Long = 0L,
    val localPath: String? = null,  // set after saving received file to cache
    val replyToId: String? = null,
    val replyToText: String? = null,
    val reactions: Map<String, Int> = emptyMap(),  // emoji -> count
    val myReactions: Set<String> = emptySet(),     // emojis this user has active
    val expiresInSecs: Int? = null,
    val isEdited: Boolean = false,
    val callSessionId: String? = null,
    val callHasVideo: Boolean = false,
)

data class GroupMemberInfo(
    val peerIdB64: String,
    val displayName: String,
    val role: String,          // "Owner"|"Admin"|"Member"
)

data class GroupInfo(
    val id: String,            // hex group id
    val name: String,
    val description: String,
    val memberCount: Int,
    val topic: String,
    val createdAt: Long,
    val createdByB64: String,
    val members: List<GroupMemberInfo>,
)

data class AdaEvent(
    val type: String,
    val raw: JSONObject,
)

// ── JSON parsing helpers ──────────────────────────────────────────────────────

fun parseConversations(json: String): List<ConversationItem> {
    return try {
        val arr = JSONArray(json)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            ConversationItem(
                id = o.getString("id"),
                displayName = o.optString("display_name", o.getString("id")),
                lastMessage = o.optString("last_message", ""),
                lastKind = o.optString("last_kind", ""),
                lastMimeType = o.optString("last_mime_type", ""),
                hasMessages = o.optBoolean(
                    "has_messages",
                    o.optString("last_message", "").isNotBlank() ||
                        o.optLong("last_activity_ms", 0L) > 0L ||
                        o.optInt("unread_count", 0) > 0,
                ),
                lastActivityMs = o.optLong("last_activity_ms", 0L),
                unreadCount = o.optInt("unread_count", 0),
            )
        }
    } catch (e: Exception) {
        emptyList()
    }
}

fun parseMessages(json: String): List<ChatMessage> {
    return try {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val kind = o.optString("kind", "text")
            // Skip purely protocol messages that have no display value
            if (kind == "file_chunk" || kind == "chunk_request" ||
                kind == "group_join_accept" || kind == "delete_request") return@mapNotNull null
            val displayText = when (kind) {
                "call" -> if (o.optBoolean("is_mine", false)) "📞 Исходящий звонок" else "📞 Входящий звонок"
                "group_call" -> if (o.optBoolean("has_video", false)) "📹 Групповой видеозвонок" else "📞 Групповой звонок"
                "file" -> "📎 " + o.optString("text", "Файл")
                "group_invite" -> "👥 Приглашение в группу: ${o.optString("text", "")}"
                else   -> o.optString("text", "")
            }
            ChatMessage(
                id = o.getString("id"),
                sender = o.optString("sender", ""),
                senderName = o.optString("sender_name", ""),
                text = displayText,
                timestampMs = o.optLong("timestamp_ms", 0L),
                isMine = o.optBoolean("is_mine", false),
                status = o.optString("status", "sent"),
                kind = kind,
                mimeType = o.optString("mime_type", ""),
                fileId = o.optString("file_id", ""),
                fileSize = o.optLong("file_size", 0L),
                // org.json returns the string "null" (not null) when the JSON value is
                // explicitly null — guard with isNull() so non-reply messages don't get
                // a replyToId of "null" and render an unwanted reply-quote bubble.
                replyToId = if (o.isNull("reply_to_id")) null else o.optString("reply_to_id", "").takeIf { it.isNotEmpty() },
                replyToText = if (o.isNull("reply_to_text")) null else o.optString("reply_to_text", "").takeIf { it.isNotEmpty() },
                reactions = run {
                    val rObj = o.optJSONObject("reactions")
                    if (rObj != null) {
                        val map = mutableMapOf<String, Int>()
                        rObj.keys().forEach { key -> map[key] = rObj.optInt(key, 0) }
                        map
                    } else emptyMap()
                },
                myReactions = run {
                    val myReactionsArray = o.optJSONArray("my_reactions")
                    if (myReactionsArray != null) {
                        (0 until myReactionsArray.length()).mapTo(mutableSetOf()) { myReactionsArray.getString(it) }
                    } else emptySet()
                },
                expiresInSecs = if (o.isNull("expires_in_secs")) null else o.optInt("expires_in_secs"),
                isEdited = o.optBoolean("is_edited", false),
                callSessionId = if (o.isNull("call_session_id")) null else o.optString("call_session_id", "").takeIf { it.isNotEmpty() },
                callHasVideo = o.optBoolean("has_video", false),
            )
        }
    } catch (e: Exception) {
        emptyList()
    }
}

fun parseGroupInfo(json: String): GroupInfo? {
    return try {
        val o = JSONObject(json)
        val membersArr = o.optJSONArray("members") ?: JSONArray()
        val members = (0 until membersArr.length()).map { i ->
            val m = membersArr.getJSONObject(i)
            GroupMemberInfo(
                peerIdB64 = m.getString("peer_id"),
                displayName = m.optString("display_name", ""),
                role = m.optString("role", "Member"),
            )
        }
        GroupInfo(
            id = o.getString("id"),
            name = o.optString("name", ""),
            description = o.optString("description", ""),
            memberCount = o.optInt("member_count", members.size),
            topic = o.optString("topic", ""),
            createdAt = o.optLong("created_at", 0L),
            createdByB64 = o.optString("created_by", ""),
            members = members,
        )
    } catch (e: Exception) {
        null
    }
}

fun parseGroups(json: String): List<GroupInfo> {
    return try {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            GroupInfo(
                id = o.getString("id"),
                name = o.optString("name", ""),
                description = o.optString("description", ""),
                memberCount = o.optInt("member_count", 0),
                topic = o.optString("topic", ""),
                createdAt = o.optLong("created_at", 0L),
                createdByB64 = o.optString("created_by", ""),
                members = emptyList(),
            )
        }
    } catch (e: Exception) {
        emptyList()
    }
}

fun parseEvent(json: String): AdaEvent? {
    return try {
        val o = JSONObject(json)
        AdaEvent(type = o.getString("type"), raw = o)
    } catch (e: Exception) {
        null
    }
}

// ── Call history ──────────────────────────────────────────────────────────────

data class CallLogEntry(
    val callId: String,
    val peerIdB64: String,
    val direction: String,     // "outgoing" | "incoming"
    val hasVideo: Boolean,
    val durationSecs: Long,
    val startedAt: Long,       // unix seconds
    val endedAt: Long,         // unix seconds
    val reason: String,        // "hung_up" | "rejected" | "timeout" | "missed"
    val displayName: String = "",
)

/**
 * Parse JSON returned by [AdaCore.getCallHistoryJson] and resolve display names
 * from the known [conversations] list.
 */
fun parseCallHistory(json: String, conversations: List<ConversationItem>): List<CallLogEntry> {
    val nameMap = conversations.associate { it.peerIdB64 to it.displayName }
    return try {
        val arr = JSONArray(json)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val peerId = o.optString("peer_id", "")
            CallLogEntry(
                callId     = o.optString("call_id", ""),
                peerIdB64  = peerId,
                direction  = o.optString("direction", "incoming"),
                hasVideo   = o.optInt("has_video", 0) != 0,
                durationSecs = o.optLong("duration_secs", 0L),
                startedAt  = o.optLong("started_at", 0L),
                endedAt    = o.optLong("ended_at", 0L),
                reason     = o.optString("reason", "hung_up"),
                displayName = nameMap[peerId] ?: peerId.take(8),
            )
        }
    } catch (e: Exception) {
        emptyList()
    }
}
