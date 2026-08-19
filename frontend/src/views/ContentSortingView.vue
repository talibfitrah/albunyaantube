<template>
  <div class="content-sorting">
    <div class="page-header">
      <h1>{{ t('contentSorting.title') }}</h1>
      <p class="subtitle">{{ t('contentSorting.subtitle') }}</p>
    </div>

    <!-- Loading state -->
    <div v-if="isLoading" class="loading-state">
      <p>{{ t('contentSorting.loading') }}</p>
    </div>

    <!-- Error state -->
    <div v-else-if="error" class="error-state">
      <p>{{ error }}</p>
      <button class="btn btn-primary" @click="loadCategories">{{ t('contentSorting.retry') }}</button>
    </div>

    <!-- Categories list -->
    <div v-else class="categories-list">
      <table class="sort-table">
        <thead>
          <tr>
            <th class="col-drag"></th>
            <th class="col-name">{{ t('contentSorting.categoryName') }}</th>
            <th class="col-position">{{ t('contentSorting.position') }}</th>
            <th class="col-count">{{ t('contentSorting.items') }}</th>
            <th class="col-actions"></th>
          </tr>
        </thead>
        <tbody>
          <template v-for="cat in displayCats" :key="cat.id">
            <!-- Category row -->
            <tr
              class="category-row"
              :class="{
                'expanded': expandedCategoryId === cat.id,
                'subcategory-row': cat.isSubcategory,
                'drag-over': !cat.isSubcategory && catDragOverId === cat.id
              }"
              :draggable="!cat.isSubcategory"
              @dragstart="!cat.isSubcategory && handleCatDragStart($event, cat.id)"
              @dragover.prevent="!cat.isSubcategory && handleCatDragOver($event, cat.id)"
              @dragleave="handleCatDragLeave"
              @drop="!cat.isSubcategory && handleCatDrop($event, cat.id)"
              @dragend="handleCatDragEnd"
            >
              <td class="col-drag">
                <span v-if="!cat.isSubcategory" class="drag-handle" :title="t('contentSorting.dragToReorder')">&#x22EE;&#x22EE;</span>
              </td>
              <td class="col-name" @click="toggleCategory(cat.id)">
                <span class="expand-icon">{{ expandedCategoryId === cat.id ? '&#9660;' : '&#9654;' }}</span>
                <span v-if="cat.icon" class="category-icon">{{ cat.icon }}</span>
                <span class="category-name">{{ cat.name }}</span>
              </td>
              <td class="col-position">
                <input
                  type="number"
                  class="position-input"
                  :value="cat.displayOrder"
                  min="0"
                  @keydown.enter="handleCatPositionChange(cat.id, $event)"
                  @blur="handleCatPositionChange(cat.id, $event)"
                />
              </td>
              <td class="col-count">{{ cat.contentCount }}</td>
              <td class="col-actions">
                <button class="btn btn-sm" @click="toggleCategory(cat.id)">
                  {{ expandedCategoryId === cat.id ? t('contentSorting.collapse') : t('contentSorting.expand') }}
                </button>
              </td>
            </tr>

            <!-- Expanded content items -->
            <tr v-if="expandedCategoryId === cat.id" class="content-row">
              <td colspan="5" class="content-cell">
                <!-- Content toolbar: type filter + add button -->
                <div class="content-toolbar">
                  <div class="type-filters">
                    <button
                      v-for="filter in typeFilters"
                      :key="filter.value"
                      class="filter-btn"
                      :class="{ active: contentTypeFilter === filter.value }"
                      @click="contentTypeFilter = filter.value"
                    >
                      {{ filter.label }}
                      <span v-if="filter.value === 'all'" class="filter-count">{{ contentItems.length }}</span>
                      <span v-else class="filter-count">{{ contentItems.filter(i => i.contentType === filter.value).length }}</span>
                    </button>
                  </div>
                  <button class="btn btn-primary btn-sm" @click="openAddContentModal(cat.id)">
                    + {{ t('contentSorting.addContent') }}
                  </button>
                </div>

                <div v-if="contentLoading" class="content-loading">
                  <p>{{ t('contentSorting.loadingContent') }}</p>
                </div>
                <div v-else-if="contentError" class="content-error">
                  <p>{{ contentError }}</p>
                </div>
                <div v-else-if="filteredContentItems.length === 0" class="content-empty">
                  <p>{{ contentTypeFilter === 'all' ? t('contentSorting.noContent') : t('contentSorting.noFilterResults', { type: activeFilterLabel }) }}</p>
                </div>
                <template v-else>
                  <!-- Bulk action bar -->
                  <div v-if="selectedContentKeys.size > 0" class="bulk-action-bar">
                    <span class="bulk-count">{{ t('contentSorting.selectedCount', { count: selectedContentKeys.size }) }}</span>
                    <div class="bulk-actions">
                      <button class="btn btn-sm" @click="clearSelection">{{ t('contentSorting.clearSelection') }}</button>
                      <button class="btn btn-sm btn-danger" :disabled="bulkDeleting" @click="handleDeleteSelected">
                        {{ bulkDeleting ? t('contentSorting.deleting') : t('contentSorting.deleteSelected') }}
                      </button>
                    </div>
                  </div>
                  <table class="content-table">
                  <thead>
                    <tr>
                      <th class="col-check">
                        <input
                          type="checkbox"
                          :checked="allFilteredSelected"
                          :indeterminate="someFilteredSelected && !allFilteredSelected"
                          @change="toggleSelectAll"
                        />
                      </th>
                      <th class="col-drag"></th>
                      <th class="col-thumb"></th>
                      <th class="col-content-name">{{ t('contentSorting.contentTitle') }}</th>
                      <th class="col-content-type">{{ t('contentSorting.type') }}</th>
                      <th class="col-position">{{ t('contentSorting.position') }}</th>
                      <th class="col-content-actions"></th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr
                      v-for="(item, cIndex) in filteredContentItems"
                      :key="`${item.contentType}-${item.contentId}`"
                      :class="{
                        'drag-over': contentDragOverIndex === cIndex,
                        'row-selected': selectedContentKeys.has(contentKey(item))
                      }"
                      :draggable="contentTypeFilter === 'all'"
                      @dragstart="contentTypeFilter === 'all' && handleContentDragStart($event, cIndex)"
                      @dragover.prevent="contentTypeFilter === 'all' && handleContentDragOver($event, cIndex)"
                      @dragleave="handleContentDragLeave"
                      @drop="contentTypeFilter === 'all' && handleContentDrop($event, cIndex)"
                      @dragend="handleContentDragEnd"
                    >
                      <td class="col-check">
                        <input
                          type="checkbox"
                          :checked="selectedContentKeys.has(contentKey(item))"
                          @change="toggleContentSelection(item)"
                        />
                      </td>
                      <td class="col-drag">
                        <span v-if="contentTypeFilter === 'all'" class="drag-handle" :title="t('contentSorting.dragToReorder')">&#x22EE;&#x22EE;</span>
                      </td>
                      <td class="col-thumb">
                        <img
                          v-if="getThumbnailUrl(item, item.contentType?.toLowerCase() as 'channel' | 'playlist' | 'video')"
                          :src="getThumbnailUrl(item, item.contentType?.toLowerCase() as 'channel' | 'playlist' | 'video')!"
                          :alt="item.title"
                          class="thumbnail"
                          @error="handleThumbnailError($event, item)"
                        />
                        <div class="thumbnail-placeholder" :style="getThumbnailUrl(item, item.contentType?.toLowerCase() as 'channel' | 'playlist' | 'video') ? 'display:none' : ''"></div>
                      </td>
                      <td class="col-content-name">{{ item.title }}</td>
                      <td class="col-content-type">
                        <span class="type-badge" :class="'type-' + item.contentType">{{ item.contentType }}</span>
                      </td>
                      <td class="col-position">
                        <input
                          v-if="contentTypeFilter === 'all'"
                          type="number"
                          class="position-input"
                          :value="item.position"
                          min="0"
                          @keydown.enter="handleContentPositionChange(item, $event)"
                          @blur="handleContentPositionChange(item, $event)"
                        />
                        <span v-else class="position-label">{{ item.position }}</span>
                      </td>
                      <td class="col-content-actions">
                        <button
                          class="btn-icon btn-remove"
                          :title="t('contentSorting.removeContent')"
                          @click="handleRemoveContent(item)"
                        >
                          &times;
                        </button>
                      </td>
                    </tr>
                  </tbody>
                </table>
                </template>
              </td>
            </tr>
          </template>
        </tbody>
      </table>

      <div v-if="categories.length === 0" class="empty-state">
        <p>{{ t('contentSorting.noCategories') }}</p>
      </div>
    </div>

    <!-- Add Content Modal -->
    <teleport to="body">
      <transition name="modal">
        <div v-if="addModalOpen" class="modal-overlay" @click.self="closeAddContentModal">
          <div ref="addModalContainerRef" class="modal-container" role="dialog" aria-modal="true" :aria-label="t('contentSorting.addContentTitle')">
            <div class="modal-header">
              <h2>{{ t('contentSorting.addContentTitle') }}</h2>
              <button @click="closeAddContentModal" class="btn-close" :aria-label="t('contentSorting.close')">&times;</button>
            </div>

            <!-- Search + type filter -->
            <div class="modal-filters">
              <input
                v-model="addModalSearch"
                type="text"
                class="search-input"
                :placeholder="t('contentSorting.addContentSearch')"
              />
              <div class="modal-type-filters">
                <button
                  v-for="filter in typeFilters"
                  :key="filter.value"
                  class="filter-btn"
                  :class="{ active: addModalTypeFilter === filter.value }"
                  @click="addModalTypeFilter = filter.value"
                >
                  {{ filter.label }}
                </button>
              </div>
            </div>

            <div class="modal-body">
              <div v-if="addModalTruncated" class="truncation-warning">
                <p>{{ t('contentSorting.addContentTruncated', { count: addModalAvailable.length }) }}</p>
              </div>
              <div v-if="addModalMatches.length > addModalFiltered.length" class="truncation-warning">
                <p>{{ t('contentSorting.addContentClipped', { shown: addModalFiltered.length, total: addModalMatches.length }) }}</p>
              </div>
              <div v-if="addModalLoading" class="loading-state" role="status" aria-live="polite">
                <span class="spinner" aria-hidden="true"></span>
                <p>{{ t('contentSorting.addContentLoading') }}</p>
              </div>
              <div v-else-if="addModalFiltered.length === 0" class="empty-state">
                <p>{{ addModalAvailable.length === 0 ? t('contentSorting.addContentEmpty') : t('contentSorting.addContentNoResults') }}</p>
              </div>
              <div v-else class="add-content-list">
                <div
                  v-for="item in addModalFiltered"
                  :key="`${item.type}-${item.id}`"
                  class="add-content-item"
                  :class="{ selected: addModalSelected.has(contentKey(item)) }"
                  @click="toggleAddSelection(item)"
                >
                  <input
                    type="checkbox"
                    :checked="addModalSelected.has(contentKey(item))"
                    @click.stop
                    @change="toggleAddSelection(item)"
                  />
                  <img
                    v-if="getThumbnailUrl(item, item.type?.toLowerCase() as 'channel' | 'playlist' | 'video')"
                    :src="getThumbnailUrl(item, item.type?.toLowerCase() as 'channel' | 'playlist' | 'video')!"
                    :alt="item.title"
                    class="thumbnail"
                    @error="handleThumbnailError($event, item)"
                  />
                  <div class="thumbnail-placeholder" :style="getThumbnailUrl(item, item.type?.toLowerCase() as 'channel' | 'playlist' | 'video') ? 'display:none' : ''"></div>
                  <div class="add-content-info">
                    <span class="add-content-title">{{ item.title }}</span>
                    <span class="type-badge" :class="'type-' + item.type">{{ item.type }}</span>
                  </div>
                </div>
              </div>
            </div>

            <div class="modal-footer">
              <span v-if="addModalSelected.size > 0" class="selection-count">
                {{ t('contentSorting.addContentSelected', { count: addModalSelected.size }) }}
              </span>
              <div class="footer-actions">
                <button class="btn" @click="closeAddContentModal">{{ t('contentSorting.cancel') }}</button>
                <button
                  class="btn btn-primary"
                  :disabled="addModalSelected.size === 0 || addModalSubmitting"
                  @click="confirmAddContent"
                >
                  {{ t('contentSorting.addContentConfirm') }}
                </button>
              </div>
            </div>
          </div>
        </div>
      </transition>
    </teleport>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, nextTick, onMounted } from 'vue';
