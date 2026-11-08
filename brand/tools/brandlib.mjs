// Shared drawing code for the Pocketnode brand tools. Pure functions, no file writes.
import { Resvg } from '@resvg/resvg-js';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

export const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');

// ---------------------------------------------------------------- color
export const COLORS = {
  black: '#0A0A0C',
  graphite: '#17181C',
  silver: '#D5D8DE',
  silverLight: '#F5F6F8',
  silverMid: '#A5A9B2',
  white: '#FFFFFF',
};

const lin = (c) => (c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4);
export function luminance(hex) {
  const h = hex.replace('#', '');
  const [r, g, b] = [0, 2, 4].map((i) => lin(parseInt(h.slice(i, i + 2), 16) / 255));
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}
export function contrast(a, b) {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
}
/** Mix color a over b with alpha (0..1); returns hex. */
export function mix(a, b, alpha) {
  const p = (h) => [1, 3, 5].map((i) => parseInt(h.slice(i, i + 2), 16));
  const [x, y] = [p(a), p(b)];
  return '#' + x.map((v, i) => Math.round(v * alpha + y[i] * (1 - alpha)).toString(16).padStart(2, '0')).join('').toUpperCase();
}

// ---------------------------------------------------------------- raster
export function png(svg, width, background) {
  const r = new Resvg(svg, { fitTo: { mode: 'width', value: width }, background, font: { loadSystemFonts: true, defaultFontFamily: "Helvetica" } });
  return r.render().asPng();
}

// ---------------------------------------------------------------- icons
/** One icon as a standalone SVG string. style: 'line' | 'tint'. */
export function iconSvg(inner, { style = 'line', color = 'currentColor', size = 24, stroke = 2 } = {}) {
  const body = style === 'tint'
    ? inner.replace(/<(\w+) t /g, `<$1 fill="${color}" fill-opacity="0.16" `)
    : inner.replace(/<(\w+) t /g, '<$1 ');
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" width="${size}" height="${size}" fill="none" stroke="${color}" stroke-width="${stroke}" stroke-linecap="round" stroke-linejoin="round">${body}</svg>`;
}

const n = (v) => +v.toFixed(3);
function rectPath(x, y, w, h, r) {
  return `M${n(x + r)},${n(y)}H${n(x + w - r)}A${r},${r} 0 0 1 ${n(x + w)},${n(y + r)}V${n(y + h - r)}A${r},${r} 0 0 1 ${n(x + w - r)},${n(y + h)}H${n(x + r)}A${r},${r} 0 0 1 ${n(x)},${n(y + h - r)}V${n(y + r)}A${r},${r} 0 0 1 ${n(x + r)},${n(y)}Z`;
}
function circlePath(cx, cy, r) {
  return `M${n(cx - r)},${n(cy)}A${r},${r} 0 1 1 ${n(cx + r)},${n(cy)}A${r},${r} 0 1 1 ${n(cx - r)},${n(cy)}Z`;
}
const attr = (s, k) => (s.match(new RegExp(`\\b${k}="([^"]*)"`)) || [])[1];

/** Android VectorDrawable for an icon. Color is black; tint it in the app. */
export function iconAndroid(inner, { style = 'line', stroke = 2 } = {}) {
  const els = [...inner.matchAll(/<(path|rect|circle)\b([^>]*?)\/>/g)];
  const paths = els.map(([, tag, a]) => {
    let d;
    if (tag === 'path') d = attr(a, 'd');
    else if (tag === 'rect') d = rectPath(+attr(a, 'x'), +attr(a, 'y'), +attr(a, 'width'), +attr(a, 'height'), +(attr(a, 'rx') || 0));
    else d = circlePath(+attr(a, 'cx'), +attr(a, 'cy'), +attr(a, 'r'));
    const tinted = style === 'tint' && /(^|\s)t(\s|$)/.test(a);
    return `    <path\n        android:pathData="${d}"\n        android:strokeColor="#FF000000"\n        android:strokeWidth="${stroke}"\n        android:strokeLineCap="round"\n        android:strokeLineJoin="round"${tinted ? '\n        android:fillColor="#29000000"' : ''} />`;
  });
  return `<?xml version="1.0" encoding="utf-8"?>\n<vector xmlns:android="http://schemas.android.com/apk/res/android"\n    android:width="24dp"\n    android:height="24dp"\n    android:viewportWidth="24"\n    android:viewportHeight="24">\n${paths.join('\n')}\n</vector>\n`;
}

// ---------------------------------------------------------------- logo mark
// The existing Pocketnode mark, kept as the owner asked: a ring open at the upper right with a
// solid dot sitting in the gap. Geometry on a 108 unit canvas, same as app/.../Components.kt Logo.
const RING = { c: 54, r: 30, stroke: 9, dot: 8 };
const RING_ARC = 'M82.98,46.24A30,30 0 1 1 61.76,25.02';
const RING_DOT = [75.21, 32.79];
export const MARK_BOX = { x: 19.5, y: 19.5, w: 69, h: 69 }; // outer edge of the ring
export const MARK_CENTER = [54, 54];

