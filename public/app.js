const statusEl = document.querySelector('#status');
const workdirEl = document.querySelector('#workdir');
const loginForm = document.querySelector('#loginForm');
const pairingCodeInput = document.querySelector('#pairingCode');
const accessPanel = document.querySelector('#accessPanel');
const remotePanel = document.querySelector('#remotePanel');
const chatLog = document.querySelector('#chatLog');
const emptyState = document.querySelector('#emptyState');
const composer = document.querySelector('#composer');
const promptInput = document.querySelector('#prompt');
const attachButton = document.querySelector('#attachButton');
const imageInput = document.querySelector('#imageInput');
const attachmentTray = document.querySelector('#attachmentTray');
const interruptButton = document.querySelector('#interruptButton');
const clearButton = document.querySelector('#clearButton');
const quickKeys = document.querySelectorAll('.key-button');
const connectButton = document.querySelector('#connectButton');
const loginError = document.querySelector('#loginError');
const accessTitle = document.querySelector('#accessTitle');
const accessSubtitle = document.querySelector('#accessSubtitle');
const workspaceName = document.querySelector('#workspaceName');
const slashPanel = document.querySelector('#slashPanel');
const slashList = document.querySelector('#slashList');
const paletteTitle = document.querySelector('#paletteTitle');
const notifyButton = document.querySelector('#notifyButton');
const shell = document.querySelector('.shell');
const clearPairingButton = document.querySelector('#clearPairingButton');
const pairingRequestPanel = document.querySelector('#pairingRequestPanel');
const pairingRequestTitle = document.querySelector('#pairingRequestTitle');
const pairingRequestDevice = document.querySelector('#pairingRequestDevice');
const pairingRequestCode = document.querySelector('#pairingRequestCode');
const confirmPairingRequest = document.querySelector('#confirmPairingRequest');
const cancelPairingRequest = document.querySelector('#cancelPairingRequest');
const sidebarButton = document.querySelector('#sidebarButton');
const projectsButton = document.querySelector('#projectsButton');
const menuProjectsButton = document.querySelector('#menuProjectsButton');
const projectDrawer = document.querySelector('#projectDrawer');
const projectPanel = projectDrawer?.querySelector('.drawer-panel');
const projectScrim = document.querySelector('#projectScrim');
const closeProjectsButton = document.querySelector('#closeProjectsButton');
const projectList = document.querySelector('#projectList');

let deviceToken = localStorage.getItem('codexRelayDeviceToken') || '';
let slashCommands = [];
let mentionItems = [];
let projects = [];
let projectsLoaded = false;
let selectedProject = {
  name: localStorage.getItem('codexRelayProjectName') || 'Default workspace',
  path: localStorage.getItem('codexRelayProjectPath') || ''
};
let lastActivityAt = 0;
let activeRequest = null;
let activeAssistantMessage = null;
let pendingAttachments = [];
let visiblePairingRequest = null;
let connectedDevice = null;

function setPairingFocus(isFocused) {
  shell?.classList.toggle('is-pairing', isFocused);
  document.body.classList.toggle('is-pairing', isFocused);
}

function updateConnectedDevice(device) {
  connectedDevice = device || null;
  accessPanel?.classList.toggle('has-connected-device', Boolean(connectedDevice));
  if (!accessTitle || !accessSubtitle) return;

  if (connectedDevice) {
    const name = connectedDevice.deviceName || 'Android phone';
    accessTitle.textContent = `${name} connected`;
    accessSubtitle.textContent = 'This Mac is paired and ready to receive Codex requests from your device.';
    workspaceName.textContent = name;
    return;
  }

  accessTitle.textContent = 'Ready for nearby pairing';
  accessSubtitle.textContent = 'Open the Android app on the same Wi-Fi and tap Continue. Pairing requests appear here for approval.';
  workspaceName.textContent = 'Ready on this Mac';
}

localStorage.removeItem('codexRemoteToken');

clearPairingButton?.addEventListener('click', () => {
  pairingCodeInput.value = '';
  pairingCodeInput.focus();
});

confirmPairingRequest?.addEventListener('click', () => respondToPairingRequest('confirm'));
cancelPairingRequest?.addEventListener('click', () => respondToPairingRequest('cancel'));

for (const button of [sidebarButton, projectsButton, menuProjectsButton]) {
  button?.addEventListener('click', () => openProjects());
}

projectScrim?.addEventListener('click', closeProjects);
closeProjectsButton?.addEventListener('click', closeProjects);
projectDrawer?.addEventListener('keydown', (event) => {
  if (event.key === 'Escape') closeProjects();
});

