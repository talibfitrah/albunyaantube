# Phases 4–12 Ticket Breakdown

This appendix extends the Phase 3 ticket backlog to cover the remaining roadmap. For each phase, it maps the roadmap objectives to actionable 2–4 hour tickets while grounding the work in the supporting architecture, UX, testing, and security specs.【F:docs/roadmap/roadmap.md†L5-L19】【F:docs/architecture/solution-architecture.md†L28-L57】【F:docs/testing/test-strategy.md†L11-L41】

## Phase 4 — Admin UI Complete
Phase 4 focuses on rounding out the admin console with exclusions management, user administration, audit history, and accessibility polish so the console is feature complete per the roadmap and acceptance criteria.【F:docs/roadmap/roadmap.md†L11-L19】【F:docs/acceptance/criteria.md†L36-L53】

### ADMIN-COMP-01 — Implement exclusions editor end-to-end
- **Estimate**: 4h
- **Goal**: Deliver the exclusions workspace so admins can manage channel/playlist/video exclusions with reason capture per AC-ADM-005.【F:docs/acceptance/criteria.md†L36-L40】【F:docs/data/json-schemas/exclusion-response.json†L1-L16】

**Plan**
1. Review exclusion schemas and determine required fields for the form (entity reference, reason, createdBy, timestamps).
2. Sketch the exclusions list/table layout matching admin table styling from the UI spec.
3. Identify backend endpoints for list/create/delete and confirm cursor pagination contract.

**Propose diff**
- `frontend/src/views/ExclusionsView.vue` (new): render table with filters, inline create modal.
- `frontend/src/components/exclusions/ExclusionForm.vue` (new): capture entity selector + reason.
- `frontend/src/stores/exclusions.ts` (new): Pinia store for fetching/creating/deleting exclusions.
- `frontend/src/services/exclusions.ts` (new): API client wired to `/admin/exclusions` endpoints.

**Tests**
- Vitest store test verifying pagination state resets after create/delete.
- Component test ensuring form validation prevents missing reason submission.

**Implement**
- Hook table actions to store methods and show toast confirmations.
- Apply localization tokens for labels and ensure RTL mirroring via logical CSS properties.

**Reflect**
- Record follow-up if batch exclusion import is required beyond manual entry.

### ADMIN-COMP-02 — Wire backend exclusion APIs
- **Estimate**: 3.5h
- **Goal**: Expose Spring Boot REST endpoints for listing/creating/removing exclusions backed by the schema and audit logging requirements.【F:docs/architecture/solution-architecture.md†L11-L26】【F:docs/security/threat-model.md†L12-L33】

**Plan**
1. Model JPA entities and repositories from the JSON schema, including relationships to channels/playlists/videos.
2. Define service layer enforcing RBAC (ADMIN only) and audit log writes.
3. Draft controller endpoints with cursor pagination parameters and validation.

**Propose diff**
- `backend/src/main/java/.../exclusion/ExclusionEntity.java` (new) with mappings to parent entities.
- `backend/src/main/java/.../exclusion/ExclusionRepository.java` (new) using Spring Data.
- `backend/src/main/java/.../exclusion/ExclusionService.java` (new) handling business logic + audit events.
- `backend/src/main/java/.../exclusion/ExclusionController.java` (new) exposing REST endpoints.
- Flyway migration to create `exclusions` table with audit columns.

**Tests**
- Spring Boot integration tests covering create/list/delete flows and audit log entry assertions.
- Validation test ensuring duplicate exclusion for same entity is rejected.

**Implement**
- Secure endpoints with `@PreAuthorize("hasRole('ADMIN')")` and ensure audit log service is invoked.
- Map DTOs using MapStruct or manual mappers aligning with schema fields.

**Reflect**
- Confirm API aligns with OpenAPI draft and log follow-up if documentation needs regeneration.

### ADMIN-COMP-03 — Ship admin user management UI
- **Estimate**: 3h
- **Goal**: Provide CRUD screens for admin users enforcing RBAC and localized messaging per AC-ADM-001.【F:docs/acceptance/criteria.md†L19-L24】【F:docs/architecture/solution-architecture.md†L35-L39】

**Plan**
1. Review existing auth store to understand token retrieval for user management calls.
2. Design user table with invite/reset actions mirroring registry table styles.
3. Define modal forms for create/edit enforcing role selection and password reset triggers.

**Propose diff**
- `frontend/src/views/UsersView.vue` (new) with table, pagination, and action buttons.
- `frontend/src/components/users/UserForm.vue` (new) capturing email, roles, locale preferences.
- Extend `frontend/src/stores/auth.ts` or new `users` store to fetch/manage admin accounts.
- `frontend/src/services/users.ts` (new) hitting `/admin/users` endpoints.

**Tests**
- Component test confirming only ADMIN role sees create button.
- Vitest test ensuring form validation enforces role selection.

**Implement**
- Integrate toast notifications and optimistic updates while respecting error handling strategy.
- Ensure forms support RTL text and label associations for accessibility compliance.

**Reflect**
- Note if invitation emails or password management needs separate automation in later phases.

### ADMIN-COMP-04 — Build audit log viewer with filters
- **Estimate**: 3.5h
- **Goal**: Surface audit trail with cursor pagination and filters (actor, resource, action) satisfying AC-ADM-002 and security retention controls.【F:docs/acceptance/criteria.md†L19-L24】【F:docs/security/threat-model.md†L12-L32】

**Plan**
1. Inspect audit JSON schema to enumerate columns for display.
2. Plan filter controls for action/resource types aligned with schema enums.
3. Determine pagination strategy and empty state messaging.

**Propose diff**
- `frontend/src/views/AuditLogView.vue` (new) showing paginated table + filters.
- `frontend/src/components/audit/AuditFilters.vue` (new) for actor/action/resource selectors.
- `frontend/src/stores/audit.ts` (new) managing filters/cursors.
- `frontend/src/services/audit.ts` (new) wrapping `/admin/audit` endpoint.

**Tests**
- Store test verifying filter changes reset pagination and trigger fetch.
- Component snapshot under Arabic locale to ensure alignment.

**Implement**
- Render timestamp with localized format using `Intl.DateTimeFormat` per i18n strategy.
- Add export button placeholder logging TODO for later bulk export automation.

