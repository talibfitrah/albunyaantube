<template>
  <div v-if="run" class="progress-panel">
    <!-- Header with status -->
    <div class="progress-header">
      <h3 class="progress-title">
        {{ title }}
      </h3>
      <span :class="['status-badge', `status-${run.status.toLowerCase()}`]">
        {{ run.status }}
      </span>
    </div>

    <!-- Phase indicator -->
    <div v-if="run.currentPhase" class="phase-indicator">
      {{ $t(`bulkImportExport.phases.${run.currentPhase}`) }}
    </div>

    <!-- Progress bar -->
    <div v-if="run.status === 'RUNNING'" class="progress-bar-container">
      <div class="progress-bar">
        <div
          class="progress-bar-fill"
          :style="{ width: `${run.progressPercent || 0}%` }"
        ></div>
      </div>
      <span class="progress-text">{{ run.progressPercent || 0 }}%</span>
    </div>

    <!-- Counters Grid -->
    <div class="counters-grid">
      <!-- Validation Mode Counters -->
      <template v-if="mode === 'validation'">
        <div v-if="hasValidationData" class="counter-section">
          <h4 class="counter-section-title">{{ $t('validation.counters') }}</h4>
          <div class="counter-row">
            <span class="counter-label">{{ $t('validation.channelsChecked') }}:</span>
            <span class="counter-value">{{ run.channelsChecked || 0 }}</span>
          </div>
          <div class="counter-row">
            <span class="counter-label">{{ $t('validation.channelsArchived') }}:</span>
            <span class="counter-value counter-archived">{{ run.channelsArchived || 0 }}</span>
          </div>
          <div class="counter-row">
            <span class="counter-label">{{ $t('validation.playlistsChecked') }}:</span>
            <span class="counter-value">{{ run.playlistsChecked || 0 }}</span>
          </div>
          <div class="counter-row">
            <span class="counter-label">{{ $t('validation.playlistsArchived') }}:</span>
            <span class="counter-value counter-archived">{{ run.playlistsArchived || 0 }}</span>
          </div>
          <div class="counter-row">
            <span class="counter-label">{{ $t('validation.videosChecked') }}:</span>
            <span class="counter-value">{{ run.videosChecked || 0 }}</span>
          </div>
          <div class="counter-row">
            <span class="counter-label">{{ $t('validation.videosArchived') }}:</span>
            <span class="counter-value counter-archived">{{ run.videosArchived || 0 }}</span>
          </div>
          <div class="counter-row total-row">
            <span class="counter-label">{{ $t('validation.totalArchived') }}:</span>
            <span class="counter-value counter-archived">{{ run.totalArchived || 0 }}</span>
          </div>
        </div>
      </template>

      <!-- Import Mode Counters -->
      <template v-else-if="mode === 'import'">
        <div v-if="hasImportData" class="counter-section">
          <h4 class="counter-section-title">{{ $t('importExport.import.channels') }}</h4>
          <div class="counter-row">
            <span class="counter-label">{{ $t('importExport.import.imported') }}:</span>
            <span class="counter-value counter-success">{{ run.channelsImported || 0 }}</span>
          </div>
          <div class="counter-row">
            <span class="counter-label">{{ $t('importExport.import.skipped') }}:</span>
            <span class="counter-value counter-skipped">{{ run.channelsSkipped || 0 }}</span>
          </div>
          <div class="counter-row">
            <span class="counter-label">{{ $t('importExport.import.validationFailed') }}:</span>
            <span class="counter-value counter-warning">{{ run.channelsValidationFailed || 0 }}</span>
          </div>
          <div class="counter-row">
            <span class="counter-label">{{ $t('importExport.import.failed') }}:</span>
            <span class="counter-value counter-error">{{ run.channelsFailed || 0 }}</span>
          </div>
        </div>

        <div v-if="hasImportData" class="counter-section">
          <h4 class="counter-section-title">{{ $t('importExport.import.playlists') }}</h4>
          <div class="counter-row">
            <span class="counter-label">{{ $t('importExport.import.imported') }}:</span>
            <span class="counter-value counter-success">{{ run.playlistsImported || 0 }}</span>
          </div>
          <div class="counter-row">
            <span class="counter-label">{{ $t('importExport.import.skipped') }}:</span>
            <span class="counter-value counter-skipped">{{ run.playlistsSkipped || 0 }}</span>
          </div>
          <div class="counter-row">
            <span class="counter-label">{{ $t('importExport.import.validationFailed') }}:</span>
            <span class="counter-value counter-warning">{{ run.playlistsValidationFailed || 0 }}</span>
          </div>
          <div class="counter-row">
            <span class="counter-label">{{ $t('importExport.import.failed') }}:</span>
            <span class="counter-value counter-error">{{ run.playlistsFailed || 0 }}</span>
          </div>
        </div>

        <div v-if="hasImportData" class="counter-section">
          <h4 class="counter-section-title">{{ $t('importExport.import.videos') }}</h4>
          <div class="counter-row">
            <span class="counter-label">{{ $t('importExport.import.imported') }}:</span>
            <span class="counter-value counter-success">{{ run.videosImported || 0 }}</span>
          </div>
          <div class="counter-row">
            <span class="counter-label">{{ $t('importExport.import.skipped') }}:</span>
            <span class="counter-value counter-skipped">{{ run.videosSkipped || 0 }}</span>
          </div>
          <div class="counter-row">
            <span class="counter-label">{{ $t('importExport.import.validationFailed') }}:</span>
            <span class="counter-value counter-warning">{{ run.videosValidationFailed || 0 }}</span>
          </div>
          <div class="counter-row">
            <span class="counter-label">{{ $t('importExport.import.failed') }}:</span>
            <span class="counter-value counter-error">{{ run.videosFailed || 0 }}</span>
          </div>
        </div>

        <!-- Totals -->
        <div v-if="hasImportData" class="counter-section total-section">
          <h4 class="counter-section-title">{{ $t('importExport.import.totals') }}</h4>
          <div class="counter-row total-row">
            <span class="counter-label">{{ $t('importExport.import.totalImported') }}:</span>
            <span class="counter-value counter-success">{{ totalImported }}</span>
          </div>
          <div class="counter-row total-row">
            <span class="counter-label">{{ $t('importExport.import.totalSkipped') }}:</span>
            <span class="counter-value counter-skipped">{{ totalSkipped }}</span>
          </div>
          <div class="counter-row total-row">
            <span class="counter-label">{{ $t('importExport.import.totalFailed') }}:</span>
            <span class="counter-value counter-error">{{ totalFailed }}</span>
          </div>
        </div>
      </template>
    </div>

    <!-- Duration -->
    <div v-if="run.durationMs" class="duration">
      {{ $t('validation.duration') }}: {{ formatDuration(run.durationMs) }}
    </div>

    <!-- Reason counts (collapsible) -->
    <div v-if="hasReasonCounts" class="reason-counts">
      <button
        @click="showReasonCounts = !showReasonCounts"
        class="reason-counts-toggle"
      >
        {{ showReasonCounts ? '▼' : '▶' }}
        {{ $t('importExport.import.reasonCounts') }}
        ({{ Object.keys(reasonCounts).length }})
      </button>
      <div v-if="showReasonCounts" class="reason-counts-list">
        <div
          v-for="(count, reason) in reasonCounts"
          :key="reason"
          class="reason-count-row"
        >
          <span class="reason-label">{{ reason }}:</span>
          <span class="reason-value">{{ count }}</span>
        </div>
      </div>
    </div>

    <!-- Actions -->
    <div v-if="run.status === 'COMPLETED' && mode === 'import'" class="actions">
      <button
        v-if="totalFailed > 0"
        @click="$emit('download-failed')"
        class="btn btn-secondary"
      >
        {{ $t('importExport.import.downloadFailedItems') }}
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import type { ValidationRun } from '@/types/validation'