import { useI18n } from 'vue-i18n';
import { getThumbnailUrl, getThumbnailFallbacks } from '@/utils/formatters';
import {
  getCategorySortOrder,
  reorderCategory,
  getCategoryContentOrder,
  reorderContentInCategory,
  addContentToCategorySort,
  getApprovedContent,
  removeContentFromCategorySort,
  buildDisplayCategories,
  type CategorySortItem,
  type ContentSortItem,
  type ApprovedContentItem,
  type DisplayCategory
} from '@/services/sortOrder';
import { toast } from '@/utils/toast';
import { useFocusTrap } from '@/composables/useFocusTrap';
import type { Ref } from 'vue';

const { t } = useI18n();

function contentKey(item: { contentType?: string; type?: string; contentId?: string; id?: string }): string {
  return `${item.contentType ?? item.type}:${item.contentId ?? item.id}`;
}

function parseContentKey(key: string): { contentType: string; contentId: string } {
  const i = key.indexOf(':');
  return { contentType: key.substring(0, i), contentId: key.substring(i + 1) };
}

function toggleInSet(setRef: Ref<Set<string>>, key: string) {
  const next = new Set(setRef.value);
  next.has(key) ? next.delete(key) : next.add(key);
  setRef.value = next;
}

// ==================== Type filters ====================

