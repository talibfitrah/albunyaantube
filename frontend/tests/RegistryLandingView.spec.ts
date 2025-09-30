import '@testing-library/jest-dom';
import { fireEvent, render, screen, within } from '@testing-library/vue';
import { createI18n } from 'vue-i18n';
import { vi, describe, it, expect, beforeEach } from 'vitest';
import RegistryLandingView from '@/views/RegistryLandingView.vue';
import { messages } from '@/locales/messages';
import type { AdminSearchResponse } from '@/types/registry';

const fetchAllCategoriesMock = vi.fn();
const searchRegistryMock = vi.fn();
const updateChannelExclusionsMock = vi.fn();
const updatePlaylistExclusionsMock = vi.fn();

vi.mock('@/services/categories', () => ({
  fetchAllCategories: (...args: unknown[]) => fetchAllCategoriesMock(...args)
}));

vi.mock('@/services/registry', () => ({
  searchRegistry: (...args: unknown[]) => searchRegistryMock(...args),
  updateChannelExclusions: (...args: unknown[]) => updateChannelExclusionsMock(...args),
  updatePlaylistExclusions: (...args: unknown[]) => updatePlaylistExclusionsMock(...args)
}));

const sampleResponse: AdminSearchResponse = {
  query: '',
  channels: [
    {
      id: 'channel-1',
      ytId: 'UC123',
      name: null,
      avatarUrl: null,
      subscriberCount: 0,
      categories: [{ id: 'quran', label: 'Quran' }],
      includeState: 'INCLUDED',
      excludedItemCounts: { videos: 0, playlists: 0 },
      excludedPlaylistIds: [],
      excludedVideoIds: [],
      bulkEligible: true
    }
  ],
  playlists: [
    {
      id: 'playlist-1',
      ytId: 'PL123',
      title: null,
      thumbnailUrl: null,
      itemCount: 0,
      owner: {
        id: 'channel-1',
        ytId: 'UC123',
        name: null,
        avatarUrl: null,
        subscriberCount: 0,
        categories: [{ id: 'quran', label: 'Quran' }]
      },
      categories: [{ id: 'quran', label: 'Quran' }],
      downloadable: true,
      includeState: 'INCLUDED',
      parentChannelId: 'channel-1',
      excludedVideoCount: 0,
      excludedVideoIds: [],
      bulkEligible: true
    }
  ],
  videos: [
    {
      id: 'video-1',
      ytId: 'VID123',
      title: null,
      thumbnailUrl: null,
      durationSeconds: 0,
      publishedAt: new Date('2024-05-01T00:00:00Z').toISOString(),
      viewCount: 0,
      channel: {
        id: 'channel-1',
        ytId: 'UC123',
        name: null,
        avatarUrl: null,
        subscriberCount: 0,
        categories: [{ id: 'quran', label: 'Quran' }]
      },
      categories: [{ id: 'quran', label: 'Quran' }],
      bookmarked: null,
      downloaded: null,
      includeState: 'INCLUDED',
      parentChannelId: 'channel-1',
      parentPlaylistIds: ['PL123']
    }
  ]
};

function buildI18n() {
  return createI18n({
    legacy: false,
    locale: 'en',
    messages
  });
}

async function flushPromises() {
  await Promise.resolve();
  await Promise.resolve();
}

describe('RegistryLandingView', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    fetchAllCategoriesMock.mockResolvedValue([
      { id: 'cat-1', slug: 'quran', label: 'Quran' }
    ]);
    searchRegistryMock.mockResolvedValue(structuredClone(sampleResponse));
    updateChannelExclusionsMock.mockResolvedValue(undefined);
    updatePlaylistExclusionsMock.mockResolvedValue(undefined);
  });

  function renderView() {
    return render(RegistryLandingView, {
      global: {
        plugins: [buildI18n()]
      }
    });
  }

  it('renders aggregated registry results', async () => {
    renderView();
    expect(
      await screen.findByText('UC123', {
        selector: '.card-title'
      })
    ).toBeInTheDocument();

    expect(
      await screen.findByText('PL123', {
        selector: '.card-title'
      })
    ).toBeInTheDocument();

    expect(
      await screen.findByText('VID123', {
        selector: '.card-title'
      })
    ).toBeInTheDocument();
  });

  it('updates channel exclusions when toggling a playlist', async () => {
    renderView();
    const playlistCardTitle = await screen.findByText('PL123');
    const playlistCard = playlistCardTitle.closest('article');
    expect(playlistCard).not.toBeNull();
    const button = within(playlistCard as HTMLElement).getByRole('button', { name: /Exclude/i });
    await fireEvent.click(button);
    await flushPromises();
    expect(updateChannelExclusionsMock).toHaveBeenCalledWith('channel-1', {
      excludedPlaylistIds: ['PL123'],
      excludedVideoIds: []
    });
  });

  it('updates channel and playlist exclusions when toggling a video', async () => {
    renderView();
    await screen.findByText('VID123');
    updateChannelExclusionsMock.mockClear();
    updatePlaylistExclusionsMock.mockClear();

    const videoCardTitle = screen.getByText('VID123');
    const videoCard = videoCardTitle.closest('article');
    expect(videoCard).not.toBeNull();
    const button = within(videoCard as HTMLElement).getByRole('button', { name: /Exclude/i });
    await fireEvent.click(button);
    await flushPromises();

    expect(updateChannelExclusionsMock).toHaveBeenCalledWith('channel-1', {
      excludedPlaylistIds: [],
      excludedVideoIds: ['VID123']
    });
    expect(updatePlaylistExclusionsMock).toHaveBeenCalledWith('playlist-1', {
      excludedVideoIds: ['VID123']
    });
  });
});
