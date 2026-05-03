import { Terminal } from '/vendor/xterm/lib/xterm.mjs';
import { FitAddon } from '/vendor/xterm-fit/lib/addon-fit.mjs';

const statusEl = document.querySelector('#status');
const workdirEl = document.querySelector('#workdir');
const loginForm = document.querySelector('#loginForm');
const tokenInput = document.querySelector('#token');
const accessPanel = document.querySelector('#accessPanel');
const remotePanel = document.querySelector('#remotePanel');
const terminal = document.querySelector('#terminal');
const composer = document.querySelector('#composer');
const promptInput = document.querySelector('#prompt');
const interruptButton = document.querySelector('#interruptButton');
const clearButton = document.querySelector('#clearButton');
const sessionLabel = document.querySelector('#sessionLabel');
const quickKeys = document.querySelectorAll('.key-button');
const connectButton = document.querySelector('#connectButton');
const loginError = document.querySelector('#loginError');
const endpoint = document.querySelector('#endpoint');
const workspaceName = document.querySelector('#workspaceName');
const slashPanel = document.querySelector('#slashPanel');
const slashList = document.querySelector('#slashList');
const paletteTitle = document.querySelector('#paletteTitle');
const notifyButton = document.querySelector('#notifyButton');

let socket;
let token = localStorage.getItem('codexRemoteToken') || '';
let slashCommands = [];
let mentionItems = [];
let lastActivityAt = 0;
const fitAddon = new FitAddon();
const term = new Terminal({
  cursorBlink: true,
  convertEol: true,
  fontFamily: '"SFMono-Regular", Consolas, "Liberation Mono", monospace',
  fontSize: window.matchMedia('(max-width: 46rem)').matches ? 13 : 14,
  lineHeight: 1.45,
  theme: {
    background: '#050505',
    foreground: '#f4f4f5',
    cursor: '#34d399',
    selectionBackground: '#10b98155',
    black: '#09090b',
    red: '#f87171',
    green: '#34d399',
    yellow: '#facc15',
    blue: '#93c5fd',
    magenta: '#c4b5fd',
    cyan: '#67e8f9',
    white: '#fafafa',
    brightBlack: '#71717a',
    brightRed: '#fca5a5',
    brightGreen: '#86efac',
    brightYellow: '#fde68a',
    brightBlue: '#bfdbfe',
    brightMagenta: '#ddd6fe',
    brightCyan: '#a5f3fc',
    brightWhite: '#ffffff'
  }
});

term.loadAddon(fitAddon);
term.open(terminal);
fitAddon.fit();
term.write('Waiting for secure connection...\r\n');

if (token) {
  tokenInput.value = token;
}

endpoint.textContent = location.host || 'localhost';

fetch('/api/config')
  .then((response) => response.json())
  .then((config) => {
    workdirEl.textContent = config.workspaceLabel || 'Private command center';
    workspaceName.textContent = config.workspaceLabel || 'Ready on this Mac';
  })
  .catch(() => {
    workdirEl.textContent = 'Server unavailable';
    workspaceName.textContent = 'Unavailable';
    setFormError('App-server is not responding.');
  });

if ('serviceWorker' in navigator) {
  navigator.serviceWorker.register('/sw.js').catch(() => {});
}

loginForm.addEventListener('submit', async (event) => {
  event.preventDefault();
  token = tokenInput.value.trim();
  localStorage.setItem('codexRemoteToken', token);
  await startSession();
});

composer.addEventListener('submit', (event) => {
  event.preventDefault();
  const value = promptInput.value.trim();
  if (!value || !socket || socket.readyState !== WebSocket.OPEN) return;
  sendInput(`${value}\n`);
  promptInput.value = '';
  hidePalette();
});

promptInput.addEventListener('input', updatePalette);
promptInput.addEventListener('focus', updatePalette);
promptInput.addEventListener('keydown', (event) => {
  if (event.key === 'Escape') hidePalette();
});

notifyButton.addEventListener('click', async () => {
  await requestNotificationPermission();
  updateNotifyButton();
});

interruptButton.addEventListener('click', () => {
  sendInput('\u0003');
});

clearButton.addEventListener('click', () => {
  term.clear();
  term.focus();
});

