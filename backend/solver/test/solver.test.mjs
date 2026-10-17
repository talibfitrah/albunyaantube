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
