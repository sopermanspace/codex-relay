import 'dotenv/config';

import http from 'node:http';
import crypto from 'node:crypto';
import dgram from 'node:dgram';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { execFile, spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { promisify } from 'node:util';

import express from 'express';
import { customAlphabet } from 'nanoid';
import pty from '@homebridge/node-pty-prebuilt-multiarch';
import qrcode from 'qrcode-terminal';
import { WebSocketServer } from 'ws';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const rootDir = path.resolve(__dirname, '..');
const publicDir = path.join(rootDir, 'public');
const execFileAsync = promisify(execFile);

const host = process.env.HOST || '0.0.0.0';
const port = Number(process.env.PORT || 8787);
const discoveryPort = Number(process.env.REMOTE_DISCOVERY_PORT || 8788);
const discoveryRequest = 'CODEX_RELAY_DISCOVER_V1';
const codexCommand = process.env.CODEX_COMMAND || 'codex';
const codexWorkdir = process.env.CODEX_WORKDIR || os.homedir();
const projectRoots = getProjectRoots();
const codexStateDbPath = process.env.CODEX_STATE_DB || path.join(os.homedir(), '.codex', 'state_5.sqlite');
let desktopProjectPathCache = new Set();
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
const imageArtifactExtensions = new Set(['.avif', '.gif', '.jpeg', '.jpg', '.png', '.webp']);
const imageContentTypes = new Map([
  ['.avif', 'image/avif'],
  ['.gif', 'image/gif'],
  ['.jpeg', 'image/jpeg'],
  ['.jpg', 'image/jpeg'],
  ['.png', 'image/png'],
  ['.webp', 'image/webp']
]);
const makeToken = customAlphabet('123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz', 32);
const remoteToken = process.env.REMOTE_TOKEN || makeToken();
assertSafeRemoteToken(remoteToken, Boolean(process.env.REMOTE_TOKEN));
const trustedProxy = process.env.TRUST_PROXY === 'true';
const allowedRemoteOrigins = parseCsv(process.env.REMOTE_ALLOWED_ORIGINS);
const maxFailedAuth = Number(process.env.REMOTE_MAX_FAILED_AUTH || 12);
const failedAuthWindowMs = Number(process.env.REMOTE_AUTH_WINDOW_MS || 10 * 60 * 1000);
const sessionTtlMs = Number(process.env.CODEX_SESSION_TTL_MS || 30 * 60 * 1000);
const pairingCodeTtlMs = Number(process.env.REMOTE_PAIRING_CODE_TTL_MS || 10 * 60 * 1000);
const deviceTokenTtlMs = Number(process.env.REMOTE_DEVICE_TOKEN_TTL_MS || 30 * 24 * 60 * 60 * 1000);
const allowLegacyToken = process.env.REMOTE_ALLOW_LEGACY_TOKEN === 'true';
const failedAuth = new Map();
const pairedDevices = new Map();
let activePairing = createPairingCode();
let pendingPairingRequest = null;
let latestPairingEvent = null;
let connectedDevice = null;

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
app.use(express.json({ limit: process.env.REMOTE_JSON_LIMIT || '64mb' }));
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
    pairingRequired: true,
    pairingCodeLength: 8,
    connectedDevice: getConnectedDeviceSummary(),
    secureTransport: isSecureRequest(req),
    localNetwork: isPrivateNetworkIp(getClientIp(req))
  });
});

app.post('/api/pairing/start', (req, res) => {
  const ip = getClientIp(req);
  if (isRateLimited(ip)) {
    res.status(429).json({ error: 'Too many pairing attempts. Wait, then try again.' });
    return;
  }

  if (!isSameLocalNetwork(ip)) {
    res.status(403).json({ error: 'Pairing setup must start from the same Wi-Fi as your Mac.' });
    return;
  }

  const requestId = crypto.randomBytes(16).toString('base64url');
  const deviceName = sanitizePairingDeviceName(req.body?.deviceName);
  activePairing = createPairingCode({
    requestId,
    requiresConfirmation: true,
    confirmedAt: 0
  });
  pendingPairingRequest = {
    id: requestId,
    deviceName,
    ip,
    createdAt: activePairing.createdAt,
    expiresAt: activePairing.createdAt + pairingCodeTtlMs
  };
  latestPairingEvent = null;
  clearFailedAuth(ip);
  logPairingCode('Pairing code requested from nearby phone');
  res.json({
    ok: true,
    requestId,
    pairingCodeLength: 8,
    expiresAt: Date.now() + pairingCodeTtlMs
  });
});

