# Plan D — Account Sync Engine — Design Spec

**Status:** Draft, awaiting user review.
**Date:** 2026-05-12
**Ticket:** SYNC-01
**Position in series:** Plan D of six. Depends on Plan A (backend account foundation), Plan B (Android Firebase Auth), Plan C (account bootstrap + age gate), all merged to `develop`.

---

## 1. Goal

Sync three Me-tab entity types (`subscribed_channels`, `saved_playlists`, `favorite_videos`) bidirectionally between Android clients and each user's Firestore account. Make per-user data appear consistent across all of a user's devices, prune content the curators have archived, and absorb pre-Plan-D local data on first sign-in without loss.

---

## 2. Why this is a separate plan

Plans A–C deliver authenticated accounts and onboarding but leave Me-tab content device-local. Plan D introduces the sync protocol, server data model, conflict resolution, and a one-shot client migration that promotes pre-release local content into the user's account. It is the largest dependency for any future cross-device feature (history sharing, recommendations, family-supervised accounts).

It cannot be folded into Plans B or C because:

- It changes the Android schema (Room v7 → v8) and adds new backend endpoints (`/api/account/sync`, write routes per entity type).
- It introduces a non-trivial state machine (account binding, tombstones, dirty queue) that benefits from a dedicated review.
- Plans E and F sit on top of it (moderator surfacing of associate submissions assumes account-bound data; admin UI's deleted-user view becomes more useful once user data has tombstones to inspect).

---

## 3. Locked design decisions

| # | Decision | Rationale |
|---|---|---|
| D1 | Sync scope is exactly three entity types: subscriptions, playlists, favorites. | User confirmed during brainstorm. `followed_channels` is a separate legacy concept; downloads and watch-history aren't part of Me-tab. |
| D2 | Anonymous → account merge on first sign-in is **additive (union)**. | Local content from the ≤20 pre-release testers must not be lost. Server LWW + tombstones reconcile any cross-device collisions on subsequent syncs. |
| D3 | Sync triggers: foreground (pull) + push-on-change (immediate write attempt) + connectivity-restored drain. **No WorkManager / background periodic.** | Battery-friendly and matches modern sync UX; cross-device immediacy is "good enough" via push-on-change. |
| D4 | Conflict policy is **last-write-wins by server-stamped `updatedAt`**, with tombstones-as-rows for deletes. | Falls out of monotonic Firestore server timestamps; no explicit conflict detection logic needed. Client-clock-trust avoided. |
| D5 | Archive integration is **silent removal via virtual tombstones** in sync responses. Underlying Firestore rows are NOT mutated. | Matches existing archived-content-leak gating (GlobalStreamResolver). User intent preserved for any future un-archive workflow without fan-out writes. |
| D6 | Authentication is mandatory. There is no anonymous Me-tab going forward. Local data is tagged with `user_id`; clears only on account switch (not on sign-out). | Confirmed by user. Sign-out preserves offline access; account switch wipes to prevent cross-account pollution. |
| D7 | Pre-release scale (≤20 users) → no staged rollout, no feature flag rampup. Single build constant `syncEnabled` exists only for emergency disable. | YAGNI. Re-introduce gradual rollout if Plan E/F users complain. |
| D8 | Plan D does not implement un-archive recovery. If admin un-archives a previously-archived item, users must manually re-add. | Avoids fan-out write storm. Documented as known limitation; revisit when un-archive becomes a real product workflow. |

---

## 4. Scope

### In scope

- New Firestore subcollections under `users/{uid}/`: `subscriptions/`, `playlists/`, `favorites/`.
- New REST endpoints: `GET /api/account/sync`, and `PUT|DELETE /api/account/{type}/{id}` for each of the three types.
- Room v8 migration: add `user_id`, `updated_at`, `deleted`, `dirty` columns to the three existing tables; create `sync_state` and `account_binding` tables.
- New Android `SyncManager` (Hilt `@Singleton`) with `bind()`, `pullAll()`, `pushDirty()`, `unbind()`.
- Archive projection on the server side, converting archived live rows into virtual tombstones in sync read responses.
- Firestore Security Rules covering the three new subcollections (defense-in-depth; backend `/api/account/*` is the primary gate).
- Tombstone GC (weekly cron, 90-day retention).
- Tests: backend unit + integration (emulator) + rules; Android unit + Room v8 instrumented migration.

### Out of scope

- **Plan E** — moderator workflow enhancement (e.g., `submittedByDisplayName` on `PendingApprovalDto`, `REQUEST_CHANGES` status). Plan D does not add associate-submission semantics.
- **Plan F** — admin UI expansion (paginated user search, block/recover UI, password-reset email). Plan D does not add admin-side data inspection.
- **Future** — watch history sync; download metadata sync; un-archive fan-out recovery; real-time push (Firestore listeners or WebSocket); multi-region replication.

---

## 5. Data model

### Server (Firestore)

Three subcollections per user, plus existing `users/{uid}` doc unchanged:

```
users/{uid}/subscriptions/{channelId}
  channelId      string
  channelUrl     string
  name           string
  avatarUrl      string?
  subscribedAt   long           // client-stamped; for display only
  updatedAt      server.timestamp
  deleted        bool           // tombstone-as-row

users/{uid}/playlists/{playlistId}
  playlistId     string
  playlistUrl    string
  name           string
  thumbnailUrl   string?
  uploaderName   string?
  savedAt        long           // client-stamped; for display only
  updatedAt      server.timestamp
  deleted        bool

users/{uid}/favorites/{videoId}
  videoId           string
  title             string
  channelName       string
  thumbnailUrl      string?
  durationSeconds   int
  addedAt           long        // client-stamped; for display only
  updatedAt         server.timestamp
  deleted           bool
```

**Tombstones-as-rows.** Delete sets `deleted=true, updatedAt=serverTs()`; the row stays. GC purges rows where `deleted=true AND updatedAt < now-90d` weekly (see §15).

**Cursor mechanics.** `updatedAt` is set on every write via `FieldValue.serverTimestamp()`. Clients sync per-type with `?{subs|playlists|favs}=<cursor>` query params; server queries `where updatedAt > cursor orderBy updatedAt asc limit 500`.

### Client (Room v8)

Existing tables get four added columns each:

| column | type | default | purpose |
|---|---|---|---|
| `user_id` | TEXT NOT NULL | `''` | account scoping; `''` = anon-era data awaiting tag-and-merge |
| `updated_at` | INTEGER NOT NULL | `0` | server `updatedAt` after last successful sync; `0` = never synced |
| `deleted` | INTEGER NOT NULL | `0` | tombstone flag |
| `dirty` | INTEGER NOT NULL | `0` | push-pending flag |

Two new tables:

```
@Entity(tableName = "sync_state")
data class SyncStateRow(
    @PrimaryKey val entityType: String,   // "subscriptions" | "playlists" | "favorites"
    val user_id: String,
    val last_cursor: Long,                // most recent server updatedAt pulled
    val last_sync_at: Long                // local clock; for debug only
)

@Entity(tableName = "account_binding")
data class AccountBinding(
    @PrimaryKey val user_id: String,
    val bound_at: Long,
    val initial_merge_done: Boolean
)
```

**No `pending_ops` table.** `dirty=1` on the entity row is the push queue. Push worker scans `WHERE dirty=1 AND user_id=:current` and sends the row's current state (PUT if `deleted=0`, DELETE if `deleted=1`). Idempotent — late intermediate states never hit the wire.

### Migration v7 → v8

Pure additive ALTER (per existing migration style in `Migrations.kt`):

```sql
ALTER TABLE subscribed_channels ADD COLUMN user_id    TEXT    NOT NULL DEFAULT '';
ALTER TABLE subscribed_channels ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0;
ALTER TABLE subscribed_channels ADD COLUMN deleted    INTEGER NOT NULL DEFAULT 0;
ALTER TABLE subscribed_channels ADD COLUMN dirty      INTEGER NOT NULL DEFAULT 0;
-- same for saved_playlists, favorite_videos

CREATE TABLE IF NOT EXISTS sync_state (
  entityType    TEXT    NOT NULL PRIMARY KEY,
  user_id       TEXT    NOT NULL,
  last_cursor   INTEGER NOT NULL,
  last_sync_at  INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS account_binding (
  user_id              TEXT    NOT NULL PRIMARY KEY,
  bound_at             INTEGER NOT NULL,
  initial_merge_done   INTEGER NOT NULL
);
```

Self-heal pattern: each `CREATE TABLE IF NOT EXISTS` mirrors the existing defensive migration style (MIGRATION_2_3 etc.) so reapply is safe.

No row rewrites in the migration itself. The bind/runMerge flow (§8) tags rows and marks them dirty.

---

## 6. API surface

All under `/api/account/`. Firebase token required (existing `FirebaseAuthInterceptor`). `AccountStatusFilter` (Plan A) gates BLOCKED/DELETED uids before any sync handler runs. `PENDING_PROFILE` is allowed — sync is independent of profile completion.

### Pull

```
GET /api/account/sync?subs=<cursor>&playlists=<cursor>&favorites=<cursor>
→ 200 {
    subscriptions: { items: [SubscriptionDto…], nextCursor: long|null },
    playlists:     { items: [PlaylistDto…],     nextCursor: long|null },
    favorites:     { items: [FavoriteDto…],     nextCursor: long|null }
  }
```

- Per-type cursor: `cursor` query param is the client's `sync_state.last_cursor` for that type. Default `0` if absent → full pull.
- Page size cap **500 per type**; `nextCursor` is non-null when results saturate → caller loops with the new cursor for that type only.
- Each row dto includes `deleted` flag. Virtual tombstones (archived items) arrive identically to real tombstones.
- Cursor advances per-type independently — type X may be `null` (no more) while type Y still pages.

### Push

```
PUT    /api/account/subscriptions/{channelId}    body: { channelUrl, name, avatarUrl, subscribedAt }
DELETE /api/account/subscriptions/{channelId}

PUT    /api/account/playlists/{playlistId}       body: { playlistUrl, name, thumbnailUrl, uploaderName, savedAt }
DELETE /api/account/playlists/{playlistId}

PUT    /api/account/favorites/{videoId}          body: { title, channelName, thumbnailUrl, durationSeconds, addedAt }
DELETE /api/account/favorites/{videoId}

→ 200 { entityType, entityId, updatedAt, deleted }     // echo of stored row
```

PUT is upsert: overrides existing tombstone for the same id. Body fields are the metadata snapshot. Client-stamped `*_at` fields are stored verbatim for UI ordering; the sync protocol always uses the server `updatedAt`.

### Response codes

| code | meaning | client behavior |
|---|---|---|
| 200 | success | apply normally |
| 400 | malformed body | log; do not retry; surface in debug logs |
| 401 | Firebase token expired or revoked | `FirebaseAuthInterceptor` refreshes once; if still 401, propagate (likely sign-out) |
| 403 | account `BLOCKED` / `DELETED` (or `PENDING_PROFILE` for a route requiring `ACTIVE` — none in Plan D) | `AccountStatusInterceptor` (Plan C) shows status dialog |
| 404 | DELETE on a non-existent id | treat as success (idempotent) |
| 429 | rate limited | exponential backoff |
| 5xx | server error | exponential backoff; `dirty=1` rows retried on next cycle |

No 409 — LWW means the server never rejects on conflict; the latest write simply wins.

---

## 7. Conflict mechanics

LWW falls out of monotonic Firestore server timestamps; there is no explicit detection logic.

On every push (PUT or DELETE) the server writes:
```kotlin
docRef.set(mapOf(
  // …row data…
  "deleted" to (op == DELETE),
  "updatedAt" to FieldValue.serverTimestamp()
), SetOptions.merge())
```

A tombstone with `updatedAt=T1` is overwritten by a PUT with `updatedAt=T2 > T1` (and vice versa). No race window can produce a different outcome because Firestore serializes per-doc writes and assigns timestamps in commit order.

On the client, pulled deltas apply in row order. For each row:
- `deleted=true` → mark local row `deleted=1, updated_at=row.updatedAt, dirty=0`
- `deleted=false` → upsert with the row's metadata, `updated_at=row.updatedAt, dirty=0`
- Track max `updatedAt` seen per type → write to `sync_state.last_cursor`.

The client never overwrites a row whose local `updated_at` is **newer** than the pulled row's — that case can only happen if the local row is locally dirty (push hasn't fired yet), in which case the dirty version will be pushed and the server will assign a fresh `updatedAt`. Pull-before-push therefore cannot regress local state.

