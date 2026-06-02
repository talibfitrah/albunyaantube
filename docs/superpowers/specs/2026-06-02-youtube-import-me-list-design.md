# Import from YouTube → Me list — Design Spec

> **Date**: 2026-06-02
> **Branch**: `feature/youtube-import-me-list`
> **Status**: Approved design, pre-implementation
> **Author**: brainstormed with Farouq

---

## 1. Summary

Let a signed-in FitrahTube user import their **YouTube subscriptions**, **created playlists**, and **liked videos** into their Me list. Items we have **already approved** in the content registry drop straight into the Me list (and the curated feed). Items we don't recognize are **submitted to the admin approval queue** and parked in a clearly-labeled **"Awaiting review"** section of the Me tab; on admin approval they graduate into the real feed (on rejection they silently disappear).

The import is offered **once on first login** and is always available via a new **"Import from YouTube"** item in the Me-tab kebab (visible to all signed-in users).

## 2. Goals

- One-tap (with review/deselect) import of subscriptions, created playlists, and liked videos.
- Preserve FitrahTube's halal-curation guarantee: **no un-vetted video ever enters the curated feed**.
- Reuse the existing, shipped account sync engine (Plan D) so awaiting-review and graduation are cross-device and automatic.
- Never store a user's Google/YouTube OAuth token on our servers.
- Don't flood the admin queue with duplicates across users.

## 3. Non-goals (explicit YAGNI)

- **No backend OAuth-token storage** and no server-side YouTube Data API calls.
- **No watch-history import** — the Data API does not expose it.
- **No "playlists saved from others"** — `playlists.list?mine=true` returns only playlists the user *created*; saved playlists aren't exposed by the API.
- **No Android bulk-paste UI** — existing product decision; bulk paste stays web-admin-only.
- **No demand analytics dashboard** ("N users want this") in v1 — the data is derivable later from the fan-out query if wanted.
- No new account-sync subsystem — we extend the existing one additively.

## 4. Locked decisions

| # | Decision | Rationale |
|---|----------|-----------|
| 1 | **OAuth + YouTube Data API v3** (`youtube.readonly`) | Only sanctioned way to read a user's private subs/playlists/likes. NewPipe (public scraping) and the API-key path physically cannot. |
| 2 | Unknown imports → **"Awaiting review" section**, graduate on approval | Keeps the curated feed 100% vetted while still capturing the user's intent. |
| 3 | **Review & select** (checkboxes, all on by default) before import | Avoids dumping stale subs into the list and the queue; fits the curated feel. |
| 4 | **Client-fetch, thin backend** | No OAuth token ever reaches our servers; smallest sensible backend. |
| 5 | Graduation rides the **existing Plan D sync engine** (not a new poll) | The Me list is already backend-synced incl. favorites; reusing it is less code and more consistent. |

### Baked-in assumptions (confirmed with user)

- **"Favorite videos" = YouTube Liked videos** (`videos.list?myRating=like`).
- **"Playlists" = playlists the user created** (`playlists.list?mine=true`).
- **Rejected items are skipped silently** on import — not re-added, not re-submitted.
- **Import is re-runnable / idempotent** — already-imported items are skipped; new items are picked up.
- The first-login offer appears for **any** sign-in method; the YouTube authorization is a **separate, incremental** consent triggered only when the user actually imports.

## 5. Relevant current architecture (ground-truthed)

- **Auth**: Firebase Auth (email/password + Google via Firebase `GoogleAuthProvider`). `play-services-auth:21.5.1` is present, but **no OAuth scopes** and no Data API token flow today.
- **Me list = three Room tables** — `subscribed_channels`, `saved_playlists`, `favorite_videos` — each with sync columns `user_id`, `updated_at`, `deleted`, `dirty`.
- **Account sync engine (Plan D, shipped & wired)** — `SyncManager` (`android/.../data/sync/SyncManager.kt`): `bind/pullAll/pushDirty/runMerge/unbind`, triggered on sign-in, app resume (`ProcessLifecycleOwner`), and reconnect. Backend `SyncController` at base path **`/api/account`**:
  - `GET /api/account/sync` → `SyncResponseDto { subscriptions, playlists, favorites : SyncPageDto<T> }` (cursor `= (updatedAt, docId)`).
  - `PUT/DELETE /api/account/{subscriptions|playlists|favorites}/{id}` → per-type sync DTOs.
  - Firestore: `users/{uid}/{subscriptions,playlists,favorites}`. Last-write-wins via `updatedAt`; tombstones via `deleted`; local `dirty=1` rows win over server on pull.
