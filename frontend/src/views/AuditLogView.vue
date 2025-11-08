<template>
  <section class="audit-view">
    <header class="workspace-header">
      <div>
        <h1>{{ t('audit.heading') }}</h1>
        <p>{{ t('audit.description') }}</p>
      </div>
      <div class="header-filters">
        <label class="sr-only" for="audit-actor-search">{{ t('audit.filters.actorLabel') }}</label>
        <input
          id="audit-actor-search"
          v-model="actorFilter"
          type="search"
          class="input"
          :placeholder="t('audit.filters.actorPlaceholder')"
          @input="scheduleReload"
        />
        <label class="sr-only" for="audit-action-filter">{{ t('audit.filters.actionLabel') }}</label>
        <input
          id="audit-action-filter"
          v-model="actionFilter"
          type="search"
          class="input"
          :placeholder="t('audit.filters.actionPlaceholder')"
          @input="scheduleReload"
        />
      </div>
    </header>

    <div v-if="errorMessage" class="action-error" role="alert">
      {{ errorMessage }}
      <button type="button" class="retry" :disabled="isLoading" @click="reload">{{ t('audit.table.retry') }}</button>
    </div>

    <div class="table-wrapper" role="region" aria-live="polite">
      <table class="data-table">
        <thead>
          <tr>
            <th scope="col">{{ t('audit.columns.actor') }}</th>
            <th scope="col">{{ t('audit.columns.action') }}</th>
            <th scope="col">{{ t('audit.columns.entity') }}</th>
            <th scope="col">{{ t('audit.columns.metadata') }}</th>
            <th scope="col">{{ t('audit.columns.timestamp') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="isLoading && !entries.length">
            <td :colspan="5">
              <div class="skeleton-stack" aria-hidden="true">
                <div v-for="index in 5" :key="`skeleton-${index}`" class="skeleton-row"></div>
              </div>
            </td>
          </tr>
          <tr v-else-if="!entries.length">
            <td :colspan="5" class="empty-state">{{ t('audit.table.empty') }}</td>
          </tr>
          <tr v-for="entry in entries" :key="entry.id">
            <td>
              <div class="actor-email">{{ entry.actorUid }}</div>
              <div class="actor-roles">{{ entry.actorDisplayName || t('audit.roles.none') }}</div>
            </td>
            <td class="action-cell">
              <span class="action-badge">{{ entry.action }}</span>
            </td>
            <td>
              <div class="entity-type">{{ entry.entityType }}</div>
              <div class="entity-id">{{ entry.entityId }}</div>
            </td>
            <td>
              <code class="metadata">{{ formatMetadata(entry.details) }}</code>
            </td>
            <td>{{ formatDateTime(entry.timestamp) }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <footer class="table-footer">
      <button type="button" class="pager" :disabled="!hasPrevious || isLoading" @click="previous">
        {{ t('audit.pagination.previous') }}
      </button>
      <div class="footer-status">
        <span v-if="isLoading">{{ t('audit.table.loading') }}</span>
        <span v-else>{{ paginationSummary }}</span>
      </div>
      <button type="button" class="pager" :disabled="!hasNext || isLoading" @click="next">
        {{ t('audit.pagination.next') }}
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

const actorFilter = ref('');
const actionFilter = ref('');
const errorMessage = ref<string | null>(null);

const pagination = useCursorPagination<AuditEntry>(async (cursor, limit) => {
  return fetchAuditLogPage({
    cursor,
    limit,
    actorId: actorFilter.value.trim() || undefined,
    action: actionFilter.value.trim() || undefined
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

function formatMetadata(metadata: Record<string, unknown>) {
  try {
    return JSON.stringify(metadata, null, 2);
  } catch (_err) {
    return t('audit.metadata.unavailable');
  }
}

function formatDateTime(value: string) {
  return baseFormatDateTime(value, currentLocale.value);
}

const paginationSummary = computed(() => {
  if (!pageInfo.value) {
    return '';
  }
  const formatter = new Intl.NumberFormat(currentLocale.value);
  const count = formatter.format(entries.value.length);
  const limit = formatter.format(pageInfo.value.limit ?? entries.value.length);
  return t('audit.pagination.showing', { count, limit });
});
</script>

<style scoped>
.audit-view {
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
  max-width: 560px;
}

.header-filters {
  display: flex;
  gap: 0.75rem;
  flex-wrap: wrap;
}

.input {
  border-radius: 0.75rem;
  border: 1px solid var(--color-border);
  padding: 0.6rem 0.75rem;
  min-width: 200px;
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
  min-width: 960px;
}

th {
  text-align: left;
  padding: 0.75rem 1.25rem;
  background: var(--color-surface-alt);
  color: var(--color-text-primary);
  font-weight: 600;
  border-bottom: 1px solid var(--color-border);
}

td {
  padding: 0.75rem 1.25rem;
  border-bottom: 1px solid var(--color-border);
  vertical-align: top;
}

.actor-email {
  font-weight: 600;
}

.actor-roles,
.entity-id {
  font-size: 0.85rem;
  color: var(--color-text-secondary);
}

.action-cell {
  width: 180px;
}

.action-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 0.3rem 0.75rem;
  border-radius: 999px;
  background: var(--color-surface-alt);
  font-weight: 600;
}

.metadata {
  display: block;
  white-space: pre-wrap;
  background: var(--color-surface-alt);
  padding: 0.5rem;
  border-radius: 0.75rem;
  font-size: 0.85rem;
}

.skeleton-stack {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.skeleton-row {
  height: 1.5rem;
  border-radius: 0.5rem;
  background: linear-gradient(90deg, var(--color-surface-alt) 0%, var(--color-border) 50%, var(--color-surface-alt) 100%);
  animation: shimmer 1.4s infinite;
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
  padding: 1.5rem;
  color: var(--color-text-secondary);
}

.action-error {
  background: var(--color-danger-soft);
  color: var(--color-danger);
  border-radius: 0.75rem;
  padding: 0.75rem 1rem;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
}

.retry {
  border: none;
  border-radius: 0.75rem;
  padding: 0.5rem 1.25rem;
  background: var(--color-brand);
  color: var(--color-text-inverse);
  font-weight: 600;
  cursor: pointer;
}

.table-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  background: var(--color-surface);
  border-radius: 1rem;
  padding: 1rem 1.5rem;
  box-shadow: var(--shadow-elevated);
}

.pager {
  border: none;
  border-radius: 0.75rem;
  padding: 0.6rem 1.2rem;
  background: var(--color-surface-alt);
  font-weight: 600;
  cursor: pointer;
}

.pager:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.footer-status {
  font-weight: 600;
  color: var(--color-text-secondary);
}

.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  border: 0;
}

/* Touch Optimizations */
button,
input,
select {
  -webkit-tap-highlight-color: transparent;
}

button,
.pager,
.retry {
  min-height: 44px;
}

/* Mobile/Tablet Responsive */
@media (max-width: 1023px) {
  .audit-view {
    gap: 1.5rem;
  }

  .audit-header {
    flex-direction: column;
    gap: 1.25rem;
  }

  .audit-header h1 {
    font-size: 1.75rem;
  }

  .audit-header p {
    font-size: 0.875rem;
  }

  .filters {
    flex-direction: column;
    gap: 1rem;
  }

  .filter {
    flex-direction: row;
    align-items: center;
    justify-content: space-between;
  }

  .filter select {
    width: auto;
    min-width: 150px;
    min-height: 44px;
  }

  .table-wrapper {
    overflow-x: auto;
    -webkit-overflow-scrolling: touch;
  }

  .audit-table {
    min-width: 900px;
  }

  .table-footer {
    flex-wrap: wrap;
    gap: 1rem;
  }

  .pager {
    flex: 1;
    min-width: 120px;
    min-height: 48px;
  }

  .footer-status {
    width: 100%;
    text-align: center;
    order: -1;
  }

  .action-error {
    flex-direction: column;
    align-items: stretch;
  }

  .retry {
    width: 100%;
    min-height: 48px;
  }
}

@media (max-width: 767px) {
  .audit-header h1 {
    font-size: 1.5rem;
  }

  .filters span {
    font-size: 0.875rem;
  }

  .audit-table {
    font-size: 0.875rem;
  }

  td {
    padding: 0.625rem 1rem;
  }
}
</style>
