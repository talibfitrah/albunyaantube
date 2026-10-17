import '@testing-library/jest-dom';
import { render, screen, fireEvent, waitFor, within } from '@testing-library/vue';
import { createI18n } from 'vue-i18n';
import { describe, it, expect, vi } from 'vitest';
import CategoryAssignmentModal from '@/components/CategoryAssignmentModal.vue';
import CategoriesView from '@/views/CategoriesView.vue';
import { messages } from '@/locales/messages';

const auth = vi.hoisted(() => ({ isAdmin: true }));
const service = vi.hoisted(() => ({ updateCategory: vi.fn(() => Promise.resolve({})) }));
vi.mock('@/stores/auth', () => ({ useAuthStore: () => auth }));
vi.mock('@/services/categoryService', () => ({
  getAllCategories: () => Promise.resolve([
    { id: 'live', name: 'Channels Live', label: 'Channels Live', type: 'LIVE', subcategories: [] },
    { id: 'quran', name: 'Quran', label: 'Quran', type: null, subcategories: [] }
  ]),
  createCategory: vi.fn(),
  updateCategory: service.updateCategory,
  deleteCategory: vi.fn()
}));

function renderModal(contentType: 'channel' | 'playlist' | 'video') {
  return render(CategoryAssignmentModal, {
    props: { isOpen: true, multiSelect: true, contentType },
    global: { plugins: [createI18n({ legacy: false, locale: 'en', messages })] }
  });
}

describe('CategoryAssignmentModal and the live category (spec §8)', () => {
  it('hides the live category from moderators', async () => {
    auth.isAdmin = false;
    renderModal('channel');
    expect(await screen.findByText('Quran')).toBeInTheDocument();
    expect(screen.queryByText('Channels Live')).not.toBeInTheDocument();
  });

  it('disables it for a playlist, with the reason', async () => {
    auth.isAdmin = true;
    renderModal('playlist');
    const label = await screen.findByText('Channels Live');
    const checkbox = label.closest('label')!.querySelector('input[type=checkbox]') as HTMLInputElement;
    expect(checkbox).toBeDisabled();
    expect(screen.getByText('Live category: channels and live streams only')).toBeInTheDocument();
  });

  it('ignores a click on a disabled row', async () => {
    auth.isAdmin = true;
    renderModal('playlist');
    const label = await screen.findByText('Channels Live');
    await fireEvent.click(label);
    expect(label.closest('label')!.querySelector('input[type=checkbox]')).not.toBeChecked();
    expect(screen.getByRole('button', { name: 'Assign' })).toBeDisabled(); // nothing selected
  });

  it('keeps it selectable for a video (the server checks live)', async () => {
    auth.isAdmin = true;
    renderModal('video');
    const label = await screen.findByText('Channels Live');
    expect(label.closest('label')!.querySelector('input[type=checkbox]')).not.toBeDisabled();
  });

  it('drops a tick carried over from a channel add when the next item is a playlist', async () => {
    auth.isAdmin = true;
    const { rerender } = renderModal('channel');
    const checkbox = (await screen.findByText('Channels Live')).closest('label')!
      .querySelector('input[type=checkbox]') as HTMLInputElement;
    await fireEvent.click(checkbox);
    expect(checkbox).toBeChecked();

    await rerender({ contentType: 'playlist' });

    expect(checkbox).toBeDisabled();
    expect(checkbox).not.toBeChecked();
  });
});

/** Opens the edit dialog for `name`, picks Live channels, and saves. */
async function saveAsLive(name: string) {
  render(CategoriesView, { global: { plugins: [createI18n({ legacy: false, locale: 'en', messages })] } });
  const row = (await screen.findByText(name)).closest('.category-row') as HTMLElement;
  await fireEvent.click(within(row).getByTitle('Edit'));
  const select = screen.getByLabelText('Type') as HTMLSelectElement;
  select.selectedIndex = 1; // Live channels
  await fireEvent.change(select);
  await fireEvent.submit(select.closest('form')!);
}

const formError = () => document.querySelector('.form-error')?.textContent;

describe('CategoriesView and the category type (spec §8, R18)', () => {
  it('shows the chip, and sends type null when a live category goes back to standard', async () => {
    render(CategoriesView, { global: { plugins: [createI18n({ legacy: false, locale: 'en', messages })] } });
    const row = (await screen.findByText('Channels Live')).closest('.category-row') as HTMLElement;
    expect(within(row).getByText('Live channels')).toBeInTheDocument();
    expect(within(row).queryByTitle('Add Subcategory')).not.toBeInTheDocument();

    await fireEvent.click(within(row).getByTitle('Edit'));
    const select = screen.getByLabelText('Type') as HTMLSelectElement;
    expect(screen.getByText(messages.en.categories.dialog.typeLiveHelp)).toBeInTheDocument();
    select.selectedIndex = 0; // Standard
    await fireEvent.change(select);
    await fireEvent.submit(select.closest('form')!);

    // An omitted type keeps LIVE on the server, so clearing must be an explicit null.
    await waitFor(() => expect(service.updateCategory).toHaveBeenCalledWith('live', expect.objectContaining({ type: null })));
  });

  it('lists each offending member when the flip to LIVE is refused (MEMBERS_INVALID)', async () => {
    service.updateCategory.mockRejectedValueOnce(Object.assign(new Error('Request failed with status code 400'), {
      response: { status: 400, data: { code: 'LIVE_CATEGORY_MEMBERS_INVALID', message: 'members invalid',
        details: { offenders: [{ ref: 'PLabc', code: 'LIVE_CATEGORY_TYPE_NOT_ALLOWED' }] } } }
    }));
    await saveAsLive('Quran');

    const en = messages.en.apiErrors;
    await waitFor(() => expect(formError()).toBe(`${en.LIVE_CATEGORY_MEMBERS_INVALID}\nPLabc: ${en.LIVE_CATEGORY_TYPE_NOT_ALLOWED}`));
  });

  it('shows the localized fallback, not axios English, for an uncoded failure', async () => {
    service.updateCategory.mockRejectedValueOnce(
      Object.assign(new Error('Request failed with status code 500'), { response: { status: 500, data: {} } }));
    await saveAsLive('Quran');

    await waitFor(() => expect(formError()).toBe(messages.en.categories.dialog.error));
  });
});
