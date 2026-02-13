import type { CursorPage } from '@/types/pagination';

export type ExclusionParentType = 'CHANNEL' | 'PLAYLIST';
export type ExclusionResourceType = 'PLAYLIST' | 'VIDEO';

/**
 * Admin user summary for workspace exclusions.
 * Simplified from full AdminUserSummary since backend DTO only provides id/email/displayName.
 */
export interface ExclusionAdminUser {
  id: string;
  email?: string | null;
  displayName?: string | null;
}

export interface Exclusion {
  id: string;
  parentType: ExclusionParentType;
  parentId: string;
  parentYoutubeId?: string;
  /** Name of the parent channel or playlist */
  parentName?: string | null;
  excludeType: ExclusionResourceType;
  excludeId: string;
  reason?: string | null;
  createdAt?: string | null;
  createdBy?: ExclusionAdminUser | null;
}

export interface CreateExclusionPayload {
  parentType: ExclusionParentType;
  parentId: string;
  excludeType: ExclusionResourceType;
  excludeId: string;
  reason?: string;
}

export type ExclusionPage = CursorPage<Exclusion>;
