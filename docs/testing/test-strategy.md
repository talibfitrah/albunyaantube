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
- **Unit**: Vitest for components; ensure tokens from [`../ux/design-tokens.json`](../ux/design-tokens.json) applied.
- **E2E**: Playwright hitting staging backend mock; scenarios include moderation approval, exclusions editing, audit pagination.
- **i18n**: Snapshot tests verifying ar/nl translations, directionality (RTL snapshots).
- **Accessibility**: axe-core integration ensures WCAG AA.

## Android Testing
- **Unit**: JUnit + Mockito for ViewModels/use cases.
- **Instrumentation**: Espresso for navigation flows (onboarding, bottom nav, filters).
- **Playback Reliability** (Phase 8): Robolectric for state, device farm for PiP/background audio scenarios.
- **Paging**: Paging 3 test helpers verifying cursor handoff.
- **Download Service**: WorkManager test harness to validate pause/resume, quota enforcement.
- **Localization**: Locale-specific screenshot tests using Paparazzi.

## Test Data Management
- Seed data via Flyway migrations for categories + sample content (Phase 1 exit criteria).
- Use synthetic thumbnails stored locally for tests; avoid hitting YouTube.
- Redact any production data; use environment variables for secrets.

## Observability Validation
- Assert structured logs contain `traceId`, `userId`, `locale` fields.
- Metrics tests verify custom counters (`downloads_started`, `moderation_pending`).
- Alerting runbook stored in ops documentation (future `/ops` folder).

## Release Management
- Staging environment smoke tests triggered by CI pipeline.
- Beta rollout gating on crash-free sessions ≥99% (Firebase Crashlytics).
- Rollback plan: blue/green deployment for backend; Play Store staged rollout for Android.

## Traceability
- Test cases map to acceptance criteria IDs (see [`../acceptance/criteria.md`](../acceptance/criteria.md)).
- Performance budgets derived from vision metrics (see [`../vision/vision.md`](../vision/vision.md#success-metrics)).
