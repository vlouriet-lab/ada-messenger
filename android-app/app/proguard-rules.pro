# ── ADA Messenger ProGuard Rules ──────────────────────────────────────────────

# ── JNI / Rust native bridge ─────────────────────────────────────────────────
# Keep all native method declarations and the classes that contain them.
-keep class com.ada.messenger.core.AdaCore { *; }
-keepclassmembers class com.ada.messenger.core.AdaCore {
    native <methods>;
}

# ── WebRTC ────────────────────────────────────────────────────────────────────
# The WebRTC AAR uses JNI callbacks from C++ to Java; obfuscation breaks them.
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# ── ZXing (QR code) ──────────────────────────────────────────────────────────
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# ── Coil (image loading) ─────────────────────────────────────────────────────
-dontwarn coil.**

# ── Kotlin serialization / coroutines ─────────────────────────────────────────
-dontwarn kotlinx.coroutines.**
-keep class kotlinx.coroutines.** { *; }

# ── Compose — keep @Composable metadata ───────────────────────────────────────
-dontwarn androidx.compose.**

# ── General: keep R8 from stripping ViewModel factories ───────────────────────
-keep class * extends androidx.lifecycle.ViewModel { <init>(...); }
-keep class * extends androidx.lifecycle.ViewModelProvider$Factory { *; }
# ── javax.crypto (used by AppLockManager AES-GCM / PBKDF2) ───────────────
-keep class javax.crypto.** { *; }
-dontwarn javax.crypto.**

# ── AndroidX Security (EncryptedSharedPreferences) ────────────────────────
-keep class androidx.security.crypto.** { *; }
-dontwarn androidx.security.crypto.**

# ── Tink (underlying library for EncryptedSharedPreferences) ──────────────
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**