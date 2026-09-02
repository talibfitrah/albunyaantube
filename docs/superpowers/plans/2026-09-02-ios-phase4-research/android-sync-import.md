# Phase 4 research — Android sync engine and YouTube import

Spec §13 bullets 5 and 6. Paths relative to `android/app/src/main/java/com/albunyaan/tube/`.

---

## Part A — `SyncManager` (636 lines, `data/sync/SyncManager.kt`)

### A1. Shape and concurrency

Constructor takes `SyncApi`, `AppDatabase`, three entity DAOs, `SyncStateDao`, `AccountBindingDao`,
`PlaylistVideoLinkDao` (`:31-40`). Own scope `SupervisorJob() + Dispatchers.IO` (`:42`).

**ONE mutex** — `syncMutex` (`:56`) serialises bind, pull **and** push. The comment at `:44-55` is
the whole rationale: two separate mutexes let a pull read the server `updatedAt` while a push wrote
concurrently, so the pull persisted a stale cursor tail. `bind()` takes the same lock so an
account switch mid-flight cannot leak writes tagged with the wrong uid.

`pendingRetry: Job?` is `@Volatile` (`:72`) and cancelled by `unbind()` — a retry scheduled before
sign-out would otherwise fire `pushDirty(staleUid)` signed with the *next* user's token (`:63-71`).
`unbind()` **acquires the mutex first** (`:619-633`) because `@Volatile` gives visibility, not
check-then-set atomicity, and unbind could interleave between `pendingRetry?.cancel()` and the
reassignment inside `pushDirtyLocked`.

### A2. `bind(uid)` — the decision matrix (`:76-132`)

| `account_binding` row | Action |
|---|---|
| absent | upsert `AccountBindingEntity(uid, now, initial_merge_done = false)` then `runMergeLocked(uid)` (`:79-82`) |
| `user_id == uid && initial_merge_done` | `pullAllLocked(uid)` then `pushDirtyLocked(uid)` (`:83-86`) |
| `user_id == uid && !initial_merge_done` | prior merge crashed mid-way → `runMergeLocked(uid)` again (`:87-90`) |
| `user_id != uid` (account switch) | the transaction below, then `runMergeLocked(uid)` (`:91-130`) |

The account-switch transaction (`:113-128`) does, **atomically, in one `db.withTransaction`**:
`tagAnonRowsToUid(b.user_id)` for all three types → `wipeForUid(b.user_id)` for all three →
`playlistLinks.pruneOrphans()` → `syncState.clearForUid(b.user_id)` → `binding.clear()` →
`binding.upsert(new uid, now, false)`. The three-paragraph comment (`:92-112`) records both bugs
this shape fixes: (a) `MIGRATION_7_8` stamped legacy rows with `user_id = ''`, so a wipe that skipped
them let the *next* user's merge re-tag user A's data to user B; (b) doing the tagging outside the
transaction left a crash window that re-merged the old user's data into the new one.

### A3. `runMerge(uid)` — four ordered steps (`:136-147`)

1. `tagAnonRowsToUid(uid)` on subs, playlists, favorites (`:138-140`).
2. `pullAllLocked(uid)` — collisions overwrite local and clear dirty (`:142`).
3. `pushDirtyLocked(uid)` — remaining local-only rows (`:144`).
4. `binding.markMergeDone(uid)` (`:146`).

### A4. `pullAll(uid)` (`:151-365`)

- Cursors: a `MutableMap` of `{"subscriptions","playlists","favorites"} → last_cursor ?: 0L`
  (`:152-156`) **plus** `lastIds` from `syncState.cursorIdFor` (`:162-166`). The compound
  `(cursor_ts, last_doc_id)` pair is persisted in Room v9 so the pair survives process death
  (`:157-161`).
- Request: `GET api/account/sync?subs&playlists&favorites&subs_id&playlists_id&favorites_id`
  (`data/sync/SyncApi.kt:9-20`).
