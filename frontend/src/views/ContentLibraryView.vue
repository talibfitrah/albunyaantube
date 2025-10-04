<template>
  <div class="content-library">
    <div class="header">
      <div class="header-content">
        <h1 class="heading">{{ t('contentLibrary.heading') }}</h1>
        <p class="subtitle">{{ t('contentLibrary.subtitle') }}</p>
      </div>
      <div class="header-actions">
        <button
          v-if="selectedItems.length > 0"
          type="button"
          class="btn-secondary"
          @click="clearSelection"
        >
          {{ t('contentLibrary.clearSelection') }} ({{ selectedItems.length }})
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
      <!-- Sidebar Filters -->
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
            <label v-for="cat in filteredCategories" :key="cat.id" class="filter-option">
              <input
                type="checkbox"
                :value="cat.id"
                :checked="filters.categories.includes(cat.id)"
                @change="toggleCategoryFilter(cat.id)"
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
            <select v-model="sortBy" class="sort-select" @change="loadContent">
              <option value="date-desc">{{ t('contentLibrary.sort.newestFirst') }}</option>
              <option value="date-asc">{{ t('contentLibrary.sort.oldestFirst') }}</option>
              <option value="name-asc">{{ t('contentLibrary.sort.nameAZ') }}</option>
              <option value="name-desc">{{ t('contentLibrary.sort.nameZA') }}</option>
            </select>
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

        <!-- Content Table -->
        <div v-else class="content-table-wrapper">
          <table class="content-table">
            <thead>
              <tr>
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
                v-for="item in content"
                :key="item.id"
                :class="{ selected: isSelected(item.id) }"
              >
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
                    />
                    <div v-else class="thumbnail-placeholder"></div>
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
                  <button
                    type="button"
                    class="action-btn"
                    :title="t('contentLibrary.viewDetails')"
                    @click="openDetailsModal(item)"
                  >
                    👁
                  </button>
                  <button
                    type="button"
                    class="action-btn"
                    :title="t('contentLibrary.assignCategories')"
                    @click="openCategoryModal(item)"
                  >
                    🏷
                  </button>
                  <button
                    type="button"
                    class="action-btn delete"
                    :title="t('contentLibrary.delete')"
                    @click="confirmDelete(item)"
                  >
                    🗑
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </main>
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
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useI18n } from 'vue-i18n';

const { t } = useI18n();

