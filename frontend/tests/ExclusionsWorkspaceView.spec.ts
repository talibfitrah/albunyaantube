import '@testing-library/jest-dom';
import { render, screen, fireEvent, waitFor } from '@testing-library/vue';
import { createI18n } from 'vue-i18n';
import { createRouter, createMemoryHistory, RouterView } from 'vue-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { h, defineComponent } from 'vue';
import ExclusionsWorkspaceView from '@/views/ExclusionsWorkspaceView.vue';
import { messages } from '@/locales/messages';
import {
  fetchExclusionsPage,
  createExclusion,
  removeExclusion
} from '@/services/exclusions';
import type { Exclusion, ExclusionPage } from '@/types/exclusions';

vi.mock('@/services/exclusions', () => ({
  fetchExclusionsPage: vi.fn(),
  createExclusion: vi.fn(),
  removeExclusion: vi.fn()
}));

// Mock the modal components to emit 'updated' event when needed
vi.mock('@/components/exclusions/ChannelDetailModal.vue', () => ({
  default: {
    name: 'ChannelDetailModal',
    props: ['open', 'channelId', 'channelYoutubeId'],
    emits: ['close', 'updated'],
    setup(props: any, { emit }: any) {
      return () => h('div', { 'data-testid': 'channel-modal', role: 'dialog' }, [
        h('button', {
          'data-testid': 'trigger-update',
          onClick: () => emit('updated')
        }, 'Trigger Update')
      ]);
    }
  }
}));

vi.mock('@/components/exclusions/PlaylistDetailModal.vue', () => ({
  default: {
    name: 'PlaylistDetailModal',
    props: ['open', 'playlistId', 'playlistYoutubeId'],
    emits: ['close', 'updated'],
    setup(props: any, { emit }: any) {
      return () => h('div', { 'data-testid': 'playlist-modal', role: 'dialog' }, [
        h('button', {
          'data-testid': 'trigger-update',
          onClick: () => emit('updated')
        }, 'Trigger Update')
      ]);
    }
  }
}));

vi.mock('@/components/exclusions/ContentBrowserModal.vue', () => ({
  default: {
    name: 'ContentBrowserModal',
    props: ['open'],
    emits: ['close', 'manual', 'updated'],
    setup(props: any, { emit }: any) {
      return () => props.open
        ? h('div', { 'data-testid': 'content-browser-modal', role: 'dialog' }, [
            h('button', {
              'data-testid': 'manual-entry-btn',
              onClick: () => emit('manual')
            }, 'Manual Entry')
          ])
        : null;
    }
  }
}));

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  messages
});

function buildRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'exclusions', component: ExclusionsWorkspaceView },
      { path: '/other', name: 'other', component: { template: '<div />' } }
    ]
  });
}

// Wrap in RouterView so onBeforeRouteLeave has an active route record
const RouterWrapper = defineComponent({
  setup() {
    return () => h(RouterView);
  }
});

async function renderView() {
  const router = buildRouter();
  router.push('/');
  await router.isReady();
  return render(RouterWrapper, {
    global: {
      plugins: [i18n, router]
    }
  });
}

const baseUser = {
  id: 'user-1',
  email: 'admin@example.com',
  displayName: 'Admin Example',
  roles: ['ADMIN'],
  status: 'ACTIVE',
  lastLoginAt: null,
  createdAt: '2025-09-01T00:00:00Z',
  updatedAt: '2025-09-01T00:00:00Z'
} as const;

function createPage(data: Exclusion[]): ExclusionPage {
  return {
    data,
    pageInfo: {
      cursor: null,
      nextCursor: null,
      hasNext: false,
      limit: data.length
    }
  };
}

