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
            <option value="RELEVANCE">{{ t('contentSearch.filters.mostRelevant') }}</option>
            <option value="DATE">{{ t('contentSearch.filters.mostRecent') }}</option>
            <option value="VIEW_COUNT">{{ t('contentSearch.filters.mostPopular') }}</option>
            <option value="RATING">{{ t('contentSearch.filters.topRated') }}</option>
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
          @add="handleAddChannel"
        />
        <PlaylistCard
          v-for="playlist in filteredPlaylists"
          :key="'playlist-' + playlist.id"
          :playlist="playlist"
          :already-added="isPlaylistAlreadyAdded(playlist.ytId)"
          @add="handleAddPlaylist"
        />
        <VideoCard
          v-for="video in filteredVideos"
          :key="'video-' + video.id"
          :video="video"
          :already-added="isVideoAlreadyAdded(video.ytId)"
          @add="handleAddVideo"
        />
      </div>

      <!-- Loading more indicator -->
      <div v-if="isLoadingMore" class="loading-more">
        <div class="spinner-small"></div>
        <p>Loading more results...</p>
      </div>

      <!-- End of results message -->
      <div v-else-if="hasSearched && !hasMoreResults && hasResults" class="end-of-results">
        <p>End of results</p>
      </div>
    </div>

  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue';
import { useI18n } from 'vue-i18n';
import { toast } from '@/utils/toast';
import { fetchAllCategories } from '@/services/categories';
import { searchYouTube, addToPendingApprovals } from '@/services/youtubeService';
import apiClient from '@/services/api/client';
import ChannelCard from '@/components/search/ChannelCard.vue';
import PlaylistCard from '@/components/search/PlaylistCard.vue';
import VideoCard from '@/components/search/VideoCard.vue';
import type { AdminSearchChannelResult, AdminSearchPlaylistResult, AdminSearchVideoResult } from '@/types/registry';

const { t } = useI18n();

const searchQuery = ref('');
const contentType = ref<'all' | 'channels' | 'playlists' | 'videos'>('all');
const sortFilter = ref('RELEVANCE');

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

const contentTypes: Array<{ value: 'all' | 'channels' | 'playlists' | 'videos'; labelKey: string }> = [
  { value: 'all', labelKey: 'contentSearch.types.all' },
  { value: 'channels', labelKey: 'contentSearch.types.channels' },
  { value: 'playlists', labelKey: 'contentSearch.types.playlists' },
  { value: 'videos', labelKey: 'contentSearch.types.videos' }
];

// Computed filtered results based on selected content type
const filteredChannels = computed(() => {
  return contentType.value === 'all' || contentType.value === 'channels' ? channels.value : [];
});

const filteredPlaylists = computed(() => {
  return contentType.value === 'all' || contentType.value === 'playlists' ? playlists.value : [];
});

const filteredVideos = computed(() => {
  return contentType.value === 'all' || contentType.value === 'videos' ? videos.value : [];
});

