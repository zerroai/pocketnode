// Phone controls: thin wrappers over the phone bridge. Each tool names the one phone operation it uses, and
// the manifest lists them all, so the permission screen shows exactly what this pack can do.
const s = (v) => (v == null ? undefined : String(v));
const need = (v, what) => { if (v == null || String(v).trim() === '') throw new Error(`Say ${what}`); return String(v); };
const num = (v, what) => { const n = Number(v); if (v == null || v === '' || !isFinite(n)) throw new Error(`Say ${what}`); return n; };

pack = {
  tools: {
    'phone.status': async () => device.call('status', {}),
    'apps.list': async ({ query, limit } = {}) => device.call('apps.list', { query: s(query), limit: limit == null ? undefined : num(limit, 'how many') }),
    'apps.open': async ({ name }) => device.call('apps.open', { name: need(name, 'which app') }),
    'phone.flashlight': async ({ on } = {}) => device.call('flashlight', { on: on == null ? true : on === true || on === 'true' }),
    'phone.media': async ({ action } = {}) => device.call('media.control', { action: need(action, 'the action') }),
    'phone.alarm': async ({ hour, minute, label }) => device.call('alarm.set', { hour: num(hour, 'the hour'), minute: minute == null ? 0 : num(minute, 'the minute'), label: s(label) }),
    'phone.timer': async ({ seconds, label }) => device.call('timer.set', { seconds: num(seconds, 'how many seconds'), label: s(label) }),
    'phone.call': async ({ number }) => device.call('dial', { number: need(number, 'the number') }),
    'phone.text': async ({ number, text }) => device.call('sms.compose', { number: need(number, 'the number'), text: s(text) || '' }),
    'phone.email': async ({ to, subject, body }) => device.call('email.compose', { to: s(to) || '', subject: s(subject) || '', body: s(body) || '' }),
    'phone.share': async ({ text, title }) => device.call('share.text', { text: need(text, 'what to share'), title: s(title) }),
    'phone.open_link': async ({ url }) => device.call('url.open', { url: need(url, 'the web address') }),
    'phone.map': async ({ query, directions }) => device.call('map.open', { query: need(query, 'the place'), mode: directions ? 'directions' : '' }),
    'phone.notify': async ({ title, text }) => device.call('notify', { title: s(title), text: need(text, 'what the notification says') }),
    'phone.speak': async ({ text }) => device.call('speak', { text: need(text, 'what to say') }),
    'phone.copy': async ({ text }) => device.call('clipboard.set', { text: need(text, 'what to copy') }),
  },
};
