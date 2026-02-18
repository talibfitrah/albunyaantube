import { describe, it, expect, vi, beforeEach } from 'vitest';
import { getPendingApprovals, getMySubmissions, approveItem, rejectItem } from '@/services/approvalService';
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

      vi.mocked(apiClient.get).mockResolvedValueOnce({
        data: {
          data: mockApprovals,
          pageInfo: { nextCursor: null, hasNext: false }
        }
      });

      const result = await getPendingApprovals();

      expect(apiClient.get).toHaveBeenCalledWith('/api/admin/approvals/pending', {
        params: { limit: 20 }
      });
      expect(result.items).toHaveLength(2);
      expect(result.items[0].type).toBe('channel');
      expect(result.items[1].type).toBe('playlist');
      expect(result.nextCursor).toBeNull();
    });

    it('should filter by type: channels only', async () => {
      vi.mocked(apiClient.get).mockResolvedValueOnce({
        data: { data: [], pageInfo: { nextCursor: null, hasNext: false } }
      });

      await getPendingApprovals({ type: 'channels' });

      expect(apiClient.get).toHaveBeenCalledWith('/api/admin/approvals/pending', {
        params: { limit: 20, type: 'CHANNEL' }
      });
    });

    it('should filter by type: playlists only', async () => {
      vi.mocked(apiClient.get).mockResolvedValueOnce({
        data: { data: [], pageInfo: { nextCursor: null, hasNext: false } }
      });

      await getPendingApprovals({ type: 'playlists' });

      expect(apiClient.get).toHaveBeenCalledWith('/api/admin/approvals/pending', {
        params: { limit: 20, type: 'PLAYLIST' }
      });
    });

    it('should pass the category filter to the API', async () => {
      vi.mocked(apiClient.get).mockResolvedValueOnce({
        data: { data: [], pageInfo: { nextCursor: null, hasNext: false } }
      });

      await getPendingApprovals({ category: 'cat-1' });

      expect(apiClient.get).toHaveBeenCalledWith('/api/admin/approvals/pending', {
        params: { limit: 20, category: 'cat-1' }
      });
    });

    it('should return nextCursor for pagination', async () => {
      vi.mocked(apiClient.get).mockResolvedValueOnce({
        data: {
          data: [{ id: 'ch-1', type: 'CHANNEL', title: 'Channel 1', submittedAt: '2024-01-01T00:00:00Z', submittedBy: 'user1' }],
          pageInfo: { nextCursor: 'cursor_abc', hasNext: true }
        }
      });

      const result = await getPendingApprovals();

      expect(result.nextCursor).toBe('cursor_abc');
      expect(result.items).toHaveLength(1);
    });

    it('should pass cursor for next page', async () => {
      vi.mocked(apiClient.get).mockResolvedValueOnce({
        data: { data: [], pageInfo: { nextCursor: null, hasNext: false } }
      });

      await getPendingApprovals({ cursor: 'cursor_abc' });

      expect(apiClient.get).toHaveBeenCalledWith('/api/admin/approvals/pending', {
        params: { limit: 20, cursor: 'cursor_abc' }
      });
    });
  });

  describe('getMySubmissions', () => {
    it('should fetch submissions with status filter', async () => {
      const mockSubmissions = [
        {
          id: 'ch-1',
          type: 'CHANNEL',
          title: 'My Channel',
          submittedAt: '2024-01-01T00:00:00Z',
          submittedBy: 'mod@example.com',
          status: 'PENDING'
        }
      ];

      vi.mocked(apiClient.get).mockResolvedValueOnce({
        data: {
          data: mockSubmissions,
          pageInfo: { nextCursor: null, hasNext: false }
        }
      });

      const result = await getMySubmissions({ status: 'PENDING' });

      expect(apiClient.get).toHaveBeenCalledWith('/api/admin/approvals/my-submissions', {
        params: { status: 'PENDING', limit: 20 }
      });
      expect(result.items).toHaveLength(1);
      expect(result.items[0].status).toBe('PENDING');
    });

    it('should filter by VIDEO type', async () => {
      vi.mocked(apiClient.get).mockResolvedValueOnce({
        data: { data: [], pageInfo: { nextCursor: null, hasNext: false } }
      });

      await getMySubmissions({ type: 'videos' });

      expect(apiClient.get).toHaveBeenCalledWith('/api/admin/approvals/my-submissions', {
        params: { type: 'VIDEO', limit: 20 }
      });
    });

    it('should pass cursor for pagination', async () => {
      vi.mocked(apiClient.get).mockResolvedValueOnce({
        data: { data: [], pageInfo: { nextCursor: null, hasNext: false } }
      });

      await getMySubmissions({ cursor: 'cursor_xyz' });

      expect(apiClient.get).toHaveBeenCalledWith('/api/admin/approvals/my-submissions', {
        params: { limit: 20, cursor: 'cursor_xyz' }
      });
    });

    it('should map status, rejectionReason, and reviewNotes from DTO', async () => {
      const mockSubmission = {
        id: 'ch-1',
        type: 'CHANNEL',
        title: 'Rejected Channel',
        submittedAt: '2024-01-01T00:00:00Z',
        submittedBy: 'mod@example.com',
        status: 'REJECTED',
        rejectionReason: 'LOW_QUALITY',
        reviewNotes: 'Not enough content'
      };

      vi.mocked(apiClient.get).mockResolvedValueOnce({
        data: {
          data: [mockSubmission],
          pageInfo: { nextCursor: null, hasNext: false }
        }
      });

      const result = await getMySubmissions({ status: 'REJECTED' });

      expect(result.items[0].status).toBe('REJECTED');
      expect(result.items[0].rejectionReason).toBe('LOW_QUALITY');
      expect(result.items[0].reviewNotes).toBe('Not enough content');
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
