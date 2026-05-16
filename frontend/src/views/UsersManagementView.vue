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

    <!-- Bulk result toast -->
    <div
      v-if="lastResult"
      data-testid="bulk-result-toast"
      class="bulk-toast"
      role="status"
      aria-live="polite"
    >
      <!-- Cubic R7 P2 — aria-live so screen readers announce the bulk
           result without focus stealing; assistive tech otherwise missed
           the toast entirely (just `role="status"` is not enough on its
           own in some Voice-Over implementations). -->
      <span>{{ lastResult.action }}: {{ lastResult.ok }} succeeded, {{ lastResult.fail }} failed</span>
      <button type="button" class="toast-close" @click="lastResult = null">✕</button>
      <div v-if="lastResult.failures.length > 0">
        <!-- Cubic R7 P2 — toggle moved to a method so the inline template
             mutation doesn't violate one-way data flow. -->
        <button type="button" class="toast-details-toggle" @click="toggleResultExpand">
          {{ lastResult.expand ? 'Hide details' : 'Details' }}
        </button>
        <ul v-if="lastResult.expand" class="toast-failures">
          <li v-for="f in lastResult.failures" :key="f.uid">
            {{ f.uid }}: {{ t('users.bulk.reason.' + reasonKey(f.reason), t('users.bulk.reason.unknown')) }}
          </li>
        </ul>
      </div>
    </div>

    <!-- Cubic R7 P1 — surface cross-page selection loss. Auto-dismissable
         via close button; clears on next reload. -->
    <div v-if="selectionDroppedCount > 0" class="selection-dropped-toast" role="status">
      <span>{{ t('users.selection.dropped', { n: selectionDroppedCount }) }}</span>
      <button type="button" class="toast-close" @click="selectionDroppedCount = 0">✕</button>
    </div>

    <!-- Sticky bulk-action toolbar -->
    <div v-if="selected.size >= 1" class="bulk-toolbar">
      <span class="bulk-selected">{{ selected.size }} selected</span>
      <!-- Cubic R7 P1 — bulk buttons :disabled while a bulk request is in
           flight. Pre-fix a slow API + impatient double-click submitted two
           POSTs; the second ran against a half-mutated set after the first
           reload(). -->
      <button type="button" data-testid="bulk-block" class="bulk-btn" :disabled="bulkActionRunning" @click="handleBulkBlock">
        {{ t('users.bulk.block') }}
      </button>
      <button type="button" data-testid="bulk-delete" class="bulk-btn danger" :disabled="bulkActionRunning" @click="handleBulkDelete">
        {{ t('users.bulk.delete') }}
      </button>
      <button type="button" data-testid="bulk-recover" class="bulk-btn" :disabled="bulkActionRunning" @click="handleBulkRecover">
        {{ t('users.bulk.recover') }}
      </button>
      <button type="button" data-testid="bulk-revoke-sessions" class="bulk-btn" :disabled="bulkActionRunning" @click="handleBulkRevokeSessions">
        {{ t('users.bulk.revokeSessions') }}
      </button>
    </div>

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
          <option value="BLOCKED">{{ t('users.status.blocked') }}</option>
          <option value="DELETED">{{ t('users.status.deleted') }}</option>
          <option value="PENDING_PROFILE">{{ t('users.status.pendingProfile') }}</option>
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
            <th scope="col" class="checkbox-column">
              <!--
                Cubic R5 P1 #28: `indeterminate` is an IDL property, not an
                HTML attribute — `:indeterminate="…"` would silently set an
                attribute the browser ignores. Bind a template ref instead
                and write the property directly from a watcher.
              -->
              <input
                ref="selectAllRef"
                type="checkbox"
                :checked="users.length > 0 && selected.size === users.length"
                @change="toggleSelectAll"
                :aria-label="t('users.table.selectAll')"
              />
            </th>
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
            <td :colspan="7">
              <div class="skeleton-stack" aria-hidden="true">
                <div v-for="index in 5" :key="`skeleton-${index}`" class="skeleton-row"></div>
              </div>
            </td>
          </tr>
          <tr v-else-if="!users.length">
            <td :colspan="7" class="empty-state">{{ t('users.table.empty') }}</td>
          </tr>
          <tr v-for="user in users" :key="user.id">
            <td class="checkbox-column">
              <input
                type="checkbox"
                :checked="selected.has(user.id)"
                data-testid="row-select"
                :aria-label="`Select ${user.email}`"
                @change="toggleSelectUser(user.id)"
              />
            </td>
            <td>
              <div class="user-email">{{ user.email }}</div>
              <div class="user-id">{{ user.id }}</div>
            </td>
            <td>
              <span class="role-tag">{{ roleLabel(user.role) }}</span>
            </td>
            <td>
              <span :class="['status-badge', `status-${user.status.toLowerCase()}`]">
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
                type="button"
                class="action"
                :disabled="isLoading || resettingPasswordUserId === user.id"
                @click="handleResetPassword(user)"
              >
                {{ resettingPasswordUserId === user.id ? t('users.actions.resettingPassword') : t('users.actions.resetPassword') }}
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
              <button
                type="button"
                class="action danger"
                :disabled="isLoading || deleteState.isDeleting"
                @click="openDeleteDialog(user)"
              >
                {{ t('users.actions.delete') }}
              </button>
              <button
                type="button"
                class="action danger"
                data-testid="force-logout-btn"
                :disabled="isLoading || forcingLogoutUserId === user.id"
                @click="handleForceLogout(user)"
              >
                {{ forcingLogoutUserId === user.id ? t('users.forceLogout.busy') : t('users.forceLogout.button') }}
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

          <label class="modal-label" for="create-password">{{ t('users.dialogs.create.password') }}</label>
          <input
            id="create-password"
            v-model="createState.password"
            type="password"
            class="modal-input"
            :disabled="createState.isSubmitting"
            minlength="6"
            required
          />

          <label class="modal-label" for="create-displayName">{{ t('users.dialogs.create.displayName') }}</label>
          <input
            id="create-displayName"
            v-model="createState.displayName"
            type="text"
            class="modal-input"
            :disabled="createState.isSubmitting"
          />

          <fieldset class="modal-fieldset">
            <legend>{{ t('users.dialogs.create.role') }}</legend>
            <div class="radio-list">
              <label v-for="role in roleOptions" :key="role" class="radio-item">
                <input
                  type="radio"
                  :value="role"
                  v-model="createState.role"
                  :disabled="createState.isSubmitting"
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
            <legend>{{ t('users.dialogs.edit.role') }}</legend>
            <div class="radio-list">
              <label v-for="role in roleOptions" :key="`edit-${role}`" class="radio-item">
                <input
                  type="radio"
                  :value="role"
                  v-model="editState.role"
                  :disabled="editState.isSubmitting"
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
            <!-- Cubic R-final-verify P0 — replaced the now-invalid
                 value="DISABLED" radio with the canonical BLOCKED option,
                 plus DELETED so an admin can transition a user through the
                 full lifecycle from this dialog. PENDING_PROFILE intentionally
                 absent — that state is set by the bootstrap flow, not by
                 admin action. -->
            <label class="radio-item">
              <input
                type="radio"
                value="BLOCKED"
                v-model="editState.status"
                :disabled="editState.isSubmitting"
              />
              <span>{{ t('users.status.blocked') }}</span>
            </label>
            <label class="radio-item">
              <input
                type="radio"
                value="DELETED"
                v-model="editState.status"
                :disabled="editState.isSubmitting"
              />
              <span>{{ t('users.status.deleted') }}</span>
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

    <div v-if="deleteState.visible" class="modal-backdrop">
      <div
        ref="deleteDialogRef"
        class="modal"
        role="dialog"
        aria-modal="true"
        :aria-labelledby="deleteDialogTitleId"
        :aria-describedby="deleteDialogDescriptionId"
        tabindex="-1"
      >
        <h2 :id="deleteDialogTitleId">{{ t('users.dialogs.delete.title', { email: deleteState.userEmail }) }}</h2>
        <p :id="deleteDialogDescriptionId" class="modal-description">
          {{ t('users.dialogs.delete.description') }}
        </p>
        <form @submit.prevent="handleDelete">
          <p v-if="deleteState.error" class="form-error" role="alert">{{ deleteState.error }}</p>
          <div class="modal-actions">
            <button type="button" class="modal-secondary" :disabled="deleteState.isDeleting" @click="closeDeleteDialog">
              {{ t('users.dialogs.actions.cancel') }}
            </button>
            <button type="submit" class="modal-primary modal-danger" :disabled="deleteState.isDeleting">
              {{ deleteState.isDeleting ? t('users.dialogs.delete.submitting') : t('users.dialogs.delete.submit') }}
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
import { useAuthStore } from '@/stores/auth';
import { useCursorPagination } from '@/composables/useCursorPagination';
import { useFocusTrap } from '@/composables/useFocusTrap';
import {
  bulkBlock,
  bulkDelete,
  bulkRecover,
  bulkRevokeSessions,
  createUser,
  deleteUser,
  fetchUsersPage,
  forceLogout,
  sendPasswordReset,
  updateUserRole,
  updateUserStatus
} from '@/services/adminUsers';
import type { AdminRole, AdminUser, AdminUserStatus } from '@/types/admin';
import { formatDateTime as baseFormatDateTime } from '@/utils/formatters';

