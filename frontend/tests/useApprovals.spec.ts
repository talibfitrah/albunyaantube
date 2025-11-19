import { describe, it, expect, vi, beforeEach } from 'vitest';
import { useApprovals } from '@/composables/useApprovals';
import * as approvalService from '@/services/approvalService';
import { toast } from '@/utils/toast';

vi.mock('@/services/approvalService');
vi.mock('@/utils/toast');

describe('useApprovals', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  describe('initial state', () => {
    it('should have default filter values', () => {
      const { contentType, categoryFilter, sortFilter } = useApprovals();

      expect(contentType.value).toBe('all');
      expect(categoryFilter.value).toBe('');
      expect(sortFilter.value).toBe('oldest');
    });

    it('should accept initial filter values', () => {
      const { contentType, categoryFilter, sortFilter } = useApprovals({
        type: 'channels',
        category: 'cat-1',
        sort: 'newest'
      });

      expect(contentType.value).toBe('channels');
      expect(categoryFilter.value).toBe('cat-1');
      expect(sortFilter.value).toBe('newest');
    });

    it('should start with empty approvals', () => {
      const { approvals, totalPending } = useApprovals();

      expect(approvals.value).toEqual([]);
      expect(totalPending.value).toBe(0);
    });
  });

  describe('loadApprovals', () => {
    it('should fetch and transform approvals', async () => {
      const mockDtos = [
        {
          id: 'ch-1',
          type: 'CHANNEL',
          title: 'Test Channel',
          submittedAt: '2024-01-01T00:00:00Z',
          submittedBy: 'admin@example.com',
          category: 'Islamic',
          metadata: {}
        }
      ];

      vi.mocked(approvalService.fetchPendingApprovals).mockResolvedValueOnce({
        data: mockDtos,
        pageInfo: { cursor: null, nextCursor: null, hasNext: false }
      });

      const { loadApprovals, approvals, isLoading } = useApprovals();

      const promise = loadApprovals();
      expect(isLoading.value).toBe(true);

      await promise;

      expect(isLoading.value).toBe(false);
      expect(approvals.value).toHaveLength(1);
      expect(approvals.value[0].id).toBe('ch-1');
      expect(approvals.value[0].type).toBe('channel');
      expect(approvals.value[0].categories).toEqual(['Islamic']);
    });

    it('should sort approvals by oldest first by default', async () => {
      const mockDtos = [
        { id: 'ch-2', type: 'CHANNEL', title: 'New', submittedAt: '2024-01-02T00:00:00Z', submittedBy: 'user', metadata: {} },
        { id: 'ch-1', type: 'CHANNEL', title: 'Old', submittedAt: '2024-01-01T00:00:00Z', submittedBy: 'user', metadata: {} }
      ];

      vi.mocked(approvalService.fetchPendingApprovals).mockResolvedValueOnce({
        data: mockDtos,
        pageInfo: { cursor: null, nextCursor: null, hasNext: false }
      });

      const { loadApprovals, approvals } = useApprovals();
      await loadApprovals();

      expect(approvals.value[0].id).toBe('ch-1'); // oldest first
      expect(approvals.value[1].id).toBe('ch-2');
    });

    it('should sort approvals by newest first when filter is set', async () => {
      const mockDtos = [
        { id: 'ch-1', type: 'CHANNEL', title: 'Old', submittedAt: '2024-01-01T00:00:00Z', submittedBy: 'user', metadata: {} },
        { id: 'ch-2', type: 'CHANNEL', title: 'New', submittedAt: '2024-01-02T00:00:00Z', submittedBy: 'user', metadata: {} }
      ];

      vi.mocked(approvalService.fetchPendingApprovals).mockResolvedValueOnce({
        data: mockDtos,
        pageInfo: { cursor: null, nextCursor: null, hasNext: false }
      });

      const { loadApprovals, approvals } = useApprovals({ sort: 'newest' });
      await loadApprovals();

      expect(approvals.value[0].id).toBe('ch-2'); // newest first
      expect(approvals.value[1].id).toBe('ch-1');
    });

    it('should set error on failure', async () => {
      vi.mocked(approvalService.fetchPendingApprovals).mockRejectedValueOnce(
        new Error('Network error')
      );

      const { loadApprovals, error, isLoading } = useApprovals();

      await expect(loadApprovals()).rejects.toThrow();

      expect(error.value).toBe('Network error');
      expect(isLoading.value).toBe(false);
    });
  });

  describe('approve', () => {
    it('should approve an item and reload', async () => {
      // Setup initial load
      vi.mocked(approvalService.fetchPendingApprovals).mockResolvedValue({
        data: [],
        pageInfo: { cursor: null, nextCursor: null, hasNext: false }
      });

      vi.mocked(approvalService.approveItem).mockResolvedValueOnce();

      const { approve, loadApprovals } = useApprovals();
      await loadApprovals();

      const item = { id: 'ch-1', type: 'channel' as const, title: 'Test', description: '', thumbnailUrl: '', categories: [], submittedAt: '', submittedBy: '' };
      await approve(item);

      expect(approvalService.approveItem).toHaveBeenCalledWith('ch-1');
      expect(toast.success).toHaveBeenCalledWith('Channel approved successfully');
    });

    it('should set processingId during approval', async () => {
      vi.mocked(approvalService.fetchPendingApprovals).mockResolvedValue({
        data: [],
        pageInfo: { cursor: null, nextCursor: null, hasNext: false }
      });

      let resolveApprove: () => void;
      vi.mocked(approvalService.approveItem).mockReturnValueOnce(
        new Promise(resolve => { resolveApprove = resolve; })
      );

      const { approve, processingId, loadApprovals } = useApprovals();
      await loadApprovals();

      const item = { id: 'ch-1', type: 'channel' as const, title: 'Test', description: '', thumbnailUrl: '', categories: [], submittedAt: '', submittedBy: '' };
      const promise = approve(item);

      expect(processingId.value).toBe('ch-1');

      resolveApprove!();
      await promise;

      expect(processingId.value).toBe(null);
    });
  });

  describe('reject', () => {
    it('should reject an item with reason and reload', async () => {
      vi.mocked(approvalService.fetchPendingApprovals).mockResolvedValue({
        data: [],
        pageInfo: { cursor: null, nextCursor: null, hasNext: false }
      });

      vi.mocked(approvalService.rejectItem).mockResolvedValueOnce();

      const { reject, loadApprovals } = useApprovals();
      await loadApprovals();

      const item = { id: 'pl-1', type: 'playlist' as const, title: 'Test', description: '', thumbnailUrl: '', categories: [], submittedAt: '', submittedBy: '' };
      await reject(item, 'LOW_QUALITY');

      expect(approvalService.rejectItem).toHaveBeenCalledWith('pl-1', 'LOW_QUALITY');
      expect(toast.success).toHaveBeenCalledWith('Playlist rejected');
    });
  });

  describe('setFilter', () => {
    it('should update content type filter', () => {
      const { contentType, setFilter } = useApprovals();

      setFilter('type', 'videos');

      expect(contentType.value).toBe('videos');
    });

    it('should update category filter', () => {
      const { categoryFilter, setFilter } = useApprovals();

      setFilter('category', 'cat-123');

      expect(categoryFilter.value).toBe('cat-123');
    });

    it('should update sort filter', () => {
      const { sortFilter, setFilter } = useApprovals();

      setFilter('sort', 'newest');

      expect(sortFilter.value).toBe('newest');
    });
  });
});
