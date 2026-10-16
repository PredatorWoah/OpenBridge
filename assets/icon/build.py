#!/usr/bin/env python3
"""Render the icon SVGs to the Android PNG layers and macOS AppIcon.icns. Needs Node.js with Playwright."""
from pathlib import Path
import json
import struct
import subprocess
import tempfile

here = Path(__file__).resolve().parent
root = here.parent.parent
res = root / "android/app/src/main/res"
densities = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}
mac_sizes = [16, 32, 64, 128, 256, 512, 1024]

renderer = """
const { chromium } = require('playwright'); const fs = require('fs');
(async () => {
  const browser = await chromium.launch(process.env.CHROMIUM ? { executablePath: process.env.CHROMIUM } : {});
  const page = await browser.newPage();
  for (const [src, out, size] of JSON.parse(fs.readFileSync(process.argv[2]))) {
    await page.setViewportSize({ width: size, height: size });
    const uri = 'data:image/svg+xml;base64,' + fs.readFileSync(src).toString('base64');
    await page.setContent(`<body style="margin:0;background:transparent"><img src="${uri}" width="${size}" height="${size}" style="display:block"></body>`);
    await page.waitForFunction(() => document.images[0].complete);
    await page.screenshot({ path: out, omitBackground: true, clip: { x: 0, y: 0, width: size, height: size } });
  }
  await browser.close();
})();
"""

with tempfile.TemporaryDirectory() as work:
    work = Path(work)
    jobs = [[str(here / "macos.svg"), str(work / f"mac-{s}.png"), s] for s in mac_sizes]
    for density, size in densities.items():
        folder = res / f"mipmap-{density}"
        folder.mkdir(parents=True, exist_ok=True)
        jobs.append([str(here / "android-foreground.svg"), str(folder / "ic_launcher_foreground.png"), size])
        jobs.append([str(here / "android-background.svg"), str(folder / "ic_launcher_background.png"), size])
    jobs.append([str(here / "macos.svg"), str(root / "docs/images/icon.png"), 256])
    (work / "jobs.json").write_text(json.dumps(jobs))
    (work / "render.js").write_text(renderer)
    subprocess.run(["node", str(work / "render.js"), str(work / "jobs.json")], check=True)

    # icns: 'icns' + total length, then (type, length including its 8-byte header, PNG data) entries.
    entries = [("icp4", 16), ("icp5", 32), ("icp6", 64), ("ic07", 128), ("ic08", 256), ("ic09", 512),
               ("ic10", 1024), ("ic11", 32), ("ic12", 64), ("ic13", 256), ("ic14", 512)]
    body = b"".join(kind.encode() + struct.pack(">I", 8 + len(data)) + data
                    for kind, size in entries for data in [(work / f"mac-{size}.png").read_bytes()])
    (root / "macos/AppIcon.icns").write_bytes(b"icns" + struct.pack(">I", 8 + len(body)) + body)
print("Icons written.")
