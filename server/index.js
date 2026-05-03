import 'dotenv/config';

import http from 'node:http';
import crypto from 'node:crypto';
import fs from 'node:fs/promises';
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
const projectRoots = getProjectRoots();
const ignoredProjectDirs = new Set([
  '.git',
  '.gradle',
  '.next',
  '.vercel',
  'artifacts',
  'build',
  'dist',
  'node_modules',
  'screenshots'
]);
const ignoredMentionExtensions = new Set([
  '.7z',
  '.apk',
  '.bin',
  '.class',
  '.dmg',
  '.exe',
  '.gif',
  '.gz',
  '.ico',
  '.jar',
  '.jpeg',
  '.jpg',
  '.lock',
  '.mov',
  '.mp4',
  '.pdf',
  '.png',
  '.webp',
  '.zip'
]);
const makeToken = customAlphabet('123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz', 32);
const remoteToken = process.env.REMOTE_TOKEN || makeToken();
assertSafeRemoteToken(remoteToken, Boolean(process.env.REMOTE_TOKEN));
const trustedProxy = process.env.TRUST_PROXY === 'true';
const allowedRemoteOrigins = parseCsv(process.env.REMOTE_ALLOWED_ORIGINS);
const maxFailedAuth = Number(process.env.REMOTE_MAX_FAILED_AUTH || 12);
const failedAuthWindowMs = Number(process.env.REMOTE_AUTH_WINDOW_MS || 10 * 60 * 1000);
const sessionTtlMs = Number(process.env.CODEX_SESSION_TTL_MS || 30 * 60 * 1000);
const failedAuth = new Map();

const app = express();
const server = http.createServer(app);
const wss = new WebSocketServer({ noServer: true });

const sessions = new Map();
const slashCommands = [
  { name: '/help', description: 'Show Codex slash commands and shortcuts.' },
  { name: '/model', description: 'Switch the active model.' },
  { name: '/approvals', description: 'Change approval behavior for commands and edits.' },
  { name: '/status', description: 'Show session, model, workspace, and account status.' },
  { name: '/mcp', description: 'Inspect configured MCP servers and tools.' },
  { name: '/diff', description: 'Review the current code changes.' },
  { name: '/compact', description: 'Summarize and compact the conversation context.' },
  { name: '/new', description: 'Start a fresh Codex thread.' },
  { name: '/init', description: 'Create or refresh project instructions.' },
  { name: '/review', description: 'Run a focused code review on current changes.' },
  { name: '/quit', description: 'Exit the interactive Codex session.' },
  { name: '/exit', description: 'Exit the interactive Codex session.' }
];

app.disable('x-powered-by');
if (trustedProxy) {
  app.set('trust proxy', true);
}
app.use((req, res, next) => {
  res.setHeader('Cross-Origin-Opener-Policy', 'same-origin');
  res.setHeader('Referrer-Policy', 'no-referrer');
  res.setHeader('X-Content-Type-Options', 'nosniff');
  res.setHeader('X-Frame-Options', 'DENY');
  res.setHeader('Cache-Control', 'no-store');
  res.setHeader('Permissions-Policy', 'camera=(), microphone=(), geolocation=()');
  if (isSecureRequest(req)) {
    res.setHeader('Strict-Transport-Security', 'max-age=31536000; includeSubDomains');
  }
  next();
});
app.use(express.json({ limit: '64kb' }));
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
    appName: 'Codex Relay',
    workspaceLabel: 'Ready on this Mac',
    tokenRequired: true
  });
});

app.get('/api/slash-commands', (req, res) => {
  const access = authorizeRequest(req);
  if (!access.ok) {
    res.status(access.status).json({ error: access.error });
    return;
  }

  res.json({
    ok: true,
    commands: slashCommands
  });
});

app.get('/api/mentions', async (req, res) => {
  const access = authorizeRequest(req);
  if (!access.ok) {
    res.status(access.status).json({ error: access.error });
    return;
  }

  const cwd = resolveRequestedCwd(req.query.cwd);
  if (!cwd) {
    res.status(400).json({ error: 'Project folder is outside the allowed roots.' });
    return;
  }

  try {
    const query = typeof req.query.q === 'string' ? req.query.q.trim() : '';
    const mentions = await listMentions(cwd, query);
    res.json({ ok: true, cwd, mentions });
  } catch (error) {
    res.status(500).json({ ok: false, error: error.message });
  }
});

app.get('/api/auth', (req, res) => {
  const access = authorizeRequest(req);
  if (!access.ok) {
    res.status(access.status).json({ error: access.error });
    return;
  }

  res.json({
    ok: true,
    appName: 'Codex Relay'
  });
});

