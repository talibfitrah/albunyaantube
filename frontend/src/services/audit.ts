export type AuditEventName =
  | 'moderation:approve'
  | 'moderation:reject'
  | 'exclusions:create'
  | 'exclusions:update'
  | 'exclusions:delete'
  | 'exclusions:delete-many';

export interface AuditEventDetail {
  name: AuditEventName;
  proposalId: string;
  timestamp: string;
  metadata?: Record<string, unknown>;
}

type AuditEventSink = (detail: AuditEventDetail) => void;

let customSink: AuditEventSink | null = null;

export function setAuditEventSink(next: AuditEventSink | null) {
  customSink = next;
}

export function emitAuditEvent(detail: AuditEventDetail) {
  if (customSink) {
    customSink(detail);
    return;
  }

  if (typeof window !== 'undefined' && typeof window.dispatchEvent === 'function') {
    window.dispatchEvent(new CustomEvent('admin:audit', { detail }));
  }

  if (typeof console !== 'undefined' && typeof console.info === 'function' && import.meta.env.DEV) {
    console.info('[audit]', detail);
  }
}
