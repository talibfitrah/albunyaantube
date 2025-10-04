<script setup lang="ts">
import { ref, onMounted, computed } from 'vue';
import { useI18n } from 'vue-i18n';

const { t } = useI18n();

// API Key state
const apiKey = ref('');
const dailyQuota = ref(10000);
const quotaUsed = ref(0);
const quotaResetDate = ref('');
const showApiKey = ref(false);

// UI state
const isLoading = ref(false);
const isSaving = ref(false);
const isTesting = ref(false);
const error = ref('');
const successMessage = ref('');
const testResult = ref<{ success: boolean; message: string } | null>(null);

// Validation
const apiKeyError = ref('');

const quotaPercentage = computed(() => {
  if (dailyQuota.value === 0) return 0;
  return Math.round((quotaUsed.value / dailyQuota.value) * 100);
});

const quotaStatus = computed(() => {
  const percentage = quotaPercentage.value;
  if (percentage >= 90) return 'critical';
  if (percentage >= 75) return 'warning';
  return 'normal';
});

const maskedApiKey = computed(() => {
  if (!apiKey.value) return '';
  if (showApiKey.value) return apiKey.value;
  const keyLength = apiKey.value.length;
  if (keyLength <= 8) return '•'.repeat(keyLength);
  return apiKey.value.substring(0, 4) + '•'.repeat(keyLength - 8) + apiKey.value.substring(keyLength - 4);
});

onMounted(() => {
  loadSettings();
});

async function loadSettings() {
  isLoading.value = true;
  error.value = '';

  try {
    // Mock data - replace with actual API call
    await new Promise(resolve => setTimeout(resolve, 300));
    apiKey.value = 'AIzaSyDemoKey123456789ABCDEFGH';
    dailyQuota.value = 10000;
    quotaUsed.value = 6547;
    quotaResetDate.value = new Date(Date.now() + 86400000).toISOString();
  } catch (err) {
    error.value = t('settings.youtubeApi.errors.loadFailed');
  } finally {
    isLoading.value = false;
  }
}

function validateApiKey(): boolean {
  apiKeyError.value = '';

  if (!apiKey.value.trim()) {
    apiKeyError.value = t('settings.youtubeApi.errors.apiKeyRequired');
    return false;
  }

  if (apiKey.value.length < 30) {
    apiKeyError.value = t('settings.youtubeApi.errors.apiKeyInvalid');
    return false;
  }

  return true;
}

async function handleSaveSettings() {
  successMessage.value = '';
  error.value = '';
  testResult.value = null;

  if (!validateApiKey()) {
    return;
  }

  isSaving.value = true;

  try {
    // Mock save - replace with actual API call
    await new Promise(resolve => setTimeout(resolve, 800));

    successMessage.value = t('settings.youtubeApi.successMessage');

    // Auto-hide success message after 3 seconds
    setTimeout(() => {
      successMessage.value = '';
    }, 3000);
  } catch (err) {
    error.value = t('settings.youtubeApi.errors.saveFailed');
  } finally {
    isSaving.value = false;
  }
}

async function handleTestConnection() {
  testResult.value = null;
  error.value = '';

  if (!validateApiKey()) {
    return;
  }

  isTesting.value = true;

  try {
    // Mock test - replace with actual API call
    await new Promise(resolve => setTimeout(resolve, 1500));

    const success = Math.random() > 0.3; // 70% success rate for demo

    testResult.value = {
      success,
      message: success
        ? t('settings.youtubeApi.testSuccess')
        : t('settings.youtubeApi.testFailure')
    };
  } catch (err) {
    testResult.value = {
      success: false,
      message: t('settings.youtubeApi.errors.testFailed')
    };
  } finally {
    isTesting.value = false;
  }
}

function formatQuotaResetTime(): string {
  if (!quotaResetDate.value) return '';
  const date = new Date(quotaResetDate.value);
  const now = new Date();
  const diff = date.getTime() - now.getTime();
  const hours = Math.floor(diff / (1000 * 60 * 60));
  const minutes = Math.floor((diff % (1000 * 60 * 60)) / (1000 * 60));
  return t('settings.youtubeApi.quotaResetIn', { hours, minutes });
}
</script>

