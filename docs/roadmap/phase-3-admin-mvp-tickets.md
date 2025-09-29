# Phase 3 — Admin UI MVP Ticket Breakdown

This plan enumerates the remaining work required to complete the Phase 3 exit criteria for the admin console. Phase 3 focuses on delivering the admin information architecture, routing, shared state, and localization wiring described in the roadmap and UX spec.【F:docs/roadmap/roadmap.md†L9-L15】【F:docs/ux/ui-spec.md†L84-L97】

## Current Status
- Admin shell, authentication, and protected routing are in place via the Vue 3 SPA scaffold.【F:frontend/src/layouts/AdminLayout.vue†L1-L88】【F:frontend/src/stores/auth.ts†L1-L166】
- Registry workspace renders paginated tables for channels, playlists, and videos backed by the admin registry endpoints.【F:frontend/src/views/RegistryLandingView.vue†L1-L83】【F:frontend/src/components/registry/VideosTable.vue†L1-L143】
- Moderation queue provides the required status filter, approve/reject actions, and localized copy.【F:frontend/src/views/ModerationQueueView.vue†L1-L271】【F:frontend/src/locales/messages.ts†L33-L104】
- Localization is currently English-only and the dashboard metrics remain static placeholders.【F:frontend/src/locales/messages.ts†L1-L108】【F:frontend/src/views/DashboardView.vue†L1-L47】

The following tickets decompose the remaining Phase 3 scope into 2–4 hour tasks. Each ticket follows the "plan → propose diff → tests → implement → reflect" workflow to reinforce disciplined delivery.【F:docs/testing/test-strategy.md†L19-L41】

## Ticket Backlog

### ADMIN-MVP-01 — Add admin locale switcher
- **Estimate**: 3h
- **Goal**: Provide the manual locale switcher mandated by the i18n strategy so admins can toggle between en/ar/nl within the console.【F:docs/i18n/strategy.md†L24-L44】

**Plan**
1. Audit existing layout to identify insertion point for a locale dropdown in the top bar.
2. Create a preferences store to persist the selected locale to `localStorage`.
3. Update `vue-i18n` initialization to hydrate from the persisted locale and expose a setter.

**Propose diff**
- `frontend/src/layouts/AdminLayout.vue`: add dropdown UI and wire change handler.
- `frontend/src/stores/preferences.ts` (new): manage persisted locale.
- `frontend/src/main.ts` / `frontend/src/locales/index.ts` (new or updated): bootstrap i18n with stored locale.

**Tests**
- Vitest unit test verifying the preferences store reads/writes locale from storage.
- Component test asserting locale change updates rendered navigation labels.

**Implement**
- Build dropdown with accessible markup and success-color focus ring per UI spec.
- Persist locale selection and trigger `i18n.global.locale` updates.

**Reflect**
- Confirm locale survives reload and log a follow-up if we need backend propagation via `X-Admin-Locale` header.

### ADMIN-MVP-02 — Ship Arabic and Dutch locale bundles with RTL support
- **Estimate**: 4h
- **Goal**: Expand admin copy to ar/nl, ensure CSS respects RTL mirroring, and satisfy localization wiring requirements.【F:docs/ux/ui-spec.md†L84-L115】【F:docs/i18n/strategy.md†L1-L44】

**Plan**
1. Split `messages.ts` into per-locale modules and populate translations sourced from localization spreadsheet.
2. Introduce a utility that toggles `dir="rtl"` on the document when locale is Arabic.
3. Replace directional CSS in layout/components with logical properties so mirroring works automatically.

**Propose diff**
- `frontend/src/locales/messages.ts` → refactor into `en.ts`, `ar.ts`, `nl.ts`, plus an index exporter.
- `frontend/src/layouts/AdminLayout.vue` and registry/moderation components: adjust padding/margin to use logical properties.
- `frontend/src/main.ts`: toggle `document.dir` based on current locale.
- `frontend/src/utils/rtl.ts` (new): helper for direction management.

**Tests**
- Vitest snapshot tests for key components under Arabic locale to ensure copy and alignment update.
- Playwright visual diff (if feasible) or DOM assertions verifying `dir` attribute flips.