const typeFilters = computed(() => [
  { value: 'all', label: t('contentSorting.filterAll') },
  { value: 'channel', label: t('contentSorting.filterChannels') },
  { value: 'playlist', label: t('contentSorting.filterPlaylists') },
  { value: 'video', label: t('contentSorting.filterVideos') }
]);

// ==================== Category state ====================

const categories = ref<CategorySortItem[]>([]);
const displayCats = computed<DisplayCategory[]>(() => buildDisplayCategories(categories.value));
const isLoading = ref(false);
const error = ref<string | null>(null);

// Expanded category state
const expandedCategoryId = ref<string | null>(null);
const contentItems = ref<ContentSortItem[]>([]);
const contentLoading = ref(false);
const contentError = ref<string | null>(null);
const contentTypeFilter = ref('all');

const filteredContentItems = computed(() => {
  if (contentTypeFilter.value === 'all') return contentItems.value;
  return contentItems.value.filter(i => i.contentType === contentTypeFilter.value);
});

const activeFilterLabel = computed(() => {
  const f = typeFilters.value.find(tf => tf.value === contentTypeFilter.value);
  return f?.label ?? contentTypeFilter.value;
});

// Category drag state
const catDragOverId = ref<string | null>(null);
let catDragStartId: string | null = null;

// Content drag state
const contentDragOverIndex = ref<number | null>(null);
let contentDragStartIndex: number | null = null;