---

## 8. Client sync orchestrator

`SyncManager` is a Hilt `@Singleton` backed by a coroutine scope tied to `ProcessLifecycleOwner`.

### Triggers

| event | action |
|---|---|
| Sign-in success (Plan C `SplashRouter` resolves to `/me`) | `bind(uid)` |
| App foreground (`ON_RESUME` on root activity) | `pullAll(uid); pushDirty(uid)` |
| Local mutation in any repo (subscribe / save / favorite or their inverses) | repo writes `dirty=1, user_id=current`; calls `pushDirty(uid)` non-blocking |
| Connectivity restored (`ConnectivityManager.NetworkCallback#onAvailable`) | `pushDirty(uid)` |
| Sign-out | `unbind()` — clears in-memory state; tables retain their `user_id` tags for the next sign-in's account-switch check |

### `bind(uid)` decision matrix

```kotlin
suspend fun bind(uid: String) {
    val binding = accountBindingDao.get()
    when {
        binding == null -> {
            accountBindingDao.insert(AccountBinding(uid, now(), initialMergeDone = false))
            runMerge(uid)
        }
        binding.user_id == uid -> {
            pullAll(uid)
            pushDirty(uid)
        }
        else -> {
            // account switch
            repos.deleteAllFor(binding.user_id)
            syncStateDao.clear()
            accountBindingDao.update(uid, boundAt = now(), initialMergeDone = false)
            runMerge(uid)
        }
    }
}
```

