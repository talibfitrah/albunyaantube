import { authorizedJsonFetch } from '@/services/http';
import type { CursorPage } from '@/types/pagination';

// FIREBASE-MIGRATE: Moderation not implemented in Firebase backend yet

export async function fetchProposalsPage(params: any = {}): Promise<CursorPage<any>> {
  // FIREBASE-MIGRATE: Moderation proposals not implemented yet
  // TODO: Implement moderation system in backend
  return {
    data: [],
    pageInfo: {
      cursor: null,
      nextCursor: null,
      hasNext: false
    }
  };
}

export async function approveProposal(proposalId: string): Promise<void> {
  console.warn('Moderation proposals not implemented in Firebase backend');
}

export async function rejectProposal(proposalId: string, reason?: string): Promise<void> {
  console.warn('Moderation proposals not implemented in Firebase backend');
}
