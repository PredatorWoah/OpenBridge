# App icon

`icon.svg` is the master artwork (1024 × 1024, full bleed). The other SVGs are derived from it:

| File | Used for |
| --- | --- |
| `macos.svg` | macOS icon: 824 pt rounded body on Apple's 1024 grid, with shadow. Rendered into `macos/AppIcon.icns`. |
| `android-background.svg` | Adaptive icon background layer (`mipmap-*/ic_launcher_background.png`). |
| `android-foreground.svg` | Adaptive icon foreground, scaled to 78 % so the bridge stays inside the 66 dp safe zone (`mipmap-*/ic_launcher_foreground.png`). |

The themed-icon layer is a hand-written vector: `android/app/src/main/res/drawable/ic_launcher_monochrome.xml`.

To regenerate the PNGs and `AppIcon.icns` after editing the SVGs, run `python3 assets/icon/build.py` (needs Node.js with Playwright and Chromium).
