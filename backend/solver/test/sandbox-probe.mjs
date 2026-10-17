// Run ONLY under the unit's flags (sandbox.test.mjs): what the permission model lets this process do (R40).
import child from 'node:child_process';
import fs from 'node:fs';

const [outsideFile, appFile, cacheFile] = process.argv.slice(2);
const attempt = (fn) => { try { fn(); return 'allowed'; } catch (error) { return error.code ?? error.message; } };
console.log(JSON.stringify({
  readOutside: attempt(() => fs.readFileSync(outsideFile)),
  writeApp: attempt(() => fs.writeFileSync(appFile, 'x')),
  writeCache: attempt(() => fs.writeFileSync(cacheFile, 'x')),
  spawn: attempt(() => child.execSync('true')),
}));
