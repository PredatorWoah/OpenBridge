# Testing and verification

OpenBridge is tested three ways: automated tests on every change, native builds of every app in CI, and real phones.
Real phones matter most, because every Android brand bends USB File transfer (MTP) a little differently. If you
test on your phone, the [checklist below](#test-it-on-your-phone) takes about 20 minutes, and a
[device report](https://github.com/PredatorWoah/OpenBridge/issues/new?template=device-report.yml) helps everyone
with the same phone.

## Real-device results

| Date | Devices | Result |
| --- | --- | --- |
| 2026-10-17 | vivo X300 Pro (V2514) + MacBook Air M1 | USB File transfer: phone found by itself, folders browsed, a 7.83 GB file downloaded. Found and fixed: a crash on connect (0.9.3), a disappearing window and blank folder lists (0.9.5). |
| 2026-10-17 | vivo X300 Pro | The phone's USB tethering uses RNDIS, which macOS doesn't support; the Mac app explains this and points to File transfer. |
| 2026-10-18 | vivo X300 Pro + MacBook Air M1 | Folders over USB needed several refreshes (fixed in 0.9.10); toolbar text squashed when narrow, no folder sizes or deep search over USB (fixed in 0.12.1); folders counted inside themselves over USB, giving huge sizes and duplicate search results (fixed in 0.12.2). |

Add yours with a [device report](https://github.com/PredatorWoah/OpenBridge/issues/new?template=device-report.yml).

## Automated checks

| When | Jobs | What runs |
| --- | --- | --- |
| Every push and pull request | `protocol`, `mtp`, `repository` | The Java protocol tests (including fuzzing every operation with 35,000 random and half-valid requests), a real Java TLS server against a Python client, the MTP tests (Swift on Linux) against a simulated phone with real Android quirks, and repository checks. |
| Manually (Actions › *Build and protocol checks* › Run workflow) | adds `android`, `macos`, `windows`, `linux` | Android build and lint with a release-signing check; macOS build, every Swift test (with the Java/Swift TLS interoperability test) and the app bundle; the Windows and Linux apps built, tested, packaged and launched for 25 seconds to prove they start. |
| Release (Actions › *Release*) | `verify`, the four builds, `publish` | Checks the version in every app and the changelog, reruns the tests, builds everything and publishes it with `SHA256SUMS.txt`. |

What the tests cover:

- **The Wi‑Fi protocol (OB01):** authentication, pairing vectors and declines, every operation's success and failure paths, strict UTF-8 and bounds, opaque IDs that can't escape the shared storage, collisions that keep the original, cleanup after interrupted uploads, resumable uploads that never mix old and new bytes, texts and files in the outbox, and the frozen operation set. A fuzzer sends random and half-valid requests to a phone with memory storage and to one with a real folder: every request must get an error or a closed connection, never a crash or a hang, and nothing may change outside the shared folder.
- **TLS:** a real Java TLS server against the Python client and the Mac's Swift client, with pinned certificates, transfers both ways, wrong pins and wrong secrets.
- **USB File transfer (MTP):** a simulated phone that enforces USB bulk rules and behaves like real Android phones: folders that list themselves, items listed twice, missing dates and file names in property lists, 0-byte files, Chinese and emoji names, odd storage sizes. It covers sessions, listings, downloads and uploads across packet boundaries, files of 4 GiB and more, free-space checks, collisions, errors, recovery after a USB failure, sizes and search.
- **Desktop app:** the client against the real Java server: pairing, browsing, transfers, resuming, the outbox and texts, search, Fast USB matching.

## Test it on your phone

You need: the phone app and one computer app from the [latest release](https://github.com/PredatorWoah/OpenBridge/releases/latest).
Tick what works and note anything odd. **Settings › Copy diagnostics** (on the phone and the computer) copies a short
report without any secrets to paste into your device report.

**Wi‑Fi**
- [ ] Tap the button on the phone. The computer lists the phone under *Phones nearby*; pair by comparing the 6 digits.
- [ ] Browse a few folders, open a photo, switch to grid view. Folder sizes appear after a moment.
- [ ] Download a big video (1 GB or more) and upload a folder of photos. Turn Wi‑Fi off on the phone halfway through a download, turn it back on, and press **Resume**.
- [ ] Search the whole phone for a file you know (try part of its name, like `.pdf`). Photos and videos should show up instantly.
- [ ] Rename a file and delete a test file from the computer.
- [ ] Quit both apps, then start sharing again: the computer reconnects by itself, without a code.

**USB cable**
- [ ] Plug in, unlock the phone and choose **File transfer**. The computer finds it by itself (Mac and Windows: in OpenBridge's own window).
- [ ] Browse, download and upload over the cable. Check folder sizes and search.
- [ ] Turn on USB debugging (Settings › About phone › tap Build number 7 times › Developer options). The computer switches to **Fast USB**; allow USB debugging on the phone if asked. Search and sizes should now be instant.
- [ ] **Mirror screen**, then **Go wireless** / **Mirror over Wi‑Fi** and unplug.

**From the phone**
- [ ] In the gallery, select two photos and tap **Share › OpenBridge**. They land in *Downloads/OpenBridge* on the computer.
- [ ] Share a 2 GB or larger video the same way and check the phone's free space doesn't drop by that much.
- [ ] Copy some text on the phone and use the **Send clipboard** tile or button. It's on the computer's clipboard a second later.
- [ ] On the computer, open **Texts**, type something and **Send**. It's on the phone's clipboard.
- [ ] **Import new photos** twice: the second time only new photos come over.

**Networks that get in the way**
- [ ] On a guest, hotel or campus Wi‑Fi, the computer explains that the network keeps devices apart. Turn on **Settings › Phone hotspot** on the phone (Android 13+), join it from the computer, and connect.

**Looks and comfort**
- [ ] Try each look (Dusk, Aurora, Tide) on the phone and the computer.
- [ ] Turn on your system's reduce-motion setting: backgrounds stop moving.
- [ ] Use the computer browser with only the keyboard: arrows, Enter, Backspace, Delete, F2.
- [ ] **Update** from the update pill when a new version comes out.

## Before a release

The maintainer runs the native builds (Actions › *Build and protocol checks*) on the release commit and repeats the
Wi‑Fi, USB and *From the phone* parts above on at least one phone and one computer. Any check that fails blocks the
release, and every fix found on real hardware is recorded in the table above.
