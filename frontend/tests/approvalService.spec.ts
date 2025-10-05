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
    it('should fetch pending channels and playlists', async () => {
      const mockChannels = [
        {
          id: 'ch-1',
          name: 'Test Channel',
          description: 'Channel description',
          thumbnailUrl: 'https://example.com/thumb.jpg',
          subscribers: 1000,
          videoCount: 50,
          categoryIds: ['cat-1'],
          createdAt: '2024-01-01T00:00:00Z',
          submittedBy: 'user@example.com'
        }
      ];

      const mockPlaylists = [
        {
          id: 'pl-1',
          title: 'Test Playlist',
          description: 'Playlist description',
          thumbnailUrl: 'https://example.com/thumb.jpg',
          itemCount: 10,
          categoryIds: ['cat-1'],
          createdAt: '2024-01-01T00:00:00Z',
          submittedBy: 'user@example.com'
        }
      ];

      vi.mocked(apiClient.get)
        .mockResolvedValueOnce({ data: mockChannels })
        .mockResolvedValueOnce({ data: mockPlaylists });

      const result = await getPendingApprovals();

      expect(apiClient.get).toHaveBeenCalledWith('/api/admin/registry/channels/status/PENDING');
      expect(apiClient.get).toHaveBeenCalledWith('/api/admin/registry/playlists/status/PENDING');
      expect(result).toHaveLength(2);
      expect(result[0].type).toBe('channel');
      expect(result[1].type).toBe('playlist');
    });

    it('should filter by type: channels only', async () => {
      const mockChannels = [{ id: 'ch-1', name: 'Test' }];

      vi.mocked(apiClient.get).mockResolvedValueOnce({ data: mockChannels });

      const result = await getPendingApprovals({ type: 'channels' });

      expect(apiClient.get).toHaveBeenCalledTimes(1);
      expect(apiClient.get).toHaveBeenCalledWith('/api/admin/registry/channels/status/PENDING');
    });

    it('should filter by type: playlists only', async () => {
      const mockPlaylists = [{ id: 'pl-1', title: 'Test' }];

      vi.mocked(apiClient.get).mockResolvedValueOnce({ data: mockPlaylists });

      const result = await getPendingApprovals({ type: 'playlists' });

      expect(apiClient.get).toHaveBeenCalledTimes(1);
      expect(apiClient.get).toHaveBeenCalledWith('/api/admin/registry/playlists/status/PENDING');
    });

    it('should filter by category', async () => {
      const mockChannels = [
        { id: 'ch-1', categoryIds: ['cat-1'] },
        { id: 'ch-2', categoryIds: ['cat-2'] }
      ];

      vi.mocked(apiClient.get)
        .mockResolvedValueOnce({ data: mockChannels })
        .mockResolvedValueOnce({ data: [] });

      const result = await getPendingApprovals({ category: 'cat-1' });

      expect(result).toHaveLength(1);
      expect(result[0].id).toBe('ch-1');
    });

    it('should sort by newest first', async () => {
      const mockChannels = [
        { id: 'ch-1', createdAt: '2024-01-01T00:00:00Z' },
        { id: 'ch-2', createdAt: '2024-01-02T00:00:00Z' }
      ];

      vi.mocked(apiClient.get)
        .mockResolvedValueOnce({ data: mockChannels })
        .mockResolvedValueOnce({ data: [] });

      const result = await getPendingApprovals({ sort: 'newest' });

      expect(result[0].id).toBe('ch-2');
      expect(result[1].id).toBe('ch-1');
    });
  });

  describe('approveItem', () => {
    it('should approve a channel', async () => {
      vi.mocked(apiClient.patch).mockResolvedValueOnce({ data: {} });

      await approveItem('ch-1', 'channel');

      expect(apiClient.patch).toHaveBeenCalledWith('/api/admin/registry/channels/ch-1/toggle');
    });

    it('should approve a playlist', async () => {
      vi.mocked(apiClient.patch).mockResolvedValueOnce({ data: {} });

      await approveItem('pl-1', 'playlist');

      expect(apiClient.patch).toHaveBeenCalledWith('/api/admin/registry/playlists/pl-1/toggle');
    });
  });

  describe('rejectItem', () => {
    it('should reject a channel with reason', async () => {
      vi.mocked(apiClient.delete).mockResolvedValueOnce({ data: {} });

      await rejectItem('ch-1', 'channel', 'Inappropriate content');

      expect(apiClient.delete).toHaveBeenCalledWith('/api/admin/registry/channels/ch-1');
    });

    it('should reject a playlist', async () => {
      vi.mocked(apiClient.delete).mockResolvedValueOnce({ data: {} });

      await rejectItem('pl-1', 'playlist', 'Low quality');

      expect(apiClient.delete).toHaveBeenCalledWith('/api/admin/registry/playlists/pl-1');
    });
  });
});
