<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { getAllCategories } from '@/services/categoryService'
import type { Category } from '@/services/categoryService'

const props = defineProps<{ modelValue: string[] }>()
const emit = defineEmits<{
  (e: 'save', categoryIds: string[]): void
  (e: 'cancel'): void
}>()

const { t } = useI18n()

const flatCategories = ref<Category[]>([])
const selected = ref<string[]>([...props.modelValue])
const isLoading = ref(false)

function flattenCategories(cats: Category[]): Category[] {
  const result: Category[] = []
  function walk(list: Category[]) {
    for (const c of list) {
      result.push(c)
      if (c.subcategories?.length) walk(c.subcategories)
    }
  }
  walk(cats)
  return result
}

onMounted(async () => {
  isLoading.value = true
  try {
    const tree = await getAllCategories()
    flatCategories.value = flattenCategories(tree)
  } finally {
    isLoading.value = false
  }
})

function toggle(id: string) {
  selected.value = selected.value.includes(id)
    ? selected.value.filter((x) => x !== id)
    : [...selected.value, id]
}

function displayName(cat: Category): string {
  return cat.name ?? cat.label ?? cat.id
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
          {{ displayName(cat) }}
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
