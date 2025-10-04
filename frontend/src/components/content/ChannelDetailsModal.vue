<template>
  <div v-if="isOpen" class="modal-overlay" @click="handleOverlayClick">
    <div ref="modalRef" class="modal-content" role="dialog" aria-modal="true" :aria-label="channel?.title" @click.stop>
      <div class="modal-header">
        <div class="header-info">
          <img
            v-if="channel?.thumbnailUrl"
            :src="channel.thumbnailUrl"
            :alt="channel.title"
            class="channel-avatar"
            loading="lazy"
          />
          <div v-else class="channel-avatar-placeholder"></div>
          <div>
            <h2 class="channel-title">{{ channel?.title }}</h2>
            <p class="channel-id">{{ channel?.youtubeId }}</p>
          </div>
        </div>
        <button type="button" class="close-btn" @click="close">×</button>
      </div>

      <div class="modal-tabs">
        <button
          v-for="tab in tabs"
          :key="tab.id"
          type="button"
          :class="['tab-btn', { active: activeTab === tab.id }]"
          @click="activeTab = tab.id"
        >
          {{ t(`channelDetails.tabs.${tab.id}`) }}
        </button>
      </div>

      <div class="modal-body">
        <!-- Overview Tab -->
        <div v-if="activeTab === 'overview'" class="tab-content">
          <div class="info-section">
            <h3 class="section-heading">{{ t('channelDetails.overview.basicInfo') }}</h3>
            <div class="info-grid">
              <div class="info-item">
                <span class="info-label">{{ t('channelDetails.overview.status') }}</span>
                <span class="info-value">
                  <span class="status-badge" :class="`status-${channel?.status}`">
                    {{ t(`contentLibrary.statuses.${channel?.status}`) }}
                  </span>
                </span>
              </div>
              <div class="info-item">
                <span class="info-label">{{ t('channelDetails.overview.dateAdded') }}</span>
                <span class="info-value">{{ formatDate(channel?.createdAt) }}</span>
              </div>
              <div class="info-item">
                <span class="info-label">{{ t('channelDetails.overview.addedBy') }}</span>
                <span class="info-value">{{ channel?.addedBy || t('channelDetails.overview.unknown') }}</span>
              </div>
            </div>
          </div>

          <div class="info-section">
            <h3 class="section-heading">{{ t('channelDetails.overview.description') }}</h3>
            <p class="description-text">{{ channel?.description || t('channelDetails.overview.noDescription') }}</p>
          </div>

          <div class="info-section">
            <h3 class="section-heading">{{ t('channelDetails.overview.youtubeLink') }}</h3>
            <a
              :href="`https://youtube.com/channel/${channel?.youtubeId}`"
              target="_blank"
              rel="noopener noreferrer"
              class="youtube-link"
            >
              {{ `https://youtube.com/channel/${channel?.youtubeId}` }}
            </a>
          </div>
        </div>

        <!-- Categories Tab -->
        <div v-if="activeTab === 'categories'" class="tab-content">
          <div class="categories-header">
            <h3 class="section-heading">{{ t('channelDetails.categories.assigned') }}</h3>
            <button type="button" class="btn-primary-sm" @click="openCategoryAssignment">
              {{ t('channelDetails.categories.manage') }}
            </button>
          </div>

          <div v-if="channel?.categoryIds.length === 0" class="empty-state">
            <p>{{ t('channelDetails.categories.noCategories') }}</p>
            <button type="button" class="btn-secondary-sm" @click="openCategoryAssignment">
              {{ t('channelDetails.categories.assignFirst') }}
            </button>
          </div>

          <div v-else class="category-list">
            <div v-for="catId in channel?.categoryIds" :key="catId" class="category-item">
              <span class="category-name">{{ getCategoryName(catId) }}</span>
              <button
                type="button"
                class="remove-btn"
                :title="t('channelDetails.categories.remove')"
                @click="removeCategory(catId)"
              >
                ×
              </button>
            </div>
          </div>
        </div>

        <!-- Exclusions Tab -->
        <div v-if="activeTab === 'exclusions'" class="tab-content">
          <div class="exclusions-header">
            <h3 class="section-heading">{{ t('channelDetails.exclusions.title') }}</h3>
            <button type="button" class="btn-primary-sm" @click="addExclusion">
              {{ t('channelDetails.exclusions.add') }}
            </button>
          </div>

          <div v-if="exclusions.length === 0" class="empty-state">
            <p>{{ t('channelDetails.exclusions.noExclusions') }}</p>
          </div>

          <div v-else class="exclusions-table">
            <table>
              <thead>
                <tr>
                  <th>{{ t('channelDetails.exclusions.type') }}</th>
                  <th>{{ t('channelDetails.exclusions.title') }}</th>
                  <th>{{ t('channelDetails.exclusions.reason') }}</th>
                  <th>{{ t('channelDetails.exclusions.actions') }}</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="exc in exclusions" :key="exc.id">
                  <td>
                    <span class="type-badge" :class="`type-${exc.type}`">
                      {{ t(`contentLibrary.types.${exc.type}`) }}
                    </span>
                  </td>
                  <td>{{ exc.title }}</td>
                  <td class="reason-cell">{{ exc.reason || '—' }}</td>
                  <td>
                    <button
                      type="button"
                      class="action-btn-sm delete"
                      @click="removeExclusion(exc.id)"
                    >
                      {{ t('channelDetails.exclusions.remove') }}
                    </button>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <!-- Metadata Tab -->
        <div v-if="activeTab === 'metadata'" class="tab-content">
          <h3 class="section-heading">{{ t('channelDetails.metadata.title') }}</h3>
          <div class="metadata-grid">
            <div class="metadata-item">
              <span class="metadata-label">{{ t('channelDetails.metadata.id') }}</span>
              <code class="metadata-value">{{ channel?.id }}</code>
            </div>
            <div class="metadata-item">
              <span class="metadata-label">{{ t('channelDetails.metadata.youtubeId') }}</span>
              <code class="metadata-value">{{ channel?.youtubeId }}</code>
            </div>
            <div class="metadata-item">
              <span class="metadata-label">{{ t('channelDetails.metadata.createdAt') }}</span>
              <span class="metadata-value">{{ formatDateTime(channel?.createdAt) }}</span>
            </div>
            <div class="metadata-item">
              <span class="metadata-label">{{ t('channelDetails.metadata.updatedAt') }}</span>
              <span class="metadata-value">{{ formatDateTime(channel?.updatedAt) }}</span>
            </div>
          </div>
        </div>

        <!-- History Tab -->
        <div v-if="activeTab === 'history'" class="tab-content">
          <h3 class="section-heading">{{ t('channelDetails.history.title') }}</h3>

          <div v-if="history.length === 0" class="empty-state">
            <p>{{ t('channelDetails.history.noHistory') }}</p>
          </div>

          <div v-else class="history-timeline">
            <div v-for="event in history" :key="event.id" class="timeline-item">
              <div class="timeline-marker"></div>
              <div class="timeline-content">
                <div class="timeline-header">
                  <span class="timeline-action">{{ event.action }}</span>
                  <span class="timeline-date">{{ formatDateTime(event.timestamp) }}</span>
                </div>
                <div class="timeline-details">
                  <span class="timeline-actor">{{ event.actor }}</span>
                  <span v-if="event.details" class="timeline-meta">{{ event.details }}</span>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>

      <div class="modal-footer">
        <button type="button" class="btn-secondary" @click="close">
          {{ t('channelDetails.close') }}
        </button>
        <button type="button" class="btn-danger" @click="confirmDelete">
          {{ t('channelDetails.delete') }}
        </button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { useFocusTrap } from '@/composables/useFocusTrap';

