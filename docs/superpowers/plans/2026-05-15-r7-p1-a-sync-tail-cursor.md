# SyncService Partial-Page Tail Cursor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Server-mint a `nextCursor` for partial-tail sync pages so the Android client advances past a partial last page instead of re-fetching it under continuous-write conditions.

**Architecture:** Backend `SyncService` currently sets `SyncPageDto.nextCursor = null` when `items.size() < pageSize`. Combined with the R5 P0 client-side change that removed local cursor-max computation, this means the client never advances past a partial tail. Fix: server mints a cursor from the LAST row of every non-empty page (full or partial); `nextCursor == null` becomes "iterator empty" only.

**Tech Stack:** Spring Boot 3 (Java 17), Firestore SDK, Kotlin coroutines + Room (Android client), JUnit 5 + Mockito.

**Spec source:** Cubic R7 P1 finding (Plan 0 → HEAD review, 2026-05-15).

**Ticket prefix:** `SYNC-TAIL-01`. Branch: `feature/SYNC-TAIL-01-partial-tail-cursor`. Commit prefix: `[FEAT-SYNC-TAIL-01-Tn]` / `[TEST-SYNC-TAIL-01-Tn]`.

---

## File Structure

| Path | Responsibility | Change type |
|------|----------------|-------------|
| `backend/src/main/java/com/albunyaan/tube/service/sync/SyncService.java` | Page assembly — mint tail cursor for every non-empty page | Modify |
| `backend/src/main/java/com/albunyaan/tube/dto/sync/SyncPageDto.java` | Page wire DTO — update Javadoc on `nextCursor` semantics | Modify (Javadoc only) |
| `backend/src/test/java/com/albunyaan/tube/service/sync/SyncServiceTest.java` | Backend unit coverage of partial-page cursor minting | Modify (add 3 tests) |
| `android/app/src/test/java/com/albunyaan/tube/data/sync/SyncManagerPullAllTest.kt` | Client coverage of resume from partial-page cursor | Modify (add 1 test) |
| `docs/superpowers/specs/2026-05-12-plan-d-sync-engine-design.md` | Spec §9 D8 — document new contract | Modify (1 paragraph) |

---

## Task 1: Backend tail-cursor minting

**Files:**
- Modify: `backend/src/main/java/com/albunyaan/tube/service/sync/SyncService.java`
- Test: `backend/src/test/java/com/albunyaan/tube/service/sync/SyncServiceTest.java`

- [ ] **Step 1: Write the failing test for partial-page cursor**

```java
@Test
void partialPage_subscriptions_mintsCursorFromLastRow() throws Exception {
    // pageSize=50, repository returns 3 rows (partial tail)
    String uid = "u1";
    List<SyncRepository.RawRow> rows = List.of(
        rawSub("c1", 100L), rawSub("c2", 200L), rawSub("c3", 300L));
    when(syncRepository.findAfter(eq(uid), eq("subscriptions"), isNull(), isNull(), eq(50)))
        .thenReturn(rows);
    when(channels.archivedIdsAmong(anyList())).thenReturn(Set.of());

    SyncResponseDto resp = service.pull(uid, null, null, null, null, null, null, 50);

    assertThat(resp.subscriptions().items()).hasSize(3);
    assertThat(resp.subscriptions().nextCursor()).isEqualTo(300L);
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :backend:test --tests SyncServiceTest.partialPage_subscriptions_mintsCursorFromLastRow`
Expected: FAIL — `resp.subscriptions().nextCursor()` is `null` (current behaviour).

- [ ] **Step 3: Implement minimal cursor-from-last-row logic**

Locate the `buildPage(...)` helper or inline page assembly in `SyncService.pull`. Pre-fix:

```java
Long nextCursor = rows.size() == pageSize ? rows.get(rows.size() - 1).updatedAt() : null;
```

Post-fix:

```java
// Cubic R7 P1 — mint cursor for every non-empty page, partial or full.
// `nextCursor == null` now means "iterator empty"; clients keep pulling
// until they see null. Pre-fix a partial-tail page returned null and the
// client (R5 P0 onwards) never advanced past it.
Long nextCursor = rows.isEmpty() ? null : rows.get(rows.size() - 1).updatedAt();
```

Repeat for the playlists and favorites branches in the same method.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :backend:test --tests SyncServiceTest.partialPage_subscriptions_mintsCursorFromLastRow`
Expected: PASS.

- [ ] **Step 5: Add coverage for empty-page and full-page edge cases**

```java
@Test
void emptyPage_returnsNullCursor() throws Exception {
    when(syncRepository.findAfter(eq("u1"), eq("subscriptions"), isNull(), isNull(), eq(50)))
        .thenReturn(List.of());
    when(channels.archivedIdsAmong(anyList())).thenReturn(Set.of());

    SyncResponseDto resp = service.pull("u1", null, null, null, null, null, null, 50);

    assertThat(resp.subscriptions().items()).isEmpty();
    assertThat(resp.subscriptions().nextCursor()).isNull();
}

@Test
void fullPage_mintsCursorAsBefore() throws Exception {
    List<SyncRepository.RawRow> rows = new ArrayList<>();
    for (int i = 0; i < 50; i++) rows.add(rawSub("c" + i, 100L + i));
    when(syncRepository.findAfter(eq("u1"), eq("subscriptions"), isNull(), isNull(), eq(50)))
        .thenReturn(rows);
    when(channels.archivedIdsAmong(anyList())).thenReturn(Set.of());

    SyncResponseDto resp = service.pull("u1", null, null, null, null, null, null, 50);

    assertThat(resp.subscriptions().nextCursor()).isEqualTo(149L);
}
```

- [ ] **Step 6: Run all SyncServiceTest cases**

Run: `./gradlew :backend:test --tests SyncServiceTest`
Expected: PASS — including pre-existing tests; the change is additive (partial pages used to return null, now return a cursor; full pages unchanged; empty pages still null).

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/albunyaan/tube/service/sync/SyncService.java \
        backend/src/test/java/com/albunyaan/tube/service/sync/SyncServiceTest.java
git commit -m "[FEAT-SYNC-TAIL-01-T1]: server mints cursor for partial-tail sync pages"
```

---

## Task 2: Update SyncPageDto Javadoc

**Files:**
- Modify: `backend/src/main/java/com/albunyaan/tube/dto/sync/SyncPageDto.java`

- [ ] **Step 1: Locate the `nextCursor` field comment**

Run: `grep -n "nextCursor" backend/src/main/java/com/albunyaan/tube/dto/sync/SyncPageDto.java`

- [ ] **Step 2: Replace the comment**

Pre-fix (probable shape):

```java
/** Cursor for next page, or null if this was the last page. */
Long nextCursor
```

Post-fix:

