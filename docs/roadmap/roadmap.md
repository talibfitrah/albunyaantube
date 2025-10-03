# Phased Roadmap

> Execution metadata — Last reviewed: 2025-10-03

## Status Snapshot
- Delivered
  - **Firebase Migration + Core Admin Features Complete (2025-10-03)**
    - ✅ Migrated from PostgreSQL/JPA to Firebase Firestore
    - ✅ Replaced custom JWT auth with Firebase Authentication
    - ✅ Restructured category model from embedded subcategories to hierarchical parentCategoryId
    - ✅ Integrated YouTube Data API v3 for admin search/preview
    - ✅ Frontend updated with Firebase SDK for authentication
    - ✅ **NEW**: User management (create/update/delete admin/moderator users)
    - ✅ **NEW**: Dashboard with metrics and analytics
    - ✅ **NEW**: Audit log for compliance and action tracking
    - ✅ **NEW**: 33 total API endpoints across 6 controllers
    - Backend: `backend/src/main/java/com/albunyaan/tube/{config,model,repository,security,service,controller}`
    - Frontend: `frontend/src/{config,stores,services}`
    - Docs: `backend/FIREBASE_SETUP.md`, `FIREBASE_MIGRATION_SUMMARY.md`, `FIREBASE_MIGRATION_COMPLETE.md`
    - Commits: `0f45261`, `54be1e0`, `8ed8451`, `5f966a4`, `54d1506`, `1332382`, `86737fe`, `f0c53ad`
  - Dark-mode tokenization + component mappings in Admin UI
    - Code: `frontend/src/assets/main.css`, views/components now map to tokens
    - Tests: `frontend/tests/ThemeTokens.spec.ts`
    - Docs: token table in `README.md`, dark palette added to `docs/ux/design-tokens.json`
    - AC linkage: satisfies contrast requirements in **AC-A11Y-002**
  - Canonical bottom tab config + reusable tab bar component (for shared parity and future Android mapping)
    - Code: `frontend/src/constants/tabs.ts`, `frontend/src/components/navigation/MainTabBar.vue`
    - Tests: `frontend/tests/MainTabBar.spec.ts`

- In progress / planned
  - Phase 1–2 backend hardening and Phase 3 Admin MVP items per files in this folder

## How To Use This Roadmap (multi-agent)
- Each phase below begins with “Execution Metadata” listing Status, Last Reviewed, Dependencies, and Owners (TBD where unassigned).
- When work lands, append a short “Delivered in this repo” snippet under the relevant phase with file paths and any follow‑ups required.
- Keep `docs/backlog/product-backlog.csv` in sync (append rows for newly discovered work; mark status inline in Description until a formal “Status” column is introduced).

This roadmap expresses Albunyaan Tube's design-first delivery strategy. Every phase lists an estimate plus the activities required to exit the phase using the cadence `Estimate → Goals → Proposed Diff → Tests → Implement → Reflect`. Linked artifacts live in this repository so future engineering teams inherit a complete, traceable blueprint.

## Phase 0 — Discovery & Contracts
**Estimate**: 2 calendar weeks cross-discipline (Product, Architecture, UX, Security).

**Goals**
- Capture the product vision, success metrics, and constraints in `docs/vision/vision.md`.
- Produce canonical contracts: API draft, architecture diagrams, UX spec, and i18n plan.
- Establish traceability between requirements, future APIs, and acceptance criteria.

**Proposed Diff**
- `README.md`: orient contributors on how to consume the design artifacts.
- `docs/api/openapi-draft.yaml`: end-to-end endpoint coverage per brief.
- `docs/architecture/solution-architecture.md` + `docs/architecture/diagrams/*.md`: C4 + key sequences.
- `docs/ux/ui-spec.md` + `docs/ux/design-tokens.json`: UI contract and tokens.
- `docs/i18n/strategy.md`, `docs/testing/test-strategy.md`, `docs/security/threat-model.md`.

**Tests**
- Structured stakeholder review covering Product, Engineering, Design, Localization.
- OpenAPI validation (`spectral`, TODO) and schema cross-check against `docs/data/json-schemas`.
- Accessibility + i18n heuristic review of UI spec (contrast, RTL, bidi scenarios).

**Implement**
- Facilitate discovery workshops; log assumptions in `docs/vision/vision.md`.
- Draft and iterate on diagrams + contracts; ensure all artifacts cite dependencies.
- Populate `docs/backlog/product-backlog.csv` with epic/story seeds tagged to Phase 0.

