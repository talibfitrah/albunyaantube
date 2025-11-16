import '@testing-library/jest-dom';
import { render, screen, fireEvent, waitFor, within } from '@testing-library/vue';
import { createI18n } from 'vue-i18n';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import UsersManagementView from '@/views/UsersManagementView.vue';
import { messages } from '@/locales/messages';
import {
  fetchUsersPage,
  createUser,
  updateUserRole,
  updateUserStatus,
  deleteUser
} from '@/services/adminUsers';
import type { AdminUser, AdminUsersPage } from '@/types/admin';

vi.mock('@/services/adminUsers', () => ({
  fetchUsersPage: vi.fn(),
  createUser: vi.fn(),
  updateUserRole: vi.fn(),
  updateUserStatus: vi.fn(),
  deleteUser: vi.fn(),
  sendPasswordReset: vi.fn()
}));

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  messages
});

function renderView() {
  return render(UsersManagementView, {
    global: {
      plugins: [i18n]
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
  status: 'DISABLED',
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

describe('UsersManagementView', () => {
  beforeEach(() => {
    vi.clearAllMocks();
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

    const disabledRadio = within(editDialog).getByLabelText(/disabled/i);
    await fireEvent.click(disabledRadio);

    await fireEvent.click(within(editDialog).getByRole('button', { name: /save changes/i }));

    await waitFor(() => {
      expect(updateUserRole).toHaveBeenCalledWith('user-1', 'MODERATOR');
      expect(updateUserStatus).toHaveBeenCalledWith('user-1', 'DISABLED');
    });
  });

  it('deactivates an active user', async () => {
    renderView();
    await screen.findByText('admin@example.com');

    await fireEvent.click(screen.getByRole('button', { name: /deactivate/i }));

    await waitFor(() => {
      expect(updateUserStatus).toHaveBeenCalledWith('user-1', 'DISABLED');
    });
  });
});