interface ContentItem {
  id: string;
  title: string;
  type: 'channel' | 'playlist' | 'video';
  thumbnailUrl?: string;
  categoryIds: string[];
  status: 'approved' | 'pending' | 'rejected';
  createdAt: Date;
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

const filters = ref({
  types: [] as string[],
  status: 'all',
  categories: [] as string[],
  dateAdded: 'any'
});

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

function toggleCategoryFilter(catId: string) {
  const index = filters.value.categories.indexOf(catId);
  if (index > -1) {
    filters.value.categories.splice(index, 1);
  } else {
    filters.value.categories.push(catId);
  }
  loadContent();
}

function resetFilters() {
  filters.value = {
    types: [],
    status: 'all',
    categories: [],
    dateAdded: 'any'
  };
  searchQuery.value = '';
  categorySearch.value = '';
  loadContent();
}

// Bulk Actions
function openBulkActionsMenu() {
  showBulkMenu.value = true;
}

function closeBulkActionsMenu() {
  showBulkMenu.value = false;
}

async function bulkChangeStatus(status: string) {
  // TODO: Implement bulk status change API call
  console.log('Bulk change status to:', status, selectedItems.value);
  closeBulkActionsMenu();
}

function openBulkCategoryAssignment() {
  // TODO: Open category assignment modal
  console.log('Open bulk category assignment for:', selectedItems.value);
  closeBulkActionsMenu();
}

async function bulkDelete() {
  if (!confirm(t('contentLibrary.confirmBulkDelete', { count: selectedItems.value.length }))) {
    return;
  }
  // TODO: Implement bulk delete API call
  console.log('Bulk delete:', selectedItems.value);
  closeBulkActionsMenu();
  clearSelection();
}

// Item Actions
function openDetailsModal(item: ContentItem) {
  // TODO: Open channel details modal (Sprint 4 - UI-009)
  console.log('Open details for:', item);
}

function openCategoryModal(item: ContentItem) {
  // TODO: Open category assignment modal
  console.log('Assign categories to:', item);
}

async function confirmDelete(item: ContentItem) {
  if (!confirm(t('contentLibrary.confirmDelete', { title: item.title }))) {
    return;
  }
  // TODO: Implement delete API call
  console.log('Delete item:', item);
}

// Data Loading
async function loadContent() {
  isLoading.value = true;
  error.value = null;

  try {
    // TODO: Implement actual API call with filters
    await new Promise(resolve => setTimeout(resolve, 500));
    content.value = [];
  } catch (err) {
    error.value = err instanceof Error ? err.message : t('contentLibrary.error');
  } finally {
    isLoading.value = false;
  }
}

async function loadCategories() {
  try {
    // TODO: Implement actual API call
    await new Promise(resolve => setTimeout(resolve, 300));
    categories.value = [];
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

function formatDate(date: Date): string {
  return new Intl.DateTimeFormat('en-US', {
    year: 'numeric',
    month: 'short',
    day: 'numeric'
  }).format(new Date(date));
}

onMounted(() => {
  loadCategories();
  loadContent();
});
</script>

<style scoped>
.content-library {
  display: flex;
  flex-direction: column;
  gap: 1.5rem;
  padding: 2rem;
  min-height: 100vh;
}

.header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 1rem;
}

.header-content {
  flex: 1;
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
}

.content-container {
  display: grid;
  grid-template-columns: 260px 1fr;
  gap: 2rem;
  flex: 1;
}

/* Sidebar Filters */
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

.filter-option input[type="checkbox"],
.filter-option input[type="radio"] {
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
  padding: 0.5rem;
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
}

.content-toolbar {
  display: flex;
  gap: 1rem;
  align-items: center;
}

.search-box {
  flex: 1;
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

.state-container.error {
  border-color: var(--color-danger);
}

.error-message {
  color: var(--color-danger);
  font-size: 0.9375rem;
}

.empty-message {
  color: var(--color-text-secondary);
  font-size: 0.9375rem;
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

/* Table */
.content-table-wrapper {
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
  border-bottom: 1px solid var(--color-border);
}

.content-table th {
  padding: 0.875rem 1rem;
  text-align: left;
  font-size: 0.8125rem;
  font-weight: 600;
  color: var(--color-text-secondary);
  text-transform: uppercase;
  letter-spacing: 0.025em;
}

.content-table td {
  padding: 1rem;
  border-top: 1px solid var(--color-border);
  font-size: 0.9375rem;
}

.content-table tr.selected {
  background: rgba(22, 131, 90, 0.05);
}

.col-checkbox {
  width: 40px;
  text-align: center;
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

.thumbnail-placeholder {
  width: 48px;
  height: 36px;
  background: var(--color-border);
  border-radius: 0.25rem;
}

.title-text {
  font-weight: 500;
  color: var(--color-text-primary);
}

.type-badge,
.status-badge {
  display: inline-block;
  padding: 0.25rem 0.625rem;
  border-radius: 0.375rem;
  font-size: 0.75rem;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.025em;
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
  color: var(--color-text-inverse);
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

.action-btn.delete:hover {
  opacity: 1;
  filter: brightness(0) saturate(100%) invert(27%) sepia(51%) saturate(2878%) hue-rotate(346deg);
}

/* Buttons */
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
}

.btn-secondary:hover,
.btn-bulk:hover,
.btn-retry:hover {
  background: var(--color-background);
  border-color: var(--color-brand);
  color: var(--color-brand);
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
  color: var(--color-text-primary);
}

.bulk-menu-item {
  padding: 0.75rem 1rem;
  border: 1px solid var(--color-border);
  border-radius: 0.5rem;
  background: transparent;
  color: var(--color-text-primary);
  font-size: 0.9375rem;
  font-weight: 500;
  cursor: pointer;
  text-align: left;
  transition: all 0.2s ease;
}

.bulk-menu-item:hover {
  background: var(--color-background);
  border-color: var(--color-brand);
}

.bulk-menu-item.danger {
  color: var(--color-danger);
  border-color: var(--color-danger);
}

.bulk-menu-item.danger:hover {
  background: var(--color-danger);
  color: white;
}

.bulk-menu-cancel {
  padding: 0.75rem 1rem;
  border: none;
  background: transparent;
  color: var(--color-text-secondary);
  font-size: 0.875rem;
  cursor: pointer;
  margin-top: 0.5rem;
}

.bulk-menu-cancel:hover {
  color: var(--color-text-primary);
}

@media (max-width: 1024px) {
  .content-container {
    grid-template-columns: 1fr;
  }

  .filters-sidebar {
    position: static;
  }
}
</style>