- **Content registry** — Firestore collections `channels`, `playlists`, `videos`. Each doc: internal `@DocumentId id` + canonical `youtubeId` + `status ∈ {PENDING, APPROVED, REJECTED, REQUEST_CHANGES}` + `categoryIds` + `submittedBy` + `approvalMetadata` + `thumbnailUrl`. Lookup: `findByYoutubeId(youtubeId)` (indexed) on each repository.
- **Submission today** — `POST /api/admin/registry/{channels|playlists|videos}` is `@PreAuthorize("hasAnyRole('ADMIN','MODERATOR')")`, **requires categories**, and returns **409 on any existing youtubeId**. The Android "Suggest content" flow uses this and is gated to moderators. **Regular users cannot submit today** — this feature adds a user-facing path.
- **Approval** — `ApprovalController` `/api/admin/approvals`: `GET /pending`, `POST /{id}/approve` (optional `categoryOverride`), `/reject`, `/request-changes`. Approve flips status → APPROVED and evicts public caches.
- **Me feed** — `MeFeedRepository` builds the feed by NewPipe-deep-paging the channels in `subscribed_channels` and the playlists in `saved_playlists`. Today every row is implicitly approved (you can only subscribe to catalog content). **This feature is the first path that can introduce un-approved rows**, hence the `approvalStatus` gate.

## 6. Architecture overview

```
┌─────────────────────────── Android ───────────────────────────┐
│ ImportFromYouTubeFragment / ImportViewModel                    │
│   1. YouTubeAuthManager  → youtube.readonly access token       │
│   2. YouTubeImportApi    → fetch subs / playlists / liked      │
│   3. Review & select (checkboxes)                              │
│   4. YouTubeImportRepository.import(selected)                  │
│        a. skip rows already present in local Room              │
│        b. POST /api/account/import/resolve (chunked)           │
│        c. write Me-list rows: APPROVED → feed, AWAITING → review│
│           (existing repos mark dirty → SyncManager pushes)     │
│        d. show summary                                          │
└────────────────────────────────────────────────────────────────┘
                     │  resolve (IDs + client metadata)
                     ▼
┌─────────────────────────── Backend ───────────────────────────┐
│ ImportController  POST /api/account/import/resolve             │
│   per item: findByYoutubeId →                                  │
│     APPROVED  → return APPROVED (+ curated ContentItemDto)     │
│     PENDING   → return PENDING                                 │
│     REJECTED  → return REJECTED (client skips)                 │
│     UNKNOWN   → UserImportSubmissionService.submit()           │
│                 (create PENDING, source=USER_IMPORT,           │
│                  categoryIds=[], client metadata, deduped)     │
│                 → return PENDING                               │
│                                                                │
│ ApprovalService.approve()/reject()  ── fan-out ──▶             │
│   collectionGroup({subscriptions|playlists|favorites})         │
│     .whereEqualTo(youtubeId).whereEqualTo(approvalStatus,      │
│      AWAITING)  → flip to APPROVED (or tombstone on reject),   │
│      bump updatedAt                                            │
└────────────────────────────────────────────────────────────────┘
                     │  next GET /api/account/sync delta pull
                     ▼
            Row graduates into the feed on every device
```

## 7. Component detail

### 7.1 Android — new

