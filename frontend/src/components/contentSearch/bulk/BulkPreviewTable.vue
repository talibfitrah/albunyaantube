<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useBulkSubmissionStore } from '@/stores/bulkSubmissionStore'
import BulkPreviewRow from './BulkPreviewRow.vue'

const { t } = useI18n()
const store = useBulkSubmissionStore()

const counts = computed(() => ({
  // Count only OK rows — DUPLICATE_REJECTED rows are dropped from the
  // submittable set in bulkSubmissionStore.runSubmit (re-using a rejected
  // youtubeId would create a second Firestore doc; admins must re-approve
  // via the queue UI). Counting them as "valid" enabled the Submit button
  // for batches that contained only DUPLICATE_REJECTED rows, which then
  // failed with "no valid rows to submit" on click — a stuck UX state.
  valid: store.previewRows.filter((r) => r.status === 'OK')
    .length,
  duplicate: store.previewRows.filter((r) => r.status === 'DUPLICATE').length,
  error: store.previewRows.filter((r) => r.status === 'ERROR').length,
}))
</script>

<template>
  <div class="bulk-preview">
    <div class="bulk-preview-header">
      <div class="bulk-preview-counts">
        <span class="count count-valid">{{ counts.valid }} {{ t('contentSearch.bulk.preview.validCount') }}</span>
        <span class="count count-duplicate">{{ counts.duplicate }} {{ t('contentSearch.bulk.preview.duplicateCount') }}</span>
        <span class="count count-error">{{ counts.error }} {{ t('contentSearch.bulk.preview.errorCount') }}</span>
      </div>
      <div class="bulk-preview-actions">
        <button type="button" class="bulk-btn-link" @click="store.phase = 'INPUT'">
          {{ t('contentSearch.bulk.preview.backButton') }}
        </button>
        <button
          type="button"
          class="bulk-btn-primary"
          :disabled="counts.valid === 0"
          @click="store.runSubmit()"
        >
          {{ t('contentSearch.bulk.preview.submitButton') }}
        </button>
      </div>
    </div>

    <div class="bulk-preview-table-wrapper">
      <table class="bulk-preview-table">
        <thead>
          <tr>
            <th>#</th>
            <th></th>
            <th>{{ t('contentSearch.bulk.preview.colType') }}</th>
            <th>{{ t('contentSearch.bulk.preview.colTitle') }}</th>
            <th>{{ t('contentSearch.bulk.preview.colChannel') }}</th>
            <th>{{ t('contentSearch.bulk.preview.colStatus') }}</th>
            <th>{{ t('contentSearch.bulk.preview.colCategories') }}</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          <BulkPreviewRow
            v-for="row in store.previewRows"
            :key="row.rowIndex"
            :row="row"
            :default-category-ids="store.defaultCategoryIds"
            @remove="store.removeRow($event)"
            @update-categories="(idx, ids) => store.setRowCategories(idx, ids)"
          />
        </tbody>
      </table>
    </div>
  </div>
</template>

<style scoped>
.bulk-preview {
  display: flex;
  flex-direction: column;
  gap: 1rem;
}

.bulk-preview-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 1rem;
  flex-wrap: wrap;
}

.bulk-preview-counts {
  display: flex;
  gap: 1rem;
  font-size: 0.875rem;
  flex-wrap: wrap;
}

.count {
  display: inline-flex;
  align-items: center;
  font-weight: 500;
}

.count-valid { color: var(--color-success); }
.count-duplicate { color: var(--color-warning); }
.count-error { color: var(--color-danger-strong); }

.bulk-preview-actions {
  display: flex;
  gap: 0.75rem;
  align-items: center;
}

.bulk-btn-link {
  background: none;
  border: none;
  color: var(--color-brand);
  cursor: pointer;
  font-size: 0.875rem;
  font-weight: 500;
  padding: 0.5rem 0.75rem;
  border-radius: 0.375rem;
}

.bulk-btn-link:hover {
  text-decoration: underline;
  background: var(--color-brand-soft);
}

.bulk-btn-primary {
  padding: 0.625rem 1.25rem;
  background: var(--color-brand);
  color: var(--color-text-inverse);
  border: none;
  border-radius: 0.5rem;
  font-weight: 600;
  font-size: 0.875rem;
  cursor: pointer;
  transition: all 0.2s ease;
}

.bulk-btn-primary:hover:not(:disabled) {
  background: var(--color-accent);
}

.bulk-btn-primary:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.bulk-preview-table-wrapper {
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: 0.75rem;
  /* overflow: visible (no clipping) so the absolute-positioned
     per-row category editor (z-index: 1050) can escape the table
     bounds. overflow-x: auto here would establish a clipping context
     that crops the dropdown. The admin dashboard is desktop-only and
     the 8-column table fits comfortably; if a future column makes the
     table wider than the viewport we should teleport the editor to
     body instead of re-introducing wrapper clipping. */
  overflow: visible;
}

.bulk-preview-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 0.875rem;
}

.bulk-preview-table thead th {
  text-align: start;
  padding: 0.75rem 0.875rem;
  font-weight: 600;
  font-size: 0.8125rem;
  color: var(--color-text-secondary);
  text-transform: uppercase;
  letter-spacing: 0.03em;
  border-bottom: 1px solid var(--color-border);
  background: var(--color-surface-alt);
}

.bulk-preview-table tbody :deep(td) {
  padding: 0.75rem 0.875rem;
  vertical-align: middle;
  border-bottom: 1px solid var(--color-border);
  color: var(--color-text-primary);
}

.bulk-preview-table tbody :deep(tr:last-child td) {
  border-bottom: none;
}
</style>
