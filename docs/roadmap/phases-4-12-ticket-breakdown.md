# Phases 4–12 Ticket Breakdown

Execution Metadata
- Status: Planned
- Last reviewed: 2025-09-30
- Dependencies: Phase 3 Admin MVP exit
- Owners: TBD

This appendix extends the roadmap with ready-to-groom tickets for Phases 4 through 12. Each ticket adheres to the `Estimate → Goals → Propose diff → Tests → Implement → Reflect` template and references design artifacts only. When implementation begins, engineers can convert these tickets directly into user stories/backlog items.

## Phase 4 — Admin UI Complete

### ADMIN-COMP-01 — Exclusions Editor Experience
```yaml
meta:
  id: ADMIN-COMP-01
  status: done
  owner: Frontend
  depends: [ADMIN-MVP-06]
  lastReviewed: 2025-10-04
```
**Estimate**: 4h.

**Goals**
- Deliver full exclusions CRUD workspace (list, filter, create, delete) per acceptance criteria AC-ADM-005.
- Ensure localized copy and RTL alignment for forms and tables.
- Capture audit log hooks for every mutation.

**Propose diff**
- `frontend/src/views/ExclusionsView.vue` (planned) + supporting form component.
- `docs/ux/ui-spec.md`: add detailed wireframes for exclusions flow.
- `docs/api/openapi-draft.yaml`: confirm `/exclusions` endpoints cover fields and errors.

**Tests**
- Component/unit tests for create/delete flows and optimistic updates.
- Accessibility audit covering keyboard navigation and screen reader labels.
- Contract tests verifying audit entries emitted.

**Implement**
- Design table schema (columns, filters) aligned with UI spec.
- Document backend coordination for audit logging + RBAC.
- Update backlog with follow-up for bulk import if requested.

**Reflect**
- Record moderator feedback to inform future enhancements.
- Log risks around exclusions scale (pagination, filtering) in risk register.

> **Next focus**: Begin ADMIN-COMP-02 implementation, replacing placeholder Users/Audit views with full CRUD + audit log UI as defined below.

### ADMIN-COMP-02 — Admin User Management & Audit Viewer
```yaml
meta:
  id: ADMIN-COMP-02
  status: done
  owner: Frontend
  depends: [BACK-PLAN-02]
  lastReviewed: 2025-10-04
```
**Estimate**: 4h.

**Goals**
- Provide admin user CRUD UI (create, edit roles, deactivate) with RBAC enforcement.
- Ship audit viewer table with cursor pagination and filters (actor, date range, action type).
- Align UI states with UX/accessibility guidelines.

**Propose diff**
- `frontend/src/views/UsersView.vue` and `frontend/src/views/AuditView.vue` (planned).
- `docs/api/openapi-draft.yaml`: ensure `/admin/users` and `/admin/audit` endpoints include required fields.
- `docs/testing/test-strategy.md`: add Playwright coverage for user/audit flows.

**Tests**
- Unit tests verifying role-based visibility of user management actions.
- Contract tests for audit pagination (cursor, limit, filters).
- Accessibility review (focus order, table semantics).

**Implement**
- Outline UI flows for invites/resets and audit filtering interactions.
- Document integration dependencies (email notifications, optional future) and backlog them.
- Update acceptance criteria mapping for AC-ADM-001/002.

**Reflect**
- Capture outstanding admin self-service needs (e.g., password reset) for later phases.
- Note data volume considerations for audit table (indexing, archiving).

> **Status update (2025-10-04)**: Admin user CRUD UI and audit viewer are under development; Playwright e2e coverage is being authored alongside service mocks.

### ADMIN-COMP-03 — Accessibility & Localization Hardening
```yaml
meta:
  id: ADMIN-COMP-03
  status: in-review
  owner: Product+Frontend
  depends: [ADMIN-MVP-07]
  lastReviewed: 2025-10-02
```
**Estimate**: 3h.

