// Export any brand asset at any size or color, on demand.
//   node brand/tools/export.mjs icon chat --style tile --size 128 --out chat.png      (style: line, tint or tile)
//   node brand/tools/export.mjs icons --style line --color "#FFFFFF" --size 64 --out ~/Desktop/mc-icons
//   node brand/tools/export.mjs logo horizontal --mark "#0A0A0C" --word "#0A0A0C" --width 1600 --bg "#FFFFFF" --out logo.png
//   node brand/tools/export.mjs appicon --size 1024 --kind black --out icon.png       (kind: black or silver, with depth)
//   node brand/tools/export.mjs appicon --size 1024 --bg "#17181C" --fg "#D5D8DE" --out flat.png
// Use .svg as the output extension to get SVG instead of PNG.
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, extname, join } from 'node:path';
import { ICONS } from './icons.mjs';
import { png, iconSvg, logos, appIcon, appIconDepth, tileSvg, COLORS } from './brandlib.mjs';

const [kind, name, ...rest] = process.argv.slice(2);
const argv = kind === 'icons' || kind === 'appicon' ? [name, ...rest].filter(Boolean) : rest;
const opt = {};
for (let i = 0; i < argv.length; i += 2) opt[argv[i].replace(/^--/, '')] = argv[i + 1];
const size = +(opt.size || opt.width || 96);
const outPath = opt.out;
if (!kind || !outPath) { console.error('usage: export.mjs icon|icons|logo|appicon ... --out path (see the header of this file)'); process.exit(1); }

function save(path, svg, w, bg) {
  mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path, extname(path) === '.svg' ? svg : png(svg, w, bg));
  console.log('wrote', path);
}
const iconOpts = { style: opt.style || 'line', color: opt.color || COLORS.black, size };
if (kind === 'icon') {
  const row = ICONS.find((i) => i[0] === name);
  if (!row) { console.error('no icon named ' + name); process.exit(1); }
  save(outPath, opt.style === 'tile' ? tileSvg(row[3], size) : iconSvg(row[3], iconOpts), size, opt.bg);
} else if (kind === 'icons') {
  for (const [n, , , inner] of ICONS) save(join(outPath, `${n}.${opt.format || 'png'}`), opt.style === 'tile' ? tileSvg(inner, size) : iconSvg(inner, iconOpts), size, opt.bg);
} else if (kind === 'logo') {
  const L = logos({ mark: opt.mark || COLORS.black, word: opt.word || COLORS.black });
  if (!L[name]) { console.error('logo kinds: ' + Object.keys(L).join(', ')); process.exit(1); }
  save(outPath, L[name], size, opt.bg);
} else if (kind === 'appicon') {
  save(outPath, appIcon({ size, bg: opt.bg || COLORS.black, fg: opt.fg || COLORS.silver, radius: +(opt.radius || 0) }), size);
} else { console.error('unknown kind ' + kind); process.exit(1); }
