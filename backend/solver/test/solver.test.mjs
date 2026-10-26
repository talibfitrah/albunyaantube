// cd backend/solver && node --test test/*.test.mjs   (spec §6.3). The 70-answer test needs a local base.js and skips without one.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import { spawnSync } from 'node:child_process';
import { buildPlayer, newContext, selfTest, solveAll } from '../server.mjs';

const SPIKE = new URL('../../../.superpowers/sdd/2026-10-14-playback-phase2/spike/out/', import.meta.url).pathname;
const PLAYER = process.env.SOLVER_TEST_PLAYER ?? `${SPIKE}base.js`;      // player 21090930, the one the answers were recorded on
const ANSWERS = `${SPIKE}ejs-results.json`;
const haveRecording = fs.existsSync(PLAYER) && fs.existsSync(ANSWERS);

test('the solver realm has no process, fetch or require (R40)', () => {
  assert.equal(vm.runInContext('typeof process + typeof fetch + typeof require', newContext()), 'undefinedundefinedundefined');
});

test('the realm\'s global inherits nothing from the host, so constructor.constructor cannot reach process (security M1)', () => {
  const ctx = newContext();
  const escape = "(() => { try { return typeof constructor.constructor('return process')(); } catch { return 'blocked'; } })()";
  assert.notEqual(vm.runInContext(escape, ctx), 'object', 'top level');
  assert.notEqual(vm.runInContext(`Function(${JSON.stringify(`return ${escape}`)})()`, ctx), 'object', 'in a Function() body, as buildPlayer runs base.js');
});

test('a hanging build stops at the vm timeout (R27, R40)', () => {
  const ctx = newContext();
  vm.runInContext('globalThis.jsc = () => { const e = Date.now() + 2000; while (Date.now() < e) {} }', ctx);
  assert.throws(() => buildPlayer(ctx, 'player text', 200), { code: 'ERR_SCRIPT_EXECUTION_TIMEOUT' });
});

test('the self-test rejects identity, [object Object] and enhanced_except functions and accepts a real transform (R26)', () => {
  const ctx = newContext();
  const fns = (source) => ({ ctx, ...vm.runInContext(source, ctx) });
  assert.equal(selfTest(fns('({ n: (x) => x, sig: (x) => x })'), 1000), false);
  assert.equal(selfTest(fns('({ n: () => String({}), sig: () => String({}) })'), 1000), false);
  // the player's own n-failure fallback: inside the alphabet and different from the input, so only the prefix gives it away
  assert.equal(selfTest(fns("({ n: (x) => 'enhanced_except_' + x, sig: (x) => x.slice(1) })"), 1000), false);
  assert.equal(selfTest(fns("({ n: (x) => x.split('').reverse().join('') + 'A', sig: (x) => x.slice(1) })"), 1000), true);
});

test('the self-test leaves no helper globals and gives the player no path to process (fix 1, 3)', () => {
  const ctx = newContext();
  const player = { ctx, ...vm.runInContext("({ n: (x) => { try { globalThis.leak = (typeof __probe === 'undefined' ? __probeIn : __probe.inputs).constructor.constructor('return typeof process')(); } catch { globalThis.leak = 'blocked'; } return x + 'A'; }, sig: (x) => x.slice(1) })", ctx) };
  selfTest(player, 1000);
  assert.notEqual(vm.runInContext('globalThis.leak', ctx), 'object');
  assert.equal(vm.runInContext('typeof __probe + typeof __probeN + typeof __probeS + typeof __probeIn + typeof __player', ctx), 'undefined'.repeat(5));
});

test('a failed build leaves no __player behind (fix 3)', () => {
  const ctx = newContext();
  assert.throws(() => buildPlayer(ctx, 'not a player at all ((('));
  assert.equal(vm.runInContext('typeof __player', ctx), 'undefined');
});

// Runs in a child process: terminating a microtask drain inside node:test's own async scope corrupts the runner's async-hook stack.
test('a microtask loop scheduled by the player hits the timeout (fix 2)', { timeout: 30_000 }, () => {
  const script = `import vm from 'node:vm'; import { newContext, selfTest } from ${JSON.stringify(new URL('../server.mjs', import.meta.url).href)};
    const ctx = newContext();
    const player = { ctx, ...vm.runInContext("({ n: (x) => { Promise.resolve().then(() => { for (;;) {} }); return x + 'A'; }, sig: (x) => x.slice(1) })", ctx) };
    try { selfTest(player, 300); console.log('no timeout'); } catch (e) { console.log(e.code); }`;
  const r = spawnSync(process.execPath, ['--experimental-vm-modules', '--input-type=module', '-e', script], { encoding: 'utf8', timeout: 20_000 });
  assert.equal(r.stdout.trim(), 'ERR_SCRIPT_EXECUTION_TIMEOUT');
});

test('the self-test rejects a constant function (fix 5)', () => {
  const ctx = newContext();
  const fns = (source) => ({ ctx, ...vm.runInContext(source, ctx) });
  assert.equal(selfTest(fns("({ n: () => 'ConstantAnswer', sig: (x) => x.slice(1) })"), 1000), false);
  assert.equal(selfTest(fns("({ n: (x) => x + 'A', sig: () => 'ConstantAnswer' })"), 1000), false);
});

test('one good function with one identity function still fails (fix 6)', () => {
  const ctx = newContext();
  const fns = (source) => ({ ctx, ...vm.runInContext(source, ctx) });
  assert.equal(selfTest(fns("({ n: (x) => x + 'A', sig: (x) => x })"), 1000), false);
  assert.equal(selfTest(fns("({ n: (x) => x, sig: (x) => x.slice(1) })"), 1000), false);
});

