# Icon library

77 icons drawn from scratch for Pocketnode, in two styles. Source of truth is `brand/tools/icons.mjs`. Everything in this folder is generated from it.

## Open the catalog
Open `brand/index.html` in a browser (double click it). It works offline. Search, switch Tile, Line and Tint, change size, and click an icon to copy its SVG.

## Styles
- **Tile (house style, chosen by the owner):** the icon in silver metal on a black rounded tile with a thin silver edge. For list rows, Settings and Profile, approval cards, pack and agent lists. Fixed colors, not tintable.
- **Line:** outline only, one color. For the tab bar, composer, top bar and buttons.
- **Tint:** the main body is filled at 16 percent with the same color. For the selected tab and empty states.

## Files
| Path | What |
|---|---|
| `svg/tile/` | One SVG per icon on its black tile, 96 by 96 |
| `svg/line/`, `svg/tint/` | One SVG per icon, `currentColor`, 24 by 24 viewBox. Use these on the web and in design tools |
| `sprite.svg` | All icons as `<symbol id="mc-chat">`, tint as `mc-chat-tint`. Use with `<use href="sprite.svg#mc-chat">` |
| `png/tile/` | 192 px PNGs of the tiles |
| `png/<line|tint>/<black|white|silver>/` | 96 px PNGs |
| `android/line/`, `android/tint/` | VectorDrawables `ic_mc_<name>.xml`, black, tint them in code. There is no Android vector for the tile (its gradients need a gradient vector); draw the tile in Compose with the colors in `brand/color/tokens.json` under `metal`, or use the PNGs |
| `icons.json` | Names, groups and search tags |
| `icon-names.kt.txt` | An enum of names for Kotlin |

## Custom size or color, any time
```
node brand/tools/export.mjs icon chat --style tile --size 256 --out chat.png
node brand/tools/export.mjs icons --style line --color "#FFFFFF" --size 64 --out ~/Desktop/white-64
node brand/tools/export.mjs icon cloud --style line --size 24 --out cloud.svg
```

## Drawing rules
1. 24 unit canvas. Live area 20 by 20 (2 units of padding). Details on half units.
2. Stroke 2 units, round caps, round joins. Never mix weights in one icon.
3. Outer corner radius 3 on boxes (2.5 on small ones). No sharp outer corners.
4. Details are dots made with a zero length round stroke (`M9.5 13.5h.01`), not tiny circles.
5. Keylines: a circle may reach 18 units across, a square 16, wide or tall shapes 18 by 14. A circle needs to be slightly larger than a square to look the same size.
6. One idea per icon. If it needs a label to be understood, the label stays next to it in the app.
7. No brand logos of other companies. Channel packs use the generic `channel` glyph on the tile, and their name.
8. In the app the glyph is 24 dp inside a 48 dp target. The tile is 40 to 44 dp in rows, 32 dp in cards. In tabs it is 24 dp plain with a label.
9. Tile glyph: 56 percent of the tile, centered. Corner radius 22.5 percent of the tile. Silver gradient from white at the top to #8F949E at the bottom.

## Groups
Navigation 14, Actions 21, Status and safety 18, Product 14, Things 10. `icons.json` is exact.

## Adding an icon
See "Process we follow for a new icon" in `brand/research/02-icon-systems-research.md`. In short: add one line to `icons.mjs`, run the build, look at the sheet at 24, 32 and 48 px.

## Status
Drawn and viewed as images on a Mac screen only. Not yet checked on the phone at 24 dp on the S9's 720 by 1480 screen. Do that before replacing `Icons.kt`.
