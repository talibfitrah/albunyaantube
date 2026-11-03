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
import { activator, execFlags, EXEC_UNIT } from './harness.mjs';

const SOLVER = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const UNIT = fs.readFileSync(path.join(SOLVER, '..', 'scripts', 'systemd', 'fitrah-solver.service'), 'utf8');
const WATCHDOG = fs.readFileSync(path.join(SOLVER, '..', 'scripts', 'systemd', 'fitrah-solver-watchdog.service'), 'utf8');
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
  fs.cpSync(path.join(SOLVER, 'executor.mjs'), path.join(root, 'app', 'executor.mjs'));
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
    ['--permission', '--allow-fs-read=/R/', '--allow-fs-read=/C/', '--allow-fs-write=/C/', '--max-old-space-size=96']);
  for (const line of ['MemoryMax=192M', 'StartLimitIntervalSec=3600', 'StartLimitBurst=4', 'RestartSec=60', 'CPUWeight=20',
    'DynamicUser=yes', 'CacheDirectory=fitrah-solver', 'ProtectHome=yes', 'InaccessiblePaths=-/opt/albunyaan',
    'PrivateDevices=yes', 'CapabilityBoundingSet=', 'RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX',
    'Environment=SOLVER_BIND=127.0.0.1 SOLVER_PORT=4417 SOLVER_EXEC_SOCKET=/run/fitrah-solver-exec.sock', 'Restart=on-failure',
    'Requires=fitrah-solver-exec.socket', 'ExecStopPost=-+/bin/systemctl kill --signal=SIGKILL fitrah-solver-exec@*.service',
    'IPAddressDeny=10.0.0.0/8 172.16.0.0/12 192.168.0.0/16 169.254.0.0/16 100.64.0.0/10 fc00::/7 fe80::/10',
    'SystemCallArchitectures=native', 'RestrictNamespaces=yes', 'LockPersonality=yes', 'ProtectKernelTunables=yes',
    'ProtectKernelModules=yes', 'ProtectKernelLogs=yes', 'ProtectControlGroups=yes', 'ProtectProc=invisible']) {
    assert.ok(UNIT.split('\n').includes(line), line);
  }
  assert.ok(!/MemoryDenyWriteExecute/.test(UNIT), 'V8 needs writable+executable memory');
});

/** S20: the executor unit is the boundary (Cubic r13 #1, r14 P1). Only Linux can prove these lines (the deploy's
 *  verification commands); the Mac proves the code runs under their sandbox-exec equivalent (the escape drill below). */