describe('ExclusionsWorkspaceView', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    const fetchMock = fetchExclusionsPage as unknown as vi.Mock;
    fetchMock.mockResolvedValue(createPage([
      {
        id: 'exclusion-1',
        parentType: 'CHANNEL',
        parentId: 'channel:alqalam',
        excludeType: 'PLAYLIST',
        excludeId: 'playlist:alqalam-foundation',
        reason: 'Manual removal pending QA',
        createdAt: '2025-09-20T08:30:00Z',
        createdBy: baseUser
      },
      {
        id: 'exclusion-2',
        parentType: 'PLAYLIST',
        parentId: 'playlist:series-halaqa',
        excludeType: 'VIDEO',
        excludeId: 'video:daily-halaqa-231',
        reason: 'Contains unrelated segment',
        createdAt: '2025-09-25T12:40:00Z',
        createdBy: baseUser
      }
    ]));
    (createExclusion as unknown as vi.Mock).mockReset();
    (removeExclusion as unknown as vi.Mock).mockReset();
  });

  it('filters results by search query', async () => {
    const fetchMock = fetchExclusionsPage as unknown as vi.Mock;
    const firstPage = createPage([
      {
        id: 'exclusion-1',
        parentType: 'CHANNEL',
        parentId: 'channel:alqalam',
        excludeType: 'PLAYLIST',
        excludeId: 'playlist:alqalam-foundation',
        reason: 'Manual removal pending QA',
        createdAt: '2025-09-20T08:30:00Z',
        createdBy: baseUser
      },
      {
        id: 'exclusion-2',
        parentType: 'PLAYLIST',
        parentId: 'playlist:series-halaqa',
        excludeType: 'VIDEO',
        excludeId: 'video:daily-halaqa-231',
        reason: 'Contains unrelated segment',
        createdAt: '2025-09-25T12:40:00Z',
        createdBy: baseUser
      }
    ]);
    const secondPage = createPage([
      {
        id: 'exclusion-2',
        parentType: 'PLAYLIST',
        parentId: 'playlist:series-halaqa',
        excludeType: 'VIDEO',
        excludeId: 'video:daily-halaqa-231',
        reason: 'Contains unrelated segment',
        createdAt: '2025-09-25T12:40:00Z',
        createdBy: baseUser
      }
    ]);
    fetchMock.mockResolvedValueOnce(firstPage);
    fetchMock.mockResolvedValueOnce(secondPage);

    await renderView();

    await screen.findByText('playlist:alqalam-foundation', { selector: '.entity-label' });

    const search = screen.getByRole('searchbox', { name: /search exclusions/i });
    await fireEvent.update(search, 'daily');

    await waitFor(() => {
      expect(
        screen.queryByText('playlist:alqalam-foundation', { selector: '.entity-label' })
      ).not.toBeInTheDocument();
      expect(
        screen.getByText('video:daily-halaqa-231', { selector: '.entity-label' })
      ).toBeInTheDocument();
    });
  });

  it('removes selected exclusions in bulk', async () => {
    const fetchMock = fetchExclusionsPage as unknown as vi.Mock;
    fetchMock.mockResolvedValueOnce(
      createPage([
        {
          id: 'exclusion-1',
          parentType: 'CHANNEL',
          parentId: 'channel:alqalam',
          excludeType: 'PLAYLIST',
          excludeId: 'playlist:alqalam-foundation',
          reason: 'Manual removal pending QA',
          createdAt: '2025-09-20T08:30:00Z',
          createdBy: baseUser
        }
      ])
    );
    fetchMock.mockResolvedValueOnce(createPage([]));

    await renderView();

    const checkbox = await screen.findByRole('checkbox', {
      name: /select playlist:alqalam-foundation for bulk action/i
    });
    await fireEvent.click(checkbox);

    expect(await screen.findByText('1 selected')).toBeInTheDocument();

    const bulkRemove = screen.getByRole('button', { name: /remove selected/i });
    (removeExclusion as unknown as vi.Mock).mockResolvedValue(undefined);
    await fireEvent.click(bulkRemove);

    await waitFor(() => {
      expect(removeExclusion).toHaveBeenCalledWith('exclusion-1');
      expect(screen.getByRole('status')).toHaveTextContent('1 exclusions removed.');
    });
  });

  it('adds a new exclusion via the dialog', async () => {
    const fetchMock = fetchExclusionsPage as unknown as vi.Mock;
    fetchMock.mockResolvedValueOnce(createPage([]));
    const response: Exclusion = {
      id: 'exclusion-3',
      parentType: 'CHANNEL',
      parentId: 'channel:new-parent',
      excludeType: 'VIDEO',
      excludeId: 'video:new',
      reason: 'LIVESTREAM',
      createdAt: '2025-10-01T10:00:00Z',
      createdBy: baseUser
    };
    fetchMock.mockResolvedValueOnce(createPage([response]));

    await renderView();

    const trigger = await screen.findByRole('button', { name: /add exclusion/i });
    await fireEvent.click(trigger);

    // Content browser opens first; click "Manual Entry" to get to the form
    const manualEntryBtn = await screen.findByTestId('manual-entry-btn');
    await fireEvent.click(manualEntryBtn);

    const parentIdField = await screen.findByLabelText(/parent id/i);
    const excludedIdField = screen.getByLabelText(/excluded id/i);
    const contentTypeSelect = screen.getByLabelText(/content type/i);

    await fireEvent.update(parentIdField, 'channel:new-parent');
    const typeSelect = screen.getByLabelText(/excluded type/i);
    await fireEvent.update(typeSelect, 'VIDEO');
    await fireEvent.update(excludedIdField, 'video:new');
    await fireEvent.update(contentTypeSelect, 'LIVESTREAM');

    (createExclusion as unknown as vi.Mock).mockResolvedValue(response);

    const submit = screen.getByRole('button', { name: /create exclusion/i });
    await fireEvent.click(submit);

    await waitFor(() => {
      expect(createExclusion).toHaveBeenCalledWith({
        parentType: 'CHANNEL',
        parentId: 'channel:new-parent',
        excludeType: 'VIDEO',
        excludeId: 'video:new',
        reason: 'LIVESTREAM'
      });
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
      expect(screen.getByText('video:new', { selector: '.entity-label' })).toBeInTheDocument();
    });
  });

  // NOTE: Edit exclusion functionality was removed from the UI - reason is now immutable after creation

  it('displays "View Details" button for each exclusion', async () => {
    await renderView();

    await waitFor(() => {
      const viewDetailsButtons = screen.getAllByRole('button', { name: /view details/i });
      expect(viewDetailsButtons.length).toBeGreaterThan(0);
    });
  });

  it('opens ChannelDetailModal when clicking "View Details" for a channel exclusion', async () => {
    const fetchMock = fetchExclusionsPage as unknown as vi.Mock;
    fetchMock.mockResolvedValueOnce(
      createPage([
        {
          id: 'exclusion-1',
          parentType: 'CHANNEL',
          parentId: 'UCxxxxxx',
          excludeType: 'PLAYLIST',
          excludeId: 'playlist:test',
          reason: 'Test',
          createdAt: '2025-09-20T08:30:00Z',
          createdBy: baseUser
        }
      ])
    );

    await renderView();

    const viewDetailsButton = await screen.findByRole('button', { name: /view details/i });
    await fireEvent.click(viewDetailsButton);

    // Modal should be rendered (checking for modal presence)
    // Note: Actual modal testing is in ChannelDetailModal.spec.ts
    await waitFor(() => {
      // The modal should be in the DOM when opened
      expect(document.querySelector('[role="dialog"]')).toBeInTheDocument();
    });
  });

  it('opens PlaylistDetailModal when clicking "View Details" for a playlist exclusion', async () => {
    const fetchMock = fetchExclusionsPage as unknown as vi.Mock;
    fetchMock.mockResolvedValueOnce(
      createPage([
        {
          id: 'exclusion-2',
          parentType: 'PLAYLIST',
          parentId: 'PLxxxxxx',
          excludeType: 'VIDEO',
          excludeId: 'video:test',
          reason: 'Test',
          createdAt: '2025-09-25T12:40:00Z',
          createdBy: baseUser
        }
      ])
    );

    await renderView();

    const viewDetailsButton = await screen.findByRole('button', { name: /view details/i });
    await fireEvent.click(viewDetailsButton);

    // Modal should be rendered (checking for modal presence)
    // Note: Actual modal testing is in PlaylistDetailModal.spec.ts
    await waitFor(() => {
      // The modal should be in the DOM when opened
      expect(document.querySelector('[role="dialog"]')).toBeInTheDocument();
    });
  });

  it('refreshes exclusions list after modal is updated', async () => {
    const fetchMock = fetchExclusionsPage as unknown as vi.Mock;
    const initialPage = createPage([
      {
        id: 'exclusion-1',
        parentType: 'CHANNEL',
        parentId: 'UCxxxxxx',
        excludeType: 'VIDEO',
        excludeId: 'video:test',
        reason: 'Test',
        createdAt: '2025-09-20T08:30:00Z',
        createdBy: baseUser
      }
    ]);

    const updatedPage = createPage([
      {
        id: 'exclusion-1',
        parentType: 'CHANNEL',
        parentId: 'UCxxxxxx',
        excludeType: 'VIDEO',
        excludeId: 'video:updated',
        reason: 'Updated',
        createdAt: '2025-09-20T08:30:00Z',
        createdBy: baseUser
      }
    ]);

    // Mock initial load
    fetchMock.mockResolvedValueOnce(initialPage);
    // Mock second load after modal update
    fetchMock.mockResolvedValueOnce(updatedPage);

    await renderView();

    // Wait for initial load
    await screen.findByText('video:test', { selector: '.entity-label' });

    // Verify initial fetch was called once
    expect(fetchMock).toHaveBeenCalledTimes(1);

    // Click "View Details" to open modal
    const viewDetailsButton = await screen.findByRole('button', { name: /view details/i });
    await fireEvent.click(viewDetailsButton);

    // Wait for modal to appear
    const modal = await screen.findByTestId('channel-modal');
    expect(modal).toBeInTheDocument();

    // Find and click the trigger update button in the mock modal
    const triggerButton = await screen.findByTestId('trigger-update');
    await fireEvent.click(triggerButton);

    // Wait for the second fetch to complete
    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledTimes(2);
    });

    // Verify updated data appears
    await waitFor(() => {
      expect(screen.getByText('video:updated', { selector: '.entity-label' })).toBeInTheDocument();
    });
  });
});
