// Reminders: thin wrappers over the phone's reminder scheduler. The phone keeps and fires the reminders, so they still go off
// when the app is closed or the phone restarts.
const WHEN = /^\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}$/;
const CLOCK = /^([01]?\d|2[0-3]):[0-5]\d$/;
const REPEATS = ['daily', 'weekly', 'weekdays'];

pack = {
  tools: {
    'reminder.add': async ({ text, at, in_minutes, time, repeat } = {}) => {
      if (text == null || String(text).trim() === '') throw new Error('Say what to remind about');
      const hasAt = at != null && String(at).trim() !== '';
      const hasIn = in_minutes != null && in_minutes !== '';
      const hasTime = time != null && String(time).trim() !== '';
      if (hasAt + hasIn + hasTime !== 1) throw new Error('Give exactly one of at (like 2026-10-20T15:00), in_minutes, or time (like 09:00, with repeat)');
      if (hasTime && !CLOCK.test(String(time).trim())) throw new Error('time looks like 09:00');
      if (hasTime && (repeat == null || repeat === '')) throw new Error('time is for repeating reminders; say repeat as well, or use at');
      if (hasAt && !WHEN.test(String(at).trim())) throw new Error('at looks like 2026-10-20T15:00');
      let minutes;
      if (hasIn) {
        minutes = Math.round(Number(in_minutes));
        if (!isFinite(minutes) || minutes < 1 || minutes > 525600) throw new Error('in_minutes is 1 to 525600');
      }
      const again = repeat == null || repeat === '' ? undefined : String(repeat);
      if (again !== undefined && !REPEATS.includes(again)) throw new Error('repeat is daily, weekly or weekdays');
      return device.call('reminder.add', { text: String(text), at: hasAt ? String(at).trim().replace(' ', 'T') : undefined, in_minutes: minutes, time: hasTime ? String(time).trim() : undefined, repeat: again });
    },
    'reminder.list': async () => device.call('reminder.list', {}),
    'reminder.cancel': async ({ id } = {}) => {
      const n = Number(id);
      if (id == null || !isFinite(n)) throw new Error('Say which reminder, by its id');
      const list = await device.call('reminder.list', {});
      const mine = ((list && list.reminders) || []).find((r) => r.id === Math.round(n));
      await device.call('owner.confirm', { title: 'Cancel this reminder?', message: mine ? String(mine.text).slice(0, 200) + (mine.when ? '\n' + mine.when : '') : 'Reminder ' + Math.round(n), allow: 'Cancel it', deny: 'Keep it' });
      return device.call('reminder.cancel', { id: Math.round(n) });
    },
  },
};
