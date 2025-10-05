<template>
  <div v-if="isOpen" class="drawer-overlay" @click="handleClose">
    <div class="drawer" @click.stop>
      <header class="drawer-header">
        <h2>{{ channel?.name || 'Channel Preview' }}</h2>
        <button type="button" class="close-button" @click="handleClose">✕</button>
      </header>

      <div v-if="loading" class="drawer-loading">
        <div class="spinner"></div>
        <p>Loading channel details...</p>
      </div>

      <div v-else-if="channel" class="drawer-content">
        <div class="channel-info">
          <img v-if="channel.avatarUrl" :src="channel.avatarUrl" :alt="channel.name || ''" class="channel-avatar" />
          <div class="channel-meta">
            <p class="subscriber-count">{{ channel.subscriberCount.toLocaleString() }} subscribers</p>
            <div class="categories">
              <span v-for="cat in channel.categories" :key="cat.id" class="category-tag">{{ cat.label }}</span>
            </div>
          </div>
        </div>

        <div class="tabs">
          <button
            v-for="tab in tabs"
            :key="tab.value"
            type="button"
            :class="['tab', { active: activeTab === tab.value }]"
            @click="activeTab = tab.value"
          >
            {{ tab.label }}
          </button>
        </div>

        <div class="tab-content">
          <div v-if="activeTab === 'videos'" class="items-list">
            <div v-for="video in videos" :key="video.id" class="item-card">
              <img :src="video.thumbnailUrl || ''" :alt="video.title || ''" class="item-thumbnail" />
              <div class="item-info">
                <h4>{{ video.title }}</h4>
                <p>{{ formatDuration(video.durationSeconds) }} • {{ formatViews(video.viewCount) }} views</p>
              </div>
            </div>
          </div>

          <div v-else-if="activeTab === 'playlists'" class="items-list">
            <div v-for="playlist in playlists" :key="playlist.id" class="item-card">
              <img :src="playlist.thumbnailUrl || ''" :alt="playlist.title || ''" class="item-thumbnail" />
              <div class="item-info">
                <h4>{{ playlist.title }}</h4>
                <p>{{ playlist.itemCount }} videos</p>
              </div>
            </div>
          </div>
        </div>

        <div class="drawer-actions">
          <button type="button" class="action-btn include" @click="handleInclude">Include Channel</button>
          <button type="button" class="action-btn exclude" @click="handleExclude">Exclude Channel</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue';
import { getChannelDetails } from '@/services/youtubeService';
import type { AdminSearchChannelResult, AdminSearchVideoResult, AdminSearchPlaylistResult } from '@/types/registry';

const props = defineProps<{
  isOpen: boolean;
  channelId: string | null;
}>();

const emit = defineEmits<{
  close: [];
  include: [channelId: string];
  exclude: [channelId: string];
}>();

const loading = ref(false);
const channel = ref<AdminSearchChannelResult | null>(null);
const videos = ref<AdminSearchVideoResult[]>([]);
const playlists = ref<AdminSearchPlaylistResult[]>([]);
const activeTab = ref<'videos' | 'playlists'>('videos' as 'videos' | 'playlists');

const tabs = [
  { value: 'videos', label: 'Videos' },
  { value: 'playlists', label: 'Playlists' }
];

watch(() => props.channelId, async (newId) => {
  if (newId && props.isOpen) {
    loading.value = true;
    try {
      const details = await getChannelDetails(newId);
      channel.value = details.channel;
      videos.value = details.videos;
      playlists.value = details.playlists;
    } catch (err) {
      console.error('Failed to load channel details', err);
    } finally {
      loading.value = false;
    }
  }
}, { immediate: true });

function handleClose() {
  emit('close');
}

function handleInclude() {
  if (channel.value) {
    emit('include', channel.value.id);
    handleClose();
  }
}

function handleExclude() {
  if (channel.value) {
    emit('exclude', channel.value.id);
    handleClose();
  }
}

function formatDuration(seconds: number): string {
  const mins = Math.floor(seconds / 60);
  const secs = seconds % 60;
  return `${mins}:${secs.toString().padStart(2, '0')}`;
}

function formatViews(count: number): string {
  if (count >= 1000000) return `${(count / 1000000).toFixed(1)}M`;
  if (count >= 1000) return `${(count / 1000).toFixed(1)}K`;
  return count.toString();
}
</script>

<style scoped>
.drawer-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  justify-content: flex-end;
  z-index: 1000;
}

.drawer {
  width: 600px;
  max-width: 90vw;
  background: var(--color-surface);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  box-shadow: -2px 0 12px rgba(0, 0, 0, 0.15);
}

.drawer-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 1.5rem;
  border-bottom: 1px solid var(--color-border);
}

.drawer-header h2 {
  margin: 0;
  font-size: 1.25rem;
  font-weight: 600;
}

.close-button {
  background: none;
  border: none;
  font-size: 1.5rem;
  cursor: pointer;
  color: var(--color-text-secondary);
  padding: 0.25rem 0.5rem;
}

.drawer-loading {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 1rem;
  padding: 3rem;
}

.spinner {
  width: 2rem;
  height: 2rem;
  border: 3px solid var(--color-border);
  border-top-color: var(--color-brand);
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.drawer-content {
  flex: 1;
  overflow-y: auto;
  padding: 1.5rem;
  display: flex;
  flex-direction: column;
  gap: 1.5rem;
}

.channel-info {
  display: flex;
  gap: 1rem;
  align-items: center;
}

.channel-avatar {
  width: 80px;
  height: 80px;
  border-radius: 50%;
}

.channel-meta {
  flex: 1;
}

.subscriber-count {
  font-size: 0.875rem;
  color: var(--color-text-secondary);
  margin: 0 0 0.5rem;
}

.categories {
  display: flex;
  gap: 0.5rem;
  flex-wrap: wrap;
}

.category-tag {
  padding: 0.25rem 0.75rem;
  background: var(--color-brand-soft);
  color: var(--color-brand);
  border-radius: 999px;
  font-size: 0.75rem;
  font-weight: 500;
}

.tabs {
  display: flex;
  gap: 0.5rem;
  border-bottom: 1px solid var(--color-border);
}

.tab {
  padding: 0.75rem 1.5rem;
  background: none;
  border: none;
  border-bottom: 2px solid transparent;
  cursor: pointer;
  font-weight: 500;
  color: var(--color-text-secondary);
  transition: all 0.2s;
}

.tab.active {
  color: var(--color-brand);
  border-bottom-color: var(--color-brand);
}

.items-list {
  display: flex;
  flex-direction: column;
  gap: 1rem;
}

.item-card {
  display: flex;
  gap: 1rem;
  padding: 0.75rem;
  border-radius: 0.5rem;
  background: var(--color-surface-alt);
}

.item-thumbnail {
  width: 120px;
  height: 68px;
  object-fit: cover;
  border-radius: 0.375rem;
}

.item-info h4 {
  margin: 0 0 0.25rem;
  font-size: 0.875rem;
  font-weight: 500;
}

.item-info p {
  margin: 0;
  font-size: 0.75rem;
  color: var(--color-text-secondary);
}

.drawer-actions {
  display: flex;
  gap: 1rem;
  padding: 1.5rem;
  border-top: 1px solid var(--color-border);
}

.action-btn {
  flex: 1;
  padding: 0.75rem;
  border: none;
  border-radius: 0.5rem;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s;
}

.action-btn.include {
  background: var(--color-brand);
  color: white;
}

.action-btn.exclude {
  background: var(--color-danger);
  color: white;
}
</style>
