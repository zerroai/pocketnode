// App icon concepts built the way Apple describes: flat simple shapes on separate layers, a rich
// backdrop, then soft light, highlight and shadow added on top. Same ring and dot mark in all.
// Run: node brand/tools/icon-concepts.mjs   (writes brand/mockups/explorations/icon-concepts.*)
import { writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { ROOT, png } from './brandlib.mjs';

const ARC = 'M82.98,46.24A30,30 0 1 1 61.76,25.02';
const DOT = [75.21, 32.79];

const CONCEPTS = [
  { id: 'sunrise', name: '1. Sunrise', note: 'warm coral to amber, cream mark', bg: ['#FF8A5B', '#E8452C'], glow: '#FFD2A8', ring: ['#FFFFFF', '#FFE9D6'], dot: ['#FFFFFF', '#FFE2C8'], shadow: '#8A1F0A' },
  { id: 'sky', name: '2. Sky', note: 'keeps the blue, lighter and softer', bg: ['#5AB6FF', '#1F5CE0'], glow: '#C9ECFF', ring: ['#FFFFFF', '#E3F1FF'], dot: ['#FFFFFF', '#DDEEFF'], shadow: '#0B2A78' },
  { id: 'meadow', name: '3. Meadow', note: 'friendly green, a trust color', bg: ['#4FD18B', '#12935A'], glow: '#D5FFE6', ring: ['#FFFFFF', '#E6FFF0'], dot: ['#FFF3B0', '#FFD84D'], shadow: '#065235' },
  { id: 'plum', name: '4. Plum and honey', note: 'deep violet, honey dot stands out', bg: ['#8A6BFF', '#4B2BC9'], glow: '#D9CCFF', ring: ['#FFFFFF', '#EDE6FF'], dot: ['#FFE08A', '#FFB52E'], shadow: '#1F0E66' },
  { id: 'cream', name: '5. Cream tile', note: 'light tile, colored mark (Headspace style)', bg: ['#FFFFFF', '#FFF0E3'], glow: '#FFFFFF', ring: ['#FF8A5B', '#E8452C'], dot: ['#FFC13D', '#FF9A1F'], shadow: '#B5421F', light: true },
];

function tile(c, size, uid) {
  const k = size / 108; // mark canvas to tile canvas
  const s = 1.0; // mark scale inside the tile
  return `<g>
<defs>
<linearGradient id="bg${uid}" x1="0.15" y1="0" x2="0.85" y2="1"><stop offset="0" stop-color="${c.bg[0]}"/><stop offset="1" stop-color="${c.bg[1]}"/></linearGradient>
<radialGradient id="gl${uid}" cx="0.25" cy="0.15" r="0.8"><stop offset="0" stop-color="${c.glow}" stop-opacity="${c.light ? 0 : 0.55}"/><stop offset="1" stop-color="${c.glow}" stop-opacity="0"/></radialGradient>
<linearGradient id="rg${uid}" x1="0" y1="0" x2="0.3" y2="1"><stop offset="0" stop-color="${c.ring[0]}"/><stop offset="1" stop-color="${c.ring[1]}"/></linearGradient>
<linearGradient id="dg${uid}" x1="0" y1="0" x2="0.3" y2="1"><stop offset="0" stop-color="${c.dot[0]}"/><stop offset="1" stop-color="${c.dot[1]}"/></linearGradient>
<filter id="sh${uid}" x="-30%" y="-30%" width="160%" height="170%"><feGaussianBlur in="SourceAlpha" stdDeviation="2.2"/><feOffset dx="1.2" dy="2.6" result="b"/><feFlood flood-color="${c.shadow}" flood-opacity="${c.light ? 0.3 : 0.45}"/><feComposite in2="b" operator="in"/><feMerge><feMergeNode/><feMergeNode in="SourceGraphic"/></feMerge></filter>
<filter id="sh2${uid}" x="-50%" y="-50%" width="200%" height="220%"><feGaussianBlur in="SourceAlpha" stdDeviation="2.6"/><feOffset dx="1.4" dy="3.4" result="b"/><feFlood flood-color="${c.shadow}" flood-opacity="${c.light ? 0.35 : 0.5}"/><feComposite in2="b" operator="in"/><feMerge><feMergeNode/><feMergeNode in="SourceGraphic"/></feMerge></filter>
<clipPath id="cl${uid}"><rect width="${size}" height="${size}" rx="${size * 0.225}"/></clipPath>
</defs>
<g clip-path="url(#cl${uid})">
<rect width="${size}" height="${size}" fill="url(#bg${uid})"/>
<rect width="${size}" height="${size}" fill="url(#gl${uid})"/>
<g transform="scale(${k * s}) translate(${54 - 54 * s / s} ${54 - 54})" >
<g transform="translate(54 54) scale(0.95) translate(-54 -54)">
<path d="${ARC}" fill="none" stroke="url(#rg${uid})" stroke-width="9" stroke-linecap="round" filter="url(#sh${uid})"/>
<path d="${ARC}" fill="none" stroke="#fff" stroke-opacity="${c.light ? 0.25 : 0.7}" stroke-width="1.6" stroke-linecap="round" transform="translate(-1.5 -1.5) scale(1)" clip-path="none" opacity="0"/>
<circle cx="${DOT[0]}" cy="${DOT[1]}" r="8.4" fill="url(#dg${uid})" filter="url(#sh2${uid})"/>
<circle cx="${DOT[0] - 2.6}" cy="${DOT[1] - 3}" r="2.4" fill="#fff" opacity="${c.light ? 0.5 : 0.7}"/>
</g></g></g>
<rect width="${size}" height="${size}" rx="${size * 0.225}" fill="none" stroke="#000" stroke-opacity="0.06"/>
</g>`;
}

const grey = ['#E7D9CC', '#D6DEE8', '#E5E2F0', '#DCE9DD', '#F1E4C7', '#E9D5D9', '#D3E3E6', '#E2DCCF', '#DDD6E8', '#D9E4D2', '#EBD9C8'];
function home(c, x, y, uid) {
  // a small home screen: 4 columns x 4 rows of icons, ours at row 2 col 2, placeholders elsewhere
  let s = `<g transform="translate(${x} ${y})"><rect width="300" height="400" rx="30" fill="#2F3B4F"/><rect width="300" height="400" rx="30" fill="url(#wall${uid})"/>`;
  s += `<defs><linearGradient id="wall${uid}" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#9DB7D8"/><stop offset="1" stop-color="#E5C9B8"/></linearGradient></defs>`;
  let n = 0;
  for (let r = 0; r < 4; r++) for (let col = 0; col < 4; col++) {
    const ix = 20 + col * 70, iy = 40 + r * 82;
    if (r === 1 && col === 1) s += `<g transform="translate(${ix} ${iy})">${tile(c, 56, uid + 'h')}</g>` + `<text x="${ix + 28}" y="${iy + 72}" font-size="10" fill="#fff" text-anchor="middle">Pocketnode</text>`;
    else { s += `<rect x="${ix}" y="${iy}" width="56" height="56" rx="12.6" fill="${grey[n % grey.length]}"/><rect x="${ix + 14}" y="${iy + 14}" width="28" height="28" rx="8" fill="#fff" opacity="0.5"/><text x="${ix + 28}" y="${iy + 72}" font-size="10" fill="#fff" text-anchor="middle" opacity="0.8">App</text>`; n++; }
  }
  return s + '</g>';
}

let body = '';
CONCEPTS.forEach((c, i) => {
  const x = 40 + i * 340;
  body += `<g transform="translate(${x} 70)">${tile(c, 260, 'b' + i)}</g>`;
  body += `<text x="${x + 130}" y="378" font-size="19" font-weight="700" fill="#211A17" text-anchor="middle">${c.name}</text><text x="${x + 130}" y="402" font-size="13" fill="#62554D" text-anchor="middle">${c.note}</text>`;
  body += home(c, x - 10, 440, 'h' + i);
});
const W = 40 + CONCEPTS.length * 340, H = 880;
const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${W} ${H}" font-family="Helvetica, Arial, sans-serif"><rect width="100%" height="100%" fill="#FFFBF7"/><text x="40" y="44" font-size="22" font-weight="800" fill="#211A17">App icon concepts: the same ring and dot, built with a backdrop, light and depth</text>${body}</svg>`;
writeFileSync(join(ROOT, 'mockups/explorations/icon-concepts.svg'), svg + '\n');
writeFileSync(join(ROOT, 'mockups/explorations/icon-concepts.png'), png(svg, 2400));
console.log('ok');
