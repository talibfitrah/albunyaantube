<template>
  <div class="content-search">
    <header class="search-header">
      <h1>{{ t('contentSearch.heading') }}</h1>
      <p>{{ t('contentSearch.subtitle') }}</p>
    </header>

    <!-- Search Bar -->
    <div class="search-bar">
      <div class="search-input-wrapper">
        <input
          v-model="searchQuery"
          type="text"
          :placeholder="t('contentSearch.searchPlaceholder')"
          class="search-input"
          @keyup.enter="handleSearch"
        />
        <button type="button" class="search-button" @click="handleSearch">
          <span>{{ t('contentSearch.search') }}</span>
        </button>
      </div>
    </div>

    <!-- Filters -->
    <div class="filters">
      <div class="filter-group">
        <label>{{ t('contentSearch.filters.type') }}</label>
        <div class="filter-tabs">
          <button
            v-for="type in contentTypes"
            :key="type.value"
            type="button"
            :class="['filter-tab', { active: contentType === type.value }]"
            @click="contentType = type.value"
          >
            {{ t(type.labelKey) }}
          </button>
        </div>
      </div>

      <div class="filter-row">
        <div class="filter-item">
          <label>{{ t('contentSearch.filters.category') }}</label>
          <select v-model="categoryFilter" class="filter-select">
            <option value="">{{ t('contentSearch.filters.allCategories') }}</option>
            <option v-for="cat in categories" :key="cat.id" :value="cat.id">
              {{ cat.label }}
            </option>
          </select>
        </div>

        <div v-if="contentType === 'videos'" class="filter-item">
          <label>{{ t('contentSearch.filters.length') }}</label>
          <select v-model="lengthFilter" class="filter-select">
            <option value="">{{ t('contentSearch.filters.anyLength') }}</option>
            <option value="SHORT">{{ t('contentSearch.filters.short') }}</option>
            <option value="MEDIUM">{{ t('contentSearch.filters.medium') }}</option>
            <option value="LONG">{{ t('contentSearch.filters.long') }}</option>
          </select>
        </div>

        <div class="filter-item">
          <label>{{ t('contentSearch.filters.sort') }}</label>
          <select v-model="sortFilter" class="filter-select">
            <option value="RELEVANT">{{ t('contentSearch.filters.relevant') }}</option>
            <option value="RECENT">{{ t('contentSearch.filters.recent') }}</option>
            <option value="POPULAR">{{ t('contentSearch.filters.popular') }}</option>
          </select>
        </div>
      </div>
    </div>

    <!-- Results -->
    <div v-if="isLoading" class="loading">
      <div class="spinner"></div>
      <p>{{ t('contentSearch.searching') }}</p>
    </div>

    <div v-else-if="error" class="error-message" role="alert">
      <p>{{ error }}</p>
      <button type="button" @click="handleSearch">{{ t('contentSearch.retry') }}</button>
    </div>

    <div v-else-if="hasSearched && results.length === 0" class="empty-state">
      <p>{{ t('contentSearch.noResults') }}</p>
    </div>

    <div v-else-if="results.length > 0" class="results">
      <div class="results-header">
        <p>{{ t('contentSearch.resultsCount', { count: results.length }) }}</p>
      </div>
      <div class="results-grid">
        <div v-for="item in results" :key="item.id" class="result-card">
          <div class="result-thumbnail">
            <img v-if="item.thumbnailUrl" :src="item.thumbnailUrl" :alt="item.title" />
            <div v-else class="thumbnail-placeholder"></div>
          </div>
          <div class="result-content">
            <h3 class="result-title">{{ item.title }}</h3>
            <p class="result-description">{{ item.description }}</p>
            <div class="result-meta">
              <span class="meta-item">{{ item.channelTitle }}</span>
              <span v-if="item.publishedAt" class="meta-item">{{ formatDate(item.publishedAt) }}</span>
            </div>
          </div>
          <div class="result-actions">
            <button type="button" class="action-button primary" @click="handleAdd(item)">
              {{ t('contentSearch.add') }}
            </button>
          </div>
        </div>
      </div>
    </div>

    <ChannelPreviewDrawer
      :is-open="isDrawerOpen"
      :channel-id="selectedChannelId"
      @close="closeDrawer"
      @include="handleIncludeChannel"
      @exclude="handleExcludeChannel"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue';
import { useI18n } from 'vue-i18n';
import { fetchAllCategories } from '@/services/categories';
import { searchYouTube, toggleIncludeState } from '@/services/youtubeService';
import ChannelPreviewDrawer from '@/components/admin/ChannelPreviewDrawer.vue';

const { t } = useI18n();

const searchQuery = ref('');
const contentType = ref<'channels' | 'playlists' | 'videos'>('channels');
const categoryFilter = ref('');
const lengthFilter = ref('');
const sortFilter = ref('RELEVANT');

