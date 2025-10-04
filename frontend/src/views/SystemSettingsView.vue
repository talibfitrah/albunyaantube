<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { useI18n } from 'vue-i18n';

const { t } = useI18n();

// System settings
const autoApproveChannels = ref(false);
const autoApprovePlaylists = ref(false);
const autoApproveVideos = ref(false);
const requireCategoryAssignment = ref(true);
const enableContentModeration = ref(true);
const contentExpiryDays = ref(90);
const maxVideosPerChannel = ref(1000);
const enableAuditLog = ref(true);
const auditLogRetentionDays = ref(365);

// UI state
const isLoading = ref(false);
const isSaving = ref(false);
const error = ref('');
const successMessage = ref('');

// Validation errors
const contentExpiryError = ref('');
const maxVideosError = ref('');
const auditRetentionError = ref('');

onMounted(() => {
  loadSettings();
});

async function loadSettings() {
  isLoading.value = true;
  error.value = '';

  try {
    // Mock data - replace with actual API call
    await new Promise(resolve => setTimeout(resolve, 300));
    // Settings are already set in refs above
  } catch (err) {
    error.value = t('settings.system.errors.loadFailed');
  } finally {
    isLoading.value = false;
  }
}

function validateSettings(): boolean {
  contentExpiryError.value = '';
  maxVideosError.value = '';
  auditRetentionError.value = '';

  let isValid = true;

  if (contentExpiryDays.value < 1 || contentExpiryDays.value > 3650) {
    contentExpiryError.value = t('settings.system.errors.contentExpiryRange');
    isValid = false;
  }

  if (maxVideosPerChannel.value < 1 || maxVideosPerChannel.value > 10000) {
    maxVideosError.value = t('settings.system.errors.maxVideosRange');
    isValid = false;
  }

  if (auditLogRetentionDays.value < 30 || auditLogRetentionDays.value > 3650) {
    auditRetentionError.value = t('settings.system.errors.auditRetentionRange');
    isValid = false;
  }

  return isValid;
}

async function handleSaveSettings() {
  successMessage.value = '';
  error.value = '';

  if (!validateSettings()) {
    return;
  }

  isSaving.value = true;

  try {
    // Mock save - replace with actual API call
    await new Promise(resolve => setTimeout(resolve, 1000));

    successMessage.value = t('settings.system.successMessage');

    // Auto-hide success message after 3 seconds
    setTimeout(() => {
      successMessage.value = '';
    }, 3000);
  } catch (err) {
    error.value = t('settings.system.errors.saveFailed');
  } finally {
    isSaving.value = false;
  }
}

async function handleResetToDefaults() {
  if (!confirm(t('settings.system.confirmReset'))) {
    return;
  }

  autoApproveChannels.value = false;
  autoApprovePlaylists.value = false;
  autoApproveVideos.value = false;
  requireCategoryAssignment.value = true;
  enableContentModeration.value = true;
  contentExpiryDays.value = 90;
  maxVideosPerChannel.value = 1000;
  enableAuditLog.value = true;
  auditLogRetentionDays.value = 365;

  successMessage.value = t('settings.system.resetSuccess');

  setTimeout(() => {
    successMessage.value = '';
  }, 3000);
}
</script>

