# SyncRepository Archive-Echo Through ArchiveProjector Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Project write-path response payloads (PUT/DELETE in `SyncController`) through `ArchiveProjector` so a client writing a row backing an archived parent learns immediately, not on the next pull cycle.

**Architecture:** Today `ArchiveProjector` runs only on the PULL path. PUT/DELETE responses echo the raw Firestore row. Result: client PUTs a favourite on an archived channel → server stores it → echoes back live row → client UI shows it as alive until the next pull projects it dead. Fix: run the echo through a new `projectOne(row, EntityType)` dispatch helper before serialising.

**Tech Stack:** Spring Boot 3 (Java 17), Firestore SDK, Kotlin coroutines + Room (Android client), JUnit 5 + Mockito.

**Spec source:** Cubic R7 P1 finding (Plan 0 → HEAD review, 2026-05-15) + Plan D spec §9 D8.

**Ticket prefix:** `SYNC-ECHO-01`. Branch: `feature/SYNC-ECHO-01-archive-echo`. Commit prefix: `[FEAT-SYNC-ECHO-01-Tn]`.

---

## File Structure

| Path | Responsibility | Change type |
|------|----------------|-------------|
| `backend/src/main/java/com/albunyaan/tube/service/sync/ArchiveProjector.java` | Add `projectOne(row, EntityType)` typed dispatcher | Modify |
| `backend/src/main/java/com/albunyaan/tube/service/sync/EntityType.java` | New enum: SUBSCRIPTION / PLAYLIST / FAVORITE | Create |
| `backend/src/main/java/com/albunyaan/tube/controller/SyncController.java` | Project PUT/DELETE response rows before DTO serialise | Modify |
| `backend/src/test/java/com/albunyaan/tube/service/sync/ArchiveProjectorTest.java` | New test cases for `projectOne` dispatcher | Modify |
| `backend/src/test/java/com/albunyaan/tube/controller/SyncControllerArchiveEchoTest.java` | Controller-level: PUT on archived parent returns deleted=true | Create |
| `android/app/src/main/java/com/albunyaan/tube/data/sync/SyncManager.kt` | On PUT/DELETE response.deleted=true, apply tombstone locally | Modify |
| `android/app/src/test/java/com/albunyaan/tube/data/sync/SyncManagerPushEchoTest.kt` | Client-side: dirty=0 + tombstoned after archive echo | Create |
| `docs/superpowers/specs/2026-05-12-plan-d-sync-engine-design.md` | §9 D8 contract: write responses are projection-stable | Modify |

---

## Task 1: Create EntityType enum

**Files:**
- Create: `backend/src/main/java/com/albunyaan/tube/service/sync/EntityType.java`

- [ ] **Step 1: Write the enum file**

```java
package com.albunyaan.tube.service.sync;

/**
 * Typed dispatcher key for {@link ArchiveProjector#projectOne}. Replaces
 * stringly-typed "subscriptions" / "playlists" / "favorites" callers
 * passed pre-SYNC-ECHO-01.
 */
public enum EntityType {
    SUBSCRIPTION,
    PLAYLIST,
    FAVORITE
}
```

- [ ] **Step 2: Commit**

```bash
git add backend/src/main/java/com/albunyaan/tube/service/sync/EntityType.java
git commit -m "[FEAT-SYNC-ECHO-01-T1]: add EntityType enum for projection dispatch"
```

---

## Task 2: Add ArchiveProjector.projectOne dispatcher

**Files:**
- Modify: `backend/src/main/java/com/albunyaan/tube/service/sync/ArchiveProjector.java`
- Test: `backend/src/test/java/com/albunyaan/tube/service/sync/ArchiveProjectorTest.java`

- [ ] **Step 1: Write failing test**

