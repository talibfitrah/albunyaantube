import '@testing-library/jest-dom';
import { render, screen, fireEvent } from '@testing-library/vue';
import { createI18n } from 'vue-i18n';
import { describe, it, expect, vi } from 'vitest';
import ContentSortingView from '@/views/ContentSortingView.vue';
import { messages } from '@/locales/messages';

const status = vi.hoisted(() => ({ fn: vi.fn() }));

vi.mock('@/services/sortOrder', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/services/sortOrder')>()),
  getCategorySortOrder: () => Promise.resolve([
    { id: 'live', name: 'Channels Live', icon: null, localizedNames: null, displayOrder: 17, contentCount: 2, parentCategoryId: null }
  ]),
  getCategoryContentOrder: () => Promise.resolve([
    { contentId: 'channel_UCa', contentType: 'channel', title: 'Zad', thumbnailUrl: null, position: 0, youtubeId: 'UCa' },
    { contentId: 'doc-1', contentType: 'video', title: 'A stream', thumbnailUrl: null, position: 1, youtubeId: 'vid00000001' }
  ])
}));
vi.mock('@/services/liveChannels', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/services/liveChannels')>()),
  getLiveStatus: () => status.fn()
}));

const ok = () => Promise.resolve({
  suspectSince: '2026-10-17T01:20:00Z',
  categories: [{ id: 'live', entries: [
    { contentType: 'channel', contentId: 'channel_UCa', status: 'LIVE', lives: [{ id: 'x', title: 't', served: 'SERVED' }] },
    { contentType: 'video', contentId: 'doc-1', status: 'MISSING', misses: 1 }
  ] }]
});
const mount = (locale = 'en') =>
  render(ContentSortingView, { global: { plugins: [createI18n({ legacy: false, locale, messages })] } });

describe('Content Sorting on a live category (spec §8)', () => {
  it('shows the note, the suspect banner and a chip per row', async () => {
    status.fn.mockImplementation(ok);
    mount();

    await fireEvent.click(await screen.findByText('Channels Live'));

    expect(await screen.findByText(/Users see each channel's current live streams/)).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('Live check suspect since');
    expect(screen.getByText('Live · 1')).toBeInTheDocument();
    expect(screen.getByText('Not live (1 of 2)')).toBeInTheDocument();
  });

  it('renders in Arabic', async () => {
    status.fn.mockImplementation(ok);
    mount('ar');

    await fireEvent.click(await screen.findByText('Channels Live'));

    expect(await screen.findByText(/يرى المستخدمون البث المباشر الحالي/)).toBeInTheDocument();
  });

  it('a 500 from the status call says live status is unavailable; a 403 stays silent (F-M9)', async () => {
    status.fn.mockImplementation(() => Promise.reject({ response: { status: 500 } }));
    const { unmount } = mount();
    expect(await screen.findByText('Live status unavailable right now.')).toBeInTheDocument();
    unmount();

    status.fn.mockImplementation(() => Promise.reject({ response: { status: 403 } }));
    mount();
    await screen.findByText('Channels Live');
    expect(screen.queryByText('Live status unavailable right now.')).toBeNull();
  });

  it('a failed status call (moderator 403) shows no live UI and still lists the rows', async () => {
    status.fn.mockImplementation(() => Promise.reject({ response: { status: 403 } }));
    mount();

    await fireEvent.click(await screen.findByText('Channels Live'));

    expect(await screen.findByText('Zad')).toBeInTheDocument();
    expect(screen.queryByText(/Users see each channel's current live streams/)).toBeNull();
    expect(screen.queryByRole('status')).toBeNull();
    expect(screen.queryByText(/^Live ·/)).toBeNull();
  });
});
