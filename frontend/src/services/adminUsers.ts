import { authorizedJsonFetch } from '@/services/http';
import type { CursorPage } from '@/types/pagination';
import type {
  AdminUser,
  AdminUserCreatePayload,
  AdminUserStatus,
  AdminRole
} from '@/types/admin';
import type { User } from '@/types/api';

// FIREBASE-MIGRATE-04: User management now implemented
const USERS_BASE_PATH = '/api/admin/users';

export interface UsersPageParams {
  // Placeholder for future filtering/pagination params
}

// Transform frontend types to backend format
function toBackendRole(role: AdminRole): string {
  return role.toLowerCase(); // API uses lowercase: 'admin', 'moderator'
}

function toBackendStatus(status: AdminUserStatus): string {
  return status === 'ACTIVE' ? 'active' : 'inactive';
}

function fromBackendStatus(status?: string): AdminUserStatus {
  return status === 'active' ? 'ACTIVE' : 'DISABLED';
}

function fromBackendRole(role?: string): AdminRole {
  return (role?.toUpperCase() || 'MODERATOR') as AdminRole;
}

/**
 * Map API User DTO to UI AdminUser model
 */
function transformUser(apiUser: User): AdminUser {
  return {
    id: apiUser.uid || '',
    email: apiUser.email || '',
    role: fromBackendRole(apiUser.role),
    status: fromBackendStatus(apiUser.status),
    displayName: apiUser.displayName,
    lastLoginAt: null, // Not in API schema, would need backend enhancement
    createdAt: apiUser.createdAt || new Date().toISOString(),
    updatedAt: apiUser.createdAt || new Date().toISOString() // Use createdAt as fallback
  };
}

export async function fetchUsersPage(params: UsersPageParams = {}): Promise<CursorPage<AdminUser>> {
  // Backend returns array, not paginated response
  const users = await authorizedJsonFetch<User[]>(USERS_BASE_PATH);

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

  const result = await authorizedJsonFetch<User>(USERS_BASE_PATH, {
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
