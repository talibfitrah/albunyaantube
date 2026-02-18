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

    <!-- Content Type Filter -->
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
          <label>{{ t('contentSearch.filters.sort') }}</label>
          <select v-model="sortFilter" class="filter-select">
            <option value="DATE">Most Recent</option>
            <option value="POPULAR">Most Popular</option>
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

    <div v-else-if="hasSearched && !hasResults" class="empty-state">
      <p>{{ t('contentSearch.noResults') }}</p>
    </div>

    <div v-else-if="hasResults" class="results">
      <div class="results-header">
        <p>{{ getResultsCountText() }}</p>
      </div>
      <div class="results-list">
        <ChannelCard
          v-for="channel in filteredChannels"
          :key="'channel-' + channel.id"
          :channel="channel"
          :already-added="isChannelAlreadyAdded(channel.ytId)"
          :is-admin="authStore.isAdmin"
          @add="handleAddChannel"
        />
        <PlaylistCard
          v-for="playlist in filteredPlaylists"
          :key="'playlist-' + playlist.id"
          :playlist="playlist"
          :already-added="isPlaylistAlreadyAdded(playlist.ytId)"
          :is-admin="authStore.isAdmin"
          @add="handleAddPlaylist"
        />
        <VideoCard
          v-for="video in filteredVideos"
          :key="'video-' + video.id"
          :video="video"
          :already-added="isVideoAlreadyAdded(video.ytId)"
          :is-admin="authStore.isAdmin"
          @add="handleAddVideo"
        />
      </div>

      <!-- Load More button -->
      <div v-if="hasMoreResults" class="load-more-container">
        <button
          type="button"
          class="btn-load-more"
          :disabled="isLoadingMore"
          @click="loadMoreResults"
        >
          <span v-if="isLoadingMore" class="spinner-small"></span>
          {{ isLoadingMore ? t('contentSearch.loading') : t('contentSearch.loadMore') }}
        </button>
      </div>

      <!-- End of results message -->
      <div v-else-if="hasSearched && hasResults" class="end-of-results">
        <p>{{ t('contentSearch.endOfResults') }}</p>
      </div>
    </div>

    <!-- Category Assignment Modal -->
    <CategoryAssignmentModal
      :is-open="isCategoryModalOpen"
      :multi-select="true"
      @close="handleCategoryModalClose"
      @assign="handleCategoryAssignment"
    />

  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue';
import { useI18n } from 'vue-i18n';
import { toast } from '@/utils/toast';
import { fetchAllCategories } from '@/services/categories';
import { searchYouTube, addToPendingApprovals } from '@/services/youtubeService';
import apiClient from '@/services/api/client';
import { useAuthStore } from '@/stores/auth';
import ChannelCard from '@/components/search/ChannelCard.vue';
import PlaylistCard from '@/components/search/PlaylistCard.vue';
import VideoCard from '@/components/search/VideoCard.vue';
import CategoryAssignmentModal from '@/components/CategoryAssignmentModal.vue';
import type { AdminSearchChannelResult, AdminSearchPlaylistResult, AdminSearchVideoResult } from '@/types/registry';

const { t } = useI18n();
const authStore = useAuthStore();

const searchQuery = ref('');
const contentType = ref<'all' | 'channels' | 'playlists' | 'videos'>('all');
const sortFilter = ref('DATE');

const isLoading = ref(false);
const isLoadingMore = ref(false);
const error = ref<string | null>(null);
const hasSearched = ref(false);
const channels = ref<AdminSearchChannelResult[]>([]);
const playlists = ref<AdminSearchPlaylistResult[]>([]);
const videos = ref<AdminSearchVideoResult[]>([]);
const existingChannelIds = ref<Set<string>>(new Set());
const existingPlaylistIds = ref<Set<string>>(new Set());
const existingVideoIds = ref<Set<string>>(new Set());
const hasMoreResults = ref(true);
const currentPage = ref(0);
const nextPageToken = ref<string | null>(null);

