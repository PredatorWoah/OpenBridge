# OB01 file-transfer protocol

Transport: one request per **TLS** TCP connection, TLS 1.2 minimum. Pin the SHA-256 digest of the server's complete leaf certificate DER, learned by pairing (below). A self-signed certificate is expected. Never send the secret before successful pin verification. The server is reachable only through private IPv4 addresses (Wi-Fi, a USB tethering interface, or the phone's own hotspot) and port: 41987 when it is free, otherwise an ephemeral one. From 1.1.1 the phone also listens on port 41986 on every IPv4 address, answering only private peers that reach it through an address outside Android's own networks: its hotspot, on phones that hide the hotspot interface. A computer on the phone's hotspot finds the phone at its default gateway, on 41987 or 41986.

**Fast USB.** While sharing, the phone also listens on its loopback address `127.0.0.1`, on the first free port of 41987 to 41990. Nothing on the network reaches it; a computer reaches it through USB debugging, by opening the ADB service `tcp:<port>` (what `adb forward` does). The connection is the same TLS with the same pin and secret, so a computer tries the four ports in order and keeps the one whose certificate is the phone's. When nothing answers, the computer may start sharing by launching the app over ADB with `am start -n org.openbridge.android/.MainActivity -a org.openbridge.android.SHARE_USB`; the phone then shares over the cable only (loopback), until the user stops it or the cable comes out. It does so only while a cable with USB debugging is plugged in and storage access is already granted; otherwise the app just opens with a note, and the user starts sharing there.

All integers are big endian. A string is a signed 32-bit nonnegative UTF-8 byte length followed by exactly that many bytes, strictly decoded. Strings are not Java modified UTF-8. Each connection starts with:

| Field | Encoding |
| --- | --- |
| Version | four ASCII bytes `OB01` |
| Pairing secret | 32 raw bytes, received during pairing (each computer has its own) |
| Operation | one byte: `1` LIST, `2` GET, `3` PUT, `4` MKDIR, `5` THUMB, `6` RESUME, `7` CLIPBOARD GET, `8` CLIPBOARD SET, `9` DELETE, `10` RENAME, `11` MEASURE, `12` PUT RESUMABLE, `13` OUTBOX, `14` OUTBOX GET, `15` OUTBOX DONE, `16` SEARCH, `17` OUTBOX ALL, `18` INFO |
| Document ID | string, maximum 128 bytes; empty for root |

Authentication is checked before the operation reaches storage. There is no unauthenticated metadata endpoint. A status is one byte: `0` success, or `1` followed by an error string (client limit 512 bytes). Any unexpected status, length, EOF, TLS failure, or timeout means failure. Close the connection; do not parse additional commands.

## Pairing (OBP1 and OBP2)

A new client pairs on a TLS connection **without** a pin, recording the SHA-256 `F` of the server's leaf certificate. Nonces are 32 random bytes. `H` is SHA-256 over an ASCII label followed by the listed byte strings.

1. Client sends the four ASCII bytes `OBP2`, then its name in exactly 128 bytes: UTF-8, cut at a character boundary and padded with zero bytes (like "Aditya's MacBook Air"; the phone removes control characters and shows it). A phone older than 0.11.0 reads the block as a wrong secret and replies status `1` `Pairing rejected` at once; the client then pairs again with `OBP1`, which sends no name (shown as "Computer").
2. Server replies status `0` and the commitment `H("OpenBridge pairing commit", F, Np)` for its nonce `Np` (or status `1` and an error, for example when pairing is unavailable).
3. Client sends its nonce `Nm`.
4. Server sends `Np`. The client checks the commitment against the `F` it saw and fails on a mismatch.
5. Both compute the code: the first 4 bytes of `H("OpenBridge pairing code", F, Nm, Np)` as a big-endian unsigned integer, modulo 1,000,000, written as 6 digits with leading zeros. Both display it; the phone asks its user to confirm that they match (at most about a minute).
6. Server sends status `0` and the 32-byte secret if the user approved, or status `1` and an error. The client then pins `F` and uses the secret for OB01 requests.

**Remembered pairing.** From 0.11.0 the phone keeps the same certificate for as long as the app is installed, and gives each computer its own random secret, kept until the user taps **Forget** on the phone. The phone stores only SHA-256 of each secret. A computer may save `F`, its secret and the phone's last address, and reconnect later without pairing; a request with a forgotten secret gets status `1` and `Pairing rejected`, after which the computer forgets the phone and asks to pair again.

Reference vector: with `F`, `Nm` and `Np` filled with the bytes `01`, `02` and `03`, the commitment is `8ef8a25a…9b356` and the code is `520380`.

## LIST (1)