**Reflect**
- Capture any performance concerns if audit pages exceed threshold and consider server-side filtering enhancements.

### ADMIN-COMP-05 — Accessibility audit & fixes
- **Estimate**: 2.5h
- **Goal**: Address outstanding WCAG AA issues across admin modules using the accessibility guidelines in the UI spec and acceptance criteria.【F:docs/ux/ui-spec.md†L84-L103】【F:docs/acceptance/criteria.md†L50-L54】

**Plan**
1. Run axe-core against key screens (Dashboard, Registry, Users, Audit) to collect violations.
2. Categorize issues (contrast, labels, focus order) and prioritize quick wins.
3. Prepare patch list mapping each violation to component updates.

**Propose diff**
- Update affected components (layout, tables, forms) to add ARIA labels, adjust contrast tokens, and fix focus order.
- `frontend/src/styles/accessibility.scss` (new) centralizing high-contrast overrides.

**Tests**
- Vitest/Playwright accessibility assertions verifying no critical axe violations remain.
- Keyboard navigation test ensuring focus trap removed from modals.

**Implement**
- Apply logical focus order, add skip links, and ensure button labels localized across locales.

**Reflect**
- Document remaining medium-risk issues requiring design buy-in in follow-up backlog item.

### ADMIN-COMP-06 — Update admin documentation & onboarding
- **Estimate**: 2h
- **Goal**: Refresh README/backlog to capture new admin features, locale support, and a11y expectations for contributors.【F:README.md†L1-L24】【F:docs/backlog/product-backlog.csv†L1-L10】

**Plan**
1. Inventory documentation touchpoints referencing admin capabilities.
2. Draft new sections covering exclusions workflow, user management, audit review, and accessibility tooling.
3. Coordinate with backlog CSV to mark Phase 4 stories and link ticket IDs.

**Propose diff**
- `README.md`: add Phase 4 status callouts and navigation pointers.
- `frontend/README.md`: document new npm scripts for accessibility linting.
- `docs/backlog/product-backlog.csv`: update status/owners for Phase 4 stories.
- `docs/runbooks/admin-onboarding.md` (new): step-by-step for new engineers.

**Tests**
- Markdown lint (if configured) or spellcheck to ensure docs quality.

**Implement**
- Include links to accessibility resources and highlight locale testing expectations.

**Reflect**
- Capture feedback from onboarding buddy to iterate on runbook clarity.

## Phase 5 — Android Skeleton
Phase 5 establishes the Android navigation foundation, onboarding, and locale switcher per the architecture and UI specs.【F:docs/roadmap/roadmap.md†L12-L19】【F:docs/architecture/solution-architecture.md†L28-L34】【F:docs/ux/ui-spec.md†L31-L83】

### AND-SKEL-01 — Scaffold navigation graph and bottom nav
- **Estimate**: 3.5h
- **Goal**: Implement single-activity Navigation Component graph with bottom navigation destinations (Home, Channels, Playlists, Videos).【F:docs/architecture/solution-architecture.md†L28-L33】【F:docs/ux/ui-spec.md†L47-L77】

**Plan**
1. Define `NavHost` container and destination fragments per screen spec.
2. Configure bottom nav menu XML with icons/labels respecting design tokens.
3. Wire navigation actions and state preservation between tabs.

**Propose diff**
- `android/app/src/main/res/navigation/app_nav_graph.xml` (new) defining destinations.
- `android/app/src/main/res/menu/main_bottom_nav.xml` (new) listing menu items.
- `android/app/src/main/java/.../MainActivity.kt`: setup `NavController` and bottom nav integration.
- Skeleton fragment classes/layouts for each destination.

**Tests**
- Instrumentation test verifying bottom nav selection swaps fragments and preserves state.
- Unit test ensuring nav graph destinations match expected IDs.

**Implement**
- Apply theme styles for active tab indicator and ensure accessibility labels align with localization keys.

**Reflect**
- Note need for future deep link integration once backend endpoints ready.

### AND-SKEL-02 — Build onboarding flow per UI spec
- **Estimate**: 3h
- **Goal**: Implement carousel-based onboarding with help modal and skip CTA aligned with spec assets.【F:docs/ux/ui-spec.md†L41-L45】

**Plan**
1. Create ViewPager2 (or Compose equivalent) for slides using design tokens.
2. Add skip/help buttons with proper accessibility descriptions.
3. Persist completion flag via DataStore to bypass onboarding on subsequent launches.

**Propose diff**
- `android/app/src/main/res/layout/fragment_onboarding.xml` and supporting view models.
- `android/app/src/main/java/.../OnboardingViewModel.kt` handling state and DataStore writes.
- `android/app/src/main/java/.../datastore/UserPreferences.kt` for persistence.

**Tests**
- Instrumentation test verifying onboarding only shows once after completion.
- Unit test for ViewModel ensuring DataStore flag set when user finishes.

**Implement**
- Wire slide content with localized strings and ensure RTL swiping works.

**Reflect**
- Capture TODO for analytics instrumentation if required in later phases.

### AND-SKEL-03 — Implement locale switcher & configuration handling
- **Estimate**: 3.5h
- **Goal**: Provide in-app locale picker and configuration change handling per localization strategy.【F:docs/i18n/strategy.md†L24-L52】【F:docs/architecture/solution-architecture.md†L32-L34】

**Plan**
1. Create settings screen with locale options (en/ar/nl) reading from resources.
2. Integrate `AppCompatDelegate.setApplicationLocales` to apply changes without full restart.
3. Ensure onboarding respects default locale detection on first launch.

**Propose diff**
- `android/app/src/main/res/xml/locales_config.xml` (new) enumerating locales.
- `android/app/src/main/java/.../settings/LocaleSettingsFragment.kt` (new).
- Update `MainActivity` to observe locale preference and re-render UI.
- Extend DataStore preferences to persist locale selection.

**Tests**
- Instrumentation test verifying locale change updates text and navigation order for Arabic.
- Unit test ensuring DataStore persistence round-trips locale enum.

**Implement**
- Update strings resources for base/en/ar/nl and ensure Arabic resources have mirrored icons.

**Reflect**
- Document manual QA checklist for RTL verification in test strategy.

