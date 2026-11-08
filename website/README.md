# The Pocketnode website

Built with Next.js (App Router) and exported as plain static files, so any web host can serve it. Animation is done with Motion. No cookies, no analytics, nothing loaded from other sites. The words of the documentation come from our own Markdown in `docs/`, and the colors, logo, icons and font come from `brand/` only (rule 7 in `AGENTS.md`).

## Look at it
```
cd website
npm install          # once
npm run dev          # then open http://localhost:3000
```
To see exactly what will be published:
```
npm run build        # writes website/out and checks every link
npm run preview      # then open http://localhost:3001
```
`npm run build` fails if a link, image or section address is missing, or if any page has an em dash or an emoji.

## What is in it
| Page | Made from |
|---|---|
| Home (animated hero, chat demo, voice call demo, pack ticker) | `src/app/page.tsx` and `src/components/` |
| Download | `src/app/download/page.tsx`, reads `site.config.json` |
| Packs | `src/app/packs/page.tsx`, the list is read from `docs/guide/03-packs.md` |
| Documentation | `docs/guide/`, `docs/developers/`, `docs/privacy.md`, `docs/threat-model.md` through `src/lib/docs.ts`. Change the Markdown, rebuild. |
| Search | `/search.json`, built from the same documents and searched in the browser (press `/`) |

## The look
Black and silver, flat, thin lines and small monospace labels. The silver metal on the mark (as in the app) is the only gradient, plus a very faint glow that follows the pointer over a card. The mark animates the way the call screen does: it swells while you talk, turns while it thinks, ripples while it speaks. Motion stops for people who ask their phone for less motion. The chat and call pictures are drawings of the app, and the page says so.

## Settings: `site.config.json`
| Key | Meaning |
|---|---|
| `domain` | The site address, for example `pocketnode.example`. Turns on the sitemap, canonical and social image links. Empty until chosen. |
| `supportEmail` | Shown in the footer when set. |
| `version`, `apk` | The download. Left empty, the Download page says plainly that no build is published. Fill them with `scripts/site-release.sh <apk> <version>`. |
| `playStoreUrl` | Shows a Google Play button when set. |
| `NEXT_PUBLIC_BASE_PATH` (environment) | Only when the site is served from a folder instead of the top of a domain. |

## Publishing a build
1. Build and sign the release app (`docs/release-keys.md`). Never publish a debug build: it trusts development keys.
2. `scripts/site-release.sh path/to/app-release.apk 1.0.0` copies the file into `website/public/downloads/`, writes its SHA-256 and size, and rebuilds.
3. Upload `website/out` to a static host (Cloudflare Pages, Netlify, Vercel, GitHub Pages, or any web server) over https. Serve the app file as `application/vnd.android.package-archive`.

## Dependencies (all permissive licences, recorded in `docs/DEPENDENCIES.md`)
next, react, react-dom, motion (MIT); typescript (Apache-2.0); type definitions (MIT). The Markdown reader is our own (`src/lib/markdown.mjs`).

## Owner decisions that touch the site
Product name (the site says Pocketnode), domain and host, price and plans (the site says paid plans are not open yet), a support address, whether Google Play is a channel, and a trademark search. Real screenshots would replace the drawn chat when there are clean ones.
