import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { nextTick } from 'vue';
import { usePreferencesStore } from '@/stores/preferences';

const STORAGE_KEY = 'albunyaan.admin.locale';

describe('preferences store', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    window.localStorage.clear();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('initializes from persisted storage when present', () => {
    window.localStorage.setItem(STORAGE_KEY, 'nl');

    const store = usePreferencesStore();
    const locale = store.initialize();

    expect(locale).toBe('nl');
    expect(store.locale).toBe('nl');
  });

  it('falls back to navigator languages when storage is empty', () => {
    vi.spyOn(window.navigator, 'languages', 'get').mockReturnValue(['ar-EG']);
    vi.spyOn(window.navigator, 'language', 'get').mockReturnValue('en-US');

    const store = usePreferencesStore();
    const locale = store.initialize();

    expect(locale).toBe('ar');
    expect(store.locale).toBe('ar');
  });

  it('persists locale updates to storage', async () => {
    const store = usePreferencesStore();
    store.initialize();

    store.setLocale('ar');
    await nextTick();

    expect(window.localStorage.getItem(STORAGE_KEY)).toBe('ar');
  });
});