const { t, locale } = useI18n();
const authStore = useAuthStore();

/**
 * Cubic R-final5 P2 — prune self and admin targets from a bulk-action
 * selection BEFORE issuing the POST. Pre-fix the bulk payload included
 * the actor's own uid + any admin rows; each one round-tripped through
 * the backend and landed in {@code result.failures} with
 * {@code self_action_forbidden} / {@code admin_target_forbidden}. A
 * 50-row bulk-block including the actor returned N-1 successes + a
 * confusing per-row failure even though the admin's intent was clear.
 * Returns the pruned uid list AND the count of dropped rows so the
 * caller can surface "N skipped" to the UI.
 */
function pruneSelfAndAdmins(selectedIds: Iterable<string>): { uids: string[]; dropped: number } {
  const selfUid = authStore.currentUser?.uid ?? null;
  const byId = new Map(users.value.map((u) => [u.id, u]));
  const kept: string[] = [];
  let dropped = 0;
  for (const id of selectedIds) {
    if (id === selfUid) { dropped++; continue; }
    const u = byId.get(id);
    if (u && u.role === 'ADMIN') { dropped++; continue; }
    kept.push(id);
  }
  return { uids: kept, dropped };
}
const currentLocale = computed(() => locale.value);

// Cubic R7 P0 — whitelist of reason codes accepted from the backend bulk-action
// response. Without this guard, `t('users.bulk.reason.' + f.reason)` builds the
// i18n key from a backend-supplied string. A reason containing dots/spaces or
// colliding key paths could silently resolve to an unrelated translation, or
// (worse) walk into other i18n namespaces. Mapping through this whitelist
// pins the resolved keys to the closed set that messages.ts actually defines.
const KNOWN_BULK_REASONS: ReadonlySet<string> = new Set([
  'user_not_found',
  'already_blocked',
  'not_blocked',
  'already_deleted',
  'not_deleted',
  'self_action_forbidden',
  'admin_target_forbidden',
  'blocked_cannot_delete',
  'deleted_cannot_block',
  'deleted_cannot_role_change',
  'firebase_error',
  'invalid_state',
  'unknown',
]);
function reasonKey(raw: string | null | undefined): string {
  return raw != null && KNOWN_BULK_REASONS.has(raw) ? raw : 'unknown';
}

