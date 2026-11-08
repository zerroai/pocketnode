// Math and dates: pure functions, no network. The calculator is a small parser, never eval.
const FUNCS = {
  sqrt: Math.sqrt, abs: Math.abs, round: Math.round, floor: Math.floor, ceil: Math.ceil, ln: Math.log, log10: Math.log10,
  sin: Math.sin, cos: Math.cos, tan: Math.tan, min: Math.min, max: Math.max,
};
const CONSTS = { pi: Math.PI, e: Math.E };

function evaluate(src) {
  const s = String(src || '');
  if (s.length > 300) throw new Error('The expression is too long');
  let i = 0, depth = 0;
  const ws = () => { while (s[i] === ' ' || s[i] === '\t') i++; };
  const peek = () => { ws(); return s[i]; };
  function expr() {
    let v = term();
    for (;;) { const c = peek(); if (c === '+') { i++; v += term(); } else if (c === '-') { i++; v -= term(); } else return v; }
  }
  function term() {
    let v = unary();
    for (;;) {
      const c = peek();
      if (c === '*') { i++; v *= unary(); }
      else if (c === '/') { i++; const d = unary(); if (d === 0) throw new Error('Division by zero'); v /= d; }
      else if (c === '%') { i++; const d = unary(); if (d === 0) throw new Error('Division by zero'); v %= d; }
      else return v;
    }
  }
  function unary() { const c = peek(); if (c === '-') { i++; return -unary(); } if (c === '+') { i++; return unary(); } return power(); }
  function power() { const b = atom(); if (peek() === '^') { i++; return Math.pow(b, unary()); } return b; }
  function atom() {
    const c = peek();
    if (c === '(') { if (++depth > 40) throw new Error('Too many nested brackets'); i++; const v = expr(); if (peek() !== ')') throw new Error('A bracket is not closed'); i++; depth--; return v; }
    const num = /^(\d+\.?\d*|\.\d+)(e[+-]?\d+)?/i.exec(s.slice(i));
    if (num) { i += num[0].length; return Number(num[0]); }
    const id = /^[a-z][a-z0-9]*/i.exec(s.slice(i));
    if (id) {
      const name = id[0].toLowerCase(); i += id[0].length;
      if (peek() === '(') {
        if (!(name in FUNCS)) throw new Error(`I do not know the function ${name}`);
        i++; const args = [];
        if (peek() !== ')') { for (;;) { args.push(expr()); if (peek() === ',') { i++; continue; } break; } }
        if (peek() !== ')') throw new Error('A bracket is not closed');
        i++; return FUNCS[name](...args);
      }
      if (name in CONSTS) return CONSTS[name];
      throw new Error(`I do not know "${id[0]}"`);
    }
    throw new Error(i >= s.length ? 'The expression ended too early' : `Unexpected "${s[i]}"`);
  }
  const v = expr();
  if (peek() !== undefined) throw new Error(`Unexpected "${s[i]}"`);
  if (!isFinite(v)) throw new Error('That has no finite answer');
  return v;
}

const DAY = 86400000;
function parseDate(text) {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(String(text || '').trim());
  if (!m) throw new Error('A date looks like 2026-12-31');
  const t = Date.UTC(+m[1], +m[2] - 1, +m[3]);
  const d = new Date(t);
  if (d.getUTCFullYear() !== +m[1] || d.getUTCMonth() !== +m[2] - 1 || d.getUTCDate() !== +m[3]) throw new Error(`${text} is not a real date`);
  return t;
}
const iso = (t) => new Date(t).toISOString().slice(0, 10);
const WEEKDAYS = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'];
const weekday = (t) => WEEKDAYS[new Date(t).getUTCDay()];
const tidy = (n) => Math.round(n * 1e10) / 1e10;

pack = {
  tools: {
    'math.calc': async ({ expression }) => ({ expression: String(expression), result: tidy(evaluate(expression)) }),
    'math.percent': async ({ value, percent, op }) => {
      const v = Number(value), p = Number(percent);
      if (!isFinite(v) || !isFinite(p)) throw new Error('The value and percent must be numbers');
      if (op === 'of') return { result: tidy(v * p / 100) };
      if (op === 'increase') return { result: tidy(v * (1 + p / 100)) };
      if (op === 'decrease') return { result: tidy(v * (1 - p / 100)) };
      throw new Error('op must be of, increase or decrease');
    },
    'date.diff': async ({ from, to }) => {
      const days = Math.round((parseDate(to) - parseDate(from)) / DAY);
      return { days, weeks: Math.floor(Math.abs(days) / 7) * Math.sign(days), remainingDays: Math.abs(days) % 7 };
    },
    'date.add': async ({ date, days }) => {
      const n = Math.round(Number(days));
      if (!isFinite(n) || Math.abs(n) > 365000) throw new Error('The number of days is not usable');
      const t = parseDate(date) + n * DAY;
      return { date: iso(t), weekday: weekday(t) };
    },
    'date.weekday': async ({ date }) => ({ date: String(date).trim(), weekday: weekday(parseDate(date)) }),
  },
};
