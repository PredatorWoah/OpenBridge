#!/usr/bin/env python3
"""Dependency-free repository checks, usable even without either native toolchain.

These validate packaging and critical invariants, not application execution.
"""
from pathlib import Path
import plistlib
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parent.parent
manifest = ET.parse(root / "android/app/src/main/AndroidManifest.xml").getroot()
ns = "{http://schemas.android.com/apk/res/android}"
permissions = {p.attrib[ns + "name"] for p in manifest.findall("uses-permission")}
assert permissions == {"android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE"}
assert manifest.find("application").attrib[ns + "usesCleartextTraffic"] == "false"
with (root / "macos/Info.plist").open("rb") as handle:
    info = plistlib.load(handle)
assert info["CFBundleExecutable"] == "OpenBridge"
assert info["NSLocalNetworkUsageDescription"]
assert "NSAppTransportSecurity" not in info
for required in ["LICENSE", "SECURITY.md", "README.md", "docs/protocol.md", "docs/verification.md",
                 "macos/Package.swift", ".github/workflows/ci.yml", ".github/workflows/release.yml",
                 "CHANGELOG.md", "docs/images/hero.svg", "docs/images/how-it-works.svg"]:
    assert (root / required).is_file(), required
for script in (root / "scripts").glob("*.sh"):
    subprocess.run(["bash", "-n", str(script)], check=True)
# Every app version must have a changelog entry before it can be released.
android_version = re.search(r'versionName = "([^"]+)"', (root / "android/app/build.gradle.kts").read_text()).group(1)
assert android_version == info["CFBundleShortVersionString"], "Android and macOS versions differ"
subprocess.run([sys.executable, str(root / "scripts/release-notes.py"), android_version], check=True, stdout=subprocess.DEVNULL)
subprocess.run(["git", "diff", "--check"], cwd=root, check=True)
print("Repository checks passed (manifest, plist, required files, shell syntax, versions and changelog, whitespace).")
