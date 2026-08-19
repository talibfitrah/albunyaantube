/**
 * [ADMIN-IMPORT-01] approvalService — source field mapping
 *
 * Verifies that the `source` field from PendingApprovalDto is surfaced in the
 * UI model returned by getPendingApprovals / getMySubmissions.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { getPendingApprovals, getMySubmissions } from '@/services/approvalService';
import apiClient from '@/services/api/client';

vi.mock('@/services/api/client');
vi.mock('@/utils/toast');

describe('approvalService — source field mapping', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('maps source=USER_IMPORT from the DTO to the UI model', async () => {
    const dto = {
      id: 'ch-import-1',
      type: 'CHANNEL',
      title: 'Imported Channel',
      submittedAt: '2024-01-01T00:00:00Z',
      submittedBy: 'user@example.com',
      source: 'USER_IMPORT'
    };

    vi.mocked(apiClient.get).mockResolvedValueOnce({
      data: {
        data: [dto],
        pageInfo: { nextCursor: null, hasNext: false }
      }
    });

    const result = await getPendingApprovals();

    expect(result.items).toHaveLength(1);
    expect(result.items[0].source).toBe('USER_IMPORT');
  });

  it('returns undefined source for a legacy item (no source in DTO)', async () => {
    const dto = {
      id: 'ch-legacy-1',
      type: 'CHANNEL',
      title: 'Legacy Channel',
      submittedAt: '2024-01-01T00:00:00Z',
      submittedBy: 'admin@example.com'
      // no source field
    };

    vi.mocked(apiClient.get).mockResolvedValueOnce({
      data: {
        data: [dto],
        pageInfo: { nextCursor: null, hasNext: false }
      }
    });

    const result = await getPendingApprovals();

    expect(result.items).toHaveLength(1);
    expect(result.items[0].source).toBeUndefined();
  });

  it('maps source for PLAYLIST and VIDEO types', async () => {
    const dtos = [
      {
        id: 'pl-1',
        type: 'PLAYLIST',
        title: 'Imported Playlist',
        submittedAt: '2024-01-01T00:00:00Z',
        submittedBy: 'user@example.com',
        source: 'USER_IMPORT'
      },
      {
        id: 'vid-1',
        type: 'VIDEO',
        title: 'Imported Video',
        submittedAt: '2024-01-01T00:00:00Z',
        submittedBy: 'user@example.com',
        source: 'USER_IMPORT'
      }
    ];

    vi.mocked(apiClient.get).mockResolvedValueOnce({
      data: {
        data: dtos,
        pageInfo: { nextCursor: null, hasNext: false }
      }
    });

    const result = await getPendingApprovals();

    expect(result.items[0].source).toBe('USER_IMPORT');
    expect(result.items[1].source).toBe('USER_IMPORT');
  });

  it('maps source through getMySubmissions as well', async () => {
    const dto = {
      id: 'ch-my-1',
      type: 'CHANNEL',
      title: 'My Imported Channel',
      submittedAt: '2024-01-01T00:00:00Z',
      submittedBy: 'user@example.com',
      status: 'PENDING',
      source: 'USER_IMPORT'
    };

    vi.mocked(apiClient.get).mockResolvedValueOnce({
      data: {
        data: [dto],
        pageInfo: { nextCursor: null, hasNext: false }
      }
    });

    const result = await getMySubmissions({ status: 'PENDING' });

    expect(result.items).toHaveLength(1);
    expect(result.items[0].source).toBe('USER_IMPORT');
  });
});

/**
 * Field complaint: the queue showed a raw Firebase uid under "Submitted by", which tells an
 * admin nothing about who sent the item. The backend already resolves the submitter's name and
 * email onto the DTO; the mapper was dropping both.
 */
describe('approvalService — submitter label', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  async function mapOne(dto: Record<string, unknown>) {
    vi.mocked(apiClient.get).mockResolvedValueOnce({
      data: { data: [dto], pageInfo: { nextCursor: null, hasNext: false } }
    });
    const result = await getPendingApprovals();
    return result.items[0];
  }

  const base = {
    id: 'ch-1',
    type: 'CHANNEL',
    title: 'A Channel',
    submittedAt: '2024-01-01T00:00:00Z',
    submittedBy: 'jJ8M9PGkAyNAvijwVZu6hDCAe933'
  };

  it('prefers the submitter display name', async () => {
    const item = await mapOne({ ...base, submittedByDisplayName: 'Ahmed', submittedByEmail: 'a@example.com' });
    expect(item.submittedByLabel).toBe('Ahmed');
  });

  it('falls back to the submitter email when there is no display name', async () => {
    const item = await mapOne({ ...base, submittedByEmail: 'a@example.com' });
    expect(item.submittedByLabel).toBe('a@example.com');
  });

  it('falls back to the uid when the backend resolved neither', async () => {
    const item = await mapOne(base);
    expect(item.submittedByLabel).toBe('jJ8M9PGkAyNAvijwVZu6hDCAe933');
  });

  it('keeps the raw uid available for filtering by submitter', async () => {
    const item = await mapOne({ ...base, submittedByDisplayName: 'Ahmed' });
    expect(item.submittedBy).toBe('jJ8M9PGkAyNAvijwVZu6hDCAe933');
  });
});