- Transient retry inside the page loop (`:180-201`): up to **3 attempts**, `delay(200 * attempt)`,
  retrying `IOException` and **5xx only**; a persistent 4xx aborts (`:170-179`).
- Per-row application, inside one `db.withTransaction` (`:207-325`):
  - `row.deleted == true` → `dao.applyTombstone(uid, entityId, row.updatedAt)`; the DAO carries a
    **monotonicity guard** (an older tombstone cannot resurrect a newer row).
  - else → look up `getByIdAny(uid, entityId)` (deleted-agnostic) and **skip the server row when the
    local row is `dirty`** (`:232-235`, `:250`, `:265`). The comment (`:208-225`, `:244-249`) records
    the two iterations: the original `OnConflictStrategy.REPLACE` clobbered a locally-tagged anon
    addition with an older server row, and the later `local.updated_at > row.updatedAt` clause was
    vacuous because local writes never bump `updated_at` (that column is **server-stamped on push
    success** via `clearDirty`). `dirty = 1` alone is the conflict signal.
- Cursor advance (`:269-324`): uses the **server's** `(nextCursor, nextCursorId)` pair, never a
  client-side `max(items.updatedAt)` (`:269-274`), and writes `SyncStateEntity(entityType, user_id,
  last_cursor, last_doc_id, last_sync_at)` in the same transaction. `lastIds` is updated **only for
  the types that actually advanced** (`:305-324`) — an exhausted type otherwise sent
  `(cursor = T_old, lastDocId = null)` and the server's strict `>` branch silently dropped a
  same-millisecond row.
- **Stalled-cursor guard** (`:350-363`): `mintedCursor = any type returned a non-null nextCursor`;
  `advanced = cursors != cursorsBefore || lastIds != lastIdsBefore`; loop continues only while
  `mintedCursor && advanced`. When `mintedCursor && !advanced` it logs a WARN and stops. The comment
  (`:326-349`) records the production incident: a stored `updatedAt` with sub-millisecond precision
  the millisecond cursor could not express meant `startAfter()` never passed the row, the loop ran
  unthrottled at ~3 req/s, starved the shared OkHttp client and pinned the app on the splash screen.
  **Do not port the loop without this guard.**
- Known accepted waste (`:326-333`): an exhausted type keeps being re-sent while another still
  pages, costing at most one page of reads per type per cycle.

### A5. `pushDirty(uid)` (`:420-559`)

- Drains in fixed order **subscriptions → playlists → favorites** (`:437`, `:474`, `:505`), each from
  `dao.selectDirty(uid)`.
- Per row: `deleted` → `DELETE api/account/{type}/{id}`; else `PUT api/account/{type}/{id}` with the
  full body (see A6). On PUT success, if the response says `deleted` (**archive echo**, SYNC-ECHO-01)
  the row is tombstoned locally instead of just cleared (`:454-458`, `:486-491`, `:517-522`) — the
  server's projection knows a parent was archived. Otherwise `clearDirty(uid, id, resp.updatedAt)` —
  **that is where `updated_at` gets its value**.
- **Resilient, not all-or-nothing** (`:423-432`): a transient failure records a flag and the drain
  keeps going; the failing row stays dirty. Auth failures short-circuit.
- `push()` classifier (`:575-617`):

  | Response | Outcome |
  |---|---|
  | 2xx with body | `onSuccess(body)` → **OK** |
  | 2xx with **null body** | **TRANSIENT_FAILURE** + WARN (`:588-605`) — the R-final7 P0 fix: returning OK meant `clearDirty` never ran and the row re-pushed forever |
  | 404 | `on404()` → **OK** (idempotent DELETE) |
  | 401 / 403 | **AUTH_FAILED** — breaks the drain (`:609`) |
  | 400 / 409 / 422 | **PERMANENT_FAILURE** → `clearDirty(...)` with a local WARN so a malformed row cannot block pulls forever (`:610-614`, `:465-469`) |
  | 5xx / 429 / network / anything else | **TRANSIENT_FAILURE** (`:615`) |

