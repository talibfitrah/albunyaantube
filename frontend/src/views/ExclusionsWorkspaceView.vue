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
          type="search"
          @input="onSearchChange"
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
        <button
          type="button"
          class="bulk-primary"
          @click="handleBulkRemove"
          :disabled="isBulkProcessing"
        >
          {{ isBulkProcessing ? t('exclusions.actions.removing') : t('exclusions.actions.removeSelected') }}
        </button>
        <button type="button" class="bulk-secondary" @click="clearSelection">
          {{ t('exclusions.actions.clearSelection') }}
        </button>
      </div>
    </div>

    <div class="table-wrapper" role="region" aria-live="polite">
      <div v-if="isLoading" class="table-state">
        {{ t('exclusions.table.loading') }}
      </div>
      <div v-else-if="loadError" class="table-state error-state">
        <span>{{ loadError }}</span>
        <button type="button" class="retry" @click="reload">{{ t('exclusions.table.retry') }}</button>
      </div>
      <table v-else class="exclusions-table">
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
            <th scope="col">{{ t('exclusions.table.columns.created') }}</th>
            <th scope="col">{{ t('exclusions.table.columns.actions') }}</th>
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
                :aria-label="t('exclusions.table.rowSelect', { name: entry.excludeId })"
                :checked="isSelected(entry.id)"
                @change="toggleSelection(entry.id, $event)"
              />
            </td>
            <td>
              <div class="entity-cell">
                <span class="entity-label">{{ entry.excludeId }}</span>
                <span class="entity-subtle">{{ entitySummary(entry) }}</span>
              </div>
            </td>
            <td>
              <span class="pill" :data-type="entry.excludeType">{{ resourceTypeLabel(entry.excludeType) }}</span>
            </td>
            <td>
              <div class="entity-cell">
                <span class="entity-label">{{ entry.parentId }}</span>
                <span class="entity-subtle">{{ parentTypeLabel(entry.parentType) }}</span>
              </div>
            </td>
            <td>
              <span>{{ entry.reason || t('exclusions.table.noReason') }}</span>
            </td>
            <td>
              <div class="timestamp">
                <time :datetime="entry.createdAt">{{ formatUpdated(entry.createdAt) }}</time>
                <span class="entity-subtle">{{ entry.createdBy.email }}</span>
              </div>
            </td>
            <td>
              <button type="button" class="link-action" @click="openViewDetails(entry)">
                {{ t('exclusions.actions.viewDetails') }}
              </button>
              <span aria-hidden="true">·</span>
              <button type="button" class="link-action" @click="openEditDialog(entry)">
                {{ t('exclusions.actions.edit') }}
              </button>
              <span aria-hidden="true">·</span>
              <button
                type="button"
                class="link-action"
                @click="handleRemove(entry.id)"
                :disabled="isRemoving(entry.id)"
              >
                {{ isRemoving(entry.id) ? t('exclusions.actions.removing') : t('exclusions.actions.remove') }}
              </button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <div v-if="!isLoading && !loadError && filteredItems.length" class="table-footer">
      <button type="button" class="pager" @click="previous" :disabled="!hasPrevious || isLoading">
        {{ t('exclusions.pagination.previous') }}
      </button>
      <div class="footer-status">{{ paginationSummary }}</div>
      <button type="button" class="pager" @click="next" :disabled="!hasNext || isLoading">
        {{ t('exclusions.pagination.next') }}
      </button>
    </div>

    <p v-if="actionMessage" :id="actionMessageId" class="action-message" role="status" aria-live="polite">
      {{ actionMessage }}
    </p>
  </section>

  <!-- Detail Modals -->
  <ChannelDetailModal
    v-if="selectedItem && channelModalOpen"
    :open="channelModalOpen"
    :channel-id="selectedItem.id"
    :channel-youtube-id="selectedItem.youtubeId"
    @close="closeChannelModal"
    @updated="handleModalUpdated"
  />

  <PlaylistDetailModal
    v-if="selectedItem && playlistModalOpen"
    :open="playlistModalOpen"
    :playlist-id="selectedItem.id"
    :playlist-youtube-id="selectedItem.youtubeId"
    @close="closePlaylistModal"
    @updated="handleModalUpdated"
  />

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
      <form @submit.prevent="handleSubmit">
        <div class="form-grid">
          <label class="modal-label" :for="addDialogParentTypeId">
            {{ t('exclusions.dialog.parentTypeLabel') }}
          </label>
          <select :id="addDialogParentTypeId" v-model="addDialog.parentType" :disabled="isEditMode()">
            <option value="CHANNEL">{{ t('exclusions.dialog.parentChannel') }}</option>
            <option value="PLAYLIST">{{ t('exclusions.dialog.parentPlaylist') }}</option>
          </select>

          <label class="modal-label" :for="addDialogParentId">
            {{ t('exclusions.dialog.parentIdLabel') }}
          </label>
          <input
            :id="addDialogParentId"
            v-model.trim="addDialog.parentId"
            type="text"
            required
            autocomplete="off"
            :disabled="isEditMode()"
          />

          <label class="modal-label" :for="addDialogTypeId">
            {{ t('exclusions.dialog.typeLabel') }}
          </label>
          <select :id="addDialogTypeId" v-model="addDialog.excludeType" :disabled="isEditMode()">
            <option value="PLAYLIST">{{ t('navigation.playlists') }}</option>
            <option value="VIDEO">{{ t('navigation.videos') }}</option>
          </select>

          <label class="modal-label" :for="addDialogTargetId">
            {{ t('exclusions.dialog.targetLabel') }}
          </label>
          <input
            :id="addDialogTargetId"
            ref="addDialogTargetRef"
            v-model.trim="addDialog.excludeId"
            type="text"
            required
            autocomplete="off"
            :disabled="isEditMode()"
          />
        </div>

        <label class="modal-label" :for="addDialogReasonId">
          {{ t('exclusions.dialog.reasonLabel') }}
        </label>
        <textarea :id="addDialogReasonId" v-model.trim="addDialog.reason" rows="3" required></textarea>

        <p v-if="formError" class="form-error" role="alert">{{ formError }}</p>

        <div class="modal-actions">
          <button type="button" class="modal-secondary" @click="closeAddDialog" :disabled="isSubmitting">
            {{ t('exclusions.actions.cancel') }}
          </button>
          <button type="submit" class="modal-primary" :disabled="isSubmitting">
            {{ submitButtonLabel() }}
          </button>
        </div>
      </form>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { formatDateTime } from '@/utils/formatters';
