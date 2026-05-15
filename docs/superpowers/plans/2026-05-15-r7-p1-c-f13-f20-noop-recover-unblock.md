# F13/F20 Noop Variant for recoverUser / unblockUser Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Return a Boolean transition flag from `recoverUser` and `unblockUser` so post-transaction Firebase Auth side-effects (`setDisabled(false)`, audit log) only fire when the transaction actually transitioned state, matching the F13/F20 pattern already applied to `blockUser` and `softDeleteUser`.

**Architecture:** `blockUser` and `softDeleteUser` return a `Boolean transitioned` from the lifecycle transaction; the caller gates the FB Auth side-effects on it so retries against an already-blocked/deleted user are no-ops. `recoverUser` and `unblockUser` were inadvertently shipped without this pattern (R7 P1 finding) — a retry against an already-active user re-calls `firebaseAuth.setDisabled(false)` and writes a duplicate `USER_RECOVERED` / `USER_UNBLOCKED` audit row. Fix: thread the transition flag through both methods.

**Tech Stack:** Spring Boot 3 (Java 17), Firebase Admin SDK, Firestore transactions, JUnit 5 + Mockito.

**Spec source:** Cubic R7 P1 finding (Plan 0 → HEAD review, 2026-05-15). F13/F20 pattern documented in `docs/superpowers/specs/2026-05-12-plan-f-admin-user-management-design.md`.

**Ticket prefix:** `F-LIFECYCLE-NOOP-01`. Branch: `feature/F-LIFECYCLE-NOOP-01-recover-unblock-idempotent`. Commit prefix: `[FIX-F-LIFECYCLE-NOOP-01-Tn]`.

---

## File Structure

| Path | Responsibility | Change type |
|------|----------------|-------------|
| `backend/src/main/java/com/albunyaan/tube/service/AuthService.java` | `recoverUser`, `unblockUser` — return transition flag; gate side-effects | Modify |
| `backend/src/test/java/com/albunyaan/tube/service/AuthServiceTest.java` | New tests for noop path on already-active user | Modify |

---

## Task 1: recoverUser noop variant

**Files:**
- Modify: `backend/src/main/java/com/albunyaan/tube/service/AuthService.java` (lines around 625)
- Test: `backend/src/test/java/com/albunyaan/tube/service/AuthServiceTest.java`

- [ ] **Step 1: Write failing test for noop path**

```java
@Test
void recoverUser_onAlreadyActiveTarget_doesNotReCallSetDisabledOrAuditAgain() throws Exception {
    String uid = "u1";
    User active = new User(uid, "e@t", null, "user");
    active.setDeleted(false);
    when(userRepository.findByUidUncached(uid)).thenReturn(Optional.of(active));
    when(firestore.runTransaction(any())).thenAnswer(inv -> {
        // The transaction returns Boolean.FALSE because no transition.
        return CompletableFuture.completedFuture(Boolean.FALSE);
    });

    authService.recoverUser(uid, "admin-uid");

    verify(firebaseAuth, never()).updateUser(any());
    verify(auditLogService, never()).log(eq("USER_RECOVERED"), any(), any(), any(), anyMap());
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :backend:test --tests AuthServiceTest.recoverUser_onAlreadyActiveTarget_doesNotReCallSetDisabledOrAuditAgain`
Expected: FAIL — current `recoverUser` always calls `firebaseAuth.updateUser` and audits.

- [ ] **Step 3: Read the current recoverUser implementation**

Run: `sed -n '625,675p' backend/src/main/java/com/albunyaan/tube/service/AuthService.java`

- [ ] **Step 4: Convert the transaction to return Boolean transitioned**

Replace the existing `recoverUser` body. The lifecycle tx must:

