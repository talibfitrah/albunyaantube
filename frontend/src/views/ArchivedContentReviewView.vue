<template>
  <div class="view-container">
    <header class="page-header">
      <div class="header-content">
        <div>
          <h1>{{ t('archivedContent.heading') }}</h1>
          <p>{{ t('archivedContent.subtitle') }}</p>
        </div>
        <div class="header-actions">
          <button
            type="button"
            class="validate-button"
            :disabled="isValidating || retryCountdown > 0"
            @click="handleValidateAll"
          >
            <template v-if="retryCountdown > 0">
              {{ t('archivedContent.validation.retryIn', { seconds: retryCountdown }) }}
            </template>
            <template v-else>
              {{ isValidating ? t('archivedContent.validation.running') : t('archivedContent.validation.runAll') }}
            </template>
          </button>
        </div>
      </div>
    </header>

    <!-- Counts Summary -->
    <div class="counts-summary" v-if="counts">
      <div class="count-card">
        <span class="count-value">{{ counts.channels }}</span>
        <span class="count-label">{{ t('archivedContent.tabs.channels') }}</span>
      </div>
      <div class="count-card">
        <span class="count-value">{{ counts.playlists }}</span>
        <span class="count-label">{{ t('archivedContent.tabs.playlists') }}</span>
      </div>
      <div class="count-card">
        <span class="count-value">{{ counts.videos }}</span>
        <span class="count-label">{{ t('archivedContent.tabs.videos') }}</span>
      </div>
      <div class="count-card total">
        <span class="count-value">{{ counts.total }}</span>
        <span class="count-label">{{ t('archivedContent.counts.total', { count: '' }).replace('{count}', '').trim() }}</span>
      </div>
    </div>

    <!-- Validation Progress Bar -->
    <div v-if="isValidating && validationRun" class="progress-panel">
      <div class="progress-header">
        <span class="progress-title">{{ t('archivedContent.validation.inProgress') }}</span>
        <span class="progress-phase">{{ progressPhaseLabel }}</span>
      </div>
      <div class="progress-bar-container">
        <div
          class="progress-bar-fill"
          :style="{ width: `${progressPercent}%` }"
          role="progressbar"
          :aria-valuenow="progressPercent"
          aria-valuemin="0"
          aria-valuemax="100"
        ></div>
      </div>
      <div class="progress-footer">
        <span class="progress-percent">{{ progressPercent }}%</span>
        <span class="progress-details">{{ progressDetails }}</span>
      </div>
    </div>

    <!-- Error Message -->
    <div v-if="errorMessage" class="error-panel" role="alert">
      <p>{{ errorMessage }}</p>
      <button type="button" class="retry-button" @click="loadData">
        {{ t('common.retry') }}
      </button>
    </div>

    <!-- Success Message -->
    <div v-if="successMessage" class="success-panel" role="status">
      <p>{{ successMessage }}</p>
    </div>

    <!-- Tabs -->
    <div class="tabs-container">
      <div class="tabs" role="tablist">
        <button
          v-for="tab in tabs"
          :key="tab.id"
          type="button"
          role="tab"
          :aria-selected="activeTab === tab.id"
          :class="['tab', { active: activeTab === tab.id }]"
          @click="activeTab = tab.id"
        >
          {{ tab.label }}
          <span class="tab-count" v-if="getCountForTab(tab.id) > 0">{{ getCountForTab(tab.id) }}</span>
        </button>
      </div>

      <!-- Bulk Actions -->
      <div class="bulk-actions" v-if="selectedIds.length > 0">
        <span class="selection-count">{{ selectedIds.length }} selected</span>
        <button type="button" class="action-button restore" @click="handleBulkRestore">
          {{ t('archivedContent.actions.restoreSelected') }}
        </button>
        <button type="button" class="action-button delete" @click="handleBulkDelete">
          {{ t('archivedContent.actions.deleteSelected') }}
        </button>
      </div>
    </div>

    <!-- Loading State -->
    <div v-if="isLoading" class="loading-state">
      {{ t('common.loading') }}
    </div>

    <!-- Empty State -->
    <div v-else-if="currentContent.length === 0" class="empty-state">
      <div class="empty-icon">📦</div>
      <h3>{{ t('archivedContent.empty.title') }}</h3>
      <p>{{ t('archivedContent.empty.description') }}</p>
    </div>

    <!-- Content Table (Desktop) -->
    <div v-else class="content-table-wrapper">
      <table class="content-table desktop-only">
        <thead>
          <tr>
            <th class="checkbox-col">
              <input
                ref="selectAllDesktopRef"
                type="checkbox"
                :checked="isAllSelected"
                @change="toggleSelectAll"
                :aria-label="isAllSelected ? t('archivedContent.actions.deselectAll') : t('archivedContent.actions.selectAll')"
              />
            </th>
            <th class="thumbnail-col">{{ t('archivedContent.table.thumbnail') }}</th>
            <th class="title-col">{{ t('archivedContent.table.title') }}</th>
            <th class="youtube-id-col">{{ t('archivedContent.table.youtubeId') }}</th>
            <th class="category-col">{{ t('archivedContent.table.category') }}</th>
            <th class="metadata-col">{{ t('archivedContent.table.metadata') }}</th>
            <th class="actions-col">{{ t('archivedContent.table.actions') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in currentContent" :key="item.id" :class="{ selected: selectedIds.includes(item.id) }">
            <td class="checkbox-col">
              <input
                type="checkbox"
                :checked="selectedIds.includes(item.id)"
                @change="toggleSelection(item.id)"
                :aria-label="`Select ${item.title}`"
              />
            </td>
            <td class="thumbnail-col">
              <img
                v-if="item.thumbnailUrl"
                :src="item.thumbnailUrl"
                :alt="item.title"
                class="thumbnail"
                loading="lazy"
              />
              <div v-else class="thumbnail-placeholder">
                {{ getTypeIcon(item.type) }}
              </div>
            </td>
            <td class="title-col">
              <span class="item-title">{{ item.title }}</span>
            </td>
            <td class="youtube-id-col">
              <code class="youtube-id">{{ item.youtubeId }}</code>
            </td>
            <td class="category-col">
              <span v-if="item.category" class="category-badge">{{ item.category }}</span>
              <span v-else class="no-category">—</span>
            </td>
            <td class="metadata-col">
              <span class="metadata">{{ item.metadata || '—' }}</span>
            </td>
            <td class="actions-col">
              <div class="action-buttons">
                <button
                  type="button"
                  class="icon-button restore"
                  @click="handleRestore(item)"
                  :title="t('archivedContent.actions.restore')"
                >
                  ↩
                </button>
                <button
                  type="button"
                  class="icon-button delete"
                  @click="handleDelete(item)"
                  :title="t('archivedContent.actions.delete')"
                >
                  🗑
                </button>
              </div>
            </td>
          </tr>
        </tbody>
      </table>

      <!-- Content Cards (Mobile) -->
      <div class="content-cards mobile-only">
        <div class="select-all-row">
          <label class="select-all-label">
            <input
              ref="selectAllMobileRef"
              type="checkbox"
              :checked="isAllSelected"
              @change="toggleSelectAll"
            />
            <span>{{ isAllSelected ? t('archivedContent.actions.deselectAll') : t('archivedContent.actions.selectAll') }}</span>
          </label>
        </div>
        <div
          v-for="item in currentContent"
          :key="item.id"
          :class="['content-card', { selected: selectedIds.includes(item.id) }]"
        >
          <div class="card-header">
            <input
              type="checkbox"
              :checked="selectedIds.includes(item.id)"
              @change="toggleSelection(item.id)"
            />
            <img
              v-if="item.thumbnailUrl"
              :src="item.thumbnailUrl"
              :alt="item.title"
              class="card-thumbnail"
              loading="lazy"
            />
            <div v-else class="card-thumbnail-placeholder">
              {{ getTypeIcon(item.type) }}
            </div>
          </div>
          <div class="card-body">
            <h4 class="card-title">{{ item.title }}</h4>
            <p class="card-youtube-id">
              <code>{{ item.youtubeId }}</code>
            </p>
            <p v-if="item.category" class="card-category">
              <span class="category-badge">{{ item.category }}</span>
            </p>
            <p v-if="item.metadata" class="card-metadata">{{ item.metadata }}</p>
          </div>
          <div class="card-actions">
            <button type="button" class="card-action-button restore" @click="handleRestore(item)">
              {{ t('archivedContent.actions.restore') }}
            </button>
            <button type="button" class="card-action-button delete" @click="handleDelete(item)">
              {{ t('archivedContent.actions.delete') }}
            </button>
          </div>
        </div>
      </div>
    </div>

    <!-- Confirmation Dialog -->
    <Teleport to="body">
      <div v-if="showConfirmDialog" class="dialog-overlay" @click.self="closeDialog">
        <div class="dialog" role="dialog" :aria-labelledby="'dialog-title'">
          <h3 id="dialog-title">{{ dialogConfig.title }}</h3>
          <p>{{ dialogConfig.message }}</p>
          <div class="dialog-actions">
            <button type="button" class="dialog-button cancel" @click="closeDialog">
              {{ dialogConfig.cancelLabel }}
            </button>
            <button
              type="button"
              :class="['dialog-button', dialogConfig.confirmClass]"
              @click="confirmAction"
              :disabled="isProcessing"
            >
              {{ isProcessing ? '...' : dialogConfig.confirmLabel }}
            </button>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch, watchEffect } from 'vue';
import { useI18n } from 'vue-i18n';
import type { ArchivedContent, ArchivedCounts, ContentType, ValidationRun } from '@/types/validation';
import {
  getArchivedCounts,
  getArchivedChannels,
  getArchivedPlaylists,
  getArchivedVideos,
  deleteArchivedContent,
  restoreArchivedContent,
  validateAllContent,
  getValidationStatus
} from '@/services/contentValidation';

const { t } = useI18n();

// Template refs for indeterminate checkbox state
const selectAllDesktopRef = ref<HTMLInputElement | null>(null);
const selectAllMobileRef = ref<HTMLInputElement | null>(null);

// State
const isLoading = ref(false);
const isValidating = ref(false);
const isProcessing = ref(false);
const errorMessage = ref<string | null>(null);
const successMessage = ref<string | null>(null);
const counts = ref<ArchivedCounts | null>(null);
const channels = ref<ArchivedContent[]>([]);
const playlists = ref<ArchivedContent[]>([]);
const videos = ref<ArchivedContent[]>([]);
const activeTab = ref<ContentType>('CHANNEL');
const selectedIds = ref<string[]>([]);
const showConfirmDialog = ref(false);
const pendingAction = ref<{ action: 'delete' | 'restore'; items: ArchivedContent[] } | null>(null);

// Validation progress state
const validationRun = ref<ValidationRun | null>(null);
const pollingInterval = ref<ReturnType<typeof setInterval> | null>(null);
const pollingErrorCount = ref(0);
const MAX_POLLING_ERRORS = 5;

// Backpressure state (503 Service Unavailable handling)
const retryCountdown = ref(0);
const retryCountdownInterval = ref<ReturnType<typeof setInterval> | null>(null);

// Computed progress values
const progressPercent = computed(() => validationRun.value?.progressPercent ?? 0);
const currentPhase = computed(() => validationRun.value?.currentPhase ?? 'STARTING');
const progressPhaseLabel = computed(() => {
  switch (currentPhase.value) {
    case 'STARTING': return t('archivedContent.validation.phases.starting');
    case 'INITIALIZING': return t('archivedContent.validation.phases.initializing');
    case 'CHANNELS': return t('archivedContent.validation.phases.channels');
    case 'PLAYLISTS': return t('archivedContent.validation.phases.playlists');
    case 'VIDEOS': return t('archivedContent.validation.phases.videos');
    case 'COMPLETE': return t('archivedContent.validation.phases.complete');
    default: return currentPhase.value;
  }
});
const progressDetails = computed(() => {
  if (!validationRun.value) return '';
  const run = validationRun.value;
  const checked = run.totalChecked ?? 0;
  const total = run.totalToCheck ?? 0;
  return `${checked} / ${total}`;
});

// Tabs configuration
const tabs = computed(() => [
  { id: 'CHANNEL' as ContentType, label: t('archivedContent.tabs.channels') },
  { id: 'PLAYLIST' as ContentType, label: t('archivedContent.tabs.playlists') },
  { id: 'VIDEO' as ContentType, label: t('archivedContent.tabs.videos') }
]);

// Current content based on active tab
const currentContent = computed(() => {
  switch (activeTab.value) {
    case 'CHANNEL': return channels.value;
    case 'PLAYLIST': return playlists.value;
    case 'VIDEO': return videos.value;
    default: return [];
  }
});

// Selection state
const isAllSelected = computed(() =>
  currentContent.value.length > 0 && selectedIds.value.length === currentContent.value.length
);

const isPartiallySelected = computed(() =>
  selectedIds.value.length > 0 && selectedIds.value.length < currentContent.value.length
);

// Set indeterminate property on checkboxes (can't use :indeterminate in Vue)
watchEffect(() => {
  const indeterminate = isPartiallySelected.value;
  if (selectAllDesktopRef.value) {
    selectAllDesktopRef.value.indeterminate = indeterminate;
  }
  if (selectAllMobileRef.value) {
    selectAllMobileRef.value.indeterminate = indeterminate;
  }
});

// Dialog configuration
const dialogConfig = computed(() => {
  if (!pendingAction.value) return { title: '', message: '', confirmLabel: '', cancelLabel: '', confirmClass: '' };

  const count = pendingAction.value.items.length;
  if (pendingAction.value.action === 'delete') {
    return {
      title: t('archivedContent.confirmDelete.title'),
      message: t('archivedContent.confirmDelete.message', { count }),
      confirmLabel: t('archivedContent.confirmDelete.confirm'),
      cancelLabel: t('archivedContent.confirmDelete.cancel'),
      confirmClass: 'danger'
    };
  } else {
    return {
      title: t('archivedContent.confirmRestore.title'),
      message: t('archivedContent.confirmRestore.message', { count }),
      confirmLabel: t('archivedContent.confirmRestore.confirm'),
      cancelLabel: t('archivedContent.confirmRestore.cancel'),
      confirmClass: 'primary'
    };
  }
});

// Functions
function getCountForTab(tabId: ContentType): number {
  if (!counts.value) return 0;
  switch (tabId) {
    case 'CHANNEL': return counts.value.channels;
    case 'PLAYLIST': return counts.value.playlists;
    case 'VIDEO': return counts.value.videos;
    default: return 0;
  }
}

function getTypeIcon(type: ContentType): string {
  switch (type) {
    case 'CHANNEL': return '📺';
    case 'PLAYLIST': return '📋';
    case 'VIDEO': return '🎬';
    default: return '📦';
  }
}

function toggleSelectAll() {
  if (isAllSelected.value) {
    selectedIds.value = [];
  } else {
    selectedIds.value = currentContent.value.map(item => item.id);
  }
}

function toggleSelection(id: string) {
  const index = selectedIds.value.indexOf(id);
  if (index === -1) {
    selectedIds.value.push(id);
  } else {
    selectedIds.value.splice(index, 1);
  }
}

async function loadData() {
  isLoading.value = true;
  errorMessage.value = null;

  try {
    const [countsData, channelsData, playlistsData, videosData] = await Promise.all([
      getArchivedCounts(),
      getArchivedChannels(),
      getArchivedPlaylists(),
      getArchivedVideos()
    ]);

    counts.value = countsData;
    channels.value = channelsData;
    playlists.value = playlistsData;
    videos.value = videosData;
  } catch (err) {
    errorMessage.value = t('archivedContent.toasts.loadError');
    console.error('Failed to load archived content:', err);
  } finally {
    isLoading.value = false;
  }
}

function stopPolling() {
  if (pollingInterval.value) {
    clearInterval(pollingInterval.value);
    pollingInterval.value = null;
  }
}

function startRetryCountdown(seconds: number) {
  // Clear any existing countdown
  stopRetryCountdown();

  // Set initial countdown value
  retryCountdown.value = seconds;

  // Decrement every second
  retryCountdownInterval.value = setInterval(() => {
    retryCountdown.value--;
    if (retryCountdown.value <= 0) {
      stopRetryCountdown();
    }
  }, 1000);
}

function stopRetryCountdown() {
  if (retryCountdownInterval.value) {
    clearInterval(retryCountdownInterval.value);
    retryCountdownInterval.value = null;
  }
  retryCountdown.value = 0;
}

async function pollValidationStatus(runId: string) {
  try {
    const status = await getValidationStatus(runId);
    validationRun.value = status;
    pollingErrorCount.value = 0; // Reset error count on success

    if (status.status === 'COMPLETED' || status.status === 'FAILED') {
      stopPolling();
      isValidating.value = false;

      if (status.status === 'COMPLETED') {
        // Build success message
        let message = t('archivedContent.validation.success', {
          checked: status.totalChecked || 0,
          archived: status.totalArchived || 0
        });

        // Add per-type breakdown
        message += '\n' + t('archivedContent.validation.successDetails', {
          channelsChecked: status.channelsChecked || 0,
          channelsArchived: status.channelsArchived || 0,
          playlistsChecked: status.playlistsChecked || 0,
          playlistsArchived: status.playlistsArchived || 0,
          videosChecked: status.videosChecked || 0,
          videosArchived: status.videosArchived || 0
        });

        // Add error warning if there were transient errors
        if (status.errorCount && status.errorCount > 0) {
          message += '\n' + t('archivedContent.validation.successWithErrors', {
            errorCount: status.errorCount
          });
        }

        successMessage.value = message;
        await loadData();
        setTimeout(() => { successMessage.value = null; }, 8000);
      } else {
        errorMessage.value = t('archivedContent.validation.error');
      }

      // Clear validation run after a delay
      setTimeout(() => { validationRun.value = null; }, 3000);
    }
  } catch (err: unknown) {
    console.error('Failed to poll validation status:', err);
    pollingErrorCount.value++;

    // Check for fatal errors (404 = run not found, 410 = gone)
    const axiosError = err as { response?: { status?: number } };
    const httpStatus = axiosError?.response?.status;

    if (httpStatus === 404 || httpStatus === 410) {
      // Run not found - stop polling immediately
      stopPolling();
      isValidating.value = false;
      errorMessage.value = t('archivedContent.validation.runNotFound');
      validationRun.value = null;
    } else if (pollingErrorCount.value >= MAX_POLLING_ERRORS) {
      // Too many consecutive errors - stop polling
      stopPolling();
      isValidating.value = false;
      errorMessage.value = t('archivedContent.validation.pollingFailed');
      validationRun.value = null;
    }
    // Otherwise continue polling (transient error)
  }
}

async function handleValidateAll() {
  isValidating.value = true;
  errorMessage.value = null;
  successMessage.value = null;
  validationRun.value = null;

  try {
    const result = await validateAllContent();
    if (result.success && result.runId) {
      // Start polling for progress
      pollingInterval.value = setInterval(() => {
        pollValidationStatus(result.runId);
      }, 1500); // Poll every 1.5 seconds

      // Initial poll
      await pollValidationStatus(result.runId);
    } else {
      isValidating.value = false;
      errorMessage.value = t('archivedContent.validation.error');
    }
  } catch (err) {
    isValidating.value = false;

    // Check if it's a 503 Service Unavailable (system overloaded)
    const axiosError = err as { response?: { status?: number; data?: { error?: string; retryAfter?: number } } };
    if (axiosError?.response?.status === 503) {
      const retryAfter = axiosError.response.data?.retryAfter || 60;
      errorMessage.value = t('archivedContent.validation.systemOverloaded', { seconds: retryAfter });

      // Start countdown timer to prevent button spam
      startRetryCountdown(retryAfter);
    } else {
      errorMessage.value = t('archivedContent.validation.error');
    }

    console.error('Failed to start validation:', err);
  }
}

function handleRestore(item: ArchivedContent) {
  pendingAction.value = { action: 'restore', items: [item] };
  showConfirmDialog.value = true;
}

function handleDelete(item: ArchivedContent) {
  pendingAction.value = { action: 'delete', items: [item] };
  showConfirmDialog.value = true;
}

function handleBulkRestore() {
  const items = currentContent.value.filter(item => selectedIds.value.includes(item.id));
  pendingAction.value = { action: 'restore', items };
  showConfirmDialog.value = true;
}

function handleBulkDelete() {
  const items = currentContent.value.filter(item => selectedIds.value.includes(item.id));
  pendingAction.value = { action: 'delete', items };
  showConfirmDialog.value = true;
}

function closeDialog() {
  showConfirmDialog.value = false;
  pendingAction.value = null;
}

async function confirmAction() {
  if (!pendingAction.value) return;

  isProcessing.value = true;
  const { action, items } = pendingAction.value;
  const ids = items.map(item => item.id);
  const type = activeTab.value;

  try {
    if (action === 'delete') {
      const result = await deleteArchivedContent(type, ids);
      successMessage.value = t('archivedContent.toasts.deleteSuccess', { count: result.successCount });
    } else {
      const result = await restoreArchivedContent(type, ids);
      successMessage.value = t('archivedContent.toasts.restoreSuccess', { count: result.successCount });
    }

    // Clear selection and reload
    selectedIds.value = [];
    await loadData();
    setTimeout(() => { successMessage.value = null; }, 5000);
  } catch (err) {
    errorMessage.value = action === 'delete'
      ? t('archivedContent.toasts.deleteError')
      : t('archivedContent.toasts.restoreError');
    console.error(`${action} failed:`, err);
  } finally {
    isProcessing.value = false;
    closeDialog();
  }
}

// Clear selection when switching tabs
watch(activeTab, () => {
  selectedIds.value = [];
});

onMounted(() => {
  loadData();
});

onUnmounted(() => {
  stopPolling();
  stopRetryCountdown();
});
</script>

<style scoped>
.view-container {
  max-width: 1400px;
  margin: 0 auto;
  padding: 2rem;
}

/* Header */
.page-header {
  margin-bottom: 2rem;
}

.header-content {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 1.5rem;
  flex-wrap: wrap;
}

.page-header h1 {
  margin: 0;
  font-size: 1.75rem;
  font-weight: 700;
  color: var(--color-text-primary);
}

.page-header p {
  margin: 0.5rem 0 0;
  color: var(--color-text-secondary);
  font-size: 0.9375rem;
}

.validate-button {
  appearance: none;
  border: none;
  background: var(--color-brand);
  color: var(--color-text-inverse);
  padding: 0.75rem 1.5rem;
  border-radius: 0.5rem;
  font-weight: 600;
  font-size: 0.9375rem;
  cursor: pointer;
  transition: background 0.2s ease;
  white-space: nowrap;
  min-height: 44px;
}

.validate-button:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

/* Counts Summary */
.counts-summary {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
  gap: 1rem;
  margin-bottom: 2rem;
}

.count-card {
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: 0.75rem;
  padding: 1.25rem;
  text-align: center;
}

.count-card.total {
  background: var(--color-brand);
  border-color: var(--color-brand);
}

.count-card.total .count-value,
.count-card.total .count-label {
  color: var(--color-text-inverse);
}

.count-value {
  display: block;
  font-size: 2rem;
  font-weight: 700;
  color: var(--color-text-primary);
}

.count-label {
  display: block;
  font-size: 0.875rem;
  color: var(--color-text-secondary);
  margin-top: 0.25rem;
}

/* Error/Success Panels */
.error-panel {
  background: rgba(217, 45, 32, 0.15);
  border: 1px solid rgba(217, 45, 32, 0.35);
  border-radius: 0.75rem;
  padding: 1.25rem;
  margin-bottom: 1.5rem;
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}

.success-panel {
  background: rgba(22, 131, 90, 0.15);
  border: 1px solid rgba(22, 131, 90, 0.35);
  border-radius: 0.75rem;
  padding: 1.25rem;
  margin-bottom: 1.5rem;
  color: var(--color-text-primary);
  white-space: pre-line; /* Preserve newlines in message */
}

/* Progress Panel */
.progress-panel {
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: 0.75rem;
  padding: 1.25rem;
  margin-bottom: 1.5rem;
}

.progress-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 0.75rem;
}

