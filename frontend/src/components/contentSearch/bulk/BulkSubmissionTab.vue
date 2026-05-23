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
    <div v-if="store.error" class="alert alert-warning" role="alert">
      {{ store.error }}
    </div>

    <div v-if="store.phase === 'LOADING'" class="text-center my-5">
      <div class="spinner-border" role="status"></div>
      <p class="mt-2 text-muted">{{ $t('contentSearch.bulk.loading') }}</p>
    </div>

    <BulkInputView v-else-if="store.phase === 'INPUT'" @show-format-help="showFormatHelp = true" />
    <BulkPreviewTable v-else-if="store.phase === 'PREVIEW'" />
    <BulkResultSummary v-else-if="store.phase === 'RESULT'" />

    <BulkFormatHelpModal v-if="showFormatHelp" @close="showFormatHelp = false" />
  </div>
</template>
