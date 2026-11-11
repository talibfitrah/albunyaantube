/**
 * A Load More still in flight when the admin switches tab or searches belongs to the old listing.
 * Landing after the new first page, it appended the old query's rows and installed its cursor, so
 * the next Load More resumed the new listing at the old one's position, skipping rows.
 */
import '@testing-library/jest-dom';
import { render, screen, fireEvent, waitFor } from '@testing-library/vue';
import { createI18n } from 'vue-i18n';
import { describe, it, expect, vi } from 'vitest';
import ContentBrowserModal from '@/components/exclusions/ContentBrowserModal.vue';
import { messages } from '@/locales/messages';

const getMock = vi.fn();
vi.mock('@/services/api/client', () => ({
  default: { get: (...args: unknown[]) => getMock(...args) }
}));

describe('ContentBrowserModal paging', () => {
  it('drops a Load More that belongs to the listing before a tab change', async () => {
    let releaseOld: (() => void) | null = null;
    getMock.mockImplementation((_url: string, config: { params: Record<string, string> }) => {
      const p = config.params;
      if (p.cursor) {
        return new Promise(resolve => {
          releaseOld = () => resolve({ data: { content: [{ id: 'c2', title: 'Old channel two', type: 'CHANNEL' }], nextCursor: 'old-2' } });
        });
      }
      if (p.types === 'PLAYLIST') {
        return Promise.resolve({ data: { content: [{ id: 'p1', title: 'Playlist one', type: 'PLAYLIST' }], nextCursor: null } });
      }
      return Promise.resolve({ data: { content: [{ id: 'c1', title: 'Channel one', type: 'CHANNEL' }], nextCursor: 'old-1' } });
    });

    const i18n = createI18n({ legacy: false, locale: 'en', messages });
    const view = render(ContentBrowserModal, { props: { open: false }, global: { plugins: [i18n] } });
    await view.rerender({ open: true });
    await waitFor(() => expect(screen.getByText('Channel one')).toBeInTheDocument());

    await fireEvent.scroll(document.querySelector('.content-list')!); // Load More for the old listing
    await waitFor(() => expect(releaseOld).not.toBeNull());
    await fireEvent.click(screen.getByText(messages.en.exclusions.contentBrowser.filterPlaylists));
    await waitFor(() => expect(screen.getByText('Playlist one')).toBeInTheDocument());

    releaseOld!();
    await new Promise(resolve => setTimeout(resolve));

    expect(screen.queryByText('Old channel two')).not.toBeInTheDocument();
  });

  it('does not load more while a new listing is still loading', async () => {
    getMock.mockReset();
    let releasePlaylists: (() => void) | null = null;
    getMock.mockImplementation((_url: string, config: { params: Record<string, string> }) => {
      const p = config.params;
      if (p.types === 'PLAYLIST') {
        return new Promise(resolve => {
          releasePlaylists = () => resolve({ data: { content: [{ id: 'p1', title: 'Playlist one', type: 'PLAYLIST' }], nextCursor: null } });
        });
      }
      return Promise.resolve({ data: { content: [{ id: 'c1', title: 'Channel one', type: 'CHANNEL' }], nextCursor: 'old-1' } });
    });

    const i18n = createI18n({ legacy: false, locale: 'en', messages });
    const view = render(ContentBrowserModal, { props: { open: false }, global: { plugins: [i18n] } });
    await view.rerender({ open: true });
    await waitFor(() => expect(screen.getByText('Channel one')).toBeInTheDocument());

    await fireEvent.click(screen.getByText(messages.en.exclusions.contentBrowser.filterPlaylists));
    await waitFor(() => expect(releasePlaylists).not.toBeNull());
    // The emptied list clamps the scroll position, which fires a scroll event.
    await fireEvent.scroll(document.querySelector('.content-list')!);
    releasePlaylists!();
    await waitFor(() => expect(screen.getByText('Playlist one')).toBeInTheDocument());

    // A Load More here would ask the new listing for its first page again (no cursor) and append it.
    expect(getMock).toHaveBeenCalledTimes(2);
    expect(screen.getAllByText('Playlist one')).toHaveLength(1);
  });
});
