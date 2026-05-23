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

const statusBadgeClass = computed(() => {
  if (props.row.status === 'OK') return 'bulk-badge-success'
  if (props.row.status === 'DUPLICATE' || props.row.status === 'DUPLICATE_REJECTED') return 'bulk-badge-warning'
  if (props.row.status === 'ERROR') return 'bulk-badge-danger'
  return ''
})

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
      />
    </td>
    <td><span class="bulk-badge bulk-badge-neutral">{{ typeChipLabel }}</span></td>
    <td class="bulk-cell-title">
      {{ row.metadata?.title ?? row.originalUrl }}
    </td>
    <td>{{ row.metadata?.channelName ?? '—' }}</td>
    <td>
      <span class="bulk-badge" :class="statusBadgeClass">{{ row.status }}</span>
      <small v-if="row.error?.messageKey" class="bulk-row-error">
        {{ t(row.error.messageKey) }}
      </small>
    </td>
    <td class="bulk-cell-categories">
      <button
        v-if="isActionable"
        type="button"
        class="bulk-categories-toggle"
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
        class="bulk-remove-btn"
        :aria-label="t('contentSearch.bulk.preview.removeRow')"
        @click="emit('remove', row.rowIndex ?? 0)"
      >
        ×
      </button>
    </td>
  </tr>
</template>

<style scoped>
.bulk-thumb {
  max-width: 60px;
  border-radius: 0.25rem;
  display: block;
}

.bulk-badge {
  display: inline-block;
  padding: 0.25rem 0.625rem;
  border-radius: 999px;
  font-size: 0.75rem;
  font-weight: 600;
  line-height: 1;
  text-transform: uppercase;
  letter-spacing: 0.04em;
}

.bulk-badge-neutral {
  background: var(--color-surface-alt);
  color: var(--color-text-secondary);
}

.bulk-badge-success {
  background: var(--color-success-soft);
  color: var(--color-success);
}

.bulk-badge-warning {
  background: var(--color-warning-soft);
  color: var(--color-warning);
}

.bulk-badge-danger {
  background: var(--color-danger-soft);
  color: var(--color-danger-strong);
}

.bulk-cell-title {
  max-width: 240px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.bulk-row-error {
  display: block;
  margin-top: 0.25rem;
  color: var(--color-text-secondary);
  font-size: 0.75rem;
}

.bulk-cell-categories {
  position: relative;
}

.bulk-categories-toggle {
  background: none;
  border: none;
  padding: 0;
  color: var(--color-brand);
  font-size: 0.8125rem;
  font-weight: 500;
  cursor: pointer;
}

.bulk-categories-toggle:hover {
  text-decoration: underline;
}

.bulk-remove-btn {
  background: none;
  border: none;
  padding: 0.25rem 0.5rem;
  color: var(--color-danger);
  font-size: 1.25rem;
  line-height: 1;
  cursor: pointer;
  border-radius: 0.25rem;
}

.bulk-remove-btn:hover {
  background: var(--color-danger-soft);
}
</style>
