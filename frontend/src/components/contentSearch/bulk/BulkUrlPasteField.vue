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
    <label for="bulk-paste-textarea" class="form-label">
      {{ t('contentSearch.bulk.input.pasteLabel') }}
    </label>
    <textarea
      id="bulk-paste-textarea"
      class="form-control"
      rows="6"
      dir="ltr"
      :value="modelValue"
      :placeholder="t('contentSearch.bulk.input.pasteHint')"
      @input="emit('update:modelValue', ($event.target as HTMLTextAreaElement).value)"
    />
    <small :class="{ 'text-danger': tooMany }">
      {{ urlCount }} / 25 URLs
      <span v-if="tooMany">— {{ t('contentSearch.bulk.input.tooMany') }}</span>
    </small>
  </div>
</template>
