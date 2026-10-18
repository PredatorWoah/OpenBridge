#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../macos"
# One universal binary: Apple Silicon and Intel Macs.
arches=(--arch arm64 --arch x86_64)
swift build -c release --product OpenBridge "${arches[@]}"
bundle="build/OpenBridge.app"
mkdir -p "$bundle/Contents/MacOS"
cp "$(swift build -c release "${arches[@]}" --show-bin-path)/OpenBridge" "$bundle/Contents/MacOS/"
lipo -info "$bundle/Contents/MacOS/OpenBridge"
cp Info.plist "$bundle/Contents/Info.plist"
mkdir -p "$bundle/Contents/Resources"
cp AppIcon.icns "$bundle/Contents/Resources/AppIcon.icns"
# Screen mirroring helper (scrcpy server, Apache-2.0), pinned by SHA-256, with its license.
bash ../scripts/fetch-scrcpy-server.sh build/scrcpy-server.jar
cp build/scrcpy-server.jar "$bundle/Contents/Resources/scrcpy-server.jar"
cp ../third_party/scrcpy/LICENSE "$bundle/Contents/Resources/scrcpy-LICENSE.txt"
codesign --force --sign - "$bundle"
echo "Built macos/$bundle (local ad-hoc signature)."