const WORD = JSON.parse(readFileSync(join(ROOT, 'logo/src/wordmark.json'), 'utf8'));
const X_HEIGHT = WORD.xHeight / WORD.upm;
const ASC = 0.74;

/** Wordmark path scaled to a font size in canvas units, placed with its baseline at (x, y). */
function wordmark(fontSize, x, y, fill) {
  const s = fontSize / WORD.upm;
  return `<path transform="translate(${x} ${y}) scale(${s})" fill="${fill}" d="${WORD.d}"/>`;
}
const svgWrap = (vb, body, extra = '') =>
  `<svg xmlns="http://www.w3.org/2000/svg" viewBox="${vb}" ${extra}>${body}</svg>`;
const markEl = (fill, tf = '') => `<g${tf ? ` transform="${tf}"` : ''}><path fill="none" stroke="${fill}" stroke-width="${RING.stroke}" stroke-linecap="round" d="${RING_ARC}"/><circle fill="${fill}" cx="${RING_DOT[0]}" cy="${RING_DOT[1]}" r="${RING.dot}"/></g>`;

/** Logo files by name. tone: colors for mark and word. */
export function logos({ mark = COLORS.black, word = COLORS.black } = {}) {
  const m = MARK_BOX;
  const fs = 56;
  const wx = m.x + m.w + 20, wy = MARK_CENTER[1] + (X_HEIGHT * fs) / 2;
  const ww = (WORD.width / WORD.upm) * fs;
  const horizontal = svgWrap(`${m.x} ${m.y} ${n(wx + ww - m.x)} ${m.h}`, markEl(mark) + wordmark(fs, wx, wy, word));
  const fs2 = 40;
  const w2 = (WORD.width / WORD.upm) * fs2;
  const base2 = m.y + m.h + 16 + ASC * fs2;
  const stacked = svgWrap(`${n(MARK_CENTER[0] - w2 / 2)} ${m.y} ${n(w2)} ${n(base2 - m.y + 2)}`, markEl(mark) + wordmark(fs2, MARK_CENTER[0] - w2 / 2, base2, word));
  const markOnly = svgWrap(`${m.x} ${m.y} ${m.w} ${m.h}`, markEl(mark));
  const wordOnly = svgWrap(`0 ${-ASC * fs} ${n(ww)} ${n(ASC * fs + 2)}`, wordmark(fs, 0, 0, word));
  return { horizontal, stacked, mark: markOnly, wordmark: wordOnly };
}

/** App icon: square tile with the cloud, mark centered. fraction = mark width over tile width. */
export function appIcon({ bg = COLORS.graphite, fg = COLORS.silver, fraction = 0.6, radius = 0.225, size = 1024 } = {}) {
  const s = (size * fraction) / MARK_BOX.w;
  const tx = size / 2 - MARK_CENTER[0] * s, ty = size / 2 - MARK_CENTER[1] * s;
  const rect = bg ? `<rect width="${size}" height="${size}" rx="${size * radius}" fill="${bg}"/>` : '';
  return svgWrap(`0 0 ${size} ${size}`, rect + markEl(fg, `translate(${n(tx)} ${n(ty)}) scale(${n(s)})`), `width="${size}" height="${size}"`);
}

/** Android adaptive icon foreground: mark on the 108 canvas, inside the 66 unit safe circle. */
export function androidForeground(fill = '#FFFFFFFF', scale = 0.85) {
  return `<?xml version="1.0" encoding="utf-8"?>\n<vector xmlns:android="http://schemas.android.com/apk/res/android"\n    android:width="108dp"\n    android:height="108dp"\n    android:viewportWidth="108"\n    android:viewportHeight="108">\n    <group android:scaleX="${scale}" android:scaleY="${scale}" android:pivotX="54" android:pivotY="54">\n        <path\n            android:pathData="${RING_ARC.replace('A', ' A')}"\n            android:strokeColor="${fill}"\n            android:strokeWidth="${RING.stroke}"\n            android:strokeLineCap="round" />\n        <path\n            android:fillColor="${fill}"\n            android:pathData="M67.21,32.79 a8,8 0 1 0 16,0 a8,8 0 1 0 -16,0" />\n    </group>\n</vector>\n`;
}

