# App icon and social images

Black tile with a soft top-left light, and the ring and dot in silver metal with a soft shadow and a small highlight on the dot. It is built the way Apple describes: a background, then flat shapes, then light and depth added on top. The alternative is a silver tile with a black mark (`store/alt-silver-1024.png`).

| Folder | What |
|---|---|
| `store/` | `ios-1024.png` (square, the stores round it), `play-512.png`, `preview-rounded-1024.png` for slides, `alt-silver-1024.png`, `tinted-1024.png` (mono) |
| `android/` | `mipmap-*/ic_launcher*.png` fallbacks, adaptive icon XML (`ic_launcher.xml`, foreground in silver, background near black, monochrome) |
| `web/` | `favicon.svg` and PNGs at 16, 32, 48, 180, 192, 512 |
| `social/` | 1200 by 630 covers (black, light) and a 400 px avatar |
| `svg/` | Master SVGs, plus splash marks |

## Wiring into the Android app (not done yet)
The app's launcher is the same ring and dot, white on ink, flat. The new files add the black gradient tile and the silver metal. To switch: copy `android/mipmap-*` over `app/src/main/res/mipmap-*`, copy the foreground, background and monochrome XML files into `app/src/main/res/drawable` and `ic_launcher.xml` into `mipmap-anydpi-v26`. The adaptive foreground is flat silver (Android cannot give it depth), so the launcher on Android 8 and up looks flatter than the store PNG. Run the app on the phone and look at the launcher, the recents screen and a themed (monochrome) icon before calling it done. This was not tried on a phone.

## Safe zone
Android adaptive icons crop to a circle of 66 of 108 units. The mark is scaled to 85 percent, as in the current launcher icon, so the whole ring stays inside that circle. Check this on a device with a circular launcher mask.
