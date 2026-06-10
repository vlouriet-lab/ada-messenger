package com.ada.messenger.core

/**
 * Centralized application constants.
 *
 * All tunable values, SharedPreferences keys, notification IDs, and limits
 * live here — no magic numbers scattered across files.
 */
object AdaConfig {

    // ── Identity persistence ─────────────────────────────────────────────
    const val IDENTITY_PREFS    = "ada_identity_v1"
    const val KEY_IDENTITY_TYPE = "identity_type"
    const val KEY_PEER_ID       = "peer_id"
    const val KEY_DISPLAY_NAME  = "display_name"
    const val KEY_AVATAR_INDEX  = "avatar_index"
    const val IDENTITY_TYPE_PATTERN = "pattern"

    // ── Worker / Service credentials ─────────────────────────────────────
    const val WORKER_PREFS      = "ada_worker_prefs_v2"
    const val PREF_DISPLAY_NAME = "display_name"
    const val PREF_DATA_DIR     = "data_dir"

    // ── Network profile ──────────────────────────────────────────────────
    const val PROFILE_PREFS = "ada_profile"
    const val KEY_CONNECTION_PROFILE = "connection_profile"
    const val DEFAULT_CONNECTION_PROFILE = "auto"

    // ── Optional user-provided bridge bootstrap ──────────────────────────
    const val BOOTSTRAP_PREFS = "ada_bridge_bootstrap"
    const val KEY_CUSTOM_MANIFEST_URL = "custom_manifest_url"
    const val KEY_CUSTOM_MANIFEST_PUBLIC_KEY = "custom_manifest_public_key"

    fun normalizeConnectionProfile(value: String?): String = when (value?.trim()?.lowercase()) {
        "normal" -> "normal"
        "mobile_saver", "battery_saver" -> "mobile_saver"
        "censored_light" -> "censored_light"
        "censored_heavy", "censored" -> "censored_heavy"
        "allowlist_only", "allowlist", "whitelist", "whitelist_only", "https_only" -> "allowlist_only"
        "incident_safe", "incident" -> "incident_safe"
        else -> DEFAULT_CONNECTION_PROFILE
    }

    // ── Avatar ───────────────────────────────────────────────────────────
    const val AVATAR_COUNT = 20

    // ── Notification channels ────────────────────────────────────────────
    const val CHANNEL_P2P_ID   = "ada_p2p"
    const val CHANNEL_MSG_ID   = "ada_msg_v2"
    const val CHANNEL_CALL_ID  = "ada_calls_v2"
    const val CHANNEL_NOTIF_ID = "ada_alerts"

    // ── Notification IDs ─────────────────────────────────────────────────
    const val NOTIF_ID_FOREGROUND = 1
    const val NOTIF_ID_MESSAGE    = 2
    const val NOTIF_ID_CALL       = 3

    // ── Messaging limits ─────────────────────────────────────────────────
    /** Max text length before Rust rejects (64 KB); UI enforces a lower limit. */
    const val MAX_TEXT_LENGTH = 60_000
    /** Max file size for attachments (1.5 GB limit to avoid JVM out of memory before streaming refactor). */
    const val MAX_FILE_SIZE = 50_000L * 1024 * 1024
    /** Max group size enforced by Rust sender-keys. */
    const val MAX_GROUP_SIZE = 16
    /** Max group size for video calls (bandwidth-limited). */
    const val MAX_VIDEO_CALL_GROUP_SIZE = 8

    // ── Timeouts ─────────────────────────────────────────────────────────
    /** Ring timeout for unanswered outgoing calls (ms). */
    const val RING_TIMEOUT_MS = 45_000L
    /** Connecting indicator auto-reset (ms). */
    const val CONNECTING_RESET_MS = 16_000L
    /** Event polling interval — active call (ms). */
    const val POLL_INTERVAL_ACTIVE_MS = 50L
    /** Event polling interval — idle (ms). */
    const val POLL_INTERVAL_IDLE_MS = 500L
    /** Max events drained per poll cycle (caps IO thread time). */
    const val MAX_EVENTS_PER_DRAIN = 50
}
