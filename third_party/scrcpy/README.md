# scrcpy server (third party)

OpenBridge's screen mirroring runs the official **scrcpy server** on the phone. It is not stored in this
repository: `scripts/fetch-scrcpy-server.sh` downloads it from the scrcpy release and checks it against the
pinned SHA-256 below before it is bundled into the Mac app.

- Project: https://github.com/Genymobile/scrcpy
- Version: 5.0.1
- File: `scrcpy-server-v5.0.1` (733,930 bytes)
- SHA-256: `764eb6f79811d5211fe9df341120882ba9994c7a61b897d7bf3fb662e53bc536`
- License: Apache License 2.0 (see [LICENSE](LICENSE)). Copyright (C) 2018 Genymobile, Copyright (C) 2018-2026 Romain Vimont.

OpenBridge does not modify the server. The Mac app's own client for its protocol (in
`macos/Sources/BridgeCore/Scrcpy.swift`) was written for OpenBridge.