**Goals**
- Validate admin console meets WCAG AA and localization requirements before MVP exit.
- Document accessibility fixes (focus states, skip links, color contrast adjustments).
- Ensure Arabic RTL review complete with screenshots.

**Propose diff**
- `docs/ux/ui-spec.md`: add accessibility annotations for admin components ✅
- `docs/i18n/strategy.md`: expand admin QA checklist ✅
- `docs/testing/test-strategy.md`: document axe-core automation plan ✅

**Tests**
- Run axe-core/lighthouse accessibility checks on key screens (pending automation implementation).
- Localization QA walkthrough for en/ar/nl (scheduled with QA once automation lands).
- Contrast verification using design tokens (documented; execution pending design sign-off).

**Implement**
- Ticketize UI updates (focus rings, aria labels, skip links) referencing tokens — tracked via follow-up dev tasks.
- Capture screenshot set for localization QA — assign to QA once UI adjustments merged.
- Update backlog with recurring accessibility regression suite tasks — aligned via ADMIN-COMP-03 traceability entry.

**Reflect**
- Document remaining medium-risk issues and escalate to leadership if deferral required.
- Update risk register with residual accessibility/localization concerns.

## Phase 5 — Android Skeleton

### AND-SKEL-01 — Navigation Graph & Bottom Navigation
**Estimate**: 3.5h.

**Goals**
- Define single-activity Navigation Component graph for Splash → Onboarding → Main Shell.
- Implement bottom navigation with Home, Channels, Playlists, Videos tabs per UI spec.
- Preserve state per tab and handle back stack behavior.

**Propose diff**
- `android/app/src/main/res/navigation/app_nav_graph.xml` (planned) + menu resources.
- `docs/ux/ui-spec.md`: include navigation diagrams + bottom nav interactions.
- `docs/architecture/solution-architecture.md`: detail nav + state restoration strategy.

**Tests**
- Instrumentation test verifying tab switching retains scroll + state.
- Unit test for nav graph destination IDs.
- Accessibility check for TalkBack tab announcements.

**Implement**
- Outline fragments/composables per tab and data flow placeholders.
- Document navigation actions for deep links used in later phases.
- Add backlog tasks for analytics instrumentation of tab selection.

**Reflect**
- Record outstanding requirements (e.g., badge counts) for future phases.
- Update risk register if nav complexity introduces edge cases.

### AND-SKEL-02 — Onboarding Carousel & Help Modal
```yaml
meta:
  id: AND-SKEL-02
  status: done
  owner: Android
  depends: [AND-SKEL-01]
  lastReviewed: 2025-10-04
```
**Estimate**: 3h.

**Goals**
- Plan onboarding flow (slides, Next/Skip, help “?” modal) per mockups.
- Persist onboarding completion flag using DataStore.
- Ensure locale/RTL coverage for slides.

**Propose diff**
- `android/app/src/main/res/layout/fragment_onboarding.xml` (planned) + associated view model.
- `docs/ux/ui-spec.md`: specify copy, layout measurements, and help modal behavior.
- `docs/i18n/strategy.md`: add onboarding translation guidance.

**Tests**
- Instrumentation test verifying onboarding only shows once after completion.
- Localization test capturing screenshot per locale.
- Accessibility test for focus order and TalkBack descriptions.

**Implement**
- Define slide content (copy, assets) referencing UI spec + translation spreadsheet.
- Document DataStore schema for onboarding + locale preferences.
- Update backlog with analytics requirement for onboarding drop-off.

**Reflect**
- Note any additional onboarding content requests (e.g., privacy) for backlog.
- Capture risks around asset localization or animation performance.

### AND-SKEL-03 — Locale Switcher & RTL Foundation
```yaml
meta:
  id: AND-SKEL-03
  status: done
  owner: Android
  depends: [AND-SKEL-02]
  lastReviewed: 2025-10-04
```
**Estimate**: 2.5h.