**Reflect**
- Record unresolved risks in `docs/risk-register.md`.
- Summarize stakeholder feedback and acceptance status inside `docs/acceptance/criteria.md` references.

## Phase 1 — Backend Foundations (Delivered)
Execution Metadata
- Status: ✅ **COMPLETE** (Firebase-based architecture)
- Last reviewed: 2025-10-03
- Dependencies: Firebase (Firestore + Authentication), Redis, YouTube Data API v3
- Owners: Backend Team

Delivered in this repo (Firebase Migration)
- ✅ Firebase Firestore replaces PostgreSQL (schema-less, scalable)
- ✅ Firebase Authentication with custom claims (role: admin|moderator)
- ✅ Category model restructured: hierarchical parentCategoryId instead of embedded subcategories
- ✅ YouTube Data API v3 integration for admin content search/preview
- ✅ 21 new API endpoints: Categories (6), Channels (6), YouTube (9)
- ✅ Removed 115 obsolete PostgreSQL/JPA files (-6,000 lines of code)
- Code: `backend/src/main/java/com/albunyaan/tube/{config,model,repository,security,service,controller}`
- Docs: `backend/FIREBASE_SETUP.md`, `FIREBASE_MIGRATION_SUMMARY.md`
- Commits: `0f45261` (foundation), `54be1e0` (services/controllers), `8ed8451` (cleanup)
**Actual effort**: 4 major commits over migration sprint.

**Goals**
- Finalize backend auth/RBAC, migration, and seeding plan aligned with Phase 0 contracts.
- Detail caching, persistence, and seed data strategies before implementation.
- Produce ticket-ready backlog entries for auth, categories, migrations, and CI scaffolding.

**Proposed Diff**
- `docs/roadmap/phase-1-backend-plan.md`: ticketized plan using Estimate/Goals/Proposed diff/Tests/Implement/Reflect format.
- `docs/backlog/product-backlog.csv`: refine BACK-* stories with acceptance IDs.
- `docs/api/openapi-draft.yaml`: annotate auth endpoints with RBAC + rate-limit notes.
- `docs/testing/test-strategy.md`: expand Testcontainers + security regression coverage detail.

**Tests**
- Design review with Backend + Security leads focusing on JWT rotation, Redis usage, and Flyway strategy.
- Schema sanity check ensuring locale maps and category slugs satisfy data requirements.
- Risk assessment update verifying mitigations for seeding + bootstrap flows.

**Implement**
- Write migration design notes (naming, rollback strategy) inside `phase-1-backend-plan.md`.
- Define integration test matrix referencing acceptance criteria IDs (AC-BE-001..).
- Update backlog with groomed tasks including effort, dependencies, and owners placeholder.

**Reflect**
- Capture open questions (e.g., admin bootstrap secrets handling) and feed into Phase 2 prerequisites.
- Adjust risk register likelihood/impact based on backend findings.

## Phase 2 — Registry & Moderation (In Progress)
Execution Metadata
- Status: Partially delivered (Firebase backend ready, UI pending)
- Last reviewed: 2025-10-03
- Dependencies: Phase 1 ✅ complete (Firebase auth + data foundations)
- Owners: TBD (Backend + Frontend)

Delivered in this repo
- ✅ Firebase Firestore collections: categories, channels, playlists, videos
- ✅ Hierarchical category structure with parentCategoryId field
- ✅ Channel/Playlist models with excludedItems/excludedVideoIds
- ✅ Approval workflow (pending → approved → rejected status)
- ✅ API endpoints for CRUD operations with RBAC enforcement
- 🔲 Pending: Admin UI for channel submission, approval queue, exclusions editor
- 🔲 Pending: Moderation proposals workflow implementation
**Estimate**: 2 engineering weeks remaining (frontend focus).

**Goals**
- Lock data model for channels/playlists/videos including pagination policy and cache invalidation rules.
- Define moderation proposal lifecycle and exclusions interplay.
- Specify blended admin search contract returning channels/playlists/videos with include/exclude state and bulk semantics.
- Produce ready-to-build tickets for registry endpoints, moderation workflows, and caching.

**Proposed Diff**
- `docs/data/json-schemas/*.json`: finalize catalogue + moderation schemas (ensure ≥1 category requirement).
- `docs/api/openapi-draft.yaml`: document pagination parameters, error taxonomy, cache headers, and blended `/admin/search` response.
- `docs/architecture/diagrams/moderation-sequence.md` & `channel-tabs-sequence.md`: confirm flows.
 - `docs/roadmap/phases-4-12-ticket-breakdown.md`: seed Phase 2 section with tickets.

