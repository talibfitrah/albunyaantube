import { authorizedJsonFetch } from '@/services/http';
import type { DashboardMetricsResponse, DashboardTimeframe } from '@/types/dashboard';

// FIREBASE-MIGRATE: Dashboard not implemented in Firebase backend yet
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
  // FIREBASE-MIGRATE: Dashboard endpoint not implemented yet
  // TODO: Implement dashboard metrics in backend
  // Return empty metrics to prevent frontend errors
  return {
    totalChannels: 0,
    totalPlaylists: 0,
    totalVideos: 0,
    pendingApprovals: 0,
    recentActivity: []
  } as any;
}
