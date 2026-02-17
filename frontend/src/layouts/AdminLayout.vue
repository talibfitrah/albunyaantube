<template>
  <a class="skip-link" href="#main-content" @click.prevent="handleSkip">
    {{ t('layout.skipToContent') }}
  </a>
  <div class="layout">
    <!-- Mobile/Tablet Header with Hamburger -->
    <header class="mobile-header">
      <button
        type="button"
        class="hamburger-btn"
        :aria-label="isSidebarOpen ? t('layout.closeMenu') : t('layout.openMenu')"
        @click="toggleSidebar"
      >
        <span class="hamburger-icon" :class="{ open: isSidebarOpen }">
          <span></span>
          <span></span>
          <span></span>
        </span>
      </button>
      <div class="mobile-brand">FitrahTube</div>
      <button type="button" class="mobile-logout" @click="handleLogout">
        {{ t('auth.logout') }}
      </button>
    </header>

    <!-- Sidebar Overlay for Mobile/Tablet -->
    <div
      v-if="isSidebarOpen"
      class="sidebar-overlay"
      @click="closeSidebar"
    ></div>

    <!-- Sidebar Navigation -->
    <aside class="sidebar" :class="{ open: isSidebarOpen }" role="complementary" aria-label="Main navigation">
      <div class="sidebar-header">
        <div class="brand">FitrahTube</div>
        <button
          type="button"
          class="close-sidebar"
          @click="closeSidebar"
          :aria-label="t('layout.closeMenu')"
        >×</button>
      </div>

      <nav class="sidebar-nav" role="navigation" aria-label="Primary navigation">
        <RouterLink
          v-for="item in filteredNavRoutes"
          :key="item.labelKey"
          :to="item.route"
          class="nav-item"
          :class="{ active: isActive(item.route) }"
          :aria-current="isActive(item.route) ? 'page' : undefined"
          @click="handleNavClick"
        >
          <span class="nav-icon" aria-hidden="true">{{ getNavIcon(item.labelKey) }}</span>
          <span class="nav-label">{{ t(item.labelKey) }}</span>
        </RouterLink>
      </nav>

      <!-- Locale Switcher in Sidebar (Mobile/Tablet) -->
      <div class="sidebar-footer">
        <label class="locale-switcher-mobile">
          <span class="locale-label">{{ t('preferences.localeLabel') }}</span>
          <select
            class="locale-select"
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
      </div>
    </aside>

    <!-- Main Content Area -->
    <div class="content">
      <!-- Desktop Topbar -->
      <header class="topbar">
        <div class="breadcrumbs">{{ currentSectionLabel }}</div>
        <div class="topbar-actions">
          <NotificationsPanel />
          <label class="locale-switcher">
            <span class="locale-label">{{ t('preferences.localeLabel') }}</span>
            <select
              class="locale-select"
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
import { computed, ref, onMounted, onUnmounted } from 'vue';
import { useI18n } from 'vue-i18n';
import { useRoute, useRouter, type RouteLocationRaw } from 'vue-router';
import { storeToRefs } from 'pinia';
import NotificationsPanel from '@/components/NotificationsPanel.vue';
import { useAuthStore } from '@/stores/auth';
import { navRoutes, type NavRoute } from '@/constants/navigation';
import { usePreferencesStore, type LocaleCode } from '@/stores/preferences';

const { t } = useI18n();
const route = useRoute();
const router = useRouter();
const authStore = useAuthStore();
const preferencesStore = usePreferencesStore();
const { locale } = storeToRefs(preferencesStore);
const mainRef = ref<HTMLElement | null>(null);
const isSidebarOpen = ref(false);
const windowWidth = ref(window.innerWidth);

/** Filter nav routes based on user role */
function canAccess(item: NavRoute): boolean {
  if (!item.requiredRole) return true;
  if (item.requiredRole === 'ADMIN') return authStore.isAdmin;
  return true;
}

