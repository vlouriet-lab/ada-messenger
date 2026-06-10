; ════════════════════════════════════════════════════════════════════════════
; ADA Messenger — Windows NSIS Installer
;
; Build command (run from repo root, NSIS 3 required):
;   makensis /DVERSION=0.3.54 installer.nsi
;
; Or via the orchestration script:
;   .\build-installer-windows.ps1
; ════════════════════════════════════════════════════════════════════════════

Unicode True
SetCompressor /SOLID lzma

!include "MUI2.nsh"
!include "LogicLib.nsh"

; ── Compile-time variables ────────────────────────────────────────────────────
; VERSION is passed via /DVERSION=x.y.z from makensis command line.
!ifndef VERSION
  !define VERSION "0.3.54"
!endif

!define APPNAME   "ADA Messenger"
!define PUBLISHER "ADA"
!define REGKEY    "Software\Microsoft\Windows\CurrentVersion\Uninstall\ADAMessenger"

; Paths relative to the repo root (where makensis is invoked).
!define DISTDIR   "android-app\desktopApp\build\compose\binaries\main\app\ADA Messenger"
!define DLLPATH   "ada-core\target\debug\ada_core.dll"
!define ICONFILE  "android-app\desktopApp\src\desktopMain\resources\ada_icon.ico"
!define OUTDIR    "releases"

; ── Basic installer settings ─────────────────────────────────────────────────
Name          "${APPNAME} ${VERSION}"
OutFile       "${OUTDIR}\ADA-Messenger-Setup-${VERSION}.exe"
InstallDir    "$PROGRAMFILES64\${APPNAME}"
InstallDirRegKey HKLM "${REGKEY}" "InstallLocation"
RequestExecutionLevel admin
ShowInstDetails show
ShowUninstDetails show

; ── MUI appearance ───────────────────────────────────────────────────────────
!define MUI_ABORTWARNING
!define MUI_ICON   "${ICONFILE}"
!define MUI_UNICON "${ICONFILE}"

!define MUI_WELCOMEPAGE_TITLE    "Установка ${APPNAME} ${VERSION}"
!define MUI_WELCOMEPAGE_TEXT     \
    "Этот мастер установит ${APPNAME} на ваш компьютер.$\r$\n$\r$\n\
    ADA — зашифрованный мессенджер с защитой от цензуры.$\r$\n$\r$\n\
    Нажмите «Далее» для продолжения."

!define MUI_FINISHPAGE_RUN          "$INSTDIR\ADA Messenger.exe"
!define MUI_FINISHPAGE_RUN_TEXT     "Запустить ${APPNAME}"
!define MUI_FINISHPAGE_SHOWREADME   ""
!define MUI_FINISHPAGE_TITLE        "Установка завершена"
!define MUI_FINISHPAGE_TEXT         \
    "${APPNAME} ${VERSION} успешно установлен.$\r$\n$\r$\n\
    Ярлык добавлен на рабочий стол и в меню «Пуск»."

; ── Pages ─────────────────────────────────────────────────────────────────────
!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH

!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES

!insertmacro MUI_LANGUAGE "Russian"

; ── Install section ───────────────────────────────────────────────────────────
Section "!${APPNAME}" SecMain
    SectionIn RO

    SetOutPath "$INSTDIR"

    ; Install distributable (bundled JRE 17 + application JARs + launcher exe)
    ; The Compose Desktop createDistributable task outputs:
    ;   app\          — JARs
    ;   runtime\      — bundled JRE
    ;   ADA Messenger.exe
    ;   ADA Messenger.ico
    ;   .jpackage.xml
    File /r "${DISTDIR}\*"

    ; Install Rust native library next to the launcher.
    ; jpackage launchers on Windows add $INSTDIR to java.library.path,
    ; so System.loadLibrary("ada_core") finds this file automatically.
    File "${DLLPATH}"

    ; ── Shortcuts ────────────────────────────────────────────────────────────
    SetShellVarContext all
    CreateDirectory "$SMPROGRAMS\${APPNAME}"
    CreateShortcut  "$SMPROGRAMS\${APPNAME}\${APPNAME}.lnk" \
                    "$INSTDIR\ADA Messenger.exe" "" "$INSTDIR\ADA Messenger.exe"
    CreateShortcut  "$DESKTOP\${APPNAME}.lnk" \
                    "$INSTDIR\ADA Messenger.exe" "" "$INSTDIR\ADA Messenger.exe"

    ; ── Uninstaller ──────────────────────────────────────────────────────────
    WriteUninstaller "$INSTDIR\Uninstall.exe"

    ; ── Add/Remove Programs ──────────────────────────────────────────────────
    WriteRegStr   HKLM "${REGKEY}" "DisplayName"     "${APPNAME}"
    WriteRegStr   HKLM "${REGKEY}" "DisplayVersion"  "${VERSION}"
    WriteRegStr   HKLM "${REGKEY}" "Publisher"       "${PUBLISHER}"
    WriteRegStr   HKLM "${REGKEY}" "InstallLocation" "$INSTDIR"
    WriteRegStr   HKLM "${REGKEY}" "UninstallString" '"$INSTDIR\Uninstall.exe"'
    WriteRegStr   HKLM "${REGKEY}" "DisplayIcon"     "$INSTDIR\ADA Messenger.exe"
    WriteRegDWORD HKLM "${REGKEY}" "NoModify"        1
    WriteRegDWORD HKLM "${REGKEY}" "NoRepair"        1
    ; EstimatedSize in KB (distributable with JRE ~250 MB)
    WriteRegDWORD HKLM "${REGKEY}" "EstimatedSize"   256000
SectionEnd

; ── Uninstall section ─────────────────────────────────────────────────────────
Section "Uninstall"
    SetShellVarContext all

    ; Remove shortcuts
    Delete "$SMPROGRAMS\${APPNAME}\${APPNAME}.lnk"
    RMDir  "$SMPROGRAMS\${APPNAME}"
    Delete "$DESKTOP\${APPNAME}.lnk"

    ; Remove installed files (user data lives in %APPDATA%, not here)
    RMDir /r "$INSTDIR\app"
    RMDir /r "$INSTDIR\runtime"
    Delete   "$INSTDIR\ADA Messenger.exe"
    Delete   "$INSTDIR\ADA Messenger.ico"
    Delete   "$INSTDIR\.jpackage.xml"
    Delete   "$INSTDIR\ada_core.dll"
    Delete   "$INSTDIR\Uninstall.exe"
    RMDir    "$INSTDIR"

    DeleteRegKey HKLM "${REGKEY}"
SectionEnd
