/**
 * Reports Service
 * Admin API integration for the content-report moderation workflow.
 * Maps to /api/admin/reports endpoints (REPORT-01).
 */

import apiClient from './api/client';

// ---------- Types ----------

export type ReportTargetType = 'VIDEO' | 'CHANNEL' | 'PLAYLIST';
export type ReportStatus = 'PENDING' | 'RESOLVED' | 'REJECTED';
export type ReportReason =
  | 'MUSIC' | 'NUDITY' | 'BAD_LANGUAGE' | 'FLIRTING' | 'ROMANCE'
  | 'AWRAH' | 'SHIRK' | 'BIDAH' | 'VIOLENCE' | 'MISINFORMATION' | 'OTHER';

/** Firestore Timestamp shape as serialised by the backend. */
interface FirestoreTimestamp {
  seconds: number;
  nanos: number;
}

export interface ContentReport {
  id: string;
  targetType: ReportTargetType;
  targetId: string;
  reasons: ReportReason[];
  otherDescription?: string;
  deviceId: string;
  status: ReportStatus;
  createdAt: FirestoreTimestamp;
  resolvedAt?: FirestoreTimestamp;
  resolvedBy?: string;
  resolutionNote?: string;
  // Optional parent context populated when the user reported an item
  // from inside a channel or playlist — drives correct exclusion bucket
  // on resolve and lets the admin table show "from [parent title]".
  parentType?: ReportTargetType;
  parentId?: string;
  // VIDEO sub-type. SHORT or LIVESTREAM narrows which exclusion bucket
  // a CHANNEL parent's resolution targets.
  contentSubType?: 'SHORT' | 'LIVESTREAM' | 'POST';
}

export interface ReportStats {
  pending: number;
  resolved: number;
  rejected: number;
  newLast3h: number;
}

export interface ReportFilters {
  status?: ReportStatus;
  targetType?: ReportTargetType;
  page?: number;
  size?: number;
}

export interface ResolveReportRequest {
  status: 'RESOLVED' | 'REJECTED';
  note?: string;
}

// ---------- Helpers ----------

/** Convert a Firestore Timestamp to a JS Date. */
export function timestampToDate(ts: FirestoreTimestamp): Date {
  return new Date(ts.seconds * 1000 + Math.floor(ts.nanos / 1_000_000));
}

/** Format a Firestore Timestamp for display. */
export function formatTimestamp(ts: FirestoreTimestamp | undefined): string {
  if (!ts) return '—';
  return timestampToDate(ts).toLocaleString();
}

/** Human-readable label for each report reason. */
export const REASON_LABELS: Record<ReportReason, string> = {
  MUSIC: 'Music / Instruments',
  NUDITY: 'Nudity / Immodesty',
  BAD_LANGUAGE: 'Bad Language',
  FLIRTING: 'Flirting / Innuendo',
  ROMANCE: 'Romance / Love Content',
  AWRAH: 'Awrah Exposure',
  SHIRK: 'Shirk / Polytheism',
  BIDAH: "Bid'ah / Innovation",
  VIOLENCE: 'Violence / Gore',
  MISINFORMATION: 'Misinformation',
  OTHER: 'Other',
};

// ---------- API calls ----------

export async function fetchReports(filters: ReportFilters = {}): Promise<ContentReport[]> {
  const params: Record<string, string | number> = {};
  if (filters.status) params.status = filters.status;
  if (filters.targetType) params.targetType = filters.targetType;
  if (filters.page !== undefined) params.page = filters.page;
  if (filters.size !== undefined) params.size = filters.size;

  const response = await apiClient.get<ContentReport[]>('/api/admin/reports', { params });
  return response.data;
}

export async function resolveReport(id: string, request: ResolveReportRequest): Promise<ContentReport> {
  const response = await apiClient.patch<ContentReport>(`/api/admin/reports/${id}`, request);
  return response.data;
}

export async function fetchReportStats(): Promise<ReportStats> {
  const response = await apiClient.get<ReportStats>('/api/admin/reports/stats');
  return response.data;
}
