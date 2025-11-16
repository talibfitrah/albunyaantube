import { describe, it, expect, beforeEach, vi } from 'vitest';
import { useDashboardMetrics } from '@/composables/useDashboardMetrics';
import type { DashboardMetricsResponse } from '@/types/dashboard';

const fetchDashboardMetricsMock = vi.fn();

vi.mock('@/services/dashboard', () => ({
  fetchDashboardMetrics: (...args: unknown[]) => fetchDashboardMetricsMock(...args)
}));

const sampleResponse: DashboardMetricsResponse = {
  data: {
    pendingModeration: {
      current: 12,
      previous: 9,
      trend: 'UP'
    },
    categories: {
      total: 48,
      newThisPeriod: 4,
      previousTotal: 44
    },
    moderators: {
      current: 6,
      previous: 6,
      trend: 'FLAT'
    },
    videoValidation: {
      status: 'COMPLETED',
      videosChecked: 120,
      videosMarkedUnavailable: 3,
      validationErrors: 0,
      lastRunAt: '2024-10-10T11:00:00Z'
    }
  },
  meta: {
    generatedAt: '2024-10-10T12:00:00Z',
    timeRange: {
      start: '2024-10-09T12:00:00Z',
      end: '2024-10-10T12:00:00Z',
      label: 'LAST_24_HOURS'
    },
    cacheTtlSeconds: 60,
    warnings: []
  }
};

describe('useDashboardMetrics', () => {
  beforeEach(() => {
    fetchDashboardMetricsMock.mockReset();
  });

  it('fetches metrics and exposes cards', async () => {
    fetchDashboardMetricsMock.mockResolvedValue(structuredClone(sampleResponse));
    const metrics = useDashboardMetrics();

    await metrics.refresh();

    expect(fetchDashboardMetricsMock).toHaveBeenCalledWith('LAST_24_HOURS');
    expect(metrics.cards.value).toHaveLength(4);
    expect(metrics.cards.value[0].kind).toBe('comparison');
    expect(metrics.cards.value[3].kind).toBe('validation');
    expect(metrics.lastUpdated.value).toBe('2024-10-10T12:00:00Z');
  });

  it('updates timeframe when changeTimeframe is called', async () => {
    fetchDashboardMetricsMock.mockResolvedValue(structuredClone(sampleResponse));
    const metrics = useDashboardMetrics();

    await metrics.changeTimeframe('LAST_7_DAYS');

    expect(fetchDashboardMetricsMock).toHaveBeenCalledWith('LAST_7_DAYS');
    expect(metrics.timeframe.value).toBe('LAST_7_DAYS');
  });

  it('captures warnings from API response', async () => {
    const responseWithWarning = structuredClone(sampleResponse);
    responseWithWarning.meta.warnings = ['STALE_DATA'];
    fetchDashboardMetricsMock.mockResolvedValue(responseWithWarning);

    const metrics = useDashboardMetrics();
    await metrics.refresh();

    expect(metrics.warnings.value).toEqual(['STALE_DATA']);
  });

  it('stores errors when request fails', async () => {
    fetchDashboardMetricsMock.mockRejectedValue(new Error('Network error'));
    const metrics = useDashboardMetrics();

    await metrics.refresh();

    expect(metrics.error.value).toBe('Network error');
    expect(metrics.cards.value).toEqual([]);
  });
});
