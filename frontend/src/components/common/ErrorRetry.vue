<template>
  <div class="error-retry" role="alert">
    <div class="error-icon" aria-hidden="true">
      <svg width="24" height="24" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
        <path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-2h2v2zm0-4h-2V7h2v6z" fill="currentColor"/>
      </svg>
    </div>
    <div class="error-content">
      <h3 class="error-title">{{ title || t('common.error.title') }}</h3>
      <p class="error-message">{{ message || t('common.error.message') }}</p>
    </div>
    <button
      v-if="showRetry"
      type="button"
      class="retry-button"
      :disabled="loading"
      @click="handleRetry"
    >
      <span v-if="loading">{{ t('common.error.retrying') }}</span>
      <span v-else>{{ t('common.error.retry') }}</span>
    </button>
  </div>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n';

interface Props {
  title?: string;
  message?: string;
  showRetry?: boolean;
  loading?: boolean;
}

interface Emits {
  (e: 'retry'): void;
}

const { t } = useI18n();

withDefaults(defineProps<Props>(), {
  showRetry: true,
  loading: false
});

const emit = defineEmits<Emits>();

function handleRetry() {
  emit('retry');
}
</script>

<style scoped>
.error-retry {
  display: flex;
  align-items: center;
  gap: 1rem;
  padding: 1rem 1.25rem;
  background: var(--color-danger-soft, #fee);
  border: 1px solid var(--color-danger-border, #fcc);
  border-radius: 0.75rem;
  color: var(--color-danger, #c00);
  animation: slideIn 0.3s ease-out;
}

@keyframes slideIn {
  from {
    opacity: 0;
    transform: translateY(-8px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.error-icon {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 2.5rem;
  height: 2.5rem;
  border-radius: 50%;
  background: var(--color-danger, #c00);
  color: white;
}

.error-content {
  flex: 1;
  min-width: 0;
}

.error-title {
  margin: 0;
  font-size: 0.9375rem;
  font-weight: 600;
  color: var(--color-danger-dark, #900);
}

.error-message {
  margin: 0.25rem 0 0;
  font-size: 0.875rem;
  color: var(--color-danger, #c00);
  line-height: 1.5;
}

.retry-button {
  flex-shrink: 0;
  border: none;
  border-radius: 0.5rem;
  padding: 0.625rem 1.25rem;
  background: var(--color-danger, #c00);
  color: white;
  font-weight: 600;
  font-size: 0.875rem;
  cursor: pointer;
  transition: all 0.2s ease;
  min-height: 44px; /* Touch target */
}

.retry-button:hover:not(:disabled) {
  background: var(--color-danger-dark, #900);
  transform: translateY(-1px);
  box-shadow: 0 4px 12px rgba(192, 0, 0, 0.25);
}

.retry-button:active:not(:disabled) {
  transform: translateY(0);
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.1);
}

.retry-button:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

/* Mobile Responsive */
@media (max-width: 767px) {
  .error-retry {
    flex-direction: column;
    align-items: stretch;
    gap: 0.75rem;
  }

  .error-icon {
    align-self: flex-start;
  }

  .retry-button {
    width: 100%;
    min-height: 48px;
  }
}
</style>
