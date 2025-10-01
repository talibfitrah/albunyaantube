import type { CursorPage } from './pagination';

export type AdminRole = 'ADMIN' | 'MODERATOR';

export type AdminUserStatus = 'ACTIVE' | 'DISABLED';

export interface AdminUser {
  id: string;
  email: string;
  roles: AdminRole[];
  status: AdminUserStatus;
  lastLoginAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface AdminUserCreatePayload {
  email: string;
  roles: AdminRole[];
}

export interface AdminUserUpdatePayload {
  roles: AdminRole[];
  status: AdminUserStatus;
}

export interface AdminUsersPage extends CursorPage<AdminUser> {}

export interface AuditEntity {
  type: string;
  id: string;
  slug?: string | null;
}

export interface AuditEntry {
  id: string;
  actor: AdminUser;
  action: string;
  entity: AuditEntity;
  metadata: Record<string, unknown>;
  createdAt: string;
}

export interface AuditPage extends CursorPage<AuditEntry> {}