fetch('/api/config')
  .then((response) => response.json())
  .then((config) => {
    workdirEl.textContent = config.workspaceLabel || 'Private command center';
    updateConnectedDevice(config.connectedDevice);
    if (!config.connectedDevice) workspaceName.textContent = config.workspaceLabel || 'Ready on this Mac';
  })
  .catch(() => {
    workdirEl.textContent = 'Server unavailable';
    workspaceName.textContent = 'Unavailable';
    setFormError('App-server is not responding.');
  });

if ('serviceWorker' in navigator) {
  navigator.serviceWorker.register('/sw.js').catch(() => {});
}

if (deviceToken) {
  setStatus('Checking', 'busy');
  window.setTimeout(() => unlockChat(), 0);
}

pollPairingRequest();
window.setInterval(pollPairingRequest, 2500);

loginForm?.addEventListener('submit', async (event) => {
  event.preventDefault();
  try {
    await pairDevice();
    await unlockChat();
  } catch {
    // The form message already explains the pairing failure.
  }
});

pairingCodeInput?.addEventListener('input', () => {
  const digits = pairingCodeInput.value.replace(/\D/g, '').slice(0, 8);
  pairingCodeInput.value = digits.length > 4 ? `${digits.slice(0, 4)} ${digits.slice(4)}` : digits;
});

composer.addEventListener('submit', async (event) => {
  event.preventDefault();
  const value = promptInput.value.trim();
  if ((!value && pendingAttachments.length === 0) || activeRequest) return;

  const attachments = [...pendingAttachments];
  appendUserMessage(value || 'Analyze the attached image.', attachments);
  promptInput.value = '';
  clearAttachments();
  resizeComposer();
  hidePalette();
  await sendPrompt(value, attachments);
});

promptInput.addEventListener('input', () => {
  resizeComposer();
  updatePalette();
});

promptInput.addEventListener('focus', updatePalette);
promptInput.addEventListener('keydown', (event) => {
  if (event.key === 'Escape') {
    hidePalette();
    return;
  }

  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault();
    composer.requestSubmit();
  }
});

notifyButton.addEventListener('click', async () => {
  await requestNotificationPermission();
  updateNotifyButton();
});

attachButton.addEventListener('click', () => {
  imageInput.click();
});

imageInput.addEventListener('change', async () => {
  const files = [...imageInput.files].filter((file) => file.type.startsWith('image/'));
  imageInput.value = '';
  if (files.length === 0) return;
  await uploadAttachments(files);
});

interruptButton.addEventListener('click', () => {
  if (!activeRequest) return;
  activeRequest.abort();
});

clearButton.addEventListener('click', () => {
  chatLog.replaceChildren(emptyState);
  emptyState.classList.remove('hidden');
  clearAttachments();
  clearButton.classList.add('hidden');
  setStatus('Ready', 'live');
  promptInput.focus();
});

for (const button of quickKeys) {
  button.addEventListener('click', () => {
    const key = button.dataset.key;
    if (key === 'escape') {
      hidePalette();
      promptInput.focus();
      return;
    }
    if (key === 'tab') insertAtCursor('  ');
    if (key === 'up') insertAtCursor('/status');
    if (key === 'down') insertAtCursor('/help');
  });
}

async function unlockChat() {
  setFormError('');
  setConnecting(true);
  setStatus('Checking', 'busy');
  if (!deviceToken) {
    setStatus('Ready', 'live');
    setFormError('');
    setConnecting(false);
    return;
  }

  let response;
  try {
    response = await fetch('/api/auth', {
      headers: authHeaders()
    });
  } catch {
    setStatus('Offline', 'error');
    setFormError('Cannot reach the Codex app-server.');
    setConnecting(false);
    return;
  }

  if (!response.ok) {
    setStatus('Pair again', 'error');
    setFormError('This device is not paired anymore. Enter the newest code from your Mac.');
    localStorage.removeItem('codexRelayDeviceToken');
    deviceToken = '';
    setConnecting(false);
    return;
  }

  localStorage.setItem('codexRelayDeviceToken', deviceToken);
  accessPanel.classList.add('hidden');
  remotePanel.classList.remove('hidden');
  shell.classList.add('is-unlocked');
  setStatus('Ready', 'live');
  setConnecting(false);
  promptInput.focus();
  loadSlashCommands();
  loadMentions();
  loadProjects();
  updateActiveProjectUi();
  updateNotifyButton();
}