```java
public void recoverUser(String uid, String actorUid) throws Exception {
    Boolean transitioned = runLifecycleTx(tx -> {
        DocumentReference userRef = firestore.collection("users").document(uid);
        DocumentSnapshot snap = tx.get(userRef).get(timeoutProperties.getWrite(), TimeUnit.SECONDS);
        if (!snap.exists()) {
            throw new UserNotFoundException(uid);
        }
        User target = snap.toObject(User.class);

        // F13 (R7 P1): idempotent — if target is already active, no-op.
        // Pre-fix a retry after a partial failure (tx commits, FB Auth fails,
        // admin retries) re-ran the active-state write and emitted a second
        // USER_RECOVERED audit row, producing a duplicate timeline event.
        if (!target.isDeleted()) {
            return false;
        }

        target.setDeleted(false);
        target.setDeleteReason(null);
        target.setDeletedAt(null);
        target.setUpdatedAt(Timestamp.now());
        tx.set(userRef, target);

        AuditLog audit = auditLogService.buildEntry(
            "USER_RECOVERED", "user", uid,
            new FirebaseUserDetails(actorUid, null, "admin"),
            Map.of("previousStatus", "deleted"));
        auditLogRepository.saveInTransaction(tx, audit);
        return true;
    });

    try {
        // F20: gate the FB Auth side-effect on the tx transition flag.
        // setDisabled(false) has no visible side-effect when already false,
        // but skipping it cuts an unnecessary FB Auth round-trip on retries.
        if (Boolean.TRUE.equals(transitioned)) {
            firebaseAuth.updateUser(UserRecord.UpdateRequest.builder()
                .setUid(uid).setDisabled(false).build());
        }
    } finally {
        evictUserStatus(uid);
    }

    logger.info("Recovered user uid={} actor={} transitioned={}",
        uid, actorUid, transitioned);
}
```

(Match the surrounding helper signatures: `runLifecycleTx`, `auditLogService.buildEntry`, etc. — verify by reading the file before editing.)

- [ ] **Step 5: Run tests**

Run: `./gradlew :backend:test --tests AuthServiceTest`
Expected: PASS — new test passes; existing `recoverUser_transitionsDeletedToActive` still passes (the transition path is unchanged).

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/albunyaan/tube/service/AuthService.java \
        backend/src/test/java/com/albunyaan/tube/service/AuthServiceTest.java
