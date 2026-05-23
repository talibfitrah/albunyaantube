<script setup lang="ts">
import { ref, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useBulkSubmissionStore } from '@/stores/bulkSubmissionStore'
import { parsePastedUrls } from '@/utils/bulkFileParsers'
import BulkUrlPasteField from './BulkUrlPasteField.vue'
import BulkFileDropzone from './BulkFileDropzone.vue'
import BulkDefaultCategoriesPicker from './BulkDefaultCategoriesPicker.vue'

const { t } = useI18n()
const store = useBulkSubmissionStore()

const dropzoneError = ref<string | null>(null)

const emit = defineEmits<{ (e: 'showFormatHelp'): void }>()

function onPastedUrlsUpdate(raw: string) {
  store.pastedUrls = raw
  store.parsedUrls = parsePastedUrls(raw)
}

function onFileParsed(urls: string[]) {
  store.parsedUrls = urls
  store.pastedUrls = urls.join('\n')
  dropzoneError.value = null
}

const canParse = computed(
  () =>
    store.parsedUrls.length > 0 &&
    store.parsedUrls.length <= 25 &&
    store.defaultCategoryIds.length > 0
)
</script>

<template>
  <div class="bulk-input">
    <div v-if="store.error" class="bulk-alert bulk-alert-danger" role="alert">
      {{ store.error }}
    </div>

    <div class="bulk-input-grid">
      <div class="bulk-input-col">
        <BulkUrlPasteField
          :model-value="store.pastedUrls"
          @update:model-value="onPastedUrlsUpdate"
        />
      </div>

      <div class="bulk-input-col">
        <BulkFileDropzone @parsed="onFileParsed" @error="dropzoneError = $event" />

        <button
          type="button"
          class="bulk-help-link"
          @click="emit('showFormatHelp')"
        >
          <span aria-hidden="true">&#9432;</span>
          {{ t('contentSearch.bulk.input.formatHelpButton') }}
        </button>

        <div v-if="dropzoneError" class="bulk-alert bulk-alert-warning bulk-alert-compact">
          {{ dropzoneError }}
        </div>
      </div>
    </div>

    <BulkDefaultCategoriesPicker
      :model-value="store.defaultCategoryIds"
      @update:model-value="store.defaultCategoryIds = $event"
    />

    <div class="bulk-input-actions">
      <button
        type="button"
        class="bulk-btn bulk-btn-primary"
        :disabled="!canParse"
        @click="store.runPreview()"
      >
        {{ t('contentSearch.bulk.input.parseButton') }}
      </button>
    </div>
  </div>
</template>

<style scoped>
.bulk-input {
  display: flex;
  flex-direction: column;
  gap: 1.5rem;
}

.bulk-input-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 1.5rem;
}

@media (max-width: 768px) {
  .bulk-input-grid {
    grid-template-columns: 1fr;
  }
}

.bulk-input-col {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.bulk-help-link {
  align-self: flex-start;
  background: none;
  border: none;
  padding: 0;
  font-size: 0.8125rem;
  color: var(--color-brand);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 0.25rem;
}

.bulk-help-link:hover {
  text-decoration: underline;
}

.bulk-input-actions {
  display: flex;
  gap: 0.75rem;
}

.bulk-btn {
  padding: 0.75rem 2rem;
  border-radius: 0.5rem;
  font-weight: 600;
  font-size: 0.9375rem;
  cursor: pointer;
  transition: all 0.2s ease;
  border: 1.5px solid transparent;
}

.bulk-btn-primary {
  background: var(--color-brand);
  color: var(--color-text-inverse);
  border-color: var(--color-brand);
}

.bulk-btn-primary:hover:not(:disabled) {
  background: var(--color-accent);
  box-shadow: 0 4px 12px rgba(22, 131, 90, 0.25);
  transform: translateY(-1px);
}

.bulk-btn-primary:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
</style>