app.get('/api/projects', async (req, res) => {
  const access = authorizeRequest(req);
  if (!access.ok) {
    res.status(access.status).json({ error: access.error });
    return;
  }

  try {
    const projects = await listProjects();
    res.json({
      ok: true,
      workdir: codexWorkdir,
      roots: projectRoots,
      projects
    });
  } catch (error) {
    res.status(500).json({ ok: false, error: error.message });
  }
});

app.post('/api/projects', async (req, res) => {
  const access = authorizeRequest(req);
  if (!access.ok) {
    res.status(access.status).json({ error: access.error });
    return;
  }

  const name = typeof req.body?.name === 'string' ? req.body.name.trim() : '';
  const brief = typeof req.body?.brief === 'string' ? req.body.brief.trim() : '';
  const safeName = sanitizeProjectName(name);
  if (!safeName) {
    res.status(400).json({ error: 'Project name can use letters, numbers, spaces, dashes, underscores, and dots.' });
    return;
  }

  const root = projectRoots[0] || path.join(os.homedir(), 'Documents');
  const projectPath = path.join(root, safeName);
  if (!isAllowedProjectPath(projectPath)) {
    res.status(400).json({ error: 'Project folder is outside the allowed roots.' });
    return;
  }

  try {
    await fs.mkdir(projectPath, { recursive: false });
    await fs.writeFile(
      path.join(projectPath, 'README.md'),
      `# ${safeName}\n\n${brief || 'Created from Codex Relay mobile project setup.'}\n`,
      'utf8'
    );
    await fs.writeFile(
      path.join(projectPath, 'AGENTS.md'),
      [
        '# AGENTS.md instructions',
        '',
        'Treat this folder as the active Codex project.',
        'Before editing, inspect the repository structure and preserve user changes.',
        'Prefer small, verifiable steps and explain important results clearly.',
        ''
      ].join('\n'),
      'utf8'
    );

    res.status(201).json({
      ok: true,
      project: await projectMeta(projectPath)
    });
  } catch (error) {
    if (error.code === 'EEXIST') {
      res.status(409).json({ ok: false, error: 'A project with that name already exists.' });
      return;
    }
    res.status(500).json({ ok: false, error: error.message });
  }
});

