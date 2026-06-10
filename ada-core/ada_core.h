#pragma once

#include <stdarg.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>

/**
 * Maximum allowed plaintext message size (64 MiB)
 */
#define MAX_MESSAGE_SIZE ((64 * 1024) * 1024)

/**
 * Chunk size used for file transfers (64 KiB)
 */
#define CHUNK_SIZE (64 * 1024)

#define BOARD_SIZE 8

#define BOARD_CELLS (BOARD_SIZE * BOARD_SIZE)

#define PATTERN_CUBES 16

/**
 * Number of available cube colours (0 = primary, 1 = secondary, 2 = tertiary).
 */
#define PATTERN_COLORS 3

/**
 * Byte length of the canonical password fed to Argon2id:
 * 2 bytes per cube (cell_idx, color_idx) × 16 cubes = 32 bytes.
 */
#define PATTERN_KEY_BYTES (PATTERN_CUBES * 2)

#define DEFAULT_REPUTATION 50

#define MIN_REPUTATION 0

#define MAX_REPUTATION 100

/**
 * Result code returned by FFI functions
 */
typedef enum AdaResult {
  Ok = 0,
  Error = 1,
  NotInitialized = 2,
  InvalidArg = 3,
  Timeout = 4,
} AdaResult;

/**
 * Opaque handle to an ADACore instance
 */
typedef struct AdaCoreHandle AdaCoreHandle;

/**
 * Callback type for receiving events
 */
typedef void (*AdaEventCallback)(int event_type, const char *payload_json, void *user_data);

/**
 * Create a new ADA Core instance
 *
 * # Safety
 * - `display_name` must be a valid null-terminated UTF-8 string
 * - Caller must free the handle with `ada_core_free`
 */
struct AdaCoreHandle *ada_core_create(const char *aDisplayName, const char *aDataDir);

/**
 * Free an ADA Core handle
 *
 * # Safety
 * `handle` must be a valid pointer from `ada_core_create`
 */
void ada_core_free(struct AdaCoreHandle *aHandle);

/**
 * Get the local peer ID as a null-terminated base64 string
 *
 * # Safety
 * - `handle` must be valid
 * - Caller must free returned string with `ada_string_free`
 */
char *ada_get_peer_id(const struct AdaCoreHandle *aHandle);

/**
 * Send a text message
 *
 * # Safety
 * All pointer arguments must be valid null-terminated UTF-8 strings
 */
enum AdaResult ada_send_text(const struct AdaCoreHandle *aHandle,
                             const char *aPeerIdB64,
                             const char *aText,
                             uint8_t *aOutMessageId);

/**
 * Free a string returned by ADA FFI functions
 *
 * # Safety
 * `s` must be a pointer returned by an ADA FFI function
 */
void ada_string_free(char *aS);

/**
 * Take and clear the last detailed error recorded on this handle.
 *
 * Returns null when there is no recorded error. The returned string must be
 * freed with `ada_string_free`.
 *
 * # Safety
 * `handle` must be a valid handle created by ADA Core, or null.
 */
char *ada_take_last_error_message(const struct AdaCoreHandle *aHandle);

/**
 * Register an event callback.
 *
 * The callback is invoked from a native worker thread, not from the UI thread.
 *
 * # Safety
 * - `callback` must remain valid for the lifetime of the handle
 * - `user_data` is passed through unchanged
 */
enum AdaResult ada_set_event_callback(struct AdaCoreHandle *aHandle,
                                      AdaEventCallback aCallback,
                                      void *aUserData);

/**
 * Clear a previously registered event callback.
 *
 * # Safety
 * `handle` must be a valid pointer from `ada_core_create`.
 */
enum AdaResult ada_clear_event_callback(struct AdaCoreHandle *aHandle);

/**
 * Start an audio call
 */
enum AdaResult ada_call_audio(const struct AdaCoreHandle *aHandle,
                              const char *aPeerIdB64,
                              const char *aOfferSdp,
                              uint8_t *aOutCallId);

