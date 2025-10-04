<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { useI18n } from 'vue-i18n';

const { t } = useI18n();

// Notification preferences
const emailNotifications = ref({
  newApprovals: true,
  approvalDecisions: true,
  categoryChanges: false,
  userActivity: true,
  systemAlerts: true,
  weeklyDigest: false
});

const inAppNotifications = ref({
  newApprovals: true,
  approvalDecisions: true,
  categoryChanges: true,
  userActivity: false,
  systemAlerts: true
});

const notificationFrequency = ref<'realtime' | 'hourly' | 'daily'>('realtime');

// UI state
const isLoading = ref(false);
const isSaving = ref(false);
const error = ref('');
const successMessage = ref('');

onMounted(() => {
  loadPreferences();
});

async function loadPreferences() {
  isLoading.value = true;
  error.value = '';

  try {
    // Mock data - replace with actual API call
    await new Promise(resolve => setTimeout(resolve, 300));
    // Preferences are already set in refs above
  } catch (err) {
    error.value = t('settings.notifications.errors.loadFailed');
  } finally {
    isLoading.value = false;
  }
}

async function handleSavePreferences() {
  successMessage.value = '';
  error.value = '';
  isSaving.value = true;

  try {
    // Mock save - replace with actual API call
    await new Promise(resolve => setTimeout(resolve, 800));

    successMessage.value = t('settings.notifications.successMessage');

    // Auto-hide success message after 3 seconds
    setTimeout(() => {
      successMessage.value = '';
    }, 3000);
  } catch (err) {
    error.value = t('settings.notifications.errors.saveFailed');
  } finally {
    isSaving.value = false;
  }
}

function toggleAllEmail(enabled: boolean) {
  Object.keys(emailNotifications.value).forEach(key => {
    emailNotifications.value[key as keyof typeof emailNotifications.value] = enabled;
  });
}

function toggleAllInApp(enabled: boolean) {
  Object.keys(inAppNotifications.value).forEach(key => {
    inAppNotifications.value[key as keyof typeof inAppNotifications.value] = enabled;
  });
}
</script>