test('a throwing or misshapen answer omits only that challenge', () => {
  const ctx = newContext();
  const player = { ctx, ...vm.runInContext("({ n: (x) => { if (x === 'bad') throw new Error('x'); if (x === 'gone') return 'enhanced_except_' + x; return x + 'A'; }, sig: () => 'has space' })", ctx) };
  assert.deepEqual(solveAll(player, ['good', 'bad', 'gone'], ['s1']), { n: { good: 'goodA' }, s: {} });
});

test('the held functions reproduce the 70 recorded live answers (spec §6.3)', { skip: !haveRecording && 'no recorded base.js' }, () => {
  const player = buildPlayer(newContext(), fs.readFileSync(PLAYER, 'utf8'));
  const want = JSON.parse(fs.readFileSync(ANSWERS, 'utf8'));
  assert.equal(Object.keys(want.n).length + Object.keys(want.sig).length, 70);
  const got = solveAll(player, Object.keys(want.n), Object.keys(want.sig));
  assert.deepEqual(got.n, want.n);
  assert.deepEqual(got.s, want.sig);
  assert.equal(selfTest(player), true);
});

import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import { once } from 'node:events';
import { createSolver, playerHash, serve, signatureTimestamp } from '../server.mjs';

const DAY = 86_400_000;
const today = () => Math.floor(Date.now() / DAY);
const quiet = { log() {}, warn() {} };
// iframe_api's real text escapes its slashes ("player\/0229cd64\/"), the shape NewPipe's regex expects too.
const iframeText = (hash) => `var scriptUrl = 'https:\\/\\/www.youtube.com\\/s\\/player\\/${hash}\\/www-widgetapi.vflset\\/www-widgetapi.js';`;
const tempDir = () => fs.mkdtempSync(path.join(fs.realpathSync(os.tmpdir()), 'fitrah-solver-'));

/** Stands in for www.youtube.com: iframe_api answers the next hash (the last repeats); base.js carries today's sts. */
async function origin(hashes) {
  const hits = [];
  let next = 0;
  const server = http.createServer((req, res) => {
    hits.push(req.url);
    if (req.url === '/iframe_api') return res.end(iframeText(hashes[Math.min(next++, hashes.length - 1)]));
    if (req.url.startsWith('/s/player/ffffffff/')) return res.end('x'.repeat(9_000_000));
    if (/^\/s\/player\/[0-9a-f]{8}\/player_ias\.vflset\/en_US\/base\.js$/.test(req.url)) return res.end(`var cfg={signatureTimestamp:${today()}};`);
    res.writeHead(404); res.end();
  }).listen(0, '127.0.0.1');
  server.unref();
  await once(server, 'listening');
  return { url: `http://127.0.0.1:${server.address().port}`, downloads: () => hits.filter((h) => h.endsWith('base.js')).length, close: () => server.close() };
}

/** Held functions that pass the self-test without a 3 MB player; `builds` counts the calls. */
const fakeBuild = (builds = []) => () => {
  builds.push(1);
  const ctx = newContext();
  return { ctx, ...vm.runInContext("({ n: (x) => x.split('').reverse().join('') + 'A', sig: (x) => x.slice(1) })", ctx) };
};

test('playerHash reads the escaped and plain forms, and refuses anything but 8 lowercase hex (M12)', () => {
  assert.equal(playerHash(iframeText('0229cd64')), '0229cd64');
  assert.equal(playerHash('https://www.youtube.com/s/player/21090930/www-widgetapi.vflset/x.js'), '21090930');
  assert.equal(playerHash(iframeText('0229CD64')), null);
  assert.equal(playerHash(iframeText('0229cd6')), null);
  assert.equal(playerHash('nothing here'), null);
});

test('signatureTimestamp keeps today ±60 days and refuses the rest (M13)', () => {
  const oct12 = Date.UTC(2026, 9, 12);                  // sts 20738
  assert.equal(signatureTimestamp('a={signatureTimestamp:20738}', oct12), 20738);
  assert.equal(signatureTimestamp('b={sts:20798}', oct12), 20798);
  assert.equal(signatureTimestamp('b={sts:20799}', oct12), null);
  assert.equal(signatureTimestamp('no timestamp at all', oct12), null);
});

test('a new hash downloads once and is adopted; a held hash is never fetched again', async () => {
  const yt = await origin(['0229cd64']);
  const builds = [];
  const solver = createSolver({ origin: yt.url, cacheDir: tempDir(), build: fakeBuild(builds), log: quiet });
  await solver.poll();
  await solver.poll();
  assert.equal(yt.downloads(), 1);
  assert.equal(builds.length, 1);
  assert.deepEqual([solver.health().status, solver.health().current, solver.health().sts], ['ok', '0229cd64', today()]);
  yt.close();
});

test('an A/B flip between two held hashes never rebuilds (M10)', async () => {
  const yt = await origin(['aaaaaaaa', 'bbbbbbbb', 'aaaaaaaa', 'bbbbbbbb']);
  const builds = [];
  const solver = createSolver({ origin: yt.url, cacheDir: tempDir(), build: fakeBuild(builds), log: quiet });
  for (let i = 0; i < 4; i++) await solver.poll();
  assert.equal(builds.length, 2);
  assert.equal(yt.downloads(), 2);
  assert.deepEqual(solver.health().held, ['aaaaaaaa', 'bbbbbbbb']);
  yt.close();
});