### AND-SKEL-04 — Home skeleton with section placeholders
- **Estimate**: 2.5h
- **Goal**: Build Home fragment layout with placeholder components for Channels/Playlists/Videos sections aligned to UI spec layout grid.【F:docs/ux/ui-spec.md†L47-L51】

**Plan**
1. Create layout with header, search action placeholders, and section RecyclerViews.
2. Implement stub adapters with static data to validate spacing.
3. Hook up shared ViewModel ready for backend integration in later phases.

**Propose diff**
- `HomeFragment` layout and adapter classes for each section.
- `android/app/src/main/res/layout/item_home_channel.xml` etc using design tokens.
- `android/app/src/main/java/.../home/HomeViewModel.kt` returning stub data.

**Tests**
- UI test verifying sections render with 3 items each and proper headings.
- Unit test ensuring ViewModel emits stub data for preview/testing.

**Implement**
- Apply spacing via dimension resources and set content descriptions for accessibility.

**Reflect**
- Capture open question about dynamic sections once backend home endpoint ready.

### AND-SKEL-05 — Document Android project setup
- **Estimate**: 2h
- **Goal**: Capture navigation, onboarding, and locale setup steps in the documentation workspace so Android engineers can ramp quickly while aligning with the shared test strategy.【F:README.md†L1-L12】【F:docs/testing/test-strategy.md†L28-L36】

**Plan**
1. Outline new modules/components added in Phase 5.
2. Document commands for running unit/instrumentation tests.
3. Capture locale testing and onboarding flag reset instructions.

**Propose diff**
- `docs/runbooks/android-onboarding.md` (new) summarizing setup for new mobile engineers.
- `README.md`: add quick link to the Android onboarding runbook once created.

**Tests**
- Markdown lint/spellcheck as applicable.

**Implement**
- Include screenshots/links to UI spec where helpful and note emulator locale tips.

**Reflect**
- Gather feedback from Android devs to refine runbook in future phase.

## Phase 6 — Lists & Home Rules
Phase 6 adds paging, 3-latest rule, and robust error handling to the Android client leveraging backend contracts.【F:docs/roadmap/roadmap.md†L13-L16】【F:docs/api/openapi-draft.yaml†L180-L248】【F:docs/testing/test-strategy.md†L11-L27】

### AND-LISTS-01 — Integrate cursor pagination for lists
- **Estimate**: 3.5h
- **Goal**: Wire Paging 3 with backend cursor schema for channels/playlists/videos including loading/error states.【F:docs/api/openapi-draft.yaml†L180-L213】【F:docs/architecture/solution-architecture.md†L28-L33】

**Plan**
1. Generate API client from OpenAPI or extend Retrofit interfaces for paginated endpoints.
2. Implement PagingSource classes per entity type mapping cursor tokens.
3. Update list fragments to submit paging data and render skeleton states.

**Propose diff**
- Retrofit interfaces & DTOs aligning with `cursor-page-info.json`.
- `ChannelsPagingSource`, `PlaylistsPagingSource`, `VideosPagingSource` classes.
- Fragment updates to use `PagingDataAdapter` and display loading/empty/error views.

**Tests**
- Unit tests for PagingSources verifying cursor extraction and error propagation.
- Instrumentation test ensuring endless scroll loads subsequent pages.

**Implement**
- Add retry actions for failures and ensure localized error copy from resources.

**Reflect**
- Evaluate caching strategy for offline tolerance in later phases.

### AND-LISTS-02 — Enforce “3-latest” home rule
- **Estimate**: 3h
- **Goal**: Apply client logic to limit home sections to the three latest items per source while displaying backend-supplied ordering.【F:docs/ux/ui-spec.md†L47-L51】【F:docs/acceptance/criteria.md†L16-L23】

**Plan**
1. Update HomeViewModel to combine paged data with limit logic.
2. Ensure sections respect locale-specific titles and analytics events.
3. Provide fallback skeletons when less than three items available.

**Propose diff**
- HomeViewModel adjustments enforcing item count limit.
- Tests verifying transformation logic.
- UI updates to show “See all” button hooking to list fragments.

**Tests**
- Unit test confirming only latest three items emitted even if backend returns more.
- UI test verifying sections collapse gracefully when underfilled.

**Implement**
- Use Kotlin Flow operators to transform PagingData and update adapters.

**Reflect**
- Determine if backend should enforce rule server-side for consistency; document in backlog if needed.

### AND-LISTS-03 — Error state & retry UX
- **Estimate**: 2.5h
- **Goal**: Implement consistent error banners and retry buttons per UI guidelines when list loads fail.【F:docs/testing/test-strategy.md†L11-L27】【F:docs/ux/ui-spec.md†L95-L99】

**Plan**
1. Define shared error component with localized messages.
2. Hook component into Paging adapters to display on loadState errors.
3. Instrument analytics/logging for failures if required.

**Propose diff**
- `android/app/src/main/res/layout/view_error_state.xml` (new).
- `ErrorStateView` class and extension to Paging adapters.
- Update fragments to observe load state and toggle retry.

**Tests**
- Unit test verifying error view binding displays provided message.
- UI test ensuring retry triggers Paging refresh.

**Implement**
- Use Material motion for fade-in/out and ensure TalkBack announces errors.

**Reflect**
- Capture need for centralized telemetry if metrics lacking.

### AND-LISTS-04 — Backend pagination validation & caching
- **Estimate**: 3.5h
- **Goal**: Implement backend cursor validation and Redis caching invalidation meeting AC-BE-001 and AC-BE-003.【F:docs/acceptance/criteria.md†L41-L45】【F:docs/architecture/solution-architecture.md†L11-L18】

**Plan**
1. Add backend validator for cursor tokens ensuring signature and expiration.
2. Implement cache invalidation hooks triggered by moderation approvals.
3. Write service tests covering invalid cursor and cache bust scenarios.

**Propose diff**
- `backend/.../pagination/CursorValidator.java` (new) and integration into controllers.
- Redis cache service updates to evict list keys on moderation events.
- Tests under `backend/src/test/...` verifying behavior.

**Tests**
- Integration tests simulating invalid cursor requests returning 400 with `error=CLIENT_ERROR`.
- Cache invalidation test ensuring new content appears after approval within 5s.

**Implement**
- Leverage Spring events or transactional listeners to trigger cache eviction.

