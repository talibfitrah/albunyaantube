import '@testing-library/jest-dom';
import { render, screen, fireEvent, waitFor } from '@testing-library/vue';
import { createI18n } from 'vue-i18n';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import ExclusionsWorkspaceView from '@/views/ExclusionsWorkspaceView.vue';
import { messages } from '@/locales/messages';
import {
  fetchExclusionsPage,
  createExclusion,
  deleteExclusion,
  updateExclusion
} from '@/services/exclusions';
import type { Exclusion, ExclusionPage } from '@/types/exclusions';

vi.mock('@/services/exclusions', () => ({
  fetchExclusionsPage: vi.fn(),
  createExclusion: vi.fn(),
  deleteExclusion: vi.fn(),
  updateExclusion: vi.fn()
}));

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  messages
});

function renderView() {
  return render(ExclusionsWorkspaceView, {
    global: {
      plugins: [i18n]
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
    (deleteExclusion as unknown as vi.Mock).mockReset();
    (updateExclusion as unknown as vi.Mock).mockReset();
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

    renderView();

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

    renderView();

    const checkbox = await screen.findByRole('checkbox', {
      name: /select playlist:alqalam-foundation for bulk action/i
    });
    await fireEvent.click(checkbox);

    expect(await screen.findByText('1 selected')).toBeInTheDocument();

    const bulkRemove = screen.getByRole('button', { name: /remove selected/i });
    (deleteExclusion as unknown as vi.Mock).mockResolvedValue(undefined);
    await fireEvent.click(bulkRemove);

    await waitFor(() => {
      expect(deleteExclusion).toHaveBeenCalledWith('exclusion-1');
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
      reason: 'Manual QA hold',
      createdAt: '2025-10-01T10:00:00Z',
      createdBy: baseUser
    };
    fetchMock.mockResolvedValueOnce(createPage([response]));

    renderView();

    const trigger = await screen.findByRole('button', { name: /add exclusion/i });
    await fireEvent.click(trigger);

    const parentIdField = await screen.findByLabelText(/parent id/i);
    const excludedIdField = screen.getByLabelText(/excluded id/i);
    const reasonField = screen.getByLabelText(/reason/i);

    await fireEvent.update(parentIdField, 'channel:new-parent');
    const typeSelect = screen.getByLabelText(/excluded type/i);
    await fireEvent.update(typeSelect, 'VIDEO');
    await fireEvent.update(excludedIdField, 'video:new');
    await fireEvent.update(reasonField, 'Manual QA hold');

    (createExclusion as unknown as vi.Mock).mockResolvedValue(response);

    const submit = screen.getByRole('button', { name: /create exclusion/i });
    await fireEvent.click(submit);

    await waitFor(() => {
      expect(createExclusion).toHaveBeenCalledWith({
        parentType: 'CHANNEL',
        parentId: 'channel:new-parent',
        excludeType: 'VIDEO',
        excludeId: 'video:new',
        reason: 'Manual QA hold'
      });
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
      expect(screen.getByText('video:new', { selector: '.entity-label' })).toBeInTheDocument();
    });
  });

  it('updates an exclusion reason', async () => {
    const fetchMock = fetchExclusionsPage as unknown as vi.Mock;
    const original: Exclusion = {
      id: 'exclusion-1',
      parentType: 'CHANNEL',
      parentId: 'channel:alqalam',
      excludeType: 'PLAYLIST',
      excludeId: 'playlist:alqalam-foundation',
      reason: 'Manual removal pending QA',
      createdAt: '2025-09-20T08:30:00Z',
      createdBy: baseUser
    };
    fetchMock.mockResolvedValueOnce(createPage([original]));
    const updated: Exclusion = { ...original, reason: 'Updated note', createdAt: '2025-10-02T10:00:00Z' };
    fetchMock.mockResolvedValueOnce(createPage([updated]));

    renderView();

    const editButton = await screen.findByRole('button', { name: /edit/i });
    await fireEvent.click(editButton);

    const reasonField = screen.getByLabelText(/reason/i);
    await fireEvent.update(reasonField, 'Updated note');

    (updateExclusion as unknown as vi.Mock).mockResolvedValue(updated);

    const submit = screen.getByRole('button', { name: /update exclusion/i });
    await fireEvent.click(submit);

    await waitFor(() => {
      expect(updateExclusion).toHaveBeenCalledWith('exclusion-1', { reason: 'Updated note' });
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
      expect(screen.getByRole('status')).toHaveTextContent('Exclusion updated.');
    });
  });
});
