param(
    [string]$SignedManifestPath = "production\signed-manifest.json",
    [string]$ManifestPublicKeys = "",
    [switch]$RunWorkerChecks,
    [switch]$RunWorkerDryRun,
    [switch]$Strict
)

$ErrorActionPreference = "Stop"
$RepoRoot = Split-Path -Parent $PSScriptRoot
$Failures = New-Object System.Collections.Generic.List[string]

function Add-Failure([string]$Message) {
    $Failures.Add($Message) | Out-Null
    Write-Warning $Message
}

function Require-Env([string]$Name) {
    $value = [Environment]::GetEnvironmentVariable($Name)
    if ([string]::IsNullOrWhiteSpace($value)) {
        Add-Failure "$Name is not set"
    } else {
        Write-Host "$Name is set" -ForegroundColor Green
    }
}

Push-Location $RepoRoot
try {
    Require-Env "ADA_BUILTIN_MANIFEST_URLS"
    Require-Env "ADA_BUILTIN_MANIFEST_PUBLIC_KEYS"

    if ([string]::IsNullOrWhiteSpace($ManifestPublicKeys)) {
        $ManifestPublicKeys = [Environment]::GetEnvironmentVariable("ADA_BUILTIN_MANIFEST_PUBLIC_KEYS")
    }

    if (Test-Path $SignedManifestPath) {
        if ([string]::IsNullOrWhiteSpace($ManifestPublicKeys)) {
            Add-Failure "Cannot verify $SignedManifestPath without manifest public keys"
        } else {
            & cargo run --manifest-path ada-core\Cargo.toml --bin verify_manifest -- $SignedManifestPath $ManifestPublicKeys
            if ($LASTEXITCODE -ne 0) { Add-Failure "verify_manifest failed" }
        }
    } else {
        Add-Failure "$SignedManifestPath does not exist"
    }

    $bridgeWrangler = "cf-workers\serverless-bridge-worker\wrangler.toml"
    $bridgeToml = if (Test-Path $bridgeWrangler) { Get-Content $bridgeWrangler -Raw } else { "" }
    if ($bridgeToml -match 'BRIDGE_FINGERPRINT_HEX\s*=\s*""') {
        Add-Failure "serverless bridge wrangler.toml has empty BRIDGE_FINGERPRINT_HEX; set it in deployed Worker vars or update the file before deploy"
    }

    if ($RunWorkerChecks -or $RunWorkerDryRun) {
        $npm = Get-Command npm -ErrorAction SilentlyContinue
        if ($null -eq $npm) {
            Add-Failure "npm is not available in PATH"
        } else {
            foreach ($workerDir in @("cf-workers\serverless-bridge-worker", "cf-workers\manifest-worker")) {
                Push-Location $workerDir
                try {
                    if (-not (Test-Path "node_modules")) { & npm install }
                    & npm run check
                    if ($LASTEXITCODE -ne 0) { Add-Failure "npm run check failed in $workerDir" }
                    if ($RunWorkerDryRun) {
                        & npm run dry-run
                        if ($LASTEXITCODE -ne 0) { Add-Failure "npm run dry-run failed in $workerDir" }
                    }
                } finally {
                    Pop-Location
                }
            }
        }
    }

    if ($Failures.Count -gt 0) {
        Write-Warning "$($Failures.Count) production bootstrap check(s) failed"
        if ($Strict) { exit 1 }
    } else {
        Write-Host "Production bootstrap preflight OK" -ForegroundColor Green
    }
} finally {
    Pop-Location
}