/**
 * Hang up a call
 */
enum AdaResult ada_hangup(const struct AdaCoreHandle *aHandle,
                          const uint8_t *aCallId,
                          const char *aPeerIdB64);

/**
 * Decline (reject) an incoming call. Sends HangupReason::Declined to the peer.
 *
 * # Safety: `handle` must be valid; `call_id` points to 16 bytes; `peer_id_b64` is a valid C string.
 */
enum AdaResult ada_decline_call(const struct AdaCoreHandle *aHandle,
                                const uint8_t *aCallId,
                                const char *aPeerIdB64);

/**
 * Add a bridge for censorship circumvention
 */
enum AdaResult ada_add_bridge(struct AdaCoreHandle *aHandle, const char *aBridgeLine);

/**
 * Returns own display name as a null-terminated string.
 * Caller must free with `ada_string_free`.
 *
 * # Safety
 * `handle` must be a valid pointer.
 */
char *ada_get_display_name(const struct AdaCoreHandle *aHandle);

/**
 * Returns a JSON array of conversations sorted by last activity.
 * Schema: [{"id":"d:BASE64"|"g:HEX","display_name":"...","last_message":"...","last_activity_ms":N,"unread_count":N}]
 * Caller must free with `ada_string_free`.
 *
 * # Safety
 * `handle` must be a valid pointer.
 */
char *ada_get_conversations_json(const struct AdaCoreHandle *aHandle);

/**
 * Returns a JSON array of messages for a conversation.
 * `conv_id_str` is "d:BASE64" for direct or "g:HEX" for group.
 * `limit` is max number of messages (0 = use default 50).
 * Schema: [{"id":"HEX","sender":"BASE64","text":"...","timestamp_ms":N,"is_mine":true,"status":"sent","kind":"text"}]
 * Caller must free with `ada_string_free`.
 *
 * # Safety
 * All pointer arguments must be valid.
 */
char *ada_get_messages_json(const struct AdaCoreHandle *aHandle,
                            const char *aConvIdStr,
                            unsigned int aLimit);

/**
 * Poll one pending event as a JSON string.
 * Returns null if no events are pending.
 * Schema: {"type":"MessageReceived","data":{...}}
 * Caller must free with `ada_string_free`.
 *
 * # Safety
 * `handle` must be a valid pointer.
 */
char *ada_poll_event_json(const struct AdaCoreHandle *aHandle);

/**
 * Mark all messages in a conversation as read.
 *
 * # Safety
 * All pointer arguments must be valid.
 */
enum AdaResult ada_mark_read(const struct AdaCoreHandle *aHandle, const char *aConvIdStr);

/**
 * # Safety
 * All pointer arguments must be valid.
 */
enum AdaResult ada_delete_message(const struct AdaCoreHandle *aHandle, const char *aMsgIdHex);

/**
 * # Safety
 * All pointer arguments must be valid.
 */
enum AdaResult ada_delete_conversation(const struct AdaCoreHandle *aHandle, const char *aConvIdStr);

/**
 * # Safety
 * All pointer arguments must be valid.
 * Clears all messages in a conversation but keeps the conversation entry.
 */
enum AdaResult ada_clear_conversation_messages(const struct AdaCoreHandle *aHandle,
                                               const char *aConvIdStr);

/**
 * # Safety
 * All pointer arguments must be valid.
 * Deletes the message locally and sends a DeleteRequest to the peer.
 */
enum AdaResult ada_delete_message_for_everyone(const struct AdaCoreHandle *aHandle,
                                               const char *aPeerIdB64,
                                               const char *aMsgIdHex);

/**
 * Answer an incoming call and send the SDP answer to the peer via messaging.
 *
 * # Safety: `handle` valid, `call_id` points to 16 bytes, `peer_id_b64` valid C string.
 */
enum AdaResult ada_answer_call(const struct AdaCoreHandle *aHandle,
                               const uint8_t *aCallId,
                               const char *aPeerIdB64,
                               const char *aAnswerSdp);

