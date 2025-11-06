<template>
  <section class="validation-panel">
    <header>
      <div class="header-content">
        <div>
          <h2>{{ t('videoValidation.heading') }}</h2>
          <p>{{ t('videoValidation.subtitle') }}</p>
        </div>
        <button
          type="button"
          class="trigger-button"
          :disabled="isTriggering || isLoading"
          @click="handleTriggerValidation"
        >
          {{ isTriggering ? t('videoValidation.triggering') : t('videoValidation.triggerButton') }}
        </button>
      </div>
    </header>

    <div v-if="errorMessage" class="error-panel" role="alert">
      <p>{{ errorMessage }}</p>
      <button type="button" class="retry-button" @click="handleRetry">
        {{ t('common.retry') }}
      </button>
    </div>

    <div v-if="successMessage" class="success-panel" role="status">
      <p>{{ successMessage }}</p>
    </div>

    <div class="latest-run" v-if="latestRun">
      <h3>{{ t('videoValidation.latestRun') }}</h3>
      <div class="run-details">
        <div class="detail-item">
          <span class="detail-label">{{ t('videoValidation.status') }}:</span>
          <span class="detail-value" :class="`status-${latestRun.status.toLowerCase()}`">
            {{ t(`videoValidation.statuses.${latestRun.status.toLowerCase()}`) }}
          </span>
        </div>
        <div class="detail-item">
          <span class="detail-label">{{ t('videoValidation.videosChecked') }}:</span>
          <span class="detail-value">{{ latestRun.videosChecked }}</span>
        </div>
        <div class="detail-item">
          <span class="detail-label">{{ t('videoValidation.videosMarkedUnavailable') }}:</span>
          <span class="detail-value">{{ latestRun.videosMarkedUnavailable }}</span>
        </div>
        <div class="detail-item">
          <span class="detail-label">{{ t('videoValidation.errors') }}:</span>
          <span class="detail-value">{{ latestRun.errorCount }}</span>
        </div>
        <div class="detail-item" v-if="latestRun.startedAt">
          <span class="detail-label">{{ t('videoValidation.startedAt') }}:</span>
          <span class="detail-value">{{ formatDateTime(latestRun.startedAt, locale) }}</span>
        </div>
        <div class="detail-item" v-if="latestRun.durationMs">
          <span class="detail-label">{{ t('videoValidation.duration') }}:</span>
          <span class="detail-value">{{ formatDuration(latestRun.durationMs) }}</span>
        </div>
      </div>
    </div>

    <div class="history-section">
      <h3>{{ t('videoValidation.history') }}</h3>

      <div v-if="isLoading && !validationHistory.length" class="loading">
        {{ t('common.loading') }}
      </div>

      <div v-else-if="!validationHistory.length" class="empty-state">
        {{ t('videoValidation.noHistory') }}
      </div>

      <div v-else class="history-list">
        <div
          v-for="run in validationHistory"
          :key="run.id"
          class="history-item"
        >
          <div class="history-item-header">
            <span class="history-status" :class="`status-${run.status.toLowerCase()}`">
              {{ t(`videoValidation.statuses.${run.status.toLowerCase()}`) }}
            </span>
            <span class="history-date">{{ formatDateTime(run.startedAt, locale) }}</span>
          </div>
          <div class="history-item-stats">
            <span>{{ t('videoValidation.checked', { count: run.videosChecked }) }}</span>
            <span>{{ t('videoValidation.unavailable', { count: run.videosMarkedUnavailable }) }}</span>
            <span v-if="run.errorCount > 0" class="error-count">
              {{ t('videoValidation.errors', { count: run.errorCount }) }}
            </span>
          </div>
          <div class="history-item-trigger">
            <span class="trigger-type">{{ t(`videoValidation.triggerTypes.${run.triggerType.toLowerCase()}`) }}</span>
            <span v-if="run.triggeredByDisplayName" class="triggered-by">
              {{ t('videoValidation.triggeredBy', { name: run.triggeredByDisplayName }) }}
            </span>
          </div>
        </div>
      </div>
    </div>
  </section>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useI18n } from 'vue-i18n';
import { formatDateTime } from '@/utils/formatters';
import type { ValidationRun } from '@/types/validation';
import {
  getLatestValidationRun,
  getValidationHistory,
  triggerValidation
} from '@/services/videoValidation';

const { t, locale } = useI18n();

const isLoading = ref(false);
const isTriggering = ref(false);
const errorMessage = ref<string | null>(null);
const successMessage = ref<string | null>(null);
const latestRun = ref<ValidationRun | null>(null);
const validationHistory = ref<ValidationRun[]>([]);

