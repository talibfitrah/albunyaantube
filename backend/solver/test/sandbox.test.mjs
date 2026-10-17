// The sidecar under the systemd unit's exact ExecStart flags (spec §6.3, R31, R40), with the unit's two directories
// moved to temp dirs. realpath: macOS's /var is a symlink, and the permission model checks resolved paths.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawn, spawnSync } from 'node:child_process';
import { once } from 'node:events';
import fs from 'node:fs';
import http from 'node:http';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { signatureTimestamp } from '../server.mjs';

const SOLVER = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const UNIT = fs.readFileSync(path.join(SOLVER, '..', 'scripts', 'systemd', 'fitrah-solver.service'), 'utf8');
const PLAYER = process.env.SOLVER_TEST_PLAYER
  ?? path.join(SOLVER, '..', '..', '.superpowers/sdd/2026-10-14-playback-phase2/spike/out/base.js');
const playerText = fs.existsSync(PLAYER) ? fs.readFileSync(PLAYER, 'utf8') : null;
const playerFresh = playerText !== null && signatureTimestamp(playerText, Date.now()) !== null;

function unitFlags(root, cache) {
  const exec = UNIT.replace(/\\\n\s*/g, ' ').match(/^ExecStart=(.*)$/m)[1].trim().split(/\s+/);
  return exec.slice(1, -1).map((flag) => flag.replace('/opt/fitrah-solver/', `${root}/`).replace('/var/cache/fitrah-solver/', `${cache}/`));
}

/** Everything a test makes in the temp dir is removed in t.after, so a failed or timed-out run leaves nothing. */
function install(t) {
  const tmp = fs.realpathSync(os.tmpdir());
  const root = fs.mkdtempSync(path.join(tmp, 'fitrah-solver-root-'));
  const cache = fs.mkdtempSync(path.join(tmp, 'fitrah-solver-cache-'));
  t.after(() => { fs.rmSync(root, { recursive: true, force: true }); fs.rmSync(cache, { recursive: true, force: true }); });
  fs.mkdirSync(path.join(root, 'app'));
  fs.cpSync(path.join(SOLVER, 'server.mjs'), path.join(root, 'app', 'server.mjs'));
  fs.cpSync(path.join(SOLVER, 'vendor'), path.join(root, 'app', 'vendor'), { recursive: true });
  fs.cpSync(path.join(SOLVER, 'test', 'sandbox-probe.mjs'), path.join(root, 'app', 'sandbox-probe.mjs'));
  fs.cpSync(path.join(SOLVER, 'test', 'memory-probe.mjs'), path.join(root, 'app', 'memory-probe.mjs'));
  return { root, cache };
}

/** Prod's layout (DynamicUser): /var/cache/fitrah-solver is a RELATIVE link to private/fitrah-solver. */
function relativeCacheLink(t) {
  const parent = fs.mkdtempSync(path.join(fs.realpathSync(os.tmpdir()), 'fitrah-cache-parent-'));
  t.after(() => fs.rmSync(parent, { recursive: true, force: true }));
  fs.mkdirSync(path.join(parent, 'private', 'fitrah-solver'), { recursive: true });
  const link = path.join(parent, 'fitrah-solver');
  fs.symlinkSync(path.join('private', 'fitrah-solver'), link);
  return link;
}

async function freePort() {
  const server = net.createServer().listen(0, '127.0.0.1');
  await once(server, 'listening');
  const { port } = server.address();
  server.close();
  return port;
}