const { t } = useI18n();

interface Channel {
  id: string;
  youtubeId: string;
  title: string;
  description?: string;
  thumbnailUrl?: string;
  status: 'approved' | 'pending' | 'rejected';
  categoryIds: string[];
  addedBy?: string;
  createdAt: Date;
  updatedAt: Date;
}

interface Exclusion {
  id: string;
  type: 'playlist' | 'video';
  title: string;
  reason?: string;
}

interface HistoryEvent {
  id: string;
  action: string;
  actor: string;
  timestamp: Date;
  details?: string;
}

interface Props {
  isOpen: boolean;
  channel: Channel | null;
}

const props = defineProps<Props>();

const emit = defineEmits<{
  close: [];
  delete: [channelId: string];
  updateCategories: [channelId: string, categoryIds: string[]];
}>();

const activeTab = ref<'overview' | 'categories' | 'exclusions' | 'metadata' | 'history'>('overview');
const exclusions = ref<Exclusion[]>([]);
const history = ref<HistoryEvent[]>([]);

const tabs = [
  { id: 'overview' },
  { id: 'categories' },
  { id: 'exclusions' },
  { id: 'metadata' },
  { id: 'history' }
];

// Focus trap for modal
const modalRef = ref<HTMLElement | null>(null);
const { activate, deactivate } = useFocusTrap(modalRef, {
  onEscape: close,
  escapeDeactivates: true,
  returnFocus: true
});

