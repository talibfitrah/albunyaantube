import { authorizedJsonFetch } from '@/services/http';
import type { CursorPage } from '@/types/pagination';

// FIREBASE-MIGRATE: Audit log not implemented in Firebase backend yet

export async function fetchAuditLogPage(params: any = {}): Promise<CursorPage<any>> {
  // FIREBASE-MIGRATE: Audit log not implemented yet
  // TODO: Implement audit log in backend (Firestore collection with admin actions)
  return {
    data: [],
    pageInfo: {
      nextCursor: null,
      hasNextPage: false
    }
  };
}