test('the oldest player is evicted BEFORE the third is built (R27)', async () => {
  const yt = await origin(['aaaaaaaa', 'bbbbbbbb', 'cccccccc']);
  const heldAtBuild = [];
  const build = fakeBuild();
  let solver;
  solver = createSolver({ origin: yt.url, cacheDir: tempDir(), log: quiet,
    build: (text) => { heldAtBuild.push(solver.health().held.length); return build(text); } });
  for (let i = 0; i < 3; i++) await solver.poll();
  assert.deepEqual(heldAtBuild, [0, 1, 1]);
  assert.deepEqual(solver.health().held, ['bbbbbbbb', 'cccccccc']);
  yt.close();
});

test('the fetch log allows 3 downloads an hour, survives a restart, and reopens an hour later (R39)', async () => {
  const yt = await origin(['aaaaaaaa', 'bbbbbbbb', 'cccccccc', 'dddddddd']);
  const dir = tempDir();
  let t = Date.now();
  const now = () => t;
  const first = createSolver({ origin: yt.url, cacheDir: dir, build: fakeBuild(), now, log: quiet });
  for (let i = 0; i < 4; i++) await first.poll();
  assert.equal(yt.downloads(), 3);
  assert.equal(first.health().fetchesLastHour, 3);
  assert.equal(first.health().lastPoll.step, 'fetch-budget');
  const restarted = createSolver({ origin: yt.url, cacheDir: dir, build: fakeBuild(), now, log: quiet });
  await restarted.poll();
  assert.equal(yt.downloads(), 3);
  assert.equal(restarted.health().status, 'throttled');
  t += 3_600_001;
  await restarted.poll();
  assert.equal(yt.downloads(), 4);
  assert.equal(restarted.health().status, 'ok');
  yt.close();
});

test('a base.js already in the cache is reused after a restart, with no download', async () => {
  const yt = await origin(['aaaaaaaa']);
  const dir = tempDir();
  await createSolver({ origin: yt.url, cacheDir: dir, build: fakeBuild(), log: quiet }).poll();
  const restarted = createSolver({ origin: yt.url, cacheDir: dir, build: fakeBuild(), log: quiet });
  await restarted.poll();
  assert.equal(yt.downloads(), 1);
  assert.equal(restarted.health().status, 'ok');
  yt.close();
});

test('an unwritable cache fails closed: nothing is downloaded and /health says no-cache (R39)', async () => {
  const yt = await origin(['aaaaaaaa']);
  const dir = tempDir();
  fs.chmodSync(dir, 0o500);
  try {
    const solver = createSolver({ origin: yt.url, cacheDir: dir, build: fakeBuild(), log: quiet });
    await solver.poll();
    assert.equal(yt.downloads(), 0);
    assert.deepEqual([solver.health().status, solver.health().cacheWritable], ['no-cache', false]);
  } finally {
    fs.chmodSync(dir, 0o700);
    yt.close();
  }
});

test('a corrupt fetch log fails closed: nothing is downloaded and /health says no-cache (R39)', async () => {
  const yt = await origin(['aaaaaaaa']);
  const dir = tempDir();
  fs.writeFileSync(path.join(dir, 'fetch-log.json'), '[17604');          // a write cut short
  const solver = createSolver({ origin: yt.url, cacheDir: dir, build: fakeBuild(), log: quiet });
  await solver.poll();
  assert.equal(yt.downloads(), 0);
  assert.deepEqual([solver.health().status, solver.health().lastPoll.step], ['no-cache', 'fetch-budget']);
  yt.close();
});

test('a player that fails the self-test is not adopted, and the WARN names the step (R26)', async () => {
  const yt = await origin(['aaaaaaaa']);
  const warnings = [];
  const solver = createSolver({ origin: yt.url, cacheDir: tempDir(), log: { log() {}, warn: (m) => warnings.push(m) },
    build: () => { const ctx = newContext(); return { ctx, ...vm.runInContext('({ n: (x) => x, sig: (x) => x })', ctx) }; } });
  await solver.poll();
  assert.equal(solver.health().status, 'warming');
  assert.match(warnings[0], /^WARN poll failed at self-test/);
  yt.close();
});

test('/solve validates, answers 409 for an unknown player, and /player and /health have the spec shape', async () => {
  const yt = await origin(['aaaaaaaa']);
  const solver = createSolver({ origin: yt.url, cacheDir: tempDir(), build: fakeBuild(), log: quiet });
  await solver.poll();
  const server = serve(solver, { host: '127.0.0.1', port: 0 });
  await once(server, 'listening');
  const base = `http://127.0.0.1:${server.address().port}`;
  const post = (body) => fetch(`${base}/solve`, { method: 'POST', body: typeof body === 'string' ? body : JSON.stringify(body) });
  try {
    assert.equal((await post('{nope')).status, 400);
    assert.equal((await post({ playerId: 'aaaaaaaa', n: 'x', s: [] })).status, 400);
    assert.equal((await post({ playerId: 'aaaaaaaa', n: ['x'.repeat(70_000)], s: [] })).status, 400);
    assert.equal((await post({ playerId: 'deadbeef', n: ['abc'], s: [] })).status, 409);
    const ok = await post({ playerId: 'aaaaaaaa', n: ['abc'], s: ['xyz'] });
    assert.deepEqual(await ok.json(), { n: { abc: 'cbaA' }, s: { xyz: 'yz' } });
    assert.deepEqual(await (await fetch(`${base}/player`)).json(), { playerId: 'aaaaaaaa', sts: today() });
    const health = await (await fetch(`${base}/health`)).json();
    assert.deepEqual(Object.keys(health).sort(),
      ['cacheWritable', 'current', 'fetchesLastHour', 'held', 'lastPoll', 'p95CpuMs', 'rssMiB', 'solves', 'status', 'sts']);
    assert.equal(health.solves, 1);
  } finally {
    server.close();
    yt.close();
  }
});

