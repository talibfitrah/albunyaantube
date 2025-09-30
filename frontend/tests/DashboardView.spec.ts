import '@testing-library/jest-dom';
import { render, screen, fireEvent } from '@testing-library/vue';
import { createI18n } from 'vue-i18n';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import DashboardView from '@/views/DashboardView.vue';
import { messages } from '@/locales/messages';
import type { DashboardMetricsResponse } from '@/types/dashboard';

const fetchDashboardMetricsMock = vi.fn();

vi.mock('@/services/dashboard', () => ({
  fetchDashboardMetrics: (...args: unknown[]) => fetchDashboardMetricsMock(...args)
}));

const baseResponse: DashboardMetricsResponse = {
  data: {
    pendingModeration: { current: 12, previous: 10, trend: 'UP' },
    categories: { total: 48, newThisPeriod: 5, previousTotal: 43 },
    moderators: { current: 6, previous: 7, trend: 'DOWN' }
  },
  meta: {
    generatedAt: '2024-10-10T12:00:00Z',
    timeRange: {
      start: '2024-10-09T12:00:00Z',
      end: '2024-10-10T12:00:00Z',
      label: 'LAST_24_HOURS'
    },
    cacheTtlSeconds: 60
  }
};

describe('DashboardView', () => {
  beforeEach(() => {
    fetchDashboardMetricsMock.mockReset();
  });

  function renderDashboard() {
    const i18n = createI18n({ legacy: false, locale: 'en', messages });
    return render(DashboardView, {
      global: {
        plugins: [i18n]
      }
    });
  }

  it('renders dashboard metrics when loaded', async () => {
    fetchDashboardMetricsMock.mockResolvedValue(structuredClone(baseResponse));

    renderDashboard();

    expect(await screen.findByText('Pending moderation')).toBeInTheDocument();
    expect(screen.getByText('12')).toBeInTheDocument();
    expect(screen.getByText('48')).toBeInTheDocument();
  });

  it('displays warnings surfaced by the API', async () => {
    const responseWithWarning = structuredClone(baseResponse);
    responseWithWarning.meta.warnings = ['STALE_DATA'];
    fetchDashboardMetricsMock.mockResolvedValue(responseWithWarning);

    renderDashboard();

    expect(
      await screen.findByText('Metrics may be out of date. Refresh to update.')
    ).toBeInTheDocument();
  });

  it('surfaces errors and allows retry', async () => {
    fetchDashboardMetricsMock
      .mockRejectedValueOnce(new Error('Network error'))
      .mockResolvedValueOnce(structuredClone(baseResponse));

    renderDashboard();

    expect(await screen.findByText('Unable to load dashboard metrics.')).toBeInTheDocument();
    expect(screen.getByText('Network error')).toBeInTheDocument();

    const retryButton = screen.getByRole('button', { name: /retry/i });
    await fireEvent.click(retryButton);

    expect(await screen.findByText('Pending moderation')).toBeInTheDocument();
    expect(fetchDashboardMetricsMock).toHaveBeenCalledTimes(2);
  });
});
