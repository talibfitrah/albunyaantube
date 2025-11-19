<template>
  <div class="bulk-import-export">
    <header class="view-header">
      <div>
        <h1 class="heading">{{ t('bulkImportExport.heading') }}</h1>
        <p class="subtitle">{{ t('bulkImportExport.subtitle') }}</p>
      </div>
    </header>

    <div class="content-grid">
      <!-- Format Selection -->
      <section class="format-section">
        <div class="section-header">
          <h2>{{ t('bulkImportExport.format.title') }}</h2>
          <p class="section-description">{{ t('bulkImportExport.format.description') }}</p>
        </div>

        <div class="format-options">
          <label class="format-card" :class="{ selected: selectedFormat === 'simple' }">
            <input
              type="radio"
              name="format"
              value="simple"
              v-model="selectedFormat"
            />
            <div class="format-content">
              <h3>{{ t('bulkImportExport.format.simple.title') }}</h3>
              <p>{{ t('bulkImportExport.format.simple.description') }}</p>
              <ul class="format-features">
                <li>{{ t('bulkImportExport.format.simple.feature1') }}</li>
                <li>{{ t('bulkImportExport.format.simple.feature2') }}</li>
                <li>{{ t('bulkImportExport.format.simple.feature3') }}</li>
              </ul>
            </div>
          </label>

          <label class="format-card" :class="{ selected: selectedFormat === 'full' }">
            <input
              type="radio"
              name="format"
              value="full"
              v-model="selectedFormat"
            />
            <div class="format-content">
              <h3>{{ t('bulkImportExport.format.full.title') }}</h3>
              <p>{{ t('bulkImportExport.format.full.description') }}</p>
              <ul class="format-features">
                <li>{{ t('bulkImportExport.format.full.feature1') }}</li>
                <li>{{ t('bulkImportExport.format.full.feature2') }}</li>
                <li>{{ t('bulkImportExport.format.full.feature3') }}</li>
              </ul>
            </div>
          </label>
        </div>
      </section>

      <!-- Export Section -->
      <section class="export-section">
        <div class="section-header">
          <h2>{{ t('bulkImportExport.export.title') }}</h2>
          <p class="section-description">{{ t('bulkImportExport.export.description') }}</p>
        </div>

        <div class="export-options">
          <div class="option-group">
            <label class="option-label">{{ t('bulkImportExport.export.contentFilters') }}</label>
            <div class="filter-grid">
              <label v-if="selectedFormat === 'full'" class="checkbox-option">
                <input type="checkbox" v-model="exportForm.includeCategories" />
                <span>{{ t('bulkImportExport.export.includeCategories') }}</span>
              </label>
              <label class="checkbox-option">
                <input type="checkbox" v-model="exportForm.includeChannels" />
                <span>{{ t('bulkImportExport.export.includeChannels') }}</span>
              </label>
              <label class="checkbox-option">
                <input type="checkbox" v-model="exportForm.includePlaylists" />
                <span>{{ t('bulkImportExport.export.includePlaylists') }}</span>
              </label>
              <label class="checkbox-option">
                <input type="checkbox" v-model="exportForm.includeVideos" />
                <span>{{ t('bulkImportExport.export.includeVideos') }}</span>
              </label>
            </div>
          </div>

          <button
            type="button"
            class="btn-primary"
            :disabled="isExporting"
            @click="handleExport"
          >
            {{ isExporting ? t('bulkImportExport.export.exporting') : t('bulkImportExport.export.download') }}
          </button>
        </div>

        <div v-if="exportError" class="error-message" role="alert">
          {{ exportError }}
        </div>
        <div v-if="exportSuccess" class="success-message" role="status">
          {{ exportSuccess }}
        </div>
      </section>

      <!-- Import Section -->
      <section class="import-section">
        <div class="section-header">
          <h2>{{ t('bulkImportExport.import.title') }}</h2>
          <p class="section-description">{{ t('bulkImportExport.import.description') }}</p>
        </div>

        <div class="import-options">
          <!-- Template Download (Simple Format Only) -->
          <div v-if="selectedFormat === 'simple'" class="template-download">
            <button type="button" class="btn-secondary" @click="downloadTemplate">
              {{ t('bulkImportExport.import.downloadTemplate') }}
            </button>
          </div>

          <!-- Default Status (Simple Format Only) -->
          <div v-if="selectedFormat === 'simple'" class="option-group">
            <label class="option-label">{{ t('bulkImportExport.import.defaultStatus') }}</label>
            <div class="radio-group">
              <label class="radio-option">
                <input
                  type="radio"
                  name="defaultStatus"
                  value="APPROVED"
                  v-model="importForm.defaultStatus"
                />
                <span>{{ t('bulkImportExport.import.statusApproved') }}</span>
              </label>
              <label class="radio-option">
                <input
                  type="radio"
                  name="defaultStatus"
                  value="PENDING"
                  v-model="importForm.defaultStatus"
                />
                <span>{{ t('bulkImportExport.import.statusPending') }}</span>
              </label>
            </div>
          </div>

          <!-- Merge Strategy (Full Format Only) -->
          <div v-if="selectedFormat === 'full'" class="option-group">
            <label class="option-label">{{ t('bulkImportExport.import.mergeStrategy') }}</label>
            <div class="radio-group">
              <label class="radio-option">
                <input
                  type="radio"
                  name="mergeStrategy"
                  value="SKIP"
                  v-model="importForm.mergeStrategy"
                />
                <span>{{ t('bulkImportExport.import.strategySkip') }}</span>
              </label>
              <label class="radio-option">
                <input
                  type="radio"
                  name="mergeStrategy"
                  value="OVERWRITE"
                  v-model="importForm.mergeStrategy"
                />
                <span>{{ t('bulkImportExport.import.strategyOverwrite') }}</span>
              </label>
            </div>
          </div>

          <!-- File Upload -->
          <div class="file-upload">
            <label class="upload-label">
              <input
                type="file"
                accept=".json"
                @change="handleFileSelect"
                ref="fileInput"
              />
              <span class="upload-text">
                {{ importForm.file ? importForm.file.name : t('bulkImportExport.import.selectFile') }}
              </span>
            </label>
            <span v-if="importForm.file" class="file-size">
              {{ formatFileSize(importForm.file.size) }}
            </span>
          </div>

          <!-- Action Buttons -->
          <div class="import-actions">
            <button
              type="button"
              class="btn-secondary"
              :disabled="!importForm.file || isValidating"
              @click="handleValidate"
            >
              {{ isValidating ? t('bulkImportExport.import.validating') : t('bulkImportExport.import.validate') }}
            </button>
            <button
              type="button"
              class="btn-primary"
              :disabled="!importForm.file || isImporting"
              @click="handleImport"
            >
              {{ isImporting ? t('bulkImportExport.import.importing') : t('bulkImportExport.import.submit') }}
            </button>
          </div>
        </div>

        <div v-if="importError" class="error-message" role="alert">
          {{ importError }}
        </div>
        <div v-if="importSuccess" class="success-message" role="status">
          {{ importSuccess }}
        </div>

        <!-- Import Results Table -->
        <div v-if="importResults.length > 0" class="results-section">
          <div class="results-header">
            <h3>{{ t('bulkImportExport.results.title') }}</h3>
            <div class="results-summary">
              <span class="success-count">{{ successCount }} {{ t('bulkImportExport.results.successful') }}</span>
              <span class="skipped-count">{{ skippedCount }} {{ t('bulkImportExport.results.skipped') }}</span>
              <span class="error-count">{{ errorCount }} {{ t('bulkImportExport.results.failed') }}</span>
            </div>
          </div>

          <div class="results-table-wrapper">
            <table class="results-table">
              <thead>
                <tr>
                  <th>{{ t('bulkImportExport.results.youtubeId') }}</th>
                  <th>{{ t('bulkImportExport.results.title') }}</th>
                  <th>{{ t('bulkImportExport.results.type') }}</th>
                  <th>{{ t('bulkImportExport.results.status') }}</th>
                  <th>{{ t('bulkImportExport.results.reason') }}</th>
                </tr>
              </thead>
              <tbody>
                <tr
                  v-for="(result, index) in importResults"
                  :key="index"
                  :class="getResultRowClass(result)"
                >
                  <td class="youtube-id">{{ result.youtubeId }}</td>
                  <td class="title">{{ result.title }}</td>
                  <td class="type">{{ result.type }}</td>
                  <td class="status">
                    <span :class="`status-badge status-${result.status.toLowerCase()}`">
                      {{ result.status }}
                    </span>
                  </td>
                  <td class="reason">{{ result.errorReason || '-' }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import * as importExportService from '@/services/importExportService'
import type { SimpleImportItemResult } from '@/services/importExportService'

const { t } = useI18n()

// Format selection
const selectedFormat = ref<'simple' | 'full'>('simple')

// Export form
const exportForm = ref({
  includeCategories: true,
  includeChannels: true,
  includePlaylists: true,
  includeVideos: true
})

// Import form
const importForm = ref({
  file: null as File | null,
  defaultStatus: 'APPROVED' as 'APPROVED' | 'PENDING',
  mergeStrategy: 'SKIP' as 'SKIP' | 'OVERWRITE' | 'MERGE'
})

// State
const isExporting = ref(false)
const isImporting = ref(false)
const isValidating = ref(false)
const exportError = ref('')
const exportSuccess = ref('')
const importError = ref('')
const importSuccess = ref('')
const importResults = ref<SimpleImportItemResult[]>([])
const fileInput = ref<HTMLInputElement | null>(null)

// Computed
const successCount = computed(() => importResults.value.filter(r => r.status === 'SUCCESS').length)
const skippedCount = computed(() => importResults.value.filter(r => r.status === 'SKIPPED').length)
const errorCount = computed(() => importResults.value.filter(r => r.status === 'FAILED').length)

// Methods
function handleFileSelect(event: Event) {
  const target = event.target as HTMLInputElement
  if (target.files && target.files.length > 0) {
    importForm.value.file = target.files[0]
    importError.value = ''
    importSuccess.value = ''
    importResults.value = []
  }
}

function formatFileSize(bytes: number): string {
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(2) + ' KB'
  return (bytes / (1024 * 1024)).toFixed(2) + ' MB'
}

function getResultRowClass(result: SimpleImportItemResult): string {
  return `result-${result.status.toLowerCase()}`
}

function downloadTemplate() {
  const blob = importExportService.generateSimpleFormatTemplate()
  importExportService.downloadBlob(blob, 'albunyaan-import-template.json')
}

async function handleExport() {
  exportError.value = ''
  exportSuccess.value = ''
  isExporting.value = true

  try {
    let blob: Blob

    if (selectedFormat.value === 'simple') {
      blob = await importExportService.exportSimple({
        includeChannels: exportForm.value.includeChannels,
        includePlaylists: exportForm.value.includePlaylists,
        includeVideos: exportForm.value.includeVideos
      })
    } else {
      blob = await importExportService.exportFull({
        includeCategories: exportForm.value.includeCategories,
        includeChannels: exportForm.value.includeChannels,
        includePlaylists: exportForm.value.includePlaylists,
        includeVideos: exportForm.value.includeVideos
      })
    }

    const filename = selectedFormat.value === 'simple'
      ? `albunyaan-export-simple-${new Date().toISOString().split('T')[0]}.json`
      : `albunyaan-export-full-${new Date().toISOString().split('T')[0]}.json`

    importExportService.downloadBlob(blob, filename)
    exportSuccess.value = t('bulkImportExport.export.success')
  } catch (error: any) {
    console.error('Export failed:', error)
    exportError.value = error.response?.data?.message || t('bulkImportExport.export.error')
  } finally {
    isExporting.value = false
  }
}

async function handleValidate() {
  if (!importForm.value.file) return

  importError.value = ''
  importSuccess.value = ''
  importResults.value = []
  isValidating.value = true

  try {
    let response

    if (selectedFormat.value === 'simple') {
      response = await importExportService.validateSimple(importForm.value.file)
      importResults.value = response.results
    } else {
      const fullResponse = await importExportService.validateFull(importForm.value.file)
      // Convert full format errors to results format
      importResults.value = fullResponse.errors.map(err => ({
        youtubeId: err.id,
        title: err.id,
        type: err.type as any,
        status: 'FAILED' as const,
        errorReason: err.error
      }))
    }

    importSuccess.value = t('bulkImportExport.import.validationComplete')
  } catch (error: any) {
    console.error('Validation failed:', error)
    importError.value = error.response?.data?.message || t('bulkImportExport.import.validationError')
  } finally {
    isValidating.value = false
  }
}

async function handleImport() {
  if (!importForm.value.file) return

  importError.value = ''
  importSuccess.value = ''
  importResults.value = []
  isImporting.value = true

  try {
    let response

    if (selectedFormat.value === 'simple') {
      response = await importExportService.importSimple(
        importForm.value.file,
        importForm.value.defaultStatus
      )
      importResults.value = response.results

      const { counts } = response
      const totalImported = counts.channelsImported + counts.playlistsImported + counts.videosImported
      const totalSkipped = counts.channelsSkipped + counts.playlistsSkipped + counts.videosSkipped

      importSuccess.value = t('bulkImportExport.import.successSimple', {
        imported: totalImported,
        skipped: totalSkipped,
        errors: counts.totalErrors
      })
    } else {
      const fullResponse = await importExportService.importFull(
        importForm.value.file,
        importForm.value.mergeStrategy
      )

      // Convert full format errors to results format
      importResults.value = fullResponse.errors.map(err => ({
        youtubeId: err.id,
        title: err.id,
        type: err.type as any,
        status: 'FAILED' as const,
        errorReason: err.error
      }))

      const { counts } = fullResponse
      const totalImported = counts.categoriesImported + counts.channelsImported +
                           counts.playlistsImported + counts.videosImported
      const totalSkipped = counts.categoriesSkipped + counts.channelsSkipped +
                          counts.playlistsSkipped + counts.videosSkipped

      importSuccess.value = t('bulkImportExport.import.successFull', {
        imported: totalImported,
        skipped: totalSkipped,
        errors: counts.totalErrors
      })
    }

    // Reset file input
    if (fileInput.value) {
      fileInput.value.value = ''
    }
    importForm.value.file = null
  } catch (error: any) {
    console.error('Import failed:', error)
    importError.value = error.response?.data?.message || t('bulkImportExport.import.error')
  } finally {
    isImporting.value = false
  }
}
</script>

<style scoped>
.bulk-import-export {
  padding: 2rem;
  max-width: 1400px;
  margin: 0 auto;
}

.view-header {
  margin-bottom: 2rem;
}

.heading {
  font-size: 1.875rem;
  font-weight: 600;
  margin-bottom: 0.5rem;
}

.subtitle {
  color: var(--color-text-secondary);
}

.content-grid {
  display: grid;
  gap: 2rem;
}

.format-section,
.export-section,
.import-section {
  background: var(--color-background-soft);
  border-radius: 8px;
  padding: 1.5rem;
}

.section-header {
  margin-bottom: 1.5rem;
}

.section-header h2 {
  font-size: 1.25rem;
  font-weight: 600;
  margin-bottom: 0.5rem;
}

.section-description {
  color: var(--color-text-secondary);
  font-size: 0.875rem;
}

.format-options {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(300px, 1fr));
  gap: 1rem;
}