const filteredNavRoutes = computed(() =>
  navRoutes.filter(canAccess).map(item => {
    if (item.children) {
      return { ...item, children: item.children.filter(canAccess) };
    }
    return item;
  })
);

const localeOptions = computed(() =>
  preferencesStore.availableLocales.map((code) => ({
    code,
    label: t(`preferences.locales.${code}`)
  }))
);

const currentSectionLabel = computed(() => {
  const active = filteredNavRoutes.value.find((item) => isActive(item.route));
  return active ? t(active.labelKey) : '';
});

function isActive(targetRoute: RouteLocationRaw) {
  return router.resolve(targetRoute).name === route.name;
}

function toggleSidebar() {
  isSidebarOpen.value = !isSidebarOpen.value;
  if (isSidebarOpen.value) {
    document.body.style.overflow = 'hidden';
  } else {
    document.body.style.overflow = '';
  }
}

function closeSidebar() {
  isSidebarOpen.value = false;
  document.body.style.overflow = '';
}

function handleNavClick() {
  // Close sidebar on mobile/tablet after navigation
  if (windowWidth.value < 1024) {
    closeSidebar();
  }
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

function getNavIcon(labelKey: string): string {
  const iconMap: Record<string, string> = {
    'navigation.dashboard': '📊',
    'navigation.contentSearch': '🔍',
    'navigation.categories': '🏷️',
    'navigation.approvals': '✅',
    'navigation.contentLibrary': '📚',
    'navigation.contentSorting': '↕️',
    'navigation.exclusions': '🚫',
    'navigation.bulkImportExport': '📥',
    'navigation.archivedContent': '📦',
    'navigation.users': '👥',
    'navigation.audit': '📋',
    'navigation.activity': '📝',
    'navigation.settings': '⚙️'
  };
  return iconMap[labelKey] || '•';
}

function handleResize() {
  windowWidth.value = window.innerWidth;
  // Auto-close sidebar on resize to desktop
  if (windowWidth.value >= 1024 && isSidebarOpen.value) {
    closeSidebar();
  }
}

onMounted(() => {
  window.addEventListener('resize', handleResize);
});

onUnmounted(() => {
  window.removeEventListener('resize', handleResize);
  document.body.style.overflow = '';
});
</script>

<style scoped>
/* Skip Link */
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

.skip-link:focus-visible {
  top: 1rem;
}

/* Base Layout */
.layout {
  display: grid;
  min-height: 100vh;
  position: relative;
}

/* Mobile Header (< 1024px) */
.mobile-header {
  display: none;
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  height: 56px;
  background: var(--color-surface-inverse);
  color: var(--color-text-inverse);
  padding: 0 1rem;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid rgba(255, 255, 255, 0.1);
  z-index: 100;
}

.hamburger-btn {
  background: transparent;
  border: none;
  padding: 0.5rem;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  -webkit-tap-highlight-color: transparent;
}

.hamburger-icon {
  width: 24px;
  height: 20px;
  position: relative;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
}

.hamburger-icon span {
  display: block;
  height: 3px;
  width: 100%;
  background: var(--color-text-inverse);
  border-radius: 2px;
  transition: all 0.3s ease;
}

.hamburger-icon.open span:nth-child(1) {
  transform: translateY(8.5px) rotate(45deg);
}

.hamburger-icon.open span:nth-child(2) {
  opacity: 0;
}

.hamburger-icon.open span:nth-child(3) {
  transform: translateY(-8.5px) rotate(-45deg);
}

.mobile-brand {
  font-size: 1.125rem;
  font-weight: 700;
  flex: 1;
  text-align: center;
}

.mobile-logout {
  background: var(--color-danger);
  color: var(--color-text-inverse);
  border: none;
  padding: 0.5rem 1rem;
  border-radius: 0.375rem;
  font-size: 0.875rem;
  font-weight: 600;
  cursor: pointer;
  -webkit-tap-highlight-color: transparent;
}

/* Sidebar Overlay */
.sidebar-overlay {
  display: none;
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.5);
  z-index: 150;
  animation: fadeIn 0.2s ease;
}

