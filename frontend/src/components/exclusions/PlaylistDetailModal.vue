<template>
  <Teleport to="body">
    <Transition name="modal">
      <div v-if="open" class="modal-overlay" @click.self="$emit('close')">
        <div class="modal-container" role="dialog" aria-modal="true">
          <!-- Header -->
          <div class="modal-header">
            <div class="playlist-info">
              <img
                v-if="playlistDetails?.thumbnailUrl"
                :src="playlistDetails.thumbnailUrl"
                :alt="playlistDetails.title"
                class="playlist-thumbnail"
              />
              <div class="playlist-meta">
                <h2 class="playlist-title">{{ playlistDetails?.title || $t('exclusions.playlistDetail.title') }}</h2>
                <p v-if="playlistDetails" class="playlist-stats">
                  {{ $t('exclusions.playlistDetail.totalVideos', { count: playlistDetails.itemCount || 0 }) }}
                </p>
              </div>
            </div>
            <button class="close-btn" @click="$emit('close')" :aria-label="$t('common.close')">
              ×
            </button>
          </div>

          <!-- Search Bar -->
          <div class="search-bar">
            <input
              v-model="searchQuery"
              type="text"
              :placeholder="$t('exclusions.playlistDetail.search')"
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
              <p>{{ $t('exclusions.playlistDetail.noResults') }}</p>
            </div>

            <!-- Items List -->
            <div v-else class="items-list">
              <div
                v-for="(item, index) in items"
                :key="item.id"
                class="item clickable"
                :class="{ excluded: hasRegistryId && isExcluded(item.videoId) }"
                @click="openVideoPreview(item)"
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
                    {{ $t('exclusions.playlistDetail.position', { position: index + 1 }) }}
                  </p>
                </div>
                <!-- Exclusion buttons only shown when registry ID is available -->
                <template v-if="hasRegistryId">
                  <button
                    v-if="isExcluded(item.videoId)"
                    class="action-btn remove"
                    @click.stop="removeExclusion(item.videoId)"
                    :disabled="actionLoading[item.videoId]"
                  >
                    {{ $t('exclusions.playlistDetail.removeExclusion') }}
                  </button>
                  <button
                    v-else
                    class="action-btn exclude"
                    @click.stop="addExclusion(item.videoId)"
                    :disabled="actionLoading[item.videoId]"
                  >
                    {{ $t('exclusions.playlistDetail.exclude') }}
                  </button>
                </template>
              </div>
            </div>

            <!-- Load More Indicator -->
            <div v-if="isLoading && items.length > 0" class="loading-more">
              <div class="spinner"></div>
              <p>{{ $t('exclusions.playlistDetail.loadingMore') }}</p>
            </div>

            <!-- No More Items -->
            <div v-if="!hasMore && items.length > 0" class="no-more">
              {{ $t('exclusions.playlistDetail.noMoreItems') }}
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

    <!-- Nested Video Preview Modal -->
    <VideoPreviewModal
      :open="showVideoPreview"
      :youtube-id="previewVideoId"
      :title="previewVideoTitle"
      @close="closeVideoPreview"
    />
  </Teleport>
</template>

<script setup lang="ts">
import { ref, computed, watch, reactive } from 'vue'
import { useI18n } from 'vue-i18n'
import { useInfiniteScroll } from '@/composables/useInfiniteScroll'
import { fetchPlaylistExclusions, addPlaylistExclusion, removePlaylistExclusion } from '@/services/exclusions'
import { getPlaylistDetails, getPlaylistVideos } from '@/services/youtubeService'
import VideoPreviewModal from '@/components/VideoPreviewModal.vue'

const { t: $t } = useI18n()

interface Props {
  open: boolean
  playlistId: string
  playlistYoutubeId: string
}

const props = defineProps<Props>()
const emit = defineEmits<{
  close: []
  updated: []
}>()

// Whether we have a registry document ID (needed for exclusion APIs)
// When opened from ChannelDetailModal with only a YouTube ID, this is false.
const hasRegistryId = computed(() => !!props.playlistId)

