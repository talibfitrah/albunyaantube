<template>
  <div class="approvals-view">
    <header class="approvals-header">
      <div>
        <h1>{{ t('approvals.heading') }}</h1>
        <p>{{ t('approvals.subtitle') }}</p>
      </div>
      <div class="header-stats">
        <div class="stat-badge">
          <span class="stat-value">{{ totalPending }}</span>
          <span class="stat-label">{{ t('approvals.pending') }}</span>
        </div>
      </div>
    </header>

    <!-- Filters -->
    <div class="filters">
      <div class="filter-group">
        <label>{{ t('approvals.filters.type') }}</label>
        <div class="filter-tabs">
          <button
            v-for="type in contentTypes"
            :key="type.value"
            type="button"
            :class="['filter-tab', { active: contentType === type.value }]"
            @click="contentType = type.value"
          >
            {{ t(type.labelKey) }}
          </button>
        </div>
      </div>

      <div class="filter-row">
        <div class="filter-item">
          <label>{{ t('approvals.filters.category') }}</label>
          <select v-model="categoryFilter" @change="handleFilterChange">
            <option value="">{{ t('approvals.filters.allCategories') }}</option>
            <option v-for="cat in categories" :key="cat.id" :value="cat.id">
              {{ cat.label }}
            </option>
          </select>
        </div>

        <div class="filter-item">
          <label>{{ t('approvals.filters.sort') }}</label>
          <select v-model="sortFilter" @change="handleFilterChange">
            <option value="oldest">{{ t('approvals.filters.oldest') }}</option>
            <option value="newest">{{ t('approvals.filters.newest') }}</option>
          </select>
        </div>
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
      <button type="button" @click="loadApprovals">{{ t('approvals.retry') }}</button>
    </div>

    <!-- Empty State -->
    <div v-else-if="approvals.length === 0" class="empty-state">
      <p>{{ t('approvals.empty') }}</p>
    </div>

    <!-- Approvals Grid -->
    <div v-else class="approvals-grid">
      <div v-for="item in approvals" :key="item.id" class="approval-card">
        <div class="card-header">
          <span class="content-type">{{ t(`approvals.types.${item.type}`) }}</span>
          <span class="submitted-date">{{ formatDate(item.submittedAt) }}</span>
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

        <div class="card-footer">
          <div class="submitted-by">
            <span class="meta-label">{{ t('approvals.submittedBy') }}:</span>
            <span>{{ item.submittedBy || t('approvals.unknown') }}</span>
          </div>
          <div class="actions">
            <button
              type="button"
              class="action-btn reject"
              :disabled="processingId === item.id"
              @click="openRejectDialog(item)"
            >
              {{ t('approvals.reject') }}
            </button>
            <button
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
import { fetchAllCategories } from '@/services/categories';

const { t } = useI18n();

const contentType = ref<'all' | 'channels' | 'playlists' | 'videos'>('all');
const categoryFilter = ref('');
const sortFilter = ref('oldest');

const approvals = ref<any[]>([]);
const categories = ref<any[]>([]);
const isLoading = ref(false);
const error = ref<string | null>(null);
const processingId = ref<string | null>(null);

const showRejectDialog = ref(false);
const rejectItem = ref<any | null>(null);
const rejectReason = ref('');
const isRejecting = ref(false);
const rejectError = ref<string | null>(null);

const contentTypes = [
  { value: 'all', labelKey: 'approvals.types.all' },
  { value: 'channels', labelKey: 'approvals.types.channels' },
  { value: 'playlists', labelKey: 'approvals.types.playlists' },
  { value: 'videos', labelKey: 'approvals.types.videos' }
];

const totalPending = computed(() => approvals.value.length);

async function loadCategories() {
  try {
    const cats = await fetchAllCategories();
    categories.value = cats;
  } catch (err) {
    console.error('Failed to load categories', err);
  }
}

async function loadApprovals() {
  isLoading.value = true;
  error.value = null;

  try {
    // TODO: Implement actual API call to fetch pending approvals
    // For now, return empty array
    await new Promise(resolve => setTimeout(resolve, 500));
    approvals.value = [];
  } catch (err) {
    error.value = err instanceof Error ? err.message : t('approvals.error');
  } finally {
    isLoading.value = false;
  }
}

function handleFilterChange() {
  loadApprovals();
}

async function handleApprove(item: any) {
  if (processingId.value) return;

  processingId.value = item.id;
  try {
    // TODO: Implement actual approve API call
    await new Promise(resolve => setTimeout(resolve, 500));
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
    // TODO: Implement actual reject API call
    await new Promise(resolve => setTimeout(resolve, 500));
    closeRejectDialog();
    await loadApprovals();
  } catch (err) {
    rejectError.value = err instanceof Error ? err.message : t('approvals.rejectError');
  } finally {
    isRejecting.value = false;
  }
}

function getCategoryName(categoryId: string): string {
  function findCategory(cats: any[], id: string): any {
    for (const cat of cats) {
      if (cat.id === id) return cat;
      if (cat.subcategories) {
        const found = findCategory(cat.subcategories, id);
        if (found) return found;
      }
    }
    return null;
  }

  const cat = findCategory(categories.value, categoryId);
  return cat ? cat.label : categoryId;
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
}

.filter-tab:hover {
  border-color: var(--color-brand);
  background: var(--color-brand-soft);
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

.filter-item select:hover {
  border-color: var(--color-brand);
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

.approval-card:hover {
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.08);
  border-color: var(--color-brand);
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 1rem 1.5rem;
  background: var(--color-surface-alt);
  border-bottom: 1px solid var(--color-border);
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
}

.action-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.action-btn.approve {
  background: var(--color-success);
  color: var(--color-text-inverse);
}

.action-btn.approve:not(:disabled):hover {
  background: var(--color-success);
  filter: brightness(1.1);
  box-shadow: 0 2px 8px rgba(21, 128, 61, 0.25);
}

.action-btn.reject {
  background: transparent;
  color: var(--color-danger);
  border: 1.5px solid var(--color-danger);
}

.action-btn.reject:not(:disabled):hover {
  background: var(--color-danger-soft);
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
  width: 2rem;
  height: 2rem;
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
}

.close-button:hover {
  background: var(--color-surface-alt);
  color: var(--color-text-primary);
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

.form-group textarea:hover {
  border-color: var(--color-brand);
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
}

.button.secondary {
  background: var(--color-surface-alt);
  color: var(--color-text-primary);
  border: 1.5px solid var(--color-border);
}

.button.secondary:hover {
  background: var(--color-surface);
  border-color: var(--color-brand);
}

.button.danger {
  background: var(--color-danger);
  color: var(--color-text-inverse);
}

.button.danger:hover:not(:disabled) {
  background: var(--color-danger-strong);
  box-shadow: 0 2px 8px rgba(220, 38, 38, 0.25);
}

.button.danger:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

@media (max-width: 768px) {
  .card-body {
    grid-template-columns: 1fr;
  }

  .thumbnail {
    width: 100%;
    height: 200px;
  }

  .card-footer {
    flex-direction: column;
    align-items: stretch;
    gap: 1rem;
  }

  .actions {
    flex-direction: column;
  }
}
</style>
