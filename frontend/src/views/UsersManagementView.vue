<template>
  <section class="users-view">
    <header class="workspace-header">
      <div>
        <h1>{{ t('users.heading') }}</h1>
        <p>{{ t('users.description') }}</p>
      </div>
      <div class="header-actions">
        <label class="sr-only" for="user-search">{{ t('users.search.label') }}</label>
        <div class="search-field">
          <input
            id="user-search"
            ref="searchInputRef"
            v-model="searchQuery"
            type="search"
            class="search-input"
            :placeholder="t('users.search.placeholder')"
            @input="onSearchChange"
          />
          <button v-if="searchQuery" type="button" class="clear" @click="clearSearch">
            {{ t('users.search.clear') }}
          </button>
        </div>
        <button type="button" class="primary" @click="openCreateDialog">
          {{ t('users.actions.add') }}
        </button>
      </div>
    </header>

    <div v-if="actionMessage" class="action-message" role="status">{{ actionMessage }}</div>
    <div v-if="actionError" class="action-error" role="alert">{{ actionError }}</div>

    <div class="filters">
      <label class="filter">
        <span>{{ t('users.filters.role') }}</span>
        <select v-model="roleFilter" @change="handleFilterChange">
          <option value="all">{{ t('users.filters.roleAll') }}</option>
          <option value="ADMIN">{{ t('users.roles.admin') }}</option>
          <option value="MODERATOR">{{ t('users.roles.moderator') }}</option>
        </select>
      </label>
      <label class="filter">
        <span>{{ t('users.filters.status') }}</span>
        <select v-model="statusFilter" @change="handleFilterChange">
          <option value="all">{{ t('users.filters.statusAll') }}</option>
          <option value="ACTIVE">{{ t('users.status.active') }}</option>
          <option value="DISABLED">{{ t('users.status.disabled') }}</option>
        </select>
      </label>
    </div>

    <div class="table-wrapper" role="region" aria-live="polite">
      <div v-if="loadError" class="error-state">
        <p>{{ t('users.table.error') }}</p>
        <button type="button" class="retry" :disabled="isLoading" @click="reload">
          {{ t('users.table.retry') }}
        </button>
      </div>
      <table v-else class="data-table">
        <thead>
          <tr>
            <th scope="col">{{ t('users.columns.email') }}</th>
            <th scope="col">{{ t('users.columns.roles') }}</th>
            <th scope="col">{{ t('users.columns.status') }}</th>
            <th scope="col">{{ t('users.columns.lastLogin') }}</th>
            <th scope="col">{{ t('users.columns.created') }}</th>
            <th scope="col" class="actions-column">{{ t('users.columns.actions') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="isLoading && !users.length">
            <td :colspan="6">
              <div class="skeleton-stack" aria-hidden="true">
                <div v-for="index in 5" :key="`skeleton-${index}`" class="skeleton-row"></div>
              </div>
            </td>
          </tr>
          <tr v-else-if="!users.length">
            <td :colspan="6" class="empty-state">{{ t('users.table.empty') }}</td>
          </tr>
          <tr v-for="user in users" :key="user.id">
            <td>
              <div class="user-email">{{ user.email }}</div>
              <div class="user-id">{{ user.id }}</div>
            </td>
            <td>
              <ul class="role-tags">
                <li v-for="role in user.roles" :key="role" class="role-tag">{{ roleLabel(role) }}</li>
              </ul>
            </td>
            <td>
              <span :class="['status-badge', user.status === 'ACTIVE' ? 'status-active' : 'status-disabled']">
                {{ statusLabel(user.status) }}
              </span>
            </td>
            <td>{{ formatMaybeDate(user.lastLoginAt) }}</td>
            <td>{{ formatDateTime(user.createdAt) }}</td>
            <td class="actions-cell">
              <button
                type="button"
                class="action"
                :disabled="isLoading || editState.isSubmitting"
                @click="openEditDialog(user)"
              >
                {{ t('users.actions.edit') }}
              </button>
              <button
                v-if="user.status === 'ACTIVE'"
                type="button"
                class="action danger"
                :disabled="isLoading || busyUserId === user.id"
                @click="handleDeactivate(user)"
              >
                {{ busyUserId === user.id ? t('users.actions.deactivating') : t('users.actions.deactivate') }}
              </button>
              <button
                v-else
                type="button"
                class="action"
                :disabled="isLoading || busyUserId === user.id"
                @click="handleActivate(user)"
              >
                {{ busyUserId === user.id ? t('users.actions.activating') : t('users.actions.activate') }}
              </button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <footer class="table-footer">
      <button type="button" class="pager" :disabled="!hasPrevious || isLoading" @click="previous">
        {{ t('users.pagination.previous') }}
      </button>
      <div class="footer-status">
        <span v-if="isLoading">{{ t('users.table.loading') }}</span>
        <span v-else>{{ paginationSummary }}</span>
      </div>
      <button type="button" class="pager" :disabled="!hasNext || isLoading" @click="next">
        {{ t('users.pagination.next') }}
      </button>
    </footer>

    <div v-if="createState.visible" class="modal-backdrop">
      <div
        ref="createDialogRef"
        class="modal"
        role="dialog"
        aria-modal="true"
        :aria-labelledby="createDialogTitleId"
        :aria-describedby="createDialogDescriptionId"
        tabindex="-1"
      >
        <h2 :id="createDialogTitleId">{{ t('users.dialogs.create.title') }}</h2>
        <p :id="createDialogDescriptionId" class="modal-description">
          {{ t('users.dialogs.create.description') }}
        </p>
        <form @submit.prevent="handleCreate">
          <label class="modal-label" :for="createEmailId">{{ t('users.dialogs.create.email') }}</label>
          <input
            :id="createEmailId"
            ref="createEmailRef"
            v-model="createState.email"
            type="email"
            class="modal-input"
            :disabled="createState.isSubmitting"
            required
          />
          <fieldset class="modal-fieldset">
            <legend>{{ t('users.dialogs.create.roles') }}</legend>
            <div class="checkbox-list">
              <label v-for="role in roleOptions" :key="role" class="checkbox-item">
                <input
                  type="checkbox"
                  :value="role"
                  :checked="createState.roles.includes(role)"
                  :disabled="createState.isSubmitting"
                  @change="toggleCreateRole(role, $event)"
                />
                <span>{{ roleLabel(role) }}</span>
              </label>
            </div>
          </fieldset>
          <p v-if="createState.error" class="form-error" role="alert">{{ createState.error }}</p>
          <div class="modal-actions">
            <button type="button" class="modal-secondary" :disabled="createState.isSubmitting" @click="closeCreateDialog">
              {{ t('users.dialogs.actions.cancel') }}
            </button>
            <button type="submit" class="modal-primary" :disabled="createState.isSubmitting">
              {{ createState.isSubmitting ? t('users.dialogs.create.submitting') : t('users.dialogs.create.submit') }}
            </button>
          </div>
        </form>
      </div>
    </div>

    <div v-if="editState.visible" class="modal-backdrop">
      <div
        ref="editDialogRef"
        class="modal"
        role="dialog"
        aria-modal="true"
        :aria-labelledby="editDialogTitleId"
        :aria-describedby="editDialogDescriptionId"
        tabindex="-1"
      >
        <h2 :id="editDialogTitleId">{{ t('users.dialogs.edit.title', { email: editingUser?.email ?? '' }) }}</h2>
        <p :id="editDialogDescriptionId" class="modal-description">
          {{ t('users.dialogs.edit.description') }}
        </p>
        <form @submit.prevent="handleEdit">
          <fieldset class="modal-fieldset">
            <legend>{{ t('users.dialogs.edit.roles') }}</legend>
            <div class="checkbox-list">
              <label v-for="role in roleOptions" :key="`edit-${role}`" class="checkbox-item">
                <input
                  type="checkbox"
                  :value="role"
                  :checked="editState.roles.includes(role)"
                  :disabled="editState.isSubmitting"
                  @change="toggleEditRole(role, $event)"
                />
                <span>{{ roleLabel(role) }}</span>
              </label>
            </div>
          </fieldset>
          <fieldset class="modal-fieldset">
            <legend>{{ t('users.dialogs.edit.status') }}</legend>
            <label class="radio-item">
              <input
                type="radio"
                value="ACTIVE"
                v-model="editState.status"
                :disabled="editState.isSubmitting"
              />
              <span>{{ t('users.status.active') }}</span>
            </label>
            <label class="radio-item">
              <input
                type="radio"
                value="DISABLED"
                v-model="editState.status"
                :disabled="editState.isSubmitting"
              />
              <span>{{ t('users.status.disabled') }}</span>
            </label>
          </fieldset>
          <p v-if="editState.error" class="form-error" role="alert">{{ editState.error }}</p>
          <div class="modal-actions">
            <button type="button" class="modal-secondary" :disabled="editState.isSubmitting" @click="closeEditDialog">
              {{ t('users.dialogs.actions.cancel') }}
            </button>
            <button type="submit" class="modal-primary" :disabled="editState.isSubmitting">
              {{ editState.isSubmitting ? t('users.dialogs.edit.submitting') : t('users.dialogs.edit.submit') }}
            </button>
          </div>
        </form>
      </div>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { useCursorPagination } from '@/composables/useCursorPagination';
import { useFocusTrap } from '@/composables/useFocusTrap';
import {
  createAdminUser,
  deleteAdminUser,
  fetchAdminUsersPage,
  updateAdminUser
} from '@/services/adminUsers';
import type { AdminRole, AdminUser, AdminUserStatus } from '@/types/admin';
import { formatDateTime as baseFormatDateTime } from '@/utils/formatters';

const { t, locale } = useI18n();
const currentLocale = computed(() => locale.value);

const roleOptions: AdminRole[] = ['ADMIN', 'MODERATOR'];

const searchQuery = ref('');
const activeSearch = ref('');
const roleFilter = ref<'all' | AdminRole>('all');
const statusFilter = ref<'all' | AdminUserStatus>('all');
const actionMessage = ref<string | null>(null);
const actionError = ref<string | null>(null);
const busyUserId = ref<string | null>(null);

const pagination = useCursorPagination<AdminUser>(async (cursor, limit) => {
  return fetchAdminUsersPage({
    cursor,
    limit,
    search: activeSearch.value || undefined,
    role: roleFilter.value === 'all' ? null : roleFilter.value,
    status: statusFilter.value === 'all' ? null : statusFilter.value
  });
});

const { items, isLoading, error, load, next, previous, hasNext, hasPrevious, pageInfo } = pagination;
const users = items;
const loadError = computed(() => error.value);

const searchInputRef = ref<HTMLInputElement | null>(null);

const createDialogRef = ref<HTMLDivElement | null>(null);
const createEmailRef = ref<HTMLInputElement | null>(null);
const createDialogTitleId = 'create-user-title';
const createDialogDescriptionId = 'create-user-description';
const createEmailId = 'create-user-email';

const editDialogRef = ref<HTMLDivElement | null>(null);
const editDialogTitleId = 'edit-user-title';
const editDialogDescriptionId = 'edit-user-description';

const createState = reactive({
  visible: false,
  email: '',
  roles: [] as AdminRole[],
  isSubmitting: false,
  error: null as string | null
});

const editState = reactive({
  visible: false,
  roles: [] as AdminRole[],
  status: 'ACTIVE' as AdminUserStatus,
  isSubmitting: false,
  error: null as string | null
});

const editingUser = ref<AdminUser | null>(null);

const { activate: activateCreateTrap, deactivate: deactivateCreateTrap } = useFocusTrap(createDialogRef, {
  onEscape: () => {
    if (!createState.isSubmitting) {
      closeCreateDialog();
    }
  }
});

const { activate: activateEditTrap, deactivate: deactivateEditTrap } = useFocusTrap(editDialogRef, {
  onEscape: () => {
    if (!editState.isSubmitting) {
      closeEditDialog();
    }
  }
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

function handleFilterChange() {
  void load(null, 'reset');
}

watch(searchQuery, () => {
  scheduleReload();
});

onMounted(async () => {
  await load(null, 'reset');
});

onBeforeUnmount(() => {
  if (reloadTimeout) {
    clearTimeout(reloadTimeout);
  }
});

async function reload() {
  await load(null, 'reset');
}

function onSearchChange() {
  if (!searchQuery.value) {
    scheduleReload();
  }
}

function clearSearch() {
  searchQuery.value = '';
  scheduleReload();
  searchInputRef.value?.focus();
}

function roleLabel(role: AdminRole) {
  return role === 'ADMIN' ? t('users.roles.admin') : t('users.roles.moderator');
}

function statusLabel(status: AdminUserStatus) {
  return status === 'ACTIVE' ? t('users.status.active') : t('users.status.disabled');
}

function formatMaybeDate(value: string | null) {
  if (!value) {
    return t('users.table.never');
  }
  return formatDateTime(value);
}

function formatDateTime(value: string) {
  return baseFormatDateTime(value, currentLocale.value);
}

function openCreateDialog() {
  createState.visible = true;
  createState.email = '';
  createState.roles = ['ADMIN'];
  createState.error = null;
  actionError.value = null;
  nextTick(() => {
    activateCreateTrap({ initialFocus: createEmailRef.value ?? null });
  });
}

function closeCreateDialog() {
  createState.visible = false;
  createState.isSubmitting = false;
  deactivateCreateTrap();
}

function toggleCreateRole(role: AdminRole, event: Event) {
  const target = event.target as HTMLInputElement;
  if (target.checked) {
    if (!createState.roles.includes(role)) {
      createState.roles = [...createState.roles, role];
    }
  } else {
    createState.roles = createState.roles.filter((value) => value !== role);
  }
}

async function handleCreate() {
  if (createState.isSubmitting) {
    return;
  }
  if (!createState.email.trim()) {
    createState.error = t('users.dialogs.create.errors.email');
    return;
  }
  if (createState.roles.length === 0) {
    createState.error = t('users.dialogs.create.errors.roles');
    return;
  }

  createState.error = null;
  createState.isSubmitting = true;
  try {
    await createAdminUser({
      email: createState.email.trim(),
      roles: createState.roles
    });
    actionMessage.value = t('users.toasts.created', { email: createState.email.trim() });
    await reload();
    closeCreateDialog();
  } catch (err) {
    createState.error = err instanceof Error ? err.message : t('users.dialogs.create.errors.generic');
  } finally {
    createState.isSubmitting = false;
  }
}

function openEditDialog(user: AdminUser) {
  editingUser.value = user;
  editState.visible = true;
  editState.roles = [...user.roles];
  editState.status = user.status;
  editState.error = null;
  actionError.value = null;
  nextTick(() => {
    activateEditTrap({ initialFocus: editDialogRef.value ?? null });
  });
}

function closeEditDialog() {
  editState.visible = false;
  editState.isSubmitting = false;
  deactivateEditTrap();
}

function toggleEditRole(role: AdminRole, event: Event) {
  const target = event.target as HTMLInputElement;
  if (target.checked) {
    if (!editState.roles.includes(role)) {
      editState.roles = [...editState.roles, role];
    }
  } else {
    editState.roles = editState.roles.filter((value) => value !== role);
  }
}

async function handleEdit() {
  if (!editingUser.value || editState.isSubmitting) {
    return;
  }
  if (editState.roles.length === 0) {
    editState.error = t('users.dialogs.edit.errors.roles');
    return;
  }
  editState.error = null;
  editState.isSubmitting = true;
  try {
    await updateAdminUser(editingUser.value.id, {
      roles: editState.roles,
      status: editState.status
    });
    actionMessage.value = t('users.toasts.updated', { email: editingUser.value.email });
    await reload();
    closeEditDialog();
  } catch (err) {
    editState.error = err instanceof Error ? err.message : t('users.dialogs.edit.errors.generic');
  } finally {
    editState.isSubmitting = false;
  }
}

async function handleDeactivate(user: AdminUser) {
  if (busyUserId.value === user.id) {
    return;
  }
  busyUserId.value = user.id;
  actionError.value = null;
  try {
    await deleteAdminUser(user.id);
    actionMessage.value = t('users.toasts.deactivated', { email: user.email });
    await reload();
  } catch (err) {
    actionError.value = err instanceof Error ? err.message : t('users.errors.deactivate');
  } finally {
    busyUserId.value = null;
  }
}

async function handleActivate(user: AdminUser) {
  if (busyUserId.value === user.id) {
    return;
  }
  busyUserId.value = user.id;
  actionError.value = null;
  try {
    await updateAdminUser(user.id, {
      roles: user.roles,
      status: 'ACTIVE'
    });
    actionMessage.value = t('users.toasts.activated', { email: user.email });
    await reload();
  } catch (err) {
    actionError.value = err instanceof Error ? err.message : t('users.errors.activate');
  } finally {
    busyUserId.value = null;
  }
}

const paginationSummary = computed(() => {
  if (!pageInfo.value) {
    return '';
  }
  const formatter = new Intl.NumberFormat(currentLocale.value);
  const count = formatter.format(users.value.length);
  const limit = formatter.format(pageInfo.value.limit ?? users.value.length);
  return t('users.pagination.showing', { count, limit });
});
</script>

<style scoped>
.users-view {
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
  max-width: 540px;
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  flex-wrap: wrap;
}

.search-field {
  display: inline-flex;
  align-items: center;
  background: var(--color-surface-alt);
  border-radius: 999px;
  padding: 0.35rem 0.75rem;
  border: 1px solid var(--color-border);
}

.search-input {
  border: none;
  background: transparent;
  min-width: 220px;
  padding: 0.35rem 0.5rem;
  font-size: 0.95rem;
}

.clear {
  border: none;
  background: transparent;
  color: var(--color-text-secondary);
  font-weight: 600;
  cursor: pointer;
}

.primary {
  background: var(--color-brand);
  color: var(--color-text-inverse);
  border: none;
  border-radius: 0.75rem;
  padding: 0.6rem 1.5rem;
  font-weight: 600;
  cursor: pointer;
}

.primary:focus-visible,
.primary:hover {
  background: var(--color-accent);
}

.filters {
  display: flex;
  flex-wrap: wrap;
  gap: 1rem;
  align-items: center;
}

.filter {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
  font-weight: 600;
  color: var(--color-text-primary);
}

.filter select {
  border-radius: 0.75rem;
  border: 1px solid var(--color-border);
  padding: 0.4rem 0.75rem;
  background: var(--color-surface);
  color: var(--color-text-primary);
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

.actions-column {
  width: 220px;
}

.user-email {
  font-weight: 600;
  color: var(--color-text-primary);
}

.user-id {
  font-size: 0.85rem;
  color: var(--color-text-secondary);
}

.role-tags {
  display: flex;
  gap: 0.5rem;
  flex-wrap: wrap;
  list-style: none;
  margin: 0;
  padding: 0;
}

.role-tag {
  background: var(--color-surface-alt);
  color: var(--color-text-primary);
  padding: 0.25rem 0.75rem;
  border-radius: 999px;
  font-size: 0.85rem;
  font-weight: 600;
}

.status-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 999px;
  padding: 0.25rem 0.75rem;
  font-weight: 600;
  font-size: 0.85rem;
}

.status-active {
  background: var(--color-success-soft);
  color: var(--color-success);
}

.status-disabled {
  background: var(--color-danger-soft);
  color: var(--color-danger);
}

.actions-cell {
  display: flex;
  gap: 0.5rem;
  flex-wrap: wrap;
}

.action {
  border: none;
  border-radius: 0.5rem;
  padding: 0.4rem 1rem;
  font-weight: 600;
  background: var(--color-surface-alt);
  color: var(--color-text-primary);
  cursor: pointer;
}

.action.danger {
  background: var(--color-danger-soft);
  color: var(--color-danger);
}

.action:disabled {
  opacity: 0.6;
  cursor: not-allowed;
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
  color: var(--color-text-secondary);
}

.error-state {
  text-align: center;
  padding: 2rem;
  color: var(--color-danger);
  display: flex;
  flex-direction: column;
  gap: 1rem;
  align-items: center;
}

.retry {
  border: none;
  border-radius: 0.75rem;
  padding: 0.6rem 1.5rem;
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

.action-message {
  background: var(--color-success-soft);
  color: var(--color-success);
  border-radius: 0.75rem;
  padding: 0.75rem 1rem;
}

.action-error {
  background: var(--color-danger-soft);
  color: var(--color-danger);
  border-radius: 0.75rem;
  padding: 0.75rem 1rem;
}

.modal-backdrop {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.35);
  display: flex;
  justify-content: center;
  align-items: center;
  padding: 2rem;
  z-index: 1000;
}

.modal {
  background: var(--color-surface);
  border-radius: 1rem;
  width: min(560px, 100%);
  padding: 2rem;
  box-shadow: var(--shadow-elevated);
  display: flex;
  flex-direction: column;
  gap: 1rem;
}

.modal-description {
  margin: 0;
  color: var(--color-text-secondary);
}

.modal-label {
  font-weight: 600;
  color: var(--color-text-primary);
}

.modal-input {
  width: 100%;
  border-radius: 0.75rem;
  border: 1px solid var(--color-border);
  padding: 0.6rem 0.75rem;
  font-size: 1rem;
}

.modal-fieldset {
  border: none;
  padding: 0;
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.checkbox-list {
  display: flex;
  gap: 0.75rem;
  flex-wrap: wrap;
}

.checkbox-item,
.radio-item {
  display: inline-flex;
  align-items: center;
  gap: 0.5rem;
  font-weight: 600;
}

.modal-actions {
  display: flex;
  justify-content: flex-end;
  gap: 0.75rem;
}

.modal-secondary,
.modal-primary {
  border: none;
  border-radius: 0.75rem;
  padding: 0.6rem 1.4rem;
  font-weight: 600;
  cursor: pointer;
}

.modal-secondary {
  background: var(--color-surface-alt);
  color: var(--color-text-primary);
}

.modal-primary {
  background: var(--color-brand);
  color: var(--color-text-inverse);
}

.form-error {
  color: var(--color-danger);
  margin: 0.5rem 0 0;
}

.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
  border: 0;
}
</style>
