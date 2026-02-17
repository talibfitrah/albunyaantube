import { authorizedJsonFetch } from '@/services/http';
import type { CursorPage } from '@/types/pagination';
import type { AuditEntry } from '@/types/admin';
import type { AuditLog } from '@/types/api';

// FIREBASE-MIGRATE-04: Audit log now implemented
const AUDIT_BASE_PATH = '/api/admin/audit';

export interface AuditLogPageParams {
  cursor?: string | null;
  limit?: number;
  actorId?: string;
  action?: string;
}

/**
 * Map API AuditLog DTO to UI AuditEntry model
 */
function mapAuditLogToEntry(log: AuditLog): AuditEntry | null {
  // Backend model uses 'actorDisplayName' and 'details' field names,
  // but the OpenAPI spec (and generated types) use 'actorEmail' and 'metadata'.
  // Handle both at runtime to be resilient to this mismatch.
  const raw = log as Record<string, unknown>;

  const id = (raw.id as string) || '';
  const actorUid = (raw.actorUid as string) || '';
  const action = (raw.action as string) || '';
  const entityType = (raw.entityType as string) || '';
  const entityId = (raw.entityId as string) || '';
  const timestamp = (raw.timestamp as string) || '';

  // Skip entries missing critical fields instead of throwing
  if (!id || !action || !entityType || !timestamp) {
    console.warn('Skipping audit log with missing required fields:', log);
    return null;
  }

  // Backend sends 'actorDisplayName', spec says 'actorEmail' — try both
  const actorDisplayName = (raw.actorDisplayName as string)
    || (raw.actorEmail as string)
    || '';

  // Backend sends 'details', spec says 'metadata' — try both
  let details: Record<string, unknown> = {};
  const rawDetails = raw.details ?? raw.metadata;
  if (rawDetails && typeof rawDetails === 'object' && !Array.isArray(rawDetails)) {
    details = rawDetails as Record<string, unknown>;
  }

  return {
    id,
    actorUid,
    actorDisplayName,
    action,
    entityType,
    entityId,
    details,
    timestamp
  };
}

export async function fetchAuditLogPage(params: AuditLogPageParams = {}): Promise<CursorPage<AuditEntry>> {
  const limit = params.limit || 100;

  // Build query string with all supported params
  const queryParams = new URLSearchParams();
  queryParams.append('limit', limit.toString());

  if (params.cursor) {
    queryParams.append('cursor', params.cursor);
  }

  if (params.actorId) {
    queryParams.append('actorId', params.actorId);
  }

  if (params.action) {
    queryParams.append('action', params.action);
  }

  // Backend returns array, not paginated response
  const logs = await authorizedJsonFetch<AuditLog[]>(`${AUDIT_BASE_PATH}?${queryParams}`);

  return {
    data: logs.map(mapAuditLogToEntry).filter((entry): entry is AuditEntry => entry !== null),
    pageInfo: {
      cursor: null,
      nextCursor: null,
      hasNext: false
    }
  };
}

export async function fetchAuditLogsByActor(actorUid: string, limit = 100): Promise<AuditEntry[]> {
  const logs = await authorizedJsonFetch<AuditLog[]>(`${AUDIT_BASE_PATH}/actor/${encodeURIComponent(actorUid)}?limit=${limit}`);
  return logs.map(mapAuditLogToEntry).filter((entry): entry is AuditEntry => entry !== null);
}

export async function fetchAuditLogsByEntityType(entityType: string, limit = 100): Promise<AuditEntry[]> {
  const logs = await authorizedJsonFetch<AuditLog[]>(`${AUDIT_BASE_PATH}/entity-type/${encodeURIComponent(entityType)}?limit=${limit}`);
  return logs.map(mapAuditLogToEntry).filter((entry): entry is AuditEntry => entry !== null);
}

export async function fetchAuditLogsByAction(action: string, limit = 100): Promise<AuditEntry[]> {
  const logs = await authorizedJsonFetch<AuditLog[]>(`${AUDIT_BASE_PATH}/action/${encodeURIComponent(action)}?limit=${limit}`);
  return logs.map(mapAuditLogToEntry).filter((entry): entry is AuditEntry => entry !== null);
}
