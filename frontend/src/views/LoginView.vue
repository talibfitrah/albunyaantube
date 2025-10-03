<template>
  <div class="auth-shell">
    <section class="panel">
      <header>
        <h1>{{ t('auth.title') }}</h1>
        <p>{{ t('auth.subtitle') }}</p>
      </header>
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
    </section>
  </div>
</template>

<script setup lang="ts">
import { reactive } from 'vue';
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

const fieldErrors = reactive<{ email: string; password: string }>({
  email: '',
  password: ''
});

const schema = z.object({
  email: z.string().email({ message: t('auth.errors.invalidEmail') }),
  password: z.string().min(8, { message: t('auth.errors.passwordLength') })
});

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

  const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : undefined;
  const success = await authStore.login({ email: result.data.email, password: result.data.password });
  if (success) {
    router.replace(redirect ?? { name: 'dashboard' });
  }
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
</style>
