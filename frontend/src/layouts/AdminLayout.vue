<template>
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
        <button class="logout" type="button" @click="handleLogout">
          {{ t('auth.logout') }}
        </button>
      </header>
      <main>
        <RouterView />
      </main>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { useI18n } from 'vue-i18n';
import { useRoute, useRouter, type RouteLocationRaw } from 'vue-router';
import { useAuthStore } from '@/stores/auth';
import { navRoutes } from '@/constants/navigation';

const { t } = useI18n();
const route = useRoute();
const router = useRouter();
const authStore = useAuthStore();

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
</script>

<style scoped>
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
