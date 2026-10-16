#!/usr/bin/env python3
"""Print the CHANGELOG.md section for a version. Fails if the version has no entry.

Usage: python3 scripts/release-notes.py 1.0.0
"""
from pathlib import Path
import re
import sys

if len(sys.argv) != 2:
    sys.exit(__doc__)
version = sys.argv[1]
text = (Path(__file__).resolve().parent.parent / "CHANGELOG.md").read_text(encoding="utf-8")
match = re.search(rf"^## \[{re.escape(version)}\][^\n]*\n(.*?)(?=^## \[|^\[[^\]]+\]: |\Z)", text, re.M | re.S)
if not match or not match.group(1).strip():
    sys.exit(f"CHANGELOG.md has no entry for {version}. Add one before releasing.")
print(match.group(1).strip())
print(f"\n**Checksums:** see `SHA256SUMS.txt`. Full history: [CHANGELOG.md](https://github.com/PredatorWoah/OpenBridge/blob/v{version}/CHANGELOG.md)")
