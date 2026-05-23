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
    <div class="d-flex justify-content-between align-items-center mb-2">
      <small>
        {{ counts.valid }} {{ t('contentSearch.bulk.preview.validCount') }} ·
        {{ counts.duplicate }} {{ t('contentSearch.bulk.preview.duplicateCount') }} ·
        {{ counts.error }} {{ t('contentSearch.bulk.preview.errorCount') }}
      </small>
      <div>
        <button type="button" class="btn btn-link btn-sm" @click="store.phase = 'INPUT'">
          {{ t('contentSearch.bulk.preview.backButton') }}
        </button>
        <button
          type="button"
          class="btn btn-primary btn-sm ms-2"
          :disabled="counts.valid === 0"
          @click="store.runSubmit()"
        >
          {{ t('contentSearch.bulk.preview.submitButton') }}
        </button>
      </div>
    </div>

    <table class="table table-sm align-middle">
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
</template>
