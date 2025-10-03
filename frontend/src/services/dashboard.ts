import { authorizedJsonFetch } from '@/services/http';
import type { DashboardMetricsResponse, DashboardTimeframe } from '@/types/dashboard';

// FIREBASE-MIGRATE-04: Dashboard endpoint now implemented
const DASHBOARD_ENDPOINT = '/api/admin/dashboard';

function buildQuery(timeframe?: DashboardTimeframe): string {
  if (!timeframe) {
    return '';
  }
  const params = new URLSearchParams();
  params.set('timeframe', timeframe);
  const query = params.toString();
  return query ? `?${query}` : '';
}

export async function fetchDashboardMetrics(
  timeframe?: DashboardTimeframe
): Promise<DashboardMetricsResponse> {
  const query = buildQuery(timeframe);
  return authorizedJsonFetch<DashboardMetricsResponse>(`${DASHBOARD_ENDPOINT}${query}`);
}
