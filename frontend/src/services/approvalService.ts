/**
 * Approval Service
 * Backend API integration for content approval workflow
 * Uses /api/admin/approvals endpoints (BACKEND-APPR-01)
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
 * Get pending approvals using the proper approval endpoint
 */
export async function getPendingApprovals(filters?: {
  type?: 'all' | 'channels' | 'playlists' | 'videos';
  category?: string;
  sort?: 'oldest' | 'newest';
}): Promise<PendingApproval[]> {
  // Map frontend filter type to backend type param
  let typeParam: string | undefined;
  if (filters?.type === 'channels') typeParam = 'CHANNEL';
  else if (filters?.type === 'playlists') typeParam = 'PLAYLIST';
  else if (filters?.type === 'videos') typeParam = 'VIDEO';

  const params: any = {};
  if (typeParam) params.type = typeParam;
  if (filters?.category) params.category = filters.category;
  params.limit = 100; // Get all for now

  const response = await apiClient.get('/api/admin/approvals/pending', { params });
  const data = response.data.data || response.data; // Handle both CursorPageDto and direct array

  const approvals: PendingApproval[] = data.map((item: any) => ({
    id: item.id,
    type: item.type?.toLowerCase() || 'channel',
    title: item.title || item.name || '',
    description: item.description || '',
    thumbnailUrl: item.thumbnailUrl || '',
    channelTitle: item.channelTitle || item.channelName || '',
    subscriberCount: item.subscriberCount || item.subscribers || 0,
    videoCount: item.videoCount || item.itemCount || 0,
    categories: item.categoryIds || item.categories || [],
    submittedAt: item.submittedAt || item.createdAt || new Date().toISOString(),
    submittedBy: item.submittedBy || ''
  }));

  // Apply sorting
  if (filters?.sort === 'newest') {
    approvals.sort((a, b) => new Date(b.submittedAt).getTime() - new Date(a.submittedAt).getTime());
  } else {
    approvals.sort((a, b) => new Date(a.submittedAt).getTime() - new Date(b.submittedAt).getTime());
  }

  return approvals;
}

/**
 * Approve an item using the proper approval endpoint
 */
export async function approveItem(
  itemId: string,
  itemType: 'channel' | 'playlist' | 'video',
  categoryOverride?: string,
  reviewNotes?: string
): Promise<void> {
  const payload: any = {};
  if (reviewNotes) payload.reviewNotes = reviewNotes;
  if (categoryOverride) payload.categoryOverride = categoryOverride;

  await apiClient.post(`/api/admin/approvals/${itemId}/approve`, payload);
  toast.success(`${itemType.charAt(0).toUpperCase() + itemType.slice(1)} approved successfully`);
}

/**
 * Reject an item using the proper approval endpoint
 */
export async function rejectItem(
  itemId: string,
  itemType: 'channel' | 'playlist' | 'video',
  reason: string,
  reviewNotes?: string
): Promise<void> {
  const payload: any = {
    reason: reason || 'OTHER'
  };
  if (reviewNotes) payload.reviewNotes = reviewNotes;

  await apiClient.post(`/api/admin/approvals/${itemId}/reject`, payload);
  toast.success(`${itemType.charAt(0).toUpperCase() + itemType.slice(1)} rejected`);
}