// ---- rulings: timeouts throw, solves are bounded, the server outlives a hung build
const hangingBuild = () => { const ctx = newContext(); vm.runInContext('globalThis.jsc = () => { const e = Date.now() + 2000; while (Date.now() < e) {} }', ctx); return buildPlayer(ctx, 'x', 200); };

test('a solve with a synchronous loop stops at the vm timeout (ruling 2)', () => {
  const ctx = newContext();
  const player = { ctx, ...vm.runInContext("({ n: (x) => { if (x === 'spin') { const e = Date.now() + 2000; while (Date.now() < e) {} } return x + 'A'; }, sig: (x) => x.slice(1) })", ctx) };
  assert.throws(() => solveAll(player, ['abc', 'spin'], ['xyz'], 200), { code: 'ERR_SCRIPT_EXECUTION_TIMEOUT' });
  assert.deepEqual(solveAll(player, ['abc'], ['xyz'], 200), { n: { abc: 'abcA' }, s: { xyz: 'yz' } });   // the realm is reusable
});

test('a solve that times out evicts only that player and answers 500 (ruling 1)', async () => {
  const yt = await origin(['aaaaaaaa', 'bbbbbbbb']);
  const builds = [];
  const spin = () => {
    builds.push(1);
    const ctx = newContext();
    return { ctx, ...vm.runInContext("({ n: (x) => { if (x === 'spin') { const e = Date.now() + 2000; while (Date.now() < e) {} } return x.split('').reverse().join('') + 'A'; }, sig: (x) => x.slice(1) })", ctx) };
  };
  const solver = createSolver({ origin: yt.url, cacheDir: tempDir(), build: spin, log: quiet, solveTimeoutMs: 200 });
  await solver.poll(); await solver.poll();
  assert.deepEqual(solver.solve({ playerId: 'bbbbbbbb', n: ['spin'], s: [] }), [500, { error: 'solve failed' }]);
  assert.deepEqual(solver.health().held, ['aaaaaaaa']);
  assert.deepEqual(solver.solve({ playerId: 'aaaaaaaa', n: ['abc'], s: [] }), [200, { n: { abc: 'cbaA' }, s: {} }]);
  yt.close();
});

test('a build that times out fails closed and the HTTP server keeps answering health (rulings 1, 3)', async () => {
  const yt = await origin(['aaaaaaaa', 'bbbbbbbb']);
  const warnings = [];
  let calls = 0;
  const good = fakeBuild();
  const solver = createSolver({ origin: yt.url, cacheDir: tempDir(), log: { log() {}, warn: (m) => warnings.push(m) },
    build: (text) => (calls++ === 0 ? hangingBuild() : good(text)) });
  const server = serve(solver, { host: '127.0.0.1', port: 0 });
  await once(server, 'listening');
  const base = `http://127.0.0.1:${server.address().port}`;
  try {
    await solver.poll();                                       // the build hangs and is stopped at the vm timeout
    assert.match(warnings[0], /^WARN poll failed at build: .*timed out/);
    const bad = await fetch(`${base}/health`);
    assert.equal(bad.status, 503);
    assert.equal((await bad.json()).status, 'warming');
    await solver.poll();                                       // the next player still builds
    const ok = await fetch(`${base}/health`);
    assert.equal(ok.status, 200);
    assert.deepEqual((await ok.json()).held, ['bbbbbbbb']);
  } finally {
    server.close();
    yt.close();
  }
});

test('a selfTest that throws is a failed build too, and the WARN names the step (ruling 1)', async () => {
  const yt = await origin(['aaaaaaaa']);
  const warnings = [];
  const solver = createSolver({ origin: yt.url, cacheDir: tempDir(), log: { log() {}, warn: (m) => warnings.push(m) }, selfTestTimeoutMs: 100,
    build: () => { const ctx = newContext(); return { ctx, ...vm.runInContext("({ n: () => { const e = Date.now() + 2000; while (Date.now() < e) {} }, sig: (x) => x })", ctx) }; } });
  await solver.poll();
  assert.match(warnings[0], /^WARN poll failed at self-test/);
  assert.equal(solver.health().status, 'warming');
  yt.close();
});

// ---- fix round 1
test('a player that fails to build, has no sts or fails the self-test is deleted from the cache and downloaded again (fix 2)', async () => {
  const yt = await origin(['aaaaaaaa']);
  const dir = tempDir();
  let calls = 0;
  const good = fakeBuild();
  const solver = createSolver({ origin: yt.url, cacheDir: dir, log: quiet, build: (t) => { if (calls++ === 0) throw new Error('bad build'); return good(t); } });
  await solver.poll();
  assert.equal(fs.existsSync(path.join(dir, 'aaaaaaaa.js')), false);
  await solver.poll();
  assert.equal(yt.downloads(), 2);
  assert.equal(solver.health().status, 'ok');
  assert.deepEqual(fs.readdirSync(dir).filter((f) => f.endsWith('.tmp')), []);
  yt.close();
});

test('a fetch-log write failure reports no-cache (M1)', async () => {
  const yt = await origin(['aaaaaaaa']);
  const dir = tempDir();
  fs.mkdirSync(path.join(dir, 'fetch-log.json.tmp'));          // the atomic write cannot create its temp file
  const solver = createSolver({ origin: yt.url, cacheDir: dir, build: fakeBuild(), log: quiet });
  await solver.poll();
  assert.equal(yt.downloads(), 0);
  assert.deepEqual([solver.health().status, solver.health().cacheWritable], ['no-cache', false]);
  yt.close();
});

test('a fetch log with non-numeric entries fails closed (M2)', async () => {
  const yt = await origin(['aaaaaaaa']);
  const dir = tempDir();
  fs.writeFileSync(path.join(dir, 'fetch-log.json'), '["x", null]');
  const solver = createSolver({ origin: yt.url, cacheDir: dir, build: fakeBuild(), log: quiet });
  await solver.poll();
  assert.equal(yt.downloads(), 0);
  assert.equal(solver.health().status, 'no-cache');
  yt.close();
});