interface Props {
  run: ValidationRun | null
  title: string
  mode: 'validation' | 'import'
}

const props = defineProps<Props>()

defineEmits<{
  (e: 'download-failed'): void
}>()

const showReasonCounts = ref(false)

const hasValidationData = computed(() => {
  return props.run && (
    (props.run.channelsChecked ?? 0) > 0 ||
    (props.run.playlistsChecked ?? 0) > 0 ||
    (props.run.videosChecked ?? 0) > 0
  )
})

const hasImportData = computed(() => {
  return props.run && (
    (props.run.channelsImported ?? 0) > 0 ||
    (props.run.channelsSkipped ?? 0) > 0 ||
    (props.run.channelsValidationFailed ?? 0) > 0 ||
    (props.run.channelsFailed ?? 0) > 0 ||
    (props.run.playlistsImported ?? 0) > 0 ||
    (props.run.playlistsSkipped ?? 0) > 0 ||
    (props.run.playlistsValidationFailed ?? 0) > 0 ||
    (props.run.playlistsFailed ?? 0) > 0 ||
    (props.run.videosImported ?? 0) > 0 ||
    (props.run.videosSkipped ?? 0) > 0 ||
    (props.run.videosValidationFailed ?? 0) > 0 ||
    (props.run.videosFailed ?? 0) > 0
  )
})

