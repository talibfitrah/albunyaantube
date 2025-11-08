<template>
  <section class="panel">
    <header>
      <h1>{{ t('dashboard.heading') }}</h1>
      <p>{{ t('dashboard.subtitle') }}</p>
    </header>

    <div class="dashboard-toolbar">
      <div class="timeframe" role="radiogroup" :aria-label="t('dashboard.timeframe.label')">
        <span class="timeframe-label">{{ t('dashboard.timeframe.label') }}</span>
        <div class="timeframe-options">
          <button
            v-for="option in timeframeOptions"
            :key="option.value"
            type="button"
            class="timeframe-button"
            :class="{ active: option.value === timeframe }"
            role="radio"
            :aria-checked="option.value === timeframe"
            @click="handleTimeframe(option.value)"
          >
            {{ t(option.labelKey) }}
          </button>
        </div>
      </div>
      <div v-if="lastUpdatedLabel" class="last-updated">
        {{ t('dashboard.lastUpdated', { timestamp: lastUpdatedLabel }) }}
      </div>
    </div>

    <div v-if="warningMessages.length" class="warning-banner" role="alert">
      <ul>
        <li v-for="message in warningMessages" :key="message">{{ message }}</li>
      </ul>
    </div>

    <ErrorRetry
      v-if="errorMessage"
      :title="t('dashboard.error.title')"
      :message="errorMessage"
      :loading="isLoading"
      @retry="handleRetry"
    />

    <div v-else>
      <div v-if="isLoading && !cards.length" class="cards cards-skeleton">
        <article v-for="index in 3" :key="index" class="card card-skeleton" aria-hidden="true">
          <div class="skeleton-line short"></div>
          <div class="skeleton-line"></div>
          <div class="skeleton-line thin"></div>
        </article>
      </div>

      <div v-else class="cards" aria-live="polite">
        <article class="card" v-for="card in cards" :key="card.id">
          <div class="card-title">{{ t(card.titleKey) }}</div>
          <div class="card-value">{{ card.value }}</div>
          <div class="card-caption">{{ t(card.captionKey) }}</div>
          <ul class="card-meta">
            <li v-for="line in card.meta" :key="line">{{ line }}</li>
          </ul>
          <span v-if="card.threshold" class="card-warning">{{ t('dashboard.cards.thresholdBreached') }}</span>
        </article>
      </div>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted } from 'vue';
import { useI18n } from 'vue-i18n';
import { useDashboardMetrics } from '@/composables/useDashboardMetrics';
import type { DashboardCard } from '@/composables/useDashboardMetrics';
import type { DashboardTimeframe } from '@/types/dashboard';
import { formatDateTime, formatNumber } from '@/utils/formatters';
import ErrorRetry from '@/components/common/ErrorRetry.vue';

const { t, locale } = useI18n();
const metrics = useDashboardMetrics();

const currentLocale = computed(() => locale.value);

const timeframeOptions: Array<{ value: DashboardTimeframe; labelKey: string }> = [
  { value: 'LAST_24_HOURS', labelKey: 'dashboard.timeframe.last24h' },
  { value: 'LAST_7_DAYS', labelKey: 'dashboard.timeframe.last7' },
  { value: 'LAST_30_DAYS', labelKey: 'dashboard.timeframe.last30' }
];

const timeframe = computed(() => metrics.timeframe.value);

const cards = computed(() => buildDisplayCards(metrics.cards.value, currentLocale.value));

const warningMessages = computed(() => {
  if (!metrics.warnings.value.length) {
    return [] as string[];
  }
  return metrics.warnings.value.map((warning) => {
    if (warning === 'STALE_DATA') {
      return t('dashboard.warnings.stale');
    }
    return warning;
  });
});

const lastUpdatedLabel = computed(() => {
  const timestamp = metrics.lastUpdated.value;
  if (!timestamp) {
    return null;
  }
  return formatDateTime(timestamp, currentLocale.value);
});

const isLoading = computed(() => metrics.isLoading.value);
const errorMessage = computed(() => metrics.error.value);

function formatPercent(value: number, localeCode: string): string {
  const formatter = new Intl.NumberFormat(localeCode, {
    style: 'percent',
    maximumFractionDigits: 1
  });
  return formatter.format(value);
}