**Goals**
- Specify in-app locale switcher placement and behavior (settings sheet).
- Ensure Android resources prepared for en/ar/nl with mirrored layouts.
- Align DataStore schema with admin locale strategy.

**Propose diff**
- `docs/i18n/strategy.md`: extend Android locale switcher details.
- `android/app/src/main/java/.../LocaleManager.kt` (planned) to apply locales without restart.
- `docs/testing/test-strategy.md`: add RTL test plan using Paparazzi/Instrumentation.

**Tests**
- Unit tests for LocaleManager applying locales.
- Instrumentation verifying RTL mirroring and numeric shaping.
- Performance check ensuring locale change does not trigger cold start regression.

**Implement**
- Outline UI flow for locale switcher (settings screen or overflow menu).
- Document resource requirements (fonts, strings) for localization team.
- Update backlog with follow-on tasks for analytics + telemetry.

**Reflect**
- Flag any platform limitations (pre-Android 13 locale APIs) to revisit.
- Synchronize with admin locale plan for consistent user experience.

## Phase 6 — Lists & Home Rules

### AND-LISTS-01 — Paging 3 Integration & Cache Strategy
```yaml
meta:
  id: AND-LISTS-01
  status: in-progress
  owner: Android
  depends: [AND-SKEL-03]
  lastReviewed: 2025-10-04
```
**Estimate**: 4h.

**Goals**
- Define Paging 3 usage across Home, Channels, Playlists, Videos tabs.
- Ensure global category filter interacts correctly with paging sources.
- Outline cache policy (Room + Disk) aligning with performance budgets.

**Propose diff**
- `docs/architecture/solution-architecture.md`: add paging architecture diagram + cache notes.
- `docs/testing/test-strategy.md`: include paging unit tests + benchmark plan.
- `docs/api/openapi-draft.yaml`: reaffirm cursor pagination schema + examples.

**Tests**
- Unit tests for paging sources using fake backend responses.
- Performance benchmarks measuring list render time + payload size compliance.
- Offline scenario tests verifying cached data usage.

**Implement**
- Outline repository interfaces and caching behavior for each tab.
- Document error handling + retry UX per UI spec.
- Update backlog with instrumentation tasks (cache hit ratio metrics).

**Reflect**
- Identify dependencies on backend caching TTL and note in risk register.
- Capture lessons for later phases (detail pages, player Up Next).

### AND-LISTS-02 — Filter Row & Query Controls
```yaml
meta:
  id: AND-LISTS-02
  status: in-progress
  owner: Android
  depends: [AND-LISTS-01]
  lastReviewed: 2025-10-04
```
**Estimate**: 3.5h.

**Goals**
- Specify filter row (Category, Length, Date, Popular) interactions across screens.
- Align UI states (expanded sheets, badges, reset) with design tokens.
- Ensure backend query parameters documented for each filter combination.

**Propose diff**
- `docs/ux/ui-spec.md`: add filter interaction tables + visuals.
- `docs/api/openapi-draft.yaml`: include query parameter documentation and validation rules.
- `docs/testing/test-strategy.md`: plan UI automation for filter permutations.

**Tests**
- Playwright-equivalent instrumentation (Android UI tests) covering filter usage.
- Localization tests verifying filter labels across locales.
- QA checklist for empty/skeleton states triggered by filters.

**Implement**
- Outline filter state machine (selected/unselected, reset) and interactions with paging.
- Document fallback messaging when filters yield no results.
- Update backlog with analytics instrumentation for filter usage.

**Reflect**
- Record potential need for saved filter presets (future enhancement).
- Note backend dependency for fast filtered queries.

### AND-LISTS-03 — Error & Loading States + Metrics
```yaml
meta:
  id: AND-LISTS-03
  status: in-progress
  owner: Android
  depends: [AND-LISTS-02]
  lastReviewed: 2025-10-04
```
**Estimate**: 2.5h.

