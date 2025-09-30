<template>
  <section
    class="moderation-queue"
    :aria-hidden="rejectDialog.visible ? 'true' : undefined"
    :inert="rejectDialog.visible ? '' : undefined"
  >
    <header class="workspace-header">
      <div>
        <h1>{{ t('moderation.heading') }}</h1>
        <p>{{ t('moderation.description') }}</p>
      </div>
      <div class="filter-controls">
        <span class="filter-label" id="status-filter-label">{{ t('moderation.filters.label') }}</span>
        <div class="filter-options" role="radiogroup" aria-labelledby="status-filter-label">
          <button
            v-for="option in statusOptions"
            :key="option.value"
            type="button"
            class="filter-option"
            role="radio"
            :aria-checked="statusFilter === option.value"
            :class="{ active: statusFilter === option.value }"
            :tabindex="statusFilter === option.value ? 0 : -1"
            :data-status-option="option.value"
            @keydown="handleStatusKeydown($event, option.value)"
            @click="setStatusFilter(option.value)"
          >
            {{ option.label }}
          </button>
        </div>
      </div>
    </header>

    <div v-if="actionError" class="action-error" role="alert">
      {{ actionError }}
    </div>

    <div class="table-wrapper" role="region" aria-live="polite">
      <div v-if="error" class="error-state">
        <p>{{ t('moderation.table.error') }}</p>
        <button type="button" class="retry" @click="handleRetry" :disabled="isLoading">
          {{ t('registry.table.retry') }}
        </button>
      </div>

      <table v-else class="data-table">
        <thead>
          <tr>
            <th scope="col">{{ t('moderation.table.columns.kind') }}</th>
            <th scope="col">{{ t('moderation.table.columns.resource') }}</th>
            <th scope="col">{{ t('moderation.table.columns.categories') }}</th>
            <th scope="col">{{ t('moderation.table.columns.proposer') }}</th>
            <th scope="col">{{ t('moderation.table.columns.submitted') }}</th>
            <th scope="col">{{ t('moderation.table.columns.notes') }}</th>
            <th scope="col">{{ t('moderation.table.columns.status') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="isLoading && !items.length">
            <td :colspan="7">
              <div class="skeleton-stack" aria-hidden="true">
                <div v-for="index in 5" :key="index" class="skeleton-row"></div>
              </div>
            </td>
          </tr>
          <tr v-else-if="!items.length">
            <td :colspan="7" class="empty-state">{{ t('moderation.table.empty') }}</td>
          </tr>
          <tr v-for="proposal in items" :key="proposal.id">
            <td class="kind-cell">
              <span class="kind-badge">{{ kindLabel(proposal.kind) }}</span>
            </td>
            <td class="resource-cell">
              <span class="resource-id">{{ proposal.ytId }}</span>
            </td>
            <td>
              <ul class="category-tags">
                <li v-for="tag in proposal.suggestedCategories" :key="tag.id" class="category-tag">
                  {{ tag.label }}
                </li>
              </ul>
            </td>
            <td>
              <div class="user-meta">
                <span class="user-name">{{ proposal.proposer.displayName || proposal.proposer.email }}</span>
                <span class="user-email">{{ proposal.proposer.email }}</span>
              </div>
            </td>
            <td>{{ formatSubmittedAt(proposal.createdAt) }}</td>
            <td class="notes-cell">
              <span v-if="proposal.notes">{{ proposal.notes }}</span>
              <span v-else class="notes-placeholder">{{ t('moderation.notesPlaceholder') }}</span>
            </td>
            <td>
              <div class="status-cell">
                <span class="status-badge" :class="statusBadgeClass(proposal.status)">
                  {{ statusLabel(proposal.status) }}
                </span>
                <div v-if="proposal.status === 'PENDING'" class="action-buttons">
                  <button
                    type="button"
                    class="approve"
                    @click="handleApprove(proposal)"
                    :disabled="isActionLoading(proposal.id) || isLoading"
                  >
                    {{ isActionLoading(proposal.id) && pendingAction === 'approve'
                      ? t('moderation.actions.approving')
                      : t('moderation.actions.approve') }}
                  </button>
                  <button
                    type="button"
                    class="reject"
                    :data-focus-return="`reject-${proposal.id}`"
                    @click="openRejectDialog(proposal, $event)"
                    :disabled="isActionLoading(proposal.id) || isLoading"
                  >
                    {{ isActionLoading(proposal.id) && pendingAction === 'reject'
                      ? t('moderation.actions.rejecting')
                      : t('moderation.actions.reject') }}
                  </button>
                </div>
                <div v-else class="status-meta">
                  <span v-for="(line, index) in decisionMeta(proposal)" :key="index">{{ line }}</span>
                  <span v-if="proposal.decisionReason" class="status-reason">
                    {{ t('moderation.decision.reason', { reason: proposal.decisionReason }) }}
                  </span>
                </div>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <footer class="table-footer">
      <button type="button" class="pager" @click="handlePrevious" :disabled="!hasPrevious || isLoading">
        {{ t('registry.pagination.previous') }}
      </button>
      <div class="footer-status">
        <span v-if="isLoading">{{ t('moderation.table.loading') }}</span>
        <span v-else>{{ paginationSummary }}</span>
      </div>
      <button type="button" class="pager" @click="handleNext" :disabled="!hasNext || isLoading">
        {{ t('registry.pagination.next') }}
      </button>
    </footer>
  </section>

  <div v-if="rejectDialog.visible" class="modal-backdrop">
    <div
      ref="rejectDialogRef"
      class="modal"
      role="dialog"
      aria-modal="true"
      :aria-labelledby="rejectDialogTitleId"
      :aria-describedby="rejectDialogDescriptionId"
      tabindex="-1"
      @keydown="handleModalKeydown"
    >
      <h2 :id="rejectDialogTitleId">{{ t('moderation.actions.confirmReject') }}</h2>
      <p :id="rejectDialogDescriptionId" class="modal-description">
        {{ t('moderation.actions.confirmRejectDescription') }}
      </p>
      <form @submit.prevent="confirmReject">
        <label class="modal-label" :for="rejectDialogTextareaId">
          {{ t('moderation.actions.reasonLabel') }}
        </label>
        <textarea
          :id="rejectDialogTextareaId"
          ref="rejectTextareaRef"
          v-model="rejectDialog.reason"
          rows="4"
          class="modal-textarea"
          :disabled="isRejectSubmitting"
        ></textarea>
        <div class="modal-actions">
          <button type="button" class="modal-secondary" @click="closeRejectDialog" :disabled="isRejectSubmitting">
            {{ t('moderation.actions.cancel') }}
          </button>
          <button type="submit" class="modal-primary" :disabled="isRejectSubmitting">
            {{ isRejectSubmitting ? t('moderation.actions.rejecting') : t('moderation.actions.submitReject') }}
          </button>
        </div>
      </form>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { useCursorPagination } from '@/composables/useCursorPagination';
import {
  approveModerationProposal,
  fetchModerationProposals,
  rejectModerationProposal
} from '@/services/moderation';
import type { ModerationProposal, ModerationProposalStatus } from '@/types/moderation';
import { formatDateTime } from '@/utils/formatters';
import { emitAuditEvent } from '@/services/audit';

const { t, locale } = useI18n();
const currentLocale = computed(() => locale.value);

type StatusFilter = ModerationProposalStatus | 'ALL';

const statusFilter = ref<StatusFilter>('PENDING');
const statusOptions = computed(() => [
  { value: 'PENDING' as StatusFilter, label: t('moderation.filters.pending') },
  { value: 'APPROVED' as StatusFilter, label: t('moderation.filters.approved') },
  { value: 'REJECTED' as StatusFilter, label: t('moderation.filters.rejected') },
  { value: 'ALL' as StatusFilter, label: t('moderation.filters.all') }
]);

const statusOptionValues = computed(() => statusOptions.value.map(option => option.value));

const pagination = useCursorPagination<ModerationProposal>(async (cursor, limit) => {
  const status = statusFilter.value === 'ALL' ? undefined : statusFilter.value;
  return fetchModerationProposals({ cursor, limit, status });
});

const { items, isLoading, error, load, next, previous, hasNext, hasPrevious, pageInfo } = pagination;

const actionError = ref<string | null>(null);
const actionLoadingId = ref<string | null>(null);
const pendingAction = ref<'approve' | 'reject' | null>(null);

const rejectDialog = reactive({
  visible: false,
  proposal: null as ModerationProposal | null,
  reason: ''
});

const rejectTextareaRef = ref<HTMLTextAreaElement | null>(null);
const rejectDialogRef = ref<HTMLDivElement | null>(null);
const rejectDialogTitleId = 'reject-dialog-title';
const rejectDialogTextareaId = 'reject-dialog-textarea';
const rejectDialogDescriptionId = 'reject-dialog-description';
const lastFocusedElement = ref<HTMLElement | null>(null);
interface FocusRestoreTarget {
  element: HTMLElement | null;
  fallbackId: string | null;
}
const deferredFocusTarget = ref<FocusRestoreTarget | null>(null);

const paginationSummary = computed(() => {
  if (!pageInfo.value) {
    return '';
  }
  const count = new Intl.NumberFormat(currentLocale.value).format(items.value.length);
  const pageLimit = pageInfo.value.limit ?? items.value.length;
  return t('registry.pagination.showing', {
    count,
    limit: new Intl.NumberFormat(currentLocale.value).format(pageLimit)
  });
});

const isRejectSubmitting = computed(
  () => actionLoadingId.value !== null && pendingAction.value === 'reject'
);

onMounted(() => {
  load(null, 'reset');
});

watch(statusFilter, async () => {
  actionError.value = null;
  closeRejectDialog();
  await load(null, 'reset');
});

function setStatusFilter(nextStatus: StatusFilter) {
  statusFilter.value = nextStatus;
}

function focusStatusOption(value: StatusFilter) {
  nextTick(() => {
    const button = document.querySelector<HTMLButtonElement>(
      `button[data-status-option="${value}"]`
    );
    button?.focus();
  });
}

function handleStatusKeydown(event: KeyboardEvent, value: StatusFilter) {
  const options = statusOptionValues.value;
  const currentIndex = options.indexOf(value);
  if (currentIndex === -1) {
    return;
  }

  if (event.key === 'ArrowRight' || event.key === 'ArrowDown') {
    event.preventDefault();
    const nextValue = options[(currentIndex + 1) % options.length];
    statusFilter.value = nextValue;
    focusStatusOption(nextValue);
  } else if (event.key === 'ArrowLeft' || event.key === 'ArrowUp') {
    event.preventDefault();
    const nextValue = options[(currentIndex - 1 + options.length) % options.length];
    statusFilter.value = nextValue;
    focusStatusOption(nextValue);
  } else if (event.key === 'Home') {
    event.preventDefault();
    const nextValue = options[0];
    statusFilter.value = nextValue;
    focusStatusOption(nextValue);
  } else if (event.key === 'End') {
    event.preventDefault();
    const nextValue = options[options.length - 1];
    statusFilter.value = nextValue;
    focusStatusOption(nextValue);
  }
}

function kindLabel(kind: ModerationProposal['kind']) {
  return t(`moderation.kind.${kind}`);
}

function statusLabel(status: ModerationProposalStatus) {
  const key = status.toLowerCase() as 'pending' | 'approved' | 'rejected';
  return t(`moderation.status.${key}`);
}

function statusBadgeClass(status: ModerationProposalStatus) {
  return `status-${status.toLowerCase()}`;
}

function formatSubmittedAt(value: string) {
  return formatDateTime(value, currentLocale.value);
}

function decisionMeta(proposal: ModerationProposal) {
  if (proposal.status === 'PENDING') {
    return [] as string[];
  }
  const lines: string[] = [];
  if (proposal.decidedBy) {
    const name = proposal.decidedBy.displayName || proposal.decidedBy.email;
    const key = proposal.status === 'APPROVED' ? 'approvedBy' : 'rejectedBy';
    lines.push(t(`moderation.decision.${key}`, { name }));
  }
  if (proposal.decidedAt) {
    lines.push(t('moderation.decision.decidedOn', {
      date: formatDateTime(proposal.decidedAt, currentLocale.value)
    }));
  }
  return lines;
}

function isActionLoading(id: string) {
  return actionLoadingId.value === id;
}

async function reloadCurrentPage() {
  const cursor = pageInfo.value?.cursor ?? null;
  await load(cursor, 'replace');
}

async function handleApprove(proposal: ModerationProposal) {
  actionError.value = null;
  actionLoadingId.value = proposal.id;
  pendingAction.value = 'approve';
  try {
    await approveModerationProposal(proposal.id);
    emitAuditEvent({
      name: 'moderation:approve',
      proposalId: proposal.id,
      timestamp: new Date().toISOString()
    });
    await reloadCurrentPage();
  } catch (err) {
    actionError.value = err instanceof Error ? err.message : t('moderation.errors.actionFailed');
  } finally {
    actionLoadingId.value = null;
    pendingAction.value = null;
  }
}

function openRejectDialog(proposal: ModerationProposal, event?: Event) {
  actionError.value = null;
  const trigger = event?.currentTarget as HTMLElement | null;
  lastFocusedElement.value = trigger ?? (document.activeElement as HTMLElement) ?? null;
  rejectDialog.visible = true;
  rejectDialog.proposal = proposal;
  rejectDialog.reason = '';
  pendingAction.value = null;
  nextTick(() => {
    rejectDialogRef.value?.focus();
    rejectTextareaRef.value?.focus();
  });
}

function closeRejectDialog(options: { deferFocus?: boolean } = {}) {
  const target: FocusRestoreTarget = {
    element: lastFocusedElement.value,
    fallbackId: lastFocusedElement.value?.getAttribute('data-focus-return') ?? null
  };
  rejectDialog.visible = false;
  rejectDialog.proposal = null;
  rejectDialog.reason = '';
  nextTick(() => {
    scheduleFocusRestore(target, options.deferFocus ?? false);
    lastFocusedElement.value = null;
  });
}

async function confirmReject() {
  if (!rejectDialog.proposal) {
    return;
  }
  actionError.value = null;
  actionLoadingId.value = rejectDialog.proposal.id;
  pendingAction.value = 'reject';
  try {
    const trimmedReason = rejectDialog.reason.trim();
    await rejectModerationProposal(rejectDialog.proposal.id, trimmedReason);
    emitAuditEvent({
      name: 'moderation:reject',
      proposalId: rejectDialog.proposal.id,
      timestamp: new Date().toISOString(),
      metadata: trimmedReason ? { reason: trimmedReason } : undefined
    });
    closeRejectDialog({ deferFocus: true });
    await reloadCurrentPage();
  } catch (err) {
    actionError.value = err instanceof Error ? err.message : t('moderation.errors.actionFailed');
  } finally {
    actionLoadingId.value = null;
    pendingAction.value = null;
    restoreDeferredFocus();
  }
}

async function handleNext() {
  await next();
}

async function handlePrevious() {
  await previous();
}

async function handleRetry() {
  await load(pageInfo.value?.cursor ?? null, 'replace');
}

function scheduleFocusRestore(target: FocusRestoreTarget, defer: boolean) {
  if (defer) {
    deferredFocusTarget.value = target;
    return;
  }
  nextTick(() => {
    restoreFocusTarget(target);
  });
}

function restoreDeferredFocus() {
  if (!deferredFocusTarget.value) {
    return;
  }
  const target = deferredFocusTarget.value;
  deferredFocusTarget.value = null;
  nextTick(() => {
    restoreFocusTarget(target);
  });
}

function restoreFocusTarget(target: FocusRestoreTarget | null) {
  if (!target) {
    return;
  }
  const { element, fallbackId } = target;
  if (element && document.contains(element) && !element.hasAttribute('disabled')) {
    element.focus();
    return;
  }
  if (fallbackId) {
    const fallback = document.querySelector<HTMLElement>(`[data-focus-return="${fallbackId}"]`);
    fallback?.focus();
  }
}

function getModalFocusableElements() {
  if (!rejectDialogRef.value) {
    return [] as HTMLElement[];
  }
  const selector =
    'a[href], button:not([disabled]), textarea:not([disabled]), input:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])';
  return Array.from(rejectDialogRef.value.querySelectorAll<HTMLElement>(selector));
}

function handleModalKeydown(event: KeyboardEvent) {
  if (!rejectDialog.visible) {
    return;
  }

  if (event.key === 'Escape') {
    event.preventDefault();
    if (!isRejectSubmitting.value) {
      closeRejectDialog();
    }
    return;
  }

  if (event.key !== 'Tab') {
    return;
  }

  const focusable = getModalFocusableElements();
  if (focusable.length === 0) {
    event.preventDefault();
    return;
  }

  const first = focusable[0];
  const last = focusable[focusable.length - 1];
  const active = document.activeElement as HTMLElement | null;

  if (event.shiftKey) {
    if (!active || active === first) {
      event.preventDefault();
      last.focus();
    }
    return;
  }

  if (!active || active === last) {
    event.preventDefault();
    first.focus();
  }
}
</script>

<style scoped>
.moderation-queue {
  display: flex;
  flex-direction: column;
  gap: 1.5rem;
}

.workspace-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  background: var(--color-surface);
  border-radius: 1rem;
  padding: 1.75rem 2rem;
  box-shadow: var(--shadow-elevated);
  gap: 1rem;
  flex-wrap: wrap;
}

.workspace-header h1 {
  margin: 0;
  font-size: 1.5rem;
  font-weight: 700;
  color: var(--color-text-primary);
}

.workspace-header p {
  margin: 0.5rem 0 0;
  color: var(--color-text-secondary);
  font-size: 1rem;
  max-width: 520px;
}

.filter-controls {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.filter-label {
  font-weight: 600;
  color: var(--color-text-primary);
}

.filter-options {
  display: inline-flex;
  gap: 0.5rem;
  background: var(--color-surface-alt);
  padding: 0.4rem;
  border-radius: 999px;
}

.filter-option {
  border: none;
  background: transparent;
  padding: 0.4rem 1.2rem;
  border-radius: 999px;
  font-weight: 600;
  color: var(--color-text-secondary);
  cursor: pointer;
  transition: background 0.2s ease, color 0.2s ease;
}

.filter-option.active {
  background: var(--color-brand);
  color: var(--color-text-inverse);
  box-shadow: 0 10px 25px -18px var(--color-overlay);
}

.action-error {
  background: var(--color-danger-soft);
  color: var(--color-danger);
  border-radius: 0.75rem;
  padding: 0.75rem 1rem;
}

.table-wrapper {
  background: var(--color-surface);
  border-radius: 1rem;
  box-shadow: var(--shadow-elevated);
  overflow: hidden;
}

.data-table {
  width: 100%;
  border-collapse: collapse;
  min-width: 1080px;
}

th {
  position: sticky;
  top: 0;
  background: var(--color-surface-alt);
  color: var(--color-text-primary);
  text-align: left;
  padding: 0.75rem 1.25rem;
  font-size: 0.9rem;
  font-weight: 600;
  border-bottom: 1px solid var(--color-border);
  z-index: 1;
}

td {
  padding: 0.75rem 1.25rem;
  border-bottom: 1px solid var(--color-border);
  vertical-align: top;
  font-size: 0.95rem;
  color: var(--color-text-primary);
}

.kind-cell {
  width: 120px;
}

.kind-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: var(--color-surface-alt);
  color: var(--color-text-primary);
  border-radius: 999px;
  padding: 0.25rem 0.9rem;
  font-weight: 600;
  font-size: 0.85rem;
}

.resource-cell {
  font-family: 'IBM Plex Mono', Menlo, Monaco, Consolas, 'Liberation Mono', 'Courier New', monospace;
  color: var(--color-text-secondary);
}

.resource-id {
  word-break: break-all;
}

.category-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
  list-style: none;
  padding: 0;
  margin: 0;
}

