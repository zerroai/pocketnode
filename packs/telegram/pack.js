// Telegram channel pack. Talks to the Telegram Bot API only; the bot token comes from the secret store.
const API = 'https://api.telegram.org';

async function call(method, payload) {
  const token = await secret.get('bot_token');
  if (!token) throw new Error('Telegram bot token is not set');
  const r = await fetch(`${API}/bot${token}/${method}`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(payload || {}),
  });
  const j = await r.json();
  if (!j.ok) throw new Error('Telegram: ' + (j.description || r.status));
  return j.result;
}

function chatName(chat) {
  const person = [chat.first_name, chat.last_name].filter(Boolean).join(' ');
  return chat.title || person || chat.username || '';
}

pack = {
  tools: {
    // Returns new text messages without confirming them; the core confirms with channel.ack after replying.
    'channel.poll': async () => {
      const offset = Number((await store.get('offset')) || 0);
      const updates = await call('getUpdates', { offset, timeout: 25, allowed_updates: ['message'] });
      return updates
        .filter((u) => u.message && typeof u.message.text === 'string')
        .map((u) => ({ id: u.update_id, chatId: String(u.message.chat.id), name: chatName(u.message.chat), text: u.message.text }));
    },
    // Confirms the bot token works and returns the bot's username.
    'channel.check': async () => {
      const me = await call('getMe', {});
      return { username: me.username };
    },
    // Relay mode: turns one raw update from Telegram into messages, without calling Telegram.
    'channel.parse': async ({ payload }) => {
      const u = JSON.parse(payload);
      const m = u.message;
      if (!m || typeof m.text !== 'string') return [];
      return [{ id: u.update_id, chatId: String(m.chat.id), name: chatName(m.chat), text: m.text }];
    },
    // Points Telegram at the relay. Telegram then sends updates there instead of waiting to be polled.
    'channel.webhook.set': async ({ url, secret }) => {
      await call('setWebhook', { url, secret_token: secret, allowed_updates: ['message'] });
      return 'ok';
    },
    'channel.webhook.clear': async () => {
      await call('deleteWebhook', { drop_pending_updates: false });
      return 'ok';
    },
    'channel.ack': async ({ id }) => {
      await store.set('offset', String(id + 1));
      return 'ok';
    },
    'channel.send': async ({ chatId, text }) => {
      await call('sendMessage', { chat_id: chatId, text: String(text).slice(0, 4096) });
      return 'sent';
    },
  },
};
