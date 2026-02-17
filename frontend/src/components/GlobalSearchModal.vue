<template>
  <Teleport to="body">
    <div v-if="isOpen" class="modal-backdrop" @click="close">
      <div
        ref="modalRef"
        class="search-modal"
        role="dialog"
        aria-modal="true"
        aria-label="Global search"
        @click.stop
      >
        <div class="search-header">
          <div class="search-input-container">
            <span class="search-icon" aria-hidden="true">🔍</span>
            <input
              ref="searchInputRef"
              v-model="searchQuery"
              type="search"
              :placeholder="t('globalSearch.placeholder')"
              class="search-input"
              @input="handleSearchInput"
              @keydown.down.prevent="navigateResults(1)"
              @keydown.up.prevent="navigateResults(-1)"
              @keydown.enter.prevent="selectResult"
              @keydown.esc="close"
            />
            <button
              v-if="searchQuery"
              type="button"
              class="clear-button"
              @click="clearSearch"
              :aria-label="t('globalSearch.clearSearch')"
            >
              ✕
            </button>
          </div>
          <button type="button" class="close-button" @click="close" :aria-label="t('globalSearch.close')">
            ✕
          </button>
        </div>

        <div class="search-filters">
          <button
            v-for="filter in entityFilters"
            :key="filter.value"
            type="button"
            :class="['filter-chip', { active: selectedFilters.includes(filter.value) }]"
            @click="toggleFilter(filter.value)"
          >
            {{ filter.label }}
          </button>
        </div>

        <div class="search-body">
          <!-- Recent Searches -->
          <div v-if="!searchQuery && recentSearches.length > 0" class="recent-searches">
            <div class="section-header">
              <h3>{{ t('globalSearch.recentSearches') }}</h3>
              <button type="button" class="link-button" @click="clearRecentSearches">
                {{ t('globalSearch.clearRecent') }}
              </button>
            </div>
            <div class="search-results">
              <button
                v-for="(recent, idx) in recentSearches"
                :key="idx"
                type="button"
                class="result-item recent-item"
                @click="applyRecentSearch(recent)"
              >
                <span class="recent-icon">🕒</span>
                <span class="recent-text">{{ recent }}</span>
              </button>
            </div>
          </div>

          <!-- Search Suggestions -->
          <div v-else-if="searchQuery && suggestions.length > 0 && !searching" class="suggestions">
            <div class="section-header">
              <h3>{{ t('globalSearch.suggestions') }}</h3>
            </div>
            <div class="search-results">
              <button
                v-for="(suggestion, idx) in suggestions"
                :key="idx"
                type="button"
                :class="['result-item suggestion-item', { focused: idx === focusedIndex }]"
                @click="applySuggestion(suggestion)"
              >
                <span class="suggestion-icon">💡</span>
                <span class="suggestion-text">{{ suggestion }}</span>
              </button>
            </div>
          </div>

          <!-- Search Results -->
          <div v-else-if="searchQuery && results.length > 0" class="results">
            <div class="section-header">
              <h3>{{ t('globalSearch.resultsCount', { count: results.length }) }}</h3>
            </div>
            <div class="search-results">
              <button
                v-for="(result, idx) in results"
                :key="result.id"
                type="button"
                :class="['result-item', { focused: idx === focusedIndex }]"
                @click="selectResultItem(result)"
              >
                <div class="result-icon">{{ getEntityIcon(result.type) }}</div>
                <div class="result-content">
                  <div class="result-title">{{ result.title }}</div>
                  <div class="result-meta">
                    <span class="result-type">{{ getEntityTypeLabel(result.type) }}</span>
                    <span v-if="result.subtitle" class="result-subtitle">{{ result.subtitle }}</span>
                  </div>
                </div>
              </button>
            </div>
          </div>

          <!-- Loading -->
          <div v-else-if="searching" class="empty-state">
            <div class="loading-spinner"></div>
            <p>{{ t('globalSearch.searching') }}</p>
          </div>

          <!-- No Results -->
          <div v-else-if="searchQuery && !searching" class="empty-state">
            <span class="empty-icon">🔍</span>
            <p>{{ t('globalSearch.noResults') }}</p>
          </div>

          <!-- Empty State -->
          <div v-else class="empty-state">
            <span class="empty-icon">🔎</span>
            <p>{{ t('globalSearch.emptyState') }}</p>
            <p class="empty-hint">{{ t('globalSearch.emptyHint') }}</p>
          </div>
        </div>

        <div class="search-footer">
          <div class="keyboard-hints">
            <kbd>↑</kbd><kbd>↓</kbd> {{ t('globalSearch.navigate') }}
            <kbd>↵</kbd> {{ t('globalSearch.select') }}
            <kbd>Esc</kbd> {{ t('globalSearch.closeHint') }}
          </div>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { ref, computed, watch, nextTick, onMounted, onUnmounted } from 'vue';
