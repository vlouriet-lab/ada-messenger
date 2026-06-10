package com.ada.messenger.core

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [ConversationItem] computed properties.
 */
class ConversationItemTest {

    @Test
    fun `direct conversation exposes peerIdB64`() {
        val conv = ConversationItem(
            id = "d:abc123",
            displayName = "Alice",
            lastMessage = "",
            lastActivityMs = 0L,
            unreadCount = 0,
        )
        assertTrue(conv.isDirect)
        assertFalse(conv.isGroup)
        assertEquals("abc123", conv.peerIdB64)
        assertNull(conv.groupIdHex)
    }

    @Test
    fun `group conversation exposes groupIdHex`() {
        val conv = ConversationItem(
            id = "g:deadbeef",
            displayName = "Team",
            lastMessage = "",
            lastActivityMs = 0L,
            unreadCount = 0,
        )
        assertFalse(conv.isDirect)
        assertTrue(conv.isGroup)
        assertNull(conv.peerIdB64)
        assertEquals("deadbeef", conv.groupIdHex)
    }

    @Test
    fun `unknown prefix — neither direct nor group`() {
        val conv = ConversationItem(
            id = "x:unknown",
            displayName = "?",
            lastMessage = "",
            lastActivityMs = 0L,
            unreadCount = 0,
        )
        assertFalse(conv.isDirect)
        assertFalse(conv.isGroup)
        assertNull(conv.peerIdB64)
        assertNull(conv.groupIdHex)
    }

    @Test
    fun `empty id`() {
        val conv = ConversationItem(
            id = "",
            displayName = "Empty",
            lastMessage = "",
            lastActivityMs = 0L,
            unreadCount = 0,
        )
        assertFalse(conv.isDirect)
        assertFalse(conv.isGroup)
    }
}