.format-card {
  border: 2px solid var(--color-border);
  border-radius: 8px;
  padding: 1.5rem;
  cursor: pointer;
  transition: all 0.2s;
}

.format-card:hover {
  border-color: var(--color-primary);
}

.format-card.selected {
  border-color: var(--color-primary);
  background: var(--color-primary-soft);
}

.format-card input[type="radio"] {
  display: none;
}

.format-content h3 {
  font-size: 1.125rem;
  font-weight: 600;
  margin-bottom: 0.5rem;
}

.format-content p {
  color: var(--color-text-secondary);
  font-size: 0.875rem;
  margin-bottom: 1rem;
}

.format-features {
  list-style: none;
  padding: 0;
  margin: 0;
}

.format-features li {
  padding: 0.25rem 0;
  font-size: 0.875rem;
  color: var(--color-text-secondary);
}

.format-features li::before {
  content: "✓ ";
  color: var(--color-success);
  font-weight: bold;
  margin-right: 0.5rem;
}

.option-group {
  margin-bottom: 1.5rem;
}

.option-label {
  display: block;
  font-weight: 500;
  margin-bottom: 0.75rem;
}

.radio-group {
  display: flex;
  gap: 1rem;
  flex-wrap: wrap;
}

.radio-option {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  cursor: pointer;
}

