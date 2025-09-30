# Phase 3 — Admin UI MVP Ticket Breakdown

Execution Metadata
- Status: In progress (several tickets partially delivered)
- Last reviewed: 2025-09-30
- Dependencies: Phase 2 registry/search contracts
- Owners: TBD (Frontend)

Phase 3 delivers the admin shell, registry tables, moderation queue, and localization wiring required for MVP exit criteria. Tickets follow the `Estimate → Goals → Propose diff → Tests → Implement → Reflect` cadence and reference design artifacts instead of code so engineering teams can pick them up when implementation begins. Supporting docs: `docs/ux/ui-spec.md`, `docs/i18n/strategy.md`, `docs/api/openapi-draft.yaml`, `docs/testing/test-strategy.md`, and `docs/acceptance/criteria.md`.

## ADMIN-MVP-01 — Admin Locale Switcher & Persistence
```yaml
meta:
  id: ADMIN-MVP-01
  status: planned
  owner: TBD-frontend
  depends: []
  lastReviewed: 2025-09-30
```
Status: Planned
**Estimate**: 3h.

**Goals**
- Provide manual locale switcher in admin shell per i18n strategy.
- Persist locale selection across reloads and sessions.
- Respect Accept-Language fallback guide for future API requests.

**Propose diff**
- `frontend/src/layouts/AdminLayout.vue` (future implementation): add switcher UI referencing design tokens.
- `frontend/src/stores/preferences.ts` (planned): persist locale to storage and expose getter/setter.
- `docs/i18n/strategy.md`: ensure admin locale switching behavior is documented (already referenced) and update if gaps appear.

**Tests**
- Vitest component + store tests verifying locale persists and emits change events.
- Manual QA checklist for en/ar/nl verifying directionality updates.

**Implement**
- Design dropdown interaction aligned with UI spec (48dp touch target, success focus ring).
- Persist selection (localStorage) and update `Accept-Language` header usage plan.
- Capture analytics requirement for locale switch usage (deferred to Phase 10).

**Reflect**
- Note requirement for backend override header (`X-Admin-Locale`) and file follow-up ticket.
- Update backlog if additional locales planned post-launch.

## ADMIN-MVP-02 — Ship Arabic & Dutch Locale Bundles
```yaml
meta:
  id: ADMIN-MVP-02
  status: planned
  owner: TBD-frontend
  depends: [ADMIN-MVP-01]
  lastReviewed: 2025-09-30
```
Status: Planned
**Estimate**: 4h.

**Goals**
- Provide localized admin copy for en/ar/nl using ICU patterns.
- Enable RTL rendering when locale is Arabic.
- Ensure layout uses CSS logical properties for mirrored alignment.

**Propose diff**
- `frontend/src/locales/{en,ar,nl}.ts` (planned): split message bundles.
- `frontend/src/utils/rtl.ts` (planned): helper toggling `dir` attribute.
- `docs/i18n/strategy.md`: add translation key list + QA notes.

**Tests**
- Snapshot/unit tests verifying locale modules export expected keys.
- Playwright smoke tests capturing screenshots for RTL vs LTR.
- Accessibility audit ensuring focus order preserved under RTL.

**Implement**
- Populate translation spreadsheet → transform into locale files.
- Apply logical CSS properties in shared layout components.
- Document translation handoff + update workflow.

**Reflect**
- Record missing translations or ambiguous copy for localization follow-up.
- Update risk register if additional typography assets needed.

## ADMIN-MVP-03 — Registry Category Filter & Shared State
```yaml
meta:
  id: ADMIN-MVP-03
  status: in-review
  owner: Frontend
  depends: []
  lastReviewed: 2025-10-01
```
Status: In review
Delivered so far
- Shared `useRegistryFiltersStore` managing category/query state across registry surfaces.
- `RegistryFilters` component with skeleton + clear affordances wired into landing view and services.
Gaps
- Expanded filters (length/date/sort) remain for ADMIN-MVP-04.
**Estimate**: 4h.

**Goals**
- Provide category filter shared across channels/playlists/videos tables.
- Align UI behavior with UX spec (chips/dropdowns, skeleton states).
- Ensure API requests include `categoryId` parameter to honor allow-list policy.

**Propose diff**
- `frontend/src/components/registry/RegistryFilters.vue` (planned) with filter controls.
- `frontend/src/stores/registryFilters.ts` (planned) for centralized filter state.
- `frontend/src/services/catalog.ts` (planned) to retrieve categories (cursor pagination).
- `docs/api/openapi-draft.yaml`: confirm `/categories` + registry endpoints surface required fields.