.progress-title {
  font-weight: 600;
  color: var(--color-text-primary);
}

.progress-phase {
  font-size: 0.875rem;
  color: var(--color-brand);
  font-weight: 500;
}

.progress-bar-container {
  height: 8px;
  background: var(--color-surface-alt);
  border-radius: 4px;
  overflow: hidden;
  margin-bottom: 0.75rem;
}

.progress-bar-fill {
  height: 100%;
  background: var(--color-brand);
  border-radius: 4px;
  transition: width 0.3s ease;
}

.progress-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 0.875rem;
}

.progress-percent {
  font-weight: 600;
  color: var(--color-text-primary);
}

.progress-details {
  color: var(--color-text-secondary);
}

.retry-button {
  align-self: flex-start;
  appearance: none;
  border: none;
  background: var(--color-brand);
  color: var(--color-text-inverse);
  padding: 0.5rem 1rem;
  border-radius: 0.5rem;
  cursor: pointer;
  font-weight: 600;
  min-height: 44px;
}

/* Tabs */
.tabs-container {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 1rem;
  margin-bottom: 1.5rem;
  padding-bottom: 1rem;
  border-bottom: 1px solid var(--color-border);
}

.tabs {
  display: flex;
  gap: 0.5rem;
}

.tab {
  appearance: none;
  border: none;
  background: var(--color-surface);
  color: var(--color-text-secondary);
  padding: 0.75rem 1.25rem;
  border-radius: 0.5rem;
  font-weight: 500;
  font-size: 0.9375rem;
  cursor: pointer;
  transition: all 0.2s ease;
  display: flex;
  align-items: center;
  gap: 0.5rem;
  min-height: 44px;
}

