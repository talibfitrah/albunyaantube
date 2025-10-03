import { authorizedJsonFetch } from '@/services/http';
import type { CursorPage } from '@/types/pagination';

// FIREBASE-MIGRATE: Exclusions not implemented as separate endpoint in Firebase backend
// Exclusions are managed via channel update endpoint

export async function fetchExclusionsPage(params: any = {}): Promise<CursorPage<any>> {
  // FIREBASE-MIGRATE: Exclusions listing not implemented yet
  // TODO: Implement exclusions listing endpoint or derive from channels
  return {
    data: [],
    pageInfo: {
      nextCursor: null,
      hasNextPage: false
    }
  };
}

export async function removeExclusion(exclusionId: string): Promise<void> {
  console.warn('Exclusions management not implemented as separate endpoint in Firebase backend');
}
