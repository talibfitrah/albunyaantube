import '@testing-library/jest-dom';
import { render, screen, fireEvent, waitFor } from '@testing-library/vue';
import { createI18n } from 'vue-i18n';
import { beforeEach, describe, expect, it, onTestFinished, vi } from 'vitest';
import ChannelDetailModal from '@/components/exclusions/ChannelDetailModal.vue';
import { messages } from '@/locales/messages';
import {
  fetchChannelExclusions,
  addChannelExclusion,
  removeChannelExclusion
} from '@/services/exclusions';
import {
  getChannelDetails,
  getChannelVideos,
  getChannelPlaylists,
  getChannelShorts
} from '@/services/youtubeService';

vi.mock('@/services/exclusions', () => ({
  fetchChannelExclusions: vi.fn(),
  addChannelExclusion: vi.fn(),
  removeChannelExclusion: vi.fn()
}));

vi.mock('@/services/youtubeService', () => ({
  getChannelDetails: vi.fn(),
  getChannelVideos: vi.fn(),
  getChannelPlaylists: vi.fn(),
  getChannelShorts: vi.fn()
}));

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  messages
});

function renderModal(props = {}) {
  return render(ChannelDetailModal, {
    props: {
      open: true,
      channelId: 'channel-123',
      channelYoutubeId: 'UCxxxxxx',
      ...props
    },
    global: {
      plugins: [i18n]
    }
  });
}

