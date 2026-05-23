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
  <div class="bulk-result" v-if="store.submitResult">
    <div class="bulk-result-counter">{{ store.submitResult.added }}</div>
    <p class="bulk-result-hint">{{ $t('contentSearch.bulk.result.addedHint') }}</p>

    <details v-if="failedRows.length > 0" class="bulk-result-errors">
      <summary>{{ $t('contentSearch.bulk.result.errorsToggle', { n: failedRows.length }) }}</summary>
      <ul class="bulk-result-error-list">
        <li v-for="r in failedRows" :key="r.rowIndex">
          <small>#{{ (r.rowIndex ?? 0) + 1 }} — {{ r.originalUrl }} ({{ r.errorCode }})</small>
        </li>
      </ul>
    </details>

    <div class="bulk-result-actions">
      <button type="button" class="result-btn-outline" @click="store.reset()">
        {{ $t('contentSearch.bulk.result.submitAnother') }}
      </button>
      <button type="button" class="result-btn-link" @click="router.push('/admin/approvals')">
        {{ $t('contentSearch.bulk.result.gotoPending') }}
      </button>
    </div>
  </div>
</template>

<style scoped>
.bulk-result {
  text-align: center;
  padding: 2.5rem 1.5rem;
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: 0.75rem;
}

.bulk-result-counter {
  font-size: 4rem;
  font-weight: 700;
  color: var(--color-success);
  line-height: 1;
  margin-bottom: 0.75rem;
}

.bulk-result-hint {
  margin: 0 0 1.5rem;
  color: var(--color-text-secondary);
  font-size: 1rem;
}

.bulk-result-errors {
  text-align: start;
  margin: 1.5rem auto;
  max-width: 540px;
  padding: 1rem;
  background: var(--color-warning-soft);
  border: 1px solid var(--color-warning);
  border-radius: 0.5rem;
  color: var(--color-warning);
}

.bulk-result-errors summary {
  cursor: pointer;
  font-weight: 600;
  font-size: 0.875rem;
}

.bulk-result-error-list {
  list-style: none;
  margin: 0.5rem 0 0;
  padding: 0;
}

.bulk-result-error-list li {
  padding: 0.25rem 0;
  color: var(--color-text-primary);
  font-size: 0.8125rem;
}

.bulk-result-actions {
  display: flex;
  gap: 0.75rem;
  justify-content: center;
  flex-wrap: wrap;
  margin-top: 0.5rem;
}

.result-btn-outline {
  padding: 0.75rem 1.5rem;
  background: transparent;
  border: 1.5px solid var(--color-brand);
  color: var(--color-brand);
  border-radius: 0.5rem;
  font-weight: 600;
  font-size: 0.9375rem;
  cursor: pointer;
  transition: all 0.2s ease;
}

.result-btn-outline:hover {
  background: var(--color-brand);
  color: var(--color-text-inverse);
}

.result-btn-link {
  background: none;
  border: none;
  color: var(--color-brand);
  font-size: 0.9375rem;
  font-weight: 500;
  cursor: pointer;
  padding: 0.75rem 1rem;
  border-radius: 0.5rem;
}

.result-btn-link:hover {
  text-decoration: underline;
  background: var(--color-brand-soft);
}
</style>
