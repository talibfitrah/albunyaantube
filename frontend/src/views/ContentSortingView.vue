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
                <div v-if="contentLoading" class="content-loading">
                  <p>{{ t('contentSorting.loadingContent') }}</p>
                </div>
                <div v-else-if="contentError" class="content-error">
                  <p>{{ contentError }}</p>
                </div>
                <div v-else-if="contentItems.length === 0" class="content-empty">
                  <p>{{ t('contentSorting.noContent') }}</p>
                </div>
                <table v-else class="content-table">
                  <thead>
                    <tr>
                      <th class="col-drag"></th>
                      <th class="col-thumb"></th>
                      <th class="col-content-name">{{ t('contentSorting.contentTitle') }}</th>
                      <th class="col-content-type">{{ t('contentSorting.type') }}</th>
                      <th class="col-position">{{ t('contentSorting.position') }}</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr
                      v-for="(item, cIndex) in contentItems"
                      :key="`${item.contentType}-${item.contentId}`"
                      :class="{ 'drag-over': contentDragOverIndex === cIndex }"
                      draggable="true"
                      @dragstart="handleContentDragStart($event, cIndex)"
                      @dragover.prevent="handleContentDragOver($event, cIndex)"
                      @dragleave="handleContentDragLeave"
                      @drop="handleContentDrop($event, cIndex)"
                      @dragend="handleContentDragEnd"
                    >
                      <td class="col-drag">
                        <span class="drag-handle" :title="t('contentSorting.dragToReorder')">&#x22EE;&#x22EE;</span>
                      </td>
                      <td class="col-thumb">
                        <img v-if="item.thumbnailUrl" :src="item.thumbnailUrl" :alt="item.title" class="thumbnail" />
                        <div v-else class="thumbnail-placeholder"></div>
                      </td>
                      <td class="col-content-name">{{ item.title }}</td>
                      <td class="col-content-type">
                        <span class="type-badge" :class="'type-' + item.contentType">{{ item.contentType }}</span>
                      </td>
                      <td class="col-position">
                        <input
                          type="number"
                          class="position-input"
                          :value="item.position"
                          min="0"
                          @keydown.enter="handleContentPositionChange(item, $event)"
                          @blur="handleContentPositionChange(item, $event)"
                        />
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
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { useI18n } from 'vue-i18n';
import {
  getCategorySortOrder,
  reorderCategory,
  getCategoryContentOrder,
  reorderContentInCategory,
  type CategorySortItem,
  type ContentSortItem
} from '@/services/sortOrder';
import { toast } from '@/utils/toast';

const { t } = useI18n();

// Category state
const categories = ref<CategorySortItem[]>([]);
const isLoading = ref(false);
const error = ref<string | null>(null);

// Expanded category state
const expandedCategoryId = ref<string | null>(null);
const contentItems = ref<ContentSortItem[]>([]);
const contentLoading = ref(false);
const contentError = ref<string | null>(null);

// Category drag state
const dragOverIndex = ref<number | null>(null);
let dragStartIndex: number | null = null;

// Content drag state
const contentDragOverIndex = ref<number | null>(null);
let contentDragStartIndex: number | null = null;

// Request token to prevent stale responses from overwriting newer ones
let contentLoadToken = 0;

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
    return;
  }

  expandedCategoryId.value = categoryId;
  contentLoading.value = true;
  contentError.value = null;
  contentItems.value = [];

  const token = ++contentLoadToken;
  try {
    const items = await getCategoryContentOrder(categoryId);
    // Only apply result if this is still the latest request
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

  // Deduplicate: Enter fires, then blur fires with same value
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

  const item = contentItems.value[contentDragStartIndex];
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

  // Deduplicate: Enter fires, then blur fires with same value
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

.content-cell {
  padding: 0 0 0.5rem 2.5rem;
  background-color: var(--content-bg, #fafafa);
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

.btn-sm {
  padding: 0.25rem 0.75rem;
  font-size: 0.75rem;
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

/* RTL support */
[dir="rtl"] .content-cell {
  padding: 0 2.5rem 0.5rem 0;
}
</style>
