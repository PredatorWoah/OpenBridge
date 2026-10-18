# OB01 file-transfer protocol

Transport: one request per **TLS** TCP connection, TLS 1.2 minimum. Pin the SHA-256 digest of the server's complete leaf certificate DER, learned by pairing (below). A self-signed certificate is expected. Never send the secret before successful pin verification. The server is reachable only through its selected private IPv4 address (Wi-Fi, or a USB tethering interface) and port: 41987 when it is free, otherwise an ephemeral one.

All integers are big endian. A string is a signed 32-bit nonnegative UTF-8 byte length followed by exactly that many bytes, strictly decoded. Strings are not Java modified UTF-8. Each connection starts with:

| Field | Encoding |
| --- | --- |
| Version | four ASCII bytes `OB01` |
| Pairing secret | 32 raw bytes, received during pairing (each computer has its own) |
| Operation | one byte: `1` LIST, `2` GET, `3` PUT, `4` MKDIR, `5` THUMB, `6` RESUME, `7` CLIPBOARD GET, `8` CLIPBOARD SET, `9` DELETE, `10` RENAME, `11` MEASURE |
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

## Discovery

While sharing over Wi-Fi, the phone announces itself with DNS-SD as `_openbridge._tcp` under its device name, with TXT keys `ip`, `port`, `v=1` and, from 0.11.0, `id`: the first 8 bytes of `F` in lowercase hex, so a computer recognizes a phone it saved even when its address changed. The Mac lists these so nobody types an address. The announcement reveals only the name, address and port that the phone would otherwise show on screen; pairing still needs the 6-digit code. It stops when sharing stops.

## Evolution

Unknown operation/version fails closed; servers older than 0.9.6 answer operations 4–8 with an error, and those older than 0.11.0 operations 9–11 and OBP2, and the computer asks to update the phone app. Future companion features should use a new version/capability negotiation, with explicit permissions, rather than adding implicit access to this file capability. OB01 has no arbitrary path access, background synchronization, public hosting, or command execution. (Screen mirroring is separate: it uses USB debugging over the cable, not this protocol; see SECURITY.md.)
