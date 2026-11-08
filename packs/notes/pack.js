// Notes and to-dos, kept in the pack's private store on this phone. No network.
const MAX_ITEMS = 500, MAX_TEXT = 2000;

async function load() {
  const raw = await store.get('data');
  const d = raw ? JSON.parse(raw) : {};
  return { next: d.next || 1, notes: d.notes || [], todos: d.todos || [] };
}
const save = (d) => store.set('data', JSON.stringify(d));

function clean(text, what) {
  const t = String(text == null ? '' : text).trim();
  if (!t) throw new Error(`The ${what} is empty`);
  return t.slice(0, MAX_TEXT);
}

pack = {
  tools: {
    'notes.add': async ({ text }) => {
      const d = await load();
      if (d.notes.length >= MAX_ITEMS) throw new Error('There are too many notes. Delete some first');
      const note = { id: d.next++, text: clean(text, 'note'), created: new Date().toISOString() };
      d.notes.push(note);
      await save(d);
      return { saved: true, id: note.id };
    },
    'notes.list': async ({ limit } = {}) => {
      const d = await load();
      const n = Math.min(100, Math.max(1, Math.round(Number(limit) || 20)));
      return { total: d.notes.length, notes: d.notes.slice().reverse().slice(0, n) };
    },
    'notes.search': async ({ query }) => {
      const words = String(query || '').toLowerCase().split(/\s+/).filter(Boolean);
      if (!words.length) throw new Error('Say what to search for');
      const d = await load();
      return { matches: d.notes.filter((n) => words.every((w) => n.text.toLowerCase().includes(w))).reverse().slice(0, 30) };
    },
    'notes.delete': async ({ id }) => {
      const d = await load();
      const before = d.notes.length;
      const doomed = d.notes.find((n) => n.id === Number(id));
      if (!doomed) throw new Error(`There is no note ${id}`);
      // Deleting is permanent, so the owner decides, not the assistant.
      await device.call('owner.confirm', { title: 'Delete this note?', message: String(doomed.text).slice(0, 300), allow: 'Delete', deny: 'Keep it' });
      d.notes = d.notes.filter((n) => n.id !== Number(id));
      if (d.notes.length === before) throw new Error(`There is no note ${id}`);
      await save(d);
      return { deleted: true, id: Number(id) };
    },
    'todo.add': async ({ text, due }) => {
      const d = await load();
      if (d.todos.length >= MAX_ITEMS) throw new Error('The to-do list is full. Clear some items first');
      if (due != null && due !== '' && !/^\d{4}-\d{2}-\d{2}$/.test(String(due))) throw new Error('A due date looks like 2026-12-31');
      const item = { id: d.next++, text: clean(text, 'to-do'), due: due || null, done: false, created: new Date().toISOString() };
      d.todos.push(item);
      await save(d);
      return { added: true, id: item.id };
    },
    'todo.list': async ({ includeDone } = {}) => {
      const d = await load();
      const items = d.todos.filter((t) => includeDone || !t.done);
      items.sort((a, b) => (a.due || '9999-12-31').localeCompare(b.due || '9999-12-31') || a.id - b.id);
      return { open: d.todos.filter((t) => !t.done).length, items };
    },
    'todo.done': async ({ id }) => {
      const d = await load();
      const item = d.todos.find((t) => t.id === Number(id));
      if (!item) throw new Error(`There is no to-do ${id}`);
      item.done = true;
      await save(d);
      return { done: true, id: item.id, text: item.text };
    },
  },
};
