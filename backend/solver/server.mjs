// FitrahTube playback challenge solver: the GATEWAY (spec 2026-10-14 §4.5, R25-R27, R39, R40; S20). Node built-ins only.
// It serves the backend, fetches and caches base.js, and holds the bookkeeping, but never runs YouTube JS: that happens in
// the executor (executor.mjs), one process per connection to fitrah-solver-exec.socket, with no network and nothing writable,
// and one connection per held player. Every answer the executor gives is untrusted and checked here. Challenges are never logged.
import fs from 'node:fs';
import http from 'node:http';
import net from 'node:net';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

export const CHALLENGE = /^[A-Za-z0-9_=-]+$/;

const ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_';
const random = (length) => Array.from({ length }, () => ALPHABET[Math.floor(Math.random() * 64)]).join('');
/** yt-dlp's rule too: a player that cannot transform `n` returns `enhanced_except_…`, which is in the alphabet. Real answers
 *  are about 20 (n) and 110 (s) characters; a longer one is a hostile player's, which the backend would cache (S20 review). */
const usable = (output) => typeof output === 'string' && output.length <= 256 && CHALLENGE.test(output) && !output.startsWith('enhanced_except');
const changed = (output, input) => usable(output) && output !== input;

/** R26: 10 synthetic n (16 chars) and s (108 chars), solved by the executor as any request is. */
export const selfTestInputs = () => Array.from({ length: 10 }, () => [random(16), random(108)]);

/** R26: each must come back changed and in the challenge alphabet, and distinct inputs must give distinct outputs, which
 *  rejects identity and constant functions and the `[object Object]` class NewPipe 0.26.5 hit. `raw` is the executor's. */
export function selfTestPassed(inputs, raw) {
  const [n, s] = Array.isArray(raw) && raw.every(Array.isArray) ? raw : [[], []];
  const distinct = (list) => new Set(list).size === inputs.length;
  return distinct(n) && distinct(s) && inputs.every(([ni, si], i) => changed(n[i], ni) && changed(s[i], si));
}

/** The executor's answers, as the spec's {n: {challenge: answer}, s: {...}}: a challenge whose answer is not usable is omitted.
 *  `raw` is untrusted (S20): anything not the expected shape omits the answer, never throws. */
export function answers(n, s, raw) {
  const out = { n: {}, s: {} };
  const [rn, rs] = Array.isArray(raw) ? raw : [];
  [[n, rn, out.n], [s, rs, out.s]].forEach(([list, got, into]) => {
    list.forEach((challenge, i) => { const answer = Array.isArray(got) ? got[i] : undefined; if (usable(answer)) into[challenge] = answer; });
  });
  return out;
}

const MAX_LINE = 1_048_576;                     // a solve reply is a few KB; more is an executor gone wrong, not a bigger answer
/** An executor's number, never coerced: JSON's {"valueOf":1,"toString":1} makes Number() and String() throw. */
const ms = (value) => (Number.isFinite(value) ? value : 0);

/** The executor over one connection (production: fitrah-solver-exec.socket), which holds one player. One request at a time:
 *  createSolver serializes them, so each reply answers the one waiting call. An executor that answers late (`timeoutMs`, above
 *  any op's vm timeouts), out of turn, unparseably or too long, or hangs up, is dead: `onDead` runs once. It cannot be stopped
 *  from here (another uid, another namespace), so production's onDead exits this process with the crash marker naming the
 *  connection's player, and the unit's ExecStopPost SIGKILLs every executor. `close` is the gateway's own hang-up: no onDead.
 *  `onRejected` takes no hash: whatever the executor names, the connection's player is the one that ran there. `onDead`'s second
 *  argument says whether the executor said it was ready, which it does before it reads a request; no request is sent before
 *  that, so one that died unready never ran a player: no player's doing (e.g. systemd's MaxConnections dropping it). */