app.post('/api/session', (req, res) => {
  const access = authorizeRequest(req);
  if (!access.ok) {
    res.status(access.status).json({ error: access.error });
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
  const access = authorizeRequest(req);
  if (!access.ok) {
    res.status(access.status).json({ error: access.error });
    return;
  }

  const prompt = typeof req.body?.prompt === 'string' ? req.body.prompt.trim() : '';
  const cwd = resolveRequestedCwd(req.body?.cwd);
  if (!prompt) {
    res.status(400).json({ error: 'Prompt is required' });
    return;
  }
  if (!cwd) {
    res.status(400).json({ error: 'Project folder is outside the allowed roots.' });
    return;
  }

  const startedAt = Date.now();
  try {
    const slashCommand = resolveSlashCommand(prompt);
    if (slashCommand) {
      const result = await runSlashCommand(slashCommand, cwd);
      res.json({
        ok: result.exitCode === 0,
        exitCode: result.exitCode,
        output: result.output,
        cwd,
        durationMs: Date.now() - startedAt
      });
      return;
    }

    const result = await runCodexExec(prompt, cwd);
    res.json({
      ok: result.exitCode === 0,
      exitCode: result.exitCode,
      output: result.output,
      cwd,
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
  const mode = url.searchParams.get('mode') || req.headers['x-codex-access-mode'];

  const access = authorizeRequest(req, { token, mode });
  const session = sessions.get(id);
  if (!access.ok || !id || !session || isSessionExpired(session)) {
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
  if (!session || isSessionExpired(session)) {
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

  console.log(`Codex Relay server listening on ${localUrl}`);
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

setInterval(cleanupSecurityState, 60_000).unref();

function getBearerToken(req) {
  const auth = req.headers.authorization || '';
  return auth.startsWith('Bearer ') ? auth.slice(7) : '';
}

function isAuthorized(token) {
  if (typeof token !== 'string' || token.length === 0) return false;
  const expected = Buffer.from(remoteToken);
  const received = Buffer.from(token);
  if (expected.length !== received.length) return false;
  return crypto.timingSafeEqual(expected, received);
}

function assertSafeRemoteToken(value, isPersistent) {
  const weakValues = new Set([
    'change-me',
    'change-me-to-a-long-random-token',
    'password',
    'token',
    'secret'
  ]);
  if (typeof value !== 'string' || value.length < 32 || weakValues.has(value.trim().toLowerCase())) {
    console.error('REMOTE_TOKEN must be a private random value with at least 32 characters.');
    console.error('Run: npm run setup');
    process.exit(1);
  }

  if (!isPersistent) {
    console.warn('REMOTE_TOKEN is temporary for this run. Run `npm run setup` to create a persistent private token.');
  }
}

function authorizeRequest(req, override = {}) {
  const ip = getClientIp(req);
  if (isRateLimited(ip)) {
    return { ok: false, status: 429, error: 'Too many attempts. Try again later.' };
  }

  const token = override.token ?? getBearerToken(req);
  if (!isAuthorized(token)) {
    noteFailedAuth(ip);
    return { ok: false, status: 401, error: 'Unauthorized' };
  }

  const mode = normalizeAccessMode(override.mode ?? req.headers['x-codex-access-mode']);
  if (mode === 'local' && !isPrivateNetworkIp(ip)) {
    return { ok: false, status: 403, error: 'Connection blocked.' };
  }

  if (mode === 'remote' || (mode === 'auto' && !isPrivateNetworkIp(ip))) {
    if (!isSecureRequest(req)) {
      return { ok: false, status: 403, error: 'Connection blocked.' };
    }

    const origin = req.headers.origin;
    if (allowedRemoteOrigins.length > 0 && origin && !allowedRemoteOrigins.includes(origin)) {
      return { ok: false, status: 403, error: 'Connection blocked.' };
    }
  }

  clearFailedAuth(ip);
  return { ok: true, mode, ip };
}

function normalizeAccessMode(value) {
  if (value === 'remote' || value === 'secure') return 'remote';
  if (value === 'local' || value === 'local_only') return 'local';
  return 'auto';
}

function isSecureRequest(req) {
  if (req.socket.encrypted || req.secure) return true;
  if (!trustedProxy) return false;
  const proto = String(req.headers['x-forwarded-proto'] || '').split(',')[0].trim().toLowerCase();
  return proto === 'https';
}

function getClientIp(req) {
  const forwarded = trustedProxy ? String(req.headers['x-forwarded-for'] || '').split(',')[0].trim() : '';
  return normalizeIp(forwarded || req.socket.remoteAddress || req.ip || '');
}

function normalizeIp(value) {
  if (!value) return '';
  if (value.startsWith('::ffff:')) return value.slice(7);
  if (value === '::1') return '127.0.0.1';
  return value;
}

function isPrivateNetworkIp(ip) {
  if (ip === '127.0.0.1' || ip === 'localhost') return true;
  if (ip.startsWith('10.')) return true;
  if (ip.startsWith('192.168.')) return true;
  if (ip.startsWith('172.')) {
    const second = Number(ip.split('.')[1]);
    return second >= 16 && second <= 31;
  }
  if (ip.startsWith('169.254.')) return true;
  if (ip === '::1' || ip.toLowerCase().startsWith('fc') || ip.toLowerCase().startsWith('fd') || ip.toLowerCase().startsWith('fe80:')) return true;
  return false;
}

function noteFailedAuth(ip) {
  const now = Date.now();
  const current = failedAuth.get(ip) || { count: 0, firstAt: now };
  if (now - current.firstAt > failedAuthWindowMs) {
    failedAuth.set(ip, { count: 1, firstAt: now });
    return;
  }
  current.count += 1;
  failedAuth.set(ip, current);
}

function clearFailedAuth(ip) {
  failedAuth.delete(ip);
}

function isRateLimited(ip) {
  const current = failedAuth.get(ip);
  if (!current) return false;
  const now = Date.now();
  if (now - current.firstAt > failedAuthWindowMs) {
    failedAuth.delete(ip);
    return false;
  }
  return current.count >= maxFailedAuth;
}

function isSessionExpired(session) {
  return Date.now() - session.createdAt > sessionTtlMs;
}

function cleanupSecurityState() {
  const now = Date.now();
  for (const [ip, item] of failedAuth) {
    if (now - item.firstAt > failedAuthWindowMs) failedAuth.delete(ip);
  }

  for (const [id, session] of sessions) {
    if (!isSessionExpired(session)) continue;
    session.shell.kill();
    for (const client of session.clients) {
      client.close();
    }
    sessions.delete(id);
  }
}

function parseCsv(value = '') {
  return value
    .split(',')
    .map((item) => item.trim())
    .filter(Boolean);
}

function sanitizeProjectName(name) {
  if (!name || name.length > 80) return '';
  if (name.includes('/') || name.includes('\\') || name === '.' || name === '..') return '';
  if (!/^[\w .-]+$/u.test(name)) return '';
  return name.replace(/\s+/g, ' ').trim();
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

function getProjectRoots() {
  const configured = process.env.CODEX_PROJECT_ROOTS
    ? process.env.CODEX_PROJECT_ROOTS.split(path.delimiter)
    : [
        path.dirname(codexWorkdir),
        path.join(os.homedir(), 'Documents'),
        path.join(os.homedir(), 'Desktop')
      ];

  return Array.from(new Set(
    configured
      .map((item) => path.resolve(item.trim()))
      .filter(Boolean)
  ));
}

async function listProjects() {
  const entries = [];
  const seen = new Set();

  for (const root of projectRoots) {
    let children;
    try {
      children = await fs.readdir(root, { withFileTypes: true });
    } catch {
      continue;
    }

    for (const child of children) {
      if (!child.isDirectory() || child.name.startsWith('.') || ignoredProjectDirs.has(child.name)) continue;
      const fullPath = path.join(root, child.name);
      if (seen.has(fullPath)) continue;
      seen.add(fullPath);
      const meta = await projectMeta(fullPath);
      entries.push(meta);
    }
  }

  entries.sort((a, b) => b.updatedAt - a.updatedAt || a.name.localeCompare(b.name));
  return entries.slice(0, Number(process.env.CODEX_MAX_PROJECTS || 80));
}

async function projectMeta(projectPath) {
  const stat = await fs.stat(projectPath);
  const markers = await Promise.all([
    exists(path.join(projectPath, '.git')),
    exists(path.join(projectPath, 'package.json')),
    exists(path.join(projectPath, 'pyproject.toml')),
    exists(path.join(projectPath, 'README.md')),
    exists(path.join(projectPath, 'AGENTS.md'))
  ]);

  const tags = [];
  if (markers[0]) tags.push('Git');
  if (markers[1]) tags.push('Node');
  if (markers[2]) tags.push('Python');
  if (markers[3]) tags.push('Docs');
  if (markers[4]) tags.push('Agents');

  return {
    name: path.basename(projectPath),
    path: projectPath,
    parent: path.dirname(projectPath),
    tags,
    updatedAt: stat.mtimeMs
  };
}

async function listMentions(cwd, query = '') {
  const [plugins, files] = await Promise.all([
    listPluginMentions(query),
    listFileMentions(cwd, query)
  ]);
  return [...plugins, ...files];
}

async function listPluginMentions(query = '') {
  const normalizedQuery = query.toLowerCase();
  const roots = [
    path.join(os.homedir(), '.codex', 'plugins', 'cache', 'openai-curated'),
    path.join(os.homedir(), '.codex', 'plugins', 'cache', 'openai-primary-runtime'),
    path.join(os.homedir(), '.codex', 'plugins', 'cache', 'openai-bundled')
  ];
  const mentions = [];
  const seen = new Set();

  for (const root of roots) {
    let entries;
    try {
      entries = await fs.readdir(root, { withFileTypes: true });
    } catch {
      continue;
    }

    for (const entry of entries) {
      if (!entry.isDirectory() || entry.name.startsWith('.')) continue;
      const label = `@${entry.name}`;
      if (seen.has(label)) continue;
      if (normalizedQuery && !entry.name.toLowerCase().includes(normalizedQuery)) continue;
      seen.add(label);
      mentions.push({
        label,
        path: entry.name,
        detail: pluginSourceLabel(root),
        type: 'plugin'
      });
    }
  }

  mentions.sort((a, b) => a.label.localeCompare(b.label));
  return mentions;
}

function pluginSourceLabel(root) {
  if (root.includes('openai-curated')) return 'Codex plugin';
  if (root.includes('openai-primary-runtime')) return 'Runtime plugin';
  if (root.includes('openai-bundled')) return 'Bundled plugin';
  return 'Plugin';
}

async function listFileMentions(cwd, query = '') {
  const root = path.resolve(cwd);
  const normalizedQuery = query.toLowerCase();
  const results = [];
  const maxResults = Number(process.env.CODEX_MAX_MENTIONS || 80);
  const maxVisited = Number(process.env.CODEX_MAX_MENTION_SCAN || 1800);
  let visited = 0;

  async function walk(current) {
    if (results.length >= maxResults || visited >= maxVisited) return;
    let entries;
    try {
      entries = await fs.readdir(current, { withFileTypes: true });
    } catch {
      return;
    }

    entries.sort((a, b) => {
      if (a.isDirectory() !== b.isDirectory()) return a.isDirectory() ? -1 : 1;
      return a.name.localeCompare(b.name);
    });

    for (const entry of entries) {
      if (results.length >= maxResults || visited >= maxVisited) return;
      if (entry.name.startsWith('.') || ignoredProjectDirs.has(entry.name)) continue;
      const fullPath = path.join(current, entry.name);
      const relativePath = path.relative(root, fullPath);
      if (entry.isDirectory()) {
        await walk(fullPath);
        continue;
      }
      if (!entry.isFile()) continue;
      visited += 1;
      if (ignoredMentionExtensions.has(path.extname(entry.name).toLowerCase())) continue;
      if (normalizedQuery && !relativePath.toLowerCase().includes(normalizedQuery)) continue;
      results.push({
        label: `@${relativePath}`,
        path: relativePath,
        detail: path.dirname(relativePath) === '.' ? 'Project file' : path.dirname(relativePath),
        type: 'file'
      });
    }
  }

  await walk(root);
  return results;
}

async function exists(targetPath) {
  try {
    await fs.access(targetPath);
    return true;
  } catch {
    return false;
  }
}

function resolveRequestedCwd(value) {
  if (typeof value !== 'string' || value.trim() === '') {
    return path.resolve(codexWorkdir);
  }

  const resolved = path.resolve(value);
  if (!isAllowedProjectPath(resolved)) return null;
  return resolved;
}

function isAllowedProjectPath(candidate) {
  return projectRoots.some((root) => {
    const relative = path.relative(root, candidate);
    return relative === '' || (!relative.startsWith('..') && !path.isAbsolute(relative));
  });
}

function runCodexExec(prompt, cwd = codexWorkdir) {
  return (async () => {
    const outputDir = await fs.mkdtemp(path.join(os.tmpdir(), 'codex-relay-'));
    const outputFile = path.join(outputDir, 'last-message.txt');
    const args = [
      'exec',
      '--sandbox',
      process.env.CODEX_SANDBOX || 'workspace-write',
      '-C',
      cwd,
      '--color',
      'never',
      '--skip-git-repo-check',
      '--output-last-message',
      outputFile,
      prompt
    ];

    return new Promise((resolve, reject) => {
    const child = spawn(codexCommand, args, {
      cwd,
      env: {
        ...process.env,
        TERM: 'dumb',
        NO_COLOR: '1'
      },
      stdio: ['ignore', 'pipe', 'pipe']
    });

    let output = '';
    let stderr = '';
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
      stderr += chunk.toString();
    });

    child.on('error', (error) => {
      if (settled) return;
      settled = true;
      clearTimeout(timeout);
      reject(error);
    });

    child.on('close', async (exitCode) => {
      if (settled) return;
      settled = true;
      clearTimeout(timeout);
      let finalOutput = output.trim();
      try {
        const lastMessage = await fs.readFile(outputFile, 'utf8');
        if (lastMessage.trim()) finalOutput = lastMessage.trim();
      } catch {
        if (!finalOutput && stderr.trim()) finalOutput = stderr.trim();
      }
      fs.rm(outputDir, { recursive: true, force: true }).catch(() => {});
      resolve({
        exitCode,
        output: finalOutput
      });
    });
    });
  })();
}

function resolveSlashCommand(prompt) {
  const [rawName, ...rest] = prompt.trim().split(/\s+/);
  if (!rawName?.startsWith('/')) return null;
  const command = slashCommands.find((item) => item.name === rawName.toLowerCase());
  if (!command) return null;
  return {
    name: command.name,
    args: rest.join(' ')
  };
}

async function runSlashCommand(command, cwd) {
  if (command.name === '/help') {
    return {
      exitCode: 0,
      output: slashCommands.map((item) => `${item.name.padEnd(11)} ${item.description}`).join('\n')
    };
  }

  if (command.name === '/review') {
    return runCodexExec('Review the current code changes and provide prioritized, actionable findings.', cwd);
  }

  if (command.name === '/diff') {
    return runCodexExec('Inspect the current repository diff and summarize the meaningful changes.', cwd);
  }

  if (command.name === '/init') {
    return runCodexExec('Create or update the project AGENTS.md instructions for this repository.', cwd);
  }

  if (command.name === '/status') {
    return {
      exitCode: 0,
      output: [
        'Codex Relay is connected.',
        `Workspace: ${cwd}`,
        `Command: ${codexCommand}`,
        'Use the live terminal for interactive model, approval, MCP, compact, new, and exit controls.'
      ].join('\n')
    };
  }

  if (command.name === '/new') {
    return {
      exitCode: 0,
      output: 'New chat controls are available from the phone dashboard. Start a new chat, then send your next task.'
    };
  }

  return {
    exitCode: 0,
    output: `${command.name} is an interactive Codex slash command. Open the live terminal in Codex Relay to run it directly.`
  };
}
