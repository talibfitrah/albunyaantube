import { computed, ref, watch } from 'vue';
import { defineStore } from 'pinia';

const STORAGE_KEY = 'albunyaan.admin.locale';
const THEME_STORAGE_KEY = 'albunyaan.admin.theme';
const SUPPORTED_LOCALES = ['en', 'ar', 'nl'] as const;
const SUPPORTED_THEMES = ['light', 'dark', 'system'] as const;

export type ThemeMode = (typeof SUPPORTED_THEMES)[number];

export type LocaleCode = (typeof SUPPORTED_LOCALES)[number];

function getStorage(): Storage | undefined {
  if (typeof window === 'undefined') {
    return undefined;
  }

  try {
    return window.localStorage;
  } catch (error) {
    console.warn('localStorage unavailable; locale preferences will not persist.', error);
    return undefined;
  }
}

function normalizeLocale(candidate: string | null | undefined): LocaleCode | null {
  if (!candidate) {
    return null;
  }

  const normalized = candidate.toLowerCase().split('-')[0];
  return SUPPORTED_LOCALES.find((locale) => locale === normalized) ?? null;
}

function detectNavigatorLocale(): LocaleCode {
  if (typeof navigator === 'undefined') {
    return 'en';
  }

  const candidates = Array.isArray(navigator.languages) && navigator.languages.length > 0
    ? navigator.languages
    : [navigator.language];

  for (const candidate of candidates) {
    const normalized = normalizeLocale(candidate);
    if (normalized) {
      return normalized;
    }
  }

  return 'en';
}

function readPersistedLocale(storage: Storage | undefined): LocaleCode | null {
  if (!storage) {
    return null;
  }

  const stored = storage.getItem(STORAGE_KEY);
  return normalizeLocale(stored);
}

function readPersistedTheme(storage: Storage | undefined): ThemeMode | null {
  if (!storage) return null;
  const stored = storage.getItem(THEME_STORAGE_KEY);
  if (stored && SUPPORTED_THEMES.includes(stored as ThemeMode)) {
    return stored as ThemeMode;
  }
  return null;
}

const systemDarkQuery = typeof window !== 'undefined' && typeof window.matchMedia === 'function'
  ? window.matchMedia('(prefers-color-scheme: dark)')
  : null;

function resolveTheme(theme: ThemeMode): 'dark' | 'light' {
  if (theme === 'system') {
    return systemDarkQuery?.matches ? 'dark' : 'light';
  }
  return theme;
}

function applyTheme(theme: ThemeMode) {
  if (typeof document === 'undefined') return;
  document.documentElement.setAttribute('data-theme', resolveTheme(theme));
}

export const usePreferencesStore = defineStore('preferences', () => {
  const storage = getStorage();
  const locale = ref<LocaleCode>('en');
  const theme = ref<ThemeMode>('system');

  function initialize(): LocaleCode {
    const persistedLocale = readPersistedLocale(storage);
    if (persistedLocale) {
      locale.value = persistedLocale;
    } else {
      locale.value = detectNavigatorLocale();
    }

    const persistedTheme = readPersistedTheme(storage);
    if (persistedTheme) {
      theme.value = persistedTheme;
    }
    applyTheme(theme.value);

    // Re-apply when OS dark/light preference changes (only matters for 'system' theme)
    systemDarkQuery?.addEventListener?.('change', () => {
      if (theme.value === 'system') applyTheme('system');
    });

    return locale.value;
  }

  function setLocale(next: LocaleCode) {
    locale.value = next;
  }

  function setTheme(next: ThemeMode) {
    theme.value = next;
  }

  watch(
    locale,
    (value) => {
      if (!storage) return;
      storage.setItem(STORAGE_KEY, value);
    },
    { flush: 'post' }
  );

  watch(
    theme,
    (value) => {
      applyTheme(value);
      if (!storage) return;
      storage.setItem(THEME_STORAGE_KEY, value);
    },
    { flush: 'post' }
  );

  const availableLocales = computed<LocaleCode[]>(() => [...SUPPORTED_LOCALES]);
  const availableThemes = computed<ThemeMode[]>(() => [...SUPPORTED_THEMES]);

  return {
    availableLocales,
    availableThemes,
    initialize,
    locale,
    setLocale,
    theme,
    setTheme
  };
});
