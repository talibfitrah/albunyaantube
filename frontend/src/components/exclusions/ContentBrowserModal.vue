<template>
  <Teleport to="body">
    <Transition name="modal">
      <div v-if="open" class="modal-overlay" @click.self="$emit('close')">
        <div class="modal-container" role="dialog" aria-modal="true">
          <!-- Header -->
          <div class="modal-header">
            <div class="header-info">
              <h2>{{ $t('exclusions.contentBrowser.title') }}</h2>
              <p class="header-subtitle">{{ $t('exclusions.contentBrowser.subtitle') }}</p>
            </div>
            <button class="close-btn" @click="$emit('close')" :aria-label="$t('common.close')">
              &times;
            </button>
          </div>

          <!-- Filters -->
          <div class="browser-filters">
            <input
              v-model="searchQuery"
              type="text"
              :placeholder="$t('exclusions.contentBrowser.searchPlaceholder')"
              :aria-label="$t('exclusions.contentBrowser.searchPlaceholder')"
              class="search-input"
              @keyup.enter="performSearch"
            />
            <div class="type-tabs">
              <button
                class="type-tab"
                :class="{ active: typeFilter === 'all' }"
                @click="setTypeFilter('all')"
              >
                {{ $t('exclusions.contentBrowser.filterAll') }}
              </button>
              <button
                class="type-tab"
                :class="{ active: typeFilter === 'channel' }"
                @click="setTypeFilter('channel')"
              >
                {{ $t('exclusions.contentBrowser.filterChannels') }}
              </button>
              <button
                class="type-tab"
                :class="{ active: typeFilter === 'playlist' }"
                @click="setTypeFilter('playlist')"
              >
                {{ $t('exclusions.contentBrowser.filterPlaylists') }}
              </button>
            </div>
          </div>

          <!-- Content List -->
          <div ref="scrollContainer" class="content-list" @scroll="handleScroll">
            <!-- Loading Initial -->
            <div v-if="isLoading && items.length === 0" class="loading-state">
              <div class="spinner"></div>
              <p>{{ $t('common.loading') }}</p>
            </div>

            <!-- Error State -->
            <div v-else-if="error" class="error-state">
              <p>{{ error }}</p>
              <button class="retry-btn" @click="loadContent">
                {{ $t('common.retry') }}
              </button>
            </div>

            <!-- Empty State -->
            <div v-else-if="items.length === 0" class="empty-state">
              <p>{{ $t('exclusions.contentBrowser.noResults') }}</p>
            </div>

            <!-- Items Grid -->
            <div v-else class="items-grid">
              <div
                v-for="item in items"
                :key="item.id"
                class="content-card"
                role="button"
                tabindex="0"
                @click="openDetail(item)"
                @keydown.enter="openDetail(item)"
                @keydown.space.prevent="openDetail(item)"
              >
                <img
                  v-if="item.thumbnailUrl"
                  :src="item.thumbnailUrl"
                  :alt="item.title"
                  class="card-thumbnail"
                />
                <div v-else class="card-thumbnail-placeholder"></div>
                <div class="card-info">
                  <span class="card-type" :data-type="item.type">{{ typeLabel(item.type) }}</span>
                  <h3 class="card-title">{{ item.title }}</h3>
                </div>
              </div>
            </div>

            <!-- Load More Indicator -->
            <div v-if="isLoadingMore" class="loading-more">
              <div class="spinner small"></div>
              <span>{{ $t('contentLibrary.loadingMore') }}</span>
            </div>

            <!-- Load More Error -->
            <div v-if="loadMoreError" class="load-more-error">
              <p>{{ loadMoreError }}</p>
              <button class="retry-btn" @click="retryLoadMore">
                {{ $t('common.retry') }}
              </button>
            </div>

            <!-- End of Content -->
            <div v-if="!hasMore && !loadMoreError && items.length > 0" class="end-of-content">
              {{ $t('exclusions.contentBrowser.allLoaded', { count: items.length }) }}
            </div>
          </div>

          <!-- Footer -->
          <div class="modal-footer">
            <button class="btn-secondary" @click="$emit('close')">
              {{ $t('common.close') }}
            </button>
            <button class="btn-manual" @click="$emit('manual')">
              {{ $t('exclusions.contentBrowser.manualEntry') }}
            </button>
          </div>
        </div>
      </div>
    </Transition>

    <!-- Nested Channel Detail Modal -->
    <ChannelDetailModal
      v-if="selectedChannel"
      :open="showChannelDetail"
      :channel-id="selectedChannel.id"
      :channel-youtube-id="selectedChannel.youtubeId"
      @close="closeChannelDetail"
      @updated="handleExclusionUpdated"
    />

    <!-- Nested Playlist Detail Modal -->
    <PlaylistDetailModal
      v-if="selectedPlaylist"
      :open="showPlaylistDetail"
      :playlist-id="selectedPlaylist.id"
      :playlist-youtube-id="selectedPlaylist.youtubeId"
      @close="closePlaylistDetail"
      @updated="handleExclusionUpdated"
    />
  </Teleport>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import apiClient from '@/services/api/client'
