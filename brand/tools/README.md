# Brand tools

Build tools only. Not part of the app.

```
cd brand/tools && npm install        # once
node brand/tools/build.mjs           # regenerate every brand file (from the repo root)
node brand/tools/export.mjs ...      # one-off exports, see the header of export.mjs
python3 brand/tools/wordmark.py      # only if the wordmark text or font changes (needs fonttools)
```

| File | Purpose |
|---|---|
| `icons.mjs` | The icon drawings. Edit this to add or change an icon |
| `brandlib.mjs` | Logo geometry, icon rendering, color math, PNG rendering |
| `build.mjs` | Writes color, logo, app icon, icon library and `brand/index.html` |
| `black-silver.mjs`, `mockups.mjs`, `icon-concepts.mjs`, `tile-styles.mjs`, `neutral-styles.mjs` | Mockup sheets in `brand/mockups/` (the last four write to `explorations/`) |
| `export.mjs` | Custom size and color exports |
| `wordmark.py` | Converts the wordmark text to outlines |

Generated files are committed so the assets work without running anything. Running the build is safe to repeat; it rewrites the same files.

Tool licences (not shipped in the app): `@resvg/resvg-js` MPL-2.0 (renders SVG to PNG), `fonttools` MIT. Listed in `docs/DEPENDENCIES.md`.
