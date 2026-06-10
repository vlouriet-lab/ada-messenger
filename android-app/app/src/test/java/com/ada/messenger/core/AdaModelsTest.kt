package com.ada.messenger.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [AdaModels] parsing functions.
 *
 * These tests verify that the JSON parsing layer correctly maps
 * Rust-produced JSON into Kotlin domain objects and gracefully
 * handles malformed / missing data.
 */
class AdaModelsTest {

    // ══════════════════════════════════════════════════════════════════════
    //  parseConversations
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `parseConversations — valid direct conversation`() {
        val json = JSONArray().apply {
            put(JSONObject().apply {
                put("id", "d:AAAA")
                put("display_name", "Alice")
                put("last_message", "Hello")
                put("last_activity_ms", 1700000000000L)
                put("unread_count", 3)
            })
        }.toString()

        val result = parseConversations(json)

        assertEquals(1, result.size)
        val conv = result[0]
        assertEquals("d:AAAA", conv.id)
        assertEquals("Alice", conv.displayName)
        assertEquals("Hello", conv.lastMessage)
        assertEquals(1700000000000L, conv.lastActivityMs)
        assertEquals(3, conv.unreadCount)
        assertTrue(conv.isDirect)
        assertFalse(conv.isGroup)
        assertEquals("AAAA", conv.peerIdB64)
        assertNull(conv.groupIdHex)
    }

    @Test
    fun `parseConversations — valid group conversation`() {
        val json = JSONArray().apply {
            put(JSONObject().apply {
                put("id", "g:deadbeef")
                put("display_name", "Team")
                put("last_message", "")
                put("last_activity_ms", 0L)
                put("unread_count", 0)
            })
        }.toString()

        val result = parseConversations(json)

        assertEquals(1, result.size)
        assertTrue(result[0].isGroup)
        assertEquals("deadbeef", result[0].groupIdHex)
        assertNull(result[0].peerIdB64)
    }

    @Test
    fun `parseConversations — missing optional fields use defaults`() {
        val json = JSONArray().apply {
            put(JSONObject().apply {
                put("id", "d:BBBB")
            })
        }.toString()

        val result = parseConversations(json)

        assertEquals(1, result.size)
        assertEquals("d:BBBB", result[0].displayName) // falls back to id
        assertEquals("", result[0].lastMessage)
        assertEquals(0L, result[0].lastActivityMs)
        assertEquals(0, result[0].unreadCount)
    }

    @Test
    fun `parseConversations — empty array`() {
        assertEquals(emptyList<ConversationItem>(), parseConversations("[]"))
    }

    @Test
    fun `parseConversations — malformed JSON returns empty`() {
        assertEquals(emptyList<ConversationItem>(), parseConversations("not json"))
    }

    // ══════════════════════════════════════════════════════════════════════
    //  parseMessages
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `parseMessages — text message`() {
        val json = JSONArray().apply {
            put(JSONObject().apply {
                put("id", "msg1")
                put("sender", "PEER1")
                put("sender_name", "Bob")
                put("text", "Hi!")
                put("timestamp_ms", 1700000000000L)
                put("is_mine", false)
                put("status", "delivered")
                put("kind", "text")
            })
        }.toString()

        val result = parseMessages(json)

        assertEquals(1, result.size)
        val msg = result[0]
        assertEquals("msg1", msg.id)
        assertEquals("PEER1", msg.sender)
        assertEquals("Bob", msg.senderName)
        assertEquals("Hi!", msg.text)
        assertFalse(msg.isMine)
        assertEquals("delivered", msg.status)
        assertEquals("text", msg.kind)
    }

    @Test
    fun `parseMessages — file message adds icon prefix`() {
        val json = JSONArray().apply {
            put(JSONObject().apply {
                put("id", "msg2")
                put("kind", "file")
                put("text", "photo.jpg")
            })
        }.toString()

        val result = parseMessages(json)
        assertEquals(1, result.size)
        assertTrue(result[0].text.startsWith("📎"))
    }

    @Test
    fun `parseMessages — call message shows localized text`() {
        val json = JSONArray().apply {
            put(JSONObject().apply {
                put("id", "msg3")
                put("kind", "call")
                put("is_mine", true)
            })
        }.toString()

        val result = parseMessages(json)
        assertEquals(1, result.size)
        assertTrue(result[0].text.contains("Исходящий звонок"))
    }

