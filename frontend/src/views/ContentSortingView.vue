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
          <template v-for="(cat, index) in categories" :key="cat.id">
            <!-- Category row -->
            <tr
              class="category-row"
              :class="{ 'drag-over': dragOverIndex === index, 'expanded': expandedCategoryId === cat.id }"
              draggable="true"
              @dragstart="handleCatDragStart($event, index)"
              @dragover.prevent="handleCatDragOver($event, index)"
              @dragleave="handleCatDragLeave"
              @drop="handleCatDrop($event, index)"
              @dragend="handleCatDragEnd"
            >
              <td class="col-drag">
                <span class="drag-handle" :title="t('contentSorting.dragToReorder')">&#x22EE;&#x22EE;</span>
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
                <table v-else class="content-table">
                  <thead>
                    <tr>
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
                      :class="{ 'drag-over': contentDragOverIndex === cIndex }"
                      :draggable="contentTypeFilter === 'all'"
                      @dragstart="contentTypeFilter === 'all' && handleContentDragStart($event, cIndex)"
                      @dragover.prevent="contentTypeFilter === 'all' && handleContentDragOver($event, cIndex)"
                      @dragleave="handleContentDragLeave"
                      @drop="contentTypeFilter === 'all' && handleContentDrop($event, cIndex)"
                      @dragend="handleContentDragEnd"
                    >
                      <td class="col-drag">
                        <span v-if="contentTypeFilter === 'all'" class="drag-handle" :title="t('contentSorting.dragToReorder')">&#x22EE;&#x22EE;</span>
                      </td>
                      <td class="col-thumb">
                        <img v-if="getThumbnailUrl(item, item.contentType?.toLowerCase() as 'channel' | 'playlist' | 'video')" :src="getThumbnailUrl(item, item.contentType?.toLowerCase() as 'channel' | 'playlist' | 'video')!" :alt="item.title" class="thumbnail" />
                        <div v-else class="thumbnail-placeholder"></div>
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
              <div v-if="addModalLoading" class="loading-state">
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
                  :class="{ selected: addModalSelected.has(`${item.type}:${item.id}`) }"
                  @click="toggleAddSelection(item)"
                >
                  <input
                    type="checkbox"
                    :checked="addModalSelected.has(`${item.type}:${item.id}`)"
                    @click.stop
                    @change="toggleAddSelection(item)"
                  />
                  <img v-if="getThumbnailUrl(item, item.type?.toLowerCase() as 'channel' | 'playlist' | 'video')" :src="getThumbnailUrl(item, item.type?.toLowerCase() as 'channel' | 'playlist' | 'video')!" :alt="item.title" class="thumbnail" />
                  <div v-else class="thumbnail-placeholder"></div>
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
import { getThumbnailUrl } from '@/utils/formatters';
import {
  getCategorySortOrder,
  reorderCategory,
  getCategoryContentOrder,
  reorderContentInCategory,
  addContentToCategorySort,
  getApprovedContent,
  removeContentFromCategorySort,
  type CategorySortItem,
  type ContentSortItem,
  type ApprovedContentItem
} from '@/services/sortOrder';
import { toast } from '@/utils/toast';
import { useFocusTrap } from '@/composables/useFocusTrap';

const { t } = useI18n();

// ==================== Type filters ====================

const typeFilters = computed(() => [
  { value: 'all', label: t('contentSorting.filterAll') },
  { value: 'channel', label: t('contentSorting.filterChannels') },
  { value: 'playlist', label: t('contentSorting.filterPlaylists') },
  { value: 'video', label: t('contentSorting.filterVideos') }
]);

// ==================== Category state ====================

const categories = ref<CategorySortItem[]>([]);
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
const dragOverIndex = ref<number | null>(null);
let dragStartIndex: number | null = null;

// Content drag state
const contentDragOverIndex = ref<number | null>(null);
let contentDragStartIndex: number | null = null;

// Request token to prevent stale responses from overwriting newer ones
let contentLoadToken = 0;

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

