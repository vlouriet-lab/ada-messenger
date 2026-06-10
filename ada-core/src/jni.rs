use crate::ffi::{AdaCoreHandle, AdaResult};
use jni::objects::{JByteArray, JClass, JObject, JString};
use jni::sys::{jboolean, jint, jlong, jstring, JavaVM};
use jni::JNIEnv;
use std::ffi::{CStr, CString};

// ─── Android logging init ─────────────────────────────────────────────────────

#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_core_AdaCore_nativeInitTracing(
    mut env: JNIEnv,
    _class: JClass,
    data_dir: JString,
    is_mobile: jboolean,
) {
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        if let Ok(dir) = env.get_string(&data_dir) {
            let dir_str: String = dir.into();
            let mobile = is_mobile != 0;
            // This will setup tracing to write to a file in data_dir.
            crate::logging::init_tracing(&dir_str, mobile);
        }
    }))
    .map_err(|_| {
        log::error!("[jni] nativeInitTracing panicked");
    });
}

// Desktop Compose uses a different JVM class name than Android, but both wrappers
// need to land in the same native implementation.
#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_desktop_core_DesktopAdaCore_nativeInitTracing(
    env: JNIEnv,
    class: JClass,
    data_dir: JString,
    is_mobile: jboolean,
) {
    Java_com_ada_messenger_core_AdaCore_nativeInitTracing(env, class, data_dir, is_mobile)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_desktop_core_DesktopAdaCore_nativeCreateFromPattern(
    env: JNIEnv,
    class: JClass,
    cells: JByteArray,
    display_name: JString,
    data_dir: JString,
    connection_profile: JString,
) -> jlong {
    Java_com_ada_messenger_core_AdaCore_nativeCreateFromPattern(
        env,
        class,
        cells,
        display_name,
        data_dir,
        connection_profile,
    )
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_desktop_core_DesktopAdaCore_nativeFree(
    env: JNIEnv,
    class: JClass,
    handle: jlong,
) {
    Java_com_ada_messenger_core_AdaCore_nativeFree(env, class, handle)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_desktop_core_DesktopAdaCore_nativeGetPeerId(
    env: JNIEnv,
    obj: JObject,
    handle: jlong,
) -> jstring {
    Java_com_ada_messenger_core_AdaCore_nativeGetPeerId(env, obj, handle)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_desktop_core_DesktopAdaCore_nativeGetDisplayName(
    env: JNIEnv,
    obj: JObject,
    handle: jlong,
) -> jstring {
    Java_com_ada_messenger_core_AdaCore_nativeGetDisplayName(env, obj, handle)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_desktop_core_DesktopAdaCore_nativeExportIdentityJson(
    env: JNIEnv,
    obj: JObject,
    handle: jlong,
) -> jstring {
    Java_com_ada_messenger_core_AdaCore_nativeExportIdentityJson(env, obj, handle)
}

/// Export a full device snapshot from the desktop core (delegates to Android impl).
#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_desktop_core_DesktopAdaCore_nativeExportSnapshot(
    env: JNIEnv,
    obj: JObject,
    handle: jlong,
) -> jstring {
    Java_com_ada_messenger_core_AdaCore_nativeExportSnapshot(env, obj, handle)
}

/// Import snapshot data (contacts, ratchets, messages) into the running desktop core.
#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_desktop_core_DesktopAdaCore_nativeImportSnapshotData(
    env: JNIEnv,
    obj: JObject,
    handle: jlong,
    snapshot_json: JString,
) -> jint {
    Java_com_ada_messenger_core_AdaCore_nativeImportSnapshotData(env, obj, handle, snapshot_json)
}

/// Create a new desktop core from a phone snapshot + pattern.
/// Returns a non-zero handle on success, 0 on failure.
/// The returned handle must be freed with `nativeFreeHandle`.
#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_desktop_core_DesktopAdaCore_nativeCreateFromSnapshotWithPattern(
    mut env: JNIEnv,
    _class: JClass,
    cells: JByteArray,
    snapshot_json: JString,
    data_dir: JString,
    connection_profile: JString,
) -> jlong {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        // --- cells → raw bytes ---
        let cells_len = env.get_array_length(&cells).unwrap_or(0) as usize;
        if cells_len != crate::pattern_auth::PATTERN_KEY_BYTES {
            tracing::error!(
                "[jni] nativeCreateFromSnapshotWithPattern: bad cells_len={}",
                cells_len
            );
            return 0i64;
        }
        let mut cells_buf = vec![0i8; cells_len];
        if env
            .get_byte_array_region(&cells, 0, &mut cells_buf)
            .is_err()
        {
            return 0i64;
        }
        let cells_u8: Vec<u8> = cells_buf.iter().map(|b| *b as u8).collect();

        // --- snapshot_json ---
        let json_str: String = match env.get_string(&snapshot_json) {
            Ok(s) => s.into(),
            Err(_) => return 0i64,
        };
        // --- data_dir ---
        let dir_str: String = match env.get_string(&data_dir) {
            Ok(s) => s.into(),
            Err(_) => return 0i64,
        };
        // --- connection_profile ---
        let profile_str: String = match env.get_string(&connection_profile) {
            Ok(s) => s.into(),
            Err(_) => return 0i64,
        };

        // Convert to C strings for the FFI layer
        let c_json = match CString::new(json_str) {
            Ok(c) => c,
            Err(_) => return 0i64,
        };
        let c_dir = match CString::new(dir_str) {
            Ok(c) => c,
            Err(_) => return 0i64,
        };
        let c_profile = match CString::new(profile_str) {
            Ok(c) => c,
            Err(_) => return 0i64,
        };

        let handle_ptr = crate::ffi::ada_create_from_snapshot_with_pattern(
            cells_u8.as_ptr(),
            cells_u8.len(),
            c_json.as_ptr(),
            c_dir.as_ptr(),
            c_profile.as_ptr(),
        );

        handle_ptr as jlong
    }))
    .unwrap_or_else(|_| {
        tracing::error!("[jni] nativeCreateFromSnapshotWithPattern panicked");
        0i64
    })
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_desktop_core_DesktopAdaCore_nativeSendText(
    env: JNIEnv,
    obj: JObject,
    handle: jlong,
    peer_id_b64: JString,
    text: JString,
) -> jint {
    Java_com_ada_messenger_core_AdaCore_nativeSendText(env, obj, handle, peer_id_b64, text)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_desktop_core_DesktopAdaCore_nativeSendGroupText(
    env: JNIEnv,
    obj: JObject,
    handle: jlong,
    group_id_hex: JString,
    text: JString,
) -> jint {
    Java_com_ada_messenger_core_AdaCore_nativeSendGroupText(
        env,
        obj,
        handle,
        group_id_hex,
        text,
    )
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_desktop_core_DesktopAdaCore_nativeGetConversationsJson(
    env: JNIEnv,
    obj: JObject,
    handle: jlong,
) -> jstring {
    Java_com_ada_messenger_core_AdaCore_nativeGetConversationsJson(env, obj, handle)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_desktop_core_DesktopAdaCore_nativeSearchConversationsJson(
    env: JNIEnv,
    obj: JObject,
    handle: jlong,
    query: JString,
) -> jstring {
    Java_com_ada_messenger_core_AdaCore_nativeSearchConversationsJson(env, obj, handle, query)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_desktop_core_DesktopAdaCore_nativeGetMessagesJson(
    env: JNIEnv,
    obj: JObject,
    handle: jlong,
    conv_id: JString,
    limit: jint,
) -> jstring {
    Java_com_ada_messenger_core_AdaCore_nativeGetMessagesJson(env, obj, handle, conv_id, limit)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_desktop_core_DesktopAdaCore_nativeMarkRead(
    env: JNIEnv,
    obj: JObject,
    handle: jlong,
    conv_id: JString,
) -> jint {
    Java_com_ada_messenger_core_AdaCore_nativeMarkRead(env, obj, handle, conv_id)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_desktop_core_DesktopAdaCore_nativeDeleteMessage(
    env: JNIEnv,
    obj: JObject,
    handle: jlong,
    msg_id_hex: JString,
) -> jint {
    Java_com_ada_messenger_core_AdaCore_nativeDeleteMessage(env, obj, handle, msg_id_hex)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_desktop_core_DesktopAdaCore_nativeDeleteMessageForEveryone(
    env: JNIEnv,
    obj: JObject,
    handle: jlong,
    peer_id_b64: JString,
    msg_id_hex: JString,
) -> jint {
    Java_com_ada_messenger_core_AdaCore_nativeDeleteMessageForEveryone(
        env,
        obj,
        handle,
        peer_id_b64,
        msg_id_hex,
    )
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_desktop_core_DesktopAdaCore_nativeCallAudio(
    env: JNIEnv,
    obj: JObject,
    handle: jlong,
    peer_id_b64: JString,
    offer_sdp: JString,
) -> jstring {
    Java_com_ada_messenger_core_AdaCore_nativeCallAudio(env, obj, handle, peer_id_b64, offer_sdp)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_desktop_core_DesktopAdaCore_nativeCallVideo(
    env: JNIEnv,
    obj: JObject,
    handle: jlong,
    peer_id_b64: JString,
    offer_sdp: JString,
) -> jstring {
    Java_com_ada_messenger_core_AdaCore_nativeCallVideo(env, obj, handle, peer_id_b64, offer_sdp)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_desktop_core_DesktopAdaCore_nativeAnswerCall(
    env: JNIEnv,
    obj: JObject,
    handle: jlong,
    call_id_hex: JString,
    peer_id_b64: JString,
    answer_sdp: JString,
) -> jint {
    Java_com_ada_messenger_core_AdaCore_nativeAnswerCall(
        env,
        obj,
        handle,
        call_id_hex,
        peer_id_b64,
        answer_sdp,
    )
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_desktop_core_DesktopAdaCore_nativeHangup(
    env: JNIEnv,
    obj: JObject,
    handle: jlong,
    call_id_hex: JString,
    peer_id_b64: JString,
) -> jint {
    Java_com_ada_messenger_core_AdaCore_nativeHangup(env, obj, handle, call_id_hex, peer_id_b64)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_desktop_core_DesktopAdaCore_nativeDeclineCall(
    env: JNIEnv,
    obj: JObject,
    handle: jlong,
    call_id_hex: JString,
    peer_id_b64: JString,
) -> jint {
    Java_com_ada_messenger_core_AdaCore_nativeDeclineCall(
        env,
        obj,
        handle,
        call_id_hex,
        peer_id_b64,
    )
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_desktop_core_DesktopAdaCore_nativeSendIceCandidate(
    env: JNIEnv,
    obj: JObject,
    handle: jlong,
    call_id_hex: JString,
    peer_id_b64: JString,
    candidate: JString,
    sdp_mid: JString,
    sdp_mline_index: jint,
) -> jint {
    Java_com_ada_messenger_core_AdaCore_nativeSendIceCandidate(
        env,
        obj,
        handle,
        call_id_hex,
        peer_id_b64,
        candidate,
        sdp_mid,
        sdp_mline_index,
    )
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_desktop_core_DesktopAdaCore_nativeSendIceRestartOffer(
    env: JNIEnv,
    obj: JObject,
    handle: jlong,
    call_id_hex: JString,
    peer_id_b64: JString,
    offer_sdp: JString,
) -> jint {
    Java_com_ada_messenger_core_AdaCore_nativeSendIceRestartOffer(
        env,
        obj,
        handle,
        call_id_hex,
        peer_id_b64,
        offer_sdp,
    )
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_desktop_core_DesktopAdaCore_nativeSendIceRestartAnswer(
    env: JNIEnv,
    obj: JObject,
    handle: jlong,
    call_id_hex: JString,
    peer_id_b64: JString,
    answer_sdp: JString,
) -> jint {
    Java_com_ada_messenger_core_AdaCore_nativeSendIceRestartAnswer(
        env,
        obj,
        handle,
        call_id_hex,
        peer_id_b64,
        answer_sdp,
    )
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_desktop_core_DesktopAdaCore_nativePollEventJson(
    env: JNIEnv,
    obj: JObject,
    handle: jlong,
    timeout_ms: jint,
) -> jstring {
    Java_com_ada_messenger_core_AdaCore_nativePollEventJson(env, obj, handle, timeout_ms)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_desktop_core_DesktopAdaCore_nativeGetActiveCallsJson(
    env: JNIEnv,
    obj: JObject,
    handle: jlong,
) -> jstring {
    Java_com_ada_messenger_core_AdaCore_nativeGetActiveCallsJson(env, obj, handle)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_desktop_core_DesktopAdaCore_nativeGetCallAvailabilityJson(
    env: JNIEnv,
    obj: JObject,
    handle: jlong,
) -> jstring {
    Java_com_ada_messenger_core_AdaCore_nativeGetCallAvailabilityJson(env, obj, handle)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_desktop_core_DesktopAdaCore_nativeGetCallHistoryJson(
    env: JNIEnv,
    obj: JObject,
    handle: jlong,
    limit: jint,
) -> jstring {
    Java_com_ada_messenger_core_AdaCore_nativeGetCallHistoryJson(env, obj, handle, limit)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_desktop_core_DesktopAdaCore_nativeStartWebRtcProxy(
    env: JNIEnv,
    obj: JObject,
    handle: jlong,
    peer_b64: JString,
) -> jint {
    Java_com_ada_messenger_core_AdaCore_nativeStartWebRtcProxy(env, obj, handle, peer_b64)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_desktop_core_DesktopAdaCore_nativeStopWebRtcProxy(
    env: JNIEnv,
    obj: JObject,
    handle: jlong,
    peer_b64: JString,
) {
    Java_com_ada_messenger_core_AdaCore_nativeStopWebRtcProxy(env, obj, handle, peer_b64)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_desktop_core_DesktopAdaCore_nativeSendFileFromPath(
    env: JNIEnv,
    obj: JObject,
    handle: jlong,
    peer_id_b64: JString,
    file_name: JString,
    mime_type: JString,
    file_path: JString,
) -> jstring {
    Java_com_ada_messenger_core_AdaCore_nativeSendFileFromPath(
        env,
        obj,
        handle,
        peer_id_b64,
        file_name,
        mime_type,
        file_path,
    )
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_desktop_core_DesktopAdaCore_nativeAddBridge(
    env: JNIEnv,
    obj: JObject,
    handle: jlong,
    bridge_line: JString,
) -> jint {
    Java_com_ada_messenger_core_AdaCore_nativeAddBridge(env, obj, handle, bridge_line)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_desktop_core_DesktopAdaCore_nativeTakeLastErrorMessage(
    env: JNIEnv,
    obj: JObject,
    handle: jlong,
) -> jstring {
    Java_com_ada_messenger_core_AdaCore_nativeTakeLastErrorMessage(env, obj, handle)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_desktop_core_DesktopAdaCore_nativeGetBridgeStatusJson(
    env: JNIEnv,
    obj: JObject,
    handle: jlong,
) -> jstring {
    Java_com_ada_messenger_core_AdaCore_nativeGetBridgeStatusJson(env, obj, handle)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_desktop_core_DesktopAdaCore_nativeGetMetricsSnapshot(
    env: JNIEnv,
    obj: JObject,
    handle: jlong,
) -> jstring {
    Java_com_ada_messenger_core_AdaCore_nativeGetMetricsSnapshot(env, obj, handle)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_desktop_core_DesktopAdaCore_nativeDetectCensorshipJson(
    env: JNIEnv,
    obj: JObject,
    handle: jlong,
) -> jstring {
    Java_com_ada_messenger_core_AdaCore_nativeDetectCensorshipJson(env, obj, handle)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_desktop_core_DesktopAdaCore_nativeSetBridgeMode(
    env: JNIEnv,
    obj: JObject,
    handle: jlong,
    mode_str: JString,
) -> jint {
    Java_com_ada_messenger_core_AdaCore_nativeSetBridgeMode(env, obj, handle, mode_str)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_desktop_core_DesktopAdaCore_nativeImportBridgeManifestJson(
    env: JNIEnv,
    obj: JObject,
    handle: jlong,
    manifest_json: JString,
    source: JString,
    trusted_public_key_hex: JString,
) -> jint {
    Java_com_ada_messenger_core_AdaCore_nativeImportBridgeManifestJson(
        env,
        obj,
        handle,
        manifest_json,
        source,
        trusted_public_key_hex,
    )
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_desktop_core_DesktopAdaCore_nativeImportBridgeManifestUrl(
    env: JNIEnv,
    obj: JObject,
    handle: jlong,
    manifest_url: JString,
    trusted_public_key_hex: JString,
) -> jint {
    Java_com_ada_messenger_core_AdaCore_nativeImportBridgeManifestUrl(
        env,
        obj,
        handle,
        manifest_url,
        trusted_public_key_hex,
    )
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_desktop_core_DesktopAdaCore_nativeGetContactCardJson(
    env: JNIEnv,
    obj: JObject,
    handle: jlong,
) -> jstring {
    Java_com_ada_messenger_core_AdaCore_nativeGetContactCardJson(env, obj, handle)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_desktop_core_DesktopAdaCore_nativeAddContactJson(
    env: JNIEnv,
    obj: JObject,
    handle: jlong,
    contact_card_json: JString,
) -> jint {
    Java_com_ada_messenger_core_AdaCore_nativeAddContactJson(env, obj, handle, contact_card_json)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_desktop_core_DesktopAdaCore_nativeAddRelayNode(
    env: JNIEnv,
    obj: JObject,
    handle: jlong,
    relay_url: JString,
) -> jint {
    Java_com_ada_messenger_core_AdaCore_nativeAddRelayNode(env, obj, handle, relay_url)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_desktop_core_DesktopAdaCore_nativeSetRelayOnly(
    env: JNIEnv,
    obj: JObject,
    handle: jlong,
    enabled: jint,
) -> jint {
    Java_com_ada_messenger_core_AdaCore_nativeSetRelayOnly(env, obj, handle, enabled)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_desktop_core_DesktopAdaCore_nativeSetConnectionProfile(
    env: JNIEnv,
    obj: JObject,
    handle: jlong,
    connection_profile: JString,
) -> jint {
    Java_com_ada_messenger_core_AdaCore_nativeSetConnectionProfile(
        env,
        obj,
        handle,
        connection_profile,
    )
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_desktop_core_DesktopAdaCore_nativeEncodeShortLink(
    env: JNIEnv,
    obj: JObject,
    json: JString,
) -> jstring {
    Java_com_ada_messenger_core_AdaCore_nativeEncodeShortLink(env, obj, json)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_desktop_core_DesktopAdaCore_nativeDecodeShortLink(
    env: JNIEnv,
    obj: JObject,
    url: JString,
) -> jstring {
    Java_com_ada_messenger_core_AdaCore_nativeDecodeShortLink(env, obj, url)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_desktop_core_DesktopAdaCore_nativeHangupGroupCallRoom(
    env: JNIEnv,
    obj: JObject,
    handle: jlong,
    session_id_hex: JString,
) -> jint {
    Java_com_ada_messenger_core_AdaCore_nativeHangupGroupCallRoom(env, obj, handle, session_id_hex)
}

/// Called by the JVM when the native library is loaded (`System.loadLibrary`).
/// Initialises android_logger so all `tracing` / `log` records from Rust are
/// routed to logcat under the tag "ada_core".  Without this, every Rust log
/// is silently dropped on Android.
///
/// # Safety
/// JNI contract: called once by the JVM on library load.
#[no_mangle]
pub unsafe extern "C" fn JNI_OnLoad(_vm: *mut JavaVM, _reserved: *mut std::ffi::c_void) -> jint {
    #[cfg(feature = "jni-bindings")]
    {
        android_logger::init_once(
            android_logger::Config::default()
                .with_max_level(log::LevelFilter::Debug)
                .with_tag("ada_core")
                // Suppress iroh internal DEBUG/INFO noise; keep our own crate at Debug.
                .with_filter(
                    android_logger::FilterBuilder::new()
                        .parse("warn,ada_core=debug")
                        .build(),
                ),
        );
        // Bridge tracing → log facade so tracing::debug!/warn!/info! also reach logcat.
        tracing_log::LogTracer::init().ok();
    }
    jni::sys::JNI_VERSION_1_6
}

// ─── Lifecycle ────────────────────────────────────────────────────────────────

#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_core_AdaCore_nativeCreate(
    mut env: JNIEnv,
    _class: JClass,
    display_name: JString,
    data_dir: JString,
    connection_profile: JString,
) -> jlong {
    // Wrap in catch_unwind so a Rust panic cannot unwind into the JVM and
    // kill the process (undefined behaviour across FFI boundary).
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let name: String = match env.get_string(&display_name) {
            Ok(s) => s.into(),
            Err(_) => return 0,
        };
        let dir: String = match env.get_string(&data_dir) {
            Ok(s) => s.into(),
            Err(_) => return 0,
        };
        let profile: String = match env.get_string(&connection_profile) {
            Ok(s) => s.into(),
            Err(_) => return 0,
        };
        let name_c = match CString::new(name) {
            Ok(s) => s,
            Err(_) => return 0,
        };
        let dir_c = match CString::new(dir) {
            Ok(s) => s,
            Err(_) => return 0,
        };
        let profile_c = match CString::new(profile) {
            Ok(s) => s,
            Err(_) => return 0,
        };

        let handle = crate::ffi::ada_core_create_with_profile(
            name_c.as_ptr(),
            dir_c.as_ptr(),
            profile_c.as_ptr(),
        );
        handle as jlong
    }))
    .unwrap_or_else(|_| {
        tracing::error!("[jni] nativeCreate panicked");
        0
    })
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_core_AdaCore_nativeFree(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    crate::ffi::ada_core_free(handle as *mut AdaCoreHandle);
}

/// Notify the Rust core that the Android network has been restored.
/// Call from `ConnectivityManager.NetworkCallback.onAvailable()`.
#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_core_AdaCore_nativeNotifyNetworkAvailable(
    _env: JNIEnv,
    _obj: JObject,
    handle: jlong,
) {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        crate::ffi::ada_notify_network_available(handle as *const AdaCoreHandle);
    }))
    .unwrap_or_else(|_| {
        tracing::error!("[jni] nativeNotifyNetworkAvailable panicked");
    });
}

/// Notify the Rust core that the current network interface has been lost.
/// Call from `ConnectivityManager.NetworkCallback.onLost()`.
#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_core_AdaCore_nativeNotifyNetworkLost(
    _env: JNIEnv,
    _obj: JObject,
    handle: jlong,
) {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        crate::ffi::ada_notify_network_lost(handle as *const AdaCoreHandle);
    }))
    .unwrap_or_else(|_| {
        tracing::error!("[jni] nativeNotifyNetworkLost panicked");
    });
}

/// Receive bytes from the Android Local Mesh queue (BLE / Wi-Fi Direct).
/// Returns 0 on success, < 0 on failure.
#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_core_AdaCore_nativeReceiveMeshBytes(
    mut env: JNIEnv,
    _obj: JObject,
    handle: jlong,
    peer_b64: JString,
    bytes: JByteArray,
) -> jint {
    let peer_str: String = match env.get_string(&peer_b64) {
        Ok(s) => s.into(),
        Err(_) => return -1,
    };
    let peer_c = match std::ffi::CString::new(peer_str) {
        Ok(s) => s,
        Err(_) => return -1,
    };

    let byte_array = match env.convert_byte_array(&bytes) {
        Ok(b) => b,
        Err(_) => return -1,
    };

    let res = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        crate::ffi::ada_receive_mesh_bytes(
            handle as *const AdaCoreHandle,
            peer_c.as_ptr(),
            byte_array.as_ptr(),
            byte_array.len(),
        )
    }))
    .unwrap_or_else(|_| {
        tracing::error!("[jni] nativeReceiveMeshBytes panicked");
        -99
    });

    res as jint
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_core_AdaCore_nativeMeshPeerDisconnected(
    mut env: JNIEnv,
    _obj: JObject,
    handle: jlong,
    peer_b64: JString,
) -> jint {
    let peer_str: String = match env.get_string(&peer_b64) {
        Ok(s) => s.into(),
        Err(_) => return -1,
    };
    let peer_c = match std::ffi::CString::new(peer_str) {
        Ok(s) => s,
        Err(_) => return -1,
    };

    let res = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        crate::ffi::ada_mesh_peer_disconnected(
            handle as *const crate::ffi::AdaCoreHandle,
            peer_c.as_ptr(),
        )
    }))
    .unwrap_or_else(|_| {
        tracing::error!("[jni] nativeMeshPeerDisconnected panicked");
        -99
    });

    res as jint
}

// ─── Identity ─────────────────────────────────────────────────────────────────

#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_core_AdaCore_nativeGetPeerId(
    mut env: JNIEnv,
    _obj: JObject,
    handle: jlong,
) -> jstring {
    let ptr = crate::ffi::ada_get_peer_id(handle as *const AdaCoreHandle);
    if ptr.is_null() {
        return std::ptr::null_mut();
    }
    let s = CStr::from_ptr(ptr).to_str().unwrap_or("");
    let result = env.new_string(s);
    crate::ffi::ada_string_free(ptr);
    match result {
        Ok(j) => j.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_core_AdaCore_nativeGetDisplayName(
    mut env: JNIEnv,
    _obj: JObject,
    handle: jlong,
) -> jstring {
    let ptr = crate::ffi::ada_get_display_name(handle as *const AdaCoreHandle);
    if ptr.is_null() {
        return std::ptr::null_mut();
    }
    let s = CStr::from_ptr(ptr).to_str().unwrap_or("");
    let result = env.new_string(s);
    crate::ffi::ada_string_free(ptr);
    match result {
        Ok(j) => j.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// Export the running identity's secret key material as JSON.
/// Returns null on failure.  The JVM side must call this from a worker thread.
#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_core_AdaCore_nativeExportIdentityJson(
    mut env: JNIEnv,
    _obj: JObject,
    handle: jlong,
) -> jstring {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let ptr = crate::ffi::ada_export_identity_json(handle as *const AdaCoreHandle);
        if ptr.is_null() {
            return std::ptr::null_mut();
        }
        let s = CStr::from_ptr(ptr).to_str().unwrap_or("");
        let result = env.new_string(s);
        crate::ffi::ada_string_free(ptr);
        match result {
            Ok(j) => j.into_raw(),
            Err(_) => std::ptr::null_mut(),
        }
    }))
    .unwrap_or_else(|_| {
        tracing::error!("[jni] nativeExportIdentityJson panicked");
        std::ptr::null_mut()
    })
}

/// Export a full device snapshot (identity + contacts + ratchets + messages) as JSON.
/// Returns null on failure.  Must be called from a worker thread.
#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_core_AdaCore_nativeExportSnapshot(
    mut env: JNIEnv,
    _obj: JObject,
    handle: jlong,
) -> jstring {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let ptr = crate::ffi::ada_export_snapshot(handle as *const AdaCoreHandle);
        if ptr.is_null() {
            return std::ptr::null_mut();
        }
        let s = CStr::from_ptr(ptr).to_str().unwrap_or("");
        let result = env.new_string(s);
        crate::ffi::ada_free_string(ptr);
        match result {
            Ok(j) => j.into_raw(),
            Err(_) => std::ptr::null_mut(),
        }
    }))
    .unwrap_or_else(|_| {
        tracing::error!("[jni] nativeExportSnapshot panicked");
        std::ptr::null_mut()
    })
}

/// Import snapshot data (contacts, ratchets, messages) into a running core.
/// Returns 0 on success, non-zero on failure.
#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_core_AdaCore_nativeImportSnapshotData(
    mut env: JNIEnv,
    _obj: JObject,
    handle: jlong,
    snapshot_json: JString,
) -> jint {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let json_str: String = match env.get_string(&snapshot_json) {
            Ok(s) => s.into(),
            Err(_) => return 1,
        };
        let c_json = match CString::new(json_str) {
            Ok(c) => c,
            Err(_) => return 1,
        };
        crate::ffi::ada_import_snapshot_data(handle as *const AdaCoreHandle, c_json.as_ptr())
    }))
    .unwrap_or_else(|_| {
        tracing::error!("[jni] nativeImportSnapshotData panicked");
        1
    })
}

// ─── Device Sync Channel JNI (Android) ───────────────────────────────────────

/// Returns the 64-char hex link key or null if not paired.
#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_core_AdaCore_nativeGetLinkKeyHex(
    mut env: JNIEnv,
    _obj: JObject,
    handle: jlong,
) -> jstring {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let ptr = crate::ffi::ada_get_link_key_hex(handle as *const AdaCoreHandle);
        if ptr.is_null() {
            return std::ptr::null_mut();
        }
        let s = CStr::from_ptr(ptr).to_str().unwrap_or("");
        let result = env.new_string(s);
        crate::ffi::ada_free_string(ptr);
        match result {
            Ok(j) => j.into_raw(),
            Err(_) => std::ptr::null_mut(),
        }
    }))
    .unwrap_or(std::ptr::null_mut())
}

/// Store the HTTP sync URL of the linked desktop device.
#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_core_AdaCore_nativeStoreLinkSyncUrl(
    mut env: JNIEnv,
    _obj: JObject,
    handle: jlong,
    url: JString,
) -> jint {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let url_str: String = match env.get_string(&url) {
            Ok(s) => s.into(),
            Err(_) => return -1i32,
        };
        let c_url = match CString::new(url_str) {
            Ok(c) => c,
            Err(_) => return -1,
        };
        crate::ffi::ada_store_linked_device_sync_url(handle as *const AdaCoreHandle, c_url.as_ptr())
    }))
    .unwrap_or(-1)
}

/// Get the stored sync URL (or null).
#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_core_AdaCore_nativeGetLinkedDeviceSyncUrl(
    mut env: JNIEnv,
    _obj: JObject,
    handle: jlong,
) -> jstring {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let ptr = crate::ffi::ada_get_linked_device_sync_url(handle as *const AdaCoreHandle);
        if ptr.is_null() {
            return std::ptr::null_mut();
        }
        let s = CStr::from_ptr(ptr).to_str().unwrap_or("");
        let result = env.new_string(s);
        crate::ffi::ada_free_string(ptr);
        match result {
            Ok(j) => j.into_raw(),
            Err(_) => std::ptr::null_mut(),
        }
    }))
    .unwrap_or(std::ptr::null_mut())
}

