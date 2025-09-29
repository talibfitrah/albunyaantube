<template>
  <section class="registry-workspace">
    <header class="workspace-header">
      <div>
        <h1>{{ t('registry.heading') }}</h1>
        <p>{{ t('registry.description') }}</p>
      </div>
    </header>

    <nav class="tabs" role="tablist" aria-label="Registry sections">
      <button
        v-for="tab in tabItems"
        :key="tab.key"
        class="tab"
        type="button"
        role="tab"
        :id="`registry-tab-${tab.key}`"
        :aria-controls="`registry-panel-${tab.key}`"
        :aria-selected="tab.key === activeTab"
        :tabindex="tab.key === activeTab ? 0 : -1"
        @click="setActiveTab(tab.key)"
      >
        {{ tab.label }}
      </button>
    </nav>

    <div class="tab-panel" :id="`registry-panel-${activeTab}`" role="tabpanel" :aria-labelledby="`registry-tab-${activeTab}`">
      <component :is="activeComponent" />
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import ChannelsTable from '@/components/registry/ChannelsTable.vue';
import PlaylistsTable from '@/components/registry/PlaylistsTable.vue';
import VideosTable from '@/components/registry/VideosTable.vue';

type TabKey = 'channels' | 'playlists' | 'videos';

const tabComponents = {
  channels: ChannelsTable,
  playlists: PlaylistsTable,
  videos: VideosTable
} as const;

const { t } = useI18n();

const activeTab = ref<TabKey>('channels');

const tabItems = computed(() => [
  { key: 'channels' as TabKey, label: t('registry.tabs.channels') },
  { key: 'playlists' as TabKey, label: t('registry.tabs.playlists') },
  { key: 'videos' as TabKey, label: t('registry.tabs.videos') }
]);

const activeComponent = computed(() => tabComponents[activeTab.value]);

function setActiveTab(tab: TabKey) {
  activeTab.value = tab;
}
</script>

<style scoped>
.registry-workspace {
  display: flex;
  flex-direction: column;
  gap: 1.5rem;
}

.workspace-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  background: white;
  border-radius: 1rem;
  padding: 1.75rem 2rem;
  box-shadow: 0 20px 45px -30px rgba(15, 23, 42, 0.35);
}

.workspace-header h1 {
  margin: 0;
  font-size: 1.5rem;
  font-weight: 700;
  color: #0f172a;
}

.workspace-header p {
  margin: 0.5rem 0 0;
  color: #475569;
  font-size: 1rem;
}

.tabs {
  display: inline-flex;
  flex-wrap: wrap;
  gap: 0.5rem;
  background: white;
  padding: 0.5rem;
  border-radius: 999px;
  box-shadow: 0 12px 30px -22px rgba(15, 23, 42, 0.45);
  width: fit-content;
}

.tab {
  border: none;
  padding: 0.5rem 1.25rem;
  border-radius: 999px;
  background: transparent;
  font-weight: 600;
  color: #475569;
  cursor: pointer;
  transition: background 0.2s ease, color 0.2s ease;
}

.tab[aria-selected='true'] {
  background: #0f172a;
  color: white;
  box-shadow: 0 10px 20px -15px rgba(15, 23, 42, 0.6);
}

.tab:hover {
  background: rgba(15, 23, 42, 0.08);
}

.tab-panel {
  display: flex;
}

.tab-panel :deep(.registry-card) {
  width: 100%;
}
</style>