// State
const searchQuery = ref('')
const activeSearch = ref('')
const items = ref<any[]>([])
const nextPageToken = ref<string | null>(null)
const excludedIds = ref<Set<string>>(new Set())
const actionLoading = reactive<Record<string, boolean>>({})
const initialLoading = ref(false)
const error = ref<string | null>(null)
const playlistDetails = ref<any>(null)
let requestIdCounter = 0 // Track in-flight requests to prevent race conditions

// Video preview state
const showVideoPreview = ref(false)
const previewVideoId = ref('')
const previewVideoTitle = ref('')

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
    isLoading.value = false

    // Reset state when closed
    items.value = []
    nextPageToken.value = null
    searchQuery.value = ''
    activeSearch.value = ''
    error.value = null
    reset()
  }
}, { immediate: true })

async function loadInitialData() {
  initialLoading.value = true
  error.value = null
  items.value = []
  nextPageToken.value = null
  reset()

  try {
    // Load playlist details
    const details = await getPlaylistDetails(props.playlistYoutubeId)
    playlistDetails.value = details

    // Load exclusions only if we have a registry document ID
    if (hasRegistryId.value) {
      const exclusions = await fetchPlaylistExclusions(props.playlistId)
      excludedIds.value = new Set(exclusions)
    } else {
      excludedIds.value = new Set()
    }

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
  const capturedPlaylistId = props.playlistYoutubeId
  const capturedPageToken = nextPageToken.value
  const capturedSearch = activeSearch.value

  isLoading.value = true
  error.value = null

  try {
    const result = await getPlaylistVideos(
      capturedPlaylistId,
      capturedPageToken || undefined,
      capturedSearch || undefined
    )

    // Verify context hasn't changed while request was in flight
    if (
      requestId !== requestIdCounter ||
      capturedPlaylistId !== props.playlistYoutubeId ||
      capturedPageToken !== nextPageToken.value ||
      capturedSearch !== activeSearch.value
    ) {
      // Context changed (playlist/search changed), discard this response
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
      capturedPlaylistId !== props.playlistYoutubeId ||
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

function performSearch() {
  // Invalidate any in-flight requests
  requestIdCounter++
  isLoading.value = false

  activeSearch.value = searchQuery.value.trim()
  items.value = []
  nextPageToken.value = null
  reset()
  loadMore()
}

function isExcluded(videoId: string): boolean {
  return excludedIds.value.has(videoId)
}

async function addExclusion(videoId: string) {
  actionLoading[videoId] = true

  try {
    await addPlaylistExclusion(props.playlistId, videoId)
    excludedIds.value.add(videoId)
    emit('updated')
  } catch (err: any) {
    alert($t('exclusions.playlistDetail.excludeError'))
  } finally {
    actionLoading[videoId] = false
  }
}

async function removeExclusion(videoId: string) {
  actionLoading[videoId] = true

  try {
    await removePlaylistExclusion(props.playlistId, videoId)
    excludedIds.value.delete(videoId)
    emit('updated')
  } catch (err: any) {
    alert($t('exclusions.playlistDetail.removeError'))
  } finally {
    actionLoading[videoId] = false
  }
}

function openVideoPreview(item: any) {
  previewVideoId.value = item.videoId || item.id
  previewVideoTitle.value = item.title
  showVideoPreview.value = true
}

function closeVideoPreview() {
  showVideoPreview.value = false
  previewVideoId.value = ''
  previewVideoTitle.value = ''
}
</script>

<style scoped>
/* Reuse most styles from ChannelDetailModal */
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
  z-index: 1050;
  padding: 1rem;
}

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

.modal-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 1.5rem;
  border-bottom: 1px solid var(--color-border, #e5e7eb);
}

.playlist-info {
  display: flex;
  align-items: center;
  gap: 1rem;
}

.playlist-thumbnail {
  width: 80px;
  height: 60px;
  border-radius: 8px;
  object-fit: cover;
}

.playlist-title {
  margin: 0;
  font-size: 1.25rem;
  font-weight: 600;
}

.playlist-stats {
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

.item.clickable {
  cursor: pointer;
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