/// Encrypt a ChatMessage JSON for device sync transport.
/// Returns base64-encoded sealed bytes, or null on error.
#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_core_AdaCore_nativeSealSyncPushJson(
    mut env: JNIEnv,
    _obj: JObject,
    handle: jlong,
    msg_json: JString,
) -> jstring {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let json_str: String = match env.get_string(&msg_json) {
            Ok(s) => s.into(),
            Err(_) => return std::ptr::null_mut(),
        };
        let c_json = match CString::new(json_str) {
            Ok(c) => c,
            Err(_) => return std::ptr::null_mut(),
        };
        let ptr = crate::ffi::ada_seal_sync_push_json(handle as *const AdaCoreHandle, c_json.as_ptr());
        if ptr.is_null() {
            return std::ptr::null_mut();
        }
        let s = CStr::from_ptr(ptr).to_str().unwrap_or("");
        let result = env.new_string(s);
        crate::ffi::ada_free_string(ptr);
        match result {
            Ok(j) => j.into_raw(),
            Err(_) => std::ptr::null_mut(),
        }
    }))
    .unwrap_or(std::ptr::null_mut())
}

/// Apply a device sync push on the desktop side.
/// `linkKeyHex` — 64-char hex; `dataB64` — base64-encoded sealed payload.
#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_core_AdaCore_nativeHandleSyncPush(
    mut env: JNIEnv,
    _obj: JObject,
    handle: jlong,
    link_key_hex: JString,
    data_b64: JString,
) -> jint {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let key_str: String = match env.get_string(&link_key_hex) {
            Ok(s) => s.into(),
            Err(_) => return -1i32,
        };
        let data_str: String = match env.get_string(&data_b64) {
            Ok(s) => s.into(),
            Err(_) => return -1,
        };
        use base64::Engine;
        let data = match base64::engine::general_purpose::STANDARD.decode(&data_str) {
            Ok(d) => d,
            Err(_) => return -1,
        };
        let c_key = match CString::new(key_str) {
            Ok(c) => c,
            Err(_) => return -1,
        };
        crate::ffi::ada_handle_sync_push(
            handle as *const AdaCoreHandle,
            c_key.as_ptr(),
            data.as_ptr(),
            data.len(),
        )
    }))
    .unwrap_or(-1)
}

