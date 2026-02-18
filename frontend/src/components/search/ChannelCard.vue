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
        <span v-if="channel.publishedAt" class="meta-item meta-date">
          <svg class="meta-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <rect x="3" y="4" width="18" height="18" rx="2" ry="2"></rect>
            <line x1="16" y1="2" x2="16" y2="6"></line>
            <line x1="8" y1="2" x2="8" y2="6"></line>
            <line x1="3" y1="10" x2="21" y2="10"></line>
          </svg>
          Created {{ formatRelativeTime(channel.publishedAt) }}
        </span>
      </div>
    </div>
    <div class="card-actions">
      <span class="content-type-badge channel-badge">CHANNEL</span>
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
        {{ isAdmin ? 'Add' : 'Add for Approval' }}
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import type { AdminSearchChannelResult } from '@/types/registry';

defineProps<{
  channel: AdminSearchChannelResult;
  alreadyAdded?: boolean;
  isAdmin?: boolean;
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

function formatRelativeTime(dateStr: string): string {
  const date = new Date(dateStr);
  const now = new Date();
  const diffInSeconds = Math.floor((now.getTime() - date.getTime()) / 1000);

  if (diffInSeconds < 60) return 'just now';

  const mins = Math.floor(diffInSeconds / 60);
  if (mins < 60) return `${mins} minute${mins !== 1 ? 's' : ''} ago`;

  const hours = Math.floor(mins / 60);
  if (hours < 24) return `${hours} hour${hours !== 1 ? 's' : ''} ago`;

  const days = Math.floor(hours / 24);
  if (days < 7) return `${days} day${days !== 1 ? 's' : ''} ago`;

  const weeks = Math.floor(days / 7);
  if (weeks < 4) return `${weeks} week${weeks !== 1 ? 's' : ''} ago`;

  const months = Math.floor(days / 30);
  if (months < 12) return `${months} month${months !== 1 ? 's' : ''} ago`;

  const years = Math.floor(days / 365);
  return `${years} year${years !== 1 ? 's' : ''} ago`;
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
  flex: 1;
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

.meta-date {
  color: var(--color-text-tertiary);
  font-size: 0.8125rem;
}

.content-type-badge {
  padding: 0.25rem 0.625rem;
  border-radius: 999px;
  font-size: 0.6875rem;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.05em;
  white-space: nowrap;
  flex-shrink: 0;
}

.channel-badge {
  background: var(--color-brand-soft);
  color: var(--color-brand);
}

.card-actions {
  display: flex;
  align-items: center;
  gap: 0.75rem;
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