| Component | Responsibility | Key interface |
|---|---|---|
| `YouTubeAuthManager` | Incremental authorization for `youtube.readonly` via Google Identity Services `AuthorizationClient`. Returns an OAuth access token; handles "needs consent" → `PendingIntent`. **Not** tied to Firebase sign-in. | `suspend fun authorize(): AuthResult` (`Granted(token)` / `NeedsConsent(intent)` / `Denied`) |
| `YouTubeImportApi` | Retrofit interface against `https://www.googleapis.com/youtube/v3/`. `subscriptions?part=snippet&mine=true&maxResults=50&pageToken=`, `playlists?part=snippet&mine=true&...`, `videos?part=snippet&myRating=like&...`. Bearer token via per-call header. | returns page DTOs with `nextPageToken` |
| `YouTubeImportRemoteSource` | Paginates each list to completion; maps to unified `ImportCandidate(type, youtubeId, title, thumbnailUrl, channelId?)`. Handles per-type partial failure. | `suspend fun fetchAll(token): ImportFetchResult` |
| `YouTubeImportRepository` | Orchestrates: dedup vs local Room → chunked `resolve` → write Me-list rows with `approvalStatus` via existing `SubscriptionRepository`/`SavedPlaylistRepository`/`FavoritesRepository` → emit progress + summary. | `suspend fun import(selected): ImportSummary`, `val progress: Flow<ImportProgress>` |
| `ImportFromYouTubeFragment` + `ImportViewModel` | UI state machine: `Idle → Authorizing → Fetching → Review → Importing → Summary/Error`. Review screen: 3 grouped, collapsible sections (Channels / Playlists / Liked videos), per-item checkboxes (all checked), per-group select-all + counts. Summary: "X added · Y sent for review · Z skipped". | MVVM + Hilt; layouts for `layout/`, `layout-sw600dp/`, `layout-sw720dp/`, RTL. |

### 7.2 Android — changed

- **`menu_me_kebab.xml`**: add `action_import_youtube` (project-local vector icon, **no vector-level tint**), `showAsAction="never"`. Visible to **all** signed-in users (no role gate, unlike `action_suggest_content`). Wire navigation in `MeFragment.setupKebab()`.
- **First-login hook**: after the first successful sign-in, route once to an offer (reuse/extend the onboarding stub or a lightweight dialog). Persist an `import_offer_shown` flag in DataStore. "Skip" → dismiss; always reachable later via kebab.
- **Room migration** (`subscribed_channels`, `saved_playlists`, `favorite_videos`): add
  - `approval_status TEXT NOT NULL DEFAULT 'APPROVED'` (`APPROVED` | `AWAITING`),
  - `source TEXT` (nullable; `'YOUTUBE_IMPORT'` for imported rows),
  - `imported_at INTEGER` (nullable).
  Default `APPROVED` so all pre-existing rows keep current behavior. Migration test required.
