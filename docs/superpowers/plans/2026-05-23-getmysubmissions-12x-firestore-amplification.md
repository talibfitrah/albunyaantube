# Plan: Reduce `getMySubmissionsAllStatuses` 12× Firestore amplification

**Date raised:** 2026-05-23 (cubic R1 on post-Plan-G review pipeline, finding #3)
**Status:** Deferred from immediate patch round. Pre-release scale.
**Severity:** P1 by cubic. P2 in practice at current scale.

## The amplification

`ApprovalService.getMySubmissionsAllStatuses(submittedBy, type, pageSize)`
(used when the Android "My Submissions" screen calls with `status=null` to
see every state) loops over 4 statuses × calls `getSubmissionsMixed` per
status, which itself does 3 Firestore queries (one per type: channels,
playlists, videos) when `type` is null.

  4 statuses × 3 type sub-queries = **12 Firestore queries** per HTTP call

Each sub-query returns up to `pageSize` (default 20) docs → up to 240 docs
read, sorted client-side in Java, trimmed to 20, returned.

## Practical impact today

Pre-release deployment: <20 users × ~10 "My Submissions" opens/day each
× 12 reads = ~2.4k Firestore reads/day total. Firestore free tier is
50k reads/day. Headroom is ~20×.

## When this bites

- Active-user growth → linear cost increase.
- Mobile data + battery: 240 docs (~50-100 KB) transferred per "My
  Submissions" open vs ~20 docs (~5-10 KB) for a single-status path.
- Latency: 12 sequential Firestore round-trips. At ~100ms each that's
  ~1.2s server-side per call. Visible UI lag.

## Two-phase fix

### Phase 1 — parallelise the outer loop (4× latency win, 0× cost win)

Replace the sequential `for (String s : statuses)` with
`CompletableFuture.allOf(...)` so all 4 statuses run in parallel against
Firestore's connection pool. Sub-queries inside each `getSubmissionsMixed`
remain sequential per status.

  Sequential: 4 × 3 × ~100ms = ~1.2s
  Phase 1:    max(3 × ~100ms) = ~300ms

Still 12 reads, but observed latency cut 4×.

### Phase 2 — collapse statuses via Firestore `whereIn` (3× cost win)

Add `ApprovalRepository.findXBySubmitterAndStatusIn(submittedBy,
List<String> statuses, pageSize, cursor)` for X ∈ {channels, playlists,
videos}. Use Firestore's `whereIn('status', statuses)` (max 30 values per
clause; 4 values fits).

  Phase 1+2:  3 reads total (one per type, scanning all 4 statuses)
              ≈ 3 × ~100ms = ~300ms

Same latency as Phase 1, but Firestore cost drops from 12 to 3 reads per
HTTP call.

## Testing requirements

- Unit test on `ApprovalRepository.findChannelsBySubmitterAndStatusIn`
  covering all 4 statuses present.
- Integration test (Firestore emulator) verifying the merged response is
  sorted by `submittedAt desc` across statuses, trimmed to `pageSize`.
- Re-run the 7-stage review pipeline focused on the changed file set.

## Why deferred

- Practical urgency is low at current scale (20× headroom in Firestore
  free tier).
- The fix touches shared repository code; risk of regressing other
  callers without careful test coverage.
- Today's scope is the post-Plan-G review pipeline; this perf issue
  pre-dates that range (added in commit c31284cb, the Plan E hardening
  + UI consistency review pass, before bloat audit).

## When to prioritise

- Pre-release → public release: bump to P1, must-fix.
- Or when active users × calls/day approaches 5k/day (~10% of free
  tier).
- Or when "My Submissions" perceived latency complaints arrive.