.category-tag {
  background: var(--color-surface-alt);
  color: var(--color-text-primary);
  padding: 0.25rem 0.75rem;
  border-radius: 999px;
  font-size: 0.8rem;
  font-weight: 500;
}

.user-meta {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}

.user-name {
  font-weight: 600;
  color: var(--color-text-primary);
}

.user-email {
  color: var(--color-text-secondary);
  font-size: 0.85rem;
}

.notes-cell {
  max-width: 260px;
}

.notes-cell span {
  display: block;
  white-space: pre-wrap;
}

.notes-placeholder {
  color: var(--color-text-secondary);
  opacity: 0.75;
  font-style: italic;
}

.status-cell {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.status-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 999px;
  padding: 0.25rem 0.75rem;
  font-weight: 600;
  font-size: 0.85rem;
  width: fit-content;
}

.status-pending {
  background: var(--color-warning-soft);
  color: var(--color-warning);
}

.status-approved {
  background: var(--color-success-soft);
  color: var(--color-success);
}

.status-rejected {
  background: var(--color-danger-soft);
  color: var(--color-danger);
}

.action-buttons {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
}

.approve,
.reject {
  border: none;
  border-radius: 0.75rem;
  padding: 0.45rem 1.25rem;
  cursor: pointer;
  font-weight: 600;
  transition: background 0.2s ease, color 0.2s ease;
}

