import '@testing-library/jest-dom';
import { render, screen, fireEvent } from '@testing-library/vue';
import { createI18n } from 'vue-i18n';
import { describe, it, expect, vi } from 'vitest';
import ContentSortingView from '@/views/ContentSortingView.vue';
import { messages } from '@/locales/messages';

const status = vi.hoisted(() => ({ fn: vi.fn() }));
const add = vi.hoisted(() => ({ fn: vi.fn(), toastError: vi.fn() }));

vi.mock('@/utils/toast', () => ({
  toast: { success: vi.fn(), error: (m: string) => add.toastError(m), info: vi.fn(), warning: vi.fn() }
}));
vi.mock('@/services/categoryService', () => ({
  getAllCategories: () => Promise.resolve([
    { id: 'live', name: 'Channels Live', type: 'LIVE', subcategories: [] },
    { id: 'other', name: 'Other', type: 'REGULAR', subcategories: [] }
  ])
}));

vi.mock('@/services/sortOrder', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/services/sortOrder')>()),
  getCategorySortOrder: () => Promise.resolve([
    { id: 'live', name: 'Channels Live', icon: null, localizedNames: null, displayOrder: 17, contentCount: 2, parentCategoryId: null },
    { id: 'other', name: 'Other', icon: null, localizedNames: null, displayOrder: 18, contentCount: 0, parentCategoryId: null }
  ]),
  addContentToCategorySort: (...a: unknown[]) => add.fn(...a),
  getCategoryContentOrder: () => Promise.resolve([
    { contentId: 'channel_UCa', contentType: 'channel', title: 'Zad', thumbnailUrl: null, position: 0, youtubeId: 'UCa' },
    { contentId: 'doc-1', contentType: 'video', title: 'A stream', thumbnailUrl: null, position: 1, youtubeId: 'vid00000001' }
  ]),
  getApprovedContent: () => Promise.resolve({ truncated: false, items: [
    { id: 'pl-9', type: 'playlist', youtubeId: null, title: 'Some playlist', thumbnailUrl: null },
    { id: 'ch-9', type: 'channel', youtubeId: 'UCz', title: 'Some channel', thumbnailUrl: null }
  ] })
}));
vi.mock('@/services/liveChannels', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/services/liveChannels')>()),
  getLiveStatus: () => status.fn()
}));

const ok = () => Promise.resolve({
  suspectSince: '2026-10-17T01:20:00Z',
  categories: [{ id: 'live', entries: [
    { contentType: 'channel', contentId: 'channel_UCa', status: 'LIVE', lives: [{ id: 'x', title: 't', served: 'SERVED' }] },
    { contentType: 'video', contentId: 'doc-1', status: 'MISSING', misses: 1 }
  ] }]
});
const mount = (locale = 'en') =>
  render(ContentSortingView, { global: { plugins: [createI18n({ legacy: false, locale, messages })] } });

describe('Content Sorting on a live category (spec §8)', () => {
  it('shows the note, the suspect banner and a chip per row', async () => {
    status.fn.mockImplementation(ok);
    mount();

    await fireEvent.click(await screen.findByText('Channels Live'));

    expect(await screen.findByText(/Users see each channel's current live streams/)).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('Live check suspect since');
    expect(screen.getByText('Live · 1')).toBeInTheDocument();
    expect(screen.getByText('Not live (1 of 2)')).toBeInTheDocument();
  });

  it('renders in Arabic', async () => {
    status.fn.mockImplementation(ok);
    mount('ar');

    await fireEvent.click(await screen.findByText('Channels Live'));

    expect(await screen.findByText(/يرى المستخدمون البث المباشر الحالي/)).toBeInTheDocument();
  });

  it('a 500 from the status call says live status is unavailable (F-M9)', async () => {
    status.fn.mockImplementation(() => Promise.reject({ response: { status: 500 } }));
    mount();
    expect(await screen.findByText('Live status unavailable right now.')).toBeInTheDocument();
  });

  it('a failed status call (moderator 403) shows no live UI and still lists the rows', async () => {
    status.fn.mockImplementation(() => Promise.reject({ response: { status: 403 } }));
    mount();

    await fireEvent.click(await screen.findByText('Channels Live'));

    expect(await screen.findByText('Zad')).toBeInTheDocument();
    expect(screen.queryByText(/Users see each channel's current live streams/)).toBeNull();
    expect(screen.queryByRole('status')).toBeNull();
    expect(screen.queryByText(/^Live ·/)).toBeNull();
  });

  it('does not offer playlists in the add-content modal of a live category', async () => {
    status.fn.mockImplementation(ok);
    mount();

    await fireEvent.click(await screen.findByText('Channels Live'));
    await screen.findByText('Live · 1');
    await fireEvent.click(await screen.findByText(/Add Content/));

    expect(await screen.findByText('Some channel')).toBeInTheDocument();
    expect(screen.queryByText('Some playlist')).not.toBeInTheDocument();
  });

  it('offers no playlists for a live category while the live status is still loading', async () => {
    status.fn.mockImplementation(() => new Promise(() => {}));
    mount();

    await fireEvent.click(await screen.findByText('Channels Live'));
    await fireEvent.click(await screen.findByText(/Add Content/));

    expect(await screen.findByText('Some channel')).toBeInTheDocument();
    expect(screen.queryByText('Some playlist')).not.toBeInTheDocument();
  });

  describe('an add refusal that returns after the modal moved on', () => {
    const refusal = () => Object.assign(new Error('x'), { response: { data: { code: 'LIVE_LINK_NOT_LIVE' } } });
    const startAdd = async () => {
      let reject!: (e: unknown) => void;
      add.fn.mockImplementation(() => new Promise((_, r) => { reject = r; }));
      add.toastError.mockClear();
      status.fn.mockImplementation(ok);
      mount();
      await fireEvent.click(await screen.findByText('Channels Live'));
      await fireEvent.click(await screen.findByText(/Add Content/));
      await fireEvent.click(await screen.findByText('Some channel'));
      await fireEvent.click(screen.getByText('Add to Category'));
      return () => reject(refusal());
    };

    it('shows in the modal it was started from', async () => {
      const fail = await startAdd();
      fail();
      expect(await screen.findByRole('alert')).toHaveTextContent("isn't live on its channel's Live tab");
      expect(add.toastError).not.toHaveBeenCalled();
    });

    it('is toasted when the modal was closed meanwhile', async () => {
      const fail = await startAdd();
      await fireEvent.click(screen.getByText('Cancel'));
      fail();
      await vi.waitFor(() => expect(add.toastError).toHaveBeenCalledWith(expect.stringContaining("isn't live on its channel's Live tab")));
    });

    it('is toasted, not shown in another category\'s modal opened meanwhile', async () => {
      const fail = await startAdd();
      await fireEvent.click(screen.getByText('Cancel'));
      await fireEvent.click(await screen.findByText('Other'));
      await fireEvent.click(await screen.findByText(/Add Content/));
      await screen.findByText('Some channel');
      fail();
      await vi.waitFor(() => expect(add.toastError).toHaveBeenCalled());
      expect(screen.queryByRole('alert')).toBeNull();
    });
  });
});
