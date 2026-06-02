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