import { useFocusTrap } from '@/composables/useFocusTrap';
import { useCursorPagination } from '@/composables/useCursorPagination';
import {
  fetchExclusionsPage,
  removeExclusion,
  createExclusion,
  updateExclusion
} from '@/services/exclusions';
import type { Exclusion, ExclusionParentType, ExclusionResourceType } from '@/types/exclusions';
import { emitAuditEvent } from '@/services/audit';
import ChannelDetailModal from '@/components/exclusions/ChannelDetailModal.vue';
import PlaylistDetailModal from '@/components/exclusions/PlaylistDetailModal.vue';

type TypeFilterValue = 'all' | 'parent:CHANNEL' | 'parent:PLAYLIST' | 'exclude:PLAYLIST' | 'exclude:VIDEO';

const { t, locale } = useI18n();
const currentLocale = computed(() => locale.value);

const searchQuery = ref('');
const activeSearch = ref('');
const typeFilter = ref<TypeFilterValue>('all');
const selectedIds = ref<string[]>([]);
const actionMessage = ref<string | null>(null);
const isSubmitting = ref(false);
const formError = ref<string | null>(null);
const isBulkProcessing = ref(false);
const removingIds = ref<string[]>([]);

const actionMessageId = 'exclusions-action-message';
const searchInputId = 'exclusions-search-input';
const addDialogTitleId = 'exclusions-dialog-title';
const addDialogDescriptionId = 'exclusions-dialog-description';
const addDialogParentTypeId = 'exclusions-dialog-parent-type';
const addDialogParentId = 'exclusions-dialog-parent-id';
const addDialogTargetId = 'exclusions-dialog-target';
const addDialogReasonId = 'exclusions-dialog-reason';
const addDialogTypeId = 'exclusions-dialog-type';

