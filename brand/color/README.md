# Color: black and silver

Calm, premium, one family. Tokens are the source of truth in `tokens.json`; everything else here is generated from it by `brand/tools/build.mjs`.

| File | Use |
|---|---|
| `tokens.json` | All values, light and dark, plus the silver ramp and metal gradient stops |
| `tokens.css` | CSS variables, follows system dark mode, `data-theme="dark"` forces it |
| `android/colors.xml`, `colors-night.xml` | Android resources named `mc_<token>` |
| `compose-palette.kt.txt` | Reference values for `Theme.kt` (not compiled) |
| `swatches.svg`, `swatches.png` | Visual sheet |
| `contrast-report.md` | Every pair checked against WCAG AA |

## Roles
| Token | Light | Dark | Use |
|---|---|---|---|
| background | #FFFFFF | #0A0A0C | Screens |
| grouped | #F2F3F5 | #0A0A0C | Page behind grouped lists |
| surface | #FFFFFF | #17181C | Cards and rows |
| fill | #F0F1F3 | #1F2025 | Inputs, secondary buttons, composer |
| pressed | #E3E5E9 | #2A2C32 | Pressed state |
| separator | #E1E3E7 | #2A2C32 | Hairlines |
| text | #0A0A0C | #F5F6F8 | Primary text, primary buttons, the user's bubble in light mode |
| textSecondary | #5B5F69 | #A5A9B2 | Supporting text |
| textTertiary | #686D77 | #8A8F99 | Placeholders |
| accent | #17181C | #D5D8DE | Selection, focus, active tab, primary button in dark mode, user bubble in dark mode |
| onAccent | #F5F6F8 | #0A0A0C | Text on an accent fill |
| success / warning / error / info | #1F7A44 / #8A5A00 / #C4122F / #3A5BA0 | #5FD08A / #F2B33D / #FF6B7F / #8AB4F8 | Status, always with an icon and words |

Silver ramp: 50 `#F5F6F8`, 100 `#E8EAEE`, 200 `#D5D8DE`, 300 `#BFC3CB`, 400 `#A5A9B2`, 500 `#8A8F99`, 600 `#6B707A`, graphite 800 `#2A2C32`, 900 `#17181C`, black `#0A0A0C`.

## Rules
1. No accent hue. Black and silver carry everything. Status colors are the only hues in the product.
2. Metal (a short white to silver gradient) is allowed on icon glyphs, the app icon and the metal mark only. Screens, buttons, bubbles and text are flat colors.
3. Never show a status by color alone. Add an icon and words.
4. Warm up with shape and voice, not with a new color (see `strategy/brand-strategy.md`).
5. No decorative shadows on screens.

## Changing a color
Edit `TOKENS` in `tools/build.mjs`, run `node brand/tools/build.mjs`, and read `contrast-report.md`. A line that says FAIL means the change is not allowed.
