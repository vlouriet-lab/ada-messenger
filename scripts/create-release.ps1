<#
.SYNOPSIS
    Создать первый GitHub Release с уже собранными локальными артефактами.

.DESCRIPTION
    Загружает подписанный APK и Windows-инсталлятор из локальных путей
    в GitHub Release через `gh` CLI (GitHub CLI).

    Требования:
      - Установлен GitHub CLI: https://cli.github.com/
      - Авторизован: gh auth login
      - Репозиторий уже создан на GitHub и задан в параметре -Repo

.PARAMETER Tag
    Тег релиза, например v0.3.1. Будет создан, если не существует.

.PARAMETER Repo
    Владелец/имя репозитория на GitHub, например myuser/ada-messenger.

.PARAMETER ApkPath
    Путь к подписанному APK-файлу на локальном диске.
    По умолчанию ищет в стандартных выходных путях Gradle.

.PARAMETER InstallerPath
    Путь к Windows-инсталлятору .exe на локальном диске.
    По умолчанию ищет в .\releases\.

.PARAMETER Draft
    Если указан — создать релиз как черновик (Draft) для предварительного просмотра.

.PARAMETER Prerelease
    Если указан — пометить релиз как предрелизный.

.EXAMPLE
    .\scripts\create-release.ps1 -Tag v0.3.1 -Repo myuser/ada-messenger

.EXAMPLE
    .\scripts\create-release.ps1 -Tag v0.3.1 -Repo myuser/ada-messenger `
        -ApkPath "android-app\app\build\outputs\apk\quasiRelease\app-quasiRelease.apk" `
        -InstallerPath "releases\ADA-Messenger-Setup.exe"
#>

[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$Tag,

    [Parameter(Mandatory)]
    [string]$Repo,

    [string]$ApkPath,
    [string]$InstallerPath,
    [switch]$Draft,
    [switch]$Prerelease
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# ── Проверить наличие gh CLI ──────────────────────────────────────────────────
if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
    Write-Error @"
GitHub CLI (gh) не найден.
Установите с https://cli.github.com/ и выполните: gh auth login
"@
    exit 1
}

# ── Найти APK ─────────────────────────────────────────────────────────────────
if (-not $ApkPath) {
    $candidates = @(
        "android-app\app\build\outputs\apk\quasiRelease\app-quasiRelease.apk",
        "android-app\app\build\outputs\apk\release\app-release.apk",
        "android-app\app\build\outputs\apk\debug\app-debug.apk"
    )
    foreach ($c in $candidates) {
        if (Test-Path $c) { $ApkPath = $c; break }
    }
}

if (-not $ApkPath -or -not (Test-Path $ApkPath)) {
    Write-Warning "APK не найден. Укажите путь через -ApkPath или соберите проект:"
    Write-Warning "  cd android-app; .\gradlew.bat :app:assembleQuasiRelease"
    $ApkPath = $null
}

# ── Найти Windows installer ───────────────────────────────────────────────────
if (-not $InstallerPath) {
    $exes = Get-ChildItem releases\*.exe -ErrorAction SilentlyContinue
    if ($exes) { $InstallerPath = $exes[0].FullName }
}

if (-not $InstallerPath -or -not (Test-Path $InstallerPath)) {
    Write-Warning "Windows-инсталлятор не найден. Укажите путь через -InstallerPath или соберите:"
    Write-Warning "  .\build-installer-windows.ps1 -SkipSigning"
    $InstallerPath = $null
}

if (-not $ApkPath -and -not $InstallerPath) {
    Write-Error "Не найдено ни APK, ни Windows-инсталлятора. Нечего загружать."
    exit 1
}

# ── Переименовать артефакты с тегом ───────────────────────────────────────────
$files = @()

if ($ApkPath) {
    $apkDest = "ada-messenger-${Tag}.apk"
    Copy-Item $ApkPath $apkDest -Force
    $files += $apkDest
    Write-Host "APK: $apkDest ($([math]::Round((Get-Item $apkDest).Length / 1MB, 1)) MB)"
}

if ($InstallerPath) {
    $exeDest = "ADA-Messenger-Setup-${Tag}.exe"
    Copy-Item $InstallerPath $exeDest -Force
    $files += $exeDest
    Write-Host "EXE: $exeDest ($([math]::Round((Get-Item $exeDest).Length / 1MB, 1)) MB)"
}

# ── Составить тело релиза ─────────────────────────────────────────────────────
$body = @"
## Установка / Install

**Android** — скачайте ``ada-messenger-${Tag}.apk``, включите *Установка из неизвестных источников* и откройте файл.
Или через ADB: ``adb install -r ada-messenger-${Tag}.apk``

**Windows** — скачайте и запустите ``ADA-Messenger-Setup-${Tag}.exe``.
SmartScreen может показать *Подробнее → Выполнить* (сборка без EV-сертификата).

Подробные инструкции: [docs/INSTALL.md](https://github.com/${Repo}/blob/main/docs/INSTALL.md)
"@

# ── Создать релиз через gh CLI ────────────────────────────────────────────────
$ghArgs = @(
    'release', 'create', $Tag,
    '--repo', $Repo,
    '--title', "ADA Messenger $Tag",
    '--notes', $body
)

if ($Draft)      { $ghArgs += '--draft' }
if ($Prerelease) { $ghArgs += '--prerelease' }

$ghArgs += $files

Write-Host "`nСоздаю релиз $Tag в $Repo..."
& gh @ghArgs

if ($LASTEXITCODE -eq 0) {
    Write-Host "`nРелиз создан: https://github.com/$Repo/releases/tag/$Tag"
    # Удалить временные переименованные копии
    foreach ($f in $files) { Remove-Item $f -Force -ErrorAction SilentlyContinue }
} else {
    Write-Error "gh завершился с кодом $LASTEXITCODE"
}
