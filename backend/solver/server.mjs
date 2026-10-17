// FitrahTube playback challenge solver (spec 2026-10-14 §4.5, R25-R27, R39, R40). Node built-ins only.
// YouTube-supplied JS runs in its own vm realm (no process, fetch or require) under a 10 s timeout, inside Node's
// permission model and the systemd unit's hardening. Challenges are never logged.
import fs from 'node:fs';
import http from 'node:http';
import path from 'node:path';
import vm from 'node:vm';
import { fileURLToPath, pathToFileURL } from 'node:url';

export const CHALLENGE = /^[A-Za-z0-9_=-]+$/;

const EJS_DIR = path.join(path.dirname(fileURLToPath(import.meta.url)), 'vendor', 'ejs-0.8.0');
const EJS_SOURCE = fs.readFileSync(path.join(EJS_DIR, 'lib.min.js'), 'utf8')
  + '\nObject.assign(globalThis, lib);\n'
  + fs.readFileSync(path.join(EJS_DIR, 'core.min.js'), 'utf8')
  + '\nglobalThis.jsc = jsc;';

/** A fresh realm holding EJS and nothing of Node's (R40). One per player, so eviction drops it whole. */
export function newContext(timeoutMs = 10_000) {
  const ctx = vm.createContext({}, { microtaskMode: 'afterEvaluate' }); // microtasks drain inside the timeout
  vm.runInContext(EJS_SOURCE, ctx, { timeout: timeoutMs });
  return ctx;
}

/** Preprocesses one base.js and pulls out its n/sig functions, entirely inside `ctx` under the vm timeout (R27). */
export function buildPlayer(ctx, playerText, timeoutMs = 10_000) {
  ctx.__player = playerText;
  let fns;
  try {
    fns = vm.runInContext(`(() => {
      const pre = jsc({ type: 'player', player: __player, requests: [], output_preprocessed: true }).preprocessed_player;
      const r = { n: null, sig: null };
      Function('_result', pre)(r);
      return r;
    })()`, ctx, { timeout: timeoutMs });
  } finally { delete ctx.__player; }
  if (typeof fns?.n !== 'function' || typeof fns?.sig !== 'function') throw new Error('no n/sig functions in this player');
  return { ctx, n: fns.n, sig: fns.sig };
}

const ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_';
const random = (length) => Array.from({ length }, () => ALPHABET[Math.floor(Math.random() * 64)]).join('');
/** yt-dlp's rule too: a player that cannot transform `n` returns `enhanced_except_…`, which is in the alphabet. */
const usable = (output) => typeof output === 'string' && CHALLENGE.test(output) && !output.startsWith('enhanced_except');
const changed = (output, input) => usable(output) && output !== input;

/** R26: 10 synthetic n (16 chars) and s (108 chars) must each come back changed and in the challenge alphabet.
 *  Catches identity functions and the `[object Object]` class NewPipe 0.26.5 hit. Runs under the vm timeout. */
export function selfTest(player, timeoutMs = 10_000) {
  const inputs = Array.from({ length: 10 }, () => [random(16), random(108)]);
  // Only the player's own (in-realm) functions and one JSON string cross in: no main-realm object is reachable (R40).
  player.ctx.__probeN = player.n;
  player.ctx.__probeS = player.sig;
  player.ctx.__probeIn = JSON.stringify(inputs);
  let outputs;
  try {
    outputs = JSON.parse(vm.runInContext('JSON.stringify(JSON.parse(__probeIn).map(([n, s]) => [__probeN(n), __probeS(s)]))', player.ctx, { timeout: timeoutMs }));
  } finally { delete player.ctx.__probeN; delete player.ctx.__probeS; delete player.ctx.__probeIn; }
  // distinct inputs must give distinct outputs, which rejects constant functions
  const distinct = (k) => new Set(outputs.map((o) => o[k])).size === outputs.length;
  return distinct(0) && distinct(1) && inputs.every(([n, s], i) => changed(outputs[i][0], n) && changed(outputs[i][1], s));
}

/** The held functions, run inside the realm under the same vm timeout as a build (R40), only strings crossing in and out.
 *  A challenge whose function throws or answers outside the alphabet is omitted. Throws on timeout. */
export function solveAll(player, n, s, timeoutMs = 10_000) {
  player.ctx.__solveN = player.n;
  player.ctx.__solveS = player.sig;
  player.ctx.__solveIn = JSON.stringify([n, s]);
  let raw;
  try {
    raw = JSON.parse(vm.runInContext(`(() => {
      const run = (fn, list) => list.map((c) => { try { const v = fn(c); return typeof v === 'string' ? v : null; } catch { return null; } });
      const [n, s] = JSON.parse(__solveIn);
      return JSON.stringify([run(__solveN, n), run(__solveS, s)]);
    })()`, player.ctx, { timeout: timeoutMs }));
  } finally { delete player.ctx.__solveN; delete player.ctx.__solveS; delete player.ctx.__solveIn; }
  const out = { n: {}, s: {} };
  [[n, raw[0], out.n], [s, raw[1], out.s]].forEach(([list, answers, into]) => {
    list.forEach((challenge, i) => { if (usable(answers[i])) into[challenge] = answers[i]; });
  });
  return out;
}

