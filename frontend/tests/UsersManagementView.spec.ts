import '@testing-library/jest-dom';
import { render, screen, fireEvent, waitFor, within } from '@testing-library/vue';
import { createPinia } from 'pinia';
import { createI18n } from 'vue-i18n';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import UsersManagementView from '@/views/UsersManagementView.vue';
import { messages } from '@/locales/messages';
import {
  fetchUsersPage,
  createUser,
  updateUserRole,
  updateUserStatus,
  deleteUser,
  bulkBlock,
  bulkDelete,
  bulkRecover,
  bulkRevokeSessions,
  forceLogout
} from '@/services/adminUsers';
import type { AdminUser, AdminUsersPage } from '@/types/admin';

vi.mock('@/services/adminUsers', () => ({
  fetchUsersPage: vi.fn(),
  createUser: vi.fn(),
  updateUserRole: vi.fn(),
  updateUserStatus: vi.fn(),
  deleteUser: vi.fn(),
  sendPasswordReset: vi.fn(),
  bulkBlock: vi.fn(),
  bulkDelete: vi.fn(),
  bulkRecover: vi.fn(),
  bulkRevokeSessions: vi.fn(),
  forceLogout: vi.fn()
}));

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  messages
});

function renderView() {
  return render(UsersManagementView, {
    global: {
      plugins: [createPinia(), i18n]
    }
  });
}

function createPage(data: AdminUser[]): AdminUsersPage {
  return {
    data,
    pageInfo: {
      cursor: null,
      nextCursor: null,
      hasNext: false,
      limit: data.length
    }
  };
}

const baseUser: AdminUser = {
  id: 'user-1',
  email: 'admin@example.com',
  role: 'ADMIN',
  status: 'ACTIVE',
  displayName: 'Admin One',
  lastLoginAt: '2025-09-20T12:00:00Z',
  createdAt: '2025-09-10T08:00:00Z',
  updatedAt: '2025-09-10T08:00:00Z'
};

const secondUser: AdminUser = {
  id: 'user-2',
  email: 'moderator@example.com',
  role: 'MODERATOR',
  status: 'BLOCKED',
  displayName: 'Mod Two',
  lastLoginAt: null,
  createdAt: '2025-09-15T08:00:00Z',
  updatedAt: '2025-09-20T08:00:00Z'
};

const fetchUsersPageMock = fetchUsersPage as unknown as vi.Mock;
const createUserMock = createUser as unknown as vi.Mock;
const updateUserRoleMock = updateUserRole as unknown as vi.Mock;
const updateUserStatusMock = updateUserStatus as unknown as vi.Mock;
const deleteUserMock = deleteUser as unknown as vi.Mock;
const bulkBlockMock = bulkBlock as unknown as vi.Mock;
const bulkDeleteMock = bulkDelete as unknown as vi.Mock;
const bulkRecoverMock = bulkRecover as unknown as vi.Mock;
const bulkRevokeMock = bulkRevokeSessions as unknown as vi.Mock;
const forceLogoutMock = forceLogout as unknown as vi.Mock;

const thirdUser: AdminUser = {
  id: 'user-3',
  email: 'third@example.com',
  role: 'MODERATOR',
  status: 'ACTIVE',
  displayName: 'Third User',
  lastLoginAt: null,
  createdAt: '2025-09-16T08:00:00Z',
  updatedAt: '2025-09-16T08:00:00Z'
};

