<template>
  <div class="auth-shell">
    <section class="panel">
      <header>
        <h1>{{ t('auth.title') }}</h1>
        <p>{{ t('auth.subtitle') }}</p>
      </header>

      <!-- Linking flow: shown when a Google sign-in landed on a
           password-provisioned email. Hides the normal forms until the
           user submits the password or cancels. -->
      <template v-if="authStore.linkingRequired">
        <form @submit.prevent="handleLinkSubmit" novalidate>
          <p class="link-explainer">
            {{ t('auth.linking.explainer', { email: authStore.linkingEmail }) }}
          </p>
          <div class="field">
            <label for="link-password">{{ t('auth.password') }}</label>
            <input
              id="link-password"
              v-model="linkForm.password"
              type="password"
              autocomplete="current-password"
              :aria-invalid="Boolean(linkFieldError)"
              :aria-describedby="linkFieldError ? 'link-password-error' : undefined"
              required
            />
            <p v-if="linkFieldError" class="error" id="link-password-error">{{ linkFieldError }}</p>
          </div>
          <p v-if="authStore.error" class="error" role="alert">{{ authStore.error }}</p>
          <button class="submit" type="submit" :disabled="authStore.isLoading">
            <span v-if="authStore.isLoading">{{ t('auth.linking.submitting') }}</span>
            <span v-else>{{ t('auth.linking.submit') }}</span>
          </button>
          <button type="button" class="link-cancel" :disabled="authStore.isLoading" @click="cancelLink">
            {{ t('auth.linking.cancel') }}
          </button>
        </form>
      </template>

      <template v-else>
        <form @submit.prevent="handleSubmit" novalidate>
          <div class="field">
            <label for="email">{{ t('auth.email') }}</label>
            <input
              id="email"
              v-model="form.email"
              type="email"
              autocomplete="username"
              :aria-invalid="Boolean(fieldErrors.email)"
              :aria-describedby="fieldErrors.email ? 'email-error' : undefined"
              required
            />
            <p v-if="fieldErrors.email" class="error" id="email-error">{{ fieldErrors.email }}</p>
          </div>
          <div class="field">
            <label for="password">{{ t('auth.password') }}</label>
            <input
              id="password"
              v-model="form.password"
              type="password"
              autocomplete="current-password"
              :aria-invalid="Boolean(fieldErrors.password)"
              :aria-describedby="fieldErrors.password ? 'password-error' : undefined"
              required
            />
            <p v-if="fieldErrors.password" class="error" id="password-error">{{ fieldErrors.password }}</p>
          </div>
          <p v-if="authStore.error" class="error" role="alert">{{ authStore.error }}</p>
          <button class="submit" type="submit" :disabled="authStore.isLoading">
            <span v-if="authStore.isLoading">{{ t('auth.signingIn') }}</span>
            <span v-else>{{ t('auth.signIn') }}</span>
          </button>
        </form>

        <div class="divider" role="separator">
          <span>{{ t('auth.orDivider') }}</span>
        </div>

        <button
          type="button"
          class="google-button"
          :disabled="authStore.isLoading"
          @click="handleGoogleSignIn"
        >
          <svg class="google-icon" viewBox="0 0 18 18" aria-hidden="true">
            <path fill="#4285F4" d="M17.64 9.2c0-.637-.057-1.251-.164-1.84H9v3.481h4.844a4.14 4.14 0 0 1-1.796 2.717v2.258h2.908c1.702-1.567 2.684-3.874 2.684-6.615z"/>
            <path fill="#34A853" d="M9 18c2.43 0 4.467-.806 5.956-2.18l-2.908-2.259c-.806.54-1.837.86-3.048.86-2.344 0-4.328-1.584-5.036-3.71H.957v2.332A8.997 8.997 0 0 0 9 18z"/>
            <path fill="#FBBC05" d="M3.964 10.71A5.41 5.41 0 0 1 3.682 9c0-.593.102-1.17.282-1.71V4.958H.957A8.996 8.996 0 0 0 0 9c0 1.452.348 2.827.957 4.042l3.007-2.332z"/>
            <path fill="#EA4335" d="M9 3.58c1.321 0 2.508.454 3.44 1.345l2.582-2.58C13.463.891 11.426 0 9 0A8.997 8.997 0 0 0 .957 4.958L3.964 7.29C4.672 5.163 6.656 3.58 9 3.58z"/>
          </svg>
          <span>{{ t('auth.signInWithGoogle') }}</span>
        </button>
      </template>
    </section>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue';
