<template>
  <section class="placeholder">
    <h1>{{ t('audit.heading') }}</h1>
    <p>{{ t('audit.description') }}</p>
  </section>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n';

const { t } = useI18n();
</script>

<style scoped>
.placeholder {
  background: var(--color-surface);
  border-radius: 1rem;
  padding: 2rem;
  box-shadow: var(--shadow-elevated);
}

h1 {
  margin: 0 0 0.75rem;
}

p {
  margin: 0;
  color: var(--color-text-secondary);
}
</style>
