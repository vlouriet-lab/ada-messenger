# Build ada-core as Android shared libraries (.so) and copy to app/src/main/jniLibs/
#
# Requires:
#   - Android NDK (set ANDROID_NDK_HOME or install via sdkmanager)
#   - cargo-ndk: cargo install cargo-ndk
#   - Rust Android targets:
#       rustup target add aarch64-linux-android x86_64-linux-android
#
# Usage:
#   ./build-android.ps1            # debug build
#   ./build-android.ps1 -Release   # release build

param (
    [switch]$Release,
    [switch]$RequireBootstrapManifest,
    [string]$JniLibsDir,
    [string]$Features
)

$ErrorActionPreference = "Stop"

$BuildProfile  = if ($Release) { "release" } else { "debug" }
$CargoFlag = if ($Release) { "--release" } else { "" }
# Default: arm64-v8a only (covers ~95% of Android devices, keeps APK small).
# x86_64 only needed for emulator testing — add "-Targets x86_64" if required.
$Targets  = @("arm64-v8a")
$RustTargets = @{
    "arm64-v8a" = "aarch64-linux-android"
    "x86_64"    = "x86_64-linux-android"
}
# Release builds default to the "mobile" feature set: jni-bindings + sqlcipher + ffi + proto.
# Debug builds default to "mobile-dev" (plain bundled SQLite, no OpenSSL needed) for fast iteration.
$CargoFeatures = if ($Features) {
    $Features
} elseif ($Release) {
    "mobile"
} else {
    "mobile-dev"
}

$ScriptDir   = $PSScriptRoot
$CoreDir     = Join-Path $ScriptDir "..\ada-core"
$JniLibsDir  = if ($JniLibsDir) { $JniLibsDir } else { Join-Path $ScriptDir "app\src\main\jniLibs" }
$PrimarySo   = "libada_core.so"
$PreviousRustFlags = $env:RUSTFLAGS
$AndroidPageSizeRustFlag = "-C link-arg=-Wl,-z,max-page-size=16384"

$ManifestUrls = if ($null -ne $env:ADA_BUILTIN_MANIFEST_URLS) { $env:ADA_BUILTIN_MANIFEST_URLS.Trim() } else { "" }
$ManifestPublicKeys = if ($null -ne $env:ADA_BUILTIN_MANIFEST_PUBLIC_KEYS) { $env:ADA_BUILTIN_MANIFEST_PUBLIC_KEYS.Trim() } else { "" }
$HasBootstrapManifest = -not [string]::IsNullOrWhiteSpace($ManifestUrls) -and -not [string]::IsNullOrWhiteSpace($ManifestPublicKeys)

if ($HasBootstrapManifest) {
    Write-Host "==> Embedding bootstrap manifest URL/key from environment" -ForegroundColor Cyan
} else {
    $message = "ADA_BUILTIN_MANIFEST_URLS and ADA_BUILTIN_MANIFEST_PUBLIC_KEYS are not both set; fresh-install Cloudflare bootstrap will be disabled in this native build."
    if ($RequireBootstrapManifest) {
        throw $message
    }
    Write-Warning $message
}

if ([string]::IsNullOrWhiteSpace($env:RUSTFLAGS)) {
    $env:RUSTFLAGS = $AndroidPageSizeRustFlag
} elseif ($env:RUSTFLAGS -notlike "*max-page-size=16384*") {
    $env:RUSTFLAGS = "$env:RUSTFLAGS $AndroidPageSizeRustFlag"
}

Write-Host "==> Building ada-core for Android ($BuildProfile)" -ForegroundColor Cyan

Push-Location $CoreDir
try {
    foreach ($abi in $Targets) {
        $rustTarget = $RustTargets[$abi]
        $AbiOutDir = Join-Path $JniLibsDir $abi

        Write-Host "  -> $abi ($rustTarget)" -ForegroundColor Yellow

        # Clean previously copied .so files for this ABI to avoid stale/duplicate libs.
        if (Test-Path $AbiOutDir) {
            Get-ChildItem $AbiOutDir -File -Filter "*.so" -ErrorAction SilentlyContinue |
                Remove-Item -Force -ErrorAction SilentlyContinue
        }

        # Build with cargo-ndk
        $cargoArgs = @("-t", $abi, "--platform", "26", "-o", $JniLibsDir)
        if ($CargoFlag) { $cargoArgs += "build"; $cargoArgs += "--release" }
        else            { $cargoArgs += "build" }

        & cargo ndk @cargoArgs --features $CargoFeatures
        if ($LASTEXITCODE -ne 0) { throw "cargo ndk failed for $abi" }

        # Keep only the primary JNI entrypoint library in app/src/main/jniLibs.
        # Rust sidecar dylibs (hash-suffixed) are not required by libada_core.so.
        if (Test-Path $AbiOutDir) {
            Get-ChildItem $AbiOutDir -File -Filter "*.so" |
                Where-Object { $_.Name -ne $PrimarySo } |
                Remove-Item -Force -ErrorAction SilentlyContinue
        }
    }
} finally {
    Pop-Location
    $env:RUSTFLAGS = $PreviousRustFlags
}

Write-Host "==> Done. .so files placed in $JniLibsDir" -ForegroundColor Green
Write-Host "    Now open android-app in Android Studio and run on device/emulator."
