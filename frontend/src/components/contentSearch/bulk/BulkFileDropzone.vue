<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { parseCsv, parseExcel, parseJson } from '@/utils/bulkFileParsers'

const { t } = useI18n()

const emit = defineEmits<{
  (e: 'parsed', urls: string[]): void
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
    emit('parsed', urls)
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
    class="bulk-dropzone"
    :class="{ 'is-drag-over': dragOver }"
    @dragover.prevent="dragOver = true"
    @dragleave.prevent="dragOver = false"
    @drop="onDrop"
  >
    <p class="bulk-dropzone-label">{{ t('contentSearch.bulk.input.uploadLabel') }}</p>
    <input
      ref="fileInput"
      type="file"
      accept=".csv,.xlsx,.xls,.json"
      hidden
      @change="onPick"
    />
    <button type="button" class="bulk-dropzone-browse" @click="fileInput?.click()">
      {{ t('contentSearch.bulk.input.uploadBrowse') }}
    </button>
    <p class="bulk-dropzone-hint">{{ t('contentSearch.bulk.input.uploadHint') }}</p>
  </div>
</template>

<style scoped>
.bulk-dropzone {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 0.5rem;
  padding: 2rem 1rem;
  border: 2px dashed var(--color-border);
  border-radius: 0.75rem;
  background: var(--color-surface);
  text-align: center;
  transition: all 0.2s ease;
  min-height: 11rem;
  justify-content: center;
}

.bulk-dropzone.is-drag-over {
  border-color: var(--color-brand);
  background: var(--color-brand-soft);
}

.bulk-dropzone-label {
  margin: 0;
  font-size: 0.9375rem;
  color: var(--color-text-primary);
  font-weight: 500;
}

.bulk-dropzone-browse {
  padding: 0.5rem 1.25rem;
  background: transparent;
  border: 1.5px solid var(--color-brand);
  color: var(--color-brand);
  border-radius: 0.5rem;
  font-size: 0.875rem;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s ease;
}

.bulk-dropzone-browse:hover {
  background: var(--color-brand);
  color: var(--color-text-inverse);
}

.bulk-dropzone-hint {
  margin: 0;
  font-size: 0.8125rem;
  color: var(--color-text-secondary);
}
</style>