for (const button of quickKeys) {
  button.addEventListener('click', () => {
    const key = button.dataset.key;
    const values = {
      escape: '\u001b',
      tab: '\t',
      up: '\u001b[A',
      down: '\u001b[B'
    };
    sendInput(values[key] || '');
    term.focus();
  });
}

term.onData((data) => {
  if (!socket || socket.readyState !== WebSocket.OPEN) return;
  sendInput(data);
});

window.addEventListener('resize', debounce(() => {
  fitAddon.fit();
  sendResize();
}, 200));

async function startSession() {
  setFormError('');
  setConnecting(true);
  setStatus('Connecting', 'busy');
  terminal.classList.add('skeleton');
  term.reset();
  term.write('Starting Codex...\r\n');

  let response;
  try {
    response = await fetch('/api/session', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${token}`
      },
      body: JSON.stringify(getSize())
    });
  } catch {
    setStatus('Offline', 'error');
    setFormError('Cannot reach the Codex app-server.');
    setConnecting(false);
    terminal.classList.remove('skeleton');
    return;
  }

  if (!response.ok) {
    setStatus('Token rejected', 'error');
    setFormError('Token rejected. Check the server token and try again.');
    setConnecting(false);
    terminal.classList.remove('skeleton');
    return;
  }

  const { id } = await response.json();
  const protocol = location.protocol === 'https:' ? 'wss' : 'ws';
  socket = new WebSocket(`${protocol}://${location.host}/?session=${encodeURIComponent(id)}&token=${encodeURIComponent(token)}`);

  socket.addEventListener('open', () => {
    accessPanel.classList.add('hidden');
    remotePanel.classList.remove('hidden');
    sessionLabel.textContent = `Session ${id.slice(0, 8)}`;
    setStatus('Live', 'live');
    setConnecting(false);
    fitAddon.fit();
    sendResize();
    term.focus();
    loadSlashCommands();
    loadMentions();
    updateNotifyButton();
  });

  socket.addEventListener('message', (event) => {
    const message = JSON.parse(event.data);
    if (message.type === 'output') {
      terminal.classList.remove('skeleton');
      term.write(message.data);
      lastActivityAt = Date.now();
    }
    if (message.type === 'exit') {
      setStatus(`Exited ${message.exitCode}`, 'error');
      notifyDone(message.exitCode === 0 ? 'Codex task finished' : 'Codex session ended', `Exit code ${message.exitCode}`);
    }
  });

  socket.addEventListener('close', () => {
    setStatus('Disconnected', 'error');
    setConnecting(false);
  });
}

function sendInput(data) {
  socket?.send(JSON.stringify({ type: 'input', data }));
}

function sendResize() {
  if (!socket || socket.readyState !== WebSocket.OPEN) return;
  socket.send(JSON.stringify({ type: 'resize', ...getSize() }));
}

async function loadSlashCommands() {
  if (slashCommands.length > 0) return;
  try {
    const response = await fetch('/api/slash-commands', {
      headers: { Authorization: `Bearer ${token}` }
    });
    if (!response.ok) return;
    const payload = await response.json();
    slashCommands = Array.isArray(payload.commands) ? payload.commands : [];
    renderSlashCommands(slashCommands);
  } catch {
    slashCommands = [];
  }
}

async function loadMentions(query = '') {
  try {
    const params = new URLSearchParams();
    if (query) params.set('q', query);
    const response = await fetch(`/api/mentions?${params.toString()}`, {
      headers: { Authorization: `Bearer ${token}` }
    });
    if (!response.ok) return;
    const payload = await response.json();
    mentionItems = Array.isArray(payload.mentions) ? payload.mentions : [];
  } catch {
    mentionItems = [];
  }
}

function updatePalette() {
  const value = promptInput.value.trimStart();
  const trigger = getActiveTrigger(value);
  if (!trigger) {
    hidePalette();
    return;
  }

  if (trigger.type === '/') {
    loadSlashCommands();
    const matches = slashCommands.filter((command) => command.name.slice(1).toLowerCase().startsWith(trigger.query));
    paletteTitle.textContent = 'Slash commands';
    renderPalette(matches.map((command) => ({
      label: command.name,
      detail: command.description,
      insertText: `${command.name} `
    })));
    slashPanel.classList.toggle('hidden', matches.length === 0);
    return;
  }

  loadMentions(trigger.query).then(() => {
    const matches = mentionItems.filter((item) => item.path.toLowerCase().includes(trigger.query));
    paletteTitle.textContent = 'File mentions';
    renderPalette(matches.map((item) => ({
      label: item.label,
      detail: item.detail,
      insertText: `${item.label} `
    })));
    slashPanel.classList.toggle('hidden', matches.length === 0);
  });
}

function getActiveTrigger(value) {
  const match = value.match(/(?:^|\s)([/@])([^\s]*)$/);
  if (!match) return null;
  return {
    type: match[1],
    query: match[2].toLowerCase()
  };
}

function renderPalette(items) {
  slashList.replaceChildren();
  for (const item of items) {
    const button = document.createElement('button');
    button.className = 'slash-command';
    button.type = 'button';
    button.setAttribute('role', 'option');
    button.innerHTML = `<strong>${escapeHtml(item.label)}</strong><span>${escapeHtml(item.detail)}</span>`;
    button.addEventListener('click', () => {
      promptInput.value = replaceActiveToken(promptInput.value, item.insertText);
      promptInput.focus();
      hidePalette();
    });
    slashList.append(button);
  }
}

function replaceActiveToken(value, replacement) {
  return value.replace(/(?:^|\s)([/@])[^\s]*$/, (match) => {
    const prefix = match.startsWith(' ') ? ' ' : '';
    return `${prefix}${replacement}`;
  });
}

function hidePalette() {
  slashPanel.classList.add('hidden');
}

async function requestNotificationPermission() {
  if (!('Notification' in window)) return;
  if (Notification.permission === 'default') {
    await Notification.requestPermission();
  }
}

function updateNotifyButton() {
  if (!('Notification' in window)) {
    notifyButton.disabled = true;
    notifyButton.title = 'Notifications unavailable';
    return;
  }
  const enabled = Notification.permission === 'granted';
  notifyButton.classList.toggle('is-on', enabled);
  notifyButton.title = enabled ? 'Completion notifications are on' : 'Enable completion notifications';
}

function notifyDone(title, body) {
  if (!('Notification' in window) || Notification.permission !== 'granted') return;
  if (Date.now() - lastActivityAt < 1200 && document.visibilityState === 'visible') return;
  navigator.serviceWorker?.ready
    .then((registration) => registration.showNotification(title, {
      body,
      icon: '/icons/icon-192.png',
      badge: '/icons/icon.svg',
      tag: 'codex-task-done'
    }))
    .catch(() => new Notification(title, { body, icon: '/icons/icon-192.png' }));
}

function getSize() {
  fitAddon.fit();
  const dimensions = fitAddon.proposeDimensions();
  const cols = dimensions?.cols || Math.max(54, Math.floor((terminal.clientWidth || window.innerWidth) / 8.5));
  const rows = dimensions?.rows || Math.max(16, Math.floor((terminal.clientHeight || window.innerHeight * 0.58) / 18));
  return { cols, rows };
}

function setStatus(label, state = '') {
  statusEl.textContent = label;
  statusEl.classList.toggle('live', state === 'live');
  statusEl.classList.toggle('busy', state === 'busy');
  statusEl.classList.toggle('error', state === 'error');
}

function setConnecting(isConnecting) {
  connectButton.disabled = isConnecting;
  connectButton.textContent = isConnecting ? 'Unlocking' : 'Unlock';
}

function setFormError(message) {
  loginError.textContent = message;
  tokenInput.setAttribute('aria-invalid', message ? 'true' : 'false');
}

function debounce(fn, delay) {
  let timer;
  return (...args) => {
    clearTimeout(timer);
    timer = setTimeout(() => fn(...args), delay);
  };
}

function escapeHtml(value = '') {
  return value.replace(/[&<>"']/g, (char) => ({
    '&': '&amp;',
    '<': '&lt;',
    '>': '&gt;',
    '"': '&quot;',
    "'": '&#39;'
  })[char]);
}