const totalImported = computed(() => {
  if (!props.run) return 0
  return (
    (props.run.channelsImported || 0) +
    (props.run.playlistsImported || 0) +
    (props.run.videosImported || 0)
  )
})

const totalSkipped = computed(() => {
  if (!props.run) return 0
  return (
    (props.run.channelsSkipped || 0) +
    (props.run.playlistsSkipped || 0) +
    (props.run.videosSkipped || 0)
  )
})

const totalFailed = computed(() => {
  if (!props.run) return 0
  return (
    (props.run.channelsFailed || 0) +
    (props.run.channelsValidationFailed || 0) +
    (props.run.playlistsFailed || 0) +
    (props.run.playlistsValidationFailed || 0) +
    (props.run.videosFailed || 0) +
    (props.run.videosValidationFailed || 0)
  )
})

const reasonCounts = computed(() => {
  if (!props.run?.details?.reasonCounts) return {}
  return props.run.details.reasonCounts as Record<string, number>
})

const hasReasonCounts = computed(() => {
  return Object.keys(reasonCounts.value).length > 0
})

function formatDuration(ms: number): string {
  const seconds = Math.floor(ms / 1000)
  const minutes = Math.floor(seconds / 60)
  const hours = Math.floor(minutes / 60)

  if (hours > 0) {
    return `${hours}h ${minutes % 60}m ${seconds % 60}s`
  } else if (minutes > 0) {
    return `${minutes}m ${seconds % 60}s`
  } else {
    return `${seconds}s`
  }
}
</script>

<style scoped>
.progress-panel {
  background: var(--color-background-soft);
  border: 1px solid var(--color-border);
  border-radius: 8px;
  padding: 1.5rem;
  margin: 1rem 0;
}

.progress-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 1rem;
}

.progress-title {
  margin: 0;
  font-size: 1.25rem;
  font-weight: 600;
  color: var(--color-heading);
}

.status-badge {
  padding: 0.25rem 0.75rem;
  border-radius: 4px;
  font-size: 0.875rem;
  font-weight: 600;
  text-transform: uppercase;
}

