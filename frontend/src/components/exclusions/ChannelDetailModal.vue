<template>
  <Teleport to="body">
    <Transition name="modal">
      <div v-if="open" class="modal-overlay" @click.self="$emit('close')">
        <div class="modal-container" role="dialog" aria-modal="true">
          <!-- Header -->
          <div class="modal-header">
            <div class="channel-info">
              <img
                v-if="channelDetails?.thumbnailUrl"
                :src="channelDetails.thumbnailUrl"
                :alt="channelDetails.name"
                class="channel-thumbnail"
              />
              <div class="channel-meta">
                <h2 class="channel-name">{{ channelDetails?.name || $t('exclusions.channelDetail.title') }}</h2>
                <p v-if="channelDetails" class="channel-stats">
                  {{ $t('exclusions.channelDetail.subscribers', { count: formatNumber(channelDetails.subscribers) }) }} |
                  {{ $t('exclusions.channelDetail.videos', { count: formatNumber(channelDetails.videoCount) }) }}
                </p>
              </div>
            </div>
            <button class="close-btn" @click="$emit('close')" :aria-label="$t('common.close')">
              ×
            </button>
          </div>

          <!-- Tabs -->
          <div class="tabs">
            <button
              class="tab"
              :class="{ active: activeTab === 'videos' }"
              @click="switchTab('videos')"
            >
              {{ $t('exclusions.channelDetail.tabs.videos') }}
            </button>
            <button
              class="tab"
              :class="{ active: activeTab === 'playlists' }"
              @click="switchTab('playlists')"
            >
              {{ $t('exclusions.channelDetail.tabs.playlists') }}
            </button>
          </div>

          <!-- Search Bar -->
          <div class="search-bar">
            <input
              v-model="searchQuery"
              type="text"
              :placeholder="$t('exclusions.channelDetail.search')"
              class="search-input"
              @keyup.enter="performSearch"
            />
            <button class="search-btn" @click="performSearch">
              🔍
            </button>
          </div>

          <!-- Content List -->
          <div ref="containerRef" class="content-list">
            <!-- Loading Initial -->
            <div v-if="initialLoading" class="loading-state">
              <div class="spinner"></div>
              <p>{{ $t('common.loading') }}</p>
            </div>

            <!-- Error State -->
            <div v-else-if="error" class="error-state">
              <p>{{ error }}</p>
              <button class="retry-btn" @click="loadInitialData">
                {{ $t('common.retry') }}
              </button>
            </div>

            <!-- Empty State -->
            <div v-else-if="items.length === 0" class="empty-state">
              <p>{{ $t('exclusions.channelDetail.noResults') }}</p>
            </div>

            <!-- Items List -->
            <div v-else class="items-list">
              <div
                v-for="item in items"
                :key="item.id"
                class="item"
                :class="{ excluded: isExcluded(item.id) }"
              >
                <img
                  v-if="item.thumbnailUrl"
                  :src="item.thumbnailUrl"
                  :alt="item.title"
                  class="item-thumbnail"
                />
                <div class="item-details">
                  <h3 class="item-title">{{ item.title }}</h3>
                  <p class="item-meta">
                    <span v-if="activeTab === 'videos'">
                      {{ formatDate(item.publishedAt) }}
                    </span>
                    <span v-else>
                      {{ $t('exclusions.channelDetail.itemCount', { count: item.itemCount || 0 }) }}
                    </span>
                  </p>
                </div>
                <button
                  v-if="isExcluded(item.id)"
                  class="action-btn remove"
                  @click="removeExclusion(item.id)"
                  :disabled="actionLoading[item.id]"
                >
                  {{ $t('exclusions.channelDetail.removeExclusion') }}
                </button>
                <button
                  v-else
                  class="action-btn exclude"
                  @click="addExclusion(item.id)"
                  :disabled="actionLoading[item.id]"
                >
                  {{ $t('exclusions.channelDetail.exclude') }}
                </button>
              </div>
            </div>

            <!-- Load More Indicator -->
            <div v-if="isLoading && items.length > 0" class="loading-more">
              <div class="spinner"></div>
              <p>{{ $t('exclusions.channelDetail.loadingMore') }}</p>
            </div>

            <!-- No More Items -->
            <div v-if="!hasMore && items.length > 0" class="no-more">
              {{ $t('exclusions.channelDetail.noMoreItems') }}
            </div>
          </div>

          <!-- Footer -->
          <div class="modal-footer">
            <button class="btn-secondary" @click="$emit('close')">
              {{ $t('common.close') }}
            </button>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup lang="ts">
