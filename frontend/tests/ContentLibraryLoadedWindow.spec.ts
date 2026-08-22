/**
 * Every mutation on the Content Library called loadContent(), which restarts at page 0 and
 * replaces the array — so deleting one row threw away every page the admin had scrolled to load.
 * The list collapsed to a single page and the browser dropped them back at the top, losing their
 * place in a list they were working through item by item.
 */
import '@testing-library/jest-dom';
import { render, screen, waitFor, fireEvent, within } from '@testing-library/vue';
import { createI18n } from 'vue-i18n';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import ContentLibraryView from '@/views/ContentLibraryView.vue';
import { messages } from '@/locales/messages';

const getMock = vi.fn();
const bulkDeleteMock = vi.fn();
const bulkApproveMock = vi.fn();
const bulkMarkPendingMock = vi.fn();
const bulkAssignCategoriesMock = vi.fn();
/** What the stub modal emits. Two rows with different existing categories split into two groups. */
const assignPayload = vi.hoisted(() => ({ categoryIds: ['cat-b'], unchangedIds: [] as string[] }));

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
  bulkDelete: (...args: unknown[]) => bulkDeleteMock(...args),
  bulkApprove: (...args: unknown[]) => bulkApproveMock(...args),
  bulkMarkPending: (...args: unknown[]) => bulkMarkPendingMock(...args),
  bulkAssignCategories: (...args: unknown[]) => bulkAssignCategoriesMock(...args)
}));

// Both modals are stood in for by a single button that fires the event the view listens to.
// What is under test is what the view does with that event, not the modals themselves.
vi.mock('@/components/CategoryAssignmentModal.vue', async () => {
  const { h } = await import('vue');
  return {
    default: {
      name: 'CategoryAssignmentModal',
      emits: ['assign', 'close'],
      setup(_props: unknown, { emit }: { emit: (event: string, ...args: unknown[]) => void }) {
        return () => h(
          'button',
          {
            'data-testid': 'assign-categories',
            onClick: () => emit('assign', assignPayload.categoryIds, assignPayload.unchangedIds)
          },
          'assign'
        );
      }
    }
  };
});

vi.mock('@/components/exclusions/ChannelDetailModal.vue', async () => {
  const { h } = await import('vue');
  return {
    default: {
      name: 'ChannelDetailModal',
      emits: ['updated', 'close'],
      setup(_props: unknown, { emit }: { emit: (event: string, ...args: unknown[]) => void }) {
        return () => h(
          'button',
          { 'data-testid': 'exclusion-changed', onClick: () => emit('updated') },
          'exclude'
        );
      }
    }
  };
});

interface ServerItem {
  type: string;
  id: string;
  title: string;
  status: string;
  categoryIds: string[];
  [key: string]: unknown;
}

/** Stand-in registry. Distinct descending dates so date-desc order matches this array. */
function makeItems(count: number, overrides: (n: number) => Record<string, unknown> = () => ({})): ServerItem[] {
  return Array.from({ length: count }, (_, i) => ({
    type: 'video',
    id: `v${i + 1}`,
    youtubeId: `yt${i + 1}`,
    title: `Video ${i + 1}`,
    status: 'PENDING',
    categoryIds: ['cat-a'],
    createdAt: new Date(Date.UTC(2026, 0, 1) - i * 60_000).toISOString(),
    visibility: 'PUBLIC',
    grantedTo: [],
    ...overrides(i + 1)
  }));
}

let library: ServerItem[];
/** Hold the next listing response open, so a mutation can land while it is in flight. */
let deferListing = false;
/** Whether the held response is read when released rather than when requested. */
let deferReadsLate = false;
let releaseListing: (() => void) | null = null;
/** Hold a delete open, so a listing request can be issued and read while it is still pending. */
let deferDelete = false;
let releaseDelete: (() => void) | null = null;

function findItem(type: string, id: string): ServerItem | undefined {
  return library.find(item => item.type === type && item.id === id);
}