.tab.active {
  background: var(--color-brand);
  color: var(--color-text-inverse);
}

.tab-count {
  background: rgba(255, 255, 255, 0.2);
  padding: 0.125rem 0.5rem;
  border-radius: 1rem;
  font-size: 0.75rem;
}

.tab.active .tab-count {
  background: rgba(255, 255, 255, 0.3);
}

/* Bulk Actions */
.bulk-actions {
  display: flex;
  align-items: center;
  gap: 0.75rem;
}

.selection-count {
  font-size: 0.875rem;
  color: var(--color-text-secondary);
}

.action-button {
  appearance: none;
  border: none;
  padding: 0.5rem 1rem;
  border-radius: 0.5rem;
  font-weight: 600;
  font-size: 0.875rem;
  cursor: pointer;
  min-height: 44px;
}

.action-button.restore {
  background: rgba(22, 131, 90, 0.15);
  color: #16835a;
}

.action-button.delete {
  background: rgba(217, 45, 32, 0.15);
  color: #d92d20;
}

/* Loading/Empty States */
.loading-state,
.empty-state {
  padding: 4rem 2rem;
  text-align: center;
  color: var(--color-text-secondary);
}

.empty-state {
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: 0.75rem;
}

.empty-icon {
  font-size: 3rem;
  margin-bottom: 1rem;
}