**Reflect**
- Assess whether to introduce background job for cache warmup; document if beneficial.

### AND-LISTS-05 — Update acceptance & test docs for paging rules
- **Estimate**: 2h
- **Goal**: Document paging, error handling, and home rule scenarios across acceptance/test strategy.【F:docs/acceptance/criteria.md†L16-L28】【F:docs/testing/test-strategy.md†L11-L27】

**Plan**
1. Add new acceptance criteria IDs covering retry UX and analytics logging if needed.
2. Update test strategy Android section with Paging 3 coverage and instrumentation steps.
3. Annotate backlog items with dependencies on backend caching work.

**Propose diff**
- `docs/acceptance/criteria.md`: add/clarify entries for paging and error states.
- `docs/testing/test-strategy.md`: expand Android testing bullets with Paging 3 specifics.
- `docs/backlog/product-backlog.csv`: update status/responsible engineers.

**Tests**
- Markdown lint/spellcheck.

**Implement**
- Ensure cross-links between docs and roadmap remain accurate.

**Reflect**
- Gather QA feedback to iterate on acceptance/test updates.

## Phase 7 — Channel & Playlist Details
Phase 7 deepens detail screens with tab behaviors, deep links, and exclusion enforcement guided by sequence diagrams and acceptance criteria.【F:docs/roadmap/roadmap.md†L14-L19】【F:docs/architecture/diagrams/channel-tabs-sequence.md†L1-L27】【F:docs/acceptance/criteria.md†L36-L40】

### AND-DETAIL-01 — Channel detail tabs & paging integration
- **Estimate**: 3.5h
- **Goal**: Implement tabbed channel detail screen using Paging 3 per sequence diagram, supporting Videos/Live/Shorts/Playlists/Posts.【F:docs/architecture/diagrams/channel-tabs-sequence.md†L1-L27】【F:docs/ux/ui-spec.md†L58-L63】

**Plan**
1. Create tab layout with ViewPager or Compose tabs matching spec spacing.
2. Wire each tab to dedicated PagingSource keyed by channelId.
3. Persist tab state across configuration changes.

**Propose diff**
- `ChannelDetailFragment` with TabLayout/ViewPager integration.
- PagingSources for each tab hitting `/channels/{id}/{tab}` endpoints.
- Layout XML for tab content reusing list/grid components.

**Tests**
- UI test verifying tab switching preserves scroll position when returning.
- Unit test ensuring correct endpoint invoked per tab selection.

**Implement**
- Apply skeleton placeholders while loading and ensure TalkBack announces tab context.

**Reflect**
- Evaluate performance for combined Paging requests; note optimization ideas.

### AND-DETAIL-02 — Playlist detail hero & download CTA
- **Estimate**: 3h
- **Goal**: Build playlist detail UI with hero layout, download button, and track offline availability flags per UI and download policy specs.【F:docs/ux/ui-spec.md†L68-L71】【F:docs/security/threat-model.md†L22-L32】

**Plan**
1. Implement hero view with gradient overlay and owner metadata.
2. Add download CTA respecting policy flag from backend response.
3. Prepare analytics hooks for download interactions.

**Propose diff**
- `PlaylistDetailFragment` layout with hero, button, and list adapter.
- Download button state management hooking to later offline module.
- Strings/resources for localized labels and policy messaging.

**Tests**
- UI test verifying button disabled when policy forbids downloads.
- Unit test ensuring hero binds data correctly across locales.

**Implement**
- Use MotionLayout or ConstraintLayout to achieve spec layout and accessible content descriptions.

**Reflect**
- Document dependency on Phase 9 offline implementation for actual download logic.

### AND-DETAIL-03 — Exclusion enforcement on detail screens
- **Estimate**: 2.5h
- **Goal**: Respect backend exclusion flags so excluded child items are hidden and labeled appropriately per acceptance criteria.【F:docs/acceptance/criteria.md†L36-L40】【F:docs/security/threat-model.md†L12-L20】

**Plan**
1. Update DTOs to include exclusion metadata from API.
2. Filter adapters to hide excluded items or mark with badge.
3. Add admin-only logging to surface unexpected exclusion states.

**Propose diff**
- DTO modifications and mapping functions for exclusion flags.
- Adapter changes to filter/hide items and show “Excluded” badge when necessary.
- Unit tests verifying transformations.

**Tests**
- UI test ensuring excluded items do not appear in lists.
- Unit test verifying analytics/log statements triggered when backend returns excluded items unexpectedly.

**Implement**
- Localize badge text and ensure accessibility labels note exclusion state.

**Reflect**
- Escalate to backend team if exclusion mismatches appear during QA.

### AND-DETAIL-04 — Deep link handling for channel/playlist routes
- **Estimate**: 3h
- **Goal**: Support `albunyaan://channel/{id}` and `albunyaan://playlist/{id}` deep links to navigate directly to detail screens.【F:docs/architecture/solution-architecture.md†L28-L34】【F:docs/acceptance/criteria.md†L16-L24】

**Plan**
1. Define navigation graph deep link entries for channel/playlist routes.
2. Handle incoming intents in MainActivity and route to appropriate destination.
3. Add tests for deep link navigation from external intents.

**Propose diff**
- Update nav graph with `<deepLink>` elements.
- Intent handling logic in `MainActivity`.
- Unit tests mocking intents to ensure correct navigation.

**Tests**
- Instrumentation test launching activity with deep link verifying correct fragment opens.
- Unit test for navigation controller handling.

**Implement**
- Ensure analytics capture deep link origin and adjust onboarding skip logic for direct links.

**Reflect**
- Document Play Store intent filters for Phase 12 release tasks.

### AND-DETAIL-05 — Update docs & backlog for detail features
- **Estimate**: 2h
- **Goal**: Capture new detail screen requirements and exclusion handling in acceptance/test docs and backlog.【F:docs/acceptance/criteria.md†L36-L40】【F:docs/testing/test-strategy.md†L28-L36】

**Plan**
1. Add acceptance criteria for deep links, hero layout, and exclusion enforcement.
2. Update test strategy with tabbed paging coverage.
3. Refresh backlog entries for Phase 7 stories with updated status/dependencies.

