# Agents.md

## Purpose  
This document defines the operational rules for all developer agents working in this repository. It ensures consistent execution of commands, prevents hanging processes, and enforces safe defaults.  

---

## Test Execution Policy  

### Command  
All tests related to the following command:  

```bash
npm test
```

Which expands to:  

```bash
vitest run
```

---

### Timeout Rule  
- **Maximum runtime:** 300 seconds (5 minutes).  
- If the command exceeds 300 seconds, it **must be terminated**.  
- Agents must ensure the timeout is enforced in all contexts (local, CI, scripted).  

---

### Implementation Guidance  

1. **Local Shell Execution**  
   Wrap the command with a timeout:  
   ```bash
   timeout 300s npm test
   ```

2. **Package.json Scripts**  
   Update `scripts` to enforce timeout at the command level:  
   ```json
   "scripts": {
     "dev": "vite",
     "build": "vue-tsc --noEmit && vite build",
     "preview": "vite preview",
     "test": "timeout 300s vitest run"
   }
   ```

3. **Vitest Config (per-test safety)**  
   Additionally configure Vitest to apply per-test timeouts:  
   ```ts
   // vitest.config.ts
   import { defineConfig } from 'vitest/config'

   export default defineConfig({
     test: {
       testTimeout: 300000 // 300s = 5 minutes
     },
   })
   ```

---

### Expected Agent Behavior  
- Always enforce the 300s timeout when running or generating code for `npm test`.  
- Do not generate commands or scripts that allow tests to run without a timeout.  
- If a test exceeds the timeout, log a clear error and stop execution.  
