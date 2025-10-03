<template>
  <button
    :type="type"
    :disabled="disabled"
    :class="['app-button', `variant-${variant}`, `size-${size}`, { 'is-loading': loading }]"
    @click="handleClick"
  >
    <span v-if="loading" class="spinner" aria-hidden="true"></span>
    <slot v-else />
  </button>
</template>

<script setup lang="ts">
type ButtonVariant = 'primary' | 'secondary' | 'danger' | 'ghost';
type ButtonSize = 'sm' | 'md' | 'lg';

interface Props {
  type?: 'button' | 'submit' | 'reset';
  variant?: ButtonVariant;
  size?: ButtonSize;
  disabled?: boolean;
  loading?: boolean;
}

withDefaults(defineProps<Props>(), {
  type: 'button',
  variant: 'primary',
  size: 'md',
  disabled: false,
  loading: false
});

const emit = defineEmits<{
  click: [event: MouseEvent];
}>();

function handleClick(event: MouseEvent) {
  emit('click', event);
}
</script>

<style scoped>
.app-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 0.5rem;
  border: none;
  border-radius: 0.5rem;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s ease;
  font-family: inherit;
  white-space: nowrap;
}

.app-button:disabled,
.app-button.is-loading {
  cursor: not-allowed;
  opacity: 0.6;
}

/* Sizes */
.size-sm {
  padding: 0.5rem 0.875rem;
  font-size: 0.8125rem;
}

.size-md {
  padding: 0.75rem 1.25rem;
  font-size: 0.9375rem;
}

.size-lg {
  padding: 1rem 1.5rem;
  font-size: 1rem;
}

/* Variants */
.variant-primary {
  background: var(--color-brand);
  color: var(--color-text-inverse);
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.1);
}

.variant-primary:not(:disabled):not(.is-loading):hover {
  background: var(--color-accent);
  box-shadow: 0 4px 12px rgba(22, 131, 90, 0.25);
  transform: translateY(-1px);
}

.variant-primary:not(:disabled):not(.is-loading):active {
  transform: translateY(0);
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.1);
}

.variant-secondary {
  background: var(--color-surface);
  color: var(--color-text-primary);
  border: 1.5px solid var(--color-border);
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.05);
}

.variant-secondary:not(:disabled):not(.is-loading):hover {
  border-color: var(--color-brand);
  background: var(--color-brand-soft);
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.08);
}

.variant-danger {
  background: var(--color-danger);
  color: var(--color-text-inverse);
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.1);
}

.variant-danger:not(:disabled):not(.is-loading):hover {
  background: var(--color-danger-strong);
  box-shadow: 0 4px 12px rgba(220, 38, 38, 0.25);
  transform: translateY(-1px);
}

.variant-ghost {
  background: transparent;
  color: var(--color-text-primary);
  border: none;
  box-shadow: none;
}

.variant-ghost:not(:disabled):not(.is-loading):hover {
  background: var(--color-surface-alt);
}

/* Loading spinner */
.spinner {
  width: 1rem;
  height: 1rem;
  border: 2px solid currentColor;
  border-right-color: transparent;
  border-radius: 50%;
  animation: spin 0.6s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
