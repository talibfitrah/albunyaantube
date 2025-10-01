<template>
  <section class="exclusions-workspace">
    <header class="workspace-header">
      <div>
        <h1>{{ t('exclusions.heading') }}</h1>
        <p>{{ t('exclusions.description') }}</p>
      </div>
      <button type="button" class="primary" @click="openAddDialog">
        {{ t('exclusions.actions.add') }}
      </button>
    </header>

    <div class="controls" role="search">
      <label class="visually-hidden" :for="searchInputId">{{ t('exclusions.search.label') }}</label>
      <div class="search-field">
        <input
          :id="searchInputId"
          :placeholder="t('exclusions.search.placeholder')"
          :value="searchQuery"
          @input="onSearchChange"
          type="search"
        />
        <button v-if="searchQuery" type="button" class="clear" @click="clearSearch">
          {{ t('exclusions.actions.clearSearch') }}
        </button>
      </div>

      <div class="type-filters" role="radiogroup" :aria-label="t('exclusions.filter.label')">
        <button
          v-for="option in typeOptions"
          :key="option.value"
          type="button"
          class="type-option"
          role="radio"
          :aria-checked="typeFilter === option.value"
          :class="{ active: typeFilter === option.value }"
          @click="setTypeFilter(option.value)"
        >
          {{ option.label }}
        </button>
      </div>
    </div>

    <div v-if="hasSelection" class="bulk-bar" aria-live="polite">
      <span>{{ t('exclusions.summary.selection', { count: selectedIds.length }) }}</span>
      <div class="bulk-actions">
        <button type="button" class="bulk-primary" @click="handleBulkInclude">
          {{ t('exclusions.actions.bulkInclude') }}
        </button>
        <button type="button" class="bulk-secondary" @click="clearSelection">
          {{ t('exclusions.actions.clearSelection') }}
        </button>
      </div>
    </div>

    <div class="table-wrapper" role="region" :aria-live="filteredItems.length ? 'off' : 'polite'">
      <table class="exclusions-table">
        <thead>
          <tr>
            <th scope="col">
              <input
                type="checkbox"
                :checked="isAllVisibleSelected"
                :aria-label="t('exclusions.table.columns.selection')"
                @change="toggleSelectAll"
              />
            </th>
            <th scope="col">{{ t('exclusions.table.columns.entity') }}</th>
            <th scope="col">{{ t('exclusions.table.columns.type') }}</th>
            <th scope="col">{{ t('exclusions.table.columns.parent') }}</th>
            <th scope="col">{{ t('exclusions.table.columns.reason') }}</th>
            <th scope="col">{{ t('exclusions.table.columns.updated') }}</th>
            <th scope="col">{{ t('exclusions.table.columns.status') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="!filteredItems.length">
            <td :colspan="7" class="empty">{{ t('exclusions.table.empty') }}</td>
          </tr>
          <tr v-for="entry in filteredItems" :key="entry.id">
            <td>
              <input
                type="checkbox"
                :aria-label="t('exclusions.table.rowSelect', { name: entry.label })"
                :checked="isSelected(entry.id)"
                @change="toggleSelection(entry.id, $event)"
              />
            </td>
            <td>
              <div class="entity-cell">
                <span class="entity-label">{{ entry.label }}</span>
                <span class="entity-id">{{ entry.id }}</span>
              </div>
            </td>
            <td>
              <span class="pill" :data-type="entry.type">{{ typeLabel(entry.type) }}</span>
            </td>
            <td>
              <span class="parent-label">{{ entry.parentLabel }}</span>
            </td>
            <td>
              <span v-if="entry.reason" class="reason-text">{{ entry.reason }}</span>
              <span v-else class="reason-missing">{{ t('exclusions.table.noReason') }}</span>
            </td>
            <td>
              <time :datetime="entry.updatedAt">{{ formatUpdated(entry.updatedAt) }}</time>
            </td>
            <td>
              <div class="status-toggle" role="radiogroup" :aria-label="t('exclusions.table.columns.status')">
                <button
                  v-for="state in statusOptions"
                  :key="state"
                  type="button"
                  class="status-option"
                  role="radio"
                  :aria-checked="entry.status === state"
                  :class="{ active: entry.status === state }"
                  @click="setEntryStatus(entry, state)"
                >
                  {{ statusLabel(state) }}
                </button>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <p v-if="actionMessage" :id="actionMessageId" class="action-message" role="status" aria-live="polite">
      {{ actionMessage }}
    </p>
  </section>

  <div v-if="addDialog.visible" class="modal-backdrop">
    <div
      ref="addDialogRef"
      class="modal"
      role="dialog"
      aria-modal="true"
      :aria-labelledby="addDialogTitleId"
      :aria-describedby="addDialogDescriptionId"
      tabindex="-1"
    >
      <h2 :id="addDialogTitleId">{{ t('exclusions.dialog.title') }}</h2>
      <p :id="addDialogDescriptionId" class="modal-description">
        {{ t('exclusions.dialog.description') }}
      </p>
      <form @submit.prevent="handleAdd">
        <label class="modal-label" :for="addDialogTargetId">
          {{ t('exclusions.dialog.targetLabel') }}
        </label>
        <input
          :id="addDialogTargetId"
          ref="addDialogTargetRef"
          v-model.trim="addDialog.label"
          type="text"
          required
          autocomplete="off"
        />

        <label class="modal-label" :for="addDialogTypeId">
          {{ t('exclusions.dialog.typeLabel') }}
        </label>
        <select :id="addDialogTypeId" v-model="addDialog.type">
          <option v-for="option in typeOptions.filter(option => option.value !== 'all')" :key="option.value" :value="option.value">
            {{ option.label }}
          </option>
        </select>

        <label class="modal-label" :for="addDialogReasonId">
          {{ t('exclusions.dialog.reasonLabel') }}
        </label>
        <textarea :id="addDialogReasonId" v-model.trim="addDialog.reason" rows="3"></textarea>

        <div class="modal-actions">
          <button type="button" class="modal-secondary" @click="closeAddDialog">
            {{ t('exclusions.actions.cancel') }}
          </button>
          <button type="submit" class="modal-primary">
            {{ t('exclusions.actions.create') }}
          </button>
        </div>
      </form>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, reactive, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { formatDateTime } from '@/utils/formatters';
import { useFocusTrap } from '@/composables/useFocusTrap';

type ExclusionType = 'channel' | 'playlist' | 'video';
type ExclusionStatus = 'excluded' | 'pending' | 'included';

interface ExclusionEntry {
  id: string;
  label: string;
  type: ExclusionType;
  parentLabel: string;
  reason: string | null;
  status: ExclusionStatus;
  updatedAt: string;
}

const { t, locale } = useI18n();
const currentLocale = computed(() => locale.value);

const searchQuery = ref('');
const typeFilter = ref<'all' | ExclusionType>('all');
const selectedIds = ref<string[]>([]);
const actionMessage = ref<string | null>(null);

const entries = ref<ExclusionEntry[]>([
  {
    id: 'channel:al-qalam',
    label: 'Al-Qalam Foundation',
    type: 'channel',
    parentLabel: '—',
    reason: 'Requested removal by moderation team pending review.',
    status: 'excluded',
    updatedAt: '2025-09-20T08:30:00Z'
  },
  {
    id: 'playlist:recitations-juz-amma',
    label: 'Recitations — Juz Amma (HQ)',
    type: 'playlist',
    parentLabel: 'Al-Qalam Foundation',
    reason: null,
    status: 'excluded',
    updatedAt: '2025-09-22T12:40:00Z'
  },
  {
    id: 'video:daily-halaqa-231',
    label: 'Daily Halaqa 231: Mercy and Neighbours',
    type: 'video',
    parentLabel: 'Iman Circle Live',
    reason: 'Contains unrelated promotional segment.',
    status: 'excluded',
    updatedAt: '2025-09-25T18:10:00Z'
  }
]);

const visibleEntries = computed(() => entries.value.filter((entry) => entry.status !== 'included'));

const filteredItems = computed(() => {
  const normalizedQuery = searchQuery.value.trim().toLowerCase();
  return visibleEntries.value.filter((entry) => {
    const matchesType = typeFilter.value === 'all' || entry.type === typeFilter.value;
    if (!matchesType) {
      return false;
    }
    if (!normalizedQuery) {
      return true;
    }
    return (
      entry.label.toLowerCase().includes(normalizedQuery) ||
      entry.parentLabel.toLowerCase().includes(normalizedQuery) ||
      entry.id.toLowerCase().includes(normalizedQuery)
    );
  });
});

const hasSelection = computed(() => selectedIds.value.length > 0);
const actionMessageId = 'exclusions-action-message';
const searchInputId = 'exclusions-search-input';
const addDialogTitleId = 'exclusions-dialog-title';
const addDialogDescriptionId = 'exclusions-dialog-description';
const addDialogTargetId = 'exclusions-dialog-target';
const addDialogReasonId = 'exclusions-dialog-reason';
const addDialogTypeId = 'exclusions-dialog-type';

const statusOptions: ExclusionStatus[] = ['excluded', 'pending', 'included'];

const typeOptions = computed(() => [
  { value: 'all' as const, label: t('exclusions.filter.all') },
  { value: 'channel' as const, label: t('exclusions.filter.channels') },
  { value: 'playlist' as const, label: t('exclusions.filter.playlists') },
  { value: 'video' as const, label: t('exclusions.filter.videos') }
]);

const addDialog = reactive({
  visible: false,
  label: '',
  type: 'channel' as ExclusionType,
  reason: ''
});

const addDialogRef = ref<HTMLDivElement | null>(null);
const addDialogTargetRef = ref<HTMLInputElement | null>(null);

const { activate: activateAddTrap, deactivate: deactivateAddTrap } = useFocusTrap(addDialogRef, {
  onEscape: () => {
    closeAddDialog();
  }
});

watch(filteredItems, (items) => {
  const visibleIds = new Set(items.map((item) => item.id));
  selectedIds.value = selectedIds.value.filter((id) => visibleIds.has(id));
});

function onSearchChange(event: Event) {
  const target = event.target as HTMLInputElement;
  searchQuery.value = target.value;
}

function clearSearch() {
  searchQuery.value = '';
}

function setTypeFilter(value: 'all' | ExclusionType) {
  typeFilter.value = value;
}

function isSelected(id: string) {
  return selectedIds.value.includes(id);
}

const isAllVisibleSelected = computed(() => {
  if (!filteredItems.value.length) {
    return false;
  }
  return filteredItems.value.every((entry) => selectedIds.value.includes(entry.id));
});

function toggleSelection(id: string, event: Event) {
  const target = event.target as HTMLInputElement;
  const checked = target.checked;
  if (checked) {
    if (!selectedIds.value.includes(id)) {
      selectedIds.value = [...selectedIds.value, id];
    }
  } else {
    selectedIds.value = selectedIds.value.filter((value) => value !== id);
  }
}

function toggleSelectAll(event: Event) {
  const target = event.target as HTMLInputElement;
  if (target.checked) {
    const unique = new Set([...selectedIds.value, ...filteredItems.value.map((entry) => entry.id)]);
    selectedIds.value = Array.from(unique);
  } else {
    const visibleIds = new Set(filteredItems.value.map((entry) => entry.id));
    selectedIds.value = selectedIds.value.filter((id) => !visibleIds.has(id));
  }
}

function clearSelection() {
  selectedIds.value = [];
}

function typeLabel(type: ExclusionType) {
  if (type === 'channel') {
    return t('navigation.channels');
  }
  if (type === 'playlist') {
    return t('navigation.playlists');
  }
  return t('navigation.videos');
}

function statusLabel(status: ExclusionStatus) {
  return t(`exclusions.status.${status}`);
}

function formatUpdated(value: string) {
  return formatDateTime(value, currentLocale.value);
}

function openAddDialog() {
  addDialog.visible = true;
  nextTick(() => {
    activateAddTrap({ initialFocus: addDialogTargetRef.value ?? null });
  });
}

function closeAddDialog() {
  deactivateAddTrap();
  addDialog.visible = false;
  addDialog.label = '';
  addDialog.reason = '';
  addDialog.type = 'channel';
}

function handleAdd() {
  if (!addDialog.label.trim()) {
    return;
  }
  const now = new Date().toISOString();
  const entry: ExclusionEntry = {
    id: `${addDialog.type}:${addDialog.label.trim().toLowerCase().replace(/\s+/g, '-')}-${Date.now()}`,
    label: addDialog.label.trim(),
    type: addDialog.type,
    parentLabel: addDialog.type === 'channel' ? '—' : t('exclusions.table.parentUnknown'),
    reason: addDialog.reason.trim() ? addDialog.reason.trim() : null,
    status: 'excluded',
    updatedAt: now
  };
  entries.value = [entry, ...entries.value];
  actionMessage.value = t('exclusions.toasts.added', { name: entry.label });
  closeAddDialog();
}

function setEntryStatus(entry: ExclusionEntry, status: ExclusionStatus) {
  if (entry.status === status) {
    return;
  }
  entry.status = status;
  entry.updatedAt = new Date().toISOString();
  const stateLabel = statusLabel(status);
  actionMessage.value = t('exclusions.toasts.statusChanged', { name: entry.label, state: stateLabel });
}

function handleBulkInclude() {
  if (!selectedIds.value.length) {
    return;
  }
  const set = new Set(selectedIds.value);
  entries.value = entries.value.map((entry) => {
    if (set.has(entry.id)) {
      return { ...entry, status: 'included', updatedAt: new Date().toISOString() };
    }
    return entry;
  });
  const count = selectedIds.value.length;
  actionMessage.value = t('exclusions.toasts.bulkIncluded', { count });
  clearSelection();
}
</script>

<style scoped>
.exclusions-workspace {
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

.primary {
  border: none;
  border-radius: 0.75rem;
  background: var(--color-brand);
  color: var(--color-text-inverse);
  padding: 0.65rem 1.5rem;
  font-weight: 600;
  cursor: pointer;
  transition: background 0.2s ease;
}

.primary:hover,
.primary:focus-visible {
  background: var(--color-accent);
}

.controls {
  display: flex;
  flex-wrap: wrap;
  gap: 1rem;
  align-items: center;
  justify-content: space-between;
}

.search-field {
  position: relative;
  display: flex;
  align-items: center;
  background: var(--color-surface);
  border-radius: 999px;
  padding: 0.35rem 0.75rem;
  border: 1px solid var(--color-border);
  min-width: 280px;
}

.search-field input {
  border: none;
  background: transparent;
  padding: 0.4rem 0.75rem;
  font-size: 0.95rem;
  min-width: 200px;
  color: var(--color-text-primary);
}

.search-field .clear {
  border: none;
  background: transparent;
  color: var(--color-text-secondary);
  cursor: pointer;
  font-weight: 600;
}

.search-field .clear:focus-visible {
  color: var(--color-text-primary);
}

.type-filters {
  display: inline-flex;
  gap: 0.5rem;
  background: var(--color-surface);
  border-radius: 999px;
  padding: 0.35rem;
  border: 1px solid var(--color-border);
}

.type-option {
  border: none;
  background: transparent;
  border-radius: 999px;
  padding: 0.35rem 1.1rem;
  font-weight: 600;
  color: var(--color-text-secondary);
  cursor: pointer;
  transition: background 0.2s ease, color 0.2s ease;
}

.type-option.active {
  background: var(--color-brand);
  color: var(--color-text-inverse);
}

.type-option:focus-visible {
  background: var(--color-brand-soft);
  color: var(--color-text-primary);
}

.bulk-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  background: var(--color-warning-soft);
  color: var(--color-warning);
  border-radius: 0.75rem;
  padding: 0.6rem 1rem;
  gap: 1rem;
}

.bulk-actions {
  display: flex;
  gap: 0.5rem;
}

.bulk-primary,
.bulk-secondary {
  border: none;
  border-radius: 0.75rem;
  padding: 0.5rem 1.25rem;
  font-weight: 600;
  cursor: pointer;
}

.bulk-primary {
  background: var(--color-brand);
  color: var(--color-text-inverse);
}

.bulk-primary:hover,
.bulk-primary:focus-visible {
  background: var(--color-accent);
}

.bulk-secondary {
  background: var(--color-surface);
  color: var(--color-text-primary);
}

.bulk-secondary:focus-visible {
  background: var(--color-surface-alt);
}

.table-wrapper {
  background: var(--color-surface);
  border-radius: 1rem;
  box-shadow: var(--shadow-elevated);
  overflow: hidden;
}

.exclusions-table {
  width: 100%;
  border-collapse: collapse;
  min-width: 1080px;
}

th {
  background: var(--color-surface-alt);
  color: var(--color-text-primary);
  text-align: left;
  padding: 0.75rem 1.25rem;
  font-size: 0.9rem;
  font-weight: 600;
  border-bottom: 1px solid var(--color-border);
}

td {
  padding: 0.75rem 1.25rem;
  border-bottom: 1px solid var(--color-border);
  vertical-align: top;
  font-size: 0.95rem;
  color: var(--color-text-primary);
}

.entity-cell {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}

.entity-label {
  font-weight: 600;
}

.entity-id {
  color: var(--color-text-secondary);
  font-size: 0.8rem;
  word-break: break-all;
}

.pill {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 0.2rem 0.75rem;
  border-radius: 999px;
  font-size: 0.8rem;
  font-weight: 600;
  background: var(--color-surface-alt);
  color: var(--color-text-secondary);
}

.parent-label {
  color: var(--color-text-secondary);
}

.reason-text {
  display: block;
  white-space: pre-wrap;
}

.reason-missing {
  color: var(--color-text-secondary);
  font-style: italic;
}

.status-toggle {
  display: inline-flex;
  gap: 0.5rem;
  background: var(--color-surface-alt);
  border-radius: 999px;
  padding: 0.3rem;
}

.status-option {
  border: none;
  background: transparent;
  border-radius: 999px;
  padding: 0.35rem 0.9rem;
  font-weight: 600;
  color: var(--color-text-secondary);
  cursor: pointer;
  transition: background 0.2s ease, color 0.2s ease;
}

.status-option.active {
  background: var(--color-brand);
  color: var(--color-text-inverse);
}

.status-option:focus-visible {
  background: var(--color-brand-soft);
  color: var(--color-text-primary);
}

.empty {
  text-align: center;
  padding: 2rem 0;
  color: var(--color-text-secondary);
}

.action-message {
  margin: 0;
  color: var(--color-text-secondary);
  font-size: 0.95rem;
}

.modal-backdrop {
  position: fixed;
  inset: 0;
  background: var(--color-overlay);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 1.5rem;
  z-index: 50;
}

.modal {
  background: var(--color-surface);
  border-radius: 1rem;
  padding: 1.75rem;
  max-width: 540px;
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
  margin-bottom: 0.35rem;
}

.modal input,
.modal select,
.modal textarea {
  width: 100%;
  border-radius: 0.75rem;
  border: 1px solid var(--color-border);
  padding: 0.6rem 0.75rem;
  margin-bottom: 1rem;
  font: inherit;
}

.modal textarea {
  resize: vertical;
  min-height: 96px;
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

.modal-secondary:focus-visible {
  background: var(--color-surface-alt);
}

.modal-primary {
  background: var(--color-brand);
  color: var(--color-text-inverse);
}

.modal-primary:hover,
.modal-primary:focus-visible {
  background: var(--color-accent);
}

.visually-hidden {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  border: 0;
}

@media (max-width: 960px) {
  .exclusions-table {
    min-width: 960px;
  }
}
</style>