import { ref, watch, reactive } from 'vue'
import { useI18n } from 'vue-i18n'
import { useInfiniteScroll } from '@/composables/useInfiniteScroll'
import { fetchChannelExclusions, addChannelExclusion, removeChannelExclusion } from '@/services/exclusions'
import { getChannelDetails, getChannelVideos, getChannelPlaylists } from '@/services/youtubeService'

const { t: $t } = useI18n()

interface Props {
  open: boolean
  channelId: string
  channelYoutubeId: string
}

const props = defineProps<Props>()
const emit = defineEmits<{
  close: []
  updated: []
}>()

// State
const activeTab = ref<'videos' | 'playlists'>('videos')
const searchQuery = ref('')
const activeSearch = ref('')
const items = ref<any[]>([])
const nextPageToken = ref<string | null>(null)
const excludedIds = ref<Set<string>>(new Set())
const actionLoading = reactive<Record<string, boolean>>({})
const initialLoading = ref(false)
const error = ref<string | null>(null)
const channelDetails = ref<any>(null)
let requestIdCounter = 0 // Track in-flight requests to prevent race conditions

// Infinite scroll
const { containerRef, isLoading, hasMore, reset } = useInfiniteScroll({
  threshold: 200,
  throttleMs: 500,
  onLoadMore: loadMore
})

// Watch for modal open
watch(() => props.open, (isOpen) => {
  if (isOpen) {
    loadInitialData()
  } else {
    // Invalidate any in-flight requests when closing
    requestIdCounter++

    // Reset state when closed
    items.value = []
    nextPageToken.value = null
    searchQuery.value = ''
    activeSearch.value = ''
    error.value = null
    reset()
  }
})

async function loadInitialData() {
  initialLoading.value = true
  error.value = null
  items.value = []
  nextPageToken.value = null
  reset()

  try {
    // Load channel details
    const detailsResponse = await getChannelDetails(props.channelYoutubeId)
    channelDetails.value = {
      name: detailsResponse.channel?.name || '',
      thumbnailUrl: detailsResponse.channel?.avatarUrl || '',
      subscribers: detailsResponse.channel?.subscriberCount || 0,
      videoCount: detailsResponse.videos?.length || 0
    }

    // Load exclusions
    const exclusions = await fetchChannelExclusions(props.channelId)
    excludedIds.value = new Set(
      activeTab.value === 'videos' ? exclusions.videos : exclusions.playlists
    )

    // Load initial items
    await loadMore()
  } catch (err: any) {
    error.value = err.message || $t('common.error')
  } finally {
    initialLoading.value = false
  }
}

async function loadMore() {
  if (isLoading.value || (!hasMore.value && nextPageToken.value === null && items.value.length > 0)) {
    return
  }

  // Capture request context before any await to prevent race conditions
  const requestId = ++requestIdCounter
  const capturedTab = activeTab.value
  const capturedPageToken = nextPageToken.value
  const capturedSearch = activeSearch.value

  isLoading.value = true
  error.value = null

  try {
    let result: any

    if (capturedTab === 'videos') {
      result = await getChannelVideos(
        props.channelYoutubeId,
        capturedPageToken || undefined,
        capturedSearch || undefined
      )
    } else {
      result = await getChannelPlaylists(
        props.channelYoutubeId,
        capturedPageToken || undefined
      )
    }

    // Verify context hasn't changed while request was in flight
    if (
      requestId !== requestIdCounter ||
      capturedTab !== activeTab.value ||
      capturedPageToken !== nextPageToken.value ||
      capturedSearch !== activeSearch.value
    ) {
      // Context changed (user switched tabs/search), discard this response
      return
    }

    // Append new items
    items.value.push(...result.items)
    nextPageToken.value = result.nextPageToken || null
    hasMore.value = !!result.nextPageToken
  } catch (err: any) {
    // Verify context still matches before updating error state
    if (
      requestId !== requestIdCounter ||
      capturedTab !== activeTab.value ||
      capturedPageToken !== nextPageToken.value ||
      capturedSearch !== activeSearch.value
    ) {
      // Context changed, discard error from stale request
      return
    }

    error.value = err.message || $t('common.error')
    hasMore.value = false
  } finally {
    // Only clear loading if this is still the latest request
    if (requestId === requestIdCounter) {
      isLoading.value = false
    }
  }
}

