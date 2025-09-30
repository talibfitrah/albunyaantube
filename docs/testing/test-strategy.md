# Test Strategy

This strategy spans backend, admin frontend, and Android client. It complements the architecture plan in [`../architecture/solution-architecture.md`](../architecture/solution-architecture.md) and acceptance criteria in [`../acceptance/criteria.md`](../acceptance/criteria.md).

## Guiding Principles
- Automated coverage for every acceptance criterion prior to release.
- Use real dependencies via Testcontainers for backend persistence.
- Localized fixtures for en/ar/nl to verify translations.
- Security regression tests enforce JWT rotation, blacklist, and RBAC.

## Backend Testing
| Layer | Tooling | Scope |
| --- | --- | --- |
| Unit | JUnit 5, Mockito | Service-level logic (3-latest rule, pagination parsing, download policy enforcement). |
| Integration | Spring Boot Test + Testcontainers (PostgreSQL, Redis) | Auth flows, repository queries, caching invalidation, Accept-Language fallbacks. |
| Contract | Spring Cloud Contract / OpenAPI schema validation | Ensure responses match schemas in [`../data/json-schemas`](../data/json-schemas). |
| Performance | Gatling | Verify list endpoints deliver ≤80KB payload per page and <150ms server time at 200 RPS. |
| Security | OWASP ZAP, dependency scanning | Check for common vulnerabilities, enforce TLS. |

### Error Taxonomy
- `CLIENT_ERROR` (4xx) with localization.
- `SERVER_ERROR` (5xx) with traceId.
- `POLICY_BLOCK` for download-disabled scenarios.

## Admin Frontend Testing
- **Unit**: Vitest for components; ensure tokens from [`../ux/design-tokens.json`](../ux/design-tokens.json) applied. Moderation queue spec covers approve/reject flows, audit hook emission, and reject modal focus traps.
- **E2E**: Playwright hitting staging backend mock; scenarios include moderation approval, exclusions editing, audit pagination, and blended search/import flows (single-surface results, bulk include/exclude).
- **i18n**: Snapshot tests verifying ar/nl translations, directionality (RTL snapshots).
- **Accessibility**: axe-core integration ensures WCAG AA; reject confirmation modal enforces focus loop + Escape handling in coverage checklist.

### Admin Dashboard Metrics
- **Contract**: Validate `/admin/dashboard` responses against `admin-dashboard-metrics-response.json` using OpenAPI schema checks in CI.
- **Unit**: `useDashboardMetrics` composable tests mock API client to assert loading → success/error transitions and localization helpers.
- **E2E**: Playwright scenario loads dashboard with mocked API delays to capture skeleton state, retries, and warning toast when backend flags stale data.
- **Observability**: Smoke test hits metrics endpoint and asserts Prometheus counter `admin.dashboard.generated` increments with `cache_hit` label.

## Android Testing
- **Unit**: JUnit + Mockito for ViewModels/use cases.
- **Instrumentation**: Espresso for navigation flows (onboarding, bottom nav, filters).
- **Paging**: Paging 3 test helpers verifying cursor handoff.
- **Download Service**: WorkManager test harness to validate pause/resume, quota enforcement.
- **Localization**: Locale-specific screenshot tests using Paparazzi.

### Player Reliability
- **Scope**: End-to-end playback stability covering buffering recovery, audio-only fallback, and PiP transitions for the Phase 8 rollout.
- **Tooling**: Robolectric for state-machine validation, Firebase Test Lab/device farm for real hardware scenarios, and ExoPlayer analytics assertions.
- **Release Gate**: Require <1% playback crash rate and automated regression suites for background playback before enabling new player features.

## Test Data Management
- Seed data via Flyway migrations for categories + sample content (Phase 1 exit criteria).
- Use synthetic thumbnails stored locally for tests; avoid hitting YouTube.
- Redact any production data; use environment variables for secrets.

## Observability Validation
- Assert structured logs contain `traceId`, `userId`, `locale` fields.
- Metrics tests verify custom counters (`downloads_started`, `moderation_pending`).
- Alerting runbook stored in ops documentation (future `/ops` folder).

## Performance Metrics
- Track p95 API latency (<150ms) and payload size (<80KB) during Gatling runs; fail build when thresholds exceeded.
- Monitor Crashlytics crash-free sessions (≥99%) and ANR rate (<0.5%) before promoting Android releases.
- Compare Redis cache hit ratio (>85%) per locale to ensure caching strategy effectiveness.

## Release Management
- Staging environment smoke tests triggered by CI pipeline.
- Beta rollout gating on crash-free sessions ≥99% (Firebase Crashlytics).
- Rollback plan: blue/green deployment for backend; Play Store staged rollout for Android.

## Traceability
- Test cases map to acceptance criteria IDs (see [`../acceptance/criteria.md`](../acceptance/criteria.md)).
- Performance budgets derived from vision metrics (see [`../vision/vision.md`](../vision/vision.md#success-metrics)).