app.get('/api/pairing/request', (req, res) => {
  if (!isPrivateNetworkIp(getClientIp(req)) && !isSecureRequest(req)) {
    res.status(403).json({ error: 'Pairing requests are only visible on this Mac or trusted local network.' });
    return;
  }

  if (!pendingPairingRequest || Date.now() > pendingPairingRequest.expiresAt) {
    pendingPairingRequest = null;
    if (latestPairingEvent && Date.now() < latestPairingEvent.expiresAt) {
      res.json({ ok: true, request: null, event: latestPairingEvent, connectedDevice: getConnectedDeviceSummary() });
      return;
    }
    latestPairingEvent = null;
    res.json({ ok: true, request: null, event: null, connectedDevice: getConnectedDeviceSummary() });
    return;
  }

  res.json({
    ok: true,
    request: {
      id: pendingPairingRequest.id,
      deviceName: pendingPairingRequest.deviceName,
      ip: pendingPairingRequest.ip,
      code: `${activePairing.code.slice(0, 4)} ${activePairing.code.slice(4)}`,
      confirmed: Boolean(activePairing.confirmedAt),
      expiresAt: pendingPairingRequest.expiresAt
    },
    connectedDevice: getConnectedDeviceSummary()
  });
});

app.post('/api/pairing/confirm', (req, res) => {
  if (!isPrivateNetworkIp(getClientIp(req)) && !isSecureRequest(req)) {
    res.status(403).json({ error: 'Pairing confirmation must happen on this Mac or trusted local network.' });
    return;
  }

  const requestId = String(req.body?.requestId || '');
  if (!pendingPairingRequest || pendingPairingRequest.id !== requestId || Date.now() > pendingPairingRequest.expiresAt) {
    res.status(404).json({ error: 'Pairing request expired.' });
    return;
  }

  activePairing.confirmedAt = Date.now();
  res.json({ ok: true });
});

app.post('/api/pairing/cancel', (req, res) => {
  if (!isPrivateNetworkIp(getClientIp(req)) && !isSecureRequest(req)) {
    res.status(403).json({ error: 'Pairing cancellation must happen on this Mac or trusted local network.' });
    return;
  }

  const requestId = String(req.body?.requestId || '');
  if (pendingPairingRequest && pendingPairingRequest.id === requestId) {
    pendingPairingRequest = null;
    latestPairingEvent = null;
    activePairing = createPairingCode();
  }
  res.json({ ok: true });
});

app.post('/api/pair', (req, res) => {
  const access = authorizePairingRequest(req);
  if (!access.ok) {
    res.status(access.status).json({ error: access.error });
    return;
  }

  const code = normalizePairingCode(req.body?.code);
  if (!isCurrentPairingCode(code)) {
    noteFailedAuth(access.ip);
    res.status(401).json({ error: 'Pairing code rejected. Check the code on your Mac and try again.' });
    return;
  }
  if (activePairing.requiresConfirmation && !activePairing.confirmedAt) {
    res.status(409).json({ error: 'Approve this phone on your Mac first, then tap Pair / Connect again.' });
    return;
  }
  if (activePairing.requiresConfirmation && !isSameLocalNetwork(access.ip)) {
    res.status(403).json({ error: 'First-time pairing must finish on the same Wi-Fi as your Mac.' });
    return;
  }

  const deviceToken = crypto.randomBytes(32).toString('base64url');
  const connectedName = pendingPairingRequest?.deviceName || 'Android phone';
  pairedDevices.set(hashSecret(deviceToken), {
    createdAt: Date.now(),
    lastSeenAt: Date.now(),
    ip: access.ip,
    deviceName: connectedName,
    userAgent: String(req.headers['user-agent'] || '').slice(0, 160)
  });
  connectedDevice = {
    deviceName: connectedName,
    ip: access.ip,
    connectedAt: Date.now(),
    lastSeenAt: Date.now(),
    expiresAt: Date.now() + deviceTokenTtlMs
  };
  clearFailedAuth(access.ip);
  latestPairingEvent = {
    type: 'connected',
    id: pendingPairingRequest?.id || activePairing.requestId || crypto.randomBytes(8).toString('base64url'),
    deviceName: connectedName,
    ip: access.ip,
    at: Date.now(),
    expiresAt: Date.now() + 12_000
  };
  pendingPairingRequest = null;
  activePairing = createPairingCode();
  logPairingCode('Next pairing code');

  res.status(201).json({
    ok: true,
    token: deviceToken,
    expiresAt: Date.now() + deviceTokenTtlMs
  });
});

