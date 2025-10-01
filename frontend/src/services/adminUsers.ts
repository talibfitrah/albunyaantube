import { authorizedJsonFetch } from '@/services/http';
import type {
  AdminUser,
  AdminUserCreatePayload,
  AdminUserUpdatePayload,
  AdminUsersPage,
  AdminRole,
  AdminUserStatus
} from '@/types/admin';

export interface AdminUserListParams {
  cursor?: string | null;
  limit?: number;
  search?: string | null;
  role?: AdminRole | null;
  status?: AdminUserStatus | null;
}

function buildQuery(params: Record<string, string | number | null | undefined>): string {
  const searchParams = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== null && value !== '') {
      searchParams.set(key, String(value));
    }
  }
  const query = searchParams.toString();
  return query ? `?${query}` : '';
}

export async function fetchAdminUsersPage(params: AdminUserListParams = {}): Promise<AdminUsersPage> {
  const query = buildQuery({
    cursor: params.cursor ?? undefined,
    limit: params.limit,
    search: params.search ?? undefined,
    role: params.role ?? undefined,
    status: params.status ?? undefined
  });
  return authorizedJsonFetch<AdminUsersPage>(`/api/v1/admin/users${query}`);
}

export async function createAdminUser(payload: AdminUserCreatePayload): Promise<AdminUser> {
  return authorizedJsonFetch<AdminUser>('/api/v1/admin/users', {
    method: 'POST',
    body: JSON.stringify(payload)
  });
}

export async function updateAdminUser(id: string, payload: AdminUserUpdatePayload): Promise<AdminUser> {
  return authorizedJsonFetch<AdminUser>(`/api/v1/admin/users/${id}`, {
    method: 'PUT',
    body: JSON.stringify(payload)
  });
}

export async function deleteAdminUser(id: string): Promise<void> {
  await authorizedJsonFetch<void>(`/api/v1/admin/users/${id}`, {
    method: 'DELETE'
  });
}
