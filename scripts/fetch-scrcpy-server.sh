#!/usr/bin/env bash
# Downloads the scrcpy server used for screen mirroring and verifies its pinned SHA-256. Usage: fetch-scrcpy-server.sh <output>
set -euo pipefail
version="5.0.1"
sha256="764eb6f79811d5211fe9df341120882ba9994c7a61b897d7bf3fb662e53bc536"
output="$1"
if [ -f "$output" ] && echo "$sha256  $output" | shasum -a 256 -c - >/dev/null 2>&1; then exit 0; fi
curl -fsSL --retry 3 -o "$output.download" "https://github.com/Genymobile/scrcpy/releases/download/v$version/scrcpy-server-v$version"
if ! echo "$sha256  $output.download" | shasum -a 256 -c - >/dev/null; then
    rm -f "$output.download"
    echo "scrcpy server checksum mismatch; refusing to bundle it." >&2
    exit 1
fi
mv "$output.download" "$output"
