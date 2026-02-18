<template>
  <div class="approvals-view">
    <header class="approvals-header">
      <div>
        <h1>{{ isModeratorView ? t('approvals.mySubmissionsHeading') : t('approvals.heading') }}</h1>
        <p>{{ isModeratorView ? t('approvals.mySubmissionsSubtitle') : t('approvals.subtitle') }}</p>
      </div>
      <div class="header-stats">
        <div class="stat-badge">
          <span class="stat-value">{{ totalPending }}</span>
          <span class="stat-label">{{ t('approvals.pending') }}</span>
        </div>
      </div>
    </header>

    <!-- Status Tabs (moderator view) -->
    <div v-if="isModeratorView" class="filters">
      <div class="filter-group">
        <label>{{ t('approvals.filters.status') }}</label>
        <div class="filter-tabs">
          <button
            v-for="tab in statusTabs"
            :key="tab.value"
            type="button"
            :class="['filter-tab', { active: statusFilter === tab.value }]"
            @click="statusFilter = tab.value; handleFilterChange()"
          >
            {{ t(tab.labelKey) }}
          </button>
        </div>
      </div>

      <div class="filter-group">
        <label>{{ t('approvals.filters.type') }}</label>
        <div class="filter-tabs">
          <button
            v-for="type in contentTypes"
            :key="type.value"
            type="button"
            :class="['filter-tab', { active: contentType === type.value }]"
            @click="contentType = type.value; handleFilterChange()"
          >
            {{ t(type.labelKey) }}
          </button>
        </div>
      </div>
    </div>

    <!-- Admin Filters -->
    <div v-else class="filters">
      <div class="filter-group">
        <label>{{ t('approvals.filters.type') }}</label>
        <div class="filter-tabs">
          <button
            v-for="type in contentTypes"
            :key="type.value"
            type="button"
            :class="['filter-tab', { active: contentType === type.value }]"
            @click="contentType = type.value; handleFilterChange()"
          >
            {{ t(type.labelKey) }}
          </button>
        </div>
      </div>

      <div class="filter-group">
        <label>{{ t('approvals.filters.category') }}</label>
        <select v-model="categoryFilter" @change="handleFilterChange">
          <option value="">{{ t('approvals.filters.allCategories') }}</option>
          <option v-for="cat in flatCategories" :key="cat.id" :value="cat.id">
            {{ cat.label }}
          </option>
        </select>
      </div>
    </div>

    <!-- Loading State -->
    <div v-if="isLoading && !approvals.length" class="loading">
      <div class="spinner"></div>
      <p>{{ t('approvals.loading') }}</p>
    </div>

    <!-- Error State -->
    <div v-else-if="error" class="error-panel" role="alert">
      <p>{{ error }}</p>
      <button type="button" @click="loadApprovals()">{{ t('approvals.retry') }}</button>
    </div>

    <!-- Empty State -->
    <div v-else-if="approvals.length === 0" class="empty-state">
      <p>{{ isModeratorView ? t('approvals.emptySubmissions') : t('approvals.empty') }}</p>
    </div>

    <!-- Approvals Grid -->
    <div v-else class="approvals-grid">
      <div v-for="item in approvals" :key="item.id" class="approval-card">
        <div class="card-header">
          <span class="content-type">{{ t(`approvals.types.${item.type}`) }}</span>
          <div class="card-header-right">
            <span v-if="isModeratorView && item.status" :class="['status-badge', `status-${item.status.toLowerCase()}`]">
              {{ t(`approvals.statusTabs.${item.status.toLowerCase()}`) }}
            </span>
            <span class="submitted-date">{{ formatDate(item.submittedAt) }}</span>
          </div>
        </div>

        <div class="card-body">
          <div class="thumbnail">
            <img v-if="item.thumbnailUrl" :src="item.thumbnailUrl" :alt="item.title" />
            <div v-else class="thumbnail-placeholder"></div>
          </div>

          <div class="content-info">
            <h3 class="content-title">{{ item.title }}</h3>
            <p class="content-description">{{ item.description }}</p>

            <div class="metadata">
              <div v-if="item.channelTitle" class="meta-item">
                <span class="meta-label">{{ t('approvals.channel') }}:</span>
                <span>{{ item.channelTitle }}</span>
              </div>
              <div v-if="item.subscriberCount" class="meta-item">
                <span class="meta-label">{{ t('approvals.subscribers') }}:</span>
                <span>{{ formatNumber(item.subscriberCount) }}</span>
              </div>
              <div v-if="item.videoCount" class="meta-item">
                <span class="meta-label">{{ t('approvals.videos') }}:</span>
                <span>{{ formatNumber(item.videoCount) }}</span>
              </div>
            </div>

            <div class="categories">
              <span class="meta-label">{{ t('approvals.categories') }}:</span>
              <div class="category-tags">
                <span v-for="cat in item.categories" :key="cat" class="category-tag">
                  {{ getCategoryName(cat) }}
                </span>
                <span v-if="!item.categories || item.categories.length === 0" class="no-categories">
                  {{ t('approvals.noCategories') }}
                </span>
              </div>
            </div>
          </div>
        </div>

        <!-- Rejection/Review info for moderator view -->
        <div v-if="isModeratorView && (item.rejectionReason || item.reviewNotes)" class="review-info">
          <div v-if="item.rejectionReason" class="review-detail">
            <span class="meta-label">{{ t('approvals.rejectedReason') }}:</span>
            <span>{{ item.rejectionReason }}</span>
          </div>
          <div v-if="item.reviewNotes" class="review-detail">
            <span class="meta-label">{{ t('approvals.adminNotes') }}:</span>
            <span>{{ item.reviewNotes }}</span>
          </div>
        </div>

        <div class="card-footer">
          <div class="submitted-by">
            <span class="meta-label">{{ t('approvals.submittedBy') }}:</span>
            <span>{{ item.submittedBy || t('approvals.unknown') }}</span>
          </div>
          <div class="actions">
            <button
              v-if="item.youtubeId"
              type="button"
              class="action-btn preview"
              @click="openPreview(item)"
            >
              {{ t('approvals.preview') }}
            </button>
            <button
              v-if="authStore.isAdmin"
              type="button"
              class="action-btn reject"
              :disabled="processingId === item.id"
              @click="openRejectDialog(item)"
            >
              {{ t('approvals.reject') }}
            </button>
            <button
              v-if="authStore.isAdmin"
              type="button"
              class="action-btn approve"
              :disabled="processingId === item.id"
              @click="handleApprove(item)"
            >
              <span v-if="processingId === item.id">{{ t('approvals.approving') }}</span>
              <span v-else>{{ t('approvals.approve') }}</span>
            </button>
          </div>
        </div>
      </div>
    </div>

    <!-- Load More Button -->
    <div v-if="nextCursor && !isLoading" class="load-more">
      <button type="button" class="button secondary" :disabled="isLoadingMore" @click="loadMore">
        <span v-if="isLoadingMore">{{ t('approvals.loadingMore') }}</span>
        <span v-else>{{ t('approvals.loadMore') }}</span>
      </button>
    </div>

    <!-- Preview Modals -->
    <ChannelDetailModal
      v-if="previewItem?.type === 'channel'"
      :open="showPreview"
      :channel-id="previewItem.id"
      :channel-youtube-id="previewItem.youtubeId"
      @close="closePreview"
    />

    <PlaylistDetailModal
      v-if="previewItem?.type === 'playlist'"
      :open="showPreview"
      :playlist-id="previewItem.id"
      :playlist-youtube-id="previewItem.youtubeId"
      @close="closePreview"
    />

    <VideoPreviewModal
      v-if="previewItem?.type === 'video'"
      :open="showPreview"
      :youtube-id="previewItem.youtubeId"
      :title="previewItem.title"
      @close="closePreview"
    />

    <!-- Reject Modal -->
    <teleport to="body">
      <div v-if="showRejectDialog" class="modal-overlay" @click="closeRejectDialog">
        <div class="modal" @click.stop>
          <header class="modal-header">
            <h2>{{ t('approvals.rejectDialog.title') }}</h2>
            <button type="button" class="close-button" @click="closeRejectDialog">×</button>
          </header>

          <form @submit.prevent="handleReject">
            <div class="modal-body">
              <p class="reject-content-name">{{ rejectItem?.title }}</p>

              <div class="form-group">
                <label for="reject-reason">{{ t('approvals.rejectDialog.reason') }} *</label>
                <textarea
                  id="reject-reason"
                  v-model="rejectReason"
                  rows="4"
                  required
                  :placeholder="t('approvals.rejectDialog.reasonPlaceholder')"
                ></textarea>
              </div>

              <div v-if="rejectError" class="form-error">{{ rejectError }}</div>
            </div>

            <div class="modal-footer">
              <button type="button" class="button secondary" @click="closeRejectDialog">
                {{ t('approvals.rejectDialog.cancel') }}
              </button>
              <button type="submit" class="button danger" :disabled="isRejecting">
                {{ isRejecting ? t('approvals.rejectDialog.rejecting') : t('approvals.rejectDialog.confirm') }}
              </button>
            </div>
          </form>
        </div>
      </div>
    </teleport>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useI18n } from 'vue-i18n';