// ─── Device Sync Channel JNI (Desktop) ───────────────────────────────────────

#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_desktop_core_DesktopAdaCore_nativeGetLinkKeyHex(
    env: JNIEnv,
    obj: JObject,
    handle: jlong,
) -> jstring {
    Java_com_ada_messenger_core_AdaCore_nativeGetLinkKeyHex(env, obj, handle)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_desktop_core_DesktopAdaCore_nativeHandleSyncPush(
    env: JNIEnv,
    obj: JObject,
    handle: jlong,
    link_key_hex: JString,
    data_b64: JString,
) -> jint {
    Java_com_ada_messenger_core_AdaCore_nativeHandleSyncPush(env, obj, handle, link_key_hex, data_b64)
}

// ─── Messaging ────────────────────────────────────────────────────────────────

#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_core_AdaCore_nativeSendText(
    mut env: JNIEnv,
    _obj: JObject,
    handle: jlong,
    peer_id_b64: JString,
    text: JString,
) -> jint {
    let peer_str: String = match env.get_string(&peer_id_b64) {
        Ok(s) => s.into(),
        Err(_) => return 1,
    };
    let text_str: String = match env.get_string(&text) {
        Ok(s) => s.into(),
        Err(_) => return 1,
    };

    let peer_c = match CString::new(peer_str) {
        Ok(s) => s,
        Err(_) => return 1,
    };
    let text_c = match CString::new(text_str) {
        Ok(s) => s,
        Err(_) => return 1,
    };

    let mut msg_id = [0u8; 16];
    let res = crate::ffi::ada_send_text(
        handle as *const AdaCoreHandle,
        peer_c.as_ptr(),
        text_c.as_ptr(),
        msg_id.as_mut_ptr(),
    );
    res as jint
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_core_AdaCore_nativeEditMessage(
    mut env: JNIEnv,
    _obj: JObject,
    handle: jlong,
    peer_id_b64: JString,
    target_msg_id_hex: JString,
    new_text: JString,
) -> jint {
    let peer_str: String = match env.get_string(&peer_id_b64) {
        Ok(s) => s.into(),
        Err(_) => return 1,
    };
    let target_hex: String = match env.get_string(&target_msg_id_hex) {
        Ok(s) => s.into(),
        Err(_) => return 1,
    };
    let text_str: String = match env.get_string(&new_text) {
        Ok(s) => s.into(),
        Err(_) => return 1,
    };

    let peer_c = match CString::new(peer_str) {
        Ok(s) => s,
        Err(_) => return 1,
    };
    let target_c = match CString::new(target_hex) {
        Ok(s) => s,
        Err(_) => return 1,
    };
    let text_c = match CString::new(text_str) {
        Ok(s) => s,
        Err(_) => return 1,
    };

    let mut msg_id = [0u8; 16];
    let res = crate::ffi::ada_edit_message(
        handle as *const AdaCoreHandle,
        peer_c.as_ptr(),
        target_c.as_ptr(),
        text_c.as_ptr(),
        msg_id.as_mut_ptr(),
    );
    res as jint
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_core_AdaCore_nativeSendEphemeralText(
    mut env: JNIEnv,
    _obj: JObject,
    handle: jlong,
    peer_id_b64: JString,
    text: JString,
    expires_in_secs: jint,
) -> jint {
    let peer_str: String = match env.get_string(&peer_id_b64) {
        Ok(s) => s.into(),
        Err(_) => return 1,
    };
    let text_str: String = match env.get_string(&text) {
        Ok(s) => s.into(),
        Err(_) => return 1,
    };

    let peer_c = match CString::new(peer_str) {
        Ok(s) => s,
        Err(_) => return 1,
    };
    let text_c = match CString::new(text_str) {
        Ok(s) => s,
        Err(_) => return 1,
    };

    let mut msg_id = [0u8; 16];
    let res = crate::ffi::ada_send_ephemeral_text(
        handle as *const AdaCoreHandle,
        peer_c.as_ptr(),
        text_c.as_ptr(),
        expires_in_secs as u32,
        msg_id.as_mut_ptr(),
    );
    res as jint
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_core_AdaCore_nativeEditGroupMessage(
    mut env: JNIEnv,
    _obj: JObject,
    handle: jlong,
    group_id_hex: JString,
    target_msg_id_hex: JString,
    new_text: JString,
) -> jint {
    let gid_str: String = match env.get_string(&group_id_hex) {
        Ok(s) => s.into(),
        Err(_) => return 1,
    };
    let target_hex: String = match env.get_string(&target_msg_id_hex) {
        Ok(s) => s.into(),
        Err(_) => return 1,
    };
    let text_str: String = match env.get_string(&new_text) {
        Ok(s) => s.into(),
        Err(_) => return 1,
    };

    let gid_c = match CString::new(gid_str) {
        Ok(s) => s,
        Err(_) => return 1,
    };
    let target_c = match CString::new(target_hex) {
        Ok(s) => s,
        Err(_) => return 1,
    };
    let text_c = match CString::new(text_str) {
        Ok(s) => s,
        Err(_) => return 1,
    };

    let mut msg_id = [0u8; 16];
    let res = crate::ffi::ada_edit_group_message(
        handle as *const AdaCoreHandle,
        gid_c.as_ptr(),
        target_c.as_ptr(),
        text_c.as_ptr(),
        msg_id.as_mut_ptr(),
    ) as jint;
    res
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_core_AdaCore_nativeSendReply(
    mut env: JNIEnv,
    _obj: JObject,
    handle: jlong,
    peer_id_b64: JString,
    text: JString,
    reply_to_msg_id_hex: JString,
) -> jint {
    let peer_str: String = match env.get_string(&peer_id_b64) {
        Ok(s) => s.into(),
        Err(_) => return 1,
    };
    let text_str: String = match env.get_string(&text) {
        Ok(s) => s.into(),
        Err(_) => return 1,
    };
    let reply_hex: String = match env.get_string(&reply_to_msg_id_hex) {
        Ok(s) => s.into(),
        Err(_) => return 1,
    };

    let peer_c = match CString::new(peer_str) {
        Ok(s) => s,
        Err(_) => return 1,
    };
    let text_c = match CString::new(text_str) {
        Ok(s) => s,
        Err(_) => return 1,
    };
    let reply_c = match CString::new(reply_hex) {
        Ok(s) => s,
        Err(_) => return 1,
    };

    let mut msg_id = [0u8; 16];
    let res = crate::ffi::ada_send_reply(
        handle as *const AdaCoreHandle,
        peer_c.as_ptr(),
        text_c.as_ptr(),
        reply_c.as_ptr(),
        msg_id.as_mut_ptr(),
    );
    res as jint
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_core_AdaCore_nativeSendReaction(
    mut env: JNIEnv,
    _obj: JObject,
    handle: jlong,
    peer_id_b64: JString,
    target_msg_id_hex: JString,
    emoji: JString,
) -> jint {
    let peer_str: String = match env.get_string(&peer_id_b64) {
        Ok(s) => s.into(),
        Err(_) => return 1,
    };
    let target_hex: String = match env.get_string(&target_msg_id_hex) {
        Ok(s) => s.into(),
        Err(_) => return 1,
    };
    let emoji_str: String = match env.get_string(&emoji) {
        Ok(s) => s.into(),
        Err(_) => return 1,
    };

    let peer_c = match CString::new(peer_str) {
        Ok(s) => s,
        Err(_) => return 1,
    };
    let target_c = match CString::new(target_hex) {
        Ok(s) => s,
        Err(_) => return 1,
    };
    let emoji_c = match CString::new(emoji_str) {
        Ok(s) => s,
        Err(_) => return 1,
    };

    let res = crate::ffi::ada_send_reaction(
        handle as *const AdaCoreHandle,
        peer_c.as_ptr(),
        target_c.as_ptr(),
        emoji_c.as_ptr(),
    );
    res as jint
}

/// Returns JSON array of conversations. Caller receives a Kotlin String.
#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_core_AdaCore_nativeGetConversationsJson(
    mut env: JNIEnv,
    _obj: JObject,
    handle: jlong,
) -> jstring {
    let ptr = crate::ffi::ada_get_conversations_json(handle as *const AdaCoreHandle);
    if ptr.is_null() {
        return env
            .new_string("[]")
            .map(|s| s.into_raw())
            .unwrap_or(std::ptr::null_mut());
    }
    let s = CStr::from_ptr(ptr).to_str().unwrap_or("[]");
    let j = env
        .new_string(s)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut());
    crate::ffi::ada_string_free(ptr);
    j
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_core_AdaCore_nativeSearchConversationsJson(
    mut env: JNIEnv,
    _obj: JObject,
    handle: jlong,
    query: JString,
) -> jstring {
    let query_str: String = match env.get_string(&query) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let query_c = match CString::new(query_str) {
        Ok(s) => s,
        Err(_) => return std::ptr::null_mut(),
    };

    let ptr =
        crate::ffi::ada_search_conversations_json(handle as *const AdaCoreHandle, query_c.as_ptr());
    if ptr.is_null() {
        return env
            .new_string("[]")
            .map(|s| s.into_raw())
            .unwrap_or(std::ptr::null_mut());
    }
    let s = CStr::from_ptr(ptr).to_str().unwrap_or("[]");
    let j = env
        .new_string(s)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut());
    crate::ffi::ada_string_free(ptr);
    j
}

/// Returns JSON array of messages for a conversation.
/// `convId` format: "d:BASE64" or "g:HEX"
#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_core_AdaCore_nativeGetMessagesJson(
    mut env: JNIEnv,
    _obj: JObject,
    handle: jlong,
    conv_id: JString,
    limit: jint,
) -> jstring {
    let conv_str: String = match env.get_string(&conv_id) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let conv_c = match CString::new(conv_str) {
        Ok(s) => s,
        Err(_) => return std::ptr::null_mut(),
    };

    let ptr = crate::ffi::ada_get_messages_json(
        handle as *const AdaCoreHandle,
        conv_c.as_ptr(),
        limit.max(0) as u32,
    );
    if ptr.is_null() {
        return env
            .new_string("[]")
            .map(|s| s.into_raw())
            .unwrap_or(std::ptr::null_mut());
    }
    let s = CStr::from_ptr(ptr).to_str().unwrap_or("[]");
    let j = env
        .new_string(s)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut());
    crate::ffi::ada_string_free(ptr);
    j
}

/// Poll one pending event as JSON. Returns null string if no events.
#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_core_AdaCore_nativePollEventJson(
    mut env: JNIEnv,
    _obj: JObject,
    handle: jlong,
    timeout_ms: jint,
) -> jstring {
    let ptr = crate::ffi::ada_poll_event_json(handle as *const AdaCoreHandle, timeout_ms);
    if ptr.is_null() {
        return std::ptr::null_mut();
    }
    let s = CStr::from_ptr(ptr).to_str().unwrap_or("");
    let j = env
        .new_string(s)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut());
    crate::ffi::ada_string_free(ptr);
    j
}