// Request token to prevent stale responses from overwriting newer ones
let contentLoadToken = 0;

// ==================== Thumbnail fallback state ====================

const thumbnailFallbackIndex = new Map<string, number>();

function handleThumbnailError(event: Event, item: any) {
  const img = event.target as HTMLImageElement;
  const id = item.id || item.contentId;
  const idx = thumbnailFallbackIndex.get(id) ?? 0;
  const type = (item.type || item.contentType || '').toLowerCase();
  const fallbacks = getThumbnailFallbacks(item, type as 'channel' | 'playlist' | 'video');
  const currentSrc = img.src.replace(/\/$/, '');
  let nextIdx = idx;
  while (nextIdx < fallbacks.length && fallbacks[nextIdx].replace(/\/$/, '') === currentSrc) {
    nextIdx++;
  }
  if (nextIdx < fallbacks.length) {
    thumbnailFallbackIndex.set(id, nextIdx + 1);
    img.src = fallbacks[nextIdx];
    return;
  }
  // All fallbacks exhausted — hide img and show placeholder via DOM
  // (avoids reactive Set cascade that re-renders all list items)
  img.style.display = 'none';
  const placeholder = img.nextElementSibling;
  if (placeholder && placeholder.classList.contains('thumbnail-placeholder')) {
    (placeholder as HTMLElement).style.display = '';
  }
}

// ==================== Add Content Modal state ====================

const addModalOpen = ref(false);
const addModalLoading = ref(false);
const addModalSubmitting = ref(false);
const addModalSearch = ref('');
const addModalTypeFilter = ref('all');
const addModalAvailable = ref<ApprovedContentItem[]>([]);
const addModalSelected = ref<Set<string>>(new Set());
let addModalCategoryId = '';
let addModalLoadToken = 0;
const addModalTruncated = ref(false);
const addModalContainerRef = ref<HTMLElement | null>(null);

const { activate: activateFocusTrap, deactivate: deactivateFocusTrap } = useFocusTrap(addModalContainerRef, {
  onEscape: () => closeAddContentModal()
});

/** Rows drawn at once in the add-content modal. Beyond this, the reviewer searches. */
const ADD_MODAL_RENDER_LIMIT = 300;

/** Everything matching the current filters, before the render cap. */
const addModalMatches = computed(() => {
  let items = addModalAvailable.value;

  if (addModalTypeFilter.value !== 'all') {
    items = items.filter(i => i.type === addModalTypeFilter.value);
  }

  if (addModalSearch.value.trim()) {
    const q = addModalSearch.value.toLowerCase();
    items = items.filter(i => i.title.toLowerCase().includes(q));
  }

  return items;
});

/**
 * What is actually drawn. The list is a plain v-for, and the picker now fetches the whole
 * approved registry in one request rather than the first twenty pages of it, so rendering every
 * row of a large library would put thousands of nodes in the modal. The clip is stated in the UI
 * — a silent one would read as content missing from the registry.
 */
const addModalFiltered = computed(() => addModalMatches.value.slice(0, ADD_MODAL_RENDER_LIMIT));

// ==================== Data loading ====================

async function loadCategories() {
  isLoading.value = true;
  error.value = null;
  try {
    categories.value = await getCategorySortOrder();
  } catch (e: any) {
    error.value = e.message || 'Failed to load categories';
  } finally {
    isLoading.value = false;
  }
}

async function toggleCategory(categoryId: string) {
  if (expandedCategoryId.value === categoryId) {
    expandedCategoryId.value = null;
    contentItems.value = [];
    contentTypeFilter.value = 'all';
    selectedContentKeys.value = new Set();
    return;
  }

  expandedCategoryId.value = categoryId;
  contentLoading.value = true;
  contentError.value = null;
  contentItems.value = [];
  contentTypeFilter.value = 'all';
  selectedContentKeys.value = new Set();

  const token = ++contentLoadToken;
  try {
    const items = await getCategoryContentOrder(categoryId);
    if (token !== contentLoadToken) return;
    contentItems.value = items;
  } catch (e: any) {
    if (token !== contentLoadToken) return;
    contentError.value = e.message || 'Failed to load content';
  } finally {
    if (token === contentLoadToken) {
      contentLoading.value = false;
    }
  }
}

// Track last-submitted positions to prevent duplicate Enter+blur API calls
let lastCatSubmit: { id: string; pos: number } | null = null;

