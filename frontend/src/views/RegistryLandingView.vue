<template>
  <section class="registry-workspace">
    <header class="workspace-header">
      <div>
        <h1>{{ t('registry.heading') }}</h1>
        <p>{{ t('registry.description') }}</p>
      </div>
      <div class="workspace-controls">
        <RegistryFilters />
      </div>
    </header>

    <div v-if="error" class="error-banner" role="alert">{{ error }}</div>

    <div v-if="isLoading" class="skeleton-grid" aria-hidden="true">
      <div v-for="index in 6" :key="index" class="skeleton-card"></div>
    </div>

    <div v-else class="results-grid">
      <section class="results-section">
        <header class="section-header">
          <h2>{{ t('registry.sections.channels') }}</h2>
          <span class="meta" v-if="channelResults.length">
            {{ formatNumber(channelResults.length, currentLocale) }}
          </span>
        </header>
        <p class="section-hint">{{ t('registry.channels.description') }}</p>
        <div v-if="!channelResults.length" class="empty-state">
          {{ t('registry.state.emptyChannels') }}
        </div>
        <ul v-else class="card-list">
          <li v-for="channel in channelResults" :key="channel.id" class="card">
            <article>
              <header class="card-header">
                <div class="card-title">{{ channel.ytId }}</div>
                <div class="card-subtitle">{{ formatCategoryList(channel.categories) }}</div>
              </header>
              <dl class="card-meta">
                <div>
                  <dt>{{ t('registry.playlists.columns.items') }}</dt>
                  <dd>{{ formatNumber(channel.excludedItemCounts.playlists, currentLocale) }}</dd>
                </div>
                <div>
                  <dt>{{ t('registry.videos.columns.views') }}</dt>
                  <dd>{{ formatNumber(channel.excludedItemCounts.videos, currentLocale) }}</dd>
                </div>
              </dl>
            </article>
          </li>
        </ul>
      </section>

      <section class="results-section">
        <header class="section-header">
          <h2>{{ t('registry.sections.playlists') }}</h2>
          <span class="meta" v-if="playlistResults.length">
            {{ formatNumber(playlistResults.length, currentLocale) }}
          </span>
        </header>
        <p class="section-hint">{{ t('registry.playlists.description') }}</p>
        <div v-if="!playlistResults.length" class="empty-state">
          {{ t('registry.state.emptyPlaylists') }}
        </div>
        <ul v-else class="card-list">
          <li v-for="playlist in playlistResults" :key="playlist.id" class="card">
            <article>
              <header class="card-header">
                <div class="card-title">{{ playlist.ytId }}</div>
                <div class="card-subtitle">{{ formatCategoryList(playlist.categories) }}</div>
              </header>
              <dl class="card-meta">
                <div>
                  <dt>{{ t('registry.playlists.columns.owner') }}</dt>
                  <dd>{{ playlist.owner.ytId }}</dd>
                </div>
                <div>
                  <dt>{{ t('registry.playlists.columns.items') }}</dt>
                  <dd>{{ formatNumber(playlist.excludedVideoCount, currentLocale) }}</dd>
                </div>
              </dl>
              <footer class="card-actions">
                <button
                  type="button"
                  class="chip"
                  :class="{ active: playlist.includeState === 'EXCLUDED' }"
                  :disabled="isPending(`playlist-${playlist.id}`)"
                  @click="togglePlaylistInclusion(playlist)"
                >
                  <span v-if="isPending(`playlist-${playlist.id}`)">
                    {{ playlist.includeState === 'EXCLUDED' ? t('registry.actions.including') : t('registry.actions.excluding') }}
                  </span>
                  <span v-else>
                    {{ playlist.includeState === 'EXCLUDED' ? t('registry.actions.include') : t('registry.actions.exclude') }}
                  </span>
                </button>
              </footer>
            </article>
          </li>
        </ul>
      </section>

      <section class="results-section">
        <header class="section-header">
          <h2>{{ t('registry.sections.videos') }}</h2>
          <span class="meta" v-if="videoResults.length">
            {{ formatNumber(videoResults.length, currentLocale) }}
          </span>
        </header>
        <p class="section-hint">{{ t('registry.videos.description') }}</p>
        <div v-if="!videoResults.length" class="empty-state">
          {{ t('registry.state.emptyVideos') }}
        </div>
        <ul v-else class="card-list">
          <li v-for="video in videoResults" :key="video.id" class="card">
            <article>
              <header class="card-header">
                <div class="card-title">{{ video.ytId }}</div>
                <div class="card-subtitle">{{ formatCategoryList(video.categories) }}</div>
              </header>
              <dl class="card-meta">
                <div>
                  <dt>{{ t('registry.videos.columns.channel') }}</dt>
                  <dd>{{ video.channel.ytId }}</dd>
                </div>
                <div>
                  <dt>{{ t('registry.videos.columns.duration') }}</dt>
                  <dd>{{ video.durationSeconds }}s</dd>
                </div>
              </dl>
              <footer class="card-actions">
                <button
                  type="button"
                  class="chip"
                  :class="{ active: video.includeState === 'EXCLUDED' }"
                  :disabled="isPending(`video-${video.id}`)"
                  @click="toggleVideoInclusion(video)"
                >
                  <span v-if="isPending(`video-${video.id}`)">
                    {{ video.includeState === 'EXCLUDED' ? t('registry.actions.including') : t('registry.actions.excluding') }}
                  </span>
                  <span v-else>
                    {{ video.includeState === 'EXCLUDED' ? t('registry.actions.include') : t('registry.actions.exclude') }}
                  </span>
                </button>
              </footer>
            </article>
          </li>
        </ul>
      </section>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { storeToRefs } from 'pinia';
