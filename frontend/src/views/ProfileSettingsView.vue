<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { useI18n } from 'vue-i18n';
import { useAuthStore } from '@/stores/auth';

const { t } = useI18n();
const authStore = useAuthStore();

// Form state
const displayName = ref('');
const email = ref('');
const currentPassword = ref('');
const newPassword = ref('');
const confirmPassword = ref('');

// UI state
const isLoading = ref(false);
const isSaving = ref(false);
const error = ref('');
const successMessage = ref('');

// Validation errors
const displayNameError = ref('');
const passwordError = ref('');

onMounted(() => {
  loadProfile();
});

async function loadProfile() {
  isLoading.value = true;
  error.value = '';

  try {
    // Mock data - replace with actual API call
    displayName.value = authStore.user?.displayName || '';
    email.value = authStore.user?.email || '';
  } catch (err) {
    error.value = t('settings.profile.errors.loadFailed');
  } finally {
    isLoading.value = false;
  }
}

function validateDisplayName(): boolean {
  displayNameError.value = '';

  if (!displayName.value.trim()) {
    displayNameError.value = t('settings.profile.errors.displayNameRequired');
    return false;
  }

  if (displayName.value.length < 2) {
    displayNameError.value = t('settings.profile.errors.displayNameTooShort');
    return false;
  }

  return true;
}

function validatePassword(): boolean {
  passwordError.value = '';

  // If any password field is filled, all must be filled
  const anyPasswordFilled = currentPassword.value || newPassword.value || confirmPassword.value;

  if (!anyPasswordFilled) {
    return true; // Password change is optional
  }

  if (!currentPassword.value) {
    passwordError.value = t('settings.profile.errors.currentPasswordRequired');
    return false;
  }

  if (!newPassword.value) {
    passwordError.value = t('settings.profile.errors.newPasswordRequired');
    return false;
  }

  if (newPassword.value.length < 8) {
    passwordError.value = t('settings.profile.errors.passwordTooShort');
    return false;
  }

  if (newPassword.value !== confirmPassword.value) {
    passwordError.value = t('settings.profile.errors.passwordMismatch');
    return false;
  }

  return true;
}

async function handleSaveProfile() {
  successMessage.value = '';
  error.value = '';

  const isDisplayNameValid = validateDisplayName();
  const isPasswordValid = validatePassword();

  if (!isDisplayNameValid || !isPasswordValid) {
    return;
  }

  isSaving.value = true;

  try {
    // Mock save - replace with actual API call
    await new Promise(resolve => setTimeout(resolve, 1000));

    successMessage.value = t('settings.profile.successMessage');

    // Clear password fields on success
    currentPassword.value = '';
    newPassword.value = '';
    confirmPassword.value = '';

    // Auto-hide success message after 3 seconds
    setTimeout(() => {
      successMessage.value = '';
    }, 3000);
  } catch (err) {
    error.value = t('settings.profile.errors.saveFailed');
  } finally {
    isSaving.value = false;
  }
}
</script>

<template>
  <div class="profile-settings">
    <div class="settings-header">
      <h1>{{ t('settings.profile.heading') }}</h1>
      <p class="subtitle">{{ t('settings.profile.description') }}</p>
    </div>

    <!-- Loading State -->
    <div v-if="isLoading" class="loading-state">
      <div class="spinner"></div>
      <p>{{ t('settings.profile.loading') }}</p>
    </div>

    <!-- Settings Form -->
    <form v-else @submit.prevent="handleSaveProfile" class="settings-form">
      <!-- Success Message -->
      <div v-if="successMessage" class="alert alert-success">
        {{ successMessage }}
      </div>

      <!-- Error Message -->
      <div v-if="error" class="alert alert-error">
        {{ error }}
      </div>

      <!-- Profile Information Section -->
      <section class="form-section">
        <h2>{{ t('settings.profile.sections.profileInfo') }}</h2>

        <div class="form-group">
          <label for="email">{{ t('settings.profile.fields.email') }}</label>
          <input
            id="email"
            type="email"
            :value="email"
            disabled
            class="input-disabled"
          />
          <p class="field-hint">{{ t('settings.profile.hints.emailImmutable') }}</p>
        </div>

        <div class="form-group">
          <label for="displayName">{{ t('settings.profile.fields.displayName') }}</label>
          <input
            id="displayName"
            v-model="displayName"
            type="text"
            :class="{ 'input-error': displayNameError }"
            @blur="validateDisplayName"
          />
          <p v-if="displayNameError" class="error-message">{{ displayNameError }}</p>
        </div>
      </section>

      <!-- Change Password Section -->
      <section class="form-section">
        <h2>{{ t('settings.profile.sections.changePassword') }}</h2>
        <p class="section-hint">{{ t('settings.profile.hints.passwordOptional') }}</p>

        <div class="form-group">
          <label for="currentPassword">{{ t('settings.profile.fields.currentPassword') }}</label>
          <input
            id="currentPassword"
            v-model="currentPassword"
            type="password"
            autocomplete="current-password"
            :class="{ 'input-error': passwordError }"
          />
        </div>

        <div class="form-group">
          <label for="newPassword">{{ t('settings.profile.fields.newPassword') }}</label>
          <input
            id="newPassword"
            v-model="newPassword"
            type="password"
            autocomplete="new-password"
            :class="{ 'input-error': passwordError }"
          />
        </div>

        <div class="form-group">
          <label for="confirmPassword">{{ t('settings.profile.fields.confirmPassword') }}</label>
          <input
            id="confirmPassword"
            v-model="confirmPassword"
            type="password"
            autocomplete="new-password"
            :class="{ 'input-error': passwordError }"
            @blur="validatePassword"
          />
          <p v-if="passwordError" class="error-message">{{ passwordError }}</p>
        </div>
      </section>

      <!-- Form Actions -->
      <div class="form-actions">
        <button
          type="button"
          class="btn-secondary"
          @click="loadProfile"
          :disabled="isSaving"
        >
          {{ t('settings.profile.actions.cancel') }}
        </button>
        <button
          type="submit"
          class="btn-primary"
          :disabled="isSaving"
        >
          {{ isSaving ? t('settings.profile.actions.saving') : t('settings.profile.actions.save') }}
        </button>
      </div>
    </form>
  </div>
</template>

<style scoped>
.profile-settings {
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

.form-group input {
  width: 100%;
  padding: 0.625rem 0.875rem;
  border: 1px solid var(--color-border);
  border-radius: 6px;
  font-size: 0.9375rem;
  background: var(--color-background);
  color: var(--color-text);
  transition: border-color 0.2s;
}

.form-group input:focus {
  outline: none;
  border-color: var(--color-primary);
}

.input-disabled {
  background: var(--color-surface-variant) !important;
  color: var(--color-text-secondary) !important;
  cursor: not-allowed;
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
  .profile-settings {
    padding: 1rem;
  }

  .settings-header h1 {
    font-size: 1.5rem;
  }

  .settings-form {
    padding: 1rem;
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