const roleOptions: AdminRole[] = ['ADMIN', 'MODERATOR'];

const searchQuery = ref('');
const activeSearch = ref('');
const roleFilter = ref<'all' | AdminRole>('all');
const statusFilter = ref<'all' | AdminUserStatus>('all');
const actionMessage = ref<string | null>(null);
const actionError = ref<string | null>(null);
const busyUserId = ref<string | null>(null);
const resettingPasswordUserId = ref<string | null>(null);
const forcingLogoutUserId = ref<string | null>(null);
// Cubic R7 P1 — gate every bulk handler on this ref so a double-click on a
// slow API can't submit two POSTs.
const bulkActionRunning = ref(false);
// Cubic R7 P1 — surface cross-page selection loss with a transient notice.
const selectionDroppedCount = ref(0);

// Checkbox selection
const selected = ref<Set<string>>(new Set());
const selectAllRef = ref<HTMLInputElement | null>(null);

interface BulkResult {
  action: string;
  ok: number;
  fail: number;
  failures: { uid: string; reason: string }[];
  expand: boolean;
}
const lastResult = ref<BulkResult | null>(null);

const pagination = useCursorPagination<AdminUser>(async (cursor, limit) => {
  // Cubic R-final5 P0 — pass includeDeleted=true when the status filter
  // explicitly targets DELETED users, OR when 'all' is selected (so Bulk
  // Recover can see soft-deleted rows). Anything else hides them — they
  // are excluded from the admin's normal moderation surface.
  const isDeletedView = statusFilter.value === 'DELETED' || statusFilter.value === 'all';
  return fetchUsersPage({
    cursor,
    limit,
    search: activeSearch.value || undefined,
    role: roleFilter.value === 'all' ? null : roleFilter.value,
    status: statusFilter.value === 'all' ? null : statusFilter.value,
    includeDeleted: isDeletedView
  });
});