<template>
  <div class="notifications-settings">
    <div class="settings-header">
      <h1>{{ t('settings.notifications.heading') }}</h1>
      <p class="subtitle">{{ t('settings.notifications.description') }}</p>
    </div>

    <!-- Loading State -->
    <div v-if="isLoading" class="loading-state">
      <div class="spinner"></div>
      <p>{{ t('settings.notifications.loading') }}</p>
    </div>

    <!-- Settings Form -->
    <form v-else @submit.prevent="handleSavePreferences" class="settings-form">
      <!-- Success Message -->
      <div v-if="successMessage" class="alert alert-success">
        {{ successMessage }}
      </div>

      <!-- Error Message -->
      <div v-if="error" class="alert alert-error">
        {{ error }}
      </div>

      <!-- Email Notifications Section -->
      <section class="form-section">
        <div class="section-header">
          <h2>{{ t('settings.notifications.sections.email') }}</h2>
          <div class="toggle-all">
            <button type="button" @click="toggleAllEmail(true)" class="btn-link">
              {{ t('settings.notifications.actions.enableAll') }}
            </button>
            <span class="separator">|</span>
            <button type="button" @click="toggleAllEmail(false)" class="btn-link">
              {{ t('settings.notifications.actions.disableAll') }}
            </button>
          </div>
        </div>

        <div class="preferences-list">
          <label class="preference-item">
            <input type="checkbox" v-model="emailNotifications.newApprovals" />
            <div class="preference-info">
              <span class="preference-label">{{ t('settings.notifications.preferences.newApprovals') }}</span>
              <span class="preference-hint">{{ t('settings.notifications.hints.newApprovals') }}</span>
            </div>
          </label>

          <label class="preference-item">
            <input type="checkbox" v-model="emailNotifications.approvalDecisions" />
            <div class="preference-info">
              <span class="preference-label">{{ t('settings.notifications.preferences.approvalDecisions') }}</span>
              <span class="preference-hint">{{ t('settings.notifications.hints.approvalDecisions') }}</span>
            </div>
          </label>

          <label class="preference-item">
            <input type="checkbox" v-model="emailNotifications.categoryChanges" />
            <div class="preference-info">
              <span class="preference-label">{{ t('settings.notifications.preferences.categoryChanges') }}</span>
              <span class="preference-hint">{{ t('settings.notifications.hints.categoryChanges') }}</span>
            </div>
          </label>

          <label class="preference-item">
            <input type="checkbox" v-model="emailNotifications.userActivity" />
            <div class="preference-info">
              <span class="preference-label">{{ t('settings.notifications.preferences.userActivity') }}</span>
              <span class="preference-hint">{{ t('settings.notifications.hints.userActivity') }}</span>
            </div>
          </label>

          <label class="preference-item">
            <input type="checkbox" v-model="emailNotifications.systemAlerts" />
            <div class="preference-info">
              <span class="preference-label">{{ t('settings.notifications.preferences.systemAlerts') }}</span>
              <span class="preference-hint">{{ t('settings.notifications.hints.systemAlerts') }}</span>
            </div>
          </label>

          <label class="preference-item">
            <input type="checkbox" v-model="emailNotifications.weeklyDigest" />
            <div class="preference-info">
              <span class="preference-label">{{ t('settings.notifications.preferences.weeklyDigest') }}</span>
              <span class="preference-hint">{{ t('settings.notifications.hints.weeklyDigest') }}</span>
            </div>
          </label>
        </div>
      </section>

      <!-- In-App Notifications Section -->
      <section class="form-section">
        <div class="section-header">
          <h2>{{ t('settings.notifications.sections.inApp') }}</h2>
          <div class="toggle-all">
            <button type="button" @click="toggleAllInApp(true)" class="btn-link">
              {{ t('settings.notifications.actions.enableAll') }}
            </button>
            <span class="separator">|</span>
            <button type="button" @click="toggleAllInApp(false)" class="btn-link">
              {{ t('settings.notifications.actions.disableAll') }}
            </button>
          </div>
        </div>

        <div class="preferences-list">
          <label class="preference-item">
            <input type="checkbox" v-model="inAppNotifications.newApprovals" />
            <div class="preference-info">
              <span class="preference-label">{{ t('settings.notifications.preferences.newApprovals') }}</span>
            </div>
          </label>

          <label class="preference-item">
            <input type="checkbox" v-model="inAppNotifications.approvalDecisions" />
            <div class="preference-info">
              <span class="preference-label">{{ t('settings.notifications.preferences.approvalDecisions') }}</span>
            </div>
          </label>

          <label class="preference-item">
            <input type="checkbox" v-model="inAppNotifications.categoryChanges" />
            <div class="preference-info">
              <span class="preference-label">{{ t('settings.notifications.preferences.categoryChanges') }}</span>
            </div>
          </label>

          <label class="preference-item">
            <input type="checkbox" v-model="inAppNotifications.userActivity" />
            <div class="preference-info">
              <span class="preference-label">{{ t('settings.notifications.preferences.userActivity') }}</span>
            </div>
          </label>

          <label class="preference-item">
            <input type="checkbox" v-model="inAppNotifications.systemAlerts" />
            <div class="preference-info">
              <span class="preference-label">{{ t('settings.notifications.preferences.systemAlerts') }}</span>
            </div>
          </label>
        </div>
      </section>

      <!-- Notification Frequency Section -->
      <section class="form-section">
        <h2>{{ t('settings.notifications.sections.frequency') }}</h2>
        <p class="section-hint">{{ t('settings.notifications.hints.frequency') }}</p>

        <div class="radio-group">
          <label class="radio-item">
            <input type="radio" v-model="notificationFrequency" value="realtime" />
            <div class="radio-info">
              <span class="radio-label">{{ t('settings.notifications.frequency.realtime') }}</span>
              <span class="radio-hint">{{ t('settings.notifications.hints.realtime') }}</span>
            </div>
          </label>

          <label class="radio-item">
            <input type="radio" v-model="notificationFrequency" value="hourly" />
            <div class="radio-info">
              <span class="radio-label">{{ t('settings.notifications.frequency.hourly') }}</span>
              <span class="radio-hint">{{ t('settings.notifications.hints.hourly') }}</span>
            </div>
          </label>

          <label class="radio-item">
            <input type="radio" v-model="notificationFrequency" value="daily" />
            <div class="radio-info">
              <span class="radio-label">{{ t('settings.notifications.frequency.daily') }}</span>
              <span class="radio-hint">{{ t('settings.notifications.hints.daily') }}</span>
            </div>
          </label>
        </div>
      </section>

      <!-- Form Actions -->
      <div class="form-actions">
        <button
          type="button"
          class="btn-secondary"
          @click="loadPreferences"
          :disabled="isSaving"
        >
          {{ t('settings.notifications.actions.cancel') }}
        </button>
        <button
          type="submit"
          class="btn-primary"
          :disabled="isSaving"
        >
          {{ isSaving ? t('settings.notifications.actions.saving') : t('settings.notifications.actions.save') }}
        </button>
      </div>
    </form>
  </div>