import { useRouter, useRoute } from 'vue-router';
import { useI18n } from 'vue-i18n';
import { z } from 'zod';
import { useAuthStore } from '@/stores/auth';

const authStore = useAuthStore();
const router = useRouter();
const route = useRoute();
const { t } = useI18n();

const form = reactive({
  email: '',
  password: ''
});

const linkForm = reactive({
  password: ''
});

const linkFieldError = ref('');

const fieldErrors = reactive<{ email: string; password: string }>({
  email: '',
  password: ''
});

const schema = z.object({
  email: z.string().email({ message: t('auth.errors.invalidEmail') }),
  password: z.string().min(8, { message: t('auth.errors.passwordLength') })
});

// Only accept same-origin absolute paths as redirect targets. Reject
// protocol-relative ('//evil.example') and absolute URLs to prevent
// open-redirect via ?redirect=… on the login page.
// Cubic R1 P2: also reject backslash variants like '/\evil.example' —
// browsers normalize '\' to '/' in Location headers, so this would
// effectively become '//evil.example' if forwarded to URL APIs.
function safeRedirect(raw: unknown): string | null {
  if (typeof raw !== 'string') return null;
  // Normalize backslashes to forward slashes before the prefix check so
  // mixed-slash variants are caught by the same rules below.
  const normalized = raw.replace(/\\/g, '/');
  if (!normalized.startsWith('/')) return null;
  if (normalized.startsWith('//')) return null;
  return normalized;
}

async function handleSubmit() {
  const result = schema.safeParse(form);
  fieldErrors.email = '';
  fieldErrors.password = '';

  if (!result.success) {
    for (const issue of result.error.issues) {
      if (issue.path[0] === 'email') {
        fieldErrors.email = issue.message;
      }
      if (issue.path[0] === 'password') {
        fieldErrors.password = issue.message;
      }
    }
    return;
  }

  const redirect = safeRedirect(route.query.redirect);
  const success = await authStore.login({ email: result.data.email, password: result.data.password });
  if (success) {
    router.replace(redirect ?? { name: 'dashboard' });
  }
}

async function handleGoogleSignIn() {
  // Clear any stale field errors from a prior email/password attempt so
  // the user doesn't see leftover red chips after a successful Google
  // sign-in (cosmetic — the auth store's `error` is already replaced).
  fieldErrors.email = '';
  fieldErrors.password = '';
  const redirect = safeRedirect(route.query.redirect);
  const success = await authStore.loginWithGoogle();
  if (success) {
    router.replace(redirect ?? { name: 'dashboard' });
  }
  // If !success and authStore.linkingRequired became true, the template
  // will switch to the linking form on the next render — no extra logic
  // needed here.
}

async function handleLinkSubmit() {
  // Reuse the same password-length rule the email/password form enforces
  // so users get consistent client-side feedback before any Firebase call.
  linkFieldError.value = '';
  const passwordResult = z
    .string()
    .min(8, { message: t('auth.errors.passwordLength') })
    .safeParse(linkForm.password);
  if (!passwordResult.success) {
    linkFieldError.value = passwordResult.error.issues[0]?.message ?? '';
    return;
  }
  const redirect = safeRedirect(route.query.redirect);
  const success = await authStore.completeGoogleLink(linkForm.password);
  if (success) {
    linkForm.password = '';
    router.replace(redirect ?? { name: 'dashboard' });
  }
}

function cancelLink() {
  linkForm.password = '';
  linkFieldError.value = '';
  authStore.cancelGoogleLink();
}
</script>