app.get('/api/artifact', async (req, res) => {
  const access = authorizeRequest(req, {
    token: typeof req.query?.token === 'string' ? req.query.token : undefined,
    mode: req.headers['x-codex-access-mode']
  });
  if (!access.ok) {
    res.status(access.status).json({ error: access.error });
    return;
  }

  const artifactPath = resolveArtifactPath(req.query?.path);
  if (!artifactPath) {
    res.status(404).json({ error: 'Artifact not found.' });
    return;
  }

  const extension = path.extname(artifactPath).toLowerCase();
  res.setHeader('Content-Type', imageContentTypes.get(extension) || 'application/octet-stream');
  res.setHeader('Cache-Control', 'private, max-age=300');
  res.sendFile(artifactPath);
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

app.post('/api/attachments', async (req, res) => {
  const access = authorizeRequest(req);
  if (!access.ok) {
    res.status(access.status).json({ error: access.error });
    return;
  }

  const images = Array.isArray(req.body?.images) ? req.body.images.slice(0, 6) : [];
  if (images.length === 0) {
    res.status(400).json({ error: 'Choose at least one image.' });
    return;
  }

  try {
    const uploadDir = path.join(codexWorkdir, '.codex-relay-uploads');
    await fs.mkdir(uploadDir, { recursive: true });
    const attachments = [];

    for (const image of images) {
      const attachment = await saveImageAttachment(image, uploadDir);
      attachments.push(attachment);
    }

    res.status(201).json({ ok: true, attachments });
  } catch (error) {
    res.status(400).json({ ok: false, error: error.message });
  }
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

app.get('/api/project-chats', async (req, res) => {
  const access = authorizeRequest(req);
  if (!access.ok) {
    res.status(access.status).json({ error: access.error });
    return;
  }

  const cwd = resolveRequestedCwd(req.query?.cwd);
  if (!cwd) {
    res.status(400).json({ error: 'Project folder is outside the allowed roots.' });
    return;
  }

  try {
    const chats = await listProjectChats(cwd);
    res.json({ ok: true, cwd, chats });
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
  const threadId = normalizeThreadId(req.body?.threadId);
  if (!prompt) {
    res.status(400).json({ error: 'Prompt is required' });
    return;
  }
  if (!cwd) {
    res.status(400).json({ error: 'Project folder is outside the allowed roots.' });
    return;
  }
  if (threadId && !(await threadBelongsToCwd(threadId, cwd))) {
    res.status(400).json({ error: 'Selected chat does not belong to this project.' });
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

    const result = await runCodexExec(prompt, cwd, threadId);
    res.json({
      ok: result.exitCode === 0,
      exitCode: result.exitCode,
      output: result.output,
      artifacts: result.artifacts,
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
  logPairingCode('Pairing code');

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

startDiscoveryResponder();

setInterval(cleanupSecurityState, 60_000).unref();
setInterval(rotatePairingCodeIfExpired, 15_000).unref();

function getBearerToken(req) {
  const auth = req.headers.authorization || '';
  return auth.startsWith('Bearer ') ? auth.slice(7) : '';
}

function isAuthorized(token) {
  if (typeof token !== 'string' || token.length === 0) return false;
  if (isAuthorizedDeviceToken(token)) return true;
  if (!allowLegacyToken) return false;
  const expected = Buffer.from(remoteToken);
  const received = Buffer.from(token);
  if (expected.length !== received.length) return false;
  return crypto.timingSafeEqual(expected, received);
}

function isAuthorizedDeviceToken(token) {
  const device = pairedDevices.get(hashSecret(token));
  if (!device) return false;
  if (Date.now() - device.createdAt > deviceTokenTtlMs) {
    pairedDevices.delete(hashSecret(token));
    return false;
  }
  device.lastSeenAt = Date.now();
  if (connectedDevice && connectedDevice.deviceName === device.deviceName) {
    connectedDevice.lastSeenAt = device.lastSeenAt;
  }
  return true;
}

function getConnectedDeviceSummary() {
  if (!connectedDevice || Date.now() > connectedDevice.expiresAt) {
    connectedDevice = null;
    return null;
  }

  return {
    deviceName: connectedDevice.deviceName,
    ip: connectedDevice.ip,
    connectedAt: connectedDevice.connectedAt,
    lastSeenAt: connectedDevice.lastSeenAt
  };
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

function authorizePairingRequest(req) {
  const ip = getClientIp(req);
  if (isRateLimited(ip)) {
    return { ok: false, status: 429, error: 'Too many pairing attempts. Wait, then use the newest code on your Mac.' };
  }

  const mode = normalizeAccessMode(req.headers['x-codex-access-mode']);
  const isLocal = isPrivateNetworkIp(ip);
  if ((mode === 'remote' || !isLocal) && !isSecureRequest(req)) {
    return { ok: false, status: 403, error: 'Pairing over the internet requires HTTPS.' };
  }

  const origin = req.headers.origin;
  if ((mode === 'remote' || !isLocal) && allowedRemoteOrigins.length > 0 && origin && !allowedRemoteOrigins.includes(origin)) {
    return { ok: false, status: 403, error: 'Connection blocked.' };
  }

  return { ok: true, ip };
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

function createPairingCode(options = {}) {
  return {
    code: String(crypto.randomInt(0, 100_000_000)).padStart(8, '0'),
    createdAt: Date.now(),
    requestId: options.requestId || '',
    requiresConfirmation: Boolean(options.requiresConfirmation),
    confirmedAt: options.confirmedAt || 0
  };
}

function normalizePairingCode(value) {
  return String(value || '').replace(/\D/g, '').slice(0, 8);
}

function normalizeThreadId(value) {
  const id = String(value || '').trim();
  return /^[a-zA-Z0-9_-]{8,80}$/.test(id) ? id : '';
}

function sqlString(value) {
  return `'${String(value).replaceAll("'", "''")}'`;
}

function isCurrentPairingCode(code) {
  if (Date.now() - activePairing.createdAt > pairingCodeTtlMs) return false;
  if (code.length !== activePairing.code.length) return false;
  return crypto.timingSafeEqual(Buffer.from(code), Buffer.from(activePairing.code));
}

function rotatePairingCodeIfExpired() {
  if (Date.now() - activePairing.createdAt <= pairingCodeTtlMs) return;
  activePairing = createPairingCode();
  pendingPairingRequest = null;
  logPairingCode('New pairing code');
}

function hashSecret(value) {
  return crypto.createHash('sha256').update(String(value)).digest('base64url');
}

function logPairingCode(label) {
  const pretty = `${activePairing.code.slice(0, 4)} ${activePairing.code.slice(4)}`;
  const minutes = Math.max(1, Math.round(pairingCodeTtlMs / 60_000));
  console.log(`${label}: ${pretty} (expires in ${minutes} min, one-time use)`);
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

  for (const [tokenHash, device] of pairedDevices) {
    if (now - device.createdAt > deviceTokenTtlMs) pairedDevices.delete(tokenHash);
  }

  if (connectedDevice && now > connectedDevice.expiresAt) {
    connectedDevice = null;
  }

  if (pendingPairingRequest && now > pendingPairingRequest.expiresAt) {
    pendingPairingRequest = null;
  }

  if (latestPairingEvent && now > latestPairingEvent.expiresAt) {
    latestPairingEvent = null;
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

function sanitizePairingDeviceName(name) {
  const value = String(name || '').replace(/[^\w .-]/g, '').replace(/\s+/g, ' ').trim();
  return value.slice(0, 60) || 'Android phone';
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

function startDiscoveryResponder() {
  const socket = dgram.createSocket('udp4');
  socket.on('message', (message, rinfo) => {
    if (String(message).trim() !== discoveryRequest) return;
    const url = getNetworkUrlForClient(rinfo.address, port);
    if (!url) return;

    const payload = Buffer.from(JSON.stringify({
      type: 'CODEX_RELAY_DISCOVERY_V1',
      name: 'Codex Relay',
      url,
      port
    }));
    socket.send(payload, rinfo.port, rinfo.address);
  });
  socket.on('error', (error) => {
    console.warn(`Codex Relay discovery disabled: ${error.message}`);
    socket.close();
  });
  socket.bind(discoveryPort, host, () => {
    try {
      socket.setBroadcast(true);
    } catch {
      // Some hosts do not allow broadcast toggles; direct replies still work.
    }
    console.log(`Nearby pairing discovery listening on udp://0.0.0.0:${discoveryPort}`);
  });
  socket.unref();
}

function getNetworkUrlForClient(clientIp, selectedPort) {
  if (!clientIp || clientIp.includes(':')) return null;
  for (const addresses of Object.values(os.networkInterfaces())) {
    for (const address of addresses || []) {
      if (address.family !== 'IPv4' || address.internal) continue;
      if (isIpv4InNetwork(clientIp, address.address, address.netmask)) {
        return `http://${address.address}:${selectedPort}`;
      }
    }
  }
  return null;
}

function isSameLocalNetwork(clientIp) {
  if (clientIp === '127.0.0.1' || clientIp === 'localhost' || clientIp === '::1') return true;
  if (!clientIp || clientIp.includes(':')) return false;
  for (const addresses of Object.values(os.networkInterfaces())) {
    for (const address of addresses || []) {
      if (address.family !== 'IPv4' || address.internal) continue;
      if (isIpv4InNetwork(clientIp, address.address, address.netmask)) return true;
    }
  }
  return false;
}

function isIpv4InNetwork(candidateIp, interfaceIp, netmask) {
  const candidate = ipv4ToInt(candidateIp);
  const current = ipv4ToInt(interfaceIp);
  const mask = ipv4ToInt(netmask || '255.255.255.0');
  if (candidate === null || current === null || mask === null) return false;
  return (candidate & mask) === (current & mask);
}

function ipv4ToInt(value) {
  const parts = String(value || '').split('.');
  if (parts.length !== 4) return null;
  let result = 0;
  for (const part of parts) {
    if (!/^\d+$/.test(part)) return null;
    const number = Number(part);
    if (number < 0 || number > 255) return null;
    result = ((result << 8) | number) >>> 0;
  }
  return result >>> 0;
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

  const addProject = async (projectPath, overrides = {}) => {
    const resolved = path.resolve(projectPath);
    if (seen.has(resolved)) return;
    seen.add(resolved);
    const meta = await projectMeta(resolved);
    if (overrides.source === 'codex' && !meta.tags.includes('Codex')) {
      meta.tags = ['Codex', ...meta.tags];
    }
    entries.push({ ...meta, ...overrides });
  };

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
      await addProject(fullPath, { source: 'folder' });
    }
  }

  for (const desktopProject of await listDesktopCodexProjects()) {
    await addProject(desktopProject.path, {
      source: 'codex',
      threadCount: desktopProject.threadCount,
      updatedAt: desktopProject.updatedAt
    });
  }

  entries.sort((a, b) => b.updatedAt - a.updatedAt || a.name.localeCompare(b.name));
  return entries.slice(0, Number(process.env.CODEX_MAX_PROJECTS || 80));
}

async function listDesktopCodexProjects() {
  if (!(await exists(codexStateDbPath))) {
    desktopProjectPathCache = new Set();
    return [];
  }

  const sql = `
    select
      cwd,
      count(*) as threadCount,
      max(coalesce(updated_at_ms, updated_at * 1000)) as updatedAt
    from threads
    where archived = 0
      and cwd is not null
      and cwd <> ''
    group by cwd
    order by updatedAt desc
    limit ${Number(process.env.CODEX_DESKTOP_PROJECT_LIMIT || 200)}
  `;

  let rows = [];
  try {
    const { stdout } = await execFileAsync('sqlite3', ['-json', codexStateDbPath, sql], {
      timeout: Number(process.env.CODEX_STATE_QUERY_TIMEOUT_MS || 3000),
      maxBuffer: 1024 * 1024
    });
    rows = JSON.parse(stdout || '[]');
  } catch (error) {
    console.warn(`Unable to read Codex desktop project state: ${error.message}`);
    return [];
  }

  const projects = [];
  const syncedPaths = new Set();

  for (const row of rows) {
    const projectPath = typeof row.cwd === 'string' ? path.resolve(row.cwd) : '';
    if (!shouldIncludeDesktopProject(projectPath)) continue;

    try {
      const stat = await fs.stat(projectPath);
      if (!stat.isDirectory()) continue;
    } catch {
      continue;
    }

    syncedPaths.add(projectPath);
    projects.push({
      path: projectPath,
      threadCount: Number(row.threadCount || 0),
      updatedAt: Number(row.updatedAt || 0)
    });
  }

  desktopProjectPathCache = syncedPaths;
  return projects;
}

async function listProjectChats(cwd) {
  if (!(await exists(codexStateDbPath))) return [];

  const sql = `
    select
      id,
      title,
      first_user_message as firstUserMessage,
      source,
      model,
      coalesce(updated_at_ms, updated_at * 1000) as updatedAt,
      coalesce(created_at_ms, created_at * 1000) as createdAt
    from threads
    where archived = 0
      and cwd = ${sqlString(cwd)}
    order by updatedAt desc
    limit ${Number(process.env.CODEX_MAX_PROJECT_CHATS || 20)}
  `;

  try {
    const { stdout } = await execFileAsync('sqlite3', ['-json', codexStateDbPath, sql], {
      timeout: Number(process.env.CODEX_STATE_QUERY_TIMEOUT_MS || 3000),
      maxBuffer: 1024 * 1024
    });
    const rows = JSON.parse(stdout || '[]');
    return rows.map((row) => ({
      id: String(row.id || ''),
      title: String(row.title || row.firstUserMessage || 'Untitled chat').slice(0, 160),
      preview: String(row.firstUserMessage || '').slice(0, 220),
      source: String(row.source || ''),
      model: String(row.model || ''),
      updatedAt: Number(row.updatedAt || 0),
      createdAt: Number(row.createdAt || 0)
    })).filter((row) => row.id);
  } catch (error) {
    console.warn(`Unable to read Codex project chats: ${error.message}`);
    return [];
  }
}

async function threadBelongsToCwd(threadId, cwd) {
  if (!threadId || !(await exists(codexStateDbPath))) return false;
  const sql = `
    select id
    from threads
    where archived = 0
      and id = ${sqlString(threadId)}
      and cwd = ${sqlString(cwd)}
    limit 1
  `;

  try {
    const { stdout } = await execFileAsync('sqlite3', ['-json', codexStateDbPath, sql], {
      timeout: Number(process.env.CODEX_STATE_QUERY_TIMEOUT_MS || 3000),
      maxBuffer: 1024 * 128
    });
    const rows = JSON.parse(stdout || '[]');
    return rows.length > 0;
  } catch {
    return false;
  }
}

function shouldIncludeDesktopProject(projectPath) {
  if (!projectPath) return false;
  const base = path.basename(projectPath);
  if (!base || base.startsWith('.') || ignoredProjectDirs.has(base)) return false;
  if (projectPath === os.homedir()) return false;

  const codexStateRoot = path.join(os.homedir(), '.codex');
  const relativeToState = path.relative(codexStateRoot, projectPath);
  if (relativeToState === '' || (!relativeToState.startsWith('..') && !path.isAbsolute(relativeToState))) {
    return false;
  }

  return true;
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
  return isPathInsideRoots(candidate, projectRoots) || isPathInsideRoots(candidate, [...desktopProjectPathCache]);
}

function isPathInsideRoots(candidate, roots) {
  return roots.some((root) => {
    const relative = path.relative(root, candidate);
    return relative === '' || (!relative.startsWith('..') && !path.isAbsolute(relative));
  });
}

function runCodexExec(prompt, cwd = codexWorkdir, threadId = '') {
  return (async () => {
    const outputDir = await fs.mkdtemp(path.join(os.tmpdir(), 'codex-relay-'));
    const outputFile = path.join(outputDir, 'last-message.txt');
    const imageSnapshot = await listImageArtifacts(cwd);
    const args = threadId
      ? [
        'exec',
        'resume',
        '--skip-git-repo-check',
        '--output-last-message',
        outputFile,
        threadId,
        buildCodexExecPrompt(prompt)
      ]
      : [
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
        buildCodexExecPrompt(prompt)
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
      const artifacts = await collectImageArtifacts(cwd, imageSnapshot, finalOutput);
      fs.rm(outputDir, { recursive: true, force: true }).catch(() => {});
      resolve({
        exitCode,
        output: finalOutput,
        artifacts
      });
    });
    });
  })();
}

function buildCodexExecPrompt(prompt) {
  if (!looksLikeImageRequest(prompt)) return prompt;
  return [
    prompt,
    '',
    'Relay display requirement: save any generated or edited image as a PNG, JPG, WEBP, AVIF, or GIF file inside the current workspace, then include the saved file path in the final response. Do not report that the image is complete unless a viewable image file exists.'
  ].join('\n');
}

function looksLikeImageRequest(prompt) {
  return /\b(image|picture|photo|wallpaper|poster|logo|icon|avatar|mockup|illustration|banner|thumbnail|generate.*visual|create.*visual)\b/i.test(prompt);
}

async function collectImageArtifacts(cwd, previousImages, output) {
  const previous = new Map(previousImages.map((item) => [item.path, item]));
  const currentImages = await listImageArtifacts(cwd);
  const discovered = new Map();

  for (const item of currentImages) {
    const before = previous.get(item.path);
    if (!before || before.mtimeMs !== item.mtimeMs || before.size !== item.size) {
      discovered.set(item.path, item);
    }
  }

  for (const referencedPath of extractReferencedImagePaths(output, cwd)) {
    const match = currentImages.find((item) => item.path === referencedPath);
    if (match) discovered.set(match.path, match);
  }

  return [...discovered.values()]
    .sort((a, b) => b.mtimeMs - a.mtimeMs)
    .slice(0, 8)
    .map((item) => ({
      type: 'image',
      name: path.basename(item.path),
      path: item.path,
      url: `/api/artifact?path=${encodeURIComponent(item.path)}`,
      size: item.size
    }));
}

async function listImageArtifacts(cwd) {
  const root = path.resolve(cwd);
  const results = [];
  const maxVisited = Number(process.env.CODEX_ARTIFACT_SCAN_LIMIT || 2500);
  let visited = 0;

  async function walk(current) {
    if (visited >= maxVisited) return;
    let entries;
    try {
      entries = await fs.readdir(current, { withFileTypes: true });
    } catch {
      return;
    }

    for (const entry of entries) {
      if (visited >= maxVisited) return;
      if (entry.name.startsWith('.') || ignoredProjectDirs.has(entry.name)) continue;
      const fullPath = path.join(current, entry.name);
      if (entry.isDirectory()) {
        await walk(fullPath);
        continue;
      }
      if (!entry.isFile()) continue;
      visited += 1;
      if (!imageArtifactExtensions.has(path.extname(entry.name).toLowerCase())) continue;
      try {
        const stat = await fs.stat(fullPath);
        results.push({
          path: fullPath,
          mtimeMs: stat.mtimeMs,
          size: stat.size
        });
      } catch {
        // Ignore files that disappear while scanning.
      }
    }
  }

  await walk(root);
  return results;
}

function extractReferencedImagePaths(output, cwd) {
  if (typeof output !== 'string' || output.trim() === '') return [];
  const matches = new Set();
  const patterns = [
    /!\[[^\]]*]\(([^)]+)\)/g,
    /(?:^|\s)(\/[^\s"'<>]+\.(?:avif|gif|jpe?g|png|webp))(?:\s|$)/gi,
    /(?:^|\s)([^\s"'<>]+\.(?:avif|gif|jpe?g|png|webp))(?:\s|$)/gi
  ];

  for (const pattern of patterns) {
    for (const match of output.matchAll(pattern)) {
      const rawPath = String(match[1] || '').replace(/^file:\/\//, '').trim();
      const withoutFragment = rawPath.split('#')[0].split('?')[0];
      const resolved = path.isAbsolute(withoutFragment)
        ? path.resolve(withoutFragment)
        : path.resolve(cwd, withoutFragment);
      if (isAllowedProjectPath(resolved)) matches.add(resolved);
    }
  }

  return [...matches];
}

function resolveArtifactPath(value) {
  if (typeof value !== 'string' || value.trim() === '') return null;
  const resolved = path.resolve(value);
  if (!isAllowedProjectPath(resolved)) return null;
  const extension = path.extname(resolved).toLowerCase();
  if (!imageArtifactExtensions.has(extension)) return null;
  return resolved;
}

async function saveImageAttachment(image, uploadDir) {
  if (!image || typeof image.data !== 'string') {
    throw new Error('Attachment data is missing.');
  }

  const extension = imageExtensionForUpload(image);
  if (!extension) {
    throw new Error('Only PNG, JPEG, WebP, GIF, and AVIF images are supported.');
  }

  const base64 = image.data.includes(',')
    ? image.data.slice(image.data.indexOf(',') + 1)
    : image.data;
  if (!/^[a-zA-Z0-9+/=\s]+$/.test(base64)) {
    throw new Error('Image data is invalid.');
  }

  const buffer = Buffer.from(base64.replace(/\s/g, ''), 'base64');
  const maxBytes = Number(process.env.CODEX_ATTACHMENT_MAX_BYTES || 10 * 1024 * 1024);
  if (buffer.length === 0 || buffer.length > maxBytes) {
    throw new Error('Each image must be smaller than 10 MB.');
  }

  const originalName = typeof image.name === 'string' ? image.name : 'image';
  const safeBase = path.basename(originalName, path.extname(originalName))
    .replace(/[^\w.-]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 60) || 'image';
  const fileName = `${Date.now()}-${makeToken().slice(0, 8)}-${safeBase}${extension}`;
  const targetPath = path.join(uploadDir, fileName);

  if (!isAllowedProjectPath(targetPath)) {
    throw new Error('Attachment folder is outside the allowed workspace.');
  }

  await fs.writeFile(targetPath, buffer);
  return {
    name: `${safeBase}${extension}`,
    path: targetPath,
    size: buffer.length,
    type: imageContentTypes.get(extension)
  };
}

function imageExtensionForUpload(image) {
  const fromName = path.extname(String(image.name || '')).toLowerCase();
  if (imageArtifactExtensions.has(fromName)) return fromName;

  const type = String(image.type || '').toLowerCase();
  for (const [extension, contentType] of imageContentTypes) {
    if (contentType === type) return extension;
  }

  const dataMatch = String(image.data || '').match(/^data:(image\/[a-z0-9.+-]+);base64,/i);
  if (dataMatch) {
    const contentType = dataMatch[1].toLowerCase();
    for (const [extension, mappedType] of imageContentTypes) {
      if (mappedType === contentType) return extension;
    }
  }

  return '';
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
