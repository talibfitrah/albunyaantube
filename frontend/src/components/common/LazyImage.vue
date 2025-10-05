<template>
  <img
    v-if="isLoaded || shouldLoad"
    ref="imgRef"
    :src="shouldLoad ? src : placeholder"
    :alt="alt"
    :class="['lazy-image', { 'is-loaded': isLoaded }]"
    @load="handleLoad"
    @error="handleError"
  />
  <div v-else class="lazy-image-placeholder" :style="placeholderStyle">
    <slot name="placeholder">
      <div class="lazy-image-skeleton"></div>
    </slot>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount, computed } from 'vue';

const props = withDefaults(
  defineProps<{
    src: string;
    alt: string;
    placeholder?: string;
    width?: number | string;
    height?: number | string;
    eager?: boolean;
  }>(),
  {
    placeholder: 'data:image/svg+xml,%3Csvg xmlns="http://www.w3.org/2000/svg"%3E%3C/svg%3E',
    eager: false
  }
);

const imgRef = ref<HTMLImageElement | null>(null);
const isLoaded = ref(false);
const shouldLoad = ref(props.eager);
const hasError = ref(false);

let observer: IntersectionObserver | null = null;

const placeholderStyle = computed(() => ({
  width: typeof props.width === 'number' ? `${props.width}px` : props.width,
  height: typeof props.height === 'number' ? `${props.height}px` : props.height
}));

onMounted(() => {
  if (props.eager) {
    return;
  }

  observer = new IntersectionObserver(
    (entries) => {
      entries.forEach((entry) => {
        if (entry.isIntersecting) {
          shouldLoad.value = true;
          if (observer && imgRef.value) {
            observer.unobserve(entry.target);
          }
        }
      });
    },
    {
      rootMargin: '50px' // Start loading 50px before visible
    }
  );

  if (imgRef.value) {
    observer.observe(imgRef.value);
  }
});

onBeforeUnmount(() => {
  if (observer && imgRef.value) {
    observer.unobserve(imgRef.value);
  }
});

function handleLoad() {
  isLoaded.value = true;
}

function handleError() {
  hasError.value = true;
  isLoaded.value = true;
}
</script>

<style scoped>
.lazy-image {
  opacity: 0;
  transition: opacity 0.3s ease-in-out;
}

.lazy-image.is-loaded {
  opacity: 1;
}

.lazy-image-placeholder {
  display: inline-block;
  background: var(--color-surface-alt);
  border-radius: 4px;
}

.lazy-image-skeleton {
  width: 100%;
  height: 100%;
  background: linear-gradient(
    90deg,
    var(--color-surface-alt) 25%,
    var(--color-border) 50%,
    var(--color-surface-alt) 75%
  );
  background-size: 200% 100%;
  animation: shimmer 1.5s infinite;
}

@keyframes shimmer {
  0% {
    background-position: 200% 0;
  }
  100% {
    background-position: -200% 0;
  }
}
</style>
