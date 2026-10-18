# Changelog

All notable changes to OpenBridge are recorded here. Every release has an entry; the release workflow refuses to publish a version that is missing from this file. Versions follow [Semantic Versioning](https://semver.org).

## [Unreleased]

## [0.11.0] - 2026-10-18

It remembers you: pair once, and your computer reconnects by itself. Plus rename and delete, folder sizes, search, update alerts, Chinese on the phone and Intel Macs.

### Added
- **Remembered pairing.** Pair once with the 6-digit code; after that the Mac or PC reconnects by itself whenever the phone is sharing, even at a new address or on a Wi‑Fi where phones don't show up. Your phones are listed under **Your phones** on the computer, and the phone lists the computers it trusts under **Trusted computers**, with **Forget** on both sides.
- **The phone shows who's asking.** Pairing requests and the status say the computer's name, like "Pair with Aditya's MacBook Air?" and "Aditya's MacBook Air is connected".
- **Rename and delete** files and folders on the phone, from the Mac (right-click, the ⋯ menu, or ⌘⌫) and Windows (the ⋯ menu, F2 and Delete). Deleting always asks first, since the phone has no bin. On the Mac this works over USB File transfer too.
- **Folder sizes.** Folders show their total size over Wi‑Fi, counted on the phone in the background, and sort by size. Very large folders show "≥" once the phone has counted for a few seconds.
- **Search and a photos-and-videos filter** in every folder, on the Mac and Windows.
- **Update alerts.** All three apps check GitHub once per launch and show a link when a new version is out. Nothing is installed automatically.
- **简体中文 on the phone.** The Android app follows the phone's language, like the Windows app.
- **Intel Macs.** The Mac app is now a universal app for Apple Silicon and Intel.

### Changed
- The phone keeps one identity for as long as the app is installed, and gives each computer its own secret, stored only as a hash. It prefers port 41987, so saved computers find it again.
- A computer the phone no longer trusts (forgotten, or the app reinstalled) is told so straight away and asked to pair again, instead of timing out.

### Notes
- Update the phone app and the computer apps together. Computers still pair with older phone apps, but can't rename, delete or measure folders on them.
- After updating, pair once more: phones on 0.9.10 and earlier made a new identity every time they started sharing.

## [0.9.10] - 2026-10-18

Folders over USB open the first time.

### Fixed
- **Mac: "Unable to send IO." when opening folders over USB**, until you pressed Refresh a few times. Every photo that scrolled into view queued a preview request on the cable, so opening a folder waited behind all of them; and when the phone choked on one preview, the reset connection failed the folder you had asked for. Now:
  - Previews load one at a time over USB (two over Wi‑Fi), most recently shown first, and pause while a folder loads or a file transfers. Previews for a folder you've left are dropped.
  - If the cable hiccups while opening a folder, the Mac reconnects and tries again by itself.
  - If a phone's previews break the connection twice, previews stay off until you reconnect, so browsing stays reliable.
  - USB errors say what happened in plain words, with the system's error code for bug reports, instead of macOS's "Unable to send IO."
- **Mac: folders open faster in photo-heavy folders**, over USB and Wi‑Fi, since they no longer wait behind previews.
- Mac over Wi‑Fi: previews no longer open dozens of connections at once, which made some of them fail for good.

### Notes
- Installs over 0.9.9. The phone and Windows apps are unchanged apart from the version number.

## [0.9.9] - 2026-10-17

A proper Windows installer, and sturdier connections on Windows and with every computer.

### Added
- **Windows installer.** `OpenBridge-0.9.9-Windows-Setup.exe` installs OpenBridge for your user account, with no administrator prompt, and adds it to the Start menu and the desktop. Later versions install over it. The portable zip is still there if you prefer it.
- **Cancel pairing on Windows.** A Cancel button appears while connecting or waiting for you to tap Pair on the phone, instead of waiting a minute for it to time out.
- **Help when no phone shows up.** After a few seconds without finding a phone, the Windows app explains the usual cause: Windows hides phones on a Wi‑Fi set to *Public*, and how to switch it to *Private*. Connection errors mention VPNs and proxies that block local network traffic, a common cause in China.
- Pasting the address as `192.168.1.23:41987` fills in the port too.

### Fixed
- **"Couldn't reach the phone" while browsing photos.** The phone served only two requests at a time and dropped the rest, so opening a folder or starting a download while previews were loading could fail at random, and some previews never appeared. The phone now serves four at once with a short queue, and the Windows app loads one preview at a time and retries a connection the phone was too busy to take.
- **Windows: phones nearby stopped appearing after disconnecting** until the app was restarted. Discovery now keeps running for the whole session.
- **Windows: when the phone stops sharing or leaves the Wi‑Fi**, the app returns to pairing with a clear message, instead of staying in a folder that only shows errors like "Connection reset". Unfinished transfers continue by themselves after pairing again.
- Windows error messages are now translated into Simplified Chinese too.
- The Windows app looks for USB phones only while the pairing screen is open, using less CPU while you browse.

### Notes
- Update the phone app too, to get the connection fix on the phone's side. Installs over 0.9.8.
- The installer isn't code-signed yet, so SmartScreen may show "Windows protected your PC": click More info, then Run anyway.

## [0.9.8] - 2026-10-17

OpenBridge comes to Windows: a fast, private alternative to Phone Link that works with any Android phone, in English and Simplified Chinese.

### Added
- **OpenBridge for Windows (beta).** Unzip and run `OpenBridge.exe`, no installer and nothing else to install. It has the same Dusk Glass sun as the phone and does everything the Mac app does over Wi‑Fi:
  - Phones sharing on the same Wi‑Fi show up by themselves; click one, compare the 6 digits and tap Pair on the phone.
  - Browse with thumbnails, sort by name, size or kind, select with Ctrl and Shift, and download files or whole folders. Interrupted downloads continue where they stopped, and continue by themselves when the same phone pairs again.
  - Drop files or folders onto the window to upload them, and create folders on the phone.
  - Send the PC's clipboard to the phone, or copy the phone's to the PC.
- **USB on Windows.** Plug in the phone and choose File transfer: OpenBridge notices it and opens it in File Explorer, where Windows handles it like a USB drive.
- **Screen mirroring on Windows (beta).** Click Mirror screen to see and control the phone in a window, with the official scrcpy built in. It needs USB debugging, like on the Mac.
- **Simplified Chinese.** The Windows app follows Windows' language, and there is a Chinese README. OpenBridge works with Xiaomi, OPPO, vivo, OnePlus, Honor and other Android phones without any account or cloud, including where Phone Link can't connect.

### Changed
- The phone app now says "computer" instead of "Mac", since it pairs with Windows PCs too, and its cable hint mentions File Explorer.

### Notes
- The Windows app is not code-signed yet, so SmartScreen may show "Windows protected your PC": click More info, then Run anyway. Check the zip against SHA256SUMS.txt.
- Huawei phones on HarmonyOS NEXT can't run Android apps, so they can't use OpenBridge. Huawei phones on EMUI or HarmonyOS 2 to 4 can.
- Installs over 0.9.7. The Mac app is unchanged apart from the version number.

## [0.9.7] - 2026-10-17

Fixes from real-device testing of 0.9.6: mirroring that froze, and resume that didn't survive unplugging.

### Fixed
- **Resume now works after unplugging.** Unplugging the phone mid-download cleared the transfer, and the phone gives files new IDs on every connection, so nothing could continue it. Interrupted transfers now remember where each file lives on the phone by name. Plug the same phone back in (or pair it again over Wi‑Fi) and the download continues by itself from where it stopped, with a "Continuing…" notice. Downloading the same file to the same place again also continues it.
- **Screen mirroring no longer freezes.** The picture could stop until you rotated the phone, when macOS paused its video decoder (window hidden, display asleep, graphics switch) or after a decoding hiccup. OpenBridge now notices and asks the phone for a fresh frame at once, and refreshes the picture when the window comes back into view or the Mac wakes.

## [0.9.6] - 2026-10-17

The power-user release: mirror and control your phone from the Mac, a real file browser, a shared clipboard, and no more typing addresses.

### Added
- **Screen mirroring (beta).** Click **Mirror screen** to see and control your phone in a window on the Mac: click to tap, drag to swipe, scroll, type, and use Back, Home, Recent apps, volume, power and rotate. It uses the open-source scrcpy server, built in, with Apple's hardware video decoder, so there is nothing else to install. It needs USB debugging on the phone, and asks you to tap Allow once.
- **Shared clipboard.** Send the Mac's clipboard to the phone or copy the phone's to the Mac over Wi‑Fi. While mirroring, copying on either side syncs automatically, and ⌘V pastes on the phone.
- **Phones show up by themselves on Wi‑Fi.** While sharing, the phone announces itself on your network; on the Mac, click it under **Phones nearby** instead of typing its address.
- **Keep sharing in the background.** Leave the OpenBridge app and sharing continues, with a notification showing the status and a Stop button. Pairing requests arrive as a notification too. A switch on the main screen turns it off.
- **Thumbnails, grid view and Quick Look.** Photos and videos show previews in the list, or browse them as a grid. Press the space bar to Quick Look a file.
- **Multi-select, whole folders and drag and drop.** Select with ⌘-click, ⇧-click or ⌘A, download several files or entire folders at once, and drop files or folders from Finder onto the window to upload them. Drag a file out to Finder to copy it.
- **Sort by name, size or kind**, ascending or descending, with folders first.
- **New folders on the phone**, over USB or Wi‑Fi.
- **Downloads continue where they stopped.** An interrupted download keeps its progress; Resume picks it up from the same byte, over USB or Wi‑Fi. Batches continue with the files that were left.
- A clickable path bar, and keyboard shortcuts: space for Quick Look, ⌘A, ⌘↑, ⌘↓ or Return to open, ⌘⇧N for a new folder.

### Changed
- Downloads and uploads of several items never fail on a name clash: copies get a new name like "photo 2.jpg". A single download still asks where to save it.
- The Wi‑Fi protocol gains folder creation, previews, resuming and the clipboard (operations 4 to 8). Update both apps together; an older phone app answers these with a request to update.
- Android: the phone asks for notification permission the first time you share, for the background notification and pairing requests.

### Notes
- Installs over 0.9.5 without uninstalling. USB File transfer and screen mirroring are beta.
- Mirroring relies on USB debugging, which gives a trusted computer broad access to the phone. See SECURITY.md, and turn USB debugging off when you don't need it.

## [0.9.5] - 2026-10-17

Fixes from the first real-device test of the new design: blank folders on the Mac, a nicer grey sun, and the phone lighting up for the cable too.

### Added
- **The phone's sun lights up for the cable too.** Plug the phone into your Mac and choose File transfer: the sun whips around and turns gold, with "Connected to your Mac by cable", no tap needed. If the cable is only charging, the phone tells you to pick File transfer.

### Changed
- **A prettier grey sun.** While nothing is connected the sun is bright moonlight glass with a soft glow, instead of a dull grey ball, and it stays solid as it passes below the horizon.
- **Stop sends the sun home.** After Stop sharing the sun glides on to the top of the arch and rests there, instead of freezing wherever it was.
- The Whole phone and One folder tiles are the same height.
- **Clearer cable hint on the phone.** It used to say "No app needed", which was confusing inside the app. It now explains that the OpenBridge Mac app opens your phone once you choose File transfer, with no need to tap the sun.

### Fixed
- **Mac: a retry can no longer mix up USB and Wi‑Fi.** Pressing Try again while another connection was still starting could label it with the wrong kind and show the wrong error. The Mac now ignores a new connection attempt until the current one finishes.
- **Mac: folders show their files right away.** Opening the phone or a folder could leave the list blank until you went back and opened it again. Folders now fade in as a whole in a fraction of a second, and rows no longer wait to appear while you scroll.

### Notes
- **First real-device result:** a 7.83 GB file downloaded over USB File transfer from a vivo X300 Pro to a MacBook Air M1, covering the path for files of 4 GiB and more. USB File transfer remains beta while more phones are tested.
- Installs over 0.9.4 without uninstalling.

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

[0.9.7]: https://github.com/PredatorWoah/OpenBridge/releases/tag/v0.9.7
[0.9.6]: https://github.com/PredatorWoah/OpenBridge/releases/tag/v0.9.6
[0.9.5]: https://github.com/PredatorWoah/OpenBridge/releases/tag/v0.9.5
[0.9.4]: https://github.com/PredatorWoah/OpenBridge/releases/tag/v0.9.4
[0.9.3]: https://github.com/PredatorWoah/OpenBridge/releases/tag/v0.9.3
[0.9.2]: https://github.com/PredatorWoah/OpenBridge/releases/tag/v0.9.2
[0.9.1]: https://github.com/PredatorWoah/OpenBridge/releases/tag/v0.9.1
[0.9.0]: https://github.com/PredatorWoah/OpenBridge/releases/tag/v0.9.0
[0.5.0]: https://github.com/PredatorWoah/OpenBridge/releases/tag/v0.5.0
