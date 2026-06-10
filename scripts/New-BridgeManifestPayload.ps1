param(
    [Parameter(Mandatory = $true)]
    [string]$BridgeHost,

    [Parameter(Mandatory = $true)]
    [string]$FingerprintHex,

    [string]$BridgeId = "cf-worker-primary",
    [int]$Port = 443,
    [string]$Hostname = "",
    [int]$TtlSecs = 3600,
    [int]$MaxAttachmentBytes = 262144,
    [int]$Priority = 220,
    [string]$OutputPath = "production\manifest-payload.json",
    [switch]$NoRealtimeCalls
)

$ErrorActionPreference = "Stop"

function Normalize-Hex64([string]$Value, [string]$Name) {
    $normalized = ($Value -replace ':', '').Trim().ToLowerInvariant()
    if ($normalized -notmatch '^[0-9a-f]{64}$') {
        throw "$Name must be exactly 32 bytes encoded as 64 hex characters"
    }
    if ($normalized -match '^0+$') {
        throw "$Name must not be all zero"
    }
    return $normalized
}

function Assert-CleanToken([string]$Value, [string]$Name) {
    if ([string]::IsNullOrWhiteSpace($Value)) {
        throw "$Name must not be empty"
    }
    if ($Value -match '\s') {
        throw "$Name must not contain whitespace"
    }
}

Assert-CleanToken $BridgeHost "BridgeHost"
Assert-CleanToken $BridgeId "BridgeId"
if ($Port -lt 1 -or $Port -gt 65535) { throw "Port must be 1..65535" }
if ($TtlSecs -lt 60) { throw "TtlSecs should be at least 60 seconds" }
if ($MaxAttachmentBytes -lt 1) { throw "MaxAttachmentBytes must be positive" }
if ($Priority -lt 0 -or $Priority -gt 255) { throw "Priority must be 0..255" }

$fingerprint = Normalize-Hex64 $FingerprintHex "FingerprintHex"
$effectiveHostname = if ([string]::IsNullOrWhiteSpace($Hostname)) { $BridgeHost.Trim() } else { $Hostname.Trim() }
Assert-CleanToken $effectiveHostname "Hostname"

$epoch = [datetime]'1970-01-01T00:00:00Z'
$issuedAtMs = [int64](([datetime]::UtcNow - $epoch).TotalMilliseconds)

$payload = [ordered]@{
    version = 1
    issued_at_ms = $issuedAtMs
    ttl_secs = $TtlSecs
    max_attachment_bytes = $MaxAttachmentBytes
    supports_realtime_calls = (-not $NoRealtimeCalls.IsPresent)
    bridges = @(
        [ordered]@{
            id = $BridgeId.Trim()
            address = $BridgeHost.Trim()
            port = $Port
            protocol = "websocket"
            hostname = $effectiveHostname
            fingerprint_hex = $fingerprint
            priority = $Priority
            wire_format = "json"
        }
    )
}

$outDir = Split-Path -Parent $OutputPath
if (-not [string]::IsNullOrWhiteSpace($outDir) -and -not (Test-Path $outDir)) {
    New-Item -ItemType Directory -Path $outDir | Out-Null
}

$json = $payload | ConvertTo-Json -Depth 12
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$absoluteOutputPath = [System.IO.Path]::GetFullPath($OutputPath)
[System.IO.File]::WriteAllText($absoluteOutputPath, $json, $utf8NoBom)
Write-Host "Wrote manifest payload to $OutputPath" -ForegroundColor Green
Write-Host "Next: set ADA_BRIDGE_SIGNING_SEED in your shell, then run sign_manifest." -ForegroundColor Cyan