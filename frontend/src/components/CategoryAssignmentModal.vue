<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { useFocusTrap } from '@/composables/useFocusTrap';
import { getAllCategories } from '@/services/categoryService';
import type { Category as CategoryType } from '@/services/categoryService';
import CategoryTreeNode from './CategoryTreeNode.vue';

const { t } = useI18n();

// Use the Category type from categoryService but ensure it has children array
interface Category extends CategoryType {
  children?: Category[];
}

interface Props {
  isOpen: boolean;
  selectedCategoryIds?: string[];
  multiSelect?: boolean;
}

interface Emits {
  (e: 'close'): void;
  (e: 'assign', categoryIds: string[]): void;
}

const props = withDefaults(defineProps<Props>(), {
  selectedCategoryIds: () => [],
  multiSelect: false
});

const emit = defineEmits<Emits>();

// State
const categories = ref<Category[]>([]);
const expandedNodes = ref<Set<string>>(new Set());
const selectedIds = ref<Set<string>>(new Set(props.selectedCategoryIds));
const searchQuery = ref('');
const isLoading = ref(false);

// Computed
const filteredCategories = computed(() => {
  if (!searchQuery.value) return categories.value;

  const query = searchQuery.value.toLowerCase();
  return filterCategoriesRecursive(categories.value, query);
});

const hasSelection = computed(() => selectedIds.value.size > 0);

// Methods
onMounted(() => {
  loadCategories();
  // Auto-expand to show selected categories
  props.selectedCategoryIds.forEach(id => {
    expandToCategory(id);
  });
});

async function loadCategories() {
  isLoading.value = true;
  try {
    // Fetch categories from backend API
    const fetchedCategories = await getAllCategories();

    // The API already returns hierarchical structure with subcategories
    // Map subcategories to children for consistency with component
    categories.value = fetchedCategories.map(cat => ({
      ...cat,
      name: cat.name || cat.label || 'Unnamed Category',
      children: cat.subcategories || []
    })) as Category[];
  } catch (err) {
    console.error('Failed to load categories', err);
    categories.value = [];
  } finally {
    isLoading.value = false;
  }
}

function filterCategoriesRecursive(cats: Category[], query: string): Category[] {
  const filtered: Category[] = [];

  for (const cat of cats) {
    const nameMatches = cat.name.toLowerCase().includes(query);
    const childMatches = cat.children ? filterCategoriesRecursive(cat.children, query) : [];

    if (nameMatches || childMatches.length > 0) {
      filtered.push({
        ...cat,
        children: childMatches.length > 0 ? childMatches : cat.children
      });

      // Auto-expand when filtering
      if (childMatches.length > 0) {
        expandedNodes.value.add(cat.id);
      }
    }
  }

  return filtered;
}

function expandToCategory(categoryId: string) {
  // Find parent chain and expand all
  const findParents = (cats: Category[], targetId: string, parents: string[] = []): string[] | null => {
    for (const cat of cats) {
      if (cat.id === targetId) {
        return parents;
      }
      if (cat.children) {
        const found = findParents(cat.children, targetId, [...parents, cat.id]);
        if (found) return found;
      }
    }
    return null;
  };

  const parents = findParents(categories.value, categoryId);
  if (parents) {
    parents.forEach(id => expandedNodes.value.add(id));
  }
}

function toggleExpand(categoryId: string) {
  if (expandedNodes.value.has(categoryId)) {
    expandedNodes.value.delete(categoryId);
  } else {
    expandedNodes.value.add(categoryId);
  }
}

function toggleSelect(categoryId: string) {
  if (props.multiSelect) {
    if (selectedIds.value.has(categoryId)) {
      selectedIds.value.delete(categoryId);
    } else {
      selectedIds.value.add(categoryId);
    }
  } else {
    selectedIds.value.clear();
    selectedIds.value.add(categoryId);
  }
}

function handleAssign() {
  emit('assign', Array.from(selectedIds.value));
  handleClose();
}

function handleClose() {
  selectedIds.value.clear();
  selectedIds.value = new Set(props.selectedCategoryIds);
  searchQuery.value = '';
  emit('close');
}

function clearSelection() {
  selectedIds.value.clear();
}

// Focus trap
const modalRef = ref<HTMLElement | null>(null);
const { activate, deactivate } = useFocusTrap(modalRef, {
  onEscape: handleClose,
  escapeDeactivates: true,
  returnFocus: true
});

// Activate/deactivate focus trap when modal opens/closes
watch(() => props.isOpen, (isOpen) => {
  if (isOpen) {
    activate();
  } else {
    deactivate();
  }
});
</script>