/**
 * Initiate a video call. Returns call-id via `out_call_id` (16 bytes).
 *
 * # Safety: same as `ada_call_audio`.
 */
enum AdaResult ada_call_video(const struct AdaCoreHandle *aHandle,
                              const char *aPeerIdB64,
                              const char *aOfferSdp,
                              uint8_t *aOutCallId);

/**
 * Get all active calls as a JSON array.
 * Schema: [{"call_id":"HEX","peer":"BASE64","has_video":bool,"outgoing":bool,"state":"..."}]
 * Caller must free with `ada_string_free`.
 *
 * # Safety: `handle` must be valid.
 */
char *ada_get_active_calls_json(const struct AdaCoreHandle *aHandle);

/**
 * Send a local ICE candidate to the remote peer via encrypted signaling.
 *
 * # Safety
 * - `handle` must be a valid pointer obtained from `ada_create`.
 * - `peer_id_b64` must be a null-terminated UTF-8 string.
 * - `call_id` must point to exactly 16 bytes.
 * - `candidate`, `sdp_mid` must be null-terminated UTF-8 strings.
 *   `sdp_mid` may be null (treated as empty string).
 */
enum AdaResult ada_send_ice_candidate(const struct AdaCoreHandle *aHandle,
                                      const uint8_t *aCallId,
                                      const char *aPeerIdB64,
                                      const char *aCandidate,
                                      const char *aSdpMid,
                                      uint16_t aSdpMlineIndex);

/**
 * Send an ICE restart offer for an existing call (called when ICE fails on the offerer side).
 * # Safety: all pointers must be valid.
 */
enum AdaResult ada_send_ice_restart_offer(const struct AdaCoreHandle *aHandle,
                                          const uint8_t *aCallId,
                                          const char *aPeerIdB64,
                                          const char *aOfferSdp);

/**
 * Send an ICE restart answer (answerer responds to IceRestartOffer).
 * # Safety: all pointers must be valid.
 */
enum AdaResult ada_send_ice_restart_answer(const struct AdaCoreHandle *aHandle,
                                           const uint8_t *aCallId,
                                           const char *aPeerIdB64,
                                           const char *aAnswerSdp);

/**
 * Queue a file for sending to a peer.
 * `data` points to `data_len` bytes of raw file content.
 * `out_transfer_id` receives the 16-byte transfer ID.
 *
 * # Safety: all pointers must be valid; `data` must point to `data_len` readable bytes.
 */
enum AdaResult ada_send_file_bytes(const struct AdaCoreHandle *aHandle,
                                   const char *aPeerIdB64,
                                   const char *aFileName,
                                   const uint8_t *aData,
                                   unsigned int aDataLen,
                                   uint8_t *aOutTransferId);

/**
 * Get all active transfers as a JSON array.
 * Schema: [{"id":"HEX","peer":"BASE64","file_name":"...","file_size":N,"progress":0.0,
 *           "mime_type":"...","is_outbound":bool}]
 * Caller must free with `ada_string_free`.
 *
 * # Safety: `handle` must be valid.
 */
char *ada_get_transfers_json(const struct AdaCoreHandle *aHandle);

/**
 * Cancel an active transfer. `transfer_id` points to 16 bytes.
 *
 * # Safety: `handle` valid, `transfer_id` points to 16 readable bytes.
 */
enum AdaResult ada_cancel_transfer(const struct AdaCoreHandle *aHandle, const uint8_t *aTransferId);

/**
 * Get bridge list + current mode as JSON.
 * Schema: {"mode":"...","bridges":[...],"has_working":bool}
 * Caller must free with `ada_string_free`.
 *
 * # Safety: `handle` must be valid.
 */
char *ada_get_bridge_status_json(const struct AdaCoreHandle *aHandle);

/**
 * Probe the local network and return the detected censorship level as JSON.
 * Schema: {"level":"None"|"Light"|"Moderate"|"Heavy"|"Extreme"}
 * Caller must free with `ada_string_free`.
 *
 * # Safety: `handle` must be valid.
 */
