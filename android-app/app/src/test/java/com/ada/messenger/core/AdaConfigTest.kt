package com.ada.messenger.core

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [AdaConfig] constants.
 *
 * These tests act as a safety net: if someone accidentally changes a
 * config value, the test will fail and surface the change in CI review.
 */
class AdaConfigTest {

    @Test
    fun `notification IDs are distinct`() {
        val ids = setOf(
            AdaConfig.NOTIF_ID_FOREGROUND,
            AdaConfig.NOTIF_ID_MESSAGE,
            AdaConfig.NOTIF_ID_CALL,
        )
        assertEquals("All notification IDs must be unique", 3, ids.size)
    }

    @Test
    fun `channel IDs are distinct`() {
        val channels = setOf(
            AdaConfig.CHANNEL_P2P_ID,
            AdaConfig.CHANNEL_MSG_ID,
            AdaConfig.CHANNEL_CALL_ID,
            AdaConfig.CHANNEL_NOTIF_ID,
        )
        assertEquals("All channel IDs must be unique", 4, channels.size)
    }

    @Test
    fun `MAX_TEXT_LENGTH is a sane value`() {
        assertTrue("MAX_TEXT_LENGTH should be >= 1000", AdaConfig.MAX_TEXT_LENGTH >= 1000)
        assertTrue("MAX_TEXT_LENGTH should be <= 100_000", AdaConfig.MAX_TEXT_LENGTH <= 100_000)
    }

    @Test
    fun `MAX_FILE_SIZE equals 50 GB`() {
        assertEquals(50_000L * 1024 * 1024, AdaConfig.MAX_FILE_SIZE)
    }

    @Test
    fun `MAX_GROUP_SIZE is within signal-like limits`() {
        assertTrue(AdaConfig.MAX_GROUP_SIZE in 2..1024)
    }

    @Test
    fun `POLL_INTERVAL_ACTIVE is faster than IDLE`() {
        assertTrue(
            "Active poll must be faster (lower ms) than idle",
            AdaConfig.POLL_INTERVAL_ACTIVE_MS < AdaConfig.POLL_INTERVAL_IDLE_MS,
        )
    }

    @Test
    fun `RING_TIMEOUT is between 15s and 120s`() {
        assertTrue(AdaConfig.RING_TIMEOUT_MS in 15_000L..120_000L)
    }

    @Test
    fun `AVATAR_COUNT is positive`() {
        assertTrue(AdaConfig.AVATAR_COUNT > 0)
    }

    @Test
    fun `video call group size is less than or equal to max group size`() {
        assertTrue(AdaConfig.MAX_VIDEO_CALL_GROUP_SIZE <= AdaConfig.MAX_GROUP_SIZE)
    }
}