- Backoff (`:543-558`): clean drain or auth failure → `pushBackoff.reset()`; transient failure →
  `pushBackoff.next()` and one queued retry Task (previous cancelled first). `SyncBackoff`
  (`data/sync/SyncBackoff.kt:18-35`): base 1 s doubling to a 60 s cap, then **equal jitter** —
  `wait ∈ [base/2, base]` (`:29-31`), added because a fleet-wide outage produced synchronised
  reconnections.

### A6. Wire shapes (`data/sync/dto/SyncDtos.kt`)

```
SyncResponseDto { subscriptions: SyncPageDto<SubscriptionSyncDto>, playlists: …, favorites: … }   :6-10
SyncPageDto<T>  { items: [T], nextCursor: Long?, nextCursorId: String? }                          :13-18
SubscriptionSyncDto { entityId, deleted: Bool, updatedAt: Long,
                      channelUrl, name, avatarUrl?, subscribedAt: Long,
                      approvalStatus?, source?, importedAt? }                                     :21-33
PlaylistSyncDto     { entityId, deleted, updatedAt,
                      playlistUrl, name, thumbnailUrl?, uploaderName?, savedAt: Long,
                      approvalStatus?, source?, importedAt? }                                     :36-49
FavoriteSyncDto     { entityId, deleted, updatedAt,
                      title, channelName, thumbnailUrl?, durationSeconds: Int, addedAt: Long,
                      approvalStatus?, source?, importedAt? }                                     :52-65
PutSubscriptionRequest { channelUrl, name, avatarUrl?, subscribedAt, approvalStatus?, source?, importedAt? }  :70-79
PutPlaylistRequest     { playlistUrl, name, thumbnailUrl?, uploaderName?, savedAt, … }            :82-92
PutFavoriteRequest     { title, channelName, thumbnailUrl?, durationSeconds, addedAt, … }         :95-105
```

`rowToSub` / `rowToPlaylist` / `rowToFavorite` (`SyncManager.kt:367-418`) default a null server
`approvalStatus` to `"APPROVED"` (`:379`, `:397`, `:415`).

**`deleted` is the wire name on every DTO.** iOS's SwiftData property is `isRemoved` because
`deleted` collides with Core Data's KVC `isDeleted` and is silently reverted on save
(`FavoriteVideo.swift:12-18`, a controlled A/B test). Spec §13 already records this; the sync
codec must map `isRemoved ↔ "deleted"` explicitly.

### A7. Room v11 columns the SwiftData models must mirror

`data/local/AppDatabase.kt:35-49` — version **11**, nine entities. The three synced tables:

| Room | Columns |
|---|---|
| `subscribed_channels` (`SubscribedChannel.kt:7-23`) | `channelId` (PK), `channelUrl`, `name`, `avatarUrl?`, `subscribedAt`, `user_id`, `updated_at`, `deleted`, `dirty`, `approval_status` (default `"APPROVED"`), `source?`, `imported_at?` |
| `saved_playlists` (`SavedPlaylist.kt:7-24`) | `playlistId` (PK), `playlistUrl`, `name`, `thumbnailUrl?`, `uploaderName?`, `savedAt`, `user_id`, `updated_at`, `deleted`, `dirty`, `approval_status`, `source?`, `imported_at?` |
| `favorite_videos` (`FavoriteVideo.kt:20-38`) | `videoId` (PK), `title`, `channelName`, `thumbnailUrl?`, `durationSeconds`, `addedAt`, `user_id`, `updated_at`, `deleted`, `dirty`, `approval_status`, `source?`, `imported_at?` |

Sync bookkeeping:
- `sync_state` (`SyncStateEntity.kt:9-20`), composite PK `["entityType", "user_id"]`:
  `entityType`, `user_id`, `last_cursor: Long`, `last_doc_id: String?`, `last_sync_at: Long`.
- `account_binding` (`AccountBindingEntity.kt:10-15`), single row: `user_id` (PK), `bound_at`,
  `initial_merge_done`.