const typeOptions = computed(() => [
  { value: 'all' as const, label: t('exclusions.filter.all') },
  { value: 'parent:CHANNEL' as const, label: t('exclusions.filter.channels') },
  { value: 'parent:PLAYLIST' as const, label: t('exclusions.filter.parentPlaylist') },
  { value: 'exclude:PLAYLIST' as const, label: t('exclusions.filter.excludePlaylist') },
  { value: 'exclude:VIDEO' as const, label: t('exclusions.filter.excludeVideo') }
]);

const addDialog = reactive({
  visible: false,
  parentType: 'CHANNEL' as ExclusionParentType,
  parentId: '',
  excludeType: 'PLAYLIST' as ExclusionResourceType,
  excludeId: '',
  reason: ''
});

const mode = ref<'create' | 'edit'>('create');
const editingId = ref<string | null>(null);

const addDialogRef = ref<HTMLDivElement | null>(null);
const addDialogTargetRef = ref<HTMLInputElement | null>(null);

// Detail modals state
const channelModalOpen = ref(false);
const playlistModalOpen = ref(false);
const selectedItem = ref<{ id: string; youtubeId: string } | null>(null);

const { activate: activateAddTrap, deactivate: deactivateAddTrap } = useFocusTrap(addDialogRef, {
  onEscape: () => {
    if (!isSubmitting.value) {
      closeAddDialog();
    }
  }
});

const pagination = useCursorPagination<Exclusion>(async (cursor, limit) => {
  const descriptor = parseFilter(typeFilter.value);
  return fetchExclusionsPage({
    cursor,
    limit,
    parentType: descriptor?.kind === 'parent' ? descriptor.value : undefined,
    excludeType: descriptor?.kind === 'exclude' ? descriptor.value : undefined,
    search: activeSearch.value || undefined
  });
});

const { items, isLoading, error, load, next, previous, hasNext, hasPrevious, pageInfo } = pagination;
const entries = items;
const loadError = computed(() => error.value);
const hasSelection = computed(() => selectedIds.value.length > 0);
const filteredItems = computed(() => entries.value);
const isAllVisibleSelected = computed(() => {
  if (!entries.value.length) {
    return false;
  }
  const visibleIds = entries.value.map((entry) => entry.id);
  return visibleIds.every((id) => selectedIds.value.includes(id));
});

const paginationSummary = computed(() => {
  if (!pageInfo.value) {
    return '';
  }
  const formatter = new Intl.NumberFormat(currentLocale.value);
  const count = formatter.format(entries.value.length);
  const limit = formatter.format(pageInfo.value.limit ?? entries.value.length);
  return t('exclusions.pagination.showing', { count, limit });
});

watch(entries, (items) => {
  const visibleIds = new Set(items.map((entry) => entry.id));
  selectedIds.value = selectedIds.value.filter((id) => visibleIds.has(id));
});

watch(typeFilter, () => {
  scheduleReload();
});

watch(searchQuery, () => {
  scheduleReload();
});

onMounted(async () => {
  await load(null, 'reset');
});

let reloadTimeout: ReturnType<typeof setTimeout> | null = null;

function scheduleReload() {
  if (reloadTimeout) {
    clearTimeout(reloadTimeout);
  }
  reloadTimeout = setTimeout(() => {
    activeSearch.value = searchQuery.value.trim();
    void load(null, 'reset');
  }, 250);
}

async function reload() {
  await load(null, 'reset');
}

onBeforeUnmount(() => {
  if (reloadTimeout) {
    clearTimeout(reloadTimeout);
  }
});

function parseFilter(value: TypeFilterValue):
  | { kind: 'parent'; value: ExclusionParentType }
  | { kind: 'exclude'; value: ExclusionResourceType }
  | null {
  if (value === 'all') {
    return null;
  }
  const [kind, raw] = value.split(':');
  if (kind === 'parent') {
    return { kind: 'parent', value: raw as ExclusionParentType };
  }
  return { kind: 'exclude', value: raw as ExclusionResourceType };
}

function onSearchChange(event: Event) {
  const target = event.target as HTMLInputElement;
  searchQuery.value = target.value;
}

function clearSearch() {
  searchQuery.value = '';
}

function setTypeFilter(value: TypeFilterValue) {
  typeFilter.value = value;
}