**Goals**
- Define skeleton/empty/error UI states across list screens.
- Map error taxonomy to backend responses (CLIENT_ERROR, POLICY_BLOCK, etc.).
- Specify metrics to track list health (load failures, retry counts).

**Propose diff**
- `docs/ux/ui-spec.md`: add visual spec for skeletons/errors.
- `docs/testing/test-strategy.md`: include error handling test scenarios.
- `docs/acceptance/criteria.md`: expand AC-PERF-001/AC-AND-001 references.

**Tests**
- Unit/instrumentation tests simulating network failures + retries.
- Monitoring dry run verifying metrics emitted per error type.
- Localization checks for error messages.

**Implement**
- Document error handling strategy including offline messaging.
- Outline analytics counters for retries, cache hits, and user-initiated refresh.
- Update backlog with instrumentation + alerting tasks.

**Reflect**
- Feed learnings into Phase 10 performance hardening plan.
- Update risk register if error handling requires additional backend support.

## Phase 7 — Channel & Playlist Details

### AND-DETAILS-01 — Channel Tabs & State Retention
**Estimate**: 3h.

**Goals**
- Define tabbed layout (Videos/Live/Shorts/Playlists/Posts) with lazy loading.
- Ensure state retention when switching tabs and returning from detail screens.
- Document analytics + acceptance criteria for tab engagement.

**Propose diff**
- `docs/ux/ui-spec.md`: add channel detail layout + tab states.
- `docs/api/openapi-draft.yaml`: confirm tab-specific endpoints and fields.
- `docs/testing/test-strategy.md`: plan test matrix for tab switching.

**Tests**
- Instrumentation verifying state preserved across tab switches.
- Performance measurement for initial load vs subsequent loads.
- Localization + accessibility checks on tab labels.

**Implement**
- Outline fragment/viewmodel responsibilities per tab.
- Document caching vs live fetch strategy for tab data.
- Update backlog with analytics instrumentation tasks.

**Reflect**
- Identify data dependencies (live counts) for backend team.
- Update risk register with potential load/performance issues for multi-tab fetches.

### AND-DETAILS-02 — Playlist Detail & Download CTA Rules
**Estimate**: 2.5h.

**Goals**
- Specify playlist hero layout, video list styling, and download CTA states.
- Define policy gating for downloads (enabled/disabled/queued) referencing AC-DL-001.
- Document offline availability indicators.

**Propose diff**
- `docs/ux/ui-spec.md`: expand playlist detail spec + CTA states.
- `docs/architecture/solution-architecture.md`: describe download policy flag evaluation.
- `docs/testing/test-strategy.md`: add test coverage for download button states.

**Tests**
- UI tests verifying CTA states across policy scenarios.
- Contract tests ensuring backend delivers policy flag + localized messaging.
- Localization QA for hero descriptions and CTA copy.

**Implement**
- Map backend fields to UI state machine (e.g., `downloadPolicy`).
- Document analytics for playlist downloads (initiate, cancel, success).
- Update backlog with offline storage tasks for playlist metadata.

**Reflect**
- Capture open questions about partial download progress display.
- Note backend dependency for aggregated download progress endpoint.

### AND-DETAILS-03 — Deep Links & Exclusions Enforcement
**Estimate**: 3h.

**Goals**
- Plan deep link routes into channel/playlist/video details from notifications or share links.
- Ensure exclusions are enforced (filtered items hidden, messaging shown).
- Provide error handling when excluded content accessed via deep link.

**Propose diff**
- `docs/architecture/solution-architecture.md`: add deep link flow + exclusion enforcement notes.
- `docs/api/openapi-draft.yaml`: confirm exclusion flags available on endpoints.
- `docs/acceptance/criteria.md`: capture new acceptance criteria for exclusions.

**Tests**
- Instrumentation tests covering deep link navigation and exclusion behavior.
- Backend contract tests verifying excluded content not returned.
- Localization QA for exclusion messaging.