/// Mark all messages in a conversation as read.
#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_core_AdaCore_nativeMarkRead(
    mut env: JNIEnv,
    _obj: JObject,
    handle: jlong,
    conv_id: JString,
) -> jint {
    let conv_str: String = match env.get_string(&conv_id) {
        Ok(s) => s.into(),
        Err(_) => return 1,
    };
    let conv_c = match CString::new(conv_str) {
        Ok(s) => s,
        Err(_) => return 1,
    };
    crate::ffi::ada_mark_read(handle as *const AdaCoreHandle, conv_c.as_ptr()) as jint
}

/// Delete a single message (local only). Returns 0 on success.
#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_core_AdaCore_nativeDeleteMessage(
    mut env: JNIEnv,
    _obj: JObject,
    handle: jlong,
    msg_id_hex: JString,
) -> jint {
    let id_str: String = match env.get_string(&msg_id_hex) {
        Ok(s) => s.into(),
        Err(_) => return 1,
    };
    let id_c = match CString::new(id_str) {
        Ok(s) => s,
        Err(_) => return 1,
    };
    crate::ffi::ada_delete_message(handle as *const AdaCoreHandle, id_c.as_ptr()) as jint
}

/// Delete an entire conversation (local only). Returns 0 on success.
#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_core_AdaCore_nativeDeleteConversation(
    mut env: JNIEnv,
    _obj: JObject,
    handle: jlong,
    conv_id: JString,
) -> jint {
    let conv_str: String = match env.get_string(&conv_id) {
        Ok(s) => s.into(),
        Err(_) => return 1,
    };
    let conv_c = match CString::new(conv_str) {
        Ok(s) => s,
        Err(_) => return 1,
    };
    crate::ffi::ada_delete_conversation(handle as *const AdaCoreHandle, conv_c.as_ptr()) as jint
}

/// Clear all messages in a conversation, keeping the conversation entry. Returns 0 on success.
#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_core_AdaCore_nativeClearConversationMessages(
    mut env: JNIEnv,
    _obj: JObject,
    handle: jlong,
    conv_id: JString,
) -> jint {
    let conv_str: String = match env.get_string(&conv_id) {
        Ok(s) => s.into(),
        Err(_) => return 1,
    };
    let conv_c = match CString::new(conv_str) {
        Ok(s) => s,
        Err(_) => return 1,
    };
    crate::ffi::ada_clear_conversation_messages(handle as *const AdaCoreHandle, conv_c.as_ptr())
        as jint
}

/// Delete a message locally and send a DeleteRequest to the peer. Returns 0 on success.
#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_core_AdaCore_nativeDeleteMessageForEveryone(
    mut env: JNIEnv,
    _obj: JObject,
    handle: jlong,
    peer_id_b64: JString,
    msg_id_hex: JString,
) -> jint {
    let peer_str: String = match env.get_string(&peer_id_b64) {
        Ok(s) => s.into(),
        Err(_) => return 1,
    };
    let id_str: String = match env.get_string(&msg_id_hex) {
        Ok(s) => s.into(),
        Err(_) => return 1,
    };
    let peer_c = match CString::new(peer_str) {
        Ok(s) => s,
        Err(_) => return 1,
    };
    let id_c = match CString::new(id_str) {
        Ok(s) => s,
        Err(_) => return 1,
    };
    crate::ffi::ada_delete_message_for_everyone(
        handle as *const AdaCoreHandle,
        peer_c.as_ptr(),
        id_c.as_ptr(),
    ) as jint
}

// ─── Calls ───────────────────────────────────────────────────────────────────

