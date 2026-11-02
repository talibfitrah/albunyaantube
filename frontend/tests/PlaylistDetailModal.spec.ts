import '@testing-library/jest-dom';
import { render, screen, fireEvent, waitFor } from '@testing-library/vue';
import { createI18n } from 'vue-i18n';
import { beforeEach, describe, expect, it, onTestFinished, vi } from 'vitest';
import PlaylistDetailModal from '@/components/exclusions/PlaylistDetailModal.vue';
import { messages } from '@/locales/messages';
import {
  fetchPlaylistExclusions,
  addPlaylistExclusion,
  removePlaylistExclusion
} from '@/services/exclusions';
import {
  getPlaylistDetails,
  getPlaylistVideos
} from '@/services/youtubeService';

vi.mock('@/services/exclusions', () => ({
  fetchPlaylistExclusions: vi.fn(),
  addPlaylistExclusion: vi.fn(),
  removePlaylistExclusion: vi.fn()
}));

vi.mock('@/services/youtubeService', () => ({
  getPlaylistDetails: vi.fn(),
  getPlaylistVideos: vi.fn()
}));

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  messages
});

function renderModal(props = {}) {
  return render(PlaylistDetailModal, {
    props: {
      open: true,
      playlistId: 'playlist-123',
      playlistYoutubeId: 'PLxxxxxx',
      ...props
    },
    global: {
      plugins: [i18n]
    }
  });
}

