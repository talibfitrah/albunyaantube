<template>
  <Teleport to="body">
    <div
      v-if="open"
      ref="overlayRef"
      class="modal-overlay"
      role="dialog"
      aria-modal="true"
      :aria-labelledby="titleId"
      tabindex="-1"
      @click.self="$emit('close')"
      @keydown.escape="$emit('close')"
    >
      <div class="modal-content">
        <div class="modal-header">
          <h3 :id="titleId">{{ title }}</h3>
          <button class="close-btn" aria-label="Close" @click="$emit('close')">&times;</button>
        </div>
        <div class="modal-body">
          <iframe
            :src="`https://www.youtube-nocookie.com/embed/${youtubeId}?origin=${embedOrigin}`"
            :title="title"
            width="100%"
            height="360"
            frameborder="0"
            referrerpolicy="no-referrer"
            sandbox="allow-scripts allow-same-origin allow-presentation allow-popups"
            allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture"
            allowfullscreen
          />
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { ref, computed, watch, nextTick, useId } from 'vue';

const props = defineProps<{
  open: boolean;
  youtubeId: string;
  title: string;
}>();

defineEmits<{
  close: [];
}>();

const overlayRef = ref<HTMLElement | null>(null);
const titleId = `video-modal-title-${useId()}`;
const embedOrigin = computed(() => encodeURIComponent(window.location.origin));

watch(() => props.open, async (isOpen) => {
  if (isOpen) {
    await nextTick();
    // Focus the modal overlay so Escape key works immediately
    overlayRef.value?.focus();
  }
});
</script>

<style scoped>
.modal-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.6);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1100;
  outline: none;
}

.modal-content {
  background: var(--color-surface, #fff);
  border-radius: 8px;
  width: 90%;
  max-width: 720px;
  overflow: hidden;
}

.modal-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px;
  border-bottom: 1px solid var(--color-border, #e0e0e0);
}

.modal-header h3 {
  margin: 0;
  font-size: 1.1rem;
}

.close-btn {
  background: none;
  border: none;
  font-size: 1.5rem;
  cursor: pointer;
  color: var(--color-text-secondary, #666);
}

.modal-body {
  padding: 16px;
}
</style>
