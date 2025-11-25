import apiClient from './api/client';
import type { DashboardMetricsResponse, DashboardTimeframe } from '@/types/dashboard';

// FIREBASE-MIGRATE-04: Dashboard endpoint now implemented
const DASHBOARD_ENDPOINT = '/api/admin/dashboard';

export async function fetchDashboardMetrics(
  timeframe?: DashboardTimeframe
): Promise<DashboardMetricsResponse> {
  const params: Record<string, string> = {};
  if (timeframe) {
    params.timeframe = timeframe;
  }
  const response = await apiClient.get<DashboardMetricsResponse>(DASHBOARD_ENDPOINT, { params });
  return response.data;
}
