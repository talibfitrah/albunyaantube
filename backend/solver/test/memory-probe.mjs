// Run ONLY under the unit's flags (sandbox.test.mjs): two held players, one realm each, then 1,000 solves (R25, §3.2).
// Also times each in-realm solve (R30: p95 <= 2 ms).
import fs from 'node:fs';
import { buildPlayer, newContext, selfTest, solveAll } from './server.mjs';

const text = fs.readFileSync(process.argv[2], 'utf8');
const held = [buildPlayer(newContext(), text), buildPlayer(newContext(), text)];
if (!held.every((player) => selfTest(player))) throw new Error('self-test failed');
const rssMiB = () => Math.round(process.memoryUsage().rss / 1_048_576);
const afterBuild = rssMiB();
const ms = [];
for (let i = 0; i < 1000; i++) {
  const t0 = performance.now();
  solveAll(held[i % 2], ['ABCDEFGHIJKLMNOP'], ['S'.repeat(108)]);
  ms.push(performance.now() - t0);
}
ms.sort((a, b) => a - b);
// resourceUsage().maxRSS is in kilobytes (Node docs); a number in the hundreds of thousands means bytes: report it.
console.log(JSON.stringify({
  maxRssMiB: Math.round(process.resourceUsage().maxRSS / 1024), afterBuildMiB: afterBuild, steadyMiB: rssMiB(),
  p50Ms: Number(ms[499].toFixed(3)), p95Ms: Number(ms[949].toFixed(3)),
}));
