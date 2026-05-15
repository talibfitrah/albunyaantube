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

// Cubic R9 P2 — 1:1 mapping with backend UserStatus enum. Pre-R9 the
// fromBackendStatus mapper collapsed blocked/deleted/pending_profile to
// 'DISABLED'; the toBackendStatus mapper sent the legacy alias 'inactive'
// instead of the canonical 'blocked'. Both now mirror UserStatus.java.
function toBackendStatus(status: AdminUserStatus): string {
  switch (status) {
    case 'ACTIVE':          return 'active';
    case 'BLOCKED':         return 'blocked';
    case 'DELETED':         return 'deleted';
    case 'PENDING_PROFILE': return 'pending_profile';
  }
}

function fromBackendStatus(status?: string): AdminUserStatus {
  switch (status) {
    case 'active':          return 'ACTIVE';
    case 'blocked':         return 'BLOCKED';
    case 'deleted':         return 'DELETED';
    case 'pending_profile': return 'PENDING_PROFILE';
    // Unknown / legacy values surface as BLOCKED rather than ACTIVE so a
    // bug in the backend never silently re-enables a user in the admin UI.
    default:                return 'BLOCKED';
  }
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

// Plan F (ADMIN-USER-01) — bulk + force-logout

export type BulkAction = 'block' | 'delete' | 'recover' | 'revokeSessions';

export interface FailureEntry {
  uid: string;
  reason: string;
}

export interface BulkUserActionResult {
  successes: string[];
  failures: FailureEntry[];
}

export interface BulkUserActionRequest {
  uids: string[];
  reason?: string;
}

async function postBulk(path: string, req: BulkUserActionRequest): Promise<BulkUserActionResult> {
  return authorizedJsonFetch<BulkUserActionResult>(`${USERS_BASE_PATH}/${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(req)
  });
}

export const bulkBlock          = (req: BulkUserActionRequest) => postBulk('bulk-block', req);
export const bulkDelete         = (req: BulkUserActionRequest) => postBulk('bulk-delete', req);
export const bulkRecover        = (req: BulkUserActionRequest) => postBulk('bulk-recover', req);
export const bulkRevokeSessions = (req: BulkUserActionRequest) => postBulk('bulk-revoke-sessions', req);

/**
 * Cubic R7 P1 — `reason` parameter dropped.
 *
 * Pre-fix the helper accepted an optional `reason` but no UI surfaced a
 * prompt for it; every call went out without a reason and the backend
 * audit row recorded blank. Misleading API surface. If a per-revoke
 * justification becomes a product requirement, surface it as a dialog in
 * UsersManagementView and re-add as a typed field; do not re-introduce
 * the silent optional.
 */
export async function forceLogout(uid: string): Promise<void> {
  // Cubic R7 P2 — no body. Backend's POST /{uid}/revoke-sessions has
  // @RequestBody(required = false); sending {} works today but some
  // intermediate proxies / future stricter mappings reject empty JSON
  // bodies. Drop the Content-Type and body entirely so the request is a
  // bare POST.
  await authorizedJsonFetch<void>(`${USERS_BASE_PATH}/${uid}/revoke-sessions`, {
    method: 'POST'
  });
}
