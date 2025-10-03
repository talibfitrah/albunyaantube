<template>
  <div :class="['app-card', { 'is-interactive': interactive }]">
    <header v-if="$slots.header" class="card-header">
      <slot name="header" />
    </header>
    <div class="card-body">
      <slot />
    </div>
    <footer v-if="$slots.footer" class="card-footer">
      <slot name="footer" />
    </footer>
  </div>
</template>

<script setup lang="ts">
interface Props {
  interactive?: boolean;
}

withDefaults(defineProps<Props>(), {
  interactive: false
});
</script>

<style scoped>
.app-card {
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: 0.75rem;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.05);
  overflow: hidden;
  transition: all 0.2s ease;
}

.app-card.is-interactive {
  cursor: pointer;
}

.app-card.is-interactive:hover {
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.08);
  transform: translateY(-2px);
  border-color: var(--color-brand);
}

.card-header {
  padding: 1.25rem 1.5rem;
  border-bottom: 1px solid var(--color-border);
  background: var(--color-surface);
}

.card-body {
  padding: 1.5rem;
}

.card-footer {
  padding: 1rem 1.5rem;
  border-top: 1px solid var(--color-border);
  background: var(--color-surface-alt);
}
</style>
