import '@testing-library/jest-dom';
import { render, screen, fireEvent, waitFor } from '@testing-library/vue';
import { createI18n } from 'vue-i18n';
import { beforeEach, describe, expect, it, vi } from 'vitest';
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
  getChannelPlaylists
} from '@/services/youtubeService';

vi.mock('@/services/exclusions', () => ({
  fetchChannelExclusions: vi.fn(),
  addChannelExclusion: vi.fn(),
  removeChannelExclusion: vi.fn()
}));

vi.mock('@/services/youtubeService', () => ({
  getChannelDetails: vi.fn(),
  getChannelVideos: vi.fn(),
  getChannelPlaylists: vi.fn()
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
    await rerender({ open: false, channelId: 'channel-123', channelYoutubeId: 'UCxxxxxx' });

    // Reopen modal
    await rerender({ open: true, channelId: 'channel-123', channelYoutubeId: 'UCxxxxxx' });

    // Should reload data
    await waitFor(() => {
      expect(getChannelDetails).toHaveBeenCalledTimes(2);
    });
  });
});
