<template>
  <div class="bulk-import-export">
    <header class="view-header">
      <div>
        <h1 class="heading">{{ t('bulkImportExport.heading') }}</h1>
        <p class="subtitle">{{ t('bulkImportExport.subtitle') }}</p>
      </div>
    </header>

    <div class="content-grid">
      <!-- Export Section -->
      <section class="export-section">
        <div class="section-header">
          <h2>{{ t('bulkImportExport.export.title') }}</h2>
          <p class="section-description">{{ t('bulkImportExport.export.description') }}</p>
        </div>

        <div class="export-options">
          <div class="option-group">
            <label class="option-label">{{ t('bulkImportExport.export.selectType') }}</label>
            <div class="radio-group">
              <label v-for="type in exportTypes" :key="type.value" class="radio-option">
                <input
                  type="radio"
                  name="exportType"
                  :value="type.value"
                  v-model="exportForm.type"
                />
                <span>{{ type.label }}</span>
              </label>
            </div>
          </div>

          <div v-if="exportForm.type === 'content'" class="option-group">
            <label class="option-label">{{ t('bulkImportExport.export.contentFilters') }}</label>
            <div class="filter-grid">
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
            {{ isExporting ? t('bulkImportExport.export.exporting') : t('bulkImportExport.export.downloadCSV') }}
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
          <div class="option-group">
            <label class="option-label">{{ t('bulkImportExport.import.selectType') }}</label>
            <div class="radio-group">
              <label v-for="type in importTypes" :key="type.value" class="radio-option">
                <input
                  type="radio"
                  name="importType"
                  :value="type.value"
                  v-model="importForm.type"
                  @change="resetImport"
                />
                <span>{{ type.label }}</span>
              </label>
            </div>
          </div>

          <div class="file-upload">
            <label class="upload-label">
              <input
                type="file"
                accept=".csv"
                @change="handleFileSelect"
                ref="fileInput"
                class="file-input"
              />
              <span class="upload-button">
                {{ importForm.file ? importForm.file.name : t('bulkImportExport.import.chooseFile') }}
              </span>
            </label>
          </div>

          <div v-if="importForm.file" class="file-info">
            <p>{{ t('bulkImportExport.import.fileSize') }}: {{ formatFileSize(importForm.file.size) }}</p>
            <button
              type="button"
              class="btn-primary"
              :disabled="isImporting"
              @click="handleImport"
            >
              {{ isImporting ? t('bulkImportExport.import.importing') : t('bulkImportExport.import.uploadCSV') }}
            </button>
          </div>
        </div>

        <div v-if="importError" class="error-message" role="alert">
          {{ importError }}
        </div>
        <div v-if="importSuccess" class="success-message" role="status">
          {{ importSuccess }}
        </div>

        <div v-if="importResults.length > 0" class="import-results">
          <h3>{{ t('bulkImportExport.import.results') }}</h3>
          <div class="results-summary">
            <p>{{ t('bulkImportExport.import.totalProcessed') }}: {{ importResults.length }}</p>
            <p>{{ t('bulkImportExport.import.successful') }}: {{ successCount }}</p>
            <p v-if="errorCount > 0" class="error-count">{{ t('bulkImportExport.import.failed') }}: {{ errorCount }}</p>
          </div>
          <div class="results-table-container">
            <table class="results-table">
              <thead>
                <tr>
                  <th>{{ t('bulkImportExport.import.row') }}</th>
                  <th>{{ t('bulkImportExport.import.status') }}</th>
                  <th>{{ t('bulkImportExport.import.message') }}</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(result, idx) in importResults" :key="idx" :class="{ 'error-row': !result.success }">
                  <td>{{ result.row }}</td>
                  <td>
                    <span :class="['status-badge', result.success ? 'success' : 'error']">
                      {{ result.success ? t('bulkImportExport.import.statusSuccess') : t('bulkImportExport.import.statusError') }}
                    </span>
                  </td>
                  <td>{{ result.message }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      </section>
    </div>

    <!-- Template Download Links -->
    <section class="templates-section">
      <h3>{{ t('bulkImportExport.templates.title') }}</h3>
      <p>{{ t('bulkImportExport.templates.description') }}</p>
      <div class="template-links">
        <button
          v-for="template in templates"
          :key="template.type"
          type="button"
          class="btn-secondary"
          @click="downloadTemplate(template.type)"
        >
          {{ template.label }}
        </button>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue';
import { useI18n } from 'vue-i18n';

const { t } = useI18n();

interface ImportResult {
  row: number;
  success: boolean;
  message: string;
}

const exportForm = ref({
  type: 'content' as 'content' | 'categories',
  includeChannels: true,
  includePlaylists: true,
  includeVideos: true
});

const importForm = ref({
  type: 'channels' as 'channels' | 'categories',
  file: null as File | null
});

const isExporting = ref(false);
const isImporting = ref(false);
const exportError = ref('');
const exportSuccess = ref('');
const importError = ref('');
const importSuccess = ref('');
const importResults = ref<ImportResult[]>([]);
const fileInput = ref<HTMLInputElement | null>(null);

const exportTypes = computed(() => [
  { value: 'content', label: t('bulkImportExport.export.types.content') },
  { value: 'categories', label: t('bulkImportExport.export.types.categories') }
]);

const importTypes = computed(() => [
  { value: 'channels', label: t('bulkImportExport.import.types.channels') },
  { value: 'categories', label: t('bulkImportExport.import.types.categories') }
]);

const templates = computed(() => [
  { type: 'channels', label: t('bulkImportExport.templates.channels') },
  { type: 'categories', label: t('bulkImportExport.templates.categories') }
]);

const successCount = computed(() => importResults.value.filter(r => r.success).length);
const errorCount = computed(() => importResults.value.filter(r => !r.success).length);

function handleFileSelect(event: Event) {
  const target = event.target as HTMLInputElement;
  if (target.files && target.files.length > 0) {
    importForm.value.file = target.files[0];
    importError.value = '';
    importSuccess.value = '';
  }
}

function resetImport() {
  importForm.value.file = null;
  importError.value = '';
  importSuccess.value = '';
  importResults.value = [];
  if (fileInput.value) {
    fileInput.value.value = '';
  }
}

function formatFileSize(bytes: number): string {
  if (bytes < 1024) return bytes + ' B';
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(2) + ' KB';
  return (bytes / (1024 * 1024)).toFixed(2) + ' MB';
}

async function handleExport() {
  exportError.value = '';
  exportSuccess.value = '';
  isExporting.value = true;

  try {
    // Simulate export logic - replace with actual API call
    await new Promise(resolve => setTimeout(resolve, 1500));

    const csvData = generateCSVData();
    const blob = new Blob([csvData], { type: 'text/csv;charset=utf-8;' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = `${exportForm.value.type}-export-${new Date().toISOString().split('T')[0]}.csv`;
    link.click();
    URL.revokeObjectURL(url);

    exportSuccess.value = t('bulkImportExport.export.success');
  } catch (error) {
    exportError.value = t('bulkImportExport.export.error');
  } finally {
    isExporting.value = false;
  }
}

function generateCSVData(): string {
  if (exportForm.value.type === 'categories') {
    return 'id,name,parentId,description\n1,Islamic Studies,,Learn about Islam\n2,Quran Recitation,1,Beautiful Quran recitations';
  } else {
    let headers = '';
    let rows = '';

    if (exportForm.value.includeChannels) {
      headers = 'type,youtubeId,title,description,categoryIds\n';
      rows += 'channel,UCxyz123,Example Channel,A great channel,1;2\n';
    }
    if (exportForm.value.includePlaylists) {
      if (!headers) headers = 'type,youtubeId,title,description,categoryIds\n';
      rows += 'playlist,PLabc456,Example Playlist,A helpful playlist,1\n';
    }
    if (exportForm.value.includeVideos) {
      if (!headers) headers = 'type,youtubeId,title,description,categoryIds\n';
      rows += 'video,VIDdef789,Example Video,An informative video,2\n';
    }

    return headers + rows;
  }
}

async function handleImport() {
  if (!importForm.value.file) return;

  importError.value = '';
  importSuccess.value = '';
  importResults.value = [];
  isImporting.value = true;

  try {
    const text = await importForm.value.file.text();
    const lines = text.split('\n').filter(line => line.trim());

    if (lines.length < 2) {
      throw new Error(t('bulkImportExport.import.errorEmptyFile'));
    }

    const headers = lines[0].split(',').map(h => h.trim());
    const results: ImportResult[] = [];

    // Validate and process each row
    for (let i = 1; i < lines.length; i++) {
      const row = i + 1;
      const values = lines[i].split(',').map(v => v.trim());

      try {
        validateRow(headers, values, importForm.value.type);
        results.push({
          row,
          success: true,
          message: t('bulkImportExport.import.rowSuccess')
        });
      } catch (error) {
        results.push({
          row,
          success: false,
          message: error instanceof Error ? error.message : t('bulkImportExport.import.rowError')
        });
      }
    }

    importResults.value = results;

    const successCount = results.filter(r => r.success).length;
    importSuccess.value = t('bulkImportExport.import.importComplete', { count: successCount });
  } catch (error) {
    importError.value = error instanceof Error ? error.message : t('bulkImportExport.import.error');
  } finally {
    isImporting.value = false;
  }
}

function validateRow(headers: string[], values: string[], type: string) {
  if (type === 'channels') {
    const requiredFields = ['youtubeId', 'title'];
    for (const field of requiredFields) {
      const idx = headers.indexOf(field);
      if (idx === -1) throw new Error(`Missing required column: ${field}`);
      if (!values[idx]) throw new Error(`Missing ${field} value`);
    }

    // Validate YouTube ID format
    const youtubeIdIdx = headers.indexOf('youtubeId');
    if (values[youtubeIdIdx] && !/^UC[a-zA-Z0-9_-]{22}$/.test(values[youtubeIdIdx])) {
      throw new Error('Invalid YouTube channel ID format');
    }
  } else if (type === 'categories') {
    const requiredFields = ['name'];
    for (const field of requiredFields) {
      const idx = headers.indexOf(field);
      if (idx === -1) throw new Error(`Missing required column: ${field}`);
      if (!values[idx]) throw new Error(`Missing ${field} value`);
    }
  }
}

function downloadTemplate(type: string) {
  let csvContent = '';

  if (type === 'channels') {
    csvContent = 'youtubeId,title,description,categoryIds\nUCxyz123abc,Example Channel,Description here,1;2';
  } else if (type === 'categories') {
    csvContent = 'name,parentId,description\nIslamic Studies,,Learn about Islam\nQuran Recitation,1,Beautiful Quran recitations';
  }

  const blob = new Blob([csvContent], { type: 'text/csv;charset=utf-8;' });
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = `${type}-template.csv`;
  link.click();
  URL.revokeObjectURL(url);
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
  font-weight: 700;
  color: var(--color-text-primary);
  margin: 0 0 0.5rem 0;
}

.subtitle {
  font-size: 1rem;
  color: var(--color-text-secondary);
  margin: 0;
}

.content-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(500px, 1fr));
  gap: 2rem;
  margin-bottom: 2rem;
}

.export-section,
.import-section {
  background: var(--color-background-secondary);
  border: 1px solid var(--color-border);
  border-radius: 0.5rem;
  padding: 1.5rem;
}

.section-header {
  margin-bottom: 1.5rem;
}

.section-header h2 {
  font-size: 1.25rem;
  font-weight: 600;
  color: var(--color-text-primary);
  margin: 0 0 0.5rem 0;
}

.section-description {
  font-size: 0.875rem;
  color: var(--color-text-secondary);
  margin: 0;
}

.option-group {
  margin-bottom: 1.5rem;
}

.option-label {
  display: block;
  font-size: 0.875rem;
  font-weight: 600;
  color: var(--color-text-primary);
  margin-bottom: 0.75rem;
}

.radio-group {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.radio-option,
.checkbox-option {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  cursor: pointer;
}

.filter-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: 0.75rem;
}

.file-upload {
  margin-bottom: 1rem;
}

.upload-label {
  display: block;
  cursor: pointer;
}

.file-input {
  display: none;
}

.upload-button {
  display: inline-block;
  padding: 0.75rem 1.25rem;
  background: var(--color-background-tertiary);
  border: 2px dashed var(--color-border);
  border-radius: 0.5rem;
  color: var(--color-text-primary);
  font-size: 0.875rem;
  transition: all 0.2s;
}

.upload-button:hover {
  border-color: var(--color-primary);
  background: var(--color-primary-light);
}

.file-info {
  padding: 1rem;
  background: var(--color-background-tertiary);
  border-radius: 0.5rem;
  margin-bottom: 1rem;
}

.file-info p {
  margin: 0 0 0.75rem 0;
  font-size: 0.875rem;
  color: var(--color-text-secondary);
}

.btn-primary {
  padding: 0.75rem 1.5rem;
  background: var(--color-primary);
  color: white;
  border: none;
  border-radius: 0.5rem;
  font-weight: 600;
  cursor: pointer;
  transition: background 0.2s;
}

.btn-primary:hover:not(:disabled) {
  background: var(--color-primary-dark);
}

.btn-primary:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.btn-secondary {
  padding: 0.75rem 1.5rem;
  background: var(--color-background-tertiary);
  color: var(--color-text-primary);
  border: 1px solid var(--color-border);
  border-radius: 0.5rem;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s;
}

.btn-secondary:hover {
  background: var(--color-background-secondary);
  border-color: var(--color-primary);
}

.error-message {
  padding: 0.75rem 1rem;
  background: #fee;
  border: 1px solid #fcc;
  border-radius: 0.5rem;
  color: #c33;
  font-size: 0.875rem;
  margin-top: 1rem;
}

.success-message {
  padding: 0.75rem 1rem;
  background: #efe;
  border: 1px solid #cfc;
  border-radius: 0.5rem;
  color: #363;
  font-size: 0.875rem;
  margin-top: 1rem;
}

.import-results {
  margin-top: 2rem;
  padding-top: 2rem;
  border-top: 1px solid var(--color-border);
}

.import-results h3 {
  font-size: 1.125rem;
  font-weight: 600;
  margin: 0 0 1rem 0;
}

.results-summary {
  margin-bottom: 1rem;
  font-size: 0.875rem;
}

.results-summary p {
  margin: 0.25rem 0;
}

.error-count {
  color: #c33;
  font-weight: 600;
}

.results-table-container {
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
  text-align: start;
  border-bottom: 1px solid var(--color-border);
}

.results-table th {
  font-weight: 600;
  background: var(--color-background-tertiary);
}

.error-row {
  background: #fff5f5;
}

.status-badge {
  display: inline-block;
  padding: 0.25rem 0.5rem;
  border-radius: 0.25rem;
  font-size: 0.75rem;
  font-weight: 600;
}

.status-badge.success {
  background: #d4edda;
  color: #155724;
}

.status-badge.error {
  background: #f8d7da;
  color: #721c24;
}

.templates-section {
  background: var(--color-background-secondary);
  border: 1px solid var(--color-border);
  border-radius: 0.5rem;
  padding: 1.5rem;
}

.templates-section h3 {
  font-size: 1.125rem;
  font-weight: 600;
  margin: 0 0 0.5rem 0;
}

.templates-section p {
  font-size: 0.875rem;
  color: var(--color-text-secondary);
  margin: 0 0 1rem 0;
}

.template-links {
  display: flex;
  flex-wrap: wrap;
  gap: 0.75rem;
}

@media (max-width: 768px) {
  .bulk-import-export {
    padding: 1rem;
  }

  .content-grid {
    grid-template-columns: 1fr;
  }

  .filter-grid {
    grid-template-columns: 1fr;
  }
}

/* RTL Support */
[dir='rtl'] .radio-option,
[dir='rtl'] .checkbox-option {
  flex-direction: row-reverse;
}
</style>