.status-running {
  background: var(--color-info-bg, #e3f2fd);
  color: var(--color-info, #1976d2);
}

.status-completed {
  background: var(--color-success-bg, #e8f5e9);
  color: var(--color-success, #2e7d32);
}

.status-failed {
  background: var(--color-error-bg, #ffebee);
  color: var(--color-error, #c62828);
}

.phase-indicator {
  font-size: 0.875rem;
  color: var(--color-text-secondary);
  margin-bottom: 0.5rem;
  font-style: italic;
}

.progress-bar-container {
  display: flex;
  align-items: center;
  gap: 1rem;
  margin-bottom: 1.5rem;
}

.progress-bar {
  flex: 1;
  height: 24px;
  background: var(--color-background);
  border-radius: 12px;
  overflow: hidden;
  border: 1px solid var(--color-border);
}

.progress-bar-fill {
  height: 100%;
  background: linear-gradient(90deg, var(--color-brand), var(--color-accent));
  transition: width 0.4s ease-out;
  will-change: width;
}

.progress-text {
  font-weight: 600;
  color: var(--color-text);
  min-width: 3rem;
  text-align: right;
}

.counters-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(250px, 1fr));
  gap: 1.5rem;
  margin: 1.5rem 0;
}

.counter-section {
  background: var(--color-background);
  padding: 1rem;
  border-radius: 6px;
  border: 1px solid var(--color-border);
}

.counter-section-title {
  margin: 0 0 0.75rem 0;
  font-size: 0.875rem;
  font-weight: 600;
  color: var(--color-text-secondary);
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.counter-row {
  display: flex;
  justify-content: space-between;
  padding: 0.5rem 0;
  border-bottom: 1px solid var(--color-border-light, var(--color-border));
}

.counter-row:last-child {
  border-bottom: none;
}

.total-row {
  font-weight: 600;
  padding-top: 0.75rem;
  margin-top: 0.25rem;
  border-top: 2px solid var(--color-border);
  border-bottom: none;
}

.counter-label {
  color: var(--color-text-secondary);
  font-size: 0.875rem;
}

.counter-value {
  font-weight: 600;
  font-size: 1rem;
}

.counter-success {
  color: var(--color-success, #2e7d32);
}

.counter-skipped {
  color: var(--color-warning, #f57c00);
}

.counter-warning {
  color: var(--color-warning, #f57c00);
}

.counter-error {
  color: var(--color-error, #c62828);
}

.counter-archived {
  color: var(--color-error, #c62828);
}

.total-section {
  grid-column: 1 / -1;
}

.duration {
  font-size: 0.875rem;
  color: var(--color-text-secondary);
  margin-top: 1rem;
}

.reason-counts {
  margin-top: 1.5rem;
  padding-top: 1.5rem;
  border-top: 1px solid var(--color-border);
}

.reason-counts-toggle {
  background: none;
  border: none;
  color: var(--color-primary);
  font-weight: 600;
  cursor: pointer;
  padding: 0.5rem 0;
  display: flex;
  align-items: center;
  gap: 0.5rem;
  font-size: 0.875rem;
}

.reason-counts-toggle:hover {
  color: var(--color-primary-dark, var(--color-primary));
}

.reason-counts-list {
  margin-top: 1rem;
  padding: 1rem;
  background: var(--color-background);
  border-radius: 6px;
  border: 1px solid var(--color-border);
  max-height: 300px;
  overflow-y: auto;
}

.reason-count-row {
  display: flex;
  justify-content: space-between;
  padding: 0.5rem 0;
  border-bottom: 1px solid var(--color-border-light, var(--color-border));
  font-size: 0.875rem;
}

.reason-count-row:last-child {
  border-bottom: none;
}

.reason-label {
  color: var(--color-text);
  flex: 1;
}

.reason-value {
  font-weight: 600;
  color: var(--color-text-secondary);
  margin-left: 1rem;
}

.actions {
  margin-top: 1.5rem;
  padding-top: 1.5rem;
  border-top: 1px solid var(--color-border);
  display: flex;
  gap: 1rem;
}

.btn {
  padding: 0.5rem 1rem;
  border-radius: 6px;
  font-weight: 600;
  cursor: pointer;
  border: none;
  transition: all 0.2s;
}

.btn-secondary {
  background: var(--color-background-soft);
  color: var(--color-text);
  border: 1px solid var(--color-border);
}

.btn-secondary:hover {
  background: var(--color-background-mute);
}
</style>