### `runMerge(uid)` — additive merge (D2)

```kotlin
suspend fun runMerge(uid: String) {
    // Step 1: tag any anon-era rows and any leftover-from-old-account dirty rows
    db.withTransaction {
        for (table in [subscribed_channels, saved_playlists, favorite_videos]) {
            db.execSQL("""
                UPDATE $table
                   SET user_id = :uid, dirty = 1
                 WHERE user_id = '' OR (user_id = :uid AND dirty = 1)
            """)
        }
    }
    // Step 2: pull server state — collisions overwrite local, clearing dirty for those
    pullAll(uid)
    // Step 3: push remaining dirty rows = local-only items not yet on server
    pushDirty(uid)
    // Step 4: mark merge done
    accountBindingDao.markMergeDone(uid)
}
```

Convergence: after step 3 the server holds `(server_initial ∪ local_only)` and local mirrors the server. Subsequent foregrounds run only steps 2 and 3 (no re-tagging).

### `pullAll(uid)`

```kotlin
suspend fun pullAll(uid: String) {
    val cursors = syncStateDao.getCursors(uid)            // {subs, playlists, favorites}
    var more: Boolean
    do {
        val resp = api.sync(cursors)
        more = false
        for ((type, page) in resp) {
            db.withTransaction {
                for (row in page.items) {
                    if (row.deleted) daoFor(type).applyTombstone(uid, row.id, row.updatedAt)
                    else             daoFor(type).upsertFromServer(uid, row)
                }
                page.items.maxByOrNull { it.updatedAt }?.let {
                    syncStateDao.setCursor(type, uid, it.updatedAt)
                    cursors[type] = it.updatedAt
                }
            }
            if (page.nextCursor != null) more = true
        }
    } while (more)
}
```

