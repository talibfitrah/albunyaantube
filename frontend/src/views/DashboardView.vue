<template>
  <section class="panel">
    <header>
      <h1>{{ t('dashboard.heading') }}</h1>
      <p>{{ t('dashboard.subtitle') }}</p>
    </header>
    <div class="cards">
      <article class="card" v-for="card in cards" :key="card.title">
        <div class="card-title">{{ t(card.title) }}</div>
        <div class="card-value">{{ card.value }}</div>
        <div class="card-caption">{{ t(card.caption) }}</div>
      </article>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { useI18n } from 'vue-i18n';

const { t } = useI18n();

const cards = computed(() => [
  { title: 'dashboard.cards.pendingModeration', value: '—', caption: 'dashboard.cards.pendingModerationCaption' },
  { title: 'dashboard.cards.categories', value: '—', caption: 'dashboard.cards.categoriesCaption' },
  { title: 'dashboard.cards.moderators', value: '—', caption: 'dashboard.cards.moderatorsCaption' }
]);
</script>

<style scoped>
.panel {
  background: var(--color-surface);
  border-radius: 1rem;
  padding: 2rem;
  box-shadow: var(--shadow-elevated);
  display: flex;
  flex-direction: column;
  gap: 2rem;
}

header h1 {
  margin: 0;
  font-size: 1.75rem;
  font-weight: 600;
  color: var(--color-text-primary);
}

header p {
  margin: 0.5rem 0 0;
  color: var(--color-text-secondary);
}

.cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 1.25rem;
}

.card {
  background: linear-gradient(135deg, var(--color-brand), var(--color-accent));
  color: var(--color-text-inverse);
  border-radius: 0.75rem;
  padding: 1.5rem;
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}

.card-title {
  font-size: 0.95rem;
  text-transform: uppercase;
  letter-spacing: 0.08em;
}

.card-value {
  font-size: 2rem;
  font-weight: 700;
}

.card-caption {
  font-size: 0.85rem;
  opacity: 0.85;
}
</style>