The ID names a previously returned directory or the empty root. Reply: status; on success, a 32-bit count (0–10,000), then that many entries:

| Entry field | Encoding |
| --- | --- |
| ID | string, maximum 128 bytes; opaque and session scoped |
| Name | string, maximum 255 UTF-8 bytes |
| Directory | byte, exactly 0 or 1 |
| Size | nonnegative signed 64-bit byte count; 0 for directories |

IDs are capabilities resolved only from the granted tree, never paths. Re-pairing invalidates them. The Android cache is capped at 50,000 documents per session. There is no pagination yet; a folder above the listing limit returns an error.

## GET (2)

ID names a previously listed file. Reply: status; on success, signed 64-bit byte count followed by exactly that many raw file bytes. Range 0–68,719,476,736 (64 GiB). There is no trailing status. Once the response begins, source/connection errors terminate the stream, and the client discards a short download. TLS authenticates bytes; no separate checksum is sent.

## PUT (3)

ID names the destination directory. After the common header, send a filename string (1–255 UTF-8 bytes), then a signed 64-bit length in the same range as GET. The filename cannot be `.` or `..`, contain `/`, `\`, NUL or control characters. Names are not URL-decoded: a percent sign is literal. The Android server additionally rejects names containing `" * : < > ? |` or starting with `.openbridge-part-`, because its storage provider would otherwise publish them under a different name.

The server validates storage permissions, filename and collision policy, creates a temporary document, and returns a **ready status**. On error, the client sends no body. On success, the client sends exactly the declared number of bytes. The server closes the output, attempts publication under the requested name, and sends a **second status**. The client must receive this second success before reporting a completed upload. A disconnected upload is aborted. If the final acknowledgement is lost after publication, success is uncertain; refresh before retrying.

Existing names are rejected, not replaced. Android compares names ignoring case, matching its shared storage. LIST omits in-progress upload documents and entries whose names the protocol cannot carry. App-local concurrent uploads reserve names. External provider concurrency and process-kill cleanup limitations are documented in [SECURITY.md](../SECURITY.md).

## MKDIR (4)

ID names the parent directory. After the common header, send a folder name string (same rules as PUT). Reply: status; on success, the new folder's ID string (maximum 128 bytes). An existing file or folder with that name (compared ignoring case on Android) is an error; nothing is reused or replaced.

## THUMB (5)

