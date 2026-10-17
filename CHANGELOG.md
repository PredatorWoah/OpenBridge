# Changelog

All notable changes to OpenBridge are recorded here. Every release has an entry; the release workflow refuses to publish a version that is missing from this file. Versions follow [Semantic Versioning](https://semver.org).

## [Unreleased]

### Fixed
- **Mac: folders show their files right away.** Opening the phone or a folder could leave the list blank until you went back and opened it again. Folders now fade in as a whole in a fraction of a second, and rows no longer wait to appear while you scroll.

## [0.9.4] - 2026-10-17

A brand new look on both devices, built around the sunset arch from the icon.

### Added
- **Tap the sun to share.** On Android the arch from the app icon is now the main button. Tap the sun: it pulls back, whips once around the ring like a drop of liquid glass and warms up as it goes. When your Mac pairs, it lights up gold with a ripple and a little haptic tick, then breathes softly every few seconds while connected. It stays grey glass while nothing is connected.
- **The Mac finds your phone by itself.** Plug in the phone and choose File transfer: no button to press. The Mac's sun circles while it waits, then whips to the top and lights up, and a card for your phone springs up with **Open phone**.
- **Open phone, or jump straight in.** The card has shortcuts to Camera, Download, Screenshots, WhatsApp and Documents, and an **Open automatically when I plug in** option.
- **A new file browser on the Mac.** Glass sidebar, folders that open with a double-click, rows that cascade in, a sunset-coloured progress bar with a glowing sun at its tip, and **Show in Finder** when a download finishes.
- A pairing sheet on Android with big, easy-to-compare code digits.

### Changed
- **Simpler phone screen.** Two choices (**Whole phone** or **One folder**) and the sun. Everything else appears only when you need it. The address and port show while waiting for your Mac.
- The confusing **USB tethering** button is gone. USB File transfer needs no app at all, and the screen now says so.
- On the Mac, Wi‑Fi pairing moved into its own **Pair over Wi‑Fi** sheet. Unplugging the phone returns to the waiting screen.
- Both apps use the dark Dusk Glass look from the icon.

### Fixed
- **The Mac window no longer disappears.** Switching to another app could hide the OpenBridge window for good, even though the app kept running. Clicking OpenBridge in the Dock now always brings its window back.

### Notes
- Installs over 0.9.3 without uninstalling. USB File transfer remains beta.

## [0.9.3] - 2026-10-17

Fixes the Mac app crashing on **Connect over USB**.

### Fixed
- **No more crash when connecting over USB.** Choosing File transfer on the phone and pressing Connect over USB closed the Mac app. The cause was the way the app prepared memory for USB transfers; it now uses ordinary memory, which macOS accepts on every Mac.
- **Errors instead of crashes.** Any unexpected failure inside macOS's USB framework is now caught and shown as a message you can screenshot, rather than closing the app.
- If the phone is connected but does not answer, the message now includes the underlying reason.

### Changed
- **The Mac app now comes as a disk image (`.dmg`).** Open it and drag OpenBridge onto Applications, like most Mac apps. No more zip.
- If the phone is in USB tethering mode, the Mac now says so and explains how to switch to File transfer. macOS cannot use Android's USB tethering.

### Notes
- Installs over 0.9.2 without uninstalling. USB File transfer remains beta.

## [0.9.2] - 2026-10-17

Share your whole phone over Wi-Fi, not just one folder.

### Added
- **Share whole phone.** Besides **Choose folder**, the Android app can now share all of internal storage and any SD card. Android asks once for *All files access*; the Mac then browses, downloads and uploads anywhere, just like over USB, but wireless and encrypted. Android keeps `Android/data` and `Android/obb` private.
- Uploads in whole-phone mode publish with a move that refuses to replace an existing file, and symbolic links are never followed.

### Changed
- The Mac labels the top of the phone's storage "Phone".

### Notes
- Installs over 0.9.1 without uninstalling. Still a preview, not yet verified on physical devices.

## [0.9.1] - 2026-10-17

Easy 6-digit pairing, and USB File transfer that actually finds your phone.

### Fixed
- **USB File transfer now finds Android phones.** The Mac looked for the phone's File transfer interface in a way macOS cannot match for Android's vendor-specific USB class, so it never found the phone. It now checks every USB interface itself. If no phone is found, the message lists the USB devices the Mac can see, to show whether the phone is attached and in which mode.

### Changed
- **6-digit pairing instead of 64-character codes.** Enter the phone's IP and port on the Mac, check that both screens show the same 6-digit code, and tap Pair on the phone, like pairing Bluetooth. The long fingerprint and secret are gone, and the protection against someone intercepting the connection stays.
- The start screen separates Wi-Fi and USB clearly: USB File transfer needs no pairing, and its errors no longer mention Wi-Fi or fingerprints.

### Notes
- Installs over 0.9.0 without uninstalling.
- Still a preview: not yet verified on physical devices. USB File transfer remains beta. The Mac app is not notarized; see the README for how to open it.

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

[0.9.4]: https://github.com/PredatorWoah/OpenBridge/releases/tag/v0.9.4
[0.9.3]: https://github.com/PredatorWoah/OpenBridge/releases/tag/v0.9.3
[0.9.2]: https://github.com/PredatorWoah/OpenBridge/releases/tag/v0.9.2
[0.9.1]: https://github.com/PredatorWoah/OpenBridge/releases/tag/v0.9.1
[0.9.0]: https://github.com/PredatorWoah/OpenBridge/releases/tag/v0.9.0
[0.5.0]: https://github.com/PredatorWoah/OpenBridge/releases/tag/v0.5.0