function buildDisplayCards(source: DashboardCard[], localeCode: string) {
  return source.map((card) => {
    if (card.kind === 'comparison') {
      const current = formatNumber(card.metric.current, localeCode);
      const previous = formatNumber(card.metric.previous, localeCode);
      const diff = card.metric.current - card.metric.previous;
      const base = card.metric.previous > 0 ? Math.abs(diff / card.metric.previous) : card.metric.current > 0 ? 1 : 0;
      const percent = formatPercent(base, localeCode);
      const trendKey = card.metric.trend === 'UP'
        ? 'dashboard.cards.deltaUp'
        : card.metric.trend === 'DOWN'
          ? 'dashboard.cards.deltaDown'
          : 'dashboard.cards.deltaFlat';
      const meta = [
        card.metric.trend === 'FLAT'
          ? t('dashboard.cards.deltaFlat')
          : t(trendKey, { value: percent }),
        t('dashboard.cards.previousValue', { value: previous })
      ];
      return {
        id: card.id,
        titleKey: card.titleKey,
        captionKey: card.captionKey,
        value: current,
        meta,
        threshold: Boolean(card.metric.thresholdBreached)
      };
    }

    if (card.kind === 'validation') {
      const metric = card.metric;
      const checked = formatNumber(metric.videosChecked, localeCode);
      const unavailable = formatNumber(metric.videosMarkedUnavailable, localeCode);
      const errors = formatNumber(metric.validationErrors, localeCode);

      const statusKey = metric.status === 'NEVER_RUN'
        ? 'dashboard.cards.validationNeverRun'
        : metric.status === 'RUNNING'
        ? 'dashboard.cards.validationRunning'
        : metric.status === 'FAILED'
        ? 'dashboard.cards.validationFailed'
        : metric.status === 'ERROR'
        ? 'dashboard.cards.validationError'
        : 'dashboard.cards.validationCompleted';

      const lastRun = metric.lastRunAt
        ? formatDateTime(metric.lastRunAt, localeCode)
        : t('dashboard.cards.validationNever');

      return {
        id: card.id,
        titleKey: card.titleKey,
        captionKey: card.captionKey,
        value: checked,
        meta: [
          t(statusKey),
          t('dashboard.cards.validationLastRun', { time: lastRun }),
          t('dashboard.cards.validationUnavailable', { count: unavailable }),
          t('dashboard.cards.validationErrors', { count: errors })
        ],
        threshold: metric.validationErrors > 0 || metric.status === 'FAILED'
      };
    }

    const total = formatNumber(card.total, localeCode);
    const newCount = formatNumber(card.newThisPeriod, localeCode);
    const previous = formatNumber(card.previousTotal, localeCode);
      return {
        id: card.id,
        titleKey: card.titleKey,
        captionKey: card.captionKey,
        value: total,
        meta: [
          t('dashboard.cards.categoriesNewThisPeriod', { count: newCount }),
          t('dashboard.cards.categoriesPreviousTotal', { count: previous })
        ],
        threshold: false
      };
  });
}

function handleTimeframe(next: DashboardTimeframe) {
  if (next === timeframe.value && metrics.cards.value.length) {
    return;
  }
  metrics.changeTimeframe(next);
}

function handleRetry() {
  metrics.refresh();
}

onMounted(() => {
  metrics.refresh();
});
</script>

<style scoped>
.panel {
  display: flex;
  flex-direction: column;
  gap: 2rem;
}

header h1 {
  margin: 0;
  font-size: 2rem;
  font-weight: 700;
  color: var(--color-text-primary);
  letter-spacing: -0.02em;
}

header p {
  margin: 0.75rem 0 0;
  color: var(--color-text-secondary);
  font-size: 0.9375rem;
}

.dashboard-toolbar {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  gap: 1rem;
  align-items: center;
}