const { items, isLoading, error, load, next, previous, hasNext, hasPrevious, pageInfo } = pagination;
const users = items;
const loadError = computed(() => error.value);

// Cubic R5 P1 #28 — Vue's attribute binding cannot set the `indeterminate`
// IDL property on a checkbox. Compute the tri-state condition and apply it
// to the element's property whenever it changes. `flush: 'post'` so the
// ref is bound before the watcher fires.
const isSelectAllIndeterminate = computed(
  () => selected.value.size > 0 && selected.value.size < users.value.length
);
watch(
  isSelectAllIndeterminate,
  (v) => { if (selectAllRef.value) selectAllRef.value.indeterminate = v; },
  { flush: 'post', immediate: true }
);

// Cubic R5 P1 #27 — prune `selected` to ids still present in the current
// page. After `reload()` or filter change the Set kept ids from prior
// pages, so the header "select all" comparison drifted (size === length
// could match while no row was actually checked) and a subsequent bulk
// POST sent stale uids the admin no longer has visibility on.
watch(users, (newUsers) => {
  if (selected.value.size === 0) return;
  const visible = new Set(newUsers.map((u) => u.id));
  const next = new Set<string>();
  for (const id of selected.value) if (visible.has(id)) next.add(id);
  if (next.size !== selected.value.size) {
    // Cubic R7 P1 — surface dropped cross-page selection to the admin.
    // Pre-fix the prune silently discarded selections that moved out of
    // view on page navigation; admins thought their selection persisted
    // and were surprised when bulk actions ran against fewer rows than
    // they ticked. The notice clears on the next reload + scroll.
    selectionDroppedCount.value = selected.value.size - next.size;
    selected.value = next;
  }
});

const searchInputRef = ref<HTMLInputElement | null>(null);

const createDialogRef = ref<HTMLDivElement | null>(null);
const createEmailRef = ref<HTMLInputElement | null>(null);
const createDialogTitleId = 'create-user-title';
const createDialogDescriptionId = 'create-user-description';
const createEmailId = 'create-user-email';

const editDialogRef = ref<HTMLDivElement | null>(null);
const editDialogTitleId = 'edit-user-title';
const editDialogDescriptionId = 'edit-user-description';

const deleteDialogRef = ref<HTMLDivElement | null>(null);
const deleteDialogTitleId = 'delete-user-title';
const deleteDialogDescriptionId = 'delete-user-description';