function switchTab(tab: 'videos' | 'playlists') {
  if (tab === activeTab.value) return

  // Invalidate any in-flight requests
  requestIdCounter++

  activeTab.value = tab
  items.value = []
  nextPageToken.value = null
  searchQuery.value = ''
  activeSearch.value = ''
  reset()

  // Reload exclusions for new tab
  loadInitialData()
}

function performSearch() {
  // Invalidate any in-flight requests
  requestIdCounter++

  activeSearch.value = searchQuery.value.trim()
  items.value = []
  nextPageToken.value = null
  reset()
  loadMore()
}

function isExcluded(id: string): boolean {
  return excludedIds.value.has(id)
}

async function addExclusion(itemId: string) {
  actionLoading[itemId] = true

  try {
    const type = activeTab.value === 'videos' ? 'video' : 'playlist'
    await addChannelExclusion(props.channelId, type, itemId)
    excludedIds.value.add(itemId)
    emit('updated')
  } catch (err: any) {
    alert($t('exclusions.channelDetail.excludeError'))
  } finally {
    actionLoading[itemId] = false
  }
}

async function removeExclusion(itemId: string) {
  actionLoading[itemId] = true

  try {
    const type = activeTab.value === 'videos' ? 'video' : 'playlist'
    await removeChannelExclusion(props.channelId, type, itemId)
    excludedIds.value.delete(itemId)
    emit('updated')
  } catch (err: any) {
    alert($t('exclusions.channelDetail.removeError'))
  } finally {
    actionLoading[itemId] = false
  }
}

function formatNumber(num: number | null | undefined): string {
  if (!num) return '0'
  return num.toLocaleString()
}

function formatDate(dateStr: string | null | undefined): string {
  if (!dateStr) return ''
  const date = new Date(dateStr)
  return date.toLocaleDateString()
}
</script>

<style scoped>
/* Modal Overlay */
.modal-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.7);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
  padding: 1rem;
}

/* Modal Container */
.modal-container {
  background: var(--color-surface, #ffffff);
  border-radius: 12px;
  width: 100%;
  max-width: 900px;
  max-height: 90vh;
  display: flex;
  flex-direction: column;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.3);
}

/* Header */
.modal-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 1.5rem;
  border-bottom: 1px solid var(--color-border, #e5e7eb);
}

.channel-info {
  display: flex;
  align-items: center;
  gap: 1rem;
}

.channel-thumbnail {
  width: 60px;
  height: 60px;
  border-radius: 50%;
  object-fit: cover;
}

.channel-name {
  margin: 0;
  font-size: 1.25rem;
  font-weight: 600;
}

