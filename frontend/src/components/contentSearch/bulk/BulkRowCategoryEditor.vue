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
  <div
    class="bulk-row-category-editor card shadow position-absolute"
    style="z-index: 1050; min-width: 280px; top: 100%; left: 0"
  >
    <div class="card-body p-2">
      <div v-if="isLoading" class="text-muted small">
        {{ t('contentSearch.bulk.input.categoriesLoading') }}
      </div>
      <div v-else class="d-flex flex-wrap gap-1 mb-2">
        <button
          v-for="cat in flatCategories"
          :key="cat.id"
          type="button"
          class="btn btn-sm"
          :class="selected.includes(cat.id) ? 'btn-primary' : 'btn-outline-secondary'"
          @click="toggle(cat.id)"
        >
          {{ categoryDisplayName(cat) }}
        </button>
        <span v-if="flatCategories.length === 0" class="text-muted small">
          {{ t('contentSearch.bulk.input.categoriesEmpty') }}
        </span>
      </div>
      <div class="d-flex justify-content-end gap-2">
        <button type="button" class="btn btn-sm btn-link" @click="emit('cancel')">
          {{ t('contentSearch.bulk.preview.cancel') }}
        </button>
        <button
          type="button"
          class="btn btn-sm btn-primary"
          :disabled="selected.length === 0"
          @click="emit('save', selected)"
        >
          {{ t('contentSearch.bulk.preview.save') }}
        </button>
      </div>
    </div>
  </div>
</template>
