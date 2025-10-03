<template>
  <div class="app-input-wrapper">
    <label v-if="label" :for="id" class="input-label">
      {{ label }}
      <span v-if="required" class="required-mark" aria-label="required">*</span>
    </label>
    <div class="input-container">
      <input
        :id="id"
        :type="type"
        :value="modelValue"
        :placeholder="placeholder"
        :disabled="disabled"
        :required="required"
        :aria-invalid="!!error"
        :aria-describedby="error ? `${id}-error` : undefined"
        :class="['input-field', { 'has-error': !!error }]"
        @input="handleInput"
        @blur="handleBlur"
      />
    </div>
    <p v-if="error" :id="`${id}-error`" class="input-error">{{ error }}</p>
    <p v-else-if="hint" class="input-hint">{{ hint }}</p>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';

interface Props {
  id: string;
  modelValue: string;
  type?: 'text' | 'email' | 'password' | 'number' | 'tel' | 'url';
  label?: string;
  placeholder?: string;
  disabled?: boolean;
  required?: boolean;
  error?: string;
  hint?: string;
}

withDefaults(defineProps<Props>(), {
  type: 'text',
  disabled: false,
  required: false
});

const emit = defineEmits<{
  'update:modelValue': [value: string];
  blur: [event: FocusEvent];
}>();

function handleInput(event: Event) {
  const target = event.target as HTMLInputElement;
  emit('update:modelValue', target.value);
}

function handleBlur(event: FocusEvent) {
  emit('blur', event);
}
</script>

<style scoped>
.app-input-wrapper {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.input-label {
  font-weight: 600;
  color: var(--color-text-primary);
  font-size: 0.875rem;
  display: flex;
  align-items: center;
  gap: 0.25rem;
}

.required-mark {
  color: var(--color-danger);
  font-weight: 700;
}

.input-container {
  position: relative;
}

.input-field {
  width: 100%;
  padding: 0.875rem 1rem;
  border-radius: 0.5rem;
  border: 1.5px solid var(--color-border);
  background: var(--color-surface);
  color: var(--color-text-primary);
  font-size: 0.9375rem;
  font-family: inherit;
  transition: all 0.2s ease;
}

.input-field::placeholder {
  color: var(--color-text-secondary);
  opacity: 0.6;
}

.input-field:hover:not(:disabled) {
  border-color: var(--color-brand);
}

.input-field:focus {
  outline: none;
  border-color: var(--color-brand);
  box-shadow: 0 0 0 3px rgba(22, 131, 90, 0.1);
}

.input-field:disabled {
  background: var(--color-surface-alt);
  cursor: not-allowed;
  opacity: 0.6;
}

.input-field.has-error {
  border-color: var(--color-danger);
}

.input-field.has-error:focus {
  box-shadow: 0 0 0 3px rgba(220, 38, 38, 0.1);
}

.input-error {
  margin: 0;
  font-size: 0.8125rem;
  color: var(--color-danger);
  font-weight: 500;
}

.input-hint {
  margin: 0;
  font-size: 0.8125rem;
  color: var(--color-text-secondary);
}
</style>