@keyframes fadeIn {
  from { opacity: 0; }
  to { opacity: 1; }
}

/* Sidebar */
.sidebar {
  background: var(--color-surface-inverse);
  color: var(--color-text-inverse);
  display: flex;
  flex-direction: column;
  border-right: 1px solid rgba(255, 255, 255, 0.08);
}

.sidebar-header {
  display: none;
  padding: 1rem 1.5rem;
  border-bottom: 1px solid rgba(255, 255, 255, 0.1);
  align-items: center;
  justify-content: space-between;
}

.brand {
  font-size: 1.5rem;
  font-weight: 700;
  color: var(--color-text-inverse);
  letter-spacing: -0.02em;
}

.close-sidebar {
  background: transparent;
  border: none;
  color: var(--color-text-inverse);
  font-size: 2rem;
  line-height: 1;
  cursor: pointer;
  padding: 0;
  width: 32px;
  height: 32px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 0.375rem;
  transition: background 0.2s ease;
  -webkit-tap-highlight-color: transparent;
}

.close-sidebar:hover,
.close-sidebar:active {
  background: rgba(255, 255, 255, 0.1);
}

.sidebar-nav {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
  padding: 2rem 1.25rem;
  overflow-y: auto;
}

.nav-item {
  padding: 0.875rem 1.25rem;
  border-radius: 0.5rem;
  color: var(--color-text-inverse-muted);
  transition: all 0.2s ease;
  font-weight: 500;
  font-size: 0.9375rem;
  display: flex;
  align-items: center;
  gap: 0.75rem;
  text-decoration: none;
  -webkit-tap-highlight-color: transparent;
}

.nav-icon {
  font-size: 1.25rem;
  line-height: 1;
  flex-shrink: 0;
}

.nav-label {
  flex: 1;
}

.nav-item:hover,
.nav-item:focus-visible,
.nav-item:active {
  background: rgba(255, 255, 255, 0.08);
  color: var(--color-text-inverse);
}

.nav-item.active {
  background: var(--color-brand);
  color: var(--color-text-inverse);
  font-weight: 600;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.15);
}

.sidebar-footer {
  padding: 1.5rem 1.25rem;
  border-top: 1px solid rgba(255, 255, 255, 0.1);
}

.locale-switcher-mobile {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.locale-switcher-mobile .locale-label {
  font-size: 0.75rem;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.05em;
  color: var(--color-text-inverse-muted);
}

.locale-switcher-mobile .locale-select {
  background: rgba(255, 255, 255, 0.1);
  border: 1px solid rgba(255, 255, 255, 0.2);
  color: var(--color-text-inverse);
  border-radius: 0.5rem;
  padding: 0.75rem 2.5rem 0.75rem 0.75rem;
  font-size: 1rem;
  cursor: pointer;
  -webkit-tap-highlight-color: transparent;
}

[dir="rtl"] .locale-switcher-mobile .locale-select {
  padding: 0.75rem 0.75rem 0.75rem 2.5rem;
}

.locale-switcher-mobile .locale-select option {
  background: var(--color-surface-inverse);
  color: var(--color-text-inverse);
}

/* Content Area */
.content {
  display: flex;
  flex-direction: column;
  background: var(--color-surface);
}

.topbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 1rem 2rem;
  border-bottom: 1px solid var(--color-border);
  background: var(--color-surface);
  min-height: 64px;
}

.breadcrumbs {
  font-weight: 600;
  color: var(--color-text-primary);
  font-size: 1rem;
}

.topbar-actions {
  display: flex;
  align-items: center;
  gap: 1rem;
}

.locale-switcher {
  display: flex;
  align-items: center;
  gap: 0.75rem;
}

.locale-label {
  font-size: 0.875rem;
  font-weight: 500;
  color: var(--color-text-secondary);
  white-space: nowrap;
}

