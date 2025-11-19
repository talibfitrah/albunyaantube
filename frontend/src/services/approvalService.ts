/**
 * Approval Service
 * Pure IO layer for content approval API calls
 */

import apiClient from './api/client';
import type { PendingApprovalDto, ApprovalRequestDto, RejectionRequestDto } from '@/types/api';
import type { CursorPage } from '@/types/pagination';

export type { PendingApproval } from '@/utils/approvalTransformers';

export interface ApprovalFilters {
  type?: 'all' | 'channels' | 'playlists' | 'videos';
  category?: string;
  limit?: number;
}

/**
 * Fetch pending approvals from API (raw DTOs)
 */
export async function fetchPendingApprovals(
  filters?: ApprovalFilters
): Promise<CursorPage<PendingApprovalDto>> {
  // Map frontend filter type to backend type param
  let typeParam: string | undefined;
  if (filters?.type === 'channels') typeParam = 'CHANNEL';
  else if (filters?.type === 'playlists') typeParam = 'PLAYLIST';
  else if (filters?.type === 'videos') typeParam = 'VIDEO';

  const params: Record<string, string | number> = {};
  if (typeParam) params.type = typeParam;
  if (filters?.category) params.category = filters.category;
  params.limit = filters?.limit || 100;

  const response = await apiClient.get<CursorPage<PendingApprovalDto>>(
    '/api/admin/approvals/pending',
    { params }
  );
  return response.data;
}

/**
 * Approve an item
 */
export async function approveItem(
  itemId: string,
  categoryOverride?: string,
  reviewNotes?: string
): Promise<void> {
  const payload: ApprovalRequestDto = {
    reviewNotes,
    categoryOverride
  };
  await apiClient.post(`/api/admin/approvals/${itemId}/approve`, payload);
}

/**
 * Reject an item
 */
export async function rejectItem(
  itemId: string,
  reason: string
): Promise<void> {
  const payload: RejectionRequestDto = {
    reason: reason || 'OTHER'
  };
  await apiClient.post(`/api/admin/approvals/${itemId}/reject`, payload);
}