async function pairDevice() {
  const code = pairingCodeInput.value.replace(/\D/g, '');
  if (deviceToken && code.length === 0) return;
  if (code.length !== 8) {
    setStatus('Code needed', 'error');
    setFormError('Enter the 8-digit pairing code shown on your Mac.');
    throw new Error('Pairing code required.');
  }

  setFormError('');
  setConnecting(true);
  setStatus('Pairing', 'busy');

  let response;
  try {
    response = await fetch('/api/pair', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ code })
    });
  } catch {
    setStatus('Offline', 'error');
    setFormError('Cannot reach the Codex app-server.');
    setConnecting(false);
    throw new Error('Pairing failed.');
  }

  const payload = await response.json().catch(() => ({}));
  if (!response.ok || !payload.token) {
    setStatus(response.status === 409 ? 'Confirm on Mac' : 'Code rejected', 'error');
    setFormError(payload.error || 'Pairing code rejected. Check the code on your Mac and try again.');
    setConnecting(false);
    throw new Error('Pairing rejected.');
  }

  deviceToken = payload.token;
  localStorage.setItem('codexRelayDeviceToken', deviceToken);
}

async function pollPairingRequest() {
  let payload;
  try {
    const response = await fetch('/api/pairing/request');
    if (!response.ok) return;
    payload = await response.json();
  } catch {
    return;
  }

  const request = payload.request;
  const event = payload.event;
  if (!request && event?.type === 'connected') {
    showPairingConnected(event);
    return;
  }

  if (!request) {
    visiblePairingRequest = null;
    pairingRequestPanel?.classList.add('hidden');
    setPairingFocus(false);
    updateConnectedDevice(payload.connectedDevice);
    return;
  }

  visiblePairingRequest = request;
  setPairingFocus(true);
  pairingRequestPanel?.classList.remove('is-connected');
  pairingRequestTitle.textContent = request.confirmed ? 'Waiting for device to finish' : 'Nearby device wants to pair';
  pairingRequestDevice.textContent = request.deviceName || 'Android phone';
  pairingRequestCode.textContent = request.code || '0000 0000';
  confirmPairingRequest.textContent = request.confirmed ? 'Confirmed' : 'Confirm';
  confirmPairingRequest.disabled = Boolean(request.confirmed);
  confirmPairingRequest.classList.remove('hidden');
  cancelPairingRequest.classList.remove('hidden');
  pairingRequestPanel?.classList.remove('hidden');
}

function showPairingConnected(event) {
  visiblePairingRequest = null;
  updateConnectedDevice(event);
  setPairingFocus(true);
  pairingRequestPanel?.classList.add('is-connected');
  pairingRequestTitle.textContent = 'Device connected';
  pairingRequestDevice.textContent = event.deviceName || 'Android phone';
  pairingRequestCode.textContent = 'Connected';
  confirmPairingRequest.classList.add('hidden');
  cancelPairingRequest.classList.add('hidden');
  pairingRequestPanel?.classList.remove('hidden');
  window.setTimeout(() => {
    pairingRequestPanel?.classList.add('hidden');
    pairingRequestPanel?.classList.remove('is-connected');
    setPairingFocus(false);
    confirmPairingRequest.classList.remove('hidden');
    cancelPairingRequest.classList.remove('hidden');
  }, 4200);
}

async function respondToPairingRequest(action) {
  if (!visiblePairingRequest) return;
  const requestId = visiblePairingRequest.id;
  const endpoint = action === 'confirm' ? '/api/pairing/confirm' : '/api/pairing/cancel';
  const button = action === 'confirm' ? confirmPairingRequest : cancelPairingRequest;
  button.disabled = true;
  try {
    await fetch(endpoint, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ requestId })
    });
    await pollPairingRequest();
  } finally {
    button.disabled = false;
  }
}

async function sendPrompt(prompt, attachments = []) {
  const controller = new AbortController();
  activeRequest = controller;
  activeAssistantMessage = appendAssistantMessage('', { thinking: true });
  setStatus('Thinking', 'busy');
  setComposerBusy(true);

  try {
    const response = await fetch('/api/command', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...authHeaders()
      },
      body: JSON.stringify({
        prompt: buildPrompt(prompt, attachments),
        ...(selectedProject.path ? { cwd: selectedProject.path } : {})
      }),
      signal: controller.signal
    });

    const payload = await response.json().catch(() => ({}));
    if (!response.ok) {
      throw new Error(payload.error || 'The request could not be completed.');
    }

    const output = payload.output || 'Done.';
    updateAssistantMessage(activeAssistantMessage, output, {
      error: payload.ok === false,
      durationMs: payload.durationMs
    });
    setStatus(payload.ok === false ? 'Issue' : 'Ready', payload.ok === false ? 'error' : 'live');
    lastActivityAt = Date.now();
    notifyDone(payload.ok === false ? 'Task needs attention' : 'Task finished', output.slice(0, 120));
  } catch (error) {
    const wasAbort = error.name === 'AbortError';
    updateAssistantMessage(activeAssistantMessage, wasAbort ? 'Stopped.' : error.message, { error: !wasAbort });
    setStatus(wasAbort ? 'Stopped' : 'Issue', wasAbort ? '' : 'error');
  } finally {
    activeRequest = null;
    activeAssistantMessage = null;
    setComposerBusy(false);
    scrollToLatest();
  }
}