**Propose diff**
- `docs/acceptance/criteria.md`: add new AC IDs.
- `docs/testing/test-strategy.md`: expand Android section for detail screen tests.
- `docs/backlog/product-backlog.csv`: mark tasks as in-progress.

**Tests**
- Markdown lint/spellcheck.

**Implement**
- Ensure cross-references to sequence diagram remain accurate.

**Reflect**
- Solicit QA sign-off on new criteria.

## Phase 8 — Player & Background Audio
Phase 8 implements full playback experience including ExoPlayer controls, PiP, captions, and background audio per architecture/test strategy.【F:docs/roadmap/roadmap.md†L15-L19】【F:docs/architecture/solution-architecture.md†L28-L33】【F:docs/testing/test-strategy.md†L28-L37】

### AND-PLAYER-01 — Integrate ExoPlayer with MediaSession
- **Estimate**: 3.5h
- **Goal**: Instantiate ExoPlayer with MediaSession + notification for background playback aligning with architecture plan.【F:docs/architecture/solution-architecture.md†L28-L33】【F:docs/testing/test-strategy.md†L33-L37】

**Plan**
1. Create playback service hosting ExoPlayer and MediaSession.
2. Wire notification controls (play/pause/skip) and handle audio-only toggle.
3. Manage lifecycle between activity/fragment and service.

**Propose diff**
- `PlaybackService.kt`, `PlaybackNotificationManager.kt` classes.
- Update `PlayerFragment` to bind to service and issue playback commands.
- Manifest changes for foreground service permission.

**Tests**
- Unit test verifying service reacts to play/pause intents and updates state.
- Instrumentation test ensuring playback continues when app backgrounded.

**Implement**
- Use dependency injection for ExoPlayer instance and ensure cleanup on destroy.

**Reflect**
- Note metrics instrumentation to add in Phase 12 release.

### AND-PLAYER-02 — Implement captions & quality selector
- **Estimate**: 3h
- **Goal**: Surface caption selection and quality chooser overlay meeting accessibility requirements.【F:docs/ux/ui-spec.md†L78-L82】【F:docs/acceptance/criteria.md†L16-L24】

**Plan**
1. Parse available tracks from backend manifest response.
2. Build bottom sheet for captions/quality with localized labels.
3. Persist user selection across sessions where possible.

**Propose diff**
- UI components for settings sheet and data models for tracks.
- Logic in PlayerViewModel to apply track selections.
- Strings updates for accessibility descriptions.

**Tests**
- Unit test verifying selected track persisted and reapplied.
- UI test ensuring captions toggle reflects active selection.

**Implement**
- Ensure TalkBack announces selection changes and fallback when captions unavailable.

**Reflect**
- Document requirement for future analytics on captions usage.

### AND-PLAYER-03 — Picture-in-Picture support
- **Estimate**: 2.5h
- **Goal**: Enable Android 12 PiP behavior with playback controls meeting reliability scenarios.【F:docs/ux/ui-spec.md†L78-L82】【F:docs/testing/test-strategy.md†L33-L37】

**Plan**
1. Configure manifest and activity to support PiP.
2. Implement PiP actions for play/pause and close.
3. Handle transitions between PiP and full screen without playback glitches.

**Propose diff**
- Manifest updates enabling PiP.
- MainActivity overrides for `onUserLeaveHint` and PiP mode changes.
- PlayerFragment adjustments to update UI when entering/exiting PiP.

**Tests**
- Instrumentation test verifying PiP entry/exit preserves playback state.
- Unit test for action handler mapping to playback commands.

**Implement**
- Provide descriptive PiP actions for accessibility and ensure metrics capture PiP usage.

**Reflect**
- Note device compatibility issues for QA follow-up.

### AND-PLAYER-04 — Backend playback manifest endpoint
- **Estimate**: 3.5h
- **Goal**: Implement backend endpoint delivering stream manifests with audio/video renditions and caption metadata.【F:docs/api/openapi-draft.yaml†L248-L320】【F:docs/architecture/solution-architecture.md†L11-L18】

**Plan**
1. Define DTOs for playback manifest referencing NewPipeExtractor integration.
2. Implement service fetching stream URLs, applying allow-list policy, and caching.
3. Expose controller endpoint with security checks and localization for captions.

**Propose diff**
- `backend/.../playback/PlaybackService.java` interfacing with extractor.
- Controller exposing `/videos/{id}/manifest` endpoint.
- Redis caching configuration for manifests with TTL.

**Tests**
- Integration test ensuring manifest includes expected tracks and respects exclusions.
- Security test verifying unauthorized access rejected.

**Implement**
- Ensure download policy enforcement and telemetry hooks for playback errors.

**Reflect**
- Identify fallback strategy if extractor fails (Phase 10 resilience work).

### AND-PLAYER-05 — Update docs for playback features
- **Estimate**: 2h
- **Goal**: Document playback architecture, PiP, and testing strategy additions across docs/readme.【F:docs/architecture/solution-architecture.md†L28-L33】【F:docs/testing/test-strategy.md†L33-L37】

**Plan**
1. Update solution architecture playback section with new service details.
2. Expand test strategy with player reliability scenarios and device farm coverage.
3. Refresh backlog entries for player tasks and note dependencies.

**Propose diff**
- `docs/architecture/solution-architecture.md`: append playback details and diagrams references.
- `docs/testing/test-strategy.md`: enrich player reliability subsection.
- `docs/backlog/product-backlog.csv`: update statuses.

**Tests**
- Markdown lint/spellcheck.

**Implement**
- Include pointers to new instrumentation scripts and QA metrics targets.

**Reflect**
- Solicit PM approval for updated roadmap narrative.

## Phase 9 — Downloads & Offline
Phase 9 introduces download queue management, notifications, policy gating, and storage quotas guided by security controls and architecture plans.【F:docs/roadmap/roadmap.md†L16-L19】【F:docs/architecture/solution-architecture.md†L28-L33】【F:docs/security/threat-model.md†L12-L33】

### AND-DL-01 — Offline storage manager & quota enforcement
- **Estimate**: 3.5h
- **Goal**: Implement storage manager enforcing quotas and app-private storage per security policy.【F:docs/architecture/solution-architecture.md†L28-L33】【F:docs/security/threat-model.md†L22-L33】

**Plan**
1. Define storage quota constants and thresholds.
2. Implement manager handling file writes, deletions, and disk space monitoring.
3. Integrate with download service to block when quota exceeded.

