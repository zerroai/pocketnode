// Phone mockups that show the brand in the chat screens. Not the real app: drawn from the layout
// rules in docs/design/ui-guidelines.md. Run: node brand/tools/mockups.mjs
import { writeFileSync, mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { ICONS } from './icons.mjs';
import { ROOT, png, mix } from './brandlib.mjs';

const ic = Object.fromEntries(ICONS.map(([n, , , inner]) => [n, inner]));
const W = 360, H = 740;
const icon = (name, x, y, size, color, tint) => {
  const inner = ic[name].replace(/<(\w+) t /g, tint ? `<$1 fill="${color}" fill-opacity="0.16" ` : '<$1 ');
  return `<g transform="translate(${x} ${y}) scale(${size / 24})" fill="none" stroke="${color}" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">${inner}</g>`;
};
const t = (x, y, s, size, fill, weight = 400, anchor = 'start') => `<text x="${x}" y="${y}" font-size="${size}" font-weight="${weight}" fill="${fill}" text-anchor="${anchor}">${s}</text>`;

const VARIANTS = {
  'A-today': {
    label: 'A. Today: white and blue',
    light: { bg: '#FFFFFF', surface: '#FFFFFF', fill: '#F1F1F4', sep: '#E4E4E9', text: '#0F0F12', t2: '#5E5E63', accent: '#0A5BE0', onAccent: '#FFFFFF', soft: '#0A5BE01F' },
    dark: { bg: '#000000', surface: '#1A1A1D', fill: '#1A1A1D', sep: '#2E2E32', text: '#F5F5F7', t2: '#A5A5AB', accent: '#4D8DFF', onAccent: '#000000', soft: '#4D8DFF29' },
  },
  'B-ember': {
    label: 'B. Ember on cream (proposed)',
    light: { bg: '#FFFBF7', surface: '#FFFFFF', fill: '#F3ECE4', sep: '#E8DED3', text: '#211A17', t2: '#62554D', accent: '#C4381B', onAccent: '#FFFFFF', soft: '#C4381B1F' },
    dark: { bg: '#17110F', surface: '#241C19', fill: '#2A211E', sep: '#3A2F2A', text: '#FBF3EC', t2: '#BFB0A6', accent: '#FF8A65', onAccent: '#17110F', soft: '#FF8A6529' },
  },
  'C-cream-blue': {
    label: 'C. Warm cream, keep the blue',
    light: { bg: '#FFFBF7', surface: '#FFFFFF', fill: '#F3ECE4', sep: '#E8DED3', text: '#211A17', t2: '#62554D', accent: '#0A5BE0', onAccent: '#FFFFFF', soft: '#0A5BE01F' },
    dark: { bg: '#17110F', surface: '#241C19', fill: '#2A211E', sep: '#3A2F2A', text: '#FBF3EC', t2: '#BFB0A6', accent: '#4D8DFF', onAccent: '#17110F', soft: '#4D8DFF29' },
  },
};

function frame(p, body) {
  return `<rect width="${W}" height="${H}" rx="28" fill="${p.bg}"/>
${t(24, 22, '9:41', 12, p.text, 700)}${icon('wifi', 290, 8, 16, p.text)}${icon('battery', 312, 8, 18, p.text)}${body}`;
}

function conversation(p) {
  let b = '';
  b += icon('back', 12, 36, 24, p.text) + t(180, 54, 'Battery and weather', 16, p.text, 700, 'middle');
  b += `<circle cx="326" cy="48" r="16" fill="${p.fill}"/>` + icon('profile', 316, 38, 20, p.text);
  b += `<line x1="0" y1="72" x2="${W}" y2="72" stroke="${p.sep}"/>`;
  // user bubble (ink)
  b += `<rect x="96" y="90" width="248" height="38" rx="19" fill="${p.text}"/>` + t(112, 114, 'How much battery do I have left?', 12.5, p.bg, 500);
  // tool card
  b += `<rect x="16" y="144" width="300" height="56" rx="18" fill="${p.surface}" stroke="${p.sep}"/><rect x="28" y="156" width="32" height="32" rx="10" fill="${p.soft}"/>` + icon('battery', 32, 160, 24, p.accent, true) + t(72, 172, 'Phone', 13, p.text, 700) + t(72, 189, 'Battery 59%, on wifi', 12, p.t2) + icon('check', 288, 164, 20, p.accent);
  // reply, no bubble
  b += t(18, 226, 'You have 59% left. I looked it up just now.', 13, p.text);
  // user bubble 2
  b += `<rect x="40" y="248" width="304" height="58" rx="22" fill="${p.text}"/>` + t(56, 270, 'Every morning at 7:30 tell me the weather in', 12.5, p.bg, 500) + t(56, 289, 'Accra, but only if it will rain', 12.5, p.bg, 500);
  // approval card
  b += `<rect x="16" y="322" width="328" height="170" rx="20" fill="${p.surface}" stroke="${p.sep}"/><rect x="30" y="336" width="32" height="32" rx="16" fill="${p.soft}"/>` + icon('shield', 34, 340, 24, p.accent, true) + t(74, 358, 'Add this agent?', 15, p.text, 700);
  b += t(30, 392, 'Weather alert', 13, p.text, 700) + t(30, 411, 'Every day at 7:30. Checks the Accra forecast', 12, p.t2) + t(30, 428, 'and tells you only if it will rain.', 12, p.t2);
  b += `<rect x="30" y="444" width="140" height="36" rx="18" fill="${p.fill}"/>` + t(100, 467, 'Not now', 13, p.text, 700, 'middle') + `<rect x="180" y="444" width="150" height="36" rx="18" fill="${p.text}"/>` + t(255, 467, 'Add agent', 13, p.bg, 700, 'middle');
  // composer
  b += `<rect x="12" y="664" width="336" height="56" rx="28" fill="${p.fill}"/>` + icon('plus', 24, 680, 24, p.text) + t(60, 697, 'Message', 14, p.t2) + icon('mic', 270, 680, 24, p.text) + `<circle cx="324" cy="692" r="18" fill="${p.accent}"/>` + icon('send', 314, 682, 20, p.onAccent);
  return frame(p, b);
}

function list(p) {
  let b = '';
  b += t(20, 84, 'Chats', 30, p.text, 800) + `<circle cx="326" cy="70" r="16" fill="${p.fill}"/>` + icon('profile', 316, 60, 20, p.text);
  b += `<rect x="16" y="104" width="328" height="40" rx="20" fill="${p.fill}"/>` + icon('search', 28, 114, 20, p.t2) + t(58, 129, 'Search', 14, p.t2);
  const rows = [['Battery and weather', 'You have 59% left. I looked it up', 'chat'], ['Weather alert', 'Agent ran at 7:30. No rain in Accra', 'agents'], ['Shopping list', 'Added bread and eggs to your notes', 'note'], ['Trip to Lagos', 'Packed list saved to Files', 'files']];
  rows.forEach(([a, c, g], i) => {
    const y = 164 + i * 70;
    b += `<rect x="20" y="${y}" width="44" height="44" rx="14" fill="${p.soft}"/>` + icon(g, 30, y + 10, 24, p.accent, true) + t(78, y + 20, a, 15, p.text, 700) + t(78, y + 40, c, 12.5, p.t2);
    if (i < rows.length - 1) b += `<line x1="78" y1="${y + 58}" x2="344" y2="${y + 58}" stroke="${p.sep}"/>`;
  });
  // floating pill tab bar
  b += `<rect x="20" y="654" width="320" height="64" rx="32" fill="${p.surface}" stroke="${p.sep}"/>`;
  [['chat', 'Chats', 0], ['files', 'Files', 1], ['packs', 'Packs', 2], ['agents', 'Agents', 3]].forEach(([g, l, i]) => {
    const cx = 54 + i * 84, on = i === 0;
    if (on) b += `<rect x="${cx - 36}" y="662" width="72" height="48" rx="24" fill="${p.soft}"/>`;
    b += icon(g, cx - 12, 667, 24, on ? p.accent : p.t2, on) + t(cx, 703, l, 11, on ? p.accent : p.t2, on ? 700 : 500, 'middle');
  });
  return frame(p, b);
}

const phone = (svg, x, y) => `<g transform="translate(${x} ${y})"><rect x="-6" y="-6" width="${W + 12}" height="${H + 12}" rx="34" fill="#00000014"/>${svg}</g>`;
const sheet = (cols, rows, body, extra = 0) => {
  const w = cols * (W + 40) + 40, h = rows * (H + 90) + 40 + extra;
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${w} ${h}" font-family="Helvetica, Arial, sans-serif"><rect width="100%" height="100%" fill="#8E8A87"/>${body}</svg>`;
};

mkdirSync(join(ROOT, 'mockups/explorations'), { recursive: true });
const save = (name, svg, width) => { writeFileSync(join(ROOT, 'mockups/explorations', name + '.svg'), svg + '\n'); writeFileSync(join(ROOT, 'mockups/explorations', name + '.png'), png(svg, width)); };

for (const [kind, fn] of [['chat', conversation], ['chats-list', list]]) {
  let body = '';
  Object.entries(VARIANTS).forEach(([, v], c) => {
    body += `<text x="${40 + c * (W + 40) + W / 2}" y="40" font-size="20" font-weight="700" fill="#fff" text-anchor="middle">${v.label}</text>`;
    ['light', 'dark'].forEach((mode, r) => { body += phone(fn(v[mode]), 40 + c * (W + 40), 70 + r * (H + 60)); });
  });
  save(`compare-${kind}`, sheet(3, 2, body, 0), 1800);
}
// The proposed look alone: four screens
let body = '';
['light', 'dark'].forEach((mode, r) => [conversation, list].forEach((fn, c) => { body += phone(fn(VARIANTS['B-ember'][mode]), 40 + (c + r * 2) * (W + 40), 40); }));
save('proposed-ember', sheet(4, 1, body, -90), 2000);
console.log('mockups written to brand/mockups');