// Monotonic sequence counter for stable pagination ordering.
// Each item gets a seq number at insertion time so load-more appends
// never cause existing items to jump positions.
let insertionSeq = 0;
const itemSequence = ref<Map<string, number>>(new Map());

// Category modal state
const isCategoryModalOpen = ref(false);
const pendingContent = ref<{
  data: AdminSearchChannelResult | AdminSearchPlaylistResult | AdminSearchVideoResult;
  type: 'channel' | 'playlist' | 'video';
} | null>(null);

const contentTypes: Array<{ value: 'all' | 'channels' | 'playlists' | 'videos'; labelKey: string }> = [
  { value: 'all', labelKey: 'contentSearch.types.all' },
  { value: 'channels', labelKey: 'contentSearch.types.channels' },
  { value: 'playlists', labelKey: 'contentSearch.types.playlists' },
  { value: 'videos', labelKey: 'contentSearch.types.videos' }
];

// Helper: get a stable key for sequence tracking
function itemKey(type: string, data: any): string {
  return `${type}:${data.ytId || data.id || ''}`;
}

// Helper: get the insertion sequence for an item (for stable sort tiebreaker)
function getSeq(type: string, data: any): number {
  return itemSequence.value.get(itemKey(type, data)) ?? 0;
}

// Unified sorted results - combines all types and sorts globally.
// Uses insertion sequence as tiebreaker so load-more never causes
// existing items to jump positions.
const sortedResults = computed(() => {
  // Filter by content type
  let allItems: Array<{type: string; data: any}> = [];

  if (contentType.value === 'all' || contentType.value === 'channels') {
    allItems.push(...channels.value.map(c => ({ type: 'channel', data: c })));
  }
  if (contentType.value === 'all' || contentType.value === 'playlists') {
    allItems.push(...playlists.value.map(p => ({ type: 'playlist', data: p })));
  }
  if (contentType.value === 'all' || contentType.value === 'videos') {
    allItems.push(...videos.value.map(v => ({ type: 'video', data: v })));
  }

  // Apply global sorting with stable insertion-order tiebreaker
  switch (sortFilter.value) {
    case 'DATE':
      return [...allItems].sort((a, b) => {
        const dateA = new Date(a.data.publishedAt || 0).getTime();
        const dateB = new Date(b.data.publishedAt || 0).getTime();
        const cmp = dateB - dateA;
        return cmp !== 0 ? cmp : getSeq(a.type, a.data) - getSeq(b.type, b.data);
      });
    case 'POPULAR':
      return [...allItems].sort((a, b) => {
        let valueA = 0;
        let valueB = 0;

        if (a.type === 'channel') valueA = a.data.subscriberCount || 0;
        else if (a.type === 'video') valueA = a.data.viewCount || 0;
        else if (a.type === 'playlist') valueA = a.data.itemCount || 0;

        if (b.type === 'channel') valueB = b.data.subscriberCount || 0;
        else if (b.type === 'video') valueB = b.data.viewCount || 0;
        else if (b.type === 'playlist') valueB = b.data.itemCount || 0;

        const cmp = valueB - valueA;
        return cmp !== 0 ? cmp : getSeq(a.type, a.data) - getSeq(b.type, b.data);
      });
    default:
      // Insertion order (API relevance order)
      return [...allItems].sort((a, b) => getSeq(a.type, a.data) - getSeq(b.type, b.data));
  }
});

// Separate the unified sorted results back into types for rendering
const filteredChannels = computed(() => {
  return sortedResults.value.filter(item => item.type === 'channel').map(item => item.data);
});

const filteredPlaylists = computed(() => {
  return sortedResults.value.filter(item => item.type === 'playlist').map(item => item.data);
});

const filteredVideos = computed(() => {
  return sortedResults.value.filter(item => item.type === 'video').map(item => item.data);
});

const hasResults = computed(() => {
  return sortedResults.value.length > 0;
});

