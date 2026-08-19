<template>
  <div class="approvals-view">
    <header class="approvals-header">
      <div>
        <h1>{{ isModeratorView ? t('approvals.mySubmissionsHeading') : t('approvals.heading') }}</h1>
        <p>{{ isModeratorView ? t('approvals.mySubmissionsSubtitle') : t('approvals.subtitle') }}</p>
      </div>
      <div class="header-stats">
        <div :class="['stat-badge', { 'stat-unavailable': countUnavailable }]">
          <span class="stat-value">{{ displayPendingCount }}</span>
          <span class="stat-label">{{ countUnavailable ? t('approvals.pendingApprox') : t('approvals.pending') }}</span>
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

    <!-- Admin: queue vs by-user. Bulk imports from ordinary users are reviewed per person. -->
    <div v-else class="admin-tabs">
      <button
        type="button"
        :class="['filter-tab', { active: adminTab === 'queue' }]"
        @click="selectAdminTab('queue')"
      >
        {{ t('approvals.tabs.queue') }}
      </button>
      <button
        type="button"
        :class="['filter-tab', { active: adminTab === 'byUser' }]"
        @click="selectAdminTab('byUser')"
      >
        {{ t('approvals.tabs.byUser') }}
      </button>
    </div>

    <!-- Admin Filters -->
    <div v-if="!isModeratorView && !showingSubmitterList" class="filters">
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

      <div v-if="!selectedSubmitter" class="filter-group">
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
    <!-- cubic-P2: non-blocking categories-load warning — renders alongside the queue, never replaces it -->
    <div v-if="categoriesError" class="error-panel" role="alert">
      <p>{{ categoriesError }}</p>
    </div>

    <!-- By-user tab: the people, before their items -->
    <template v-if="showingSubmitterList">
      <div v-if="isLoadingSubmitters" class="loading">
        <div class="spinner"></div>
        <p>{{ t('approvals.loading') }}</p>
      </div>
      <div v-else-if="submittersError" class="error-panel" role="alert">
        <p>{{ submittersError }}</p>
        <button type="button" @click="loadSubmitters()">{{ t('approvals.retry') }}</button>
      </div>
      <div v-else-if="submitters.length === 0" class="empty-state">
        <p>{{ t('approvals.byUser.empty') }}</p>
      </div>
      <ul v-else class="submitter-list">
        <li v-for="submitter in submitters" :key="submitter.uid">
          <button type="button" class="submitter-row" @click="openSubmitter(submitter)">
            <span class="submitter-name">{{ submitter.label }}</span>
            <span class="submitter-count">{{ t('approvals.byUser.count', { count: submitter.pendingCount }) }}</span>
          </button>
        </li>
      </ul>
    </template>

    <template v-else>
    <div v-if="selectedSubmitter" class="submitter-crumb">
      <button type="button" class="button secondary" @click="backToSubmitters">
        ← {{ t('approvals.byUser.back') }}
      </button>
      <span class="submitter-name">{{ selectedSubmitter.label }}</span>
    </div>

    <div v-if="(isLoading || isLoadingMore) && !approvals.length" class="loading">
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

    <!-- Approvals grid, with the bulk actions bar above it -->
    <template v-else>
    <div v-if="authStore.isAdmin" class="bulk-bar">
      <label class="bulk-select-all">
        <input
          type="checkbox"
          :checked="allLoadedSelected"
          :indeterminate.prop="someLoadedSelected"
          @change="toggleSelectAll"
        />
        <span>{{ t('approvals.bulk.selectAllLoaded') }}</span>
      </label>

      <template v-if="selectedIds.size > 0">
        <span class="bulk-count">{{ t('approvals.bulk.selected', { count: selectedIds.size }) }}</span>

        <div v-if="selectionHasUncategorised" class="bulk-category">
          <label for="bulk-category-select">{{ t('approvals.bulk.categoryLabel') }}</label>
          <select id="bulk-category-select" v-model="bulkCategory">
            <option value="">{{ t('approvals.selectCategory') }}</option>
            <option v-for="cat in flatCategories" :key="cat.id" :value="cat.id">{{ cat.label }}</option>
          </select>
        </div>

        <button type="button" class="button danger" :disabled="isBulkProcessing" @click="openBulkRejectDialog">
          {{ t('approvals.bulk.reject', { count: selectedIds.size }) }}
        </button>
        <button type="button" class="button primary" :disabled="isBulkProcessing" @click="bulkApprove">
          <span v-if="isBulkProcessing">{{ t('approvals.bulk.working') }}</span>
          <span v-else>{{ t('approvals.bulk.approve', { count: selectedIds.size }) }}</span>
        </button>
        <button type="button" class="button secondary" :disabled="isBulkProcessing" @click="clearSelection">
          {{ t('approvals.bulk.clear') }}
        </button>
      </template>
    </div>

    <div class="approvals-grid">
      <div v-for="item in approvals" :key="item.id" :class="['approval-card', { selected: isSelected(item.id) }]">
        <div class="card-header">
          <div class="card-header-left">
            <input
              v-if="authStore.isAdmin"
              type="checkbox"
              class="card-select"
              :aria-label="t('approvals.selectItem', { title: item.title })"
              :checked="isSelected(item.id)"
              @change="toggleSelect(item.id)"
            />
            <span class="content-type">{{ t(`approvals.types.${item.type}`) }}</span>
            <span v-if="item.source === 'USER_IMPORT'" class="source-badge source-user-import">
              {{ t('approvals.sourceUserImport') }}
            </span>
          </div>
          <div class="card-header-right">
            <span v-if="isModeratorView && item.status" :class="['status-badge', `status-${item.status.toLowerCase()}`]">
              {{ t(`approvals.statusTabs.${item.status.toLowerCase()}`) }}
            </span>
            <span class="submitted-date">{{ formatDate(item.submittedAt) }}</span>
          </div>
        </div>

        <div class="card-body">
          <div class="thumbnail">
            <img v-if="getThumbnailUrl(item, item.type)" :src="getThumbnailUrl(item, item.type) ?? ''" :alt="item.title" @error="handleThumbnailError($event, item)" />
            <div class="thumbnail-placeholder" :style="getThumbnailUrl(item, item.type) ? 'display:none' : ''"></div>
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

        <!-- Free-text "why I'm suggesting this" note from the submitter. Surfaces on
             every card (admin queue AND moderator's My Submissions) so admins get
             the context the submitter wanted them to have. -->
        <div v-if="item.submitterNote" class="submitter-note">
          <span class="meta-label">{{ t('approvals.submitterNote') }}:</span>
          <p>{{ item.submitterNote }}</p>
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

        <!-- Category selector: offered (not required) for admin view on items with no categories -->
        <div
          v-if="authStore.isAdmin && (!item.categories || item.categories.length === 0)"
          class="category-optional-row"
        >
          <label :for="`cat-select-${item.id}`" class="meta-label">
            {{ t('approvals.categoryOptional') }}
          </label>
          <select
            :id="`cat-select-${item.id}`"
            :value="selectedCategoryOverrides[item.id] ?? ''"
            class="category-override-select"
            @change="onCategoryOverrideChange(item.id, ($event.target as HTMLSelectElement).value)"
          >
            <option value="">{{ t('approvals.selectCategory') }}</option>
            <option v-for="cat in flatCategories" :key="cat.id" :value="cat.id">
              {{ cat.label }}
            </option>
          </select>
        </div>

        <div class="card-footer">
          <div class="submitted-by">
            <span class="meta-label">{{ t('approvals.submittedBy') }}:</span>
            <span>{{ item.submittedByLabel || t('approvals.unknown') }}</span>
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
              :disabled="processingId === item.id || isBulkProcessing"
              @click="openRejectDialog(item)"
            >
              {{ t('approvals.reject') }}
            </button>
            <button
              v-if="authStore.isAdmin"
              type="button"
              class="action-btn approve"
              :disabled="processingId === item.id || isBulkProcessing"
              @click="handleApprove(item)"
            >
              <span v-if="processingId === item.id">{{ t('approvals.approving') }}</span>
              <span v-else>{{ item.source === 'USER_IMPORT' ? t('approvals.approvePublic') : t('approvals.approve') }}</span>
            </button>
            <button
              v-if="authStore.isAdmin && item.source === 'USER_IMPORT'"
              type="button"
              class="action-btn approve-personal"
              :disabled="processingId === item.id || isBulkProcessing"
              :title="t('approvals.approvePersonalHint')"
              @click="handleApprovePersonal(item)"
            >
              {{ t('approvals.approvePersonal') }}
            </button>
          </div>
        </div>
      </div>
    </div>
    </template>

    <!-- Load More Button -->
    <div v-if="nextCursor && !isLoading" class="load-more">
      <button type="button" class="button secondary" :disabled="isLoadingMore" @click="loadMore">
        <span v-if="isLoadingMore">{{ t('approvals.loadingMore') }}</span>
        <span v-else>{{ t('approvals.loadMore') }}</span>
      </button>
    </div>

    </template>

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
            <h2>
              {{ rejectTargets.length > 1
                ? t('approvals.rejectDialog.titleMany', { count: rejectTargets.length })
                : t('approvals.rejectDialog.title') }}
            </h2>
            <button type="button" class="close-button" @click="closeRejectDialog">×</button>
          </header>

          <form @submit.prevent="handleReject">
            <div class="modal-body">
              <p v-if="rejectTargets.length === 1" class="reject-content-name">{{ rejectTargets[0]?.title }}</p>
              <ul v-else class="reject-content-list">
                <li v-for="target in rejectTargets" :key="target.id">{{ target.title }}</li>
              </ul>

              <div class="form-group">
                <label for="reject-reason">{{ t('approvals.rejectDialog.reason') }}</label>
                <textarea
                  id="reject-reason"
                  v-model="rejectReason"
                  rows="4"
                  :placeholder="t('approvals.rejectDialog.reasonPlaceholder')"
                ></textarea>
                <p class="form-hint">{{ t('approvals.rejectDialog.reasonOptional') }}</p>
              </div>

              <div v-if="rejectError" class="form-error">{{ rejectError }}</div>
            </div>

            <div class="modal-footer">
              <button type="button" class="button secondary" @click="closeRejectDialog">
                {{ t('approvals.rejectDialog.cancel') }}
              </button>
              <button type="submit" class="button danger" :disabled="isRejecting">
                <span v-if="isRejecting">{{ t('approvals.rejectDialog.rejecting') }}</span>
                <span v-else-if="rejectTargets.length > 1">{{ t('approvals.rejectDialog.confirmMany') }}</span>
                <span v-else>{{ t('approvals.rejectDialog.confirm') }}</span>
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
import { getThumbnailUrl, getThumbnailFallbacks } from '@/utils/formatters';
import { useToast } from '@/composables/useToast';
import { getAllCategories } from '@/services/categoryService';
import { getPendingApprovals, getPendingSubmitters, getMySubmissions, approveItem, rejectItem as rejectItemApi, getPendingCount, type PendingApproval, type PendingSubmitter, type SubmissionStatus, type MySubmission } from '@/services/approvalService';
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

/**
 * Admin view splits into two kinds of work: the moderator-curated queue, and bulk imports from
 * ordinary users, which are reviewed per person.
 */
const adminTab = ref<'queue' | 'byUser'>('queue');
const submitters = ref<PendingSubmitter[]>([]);
const selectedSubmitter = ref<PendingSubmitter | null>(null);
const submittersError = ref<string | null>(null);
/** Set when a decision inside a drill-down invalidates the submitter counts. */
const submitterCountsStale = ref(false);
const isLoadingSubmitters = ref(false);

/** True while the by-user tab is showing the list of people rather than one person's items. */
const showingSubmitterList = computed(
  () => !isModeratorView.value && adminTab.value === 'byUser' && !selectedSubmitter.value
);

async function loadSubmitters() {
  isLoadingSubmitters.value = true;
  submittersError.value = null;
  try {
    submitters.value = await getPendingSubmitters();
  } catch {
    submittersError.value = t('approvals.byUser.error');
  } finally {
    isLoadingSubmitters.value = false;
  }
}

/**
 * How many phone imports are waiting, across everybody.
 *
 * Summed from the by-user roll-up, which scans a bounded number of pending documents. Past that
 * bound this under-counts, so the queue badge — which subtracts it from an exact total — would
 * over-report by however many imports went uncounted. Bounded by a queue depth far past what a
 * person reviews by hand, and the server logs when it hits it.
 */
const userImportTotal = computed(() =>
  submitters.value.reduce((sum, s) => sum + s.pendingCount, 0)
);

function selectAdminTab(tab: 'queue' | 'byUser') {
  if (adminTab.value === tab) return;
  adminTab.value = tab;
  selectedSubmitter.value = null;
  clearSelection();
  approvals.value = [];
  nextCursor.value = null;
  if (tab === 'byUser') {
    if (!submitters.value.length || submitterCountsStale.value) loadSubmitters();
    submitterCountsStale.value = false;
  } else {
    loadApprovals();
  }
}

function openSubmitter(submitter: PendingSubmitter) {
  selectedSubmitter.value = submitter;
  // The per-submitter query cannot filter by category; clear it rather than leave a filter set
  // that no longer affects the results.
  categoryFilter.value = '';
  clearSelection();
  loadApprovals();
}

function backToSubmitters() {
  const countsChanged = submitterCountsStale.value;
  selectedSubmitter.value = null;
  submitterCountsStale.value = false;
  clearSelection();
  approvals.value = [];
  nextCursor.value = null;
  // The roll-up scans every pending document, so only pay for it when a decision in the
  // drill-down can actually have moved the counts.
  if (countsChanged || submitters.value.length === 0) loadSubmitters();
}

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
// Thumbnail fallback state — DOM-based pattern to avoid reactive cascade re-renders
const thumbnailFallbackIndex = new Map<string, number>();

function handleThumbnailError(event: Event, item: any) {
  const img = event.target as HTMLImageElement;
  const id = item.id;
  const idx = thumbnailFallbackIndex.get(id) ?? 0;
  const fallbacks = getThumbnailFallbacks(item, item.type);
  const nextIdx = Math.min(idx, fallbacks.length - 1);
  if (nextIdx < fallbacks.length && nextIdx >= 0) {
    thumbnailFallbackIndex.set(id, nextIdx + 1);
    img.src = fallbacks[nextIdx];
    return;
  }
  // All fallbacks exhausted — hide img and show placeholder via DOM (no reactive cascade)
  img.style.display = 'none';
  const placeholder = img.nextElementSibling;
  if (placeholder && placeholder.classList.contains('thumbnail-placeholder')) {
    (placeholder as HTMLElement).style.display = '';
  }
}

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

/**
 * Per-card category override selections. Keyed by item.id.
 * Only relevant for items with no existing categories (user-import items arrive
 * with empty categoryIds — the backend enforces a category before approving).
 */
const selectedCategoryOverrides = ref<Record<string, string>>({});

function onCategoryOverrideChange(itemId: string, categoryId: string) {
  if (categoryId) {
    selectedCategoryOverrides.value[itemId] = categoryId;
  } else {
    delete selectedCategoryOverrides.value[itemId];
  }
}

/** Returns true when the item already has at least one category assigned. */
function itemHasCategories(item: any): boolean {
  return !!(item.categories && item.categories.length > 0);
}

/**
 * Selection state for the bulk actions bar. Holds ids rather than items so it survives the
 * splice in [removeFromQueue] without dangling references.
 */
const selectedIds = ref<Set<string>>(new Set());
const bulkCategory = ref('');
const isBulkProcessing = ref(false);

const selectedItems = computed(() => approvals.value.filter(i => selectedIds.value.has(i.id)));
const allLoadedSelected = computed(
  () => approvals.value.length > 0 && approvals.value.every(i => selectedIds.value.has(i.id))
);
const someLoadedSelected = computed(() => selectedIds.value.size > 0 && !allLoadedSelected.value);
/** True when any selected item would be approved with no category at all. */
const selectionHasUncategorised = computed(() => selectedItems.value.some(i => !itemHasCategories(i)));

function isSelected(id: string): boolean {
  return selectedIds.value.has(id);
}

function toggleSelect(id: string) {
  // Reassign so Vue tracks the change — Set mutations are not reactive.
  const next = new Set(selectedIds.value);
  if (next.has(id)) next.delete(id);
  else next.add(id);
  selectedIds.value = next;
}

function toggleSelectAll() {
  selectedIds.value = allLoadedSelected.value
    ? new Set()
    : new Set(approvals.value.map(i => i.id));
}

function clearSelection() {
  selectedIds.value = new Set();
  bulkCategory.value = '';
}

/**
 * Drop actioned items from the loaded queue instead of refetching it.
 *
 * Refetching reset the cursor to the first page and threw away every page the reviewer had
 * loaded, so acting on the 100th item sent them back to the top. Splicing leaves the scroll
 * position untouched and costs one request fewer per action.
 */
function removeFromQueue(ids: string[]) {
  if (ids.length && selectedSubmitter.value) submitterCountsStale.value = true;
  const gone = new Set(ids);
  approvals.value = approvals.value.filter(item => !gone.has(item.id));
  const nextSelection = new Set(selectedIds.value);
  ids.forEach(id => {
    delete selectedCategoryOverrides.value[id];
    nextSelection.delete(id);
  });
  selectedIds.value = nextSelection;

  // Approving the whole loaded page empties the list while there are still pages behind it,
  // which would render "no pending approvals" directly above a Load more button. Pull the next
  // page instead — with a deep queue this is the normal outcome of a bulk approve, not an edge.
  if (approvals.value.length === 0 && nextCursor.value && !isLoading.value && !isLoadingMore.value) {
    loadApprovals(true);
  }
}

/**
 * Run `task` over `items` with at most `limit` in flight.
 *
 * The queue actions go through the per-item approve/reject endpoints — those are the only ones
 * that graduate the importers' rows, write approval metadata and audit, and CAS on PENDING. A
 * cap keeps a 50-item bulk from opening 50 sockets at once.
 */
async function runBounded<T>(items: T[], limit: number, task: (item: T) => Promise<void>) {
  let cursor = 0;
  const workers = Array.from({ length: Math.min(limit, items.length) }, async () => {
    while (cursor < items.length) {
      await task(items[cursor++]);
    }
  });
  await Promise.all(workers);
}

const BULK_CONCURRENCY = 4;

/** How many empty-but-cursored pages to step past before handing control back to the reviewer. */
const MAX_EMPTY_PAGE_RETRIES = 3;

/**
 * Category to send for one item: its own if it has one, otherwise whatever the caller chose.
 * A blank choice is legitimate — the item is approved uncategorized.
 */
function categoryOverrideFor(item: any, chosen: string): string | undefined {
  if (itemHasCategories(item)) return undefined;
  // A category picked on the card itself is the more specific instruction; the bulk picker is
  // the fallback for everything the admin did not file by hand.
  return selectedCategoryOverrides.value[item.id] || chosen || undefined;
}

const showRejectDialog = ref(false);
/** Items the open reject dialog will act on — one for a card reject, N for a bulk reject. */
const rejectTargets = ref<any[]>([]);
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

const totalPending = ref<number | null>(null);
const countUnavailable = ref(false);
const displayPendingCount = computed(() => {
  // The by-user tab counts its own; the queue's badge must not include the imports it no longer
  // shows, or the number and the list disagree by exactly that many.
  if (adminTab.value === 'byUser' && !isModeratorView.value) return userImportTotal.value;
  if (totalPending.value !== null) {
    return isModeratorView.value
      ? totalPending.value
      : Math.max(0, totalPending.value - userImportTotal.value);
  }
  // Before any count loads, show loaded items count as lower-bound
  return approvals.value.length;
});

const categoriesError = ref<string | null>(null);

async function loadCategories() {
  try {
    const cats = await getAllCategories();
    categories.value = cats;
    categoriesError.value = null;
  } catch (err) {
    // cubic-P2: surface on a SEPARATE ref — do NOT write the blocking `error` ref,
    // which gates the main panel and would hide the entire approvals queue when only
    // the categories endpoint is down (the approvals list may have loaded fine).
    console.error('Failed to load categories', err);
    categoriesError.value = t('approvals.loadCategoriesError');
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
      let cursor = append ? (nextCursor.value || undefined) : undefined;
      const collected: MySubmission[] = [];

      // A cursor page can come back empty while still handing back a next cursor — a type filter
      // that skips a whole cursor window, for instance. Landing on "no pending approvals" above
      // a Load more button is worse than one more request, so step past a few of those.
      for (let attempt = 0; attempt < MAX_EMPTY_PAGE_RETRIES; attempt++) {
        const result = await getPendingApprovals({
          type: contentType.value,
          category: categoryFilter.value || undefined,
          submittedBy: selectedSubmitter.value?.uid,
          // The queue is moderator submissions only; phone imports live in the by-user tab.
          // Applied server-side, so a page never comes back empty with more waiting behind it.
          scope: selectedSubmitter.value ? undefined : 'MODERATOR_QUEUE',
          cursor
        });
        collected.push(...result.items.map(item => ({ ...item, status: 'PENDING' as const })));
        cursor = result.nextCursor || undefined;
        nextCursor.value = result.nextCursor;
        if (collected.length || !result.nextCursor) break;
      }

      approvals.value = append ? [...approvals.value, ...collected] : collected;
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
  // The selection refers to the list being replaced. Keeping it would leave the bulk bar
  // claiming "3 selected" over cards that are no longer loaded, with buttons that do nothing.
  clearSelection();
  loadApprovals();
}

function loadMore() {
  if (nextCursor.value && !isLoadingMore.value) {
    loadApprovals(true);
  }
}

async function handleApprove(item: any) {
  if (processingId.value) return;

  const categoryOverride = categoryOverrideFor(item, '');

  processingId.value = item.id;
  try {
    await approveItem(item.id, item.type, categoryOverride);
    removeFromQueue([item.id]);
    loadPendingCount();
  } catch (err) {
    error.value = err instanceof Error ? err.message : t('approvals.approveError');
  } finally {
    processingId.value = null;
  }
}

async function bulkApprove() {
  if (isBulkProcessing.value) return;
  const batch = selectedItems.value;
  if (!batch.length) return;

  const chosen = bulkCategory.value;
  const approved: string[] = [];
  let failed = 0;
  let firstError: string | null = null;

  isBulkProcessing.value = true;
  try {
    await runBounded(batch, BULK_CONCURRENCY, async item => {
      try {
        await approveItem(item.id, item.type, categoryOverrideFor(item, chosen));
        approved.push(item.id);
      } catch (err) {
        // One rejection from the server must not abandon the rest of the batch — but a batch
        // where everything failed must still say why, not just how many.
        failed += 1;
        if (!firstError) firstError = err instanceof Error ? err.message : null;
      }
    });
  } finally {
    isBulkProcessing.value = false;
  }

  removeFromQueue(approved);
  bulkCategory.value = '';
  loadPendingCount();
  toast[failed ? 'warning' : 'success'](
    failed
      // Never through `error` — that ref gates the whole grid, so a failed batch would replace
      // every loaded page with an error panel whose retry refetches from page one.
      ? `${t('approvals.bulk.approvedWithFailures', { ok: approved.length, failed })}${firstError ? ` — ${firstError}` : ''}`
      : t('approvals.bulk.approved', { ok: approved.length })
  );
}

/**
 * Finding 3: personal approval — grants the imported item only to the user(s) who
 * imported it (no category required) and keeps it out of the public library.
 */
async function handleApprovePersonal(item: any) {
  if (processingId.value) return;
  processingId.value = item.id;
  try {
    await approveItem(item.id, item.type, undefined, undefined, 'PERSONAL');
    removeFromQueue([item.id]);
    loadPendingCount();
  } catch (err) {
    error.value = err instanceof Error ? err.message : t('approvals.approveError');
  } finally {
    processingId.value = null;
  }
}

function openRejectDialog(item: any) {
  rejectTargets.value = [item];
  rejectReason.value = '';
  rejectError.value = null;
  showRejectDialog.value = true;
}

/** Same dialog, whole selection — one reason typed once, applied to every item. */
function openBulkRejectDialog() {
  if (!selectedItems.value.length) return;
  rejectTargets.value = [...selectedItems.value];
  rejectReason.value = '';
  rejectError.value = null;
  showRejectDialog.value = true;
}

function closeRejectDialog() {
  showRejectDialog.value = false;
  rejectTargets.value = [];
  rejectReason.value = '';
  rejectError.value = null;
}

async function handleReject() {
  const targets = rejectTargets.value;
  if (!targets.length) return;

  // The reason is optional. Blank means "no feedback", not "OTHER".
  const reason = rejectReason.value.trim() || undefined;
  const rejected: string[] = [];
  let failed = 0;

  isRejecting.value = true;
  rejectError.value = null;

  let firstError: string | null = null;

  try {
    await runBounded(targets, BULK_CONCURRENCY, async item => {
      try {
        await rejectItemApi(item.id, item.type, reason);
        rejected.push(item.id);
      } catch (err) {
        failed += 1;
        // Keep the server's words for the reviewer — "already reviewed in another tab" is
        // actionable in a way that a generic failure is not.
        if (!firstError) firstError = err instanceof Error ? err.message : null;
      }
    });

    if (!rejected.length) {
      rejectError.value = firstError || t('approvals.rejectError');
      return;
    }

    closeRejectDialog();
    removeFromQueue(rejected);
    loadPendingCount();
    if (targets.length > 1) {
      toast[failed ? 'warning' : 'success'](
        failed
          ? t('approvals.bulk.rejectedWithFailures', { ok: rejected.length, failed })
          : t('approvals.bulk.rejected', { ok: rejected.length })
      );
    }
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

async function loadPendingCount() {
  try {
    const count = await getPendingCount();
    if (count >= 0) {
      totalPending.value = count;
      countUnavailable.value = false;
    } else {
      // -1 means service unavailable — use loaded items as lower-bound
      countUnavailable.value = true;
      totalPending.value = Math.max(totalPending.value ?? 0, approvals.value.length);
    }
  } catch {
    countUnavailable.value = true;
    totalPending.value = Math.max(totalPending.value ?? 0, approvals.value.length);
  }
}

onMounted(() => {
  loadCategories();
  loadApprovals();
  loadPendingCount();
  // Needed by the queue badge too, which subtracts the imports it no longer lists.
  if (!isModeratorView.value) loadSubmitters();
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

.stat-unavailable {
  opacity: 0.7;
  border-style: dashed;
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

.submitter-note {
  padding: 0.875rem 1.5rem;
  background: var(--color-brand-soft, var(--color-surface-alt));
  border-top: 1px solid var(--color-border);
  border-left: 3px solid var(--color-brand);
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}

.submitter-note p {
  margin: 0;
  font-size: 0.9375rem;
  line-height: 1.45;
  white-space: pre-wrap;
}

.review-detail {
  display: flex;
  gap: 0.5rem;
  font-size: 0.875rem;
  line-height: 1.5;
}

.approval-card.selected {
  border-color: var(--color-brand);
  box-shadow: inset 3px 0 0 var(--color-brand);
}

.card-header-left {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.card-select {
  width: 1rem;
  height: 1rem;
  cursor: pointer;
  accent-color: var(--color-brand);
}

.admin-tabs {
  display: flex;
  gap: 0.5rem;
  margin-bottom: 1rem;
}

.submitter-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.submitter-row {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
  padding: 0.875rem 1.25rem;
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: 0.75rem;
  cursor: pointer;
  text-align: start;
  font: inherit;
  color: inherit;
}

@media (hover: hover) {
  .submitter-row:hover {
    border-color: var(--color-brand);
  }
}

.submitter-name {
  font-weight: 600;
}

.submitter-count {
  font-size: 0.875rem;
  color: var(--color-text-muted, #64748b);
  white-space: nowrap;
}

.submitter-crumb {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  margin-bottom: 1rem;
}

.bulk-bar {
  position: sticky;
  top: 0;
  z-index: 5;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.75rem;
  padding: 0.75rem 1rem;
  margin-bottom: 1rem;
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: 0.75rem;
}

.bulk-select-all {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  font-size: 0.875rem;
  cursor: pointer;
}

.bulk-select-all input {
  accent-color: var(--color-brand);
}

.bulk-count {
  font-size: 0.875rem;
  font-weight: 600;
}

.bulk-category {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  font-size: 0.875rem;
}

/* Push the actions to the trailing edge — direction-aware, so RTL mirrors correctly. */
.bulk-bar .button:first-of-type {
  margin-inline-start: auto;
}

.reject-content-list {
  margin: 0 0 1rem;
  padding-inline-start: 1.25rem;
  max-height: 12rem;
  overflow-y: auto;
  font-size: 0.875rem;
}

.form-hint {
  margin: 0.35rem 0 0;
  font-size: 0.8125rem;
  color: var(--color-text-muted, #64748b);
}

.content-type {
  font-size: 0.8125rem;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.05em;
  color: var(--color-brand);
}

.source-badge {
  font-size: 0.6875rem;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  padding: 0.2rem 0.5rem;
  border-radius: 999px;
}

.source-user-import {
  background: rgba(139, 92, 246, 0.12);
  color: #7c3aed;
  border: 1px solid rgba(139, 92, 246, 0.3);
}

.category-optional-row {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  padding: 0.875rem 1.5rem;
  background: var(--color-surface-muted, rgba(148, 163, 184, 0.08));
  border-top: 1px solid var(--color-border);
}

.category-optional-row .meta-label {
  white-space: nowrap;
  font-size: 0.875rem;
}

.category-override-select {
  flex: 1;
  min-width: 0;
  padding: 0.4rem 0.75rem;
  border: 1.5px solid var(--color-border);
  border-radius: 0.375rem;
  background: var(--color-surface);
  font-size: 0.875rem;
  cursor: pointer;
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
