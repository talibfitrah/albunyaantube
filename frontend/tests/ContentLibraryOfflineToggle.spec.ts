import '@testing-library/jest-dom';
import { render, screen, waitFor, fireEvent } from '@testing-library/vue';
import { createI18n } from 'vue-i18n';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import ContentLibraryView from '@/views/ContentLibraryView.vue';
import { messages } from '@/locales/messages';

const getMock = vi.fn();
const setOfflineMock = vi.fn();

vi.mock('@/services/api/client', () => ({
  default: {
    get: (...args: unknown[]) => getMock(...args),
    put: vi.fn(),
    post: vi.fn()
  }
}));

vi.mock('@/services/contentLibrary', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/services/contentLibrary')>()),
  fetchRegistryTotals: () => Promise.resolve({ channels: 0, playlists: 0, videos: 0 }),
  setVideoOfflineAllowed: (...args: unknown[]) => setOfflineMock(...args)
}));

/**
 * iOS Phase 3 offline gate: the per-video "Save for offline" flag is flipped from the
 * Content Library. The toggle exists only on video rows — channels and playlists are
 * never individually saveable — and sends the partial {offlineAllowed} body.
 */
describe('ContentLibraryView — Save for offline toggle', () => {
  beforeEach(() => {
    getMock.mockReset();
    setOfflineMock.mockReset();
    setOfflineMock.mockResolvedValue(undefined);
  });

  function stubLibrary(items: Record<string, unknown>[]) {
    getMock.mockImplementation((url: string) => {
      if (url === '/api/admin/categories') {
        return Promise.resolve({ data: [] });
      }
      return Promise.resolve({
        data: {
          content: items,
          totalItems: items.length,
          currentPage: 0,
          pageSize: 20,
          totalPages: 1,
          truncated: false
        }
      });
    });
  }

  function renderView() {
    const i18n = createI18n({ legacy: false, locale: 'en', messages });
    return render(ContentLibraryView, { global: { plugins: [i18n] } });
  }

  const videoItem = {
    type: 'video',
    id: 'v1',
    youtubeId: 'xc7keR2piUM',
    title: 'Tafsir Lecture 1',
    status: 'APPROVED',
    categoryIds: [],
    createdAt: '2024-01-01T00:00:00Z',
    keywords: [],
    offlineAllowed: false
  };

  const channelItem = {
    type: 'channel',
    id: 'c1',
    youtubeId: 'UC-test',
    title: 'A Channel',
    status: 'APPROVED',
    categoryIds: [],
    createdAt: '2024-01-01T00:00:00Z',
    keywords: []
  };

  it('toggling a video calls the update service with the flag flipped on', async () => {
    stubLibrary([videoItem]);
    renderView();

    await waitFor(() => expect(screen.getByText('Tafsir Lecture 1')).toBeInTheDocument());

    await fireEvent.click(screen.getByTitle('Save for offline: not allowed'));

    await waitFor(() => expect(setOfflineMock).toHaveBeenCalledWith('v1', true));
  });

  it('toggling an enabled video calls the update service with the flag flipped off', async () => {
    stubLibrary([{ ...videoItem, offlineAllowed: true }]);
    renderView();

    await waitFor(() => expect(screen.getByText('Tafsir Lecture 1')).toBeInTheDocument());

    await fireEvent.click(screen.getByTitle('Save for offline: allowed'));

    await waitFor(() => expect(setOfflineMock).toHaveBeenCalledWith('v1', false));
  });

  it('renders no offline toggle on a channel row', async () => {
    stubLibrary([channelItem]);
    renderView();

    await waitFor(() => expect(screen.getByText('A Channel')).toBeInTheDocument());

    expect(screen.queryByTitle('Save for offline: not allowed')).toBeNull();
    expect(screen.queryByTitle('Save for offline: allowed')).toBeNull();
  });
});