Not synced but Me-tab-relevant: `channel_feed_refresh_state`
(`ChannelFeedRefreshState.kt:38-51`, see `android-accounts.md` §5.3), `playlist_video_link`
(`PlaylistVideoLink.kt:17-23`, composite PK `["playlistId","videoId"]`), `channel_video_cache`.
`followed_channels` is dead (phase-2 inventory §6.2, ruling 23 — omit).

**Gap in the shipped iOS schema** (`ios-seams.md` §5): `SubscribedChannel.swift` and
`SavedPlaylist.swift` carry **no** `approvalStatus`/`source`/`importedAt`, no `channelUrl`/
`playlistUrl`/`uploaderName`, and `SavedPlaylist` has an `itemCount` the wire does not. Only
`FavoriteVideo` is complete. Phase 4 needs `FavoritesSchemaV5` + a lightweight migration.

### A8. Triggers

| Trigger | Site |
|---|---|
| sign-in success → `bind(uid)` | `ui/SplashFragment.kt:129-141` (`launch { syncManager.bind(loaded.uid) }`, off the splash critical path) |
| foreground (`ProcessLifecycleOwner ON_RESUME`) → `pullAll` + `pushDirty` | `AlBunyaanApplication.kt:166-185`; waits for `accountState` to leave `Loading` under a **10 s** `withTimeoutOrNull` (`:178-180`) and skips on timeout (`:167-177` — unbounded waiters were accumulating one per foreground) |
| repo write → `pushDirtyAsync(uid)` | `SubscriptionLimitGuard.kt:68`, `SubscriptionRepository.kt:135,153,175,189`, `FavoritesRepository` |
| connectivity restored → `pushDirtyAsync(uid)` | `AlBunyaanApplication.kt:205-222`, `registerDefaultNetworkCallback`, wrapped in try/catch for OEM `SecurityException` (`:214-220`) |
| sign-out (`AccountStatusEvent.SignedOut`) → `unbind()` | `di/SyncModule.kt:42-56` |

`SubscriptionRepository` uses `accountRepository.accountState.flatMapLatest { channels.observeAll(uidOf(state)) }`
(`:46-50`) so every flow **re-scopes on sign-in/sign-out** — evaluating `currentUid()` once at flow
construction left the UI showing the previous user's rows (`:33-45`). The iOS equivalent is
`SwiftDataFavoritesStore.currentUserId`'s `didSet { refresh() }`
(`FavoritesStore.swift:21-26`), which exists but is **not on the protocol** — see `ios-seams.md` §5.

`unsubscribe` (`SubscriptionRepository.kt:146-154`) and `unsavePlaylist` (`:178-190`) are
transactional: soft-delete + purge the channel's cached videos + delete its refresh-state row / the
playlist's link rows, then push.

---

## Part B — YouTube import

### B1. Authorization (`data/youtube/YouTubeAuthManager.kt`, 150 lines)

- Scope constant: `SCOPE_YOUTUBE_READONLY = "https://www.googleapis.com/auth/youtube.readonly"`
  (`:17`).
- `AuthResult { Granted(accessToken) | NeedsConsent(pendingIntent) | Denied | Failed(error) }`
  (`:33-38`).
- Interface `YouTubeAuthManager { authorize(); authorizeFromConsentResult(data) }` (`:54-71`) exists
  **precisely so the repository/ViewModel layer tests against fakes without the Google SDK**
  (`:44-52`) — the seam Phase 4's "tests against fakes" gate needs, already proven on Android.
- Production impl wraps GIS `Identity.getAuthorizationClient(context)` + `AuthorizationRequest`
  (`:92-100`); incremental — **the scope is requested only when the user starts an import, never at
  sign-in** (`:45-46`). `CancellationException` is rethrown in both entry points (`:108-109`,
  `:121-122`).

iOS equivalent per spec §13: `GIDSignIn.sharedInstance.addScopes(["…/youtube.readonly"],
presenting:)` on the current user, then `user.accessToken.tokenString`. Different SDK, same seam:
one protocol, one fake.

