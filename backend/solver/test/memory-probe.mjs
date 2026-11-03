// Run ONLY under the executor unit's flags (sandbox.test.mjs): two held players, one realm each, then 1,000 solves (R25,
// §3.2), in the executor as production holds them. Also times each in-realm solve (R30: p95 <= 2 ms).
import fs from 'node:fs';
import { createExecutor } from './executor.mjs';
import { selfTestInputs, selfTestPassed } from './server.mjs';

const text = fs.readFileSync(process.argv[2], 'utf8');
const executor = createExecutor();
const selfTest = (hash) => {
  const inputs = selfTestInputs();
  return selfTestPassed(inputs, executor.solve(hash, inputs.map(([n]) => n), inputs.map(([, s]) => s), 10_000).raw);
};
const held = ['aaaaaaaa', 'bbbbbbbb'];
for (const hash of held) executor.build(hash, text);
if (!held.every(selfTest)) throw new Error('self-test failed');
const rssMiB = () => Math.round(process.memoryUsage().rss / 1_048_576);
const afterBuild = rssMiB();
const ms = [];
for (let i = 0; i < 1000; i++) {
  const t0 = performance.now();
  executor.solve(held[i % 2], ['ABCDEFGHIJKLMNOP'], ['S'.repeat(108)], 10_000);
  ms.push(performance.now() - t0);
}
ms.sort((a, b) => a - b);
const twoHeldMaxRssMiB = Math.round(process.resourceUsage().maxRSS / 1024);
// R27 rollover, three times: evict the older, then build a new one (as pollOnce does), and solve on it.
for (const hash of ['cccccccc', 'dddddddd', 'eeeeeeee']) {
  executor.drop(held.shift());
  held.push(hash);
  executor.build(hash, text);
  if (!selfTest(hash)) throw new Error('self-test failed after rollover');
  executor.solve(hash, ['ABCDEFGHIJKLMNOP'], ['S'.repeat(108)], 10_000);
}
// resourceUsage().maxRSS is in kilobytes (Node docs); a number in the hundreds of thousands means bytes: report it.
console.log(JSON.stringify({
  twoHeldMaxRssMiB, rolloverMaxRssMiB: Math.round(process.resourceUsage().maxRSS / 1024), afterBuildMiB: afterBuild, steadyMiB: rssMiB(),
  p50Ms: Number(ms[499].toFixed(3)), p95Ms: Number(ms[949].toFixed(3)),
}));
