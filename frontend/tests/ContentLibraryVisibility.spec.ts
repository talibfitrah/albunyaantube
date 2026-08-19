import '@testing-library/jest-dom';
import { render, screen, waitFor } from '@testing-library/vue';
import { createI18n } from 'vue-i18n';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import ContentLibraryView from '@/views/ContentLibraryView.vue';
import { messages } from '@/locales/messages';

const getMock = vi.fn();

vi.mock('@/services/api/client', () => ({
  default: {
    get: (...args: unknown[]) => getMock(...args),
    put: vi.fn(),
    post: vi.fn()
  }
}));

vi.mock('@/services/contentLibrary', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/services/contentLibrary')>()),
  fetchRegistryTotals: () => Promise.resolve({ channels: 0, playlists: 0, videos: 0 })
}));

/**
 * "Approved" covered two different things: in the public catalogue, and granted only to the
 * people who imported it. An admin looking at the library could not tell them apart, so a
 * personal grant read as if the item were live for everyone.
 */
describe('ContentLibraryView — approved for everyone vs for one person', () => {
  beforeEach(() => {
    getMock.mockReset();
  });

  function stubLibrary(item: Record<string, unknown>) {
    getMock.mockImplementation((url: string) => {
      if (url === '/api/admin/categories') {
        return Promise.resolve({ data: [] });
      }
      return Promise.resolve({
        data: {
          content: [item],
          totalItems: 1,
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

  const base = {
    type: 'video',
    id: 'v1',
    youtubeId: 'yt-v1',
    title: 'A Video',
    status: 'APPROVED',
    categoryIds: [],
    createdAt: '2024-01-01T00:00:00Z',
    keywords: []
  };

  it('names the person a personally-approved item was approved for', async () => {
    stubLibrary({ ...base, visibility: 'PERSONAL', grantedTo: ['Ahmed'] });

    renderView();

    await waitFor(() => {
      expect(screen.getAllByText('Approved for Ahmed').length).toBeGreaterThan(0);
    });
  });

  it('lists every grantee when a personal approval covers more than one person', async () => {
    stubLibrary({ ...base, visibility: 'PERSONAL', grantedTo: ['Ahmed', 'Fatima'] });

    renderView();

    await waitFor(() => {
      expect(screen.getAllByText('Approved for Ahmed, Fatima').length).toBeGreaterThan(0);
    });
  });

  it('still marks a personal grant whose grantee list came back empty', async () => {
    // An older document may have no stored grants. The item is still restricted, so reading as
    // plain "Approved" would be exactly the confusion this label exists to remove.
    stubLibrary({ ...base, visibility: 'PERSONAL', grantedTo: [] });

    renderView();

    await waitFor(() => expect(screen.getByText('A Video')).toBeInTheDocument());
    expect(screen.getByTitle('Approved for specific people')).toBeInTheDocument();
  });

  it('shows a plain Approved for an item in the public catalogue', async () => {
    // Grantees left over from a personal→public promotion must not make a public item read as
    // restricted. The view decides on visibility, not on the presence of a grant list.
    stubLibrary({ ...base, visibility: 'PUBLIC', grantedTo: ['Ahmed'] });

    renderView();

    // Wait for the ROW, not for the word "Approved" — the status filter renders an "Approved"
    // option before any data loads, so waiting on that text passes against an empty list and
    // makes the assertion below vacuous.
    await waitFor(() => expect(screen.getByText('A Video')).toBeInTheDocument());

    expect(screen.getByTitle('Approved')).toBeInTheDocument();
    expect(screen.queryByText(/approved for/i)).toBeNull();
  });
});
