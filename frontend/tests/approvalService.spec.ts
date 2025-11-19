import { describe, it, expect, vi, beforeEach } from 'vitest';
import { fetchPendingApprovals, approveItem, rejectItem } from '@/services/approvalService';
import apiClient from '@/services/api/client';

vi.mock('@/services/api/client');

describe('ApprovalService', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  describe('fetchPendingApprovals', () => {
    it('should fetch pending approvals from the canonical endpoint', async () => {
      const mockResponse = {
        data: [
          { id: 'ch-1', type: 'CHANNEL', title: 'Test Channel' },
          { id: 'pl-1', type: 'PLAYLIST', title: 'Test Playlist' }
        ],
        pageInfo: { nextCursor: null, hasNext: false }
      };

      vi.mocked(apiClient.get).mockResolvedValueOnce({ data: mockResponse });

      const result = await fetchPendingApprovals();

      expect(apiClient.get).toHaveBeenCalledWith('/api/admin/approvals/pending', {
        params: { limit: 100 }
      });
      expect(result).toEqual(mockResponse);
    });

    it('should filter by type: channels only', async () => {
      vi.mocked(apiClient.get).mockResolvedValueOnce({
        data: { data: [], pageInfo: { nextCursor: null, hasNext: false } }
      });

      await fetchPendingApprovals({ type: 'channels' });

      expect(apiClient.get).toHaveBeenCalledWith('/api/admin/approvals/pending', {
        params: { limit: 100, type: 'CHANNEL' }
      });
    });

    it('should filter by type: playlists only', async () => {
      vi.mocked(apiClient.get).mockResolvedValueOnce({
        data: { data: [], pageInfo: { nextCursor: null, hasNext: false } }
      });

      await fetchPendingApprovals({ type: 'playlists' });

      expect(apiClient.get).toHaveBeenCalledWith('/api/admin/approvals/pending', {
        params: { limit: 100, type: 'PLAYLIST' }
      });
    });

    it('should filter by type: videos only', async () => {
      vi.mocked(apiClient.get).mockResolvedValueOnce({
        data: { data: [], pageInfo: { nextCursor: null, hasNext: false } }
      });

      await fetchPendingApprovals({ type: 'videos' });

      expect(apiClient.get).toHaveBeenCalledWith('/api/admin/approvals/pending', {
        params: { limit: 100, type: 'VIDEO' }
      });
    });

    it('should pass the category filter to the API', async () => {
      vi.mocked(apiClient.get).mockResolvedValueOnce({
        data: { data: [], pageInfo: { nextCursor: null, hasNext: false } }
      });

      await fetchPendingApprovals({ category: 'cat-1' });

      expect(apiClient.get).toHaveBeenCalledWith('/api/admin/approvals/pending', {
        params: { limit: 100, category: 'cat-1' }
      });
    });

    it('should respect custom limit', async () => {
      vi.mocked(apiClient.get).mockResolvedValueOnce({
        data: { data: [], pageInfo: { nextCursor: null, hasNext: false } }
      });

      await fetchPendingApprovals({ limit: 50 });

      expect(apiClient.get).toHaveBeenCalledWith('/api/admin/approvals/pending', {
        params: { limit: 50 }
      });
    });
  });

  describe('approveItem', () => {
    it('should approve an item with empty payload', async () => {
      vi.mocked(apiClient.post).mockResolvedValueOnce({ data: {} });

      await approveItem('ch-1');

      expect(apiClient.post).toHaveBeenCalledWith('/api/admin/approvals/ch-1/approve', {
        categoryOverride: undefined,
        reviewNotes: undefined
      });
    });

    it('should approve an item with overrides', async () => {
      vi.mocked(apiClient.post).mockResolvedValueOnce({ data: {} });

      await approveItem('pl-1', 'cat-1', 'looks good');

      expect(apiClient.post).toHaveBeenCalledWith('/api/admin/approvals/pl-1/approve', {
        categoryOverride: 'cat-1',
        reviewNotes: 'looks good'
      });
    });
  });

  describe('rejectItem', () => {
    it('should reject an item with reason', async () => {
      vi.mocked(apiClient.post).mockResolvedValueOnce({ data: {} });

      await rejectItem('ch-1', 'INAPPROPRIATE');

      expect(apiClient.post).toHaveBeenCalledWith('/api/admin/approvals/ch-1/reject', {
        reason: 'INAPPROPRIATE'
      });
    });

    it('should default to OTHER when reason is empty', async () => {
      vi.mocked(apiClient.post).mockResolvedValueOnce({ data: {} });

      await rejectItem('pl-1', '');

      expect(apiClient.post).toHaveBeenCalledWith('/api/admin/approvals/pl-1/reject', {
        reason: 'OTHER'
      });
    });
  });
});