**Propose diff**
- `OfflineStorageManager.kt` handling file operations.
- Integration with WorkManager download tasks.
- Preferences/DataStore updates storing quota usage.

**Tests**
- Unit test simulating disk usage to ensure quota enforcement triggers.
- Instrumentation test verifying user notified when quota reached.

**Implement**
- Use encrypted file system if required and ensure localized warnings.

**Reflect**
- Document need for manual cleanup UI in backlog.

### AND-DL-02 — Download queue WorkManager orchestration
- **Estimate**: 3h
- **Goal**: Orchestrate downloads via WorkManager with pause/resume and notification updates.【F:docs/architecture/solution-architecture.md†L28-L33】【F:docs/testing/test-strategy.md†L28-L36】

**Plan**
1. Create WorkManager tasks for queued downloads with progress reporting.
2. Implement notification channel and updates per download state.
3. Provide UI controls to pause/resume/cancel from downloads screen.

**Propose diff**
- `DownloadWorker.kt`, `DownloadRepository.kt` for queue management.
- UI components for downloads list and controls.
- Notification channel setup in Application class.

**Tests**
- WorkManager test harness verifying pause/resume flows.
- UI test ensuring notifications update on progress.

**Implement**
- Ensure policy gating: check EULA acceptance before enqueuing downloads.

**Reflect**
- Evaluate need for background sync to resume downloads on connectivity changes.

### AND-DL-03 — Policy gating & EULA enforcement
- **Estimate**: 2.5h
- **Goal**: Enforce download policy flags and require EULA acceptance before downloads proceed per acceptance criteria.【F:docs/acceptance/criteria.md†L23-L28】【F:docs/security/threat-model.md†L22-L33】

**Plan**
1. Update login/auth flow to surface EULA prompt and persist acceptance.
2. Gate download actions behind policy flags from backend manifest.
3. Provide localized copy referencing halal policy.

**Propose diff**
- Auth ViewModel updates capturing `downloadPolicy` and `eulaAccepted` flags.
- UI dialog for EULA acceptance with checkbox.
- Backend updates ensuring manifest reflects policy state if needed.

**Tests**
- Integration test verifying download blocked until EULA accepted.
- Unit test for policy gating logic.

**Implement**
- Persist acceptance timestamp for auditing and ensure offline flows respect gating.

**Reflect**
- Coordinate with legal/compliance for EULA copy review.

### AND-DL-04 — Backend download manifest security hardening
- **Estimate**: 3h
- **Goal**: Enforce signed URLs, token checks, and rate limiting on download manifest endpoints per threat model.【F:docs/security/threat-model.md†L12-L33】【F:docs/architecture/solution-architecture.md†L11-L18】

**Plan**
1. Implement signed URL generation with expiration and single-use enforcement.
2. Add rate limiting middleware and JWT validation tailored to downloads.
3. Write integration tests covering tampering and replay scenarios.

**Propose diff**
- Backend service methods generating signed URLs and verifying tokens.
- Middleware/filter for rate limiting download endpoints.
- Tests under security package simulating attacks.

**Tests**
- Security test ensuring tampered signatures rejected.
- Load test verifying rate limits behave as expected.

**Implement**
- Integrate with Redis for blacklist tracking and logging for audits.

**Reflect**
- Document secrets rotation process in ops runbook.

### AND-DL-05 — Update docs & backlog for offline features
- **Estimate**: 2h
- **Goal**: Capture download workflow, quota policy, and testing guidance in docs/backlog.【F:docs/testing/test-strategy.md†L28-L37】【F:docs/acceptance/criteria.md†L23-L28】

**Plan**
1. Add acceptance criteria for quota enforcement and WorkManager flows.
2. Expand test strategy with offline scenarios and crash recovery steps.
3. Update backlog items with dependencies on security hardening tasks.

**Propose diff**
- `docs/acceptance/criteria.md`: new AC IDs for downloads.
- `docs/testing/test-strategy.md`: offline testing additions.
- `docs/backlog/product-backlog.csv`: reflect updated sequencing.

**Tests**
- Markdown lint/spellcheck.

**Implement**
- Ensure cross-links to threat model for policy context.

**Reflect**
- Gather QA/law feedback post-update.

## Phase 10 — Performance & Security Hardening
Phase 10 tightens caching, performance budgets, and security testing per architecture and threat model guidance.【F:docs/roadmap/roadmap.md†L17-L19】【F:docs/architecture/solution-architecture.md†L48-L57】【F:docs/security/threat-model.md†L12-L33】

### HARDEN-01 — Backend performance profiling & budget enforcement
- **Estimate**: 3.5h
- **Goal**: Instrument backend with performance metrics and enforce payload/latency budgets from test strategy.【F:docs/architecture/solution-architecture.md†L48-L53】【F:docs/testing/test-strategy.md†L11-L27】

**Plan**
1. Configure Micrometer timers for key endpoints and expose Prometheus metrics.
2. Implement payload size guards ensuring ≤80KB per list response.
3. Run Gatling profiles to capture baseline metrics.

**Propose diff**
- Backend config enabling Micrometer exporters and payload validators.
- Gatling test scripts under `backend/perf-tests`.
- Documentation updates summarizing metrics.

**Tests**
- Automated Gatling run verifying thresholds.
- Unit tests for payload guard utility.

**Implement**
- Wire alerts thresholds and update README with profiling steps.

**Reflect**
- Identify hotspots requiring caching or query optimization follow-up.

### HARDEN-02 — Redis caching audit & optimizations
- **Estimate**: 3h
- **Goal**: Audit Redis usage for list endpoints, improve cache hit ratio, and document invalidation strategy.【F:docs/architecture/solution-architecture.md†L15-L18】【F:docs/testing/test-strategy.md†L11-L27】

**Plan**
1. Review current cache keys/TTLs and compare against access patterns.
2. Implement metrics capturing hit/miss ratios per locale.
3. Optimize TTLs or add prefetching for high-traffic queries.

**Propose diff**
- Cache service updates to track metrics and adjust TTL constants.
- Dashboard/metrics docs capturing hit ratio targets.
- Tests verifying new caching behavior.

**Tests**
- Integration tests ensuring cache invalidates on updates.
- Unit tests for key computation logic.