<template>
  <div class="youtube-api-settings">
    <div class="settings-header">
      <h1>{{ t('settings.youtubeApi.heading') }}</h1>
      <p class="subtitle">{{ t('settings.youtubeApi.description') }}</p>
    </div>

    <!-- Loading State -->
    <div v-if="isLoading" class="loading-state">
      <div class="spinner"></div>
      <p>{{ t('settings.youtubeApi.loading') }}</p>
    </div>

    <!-- Settings Form -->
    <div v-else class="settings-content">
      <!-- Success Message -->
      <div v-if="successMessage" class="alert alert-success">
        {{ successMessage }}
      </div>

      <!-- Error Message -->
      <div v-if="error" class="alert alert-error">
        {{ error }}
      </div>

      <!-- API Key Section -->
      <section class="form-section">
        <h2>{{ t('settings.youtubeApi.sections.apiKey') }}</h2>
        <p class="section-hint">{{ t('settings.youtubeApi.hints.apiKey') }}</p>

        <div class="form-group">
          <label for="apiKey">{{ t('settings.youtubeApi.fields.apiKey') }}</label>
          <div class="api-key-input">
            <input
              id="apiKey"
              v-model="apiKey"
              :type="showApiKey ? 'text' : 'password'"
              :class="{ 'input-error': apiKeyError }"
              @blur="validateApiKey"
            />
            <button
              type="button"
              @click="showApiKey = !showApiKey"
              class="btn-toggle-visibility"
              :aria-label="showApiKey ? t('settings.youtubeApi.actions.hide') : t('settings.youtubeApi.actions.show')"
            >
              {{ showApiKey ? '👁️' : '👁️‍🗨️' }}
            </button>
          </div>
          <p v-if="apiKeyError" class="error-message">{{ apiKeyError }}</p>
          <p v-else class="field-hint">{{ t('settings.youtubeApi.hints.getApiKey') }}</p>
        </div>

        <div class="action-buttons">
          <button
            type="button"
            @click="handleTestConnection"
            class="btn-secondary"
            :disabled="isTesting || isSaving"
          >
            {{ isTesting ? t('settings.youtubeApi.actions.testing') : t('settings.youtubeApi.actions.test') }}
          </button>
          <button
            type="button"
            @click="handleSaveSettings"
            class="btn-primary"
            :disabled="isSaving || isTesting"
          >
            {{ isSaving ? t('settings.youtubeApi.actions.saving') : t('settings.youtubeApi.actions.save') }}
          </button>
        </div>

        <!-- Test Result -->
        <div v-if="testResult" :class="['test-result', testResult.success ? 'test-success' : 'test-failure']">
          <span class="test-icon">{{ testResult.success ? '✓' : '✗' }}</span>
          <span>{{ testResult.message }}</span>
        </div>
      </section>

      <!-- Quota Usage Section -->
      <section class="form-section">
        <h2>{{ t('settings.youtubeApi.sections.quota') }}</h2>
        <p class="section-hint">{{ t('settings.youtubeApi.hints.quota') }}</p>

        <div class="quota-card">
          <div class="quota-header">
            <div class="quota-stats">
              <div class="quota-stat">
                <span class="stat-label">{{ t('settings.youtubeApi.quota.used') }}</span>
                <span class="stat-value">{{ quotaUsed.toLocaleString() }}</span>
              </div>
              <div class="quota-stat">
                <span class="stat-label">{{ t('settings.youtubeApi.quota.limit') }}</span>
                <span class="stat-value">{{ dailyQuota.toLocaleString() }}</span>
              </div>
              <div class="quota-stat">
                <span class="stat-label">{{ t('settings.youtubeApi.quota.remaining') }}</span>
                <span class="stat-value">{{ (dailyQuota - quotaUsed).toLocaleString() }}</span>
              </div>
            </div>
          </div>

          <div class="quota-progress">
            <div class="progress-bar">
              <div
                class="progress-fill"
                :class="`status-${quotaStatus}`"
                :style="{ width: `${quotaPercentage}%` }"
              ></div>
            </div>
            <div class="progress-label">
              <span>{{ quotaPercentage }}% {{ t('settings.youtubeApi.quota.used').toLowerCase() }}</span>
              <span class="reset-time">{{ formatQuotaResetTime() }}</span>
            </div>
          </div>

          <div v-if="quotaStatus === 'critical'" class="quota-warning critical">
            ⚠️ {{ t('settings.youtubeApi.warnings.quotaCritical') }}
          </div>
          <div v-else-if="quotaStatus === 'warning'" class="quota-warning warning">
            ⚠️ {{ t('settings.youtubeApi.warnings.quotaWarning') }}
          </div>
        </div>
      </section>

      <!-- Documentation Links -->
      <section class="form-section">
        <h2>{{ t('settings.youtubeApi.sections.documentation') }}</h2>
        <div class="docs-links">
          <a href="https://console.cloud.google.com/apis/credentials" target="_blank" rel="noopener noreferrer" class="doc-link">
            <span>🔑</span>
            <div>
              <div class="link-title">{{ t('settings.youtubeApi.docs.getApiKey') }}</div>
              <div class="link-hint">console.cloud.google.com</div>
            </div>
          </a>
          <a href="https://developers.google.com/youtube/v3/getting-started" target="_blank" rel="noopener noreferrer" class="doc-link">
            <span>📚</span>
            <div>
              <div class="link-title">{{ t('settings.youtubeApi.docs.apiDocs') }}</div>
              <div class="link-hint">developers.google.com</div>
            </div>
          </a>
          <a href="https://developers.google.com/youtube/v3/determine_quota_cost" target="_blank" rel="noopener noreferrer" class="doc-link">
            <span>📊</span>
            <div>
              <div class="link-title">{{ t('settings.youtubeApi.docs.quotaDocs') }}</div>
              <div class="link-hint">developers.google.com</div>
            </div>
          </a>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.youtube-api-settings {
  max-width: 800px;
  margin: 0 auto;
  padding: 1.5rem;
}

