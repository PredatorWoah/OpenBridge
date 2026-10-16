# OpenBridge

An open-source Android ↔ macOS companion. Phase 1 shares **one user-selected local Android folder** over Wi-Fi: browse folders, download to Mac, and upload to Android. No accounts, hosted backend, discovery service, telemetry, or cloud dependency at runtime. Licensed under [MIT](LICENSE).

This is an early MVP implementation. See [verification status](docs/verification.md) for exactly what has and has not been tested. Physical-device behavior is not yet verified.

## Requirements

- Android 8.0/API 26 or later, private IPv4 Wi-Fi, and a writable folder in internal storage or an SD card.
- macOS 13 or later. Both devices must be reachable on the same local network; guest Wi-Fi/client isolation can prevent connections.
- Development: JDK **17**, Gradle **8.13**, Android SDK platform **35**, and Android Studio or the command-line SDK. Mac builds need Xcode command-line tools/Swift 5.9 or later. These tools and dependency downloads are build-time requirements only.

## Build and install

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

1. Put both devices on the same trusted LAN. Open Android OpenBridge and choose **Choose folder**. Select a folder under internal storage or the SD card in the system picker. Android intentionally disallows some roots; use a subfolder such as `Documents/OpenBridge`. Cloud providers are rejected.
2. Press **Start sharing**. Keep the Android app visible; the screen stays on while sharing. The screen shows the Wi-Fi IPv4 address, a dynamically allocated port, a SHA-256 certificate fingerprint, and a 64-character pairing secret.
3. Open the Mac app. Enter those four values, copying the fingerprint **directly from the phone**. Press **Pair and browse**. A wrong certificate or secret is rejected; TLS verification has no bypass.
4. Use **Open**, **Up**, and **Refresh** to browse. Use **Download…** to save a file on Mac, or **Upload file…** to send a regular file into the current Android folder. Progress and failures appear in the window. Empty files are supported; the per-file limit is 64 GiB.
5. Use **Stop** on Android when finished. Leaving the Android app or losing/changing Wi-Fi also stops sharing. Restarting creates new credentials; disconnect and pair again on Mac.

Existing filenames are rejected on both sides, before any data is transferred. On Android, names are compared ignoring case (`Photo.JPG` collides with `photo.jpg`), and the characters `" * : < > ? |` are not allowed. Choose another name (rename the source before upload). Downloads use temporary sibling files; uploads use temporary SAF documents and publish only after the declared body is received. On a normal disconnection/cancellation, partial files are removed. After a process kill or power loss, remove any leftover `.openbridge-part-*` Android documents (hidden from the Mac's file list) or `.openbridge-*.part` Mac files manually. A cancellation exactly at upload completion can mean the phone committed the file before the Mac received its acknowledgement; refresh before retrying.

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
- `android/app/`: Kotlin/Compose UI, local Wi-Fi listener, AndroidKeyStore TLS identity, SAF-backed storage.
- `macos/`: SwiftUI app, independent Swift wire codec, pinned TLS channel, streaming transfers and tests.
- `docs/`: protocol contract, limitations, and manual verification.

The `Store` interface separates document access from wire handling; `BridgeCore` separates networking from the Mac UI. Future clipboard, notifications, folder sync, and screen mirroring need explicit new capabilities and protocol versions. They are not implemented in Phase 1. There is no background sharing, resumable transfer, remote deletion, folder creation, batch upload, or automatic discovery in this MVP.
