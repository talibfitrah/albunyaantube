# Admin Dashboard Metrics Consumption Plan

## Goal
Coordinate the admin dashboard cards with the `/admin/dashboard` backend contract so that pending moderation, category totals, and active moderator counts render with reliable loading, error, and retry behavior.

## Data Contract
```ts
// Generated via openapi-typescript from docs/api/openapi-draft.yaml
export interface AdminDashboardMetricsResponse {
  data: {
    pendingModeration: ComparisonMetric;
    categories: {
      total: number;
      newThisPeriod: number;
      previousTotal: number;
    };
    moderators: ComparisonMetric;
  };
  meta: {
    generatedAt: string; // ISO timestamp
    timeRange: {
      start: string;
      end: string;
      label: 'LAST_24_HOURS' | 'LAST_7_DAYS' | 'LAST_30_DAYS';
    };
    cacheTtlSeconds: number; // expect 60s baseline, max 900s fallback
    warnings?: string[];
  };
}

interface ComparisonMetric {
  current: number;
  previous: number;
  trend: 'UP' | 'DOWN' | 'FLAT';
  thresholdBreached?: boolean; // true when SLA (48h) breached
}
```

## Service Layer
- **File**: `frontend/src/services/dashboard.ts` (new).
- **Responsibility**: wrap `apiClient.get('/admin/dashboard', { params: { timeframe } })` using shared Axios instance.
- **Exports**:
  - `fetchDashboardMetrics(timeframe?: DashboardTimeframe): Promise<AdminDashboardMetricsResponse>`
  - `type DashboardTimeframe = 'LAST_24_HOURS' | 'LAST_7_DAYS' | 'LAST_30_DAYS'`
- **Error handling**: throw `ApiError` with `problemDetail` from backend; calling composable converts to UI-friendly message via i18n key `errors.dashboard.load`.
- **Caching**: rely on HTTP `Cache-Control: max-age=60` (documented in backend); do not memoize client-side to keep numbers real-time after refresh.

## Composable
- **File**: `frontend/src/composables/useDashboardMetrics.ts` (new).
- **State machine**:
  1. `idle` → invoked on Dashboard route mount.
  2. `loading` → triggered by `fetchDashboardMetrics`.
  3. `success` with normalized cards when response resolves.
  4. `error` with retry CTA when request fails or backend sets `warnings` that include `"STALE_DATA"`.
- **Returns**:
  ```ts
  {
    cards: Ref<DashboardCard[]>;
    isLoading: Ref<boolean>;
    error: Ref<DashboardError | null>;
    timeframe: Ref<DashboardTimeframe>;
    refresh: (overrideTimeframe?: DashboardTimeframe) => Promise<void>;
  }
  ```
- **Normalization**:
  - Convert `pendingModeration` into card with digits localized via `useI18n().n`.
  - `thresholdBreached` toggles warning badge token (`surface-warning-soft`).
  - Format delta percentage = `(current - previous) / max(previous, 1)` for tooltip.
  - Expose `meta.warnings` separately so view can show toast (Pinia notification store).

## Dashboard View Wiring
- Inject composable inside `DashboardView.vue` setup function.
- Replace placeholder card array with computed result from composable.
- Display skeleton state using existing `AppCard` + `content-loading` token (reuse from registry pages).
- Error state uses `AppErrorPanel` with `dashboard.error.title`/`dashboard.error.cta` strings; CTA triggers `refresh()`.
- Add timeframe filter chip group (default `LAST_24_HOURS`); hooking into composable `refresh` with selected timeframe.

## Accessibility & Localization
- Cards use `aria-live="polite"` to announce refreshed numbers.
- Provide i18n messages for delta tooltips (`dashboard.cards.delta.up`, `.down`, `.flat`).
- Localize digits via existing `NumberFormat` helper; ensure Arabic Indic numerals for `ar`.
- Use tokenized colors (`text-success`, `text-danger`) for trend indicators to comply with `AC-A11Y-002` contrast requirements.

## Testing Plan
- **Unit (Vitest)**: mock `apiClient` to cover success, error, warning paths in `useDashboardMetrics`. Snapshot normalized payload to guard against contract drift.
- **Contract**: add schema validation step in backend pipeline referencing `admin-dashboard-metrics-response.json`.
- **E2E (Playwright)**: simulate slow backend to verify skeleton + error fallback, record screenshot for design review.
- **Monitoring**: assert `admin.dashboard.generated` metric present in observability smoke tests (see docs/testing/test-strategy.md update).

## Open Questions / Follow-ups
- Do we expose additional KPIs (e.g., top moderation categories)? Capture in backlog if stakeholders request.
- Determine when to promote timeframe selector in UI spec (coordinate with design tokens for chip states).
