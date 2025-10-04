<template>
  <div class="categories-view">
    <header class="categories-header">
      <div>
        <h1>{{ t('categories.heading') }}</h1>
        <p>{{ t('categories.subtitle') }}</p>
      </div>
      <button type="button" class="add-button" @click="openAddDialog(null)">
        {{ t('categories.addCategory') }}
      </button>
    </header>

    <!-- Loading State -->
    <div v-if="isLoading" class="loading">
      <div class="spinner"></div>
      <p>{{ t('categories.loading') }}</p>
    </div>

    <!-- Error State -->
    <div v-else-if="error" class="error-panel" role="alert">
      <p>{{ error }}</p>
      <button type="button" @click="loadCategories">{{ t('categories.retry') }}</button>
    </div>

    <!-- Categories Tree -->
    <div v-else class="categories-tree-container">
      <div v-if="categories.length === 0" class="empty-state">
        <p>{{ t('categories.empty') }}</p>
        <button type="button" @click="openAddDialog(null)">
          {{ t('categories.addFirst') }}
        </button>
      </div>

      <div v-else class="categories-tree">
        <CategoryTreeItem
          v-for="category in categories"
          :key="category.id"
          :category="category"
          :level="0"
          @edit="openEditDialog"
          @delete="handleDelete"
          @add-child="openAddDialog"
        />
      </div>
    </div>

    <!-- Add/Edit Modal -->
    <teleport to="body">
      <div v-if="showDialog" class="modal-overlay" @click="closeDialog">
        <div class="modal" @click.stop>
          <header class="modal-header">
            <h2>{{ dialogMode === 'add' ? t('categories.dialog.addTitle') : t('categories.dialog.editTitle') }}</h2>
            <button type="button" class="close-button" @click="closeDialog">×</button>
          </header>

          <form @submit.prevent="handleSubmit">
            <div class="form-group">
              <label for="category-name">{{ t('categories.dialog.name') }} *</label>
              <input
                id="category-name"
                v-model="dialogData.name"
                type="text"
                required
                :placeholder="t('categories.dialog.namePlaceholder')"
              />
            </div>

            <div v-if="dialogData.parentId" class="form-group">
              <label>{{ t('categories.dialog.parent') }}</label>
              <p class="parent-name">{{ getParentName(dialogData.parentId) }}</p>
            </div>

            <div class="form-group">
              <label for="category-icon">{{ t('categories.dialog.icon') }}</label>
              <input
                id="category-icon"
                v-model="dialogData.icon"
                type="text"
                :placeholder="t('categories.dialog.iconPlaceholder')"
              />
            </div>

            <div class="form-group">
              <label for="category-order">{{ t('categories.dialog.displayOrder') }}</label>
              <input
                id="category-order"
                v-model.number="dialogData.displayOrder"
                type="number"
                min="0"
              />
            </div>

            <div v-if="dialogError" class="form-error">{{ dialogError }}</div>

            <div class="modal-footer">
              <button type="button" class="button secondary" @click="closeDialog">
                {{ t('categories.dialog.cancel') }}
              </button>
              <button type="submit" class="button primary" :disabled="isSubmitting">
                {{ isSubmitting ? t('categories.dialog.saving') : t('categories.dialog.save') }}
              </button>
            </div>
          </form>
        </div>
      </div>
    </teleport>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { useI18n } from 'vue-i18n';
import { fetchAllCategories } from '@/services/categories';
import CategoryTreeItem from '@/components/categories/CategoryTreeItem.vue';

const { t } = useI18n();

const categories = ref<any[]>([]);
const isLoading = ref(false);
const error = ref<string | null>(null);

const showDialog = ref(false);
const dialogMode = ref<'add' | 'edit'>('add');
const dialogData = ref({
  id: '',
  name: '',
  parentId: null as string | null,
  icon: '',
  displayOrder: 0
});
const isSubmitting = ref(false);
const dialogError = ref<string | null>(null);

async function loadCategories() {
  isLoading.value = true;
  error.value = null;

  try {
    const cats = await fetchAllCategories();
    categories.value = cats;
  } catch (err) {
    error.value = err instanceof Error ? err.message : t('categories.error');
  } finally {
    isLoading.value = false;
  }
}