test('an oversize /solve body is refused at once with connection: close, and the server has a request timeout (M4)', async () => {
  const solver = createSolver({ origin: 'http://127.0.0.1:1', cacheDir: tempDir(), build: fakeBuild(), log: quiet });
  const server = serve(solver, { host: '127.0.0.1', port: 0 });
  await once(server, 'listening');
  try {
    assert.equal(server.requestTimeout, 5000);
    const req = http.request({ host: '127.0.0.1', port: server.address().port, method: 'POST', path: '/solve' });
    req.on('error', () => {});
    req.write('x'.repeat(70_000));                              // never ends the request: only an early answer can respond
    const [res] = await once(req, 'response');
    assert.equal(res.statusCode, 400);
    assert.equal(res.headers.connection, 'close');
    req.destroy();
  } finally {
    server.close();
  }
});

test('every answer closes its connection, so a pooled client never reuses one the sidecar timed out (Task 6)', async () => {
  const solver = createSolver({ origin: 'http://127.0.0.1:1', cacheDir: tempDir(), build: fakeBuild(), log: quiet });
  const server = serve(solver, { host: '127.0.0.1', port: 0 });
  await once(server, 'listening');
  const agent = new http.Agent({ keepAlive: true });                  // a pooling client, like the JDK's
  try {
    for (const [method, path, body] of [['GET', '/player'], ['GET', '/health'],
      ['POST', '/solve', '{"playerId":"0229cd64","n":["ABCDEFGHIJKLMNOP"],"s":[]}']]) {
      const req = http.request({ host: '127.0.0.1', port: server.address().port, method, path, agent });
      req.end(body);
      const [res] = await once(req, 'response');
      res.resume();
      assert.equal(res.headers.connection, 'close', `${method} ${path}`);
    }
  } finally {
    agent.destroy();
    server.close();
  }
});

test('a player with no usable sts is refused BEFORE a held player is evicted (M5)', async () => {
  const yt = await origin(['aaaaaaaa', 'bbbbbbbb', 'cccccccc']);
  const dir = tempDir();
  fs.writeFileSync(path.join(dir, 'cccccccc.js'), 'no timestamp here');
  const solver = createSolver({ origin: yt.url, cacheDir: dir, build: fakeBuild(), log: quiet });
  for (let i = 0; i < 3; i++) await solver.poll();
  assert.deepEqual(solver.health().held, ['aaaaaaaa', 'bbbbbbbb']);
  assert.equal(solver.health().lastPoll.step, 'sts');
  assert.equal(fs.existsSync(path.join(dir, 'cccccccc.js')), false);
  yt.close();
});

test('a base.js over 8 MB is refused and not cached (M6)', async () => {
  const yt = await origin(['ffffffff']);
  const dir = tempDir();
  const solver = createSolver({ origin: yt.url, cacheDir: dir, build: fakeBuild(), log: quiet });
  await solver.poll();
  assert.equal(solver.health().lastPoll.step, 'download');
  assert.equal(fs.existsSync(path.join(dir, 'ffffffff.js')), false);
  yt.close();
});

test('poll is single-flight: concurrent callers share one run (M9)', async () => {
  const yt = await origin(['aaaaaaaa']);
  const builds = [];
  const solver = createSolver({ origin: yt.url, cacheDir: tempDir(), build: fakeBuild(builds), log: quiet });
  const a = solver.poll();
  const b = solver.poll();
  assert.equal(a, b);
  await a;
  assert.equal(yt.downloads(), 1);
  assert.equal(builds.length, 1);
  yt.close();
});

test('a failed cache delete in a read-only directory leaves poll resolving and /health answering (fix round 2)', async () => {
  const yt = await origin(['aaaaaaaa']);
  const dir = tempDir();
  fs.writeFileSync(path.join(dir, 'aaaaaaaa.js'), 'no timestamp here');
  fs.chmodSync(dir, 0o500);
  const solver = createSolver({ origin: yt.url, cacheDir: dir, build: fakeBuild(), log: quiet });
  const server = serve(solver, { host: '127.0.0.1', port: 0 });
  await once(server, 'listening');
  try {
    await assert.doesNotReject(solver.poll());
    assert.equal(solver.health().lastPoll.step, 'sts');
    assert.equal((await fetch(`http://127.0.0.1:${server.address().port}/health`)).status, 503);
  } finally {
    fs.chmodSync(dir, 0o700);
    server.close();
    yt.close();
  }
});

// ---- stage 9 Cubic round 1: nothing the realm controls runs on the host, and a crashing player cannot loop restarts
import { spawn } from 'node:child_process';
const SERVER = JSON.stringify(new URL('../server.mjs', import.meta.url).href);

/** Runs `script` (an ES module) in a child Node, killed after `ms`: a host stuck in realm code shows as signal SIGKILL. */
async function child(script, ms = 8_000, flags = []) {
  // newContext needs --experimental-vm-modules (Cubic r7 #1); every realm child runs under the flag the prod unit now carries.
  const proc = spawn(process.execPath, ['--experimental-vm-modules', ...flags, '--input-type=module', '-e', script], { stdio: ['ignore', 'pipe', 'inherit'] });
  let stdout = '';
  proc.stdout.on('data', (d) => { stdout += d; });
  const timer = setTimeout(() => proc.kill('SIGKILL'), ms);
  const [code, signal] = await once(proc, 'exit');
  clearTimeout(timer);
  return { code, signal, stdout: stdout.trim() };
}