const addModalFiltered = computed(() => {
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
    return;
  }

  expandedCategoryId.value = categoryId;
  contentLoading.value = true;
  contentError.value = null;
  contentItems.value = [];
  contentTypeFilter.value = 'all';

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

// ==================== Category drag-and-drop ====================

function handleCatDragStart(e: DragEvent, index: number) {
  dragStartIndex = index;
  if (e.dataTransfer) {
    e.dataTransfer.effectAllowed = 'move';
    e.dataTransfer.setData('text/plain', String(index));
  }
}

function handleCatDragOver(e: DragEvent, index: number) {
  e.preventDefault();
  dragOverIndex.value = index;
}

function handleCatDragLeave() {
  dragOverIndex.value = null;
}

async function handleCatDrop(e: DragEvent, dropIndex: number) {
  e.preventDefault();
  dragOverIndex.value = null;

  if (dragStartIndex === null || dragStartIndex === dropIndex) return;

  const cat = categories.value[dragStartIndex];
  if (!cat) return;

  try {
    categories.value = await reorderCategory(cat.id, dropIndex);
    toast.success(t('contentSorting.categoryReordered'));
  } catch (e: any) {
    toast.error(e.message || 'Failed to reorder category');
    await loadCategories();
  }

  dragStartIndex = null;
}

function handleCatDragEnd() {
  dragOverIndex.value = null;
  dragStartIndex = null;
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
    // Filter out items already in this category
    const existingKeys = new Set(
      contentItems.value.map(i => `${i.contentType}:${i.contentId}`)
    );
    addModalAvailable.value = result.items.filter(
      i => !existingKeys.has(`${i.type}:${i.id}`)
    );
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
  const key = `${item.type}:${item.id}`;
  const newSet = new Set(addModalSelected.value);
  if (newSet.has(key)) {
    newSet.delete(key);
  } else {
    newSet.add(key);
  }
  addModalSelected.value = newSet;
}

async function confirmAddContent() {
  if (addModalSelected.value.size === 0) return;
  addModalSubmitting.value = true;

  const categoryId = addModalCategoryId;
  const items = Array.from(addModalSelected.value).map(key => {
    const colonIdx = key.indexOf(':');
    const contentType = key.substring(0, colonIdx);
    const contentId = key.substring(colonIdx + 1);
    return { contentId, contentType };
  });

  try {
    contentItems.value = await addContentToCategorySort(categoryId, items);
    // Update category content count
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
  color: var(--text-secondary, #6b7280);
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
  border-bottom: 1px solid var(--border-color, #e5e7eb);
}

.sort-table thead th,
.content-table thead th {
  font-weight: 600;
  font-size: 0.75rem;
  text-transform: uppercase;
  color: var(--text-secondary, #6b7280);
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
  color: var(--text-secondary, #9ca3af);
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
  background-color: var(--hover-bg, #f9fafb);
}

.category-row.expanded {
  background-color: var(--active-bg, #f3f4f6);
}

.category-row.drag-over,
tr.drag-over {
  background-color: var(--drag-over-bg, #dbeafe);
  border-top: 2px solid var(--primary-color, #3b82f6);
}

.expand-icon {
  display: inline-block;
  width: 1rem;
  font-size: 0.625rem;
  color: var(--text-secondary, #9ca3af);
}

.category-icon {
  margin-inline-end: 0.5rem;
}

.category-name {
  font-weight: 500;
}

.position-input {
  width: 60px;
  padding: 0.25rem 0.5rem;
  border: 1px solid var(--border-color, #d1d5db);
  border-radius: 4px;
  text-align: center;
  font-size: 0.875rem;
}

.position-input:focus {
  outline: 2px solid var(--primary-color, #3b82f6);
  outline-offset: -1px;
}

.position-label {
  display: inline-block;
  width: 60px;
  text-align: center;
  font-size: 0.875rem;
  color: var(--text-secondary, #6b7280);
}

.content-cell {
  padding: 0 0 0.5rem 2.5rem;
  background-color: var(--content-bg, #fafafa);
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
  border: 1px solid var(--border-color, #d1d5db);
  border-radius: 16px;
  background: var(--btn-bg, #fff);
  cursor: pointer;
  font-size: 0.75rem;
  color: var(--text-secondary, #6b7280);
  transition: all 0.15s;
}

.filter-btn:hover {
  background: var(--hover-bg, #f9fafb);
}

.filter-btn.active {
  background: var(--primary-color, #3b82f6);
  color: white;
  border-color: var(--primary-color, #3b82f6);
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
  background-color: var(--placeholder-bg, #e5e7eb);
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
  background-color: #dbeafe;
  color: #1d4ed8;
}

.type-playlist {
  background-color: #dcfce7;
  color: #166534;
}

.type-video {
  background-color: #fef3c7;
  color: #92400e;
}

.btn {
  display: inline-flex;
  align-items: center;
  padding: 0.5rem 1rem;
  border: 1px solid var(--border-color, #d1d5db);
  border-radius: 6px;
  background: var(--btn-bg, #fff);
  cursor: pointer;
  font-size: 0.875rem;
}

.btn:hover {
  background: var(--hover-bg, #f9fafb);
}

.btn-primary {
  background: var(--primary-color, #3b82f6);
  color: white;
  border-color: var(--primary-color, #3b82f6);
}

.btn-primary:hover {
  background: var(--primary-hover, #2563eb);
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
  color: var(--text-secondary, #9ca3af);
}

.btn-remove:hover {
  color: var(--error-color, #dc2626);
  background: #fef2f2;
}

.loading-state,
.error-state,
.empty-state,
.content-loading,
.content-error,
.content-empty {
  padding: 2rem;
  text-align: center;
  color: var(--text-secondary, #6b7280);
}

.error-state {
  color: var(--error-color, #dc2626);
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
  background: var(--color-surface, #fff);
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
  border-bottom: 1px solid var(--border-color, #e5e7eb);
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
  color: var(--text-secondary, #6b7280);
  cursor: pointer;
  padding: 0.25rem;
  line-height: 1;
}

.btn-close:hover {
  color: var(--color-text, #111);
}

.modal-filters {
  padding: 0.75rem 1.5rem;
  border-bottom: 1px solid var(--border-color, #e5e7eb);
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.search-input {
  width: 100%;
  padding: 0.5rem 0.75rem;
  border: 1px solid var(--border-color, #d1d5db);
  border-radius: 6px;
  font-size: 0.875rem;
  background: var(--color-background, #fff);
}

.search-input:focus {
  outline: 2px solid var(--primary-color, #3b82f6);
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
  background: var(--warning-bg, #fef3c7);
  color: var(--warning-text, #92400e);
  font-size: 0.8125rem;
  border-bottom: 1px solid var(--warning-border, #fcd34d);
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
  border-bottom: 1px solid var(--border-color, #f3f4f6);
}

.add-content-item:hover {
  background: var(--hover-bg, #f9fafb);
}

.add-content-item.selected {
  background: #eff6ff;
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
  border-top: 1px solid var(--border-color, #e5e7eb);
}

.selection-count {
  font-size: 0.8125rem;
  color: var(--text-secondary, #6b7280);
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
