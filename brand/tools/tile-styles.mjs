// 20 ways to show the UI icons on colored backgrounds (the Apple Settings pattern).
// Run: node brand/tools/tile-styles.mjs   (writes brand/mockups/explorations/icon-styles-1.* and -2.*)
import { writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { ICONS } from './icons.mjs';
import { ROOT, png, mix, luminance } from './brandlib.mjs';

const ic = Object.fromEntries(ICONS.map(([n, , , inner]) => [n, inner]));
const GLYPHS = ['chat', 'files', 'packs', 'agents', 'settings', 'shield', 'bell', 'cloud'];
const lighten = (h, a) => mix('#FFFFFF', h, a);
const darken = (h, a) => mix('#000000', h, a);

// Palettes: [main, partner]. Partner is used by the two-color gradient styles.
const PAL = {
  system: [['#FF3B30', '#FF9500'], ['#FF9500', '#FFCC00'], ['#34C759', '#30D5C8'], ['#007AFF', '#5AC8FA'], ['#AF52DE', '#FF2D55'], ['#5856D6', '#007AFF']],
  candy: [['#FF4D6D', '#FF8FA3'], ['#FF9F1C', '#FFD23F'], ['#2EC4B6', '#7AE582'], ['#3A86FF', '#8338EC'], ['#8338EC', '#FF4D9D'], ['#FF6B35', '#FF4D6D']],
  sunset: [['#FF5E3A', '#FF9500'], ['#FF2D78', '#FF7A45'], ['#FFB300', '#FF6B35'], ['#D946EF', '#FF4F8B'], ['#7C3AED', '#E040FB'], ['#F43F5E', '#FB923C']],
  ocean: [['#00B4D8', '#48CAE4'], ['#0077B6', '#00B4D8'], ['#2EC4B6', '#00D9A5'], ['#3A7BFF', '#00C2FF'], ['#5E60CE', '#4EA8DE'], ['#06D6A0', '#118AB2']],
  jewel: [['#E0115F', '#FF5A8F'], ['#0F9D58', '#5BD58A'], ['#1A73E8', '#6BA7FF'], ['#7B2FF7', '#B57BFF'], ['#F29900', '#FFC84A'], ['#00897B', '#4DD0C4']],
  neon: [['#FF2E93', '#FF8A00'], ['#00F5D4', '#00BBF9'], ['#9B5DE5', '#F15BB5'], ['#FEE440', '#FF9F1C'], ['#00BBF9', '#9B5DE5'], ['#7CFF6B', '#00F5D4']],
  warm: [['#E8452C', '#FF8A5B'], ['#F29900', '#FFC84A'], ['#D6336C', '#FF7AA2'], ['#2F9E6B', '#7AD9A6'], ['#2F7BE0', '#7FB2FF'], ['#8A5CD6', '#C0A0F5']],
  pastelpop: [['#FF8FAB', '#FFB3C6'], ['#FFB86B', '#FFD6A5'], ['#7AD9B0', '#B5EAD7'], ['#7FB7FF', '#B6D4FF'], ['#B79CFF', '#D8C8FF'], ['#FFD966', '#FFE9A8']],
};

const squircle = (s) => s * 0.225;
const SHAPES = { squircle: (s) => s * 0.225, round: (s) => s / 2, soft: (s) => s * 0.32, tight: (s) => s * 0.14 };

function tile(style, hue, i, x, y, size, uid) {
  const [a, b] = hue;
  const rx = SHAPES[style.shape](size);
  const glyphName = GLYPHS[i % GLYPHS.length];
  const k = (size * 0.56) / 24, gx = x + (size - 24 * k) / 2, gy = y + (size - 24 * k) / 2;
  const inner = ic[glyphName];
  const dark = luminance(a) > 0.42; // bright hues get a dark glyph so they stay readable
  const glyph = (color, tint, extra = '') => `<g transform="translate(${gx} ${gy}) scale(${k})" fill="none" stroke="${color}" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" ${extra}>${inner.replace(/<(\w+) t /g, tint ? `<$1 fill="${color}" fill-opacity="0.2" ` : '<$1 ')}</g>`;
  const id = `${uid}-${i}`;
  let body = '';
  switch (style.kind) {
    case 'grad': // Apple Settings pattern: light top, darker bottom, white glyph
      body = `<defs><linearGradient id="g${id}" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="${lighten(a, 0.22)}"/><stop offset="1" stop-color="${darken(a, 0.1)}"/></linearGradient></defs><rect x="${x}" y="${y}" width="${size}" height="${size}" rx="${rx}" fill="url(#g${id})"/>${glyph(dark ? '#2A1A00' : '#fff', false)}`; break;
    case 'flat':
      body = `<rect x="${x}" y="${y}" width="${size}" height="${size}" rx="${rx}" fill="${a}"/>${glyph(dark ? '#2A1A00' : '#fff', false)}`; break;
    case 'duo': // two hue diagonal gradient
      body = `<defs><linearGradient id="g${id}" x1="0" y1="1" x2="1" y2="0"><stop offset="0" stop-color="${a}"/><stop offset="1" stop-color="${b}"/></linearGradient></defs><rect x="${x}" y="${y}" width="${size}" height="${size}" rx="${rx}" fill="url(#g${id})"/>${glyph(dark ? '#2A1A00' : '#fff', false)}`; break;
    case 'glossy':
      body = `<defs><linearGradient id="g${id}" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="${lighten(a, 0.3)}"/><stop offset="0.55" stop-color="${a}"/><stop offset="1" stop-color="${darken(a, 0.18)}"/></linearGradient><filter id="f${id}" x="-30%" y="-30%" width="160%" height="170%"><feGaussianBlur in="SourceAlpha" stdDeviation="0.8"/><feOffset dy="1.2" result="b"/><feFlood flood-color="#000" flood-opacity="0.28"/><feComposite in2="b" operator="in"/><feMerge><feMergeNode/><feMergeNode in="SourceGraphic"/></feMerge></filter><clipPath id="c${id}"><rect x="${x}" y="${y}" width="${size}" height="${size}" rx="${rx}"/></clipPath></defs><rect x="${x}" y="${y}" width="${size}" height="${size}" rx="${rx}" fill="url(#g${id})"/><g clip-path="url(#c${id})"><ellipse cx="${x + size / 2}" cy="${y - size * 0.12}" rx="${size * 0.7}" ry="${size * 0.5}" fill="#fff" opacity="0.28"/></g>${glyph(dark ? '#2A1A00' : '#fff', false, `filter="url(#f${id})"`)}`; break;
    case 'pastel':
      body = `<rect x="${x}" y="${y}" width="${size}" height="${size}" rx="${rx}" fill="${lighten(a, 0.84)}"/>${glyph(darken(a, 0.12), true)}`; break;
    case 'dark':
      body = `<defs><linearGradient id="g${id}" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#2A2A33"/><stop offset="1" stop-color="#101015"/></linearGradient><filter id="f${id}" x="-50%" y="-50%" width="200%" height="200%"><feGaussianBlur stdDeviation="2.2"/></filter></defs><rect x="${x}" y="${y}" width="${size}" height="${size}" rx="${rx}" fill="url(#g${id})"/><g opacity="0.8" filter="url(#f${id})">${glyph(a, false)}</g>${glyph(lighten(a, 0.25), false)}`; break;
    case 'sticker':
      body = `<rect x="${x + 2.5}" y="${y + 3}" width="${size}" height="${size}" rx="${rx}" fill="${darken(a, 0.35)}"/><rect x="${x}" y="${y}" width="${size}" height="${size}" rx="${rx}" fill="#fff" stroke="${darken(a, 0.35)}" stroke-width="2"/>${glyph(a, true)}`; break;
    case 'twotone':
      body = `<rect x="${x}" y="${y}" width="${size}" height="${size}" rx="${rx}" fill="${a}"/><g transform="translate(1.6 1.8)">${glyph(darken(a, 0.35), false)}</g>${glyph('#FFF4E6', false)}`; break;
    case 'glass':
      body = `<defs><linearGradient id="g${id}" x1="0" y1="1" x2="1" y2="0"><stop offset="0" stop-color="${darken(a, 0.12)}"/><stop offset="1" stop-color="${b}"/></linearGradient></defs><rect x="${x}" y="${y}" width="${size}" height="${size}" rx="${rx}" fill="url(#g${id})"/><rect x="${x + size * 0.14}" y="${y + size * 0.14}" width="${size * 0.72}" height="${size * 0.72}" rx="${rx * 0.6}" fill="#fff" fill-opacity="0.2" stroke="#fff" stroke-opacity="0.6" stroke-width="1.4"/>${glyph('#fff', false)}`; break;
    case 'outline':
      body = `<rect x="${x + 1}" y="${y + 1}" width="${size - 2}" height="${size - 2}" rx="${rx}" fill="none" stroke="${a}" stroke-width="2.4"/>${glyph(a, false)}`; break;
    case 'gradglyph':
      body = `<defs><linearGradient id="g${id}" x1="0" y1="1" x2="1" y2="0"><stop offset="0" stop-color="${a}"/><stop offset="1" stop-color="${b}"/></linearGradient></defs><rect x="${x}" y="${y}" width="${size}" height="${size}" rx="${rx}" fill="${lighten(a, 0.9)}"/>${glyph(`url(#g${id})`, false)}`; break;
  }
  return body;
}

const STYLES = [
  ['Apple Settings pattern', 'grad', 'squircle', 'system', 'Apple system colors, light top to dark bottom, white glyph'],
  ['Candy gradient', 'duo', 'squircle', 'candy', 'two-hue diagonal gradient'],
  ['Sunset duo', 'duo', 'squircle', 'sunset', 'hot pinks and oranges'],
  ['Ocean duo', 'duo', 'squircle', 'ocean', 'cool, fresh, clean'],
  ['Jewel gloss', 'glossy', 'squircle', 'jewel', 'deep rich colors with a light sheen'],
  ['Neon on dark', 'dark', 'squircle', 'neon', 'glowing glyph on a near black tile'],
  ['Warm flat', 'flat', 'squircle', 'warm', 'our warm family, no gradient'],
  ['Pastel pop', 'pastel', 'squircle', 'pastelpop', 'soft tiles with a deep glyph'],
  ['Sticker', 'sticker', 'soft', 'candy', 'white tile, colored outline glyph, offset shadow'],
  ['Two tone', 'twotone', 'squircle', 'sunset', 'cream glyph with a dark echo'],
  ['Glass', 'glass', 'squircle', 'ocean', 'translucent plate inside a gradient'],
  ['Round badges', 'grad', 'round', 'candy', 'circles instead of squircles'],
  ['Soft round gloss', 'glossy', 'soft', 'system', 'extra round corners with a sheen'],
  ['Gradient glyph', 'gradglyph', 'squircle', 'sunset', 'colorful gradient glyph on a pale tile'],
  ['Neon duo', 'duo', 'squircle', 'neon', 'electric gradients, dark glyph on bright'],
  ['Outline tiles', 'outline', 'soft', 'jewel', 'no fill, colored border and glyph'],
  ['Tight squares', 'flat', 'tight', 'system', 'sharper corners, flat bold color'],
  ['Warm gradient', 'grad', 'squircle', 'warm', 'Ember family with light top, dark bottom'],
  ['Jewel duo', 'duo', 'soft', 'jewel', 'rich two-hue gradient, extra round'],
  ['Candy gloss round', 'glossy', 'round', 'candy', 'glossy circles, playful'],
];

function card(i, st, x, y, w) {
  const [name, kind, shape, pal, note] = st;
  const size = 56, gap = 12;
  let s = `<g transform="translate(${x} ${y})"><rect width="${w}" height="222" rx="20" fill="#fff" stroke="#E8DED3"/><text x="20" y="34" font-size="17" font-weight="700" fill="#211A17">${i + 1}. ${name}</text><text x="20" y="54" font-size="12" fill="#62554D">${note}</text>`;
  for (let n = 0; n < 8; n++) {
    s += tile({ kind, shape }, PAL[pal][n % 6], n, 20 + (n % 4) * (size + gap) + (n % 4) * 0, 76 + Math.floor(n / 4) * (size + 10), size, `s${i}`);
  }
  // a list row preview for the first tile
  const hue = PAL[pal][0];
  s += `<g transform="translate(${20 + 4 * (size + gap) + 6} 76)"><text x="0" y="14" font-size="11" fill="#62554D">In a list row</text>`;
  [['chat', 'Chats'], ['bell', 'Reminders'], ['shield', 'Permissions']].forEach(([g, label], r) => {
    const idx = GLYPHS.indexOf(g);
    s += tile({ kind, shape }, PAL[pal][(idx + 0) % 6], idx, 0, 24 + r * 38, 30, `r${i}-${r}`);
    s += `<text x="42" y="${24 + r * 38 + 20}" font-size="13" fill="#211A17">${label}</text>`;
  });
  return s + '</g></g>';
}

function sheet(part) {
  const list = STYLES.slice(part * 10, part * 10 + 10);
  const w = 980, ch = 238;
  let body = '';
  list.forEach((st, i) => { const col = i % 2, row = Math.floor(i / 2); body += card(part * 10 + i, st, 30 + col * (w / 2 + 10), 70 + row * ch, w / 2 - 20 + 0); });
  const H = 70 + 5 * ch + 30;
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${w + 60} ${H}" font-family="Helvetica, Arial, sans-serif"><rect width="100%" height="100%" fill="#FFFBF7"/><text x="30" y="44" font-size="22" font-weight="800" fill="#211A17">Icon background styles ${part * 10 + 1} to ${part * 10 + 10}: the same 8 icons, different color treatments</text>${body}</svg>`;
  writeFileSync(join(ROOT, `mockups/explorations/icon-styles-${part + 1}.svg`), svg + '\n');
  writeFileSync(join(ROOT, `mockups/explorations/icon-styles-${part + 1}.png`), png(svg, 2000));
}
sheet(0); sheet(1);
console.log('ok');