// Activate/deactivate focus trap when modal opens/closes
watch(() => props.isOpen, (isOpen) => {
  if (isOpen) {
    activate();
  } else {
    deactivate();
  }
});

function close() {
  emit('close');
}

function handleOverlayClick() {
  close();
}

function confirmDelete() {
  if (!props.channel) return;
  if (confirm(t('channelDetails.confirmDelete', { title: props.channel.title }))) {
    emit('delete', props.channel.id);
    close();
  }
}

function openCategoryAssignment() {
  // TODO: Open category assignment modal (UI-013)
  console.log('Open category assignment modal');
}

function removeCategory(catId: string) {
  if (!props.channel) return;
  const updatedCategories = props.channel.categoryIds.filter(id => id !== catId);
  emit('updateCategories', props.channel.id, updatedCategories);
}

function addExclusion() {
  // TODO: Open add exclusion dialog
  console.log('Add exclusion');
}

function removeExclusion(excId: string) {
  // TODO: Remove exclusion API call
  console.log('Remove exclusion:', excId);
}

function getCategoryName(catId: string): string {
  // TODO: Fetch from categories store/API
  return catId;
}

function formatDate(date?: Date): string {
  if (!date) return '—';
  return new Intl.DateTimeFormat('en-US', {
    year: 'numeric',
    month: 'long',
    day: 'numeric'
  }).format(new Date(date));
}

function formatDateTime(date?: Date): string {
  if (!date) return '—';
  return new Intl.DateTimeFormat('en-US', {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit'
  }).format(new Date(date));
}
</script>

<style scoped>
.modal-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.6);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 2000;
  padding: 1rem;
}

.modal-content {
  background: var(--color-surface);
  border-radius: 1rem;
  width: min(900px, 100%);
  max-height: 90vh;
  display: flex;
  flex-direction: column;
  box-shadow: 0 25px 75px rgba(0, 0, 0, 0.3);
}

.modal-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 1.5rem 2rem;
  border-bottom: 1px solid var(--color-border);
}

.header-info {
  display: flex;
  align-items: center;
  gap: 1rem;
}

.channel-avatar {
  width: 64px;
  height: 64px;
  border-radius: 50%;
  object-fit: cover;
}

.channel-avatar-placeholder {
  width: 64px;
  height: 64px;
  border-radius: 50%;
  background: var(--color-border);
}

.channel-title {
  font-size: 1.5rem;
  font-weight: 700;
  color: var(--color-text-primary);
  margin: 0 0 0.25rem 0;
}

.channel-id {
  font-size: 0.875rem;
  color: var(--color-text-secondary);
  font-family: monospace;
  margin: 0;
}

.close-btn {
  width: 2.5rem;
  height: 2.5rem;
  border: none;
  background: transparent;
  color: var(--color-text-secondary);
  font-size: 2rem;
  line-height: 1;
  cursor: pointer;
  border-radius: 0.5rem;
  transition: all 0.2s ease;
}