.empty-state h3 {
  margin: 0 0 0.5rem;
  color: var(--color-text-primary);
}

/* Table */
.content-table-wrapper {
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: 0.75rem;
  overflow: hidden;
}

.content-table {
  width: 100%;
  border-collapse: collapse;
}

.content-table th,
.content-table td {
  padding: 1rem;
  text-align: start;
  border-bottom: 1px solid var(--color-border);
}

.content-table th {
  background: var(--color-surface-alt);
  font-weight: 600;
  font-size: 0.8125rem;
  color: var(--color-text-secondary);
  text-transform: uppercase;
  letter-spacing: 0.05em;
}

.content-table tr:last-child td {
  border-bottom: none;
}

.content-table tr.selected {
  background: rgba(var(--color-brand-rgb, 59, 130, 246), 0.1);
}

.checkbox-col { width: 48px; }
.thumbnail-col { width: 80px; }
.actions-col { width: 100px; }

.thumbnail {
  width: 60px;
  height: 45px;
  object-fit: cover;
  border-radius: 0.25rem;
}

.thumbnail-placeholder {
  width: 60px;
  height: 45px;
  background: var(--color-surface-alt);
  border-radius: 0.25rem;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 1.5rem;
}

.item-title {
  font-weight: 500;
  color: var(--color-text-primary);
}

