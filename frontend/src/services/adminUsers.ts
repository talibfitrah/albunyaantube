import { authorizedJsonFetch } from '@/services/http';
import type { CursorPage } from '@/types/pagination';
import type {
  AdminUser,
  AdminUserCreatePayload,
  AdminUserStatus,
  AdminRole
} from '@/types/admin';

// FIREBASE-MIGRATE-04: User management now implemented
const USERS_BASE_PATH = '/api/admin/users';

// Transform frontend types to backend format
function toBackendRole(role: AdminRole): string {
  return role.toLowerCase();
}

function toBackendStatus(status: AdminUserStatus): string {
  return status === 'ACTIVE' ? 'active' : 'inactive';
}

function fromBackendStatus(status: string): AdminUserStatus {
  return status === 'active' ? 'ACTIVE' : 'DISABLED';
}

function fromBackendRole(role: string): AdminRole {
  return role.toUpperCase() as AdminRole;
}

// Transform backend user to frontend format
function transformUser(backendUser: any): AdminUser {
  return {
    id: backendUser.uid,
    email: backendUser.email,
    role: fromBackendRole(backendUser.role),
    status: fromBackendStatus(backendUser.status),
    displayName: backendUser.displayName,
    lastLoginAt: backendUser.lastLoginAt,
    createdAt: backendUser.createdAt,
    updatedAt: backendUser.updatedAt
  };
}

export async function fetchUsersPage(params: any = {}): Promise<CursorPage<AdminUser>> {
  // Backend returns array, not paginated response
  const users = await authorizedJsonFetch<any[]>(USERS_BASE_PATH);

  return {
    data: users.map(transformUser),
    pageInfo: {
      cursor: null,
      nextCursor: null,
      hasNext: false
    }
  };
}

export async function createUser(payload: AdminUserCreatePayload): Promise<AdminUser> {
  const backendPayload = {
    email: payload.email,
    password: payload.password,
    displayName: payload.displayName || null,
    role: toBackendRole(payload.role)
  };

  const result = await authorizedJsonFetch<any>(USERS_BASE_PATH, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json'
    },
    body: JSON.stringify(backendPayload)
  });

  return transformUser(result);
}

export async function updateUserRole(userId: string, role: AdminRole): Promise<void> {
  await authorizedJsonFetch<void>(`${USERS_BASE_PATH}/${userId}/role`, {
    method: 'PUT',
    headers: {
      'Content-Type': 'application/json'
    },
    body: JSON.stringify({ role: toBackendRole(role) })
  });
}

export async function updateUserStatus(userId: string, status: AdminUserStatus): Promise<void> {
  await authorizedJsonFetch<void>(`${USERS_BASE_PATH}/${userId}/status`, {
    method: 'PUT',
    headers: {
      'Content-Type': 'application/json'
    },
    body: JSON.stringify({ status: toBackendStatus(status) })
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
