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

/** Build a page with N synthetic entries */
function buildPage(count: number, nextCursor: string | null): AuditPage {
  return {
    data: Array.from({ length: count }, (_, i) => ({
      id: `audit-${i + 1}`,
      actorUid: `actor-${i + 1}@example.com`,
      actorDisplayName: `Actor ${i + 1}`,
      action: 'users:create',
      entityType: 'USER',
      entityId: `user-${i + 1}`,
      details: {},
      timestamp: '2025-10-01T10:00:00Z'
    })),
    pageInfo: {
      cursor: null,
      nextCursor,
      hasNext: nextCursor !== null,
      limit: count
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

  describe('Load-more cursor pagination', () => {
    it('shows 50 rows and Load-more button on first page with nextCursor', async () => {
      (fetchAuditLogPage as unknown as vi.Mock).mockResolvedValueOnce(
        buildPage(50, 'CURSOR-1')
      );

      renderView();

      // Wait for rows to appear
      await waitFor(() => {
        expect(screen.getAllByTestId('audit-row')).toHaveLength(50);
      });

      // Load-more button must be visible
      expect(screen.getByTestId('audit-load-more')).toBeVisible();
    });

    it('appends 30 more rows on Load-more click and hides button when no nextCursor', async () => {
      (fetchAuditLogPage as unknown as vi.Mock)
        .mockResolvedValueOnce(buildPage(50, 'CURSOR-1'))
        .mockResolvedValueOnce(buildPage(30, null));

      renderView();

      // Wait for first page
      await waitFor(() => {
        expect(screen.getAllByTestId('audit-row')).toHaveLength(50);
      });

      // Click Load-more
      const loadMoreBtn = screen.getByTestId('audit-load-more');
      await fireEvent.click(loadMoreBtn);

      // Should now have 80 rows total (append, not replace)
      await waitFor(() => {
        expect(screen.getAllByTestId('audit-row')).toHaveLength(80);
      });

      // Load-more button must be gone
      expect(screen.queryByTestId('audit-load-more')).toBeNull();
    });

    it('resets list and cursor when filter changes', async () => {
      vi.useFakeTimers();

      // First load: 50 rows + nextCursor
      (fetchAuditLogPage as unknown as vi.Mock)
        .mockResolvedValueOnce(buildPage(50, 'CURSOR-1'))
        // Filter change triggers reload from scratch: 1 row, no nextCursor
        .mockResolvedValueOnce(createPage());

      renderView();

      await waitFor(() => {
        expect(screen.getAllByTestId('audit-row')).toHaveLength(50);
      });

      // Change actor filter
      const actorInput = screen.getByPlaceholderText(/actor email/i);
      await fireEvent.update(actorInput, 'other@example.com');
      vi.runAllTimers();

      // After reset, only 1 row should exist
      await waitFor(() => {
        expect(screen.getAllByTestId('audit-row')).toHaveLength(1);
      });

      // Load-more should not be visible (nextCursor is null)
      expect(screen.queryByTestId('audit-load-more')).toBeNull();

      vi.useRealTimers();
    });
  });
});
