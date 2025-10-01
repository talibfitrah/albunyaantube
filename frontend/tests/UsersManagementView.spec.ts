import '@testing-library/jest-dom';
import { render, screen, fireEvent, waitFor, within } from '@testing-library/vue';
import { createI18n } from 'vue-i18n';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import UsersManagementView from '@/views/UsersManagementView.vue';
import { messages } from '@/locales/messages';
import {
  fetchAdminUsersPage,
  createAdminUser,
  updateAdminUser,
  deleteAdminUser
} from '@/services/adminUsers';
import type { AdminUser, AdminUsersPage } from '@/types/admin';

vi.mock('@/services/adminUsers', () => ({
  fetchAdminUsersPage: vi.fn(),
  createAdminUser: vi.fn(),
  updateAdminUser: vi.fn(),
  deleteAdminUser: vi.fn()
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
  roles: ['ADMIN'],
  status: 'ACTIVE',
  lastLoginAt: '2025-09-20T12:00:00Z',
  createdAt: '2025-09-10T08:00:00Z',
  updatedAt: '2025-09-10T08:00:00Z'
};

const secondUser: AdminUser = {
  id: 'user-2',
  email: 'moderator@example.com',
  roles: ['MODERATOR'],
  status: 'DISABLED',
  lastLoginAt: null,
  createdAt: '2025-09-15T08:00:00Z',
  updatedAt: '2025-09-20T08:00:00Z'
};

describe('UsersManagementView', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    (fetchAdminUsersPage as unknown as vi.Mock).mockResolvedValue(createPage([baseUser, secondUser]));
    (createAdminUser as unknown as vi.Mock).mockResolvedValue({ ...baseUser, id: 'user-3', email: 'new@example.com' });
    (updateAdminUser as unknown as vi.Mock).mockResolvedValue({ ...baseUser });
    (deleteAdminUser as unknown as vi.Mock).mockResolvedValue(undefined);
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

    const moderatorCheckbox = within(createDialog).getByLabelText(/moderator/i);
    await fireEvent.click(moderatorCheckbox);

    await fireEvent.click(within(createDialog).getByRole('button', { name: /create user/i }));

    await waitFor(() => {
      expect(createAdminUser).toHaveBeenCalledWith({
        email: 'new@example.com',
        roles: ['ADMIN', 'MODERATOR']
      });
    });
  });

  it('updates user roles and status', async () => {
    renderView();
    await screen.findByText('admin@example.com');

    await fireEvent.click(screen.getAllByRole('button', { name: /edit user/i })[0]);

    const editDialog = await screen.findByRole('dialog', { name: /edit admin@example.com/i });
    const moderatorCheckbox = within(editDialog).getByLabelText(/moderator/i);
    if (!(moderatorCheckbox as HTMLInputElement).checked) {
      await fireEvent.click(moderatorCheckbox);
    }

    const disabledRadio = within(editDialog).getByLabelText(/disabled/i);
    await fireEvent.click(disabledRadio);

    await fireEvent.click(within(editDialog).getByRole('button', { name: /save changes/i }));

    await waitFor(() => {
      expect(updateAdminUser).toHaveBeenCalledWith('user-1', {
        roles: ['ADMIN', 'MODERATOR'],
        status: 'DISABLED'
      });
    });
  });

  it('deactivates an active user', async () => {
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true);
    renderView();
    await screen.findByText('admin@example.com');

    await fireEvent.click(screen.getByRole('button', { name: /deactivate/i }));

    await waitFor(() => {
      expect(deleteAdminUser).toHaveBeenCalledWith('user-1');
    });

    confirmSpy.mockRestore();
  });
});
