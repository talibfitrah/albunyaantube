import '@testing-library/jest-dom';
import { render, screen, waitFor, fireEvent } from '@testing-library/vue';
import { createPinia } from 'pinia';
import { createI18n } from 'vue-i18n';
import { createRouter, createMemoryHistory } from 'vue-router';
import { describe, it, expect, beforeEach } from 'vitest';
import AdminLayout from '@/layouts/AdminLayout.vue';
import { messages } from '@/locales/messages';
import { usePreferencesStore } from '@/stores/preferences';
import { watch } from 'vue';

const STORAGE_KEY = 'albunyaan.admin.locale';

function buildRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'dashboard', component: { template: '<div />' } },
      { path: '/content-search', name: 'content-search', component: { template: '<div />' } },
      { path: '/categories', name: 'categories', component: { template: '<div />' } },
      { path: '/approvals', name: 'approvals', component: { template: '<div />' } },
      { path: '/content-library', name: 'content-library', component: { template: '<div />' } },
      { path: '/exclusions', name: 'exclusions', component: { template: '<div />' } },
      { path: '/bulk-import-export', name: 'bulk-import-export', component: { template: '<div />' } },
      { path: '/video-validation', name: 'video-validation', component: { template: '<div />' } },
      { path: '/users', name: 'users', component: { template: '<div />' } },
      { path: '/audit', name: 'audit', component: { template: '<div />' } },
      { path: '/activity', name: 'activity', component: { template: '<div />' } },
      { path: '/settings/profile', name: 'settings-profile', component: { template: '<div />' } },
      { path: '/settings/notifications', name: 'settings-notifications', component: { template: '<div />' } },
      { path: '/settings/system', name: 'settings-system', component: { template: '<div />' } },
      { path: '/login', name: 'login', component: { template: '<div />' } }
    ]
  });
}

describe('AdminLayout locale switcher', () => {
  beforeEach(() => {
    window.localStorage.clear();
  });

  it('renders available locale options with translated labels', async () => {
    const pinia = createPinia();
    const i18n = createI18n({ legacy: false, locale: 'en', messages });
    const router = buildRouter();
    router.push({ name: 'dashboard' });
    await router.isReady();

    const preferencesStore = usePreferencesStore(pinia);
    preferencesStore.initialize();
    watch(
      () => preferencesStore.locale,
      (value) => {
        i18n.global.locale.value = value;
      },
      { immediate: true }
    );

    render(AdminLayout, {
      global: {
        plugins: [pinia, i18n, router]
      }
    });

    // Use getAllByLabelText since there are two locale switchers (mobile + desktop)
    const selects = await screen.findAllByLabelText(/interface language/i);
    // Check the first one (both should be identical)
    const select = selects[0];
    const options = Array.from(select.querySelectorAll('option')).map((option) => option.textContent);

    expect(select).toHaveValue('en');
    expect(options).toEqual(['English', 'العربية', 'Nederlands']);
  });

  it('persists the selected locale and updates i18n', async () => {
    const pinia = createPinia();
    const i18n = createI18n({ legacy: false, locale: 'en', messages });
    const router = buildRouter();
    router.push({ name: 'dashboard' });
    await router.isReady();

    const preferencesStore = usePreferencesStore(pinia);
    preferencesStore.initialize();
    watch(
      () => preferencesStore.locale,
      (value) => {
        i18n.global.locale.value = value;
      },
      { immediate: true }
    );

    render(AdminLayout, {
      global: {
        plugins: [pinia, i18n, router]
      }
    });

    // Use getAllByLabelText since there are two locale switchers (mobile + desktop)
    const selects = await screen.findAllByLabelText(/interface language/i);
    const select = selects[0];
    await fireEvent.update(select, 'ar');

    await waitFor(() => {
      expect(preferencesStore.locale).toBe('ar');
      expect(i18n.global.locale.value).toBe('ar');
      expect(window.localStorage.getItem(STORAGE_KEY)).toBe('ar');
    });
  });

  it('moves focus to main content when using skip link', async () => {
    const pinia = createPinia();
    const i18n = createI18n({ legacy: false, locale: 'en', messages });
    const router = buildRouter();
    router.push({ name: 'dashboard' });
    await router.isReady();

    const preferencesStore = usePreferencesStore(pinia);
    preferencesStore.initialize();
    watch(
      () => preferencesStore.locale,
      (value) => {
        i18n.global.locale.value = value;
      },
      { immediate: true }
    );

    render(AdminLayout, {
      global: {
        plugins: [pinia, i18n, router]
      }
    });

    const skipLink = screen.getByRole('link', { name: /skip to main content/i });
    const main = screen.getByRole('main');

    await fireEvent.click(skipLink);

    await waitFor(() => {
      expect(main).toHaveFocus();
    });
  });
});
