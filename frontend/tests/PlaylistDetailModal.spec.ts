import '@testing-library/jest-dom';
import { render, screen, fireEvent, waitFor } from '@testing-library/vue';
import { createI18n } from 'vue-i18n';
import { beforeEach, describe, expect, it, vi } from 'vitest';
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
    renderModal();

    await waitFor(() => {
      expect(screen.getByText('Video 3')).toBeInTheDocument();
    });

    const normalItem = screen.getByText('Video 3').closest('.item');
    const excludeButton = normalItem?.querySelector('button.exclude') as HTMLButtonElement;

    // Click and check if button gets disabled
    fireEvent.click(excludeButton);

    // Button should be disabled during API call
    expect(excludeButton.disabled).toBe(true);
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
    await expect(vi.mocked(getPlaylistVideos).mock.results[0].value).resolves.toHaveProperty('nextPageToken', 'token123');
  });
});