**Implement**
- Import translations, ensure pluralization/ICU placeholders preserved.
- Apply CSS logical properties (e.g., `padding-inline`, `margin-inline`) and flex alignment that is direction-agnostic.

**Reflect**
- Validate no regressions in LTR layout; capture RTL screenshots for localization QA.

### ADMIN-MVP-03 — Implement shared category filter for registry tables
- **Estimate**: 4h
- **Goal**: Allow admins to filter channels/playlists/videos by category using the backend `categoryId` query, aligning with registry workflow expectations.【F:docs/api/openapi-draft.yaml†L185-L230】【F:docs/acceptance/criteria.md†L15-L37】

**Plan**
1. Design a filter toolbar component that renders above each table and surfaces available categories (seeded via API or static list for now).
2. Introduce a registry store to hold filter state so pagination components can react when filters change.
3. Update fetch calls to include the selected category in query params.

**Propose diff**
- `frontend/src/components/registry/RegistryFilters.vue` (new) with category select.
- `frontend/src/stores/registryFilters.ts` (new) to share filter state.
- `frontend/src/services/catalog.ts` (new) or extend existing services to load category options.
- Modify `ChannelsTable.vue`, `PlaylistsTable.vue`, `VideosTable.vue`, and `RegistryLandingView.vue` to incorporate the filter toolbar and pass category ID into `fetch*Page` calls.

**Tests**
- Vitest unit test for the store ensuring state resets when switching tabs.
- Component test verifying category change triggers a new fetch with expected query.

**Implement**
- Render the filter toolbar with localization-ready labels and skeleton while categories load.
- Debounce filter changes to avoid spamming network requests.

**Reflect**
- Confirm UX meets accessibility requirements (labels, keyboard navigation) and note follow-up if multi-select becomes necessary.

### ADMIN-MVP-04 — Add video query controls (search, length, date, sort)
- **Estimate**: 3.5h
- **Goal**: Surface the remaining `/videos` query parameters so admins can quickly locate entries by title, duration, publish window, or popularity.【F:docs/api/openapi-draft.yaml†L217-L248】

**Plan**
1. Extend the registry filters component with inputs for free-text search and dropdowns for length/date/sort options.
2. Ensure the registry filter store exposes video-specific state without affecting channels/playlists.
3. Update `fetchVideosPage` invocation and pagination summary to reflect applied filters.

**Propose diff**
- Update `RegistryFilters.vue` to render conditional controls when active tab is videos.
- Extend `registryFilters` store with namespaced state for video filters plus reset logic on tab change.
- Modify `services/registry.ts` to accept new parameters from the store.

**Tests**
- Vitest test verifying search input debounces and updates store correctly.
- Integration-style component test mocking fetch to confirm query string contains expected params for each filter.

**Implement**
- Add accessible labels/tooltips for filter inputs and ensure keyboard focus order respects spec.
- Use `watch` hooks to trigger reloads when filters change, resetting to first page.

**Reflect**
- Capture metrics on query frequency during manual QA to validate backend readiness; file performance follow-up if needed.

### ADMIN-MVP-05 — Expose dashboard metrics endpoint (backend)
- **Estimate**: 3h
- **Goal**: Provide backend support for dashboard KPIs (pending moderation count, total categories, active moderators) so the UI can display live data.【F:docs/roadmap/phase-1-backend-plan.md†L22-L39】【F:docs/acceptance/criteria.md†L31-L40】

**Plan**
1. Define DTO returning the three counts and add repository methods to compute them.
2. Implement `/api/v1/admins/dashboard` controller that aggregates counts using existing services/entities.
3. Document the endpoint in the OpenAPI draft and add integration coverage.

**Propose diff**
- `backend/src/main/java/com/albunyaan/tube/admin/AdminDashboardController.java` (new).
- Repository/service updates under `com.albunyaan.tube.moderation`, `com.albunyaan.tube.catalog`, and `com.albunyaan.tube.user` to expose count queries.
- `docs/api/openapi-draft.yaml`: add dashboard path + schema.
- `backend/src/test/java/...` integration test verifying counts with seeded data.

