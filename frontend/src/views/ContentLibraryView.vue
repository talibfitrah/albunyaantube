<template>
  <div class="content-library">
    <div class="header">
      <div class="header-content">
        <h1 class="heading">{{ t('contentLibrary.heading') }}</h1>
        <p class="subtitle">{{ t('contentLibrary.subtitle') }}</p>
      </div>
      <div class="header-actions">
        <!-- Mobile Filter Button -->
        <button
          type="button"
          class="btn-filter-mobile"
          @click="showFilters = true"
        >
          <span class="filter-icon">🔍</span>
          <span>{{ t('contentLibrary.filters.title') }}</span>
          <span v-if="activeFilterCount > 0" class="filter-badge">{{ activeFilterCount }}</span>
        </button>

        <button
          v-if="selectedItems.length > 0"
          type="button"
          class="btn-secondary"
          @click="clearSelection"
        >
          <span class="mobile-hidden">{{ t('contentLibrary.clearSelection') }}</span>
          <span class="desktop-hidden">{{ t('contentLibrary.clear') }}</span>
          ({{ selectedItems.length }})
        </button>
        <button
          v-if="selectedItems.length > 0"
          type="button"
          class="btn-bulk"
          @click="openBulkActionsMenu"
        >
          {{ t('contentLibrary.bulkActions') }}
        </button>
      </div>
    </div>

    <div class="content-container">
      <!-- Desktop Sidebar Filters -->
      <aside class="filters-sidebar">
        <div class="filter-section">
          <h3 class="filter-heading">{{ t('contentLibrary.filters.contentType') }}</h3>
          <label v-for="type in contentTypes" :key="type.value" class="filter-option">
            <input
              type="checkbox"
              :value="type.value"
              :checked="filters.types.includes(type.value)"
              @change="toggleTypeFilter(type.value)"
            />
            <span>{{ type.label }}</span>
          </label>
        </div>

        <div class="filter-section">
          <h3 class="filter-heading">{{ t('contentLibrary.filters.status') }}</h3>
          <label v-for="status in statuses" :key="status.value" class="filter-option">
            <input
              type="radio"
              name="status"
              :value="status.value"
              :checked="filters.status === status.value"
              @change="filters.status = status.value; loadContent()"
            />
            <span>{{ status.label }}</span>
          </label>
        </div>

        <div class="filter-section">
          <h3 class="filter-heading">{{ t('contentLibrary.filters.categories') }}</h3>
          <div class="filter-search">
            <input
              v-model="categorySearch"
              type="text"
              :placeholder="t('contentLibrary.filters.searchCategories')"
              class="filter-search-input"
            />
          </div>
          <div class="category-list">
            <label class="filter-option">
              <input
                type="radio"
                name="category"
                value=""
                :checked="!filters.category"
                @change="filters.category = ''; loadContent()"
              />
              <span>{{ t('contentLibrary.filters.allCategories') }}</span>
            </label>
            <label v-for="cat in filteredCategories" :key="cat.id" class="filter-option">
              <input
                type="radio"
                name="category"
                :value="cat.id"
                :checked="filters.category === cat.id"
                @change="filters.category = cat.id; loadContent()"
              />
              <span>{{ cat.name }}</span>
            </label>
          </div>
        </div>

        <div class="filter-section">
          <h3 class="filter-heading">{{ t('contentLibrary.filters.dateAdded') }}</h3>
          <select v-model="filters.dateAdded" class="filter-select" @change="loadContent">
            <option value="any">{{ t('contentLibrary.filters.anyDate') }}</option>
            <option value="today">{{ t('contentLibrary.filters.today') }}</option>
            <option value="week">{{ t('contentLibrary.filters.thisWeek') }}</option>
            <option value="month">{{ t('contentLibrary.filters.thisMonth') }}</option>
          </select>
        </div>

        <div class="filter-actions">
          <button type="button" class="btn-reset" @click="resetFilters">
            {{ t('contentLibrary.filters.resetAll') }}
          </button>
        </div>
      </aside>

      <!-- Main Content Area -->
      <main class="content-main">
        <div class="content-toolbar">
          <div class="search-box">
            <input
              v-model="searchQuery"
              type="search"
              :placeholder="t('contentLibrary.searchPlaceholder')"
              class="search-input"
              @input="debouncedSearch"
            />
          </div>
          <div class="toolbar-actions">
            <select v-model="sortBy" class="sort-select" @change="handleSortChange">
              <option value="custom">{{ t('contentLibrary.sort.custom') }}</option>
              <option value="date-desc">{{ t('contentLibrary.sort.newestFirst') }}</option>
              <option value="date-asc">{{ t('contentLibrary.sort.oldestFirst') }}</option>
              <option value="name-asc">{{ t('contentLibrary.sort.nameAZ') }}</option>
              <option value="name-desc">{{ t('contentLibrary.sort.nameZA') }}</option>
            </select>
            <button
              v-if="sortBy === 'custom' && hasOrderChanges"
              type="button"
              class="btn-save-order"
              :disabled="isSavingOrder || !isReorderSafe"
              :title="!isReorderSafe ? t(reorderDisabledReason) : ''"
              @click="saveOrder"
            >
              {{ isSavingOrder ? t('contentLibrary.saving') : t('contentLibrary.saveOrder') }}
            </button>
          </div>
        </div>

        <!-- Loading State -->
        <div v-if="isLoading" class="state-container">
          <div class="spinner"></div>
          <p>{{ t('contentLibrary.loading') }}</p>
        </div>

        <!-- Error State -->
        <div v-else-if="error" class="state-container error">
          <p class="error-message">{{ error }}</p>
          <button type="button" class="btn-retry" @click="loadContent">
            {{ t('contentLibrary.retry') }}
          </button>
        </div>

        <!-- Empty State -->
        <div v-else-if="content.length === 0" class="state-container">
          <p class="empty-message">{{ t('contentLibrary.empty') }}</p>
        </div>

        <!-- Truncation Warning Banner -->
        <template v-else>
          <div v-if="isTruncated" class="truncation-warning">
            <span class="warning-icon">⚠️</span>
            <span>{{ t('contentLibrary.truncatedWarning') }}</span>
          </div>

        <!-- Mobile/Tablet Card Grid (only render on mobile/tablet) -->
        <div v-if="!isDesktop" class="content-cards">
          <div
            v-for="item in content"
            :key="item.id"
            class="content-card"
            :class="{ selected: isSelected(item.id) }"
          >
            <div class="card-checkbox">
              <input
                type="checkbox"
                :checked="isSelected(item.id)"
                :aria-label="`Select ${item.title}`"
                @change="toggleSelectItem(item.id)"
              />
            </div>

            <div class="card-thumbnail">
              <img
                v-if="item.thumbnailUrl"
                :src="item.thumbnailUrl"
                :alt="item.title"
                @error="handleThumbnailError($event, item)"
              />
              <div v-else class="thumbnail-placeholder"></div>
              <span class="type-badge-card" :class="`type-${item.type}`">
                {{ t(`contentLibrary.types.${item.type}`) }}
              </span>
            </div>

            <div class="card-content">
              <h3 class="card-title">{{ item.title }}</h3>

              <div class="card-meta">
                <span class="status-badge" :class="`status-${item.status}`">
                  {{ t(`contentLibrary.statuses.${item.status}`) }}
                </span>
                <span class="card-date">{{ formatDate(item.createdAt) }}</span>
              </div>

              <div v-if="item.categoryIds.length > 0" class="card-categories">
                <span v-for="catId in item.categoryIds.slice(0, 3)" :key="catId" class="category-tag">
                  {{ getCategoryName(catId) }}
                </span>
                <span v-if="item.categoryIds.length > 3" class="category-more">
                  +{{ item.categoryIds.length - 3 }}
                </span>
              </div>
            </div>

            <div class="card-actions">
              <button
                type="button"
                class="card-action-btn"
                @click="openDetailsModal(item)"
              >
                <span class="action-icon">👁</span>
                <span class="action-label">{{ t('contentLibrary.view') }}</span>
              </button>
              <button
                type="button"
                class="card-action-btn"
                @click="openCategoryModal(item)"
              >
                <span class="action-icon">🏷</span>
                <span class="action-label">{{ t('contentLibrary.categories') }}</span>
              </button>
              <button
                type="button"
                class="card-action-btn"
                @click="openKeywordsModal(item)"
              >
                <span class="action-icon">🔑</span>
                <span class="action-label">{{ t('contentLibrary.keywords') }}</span>
              </button>
              <button
                type="button"
                class="card-action-btn delete"
                @click="confirmDelete(item)"
              >
                <span class="action-icon">🗑</span>
                <span class="action-label">{{ t('contentLibrary.delete') }}</span>
              </button>
            </div>
          </div>
        </div>

        <!-- Desktop Table (only render on desktop) -->
        <div v-if="isDesktop" class="content-table-wrapper">
          <table class="content-table">
            <thead>
              <tr>
                <th v-if="sortBy === 'custom'" class="col-drag"></th>
                <th class="col-checkbox">
                  <input
                    type="checkbox"
                    :checked="allSelected"
                    :indeterminate.prop="someSelected"
                    @change="toggleSelectAll"
                  />
                </th>
                <th class="col-title">{{ t('contentLibrary.columns.title') }}</th>
                <th class="col-type">{{ t('contentLibrary.columns.type') }}</th>
                <th class="col-categories">{{ t('contentLibrary.columns.categories') }}</th>
                <th class="col-status">{{ t('contentLibrary.columns.status') }}</th>
                <th class="col-date">{{ t('contentLibrary.columns.dateAdded') }}</th>
                <th class="col-actions">{{ t('contentLibrary.columns.actions') }}</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="(item, index) in content"
                :key="item.id"
                :class="{ selected: isSelected(item.id), 'drag-over': dragOverIndex === index }"
                :draggable="sortBy === 'custom'"
                @dragstart="handleDragStart($event, index)"
                @dragover="handleDragOver($event, index)"
                @dragleave="handleDragLeave"
                @drop="handleDrop($event, index)"
                @dragend="handleDragEnd"
              >
                <td v-if="sortBy === 'custom'" class="col-drag">
                  <span class="drag-handle" title="Drag to reorder">⋮⋮</span>
                </td>
                <td class="col-checkbox">
                  <input
                    type="checkbox"
                    :checked="isSelected(item.id)"
                    @change="toggleSelectItem(item.id)"
                  />
                </td>
                <td class="col-title">
                  <div class="title-cell">
                    <img
                      v-if="item.thumbnailUrl"
                      :src="item.thumbnailUrl"
                      :alt="item.title"
                      class="thumbnail"
                      @error="handleThumbnailError($event, item)"
                    />
                    <div v-else class="thumbnail-placeholder-sm"></div>
                    <span class="title-text">{{ item.title }}</span>
                  </div>
                </td>
                <td class="col-type">
                  <span class="type-badge" :class="`type-${item.type}`">
                    {{ t(`contentLibrary.types.${item.type}`) }}
                  </span>
                </td>
                <td class="col-categories">
                  <div class="category-tags">
                    <span v-for="catId in item.categoryIds.slice(0, 2)" :key="catId" class="category-tag">
                      {{ getCategoryName(catId) }}
                    </span>
                    <span v-if="item.categoryIds.length > 2" class="category-more">
                      +{{ item.categoryIds.length - 2 }}
                    </span>
                  </div>
                </td>
                <td class="col-status">
                  <span class="status-badge" :class="`status-${item.status}`">
                    {{ t(`contentLibrary.statuses.${item.status}`) }}
                  </span>
                </td>
                <td class="col-date">{{ formatDate(item.createdAt) }}</td>
                <td class="col-actions">
                  <button type="button" class="action-btn" @click="openDetailsModal(item)" :title="t('contentLibrary.view')">👁</button>
                  <button type="button" class="action-btn" @click="openCategoryModal(item)" :title="t('contentLibrary.categories')">🏷</button>
                  <button type="button" class="action-btn" @click="openKeywordsModal(item)" :title="t('contentLibrary.keywords')">🔑</button>
                  <button type="button" class="action-btn delete" @click="confirmDelete(item)" :title="t('contentLibrary.delete')">🗑</button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <!-- Infinite Scroll Loading Indicator -->
        <div v-if="isLoadingMore" class="loading-more-indicator">
          <div class="spinner"></div>
          <span>{{ t('contentLibrary.loadingMore') }}</span>
        </div>

        <!-- Load More Error -->
        <div v-if="loadMoreError" class="load-more-error">
          <p>{{ loadMoreError }}</p>
          <button type="button" class="btn-retry" @click="retryLoadMore">
            {{ t('contentLibrary.retry') }}
          </button>
        </div>

        <!-- End of Content -->
        <div v-if="!hasMoreContent && !loadMoreError && content.length > 0 && !isLoading && !paginationDisabled" class="end-of-content">
          {{ t('contentLibrary.allLoaded', { count: content.length }) }}
        </div>
        </template>
      </main>
    </div>

    <!-- Mobile/Tablet Filter Bottom Sheet -->
    <div v-if="showFilters" class="filter-bottom-sheet-overlay" @click="showFilters = false">
      <div class="filter-bottom-sheet" @click.stop>
        <div class="sheet-header">
          <h2>{{ t('contentLibrary.filters.title') }}</h2>
          <button type="button" class="sheet-close" @click="showFilters = false">×</button>
        </div>

        <div class="sheet-content">
          <div class="filter-section-mobile">
            <h3 class="filter-heading-mobile">{{ t('contentLibrary.filters.contentType') }}</h3>
            <div class="filter-chips">
              <label v-for="type in contentTypes" :key="type.value" class="filter-chip">
                <input
                  type="checkbox"
                  :value="type.value"
                  :checked="filters.types.includes(type.value)"
                  @change="toggleTypeFilter(type.value)"
                />
                <span>{{ type.label }}</span>
              </label>
            </div>
          </div>

          <div class="filter-section-mobile">
            <h3 class="filter-heading-mobile">{{ t('contentLibrary.filters.status') }}</h3>
            <div class="filter-chips">
              <label v-for="status in statuses" :key="status.value" class="filter-chip">
                <input
                  type="radio"
                  name="status-mobile"
                  :value="status.value"
                  :checked="filters.status === status.value"
                  @change="filters.status = status.value; loadContent()"
                />
                <span>{{ status.label }}</span>
              </label>
            </div>
          </div>

          <div class="filter-section-mobile">
            <h3 class="filter-heading-mobile">{{ t('contentLibrary.filters.dateAdded') }}</h3>
            <select v-model="filters.dateAdded" class="filter-select-mobile" @change="loadContent">
              <option value="any">{{ t('contentLibrary.filters.anyDate') }}</option>
              <option value="today">{{ t('contentLibrary.filters.today') }}</option>
              <option value="week">{{ t('contentLibrary.filters.thisWeek') }}</option>
              <option value="month">{{ t('contentLibrary.filters.thisMonth') }}</option>
            </select>
          </div>
        </div>

        <div class="sheet-footer">
          <button type="button" class="btn-sheet-reset" @click="resetFilters">
            {{ t('contentLibrary.filters.resetAll') }}
          </button>
          <button type="button" class="btn-sheet-apply" @click="showFilters = false">
            {{ t('contentLibrary.filters.apply') }}
          </button>
        </div>
      </div>
    </div>

    <!-- Bulk Actions Menu -->
    <div v-if="showBulkMenu" class="bulk-menu-overlay" @click="closeBulkActionsMenu">
      <div class="bulk-menu" @click.stop>
        <h3>{{ t('contentLibrary.bulkMenu.title') }}</h3>
        <button type="button" class="bulk-menu-item" @click="bulkChangeStatus('approved')">
          {{ t('contentLibrary.bulkMenu.approve') }}
        </button>
        <button type="button" class="bulk-menu-item" @click="bulkChangeStatus('pending')">
          {{ t('contentLibrary.bulkMenu.markPending') }}
        </button>
        <button type="button" class="bulk-menu-item" @click="openBulkCategoryAssignment">
          {{ t('contentLibrary.bulkMenu.assignCategories') }}
        </button>
        <button type="button" class="bulk-menu-item danger" @click="bulkDelete">
          {{ t('contentLibrary.bulkMenu.delete') }}
        </button>
        <button type="button" class="bulk-menu-cancel" @click="closeBulkActionsMenu">
          {{ t('contentLibrary.bulkMenu.cancel') }}
        </button>
      </div>
    </div>

    <!-- Exclusion Detail Modals -->
    <ChannelDetailModal
      v-if="selectedItemForModal && selectedItemForModal.type === 'channel'"
      :open="channelModalOpen"
      :channel-id="selectedItemForModal.id"
      :channel-youtube-id="selectedItemForModal.youtubeId || selectedItemForModal.id"
      @close="channelModalOpen = false"
      @updated="handleModalUpdated"
    />

    <PlaylistDetailModal
      v-if="selectedItemForModal && selectedItemForModal.type === 'playlist'"
      :open="playlistModalOpen"
      :playlist-id="selectedItemForModal.id"
      :playlist-youtube-id="selectedItemForModal.youtubeId || selectedItemForModal.id"
      @close="playlistModalOpen = false"
      @updated="handleModalUpdated"
    />

    <VideoPreviewModal
      v-if="selectedItemForModal && selectedItemForModal.type === 'video'"
      :open="videoModalOpen"
      :youtube-id="selectedItemForModal.youtubeId || selectedItemForModal.id"
      :title="selectedItemForModal.title"
      @close="videoModalOpen = false"
    />

    <!-- Category Assignment Modal -->
    <CategoryAssignmentModal
      v-if="categoryModalOpen"
      :is-open="categoryModalOpen"
      :multi-select="true"
      @close="closeCategoryModal"
      @assign="handleCategoriesAssigned"
    />

    <!-- Keywords Edit Modal -->
    <div v-if="keywordsModalOpen" class="keywords-modal-overlay" @click.self="closeKeywordsModal">
      <div class="keywords-modal">
        <div class="keywords-modal-header">
          <h3>{{ t('contentLibrary.editKeywords') }}</h3>
          <button type="button" class="keywords-modal-close" @click="closeKeywordsModal">×</button>
        </div>
        <div class="keywords-modal-content">
          <p class="keywords-modal-item-title">{{ keywordsEditItem?.title }}</p>
          <label class="keywords-input-label">{{ t('contentLibrary.keywordsLabel') }}</label>
          <textarea
            v-model="keywordsInput"
            class="keywords-textarea"
            :placeholder="t('contentLibrary.keywordsPlaceholder')"
            rows="4"
          ></textarea>
          <p class="keywords-help">{{ t('contentLibrary.keywordsHelp') }}</p>
        </div>
        <div class="keywords-modal-footer">
          <button type="button" class="btn-keywords-cancel" @click="closeKeywordsModal">
            {{ t('common.cancel') }}
          </button>
          <button
            type="button"
            class="btn-keywords-save"
            :disabled="isSavingKeywords"
            @click="saveKeywords"
          >
            {{ isSavingKeywords ? t('contentLibrary.saving') : t('common.save') }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue';
import { useI18n } from 'vue-i18n';
import apiClient from '@/services/api/client';
import ChannelDetailModal from '@/components/exclusions/ChannelDetailModal.vue';
import PlaylistDetailModal from '@/components/exclusions/PlaylistDetailModal.vue';
import VideoPreviewModal from '@/components/VideoPreviewModal.vue';
import CategoryAssignmentModal from '@/components/CategoryAssignmentModal.vue';
import * as contentLibraryService from '@/services/contentLibrary';
import type { ReorderItem } from '@/services/contentLibrary';

const { t } = useI18n();

interface ContentItem {
  id: string;
  title: string;
  type: 'channel' | 'playlist' | 'video';
  thumbnailUrl?: string;
  categoryIds: string[];
  status: 'approved' | 'pending' | 'rejected';
  createdAt: Date | null;
  youtubeId?: string;
  displayOrder?: number;
  keywords?: string[];
}

interface Category {
  id: string;
  name: string;
}

// State
const isLoading = ref(false);
const error = ref<string | null>(null);
const content = ref<ContentItem[]>([]);
const categories = ref<Category[]>([]);
const selectedItems = ref<string[]>([]);
const searchQuery = ref('');
const categorySearch = ref('');
const sortBy = ref('date-desc');
const showBulkMenu = ref(false);
const showFilters = ref(false);

// Drag-drop state for custom ordering
const dragIndex = ref<number | null>(null);
const dragOverIndex = ref<number | null>(null);
const hasOrderChanges = ref(false);
const isSavingOrder = ref(false);
const originalOrder = ref<string[]>([]); // Track original order to detect changes

// Truncation warning - true when server limited results to prevent excessive Firestore reads
const isTruncated = ref(false);

// Reorder safety - tracks whether the current view can safely be reordered
// Reorder is unsafe when: truncated, paginated (totalItems > displayed), search active, filters active, or non-approved items visible
const totalItemsFromServer = ref(0);
const isReorderSafe = computed(() => {
  // Cannot reorder if results are truncated (server hit safety limit)
  if (isTruncated.value) return false;

  // Cannot reorder if there are more items than displayed (pagination)
  if (totalItemsFromServer.value > content.value.length) return false;

  // Cannot reorder if search is active (affects which items are visible)
  if (searchQuery.value.trim()) return false;

  // Cannot reorder if type filters are active (affects which items are visible)
  if (filters.value.types.length > 0) return false;

  // Cannot reorder if category filter is active (affects which items are visible)
  if (filters.value.category) return false;

  // Cannot reorder if date filter is active (affects which items are visible)
  if (filters.value.dateAdded !== 'any') return false;

  // Cannot reorder if status filter is not "approved" - only approved items can be reordered
  // This matches the backend enforcement in ContentLibraryController.validateAllReorderOperations()
  if (filters.value.status !== 'approved') return false;

  // Safe to reorder - we have the complete, unfiltered set of approved items
  return true;
});

// Reason why reorder is disabled (for tooltip)
const reorderDisabledReason = computed(() => {
  if (isTruncated.value) return 'contentLibrary.reorderDisabledTruncated';
  if (totalItemsFromServer.value > content.value.length) return 'contentLibrary.reorderDisabledPaginated';
  if (searchQuery.value.trim()) return 'contentLibrary.reorderDisabledSearch';
  if (filters.value.types.length > 0) return 'contentLibrary.reorderDisabledFilters';
  if (filters.value.category) return 'contentLibrary.reorderDisabledFilters';
  if (filters.value.dateAdded !== 'any') return 'contentLibrary.reorderDisabledFilters';
  if (filters.value.status !== 'approved') return 'contentLibrary.reorderDisabledNotApproved';
  return '';
});

// Track if we're on desktop (>= 1024px) to conditionally render table vs cards
// This prevents rendering both layouts simultaneously, improving performance
const isDesktop = ref(window.innerWidth >= 1024);
function handleResize() {
  isDesktop.value = window.innerWidth >= 1024;
}

const filters = ref({
  types: [] as string[],
  status: 'all',
  category: '', // Single category filter (empty = all)
  dateAdded: 'any'
});

// Modal State
const channelModalOpen = ref(false);
const playlistModalOpen = ref(false);
const videoModalOpen = ref(false);
const selectedItemForModal = ref<ContentItem | null>(null);
const categoryModalOpen = ref(false);
const itemsForCategoryAssignment = ref<ContentItem[]>([]);

// Keywords Modal State
const keywordsModalOpen = ref(false);
const keywordsEditItem = ref<ContentItem | null>(null);
const keywordsInput = ref('');
const isSavingKeywords = ref(false);

// Filter Options
const contentTypes = computed(() => [
  { value: 'channel', label: t('contentLibrary.types.channel') },
  { value: 'playlist', label: t('contentLibrary.types.playlist') },
  { value: 'video', label: t('contentLibrary.types.video') }
]);

const statuses = computed(() => [
  { value: 'all', label: t('contentLibrary.filters.allStatuses') },
  { value: 'approved', label: t('contentLibrary.statuses.approved') },
  { value: 'pending', label: t('contentLibrary.statuses.pending') },
  { value: 'rejected', label: t('contentLibrary.statuses.rejected') }
]);

const filteredCategories = computed(() => {
  if (!categorySearch.value) return categories.value;
  const query = categorySearch.value.toLowerCase();
  return categories.value.filter(cat => cat.name.toLowerCase().includes(query));
});

const activeFilterCount = computed(() => {
  let count = 0;
  if (filters.value.types.length > 0) count++;
  if (filters.value.status !== 'all') count++;
  if (filters.value.category) count++;
  if (filters.value.dateAdded !== 'any') count++;
  return count;
});

// Selection
const allSelected = computed(() =>
  content.value.length > 0 && selectedItems.value.length === content.value.length
);

const someSelected = computed(() =>
  selectedItems.value.length > 0 && selectedItems.value.length < content.value.length
);

function isSelected(id: string): boolean {
  return selectedItems.value.includes(id);
}

function toggleSelectItem(id: string) {
  const index = selectedItems.value.indexOf(id);
  if (index > -1) {
    selectedItems.value.splice(index, 1);
  } else {
    selectedItems.value.push(id);
  }
}

function toggleSelectAll() {
  if (allSelected.value) {
    selectedItems.value = [];
  } else {
    selectedItems.value = content.value.map(item => item.id);
  }
}

function clearSelection() {
  selectedItems.value = [];
}

// Filters
function toggleTypeFilter(type: string) {
  const index = filters.value.types.indexOf(type);
  if (index > -1) {
    filters.value.types.splice(index, 1);
  } else {
    filters.value.types.push(type);
  }
  loadContent();
}

function resetFilters() {
  filters.value = {
    types: [],
    status: 'all',
    category: '',
    dateAdded: 'any'
  };
  searchQuery.value = '';
  categorySearch.value = '';
  loadContent();
}

// Sort handling - client-side for name sorts, server for date sorts
function sortContentLocally() {
  if (sortBy.value === 'custom') {
    // Sort by displayOrder (lower values first, nulls at end)
    content.value.sort((a, b) => {
      const orderA = a.displayOrder ?? Number.MAX_SAFE_INTEGER;
      const orderB = b.displayOrder ?? Number.MAX_SAFE_INTEGER;
      // Tie-breaker: use id for stable ordering when displayOrder is equal
      if (orderA === orderB) {
        return a.id.localeCompare(b.id);
      }
      return orderA - orderB;
    });
  } else if (sortBy.value === 'name-asc') {
    content.value.sort((a, b) => {
      const cmp = a.title.localeCompare(b.title);
      // Tie-breaker: use id for stable ordering
      return cmp !== 0 ? cmp : a.id.localeCompare(b.id);
    });
  } else if (sortBy.value === 'name-desc') {
    content.value.sort((a, b) => {
      const cmp = b.title.localeCompare(a.title);
      // Tie-breaker: use id for stable ordering
      return cmp !== 0 ? cmp : b.id.localeCompare(a.id);
    });
  } else if (sortBy.value === 'date-asc') {
    content.value.sort((a, b) => {
      const timeA = a.createdAt ? new Date(a.createdAt).getTime() : 0;
      const timeB = b.createdAt ? new Date(b.createdAt).getTime() : 0;
      // Tie-breaker: use id for stable ordering
      if (timeA === timeB) {
        return a.id.localeCompare(b.id);
      }
      return timeA - timeB;
    });
  } else {
    // date-desc (default)
    content.value.sort((a, b) => {
      const timeA = a.createdAt ? new Date(a.createdAt).getTime() : 0;
      const timeB = b.createdAt ? new Date(b.createdAt).getTime() : 0;
      // Tie-breaker: use id for stable ordering
      if (timeA === timeB) {
        return b.id.localeCompare(a.id);
      }
      return timeB - timeA;
    });
  }
}

// Track what sort was used for last server fetch
let lastServerSort = 'date-desc';

function handleSortChange() {
  // Custom order: sort by displayOrder (client-side)
  if (sortBy.value === 'custom') {
    sortContentLocally();
    // Store original order for change detection
    originalOrder.value = content.value.map(item => item.id);
    hasOrderChanges.value = false;
    return;
  }

  // Reset order tracking when leaving custom mode
  hasOrderChanges.value = false;
  originalOrder.value = [];

  // Name sorts are always client-side (server doesn't support them)
  if (sortBy.value === 'name-asc' || sortBy.value === 'name-desc') {
    sortContentLocally();
    return;
  }

  // Date sorts: only refetch if the server sort direction changed
  const newServerSort = sortBy.value === 'date-asc' ? 'oldest' : 'newest';
  const oldServerSort = lastServerSort === 'date-asc' ? 'oldest' : 'newest';

  if (newServerSort !== oldServerSort || content.value.length === 0) {
    loadContent();
  } else {
    // Already have data with correct server sort, just re-sort locally
    sortContentLocally();
  }
}

// Bulk Actions
function openBulkActionsMenu() {
  showBulkMenu.value = true;
}

function closeBulkActionsMenu() {
  showBulkMenu.value = false;
}

async function bulkChangeStatus(status: string) {
  try {
    const items = selectedItems.value.map(id => {
      const item = content.value.find(c => c.id === id);
      return { type: item!.type, id: item!.id };
    });

    if (status === 'approved') {
      const result = await contentLibraryService.bulkApprove(items);
      alert(`${t('contentLibrary.success')} - ${result.successCount} ${t('contentLibrary.itemsApproved')}`);
      if (result.errors.length > 0) {
        console.error('Bulk approve errors:', result.errors);
      }
    } else {
      const result = await contentLibraryService.bulkReject(items);
      alert(`${t('contentLibrary.success')} - ${result.successCount} ${t('contentLibrary.itemsRejected')}`);
      if (result.errors.length > 0) {
        console.error('Bulk reject errors:', result.errors);
      }
    }

    clearSelection();
    await loadContent();
  } catch (err: any) {
    alert(t('contentLibrary.errorBulkAction') + ': ' + (err.message || ''));
  } finally {
    closeBulkActionsMenu();
  }
}

function openBulkCategoryAssignment() {
  // Get selected items
  const items = selectedItems.value
    .map(id => content.value.find(c => c.id === id))
    .filter(item => item !== undefined) as ContentItem[];

  itemsForCategoryAssignment.value = items;
  categoryModalOpen.value = true;
  closeBulkActionsMenu();
}

async function bulkDelete() {
  if (!confirm(t('contentLibrary.confirmBulkDelete', { count: selectedItems.value.length }))) {
    return;
  }

  try {
    const items = selectedItems.value.map(id => {
      const item = content.value.find(c => c.id === id);
      return { type: item!.type, id: item!.id };
    });

    const result = await contentLibraryService.bulkDelete(items);
    alert(`${t('contentLibrary.success')} - ${result.successCount} ${t('contentLibrary.itemsDeleted')}`);

    if (result.errors.length > 0) {
      console.error('Bulk delete errors:', result.errors);
    }

    clearSelection();
    await loadContent();
  } catch (err: any) {
    alert(t('contentLibrary.errorBulkAction') + ': ' + (err.message || ''));
  } finally {
    closeBulkActionsMenu();
  }
}

// Item Actions
function openDetailsModal(item: ContentItem) {
  selectedItemForModal.value = item;

  if (item.type === 'channel') {
    channelModalOpen.value = true;
  } else if (item.type === 'playlist') {
    playlistModalOpen.value = true;
  } else if (item.type === 'video') {
    videoModalOpen.value = true;
  }
}

function handleModalUpdated() {
  // Refresh content after exclusions are modified
  loadContent();
}

function openCategoryModal(item: ContentItem) {
  itemsForCategoryAssignment.value = [item];
  categoryModalOpen.value = true;
}

async function confirmDelete(item: ContentItem) {
  if (!confirm(t('contentLibrary.confirmDelete', { title: item.title }))) {
    return;
  }

  try {
    const result = await contentLibraryService.bulkDelete([{ type: item.type, id: item.id }]);

    if (result.successCount > 0) {
      alert(t('contentLibrary.deleteSuccess'));
      await loadContent();
    } else if (result.errors.length > 0) {
      alert(t('contentLibrary.errorBulkAction') + ': ' + result.errors[0]);
    }
  } catch (err: any) {
    alert(t('contentLibrary.errorBulkAction') + ': ' + (err.message || ''));
  }
}

async function handleCategoriesAssigned(categoryIds: string[]) {
  try {
    const items = itemsForCategoryAssignment.value.map(item => ({
      type: item.type,
      id: item.id
    }));

    const result = await contentLibraryService.bulkAssignCategories(items, categoryIds);

    if (result.successCount > 0) {
      alert(`${t('contentLibrary.success')} - ${result.successCount} ${t('contentLibrary.categoriesAssigned')}`);

      if (result.errors.length > 0) {
        console.error('Category assignment errors:', result.errors);
      }

      clearSelection();
      await loadContent();
    } else if (result.errors.length > 0) {
      alert(t('contentLibrary.errorBulkAction') + ': ' + result.errors[0]);
    }
  } catch (err: any) {
    alert(t('contentLibrary.errorBulkAction') + ': ' + (err.message || ''));
  } finally {
    categoryModalOpen.value = false;
    itemsForCategoryAssignment.value = [];
  }
}

function closeCategoryModal() {
  categoryModalOpen.value = false;
  itemsForCategoryAssignment.value = [];
}

// Keywords Modal Functions
function openKeywordsModal(item: ContentItem) {
  keywordsEditItem.value = item;
  keywordsInput.value = (item.keywords || []).join(', ');
  keywordsModalOpen.value = true;
}

function closeKeywordsModal() {
  keywordsModalOpen.value = false;
  keywordsEditItem.value = null;
  keywordsInput.value = '';
}

async function saveKeywords() {
  if (!keywordsEditItem.value || isSavingKeywords.value) return;

  isSavingKeywords.value = true;

  try {
    // Parse keywords from comma-separated input
    const keywords = keywordsInput.value
      .split(',')
      .map(k => k.trim())
      .filter(k => k.length > 0);

    // Call API to update keywords
    await apiClient.put(
      `/api/admin/content/${keywordsEditItem.value.type}/${keywordsEditItem.value.id}/keywords`,
      { keywords }
    );

    // Update local state
    const item = content.value.find(c => c.id === keywordsEditItem.value!.id);
    if (item) {
      item.keywords = keywords;
    }

    alert(t('contentLibrary.keywordsSaved'));
    closeKeywordsModal();
  } catch (err: any) {
    console.error('Failed to save keywords:', err);
    alert(t('contentLibrary.errorSavingKeywords') + ': ' + (err.message || ''));
  } finally {
    isSavingKeywords.value = false;
  }
}

// Data Loading
// Max items for custom sort mode to enable reordering.
// Must not exceed backend's page size cap (100) since ContentLibraryController caps at Math.min(size, 100).
// Reorder safety is enforced by isReorderSafe computed: disabled when truncated or totalItems > displayed.
const CUSTOM_SORT_MAX_ITEMS = 100;
const PAGE_SIZE = 25;

// Infinite scroll state
const currentPage = ref(0);
const isLoadingMore = ref(false);
const hasMoreContent = ref(false);
const loadMoreError = ref<string | null>(null);

// Name sorts and custom sort need all items loaded at once (no infinite scroll)
// because client-side sorting produces incorrect visual order with incremental page loads
const paginationDisabled = computed(() =>
  sortBy.value === 'custom' || sortBy.value === 'name-asc' || sortBy.value === 'name-desc'
);

// Request versioning to prevent stale responses from overlapping requests
let requestVersion = 0;

function mapContentItem(item: any): ContentItem {
  return {
    id: item.id,
    title: item.title,
    type: item.type,
    thumbnailUrl: item.thumbnailUrl,
    categoryIds: item.categoryIds || [],
    status: item.status?.toLowerCase() || 'pending',
    createdAt: item.createdAt ? new Date(item.createdAt) : null,
    youtubeId: item.youtubeId,
    displayOrder: item.displayOrder ?? undefined,
    keywords: item.keywords || []
  };
}

function buildContentParams(page: number): Record<string, any> {
  const pageSize = paginationDisabled.value ? CUSTOM_SORT_MAX_ITEMS : PAGE_SIZE;

  const params: Record<string, any> = {
    status: filters.value.status,
    page,
    size: pageSize
  };

  if (filters.value.types.length > 0) {
    params.types = filters.value.types.join(',');
  }

  if (filters.value.category) {
    params.category = filters.value.category;
  }

  if (searchQuery.value.trim()) {
    params.search = searchQuery.value.trim();
  }

  if (sortBy.value === 'date-asc') {
    params.sort = 'oldest';
  } else {
    params.sort = 'newest';
  }

  return params;
}

async function loadContent() {
  const myVersion = ++requestVersion;
  isLoading.value = true;
  error.value = null;
  loadMoreError.value = null;
  currentPage.value = 0;
  hasMoreContent.value = false;

  try {
    const params = buildContentParams(0);

    const response = await apiClient.get('/api/admin/content', { params });

    // Discard stale response if filters/search changed while request was in flight
    if (myVersion !== requestVersion) return;

    content.value = response.data.content.map(mapContentItem);

    // Track what sort we used for this fetch
    lastServerSort = sortBy.value === 'date-asc' ? 'date-asc' : 'date-desc';

    // Update truncation state from server response
    isTruncated.value = response.data.truncated === true;

    // Track total items for reorder safety check
    totalItemsFromServer.value = response.data.totalItems ?? content.value.length;

    // Determine if there are more pages to load (disabled for client-side sorts)
    if (!paginationDisabled.value) {
      hasMoreContent.value = content.value.length < totalItemsFromServer.value;
    }

    // Warn if custom sort mode has incomplete results
    if (sortBy.value === 'custom' && !isReorderSafe.value) {
      console.warn(`Custom sort: reorder disabled. Reason: ${reorderDisabledReason.value}. ` +
        `Showing ${content.value.length} of ${totalItemsFromServer.value} items.`);
    }

    // Apply client-side sort if needed (for name sorts, or to ensure consistency)
    sortContentLocally();

  } catch (err: any) {
    // Discard stale error if context changed
    if (myVersion !== requestVersion) return;
    console.error('Failed to load content:', err);
    error.value = err.response?.data?.message || err.message || t('contentLibrary.error');
  } finally {
    if (myVersion === requestVersion) {
      isLoading.value = false;
    }
  }
}

async function loadMoreContent() {
  if (isLoadingMore.value || !hasMoreContent.value || paginationDisabled.value) return;

  const myVersion = requestVersion; // Capture current version (don't increment — loadContent owns that)
  isLoadingMore.value = true;
  loadMoreError.value = null;
  const nextPage = currentPage.value + 1;

  try {
    const params = buildContentParams(nextPage);
    const response = await apiClient.get('/api/admin/content', { params });

    // Discard stale response if a new loadContent() was triggered while this was in flight
    if (myVersion !== requestVersion) return;

    const newItems = response.data.content.map(mapContentItem);

    content.value.push(...newItems);
    currentPage.value = nextPage;

    totalItemsFromServer.value = response.data.totalItems ?? totalItemsFromServer.value;
    hasMoreContent.value = content.value.length < totalItemsFromServer.value;
  } catch (err: any) {
    if (myVersion !== requestVersion) return;
    console.error('Failed to load more content:', err);
    loadMoreError.value = err.response?.data?.message || err.message || t('contentLibrary.error');
  } finally {
    if (myVersion === requestVersion) {
      isLoadingMore.value = false;
    }
  }
}

function retryLoadMore() {
  loadMoreError.value = null;
  loadMoreContent();
}

// Window scroll handler for infinite scroll
function handleWindowScroll() {
  if (isLoadingMore.value || !hasMoreContent.value || paginationDisabled.value) return;

  const scrollBottom = window.innerHeight + window.scrollY;
  const docHeight = document.documentElement.scrollHeight;

  if (docHeight - scrollBottom < 300) {
    loadMoreContent();
  }
}

async function loadCategories() {
  try {
    const response = await apiClient.get('/api/admin/categories');
    categories.value = response.data.map((cat: any) => ({
      id: cat.id,
      name: cat.name
    }));
  } catch (err) {
    console.error('Failed to load categories:', err);
  }
}

// Debounced search
let searchTimeout: ReturnType<typeof setTimeout>;
function debouncedSearch() {
  clearTimeout(searchTimeout);
  searchTimeout = setTimeout(() => {
    loadContent();
  }, 300);
}

// Utilities
function getCategoryName(catId: string): string {
  const cat = categories.value.find(c => c.id === catId);
  return cat ? cat.name : catId;
}

function formatDate(date: Date | null | undefined): string {
  if (!date) {
    return '—';
  }
  const d = new Date(date);
  if (isNaN(d.getTime())) {
    return '—';
  }
  return new Intl.DateTimeFormat('en-US', {
    year: 'numeric',
    month: 'short',
    day: 'numeric'
  }).format(d);
}

// Handle thumbnail load errors by hiding the image and showing placeholder
function handleThumbnailError(event: Event, item: ContentItem) {
  const img = event.target as HTMLImageElement;
  img.style.display = 'none';
  // Set thumbnailUrl to empty to show placeholder
  item.thumbnailUrl = '';
}

// Drag-drop handlers for custom ordering
function handleDragStart(event: DragEvent, index: number) {
  if (sortBy.value !== 'custom') return;
  dragIndex.value = index;
  if (event.dataTransfer) {
    event.dataTransfer.effectAllowed = 'move';
    event.dataTransfer.setData('text/plain', index.toString());
  }
  // Add visual feedback - use currentTarget (the row with the listener) not target (may be child element)
  const row = event.currentTarget as HTMLElement;
  row.classList.add('dragging');
}

function handleDragOver(event: DragEvent, index: number) {
  if (sortBy.value !== 'custom' || dragIndex.value === null) return;
  event.preventDefault();
  if (event.dataTransfer) {
    event.dataTransfer.dropEffect = 'move';
  }
  dragOverIndex.value = index;
}

function handleDragLeave() {
  dragOverIndex.value = null;
}

function handleDrop(event: DragEvent, targetIndex: number) {
  if (sortBy.value !== 'custom' || dragIndex.value === null) return;
  event.preventDefault();

  const sourceIndex = dragIndex.value;
  if (sourceIndex !== targetIndex) {
    // Reorder the content array
    const item = content.value.splice(sourceIndex, 1)[0];
    // When moving downward, adjust target index since splice shifted subsequent items
    const adjustedTarget = sourceIndex < targetIndex ? targetIndex - 1 : targetIndex;
    content.value.splice(adjustedTarget, 0, item);

    // Update displayOrder values to match new positions
    content.value.forEach((item, index) => {
      item.displayOrder = index;
    });

    // Check if order has changed from original
    const currentOrder = content.value.map(item => item.id);
    hasOrderChanges.value = JSON.stringify(currentOrder) !== JSON.stringify(originalOrder.value);
  }

  // Reset drag state
  dragIndex.value = null;
  dragOverIndex.value = null;
}

function handleDragEnd(event: DragEvent) {
  // Remove visual feedback - use currentTarget (the row with the listener) not target (may be child element)
  const row = event.currentTarget as HTMLElement;
  row.classList.remove('dragging');
  dragIndex.value = null;
  dragOverIndex.value = null;
}

// Save custom order to backend
async function saveOrder() {
  // Block reorder when view is not safe for reordering
  // (truncated, paginated, filtered, or search active)
  if (!hasOrderChanges.value || isSavingOrder.value || !isReorderSafe.value) return;

  isSavingOrder.value = true;

  try {
    // Build reorder items from current content positions
    const items: ReorderItem[] = content.value.map((item, index) => ({
      type: item.type,
      id: item.id,
      displayOrder: index
    }));

    const result = await contentLibraryService.bulkReorder(items);

    if (result.errors && result.errors.length > 0) {
      // Partial or complete failure - reload to get actual server state
      console.error('Reorder errors:', result.errors);

      if (result.successCount > 0) {
        alert(`${t('contentLibrary.partialReorderError')} - ${result.successCount} ${t('contentLibrary.itemsReordered')}`);
      } else {
        alert(t('contentLibrary.reorderFailed'));
      }

      await loadContent();
      // Reset order tracking after reload
      originalOrder.value = content.value.map(item => item.id);
      hasOrderChanges.value = false;
    } else if (result.successCount > 0) {
      // Full success
      alert(`${t('contentLibrary.orderSaved')} - ${result.successCount} ${t('contentLibrary.itemsReordered')}`);
      originalOrder.value = content.value.map(item => item.id);
      hasOrderChanges.value = false;
    }
  } catch (err: any) {
    console.error('Failed to save order:', err);
    alert(t('contentLibrary.errorSavingOrder') + ': ' + (err.message || ''));
  } finally {
    isSavingOrder.value = false;
  }
}

onMounted(() => {
  loadCategories();
  loadContent();
  window.addEventListener('resize', handleResize);
  window.addEventListener('scroll', handleWindowScroll, { passive: true });
});

onUnmounted(() => {
  window.removeEventListener('resize', handleResize);
  window.removeEventListener('scroll', handleWindowScroll);
  // Clear debounced search timeout to prevent memory leak
  if (searchTimeout) {
    clearTimeout(searchTimeout);
  }
});
</script>

<style scoped>
.content-library {
  display: flex;
  flex-direction: column;
  gap: 1.5rem;
  min-height: 100%;
}

.header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 1rem;
  flex-wrap: wrap;
}

