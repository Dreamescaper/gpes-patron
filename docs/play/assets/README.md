# Google Play graphics

Made 2026-10-02 from the app's own vector icon, so the store shows the same artwork as the launcher. Upload them in Play Console →
Grow → Store presence → Main store listing (and the Ukrainian custom listing).

| File | For | Spec |
|---|---|---|
| `icon-512.png` | App icon | 512×512 PNG, full square (Play rounds the corners itself). The icon artwork on the app's blue, with more margin than the launcher crop. |
| `feature-graphic-en.png`, `feature-graphic-uk.png` | Feature graphic | 1024×500 PNG, English and Ukrainian. |
| `screenshots/en/*.png`, `screenshots/uk/*.png` | Phone screenshots | 1080×2160 PNG (2:1, the tallest ratio Play accepts), 4 each: `01-drive` (verdict while tracking), `02-map`, `03-spoofing`, `04-settings`. Emulator, demo status bar (12:00, full battery). |

The screenshots show the emulator's simulated GNSS, which is physically inconsistent with its sensors, so the verdict reads "GPS is not trusted";
that is the situation the app is for, but they are not photos of a real drive. Replace them with Pixel 8 screenshots from a real drive when there is one.

## Regenerating

```bash
python3 tools/play-assets/make_icon_svg.py          # icon-store.svg / icon-full.svg from the vector drawables
CHROME="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
# icon (a page that shows icon-store.svg at 512×512)
"$CHROME" --headless=new --hide-scrollbars --window-size=512,512 --screenshot=icon-512.png file:///path/to/page-with-icon.html
# feature graphics
for l in en uk; do "$CHROME" --headless=new --hide-scrollbars --window-size=1024,500 --screenshot=feature-graphic-$l.png "file://$PWD/docs/play/assets/feature-graphic.html?lang=$l"; done
```

Screenshots: debug build on an emulator, `adb shell wm size 1080x2160`, the system UI demo mode for the status bar
(`am broadcast -a com.android.systemui.demo -e command enter|clock|battery|network|notifications`), preferences `use_questionable=true`
(see dev-guide P33), and `adb exec-out screencap -p`. Reset with `wm size reset` and the demo command `exit`.