describe('ContentLibraryView keeps the loaded window across mutations', () => {
  beforeEach(() => {
    library = makeItems(50);
    assignPayload.categoryIds = ['cat-b'];
    assignPayload.unchangedIds = [];
    deferListing = false;
    deferReadsLate = false;
    releaseListing = null;
    deferDelete = false;
    releaseDelete = null;
    vi.stubGlobal('confirm', () => true);
    vi.stubGlobal('alert', () => undefined);

    getMock.mockReset();
    getMock.mockImplementation((url: string, config?: { params?: Record<string, unknown> }) => {
      if (url === '/api/admin/categories') {
        return Promise.resolve({
          data: [
            { id: 'cat-a', name: 'Aqeedah' }, { id: 'cat-b', name: 'Fiqh' },
            { id: 'cat-c', name: 'Hadith' }, { id: 'cat-d', name: 'Tafsir' }
          ]
        });
      }
      const params = config?.params ?? {};
      const status = String(params.status ?? 'all');
      const category = params.category ? String(params.category) : null;
      const page = Number(params.page ?? 0);
      const size = Number(params.size ?? 25);
      const read = () => {
        const rows = library.filter(item =>
          (status === 'all' || item.status.toLowerCase() === status)
          && (!category || item.categoryIds.includes(category)));
        return { data: { content: rows.slice(page * size, page * size + size), totalItems: rows.length, truncated: false } };
      };
      if (deferListing) {
        deferListing = false;
        // Read now or on release: a held request can be served either side of a concurrent write.
        const readsLate = deferReadsLate;
        const early = readsLate ? null : read();
        return new Promise(resolve => { releaseListing = () => resolve(early ?? read()); });
      }
      return Promise.resolve(read());
    });

    bulkDeleteMock.mockReset();
    bulkDeleteMock.mockImplementation((items: { type: string; id: string }[]) => {
      const commit = () => {
        library = library.filter(item => !items.some(i => i.type === item.type && i.id === item.id));
        return { successCount: items.length, errors: [] };
      };
      if (deferDelete) {
        deferDelete = false;
        return new Promise(resolve => { releaseDelete = () => resolve(commit()); });
      }
      return Promise.resolve(commit());
    });

    bulkApproveMock.mockReset();
    bulkApproveMock.mockImplementation((items: { type: string; id: string }[]) => {
      items.forEach(i => {
        const found = findItem(i.type, i.id);
        if (!found) return;
        found.status = 'APPROVED';
        // executeBulkStatusUpdate writes visibility=PUBLIC alongside status when approving, and
        // withVisibility() then reports no grantees for a public row.
        found.visibility = 'PUBLIC';
        found.grantedTo = [];
      });
      return Promise.resolve({ successCount: items.length, errors: [] });
    });

    bulkMarkPendingMock.mockReset();
    bulkMarkPendingMock.mockImplementation((items: { type: string; id: string }[]) => {
      // executeBulkStatusUpdate leaves visibility alone for anything but an approval.
      items.forEach(i => { const found = findItem(i.type, i.id); if (found) found.status = 'PENDING'; });
      return Promise.resolve({ successCount: items.length, errors: [] });
    });

    bulkAssignCategoriesMock.mockReset();
    bulkAssignCategoriesMock.mockImplementation((items: { type: string; id: string }[], categoryIds: string[]) => {
      items.forEach(i => { const found = findItem(i.type, i.id); if (found) found.categoryIds = categoryIds; });
      return Promise.resolve({ successCount: items.length, errors: [] });
    });
  });

  function renderView() {
    const i18n = createI18n({ legacy: false, locale: 'en', messages });
    return render(ContentLibraryView, { global: { plugins: [i18n] } });
  }

  function rowFor(title: string): HTMLElement {
    const row = screen.getByText(title).closest('tr');
    if (!row) throw new Error(`No row rendered for "${title}"`);
    return row as HTMLElement;
  }

  /** The table's header checkbox, which ticks every loaded row. */
  function selectAllRows(): HTMLElement {
    const box = document.querySelector('.content-table thead input[type="checkbox"]');
    if (!box) throw new Error('No select-all checkbox rendered');
    return box as HTMLElement;
  }

  function rowCount(): number {
    return document.querySelectorAll('.content-table tbody tr').length;
  }

  /** Let a released response run through its handler before asserting on the result. */
  async function settle() {
    await new Promise(resolve => setTimeout(resolve));
    await new Promise(resolve => setTimeout(resolve));
  }

  function listingCalls(): number {
    return getMock.mock.calls.filter(call => call[0] === '/api/admin/content').length;
  }

  /** Render and scroll one page deep, so a reset to page 0 is visible as a lost second page. */
  async function loadTwoPages() {
    renderView();
    await waitFor(() => expect(screen.getByText('Video 1')).toBeInTheDocument());
    await fireEvent.click(screen.getByRole('button', { name: /Load More/i }));
    await waitFor(() => expect(screen.getByText('Video 50')).toBeInTheDocument());
  }

  it('keeps the second page when a row is deleted', async () => {
    await loadTwoPages();

    await fireEvent.click(within(rowFor('Video 30')).getByTitle('Delete'));

    await waitFor(() => expect(screen.queryByText('Video 30')).not.toBeInTheDocument());
    expect(screen.getByText('Video 50')).toBeInTheDocument();
  });

  it('does not skip the next row after a delete when loading more', async () => {
    library = makeItems(75);
    await loadTwoPages();
    await fireEvent.click(within(rowFor('Video 10')).getByTitle('Delete'));
    await waitFor(() => expect(screen.queryByText('Video 10')).not.toBeInTheDocument());

    await fireEvent.click(screen.getByRole('button', { name: /Load More/i }));

    // Deleting a row shifts every later row up by one in the server's offset paging, so asking
    // for the next counted page steps straight over Video 51.
    await waitFor(() => expect(screen.getByText('Video 51')).toBeInTheDocument());
    // Re-reading the overlap is how that row is recovered, so the rows inside it must not double.
    expect(screen.getAllByText('Video 30')).toHaveLength(1);
  });

  it('keeps the second page when rows are approved', async () => {
    await loadTwoPages();
    await fireEvent.click(within(rowFor('Video 30')).getByRole('checkbox'));
    await fireEvent.click(screen.getByRole('button', { name: 'Bulk Actions' }));

    await fireEvent.click(screen.getByRole('button', { name: 'Approve Selected' }));

    await waitFor(() => expect(within(rowFor('Video 30')).getByText('Approved')).toBeInTheDocument());
    expect(screen.getByText('Video 50')).toBeInTheDocument();
  });

  it('drops an approved row from a list filtered to pending', async () => {
    renderView();
    await waitFor(() => expect(screen.getByText('Video 1')).toBeInTheDocument());
    await fireEvent.click(screen.getByLabelText('Pending'));
    await waitFor(() => expect(screen.getByRole('button', { name: /Load More/i })).toBeInTheDocument());
    await fireEvent.click(screen.getByRole('button', { name: /Load More/i }));
    await waitFor(() => expect(screen.getByText('Video 50')).toBeInTheDocument());

    await fireEvent.click(within(rowFor('Video 30')).getByRole('checkbox'));
    await fireEvent.click(screen.getByRole('button', { name: 'Bulk Actions' }));
    await fireEvent.click(screen.getByRole('button', { name: 'Approve Selected' }));

    // Approved is no longer Pending: the row leaves the filtered list, the rest of it stays.
    await waitFor(() => expect(screen.queryByText('Video 30')).not.toBeInTheDocument());
    expect(screen.getByText('Video 50')).toBeInTheDocument();
  });

  it('keeps the second page when categories are assigned', async () => {
    await loadTwoPages();

    await fireEvent.click(within(rowFor('Video 30')).getByTitle('Categories'));
    await fireEvent.click(screen.getByTestId('assign-categories'));

    await waitFor(() => expect(within(rowFor('Video 30')).getByText('Fiqh')).toBeInTheDocument());
    expect(screen.getByText('Video 50')).toBeInTheDocument();
  });

  it('stops calling an approved row restricted once approving has published it', async () => {
    library = makeItems(50, n => (n === 30
      ? { status: 'APPROVED', visibility: 'PERSONAL', grantedTo: ['Ahmad'] }
      : {}));
    await loadTwoPages();
    expect(within(rowFor('Video 30')).getByText('Approved for Ahmad')).toBeInTheDocument();

    await fireEvent.click(within(rowFor('Video 30')).getByRole('checkbox'));
    await fireEvent.click(screen.getByRole('button', { name: 'Bulk Actions' }));
    await fireEvent.click(screen.getByRole('button', { name: 'Approve Selected' }));

    // Approving publishes it. A row still labelled with the grantees' names says the opposite of
    // what the registry now holds, and it is the admin's only signal that the grant went public.
    await waitFor(() => expect(within(rowFor('Video 30')).getByText('Approved')).toBeInTheDocument());
    expect(screen.queryByText('Approved for Ahmad')).not.toBeInTheDocument();
  });

  it('does not bring back a row deleted while a page request was in flight', async () => {
    library = makeItems(75);
    renderView();
    await waitFor(() => expect(screen.getByText('Video 1')).toBeInTheDocument());
    // Leaves 24 rows, so the next page overlaps what is already held.
    await fireEvent.click(within(rowFor('Video 5')).getByTitle('Delete'));
    await waitFor(() => expect(screen.queryByText('Video 5')).not.toBeInTheDocument());

    deferListing = true;
    await fireEvent.click(screen.getByRole('button', { name: /Load More/i }));
    await fireEvent.click(within(rowFor('Video 10')).getByTitle('Delete'));
    await waitFor(() => expect(screen.queryByText('Video 10')).not.toBeInTheDocument());

    releaseListing!();
    await settle();

    // That page was read before the delete: it still carries Video 10, and its offsets no longer
    // point where they did, so the row on its boundary would fall between what is held and what
    // it carries. Discarded whole — along with its total, which predates the delete too.
    expect(screen.queryByText('Video 10')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Load More/i }).textContent).toContain('73');

    // Discarding must not deadlock: the next request is computed from where the list now stands
    // and picks up the row that fell between.
    await fireEvent.click(screen.getByRole('button', { name: /Load More/i }));
    await waitFor(() => expect(screen.getByText('Video 26')).toBeInTheDocument());
  });

  it('refills the list when a bulk action empties every loaded row', async () => {
    library = makeItems(100);
    renderView();
    await waitFor(() => expect(screen.getByText('Video 1')).toBeInTheDocument());
    await fireEvent.click(screen.getByLabelText('Pending'));
    await waitFor(() => expect(screen.getByRole('button', { name: /Load More/i })).toBeInTheDocument());

    await fireEvent.click(selectAllRows());
    await fireEvent.click(screen.getByRole('button', { name: 'Bulk Actions' }));
    await fireEvent.click(screen.getByRole('button', { name: 'Approve Selected' }));

    // Approving every loaded row empties the window. There is no place left to hold, and the
    // Load More button lives inside the list's non-empty branch — so the 75 pending rows still
    // out there would sit behind an empty state with no way to reach them.
    await waitFor(() => expect(screen.getByText('Video 26')).toBeInTheDocument());
    expect(screen.queryByText(messages.en.contentLibrary.empty)).not.toBeInTheDocument();
  });

  it('tops up a client-side sort mode after a row is removed from its page', async () => {
    library = makeItems(150);
    renderView();
    await waitFor(() => expect(screen.getByText('Video 1')).toBeInTheDocument());
    await fireEvent.update(document.querySelector('.sort-select') as HTMLSelectElement, 'name-asc');
    await fireEvent.click(screen.getByLabelText('Pending'));
    await waitFor(() => expect(screen.getByText('Video 100')).toBeInTheDocument());

    await fireEvent.click(within(rowFor('Video 100')).getByTitle('Delete'));

    // This mode holds one fixed page of a longer list and has no Load More, so a row removed from
    // that page shrinks the working set with nothing to top it up.
    await waitFor(() => expect(screen.getByText('Video 101')).toBeInTheDocument());
  });

  it('keeps the second page when a subset is bulk deleted', async () => {
    await loadTwoPages();

    await fireEvent.click(within(rowFor('Video 30')).getByRole('checkbox'));
    await fireEvent.click(screen.getByRole('button', { name: 'Bulk Actions' }));
    await fireEvent.click(screen.getByRole('button', { name: 'Delete Selected' }));

    await waitFor(() => expect(screen.queryByText('Video 30')).not.toBeInTheDocument());
    expect(screen.getByText('Video 50')).toBeInTheDocument();
  });

  it('asks the server for the list when a bulk delete only partly committed', async () => {
    await loadTwoPages();
    bulkDeleteMock.mockResolvedValueOnce({ successCount: 0, errors: ['playlist not found: p1'] });
    const before = listingCalls();

    await fireEvent.click(within(rowFor('Video 30')).getByRole('checkbox'));
    await fireEvent.click(screen.getByRole('button', { name: 'Bulk Actions' }));
    await fireEvent.click(screen.getByRole('button', { name: 'Delete Selected' }));

    // The response says how many committed but not which, so the list cannot be patched from it
    // and has to come from the server.
    await waitFor(() => expect(listingCalls()).toBe(before + 1));
  });

  it('drops a row that no longer carries the category being filtered on', async () => {
    renderView();
    await waitFor(() => expect(screen.getByText('Video 1')).toBeInTheDocument());
    await fireEvent.click(screen.getByLabelText('Aqeedah'));
    await waitFor(() => expect(screen.getByRole('button', { name: /Load More/i })).toBeInTheDocument());
    await fireEvent.click(screen.getByRole('button', { name: /Load More/i }));
    await waitFor(() => expect(screen.getByText('Video 50')).toBeInTheDocument());

    // The stub modal assigns cat-b alone, taking cat-a off the row.
    await fireEvent.click(within(rowFor('Video 30')).getByTitle('Categories'));
    await fireEvent.click(screen.getByTestId('assign-categories'));

    await waitFor(() => expect(screen.queryByText('Video 30')).not.toBeInTheDocument());
    expect(screen.getByText('Video 50')).toBeInTheDocument();
  });

  it('does not refetch a client-side sort mode when the mutation removed nothing', async () => {
    library = makeItems(150);
    renderView();
    await waitFor(() => expect(screen.getByText('Video 1')).toBeInTheDocument());
    await fireEvent.update(document.querySelector('.sort-select') as HTMLSelectElement, 'name-asc');
    await fireEvent.click(screen.getByLabelText('Pending'));
    await fireEvent.click(screen.getByLabelText('All Statuses'));
    await waitFor(() => expect(screen.getByText('Video 100')).toBeInTheDocument());
    const before = listingCalls();

    await fireEvent.click(within(rowFor('Video 100')).getByRole('checkbox'));
    await fireEvent.click(screen.getByRole('button', { name: 'Bulk Actions' }));
    await fireEvent.click(screen.getByRole('button', { name: 'Approve Selected' }));

    // Nothing left the list, so there is nothing to top up — refetching here would throw away the
    // patch and the admin's place, which is the whole thing this change exists to stop.
    await waitFor(() => expect(within(rowFor('Video 100')).getByText('Approved')).toBeInTheDocument());
    expect(listingCalls()).toBe(before);
  });

  it('does not let a listing response that predates a delete put the row back', async () => {
    library = makeItems(50, n => (n <= 10 ? { status: 'APPROVED' } : {}));
    renderView();
    await waitFor(() => expect(screen.getByText('Video 1')).toBeInTheDocument());

    // Delete first, then change the filter while that delete is still pending — the listing is
    // read before the delete commits, and lands after it.
    deferDelete = true;
    await fireEvent.click(within(rowFor('Video 3')).getByTitle('Delete'));
    deferListing = true;
    await fireEvent.click(screen.getByLabelText('Pending'));

    releaseDelete!();
    await settle();
    releaseListing!();
    await settle();

    // That response was read before the delete committed, so applying it would show a row the
    // registry no longer holds. Dropping it is only half the job: the admin asked for Pending,
    // so they have to end up on Pending rather than stranded on the page they came from.
    await waitFor(() => expect(screen.getByText('Video 30')).toBeInTheDocument());
    expect(screen.queryByText('Video 3')).not.toBeInTheDocument();
  });

  it('does not strand Load More when a refill lands on a page still in flight', async () => {
    library = makeItems(75);
    renderView();
    await waitFor(() => expect(screen.getByText('Video 1')).toBeInTheDocument());

    deferListing = true;
    await fireEvent.click(screen.getByRole('button', { name: /Load More/i }));
    await fireEvent.click(selectAllRows());
    await fireEvent.click(screen.getByRole('button', { name: 'Bulk Actions' }));
    await fireEvent.click(screen.getByRole('button', { name: 'Delete Selected' }));
    await waitFor(() => expect(screen.getByText('Video 26')).toBeInTheDocument());

    releaseListing!();
    await settle();

    // The refill invalidated the page that was in flight. It must still release the loading flag,
    // or the list keeps a spinner where the button was and never loads another page.
    expect(screen.getByRole('button', { name: /Load More/i })).toBeInTheDocument();
  });

  it('loses no row when a delete commits before an in-flight page is read', async () => {
    library = makeItems(51);
    renderView();
    await waitFor(() => expect(screen.getByText('Video 1')).toBeInTheDocument());

    deferListing = true;
    deferReadsLate = true;
    await fireEvent.click(screen.getByRole('button', { name: /Load More/i }));
    await fireEvent.click(within(rowFor('Video 1')).getByTitle('Delete'));
    await waitFor(() => expect(screen.queryByText('Video 1')).not.toBeInTheDocument());

    releaseListing!();
    await settle();

    // That page was computed for an offset one row further along than the server now has, so its
    // first row is Video 27 and Video 26 falls into the gap — where nothing would ever ask for it
    // again, because the page derived from the shortened window lands on the same offset.
    await fireEvent.click(screen.getByRole('button', { name: /Load More/i }));
    await waitFor(() => expect(screen.getByText('Video 26')).toBeInTheDocument());
  });

  it('keeps a page in flight when the mutation removed nothing', async () => {
    library = makeItems(75);
    renderView();
    await waitFor(() => expect(screen.getByText('Video 1')).toBeInTheDocument());

    deferListing = true;
    await fireEvent.click(screen.getByRole('button', { name: /Load More/i }));
    await fireEvent.click(within(rowFor('Video 5')).getByRole('checkbox'));
    await fireEvent.click(screen.getByRole('button', { name: 'Bulk Actions' }));
    await fireEvent.click(screen.getByRole('button', { name: 'Approve Selected' }));

    releaseListing!();
    await settle();

    // Approving under "all statuses" takes no row out, so the offsets that page was computed for
    // still point where they did. Dropping it makes the click the admin already made do nothing.
    expect(screen.getByText('Video 26')).toBeInTheDocument();
  });

  it('drops a row marked pending from a list filtered to approved', async () => {
    library = makeItems(50, () => ({ status: 'APPROVED' }));
    renderView();
    await waitFor(() => expect(screen.getByText('Video 1')).toBeInTheDocument());
    await fireEvent.click(screen.getByLabelText('Approved'));
    await waitFor(() => expect(screen.getByRole('button', { name: /Load More/i })).toBeInTheDocument());
    await fireEvent.click(screen.getByRole('button', { name: /Load More/i }));
    await waitFor(() => expect(screen.getByText('Video 50')).toBeInTheDocument());

    await fireEvent.click(within(rowFor('Video 30')).getByRole('checkbox'));
    await fireEvent.click(screen.getByRole('button', { name: 'Bulk Actions' }));
    await fireEvent.click(screen.getByRole('button', { name: 'Mark as Pending' }));

    await waitFor(() => expect(screen.queryByText('Video 30')).not.toBeInTheDocument());
    expect(screen.getByText('Video 50')).toBeInTheDocument();
  });

  it('asks the server for the list when an approve only partly committed', async () => {
    await loadTwoPages();
    bulkApproveMock.mockResolvedValueOnce({ successCount: 1, errors: ['video not found: v99'] });
    const before = listingCalls();

    await fireEvent.click(within(rowFor('Video 30')).getByRole('checkbox'));
    await fireEvent.click(screen.getByRole('button', { name: 'Bulk Actions' }));
    await fireEvent.click(screen.getByRole('button', { name: 'Approve Selected' }));

    await waitFor(() => expect(listingCalls()).toBe(before + 1));
  });

  it('asks the server for the list when a mark-pending only partly committed', async () => {
    await loadTwoPages();
    bulkMarkPendingMock.mockResolvedValueOnce({ successCount: 0, errors: ['channel not found: c1'] });
    const before = listingCalls();

    await fireEvent.click(within(rowFor('Video 30')).getByRole('checkbox'));
    await fireEvent.click(screen.getByRole('button', { name: 'Bulk Actions' }));
    await fireEvent.click(screen.getByRole('button', { name: 'Mark as Pending' }));

    await waitFor(() => expect(listingCalls()).toBe(before + 1));
  });

  it('asks the server for the list when a category assignment only partly committed', async () => {
    await loadTwoPages();
    bulkAssignCategoriesMock.mockResolvedValueOnce({ successCount: 1, errors: ['playlist not found: p1'] });
    const before = listingCalls();

    await fireEvent.click(within(rowFor('Video 30')).getByTitle('Categories'));
    await fireEvent.click(screen.getByTestId('assign-categories'));

    await waitFor(() => expect(listingCalls()).toBe(before + 1));
  });

  it('gives each row its own categories when one assignment splits into groups', async () => {
    library = makeItems(50, n => {
      if (n === 31) return { categoryIds: ['cat-a', 'cat-c'] };
      if (n === 32) return { categoryIds: ['cat-a', 'cat-d'] };
      return {};
    });
    // An indeterminate category is kept only on the rows that already had it, so one click can
    // resolve to a different final list per row and go out as several calls.
    assignPayload.unchangedIds = ['cat-c', 'cat-d'];
    await loadTwoPages();

    for (const title of ['Video 30', 'Video 31', 'Video 32']) {
      await fireEvent.click(within(rowFor(title)).getByRole('checkbox'));
    }
    await fireEvent.click(screen.getByRole('button', { name: 'Bulk Actions' }));
    await fireEvent.click(screen.getByRole('button', { name: 'Assign Categories' }));
    await fireEvent.click(screen.getByTestId('assign-categories'));

    await waitFor(() => expect(within(rowFor('Video 30')).getByText('Fiqh')).toBeInTheDocument());
    expect(within(rowFor('Video 31')).getByText('Hadith')).toBeInTheDocument();
    expect(within(rowFor('Video 32')).getByText('Tafsir')).toBeInTheDocument();
    // Each row keeps only what its own group resolved to, not the whole selection's union.
    expect(within(rowFor('Video 30')).queryByText('Hadith')).not.toBeInTheDocument();
    expect(within(rowFor('Video 31')).queryByText('Tafsir')).not.toBeInTheDocument();
    expect(screen.getByText('Video 50')).toBeInTheDocument();
  });

  it('refills once when a grouped assignment shrinks a client-sorted page, not once per group', async () => {
    library = makeItems(150, n => ({
      categoryIds: n <= 40 ? ['cat-a', 'cat-c'] : n <= 80 ? ['cat-a', 'cat-d'] : ['cat-a']
    }));
    assignPayload.unchangedIds = ['cat-c', 'cat-d'];
    renderView();
    await waitFor(() => expect(screen.getByText('Video 1')).toBeInTheDocument());
    // Client-side sorts hold one fixed page of a longer list, so they refill on any shrink at all.
    await fireEvent.update(document.querySelector('.sort-select') as HTMLSelectElement, 'name-asc');
    await fireEvent.click(screen.getByLabelText('Aqeedah'));
    await waitFor(() => expect(screen.getByText('Video 100')).toBeInTheDocument());
    const before = listingCalls();

    await fireEvent.click(selectAllRows());
    await fireEvent.click(screen.getByRole('button', { name: 'Bulk Actions' }));
    await fireEvent.click(screen.getByRole('button', { name: 'Assign Categories' }));
    // Hold the first refill open, which is the real shape: a 100-row listing takes longer than the
    // small batch writes, so the later groups all patch while it is still out.
    deferListing = true;
    await fireEvent.click(screen.getByTestId('assign-categories'));

    // Each group shrinks the page again, so each would ask the server for the whole list — one
    // request per group, all but the last discarded.
    expect(listingCalls()).toBe(before + 1);

    releaseListing!();
    await waitFor(() => expect(screen.getByText('Video 101')).toBeInTheDocument());
    // That refill was retired by the later groups' writes, so it is re-issued exactly once.
    expect(listingCalls()).toBe(before + 2);
  });

  it('keeps making progress when rows appear ahead of the loaded window', async () => {
    renderView();
    await waitFor(() => expect(screen.getByText('Video 1')).toBeInTheDocument());

    // Another admin adds a page worth of newer content. Every later row shifts down by that much,
    // so the page derived from what is held now lands entirely on rows already loaded.
    library = [...makeItems(25).map((item, i) => ({ ...item, id: `n${i + 1}`, title: `New ${i + 1}` })), ...library];

    await fireEvent.click(screen.getByRole('button', { name: /Load More/i }));
    await waitFor(() => expect(screen.getByRole('button', { name: /Load More/i })).toBeInTheDocument());
    await fireEvent.click(screen.getByRole('button', { name: /Load More/i }));

    // A page that adds nothing must not be asked for again, or the button is dead for good.
    await waitFor(() => expect(screen.getByText('Video 26')).toBeInTheDocument());
  });

  it('does not reload the listing when an exclusion is changed', async () => {
    library = makeItems(50, n => (n === 30 ? { type: 'channel', id: 'c30', title: 'Channel 30' } : {}));
    await loadTwoPages();
    const before = listingCalls();

    await fireEvent.click(within(rowFor('Channel 30')).getByTitle('View'));
    await fireEvent.click(screen.getByTestId('exclusion-changed'));

    // Excluding a video inside a channel changes nothing this list renders, and reloading it
    // discards every page loaded behind the still-open modal.
    expect(listingCalls()).toBe(before);
    expect(screen.getByText('Video 50')).toBeInTheDocument();
  });
});