**Implement**
- Outline intent filters/nav graph destinations for deep links.
- Document fallback UI for excluded content (toast/dialog) referencing UX spec.
- Update backlog with analytics for exclusion hits.

**Reflect**
- Note policy workflows for updating exclusions and required notifications.
- Update risk register if deep link collisions identified.

## Phase 8 — Player & Background Audio

### AND-PLAYER-01 — Core Playback & Audio-Only Toggle
**Estimate**: 4h.

**Goals**
- Define ExoPlayer setup, stream selection (video/audio), and audio-only toggle behavior.
- Ensure captions/quality selector UI spec implemented.
- Align buffering and error UI with design tokens.

**Propose diff**
- `docs/architecture/solution-architecture.md`: expand playback engine section.
- `docs/architecture/diagrams/player-session.md` (planned) illustrating playback handshake + audio-only flow.
- `docs/api/openapi-draft.yaml`: finalize `/videos/{id}` payload (streams, captions, bookmarks).

**Tests**
- Unit/instrumentation tests toggling audio-only mid-playback.
- ExoPlayer analytics verifying track switches succeed.
- Localization/accessibility tests for captions + controls.

**Implement**
- Outline service architecture (player manager, repository) and state diagram.
- Document fallback/resume scenarios for network interruptions.
- Update acceptance criteria AC-AND-003 with detailed scenarios.

**Reflect**
- Note open questions around DRM/licensing; add to risk register.
- Capture metrics requirements (rebuffer ratio, playback errors) for Phase 10.

### AND-PLAYER-02 — MediaSession, Notifications & PiP
**Estimate**: 3.5h.

**Goals**
- Specify MediaSession integration, background playback notification, and PiP controls.
- Ensure foreground service behavior aligns with Android policy.
- Document user flows for background audio + PiP transitions.

**Propose diff**
- `docs/architecture/solution-architecture.md`: add MediaSession + notification diagrams.
- `docs/testing/test-strategy.md`: expand reliability suite covering background/PiP scenarios.
- `docs/ux/ui-spec.md`: attach notification/PiP control mockups.

**Tests**
- Instrumentation tests verifying MediaSession actions (play/pause/next) succeed.
- PiP transition tests on supported API levels.
- Battery impact measurement for foreground service.

**Implement**
- Outline service lifecycle, notification templates, and user controls.
- Document Doze/low battery behavior and fallback.
- Update backlog with telemetry tasks (background playback metrics).

**Reflect**
- Identify OEM-specific quirks (e.g., Xiaomi background restrictions) for QA.
- Update risk register with MediaSession compatibility concerns.

### AND-PLAYER-03 — Up Next & Analytics Instrumentation
**Estimate**: 3h.

**Goals**
- Define Up Next list structure sourced from backend `/next-up` endpoint.
- Document analytics for playback transitions, completion rates, download triggers.
- Ensure exclusions and policies respected in Up Next ordering.

**Propose diff**
- `docs/api/openapi-draft.yaml`: finalize `/next-up` schema + policy flags.
- `docs/acceptance/criteria.md`: add Up Next specific ACs.
- `docs/testing/test-strategy.md`: expand reliability scenarios for queue transitions.

**Tests**
- Unit tests verifying Up Next queue respects exclusions + category filters.
- Analytics validation ensuring events fire with expected payload.
- Localization QA for Up Next copy.

**Implement**
- Outline queue management logic (prefetch, skip, reorder) and UI states.
- Document backend requirements for prefetch caching.
- Update backlog with dashboards for playback analytics (Phase 12).

**Reflect**
- Capture user research questions (e.g., manual queue editing) for backlog.
- Note dependencies on backend caching/performance for Up Next.

## Phase 9 — Downloads & Offline

### AND-DL-01 — Download Queue & Storage Management
**Estimate**: 4h.

