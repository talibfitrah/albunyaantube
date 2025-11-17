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
function mapAuditLogToEntry(log: AuditLog): AuditEntry {
  // Validate all required fields
  const requiredFields = {
    id: log.id,
    actorUid: log.actorUid,
    action: log.action,
    entityType: log.entityType,
    entityId: log.entityId,
    timestamp: log.timestamp
  };

  const missingFields: string[] = [];
  for (const [field, value] of Object.entries(requiredFields)) {
    if (!value || (typeof value === 'string' && value.trim() === '')) {
      missingFields.push(field);
    }
  }

  if (missingFields.length > 0) {
    const errorMsg = `Audit log missing required fields: ${missingFields.join(', ')}`;
    console.error(errorMsg, log);
    throw new Error(errorMsg);
  }

  // Safely handle metadata
  let details: Record<string, unknown> = {};
  if (log.metadata !== null && log.metadata !== undefined) {
    if (typeof log.metadata === 'object' && !Array.isArray(log.metadata)) {
      details = log.metadata as Record<string, unknown>;
    } else {
      console.warn('Audit log metadata is not an object, using empty object:', log);
    }
  }

  return {
    id: log.id,
    actorUid: log.actorUid,
    actorDisplayName: log.actorEmail || '', // Use email as display name (can be enriched later)
    action: log.action,
    entityType: log.entityType,
    entityId: log.entityId,
    details,
    timestamp: log.timestamp
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
    data: logs.map(mapAuditLogToEntry),
    pageInfo: {
      cursor: null,
      nextCursor: null,
      hasNext: false
    }
  };
}

export async function fetchAuditLogsByActor(actorUid: string, limit = 100): Promise<AuditEntry[]> {
  const logs = await authorizedJsonFetch<AuditLog[]>(`${AUDIT_BASE_PATH}/actor/${actorUid}?limit=${limit}`);
  return logs.map(mapAuditLogToEntry);
}

export async function fetchAuditLogsByEntityType(entityType: string, limit = 100): Promise<AuditEntry[]> {
  const logs = await authorizedJsonFetch<AuditLog[]>(`${AUDIT_BASE_PATH}/entity-type/${entityType}?limit=${limit}`);
  return logs.map(mapAuditLogToEntry);
}

export async function fetchAuditLogsByAction(action: string, limit = 100): Promise<AuditEntry[]> {
  const logs = await authorizedJsonFetch<AuditLog[]>(`${AUDIT_BASE_PATH}/action/${action}?limit=${limit}`);
  return logs.map(mapAuditLogToEntry);
}