const hasResults = computed(() => {
  return filteredChannels.value.length > 0 || filteredPlaylists.value.length > 0 || filteredVideos.value.length > 0;
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

// Apply sort filter to results
function applySortFilter(results: any[], type: 'channels' | 'playlists' | 'videos') {
  if (!results || results.length === 0) return results;

  const sorted = [...results];

  switch (sortFilter.value) {
    case 'DATE':
      return sorted.sort((a, b) => {
        const dateA = new Date(a.publishedAt || 0).getTime();
        const dateB = new Date(b.publishedAt || 0).getTime();
        return dateB - dateA; // Most recent first
      });
    case 'VIEW_COUNT':
      if (type === 'channels') {
        return sorted.sort((a, b) => (b.subscriberCount || 0) - (a.subscriberCount || 0));
      } else if (type === 'videos') {
        return sorted.sort((a, b) => (b.viewCount || 0) - (a.viewCount || 0));
      } else if (type === 'playlists') {
        return sorted.sort((a, b) => (b.itemCount || 0) - (a.itemCount || 0));
      }
      return sorted;
    case 'RATING':
      // For rating, we can use subscriber count for channels, view count for videos
      if (type === 'channels') {
        return sorted.sort((a, b) => (b.subscriberCount || 0) - (a.subscriberCount || 0));
      } else if (type === 'videos') {
        return sorted.sort((a, b) => (b.viewCount || 0) - (a.viewCount || 0));
      }
      return sorted;
    case 'RELEVANCE':
    default:
      // YouTube API returns results sorted by relevance by default
      return results;
  }
}

async function handleSearch() {
  if (!searchQuery.value.trim()) {
    return;
  }

  isLoading.value = true;
  error.value = null;
  hasSearched.value = true;
  currentPage.value = 0;
  hasMoreResults.value = true;
  nextPageToken.value = null;

  try {
    const response = await searchYouTube(searchQuery.value, contentType.value);

    // Store all results separately with sorting applied
    channels.value = applySortFilter(response.channels, 'channels');
    playlists.value = applySortFilter(response.playlists, 'playlists');
    videos.value = applySortFilter(response.videos, 'videos');

    // Don't check existing items upfront - check on-demand when user clicks "Add"
    // This speeds up search significantly!

    // YouTube API typically returns 20 results per page, but we'll check if there are fewer
    const totalResults = response.channels.length + response.playlists.length + response.videos.length;
    if (totalResults === 0) {
      hasMoreResults.value = false;
    }
    // Note: YouTube search API doesn't provide pageToken in the response we're getting
    // We'll keep hasMoreResults true to allow user to try loading more
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
  try {
    await addToPendingApprovals(channel, 'channel');
    toast.success('Channel added to approval queue');
  } catch (err: any) {
    console.error('Failed to add channel for approval', err);
    if (err.response?.status === 409) {
      toast.error('This channel already exists in the registry');
    } else {
      toast.error('Failed to add channel for approval');
    }
  }
}

async function handleAddPlaylist(playlist: AdminSearchPlaylistResult) {
  try {
    await addToPendingApprovals(playlist, 'playlist');
    toast.success('Playlist added to approval queue');
  } catch (err: any) {
    console.error('Failed to add playlist for approval', err);
    if (err.response?.status === 409) {
      toast.error('This playlist already exists in the registry');
    } else {
      toast.error('Failed to add playlist for approval');
    }
  }
}

async function handleAddVideo(video: AdminSearchVideoResult) {
  toast.info('Video approval coming soon');
}

// Infinite scroll handler
let isScrollListenerActive = false;

function handleScroll() {
  if (isLoadingMore.value || !hasMoreResults.value || !hasSearched.value) {
    return;
  }

  const scrollPosition = window.innerHeight + window.scrollY;
  const threshold = document.documentElement.scrollHeight - 300; // 300px before bottom

  if (scrollPosition >= threshold) {
    loadMoreResults();
  }
}

async function loadMoreResults() {
  if (isLoadingMore.value || !hasMoreResults.value) {
    return; // Prevent duplicate requests
  }

  isLoadingMore.value = true;

  try {
    // YouTube API returns max 20 results per search query without pagination support in current implementation
    // Future enhancement: Add pageToken support to backend for true pagination

    // Simulate checking for more results
    await new Promise(resolve => setTimeout(resolve, 500));

    // For now, disable further loading after initial results
    // This prevents the "All results loaded" popup while maintaining smooth UX
    hasMoreResults.value = false;
  } catch (err) {
    console.error('Failed to load more results', err);
  } finally {
    isLoadingMore.value = false;
  }
}

onMounted(() => {
  window.addEventListener('scroll', handleScroll);
  isScrollListenerActive = true;
});

onUnmounted(() => {
  if (isScrollListenerActive) {
    window.removeEventListener('scroll', handleScroll);
  }
});
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

.loading-more {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 0.75rem;
  padding: 2rem;
  color: var(--color-text-secondary);
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