<template>
  <div class="system-settings">
    <div class="settings-header">
      <h1>{{ t('settings.system.heading') }}</h1>
      <p class="subtitle">{{ t('settings.system.description') }}</p>
    </div>

    <!-- Loading State -->
    <div v-if="isLoading" class="loading-state">
      <div class="spinner"></div>
      <p>{{ t('settings.system.loading') }}</p>
    </div>

    <!-- Settings Form -->
    <form v-else @submit.prevent="handleSaveSettings" class="settings-form">
      <!-- Success Message -->
      <div v-if="successMessage" class="alert alert-success">
        {{ successMessage }}
      </div>

      <!-- Error Message -->
      <div v-if="error" class="alert alert-error">
        {{ error }}
      </div>

      <!-- Auto-Approval Settings -->
      <section class="form-section">
        <h2>{{ t('settings.system.sections.autoApproval') }}</h2>
        <p class="section-hint">{{ t('settings.system.hints.autoApproval') }}</p>

        <div class="toggle-list">
          <label class="toggle-item">
            <div class="toggle-info">
              <span class="toggle-label">{{ t('settings.system.toggles.autoApproveChannels') }}</span>
              <span class="toggle-hint">{{ t('settings.system.hints.autoApproveChannels') }}</span>
            </div>
            <input type="checkbox" v-model="autoApproveChannels" class="toggle-switch" />
          </label>

          <label class="toggle-item">
            <div class="toggle-info">
              <span class="toggle-label">{{ t('settings.system.toggles.autoApprovePlaylists') }}</span>
              <span class="toggle-hint">{{ t('settings.system.hints.autoApprovePlaylists') }}</span>
            </div>
            <input type="checkbox" v-model="autoApprovePlaylists" class="toggle-switch" />
          </label>

          <label class="toggle-item">
            <div class="toggle-info">
              <span class="toggle-label">{{ t('settings.system.toggles.autoApproveVideos') }}</span>
              <span class="toggle-hint">{{ t('settings.system.hints.autoApproveVideos') }}</span>
            </div>
            <input type="checkbox" v-model="autoApproveVideos" class="toggle-switch" />
          </label>
        </div>
      </section>

      <!-- Content Moderation Settings -->
      <section class="form-section">
        <h2>{{ t('settings.system.sections.moderation') }}</h2>
        <p class="section-hint">{{ t('settings.system.hints.moderation') }}</p>

        <div class="toggle-list">
          <label class="toggle-item">
            <div class="toggle-info">
              <span class="toggle-label">{{ t('settings.system.toggles.requireCategoryAssignment') }}</span>
              <span class="toggle-hint">{{ t('settings.system.hints.requireCategoryAssignment') }}</span>
            </div>
            <input type="checkbox" v-model="requireCategoryAssignment" class="toggle-switch" />
          </label>

          <label class="toggle-item">
            <div class="toggle-info">
              <span class="toggle-label">{{ t('settings.system.toggles.enableContentModeration') }}</span>
              <span class="toggle-hint">{{ t('settings.system.hints.enableContentModeration') }}</span>
            </div>
            <input type="checkbox" v-model="enableContentModeration" class="toggle-switch" />
          </label>
        </div>
      </section>

      <!-- Content Limits Settings -->
      <section class="form-section">
        <h2>{{ t('settings.system.sections.limits') }}</h2>
        <p class="section-hint">{{ t('settings.system.hints.limits') }}</p>

        <div class="form-group">
          <label for="contentExpiryDays">{{ t('settings.system.fields.contentExpiryDays') }}</label>
          <input
            id="contentExpiryDays"
            v-model.number="contentExpiryDays"
            type="number"
            min="1"
            max="3650"
            :class="{ 'input-error': contentExpiryError }"
            @blur="validateSettings"
          />
          <p v-if="contentExpiryError" class="error-message">{{ contentExpiryError }}</p>
          <p v-else class="field-hint">{{ t('settings.system.hints.contentExpiryDays') }}</p>
        </div>

        <div class="form-group">
          <label for="maxVideosPerChannel">{{ t('settings.system.fields.maxVideosPerChannel') }}</label>
          <input
            id="maxVideosPerChannel"
            v-model.number="maxVideosPerChannel"
            type="number"
            min="1"
            max="10000"
            :class="{ 'input-error': maxVideosError }"
            @blur="validateSettings"
          />
          <p v-if="maxVideosError" class="error-message">{{ maxVideosError }}</p>
          <p v-else class="field-hint">{{ t('settings.system.hints.maxVideosPerChannel') }}</p>
        </div>
      </section>

      <!-- Audit Log Settings -->
      <section class="form-section">
        <h2>{{ t('settings.system.sections.auditLog') }}</h2>
        <p class="section-hint">{{ t('settings.system.hints.auditLog') }}</p>

        <div class="toggle-list">
          <label class="toggle-item">
            <div class="toggle-info">
              <span class="toggle-label">{{ t('settings.system.toggles.enableAuditLog') }}</span>
              <span class="toggle-hint">{{ t('settings.system.hints.enableAuditLog') }}</span>
            </div>
            <input type="checkbox" v-model="enableAuditLog" class="toggle-switch" />
          </label>
        </div>

        <div class="form-group" v-if="enableAuditLog">
          <label for="auditLogRetentionDays">{{ t('settings.system.fields.auditLogRetentionDays') }}</label>
          <input
            id="auditLogRetentionDays"
            v-model.number="auditLogRetentionDays"
            type="number"
            min="30"
            max="3650"
            :class="{ 'input-error': auditRetentionError }"
            @blur="validateSettings"
          />
          <p v-if="auditRetentionError" class="error-message">{{ auditRetentionError }}</p>
          <p v-else class="field-hint">{{ t('settings.system.hints.auditLogRetentionDays') }}</p>
        </div>
      </section>

      <!-- Form Actions -->
      <div class="form-actions">
        <button
          type="button"
          class="btn-danger"
          @click="handleResetToDefaults"
          :disabled="isSaving"
        >
          {{ t('settings.system.actions.resetToDefaults') }}
        </button>
        <div class="actions-right">
          <button
            type="button"
            class="btn-secondary"
            @click="loadSettings"
            :disabled="isSaving"
          >
            {{ t('settings.system.actions.cancel') }}
          </button>
          <button
            type="submit"
            class="btn-primary"
            :disabled="isSaving"
          >
            {{ isSaving ? t('settings.system.actions.saving') : t('settings.system.actions.save') }}
          </button>
        </div>
      </div>
    </form>
  </div>
