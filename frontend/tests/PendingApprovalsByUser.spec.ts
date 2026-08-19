/**
 * PendingApprovalsView — the by-user tab.
 *
 * Reviewing bulk imports from ordinary users is per-person work ("what did Ahmed send me"),
 * which a flat chronological queue cannot express. User imports move to their own tab, listed by
 * submitter with a count; the main queue keeps the moderator-curated submissions.
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

vi.mock('@/services/approvalService', () => ({
  getPendingApprovals: (...args: unknown[]) => getPendingApprovalsMock(...args),
  getPendingSubmitters: (...args: unknown[]) => getPendingSubmittersMock(...args),
  getMySubmissions: (...args: unknown[]) => getMySubmissionsMock(...args),
  approveItem: vi.fn().mockResolvedValue(undefined),
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
});

describe('the queue and the by-user tab are cleanly split', () => {
  it('asks the server for moderator submissions only', async () => {
    getPendingApprovalsMock.mockResolvedValue({
      items: [makeItem('a', 'Moderator Pick', 'MODERATOR')],
      nextCursor: null
    });

    await renderView();

    await waitFor(() => expect(screen.getByText('Moderator Pick')).toBeTruthy());
    // Server-side. Filtering a fetched page in the browser let the list come back empty with
    // more pages behind it, and left the pending badge counting rows the queue never showed.
    expect(getPendingApprovalsMock).toHaveBeenCalledWith(
      expect.objectContaining({ scope: 'MODERATOR_QUEUE' })
    );
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

  it('counts only what the queue shows, not the imports it hands to the other tab', async () => {
    // 12 pending in total, 5 of them phone imports — the queue lists 7 and must say 7, or the
    // reviewer is left hunting for five items that are not on this tab.
    getPendingCountMock.mockResolvedValue(12);
    getPendingSubmittersMock.mockResolvedValue([
      { uid: 'uid-ahmed', label: 'Ahmed', email: 'a@x.com', pendingCount: 3 },
      { uid: 'uid-fatima', label: 'Fatima', email: 'f@x.com', pendingCount: 2 }
    ]);
    getPendingApprovalsMock.mockResolvedValue({
      items: [makeItem('a', 'Moderator Pick', 'MODERATOR')],
      nextCursor: null
    });

    await renderView();

    await waitFor(() => expect(screen.getByText('7')).toBeTruthy());
  });

  it('shows the imports tally on the by-user tab', async () => {
    getPendingCountMock.mockResolvedValue(12);
    getPendingSubmittersMock.mockResolvedValue([
      { uid: 'uid-ahmed', label: 'Ahmed', email: 'a@x.com', pendingCount: 3 },
      { uid: 'uid-fatima', label: 'Fatima', email: 'f@x.com', pendingCount: 2 }
    ]);

    await renderView();
    await fireEvent.click(screen.getByRole('button', { name: /by user/i }));

    await waitFor(() => expect(screen.getByText('5')).toBeTruthy());
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

    // The drill-down shows the person's imports — the source filter that hides them from the
    // main queue must not hide them here too — and still marks them as user imports.
    await waitFor(() => expect(screen.getByText('Ahmeds Import')).toBeTruthy());
    expect(screen.getByText('User import')).toBeTruthy();
    // The drill-down is that person's imports; the queue scope must not narrow it further.
    expect(getPendingApprovalsMock).toHaveBeenLastCalledWith(
      expect.objectContaining({ submittedBy: 'uid-ahmed', scope: undefined })
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
