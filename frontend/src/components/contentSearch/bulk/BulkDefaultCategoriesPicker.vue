<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { getAllCategories } from '@/services/categoryService'
import type { Category } from '@/services/categoryService'

const { t } = useI18n()

const props = defineProps<{ modelValue: string[] }>()
const emit = defineEmits<{ (e: 'update:modelValue', v: string[]): void }>()

const categories = ref<Category[]>([])
const isLoading = ref(false)
const loadError = ref<string | null>(null)

/** Flatten tree to show all selectable categories (root + subcategories). */
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

const flatCategories = ref<Category[]>([])

onMounted(async () => {
  isLoading.value = true
  loadError.value = null
  try {
    const tree = await getAllCategories()
    flatCategories.value = flattenCategories(tree)
  } catch (e) {
    loadError.value = (e as Error).message
  } finally {
    isLoading.value = false
  }
})

function toggle(id: string) {
  const next = props.modelValue.includes(id)
    ? props.modelValue.filter((x) => x !== id)
    : [...props.modelValue, id]
  emit('update:modelValue', next)
}

function displayName(cat: Category): string {
  return (cat.name ?? cat.label ?? cat.id)
}
</script>

<template>
  <div class="bulk-default-categories">
    <label class="form-label fw-semibold">
      {{ t('contentSearch.bulk.input.defaultCategoriesLabel') }}
      <span class="text-danger ms-1" aria-hidden="true">*</span>
    </label>

    <div v-if="isLoading" class="text-muted small">
      {{ t('contentSearch.bulk.input.categoriesLoading') }}
    </div>

    <div v-else-if="loadError" class="alert alert-danger small py-1 px-2 mb-0">
      {{ loadError }}
    </div>

    <div v-else class="d-flex flex-wrap gap-2">
      <button
        v-for="cat in flatCategories"
        :key="cat.id"
        type="button"
        class="btn btn-sm"
        :class="modelValue.includes(cat.id) ? 'btn-primary' : 'btn-outline-secondary'"
        @click="toggle(cat.id)"
      >
        {{ displayName(cat) }}
      </button>
      <span v-if="flatCategories.length === 0" class="text-muted small">
        {{ t('contentSearch.bulk.input.categoriesEmpty') }}
      </span>
    </div>

    <small v-if="modelValue.length === 0 && !isLoading && !loadError" class="text-danger d-block mt-1">
      {{ t('contentSearch.bulk.input.categoriesRequired') }}
    </small>
  </div>
</template>