.header-content {
  flex: 1;
  min-width: 200px;
}

.heading {
  font-size: 2rem;
  font-weight: 700;
  color: var(--color-text-primary);
  margin: 0 0 0.5rem 0;
}

.subtitle {
  font-size: 0.9375rem;
  color: var(--color-text-secondary);
  margin: 0;
}

.header-actions {
  display: flex;
  gap: 0.75rem;
  align-items: center;
  flex-wrap: wrap;
}

/* Mobile Filter Button */
.btn-filter-mobile {
  display: none;
  align-items: center;
  gap: 0.5rem;
  padding: 0.75rem 1rem;
  background: var(--color-surface);
  border: 1.5px solid var(--color-border);
  border-radius: 0.5rem;
  color: var(--color-text-primary);
  font-size: 0.9375rem;
  font-weight: 600;
  cursor: pointer;
  position: relative;
  transition: all 0.2s ease;
  -webkit-tap-highlight-color: transparent;
}

.btn-filter-mobile:active {
  transform: scale(0.98);
  background: var(--color-background);
}

.filter-icon {
  font-size: 1.125rem;
}

.filter-badge {
  position: absolute;
  top: -6px;
  right: -6px;
  background: var(--color-brand);
  color: white;
  width: 20px;
  height: 20px;
  border-radius: 50%;
  font-size: 0.75rem;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
}