import ChannelDetailModal from '@/components/exclusions/ChannelDetailModal.vue'
import PlaylistDetailModal from '@/components/exclusions/PlaylistDetailModal.vue'

const { t: $t } = useI18n()

interface Props {
  open: boolean
}

const props = defineProps<Props>()
const emit = defineEmits<{
  close: []
  manual: []
  updated: []
}>()

interface ContentItem {
  id: string
  title: string
  type: string
  thumbnailUrl: string
  youtubeId: string
}

// State
const items = ref<ContentItem[]>([])
const isLoading = ref(false)
const isLoadingMore = ref(false)
const error = ref<string | null>(null)
const hasMore = ref(false)
const currentPage = ref(0)
const searchQuery = ref('')
const activeSearch = ref('')
const typeFilter = ref<'all' | 'channel' | 'playlist'>('all')

// Nested modals
const showChannelDetail = ref(false)
const selectedChannel = ref<ContentItem | null>(null)
const showPlaylistDetail = ref(false)
const selectedPlaylist = ref<ContentItem | null>(null)

const scrollContainer = ref<HTMLElement | null>(null)

const PAGE_SIZE = 25
let loadAbortController: AbortController | null = null

// Watch for modal open
watch(() => props.open, (isOpen) => {
  if (isOpen) {
    loadContent()
  } else {
    // Abort any in-flight request
    if (loadAbortController) {
      loadAbortController.abort()
      loadAbortController = null
    }
    // Reset state
    items.value = []
    currentPage.value = 0
    searchQuery.value = ''
    activeSearch.value = ''
    typeFilter.value = 'all'
    error.value = null
    loadMoreError.value = null
    hasMore.value = false
  }
}, { immediate: true })

async function loadContent() {
  // Abort previous request if still in-flight
  if (loadAbortController) {
    loadAbortController.abort()
  }
  loadAbortController = new AbortController()
  const signal = loadAbortController.signal

  isLoading.value = true
  error.value = null
  loadMoreError.value = null
  items.value = []
  currentPage.value = 0

  try {
    const params = buildParams(0)
    const response = await apiClient.get('/api/admin/content', { params, signal })
    items.value = response.data.content.map(mapItem)
    hasMore.value = items.value.length < (response.data.totalItems || 0)
  } catch (err: any) {
    if (err.name === 'CanceledError' || signal.aborted) return
    error.value = err.message || $t('common.error')
  } finally {
    isLoading.value = false
  }
}

const loadMoreError = ref<string | null>(null)

async function loadMore() {
  if (isLoadingMore.value || !hasMore.value) return

  isLoadingMore.value = true
  loadMoreError.value = null
  const nextPage = currentPage.value + 1

  try {
    const params = buildParams(nextPage)
    const response = await apiClient.get('/api/admin/content', { params })
    const newItems = response.data.content.map(mapItem)
    items.value.push(...newItems)
    currentPage.value = nextPage
    hasMore.value = items.value.length < (response.data.totalItems || 0)
  } catch (err: any) {
    loadMoreError.value = err.message || $t('common.error')
    hasMore.value = false
  } finally {
    isLoadingMore.value = false
  }
}

function retryLoadMore() {
  hasMore.value = true
  loadMoreError.value = null
  loadMore()
}

function buildParams(page: number): Record<string, any> {
  const params: Record<string, any> = {
    page,
    size: PAGE_SIZE,
    status: 'APPROVED'
  }
  if (activeSearch.value) {
    params.search = activeSearch.value
  }
  // Backend expects comma-separated `types` param (not `type`)
  // "All" tab shows only channels and playlists (videos have no detail modal)
  if (typeFilter.value === 'all') {
    params.types = 'CHANNEL,PLAYLIST'
  } else {
    params.types = typeFilter.value.toUpperCase()
  }
  return params
}

function mapItem(item: any): ContentItem {
  return {
    id: item.id,
    title: item.title,
    type: item.type?.toLowerCase() || 'channel',
    thumbnailUrl: item.thumbnailUrl || '',
    youtubeId: item.youtubeId || ''
  }
}

function performSearch() {
  activeSearch.value = searchQuery.value.trim()
  loadContent()
}

function setTypeFilter(filter: 'all' | 'channel' | 'playlist') {
  if (filter === typeFilter.value) return
  typeFilter.value = filter
  loadContent()
}

let lastScrollCheck = 0
const SCROLL_THROTTLE_MS = 150