const isLoading = ref(false);
const error = ref<string | null>(null);
const hasSearched = ref(false);
const results = ref<any[]>([]);
const categories = ref<any[]>([]);
const isDrawerOpen = ref(false);
const selectedChannelId = ref<string | null>(null);

const contentTypes = [
  { value: 'channels', labelKey: 'contentSearch.types.channels' },
  { value: 'playlists', labelKey: 'contentSearch.types.playlists' },
  { value: 'videos', labelKey: 'contentSearch.types.videos' }
];

// Load categories
async function loadCategories() {
  try {
    const cats = await fetchAllCategories();
    categories.value = cats;
  } catch (err) {
    console.error('Failed to load categories', err);
  }
}

async function handleSearch() {
  if (!searchQuery.value.trim()) {
    return;
  }

  isLoading.value = true;
  error.value = null;
  hasSearched.value = true;

  try {
    const response = await searchYouTube(searchQuery.value, contentType.value);

    if (contentType.value === 'channels') {
      results.value = response.channels.map((ch: any) => ({
        id: ch.id,
        title: ch.name,
        description: `${ch.subscriberCount.toLocaleString()} subscribers`,
        channelTitle: ch.name,
        thumbnailUrl: ch.avatarUrl,
        publishedAt: null,
        rawData: ch
      }));
    } else if (contentType.value === 'playlists') {
      results.value = response.playlists.map((pl: any) => ({
        id: pl.id,
        title: pl.title,
        description: `${pl.itemCount} videos`,
        channelTitle: pl.owner.name,
        thumbnailUrl: pl.thumbnailUrl,
        publishedAt: null,
        rawData: pl
      }));
    } else {
      results.value = response.videos.map((v: any) => ({
        id: v.id,
        title: v.title,
        description: `${Math.floor(v.durationSeconds / 60)} minutes`,
        channelTitle: v.channel.name,
        thumbnailUrl: v.thumbnailUrl,
        publishedAt: v.publishedAt,
        rawData: v
      }));
    }
  } catch (err) {
    error.value = err instanceof Error ? err.message : t('contentSearch.error');
  } finally {
    isLoading.value = false;
  }
}

function handleAdd(item: any) {
  if (contentType.value === 'channels') {
    selectedChannelId.value = item.id;
    isDrawerOpen.value = true;
  } else {
    console.log('Add item:', item);
  }
}

function closeDrawer() {
  isDrawerOpen.value = false;
  selectedChannelId.value = null;
}

async function handleIncludeChannel(channelId: string) {
  try {
    await toggleIncludeState(channelId, 'channel', 'INCLUDED');
    console.log(`Channel ${channelId} included`);
  } catch (err) {
    console.error('Failed to include channel', err);
  }
}

async function handleExcludeChannel(channelId: string) {
  try {
    await toggleIncludeState(channelId, 'channel', 'NOT_INCLUDED');
    console.log(`Channel ${channelId} excluded`);
  } catch (err) {
    console.error('Failed to exclude channel', err);
  }
}

function formatDate(dateStr: string): string {
  const date = new Date(dateStr);
  return date.toLocaleDateString();
}

// Load categories on mount
loadCategories();
</script>

<style scoped>
.content-search {
  display: flex;
  flex-direction: column;
  gap: 2rem;
}

.search-header h1 {
  margin: 0;
  font-size: 2rem;
  font-weight: 700;
  color: var(--color-text-primary);
  letter-spacing: -0.02em;
}

.search-header p {
  margin: 0.75rem 0 0;
  color: var(--color-text-secondary);
  font-size: 0.9375rem;
}

.search-bar {
  background: var(--color-surface);
  padding: 1.5rem;
  border-radius: 0.75rem;
  border: 1px solid var(--color-border);
}

.search-input-wrapper {
  display: flex;
  gap: 0.75rem;
  max-width: 800px;
}

.search-input {
  flex: 1;
  padding: 0.875rem 1rem;
  border-radius: 0.5rem;
  border: 1.5px solid var(--color-border);
  background: var(--color-surface);
  font-size: 0.9375rem;
  transition: all 0.2s ease;
}

.search-input:hover {
  border-color: var(--color-brand);
}

.search-input:focus {
  outline: none;
  border-color: var(--color-brand);
  box-shadow: 0 0 0 3px rgba(22, 131, 90, 0.1);
}

.search-button {
  padding: 0.875rem 2rem;
  background: var(--color-brand);
  color: var(--color-text-inverse);
  border: none;
  border-radius: 0.5rem;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s ease;
  white-space: nowrap;
}

