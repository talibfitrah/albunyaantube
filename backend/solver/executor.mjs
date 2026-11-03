// FitrahTube playback challenge solver: the EXECUTOR (S20). The one process that runs YouTube-supplied player JS. It is
// spawned per connection by fitrah-solver-exec.socket, its stdin and stdout that connection, and has no network, no secret
// and nothing writable (fitrah-solver-exec@.service): player JS that escapes the vm realm below (Cubic r13 #1, r14 P1) reaches
// host JS here and nothing else. The gateway (server.mjs) fetches, caches and validates. Node built-ins only.
// Protocol: one JSON object per line. Requests {op: 'build'|'solve'|'drop', ...}, one reply each, in order: {ok, value} or
// {ok: false, error}. Unasked: {event: 'rejected', hash} when a player left an unhandled rejection (Cubic r6 P2).
import fs from 'node:fs';
import path from 'node:path';
import readline from 'node:readline';
import vm from 'node:vm';
import { fileURLToPath, pathToFileURL } from 'node:url';

// Dynamic import() inside the realm is the one surface vm cannot strip: it is syntax, not a deletable global. Left alone,
// `import('x')` rejects with a HOST TypeError (ERR_VM_DYNAMIC_IMPORT_CALLBACK_MISSING, or ..._FLAG without
// --experimental-vm-modules), and `e.constructor.constructor('return process')()` then hands the player the host process
// (Cubic r7 #1, P1). Node takes the callback from the import()'s referrer, so this is passed in BOTH places; either alone leaks:
// - on EACH runInContext that runs EJS or player code: code that script compiles (direct, eval, indirect eval, Function, await)
//   uses the script's own setting, and a script without one gets the host TypeError even when the context has a callback
//   (newContext's `delete` line defines no function, so nothing ever imports with it as the referrer);
// - on the context (newContext): code compiled with no script on the stack, i.e. in a promise job
//   (`Promise.resolve('return import("z")').then(Function)`, `.then(eval)`), has no referrer and uses the context's (Cubic r9 #1).
// It needs --experimental-vm-modules (newContext asserts it). It throws a primitive, so the realm's rejection reason is never a
// host object. The throw still makes one host object: Node's importModuleDynamicallyCallback is async, so it turns the throw
// into a rejected HOST promise, and the realm promise import() returns adopts it only in a realm microtask. A vm timeout that
// cuts the run before that microtask leaves the host promise unhandled with DENIED as its reason (Cubic r10 #2; see rejected).
const DENIED = 'import() is disabled inside the solver realm';
const denyImport = () => { throw DENIED; };

const EJS_DIR = path.join(path.dirname(fileURLToPath(import.meta.url)), 'vendor', 'ejs-0.8.0');
const EJS_SOURCE = fs.readFileSync(path.join(EJS_DIR, 'lib.min.js'), 'utf8')
  + '\nObject.assign(globalThis, lib);\n'
  + fs.readFileSync(path.join(EJS_DIR, 'core.min.js'), 'utf8')
  + '\nglobalThis.jsc = jsc;';

/** A fresh realm holding EJS and nothing of Node's (R40). One per player, so eviction drops it whole. */
export function newContext(timeoutMs = 10_000) {
  // Fail closed: without --experimental-vm-modules every importModuleDynamically callback below, the context's and each run's,
  // is ignored and import() leaks the host process (Cubic r7 #1). SourceTextModule is the flag's own marker. No realm is ever
  // built unprotected. The context's callback covers import() with no referrer (Cubic r9 #1; see denyImport).
  if (typeof vm.SourceTextModule !== 'function') throw new Error('the solver realm needs --experimental-vm-modules to deny dynamic import (Cubic r7 #1)');
  // a null-prototype global: `{}` would hand the realm the host's Object.prototype, and constructor.constructor the host's process
  const ctx = vm.createContext(Object.create(null), { microtaskMode: 'afterEvaluate', importModuleDynamically: denyImport }); // microtasks drain inside the timeout
  // V8 runs these as host tasks, outside every vm timeout: a FinalizationRegistry cleanup (Cubic r2 P2; WeakRef goes with it)
  // and a WebAssembly async compile's resolve, which reads a realm `then` getter off the module (Cubic r6 P2). Node reads
  // promise[async_id_symbol] off each unhandled rejected promise after the run, which only a realm Proxy could answer with code
  // (Cubic r6 P2). Atomics.waitAsync settles with a string, so it only queues microtasks, which drain inside the next timeout.
  // base.js checks `typeof FinalizationRegistry` and `window.WebAssembly`; neither it nor EJS uses Proxy.
  vm.runInContext('delete globalThis.FinalizationRegistry; delete globalThis.WeakRef; delete globalThis.WebAssembly; delete globalThis.Proxy;', ctx);
  vm.runInContext(EJS_SOURCE, ctx, { timeout: timeoutMs, importModuleDynamically: denyImport });
  return ctx;
}

/** Runs `source` in `ctx` under the vm timeout with `inputs` as globals, and returns what it evaluated to. Nothing the realm
 *  controls runs on the host (Cubic P2): inputs are defined, never assigned, so no realm setter fires; a throw comes back
 *  as a host Error whose message the realm built under the same timeout. Callers check the answer's type before using it. */
