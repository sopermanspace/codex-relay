import 'dotenv/config';

import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';

import express from 'express';
import { customAlphabet } from 'nanoid';
import pty from '@homebridge/node-pty-prebuilt-multiarch';
import qrcode from 'qrcode-terminal';
import { WebSocketServer } from 'ws';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const rootDir = path.resolve(__dirname, '..');
const publicDir = path.join(rootDir, 'public');

const host = process.env.HOST || '0.0.0.0';
const port = Number(process.env.PORT || 8787);
const codexCommand = process.env.CODEX_COMMAND || 'codex';
const codexWorkdir = process.env.CODEX_WORKDIR || os.homedir();
const makeToken = customAlphabet('123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz', 32);
const remoteToken = process.env.REMOTE_TOKEN || makeToken();

const app = express();
const server = http.createServer(app);
const wss = new WebSocketServer({ noServer: true });

const sessions = new Map();

app.disable('x-powered-by');
app.use((req, res, next) => {
  res.setHeader('Cross-Origin-Opener-Policy', 'same-origin');
  res.setHeader('Referrer-Policy', 'no-referrer');
  res.setHeader('X-Content-Type-Options', 'nosniff');
  res.setHeader('Permissions-Policy', 'camera=(), microphone=(), geolocation=()');
  next();
});
app.use(express.json());
app.use('/vendor/xterm', express.static(path.join(rootDir, 'node_modules/@xterm/xterm'), { maxAge: '1h' }));
app.use('/vendor/xterm-fit', express.static(path.join(rootDir, 'node_modules/@xterm/addon-fit'), { maxAge: '1h' }));
app.use(express.static(publicDir, {
  extensions: ['html'],
  maxAge: process.env.NODE_ENV === 'production' ? '1h' : 0
}));

app.get('/health', (req, res) => {
  res.json({ ok: true, sessions: sessions.size });
});

app.get('/api/config', (req, res) => {
  res.json({
    appName: 'Codex Remote',
    workdir: codexWorkdir,
    tokenRequired: true
  });
});

app.get('/api/auth', (req, res) => {
  const token = getBearerToken(req);
  if (!isAuthorized(token)) {
    res.status(401).json({ error: 'Unauthorized' });
    return;
  }

  res.json({
    ok: true,
    appName: 'Codex Remote',
    workdir: codexWorkdir
  });
});

app.post('/api/session', (req, res) => {
  const token = getBearerToken(req);
  if (!isAuthorized(token)) {
    res.status(401).json({ error: 'Unauthorized' });
    return;
  }

  const id = makeToken();
  const shell = pty.spawn(codexCommand, [], {
    name: 'xterm-256color',
    cols: Number(req.body?.cols || 90),
    rows: Number(req.body?.rows || 28),
    cwd: codexWorkdir,
    env: {
      ...process.env,
      TERM: 'xterm-256color',
      COLORTERM: 'truecolor'
    }
  });

  const session = {
    id,
    shell,
    clients: new Set(),
    history: [],
    createdAt: Date.now()
  };

  shell.onData((data) => {
    session.history.push(data);
    if (session.history.length > 500) {
      session.history.splice(0, session.history.length - 500);
    }
    for (const client of session.clients) {
      if (client.readyState === client.OPEN) {
        client.send(JSON.stringify({ type: 'output', data }));
      }
    }
  });

  shell.onExit(({ exitCode }) => {
    for (const client of session.clients) {
      if (client.readyState === client.OPEN) {
        client.send(JSON.stringify({ type: 'exit', exitCode }));
      }
    }
    sessions.delete(id);
  });

  sessions.set(id, session);
  res.json({ id });
});

app.post('/api/command', async (req, res) => {
  const token = getBearerToken(req);
  if (!isAuthorized(token)) {
    res.status(401).json({ error: 'Unauthorized' });
    return;
  }

  const prompt = typeof req.body?.prompt === 'string' ? req.body.prompt.trim() : '';
  if (!prompt) {
    res.status(400).json({ error: 'Prompt is required' });
    return;
  }

  const startedAt = Date.now();
  try {
    const result = await runCodexExec(prompt);
    res.json({
      ok: result.exitCode === 0,
      exitCode: result.exitCode,
      output: result.output,
      durationMs: Date.now() - startedAt
    });
  } catch (error) {
    res.status(500).json({
      ok: false,
      error: error.message,
      durationMs: Date.now() - startedAt
    });
  }
});