**Tests**
- Unit tests for store ensuring resets when switching tabs.
- Component tests verifying query strings include category filters.
- Cross-check against acceptance criteria AC-REG-001/002.

**Implement**
- Design filter interactions (debounce, loading skeleton) per UI spec.
- Document fallback behavior when no categories exist.
- Capture analytics requirements for filter usage (Phase 10).

**Reflect**
- Identify need for multi-select or advanced filters (log backlog item).
- Note backend dependency for localized category labels.

## ADMIN-MVP-04 — Video Query Controls (Search/Length/Date/Sort)
```yaml
meta:
  id: ADMIN-MVP-04
  status: planned
  owner: TBD-frontend
  depends: [ADMIN-MVP-03]
  lastReviewed: 2025-09-30
```
Status: Planned
**Estimate**: 3.5h.

**Goals**
- Surface additional `/videos` parameters (search text, length, date range, popularity).
- Maintain consistent shared state across registry tabs.
- Provide accessible form controls with localization-ready labels.

**Propose diff**
- Extend `RegistryFilters.vue` and filter store to include video-specific state.
- Update `frontend/src/services/videos.ts` (planned) to accept new query params.
- `docs/ux/ui-spec.md`: ensure filter control layout + states are captured (update if needed).

**Tests**
- Unit tests verifying debounce + query parameter construction.
- Playwright tests covering filter combinations and pagination reset.
- Localization QA to ensure filter labels translate without truncation.

**Implement**
- Add filter UI per spec (bottom sheet or dropdown) with keyboard navigation.
- Document default filter values and interplay with category filter.
- Capture instrumentation needs for search/filter usage.

**Reflect**
- Note backend performance considerations for search queries; feed into Phase 6 perf planning.
- Update risk register if complex query building adds API load.

## ADMIN-MVP-05 — Dashboard Metrics Contract
```yaml
meta:
  id: ADMIN-MVP-05
  status: planned
  owner: TBD-frontend-backend
  depends: []
  lastReviewed: 2025-09-30
```
Status: Planned
**Estimate**: 3h (backend + frontend coordination).

**Goals**
- Define backend endpoint returning moderation pending count, total categories, active moderators.
- Specify frontend consumption plan with loading/error states.
- Align metrics with observability requirements.

**Propose diff**
- `docs/api/openapi-draft.yaml`: add `/admin/dashboard` schema.
- `docs/architecture/solution-architecture.md`: outline aggregation strategy + caching.
- `frontend/src/services/dashboard.ts` (planned) + composable to fetch metrics.

**Tests**
- Integration test plan verifying counts with seeded data.
- Contract test ensuring schema matches dashboard needs.
- Playwright/Visual check for skeleton/error UI.

**Implement**
- Document backend responsibilities (RBAC, audit logging) and add to backlog (BACK-XX new).
- Define frontend state machine (loading → success/error) referencing UI spec.
- Note metrics instrumentation for Phase 10 SLO tracking.

**Reflect**
- Identify additional KPIs requested by stakeholders (log backlog items).
- Update acceptance criteria if new metrics become mandatory.

## ADMIN-MVP-06 — Moderation Queue UX Polish
```yaml
meta:
  id: ADMIN-MVP-06
  status: partial
  owner: TBD-frontend
  depends: []
  lastReviewed: 2025-09-30
```
Status: Partially delivered
Delivered so far
- Queue surface with approve/reject actions present; color tokens applied in `frontend/src/views/ModerationQueueView.vue`.
Gaps
- Modal polish + accessibility sweeps and audit hooks.
**Estimate**: 3h.

**Goals**
- Finalize moderation queue table interactions (status filtering, approve/reject flows).
- Ensure audit logging requirements and acceptance criteria AC-ADM-003 satisfied.
- Provide localized copy for statuses, tooltips, and empty states.

**Propose diff**
- `frontend/src/views/ModerationQueueView.vue` (planned) with action dialogs.
- `docs/ux/ui-spec.md`: attach moderation flow annotations (update if missing).
- `docs/testing/test-strategy.md`: call out Playwright scenarios for queue actions.

**Tests**
- Component tests verifying approve/reject actions issue API calls + optimistic updates.
- Accessibility audit for keyboard navigation + focus handling.
- Localization QA ensuring status badges translate correctly.

**Implement**
- Outline state transitions for queue items, including audit log payloads.
- Document notification/toast patterns for success/failure.
- Plan for moderation proposal filters (status, category) aligned with backend endpoints.

**Reflect**
- Capture moderator feedback on workflow ergonomics for future enhancements.
- Update risk register if moderation SLA risks remain.

