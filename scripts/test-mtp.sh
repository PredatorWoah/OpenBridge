#!/usr/bin/env bash
# Runs the platform-independent MTP protocol tests with any Swift 5.9+ toolchain, including Linux.
# The full macOS package (USB transport, UI) is built and tested by `swift test --package-path macos`.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf -- "$work"' EXIT
mkdir -p "$work/Sources" "$work/Tests"
ln -s "$root/macos/Sources/MTPCore" "$work/Sources/MTPCore"
ln -s "$root/macos/Sources/MTPTestSupport" "$work/Sources/MTPTestSupport"
ln -s "$root/macos/Tests/MTPCoreTests" "$work/Tests/MTPCoreTests"
cat > "$work/Package.swift" <<'PACKAGE'
// swift-tools-version: 5.9
import PackageDescription
let package = Package(name: "MTPCheck", targets: [
    .target(name: "MTPCore"),
    .target(name: "MTPTestSupport", dependencies: ["MTPCore"]),
    .testTarget(name: "MTPCoreTests", dependencies: ["MTPCore", "MTPTestSupport"])
])
PACKAGE
swift test --package-path "$work"
