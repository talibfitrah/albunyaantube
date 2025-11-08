<template>
  <RouterView />
  <ToastContainer />
</template>

<script setup lang="ts">
import ToastContainer from '@/components/common/ToastContainer.vue';
</script>

<style scoped>
:host {
  display: contents;
}
</style>
