# Changelog

All notable changes to OpenBridge are recorded here. Every release has an entry; the release workflow refuses to publish a version that is missing from this file. Versions follow [Semantic Versioning](https://semver.org).

## [Unreleased]

### Added
- **New app icon** on Android and macOS: a sunset-coloured arch bridge with a sun as its keystone, reflected in the water. Adaptive on Android, including a monochrome layer for themed icons on Android 13 and later.
- **USB File transfer on the Mac, like on Windows.** Plug in the phone, choose *File transfer* in its USB notification, and press **Connect over USB**. No app or pairing is needed on the phone. Browse every storage, download, and upload, with progress and cancel. Built directly on Apple's USB framework: nothing extra to install.
- Uploads over USB never replace files (names compared ignoring case) and partial uploads are removed if a transfer fails. The Mac reconnects automatically after a USB error.
- Files of 4 GiB and more transfer in both directions over USB, and uploads check the phone's free space before sending anything.

### Known limitations
- USB File transfer is not yet verified with physical phones.

## [1.0.0] - 2026-10-16

The first release: share one Android folder with a Mac, over Wi-Fi or a USB cable.

### Added
- **Android app** (Android 8.0+): pick one local folder (internal storage or SD card) and share it while the app is open. Shows the phone's address, port, certificate fingerprint and a fresh pairing secret on every start.
- **Mac app** (macOS 13+): pair by entering those values, then browse folders, download files to the Mac and upload files to the phone, with progress and cancel.
- **Security:** TLS 1.2+ on every request, with the Mac verifying the phone's certificate fingerprint before it sends the secret. A new certificate and 256-bit secret on every start. No accounts, cloud, discovery, telemetry or internet exposure.
- **USB cable mode:** with USB tethering switched on, the phone shares over the cable instead of Wi-Fi, and stops when the cable is unplugged. Works on phones that use NCM tethering (most Android 11+ phones, including Pixels); macOS does not support the older RNDIS tethering.
- **Safe transfers:** existing files are never replaced on either side; partial uploads and downloads are removed when interrupted.
- The phone's screen stays on while sharing, so the display timeout does not stop transfers.
- Uploads with names Android storage cannot hold (`" * : < > ? |`) or that differ from an existing file only by letter case are rejected before any data is sent.
- Saving a download over an existing Mac file is refused before any data is sent.
- Folder listings use one batched storage query, skip entries whose names cannot be transferred, and hide leftover temporary upload files.
- Automated protocol, TLS and Java/Swift interoperability tests; CI on every push, with full Android and macOS builds and an automated release workflow.

### Known limitations
- Not yet verified on physical devices. Please report problems, following [SECURITY.md](SECURITY.md) for anything security related.
- Pairing requires typing the address, port, 64-character fingerprint and 64-character secret.
- The APK is signed with a build-machine debug key, so a later version may need the previous one uninstalled first. The Mac app is ad-hoc signed and not notarized (see the README for how to open it).
- No resumable transfers, folder creation, deletion, multi-file upload or automatic discovery.

[1.0.0]: https://github.com/PredatorWoah/OpenBridge/releases/tag/v1.0.0