import { useAuthStore } from '@/stores/auth';
import { useToast } from '@/composables/useToast';
import { getAllCategories } from '@/services/categoryService';
import { getPendingApprovals, getMySubmissions, approveItem, rejectItem as rejectItemApi, type PendingApproval, type SubmissionStatus, type MySubmission } from '@/services/approvalService';
import ChannelDetailModal from '@/components/exclusions/ChannelDetailModal.vue';
import PlaylistDetailModal from '@/components/exclusions/PlaylistDetailModal.vue';
import VideoPreviewModal from '@/components/VideoPreviewModal.vue';

const { t } = useI18n();
const authStore = useAuthStore();
const toast = useToast();

const isModeratorView = computed(() => !authStore.isAdmin);

const contentType = ref<'all' | 'channels' | 'playlists' | 'videos'>('all');
const categoryFilter = ref('');
const statusFilter = ref<SubmissionStatus>('PENDING');

const statusTabs = [
  { value: 'PENDING' as SubmissionStatus, labelKey: 'approvals.statusTabs.pending' },
  { value: 'APPROVED' as SubmissionStatus, labelKey: 'approvals.statusTabs.approved' },
  { value: 'REJECTED' as SubmissionStatus, labelKey: 'approvals.statusTabs.rejected' }
];

const approvals = ref<MySubmission[]>([]);
const nextCursor = ref<string | null>(null);
const isLoadingMore = ref(false);
const categories = ref<any[]>([]);
const flatCategories = computed(() => {
  const flattened: { id: string; label: string }[] = [];

  const traverse = (nodes: any[], depth = 0) => {
    nodes.forEach(node => {
      const prefix = depth > 0 ? `${'— '.repeat(depth)}` : '';
      flattened.push({ id: node.id, label: `${prefix}${node.label}` });
      if (node.subcategories?.length) {
        traverse(node.subcategories, depth + 1);
      }
    });
  };

  traverse(categories.value);
  return flattened;
});
const categoryNameMap = computed(() => {
  const map = new Map<string, string>();

  const traverse = (nodes: any[]) => {
    nodes.forEach(node => {
      map.set(node.id, node.label);
      if (node.subcategories?.length) {
        traverse(node.subcategories);
      }
    });
  };

  traverse(categories.value);
  return map;
});
const isLoading = ref(false);
const error = ref<string | null>(null);
const processingId = ref<string | null>(null);

