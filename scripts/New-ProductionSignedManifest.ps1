param(
    [Parameter(Mandatory = $true)]
    [string]$BridgeHost,

    [string]$OutputDir = "production",
    [string]$BridgeId = "cf-worker-primary",
    [int]$Port = 443,
    [string]$Hostname = "",
    [int]$TtlSecs = 3600,
    [int]$MaxAttachmentBytes = 262144,
    [int]$Priority = 220,
    [switch]$NoRealtimeCalls
)

$ErrorActionPreference = "Stop"
$RepoRoot = Split-Path -Parent $PSScriptRoot

Push-Location $RepoRoot
try {
    $seedPath = Join-Path $OutputDir "manifest-signing-seed.hex"
    $publicKeyPath = Join-Path $OutputDir "manifest-public-key.hex"
    $fingerprintPath = Join-Path $OutputDir "bridge-fingerprint.hex"
    $payloadPath = Join-Path $OutputDir "manifest-payload.json"
    $signedPath = Join-Path $OutputDir "signed-manifest.json"

    foreach ($path in @($seedPath, $publicKeyPath, $fingerprintPath)) {
        if (-not (Test-Path $path)) {
            throw "$path is missing. Run .\scripts\New-ProductionBootstrapSecrets.ps1 first."
        }
    }

    $seed = (Get-Content $seedPath -Raw).Trim()
    $publicKey = (Get-Content $publicKeyPath -Raw).Trim()
    $fingerprint = (Get-Content $fingerprintPath -Raw).Trim()

    $manifestArgs = @(
        "-BridgeHost", $BridgeHost,
        "-FingerprintHex", $fingerprint,
        "-BridgeId", $BridgeId,
        "-Port", $Port,
        "-TtlSecs", $TtlSecs,
        "-MaxAttachmentBytes", $MaxAttachmentBytes,
        "-Priority", $Priority,
        "-OutputPath", $payloadPath
    )
    if (-not [string]::IsNullOrWhiteSpace($Hostname)) {
        $manifestArgs += @("-Hostname", $Hostname)
    }
    if ($NoRealtimeCalls) {
        $manifestArgs += "-NoRealtimeCalls"
    }
    & .\scripts\New-BridgeManifestPayload.ps1 @manifestArgs

    $oldSeed = $env:ADA_BRIDGE_SIGNING_SEED
    try {
        $env:ADA_BRIDGE_SIGNING_SEED = $seed
        & cargo run --quiet --manifest-path ada-core\Cargo.toml --bin sign_manifest -- $payloadPath $signedPath
        if ($LASTEXITCODE -ne 0) { throw "sign_manifest failed" }
    } finally {
        if ($null -ne $oldSeed) {
            $env:ADA_BRIDGE_SIGNING_SEED = $oldSeed
        } else {
            Remove-Item Env:ADA_BRIDGE_SIGNING_SEED -ErrorAction SilentlyContinue
        }
    }

    & cargo run --quiet --manifest-path ada-core\Cargo.toml --bin verify_manifest -- $signedPath $publicKey
    if ($LASTEXITCODE -ne 0) { throw "verify_manifest failed" }

    Write-Host "Signed manifest ready: $signedPath" -ForegroundColor Green
    Write-Host "Use BRIDGE_FINGERPRINT_HEX=$fingerprint for the serverless bridge Worker." -ForegroundColor Cyan
    Write-Host "Use ADA_BUILTIN_MANIFEST_PUBLIC_KEYS=$publicKey when building the APK." -ForegroundColor Cyan
} finally {
    Pop-Location
}