export function connectExecutor(stream, { onRejected, onDead, timeoutMs = 30_000 }) {
  let buffer = '', waiting = null, timer = null, dead = false, ready = false, unsent = null;
  const die = (why) => {
    if (dead) return;
    dead = true;
    clearTimeout(timer);
    stream.destroy();
    waiting?.reject(new Error(`executor ${why}`));
    waiting = null;
    onDead?.(why, ready);
  };
  stream.setEncoding('utf8');
  stream.on('data', (chunk) => {
    buffer += chunk;
    if (buffer.length > MAX_LINE) return die('sent a line over 1 MiB');
    for (let i; !dead && (i = buffer.indexOf('\n')) >= 0;) {
      const line = buffer.slice(0, i);
      buffer = buffer.slice(i + 1);
      let message;
      try { message = JSON.parse(line); } catch { return die('sent an unparseable line'); }
      if (message?.event === 'ready') { ready = true; if (unsent) stream.write(unsent); unsent = null; continue; }
      if (message?.event === 'rejected') { onRejected(); continue; }
      if (!waiting) return die('answered nothing it was asked');
      const { resolve, reject } = waiting;
      waiting = null;
      clearTimeout(timer);
      if (message?.ok === true) resolve(message.value);
      else reject(new Error(typeof message?.error === 'string'                            // reaches a WARN line: bounded, one line
        ? message.error.slice(0, 200).replace(/[^\x20-\x7e]/g, '?') : 'executor error'));
    }
  });
  stream.on('error', () => die('connection failed'));
  stream.on('close', () => die('hung up'));
  const call = (request) => new Promise((resolve, reject) => {
    if (dead) return reject(new Error('executor gone'));
    if (waiting) return reject(new Error('one executor call at a time'));
    waiting = { resolve, reject };
    timer = setTimeout(() => die(`gave no answer in ${timeoutMs} ms`), timeoutMs);
    if (ready) stream.write(`${JSON.stringify(request)}\n`); else unsent = `${JSON.stringify(request)}\n`;
  });
  return {
    build: (hash, text) => call({ op: 'build', hash, text }),
    solve: (hash, n, s, solveTimeoutMs) => call({ op: 'solve', hash, n, s, timeoutMs: solveTimeoutMs }),
    close: () => { onDead = null; die('closed'); },
  };
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

/** At most 2 held players (R25), single-flight polling, a persisted 3-an-hour download budget (R39). `connect(hash, events)`
 *  opens the executor that builds and runs that one player (connectExecutor's shape, `events` its onRejected and onDead; tests
 *  pass executor.mjs's createExecutor in-process). One per player (S20 review): what one escaped player does to its process
 *  ends with that player, and a death or rejection on a connection names exactly its player. `now` and `log` are test seams. */
export function createSolver({ origin = 'https://www.youtube.com', cacheDir, connect, now = Date.now, log = console,
                               solveTimeoutMs = 10_000, selfTestTimeoutMs = 10_000 }) {
  const held = new Map();                       // hash -> { sts, executor }; insertion order is age
  const logFile = path.join(cacheDir, 'fetch-log.json');
  // What this process holds, oldest first, so a restart (a stop, a crash, the watchdog's SIGKILL) re-adopts it from the cache
  // before it polls: a player the backend handed out before the restart, which devices cache for 30 min, keeps solving.
  const heldFile = path.join(cacheDir, 'held.json');
  // Until the first poll (restore, then iframe_api) settles, /player names no player: a restored one may be evicted seconds
  // later, and the backend would hand it out for 30 min. held.json keeps the last process's list until then (cold review).
  let started = false;
  // Names the player being built or solving. An executor that dies or hangs mid-op kills this process (connectExecutor's
  // onDead), as a fatal V8 OOM or the watchdog's SIGKILL would, and skips every finally, so a marker left at start means that
  // player killed the last process: it is deleted and not built again for an hour (`bootCrash`), or each
  // restart would rebuild it and crash (Cubic P3).
  const marker = path.join(cacheDir, 'building');
  const cpuRing = [];
  let current = null, lastPoll = null, polling = null, throttled = false, logBroken = false, solves = 0, lastSlowWarn = -Infinity;
  let executorRssMiB = null;                    // the executor's own report: where the players' memory is
  let turn = Promise.resolve();                 // one op at a time, on any executor (one marker, one reply)
  let queued = 0;
  let cacheWritable = probeCache();
  const crashed = new Set();                    // every player that broke THIS process (an unhandled rejection); skipped until a restart
  // The last process's death is weaker evidence: a watchdog kill of a merely congested loop or a cgroup OOM names whatever
  // player was running (gstack review), so that one is skipped for an hour, not until YouTube rotates its hash. A player that
  // really hangs or OOMs costs one restart an hour, inside the unit's StartLimitBurst.
  const bootCrash = dropCrashedPlayer(), bootCrashAt = now();

  /** Runs `op` (an executor call) after every earlier one, with the crash marker naming `hash` until it settles. */
  function guarded(hash, op) {
    const run = turn.then(async () => {
      try { fs.writeFileSync(marker, hash); } catch { /* a read-only cache could not delete the player either */ }
      try { return await op(); } finally { try { fs.rmSync(marker, { force: true }); } catch { /* the WARN names the step */ } }
    });
    turn = run.catch(() => {});
    return run;
  }

  /** Opens `hash`'s executor. A rejection there drops the player and closes it; its death leaves the marker naming the player
   *  for the next start (production's onDead then exits), even between ops, when no marker is up (S20 review). A death before
   *  the executor was ready names nobody: the build's marker goes too (Cubic: a full socket is not the next player's crash). */
  function open(hash) {
    const executor = connect(hash, {
      onRejected: () => { dropCrashed(hash); executor.close(); },
      onDead: (why, ready) => {
        try { if (ready) fs.writeFileSync(marker, hash); else fs.rmSync(marker, { force: true }); } catch { /* a read-only cache could not delete the player either */ }
      },
    });
    return executor;
  }

  function dropCrashedPlayer() {
    try {
      const hash = fs.readFileSync(marker, 'utf8');
      fs.rmSync(marker, { force: true });
      if (!/^[0-9a-f]{8}$/.test(hash)) return null;
      fs.rmSync(path.join(cacheDir, `${hash}.js`), { force: true });
      log.warn(`WARN player ${hash} crashed the solver; deleted and skipped for an hour`);
      return hash;
    } catch {
      return null;                                // no marker: the last process did not die building
    }
  }

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
      if (!Array.isArray(log) || !log.every(Number.isFinite)) throw new Error('fetch log is not a list of times');
      logBroken = false;
      return log;
    } catch (error) {
      if (error.code === 'ENOENT') { logBroken = false; return []; }
      logBroken = true;
      throw error;
    }
  }
  async function text(url, headers = {}, maxBytes = 8_000_000) {      // M6: base.js is ~3 MB
    const response = await fetch(url, { headers, signal: AbortSignal.timeout(20_000) });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const parts = [];
    let size = 0;
    for await (const chunk of response.body) {
      size += chunk.length;
      if (size > maxBytes) throw new Error(`body over ${maxBytes} bytes`);
      parts.push(chunk);
    }
    return Buffer.concat(parts).toString('utf8');
  }

  /** Polls iframe_api, or with `restoring`, re-adopts that held player from the cache only: never a download. */
  async function pollOnce(restoring) {
    let step = 'iframe_api';
    let file = null, hash = restoring ?? null;
    try {
      if (!restoring) hash = playerHash(await text(`${origin}/iframe_api`));
      step = 'hash';
      if (!hash) throw new Error('no player hash');
      if (held.has(hash)) { lastPoll = { at: now(), ok: true, step: 'held' }; return; }   // A/B never rebuilds (M10)
      if (crashed.has(hash)) throw new Error('this player broke the solver; skipped until a restart');
      if (hash === bootCrash && now() - bootCrashAt < HOUR_MS) throw new Error('this player crashed the last solver; skipped for an hour');
      step = 'cache';
      file = path.join(cacheDir, `${hash}.js`);
      let playerText = fs.existsSync(file) ? fs.readFileSync(file, 'utf8') : null;
      if (playerText === null && restoring) throw new Error('not in the cache');
      if (playerText === null) {
        step = 'fetch-budget';
        cacheWritable = probeCache();
        if (!cacheWritable) throw new Error('cache directory not writable');
        const recent = fetchLog().filter((t) => now() - t < HOUR_MS);
        throttled = recent.length >= 3;
        if (throttled) throw new Error('3 downloads in the last hour');
        try {
          fs.writeFileSync(`${logFile}.tmp`, JSON.stringify([...recent, now()]));   // recorded BEFORE the download,
          fs.renameSync(`${logFile}.tmp`, logFile);                                   // atomically: a crash cannot corrupt it
        } catch (error) { cacheWritable = false; throw error; }                     // R39: unwritable log = no download
        step = 'download';
        playerText = await text(`${origin}/s/player/${hash}/player_ias.vflset/en_US/base.js`, { 'accept-encoding': 'br, gzip' });
        fs.writeFileSync(`${file}.tmp`, playerText);
        fs.renameSync(`${file}.tmp`, file);                                  // atomic: no half file is ever read back
      }
      step = 'sts';
      const sts = signatureTimestamp(playerText, now());                     // before any eviction (M5)
      if (sts === null) throw new Error('sts missing or more than 60 days from today');
      step = 'evict';
      let executor;
      const cpuMs = await guarded(hash, async () => {
        if (held.size >= 2) evict(held.keys().next().value);                 // BEFORE the build (R27)
        executor = open(hash);
        try {
          step = 'build';
          const built = await executor.build(hash, playerText);
          step = 'self-test';
          const inputs = selfTestInputs();
          const tested = await executor.solve(hash, inputs.map(([n]) => n), inputs.map(([, s]) => s), selfTestTimeoutMs);
          noteRss(tested);
          if (!selfTestPassed(inputs, tested?.raw)) throw new Error('self-test failed');
          return ms(built?.cpuMs) + ms(tested?.cpuMs);
        } catch (error) {
          executor.close();
          throw error;
        }
      });
      // an unhandled rejection reported while it built (Node reports it after the run): never adopted (Cubic r6 P2)
      step = 'adopt';
      if (crashed.has(hash)) throw new Error('this player broke the solver; skipped until a restart');   // its executor is closed
      held.set(hash, { sts, executor });
      current = hash;
      saveHeld();
      log.log(`adopted player ${hash} sts ${sts} cpu ${Math.round(cpuMs)} ms`);
      lastPoll = { at: now(), ok: true, step: 'adopted' };
    } catch (error) {
      log.warn(`WARN poll failed at ${step}: ${error.message}`);
      lastPoll = { at: now(), ok: false, step };
      // a cached file that will not build, has no sts or fails the self-test must not wedge its hash: next poll downloads again.
      // One that broke the solver is skipped until a restart, and then rebuilt from the cache: no download (cold review).
      if (file && ['sts', 'build', 'self-test'].includes(step) && !crashed.has(hash)) try { fs.rmSync(file, { force: true }); } catch { /* the WARN above names the step */ }
    }
  }

  function p95() {
    if (cpuRing.length === 0) return 0;
    const sorted = [...cpuRing].sort((a, b) => a - b);
    return sorted[Math.ceil(0.95 * sorted.length) - 1];
  }

  function noteRss(reply) { if (Number.isFinite(reply?.rssMiB)) executorRssMiB = reply.rssMiB; }

  function evict(playerId) {
    held.get(playerId)?.executor.close();      // its process exits on the hang-up
    held.delete(playerId);
    if (current === playerId) current = held.keys().next().value ?? null;
    saveHeld();
  }

  function saveHeld() {
    if (!started) return;
    try {
      fs.writeFileSync(`${heldFile}.tmp`, JSON.stringify([...held.keys()]));
      fs.renameSync(`${heldFile}.tmp`, heldFile);
    } catch { /* a read-only cache restores nothing: the restart then holds only what iframe_api names, as before */ }
  }

  /** The last process's held players, oldest first; anything but a list of hashes restores nothing. */
  async function restore() {
    let saved = [];
    try { saved = JSON.parse(fs.readFileSync(heldFile, 'utf8')); } catch { /* none, or unreadable */ }
    if (!Array.isArray(saved)) return;
    for (const hash of saved) if (typeof hash === 'string' && /^[0-9a-f]{8}$/.test(hash)) await pollOnce(hash);
  }

  async function solve({ playerId, n, s }) {
    if (!held.has(playerId)) return [409, { error: 'unknown player' }];
    // S20 review: the backend gives up after 1 s and sends more, so a slow executor must not grow a queue of answers nobody reads
    if (queued >= 32) return [503, { error: 'busy' }];
    const asked = now();
    queued++;
    // S20 review: a realm that escapes the vm timeout here hangs the executor; the marker makes the next process drop this
    // player, as for a build, or each restart rebuilds it and hangs again until StartLimitBurst stops the unit.
    return guarded(playerId, async () => {
      queued--;
      if (!held.has(playerId)) return [409, { error: 'unknown player' }];   // evicted while it waited its turn
      if (now() - asked > 1_000) return [503, { error: 'stale' }];          // its caller timed out
      let reply;
      try {
        reply = await held.get(playerId).executor.solve(playerId, n, s, solveTimeoutMs);
      } catch {                                   // a hung or broken player: evicted there and here, others stay served (ruling 1)
        evict(playerId);
        log.warn(`WARN solve failed for player ${playerId}; evicted`);
        return [500, { error: 'solve failed' }];
      }
      noteRss(reply);
      cpuRing.push(ms(reply?.cpuMs));              // R30: the realm run's CPU, as the executor measured it
      if (cpuRing.length > 1000) cpuRing.shift();
      solves++;
      // R30: p95 budget 2 ms, WARN above 5 ms at most once an hour. ponytail: checked every 100th solve, not every one.
      if (solves % 100 === 0 && p95() > 5 && now() - lastSlowWarn >= HOUR_MS) {
        lastSlowWarn = now();
        log.warn(`WARN solve CPU p95 ${p95().toFixed(2)} ms is over 5 ms`);
      }
      return [200, answers(n, s, reply?.raw)];
    });
  }

  function health() {
    let fetchesLastHour = null;
    try { fetchesLastHour = fetchLog().filter((t) => now() - t < HOUR_MS).length; } catch { /* reported as null */ }
    const status = held.size && started ? 'ok' : (!cacheWritable || logBroken) ? 'no-cache' : throttled ? 'throttled' : 'warming';
    return {
      status, current: started ? current : null, sts: started ? held.get(current)?.sts ?? null : null, held: [...held.keys()],
      rssMiB: Math.round(process.memoryUsage().rss / 1_048_576), executorRssMiB, solves, p95CpuMs: Number(p95().toFixed(3)),
      lastPoll, fetchesLastHour, cacheWritable,
    };
  }

  /** A player left an unhandled rejection (Cubic r6 P2; executor.mjs `rejected`): its executor dropped it, so this side evicts
   *  it and skips it until a restart, never a crash that each restart repeats. Production names the connection's player;
   *  in-process tests name what the executor says, so only a hash is taken. */
  function dropCrashed(hash) {
    if (typeof hash !== 'string' || !/^[0-9a-f]{8}$/.test(hash)) return;
    evict(hash);
    if (crashed.has(hash)) return;             // one WARN, however many it leaves
    crashed.add(hash);                           // a Set: a later rejection must not forget an earlier crasher (two players in A/B rotation)
    log.warn(`WARN player ${hash} left an unhandled promise rejection; evicted and skipped until a restart`);
  }

  /** A stop (SIGTERM) is not a crash: whatever op is in flight, the next start must not drop its player (Cubic r2 P2). */
  function stopped() { try { fs.rmSync(marker, { force: true }); } catch { /* nothing to keep */ } }

  let restored = null;
  const poll = () => (polling ??= (restored ??= restore()).then(() => pollOnce())
    .then(() => { if (!started) { started = true; saveHeld(); } }).finally(() => { polling = null; }));
  return { poll, solve, health, dropCrashed, stopped };
}