test('the unit carries the spec\'s flags and hardening lines (R31)', () => {
  assert.equal(process.version, 'v22.23.2', 'the pinned Node (R25); the numbers below were measured on it');
  const tokens = UNIT.replace(/\\\n\s*/g, ' ').match(/^ExecStart=(.*)$/m)[1].trim().split(/\s+/);
  assert.equal(tokens[0], '/opt/fitrah-solver/node/bin/node');
  assert.equal(tokens.at(-1), '/opt/fitrah-solver/app/server.mjs');
  assert.deepEqual(unitFlags('/R', '/C'),
    ['--permission', '--allow-fs-read=/R/', '--allow-fs-read=/C/', '--allow-fs-write=/C/', '--max-old-space-size=256']);
  for (const line of ['MemoryMax=384M', 'StartLimitIntervalSec=3600', 'StartLimitBurst=4', 'RestartSec=60', 'CPUWeight=20',
    'DynamicUser=yes', 'CacheDirectory=fitrah-solver', 'ProtectHome=yes', 'InaccessiblePaths=-/opt/albunyaan',
    'PrivateDevices=yes', 'CapabilityBoundingSet=', 'RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX',
    'Environment=SOLVER_BIND=127.0.0.1 SOLVER_PORT=4417', 'Restart=on-failure',
    'SystemCallArchitectures=native', 'RestrictNamespaces=yes', 'LockPersonality=yes', 'ProtectKernelTunables=yes',
    'ProtectKernelModules=yes', 'ProtectKernelLogs=yes', 'ProtectControlGroups=yes', 'ProtectProc=invisible']) {
    assert.ok(UNIT.split('\n').includes(line), line);
  }
  assert.ok(!/MemoryDenyWriteExecute/.test(UNIT), 'V8 needs writable+executable memory');
});

test('under the unit flags, reads outside, writes outside the cache and child processes are denied (R40)', (t) => {
  const { root, cache } = install(t);
  const outside = path.join(fs.realpathSync(os.tmpdir()), `fitrah-outside-${process.pid}`);
  t.after(() => fs.rmSync(outside, { force: true }));
  fs.writeFileSync(outside, 'not for the solver');
  const run = spawnSync(process.execPath,
    [...unitFlags(root, cache), path.join(root, 'app', 'sandbox-probe.mjs'), outside, path.join(root, 'app', 'x'), path.join(cache, 'x')],
    { encoding: 'utf8', timeout: 25_000 });
  assert.equal(run.status, 0, run.stderr);
  assert.deepEqual(JSON.parse(run.stdout),
    { readOutside: 'ERR_ACCESS_DENIED', writeApp: 'ERR_ACCESS_DENIED', writeCache: 'allowed', spawn: 'ERR_ACCESS_DENIED' });
});

/** Plan review M4 / spec §3.6: systemd's DynamicUser lays CacheDirectory out as /var/cache/X -> private/X, and the unit's
 *  flags name the link. Settled here on the Mac before prod's /health `cacheWritable` settles it on Linux. */
test('the cache directory reached through a relative symlink is still writable under the unit flags', (t) => {
  const { root } = install(t);
  const link = relativeCacheLink(t);
  const outside = path.join(fs.realpathSync(os.tmpdir()), `fitrah-outside-${process.pid}-link`);
  t.after(() => fs.rmSync(outside, { force: true }));
  fs.writeFileSync(outside, 'not for the solver');
  const run = spawnSync(process.execPath,
    [...unitFlags(root, link), path.join(root, 'app', 'sandbox-probe.mjs'), outside, path.join(root, 'app', 'x'), path.join(link, 'x')],
    { encoding: 'utf8', timeout: 25_000 });
  assert.equal(run.status, 0, run.stderr);
  assert.equal(JSON.parse(run.stdout).writeCache, 'allowed', 'the permission model refused the symlinked cache');
});

/** Two held players, then three R27 rollovers (evict the older, build a new one). The hard cap is the unit's MemoryMax=384M;
 *  300 MiB is a measured budget that only warns (coordinator ruling, fix round 1). */
