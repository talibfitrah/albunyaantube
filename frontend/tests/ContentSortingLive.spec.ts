import '@testing-library/jest-dom';
import { render, screen, fireEvent, within } from '@testing-library/vue';
import { createI18n } from 'vue-i18n';
import { describe, it, expect, vi } from 'vitest';
import ContentSortingView from '@/views/ContentSortingView.vue';
import { messages } from '@/locales/messages';

const status = vi.hoisted(() => ({ fn: vi.fn() }));
const add = vi.hoisted(() => ({ fn: vi.fn(), toastError: vi.fn() }));
const svc = vi.hoisted(() => ({
  getAllCategories: vi.fn((): Promise<unknown[]> => Promise.resolve([
    { id: 'live', name: 'Channels Live', type: 'LIVE', subcategories: [] },
    { id: 'other', name: 'Other', type: 'REGULAR', subcategories: [] }
  ])),
  approved: vi.fn((): unknown[] => [
    { id: 'pl-9', type: 'playlist', youtubeId: null, title: 'Some playlist', thumbnailUrl: null },
    { id: 'ch-9', type: 'channel', youtubeId: 'UCz', title: 'Some channel', thumbnailUrl: null }
  ]),
  getCategoryContentOrder: vi.fn((): Promise<unknown[]> => Promise.resolve([
    { contentId: 'channel_UCa', contentType: 'channel', title: 'Zad', thumbnailUrl: null, position: 0, youtubeId: 'UCa' },
    { contentId: 'doc-1', contentType: 'video', title: 'A stream', thumbnailUrl: null, position: 1, youtubeId: 'vid00000001' }
  ]))
}));

vi.mock('@/utils/toast', () => ({
  toast: { success: vi.fn(), error: (m: string) => add.toastError(m), info: vi.fn(), warning: vi.fn() }
}));
vi.mock('@/services/categoryService', () => ({ getAllCategories: () => svc.getAllCategories() }));

vi.mock('@/services/sortOrder', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/services/sortOrder')>()),
  getCategorySortOrder: () => Promise.resolve([
    { id: 'live', name: 'Channels Live', icon: null, localizedNames: null, displayOrder: 17, contentCount: 2, parentCategoryId: null },
    { id: 'other', name: 'Other', icon: null, localizedNames: null, displayOrder: 18, contentCount: 0, parentCategoryId: null }
  ]),
  addContentToCategorySort: (...a: unknown[]) => add.fn(...a),
  getCategoryContentOrder: () => svc.getCategoryContentOrder(),
  getApprovedContent: () => Promise.resolve(svc.approved())
}));
vi.mock('@/services/liveChannels', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/services/liveChannels')>()),
  getLiveStatus: () => status.fn()
}));

vi.mock('@/components/exclusions/ChannelDetailModal.vue', () => ({ default: {
  props: ['open', 'channelId', 'channelYoutubeId'], emits: ['close'],
  template: '<div v-if="open" data-testid="channel-detail">{{ channelId }}|{{ channelYoutubeId }}<button @click="$emit(\'close\')">close-detail</button></div>'
} }));
vi.mock('@/components/exclusions/PlaylistDetailModal.vue', () => ({ default: {
  props: ['open', 'playlistId', 'playlistYoutubeId'], emits: ['close'],
  template: '<div v-if="open" data-testid="playlist-detail">{{ playlistId }}|{{ playlistYoutubeId }}<button @click="$emit(\'close\')">close-detail</button></div>'
} }));