```java
@Test
void projectOne_subscriptionOnArchivedChannel_returnsTombstone() {
    when(channels.isArchivedById("UC1")).thenReturn(true);
    RawRow row = new RawRow("UC1", new HashMap<>(), 100L);

    RawRow out = projector.projectOne(row, EntityType.SUBSCRIPTION);

    assertThat(out.data()).containsEntry("deleted", true);
}

@Test
void projectOne_favoriteOnLiveVideo_passesThrough() {
    when(videos.isArchivedById("v1")).thenReturn(false);
    RawRow row = new RawRow("v1", new HashMap<>(), 100L);

    RawRow out = projector.projectOne(row, EntityType.FAVORITE);

    assertThat(out).isSameAs(row);   // null op
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :backend:test --tests ArchiveProjectorTest.projectOne_subscriptionOnArchivedChannel_returnsTombstone`
Expected: FAIL — `projectOne` method does not exist.

- [ ] **Step 3: Implement projectOne**

Add to `ArchiveProjector`:

```java
public RawRow projectOne(RawRow row, EntityType type) {
    return switch (type) {
        case SUBSCRIPTION -> projectSubscription(row);
        case PLAYLIST     -> projectPlaylist(row);
        case FAVORITE     -> projectFavorite(row);
    };
}
```

- [ ] **Step 4: Run tests to verify pass**

Run: `./gradlew :backend:test --tests ArchiveProjectorTest`
Expected: PASS — 2 new tests + all existing cases.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/albunyaan/tube/service/sync/ArchiveProjector.java \
        backend/src/test/java/com/albunyaan/tube/service/sync/ArchiveProjectorTest.java