**Goals**
- Design download queue (pause/resume/cancel) using WorkManager + foreground service.
- Define storage quotas and eviction policy for app-private storage.
- Align notification + progress UI with design tokens.

**Propose diff**
- `docs/architecture/solution-architecture.md`: add downloads architecture section.
- `docs/testing/test-strategy.md`: include download service test coverage.
- `docs/security/threat-model.md`: extend policy controls for offline storage.

**Tests**
- Instrumentation tests for pause/resume and queue reordering.
- Storage stress tests verifying quota enforcement.
- Battery/network performance measurements.

**Implement**
- Outline queue state machine, WorkManager jobs, and notification templates.
- Document storage directory structure + cleanup policy.
- Update backlog with analytics for download success/failure.

**Reflect**
- Identify legal/compliance considerations for storage retention.
- Update risk register if storage quotas remain unresolved.

### AND-DL-02 — EULA Gate & Policy Enforcement
**Estimate**: 3h.

**Goals**
- Define EULA acceptance flow gating downloads per policy.
- Ensure backend verifies acceptance before issuing manifests.
- Localize EULA content and acceptance messaging.

**Propose diff**
- `docs/acceptance/criteria.md`: add AC-DL-002/003 coverage details.
- `docs/api/openapi-draft.yaml`: include EULA acceptance flags in auth responses.
- `docs/ux/ui-spec.md`: add modal design for EULA acceptance.

**Tests**
- Unit/instrumentation tests verifying downloads blocked until acceptance.
- Backend integration tests ensuring policy enforced and audited.
- Localization QA for EULA content.

**Implement**
- Outline acceptance flow (modal, settings screen) and persistence.
- Document audit logging + analytics for EULA acceptance.
- Update backlog with legal review tasks for EULA copy.

**Reflect**
- Capture follow-up for EULA re-acceptance when policy changes.
- Update risk register with legal/compliance outcomes.

### AND-DL-03 — Offline Playback QA & Telemetry
**Estimate**: 2.5h.

**Goals**
- Plan QA matrix covering offline playback, expired content, revalidation.
- Define telemetry for downloads (start, progress, failure reasons).
- Ensure observability meets Phase 10/12 requirements.

**Propose diff**
- `docs/testing/test-strategy.md`: add offline QA matrix + telemetry validation steps.
- `docs/architecture/solution-architecture.md`: document revalidation flow.
- `docs/acceptance/criteria.md`: extend downloads acceptance criteria.

**Tests**
- Offline instrumentation tests verifying playback with network disabled.
- Telemetry validation ensuring events reach analytics pipeline.
- Monitoring dry run for storage cleanup events.

**Implement**
- Draft QA checklist (devices, locales, storage states) for regression cycles.
- Define analytics schema for download events (JSON contract).
- Update backlog with dashboard/reporting tasks.

**Reflect**
- Identify gaps requiring Ops/Analytics involvement.
- Log risks around telemetry accuracy or privacy.

## Phase 10 — Performance & Security Hardening

### HARDEN-01 — Caching & Performance Budgets
**Estimate**: 3h.

**Goals**
- Finalize caching strategy across backend + Android (Redis TTL, Room eviction).
- Define performance budgets (API latency, payload size, Android jank) and monitoring plan.
- Prepare load/perf test scripts.

**Propose diff**
- `docs/architecture/solution-architecture.md`: expand performance + caching section.
- `docs/testing/test-strategy.md`: add Gatling + Macrobenchmark plans.
- `docs/risk-register.md`: update with performance mitigation owners.

**Tests**
- Load testing dry run hitting critical endpoints.
- Android macrobenchmarks validating cold start + frame time targets.
- Cache hit ratio monitoring verification.

**Implement**
- Document caching config (keys, TTL, invalidation) in architecture doc.
- Outline performance regression gating in CI.
- Update backlog with tasks to optimize slow paths identified.

**Reflect**
- Record performance debt requiring post-hardening follow-up.
- Update risk register with residual performance risks.