const ok = () => Promise.resolve({
  suspectSince: '2026-10-17T01:20:00Z',
  categories: [{ id: 'live', entries: [
    { contentType: 'channel', contentId: 'channel_UCa', channelId: 'UCaaaaaaaaaaaaaaaaaaaaaa', status: 'LIVE', lives: [{ id: 'x', title: 't', served: 'SERVED' }] },
    { contentType: 'video', contentId: 'doc-1', youtubeId: 'vid00000001', status: 'MISSING', misses: 1 }
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

  it('links every live row to YouTube in a new tab', async () => {
    status.fn.mockImplementation(ok);
    mount();

    await fireEvent.click(await screen.findByText('Channels Live'));
    await screen.findByText('Live · 1');

    const links = screen.getAllByRole('link', { name: 'Open on YouTube' });
    expect(links.map(a => a.getAttribute('href'))).toEqual([
      'https://www.youtube.com/channel/UCaaaaaaaaaaaaaaaaaaaaaa/live', 'https://www.youtube.com/watch?v=vid00000001']);
    for (const a of links) {
      expect(a).toHaveAttribute('target', '_blank');
      expect(a).toHaveAttribute('rel', 'noopener noreferrer');
    }
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
      await fireEvent.click(await screen.findByRole('checkbox', { name: 'Some channel' }));
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

  it("a slow add reply does not put its list under another category opened meanwhile", async () => {
    let resolve!: (v: unknown) => void;
    add.fn.mockImplementation(() => new Promise(r => { resolve = r; }));
    status.fn.mockImplementation(ok);
    mount();
    await fireEvent.click(await screen.findByText('Channels Live'));
    await fireEvent.click(await screen.findByText(/Add Content/));
    await fireEvent.click(await screen.findByRole('checkbox', { name: 'Some channel' }));
    await fireEvent.click(screen.getByText('Add to Category'));
    await fireEvent.click(screen.getByText('Cancel'));
    await fireEvent.click(await screen.findByText('Other'));
    await screen.findByText('Zad');
    resolve([{ contentId: 'ch-9', contentType: 'channel', title: 'Late channel', thumbnailUrl: null, position: 0, youtubeId: 'UCz' }]);
    await new Promise(r => setTimeout(r, 20));
    expect(screen.queryByText('Late channel')).toBeNull();
  });

  it('a failed category fetch still fills the add picker, playlists included (Cubic r9 #26)', async () => {
    status.fn.mockImplementation(ok);
    svc.getAllCategories.mockRejectedValueOnce(new Error('Request failed with status code 500'));
    mount();
    await fireEvent.click(await screen.findByText('Other'));
    await fireEvent.click(await screen.findByText(/Add Content/));

    expect(await screen.findByText('Some channel')).toBeInTheDocument();
    expect(screen.getByText('Some playlist')).toBeInTheDocument();
  });

  it('the add picker counts every approved item, as the Library does, and says why some cannot be added', async () => {
    status.fn.mockImplementation(ok);
    svc.approved.mockReturnValueOnce([
      { id: 'channel_UCa', type: 'channel', youtubeId: 'UCa', title: 'Zad', thumbnailUrl: null, visibility: 'PUBLIC' },
      { id: 'doc-1', type: 'video', youtubeId: 'vid00000001', title: 'A stream', thumbnailUrl: null, visibility: 'PUBLIC' },
      { id: 'ch-p', type: 'channel', youtubeId: 'UCp', title: 'Granted', thumbnailUrl: null, visibility: 'PERSONAL' },
      { id: 'ch-9', type: 'channel', youtubeId: 'UCz', title: 'Some channel', thumbnailUrl: null, visibility: 'PUBLIC' }
    ]);
    mount();
    await fireEvent.click(await screen.findByText('Other'));
    await fireEvent.click(await screen.findByText(/Add Content/));
    await screen.findByText('Some channel');
    const dialog = within(screen.getByRole('dialog'));
    const tab = (label: string) =>
      dialog.getByRole('button', { name: new RegExp(`^${label}`) }).textContent!.replace(/\s+/g, ' ').trim();

    // The headline is the approved total; the two already filed and the personal grant are named, not hidden.
    expect([tab('All'), tab('Channels'), tab('Videos')]).toEqual(['All 4', 'Channels 3', 'Videos 1']);
    expect(dialog.getByText('2 already in this category · 1 limited to specific users')).toBeInTheDocument();
    expect(document.querySelectorAll('.add-content-item').length).toBe(1);
    expect(dialog.queryByText('Granted')).toBeNull();

    await fireEvent.click(dialog.getByRole('button', { name: /^Videos/ }));
    expect(dialog.getByText('1 already in this category')).toBeInTheDocument();
  });

  it('the add picker renders more rows on scroll until every item is reachable (no fixed cap)', async () => {
    status.fn.mockImplementation(ok);
    svc.approved.mockReturnValueOnce(Array.from({ length: 350 }, (_, i) =>
      ({ id: `ch-${i}`, type: 'channel', youtubeId: `UC${i}`, title: `Bulk ${i}`, thumbnailUrl: null })));
    mount();
    await fireEvent.click(await screen.findByText('Other'));
    await fireEvent.click(await screen.findByText(/Add Content/));
    await screen.findByText('Bulk 0');
    const rows = () => document.querySelectorAll('.add-content-item').length;
    expect(rows()).toBe(100);

    const body = document.querySelector('.modal-body')!;
    vi.useFakeTimers();
    try {
      for (let i = 0; i < 4; i++) {
        await fireEvent.scroll(body); // jsdom: zero heights put the list "at the bottom"
        await vi.advanceTimersByTimeAsync(100); // past the throttle
      }
    } finally {
      vi.useRealTimers();
    }
    expect(rows()).toBe(350);
    expect(screen.getByText('Bulk 349')).toBeInTheDocument();
  });

  it('the add picker shows per-type counts on the tabs and a Load more button that draws the next page', async () => {
    status.fn.mockImplementation(ok);
    // Every fifth item a video: 50 videos, 200 channels.
    svc.approved.mockReturnValueOnce(Array.from({ length: 250 }, (_, i) => i % 5 === 0
      ? { id: `v-${i}`, type: 'video', youtubeId: `vid${i}`, title: `Bulk ${i}`, thumbnailUrl: null }
      : { id: `ch-${i}`, type: 'channel', youtubeId: `UC${i}`, title: `Bulk ${i}`, thumbnailUrl: null }));
    mount();
    await fireEvent.click(await screen.findByText('Other'));
    await fireEvent.click(await screen.findByText(/Add Content/));
    await screen.findByText('Bulk 0');
    const tab = (label: string) =>
      within(screen.getByRole('dialog')).getByRole('button', { name: new RegExp(`^${label}`) }).textContent!.replace(/\s+/g, ' ').trim();
    expect([tab('All'), tab('Channels'), tab('Playlists'), tab('Videos')]).toEqual(['All 250', 'Channels 200', 'Playlists 0', 'Videos 50']);

    const more = screen.getByRole('button', { name: 'Load more (100 of 250)' });
    more.focus(); // a browser click focuses the button; jsdom's does not
    await fireEvent.click(more);
    // More pages remain: focus stays on the button, so Space/Enter loads again.
    expect(document.activeElement).toBe(await screen.findByRole('button', { name: 'Load more (200 of 250)' }));
    await fireEvent.click(document.activeElement!);
    await screen.findByText('Bulk 249');
    expect(document.querySelectorAll('.add-content-item').length).toBe(250);
    expect(screen.queryByRole('button', { name: /Load more/ })).toBeNull();
    // The button is gone: focus moved to the dialog itself (not a row or the search field).
    expect(document.activeElement).toBe(screen.getByRole('dialog'));

    await fireEvent.update(screen.getByPlaceholderText(/Search/), 'Bulk 24');
    // "Bulk 24" matches 24 and 240-249: videos 240 and 245, the other 9 channels.
    expect([tab('All'), tab('Channels'), tab('Videos')]).toEqual(['All 11', 'Channels 9', 'Videos 2']);
  });

  it('the add picker keeps growing without a scroll when its rows fit on screen (tall viewport)', async () => {
    status.fn.mockImplementation(ok);
    svc.approved.mockReturnValueOnce(Array.from({ length: 350 }, (_, i) =>
      ({ id: `ch-${i}`, type: 'channel', youtubeId: `UC${i}`, title: `Bulk ${i}`, thumbnailUrl: null })));
    // A laid-out container whose content never overflows (scrollHeight stays 0 in jsdom).
    const tall = vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get').mockReturnValue(5000);
    try {
      mount();
      await fireEvent.click(await screen.findByText('Other'));
      await fireEvent.click(await screen.findByText(/Add Content/));
      await screen.findByText('Bulk 0');
      vi.useFakeTimers();
      await vi.advanceTimersByTimeAsync(1000); // each growth re-checks fill after the throttle
      expect(document.querySelectorAll('.add-content-item').length).toBe(350);
    } finally {
      vi.useRealTimers();
      tall.mockRestore();
    }
  });

  it('an older reload of the list does not overwrite a newer add reply (Cubic r9 #27)', async () => {
    let addReply!: (v: unknown) => void;
    let reloadReply!: (v: unknown[]) => void;
    add.fn.mockImplementation(() => new Promise(r => { addReply = r; }));
    status.fn.mockImplementation(ok);
    mount();
    await fireEvent.click(await screen.findByText('Channels Live'));
    await fireEvent.click(await screen.findByText(/Add Content/));
    await fireEvent.click(await screen.findByRole('checkbox', { name: 'Some channel' }));
    await fireEvent.click(screen.getByText('Add to Category'));
    await fireEvent.click(screen.getByText('Cancel'));
    const before = await svc.getCategoryContentOrder();
    svc.getCategoryContentOrder.mockImplementationOnce(() => new Promise(r => { reloadReply = r; }));
    await fireEvent.click(screen.getByText('Channels Live')); // collapse
    await fireEvent.click(screen.getByText('Channels Live')); // expand: the reload starts before the add reply
    const late = { contentId: 'ch-9', contentType: 'channel', title: 'Late channel', thumbnailUrl: null, position: 2, youtubeId: 'UCz' };
    addReply([...before, late]);
    await new Promise(r => setTimeout(r, 20));
    reloadReply(before);
    await new Promise(r => setTimeout(r, 20));

    expect(screen.getByText('Late channel')).toBeInTheDocument();
  });
});

describe('Content Sorting add-content: see a channel or playlist before adding it', () => {
  const openPicker = async () => {
    status.fn.mockImplementation(ok);
    mount();
    await fireEvent.click(await screen.findByText('Other'));
    await fireEvent.click(await screen.findByText(/Add Content/));
    await screen.findByText('Some channel');
  };

  it("clicking a channel's title opens its detail window and does not select it", async () => {
    await openPicker();

    await fireEvent.click(screen.getByRole('button', { name: 'Some channel' }));

    expect(screen.getByTestId('channel-detail')).toHaveTextContent('ch-9|UCz');
    expect(screen.queryByText(/selected/i)).toBeNull();
  });

  it("clicking a playlist's thumbnail opens its detail window and does not select it", async () => {
    await openPicker();
    const row = screen.getByText('Some playlist').closest('.add-content-item')!;

    await fireEvent.click(row.querySelector('.add-content-thumb')!);

    expect(screen.getByTestId('playlist-detail')).toHaveTextContent('pl-9|');
    expect((row.querySelector('input[type=checkbox]') as HTMLInputElement).checked).toBe(false);
  });

  it('the rest of the row still selects, and the picker stays open behind a closed detail window', async () => {
    await openPicker();
    await fireEvent.click(screen.getByRole('button', { name: 'Some channel' }));
    await fireEvent.click(screen.getByText('close-detail'));
    expect(screen.queryByTestId('channel-detail')).toBeNull();

    const row = screen.getByText('Some channel').closest('.add-content-item')!;
    await fireEvent.click(row.querySelector('.type-badge')!);

    expect((row.querySelector('input[type=checkbox]') as HTMLInputElement).checked).toBe(true);
  });

  it('opens from the keyboard, lets focus into the detail window, and hands it back on close', async () => {
    await openPicker();
    const title = screen.getByRole('button', { name: 'Some channel' });
    title.focus();
    await fireEvent.click(title); // what Enter / Space do on a native button

    const close = screen.getByText('close-detail');
    close.focus();
    expect(document.activeElement).toBe(close); // the picker's focus trap must not pull it back

    await fireEvent.click(close);
    expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Some channel' }));
  });

  it('Escape closes the detail window, not the picker, and focus returns to the title', async () => {
    await openPicker();
    await fireEvent.click(screen.getByRole('button', { name: 'Some channel' }));

    await fireEvent.keyDown(document.activeElement ?? document.body, { key: 'Escape' });

    expect(screen.queryByTestId('channel-detail')).toBeNull();
    expect(screen.getByText('Add Content to Category')).toBeInTheDocument();
    expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Some channel' }));
  });

  it('the covered picker cannot be reached while the detail window is open', async () => {
    await openPicker();
    const picker = document.querySelector('.modal-container[role="dialog"]')!;

    await fireEvent.click(screen.getByRole('button', { name: 'Some channel' }));
    expect(picker).toHaveAttribute('inert');

    await fireEvent.click(screen.getByText('close-detail'));
    expect(picker).not.toHaveAttribute('inert');
  });
});