function openAddDialog(parentId: string | null) {
  dialogMode.value = 'add';
  dialogData.value = {
    id: '',
    name: '',
    parentId,
    icon: '',
    displayOrder: 0
  };
  showDialog.value = true;
}

function openEditDialog(category: any) {
  dialogMode.value = 'edit';
  dialogData.value = {
    id: category.id,
    name: category.label,
    parentId: category.parentId || null,
    icon: category.icon || '',
    displayOrder: category.displayOrder || 0
  };
  showDialog.value = true;
}

function closeDialog() {
  showDialog.value = false;
  dialogError.value = null;
}

async function handleSubmit() {
  if (!dialogData.value.name.trim()) {
    dialogError.value = t('categories.dialog.nameRequired');
    return;
  }

  isSubmitting.value = true;
  dialogError.value = null;

  try {
    // TODO: Implement actual create/update API calls
    await new Promise(resolve => setTimeout(resolve, 500));

    closeDialog();
    await loadCategories();
  } catch (err) {
    dialogError.value = err instanceof Error ? err.message : t('categories.dialog.error');
  } finally {
    isSubmitting.value = false;
  }
}

async function handleDelete(categoryId: string) {
  if (!confirm(t('categories.confirmDelete'))) {
    return;
  }

  try {
    // TODO: Implement actual delete API call
    await new Promise(resolve => setTimeout(resolve, 300));
    await loadCategories();
  } catch (err) {
    error.value = err instanceof Error ? err.message : t('categories.deleteError');
  }
}

function getParentName(parentId: string): string {
  function findCategory(cats: any[], id: string): any {
    for (const cat of cats) {
      if (cat.id === id) return cat;
      if (cat.subcategories) {
        const found = findCategory(cat.subcategories, id);
        if (found) return found;
      }
    }
    return null;
  }

  const parent = findCategory(categories.value, parentId);
  return parent ? parent.label : '';
}

onMounted(() => {
  loadCategories();
});
</script>

<style scoped>
.categories-view {
  display: flex;
  flex-direction: column;
  gap: 2rem;
}

.categories-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 1rem;
}

.categories-header h1 {
  margin: 0;
  font-size: 2rem;
  font-weight: 700;
  color: var(--color-text-primary);
  letter-spacing: -0.02em;
}

.categories-header p {
  margin: 0.75rem 0 0;
  color: var(--color-text-secondary);
  font-size: 0.9375rem;
}

.add-button {
  padding: 0.75rem 1.5rem;
  background: var(--color-brand);
  color: var(--color-text-inverse);
  border: none;
  border-radius: 0.5rem;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s ease;
  white-space: nowrap;
  -webkit-tap-highlight-color: transparent;
  min-height: 44px;
}

@media (hover: hover) {
  .add-button:hover {
    background: var(--color-accent);
    box-shadow: 0 4px 12px rgba(22, 131, 90, 0.25);
    transform: translateY(-1px);
  }
}

.loading {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 1rem;
  padding: 3rem;
  color: var(--color-text-secondary);
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

.error-panel {
  background: var(--color-danger-soft);
  border: 1px solid var(--color-danger);
  border-radius: 0.75rem;
  padding: 1.5rem;
  text-align: center;
}

.error-panel button {
  margin-top: 1rem;
  padding: 0.625rem 1.25rem;
  background: var(--color-brand);
  color: var(--color-text-inverse);
  border: none;
  border-radius: 0.5rem;
  font-weight: 600;
  cursor: pointer;
  -webkit-tap-highlight-color: transparent;
  min-height: 44px;
}

.categories-tree-container {
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: 0.75rem;
  padding: 1.5rem;
}

.empty-state {
  text-align: center;
  padding: 3rem;
  color: var(--color-text-secondary);
}

.empty-state button {
  margin-top: 1rem;
  padding: 0.75rem 1.5rem;
  background: var(--color-brand);
  color: var(--color-text-inverse);
  border: none;
  border-radius: 0.5rem;
  font-weight: 600;
  cursor: pointer;
  -webkit-tap-highlight-color: transparent;
  min-height: 44px;
}

.categories-tree {
  display: flex;
  flex-direction: column;
}

/* Modal */
.modal-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
  padding: 1rem;
}

