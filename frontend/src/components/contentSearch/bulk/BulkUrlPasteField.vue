<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { parsePastedUrls } from '@/utils/bulkFileParsers'

const { t } = useI18n()

const props = defineProps<{ modelValue: string }>()
const emit = defineEmits<{ (e: 'update:modelValue', v: string): void }>()

const urlCount = computed(() => parsePastedUrls(props.modelValue).length)
const tooMany = computed(() => urlCount.value > 25)
</script>

<template>
  <div class="bulk-paste">
    <label for="bulk-paste-textarea" class="bulk-paste-label">
      {{ t('contentSearch.bulk.input.pasteLabel') }}
    </label>
    <textarea
      id="bulk-paste-textarea"
      class="bulk-paste-textarea"
      rows="6"
      dir="ltr"
      :value="modelValue"
      :placeholder="t('contentSearch.bulk.input.pasteHint')"
      @input="emit('update:modelValue', ($event.target as HTMLTextAreaElement).value)"
    />
    <small class="bulk-paste-count" :class="{ 'is-too-many': tooMany }">
      {{ urlCount }} / 25 URLs
      <span v-if="tooMany">— {{ t('contentSearch.bulk.input.tooMany') }}</span>
    </small>
  </div>
</template>

<style scoped>
.bulk-paste {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.bulk-paste-label {
  font-weight: 600;
  font-size: 0.875rem;
  color: var(--color-text-primary);
}

.bulk-paste-textarea {
  width: 100%;
  padding: 0.75rem 1rem;
  border: 1.5px solid var(--color-border);
  border-radius: 0.5rem;
  background: var(--color-surface);
  color: var(--color-text-primary);
  font-size: 0.875rem;
  font-family: 'Menlo', 'Monaco', 'Courier New', monospace;
  resize: vertical;
  min-height: 9rem;
  transition: all 0.2s ease;
}

.bulk-paste-textarea:hover {
  border-color: var(--color-brand);
}

.bulk-paste-textarea:focus {
  outline: none;
  border-color: var(--color-brand);
  box-shadow: 0 0 0 3px rgba(22, 131, 90, 0.1);
}

.bulk-paste-count {
  font-size: 0.8125rem;
  color: var(--color-text-secondary);
}

.bulk-paste-count.is-too-many {
  color: var(--color-danger-strong);
  font-weight: 600;
}
</style>