const showRejectDialog = ref(false);
const rejectItem = ref<any | null>(null);
const rejectReason = ref('');
const isRejecting = ref(false);
const rejectError = ref<string | null>(null);

// Preview state
const showPreview = ref(false);
const previewItem = ref<PendingApproval | null>(null);

const contentTypes = [
  { value: 'all' as const, labelKey: 'approvals.types.all' },
  { value: 'channels' as const, labelKey: 'approvals.types.channels' },
  { value: 'playlists' as const, labelKey: 'approvals.types.playlists' },
  { value: 'videos' as const, labelKey: 'approvals.types.videos' }
];

const totalPending = computed(() => approvals.value.length);

async function loadCategories() {
  try {
    const cats = await getAllCategories();
    categories.value = cats;
  } catch (err) {
    console.error('Failed to load categories', err);
  }
}

async function loadApprovals(append = false) {
  if (append) {
    isLoadingMore.value = true;
  } else {
    isLoading.value = true;
    nextCursor.value = null;
    error.value = null;
  }

  try {
    if (isModeratorView.value) {
      const result = await getMySubmissions({
        status: statusFilter.value,
        type: contentType.value,
        cursor: append ? (nextCursor.value || undefined) : undefined
      });
      if (append) {
        approvals.value = [...approvals.value, ...result.items];
      } else {
        approvals.value = result.items;
      }
      nextCursor.value = result.nextCursor;
    } else {
      const result = await getPendingApprovals({
        type: contentType.value,
        category: categoryFilter.value || undefined,
        cursor: append ? (nextCursor.value || undefined) : undefined
      });
      // Map PendingApproval items to MySubmission with default status
      const mapped: MySubmission[] = result.items.map(item => ({
        ...item,
        status: 'PENDING' as const
      }));
      if (append) {
        approvals.value = [...approvals.value, ...mapped];
      } else {
        approvals.value = mapped;
      }
      nextCursor.value = result.nextCursor;
    }
  } catch (err) {
    if (!append) {
      error.value = err instanceof Error ? err.message : t('approvals.error');
    } else {
      toast.warning(t('approvals.loadMoreError'));
    }
  } finally {
    isLoading.value = false;
    isLoadingMore.value = false;
  }
}

