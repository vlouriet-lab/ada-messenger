<#
.SYNOPSIS
    Builds the ADA Messenger Windows installer and signs it with a code-signing certificate.

.DESCRIPTION
    Steps:
      1. Reads version from android-app/version.properties
      2. Builds ada_core.dll via Cargo (--features mobile-dev)
      3. Builds Compose Desktop distributable via Gradle
      4. Copies ada_core.dll into the distributable directory
      5. Creates releases\ directory and runs makensis
      6. Creates a self-signed code-signing certificate (if not already present)
      7. Signs the installer .exe with signtool

    After signing: Windows SmartScreen shows a one-time "More info → Run anyway"
    warning. Without an EV/OV certificate from a CA, this is expected behaviour.

.PARAMETER NsisPath
    Override path to makensis.exe. Auto-detected if omitted.

.PARAMETER SkipCargoBuild
    Skip cargo build and reuse existing ada_core.dll.

.PARAMETER SkipGradleBuild
    Skip Gradle createDistributable and reuse existing distributable directory.

.PARAMETER SkipSigning
    Do not sign the installer (useful for CI without cert access).

.EXAMPLE
    # Full build + sign:
    .\build-installer-windows.ps1

    # Reuse existing DLL and distributable, only repackage + sign:
    .\build-installer-windows.ps1 -SkipCargoBuild -SkipGradleBuild
#>
[CmdletBinding()]
param(
    [string] $NsisPath       = "",
    [switch] $SkipCargoBuild,
    [switch] $SkipGradleBuild,
    [switch] $SkipSigning
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# - Paths -
$root       = $PSScriptRoot
$androidDir = Join-Path $root "android-app"
$adaCoreDir = Join-Path $root "ada-core"
$gradlew    = Join-Path $androidDir "gradlew.bat"
$dllSrc     = Join-Path $adaCoreDir "target\debug\ada_core.dll"
$distRoot   = Join-Path $androidDir "desktopApp\build\compose\binaries\main\app"
$distDir    = Join-Path $distRoot   "ADA Messenger"
$nsisScript = Join-Path $root       "installer.nsi"
$releasesDir= Join-Path $root       "releases"
$versionFile= Join-Path $androidDir "version.properties"

# - Read version -
$vp = @{}
if (Test-Path $versionFile) {
    Get-Content $versionFile | Where-Object { $_ -match '^\s*[^#]' } | ForEach-Object {
        $parts = $_ -split '=', 2
        if ($parts.Count -eq 2) { $vp[$parts[0].Trim()] = $parts[1].Trim() }
    }
}
$verMajor = if ($vp.ContainsKey('VERSION_MAJOR') -and $vp['VERSION_MAJOR']) { $vp['VERSION_MAJOR'] } else { '0' }
$verMinor = if ($vp.ContainsKey('VERSION_MINOR') -and $vp['VERSION_MINOR']) { $vp['VERSION_MINOR'] } else { '3' }
$verCode  = if ($vp.ContainsKey('VERSION_CODE')  -and $vp['VERSION_CODE'])  { $vp['VERSION_CODE']  } else { '1' }
$version  = "$verMajor.$verMinor.$verCode"

Write-Host ""
Write-Host "=" -ForegroundColor Cyan
Write-Host "  ADA Messenger $version — Windows Installer Build"      -ForegroundColor Cyan
Write-Host "=" -ForegroundColor Cyan
Write-Host ""

# - Locate Java (required for Gradle + jpackage) -
# jpackage.exe is required for createDistributable — NOT present in Android Studio JBR.
# Prefer a full JDK 21 (Eclipse Temurin), then Microsoft JDK, then JAVA_HOME.
$jdkCandidates = @(
    (Get-ChildItem "C:\Program Files\Eclipse Adoptium" -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match 'jdk' } | Sort-Object Name -Descending |
        Select-Object -First 1 -ExpandProperty FullName),
    (Get-ChildItem "C:\Program Files\Microsoft" -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match 'jdk' } | Sort-Object Name -Descending |
        Select-Object -First 1 -ExpandProperty FullName),
    $env:JAVA_HOME,
    "C:\Program Files\Android\Android Studio\jbr"
)
$javaHome = $jdkCandidates | Where-Object { $_ -and (Test-Path "$_\bin\jpackage.exe") } | Select-Object -First 1
if (-not $javaHome) {
    $javaHome = $jdkCandidates | Where-Object { $_ -and (Test-Path "$_\bin\java.exe") } | Select-Object -First 1
}
if (-not $javaHome) {
    Write-Error "Java not found. Install JDK 21: winget install EclipseAdoptium.Temurin.21.JDK"
    exit 1
}
$env:JAVA_HOME = $javaHome
$env:PATH = "$javaHome\bin;$env:PATH"
Write-Host "Java : $javaHome"

