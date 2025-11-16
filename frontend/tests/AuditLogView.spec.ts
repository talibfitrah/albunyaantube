import '@testing-library/jest-dom';
import { render, screen, fireEvent, waitFor } from '@testing-library/vue';
import { createI18n } from 'vue-i18n';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import AuditLogView from '@/views/AuditLogView.vue';
import { messages } from '@/locales/messages';
import { fetchAuditLogPage } from '@/services/adminAudit';
import type { AuditPage } from '@/types/admin';

vi.mock('@/services/adminAudit', () => ({
  fetchAuditLogPage: vi.fn()
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
        actorUid: 'admin@example.com',
        actorDisplayName: 'Admin User',
        action: 'users:create',
        entityType: 'USER',
        entityId: 'user-2',
        details: { email: 'moderator@example.com' },
        timestamp: '2025-10-01T10:00:00Z'
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
    (fetchAuditLogPage as unknown as vi.Mock).mockResolvedValue(createPage());
  });

  it('renders audit entries and filters by actor', async () => {
    vi.useFakeTimers();
    renderView();

    await screen.findByText('admin@example.com');

    const actorInput = screen.getByPlaceholderText(/actor email/i);
    await fireEvent.update(actorInput, 'moderator@example.com');

    vi.runAllTimers();

    await waitFor(() => {
      expect((fetchAuditLogPage as unknown as vi.Mock).mock.calls.at(-1)[0]).toMatchObject({
        actorId: 'moderator@example.com'
      });
    });

    vi.useRealTimers();
  });
});