const createState = reactive({
  visible: false,
  email: '',
  password: '',
  displayName: '',
  role: 'MODERATOR' as AdminRole,
  isSubmitting: false,
  error: null as string | null
});

const editState = reactive({
  visible: false,
  role: 'MODERATOR' as AdminRole,
  status: 'ACTIVE' as AdminUserStatus,
  isSubmitting: false,
  error: null as string | null
});

const editingUser = ref<AdminUser | null>(null);

const deleteState = reactive({
  visible: false,
  userId: null as string | null,
  userEmail: '',
  isDeleting: false,
  error: null as string | null
});

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

const { activate: activateDeleteTrap, deactivate: deactivateDeleteTrap } = useFocusTrap(deleteDialogRef, {
  onEscape: () => {
    if (!deleteState.isDeleting) {
      closeDeleteDialog();
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
  // Cubic R9 P2 — 4 distinct labels so admins can see blocked vs deleted
  // vs pending-profile in the table column, not just active/disabled.
  switch (status) {
    case 'ACTIVE':          return t('users.status.active');
    case 'BLOCKED':         return t('users.status.blocked');
    case 'DELETED':         return t('users.status.deleted');
    case 'PENDING_PROFILE': return t('users.status.pendingProfile');
  }
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
  createState.password = '';
  createState.displayName = '';
  createState.role = 'MODERATOR';
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

async function handleCreate() {
  if (createState.isSubmitting) {
    return;
  }
  if (!createState.email.trim()) {
    createState.error = t('users.dialogs.create.errors.email');
    return;
  }
  if (!createState.password.trim() || createState.password.length < 6) {
    createState.error = t('users.dialogs.create.errors.password');
    return;
  }

  createState.error = null;
  createState.isSubmitting = true;
  try {
    await createUser({
      email: createState.email.trim(),
      password: createState.password,
      displayName: createState.displayName.trim() || undefined,
      role: createState.role
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
  editState.role = user.role;
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

async function handleEdit() {
  if (!editingUser.value || editState.isSubmitting) {
    return;
  }

  // Cubic R-final5 P1 — compare status to current value (parity with role),
  // and reload after partial failure so the table reflects the half-committed
  // state. Pre-fix `if (editState.status)` was always truthy (radio guarantees
  // a value), so we fired the status PUT on every save even when the user
  // only edited the role — wasted PUT, pointless audit row, unnecessary
  // network spend. Worse: a partial failure (role PUT commits, status PUT
  // throws) used to leave the dialog showing only the second error while
  // the first had already committed and no reload() ran — the visible table
  // would still claim the old role until manual refresh.
  const roleChanged = editState.role !== editingUser.value.role;
  const statusChanged = !!editState.status && editState.status !== editingUser.value.status;

  if (!roleChanged && !statusChanged) {
    closeEditDialog();
    return;
  }

  editState.error = null;
  editState.isSubmitting = true;
  try {
    if (roleChanged) {
      await updateUserRole(editingUser.value.id, editState.role);
    }
    if (statusChanged) {
      await updateUserStatus(editingUser.value.id, editState.status);
    }
    actionMessage.value = t('users.toasts.updated', { email: editingUser.value.email });
    await reload();
    closeEditDialog();
  } catch (err) {
    editState.error = err instanceof Error ? err.message : t('users.dialogs.edit.errors.generic');
    // Refresh so the table reflects any committed half — role may have
    // succeeded before status threw, and the admin needs to see that.
    await reload();
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
    // Cubic R9 P2 — pre-R9 this sent 'DISABLED' (mapped to backend
    // 'inactive', a legacy alias). New semantics: deactivate-from-UI
    // means BLOCK the user; the backend canonicalises to 'blocked'.
    await updateUserStatus(user.id, 'BLOCKED');
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
    await updateUserStatus(user.id, 'ACTIVE');
    actionMessage.value = t('users.toasts.activated', { email: user.email });
    await reload();
  } catch (err) {
    actionError.value = err instanceof Error ? err.message : t('users.errors.activate');
  } finally {
    busyUserId.value = null;
  }
}

function openDeleteDialog(user: AdminUser) {
  deleteState.visible = true;
  deleteState.userId = user.id;
  deleteState.userEmail = user.email;
  deleteState.error = null;
  actionError.value = null;
  nextTick(() => {
    activateDeleteTrap({ initialFocus: deleteDialogRef.value ?? null });
  });
}

function closeDeleteDialog() {
  deleteState.visible = false;
  deleteState.isDeleting = false;
  deleteState.userId = null;
  deleteState.userEmail = '';
  deactivateDeleteTrap();
}

async function handleDelete() {
  if (!deleteState.userId || deleteState.isDeleting) {
    return;
  }

  deleteState.error = null;
  deleteState.isDeleting = true;
  try {
    await deleteUser(deleteState.userId);
    actionMessage.value = t('users.toasts.deleted', { email: deleteState.userEmail });
    await reload();
    closeDeleteDialog();
  } catch (err) {
    deleteState.error = err instanceof Error ? err.message : t('users.dialogs.delete.errors.generic');
  } finally {
    deleteState.isDeleting = false;
  }
}

async function handleResetPassword(user: AdminUser) {
  if (resettingPasswordUserId.value === user.id) {
    return;
  }
  resettingPasswordUserId.value = user.id;
  actionError.value = null;
  try {
    await sendPasswordReset(user.id);
    actionMessage.value = t('users.toasts.passwordReset', { email: user.email });
  } catch (err) {
    actionError.value = err instanceof Error ? err.message : t('users.errors.resetPassword');
  } finally {
    resettingPasswordUserId.value = null;
  }
}

// Checkbox helpers
function toggleSelectUser(id: string) {
  const next = new Set(selected.value);
  if (next.has(id)) {
    next.delete(id);
  } else {
    next.add(id);
  }
  selected.value = next;
}

function toggleSelectAll() {
  if (selected.value.size === users.value.length) {
    selected.value = new Set();
  } else {
    selected.value = new Set(users.value.map((u) => u.id));
  }
}

/**
 * Cubic R7 P2 — extracted from the inline template handler so we don't
 * mutate `lastResult.expand` from the template (Vue one-way data flow).
 */
function toggleResultExpand() {
  if (!lastResult.value) return;
  lastResult.value = { ...lastResult.value, expand: !lastResult.value.expand };
}

// Bulk action helpers
async function handleBulkBlock() {
  // Guard against empty-selection bulk calls — the bulk buttons remain
  // enabled even with no rows ticked and would otherwise POST `uids: []`
  // and a confirm dialog appear with nothing to confirm (cubic R5 P2).
  if (selected.value.size === 0 || bulkActionRunning.value) return;
  // Cubic R7 P1 — confirm dialog parity with bulk-delete. Bulk block is
  // destructive (blocks N users with one click) and should not fire on
  // a misclick.
  if (!window.confirm(t('users.confirmBulk.block', { n: selected.value.size }))) {
    return;
  }
  bulkActionRunning.value = true;
  // Cubic R-final5 P2 — prune self + admin targets before POSTing.
  const pruned = pruneSelfAndAdmins(selected.value);
  const uids = pruned.uids;
  if (pruned.dropped > 0) {
    selectionDroppedCount.value = pruned.dropped;
  }
  if (uids.length === 0) {
    bulkActionRunning.value = false;
    return;
  }
  try {
    const result = await bulkBlock({ uids });
    lastResult.value = {
      action: t('users.bulk.block'),
      ok: result.successes.length,
      fail: result.failures.length,
      failures: result.failures,
      expand: false
    };
    selected.value = new Set();
    await reload();
  } catch (err) {
    actionError.value = err instanceof Error ? err.message : 'Bulk block failed';
  } finally {
    bulkActionRunning.value = false;
  }
}

async function handleBulkDelete() {
  if (selected.value.size === 0 || bulkActionRunning.value) return;
  // Use the i18n key added in the same diff (cubic R5 P1). The previous
  // hardcoded English string left the Arabic/Dutch keys dead and broke
  // Arabic admin workflows.
  if (!window.confirm(t('users.confirmDelete.bulk', { n: selected.value.size }))) {
    return;
  }
  bulkActionRunning.value = true;
  // Cubic R-final5 P2 — prune self + admin targets before POSTing.
  const pruned = pruneSelfAndAdmins(selected.value);
  const uids = pruned.uids;
  if (pruned.dropped > 0) {
    selectionDroppedCount.value = pruned.dropped;
  }
  if (uids.length === 0) {
    bulkActionRunning.value = false;
    return;
  }
  try {
    const result = await bulkDelete({ uids });
    lastResult.value = {
      action: t('users.bulk.delete'),
      ok: result.successes.length,
      fail: result.failures.length,
      failures: result.failures,
      expand: false
    };
    selected.value = new Set();
    await reload();
  } catch (err) {
    actionError.value = err instanceof Error ? err.message : 'Bulk delete failed';
  } finally {
    bulkActionRunning.value = false;
  }
}

async function handleBulkRecover() {
  if (selected.value.size === 0 || bulkActionRunning.value) return;
  // Cubic R7 P1 — confirm dialog parity. Recover is less destructive
  // but admins should still see the row count.
  if (!window.confirm(t('users.confirmBulk.recover', { n: selected.value.size }))) {
    return;
  }
  bulkActionRunning.value = true;
  // Cubic R-final5 P2 — prune self + admin targets before POSTing.
  const pruned = pruneSelfAndAdmins(selected.value);
  const uids = pruned.uids;
  if (pruned.dropped > 0) {
    selectionDroppedCount.value = pruned.dropped;
  }
  if (uids.length === 0) {
    bulkActionRunning.value = false;
    return;
  }
  try {
    const result = await bulkRecover({ uids });
    lastResult.value = {
      action: t('users.bulk.recover'),
      ok: result.successes.length,
      fail: result.failures.length,
      failures: result.failures,
      expand: false
    };
    selected.value = new Set();
    await reload();
  } catch (err) {
    actionError.value = err instanceof Error ? err.message : 'Bulk recover failed';
  } finally {
    bulkActionRunning.value = false;
  }
}

async function handleBulkRevokeSessions() {
  if (selected.value.size === 0 || bulkActionRunning.value) return;
  // Cubic R7 P1 — bulk revoke-sessions invalidates sessions for N users
  // at once; this MUST have a confirm dialog. Pre-fix only handleBulkDelete
  // had one, so a single misclick force-signed-out an entire selection.
  if (!window.confirm(t('users.confirmBulk.revokeSessions', { n: selected.value.size }))) {
    return;
  }
  bulkActionRunning.value = true;
  // Cubic R-final5 P2 — prune self + admin targets before POSTing.
  const pruned = pruneSelfAndAdmins(selected.value);
  const uids = pruned.uids;
  if (pruned.dropped > 0) {
    selectionDroppedCount.value = pruned.dropped;
  }
  if (uids.length === 0) {
    bulkActionRunning.value = false;
    return;
  }
  try {
    const result = await bulkRevokeSessions({ uids });
    lastResult.value = {
      action: t('users.bulk.revokeSessions'),
      ok: result.successes.length,
      fail: result.failures.length,
      failures: result.failures,
      expand: false
    };
    selected.value = new Set();
    await reload();
  } catch (err) {
    actionError.value = err instanceof Error ? err.message : 'Bulk revoke sessions failed';
  } finally {
    bulkActionRunning.value = false;
  }
}

async function handleForceLogout(user: AdminUser) {
  if (forcingLogoutUserId.value === user.id) {
    return;
  }
  // Confirm prompt added in the same diff (cubic R5 P1) — without it a
  // single misclick on the per-row red button instantly invalidated a
  // user's sessions.
  if (!window.confirm(t('users.forceLogout.confirm', { email: user.email }))) {
    return;
  }
  forcingLogoutUserId.value = user.id;
  actionError.value = null;
  try {
    await forceLogout(user.id);
    actionMessage.value = t('users.forceLogout.success', { email: user.email });
  } catch (err) {
    actionError.value = err instanceof Error ? err.message : 'Force logout failed';
  } finally {
    forcingLogoutUserId.value = null;
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
  width: 360px;
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

/* Cubic R9 P2 — distinct visual treatments for each non-active state. */
.status-blocked {
  background: var(--color-danger-soft);
  color: var(--color-danger);
}

.status-deleted {
  background: var(--color-neutral-soft, var(--color-danger-soft));
  color: var(--color-text-muted, var(--color-danger));
  text-decoration: line-through;
}

.status-pending_profile {
  background: var(--color-warning-soft, var(--color-success-soft));
  color: var(--color-warning, var(--color-success));
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

.modal-danger {
  background: var(--color-danger);
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

/* Touch Optimizations */
button,
input,
select {
  -webkit-tap-highlight-color: transparent;
}

button,
.action,
.pager,
.modal-primary,
.modal-secondary {
  min-height: 44px;
}

/* Mobile/Tablet Responsive */
@media (max-width: 1023px) {
  .users-view {
    gap: 1.5rem;
  }

  .workspace-header {
    flex-direction: column;
    gap: 1.25rem;
  }

  .workspace-header h1 {
    font-size: 1.75rem;
  }

  .workspace-header p {
    font-size: 0.875rem;
  }

  .header-actions {
    flex-direction: column;
    width: 100%;
    gap: 0.75rem;
  }

  .search-field {
    width: 100%;
  }

  .header-actions .primary {
    width: 100%;
    padding: 0.875rem 1.5rem;
    min-height: 48px;
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

  .data-table {
    min-width: 800px;
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

  .modal-backdrop {
    padding: 1rem;
  }

  .modal {
    width: 100%;
    max-width: calc(100vw - 2rem);
  }

  .modal-actions {
    flex-direction: column-reverse;
    gap: 0.625rem;
  }

  .modal-primary,
  .modal-secondary {
    width: 100%;
    min-height: 48px;
  }
}

@media (max-width: 767px) {
  .workspace-header h1 {
    font-size: 1.5rem;
  }

  .filters span {
    font-size: 0.875rem;
  }

  .action {
    font-size: 0.875rem;
    padding: 0.5rem 0.875rem;
  }
}

.radio-list {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}

.radio-item {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  cursor: pointer;
}

.radio-item input[type="radio"] {
  cursor: pointer;
}

.radio-item span {
  user-select: none;
}

.checkbox-column {
  width: 40px;
  text-align: center;
}

.bulk-toolbar {
  position: sticky;
  top: 0;
  z-index: 10;
  display: flex;
  align-items: center;
  gap: 0.75rem;
  flex-wrap: wrap;
  background: var(--color-surface);
  border-radius: 0.75rem;
  padding: 0.75rem 1.25rem;
  box-shadow: var(--shadow-elevated);
}

.bulk-selected {
  font-weight: 600;
  color: var(--color-text-secondary);
  margin-right: auto;
}

.bulk-btn {
  border: none;
  border-radius: 0.5rem;
  padding: 0.4rem 1rem;
  font-weight: 600;
  background: var(--color-surface-alt);
  color: var(--color-text-primary);
  cursor: pointer;
}

.bulk-btn.danger {
  background: var(--color-danger-soft);
  color: var(--color-danger);
}

.bulk-toast {
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: 0.75rem;
  padding: 0.75rem 1.25rem;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  box-shadow: var(--shadow-elevated);
}

.toast-close {
  border: none;
  background: transparent;
  cursor: pointer;
  font-size: 1rem;
  color: var(--color-text-secondary);
  align-self: flex-end;
}

.toast-details-toggle {
  border: none;
  background: transparent;
  cursor: pointer;
  color: var(--color-brand);
  font-weight: 600;
  padding: 0;
}

.toast-failures {
  margin: 0.25rem 0 0;
  padding-left: 1.25rem;
  font-size: 0.9rem;
  color: var(--color-text-secondary);
}
</style>