**Tests**
- Architecture review covering cache invalidation, Redis TTL, and category enforcement.
- Schema round-trip validation (example payloads exercised through `jsonschema` tooling TBD).
- Moderation policy walkthrough with content board to validate statuses + audit coverage.

**Implement**
- Write ticket entries (MOD-*, BACK-*) in backlog referencing acceptance IDs (AC-REG-001, AC-REG-002, AC-ADM-006/007).
- Document cache warming + invalidation approach within architecture doc (cross-link tests).
- Update threat model with moderation abuse scenarios discovered.

**Reflect**
- Identify dependencies for Admin UI (Phase 3) and Android (Phase 6+) needing stable API fields.
- Note outstanding legal/policy review items for exclusions in risk register.

## Phase 3 — Admin UI MVP (In Progress)
Execution Metadata
- Status: Auth migrated, UI features pending
- Last reviewed: 2025-10-03
- Dependencies: Phase 1 ✅ complete, Phase 2 backend ✅ complete
- Owners: TBD (Frontend)

Delivered in this repo
- ✅ Firebase Auth integration in frontend (`frontend/src/config/firebase.ts`, `frontend/src/stores/auth.ts`)
- ✅ Firebase ID token sent to backend in Authorization header
- ✅ Tokenized dark theme applied across admin views
- ✅ Registry landing surface with category filter
- ✅ Reusable canonical tab bar + icons
- 🔲 Pending: YouTube search/preview UI components
- 🔲 Pending: Channel expansion drawer (videos, playlists, shorts tabs)
- 🔲 Pending: Category management UI with hierarchical tree
- 🔲 Pending: Approval queue interface for moderators
**Estimate**: 3 engineering weeks remaining (UI components + wiring to Firebase backend).

**Goals**
- Specify admin IA, routing, shared state, and localization wiring for MVP scope.
- Detail data table contracts for registry lists with cursor pagination.
- Lock the Search & Import workspace that mirrors YouTube blended results, tri-state include/exclude toggles, and tabbed channel/playlist drawers.
- Define tasks for locale switcher, initial translations, and moderation queue UI.

**Proposed Diff**
- `docs/roadmap/phase-3-admin-mvp-tickets.md`: update tickets to required format with design references only.
- `docs/ux/ui-spec.md`: append admin flows/table specs plus Search & Import workspace/drawer specs.
- `docs/api/openapi-draft.yaml`: adjust `/admin/search` response to blended schema with include/exclude state.
- `docs/data/json-schemas`: add admin search result schemas and excluded ID lists on channel/playlist detail.
- `docs/i18n/strategy.md`: clarify admin locale switcher + Accept-Language handling.
- `docs/backlog/product-backlog.csv`: enrich ADMIN-* stories with estimates + acceptance IDs.

**Tests**
- Design QA: alignment of admin wireframes with tokens + accessibility heuristics.
- Localization review verifying copy coverage for en/ar/nl with ICU placeholders.
- Usability walkthrough with moderators to confirm queue ergonomics.

**Implement**
- Produce ticket breakdown covering locale switcher, registry filters, search/import workspace, moderation queue, dashboard metrics contracts.
- Capture API dependencies (e.g., `/admin/users`, `/moderation/proposals`, `/admin/search`) alongside expected response fields and include-state semantics.
- Update testing strategy with Playwright + accessibility automation scope.

**Reflect**
- Document outstanding tooling needs (design assets, translation pipeline) and add to backlog.
- Reassess risk register for admin UX debt or localization blockers.

## Phase 4 — Admin UI Complete (Plan)
Execution Metadata
- Status: Planned
- Last reviewed: 2025-09-30
- Dependencies: Phase 3 completion
- Owners: TBD (Frontend/Backend)
**Estimate**: 4 engineering weeks (frontend + backend threading for exclusions/audit).

**Goals**
- Plan for exclusions editor, user management, audit viewer, and accessibility polish.
- Ensure Admin backlog covers all acceptance criteria (AC-ADM-003..005, AC-A11Y-*).
- Align frontend/backend sequencing so data contracts and UI ship together.

