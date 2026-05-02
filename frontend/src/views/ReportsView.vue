<template>
  <section class="reports-view">
    <header class="workspace-header">
      <div>
        <h1>{{ t('reports.heading') }}</h1>
        <p>{{ t('reports.description') }}</p>
      </div>
    </header>

    <!-- Stats bar -->
    <div v-if="stats" class="stats-bar">
      <div class="stat-card">
        <span class="stat-value">{{ stats.pending }}</span>
        <span class="stat-label">{{ t('reports.stats.pending') }}</span>
      </div>
      <div class="stat-card accent">
        <span class="stat-value">{{ stats.newLast3h }}</span>
        <span class="stat-label">{{ t('reports.stats.newLast3h') }}</span>
      </div>
      <div class="stat-card muted">
        <span class="stat-value">{{ stats.resolved }}</span>
        <span class="stat-label">{{ t('reports.stats.resolved') }}</span>
      </div>
      <div class="stat-card muted">
        <span class="stat-value">{{ stats.rejected }}</span>
        <span class="stat-label">{{ t('reports.stats.rejected') }}</span>
      </div>
    </div>

    <!-- Filters -->
    <div class="filter-bar">
      <div class="filter-group">
        <label class="filter-label">{{ t('reports.filters.status') }}</label>
        <div class="filter-options" role="radiogroup">
          <button
            v-for="opt in statusOptions"
            :key="opt.value"
            type="button"
            class="filter-option"
            :class="{ active: statusFilter === opt.value }"
            @click="statusFilter = opt.value; currentPage = 0; load()"
          >
            {{ opt.label }}
          </button>
        </div>
      </div>
      <div class="filter-group">
        <label class="filter-label">{{ t('reports.filters.targetType') }}</label>
        <div class="filter-options" role="radiogroup">
          <button
            v-for="opt in targetTypeOptions"
            :key="opt.value"
            type="button"
            class="filter-option"
            :class="{ active: targetTypeFilter === opt.value }"
            @click="targetTypeFilter = opt.value; currentPage = 0; load()"
          >
            {{ opt.label }}
          </button>
        </div>
      </div>
    </div>

    <!-- Table -->
    <div class="table-wrapper" role="region" aria-live="polite">
      <div v-if="error" class="error-state">
        <p>{{ t('reports.table.error') }}</p>
        <button type="button" class="retry" @click="load">{{ t('reports.table.retry') }}</button>
      </div>

      <table v-else class="data-table">
        <thead>
          <tr>
            <th scope="col">{{ t('reports.table.col.type') }}</th>
            <th scope="col">{{ t('reports.table.col.content') }}</th>
            <th scope="col">{{ t('reports.table.col.reasons') }}</th>
            <th scope="col">{{ t('reports.table.col.submitted') }}</th>
            <th scope="col">{{ t('reports.table.col.status') }}</th>
            <th scope="col">{{ t('reports.table.col.actions') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="isLoading && !reports.length">
            <td :colspan="6">
              <div class="skeleton-stack" aria-hidden="true">
                <div v-for="i in 5" :key="i" class="skeleton-row"></div>
              </div>
            </td>
          </tr>
          <tr v-else-if="!reports.length">
            <td :colspan="6" class="empty-state">{{ t('reports.table.empty') }}</td>
          </tr>
          <tr v-for="report in reports" :key="report.id">
            <td>
              <span class="type-badge" :class="report.targetType.toLowerCase()">{{ report.targetType }}</span>
              <span
                v-if="report.contentSubType"
                class="subtype-badge"
                :class="report.contentSubType.toLowerCase()"
              >{{ report.contentSubType }}</span>
            </td>
            <td class="content-cell">
              <div class="content-info">
                <img
                  v-if="getContentMeta(report)?.thumbnailUrl"
                  :src="getContentMeta(report)!.thumbnailUrl"
                  class="content-thumb"
                  :class="{ 'thumb-circle': report.targetType === 'CHANNEL' }"
                  loading="lazy"
                  alt=""
                />
                <div v-else class="content-thumb-placeholder" :class="report.targetType.toLowerCase()" />
                <div class="content-details">
                  <span class="content-title">{{ getContentMeta(report)?.title ?? '…' }}</span>
                  <span class="content-id" :title="report.targetId">{{ report.targetId }}</span>
                  <span
                    v-if="report.parentType && report.parentId"
                    class="parent-context"
                    :title="`${report.parentType}: ${report.parentId}`"
                  >
                    from {{ report.parentType.toLowerCase() }}
                    <strong>{{ getParentMeta(report)?.title ?? report.parentId }}</strong>
                  </span>
                </div>
              </div>
            </td>
            <td>
              <ul class="reason-list">
                <li v-for="r in report.reasons" :key="r">{{ REASON_LABELS[r] }}</li>
                <li v-if="report.otherDescription" class="other-desc">"{{ report.otherDescription }}"</li>
              </ul>
            </td>
            <td class="timestamp">{{ formatTimestamp(report.createdAt) }}</td>
            <td>
              <span class="status-badge" :class="report.status.toLowerCase()">{{ report.status }}</span>
              <div v-if="report.resolutionNote" class="resolution-note" :title="report.resolutionNote">
                {{ report.resolutionNote }}
              </div>
            </td>
            <td>
              <div class="action-buttons">
                <button
                  type="button"
                  class="btn-preview"
                  @click="openPreview(report)"
                >
                  {{ t('reports.actions.preview') }}
                </button>
                <template v-if="report.status === 'PENDING'">
                  <button
                    type="button"
                    class="btn-resolve"
                    :disabled="actionLoadingId === report.id"
                    @click="openResolveDialog(report, 'RESOLVED')"
                  >
                    {{ t('reports.actions.resolve') }}
                  </button>
                  <button
                    type="button"
                    class="btn-reject"
                    :disabled="actionLoadingId === report.id"
                    @click="openResolveDialog(report, 'REJECTED')"
                  >
                    {{ t('reports.actions.reject') }}
                  </button>
                </template>
                <span v-else class="resolved-by">
                  {{ report.resolvedBy || '—' }}
                </span>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- Pagination -->
    <div v-if="!error && (reports.length || currentPage > 0)" class="pagination">
      <button type="button" class="page-btn" :disabled="currentPage === 0 || isLoading" @click="prevPage">
        ← {{ t('reports.pagination.prev') }}
      </button>
      <span class="page-indicator">{{ t('reports.pagination.page', { n: currentPage + 1 }) }}</span>
      <button type="button" class="page-btn" :disabled="!hasNextPage || isLoading" @click="nextPage">
        {{ t('reports.pagination.next') }} →
      </button>
    </div>

    <!-- Preview modals by target type -->
    <ChannelDetailModal
      v-if="previewItem?.targetType === 'CHANNEL'"
      :open="showPreview"
      :channel-id="''"
      :channel-youtube-id="previewItem.targetId"
      @close="closePreview"
    />
    <PlaylistDetailModal
      v-if="previewItem?.targetType === 'PLAYLIST'"
      :open="showPreview"
      :playlist-id="''"
      :playlist-youtube-id="previewItem.targetId"
      @close="closePreview"
    />
    <VideoPreviewModal
      v-if="previewItem?.targetType === 'VIDEO'"
      :open="showPreview"
      :youtube-id="previewItem.targetId"
      :title="previewItem.targetId"
      @close="closePreview"
    />

    <!-- Resolve / Reject dialog -->
    <div v-if="resolveDialog.visible" class="dialog-backdrop" role="dialog" aria-modal="true" :aria-label="t('reports.dialog.title')">
      <div class="dialog">
        <h2>{{ resolveDialog.action === 'RESOLVED' ? t('reports.dialog.resolveTitle') : t('reports.dialog.rejectTitle') }}</h2>
        <p class="dialog-sub">{{ t('reports.dialog.noteLabelOptional') }}</p>
        <textarea
          v-model="resolveDialog.note"
          class="note-input"
          :placeholder="t('reports.dialog.notePlaceholder')"
          rows="3"
        ></textarea>
        <div class="dialog-actions">
          <button type="button" class="btn-cancel" @click="resolveDialog.visible = false">
            {{ t('reports.dialog.cancel') }}
          </button>
          <button
            type="button"
            class="btn-confirm"
            :class="resolveDialog.action === 'RESOLVED' ? 'btn-resolve' : 'btn-reject'"
            :disabled="isSubmitting"
            @click="submitResolve"
          >
            {{ isSubmitting ? t('reports.dialog.submitting') : t('reports.dialog.confirm') }}
          </button>
        </div>
      </div>
    </div>
  </section>
</template>

<script setup lang="ts">
import { onMounted, ref, computed } from 'vue';
import { useI18n } from 'vue-i18n';
import ChannelDetailModal from '@/components/exclusions/ChannelDetailModal.vue';
import PlaylistDetailModal from '@/components/exclusions/PlaylistDetailModal.vue';
import VideoPreviewModal from '@/components/VideoPreviewModal.vue';
import apiClient from '@/services/api/client';
import {
  fetchReports,
  fetchReportStats,
  resolveReport,
  formatTimestamp,
  REASON_LABELS,
  type ContentReport,
  type ReportStats,
  type ReportStatus,
  type ReportTargetType,
} from '@/services/reportsService';

const { t } = useI18n();

const PAGE_SIZE = 20;

// State
const reports = ref<ContentReport[]>([]);
const stats = ref<ReportStats | null>(null);
const isLoading = ref(false);
const isSubmitting = ref(false);
const error = ref(false);
const actionLoadingId = ref<string | null>(null);
const currentPage = ref(0);
const hasNextPage = ref(false);

// Filters
type StatusOption = ReportStatus | 'ALL';
type TargetOption = ReportTargetType | 'ALL';
const statusFilter = ref<StatusOption>('PENDING');
const targetTypeFilter = ref<TargetOption>('ALL');

const statusOptions = computed<{ value: StatusOption; label: string }[]>(() => [
  { value: 'ALL', label: t('reports.filters.all') },
  { value: 'PENDING', label: t('reports.filters.pending') },
  { value: 'RESOLVED', label: t('reports.filters.resolved') },
  { value: 'REJECTED', label: t('reports.filters.rejected') },
]);

const targetTypeOptions = computed<{ value: TargetOption; label: string }[]>(() => [
  { value: 'ALL', label: t('reports.filters.all') },
  { value: 'VIDEO', label: t('reports.filters.video') },
  { value: 'CHANNEL', label: t('reports.filters.channel') },
  { value: 'PLAYLIST', label: t('reports.filters.playlist') },
]);

// Preview state
const showPreview = ref(false);
const previewItem = ref<ContentReport | null>(null);

function openPreview(report: ContentReport) {
  previewItem.value = report;
  showPreview.value = true;
}

function closePreview() {
  showPreview.value = false;
  setTimeout(() => { previewItem.value = null; }, 300);
}

// Content metadata (thumbnail + title for the reports table)
interface ContentMeta { thumbnailUrl?: string; title: string; }
const contentMeta = ref<Record<string, ContentMeta>>({});

function getContentMeta(report: ContentReport): ContentMeta | null {
  return contentMeta.value[`${report.targetType}:${report.targetId}`] ?? null;
}

function getParentMeta(report: ContentReport): ContentMeta | null {
  if (!report.parentType || !report.parentId) return null;
  return contentMeta.value[`${report.parentType}:${report.parentId}`] ?? null;
}

async function fetchContentMeta(items: ContentReport[]) {
  const seen = new Set<string>();
  // Each report contributes both its target and (optionally) its parent
  // to the lookup queue — admins need to see "Reported video [Title] from
  // playlist [Parent Title]" together.
  type LookupKey = { type: ReportTargetType | string; id: string };
  const queue: LookupKey[] = [];
  for (const r of items) {
    const tk = `${r.targetType}:${r.targetId}`;
    if (!seen.has(tk)) { seen.add(tk); queue.push({ type: r.targetType, id: r.targetId }); }
    if (r.parentType && r.parentId) {
      const pk = `${r.parentType}:${r.parentId}`;
      if (!seen.has(pk)) { seen.add(pk); queue.push({ type: r.parentType, id: r.parentId }); }
    }
  }
  await Promise.allSettled(queue.map(async (r) => {
    const key = `${r.type}:${r.id}`;
    if (contentMeta.value[key]) return;
    // YouTube video thumbnail URL pattern works for any video ID (regular
    // videos, shorts, livestreams) without an API key — keep it as the
    // last-resort fallback when both registry and NewPipe fail.
    const ytVideoThumb = r.type === 'VIDEO'
      ? `https://img.youtube.com/vi/${r.id}/mqdefault.jpg`
      : undefined;
    // Admin-only lookup — goes straight to NewPipe so unregistered videos
    // and playlists (loose items, child videos inside approved parents)
    // still render with real title + thumbnail. The public /api/v1
    // endpoints 404 on anything not in the approval registry, which left
    // the admin table showing opaque IDs and missing artwork.
    const lookupUrl = `/api/admin/reports/lookup?type=${r.type}&id=${encodeURIComponent(r.id)}`;
    try {
      const res = await apiClient.get<{ title?: string; name?: string; thumbnailUrl?: string }>(
        lookupUrl,
        { suppressNotFoundToast: true }
      );
      const d = res.data;
      contentMeta.value[key] = {
        thumbnailUrl: d.thumbnailUrl ?? ytVideoThumb,
        title: d.title ?? d.name ?? r.id,
      };
    } catch {
      // NewPipe extraction failed (rate-limited, deleted content, etc.).
      // Surface the YT thumbnail when we can so admins see *something*
      // instead of an opaque ID.
      contentMeta.value[key] = {
        thumbnailUrl: ytVideoThumb,
        title: r.id,
      };
    }
  }));
}

// Resolve dialog
const resolveDialog = ref({
  visible: false,
  report: null as ContentReport | null,
  action: 'RESOLVED' as 'RESOLVED' | 'REJECTED',
  note: '',
});

async function load() {
  isLoading.value = true;
  error.value = false;
  try {
    const data = await fetchReports({
      status: statusFilter.value !== 'ALL' ? statusFilter.value : undefined,
      targetType: targetTypeFilter.value !== 'ALL' ? targetTypeFilter.value : undefined,
      page: currentPage.value,
      size: PAGE_SIZE,
    });
    reports.value = data;
    hasNextPage.value = data.length === PAGE_SIZE;
    fetchContentMeta(data);
  } catch {
    error.value = true;
  } finally {
    isLoading.value = false;
  }
}

async function loadStats() {
  try {
    stats.value = await fetchReportStats();
  } catch {
    // Stats are non-critical; silently ignore
  }
}

function nextPage() {
  currentPage.value++;
  load();
}

function prevPage() {
  currentPage.value = Math.max(0, currentPage.value - 1);
  load();
}

function openResolveDialog(report: ContentReport, action: 'RESOLVED' | 'REJECTED') {
  resolveDialog.value = { visible: true, report, action, note: '' };
}

async function submitResolve() {
  const { report, action, note } = resolveDialog.value;
  if (!report) return;
  isSubmitting.value = true;
  actionLoadingId.value = report.id;
  try {
    const updated = await resolveReport(report.id, { status: action, note: note || undefined });
    const idx = reports.value.findIndex(r => r.id === updated.id);
    if (idx !== -1) reports.value[idx] = updated;
    resolveDialog.value.visible = false;
    await loadStats();
  } catch {
    // Error handled by apiClient global interceptor
  } finally {
    isSubmitting.value = false;
    actionLoadingId.value = null;
  }
}

onMounted(() => {
  load();
  loadStats();
});
</script>

<style scoped>
.reports-view {
  padding: 1.5rem;
  max-width: 1200px;
  margin: 0 auto;
}

.workspace-header {
  margin-bottom: 1.5rem;
}

.workspace-header h1 {
  font-size: 1.5rem;
  font-weight: 700;
  margin: 0 0 0.25rem;
}

.workspace-header p {
  color: var(--color-text-muted, #6b7280);
  margin: 0;
}

/* Stats bar */
.stats-bar {
  display: flex;
  gap: 1rem;
  margin-bottom: 1.5rem;
  flex-wrap: wrap;
}

.stat-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  background: var(--color-surface, #fff);
  border: 1px solid var(--color-border, #e5e7eb);
  border-radius: 0.5rem;
  padding: 0.75rem 1.25rem;
  min-width: 90px;
}

.stat-card.accent {
  border-color: var(--color-brand, #059669);
}

.stat-value {
  font-size: 1.5rem;
  font-weight: 700;
}

.stat-label {
  font-size: 0.75rem;
  color: var(--color-text-muted, #6b7280);
  white-space: nowrap;
}

/* Filters */
.filter-bar {
  display: flex;
  gap: 1.5rem;
  flex-wrap: wrap;
  margin-bottom: 1.25rem;
  align-items: flex-start;
}

.filter-group {
  display: flex;
  flex-direction: column;
  gap: 0.375rem;
}

.filter-label {
  font-size: 0.75rem;
  font-weight: 600;
  color: var(--color-text-muted, #6b7280);
  text-transform: uppercase;
  letter-spacing: 0.05em;
}

.filter-options {
  display: flex;
  gap: 0.375rem;
  flex-wrap: wrap;
}

.filter-option {
  padding: 0.3rem 0.75rem;
  border: 1px solid var(--color-border, #e5e7eb);
  border-radius: 1rem;
  background: transparent;
  cursor: pointer;
  font-size: 0.875rem;
  color: var(--color-text-primary, #132820);
  transition: background 0.15s, border-color 0.15s, color 0.15s;
}

.filter-option:hover {
  background: var(--color-surface-alt, #e8f1ec);
  color: var(--color-text-primary, #132820);
}

.filter-option.active {
  background: var(--color-brand, #059669);
  border-color: var(--color-brand, #059669);
  color: #fff;
}

/* Table */
.table-wrapper {
  overflow-x: auto;
}

.data-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 0.875rem;
}

.data-table th,
.data-table td {
  padding: 0.625rem 0.75rem;
  border-bottom: 1px solid var(--color-border, #e5e7eb);
  text-align: left;
  vertical-align: top;
}

.data-table th {
  font-weight: 600;
  color: var(--color-text-muted, #6b7280);
  background: var(--color-surface, #f9fafb);
  white-space: nowrap;
}

.type-badge {
  display: inline-block;
  padding: 0.15rem 0.5rem;
  border-radius: 0.25rem;
  font-size: 0.75rem;
  font-weight: 600;
  background: #e5e7eb;
}

.type-badge.video { background: #dbeafe; color: #1d4ed8; }
.type-badge.channel { background: #fef9c3; color: #854d0e; }
.type-badge.playlist { background: #d1fae5; color: #065f46; }

.content-cell { min-width: 180px; max-width: 260px; }

.content-info {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.content-thumb {
  width: 56px;
  height: 40px;
  object-fit: cover;
  border-radius: 4px;
  flex-shrink: 0;
  background: var(--color-surface-alt, #e8f1ec);
}

.content-thumb.thumb-circle {
  width: 40px;
  height: 40px;
  border-radius: 50%;
}

.content-thumb-placeholder {
  width: 56px;
  height: 40px;
  border-radius: 4px;
  flex-shrink: 0;
  background: var(--color-surface-alt, #e8f1ec);
  display: flex;
  align-items: center;
  justify-content: center;
}

.content-thumb-placeholder.channel {
  width: 40px;
  height: 40px;
  border-radius: 50%;
}

.content-details {
  display: flex;
  flex-direction: column;
  gap: 0.1rem;
  min-width: 0;
}

.content-title {
  font-size: 0.8rem;
  font-weight: 500;
  color: var(--color-text-primary, #132820);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 160px;
}

.content-id {
  font-family: monospace;
  font-size: 0.7rem;
  color: var(--color-text-secondary, #4f665c);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 160px;
}

.parent-context {
  font-size: 0.72rem;
  color: var(--color-text-secondary, #4f665c);
  margin-top: 0.15rem;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 220px;
}

.parent-context strong {
  color: var(--color-text-primary, #111827);
  font-weight: 600;
}

.subtype-badge {
  display: inline-block;
  padding: 0.05rem 0.45rem;
  margin-left: 0.35rem;
  border-radius: 9999px;
  font-size: 0.62rem;
  font-weight: 600;
  letter-spacing: 0.04em;
  text-transform: uppercase;
  background: #f3e8ff;
  color: #6d28d9;
}

.subtype-badge.short { background: #fce7f3; color: #be185d; }
.subtype-badge.livestream { background: #fee2e2; color: #b91c1c; }
.subtype-badge.post { background: #e0e7ff; color: #4338ca; }

.reason-list {
  margin: 0;
  padding: 0 0 0 1.1rem;
  list-style: disc;
}

.other-desc {
  font-style: italic;
  color: var(--color-text-muted, #6b7280);
}

.timestamp {
  white-space: nowrap;
  color: var(--color-text-muted, #6b7280);
}

.status-badge {
  display: inline-block;
  padding: 0.15rem 0.5rem;
  border-radius: 0.25rem;
  font-size: 0.75rem;
  font-weight: 600;
}

.status-badge.pending { background: #fef9c3; color: #854d0e; }
.status-badge.resolved { background: #d1fae5; color: #065f46; }
.status-badge.rejected { background: #fee2e2; color: #991b1b; }

.resolution-note {
  margin-top: 0.25rem;
  font-size: 0.75rem;
  color: var(--color-text-muted, #6b7280);
  max-width: 200px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.action-buttons {
  display: flex;
  gap: 0.375rem;
  flex-wrap: wrap;
}

.btn-resolve,
.btn-reject,
.btn-cancel,
.btn-confirm {
  padding: 0.3rem 0.75rem;
  border-radius: 0.375rem;
  font-size: 0.8rem;
  font-weight: 600;
  cursor: pointer;
  border: none;
  transition: opacity 0.15s;
}

.btn-preview { background: transparent; color: var(--color-brand, #059669); border: 1px solid var(--color-brand, #059669); }
.btn-preview:hover { background: var(--color-brand-soft, rgba(22,131,90,0.12)); color: var(--color-brand, #059669); }
.btn-resolve { background: #059669; color: #fff; }
.btn-reject { background: #dc2626; color: #fff; }
.btn-cancel { background: var(--color-surface-alt, #e8f1ec); color: var(--color-text-primary, #132820); border: 1px solid var(--color-border, #e5e7eb); }
.btn-confirm { min-width: 90px; }

.btn-resolve:disabled,
.btn-reject:disabled { opacity: 0.5; cursor: not-allowed; }

.resolved-by {
  font-size: 0.8rem;
  color: var(--color-text-muted, #6b7280);
}

.empty-state {
  text-align: center;
  padding: 2rem 0;
  color: var(--color-text-muted, #6b7280);
}

.skeleton-stack { display: flex; flex-direction: column; gap: 0.5rem; padding: 0.5rem 0; }
.skeleton-row { height: 2.5rem; background: var(--color-surface-hover, #f3f4f6); border-radius: 0.25rem; animation: pulse 1.4s ease-in-out infinite; }
@keyframes pulse { 0%, 100% { opacity: 1; } 50% { opacity: 0.4; } }

.error-state { text-align: center; padding: 2rem; }
.retry { margin-top: 0.75rem; padding: 0.4rem 1rem; background: var(--color-brand, #059669); color: #fff; border: none; border-radius: 0.375rem; cursor: pointer; }

/* Pagination */
.pagination {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 1rem;
  margin-top: 1.5rem;
}

.page-btn {
  padding: 0.4rem 0.9rem;
  border: 1px solid var(--color-border, #e5e7eb);
  border-radius: 0.375rem;
  background: transparent;
  cursor: pointer;
  font-size: 0.875rem;
}

.page-btn:disabled { opacity: 0.4; cursor: not-allowed; }

.page-indicator {
  font-size: 0.875rem;
  color: var(--color-text-muted, #6b7280);
}

/* Dialog */
.dialog-backdrop {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.4);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 100;
}

.dialog {
  background: var(--color-surface, #fff);
  border-radius: 0.75rem;
  padding: 1.5rem;
  width: 100%;
  max-width: 420px;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.15);
}

.dialog h2 {
  margin: 0 0 0.5rem;
  font-size: 1.1rem;
  font-weight: 700;
}

.dialog-sub {
  color: var(--color-text-muted, #6b7280);
  font-size: 0.875rem;
  margin: 0 0 0.75rem;
}

.note-input {
  width: 100%;
  border: 1px solid var(--color-border, #e5e7eb);
  border-radius: 0.375rem;
  padding: 0.5rem 0.75rem;
  font-size: 0.875rem;
  resize: vertical;
  font-family: inherit;
  box-sizing: border-box;
}

.dialog-actions {
  display: flex;
  gap: 0.75rem;
  justify-content: flex-end;
  margin-top: 1rem;
}
</style>