import { useI18n } from 'vue-i18n';
import { useRouter } from 'vue-router';
import { useFocusTrap } from '@/composables/useFocusTrap';
import apiClient from '@/services/api/client';

const { t } = useI18n();
const router = useRouter();

interface SearchResult {
  id: string;
  type: 'channel' | 'playlist' | 'video' | 'category' | 'user' | 'content';
  title: string;
  subtitle?: string;
  route?: string;
}

interface Props {
  isOpen: boolean;
}

interface Emits {
  (e: 'close'): void;
}

const props = defineProps<Props>();
const emit = defineEmits<Emits>();

const modalRef = ref<HTMLElement | null>(null);
const searchInputRef = ref<HTMLInputElement | null>(null);
const searchQuery = ref('');
const searching = ref(false);
const selectedFilters = ref<string[]>([]);
const results = ref<SearchResult[]>([]);
const focusedIndex = ref(0);
const recentSearches = ref<string[]>([]);

const entityFilters = computed(() => [
  { value: 'all', label: t('globalSearch.filters.all') },
  { value: 'channels', label: t('globalSearch.filters.channels') },
  { value: 'playlists', label: t('globalSearch.filters.playlists') },
  { value: 'videos', label: t('globalSearch.filters.videos') },
  { value: 'categories', label: t('globalSearch.filters.categories') },
  { value: 'users', label: t('globalSearch.filters.users') }
]);

const suggestions = computed<string[]>(() => {
  // No hardcoded suggestions - return empty array
  return [];
});

// Focus trap
const { activate, deactivate } = useFocusTrap(modalRef, {
  onEscape: close,
  escapeDeactivates: true,
  returnFocus: true
});

watch(() => props.isOpen, async (isOpen) => {
  if (isOpen) {
    activate();
    await nextTick();
    searchInputRef.value?.focus();
    loadRecentSearches();
  } else {
    deactivate();
  }
});

function close() {
  emit('close');
}

function clearSearch() {
  searchQuery.value = '';
  results.value = [];
  focusedIndex.value = 0;
}

function toggleFilter(filter: string) {
  if (filter === 'all') {
    selectedFilters.value = [];
  } else {
    const index = selectedFilters.value.indexOf(filter);
    if (index > -1) {
      selectedFilters.value.splice(index, 1);
    } else {
      selectedFilters.value.push(filter);
    }
  }
  handleSearchInput();
}

let searchDebounce: ReturnType<typeof setTimeout> | null = null;

async function handleSearchInput() {
  if (!searchQuery.value || searchQuery.value.length < 2) {
    results.value = [];
    return;
  }

  // Debounce API calls
  if (searchDebounce) clearTimeout(searchDebounce);
  searchDebounce = setTimeout(() => performSearch(), 300);
}

async function performSearch() {
  searching.value = true;
  focusedIndex.value = 0;

  try {
    // Determine which content types to search
    const typeFilter = selectedFilters.value.length > 0
      ? selectedFilters.value
      : ['channels', 'playlists', 'videos'];

    // Map filter values to API types parameter
    const apiTypes: string[] = [];
    if (typeFilter.includes('channels')) apiTypes.push('CHANNEL');
    if (typeFilter.includes('playlists')) apiTypes.push('PLAYLIST');
    if (typeFilter.includes('videos')) apiTypes.push('VIDEO');

    if (apiTypes.length > 0) {
      const response = await apiClient.get('/api/admin/content', {
        params: {
          search: searchQuery.value,
          types: apiTypes.join(','),
          size: 10
        }
      });

      const items = response.data?.items || [];
      results.value = items.map((item: any) => ({
        id: item.id || '',
        type: (item.type?.toLowerCase() || 'content') as SearchResult['type'],
        title: item.title || '',
        subtitle: item.type || '',
        route: '/content-library'
      }));
    } else {
      results.value = [];
    }
  } catch (err) {
    console.error('Global search failed:', err);
    results.value = [];
  } finally {
    searching.value = false;
  }
}