char *ada_detect_censorship_json(const struct AdaCoreHandle *aHandle);

/**
 * Set the active obfuscation/bridge mode.
 * `mode_str` is one of: "none", "padding", "shaping", "websocket", "fronting", "auto".
 *
 * # Safety: `handle` valid, `mode_str` valid null-terminated UTF-8.
 */
enum AdaResult ada_set_bridge_mode(struct AdaCoreHandle *aHandle, const char *aModeStr);

/**
 * Create an ADA Core instance with an identity derived from a visual pattern.
 *
 * `cells` must point to exactly 16 bytes, each a cell index in 0..64.
 * On first registration `create_new` should be 1; set to 0 when loading an
 * existing identity on a new device (the peer_id will be compared later via
 * `ada_verify_pattern` before any sensitive use).
 *
 * # Safety
 * - `cells` must be a valid pointer to `cells_len` readable bytes
 * - `display_name`, `data_dir` must be valid null-terminated UTF-8 strings
 * - Caller must free the handle with `ada_core_free`
 */
struct AdaCoreHandle *ada_create_from_pattern(const uint8_t *aCells,
                                              uintptr_t aCellsLen,
                                              const char *aDisplayName,
                                              const char *aDataDir);

/**
 * Verify that a given pattern matches the peer_id stored in the handle.
 *
 * Returns 1 if the pattern is correct, 0 otherwise.
 *
 * # Safety
 * - `handle` must be a valid pointer from `ada_create_from_pattern` or `ada_core_create`
 * - `cells` must point to `cells_len` readable bytes
 */
int ada_verify_pattern(const struct AdaCoreHandle *aHandle,
                       const uint8_t *aCells,
                       uintptr_t aCellsLen);

/**
 * Save a peer's public bundle from the QR contact card JSON.
 *
 * Parses the JSON produced by `ada_get_contact_card_json` (v2 format) and stores the
 * `PublicBundle` so that subsequent `ada_send_text` / `ada_call_*` calls can perform X3DH.
 *
 * Returns `AdaResult::Ok` on success, `AdaResult::InvalidArg` if the JSON is malformed
 * or missing required fields, `AdaResult::Error` on storage failure.
 *
 * # Safety
 * `handle` must be a valid pointer; `contact_card_json` a valid null-terminated UTF-8 string.
 */
enum AdaResult ada_add_contact_json(const struct AdaCoreHandle *aHandle,
                                    const char *aContactCardJson);

/**
 * Get the contact card JSON payload for the local identity (used for QR code generation).
 *
 * Schema: `{"v":2,"id":"BASE64","spk":"BASE64","ik":"BASE64","spk_sig":"BASE64","name":"Nickname"}`
 *
 * Cannot be used to reconstruct the original pattern — public keys only.
 * Caller must free with `ada_string_free`.
 *
 * # Safety
 * `handle` must be a valid pointer.
 */
char *ada_get_contact_card_json(const struct AdaCoreHandle *aHandle);

/**
 * Dial a new bootstrap or relay node at runtime.
 *
 * `multiaddr` is a libp2p multiaddr string e.g.
 * `/dns4/relay.example.com/tcp/4001/p2p/12D3KooW…`
 *
 * Returns `AdaResult::Ok` on success.
 *
 * # Safety
 * `handle` and `multiaddr` must be valid pointers.
 */
enum AdaResult ada_add_bootstrap_node(const struct AdaCoreHandle *aHandle, const char *aMultiaddr);

#if defined(ADA_JNI)
/**
 * Called by the JVM when the native library is loaded (`System.loadLibrary`).
 * Initialises android_logger so all `tracing` / `log` records from Rust are
 * routed to logcat under the tag "ada_core".  Without this, every Rust log
 * is silently dropped on Android.
 *
 * # Safety
 * JNI contract: called once by the JVM on library load.
 */
jint JNI_OnLoad(JavaVM *aVm, void *aReserved);
#endif

