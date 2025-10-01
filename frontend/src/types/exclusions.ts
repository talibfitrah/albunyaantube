import type { AdminUserSummary } from '@/types/moderation';

export type ExclusionParentType = 'CHANNEL' | 'PLAYLIST';
export type ExclusionResourceType = 'PLAYLIST' | 'VIDEO';

export interface Exclusion {
  id: string;
  parentType: ExclusionParentType;
  parentId: string;
  excludeType: ExclusionResourceType;
  excludeId: string;
  reason: string;
  createdAt: string;
  createdBy: AdminUserSummary;
}

export interface CreateExclusionPayload {
  parentType: ExclusionParentType;
  parentId: string;
  excludeType: ExclusionResourceType;
  excludeId: string;
  reason: string;
}