<style scoped>
.auth-shell {
  min-height: 100vh;
  display: grid;
  place-items: center;
  padding: 2rem 1rem;
  background: linear-gradient(135deg, #123d32 0%, #16835a 100%);
  position: relative;
}

.auth-shell::before {
  content: '';
  position: absolute;
  inset: 0;
  background: radial-gradient(circle at 20% 30%, rgba(47, 161, 114, 0.2) 0%, transparent 60%),
    radial-gradient(circle at 80% 70%, rgba(22, 131, 90, 0.15) 0%, transparent 50%);
  pointer-events: none;
}

.panel {
  width: min(440px, 100%);
  background: var(--color-surface);
  padding: 3rem 2.5rem;
  border-radius: 1rem;
  box-shadow: 0 24px 64px -16px rgba(0, 0, 0, 0.3);
  display: flex;
  flex-direction: column;
  gap: 2rem;
  position: relative;
  z-index: 1;
}

header h1 {
  margin: 0;
  font-size: 2rem;
  font-weight: 700;
  color: var(--color-text-primary);
  letter-spacing: -0.02em;
}

header p {
  margin: 0.75rem 0 0;
  color: var(--color-text-secondary);
  font-size: 0.9375rem;
}

form {
  display: flex;
  flex-direction: column;
  gap: 1.25rem;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

label {
  font-weight: 600;
  color: var(--color-text-primary);
  font-size: 0.875rem;
}

input {
  padding: 0.875rem 1rem;
  border-radius: 0.5rem;
  border: 1.5px solid var(--color-border);
  background: var(--color-surface);
  transition: all 0.2s ease;
  font-size: 0.9375rem;
}

input:hover {
  border-color: var(--color-brand);
}

input:focus-visible {
  border-color: var(--color-brand);
  box-shadow: 0 0 0 3px rgba(22, 131, 90, 0.1);
  outline: none;
}

.error {
  color: var(--color-danger);
  margin: 0;
  font-size: 0.875rem;
}

.submit {
  border: none;
  border-radius: 0.5rem;
  padding: 1rem;
  background: var(--color-brand);
  color: var(--color-text-inverse);
  font-weight: 600;
  font-size: 0.9375rem;
  cursor: pointer;
  transition: all 0.2s ease;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.1);
}

.submit:disabled {
  cursor: not-allowed;
  opacity: 0.6;
}

.submit:not(:disabled):hover {
  background: var(--color-accent);
  box-shadow: 0 4px 12px rgba(22, 131, 90, 0.25);
  transform: translateY(-1px);
}

.submit:not(:disabled):active {
  transform: translateY(0);
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.1);
}

.divider {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  color: var(--color-text-secondary);
  font-size: 0.8125rem;
  font-weight: 500;
  text-transform: uppercase;
  letter-spacing: 0.05em;
}

.divider::before,
.divider::after {
  content: '';
  flex: 1;
  height: 1px;
  background: var(--color-border);
}

.google-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 0.75rem;
  width: 100%;
  padding: 0.875rem 1rem;
  border-radius: 0.5rem;
  border: 1.5px solid var(--color-border);
  background: var(--color-surface);
  color: var(--color-text-primary);
  font-weight: 600;
  font-size: 0.9375rem;
  cursor: pointer;
  transition: all 0.2s ease;
}

.google-button:not(:disabled):hover {
  border-color: var(--color-brand);
  box-shadow: 0 4px 12px rgba(22, 131, 90, 0.12);
  transform: translateY(-1px);
}

.google-button:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.google-icon {
  width: 18px;
  height: 18px;
  flex-shrink: 0;
}

.link-explainer {
  margin: 0 0 0.5rem;
  padding: 0.875rem 1rem;
  background: var(--color-brand-soft);
  border: 1px solid var(--color-brand);
  border-radius: 0.5rem;
  color: var(--color-text-primary);
  font-size: 0.875rem;
  line-height: 1.5;
}

.link-cancel {
  margin-top: 0.5rem;
  width: 100%;
  padding: 0.75rem;
  background: transparent;
  border: none;
  color: var(--color-text-secondary);
  font-size: 0.875rem;
  font-weight: 500;
  cursor: pointer;
  border-radius: 0.5rem;
}

.link-cancel:hover:not(:disabled) {
  color: var(--color-text-primary);
  background: var(--color-surface-alt);
}

.link-cancel:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
</style>
