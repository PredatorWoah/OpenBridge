#!/usr/bin/env bash
# Downloads scrcpy for Linux x86_64 (screen mirroring, with its own adb) and verifies its pinned SHA-256, then unpacks
# it where the Linux app's resources are packaged. Usage: fetch-scrcpy-linux.sh <resources dir>
set -euo pipefail
version="5.0.1"
sha256="9f969d30cc574816077edecda65719c1c70b0aee1df1ba5fa00779ac07b8fd6e"
target="$1/linux-x64/scrcpy"
work="$(mktemp -d)"
trap 'rm -rf -- "$work"' EXIT
curl -fsSL --retry 3 -o "$work/scrcpy.tar.gz" "https://github.com/Genymobile/scrcpy/releases/download/v$version/scrcpy-linux-x86_64-v$version.tar.gz"
if ! echo "$sha256  $work/scrcpy.tar.gz" | sha256sum -c - >/dev/null; then
    echo "scrcpy for Linux checksum mismatch; refusing to bundle it." >&2
    exit 1
fi
mkdir "$work/unpacked"
tar -xzf "$work/scrcpy.tar.gz" -C "$work/unpacked" --no-same-owner
rm -rf "$target" && mkdir -p "$target"
cp -R "$work/unpacked/scrcpy-linux-x86_64-v$version/." "$target/"
test -x "$target/scrcpy" && test -x "$target/adb" && test -f "$target/scrcpy-server"
