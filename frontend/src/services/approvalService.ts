/**
 * Approval Service
 * Backend API integration for content approval workflow
 * Uses /api/admin/approvals endpoints (BACKEND-APPR-01)
 */

import apiClient from './api/client';
import { toast } from '@/utils/toast';
import type { PendingApprovalDto, ApprovalRequestDto, RejectionRequestDto } from '@/types/api';
import type { CursorPage } from '@/types/pagination';

// UI-specific domain model (extends API DTO with UI-specific fields)
export interface PendingApproval {
  id: string;
  type: 'channel' | 'playlist' | 'video';
  youtubeId: string;
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
 * Parse formatted number string (e.g., "1.2K", "3.5M") back to numeric value
 */
function parseFormattedNumber(value: unknown): number {
  if (typeof value === 'number') return value;
  if (typeof value !== 'string') return 0;

  const str = value.trim().toUpperCase();
  if (!str) return 0;

  // Handle K (thousands) and M (millions) suffixes
  const match = str.match(/^([\d.]+)\s*([KM])?$/);
  if (!match) return parseInt(str, 10) || 0;

  const num = parseFloat(match[1]);
  const suffix = match[2];

  if (suffix === 'K') return Math.round(num * 1000);
  if (suffix === 'M') return Math.round(num * 1000000);
  return Math.round(num);
}

/**
 * Map API PendingApprovalDto to UI domain model
 *
 * Backend sends:
 * - dto.category: single category name string
 * - metadata.subscriberCount: formatted string (e.g., "1.2K")
 * - metadata.videoCount: number
 * - metadata.itemCount: number (for playlists)
 */
function mapPendingApprovalToUi(dto: PendingApprovalDto): PendingApproval {
  const metadata = dto.metadata || {};
  const contentType = (dto.type?.toLowerCase() as 'channel' | 'playlist' | 'video') || 'channel';

  // Helper to safely extract metadata values with type assertions
  const getString = (key: string): string => (metadata[key] as string | undefined) || '';
  const getNumber = (key: string): number => (metadata[key] as number | undefined) || 0;

  // Categories come from dto.category (single name), not metadata
  // Backend sets dto.category as the first category name
  const categories = dto.category ? [dto.category] : [];

  // Type-specific field extraction
  let subscriberCount: number | undefined;
  let videoCount: number | undefined;

  if (contentType === 'channel') {
    // Backend sends subscriberCount as formatted string (e.g., "1.2K")
    subscriberCount = parseFormattedNumber(metadata['subscriberCount']);
    videoCount = getNumber('videoCount');
  } else if (contentType === 'playlist') {
    // Playlists use itemCount
    videoCount = getNumber('itemCount');
  }
  // Videos don't have subscriberCount or videoCount metadata

  return {
    id: dto.id || '',
    type: contentType,
    youtubeId: getString('youtubeId'),
    title: dto.title || '',
    description: getString('description'),
    thumbnailUrl: getString('thumbnailUrl'),
    channelTitle: getString('channelTitle'),
    subscriberCount,
    videoCount,
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

  const response = await apiClient.get<CursorPage<PendingApprovalDto>>('/api/admin/approvals/pending', { params });
  const data = response.data.data;

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