.search-button:hover {
  background: var(--color-accent);
  box-shadow: 0 4px 12px rgba(22, 131, 90, 0.25);
  transform: translateY(-1px);
}

.filters {
  background: var(--color-surface);
  padding: 1.5rem;
  border-radius: 0.75rem;
  border: 1px solid var(--color-border);
  display: flex;
  flex-direction: column;
  gap: 1.25rem;
}

.filter-group label {
  display: block;
  font-weight: 600;
  font-size: 0.875rem;
  color: var(--color-text-primary);
  margin-bottom: 0.75rem;
}

.filter-tabs {
  display: flex;
  gap: 0.5rem;
  flex-wrap: wrap;
}

.filter-tab {
  padding: 0.625rem 1.25rem;
  background: transparent;
  border: 1.5px solid var(--color-border);
  border-radius: 999px;
  font-size: 0.875rem;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s ease;
  color: var(--color-text-primary);
}

.filter-tab:hover {
  border-color: var(--color-brand);
  background: var(--color-brand-soft);
}

.filter-tab.active {
  background: var(--color-brand);
  color: var(--color-text-inverse);
  border-color: var(--color-brand);
}

.filter-row {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 1rem;
}

.filter-item label {
  display: block;
  font-weight: 600;
  font-size: 0.875rem;
  color: var(--color-text-primary);
  margin-bottom: 0.5rem;
}

.filter-select {
  width: 100%;
  padding: 0.75rem;
  border: 1.5px solid var(--color-border);
  border-radius: 0.5rem;
  background: var(--color-surface);
  font-size: 0.9375rem;
  cursor: pointer;
  transition: all 0.2s ease;
}

.filter-select:hover {
  border-color: var(--color-brand);
}

.filter-select:focus {
  outline: none;
  border-color: var(--color-brand);
  box-shadow: 0 0 0 3px rgba(22, 131, 90, 0.1);
}

.loading {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 1rem;
  padding: 3rem;
  color: var(--color-text-secondary);
}

.spinner {
  width: 2rem;
  height: 2rem;
  border: 3px solid var(--color-border);
  border-top-color: var(--color-brand);
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.error-message {
  background: var(--color-danger-soft);
  border: 1px solid var(--color-danger);
  border-radius: 0.75rem;
  padding: 1.5rem;
  text-align: center;
}

.error-message button {
  margin-top: 1rem;
  padding: 0.625rem 1.25rem;
  background: var(--color-brand);
  color: var(--color-text-inverse);
  border: none;
  border-radius: 0.5rem;
  font-weight: 600;
  cursor: pointer;
}

.empty-state {
  text-align: center;
  padding: 3rem;
  color: var(--color-text-secondary);
}

.results {
  display: flex;
  flex-direction: column;
  gap: 1.5rem;
}

.results-header {
  color: var(--color-text-secondary);
  font-size: 0.875rem;
}

.results-grid {
  display: flex;
  flex-direction: column;
  gap: 1rem;
}

.result-card {
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: 0.75rem;
  padding: 1.25rem;
  display: grid;
  grid-template-columns: 200px 1fr auto;
  gap: 1.25rem;
  align-items: start;
  transition: all 0.2s ease;
}

.result-card:hover {
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.08);
  border-color: var(--color-brand);
}

.result-thumbnail {
  width: 200px;
  height: 112px;
  border-radius: 0.5rem;
  overflow: hidden;
  background: var(--color-surface-alt);
}

.result-thumbnail img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.thumbnail-placeholder {
  width: 100%;
  height: 100%;
  background: linear-gradient(135deg, var(--color-surface-alt), var(--color-border));
}

.result-content {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.result-title {
  margin: 0;
  font-size: 1.125rem;
  font-weight: 600;
  color: var(--color-text-primary);
  line-height: 1.4;
}

.result-description {
  margin: 0;
  font-size: 0.875rem;
  color: var(--color-text-secondary);
  line-height: 1.6;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.result-meta {
  display: flex;
  gap: 1rem;
  font-size: 0.8125rem;
  color: var(--color-text-secondary);
}

.result-actions {
  display: flex;
  align-items: center;
}

.action-button {
  padding: 0.625rem 1.25rem;
  border: none;
  border-radius: 0.5rem;
  font-weight: 600;
  font-size: 0.875rem;
  cursor: pointer;
  transition: all 0.2s ease;
  white-space: nowrap;
}

.action-button.primary {
  background: var(--color-brand);
  color: var(--color-text-inverse);
}

.action-button.primary:hover {
  background: var(--color-accent);
  box-shadow: 0 2px 8px rgba(22, 131, 90, 0.25);
}

@media (max-width: 768px) {
  .result-card {
    grid-template-columns: 1fr;
  }

  .result-thumbnail {
    width: 100%;
    height: 200px;
  }
}
</style>