.youtube-id {
  font-size: 0.75rem;
  background: var(--color-surface-alt);
  padding: 0.25rem 0.5rem;
  border-radius: 0.25rem;
}

.category-badge {
  font-size: 0.75rem;
  background: var(--color-brand);
  color: var(--color-text-inverse);
  padding: 0.25rem 0.5rem;
  border-radius: 0.25rem;
}

.no-category {
  color: var(--color-text-secondary);
}

.metadata {
  font-size: 0.875rem;
  color: var(--color-text-secondary);
}

.action-buttons {
  display: flex;
  gap: 0.5rem;
}

.icon-button {
  appearance: none;
  border: none;
  background: transparent;
  padding: 0.5rem;
  border-radius: 0.25rem;
  cursor: pointer;
  font-size: 1rem;
  min-width: 36px;
  min-height: 36px;
}

.icon-button.restore:hover {
  background: rgba(22, 131, 90, 0.15);
}

.icon-button.delete:hover {
  background: rgba(217, 45, 32, 0.15);
}

/* Mobile Cards */
.content-cards {
  display: flex;
  flex-direction: column;
  gap: 1rem;
}

.select-all-row {
  padding: 0.75rem 1rem;
  background: var(--color-surface-alt);
  border-radius: 0.5rem;
}

.select-all-label {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  font-weight: 500;
  cursor: pointer;
}