/** At most `max` strings. The caps are the backend's (PlaybackSolverService.valid: 4 n, 64 s), which forwards only what it
 *  accepted, so they never refuse its request; anything more is not the backend's and would run the player once per entry. */
const strings = (value, max) => Array.isArray(value) && value.length <= max && value.every((x) => typeof x === 'string');

/** The three routes (spec §4.5 table). Bound to 127.0.0.1 by the unit; never logs a body. */
export function serve(solver, { host = '127.0.0.1', port = 4417 } = {}) {
  const server = http.createServer((req, res) => {
    const send = (status, payload) => {
      // Task 6: never leave a socket a pooling client could reuse after Node's 5 s keep-alive closes it (a lost POST).
      res.writeHead(status, { 'content-type': 'application/json', connection: 'close' });
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
    req.on('data', (chunk) => {
      size += chunk.length;
      if (size <= 65_536) return chunks.push(chunk);
      if (res.headersSent) return;
      res.setHeader('connection', 'close');                                   // M4: answer at once, stop reading
      send(400, { error: 'body over 64 KB' });
      req.destroy();
    });
    req.on('end', () => {
      if (res.headersSent) return;
      let body;
      try { body = JSON.parse(Buffer.concat(chunks).toString('utf8')); } catch { return send(400, { error: 'malformed' }); }
      if (typeof body?.playerId !== 'string' || !strings(body.n, 4) || !strings(body.s, 64)) return send(400, { error: 'malformed' });
      solver.solve(body).then(([status, payload]) => send(status, payload), () => send(500, { error: 'solve failed' }));
    });
  });
  server.requestTimeout = 5000;
  return server.listen(port, host);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const socket = process.env.SOLVER_EXEC_SOCKET || '/run/fitrah-solver-exec.sock';
  const connect = (hash, events) => connectExecutor(net.connect(socket), { ...events, onDead: (why, ready) => {
    events.onDead(why, ready);  // the marker names the player; ExecStopPost then SIGKILLs every executor, a hung one too
    console.error(`FATAL executor of player ${hash} ${why}; exiting so systemd restarts the solver with fresh executors (S20)`);
    process.exit(1);
  } });
  const solver = createSolver({ connect, origin: process.env.SOLVER_ORIGIN || undefined,
                                cacheDir: process.env.SOLVER_CACHE_DIR || '/var/cache/fitrah-solver' });
  serve(solver, { host: process.env.SOLVER_BIND || '127.0.0.1', port: Number(process.env.SOLVER_PORT || 4417) });
  // A stop is a clean exit, never Node's default kill (Cubic r2 P2), and not a crash: an op in flight keeps its player.
  for (const signal of ['SIGTERM', 'SIGINT']) process.on(signal, () => { solver.stopped(); process.exit(0); });
  const pollSafely = () => solver.poll().catch((e) => console.warn(`WARN poll rejected: ${e.message}`));   // never kill the process
  pollSafely();
  setInterval(pollSafely, 30 * 60_000);     // at startup and every 30 min (§4.5)
}