**Implement**
- Add Grafana dashboard config (if stored in repo) or doc instructions.

**Reflect**
- Log backlog item if advanced caching (e.g., write-through) required.

### HARDEN-03 — Security regression suite & dependency scanning
- **Estimate**: 3h
- **Goal**: Automate OWASP ZAP scans, dependency checks, and JWT rotation tests per threat model controls.【F:docs/security/threat-model.md†L12-L47】【F:docs/testing/test-strategy.md†L11-L27】

**Plan**
1. Configure CI job running ZAP baseline scan against staging endpoints.
2. Integrate OWASP Dependency-Check or similar tool into build.
3. Add automated test validating refresh token rotation and blacklist behavior.

**Propose diff**
- CI workflow files for security scans.
- Backend integration test for refresh rotation.
- Documentation describing remediation workflow.

**Tests**
- Run CI job locally or via container to confirm scans pass.
- Integration test verifying revoked tokens blocked.

**Implement**
- Ensure secrets handled securely in CI config.

**Reflect**
- Document recurring cadence for manual pen-test scheduling.

### HARDEN-04 — Admin SPA performance & accessibility checks
- **Estimate**: 2.5h
- **Goal**: Measure admin bundle performance and run accessibility audits post Phase 4 completion.【F:docs/ux/ui-spec.md†L84-L103】【F:docs/testing/test-strategy.md†L19-L27】

**Plan**
1. Use Lighthouse (or similar) to measure performance/accessibility scores.
2. Identify bundle size regressions and lazy-load opportunities.
3. Prepare improvement checklist for follow-up tasks.

**Propose diff**
- Performance budget config for Vite build (if available).
- Documentation summarizing audit findings and action items.
- Optional code-splitting adjustments for heavy routes.

**Tests**
- Lighthouse CI or manual run capturing metrics.

**Implement**
- Apply quick wins (e.g., route-level code splitting) and update docs.

**Reflect**
- Schedule follow-up tickets for deeper optimizations if budgets not met.

### HARDEN-05 — Update security/perf documentation
- **Estimate**: 2h
- **Goal**: Record performance/security improvements across solution architecture, threat model, and runbooks.【F:docs/architecture/solution-architecture.md†L48-L57】【F:docs/security/threat-model.md†L12-L47】

**Plan**
1. Document new metrics dashboards and caching strategy adjustments.
2. Update threat model with mitigations implemented in this phase.
3. Refresh ops runbook with monitoring/alerting steps.

**Propose diff**
- `docs/architecture/solution-architecture.md`: performance updates.
- `docs/security/threat-model.md`: add controls coverage.
- `docs/runbooks/operations.md` (new) detailing monitoring/alerts.

**Tests**
- Markdown lint/spellcheck.

**Implement**
- Include links/screenshots of dashboards if available.

**Reflect**
- Request stakeholder review to close phase.

## Phase 11 — i18n & Accessibility Polish
Phase 11 finalizes localization QA, bidi handling, numeral formatting, and accessibility compliance guided by i18n strategy and acceptance criteria.【F:docs/roadmap/roadmap.md†L18-L19】【F:docs/i18n/strategy.md†L1-L74】【F:docs/acceptance/criteria.md†L46-L54】

### I18N-01 — Localization QA sweep with pseudo-locale
- **Estimate**: 2.5h
- **Goal**: Execute pseudo-localization run across Android/Admin to catch truncation and concatenation issues per QA guidelines.【F:docs/i18n/strategy.md†L61-L76】【F:docs/acceptance/criteria.md†L46-L54】

**Plan**
1. Enable pseudo-locale builds and run through key flows capturing screenshots.
2. Log issues (truncation, RTL alignment, missing translations) into backlog.
3. Update localization spreadsheet with findings.

**Propose diff**
- Scripts/config enabling pseudo-locale builds (e.g., Gradle task, Vite flag).
- Documentation summarizing results and linking to backlog entries.

**Tests**
- Manual QA documented with screenshots; attach to report.

**Implement**
- Ensure screenshot artifacts stored under `docs/ux/mockups/` with date folder.

**Reflect**
- Coordinate with translation team on remediation timeline.

### I18N-02 — Numeral & date localization enforcement
- **Estimate**: 3h
- **Goal**: Ensure numerals/dates use locale-specific formats, including Arabic Indic digits where required.【F:docs/i18n/strategy.md†L1-L44】【F:docs/acceptance/criteria.md†L46-L53】

**Plan**
1. Audit existing formatting utilities across backend, admin, Android.
2. Implement wrappers using locale-aware `NumberFormat`/`DateTimeFormatter`.
3. Update UI components to consume new helpers.

**Propose diff**
- Shared localization utility modules in each platform.
- Unit tests verifying outputs for en/ar/nl.
- Documentation describing usage guidelines.

**Tests**
- Automated tests across platforms verifying numeral/date outputs.

**Implement**
- Replace direct `toString()` usages with helpers and ensure RTL contexts preserve punctuation.

**Reflect**
- Capture follow-up if other locales added later need additional rules.

### I18N-03 — TalkBack/Screen reader audit & fixes
- **Estimate**: 3h
- **Goal**: Validate accessibility across Android/Admin for TalkBack/keyboard navigation per acceptance criteria.【F:docs/ux/ui-spec.md†L89-L99】【F:docs/acceptance/criteria.md†L50-L54】

**Plan**
1. Run TalkBack (Android) and keyboard navigation (web) through critical flows.
2. Document missing labels, incorrect focus order, or gestures.
3. Patch components to resolve findings.

**Propose diff**
- UI component updates adding labels/ARIA attributes.
- Accessibility documentation updates capturing test protocol.

**Tests**
- Manual verification with TalkBack/VoiceOver; optionally script with accessibility testing tools.

**Implement**
- Ensure focus order follows visual layout and update tests accordingly.

**Reflect**
- Provide summary to compliance team for sign-off.

### I18N-04 — RTL regression automation
- **Estimate**: 2.5h
- **Goal**: Automate RTL snapshot tests for Admin and Android components to prevent regressions.【F:docs/i18n/strategy.md†L24-L52】【F:docs/testing/test-strategy.md†L19-L36】