.modal {
  background: var(--color-surface);
  border-radius: 0.75rem;
  max-width: 500px;
  width: 100%;
  max-height: 90vh;
  overflow-y: auto;
  box-shadow: 0 24px 64px rgba(0, 0, 0, 0.3);
}

.modal-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 1.5rem;
  border-bottom: 1px solid var(--color-border);
}

.modal-header h2 {
  margin: 0;
  font-size: 1.25rem;
  font-weight: 600;
  color: var(--color-text-primary);
}

.close-button {
  width: 2.75rem;
  height: 2.75rem;
  display: flex;
  align-items: center;
  justify-content: center;
  background: transparent;
  border: none;
  font-size: 1.5rem;
  color: var(--color-text-secondary);
  cursor: pointer;
  border-radius: 0.25rem;
  transition: all 0.2s ease;
  -webkit-tap-highlight-color: transparent;
}

@media (hover: hover) {
  .close-button:hover {
    background: var(--color-surface-alt);
    color: var(--color-text-primary);
  }
}

form {
  padding: 1.5rem;
  display: flex;
  flex-direction: column;
  gap: 1.25rem;
}

.form-group {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.form-group label {
  font-weight: 600;
  font-size: 0.875rem;
  color: var(--color-text-primary);
}

.form-group input {
  padding: 0.875rem 1rem;
  border-radius: 0.5rem;
  border: 1.5px solid var(--color-border);
  background: var(--color-surface);
  font-size: 0.9375rem;
  transition: all 0.2s ease;
}

.form-group input {
  -webkit-tap-highlight-color: transparent;
  min-height: 48px;
}

@media (hover: hover) {
  .form-group input:hover {
    border-color: var(--color-brand);
  }
}

.form-group input:focus {
  outline: none;
  border-color: var(--color-brand);
  box-shadow: 0 0 0 3px rgba(22, 131, 90, 0.1);
}

.parent-name {
  margin: 0;
  padding: 0.75rem;
  background: var(--color-surface-alt);
  border-radius: 0.5rem;
  color: var(--color-text-secondary);
  font-size: 0.875rem;
}

.form-error {
  background: var(--color-danger-soft);
  color: var(--color-danger);
  padding: 0.75rem;
  border-radius: 0.5rem;
  font-size: 0.875rem;
}

.modal-footer {
  display: flex;
  gap: 0.75rem;
  justify-content: flex-end;
  padding-top: 1rem;
  border-top: 1px solid var(--color-border);
}

.button {
  padding: 0.75rem 1.5rem;
  border: none;
  border-radius: 0.5rem;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s ease;
  -webkit-tap-highlight-color: transparent;
  min-height: 44px;
}

.button.primary {
  background: var(--color-brand);
  color: var(--color-text-inverse);
}

@media (hover: hover) {
  .button.primary:hover:not(:disabled) {
    background: var(--color-accent);
    box-shadow: 0 2px 8px rgba(22, 131, 90, 0.25);
  }
}

.button.primary:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.button.secondary {
  background: var(--color-surface-alt);
  color: var(--color-text-primary);
  border: 1.5px solid var(--color-border);
}

@media (hover: hover) {
  .button.secondary:hover {
    background: var(--color-surface);
    border-color: var(--color-brand);
  }
}

/* Mobile/Tablet Responsive */
@media (max-width: 1023px) {
  .categories-view {
    gap: 1.5rem;
  }

  .categories-header {
    flex-direction: column;
    align-items: stretch;
    gap: 1rem;
  }

  .categories-header h1 {
    font-size: 1.75rem;
  }

  .categories-header p {
    font-size: 0.875rem;
  }

  .add-button {
    width: 100%;
    padding: 0.875rem 1.5rem;
    min-height: 48px;
  }

  .categories-tree-container {
    padding: 1rem;
  }

  .modal {
    max-width: calc(100vw - 2rem);
  }

  .modal-footer {
    flex-direction: column-reverse;
    gap: 0.625rem;
  }

  .button {
    width: 100%;
    min-height: 48px;
  }
}

@media (max-width: 767px) {
  .categories-header h1 {
    font-size: 1.5rem;
  }

  .categories-tree-container {
    padding: 0.75rem;
  }
}
</style>