import RegistryFilters from '@/components/registry/RegistryFilters.vue';
import {
  searchRegistry,
  updateChannelExclusions,
  updatePlaylistExclusions
} from '@/services/registry';
import type {
  AdminSearchPlaylistResult,
  AdminSearchResponse,
  AdminSearchVideoResult,
  CategoryTag
} from '@/types/registry';
import { formatNumber } from '@/utils/formatters';
import { useRegistryFiltersStore } from '@/stores/registryFilters';

interface ChannelState {
  ytId: string;
  excludedPlaylistIds: Set<string>;
  excludedVideoIds: Set<string>;
}

interface PlaylistState {
  ytId: string;
  excludedVideoIds: Set<string>;
}

const { t, locale } = useI18n();

const filtersStore = useRegistryFiltersStore();
const { query, categoryId, searchParams } = storeToRefs(filtersStore);
const results = ref<AdminSearchResponse | null>(null);
const isLoading = ref(false);
const error = ref<string | null>(null);

const channelStates = reactive<Record<string, ChannelState>>({});
const playlistStates = reactive<Record<string, PlaylistState>>({});
const pendingKeys = reactive(new Set<string>());

const currentLocale = computed(() => locale.value);

const channelResults = computed(() => results.value?.channels ?? []);
const playlistResults = computed(() => results.value?.playlists ?? []);
const videoResults = computed(() => results.value?.videos ?? []);

let searchDebounce: number | undefined;

watch(query, () => {
  if (searchDebounce) {
    window.clearTimeout(searchDebounce);
  }
  searchDebounce = window.setTimeout(() => {
    loadResults();
  }, 350);
});

watch(categoryId, () => {
  loadResults();
});

onMounted(() => {
  filtersStore.fetchCategories();
  loadResults();
});

async function loadResults() {
  if (isLoading.value) {
    return;
  }
  isLoading.value = true;
  error.value = null;
  try {
    const params = searchParams.value;
    const response = await searchRegistry({
      q: params.q ?? undefined,
      categoryId: params.categoryId ?? undefined,
      limit: 30
    });
    results.value = response;
    synchroniseStates(response);
  } catch (err) {
    console.error('Failed to load registry search', err);
    error.value = err instanceof Error ? err.message : String(err);
  } finally {
    isLoading.value = false;
  }
}

