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
- **Filters**: Pinia store + component tests validate shared registry search filters, including video length/date/sort parameters and debounced text queries propagating across tabs.
- **E2E**: Playwright (see `frontend/tests/e2e`) intercepts API traffic to exercise moderation approval, exclusions editing (search → update → bulk delete → add), audit pagination, and blended search/import flows. Accessibility assertions run via `@axe-core/playwright`. Execute locally with `npm run build && npm run test:e2e` inside `frontend/`.
- **Admin Accounts** (Phase 4, 2025-10-04): `frontend/tests/UsersManagementView.spec.ts` and `frontend/tests/AuditLogView.spec.ts` mock the admin endpoints to validate create/edit/deactivate flows, role/status toggles, and audit metadata formatting. Playwright scenario `frontend/tests/e2e/users-and-audit.e2e.spec.ts` covers login → invite → deactivate → audit verification with axe checks.
- **Android Navigation Skeleton** (Phase 5, 2025-10-04): `android/app/src/main/res/navigation/app_nav_graph.xml` and the instrumentation stub in `android/app/src/androidTest/.../NavigationGraphTest.kt` define acceptance criteria for Splash → Onboarding → Main shell transitions and bottom nav state retention. Once the module is scaffolded, this test will verify that `NavController` contains the four primary destinations and that tab reselection restores scroll position via saved state handles.
- **Android Onboarding Carousel** (Phase 5, 2025-10-04): Stub layout `fragment_onboarding.xml` captures carousel height, indicator spacing, CTA, and skip actions. Future instrumentation should verify: (1) slide count matches localization assets, (2) help modal announces localized body text to TalkBack, and (3) onboarding completion writes the DataStore flag used in navigation guard tests.
- **Android Locale Switcher** (Phase 5, 2025-10-04): `fragment_locale_settings.xml` + `LocaleManager` sketch outline the in-app language picker. Planned tests: unit test ensuring `LocaleManager.detectDefaultLocale()` honors system locale order; instrumentation verifying dropdown shows translated labels, applying a new locale triggers `AppCompatDelegate.setApplicationLocales`, and RTL mirroring occurs for Arabic.
- **Android Paging** (Phase 6 prep, 2025-10-04): `android/app/src/main/java/com/albunyaan/tube/data/paging` contains repository/source stubs. Future tests include PagingSource unit tests with fake backend cursors, RemoteMediator integration with Room to measure cache hit ratio, and macro-benchmarks validating list scroll smoothness with cached vs. network data.
- **Android Filter Controls** (Phase 6 prep, 2025-10-05): `FilterManager` now persists selections via DataStore while exposing a `StateFlow` for UI bindings. Bottom sheet single-choice pickers replace temporary alert dialogs. Planned coverage: unit tests confirming filter combinations trigger expected state transitions, integration tests simulating chip interactions + bottom sheet selections, instrumentation ensuring TalkBack announces filter changes, the clear button resets state, and process-death tests verifying selections survive restarts while focus returns to the triggering chip.
- **Android Error/Loading States** (Phase 6 prep, 2025-10-05): `view_list_state.xml` and `view_list_footer.xml` capture skeleton/error/empty/footer UI. Test plan includes Espresso tests toggling state transitions, offline simulation verifying cached data messaging, verifying clear-filters CTA visibility when filters active, and asserting Logcat metrics events (`load_success`, `load_empty`, `load_error`, `retry`, `clear_filters`). Performance measurements still track skeleton display duration.
- **Android Channel Detail Tabs** (Phase 7 prep, 2025-10-05): ViewPager2 scaffold with five tabs (Videos/Live/Shorts/Playlists/Posts). Planned tests: fragment state retention across tab + back navigation, placeholder copy fallback when data absent, and analytics hooks once real endpoints wire in.
- **Android Playlist Detail** (Phase 7 prep, 2025-10-05): Hero + download CTA scaffold. Planned tests: verify CTA text/state per download policy enum, ensure queued/disabled states disable button, confirm metadata renders, and once backend integrated, add contract tests for policy flag + localized copy.
- **Android Deep Links & Exclusions** (Phase 7 prep, 2025-10-05): Intent filters for `albunyaantube://channel/{id}` and `/playlist/{id}` landed. Tests should cover deep link navigation into detail screens, verifying exclusion banner visibility when the `excluded` flag is true, and ensuring download CTA disables when excluded.
- **Android Player Core** (Phase 8 prep, 2025-10-05): ExoPlayer scaffold + audio-only toggle. Planned tests: unit tests toggling state in `PlayerViewModel`, instrumentation ensuring toggle updates UI and that deep link navigation to player retains state across configuration changes. Once streams available, add track-selection and buffering analytics assertions.
- **Android Player MediaSession** (Phase 8 prep, 2025-10-05): Foreground service + MediaSession scaffold. Tests should verify notification channel presence, MediaButton intents routed to the service, PiP entry/exit behavior, and that background playback respects `FOREGROUND_SERVICE` policies once real media plays.
- **Android Player Up Next** (Phase 8, 2025-10-06): `PlayerViewModel` now exposes an Up Next queue with exclusion filtering, analytics events for start/complete/toggle, and a unit test suite (`PlayerViewModelTest`) covering queue ordering + duplicate guard rails. Future instrumentation will validate UI rendering, TalkBack announcements, and analytics payload emission after backend integration.
- **Android Metadata Hydration** (Phase 8, 2025-10-06): `MetadataHydratorTest` exercises NewPipeExtractor scaffolding for list feeds, ensuring Albunyaan overrides win when set and extractor failures fall back without breaking Paging flows. Later instrumentation will simulate extractor latency + cache reuse.
- **i18n**: Snapshot tests verifying ar/nl translations, directionality (RTL snapshots).
- **Accessibility**: axe-core integration ensures WCAG AA; coverage includes skip-to-content focus target, keyboard traversal for locale switcher/search/table controls, and dialog focus traps (moderation reject, exclusions CRUD). Document violations in the Phase 4 accessibility log and re-test after fixes land.

### Admin Dashboard Metrics
- **Contract**: Validate `/admin/dashboard` responses against `admin-dashboard-metrics-response.json` using OpenAPI schema checks in CI.
- **Unit**: `frontend/tests/useDashboardMetrics.spec.ts` and `frontend/tests/DashboardView.spec.ts` mock the service to assert loading → success/error transitions, warning propagation, and localized number formatting.
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