function appendUserMessage(text, attachments = []) {
  emptyState.classList.add('hidden');
  clearButton.classList.remove('hidden');
  const article = document.createElement('article');
  article.className = 'message-row user-row';
  article.innerHTML = [
    '<div class="message user-message">',
    `<p>${escapeHtml(text)}</p>`,
    renderAttachmentPreviews(attachments),
    '</div>'
  ].join('');
  chatLog.append(article);
  scrollToLatest();
}

async function uploadAttachments(files) {
  setStatus('Attaching', 'busy');
  attachButton.disabled = true;

  try {
    const images = await Promise.all(files.slice(0, 6).map(async (file) => ({
      name: file.name,
      type: file.type,
      data: await readAsDataUrl(file),
      previewUrl: URL.createObjectURL(file)
    })));

    const response = await fetch('/api/attachments', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...authHeaders()
      },
      body: JSON.stringify({
        images: images.map(({ name, type, data }) => ({ name, type, data }))
      })
    });

    const payload = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(payload.error || 'Images could not be attached.');

    pendingAttachments = [
      ...pendingAttachments,
      ...payload.attachments.map((attachment, index) => ({
        ...attachment,
        previewUrl: images[index]?.previewUrl
      }))
    ].slice(0, 6);
    renderAttachmentTray();
    setStatus('Ready', 'live');
  } catch (error) {
    setStatus('Issue', 'error');
    appendAssistantMessage(error.message, { error: true });
  } finally {
    attachButton.disabled = false;
    promptInput.focus();
  }
}

function renderAttachmentTray() {
  attachmentTray.replaceChildren();
  attachmentTray.classList.toggle('hidden', pendingAttachments.length === 0);

  for (const attachment of pendingAttachments) {
    const item = document.createElement('article');
    item.className = 'attachment-item';
    item.innerHTML = [
      `<img src="${attachment.previewUrl}" alt="">`,
      `<span>${escapeHtml(attachment.name)}</span>`,
      `<button type="button" aria-label="Remove ${escapeHtml(attachment.name)}">&times;</button>`
    ].join('');
    item.querySelector('button').addEventListener('click', () => {
      pendingAttachments = pendingAttachments.filter((current) => current.path !== attachment.path);
      URL.revokeObjectURL(attachment.previewUrl);
      renderAttachmentTray();
    });
    attachmentTray.append(item);
  }
}

function renderAttachmentPreviews(attachments = []) {
  if (attachments.length === 0) return '';
  return [
    '<div class="message-attachments">',
    ...attachments.map((attachment) => [
      '<figure>',
      `<img src="${attachment.previewUrl}" alt="">`,
      `<figcaption>${escapeHtml(attachment.name)}</figcaption>`,
      '</figure>'
    ].join('')),
    '</div>'
  ].join('');
}

function buildPrompt(prompt, attachments = []) {
  if (attachments.length === 0) return prompt;
  const text = prompt.trim() || 'Please analyze the attached image.';
  const paths = attachments.map((attachment, index) => `${index + 1}. ${attachment.path}`).join('\n');
  return [
    text,
    '',
    'Attached image files available on this Mac:',
    paths,
    '',
    'Use the attached image file paths as visual context for this request.'
  ].join('\n');
}

function clearAttachments() {
  for (const attachment of pendingAttachments) {
    if (attachment.previewUrl) URL.revokeObjectURL(attachment.previewUrl);
  }
  pendingAttachments = [];
  renderAttachmentTray();
}

function readAsDataUrl(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.addEventListener('load', () => resolve(reader.result));
    reader.addEventListener('error', () => reject(new Error('Could not read image.')));
    reader.readAsDataURL(file);
  });
}

function appendAssistantMessage(markdown, options = {}) {
  emptyState.classList.add('hidden');
  clearButton.classList.remove('hidden');
  const article = document.createElement('article');
  article.className = 'message-row assistant-row';
  article.innerHTML = [
    '<div class="message assistant-message">',
    options.thinking ? '<div class="thinking-dots" aria-label="Working"><span></span><span></span><span></span></div>' : renderMarkdown(markdown),
    '</div>',
    '<div class="message-actions">',
    actionButton('Copy', 'copy'),
    actionButton('Good response', 'up'),
    actionButton('Bad response', 'down'),
    actionButton('Read aloud', 'speak'),
    actionButton('More', 'more'),
    '</div>'
  ].join('');

  wireMessageActions(article, markdown);
  chatLog.append(article);
  scrollToLatest();
  return article;
}

