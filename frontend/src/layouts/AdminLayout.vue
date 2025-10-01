<template>
  <a class="skip-link" href="#main-content" @click.prevent="handleSkip">
    {{ t('layout.skipToContent') }}
  </a>
  <div class="layout">
    <aside class="sidebar">
      <div class="brand">Albunyaan Tube</div>
      <nav>
        <RouterLink
          v-for="item in navRoutes"
          :key="item.labelKey"
          :to="item.route"
          class="nav-item"
          :class="{ active: isActive(item.route) }"
        >
          <span>{{ t(item.labelKey) }}</span>
        </RouterLink>
      </nav>
    </aside>
    <div class="content">
      <header class="topbar">
        <div class="breadcrumbs">{{ currentSectionLabel }}</div>
        <div class="topbar-actions">
          <label class="locale-switcher">
            <span class="locale-label">{{ t('preferences.localeLabel') }}</span>
            <select
              class="locale-select"
              :aria-label="t('preferences.localeLabel')"
              :value="locale"
              @change="onLocaleChange"
            >
              <option
                v-for="option in localeOptions"
                :key="option.code"
                :value="option.code"
              >
                {{ option.label }}
              </option>
            </select>
          </label>
          <button class="logout" type="button" @click="handleLogout">
            {{ t('auth.logout') }}
          </button>
        </div>
      </header>
      <main id="main-content" ref="mainRef" tabindex="-1">
        <RouterView />
      </main>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { useRoute, useRouter, type RouteLocationRaw } from 'vue-router';
import { storeToRefs } from 'pinia';
import { useAuthStore } from '@/stores/auth';
import { navRoutes } from '@/constants/navigation';
import { usePreferencesStore, type LocaleCode } from '@/stores/preferences';

const { t } = useI18n();
const route = useRoute();
const router = useRouter();
const authStore = useAuthStore();
const preferencesStore = usePreferencesStore();
const { locale } = storeToRefs(preferencesStore);
const mainRef = ref<HTMLElement | null>(null);

const localeOptions = computed(() =>
  preferencesStore.availableLocales.map((code) => ({
    code,
    label: t(`preferences.locales.${code}`)
  }))
);

const currentSectionLabel = computed(() => {
  const active = navRoutes.find((item) => isActive(item.route));
  return active ? t(active.labelKey) : '';
});

function isActive(targetRoute: RouteLocationRaw) {
  return router.resolve(targetRoute).name === route.name;
}

async function handleLogout() {
  await authStore.logout();
  router.replace({ name: 'login' });
}

function onLocaleChange(event: Event) {
  const target = event.target as HTMLSelectElement;
  preferencesStore.setLocale(target.value as LocaleCode);
}

function handleSkip() {
  if (mainRef.value) {
    mainRef.value.focus();
  }
}
</script>

<style scoped>
.skip-link {
  position: absolute;
  left: 1rem;
  top: -3rem;
  background: var(--color-brand);
  color: var(--color-text-inverse);
  padding: 0.5rem 1rem;
  border-radius: 0.5rem;
  z-index: 1000;
  transition: top 0.2s ease;
}

.skip-link:focus {
  top: 1rem;
}

.layout {
  display: grid;
  grid-template-columns: 240px 1fr;
  min-height: 100vh;
}

.sidebar {
  background: var(--color-surface-inverse);
  color: var(--color-text-inverse);
  padding: 1.5rem 1rem;
  display: flex;
  flex-direction: column;
  gap: 2rem;
}

.brand {
  font-size: 1.25rem;
  font-weight: 600;
  color: var(--color-text-inverse);
}

nav {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.nav-item {
  padding: 0.75rem 1rem;
  border-radius: 0.5rem;
  color: var(--color-text-inverse-muted);
  transition: background 0.2s ease, color 0.2s ease;
}

.nav-item:hover,
.nav-item.active {
  background: var(--color-brand-soft);
  color: var(--color-text-inverse);
}

.content {
  display: flex;
  flex-direction: column;
  background: var(--color-surface);
}

.topbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 1.25rem 1.5rem;
  border-bottom: 1px solid var(--color-border);
}

.topbar-actions {
  display: flex;
  align-items: center;
  gap: 0.75rem;
}

.locale-switcher {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}

.locale-label {
  font-size: 0.75rem;
  font-weight: 500;
  color: var(--color-text-muted);
}

.locale-select {
  border: 1px solid var(--color-border);
  background: var(--color-surface); 
  color: var(--color-text);
  border-radius: 0.5rem;
  padding: 0.4rem 2rem 0.4rem 0.75rem;
  font-size: 0.875rem;
  line-height: 1.25rem;
  min-width: 8rem;
}

.locale-select:focus {
  outline: 2px solid var(--color-brand);
  outline-offset: 2px;
}

.logout {
  border: none;
  background: var(--color-danger);
  color: var(--color-text-inverse);
  padding: 0.5rem 1rem;
  border-radius: 0.5rem;
  cursor: pointer;
  transition: background 0.2s ease;
}

.logout:hover {
  background: var(--color-danger-strong);
}

main {
  padding: 1.5rem;
  flex: 1;
  background: var(--color-bg);
}
</style>