#if defined(ADA_JNI)
jlong Java_com_ada_messenger_core_AdaCore_nativeCreate(JNIEnv aEnv,
                                                       JClass aClass,
                                                       JString aDisplayName,
                                                       JString aDataDir);
#endif

#if defined(ADA_JNI)
void Java_com_ada_messenger_core_AdaCore_nativeFree(JNIEnv aEnv, JClass aClass, jlong aHandle);
#endif

#if defined(ADA_JNI)
jstring Java_com_ada_messenger_core_AdaCore_nativeGetPeerId(JNIEnv aEnv,
                                                            JObject aObj,
                                                            jlong aHandle);
#endif

#if defined(ADA_JNI)
jstring Java_com_ada_messenger_core_AdaCore_nativeGetDisplayName(JNIEnv aEnv,
                                                                 JObject aObj,
                                                                 jlong aHandle);
#endif

#if defined(ADA_JNI)
jint Java_com_ada_messenger_core_AdaCore_nativeSendText(JNIEnv aEnv,
                                                        JObject aObj,
                                                        jlong aHandle,
                                                        JString aPeerIdB64,
                                                        JString aText);
#endif

#if defined(ADA_JNI)
/**
 * Returns JSON array of conversations. Caller receives a Kotlin String.
 */
jstring Java_com_ada_messenger_core_AdaCore_nativeGetConversationsJson(JNIEnv aEnv,
                                                                       JObject aObj,
                                                                       jlong aHandle);
#endif

#if defined(ADA_JNI)
/**
 * Returns JSON array of messages for a conversation.
 * `convId` format: "d:BASE64" or "g:HEX"
 */
jstring Java_com_ada_messenger_core_AdaCore_nativeGetMessagesJson(JNIEnv aEnv,
                                                                  JObject aObj,
                                                                  jlong aHandle,
                                                                  JString aConvId,
                                                                  jint aLimit);
#endif

#if defined(ADA_JNI)
/**
 * Poll one pending event as JSON. Returns null string if no events.
 */
jstring Java_com_ada_messenger_core_AdaCore_nativePollEventJson(JNIEnv aEnv,
                                                                JObject aObj,
                                                                jlong aHandle);
#endif

#if defined(ADA_JNI)
/**
 * Mark all messages in a conversation as read.
 */
jint Java_com_ada_messenger_core_AdaCore_nativeMarkRead(JNIEnv aEnv,
                                                        JObject aObj,
                                                        jlong aHandle,
                                                        JString aConvId);
#endif

#if defined(ADA_JNI)
/**
 * Delete a single message (local only). Returns 0 on success.
 */
jint Java_com_ada_messenger_core_AdaCore_nativeDeleteMessage(JNIEnv aEnv,
                                                             JObject aObj,
                                                             jlong aHandle,
                                                             JString aMsgIdHex);
#endif

#if defined(ADA_JNI)
/**
 * Delete an entire conversation (local only). Returns 0 on success.
 */
jint Java_com_ada_messenger_core_AdaCore_nativeDeleteConversation(JNIEnv aEnv,
                                                                  JObject aObj,
                                                                  jlong aHandle,
                                                                  JString aConvId);
#endif

#if defined(ADA_JNI)
/**
 * Clear all messages in a conversation, keeping the conversation entry. Returns 0 on success.
 */
jint Java_com_ada_messenger_core_AdaCore_nativeClearConversationMessages(JNIEnv aEnv,
                                                                         JObject aObj,
                                                                         jlong aHandle,
                                                                         JString aConvId);
#endif

#if defined(ADA_JNI)
/**
 * Delete a message locally and send a DeleteRequest to the peer. Returns 0 on success.
 */
jint Java_com_ada_messenger_core_AdaCore_nativeDeleteMessageForEveryone(JNIEnv aEnv,
                                                                        JObject aObj,
                                                                        jlong aHandle,
                                                                        JString aPeerIdB64,
                                                                        JString aMsgIdHex);
#endif

