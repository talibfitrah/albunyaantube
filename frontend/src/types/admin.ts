import type { CursorPage } from './pagination';

export type AdminRole = 'ADMIN' | 'MODERATOR';

export type AdminUserStatus = 'ACTIVE' | 'DISABLED';

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