describe('ChannelDetailModal', () => {
  beforeEach(() => {
    vi.clearAllMocks();

    // Default mock implementations
    vi.mocked(getChannelDetails).mockResolvedValue({
      channel: {
        name: 'Test Channel',
        avatarUrl: 'https://example.com/avatar.jpg',
        subscriberCount: 100000,
        id: 'UCxxxxxx'
      },
      videos: []
    });

    vi.mocked(fetchChannelExclusions).mockResolvedValue({
      videos: ['video1', 'video2'],
      playlists: ['playlist1'],
      liveStreams: [],
      shorts: [],
      posts: []
    });

    vi.mocked(getChannelVideos).mockResolvedValue({
      items: [
        {
          id: 'video1',
          title: 'Video 1',
          thumbnailUrl: 'https://example.com/thumb1.jpg',
          publishedAt: '2025-01-01T00:00:00Z'
        },
        {
          id: 'video3',
          title: 'Video 3',
          thumbnailUrl: 'https://example.com/thumb3.jpg',
          publishedAt: '2025-01-03T00:00:00Z'
        }
      ],
      nextPageToken: 'token123'
    });

    vi.mocked(getChannelPlaylists).mockResolvedValue({
      items: [
        {
          id: 'playlist1',
          title: 'Playlist 1',
          thumbnailUrl: 'https://example.com/playlist1.jpg',
          itemCount: 10
        },
        {
          id: 'playlist2',
          title: 'Playlist 2',
          thumbnailUrl: 'https://example.com/playlist2.jpg',
          itemCount: 5
        }
      ],
      nextPageToken: null
    });
  });

  it('should render modal when open', async () => {
    renderModal();

    await waitFor(() => {
      expect(screen.getByText('Test Channel')).toBeInTheDocument();
    });
  });

  it('should not render modal when closed', () => {
    renderModal({ open: false });

    expect(screen.queryByText('Test Channel')).not.toBeInTheDocument();
  });

  it('should display channel details in header', async () => {
    renderModal();

    await waitFor(() => {
      expect(screen.getByText('Test Channel')).toBeInTheDocument();
      expect(screen.getByText(/100,000 subscribers/i)).toBeInTheDocument();
    });
  });

  it('should load channel videos on initial load', async () => {
    renderModal();

    await waitFor(() => {
      expect(getChannelDetails).toHaveBeenCalledWith('UCxxxxxx');
      expect(fetchChannelExclusions).toHaveBeenCalledWith('channel-123');
      expect(getChannelVideos).toHaveBeenCalledWith('UCxxxxxx', undefined, undefined);
    });
  });

  it('should display videos tab by default', async () => {
    renderModal();

    await waitFor(() => {
      expect(screen.getByText('Video 1')).toBeInTheDocument();
      expect(screen.getByText('Video 3')).toBeInTheDocument();
    });
  });

  it('should switch to playlists tab', async () => {
    renderModal();

    await waitFor(() => {
      expect(screen.getByText('Video 1')).toBeInTheDocument();
    });

    const playlistsTab = screen.getByText('Playlists');
    await fireEvent.click(playlistsTab);

    await waitFor(() => {
      expect(screen.getByText('Playlist 1')).toBeInTheDocument();
      expect(screen.getByText('Playlist 2')).toBeInTheDocument();
    });
  });

  it('should show excluded items with different styling', async () => {
    renderModal();

    await waitFor(() => {
      const excludedVideo = screen.getByText('Video 1').closest('.item');
      const normalVideo = screen.getByText('Video 3').closest('.item');

      expect(excludedVideo).toHaveClass('excluded');
      expect(normalVideo).not.toHaveClass('excluded');
    });
  });

  it('should show "Remove Exclusion" button for excluded items', async () => {
    renderModal();

    await waitFor(() => {
      const excludedItem = screen.getByText('Video 1').closest('.item');
      expect(excludedItem?.querySelector('button')?.textContent).toContain('Remove Exclusion');
    });
  });

  it('should show "Exclude" button for non-excluded items', async () => {
    renderModal();

    await waitFor(() => {
      const normalItem = screen.getByText('Video 3').closest('.item');
      expect(normalItem?.querySelector('button')?.textContent).toContain('Exclude');
    });
  });

  it('should add exclusion when clicking Exclude button', async () => {
    vi.mocked(addChannelExclusion).mockResolvedValue({
      videos: ['video1', 'video2', 'video3'],
      playlists: ['playlist1'],
      liveStreams: [],
      shorts: [],
      posts: []
    });

    renderModal();

    await waitFor(() => {
      expect(screen.getByText('Video 3')).toBeInTheDocument();
    });

    const normalItem = screen.getByText('Video 3').closest('.item');
    const excludeButton = normalItem?.querySelector('button.exclude') as HTMLElement;

    await fireEvent.click(excludeButton);

    await waitFor(() => {
      expect(addChannelExclusion).toHaveBeenCalledWith('channel-123', 'video', 'video3');
    });
  });

  it('should remove exclusion when clicking Remove Exclusion button', async () => {
    vi.mocked(removeChannelExclusion).mockResolvedValue({
      videos: ['video2'],
      playlists: ['playlist1'],
      liveStreams: [],
      shorts: [],
      posts: []
    });

    renderModal();

    await waitFor(() => {
      expect(screen.getByText('Video 1')).toBeInTheDocument();
    });

    const excludedItem = screen.getByText('Video 1').closest('.item');
    const removeButton = excludedItem?.querySelector('button.remove') as HTMLElement;

    await fireEvent.click(removeButton);

    await waitFor(() => {
      expect(removeChannelExclusion).toHaveBeenCalledWith('channel-123', 'video', 'video1');
    });
  });

  it('should emit updated event after adding exclusion', async () => {
    vi.mocked(addChannelExclusion).mockResolvedValue({
      videos: ['video1', 'video2', 'video3'],
      playlists: ['playlist1'],
      liveStreams: [],
      shorts: [],
      posts: []
    });

    const { emitted } = renderModal();

    await waitFor(() => {
      expect(screen.getByText('Video 3')).toBeInTheDocument();
    });

    const normalItem = screen.getByText('Video 3').closest('.item');
    const excludeButton = normalItem?.querySelector('button.exclude') as HTMLElement;

    await fireEvent.click(excludeButton);

    await waitFor(() => {
      expect(emitted()).toHaveProperty('updated');
    });
  });

  it('should emit close event when clicking close button', async () => {
    const { emitted } = renderModal();

    await waitFor(() => {
      expect(screen.getByText('Test Channel')).toBeInTheDocument();
    });

    const closeButton = screen.getByLabelText('Close');
    await fireEvent.click(closeButton);

    expect(emitted()).toHaveProperty('close');
  });

  it('should perform search when search button is clicked', async () => {
    renderModal();

    await waitFor(() => {
      expect(screen.getByText('Video 1')).toBeInTheDocument();
    });

    const searchInput = screen.getByPlaceholderText(/search within channel/i);
    await fireEvent.update(searchInput, 'test query');

    const searchButton = screen.getByText('🔍');
    await fireEvent.click(searchButton);

    await waitFor(() => {
      expect(getChannelVideos).toHaveBeenCalledWith('UCxxxxxx', undefined, 'test query');
    });
  });

  it('should show loading state initially', () => {
    renderModal();

    expect(screen.getByText(/Loading/i)).toBeInTheDocument();
  });

  it('should show no results message when items list is empty', async () => {
    vi.mocked(getChannelVideos).mockResolvedValue({
      items: [],
      nextPageToken: null
    });

    renderModal();

    await waitFor(() => {
      expect(screen.getByText(/No results found/i)).toBeInTheDocument();
    });
  });

  it('should disable action buttons when loading', async () => {
    // Create a deferred promise to control when the API call resolves
    let resolveExclusion: (value: void) => void;
    const exclusionPromise = new Promise<void>((resolve) => {
      resolveExclusion = resolve;
    });
    vi.mocked(addChannelExclusion).mockReturnValue(exclusionPromise);

    renderModal();

    await waitFor(() => {
      expect(screen.getByText('Video 3')).toBeInTheDocument();
    });

    const normalItem = screen.getByText('Video 3').closest('.item');
    const excludeButton = normalItem?.querySelector('button.exclude') as HTMLButtonElement;

    // Click and check if button gets disabled
    fireEvent.click(excludeButton);

    // Button should be disabled during API call
    await waitFor(() => {
      expect(excludeButton.disabled).toBe(true);
    });

    // Resolve the promise to complete the test
    resolveExclusion!();
  });

  it('should reset state when modal closes and reopens', async () => {
    const { rerender } = renderModal({ open: true });

    await waitFor(() => {
      expect(screen.getByText('Video 1')).toBeInTheDocument();
    });

    // Close modal
    await rerender({ open: false, channelId: 'channel-123', channelYoutubeId: 'UCxxxxxx' });

    // Reopen modal
    await rerender({ open: true, channelId: 'channel-123', channelYoutubeId: 'UCxxxxxx' });

    // Should reload data
    await waitFor(() => {
      expect(getChannelDetails).toHaveBeenCalledTimes(2);
    });
  });

  // Regression (prod): scrolling to the bottom showed "Loading more…" forever.
  it('loads the next page when scrolled to the bottom', async () => {
    vi.mocked(getChannelVideos)
      .mockReset()
      .mockResolvedValueOnce({ items: [{ id: 'video1', title: 'Video 1' }], nextPageToken: 'token123' } as any)
      .mockResolvedValueOnce({ items: [{ id: 'video4', title: 'Video 4' }], nextPageToken: null } as any);
    renderModal();
    await waitFor(() => expect(screen.getByText('Video 1')).toBeInTheDocument());

    // jsdom reports 0 for scroll metrics, so the container is "at the bottom".
    document.body.querySelector('.content-list')!.dispatchEvent(new Event('scroll'));

    await waitFor(() => {
      expect(getChannelVideos).toHaveBeenLastCalledWith('UCxxxxxx', 'token123', undefined);
      expect(screen.getByText('Video 4')).toBeInTheDocument();
    });
    expect(document.body.querySelector('.loading-more')).toBeNull();
  });

  // A failed later page must not wipe the rows already shown; Retry re-fetches that page only.
  it('keeps loaded rows when a later page fails and retries that page', async () => {
    vi.mocked(getChannelVideos)
      .mockReset()
      .mockResolvedValueOnce({ items: [{ id: 'video1', title: 'Video 1' }], nextPageToken: 'token123' } as any)
      .mockRejectedValueOnce(new Error('Page 2 failed'))
      .mockResolvedValueOnce({ items: [{ id: 'video4', title: 'Video 4' }], nextPageToken: null } as any);
    renderModal();
    await waitFor(() => expect(screen.getByText('Video 1')).toBeInTheDocument());

    document.body.querySelector('.content-list')!.dispatchEvent(new Event('scroll'));
    await waitFor(() => expect(screen.getByText('Page 2 failed')).toBeInTheDocument());
    expect(screen.getByText('Video 1')).toBeInTheDocument();

    await fireEvent.click(screen.getByText('Retry'));
    await waitFor(() => expect(screen.getByText('Video 4')).toBeInTheDocument());
    expect(screen.getByText('Video 1')).toBeInTheDocument();
    expect(screen.queryByText('Page 2 failed')).toBeNull();
    expect(getChannelVideos).toHaveBeenLastCalledWith('UCxxxxxx', 'token123', undefined);
    expect(getChannelDetails).toHaveBeenCalledTimes(1);
  });

  // A search page can land after init failed (it ran while exclusions were loading): its rows have
  // no exclusion state, so they stay hidden and the next search re-runs init.
  it('hides a search page that landed after init failed, and re-inits on the next search', async () => {
    let failExclusions!: (e: Error) => void;
    vi.mocked(fetchChannelExclusions).mockReturnValueOnce(new Promise((_r, reject) => { failExclusions = reject; }));
    vi.mocked(getChannelVideos).mockReset()
      .mockResolvedValueOnce({ items: [{ id: 'video9', title: 'Needle' }], nextPageToken: 'p2' } as any)
      .mockResolvedValue({ items: [], nextPageToken: null } as any);
    renderModal();
    await waitFor(() => expect(fetchChannelExclusions).toHaveBeenCalled());

    await fireEvent.update(screen.getByPlaceholderText(/search within channel/i), 'needle');
    await fireEvent.click(screen.getByText('🔍'));
    await waitFor(() => expect(getChannelVideos).toHaveBeenCalledTimes(1));
    failExclusions(new Error('Exclusions failed'));

    await waitFor(() => expect(screen.getByText('Exclusions failed')).toBeInTheDocument());
    expect(screen.queryByText('Needle')).toBeNull();
    await fireEvent.click(screen.getByText('🔍'));
    await waitFor(() => expect(getChannelDetails).toHaveBeenCalledTimes(2));
  });

  // Init succeeded, so a search after a later-page error needs no re-init (details + exclusions).
  it('searches without re-initializing after a later page failed', async () => {
    vi.mocked(getChannelVideos)
      .mockReset()
      .mockResolvedValueOnce({ items: [{ id: 'video1', title: 'Video 1' }], nextPageToken: 'token123' } as any)
      .mockRejectedValueOnce(new Error('Page 2 failed'))
      .mockResolvedValueOnce({ items: [{ id: 'video9', title: 'Needle' }], nextPageToken: null } as any);
    renderModal();
    await waitFor(() => expect(screen.getByText('Video 1')).toBeInTheDocument());
    document.body.querySelector('.content-list')!.dispatchEvent(new Event('scroll'));
    await waitFor(() => expect(screen.getByText('Page 2 failed')).toBeInTheDocument());

    await fireEvent.update(screen.getByPlaceholderText(/search within channel/i), 'needle');
    await fireEvent.click(screen.getByText('🔍'));

    await waitFor(() => expect(screen.getByText('Needle')).toBeInTheDocument());
    expect(getChannelVideos).toHaveBeenLastCalledWith('UCxxxxxx', undefined, 'needle');
    expect(getChannelDetails).toHaveBeenCalledTimes(1);
  });

  // A scroll fired while a tab switch re-initializes must not start page 1 itself:
  // init's own loadMore would then return early and flash "No results".
  it('ignores scroll while the tab is initializing', async () => {
    renderModal();
    await waitFor(() => expect(screen.getByText('Video 1')).toBeInTheDocument());

    let releaseDetails!: () => void;
    vi.mocked(getChannelDetails).mockImplementationOnce(
      () => new Promise((resolve) => { releaseDetails = () => resolve({ channel: { name: 'Test Channel' }, videos: [] } as any); })
    );
    await fireEvent.click(screen.getByText('Playlists'));
    document.body.querySelector('.content-list')!.dispatchEvent(new Event('scroll'));
    expect(getChannelPlaylists).not.toHaveBeenCalled();

    releaseDetails();
    await waitFor(() => expect(screen.getByText('Playlist 1')).toBeInTheDocument());
    expect(getChannelPlaylists).toHaveBeenCalledTimes(1);
  });

  // A page too short to scroll must still pull the next one (no scroll event ever fires).
  it('loads the next page when the first page does not fill the list', async () => {
    const spy = vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get').mockReturnValue(500);
    onTestFinished(() => spy.mockRestore());
    vi.mocked(getChannelVideos)
      .mockReset()
      .mockResolvedValueOnce({ items: [{ id: 'video1', title: 'Video 1' }], nextPageToken: 'token123' } as any)
      .mockResolvedValueOnce({ items: [{ id: 'video4', title: 'Video 4' }], nextPageToken: null } as any);
    renderModal();

    await waitFor(() => expect(screen.getByText('Video 4')).toBeInTheDocument());
    expect(getChannelVideos).toHaveBeenCalledTimes(2);
  });

  const flush = () => new Promise((r) => setTimeout(r, 20));
  const propsFor = (id: string) => ({ channelId: `reg-${id}`, channelYoutubeId: `UC${id}` });

  it('drops a late initial load from the previously opened channel', async () => {
    let releaseA!: () => void;
    vi.mocked(getChannelDetails).mockImplementation((id: string) =>
      id === 'UCA'
        ? new Promise((resolve) => { releaseA = () => resolve({ channel: { name: 'Channel A' }, videos: [] } as any); })
        : Promise.resolve({ channel: { name: 'Channel B' }, videos: [] } as any)
    );
    vi.mocked(fetchChannelExclusions).mockImplementation(async (id: string) =>
      ({ videos: id === 'reg-A' ? ['video3'] : [], playlists: [] } as any)
    );
    const { rerender } = renderModal(propsFor('A'));
    await rerender({ open: false, ...propsFor('A') });
    await rerender({ open: true, ...propsFor('B') });
    await waitFor(() => expect(screen.getByText('Video 3')).toBeInTheDocument());

    releaseA();
    await flush();

    expect(screen.getByText('Channel B')).toBeInTheDocument();
    expect(screen.getByText('Video 3').closest('.item')).not.toHaveClass('excluded');
  });

  it('drops a late exclusions fetch from the previously opened channel', async () => {
    let releaseA!: () => void;
    vi.mocked(fetchChannelExclusions).mockImplementation((id: string) =>
      id === 'reg-A'
        ? new Promise((resolve) => { releaseA = () => resolve({ videos: ['video3'], playlists: [] } as any); })
        : Promise.resolve({ videos: [], playlists: [] } as any)
    );
    const { rerender } = renderModal(propsFor('A'));
    await waitFor(() => expect(fetchChannelExclusions).toHaveBeenCalledWith('reg-A'));
    await rerender({ open: false, ...propsFor('A') });
    await rerender({ open: true, ...propsFor('B') });
    await waitFor(() => expect(screen.getByText('Video 3')).toBeInTheDocument());

    releaseA();
    await flush();

    expect(screen.getByText('Video 3').closest('.item')).not.toHaveClass('excluded');
  });

  // Videos and Shorts share the video exclusion list.
  it('keeps a late video exclusion write after switching to Shorts', async () => {
    let finishAdd!: () => void;
    vi.mocked(addChannelExclusion).mockReturnValueOnce(new Promise((resolve) => { finishAdd = () => resolve({} as any); }));
    vi.mocked(fetchChannelExclusions).mockResolvedValue({ videos: [], playlists: [] } as any);
    vi.mocked(getChannelShorts).mockResolvedValueOnce({ items: [{ id: 'video3', title: 'Video 3' }], nextPageToken: null } as any);
    renderModal();
    await waitFor(() => expect(screen.getByText('Video 3')).toBeInTheDocument());
    await fireEvent.click(screen.getByText('Video 3').closest('.item')!.querySelector('button.exclude')!);

    await fireEvent.click(screen.getByText('Shorts'));
    await waitFor(() => expect(getChannelShorts).toHaveBeenCalled());
    await waitFor(() => expect(screen.getByText('Video 3')).toBeInTheDocument());
    finishAdd();
    await flush();

    expect(screen.getByText('Video 3').closest('.item')).toHaveClass('excluded');
  });

  it('drops a late exclusion write from the previously opened channel', async () => {
    let finishAdd!: () => void;
    vi.mocked(addChannelExclusion).mockReturnValueOnce(new Promise((resolve) => { finishAdd = () => resolve({} as any); }));
    vi.mocked(fetchChannelExclusions).mockResolvedValue({ videos: [], playlists: [] } as any);
    const { rerender } = renderModal(propsFor('A'));
    await waitFor(() => expect(screen.getByText('Video 3')).toBeInTheDocument());
    await fireEvent.click(screen.getByText('Video 3').closest('.item')!.querySelector('button.exclude')!);

    await rerender({ open: false, ...propsFor('A') });
    await rerender({ open: true, ...propsFor('B') });
    await waitFor(() => expect(screen.getByText('Video 3')).toBeInTheDocument());
    finishAdd();
    await flush();

    expect(screen.getByText('Video 3').closest('.item')).not.toHaveClass('excluded');
  });

  it('drops a late video exclusion write after switching to Playlists', async () => {
    let finishAdd!: () => void;
    vi.mocked(addChannelExclusion).mockReturnValueOnce(new Promise((resolve) => { finishAdd = () => resolve({} as any); }));
    vi.mocked(fetchChannelExclusions).mockResolvedValue({ videos: [], playlists: [] } as any);
    // A playlist row whose id equals the video id: the video write must not mark it.
    vi.mocked(getChannelPlaylists).mockResolvedValueOnce({ items: [{ id: 'video3', title: 'Same id playlist' }], nextPageToken: null } as any);
    renderModal();
    await waitFor(() => expect(screen.getByText('Video 3')).toBeInTheDocument());
    await fireEvent.click(screen.getByText('Video 3').closest('.item')!.querySelector('button.exclude')!);

    await fireEvent.click(screen.getByText('Playlists'));
    await waitFor(() => expect(screen.getByText('Same id playlist')).toBeInTheDocument());
    finishAdd();
    await flush();

    expect(screen.getByText('Same id playlist').closest('.item')).not.toHaveClass('excluded');
  });

  it('re-runs initialization when searching after it failed', async () => {
    vi.mocked(fetchChannelExclusions).mockRejectedValueOnce(new Error('Exclusions down'));
    renderModal();
    await waitFor(() => expect(screen.getByText('Exclusions down')).toBeInTheDocument());

    await fireEvent.update(screen.getByPlaceholderText(/search within channel/i), 'video');
    await fireEvent.click(screen.getByText('🔍'));

    await waitFor(() => expect(screen.getByText('Video 1').closest('.item')).toHaveClass('excluded'));
    expect(fetchChannelExclusions).toHaveBeenCalledTimes(2);
  });

  it('stops initializing once unmounted', async () => {
    let releaseDetails!: () => void;
    vi.mocked(getChannelDetails).mockReturnValueOnce(new Promise((resolve) => { releaseDetails = () => resolve({ channel: { name: 'C' }, videos: [] } as any); }));
    const { unmount } = renderModal();
    unmount();

    releaseDetails();
    await flush();

    expect(fetchChannelExclusions).not.toHaveBeenCalled();
  });

  // Channel lists use search.list (101 quota units a page): no unattended empty-page scanning.
  it('pauses at the first empty page and loads one page per Load more', async () => {
    vi.useFakeTimers();
    onTestFinished(() => vi.useRealTimers());
    const spy = vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get').mockReturnValue(500);
    onTestFinished(() => spy.mockRestore());
    vi.mocked(getChannelVideos).mockReset().mockResolvedValue({ items: [], nextPageToken: 'more' } as any);
    renderModal();

    await vi.advanceTimersByTimeAsync(10_000);
    expect(getChannelVideos).toHaveBeenCalledTimes(1);
    expect(screen.queryByText(/No more items|No results found/i)).toBeNull();

    await fireEvent.click(screen.getByText('Load more'));
    await vi.advanceTimersByTimeAsync(10_000);
    expect(getChannelVideos).toHaveBeenCalledTimes(2);
  });
});