**Tests**
- Integration test using Testcontainers to validate counts and RBAC (only ADMIN can access).
- Unit tests for any helper services introduced.

**Implement**
- Wire controller with `@PreAuthorize` to restrict to ADMIN.
- Ensure response includes `traceId` header to satisfy observability criteria.

**Reflect**
- Note performance of count queries; consider caching if metrics become expensive later.

### ADMIN-MVP-06 — Consume dashboard metrics in frontend
- **Estimate**: 2.5h
- **Goal**: Replace placeholder metrics with live data from the new backend endpoint and localize number formatting.【F:frontend/src/views/DashboardView.vue†L1-L47】【F:docs/testing/test-strategy.md†L19-L41】

**Plan**
1. Create a lightweight dashboard service calling `/api/v1/admins/dashboard`.
2. Introduce a composable to load metrics on mount with loading/error states.
3. Update dashboard cards to display formatted counts and handle failures gracefully.

**Propose diff**
- `frontend/src/services/dashboard.ts` (new) with fetch helper.
- `frontend/src/composables/useDashboardMetrics.ts` (new) to manage loading state.
- `frontend/src/views/DashboardView.vue`: consume composable, render skeleton/error UI, and adjust localization strings if needed.

**Tests**
- Vitest test for composable ensuring it handles success/error cases and formats numbers per locale.
- Component test verifying loading skeleton renders before data resolves.

**Implement**
- Display spinner or skeleton while fetching; show inline error with retry button on failure.
- Use existing `formatNumber` utility to honor locale digits.

**Reflect**
- Confirm dashboard loads within acceptable time; capture TODO if we need polling or websockets in later phases.

### ADMIN-MVP-07 — Add Playwright smoke test for admin workflows
- **Estimate**: 4h
- **Goal**: Establish an end-to-end test covering login, registry pagination, and moderation actions in line with the admin frontend test strategy.【F:docs/testing/test-strategy.md†L25-L33】

**Plan**
1. Scaffold Playwright config targeting the Vite dev server and mocked backend endpoints.
2. Write a smoke test that logs in with seeded admin credentials, verifies registry table renders, and walks through a moderation approve flow using API mocks.
3. Integrate the Playwright run into CI (reusing existing GitHub Actions workflow or adding a new job).

**Propose diff**
- `frontend/playwright.config.ts` and `/tests/admin-smoke.spec.ts` (new) with fixtures for auth and API mocks.
- `package.json`: add Playwright scripts and dependencies.
- `.github/workflows/ci.yml`: append job to run Playwright headless (if workflow exists) or document manual run instructions in README.

**Tests**
- The Playwright smoke test itself acts as coverage; ensure it runs in CI.
- Optional: add unit tests for API mock helpers if complex.

**Implement**
- Use `msw` or Playwright route intercepts to stub backend responses for deterministic assertions.
- Capture video/screenshot artifacts for debugging failures.

**Reflect**
- Evaluate runtime and flake rate; adjust fixtures or CI parallelism as needed.

### ADMIN-MVP-08 — Update developer documentation for Phase 3 features
- **Estimate**: 2h
- **Goal**: Refresh developer docs so onboarding instructions cover locale switcher, filters, metrics endpoint, and Playwright usage, ensuring traceability of Phase 3 deliverables.【F:README.md†L1-L24】【F:frontend/README.md†L1-L22】

**Plan**
1. Document new environment variables or backend prerequisites for dashboard metrics.
2. Add instructions for running Playwright tests and switching locales locally.
3. Update roadmap/backlog status to reflect Phase 3 progress if needed.

**Propose diff**
- `frontend/README.md`: add sections for locale switcher, filters, testing commands.
- `README.md`: note Phase 3 readiness and point to new tickets.
- `docs/backlog/product-backlog.csv`: mark Phase 3 stories as in-progress/done if statuses tracked (optional, confirm with PM).

**Tests**
- N/A (documentation), but run markdown lint if available.

**Implement**
- Ensure instructions cross-link to relevant docs (roadmap, i18n strategy, test strategy).

**Reflect**
- Validate docs with a teammate walkthrough; capture any gaps for future doc sprints.