function navigateResults(direction: number) {
  const maxIndex = (searchQuery.value && suggestions.value.length > 0 && !searching.value)
    ? suggestions.value.length - 1
    : results.value.length - 1;

  if (maxIndex < 0) return;

  focusedIndex.value = Math.max(0, Math.min(maxIndex, focusedIndex.value + direction));
}

function selectResult() {
  if (searchQuery.value && suggestions.value.length > 0 && !searching.value) {
    applySuggestion(suggestions.value[focusedIndex.value]);
  } else if (results.value.length > 0) {
    selectResultItem(results.value[focusedIndex.value]);
  }
}

function applySuggestion(suggestion: string) {
  searchQuery.value = suggestion;
  handleSearchInput();
}

function selectResultItem(result: SearchResult) {
  saveRecentSearch(searchQuery.value);
  if (result.route) {
    router.push(result.route);
  }
  close();
}

function applyRecentSearch(query: string) {
  searchQuery.value = query;
  handleSearchInput();
}

function getEntityIcon(type: string): string {
  const icons: Record<string, string> = {
    channel: '📺',
    playlist: '📋',
    video: '🎥',
    category: '🏷️',
    user: '👤',
    content: '📄'
  };
  return icons[type] || '📄';
}

function getEntityTypeLabel(type: string): string {
  return t(`globalSearch.entityTypes.${type}`);
}

function loadRecentSearches() {
  try {
    const stored = localStorage.getItem('albunyaan_recent_searches');
    if (stored) {
      recentSearches.value = JSON.parse(stored);
    }
  } catch (error) {
    console.error('Failed to load recent searches:', error);
  }
}

function saveRecentSearch(query: string) {
  if (!query || query.length < 2) return;

  try {
    const searches = recentSearches.value.filter(s => s !== query);
    searches.unshift(query);
    recentSearches.value = searches.slice(0, 5);
    localStorage.setItem('albunyaan_recent_searches', JSON.stringify(recentSearches.value));
  } catch (error) {
    console.error('Failed to save recent search:', error);
  }
}

function clearRecentSearches() {
  recentSearches.value = [];
  localStorage.removeItem('albunyaan_recent_searches');
}

// Keyboard shortcut (Ctrl/Cmd + K)
function handleGlobalKeydown(event: KeyboardEvent) {
  if ((event.metaKey || event.ctrlKey) && event.key === 'k') {
    event.preventDefault();
    if (!props.isOpen) {
      emit('close'); // Toggle open via parent
    }
  }
}

onMounted(() => {
  window.addEventListener('keydown', handleGlobalKeydown);
});

onUnmounted(() => {
  window.removeEventListener('keydown', handleGlobalKeydown);
});
</script>

<style scoped>
.modal-backdrop {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: flex-start;
  justify-content: center;
  padding: 4rem 1rem;
  z-index: 9999;
  animation: fadeIn 0.2s ease;
}

@keyframes fadeIn {
  from {
    opacity: 0;
  }
  to {
    opacity: 1;
  }
}

.search-modal {
  width: 100%;
  max-width: 640px;
  background: var(--color-background-primary);
  border-radius: 0.75rem;
  box-shadow: 0 20px 25px -5px rgba(0, 0, 0, 0.1), 0 10px 10px -5px rgba(0, 0, 0, 0.04);
  display: flex;
  flex-direction: column;
  max-height: 80vh;
  animation: slideDown 0.2s ease;
}

@keyframes slideDown {
  from {
    transform: translateY(-2rem);
    opacity: 0;
  }
  to {
    transform: translateY(0);
    opacity: 1;
  }
}

.search-header {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  padding: 1rem;
  border-bottom: 1px solid var(--color-border);
}