function handleFilterChange() {
  loadApprovals();
}

function loadMore() {
  if (nextCursor.value && !isLoadingMore.value) {
    loadApprovals(true);
  }
}

async function handleApprove(item: any) {
  if (processingId.value) return;

  processingId.value = item.id;
  try {
    await approveItem(item.id, item.type);
    await loadApprovals();
  } catch (err) {
    error.value = err instanceof Error ? err.message : t('approvals.approveError');
  } finally {
    processingId.value = null;
  }
}

function openRejectDialog(item: any) {
  rejectItem.value = item;
  rejectReason.value = '';
  rejectError.value = null;
  showRejectDialog.value = true;
}

function closeRejectDialog() {
  showRejectDialog.value = false;
  rejectItem.value = null;
  rejectReason.value = '';
  rejectError.value = null;
}

async function handleReject() {
  if (!rejectReason.value.trim()) {
    rejectError.value = t('approvals.rejectDialog.reasonRequired');
    return;
  }

  isRejecting.value = true;
  rejectError.value = null;

  try {
    await rejectItemApi(rejectItem.value!.id, rejectItem.value!.type, rejectReason.value);
    closeRejectDialog();
    await loadApprovals();
  } catch (err) {
    rejectError.value = err instanceof Error ? err.message : t('approvals.rejectError');
  } finally {
    isRejecting.value = false;
  }
}

function openPreview(item: PendingApproval) {
  previewItem.value = item;
  showPreview.value = true;
}

function closePreview() {
  showPreview.value = false;
  previewItem.value = null;
}

function getCategoryName(categoryId: string): string {
  return categoryNameMap.value.get(categoryId) ?? categoryId;
}

function formatDate(dateStr: string): string {
  const date = new Date(dateStr);
  return date.toLocaleDateString();
}

function formatNumber(num: number): string {
  return num.toLocaleString();
}

onMounted(() => {
  loadCategories();
  loadApprovals();
});
</script>

<style scoped>
.approvals-view {
  display: flex;
  flex-direction: column;
  gap: 2rem;
}

.approvals-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 1rem;
}

.approvals-header h1 {
  margin: 0;
  font-size: 2rem;
  font-weight: 700;
  color: var(--color-text-primary);
  letter-spacing: -0.02em;
}

.approvals-header p {
  margin: 0.75rem 0 0;
  color: var(--color-text-secondary);
  font-size: 0.9375rem;
}

.header-stats {
  display: flex;
  gap: 1rem;
}

.stat-badge {
  background: var(--color-warning-soft);
  border: 1px solid var(--color-warning);
  border-radius: 0.5rem;
  padding: 0.75rem 1.25rem;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 0.25rem;
}

.stat-value {
  font-size: 1.5rem;
  font-weight: 700;
  color: var(--color-warning);
}

.stat-label {
  font-size: 0.8125rem;
  color: var(--color-text-secondary);
  text-transform: uppercase;
  letter-spacing: 0.05em;
}

