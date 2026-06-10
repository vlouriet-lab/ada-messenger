param(
    [string]$OutputDir = "production",
    [switch]$Force
)

$ErrorActionPreference = "Stop"
$RepoRoot = Split-Path -Parent $PSScriptRoot

function New-RandomHex32 {
    $bytes = New-Object byte[] 32
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $rng.GetBytes($bytes)
    } finally {
        $rng.Dispose()
    }
    return (($bytes | ForEach-Object { $_.ToString("x2") }) -join "")
}

function Write-SecretTextNoBom([string]$Path, [string]$Value) {
    $encoding = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText([System.IO.Path]::GetFullPath($Path), $Value, $encoding)
}

Push-Location $RepoRoot
try {
    if (-not (Test-Path $OutputDir)) {
        New-Item -ItemType Directory -Path $OutputDir | Out-Null
    }

    $seedPath = Join-Path $OutputDir "manifest-signing-seed.hex"
    $publicKeyPath = Join-Path $OutputDir "manifest-public-key.hex"
    $fingerprintPath = Join-Path $OutputDir "bridge-fingerprint.hex"

    if ((Test-Path $seedPath) -and -not $Force) {
        $seed = (Get-Content $seedPath -Raw).Trim()
    } else {
        $seed = New-RandomHex32
        Write-SecretTextNoBom $seedPath $seed
    }

    if ((Test-Path $fingerprintPath) -and -not $Force) {
        $fingerprint = (Get-Content $fingerprintPath -Raw).Trim()
    } else {
        $fingerprint = New-RandomHex32
        Write-SecretTextNoBom $fingerprintPath $fingerprint
    }

    $oldSeed = $env:ADA_BRIDGE_SIGNING_SEED
    try {
        $env:ADA_BRIDGE_SIGNING_SEED = $seed
        $publicKeyOutput = (& cargo run --quiet --manifest-path ada-core\Cargo.toml --bin derive_manifest_public_key 2>&1) | Out-String
        if ($LASTEXITCODE -ne 0) {
            throw "derive_manifest_public_key failed: $publicKeyOutput"
        }
        $publicKey = $publicKeyOutput.Trim()
        if ($publicKey -notmatch '^[0-9a-f]{64}$') {
            throw "derive_manifest_public_key returned an invalid public key: $publicKey"
        }
        Write-SecretTextNoBom $publicKeyPath $publicKey
    } finally {
        if ($null -ne $oldSeed) {
            $env:ADA_BRIDGE_SIGNING_SEED = $oldSeed
        } else {
            Remove-Item Env:ADA_BRIDGE_SIGNING_SEED -ErrorAction SilentlyContinue
        }
    }

    Write-Host "Generated/loaded production bootstrap material in $OutputDir" -ForegroundColor Green
    Write-Host "BRIDGE_FINGERPRINT_HEX=$fingerprint"
    Write-Host "ADA_BUILTIN_MANIFEST_PUBLIC_KEYS=$publicKey"
    Write-Host "Secret signing seed saved to $seedPath; do not commit or paste it into chat." -ForegroundColor Yellow
} finally {
    Pop-Location
}