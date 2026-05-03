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

let socket;
let token = localStorage.getItem('codexRemoteToken') || '';
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
  });

  socket.addEventListener('message', (event) => {
    const message = JSON.parse(event.data);
    if (message.type === 'output') {
      terminal.classList.remove('skeleton');
      term.write(message.data);
    }
    if (message.type === 'exit') {
      setStatus(`Exited ${message.exitCode}`, 'error');
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