// Check if item is already added
function isChannelAlreadyAdded(channelId: string): boolean {
  return existingChannelIds.value.has(channelId);
}

function isPlaylistAlreadyAdded(playlistId: string): boolean {
  return existingPlaylistIds.value.has(playlistId);
}

function isVideoAlreadyAdded(videoId: string): boolean {
  return existingVideoIds.value.has(videoId);
}

async function handleSearch() {
  if (!searchQuery.value.trim()) {
    return;
  }

  isLoading.value = true;
  error.value = null;
  hasSearched.value = true;
  currentPage.value = 0;
  nextPageToken.value = null;

  try {
    // Always search for 'all' types - contentType filter is applied client-side via computed properties
    const response = await searchYouTube(searchQuery.value, 'all');

    // Reset sequence counter and map for new search
    insertionSeq = 0;
    itemSequence.value = new Map();

    // Store all results separately (sorting applied by computed properties)
    channels.value = response.channels;
    playlists.value = response.playlists;
    videos.value = response.videos;

    // Assign insertion sequences to all initial results
    for (const c of response.channels) itemSequence.value.set(itemKey('channel', c), insertionSeq++);
    for (const p of response.playlists) itemSequence.value.set(itemKey('playlist', p), insertionSeq++);
    for (const v of response.videos) itemSequence.value.set(itemKey('video', v), insertionSeq++);

    // Update pagination state
    nextPageToken.value = response.nextPageToken || null;
    hasMoreResults.value = !!response.nextPageToken;

    // Don't check existing items upfront - check on-demand when user clicks "Add"
    // This speeds up search significantly!
  } catch (err) {
    error.value = err instanceof Error ? err.message : t('contentSearch.error');
  } finally {
    isLoading.value = false;
  }
}

async function checkExistingItems() {
  try {
    const response = await apiClient.post('/api/admin/youtube/check-existing', {
      channelIds: channels.value.map(c => c.ytId),
      playlistIds: playlists.value.map(p => p.ytId),
      videoIds: videos.value.map(v => v.ytId)
    });

    existingChannelIds.value = new Set(response.data.existingChannels);
    existingPlaylistIds.value = new Set(response.data.existingPlaylists);
    existingVideoIds.value = new Set(response.data.existingVideos);
  } catch (err) {
    console.error('Failed to check existing items', err);
  }
}

function getResultsCountText(): string {
  const totalCount = filteredChannels.value.length + filteredPlaylists.value.length + filteredVideos.value.length;

  if (contentType.value === 'all') {
    const parts = [];
    if (filteredChannels.value.length > 0) parts.push(`${filteredChannels.value.length} channel${filteredChannels.value.length !== 1 ? 's' : ''}`);
    if (filteredPlaylists.value.length > 0) parts.push(`${filteredPlaylists.value.length} playlist${filteredPlaylists.value.length !== 1 ? 's' : ''}`);
    if (filteredVideos.value.length > 0) parts.push(`${filteredVideos.value.length} video${filteredVideos.value.length !== 1 ? 's' : ''}`);

    return parts.length > 0 ? `Found ${parts.join(', ')}` : 'No results';
  }

  return t('contentSearch.resultsCount', { count: totalCount });
}

async function handleAddChannel(channel: AdminSearchChannelResult) {
  // Open category modal instead of directly submitting
  pendingContent.value = { data: channel, type: 'channel' };
  isCategoryModalOpen.value = true;
}

async function handleAddPlaylist(playlist: AdminSearchPlaylistResult) {
  // Open category modal instead of directly submitting
  pendingContent.value = { data: playlist, type: 'playlist' };
  isCategoryModalOpen.value = true;
}

async function handleAddVideo(video: AdminSearchVideoResult) {
  // Open category modal instead of directly submitting
  pendingContent.value = { data: video, type: 'video' };
  isCategoryModalOpen.value = true;
}