.filter-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 0.75rem;
}

.checkbox-option {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  cursor: pointer;
}

.file-upload {
  margin: 1.5rem 0;
}

.upload-label {
  display: inline-block;
  padding: 0.75rem 1.5rem;
  border: 2px dashed var(--color-border);
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.2s;
}

.upload-label:hover {
  border-color: var(--color-primary);
  background: var(--color-primary-soft);
}

.upload-label input[type="file"] {
  display: none;
}

.file-size {
  margin-left: 1rem;
  color: var(--color-text-secondary);
  font-size: 0.875rem;
}

.import-actions {
  display: flex;
  gap: 1rem;
}

.btn-primary,
.btn-secondary {
  padding: 0.75rem 1.5rem;
  border-radius: 6px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s;
  border: none;
}

.btn-primary {
  background: var(--color-brand);
  color: var(--color-text-inverse);
  font-weight: 600;
  box-shadow: 0 2px 4px rgba(0, 0, 0, 0.1);
}

.btn-primary:hover:not(:disabled) {
  background: var(--color-accent);
  box-shadow: 0 4px 8px rgba(0, 0, 0, 0.15);
  transform: translateY(-1px);
}

.btn-secondary {
  background: var(--color-surface-alt);
  color: var(--color-text-primary);
  border: 1px solid var(--color-border);
}

