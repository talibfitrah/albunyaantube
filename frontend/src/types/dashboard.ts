export type DashboardTimeframe = 'LAST_24_HOURS' | 'LAST_7_DAYS' | 'LAST_30_DAYS';

export type TrendDirection = 'UP' | 'DOWN' | 'FLAT';

export interface ComparisonMetric {
  current: number;
  previous: number;
  trend: TrendDirection;
  thresholdBreached?: boolean;
}

export interface CategoryMetric {
  total: number;
  newThisPeriod: number;
  previousTotal: number;
}

export interface DashboardMetrics {
  pendingModeration: ComparisonMetric;
  categories: CategoryMetric;
  moderators: ComparisonMetric;
}

export interface DashboardMetricsMeta {
  generatedAt: string;
  timeRange: {
    start: string;
    end: string;
    label: DashboardTimeframe;
  };
  cacheTtlSeconds: number;
  warnings?: string[];
}

export interface DashboardMetricsResponse {
  data: DashboardMetrics;
  meta: DashboardMetricsMeta;
}
