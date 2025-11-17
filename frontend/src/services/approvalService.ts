/**
 * Approval Service
 * Backend API integration for content approval workflow
 * Uses /api/admin/approvals endpoints (BACKEND-APPR-01)
 */

import apiClient from './api/client';
import { toast } from '@/utils/toast';
import type { PendingApprovalDto, ApprovalRequestDto, RejectionRequestDto } from '@/types/api';

// UI-specific domain model (extends API DTO with UI-specific fields)
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
 * Map API PendingApprovalDto to UI domain model
 */
function mapPendingApprovalToUi(dto: PendingApprovalDto): PendingApproval {
  const metadata = dto.metadata || {};

  // Helper to safely extract metadata values with type assertions
  const getString = (key: string): string => (metadata[key] as string | undefined) || '';
  const getNumber = (key: string): number => (metadata[key] as number | undefined) || 0;
  const getArray = (key: string): string[] => (metadata[key] as string[] | undefined) || [];

  // Check if categoryIds key exists in metadata, otherwise fall back to categories
  // This preserves empty arrays when categoryIds exists but is empty
  const categories = ('categoryIds' in metadata)
    ? getArray('categoryIds')
    : getArray('categories');

  return {
    id: dto.id || '',
    type: (dto.type?.toLowerCase() as 'channel' | 'playlist' | 'video') || 'channel',
    title: dto.title || '',
    description: getString('description'),
    thumbnailUrl: getString('thumbnailUrl'),
    channelTitle: getString('channelTitle') || getString('channelName'),
    subscriberCount: getNumber('subscriberCount') || getNumber('subscribers'),
    videoCount: getNumber('videoCount') || getNumber('itemCount'),
    categories,
    submittedAt: dto.submittedAt || new Date().toISOString(),
    submittedBy: dto.submittedBy || ''
  };
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

  const params: Record<string, string | number> = {};
  if (typeParam) params.type = typeParam;
  if (filters?.category) params.category = filters.category;
  params.limit = 100; // Get all for now

  const response = await apiClient.get<PendingApprovalDto[]>('/api/admin/approvals/pending', { params });
  const data = response.data;

  // Map API DTOs to UI domain models
  const approvals: PendingApproval[] = data.map(mapPendingApprovalToUi);

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
  const payload: ApprovalRequestDto = {
    reviewNotes,
    categoryOverride
  };

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
  // Note: reviewNotes param kept for backward compatibility but not in RejectionRequestDto schema
  const payload: RejectionRequestDto = {
    reason: reason || 'OTHER'
  };

  await apiClient.post(`/api/admin/approvals/${itemId}/reject`, payload);
  toast.success(`${itemType.charAt(0).toUpperCase() + itemType.slice(1)} rejected`);
}