// ---------------------------------------------------------------- polling, fetch budget, HTTP (spec §4.5)
const HOUR_MS = 3_600_000;
const DAY_MS = 86_400_000;

/** The player hash in iframe_api's text, whose slashes arrive escaped. Exactly 8 lowercase hex (M12). */
export function playerHash(iframeApiText) {
  const match = /player\\?\/([0-9a-f]{8})\\?\//.exec(iframeApiText);
  return match ? match[1] : null;
}

/** sts from base.js, kept only within 60 days of `nowMs` (M13; 20738 = 2026-10-12). */
export function signatureTimestamp(playerText, nowMs) {
  const match = /(?:signatureTimestamp|sts)\s*:\s*(\d{5})/.exec(playerText);
  if (!match) return null;
  const sts = Number(match[1]);
  return Math.abs(sts - Math.floor(nowMs / DAY_MS)) <= 60 ? sts : null;
}

/** At most 2 held players (R25), single-flight polling, a persisted 3-an-hour download budget (R39).
 *  `build`, `now` and `log` are test seams; production passes none of them. */
export function createSolver({ origin = 'https://www.youtube.com', cacheDir, build = (text) => buildPlayer(newContext(), text),
                               now = Date.now, log = console, solveTimeoutMs = 10_000, selfTestTimeoutMs = 10_000 } = {}) {
  const held = new Map();                       // hash -> { sts, player }; insertion order is age
  const logFile = path.join(cacheDir, 'fetch-log.json');
  const cpuRing = [];
  let current = null, lastPoll = null, polling = null, throttled = false, logBroken = false, solves = 0, lastSlowWarn = -Infinity;
  let cacheWritable = probeCache();

  function probeCache() {
    try {
      const probe = path.join(cacheDir, '.probe');
      fs.writeFileSync(probe, '');
      fs.rmSync(probe);
      return true;
    } catch {
      return false;
    }
  }
  /** Missing = empty. Unreadable or corrupt throws and marks the log broken: no download, /health says no-cache (R39). */
  function fetchLog() {
    try {
      const log = JSON.parse(fs.readFileSync(logFile, 'utf8'));
      if (!Array.isArray(log)) throw new Error('fetch log is not a list');
      logBroken = false;
      return log;
    } catch (error) {
      if (error.code === 'ENOENT') { logBroken = false; return []; }
      logBroken = true;
      throw error;
    }
  }
  async function text(url, headers = {}) {
    const response = await fetch(url, { headers, signal: AbortSignal.timeout(20_000) });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    return response.text();
  }

  async function pollOnce() {
    let step = 'iframe_api';
    try {
      const hash = playerHash(await text(`${origin}/iframe_api`));
      step = 'hash';
      if (!hash) throw new Error('no player hash');
      if (held.has(hash)) { lastPoll = { at: now(), ok: true, step: 'held' }; return; }   // A/B never rebuilds (M10)
      step = 'cache';
      const file = path.join(cacheDir, `${hash}.js`);
      let playerText = fs.existsSync(file) ? fs.readFileSync(file, 'utf8') : null;
      if (playerText === null) {
        step = 'fetch-budget';
        cacheWritable = probeCache();
        if (!cacheWritable) throw new Error('cache directory not writable');
        const recent = fetchLog().filter((t) => now() - t < HOUR_MS);
        throttled = recent.length >= 3;
        if (throttled) throw new Error('3 downloads in the last hour');
        fs.writeFileSync(`${logFile}.tmp`, JSON.stringify([...recent, now()]));   // recorded BEFORE the download,
        fs.renameSync(`${logFile}.tmp`, logFile);                                   // atomically: a crash cannot corrupt it
        step = 'download';
        playerText = await text(`${origin}/s/player/${hash}/player_ias.vflset/en_US/base.js`, { 'accept-encoding': 'br, gzip' });
        fs.writeFileSync(file, playerText);
      }
      step = 'evict';
      if (held.size >= 2) held.delete(held.keys().next().value);          // BEFORE the build (R27)
      step = 'build';
      const cpu0 = process.cpuUsage();
      const player = build(playerText);
      step = 'sts';
      const sts = signatureTimestamp(playerText, now());
      if (sts === null) throw new Error('sts missing or more than 60 days from today');
      step = 'self-test';
      if (!selfTest(player, selfTestTimeoutMs)) throw new Error('self-test failed');
      held.set(hash, { sts, player });
      current = hash;
      const cpu = process.cpuUsage(cpu0);
      log.log(`adopted player ${hash} sts ${sts} cpu ${Math.round((cpu.user + cpu.system) / 1000)} ms`);
      lastPoll = { at: now(), ok: true, step: 'adopted' };
    } catch (error) {
      log.warn(`WARN poll failed at ${step}: ${error.message}`);
      lastPoll = { at: now(), ok: false, step };
    }
  }

  function p95() {
    if (cpuRing.length === 0) return 0;
    const sorted = [...cpuRing].sort((a, b) => a - b);
    return sorted[Math.ceil(0.95 * sorted.length) - 1];
  }

  function solve({ playerId, n, s }) {
    const entry = held.get(playerId);
    if (!entry) return [409, { error: 'unknown player' }];
    const cpu0 = process.cpuUsage();
    let answers;
    try {
      answers = solveAll(entry.player, n, s, solveTimeoutMs);
    } catch {                                   // a hung or broken player: evict it, others stay served (ruling 1)
      held.delete(playerId);
      if (current === playerId) current = held.keys().next().value ?? null;
      log.warn(`WARN solve failed for player ${playerId}; evicted`);
      return [500, { error: 'solve failed' }];
    }
    const cpu = process.cpuUsage(cpu0);
    cpuRing.push((cpu.user + cpu.system) / 1000);
    if (cpuRing.length > 1000) cpuRing.shift();
    solves++;
    // R30: p95 budget 2 ms, WARN above 5 ms at most once an hour. ponytail: checked every 100th solve, not every one.
    if (solves % 100 === 0 && p95() > 5 && now() - lastSlowWarn >= HOUR_MS) {
      lastSlowWarn = now();
      log.warn(`WARN solve CPU p95 ${p95().toFixed(2)} ms is over 5 ms`);
    }
    return [200, answers];
  }

  function health() {
    let fetchesLastHour = null;
    try { fetchesLastHour = fetchLog().filter((t) => now() - t < HOUR_MS).length; } catch { /* reported as null */ }
    const status = held.size ? 'ok' : (!cacheWritable || logBroken) ? 'no-cache' : throttled ? 'throttled' : 'warming';
    return {
      status, current, sts: held.get(current)?.sts ?? null, held: [...held.keys()],
      rssMiB: Math.round(process.memoryUsage().rss / 1_048_576), solves, p95CpuMs: Number(p95().toFixed(3)),
      lastPoll, fetchesLastHour, cacheWritable,
    };
  }

  const poll = () => (polling ??= pollOnce().finally(() => { polling = null; }));
  return { poll, solve, health };
}

