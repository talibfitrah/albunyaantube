import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, it, expect } from 'vitest';

const cssPath = resolve(__dirname, '../src/assets/main.css');
const moderationViewPath = resolve(__dirname, '../src/views/ModerationQueueView.vue');
const dashboardViewPath = resolve(__dirname, '../src/views/DashboardView.vue');

describe('theme tokens', () => {
  const css = readFileSync(cssPath, 'utf8');

  it('defines core light and dark tokens', () => {
    const requiredTokens = [
      '--color-bg',
      '--color-surface',
      '--color-text-primary',
      '--color-brand',
      '--color-accent',
      '--color-border',
      '--color-success',
      '--color-warning',
      '--color-danger',
      '--color-disabled',
      '--gradient-auth-a'
    ];
    requiredTokens.forEach(token => {
      expect(css).toContain(token);
    });
    expect(css).toContain('@media (prefers-color-scheme: dark)');
  });

  it('maps views to the tokenized palette', () => {
    const moderationView = readFileSync(moderationViewPath, 'utf8');
    const dashboardView = readFileSync(dashboardViewPath, 'utf8');
    [moderationView, dashboardView].forEach(content => {
      expect(content).toMatch(/var\(--color-/);
    });
  });
});