</template>

<style scoped>
.system-settings {
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

/* Form */
.settings-form {
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: 8px;
  padding: 1.5rem;
}

.form-section {
  margin-bottom: 2rem;
  padding-bottom: 2rem;
  border-bottom: 1px solid var(--color-border);
}

.form-section:last-of-type {
  margin-bottom: 1.5rem;
  padding-bottom: 0;
  border-bottom: none;
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

/* Toggle List */
.toggle-list {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}

.toggle-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 1rem;
  background: var(--color-background);
  border: 1px solid var(--color-border);
  border-radius: 6px;
  cursor: pointer;
  transition: background-color 0.2s;
}

.toggle-item:hover {
  background: var(--color-surface-variant);
}

.toggle-info {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
  flex: 1;
}

.toggle-label {
  font-weight: 500;
  color: var(--color-text);
  font-size: 0.9375rem;
}

.toggle-hint {
  font-size: 0.8125rem;
  color: var(--color-text-secondary);
}

.toggle-switch {
  width: 3rem;
  height: 1.5rem;
  flex-shrink: 0;
  cursor: pointer;
}

/* Form Group */
.form-group {
  margin-top: 1.5rem;
}

.form-group label {
  display: block;
  font-weight: 500;
  color: var(--color-text);
  margin-bottom: 0.5rem;
  font-size: 0.9375rem;
}

.form-group input[type="number"] {
  width: 100%;
  max-width: 200px;
  padding: 0.625rem 0.875rem;
  border: 1px solid var(--color-border);
  border-radius: 6px;
  font-size: 0.9375rem;
  background: var(--color-background);
  color: var(--color-text);
  transition: border-color 0.2s;
}

.form-group input[type="number"]:focus {
  outline: none;
  border-color: var(--color-primary);
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

/* Form Actions */
.form-actions {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding-top: 1.5rem;
}

.actions-right {
  display: flex;
  gap: 1rem;
}

.btn-primary,
.btn-secondary,
.btn-danger {
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

.btn-danger {
  background: transparent;
  color: var(--color-danger);
  border: 1px solid var(--color-danger);
}

.btn-danger:hover:not(:disabled) {
  background: var(--color-danger);
  color: white;
}

.btn-primary:disabled,
.btn-secondary:disabled,
.btn-danger:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

/* Mobile Responsiveness */
@media (max-width: 640px) {
  .system-settings {
    padding: 1rem;
  }

  .settings-header h1 {
    font-size: 1.5rem;
  }

  .settings-form {
    padding: 1rem;
  }

  .form-actions {
    flex-direction: column;
    gap: 1rem;
  }

  .btn-danger {
    width: 100%;
    order: 2;
  }

  .actions-right {
    width: 100%;
    flex-direction: column-reverse;
    order: 1;
  }

  .actions-right button {
    width: 100%;
  }

  .form-group input[type="number"] {
    max-width: 100%;
  }
}

/* RTL Support */
[dir="rtl"] .form-actions {
  flex-direction: row-reverse;
}

@media (max-width: 640px) {
  [dir="rtl"] .form-actions {
    flex-direction: column;
  }
}
</style>