### HARDEN-02 — Security & Compliance Sweep
**Estimate**: 3h.

**Goals**
- Execute pen-test checklist (JWT rotation, CSRF, RBAC audits).
- Validate dependency policy and SBOM generation.
- Ensure incident response playbooks prepared.

**Propose diff**
- `docs/security/threat-model.md`: add pen-test results + remediation plan.
- `docs/testing/test-strategy.md`: document security regression automation.
- `docs/acceptance/criteria.md`: mark security ACs as verified.

**Tests**
- OWASP ZAP scans, dependency check reports.
- Manual security review of auth flows and logging.
- Incident response tabletop exercise.

**Implement**
- Document vulnerabilities found and mitigation tasks.
- Update backlog with remediation tickets and deadlines.
- Coordinate with Ops on incident runbook updates.

**Reflect**
- Capture lessons for future security cycles.
- Update risk register with residual vulnerabilities if any.

### HARDEN-03 — Observability & SLO Definition
**Estimate**: 2.5h.

**Goals**
- Define SLIs/SLOs (API availability, playback success, download throughput).
- Ensure metrics, logs, traces instrumented consistently.
- Align alert thresholds with launch readiness.

**Propose diff**
- `docs/architecture/solution-architecture.md`: add observability + SLO section.
- `docs/testing/test-strategy.md`: document observability validation plan.
- `docs/acceptance/criteria.md`: link observability ACs to metrics.

**Tests**
- Monitoring dry runs verifying alerts fire at thresholds.
- Log/trace sampling to ensure required fields present (traceId, locale, userId).
- Analytics QA aligning tracked events with product metrics.

**Implement**
- Define metrics registry (naming, labels) for backend + Android.
- Document dashboards/alerts required before launch.
- Update backlog with tasks to close instrumentation gaps.

**Reflect**
- Note open instrumentation requests from stakeholders.
- Update risk register with observability coverage status.

## Phase 11 — i18n & Accessibility Polish

### QA-L10N-01 — Localization Audit
**Estimate**: 2.5h.

**Goals**
- Run full localization QA across Android + Admin for en/ar/nl.
- Validate numerals, dates, bidi text, and truncation scenarios.
- Compile localization bug list and remediation plan.

**Propose diff**
- `docs/i18n/strategy.md`: add audit results + remediation tracker.
- `docs/testing/test-strategy.md`: append localization regression workflow.
- `docs/acceptance/criteria.md`: update i18n ACs with verification notes.

**Tests**
- Manual QA using locale builds/screenshots.
- Automated screenshot comparison (Paparazzi/Playwright) for key flows.
- Content review by native speakers.

**Implement**
- Document findings, categorize severity, and create backlog tasks.
- Update translation pipeline to address missing keys.
- Capture final sign-off checklist.

**Reflect**
- Note lessons for future locale expansion.
- Update risk register with any unresolved localization debt.

### QA-A11Y-01 — Accessibility Verification
**Estimate**: 2.5h.

**Goals**
- Conduct accessibility sweep (TalkBack, keyboard, contrast) for all surfaces.
- Ensure acceptance criteria AC-A11Y-001..003 validated.
- Document remediation plan for failures.

**Propose diff**
- `docs/ux/ui-spec.md`: annotate accessibility expectations per screen.
- `docs/testing/test-strategy.md`: add recurring accessibility testing cadence.
- `docs/acceptance/criteria.md`: mark a11y ACs with test evidence links.

**Tests**
- TalkBack/VoiceOver manual testing.
- axe-core automation for admin console.
- Contrast analyzer checks.

**Implement**
- Log issues, assign severities, and create backlog fixes.
- Update documentation with accessible names/roles where needed.
- Share findings with design/system teams.

**Reflect**
- Capture improvements for design tokens or component guidelines.
- Update risk register if critical accessibility issues remain.

### QA-COMP-01 — Compliance Report Compilation
**Estimate**: 2h.