```java
/**
 * Cursor pointing past the last row in {@code items}, or null iff the
 * underlying iterator returned zero rows for this query. After cubic
 * R7 P1 (SYNC-TAIL-01) the server mints a cursor for every non-empty
 * page — partial tails included — so clients must keep pulling until
 * they observe a page with {@code items.isEmpty() && nextCursor == null}.
 */
Long nextCursor
```

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/albunyaan/tube/dto/sync/SyncPageDto.java
git commit -m "[DOCS-SYNC-TAIL-01-T2]: clarify SyncPageDto.nextCursor semantics"
```

---

## Task 3: Android client regression test

**Files:**
- Modify: `android/app/src/test/java/com/albunyaan/tube/data/sync/SyncManagerPullAllTest.kt` (or `PullAllTest.kt` — verify path)

- [ ] **Step 1: Identify the existing PullAllTest path**

Run: `find android/app/src/test -name "PullAllTest*.kt" -o -name "SyncManagerPullAllTest*.kt"`
Expected: `android/app/src/test/java/com/albunyaan/tube/data/sync/PullAllTest.kt` exists.

- [ ] **Step 2: Add the partial-tail resume test**

```kotlin
@Test fun partialTail_advancesCursorViaServerMint() = runTest {
    val first = SubscriptionSyncDto("UC1", false, 100L, "u", "n", null, 0L)
    val second = SubscriptionSyncDto("UC2", false, 200L, "u", "n", null, 0L)
    // Server returns 1 row (partial) WITH a cursor pointing past it.
    // Pre-SYNC-TAIL-01 the server returned cursor=null for partial pages
    // and the client would never advance past the first page.
    var call = 0
    api.pullResponse = {
        call++
        if (call == 1) Response.success(
            SyncResponseDto(SyncPageDto(listOf(first), 100L),
                            SyncPageDto(emptyList(), null),
                            SyncPageDto(emptyList(), null)))
        else Response.success(
            SyncResponseDto(SyncPageDto(listOf(second), null),
                            SyncPageDto(emptyList(), null),
                            SyncPageDto(emptyList(), null)))
    }

    sm.pullAll("uid")

    assertEquals(2, db.subscribedChannelDao().count("uid"))
    assertEquals(200L, db.syncStateDao().cursorFor("uid", "subscriptions"))
}
```

- [ ] **Step 3: Run the new test**

Run: `./gradlew :app:test -Pandroid.testOptions.unitTests.includes="*PullAllTest*"`
Expected: PASS — Task 1's server fix together with the unchanged client cursor-advance logic produces the right behaviour.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/test/java/com/albunyaan/tube/data/sync/PullAllTest.kt
git commit -m "[TEST-SYNC-TAIL-01-T3]: cover partial-tail cursor advance"
```

---

## Task 4: Update Plan D spec §9 D8

**Files:**
- Modify: `docs/superpowers/specs/2026-05-12-plan-d-sync-engine-design.md`

- [ ] **Step 1: Locate §9 D8**

Run: `grep -n "D8\|partial.*page\|nextCursor" docs/superpowers/specs/2026-05-12-plan-d-sync-engine-design.md | head`

- [ ] **Step 2: Add or amend the cursor-semantics paragraph**

Insert after the existing pagination description:

```markdown
**D8 (revised, R7 P1):** `SyncPageDto.nextCursor` is non-null iff the page contains at least one row. Clients MUST advance their stored cursor on every non-null `nextCursor` and keep pulling until they observe `items.isEmpty() && nextCursor == null`. Prior to SYNC-TAIL-01 a partial-tail page returned null, causing clients to stall on the last page under continuous-write conditions.
```

- [ ] **Step 3: Commit**

```bash
git add docs/superpowers/specs/2026-05-12-plan-d-sync-engine-design.md
git commit -m "[DOCS-SYNC-TAIL-01-T4]: spec §9 D8 partial-tail cursor contract"
```

---

## Test plan

- Backend unit: `./gradlew :backend:test --tests SyncServiceTest` — 3 new cases pass; all existing cases unchanged.
- Backend integration (Firebase emulator): `./gradlew :backend:test -Pintegration=true --tests SyncControllerIntegrationTest` — exercise full sync flow against the emulator; assert second `/sync` call honours the partial-page cursor.
- Android unit: `./gradlew :app:test -Pandroid.testOptions.unitTests.includes="*PullAllTest*"` — new test passes; pre-existing failures (R5 P0 cursor-source mismatch) remain known regressions on their own ticket.
- Manual smoke: write 51 favourite rows with `pageSize=50`; observe two pull cycles, second starts from row 51 (`updatedAt` of row 50).

## Self-review checklist

- [x] Each step is one action.
- [x] Code blocks contain the full snippet to add/replace.
- [x] No "TBD" / "similar to" / "implement appropriate" placeholders.
- [x] Method signature `SyncResponseDto pull(...)` consistent between Tasks 1 and 3.
- [x] Spec amendment matches the wire contract.
