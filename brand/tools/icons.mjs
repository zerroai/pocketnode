// The Pocketnode icon set. Drawn by hand on a 24 unit grid.
// Rules (see brand/icons/README.md): live area 20x20 (2 unit margin), 2 unit stroke, round caps and
// joins, outer corner radius 3 on boxes, inner details on half units, no fills in the line style.
// An element with the attribute `t` is the main body: the tint style fills it at 16 percent.
// Each entry: [name, group, tags, svg inner markup].

export const GROUPS = {
  nav: 'Navigation',
  action: 'Actions',
  status: 'Status and safety',
  product: 'Product',
  things: 'Things',
};

export const ICONS = [
  // Navigation
  ['chat', 'nav', 'message talk bubble', '<path t d="M7 4h10a4 4 0 0 1 4 4v5a4 4 0 0 1-4 4h-6l-4.5 3.5V17H7a4 4 0 0 1-4-4V8a4 4 0 0 1 4-4z"/>'],
  ['files', 'nav', 'folder documents', '<path t d="M3 8a3 3 0 0 1 3-3h3.5l2 2.5H18a3 3 0 0 1 3 3V16a3 3 0 0 1-3 3H6a3 3 0 0 1-3-3z"/>'],
  ['packs', 'nav', 'box cube add-on bundle', '<path t d="M4 8.5 12 4l8 4.5v7L12 20l-8-4.5z"/><path d="m4 8.5 8 4.5 8-4.5M12 13v7"/>'],
  ['agents', 'nav', 'robot helper assistant', '<rect t x="5" y="8" width="14" height="11.5" rx="4"/><path d="M12 8V5.5M9.5 13.5h.01M14.5 13.5h.01"/><circle cx="12" cy="4" r=".6"/>'],
  ['home', 'nav', 'house start', '<path t d="M4 11 12 4l8 7v7.5a1.5 1.5 0 0 1-1.5 1.5H15v-5H9v5H5.5A1.5 1.5 0 0 1 4 18.5z"/>'],
  ['back', 'nav', 'arrow left previous', '<path d="m15 5-7 7 7 7"/>'],
  ['forward', 'nav', 'chevron right next', '<path d="m9 5 7 7-7 7"/>'],
  ['down', 'nav', 'chevron expand', '<path d="m5 9 7 7 7-7"/>'],
  ['up', 'nav', 'chevron collapse', '<path d="m5 15 7-7 7 7"/>'],
  ['close', 'nav', 'x dismiss cancel', '<path d="M6 6l12 12M18 6 6 18"/>'],
  ['menu', 'nav', 'hamburger list', '<path d="M4 7h16M4 12h16M4 17h16"/>'],
  ['more', 'nav', 'dots overflow', '<path d="M5 12h.01M12 12h.01M19 12h.01"/>'],
  ['profile', 'nav', 'person account me', '<circle t cx="12" cy="8.5" r="3.5"/><path t d="M5 20a7 7 0 0 1 14 0z"/>'],
  ['settings', 'nav', 'sliders preferences options', '<path d="M4 8h8M16 8h4M4 16h4M12 16h8"/><circle t cx="14" cy="8" r="2"/><circle t cx="10" cy="16" r="2"/>'],

  // Actions
  ['send', 'action', 'paper plane submit', '<path t d="M20.5 3.5 3.5 10.5l6.5 3 3 6.5z"/><path d="M10 13.5l10.5-10"/>'],
  ['mic', 'action', 'voice record microphone', '<rect t x="9" y="3" width="6" height="11" rx="3"/><path d="M5.5 11.5a6.5 6.5 0 0 0 13 0M12 18v3"/>'],
  ['attach', 'action', 'paperclip', '<path d="m20 11.5-7.7 7.7a5 5 0 0 1-7-7l8-8a3.3 3.3 0 0 1 4.7 4.7l-8 8a1.7 1.7 0 0 1-2.4-2.4l7.3-7.3"/>'],
  ['camera', 'action', 'photo picture', '<path t d="M4 8a2 2 0 0 1 2-2h1.5l1.2-2h6.6l1.2 2H18a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2z"/><circle cx="12" cy="13" r="3.5"/>'],
  ['search', 'action', 'find magnifier', '<circle t cx="11" cy="11" r="6.5"/><path d="m16 16 4.5 4.5"/>'],
  ['plus', 'action', 'add new', '<path d="M12 5v14M5 12h14"/>'],
  ['minus', 'action', 'remove less', '<path d="M5 12h14"/>'],
  ['check', 'action', 'done tick ok', '<path d="m5 12.5 4.5 4.5L19 7.5"/>'],
  ['share', 'action', 'send out export', '<path d="M12 15V4M8 8l4-4 4 4M5 12v6a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-6"/>'],
  ['copy', 'action', 'duplicate clipboard', '<rect t x="8" y="8" width="12" height="12" rx="3"/><path d="M16 8V6a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h2"/>'],
  ['edit', 'action', 'pencil write rename', '<path t d="M4 20l1-4.5L16.5 4a2.1 2.1 0 0 1 3 3L8 18.5z"/><path d="m14.5 6 3 3"/>'],
  ['trash', 'action', 'delete bin remove', '<path t d="M7 7l.8 11.2A2 2 0 0 0 9.8 20h4.4a2 2 0 0 0 2-1.8L17 7z"/><path d="M5 7h14M10 4h4M10 11v5M14 11v5"/>'],
  ['download', 'action', 'save get', '<path d="M12 4v11M7.5 10.5 12 15l4.5-4.5M5 19h14"/>'],
  ['upload', 'action', 'put send file', '<path d="M12 15V4M7.5 8.5 12 4l4.5 4.5M5 19h14"/>'],
  ['refresh', 'action', 'reload sync again', '<path d="M19.5 12a7.5 7.5 0 1 1-2.2-5.3M17.5 3v4h-4"/>'],
  ['play', 'action', 'run start', '<path t d="M8 5.5v13l10.5-6.5z"/>'],
  ['pause', 'action', 'hold', '<path d="M8.5 5v14M15.5 5v14"/>'],
  ['stop', 'action', 'halt square', '<rect t x="6" y="6" width="12" height="12" rx="3"/>'],
  ['pin', 'action', 'keep pinned', '<path t d="M9 4h6l-1 5 3.5 3.5V14h-11v-1.5L10 9z"/><path d="M12 14v6"/>'],
  ['filter', 'action', 'sort narrow', '<path d="M4 6h16M7 12h10M10 18h4"/>'],
  ['logout', 'action', 'sign out leave', '<path d="M14 4h3.5A2.5 2.5 0 0 1 20 6.5v11a2.5 2.5 0 0 1-2.5 2.5H14M10 8l-4 4 4 4M6 12h10"/>'],

  // Status and safety
  ['alert', 'status', 'warning problem', '<circle t cx="12" cy="12" r="9"/><path d="M12 7.5v5.5M12 16.5h.01"/>'],
  ['info', 'status', 'about details', '<circle t cx="12" cy="12" r="9"/><path d="M12 11v5.5M12 7.5h.01"/>'],
  ['help', 'status', 'question support', '<circle t cx="12" cy="12" r="9"/><path d="M9.5 9.5a2.5 2.5 0 1 1 3.6 2.2c-.7.4-1.1 1-1.1 1.8M12 17h.01"/>'],
  ['lock', 'status', 'private secure', '<rect t x="5" y="10.5" width="14" height="10" rx="3"/><path d="M8 10.5V8a4 4 0 0 1 8 0v2.5M12 15v2"/>'],
  ['unlock', 'status', 'open', '<rect t x="5" y="10.5" width="14" height="10" rx="3"/><path d="M8 10.5V8a4 4 0 0 1 7.5-2M12 15v2"/>'],
  ['key', 'status', 'secret token password', '<circle t cx="8" cy="15.5" r="3.5"/><path d="m10.6 13 8.9-9M16 7.5l2.5 2.5M13.5 10l1.8 1.8"/>'],
  ['shield', 'status', 'protect safe permission', '<path t d="M12 3.5 19 6v5.5c0 4.4-3 7.6-7 9-4-1.4-7-4.6-7-9V6z"/><path d="m9 12 2.2 2.2L15.5 10"/>'],
  ['bell', 'status', 'notification alert reminder', '<path t d="M6 16v-5a6 6 0 0 1 12 0v5l1.5 2h-15z"/><path d="M10 21h4"/>'],
  ['clock', 'status', 'time schedule', '<circle t cx="12" cy="12" r="9"/><path d="M12 7v5l3.5 2"/>'],
  ['calendar', 'status', 'date day', '<rect t x="4" y="5.5" width="16" height="14.5" rx="3"/><path d="M4 10.5h16M8.5 3.5v4M15.5 3.5v4"/>'],
  ['bolt', 'status', 'power fast energy', '<path t d="M13 3 5.5 13.5H11L10 21l8.5-11H13z"/>'],
  ['battery', 'status', 'charge level', '<rect t x="3" y="8" width="16" height="9" rx="2.5"/><path d="M21.5 11v2M6.5 12.5h5"/>'],
  ['wifi', 'status', 'network connection signal', '<path d="M3.5 9.5a12 12 0 0 1 17 0M6.5 12.8a8 8 0 0 1 11 0M9.5 16a4 4 0 0 1 5 0M12 19.5h.01"/>'],
  ['sun', 'status', 'light theme day weather', '<circle t cx="12" cy="12" r="4"/><path d="M12 3v2M12 19v2M3 12h2M19 12h2M5.6 5.6 7 7M17 17l1.4 1.4M5.6 18.4 7 17M17 7l1.4-1.4"/>'],
  ['moon', 'status', 'dark theme night', '<path t d="M20 14.5A8.5 8.5 0 1 1 9.5 4a6.7 6.7 0 0 0 10.5 10.5z"/>'],
  ['eye', 'status', 'show view', '<path t d="M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12z"/><circle cx="12" cy="12" r="3"/>'],
  ['eye-off', 'status', 'hide private', '<path d="M9.2 6a9 9 0 0 1 2.8-.5C18 5.5 21.5 12 21.5 12a16 16 0 0 1-2.4 3.2M6.2 8A16 16 0 0 0 2.5 12S6 18.5 12 18.5a9 9 0 0 0 3.2-.6M4 4l16 16"/><path d="M10.2 10.2a3 3 0 0 0 4.2 4.2"/>'],
  ['activity', 'status', 'pulse log history', '<path d="M3 12h4l2.5-6 4 12 2.5-6H21"/>'],

  // Product
  ['cloud', 'product', 'brand Pocketnode sync', '<path t d="M7.5 18.5a4.5 4.5 0 0 1-.6-8.96A6 6 0 0 1 18.4 10.4 4 4 0 0 1 17.5 18.5z"/>'],
  ['phone', 'product', 'node device mobile', '<rect t x="7" y="2.5" width="10" height="19" rx="3"/><path d="M10.5 18.5h3"/>'],
  ['cluster', 'product', 'devices paired group', '<circle t cx="12" cy="6" r="2.5"/><circle t cx="6" cy="17" r="2.5"/><circle t cx="18" cy="17" r="2.5"/><path d="M10.6 8.2 7.4 14.8M13.4 8.2l3.2 6.6M8.5 17h7"/>'],
  ['link', 'product', 'pair connect chain', '<path d="M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1"/>'],
  ['server', 'product', 'host relay computer', '<rect t x="4" y="4" width="16" height="6.5" rx="2.5"/><rect t x="4" y="13.5" width="16" height="6.5" rx="2.5"/><path d="M8 7.25h.01M8 16.75h.01"/>'],
  ['spark', 'product', 'model ai magic assistant', '<path t d="M12 3c.7 4.6 2.4 6.3 7 7-4.6.7-6.3 2.4-7 7-.7-4.6-2.4-6.3-7-7 4.6-.7 6.3-2.4 7-7z"/><path d="M19 16.5v3M17.5 18h3"/>'],
  ['flow', 'product', 'canvas steps workflow', '<rect t x="3" y="4" width="7" height="6" rx="2"/><rect t x="14" y="4" width="7" height="6" rx="2"/><rect t x="14" y="14" width="7" height="6" rx="2"/><path d="M10 7h4M6.5 10v3a2 2 0 0 0 2 2H14"/>'],
  ['tasks', 'product', 'checklist todo', '<path d="M10 6.5h10M10 12h10M10 17.5h10M4 6.5l1.2 1.2L7.2 5.2M4 12l1.2 1.2 2-2.5M4 17.5l1.2 1.2 2-2.5"/>'],
  ['channel', 'product', 'broadcast announce messaging', '<path t d="M4 14v-4a1 1 0 0 1 1-1h3l8-4v14l-8-4H5a1 1 0 0 1-1-1z"/><path d="M7.5 15.5 9 20M19 9.5a3.5 3.5 0 0 1 0 5"/>'],
  ['voice', 'product', 'wave speak listen', '<path d="M4 12h.01M8 8v8M12 5v14M16 8v8M20 12h.01"/>'],
  ['volume', 'product', 'sound speaker', '<path t d="M4 10v4h3.5l4.5 4V6L7.5 10z"/><path d="M15.5 9a4 4 0 0 1 0 6M18 6.5a7.5 7.5 0 0 1 0 11"/>'],
  ['wallet', 'product', 'plan payment money', '<rect t x="3.5" y="6.5" width="17" height="13" rx="3"/><path d="M3.5 10h17M16 14.5h.01"/>'],
  ['palette', 'product', 'design appearance colors', '<path t d="M12 3.5a8.5 8.5 0 1 0 0 17c1.4 0 2-1 1.6-2.1-.5-1.2.2-2.4 1.6-2.4H17a3.5 3.5 0 0 0 3.5-3.5C20.5 6.7 16.7 3.5 12 3.5z"/><path d="M7.5 11h.01M10 7.5h.01M14.5 7.5h.01"/>'],
  ['code', 'product', 'developer script', '<path d="m8.5 7-5 5 5 5M15.5 7l5 5-5 5"/>'],

  // Things
  ['note', 'things', 'memo text page', '<path t d="M7.5 3.5h6.5l5 5V18a2.5 2.5 0 0 1-2.5 2.5h-9A2.5 2.5 0 0 1 5 18V6a2.5 2.5 0 0 1 2.5-2.5z"/><path d="M14 3.5V8a1 1 0 0 0 1 1h4M9 13h6M9 16.5h4"/>'],
  ['file', 'things', 'document page', '<path t d="M7.5 3.5h6.5l5 5V18a2.5 2.5 0 0 1-2.5 2.5h-9A2.5 2.5 0 0 1 5 18V6a2.5 2.5 0 0 1 2.5-2.5z"/><path d="M14 3.5V8a1 1 0 0 0 1 1h4"/>'],
  ['image', 'things', 'picture photo gallery', '<rect t x="3.5" y="4.5" width="17" height="15" rx="3"/><circle cx="8.5" cy="10" r="1.5"/><path d="m4 17 4.5-4.5 3.5 3.5 3-3 5 4.5"/>'],
  ['mail', 'things', 'email message', '<rect t x="3" y="5.5" width="18" height="13" rx="3"/><path d="m4 8 8 5.5L20 8"/>'],
  ['place', 'things', 'map pin location', '<path t d="M12 21s6.5-5.8 6.5-11a6.5 6.5 0 0 0-13 0c0 5.2 6.5 11 6.5 11z"/><circle cx="12" cy="10" r="2.3"/>'],
  ['globe', 'things', 'web world internet', '<circle t cx="12" cy="12" r="9"/><path d="M3 12h18M12 3c3 2.6 3 15.4 0 18M12 3c-3 2.6-3 15.4 0 18"/>'],
  ['airplane', 'things', 'flight mode travel', '<path t d="M20.5 15.5 13.5 11V5.5a1.5 1.5 0 0 0-3 0V11l-7 4.5V17l7-2v3.5l-1.5 1.2V21l3-.8 3 .8v-1.3L13.5 18.5V15l7 2z"/>'],
  ['heart', 'things', 'favorite like', '<path t d="M12 20s-7.5-4.6-7.5-10.2A4.3 4.3 0 0 1 12 7.2a4.3 4.3 0 0 1 7.5 2.6C19.5 15.4 12 20 12 20z"/>'],
  ['users', 'things', 'people group contacts', '<circle t cx="9" cy="9" r="3"/><path t d="M3.5 19.5a5.5 5.5 0 0 1 11 0z"/><path d="M16 6.3a3 3 0 0 1 0 5.4M17.5 14.2a5.5 5.5 0 0 1 3 5.3"/>'],
  ['wrench', 'things', 'tool fix', '<path t d="M14.7 5.3a4.3 4.3 0 0 0-4 5.8L4.9 17a2.1 2.1 0 0 0 3 3l5.9-5.8a4.3 4.3 0 0 0 5.8-4l-2.8 2.5-2.6-.5-.5-2.6z"/>'],
];