.mobile-hidden {
  display: inline;
}

.desktop-hidden {
  display: none;
}

.content-container {
  display: grid;
  grid-template-columns: 260px 1fr;
  gap: 2rem;
  flex: 1;
}

/* Desktop Sidebar Filters */
.filters-sidebar {
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: 0.75rem;
  padding: 1.5rem;
  height: fit-content;
  position: sticky;
  top: 2rem;
  display: flex;
  flex-direction: column;
  gap: 1.5rem;
}

.filter-section {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}

.filter-heading {
  font-size: 0.875rem;
  font-weight: 600;
  color: var(--color-text-primary);
  margin: 0;
  text-transform: uppercase;
  letter-spacing: 0.025em;
}

.filter-option {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  font-size: 0.9375rem;
  color: var(--color-text-primary);
  cursor: pointer;
}

.filter-search-input,
.filter-select {
  width: 100%;
  padding: 0.5rem;
  border: 1px solid var(--color-border);
  border-radius: 0.375rem;
  font-size: 0.875rem;
  font-family: inherit;
}

.category-list {
  max-height: 200px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.filter-actions {
  padding-top: 0.5rem;
  border-top: 1px solid var(--color-border);
}

.btn-reset {
  width: 100%;
  padding: 0.625rem;
  background: transparent;
  border: 1px solid var(--color-border);
  border-radius: 0.375rem;
  color: var(--color-text-secondary);
  font-size: 0.875rem;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s ease;
}

.btn-reset:hover {
  background: var(--color-background);
  border-color: var(--color-brand);
  color: var(--color-brand);
}

/* Main Content */
.content-main {
  display: flex;
  flex-direction: column;
  gap: 1.5rem;
  min-height: 400px;
}

.content-toolbar {
  display: flex;
  gap: 1rem;
  align-items: center;
  flex-wrap: wrap;
}

.search-box {
  flex: 1;
  min-width: 200px;
}

.search-input {
  width: 100%;
  padding: 0.625rem 1rem;
  border: 1px solid var(--color-border);
  border-radius: 0.5rem;
  font-size: 0.9375rem;
  font-family: inherit;
}

.sort-select {
  padding: 0.625rem 1rem;
  border: 1px solid var(--color-border);
  border-radius: 0.5rem;
  font-size: 0.9375rem;
  font-family: inherit;
  background: var(--color-surface);
  cursor: pointer;
}

/* States */
.state-container {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 1rem;
  padding: 4rem 2rem;
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: 0.75rem;
}

.loading-more-indicator {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 0.75rem;
  padding: 1.5rem;
  color: var(--color-text-secondary);
  font-size: 0.875rem;
}

.load-more-error {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 1.5rem;
  gap: 0.75rem;
  color: var(--color-danger, #ef4444);
  font-size: 0.875rem;
}

.load-more-error p {
  margin: 0;
}

.end-of-content {
  text-align: center;
  padding: 1.5rem;
  color: var(--color-text-secondary);
  font-size: 0.875rem;
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

/* Truncation Warning */
.truncation-warning {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.75rem 1rem;
  margin-bottom: 1rem;
  background: var(--color-warning-bg, #fef3cd);
  border: 1px solid var(--color-warning-border, #ffc107);
  border-radius: 0.5rem;
  color: var(--color-warning-text, #856404);
  font-size: 0.875rem;
}

.truncation-warning .warning-icon {
  flex-shrink: 0;
}

/* Mobile Card Grid */
.content-cards {
  display: none;
  grid-template-columns: 1fr;
  gap: 1rem;
}

.content-card {
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: 0.75rem;
  overflow: hidden;
  transition: all 0.2s ease;
  position: relative;
}

.content-card.selected {
  border-color: var(--color-brand);
  box-shadow: 0 0 0 2px rgba(22, 131, 90, 0.1);
}

.card-checkbox {
  position: absolute;
  top: 0.75rem;
  left: 0.75rem;
  z-index: 10;
}

.card-checkbox input[type="checkbox"] {
  width: 24px;
  height: 24px;
  cursor: pointer;
}

.card-thumbnail {
  position: relative;
  width: 100%;
  height: 180px;
  background: var(--color-background);
  overflow: hidden;
}

.card-thumbnail img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.thumbnail-placeholder {
  width: 100%;
  height: 100%;
  background: linear-gradient(135deg, #e5e7eb 0%, #f3f4f6 100%);
}

.type-badge-card {
  position: absolute;
  bottom: 0.75rem;
  right: 0.75rem;
  padding: 0.375rem 0.75rem;
  border-radius: 0.375rem;
  font-size: 0.75rem;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 0.025em;
  backdrop-filter: blur(8px);
}

.card-content {
  padding: 1rem;
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}

.card-title {
  font-size: 1rem;
  font-weight: 600;
  color: var(--color-text-primary);
  margin: 0;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.card-meta {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  flex-wrap: wrap;
}

.card-date {
  font-size: 0.875rem;
  color: var(--color-text-secondary);
}

.card-categories {
  display: flex;
  gap: 0.5rem;
  flex-wrap: wrap;
}

.card-actions {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  border-top: 1px solid var(--color-border);
}

.card-action-btn {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 0.25rem;
  padding: 0.875rem 0.5rem;
  border: none;
  background: transparent;
  color: var(--color-text-secondary);
  font-size: 0.75rem;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s ease;
  border-right: 1px solid var(--color-border);
  -webkit-tap-highlight-color: transparent;
  min-height: 64px;
}

.card-action-btn:last-child {
  border-right: none;
}

.card-action-btn:active {
  background: var(--color-background);
}

.card-action-btn.delete:active {
  background: rgba(220, 38, 38, 0.1);
  color: var(--color-danger);
}

.action-icon {
  font-size: 1.25rem;
}

.action-label {
  text-align: center;
}

/* Desktop Table */
.content-table-wrapper {
  display: block;
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: 0.75rem;
  overflow: hidden;
}

.content-table {
  width: 100%;
  border-collapse: collapse;
}

.content-table thead {
  background: var(--color-background);
}

.content-table th {
  padding: 0.875rem 1rem;
  text-align: left;
  font-size: 0.8125rem;
  font-weight: 600;
  color: var(--color-text-secondary);
  text-transform: uppercase;
  letter-spacing: 0.025em;
  border-bottom: 1px solid var(--color-border);
}

.content-table td {
  padding: 1rem;
  border-top: 1px solid var(--color-border);
  font-size: 0.9375rem;
}

.content-table tr.selected {
  background: rgba(22, 131, 90, 0.05);
}

.title-cell {
  display: flex;
  align-items: center;
  gap: 0.75rem;
}

.thumbnail {
  width: 48px;
  height: 36px;
  object-fit: cover;
  border-radius: 0.25rem;
}

.thumbnail-placeholder-sm {
  width: 48px;
  height: 36px;
  background: var(--color-border);
  border-radius: 0.25rem;
}

.type-badge,
.status-badge {
  display: inline-block;
  padding: 0.25rem 0.625rem;
  border-radius: 0.375rem;
  font-size: 0.75rem;
  font-weight: 600;
  text-transform: uppercase;
}

.type-channel { background: #e0f2fe; color: #0369a1; }
.type-playlist { background: #fef3c7; color: #92400e; }
.type-video { background: #e0e7ff; color: #4338ca; }

.status-approved { background: #d1fae5; color: #065f46; }
.status-pending { background: #fed7aa; color: #92400e; }
.status-rejected { background: #fee2e2; color: #991b1b; }

.category-tags {
  display: flex;
  gap: 0.375rem;
  flex-wrap: wrap;
}

.category-tag {
  padding: 0.25rem 0.5rem;
  background: var(--color-background);
  border: 1px solid var(--color-border);
  border-radius: 0.25rem;
  font-size: 0.75rem;
  color: var(--color-text-secondary);
}

.category-more {
  padding: 0.25rem 0.5rem;
  background: var(--color-brand);
  color: white;
  border-radius: 0.25rem;
  font-size: 0.75rem;
  font-weight: 600;
}

.action-btn {
  padding: 0.375rem 0.5rem;
  border: none;
  background: transparent;
  cursor: pointer;
  font-size: 1.125rem;
  opacity: 0.6;
  transition: opacity 0.2s ease;
}

.action-btn:hover {
  opacity: 1;
}

/* Mobile Filter Bottom Sheet */
.filter-bottom-sheet-overlay {
  display: none;
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.5);
  z-index: 1000;
  animation: fadeIn 0.2s ease;
}

.filter-bottom-sheet {
  position: absolute;
  bottom: 0;
  left: 0;
  right: 0;
  background: var(--color-surface);
  border-radius: 1rem 1rem 0 0;
  max-height: 85vh;
  display: flex;
  flex-direction: column;
  animation: slideUp 0.3s ease;
}

@keyframes slideUp {
  from {
    transform: translateY(100%);
  }
  to {
    transform: translateY(0);
  }
}

.sheet-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 1.25rem 1.5rem;
  border-bottom: 1px solid var(--color-border);
}

.sheet-header h2 {
  font-size: 1.25rem;
  font-weight: 700;
  margin: 0;
}

.sheet-close {
  background: transparent;
  border: none;
  font-size: 2rem;
  line-height: 1;
  cursor: pointer;
  padding: 0;
  width: 32px;
  height: 32px;
  color: var(--color-text-secondary);
  -webkit-tap-highlight-color: transparent;
}

.sheet-content {
  flex: 1;
  overflow-y: auto;
  padding: 1.5rem;
  display: flex;
  flex-direction: column;
  gap: 1.5rem;
}

.filter-section-mobile {
  display: flex;
  flex-direction: column;
  gap: 1rem;
}

.filter-heading-mobile {
  font-size: 0.875rem;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.05em;
  color: var(--color-text-secondary);
  margin: 0;
}

.filter-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 0.75rem;
}

.filter-chip {
  position: relative;
}

.filter-chip input {
  position: absolute;
  opacity: 0;
  pointer-events: none;
}

.filter-chip span {
  display: block;
  padding: 0.75rem 1.25rem;
  background: var(--color-background);
  border: 2px solid var(--color-border);
  border-radius: 2rem;
  font-size: 0.9375rem;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s ease;
  -webkit-tap-highlight-color: transparent;
  user-select: none;
}

.filter-chip input:checked + span {
  background: var(--color-brand);
  border-color: var(--color-brand);
  color: white;
  font-weight: 600;
}

.filter-chip:active span {
  transform: scale(0.98);
}

.filter-select-mobile {
  width: 100%;
  padding: 0.875rem 1rem;
  border: 2px solid var(--color-border);
  border-radius: 0.5rem;
  font-size: 1rem;
  font-family: inherit;
  background: var(--color-surface);
  cursor: pointer;
  -webkit-tap-highlight-color: transparent;
}

.sheet-footer {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0.75rem;
  padding: 1.5rem;
  border-top: 1px solid var(--color-border);
}

.btn-sheet-reset,
.btn-sheet-apply {
  padding: 1rem;
  border: none;
  border-radius: 0.5rem;
  font-size: 1rem;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s ease;
  -webkit-tap-highlight-color: transparent;
  min-height: 48px;
}

.btn-sheet-reset {
  background: transparent;
  border: 2px solid var(--color-border);
  color: var(--color-text-primary);
}

.btn-sheet-reset:active {
  background: var(--color-background);
}

.btn-sheet-apply {
  background: var(--color-brand);
  color: white;
}

.btn-sheet-apply:active {
  background: var(--color-accent);
  transform: scale(0.98);
}

/* Bulk Menu */
.bulk-menu-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}

.bulk-menu {
  background: var(--color-surface);
  border-radius: 0.75rem;
  padding: 1.5rem;
  width: min(400px, 90vw);
  box-shadow: 0 20px 50px rgba(0, 0, 0, 0.3);
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}

.bulk-menu h3 {
  margin: 0 0 0.5rem 0;
  font-size: 1.125rem;
}

.bulk-menu-item {
  padding: 0.875rem 1rem;
  border: 1px solid var(--color-border);
  border-radius: 0.5rem;
  background: transparent;
  color: var(--color-text-primary);
  font-size: 0.9375rem;
  font-weight: 500;
  cursor: pointer;
  text-align: left;
  transition: all 0.2s ease;
  -webkit-tap-highlight-color: transparent;
  min-height: 48px;
}

.bulk-menu-item:active {
  background: var(--color-background);
}

.bulk-menu-item.danger {
  color: var(--color-danger);
  border-color: var(--color-danger);
}

.bulk-menu-item.danger:active {
  background: var(--color-danger);
  color: white;
}

.bulk-menu-cancel {
  padding: 0.75rem;
  border: none;
  background: transparent;
  color: var(--color-text-secondary);
  font-size: 0.875rem;
  cursor: pointer;
  margin-top: 0.5rem;
  -webkit-tap-highlight-color: transparent;
}

.btn-secondary,
.btn-bulk,
.btn-retry {
  padding: 0.625rem 1.25rem;
  border: 1px solid var(--color-border);
  border-radius: 0.5rem;
  background: var(--color-surface);
  color: var(--color-text-primary);
  font-size: 0.875rem;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s ease;
  white-space: nowrap;
}

/* Tablet Layout (768px - 1023px) */
@media (max-width: 1023px) {
  .content-container {
    grid-template-columns: 1fr;
  }

  .filters-sidebar {
    display: none;
  }

  .btn-filter-mobile {
    display: flex;
  }

  .filter-bottom-sheet-overlay {
    display: flex;
  }

  .content-cards {
    display: grid;
  }

  .content-table-wrapper {
    display: none;
  }
}

/* Mobile Layout (< 768px) */
@media (max-width: 767px) {
  .heading {
    font-size: 1.5rem;
  }

  .subtitle {
    font-size: 0.875rem;
  }

  .header-actions {
    width: 100%;
    flex-wrap: wrap;
  }

  .btn-filter-mobile {
    flex: 1;
    min-width: 0;
    white-space: nowrap;
  }

  .mobile-hidden {
    display: none;
  }

  .desktop-hidden {
    display: inline;
  }

  .btn-secondary,
  .btn-bulk {
    flex: 1;
    min-width: 0;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .content-toolbar {
    flex-direction: column;
  }

  .search-box,
  .sort-select {
    width: 100%;
  }

  .card-actions {
    grid-template-columns: 1fr;
  }

  .card-action-btn {
    flex-direction: row;
    justify-content: flex-start;
    border-right: none;
    border-bottom: 1px solid var(--color-border);
  }

  .card-action-btn:last-child {
    border-bottom: none;
  }

  .action-icon {
    font-size: 1.5rem;
  }

  .action-label {
    text-align: left;
    font-size: 0.875rem;
  }
}

/* Touch Device Improvements */
@media (hover: none) {
  .btn-filter-mobile,
  .card-action-btn,
  .bulk-menu-item,
  .filter-chip span {
    min-height: 48px;
  }
}

/* Drag-drop styles for custom ordering */
.col-drag {
  width: 32px;
  text-align: center;
}

.drag-handle {
  cursor: grab;
  color: var(--color-text-secondary);
  font-size: 1rem;
  user-select: none;
  padding: 0.5rem;
  display: inline-block;
}

.drag-handle:hover {
  color: var(--color-brand);
}

.content-table tr[draggable="true"] {
  cursor: grab;
}

.content-table tr[draggable="true"]:active {
  cursor: grabbing;
}

.content-table tr.dragging {
  opacity: 0.5;
  background: var(--color-background);
}

.content-table tr.drag-over {
  border-top: 2px solid var(--color-brand);
}

.content-table tr.drag-over td {
  border-top: none;
}

.btn-save-order {
  padding: 0.625rem 1.25rem;
  background: var(--color-brand);
  color: white;
  border: none;
  border-radius: 0.5rem;
  font-size: 0.875rem;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s ease;
  white-space: nowrap;
}

.btn-save-order:hover:not(:disabled) {
  background: var(--color-accent);
}

.btn-save-order:disabled {
  opacity: 0.7;
  cursor: not-allowed;
}

.toolbar-actions {
  display: flex;
  gap: 0.75rem;
  align-items: center;
}

/* Keywords Modal */
.keywords-modal-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}

.keywords-modal {
  background: var(--color-surface);
  border-radius: 0.75rem;
  width: min(500px, 90vw);
  box-shadow: 0 20px 50px rgba(0, 0, 0, 0.3);
  display: flex;
  flex-direction: column;
}

.keywords-modal-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 1.25rem 1.5rem;
  border-bottom: 1px solid var(--color-border);
}

.keywords-modal-header h3 {
  margin: 0;
  font-size: 1.125rem;
  font-weight: 600;
}

.keywords-modal-close {
  background: transparent;
  border: none;
  font-size: 1.75rem;
  line-height: 1;
  cursor: pointer;
  padding: 0;
  color: var(--color-text-secondary);
}

.keywords-modal-content {
  padding: 1.5rem;
  display: flex;
  flex-direction: column;
  gap: 1rem;
}

.keywords-modal-item-title {
  font-weight: 600;
  color: var(--color-text-primary);
  margin: 0;
  font-size: 0.9375rem;
}

.keywords-input-label {
  font-size: 0.875rem;
  font-weight: 500;
  color: var(--color-text-secondary);
}

.keywords-textarea {
  width: 100%;
  padding: 0.75rem;
  border: 1px solid var(--color-border);
  border-radius: 0.5rem;
  font-size: 0.9375rem;
  font-family: inherit;
  resize: vertical;
  min-height: 100px;
}

.keywords-textarea:focus {
  outline: none;
  border-color: var(--color-brand);
}

.keywords-help {
  font-size: 0.8125rem;
  color: var(--color-text-secondary);
  margin: 0;
}

.keywords-modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 0.75rem;
  padding: 1rem 1.5rem;
  border-top: 1px solid var(--color-border);
}

.btn-keywords-cancel,
.btn-keywords-save {
  padding: 0.625rem 1.25rem;
  border-radius: 0.5rem;
  font-size: 0.875rem;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s ease;
}

.btn-keywords-cancel {
  background: transparent;
  border: 1px solid var(--color-border);
  color: var(--color-text-primary);
}

.btn-keywords-cancel:hover {
  background: var(--color-background);
}

.btn-keywords-save {
  background: var(--color-brand);
  border: none;
  color: white;
}

.btn-keywords-save:hover:not(:disabled) {
  background: var(--color-accent);
}

.btn-keywords-save:disabled {
  opacity: 0.7;
  cursor: not-allowed;
}
</style>