test('two held players and three rollovers under the unit flags peak under the 384 MiB cap (spec §3.2, §3.5, R27)',
  { skip: !playerText && 'SOLVER_TEST_PLAYER missing', timeout: 30_000 }, (t) => {
    const { root } = install(t);
    fs.writeFileSync(path.join(root, 'player.js'), playerText);       // inside the read allowlist
    const cache = fs.mkdtempSync(path.join(fs.realpathSync(os.tmpdir()), 'fitrah-solver-cache-'));
    t.after(() => fs.rmSync(cache, { recursive: true, force: true }));
    const run = spawnSync(process.execPath,
      [...unitFlags(root, cache), path.join(root, 'app', 'memory-probe.mjs'), path.join(root, 'player.js')], { encoding: 'utf8', timeout: 25_000 });
    assert.equal(run.status, 0, run.stderr);                           // no OOM under --max-old-space-size=256
    const probe = JSON.parse(run.stdout);
    console.log(`memory probe: ${JSON.stringify(probe)}`);
    if (probe.rolloverMaxRssMiB >= 300) console.log(`WARN rollover peak ${probe.rolloverMaxRssMiB} MiB is at or over the 300 MiB budget`);
    assert.ok(probe.twoHeldMaxRssMiB > 50, `two-held peak ${probe.twoHeldMaxRssMiB} MiB`);
    assert.ok(probe.rolloverMaxRssMiB < 384, `rollover peak ${probe.rolloverMaxRssMiB} MiB is over MemoryMax`);
    // probe.p95Ms is the in-realm wall clock, recorded only; R30's bar is on the server's handler CPU (the e2e test below).
  });

test('the real server under the unit flags adopts a player, then 1,000 solves leave RSS flat and p95 CPU at most 2 ms (R30)',
  { skip: !playerFresh && 'SOLVER_TEST_PLAYER missing or more than 60 days old: point it at a fresh base.js', timeout: 30_000 },
  async (t) => {
    const { root } = install(t);
    const cache = relativeCacheLink(t);   // every cache read, write and rename goes through the prod-style link
    const hash = '0229cd64';
    const yt = http.createServer((req, res) => {
      if (req.url === '/iframe_api') return res.end(`'https:\\/\\/www.youtube.com\\/s\\/player\\/${hash}\\/www-widgetapi.vflset\\/x.js'`);
      if (req.url === `/s/player/${hash}/player_ias.vflset/en_US/base.js`) return res.end(playerText);
      res.writeHead(404); res.end();
    }).listen(0, '127.0.0.1');
    await once(yt, 'listening');
    const port = await freePort();
    const child = spawn(process.execPath, [...unitFlags(root, cache), path.join(root, 'app', 'server.mjs')], {
      env: { ...process.env, SOLVER_ORIGIN: `http://127.0.0.1:${yt.address().port}`, SOLVER_CACHE_DIR: cache, SOLVER_PORT: String(port) },
      stdio: ['ignore', 'inherit', 'inherit'] });
    t.after(() => { child.kill(); yt.close(); });
    const base = `http://127.0.0.1:${port}`;
    const health = () => fetch(`${base}/health`).then((r) => r.json()).catch(() => null);
    try {
      let h = null;
      for (let i = 0; i < 75 && h?.status !== 'ok'; i++) { await new Promise((r) => setTimeout(r, 200)); h = await health(); }
      assert.equal(h?.status, 'ok');
      assert.equal(h.cacheWritable, true);
      const rnd = (n) => Array.from({ length: n }, () => 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_'[Math.floor(Math.random() * 64)]).join('');
      const solveOnce = () => fetch(`${base}/solve`, { method: 'POST',
        body: JSON.stringify({ playerId: hash, n: [rnd(16)], s: Array.from({ length: 7 }, () => rnd(108)) }) });
      for (let i = 0; i < 100; i++) await solveOnce();
      const before = (await health()).rssMiB;
      for (let i = 0; i < 1000; i++) assert.equal((await solveOnce()).status, 200);
      const after = await health();
      console.log(`rss ${before} -> ${after.rssMiB} MiB, p95 ${after.p95CpuMs} ms, ${after.solves} solves`);
      assert.ok(after.rssMiB - before < 20, `RSS grew ${after.rssMiB - before} MiB`);
      assert.ok(after.p95CpuMs <= 2, `p95 ${after.p95CpuMs} ms`);       // R30
    } finally { /* cleanup is in t.after */ }
  });