describe('UsersManagementView', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    // window.confirm() guards bulk-delete and force-logout actions; stub to
    // auto-accept so click-through tests don't deadlock waiting for a
    // confirmation that never resolves (cubic R5 Tier-A test fallout).
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    fetchUsersPageMock.mockResolvedValue(createPage([baseUser, secondUser]));
    createUserMock.mockResolvedValue({
      id: 'user-3',
      email: 'new@example.com',
      role: 'ADMIN',
      status: 'ACTIVE',
      displayName: 'New Admin',
      lastLoginAt: null,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString()
    });
    updateUserRoleMock.mockResolvedValue(undefined);
    updateUserStatusMock.mockResolvedValue(undefined);
    deleteUserMock.mockResolvedValue(undefined);
    bulkBlockMock.mockResolvedValue(undefined);
    bulkDeleteMock.mockResolvedValue(undefined);
    bulkRecoverMock.mockResolvedValue(undefined);
    bulkRevokeMock.mockResolvedValue(undefined);
    forceLogoutMock.mockResolvedValue(undefined);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('creates a new user through the dialog', async () => {
    renderView();

    await screen.findByText('admin@example.com');

    await fireEvent.click(screen.getByRole('button', { name: /add user/i }));

    const createDialog = await screen.findByRole('dialog', { name: /invite admin or moderator/i });
    const emailField = within(createDialog).getByLabelText(/work email/i);
    await fireEvent.update(emailField, 'new@example.com');

    const passwordField = within(createDialog).getByLabelText(/password/i);
    await fireEvent.update(passwordField, 'secret1');

    const displayNameField = within(createDialog).getByLabelText(/display name/i);
    await fireEvent.update(displayNameField, 'New Admin');

    const adminRadio = within(createDialog).getByLabelText(/administrator/i);
    await fireEvent.click(adminRadio);

    await fireEvent.click(within(createDialog).getByRole('button', { name: /create user/i }));

    await waitFor(() => {
      expect(createUser).toHaveBeenCalledWith({
        email: 'new@example.com',
        password: 'secret1',
        displayName: 'New Admin',
        role: 'ADMIN'
      });
    });
  });

  it('updates user roles and status', async () => {
    renderView();
    await screen.findByText('admin@example.com');

    await fireEvent.click(screen.getAllByRole('button', { name: /edit user/i })[0]);

    const editDialog = await screen.findByRole('dialog', { name: /edit admin@example.com/i });
    const moderatorRadio = within(editDialog).getByLabelText(/moderator/i);
    await fireEvent.click(moderatorRadio);

    // Cubic R9 P2: status radios are now Active / Blocked / Deleted (no
    // "Disabled" label). The test exercises the "block via edit dialog"
    // path which sends 'BLOCKED' (canonical) instead of the legacy 'DISABLED'.
    const blockedRadio = within(editDialog).getByLabelText(/blocked/i);
    await fireEvent.click(blockedRadio);

    await fireEvent.click(within(editDialog).getByRole('button', { name: /save changes/i }));

    await waitFor(() => {
      expect(updateUserRole).toHaveBeenCalledWith('user-1', 'MODERATOR');
      expect(updateUserStatus).toHaveBeenCalledWith('user-1', 'BLOCKED');
    });
  });

  it('deactivates an active user', async () => {
    renderView();
    await screen.findByText('admin@example.com');

    await fireEvent.click(screen.getByRole('button', { name: /deactivate/i }));

    await waitFor(() => {
      expect(updateUserStatus).toHaveBeenCalledWith('user-1', 'BLOCKED');
    });
  });

  describe('T27: checkbox + bulk toolbar + force logout', () => {
    it('shows mixed-result toast after bulk-block with 1 success and 2 failures', async () => {
      // Arrange: 3 active users
      fetchUsersPageMock.mockResolvedValue(createPage([baseUser, secondUser, thirdUser]));
      bulkBlockMock.mockResolvedValue({
        successes: ['user-1'],
        failures: [
          { uid: 'user-2', reason: 'already_blocked' },
          { uid: 'user-3', reason: 'firebase_error' }
        ]
      });

      renderView();
      await screen.findByText('admin@example.com');

      // Select all 3 row checkboxes
      const checkboxes = screen.getAllByTestId('row-select');
      expect(checkboxes).toHaveLength(3);
      for (const cb of checkboxes) {
        await fireEvent.click(cb);
      }

      // Click bulk-block button in the toolbar
      const bulkBlockBtn = screen.getByTestId('bulk-block');
      await fireEvent.click(bulkBlockBtn);

      // Assert toast appears with correct counts
      await waitFor(() => {
        const toast = screen.getByTestId('bulk-result-toast');
        expect(toast).toBeInTheDocument();
        expect(toast.textContent).toContain('1 succeeded');
        expect(toast.textContent).toContain('2 failed');
      });
    });

    it('calls forceLogout for the correct user on force-logout button click', async () => {
      forceLogoutMock.mockResolvedValue(undefined);
      renderView();
      await screen.findByText('admin@example.com');

      const forceLogoutButtons = screen.getAllByTestId('force-logout-btn');
      await fireEvent.click(forceLogoutButtons[0]);

      await waitFor(() => {
        expect(forceLogout).toHaveBeenCalledWith('user-1');
      });
    });
  });
});
