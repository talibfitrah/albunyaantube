import { computed, ref } from 'vue';
import { fetchDashboardMetrics } from '@/services/dashboard';
import type {
  ComparisonMetric,
  DashboardMetricsResponse,
  DashboardTimeframe
} from '@/types/dashboard';

interface BaseCard {
  id: 'pendingModeration' | 'categories' | 'moderators';
  titleKey: string;
  captionKey: string;
}

export interface ComparisonCard extends BaseCard {
  kind: 'comparison';
  metric: ComparisonMetric;
}

export interface CategoryCard extends BaseCard {
  kind: 'categories';
  total: number;
  newThisPeriod: number;
  previousTotal: number;
}

export type DashboardCard = ComparisonCard | CategoryCard;

export function useDashboardMetrics(initialTimeframe: DashboardTimeframe = 'LAST_24_HOURS') {
  const timeframe = ref<DashboardTimeframe>(initialTimeframe);
  const isLoading = ref(false);
  const error = ref<string | null>(null);
  const warnings = ref<string[]>([]);
  const response = ref<DashboardMetricsResponse | null>(null);

  async function fetchMetrics(targetTimeframe: DashboardTimeframe = timeframe.value): Promise<void> {
    isLoading.value = true;
    error.value = null;
    try {
      const metrics = await fetchDashboardMetrics(targetTimeframe);
      response.value = metrics;
      warnings.value = metrics.meta.warnings ?? [];
      timeframe.value = targetTimeframe;
    } catch (err) {
      if (err instanceof Error) {
        error.value = err.message;
      } else {
        error.value = 'Unknown error occurred.';
      }
    } finally {
      isLoading.value = false;
    }
  }

  function changeTimeframe(next: DashboardTimeframe): Promise<void> {
    return fetchMetrics(next);
  }

  const cards = computed<DashboardCard[]>(() => {
    if (!response.value) {
      return [];
    }
    const data = response.value.data;
    return [
      {
        id: 'pendingModeration',
        kind: 'comparison',
        titleKey: 'dashboard.cards.pendingModeration',
        captionKey: 'dashboard.cards.pendingModerationCaption',
        metric: data.pendingModeration
      },
      {
        id: 'categories',
        kind: 'categories',
        titleKey: 'dashboard.cards.categories',
        captionKey: 'dashboard.cards.categoriesCaption',
        total: data.categories.total,
        newThisPeriod: data.categories.newThisPeriod,
        previousTotal: data.categories.previousTotal
      },
      {
        id: 'moderators',
        kind: 'comparison',
        titleKey: 'dashboard.cards.moderators',
        captionKey: 'dashboard.cards.moderatorsCaption',
        metric: data.moderators
      }
    ];
  });

  const lastUpdated = computed(() => response.value?.meta.generatedAt ?? null);

  return {
    cards,
    timeframe,
    isLoading,
    error,
    warnings,
    lastUpdated,
    meta: computed(() => response.value?.meta ?? null),
    refresh: fetchMetrics,
    changeTimeframe
  };
}