#if defined(ADA_JNI)
jstring Java_com_ada_messenger_core_AdaCore_nativeCallAudio(JNIEnv aEnv,
                                                            JObject aObj,
                                                            jlong aHandle,
                                                            JString aPeerIdB64,
                                                            JString aOfferSdp);
#endif

#if defined(ADA_JNI)
jint Java_com_ada_messenger_core_AdaCore_nativeHangup(JNIEnv aEnv,
                                                      JObject aObj,
                                                      jlong aHandle,
                                                      JString aCallIdHex,
                                                      JString aPeerIdB64);
#endif

#if defined(ADA_JNI)
/**
 * Decline (reject) an incoming call. Returns 0 on success.
 */
jint Java_com_ada_messenger_core_AdaCore_nativeDeclineCall(JNIEnv aEnv,
                                                           JObject aObj,
                                                           jlong aHandle,
                                                           JString aCallIdHex,
                                                           JString aPeerIdB64);
#endif

#if defined(ADA_JNI)
/**
 * Answer an incoming call. Returns 0 on success.
 */
jint Java_com_ada_messenger_core_AdaCore_nativeAnswerCall(JNIEnv aEnv,
                                                          JObject aObj,
                                                          jlong aHandle,
                                                          JString aCallIdHex,
                                                          JString aPeerIdB64,
                                                          JString aAnswerSdp);
#endif

#if defined(ADA_JNI)
/**
 * Initiate a video call. Returns hex call ID string or null on error.
 */
jstring Java_com_ada_messenger_core_AdaCore_nativeCallVideo(JNIEnv aEnv,
                                                            JObject aObj,
                                                            jlong aHandle,
                                                            JString aPeerIdB64,
                                                            JString aOfferSdp);
#endif

#if defined(ADA_JNI)
/**
 * Returns JSON array of active calls.
 */
jstring Java_com_ada_messenger_core_AdaCore_nativeGetActiveCallsJson(JNIEnv aEnv,
                                                                     JObject aObj,
                                                                     jlong aHandle);
#endif

#if defined(ADA_JNI)
/**
 * Send a local ICE candidate to the remote peer via Rust signaling.
 * Returns 0 on success, non-zero on error.
 */
jint Java_com_ada_messenger_core_AdaCore_nativeSendIceCandidate(JNIEnv aEnv,
                                                                JObject aObj,
                                                                jlong aHandle,
                                                                JString aCallIdHex,
                                                                JString aPeerIdB64,
                                                                JString aCandidate,
                                                                JString aSdpMid,
                                                                jint aSdpMlineIndex);
#endif

#if defined(ADA_JNI)
/**
 * Send an ICE restart offer for an existing call (called by the offerer on ICE failure).
 */
jint Java_com_ada_messenger_core_AdaCore_nativeSendIceRestartOffer(JNIEnv aEnv,
                                                                   JObject aObj,
                                                                   jlong aHandle,
                                                                   JString aCallIdHex,
                                                                   JString aPeerIdB64,
                                                                   JString aOfferSdp);
#endif

#if defined(ADA_JNI)
/**
 * Send an ICE restart answer (called by the answerer when it receives IceRestartOffer).
 */
jint Java_com_ada_messenger_core_AdaCore_nativeSendIceRestartAnswer(JNIEnv aEnv,
                                                                    JObject aObj,
                                                                    jlong aHandle,
                                                                    JString aCallIdHex,
                                                                    JString aPeerIdB64,
                                                                    JString aAnswerSdp);
#endif

#if defined(ADA_JNI)
/**
 * Send a file to a peer. `data` is a Kotlin ByteArray. Returns hex transfer-id or null.
 */
jstring Java_com_ada_messenger_core_AdaCore_nativeSendFileBytes(JNIEnv aEnv,
                                                                JObject aObj,
                                                                jlong aHandle,
                                                                JString aPeerIdB64,
                                                                JString aFileName,
                                                                JByteArray aData);
#endif

#if defined(ADA_JNI)
/**
 * Returns JSON array of active transfers.
 */
