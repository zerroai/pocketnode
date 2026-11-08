# Logo

The mark is the existing Pocketnode logo: a ring open at the upper right with a solid dot in the gap. The wordmark is "Pocketnode" in Nunito ExtraBold, converted to outlines so it needs no font.

## Files
| Folder | What |
|---|---|
| `svg/` | Master vector files: `horizontal`, `stacked`, `mark`, `wordmark`, each in four tones, plus `mark-metal` |
| `png/` | Same files as transparent PNG. `-with-background` files carry the tone's own background |
| `android/ic_mc_mark.xml`, `ic_mc_mark_silver.xml` | The mark as VectorDrawables (black, silver) |
| `src/wordmark.json` | Outline data for the wordmark (made by `tools/wordmark.py`) |

Tones: `black` (for white or light backgrounds), `on-dark` (silver mark, near white word, for black backgrounds), `white` (one color, on black photos), `silver` (one mid silver color, for quiet uses). `mark-metal` is the mark with the silver metal gradient, for black backgrounds only.

## Rules
- **Clear space:** keep one dot diameter (about a quarter of the mark width) free on every side. Nothing enters it.
- **Minimum size:** the mark alone 16 px. Horizontal lockup 96 px wide. Stacked 72 px wide. Below that use the mark only.
- **Backgrounds:** `black` on white or light silver. `on-dark` or `mark-metal` on #0A0A0C or darker. `white` on a dark photo. If none fits, use `black`.
- **Use the mark alone** for the app icon, avatar, favicon and splash. Use the lockup where the name must be read.
- **Do not:** stretch or rotate, recolor outside the tones, add outlines, shadows or glow, change the wordmark's spacing, set the name in another font, change the gap or the dot position, or put the mark inside a circle or other badge (the app icon tile is the only container).

## Making a custom version
```
node brand/tools/export.mjs logo horizontal --mark "#0A0A0C" --word "#0A0A0C" --width 1600 --bg "#FFFFFF" --out ~/Desktop/logo.png
node brand/tools/export.mjs logo mark --mark "#D5D8DE" --width 512 --out mark-silver.svg
```
Kinds: `horizontal`, `stacked`, `mark`, `wordmark`. Use an `.svg` extension for vector output.