#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_core_AdaCore_nativeCallAudio(
    mut env: JNIEnv,
    _obj: JObject,
    handle: jlong,
    peer_id_b64: JString,
    offer_sdp: JString,
) -> jstring {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let peer_str: String = match env.get_string(&peer_id_b64) {
            Ok(s) => s.into(),
            Err(_) => return std::ptr::null_mut(),
        };
        let offer_str: String = match env.get_string(&offer_sdp) {
            Ok(s) => s.into(),
            Err(_) => return std::ptr::null_mut(),
        };
        let peer_c = match CString::new(peer_str) {
            Ok(s) => s,
            Err(_) => return std::ptr::null_mut(),
        };
        let offer_c = match CString::new(offer_str) {
            Ok(s) => s,
            Err(_) => return std::ptr::null_mut(),
        };

        let mut call_id = [0u8; 16];
        let res = crate::ffi::ada_call_audio(
            handle as *const AdaCoreHandle,
            peer_c.as_ptr(),
            offer_c.as_ptr(),
            call_id.as_mut_ptr(),
        );
        if matches!(res, AdaResult::Ok) {
            let call_id_hex = hex::encode(call_id);
            env.new_string(call_id_hex)
                .map(|s| s.into_raw())
                .unwrap_or(std::ptr::null_mut())
        } else {
            std::ptr::null_mut()
        }
    }))
    .unwrap_or_else(|_| {
        tracing::error!("[jni] nativeCallAudio panicked");
        std::ptr::null_mut()
    })
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_core_AdaCore_nativeCallInGroupRoom(
    mut env: JNIEnv,
    _obj: JObject,
    handle: jlong,
    peer_id_b64: JString,
    offer_sdp: JString,
    group_id_hex: JString,
    session_id_hex: JString,
    has_video: jint,
) -> jstring {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let peer_str: String = match env.get_string(&peer_id_b64) {
            Ok(s) => s.into(),
            Err(_) => return std::ptr::null_mut(),
        };
        let offer_str: String = match env.get_string(&offer_sdp) {
            Ok(s) => s.into(),
            Err(_) => return std::ptr::null_mut(),
        };
        let group_str: String = match env.get_string(&group_id_hex) {
            Ok(s) => s.into(),
            Err(_) => return std::ptr::null_mut(),
        };
        let session_str: String = match env.get_string(&session_id_hex) {
            Ok(s) => s.into(),
            Err(_) => return std::ptr::null_mut(),
        };

        let peer_c = match CString::new(peer_str) {
            Ok(s) => s,
            Err(_) => return std::ptr::null_mut(),
        };
        let offer_c = match CString::new(offer_str) {
            Ok(s) => s,
            Err(_) => return std::ptr::null_mut(),
        };
        let group_c = match CString::new(group_str) {
            Ok(s) => s,
            Err(_) => return std::ptr::null_mut(),
        };
        let session_c = match CString::new(session_str) {
            Ok(s) => s,
            Err(_) => return std::ptr::null_mut(),
        };

        let mut call_id = [0u8; 16];
        let res = crate::ffi::ada_call_in_group_room(
            handle as *const AdaCoreHandle,
            peer_c.as_ptr(),
            offer_c.as_ptr(),
            group_c.as_ptr(),
            session_c.as_ptr(),
            has_video,
            call_id.as_mut_ptr(),
        );
        if matches!(res, AdaResult::Ok) {
            let call_id_hex = hex::encode(call_id);
            env.new_string(call_id_hex)
                .map(|s| s.into_raw())
                .unwrap_or(std::ptr::null_mut())
        } else {
            std::ptr::null_mut()
        }
    }))
    .unwrap_or_else(|_| {
        tracing::error!("[jni] nativeCallInGroupRoom panicked");
        std::ptr::null_mut()
    })
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_core_AdaCore_nativeHangup(
    mut env: JNIEnv,
    _obj: JObject,
    handle: jlong,
    call_id_hex: JString,
    peer_id_b64: JString,
) -> jint {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let call_hex: String = match env.get_string(&call_id_hex) {
            Ok(s) => s.into(),
            Err(_) => return 1,
        };
        let peer_str: String = match env.get_string(&peer_id_b64) {
            Ok(s) => s.into(),
            Err(_) => return 1,
        };

        let call_bytes = match hex::decode(&call_hex) {
            Ok(b) if b.len() == 16 => b,
            _ => return 1,
        };
        let peer_c = match CString::new(peer_str) {
            Ok(s) => s,
            Err(_) => return 1,
        };

        let mut call_id = [0u8; 16];
        call_id.copy_from_slice(&call_bytes);

        crate::ffi::ada_hangup(
            handle as *const AdaCoreHandle,
            call_id.as_ptr(),
            peer_c.as_ptr(),
        ) as jint
    }))
    .unwrap_or_else(|_| {
        tracing::error!("[jni] nativeHangup panicked");
        1
    })
}

/// Decline (reject) an incoming call. Returns 0 on success.
#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_core_AdaCore_nativeDeclineCall(
    mut env: JNIEnv,
    _obj: JObject,
    handle: jlong,
    call_id_hex: JString,
    peer_id_b64: JString,
) -> jint {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let call_hex: String = match env.get_string(&call_id_hex) {
            Ok(s) => s.into(),
            Err(_) => return 1,
        };
        let peer_str: String = match env.get_string(&peer_id_b64) {
            Ok(s) => s.into(),
            Err(_) => return 1,
        };

        let call_bytes = match hex::decode(&call_hex) {
            Ok(b) if b.len() == 16 => b,
            _ => return 1,
        };
        let peer_c = match CString::new(peer_str) {
            Ok(s) => s,
            Err(_) => return 1,
        };

        let mut call_id = [0u8; 16];
        call_id.copy_from_slice(&call_bytes);

        crate::ffi::ada_decline_call(
            handle as *const AdaCoreHandle,
            call_id.as_ptr(),
            peer_c.as_ptr(),
        ) as jint
    }))
    .unwrap_or_else(|_| {
        tracing::error!("[jni] nativeDeclineCall panicked");
        1
    })
}

// ─── Phase 5: Extended call JNI ──────────────────────────────────────────────

/// Answer an incoming call. Returns 0 on success.
#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_core_AdaCore_nativeAnswerCall(
    mut env: JNIEnv,
    _obj: JObject,
    handle: jlong,
    call_id_hex: JString,
    peer_id_b64: JString,
    answer_sdp: JString,
) -> jint {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let call_hex: String = match env.get_string(&call_id_hex) {
            Ok(s) => s.into(),
            Err(_) => return 1,
        };
        let peer_str: String = match env.get_string(&peer_id_b64) {
            Ok(s) => s.into(),
            Err(_) => return 1,
        };
        let answer_str: String = match env.get_string(&answer_sdp) {
            Ok(s) => s.into(),
            Err(_) => return 1,
        };
        let call_bytes = match hex::decode(&call_hex) {
            Ok(b) if b.len() == 16 => b,
            _ => return 1,
        };
        let peer_c = match CString::new(peer_str) {
            Ok(s) => s,
            Err(_) => return 1,
        };
        let answer_c = match CString::new(answer_str) {
            Ok(s) => s,
            Err(_) => return 1,
        };
        let mut cid = [0u8; 16];
        cid.copy_from_slice(&call_bytes);
        crate::ffi::ada_answer_call(
            handle as *const AdaCoreHandle,
            cid.as_ptr(),
            peer_c.as_ptr(),
            answer_c.as_ptr(),
        ) as jint
    }))
    .unwrap_or_else(|_| {
        tracing::error!("[jni] nativeAnswerCall panicked");
        1
    })
}

/// Initiate a video call. Returns hex call ID string or null on error.
#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_core_AdaCore_nativeCallVideo(
    mut env: JNIEnv,
    _obj: JObject,
    handle: jlong,
    peer_id_b64: JString,
    offer_sdp: JString,
) -> jstring {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let peer_str: String = match env.get_string(&peer_id_b64) {
            Ok(s) => s.into(),
            Err(_) => return std::ptr::null_mut(),
        };
        let offer_str: String = match env.get_string(&offer_sdp) {
            Ok(s) => s.into(),
            Err(_) => return std::ptr::null_mut(),
        };
        let peer_c = match CString::new(peer_str) {
            Ok(s) => s,
            Err(_) => return std::ptr::null_mut(),
        };
        let offer_c = match CString::new(offer_str) {
            Ok(s) => s,
            Err(_) => return std::ptr::null_mut(),
        };
        let mut call_id = [0u8; 16];
        let res = crate::ffi::ada_call_video(
            handle as *const AdaCoreHandle,
            peer_c.as_ptr(),
            offer_c.as_ptr(),
            call_id.as_mut_ptr(),
        );
        if matches!(res, AdaResult::Ok) {
            env.new_string(hex::encode(call_id))
                .map(|s| s.into_raw())
                .unwrap_or(std::ptr::null_mut())
        } else {
            std::ptr::null_mut()
        }
    }))
    .unwrap_or_else(|_| {
        tracing::error!("[jni] nativeCallVideo panicked");
        std::ptr::null_mut()
    })
}

/// Returns JSON array of active calls.
#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_core_AdaCore_nativeGetActiveCallsJson(
    mut env: JNIEnv,
    _obj: JObject,
    handle: jlong,
) -> jstring {
    let ptr = crate::ffi::ada_get_active_calls_json(handle as *const AdaCoreHandle);
    if ptr.is_null() {
        return env
            .new_string("[]")
            .map(|s| s.into_raw())
            .unwrap_or(std::ptr::null_mut());
    }
    let s = CStr::from_ptr(ptr).to_str().unwrap_or("[]");
    let j = env
        .new_string(s)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut());
    crate::ffi::ada_string_free(ptr);
    j
}

/// Returns JSON describing whether realtime calls can be started now.
#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_core_AdaCore_nativeGetCallAvailabilityJson(
    mut env: JNIEnv,
    _obj: JObject,
    handle: jlong,
) -> jstring {
    let ptr = crate::ffi::ada_get_call_availability_json(handle as *const AdaCoreHandle);
    if ptr.is_null() {
        return env
            .new_string("{\"available\":false}")
            .map(|s| s.into_raw())
            .unwrap_or(std::ptr::null_mut());
    }
    let s = CStr::from_ptr(ptr)
        .to_str()
        .unwrap_or("{\"available\":false}");
    let j = env
        .new_string(s)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut());
    crate::ffi::ada_string_free(ptr);
    j
}

/// Returns JSON array of call history (most-recent first).
#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_core_AdaCore_nativeGetCallHistoryJson(
    mut env: JNIEnv,
    _obj: JObject,
    handle: jlong,
    limit: jint,
) -> jstring {
    let ptr = crate::ffi::ada_get_call_history_json(handle as *const AdaCoreHandle, limit);
    if ptr.is_null() {
        return env
            .new_string("[]")
            .map(|s| s.into_raw())
            .unwrap_or(std::ptr::null_mut());
    }
    let s = CStr::from_ptr(ptr).to_str().unwrap_or("[]");
    let j = env
        .new_string(s)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut());
    crate::ffi::ada_string_free(ptr);
    j
}

/// Send a local ICE candidate to the remote peer via Rust signaling.
/// Returns 0 on success, non-zero on error.
#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_core_AdaCore_nativeSendIceCandidate(
    mut env: JNIEnv,
    _obj: JObject,
    handle: jlong,
    call_id_hex: JString,
    peer_id_b64: JString,
    candidate: JString,
    sdp_mid: JString,
    sdp_mline_index: jint,
) -> jint {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let call_hex: String = match env.get_string(&call_id_hex) {
            Ok(s) => s.into(),
            Err(_) => return 1,
        };
        let peer_str: String = match env.get_string(&peer_id_b64) {
            Ok(s) => s.into(),
            Err(_) => return 1,
        };
        let cand_str: String = match env.get_string(&candidate) {
            Ok(s) => s.into(),
            Err(_) => return 1,
        };

        let call_bytes = match hex::decode(&call_hex) {
            Ok(b) if b.len() == 16 => b,
            _ => return 1,
        };
        let peer_c = match CString::new(peer_str) {
            Ok(s) => s,
            Err(_) => return 1,
        };
        let cand_c = match CString::new(cand_str) {
            Ok(s) => s,
            Err(_) => return 1,
        };

        let mid_opt: Option<CString> = env.get_string(&sdp_mid).ok().and_then(|s| {
            let st: String = s.into();
            CString::new(st).ok()
        });
        let mid_ptr = mid_opt
            .as_ref()
            .map(|c| c.as_ptr())
            .unwrap_or(std::ptr::null());

        let mut cid = [0u8; 16];
        cid.copy_from_slice(&call_bytes);

        crate::ffi::ada_send_ice_candidate(
            handle as *const AdaCoreHandle,
            cid.as_ptr(),
            peer_c.as_ptr(),
            cand_c.as_ptr(),
            mid_ptr,
            sdp_mline_index as u16,
        ) as jint
    }))
    .unwrap_or_else(|_| {
        tracing::error!("[jni] nativeSendIceCandidate panicked");
        1
    })
}

// ─── Phase 6: File transfer JNI ──────────────────────────────────────────────

/// Send an ICE restart offer for an existing call (called by the offerer on ICE failure).
#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_core_AdaCore_nativeSendIceRestartOffer(
    mut env: JNIEnv,
    _obj: JObject,
    handle: jlong,
    call_id_hex: JString,
    peer_id_b64: JString,
    offer_sdp: JString,
) -> jint {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let call_hex: String = match env.get_string(&call_id_hex) {
            Ok(s) => s.into(),
            Err(_) => return 1,
        };
        let peer_str: String = match env.get_string(&peer_id_b64) {
            Ok(s) => s.into(),
            Err(_) => return 1,
        };
        let offer_str: String = match env.get_string(&offer_sdp) {
            Ok(s) => s.into(),
            Err(_) => return 1,
        };
        let call_bytes = match hex::decode(&call_hex) {
            Ok(b) if b.len() == 16 => b,
            _ => return 1,
        };
        let peer_c = match CString::new(peer_str) {
            Ok(s) => s,
            Err(_) => return 1,
        };
        let offer_c = match CString::new(offer_str) {
            Ok(s) => s,
            Err(_) => return 1,
        };
        let mut cid = [0u8; 16];
        cid.copy_from_slice(&call_bytes);
        crate::ffi::ada_send_ice_restart_offer(
            handle as *const AdaCoreHandle,
            cid.as_ptr(),
            peer_c.as_ptr(),
            offer_c.as_ptr(),
        ) as jint
    }))
    .unwrap_or_else(|_| {
        tracing::error!("[jni] nativeSendIceRestartOffer panicked");
        1
    })
}