function isSelected(id: string) {
  return selectedIds.value.includes(id);
}

function toggleSelection(id: string, event: Event) {
  const target = event.target as HTMLInputElement;
  if (target.checked) {
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
    const ids = entries.value.map((entry) => entry.id);
    selectedIds.value = Array.from(new Set([...selectedIds.value, ...ids]));
  } else {
    const visible = new Set(entries.value.map((entry) => entry.id));
    selectedIds.value = selectedIds.value.filter((id) => !visible.has(id));
  }
}

function clearSelection() {
  selectedIds.value = [];
}

function entitySummary(entry: Exclusion) {
  return entry.excludeType === 'PLAYLIST'
    ? t('exclusions.table.playlistSummary', { id: entry.excludeId })
    : t('exclusions.table.videoSummary', { id: entry.excludeId });
}

function resourceTypeLabel(type: ExclusionResourceType) {
  return type === 'PLAYLIST' ? t('navigation.playlists') : t('navigation.videos');
}

function parentTypeLabel(type: ExclusionParentType) {
  return type === 'CHANNEL' ? t('navigation.channels') : t('navigation.playlists');
}

function formatUpdated(value: string) {
  return formatDateTime(value, currentLocale.value);
}

function isRemoving(id: string) {
  return removingIds.value.includes(id);
}

function openEditDialog(entry: Exclusion) {
  mode.value = 'edit';
  editingId.value = entry.id;
  addDialog.parentType = entry.parentType;
  addDialog.parentId = entry.parentId;
  addDialog.excludeType = entry.excludeType;
  addDialog.excludeId = entry.excludeId;
  addDialog.reason = entry.reason;
  openDialog(true);
}

function openDialog(skipFocus = false) {
  formError.value = null;
  addDialog.visible = true;
  nextTick(() => {
    activateAddTrap({ initialFocus: skipFocus ? undefined : addDialogTargetRef.value ?? null });
  });
}

function resetDialog() {
  addDialog.parentType = 'CHANNEL';
  addDialog.parentId = '';
  addDialog.excludeType = 'PLAYLIST';
  addDialog.excludeId = '';
  addDialog.reason = '';
}

function closeAddDialog() {
  deactivateAddTrap();
  addDialog.visible = false;
  resetDialog();
  mode.value = 'create';
  editingId.value = null;
  isSubmitting.value = false;
}

async function handleSubmit() {
  if (isSubmitting.value) {
    return;
  }
  if (!addDialog.parentId || !addDialog.excludeId || !addDialog.reason) {
    formError.value = t('exclusions.dialog.validation');
    return;
  }
  isSubmitting.value = true;
  formError.value = null;
  try {
    if (mode.value === 'create') {
      const payload = {
        parentType: addDialog.parentType,
        parentId: addDialog.parentId.trim(),
        excludeType: addDialog.excludeType,
        excludeId: addDialog.excludeId.trim(),
        reason: addDialog.reason.trim()
      };
      const created = await createExclusion(payload);
      emitAuditEvent({
        name: 'exclusions:create',
        exclusionId: created.id,
        timestamp: new Date().toISOString(),
        metadata: {
          parentType: created.parentType,
          parentId: created.parentId,
          excludeType: created.excludeType,
          excludeId: created.excludeId
        }
      });
      await load(null, 'reset');
      actionMessage.value = t('exclusions.toasts.added', { name: created.excludeId });
      closeAddDialog();
    } else if (editingId.value) {
      const updated = await updateExclusion(editingId.value, { reason: addDialog.reason.trim() });
      emitAuditEvent({
        name: 'exclusions:update',
        exclusionId: updated.id,
        timestamp: new Date().toISOString(),
        metadata: { reason: updated.reason }
      });
      await reloadCurrentPage();
      actionMessage.value = t('exclusions.toasts.updated');
      closeAddDialog();
    }
  } catch (err) {
    formError.value = err instanceof Error ? err.message : t('exclusions.errors.createFailed');
  } finally {
    isSubmitting.value = false;
  }
}