<template>
  <teleport to="body">
    <transition name="modal">
      <div v-if="isOpen" class="modal-overlay" @click.self="handleClose">
        <div ref="modalRef" class="modal-container" role="dialog" aria-modal="true" :aria-label="multiSelect ? t('categoryModal.headingMulti') : t('categoryModal.headingSingle')">
          <!-- Modal Header -->
          <div class="modal-header">
            <h2>{{ multiSelect ? t('categoryModal.headingMulti') : t('categoryModal.headingSingle') }}</h2>
            <button @click="handleClose" class="btn-close" :aria-label="t('categoryModal.close')">
              ✕
            </button>
          </div>

          <!-- Search Bar -->
          <div class="modal-search">
            <input
              v-model="searchQuery"
              type="text"
              :placeholder="t('categoryModal.searchPlaceholder')"
              class="search-input"
            />
          </div>

          <!-- Category Tree -->
          <div class="modal-body">
            <div v-if="isLoading" class="loading-state">
              <div class="spinner"></div>
              <p>{{ t('categoryModal.loading') }}</p>
            </div>

            <div v-else-if="filteredCategories.length === 0" class="empty-state">
              <p>{{ t('categoryModal.noResults') }}</p>
            </div>

            <div v-else class="category-tree">
              <CategoryTreeNode
                v-for="category in filteredCategories"
                :key="category.id"
                :category="category"
                :expanded-nodes="expandedNodes"
                :selected-ids="selectedIds"
                :multi-select="multiSelect"
                @toggle-expand="toggleExpand"
                @toggle-select="toggleSelect"
              />
            </div>
          </div>

          <!-- Modal Footer -->
          <div class="modal-footer">
            <div class="selection-info">
              <span v-if="hasSelection">
                {{ t('categoryModal.selectedCount', { count: selectedIds.size }) }}
              </span>
              <button
                v-if="hasSelection"
                @click="clearSelection"
                class="btn-link"
              >
                {{ t('categoryModal.clearSelection') }}
              </button>
            </div>
            <div class="footer-actions">
              <button @click="handleClose" class="btn-secondary">
                {{ t('categoryModal.cancel') }}
              </button>
              <button
                @click="handleAssign"
                class="btn-primary"
                :disabled="!hasSelection"
              >
                {{ t('categoryModal.assign') }}
              </button>
            </div>
          </div>
        </div>
      </div>
    </transition>
  </teleport>
</template>

<style scoped>
/* Modal Overlay */
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
  max-width: 600px;
  max-height: 80vh;
  display: flex;
  flex-direction: column;
  box-shadow: 0 20px 25px -5px rgba(0, 0, 0, 0.1), 0 10px 10px -5px rgba(0, 0, 0, 0.04);
}

/* Modal Header */
.modal-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 1.5rem;
  border-bottom: 1px solid var(--color-border);
}

.modal-header h2 {
  font-size: 1.25rem;
  font-weight: 600;
  color: var(--color-text);
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
  transition: color 0.2s;
}

.btn-close:hover {
  color: var(--color-text);
}

/* RTL: Move close button to left */
[dir='rtl'] .modal-header {
  flex-direction: row-reverse;
}

/* Search Bar */
.modal-search {
  padding: 1rem 1.5rem;
  border-bottom: 1px solid var(--color-border);
}

.search-input {
  width: 100%;
  padding: 0.625rem 1rem;
  border: 1px solid var(--color-border);
  border-radius: 6px;
  font-size: 0.9375rem;
  background: var(--color-background);
  color: var(--color-text);
}

.search-input:focus {
  outline: none;
  border-color: var(--color-primary);
}

/* Modal Body */
.modal-body {
  flex: 1;
  overflow-y: auto;
  padding: 1rem 0;
}

.loading-state,
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 3rem 1.5rem;
  color: var(--color-text-secondary);
}

.spinner {
  width: 2rem;
  height: 2rem;
  border: 3px solid var(--color-border);
  border-top-color: var(--color-primary);
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

/* Category Tree */
.category-tree {
  padding: 0 1.5rem;
}

/* Modal Footer */
.modal-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 1rem 1.5rem;
  border-top: 1px solid var(--color-border);
}

.selection-info {
  display: flex;
  align-items: center;
  gap: 1rem;
  font-size: 0.875rem;
  color: var(--color-text-secondary);
}

.btn-link {
  background: none;
  border: none;
  color: var(--color-primary);
  cursor: pointer;
  font-size: 0.875rem;
  padding: 0;
}

.btn-link:hover {
  text-decoration: underline;
}

.footer-actions {
  display: flex;
  gap: 0.75rem;
}

/* RTL: Reverse button order (primary on left) */
[dir='rtl'] .footer-actions {
  flex-direction: row-reverse;
}

.btn-primary,
.btn-secondary {
  padding: 0.625rem 1.5rem;
  border-radius: 6px;
  font-weight: 500;
  font-size: 0.9375rem;
  cursor: pointer;
  transition: all 0.2s;
  border: none;
  min-height: 44px;
}

.btn-primary {
  background: var(--color-primary);
  color: white;
}

.btn-primary:hover:not(:disabled) {
  background: var(--color-primary-hover);
}

.btn-primary:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.btn-secondary {
  background: transparent;
  color: var(--color-text);
  border: 1px solid var(--color-border);
}

.btn-secondary:hover {
  background: var(--color-surface-variant);
}

/* Modal Animation */
.modal-enter-active,
.modal-leave-active {
  transition: opacity 0.3s;
}

.modal-enter-active .modal-container,
.modal-leave-active .modal-container {
  transition: transform 0.3s;
}

.modal-enter-from,
.modal-leave-to {
  opacity: 0;
}

.modal-enter-from .modal-container,
.modal-leave-to .modal-container {
  transform: scale(0.9);
}

/* Mobile Responsiveness */
@media (max-width: 640px) {
  .modal-container {
    max-height: 90vh;
  }

  .modal-footer {
    flex-direction: column;
    gap: 1rem;
    align-items: stretch;
  }

  .selection-info {
    order: 2;
    justify-content: center;
  }

  .footer-actions {
    order: 1;
    flex-direction: column-reverse;
  }

  .footer-actions button {
    width: 100%;
  }
}
</style>
