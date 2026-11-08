// Neutral icon background styles: black, charcoal, gray, ash, silver, cream, white.
// Run: node brand/tools/neutral-styles.mjs   (writes brand/mockups/explorations/icon-styles-neutral.*)
import { writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { ICONS } from './icons.mjs';
import { ROOT, png } from './brandlib.mjs';

const ic = Object.fromEntries(ICONS.map(([n, , , inner]) => [n, inner]));
const GLYPHS = ['chat', 'files', 'packs', 'agents', 'settings', 'shield', 'bell', 'cloud'];
const EMBER = ['#E8452C', '#FF8A5B'];

// [name, note, cardBg, textColor, tile fn(i) -> {top, bottom, glyph, border?, plate?}]
const S = [
  ['Black', 'pure black tile, white glyph', '#2A2A2E', '#fff', () => ({ top: '#1A1A1E', bottom: '#050506', glyph: '#FFFFFF', border: '#FFFFFF22' })],
  ['Charcoal', 'soft dark gray, off-white glyph', '#EDEDF0', '#211A17', () => ({ top: '#3A3A40', bottom: '#222226', glyph: '#F5F5F7' })],
  ['Graphite gradient', 'mid dark gray, white glyph', '#EDEDF0', '#211A17', () => ({ top: '#62626B', bottom: '#2E2E34', glyph: '#FFFFFF' })],
  ['Ash', 'mid gray, white glyph', '#EDEDF0', '#211A17', () => ({ top: '#A0A0A8', bottom: '#74747C', glyph: '#FFFFFF' })],
  ['Silver', 'light metallic gray, black glyph', '#FFFFFF', '#211A17', () => ({ top: '#E4E4E9', bottom: '#BDBDC4', glyph: '#18181B' })],
  ['Light gray', 'flat light gray, dark glyph', '#FFFFFF', '#211A17', () => ({ top: '#F1F1F4', bottom: '#F1F1F4', glyph: '#2A2A30', border: '#E0E0E6' })],
  ['Cream', 'warm cream tile, warm ink glyph', '#FFFFFF', '#211A17', () => ({ top: '#FFF6EA', bottom: '#FFEBD6', glyph: '#211A17', border: '#F0DFC8' })],
  ['White', 'white tile with a hairline, ink glyph', '#F1F1F4', '#211A17', () => ({ top: '#FFFFFF', bottom: '#FFFFFF', glyph: '#18181B', border: '#E0E0E6' })],
  ['Sand and taupe', 'earthy, calm, white glyph', '#FFFFFF', '#211A17', () => ({ top: '#CDBBA9', bottom: '#A3917E', glyph: '#FFFFFF' })],
  ['Black with Ember glyph', 'black tile, one warm color for the glyph', '#2A2A2E', '#fff', () => ({ top: '#1A1A1E', bottom: '#050506', glyph: '#FF8A65', border: '#FFFFFF22' })],
  ['Cream with Ember glyph', 'cream tile, Ember glyph', '#FFFFFF', '#211A17', () => ({ top: '#FFF6EA', bottom: '#FFEBD6', glyph: '#C4381B', border: '#F0DFC8' })],
  ['Graphite with one pop', 'all gray, only chat and agents in color', '#EDEDF0', '#211A17', (i) => (i === 0 || i === 3 ? { top: EMBER[1], bottom: EMBER[0], glyph: '#FFFFFF' } : { top: '#62626B', bottom: '#2E2E34', glyph: '#FFFFFF' })],
  ['Black and white mix', 'tiles alternate black and white', '#EDEDF0', '#211A17', (i) => (i % 2 ? { top: '#FFFFFF', bottom: '#FFFFFF', glyph: '#18181B', border: '#D8D8DE' } : { top: '#1A1A1E', bottom: '#050506', glyph: '#FFFFFF' })],
  ['Black glass', 'dark tile with a translucent plate', '#2A2A2E', '#fff', () => ({ top: '#2C2C32', bottom: '#0B0B0D', glyph: '#FFFFFF', plate: true, border: '#FFFFFF22' })],
];

function tile(st, i, x, y, size, uid) {
  const t = st(i);
  const rx = size * 0.225, k = (size * 0.56) / 24, gx = x + (size - 24 * k) / 2, gy = y + (size - 24 * k) / 2;
  const id = `${uid}-${i}`;
  return `<defs><linearGradient id="g${id}" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="${t.top}"/><stop offset="1" stop-color="${t.bottom}"/></linearGradient></defs>
<rect x="${x}" y="${y}" width="${size}" height="${size}" rx="${rx}" fill="url(#g${id})" ${t.border ? `stroke="${t.border}" stroke-width="1"` : ''}/>
${t.plate ? `<rect x="${x + size * 0.14}" y="${y + size * 0.14}" width="${size * 0.72}" height="${size * 0.72}" rx="${rx * 0.6}" fill="#fff" fill-opacity="0.08" stroke="#fff" stroke-opacity="0.35" stroke-width="1.2"/>` : ''}
<g transform="translate(${gx} ${gy}) scale(${k})" fill="none" stroke="${t.glyph}" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">${ic[GLYPHS[i % 8]].replace(/<(\w+) t /g, '<$1 ')}</g>`;
}

function card(n, s, x, y, w) {
  const [name, note, bg, fg, fn] = s;
  const size = 56, gap = 12;
  let out = `<g transform="translate(${x} ${y})"><rect width="${w}" height="222" rx="20" fill="${bg}" stroke="#00000014"/><text x="20" y="34" font-size="17" font-weight="700" fill="${fg}">${n + 1}. ${name}</text><text x="20" y="54" font-size="12" fill="${fg}" opacity="0.7">${note}</text>`;
  for (let i = 0; i < 8; i++) out += tile(fn, i, 20 + (i % 4) * (size + gap), 76 + Math.floor(i / 4) * (size + 10), size, `n${n}`);
  out += `<g transform="translate(${20 + 4 * (size + gap) + 6} 76)"><text x="0" y="14" font-size="11" fill="${fg}" opacity="0.7">In a list row</text>`;
  [['chat', 'Chats'], ['bell', 'Reminders'], ['shield', 'Permissions']].forEach(([g, label], r) => {
    out += tile(fn, GLYPHS.indexOf(g), 0, 24 + r * 38, 30, `r${n}-${r}`) + `<text x="42" y="${24 + r * 38 + 20}" font-size="13" fill="${fg}">${label}</text>`;
  });
  return out + '</g></g>';
}

const w = 980, ch = 238, rows = Math.ceil(S.length / 2);
let body = '';
S.forEach((s, n) => { body += card(n, s, 30 + (n % 2) * (w / 2 + 10), 70 + Math.floor(n / 2) * ch, w / 2 - 20); });
const H = 70 + rows * ch + 30;
const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${w + 60} ${H}" font-family="Helvetica, Arial, sans-serif"><rect width="100%" height="100%" fill="#FFFBF7"/><text x="30" y="44" font-size="22" font-weight="800" fill="#211A17">Neutral icon background styles: black, gray, ash, silver, cream, white</text>${body}</svg>`;
writeFileSync(join(ROOT, 'mockups/explorations/icon-styles-neutral.svg'), svg + '\n');
writeFileSync(join(ROOT, 'mockups/explorations/icon-styles-neutral.png'), png(svg, 2000));
console.log('ok');
