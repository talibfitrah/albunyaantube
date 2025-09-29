import type { CursorPage } from '@/types/pagination';
import type { CategoryTag } from '@/types/registry';

export type ModerationProposalStatus = 'PENDING' | 'APPROVED' | 'REJECTED';
export type ModerationProposalKind = 'CHANNEL' | 'PLAYLIST' | 'VIDEO';

export interface AdminUserSummary {
  id: string;
  email: string;
  displayName: string;
  roles: ('ADMIN' | 'MODERATOR')[];
  status: 'ACTIVE' | 'DISABLED';
  lastLoginAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface ModerationProposal {
  id: string;
  kind: ModerationProposalKind;
  ytId: string;
  status: ModerationProposalStatus;
  suggestedCategories: CategoryTag[];
  proposer: AdminUserSummary;
  notes: string | null;
  decidedBy: AdminUserSummary | null;
  decidedAt: string | null;
  decisionReason: string | null;
  createdAt: string;
  updatedAt: string;
}

export type ModerationProposalPage = CursorPage<ModerationProposal>;