.approve {
  background: var(--color-brand);
  color: var(--color-text-inverse);
}

.approve:disabled {
  background: var(--color-disabled);
  cursor: not-allowed;
}

.approve:not(:disabled):hover {
  background: var(--color-accent);
}

.reject {
  background: var(--color-danger-soft);
  color: var(--color-danger);
}

.reject:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.reject:not(:disabled):hover {
  background: var(--color-danger);
  color: var(--color-text-inverse);
}

.status-meta {
  display: flex;
  flex-direction: column;
  gap: 0.35rem;
  color: var(--color-text-secondary);
  font-size: 0.85rem;
}

.status-reason {
  color: var(--color-danger);
}

.skeleton-stack {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.skeleton-row {
  height: 16px;
  border-radius: 999px;
  background: linear-gradient(
    90deg,
    var(--color-surface-alt) 25%,
    var(--color-border) 50%,
    var(--color-surface-alt) 75%
  );
  animation: shimmer 1.6s infinite;
}

@keyframes shimmer {
  0% {
    background-position: -200px 0;
  }
  100% {
    background-position: 200px 0;
  }
}

.empty-state {
  text-align: center;
  color: var(--color-text-secondary);
  padding: 2rem 0;
}

.error-state {
  padding: 2rem;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 0.75rem;
  color: var(--color-danger);
}

.retry {
  background: var(--color-brand);
  color: var(--color-text-inverse);
  border: none;
  border-radius: 0.5rem;
  padding: 0.5rem 1rem;
  cursor: pointer;
}

.retry:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.table-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 1rem 1.75rem 1.5rem;
  gap: 1rem;
}

