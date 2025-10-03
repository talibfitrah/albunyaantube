import { authorizedJsonFetch } from '@/services/http';
import type { CursorPage } from '@/types/pagination';

// FIREBASE-MIGRATE-04: User management now implemented
const USERS_BASE_PATH = '/api/admin/users';

export async function fetchUsersPage(params: any = {}): Promise<CursorPage<any>> {
  // Backend returns array, not paginated response
  const users = await authorizedJsonFetch<any[]>(USERS_BASE_PATH);
  
  return {
    data: users,
    pageInfo: {
      cursor: null,
      nextCursor: null,
      hasNext: false
    }
  };
}

export async function createUser(payload: any): Promise<any> {
  return authorizedJsonFetch<any>(USERS_BASE_PATH, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json'
    },
    body: JSON.stringify(payload)
  });
}

export async function updateUserRole(userId: string, role: string): Promise<void> {
  await authorizedJsonFetch<void>(`${USERS_BASE_PATH}/${userId}/role`, {
    method: 'PUT',
    headers: {
      'Content-Type': 'application/json'
    },
    body: JSON.stringify({ role })
  });
}

export async function updateUserStatus(userId: string, status: string): Promise<void> {
  await authorizedJsonFetch<void>(`${USERS_BASE_PATH}/${userId}/status`, {
    method: 'PUT',
    headers: {
      'Content-Type': 'application/json'
    },
    body: JSON.stringify({ status })
  });
}

export async function deleteUser(userId: string): Promise<void> {
  await authorizedJsonFetch<void>(`${USERS_BASE_PATH}/${userId}`, {
    method: 'DELETE'
  });
}

export async function sendPasswordReset(userId: string): Promise<void> {
  await authorizedJsonFetch<void>(`${USERS_BASE_PATH}/${userId}/reset-password`, {
    method: 'POST'
  });
}
