<template>
  <section class="placeholder">
    <h1>{{ t('users.heading') }}</h1>
    <p>{{ t('users.description') }}</p>
  </section>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n';

const { t } = useI18n();
</script>

<style scoped>
.placeholder {
  background: white;
  border-radius: 1rem;
  padding: 2rem;
  box-shadow: 0 10px 40px -24px rgba(15, 23, 42, 0.2);
}

h1 {
  margin: 0 0 0.75rem;
}

p {
  margin: 0;
  color: #475569;
}
</style>