function updateAssistantMessage(article, markdown, options = {}) {
  if (!article) return;
  const message = article.querySelector('.assistant-message');
  message.classList.toggle('error-message', Boolean(options.error));
  message.innerHTML = renderMarkdown(markdown);
  article.dataset.raw = markdown;
  wireMessageActions(article, markdown);
}

function wireMessageActions(article, rawText = '') {
  article.dataset.raw = rawText;
  const copyButton = article.querySelector('[data-action="copy"]');
  if (copyButton) copyButton.onclick = async () => {
    const button = article.querySelector('[data-action="copy"]');
    const text = article.dataset.raw || article.querySelector('.assistant-message')?.innerText || '';
    const copied = await copyText(text);
    if (!copied) selectMessageText(article);
    flashAction(button, copied ? 'Copied' : 'Selected');
  };

  const upButton = article.querySelector('[data-action="up"]');
  if (upButton) upButton.onclick = () => {
    setFeedback(article, 'up');
  };

  const downButton = article.querySelector('[data-action="down"]');
  if (downButton) downButton.onclick = () => {
    setFeedback(article, 'down');
  };

  const speakButton = article.querySelector('[data-action="speak"]');
  if (speakButton) speakButton.onclick = () => {
    const button = article.querySelector('[data-action="speak"]');
    const text = article.dataset.raw || article.querySelector('.assistant-message')?.innerText || '';
    if (!('speechSynthesis' in window) || !text.trim()) {
      flashAction(button, 'Unavailable');
      return;
    }
    window.speechSynthesis.cancel();
    window.speechSynthesis.speak(new SpeechSynthesisUtterance(text));
    flashAction(button, 'Reading');
  };

  const moreButton = article.querySelector('[data-action="more"]');
  if (moreButton) moreButton.onclick = () => {
    const text = article.dataset.raw || '';
    promptInput.value = `Follow up on this response:\n\n${text}`.trim();
    resizeComposer();
    promptInput.focus();
  };
}

async function copyText(text) {
  if (!text.trim()) return false;
  try {
    const fallback = document.createElement('textarea');
    fallback.value = text;
    fallback.readOnly = true;
    fallback.style.position = 'fixed';
    fallback.style.inset = '0 auto auto 0';
    fallback.style.width = '1px';
    fallback.style.height = '1px';
    fallback.style.opacity = '0.001';
    fallback.style.pointerEvents = 'none';
    document.body.append(fallback);
    fallback.focus();
    fallback.select();
    fallback.setSelectionRange(0, fallback.value.length);
    const copied = document.execCommand('copy');
    fallback.remove();
    if (copied) return true;
  } catch {}

  try {
    if (!navigator.clipboard?.writeText) return false;
    await navigator.clipboard.writeText(text);
    return true;
  } catch {
    return false;
  }
}

function flashAction(button, label) {
  if (!button) return;
  const original = button.getAttribute('aria-label') || '';
  button.setAttribute('aria-label', label);
  button.dataset.tooltip = label;
  button.classList.add('is-confirmed');
  window.setTimeout(() => {
    button.setAttribute('aria-label', original);
    delete button.dataset.tooltip;
    button.classList.remove('is-confirmed');
  }, 1200);
}

function selectMessageText(article) {
  const message = article.querySelector('.assistant-message');
  if (!message || !window.getSelection) return;
  const range = document.createRange();
  range.selectNodeContents(message);
  const selection = window.getSelection();
  selection.removeAllRanges();
  selection.addRange(range);
}

function setFeedback(article, action) {
  for (const button of article.querySelectorAll('[data-action="up"], [data-action="down"]')) {
    const selected = button.dataset.action === action && button.getAttribute('aria-pressed') !== 'true';
    button.setAttribute('aria-pressed', String(selected));
    button.classList.toggle('is-selected', selected);
    if (selected) flashAction(button, action === 'up' ? 'Liked' : 'Disliked');
  }
}

function actionButton(label, action) {
  const icons = {
    copy: '<path d="M8 8h10v10H8z" /><path d="M6 16H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1" />',
    up: '<path d="M7 10v11" /><path d="M15 6.5 14 10h4.8a2.2 2.2 0 0 1 2.15 2.65l-1.15 5.4A3 3 0 0 1 16.85 20.5H7" /><path d="M7 10H4.5A1.5 1.5 0 0 0 3 11.5v7A1.5 1.5 0 0 0 4.5 20H7" /><path d="M14 10V5.5a2 2 0 0 0-2-2L8 10" />',
    down: '<path d="M7 14V3" /><path d="M15 17.5 14 14h4.8a2.2 2.2 0 0 0 2.15-2.65l-1.15-5.4A3 3 0 0 0 16.85 3.5H7" /><path d="M7 14H4.5A1.5 1.5 0 0 1 3 12.5v-7A1.5 1.5 0 0 1 4.5 4H7" /><path d="M14 14v4.5a2 2 0 0 1-2 2L8 14" />',
    speak: '<path d="M4 10v4h4l5 4V6l-5 4z" /><path d="M17 9.5a4 4 0 0 1 0 5" />',
    more: '<path d="M12 5.5h.01M12 12h.01M12 18.5h.01" />'
  };
  const pressed = action === 'up' || action === 'down' ? ' aria-pressed="false"' : '';
  return `<button type="button" data-action="${action}" aria-label="${label}"${pressed}><svg viewBox="0 0 24 24" aria-hidden="true">${icons[action]}</svg></button>`;
}