.content-card {
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: 0.75rem;
  overflow: hidden;
}

.content-card.selected {
  border-color: var(--color-brand);
  box-shadow: 0 0 0 2px rgba(var(--color-brand-rgb, 59, 130, 246), 0.2);
}

.card-header {
  display: flex;
  align-items: center;
  gap: 1rem;
  padding: 1rem;
  background: var(--color-surface-alt);
}

.card-thumbnail {
  width: 80px;
  height: 60px;
  object-fit: cover;
  border-radius: 0.25rem;
}

.card-thumbnail-placeholder {
  width: 80px;
  height: 60px;
  background: var(--color-surface);
  border-radius: 0.25rem;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 2rem;
}

.card-body {
  padding: 1rem;
}

.card-title {
  margin: 0 0 0.5rem;
  font-size: 1rem;
  font-weight: 600;
  color: var(--color-text-primary);
}

.card-youtube-id {
  margin: 0 0 0.5rem;
}

.card-category {
  margin: 0 0 0.5rem;
}

.card-metadata {
  margin: 0;
  font-size: 0.875rem;
  color: var(--color-text-secondary);
}

.card-actions {
  display: flex;
  border-top: 1px solid var(--color-border);
}

.card-action-button {
  flex: 1;
  appearance: none;
  border: none;
  padding: 0.875rem;
  font-weight: 600;
  font-size: 0.875rem;
  cursor: pointer;
  min-height: 48px;
}