.filters {
  background: var(--color-surface);
  padding: 1.5rem;
  border-radius: 0.75rem;
  border: 1px solid var(--color-border);
  display: flex;
  flex-direction: column;
  gap: 1.25rem;
}

.filter-group label {
  display: block;
  font-weight: 600;
  font-size: 0.875rem;
  color: var(--color-text-primary);
  margin-bottom: 0.75rem;
}

.filter-tabs {
  display: flex;
  gap: 0.5rem;
  flex-wrap: wrap;
}

.filter-tab {
  padding: 0.625rem 1.25rem;
  background: transparent;
  border: 1.5px solid var(--color-border);
  border-radius: 999px;
  font-size: 0.875rem;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s ease;
  color: var(--color-text-primary);
  -webkit-tap-highlight-color: transparent;
  min-height: 38px;
}

@media (hover: hover) {
  .filter-tab:hover {
    border-color: var(--color-brand);
    background: var(--color-brand-soft);
  }
}

.filter-tab.active {
  background: var(--color-brand);
  color: var(--color-text-inverse);
  border-color: var(--color-brand);
}

.filter-row {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 1rem;
}

.filter-item label {
  display: block;
  font-weight: 600;
  font-size: 0.875rem;
  color: var(--color-text-primary);
  margin-bottom: 0.5rem;
}

.filter-item select {
  width: 100%;
  padding: 0.75rem;
  border: 1.5px solid var(--color-border);
  border-radius: 0.5rem;
  background: var(--color-surface);
  font-size: 0.9375rem;
  cursor: pointer;
  transition: all 0.2s ease;
}

.filter-item select {
  -webkit-tap-highlight-color: transparent;
  min-height: 44px;
}

@media (hover: hover) {
  .filter-item select:hover {
    border-color: var(--color-brand);
  }
}

