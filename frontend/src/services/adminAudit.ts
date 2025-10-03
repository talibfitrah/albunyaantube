import { authorizedJsonFetch } from '@/services/http';
import type { CursorPage } from '@/types/pagination';

// FIREBASE-MIGRATE-04: Audit log now implemented
const AUDIT_BASE_PATH = '/api/admin/audit';

export async function fetchAuditLogPage(params: any = {}): Promise<CursorPage<any>> {
  const limit = params.limit || 100;
  
  // Backend returns array, not paginated response
  const logs = await authorizedJsonFetch<any[]>(`${AUDIT_BASE_PATH}?limit=${limit}`);
  
  return {
    data: logs,
    pageInfo: {
      cursor: null,
      nextCursor: null,
      hasNext: false
    }
  };
}

export async function fetchAuditLogsByActor(actorUid: string, limit = 100): Promise<any[]> {
  return authorizedJsonFetch<any[]>(`${AUDIT_BASE_PATH}/actor/${actorUid}?limit=${limit}`);
}

export async function fetchAuditLogsByEntityType(entityType: string, limit = 100): Promise<any[]> {
  return authorizedJsonFetch<any[]>(`${AUDIT_BASE_PATH}/entity-type/${entityType}?limit=${limit}`);
}

export async function fetchAuditLogsByAction(action: string, limit = 100): Promise<any[]> {
  return authorizedJsonFetch<any[]>(`${AUDIT_BASE_PATH}/action/${action}?limit=${limit}`);
}