.card-action-button.restore {
  background: transparent;
  color: #16835a;
}

.card-action-button.delete {
  background: transparent;
  color: #d92d20;
  border-inline-start: 1px solid var(--color-border);
}

/* Dialog */
.dialog-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 1rem;
  z-index: 1000;
}

.dialog {
  background: var(--color-surface);
  border-radius: 0.75rem;
  padding: 1.5rem;
  max-width: 400px;
  width: 100%;
  box-shadow: 0 20px 25px -5px rgba(0, 0, 0, 0.1);
}

.dialog h3 {
  margin: 0 0 0.75rem;
  font-size: 1.25rem;
  color: var(--color-text-primary);
}

.dialog p {
  margin: 0 0 1.5rem;
  color: var(--color-text-secondary);
}

.dialog-actions {
  display: flex;
  gap: 0.75rem;
  justify-content: flex-end;
}

.dialog-button {
  appearance: none;
  border: none;
  padding: 0.75rem 1.25rem;
  border-radius: 0.5rem;
  font-weight: 600;
  cursor: pointer;
  min-height: 44px;
}

.dialog-button.cancel {
  background: var(--color-surface-alt);
  color: var(--color-text-primary);
}

.dialog-button.primary {
  background: var(--color-brand);
  color: var(--color-text-inverse);
}