async function handleRemove(id: string) {
  if (isRemoving(id)) {
    return;
  }
  removingIds.value = [...removingIds.value, id];
  try {
    await removeExclusion(id);
    emitAuditEvent({
      name: 'exclusions:delete',
      exclusionId: id,
      timestamp: new Date().toISOString()
    });
    await reloadCurrentPage();
    selectedIds.value = selectedIds.value.filter((value) => value !== id);
    actionMessage.value = t('exclusions.toasts.removed');
  } catch (err) {
    actionMessage.value = err instanceof Error ? err.message : t('exclusions.errors.removeFailed');
  } finally {
    removingIds.value = removingIds.value.filter((value) => value !== id);
  }
}

async function handleBulkRemove() {
  if (!selectedIds.value.length || isBulkProcessing.value) {
    return;
  }
  isBulkProcessing.value = true;
  const ids = [...selectedIds.value];
  try {
    await Promise.all(ids.map((id) => removeExclusion(id)));
    emitAuditEvent({
      name: 'exclusions:delete-many',
      timestamp: new Date().toISOString(),
      metadata: { count: ids.length }
    });
    await reloadCurrentPage();
    selectedIds.value = [];
    actionMessage.value = t('exclusions.toasts.bulkRemoved', { count: ids.length });
  } catch (err) {
    actionMessage.value = err instanceof Error ? err.message : t('exclusions.errors.removeFailed');
  } finally {
    isBulkProcessing.value = false;
  }
}

async function reloadCurrentPage() {
  const cursor = pageInfo.value?.cursor ?? null;
  await load(cursor, 'replace');
}

function isEditMode() {
  return mode.value === 'edit';
}

function submitButtonLabel() {
  if (isSubmitting.value) {
    return mode.value === 'create'
      ? t('exclusions.actions.creating')
      : t('exclusions.actions.updating');
  }
  return mode.value === 'create' ? t('exclusions.actions.create') : t('exclusions.actions.update');
}

function openAddDialog() {
  mode.value = 'create';
  editingId.value = null;
  resetDialog();
  openDialog();
}

function openViewDetails(entry: Exclusion) {
  selectedItem.value = {
    id: entry.parentId,
    youtubeId: entry.parentYoutubeId || entry.parentId
  };

  if (entry.parentType === 'CHANNEL') {
    channelModalOpen.value = true;
  } else {
    playlistModalOpen.value = true;
  }
}

function closeChannelModal() {
  channelModalOpen.value = false;
  selectedItem.value = null;
}

function closePlaylistModal() {
  playlistModalOpen.value = false;
  selectedItem.value = null;
}

async function handleModalUpdated() {
  // Refresh the current page after exclusions are updated
  await reloadCurrentPage();
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

.table-state {
  padding: 2rem;
  text-align: center;
  color: var(--color-text-secondary);
}

.error-state {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
  align-items: center;
  color: var(--color-danger);
}

.retry {
  border: none;
  border-radius: 0.5rem;
  padding: 0.5rem 1rem;
  background: var(--color-brand);
  color: var(--color-text-inverse);
  cursor: pointer;
}

.retry:focus-visible,
.retry:hover {
  background: var(--color-accent);
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
  gap: 0.2rem;
}

.entity-label {
  font-weight: 600;
}

.entity-subtle {
  color: var(--color-text-secondary);
  font-size: 0.8rem;
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

.timestamp {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
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

.link-action {
  background: transparent;
  border: none;
  color: var(--color-brand);
  font-weight: 600;
  cursor: pointer;
  padding: 0;
}

.link-action:disabled {
  opacity: 0.6;
  cursor: not-allowed;
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
  max-width: 560px;
  width: 100%;
  box-shadow: var(--shadow-elevated);
}

.modal-description {
  color: var(--color-text-secondary);
  margin: 0.5rem 0 1.5rem;
}

.form-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: 0.75rem 1rem;
  margin-bottom: 1rem;
}

.modal-label {
  font-weight: 600;
  color: var(--color-text-primary);
}

.modal select,
.modal input,
.modal textarea {
  width: 100%;
  border-radius: 0.75rem;
  border: 1px solid var(--color-border);
  padding: 0.6rem 0.75rem;
  font: inherit;
}

.modal textarea {
  resize: vertical;
  min-height: 96px;
  margin-top: 0.25rem;
}

.form-error {
  color: var(--color-danger);
  margin: 0.5rem 0 0;
  font-size: 0.9rem;
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
