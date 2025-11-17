import { describe, it, expect, vi, beforeEach } from 'vitest';
import { getPendingApprovals, approveItem, rejectItem } from '@/services/approvalService';
import apiClient from '@/services/api/client';

vi.mock('@/services/api/client');
vi.mock('@/utils/toast');

describe('ApprovalService', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  describe('getPendingApprovals', () => {
    it('should fetch pending approvals from the canonical endpoint', async () => {
      const mockApprovals = [
        {
          id: 'ch-1',
          type: 'CHANNEL',
          title: 'Test Channel',
          submittedAt: '2024-01-01T00:00:00Z',
          submittedBy: 'admin@example.com'
        },
        {
          id: 'pl-1',
          type: 'PLAYLIST',
          title: 'Test Playlist',
          submittedAt: '2024-01-02T00:00:00Z',
          submittedBy: 'moderator@example.com'
        }
      ];

      vi.mocked(apiClient.get).mockResolvedValueOnce({ data: mockApprovals });

      const result = await getPendingApprovals();

      expect(apiClient.get).toHaveBeenCalledWith('/api/admin/approvals/pending', {
        params: { limit: 100 }
      });
      expect(result).toHaveLength(2);
      expect(result[0].type).toBe('channel');
      expect(result[1].type).toBe('playlist');
    });

    it('should filter by type: channels only', async () => {
      vi.mocked(apiClient.get).mockResolvedValueOnce({ data: [] });

      await getPendingApprovals({ type: 'channels' });

      expect(apiClient.get).toHaveBeenCalledWith('/api/admin/approvals/pending', {
        params: { limit: 100, type: 'CHANNEL' }
      });
    });

    it('should filter by type: playlists only', async () => {
      vi.mocked(apiClient.get).mockResolvedValueOnce({ data: [] });

      await getPendingApprovals({ type: 'playlists' });

      expect(apiClient.get).toHaveBeenCalledWith('/api/admin/approvals/pending', {
        params: { limit: 100, type: 'PLAYLIST' }
      });
    });

    it('should pass the category filter to the API', async () => {
      vi.mocked(apiClient.get).mockResolvedValueOnce({ data: [] });

      await getPendingApprovals({ category: 'cat-1' });

      expect(apiClient.get).toHaveBeenCalledWith('/api/admin/approvals/pending', {
        params: { limit: 100, category: 'cat-1' }
      });
    });

    it('should sort results by newest first', async () => {
      const mockApprovals = [
        { id: 'ch-1', type: 'CHANNEL', title: 'Channel 1', submittedAt: '2024-01-01T00:00:00Z', submittedBy: 'user1' },
        { id: 'ch-2', type: 'CHANNEL', title: 'Channel 2', submittedAt: '2024-01-02T00:00:00Z', submittedBy: 'user2' }
      ];

      vi.mocked(apiClient.get).mockResolvedValueOnce({ data: mockApprovals });

      const result = await getPendingApprovals({ sort: 'newest' });

      expect(result[0].id).toBe('ch-2');
      expect(result[1].id).toBe('ch-1');
    });
  });

  describe('approveItem', () => {
    it('should approve a channel', async () => {
      vi.mocked(apiClient.post).mockResolvedValueOnce({ data: {} });

      await approveItem('ch-1', 'channel');

      expect(apiClient.post).toHaveBeenCalledWith('/api/admin/approvals/ch-1/approve', {});
    });

    it('should approve a playlist with overrides', async () => {
      vi.mocked(apiClient.post).mockResolvedValueOnce({ data: {} });

      await approveItem('pl-1', 'playlist', 'cat-1', 'looks good');

      expect(apiClient.post).toHaveBeenCalledWith('/api/admin/approvals/pl-1/approve', {
        categoryOverride: 'cat-1',
        reviewNotes: 'looks good'
      });
    });
  });

  describe('rejectItem', () => {
    it('should reject a channel with reason', async () => {
      vi.mocked(apiClient.post).mockResolvedValueOnce({ data: {} });

      await rejectItem('ch-1', 'channel', 'INAPPROPRIATE');

      expect(apiClient.post).toHaveBeenCalledWith('/api/admin/approvals/ch-1/reject', {
        reason: 'INAPPROPRIATE'
      });
    });

    it('should reject a playlist with reason (reviewNotes not in schema)', async () => {
      vi.mocked(apiClient.post).mockResolvedValueOnce({ data: {} });

      // reviewNotes parameter is kept for backward compatibility but not sent to API
      await rejectItem('pl-1', 'playlist', 'LOW_QUALITY', 'Too few videos');

      expect(apiClient.post).toHaveBeenCalledWith('/api/admin/approvals/pl-1/reject', {
        reason: 'LOW_QUALITY'
        // reviewNotes is NOT in RejectionRequestDto schema
      });
    });
  });
});