/** A child that runs `body` against one realm (`ctx`, `player`) and prints what each call did. */
const realmScript = (body) => `import vm from 'node:vm'; import { buildPlayer, newContext, selfTest, solveAll } from ${SERVER};
  const ctx = newContext();
  const player = { ctx, ...vm.runInContext("({ n: (x) => x + 'A', sig: (x) => x.slice(1) })", ctx) };
  const outcome = (f) => { try { return JSON.stringify(f()); } catch (e) { return 'threw: ' + e.message; } };
  ${body}`;

test('a realm JSON.stringify that answers an object with a looping toString never hangs the host (Cubic P2)', async () => {
  const r = await child(realmScript(`vm.runInContext("JSON.stringify = () => ({ toString() { for (;;) {} } })", ctx);
    console.log(JSON.stringify([outcome(() => selfTest(player, 300)), outcome(() => solveAll(player, ['abc'], [], 300))]));`));
  assert.equal(r.signal, null, 'the host hung parsing a realm value');
  const [self, solve] = JSON.parse(r.stdout);
  assert.match(self, /^threw: /);
  assert.match(solve, /^threw: /);
});

test('a looping getter on the player\'s n is read inside the realm, under the timeout (Cubic P2)', async () => {
  const r = await child(realmScript(`vm.runInContext(\`globalThis.jsc = () => ({ preprocessed_player:
      "Object.defineProperty(_result, 'n', { get() { for (;;) {} } }); _result.sig = (x) => x;" })\`, ctx);
    console.log(outcome(() => buildPlayer(ctx, 'x', 300)));`));
  assert.equal(r.signal, null, 'the host ran the getter');
  assert.match(r.stdout, /^threw: .*timed out/);
});

test('a looping setter the player left on a solve global never runs on the host (Cubic P2)', async () => {
  const r = await child(realmScript(`for (const name of ['__solveN', '__solveS', '__solveIn', '__probeN', '__probeS', '__probeIn']) {
      vm.runInContext(\`Object.defineProperty(globalThis, '\${name}', { set() { for (;;) {} }, get() { return undefined; }, configurable: true })\`, ctx);
    }
    console.log(JSON.stringify([outcome(() => solveAll(player, ['abc'], [], 300)), outcome(() => selfTest(player, 300))]));`));
  assert.equal(r.signal, null, 'the host ran the setter');
  assert.deepEqual(JSON.parse(r.stdout), [JSON.stringify({ n: { abc: 'abcA' }, s: {} }), 'true']);
});

test('a player that throws an object with a looping message getter never hangs the poll\'s WARN (Cubic P2)', async () => {
  const yt = await origin(['aaaaaaaa']);
  const r = await child(`import vm from 'node:vm'; import { createSolver, newContext } from ${SERVER};
    const warnings = [];
    const solver = createSolver({ origin: ${JSON.stringify(yt.url)}, cacheDir: ${JSON.stringify(tempDir())}, selfTestTimeoutMs: 300,
      log: { log() {}, warn: (m) => warnings.push(m) },
      build: () => { const ctx = newContext(); return { ctx, ...vm.runInContext("({ n: () => { throw { get message() { for (;;) {} } }; }, sig: (x) => x })", ctx) }; } });
    await solver.poll();
    console.log(JSON.stringify(warnings));`);
  yt.close();
  assert.equal(r.signal, null, 'the host read the thrown object\'s message');
  assert.match(JSON.parse(r.stdout)[0], /^WARN poll failed at self-test: .*timed out/);
});

test('a build that kills the process (a fatal V8 OOM skips every catch) never loops: the next start drops that player (Cubic P3)', async () => {
  const yt = await origin(['aaaaaaaa', 'aaaaaaaa', 'bbbbbbbb']);
  const dir = tempDir();
  const r = await child(`import { createSolver } from ${SERVER};
    createSolver({ origin: ${JSON.stringify(yt.url)}, cacheDir: ${JSON.stringify(dir)}, log: { log() {}, warn() {} }, build: () => process.exit(9) }).poll();`);
  assert.equal(r.code, 9);
  assert.equal(fs.existsSync(path.join(dir, 'aaaaaaaa.js')), true, 'the crash skipped the catch that deletes a bad player');
  const builds = [];
  const restarted = createSolver({ origin: yt.url, cacheDir: dir, build: fakeBuild(builds), log: quiet });
  await restarted.poll();
  assert.equal(builds.length, 0, 'the player that crashed the last build is not built again');
  assert.equal(fs.existsSync(path.join(dir, 'aaaaaaaa.js')), false);
  assert.equal(restarted.health().status, 'warming', 'up and answering, not crashing');
  await restarted.poll();                                        // the next player builds as usual
  assert.deepEqual([builds.length, restarted.health().status, yt.downloads()], [1, 'ok', 2]);
  yt.close();
});

// ---- stage 9 Cubic round 2: no host-scheduled realm callback, and a clean stop never marks a healthy player as a crash
test('a FinalizationRegistry callback the player registers never runs on the host, outside the vm timeout (Cubic r2 P2)', async () => {
  const r = await child(realmScript(`vm.runInContext(\`globalThis.jsc = () => ({ preprocessed_player:
      "if (typeof FinalizationRegistry === 'function') { globalThis.keep = new FinalizationRegistry(() => { for (;;) {} });"
      + " for (let i = 0; i < 100; i++) keep.register({}, i); } _result.n = (x) => x + 'A'; _result.sig = (x) => x.slice(1);" })\`, ctx);
    const built = outcome(() => !!buildPlayer(ctx, 'x', 300));
    for (let i = 0; i < 5; i++) { gc(); await new Promise((r) => setTimeout(r, 50)); }   // V8 posts the cleanup as a host task
    console.log(JSON.stringify([built, vm.runInContext('typeof FinalizationRegistry + typeof WeakRef', ctx)]));`), 8_000, ['--expose-gc']);
  assert.equal(r.signal, null, 'a cleanup callback looped on the host');
  assert.deepEqual(JSON.parse(r.stdout), ['true', 'undefinedundefined']);
});

