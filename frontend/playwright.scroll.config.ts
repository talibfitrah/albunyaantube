import { defineConfig } from '@playwright/test';
import { fileURLToPath } from 'url';
import path from 'path';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

/**
 * Separate from playwright.config.ts because this suite needs the dev server, not the preview
 * build: it loads tests/e2e-browser/harness.html, which Vite serves in dev and deliberately keeps
 * out of the production bundle (the build's only entry is index.html).
 */
export default defineConfig({
  testDir: './tests/e2e-browser',
  timeout: 60_000,
  expect: { timeout: 10_000 },
  use: {
    baseURL: 'http://127.0.0.1:5175',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure'
  },
  webServer: {
    // npx vite, not npm run dev: the predev hook regenerates src/generated/api/schema.ts.
    command: 'npx vite --port 5175 --strictPort',
    cwd: __dirname,
    port: 5175,
    reuseExistingServer: !process.env.CI,
    timeout: 120_000
  }
});
