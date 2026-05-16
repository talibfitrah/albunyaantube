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
  /**
   * Cubic R7 P1 — wire AbortSignal so superseded fetches actually cancel
   * server-side. Pre-fix the audit page epoch guard discarded stale
   * responses on the client, but the underlying request still ran to
   * completion and cost audit-read quota per superseded supersede.
   */
  signal?: AbortSignal;
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
  // Defensively handle deploy-order skew between FE and BE: if the backend
  // momentarily returns a legacy bare array (rollback, cached proxy response,
  // pre-deploy state), coerce it into the new shape rather than throwing
  // `page.items.map is not a function` and crashing the page (cubic R5 P2).
  const raw = await authorizedJsonFetch<unknown>(
    `${AUDIT_BASE_PATH}?${queryParams}`,
    params.signal ? { signal: params.signal } : {}
  );
  const page = normaliseAuditPage(raw);

  return {
    data: page.items.map(mapAuditLogToEntry).filter((entry): entry is AuditEntry => entry !== null),
    pageInfo: {
      cursor: params.cursor ?? null,
      nextCursor: page.nextCursor ?? null,
      hasNext: page.nextCursor != null
    }
  };
}

function normaliseAuditPage(
  raw: unknown
): { items: AuditLog[]; nextCursor: string | null } {
  if (Array.isArray(raw)) {
    return { items: raw as AuditLog[], nextCursor: null };
  }
  if (raw && typeof raw === 'object' && 'items' in raw) {
    const obj = raw as { items?: unknown; nextCursor?: unknown };
    return {
      items: Array.isArray(obj.items) ? (obj.items as AuditLog[]) : [],
      nextCursor: typeof obj.nextCursor === 'string' ? obj.nextCursor : null
    };
  }
  return { items: [], nextCursor: null };
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
  // Cubic R7 P1 — route through normaliseAuditPage so a deploy-order skew
  // (BE returns legacy bare array) doesn't crash the filter page with
  // `page.items.map is not a function`.
  const raw = await authorizedJsonFetch<unknown>(
    `${AUDIT_BASE_PATH}/actor/${encodeURIComponent(actorUid)}?${qp}`
  );
  const page = normaliseAuditPage(raw);

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
  // Cubic R7 P1 — see fetchAuditLogsByActor for rationale.
  const raw = await authorizedJsonFetch<unknown>(
    `${AUDIT_BASE_PATH}/action/${encodeURIComponent(action)}?${qp}`
  );
  const page = normaliseAuditPage(raw);

  return {
    data: page.items.map(mapAuditLogToEntry).filter((entry): entry is AuditEntry => entry !== null),
    pageInfo: {
      cursor: options.cursor ?? null,
      nextCursor: page.nextCursor ?? null,
      hasNext: page.nextCursor != null
    }
  };
}