.footer-status {
  color: var(--color-text-secondary);
  font-size: 0.9rem;
}

.pager {
  background: var(--color-brand);
  color: var(--color-text-inverse);
  border: none;
  padding: 0.5rem 1.25rem;
  border-radius: 0.75rem;
  cursor: pointer;
  font-weight: 600;
  transition: background 0.2s ease;
}

.pager:disabled {
  background: var(--color-disabled);
  cursor: not-allowed;
}

.pager:not(:disabled):hover {
  background: var(--color-accent);
}

.modal-backdrop {
  position: fixed;
  inset: 0;
  background: var(--color-overlay);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 1.5rem;
  z-index: 20;
}

.modal {
  background: var(--color-surface);
  border-radius: 1rem;
  padding: 1.75rem;
  max-width: 520px;
  width: 100%;
  box-shadow: var(--shadow-elevated);
}

.modal-description {
  color: var(--color-text-secondary);
  margin: 0.5rem 0 1.5rem;
}

.modal-label {
  display: block;
  font-weight: 600;
  color: var(--color-text-primary);
  margin-bottom: 0.5rem;
}

.modal-textarea {
  width: 100%;
  border-radius: 0.75rem;
  border: 1px solid var(--color-border);
  padding: 0.75rem;
  resize: vertical;
  font-family: inherit;
  min-height: 120px;
}

.modal-textarea:disabled {
  background: var(--color-surface-alt);
}

.modal-actions {
  display: flex;
  justify-content: flex-end;
  gap: 0.75rem;
  margin-top: 1.5rem;
}

.modal-secondary,
.modal-primary {
  border: none;
  border-radius: 0.75rem;
  padding: 0.55rem 1.5rem;
  font-weight: 600;
  cursor: pointer;
}

.modal-secondary {
  background: var(--color-surface-alt);
  color: var(--color-text-primary);
}

.modal-secondary:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.modal-primary {
  background: var(--color-brand);
  color: var(--color-text-inverse);
}

.modal-primary:disabled {
  background: var(--color-disabled);
  cursor: not-allowed;
}

.modal-primary:not(:disabled):hover {
  background: var(--color-accent);
}

@media (max-width: 960px) {
  .workspace-header {
    flex-direction: column;
    align-items: stretch;
  }

  .filter-controls {
    align-self: flex-start;
  }

  .data-table {
    min-width: 960px;
  }
}
</style>
