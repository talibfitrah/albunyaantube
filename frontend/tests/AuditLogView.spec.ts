import '@testing-library/jest-dom';
import { render, screen, fireEvent, waitFor } from '@testing-library/vue';
import { createI18n } from 'vue-i18n';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import AuditLogView from '@/views/AuditLogView.vue';
import { messages } from '@/locales/messages';
import { fetchAuditPage } from '@/services/adminAudit';
import type { AuditPage } from '@/types/admin';

vi.mock('@/services/adminAudit', () => ({
  fetchAuditPage: vi.fn()
}));

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  messages
});

function renderView() {
  return render(AuditLogView, {
    global: {
      plugins: [i18n]
    }
  });
}

function createPage(): AuditPage {
  return {
    data: [
      {
        id: 'audit-1',
        actor: {
          id: 'user-1',
          email: 'admin@example.com',
          roles: ['ADMIN'],
          status: 'ACTIVE',
          lastLoginAt: '2025-09-20T12:00:00Z',
          createdAt: '2025-09-10T08:00:00Z',
          updatedAt: '2025-09-10T08:00:00Z'
        },
        action: 'users:create',
        entity: {
          type: 'USER',
          id: 'user-2',
          slug: null
        },
        metadata: { email: 'moderator@example.com' },
        createdAt: '2025-10-01T10:00:00Z'
      }
    ],
    pageInfo: {
      cursor: null,
      nextCursor: null,
      hasNext: false,
      limit: 1
    }
  };
}

describe('AuditLogView', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    (fetchAuditPage as unknown as vi.Mock).mockResolvedValue(createPage());
  });

  it('renders audit entries and filters by actor', async () => {
    vi.useFakeTimers();
    renderView();

    await screen.findByText('admin@example.com');

    const actorInput = screen.getByPlaceholderText(/actor email/i);
    await fireEvent.update(actorInput, 'moderator@example.com');

    vi.runAllTimers();

    await waitFor(() => {
      expect((fetchAuditPage as unknown as vi.Mock).mock.calls.at(-1)[0]).toMatchObject({
        actorId: 'moderator@example.com'
      });
    });

    vi.useRealTimers();
  });
});
