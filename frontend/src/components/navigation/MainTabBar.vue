<template>
  <nav class="main-tab-bar" aria-label="Primary navigation">
    <button
      v-for="tab in MAIN_TABS"
      :key="tab.key"
      type="button"
      class="tab-button"
      :class="{ active: tab.key === activeKey }"
      :aria-pressed="tab.key === activeKey"
      @click="handleSelect(tab)"
    >
      <span class="icon" aria-hidden="true">
        <svg
          class="icon-svg"
          viewBox="0 0 24 24"
          fill="currentColor"
          role="img"
          :data-icon="tab.icon"
        >
          <path :d="TAB_ICON_PATHS[tab.icon]" fill-rule="evenodd" clip-rule="evenodd" />
        </svg>
      </span>
      <span class="label">{{ t(tab.labelKey) }}</span>
    </button>
  </nav>
</template>

<script setup lang="ts">
import { getCurrentInstance } from 'vue';
import { useI18n } from 'vue-i18n';
import { useRouter, type Router } from 'vue-router';
import { MAIN_TABS, TAB_ICON_PATHS, type MainTabKey, type TabDefinition } from '@/constants/tabs';

const props = defineProps<{ activeKey: MainTabKey }>();
const emit = defineEmits<{
  (e: 'update:activeKey', key: MainTabKey): void;
  (e: 'select', key: MainTabKey): void;
}>();

const { t } = useI18n();
const instance = getCurrentInstance();
let router: Router | null = null;

if (instance?.appContext.config.globalProperties.$router) {
  router = useRouter();
}

function handleSelect(tab: TabDefinition) {
  if (tab.key !== props.activeKey) {
    emit('update:activeKey', tab.key);
  }

  emit('select', tab.key);

  if (router && tab.routeName && router.hasRoute(tab.routeName)) {
    router.push({ name: tab.routeName });
  }
}
</script>

<style scoped>
.main-tab-bar {
  display: grid;
  grid-auto-flow: column;
  gap: 0.5rem;
  padding: 0.75rem 0.5rem;
  background: var(--color-surface);
  border-top: 1px solid var(--color-border);
  box-shadow: 0 -6px 18px -16px rgba(0, 0, 0, 0.25);
}

.tab-button {
  appearance: none;
  background: transparent;
  border: none;
  border-radius: 0.75rem;
  color: var(--color-icon-inactive);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 0.35rem;
  padding: 0.5rem 0.35rem;
  font-size: 0.75rem;
  font-weight: 600;
  letter-spacing: 0.02em;
  cursor: pointer;
  transition: color 0.2s ease, background 0.2s ease;
}

.tab-button:hover,
.tab-button:focus-visible {
  color: var(--color-icon-active);
  background: var(--color-surface-alt);
  box-shadow: var(--shadow-focus);
}

.tab-button.active {
  color: var(--color-icon-active);
  background: var(--color-brand-soft);
}

.tab-button.active .label {
  color: var(--color-text-primary);
}

.icon {
  display: inline-flex;
}

.icon-svg {
  width: 24px;
  height: 24px;
}

.label {
  color: var(--color-text-secondary);
  text-transform: uppercase;
}
</style>
