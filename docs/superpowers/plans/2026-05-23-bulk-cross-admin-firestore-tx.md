# Plan: Close BULK-01 cross-admin / cross-pod dedupe race

**Date raised:** 2026-05-23 (post-Plan-G re-audit, 7-stage pipeline Stage 3 finding #2)
**Status:** Deferred from immediate patch round. Intra-batch case closed in `f8574050`.
**Severity:** P0 by Stage 1 + Stage 3 reviewers; deferred-with-plan per user decision.

## The race

Two admins (or two pods of the backend) POST `/api/admin/registry/bulk/submit` with
overlapping `youtubeId`s within the same second. Both requests do:

```
lateBatch.findExisting(type, youtubeId)   →  empty (no prior write)
writer.writeChannel/Playlist/Video(...)   →  Firestore doc written
```

Neither sees the other's in-flight write because `RegistryDuplicateChecker.Batch`
is a per-request in-memory cache (not shared across requests) and `findExisting`
just calls the Firestore repository's read — which doesn't see another pod's
not-yet-committed write.

Result: two registry docs with the same `(type, youtubeId)`. There is no
Firestore unique constraint on `youtubeId`, so both persist with different doc
IDs. The public feed will show the same channel/playlist/video twice; admins
clicking either copy will see independent metadata trails.

## Why we deferred

Closing this requires running each `writer.writeChannel/Playlist/Video` inside a
**Firestore native transaction** that re-asserts no doc exists with the same
`(type, youtubeId)` at commit time. That's a non-trivial refactor across
`RegistrySubmissionWriter`, `RegistryDuplicateChecker`, and the per-row error
handling, plus needs integration test coverage that simulates concurrent submits.

The intra-batch case (commit `f8574050`) closes 90% of the practical exposure
because:
1. Most overlapping submits happen within ONE moderator's bulk batch (a moderator
   accidentally pastes the same URL twice).
2. The current admin/moderator pool is small (<20 users pre-release), so two
   different admins racing the same URL is rare.
3. The existing per-batch dedupe + late re-check narrows the window from
   minutes (preview → submit) down to milliseconds (read → write inside one row).

## The fix when prioritized

```java
// In RegistrySubmissionWriter.writeChannel (etc.):
return firestore.runTransaction(tx -> {
    // Re-check inside the tx — Firestore serializes the snapshot.
    var existing = channelRepository.findByYoutubeIdInTransaction(tx, meta.youtubeId());
    if (existing.isPresent() && !"REJECTED".equals(existing.get().getStatus())) {
        throw new DuplicateContentException(...);
    }
    Channel c = buildChannel(meta, ...);
    tx.set(channelRepository.docFor(c), c);
    return c.getId();
}).get();
```

Then `BulkSubmissionService.submit` catches `DuplicateContentException` and
records the row as `FAILED / DUPLICATE` (same code path as the per-batch
dedupe). The interface change ripples through all three `write*` methods plus
their tests.

## Testing requirements

- Integration test with Firestore emulator that fires two concurrent
  `/bulk/submit` calls for the same `youtubeId` and verifies exactly one
  Firestore doc lands.
- Unit test that the transaction body rejects on re-check.
- Re-run the 7-stage review pipeline focused on the writer + service after the
  change.

## Out of scope for this plan

- Firestore unique-constraint enforcement at the schema level (Firestore
  doesn't support unique indexes natively; native transactions are the
  canonical workaround).
- Sharded counter / leader-election style coordination — overkill for the
  expected concurrent-admin volume.
- Cross-region replication concerns — current deployment is single-region.