.locale-select {
  border: 1.5px solid var(--color-border);
  background: var(--color-surface);
  color: var(--color-text);
  border-radius: 0.5rem;
  padding: 0.5rem 2.5rem 0.5rem 0.75rem;
  font-size: 0.875rem;
  line-height: 1.25rem;
  min-width: 8rem;
  cursor: pointer;
  transition: all 0.2s ease;
}

[dir="rtl"] .locale-select {
  padding: 0.5rem 0.75rem 0.5rem 2.5rem;
}

.locale-select:hover {
  border-color: var(--color-brand);
}

.logout {
  border: none;
  background: var(--color-danger);
  color: var(--color-text-inverse);
  padding: 0.5rem 1.25rem;
  border-radius: 0.5rem;
  cursor: pointer;
  font-weight: 600;
  font-size: 0.875rem;
  transition: all 0.2s ease;
  white-space: nowrap;
}

.logout:hover {
  background: var(--color-danger-strong);
  box-shadow: 0 2px 8px rgba(220, 38, 38, 0.25);
}

main {
  padding: 2rem;
  flex: 1;
  background: var(--color-bg);
  overflow-y: auto;
  overflow-x: hidden;
}

/* Desktop Layout (>= 1024px) */
@media (min-width: 1024px) {
  .layout {
    grid-template-columns: 260px 1fr;
  }

  .mobile-header {
    display: none !important;
  }

  .sidebar-overlay {
    display: none !important;
  }

  .sidebar {
    position: static;
    transform: none !important;
    padding-top: 2rem;
  }

  .sidebar-header {
    display: none !important;
  }

  .sidebar-footer {
    display: none;
  }

  .brand {
    padding: 0 0.5rem 0 1.25rem;
    margin-bottom: 0.5rem;
  }
}

/* Tablet Layout (768px - 1023px) */
@media (max-width: 1023px) {
  .layout {
    grid-template-columns: 1fr;
    padding-top: 56px;
  }

  .mobile-header {
    display: flex;
  }

  .sidebar {
    position: fixed;
    top: 0;
    left: 0;
    bottom: 0;
    width: 280px;
    max-width: 80vw;
    z-index: 200;
    transform: translateX(-100%);
    transition: transform 0.3s ease;
  }

  .sidebar.open {
    transform: translateX(0);
  }

  .sidebar-overlay {
    display: block;
  }

  .sidebar-header {
    display: flex;
  }

  .sidebar-nav {
    padding: 1rem 1.25rem;
  }

  .topbar {
    display: none;
  }

  main {
    padding: 1.5rem 1rem;
  }
}

/* Mobile Layout (< 768px) */
@media (max-width: 767px) {
  main {
    padding: 1rem;
  }

  .sidebar {
    width: 85vw;
    max-width: 320px;
  }

  .nav-item {
    padding: 1rem 1.25rem;
    font-size: 1rem;
  }

  .nav-icon {
    font-size: 1.5rem;
  }
}

/* Touch Improvements */
@media (hover: none) {
  .nav-item,
  .hamburger-btn,
  .mobile-logout,
  .close-sidebar {
    min-height: 48px;
    min-width: 48px;
  }

  .nav-item:active {
    background: rgba(255, 255, 255, 0.15);
  }

  .logout:active,
  .mobile-logout:active {
    transform: scale(0.98);
  }
}

/* RTL Support */
[dir="rtl"] .sidebar {
  border-right: none;
  border-left: 1px solid rgba(255, 255, 255, 0.08);
}

[dir="rtl"] .skip-link {
  left: auto;
  right: 1rem;
}

/* RTL Desktop Layout */
@media (min-width: 1024px) {
  [dir="rtl"] .layout {
    direction: rtl;
  }

  [dir="rtl"] .brand {
    padding: 0 1.25rem 0 0.5rem;
  }
}

/* RTL Mobile/Tablet Layout */
@media (max-width: 1023px) {
  [dir="rtl"] .sidebar {
    left: auto;
    right: 0;
    transform: translateX(100%);
  }

  [dir="rtl"] .sidebar.open {
    transform: translateX(0);
  }
}
</style>
