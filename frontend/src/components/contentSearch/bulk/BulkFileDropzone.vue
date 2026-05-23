<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { parseCsv, parseExcel, parseJson } from '@/utils/bulkFileParsers'

const { t } = useI18n()

const emit = defineEmits<{
  (e: 'parsed', urls: string[], fileName: string): void
  (e: 'error', message: string): void
}>()

const dragOver = ref(false)
const fileInput = ref<HTMLInputElement | null>(null)

async function handleFile(file: File) {
  try {
    let urls: string[]
    const lname = file.name.toLowerCase()
    if (lname.endsWith('.csv')) {
      urls = await parseCsv(file)
    } else if (lname.endsWith('.json')) {
      urls = await parseJson(file)
    } else if (lname.endsWith('.xlsx') || lname.endsWith('.xls')) {
      urls = await parseExcel(file)
    } else {
      throw new Error(t('contentSearch.bulk.input.unsupportedFileType'))
    }
    emit('parsed', urls, file.name)
  } catch (e) {
    emit('error', (e as Error).message)
  }
}

function onDrop(event: DragEvent) {
  event.preventDefault()
  dragOver.value = false
  const f = event.dataTransfer?.files[0]
  if (f) handleFile(f)
}

function onPick(event: Event) {
  const f = (event.target as HTMLInputElement).files?.[0]
  if (f) handleFile(f)
}
</script>

<template>
  <div
    class="bulk-dropzone border rounded p-3 text-center"
    :class="{ 'drag-over border-primary bg-primary bg-opacity-10': dragOver }"
    @dragover.prevent="dragOver = true"
    @dragleave.prevent="dragOver = false"
    @drop="onDrop"
  >
    <p class="mb-2">{{ t('contentSearch.bulk.input.uploadLabel') }}</p>
    <input
      ref="fileInput"
      type="file"
      accept=".csv,.xlsx,.xls,.json"
      hidden
      @change="onPick"
    />
    <button type="button" class="btn btn-outline-primary btn-sm" @click="fileInput?.click()">
      {{ t('contentSearch.bulk.input.uploadBrowse') }}
    </button>
    <p class="text-muted small mt-2 mb-0">{{ t('contentSearch.bulk.input.uploadHint') }}</p>
  </div>
</template>
