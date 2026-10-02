# Resurrect

A Flutter Android app that **installs legacy APKs that Android 14+ normally refuses** — directly from the phone over **wireless ADB** (ADB over Wi‑Fi), no computer required.

Modern Android blocks installing apps that target a very old SDK. Resurrect installs them via ADB (which bypasses that block), so you can bring old/abandoned apps back to life on a current device.

> ⚠️ Only install APKs you trust. Old apps may have unpatched vulnerabilities. Use at your own risk.

## Features

- **Install blocked legacy APKs** — pushes the APK through ADB, bypassing Android 14's "app targets an old SDK" install restriction.
- **Bundle & split support** — pick a single `.apk`, multiple split APKs, or a full `.xapk` / `.apks` / `.apkm` / `.zip` bundle; Resurrect expands it and installs the base + splits atomically.
- **Pre‑install inspection** — reads and shows the app label, package name, version, size, part count, and **min / target SDK** before you commit.
- **Smart warnings** — flags when the target SDK is in the blocked range (so you know the bypass applies) and warns when **no native libraries** are found in your selection (which would crash Flutter/Unity/etc. apps unless you also include the split APKs).
- **Install history** — an animated log of each install with success/failure and the raw `adbd` reply.
- **Crash log viewer** — dumps the device's crash `logcat` buffer over ADB, with copy‑to‑clipboard, for diagnosing apps that fail to launch.
- **On‑device wireless ADB** — one‑time pairing with a compact *Connect* flow on return, auto port discovery via mDNS, and a **floating pairing helper** overlay over the Settings screen.
- **Material 3 UI** with adaptive light/dark theme.

## How it works

The Dart UI (`lib/main.dart`) talks to native Android code over platform channels:

- **MethodChannel** `com.example.resurrect/adb`
  - Connection: `pair`, `connect`, `connectAuto`, `isPaired`, `disconnect`, overlay permission + floating window controls.
  - APKs: `prepareSelection` expands raw APKs and bundles into a resolved install set with metadata; `installApks` installs the base + splits atomically; `readCrashLog` dumps the crash logcat buffer.
- **EventChannel** `com.example.resurrect/adb_events` — pushes events (e.g. `connected`) from the floating window back to the UI.

Native Kotlin components (`android/app/src/main/kotlin/com/example/resurrect/`) include the ADB connection manager, a self‑signed certificate helper for the ADB TLS handshake, and the floating window controller. File selection uses the [`file_picker`](https://pub.dev/packages/file_picker) plugin.

## Getting started

### Prerequisites

- [Flutter SDK](https://docs.flutter.dev/get-started/install) (Dart `^3.11.3`)
- An Android device with **Developer options** enabled

### Run

```bash
flutter pub get
flutter run
```

### Using the app

1. On your Android device, enable **Developer options → Wireless debugging**.
2. Open **Wireless debugging → Pair device with pairing code** to get a **pairing port** and **pairing code**.
3. In Resurrect, enter the pairing port and code (or use **Float over Settings to pair**), then tap **Pair & Connect**. Leave the connection port blank to auto‑detect it.
4. Tap **Choose APK / bundle**, select your `.apk`(s) or bundle, review the details, then tap **Resurrect & Install**.
5. If an installed app crashes on launch, use the **crash log** button in the app bar to inspect logcat.

## Project structure

```
lib/main.dart      # Flutter UI, APK selection/install + AdbService bridge
android/           # Native Kotlin: ADB manager, APK install, TLS cert, floating window
test/              # Widget tests
```

## Disclaimer

This tool installs APKs via ADB, bypassing Android's low‑target‑SDK install block. It does not root your device. Installing untrusted or outdated apps can be a security risk — review what you install.
