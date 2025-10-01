import { authorizedJsonFetch } from '@/services/http';
import type { AuditPage } from '@/types/admin';

export interface AuditListParams {
  cursor?: string | null;
  limit?: number;
  actorId?: string | null;
  action?: string | null;
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

export async function fetchAuditPage(params: AuditListParams = {}): Promise<AuditPage> {
  const query = buildQuery({
    cursor: params.cursor ?? undefined,
    limit: params.limit,
    actorId: params.actorId ?? undefined,
    action: params.action ?? undefined
  });
  return authorizedJsonFetch<AuditPage>(`/api/v1/admin/audit${query}`);
}