async function handleCatPositionChange(categoryId: string, event: Event) {
  const input = event.target as HTMLInputElement;
  const newPosition = parseInt(input.value, 10);
  if (isNaN(newPosition) || newPosition < 0) return;

  const cat = categories.value.find(c => c.id === categoryId);
  if (!cat || cat.displayOrder === newPosition) return;

  if (lastCatSubmit && lastCatSubmit.id === categoryId && lastCatSubmit.pos === newPosition) return;
  lastCatSubmit = { id: categoryId, pos: newPosition };

  try {
    categories.value = await reorderCategory(categoryId, newPosition);
    toast.success(t('contentSorting.categoryReordered'));
  } catch (e: any) {
    toast.error(e.message || 'Failed to reorder category');
    await loadCategories();
  } finally {
    lastCatSubmit = null;
  }
}

// ==================== Category drag-and-drop ====================

function handleCatDragStart(e: DragEvent, categoryId: string) {
  catDragStartId = categoryId;
  if (e.dataTransfer) {
    e.dataTransfer.effectAllowed = 'move';
    e.dataTransfer.setData('text/plain', categoryId);
  }
}

function handleCatDragOver(e: DragEvent, categoryId: string) {
  e.preventDefault();
  catDragOverId.value = categoryId;
}

function handleCatDragLeave() {
  catDragOverId.value = null;
}

async function handleCatDrop(e: DragEvent, dropCategoryId: string) {
  e.preventDefault();
  catDragOverId.value = null;

  if (!catDragStartId || catDragStartId === dropCategoryId) return;

  const mainCats = displayCats.value.filter(c => !c.isSubcategory);
  const targetCat = mainCats.find(c => c.id === dropCategoryId);
  if (!targetCat) return;

  const draggedId = catDragStartId;
  catDragStartId = null;

  try {
    categories.value = await reorderCategory(draggedId, targetCat.displayOrder ?? 0);
    toast.success(t('contentSorting.categoryReordered'));
  } catch (e: any) {
    toast.error(e.message || 'Failed to reorder category');
    await loadCategories();
  }
}

function handleCatDragEnd() {
  catDragOverId.value = null;
  catDragStartId = null;
}

// ==================== Content drag-and-drop ====================

function handleContentDragStart(e: DragEvent, index: number) {
  contentDragStartIndex = index;
  if (e.dataTransfer) {
    e.dataTransfer.effectAllowed = 'move';
    e.dataTransfer.setData('text/plain', String(index));
  }
}

function handleContentDragOver(e: DragEvent, index: number) {
  e.preventDefault();
  contentDragOverIndex.value = index;
}

function handleContentDragLeave() {
  contentDragOverIndex.value = null;
}

async function handleContentDrop(e: DragEvent, dropIndex: number) {
  e.preventDefault();
  contentDragOverIndex.value = null;

  if (contentDragStartIndex === null || contentDragStartIndex === dropIndex) return;
  if (!expandedCategoryId.value) return;

  const item = filteredContentItems.value[contentDragStartIndex];
  if (!item) return;

  try {
    contentItems.value = await reorderContentInCategory(
      expandedCategoryId.value,
      item.contentId,
      item.contentType,
      dropIndex
    );
    toast.success(t('contentSorting.contentReordered'));
  } catch (e: any) {
    toast.error(e.message || 'Failed to reorder content');
    if (expandedCategoryId.value) {
      contentItems.value = await getCategoryContentOrder(expandedCategoryId.value);
    }
  }

  contentDragStartIndex = null;
}

function handleContentDragEnd() {
  contentDragOverIndex.value = null;
  contentDragStartIndex = null;
}

// Track last-submitted content position to prevent duplicate Enter+blur API calls
let lastContentSubmit: { id: string; type: string; pos: number } | null = null;

async function handleContentPositionChange(item: ContentSortItem, event: Event) {
  const input = event.target as HTMLInputElement;
  const newPosition = parseInt(input.value, 10);
  if (isNaN(newPosition) || newPosition < 0) return;
  if (!expandedCategoryId.value || item.position === newPosition) return;

  if (lastContentSubmit && lastContentSubmit.id === item.contentId
      && lastContentSubmit.type === item.contentType
      && lastContentSubmit.pos === newPosition) return;
  lastContentSubmit = { id: item.contentId, type: item.contentType, pos: newPosition };

  try {
    contentItems.value = await reorderContentInCategory(
      expandedCategoryId.value,
      item.contentId,
      item.contentType,
      newPosition
    );
    toast.success(t('contentSorting.contentReordered'));
  } catch (e: any) {
    toast.error(e.message || 'Failed to reorder content');
    if (expandedCategoryId.value) {
      contentItems.value = await getCategoryContentOrder(expandedCategoryId.value);
    }
  } finally {
    lastContentSubmit = null;
  }
}

// ==================== Content selection ====================

const selectedContentKeys = ref<Set<string>>(new Set());
const bulkDeleting = ref(false);

const allFilteredSelected = computed(() => {
  if (filteredContentItems.value.length === 0) return false;
  return filteredContentItems.value.every(i => selectedContentKeys.value.has(contentKey(i)));
});

const someFilteredSelected = computed(() =>
  selectedContentKeys.value.size > 0 && !allFilteredSelected.value
);

