/**
 * PendingApprovalsView — the by-user tab.
 *
 * Reviewing bulk imports from ordinary users is per-person work ("what did Ahmed send me"),
 * which a flat chronological queue cannot express. The by-user tab lists them by submitter with a
 * count; the main queue lists everything pending, phone imports included and marked as such.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/vue';
import { createI18n } from 'vue-i18n';
import { messages } from '@/locales/messages';

const getPendingApprovalsMock = vi.fn();
const getPendingSubmittersMock = vi.fn();
const getMySubmissionsMock = vi.fn();
const getPendingCountMock = vi.fn();
const getAllCategoriesMock = vi.fn();
const approveItemMock = vi.fn();

vi.mock('@/services/approvalService', () => ({
  getPendingApprovals: (...args: unknown[]) => getPendingApprovalsMock(...args),
  getPendingSubmitters: (...args: unknown[]) => getPendingSubmittersMock(...args),
  getMySubmissions: (...args: unknown[]) => getMySubmissionsMock(...args),
  approveItem: (...args: unknown[]) => approveItemMock(...args),
  rejectItem: vi.fn().mockResolvedValue(undefined),
  getPendingCount: () => getPendingCountMock()
}));

vi.mock('@/services/categoryService', () => ({ getAllCategories: () => getAllCategoriesMock() }));
vi.mock('@/utils/formatters', () => ({ getThumbnailUrl: () => null, getThumbnailFallbacks: () => [] }));
vi.mock('@/composables/useToast', () => ({
  useToast: () => ({ warning: vi.fn(), success: vi.fn(), error: vi.fn() })
}));
vi.mock('@/components/exclusions/ChannelDetailModal.vue', () => ({ default: { template: '<div />' } }));
vi.mock('@/components/exclusions/PlaylistDetailModal.vue', () => ({ default: { template: '<div />' } }));
vi.mock('@/components/VideoPreviewModal.vue', () => ({ default: { template: '<div />' } }));
vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ isAdmin: true, isModerator: false, user: { uid: 'admin-uid' } })
}));

function buildI18n() {
  return createI18n({ legacy: false, locale: 'en', messages: { en: messages.en } });
}

function makeItem(id: string, title: string, source: string) {
  return {
    id,
    type: 'channel' as const,
    youtubeId: `yt-${id}`,
    title,
    description: '',
    thumbnailUrl: '',
    categories: ['cat-1'],
    submittedAt: '2024-01-01T00:00:00Z',
    submittedBy: 'uid-ahmed',
    submittedByLabel: 'Ahmed',
    source,
    status: 'PENDING' as const
  };
}

async function renderView() {
  const { default: PendingApprovalsView } = await import('@/views/PendingApprovalsView.vue');
  return render(PendingApprovalsView, { global: { plugins: [buildI18n()] } });
}

beforeEach(() => {
  vi.clearAllMocks();
  getPendingCountMock.mockResolvedValue(0);
  getAllCategoriesMock.mockResolvedValue([{ id: 'cat-1', label: 'Education', subcategories: [] }]);
  getPendingSubmittersMock.mockResolvedValue([]);
  getPendingApprovalsMock.mockResolvedValue({ items: [], nextCursor: null });
  approveItemMock.mockResolvedValue(undefined);
});

describe('the queue lists everything pending, phone imports included', () => {
  it('asks the server for every pending item and marks the phone imports with who sent them', async () => {
    getPendingApprovalsMock.mockResolvedValue({
      items: [makeItem('a', 'Moderator Pick', 'MODERATOR'), makeItem('b', 'Ahmeds Import', 'USER_IMPORT')],
      nextCursor: null
    });

    const { container } = await renderView();

    await waitFor(() => expect(screen.getByText('Ahmeds Import')).toBeTruthy());
    expect(getPendingApprovalsMock.mock.calls[0][0].scope).toBeUndefined();
    const badges = container.querySelectorAll('.source-user-import');
    expect(badges.length).toBe(1);
    expect(badges[0].textContent?.trim()).toBe('Phone import · Ahmed');
  });

  it('approves a phone import from the queue exactly as the by-user tab does', async () => {
    getPendingApprovalsMock.mockResolvedValue({
      items: [makeItem('b', 'Ahmeds Import', 'USER_IMPORT')],
      nextCursor: null
    });

    await renderView();
    await waitFor(() => expect(screen.getByText('Ahmeds Import')).toBeTruthy());
    await fireEvent.click(screen.getByRole('button', { name: /approve for this user/i }));

    await waitFor(() => expect(approveItemMock).toHaveBeenCalledWith('b', 'channel', undefined, undefined, 'PERSONAL'));
    await waitFor(() => expect(screen.queryByText('Ahmeds Import')).toBeNull());
  });

  it('refreshes the by-user counts after a phone import is decided in the queue', async () => {
    getPendingSubmittersMock.mockResolvedValue([
      { uid: 'uid-ahmed', label: 'Ahmed', email: 'a@x.com', pendingCount: 1 }
    ]);
    getPendingApprovalsMock.mockResolvedValue({
      items: [makeItem('b', 'Ahmeds Import', 'USER_IMPORT')],
      nextCursor: null
    });

    await renderView();
    await waitFor(() => expect(screen.getByRole('button', { name: 'By user (1)' })).toBeTruthy());
    await fireEvent.click(screen.getByRole('button', { name: /approve for this user/i }));
    await waitFor(() => expect(screen.queryByText('Ahmeds Import')).toBeNull());
    // The tally it showed is out of date now: no number until the roll-up is read again.
    expect(screen.getByRole('button', { name: 'By user' })).toBeTruthy();

    getPendingSubmittersMock.mockResolvedValue([]);
    await fireEvent.click(screen.getByRole('button', { name: 'By user' }));
    await waitFor(() => expect(getPendingSubmittersMock).toHaveBeenCalledTimes(2));
  });

  it('fetches exactly one page per load', async () => {
    getPendingApprovalsMock.mockResolvedValue({
      items: [makeItem('a', 'Moderator Pick', 'MODERATOR')],
      nextCursor: 'c1'
    });

    await renderView();

    await waitFor(() => expect(screen.getByText('Moderator Pick')).toBeTruthy());
    expect(getPendingApprovalsMock).toHaveBeenCalledTimes(1);
  });

  it('labels the queue with everything pending and the by-user tab with the imports', async () => {
    // 12 pending in total, 5 of them phone imports. The header and the Queue tab say 12, the same
    // number as the Dashboard and the Content Library "Pending" totals: the queue lists them all.
    getPendingCountMock.mockResolvedValue(12);
    getPendingSubmittersMock.mockResolvedValue([
      { uid: 'uid-ahmed', label: 'Ahmed', email: 'a@x.com', pendingCount: 3 },
      { uid: 'uid-fatima', label: 'Fatima', email: 'f@x.com', pendingCount: 2 }
    ]);
    getPendingApprovalsMock.mockResolvedValue({
      items: [makeItem('a', 'Moderator Pick', 'MODERATOR')],
      nextCursor: null
    });

    const { container } = await renderView();

    await waitFor(() => expect(screen.getByRole('button', { name: /queue \(12\)/i })).toBeTruthy());
    expect(screen.getByRole('button', { name: /by user \(5\)/i })).toBeTruthy();
    expect(container.querySelector('.stat-value')?.textContent).toBe('12');
  });

  it('keeps the header total when the by-user tab is open', async () => {
    getPendingCountMock.mockResolvedValue(12);
    getPendingSubmittersMock.mockResolvedValue([
      { uid: 'uid-ahmed', label: 'Ahmed', email: 'a@x.com', pendingCount: 3 },
      { uid: 'uid-fatima', label: 'Fatima', email: 'f@x.com', pendingCount: 2 }
    ]);

    const { container } = await renderView();
    await fireEvent.click(screen.getByRole('button', { name: /by user/i }));

    await waitFor(() => expect(screen.getByRole('button', { name: /by user \(5\)/i })).toBeTruthy());
    expect(container.querySelector('.stat-value')?.textContent).toBe('12');
  });

  it('never badges fewer than the by-user tab holds when the total is unavailable', async () => {
    getPendingCountMock.mockResolvedValue(-1);
    getPendingSubmittersMock.mockResolvedValue([
      { uid: 'uid-ahmed', label: 'Ahmed', email: 'a@x.com', pendingCount: 5 }
    ]);

    const { container } = await renderView();
    await fireEvent.click(screen.getByRole('button', { name: /by user/i }));

    await waitFor(() => expect(container.querySelector('.stat-value')?.textContent).toBe('5'));
  });
});

describe('the by-user tab', () => {
  it('lists each submitter with how much they have waiting', async () => {
    getPendingSubmittersMock.mockResolvedValue([
      { uid: 'uid-ahmed', label: 'Ahmed', email: 'a@x.com', pendingCount: 42 },
      { uid: 'uid-fatima', label: 'Fatima', email: 'f@x.com', pendingCount: 3 }
    ]);

    await renderView();
    await fireEvent.click(screen.getByRole('button', { name: /by user/i }));

    await waitFor(() => expect(screen.getByText('Ahmed')).toBeTruthy());
    expect(screen.getByText('42 pending')).toBeTruthy();
    expect(screen.getByText('Fatima')).toBeTruthy();
    expect(screen.getByText('3 pending')).toBeTruthy();
  });

  it('loads only that submitter\'s items when one is chosen', async () => {
    getPendingSubmittersMock.mockResolvedValue([
      { uid: 'uid-ahmed', label: 'Ahmed', email: 'a@x.com', pendingCount: 2 }
    ]);
    getPendingApprovalsMock.mockResolvedValue({
      items: [makeItem('x', 'Ahmeds Import', 'USER_IMPORT')],
      nextCursor: null
    });

    await renderView();
    await fireEvent.click(screen.getByRole('button', { name: /by user/i }));
    await waitFor(() => expect(screen.getByText('Ahmed')).toBeTruthy());
    await fireEvent.click(screen.getByText('Ahmed'));

    // The drill-down shows the person's imports, still marked as user imports.
    await waitFor(() => expect(screen.getByText('Ahmeds Import')).toBeTruthy());
    expect(screen.getByText('User import')).toBeTruthy();
    expect(getPendingApprovalsMock).toHaveBeenLastCalledWith(
      expect.objectContaining({ submittedBy: 'uid-ahmed' })
    );
  });

  it('says so when nobody has anything waiting', async () => {
    getPendingSubmittersMock.mockResolvedValue([]);

    await renderView();
    await fireEvent.click(screen.getByRole('button', { name: /by user/i }));

    await waitFor(() =>
      expect(screen.getByText('No user imports are waiting for review.')).toBeTruthy()
    );
  });
});

describe('stale replies', () => {
  it('a late queue reply never replaces the submitter list opened after a tab switch', async () => {
    getPendingCountMock.mockResolvedValue(5);
    getPendingSubmittersMock.mockResolvedValue([
      { uid: 'uid-ahmed', label: 'Ahmed', email: 'a@x.com', pendingCount: 5 }
    ]);
    let releaseQueue!: (v: unknown) => void;
    getPendingApprovalsMock
      .mockReturnValueOnce(new Promise(r => { releaseQueue = r; }))
      .mockResolvedValue({ items: [makeItem('x', 'Ahmeds Import', 'USER_IMPORT')], nextCursor: null });

    await renderView();
    await fireEvent.click(screen.getByRole('button', { name: /by user/i }));
    await waitFor(() => expect(screen.getByText('Ahmed')).toBeTruthy());
    await fireEvent.click(screen.getByText('Ahmed'));
    await waitFor(() => expect(screen.getByText('Ahmeds Import')).toBeTruthy());

    releaseQueue({ items: [], nextCursor: null });
    await new Promise(r => setTimeout(r, 50));
    expect(screen.getByText('Ahmeds Import')).toBeTruthy();
  });
});