## ADMIN-MVP-07 — Documentation & Onboarding Refresh
```yaml
meta:
  id: ADMIN-MVP-07
  status: in-progress
  owner: Product+Frontend
  depends: []
  lastReviewed: 2025-09-30
```
Status: In progress
Delivered so far
- README dark-mode token table, roadmap execution metadata (this change).
Gaps
- Admin onboarding runbook and locale QA checklist.
**Estimate**: 2h.

**Goals**
- Align README, runbooks, and backlog with MVP functionality.
- Provide onboarding notes for new admin contributors focusing on localization + accessibility expectations.
- Ensure traceability matrix references new admin acceptance criteria.

**Propose diff**
- `README.md`: update navigation + status callouts for admin artifacts.
- `docs/runbooks/admin-login.md`: adjust instructions once auth implementation details finalize.
- `docs/backlog/product-backlog.csv`: include ADMIN-* tickets with estimates/owners.
- `docs/acceptance/criteria.md`: cross-link admin-specific ACs with ticket IDs.

**Tests**
- Content review with Product + Design leads to confirm documentation accuracy.
- Spellcheck/markdown lint as applicable.
- Verify all links resolve inside repository.

**Implement**
- Refresh documentation sections to reflect MVP scope and responsibilities.
- Add onboarding checklist (accounts, tooling, locale QA) for new engineers.
- Update backlog to flag dependencies on backend Phases 1–2.

**Reflect**
- Gather feedback from onboarding pilot and iterate on documentation.
- Note additional runbooks or tooling guides required before implementation.

## ADMIN-MVP-08 — Search & Import Workspace Contract
```yaml
meta:
  id: ADMIN-MVP-08
  status: planned
  owner: TBD-frontend
  depends: [ADMIN-MVP-03]
  lastReviewed: 2025-09-30
```
Status: Planned
**Estimate**: 3.5h.

**Goals**
- Define blended search experience mirroring YouTube: single surface showing Channels, Playlists, Videos with thumbnails and stats.
- Specify Include/Exclude tri-state toggle states and bulk selection bar behavior.
- Document error, loading, and empty states for each section with localization guidance.

**Propose diff**
- `docs/ux/ui-spec.md`: expand admin search section with card layouts, sticky headers, RTL notes.
- `docs/api/openapi-draft.yaml`: update `/admin/search` contract to return aggregated results and toggle state metadata.
- `docs/data/json-schemas/admin-search-*.json` (new): schema for channel/playlist/video search results with include state.

**Tests**
- Run design review with admins to validate blended layout and toggle semantics.
- Add Vitest component test plan ensuring bulk selection bar appears/disappears correctly.
- Include accessibility checklist for keyboard navigation across sections.

**Implement**
- Capture interaction diagrams (state machine) for toggles and bulk actions; ensure analytics hooks identified for Phase 10.
- Coordinate with backend on include-state enum semantics.
- Update localization key matrix for section titles, tooltips, bulk action prompts.

**Reflect**
- Record feedback on layout density (cards vs. list) for potential iteration.
- Log risk if YouTube API result quotas constrain blended queries.

## ADMIN-MVP-09 — Channel & Playlist Drawers
```yaml
meta:
  id: ADMIN-MVP-09
  status: planned
  owner: TBD-frontend
  depends: [ADMIN-MVP-08]
  lastReviewed: 2025-09-30
```
**Estimate**: 3h.

**Goals**
- Mirror YouTube channel and playlist detail layouts within admin drawer UX, including tabbed navigation (Videos, Shorts, Live, Playlists, Posts).
- Ensure per-item Include/Exclude toggles with unsaved-change prompts and Apply/Discard flow.
- Plan bulk controls inside drawers (select all, exclude all) and keyboard accessibility.

**Propose diff**
- `docs/ux/ui-spec.md`: add channel/playlist drawer specs with measurements, tab behavior, and confirmation dialogues.
- `docs/api/openapi-draft.yaml`: confirm channel/playlist detail responses expose excluded item ID arrays for drawer hydration.
- `docs/data/json-schemas/channel-detail.json`, `playlist-detail.json`: include excluded ID lists.

**Tests**
- Define component/unit tests verifying unsaved-change modals trigger properly.
- Accessibility review for focus trapping inside drawer and tab order.
- Manual QA plan for bulk apply/cancel flows across locales.

**Implement**
- Outline state model for pending changes vs. persisted state; document optimistic update strategy.
- Coordinate with backend to batch include/exclude mutations and audit logging.
- Draft analytics events for Apply/Discard actions.

**Reflect**
- Capture moderator feedback on drawer ergonomics; adjust backlog if additional filtering needed.
- Note dependency on Exclusions revamp in Phase 4.
