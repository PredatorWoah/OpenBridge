# scrcpy (third party)

OpenBridge's screen mirroring runs the official **scrcpy server** on the phone. It is not stored in this
repository: `scripts/fetch-scrcpy-server.sh` downloads it from the scrcpy release and checks it against the
pinned SHA-256 below before it is bundled into the Mac app.

- Project: https://github.com/Genymobile/scrcpy
- Version: 5.0.1
- File: `scrcpy-server-v5.0.1` (733,930 bytes)
- SHA-256: `764eb6f79811d5211fe9df341120882ba9994c7a61b897d7bf3fb662e53bc536`
- License: Apache License 2.0 (see [LICENSE](LICENSE)). Copyright (C) 2018 Genymobile, Copyright (C) 2018-2026 Romain Vimont.

The Windows app bundles the official **scrcpy for Windows** build instead, which includes the same server, the
scrcpy client and Google's `adb.exe`. `scripts/fetch-scrcpy-windows.sh` downloads it from the same release and
checks it before it is packaged next to the app:

- File: `scrcpy-win64-v5.0.1.zip`
- SHA-256: `b12a2c4ee8be317422451fc7dcf8ee20a71b5ea7ef9ad73ddd825a25316227a5`
- Contents keep their own licenses and notices, shipped unmodified in the app's `scrcpy` folder (scrcpy and its
  libraries under Apache-2.0, LGPL and others; adb under Apache-2.0 from the Android SDK platform tools).

OpenBridge does not modify the server or the Windows build. The Mac app's own client for its protocol (in
`macos/Sources/BridgeCore/Scrcpy.swift`) was written for OpenBridge.