// ---- stage 9 Cubic round 6: nothing the realm built runs on the host after the run returns; its rejection drops the player
test('a WebAssembly compile the player starts never settles on the host, outside the vm timeout (Cubic r6 P2)', async () => {
  const r = await child(realmScript(`vm.runInContext(\`globalThis.jsc = () => ({ preprocessed_player:
      "Object.defineProperty(Object.prototype, 'then', { get() { for (;;) {} } });"
      + " try { WebAssembly.compile(new Uint8Array([0, 97, 115, 109, 1, 0, 0, 0])).catch(() => {}); } catch {}"
      + " _result.n = (x) => x + 'A'; _result.sig = (x) => x.slice(1);" })\`, ctx);
    const built = outcome(() => !!buildPlayer(ctx, 'x', 300));
    await new Promise((r) => setTimeout(r, 200));                    // V8 resolves an async compile from a host task
    console.log(JSON.stringify([built, vm.runInContext('typeof WebAssembly', ctx)]));`));
  assert.equal(r.signal, null, 'the compiled module\'s then getter looped on the host');
  assert.deepEqual(JSON.parse(r.stdout), ['true', 'undefined']);
});

test('Node\'s bookkeeping of an unhandled realm rejection never runs a realm Proxy on the host (Cubic r6 P2)', async () => {
  const r = await child(realmScript(`process.on('unhandledRejection', () => {});
    vm.runInContext(\`globalThis.jsc = () => ({ preprocessed_player:
      "try { Object.setPrototypeOf(Promise.reject(1), new Proxy({}, { get() { for (;;) {} } })); } catch {}"
      + " _result.n = (x) => x + 'A'; _result.sig = (x) => x.slice(1);" })\`, ctx);
    const built = outcome(() => !!buildPlayer(ctx, 'x', 300));
    await new Promise((r) => setTimeout(r, 200));                    // Node reads promise[async_id_symbol] after the run
    console.log(JSON.stringify([built, vm.runInContext('typeof Proxy', ctx)]));`));
  assert.equal(r.signal, null, 'a Proxy trap looped on the host');
  assert.deepEqual(JSON.parse(r.stdout), ['true', 'undefined']);
});

test('an unhandled rejection from a solve drops only that player; the host\'s own still exits as before (Cubic r6 P2)', async () => {
  const yt = await origin(['aaaaaaaa', 'bbbbbbbb']);
  const r = await child(`import vm from 'node:vm'; import { createSolver, newContext } from ${SERVER};
    const solver = createSolver({ origin: ${JSON.stringify(yt.url)}, cacheDir: ${JSON.stringify(tempDir())}, log: { log() {}, warn() {} },
      build: () => { const ctx = newContext(); return { ctx, ...vm.runInContext("({ n: (x) => { if (x === 'reject') Promise.reject(1); return x + 'A'; }, sig: (x) => x.slice(1) })", ctx) }; } });
    process.on('unhandledRejection', solver.rejected);               // as the main block wires it
    await solver.poll(); await solver.poll();
    const [status] = solver.solve({ playerId: 'aaaaaaaa', n: ['reject'], s: [] });
    await new Promise((r) => setTimeout(r, 50));
    console.log(JSON.stringify([status, solver.health().held]));
    Promise.reject(new Error('expected in this test: the host\\'s own unhandled rejection still exits the process'));`);
  yt.close();
  assert.deepEqual(JSON.parse(r.stdout), [200, ['bbbbbbbb']], 'the player whose solve rejected is evicted, not the current one');
  assert.equal(r.code, 1, 'a host rejection was blamed on a player');
});

test('a stop during the startup build clears the crash marker and keeps the healthy player (Cubic r2 P2)',
  { skip: !haveRecording && 'no recorded base.js', timeout: 30_000 }, async () => {
    const hash = 'cccccccc';
    // the recorded player, its sts moved to today: a real build takes ~1.5 s, long enough to stop the server inside it
    const text = fs.readFileSync(PLAYER, 'utf8').replace(/((?:signatureTimestamp|sts)\s*:\s*)\d{5}/, (m, key) => key + today());
    const yt = http.createServer((req, res) => {
      if (req.url === '/iframe_api') return res.end(iframeText(hash));
      if (req.url === `/s/player/${hash}/player_ias.vflset/en_US/base.js`) return res.end(text);
      res.writeHead(404); res.end();
    }).listen(0, '127.0.0.1');
    await once(yt, 'listening');
    const origin = `http://127.0.0.1:${yt.address().port}`;
    const dir = tempDir();
    const marker = path.join(dir, 'building');
    const server = spawn(process.execPath, ['--experimental-vm-modules', new URL('../server.mjs', import.meta.url).pathname], {
      env: { ...process.env, SOLVER_ORIGIN: origin, SOLVER_CACHE_DIR: dir, SOLVER_PORT: '0' }, stdio: ['ignore', 'ignore', 'inherit'] });
    try {
      for (let i = 0; i < 1500 && !fs.existsSync(marker); i++) await new Promise((r) => setTimeout(r, 10));
      assert.ok(fs.existsSync(marker), 'the startup build began');
      server.kill('SIGTERM');                                        // systemctl stop / restart
      const [code, signal] = await once(server, 'exit');
      assert.equal(fs.existsSync(marker), false, 'a clean stop left the crash marker behind');
      assert.deepEqual([code, signal], [0, null]);
      assert.equal(fs.existsSync(path.join(dir, `${hash}.js`)), true, 'the healthy player was dropped');
      const builds = [];
      const restarted = createSolver({ origin, cacheDir: dir, build: fakeBuild(builds), log: quiet });
      await restarted.poll();
      assert.deepEqual([builds.length, restarted.health().status], [1, 'ok'], 'the next start builds it from the cache');
    } finally {
      server.kill('SIGKILL');
      yt.close();
    }
  });

