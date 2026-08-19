/**
 * [ADMIN-IMPORT-01] PendingApprovalsView — user-import badge + the optional category on approve
 *
 * Tests:
 * 1. A USER_IMPORT item renders the "User import" badge.
 * 2. A non-USER_IMPORT item does not render the badge.
 * 3. Approve is available whether or not the item has a category.
 * 4. An uncategorised item approved with no category sends no override.
 * 5. On approve, a picked category is sent as categoryOverride.
 * 6. On approve for a pre-categorised item, categoryOverride is NOT sent.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/vue';
import { createI18n } from 'vue-i18n';
import { messages } from '@/locales/messages';

const approveItemMock = vi.fn().mockResolvedValue(undefined);
const getPendingApprovalsMock = vi.fn();
const getMySubmissionsMock = vi.fn();
const getPendingCountMock = vi.fn().mockResolvedValue(1);
const getAllCategoriesMock = vi.fn().mockResolvedValue([
  { id: 'cat-1', label: 'Education', subcategories: [] },
  { id: 'cat-2', label: 'Science', subcategories: [] }
]);

vi.mock('@/services/approvalService', () => ({
  getPendingApprovals: (...args: unknown[]) => getPendingApprovalsMock(...args),
  getMySubmissions: (...args: unknown[]) => getMySubmissionsMock(...args),
  approveItem: (...args: unknown[]) => approveItemMock(...args),
  rejectItem: vi.fn().mockResolvedValue(undefined),
  getPendingCount: () => getPendingCountMock()
}));

vi.mock('@/services/categoryService', () => ({
  getAllCategories: () => getAllCategoriesMock()
}));

vi.mock('@/utils/formatters', () => ({
  getThumbnailUrl: () => null,
  getThumbnailFallbacks: () => []
}));

vi.mock('@/composables/useToast', () => ({
  useToast: () => ({ warning: vi.fn() })
}));

vi.mock('@/components/exclusions/ChannelDetailModal.vue', () => ({
  default: { template: '<div />' }
}));
vi.mock('@/components/exclusions/PlaylistDetailModal.vue', () => ({
  default: { template: '<div />' }
}));
vi.mock('@/components/VideoPreviewModal.vue', () => ({
  default: { template: '<div />' }
}));

// Admin user — approve buttons are shown
vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({
    isAdmin: true,
    isModerator: false,
    user: { uid: 'admin-uid' }
  })
}));

function buildI18n() {
  return createI18n({
    legacy: false,
    locale: 'en',
    messages: { en: messages.en }
  });
}

function makeItem(overrides: {
  id?: string;
  categories?: string[];
  source?: string;
} = {}) {
  return {
    id: overrides.id ?? 'item-1',
    type: 'channel' as const,
    youtubeId: 'yt-abc',
    title: 'My Channel',
    description: '',
    thumbnailUrl: '',
    categories: overrides.categories ?? [],
    submittedAt: '2024-01-01T00:00:00Z',
    submittedBy: 'user@example.com',
    source: overrides.source,
    status: 'PENDING' as const
  };
}

async function renderView(items: ReturnType<typeof makeItem>[]) {
  getPendingApprovalsMock.mockResolvedValue({ items, nextCursor: null });
  const { default: PendingApprovalsView } = await import('@/views/PendingApprovalsView.vue');
  return render(PendingApprovalsView, { global: { plugins: [buildI18n()] } });
}

describe('PendingApprovalsView — User-import badge', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    getPendingCountMock.mockResolvedValue(1);
    getAllCategoriesMock.mockResolvedValue([
      { id: 'cat-1', label: 'Education', subcategories: [] }
    ]);
  });

  it('renders "User import" badge for a USER_IMPORT item', async () => {
    await renderView([makeItem({ source: 'USER_IMPORT' })]);

    await waitFor(() => {
      expect(screen.getByText('User import')).toBeTruthy();
    });
  });

  it('does not render the badge for a non-USER_IMPORT item (ADMIN source)', async () => {
    await renderView([makeItem({ source: 'ADMIN', categories: ['cat-1'] })]);

    await waitFor(() => {
      // Item renders (channel type badge visible)
      expect(screen.getByText('Channel')).toBeTruthy();
      // But User import badge is absent
      expect(screen.queryByText('User import')).toBeNull();
    });
  });

  it('does not render the badge when source is undefined', async () => {
    await renderView([makeItem({ categories: ['cat-1'] })]);

    await waitFor(() => {
      expect(screen.queryByText('User import')).toBeNull();
    });
  });
});

describe('PendingApprovalsView — category is optional when approving', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    approveItemMock.mockResolvedValue(undefined);
    getPendingCountMock.mockResolvedValue(1);
    getAllCategoriesMock.mockResolvedValue([
      { id: 'cat-1', label: 'Education', subcategories: [] }
    ]);
  });

  it('approve is available for an item with no categories', async () => {
    await renderView([makeItem({ source: 'MODERATOR', categories: [] })]);

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /^approve$/i })).not.toBeDisabled();
    });
  });

  it('approve is available for an item that already has categories', async () => {
    await renderView([makeItem({ source: 'ADMIN', categories: ['cat-1'] })]);

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /^approve$/i })).not.toBeDisabled();
    });
  });

  it('approves an uncategorised item with no override when none is picked', async () => {
    await renderView([makeItem({ id: 'ch-bare', source: 'MODERATOR', categories: [] })]);

    const approve = await waitFor(() => screen.getByRole('button', { name: /^approve$/i }));
    await fireEvent.click(approve);

    await waitFor(() => {
      expect(approveItemMock).toHaveBeenCalledWith('ch-bare', 'channel', undefined);
    });
  });

  it('sends the picked category as categoryOverride for an empty-category item', async () => {
    getPendingApprovalsMock.mockResolvedValue({
      items: [makeItem({ id: 'ch-99', source: 'MODERATOR', categories: [] })],
      nextCursor: null
    });

    const { default: PendingApprovalsView } = await import('@/views/PendingApprovalsView.vue');
    render(PendingApprovalsView, { global: { plugins: [buildI18n()] } });

    // The optional per-card category selector is still offered.
    const label = await waitFor(() => screen.getByText('Category (optional)'));
    const select = document.getElementById(label.getAttribute('for')!) as HTMLSelectElement;
    await fireEvent.change(select, { target: { value: 'cat-1' } });

    await fireEvent.click(screen.getByRole('button', { name: /^approve$/i }));

    await waitFor(() => {
      expect(approveItemMock).toHaveBeenCalledWith('ch-99', 'channel', 'cat-1');
    });
  });

  it('does NOT send categoryOverride for a pre-categorised item', async () => {
    getPendingApprovalsMock.mockResolvedValue({
      items: [makeItem({ id: 'ch-cat', source: 'ADMIN', categories: ['cat-1'] })],
      nextCursor: null
    });

    const { default: PendingApprovalsView } = await import('@/views/PendingApprovalsView.vue');
    render(PendingApprovalsView, { global: { plugins: [buildI18n()] } });

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /^approve$/i })).not.toBeDisabled();
    });

    await fireEvent.click(screen.getByRole('button', { name: /^approve$/i }));

    await waitFor(() => {
      expect(approveItemMock).toHaveBeenCalledWith('ch-cat', 'channel', undefined);
    });
  });
});