.settings-header {
  margin-bottom: 2rem;
}

.settings-header h1 {
  font-size: 1.875rem;
  font-weight: 700;
  color: var(--color-text);
  margin-bottom: 0.5rem;
}

.subtitle {
  color: var(--color-text-secondary);
  font-size: 0.9375rem;
}

/* Loading State */
.loading-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 3rem;
  gap: 1rem;
}

.spinner {
  width: 2.5rem;
  height: 2.5rem;
  border: 3px solid var(--color-border);
  border-top-color: var(--color-primary);
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

/* Settings Content */
.settings-content {
  display: flex;
  flex-direction: column;
  gap: 1.5rem;
}

.form-section {
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: 8px;
  padding: 1.5rem;
}

.form-section h2 {
  font-size: 1.25rem;
  font-weight: 600;
  color: var(--color-text);
  margin-bottom: 0.5rem;
}

.section-hint {
  color: var(--color-text-secondary);
  font-size: 0.875rem;
  margin-bottom: 1.5rem;
}

/* Form Group */
.form-group {
  margin-bottom: 1.5rem;
}

.form-group:last-child {
  margin-bottom: 0;
}

.form-group label {
  display: block;
  font-weight: 500;
  color: var(--color-text);
  margin-bottom: 0.5rem;
  font-size: 0.9375rem;
}

.api-key-input {
  position: relative;
  display: flex;
  align-items: center;
}

.api-key-input input {
  width: 100%;
  padding: 0.625rem 3rem 0.625rem 0.875rem;
  border: 1px solid var(--color-border);
  border-radius: 6px;
  font-size: 0.9375rem;
  background: var(--color-background);
  color: var(--color-text);
  font-family: monospace;
  transition: border-color 0.2s;
}

.api-key-input input:focus {
  outline: none;
  border-color: var(--color-primary);
}

.btn-toggle-visibility {
  position: absolute;
  right: 0.5rem;
  background: none;
  border: none;
  cursor: pointer;
  font-size: 1.25rem;
  padding: 0.25rem 0.5rem;
  opacity: 0.6;
  transition: opacity 0.2s;
}

.btn-toggle-visibility:hover {
  opacity: 1;
}

.input-error {
  border-color: var(--color-danger) !important;
}

.field-hint {
  margin-top: 0.5rem;
  font-size: 0.8125rem;
  color: var(--color-text-secondary);
}

.error-message {
  margin-top: 0.5rem;
  font-size: 0.8125rem;
  color: var(--color-danger);
}

/* Action Buttons */
.action-buttons {
  display: flex;
  gap: 1rem;
  margin-top: 1.5rem;
}

.btn-primary,
.btn-secondary {
  padding: 0.625rem 1.5rem;
  border-radius: 6px;
  font-weight: 500;
  font-size: 0.9375rem;
  cursor: pointer;
  transition: all 0.2s;
  border: none;
  min-height: 44px;
}

.btn-primary {
  background: var(--color-primary);
  color: white;
}

.btn-primary:hover:not(:disabled) {
  background: var(--color-primary-hover);
}

.btn-secondary {
  background: transparent;
  color: var(--color-text);
  border: 1px solid var(--color-border);
}

.btn-secondary:hover:not(:disabled) {
  background: var(--color-surface-variant);
}

.btn-primary:disabled,
.btn-secondary:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

/* Test Result */
.test-result {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  padding: 0.875rem 1rem;
  border-radius: 6px;
  margin-top: 1rem;
  font-size: 0.9375rem;
}

.test-success {
  background: #d4edda;
  color: #155724;
  border: 1px solid #c3e6cb;
}

.test-failure {
  background: #f8d7da;
  color: #721c24;
  border: 1px solid #f5c6cb;
}

.test-icon {
  font-size: 1.25rem;
  font-weight: bold;
}

/* Quota Card */
.quota-card {
  background: var(--color-background);
  border: 1px solid var(--color-border);
  border-radius: 8px;
  padding: 1.5rem;
}

.quota-header {
  margin-bottom: 1.5rem;
}

.quota-stats {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 1.5rem;
}

.quota-stat {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}

.stat-label {
  font-size: 0.8125rem;
  color: var(--color-text-secondary);
  text-transform: uppercase;
  letter-spacing: 0.025em;
}

.stat-value {
  font-size: 1.5rem;
  font-weight: 600;
  color: var(--color-text);
}

.quota-progress {
  margin-bottom: 1rem;
}

.progress-bar {
  width: 100%;
  height: 0.75rem;
  background: var(--color-surface-variant);
  border-radius: 9999px;
  overflow: hidden;
}

.progress-fill {
  height: 100%;
  transition: width 0.3s ease;
  border-radius: 9999px;
}

.progress-fill.status-normal {
  background: var(--color-success);
}

.progress-fill.status-warning {
  background: var(--color-warning);
}

.progress-fill.status-critical {
  background: var(--color-danger);
}

.progress-label {
  display: flex;
  justify-content: space-between;
  margin-top: 0.5rem;
  font-size: 0.8125rem;
  color: var(--color-text-secondary);
}

.reset-time {
  font-weight: 500;
}

.quota-warning {
  padding: 0.875rem 1rem;
  border-radius: 6px;
  margin-top: 1rem;
  font-size: 0.875rem;
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.quota-warning.warning {
  background: #fff3cd;
  color: #856404;
  border: 1px solid #ffeeba;
}

.quota-warning.critical {
  background: #f8d7da;
  color: #721c24;
  border: 1px solid #f5c6cb;
}

/* Documentation Links */
.docs-links {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}

.doc-link {
  display: flex;
  align-items: center;
  gap: 1rem;
  padding: 1rem;
  background: var(--color-background);
  border: 1px solid var(--color-border);
  border-radius: 6px;
  text-decoration: none;
  transition: all 0.2s;
}

.doc-link:hover {
  background: var(--color-surface-variant);
  border-color: var(--color-primary);
}

.doc-link > span {
  font-size: 1.5rem;
}

.link-title {
  font-weight: 500;
  color: var(--color-text);
  margin-bottom: 0.25rem;
}

.link-hint {
  font-size: 0.8125rem;
  color: var(--color-text-secondary);
}

/* Alerts */
.alert {
  padding: 0.875rem 1rem;
  border-radius: 6px;
  margin-bottom: 1.5rem;
  font-size: 0.9375rem;
}

.alert-success {
  background: #d4edda;
  color: #155724;
  border: 1px solid #c3e6cb;
}

.alert-error {
  background: #f8d7da;
  color: #721c24;
  border: 1px solid #f5c6cb;
}

/* Mobile Responsiveness */
@media (max-width: 640px) {
  .youtube-api-settings {
    padding: 1rem;
  }

  .settings-header h1 {
    font-size: 1.5rem;
  }

  .form-section {
    padding: 1rem;
  }

  .quota-stats {
    grid-template-columns: 1fr;
    gap: 1rem;
  }

  .action-buttons {
    flex-direction: column;
  }

  .action-buttons button {
    width: 100%;
  }

  .progress-label {
    flex-direction: column;
    gap: 0.25rem;
  }
}

/* RTL Support */
[dir="rtl"] .btn-toggle-visibility {
  right: auto;
  left: 0.5rem;
}

/* RTL: Reverse button order (primary on left) */
[dir="rtl"] .action-buttons {
  flex-direction: row-reverse;
}

@media (max-width: 640px) {
  [dir="rtl"] .action-buttons {
    flex-direction: column-reverse;
  }
}

[dir="rtl"] .api-key-input input {
  padding: 0.625rem 0.875rem 0.625rem 3rem;
}
</style>
