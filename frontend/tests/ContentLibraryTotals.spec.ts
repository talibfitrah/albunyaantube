import '@testing-library/jest-dom';
import { render, screen, waitFor } from '@testing-library/vue';
import { createI18n } from 'vue-i18n';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import ContentLibraryView from '@/views/ContentLibraryView.vue';
import { messages } from '@/locales/messages';

const getMock = vi.fn();
const fetchRegistryTotalsMock = vi.fn();

vi.mock('@/services/api/client', () => ({
  default: {
    get: (...args: unknown[]) => getMock(...args),
    put: vi.fn(),
    post: vi.fn()
  }
}));

vi.mock('@/services/contentLibrary', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/services/contentLibrary')>()),
  fetchRegistryTotals: () => fetchRegistryTotalsMock()
}));

/**
 * The registry totals must reflect the whole library, not the page. The Content Library fetch is
 * bounded and filtered, so a totals row derived from the returned rows would under-report — which
 * is what made the screen claim the 200-item ceiling was the full library.
 */
describe('ContentLibraryView registry totals', () => {
  beforeEach(() => {
    getMock.mockReset();
    fetchRegistryTotalsMock.mockReset();
    fetchRegistryTotalsMock.mockResolvedValue({ channels: 4321, playlists: 87, videos: 65 });
    getMock.mockImplementation((url: string) => {
      if (url === '/api/admin/categories') {
        return Promise.resolve({ data: [] });
      }
      return Promise.resolve({
        data: { content: [], totalItems: 0, currentPage: 0, pageSize: 20, totalPages: 0, truncated: false }
      });
    });
  });

  function renderView(locale = 'en') {
    const i18n = createI18n({ legacy: false, locale, messages });
    return render(ContentLibraryView, { global: { plugins: [i18n] } });
  }

  it('renders the per-type totals the server reported', async () => {
    renderView();

    await waitFor(() => {
      expect(screen.getByText('4,321')).toBeInTheDocument();
    });
    expect(screen.getByText('87')).toBeInTheDocument();
    expect(screen.getByText('65')).toBeInTheDocument();
    // Labelled, and scoped — an admin filtering to PENDING must be able to tell why the
    // headline number exceeds the list beneath it.
    expect(screen.getByText('Channels')).toBeInTheDocument();
    expect(screen.getByText('Playlists')).toBeInTheDocument();
    expect(screen.getByText('Videos')).toBeInTheDocument();
    expect(screen.getByText('In the registry, all statuses')).toBeInTheDocument();
  });

  it('groups digits by the app locale, not the browser locale', async () => {
    renderView('nl');

    // Dutch groups with a period. Reading the browser's locale instead would render "4,321"
    // here and disagree with the translated labels beside it.
    await waitFor(() => {
      expect(screen.getByText('4.321')).toBeInTheDocument();
    });
  });

  it('keeps the listing usable when the totals request fails', async () => {
    fetchRegistryTotalsMock.mockRejectedValue(new Error('firestore slow'));

    const { container } = renderView();

    await waitFor(() => {
      expect(getMock).toHaveBeenCalled();
    });
    // The header row hides; nothing else breaks and no error surfaces over working content.
    expect(container.querySelector('.registry-totals')).toBeNull();
    expect(container.querySelector('.content-library')).not.toBeNull();
  });
});