/// Send an ICE restart answer (called by the answerer when it receives IceRestartOffer).
#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_core_AdaCore_nativeSendIceRestartAnswer(
    mut env: JNIEnv,
    _obj: JObject,
    handle: jlong,
    call_id_hex: JString,
    peer_id_b64: JString,
    answer_sdp: JString,
) -> jint {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let call_hex: String = match env.get_string(&call_id_hex) {
            Ok(s) => s.into(),
            Err(_) => return 1,
        };
        let peer_str: String = match env.get_string(&peer_id_b64) {
            Ok(s) => s.into(),
            Err(_) => return 1,
        };
        let ans_str: String = match env.get_string(&answer_sdp) {
            Ok(s) => s.into(),
            Err(_) => return 1,
        };
        let call_bytes = match hex::decode(&call_hex) {
            Ok(b) if b.len() == 16 => b,
            _ => return 1,
        };
        let peer_c = match CString::new(peer_str) {
            Ok(s) => s,
            Err(_) => return 1,
        };
        let ans_c = match CString::new(ans_str) {
            Ok(s) => s,
            Err(_) => return 1,
        };
        let mut cid = [0u8; 16];
        cid.copy_from_slice(&call_bytes);
        crate::ffi::ada_send_ice_restart_answer(
            handle as *const AdaCoreHandle,
            cid.as_ptr(),
            peer_c.as_ptr(),
            ans_c.as_ptr(),
        ) as jint
    }))
    .unwrap_or_else(|_| {
        tracing::error!("[jni] nativeSendIceRestartAnswer panicked");
        1
    })
}

/// Send a file to a peer. `data` is a Kotlin ByteArray. Returns hex transfer-id or null.
#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_core_AdaCore_nativeSendFileBytes(
    mut env: JNIEnv,
    _obj: JObject,
    handle: jlong,
    peer_id_b64: JString,
    file_name: JString,
    mime_type: JString,
    data: jni::objects::JByteArray,
) -> jstring {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let peer_str: String = match env.get_string(&peer_id_b64) {
            Ok(s) => s.into(),
            Err(_) => return std::ptr::null_mut(),
        };
        let fname: String = match env.get_string(&file_name) {
            Ok(s) => s.into(),
            Err(_) => return std::ptr::null_mut(),
        };
        let mime: String = match env.get_string(&mime_type) {
            Ok(s) => s.into(),
            Err(_) => return std::ptr::null_mut(),
        };
        let bytes_u8: Vec<u8> = match env.convert_byte_array(&data) {
            Ok(b) => b,
            Err(_) => return std::ptr::null_mut(),
        };

        let peer_c = match CString::new(peer_str) {
            Ok(s) => s,
            Err(_) => return std::ptr::null_mut(),
        };
        let name_c = match CString::new(fname) {
            Ok(s) => s,
            Err(_) => return std::ptr::null_mut(),
        };
        let mime_c = match CString::new(mime) {
            Ok(s) => s,
            Err(_) => return std::ptr::null_mut(),
        };

        let mut tid = [0u8; 16];
        let res = crate::ffi::ada_send_file_bytes(
            handle as *const AdaCoreHandle,
            peer_c.as_ptr(),
            name_c.as_ptr(),
            mime_c.as_ptr(),
            bytes_u8.as_ptr(),
            bytes_u8.len() as u32,
            tid.as_mut_ptr(),
        );
        if matches!(res, AdaResult::Ok) {
            env.new_string(hex::encode(tid))
                .map(|s| s.into_raw())
                .unwrap_or(std::ptr::null_mut())
        } else {
            std::ptr::null_mut()
        }
    }))
    .unwrap_or_else(|_| {
        tracing::error!("[jni] nativeSendFileBytes panicked");
        std::ptr::null_mut()
    })
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_core_AdaCore_nativeSendFileFromPath(
    mut env: JNIEnv,
    _obj: JObject,
    handle: jlong,
    peer_id_b64: JString,
    file_name: JString,
    mime_type: JString,
    file_path: JString,
) -> jstring {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let peer_str: String = match env.get_string(&peer_id_b64) {
            Ok(s) => s.into(),
            Err(_) => return std::ptr::null_mut(),
        };
        let fname: String = match env.get_string(&file_name) {
            Ok(s) => s.into(),
            Err(_) => return std::ptr::null_mut(),
        };
        let mime: String = match env.get_string(&mime_type) {
            Ok(s) => s.into(),
            Err(_) => return std::ptr::null_mut(),
        };
        let path_str: String = match env.get_string(&file_path) {
            Ok(s) => s.into(),
            Err(_) => return std::ptr::null_mut(),
        };

        let peer_c = match CString::new(peer_str) {
            Ok(s) => s,
            Err(_) => return std::ptr::null_mut(),
        };
        let name_c = match CString::new(fname) {
            Ok(s) => s,
            Err(_) => return std::ptr::null_mut(),
        };
        let mime_c = match CString::new(mime) {
            Ok(s) => s,
            Err(_) => return std::ptr::null_mut(),
        };
        let path_c = match CString::new(path_str) {
            Ok(s) => s,
            Err(_) => return std::ptr::null_mut(),
        };

        let mut tid = [0u8; 16];
        let res = crate::ffi::ada_send_file_from_path(
            handle as *const AdaCoreHandle,
            peer_c.as_ptr(),
            name_c.as_ptr(),
            mime_c.as_ptr(),
            path_c.as_ptr(),
            tid.as_mut_ptr(),
        );
        if matches!(res, AdaResult::Ok) {
            env.new_string(hex::encode(tid))
                .map(|s| s.into_raw())
                .unwrap_or(std::ptr::null_mut())
        } else {
            std::ptr::null_mut()
        }
    }))
    .unwrap_or_else(|_| {
        tracing::error!("[jni] nativeSendFileFromPath panicked");
        std::ptr::null_mut()
    })
}

/// Returns JSON array of active transfers.
#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_core_AdaCore_nativeGetTransfersJson(
    mut env: JNIEnv,
    _obj: JObject,
    handle: jlong,
) -> jstring {
    let ptr = crate::ffi::ada_get_transfers_json(handle as *const AdaCoreHandle);
    if ptr.is_null() {
        return env
            .new_string("[]")
            .map(|s| s.into_raw())
            .unwrap_or(std::ptr::null_mut());
    }
    let s = CStr::from_ptr(ptr).to_str().unwrap_or("[]");
    let j = env
        .new_string(s)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut());
    crate::ffi::ada_string_free(ptr);
    j
}

/// Cancel an active transfer by hex transfer ID. Returns 0 on success.
#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_core_AdaCore_nativeCancelTransfer(
    mut env: JNIEnv,
    _obj: JObject,
    handle: jlong,
    transfer_id_hex: JString,
) -> jint {
    let hex_str: String = match env.get_string(&transfer_id_hex) {
        Ok(s) => s.into(),
        Err(_) => return 1,
    };
    let bytes = match hex::decode(&hex_str) {
        Ok(b) if b.len() == 16 => b,
        _ => return 1,
    };
    let mut tid = [0u8; 16];
    tid.copy_from_slice(&bytes);
    crate::ffi::ada_cancel_transfer(handle as *const AdaCoreHandle, tid.as_ptr()) as jint
}

/// Save a completed inbound transfer to a file on the Android filesystem.
/// Returns a JSON string `{"file_name":"...","mime_type":"...","file_size":N}` on success, or null.
/// The parent directory of `file_path` must already exist (created by the Kotlin caller).
#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_core_AdaCore_nativeSaveTransferToFile(
    mut env: JNIEnv,
    _obj: JObject,
    handle: jlong,
    transfer_id_hex: JString,
    file_path: JString,
) -> jstring {
    let tid_str: String = match env.get_string(&transfer_id_hex) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let path_str: String = match env.get_string(&file_path) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let tid_c = match CString::new(tid_str) {
        Ok(s) => s,
        Err(_) => return std::ptr::null_mut(),
    };
    let path_c = match CString::new(path_str) {
        Ok(s) => s,
        Err(_) => return std::ptr::null_mut(),
    };
    let mut meta_ptr: *mut std::os::raw::c_char = std::ptr::null_mut();
    let res = crate::ffi::ada_save_transfer_to_file(
        handle as *const AdaCoreHandle,
        tid_c.as_ptr(),
        path_c.as_ptr(),
        &mut meta_ptr,
    );
    if !matches!(res, AdaResult::Ok) || meta_ptr.is_null() {
        return std::ptr::null_mut();
    }
    let meta_str = CStr::from_ptr(meta_ptr).to_str().unwrap_or("{}");
    let j = env
        .new_string(meta_str)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut());
    crate::ffi::ada_string_free(meta_ptr);
    j
}

/// Fetch BlobRef content from `from_peer_b64`/`hash_hex` and save to `file_path`.
/// Returns true on success.
#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_core_AdaCore_nativeFetchBlobToFile(
    mut env: JNIEnv,
    _obj: JObject,
    handle: jlong,
    from_peer_b64: JString,
    hash_hex: JString,
    file_path: JString,
) -> jboolean {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let peer: String = match env.get_string(&from_peer_b64) {
            Ok(s) => s.into(),
            Err(_) => return 0,
        };
        let hash: String = match env.get_string(&hash_hex) {
            Ok(s) => s.into(),
            Err(_) => return 0,
        };
        let path: String = match env.get_string(&file_path) {
            Ok(s) => s.into(),
            Err(_) => return 0,
        };

        let peer_c = match CString::new(peer) {
            Ok(s) => s,
            Err(_) => return 0,
        };
        let hash_c = match CString::new(hash) {
            Ok(s) => s,
            Err(_) => return 0,
        };
        let path_c = match CString::new(path) {
            Ok(s) => s,
            Err(_) => return 0,
        };

        let res = crate::ffi::ada_fetch_blob_to_file(
            handle as *const AdaCoreHandle,
            peer_c.as_ptr(),
            hash_c.as_ptr(),
            path_c.as_ptr(),
        );
        matches!(res, AdaResult::Ok) as jboolean
    }))
    .unwrap_or_else(|_| {
        tracing::error!("[jni] nativeFetchBlobToFile panicked");
        0
    })
}

// ─── Phase 7: Bridge JNI ─────────────────────────────────────────────────────

/// Add a bridge using a bridge-line string. Returns 0 on success.
#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_core_AdaCore_nativeAddBridge(
    mut env: JNIEnv,
    _obj: JObject,
    handle: jlong,
    bridge_line: JString,
) -> jint {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let line: String = match env.get_string(&bridge_line) {
            Ok(s) => s.into(),
            Err(_) => return 1,
        };
        let line_c = match CString::new(line) {
            Ok(s) => s,
            Err(_) => return 1,
        };
        crate::ffi::ada_add_bridge(handle as *mut AdaCoreHandle, line_c.as_ptr()) as jint
    }))
    .unwrap_or_else(|_| {
        tracing::error!("[jni] nativeAddBridge panicked");
        1
    })
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_core_AdaCore_nativeTakeLastErrorMessage(
    env: JNIEnv,
    _obj: JObject,
    handle: jlong,
) -> jstring {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let ptr = crate::ffi::ada_take_last_error_message(handle as *const AdaCoreHandle);
        if ptr.is_null() {
            return std::ptr::null_mut();
        }
        let message = CStr::from_ptr(ptr).to_string_lossy().into_owned();
        crate::ffi::ada_string_free(ptr);
        match env.new_string(message) {
            Ok(value) => value.into_raw(),
            Err(_) => std::ptr::null_mut(),
        }
    }))
    .unwrap_or_else(|_| {
        tracing::error!("[jni] nativeTakeLastErrorMessage panicked");
        std::ptr::null_mut()
    })
}

/// Returns JSON with bridge list and current mode.
#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_core_AdaCore_nativeGetBridgeStatusJson(
    mut env: JNIEnv,
    _obj: JObject,
    handle: jlong,
) -> jstring {
    let ptr = crate::ffi::ada_get_bridge_status_json(handle as *const AdaCoreHandle);
    if ptr.is_null() {
        return env
            .new_string("{}")
            .map(|s| s.into_raw())
            .unwrap_or(std::ptr::null_mut());
    }
    let s = CStr::from_ptr(ptr).to_str().unwrap_or("{}");
    let j = env
        .new_string(s)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut());
    crate::ffi::ada_string_free(ptr);
    j
}

