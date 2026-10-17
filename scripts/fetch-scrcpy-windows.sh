#!/usr/bin/env bash
# Downloads scrcpy for Windows (screen mirroring, with its own adb) and verifies its pinned SHA-256, then unpacks it
# where the Windows app's resources are packaged. Usage: fetch-scrcpy-windows.sh <resources dir>
set -euo pipefail
version="5.0.1"
sha256="b12a2c4ee8be317422451fc7dcf8ee20a71b5ea7ef9ad73ddd825a25316227a5"
target="$1/windows/scrcpy"
work="$(mktemp -d)"
trap 'rm -rf -- "$work"' EXIT
curl -fsSL --retry 3 -o "$work/scrcpy.zip" "https://github.com/Genymobile/scrcpy/releases/download/v$version/scrcpy-win64-v$version.zip"
if ! echo "$sha256  $work/scrcpy.zip" | sha256sum -c - >/dev/null; then
    echo "scrcpy for Windows checksum mismatch; refusing to bundle it." >&2
    exit 1
fi
unzip -q "$work/scrcpy.zip" -d "$work/unpacked"
rm -rf "$target" && mkdir -p "$target"
cp -R "$work/unpacked/scrcpy-win64-v$version/." "$target/"
test -f "$target/scrcpy.exe" && test -f "$target/adb.exe"