.loading {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 1rem;
  padding: 3rem;
  color: var(--color-text-secondary);
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

.error-panel {
  background: var(--color-danger-soft);
  border: 1px solid var(--color-danger);
  border-radius: 0.75rem;
  padding: 1.5rem;
  text-align: center;
}

.error-panel button {
  margin-top: 1rem;
  padding: 0.625rem 1.25rem;
  background: var(--color-brand);
  color: var(--color-text-inverse);
  border: none;
  border-radius: 0.5rem;
  font-weight: 600;
  cursor: pointer;
  -webkit-tap-highlight-color: transparent;
  min-height: 44px;
}

.empty-state {
  text-align: center;
  padding: 3rem;
  color: var(--color-text-secondary);
  background: var(--color-surface);
  border-radius: 0.75rem;
  border: 1px solid var(--color-border);
}

.approvals-grid {
  display: flex;
  flex-direction: column;
  gap: 1.5rem;
}

.approval-card {
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: 0.75rem;
  overflow: hidden;
  transition: all 0.2s ease;
}

@media (hover: hover) {
  .approval-card:hover {
    box-shadow: 0 4px 12px rgba(0, 0, 0, 0.08);
    border-color: var(--color-brand);
  }
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 1rem 1.5rem;
  background: var(--color-surface-alt);
  border-bottom: 1px solid var(--color-border);
}

.card-header-right {
  display: flex;
  align-items: center;
  gap: 0.75rem;
}

.status-badge {
  font-size: 0.75rem;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.05em;
  padding: 0.25rem 0.625rem;
  border-radius: 999px;
}

.status-pending {
  background: var(--color-warning-soft);
  color: var(--color-warning);
}

.status-approved {
  background: rgba(21, 128, 61, 0.1);
  color: var(--color-success);
}

.status-rejected {
  background: var(--color-danger-soft);
  color: var(--color-danger);
}

.review-info {
  padding: 1rem 1.5rem;
  background: var(--color-surface-alt);
  border-top: 1px solid var(--color-border);
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.review-detail {
  display: flex;
  gap: 0.5rem;
  font-size: 0.875rem;
  line-height: 1.5;
}

.content-type {
  font-size: 0.8125rem;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.05em;
  color: var(--color-brand);
}

.submitted-date {
  font-size: 0.8125rem;
  color: var(--color-text-secondary);
}

.card-body {
  display: grid;
  grid-template-columns: 240px 1fr;
  gap: 1.5rem;
  padding: 1.5rem;
}

.thumbnail {
  width: 240px;
  height: 135px;
  border-radius: 0.5rem;
  overflow: hidden;
  background: var(--color-surface-alt);
}

.thumbnail img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.thumbnail-placeholder {
  width: 100%;
  height: 100%;
  background: linear-gradient(135deg, var(--color-surface-alt), var(--color-border));
}

.content-info {
  display: flex;
  flex-direction: column;
  gap: 1rem;
}

.content-title {
  margin: 0;
  font-size: 1.25rem;
  font-weight: 600;
  color: var(--color-text-primary);
  line-height: 1.4;
}

.content-description {
  margin: 0;
  font-size: 0.875rem;
  color: var(--color-text-secondary);
  line-height: 1.6;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.metadata {
  display: flex;
  gap: 2rem;
  flex-wrap: wrap;
}

.meta-item {
  display: flex;
  gap: 0.5rem;
  font-size: 0.875rem;
}

.meta-label {
  font-weight: 600;
  color: var(--color-text-secondary);
}

.categories {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.category-tags {
  display: flex;
  gap: 0.5rem;
  flex-wrap: wrap;
}

.category-tag {
  padding: 0.375rem 0.75rem;
  background: var(--color-brand-soft);
  color: var(--color-brand);
  border-radius: 0.375rem;
  font-size: 0.8125rem;
  font-weight: 500;
}

.no-categories {
  font-size: 0.875rem;
  color: var(--color-text-secondary);
  font-style: italic;
}

.card-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 1rem 1.5rem;
  background: var(--color-surface-alt);
  border-top: 1px solid var(--color-border);
}

.submitted-by {
  display: flex;
  gap: 0.5rem;
  font-size: 0.875rem;
}

.actions {
  display: flex;
  gap: 0.75rem;
}

.action-btn {
  padding: 0.625rem 1.5rem;
  border: none;
  border-radius: 0.5rem;
  font-weight: 600;
  font-size: 0.875rem;
  cursor: pointer;
  transition: all 0.2s ease;
  white-space: nowrap;
  -webkit-tap-highlight-color: transparent;
  min-height: 44px;
}

.action-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.action-btn.preview {
  background: transparent;
  color: var(--color-brand);
  border: 1.5px solid var(--color-brand);
}

@media (hover: hover) {
  .action-btn.preview:hover {
    background: var(--color-brand-soft);
  }
}

.action-btn.approve {
  background: var(--color-success);
  color: var(--color-text-inverse);
}

@media (hover: hover) {
  .action-btn.approve:not(:disabled):hover {
    background: var(--color-success);
    filter: brightness(1.1);
    box-shadow: 0 2px 8px rgba(21, 128, 61, 0.25);
  }
}

.action-btn.reject {
  background: transparent;
  color: var(--color-danger);
  border: 1.5px solid var(--color-danger);
}

@media (hover: hover) {
  .action-btn.reject:not(:disabled):hover {
    background: var(--color-danger-soft);
  }
}

/* Modal styles */
.modal-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
  padding: 1rem;
}

.modal {
  background: var(--color-surface);
  border-radius: 0.75rem;
  max-width: 500px;
  width: 100%;
  box-shadow: 0 24px 64px rgba(0, 0, 0, 0.3);
}

.modal-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 1.5rem;
  border-bottom: 1px solid var(--color-border);
}

.modal-header h2 {
  margin: 0;
  font-size: 1.25rem;
  font-weight: 600;
  color: var(--color-text-primary);
}

.close-button {
  width: 2.75rem;
  height: 2.75rem;
  display: flex;
  align-items: center;
  justify-content: center;
  background: transparent;
  border: none;
  font-size: 1.5rem;
  color: var(--color-text-secondary);
  cursor: pointer;
  border-radius: 0.25rem;
  transition: all 0.2s ease;
  -webkit-tap-highlight-color: transparent;
}

@media (hover: hover) {
  .close-button:hover {
    background: var(--color-surface-alt);
    color: var(--color-text-primary);
  }
}

.modal-body {
  padding: 1.5rem;
  display: flex;
  flex-direction: column;
  gap: 1.25rem;
}

.reject-content-name {
  margin: 0;
  font-weight: 600;
  color: var(--color-text-primary);
}

.form-group {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.form-group label {
  font-weight: 600;
  font-size: 0.875rem;
  color: var(--color-text-primary);
}

.form-group textarea {
  padding: 0.875rem 1rem;
  border-radius: 0.5rem;
  border: 1.5px solid var(--color-border);
  background: var(--color-surface);
  font-size: 0.9375rem;
  font-family: inherit;
  transition: all 0.2s ease;
  resize: vertical;
}

.form-group textarea {
  -webkit-tap-highlight-color: transparent;
}

@media (hover: hover) {
  .form-group textarea:hover {
    border-color: var(--color-brand);
  }
}

.form-group textarea:focus {
  outline: none;
  border-color: var(--color-brand);
  box-shadow: 0 0 0 3px rgba(22, 131, 90, 0.1);
}

.form-error {
  background: var(--color-danger-soft);
  color: var(--color-danger);
  padding: 0.75rem;
  border-radius: 0.5rem;
  font-size: 0.875rem;
}

.modal-footer {
  display: flex;
  gap: 0.75rem;
  justify-content: flex-end;
  padding: 1.5rem;
  border-top: 1px solid var(--color-border);
}

.button {
  padding: 0.75rem 1.5rem;
  border: none;
  border-radius: 0.5rem;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s ease;
  -webkit-tap-highlight-color: transparent;
  min-height: 44px;
}

.button.secondary {
  background: var(--color-surface-alt);
  color: var(--color-text-primary);
  border: 1.5px solid var(--color-border);
}

@media (hover: hover) {
  .button.secondary:hover {
    background: var(--color-surface);
    border-color: var(--color-brand);
  }
}

.button.danger {
  background: var(--color-danger);
  color: var(--color-text-inverse);
}

@media (hover: hover) {
  .button.danger:hover:not(:disabled) {
    background: var(--color-danger-strong);
    box-shadow: 0 2px 8px rgba(220, 38, 38, 0.25);
  }
}

.button.danger:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

/* Mobile/Tablet Responsive */
@media (max-width: 1023px) {
  .approvals-view {
    gap: 1.5rem;
  }

  .approvals-header {
    flex-direction: column;
    gap: 1.25rem;
  }

  .approvals-header h1 {
    font-size: 1.75rem;
  }

  .approvals-header p {
    font-size: 0.875rem;
  }

  .header-stats {
    align-self: stretch;
  }

  .stat-badge {
    flex-direction: row;
    justify-content: space-between;
    padding: 1rem 1.25rem;
  }

  .stat-value {
    font-size: 1.75rem;
  }

  .filters {
    padding: 1.25rem;
    gap: 1rem;
  }

  .filter-tabs {
    gap: 0.625rem;
  }

  .filter-tab {
    flex: 1;
    min-height: 44px;
    padding: 0.75rem 1rem;
  }

  .filter-row {
    grid-template-columns: 1fr;
    gap: 1rem;
  }

  .card-body {
    grid-template-columns: 1fr;
    gap: 1.25rem;
    padding: 1.25rem;
  }

  .thumbnail {
    width: 100%;
    height: 200px;
  }

  .card-header {
    padding: 1rem 1.25rem;
  }

  .card-footer {
    flex-direction: column;
    align-items: stretch;
    gap: 1rem;
    padding: 1rem 1.25rem;
  }

  .submitted-by {
    padding-bottom: 0.75rem;
    border-bottom: 1px solid var(--color-border);
  }

  .actions {
    flex-direction: column-reverse;
    gap: 0.625rem;
  }

  .action-btn {
    width: 100%;
    padding: 0.875rem 1.5rem;
    font-size: 0.9375rem;
    min-height: 48px;
  }

  .modal {
    max-width: calc(100vw - 2rem);
  }

  .modal-footer {
    flex-direction: column-reverse;
    gap: 0.625rem;
  }

  .button {
    width: 100%;
    min-height: 48px;
  }
}

@media (max-width: 767px) {
  .approvals-header h1 {
    font-size: 1.5rem;
  }

  .content-title {
    font-size: 1.125rem;
  }

  .metadata {
    flex-direction: column;
    gap: 0.75rem;
  }
}

.load-more {
  display: flex;
  justify-content: center;
  padding: 1rem 0;
}
</style>
