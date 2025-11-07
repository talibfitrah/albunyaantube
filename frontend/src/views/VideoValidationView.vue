<template>
  <div class="view-container">
    <VideoValidationPanel />
  </div>
</template>

<script setup lang="ts">
import VideoValidationPanel from '@/components/admin/VideoValidationPanel.vue';
</script>

<style scoped>
.view-container {
  max-width: 1200px;
  margin: 0 auto;
  padding: 2rem;
}

@media (max-width: 767px) {
  .view-container {
    padding: 1rem;
  }
}
</style>
