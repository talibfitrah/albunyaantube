import { authorizedJsonFetch } from '@/services/http';
import type { CursorPage } from '@/types/pagination';

// FIREBASE-MIGRATE: User management not implemented in Firebase backend yet

export async function fetchUsersPage(params: any = {}): Promise<CursorPage<any>> {
  // FIREBASE-MIGRATE: User management endpoints not implemented yet
  // TODO: Implement user management in backend (Firebase Auth + Firestore sync)
  return {
    data: [],
    pageInfo: {
      nextCursor: null,
      hasNextPage: false
    }
  };
}

export async function createUser(payload: any): Promise<any> {
  console.warn('User management not implemented in Firebase backend');
}

export async function updateUserRole(userId: string, role: string): Promise<void> {
  console.warn('User management not implemented in Firebase backend');
}

export async function deleteUser(userId: string): Promise<void> {
  console.warn('User management not implemented in Firebase backend');
}