/// Returns a synchronous JSON snapshot of all runtime telemetry counters.
/// Does not block on async locks — safe to call frequently.
#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_core_AdaCore_nativeGetMetricsSnapshot(
    mut env: JNIEnv,
    _obj: JObject,
    handle: jlong,
) -> jstring {
    let ptr = crate::ffi::ada_get_metrics_snapshot(handle as *const AdaCoreHandle);
    if ptr.is_null() {
        return env
            .new_string("{}")
            .map(|s| s.into_raw())
            .unwrap_or(std::ptr::null_mut());
    }
    let s = CStr::from_ptr(ptr).to_str().unwrap_or("{}");
    let j = env
        .new_string(s)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut());
    crate::ffi::ada_string_free(ptr);
    j
}

/// Returns JSON `{"level":"None"|...}` based on a fast connectivity probe.
#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_core_AdaCore_nativeDetectCensorshipJson(
    mut env: JNIEnv,
    _obj: JObject,
    handle: jlong,
) -> jstring {
    let ptr = crate::ffi::ada_detect_censorship_json(handle as *const AdaCoreHandle);
    if ptr.is_null() {
        return env
            .new_string("{\"level\":\"None\"}")
            .map(|s| s.into_raw())
            .unwrap_or(std::ptr::null_mut());
    }
    let s = CStr::from_ptr(ptr)
        .to_str()
        .unwrap_or("{\"level\":\"None\"}");
    let j = env
        .new_string(s)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut());
    crate::ffi::ada_string_free(ptr);
    j
}

/// Set the active obfuscation mode: "none"|"padding"|"shaping"|"websocket"|"fronting"|"auto".
#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_core_AdaCore_nativeSetBridgeMode(
    mut env: JNIEnv,
    _obj: JObject,
    handle: jlong,
    mode_str: JString,
) -> jint {
    let mode: String = match env.get_string(&mode_str) {
        Ok(s) => s.into(),
        Err(_) => return 1,
    };
    let mode_c = match CString::new(mode) {
        Ok(s) => s,
        Err(_) => return 1,
    };
    crate::ffi::ada_set_bridge_mode(handle as *mut AdaCoreHandle, mode_c.as_ptr()) as jint
}

/// Import a signed bridge manifest JSON payload.
#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_core_AdaCore_nativeImportBridgeManifestJson(
    mut env: JNIEnv,
    _obj: JObject,
    handle: jlong,
    manifest_json: JString,
    source: JString,
    trusted_public_key_hex: JString,
) -> jint {
    let manifest_json: String = match env.get_string(&manifest_json) {
        Ok(s) => s.into(),
        Err(_) => return 1,
    };
    let source: String = match env.get_string(&source) {
        Ok(s) => s.into(),
        Err(_) => return 1,
    };
    let trusted_public_key_hex: String = match env.get_string(&trusted_public_key_hex) {
        Ok(s) => s.into(),
        Err(_) => return 1,
    };

    let manifest_json_c = match CString::new(manifest_json) {
        Ok(s) => s,
        Err(_) => return 1,
    };
    let source_c = match CString::new(source) {
        Ok(s) => s,
        Err(_) => return 1,
    };
    let trusted_key_c = match CString::new(trusted_public_key_hex) {
        Ok(s) => s,
        Err(_) => return 1,
    };

    crate::ffi::ada_import_bridge_manifest_json(
        handle as *const AdaCoreHandle,
        manifest_json_c.as_ptr(),
        source_c.as_ptr(),
        trusted_key_c.as_ptr(),
    ) as jint
}

/// Import a signed bridge manifest from a URL.
#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_core_AdaCore_nativeImportBridgeManifestUrl(
    mut env: JNIEnv,
    _obj: JObject,
    handle: jlong,
    manifest_url: JString,
    trusted_public_key_hex: JString,
) -> jint {
    let manifest_url: String = match env.get_string(&manifest_url) {
        Ok(s) => s.into(),
        Err(_) => return 1,
    };
    let trusted_public_key_hex: String = match env.get_string(&trusted_public_key_hex) {
        Ok(s) => s.into(),
        Err(_) => return 1,
    };

    let manifest_url_c = match CString::new(manifest_url) {
        Ok(s) => s,
        Err(_) => return 1,
    };
    let trusted_key_c = match CString::new(trusted_public_key_hex) {
        Ok(s) => s,
        Err(_) => return 1,
    };

    crate::ffi::ada_import_bridge_manifest_url(
        handle as *const AdaCoreHandle,
        manifest_url_c.as_ptr(),
        trusted_key_c.as_ptr(),
    ) as jint
}

// ─── Pattern authentication JNI ───────────────────────────────────────────────

/// Create a new ADA Core instance from a visual pattern.
/// `cells` is a Kotlin ByteArray of 16 cell indices (0–63).
/// Returns a jlong handle, or 0 on failure.
#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_core_AdaCore_nativeCreateFromPattern(
    mut env: JNIEnv,
    _class: JClass,
    cells: jni::objects::JByteArray,
    display_name: JString,
    data_dir: JString,
    connection_profile: JString,
) -> jlong {
    let name: String = match env.get_string(&display_name) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };
    let dir: String = match env.get_string(&data_dir) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };
    let profile: String = match env.get_string(&connection_profile) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };
    let cells_u8: Vec<u8> = match env.convert_byte_array(&cells) {
        Ok(b) => b,
        Err(_) => return 0,
    };

    let name_c = match CString::new(name) {
        Ok(s) => s,
        Err(_) => return 0,
    };
    let dir_c = match CString::new(dir) {
        Ok(s) => s,
        Err(_) => return 0,
    };
    let profile_c = match CString::new(profile) {
        Ok(s) => s,
        Err(_) => return 0,
    };

    let handle = crate::ffi::ada_create_from_pattern_with_profile(
        cells_u8.as_ptr(),
        cells_u8.len(),
        name_c.as_ptr(),
        dir_c.as_ptr(),
        profile_c.as_ptr(),
    );
    handle as jlong
}

/// Verify that a pattern matches the stored identity.
/// `cells` is a Kotlin ByteArray of 16 cell indices.
/// Returns JNI_TRUE (1) if match, JNI_FALSE (0) otherwise.
#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_core_AdaCore_nativeVerifyPattern(
    mut env: JNIEnv,
    _obj: JObject,
    handle: jlong,
    cells: jni::objects::JByteArray,
) -> jboolean {
    let cells_u8: Vec<u8> = match env.convert_byte_array(&cells) {
        Ok(b) => b,
        Err(_) => return 0,
    };
    let result = crate::ffi::ada_verify_pattern(
        handle as *const AdaCoreHandle,
        cells_u8.as_ptr(),
        cells_u8.len(),
    );
    result as jboolean
}

/// Get the contact card JSON (for QR code).
/// Returns a Kotlin String or null on error.
#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_core_AdaCore_nativeGetContactCardJson(
    mut env: JNIEnv,
    _obj: JObject,
    handle: jlong,
) -> jstring {
    let ptr = crate::ffi::ada_get_contact_card_json(handle as *const AdaCoreHandle);
    if ptr.is_null() {
        return std::ptr::null_mut();
    }
    let s = CStr::from_ptr(ptr).to_str().unwrap_or("{}");
    let j = env
        .new_string(s)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut());
    crate::ffi::ada_string_free(ptr);
    j
}

/// Save a peer's public bundle from a QR contact card JSON string.
/// Returns 0 on success, 1 on error.
#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_core_AdaCore_nativeAddContactJson(
    mut env: JNIEnv,
    _obj: JObject,
    handle: jlong,
    contact_card_json: JString,
) -> jint {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let json_str: String = match env.get_string(&contact_card_json) {
            Ok(s) => s.into(),
            Err(_) => return 1,
        };
        let json_c = match CString::new(json_str) {
            Ok(s) => s,
            Err(_) => return 1,
        };
        let res = crate::ffi::ada_add_contact_json(handle as *const AdaCoreHandle, json_c.as_ptr());
        matches!(res, crate::ffi::AdaResult::Ok) as jint ^ 1 // 0 = success, 1 = fail
    }))
    .unwrap_or_else(|_| {
        tracing::error!("[jni] nativeAddContactJson panicked");
        1
    })
}

/// Add a custom iroh relay node at runtime.
/// `relay_url` is a relay URL string, e.g. `https://relay.example.com`.
/// Returns 0 on success, 1 on error.
#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_core_AdaCore_nativeAddRelayNode(
    mut env: JNIEnv,
    _obj: JObject,
    handle: jlong,
    relay_url: JString,
) -> jint {
    let url_str: String = match env.get_string(&relay_url) {
        Ok(s) => s.into(),
        Err(_) => return 1,
    };
    let url_c = match CString::new(url_str) {
        Ok(s) => s,
        Err(_) => return 1,
    };
    crate::ffi::ada_add_relay_node(handle as *const AdaCoreHandle, url_c.as_ptr()) as jint
}

/// Enable or disable relay-only routing at runtime.
/// `enabled` = 1 to enable, 0 to disable.
/// Returns 0 on success, non-zero on error.
#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_core_AdaCore_nativeSetRelayOnly(
    _env: JNIEnv,
    _obj: JObject,
    handle: jlong,
    enabled: jint,
) -> jint {
    crate::ffi::ada_set_relay_only(handle as *const AdaCoreHandle, enabled) as jint
}

/// Set the runtime connection profile.
/// Returns 0 on success, non-zero on error.
#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_core_AdaCore_nativeSetConnectionProfile(
    mut env: JNIEnv,
    _obj: JObject,
    handle: jlong,
    connection_profile: JString,
) -> jint {
    let profile: String = match env.get_string(&connection_profile) {
        Ok(s) => s.into(),
        Err(_) => return 1,
    };
    let profile_c = match CString::new(profile) {
        Ok(s) => s,
        Err(_) => return 1,
    };
    crate::ffi::ada_set_connection_profile(handle as *const AdaCoreHandle, profile_c.as_ptr())
        as jint
}

/// Set app execution state.
/// `in_background` = 1 for background (throttled), 0 for foreground (normal).
/// Returns 0 on success.
#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_core_AdaCore_nativeSetAppBackgroundState(
    _env: JNIEnv,
    _obj: JObject,
    handle: jlong,
    in_background: jint,
) -> jint {
    crate::ffi::ada_set_app_background_state(handle as *const AdaCoreHandle, in_background) as jint
}

// ─── Group chat JNI ───────────────────────────────────────────────────────────

/// Create a new group. Returns hex group-id string or null on error.
#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_core_AdaCore_nativeCreateGroup(
    mut env: JNIEnv,
    _obj: JObject,
    handle: jlong,
    group_name: JString,
) -> jstring {
    let name: String = match env.get_string(&group_name) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let name_c = match CString::new(name) {
        Ok(s) => s,
        Err(_) => return std::ptr::null_mut(),
    };
    let ptr = crate::ffi::ada_create_group(handle as *const AdaCoreHandle, name_c.as_ptr());
    if ptr.is_null() {
        return std::ptr::null_mut();
    }
    let s = CStr::from_ptr(ptr).to_str().unwrap_or("");
    let j = env
        .new_string(s)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut());
    crate::ffi::ada_string_free(ptr);
    j
}

