import { computed, ref, watch } from 'vue';
import { defineStore } from 'pinia';

const STORAGE_KEY = 'albunyaan.admin.locale';
const SUPPORTED_LOCALES = ['en', 'ar', 'nl'] as const;

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

export const usePreferencesStore = defineStore('preferences', () => {
  const storage = getStorage();
  const locale = ref<LocaleCode>('en');

  function initialize(): LocaleCode {
    const persisted = readPersistedLocale(storage);
    if (persisted) {
      locale.value = persisted;
      return locale.value;
    }

    const detected = detectNavigatorLocale();
    locale.value = detected;
    return locale.value;
  }

  function setLocale(next: LocaleCode) {
    locale.value = next;
  }

  watch(
    locale,
    (value) => {
      if (!storage) {
        return;
      }
      storage.setItem(STORAGE_KEY, value);
    },
    { flush: 'post' }
  );

  const availableLocales = computed<LocaleCode[]>(() => [...SUPPORTED_LOCALES]);

  return {
    availableLocales,
    initialize,
    locale,
    setLocale
  };
});
