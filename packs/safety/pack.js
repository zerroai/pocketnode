// Safety: one emergency contact, a ready-made "I need help" text with where you are, a plain "here I am" text, and a check-in
// reminder. Nothing is ever sent by itself: the text opens in the messages app and the owner presses send.
const NUMBER = /^\+?[0-9][0-9 ()\-]{4,19}$/;

const short = (s, n) => String(s == null ? '' : s).trim().slice(0, n);

async function contact() {
  const raw = await store.get('contact');
  return raw ? JSON.parse(raw) : null; // an empty string means it was cleared
}

async function where() {
  try {
    const p = await device.call('location.get', {});
    if (typeof p.latitude !== 'number' || typeof p.longitude !== 'number') return null;
    return p;
  } catch (e) {
    return { failed: String((e && e.message) || e) };
  }
}

function place(p) {
  if (!p) return { line: '', note: 'No location was available, so the text has none.' };
  if (p.failed) return { line: '', note: 'The location was not shared (' + p.failed + '), so the text has none.' };
  const lat = p.latitude.toFixed(5), lon = p.longitude.toFixed(5);
  const acc = typeof p.accuracyMeters === 'number' ? ` (within about ${Math.round(p.accuracyMeters)} m)` : '';
  return { line: `My location: https://maps.google.com/?q=${lat},${lon}${acc}`, note: 'The text has a map link to where the phone is now.' };
}

async function compose(opening, extra) {
  const c = await contact();
  if (!c) throw new Error('There is no emergency contact yet. Ask the owner for a name and number, then use safety.set_contact');
  const loc = place(await where());
  const text = [opening, short(extra, 300), loc.line].filter(Boolean).join('\n');
  const opened = await device.call('sms.compose', { number: c.number, text });
  return { to: c.name, number: c.number, text, locationNote: loc.note, opened: opened && opened.done, sent: false, note: 'The message is ready in the messages app. The owner has to press send.' };
}

pack = {
  tools: {
    'safety.set_contact': async ({ name, number } = {}) => {
      const n = short(name, 60), num = short(number, 24);
      if (!n) throw new Error('Give the contact\'s name');
      if (!NUMBER.test(num)) throw new Error('The number should be digits, like +2348012345678');
      const old = await contact();
      // This decides who gets the owner's location in an emergency, so the owner approves every change.
      await device.call('owner.confirm', { title: old ? 'Change your emergency contact?' : 'Set your emergency contact?', message: `${n}\n${num}` + (old ? `\nNow: ${old.name}, ${old.number}` : ''), allow: 'Save', deny: 'Do not save' });
      await store.set('contact', JSON.stringify({ name: n, number: num }));
      return { saved: true, name: n, number: num };
    },
    'safety.contact': async () => {
      const c = await contact();
      return c ? { name: c.name, number: c.number } : { name: null, note: 'No emergency contact is set yet.' };
    },
    'safety.alert': async ({ note } = {}) => compose('I need help. Please call me or come if you can.', note),
    'safety.share_location': async ({ note } = {}) => compose('Here is where I am.', note),
    'safety.check_in': async ({ minutes, message } = {}) => {
      const m = Math.round(Number(minutes));
      if (!isFinite(m) || m < 1 || m > 1440) throw new Error('minutes is 1 to 1440');
      const c = await contact();
      const text = short(message, 120) || 'Safety check-in: are you OK?';
      const r = await device.call('reminder.add', { text: text + (c ? ` If not, ask Pocketnode to alert ${c.name}.` : ''), in_minutes: m });
      return { set: true, inMinutes: m, reminder: r, note: 'This only reminds the owner. It cannot alert anyone by itself.' };
    },
    'safety.clear_contact': async () => {
      const c = await contact();
      if (!c) return { cleared: false, note: 'No emergency contact was set.' };
      await device.call('owner.confirm', { title: 'Remove your emergency contact?', message: `${c.name}\n${c.number}`, allow: 'Remove', deny: 'Keep' });
      await store.set('contact', '');
      return { cleared: true };
    },
  },
};