// ---- stage 9 Cubic r7 #1 (P1): dynamic import() inside the realm must not reach the host process
test('player code calling import() in any of the five forms cannot reach the host process (Cubic r7 #1, P1)', async () => {
  const forms = {
    direct: "import('a')",
    directEval: "eval(\"import('b')\")",
    indirectEval: "(0,eval)(\"import('c')\")",
    fn: "Function('return import(' + String.fromCharCode(39) + 'd' + String.fromCharCode(39) + ')')()",
    asyncAwait: "(async () => { await import('e'); })()",
  };
  // Each form runs through the real buildPlayer -> inRealm path (the one every n/sig and base.js already uses). A realm
  // reason that is a primitive string cannot answer constructor.constructor with the host process; a host Error reason can.
  const script = `import vm from 'node:vm'; import { buildPlayer, newContext } from ${SERVER};
    const forms = ${JSON.stringify(forms)};
    const cap = (f) => "globalThis.leak='pending';"
      + "var grab=function(e){try{"
      + "var p=(e!==null&&(typeof e==='object'||typeof e==='function'))?e.constructor.constructor('return process')():'prim';"
      + "globalThis.leak=(p&&typeof p==='object')?('LEAK pid='+p.pid):('safe realmString='+(e!=null&&e.constructor===String));"
      + "}catch(x){globalThis.leak='blocked:'+(x&&x.message);}};"
      + "(" + f + ").then(grab, grab);"
      + "_result.n=function(x){return x+'A';};_result.sig=function(x){return x.slice(1);};";
    const out = {};
    for (const [name, f] of Object.entries(forms)) {
      const ctx = newContext();
      vm.runInContext('globalThis.jsc = () => ({ preprocessed_player: ' + JSON.stringify(cap(f)) + ' })', ctx);
      buildPlayer(ctx, 'x', 1000);
      await new Promise((r) => setTimeout(r, 30));
      vm.runInContext('0', ctx);                           // flush the import() rejection microtask (probe5)
      out[name] = vm.runInContext('globalThis.leak', ctx);
    }
    console.log(JSON.stringify(out));`;
  const r = await child(script);
  assert.equal(r.signal, null, 'the realm hung the host');
  const out = JSON.parse(r.stdout);
  for (const [name, leak] of Object.entries(out)) assert.ok(!leak.includes('LEAK'), `${name}: ${leak}`);
  assert.equal(out.fn, out.direct, 'the Function() form closes exactly like a direct import() (controller)');
});

test('a player that leaves an uncaught import() rejection is evicted, never a process exit (Cubic r7 #2)', async () => {
  const yt = await origin(['aaaaaaaa', 'bbbbbbbb']);
  const r = await child(`import vm from 'node:vm'; import { createSolver, newContext } from ${SERVER};
    const solver = createSolver({ origin: ${JSON.stringify(yt.url)}, cacheDir: ${JSON.stringify(tempDir())}, log: { log() {}, warn() {} },
      build: () => { const ctx = newContext(); return { ctx, ...vm.runInContext("({ n: (x) => { if (x === 'imp') import('nope'); return x + 'A'; }, sig: (x) => x.slice(1) })", ctx) }; } });
    process.on('unhandledRejection', solver.rejected);               // as the main block wires it
    await solver.poll(); await solver.poll();
    const [status] = solver.solve({ playerId: 'aaaaaaaa', n: ['imp'], s: [] });
    await new Promise((r) => setTimeout(r, 80));
    console.log(JSON.stringify([status, solver.health().held]));`);
  yt.close();
  assert.equal(r.signal, null, 'the import() rejection hung or killed the host');
  assert.equal(r.code, 0, 'the import() rejection exited the process');
  assert.deepEqual(JSON.parse(r.stdout), [200, ['bbbbbbbb']], 'the player whose import() rejected is evicted; the other stays');
});

test('two players that each leave a realm rejection are both skipped until a restart (controller P3-2)', async () => {
  const yt = await origin(['aaaaaaaa', 'bbbbbbbb', 'aaaaaaaa']);
  const warnings = [];
  const solver = createSolver({ origin: yt.url, cacheDir: tempDir(), build: fakeBuild(), log: { log() {}, warn: (m) => warnings.push(m) } });
  await solver.poll(); await solver.poll();                        // adopt aaaaaaaa, bbbbbbbb
  const realmRejection = Object.create(null);                      // a non-host promise: rejected() treats it as a realm one
  solver.solve({ playerId: 'aaaaaaaa', n: ['x'], s: [] }); solver.rejected(1, realmRejection);   // crash attributed to aaaaaaaa
  solver.solve({ playerId: 'bbbbbbbb', n: ['x'], s: [] }); solver.rejected(1, realmRejection);   // crash attributed to bbbbbbbb
  warnings.length = 0;
  await solver.poll();                                             // iframe_api -> aaaaaaaa again
  assert.equal(solver.health().lastPoll.step, 'hash', 'the earlier-crashed player was rebuilt, not skipped');
  assert.ok(!solver.health().held.includes('aaaaaaaa'), 'the earlier-crashed player was re-adopted');
  assert.match(warnings.at(-1), /skipped until a restart/);
  yt.close();
});