.search-input-container {
  flex: 1;
  position: relative;
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.search-icon {
  font-size: 1.25rem;
  color: var(--color-text-secondary);
}

.search-input {
  flex: 1;
  border: none;
  background: none;
  font-size: 1rem;
  color: var(--color-text-primary);
  outline: none;
}

.search-input::placeholder {
  color: var(--color-text-tertiary);
}

.clear-button,
.close-button {
  padding: 0.25rem;
  background: none;
  border: none;
  color: var(--color-text-secondary);
  cursor: pointer;
  font-size: 1.25rem;
  line-height: 1;
  transition: color 0.2s;
}

.clear-button:hover,
.close-button:hover {
  color: var(--color-text-primary);
}

.search-filters {
  display: flex;
  gap: 0.5rem;
  padding: 0.75rem 1rem;
  border-bottom: 1px solid var(--color-border);
  overflow-x: auto;
  scrollbar-width: thin;
}

.filter-chip {
  padding: 0.375rem 0.75rem;
  background: var(--color-background-secondary);
  border: 1px solid var(--color-border);
  border-radius: 1rem;
  font-size: 0.875rem;
  color: var(--color-text-secondary);
  cursor: pointer;
  white-space: nowrap;
  transition: all 0.2s;
}

.filter-chip:hover {
  background: var(--color-background-tertiary);
  border-color: var(--color-primary);
}

.filter-chip.active {
  background: var(--color-primary);
  border-color: var(--color-primary);
  color: white;
}

.search-body {
  flex: 1;
  overflow-y: auto;
  min-height: 300px;
}

.section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 1rem;
  border-bottom: 1px solid var(--color-border);
}

.section-header h3 {
  font-size: 0.875rem;
  font-weight: 600;
  color: var(--color-text-secondary);
  margin: 0;
  text-transform: uppercase;
  letter-spacing: 0.05em;
}

.link-button {
  background: none;
  border: none;
  color: var(--color-primary);
  font-size: 0.875rem;
  cursor: pointer;
  padding: 0;
}

.link-button:hover {
  text-decoration: underline;
}

.search-results {
  padding: 0.5rem;
}

.result-item {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 0.75rem;
  padding: 0.75rem;
  background: none;
  border: none;
  border-radius: 0.5rem;
  cursor: pointer;
  text-align: start;
  transition: background 0.2s;
}

.result-item:hover,
.result-item.focused {
  background: var(--color-background-secondary);
}

.recent-icon,
.suggestion-icon,
.result-icon {
  font-size: 1.5rem;
  flex-shrink: 0;
}

.recent-text,
.suggestion-text {
  color: var(--color-text-primary);
  font-size: 0.9375rem;
}

.result-content {
  flex: 1;
  min-width: 0;
}

.result-title {
  font-size: 0.9375rem;
  font-weight: 500;
  color: var(--color-text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.result-meta {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  margin-top: 0.25rem;
  font-size: 0.8125rem;
  color: var(--color-text-secondary);
}

.result-type {
  font-weight: 500;
}

.result-subtitle::before {
  content: '•';
  margin-inline-end: 0.5rem;
}

.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 3rem 2rem;
  text-align: center;
  color: var(--color-text-secondary);
}

.empty-icon {
  font-size: 3rem;
  margin-bottom: 1rem;
  opacity: 0.5;
}

.empty-state p {
  margin: 0.25rem 0;
}

.empty-hint {
  font-size: 0.875rem;
  color: var(--color-text-tertiary);
}

.loading-spinner {
  width: 2rem;
  height: 2rem;
  border: 3px solid var(--color-border);
  border-top-color: var(--color-primary);
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
  margin-bottom: 1rem;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

.search-footer {
  padding: 0.75rem 1rem;
  border-top: 1px solid var(--color-border);
  background: var(--color-background-secondary);
}

.keyboard-hints {
  display: flex;
  align-items: center;
  gap: 1rem;
  font-size: 0.75rem;
  color: var(--color-text-secondary);
}

kbd {
  padding: 0.125rem 0.375rem;
  background: var(--color-background-tertiary);
  border: 1px solid var(--color-border);
  border-radius: 0.25rem;
  font-size: 0.6875rem;
  font-family: monospace;
}

@media (max-width: 768px) {
  .modal-backdrop {
    padding: 2rem 0.5rem;
  }

  .search-modal {
    max-height: 90vh;
  }
}
</style>