.channel-stats {
  margin: 0.25rem 0 0;
  font-size: 0.875rem;
  color: var(--color-text-secondary, #6b7280);
}

.close-btn {
  font-size: 2rem;
  background: none;
  border: none;
  cursor: pointer;
  color: var(--color-text-secondary, #6b7280);
  width: 40px;
  height: 40px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 8px;
  transition: background 0.2s;
}

.close-btn:hover {
  background: var(--color-surface-hover, #f3f4f6);
}

/* Tabs */
.tabs {
  display: flex;
  border-bottom: 1px solid var(--color-border, #e5e7eb);
  padding: 0 1.5rem;
}

.tab {
  padding: 1rem 1.5rem;
  background: none;
  border: none;
  border-bottom: 3px solid transparent;
  cursor: pointer;
  font-weight: 500;
  color: var(--color-text-secondary, #6b7280);
  transition: all 0.2s;
}

.tab.active {
  color: var(--color-primary, #3b82f6);
  border-bottom-color: var(--color-primary, #3b82f6);
}

/* Search Bar */
.search-bar {
  display: flex;
  gap: 0.5rem;
  padding: 1rem 1.5rem;
  border-bottom: 1px solid var(--color-border, #e5e7eb);
}

.search-input {
  flex: 1;
  padding: 0.625rem 1rem;
  border: 1px solid var(--color-border, #d1d5db);
  border-radius: 8px;
  font-size: 0.875rem;
}

.search-btn {
  padding: 0.625rem 1rem;
  background: var(--color-primary, #3b82f6);
  color: white;
  border: none;
  border-radius: 8px;
  cursor: pointer;
  font-size: 1rem;
}

/* Content List */
.content-list {
  flex: 1;
  overflow-y: auto;
  padding: 1rem 1.5rem;
}

.loading-state,
.error-state,
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 3rem 1rem;
  color: var(--color-text-secondary, #6b7280);
}

.spinner {
  width: 40px;
  height: 40px;
  border: 3px solid var(--color-border, #e5e7eb);
  border-top-color: var(--color-primary, #3b82f6);
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.retry-btn {
  margin-top: 1rem;
  padding: 0.5rem 1rem;
  background: var(--color-primary, #3b82f6);
  color: white;
  border: none;
  border-radius: 8px;
  cursor: pointer;
}

/* Items List */
.items-list {
  display: flex;
  flex-direction: column;
  gap: 1rem;
}

.item {
  display: flex;
  gap: 1rem;
  padding: 1rem;
  border: 1px solid var(--color-border, #e5e7eb);
  border-radius: 8px;
  transition: background 0.2s;
}

.item:hover {
  background: var(--color-surface-hover, #f9fafb);
}

.item.excluded {
  opacity: 0.6;
  background: var(--color-surface-disabled, #f3f4f6);
}

.item-thumbnail {
  width: 120px;
  height: 68px;
  border-radius: 6px;
  object-fit: cover;
  flex-shrink: 0;
}

.item-details {
  flex: 1;
  min-width: 0;
}

.item-title {
  margin: 0 0 0.5rem;
  font-size: 0.9375rem;
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

.item-meta {
  margin: 0;
  font-size: 0.8125rem;
  color: var(--color-text-secondary, #6b7280);
}

.action-btn {
  padding: 0.5rem 1rem;
  border: none;
  border-radius: 6px;
  cursor: pointer;
  font-size: 0.875rem;
  font-weight: 500;
  white-space: nowrap;
  align-self: center;
  transition: opacity 0.2s;
}

.action-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.action-btn.exclude {
  background: var(--color-danger, #ef4444);
  color: white;
}

.action-btn.remove {
  background: var(--color-success, #10b981);
  color: white;
}

.loading-more,
.no-more {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 1.5rem;
  color: var(--color-text-secondary, #6b7280);
  font-size: 0.875rem;
  gap: 0.5rem;
}

.loading-more .spinner {
  width: 24px;
  height: 24px;
  border-width: 2px;
}

/* Footer */
.modal-footer {
  padding: 1rem 1.5rem;
  border-top: 1px solid var(--color-border, #e5e7eb);
  display: flex;
  justify-content: flex-end;
  gap: 0.75rem;
}

.btn-secondary {
  padding: 0.625rem 1.5rem;
  background: var(--color-surface-hover, #f3f4f6);
  border: none;
  border-radius: 8px;
  cursor: pointer;
  font-weight: 500;
}

/* Modal Transitions */
.modal-enter-active,
.modal-leave-active {
  transition: opacity 0.3s;
}

.modal-enter-from,
.modal-leave-to {
  opacity: 0;
}

.modal-enter-active .modal-container,
.modal-leave-active .modal-container {
  transition: transform 0.3s;
}

.modal-enter-from .modal-container,
.modal-leave-to .modal-container {
  transform: scale(0.9);
}

/* Mobile Responsive */
@media (max-width: 768px) {
  .modal-container {
    max-height: 100vh;
    border-radius: 0;
    max-width: 100%;
  }

  .item {
    flex-direction: column;
  }

  .item-thumbnail {
    width: 100%;
    height: auto;
    aspect-ratio: 16/9;
  }

  .action-btn {
    width: 100%;
  }
}
</style>
