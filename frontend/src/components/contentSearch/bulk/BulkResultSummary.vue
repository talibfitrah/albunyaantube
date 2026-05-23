<script setup lang="ts">
import { computed } from 'vue'
import { useBulkSubmissionStore } from '@/stores/bulkSubmissionStore'
import { useRouter } from 'vue-router'

const store = useBulkSubmissionStore()
const router = useRouter()

const failedRows = computed(
  () => store.submitResult?.results?.filter((r) => r.status === 'FAILED') ?? [],
)
</script>

<template>
  <div class="bulk-result text-center" v-if="store.submitResult">
    <h2 class="display-5 mb-3">{{ store.submitResult.added }}</h2>
    <p class="text-muted">{{ $t('contentSearch.bulk.result.addedHint') }}</p>

    <details v-if="failedRows.length > 0" class="text-start mt-3">
      <summary>{{ $t('contentSearch.bulk.result.errorsToggle', { n: failedRows.length }) }}</summary>
      <ul class="list-unstyled mt-2">
        <li v-for="r in failedRows" :key="r.rowIndex">
          <small>#{{ (r.rowIndex ?? 0) + 1 }} — {{ r.originalUrl }} ({{ r.errorCode }})</small>
        </li>
      </ul>
    </details>

    <div class="mt-4 d-flex gap-2 justify-content-center flex-wrap">
      <button type="button" class="btn btn-outline-primary" @click="store.reset()">
        {{ $t('contentSearch.bulk.result.submitAnother') }}
      </button>
      <button type="button" class="btn btn-link" @click="router.push('/admin/approvals')">
        {{ $t('contentSearch.bulk.result.gotoPending') }}
      </button>
    </div>
  </div>
</template>
