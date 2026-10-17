# OB01 file-transfer protocol

Transport: one request per **TLS** TCP connection, TLS 1.2 minimum. Pin the SHA-256 digest of the server's complete leaf certificate DER, learned by pairing (below). A self-signed certificate is expected. Never send the secret before successful pin verification. The server is reachable only through its selected private IPv4 address (Wi-Fi, or a USB tethering interface) and ephemeral port.

All integers are big endian. A string is a signed 32-bit nonnegative UTF-8 byte length followed by exactly that many bytes, strictly decoded. Strings are not Java modified UTF-8. Each connection starts with:

| Field | Encoding |
| --- | --- |
| Version | four ASCII bytes `OB01` |
| Pairing secret | 32 raw bytes, received during pairing |
| Operation | one byte: `1` LIST, `2` GET, `3` PUT |
| Document ID | string, maximum 128 bytes; empty for root |

Authentication is checked before the operation reaches storage. There is no unauthenticated metadata endpoint. A status is one byte: `0` success, or `1` followed by an error string (client limit 512 bytes). Any unexpected status, length, EOF, TLS failure, or timeout means failure. Close the connection; do not parse additional commands.

## Pairing (OBP1)

A new client pairs on a TLS connection **without** a pin, recording the SHA-256 `F` of the server's leaf certificate. Nonces are 32 random bytes. `H` is SHA-256 over an ASCII label followed by the listed byte strings.

1. Client sends the four ASCII bytes `OBP1`.
2. Server replies status `0` and the commitment `H("OpenBridge pairing commit", F, Np)` for its nonce `Np` (or status `1` and an error, for example when pairing is unavailable).
3. Client sends its nonce `Nm`.
4. Server sends `Np`. The client checks the commitment against the `F` it saw and fails on a mismatch.
5. Both compute the code: the first 4 bytes of `H("OpenBridge pairing code", F, Nm, Np)` as a big-endian unsigned integer, modulo 1,000,000, written as 6 digits with leading zeros. Both display it; the phone asks its user to confirm that they match (at most about a minute).
6. Server sends status `0` and the 32-byte secret if the user approved, or status `1` and an error. The client then pins `F` and uses the secret for OB01 requests.

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

## Evolution

Unknown operation/version fails closed. Future companion features should use a new version/capability negotiation, with explicit permissions, rather than adding implicit access to this file capability. OB01 has no deletion, arbitrary path access, background synchronization, public hosting, or command execution.
