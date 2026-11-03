import '@testing-library/jest-dom';
import { render, screen, fireEvent, waitFor } from '@testing-library/vue';
import { createI18n } from 'vue-i18n';
import { createPinia } from 'pinia';
import { defineComponent, h } from 'vue';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import ContentSearchView from '@/views/ContentSearchView.vue';
import { messages } from '@/locales/messages';
import { ApiError } from '@/services/http';
import { searchYouTube } from '@/services/youtubeService';

vi.mock('@/services/youtubeService', () => ({
  searchYouTube: vi.fn(),
  addToPendingApprovals: vi.fn(),
  parseYouTubeUrl: () => null
}));
vi.mock('@/services/api/client');

const VideoCard = defineComponent({ props: ['video'], setup: (p) => () => h('div', p.video.title) });
const empty = defineComponent({ setup: () => () => null });

const page = (title: string, nextPageToken?: string) => ({
  channels: [],
  playlists: [],
  videos: [{ id: title, ytId: title, title, publishedAt: '2026-01-01T00:00:00Z' }],
  nextPageToken
});

function mount(locale = 'en') {
  return render(ContentSearchView, {
    global: {
      plugins: [createPinia(), createI18n({ legacy: false, locale, messages })],
      stubs: {
        VideoCard,
        ChannelCard: empty,
        PlaylistCard: empty,
        BulkSubmissionTab: empty,
        CategoryAssignmentModal: empty,
        ChannelDetailModal: empty,
        PlaylistDetailModal: empty,
        VideoPreviewModal: empty
      }
    }
  });
}

async function search(query: string) {
  const input = screen.getByPlaceholderText(messages.en.contentSearch.searchPlaceholder);
  await fireEvent.update(input, query);
  await fireEvent.keyUp(input, { key: 'Enter' });
}

describe('ContentSearchView load more', () => {
  beforeEach(() => vi.mocked(searchYouTube).mockReset());

  it('shows a failed page with Retry and keeps the loaded rows', async () => {
    vi.mocked(searchYouTube)
      .mockResolvedValueOnce(page('Video A', 't2') as any)
      .mockRejectedValueOnce(new ApiError('YouTube API request timed out', 0, 'REQUEST_TIMEOUT'))
      .mockResolvedValueOnce(page('Video B') as any);
    mount();
    await search('lecture');
    await fireEvent.click(await screen.findByText(messages.en.contentSearch.loadMore));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(messages.en.apiErrors.REQUEST_TIMEOUT);
    expect(screen.getByText('Video A')).toBeInTheDocument();

    await fireEvent.click(screen.getByText(messages.en.contentSearch.retry));

    await waitFor(() => expect(screen.getByText('Video B')).toBeInTheDocument());
    expect(screen.getByText('Video A')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).toBeNull();
    expect(searchYouTube).toHaveBeenLastCalledWith('lecture', 'all', 't2');
  });

  it('drops a page from a search that was replaced', async () => {
    let failPage!: () => void;
    vi.mocked(searchYouTube)
      .mockResolvedValueOnce(page('Video A', 't2') as any)
      .mockReturnValueOnce(new Promise((_, reject) => { failPage = () => reject(new Error('old page failed')); }))
      .mockResolvedValueOnce(page('Video C', 'c2') as any);
    mount();
    await search('lecture');
    await fireEvent.click(await screen.findByText(messages.en.contentSearch.loadMore));
    await search('tafsir');
    await screen.findByText('Video C');
    // The replaced search's page no longer holds the new search's Load more.
    expect(screen.getByText(messages.en.contentSearch.loadMore).closest('button')).not.toBeDisabled();

    failPage();
    await new Promise((r) => setTimeout(r, 20));

    expect(screen.queryByRole('alert')).toBeNull();
    expect(screen.getByText(messages.en.contentSearch.loadMore)).toBeInTheDocument();
  });

  it('keeps the latest search when an earlier one answers late', async () => {
    let finishA!: () => void;
    vi.mocked(searchYouTube)
      .mockReturnValueOnce(new Promise((resolve) => { finishA = () => resolve(page('Video A', 'a2') as any); }))
      .mockResolvedValueOnce(page('Video B', 'b2') as any)
      .mockResolvedValueOnce(page('Video B2') as any);
    mount();
    await search('lecture');
    await search('tafsir');
    await screen.findByText('Video B');

    finishA();
    await new Promise((r) => setTimeout(r, 20));

    expect(screen.queryByText('Video A')).toBeNull();
    expect(screen.getByText('Video B')).toBeInTheDocument();
    // Load more pages the search that is shown, even after the input text changes.
    await fireEvent.update(screen.getByPlaceholderText(messages.en.contentSearch.searchPlaceholder), 'edited');
    await fireEvent.click(screen.getByText(messages.en.contentSearch.loadMore));
    expect(searchYouTube).toHaveBeenLastCalledWith('tafsir', 'all', 'b2');
  });
});