// ---------------------------------------------------------------- icon tile (style A: black tile, silver glyph)
/** Markup (no svg wrapper) for an icon on its black tile with a silver metal glyph. */
export function tileMarkup(inner, x, y, size, uid = '') {
  const rx = size * 0.225, k = (size * 0.56) / 24;
  const gx = x + (size - 24 * k) / 2, gy = y + (size - 24 * k) / 2;
  const body = inner.replace(/<(\w+) t /g, '<$1 ');
  return `<defs><linearGradient id="tb${uid}" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#2A2B31"/><stop offset="1" stop-color="#070708"/></linearGradient><linearGradient id="te${uid}" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#8E939D"/><stop offset="1" stop-color="#2E3036"/></linearGradient><linearGradient id="ts${uid}" gradientUnits="userSpaceOnUse" x1="0" y1="0" x2="0" y2="24"><stop offset="0" stop-color="#FFFFFF"/><stop offset="0.45" stop-color="#C9CDD4"/><stop offset="1" stop-color="#8F949E"/></linearGradient></defs>`
    + `<rect x="${x + 0.6}" y="${y + 0.6}" width="${size - 1.2}" height="${size - 1.2}" rx="${rx}" fill="url(#tb${uid})" stroke="url(#te${uid})" stroke-width="1.2"/>`
    + `<rect x="${x + 1.5}" y="${y + 1.5}" width="${size - 3}" height="${size * 0.5}" rx="${rx}" fill="#fff" opacity="0.05"/>`
    + `<g transform="translate(${n(gx)} ${n(gy)}) scale(${n(k)})" fill="none" stroke="url(#ts${uid})" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">${body}</g>`;
}
export function tileSvg(inner, size = 96) {
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${size} ${size}" width="${size}" height="${size}">${tileMarkup(inner, 0, 0, size)}</svg>`;
}

// ---------------------------------------------------------------- app icon with depth (Apple style layers)
/** kind: 'black' (silver mark on black) or 'silver' (black mark on silver). radius 0 for store files. */
export function appIconDepth(kind = 'black', size = 1024, radius = 0.225, scale = 0.95) {
  const dark = kind !== 'silver';
  const bg = dark ? ['#2C2D33', '#050506'] : ['#FAFAFC', '#B7BBC3'];
  const ring = dark ? ['#FFFFFF', '#9DA2AC'] : ['#2A2B31', '#050506'];
  const k = size / 108, rx = size * radius;
  const clip = rx > 0 ? `<clipPath id="c"><rect width="${size}" height="${size}" rx="${rx}"/></clipPath>` : `<clipPath id="c"><rect width="${size}" height="${size}"/></clipPath>`;
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${size} ${size}" width="${size}" height="${size}"><defs><linearGradient id="b" x1="0.1" y1="0" x2="0.9" y2="1"><stop offset="0" stop-color="${bg[0]}"/><stop offset="1" stop-color="${bg[1]}"/></linearGradient><radialGradient id="r" cx="0.25" cy="0.1" r="0.85"><stop offset="0" stop-color="#fff" stop-opacity="${dark ? 0.16 : 0.5}"/><stop offset="1" stop-color="#fff" stop-opacity="0"/></radialGradient><linearGradient id="m" x1="0" y1="0" x2="0.3" y2="1"><stop offset="0" stop-color="${ring[0]}"/><stop offset="1" stop-color="${ring[1]}"/></linearGradient><filter id="s" x="-30%" y="-30%" width="160%" height="170%"><feGaussianBlur in="SourceAlpha" stdDeviation="2.2"/><feOffset dx="1.2" dy="2.8" result="b"/><feFlood flood-color="#000" flood-opacity="${dark ? 0.7 : 0.3}"/><feComposite in2="b" operator="in"/><feMerge><feMergeNode/><feMergeNode in="SourceGraphic"/></feMerge></filter>${clip}</defs>`
    + `<g clip-path="url(#c)"><rect width="${size}" height="${size}" fill="url(#b)"/><rect width="${size}" height="${size}" fill="url(#r)"/>`
    + `<g transform="scale(${k}) translate(54 54) scale(${scale}) translate(-54 -54)"><path d="${RING_ARC}" fill="none" stroke="url(#m)" stroke-width="9" stroke-linecap="round" filter="url(#s)"/><circle cx="${RING_DOT[0]}" cy="${RING_DOT[1]}" r="8.4" fill="url(#m)" filter="url(#s)"/><circle cx="${RING_DOT[0] - 2.6}" cy="${RING_DOT[1] - 3}" r="2.2" fill="#fff" opacity="${dark ? 0.7 : 0.35}"/></g></g>`
    + `<rect width="${size}" height="${size}" rx="${rx}" fill="none" stroke="${dark ? '#fff' : '#000'}" stroke-opacity="${dark ? 0.14 : 0.1}"/></svg>`;
}

/** The mark alone with the silver metal gradient, for dark backgrounds. */
export function markMetal() {
  const m = MARK_BOX;
  return svgWrap(`${m.x} ${m.y} ${m.w} ${m.h}`, `<defs><linearGradient id="m" gradientUnits="userSpaceOnUse" x1="0" y1="19" x2="20" y2="89"><stop offset="0" stop-color="#FFFFFF"/><stop offset="0.5" stop-color="#C9CDD4"/><stop offset="1" stop-color="#8F949E"/></linearGradient></defs><path fill="none" stroke="url(#m)" stroke-width="${RING.stroke}" stroke-linecap="round" d="${RING_ARC}"/><circle fill="url(#m)" cx="${RING_DOT[0]}" cy="${RING_DOT[1]}" r="${RING.dot}"/>`);
}
