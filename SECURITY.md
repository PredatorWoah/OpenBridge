# Security model

OpenBridge grants a paired Mac read and upload access to **one approved SAF tree** while the Android app is visible. Pair only with a Mac you trust, on a trusted local network. There is no identity/account service.

- The Android listener binds only to a specific RFC1918 IPv4 address: either on a USB tethering interface (`rndis*`, `usb*`, `ncm*`, preferred when present, and reachable only by the cable-connected computer), or on an Android `TRANSPORT_WIFI` network with `NOT_VPN`. Unplugging the cable or turning tethering off stops sharing within about two seconds. It never binds wildcard, public, cellular, or IPv6 addresses. The peer address must also be RFC1918. It performs no UPnP, NAT traversal, discovery broadcast, or port forwarding. The Mac accepts only literal RFC1918 endpoints, with no DNS or redirects. No software can prevent an administrator from deliberately forwarding or proxying a local port: do not expose it that way.
- All commands and file contents use TLS 1.2 or later. Android generates a new RSA-2048 AndroidKeyStore key and self-signed certificate for each start. After pairing, the Mac pins the SHA-256 digest of the exact leaf certificate DER and checks it on every connection **before sending credentials**. Public CA trust/hostname validation is replaced by this pin, not disabled without a replacement.
- **Pairing (OBP1) is numeric comparison, as in Bluetooth.** The Mac connects without a pin and records the certificate it sees. The phone first sends a commitment (SHA-256 over its certificate fingerprint and a random nonce), then receives the Mac's random nonce, then reveals its own. The Mac checks the commitment against the certificate it saw, and both sides show a 6-digit code derived from the fingerprint and both nonces. A man in the middle presents a different certificate to each side and must fix its nonces before seeing the other side's, so the two codes match with probability about 1 in 10^6 per attempt; the user cancels on a mismatch. Only after the user taps **Pair** on the phone does it send the session secret; the Mac then pins the certificate. The phone handles one pairing request at a time and cancels it after a minute. Pairing depends on the user actually comparing the codes.
- A cryptographically random 256-bit bearer secret is required on every connection and compared with `MessageDigest.isEqual`. It is sent only to a Mac the user approved during pairing, and is never shown, put in URLs, preferences, analytics, logs, or a cloud service. The Mac keeps credentials in memory only. Stop/restart invalidates them. This MVP has one sharing session, with no persistent device registry or individual-device revocation. The AndroidKeyStore alias is replaced on the next start.
- The built-in Android external-storage document provider is the only supported authority. Android's picker grants a tree capability; no all-files/storage permission is requested. The server returns session-local opaque document IDs produced from that tree's enumeration. Client strings are never passed to filesystem paths or interpreted as document URIs. Unknown IDs, slash/backslash filenames, dot segments, controls, malformed UTF-8, oversized fields, negative/oversized file lengths, and unsupported operations are rejected.
- Uploads reserve destination names within the process, reject existing names (ignoring case) and names the provider would rewrite, stream into a new temporary document, close the stream, recheck the destination, then rename and verify the resulting name. A failed transfer is deleted on cleanup. SAF does **not** offer a general atomic exclusive rename contract across external writers. Avoid modifying the shared folder with another app during transfers. Unexpected provider behavior or a simultaneous external rename remains a device-verification concern; the built-in provider must preserve the collision contract before release.
- Mac downloads use mode-0600 sibling temporary files and a move which rejects an existing destination. Neither side loads whole files into memory. Downloads are bounded to the declared size; source files must not be edited during transfer. There is no snapshot or resumable-transfer guarantee.
- Two concurrent Android handlers, a minimal accept backlog, 30-second socket read timeouts, bounded request fields/listings, and a bounded document-ID cache limit resource consumption. The Mac has a 30-second timeout per network read/write/handshake. These are safeguards for trusted-LAN use, not a promise of denial-of-service resistance on hostile networks. Android blocking socket writes may wait until transport failure or explicit stop if a peer stops reading.
- Stopping closes the listener and active sockets. Network loss/address change and Activity backgrounding stop sharing. The screen is kept on while sharing so the display timeout does not interrupt transfers; locking the phone manually still stops sharing. Temporary files may survive an abrupt process kill; filenames clearly identify them. Storage access is deliberately not persisted across process restarts.

## USB File transfer (MTP)

The Mac app's **Connect over USB** does not use the protocol above. It talks to Android's built-in File transfer (MTP) mode directly, so none of the guarantees above apply to it:

- **No TLS and no pairing secret.** The trust boundary is the USB cable plus the user unlocking the phone and choosing File transfer. Data crosses the cable unencrypted, as with any MTP client, including Windows.
- **No single-folder restriction.** The Mac can see, download and upload anything Android exposes in File transfer mode, typically all shared internal storage and SD cards. OpenBridge never deletes or replaces files except for removing its own partial upload after a failure.
- **Device identity.** A connection is bound to the phone it was opened on (manufacturer, model and serial number reported over MTP). After a USB error the Mac only reconnects to that same phone, and it only deletes a failed upload after re-reading the object and confirming its name, storage and folder still match.
- **macOS photo import.** If macOS's background photo import service (`ptpcamerad`) holds the phone, OpenBridge stops it once so it can open the device; macOS restarts it on demand.

Choose File transfer only on a Mac you trust, and switch the phone back to *No data transfer* afterwards. This mode is **beta** until verified on physical phones.

## Verifying downloads

From 1.0.0, every Android APK is signed with the OpenBridge release key, whose certificate SHA-256 fingerprint is:

```
4E:41:B5:C8:0D:00:E4:0B:26:4A:BF:E6:5C:98:6A:8B:6D:1F:4F:DF:F7:E6:D1:7E:EB:66:10:5A:E4:BB:16:85
```

Check an APK with `apksigner verify --print-certs OpenBridge-<version>.apk` (Android SDK build tools). Do not install an APK signed with a different key. Each release also lists its files' SHA-256 checksums in `SHA256SUMS.txt`.

No real-device test, penetration test, or third-party security audit is claimed. Validate the [device checklist](docs/verification.md) before relying on the app for sensitive data.

Please avoid posting credentials or sensitive files in public bug reports. Report a vulnerability privately to the repository owner through GitHub's private vulnerability reporting if enabled, or request a private contact without posting exploit details.