</template>

<style scoped>
.notifications-settings {
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

.section-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 1.5rem;
}

.section-header h2 {
  font-size: 1.25rem;
  font-weight: 600;
  color: var(--color-text);
}

.toggle-all {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.btn-link {
  background: none;
  border: none;
  color: var(--color-primary);
  font-size: 0.875rem;
  cursor: pointer;
  padding: 0.25rem 0.5rem;
}

.btn-link:hover {
  text-decoration: underline;
}

.separator {
  color: var(--color-text-secondary);
  font-size: 0.875rem;
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

/* Preferences List */
.preferences-list {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}

.preference-item {
  display: flex;
  align-items: flex-start;
  gap: 1rem;
  padding: 1rem;
  border: 1px solid var(--color-border);
  border-radius: 6px;
  cursor: pointer;
  transition: background-color 0.2s;
}

.preference-item:hover {
  background: var(--color-surface-variant);
}

.preference-item input[type="checkbox"] {
  margin-top: 0.125rem;
  width: 1.125rem;
  height: 1.125rem;
  cursor: pointer;
  flex-shrink: 0;
}

.preference-info {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
  flex: 1;
}

.preference-label {
  font-weight: 500;
  color: var(--color-text);
  font-size: 0.9375rem;
}

.preference-hint {
  font-size: 0.8125rem;
  color: var(--color-text-secondary);
}

/* Radio Group */
.radio-group {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}

.radio-item {
  display: flex;
  align-items: flex-start;
  gap: 1rem;
  padding: 1rem;
  border: 1px solid var(--color-border);
  border-radius: 6px;
  cursor: pointer;
  transition: background-color 0.2s;
}

.radio-item:hover {
  background: var(--color-surface-variant);
}

.radio-item input[type="radio"] {
  margin-top: 0.125rem;
  width: 1.125rem;
  height: 1.125rem;
  cursor: pointer;
  flex-shrink: 0;
}

.radio-info {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
  flex: 1;
}

.radio-label {
  font-weight: 500;
  color: var(--color-text);
  font-size: 0.9375rem;
}

.radio-hint {
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

/* Form Actions */
.form-actions {
  display: flex;
  gap: 1rem;
  justify-content: flex-end;
  padding-top: 1.5rem;
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

/* Mobile Responsiveness */
@media (max-width: 640px) {
  .notifications-settings {
    padding: 1rem;
  }

  .settings-header h1 {
    font-size: 1.5rem;
  }

  .settings-form {
    padding: 1rem;
  }

  .section-header {
    flex-direction: column;
    align-items: flex-start;
    gap: 0.75rem;
  }

  .form-actions {
    flex-direction: column-reverse;
  }

  .form-actions button {
    width: 100%;
  }
}

/* RTL Support - Reverse button order (primary on left) */
[dir="rtl"] .form-actions {
  flex-direction: row-reverse;
  justify-content: flex-end;
}

@media (max-width: 640px) {
  [dir="rtl"] .form-actions {
    flex-direction: column-reverse;
  }
}
</style>
