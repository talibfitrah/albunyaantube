import '@testing-library/jest-dom';
import { render, screen, fireEvent, within } from '@testing-library/vue';
import { createI18n } from 'vue-i18n';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import ContentLibraryView from '@/views/ContentLibraryView.vue';
import CategoryAssignmentModal from '@/components/CategoryAssignmentModal.vue';
import { messages } from '@/locales/messages';

const getMock = vi.fn();
const bulkAssignCategoriesMock = vi.fn();

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
  bulkAssignCategories: (...args: unknown[]) => bulkAssignCategoriesMock(...args)
}));

const CATEGORIES = [
  { id: 'cat-tech', name: 'Technology', parentCategoryId: null },
  { id: 'cat-quran', name: 'Quran', parentCategoryId: null }
];

const base = {
  type: 'channel',
  status: 'APPROVED',
  createdAt: '2026-08-19T00:00:00Z',
  keywords: []
};
const TECH_CHANNEL = { ...base, id: 'c1', youtubeId: 'yt-c1', title: 'Boma', categoryIds: ['cat-tech'] };
const QURAN_CHANNEL = { ...base, id: 'c2', youtubeId: 'yt-c2', title: 'Afaaq', categoryIds: ['cat-quran'] };

function renderView() {
  return render(ContentLibraryView, {
    global: { plugins: [createI18n({ legacy: false, locale: 'en', messages })] }
  });
}

function stubLibrary(items: unknown[]) {
  getMock.mockImplementation((url: string) => {
    if (url === '/api/admin/categories') {
      return Promise.resolve({ data: CATEGORIES });
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

async function openModal() {
  const dialog = await screen.findByRole('dialog');
  await within(dialog).findByText('Technology');
  return dialog;
}

function checkboxFor(dialog: HTMLElement, name: string): HTMLInputElement {
  return within(dialog).getByText(name).closest('label')!.querySelector('input')!;
}

/** Tick every item row's checkbox (row 0 is the header). */
async function selectAllRows() {
  const rows = (await screen.findAllByRole('row')).slice(1);
  for (const row of rows) {
    await fireEvent.click(within(row).getByRole('checkbox'));
  }
}

/**
 * Assignment REPLACES categoryIds server-side, so whatever the modal does not
 * send back is deleted from the item.
 */
describe('ContentLibraryView — Assign Categories modal', () => {
  beforeEach(() => {
    getMock.mockReset();
    bulkAssignCategoriesMock.mockReset();
    bulkAssignCategoriesMock.mockResolvedValue({ successCount: 1, errors: [] });
    vi.stubGlobal('alert', vi.fn());
  });

  it('opens with the categories already assigned to the item checked', async () => {
    stubLibrary([TECH_CHANNEL]);
    renderView();

    await fireEvent.click((await screen.findAllByTitle('Categories'))[0]);
    const dialog = await openModal();

    expect(checkboxFor(dialog, 'Technology')).toBeChecked();
    expect(checkboxFor(dialog, 'Quran')).not.toBeChecked();
  });

  it('lets a single item have its last category removed', async () => {
    stubLibrary([TECH_CHANNEL]);
    renderView();

    await fireEvent.click((await screen.findAllByTitle('Categories'))[0]);
    const dialog = await openModal();
    await fireEvent.click(checkboxFor(dialog, 'Technology'));

    // fireEvent.click fires on a disabled button too, so assert the attribute:
    // with nothing ticked the button must still be usable, or "remove them all"
    // is unreachable.
    const assign = within(dialog).getByRole('button', { name: 'Assign' });
    expect(assign).not.toBeDisabled();
    await fireEvent.click(assign);

    expect(bulkAssignCategoriesMock).toHaveBeenCalledWith([{ type: 'channel', id: 'c1' }], []);
  });

  it('marks a category held by only some of the selected items as indeterminate', async () => {
    stubLibrary([TECH_CHANNEL, QURAN_CHANNEL]);
    renderView();

    await selectAllRows();
    await fireEvent.click(screen.getByRole('button', { name: 'Bulk Actions' }));
    await fireEvent.click(screen.getByRole('button', { name: 'Assign Categories' }));
    const dialog = await openModal();

    expect(checkboxFor(dialog, 'Technology').indeterminate).toBe(true);
    expect(checkboxFor(dialog, 'Quran').indeterminate).toBe(true);
  });

  it('adds a category to a bulk selection without wiping what each item already had', async () => {
    stubLibrary([TECH_CHANNEL, QURAN_CHANNEL]);
    renderView();

    await selectAllRows();
    await fireEvent.click(screen.getByRole('button', { name: 'Bulk Actions' }));
    await fireEvent.click(screen.getByRole('button', { name: 'Assign Categories' }));
    const dialog = await openModal();

    // Indeterminate → checked: apply Technology to every selected item. Quran
    // stays indeterminate, so the item that has it keeps it.
    await fireEvent.click(checkboxFor(dialog, 'Technology'));
    await fireEvent.click(within(dialog).getByRole('button', { name: 'Assign' }));

    const sent = new Map<string, string[]>();
    for (const [items, categoryIds] of bulkAssignCategoriesMock.mock.calls) {
      for (const item of items as { id: string }[]) sent.set(item.id, [...(categoryIds as string[])].sort());
    }
    expect(sent.get('c1')).toEqual(['cat-tech']);
    expect(sent.get('c2')).toEqual(['cat-quran', 'cat-tech']);
  });
});

/**
 * ContentSearchView shares this modal to pick categories for a brand-new
 * submission, where an empty selection would file uncategorised content.
 */
describe('CategoryAssignmentModal — empty selection', () => {
  beforeEach(() => {
    getMock.mockReset();
    getMock.mockResolvedValue({ data: CATEGORIES });
  });

  async function renderModal(props: Record<string, unknown>) {
    render(CategoryAssignmentModal, {
      props: { isOpen: true, multiSelect: true, ...props },
      global: { plugins: [createI18n({ legacy: false, locale: 'en', messages })] }
    });
    const dialog = await screen.findByRole('dialog');
    await within(dialog).findByText('Technology');
    return dialog;
  }

  it('blocks assigning nothing by default', async () => {
    const dialog = await renderModal({});

    expect(within(dialog).getByRole('button', { name: 'Assign' })).toBeDisabled();
  });

  it('allows assigning nothing when the caller is editing existing assignments', async () => {
    const dialog = await renderModal({ allowEmpty: true });

    expect(within(dialog).getByRole('button', { name: 'Assign' })).not.toBeDisabled();
  });
});