function handleScroll() {
  const now = Date.now()
  if (now - lastScrollCheck < SCROLL_THROTTLE_MS) return
  lastScrollCheck = now

  const el = scrollContainer.value
  if (!el) return
  const nearBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 200
  if (nearBottom) {
    loadMore()
  }
}

function typeLabel(type: string): string {
  if (type === 'channel') return $t('exclusions.contentBrowser.filterChannels')
  if (type === 'playlist') return $t('exclusions.contentBrowser.filterPlaylists')
  return type
}

function openDetail(item: ContentItem) {
  if (item.type === 'channel') {
    selectedChannel.value = item
    showChannelDetail.value = true
  } else if (item.type === 'playlist') {
    selectedPlaylist.value = item
    showPlaylistDetail.value = true
  }
  // Videos have no detail modal — they're filtered out by the types param,
  // but we intentionally ignore unknown types here as a safety net.
}

function closeChannelDetail() {
  showChannelDetail.value = false
  selectedChannel.value = null
}

function closePlaylistDetail() {
  showPlaylistDetail.value = false
  selectedPlaylist.value = null
}

function handleExclusionUpdated() {
  emit('updated')
}
</script>

<style scoped>
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
  z-index: 900;
  padding: 1rem;
}

.modal-container {
  background: var(--color-surface, #ffffff);
  border-radius: 12px;
  width: 100%;
  max-width: 960px;
  max-height: 90vh;
  display: flex;
  flex-direction: column;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.3);
}

.modal-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  padding: 1.5rem;
  border-bottom: 1px solid var(--color-border, #e5e7eb);
}

.header-info h2 {
  margin: 0;
  font-size: 1.25rem;
  font-weight: 600;
}

.header-subtitle {
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

/* Filters */
.browser-filters {
  display: flex;
  gap: 1rem;
  padding: 1rem 1.5rem;
  border-bottom: 1px solid var(--color-border, #e5e7eb);
  align-items: center;
  flex-wrap: wrap;
}

.search-input {
  flex: 1;
  min-width: 200px;
  padding: 0.625rem 1rem;
  border: 1px solid var(--color-border, #d1d5db);
  border-radius: 8px;
  font-size: 0.875rem;
}

.type-tabs {
  display: flex;
  gap: 0.25rem;
  background: var(--color-surface-alt, #f3f4f6);
  border-radius: 8px;
  padding: 0.25rem;
}

.type-tab {
  padding: 0.5rem 1rem;
  background: none;
  border: none;
  border-radius: 6px;
  cursor: pointer;
  font-weight: 500;
  font-size: 0.875rem;
  color: var(--color-text-secondary, #6b7280);
  transition: all 0.2s;
}

.type-tab.active {
  background: var(--color-surface, #ffffff);
  color: var(--color-text-primary, #111827);
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.1);
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

.spinner.small {
  width: 24px;
  height: 24px;
  border-width: 2px;
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

/* Items Grid */
.items-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 1rem;
}

.content-card {
  border: 1px solid var(--color-border, #e5e7eb);
  border-radius: 8px;
  overflow: hidden;
  cursor: pointer;
  transition: all 0.2s;
}

.content-card:hover {
  border-color: var(--color-primary, #3b82f6);
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.08);
}

.card-thumbnail {
  width: 100%;
  height: 120px;
  object-fit: cover;
}

.card-thumbnail-placeholder {
  width: 100%;
  height: 120px;
  background: linear-gradient(135deg, var(--color-surface-alt, #f3f4f6), var(--color-border, #e5e7eb));
}

.card-info {
  padding: 0.75rem;
}

.card-type {
  display: inline-block;
  font-size: 0.6875rem;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.05em;
  color: var(--color-primary, #3b82f6);
  margin-bottom: 0.25rem;
}

.card-title {
  margin: 0;
  font-size: 0.875rem;
  font-weight: 500;
  color: var(--color-text-primary, #111827);
  overflow: hidden;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

.loading-more {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 1.5rem;
  gap: 0.5rem;
  color: var(--color-text-secondary, #6b7280);
  font-size: 0.875rem;
}

.load-more-error {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 1rem;
  gap: 0.5rem;
  color: var(--color-danger, #ef4444);
  font-size: 0.875rem;
}

.load-more-error p {
  margin: 0;
}

.end-of-content {
  text-align: center;
  padding: 1rem;
  color: var(--color-text-secondary, #6b7280);
  font-size: 0.875rem;
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

.btn-manual {
  padding: 0.625rem 1.5rem;
  background: transparent;
  border: 1.5px solid var(--color-brand, #16835a);
  color: var(--color-brand, #16835a);
  border-radius: 8px;
  cursor: pointer;
  font-weight: 500;
  transition: all 0.2s;
}

.btn-manual:hover {
  background: var(--color-brand-soft, #e6f7f0);
}

/* Transitions */
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

  .browser-filters {
    flex-direction: column;
  }

  .items-grid {
    grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
  }
}
</style>