jstring Java_com_ada_messenger_core_AdaCore_nativeGetTransfersJson(JNIEnv aEnv,
                                                                   JObject aObj,
                                                                   jlong aHandle);
#endif

#if defined(ADA_JNI)
/**
 * Cancel an active transfer by hex transfer ID. Returns 0 on success.
 */
jint Java_com_ada_messenger_core_AdaCore_nativeCancelTransfer(JNIEnv aEnv,
                                                              JObject aObj,
                                                              jlong aHandle,
                                                              JString aTransferIdHex);
#endif

#if defined(ADA_JNI)
/**
 * Add a bridge using a bridge-line string. Returns 0 on success.
 */
jint Java_com_ada_messenger_core_AdaCore_nativeAddBridge(JNIEnv aEnv,
                                                         JObject aObj,
                                                         jlong aHandle,
                                                         JString aBridgeLine);
#endif

#if defined(ADA_JNI)
/**
 * Returns JSON with bridge list and current mode.
 */
jstring Java_com_ada_messenger_core_AdaCore_nativeGetBridgeStatusJson(JNIEnv aEnv,
                                                                      JObject aObj,
                                                                      jlong aHandle);
#endif

#if defined(ADA_JNI)
/**
 * Returns JSON `{"level":"None"|...}` based on a fast connectivity probe.
 */
jstring Java_com_ada_messenger_core_AdaCore_nativeDetectCensorshipJson(JNIEnv aEnv,
                                                                       JObject aObj,
                                                                       jlong aHandle);
#endif

#if defined(ADA_JNI)
/**
 * Set the active obfuscation mode: "none"|"padding"|"shaping"|"websocket"|"fronting"|"auto".
 */
jint Java_com_ada_messenger_core_AdaCore_nativeSetBridgeMode(JNIEnv aEnv,
                                                             JObject aObj,
                                                             jlong aHandle,
                                                             JString aModeStr);
#endif

#if defined(ADA_JNI)
/**
 * Create a new ADA Core instance from a visual pattern.
 * `cells` is a Kotlin ByteArray of 16 cell indices (0–63).
 * Returns a jlong handle, or 0 on failure.
 */
jlong Java_com_ada_messenger_core_AdaCore_nativeCreateFromPattern(JNIEnv aEnv,
                                                                  JClass aClass,
                                                                  JByteArray aCells,
                                                                  JString aDisplayName,
                                                                  JString aDataDir);
#endif

#if defined(ADA_JNI)
/**
 * Verify that a pattern matches the stored identity.
 * `cells` is a Kotlin ByteArray of 16 cell indices.
 * Returns JNI_TRUE (1) if match, JNI_FALSE (0) otherwise.
 */
jboolean Java_com_ada_messenger_core_AdaCore_nativeVerifyPattern(JNIEnv aEnv,
                                                                 JObject aObj,
                                                                 jlong aHandle,
                                                                 JByteArray aCells);
#endif

#if defined(ADA_JNI)
/**
 * Get the contact card JSON (for QR code).
 * Returns a Kotlin String or null on error.
 */
jstring Java_com_ada_messenger_core_AdaCore_nativeGetContactCardJson(JNIEnv aEnv,
                                                                     JObject aObj,
                                                                     jlong aHandle);
#endif

#if defined(ADA_JNI)
/**
 * Save a peer's public bundle from a QR contact card JSON string.
 * Returns 0 on success, 1 on error.
 */
jint Java_com_ada_messenger_core_AdaCore_nativeAddContactJson(JNIEnv aEnv,
                                                              JObject aObj,
                                                              jlong aHandle,
                                                              JString aContactCardJson);
#endif

#if defined(ADA_JNI)
/**
 * Dial a bootstrap or relay node at runtime.
 * `multiaddr` is a libp2p multiaddr string.
 * Returns 0 on success, 1 on error.
 */
jint Java_com_ada_messenger_core_AdaCore_nativeAddBootstrapNode(JNIEnv aEnv,
                                                                JObject aObj,
                                                                jlong aHandle,
                                                                JString aMultiaddr);
#endif