# - Locate NSIS -
if (-not $NsisPath) {
    $NsisPath = @(
        "C:\Program Files (x86)\NSIS\makensis.exe",
        "C:\Program Files\NSIS\makensis.exe"
    ) | Where-Object { Test-Path $_ } | Select-Object -First 1

    if (-not $NsisPath) {
        $cmd = Get-Command makensis.exe -ErrorAction SilentlyContinue
        if ($cmd) { $NsisPath = $cmd.Source }
    }
}
if (-not $NsisPath -or -not (Test-Path $NsisPath)) {
    Write-Error @"

makensis.exe not found.
Install NSIS 3 from: https://nsis.sourceforge.io/Download
Then re-run this script (or pass -NsisPath 'C:\path\to\makensis.exe').
"@
    exit 1
}
Write-Host "NSIS : $NsisPath"
Write-Host ""

# - Step 1: Build ada_core.dll -
Write-Host "[1/4] Building ada_core.dll (Rust, mobile-dev)..." -ForegroundColor Green
if ($SkipCargoBuild -and (Test-Path $dllSrc)) {
    Write-Host "      Skipping — reusing existing DLL" -ForegroundColor DarkGray
    Write-Host "      $dllSrc"
} else {
    & cargo build `
        --manifest-path "$adaCoreDir\Cargo.toml" `
        --no-default-features `
        --features mobile-dev `
        --message-format short
    if ($LASTEXITCODE -ne 0) { Write-Error "Cargo build failed (exit $LASTEXITCODE)"; exit 1 }
    Write-Host "      OK: $dllSrc" -ForegroundColor Green
}

# - Step 2: Build Compose Desktop distributable -
Write-Host ""
Write-Host "[2/4] Building Compose Desktop distributable..." -ForegroundColor Green
$launcherExe = Join-Path $distDir "ADA Messenger.exe"
if ($SkipGradleBuild -and (Test-Path $launcherExe)) {
    Write-Host "      Skipping — reusing existing distributable" -ForegroundColor DarkGray
    Write-Host "      $distDir"
} else {
    & $gradlew `
        -p $androidDir `
        ":desktopApp:createDistributable" `
        "-PadaCoreFeatures=mobile-dev" `
        "-PadaCoreAllowDevStorage=true" `
        "--console=plain" `
        "--no-daemon"
    if ($LASTEXITCODE -ne 0) { Write-Error "Gradle createDistributable failed"; exit 1 }

    if (-not (Test-Path $launcherExe)) {
        Write-Error "Distributable launcher not found at: $launcherExe"
        exit 1
    }
    Write-Host "      OK: $distDir" -ForegroundColor Green
}

# Copy DLL into the distributable root (next to ADA Messenger.exe).
# jpackage launchers add the install directory to java.library.path on Windows,
# so System.loadLibrary("ada_core") picks it up without any JVM arg changes.
$dllDest = Join-Path $distDir "ada_core.dll"
Copy-Item -Force $dllSrc $dllDest
Write-Host "      Copied ada_core.dll → distributable root"

# - Step 3: Package with NSIS -
Write-Host ""
Write-Host "[3/4] Running NSIS..." -ForegroundColor Green
New-Item -ItemType Directory -Force -Path $releasesDir | Out-Null
$outExe = Join-Path $releasesDir "ADA-Messenger-Setup-$version.exe"

# Delete stale output so NSIS error is obvious if it fails silently
if (Test-Path $outExe) { Remove-Item $outExe }

Push-Location $root
try {
    & $NsisPath "/DVERSION=$version" $nsisScript
    if ($LASTEXITCODE -ne 0) { Write-Error "makensis exited with $LASTEXITCODE"; exit 1 }
} finally {
    Pop-Location
}

