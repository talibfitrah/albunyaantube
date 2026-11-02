import '@testing-library/jest-dom';
import { render, screen, fireEvent } from '@testing-library/vue';
import { createI18n } from 'vue-i18n';
import { describe, it, expect, vi } from 'vitest';
import ContentSortingView from '@/views/ContentSortingView.vue';
import BulkImportExportView from '@/views/BulkImportExportView.vue';
import { messages } from '@/locales/messages';
import apiClient from '@/services/api/client';
import { bulkSubmissionService } from '@/services/bulkSubmissionService';

const toastError = vi.hoisted(() => vi.fn());
const importRun = vi.hoisted(() => ({ value: {} as Record<string, unknown> }));

vi.mock('@/services/api/client');
vi.mock('@/utils/toast', () => ({ toast: { error: toastError, success: vi.fn(), warning: vi.fn() } }));
vi.mock('@/services/categoryService', () => ({
  getAllCategories: () => Promise.resolve([{ id: 'live', name: 'Channels Live', type: 'REGULAR', subcategories: [] }])
}));
vi.mock('@/services/liveChannels', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/services/liveChannels')>()),
  getLiveStatus: () => Promise.resolve({ suspectSince: null, categories: [] })
}));
vi.mock('@/services/sortOrder', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/services/sortOrder')>()),
  getCategorySortOrder: () => Promise.resolve([
    { id: 'live', name: 'Channels Live', icon: null, localizedNames: null, displayOrder: 17, contentCount: 0, parentCategoryId: null }
  ]),
  getCategoryContentOrder: () => Promise.resolve([]),
  getApprovedContent: () => Promise.resolve({ items: [
    { id: 'pl-1', type: 'playlist', youtubeId: null, title: 'A playlist', thumbnailUrl: null }], truncated: false }),
  addContentToCategorySort: () => Promise.reject(Object.assign(new Error('Request failed with status code 400'), {
    response: { status: 400, data: { code: 'LIVE_CATEGORY_TYPE_NOT_ALLOWED', message: 'x',
      details: { offenders: [{ ref: 'playlist:pl-1', code: 'LIVE_CATEGORY_TYPE_NOT_ALLOWED' }] } } }
  }))
}));
vi.mock('@/services/importExportService', () => ({
  default: {
    importSimpleAsync: () => Promise.resolve({ runId: 'run-1' }),
    getImportStatus: () => Promise.resolve({ run: importRun.value })
  }
}));

const mount = (view: unknown, locale = 'en') =>
  render(view as never, { global: { plugins: [createI18n({ legacy: false, locale, messages })] } });

describe('a live-category refusal is shown in the admin\'s language (spec §8)', () => {
  it('Content Sorting: a refused add stays on screen with the localized reason and each offender (F-C2)', async () => {
    mount(ContentSortingView);
    await fireEvent.click(await screen.findByText('Channels Live'));
    await fireEvent.click(await screen.findByText(/Add Content/));
    await fireEvent.click(await screen.findByText('A playlist'));
    await fireEvent.click(screen.getByRole('button', { name: /^Add/ }));

    const reason = 'The live category accepts only channels and streams that are live right now.';
    const box = await screen.findByRole('alert');
    expect(box).toHaveTextContent(reason);
    expect(box).toHaveTextContent(`A playlist: ${reason}`);
    expect(toastError).not.toHaveBeenCalled();
  });
});

/** Pick a file and press Import: the simple format goes through the async run (pre-flight M8). */
async function runImport(locale = 'en') {
  const { container } = mount(BulkImportExportView, locale);
  const input = container.querySelector('input[type="file"]') as HTMLInputElement;
  await fireEvent.change(input, { target: { files: [new File(['[{},{},{}]'], 'import.json', { type: 'application/json' })] } });
  await fireEvent.click(container.querySelector('.import-actions .btn-primary')!);
  return container;
}

describe('Import: a refused async import lists each offender (spec §8, D9)', () => {
  const refused = {
    status: 'FAILED', currentPhase: 'LIVE_CATEGORY_REFUSED', details: {
      code: 'LIVE_CATEGORY_TYPE_NOT_ALLOWED', errorMessage: '1 item(s) refused',
      offenders: [{ ref: 'playlist:PLit1r16a000000000000000000000000', code: 'LIVE_CATEGORY_TYPE_NOT_ALLOWED' }]
    }
  };

  it('shows the localized reason and one line per offending row', async () => {
    importRun.value = refused;
    const container = await runImport();

    await vi.waitFor(() => {
      const box = container.querySelector('.error-message');
      expect(box).toHaveTextContent('The live category accepts only channels and streams that are live right now.');
      expect(box).toHaveTextContent('playlist:PLit1r16a000000000000000000000000: The live category accepts only');
    }, { timeout: 3000 });
    expect(container.querySelector('.phase-indicator')).toHaveTextContent('Refused by the live category. Nothing was imported.');
  });

  it('a failed run clears the "Import started" success box (F-M3)', async () => {
    importRun.value = refused;
    const container = await runImport();

    await vi.waitFor(() => expect(container.querySelector('.error-message')).not.toBeNull(), { timeout: 3000 });
    expect(container.querySelector('.success-message')).toBeNull();
  });

  it('renders the refusal in Arabic', async () => {
    importRun.value = refused;
    const container = await runImport('ar');

    await vi.waitFor(() => expect(container.querySelector('.error-message'))
      .toHaveTextContent('تقبل فئة البث المباشر القنوات والبث الجاري الآن فقط.'), { timeout: 3000 });
    expect(container.querySelector('.phase-indicator')).toHaveTextContent('رفضته فئة البث المباشر. لم يُستورد أي شيء.');
  });

  it('a failed live check says to try again, never "import failed"', async () => {
    importRun.value = {
      status: 'FAILED', currentPhase: 'LIVE_CHECK_UNAVAILABLE', details: {
        code: 'LIVE_CHECK_UNAVAILABLE', errorMessage: '1 item(s) refused',
        offenders: [{ ref: 'video:vid00000001', code: 'LIVE_CHECK_UNAVAILABLE' }]
      }
    };
    const container = await runImport();

    await vi.waitFor(() => expect(container.querySelector('.error-message'))
      .toHaveTextContent("Couldn't confirm the stream is live. Try again in a minute."), { timeout: 3000 });
    expect(container.querySelector('.phase-indicator')).toHaveTextContent('Live check unavailable. Nothing was imported.');
  });

  it('an ordinary failed run keeps the generic message', async () => {
    importRun.value = { status: 'FAILED', currentPhase: 'VIDEOS', details: { errorMessage: 'boom' } };
    const container = await runImport();

    await vi.waitFor(() => expect(container.querySelector('.error-message'))
      .toHaveTextContent(messages.en.bulkImportExport.import.importFailed), { timeout: 3000 });
  });
});

describe('bulk submit: a moderator\'s live refusal is not doubled by the global English toast', () => {
  it('asks the client to skip its global error toast', async () => {
    vi.mocked(apiClient.post).mockResolvedValue({ data: {} });
    await bulkSubmissionService.submitBulk({ rows: [] });

    expect(vi.mocked(apiClient.post).mock.calls[0][2]).toMatchObject({ suppressGlobalErrorToast: true });
  });
});