describe('PlaylistDetailModal', () => {
  beforeEach(() => {
    vi.clearAllMocks();

    // Default mock implementations
    vi.mocked(getPlaylistDetails).mockResolvedValue({
      id: 'PLxxxxxx',
      title: 'Test Playlist',
      thumbnailUrl: 'https://example.com/playlist.jpg',
      itemCount: 25
    });

    vi.mocked(fetchPlaylistExclusions).mockResolvedValue(['video1', 'video2']);

    vi.mocked(getPlaylistVideos).mockResolvedValue({
      items: [
        {
          id: 'item1',
          videoId: 'video1',
          title: 'Video 1',
          thumbnailUrl: 'https://example.com/thumb1.jpg',
          position: 1
        },
        {
          id: 'item2',
          videoId: 'video3',
          title: 'Video 3',
          thumbnailUrl: 'https://example.com/thumb3.jpg',
          position: 2
        }
      ],
      nextPageToken: 'token123'
    });
  });

  it('should render modal when open', async () => {
    renderModal();

    await waitFor(() => {
      expect(screen.getByText('Test Playlist')).toBeInTheDocument();
    });
  });

  it('should not render modal when closed', () => {
    renderModal({ open: false });

    expect(screen.queryByText('Test Playlist')).not.toBeInTheDocument();
  });

  it('should display playlist details in header', async () => {
    renderModal();

    await waitFor(() => {
      expect(screen.getByText('Test Playlist')).toBeInTheDocument();
      expect(screen.getByText(/25 videos/i)).toBeInTheDocument();
    });
  });

  it('should load playlist videos on initial load', async () => {
    renderModal();

    await waitFor(() => {
      expect(getPlaylistDetails).toHaveBeenCalledWith('PLxxxxxx');
      expect(fetchPlaylistExclusions).toHaveBeenCalledWith('playlist-123');
      expect(getPlaylistVideos).toHaveBeenCalledWith('PLxxxxxx', undefined, undefined);
    });
  });

  it('should display videos with position numbers', async () => {
    renderModal();

    await waitFor(() => {
      expect(screen.getByText('Video 1')).toBeInTheDocument();
      expect(screen.getByText(/Position #1/i)).toBeInTheDocument();
      expect(screen.getByText('Video 3')).toBeInTheDocument();
      expect(screen.getByText(/Position #2/i)).toBeInTheDocument();
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
    vi.mocked(addPlaylistExclusion).mockResolvedValue(['video1', 'video2', 'video3']);

    renderModal();

    await waitFor(() => {
      expect(screen.getByText('Video 3')).toBeInTheDocument();
    });

    const normalItem = screen.getByText('Video 3').closest('.item');
    const excludeButton = normalItem?.querySelector('button.exclude') as HTMLElement;

    await fireEvent.click(excludeButton);

    await waitFor(() => {
      expect(addPlaylistExclusion).toHaveBeenCalledWith('playlist-123', 'video3');
    });
  });

  it('should remove exclusion when clicking Remove Exclusion button', async () => {
    vi.mocked(removePlaylistExclusion).mockResolvedValue(['video2']);

    renderModal();

    await waitFor(() => {
      expect(screen.getByText('Video 1')).toBeInTheDocument();
    });

    const excludedItem = screen.getByText('Video 1').closest('.item');
    const removeButton = excludedItem?.querySelector('button.remove') as HTMLElement;

    await fireEvent.click(removeButton);

    await waitFor(() => {
      expect(removePlaylistExclusion).toHaveBeenCalledWith('playlist-123', 'video1');
    });
  });

  it('should emit updated event after adding exclusion', async () => {
    vi.mocked(addPlaylistExclusion).mockResolvedValue(['video1', 'video2', 'video3']);

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
      expect(screen.getByText('Test Playlist')).toBeInTheDocument();
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

    const searchInput = screen.getByPlaceholderText(/search within playlist/i);
    await fireEvent.update(searchInput, 'test query');

    const searchButton = screen.getByText('🔍');
    await fireEvent.click(searchButton);

    await waitFor(() => {
      expect(getPlaylistVideos).toHaveBeenCalledWith('PLxxxxxx', undefined, 'test query');
    });
  });

  it('should show loading state initially', () => {
    renderModal();

    expect(screen.getByText(/Loading/i)).toBeInTheDocument();
  });

  it('should show no results message when items list is empty', async () => {
    vi.mocked(getPlaylistVideos).mockResolvedValue({
      items: [],
      nextPageToken: null
    });

    renderModal();

    await waitFor(() => {
      expect(screen.getByText(/No results found/i)).toBeInTheDocument();
    });
  });

  it('should show error state on API failure', async () => {
    vi.mocked(getPlaylistDetails).mockRejectedValue(new Error('API Error'));

    renderModal();

    await waitFor(() => {
      expect(screen.getByText(/API Error/i)).toBeInTheDocument();
    });
  });

  it('should show retry button on error', async () => {
    vi.mocked(getPlaylistDetails).mockRejectedValue(new Error('API Error'));

    renderModal();

    await waitFor(() => {
      expect(screen.getByText(/Retry/i)).toBeInTheDocument();
    });
  });

  it('should disable action buttons when loading', async () => {
    // Create a deferred promise to control when the API call resolves
    let resolveExclusion: (value: void) => void;
    const exclusionPromise = new Promise<void>((resolve) => {
      resolveExclusion = resolve;
    });
    vi.mocked(addPlaylistExclusion).mockReturnValue(exclusionPromise);

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
    await rerender({ open: false, playlistId: 'playlist-123', playlistYoutubeId: 'PLxxxxxx' });

    // Reopen modal
    await rerender({ open: true, playlistId: 'playlist-123', playlistYoutubeId: 'PLxxxxxx' });

    // Should reload data
    await waitFor(() => {
      expect(getPlaylistDetails).toHaveBeenCalledTimes(2);
    });
  });

  it('should handle pagination with nextPageToken', async () => {
    renderModal();

    await waitFor(() => {
      expect(screen.getByText('Video 1')).toBeInTheDocument();
    });

    // Verify initial call
    expect(getPlaylistVideos).toHaveBeenCalledWith('PLxxxxxx', undefined, undefined);

    // Note: Actual pagination scrolling would require more complex testing with scroll simulation
    // This test verifies that the nextPageToken is captured from the API response
    const result = await vi.mocked(getPlaylistVideos).mock.results[0].value;
    expect(result).toHaveProperty('nextPageToken', 'token123');
  });

  // Regression (prod): scrolling to the bottom showed "Loading more…" forever.
  function mockTwoPages() {
    vi.mocked(getPlaylistVideos)
      .mockReset()
      .mockResolvedValueOnce({
        items: [{ id: 'item1', videoId: 'video1', title: 'Video 1', position: 1 }],
        nextPageToken: 'token123'
      } as any)
      .mockResolvedValueOnce({
        items: [{ id: 'item4', videoId: 'video4', title: 'Video 4', position: 2 }],
        nextPageToken: null
      } as any);
  }

  async function scrollToBottomLoadsPage2() {
    await waitFor(() => expect(screen.getByText('Video 1')).toBeInTheDocument());

    // jsdom reports 0 for scroll metrics, so the container is "at the bottom".
    const container = document.body.querySelector('.content-list') as HTMLElement;
    container.dispatchEvent(new Event('scroll'));

    await waitFor(() => {
      expect(getPlaylistVideos).toHaveBeenLastCalledWith('PLxxxxxx', 'token123', undefined);
      expect(screen.getByText('Video 4')).toBeInTheDocument();
    });
    expect(document.body.querySelector('.loading-more')).toBeNull();
  }

  it('loads the next page when scrolled to the bottom', async () => {
    mockTwoPages();
    renderModal();

    await scrollToBottomLoadsPage2();
  });

  it('loads the next page on scroll when mounted closed and opened later', async () => {
    mockTwoPages();
    const { rerender } = renderModal({ open: false });
    await rerender({ open: true, playlistId: 'playlist-123', playlistYoutubeId: 'PLxxxxxx' });

    await scrollToBottomLoadsPage2();
  });

  // jsdom lays nothing out; give the list a height so an under-filled page is detectable.
  function layOutList() {
    const spy = vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get').mockReturnValue(500);
    onTestFinished(() => spy.mockRestore());
  }

  async function search(query: string) {
    await fireEvent.update(screen.getByPlaceholderText(/search within playlist/i), query);
    await fireEvent.click(screen.getByText('🔍'));
  }

  // Search filters each page client-side, so a page can hold 0 matches but have a next page.
  it('keeps loading when a search page has no matches but more pages exist', async () => {
    layOutList();
    vi.mocked(getPlaylistVideos)
      .mockReset()
      .mockResolvedValueOnce({ items: [{ id: 'i1', videoId: 'v1', title: 'Video 1' }], nextPageToken: null } as any)
      .mockResolvedValueOnce({ items: [], nextPageToken: 'p2' } as any)
      .mockResolvedValueOnce({ items: [{ id: 'i9', videoId: 'v9', title: 'Needle' }], nextPageToken: null } as any);
    renderModal();
    await waitFor(() => expect(screen.getByText('Video 1')).toBeInTheDocument());

    await search('needle');

    await waitFor(() => expect(screen.getByText('Needle')).toBeInTheDocument());
    expect(getPlaylistVideos).toHaveBeenLastCalledWith('PLxxxxxx', 'p2', 'needle');
  });

  it('pauses auto-loading after 5 empty pages in a row, then continues on demand', async () => {
    vi.useFakeTimers();
    onTestFinished(() => vi.useRealTimers());
    layOutList();
    vi.mocked(getPlaylistVideos)
      .mockReset()
      .mockResolvedValueOnce({ items: [{ id: 'i1', videoId: 'v1', title: 'Video 1' }], nextPageToken: null } as any)
      .mockResolvedValue({ items: [], nextPageToken: 'more' } as any);
    renderModal();
    await vi.advanceTimersByTimeAsync(0);
    expect(screen.getByText('Video 1')).toBeInTheDocument();

    await search('absent');
    await vi.advanceTimersByTimeAsync(0);
    // Still scanning: a spinner, not a premature "No results".
    expect(screen.getByText(/Loading more/i)).toBeInTheDocument();
    expect(screen.queryByText(/No results found/i)).toBeNull();

    await vi.advanceTimersByTimeAsync(10_000);
    // init page + 5 empty search pages, then paused (not exhausted).
    expect(getPlaylistVideos).toHaveBeenCalledTimes(6);
    expect(screen.getByText(/No results found/i)).toBeInTheDocument();

    await fireEvent.click(screen.getByText('Load more'));
    await vi.advanceTimersByTimeAsync(10_000);
    expect(getPlaylistVideos).toHaveBeenCalledTimes(11); // 5 more, paused again
  });

  const flush = () => new Promise((r) => setTimeout(r, 20));
  const propsFor = (id: string) => ({ playlistId: `reg-${id}`, playlistYoutubeId: `PL${id}` });

  // Close A, open B: A's late init must not overwrite B.
  it('drops a late initial load from the previously opened playlist', async () => {
    let releaseA!: () => void;
    vi.mocked(getPlaylistDetails).mockImplementation((id: string) =>
      id === 'PLA'
        ? new Promise((resolve) => { releaseA = () => resolve({ title: 'Playlist A' } as any); })
        : Promise.resolve({ title: 'Playlist B' } as any)
    );
    vi.mocked(fetchPlaylistExclusions).mockImplementation(async (id: string) => (id === 'reg-A' ? ['video3'] : []));
    const { rerender } = renderModal(propsFor('A'));
    await rerender({ open: false, ...propsFor('A') });
    await rerender({ open: true, ...propsFor('B') });
    await waitFor(() => expect(screen.getByText('Video 3')).toBeInTheDocument());

    releaseA();
    await flush();

    expect(screen.getByText('Playlist B')).toBeInTheDocument();
    expect(screen.getByText('Video 3').closest('.item')).not.toHaveClass('excluded');
  });

  it('drops a late exclusions fetch from the previously opened playlist', async () => {
    let releaseA!: () => void;
    vi.mocked(fetchPlaylistExclusions).mockImplementation((id: string) =>
      id === 'reg-A'
        ? new Promise((resolve) => { releaseA = () => resolve(['video3']); })
        : Promise.resolve([])
    );
    const { rerender } = renderModal(propsFor('A'));
    await waitFor(() => expect(fetchPlaylistExclusions).toHaveBeenCalledWith('reg-A'));
    await rerender({ open: false, ...propsFor('A') });
    await rerender({ open: true, ...propsFor('B') });
    await waitFor(() => expect(screen.getByText('Video 3')).toBeInTheDocument());

    releaseA();
    await flush();

    expect(screen.getByText('Video 3').closest('.item')).not.toHaveClass('excluded');
  });

  it('drops a late initial-load failure from the previously opened playlist', async () => {
    let failA!: () => void;
    vi.mocked(getPlaylistDetails).mockImplementation((id: string) =>
      id === 'PLA'
        ? new Promise((_, reject) => { failA = () => reject(new Error('A down')); })
        : Promise.resolve({ title: 'Playlist B' } as any)
    );
    const { rerender } = renderModal(propsFor('A'));
    await rerender({ open: false, ...propsFor('A') });
    await rerender({ open: true, ...propsFor('B') });
    await waitFor(() => expect(screen.getByText('Video 3')).toBeInTheDocument());

    failA();
    await flush();

    expect(screen.queryByText('A down')).toBeNull();
    expect(screen.getByText('Video 3')).toBeInTheDocument();
  });

  it('stops initializing once unmounted', async () => {
    let releaseDetails!: () => void;
    vi.mocked(getPlaylistDetails).mockReturnValue(new Promise((resolve) => { releaseDetails = () => resolve({ title: 'P' } as any); }));
    const { unmount } = renderModal();
    unmount();

    releaseDetails();
    await flush();

    expect(fetchPlaylistExclusions).not.toHaveBeenCalled();
  });

  // Init failed while a search page was in flight: auto-fill must not clear the error and show
  // rows without their exclusion state.
  it('keeps an initialization error when an in-flight search page lands', async () => {
    layOutList();
    let failExclusions!: () => void;
    vi.mocked(fetchPlaylistExclusions).mockReturnValue(new Promise((_, reject) => { failExclusions = () => reject(new Error('Exclusions down')); }));
    let finishSearch!: () => void;
    vi.mocked(getPlaylistVideos).mockReset().mockImplementation(
      (_id: string, token?: string) => token
        ? Promise.resolve({ items: [{ id: 'i2', videoId: 'video2', title: 'Video 2' }], nextPageToken: null } as any)
        : new Promise((resolve) => { finishSearch = () => resolve({ items: [{ id: 'i1', videoId: 'video1', title: 'Video 1' }], nextPageToken: 'p2' } as any); })
    );
    renderModal();
    await waitFor(() => expect(fetchPlaylistExclusions).toHaveBeenCalled());
    await search('video');

    failExclusions();
    await waitFor(() => expect(screen.getByText('Exclusions down')).toBeInTheDocument());
    finishSearch();
    await new Promise((r) => setTimeout(r, 100));

    expect(screen.getByText('Exclusions down')).toBeInTheDocument();
    expect(getPlaylistVideos).toHaveBeenCalledTimes(1);
  });

  it('keeps a late exclusion write when the same playlist is reopened', async () => {
    let finishAdd!: () => void;
    vi.mocked(addPlaylistExclusion).mockReturnValueOnce(new Promise((resolve) => { finishAdd = () => resolve([] as any); }));
    vi.mocked(fetchPlaylistExclusions).mockResolvedValue([]);
    const { rerender } = renderModal(propsFor('A'));
    await waitFor(() => expect(screen.getByText('Video 3')).toBeInTheDocument());
    await fireEvent.click(screen.getByText('Video 3').closest('.item')!.querySelector('button.exclude')!);

    await rerender({ open: false, ...propsFor('A') });
    await rerender({ open: true, ...propsFor('A') });
    await waitFor(() => expect(screen.getByText('Video 3')).toBeInTheDocument());
    finishAdd();
    await flush();

    expect(screen.getByText('Video 3').closest('.item')).toHaveClass('excluded');
  });

  it('drops a late exclusion write from the previously opened playlist', async () => {
    let finishAdd!: () => void;
    vi.mocked(addPlaylistExclusion).mockReturnValueOnce(new Promise((resolve) => { finishAdd = () => resolve([] as any); }));
    vi.mocked(fetchPlaylistExclusions).mockResolvedValue([]);
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
});