function inRealm(ctx, source, inputs, timeoutMs) {
  const names = Object.keys(inputs);
  for (const name of names) Object.defineProperty(ctx, name, { value: inputs[name], writable: true, configurable: true });
  let out;
  try {
    out = vm.runInContext(`(() => { try { return [${source}]; } catch (e) { try { return '' + (e?.message ?? e); } catch { return ''; } } })()`,
      ctx, { timeout: timeoutMs, importModuleDynamically: denyImport });   // deny import() in all player code (Cubic r7 #1)
  } finally { for (const name of names) Reflect.deleteProperty(ctx, name); }        // never throws on a locked property
  if (typeof out === 'string') throw new Error(out || 'the player threw');
  return out[0];                                                                     // our own array literal: no getter
}

/** A realm answer that must be a JSON string; anything else is refused unread. */
function parsed(answer) {
  if (typeof answer !== 'string') throw new Error('the player answered a non-string');
  return JSON.parse(answer);
}

/** Preprocesses one base.js and pulls out its n/sig functions, entirely inside `ctx` under the vm timeout (R27). */
export function buildPlayer(ctx, playerText, timeoutMs = 10_000) {
  const fns = inRealm(ctx, `(() => {
      const pre = jsc({ type: 'player', player: __player, requests: [], output_preprocessed: true }).preprocessed_player;
      const r = { n: null, sig: null };
      Function('_result', pre)(r);
      const n = r.n, sig = r.sig;                     // read here, under the timeout: a getter never runs on the host
      return typeof n === 'function' && typeof sig === 'function' ? [n, sig] : null;
    })()`, { __player: playerText }, timeoutMs);
  if (fns === null) throw new Error('no n/sig functions in this player');
  return { ctx, n: fns[0], sig: fns[1] };
}


/** The held functions, run inside the realm under the same vm timeout as a build (R40), only strings crossing in and out.
 *  Each answer is the function's string, or null when it threw or answered a non-string; the gateway keeps only usable
 *  ones. Throws on timeout. */
export function solveRaw(player, n, s, timeoutMs = 10_000) {
  return parsed(inRealm(player.ctx, `(() => {
      const run = (fn, list) => list.map((c) => { try { const v = fn(c); return typeof v === 'string' ? v : null; } catch { return null; } });
      const [n, s] = JSON.parse(__solveIn);
      return JSON.stringify([run(__solveN, n), run(__solveS, s)]);
    })()`, { __solveN: player.n, __solveS: player.sig, __solveIn: JSON.stringify([n, s]) }, timeoutMs));
}

const usage = (cpu0) => { const cpu = process.cpuUsage(cpu0); return { cpuMs: (cpu.user + cpu.system) / 1000, rssMiB: Math.round(process.memoryUsage.rss() / 1_048_576) }; };

/** The held players and the three ops. `build` is a test seam; production passes none. */
export function createExecutor(build = (text) => buildPlayer(newContext(), text)) {
  const held = new Map();                       // hash -> player; the gateway decides what is held (R25, R27)
  let ran = null;                               // the player whose realm ran last
  return {
    build(hash, text) {
      ran = hash;
      held.delete(hash);
      const cpu0 = process.cpuUsage();
      held.set(hash, build(text));
      return usage(cpu0);
    },
    solve(hash, n, s, timeoutMs) {
      const player = held.get(hash);
      if (!player) throw new Error('unknown player');
      ran = hash;
      const cpu0 = process.cpuUsage();
      try {
        return { raw: solveRaw(player, n, s, timeoutMs), ...usage(cpu0) };   // R30 is the realm run's CPU
      } catch (error) {
        held.delete(hash);                      // a hung or broken player is evicted; others stay served (ruling 1)
        throw error;
      }
    },
    drop(hash) { held.delete(hash); return null; },
    /** The 'unhandledRejection' listener (Cubic r6 P2). Node reports a realm's rejection once the run that made it has
     *  returned, before any other task, so it is the last-run player's: dropped here, named to the gateway, which skips it
     *  until a restart. So is the host promise Node made for the realm's import(): only denyImport rejects one with DENIED.
     *  The realm's reason is never read; any other host promise's rejection still exits as Node's default does. */
    rejected(reason, promise) {
      if (Object.getPrototypeOf(promise) === Promise.prototype && reason !== DENIED) throw reason;
      held.delete(ran);
      return ran;
    },
  };
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  // Fail closed before reading a request (Cubic r7 #1): without the flag the import() deny callback is ignored.
  if (typeof vm.SourceTextModule !== 'function') {
    console.error('FATAL: the executor needs --experimental-vm-modules; without it dynamic import() inside the realm leaks the host process (Cubic r7 #1). Refusing to start.');
    process.exit(1);
  }
  const executor = createExecutor();
  const send = (message) => process.stdout.write(`${JSON.stringify(message)}\n`);
  process.on('unhandledRejection', (reason, promise) => send({ event: 'rejected', hash: executor.rejected(reason, promise) }));
  readline.createInterface({ input: process.stdin, crlfDelay: Infinity }).on('line', (line) => {
    let reply;
    try {
      const { op, hash, text, n, s, timeoutMs } = JSON.parse(line);
      if (op === 'build') reply = executor.build(hash, text);
      else if (op === 'solve') reply = executor.solve(hash, n, s, timeoutMs);
      else throw new Error('unknown op');
      reply = { ok: true, value: reply };
    } catch (error) {
      reply = { ok: false, error: String(error?.message ?? error) };       // host errors: inRealm makes the realm's one
    }
    send(reply);
  }).on('close', () => process.exit(0));         // the gateway hung up: nothing is held for anyone else
}
