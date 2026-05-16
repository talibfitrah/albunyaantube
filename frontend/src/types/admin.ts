import type { CursorPage } from './pagination';

/**
 * Cubic R-final6 P1 — extended to include USER so the type system reflects
 * what backend rows can carry. Previously this was 'ADMIN' | 'MODERATOR'
 * and fromBackendRole had to cast 'USER' through the union, hiding the
 * default-fallback bug from TS narrowing checks. The admin dashboard
 * still primarily lists elevated roles, but the wire shape can legitimately
 * surface USER (e.g., a soft-deleted ex-moderator whose role was reset).
 */
export type AdminRole = 'ADMIN' | 'MODERATOR' | 'USER';

// Cubic R9 P2 — expose all 4 backend statuses. Pre-R9 the mapper
// collapsed BLOCKED, DELETED, PENDING_PROFILE to a single 'DISABLED'
// bucket; bulk-recover decisions and the status filter dropdown were
// blind to the distinction. Now mirrors UserStatus.java values.
export type AdminUserStatus = 'ACTIVE' | 'BLOCKED' | 'DELETED' | 'PENDING_PROFILE';

export interface AdminUser {
  id: string;
  email: string;
  role: AdminRole;
  status: AdminUserStatus;
  displayName?: string;
  lastLoginAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface AdminUserCreatePayload {
  email: string;
  password: string;
  displayName?: string;
  role: AdminRole;
}

export interface AdminUserUpdatePayload {
  role: AdminRole;
  status: AdminUserStatus;
}

export interface AdminUsersPage extends CursorPage<AdminUser> {}

export interface AuditEntity {
  type: string;
  id: string;
  slug?: string | null;
}

export interface AuditActor {
  uid: string;
  email: string;
  displayName?: string;
  role?: string;
}

export interface AuditEntry {
  id: string;
  actorUid: string;
  actorDisplayName?: string;
  action: string;
  entityType: string;
  entityId: string;
  details: Record<string, unknown>;
  timestamp: string;
  ipAddress?: string;
}

export interface AuditPage extends CursorPage<AuditEntry> {}
