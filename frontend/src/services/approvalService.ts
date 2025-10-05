/**
 * Approval Service
 * Real backend API integration for content approval workflow
 *
 * NOTE: This currently uses /api/admin/registry endpoints with status=PENDING
 * Once BACKEND-APPR-01 is complete, update to use /api/admin/approvals endpoints
 */

import apiClient from './api/client';
import { toast } from '@/utils/toast';

export interface PendingApproval {
  id: string;
  type: 'channel' | 'playlist' | 'video';
  title: string;
  description: string;
  thumbnailUrl: string;
  channelTitle?: string;
  subscriberCount?: number;
  videoCount?: number;
  categories: string[];
  submittedAt: string;
  submittedBy: string;
}

/**
 * Get pending approvals (channels and playlists with PENDING status)
 */
export async function getPendingApprovals(filters?: {
  type?: 'all' | 'channels' | 'playlists' | 'videos';
  category?: string;
  sort?: 'oldest' | 'newest';
}): Promise<PendingApproval[]> {
  const approvals: PendingApproval[] = [];

  // Fetch pending channels if needed
  if (!filters?.type || filters.type === 'all' || filters.type === 'channels') {
    const channelsResponse = await apiClient.get('/api/admin/registry/channels/status/PENDING');
    const channels = channelsResponse.data;

    approvals.push(...channels.map((channel: any) => ({
      id: channel.id,
      type: 'channel' as const,
      title: channel.name || '',
      description: channel.description || '',
      thumbnailUrl: channel.thumbnailUrl || '',
      subscriberCount: channel.subscribers || 0,
      videoCount: channel.videoCount || 0,
      categories: channel.categoryIds || [],
      submittedAt: channel.createdAt || new Date().toISOString(),
      submittedBy: channel.submittedBy || ''
    })));
  }

  // Fetch pending playlists if needed
  if (!filters?.type || filters.type === 'all' || filters.type === 'playlists') {
    const playlistsResponse = await apiClient.get('/api/admin/registry/playlists/status/PENDING');
    const playlists = playlistsResponse.data;

    approvals.push(...playlists.map((playlist: any) => ({
      id: playlist.id,
      type: 'playlist' as const,
      title: playlist.title || '',
      description: playlist.description || '',
      thumbnailUrl: playlist.thumbnailUrl || '',
      channelTitle: playlist.channelName || '',
      videoCount: playlist.itemCount || 0,
      categories: playlist.categoryIds || [],
      submittedAt: playlist.createdAt || new Date().toISOString(),
      submittedBy: playlist.submittedBy || ''
    })));
  }

  // Apply category filter
  if (filters?.category) {
    return approvals.filter(item => item.categories.includes(filters.category!));
  }

  // Apply sorting
  if (filters?.sort === 'newest') {
    approvals.sort((a, b) => new Date(b.submittedAt).getTime() - new Date(a.submittedAt).getTime());
  } else {
    approvals.sort((a, b) => new Date(a.submittedAt).getTime() - new Date(b.submittedAt).getTime());
  }

  return approvals;
}

/**
 * Approve an item (channel or playlist)
 * TODO: Replace with /api/admin/approvals/{id}/approve when BACKEND-APPR-01 is ready
 */
export async function approveItem(itemId: string, itemType: 'channel' | 'playlist' | 'video'): Promise<void> {
  const endpoint = itemType === 'channel'
    ? `/api/admin/registry/channels/${itemId}/toggle`
    : `/api/admin/registry/playlists/${itemId}/toggle`;

  await apiClient.patch(endpoint);
  toast.success(`${itemType.charAt(0).toUpperCase() + itemType.slice(1)} approved successfully`);
}

/**
 * Reject an item (channel or playlist)
 * TODO: Replace with /api/admin/approvals/{id}/reject when BACKEND-APPR-01 is ready
 */
export async function rejectItem(itemId: string, itemType: 'channel' | 'playlist' | 'video', reason: string): Promise<void> {
  // For now, we'll use delete endpoint as rejection
  // Once BACKEND-APPR-01 is ready, use: POST /api/admin/approvals/{id}/reject
  const endpoint = itemType === 'channel'
    ? `/api/admin/registry/channels/${itemId}`
    : `/api/admin/registry/playlists/${itemId}`;

  await apiClient.delete(endpoint);
  toast.success(`${itemType.charAt(0).toUpperCase() + itemType.slice(1)} rejected`);
  console.log(`Rejection reason: ${reason}`);
}
