<template>
  <div class="search-result-card video-card">
    <div class="card-thumbnail video-thumbnail">
      <img v-if="video.thumbnailUrl" :src="video.thumbnailUrl" :alt="video.title" />
      <div v-else class="thumbnail-placeholder"></div>
      <span v-if="video.durationSeconds" class="duration-badge">
        {{ formatDuration(video.durationSeconds) }}
      </span>
    </div>
    <div class="card-content">
      <h3 class="card-title">{{ video.title }}</h3>
      <div class="card-meta">
        <span v-if="video.channel?.name" class="meta-item">
          <svg class="meta-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"></path>
            <circle cx="12" cy="7" r="4"></circle>
          </svg>
          {{ video.channel.name }}
        </span>
        <span class="meta-item">
          <svg class="meta-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"></path>
            <circle cx="12" cy="12" r="3"></circle>
          </svg>
          {{ formatViewCount(video.viewCount) }} views
        </span>
      </div>
      <span class="content-type-badge video-badge">Video</span>
    </div>
    <div class="card-actions">
      <button type="button" class="action-button secondary" @click="$emit('add', video)" disabled>
        Coming Soon
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import type { AdminSearchVideoResult } from '@/types/registry';

defineProps<{
  video: AdminSearchVideoResult;
}>();

defineEmits<{
  add: [video: AdminSearchVideoResult];
}>();

function formatDuration(seconds: number): string {
  const hours = Math.floor(seconds / 3600);
  const minutes = Math.floor((seconds % 3600) / 60);
  const secs = seconds % 60;

  if (hours > 0) {
    return `${hours}:${minutes.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}`;
  }
  return `${minutes}:${secs.toString().padStart(2, '0')}`;
}

function formatViewCount(count: number): string {
  if (count >= 1_000_000) {
    return `${(count / 1_000_000).toFixed(1)}M`;
  } else if (count >= 1_000) {
    return `${(count / 1_000).toFixed(1)}K`;
  }
  return count.toString();
}
</script>

<style scoped>
.search-result-card {
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: 0.75rem;
  padding: 1.25rem;
  display: grid;
  grid-template-columns: auto 1fr auto;
  gap: 1.25rem;
  align-items: center;
  transition: all 0.2s ease;
}

.search-result-card:hover {
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.08);
  border-color: var(--color-brand);
  transform: translateY(-2px);
}

.card-thumbnail {
  border-radius: 0.5rem;
  overflow: hidden;
  background: var(--color-surface-alt);
  position: relative;
}

.video-thumbnail {
  width: 200px;
  height: 112px;
  border-radius: 0.625rem;
}

.video-thumbnail img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.duration-badge {
  position: absolute;
  bottom: 0.5rem;
  right: 0.5rem;
  background: rgba(0, 0, 0, 0.85);
  color: white;
  padding: 0.125rem 0.375rem;
  border-radius: 0.25rem;
  font-size: 0.75rem;
  font-weight: 600;
}

.thumbnail-placeholder {
  width: 100%;
  height: 100%;
  background: linear-gradient(135deg, var(--color-surface-alt), var(--color-border));
}

.card-content {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  position: relative;
}

.card-title {
  margin: 0;
  font-size: 1.125rem;
  font-weight: 600;
  color: var(--color-text-primary);
  line-height: 1.4;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.card-meta {
  display: flex;
  align-items: center;
  gap: 1rem;
  font-size: 0.875rem;
  color: var(--color-text-secondary);
  flex-wrap: wrap;
}

.meta-item {
  display: flex;
  align-items: center;
  gap: 0.375rem;
}

.meta-icon {
  width: 1rem;
  height: 1rem;
  opacity: 0.7;
}

.content-type-badge {
  position: absolute;
  top: 0;
  right: 0;
  padding: 0.25rem 0.625rem;
  border-radius: 999px;
  font-size: 0.75rem;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.05em;
}

.video-badge {
  background: rgba(239, 68, 68, 0.1);
  color: rgb(239, 68, 68);
}

.card-actions {
  display: flex;
  align-items: center;
}

.action-button {
  padding: 0.625rem 1.25rem;
  border: none;
  border-radius: 0.5rem;
  font-weight: 600;
  font-size: 0.875rem;
  cursor: pointer;
  transition: all 0.2s ease;
  white-space: nowrap;
}

.action-button.primary {
  background: var(--color-brand);
  color: var(--color-text-inverse);
}

.action-button.primary:hover {
  background: var(--color-accent);
  box-shadow: 0 2px 8px rgba(22, 131, 90, 0.25);
}

.action-button.secondary {
  background: var(--color-surface-alt);
  color: var(--color-text-secondary);
  cursor: not-allowed;
  opacity: 0.6;
}

@media (max-width: 768px) {
  .search-result-card {
    grid-template-columns: 1fr;
    gap: 1rem;
  }

  .video-thumbnail {
    width: 100%;
    height: auto;
    aspect-ratio: 16/9;
  }

  .card-actions {
    justify-content: stretch;
  }

  .action-button {
    width: 100%;
  }
}
</style>