    @Test
    fun `parseMessages — edited text flag is preserved`() {
        val json = JSONArray().apply {
            put(JSONObject().apply {
                put("id", "msg3_edit")
                put("kind", "text")
                put("text", "Updated text")
                put("is_edited", true)
            })
        }.toString()

        val result = parseMessages(json)

        assertEquals(1, result.size)
        assertEquals("Updated text", result[0].text)
        assertTrue(result[0].isEdited)
    }

    @Test
    fun `parseMessages — group call announcement keeps session metadata`() {
        val json = JSONArray().apply {
            put(JSONObject().apply {
                put("id", "group_call_1")
                put("kind", "group_call")
                put("call_session_id", "deadbeefcafebabe")
                put("has_video", true)
            })
        }.toString()

        val result = parseMessages(json)

        assertEquals(1, result.size)
        assertEquals("group_call", result[0].kind)
        assertTrue(result[0].text.contains("видеозвонок"))
        assertEquals("deadbeefcafebabe", result[0].callSessionId)
        assertTrue(result[0].callHasVideo)
    }

    @Test
    fun `parseMessages — file_chunk is filtered out`() {
        val json = JSONArray().apply {
            put(JSONObject().apply {
                put("id", "msg4")
                put("kind", "file_chunk")
                put("text", "raw data")
            })
        }.toString()

        val result = parseMessages(json)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseMessages — delete_request is filtered out`() {
        val json = JSONArray().apply {
            put(JSONObject().apply {
                put("id", "msg5")
                put("kind", "delete_request")
            })
        }.toString()

        val result = parseMessages(json)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseMessages — malformed JSON returns empty`() {
        assertEquals(emptyList<ChatMessage>(), parseMessages("{invalid"))
    }

    // ══════════════════════════════════════════════════════════════════════
    //  parseGroupInfo
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `parseGroupInfo — complete group with members`() {
        val json = JSONObject().apply {
            put("id", "aabbccdd")
            put("name", "Dev Team")
            put("description", "Core devs")
            put("member_count", 3)
            put("topic", "v2.0 release")
            put("created_at", 1700000000000L)
            put("created_by", "CREATOR_B64")
            put("members", JSONArray().apply {
                put(JSONObject().apply {
                    put("peer_id", "P1"); put("display_name", "Alice"); put("role", "Owner")
                })
                put(JSONObject().apply {
                    put("peer_id", "P2"); put("display_name", "Bob"); put("role", "Member")
                })
            })
        }.toString()

        val result = parseGroupInfo(json)

        assertNotNull(result)
        assertEquals("aabbccdd", result!!.id)
        assertEquals("Dev Team", result.name)
        assertEquals("Core devs", result.description)
        assertEquals(3, result.memberCount)
        assertEquals("v2.0 release", result.topic)
        assertEquals(2, result.members.size)
        assertEquals("Owner", result.members[0].role)
    }

    @Test
    fun `parseGroupInfo — missing members defaults to empty`() {
        val json = JSONObject().apply {
            put("id", "aabb")
            put("name", "Group")
        }.toString()

        val result = parseGroupInfo(json)
        assertNotNull(result)
        assertTrue(result!!.members.isEmpty())
    }

    @Test
    fun `parseGroupInfo — malformed returns null`() {
        assertNull(parseGroupInfo("not json"))
    }

    // ══════════════════════════════════════════════════════════════════════
    //  parseGroups
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `parseGroups — multiple groups`() {
        val json = JSONArray().apply {
            put(JSONObject().apply { put("id", "g1"); put("name", "G1") })
            put(JSONObject().apply { put("id", "g2"); put("name", "G2") })
        }.toString()

        val result = parseGroups(json)
        assertEquals(2, result.size)
        assertEquals("G1", result[0].name)
        assertEquals("G2", result[1].name)
    }

    @Test
    fun `parseGroups — empty array`() {
        assertEquals(emptyList<GroupInfo>(), parseGroups("[]"))
    }

    // ══════════════════════════════════════════════════════════════════════
    //  parseEvent
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `parseEvent — valid event`() {
        val json = JSONObject().apply {
            put("type", "MessageReceived")
            put("sender", "ABCD")
            put("text", "hello")
        }.toString()

        val event = parseEvent(json)

        assertNotNull(event)
        assertEquals("MessageReceived", event!!.type)
        assertEquals("ABCD", event.raw.getString("sender"))
    }

    @Test
    fun `parseEvent — missing type returns null`() {
        assertNull(parseEvent("""{"sender": "x"}"""))
    }

    @Test
    fun `parseEvent — malformed returns null`() {
        assertNull(parseEvent("garbage"))
    }
}