async function loadData() {
  isLoading.value = true;
  errorMessage.value = null;

  try {
    const [latest, history] = await Promise.all([
      getLatestValidationRun(),
      getValidationHistory(20)
    ]);

    latestRun.value = latest;
    validationHistory.value = history;
  } catch (err) {
    errorMessage.value = err instanceof Error ? err.message : t('videoValidation.loadError');
  } finally {
    isLoading.value = false;
  }
}

async function handleTriggerValidation() {
  isTriggering.value = true;
  errorMessage.value = null;
  successMessage.value = null;

  try {
    const result = await triggerValidation();

    if (result.success) {
      successMessage.value = t('videoValidation.triggerSuccess', {
        checked: result.data.videosChecked,
        unavailable: result.data.videosMarkedUnavailable
      });

      // Reload data to show updated results
      await loadData();

      // Clear success message after 5 seconds
      setTimeout(() => {
        successMessage.value = null;
      }, 5000);
    } else {
      errorMessage.value = result.message || t('videoValidation.triggerError');
    }
  } catch (err) {
    errorMessage.value = err instanceof Error ? err.message : t('videoValidation.triggerError');
  } finally {
    isTriggering.value = false;
  }
}

function handleRetry() {
  loadData();
}

function formatDuration(ms: number): string {
  const seconds = Math.floor(ms / 1000);
  const minutes = Math.floor(seconds / 60);
  const remainingSeconds = seconds % 60;

  if (minutes > 0) {
    return t('videoValidation.durationFormat', { minutes, seconds: remainingSeconds });
  }
  return t('videoValidation.secondsFormat', { seconds });
}

onMounted(() => {
  loadData();
});
</script>

<style scoped>
.validation-panel {
  display: flex;
  flex-direction: column;
  gap: 2rem;
}

header {
  padding-bottom: 1rem;
  border-bottom: 1px solid var(--color-border);
}

.header-content {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 1.5rem;
  flex-wrap: wrap;
}

header h2 {
  margin: 0;
  font-size: 1.75rem;
  font-weight: 700;
  color: var(--color-text-primary);
}

header p {
  margin: 0.5rem 0 0;
  color: var(--color-text-secondary);
  font-size: 0.9375rem;
}

.trigger-button {
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

.trigger-button:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

@media (hover: hover) {
  .trigger-button:not(:disabled):hover {
    background: var(--color-accent);
  }
}

.error-panel {
  background: rgba(217, 45, 32, 0.15);
  border: 1px solid rgba(217, 45, 32, 0.35);
  border-radius: 0.75rem;
  padding: 1.25rem;
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}

.success-panel {
  background: rgba(22, 131, 90, 0.15);
  border: 1px solid rgba(22, 131, 90, 0.35);
  border-radius: 0.75rem;
  padding: 1.25rem;
  color: var(--color-text-primary);
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

.latest-run,
.history-section {
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: 0.75rem;
  padding: 1.5rem;
}

.latest-run h3,
.history-section h3 {
  margin: 0 0 1.25rem 0;
  font-size: 1.25rem;
  font-weight: 600;
  color: var(--color-text-primary);
}

.run-details {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 1rem;
}

.detail-item {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}

.detail-label {
  font-size: 0.8125rem;
  color: var(--color-text-secondary);
  font-weight: 500;
}

.detail-value {
  font-size: 1.125rem;
  color: var(--color-text-primary);
  font-weight: 600;
}

.status-completed {
  color: #16835a;
}

.status-running {
  color: #ff9800;
}

.status-failed,
.status-error {
  color: #d92d20;
}

.loading,
.empty-state {
  padding: 2rem;
  text-align: center;
  color: var(--color-text-secondary);
}

.history-list {
  display: flex;
  flex-direction: column;
  gap: 1rem;
}

.history-item {
  padding: 1rem;
  background: var(--color-surface-alt);
  border: 1px solid var(--color-border);
  border-radius: 0.5rem;
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}

.history-item-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 1rem;
}

.history-status {
  font-size: 0.8125rem;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.05em;
}

.history-date {
  font-size: 0.875rem;
  color: var(--color-text-secondary);
}

.history-item-stats {
  display: flex;
  gap: 1rem;
  flex-wrap: wrap;
  font-size: 0.875rem;
  color: var(--color-text-secondary);
}

.error-count {
  color: #d92d20;
  font-weight: 600;
}

.history-item-trigger {
  display: flex;
  gap: 0.5rem;
  align-items: center;
  font-size: 0.8125rem;
  color: var(--color-text-secondary);
}

.trigger-type {
  padding: 0.25rem 0.5rem;
  background: var(--color-surface);
  border-radius: 0.25rem;
  font-weight: 500;
}

@media (max-width: 767px) {
  header h2 {
    font-size: 1.5rem;
  }

  .header-content {
    flex-direction: column;
    align-items: stretch;
  }

  .trigger-button {
    width: 100%;
  }

  .run-details {
    grid-template-columns: 1fr;
  }
}
</style>
