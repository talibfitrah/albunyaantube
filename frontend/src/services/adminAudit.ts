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

  // Plan F (T21-T23): backend now returns { items, nextCursor } instead of a bare array.
  const page = await authorizedJsonFetch<{ items: AuditLog[]; nextCursor: string | null }>(
    `${AUDIT_BASE_PATH}?${queryParams}`
  );

  return {
    data: page.items.map(mapAuditLogToEntry).filter((entry): entry is AuditEntry => entry !== null),
    pageInfo: {
      cursor: params.cursor ?? null,
      nextCursor: page.nextCursor ?? null,
      hasNext: page.nextCursor != null
    }
  };
}

export async function fetchAuditLogsByActor(
  actorUid: string,
  options: { cursor?: string | null; limit?: number } = {}
): Promise<CursorPage<AuditEntry>> {
  const limit = options.limit || 100;
  const qp = new URLSearchParams();
  qp.append('limit', String(limit));
  if (options.cursor) qp.append('cursor', options.cursor);

  // Plan F (T22): /actor/{uid} now returns { items, nextCursor }
  const page = await authorizedJsonFetch<{ items: AuditLog[]; nextCursor: string | null }>(
    `${AUDIT_BASE_PATH}/actor/${encodeURIComponent(actorUid)}?${qp}`
  );

  return {
    data: page.items.map(mapAuditLogToEntry).filter((entry): entry is AuditEntry => entry !== null),
    pageInfo: {
      cursor: options.cursor ?? null,
      nextCursor: page.nextCursor ?? null,
      hasNext: page.nextCursor != null
    }
  };
}

export async function fetchAuditLogsByEntityType(entityType: string, limit = 100): Promise<AuditEntry[]> {
  const logs = await authorizedJsonFetch<AuditLog[]>(`${AUDIT_BASE_PATH}/entity-type/${encodeURIComponent(entityType)}?limit=${limit}`);
  return logs.map(mapAuditLogToEntry).filter((entry): entry is AuditEntry => entry !== null);
}

export async function fetchAuditLogsByAction(
  action: string,
  options: { cursor?: string | null; limit?: number } = {}
): Promise<CursorPage<AuditEntry>> {
  const limit = options.limit || 100;
  const qp = new URLSearchParams();
  qp.append('limit', String(limit));
  if (options.cursor) qp.append('cursor', options.cursor);

  // Plan F (T23): /action/{action} now returns { items, nextCursor }
  const page = await authorizedJsonFetch<{ items: AuditLog[]; nextCursor: string | null }>(
    `${AUDIT_BASE_PATH}/action/${encodeURIComponent(action)}?${qp}`
  );

  return {
    data: page.items.map(mapAuditLogToEntry).filter((entry): entry is AuditEntry => entry !== null),
    pageInfo: {
      cursor: options.cursor ?? null,
      nextCursor: page.nextCursor ?? null,
      hasNext: page.nextCursor != null
    }
  };
}