function toggleContentSelection(item: ContentSortItem) {
  toggleInSet(selectedContentKeys, contentKey(item));
}

function toggleSelectAll() {
  const shouldDeselect = allFilteredSelected.value;
  const next = new Set(selectedContentKeys.value);
  for (const item of filteredContentItems.value) {
    const key = contentKey(item);
    if (shouldDeselect) next.delete(key);
    else next.add(key);
  }
  selectedContentKeys.value = next;
}

function clearSelection() {
  selectedContentKeys.value = new Set();
}

async function handleDeleteSelected() {
  if (!expandedCategoryId.value || selectedContentKeys.value.size === 0) return;

  const count = selectedContentKeys.value.size;
  if (!confirm(t('contentSorting.deleteSelectedConfirm', { count }))) return;

  bulkDeleting.value = true;
  const categoryId = expandedCategoryId.value;
  const keys = Array.from(selectedContentKeys.value);

  const results = await Promise.allSettled(
    keys.map(key => {
      const { contentType, contentId } = parseContentKey(key);
      return removeContentFromCategorySort(categoryId, contentType, contentId);
    })
  );

  const errors = results.filter(r => r.status === 'rejected').length;
  const lastSuccess = [...results].reverse().find(r => r.status === 'fulfilled') as
    | PromiseFulfilledResult<ContentSortItem[]>
    | undefined;

  if (lastSuccess) {
    contentItems.value = lastSuccess.value;
  }

  const cat = categories.value.find(c => c.id === categoryId);
  if (cat) cat.contentCount = contentItems.value.length;

  selectedContentKeys.value = new Set();
  bulkDeleting.value = false;

  if (errors > 0) {
    toast.error(t('contentSorting.deleteSelectedPartialError', { errors }));
  } else {
    toast.success(t('contentSorting.deleteSelectedSuccess', { count }));
  }
}

// ==================== Remove content ====================

async function handleRemoveContent(item: ContentSortItem) {
  if (!expandedCategoryId.value) return;
  if (!confirm(t('contentSorting.removeContentConfirm', { title: item.title }))) return;
  try {
    contentItems.value = await removeContentFromCategorySort(
      expandedCategoryId.value,
      item.contentType,
      item.contentId
    );
    // Update category content count
    const cat = categories.value.find(c => c.id === expandedCategoryId.value);
    if (cat) cat.contentCount = contentItems.value.length;
    toast.success(t('contentSorting.removeContentSuccess'));
  } catch (e: any) {
    toast.error(t('contentSorting.removeContentError'));
  }
}

// ==================== Add Content Modal ====================

async function openAddContentModal(categoryId: string) {
  addModalCategoryId = categoryId;
  addModalOpen.value = true;
  addModalSearch.value = '';
  addModalTypeFilter.value = 'all';
  addModalSelected.value = new Set();
  addModalLoading.value = true;
  addModalTruncated.value = false;

  await nextTick();
  activateFocusTrap();

  const token = ++addModalLoadToken;
  try {
    const result = await getApprovedContent();
    if (token !== addModalLoadToken) return;
    addModalTruncated.value = result.truncated;
    const existingKeys = new Set(contentItems.value.map(contentKey));
    addModalAvailable.value = result.items.filter(i => !existingKeys.has(contentKey(i)));
  } catch (e: any) {
    if (token !== addModalLoadToken) return;
    addModalAvailable.value = [];
    toast.error(t('contentSorting.addContentLoadError'));
  } finally {
    if (token === addModalLoadToken) {
      addModalLoading.value = false;
    }
  }
}

function closeAddContentModal() {
  deactivateFocusTrap();
  addModalOpen.value = false;
  addModalAvailable.value = [];
  addModalSelected.value = new Set();
  addModalTruncated.value = false;
}

function toggleAddSelection(item: ApprovedContentItem) {
  toggleInSet(addModalSelected, contentKey(item));
}

async function confirmAddContent() {
  if (addModalSelected.value.size === 0) return;
  addModalSubmitting.value = true;

  const categoryId = addModalCategoryId;
  const items = Array.from(addModalSelected.value).map(parseContentKey);

  try {
    contentItems.value = await addContentToCategorySort(categoryId, items);
    const cat = categories.value.find(c => c.id === categoryId);
    if (cat) cat.contentCount = contentItems.value.length;
    toast.success(t('contentSorting.addContentSuccess', { count: items.length }));
    closeAddContentModal();
  } catch (e: any) {
    toast.error(t('contentSorting.addContentError'));
  } finally {
    addModalSubmitting.value = false;
  }
}

onMounted(() => {
  loadCategories();
});
</script>

<style scoped>
.content-sorting {
  padding: 1.5rem;
  max-width: 1200px;
}

.page-header {
  margin-bottom: 1.5rem;
}

.page-header h1 {
  font-size: 1.5rem;
  font-weight: 600;
  margin: 0;
}

.subtitle {
  color: var(--color-text-secondary);
  margin-top: 0.25rem;
}

