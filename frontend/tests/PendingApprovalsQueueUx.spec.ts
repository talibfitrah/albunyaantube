/**
 * PendingApprovalsView — review-queue ergonomics.
 *
 * Field complaints this pins:
 *  1. Acting on the 100th item threw the reviewer back to the top, because every approve/reject
 *     refetched the queue from the first cursor and discarded the loaded pages.
 *  2. Items could only be actioned one at a time.
 *  3. A rejection reason was mandatory, and could not be reused across items.
 *  4. A category was mandatory to approve; it is now optional.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/vue';
import { createI18n } from 'vue-i18n';
import { messages } from '@/locales/messages';

const approveItemMock = vi.fn();
const rejectItemMock = vi.fn();
const getPendingApprovalsMock = vi.fn();
const getMySubmissionsMock = vi.fn();
const getPendingCountMock = vi.fn();
const getAllCategoriesMock = vi.fn();

vi.mock('@/services/approvalService', () => ({
  getPendingApprovals: (...args: unknown[]) => getPendingApprovalsMock(...args),
  getMySubmissions: (...args: unknown[]) => getMySubmissionsMock(...args),
  approveItem: (...args: unknown[]) => approveItemMock(...args),
  rejectItem: (...args: unknown[]) => rejectItemMock(...args),
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

function makeItem(id: string, title: string, categories: string[] = ['cat-1']) {
  return {
    id,
    type: 'channel' as const,
    youtubeId: `yt-${id}`,
    title,
    description: '',
    thumbnailUrl: '',
    categories,
    submittedAt: '2024-01-01T00:00:00Z',
    submittedBy: 'user@example.com',
    source: 'ADMIN',
    status: 'PENDING' as const
  };
}

async function renderView(items: ReturnType<typeof makeItem>[]) {
  getPendingApprovalsMock.mockResolvedValue({ items, nextCursor: null });
  const { default: PendingApprovalsView } = await import('@/views/PendingApprovalsView.vue');
  const utils = render(PendingApprovalsView, { global: { plugins: [buildI18n()] } });
  await waitFor(() => expect(screen.getByText(items[0].title)).toBeTruthy());
  return utils;
}

/** The card checkbox is labelled with the item's title. */
function selectCard(title: string) {
  return fireEvent.click(screen.getByLabelText(`Select ${title}`));
}

beforeEach(() => {
  vi.clearAllMocks();
  approveItemMock.mockResolvedValue(undefined);
  rejectItemMock.mockResolvedValue(undefined);
  getPendingCountMock.mockResolvedValue(2);
  getAllCategoriesMock.mockResolvedValue([
    { id: 'cat-1', label: 'Education', subcategories: [] },
    { id: 'cat-2', label: 'Science', subcategories: [] }
  ]);
});

describe('acting on an item keeps the reviewer in place', () => {
  it('drops the approved item from the queue without refetching it', async () => {
    await renderView([makeItem('a', 'First Channel'), makeItem('b', 'Second Channel')]);

    await fireEvent.click(screen.getAllByRole('button', { name: /^approve$/i })[0]);

    await waitFor(() => expect(screen.queryByText('First Channel')).toBeNull());
    // The rest of the loaded queue is untouched, and no page was refetched — which is what
    // preserves the scroll position.
    expect(screen.getByText('Second Channel')).toBeTruthy();
    expect(getPendingApprovalsMock).toHaveBeenCalledTimes(1);
  });

  it('drops the rejected item from the queue without refetching it', async () => {
    await renderView([makeItem('a', 'First Channel'), makeItem('b', 'Second Channel')]);

    await fireEvent.click(screen.getAllByRole('button', { name: /^reject$/i })[0]);
    await fireEvent.click(await screen.findByRole('button', { name: /^reject submission$/i }));

    await waitFor(() => expect(screen.queryByText('First Channel')).toBeNull());
    expect(screen.getByText('Second Channel')).toBeTruthy();
    expect(getPendingApprovalsMock).toHaveBeenCalledTimes(1);
  });
});

describe('reviewing several items at once', () => {
  it('approves every selected item and clears them from the queue', async () => {
    await renderView([
      makeItem('a', 'First Channel'),
      makeItem('b', 'Second Channel'),
      makeItem('c', 'Third Channel')
    ]);

    await selectCard('First Channel');
    await selectCard('Third Channel');

    await fireEvent.click(screen.getByRole('button', { name: /approve 2/i }));

    await waitFor(() => expect(approveItemMock).toHaveBeenCalledTimes(2));
    expect(approveItemMock.mock.calls.map(c => c[0]).sort()).toEqual(['a', 'c']);
    await waitFor(() => expect(screen.queryByText('First Channel')).toBeNull());
    expect(screen.queryByText('Third Channel')).toBeNull();
    expect(screen.getByText('Second Channel')).toBeTruthy();
  });

  it('applies the bulk category only to the selected items that have none', async () => {
    await renderView([
      makeItem('categorised', 'First Channel', ['cat-1']),
      makeItem('bare', 'Second Channel', [])
    ]);

    await selectCard('First Channel');
    await selectCard('Second Channel');

    const bulkCategory = screen.getByLabelText('Category for uncategorised');
    await fireEvent.update(bulkCategory, 'cat-2');
    await fireEvent.click(screen.getByRole('button', { name: /approve 2/i }));

    await waitFor(() => expect(approveItemMock).toHaveBeenCalledTimes(2));
    const byId = Object.fromEntries(approveItemMock.mock.calls.map(c => [c[0], c[2]]));
    expect(byId['bare']).toBe('cat-2');
    expect(byId['categorised']).toBeUndefined();
  });

  it('rejects every selected item with one shared reason', async () => {
    await renderView([makeItem('a', 'First Channel'), makeItem('b', 'Second Channel')]);

    await selectCard('First Channel');
    await selectCard('Second Channel');

    await fireEvent.click(screen.getByRole('button', { name: /reject 2/i }));
    await fireEvent.update(await screen.findByLabelText(/reason/i), 'Off topic');
    await fireEvent.click(screen.getByRole('button', { name: /^reject submissions$/i }));

    await waitFor(() => expect(rejectItemMock).toHaveBeenCalledTimes(2));
    expect(rejectItemMock.mock.calls.every(c => c[2] === 'Off topic')).toBe(true);
  });
});

describe('optional inputs', () => {
  it('rejects an item when no reason is given', async () => {
    await renderView([makeItem('a', 'First Channel')]);

    await fireEvent.click(screen.getByRole('button', { name: /^reject$/i }));
    await fireEvent.click(await screen.findByRole('button', { name: /^reject submission$/i }));

    await waitFor(() => expect(rejectItemMock).toHaveBeenCalledTimes(1));
    expect(rejectItemMock.mock.calls[0][2]).toBeUndefined();
  });

  it('approves an uncategorised item without asking for a category', async () => {
    await renderView([makeItem('bare', 'First Channel', [])]);

    const approve = screen.getByRole('button', { name: /^approve$/i });
    expect(approve).not.toBeDisabled();

    await fireEvent.click(approve);

    await waitFor(() => expect(approveItemMock).toHaveBeenCalledWith('bare', 'channel', undefined));
  });
});
