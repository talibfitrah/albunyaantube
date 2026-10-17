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

test('a hanging build stops at the vm timeout (R27, R40)', () => {
  const ctx = newContext();
  vm.runInContext('globalThis.jsc = () => { for (;;) {} }', ctx);
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
  const r = spawnSync(process.execPath, ['--input-type=module', '-e', script], { encoding: 'utf8', timeout: 20_000 });
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
    if (/^\/s\/player\/[0-9a-f]{8}\/player_ias\.vflset\/en_US\/base\.js$/.test(req.url)) return res.end(`var cfg={signatureTimestamp:${today()}};`);
    res.writeHead(404); res.end();
  }).listen(0, '127.0.0.1');
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
const hangingBuild = () => { const ctx = newContext(); vm.runInContext('globalThis.jsc = () => { for (;;) {} }', ctx); return buildPlayer(ctx, 'x', 200); };

test('a solve with a synchronous loop stops at the vm timeout (ruling 2)', () => {
  const ctx = newContext();
  const player = { ctx, ...vm.runInContext("({ n: (x) => { if (x === 'spin') for (;;) {} return x + 'A'; }, sig: (x) => x.slice(1) })", ctx) };
  assert.throws(() => solveAll(player, ['abc', 'spin'], ['xyz'], 200), { code: 'ERR_SCRIPT_EXECUTION_TIMEOUT' });
  assert.deepEqual(solveAll(player, ['abc'], ['xyz'], 200), { n: { abc: 'abcA' }, s: { xyz: 'yz' } });   // the realm is reusable
});

test('a solve that times out evicts only that player and answers 500 (ruling 1)', async () => {
  const yt = await origin(['aaaaaaaa', 'bbbbbbbb']);
  const builds = [];
  const spin = () => {
    builds.push(1);
    const ctx = newContext();
    return { ctx, ...vm.runInContext("({ n: (x) => { if (x === 'spin') for (;;) {} return x.split('').reverse().join('') + 'A'; }, sig: (x) => x.slice(1) })", ctx) };
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
    build: () => { const ctx = newContext(); return { ctx, ...vm.runInContext("({ n: () => { for (;;) {} }, sig: (x) => x })", ctx) }; } });
  await solver.poll();
  assert.match(warnings[0], /^WARN poll failed at self-test/);
  assert.equal(solver.health().status, 'warming');
  yt.close();
});
