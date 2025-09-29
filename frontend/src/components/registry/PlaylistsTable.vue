<template>
  <section class="registry-card">
    <header class="card-header">
      <div>
        <h2>{{ t('registry.tabs.playlists') }}</h2>
        <p>{{ t('registry.playlists.description') }}</p>
      </div>
    </header>

    <div class="table-wrapper" role="region" aria-live="polite">
      <div v-if="error" class="error-state">
        <p>{{ t('registry.table.error', { resource: t('registry.tabs.playlists') }) }}</p>
        <button type="button" class="retry" @click="handleRetry" :disabled="isLoading">
          {{ t('registry.table.retry') }}
        </button>
      </div>

      <table v-else class="data-table">
        <thead>
          <tr>
            <th scope="col">{{ t('registry.playlists.columns.playlist') }}</th>
            <th scope="col">{{ t('registry.playlists.columns.owner') }}</th>
            <th scope="col">{{ t('registry.playlists.columns.categories') }}</th>
            <th scope="col">{{ t('registry.playlists.columns.items') }}</th>
            <th scope="col">{{ t('registry.playlists.columns.download') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="isLoading && !items.length">
            <td :colspan="5">
              <div class="skeleton-stack" aria-hidden="true">
                <div v-for="index in 5" :key="index" class="skeleton-row"></div>
              </div>
            </td>
          </tr>
          <tr v-else-if="!items.length">
            <td :colspan="5" class="empty-state">{{ t('registry.table.empty') }}</td>
          </tr>
          <tr v-for="playlist in items" :key="playlist.id">
            <td>
              <div class="entity">
                <img :src="playlist.thumbnailUrl" :alt="playlist.title" class="entity-thumb rectangle" />
                <div class="entity-meta">
                  <div class="entity-name">{{ playlist.title }}</div>
                  <div class="entity-subtitle">{{ playlist.ytId }}</div>
                </div>
              </div>
            </td>
            <td>
              <div class="entity compact">
                <img :src="playlist.owner.avatarUrl" :alt="playlist.owner.name" class="entity-thumb" />
                <div class="entity-meta">
                  <div class="entity-name">{{ playlist.owner.name }}</div>
                  <div class="entity-subtitle">{{ playlist.owner.ytId }}</div>
                </div>
              </div>
            </td>
            <td>
              <ul class="category-tags">
                <li v-for="tag in playlist.categories" :key="tag.id" class="category-tag">{{ tag.label }}</li>
              </ul>
            </td>
            <td class="numeric">{{ formatNumber(playlist.itemCount, currentLocale) }}</td>
            <td>
              <span
                class="badge"
                :class="playlist.downloadable === false ? 'badge-negative' : 'badge-positive'"
              >
                {{
                  playlist.downloadable === false
                    ? t('registry.playlists.download.blocked')
                    : t('registry.playlists.download.available')
                }}
              </span>
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
        <span v-if="isLoading">{{ t('registry.table.loading') }}</span>
        <span v-else>{{ paginationSummary }}</span>
      </div>
      <button type="button" class="pager" @click="handleNext" :disabled="!hasNext || isLoading">
        {{ t('registry.pagination.next') }}
      </button>
    </footer>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted } from 'vue';
import { useI18n } from 'vue-i18n';
import { useCursorPagination } from '@/composables/useCursorPagination';
import { fetchPlaylistsPage } from '@/services/registry';
import { formatNumber as formatNumberUtil } from '@/utils/formatters';
import type { PlaylistSummary } from '@/types/registry';

const { t, locale } = useI18n();
const currentLocale = computed(() => locale.value);

const pagination = useCursorPagination<PlaylistSummary>((cursor, limit) =>
  fetchPlaylistsPage({ cursor, limit })
);

const { items, isLoading, error, load, next, previous, hasNext, hasPrevious, pageInfo } = pagination;

onMounted(() => {
  load(null, 'reset');
});

const paginationSummary = computed(() => {
  if (!pageInfo.value) {
    return '';
  }
  const count = formatNumberUtil(items.value.length, currentLocale.value);
  const pageLimit = pageInfo.value.limit ?? items.value.length;
  return t('registry.pagination.showing', {
    count,
    limit: formatNumberUtil(pageLimit, currentLocale.value)
  });
});

function formatNumber(value: number, currentLocale: string) {
  return formatNumberUtil(value, currentLocale);
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
</script>

<style scoped>
.registry-card {
  background: white;
  border-radius: 1rem;
  box-shadow: 0 20px 45px -30px rgba(15, 23, 42, 0.35);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.card-header {
  padding: 1.5rem 1.75rem 1rem;
  border-bottom: 1px solid #e2e8f0;
}

.card-header h2 {
  margin: 0;
  font-size: 1.125rem;
  font-weight: 600;
  color: #0f172a;
}

.card-header p {
  margin: 0.375rem 0 0;
  color: #475569;
  font-size: 0.95rem;
}

.table-wrapper {
  overflow-x: auto;
}

.data-table {
  width: 100%;
  border-collapse: collapse;
  min-width: 960px;
}

th {
  position: sticky;
  top: 0;
  background: #f1f5f9;
  color: #0f172a;
  text-align: left;
  padding: 0.75rem 1.25rem;
  font-size: 0.9rem;
  font-weight: 600;
  border-bottom: 1px solid #e2e8f0;
  z-index: 1;
}

td {
  padding: 0.75rem 1.25rem;
  border-bottom: 1px solid #e2e8f0;
  vertical-align: middle;
  font-size: 0.95rem;
  color: #1e293b;
}

.numeric {
  text-align: right;
}

.entity {
  display: flex;
  align-items: center;
  gap: 1rem;
}

.entity.compact {
  gap: 0.75rem;
}

.entity-thumb {
  width: 48px;
  height: 48px;
  border-radius: 50%;
  object-fit: cover;
  border: 1px solid #e2e8f0;
  background: #cbd5f5;
}

.entity-thumb.rectangle {
  width: 72px;
  height: 48px;
  border-radius: 0.5rem;
}

.entity-meta {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}

.entity-name {
  font-weight: 600;
  color: #0f172a;
}

.entity-subtitle {
  color: #64748b;
  font-size: 0.85rem;
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
  background: #e2e8f0;
  color: #0f172a;
  padding: 0.25rem 0.75rem;
  border-radius: 999px;
  font-size: 0.8rem;
  font-weight: 500;
}

.badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 0.25rem 0.75rem;
  border-radius: 999px;
  font-size: 0.8rem;
  font-weight: 600;
}

.badge-positive {
  background: #d1fae5;
  color: #047857;
}

.badge-negative {
  background: #fee2e2;
  color: #b91c1c;
}

.skeleton-stack {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.skeleton-row {
  height: 16px;
  border-radius: 999px;
  background: linear-gradient(90deg, #f8fafc 25%, #e2e8f0 50%, #f8fafc 75%);
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
  color: #64748b;
  padding: 2rem 0;
}

.error-state {
  padding: 2rem;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 0.75rem;
  color: #b91c1c;
}

.retry {
  background: #0f172a;
  color: white;
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
  color: #475569;
  font-size: 0.9rem;
}

.pager {
  background: #0f172a;
  color: white;
  border: none;
  padding: 0.5rem 1.25rem;
  border-radius: 0.75rem;
  cursor: pointer;
  font-weight: 600;
  transition: background 0.2s ease;
}

.pager:disabled {
  background: #94a3b8;
  cursor: not-allowed;
}

.pager:not(:disabled):hover {
  background: #1e293b;
}
</style>