.dialog-button.danger {
  background: #d92d20;
  color: white;
}

.dialog-button:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

/* Responsive */
.desktop-only {
  display: table;
}

.mobile-only {
  display: none;
}

@media (max-width: 1023px) {
  .desktop-only {
    display: none;
  }

  .mobile-only {
    display: flex;
  }

  .content-table-wrapper {
    border: none;
    background: transparent;
  }
}

@media (max-width: 767px) {
  .view-container {
    padding: 1rem;
  }

  .page-header h1 {
    font-size: 1.5rem;
  }

  .header-content {
    flex-direction: column;
    align-items: stretch;
  }

  .validate-button {
    width: 100%;
  }

  .tabs-container {
    flex-direction: column;
    align-items: stretch;
  }

  .tabs {
    width: 100%;
    overflow-x: auto;
    padding-bottom: 0.5rem;
  }

  .bulk-actions {
    width: 100%;
    flex-wrap: wrap;
  }

  .dialog-actions {
    flex-direction: column;
  }

  .dialog-button {
    width: 100%;
  }
}

/* RTL Support */
[dir="rtl"] .action-buttons {
  flex-direction: row-reverse;
}

[dir="rtl"] .card-action-button.delete {
  border-inline-start: 1px solid var(--color-border);
  border-inline-end: none;
}
</style>
