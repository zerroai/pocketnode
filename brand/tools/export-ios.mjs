// Writes the brand into the iPhone app: ios/App/Resources/Assets.xcassets (icons, colors, mark, app icon) and ios/App/Resources/Fonts.
// Run from the repo root:  node brand/tools/export-ios.mjs
// Everything comes from brand/: the 77 line icons, brand/color/tokens.json, the ring-and-dot mark and the app icon art. No other icons.
import { mkdirSync, writeFileSync, readFileSync, readdirSync, copyFileSync, rmSync, existsSync } from 'node:fs';
import { join } from 'node:path';
import { execFileSync } from 'node:child_process';
import { ROOT } from './brandlib.mjs';

const REPO = join(ROOT, '..');
const RES = join(REPO, 'ios', 'App', 'Resources');
const CAT = join(RES, 'Assets.xcassets');
const write = (p, s) => { mkdirSync(join(p, '..'), { recursive: true }); writeFileSync(p, s); };
const info = { info: { version: 1, author: 'xcode' } };

rmSync(CAT, { recursive: true, force: true });
write(join(CAT, 'Contents.json'), JSON.stringify(info, null, 2));

// Icons: the line set as template images, so SwiftUI can tint them.
const lineDir = join(ROOT, 'icons', 'svg', 'line');
for (const f of readdirSync(lineDir).filter((x) => x.endsWith('.svg'))) {
  const name = f.replace(/\.svg$/, '');
  const dir = join(CAT, 'Icons', `ic-${name}.imageset`);
  mkdirSync(dir, { recursive: true });
  copyFileSync(join(lineDir, f), join(dir, f));
  writeFileSync(join(dir, f), readFileSync(join(lineDir, f), 'utf8').replaceAll('currentColor', '#000000'));
  write(join(dir, 'Contents.json'), JSON.stringify({ images: [{ filename: f, idiom: 'universal' }], properties: { 'preserves-vector-representation': true, 'template-rendering-intent': 'template' }, ...info }, null, 2));
}

// The mark (ring and dot): silver for dark screens, black for light.
for (const v of ['silver', 'black']) {
  const dir = join(CAT, 'Logo', `mark-${v}.imageset`);
  mkdirSync(dir, { recursive: true });
  copyFileSync(join(ROOT, 'logo', 'svg', `mark-${v}.svg`), join(dir, `mark-${v}.svg`));
  write(join(dir, 'Contents.json'), JSON.stringify({ images: [{ filename: `mark-${v}.svg`, idiom: 'universal' }], properties: { 'preserves-vector-representation': true }, ...info }, null, 2));
}

// Colors from the tokens, light and dark.
const tokens = JSON.parse(readFileSync(join(ROOT, 'color', 'tokens.json'), 'utf8'));
const comp = (hex) => { const n = parseInt(hex.slice(1), 16); const f = (v) => (v / 255).toFixed(3); return { red: f((n >> 16) & 255), green: f((n >> 8) & 255), blue: f(n & 255), alpha: '1.000' }; };
for (const key of Object.keys(tokens.light)) {
  const entry = (hex, dark) => ({ ...(dark ? { appearances: [{ appearance: 'luminosity', value: 'dark' }] } : {}), color: { 'color-space': 'srgb', components: comp(hex) }, idiom: 'universal' });
  write(join(CAT, 'Colors', `${key}.colorset`, 'Contents.json'), JSON.stringify({ colors: [entry(tokens.light[key], false), entry(tokens.dark[key], true)], ...info }, null, 2));
}
for (const [k, hex] of Object.entries(tokens.silver)) {
  write(join(CAT, 'Colors', `${k}.colorset`, 'Contents.json'), JSON.stringify({ colors: [{ color: { 'color-space': 'srgb', components: comp(hex) }, idiom: 'universal' }], ...info }, null, 2));
}

// App icon: the store art (opaque 1024), with the tinted version for iOS 18 and later.
const icon = join(CAT, 'AppIcon.appiconset');
mkdirSync(icon, { recursive: true });
copyFileSync(join(ROOT, 'app-icon', 'store', 'ios-1024.png'), join(icon, 'icon-1024.png'));
const images = [{ filename: 'icon-1024.png', idiom: 'universal', platform: 'ios', size: '1024x1024' }];
if (existsSync(join(ROOT, 'app-icon', 'store', 'tinted-1024.png'))) {
  copyFileSync(join(ROOT, 'app-icon', 'store', 'tinted-1024.png'), join(icon, 'icon-tinted-1024.png'));
  images.push({ appearances: [{ appearance: 'luminosity', value: 'tinted' }], filename: 'icon-tinted-1024.png', idiom: 'universal', platform: 'ios', size: '1024x1024' });
}
// The App Store refuses icons with an alpha channel: flatten onto black (the art is already full-bleed and opaque).
for (const f of readdirSync(icon).filter((x) => x.endsWith('.png'))) execFileSync('python3', ['-c', 'import sys;from PIL import Image;Image.open(sys.argv[1]).convert("RGB").save(sys.argv[1])', join(icon, f)]);
write(join(icon, 'Contents.json'), JSON.stringify({ images, ...info }, null, 2));

// Type: Inter, four weights (SIL Open Font License, docs/licenses/Inter-OFL.txt).
const fonts = join(RES, 'Fonts');
mkdirSync(fonts, { recursive: true });
// File names must match the PostScript names and the UIAppFonts list in ios/project.yml exactly (SemiBold has a capital B).
const FONT_FILES = { regular: 'Regular', medium: 'Medium', semibold: 'SemiBold', bold: 'Bold' };
for (const [w, n] of Object.entries(FONT_FILES)) copyFileSync(join(REPO, 'app', 'src', 'main', 'res', 'font', `inter_${w}.ttf`), join(fonts, `Inter-${n}.ttf`));
console.log('iOS brand assets written to ios/App/Resources');