.btn-secondary:hover:not(:disabled) {
  background: var(--color-surface);
  border-color: var(--color-brand);
}

.btn-primary:disabled,
.btn-secondary:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.error-message,
.success-message {
  margin-top: 1rem;
  padding: 0.75rem 1rem;
  border-radius: 6px;
}

.error-message {
  background: var(--color-danger-soft);
  color: var(--color-danger);
  border: 1px solid var(--color-danger);
}

.success-message {
  background: var(--color-success-soft);
  color: var(--color-success);
  border: 1px solid var(--color-success);
}

.results-section {
  margin-top: 2rem;
  border-top: 1px solid var(--color-border);
  padding-top: 1.5rem;
}

.results-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 1rem;
}

.results-summary {
  display: flex;
  gap: 1.5rem;
  font-size: 0.875rem;
}

.success-count {
  color: var(--color-success);
  font-weight: 500;
}

.skipped-count {
  color: var(--color-warning);
  font-weight: 500;
}

.error-count {
  color: var(--color-danger);
  font-weight: 500;
}

.results-table-wrapper {
  overflow-x: auto;
}

.results-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 0.875rem;
}

.results-table th,
.results-table td {
  padding: 0.75rem;
  text-align: left;
  border-bottom: 1px solid var(--color-border);
}

.results-table th {
  font-weight: 600;
  background: var(--color-background-mute);
}

.result-success {
  background: var(--color-success-soft);
}

.result-skipped {
  background: var(--color-warning-soft);
}

.result-failed {
  background: var(--color-danger-soft);
}

.status-badge {
  display: inline-block;
  padding: 0.25rem 0.5rem;
  border-radius: 4px;
  font-size: 0.75rem;
  font-weight: 500;
}

.status-success {
  background: var(--color-success);
  color: white;
}

.status-skipped {
  background: var(--color-warning);
  color: white;
}

.status-failed {
  background: var(--color-danger);
  color: white;
}

.template-download {
  margin-bottom: 1.5rem;
}
</style>
