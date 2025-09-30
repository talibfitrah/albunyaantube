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
      { path: '/registry', name: 'registry', component: { template: '<div />' } },
      { path: '/moderation', name: 'moderation', component: { template: '<div />' } },
      { path: '/users', name: 'users', component: { template: '<div />' } },
      { path: '/audit', name: 'audit', component: { template: '<div />' } }
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

    const select = await screen.findByLabelText(/interface language/i);
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

    const select = await screen.findByLabelText(/interface language/i);
    await fireEvent.update(select, 'ar');

    await waitFor(() => {
      expect(preferencesStore.locale).toBe('ar');
      expect(i18n.global.locale.value).toBe('ar');
      expect(window.localStorage.getItem(STORAGE_KEY)).toBe('ar');
    });
  });
});