.sort-table,
.content-table {
  width: 100%;
  border-collapse: collapse;
  table-layout: fixed;
}

.sort-table th,
.sort-table td,
.content-table th,
.content-table td {
  padding: 0.75rem 0.5rem;
  text-align: start;
  border-bottom: 1px solid var(--color-border);
}

.sort-table thead th,
.content-table thead th {
  font-weight: 600;
  font-size: 0.75rem;
  text-transform: uppercase;
  color: var(--color-text-secondary);
}

.col-drag {
  width: 40px;
  text-align: center;
}

.col-position {
  width: 80px;
}

.col-count {
  width: 80px;
  text-align: center;
}

.col-actions {
  width: 100px;
  text-align: end;
}

.col-thumb {
  width: 60px;
}

.col-content-type {
  width: 100px;
}

.col-content-actions {
  width: 50px;
  text-align: center;
}

.drag-handle {
  cursor: grab;
  color: var(--color-text-secondary);
  font-size: 1rem;
  user-select: none;
}

.drag-handle:active {
  cursor: grabbing;
}

.category-row {
  cursor: pointer;
  transition: background-color 0.15s;
}

.category-row:hover {
  background-color: var(--color-surface-alt);
}

.category-row.expanded {
  background-color: var(--color-surface-alt);
}

.category-row.drag-over,
tr.drag-over {
  background-color: var(--color-brand-soft);
  border-top: 2px solid var(--color-brand);
}

.expand-icon {
  display: inline-block;
  width: 1rem;
  font-size: 0.625rem;
  color: var(--color-text-secondary);
}

.category-icon {
  margin-inline-end: 0.5rem;
}

.category-name {
  font-weight: 500;
}

.subcategory-row .col-name {
  padding-inline-start: 2.5rem;
}

.subcategory-row .category-name {
  font-weight: 400;
  font-size: 0.9em;
}

.position-input {
  width: 60px;
  padding: 0.25rem 0.5rem;
  border: 1px solid var(--color-border);
  border-radius: 4px;
  text-align: center;
  font-size: 0.875rem;
  background: var(--color-surface);
  color: var(--color-text-primary);
}

.position-input:focus {
  outline: 2px solid var(--color-brand);
  outline-offset: -1px;
}

.position-label {
  display: inline-block;
  width: 60px;
  text-align: center;
  font-size: 0.875rem;
  color: var(--color-text-secondary);
}

.content-cell {
  padding: 0 0 0.5rem 2.5rem;
  background-color: var(--color-surface-alt);
}

.content-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0.75rem 0;
  gap: 0.75rem;
  flex-wrap: wrap;
}

.type-filters {
  display: flex;
  gap: 0.25rem;
}

.filter-btn {
  padding: 0.25rem 0.75rem;
  border: 1px solid var(--color-border);
  border-radius: 16px;
  background: var(--color-surface);
  cursor: pointer;
  font-size: 0.75rem;
  color: var(--color-text-secondary);
  transition: all 0.15s;
}

.filter-btn:hover {
  background: var(--color-surface-alt);
}

.filter-btn.active {
  background: var(--color-brand);
  color: var(--color-text-inverse);
  border-color: var(--color-brand);
}

.filter-count {
  margin-inline-start: 0.25rem;
  opacity: 0.7;
  font-size: 0.6875rem;
}

.thumbnail {
  width: 48px;
  height: 36px;
  object-fit: cover;
  border-radius: 4px;
}

.thumbnail-placeholder {
  width: 48px;
  height: 36px;
  background-color: var(--color-border);
  border-radius: 4px;
}

.type-badge {
  display: inline-block;
  padding: 0.125rem 0.5rem;
  border-radius: 12px;
  font-size: 0.75rem;
  font-weight: 500;
  text-transform: capitalize;
}

.type-channel {
  background-color: var(--color-brand-soft);
  color: var(--color-brand);
}

.type-playlist {
  background-color: var(--color-success-soft);
  color: var(--color-success);
}

.type-video {
  background-color: var(--color-warning-soft);
  color: var(--color-warning);
}

.btn {
  display: inline-flex;
  align-items: center;
  padding: 0.5rem 1rem;
  border: 1px solid var(--color-border);
  border-radius: 6px;
  background: var(--color-surface);
  color: var(--color-text-primary);
  cursor: pointer;
  font-size: 0.875rem;
}

.btn:hover {
  background: var(--color-surface-alt);
}

.btn-primary {
  background: var(--color-brand);
  color: var(--color-text-inverse);
  border-color: var(--color-brand);
}

.btn-primary:hover {
  background: var(--color-accent);
}

.btn-primary:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.btn-sm {
  padding: 0.25rem 0.75rem;
  font-size: 0.75rem;
}

.btn-icon {
  background: none;
  border: none;
  cursor: pointer;
  font-size: 1.25rem;
  line-height: 1;
  padding: 0.125rem 0.375rem;
  border-radius: 4px;
  transition: all 0.15s;
}

.btn-remove {
  color: var(--color-text-secondary);
}

