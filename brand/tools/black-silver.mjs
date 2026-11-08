// Black and silver direction: icon tiles, chat screens and app icon.
// Run: node brand/tools/black-silver.mjs   (writes brand/mockups/black-silver-*.png)
import { writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { ICONS } from './icons.mjs';
import { ROOT, png } from './brandlib.mjs';

const ic = Object.fromEntries(ICONS.map(([n, , , inner]) => [n, inner]));
export const SILVER = { s50: '#F5F6F8', s100: '#E8EAEE', s200: '#D5D8DE', s300: '#BFC3CB', s400: '#A5A9B2', s500: '#8A8F99', s600: '#6B707A', g800: '#2A2C32', g900: '#17181C', black: '#0A0A0C' };
const SG = `<linearGradient id="silver" gradientUnits="userSpaceOnUse" x1="0" y1="0" x2="0" y2="24"><stop offset="0" stop-color="#FFFFFF"/><stop offset="0.45" stop-color="#C9CDD4"/><stop offset="1" stop-color="#8F949E"/></linearGradient>`;
const T = (x, y, s, size, fill, weight = 400, anchor = 'start') => `<text x="${x}" y="${y}" font-size="${size}" font-weight="${weight}" fill="${fill}" text-anchor="${anchor}">${s}</text>`;
const glyph = (name, x, y, size, stroke, tint = false) => `<g transform="translate(${x} ${y}) scale(${size / 24})" fill="none" stroke="${stroke}" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">${ic[name].replace(/<(\w+) t /g, tint ? `<$1 fill="${stroke}" fill-opacity="0.16" ` : '<$1 ')}</g>`;

// ---------------------------------------------------------------- icon tiles
const NAMES = ['chat', 'files', 'packs', 'agents', 'settings', 'shield', 'bell', 'cloud', 'key', 'clock', 'mic', 'send', 'phone', 'cluster'];
const LABELS = { a: ['A. Black tile, silver glyph', 'metal gradient glyph, thin silver edge'], b: ['B. Black tile, white glyph', 'plain, with a silver ring around the tile'], c: ['C. Silver tile, black glyph', 'light metal plate, deep black glyph'], d: ['D. Black tile with silver plate', 'a small brushed plate inside the black'] };
function tile(kind, name, x, y, size, uid) {
  const rx = size * 0.225, k = (size * 0.56) / 24, gx = x + (size - 24 * k) / 2, gy = y + (size - 24 * k) / 2, id = uid;
  let base = '', gl = '';
  if (kind === 'a') {
    base = `<defs><linearGradient id="bk${id}" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#2A2B31"/><stop offset="1" stop-color="#070708"/></linearGradient><linearGradient id="ed${id}" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#8E939D"/><stop offset="1" stop-color="#2E3036"/></linearGradient></defs><rect x="${x}" y="${y}" width="${size}" height="${size}" rx="${rx}" fill="url(#bk${id})" stroke="url(#ed${id})" stroke-width="1.2"/><rect x="${x + 1}" y="${y + 1}" width="${size - 2}" height="${size * 0.5}" rx="${rx}" fill="#fff" opacity="0.05"/>`;
    gl = `<defs>${SG}</defs>` + glyph(name, gx, gy, 24 * k, 'url(#silver)');
  } else if (kind === 'b') {
    base = `<defs><linearGradient id="bk${id}" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#1B1C20"/><stop offset="1" stop-color="#050506"/></linearGradient></defs><rect x="${x}" y="${y}" width="${size}" height="${size}" rx="${rx}" fill="url(#bk${id})"/><rect x="${x + 2}" y="${y + 2}" width="${size - 4}" height="${size - 4}" rx="${rx - 1.5}" fill="none" stroke="#C9CDD4" stroke-opacity="0.55" stroke-width="1.3"/>`;
    gl = glyph(name, gx, gy, 24 * k, '#FFFFFF');
  } else if (kind === 'c') {
    base = `<defs><linearGradient id="sv${id}" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#F7F8FA"/><stop offset="0.5" stop-color="#D3D6DC"/><stop offset="1" stop-color="#A3A8B1"/></linearGradient></defs><rect x="${x}" y="${y}" width="${size}" height="${size}" rx="${rx}" fill="url(#sv${id})" stroke="#FFFFFF" stroke-opacity="0.8" stroke-width="1"/>`;
    gl = glyph(name, gx, gy, 24 * k, '#0A0A0C');
  } else {
    base = `<defs><linearGradient id="bk${id}" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#24252A"/><stop offset="1" stop-color="#060607"/></linearGradient><linearGradient id="pl${id}" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#F2F3F6"/><stop offset="0.5" stop-color="#C3C7CE"/><stop offset="1" stop-color="#8F949E"/></linearGradient></defs><rect x="${x}" y="${y}" width="${size}" height="${size}" rx="${rx}" fill="url(#bk${id})"/><rect x="${x + size * 0.15}" y="${y + size * 0.15}" width="${size * 0.7}" height="${size * 0.7}" rx="${rx * 0.65}" fill="url(#pl${id})"/>`;
    const kk = (size * 0.4) / 24;
    gl = glyph(name, x + (size - 24 * kk) / 2, y + (size - 24 * kk) / 2, 24 * kk, '#0A0A0C');
  }
  return base + gl;
}
function iconSheet() {
  let body = '';
  Object.entries(LABELS).forEach(([kind, [title, note]], r) => {
    const y = 70 + r * 190;
    body += `<g transform="translate(0 ${y})"><rect x="30" y="0" width="1040" height="170" rx="20" fill="${kind === 'c' ? '#2A2C32' : '#F0F1F3'}"/>`;
    body += T(50, 34, title, 17, kind === 'c' ? '#fff' : '#0A0A0C', 700) + T(50, 54, note, 12, kind === 'c' ? '#BFC3CB' : '#5B5F69');
    NAMES.forEach((n, i) => { body += tile(kind, n, 50 + i * 72, 82, 56, `i${kind}${i}`); });
    body += '</g>';
  });
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1100 ${70 + 4 * 190 + 20}" font-family="Helvetica, Arial, sans-serif"><rect width="100%" height="100%" fill="#FFFFFF"/>${T(30, 44, 'Black and silver: four ways to build the icon tiles', 22, '#0A0A0C', 800)}${body}</svg>`;
  save('black-silver-icons', svg, 2200);
}

// ---------------------------------------------------------------- chat screens
const W = 360, H = 740;
const MODES = {
  dark: { bg: '#0A0A0C', surface: '#17181C', fill: '#1F2025', sep: '#2A2C32', text: '#F5F6F8', t2: '#A5A9B2', bubble: 'url(#bub)', onBubble: '#0A0A0C', send: 'url(#bub)', onSend: '#0A0A0C', primary: 'url(#bub)', onPrimary: '#0A0A0C', accent: '#D5D8DE' },
  light: { bg: '#FFFFFF', surface: '#FFFFFF', fill: '#F0F1F3', sep: '#E1E3E7', text: '#0A0A0C', t2: '#5B5F69', bubble: '#0A0A0C', onBubble: '#FFFFFF', send: '#0A0A0C', onSend: '#D5D8DE', primary: '#0A0A0C', onPrimary: '#FFFFFF', accent: '#2A2C32' },
};
const defs = `<defs><linearGradient id="bub" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#F3F4F7"/><stop offset="1" stop-color="#B9BDC5"/></linearGradient></defs>`;
let uidn = 0;
const tileSm = (x, y, size, name) => tile('a', name, x, y, size, 'c' + uidn++);
const frame = (p, body) => `${defs}<rect width="${W}" height="${H}" rx="28" fill="${p.bg}"/>${T(24, 22, '9:41', 12, p.text, 700)}${glyph('wifi', 290, 8, 16, p.text)}${glyph('battery', 312, 8, 18, p.text)}${body}`;
function conversation(p) {
  let b = glyph('back', 12, 36, 24, p.text) + T(180, 54, 'Battery and weather', 16, p.text, 700, 'middle');
  b += `<circle cx="326" cy="48" r="16" fill="${p.fill}"/>` + glyph('profile', 316, 38, 20, p.text) + `<line x1="0" y1="72" x2="${W}" y2="72" stroke="${p.sep}"/>`;
  b += `<rect x="96" y="90" width="248" height="38" rx="19" fill="${p.bubble}"/>` + T(112, 114, 'How much battery do I have left?', 12.5, p.onBubble, 600);
  b += `<rect x="16" y="144" width="300" height="56" rx="18" fill="${p.surface}" stroke="${p.sep}"/>` + tileSm(28, 156, 32, 'phone') + T(72, 172, 'Phone', 13, p.text, 700) + T(72, 189, 'Battery 59%, on wifi', 12, p.t2) + glyph('check', 288, 164, 20, p.accent);
  b += T(18, 226, 'You have 59% left. I looked it up just now.', 13, p.text);
  b += `<rect x="40" y="248" width="304" height="58" rx="22" fill="${p.bubble}"/>` + T(56, 270, 'Every morning at 7:30 tell me the weather in', 12.5, p.onBubble, 600) + T(56, 289, 'Accra, but only if it will rain', 12.5, p.onBubble, 600);
  b += `<rect x="16" y="322" width="328" height="170" rx="20" fill="${p.surface}" stroke="${p.sep}"/>` + tileSm(30, 334, 36, 'shield') + T(78, 358, 'Add this agent?', 15, p.text, 700);
  b += T(30, 392, 'Weather alert', 13, p.text, 700) + T(30, 411, 'Every day at 7:30. Checks the Accra forecast', 12, p.t2) + T(30, 428, 'and tells you only if it will rain.', 12, p.t2);
  b += `<rect x="30" y="444" width="140" height="36" rx="18" fill="${p.fill}"/>` + T(100, 467, 'Not now', 13, p.text, 700, 'middle') + `<rect x="180" y="444" width="150" height="36" rx="18" fill="${p.primary}"/>` + T(255, 467, 'Add agent', 13, p.onPrimary, 700, 'middle');
  b += `<rect x="12" y="664" width="336" height="56" rx="28" fill="${p.fill}"/>` + glyph('plus', 24, 680, 24, p.text) + T(60, 697, 'Message', 14, p.t2) + glyph('mic', 270, 680, 24, p.text) + `<circle cx="324" cy="692" r="18" fill="${p.send}"/>` + glyph('send', 314, 682, 20, p.onSend);
  return frame(p, b);
}
function list(p) {
  let b = T(20, 84, 'Chats', 30, p.text, 800) + `<circle cx="326" cy="70" r="16" fill="${p.fill}"/>` + glyph('profile', 316, 60, 20, p.text);
  b += `<rect x="16" y="104" width="328" height="40" rx="20" fill="${p.fill}"/>` + glyph('search', 28, 114, 20, p.t2) + T(58, 129, 'Search', 14, p.t2);
  [['Battery and weather', 'You have 59% left. I looked it up', 'chat'], ['Weather alert', 'Agent ran at 7:30. No rain in Accra', 'agents'], ['Shopping list', 'Added bread and eggs to your notes', 'note'], ['Trip to Lagos', 'Packed list saved to Files', 'files']].forEach(([a, c, g], i) => {
    const y = 164 + i * 70;
    b += tileSm(20, y, 44, g) + T(78, y + 20, a, 15, p.text, 700) + T(78, y + 40, c, 12.5, p.t2);
    if (i < 3) b += `<line x1="78" y1="${y + 58}" x2="344" y2="${y + 58}" stroke="${p.sep}"/>`;
  });
  b += `<rect x="20" y="654" width="320" height="64" rx="32" fill="${p.surface}" stroke="${p.sep}"/>`;
  [['chat', 'Chats'], ['files', 'Files'], ['packs', 'Packs'], ['agents', 'Agents']].forEach(([g, l], i) => {
    const cx = 54 + i * 84, on = i === 0;
    if (on) b += `<rect x="${cx - 36}" y="662" width="72" height="48" rx="24" fill="${p.fill}"/>`;
    b += glyph(g, cx - 12, 667, 24, on ? p.text : p.t2, on) + T(cx, 703, l, 11, on ? p.text : p.t2, on ? 700 : 500, 'middle');
  });
  return frame(p, b);
}
const phone = (svg, x, y) => `<g transform="translate(${x} ${y})"><rect x="-6" y="-6" width="${W + 12}" height="${H + 12}" rx="34" fill="#00000022"/>${svg}</g>`;
function chatSheet() {
  let body = '';
  [['dark', conversation], ['dark', list], ['light', conversation], ['light', list]].forEach(([m, fn], i) => { body += phone(fn(MODES[m]), 40 + i * (W + 40), 70); });
  body += T(40 + (W + 40) / 2 + W / 2 - 180, 40, 'Dark mode', 20, '#fff', 700, 'middle') + T(40 + 2.5 * (W + 40) + W / 2 - 20, 40, 'Light mode', 20, '#fff', 700, 'middle');
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${4 * (W + 40) + 40} ${H + 110}" font-family="Helvetica, Arial, sans-serif"><rect width="100%" height="100%" fill="#6E727B"/>${body}</svg>`;
  save('black-silver-chat', svg, 2200);
}

// ---------------------------------------------------------------- app icon
const ARC = 'M82.98,46.24A30,30 0 1 1 61.76,25.02', DOT = [75.21, 32.79];
function appIcon(kind, size, uid) {
  const k = size / 108, rx = size * 0.225;
  const dark = kind !== 'silver';
  const bg = dark ? ['#2C2D33', '#050506'] : ['#FAFAFC', '#B7BBC3'];
  const ring = dark ? ['#FFFFFF', '#9DA2AC'] : ['#2A2B31', '#050506'];
  return `<g><defs><linearGradient id="b${uid}" x1="0.1" y1="0" x2="0.9" y2="1"><stop offset="0" stop-color="${bg[0]}"/><stop offset="1" stop-color="${bg[1]}"/></linearGradient><radialGradient id="r${uid}" cx="0.25" cy="0.1" r="0.85"><stop offset="0" stop-color="#fff" stop-opacity="${dark ? 0.16 : 0.5}"/><stop offset="1" stop-color="#fff" stop-opacity="0"/></radialGradient><linearGradient id="m${uid}" x1="0" y1="0" x2="0.3" y2="1"><stop offset="0" stop-color="${ring[0]}"/><stop offset="1" stop-color="${ring[1]}"/></linearGradient><filter id="s${uid}" x="-30%" y="-30%" width="160%" height="170%"><feGaussianBlur in="SourceAlpha" stdDeviation="2.2"/><feOffset dx="1.2" dy="2.8" result="b"/><feFlood flood-color="#000" flood-opacity="${dark ? 0.7 : 0.3}"/><feComposite in2="b" operator="in"/><feMerge><feMergeNode/><feMergeNode in="SourceGraphic"/></feMerge></filter><clipPath id="c${uid}"><rect width="${size}" height="${size}" rx="${rx}"/></clipPath></defs>
<g clip-path="url(#c${uid})"><rect width="${size}" height="${size}" fill="url(#b${uid})"/><rect width="${size}" height="${size}" fill="url(#r${uid})"/>
<g transform="scale(${k}) translate(54 54) scale(0.95) translate(-54 -54)"><path d="${ARC}" fill="none" stroke="url(#m${uid})" stroke-width="9" stroke-linecap="round" filter="url(#s${uid})"/><circle cx="${DOT[0]}" cy="${DOT[1]}" r="8.4" fill="url(#m${uid})" filter="url(#s${uid})"/><circle cx="${DOT[0] - 2.6}" cy="${DOT[1] - 3}" r="2.2" fill="${dark ? '#fff' : '#fff'}" opacity="${dark ? 0.7 : 0.35}"/></g></g>
<rect width="${size}" height="${size}" rx="${rx}" fill="none" stroke="${dark ? '#fff' : '#000'}" stroke-opacity="${dark ? 0.14 : 0.1}"/></g>`;
}
function iconAppSheet() {
  const kinds = [['black', 'Black with silver mark'], ['silver', 'Silver with black mark']];
  let body = '';
  kinds.forEach(([k, label], i) => {
    const x = 40 + i * 540;
    body += `<g transform="translate(${x} 80)">${appIcon(k, 240, 'a' + i)}</g>` + T(x + 120, 350, label, 18, '#0A0A0C', 700, 'middle');
    body += `<g transform="translate(${x + 250} 80)"><rect width="230" height="300" rx="26" fill="#3A3F4B"/><rect width="230" height="300" rx="26" fill="url(#w${i})"/><defs><linearGradient id="w${i}" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#7C8AA5"/><stop offset="1" stop-color="#D7B8A8"/></linearGradient></defs>`;
    for (let r = 0; r < 4; r++) for (let c = 0; c < 3; c++) {
      const ix = 16 + c * 68, iy = 22 + r * 68;
      if (r === 1 && c === 1) body += `<g transform="translate(${ix} ${iy})">${appIcon(k, 52, 'h' + i)}</g>`;
      else body += `<rect x="${ix}" y="${iy}" width="52" height="52" rx="11.7" fill="#fff" opacity="0.5"/>`;
    }
    body += '</g>';
  });
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1120 400" font-family="Helvetica, Arial, sans-serif"><rect width="100%" height="100%" fill="#FFFFFF"/>${T(40, 44, 'App icon in black and silver (same ring and dot)', 22, '#0A0A0C', 800)}${body}</svg>`;
  save('black-silver-appicon', svg, 2200);
}

function save(name, svg, w) { writeFileSync(join(ROOT, 'mockups', name + '.svg'), svg + '\n'); writeFileSync(join(ROOT, 'mockups', name + '.png'), png(svg, w)); }
iconSheet(); chatSheet(); iconAppSheet();
console.log('ok');