**Goals**
- Produce compliance report covering localization, accessibility, security sign-offs.
- Cross-link report to acceptance criteria and risk register.
- Share with stakeholders as Phase 11 exit artifact.

**Propose diff**
- `docs/acceptance/compliance-report.md` (new) summarizing verification results.
- `README.md`: reference compliance report for stakeholders.
- `docs/risk-register.md`: update statuses based on audit results.

**Tests**
- Stakeholder review of report for completeness.
- Spot checks ensuring evidence links valid.
- QA verifying report aligns with acceptance criteria.

**Implement**
- Aggregate evidence (screenshots, test logs) and embed references.
- Document sign-off owners and dates.
- Archive report under version control for traceability.

**Reflect**
- Capture process feedback for future compliance cycles.
- Update backlog with any follow-up compliance tasks.

## Phase 12 — Beta & Launch

### LAUNCH-01 — Telemetry & Crash Reporting Enablement
**Estimate**: 3h.

**Goals**
- Instrument telemetry (analytics events, performance metrics) and crash reporting for Android/Admin.
- Define data retention + privacy settings.
- Ensure dashboards alert on key launch metrics.

**Propose diff**
- `docs/testing/test-strategy.md`: expand Release Management with telemetry steps.
- `docs/architecture/solution-architecture.md`: document analytics data flow.
- `docs/backlog/product-backlog.csv`: add telemetry setup stories.

**Tests**
- Beta build walkthrough verifying telemetry events delivered.
- Crash reporting smoke test (forced crash) confirming alerts triggered.
- Privacy review ensuring PII not collected beyond policy.

**Implement**
- Outline analytics schemas, event naming, and opt-in messaging.
- Configure crash/analytics tooling accounts and environment keys.
- Document dashboard expectations for launch monitoring.

**Reflect**
- Note telemetry gaps requiring post-launch follow-up.
- Update risk register with analytics/privacy risks.

### LAUNCH-02 — Beta Program Execution
**Estimate**: 2.5h.

**Goals**
- Plan closed beta cohort, recruitment, feedback channels, and support rota.
- Prepare beta onboarding materials referencing product positioning.
- Align bug triage + rollback plan for beta findings.

**Propose diff**
- `docs/acceptance/criteria.md`: add beta exit criteria + go/no-go checklist.
- `docs/testing/test-strategy.md`: outline beta feedback triage process.
- `docs/backlog/product-backlog.csv`: add beta-related tasks.

**Tests**
- Dry-run beta onboarding with internal testers.
- Feedback capture rehearsal ensuring tooling (e.g., Notion/Jira) ready.
- Support escalation drill.

**Implement**
- Draft communication templates, feedback forms, and triage schedule.
- Document release cadence for beta updates.
- Coordinate with ops to monitor beta metrics.

**Reflect**
- Capture feedback loop improvements for general availability.
- Update risk register with beta-specific findings.

### LAUNCH-03 — Launch Checklist & Rollback Playbook
**Estimate**: 3h.

**Goals**
- Produce launch checklist covering environment readiness, legal approvals, marketing comms, support coverage.
- Define rollback procedures for backend + Android builds (blue/green, Play Store staged rollout).
- Align SLO monitoring + incident response for launch week.

**Propose diff**
- `docs/acceptance/launch-checklist.md` (new) with owner matrix and gates.
- `README.md`: reference launch materials for stakeholders.
- `docs/risk-register.md`: update with launch execution risks/status.

**Tests**
- Rollback simulation (backend deploy, app store revert) documented.
- Incident response tabletop focusing on launch failure scenarios.
- Validation that all SLO dashboards green before go-live.

**Implement**
- Compile checklist items, assign owners, and set due dates.
- Document communication tree for incidents + escalation thresholds.
- Schedule launch readiness review with leadership.

**Reflect**
- Capture retrospective inputs for post-launch review.
- Update backlog with post-launch monitoring + support tasks.