const strings = (value) => Array.isArray(value) && value.every((x) => typeof x === 'string');

/** The three routes (spec §4.5 table). Bound to 127.0.0.1 by the unit; never logs a body. */
export function serve(solver, { host = '127.0.0.1', port = 4417 } = {}) {
  return http.createServer((req, res) => {
    const send = (status, payload) => {
      res.writeHead(status, { 'content-type': 'application/json' });
      res.end(JSON.stringify(payload));
    };
    if (req.method === 'GET' && req.url === '/player') {
      const h = solver.health();
      return h.current ? send(200, { playerId: h.current, sts: h.sts }) : send(503, { status: h.status });
    }
    if (req.method === 'GET' && req.url === '/health') {
      const h = solver.health();
      return send(h.status === 'ok' ? 200 : 503, h);
    }
    if (req.method !== 'POST' || req.url !== '/solve') return send(404, { error: 'not found' });
    const chunks = [];
    let size = 0;
    req.on('data', (chunk) => { size += chunk.length; if (size <= 65_536) chunks.push(chunk); });
    req.on('end', () => {
      if (size > 65_536) return send(400, { error: 'body over 64 KB' });
      let body;
      try { body = JSON.parse(Buffer.concat(chunks).toString('utf8')); } catch { return send(400, { error: 'malformed' }); }
      if (typeof body?.playerId !== 'string' || !strings(body.n) || !strings(body.s)) return send(400, { error: 'malformed' });
      const [status, payload] = solver.solve(body);
      send(status, payload);
    });
  }).listen(port, host);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const solver = createSolver({ origin: process.env.SOLVER_ORIGIN || undefined,
                                cacheDir: process.env.SOLVER_CACHE_DIR || '/var/cache/fitrah-solver' });
  serve(solver, { host: process.env.SOLVER_BIND || '127.0.0.1', port: Number(process.env.SOLVER_PORT || 4417) });
  solver.poll();
  setInterval(() => solver.poll(), 30 * 60_000);     // at startup and every 30 min (§4.5)
}