Single in-flight pull per uid (mutex). 401 → `FirebaseAuthInterceptor` refreshes once and the next request inside the same `pullAll` call succeeds or aborts. 403 → propagates via existing `AccountStatusInterceptor`. 5xx → returns; next foreground retries.

### `pushDirty(uid)`

```kotlin
suspend fun pushDirty(uid: String) {
    val dirty = collectDirtyRowsForAllTypes(uid)
    for (row in dirty) {
        val result = if (row.deleted) api.delete(row) else api.put(row)
        when (result.code) {
            in 200..299 -> daoFor(row.type).clearDirty(uid, row.id, result.body.updatedAt)
            404         -> if (row.deleted) daoFor(row.type).clearDirty(uid, row.id, now()) else surfaceError()
            401         -> { refreshAndRetryOnce(row); /* on second 401, abort whole push */ }
            403         -> { propagateAccountStatus(); return }
            429, in 500..599 -> { applyBackoff(row); return }       // stop draining; come back on next trigger
            else        -> logAndAbort()
        }
    }
}
```

Per-row exponential backoff: 1s, 2s, 4s, 8s, 16s, 32s, 60s (capped). Persisted in-memory (loss on process death is acceptable — dirty rows re-enqueue automatically).

### Repository surface

`SubscriptionRepository.subscribe(channel)` / `unsubscribe(channelId)` and the equivalents on `SavedPlaylistsRepository` / `FavoritesRepository` now:
1. Insert/update the Room row with `user_id=current, dirty=1`. If delete, set `deleted=1, dirty=1` (keep the row).
2. Call `syncManager.pushDirty(uid)` fire-and-forget.

