# Installing ADA Messenger

## Android

### From a release (recommended)
1. Open the [Releases](../../releases) page and download the latest
   `ada-messenger-*.apk`.
2. On your phone, allow installing apps from your browser/file manager
   (*Settings → Apps → Special access → Install unknown apps*).
3. Tap the downloaded APK and confirm the install.

### Via ADB (developers)
```bash
adb install -r ada-messenger-debug.apk
```

> Debug and release builds use different signing keys and cannot be installed
> over one another — uninstall first if you switch build types.

## Windows

### From a release (recommended)
1. Download the latest `ADA-Messenger-Setup-*.exe` from [Releases](../../releases).
2. Run the installer and follow the prompts.
3. On first launch, Windows SmartScreen may warn that the publisher is
   unverified. Choose **More info → Run anyway**. This happens because the
   public builds are not signed with an EV code‑signing certificate.

### Uninstall
Use *Settings → Apps → Installed apps → ADA Messenger → Uninstall*, or the
*Uninstall* shortcut created in the Start menu.

## Building it yourself
If you prefer not to trust pre‑built binaries, see [BUILDING.md](BUILDING.md)
to build from source on your own machine.