ID names a previously listed file. After the common header, send a signed 32-bit size, 32 to 1024: the longest side of the preview in pixels. Reply: status; on success, a 32-bit length (1 to 1,048,576) and that many bytes of JPEG. Files without a preview (not a photo or video, or one Android can't decode) return an error.

## RESUME (6)

Like GET, from an offset, to continue an interrupted download. After the common header, send a signed 64-bit offset (0 to the file size). Reply: status; on success, the file's full signed 64-bit size, then exactly size − offset bytes starting at the offset. The client checks that the size matches the file it was downloading and otherwise starts over. The Mac keeps an interrupted download as `.<name>.<size>.openbridge-part` next to its destination and continues it on the next attempt to the same place.

## CLIPBOARD GET (7) and CLIPBOARD SET (8)

The document ID is empty. GET replies with status and, on success, the phone's clipboard text as a string (at most 262,144 UTF-8 bytes; empty when there is no text). SET sends one string of at most 262,144 bytes after the common header and replies with status. Android lets an app read the clipboard only while it is on screen, so GET fails while OpenBridge is in the background. Both need the pairing secret like every other operation.

## DELETE (9)

ID names a previously listed file or folder. Reply: status. A folder is deleted with everything in it; the shared storage itself can't be deleted. The phone app asks nothing: clients must confirm with their user first.

## RENAME (10)

ID names a previously listed file or folder. After the common header, send the new name (same rules as PUT). Reply: status; on success, the item's ID afterwards (a string of at most 128 bytes; it may differ). The item stays in its folder. A name that exists (ignoring case on Android) is an error, except a change of letter case of the item's own name. IDs of anything inside a renamed folder may stop working; list it again.

## MEASURE (11)

ID names a previously listed folder, or the empty root. Reply: status; on success, the signed 64-bit total size in bytes of every file below it, the signed 64-bit number of files, and a byte, `1` when counting finished or `0` when it stopped early (after 5 seconds or 50,000 items) and the totals are a lower bound. Links are not followed. It hands out no IDs.

## PUT RESUMABLE (12)

Like PUT, so an interrupted upload can continue. After the common header, send the filename, the signed 64-bit length, then a 32-byte content key: the client's summary of the file (OpenBridge uses SHA-256 over the size, the modification time in milliseconds and the first MiB). The ready status is followed by a signed 64-bit count of bytes the phone already holds from an earlier, interrupted upload of the same name, length and key into the same folder (0 if none); the client then sends exactly length minus that many bytes, from that offset, and receives the second status as with PUT. An interrupted resumable upload is kept on the phone under a hidden name (`.openbridge-part-resume-…`, derived from the name, length and key) and removed after a week if nobody continues it. A different key never continues an old part, so a file edited in place starts over.

## OUTBOX (13), OUTBOX GET (14) and OUTBOX DONE (15)

Files the user sent from the phone (Share › OpenBridge), waiting for a computer to collect them. The phone never connects out: the computer asks.

- **OUTBOX**: the document ID is empty; after the common header, send a signed 32-bit wait in milliseconds (0 to 25,000). The phone answers at once if files are waiting, or when one arrives, or after the wait. Reply: status; on success, a 32-bit count (0 to 1,000) and that many entries of ID (string, at most 128 bytes), name (string, at most 255 bytes, a valid filename) and signed 64-bit size.
- **OUTBOX GET**: the ID names a waiting file; send a signed 64-bit offset. Reply as RESUME: status, the full size, then the bytes from the offset.
- **OUTBOX DONE**: the ID names a waiting file the computer has saved completely. Reply: status. The phone deletes its copy; any computer that asks later doesn't see it.

## SEARCH (16)

ID names a previously listed folder, or the empty root. After the common header, send the text to find (a string of 1 to 255 bytes; surrounding spaces are ignored). The phone looks through every folder below for files and folders whose names contain the text, ignoring case, for up to 8 seconds, 50,000 items or 500 hits. Reply: status; on success, a 32-bit count, then for each hit its ID, name, directory byte and size (as in LIST) and its folder as a string of at most 1,024 bytes: folder names joined by `/`, relative to the searched folder (empty for a hit directly inside it); then a byte, `1` when the search finished or `0` when it stopped early. Hit IDs work like listed ones. When sharing the whole phone, the phone first asks Android's media index, so most hits come first and at once, then walks the folders for files the index doesn't know; a hit is never listed twice.

## Discovery

While sharing over Wi-Fi (or, when there is no Wi-Fi, over its own hotspot), the phone announces itself with DNS-SD as `_openbridge._tcp` under its device name, with TXT keys `ip`, `port`, `v=1` and, from 0.11.0, `id`: the first 8 bytes of `F` in lowercase hex, so a computer recognizes a phone it saved even when its address changed. The Mac lists these so nobody types an address. The announcement reveals only the name, address and port that the phone would otherwise show on screen; pairing still needs the 6-digit code. It stops when sharing stops.

## OUTBOX ALL (17)

Like OUTBOX, for computers that also take texts (from 0.13.0). Same request. Reply: status; on success, a 32-bit count (0 to 1,000) and that many items of ID, name, a kind byte (`0` file, `1` text) and signed 64-bit size. A text's name is a label such as `Text`; its content, read with OUTBOX GET, is UTF-8 of at most 262,144 bytes, meant for the computer's clipboard. OUTBOX DONE drops texts like files. OUTBOX (13) never lists texts, and waits only for files, so computers from before 0.13.0 keep working.

## INFO (18)

The document ID is empty. Reply: status; on success, the phone app's version (string, at most 64 bytes, like `1.0.0`), a signed 32-bit revision (the highest operation number the phone answers: 18 from 0.13.0) and the phone's name (string, at most 128 bytes). A computer can check the revision before offering an operation, instead of waiting for the "update OpenBridge on the phone" error.

## Stability

OB01 is frozen from 1.0.0: operations 1 to 18, their requests, replies and limits never change. A 1.x computer works with any 1.x phone. New features get new operation numbers, listed here, and a computer checks INFO's revision (or handles the "Unsupported operation; update OpenBridge on the phone" error) before using one. Anything that can't fit that rule needs a new version magic instead of `OB01`. The Java server is fuzzed with random and half-valid requests on every build (`ProtocolTest`), so a malformed request is answered with an error or a closed connection, never a crash.

## Evolution

Unknown operation/version fails closed; servers older than 0.9.6 answer operations 4–8 with an error, and those older than 0.11.0 operations 9–11 and OBP2, and those older than 0.12.0 operations 12–15 (clients then upload with PUT and don't collect files), and those older than 0.12.1 operation 16, and those older than 0.13.0 operations 17 and 18 (clients then collect only files with OUTBOX), and the computer asks to update the phone app. Future companion features should use a new version/capability negotiation, with explicit permissions, rather than adding implicit access to this file capability. OB01 has no arbitrary path access, background synchronization, public hosting, or command execution. (Screen mirroring is separate: it uses USB debugging over the cable, not this protocol; see SECURITY.md.)