.close-btn:hover {
  background: var(--color-background);
  color: var(--color-text-primary);
}

/* RTL: Move close button to left */
[dir='rtl'] .modal-header {
  flex-direction: row-reverse;
}

.modal-tabs {
  display: flex;
  gap: 0.5rem;
  padding: 1rem 2rem 0;
  border-bottom: 1px solid var(--color-border);
  overflow-x: auto;
}

.tab-btn {
  padding: 0.75rem 1.25rem;
  border: none;
  background: transparent;
  color: var(--color-text-secondary);
  font-size: 0.9375rem;
  font-weight: 500;
  cursor: pointer;
  position: relative;
  white-space: nowrap;
  transition: color 0.2s ease;
}

.tab-btn:hover {
  color: var(--color-text-primary);
}

.tab-btn.active {
  color: var(--color-brand);
}

.tab-btn.active::after {
  content: '';
  position: absolute;
  bottom: -1px;
  left: 0;
  right: 0;
  height: 2px;
  background: var(--color-brand);
}

.modal-body {
  flex: 1;
  overflow-y: auto;
  padding: 2rem;
}

.tab-content {
  display: flex;
  flex-direction: column;
  gap: 2rem;
}

.info-section,
.section-heading {
  margin: 0;
}

.section-heading {
  font-size: 1.125rem;
  font-weight: 600;
  color: var(--color-text-primary);
  margin-bottom: 1rem;
}

.info-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(250px, 1fr));
  gap: 1.5rem;
}

