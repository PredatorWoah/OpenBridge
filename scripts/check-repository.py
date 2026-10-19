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
assert permissions == {"android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE",
                       "android.permission.MANAGE_EXTERNAL_STORAGE", "android.permission.READ_EXTERNAL_STORAGE",
                       "android.permission.WRITE_EXTERNAL_STORAGE", "android.permission.FOREGROUND_SERVICE",
                       "android.permission.FOREGROUND_SERVICE_DATA_SYNC", "android.permission.POST_NOTIFICATIONS",
                       "android.permission.WAKE_LOCK"}, permissions
services = manifest.find("application").findall("service")
assert [(s.attrib[ns + "name"], s.attrib[ns + "exported"], s.attrib[ns + "foregroundServiceType"]) for s in services] == \
    [(".ShareService", "false", "dataSync")], "only the private background-sharing service"
# The legacy storage permissions are only for Android 9 and 10.
assert all(p.attrib.get(ns + "maxSdkVersion") == "29" for p in manifest.findall("uses-permission")
           if p.attrib[ns + "name"].split(".")[-1] in ("READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE")), "storage permissions must stop at API 29"
assert manifest.find("application").attrib[ns + "usesCleartextTraffic"] == "false"
with (root / "macos/Info.plist").open("rb") as handle:
    info = plistlib.load(handle)
assert info["CFBundleExecutable"] == "OpenBridge"
assert info["NSLocalNetworkUsageDescription"]
assert "NSAppTransportSecurity" not in info
assert info["NSPrincipalClass"] == "NSApplication", "without it the window can vanish and the Dock icon cannot bring it back"
assert info["CFBundleIconFile"] == "AppIcon" and (root / "macos/AppIcon.icns").read_bytes()[:4] == b"icns"
application = manifest.find("application").attrib
assert application[ns + "icon"] == "@mipmap/ic_launcher" and application[ns + "roundIcon"] == "@mipmap/ic_launcher_round"
for required in ["LICENSE", "SECURITY.md", "README.md", "docs/protocol.md", "docs/verification.md",
                 "macos/Package.swift", ".github/workflows/ci.yml", ".github/workflows/release.yml", "docs/releasing.md",
                 "CHANGELOG.md", "docs/images/screens.png", "docs/images/connect.png", "docs/images/how-it-works.svg",
                 "macos/AppIcon.icns", "assets/icon/icon.svg",
                 "android/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml",
                 "third_party/scrcpy/LICENSE", "third_party/scrcpy/README.md", "README.zh-CN.md", "scripts/fetch-scrcpy-linux.sh",
                 "desktop/build.gradle.kts", "desktop/src/main/resources/OpenBridge.ico",
                 "shared/compose/org/openbridge/ui/Sun.kt"]:
    assert (root / required).is_file(), required
# The bundled scrcpy server is pinned, and its notice names the same file and version as the Swift client.
fetch = (root / "scripts/fetch-scrcpy-server.sh").read_text()
pin = re.search(r'sha256="([0-9a-f]{64})"', fetch).group(1)
pinned_version = re.search(r'version="([0-9.]+)"', fetch).group(1)
assert pin in (root / "third_party/scrcpy/README.md").read_text(), "scrcpy notice must list the pinned SHA-256"
assert f'version = "{pinned_version}"' in (root / "macos/Sources/BridgeCore/Scrcpy.swift").read_text(), "client and server versions differ"
# The Windows build of scrcpy is pinned too, and listed in the same notice.
windows_pin = re.search(r'sha256="([0-9a-f]{64})"', (root / "scripts/fetch-scrcpy-windows.sh").read_text()).group(1)
assert windows_pin in (root / "third_party/scrcpy/README.md").read_text(), "scrcpy notice must list the Windows SHA-256"
# So is the Linux build, of the same scrcpy version.
linux_fetch = (root / "scripts/fetch-scrcpy-linux.sh").read_text()
linux_pin = re.search(r'sha256="([0-9a-f]{64})"', linux_fetch).group(1)
assert linux_pin in (root / "third_party/scrcpy/README.md").read_text(), "scrcpy notice must list the Linux SHA-256"
assert re.search(r'version="([0-9.]+)"', linux_fetch).group(1) == \
    re.search(r'version="([0-9.]+)"', (root / "scripts/fetch-scrcpy-windows.sh").read_text()).group(1), "Windows and Linux bundle different scrcpy versions"
assert "TargetFormat.Deb" in (root / "desktop/build.gradle.kts").read_text(), "the Linux .deb must be built"
for script in (root / "scripts").glob("*.sh"):
    subprocess.run(["bash", "-n", str(script)], check=True)
# Every app version must have a changelog entry before it can be released.
android_version = re.search(r'versionName = "([^"]+)"', (root / "android/app/build.gradle.kts").read_text()).group(1)
assert android_version == info["CFBundleShortVersionString"], "Android and macOS versions differ"
windows_version = re.search(r'orElse\("([^"]+)"\)', (root / "desktop/build.gradle.kts").read_text()).group(1)
assert windows_version == android_version, "Android and Windows versions differ"
subprocess.run([sys.executable, str(root / "scripts/release-notes.py"), android_version], check=True, stdout=subprocess.DEVNULL)
subprocess.run(["git", "diff", "--check"], cwd=root, check=True)
print("Repository checks passed (manifest, plist, required files, shell syntax, versions and changelog, whitespace).")
