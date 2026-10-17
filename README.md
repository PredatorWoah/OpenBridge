<p align="center"><img src="docs/images/icon.png" width="128" height="128" alt="OpenBridge app icon: a sunset-coloured glass arch bridge with a glowing sun at its keystone"></p>

# OpenBridge

[![Latest release](https://img.shields.io/github/v/release/PredatorWoah/OpenBridge?sort=semver)](https://github.com/PredatorWoah/OpenBridge/releases/latest)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
![Platforms](https://img.shields.io/badge/platforms-Android%208%2B%20%7C%20macOS%2013%2B-lightgrey)

![OpenBridge: an Android phone and a Mac connected over Wi-Fi or a USB cable](docs/images/hero.svg)

**OpenBridge is a free, open-source way to move files between an Android phone and a Mac.** Share one folder or your whole phone, and browse, download and upload from your Mac over your home Wi-Fi or a USB cable. Everything stays on your own network: no accounts, no cloud, no tracking.

- 🔒 **Encrypted, with 6-digit pairing.** Over Wi-Fi (and USB tethering) every request uses TLS. To pair, check that both screens show the same 6-digit code and tap Pair on the phone, like pairing Bluetooth.
- 📁 **One folder or the whole phone.** Over Wi-Fi the Android app shares the folder you pick, or all of your storage if you choose **Whole phone**, and only while it is open.
- 🛡️ **Never overwrites.** Existing files are never replaced on either side, and interrupted transfers are cleaned up.
- 🔌 **Or just plug in (beta).** On a Mac, connect a cable, choose File transfer on the phone, and browse it like on Windows. This uses Android's own File transfer mode, so it sees everything that mode shares, not one folder; see [how the two modes differ](#wi-fi-vs-usb-file-transfer).

**Download:** grab the Android APK and the Mac app from the [latest release](https://github.com/PredatorWoah/OpenBridge/releases/latest). See the [changelog](CHANGELOG.md) for what's new.

> **Status:** the automated tests pass, including a real Java/Swift TLS interoperability test, but behavior on physical devices has not yet been verified. See [verification status](docs/verification.md).

## How it works

![How OpenBridge works: pick what to share, tap the sun, pair on the Mac, then browse and transfer](docs/images/how-it-works.svg)

## Requirements

- Android 8.0/API 26 or later, private IPv4 Wi-Fi or USB tethering (see below), and a writable folder in internal storage or an SD card.
- macOS 13 or later. Both devices must be reachable on the same local network; guest Wi-Fi/client isolation can prevent connections.
- Development: JDK **17**, Gradle **8.13**, Android SDK platform **35**, and Android Studio or the command-line SDK. Mac builds need Xcode command-line tools/Swift 5.9 or later. These tools and dependency downloads are build-time requirements only.

## Install a release

1. **Android:** download `OpenBridge-<version>.apk` from the release onto your phone and open it. Allow installing from your browser or file manager when asked.
2. **Mac:** download `OpenBridge-<version>-macOS.dmg`, open it, and drag OpenBridge onto the Applications folder. The app is not notarized, so the first time, right-click it and choose **Open**, then **Open** again. If macOS says the app is damaged, run `xattr -dr com.apple.quarantine /Applications/OpenBridge.app` in Terminal.

## Build from source

```sh
git clone https://github.com/PredatorWoah/OpenBridge.git
cd OpenBridge
# Install Gradle 8.13 using your package/version manager, or its official distribution.
# No unverified Gradle wrapper binary is checked into this repository.
# Set ANDROID_HOME to your SDK, or set sdk.dir in local.properties.
gradle :android:app:assembleDebug
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

Alternatively, open the repository root in Android Studio and select Gradle 8.13 as the local Gradle installation. Use JDK 17 for Gradle. Install SDK 35 when prompted; run the `android.app` application configuration on a phone. Android Studio may offer to generate its own wrapper.

On a Mac:

```sh
bash scripts/build-macos.sh
open macos/build/OpenBridge.app
```

The script makes an application bundle with a **local ad-hoc signature**. It does not notarize or publish a binary. You can also open `macos/Package.swift` in Xcode and run the OpenBridge executable. Allow local network access if macOS prompts. CI produces a debug APK and an ad-hoc signed Mac app artifact on successful builds; neither is a production release.

## Transfer files

1. Put both devices on the same trusted LAN. Open Android OpenBridge and pick what to share:
   - **Whole phone** (the default): allow *All files access* for OpenBridge when Android asks (Settings → Apps → Special app access → All files access). The Mac then sees all of internal storage and any SD card. Android keeps `Android/data` and `Android/obb` private to each app.
   - **One folder**: select a folder under internal storage or the SD card in the system picker. Android disallows some roots; use a subfolder such as `Documents/OpenBridge`. Cloud providers are rejected.
2. **Tap the sun.** It circles the arch while sharing starts, then the phone shows its address and port. Keep the Android app visible; the screen stays on while sharing.
3. On the Mac, click **Pair over Wi‑Fi**, enter the address and port, and press **Pair**. Both screens show a 6-digit code: tap **Pair** on the phone **only if they match**. If they differ, tap Cancel; another device may be trying to connect. When pairing succeeds the sun lights up gold on both devices.
4. Click **Open phone** to browse. Double-click a folder to open it, select a file and press **Download** to save it on the Mac, or **Upload…** to send a file into the current folder. Progress shows in the bar at the bottom, with **Show in Finder** when a download finishes. Empty files are supported; the per-file limit is 64 GiB.
5. Press **Stop sharing** on Android when finished. Leaving the Android app, losing or changing Wi-Fi, or unplugging a USB tethering cable also stops sharing. Restarting creates new credentials; pair again on the Mac.

Existing filenames are rejected on both sides, before any data is transferred. On Android, names are compared ignoring case (`Photo.JPG` collides with `photo.jpg`), and the characters `" * : < > ? |` are not allowed. Choose another name (rename the source before upload). Downloads use temporary sibling files; uploads use temporary SAF documents and publish only after the declared body is received. On a normal disconnection/cancellation, partial files are removed. After a process kill or power loss, remove any leftover `.openbridge-part-*` Android documents (hidden from the Mac's file list) or `.openbridge-*.part` Mac files manually. A cancellation exactly at upload completion can mean the phone committed the file before the Mac received its acknowledgement; refresh before retrying.

### Over a USB cable (File transfer, like on Windows) · beta

No app or pairing is needed on the phone for this. **Beta:** it has passed automated tests against a simulated phone but not yet on real phones. Please report your phone model if it is not found or a transfer fails.

1. Plug the phone into the Mac and unlock it.
2. Pull down the notification shade, tap the **USB** notification (often "Charging this device via USB"), and choose **File transfer**.
3. Open the Mac app. It notices the phone by itself: the sun whips around, lights up, and a card for your phone appears. Click **Open phone**, or a shortcut such as **Camera** or **Download**. Tick **Open automatically when I plug in** to skip the card next time.

Good to know:

- If Photos, Image Capture, Android File Transfer, OpenMTP or a similar app is using the phone, the Mac app cannot open it. OpenBridge releases it from macOS's background photo import service automatically; quit any other such app.
- Files are never replaced. Names are compared ignoring case, like the phone's storage does.
- Large files work both ways, including files of 4 GiB and more. Uploads check the phone's free space first.
- Keep the phone unlocked. Some phones close the connection when the screen locks.

### Wi-Fi vs USB File transfer

| | Wi-Fi (and USB tethering) | USB File transfer |
| --- | --- | --- |
| Needs the Android app | Yes | No |
| What the Mac can see | The folder you picked, or the whole phone if you chose **Whole phone** | Everything Android shares in File transfer mode (usually all of internal storage and the SD card) |
| Protection | TLS, paired by matching a 6-digit code | The cable and your choice of File transfer on an unlocked phone; no encryption, as with any USB file transfer |
| Stops when | You leave the app, press Stop sharing, or the network drops | You unplug, lock the phone (on some phones), or choose another USB mode |

Choose File transfer only on a Mac you trust, and switch the phone back to *No data transfer* when you are done.

### Over a USB cable with tethering (alternative)

This uses the OpenBridge Android app and the same encrypted protocol as Wi-Fi, over the cable.

1. Plug the phone into the Mac and switch on *USB tethering* (usually under Settings → Network → Hotspot & tethering). Return to OpenBridge.
2. Tap the sun. When tethering is on, OpenBridge prefers the cable over Wi-Fi.
3. Pair on the Mac exactly as above, using the IP and port on the phone. Wi-Fi is not needed.

Limitations, stated plainly:

- **Android does not let apps turn on tethering**, so you switch it on yourself each time you plug in.
- **macOS supports NCM tethering** (used by most Android 11+ phones, including Pixels) but **not RNDIS**, which many older phones and some Samsung models still use. If the Mac never shows a new network interface (System Settings → Network) after tethering is on, your phone uses RNDIS and USB mode will not work on a Mac. Use Wi-Fi.
- Some carriers block tethering entirely.
- Tethering also shares the phone's internet, and macOS may route its traffic through it, **using mobile data**. Put Wi-Fi above the phone in System Settings → Network → ⋯ → Set Service Order, or turn off mobile data while transferring.

If a connection fails, check that sharing is still on, copy the current port/credentials, allow local network access on Mac, and disable Wi-Fi client isolation. Public IPs, hostnames, IPv6, cellular-only connections, and VPN interfaces are intentionally unsupported. Do not forward the port or tunnel the server to the internet.

## Development and checks

```sh
bash scripts/test-protocol.sh             # JDK 17+, no external test dependencies
python3 scripts/test-tls.py               # real Java TLS server + stdlib Python client
python3 scripts/check-repository.py       # XML/plist, source and repository checks
gradle :protocol:check :android:app:assembleDebug :android:app:lintDebug
swift test --package-path macos           # on macOS; interop test requires fixture below
```

To run the actual Java-server/Swift-client TLS interoperability test on a Mac:

```sh
bash scripts/test-protocol.sh
keytool -genkeypair -alias fixture -keyalg RSA -keysize 2048 -storetype PKCS12 \
  -keystore /tmp/openbridge-fixture.p12 -storepass test-only -keypass test-only \
  -dname CN=OpenBridge-CI -validity 1
FIXTURE_CLASSPATH="$PWD/build/protocol-tests" \
FIXTURE_KEYSTORE=/tmp/openbridge-fixture.p12 swift test --package-path macos
rm /tmp/openbridge-fixture.p12
```

The integration test is explicitly skipped when fixture variables are missing. CI supplies them. CI builds and lints Android on Linux and builds/tests the SwiftUI app on macOS. See [protocol](docs/protocol.md), [security](SECURITY.md), and the [device checklist](docs/verification.md).

## Structure and scope

- `protocol/`: small Java streaming handler and storage interface, executable tests, CI-only TLS fixture. No Android dependencies.
- `android/app/`: Kotlin/Compose UI, local Wi-Fi/USB-tethering listener, AndroidKeyStore TLS identity, SAF-backed storage.
- `macos/`: SwiftUI app, independent Swift wire codec, pinned TLS channel, streaming transfers and tests.
- `docs/`: protocol contract, limitations, and manual verification.

The `Store` interface separates document access from wire handling; `BridgeCore` separates networking from the Mac UI. Future clipboard, notifications, folder sync, and screen mirroring need explicit new capabilities and protocol versions. They are not implemented in Phase 1. There is no background sharing, resumable transfer, remote deletion, folder creation, batch upload, or automatic discovery in this MVP.