**Proposed Diff**
- `docs/roadmap/phases-4-12-ticket-breakdown.md`: refresh Phase 4 tickets to required format.
- `docs/api/openapi-draft.yaml`: finalize exclusions endpoints, audit pagination details.
- `docs/ux/ui-spec.md`: document exclusions/user management flows.
- `docs/testing/test-strategy.md`: specify accessibility + e2e automation gating for admin.

**Tests**
- Accessibility review (WCAG AA) for admin components.
- Security review for user admin flows (RBAC, audit logging).
- Localization QA to ensure RTL admin experience remains intact.

**Implement**
- Ticketize front/back tasks for exclusions CRUD, audit table, user management UI.
- Update backlog with dependency ordering + estimates.
- Capture instrumentation requirements (metrics/toasts) to feed Phase 10.

**Reflect**
- Summarize admin readiness status; flag any features slipping to later phases.
- Feed new risks (e.g., audit data volume) into risk register.

## Phase 5 — Android Skeleton (Plan)
Execution Metadata
- Status: Planned
- Last reviewed: 2025-09-30
- Dependencies: Phase 0 contracts + shared icons/tabs mapping
- Owners: TBD (Android)
**Estimate**: 3 engineering weeks (Android feature squad).

**Goals**
- Define navigation graph, onboarding flow, bottom navigation shell, global category filter.
- Document locale switcher behavior and RTL mirroring on Android.
- Prepare UI kit + layout specs for main surfaces (Splash, Onboarding, Home shell).

**Proposed Diff**
- `docs/ux/ui-spec.md`: expand Android sections with navigation, onboarding, and category sheet specs.
- `docs/architecture/solution-architecture.md`: add Android nav + locale management subsections.
- `docs/roadmap/phases-4-12-ticket-breakdown.md`: update Phase 5 tickets to required format.
- `docs/testing/test-strategy.md`: note Android test harness (Macrobenchmark, Espresso) for skeleton pieces.

**Tests**
- Design QA ensuring mockups align with specs (Spacing, tokens, assets).
- Accessibility/RTL review for onboarding and navigation.
- Performance baseline capturing cold start metric instrumentation plan.

**Implement**
- Break down Android tasks (nav graph, onboarding carousel, DataStore for locale flag) in tickets with dependencies.
- Document required backend stubs/mocks for navigation flows.
- Outline instrumentation hooks for analytics + crash reporting to revisit in Phase 12.

**Reflect**
- Capture open questions on asset delivery (thumbnails, fonts) and offline storage constraints.
- Update backlog with follow-on tasks for dynamic content once API stabilizes.

## Phase 6 — Lists & Home Rules (Plan)
**Estimate**: 3 engineering weeks (Android + backend pairing on paging rules).

**Goals**
- Specify Paging 3 integration, 3-latest per channel/playlist algorithm, error handling + retry UX.
- Document filtering controls (Category, Length, Date, Popular) and data contract expectations.
- Define test cases + metrics for home feed and list performance budgets.

**Proposed Diff**
- `docs/api/openapi-draft.yaml`: annotate list endpoints with sorting, filtering, cursor semantics.
- `docs/testing/test-strategy.md`: add paging + performance test plans (Gatling + Android benchmarks).
- `docs/ux/ui-spec.md`: detail filter interactions and skeleton states.
 - `docs/roadmap/phases-4-12-ticket-breakdown.md`: adjust Phase 6 tickets to new format.

**Tests**
- Algorithm review with backend leads focusing on fairness of 3-latest logic.
- Performance modeling vs. 80KB payload budget.
- QA test plan for offline fallback (cached lists) and error taxonomy alignment.

**Implement**
- Ticketize backend tasks for cursor services & caching invalidation; Android tasks for Paging adapters.
- Document instrumentation needs (cache hit ratio, error rates) for metrics dashboard.
- Extend acceptance criteria with pagination edge cases.

**Reflect**
- Identify data dependencies (thumbnail CDN, metadata update cadence) and log in risk register.
- Record learnings to inform Phase 7 deep-linking and Phase 8 Up Next experience.

## Phase 7 — Channel & Playlist Details (Plan)
**Estimate**: 2.5 engineering weeks.

**Goals**
- Outline detail screens with tab behaviors (Videos/Live/Shorts/Playlists/Posts) and deep-link handling.
- Document exclusions enforcement rules on detail pages.
- Produce test matrix covering tab switching, state retention, and locale variations.