UI flows above the repositories are unchanged — sync is transparent. Existing flows (MeFeedRepository, ChannelDetailFragment, etc.) read from Room as before but with an added `WHERE user_id=:current AND deleted=0` predicate.

---

## 9. Archive integration

### `ArchiveProjector` (new backend component)

Wraps the result of each sync read query. For each row where `deleted=false`, looks up the entity ID against the existing global archive registry (the same data source the GlobalStreamResolver gate uses, per the recent archive-content-leak fix). If archived:

```kotlin
row.copy(deleted = true)   // synthesize a virtual tombstone in-memory
```

The underlying Firestore document is **not mutated**. Consequences:

- Client receives a tombstone, removes the local row, advances cursor — orphan cleanup is automatic.
- If an admin un-archives the item later, the server row's `updatedAt` is unchanged → existing devices won't see it re-appear (D8 known limitation).
- A device that signs in fresh after un-archive will pull the live (non-archived) row, since the projector only filters at read time and now reports `archived=false`.

### Why not delete the Firestore row?

Two reasons:
1. **User intent preservation.** If un-archive becomes a workflow later, the row is still there.
2. **No fan-out write.** Otherwise admin archive of a popular item would trigger N writes (one per subscriber) inside the archive workflow.

---

## 10. Firestore Security Rules (additive)

```
match /databases/{db}/documents {

  function isSelf(uid)  { return request.auth != null && request.auth.uid == uid; }
  function allowsAuth(uid) {
    // mirrors AccountStatusFilter: ACTIVE or PENDING_PROFILE
    let user = get(/databases/$(db)/documents/users/$(uid)).data;
    return user.status in ['ACTIVE', 'PENDING_PROFILE'];
  }

  match /users/{uid}/subscriptions/{channelId} {
    allow read, write: if isSelf(uid) && allowsAuth(uid);
  }
  match /users/{uid}/playlists/{playlistId} {
    allow read, write: if isSelf(uid) && allowsAuth(uid);
  }
  match /users/{uid}/favorites/{videoId} {
    allow read, write: if isSelf(uid) && allowsAuth(uid);
  }
}
```

The backend `/api/account/*` is the primary gate; Rules are defense in depth in case any future client tries to read/write Firestore directly via the Firebase SDK.

---

## 11. Audit log and caching

