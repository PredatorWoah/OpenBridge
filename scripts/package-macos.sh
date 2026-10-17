#!/usr/bin/env bash
# Packages macos/build/OpenBridge.app as a drag-to-Applications disk image. Usage: package-macos.sh <output.dmg>
set -euo pipefail
output="$1"
root="$(cd "$(dirname "$0")/.." && pwd)"
stage="$(mktemp -d)"
trap 'rm -rf -- "$stage"' EXIT
cp -R "$root/macos/build/OpenBridge.app" "$stage/"
ln -s /Applications "$stage/Applications"
hdiutil create -volname OpenBridge -srcfolder "$stage" -fs HFS+ -format UDZO -ov "$output"
