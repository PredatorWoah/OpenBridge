# Changelog

All notable changes to OpenBridge are recorded here. Every release has an entry; the release workflow refuses to publish a version that is missing from this file. Versions follow [Semantic Versioning](https://semver.org).

## [Unreleased]

## [0.9.0] - 2026-10-17

The last preview before 1.0, and the first with a permanent signing key.

### Added
- **Signed with the official OpenBridge key.** The Android app is now signed with a permanent release key, so from here on every update installs over the previous version. You can verify the APK against the fingerprint published in [SECURITY.md](SECURITY.md#verifying-downloads).
- Release builds are checked automatically: a release is refused if the APK is not signed with that key, or if the version has no changelog entry.

### Changed
- **Uninstall once:** because earlier previews used a temporary key, uninstall 0.5.0 (or any earlier test build) before installing 0.9.0. This is the last time.

### Known limitations
- Still a preview: it has passed automated tests but has not yet been verified on physical devices. USB File transfer remains beta.
- Pairing over Wi-Fi requires typing the address, port, fingerprint and secret.
- The Mac app is not notarized; see the README for how to open it.

## [0.5.0] - 2026-10-16

The first public preview: move files between an Android phone and a Mac, privately, on your own network or over a cable.

### Added
- **Android app** (Android 8.0 and later): pick one local folder and share it with your Mac while the app is open. Each start shows the phone's address, port, certificate fingerprint and a fresh pairing secret.
- **Mac app** (macOS 13 and later): pair with the phone, then browse folders, download files to the Mac and upload files to the phone, with progress and cancel.
- **Private by design:** every Wi-Fi request is encrypted with TLS, and the Mac checks the phone's certificate fingerprint before it sends anything. No accounts, no cloud, no tracking.
- **Never overwrites:** existing files are never replaced on either side, and interrupted transfers clean up after themselves.
- **USB File transfer on the Mac (beta):** plug in the phone, choose *File transfer*, and press **Connect over USB** to browse and move files, like on Windows. No app needed on the phone.

### Known limitations
- This is a preview. It has passed automated tests but has not yet been verified on physical devices. Please report problems, following [SECURITY.md](SECURITY.md) for anything security related.
- Pairing over Wi-Fi requires typing the address, port, fingerprint and secret.
- The APK is signed with a build-machine key and the Mac app is not notarized; see the README for how to install and open them. If you installed an earlier test build, uninstall it first.

[0.9.0]: https://github.com/PredatorWoah/OpenBridge/releases/tag/v0.9.0
[0.5.0]: https://github.com/PredatorWoah/OpenBridge/releases/tag/v0.5.0
