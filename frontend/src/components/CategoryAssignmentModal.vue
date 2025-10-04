<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useI18n } from 'vue-i18n';

const { t } = useI18n();

interface Category {
  id: string;
  name: string;
  slug: string;
  parentId: string | null;
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
    // Mock data - replace with actual API call
    await new Promise(resolve => setTimeout(resolve, 300));
    categories.value = [
      {
        id: '1',
        name: 'Islamic Studies',
        slug: 'islamic-studies',
        parentId: null,
        children: [
          {
            id: '1-1',
            name: 'Quran',
            slug: 'quran',
            parentId: '1',
            children: [
              { id: '1-1-1', name: 'Recitation', slug: 'recitation', parentId: '1-1' },
              { id: '1-1-2', name: 'Tafsir', slug: 'tafsir', parentId: '1-1' }
            ]
          },
          {
            id: '1-2',
            name: 'Hadith',
            slug: 'hadith',
            parentId: '1'
          }
        ]
      },
      {
        id: '2',
        name: 'Islamic History',
        slug: 'islamic-history',
        parentId: null,
        children: [
          { id: '2-1', name: 'Early Islam', slug: 'early-islam', parentId: '2' },
          { id: '2-2', name: 'Ottoman Empire', slug: 'ottoman-empire', parentId: '2' }
        ]
      },
      {
        id: '3',
        name: 'Fiqh & Jurisprudence',
        slug: 'fiqh',
        parentId: null
      }
    ];
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
</script>

<template>
  <teleport to="body">
    <transition name="modal">
      <div v-if="isOpen" class="modal-overlay" @click.self="handleClose">
        <div class="modal-container">
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

<!-- CategoryTreeNode Component (inline) -->
<script setup lang="ts" generic="T">
interface CategoryTreeNodeProps {
  category: Category;
  expandedNodes: Set<string>;
  selectedIds: Set<string>;
  multiSelect: boolean;
}

interface CategoryTreeNodeEmits {
  (e: 'toggle-expand', categoryId: string): void;
  (e: 'toggle-select', categoryId: string): void;
}

const nodeProps = defineProps<CategoryTreeNodeProps>();
const nodeEmit = defineEmits<CategoryTreeNodeEmits>();

const isExpanded = computed(() => nodeProps.expandedNodes.has(nodeProps.category.id));
const isSelected = computed(() => nodeProps.selectedIds.has(nodeProps.category.id));
const hasChildren = computed(() => nodeProps.category.children && nodeProps.category.children.length > 0);
</script>

<template>
  <div class="tree-node">
    <div class="node-content" :class="{ selected: isSelected }">
      <button
        v-if="hasChildren"
        @click="nodeEmit('toggle-expand', category.id)"
        class="expand-btn"
        :aria-label="isExpanded ? 'Collapse' : 'Expand'"
      >
        <span class="expand-icon" :class="{ expanded: isExpanded }">▶</span>
      </button>
      <div v-else class="expand-placeholder"></div>

      <label class="node-label" @click="nodeEmit('toggle-select', category.id)">
        <input
          type="checkbox"
          :checked="isSelected"
          @click.stop="nodeEmit('toggle-select', category.id)"
          class="node-checkbox"
        />
        <span class="node-text">{{ category.name }}</span>
      </label>
    </div>

    <div v-if="hasChildren && isExpanded" class="node-children">
      <CategoryTreeNode
        v-for="child in category.children"
        :key="child.id"
        :category="child"
        :expanded-nodes="expandedNodes"
        :selected-ids="selectedIds"
        :multi-select="multiSelect"
        @toggle-expand="nodeEmit('toggle-expand', $event)"
        @toggle-select="nodeEmit('toggle-select', $event)"
      />
    </div>
  </div>
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

.tree-node {
  margin: 0.25rem 0;
}

.node-content {
  display: flex;
  align-items: center;
  padding: 0.5rem;
  border-radius: 4px;
  transition: background-color 0.2s;
}

.node-content:hover {
  background: var(--color-surface-variant);
}

.node-content.selected {
  background: rgba(var(--color-primary-rgb), 0.1);
}

.expand-btn {
  background: none;
  border: none;
  cursor: pointer;
  padding: 0.25rem;
  color: var(--color-text-secondary);
  transition: transform 0.2s;
}

.expand-icon {
  display: inline-block;
  transition: transform 0.2s;
}

.expand-icon.expanded {
  transform: rotate(90deg);
}

.expand-placeholder {
  width: 1.5rem;
}

.node-label {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  flex: 1;
  cursor: pointer;
}

.node-checkbox {
  width: 1rem;
  height: 1rem;
  cursor: pointer;
}

.node-text {
  font-size: 0.9375rem;
  color: var(--color-text);
}

.node-children {
  margin-left: 1.5rem;
  border-left: 1px solid var(--color-border);
  padding-left: 0.5rem;
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
