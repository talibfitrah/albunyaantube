# Acceptance Criteria & Traceability Matrix

This document enumerates verifiable acceptance criteria linked to requirements, APIs, and test coverage. Use the IDs when creating tickets in [`../backlog/product-backlog.csv`](../backlog/product-backlog.csv).

## Legend
- **REQ**: High-level requirement from vision.
- **AC**: Acceptance criterion.
- **API**: Endpoint reference (see [`../api/openapi-draft.yaml`](../api/openapi-draft.yaml)).
- **TEST**: Pointer to test strategy section.

## Traceability Matrix
| REQ | Description | AC ID | Acceptance Criterion | API | TEST |
| --- | --- | --- | --- | --- | --- |
| R1 | Halal allow-listed catalog | AC-REG-001 | All Channels/Playlists/Videos require ≥1 category; attempts to save without fail validation. | `/channels`, `/playlists`, `/videos` | Backend Integration |
| R1 | Halal allow-listed catalog | AC-REG-003 | Category may include optional subcategories; APIs return `subcategories` as an array (empty when none). UI skips subcategory route when empty. | `/categories` | Backend Integration + Admin UI tests |
| R1 |  | AC-REG-002 | Moderation approval logs decision with actor, timestamp. | `/moderation/proposals/{id}/approve` | Backend Integration + Audit tests |
| R2 | Frictionless consumption | AC-AND-001 | Home feed renders sections (Channels/Playlists/Videos) each limited to 3 latest per source. | `/home` | Android Paging tests |
| R2 |  | AC-AND-002 | Player disables autoplay on completion; shows replay button. | N/A (client logic) | Android Instrumentation |
| R2 |  | AC-AND-003 | Audio-only toggle switches ExoPlayer to audio stream without restarting playback. | `/videos/{id}` | Player Reliability |
| R3 | Operational governance | AC-ADM-001 | Admin user CRUD enforces RBAC (only ADMIN can create). | `/admin/users` | Backend Security tests |
| R3 |  | AC-ADM-002 | Audit list paginates via cursor; next cursor null when end reached. | `/admin/audit` | Admin E2E |
| R4 | Localization quality | AC-I18N-001 | API responses honor `Accept-Language` (ar, nl) with fallback to `en`. | all localized endpoints | Backend Integration |
| R4 |  | AC-I18N-002 | Android UI mirrors layout in Arabic including bottom nav order. | N/A | Android Localization |
| R5 | Downloads policy | AC-DL-001 | Playlist download button disabled when `downloadPolicy=DISABLED_BY_POLICY`. | `/playlists/{id}` | Android Instrumentation |
| R5 |  | AC-DL-002 | Offline playback blocked until EULA accepted; acceptance recorded. | `/auth/login` (EULA flag) | Android Instrumentation + Backend |
| R6 | Security | AC-SEC-001 | Access token expires 15m; refresh rotates and blacklists prior tokens. | `/auth/refresh` | Security Tests |
| R6 |  | AC-SEC-002 | Rate limit exceeded returns 429 with localized error. | all endpoints | Performance/Security |
| R7 | Performance | AC-PERF-001 | List payloads ≤80KB per page at limit=20. | `/videos`, `/channels`, `/playlists` | Performance Tests |
| R7 |  | AC-PERF-002 | Android cold start <2.5s on mid-range device (Pixel 4a) measured via benchmark. | N/A | Android Performance |

## Android Client Criteria
- **AC-AND-004**: Splash screen visible ≥1.5s while data loads (See [`../ux/ui-spec.md`](../ux/ui-spec.md#splash)).
- **AC-AND-005**: Onboarding supports en/ar/nl with help modal accessible (RTL mirrored).
- **AC-AND-006**: Category filter persists across Home/Channels/Playlists/Videos tabs.
- **AC-AND-007**: Background playback continues with notification + MediaSession actions (play/pause/skip). Failure triggers retry/backoff.

## Admin Console Criteria
- **AC-ADM-003**: Moderation queue supports status filter, default `PENDING` sorted oldest first.
- **AC-ADM-004**: Category editor prevents deleting category assigned to content; suggests reassignment.
- **AC-ADM-005**: Exclusions view shows parent + excluded entity with reason.
- **AC-ADM-006**: Admin search blends Channels, Playlists, and Videos in a single results view with Include/Exclude toggles and bulk actions for each section.
- **AC-ADM-007**: Channel and playlist drawers expose tabbed detail (Videos, Shorts, Live, Playlists, Posts) with per-item Include/Exclude controls and unsaved-change prompts.
- **AC-ADM-008**: Admin onboarding runbook documents environment setup, credential rotation, and communication touchpoints so a new administrator can complete first-day tasks without external guidance.
- **AC-ADM-009**: Locale QA checklist covers en/ar/nl smoke tests (navigation, tables, forms, moderation queue, dashboard) with instructions to capture and log discrepancies before onboarding sign-off.
- **AC-ADM-010**: Admin dashboard displays pending moderation, category totals, and active moderators from `/admin/dashboard`; data newer than 60s renders without warning and stale results display toast per backend `warnings`.
- **AC-ADM-011**: Registry video filters support free-text search plus length, publish window, and sort controls that propagate across tabs and expose accessible labels in en/ar/nl.

## Backend Criteria
- **AC-BE-001**: Cursor parameters validated; invalid cursor returns 400 with `error=CLIENT_ERROR`.
- **AC-BE-002**: `/next-up` returns only allow-listed IDs not excluded by parent.
- **AC-BE-003**: Redis cache invalidated within 5s after moderation approval.
- **AC-BE-004**: Channel/Playlist/Video persistence stores only YouTube IDs plus Albunyaan overrides; runtime metadata comes from NewPipeExtractor clients.
- **AC-BE-005**: Channel and playlist detail responses surface `excludedVideoIds` / `excludedPlaylistIds` matching stored exclusion state.

## Internationalization Criteria
- **AC-I18N-003**: Numeric values use locale digits (Arabic Indic digits for `ar`).
- **AC-I18N-004**: Dates formatted using Gregorian calendar with locale-specific month names; document rationale for not adopting Umm al-Qura in `docs/i18n/strategy.md`.

## Accessibility Criteria
- **AC-A11Y-001**: All interactive elements have TalkBack labels (Android) or `aria-label` (Admin).
- **AC-A11Y-002**: Color contrast meets WCAG AA per design tokens.
- **AC-A11Y-003**: Focus order follows visual hierarchy; tested on Android (TalkBack) and web (keyboard).

## Downloads & Offline Criteria
- **AC-DL-003**: Downloads limited to app-private storage; hitting quota prompts user with localized message referencing policy.
- **AC-DL-004**: Playlist download surfaces aggregated progress with cancel/resume controls.

## Observability Criteria
- **AC-OBS-001**: Each API response includes `traceId` header.
- **AC-OBS-002**: Metrics emitted for `moderation.pending.count` and `downloads.active.count`.

## Change Management
- All acceptance criteria must have corresponding test cases (see [`../testing/test-strategy.md`](../testing/test-strategy.md)).
- During Phase reviews, update this document with status per AC.