function synchroniseStates(response: AdminSearchResponse) {
  Object.keys(channelStates).forEach(key => delete channelStates[key]);
  response.channels.forEach(channel => {
    channelStates[channel.id] = {
      ytId: channel.ytId,
      excludedPlaylistIds: new Set(channel.excludedPlaylistIds ?? []),
      excludedVideoIds: new Set(channel.excludedVideoIds ?? [])
    };
  });
  Object.keys(playlistStates).forEach(key => delete playlistStates[key]);
  response.playlists.forEach(playlist => {
    playlistStates[playlist.id] = {
      ytId: playlist.ytId,
      excludedVideoIds: new Set(playlist.excludedVideoIds ?? [])
    };
  });
}

function formatCategoryList(categories: CategoryTag[]): string {
  if (!categories.length) {
    return t('registry.table.empty');
  }
  return categories.map(category => category.label).join(', ');
}

function isPending(key: string): boolean {
  return pendingKeys.has(key);
}

async function togglePlaylistInclusion(playlist: AdminSearchPlaylistResult) {
  const channel = channelStates[playlist.parentChannelId];
  if (!channel) {
    return;
  }
  const key = `playlist-${playlist.id}`;
  if (pendingKeys.has(key)) {
    return;
  }
  pendingKeys.add(key);
  const shouldExclude = playlist.includeState !== 'EXCLUDED';
  const nextSet = new Set(channel.excludedPlaylistIds);
  if (shouldExclude) {
    nextSet.add(playlist.ytId);
  } else {
    nextSet.delete(playlist.ytId);
  }
  try {
    await updateChannelExclusions(playlist.parentChannelId, {
      excludedPlaylistIds: Array.from(nextSet),
      excludedVideoIds: Array.from(channel.excludedVideoIds)
    });
    channel.excludedPlaylistIds = nextSet;
    playlist.includeState = shouldExclude ? 'EXCLUDED' : 'INCLUDED';
    const channelEntry = results.value?.channels.find(item => item.id === playlist.parentChannelId);
    if (channelEntry) {
      channelEntry.excludedPlaylistIds = Array.from(nextSet);
      channelEntry.excludedItemCounts = {
        playlists: nextSet.size,
        videos: channel.excludedVideoIds.size
      };
    }
  } catch (err) {
    console.error('Failed to update playlist inclusion', err);
    error.value = t('registry.actions.error');
  } finally {
    pendingKeys.delete(key);
  }
}

async function toggleVideoInclusion(video: AdminSearchVideoResult) {
  const channel = channelStates[video.parentChannelId];
  if (!channel) {
    return;
  }
  const key = `video-${video.id}`;
  if (pendingKeys.has(key)) {
    return;
  }
  pendingKeys.add(key);
  const shouldExclude = video.includeState !== 'EXCLUDED';
  const nextChannelSet = new Set(channel.excludedVideoIds);
  if (shouldExclude) {
    nextChannelSet.add(video.ytId);
  } else {
    nextChannelSet.delete(video.ytId);
  }
  try {
    await updateChannelExclusions(video.parentChannelId, {
      excludedPlaylistIds: Array.from(channel.excludedPlaylistIds),
      excludedVideoIds: Array.from(nextChannelSet)
    });
    channel.excludedVideoIds = nextChannelSet;
    video.includeState = shouldExclude ? 'EXCLUDED' : 'INCLUDED';
    const channelEntry = results.value?.channels.find(item => item.id === video.parentChannelId);
    if (channelEntry) {
      channelEntry.excludedVideoIds = Array.from(nextChannelSet);
      channelEntry.excludedItemCounts = {
        playlists: channel.excludedPlaylistIds.size,
        videos: nextChannelSet.size
      };
    }

    if (video.parentPlaylistIds.length > 0) {
      const playlistEntry = results.value?.playlists.find(item => item.ytId === video.parentPlaylistIds[0]);
      if (playlistEntry) {
        const playlistState = playlistStates[playlistEntry.id];
        if (playlistState) {
          const nextPlaylistSet = new Set(playlistState.excludedVideoIds);
          if (shouldExclude) {
            nextPlaylistSet.add(video.ytId);
          } else {
            nextPlaylistSet.delete(video.ytId);
          }
          await updatePlaylistExclusions(playlistEntry.id, {
            excludedVideoIds: Array.from(nextPlaylistSet)
          });
          playlistState.excludedVideoIds = nextPlaylistSet;
          playlistEntry.excludedVideoIds = Array.from(nextPlaylistSet);
          playlistEntry.excludedVideoCount = nextPlaylistSet.size;
        }
      }
    }
  } catch (err) {
    console.error('Failed to update video inclusion', err);
    error.value = t('registry.actions.error');
  } finally {
    pendingKeys.delete(key);
  }
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
  flex-wrap: wrap;
  justify-content: space-between;
  align-items: center;
  background: var(--color-surface);
  border-radius: 1rem;
  padding: 1.75rem 2rem;
  box-shadow: var(--shadow-elevated);
  gap: 1rem;
}

