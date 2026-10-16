#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../macos"
swift build -c release --product OpenBridge
bundle="build/OpenBridge.app"
mkdir -p "$bundle/Contents/MacOS"
cp "$(swift build -c release --show-bin-path)/OpenBridge" "$bundle/Contents/MacOS/"
cp Info.plist "$bundle/Contents/Info.plist"
codesign --force --sign - "$bundle"
echo "Built macos/$bundle (local ad-hoc signature)."