git commit -m "[FIX-F-LIFECYCLE-NOOP-01-T1]: recoverUser F13/F20 idempotent noop"
```

---

## Task 2: unblockUser noop variant

**Files:**
- Modify: `backend/src/main/java/com/albunyaan/tube/service/AuthService.java` (lines around 829)
- Test: `backend/src/test/java/com/albunyaan/tube/service/AuthServiceTest.java`

- [ ] **Step 1: Write failing test for noop path**

```java
@Test
void unblockUser_onAlreadyActiveTarget_doesNotReCallSetDisabledOrAuditAgain() throws Exception {
    String uid = "u1";
    User active = new User(uid, "e@t", null, "user");
    active.setBlocked(false);
    when(userRepository.findByUidUncached(uid)).thenReturn(Optional.of(active));
    when(firestore.runTransaction(any())).thenAnswer(inv ->
        CompletableFuture.completedFuture(Boolean.FALSE));

    authService.unblockUser(uid, "admin-uid");

    verify(firebaseAuth, never()).updateUser(any());
    verify(auditLogService, never()).log(eq("USER_UNBLOCKED"), any(), any(), any(), anyMap());
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :backend:test --tests AuthServiceTest.unblockUser_onAlreadyActiveTarget_doesNotReCallSetDisabledOrAuditAgain`
Expected: FAIL.

- [ ] **Step 3: Read the current unblockUser implementation**

Run: `sed -n '829,890p' backend/src/main/java/com/albunyaan/tube/service/AuthService.java`

- [ ] **Step 4: Convert unblockUser to return Boolean transitioned**

Mirror the `recoverUser` pattern:

```java
public void unblockUser(String uid, String actorUid) throws Exception {
    Boolean transitioned = runLifecycleTx(tx -> {
        DocumentReference userRef = firestore.collection("users").document(uid);
        DocumentSnapshot snap = tx.get(userRef).get(timeoutProperties.getWrite(), TimeUnit.SECONDS);
        if (!snap.exists()) {
            throw new UserNotFoundException(uid);
        }
        User target = snap.toObject(User.class);

        // F13 (R7 P1): idempotent — if target is already not blocked, no-op.
        // Pre-fix a retry against an already-unblocked target re-called
        // setDisabled(false) and wrote a duplicate USER_UNBLOCKED audit row.
        if (!target.isBlocked()) {
            return false;
        }

        target.setBlocked(false);
        target.setBlockedAt(null);
        target.setBlockReason(null);
        target.setUpdatedAt(Timestamp.now());
        tx.set(userRef, target);

        AuditLog audit = auditLogService.buildEntry(
            "USER_UNBLOCKED", "user", uid,
            new FirebaseUserDetails(actorUid, null, "admin"),
            Map.of("previousStatus", "blocked"));
        auditLogRepository.saveInTransaction(tx, audit);
        return true;
    });

    try {
        if (Boolean.TRUE.equals(transitioned)) {
            firebaseAuth.updateUser(UserRecord.UpdateRequest.builder()
                .setUid(uid).setDisabled(false).build());
        }
    } finally {
        evictUserStatus(uid);
    }

    logger.info("Unblocked user uid={} actor={} transitioned={}",
        uid, actorUid, transitioned);
}
```

- [ ] **Step 5: Run tests**

Run: `./gradlew :backend:test --tests AuthServiceTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/albunyaan/tube/service/AuthService.java \
        backend/src/test/java/com/albunyaan/tube/service/AuthServiceTest.java
git commit -m "[FIX-F-LIFECYCLE-NOOP-01-T2]: unblockUser F13/F20 idempotent noop"
```

---

## Task 3: Integration coverage

**Files:**
- Modify: `backend/src/test/java/com/albunyaan/tube/integration/UserControllerLifecycleIntegrationTest.java`

- [ ] **Step 1: Add integration test for double-recover**

```java
@Test
void doubleRecover_emitsOnlyOneAuditRow() throws Exception {
    String adminUid = seedUser("admin@t", "admin");
    String targetUid = seedDeletedUser("target@t");
    stubToken(adminUid, "admin");

    // First recover — transitions, audits.
    mvc.perform(post("/api/admin/users/" + targetUid + "/recover")
            .header("Authorization", "Bearer fake-token"))
        .andExpect(status().isOk());

    // Second recover — should no-op (no second audit row).
    mvc.perform(post("/api/admin/users/" + targetUid + "/recover")
            .header("Authorization", "Bearer fake-token"))
        .andExpect(status().isOk());

    long recoveredAudits = firestore.collection("audit_logs")
        .whereEqualTo("action", "USER_RECOVERED")
        .whereEqualTo("targetUid", targetUid)
        .count().get().get().getCount();
    assertThat(recoveredAudits).isEqualTo(1L);
}
```

- [ ] **Step 2: Add symmetric test for double-unblock**

```java
@Test
void doubleUnblock_emitsOnlyOneAuditRow() throws Exception {
    String adminUid = seedUser("admin@t", "admin");
    String targetUid = seedBlockedUser("target@t");
    stubToken(adminUid, "admin");

    mvc.perform(post("/api/admin/users/" + targetUid + "/unblock")
            .header("Authorization", "Bearer fake-token"))
        .andExpect(status().isOk());

    mvc.perform(post("/api/admin/users/" + targetUid + "/unblock")
            .header("Authorization", "Bearer fake-token"))
        .andExpect(status().isOk());

    long unblockedAudits = firestore.collection("audit_logs")
        .whereEqualTo("action", "USER_UNBLOCKED")
        .whereEqualTo("targetUid", targetUid)
        .count().get().get().getCount();
    assertThat(unblockedAudits).isEqualTo(1L);
}
```

- [ ] **Step 3: Run integration suite (requires Firebase emulator)**

Run: `./gradlew :backend:test -Pintegration=true --tests UserControllerLifecycleIntegrationTest`
Expected: PASS — both new tests + all existing cases.

- [ ] **Step 4: Commit**

```bash
git add backend/src/test/java/com/albunyaan/tube/integration/UserControllerLifecycleIntegrationTest.java
git commit -m "[TEST-F-LIFECYCLE-NOOP-01-T3]: integration coverage for double-recover/unblock noop"
```

---

## Test plan

- Backend unit: `./gradlew :backend:test --tests AuthServiceTest` — 2 new tests + all existing.
- Backend integration (Firebase emulator): `./gradlew :backend:test -Pintegration=true --tests UserControllerLifecycleIntegrationTest`.
- Manual smoke: in admin UI, click "Recover" twice on a deleted user → audit log shows one entry, not two; click "Unblock" twice on a blocked user → same.

## Self-review checklist

- [x] Each step is one action.
- [x] Code blocks contain the full method body.
- [x] No "TBD" / "implement appropriate" placeholders.
- [x] `runLifecycleTx` helper signature consistent with the existing `softDeleteUser` / `blockUser` pattern documented in Plan F spec.
- [x] Integration tests verify the *audit row count*, not just the response code — the symptom of the bug is duplicate audit rows.