**Audit.** None for routine sync. Plan A's audit log is for admin actions; user-on-self sync is too noisy to instrument. Plan E may revisit (associate submission needs audit).

**Caching.** None. Sync reads are cursor-based and small; per-user cache would invalidate on every write. Existing Caffeine caches (`youtubeChannelSearch` etc.) are unaffected.

---

## 12. Tombstone GC

Weekly Spring `@Scheduled` job:

```kotlin
@Scheduled(cron = "0 0 3 * * SUN")  // Sunday 03:00 UTC
fun pruneTombstones() {
    val cutoff = Timestamp.of(Instant.now().minus(Duration.ofDays(90)))
    for (type in listOf("subscriptions", "playlists", "favorites")) {
        val docs = firestore.collectionGroup(type)
            .whereEqualTo("deleted", true)
            .whereLessThan("updatedAt", cutoff)
            .get().get()                            // sync for cron job
        var purged = 0
        for (doc in docs) { doc.reference.delete().get(); purged++ }
        metrics.counter("account.sync.tombstone.gc.purged", "type", type).increment(purged.toDouble())
    }
}
```

`collectionGroup` queries the same subcollection name across all users. Single weekly job; bounded work; safe to re-run.

---

## 13. Testing

### Backend — unit (Mockito, no Firestore)
- `SyncControllerTest` — route handling, body validation, propagation of 401/403/404
- `SyncServiceTest` — cursor advancement; 500-cap pagination; tombstone-as-row write path; PUT-over-tombstone overwrites correctly
- `ArchiveProjectorTest` — non-archived passes through; archived → virtual tombstone; preexisting real tombstone passes unchanged; mixed result set
- LWW behaviour with mock clock — concurrent put/delete on same id resolves by `updatedAt`

### Backend — integration (Firebase emulator)
- `SyncControllerIT` — full pull/push cycle per type; cursor monotonicity; pagination loop terminates at `nextCursor=null`
- `SyncStatusFilterIT` — BLOCKED → 403 on every sync route; DELETED → 401; PENDING_PROFILE allowed
- `SyncArchiveIT` — admin archives a channel mid-test; subsequent pull returns it as virtual tombstone; further pulls do not re-emit (cursor advanced past it)
- `SyncCrossUserIT` — uid A token cannot read or write uid B's subcollections (Rules-enforced 403)
- `SyncTombstoneGcIT` — seed deleted rows older than 90d + newer; run GC job; assert only older rows purged

### Firestore rules (emulator)
- self-read / self-write allowed for ACTIVE and PENDING_PROFILE
- cross-uid denied
- BLOCKED and DELETED denied even with matching uid (defense in depth)

### Android — unit (JVM, in-memory Room)
- `SyncManagerBindTest` — decision matrix (null binding / same uid / different uid); each branch's side-effects asserted
- `RunMergeConvergenceTest` — pre-tagged dirty rows + server-pulled rows → final state is set union; `account_binding.initialMergeDone=true` after success
- `PullAllTest` — virtual tombstone applied; cursor advanced to max `updatedAt`; idempotent rerun is no-op; pagination loop with `nextCursor != null` then `null`
- `PushDirtyTest` — backoff schedule; retry-once on 401; abort on 403; 404 on DELETE treated as success; 5xx breaks loop and re-enqueues
- `RaceTests` — `subscribe(X)` then `unsubscribe(X)` before push fires → single DELETE pushed; PUT-DELETE-PUT → single PUT pushed (last intent wins)

### Android — instrumented (Room migration)
- `AppDatabaseMigration7to8Test` — seed v7 DB with rows in all three tables, run migration, assert: new columns present with defaults (`user_id=''`, `dirty=0`, `deleted=0`, `updated_at=0`); `sync_state` and `account_binding` tables created and empty; original row data preserved
- DAO tests for new columns and the two new tables (read/write/query-by-user_id)

---

## 14. Observability