**No revoke affordance exists on Android** — repo-wide grep for `revoke|clearToken` over
`data/youtube/` and `ui/me/importflow/` returns only a doc-comment mention
(`YouTubeAuthManager.kt:30`). Spec §13's "revocable from the Import screen" is iOS-new
(`contradictions-and-forks.md` §C6).

### B2. Fetch (`data/youtube/YouTubeImportRemoteSource.kt`, 154 lines)

`fetchAll(accessToken)` (`:36-66`) runs **three independent paginators** and isolates failures
per type into `ImportFetchResult.failedTypes` (`:42-65`) — one 403 must not suppress the other two.

`data/youtube/YouTubeImportApi.kt` — YouTube Data API v3, base URL supplied by the Retrofit instance
(`:13-15`), **token per-call as `Authorization: Bearer …`, no global interceptor** (`:16-18`):

| Op | Request |
|---|---|
| `subscriptions` (`:26-33`) | `GET subscriptions?part=snippet&mine=true&maxResults=50&pageToken=` |
| `playlists` (`:39-46`) | `GET playlists?part=snippet&mine=true&maxResults=50&pageToken=` |
| `likedVideos` (`:52-59`) | `GET videos?part=snippet&myRating=like&maxResults=50&pageToken=` |

**No API key anywhere** — the OAuth access token is the sole credential. This closes the open
question in the brief.

Each paginator (`:70-97`, `:99-125`, `:127-153`) loops `while pageToken != null && pages < MAX_PAGES
&& seenTokens.add(pageToken)` — `MAX_PAGES = 40` (`:25`, so ≤ 2 000 items/type) plus a
**seen-token set** guarding a server that repeats a token. Truncation at the cap is logged, not
surfaced (`:93-95`; `ImportCandidate.kt:36-38` records that surfacing it is a deferred UI item).

Mapping: subscriptions → `CandidateType.CHANNEL` with `snippet.resourceId.channelId` (`:79-85`);
playlists → `PLAYLIST` with `item.id` (`:108-114`); liked videos → `VIDEO` with `item.id` and
`snippet.channelId` (`:135-142`). `ImportCandidate(type, youtubeId, title, thumbnailUrl?, channelId?)`
(`data/youtube/ImportCandidate.kt:17-23`).

### B3. Resolve + write (`data/importflow/YouTubeImportRepository.kt`, 298 lines)

`import(selected)` (`:66-164`):
1. **Dedupe against Room** (`:70-82`) — `existsInRoom` uses the deleted-agnostic, status-agnostic
   `getByIdAny` per type (`:172-178`, backed by `SubscriptionRepository.channelExistsAny`/
   `playlistExistsAny` at `:161-169` and `FavoritesRepository.favoriteExistsAny`). Hits count as
   `alreadyPresent` and are **never sent to the backend**.
2. Chunk the rest at **`BATCH_SIZE = 200`** (`:49`, `:93`) and `POST /api/account/import/resolve`
   (`data/importflow/ImportApi.kt:26-27`).
3. **429 stops the loop** (`:113-118`): `rateLimited = true; break`. Chunks already written persist
   and dedupe on retry (`:110-112`).
4. Per result (`:125-149`):
   - `"APPROVED"` → `writeApproved` with canonical `result.content` metadata preferred over the
     candidate's (`:187-242`), `approvalStatus = "APPROVED"`, `source = SOURCE_USER_IMPORT`
     (`= "USER_IMPORT"`, `:52`), `importedAt = now`; `added++`.
   - `"PENDING"` → `writePending` with candidate metadata, `approvalStatus = "AWAITING"`
     (`:248-297`); `sentForReview++` **only when a row was actually written** (`:133-140`).
   - anything else (`"REJECTED"`, `"ERROR"`, unknown) → not written, `rejectedOrError++` (`:142-145`).
5. `ImportProgress(phase ∈ {RESOLVING, WRITING, DONE}, processed, total)`
   (`data/importflow/ImportProgress.kt:13-19`); the final emission uses the **actual** processed
   count, not `total`, so a 429-truncated run is not painted as complete (`:152-154`).
