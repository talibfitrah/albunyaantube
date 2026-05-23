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

function onFileParsed(urls: string[], fileName: string) {
  store.uploadedFileName = fileName
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
    <!-- Error banner from previous parse attempt -->
    <div v-if="store.error" class="alert alert-danger small py-2 px-3 mb-3">
      {{ store.error }}
    </div>

    <div class="row g-3">
      <div class="col-md-6">
        <BulkUrlPasteField
          :model-value="store.pastedUrls"
          @update:model-value="onPastedUrlsUpdate"
        />
      </div>

      <div class="col-md-6">
        <BulkFileDropzone @parsed="onFileParsed" @error="dropzoneError = $event" />

        <button
          type="button"
          class="btn btn-link btn-sm p-0 mt-1"
          @click="emit('showFormatHelp')"
        >
          &#9432; {{ t('contentSearch.bulk.input.formatHelpButton') }}
        </button>

        <div v-if="dropzoneError" class="alert alert-warning small py-1 px-2 mt-2 mb-0">
          {{ dropzoneError }}
        </div>
      </div>
    </div>

    <div class="mt-3">
      <BulkDefaultCategoriesPicker
        :model-value="store.defaultCategoryIds"
        @update:model-value="store.defaultCategoryIds = $event"
      />
    </div>

    <button
      type="button"
      class="btn btn-primary mt-3"
      :disabled="!canParse"
      @click="store.runPreview()"
    >
      {{ t('contentSearch.bulk.input.parseButton') }}
    </button>
  </div>
</template>
