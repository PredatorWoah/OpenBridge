# Verification status and manual acceptance

Status as of 1.1.0. **Everything below is automated; nothing has yet been verified on physical devices.**

## Continuous integration

| Trigger | Jobs | What runs |
| --- | --- | --- |
| Every push and pull request | `protocol`, `mtp`, `repository` | Java protocol tests, the real Java TLS server against a Python client, the MTP protocol tests (Swift on Linux), and repository checks. |
| Manual (Actions → *Build and protocol checks* → Run workflow) | adds `android`, `macos` | Android build and lint; macOS build, all Swift tests (including the Java/Swift TLS interoperability test and the MTP client tests) and the app bundle. |
| Release (Actions → *Release*, or a `v*` tag) | `verify`, `android`, `macos`, `publish` | Checks the version matches both apps and has a changelog entry, reruns the tests, builds both apps and publishes them with checksums. |

Native builds stay manual to save CI minutes. Run them before merging anything that touches `android/` or `macos/`.

## Automated coverage

- **Wi-Fi protocol (OB01):** authentication, malformed headers, strict UTF-8 and bounds, opaque-ID traversal rejection, listings, binary multi-chunk, Unicode and empty transfers, collisions that preserve the original, interrupted-upload cleanup, commit failures, and Android filename rules (storage-provider characters, case-insensitive collisions, reserved temporary names).
- **TLS:** a real Java TLS server exercised by a Python client and by the production Swift client, with a pinned certificate, both transfer directions, progress, collisions, temporary-file cleanup, and wrong-pin and wrong-secret rejection. The fixture is test-only and never shipped.
- **USB File transfer (MTP):** a simulated phone that enforces USB bulk rules (short packets end a data phase, exact multiples need a zero-length packet, unknown-length data phases) covers sessions, storages, listings, downloads and uploads across packet boundaries, files of 4 GiB or more, free-space checks, case-insensitive collisions, error responses, recovery after a USB failure, device identity on reconnect, and cleanup of failed uploads.
- **Builds:** Android lint, the macOS release bundle, and checks that both apps reference the app icon.

## Physical Android + Mac acceptance (not yet performed)

Start with one Pixel and one Samsung. USB File transfer stays labelled beta until these pass.

Record device model, Android/API version, storage/provider, macOS version, network type, commit SHA, result, and any logs without secrets. All items remain unverified until performed:

- [ ] Build/install the APK and Mac bundle; confirm local-network prompt/allow behavior on supported macOS versions.
- [ ] Choose an internal-storage subfolder and an SD-card subfolder; reject cloud providers and unsupported roots. Confirm access outside the chosen tree is impossible. Confirm revoked grants produce a clear error.
- [ ] Verify AndroidKeyStore certificate generation and TLS handshakes on Android 8/API 26 and current Android, including TLS 1.2/1.3 compatibility and screen rotation.
- [ ] Pair by copying the phone's fingerprint and secret. Wrong fingerprint, wrong secret, old credentials and a public endpoint must fail without file access. A plaintext client must not obtain metadata.
- [ ] Browse empty, nested, Unicode and large folders. Deleted/stale IDs should fail safely; refresh should recover.
- [ ] Transfer empty files, Unicode filenames, random binary files, files larger than 64 KiB, and a multi-gigabyte file both ways. Compare SHA-256 locally on both devices. Confirm progress, bounded memory, and useful errors.
- [ ] Upload/download a duplicate name and simultaneous same-name uploads. Verify original bytes remain intact. Test built-in SAF provider rename behavior and a competing external writer. Stop release if exclusive collision behavior is not reliable under the supported usage model.
- [ ] Switch off Wi-Fi, change Wi-Fi addresses, enable guest-network isolation, cancel, stop sharing, lock the phone, switch apps and rotate the device during transfers. The server should close on background/network change, partial transfers should fail, normal partials should be removed, and restart should require new credentials.
- [ ] Fill Android storage and Mac destination storage, revoke folder access, remove the SD card, select read-only storage, and edit the source while transferring. Verify errors and cleanup without damaging existing files.
- [ ] Force-kill both apps mid-transfer; identify/remove documented temporary files and confirm no incomplete upload is presented as success. Refresh after a lost final acknowledgement.
- [ ] USB tethering: on an NCM phone (e.g. Pixel, Android 11+), enable tethering, start sharing, confirm "Connection: USB cable", and transfer both ways with Wi-Fi off on both devices. Unplug mid-transfer and turn tethering off: sharing must stop. Record whether an RNDIS phone shows no Mac interface. Check the Mac does not unexpectedly route internet over the phone.
- [ ] USB File transfer (MTP), Mac app only: on several phones (Pixel, Samsung, one older Android), choose File transfer and press Connect over USB. Browse internal storage and an SD card; download and upload small, multi-megabyte and multi-gigabyte files, including files above 4 GiB in both directions; compare SHA-256. Try an upload larger than the phone's free space. Cancel mid-transfer and unplug mid-transfer, then reconnect: no partial upload may remain on the phone. Repeat with Photos or Image Capture open. Record any phone that is not found.
- [ ] Check that the listening socket is bound only to the selected private Wi-Fi IPv4 address and accepts no public/cellular/VPN interface. Confirm no router port mapping is requested. This cannot prevent deliberate external forwarding by a network administrator.

The MVP intentionally stops when Android leaves the foreground. A foreground service, wake lock, persistent pairing and transfer resume require a later explicit design and permission review; they are not hidden prerequisites for Phase 1.