function renderMarkdown(markdown = '') {
  const normalized = markdown.replace(/\r\n?/g, '\n').trim();
  if (!normalized) return '';

  const blocks = [];
  const fencePattern = /```([a-zA-Z0-9_-]*)\n?([\s\S]*?)```/g;
  let index = 0;
  let match;

  while ((match = fencePattern.exec(normalized))) {
    if (match.index > index) blocks.push(renderMarkdownText(normalized.slice(index, match.index)));
    const language = match[1] ? `<span>${escapeHtml(match[1])}</span>` : '';
    blocks.push(`<figure class="code-block"><figcaption>${language}</figcaption><pre><code>${escapeHtml(match[2].trim())}</code></pre></figure>`);
    index = match.index + match[0].length;
  }

  if (index < normalized.length) blocks.push(renderMarkdownText(normalized.slice(index)));
  return blocks.join('');
}

function renderMarkdownText(markdown) {
  const lines = markdown.split('\n');
  const html = [];
  let paragraph = [];
  let list = [];
  let ordered = false;
  let quote = [];

  const flushParagraph = () => {
    if (!paragraph.length) return;
    html.push(`<p>${renderInline(paragraph.join(' '))}</p>`);
    paragraph = [];
  };

  const flushList = () => {
    if (!list.length) return;
    const tag = ordered ? 'ol' : 'ul';
    html.push(`<${tag}>${list.map((item) => `<li>${renderInline(item)}</li>`).join('')}</${tag}>`);
    list = [];
  };

  const flushQuote = () => {
    if (!quote.length) return;
    html.push(`<blockquote>${quote.map((item) => `<p>${renderInline(item)}</p>`).join('')}</blockquote>`);
    quote = [];
  };

  for (const line of lines) {
    const trimmed = line.trim();
    if (!trimmed) {
      flushParagraph();
      flushList();
      flushQuote();
      continue;
    }

    const heading = trimmed.match(/^(#{1,3})\s+(.+)$/);
    if (heading) {
      flushParagraph();
      flushList();
      flushQuote();
      const level = heading[1].length + 1;
      html.push(`<h${level}>${renderInline(heading[2])}</h${level}>`);
      continue;
    }

    if (/^---+$/.test(trimmed)) {
      flushParagraph();
      flushList();
      flushQuote();
      html.push('<hr>');
      continue;
    }

    const unorderedItem = trimmed.match(/^[-*]\s+(.+)$/);
    const orderedItem = trimmed.match(/^\d+[.)]\s+(.+)$/);
    if (unorderedItem || orderedItem) {
      flushParagraph();
      flushQuote();
      const nextOrdered = Boolean(orderedItem);
      if (list.length && ordered !== nextOrdered) flushList();
      ordered = nextOrdered;
      list.push((unorderedItem || orderedItem)[1]);
      continue;
    }

    const quoteLine = trimmed.match(/^>\s?(.+)$/);
    if (quoteLine) {
      flushParagraph();
      flushList();
      quote.push(quoteLine[1]);
      continue;
    }

    flushList();
    flushQuote();
    paragraph.push(trimmed);
  }

  flushParagraph();
  flushList();
  flushQuote();
  return html.join('');
}

function renderInline(value) {
  const code = [];
  let html = value.replace(/`([^`]+)`/g, (_, snippet) => {
    code.push(`<code>${escapeHtml(snippet)}</code>`);
    return `\u0000${code.length - 1}\u0000`;
  });

  html = escapeHtml(html)
    .replace(/\[([^\]]+)]\(([^)\s]+)\)/g, (_, label, href) => {
      const safeHref = sanitizeUrl(href);
      return `<a href="${safeHref}" target="_blank" rel="noreferrer">${label}</a>`;
    })
    .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
    .replace(/\*([^*]+)\*/g, '<em>$1</em>');

  return html.replace(/\u0000(\d+)\u0000/g, (_, position) => code[Number(position)] || '');
}

function sanitizeUrl(url = '') {
  try {
    const parsed = new URL(url, location.origin);
    if (['http:', 'https:', 'mailto:'].includes(parsed.protocol)) return parsed.href;
  } catch {}
  return '#';
}

async function loadSlashCommands() {
  if (slashCommands.length > 0) return;
  try {
    const response = await fetch('/api/slash-commands', {
      headers: authHeaders()
    });
    if (!response.ok) return;
    const payload = await response.json();
    slashCommands = Array.isArray(payload.commands) ? payload.commands : [];
  } catch {
    slashCommands = [];
  }
}

async function loadMentions(query = '') {
  try {
    const params = new URLSearchParams();
    if (query) params.set('q', query);
    if (selectedProject.path) params.set('cwd', selectedProject.path);
    const response = await fetch(`/api/mentions?${params.toString()}`, {
      headers: authHeaders()
    });
    if (!response.ok) return;
    const payload = await response.json();
    mentionItems = Array.isArray(payload.mentions) ? payload.mentions : [];
  } catch {
    mentionItems = [];
  }
}

async function loadProjects({ force = false } = {}) {
  if (projectsLoaded && !force) return projects;
  renderProjectsLoading();
  try {
    const response = await fetch('/api/projects', {
      headers: authHeaders()
    });
    const payload = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(payload.error || 'Could not load projects.');
    projects = Array.isArray(payload.projects) ? payload.projects : [];
    projectsLoaded = true;
    reconcileSelectedProject();
    renderProjects();
    return projects;
  } catch (error) {
    renderProjectsError(error.message);
    return [];
  }
}

function openProjects() {
  if (!deviceToken) return;
  projectDrawer.classList.add('is-open');
  projectDrawer.setAttribute('aria-hidden', 'false');
  updateActiveProjectUi();
  loadProjects({ force: !projectsLoaded });
  requestAnimationFrame(() => projectPanel?.focus());
}

function closeProjects() {
  projectDrawer.classList.remove('is-open');
  projectDrawer.setAttribute('aria-hidden', 'true');
  sidebarButton?.focus();
}

function reconcileSelectedProject() {
  if (!selectedProject.path) return;
  const match = projects.find((project) => project.path === selectedProject.path);
  if (!match) return;
  selectedProject = {
    name: match.name || selectedProject.name,
    path: match.path
  };
  persistSelectedProject();
  updateActiveProjectUi();
}

function selectProject(project) {
  selectedProject = {
    name: project.name || 'Default workspace',
    path: project.path || ''
  };
  persistSelectedProject();
  mentionItems = [];
  updateActiveProjectUi();
  renderProjects();
  closeProjects();
  setStatus('Ready', 'live');
  promptInput.placeholder = `Message ${selectedProject.name}...`;
  promptInput.focus();
}

function persistSelectedProject() {
  if (!selectedProject.path) {
    localStorage.removeItem('codexRelayProjectPath');
    localStorage.removeItem('codexRelayProjectName');
    return;
  }
  localStorage.setItem('codexRelayProjectPath', selectedProject.path);
  localStorage.setItem('codexRelayProjectName', selectedProject.name);
}

function updateActiveProjectUi() {
  const label = selectedProject.name || 'Default workspace';
  const path = selectedProject.path || 'Commands run in the server default workspace';
  workdirEl.textContent = label;
  workspaceName.textContent = label;
  promptInput.placeholder = `Message ${label}...`;
}

function renderProjectsLoading() {
  if (!projectList) return;
  projectList.innerHTML = [
    '<article class="project-state">',
    '<span class="project-skeleton"></span>',
    '<span class="project-skeleton short"></span>',
    '</article>',
    '<article class="project-state">',
    '<span class="project-skeleton"></span>',
    '<span class="project-skeleton short"></span>',
    '</article>'
  ].join('');
}

function renderProjectsError(message) {
  if (!projectList) return;
  projectList.innerHTML = [
    '<article class="project-state">',
    '<strong>Projects unavailable</strong>',
    `<p>${escapeHtml(message || 'Could not load projects from the Mac.')}</p>`,
    '</article>'
  ].join('');
}

function renderProjects() {
  if (!projectList) return;
  projectList.replaceChildren();
  projectList.append(projectButton({
    name: 'Default workspace',
    path: '',
    source: 'default',
    detail: 'Use the server default workspace'
  }));

  if (projects.length === 0) {
    projectList.insertAdjacentHTML('beforeend', [
      '<article class="project-state">',
      '<strong>No projects found</strong>',
      '<p>Codex Relay could not find project folders yet.</p>',
      '</article>'
    ].join(''));
    return;
  }

  for (const project of projects) {
    projectList.append(projectButton(project));
  }
}

function projectButton(project) {
  const button = document.createElement('button');
  const isActive = (project.path || '') === selectedProject.path;
  button.className = `project-item${isActive ? ' is-active' : ''}`;
  button.type = 'button';
  button.setAttribute('aria-pressed', String(isActive));
  button.innerHTML = [
    '<span class="project-icon" aria-hidden="true">',
    project.source === 'codex' ? terminalGlyph() : folderGlyph(),
    '</span>',
    '<span class="project-copy">',
    `<strong>${escapeHtml(project.name || 'Untitled')}</strong>`,
    `<small>${escapeHtml(project.detail || projectDetail(project))}</small>`,
    '</span>',
    isActive ? '<span class="project-check" aria-hidden="true">Selected</span>' : ''
  ].join('');
  button.addEventListener('click', () => selectProject(project));
  return button;
}

function projectDetail(project) {
  const bits = [];
  if (project.source === 'codex') bits.push('Codex');
  if (Number(project.threadCount) > 0) bits.push(`${project.threadCount} thread${project.threadCount === 1 ? '' : 's'}`);
  if (Array.isArray(project.tags) && project.tags.length > 0) bits.push(project.tags.slice(0, 2).join(' · '));
  bits.push(shortPath(project.path || project.parent || ''));
  return bits.filter(Boolean).join(' · ');
}

function shortPath(value = '') {
  const text = String(value);
  if (text.length <= 48) return text;
  const parts = text.split('/');
  if (parts.length <= 3) return text;
  return `.../${parts.slice(-3).join('/')}`;
}

function folderGlyph() {
  return '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 7.5A2.5 2.5 0 0 1 6.5 5H10l2 2h5.5A2.5 2.5 0 0 1 20 9.5v7A2.5 2.5 0 0 1 17.5 19h-11A2.5 2.5 0 0 1 4 16.5z" /></svg>';
}

function terminalGlyph() {
  return '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="m8 8 4 4-4 4" /><path d="M14 16h4" /></svg>';
}

function updatePalette() {
  const value = promptInput.value.trimStart();
  const trigger = getActiveTrigger(value);
  if (!trigger) {
    hidePalette();
    return;
  }

  if (trigger.type === '/') {
    loadSlashCommands().then(() => {
      const matches = slashCommands.filter((command) => command.name.slice(1).toLowerCase().startsWith(trigger.query));
      paletteTitle.textContent = 'Slash commands';
      renderPalette(matches.map((command) => ({
        label: command.name,
        detail: command.description,
        insertText: `${command.name} `
      })));
      slashPanel.classList.toggle('hidden', matches.length === 0);
    });
    return;
  }

  loadMentions(trigger.query).then(() => {
    const matches = mentionItems.filter((item) => item.path.toLowerCase().includes(trigger.query));
    paletteTitle.textContent = '@ plugins and files';
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
      resizeComposer();
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

function insertAtCursor(value) {
  const start = promptInput.selectionStart || 0;
  const end = promptInput.selectionEnd || 0;
  promptInput.value = `${promptInput.value.slice(0, start)}${value}${promptInput.value.slice(end)}`;
  promptInput.selectionStart = promptInput.selectionEnd = start + value.length;
  resizeComposer();
  promptInput.focus();
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

function setStatus(label, state = '') {
  statusEl.textContent = label;
  statusEl.classList.toggle('live', state === 'live');
  statusEl.classList.toggle('busy', state === 'busy');
  statusEl.classList.toggle('error', state === 'error');
}

function setConnecting(isConnecting) {
  if (!connectButton) return;
  connectButton.disabled = isConnecting;
  connectButton.innerHTML = isConnecting
    ? '<span>Pairing</span><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 5v14M5 12h14" /></svg>'
    : '<span>Connect to Codex</span><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M5 12h14M13 6l6 6-6 6" /></svg>';
}

function setComposerBusy(isBusy) {
  promptInput.disabled = isBusy;
  composer.classList.toggle('is-busy', isBusy);
}

function setFormError(message) {
  if (!loginError || !pairingCodeInput) return;
  loginError.textContent = message;
  pairingCodeInput.setAttribute('aria-invalid', message ? 'true' : 'false');
}

function authHeaders() {
  return { Authorization: `Bearer ${deviceToken}` };
}

function resizeComposer() {
  promptInput.style.height = 'auto';
  promptInput.style.height = `${Math.min(promptInput.scrollHeight, 128)}px`;
}

function scrollToLatest() {
  requestAnimationFrame(() => {
    chatLog.scrollTo({ top: chatLog.scrollHeight, behavior: 'smooth' });
  });
}

function escapeHtml(value = '') {
  return String(value).replace(/[&<>"']/g, (char) => ({
    '&': '&amp;',
    '<': '&lt;',
    '>': '&gt;',
    '"': '&quot;',
    "'": '&#39;'
  })[char]);
}
