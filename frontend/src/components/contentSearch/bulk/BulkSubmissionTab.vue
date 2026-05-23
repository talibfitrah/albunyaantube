<script setup lang="ts">
import { ref } from 'vue'
import { useBulkSubmissionStore } from '@/stores/bulkSubmissionStore'
import BulkInputView from './BulkInputView.vue'
import BulkPreviewTable from './BulkPreviewTable.vue'
import BulkResultSummary from './BulkResultSummary.vue'
import BulkFormatHelpModal from './BulkFormatHelpModal.vue'

const store = useBulkSubmissionStore()
const showFormatHelp = ref(false)
</script>

<template>
  <div class="bulk-submission-tab">
    <div v-if="store.error" class="bulk-alert bulk-alert-warning" role="alert">
      {{ store.error }}
    </div>

    <div v-if="store.phase === 'LOADING'" class="bulk-loading">
      <div class="spinner" role="status" aria-hidden="true"></div>
      <p>{{ $t('contentSearch.bulk.loading') }}</p>
    </div>

    <BulkInputView v-else-if="store.phase === 'INPUT'" @show-format-help="showFormatHelp = true" />
    <BulkPreviewTable v-else-if="store.phase === 'PREVIEW'" />
    <BulkResultSummary v-else-if="store.phase === 'RESULT'" />

    <BulkFormatHelpModal v-if="showFormatHelp" @close="showFormatHelp = false" />
  </div>
</template>

<style scoped>
.bulk-submission-tab {
  display: flex;
  flex-direction: column;
  gap: 1rem;
}

.bulk-loading {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 1rem;
  padding: 3rem;
  color: var(--color-text-secondary);
}

.bulk-loading p {
  margin: 0;
  font-size: 0.9375rem;
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
</style>