if (-not (Test-Path $outExe)) {
    Write-Error "Expected installer not found: $outExe"
    exit 1
}
$sizeMb = [math]::Round((Get-Item $outExe).Length / 1MB, 1)
Write-Host "      OK: $outExe ($sizeMb MB)" -ForegroundColor Green

# - Step 4: Code-sign the installer -
Write-Host ""
Write-Host "[4/4] Code-signing..." -ForegroundColor Green

if ($SkipSigning) {
    Write-Host "      Skipped (-SkipSigning)" -ForegroundColor DarkGray
} else {
    # Locate signtool.exe (Windows SDK)
    $signtool = Get-ChildItem "C:\Program Files (x86)\Windows Kits\10\bin" `
        -Recurse -Filter "signtool.exe" -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -match "x64" } |
        Sort-Object FullName -Descending |
        Select-Object -First 1 -ExpandProperty FullName

    if (-not $signtool) {
        $cmd = Get-Command signtool.exe -ErrorAction SilentlyContinue
        if ($cmd) { $signtool = $cmd.Source }
    }

    if (-not $signtool) {
        Write-Warning @"
signtool.exe not found — installer will not be signed.
To enable signing, install Windows SDK:
  https://developer.microsoft.com/en-us/windows/downloads/windows-sdk/
"@
    } else {
        Write-Host "      signtool: $signtool"

        # Reuse or create a self-signed code-signing certificate.
        # The certificate is stored in the current user's personal cert store.
        $certSubject = "CN=ADA Messenger, O=ADA, C=RU"
        $cert = Get-ChildItem Cert:\CurrentUser\My |
            Where-Object {
                $_.Subject -eq $certSubject -and
                $_.HasPrivateKey -and
                $_.NotAfter -gt (Get-Date).AddDays(30)
            } |
            Sort-Object NotAfter -Descending |
            Select-Object -First 1

        if (-not $cert) {
            Write-Host "      Creating self-signed code-signing certificate..."
            $cert = New-SelfSignedCertificate `
                -Type CodeSigning `
                -Subject $certSubject `
                -HashAlgorithm SHA256 `
                -CertStoreLocation "Cert:\CurrentUser\My" `
                -NotAfter (Get-Date).AddYears(5)
            Write-Host "      Created: $($cert.Thumbprint)"
        } else {
            Write-Host "      Using existing cert: $($cert.Thumbprint) (expires $($cert.NotAfter.ToString('yyyy-MM-dd')))"
        }

        # Sign with SHA-256 and a public timestamp authority.
        # DigiCert's free RFC 3161 timestamp server; fallback to Sectigo.
        $timestampUrls = @(
            "http://timestamp.digicert.com",
            "http://timestamp.sectigo.com"
        )
        $signed = $false
        foreach ($tsUrl in $timestampUrls) {
            Write-Host "      Signing (timestamp: $tsUrl)..."
            & $signtool sign `
                /sha1 $cert.Thumbprint `
                /fd   SHA256 `
                /tr   $tsUrl `
                /td   SHA256 `
                /d    "ADA Messenger" `
                $outExe
            if ($LASTEXITCODE -eq 0) { $signed = $true; break }
            Write-Warning "Signing attempt failed with $tsUrl"
        }

        if ($signed) {
            Write-Host "      Signed successfully." -ForegroundColor Green
        } else {
            Write-Warning "All signing attempts failed. The installer is valid but unsigned."
            Write-Warning "Possible causes: no internet access to timestamp servers, or cert issue."
        }
    }
}

# - Summary -
Write-Host ""
Write-Host "=" -ForegroundColor Cyan
Write-Host "  Done: $outExe" -ForegroundColor Cyan
Write-Host "  Size: $sizeMb MB" -ForegroundColor Cyan
Write-Host "=" -ForegroundColor Cyan
Write-Host ""
Write-Host "Note: A self-signed certificate will trigger Windows SmartScreen once." -ForegroundColor Yellow
Write-Host "  → Click 'More info' then 'Run anyway' to install." -ForegroundColor Yellow
Write-Host "  The warning disappears after enough users run the app (reputation)," -ForegroundColor Yellow
Write-Host "  or by purchasing an EV/OV code-signing certificate from a CA." -ForegroundColor Yellow