- **DAO / queries**: Me-feed source queries gain `WHERE approval_status = 'APPROVED'`. New queries for the awaiting section: `WHERE approval_status = 'AWAITING'` per type.
- **`MeFeedRepository`**: feed composition — channels, playlists, **and the favorites row** — uses only `APPROVED` rows. An `AWAITING` favorite (a liked video whose video isn't approved yet) does **not** appear in the favorites row; it shows in the awaiting section instead. No behavior change for existing rows.
- **Me tab awaiting section**: new section/adapter rendered when any `AWAITING` rows exist — grouped by type (channels / playlists / videos), clearly labeled ("Imported — awaiting review"), **no playable video surfaces** from awaiting channels/playlists/videos, shows a count. Items disappear (graduate to feed / tombstone) via normal sync pull.
- **Sync DTO mapping** (`SyncManager` + sync DTOs): carry `approvalStatus`, `source`, `importedAt` on subscriptions/playlists/favorites push & pull mapping (additive).

### 7.3 Backend — new

- **`ImportController`** `@RequestMapping("/api/account/import")`, authenticated (any signed-in `USER`+; **not** admin-gated):
  - `POST /resolve` — body `ImportResolveRequest { items: List<ImportItem> }` where `ImportItem { type: CHANNEL|PLAYLIST|VIDEO, youtubeId, title, thumbnailUrl?, channelId? }` (server caps `items.size`, e.g. ≤200/request; client chunks). Response `ImportResolveResponse { results: List<ImportResult> }` where `ImportResult { youtubeId, type, disposition: APPROVED|PENDING|REJECTED|ERROR, content: ContentItemDto? }`.
    - Per item: `findByYoutubeId` →
      - `APPROVED` → `APPROVED` + curated `ContentItemDto` (so the client stores canonical metadata).
      - `PENDING` / `REQUEST_CHANGES` → `PENDING`.
      - `REJECTED` → `REJECTED`.
      - not found → `UserImportSubmissionService.submit(...)` then `PENDING`.
    - Per-item failures isolated → `ERROR` (client skips, surfaced in summary).
- **`UserImportSubmissionService`** (reuses `RegistrySubmissionWriter` sanitizers): creates `Channel`/`Playlist`/`Video` with `status=PENDING`, `submittedBy=uid`, **`categoryIds=[]`**, `source="USER_IMPORT"`, sanitized client-supplied `name`/`thumbnailUrl` (**no NewPipe call at submit** → avoids the circuit breaker; lazy enrichment can happen at approval). **Dedup**: if `findByYoutubeId` returns anything, do not create — return its status. Concurrency: tolerate races (a unique-ish create or catch-and-reread on duplicate).

### 7.4 Backend — changed

- **Content models** (`Channel`/`Playlist`/`Video`): add `source` (nullable; `"USER_IMPORT"` | `"ADMIN"` | `"MODERATOR"` | `"BULK"`). Backfill-safe (nullable). **Note**: this is a *different* field from the Me-list row's `source` (§7.2, value `'YOUTUBE_IMPORT'`) — the registry `source` records who submitted the **global** content; the Me-list `source` records how a row entered **this user's** list. They live on different entities and intentionally use different vocabularies.
- **`ApprovalController` / `ApprovalService`**:
  - **Require category assignment** when approving an item whose `categoryIds` is empty (i.e. `categoryOverride` becomes mandatory for `USER_IMPORT` items). Reject the approve with a clear 400 otherwise.
  - **Graduation fan-out** on `approve` and `reject`: `collectionGroup("subscriptions"|"playlists"|"favorites").whereEqualTo("youtubeId", id).whereEqualTo("approvalStatus","AWAITING")`:
    - on **approve** → set `approvalStatus="APPROVED"`, bump `updatedAt` (sync delivers it).
    - on **reject** → set `deleted=true` (+bump `updatedAt`) so the awaiting row tombstones out on next pull.
    - Batched writes; runs after the registry status flip + cache eviction. Failure here is logged and retryable, never blocks the approve.
- **`SyncController` DTOs** (`SubscriptionSyncDto`, `PlaylistSyncDto`, `FavoriteSyncDto` + `Put*Request`): carry `approvalStatus`, `source`, `importedAt`. Default `approvalStatus="APPROVED"` when absent (back-compat for existing clients).
- **Firestore indexes**: collection-group index on `(youtubeId, approvalStatus)` for `subscriptions`, `playlists`, `favorites`.
- **Security config**: `/api/account/import/**` requires authentication (any role), consistent with `/api/account/**`.
- **Rate limiting**: per-user cap appropriate for bulk import (e.g. items/request ≤200, and a daily import-items budget). On exceed → 429 with `accepted`/`remaining`; client stores accepted, prompts retry later.

### 7.5 Frontend (admin)

- **`PendingApprovalsView`**: show a **"User import"** source badge for `source=USER_IMPORT`; **require category selection** before the approve action is enabled for empty-category items; optional filter by source. New i18n strings (en, ar, nl).

## 8. Data flow — happy path

1. User taps **Import from YouTube** (first-login offer or kebab).
2. `YouTubeAuthManager.authorize()` → consent → access token.
3. `YouTubeImportRemoteSource.fetchAll()` pages subscriptions + created playlists + liked videos → unified candidates.
4. Review screen (all checked) → user prunes → **Import**.
5. Repository drops candidates already present in local Room, chunks the rest, `POST /api/account/import/resolve`.
6. Backend dispositions each (approved / pending / rejected / submit-unknown→pending / error).
7. Repository writes Me-list rows via existing repositories:
   - `APPROVED` → row with `approval_status='APPROVED'`, canonical metadata (joins the feed).
   - `PENDING` → row with `approval_status='AWAITING'`, client metadata (awaiting section).
   - `REJECTED`/`ERROR` → not stored.
   Each write marks `dirty`; `SyncManager.pushDirtyAsync()` propagates to `users/{uid}/...`.
8. Summary shown: **"X added · Y sent for review · Z skipped"**.
9. Later, an admin approves a pending registry item → `ApprovalService` fan-out flips matching `AWAITING` rows → next `GET /api/account/sync` pull graduates them into the feed **on every device**. Reject → tombstone → row disappears from awaiting.

## 9. Error handling

| Case | Handling |
|---|---|
| User denies YouTube consent | Clean abort; no partial state; available again later. |
| Access token expired / 401 from Data API | Re-authorize (silent if possible) and retry once; else surface error with retry. |
| One list type fails (e.g. liked videos 403) | Import the types that succeeded; note the failed type in the summary. |
| Quota / 5xx from Data API | Backoff + retry; on persistent failure, fail that fetch with retry CTA. |
| Per-item backend `ERROR` in resolve | Item skipped (not parked as AWAITING); counted in summary "skipped". |
| Re-run / duplicates | Client skips rows already in Room; backend dedup is the second guard; already-approved-since items graduate. |
| Rate limit (429) | Client stores `accepted`, tells user to retry the remainder later. |
| Fan-out failure on approve | Logged + retryable; never blocks the admin approve. Worst case the user re-runs import (idempotent) and the now-APPROVED item lands directly. |
| Approve of empty-category item without category | 400 with clear message; admin must pick categories. |

## 10. Security & privacy

- `youtube.readonly` token lives only on-device; **never** sent to our backend.
- Import endpoints authenticated as the calling user; submissions are attributed (`submittedBy=uid`).
- Server sanitizes all client-supplied metadata (thumbnail CDN allowlist, title/name sanitation) — never trust client strings.
- Per-user `users/{uid}/...` collections remain private to that user (existing Plan D security rules).

## 11. Testing strategy

**Backend (JUnit; integration via Firebase emulator, `BaseIntegrationTest` clears Caffeine caches):**
- `resolve` dispositions: approved / pending / rejected / unknown→submitted / per-item error isolation.
- Dedup: existing youtubeId in each status → no duplicate PENDING created; correct disposition returned; concurrent submit race.
- `UserImportSubmissionService`: empty-category PENDING with `source=USER_IMPORT`, sanitization, no NewPipe call.
- Approval: empty-category approve **requires** `categoryOverride` (400 otherwise).
- Fan-out: approve flips matching `AWAITING` rows → APPROVED + `updatedAt` bump; reject tombstones; no effect on `APPROVED`/other users' non-matching rows; collection-group query correctness.
- Sync DTO back-compat: missing `approvalStatus` defaults to APPROVED.
- Security: `USER` allowed on `/api/account/import/**`; anonymous rejected. Rate-limit 429 shape.

**Android (JUnit/Robolectric; MockWebServer):**
- `ImportViewModel` state machine incl. all error transitions.
- Data API paging + mapping (subs / playlists / liked), `nextPageToken` exhaustion, per-type partial failure.
- `YouTubeImportRepository`: dispositions → correct Room writes (`approval_status`), dedup-skip, dirty-marking.
- Room migration (new columns; defaults) + DAO queries (feed excludes AWAITING; awaiting queries include only AWAITING).
- Graduation: simulated sync pull flips AWAITING→APPROVED → row enters feed; tombstone removes from awaiting.
- UI across `layout/` + `sw600dp` + `sw720dp` + RTL (Arabic): review checkboxes, awaiting section. Tablet/TV pagination rule respected for any new lists.

**Frontend (Vitest):**
- `PendingApprovalsView` renders the USER_IMPORT badge and requires category before approve.

## 12. Release gate (not a code task)

Develop and beta-test under the Google OAuth consent screen in **Testing** mode (≤100 test users → no verification needed for the sensitive `youtube.readonly` scope). **Before public GA**, complete **Google OAuth app verification**: privacy-policy URL, app homepage/domain, scope justification, and (typically) a demo video. Sensitive scope → verification required, but **not** the restricted-scope independent security assessment. Track as a release-blocking task, separate from code.

## 13. i18n

New user-facing strings on Android (`strings.xml`, with ar/nl + RTL) for the import flow, awaiting section, summary, errors; and on the admin frontend (`messages.ts` en/ar/nl) for the source badge / category-required prompt.

## 14. Open items (non-blocking)

- Exact first-login surface (full screen vs dialog) — finalize in implementation against the onboarding stub.
- Icon asset for the kebab item — project-local vector, no vector-level tint.
- Per-request/day rate-limit constants — pick concrete numbers during backend implementation.