**Plan**
1. Configure Vitest/Playwright to run RTL snapshots for key admin screens.
2. Add Paparazzi or Compose screenshot tests for Android in Arabic locale.
3. Integrate into CI to run on pull requests.

**Propose diff**
- Test harness scripts/config files for RTL runs.
- CI workflow updates triggering new suites.
- Documentation describing how to update snapshots.

**Tests**
- Execute new snapshot suites ensuring deterministic output.

**Implement**
- Store baseline snapshots in repo and update contributor guide.

**Reflect**
- Monitor CI runtime impact and adjust scope if needed.

### I18N-05 — Documentation & backlog updates
- **Estimate**: 2h
- **Goal**: Update localization QA guide, acceptance criteria, and backlog with Phase 11 outcomes.【F:docs/i18n/strategy.md†L61-L76】【F:docs/acceptance/criteria.md†L46-L54】

**Plan**
1. Document QA process results and any outstanding issues.
2. Update acceptance criteria status for localization/accessibility items.
3. Adjust backlog entries to reflect completion and remaining follow-ups.

**Propose diff**
- `docs/i18n/strategy.md`: append QA findings and process notes.
- `docs/acceptance/criteria.md`: mark Phase 11 criteria coverage.
- `docs/backlog/product-backlog.csv`: update statuses.

**Tests**
- Markdown lint/spellcheck.

**Implement**
- Include links to screenshot evidence and test runs.

**Reflect**
- Gather translation vendor feedback for continuous improvement.

## Phase 12 — Beta & Launch
Phase 12 prepares telemetry, crash reporting, rollout, and rollback planning per release management guidance.【F:docs/roadmap/roadmap.md†L19-L25】【F:docs/testing/test-strategy.md†L58-L60】【F:docs/security/threat-model.md†L34-L45】

### LAUNCH-01 — Instrument analytics & telemetry
- **Estimate**: 3h
- **Goal**: Configure analytics events and telemetry pipelines needed for beta monitoring.【F:docs/testing/test-strategy.md†L58-L60】【F:docs/architecture/solution-architecture.md†L54-L57】

**Plan**
1. Identify critical events (downloads, playback, moderation actions) with PM.
2. Integrate analytics SDKs on Android/Admin with privacy-compliant configurations.
3. Document event taxonomy and dashboards.

**Propose diff**
- Android/Admin instrumentation code hooking into analytics library.
- Documentation describing event schemas and dashboards.
- Configuration files/secrets management guidelines.

**Tests**
- Unit tests verifying events fire under key flows.
- Manual verification in staging dashboards.

**Implement**
- Ensure GDPR compliance by anonymizing user IDs and providing opt-out.

**Reflect**
- Plan follow-up for long-term data retention policies.

### LAUNCH-02 — Crash reporting & alerting setup
- **Estimate**: 2.5h
- **Goal**: Integrate Crashlytics (Android) and equivalent web monitoring with alert thresholds.【F:docs/testing/test-strategy.md†L58-L60】【F:docs/architecture/solution-architecture.md†L54-L57】

**Plan**
1. Add Crashlytics SDK to Android project and configure build flavors.
2. Configure frontend error boundary logging to monitoring service.
3. Set up alert thresholds matching release gates.

**Propose diff**
- Android Gradle updates and initialization code for Crashlytics.
- Admin SPA error boundary instrumentation.
- Ops documentation describing alert routing.

**Tests**
- Trigger test crash to confirm reporting pipeline.
- Verify alert notifications received by on-call channel.

**Implement**
- Redact PII from reports and confirm user consent flows.

**Reflect**
- Schedule periodic review of crash metrics during beta.

### LAUNCH-03 — Rollout & rollback playbook
- **Estimate**: 3h
- **Goal**: Produce documented rollout plan, including staged rollout, rollback, and incident response coordination.【F:docs/testing/test-strategy.md†L58-L60】【F:docs/security/threat-model.md†L34-L45】

**Plan**
1. Draft rollout stages (internal, closed beta, open beta) with success metrics.
2. Define rollback procedures for backend and Android releases.
3. Align incident response steps with threat model playbook.

**Propose diff**
- `docs/runbooks/release-playbook.md` (new) capturing rollout plan and rollback steps.
- Update test strategy release management section with references to playbook.
- Backlog updates linking launch tasks to dependencies.

**Tests**
- Peer review checklist ensuring plan covers monitoring, communications, and approvals.

**Implement**
- Include contact matrix and timeline for beta launch.

**Reflect**
- Schedule dry run of rollback to validate readiness.

### LAUNCH-04 — Beta feedback intake & triage workflow
- **Estimate**: 2h
- **Goal**: Establish process and tooling for collecting, triaging, and prioritizing beta feedback.【F:docs/testing/test-strategy.md†L58-L60】【F:docs/backlog/product-backlog.csv†L1-L10】

**Plan**
1. Configure feedback channels (in-app, email, form) and routing to backlog.
2. Define triage cadence and severity labels.
3. Document workflow in backlog guidelines.

**Propose diff**
- `docs/runbooks/beta-feedback.md` (new) outlining triage process.
- Backlog CSV updates adding severity/status columns if needed.
- Admin UI instrumentation to surface feedback link.

**Tests**
- Dry run triage meeting using sample feedback.

**Implement**
- Ensure localization of feedback prompts and anonymization of user data.

**Reflect**
- Capture improvements after initial beta week.

### LAUNCH-05 — Final compliance & privacy checklist
- **Estimate**: 2.5h
- **Goal**: Verify compliance items (GDPR, halal governance, data retention) before launch per threat model.【F:docs/security/threat-model.md†L34-L45】【F:docs/acceptance/criteria.md†L23-L28】

**Plan**
1. Audit data retention, consent flows, and policy documentation.
2. Ensure EULA, privacy policy, and halal guidelines localized and accessible.
3. Prepare sign-off checklist for legal/compliance teams.

**Propose diff**
- `docs/security/threat-model.md`: update compliance section with verification notes.
- `docs/runbooks/compliance-checklist.md` (new) capturing sign-off steps.
- README/backlog updates indicating launch readiness status.

**Tests**
- Checklist review with legal/compliance stakeholders.

**Implement**
- Store signed approvals or meeting notes in repo (if allowed) or reference storage location.

**Reflect**
- Schedule periodic compliance audits post-launch.