git commit -m "[FEAT-SYNC-ECHO-01-T2]: ArchiveProjector.projectOne typed dispatcher"
```

---

## Task 3: SyncController projects write-path responses

**Files:**
- Modify: `backend/src/main/java/com/albunyaan/tube/controller/SyncController.java`
- Create: `backend/src/test/java/com/albunyaan/tube/controller/SyncControllerArchiveEchoTest.java`

- [ ] **Step 1: Write failing integration test**

```java
@Test
void putSubscription_onArchivedChannel_responseHasDeletedTrue() throws Exception {
    String uid = seedUser("u@t", "user");
    seedArchivedChannel("UC-archived");
    stubToken(uid, "user");

    mvc.perform(put("/api/v1/me/subscriptions/UC-archived")
            .header("Authorization", "Bearer fake-token")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"channelUrl":"https://youtube.com/channel/UC-archived",
                 "name":"x","avatarUrl":null,"subscribedAt":0}
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.deleted").value(true));
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :backend:test -Pintegration=true --tests SyncControllerArchiveEchoTest`
Expected: FAIL — response.deleted is currently `false`.

- [ ] **Step 3: Inject ArchiveProjector + project in PUT handlers**

Modify `SyncController` constructor to take `ArchiveProjector projector`. In each `putSubscription` / `putPlaylist` / `putFavorite` handler, after `service.upsert*(uid, dto)` returns the stored DTO, convert to `RawRow`, project, convert back:

```java
// Cubic R7 P1 — project the echo through ArchiveProjector so the client
// learns archive-tombstones immediately instead of on the next pull.
RawRow stored = service.upsertSubscription(uid, body);
RawRow projected = projector.projectOne(stored, EntityType.SUBSCRIPTION);
return ResponseEntity.ok(SubscriptionSyncDto.fromRow(projected));
```

Repeat for playlists, favorites. DELETE handlers already write a tombstone; projection is a null op on tombstones (`deleted=true` already) but we apply it anyway for symmetry.

- [ ] **Step 4: Run tests**

Run: `./gradlew :backend:test -Pintegration=true --tests SyncControllerArchiveEchoTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/albunyaan/tube/controller/SyncController.java \
        backend/src/test/java/com/albunyaan/tube/controller/SyncControllerArchiveEchoTest.java
git commit -m "[FEAT-SYNC-ECHO-01-T3]: SyncController projects write-path responses"
```

---

## Task 4: Android client honours archive-echo on push

**Files:**
- Modify: `android/app/src/main/java/com/albunyaan/tube/data/sync/SyncManager.kt`
- Create: `android/app/src/test/java/com/albunyaan/tube/data/sync/SyncManagerPushEchoTest.kt`

- [ ] **Step 1: Identify the PUT-success handler in SyncManager**

Run: `grep -n "clearDirty\|push.*subs\|onSuccess" android/app/src/main/java/com/albunyaan/tube/data/sync/SyncManager.kt`

- [ ] **Step 2: Write failing client test**

```kotlin
@Test fun pushSubscription_archiveEcho_appliesTombstoneLocally() = runTest {
    db.subscribedChannelDao().upsert(
        SubscribedChannel("UC-arch", "u", "n", null, user_id = "uid", dirty = 1))
    // Server projects the echo to deleted=true.
    api.putSubscriptionResponse = {
        Response.success(SubscriptionSyncDto("UC-arch", true, 500L, "", "", null, 0L))
    }

    sm.pushDirty("uid")

    assertEquals(0, db.subscribedChannelDao().count("uid"))   // tombstoned
    // No dirty rows remain — push has applied the tombstone.
    assertEquals(0, db.subscribedChannelDao().countDirty("uid"))
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :app:test -Pandroid.testOptions.unitTests.includes="*SyncManagerPushEcho*"`
Expected: FAIL — current onSuccess always calls `clearDirty`, never `applyTombstone`.

- [ ] **Step 4: Branch on response.deleted in the push success handler**

In `SyncManager.pushDirtyLocked`, inside the per-row push loop's `onSuccess = { resp -> ... }`, replace the unconditional `subs.clearDirty(uid, row.id, resp.updatedAt)` with:

```kotlin
onSuccess = { resp ->
    if (resp.deleted) {
        // Cubic R7 P1 — archive-echo: server projection says this row
        // is virtually tombstoned. Apply locally so the UI does not
        // show a row already dead at the source.
        subs.applyTombstone(uid, row.id, resp.updatedAt)
    } else {
        subs.clearDirty(uid, row.id, resp.updatedAt)
    }
}
```

Repeat for the playlists and favorites loops with their respective DAOs.

- [ ] **Step 5: Run tests**

Run: `./gradlew :app:test -Pandroid.testOptions.unitTests.includes="*SyncManagerPushEcho*"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/com/albunyaan/tube/data/sync/SyncManager.kt \
        android/app/src/test/java/com/albunyaan/tube/data/sync/SyncManagerPushEchoTest.kt
git commit -m "[FEAT-SYNC-ECHO-01-T4]: client tombstones rows on archive-echo response"
```

---

## Task 5: Update Plan D spec §9 D8

**Files:**
- Modify: `docs/superpowers/specs/2026-05-12-plan-d-sync-engine-design.md`

- [ ] **Step 1: Add contract paragraph**

After the existing PUT/DELETE description:

```markdown
**D8.b (R7 P1, SYNC-ECHO-01):** PUT/PATCH/DELETE responses are projection-stable. Clients can rely on the response body being equivalent to an immediately-following GET of the same row. In particular `response.deleted == true` after a PUT means the underlying parent (channel / playlist / video) is archived and the row is a virtual tombstone; clients MUST apply the tombstone locally rather than treating the row as alive.
```

- [ ] **Step 2: Commit**

```bash
git add docs/superpowers/specs/2026-05-12-plan-d-sync-engine-design.md
git commit -m "[DOCS-SYNC-ECHO-01-T5]: spec §9 D8.b — write responses projection-stable"
```

---

## Test plan

- Backend unit: `./gradlew :backend:test --tests ArchiveProjectorTest`
- Backend integration: `./gradlew :backend:test -Pintegration=true --tests SyncControllerArchiveEchoTest`
- Android unit: `./gradlew :app:test -Pandroid.testOptions.unitTests.includes="*SyncManagerPushEcho*"`
- Manual smoke: archive a channel via admin UI → client PUTs subscription → response body shows `deleted: true` → app UI immediately hides the row (no pull cycle needed).

## Self-review checklist

- [x] Each step is one action.
- [x] Code blocks contain the full snippet to add/replace.
- [x] No "TBD" / "similar to" / "implement appropriate" placeholders.
- [x] `EntityType` consistently used across Tasks 1, 2, 3.
- [x] `applyTombstone(uid, id, updatedAt)` matches the DAO signature added in R7 P1 batch 3B.