6. `ImportSummary(added, sentForReview, skipped = alreadyPresent + rejectedOrError, alreadyPresent,
   rateLimited)` (`data/importflow/ImportSummary.kt:16-28`).

Canonical URLs written locally: `https://www.youtube.com/channel/<id>` (`:198`),
`https://www.youtube.com/playlist?list=<id>` (`:214`). For a PENDING video, `channelName` is
deliberately `""` — never the `UC…` id (`:233`, `:288`).

Wire DTOs (`data/importflow/dto/ImportDtos.kt`):
`ImportItemDto { type ∈ {CHANNEL, PLAYLIST, VIDEO}, youtubeId, title, thumbnailUrl?, channelId? }`
(`:14-22`); `ImportResolveRequestDto { items }` (`:26-28`);
`ImportResultDto { youtubeId, type, disposition ∈ {APPROVED, PENDING, REJECTED, ERROR}, content:
ContentItemDto? }` (`:38-46`, content non-null **only** for APPROVED);
`ImportResolveResponseDto { results }` (`:50-52`).

### B4. UI state machine (`ui/me/importflow/ImportUiState.kt`, `ImportViewModel.kt`)

`ImportUiState` (`:28-85`): `Idle → Authorizing → [NeedsConsent →] Fetching → Review → Importing →
Done`, `Error(message, retryable)` from anywhere (`:14-26`).
- `Review(candidates, selected: Set<String>, partialFailureTypes: Set<CandidateType>)` (`:56-65`);
  **`selected` starts as every `youtubeId`** (`ImportViewModel.kt:209-213`).
- **Empty-candidates policy** (`:22-26`, `ImportViewModel.kt:199-207`): zero candidates → `Error("No
  items found", retryable = failedTypes.isNotEmpty())`, never an empty Review.
- `toggleSelection` (`:92-100`) and `setGroupSelected(type, selected)` (`:106-115`) — per-item and
  per-group checkboxes.
- `confirmImport` (`:121-163`): double-tap guard on `importJob?.isActive` (`:126`); progress
  collected into `Importing` only while still in that state (`:132-139`); the first emission is a
  **fresh zero** progress, not `repository.progress.value`, so a re-import does not flash the
  previous run's DONE frame (`:141-144`); `CancellationException` rethrown (`:154-156`).
- `onConsentLaunched()` (`:82-86`) leaves the sticky `NeedsConsent` state so a configuration change
  cannot relaunch a second consent prompt. The iOS analogue is a presentation latch on the
  `GIDSignIn.addScopes` sheet.
- `retry()` = `start()` (`:169-171`).

### B5. The Sharī'ah caution gate

`ui/me/importflow/ImportFromYouTubeFragment.kt:199-217` — before `viewModel.confirmImport()` runs, a
`MaterialAlertDialog` with `import_caution_title` / `import_caution_message` /
`import_caution_continue`. The message (`res/values/strings.xml:893`) is substantive religious copy;
it is already in the iOS catalog (`import_caution_*`, 3 keys) and **must be shown before the import
begins**, not skipped as chrome.

`ImportFromYouTubeFragment.kt:54-86` also auto-starts the flow once on first view
(`viewModel.start()` at `:86`, guarded by a `started` flag at `:54`).

### B6. Backend contract for import (see `backend-and-phase5.md` §4)

`POST /api/account/import/resolve` — items capped at **200** by
`ImportResolveRequest` (`@Size(max = 200)`, `backend/.../dto/importflow/ImportResolveRequest.java:8-9`),
which is exactly `BATCH_SIZE`. Per-user daily budget **1 000 items** in a 24 h sliding window
(`SubmissionRateLimiter.IMPORT_DAILY_ITEM_BUDGET = 1000`, `WINDOW = 24 h`, `:31-32`), consumed
all-or-nothing per request (`tryAcquireImport`, `:124-152`), exceeding → 429 with `retryAfterSec`.