.timeframe {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.timeframe-label {
  font-size: 0.9rem;
  color: var(--color-text-secondary);
}

.timeframe-options {
  display: flex;
  gap: 0.5rem;
  flex-wrap: wrap;
}

.timeframe-button {
  appearance: none;
  border: 1px solid rgba(0, 0, 0, 0.1);
  background: transparent;
  color: var(--color-text-primary);
  padding: 0.35rem 0.85rem;
  border-radius: 999px;
  font-size: 0.875rem;
  cursor: pointer;
  transition: background 0.2s ease;
  -webkit-tap-highlight-color: transparent;
  min-height: 36px;
}

@media (hover: hover) {
  .timeframe-button:hover {
    background: var(--color-surface-alt);
  }
}

.timeframe-button:focus-visible {
  background: var(--color-surface-alt);
}

.timeframe-button.active {
  background: var(--color-brand);
  color: var(--color-text-inverse);
  border-color: var(--color-brand);
}

.last-updated {
  font-size: 0.9rem;
  color: var(--color-text-secondary);
}

.warning-banner {
  background: rgba(255, 196, 0, 0.18);
  border: 1px solid rgba(255, 196, 0, 0.45);
  border-radius: 0.75rem;
  padding: 1rem;
  color: var(--color-text-primary);
}

.warning-banner ul {
  margin: 0;
  padding-left: 1.25rem;
}

.error-panel {
  background: rgba(217, 45, 32, 0.15);
  border: 1px solid rgba(217, 45, 32, 0.35);
  border-radius: 0.75rem;
  padding: 1.25rem;
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}

.error-detail {
  margin: 0;
  color: var(--color-text-secondary);
  font-size: 0.9rem;
}

.retry {
  align-self: flex-start;
  appearance: none;
  border: none;
  background: var(--color-brand);
  color: var(--color-text-inverse);
  padding: 0.5rem 1rem;
  border-radius: 0.5rem;
  cursor: pointer;
  font-weight: 600;
  -webkit-tap-highlight-color: transparent;
  min-height: 44px;
}

@media (hover: hover) {
  .retry:hover {
    background: var(--color-accent);
  }
}

.retry:focus-visible {
  background: var(--color-accent);
}

.cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  gap: 1.5rem;
}

.card {
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  color: var(--color-text-primary);
  border-radius: 0.75rem;
  padding: 1.75rem;
  display: flex;
  flex-direction: column;
  gap: 0.875rem;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.05);
  transition: all 0.2s ease;
}

@media (hover: hover) {
  .card:hover {
    box-shadow: 0 4px 12px rgba(0, 0, 0, 0.08);
    transform: translateY(-2px);
    border-color: var(--color-brand);
  }
}

.card-title {
  font-size: 0.8125rem;
  text-transform: uppercase;
  letter-spacing: 0.05em;
  color: var(--color-text-secondary);
  font-weight: 600;
}

.card-value {
  font-size: 2.25rem;
  font-weight: 700;
  color: var(--color-brand);
  line-height: 1.2;
}

.card-caption {
  font-size: 0.875rem;
  color: var(--color-text-secondary);
}

.card-meta {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  font-size: 0.8125rem;
  color: var(--color-text-secondary);
  padding-top: 0.5rem;
  border-top: 1px solid var(--color-border);
}

.card-warning {
  align-self: flex-start;
  font-size: 0.75rem;
  text-transform: uppercase;
  letter-spacing: 0.08em;
  background: rgba(217, 45, 32, 0.2);
  color: var(--color-text-inverse);
  padding: 0.25rem 0.5rem;
  border-radius: 0.5rem;
}

.cards-skeleton .card {
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  color: transparent;
}

.cards-skeleton .card:hover {
  transform: none;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.05);
}

.card-skeleton {
  position: relative;
  overflow: hidden;
}

.skeleton-line {
  height: 1.25rem;
  border-radius: 0.5rem;
  background: var(--color-surface-alt);
}

.skeleton-line.short {
  width: 40%;
}

.skeleton-line.thin {
  height: 0.75rem;
  width: 60%;
}

.card-skeleton::after {
  content: '';
  position: absolute;
  inset: 0;
  background: linear-gradient(90deg, transparent 0%, rgba(22, 131, 90, 0.08) 50%, transparent 100%);
  transform: translateX(-100%);
  animation: shimmer 1.5s infinite;
}

@keyframes shimmer {
  100% {
    transform: translateX(100%);
  }
}

/* Mobile/Tablet Responsive */
@media (max-width: 1023px) {
  .panel {
    gap: 1.5rem;
  }

  header h1 {
    font-size: 1.75rem;
  }

  header p {
    font-size: 0.875rem;
  }

  .dashboard-toolbar {
    flex-direction: column;
    align-items: stretch;
    gap: 1rem;
  }

  .timeframe {
    gap: 0.75rem;
  }

  .timeframe-options {
    gap: 0.625rem;
  }

  .timeframe-button {
    flex: 1;
    min-width: 0;
    min-height: 44px;
    padding: 0.625rem 0.75rem;
    font-size: 0.875rem;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .last-updated {
    text-align: center;
    padding: 0.5rem;
    background: var(--color-surface-alt);
    border-radius: 0.5rem;
  }

  .cards {
    grid-template-columns: 1fr;
    gap: 1.25rem;
  }

  .card {
    padding: 1.5rem;
  }

  .retry {
    width: 100%;
    align-self: stretch;
  }
}

@media (max-width: 767px) {
  header h1 {
    font-size: 1.5rem;
  }

  .card-value {
    font-size: 2rem;
  }
}
</style>
