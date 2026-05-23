<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useCategoryPicker, toggleCategoryId, categoryDisplayName } from './useCategoryPicker'

const props = defineProps<{ modelValue: string[] }>()
const emit = defineEmits<{
  (e: 'save', categoryIds: string[]): void
  (e: 'cancel'): void
}>()

const { t } = useI18n()

const { flatCategories, isLoading, load } = useCategoryPicker()
onMounted(load)

const selected = ref<string[]>([...props.modelValue])

function toggle(id: string) {
  selected.value = toggleCategoryId(selected.value, id)
}
</script>

<template>
  <div class="bulk-row-category-editor">
    <div v-if="isLoading" class="editor-status">
      {{ t('contentSearch.bulk.input.categoriesLoading') }}
    </div>
    <div v-else class="editor-chips">
      <button
        v-for="cat in flatCategories"
        :key="cat.id"
        type="button"
        class="editor-chip"
        :class="{ active: selected.includes(cat.id) }"
        @click="toggle(cat.id)"
      >
        {{ categoryDisplayName(cat) }}
      </button>
      <span v-if="flatCategories.length === 0" class="editor-status">
        {{ t('contentSearch.bulk.input.categoriesEmpty') }}
      </span>
    </div>
    <div class="editor-actions">
      <button type="button" class="editor-btn-link" @click="emit('cancel')">
        {{ t('contentSearch.bulk.preview.cancel') }}
      </button>
      <button
        type="button"
        class="editor-btn-primary"
        :disabled="selected.length === 0"
        @click="emit('save', selected)"
      >
        {{ t('contentSearch.bulk.preview.save') }}
      </button>
    </div>
  </div>
</template>

<style scoped>
.bulk-row-category-editor {
  position: absolute;
  z-index: 1050;
  top: calc(100% + 0.25rem);
  left: 0;
  min-width: 280px;
  max-width: 360px;
  padding: 0.75rem;
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: 0.5rem;
  box-shadow: var(--shadow-elevated);
  display: flex;
  flex-direction: column;
  gap: 0.625rem;
}

[dir='rtl'] .bulk-row-category-editor {
  left: auto;
  right: 0;
}

.editor-status {
  font-size: 0.8125rem;
  color: var(--color-text-secondary);
}

.editor-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 0.375rem;
}

.editor-chip {
  padding: 0.375rem 0.75rem;
  background: transparent;
  border: 1.5px solid var(--color-border);
  border-radius: 999px;
  font-size: 0.75rem;
  font-weight: 500;
  cursor: pointer;
  color: var(--color-text-primary);
  transition: all 0.2s ease;
}

.editor-chip:hover {
  border-color: var(--color-brand);
  background: var(--color-brand-soft);
}

.editor-chip.active {
  background: var(--color-brand);
  color: var(--color-text-inverse);
  border-color: var(--color-brand);
}

.editor-actions {
  display: flex;
  justify-content: flex-end;
  gap: 0.5rem;
  padding-top: 0.25rem;
  border-top: 1px solid var(--color-border);
}

.editor-btn-link {
  background: none;
  border: none;
  padding: 0.375rem 0.75rem;
  color: var(--color-text-secondary);
  font-size: 0.8125rem;
  font-weight: 500;
  cursor: pointer;
  border-radius: 0.375rem;
}

.editor-btn-link:hover {
  color: var(--color-text-primary);
  background: var(--color-surface-alt);
}

.editor-btn-primary {
  padding: 0.375rem 0.875rem;
  background: var(--color-brand);
  color: var(--color-text-inverse);
  border: none;
  border-radius: 0.375rem;
  font-size: 0.8125rem;
  font-weight: 600;
  cursor: pointer;
}

.editor-btn-primary:hover:not(:disabled) {
  background: var(--color-accent);
}

.editor-btn-primary:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
</style>
