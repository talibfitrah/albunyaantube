// Test harness (not a test file). (1) The Mac's stand-in for fitrah-solver-exec.socket (Accept=yes): each connection gets
// its own executor, the connection its stdin and stdout, as systemd does, under sandbox-exec (the macOS kernel sandbox) with
// a profile built from the executor unit's own lines (execProfile): dropping a line there drops the deny here. (2) The
// pre-S20 in-process shapes the realm tests use, now split between the executor (runs) and the gateway (checks).
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import fs from 'node:fs';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createExecutor, solveRaw } from '../executor.mjs';
import { answers, createSolver, selfTestInputs, selfTestPassed } from '../server.mjs';

const SOLVER = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
export const EXEC_UNIT = fs.readFileSync(path.join(SOLVER, '..', 'scripts', 'systemd', 'fitrah-solver-exec@.service'), 'utf8');

/** The unit's ExecStart flags (between the node binary and the script), with /opt/fitrah-solver/ moved to `root`. */
export function execFlags(root) {
  const exec = EXEC_UNIT.replace(/\\\n\s*/g, ' ').match(/^ExecStart=(.*)$/m)[1].trim().split(/\s+/);
  return exec.slice(1, -1).map((flag) => flag.replace('/opt/fitrah-solver/', `${root}/`));
}

/** sandbox-exec's profile for what the unit promises: no network, no write, and `hidden` (the Mac's places for /opt, /var,
 *  /run) unreadable, and no exec but node's. Each deny is emitted only while the unit carries the lines that give it on Linux. */
function execProfile(hidden) {
  const has = (line) => EXEC_UNIT.split('\n').includes(line);
  return ['(version 1)', '(allow default)',
    has('PrivateNetwork=yes') && has('IPAddressDeny=any') ? '(deny network*)' : '',
    has('LimitFSIZE=0') && has('ProtectSystem=strict') ? '(deny file-write*)' : '',
    has('TemporaryFileSystem=/opt:ro /var:ro /run:ro /tmp:ro /var/tmp:ro /dev/shm:ro /dev/pts:ro') ? `(deny file-read* ${hidden.map((d) => `(subpath ${JSON.stringify(d)})`).join(' ')})` : '',
    has('NoExecPaths=/ /opt/fitrah-solver/app') && has('ExecPaths=/opt/fitrah-solver/node /usr/lib/x86_64-linux-gnu')
      ? `(deny process-exec*)(allow process-exec* (literal ${JSON.stringify(process.execPath)}))` : '',
  ].join('');
}

/** Listens like the socket unit; returns its path. `withoutPermissionModel` drops Node's --permission flags, leaving the
 *  kernel sandbox alone between escaped code and the machine. Every executor is SIGKILLed and the socket removed in t.after. */
export async function activator(t, { root, hidden = [], preload = [], withoutPermissionModel = false, onStderr = () => {} }) {
  const dir = fs.mkdtempSync(path.join(fs.realpathSync(os.tmpdir()), 'fitrah-exec-'));
  const socketPath = path.join(dir, 'exec.sock');
  const children = new Set();
  const server = net.createServer({ pauseOnConnect: true }, (connection) => {
    const flags = execFlags(root).filter((f) => !withoutPermissionModel || !/^--(permission|allow-)/.test(f));
    const node = [process.execPath, ...flags, ...preload, path.join(root, 'app', 'executor.mjs')];
    const child = spawn('/usr/bin/sandbox-exec', ['-p', execProfile(hidden), ...node], { stdio: [connection, connection, 'pipe'] });
    connection.destroy();                       // systemd keeps no copy either: the executor's exit is the gateway's EOF
    children.add(child);
    child.stderr.on('data', (d) => onStderr(String(d)));
    child.on('exit', () => children.delete(child));
  }).listen(socketPath);
  await once(server, 'listening');
  t.after(() => { for (const child of children) child.kill('SIGKILL'); server.close(); fs.rmSync(dir, { recursive: true, force: true }); });
  return { socketPath, children };
}

/** {n: {challenge: answer}, s: {...}}: the executor's raw answers as the gateway keeps them. */
export const solveAll = (player, n, s, timeoutMs) => answers(n, s, solveRaw(player, n, s, timeoutMs));

/** R26 on one in-process player, as the gateway runs it through the executor. */
export function selfTest(player, timeoutMs) {
  const inputs = selfTestInputs();
  return selfTestPassed(inputs, solveRaw(player, inputs.map(([n]) => n), inputs.map(([, s]) => s), timeoutMs));
}

/** createSolver over one in-process executor built with `build` (text -> player), standing in for every player's own; it rides
 *  along for `rejected`. */
export function solverWith({ build, ...options }) {
  const executor = createExecutor(build);
  const connect = (hash) => ({ build: async (h, text) => executor.build(h, text), solve: async (...args) => executor.solve(...args),
                               close: () => executor.drop(hash) });
  return Object.assign(createSolver({ ...options, connect }), { executor });
}