.info-item {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.info-label {
  font-size: 0.875rem;
  font-weight: 500;
  color: var(--color-text-secondary);
  text-transform: uppercase;
  letter-spacing: 0.025em;
}

.info-value {
  font-size: 0.9375rem;
  color: var(--color-text-primary);
}

.status-badge {
  display: inline-block;
  padding: 0.375rem 0.75rem;
  border-radius: 0.375rem;
  font-size: 0.75rem;
  font-weight: 600;
  text-transform: uppercase;
}

.status-approved { background: #d1fae5; color: #065f46; }
.status-pending { background: #fed7aa; color: #92400e; }
.status-rejected { background: #fee2e2; color: #991b1b; }

.description-text {
  color: var(--color-text-primary);
  line-height: 1.6;
  margin: 0;
}

.youtube-link {
  color: var(--color-brand);
  text-decoration: none;
  font-size: 0.9375rem;
}

.youtube-link:hover {
  text-decoration: underline;
}

.categories-header,
.exclusions-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 1rem;
}

.category-list {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}

.category-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0.75rem 1rem;
  background: var(--color-background);
  border: 1px solid var(--color-border);
  border-radius: 0.5rem;
}

.category-name {
  font-size: 0.9375rem;
  color: var(--color-text-primary);
}

.remove-btn {
  width: 1.5rem;
  height: 1.5rem;
  border: none;
  background: transparent;
  color: var(--color-text-secondary);
  font-size: 1.25rem;
  line-height: 1;
  cursor: pointer;
  border-radius: 0.25rem;
  transition: all 0.2s ease;
}

.remove-btn:hover {
  background: var(--color-danger);
  color: white;
}

.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 1rem;
  padding: 3rem 2rem;
  text-align: center;
  color: var(--color-text-secondary);
}

.exclusions-table {
  overflow-x: auto;
}

.exclusions-table table {
  width: 100%;
  border-collapse: collapse;
}

.exclusions-table th {
  padding: 0.75rem 1rem;
  text-align: start;
  font-size: 0.8125rem;
  font-weight: 600;
  color: var(--color-text-secondary);
  text-transform: uppercase;
  background: var(--color-background);
  border-bottom: 1px solid var(--color-border);
}

.exclusions-table td {
  padding: 1rem;
  border-bottom: 1px solid var(--color-border);
  font-size: 0.9375rem;
}

.reason-cell {
  color: var(--color-text-secondary);
  font-size: 0.875rem;
}

.type-badge {
  display: inline-block;
  padding: 0.25rem 0.625rem;
  border-radius: 0.375rem;
  font-size: 0.75rem;
  font-weight: 600;
  text-transform: uppercase;
}

.type-playlist { background: #fef3c7; color: #92400e; }
.type-video { background: #e0e7ff; color: #4338ca; }

.metadata-grid {
  display: grid;
  gap: 1.5rem;
}

.metadata-item {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.metadata-label {
  font-size: 0.875rem;
  font-weight: 500;
  color: var(--color-text-secondary);
  text-transform: uppercase;
  letter-spacing: 0.025em;
}

.metadata-value {
  font-size: 0.9375rem;
  color: var(--color-text-primary);
}

code.metadata-value {
  font-family: monospace;
  background: var(--color-background);
  padding: 0.25rem 0.5rem;
  border-radius: 0.25rem;
  font-size: 0.875rem;
}

.history-timeline {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 1.5rem;
}

.timeline-item {
  position: relative;
  padding-inline-start: 2rem;
}

.timeline-marker {
  position: absolute;
  inset-inline-start: 0;
  top: 0.375rem;
  width: 0.75rem;
  height: 0.75rem;
  background: var(--color-brand);
  border-radius: 50%;
}

.timeline-marker::before {
  content: '';
  position: absolute;
  inset-inline-start: 50%;
  top: 100%;
  width: 2px;
  height: 2rem;
  background: var(--color-border);
  transform: translateX(-50%);
}

/* RTL: Flip timeline marker transform */
[dir='rtl'] .timeline-marker::before {
  transform: translateX(50%);
}

.timeline-item:last-child .timeline-marker::before {
  display: none;
}

.timeline-content {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.timeline-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.timeline-action {
  font-weight: 600;
  color: var(--color-text-primary);
}

.timeline-date {
  font-size: 0.875rem;
  color: var(--color-text-secondary);
}

.timeline-details {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
  font-size: 0.875rem;
}

.timeline-actor {
  color: var(--color-text-secondary);
}

.timeline-meta {
  color: var(--color-text-secondary);
  font-style: italic;
}

.modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 0.75rem;
  padding: 1.5rem 2rem;
  border-top: 1px solid var(--color-border);
}

/* RTL: Reverse button order (primary on left) */
[dir='rtl'] .modal-footer {
  flex-direction: row-reverse;
}

.btn-secondary,
.btn-danger {
  padding: 0.625rem 1.25rem;
  border: 1px solid var(--color-border);
  border-radius: 0.5rem;
  font-size: 0.9375rem;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s ease;
}

.btn-secondary {
  background: transparent;
  color: var(--color-text-primary);
}

.btn-secondary:hover {
  background: var(--color-background);
}

.btn-danger {
  background: var(--color-danger);
  color: white;
  border-color: var(--color-danger);
}

.btn-danger:hover {
  background: #dc2626;
}

.btn-primary-sm,
.btn-secondary-sm {
  padding: 0.5rem 1rem;
  border-radius: 0.375rem;
  font-size: 0.875rem;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s ease;
}

.btn-primary-sm {
  background: var(--color-brand);
  color: white;
  border: none;
}

.btn-primary-sm:hover {
  background: var(--color-accent);
}

.btn-secondary-sm {
  background: transparent;
  color: var(--color-brand);
  border: 1px solid var(--color-brand);
}

.btn-secondary-sm:hover {
  background: var(--color-brand);
  color: white;
}

.action-btn-sm {
  padding: 0.375rem 0.75rem;
  border: none;
  background: transparent;
  color: var(--color-text-secondary);
  font-size: 0.875rem;
  cursor: pointer;
  border-radius: 0.25rem;
  transition: all 0.2s ease;
}

.action-btn-sm:hover {
  background: var(--color-background);
  color: var(--color-text-primary);
}

.action-btn-sm.delete {
  color: var(--color-danger);
}

.action-btn-sm.delete:hover {
  background: var(--color-danger);
  color: white;
}
</style>