server.on('upgrade', (req, socket, head) => {
  const url = new URL(req.url || '/', `http://${req.headers.host}`);
  const token = url.searchParams.get('token');
  const id = url.searchParams.get('session');

  if (!isAuthorized(token) || !id || !sessions.has(id)) {
    socket.write('HTTP/1.1 401 Unauthorized\r\n\r\n');
    socket.destroy();
    return;
  }

  wss.handleUpgrade(req, socket, head, (ws) => {
    wss.emit('connection', ws, req, id);
  });
});

wss.on('connection', (ws, req, id) => {
  const session = sessions.get(id);
  if (!session) {
    ws.close();
    return;
  }

  session.clients.add(ws);
  ws.send(JSON.stringify({ type: 'ready', session: id }));
  for (const item of session.history) {
    ws.send(JSON.stringify({ type: 'output', data: item }));
  }

  ws.on('message', (raw) => {
    let event;
    try {
      event = JSON.parse(raw.toString());
    } catch {
      return;
    }

    if (event.type === 'input' && typeof event.data === 'string') {
      session.shell.write(event.data);
    }

    if (event.type === 'resize') {
      const cols = clamp(Number(event.cols), 40, 180);
      const rows = clamp(Number(event.rows), 12, 60);
      session.shell.resize(cols, rows);
    }
  });

  ws.on('close', () => {
    session.clients.delete(ws);
  });
});

server.listen(port, host, () => {
  const localUrl = `http://localhost:${port}`;
  const networkUrl = getNetworkUrl(port);
  const publicUrl = process.env.PUBLIC_URL;

  console.log(`Codex Remote server listening on ${localUrl}`);
  console.log(`Workdir: ${codexWorkdir}`);
  console.log(`Remote token: ${remoteToken}`);

  if (networkUrl) {
    console.log(`LAN URL: ${networkUrl}`);
  }

  if (publicUrl) {
    console.log(`Public URL: ${publicUrl}`);
    qrcode.generate(publicUrl, { small: true });
  } else if (networkUrl) {
    qrcode.generate(networkUrl, { small: true });
  }
});

function getBearerToken(req) {
  const auth = req.headers.authorization || '';
  return auth.startsWith('Bearer ') ? auth.slice(7) : '';
}

function isAuthorized(token) {
  return typeof token === 'string' && token.length > 0 && token === remoteToken;
}

function clamp(value, min, max) {
  if (!Number.isFinite(value)) return min;
  return Math.max(min, Math.min(max, value));
}

function getNetworkUrl(selectedPort) {
  for (const addresses of Object.values(os.networkInterfaces())) {
    for (const address of addresses || []) {
      if (address.family === 'IPv4' && !address.internal) {
        return `http://${address.address}:${selectedPort}`;
      }
    }
  }
  return null;
}

function runCodexExec(prompt) {
  const args = [
    '-a',
    process.env.CODEX_APPROVAL_POLICY || 'never',
    '--sandbox',
    process.env.CODEX_SANDBOX || 'workspace-write',
    '-C',
    codexWorkdir,
    'exec',
    '--color',
    'never',
    '--skip-git-repo-check',
    prompt
  ];

  return new Promise((resolve, reject) => {
    const child = spawn(codexCommand, args, {
      cwd: codexWorkdir,
      env: {
        ...process.env,
        TERM: 'dumb',
        NO_COLOR: '1'
      }
    });

    let output = '';
    let settled = false;
    const timeout = setTimeout(() => {
      settled = true;
      child.kill('SIGTERM');
      reject(new Error('Codex command timed out.'));
    }, Number(process.env.CODEX_COMMAND_TIMEOUT_MS || 600000));

    child.stdout.on('data', (chunk) => {
      output += chunk.toString();
    });

    child.stderr.on('data', (chunk) => {
      output += chunk.toString();
    });

    child.on('error', (error) => {
      if (settled) return;
      settled = true;
      clearTimeout(timeout);
      reject(error);
    });

    child.on('close', (exitCode) => {
      if (settled) return;
      settled = true;
      clearTimeout(timeout);
      resolve({
        exitCode,
        output: output.trim()
      });
    });
  });
}