### Logs (structured, INFO)
- `account.sync.pull uid=… cursors=… items={subs:N,pl:M,fav:K} durationMs=…`
- `account.sync.push uid=… type=… id=… op=PUT|DELETE result=2xx|4xx|5xx`
- ERROR on repeated 5xx, token refresh failures, archive projector errors

### Metrics (Micrometer, via existing `MetricsConfig`)
- `account.sync.pull.count{type}` counter
- `account.sync.push.count{type, op, status}` counter
- `account.sync.pull.duration{type}` timer
- `account.sync.archive.virtualTombstone.count{type}` counter — visibility into archive pruning rate
- `account.sync.tombstone.gc.purged{type}` counter

---

## 15. Rollout and rollback

### Rollout
1. **Backend first** — ship `SyncController`, `SyncService`, `ArchiveProjector`, Security Rules, GC scheduler. No client traffic yet. Run full integration suite + emulator rules suite.
2. **Android second** — ship Room v8 migration + `SyncManager` + repo wiring. Build constant `BuildConfig.SYNC_ENABLED = true`; flag is for emergency disable only (no staged rollout — ≤20 users).
3. **First-sign-in merge** — testers open the updated app → splash binds → `runMerge` tags untagged rows, pulls server (empty for first signer), pushes the tagged rows. Subsequent device sign-ins pull the populated server state.

### Rollback
- **Backend** — safe to redeploy older version; new endpoints simply stop being served. Already-written Firestore data is forward-compatible (older deploys ignore unknown fields).
- **Android** — Room v8 → v7 is not supported by Room. Rollback path for a deployed APK: ship a patched APK that disables `SyncManager` (BuildConfig flag) but leaves the v8 schema in place; testers' local data remains intact. Hard rollback (uninstall + reinstall) recovers state from server. Document in release notes.

---

## 16. Risks

| risk | mitigation |
|---|---|
| Firestore per-doc 1-write/sec throughput limit on a hot subscription doc | Not hit at ≤20 users. Revisit if a single tester subscribes and unsubscribes rapidly, but per-doc throughput is irrelevant unless multiple writers contend — and each user writes only to their own subcollection. |
| Tombstone collection growth | 90-day GC via weekly `@Scheduled` job (§12). |
| Token refresh storms during long pull loop | One refresh per request via `FirebaseAuthInterceptor`. Pulls are read-only and idempotent so re-execution is safe. |
| Many `dirty=1` rows after long offline period | Push worker drains serially with backoff; bounded request rate; no spike risk. |
| Archive un-recovery (§9) | Documented limitation (D8). Plan D ships without. |
| Simultaneous first-sign-in across multiple devices | Server LWW + monotonic timestamps converge. No coordination needed. |
| Account-switch wipe loses unsynced dirty rows from previous account | Acceptable — switching accounts on the same device is rare (no production use case identified). If it becomes a real workflow, a "drain before switch" pre-step can be added later. |

---

## 17. Open questions

- **GC retention window:** 90 days assumed. Confirm before implementation. (Default reasonable for cross-device convergence — most user devices reconnect within 90 days.)
- **Page size cap:** 500 chosen as a round number. Likely never hit at our scale; revisit if observed.
- **Build constant location:** `BuildConfig.SYNC_ENABLED` vs `RemoteConfig`. Plan D uses BuildConfig (simpler, no extra Firebase dep); future plans may move to RemoteConfig if A/B testing is needed.

---

## 18. References

- Plan A spec: `docs/superpowers/specs/2026-05-10-backend-account-foundation-design.md`
- Plan B spec: `docs/superpowers/specs/2026-05-11-android-auth-design.md`
- Plan C spec: `docs/superpowers/specs/2026-05-11-account-bootstrap-design.md`
- NewPipe library guide: `docs/library-guides/newpipe-extractor.md`
- Existing Room migrations: `android/app/src/main/java/com/albunyaan/tube/data/local/Migrations.kt`
- Archive-content-leak fix (merged commit `f078442f`) — sets the precedent that availability/archive gating happens at chokepoints, not at every call site.
