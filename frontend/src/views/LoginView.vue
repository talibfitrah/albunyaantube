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
  background: radial-gradient(circle at 10% 20%, rgba(14, 165, 233, 0.18) 0%, transparent 60%),
    radial-gradient(circle at 90% 10%, rgba(99, 102, 241, 0.24) 0%, transparent 55%),
    #0f172a;
}

.panel {
  width: min(420px, 100%);
  background: white;
  padding: 2.5rem 2.25rem;
  border-radius: 1.25rem;
  box-shadow: 0 40px 80px -32px rgba(15, 23, 42, 0.35);
  display: flex;
  flex-direction: column;
  gap: 2rem;
}

header h1 {
  margin: 0;
  font-size: 1.75rem;
  font-weight: 700;
  color: #0f172a;
}

header p {
  margin: 0.5rem 0 0;
  color: #475569;
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
  color: #0f172a;
}

input {
  padding: 0.75rem 0.85rem;
  border-radius: 0.75rem;
  border: 1px solid #cbd5f5;
  background: #f8fafc;
  transition: border 0.2s ease, box-shadow 0.2s ease;
}

input:focus {
  outline: none;
  border-color: #6366f1;
  box-shadow: 0 0 0 4px rgba(99, 102, 241, 0.15);
}

.error {
  color: #dc2626;
  margin: 0;
  font-size: 0.875rem;
}

.submit {
  border: none;
  border-radius: 0.75rem;
  padding: 0.85rem 1rem;
  background: linear-gradient(120deg, #6366f1, #0ea5e9);
  color: white;
  font-weight: 600;
  cursor: pointer;
  transition: filter 0.2s ease;
}

.submit:disabled {
  cursor: progress;
  opacity: 0.7;
}

.submit:not(:disabled):hover {
  filter: brightness(1.05);
}
</style>