.btn-remove:hover {
  color: var(--color-danger);
  background: var(--color-danger-soft);
}

.btn-danger {
  background: var(--color-danger);
  color: #fff;
  border-color: var(--color-danger);
}

.btn-danger:hover {
  opacity: 0.9;
}

.btn-danger:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.col-check {
  width: 36px;
  text-align: center;
}

.col-check input[type="checkbox"] {
  width: 1rem;
  height: 1rem;
  cursor: pointer;
}

.row-selected {
  background-color: var(--color-brand-soft);
}

.bulk-action-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0.5rem 0.75rem;
  background: var(--color-brand-soft);
  border: 1px solid var(--color-brand);
  border-radius: 6px;
  margin-bottom: 0.5rem;
}

.bulk-count {
  font-size: 0.875rem;
  font-weight: 500;
  color: var(--color-text-primary);
}

.bulk-actions {
  display: flex;
  gap: 0.5rem;
}

.loading-state,
.empty-state,
.content-loading,
.content-empty {
  padding: 2rem;
  color: var(--color-text-secondary);
}

.loading-state {
  display: flex;
  align-items: center;
  gap: 0.75rem;
}

.loading-state p {
  margin: 0;
}

.spinner {
  width: 1.25rem;
  height: 1.25rem;
  flex: none;
  border: 2px solid var(--color-border);
  border-top-color: var(--color-brand);
  border-radius: 50%;
  animation: sort-spin 0.8s linear infinite;
}

@keyframes sort-spin {
  to { transform: rotate(360deg); }
}

/* Respect a reduced-motion preference: keep the indicator, drop the rotation. */
@media (prefers-reduced-motion: reduce) {
  .spinner {
    animation: none;
  }
}

.error-state,
.content-error {
  padding: 2rem;
  text-align: center;
  color: var(--color-danger);
}

/* ==================== Modal ==================== */

.modal-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 2000;
  padding: 1rem;
}

.modal-container {
  background: var(--color-surface);
  border-radius: 8px;
  width: 100%;
  max-width: 640px;
  max-height: 85vh;
  display: flex;
  flex-direction: column;
  box-shadow: 0 20px 25px -5px rgba(0, 0, 0, 0.1);
  overflow: hidden;
}

.modal-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 1.25rem 1.5rem;
  border-bottom: 1px solid var(--color-border);
}

.modal-header h2 {
  font-size: 1.125rem;
  font-weight: 600;
  margin: 0;
}

.btn-close {
  background: none;
  border: none;
  font-size: 1.5rem;
  color: var(--color-text-secondary);
  cursor: pointer;
  padding: 0.25rem;
  line-height: 1;
}

.btn-close:hover {
  color: var(--color-text-primary);
}

.modal-filters {
  padding: 0.75rem 1.5rem;
  border-bottom: 1px solid var(--color-border);
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.search-input {
  width: 100%;
  padding: 0.5rem 0.75rem;
  border: 1px solid var(--color-border);
  border-radius: 6px;
  font-size: 0.875rem;
  background: var(--color-bg);
  color: var(--color-text-primary);
}

.search-input:focus {
  outline: 2px solid var(--color-brand);
  outline-offset: -1px;
}

.modal-type-filters {
  display: flex;
  gap: 0.25rem;
}

.modal-body {
  flex: 1;
  overflow-y: auto;
  padding: 0;
  min-height: 200px;
}

.truncation-warning {
  padding: 0.5rem 1.5rem;
  background: var(--color-warning-soft);
  color: var(--color-warning);
  font-size: 0.8125rem;
  border-bottom: 1px solid var(--color-warning);
}

.add-content-list {
  display: flex;
  flex-direction: column;
}

.add-content-item {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  padding: 0.625rem 1.5rem;
  cursor: pointer;
  transition: background 0.1s;
  border-bottom: 1px solid var(--color-border);
}

.add-content-item:hover {
  background: var(--color-surface-alt);
}

.add-content-item.selected {
  background: var(--color-brand-soft);
}

.add-content-item input[type="checkbox"] {
  flex-shrink: 0;
  width: 1rem;
  height: 1rem;
  cursor: pointer;
}

.add-content-info {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  min-width: 0;
  flex: 1;
}

.add-content-title {
  font-size: 0.875rem;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.modal-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0.75rem 1.5rem;
  border-top: 1px solid var(--color-border);
}

.selection-count {
  font-size: 0.8125rem;
  color: var(--color-text-secondary);
}

.footer-actions {
  display: flex;
  gap: 0.5rem;
}

/* Modal animation */
.modal-enter-active,
.modal-leave-active {
  transition: opacity 0.2s;
}

.modal-enter-active .modal-container,
.modal-leave-active .modal-container {
  transition: transform 0.2s;
}

.modal-enter-from,
.modal-leave-to {
  opacity: 0;
}

.modal-enter-from .modal-container,
.modal-leave-to .modal-container {
  transform: scale(0.95);
}

/* RTL support */
[dir="rtl"] .content-cell {
  padding: 0 2.5rem 0.5rem 0;
}
</style>