**Proposed Diff**
- `docs/ux/ui-spec.md`: add detail screen annotations + deep-link states.
- `docs/api/openapi-draft.yaml`: ensure `/channels/{id}/...` and `/playlists/{id}` endpoints capture needed fields.
- `docs/testing/test-strategy.md`: append Phase 7 test matrix references.
- `docs/roadmap/phases-4-12-ticket-breakdown.md`: refresh Phase 7 tickets format.

**Tests**
- UX review verifying tab focus order + RTL mirroring.
- Backend contract check for exclusions + up-next interplay.
- QA scenario planning for offline/partially cached detail screens.

**Implement**
- Ticketize Android tasks (tabbed UI, state restoration, deep link routes) and backend tasks (tabbed data endpoints).
- Update acceptance criteria with AC-AND-006/007 expansions for detail flows.
- Plan analytics to observe tab engagement.

**Reflect**
- Document cross-phase dependencies (Phase 8 Player using detail context for Up Next).
- Update risk register with potential API pagination edge cases discovered.

## Phase 8 — Player & Background Audio (Plan)
**Estimate**: 4 engineering weeks (Android + backend streaming focus).

**Goals**
- Define ExoPlayer configuration, MediaSession strategy, PiP behavior, audio-only toggle, quality selector, captions.
- Document Up Next experience sourced from backend `/next-up` endpoint.
- Outline reliability scenarios, including network transitions and playback recovery.

**Proposed Diff**
- `docs/architecture/solution-architecture.md`: expand Playback Engine section with sequence diagrams.
- `docs/architecture/diagrams/player-session.md` (new): capture playback handshake, audio-only toggle, and download rights gating.
- `docs/api/openapi-draft.yaml`: finalize `/videos/{id}`, `/next-up` fields (streams, captions, bookmarks, download policy).
- `docs/testing/test-strategy.md`: add Player reliability suite + metrics gating.
 - `docs/roadmap/phases-4-12-ticket-breakdown.md`: update Phase 8 tickets to new format.
 - `docs/backlog/product-backlog.csv`: add AND-EXTRACT-01 (Extractor metadata hydration for lists) and AND-EXTRACT-02 (stream resolution for playback).

**Tests**
- Media playback design review with Android audio experts.
- Security review for download authorization + token gating.
- Performance modeling for buffering, bitrate selection, and offline storage impact.

**Implement**
- Ticketize tasks for audio-only toggle, caption track selection, PiP controls, notification service.
- Document backend needs (manifest generation, signed URLs) and caching layers.
- Update acceptance criteria (AC-AND-003, AC-DL-004, AC-OBS-001) for playback scenarios.

**Reflect**
- Capture instrumentation + analytics requirements for playback KPIs.
- Note outstanding licensing/legal compliance tasks for offline audio/video.

## Phase 9 — Downloads & Offline (Plan)
**Estimate**: 3.5 engineering weeks (Android + backend).

**Goals**
- Plan download queue, notifications, pause/resume/cancel flows, storage quotas, and policy gating.
- Define EULA acceptance workflow and backend enforcement.
- Outline storage + encryption strategy with compliance requirements.

**Proposed Diff**
- `docs/security/threat-model.md`: extend policy controls + threat scenarios for offline media.
- `docs/architecture/solution-architecture.md`: add downloads architecture subsection.
- `docs/testing/test-strategy.md`: update with download service tests and storage quota automation.
- `docs/roadmap/phases-4-12-ticket-breakdown.md`: adjust Phase 9 tickets format.
- `docs/acceptance/criteria.md`: expand downloads section with new ACs.

**Tests**
- Security/privacy review for offline storage & EULA gating.
- Performance test for download throughput + battery impact.
- QA matrix covering partial downloads, network changes, storage exhaustion.

**Implement**
- Ticketize backend manifest generation, token revocation on logout, Android foreground service, notification UI.
- Document policy flags + admin controls for enabling downloads.
- Update backlog with compliance tasks (legal review, EULA copy localization).

**Reflect**
- Record open compliance/legal risk follow-ups.
- Note metrics to monitor (downloads_active, storage_usage) feeding Phase 10.

## Phase 10 — Performance & Security Hardening (Plan)
**Estimate**: 3 engineering weeks (multi-discipline strike team).

**Goals**
- Consolidate caching, performance budgets, pen-test checklist, dependency policy.
- Define SLIs/SLOs for backend, admin, Android clients.
- Prepare security threat exercises and mitigation backlog.

