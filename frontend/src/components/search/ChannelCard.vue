<template>
  <div class="search-result-card channel-card">
    <div class="card-thumbnail channel-thumbnail">
      <img v-if="channel.avatarUrl" :src="channel.avatarUrl" :alt="channel.name" />
      <div v-else class="thumbnail-placeholder"></div>
    </div>
    <div class="card-content">
      <h3 class="card-title">{{ channel.name }}</h3>
      <div class="card-meta">
        <span class="meta-item">
          <svg class="meta-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"></path>
            <circle cx="9" cy="7" r="4"></circle>
            <path d="M23 21v-2a4 4 0 0 0-3-3.87"></path>
            <path d="M16 3.13a4 4 0 0 1 0 7.75"></path>
          </svg>
          {{ formatSubscriberCount(channel.subscriberCount) }} subscribers
        </span>
      </div>
      <span class="content-type-badge channel-badge">Channel</span>
    </div>
    <div class="card-actions">
      <button
        v-if="alreadyAdded"
        type="button"
        class="action-button secondary"
        disabled
      >
        Already Added
      </button>
      <button
        v-else
        type="button"
        class="action-button primary"
        @click="$emit('add', channel)"
      >
        Add for Approval
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import type { AdminSearchChannelResult } from '@/types/registry';

defineProps<{
  channel: AdminSearchChannelResult;
  alreadyAdded?: boolean;
}>();

defineEmits<{
  add: [channel: AdminSearchChannelResult];
}>();

function formatSubscriberCount(count: number): string {
  if (count >= 1_000_000_000) {
    return `${(count / 1_000_000_000).toFixed(1)}B`;
  } else if (count >= 1_000_000) {
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
}

.channel-thumbnail {
  width: 80px;
  height: 80px;
  border-radius: 50%;
}

.channel-thumbnail img {
  width: 100%;
  height: 100%;
  object-fit: cover;
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
}

.card-meta {
  display: flex;
  align-items: center;
  gap: 1rem;
  font-size: 0.875rem;
  color: var(--color-text-secondary);
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

.channel-badge {
  background: var(--color-brand-soft);
  color: var(--color-brand);
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

  .card-actions {
    justify-content: stretch;
  }

  .action-button {
    width: 100%;
  }
}
</style>
