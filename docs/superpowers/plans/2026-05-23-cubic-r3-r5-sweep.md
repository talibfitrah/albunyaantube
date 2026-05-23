# Plan: Cubic R3-R5 sweep — deferred P2/P3 polish

**Date raised:** 2026-05-23 (post-Plan-G 7-stage review pipeline; cubic R3-R5).
**Status:** Deferred from the post-Plan-G pipeline patch rounds. Scope was P0+P1; the items below are P2/P3 quality wins surfaced during cubic rounds 3-5 that were intentionally postponed to keep the pipeline run finite.
**Severity:** Mixed; no P0/P1 in this list (those were patched in-pipeline). Most items 5-30 min each.

## Related deferrals (separate plan docs)

- `2026-05-23-bulk-cross-admin-firestore-tx.md` — Firestore native tx with re-check inside writer to close cross-admin/cross-pod dedupe race.
- `2026-05-23-getmysubmissions-12x-firestore-amplification.md` — parallelise + `whereIn` to cut `/my-submissions` Firestore cost.

These two surfaced again in R3-R5 — they remain the priority backlog items for the next active-user growth bump.

---

## P2 — default-fix in next polish round

### Backend

**P2.1 — Submit lacks batch-level timeout (R1 #10, R2 #2, R3 #5, R4 #4, R5, Stage 6 F2).**
File: `backend/src/main/java/com/albunyaan/tube/service/BulkSubmissionService.java:271-291`
The preview path has `CompletableFuture.allOf(...).get(180, SECONDS)`; submit is sequential per-row with no global cap. A hung NewPipe can pin the Tomcat request thread for 25 × per-call timeout. Inline comment + rate-limit dampens but doesn't close.
Fix sketch: lift the per-row gateway fetch into the same `bulkPreviewExecutor`-based fan-out preview uses, with a 180s `allOf().get()` cap. Materialise a `Map<rowIndex, PreviewFetchResult>` before the sequential dedupe+write loop. Roughly 60-90 min including tests.

**P2.2 — Bulk preview rate-limit not row-count-aware (R5).**
File: `backend/src/main/java/com/albunyaan/tube/security/SubmissionRateLimitInterceptor.java`
Preview consumes 1 slot per HTTP call vs 1 per URL. A moderator can fan out 50 × 25 = 1,250 NewPipe extractions/day. Submit already fixed (`88a6d75a`). Mirror the same `tryAcquire(uid, urls.size())` pattern in the preview path.

**P2.3 — IllegalArgumentException scrubbing too broad (R3 #1, R4 #3, R5 re-flag).**
File: `backend/src/main/java/com/albunyaan/tube/exception/GlobalExceptionHandler.java:169`
Cubic R3 in commit `093e9ca7` swapped `ex.getMessage()` → `"Bad request"` to stop info leaks. Subsequent cubic rounds re-flagged that this hides legit validation messages (e.g. `ApprovalService.getMySubmissions` "Invalid status: X. Must be one of: …", `RegistryController.sanitizeSubmitterNote` "submitterNote exceeds max length 1000").
Fix sketch: introduce `ValidationException extends IllegalArgumentException` for legit user-facing validation paths; add a dedicated `@ExceptionHandler(ValidationException.class)` that returns `ex.getMessage()`. Keep generic `IllegalArgumentException` handler scrubbed. Touch the 3-4 call sites that throw user-facing messages.

**P2.4 — 429 envelope shape inconsistency (R5).**
File: `backend/src/main/java/com/albunyaan/tube/exception/GlobalExceptionHandler.java:278, 302`
`YouTubeSearchRateLimitedException` returns Spring's `{timestamp,status,error,message,path}`; `BulkSubmissionRateLimitedException` returns `{code,retryAfterSeconds,message}` to match the interceptor. Two distinct envelopes for the same HTTP status.
Fix sketch: pick one (the interceptor-compatible `{code,retryAfterSeconds,message}` is the better contract for clients) and converge both handlers + the YouTube search handler.

**P2.5 — `pruneUnsubscribed` missing `deleted = 0` on `subscribed_channels` (R5).**
File: `android/app/src/main/java/com/albunyaan/tube/data/local/ChannelVideoCacheDao.kt:97`
Sibling `PlaylistVideoLinkDao.pruneOrphans` filters `WHERE deleted = 0` on `saved_playlists`. Channel-cache prune doesn't. Tombstoned (soft-deleted-but-not-synced) subscriptions still appear in `subscribed_channels` until hard-clear, so their cache rows survive.
Fix sketch: `… WHERE channelId NOT IN (SELECT channelId FROM subscribed_channels WHERE deleted = 0) AND …`. Verify `subscribed_channels` actually has a `deleted` column (likely yes per sibling DAO pattern).

**P2.6 — Negative-result fall-through refreshes TTL of stale positive cache (R5).**
File: `android/app/src/main/java/com/albunyaan/tube/data/playlist/NewPipeChannelDetailRepository.kt` (channelIdCache write site)
`toCache = resolved ?: channelIdCache[uploaderUrl]?.value` then `CacheEntry(toCache, System.currentTimeMillis())` re-stamps the current timestamp on an arbitrarily old positive value when YouTube is degraded. A stale channelId can survive indefinitely.
Fix sketch: preserve the original timestamp on fall-through, or only fall back when the prior entry is still within the positive TTL.

**P2.7 — Single-flight + TTL caches keyed on raw uploader URL (R5).**
File: same family as P2.6.
Equivalent URLs (scheme, trailing slash, `si=` param) all bypass dedup, each firing its own NewPipe channel-page fetch.
Fix sketch: normalise key (lowercase host, strip query/fragment/trailing slash) before lookup. Reuse for both `inflightChannelInfo` and `channelInfoCache`.

**P2.8 — 5-minute negative TTL poisons handle-rename recovery (R5).**
File: same family.
A single broken `@deleted_user` blocks resolution for 5 minutes even if the next call would succeed. Combined with P2.7's un-normalised keys, a user with many `/@deleted_user` entries gets many simultaneously-poisoned slots.
Fix sketch: exponential backoff (1m → 5m → 30m) keyed on consecutive failures, or skip negative cache when `rawExtracted == null`.

**P2.9 — Race admits two NewPipe fetches for the same channel (R5).**
File: same family.
Cache write outside the mutex that guards `inflightChannelInfo`: a second caller can acquire mutex after first deferred completed+removed-itself but before the cache write landed → duplicate fetch.
Fix sketch: move cache write inside the same mutex block as in-flight removal.

**P2.10 — Playlist-cache rows with empty-string channelId (R5).**
File: `android/app/src/main/java/com/albunyaan/tube/data/me/MeFeedRepository.kt` (playlist refresh path that writes `ChannelVideoCache`)
`ChannelVideoCache(channelId = item.channelId.orEmpty(), …)` — empty `""` participates in `channelId IN (…)` queries and `pruneUnsubscribed`.
Fix sketch: skip rows with no channelId, or sentinel them `__playlist__:<playlistId>` so they cannot collide with real channel rows in chip filters.

**P2.11 — ALL-status merge sort no secondary key (R5).**
File: `backend/src/main/java/com/albunyaan/tube/service/ApprovalService.java:419-426`
Comparator returns `0` when `submittedAt` values are equal; `merged.subList(0, pageSize)` then flip-flops between requests for items submitted in the same Firestore millisecond.
Fix sketch: stable secondary sort on document id.

**P2.12 — `delay(index * STAGGER_MS)` inside `semaphore.withPermit` (R5).**
File: `android/app/src/main/java/com/albunyaan/tube/data/me/MeFeedRepository.kt` (refresh fan-out site)
Stagger holds a scarce permit while sleeping; `MAX_CONCURRENT` permits blocked on delay instead of doing useful work.
Fix sketch: move `delay(index * STAGGER_MS)` outside `semaphore.withPermit { ... }`.

**P2.13 — Blank-uid guard missing on `updateFields` (R5).**
File: `backend/src/main/java/com/albunyaan/tube/repository/UserRepository.java` (updateFields)
`save()` was hardened to reject blank uid; `updateFields(uid, ...)` resolves blank to `getCollection().document("")` → Firestore throws `IllegalArgumentException` at call time. Defensible by SDK behaviour, but matching pre-check would mirror `save()` safety.

**P2.14 — `SignedOut` event handler self-reentrance (R5).**
File: relevant `AccountStatus` event collector.
If `signOut()` emits `SignedOut` which the collector observes, it re-enters `signOut()`. Idempotent in steady state if `signOut()` short-circuits on already-signed-out; worth a unit test on the loop guard.

**P2.15 — Cross-admin REJECTED resubmit via direct API (R4 #1).**
File: `backend/src/main/java/com/albunyaan/tube/service/BulkSubmissionService.java:172`
Frontend filters DUPLICATE_REJECTED from submittable; direct-API call could still bypass. Resubmits land as PENDING (downstream admin review re-applies) but shadow the original rejection's audit metadata.
Fix sketch: in the `lateBatch.findExisting().status == "REJECTED"` branch, return a result with `DUPLICATE_REJECTED` errorCode rather than treating REJECTED as "available for fresh insert".

### Frontend

**P2.16 — Bulk submit silent ERROR-row drop (R4 #8).**
File: `frontend/src/stores/bulkSubmissionStore.ts:88` + `frontend/src/components/contentSearch/bulk/BulkPreviewTable.vue`
`submittable = previewRows.filter(r => r.status === 'OK')` silently drops 10 errors in a 25-row paste. Toast doesn't show which were excluded.
Fix sketch: above-button caption "Submitting N of M rows — K invalid will be skipped" or block submit until invalid rows are removed.

**P2.17 — Status counter omits `DUPLICATE_REJECTED` (R5).**
File: `frontend/src/components/contentSearch/bulk/BulkPreviewTable.vue`
A batch where every row is DUPLICATE_REJECTED reads "0 valid · 0 duplicate · 0 error" — DUPLICATE_REJECTED has no counter. After my R1 fix that dropped it from `counts.valid`, it's now uncounted.
Fix sketch: include DUPLICATE_REJECTED in the duplicate count (the visual category they fit), or add a 4th counter. Also disable per-row category editor for those rows (already done by `isActionable` after R1 fix).

**P2.18 — Direct `store.phase = 'INPUT'` mutation bypasses Pinia action (R5).**
File: `frontend/src/components/contentSearch/bulk/BulkPreviewTable.vue` back-button
Future fixes that need to reset `previewRows`/`error` on back-navigation get retrofitted at every callsite.
Fix sketch: add a `goBackToInput()` action that owns the transition (resets phase + clears error).

**P2.19 — No request cancellation / double-submit guard (R5).**
File: `frontend/src/stores/bulkSubmissionStore.ts`
Slow preview races with user back-navigation; in-flight response resolves and overwrites newly-reset state.
Fix sketch: AbortController per request, or `if (this.phase === 'LOADING') return` guard at action entry.

**P2.20 — Modal lacks ESC handler, focus trap, aria-modal (R5).**
File: bulk-flow modals (e.g. `BulkFormatHelpModal.vue`)
Bootstrap 5 styles without the JS controller. ESC doesn't close, focus not moved into dialog, no `role="dialog"` / `aria-modal="true"`.
Fix sketch: wrap in a small `<TheModal>` component using `<dialog>` element or Headless UI for the a11y semantics.

**P2.21 — Module-level cache never invalidates (R5).**
File: `frontend/src/components/contentSearch/bulk/useCategoryPicker.ts`
`cachedPromise` persists until full page reload — edits to the category tree in another tab leave the bulk picker stale. (Already noted in code comment as separate-ticket.)
Fix sketch: expose `reset()`; categories-edit view calls it after mutations. Or invalidate on `window.focus`.

**P2.22 — `flatten(nodes)` walks tree with no cycle/depth guard (R5).**
File: `frontend/src/components/contentSearch/bulk/useCategoryPicker.ts`
Pathologically self-referential Category response recurses forever and locks the admin browser.
Fix sketch: `WeakSet` visited guard or depth cap (6 levels — categories are shallow in practice).

**P2.23 — In-memory uid backfill no telemetry (R5).**
File: account service hydrate-fallback path.
Fallback correctly doesn't write back, but silently papers over documents missing the persisted `uid`. Any future `whereEqualTo("uid", x)` query misses those rows.
Fix sketch: counter/log when hydrate() fires the fallback so the residual cohort is observable; schedule a one-shot migration once known.

**P2.24 — Stale-client check for migrated top-level fields (R5).**
Old admin frontends / Android clients still reading from `metadata` will get `null` from the now-removed metadata entries.
Fix sketch: grep both clients for `metadata.thumbnailUrl` / `metadata.youtubeId` / `metadata.submitterNote` and confirm migration is complete; if any reader remains, defer the metadata-removal until they catch up.

---

## P3 — judgement call / cosmetic

**P3.1 — Stage 6 F3: `countForChannelsOrPlaylists` no `uploadedAt` predicate.**
SQLite scans the entire `channel_video_cache` table. Fine at current cache sizes; would bite at power-user scale.

**P3.2 — extraSlotsNeeded math fragile (R4 #7).**
`rows.size() - 1` assumes interceptor consumed 1. Today correct; future routing change could silently shift by one slot per call.

**P3.3 — Rate-limit slots consumed for all-invalid batches (R3 #2).**
No refund mechanism on `SubmissionRateLimiter`. A 25-row paste with all-INVALID_URL burns 25 slots for 0 writes. Acceptable per existing precedent (single-add interceptor doesn't refund either).

**P3.4 — Interactive search dropped circuit-breaker check (R3 #3).**
Explicit UX trade-off in commit `6cebe143`. Reconsider when active-user metrics show real moderator-search-vs-validation contention.

**P3.5 — Stacked timestamps in deque (R3 #9).**
`tryAcquire(uid, count)` adds `now` `count` times. Cosmetic — sliding window peeled later releases all `count` at once instead of staggered. No security/correctness impact.

**P3.6 — `watch?v=X&list=Y` URLs always classified as VIDEO (R1 #8).**
Mirrors YouTube's own behaviour (URL with both means "this video in the context of that playlist", primary entity = video). Moderator intending to register the playlist must strip `v=`.
Fix sketch: optional `AMBIGUOUS_URL` error, or UI hint when both params present.

**P3.7 — `f.cancel(true)` doesn't interrupt NewPipe worker (R1 #7, R5).**
`CompletableFuture.cancel(mayInterruptIfRunning)` ignores the flag per Java docs. Acknowledged in code comment. Closing requires NewPipe Downloader-level interrupt handling.

**P3.8 — mqdefault → maxresdefault 404s frequently (R5).**
YouTube only generates maxresdefault for ≥720p uploads. Many legacy/short videos 404, triggering fallback. Use `sddefault` (near-universal) for mid-DPI; reserve maxres for high-DPI.

**P3.9 — `playlist_video_link` no FK constraints (R5).**
Room can enforce `playlistId → saved_playlists.playlistId` and `videoId → channel_video_cache.videoId`. Current code has correct delete order, so this is a free safety net.

**P3.10 — Initial migration leaves `playlist_video_link` empty (R5).**
Existing users see empty playlist contribution to Me feed until `refreshPlaylistVideos()` runs. Acceptable; flag in release notes.

**P3.11 — 200s frontend timeout 20s margin (R5).**
Network RTT + Tomcat serialisation on a 179.5s preview can still 504 client-side. Bump to 230s, or expose a streaming preview.

**P3.12 — Hard-coded `←` arrow reads backwards in RTL (R5).**
Logical-back arrow in RTL should be `→`, or use a CSS logical-property pseudo-element so the icon mirrors automatically.

**P3.13 — Fragment-stripped `originalUrl` in admin UI (R5).**
`new URI("https://www.YOUTUBE.com#@evil.com/x")` parses with allow-listed host but fragment payload persists on `SubmitRow.originalUrl` → rendered in admin UI. Canonical URL safe; stored `originalUrl` is not.
Fix sketch: normalise stored `originalUrl` to canonical form (or strip fragments) before persisting.

**P3.14 — `youtubeId` / `submitterNote` cast workaround until regenerator runs (R5, R3 #7).**
OpenAPI spec was updated (`033a98de`, `a3056686`) but generated `schema.ts` isn't regenerated. Frontend keeps `as unknown as { youtubeId?: string }` cast.
Fix sketch: run `./scripts/generate-openapi-dtos.sh` (requires backend up); drop the cast in `approvalService.ts:99`.

---

## Execution

This is a single sweep ticket; don't bundle into one PR. Each P2 is independent — pick what's most impactful at the time you re-engage:

- Highest-leverage three: P2.1 (submit batch timeout — covers a real DoS amplifier), P2.5 (deleted=0 prune filter — real data correctness), P2.16 (silent ERROR-row drop — real UX bug).
- Lowest-cost wins: P2.13 (blank-uid pre-check), P2.20 (modal a11y wrapper), P3.14 (regen schema).

After execution, re-run the 7-stage review pipeline focused on the changed files to surface anything the sweep introduces.
