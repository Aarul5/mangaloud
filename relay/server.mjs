// PC relay for Manga Loud: the phone sends image slices, this runs `claude -p` on them
// with your Claude subscription, and sends the bubbles back. No dependencies.
//
//   node server.mjs            (PORT, RELAY_TOKEN and CLAUDE_BIN env vars are optional)

import { execFileSync, spawn } from 'node:child_process';
import { createHash, randomBytes, timingSafeEqual } from 'node:crypto';
import { existsSync, mkdirSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { mkdtemp, rm, writeFile } from 'node:fs/promises';
import { createServer } from 'node:http';
import { hostname, networkInterfaces, tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { castListFor, speak, updateCastEntry, validateSpeak } from './voice.mjs';

const PORT = Number(process.env.PORT || 8787);
const CLAUDE_BIN = process.env.CLAUDE_BIN || 'claude';
const MAX_BODY_BYTES = 30 * 1024 * 1024;
const MAX_IMAGES = 8;
// claude -p --effort levels. Blank leaves claude's own default.
const EFFORTS = ['low', 'medium', 'high', 'xhigh', 'max'];
const CLAUDE_TIMEOUT_MS = 4 * 60 * 1000;
const EXTENSIONS = { 'image/jpeg': 'jpg', 'image/png': 'png', 'image/webp': 'webp' };
// Reading guides ("skills") the app can ask for by name, kept in relay/skills/<name>.md. Edit one
// and the next request uses it: no app update needed.
const GUIDE_NAME = /^[a-z0-9-]{1,40}$/;
// The last few requests are kept in relay/debug (slices, request and reply), so a missed or
// misread bubble can be traced to the slice and the answer. DEBUG_KEEP=0 turns it off.
const DEBUG_KEEP = Number(process.env.DEBUG_KEEP ?? 40);
// Guides read in place of the one the app asks for. read-comic is swapped for the short prompt the
// app used before the guide (plus the fields the app now needs) while the full guide is worked
// on. Delete the line to turn the full guide back on.
const GUIDE_SWAP = { 'read-comic': 'read-comic-basic' };

const here = dirname(fileURLToPath(import.meta.url));
const TOKEN = process.env.RELAY_TOKEN || loadOrCreateToken(join(here, '.token'));

function loadOrCreateToken(path) {
  if (existsSync(path)) return readFileSync(path, 'utf8').trim();
  const token = randomBytes(18).toString('base64url');
  // Readable by your user only on macOS and Linux.
  writeFileSync(path, token + '\n', { mode: 0o600 });
  return token;
}

function isAuthorised(req) {
  const given = Buffer.from((req.headers.authorization || '').replace(/^Bearer\s+/i, ''));
  const expected = Buffer.from(TOKEN);
  return given.length === expected.length && timingSafeEqual(given, expected);
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on('data', (chunk) => {
      size += chunk.length;
      if (size > MAX_BODY_BYTES) {
        reject(Object.assign(new Error('Request too large'), { status: 413 }));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    req.on('error', reject);
  });
}

function validate(body) {
  const { prompt, schema, model, effort, images, guide } = body ?? {};
  const fail = (message) => { throw Object.assign(new Error(message), { status: 400 }); };
  if (typeof prompt !== 'string' || prompt.length === 0 || prompt.length > 10_000) fail('prompt must be a string under 10k characters');
  if (typeof schema !== 'object' || schema === null) fail('schema must be an object');
  if (model !== undefined && (typeof model !== 'string' || !/^[\w.\-]{0,64}$/.test(model))) fail('model is not a valid model name');
  if (effort !== undefined && effort !== '' && !EFFORTS.includes(effort)) fail(`effort must be one of ${EFFORTS.join(', ')}`);
  if (guide !== undefined && guide !== '' && (typeof guide !== 'string' || !GUIDE_NAME.test(guide))) fail('guide must be a short name like read-comic');
  if (!Array.isArray(images) || images.length === 0 || images.length > MAX_IMAGES) fail(`images must be 1 to ${MAX_IMAGES} items`);
  for (const image of images) {
    if (!image || typeof image.data !== 'string' || !EXTENSIONS[image.mediaType]) fail('each image needs data and a jpeg, png or webp mediaType');
  }
  return { prompt, schema, model: model || '', effort: effort || '', images, guide: guide || '' };
}

/** Runs one `claude -p` call. Only the Read tool is allowed, confined to the temp folder. */
function runClaude({ prompt, schema, model, effort }, workDir, fileNames) {
  const fileList = fileNames.map((name, i) => `${name} (slice ${i})`).join(', ');
  const fullPrompt = `${prompt}\n\nThe slices are image files in the current folder: ${fileList}. ` +
    'Use the Read tool to look at each one, then reply with only the JSON.';

  const args = [
    '-p',
    '--output-format', 'json',
    '--json-schema', JSON.stringify(schema),
    '--tools', 'Read',
    '--allowedTools', 'Read',
    '--restricted',
    '--strict-mcp-config',
    '--disable-slash-commands',
    '--no-session-persistence',
  ];
  if (model) args.push('--model', model);
  if (effort) args.push('--effort', effort);

  return new Promise((resolve, reject) => {
    const child = spawn(CLAUDE_BIN, args, { cwd: workDir, windowsHide: true });
    let stdout = '';
    let stderr = '';
    const timer = setTimeout(() => {
      child.kill();
      reject(Object.assign(new Error('claude -p timed out'), { status: 504 }));
    }, CLAUDE_TIMEOUT_MS);

    child.stdout.on('data', (d) => { stdout += d; });
    child.stderr.on('data', (d) => { stderr += d; });
    child.on('error', (err) => {
      clearTimeout(timer);
      reject(Object.assign(new Error(`Could not start ${CLAUDE_BIN}: ${err.message}`), { status: 500 }));
    });
    child.on('close', (code) => {
      clearTimeout(timer);
      try {
        resolve(parseClaudeOutput(stdout));
      } catch (err) {
        const detail = (stderr || stdout).trim().slice(0, 300);
        if (/Failed to authenticate|OAuth|not logged in|\/login/i.test(stderr + stdout)) {
          reject(Object.assign(new Error('claude on this PC is signed out. Run `claude` there and sign in again.'), { status: 502 }));
          return;
        }
        reject(Object.assign(new Error(`claude -p failed (exit ${code}): ${err.message}. ${detail}`), { status: 502 }));
      }
    });
    child.stdin.end(fullPrompt);
  });
}

/** The bubbles, plus the model that did the reading (the one with the most output). */
function parseClaudeOutput(stdout) {
  const result = JSON.parse(stdout);
  if (result.is_error) throw new Error(result.result || result.subtype || 'claude reported an error');
  const usage = result.modelUsage && typeof result.modelUsage === 'object' ? Object.entries(result.modelUsage) : [];
  const model = usage.sort(([, a], [, b]) => (b?.outputTokens || 0) - (a?.outputTokens || 0))[0]?.[0] || '';
  if (result.structured_output && typeof result.structured_output === 'object') return { reply: result.structured_output, model };
  const text = String(result.result ?? '');
  const start = text.indexOf('{');
  const end = text.lastIndexOf('}');
  if (start < 0 || end <= start) throw new Error('reply had no JSON');
  return { reply: JSON.parse(text.slice(start, end + 1)), model };
}

/** The named guide's text and a short id of its contents, read fresh so edits apply at once. */
function loadGuide(name) {
  const path = join(here, 'skills', `${name}.md`);
  if (!existsSync(path)) throw Object.assign(new Error(`No reading guide called ${name} in relay/skills`), { status: 500 });
  const text = readFileSync(path, 'utf8');
  return { text, id: `${name}@${createHash('sha256').update(text).digest('hex').slice(0, 8)}` };
}

async function handleOcr(body) {
  const request = validate(body);
  const guide = request.guide ? loadGuide(GUIDE_SWAP[request.guide] || request.guide) : null;
  // The guide says how to read; the app's part says what this batch is (cast, lines before it).
  const prompt = guide ? `${guide.text}\n\n# This request\n\n${request.prompt}` : request.prompt;
  const workDir = await mkdtemp(join(tmpdir(), 'manga-relay-'));
  try {
    const fileNames = await Promise.all(request.images.map(async (image, i) => {
      const name = `slice_${i}.${EXTENSIONS[image.mediaType]}`;
      await writeFile(join(workDir, name), Buffer.from(image.data, 'base64'));
      return name;
    }));
    const result = await runClaude({ ...request, prompt }, workDir, fileNames);
    return { ...result, guide: guide?.id || '' };
  } finally {
    await rm(workDir, { recursive: true, force: true });
  }
}

let debugCount = 0;
/** Keeps one request's slices, the app's part of the prompt and the reply in relay/debug. */
function keepForDebug(body, reply, how) {
  if (!(DEBUG_KEEP > 0)) return;
  try {
    const root = join(here, 'debug');
    const stamp = new Date().toISOString().replace(/[:.]/g, '-');
    const dir = join(root, `${stamp}-${++debugCount}`);
    mkdirSync(dir, { recursive: true });
    body.images.forEach((image, i) => {
      writeFileSync(join(dir, `slice_${i}.${EXTENSIONS[image.mediaType] || 'jpg'}`), Buffer.from(image.data, 'base64'));
    });
    writeFileSync(join(dir, 'request.txt'), `${how}\nguide: ${body.guide || '(none)'}\n\n${body.prompt}\n`);
    writeFileSync(join(dir, 'reply.json'), JSON.stringify(reply, null, 2));
    const old = readdirSync(root).sort();
    for (const name of old.slice(0, Math.max(0, old.length - DEBUG_KEEP))) rmSync(join(root, name), { recursive: true, force: true });
  } catch (err) {
    console.error(`Could not keep the request for debugging: ${err.message}`);
  }
}

/** Runs up to `limit` tasks at once; the rest wait their turn in the order they arrive. */
function makeQueue(limit = 1) {
  let running = 0;
  const waiting = [];
  const startNext = () => {
    if (running >= limit || waiting.length === 0) return;
    const { task, resolve, reject } = waiting.shift();
    running++;
    Promise.resolve()
      .then(task)
      .then(resolve, reject)
      .finally(() => {
        running--;
        startNext();
      });
  };
  return (task) =>
    new Promise((resolve, reject) => {
      waiting.push({ task, resolve, reject });
      startNext();
    });
}

// Three claude calls at once: the phone keeps two batches in flight, and the third slot lets a
// read started on opening a page (or the next chapter read early) run without holding those up.
// Capped there so a fast reader does not burn through the plan's limits.
const OCR_PARALLEL = 3;
const enqueue = makeQueue(OCR_PARALLEL);
// One voice clip at a time, on its own queue so reading text never waits behind speech.
const enqueueVoice = makeQueue();
// Fish Audio runs in the cloud, not on this PC's GPU, so its lines skip the GPU queue. Three at
// a time stays under its starter limit of five requests at once.
const enqueueCloudVoice = makeQueue(3);

function send(res, status, payload) {
  res.writeHead(status, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify(payload));
}

async function handleSpeak(req, res) {
  const started = Date.now();
  try {
    const request = validateSpeak(JSON.parse(await readBody(req)));
    const queue = request.model === 'fish' ? enqueueCloudVoice : enqueueVoice;
    const { audio, cached, voice, model } = await queue(() => speak(request));
    const seconds = ((Date.now() - started) / 1000).toFixed(1);
    const who = request.character ? `${request.character} (${voice})` : voice;
    const how = model === 'omnivoice' ? request.quality : model;
    console.log(`[${new Date().toLocaleTimeString()}] voice ${how} ${who}/${request.tone}, ${cached ? 'cached' : seconds + 's'}`);
    res.writeHead(200, { 'Content-Type': 'audio/wav', 'Content-Length': audio.length });
    res.end(audio);
  } catch (err) {
    const status = err instanceof SyntaxError ? 400 : err.status || 500;
    console.error(`[${new Date().toLocaleTimeString()}] voice ${status} ${err.message}`);
    send(res, status, { error: err.message });
  }
}

/** GET /cast?series=… lists a series' characters; POST /cast changes one character's voice. */
async function handleCast(req, res, url) {
  try {
    if (req.method === 'GET') return send(res, 200, castListFor(url.searchParams.get('series')));
    const body = JSON.parse(await readBody(req));
    return send(res, 200, updateCastEntry(body));
  } catch (err) {
    const status = err instanceof SyntaxError ? 400 : err.status || 500;
    console.error(`[${new Date().toLocaleTimeString()}] cast ${status} ${err.message}`);
    send(res, status, { error: err.message });
  }
}

const server = createServer(async (req, res) => {
  const url = new URL(req.url, 'http://relay');
  if (req.method === 'GET' && url.pathname === '/health') return send(res, 200, { ok: true });
  const known = (req.method === 'POST' && (url.pathname === '/ocr' || url.pathname === '/speak')) ||
    ((req.method === 'GET' || req.method === 'POST') && url.pathname === '/cast');
  if (!known) return send(res, 404, { error: 'Not found' });
  if (!isAuthorised(req)) return send(res, 401, { error: 'Wrong or missing token' });
  if (url.pathname === '/speak') return handleSpeak(req, res);
  if (url.pathname === '/cast') return handleCast(req, res, url);

  const started = Date.now();
  try {
    const body = JSON.parse(await readBody(req));
    const { reply, model, guide } = await enqueue(() => handleOcr(body));
    const seconds = Number(((Date.now() - started) / 1000).toFixed(1));
    const bubbles = (reply.slices || []).reduce((n, s) => n + (s.bubbles?.length || 0), 0);
    const how = `${model || body.model || 'default model'}, ${body.effort || 'default'} effort`;
    console.log(`[${new Date().toLocaleTimeString()}] ${body.images.length} slices, ${bubbles} bubbles, ${seconds}s (${how}${guide ? `, ${guide}` : ''})`);
    keepForDebug(body, reply, `${how}, ${seconds}s`);
    // meta lets the app show which model read the batch and how long it took, and that the
    // guide it asked for was used.
    send(res, 200, { ...reply, meta: { model: model || body.model || '', effort: body.effort || '', seconds, guide } });
  } catch (err) {
    const status = err instanceof SyntaxError ? 400 : err.status || 500;
    console.error(`[${new Date().toLocaleTimeString()}] ${status} ${err.message}`);
    send(res, status, { error: err.message });
  }
});

// Tailscale hands out addresses in 100.64.0.0/10.
function isTailscaleAddress(ip) {
  const [a, b] = ip.split('.').map(Number);
  return a === 100 && b >= 64 && b <= 127;
}

const TAILSCALE_BINS = [
  process.env.TAILSCALE_BIN,
  'tailscale',
  'C:\\Program Files\\Tailscale\\tailscale.exe',
  // The macOS app keeps its command inside the app.
  '/Applications/Tailscale.app/Contents/MacOS/Tailscale',
]
  .filter(Boolean);

// Asks Tailscale itself, because ISPs also hand out 100.64.0.0/10 addresses (carrier-grade NAT),
// so the address range alone does not prove an interface is Tailscale.
function tailscaleAddress() {
  for (const bin of TAILSCALE_BINS) {
    try {
      const ip = execFileSync(bin, ['ip', '-4'], { encoding: 'utf8', windowsHide: true, timeout: 5000 }).trim().split(/\s+/)[0];
      if (ip && isTailscaleAddress(ip)) return ip;
    } catch {
      // Not on PATH or not running; try the next location.
    }
  }
  return null;
}

// Listen on the Tailscale address only, so the token never crosses plain Wi-Fi.
// HOST=0.0.0.0 opts back into every network, which sends the token unencrypted.
function chooseHost() {
  if (process.env.HOST) return process.env.HOST;
  const ip = tailscaleAddress();
  if (!ip) {
    console.error('Tailscale is not running or not installed. Start it, or run with HOST=0.0.0.0 to use plain Wi-Fi.');
    process.exit(1);
  }
  return ip;
}

const HOST = chooseHost();

server.listen(PORT, HOST, () => {
  const addresses = HOST === '0.0.0.0'
    ? Object.values(networkInterfaces()).flat()
      .filter((a) => a && a.family === 'IPv4' && !a.internal)
      .map((a) => a.address)
    : [HOST];
  console.log('Manga Loud relay is running.');
  console.log('Relay address for the phone:', addresses.map((ip) => `http://${ip}:${PORT}`).join('  or  '));
  if (!addresses.every(isTailscaleAddress)) {
    console.log('Warning: listening on plain Wi-Fi too. The token is sent unencrypted on those addresses.');
  }
  console.log('Relay token:', TOKEN);
  // One link that fills in the relay on the phone. It holds the token, so it is written to a
  // git-ignored file next to .token and never leaves this PC except to your phone.
  const setupLink = 'mangaloud://setup?' + new URLSearchParams({
    name: process.env.RELAY_NAME || hostname(),
    url: `http://${addresses[0]}:${PORT}`,
    token: TOKEN,
  });
  writeFileSync(join(here, 'setup-link.txt'), setupLink + '\n', { mode: 0o600 });
  console.log('Phone setup link (saved in relay/setup-link.txt). Open it on the phone, or copy it and use');
  console.log('Settings > Paste setup link:', setupLink);
});
