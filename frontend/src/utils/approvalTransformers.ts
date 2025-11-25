/**
 * Approval Data Transformers
 * Pure functions to transform API DTOs to UI models
 */

import type { PendingApprovalDto } from '@/types/api';

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
 * Parse formatted number string (e.g., "1.2K", "3.5M") back to numeric value
 */
export function parseFormattedNumber(value: unknown): number {
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
 */
export function mapPendingApprovalToUi(dto: PendingApprovalDto): PendingApproval {
  const metadata = dto.metadata || {};
  const contentType = (dto.type?.toLowerCase() as 'channel' | 'playlist' | 'video') || 'channel';

  // Helper to safely extract metadata values with type assertions
  const getString = (key: string): string => (metadata[key] as string | undefined) || '';
  const getNumber = (key: string): number => (metadata[key] as number | undefined) || 0;

  // Categories come from dto.category (single name), not metadata
  const categories = dto.category ? [dto.category] : [];

  // Type-specific field extraction
  let subscriberCount: number | undefined;
  let videoCount: number | undefined;

  if (contentType === 'channel') {
    subscriberCount = parseFormattedNumber(metadata['subscriberCount']);
    videoCount = getNumber('videoCount');
  } else if (contentType === 'playlist') {
    videoCount = getNumber('itemCount');
  }

  return {
    id: dto.id || '',
    type: contentType,
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
 * Sort approvals by submission date
 */
export function sortApprovals(
  approvals: PendingApproval[],
  order: 'oldest' | 'newest'
): PendingApproval[] {
  const sorted = [...approvals];
  if (order === 'newest') {
    sorted.sort((a, b) => new Date(b.submittedAt).getTime() - new Date(a.submittedAt).getTime());
  } else {
    sorted.sort((a, b) => new Date(a.submittedAt).getTime() - new Date(b.submittedAt).getTime());
  }
  return sorted;
}