.workspace-header h1 {
  margin: 0;
  font-size: 1.5rem;
  font-weight: 700;
  color: var(--color-text-primary);
}

.workspace-header p {
  margin: 0.5rem 0 0;
  color: var(--color-text-secondary);
  font-size: 1rem;
}

.error-banner {
  padding: 0.85rem 1.25rem;
  border-radius: 0.75rem;
  background: var(--color-danger-soft);
  color: var(--color-danger);
}

.skeleton-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 1rem;
}

.skeleton-card {
  height: 160px;
  border-radius: 1rem;
  background: linear-gradient(
    90deg,
    var(--color-surface-alt) 25%,
    var(--color-border) 37%,
    var(--color-surface-alt) 63%
  );
  background-size: 400% 100%;
  animation: shimmer 1.4s ease infinite;
}

@keyframes shimmer {
  0% {
    background-position: -200% 0;
  }
  100% {
    background-position: 200% 0;
  }
}

.results-grid {
  display: grid;
  gap: 2rem;
}

.results-section {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}

.section-header {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  gap: 1rem;
}

.section-header h2 {
  margin: 0;
  font-size: 1.2rem;
  font-weight: 600;
  color: var(--color-text-primary);
}

.section-header .meta {
  font-size: 0.85rem;
  color: var(--color-text-secondary);
}

.section-hint {
  margin: 0;
  color: var(--color-text-secondary);
  font-size: 0.9rem;
}

.card-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: 1rem;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
}

.card {
  background: var(--color-surface);
  border-radius: 1rem;
  box-shadow: var(--shadow-elevated);
  padding: 1.25rem;
  display: flex;
}

.card-header {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}

.card-title {
  font-weight: 600;
  color: var(--color-text-primary);
}

.card-subtitle {
  color: var(--color-text-secondary);
  font-size: 0.85rem;
}

.card-meta {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0.5rem;
  margin: 1rem 0;
}

.card-meta dt {
  font-size: 0.75rem;
  color: var(--color-text-secondary);
  opacity: 0.75;
  text-transform: uppercase;
  letter-spacing: 0.04em;
}

.card-meta dd {
  margin: 0;
  font-weight: 500;
  color: var(--color-text-primary);
}

.card-actions {
  margin-top: auto;
  display: flex;
}

.chip {
  border: 1px solid var(--color-brand);
  background: transparent;
  color: var(--color-brand);
  border-radius: 999px;
  padding: 0.4rem 1rem;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s ease;
}

.chip.active {
  background: var(--color-brand);
  color: var(--color-text-inverse);
}

.chip:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.empty-state {
  padding: 1rem;
  border-radius: 0.75rem;
  border: 1px dashed var(--color-border);
  color: var(--color-text-secondary);
  font-size: 0.9rem;
}

@media (max-width: 640px) {
  .card-meta {
    grid-template-columns: 1fr;
  }
}
</style>
