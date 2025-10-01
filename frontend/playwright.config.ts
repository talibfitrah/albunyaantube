import { defineConfig } from '@playwright/test';
import { fileURLToPath } from 'url';
import path from 'path';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

export default defineConfig({
  testDir: './tests/e2e',
  timeout: 35_000,
  expect: {
    timeout: 5_000
  },
  use: {
    baseURL: process.env.PLAYWRIGHT_BASE_URL ?? 'http://127.0.0.1:4173',
    trace: 'on-first-retry',
    video: 'retain-on-failure',
    screenshot: 'only-on-failure'
  },
  webServer: {
    command: 'npm run preview -- --port=4173',
    cwd: __dirname,
    port: 4173,
    reuseExistingServer: !process.env.CI,
    timeout: 120_000
  }
});