async function handleCategoryAssignment(categoryIds: string[]) {
  if (!pendingContent.value) return;

  // Store reference before clearing
  const content = pendingContent.value;

  // Close modal immediately to prevent intercept issues
  isCategoryModalOpen.value = false;
  pendingContent.value = null;

  try {
    // Submit with selected categories
    await addToPendingApprovals(
      content.data,
      content.type,
      categoryIds
    );

    const typeLabel = content.type.charAt(0).toUpperCase() + content.type.slice(1);
    if (authStore.isAdmin) {
      toast.success(t('contentSearch.addedApproved', { type: typeLabel, count: categoryIds.length }));
    } else {
      toast.success(t('contentSearch.addedQueue', { type: typeLabel, count: categoryIds.length }));
    }

    // Mark as already added
    if (content.type === 'channel') {
      existingChannelIds.value.add((content.data as AdminSearchChannelResult).ytId);
    } else if (content.type === 'playlist') {
      existingPlaylistIds.value.add((content.data as AdminSearchPlaylistResult).ytId);
    } else if (content.type === 'video') {
      existingVideoIds.value.add((content.data as AdminSearchVideoResult).ytId);
    }

    // Close modal on success
    isCategoryModalOpen.value = false;
    pendingContent.value = null;
  } catch (err: any) {
    console.error('Failed to add content for approval', err);
    if (err.response?.status === 409) {
      toast.error(t('contentSearch.alreadyExists'));
    } else {
      toast.error(t('contentSearch.addError'));
    }
  }
}

function handleCategoryModalClose() {
  isCategoryModalOpen.value = false;
  pendingContent.value = null;
}

async function loadMoreResults() {
  if (isLoadingMore.value || !hasMoreResults.value || !nextPageToken.value) {
    return; // Prevent duplicate requests
  }

  isLoadingMore.value = true;

  try {
    // Always load 'all' types for pagination since contentType filter is client-side
    const response = await searchYouTube(searchQuery.value, 'all', nextPageToken.value);

    // Assign insertion sequences to new items before appending
    for (const c of response.channels) itemSequence.value.set(itemKey('channel', c), insertionSeq++);
    for (const p of response.playlists) itemSequence.value.set(itemKey('playlist', p), insertionSeq++);
    for (const v of response.videos) itemSequence.value.set(itemKey('video', v), insertionSeq++);

    // Append new results to existing ones (sorting applied by computed properties)
    channels.value = [...channels.value, ...response.channels];
    playlists.value = [...playlists.value, ...response.playlists];
    videos.value = [...videos.value, ...response.videos];

    // Update pagination state
    nextPageToken.value = response.nextPageToken || null;
    hasMoreResults.value = !!response.nextPageToken;

    currentPage.value++;
  } catch (err) {
    console.error('Failed to load more results', err);
    hasMoreResults.value = false;
  } finally {
    isLoadingMore.value = false;
  }
}

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

.results-list {
  display: flex;
  flex-direction: column;
  gap: 1rem;
}

.load-more-container {
  display: flex;
  justify-content: center;
  padding: 1.5rem 0;
}

.btn-load-more {
  display: inline-flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.75rem 2rem;
  background: var(--color-brand);
  color: var(--color-text-inverse);
  border: none;
  border-radius: 0.5rem;
  font-weight: 600;
  font-size: 0.9375rem;
  cursor: pointer;
  transition: all 0.2s ease;
}

.btn-load-more:hover:not(:disabled) {
  background: var(--color-accent);
  box-shadow: 0 4px 12px rgba(22, 131, 90, 0.25);
  transform: translateY(-1px);
}

.btn-load-more:disabled {
  opacity: 0.7;
  cursor: not-allowed;
}

.spinner-small {
  width: 1.5rem;
  height: 1.5rem;
  border: 2px solid var(--color-border);
  border-top-color: var(--color-brand);
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

.end-of-results {
  text-align: center;
  padding: 2rem;
  color: var(--color-text-tertiary);
  font-size: 0.875rem;
  border-top: 1px solid var(--color-border);
  margin-top: 1rem;
}

.end-of-results p {
  margin: 0;
}
</style>
