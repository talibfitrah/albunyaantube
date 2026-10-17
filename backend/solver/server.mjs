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

/** The held functions, called directly to keep a solve near 1 ms; no timeout (R40's accepted residual).
 *  A challenge whose function throws or answers outside the alphabet is omitted. */
export function solveAll(player, n, s) {
  const out = { n: {}, s: {} };
  for (const [list, fn, into] of [[n, player.n, out.n], [s, player.sig, out.s]]) {
    for (const challenge of list) {
      try {
        const answer = fn(challenge);
        if (usable(answer)) into[challenge] = answer;
      } catch { /* omitted, never logged */ }
    }
  }
  return out;
}
