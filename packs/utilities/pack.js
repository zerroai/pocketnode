// Utilities pack: pure functions, no network and no secrets.
const FACTORS = { 'km>mi': (v) => v * 0.621371, 'mi>km': (v) => v / 0.621371, 'kg>lb': (v) => v * 2.204623, 'lb>kg': (v) => v / 2.204623,
  'c>f': (v) => v * 9 / 5 + 32, 'f>c': (v) => (v - 32) * 5 / 9 };

pack = {
  tools: {
    'text.stats': async ({ text }) => {
      const t = String(text);
      return { words: t.split(/\s+/).filter(Boolean).length, characters: t.length, lines: t === '' ? 0 : t.split('\n').length };
    },
    'units.convert': async ({ value, from, to }) => {
      const f = FACTORS[`${from}>${to}`];
      if (!f) throw new Error(`Cannot convert ${from} to ${to}`);
      return { value: Math.round(f(Number(value)) * 1000) / 1000, unit: to };
    },
  },
};