**Proposed Diff**
- `docs/security/threat-model.md`: add pen-test checklist, dependency governance.
- `docs/testing/test-strategy.md`: document performance benchmarking suites + alert thresholds.
- `docs/architecture/solution-architecture.md`: include observability + scaling plans.
 - `docs/risk-register.md`: update residual risks and mitigation owners.
 - `docs/roadmap/phases-4-12-ticket-breakdown.md`: ensure Phase 10 tickets use new format.
 - `docs/backlog/product-backlog.csv`: add DEVOPS-YT-01 (YouTube API key secrets) and HARDEN-EXTRACT-01 (Extractor telemetry & circuit breakers).
 

**Tests**
- Performance capacity planning review with Ops.
- Security tabletop exercise review (JWT rotation, incident response).
- Tooling validation (Gatling, OWASP ZAP, dependency scanners).

**Implement**
- Ticketize caching improvements, load test scripts, security hardening tasks.
- Update backlog with SLO monitoring + alert configuration tasks.
- Document release gating metrics ahead of Phase 12 launch.

**Reflect**
- Capture outcomes of load/security reviews and adjust risk register accordingly.
- Highlight outstanding tech debt items for leadership prioritization.

## Phase 11 — i18n & Accessibility Polish (Plan)
**Estimate**: 2 engineering weeks (localization + QA + design).

**Goals**
- Conduct full localization audit for en/ar/nl plus bidi/numeric checks.
- Validate accessibility (TalkBack, keyboard navigation, contrast) across admin + Android.
- Document compliance report summarizing readiness.

**Proposed Diff**
- `docs/i18n/strategy.md`: add QA checklist results and tooling references.
- `docs/testing/test-strategy.md`: include localization regression automation and accessibility testing playbook.
- `docs/acceptance/criteria.md`: mark AC-I18N and AC-A11Y items with verification notes.
- `docs/roadmap/phases-4-12-ticket-breakdown.md`: update Phase 11 tickets format.

**Tests**
- Localization QA sessions with native speakers covering UI + error states.
- Accessibility testing on devices (TalkBack, Switch Access) and admin (axe-core).
- Regression pass for locale toggling, numeral display, layout mirroring.

**Implement**
- Ticketize translation sign-off, screenshot cataloging, accessibility fixes.
- Update backlog with residual issues requiring engineering follow-up.
- Produce compliance report stored under `docs/acceptance` or `docs/i18n`.

**Reflect**
- Summarize residual localization risks and plan for post-launch updates.
- Feed lessons learned into product backlog for future locale expansion.

## Phase 12 — Beta & Launch (Plan)
**Estimate**: 3 engineering weeks (cross-discipline launch readiness).

**Goals**
- Define telemetry, crash reporting, beta program, release/rollback plan, and SLO monitoring.
- Produce launch checklist with owner matrix and go/no-go criteria.
- Align marketing/support readiness with product capabilities.

**Proposed Diff**
- `docs/testing/test-strategy.md`: expand Release Management section with telemetry + rollout steps.
- `docs/acceptance/criteria.md`: add launch checklist linkage and go-live requirements.
- `docs/risk-register.md`: update with launch-specific risks (store approvals, CDN readiness).
 - `docs/backlog/product-backlog.csv`: append launch tasks (telemetry setup, beta feedback loop) and BACK-YT-01 (admin search integration) if pending.
- `docs/roadmap/phases-4-12-ticket-breakdown.md`: finalize Phase 12 tickets format.

**Tests**
- Beta program dry run including crash/analytics verification.
- Operational readiness review (incident response, on-call runbook).
- Rollback simulation for backend deploy + Play Store staged rollout.

**Implement**
- Ticketize telemetry instrumentation, crash reporting integration, beta cohort management, release comms.
- Produce launch checklist artifact in `docs/acceptance/launch-checklist.md` (to be created).
- Coordinate with Ops/Support to finalize on-call and escalation procedures.

**Reflect**
- Document launch retrospective inputs, capturing metrics and stakeholder sign-off.
- Plan post-launch iteration backlog (Phase 13+ placeholder) if required.

## Dependencies & Cross-Cutting Notes
- Phase 1 depends on Phase 0 sign-off; later phases inherit stabilized contracts.
- Android Phases (5–9) rely on backend registry stability (Phase 1–2) and player APIs (Phase 8).
- Localization polish (Phase 11) requires UI text freeze post Phase 8/9.
- Launch (Phase 12) contingent on completion of all prior phase exit criteria.

Refer to `docs/backlog/product-backlog.csv` for story-level tracking and `docs/acceptance/criteria.md` for traceability.
