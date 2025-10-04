<template>
  <section class="activity-view">
    <header class="activity-header">
      <div>
        <h1>{{ t('activity.heading') }}</h1>
        <p>{{ t('activity.description') }}</p>
      </div>
      <div class="header-actions">
        <div class="view-toggle">
          <button
            type="button"
            class="toggle-btn"
            :class="{ active: viewMode === 'table' }"
            @click="viewMode = 'table'"
          >
            <span class="toggle-icon">📋</span>
            <span>{{ t('activity.viewMode.table') }}</span>
          </button>
          <button
            type="button"
            class="toggle-btn"
            :class="{ active: viewMode === 'timeline' }"
            @click="viewMode = 'timeline'"
          >
            <span class="toggle-icon">📅</span>
            <span>{{ t('activity.viewMode.timeline') }}</span>
          </button>
        </div>
        <button type="button" class="btn-export" @click="exportLog" :disabled="!entries.length">
          <span class="export-icon">⬇️</span>
          <span>{{ t('activity.export') }}</span>
        </button>
      </div>
    </header>

    <div class="filters-panel">
      <div class="filter-group">
        <label for="actor-filter">{{ t('activity.filters.actor') }}</label>
        <input
          id="actor-filter"
          v-model="actorFilter"
          type="search"
          :placeholder="t('activity.filters.actorPlaceholder')"
          @input="scheduleReload"
        />
      </div>

      <div class="filter-group">
        <label for="action-filter">{{ t('activity.filters.action') }}</label>
        <select id="action-filter" v-model="actionFilter" @change="reload">
          <option value="">{{ t('activity.filters.allActions') }}</option>
          <option value="CREATE">{{ t('activity.actions.create') }}</option>
          <option value="UPDATE">{{ t('activity.actions.update') }}</option>
          <option value="DELETE">{{ t('activity.actions.delete') }}</option>
          <option value="APPROVE">{{ t('activity.actions.approve') }}</option>
          <option value="REJECT">{{ t('activity.actions.reject') }}</option>
          <option value="LOGIN">{{ t('activity.actions.login') }}</option>
          <option value="LOGOUT">{{ t('activity.actions.logout') }}</option>
        </select>
      </div>

      <div class="filter-group">
        <label for="entity-filter">{{ t('activity.filters.entity') }}</label>
        <select id="entity-filter" v-model="entityFilter" @change="reload">
          <option value="">{{ t('activity.filters.allEntities') }}</option>
          <option value="CHANNEL">{{ t('activity.entities.channel') }}</option>
          <option value="PLAYLIST">{{ t('activity.entities.playlist') }}</option>
          <option value="VIDEO">{{ t('activity.entities.video') }}</option>
          <option value="CATEGORY">{{ t('activity.entities.category') }}</option>
          <option value="USER">{{ t('activity.entities.user') }}</option>
        </select>
      </div>

      <div class="filter-group">
        <label for="date-range">{{ t('activity.filters.dateRange') }}</label>
        <select id="date-range" v-model="dateRange" @change="reload">
          <option value="today">{{ t('activity.filters.today') }}</option>
          <option value="week">{{ t('activity.filters.lastWeek') }}</option>
          <option value="month">{{ t('activity.filters.lastMonth') }}</option>
          <option value="all">{{ t('activity.filters.allTime') }}</option>
        </select>
      </div>

      <button v-if="hasActiveFilters" type="button" class="btn-clear-filters" @click="clearFilters">
        {{ t('activity.filters.clear') }}
      </button>
    </div>

    <div v-if="errorMessage" class="error-panel" role="alert">
      <p>{{ errorMessage }}</p>
      <button type="button" @click="reload">{{ t('activity.retry') }}</button>
    </div>

    <!-- Table View -->
    <div v-if="viewMode === 'table'" class="table-view">
      <div class="table-wrapper">
        <table class="activity-table">
          <thead>
            <tr>
              <th>{{ t('activity.columns.timestamp') }}</th>
              <th>{{ t('activity.columns.actor') }}</th>
              <th>{{ t('activity.columns.action') }}</th>
              <th>{{ t('activity.columns.entity') }}</th>
              <th>{{ t('activity.columns.details') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="isLoading && !entries.length">
              <td colspan="5">
                <div class="loading-skeleton">
                  <div v-for="i in 5" :key="i" class="skeleton-row"></div>
                </div>
              </td>
            </tr>
            <tr v-else-if="!entries.length">
              <td colspan="5" class="empty-state">{{ t('activity.empty') }}</td>
            </tr>
            <tr v-for="entry in entries" :key="entry.id">
              <td class="timestamp-cell">
                <div class="timestamp">{{ formatTime(entry.createdAt) }}</div>
                <div class="date">{{ formatDate(entry.createdAt) }}</div>
              </td>
              <td>
                <div class="actor-info">
                  <div class="actor-email">{{ entry.actor.email }}</div>
                  <div class="actor-roles">{{ formatRoles(entry.actor.roles) }}</div>
                </div>
              </td>
              <td>
                <span class="action-badge" :class="`action-${entry.action.toLowerCase()}`">
                  {{ entry.action }}
                </span>
              </td>
              <td>
                <div class="entity-info">
                  <div class="entity-type">{{ entry.entity.type }}</div>
                  <div class="entity-id">{{ entry.entity.id }}</div>
                </div>
              </td>
              <td>
                <code v-if="entry.metadata" class="metadata">{{ formatMetadata(entry.metadata) }}</code>
                <span v-else class="no-metadata">—</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- Timeline View -->
    <div v-else class="timeline-view">
      <div v-if="isLoading && !entries.length" class="loading-skeleton">
        <div v-for="i in 3" :key="i" class="skeleton-timeline-item"></div>
      </div>
      <div v-else-if="!entries.length" class="empty-state">
        <p>{{ t('activity.empty') }}</p>
      </div>
      <div v-else class="timeline">
        <div v-for="(group, date) in groupedByDate" :key="date" class="timeline-day">
          <div class="timeline-date">
            <div class="date-marker">{{ formatDateHeader(date) }}</div>
          </div>
          <div class="timeline-entries">
            <div v-for="entry in group" :key="entry.id" class="timeline-entry">
              <div class="timeline-dot" :class="`action-${entry.action.toLowerCase()}`"></div>
              <div class="timeline-card">
                <div class="timeline-header">
                  <span class="timeline-time">{{ formatTime(entry.createdAt) }}</span>
                  <span class="action-badge" :class="`action-${entry.action.toLowerCase()}`">
                    {{ entry.action }}
                  </span>
                </div>
                <div class="timeline-body">
                  <div class="timeline-actor">
                    <strong>{{ entry.actor.email }}</strong>
                    <span v-if="entry.actor.roles.length" class="actor-roles">
                      ({{ formatRoles(entry.actor.roles) }})
                    </span>
                  </div>
                  <div class="timeline-entity">
                    {{ entry.action }} {{ entry.entity.type.toLowerCase() }}
                    <code class="entity-id-inline">{{ entry.entity.id }}</code>
                  </div>
                  <div v-if="entry.metadata" class="timeline-metadata">
                    <details>
                      <summary>{{ t('activity.showDetails') }}</summary>
                      <code>{{ formatMetadata(entry.metadata) }}</code>
                    </details>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- Pagination -->
    <footer class="pagination-footer">
      <button type="button" class="pager-btn" :disabled="!hasPrevious || isLoading" @click="previous">
        {{ t('activity.pagination.previous') }}
      </button>
      <div class="page-info">
        <span v-if="isLoading">{{ t('activity.loading') }}</span>
        <span v-else>{{ paginationSummary }}</span>
      </div>
      <button type="button" class="pager-btn" :disabled="!hasNext || isLoading" @click="next">
        {{ t('activity.pagination.next') }}
      </button>
    </footer>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { useCursorPagination } from '@/composables/useCursorPagination';
import { fetchAuditLogPage } from '@/services/adminAudit';
import type { AuditEntry } from '@/types/admin';
import { formatDateTime as baseFormatDateTime } from '@/utils/formatters';

const { t, locale } = useI18n();
const currentLocale = computed(() => locale.value);

const viewMode = ref<'table' | 'timeline'>('timeline');
const actorFilter = ref('');
const actionFilter = ref('');
const entityFilter = ref('');
const dateRange = ref('week');
const errorMessage = ref<string | null>(null);

const hasActiveFilters = computed(() => {
  return actorFilter.value || actionFilter.value || entityFilter.value || dateRange.value !== 'week';
});

const pagination = useCursorPagination<AuditEntry>(async (cursor, limit) => {
  return fetchAuditLogPage({
    cursor,
    limit,
    actorId: actorFilter.value.trim() || undefined,
    action: actionFilter.value || undefined
  });
});

const { items, isLoading, error, load, next, previous, hasNext, hasPrevious, pageInfo } = pagination;
const entries = items;

let reloadTimeout: ReturnType<typeof setTimeout> | null = null;

function scheduleReload() {
  if (reloadTimeout) {
    clearTimeout(reloadTimeout);
  }
  reloadTimeout = setTimeout(() => {
    void load(null, 'reset');
  }, 300);
}

async function reload() {
  errorMessage.value = null;
  await load(null, 'reset');
}

function clearFilters() {
  actorFilter.value = '';
  actionFilter.value = '';
  entityFilter.value = '';
  dateRange.value = 'week';
  reload();
}

// Group entries by date for timeline view
const groupedByDate = computed(() => {
  const groups: Record<string, AuditEntry[]> = {};

  entries.value.forEach((entry) => {
    const date = new Date(entry.createdAt).toISOString().split('T')[0];
    if (!groups[date]) {
      groups[date] = [];
    }
    groups[date].push(entry);
  });

  return groups;
});

function formatTime(timestamp: string): string {
  const date = new Date(timestamp);
  return date.toLocaleTimeString(currentLocale.value, {
    hour: '2-digit',
    minute: '2-digit'
  });
}

function formatDate(timestamp: string): string {
  const date = new Date(timestamp);
  return date.toLocaleDateString(currentLocale.value, {
    month: 'short',
    day: 'numeric'
  });
}

function formatDateHeader(dateStr: string): string {
  const date = new Date(dateStr);
  const today = new Date();
  const yesterday = new Date(today);
  yesterday.setDate(yesterday.getDate() - 1);

  if (date.toDateString() === today.toDateString()) {
    return t('activity.dateLabels.today');
  } else if (date.toDateString() === yesterday.toDateString()) {
    return t('activity.dateLabels.yesterday');
  } else {
    return date.toLocaleDateString(currentLocale.value, {
      weekday: 'long',
      year: 'numeric',
      month: 'long',
      day: 'numeric'
    });
  }
}

function formatRoles(roles: string[]): string {
  if (!roles || roles.length === 0) {
    return t('activity.roles.none');
  }
  return roles.map(r => r.charAt(0) + r.slice(1).toLowerCase()).join(', ');
}

function formatMetadata(metadata: any): string {
  if (!metadata) {
    return t('activity.metadata.unavailable');
  }
  return JSON.stringify(metadata, null, 2);
}

const paginationSummary = computed(() => {
  const count = entries.value.length;
  const limit = pageInfo.value?.limit || 20;
  return t('activity.pagination.showing', { count, limit });
});

function exportLog() {
  const csv = [
    ['Timestamp', 'Actor', 'Roles', 'Action', 'Entity Type', 'Entity ID', 'Metadata'].join(','),
    ...entries.value.map(entry => [
      new Date(entry.createdAt).toISOString(),
      entry.actor.email,
      formatRoles(entry.actor.roles),
      entry.action,
      entry.entity.type,
      entry.entity.id,
      entry.metadata ? JSON.stringify(entry.metadata) : ''
    ].map(cell => `"${String(cell).replace(/"/g, '""')}"`).join(','))
  ].join('\n');

  const blob = new Blob([csv], { type: 'text/csv' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = `activity-log-${new Date().toISOString().split('T')[0]}.csv`;
  a.click();
  URL.revokeObjectURL(url);
}

onMounted(async () => {
  await load(null, 'reset');
});

watch(error, (value) => {
  errorMessage.value = value;
});

onBeforeUnmount(() => {
  if (reloadTimeout) {
    clearTimeout(reloadTimeout);
  }
});
</script>

<style scoped>
.activity-view {
  display: flex;
  flex-direction: column;
  gap: 2rem;
}

.activity-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 1.5rem;
  flex-wrap: wrap;
}

.activity-header h1 {
  margin: 0;
  font-size: 2rem;
  font-weight: 700;
  color: var(--color-text-primary);
  letter-spacing: -0.02em;
}

.activity-header p {
  margin: 0.75rem 0 0;
  color: var(--color-text-secondary);
  font-size: 0.9375rem;
}

.header-actions {
  display: flex;
  gap: 1rem;
  align-items: center;
  flex-wrap: wrap;
}

.view-toggle {
  display: flex;
  gap: 0.5rem;
  background: var(--color-surface);
  border: 1.5px solid var(--color-border);
  border-radius: 0.5rem;
  padding: 0.25rem;
}

.toggle-btn {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.5rem 1rem;
  background: transparent;
  border: none;
  border-radius: 0.375rem;
  font-size: 0.875rem;
  font-weight: 600;
  color: var(--color-text-secondary);
  cursor: pointer;
  transition: all 0.2s ease;
  -webkit-tap-highlight-color: transparent;
}

.toggle-btn.active {
  background: var(--color-brand);
  color: var(--color-text-inverse);
}

@media (hover: hover) {
  .toggle-btn:not(.active):hover {
    background: var(--color-surface-alt);
    color: var(--color-text-primary);
  }
}

.toggle-icon {
  font-size: 1.125rem;
  line-height: 1;
}

.btn-export {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.625rem 1.25rem;
  background: var(--color-surface);
  border: 1.5px solid var(--color-border);
  border-radius: 0.5rem;
  font-size: 0.875rem;
  font-weight: 600;
  color: var(--color-text-primary);
  cursor: pointer;
  transition: all 0.2s ease;
  -webkit-tap-highlight-color: transparent;
}

.btn-export:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

@media (hover: hover) {
  .btn-export:not(:disabled):hover {
    border-color: var(--color-brand);
    background: var(--color-brand-soft);
  }
}

.export-icon {
  font-size: 1.125rem;
}

.filters-panel {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 1rem;
  background: var(--color-surface);
  padding: 1.5rem;
  border-radius: 0.75rem;
  border: 1px solid var(--color-border);
}

.filter-group {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.filter-group label {
  font-size: 0.875rem;
  font-weight: 600;
  color: var(--color-text-primary);
}

.filter-group input,
.filter-group select {
  padding: 0.625rem 0.75rem;
  border: 1.5px solid var(--color-border);
  border-radius: 0.5rem;
  background: var(--color-surface);
  font-size: 0.9375rem;
  color: var(--color-text-primary);
  transition: all 0.2s ease;
}

.filter-group input:focus,
.filter-group select:focus {
  outline: none;
  border-color: var(--color-brand);
  box-shadow: 0 0 0 3px rgba(22, 131, 90, 0.1);
}

.btn-clear-filters {
  padding: 0.625rem 1.25rem;
  background: var(--color-surface-alt);
  border: 1.5px solid var(--color-border);
  border-radius: 0.5rem;
  font-size: 0.875rem;
  font-weight: 600;
  color: var(--color-text-primary);
  cursor: pointer;
  transition: all 0.2s ease;
  -webkit-tap-highlight-color: transparent;
  align-self: flex-end;
}

@media (hover: hover) {
  .btn-clear-filters:hover {
    background: var(--color-danger-soft);
    border-color: var(--color-danger);
    color: var(--color-danger);
  }
}

.error-panel {
  background: var(--color-danger-soft);
  border: 1px solid var(--color-danger);
  border-radius: 0.75rem;
  padding: 1.5rem;
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 1rem;
}

.error-panel button {
  padding: 0.625rem 1.25rem;
  background: var(--color-brand);
  color: var(--color-text-inverse);
  border: none;
  border-radius: 0.5rem;
  font-weight: 600;
  cursor: pointer;
  -webkit-tap-highlight-color: transparent;
}

/* Table View */
.table-wrapper {
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: 0.75rem;
  overflow: hidden;
}

.activity-table {
  width: 100%;
  border-collapse: collapse;
}

.activity-table thead {
  background: var(--color-surface-alt);
}

.activity-table th {
  padding: 1rem 1.25rem;
  text-align: left;
  font-size: 0.875rem;
  font-weight: 600;
  color: var(--color-text-secondary);
  text-transform: uppercase;
  letter-spacing: 0.05em;
  border-bottom: 1px solid var(--color-border);
}

.activity-table td {
  padding: 1rem 1.25rem;
  border-bottom: 1px solid var(--color-border);
}

.activity-table tbody tr:last-child td {
  border-bottom: none;
}

.timestamp-cell {
  white-space: nowrap;
}

.timestamp {
  font-weight: 600;
  color: var(--color-text-primary);
}

.date {
  font-size: 0.875rem;
  color: var(--color-text-secondary);
}

.actor-info,
.entity-info {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}

.actor-email {
  font-weight: 600;
  color: var(--color-text-primary);
}

.actor-roles {
  font-size: 0.875rem;
  color: var(--color-text-secondary);
}

.action-badge {
  display: inline-flex;
  padding: 0.375rem 0.75rem;
  border-radius: 999px;
  font-size: 0.8125rem;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.05em;
}

.action-create {
  background: var(--color-success-soft);
  color: var(--color-success);
}

.action-update {
  background: var(--color-brand-soft);
  color: var(--color-brand);
}

.action-delete {
  background: var(--color-danger-soft);
  color: var(--color-danger);
}

.action-approve {
  background: var(--color-success-soft);
  color: var(--color-success);
}

.action-reject {
  background: var(--color-danger-soft);
  color: var(--color-danger);
}

.action-login,
.action-logout {
  background: var(--color-surface-alt);
  color: var(--color-text-secondary);
}

.entity-type {
  font-weight: 600;
  color: var(--color-text-primary);
}

.entity-id {
  font-size: 0.875rem;
  color: var(--color-text-secondary);
  font-family: 'Courier New', monospace;
}

.metadata {
  display: block;
  max-width: 300px;
  max-height: 100px;
  overflow: auto;
  background: var(--color-surface-alt);
  padding: 0.5rem;
  border-radius: 0.375rem;
  font-size: 0.8125rem;
  white-space: pre-wrap;
  word-break: break-all;
}

.no-metadata {
  color: var(--color-text-secondary);
}

/* Timeline View */
.timeline {
  display: flex;
  flex-direction: column;
  gap: 2rem;
}

.timeline-day {
  display: flex;
  flex-direction: column;
  gap: 1rem;
}

.timeline-date {
  position: sticky;
  top: 0;
  z-index: 10;
  background: var(--color-bg);
  padding: 0.5rem 0;
}

.date-marker {
  display: inline-flex;
  padding: 0.5rem 1rem;
  background: var(--color-surface);
  border: 1.5px solid var(--color-border);
  border-radius: 999px;
  font-size: 0.875rem;
  font-weight: 600;
  color: var(--color-brand);
}

.timeline-entries {
  display: flex;
  flex-direction: column;
  gap: 1.5rem;
  padding-left: 2rem;
  border-left: 2px solid var(--color-border);
}

.timeline-entry {
  position: relative;
  display: flex;
  gap: 1rem;
}

.timeline-dot {
  position: absolute;
  left: -2.5rem;
  top: 0.5rem;
  width: 1rem;
  height: 1rem;
  border-radius: 50%;
  border: 3px solid var(--color-bg);
  box-shadow: 0 0 0 2px var(--color-border);
}

.timeline-dot.action-create,
.timeline-dot.action-approve {
  background: var(--color-success);
  box-shadow: 0 0 0 2px var(--color-success);
}

.timeline-dot.action-update {
  background: var(--color-brand);
  box-shadow: 0 0 0 2px var(--color-brand);
}

.timeline-dot.action-delete,
.timeline-dot.action-reject {
  background: var(--color-danger);
  box-shadow: 0 0 0 2px var(--color-danger);
}

.timeline-card {
  flex: 1;
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: 0.75rem;
  padding: 1.25rem;
  display: flex;
  flex-direction: column;
  gap: 1rem;
  transition: all 0.2s ease;
}

@media (hover: hover) {
  .timeline-card:hover {
    border-color: var(--color-brand);
    box-shadow: 0 4px 12px rgba(0, 0, 0, 0.08);
  }
}

.timeline-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 1rem;
}

.timeline-time {
  font-size: 0.875rem;
  font-weight: 600;
  color: var(--color-text-secondary);
}

.timeline-body {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}

.timeline-actor {
  font-size: 0.9375rem;
  color: var(--color-text-primary);
}

.timeline-entity {
  font-size: 0.875rem;
  color: var(--color-text-secondary);
}

.entity-id-inline {
  padding: 0.125rem 0.375rem;
  background: var(--color-surface-alt);
  border-radius: 0.25rem;
  font-size: 0.8125rem;
  font-family: 'Courier New', monospace;
}

.timeline-metadata details {
  margin-top: 0.5rem;
}

.timeline-metadata summary {
  font-size: 0.875rem;
  font-weight: 600;
  color: var(--color-brand);
  cursor: pointer;
  user-select: none;
}

.timeline-metadata code {
  display: block;
  margin-top: 0.5rem;
  padding: 0.75rem;
  background: var(--color-surface-alt);
  border-radius: 0.5rem;
  font-size: 0.8125rem;
  white-space: pre-wrap;
  word-break: break-all;
}

/* Loading & Empty States */
.loading-skeleton {
  display: flex;
  flex-direction: column;
  gap: 1rem;
  padding: 2rem;
}

.skeleton-row,
.skeleton-timeline-item {
  height: 3rem;
  background: linear-gradient(90deg, var(--color-surface-alt) 0%, var(--color-border) 50%, var(--color-surface-alt) 100%);
  background-size: 200% 100%;
  border-radius: 0.5rem;
  animation: shimmer 1.5s infinite;
}

.skeleton-timeline-item {
  height: 8rem;
}

@keyframes shimmer {
  0% { background-position: -200% 0; }
  100% { background-position: 200% 0; }
}

.empty-state {
  text-align: center;
  padding: 4rem 2rem;
  color: var(--color-text-secondary);
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: 0.75rem;
}

/* Pagination */
.pagination-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  background: var(--color-surface);
  padding: 1rem 1.5rem;
  border-radius: 0.75rem;
  border: 1px solid var(--color-border);
}

.pager-btn {
  padding: 0.625rem 1.25rem;
  background: var(--color-surface-alt);
  border: 1.5px solid var(--color-border);
  border-radius: 0.5rem;
  font-weight: 600;
  font-size: 0.875rem;
  color: var(--color-text-primary);
  cursor: pointer;
  transition: all 0.2s ease;
  -webkit-tap-highlight-color: transparent;
}

.pager-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

@media (hover: hover) {
  .pager-btn:not(:disabled):hover {
    background: var(--color-brand-soft);
    border-color: var(--color-brand);
    color: var(--color-brand);
  }
}

.page-info {
  font-size: 0.875rem;
  font-weight: 600;
  color: var(--color-text-secondary);
}

/* Mobile Responsive */
@media (max-width: 1023px) {
  .activity-header {
    flex-direction: column;
    align-items: stretch;
  }

  .activity-header h1 {
    font-size: 1.75rem;
  }

  .header-actions {
    width: 100%;
  }

  .view-toggle {
    flex: 1;
  }

  .toggle-btn {
    flex: 1;
    justify-content: center;
  }

  .btn-export {
    flex: 1;
    justify-content: center;
  }

  .filters-panel {
    grid-template-columns: 1fr;
  }

  .btn-clear-filters {
    align-self: stretch;
  }

  .table-wrapper {
    overflow-x: auto;
    -webkit-overflow-scrolling: touch;
  }

  .activity-table {
    min-width: 800px;
  }

  .timeline-entries {
    padding-left: 1.5rem;
  }

  .timeline-dot {
    left: -2rem;
  }

  .pagination-footer {
    flex-wrap: wrap;
    gap: 1rem;
  }

  .pager-btn {
    flex: 1;
    min-width: 120px;
  }

  .page-info {
    width: 100%;
    text-align: center;
    order: -1;
  }
}

@media (max-width: 767px) {
  .activity-header h1 {
    font-size: 1.5rem;
  }

  .toggle-icon,
  .export-icon {
    font-size: 1rem;
  }

  .toggle-btn span:not(.toggle-icon),
  .btn-export span:not(.export-icon) {
    display: none;
  }

  .timeline-entries {
    padding-left: 1rem;
  }

  .timeline-dot {
    left: -1.5rem;
    width: 0.75rem;
    height: 0.75rem;
  }

  .date-marker {
    font-size: 0.8125rem;
  }
}
</style>
