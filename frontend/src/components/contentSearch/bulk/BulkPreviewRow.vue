<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import type { components } from '@/generated/api/schema'
import BulkRowCategoryEditor from './BulkRowCategoryEditor.vue'

type PreviewRow = components['schemas']['PreviewRow']

interface PreviewRowDraft extends PreviewRow {
  categoryIds?: string[]
}

const props = defineProps<{
  row: PreviewRowDraft
  defaultCategoryIds: string[]
}>()

const emit = defineEmits<{
  (e: 'remove', rowIndex: number): void
  (e: 'updateCategories', rowIndex: number, categoryIds: string[]): void
}>()

const { t } = useI18n()

const editing = ref(false)

const resolvedCategoryIds = computed(() => props.row.categoryIds ?? props.defaultCategoryIds)

const typeChipLabel = computed(() => {
  if (props.row.detectedType === 'VIDEO' && props.row.videoType === 'LIVE') return 'LIVE'
  return props.row.detectedType ?? '—'
})

const statusBadgeClass = computed(() => ({
  'bg-success': props.row.status === 'OK',
  'bg-warning': props.row.status === 'DUPLICATE' || props.row.status === 'DUPLICATE_REJECTED',
  'bg-danger': props.row.status === 'ERROR',
}))

const isActionable = computed(
  () => props.row.status === 'OK' || props.row.status === 'DUPLICATE_REJECTED',
)
</script>

<template>
  <tr>
    <td>{{ (row.rowIndex ?? 0) + 1 }}</td>
    <td>
      <img
        v-if="row.metadata?.thumbnailUrl"
        :src="row.metadata.thumbnailUrl"
        alt=""
        class="bulk-thumb"
        style="max-width: 60px"
      />
    </td>
    <td><span class="badge bg-secondary">{{ typeChipLabel }}</span></td>
    <td class="text-truncate" style="max-width: 240px">
      {{ row.metadata?.title ?? row.originalUrl }}
    </td>
    <td>{{ row.metadata?.channelName ?? '—' }}</td>
    <td>
      <span class="badge" :class="statusBadgeClass">{{ row.status }}</span>
      <small v-if="row.error?.messageKey" class="d-block text-muted">
        {{ t(row.error.messageKey) }}
      </small>
    </td>
    <td class="position-relative">
      <button
        v-if="isActionable"
        type="button"
        class="btn btn-link btn-sm p-0"
        @click="editing = true"
      >
        {{ resolvedCategoryIds.length }}
        {{ t('contentSearch.bulk.preview.categoriesLabel') }}
      </button>
      <BulkRowCategoryEditor
        v-if="editing"
        :model-value="resolvedCategoryIds"
        @save="(ids) => { emit('updateCategories', row.rowIndex ?? 0, ids); editing = false }"
        @cancel="editing = false"
      />
    </td>
    <td>
      <button
        type="button"
        class="btn btn-link btn-sm text-danger"
        :aria-label="t('contentSearch.bulk.preview.removeRow')"
        @click="emit('remove', row.rowIndex ?? 0)"
      >
        ×
      </button>
    </td>
  </tr>
</template>