test('the executor unit runs player JS with no network, no secret and nothing writable (S20)', () => {
  const exec = EXEC_UNIT.replace(/\\\n\s*/g, ' ').match(/^ExecStart=(.*)$/m)[1].trim().split(/\s+/);
  assert.equal(exec[0], '/opt/fitrah-solver/node/bin/node');
  assert.equal(exec.at(-1), '/opt/fitrah-solver/app/executor.mjs');
  assert.deepEqual(execFlags('/R'), ['--experimental-vm-modules', '--permission', '--allow-fs-read=/R/app/', '--max-old-space-size=256']);
  for (const line of ['StandardInput=socket', 'StandardOutput=socket', 'DynamicUser=yes', 'User=fsx-%i', 'PrivateNetwork=yes', 'IPAddressDeny=any',
    'RestrictAddressFamilies=AF_UNIX', 'TemporaryFileSystem=/opt:ro /var:ro /run:ro /tmp:ro /dev/shm:ro',
    'BindReadOnlyPaths=/opt/fitrah-solver/node /opt/fitrah-solver/app', 'ProtectHome=yes', 'ProtectSystem=strict',
    'LimitFSIZE=0', 'MemoryMax=384M', 'OOMScoreAdjust=1000', 'SyslogLevelPrefix=no', 'Before=fitrah-solver.service', 'CPUWeight=20', 'TasksMax=32', 'PrivateDevices=yes', 'CapabilityBoundingSet=',
    'NoNewPrivileges=yes', 'RestrictNamespaces=yes', 'ProtectProc=invisible']) {
    assert.ok(EXEC_UNIT.split('\n').includes(line), line);
  }
  assert.ok(!/^(ReadWritePaths|StateDirectory|CacheDirectory|LogsDirectory|RuntimeDirectory|PrivateTmp)=/m.test(EXEC_UNIT), 'nothing writable');
  const socket = fs.readFileSync(path.join(SOLVER, '..', 'scripts', 'systemd', 'fitrah-solver-exec.socket'), 'utf8').split('\n');
  for (const line of ['ListenStream=/run/fitrah-solver-exec.sock', 'Accept=yes', 'MaxConnections=4']) assert.ok(socket.includes(line), line);
  assert.match(UNIT, /SOLVER_EXEC_SOCKET=\/run\/fitrah-solver-exec\.sock/, 'the gateway connects where the socket listens');
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
  // the executor's own flags: not even the cache (S20; its unit adds the kernel's denies on top)
  const exec = spawnSync(process.execPath,
    [...execFlags(root), path.join(root, 'app', 'sandbox-probe.mjs'), outside, path.join(root, 'app', 'x'), path.join(cache, 'x')],
    { encoding: 'utf8', timeout: 25_000 });
  assert.equal(exec.status, 0, exec.stderr);
  assert.deepEqual(JSON.parse(exec.stdout),
    { readOutside: 'ERR_ACCESS_DENIED', writeApp: 'ERR_ACCESS_DENIED', writeCache: 'ERR_ACCESS_DENIED', spawn: 'ERR_ACCESS_DENIED' });
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
test('two held players and three rollovers under the executor flags peak under the 384 MiB cap (spec §3.2, §3.5, R27)',
  { skip: !playerText && 'SOLVER_TEST_PLAYER missing', timeout: 30_000 }, (t) => {
    const { root } = install(t);
    fs.writeFileSync(path.join(root, 'app', 'player.js'), playerText);       // inside the executor's read allowlist
    const run = spawnSync(process.execPath,
      [...execFlags(root), path.join(root, 'app', 'memory-probe.mjs'), path.join(root, 'app', 'player.js')], { encoding: 'utf8', timeout: 25_000 });
    assert.equal(run.status, 0, run.stderr);                           // no OOM under --max-old-space-size=256
    const probe = JSON.parse(run.stdout);
    console.log(`memory probe: ${JSON.stringify(probe)}`);
    if (probe.rolloverMaxRssMiB >= 300) console.log(`WARN rollover peak ${probe.rolloverMaxRssMiB} MiB is at or over the 300 MiB budget`);
    assert.ok(probe.twoHeldMaxRssMiB > 50, `two-held peak ${probe.twoHeldMaxRssMiB} MiB`);
    assert.ok(probe.rolloverMaxRssMiB < 384, `rollover peak ${probe.rolloverMaxRssMiB} MiB is over MemoryMax`);
    // probe.p95Ms is the in-realm wall clock, recorded only; R30's bar is on the server's handler CPU (the e2e test below).
  });

/** A fake www.youtube.com serving `text` as player `hash`; closed in t.after. */
async function youtube(t, hash, text) {
  const yt = http.createServer((req, res) => {
    if (req.url === '/iframe_api') return res.end(`'https:\\/\\/www.youtube.com\\/s\\/player\\/${hash}\\/www-widgetapi.vflset\\/x.js'`);
    if (req.url === `/s/player/${hash}/player_ias.vflset/en_US/base.js`) return res.end(text);
    res.writeHead(404); res.end();
  }).listen(0, '127.0.0.1');
  await once(yt, 'listening');
  t.after(() => yt.close());
  return `http://127.0.0.1:${yt.address().port}`;
}

const health = (port) => fetch(`http://127.0.0.1:${port}/health`, { signal: AbortSignal.timeout(2000) }).then((r) => r.json()).catch(() => null);
const todaySts = (text) => text.replace(/((?:signatureTimestamp|sts)\s*:\s*)\d{5}/, (m, key) => key + Math.floor(Date.now() / 86_400_000));

test('the real stack under the unit flags adopts a player, then 1,000 solves leave the executor\'s RSS flat and p95 CPU at most 2 ms (R30)',
  { skip: !playerFresh && 'SOLVER_TEST_PLAYER missing or more than 60 days old: point it at a fresh base.js', timeout: 30_000 },
  async (t) => {
    const { root } = install(t);
    const cache = relativeCacheLink(t);   // every cache read, write and rename goes through the prod-style link
    const hash = '0229cd64';
    const { port } = await launchStack(t, { root, cache, yt: await youtube(t, hash, playerText) });
    const base = `http://127.0.0.1:${port}`;
    let h = null;
    for (let i = 0; i < 75 && h?.status !== 'ok'; i++) { await new Promise((r) => setTimeout(r, 200)); h = await health(port); }
    assert.equal(h?.status, 'ok');
    assert.equal(h.cacheWritable, true);
    const rnd = (n) => Array.from({ length: n }, () => 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_'[Math.floor(Math.random() * 64)]).join('');
    const solveOnce = () => fetch(`${base}/solve`, { method: 'POST',
      body: JSON.stringify({ playerId: hash, n: [rnd(16)], s: Array.from({ length: 7 }, () => rnd(108)) }) });
    for (let i = 0; i < 100; i++) await solveOnce();
    const before = (await health(port)).executorRssMiB;
    const ms = [];
    for (let i = 0; i < 1000; i++) {
      const t0 = performance.now();
      const r = await solveOnce();
      const body = await r.json();
      ms.push(performance.now() - t0);
      assert.equal(r.status, 200);
      if (i === 0) assert.deepEqual([Object.keys(body.n).length, Object.keys(body.s).length], [1, 7], 'answered through the executor');
    }
    ms.sort((x, y) => x - y);
    const after = await health(port);
    console.log(`executor rss ${before} -> ${after.executorRssMiB} MiB, gateway rss ${after.rssMiB} MiB, p95 cpu ${after.p95CpuMs} ms, `
      + `${after.solves} solves, HTTP round trip p50 ${ms[499].toFixed(2)} ms p95 ${ms[949].toFixed(2)} ms`);
    assert.ok(after.executorRssMiB - before < 20, `RSS grew ${after.executorRssMiB - before} MiB`);
    assert.ok(after.p95CpuMs <= 2, `p95 ${after.p95CpuMs} ms`);       // R30
    assert.ok(after.rssMiB < 192, `gateway RSS ${after.rssMiB} MiB is at its unit's MemoryMax`);
  });

/** Cubic r6 P2: Node's default exits on an unhandled rejection, so each systemd restart rebuilt the player from the cache and
 *  died again until StartLimitBurst left HD off for everyone. Since S20 the executor names the player and the gateway skips it. */
test('a player that leaves an unhandled rejection is dropped and the real stack under the unit flags stays up (Cubic r6 P2)',
  { skip: !playerText && 'SOLVER_TEST_PLAYER missing', timeout: 30_000 }, async (t) => {
    const { root, cache } = install(t);
    const hash = 'dddddddd';
    const end = playerText.lastIndexOf('})(_yt_player);');                 // a statement EJS keeps, run at every build
    const text = todaySts(playerText.slice(0, end) + ';g.r6=Promise.reject(new Error("r6"));' + playerText.slice(end));
    const stack = await launchStack(t, { root, cache, yt: await youtube(t, hash, text) });
    let h = null;
    for (let i = 0; i < 100 && stack.gateway.exitCode === null && !h?.lastPoll; i++) {
      await new Promise((r) => setTimeout(r, 200));
      h = await health(stack.port);
    }
    assert.equal(stack.gateway.exitCode, null, `the rejection killed the gateway: ${stack.stderr()}`);
    assert.deepEqual([h.status, h.held], ['warming', []]);
    assert.match(stack.stderr(), /WARN player dddddddd left an unhandled promise rejection/);
    for (let i = 0; i < 50 && stack.executors.size; i++) await new Promise((r) => setTimeout(r, 100));
    assert.equal(stack.executors.size, 0, 'the player\'s own executor exits with it; the next player gets a fresh one');
  });

/** S20: the vm timeout is not a hard bound (Node's one TerminateExecution is lost inside its promise-reject callback). Since
 *  the split the realm runs in the executor, which a hang cannot rescue, so the GATEWAY gives up on it: no answer within the
 *  op limit and it exits 1 with the crash marker naming the player (the next start drops it); its unit's ExecStopPost then
 *  SIGKILLs the executor. A preload busy-loops the executor's main thread from the moment the build arrives. */
test('a hung executor makes the gateway exit 1 with the crash marker naming the player (S20)', { timeout: 30_000 }, async (t) => {
  const { root, cache } = install(t);
  fs.writeFileSync(path.join(root, 'app', 'hang.mjs'),
    "process.stdin.on('data', (d) => { if (String(d).includes('\"op\":\"build\"')) for (;;); });");
  const { socketPath } = await activator(t, { root, hidden: [cache], preload: [`--import=${path.join(root, 'app', 'hang.mjs')}`] });
  const yt = await youtube(t, 'bbbbbbbb', 'var cfg={signatureTimestamp:' + Math.floor(Date.now() / 86_400_000) + '};');
  const gateway = spawn(process.execPath, ['--input-type=module', '-e', `
    import net from 'node:net'; import { connectExecutor, createSolver } from ${JSON.stringify(path.join(root, 'app', 'server.mjs'))};
    const connect = (hash, events) => connectExecutor(net.connect(${JSON.stringify(socketPath)}), { timeoutMs: 1500, ...events,
      onDead: (why) => { events.onDead(why); console.error('DEAD ' + why); process.exit(1); } });
    createSolver({ connect, origin: ${JSON.stringify(yt)}, cacheDir: ${JSON.stringify(cache)}, log: { log() {}, warn: console.error } }).poll();`],
  { stdio: ['ignore', 'ignore', 'pipe'] });
  let stderr = '';
  gateway.stderr.on('data', (d) => { stderr += d; });
  t.after(() => gateway.kill('SIGKILL'));
  const [code] = await once(gateway, 'exit');
  assert.equal(code, 1, stderr);
  assert.match(stderr, /DEAD gave no answer in 1500 ms/);
  assert.equal(fs.readFileSync(path.join(cache, 'building'), 'utf8'), 'bbbbbbbb', 'the next start must drop this player');
});

/** Cubic r2 P2: a stop (systemctl stop / restart) is a clean exit, not a crash. Since S20 the build runs in the executor
 *  while the gateway's loop is free, so SIGTERM can land mid-build: the gateway must clear the crash marker, or the next start
 *  drops a healthy player for an hour. */
test('a stop during the startup build clears the crash marker and keeps the healthy player (Cubic r2 P2)',
  { skip: !playerText && 'SOLVER_TEST_PLAYER missing', timeout: 30_000 }, async (t) => {
    const { root, cache } = install(t);
    const hash = 'cccccccc';
    const marker = path.join(cache, 'building');
    const stack = await launchStack(t, { root, cache, yt: await youtube(t, hash, todaySts(playerText)) });
    for (let i = 0; i < 1500 && !fs.existsSync(marker); i++) await new Promise((r) => setTimeout(r, 10));
    assert.ok(fs.existsSync(marker), 'the startup build began');
    stack.gateway.kill('SIGTERM');                                 // systemctl stop / restart
    const [code, signal] = await once(stack.gateway, 'exit');
    assert.equal(fs.existsSync(marker), false, 'a clean stop left the crash marker behind');
    assert.deepEqual([code, signal], [0, null]);
    assert.equal(fs.existsSync(path.join(cache, `${hash}.js`)), true, 'the healthy player was dropped');
  });

/** The watchdog still curls the gateway: since S20 its loop runs no player code, but one blocked by any bug is still killed. */
test('the watchdog kills only a gateway whose event loop is blocked past a legitimate build (S20)', { timeout: 30_000 }, async (t) => {
  const loop = WATCHDOG.match(/^ExecStart=\/bin\/sh -c '(.*)'$/m)[1];
  const probe = loop.match(/do (curl [^;]*);/)[1];
  assert.ok(Number(probe.match(/--max-time (\d+)/)[1]) > 3 * 10, 'the limit kills a build that every vm timeout still bounds');
  assert.match(loop, /\[ \$\$\? -eq 28 \] && .*systemctl kill --signal=SIGKILL fitrah-solver\.service/);
  const solverPort = UNIT.match(/SOLVER_PORT=(\d+)/)[1];
  assert.match(probe, new RegExp(`http://127\\.0\\.0\\.1:${solverPort}/health`), 'the probe watches the port the solver unit binds');
  const { root, cache } = install(t);
  const go = path.join(root, 'app', 'hang-now');                        // written once the server answers (Cubic r1 P3)
  fs.writeFileSync(path.join(root, 'app', 'hang.mjs'),
    `import fs from 'node:fs'; const t = setInterval(() => { if (fs.existsSync(${JSON.stringify(go)})) { clearInterval(t); for (;;); } }, 50);`);
  const start = async (flags) => {
    const { port } = await launchStack(t, { root, cache, yt: 'http://127.0.0.1:1', gatewayFlags: flags });
    for (let i = 0; i < 50 && !(await health(port)); i++) await new Promise((r) => setTimeout(r, 100));
    return port;
  };
  const check = (port) => new Promise((resolve) => {
    spawn('/bin/sh', ['-c', probe.replace('4417', String(port)).replace(/--max-time \d+/, '--max-time 3')]).on('exit', resolve);
  });
  const [hung, warming, refused] = await Promise.all([start([`--import=${path.join(root, 'app', 'hang.mjs')}`]), start([]), freePort()]);
  fs.writeFileSync(go, '');
  await new Promise((r) => setTimeout(r, 500));                        // the hung gateway's loop is blocked from here on
  assert.deepEqual(await Promise.all([check(hung), check(warming), check(refused)]), [28, 0, 7]);
});

/** Cubic r7 #1: without --experimental-vm-modules the deny callback cannot run and import() leaks the host process, so the
 *  executor must refuse to start rather than run a leaky realm. Spawned WITHOUT its unit flags. */
test('the executor refuses to start without --experimental-vm-modules (Cubic r7 #1)', (t) => {
  const { root } = install(t);
  const run = spawnSync(process.execPath, [path.join(root, 'app', 'executor.mjs')], { encoding: 'utf8', timeout: 8_000, input: '' });
  assert.notEqual(run.status, 0, `the executor started without the flag (status ${run.status}):\n${run.stderr}`);
  assert.match(run.stderr, /experimental-vm-modules/);
});

// ---- S20: OS-level isolation. Threat model: player JS fully escapes the vm realm to host JS in the process that runs it
// (Cubic r13 #1, r14 P1: both reach `process`). The boundary under test is what that process can then do, not the realm.
/** Two live escapes to a HOST error `h`, whose constructor.constructor is the host Function: r14 (an error named by a Symbol
 *  makes Node's stack formatter throw), and r13 (import() near the stack limit: Node's own callback lookup overflows and the
 *  rejection is a host RangeError). Either way, every reach the triage named: TCP to a loopback port standing for the backend
 *  on 127.0.0.1:8080, an HTTP GET, a unix socket standing for /run's, a secret file, writes and a child process. Reports on
 *  stderr, the one channel every topology keeps. An assignment, as `g.r6=` below: EJS drops a bare statement like an IIFE. */
const ROUTES = {
  r14: (attack) => `try { const e = new Error('x'); e.name = Symbol('s'); e.stack; } catch (h) { (${attack})(h); }`,
  // the host error arrives at the stack limit, where any real host work would overflow too: keep it, attack once unwound
  r13: (attack) => `let host = null; const probe = (h) => { if (host || h === null || typeof h !== 'object') return;
      try { if (typeof h.constructor.constructor('return process')() === 'object') host = h; } catch {} };
    let budget = 20000; const deep = () => { try { deep(); } catch (z) { probe(z); }
      if (budget-- > 0 && !host) { try { import('x').then(null, probe); } catch (h) { probe(h); } } };
    deep(); if (host) (${attack})(host);`,
};
const attackCode = ({ route, port, httpUrl, unixPath, secret, writes }) => `(h) => {
  const P = h.constructor.constructor('return process')(); if (typeof P !== 'object') return;
  const g = (m) => P.getBuiltinModule(m), out = { route: ${JSON.stringify(route)}, escaped: 'pid ' + P.pid };
  const t = (k, f) => { try { f(); out[k] = 'allowed'; } catch (z) { out[k] = z.code || String(z.message); } };
  t('read', () => g('fs').readFileSync(${JSON.stringify(secret)}));
  ${JSON.stringify(writes)}.forEach((w, i) => t('write' + i, () => g('fs').writeFileSync(w, 'x')));
  t('spawn', () => g('child_process').execSync('true'));
  let left = 3; const done = () => { if (--left === 0) P.stderr.write('ESCAPE ' + JSON.stringify(out) + '\\n'); };
  const c = (k, o) => { try { const s = g('net').connect(o); s.on('connect', () => { out[k] = 'connected'; s.destroy(); done(); });
    s.on('error', (z) => { out[k] = z.code; done(); }); } catch (z) { out[k] = z.code || String(z.message); done(); } };
  c('tcp', { host: '127.0.0.1', port: ${port} }); c('unix', { path: ${JSON.stringify(unixPath)} });
  try { g('http').get(${JSON.stringify(httpUrl)}, (r) => { out.http = 'allowed'; r.resume(); done(); }).on('error', (z) => { out.http = z.code; done(); }); }
  catch (z) { out.http = z.code || String(z.message); done(); }      // callbacks, not a promise: a realm .then waits for the next run
}`;
const escapeCode = (o) => `;g.s20 = (() => { ${ROUTES[o.route](attackCode(o))} })();`;

/** Starts the solver as production runs it: the gateway under its unit's flags, and its executor through the socket
 *  stand-in, under the executor unit's flags and that unit's sandbox (sandbox-exec), which hides the cache and `hidden`.
 *  Returns the gateway's port and process, the live executors, and everything both wrote on stderr. */
async function launchStack(t, { root, cache, yt, hidden = [], gatewayFlags = [], withoutPermissionModel = false }) {
  let stderr = '';
  const { socketPath, children } = await activator(t, { root, hidden: [cache, ...hidden], withoutPermissionModel, onStderr: (d) => { stderr += d; } });
  const port = await freePort();
  const gateway = spawn(process.execPath, [...unitFlags(root, cache), ...gatewayFlags, path.join(root, 'app', 'server.mjs')], {
    env: { ...process.env, SOLVER_ORIGIN: yt, SOLVER_CACHE_DIR: cache, SOLVER_PORT: String(port), SOLVER_EXEC_SOCKET: socketPath },
    stdio: ['ignore', 'ignore', 'pipe'] });
  gateway.stderr.on('data', (d) => { stderr += d; });
  t.after(() => gateway.kill('SIGKILL'));
  return { port, gateway, executors: children, stderr: () => stderr };
}

/** Listeners that count every reach: a TCP port (the backend), an HTTP server (the internet / LAN), a unix socket. */
async function targets(t) {
  const hits = { tcp: 0, http: 0, unix: 0 };
  const tcp = net.createServer((s) => { hits.tcp++; s.destroy(); }).listen(0, '127.0.0.1');
  const web = http.createServer((q, r) => { hits.http++; r.end('ok'); }).listen(0, '127.0.0.1');
  const dir = fs.mkdtempSync(path.join(fs.realpathSync(os.tmpdir()), 'fitrah-unix-'));
  const unixPath = path.join(dir, 's.sock');
  const unix = net.createServer((s) => { hits.unix++; s.destroy(); }).listen(unixPath);
  await Promise.all([once(tcp, 'listening'), once(web, 'listening'), once(unix, 'listening')]);
  t.after(() => { tcp.close(); web.close(); unix.close(); fs.rmSync(dir, { recursive: true, force: true }); });
  return { hits, port: tcp.address().port, httpUrl: `http://127.0.0.1:${web.address().port}/`, unixPath };
}

/** Twice: under the executor unit's flags, then with Node's permission model stripped, as if the escaped code got past
 *  it too (Node documents it as no sandbox against malicious code): the kernel's denies alone must hold. */
for (const route of ['r14', 'r13']) for (const withoutPermissionModel of [false, true]) {
  test(`player JS that escapes the realm (${route}, in a real base.js) reaches no network, no secret and no writable path${withoutPermissionModel ? ', even past the permission model' : ''} (S20, Cubic r13 #1, r14 P1)`,
    { skip: !playerText && 'SOLVER_TEST_PLAYER missing', timeout: 30_000 }, async (t) => {
      const { root, cache } = install(t);
      const tg = await targets(t);
      const secretDir = fs.mkdtempSync(path.join(fs.realpathSync(os.tmpdir()), 'fitrah-secret-'));   // stands for /opt/albunyaan
      t.after(() => fs.rmSync(secretDir, { recursive: true, force: true }));
      fs.writeFileSync(path.join(secretDir, '.env'), 'FIREBASE_KEY=secret');
      fs.writeFileSync(path.join(cache, 'fetch-log.json'), '[]');                                // a cache file it must not read either
      const writes = [path.join(cache, 'poisoned.js'), path.join(root, 'app', 'pwn.mjs'), path.join(secretDir, 'pwn')];
      const hash = 'eeeeeeee';
      const end = playerText.lastIndexOf('})(_yt_player);');                 // a statement EJS keeps, run at every build
      const text = todaySts(playerText.slice(0, end) + escapeCode({ route, ...tg, secret: path.join(secretDir, '.env'), writes }) + playerText.slice(end));
      const stack = await launchStack(t, { root, cache, yt: await youtube(t, hash, text), hidden: [secretDir], withoutPermissionModel });
      for (let i = 0; i < 100 && !/ESCAPE /.test(stack.stderr()); i++) await new Promise((r) => setTimeout(r, 200));
      await new Promise((r) => setTimeout(r, 300));                    // EJS runs the player twice: let the second reach land too
      const report = JSON.parse(stack.stderr().match(/ESCAPE (.*)/)?.[1] ?? 'null');
      console.log(`escape drill${withoutPermissionModel ? ' (no permission model)' : ''}: ${JSON.stringify(report)} hits ${JSON.stringify(tg.hits)}`);
      assert.match(report?.escaped ?? '', /^pid \d+/, 'the drill must really escape the realm, or it proves nothing');
      assert.deepEqual(tg.hits, { tcp: 0, http: 0, unix: 0 }, 'escaped player code reached a socket');
      for (const key of ['tcp', 'unix', 'http', 'read', 'write0', 'write1', 'write2']) assert.notEqual(report[key], 'allowed', key);
      // a child process inherits every kernel limit (namespace, mounts, rlimits, cgroup); only the permission model refuses it
      if (!withoutPermissionModel) assert.notEqual(report.spawn, 'allowed', 'spawn');
      assert.equal(fs.existsSync(writes[0]), false, 'escaped code poisoned the cache');
    });
}