/// Send a text message to a group. Returns 0 on success.
#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_core_AdaCore_nativeSendGroupText(
    mut env: JNIEnv,
    _obj: JObject,
    handle: jlong,
    group_id_hex: JString,
    text: JString,
) -> jint {
    let gid_str: String = match env.get_string(&group_id_hex) {
        Ok(s) => s.into(),
        Err(_) => return 1,
    };
    let text_str: String = match env.get_string(&text) {
        Ok(s) => s.into(),
        Err(_) => return 1,
    };
    let gid_c = match CString::new(gid_str) {
        Ok(s) => s,
        Err(_) => return 1,
    };
    let text_c = match CString::new(text_str) {
        Ok(s) => s,
        Err(_) => return 1,
    };
    crate::ffi::ada_send_group_text(
        handle as *const AdaCoreHandle,
        gid_c.as_ptr(),
        text_c.as_ptr(),
    ) as jint
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_core_AdaCore_nativeSendGroupReply(
    mut env: JNIEnv,
    _obj: JObject,
    handle: jlong,
    group_id_hex: JString,
    text: JString,
    reply_to_msg_id_hex: JString,
) -> jint {
    let gid_str: String = match env.get_string(&group_id_hex) {
        Ok(s) => s.into(),
        Err(_) => return 1,
    };
    let text_str: String = match env.get_string(&text) {
        Ok(s) => s.into(),
        Err(_) => return 1,
    };
    let reply_str: String = match env.get_string(&reply_to_msg_id_hex) {
        Ok(s) => s.into(),
        Err(_) => return 1,
    };

    let gid_c = match CString::new(gid_str) {
        Ok(s) => s,
        Err(_) => return 1,
    };
    let text_c = match CString::new(text_str) {
        Ok(s) => s,
        Err(_) => return 1,
    };
    let reply_c = match CString::new(reply_str) {
        Ok(s) => s,
        Err(_) => return 1,
    };

    crate::ffi::ada_send_group_reply(
        handle as *const AdaCoreHandle,
        gid_c.as_ptr(),
        text_c.as_ptr(),
        reply_c.as_ptr(),
    ) as jint
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_core_AdaCore_nativeSendGroupReaction(
    mut env: JNIEnv,
    _obj: JObject,
    handle: jlong,
    group_id_hex: JString,
    target_msg_id_hex: JString,
    emoji: JString,
) -> jint {
    let gid_str: String = match env.get_string(&group_id_hex) {
        Ok(s) => s.into(),
        Err(_) => return 1,
    };
    let target_str: String = match env.get_string(&target_msg_id_hex) {
        Ok(s) => s.into(),
        Err(_) => return 1,
    };
    let emoji_str: String = match env.get_string(&emoji) {
        Ok(s) => s.into(),
        Err(_) => return 1,
    };

    let gid_c = match CString::new(gid_str) {
        Ok(s) => s,
        Err(_) => return 1,
    };
    let target_c = match CString::new(target_str) {
        Ok(s) => s,
        Err(_) => return 1,
    };
    let emoji_c = match CString::new(emoji_str) {
        Ok(s) => s,
        Err(_) => return 1,
    };

    crate::ffi::ada_send_group_reaction(
        handle as *const AdaCoreHandle,
        gid_c.as_ptr(),
        target_c.as_ptr(),
        emoji_c.as_ptr(),
    ) as jint
}

// ─── Contact short-link (stateless — no handle required) ─────────────────────

/// Encode a contact-card JSON string into an opaque `ada://s/<token>` URL.
/// The token is XChaCha20-Poly1305 ciphertext so neither the peer ID nor key
/// material are visible in the link.
/// Returns the URL string or null on error.
#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_core_AdaCore_nativeEncodeShortLink(
    mut env: JNIEnv,
    _obj: JObject,
    json: JString,
) -> jstring {
    let json_str: String = match env.get_string(&json) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let url = crate::shortlink::encode(&json_str);
    env.new_string(url)
        .map(|s| s.into_raw())
        .unwrap_or_else(|_| std::ptr::null_mut())
}

/// Decode an `ada://s/<token>` URL back to the contact-card JSON string.
/// Returns null if the token is malformed or the authentication tag is invalid.
#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_core_AdaCore_nativeDecodeShortLink(
    mut env: JNIEnv,
    _obj: JObject,
    url: JString,
) -> jstring {
    let url_str: String = match env.get_string(&url) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    match crate::shortlink::decode(&url_str) {
        Some(json) => env
            .new_string(json)
            .map(|s| s.into_raw())
            .unwrap_or_else(|_| std::ptr::null_mut()),
        None => std::ptr::null_mut(),
    }
}

/// Invite a peer to a group. Returns 0 on success.
#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_core_AdaCore_nativeInviteToGroup(
    mut env: JNIEnv,
    _obj: JObject,
    handle: jlong,
    group_id_hex: JString,
    peer_id_b64: JString,
) -> jint {
    let gid_str: String = match env.get_string(&group_id_hex) {
        Ok(s) => s.into(),
        Err(_) => return 1,
    };
    let peer_str: String = match env.get_string(&peer_id_b64) {
        Ok(s) => s.into(),
        Err(_) => return 1,
    };
    let gid_c = match CString::new(gid_str) {
        Ok(s) => s,
        Err(_) => return 1,
    };
    let peer_c = match CString::new(peer_str) {
        Ok(s) => s,
        Err(_) => return 1,
    };
    crate::ffi::ada_invite_to_group(
        handle as *const AdaCoreHandle,
        gid_c.as_ptr(),
        peer_c.as_ptr(),
    ) as jint
}

/// Leave a group. Returns 0 on success.
#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_core_AdaCore_nativeLeaveGroup(
    mut env: JNIEnv,
    _obj: JObject,
    handle: jlong,
    group_id_hex: JString,
) -> jint {
    let gid_str: String = match env.get_string(&group_id_hex) {
        Ok(s) => s.into(),
        Err(_) => return 1,
    };
    let gid_c = match CString::new(gid_str) {
        Ok(s) => s,
        Err(_) => return 1,
    };
    crate::ffi::ada_leave_group(handle as *const AdaCoreHandle, gid_c.as_ptr()) as jint
}

/// Returns JSON array of all groups. Caller receives a Kotlin String.
#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_core_AdaCore_nativeGetGroupsJson(
    mut env: JNIEnv,
    _obj: JObject,
    handle: jlong,
) -> jstring {
    let ptr = crate::ffi::ada_get_groups_json(handle as *const AdaCoreHandle);
    if ptr.is_null() {
        return env
            .new_string("[]")
            .map(|s| s.into_raw())
            .unwrap_or(std::ptr::null_mut());
    }
    let s = CStr::from_ptr(ptr).to_str().unwrap_or("[]");
    let j = env
        .new_string(s)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut());
    crate::ffi::ada_string_free(ptr);
    j
}

/// Returns detailed JSON info for a group. Caller receives a Kotlin String or null.
#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_core_AdaCore_nativeGetGroupInfoJson(
    mut env: JNIEnv,
    _obj: JObject,
    handle: jlong,
    group_id_hex: JString,
) -> jstring {
    let gid_str: String = match env.get_string(&group_id_hex) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let gid_c = match CString::new(gid_str) {
        Ok(s) => s,
        Err(_) => return std::ptr::null_mut(),
    };
    let ptr = crate::ffi::ada_get_group_info_json(handle as *const AdaCoreHandle, gid_c.as_ptr());
    if ptr.is_null() {
        return std::ptr::null_mut();
    }
    let s = CStr::from_ptr(ptr).to_str().unwrap_or("{}");
    let j = env
        .new_string(s)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut());
    crate::ffi::ada_string_free(ptr);
    j
}

/// Start a group call (audio or video). Returns hex call-session-id or null on error.
#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_core_AdaCore_nativeStartGroupCall(
    mut env: JNIEnv,
    _obj: JObject,
    handle: jlong,
    group_id_hex: JString,
    offer_sdp: JString,
    has_video: jint,
) -> jstring {
    let gid_str: String = match env.get_string(&group_id_hex) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let offer_str: String = match env.get_string(&offer_sdp) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let gid_c = match CString::new(gid_str) {
        Ok(s) => s,
        Err(_) => return std::ptr::null_mut(),
    };
    let offer_c = match CString::new(offer_str) {
        Ok(s) => s,
        Err(_) => return std::ptr::null_mut(),
    };
    let ptr = crate::ffi::ada_start_group_call(
        handle as *const AdaCoreHandle,
        gid_c.as_ptr(),
        offer_c.as_ptr(),
        has_video,
    );
    if ptr.is_null() {
        return std::ptr::null_mut();
    }
    let s = CStr::from_ptr(ptr).to_str().unwrap_or("");
    let j = env
        .new_string(s)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut());
    crate::ffi::ada_string_free(ptr);
    j
}

/// Join an existing group call room by group/session id. Returns hex session-id or null on error.
#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_core_AdaCore_nativeJoinGroupCall(
    mut env: JNIEnv,
    _obj: JObject,
    handle: jlong,
    group_id_hex: JString,
    session_id_hex: JString,
    offer_sdp: JString,
    has_video: jint,
) -> jstring {
    let gid_str: String = match env.get_string(&group_id_hex) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let sid_str: String = match env.get_string(&session_id_hex) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let offer_str: String = match env.get_string(&offer_sdp) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let gid_c = match CString::new(gid_str) {
        Ok(s) => s,
        Err(_) => return std::ptr::null_mut(),
    };
    let sid_c = match CString::new(sid_str) {
        Ok(s) => s,
        Err(_) => return std::ptr::null_mut(),
    };
    let offer_c = match CString::new(offer_str) {
        Ok(s) => s,
        Err(_) => return std::ptr::null_mut(),
    };
    let ptr = crate::ffi::ada_join_group_call(
        handle as *const AdaCoreHandle,
        gid_c.as_ptr(),
        sid_c.as_ptr(),
        offer_c.as_ptr(),
        has_video,
    );
    if ptr.is_null() {
        return std::ptr::null_mut();
    }
    let s = CStr::from_ptr(ptr).to_str().unwrap_or("");
    let j = env
        .new_string(s)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut());
    crate::ffi::ada_string_free(ptr);
    j
}

/// Attach an already created pairwise call to a shared group room/session.
#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_core_AdaCore_nativeAttachCallToGroupRoom(
    mut env: JNIEnv,
    _obj: JObject,
    handle: jlong,
    call_id_hex: JString,
    group_id_hex: JString,
    session_id_hex: JString,
    has_video: jint,
) -> jint {
    let call_id_str: String = match env.get_string(&call_id_hex) {
        Ok(s) => s.into(),
        Err(_) => return 1,
    };
    let group_id_str: String = match env.get_string(&group_id_hex) {
        Ok(s) => s.into(),
        Err(_) => return 1,
    };
    let session_id_str: String = match env.get_string(&session_id_hex) {
        Ok(s) => s.into(),
        Err(_) => return 1,
    };
    let call_bytes = match hex::decode(call_id_str) {
        Ok(bytes) if bytes.len() == 16 => bytes,
        _ => return 1,
    };
    let group_c = match CString::new(group_id_str) {
        Ok(s) => s,
        Err(_) => return 1,
    };
    let session_c = match CString::new(session_id_str) {
        Ok(s) => s,
        Err(_) => return 1,
    };
    let res = crate::ffi::ada_attach_call_to_group_room(
        handle as *const AdaCoreHandle,
        call_bytes.as_ptr(),
        group_c.as_ptr(),
        session_c.as_ptr(),
        has_video,
    );
    matches!(res, crate::ffi::AdaResult::Ok) as jint ^ 1
}

/// Hang up every pairwise call associated with a group room/session id.
#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_core_AdaCore_nativeHangupGroupCallRoom(
    mut env: JNIEnv,
    _obj: JObject,
    handle: jlong,
    session_id_hex: JString,
) -> jint {
    let sid_str: String = match env.get_string(&session_id_hex) {
        Ok(s) => s.into(),
        Err(_) => return 1,
    };
    let sid_c = match CString::new(sid_str) {
        Ok(s) => s,
        Err(_) => return 1,
    };
    let res =
        crate::ffi::ada_hangup_group_call_room(handle as *const AdaCoreHandle, sid_c.as_ptr());
    matches!(res, crate::ffi::AdaResult::Ok) as jint ^ 1
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_core_AdaCore_nativeStartWebRtcProxy(
    mut env: JNIEnv,
    _obj: JObject,
    handle: jlong,
    peer_b64: JString,
) -> jint {
    let peer_str: String = match env.get_string(&peer_b64) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };
    let peer_c = match CString::new(peer_str) {
        Ok(c) => c,
        Err(_) => return 0,
    };

    let port = crate::ffi::ada_start_webrtc_proxy(handle as *const AdaCoreHandle, peer_c.as_ptr());

    port as jint
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_ada_messenger_core_AdaCore_nativeStopWebRtcProxy(
    mut env: JNIEnv,
    _obj: JObject,
    handle: jlong,
    peer_b64: JString,
) {
    let peer_str: String = match env.get_string(&peer_b64) {
        Ok(s) => s.into(),
        Err(_) => return,
    };
    let peer_c = match CString::new(peer_str) {
        Ok(c) => c,
        Err(_) => return,
    };

    crate::ffi::ada_stop_webrtc_proxy(handle as *const AdaCoreHandle, peer_c.as_ptr());
}
