<script setup lang="ts">
import { onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useCategoryPicker, toggleCategoryId, categoryDisplayName } from './useCategoryPicker'

const { t } = useI18n()

const props = defineProps<{ modelValue: string[] }>()
const emit = defineEmits<{ (e: 'update:modelValue', v: string[]): void }>()

const { flatCategories, isLoading, loadError, load } = useCategoryPicker()
onMounted(load)

function toggle(id: string) {
  emit('update:modelValue', toggleCategoryId(props.modelValue, id))
}
</script>

<template>
  <div class="bulk-default-categories">
    <label class="bulk-default-categories-label">
      {{ t('contentSearch.bulk.input.defaultCategoriesLabel') }}
      <span class="required-marker" aria-hidden="true">*</span>
    </label>

    <div v-if="isLoading" class="bulk-default-categories-status">
      {{ t('contentSearch.bulk.input.categoriesLoading') }}
    </div>

    <div v-else-if="loadError" class="bulk-alert bulk-alert-danger bulk-alert-compact">
      {{ loadError }}
    </div>

    <div v-else class="bulk-category-chips">
      <button
        v-for="cat in flatCategories"
        :key="cat.id"
        type="button"
        class="bulk-chip"
        :class="{ active: modelValue.includes(cat.id) }"
        @click="toggle(cat.id)"
      >
        {{ categoryDisplayName(cat) }}
      </button>
      <span v-if="flatCategories.length === 0" class="bulk-default-categories-status">
        {{ t('contentSearch.bulk.input.categoriesEmpty') }}
      </span>
    </div>

    <small
      v-if="modelValue.length === 0 && !isLoading && !loadError"
      class="bulk-default-categories-required"
    >
      {{ t('contentSearch.bulk.input.categoriesRequired') }}
    </small>
  </div>
</template>

<style scoped>
.bulk-default-categories {
  display: flex;
  flex-direction: column;
  gap: 0.625rem;
}

.bulk-default-categories-label {
  font-weight: 600;
  font-size: 0.875rem;
  color: var(--color-text-primary);
}

.required-marker {
  color: var(--color-danger);
  margin-inline-start: 0.25rem;
}

.bulk-default-categories-status {
  font-size: 0.8125rem;
  color: var(--color-text-secondary);
}

.bulk-default-categories-required {
  font-size: 0.8125rem;
  color: var(--color-danger-strong);
  font-weight: 500;
}

.bulk-category-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
}

.bulk-chip {
  padding: 0.5rem 1rem;
  background: transparent;
  border: 1.5px solid var(--color-border);
  border-radius: 999px;
  font-size: 0.8125rem;
  font-weight: 500;
  cursor: pointer;
  color: var(--color-text-primary);
  transition: all 0.2s ease;
}

.bulk-chip:hover {
  border-color: var(--color-brand);
  background: var(--color-brand-soft);
}

.bulk-chip.active {
  background: var(--color-brand);
  color: var(--color-text-inverse);
  border-color: var(--color-brand);
}
</style>
