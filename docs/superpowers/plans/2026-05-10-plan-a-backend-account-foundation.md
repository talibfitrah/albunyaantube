# Plan A — Backend Account Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `USER` role and a typed account lifecycle (status, soft-delete, block, recover, last-admin guard, audit logging, status enforcement in the auth filter) to the backend without exposing any new client-facing API surface — so Plans B–F can build on a hardened foundation.

**Architecture:** Introduce typed `Role` and `UserStatus` enums while keeping the Firestore wire format as lowercase strings (zero data migration). Extend `User` with lifecycle fields (`blockedAt/By/Reason`, `deletedAt/By/Reason`, `recoveredAt/By`, `profileCompletedAt`). Convert `AuthService.deleteUser` from hard-delete to soft-delete; add `blockUser`, `unblockUser`, `recoverUser`, `updateUserRoleAsActor`. Each lifecycle method runs as a single `firestore.runTransaction` that reads the admin count when the target is an admin, mutates the user doc, and writes the audit-log entry — last-admin protection and audit consistency are atomic. Firebase Auth `setDisabled(true)` and `revokeRefreshTokens` run **after** the transaction commits (idempotent). `UserRepository.findByUid` is `@Cacheable("userStatus")` (Caffeine, 60s TTL); lifecycle methods evict on commit. Enforce `BLOCKED` and `DELETED` server-side in `FirebaseAuthFilter` via the cached read. `GET /api/admin/users` filters `status="deleted"` by default with an `?includeDeleted=true` opt-in. The backfill migration runs through `POST /api/admin/migrations/user-backfill` (admin-only, feature-flagged), normalizing missing `status` / `createdAt` / `updatedAt` on legacy user docs. **No new API endpoints are exposed to non-admin clients** in this plan; admin-only `/api/admin/users/{uid}/{block,unblock,recover}` and `/api/admin/migrations/user-backfill` are added under existing admin paths.

**Tech Stack:** Spring Boot 3.5.2 · Java 17 · Firebase Admin SDK · Firestore · JUnit 5 · Firebase Emulator (integration tests via `BaseIntegrationTest`) · Gradle Kotlin DSL.

---

## Wire format decision (read before starting)

Existing Firestore docs and JWT custom claims store role/status as **lowercase strings** (e.g. `role: "admin"`, `status: "active"`). Plan A keeps that wire format unchanged and introduces type-safety only at the Java layer:

- `Role` and `UserStatus` enums use Java-conventional UPPERCASE identifiers (`Role.ADMIN`, `UserStatus.PENDING_PROFILE`).
- `Role.fromString(s)` is **case-insensitive** and tolerates legacy lowercase values from Firestore.
- `Role.getValue()` returns the **lowercase** wire value (`"admin"`).
- `User.java` keeps existing `String getRole()` / `setRole(String)` for Firestore round-trip compatibility, and adds typed `Role getRoleEnum()` / `setRoleEnum(Role)` accessors.

This keeps Firestore rules (`data.role == 'admin'`) and the existing `FirebaseAuthFilter.VALID_ROLES = Set.of("admin", "moderator")` unchanged in case-handling — Plan A only widens the allowlist, never changes its case.

**Status defaults:** existing user docs may have `status: "active"` or no status field. The backfill in Task 12 normalizes both to `"active"` (the default) and adds the missing `createdAt`/`updatedAt` timestamps. New status values introduced in this plan: `"blocked"`, `"deleted"`, `"pending_profile"`.

---

## Spec alignment (D1–D10) — applied 2026-05-10

This plan was drafted before the design spec at `docs/superpowers/specs/2026-05-10-backend-account-foundation-design.md` was finalized. The 10 decisions below are now locked. Where this section conflicts with task bodies later in this document, **this section wins**. Implementers (or sub-agents) MUST apply the canonical lifecycle pattern below instead of the simpler non-transactional patterns shown in Tasks 6, 7, and 8.

| # | Decision |
|---|---|
| D1 | All admin lifecycle endpoints (`block`, `unblock`, `recover`, `softDelete`, role PATCH, GET filter, migration trigger) ship in Plan A. |
| D2 | Last-admin guard reads admin count and writes user state inside the same `firestore.runTransaction` block. The previous `countActiveAdmins()` + save pattern is replaced. |
| D3 | `GET /api/admin/users` filters `status == "deleted"` by default. `?includeDeleted=true` opts in. |
| D4 | `UserRepository.findByUid(String)` is `@Cacheable("userStatus", key="#uid")` — Caffeine, 60s TTL, max 5,000 entries. Lifecycle methods evict after tx commit. |
| D5 | `AuditLog` writes are inside the same `runTransaction` as the user write. Audit failure rolls back the lifecycle action and returns 503. Existing async log methods are untouched. |
| D6 | Wire format stays lowercase (`role: "admin"`, `status: "active"`). Type safety added at the Java layer only. Zero data migration. |
| D7 | Backfill is HTTP-triggered (`POST /api/admin/migrations/user-backfill`), gated by `app.migrations.user-backfill.enabled=false`. No startup hook. |
| D8 | `DELETE /api/admin/users/{uid}` is soft-delete (was hard-delete). The old hard-delete code path is removed. |
| D9 | `firebaseAuth.updateUser(setDisabled)` and `revokeRefreshTokens` run **outside** the Firestore transaction. Both are idempotent. Status check is server-authoritative; Firebase Auth disable is defense-in-depth. |
| D10 | `UserStatus.PENDING_PROFILE` is stored but the auth filter does NOT block it. Plan E enforces it on the moderator submission path. |

### Canonical lifecycle method (block; others mirror)

Apply this pattern to **every** lifecycle method: `blockUser`, `unblockUser`, `softDeleteUser`, `recoverUser`, `updateUserRoleAsActor`. Replace the simpler patterns in Tasks 6, 7, 8 below.

```java
public void blockUser(String uid, String actorUid, String reason) throws Exception {
    firestore.runTransaction(tx -> {
        // Reads first
        DocumentSnapshot doc = tx.get(usersRef.document(uid)).get();
        if (!doc.exists()) throw new IllegalArgumentException("User not found: " + uid);
        User target = doc.toObject(User.class);

        // Last-admin guard (D2) — inline, transactional
        if (target.isAdmin()) {
            if (uid.equals(actorUid)) {
                throw new LastAdminException("Admins cannot block themselves.");
            }
            QuerySnapshot admins = tx.get(usersRef
                .whereEqualTo("role", "admin")
                .whereEqualTo("status", "active")).get();
            if (admins.size() <= 1) {
                throw new LastAdminException("Cannot block the last active admin.");
            }
        }

        // Mutate
        target.recordBlock(actorUid, reason);

        // Audit inside same tx (D5)
        AuditLog audit = AuditLog.of("USER_BLOCKED", "user", uid, actorUid,
            Map.of("reason", reason));

        tx.set(usersRef.document(uid), target);
        tx.set(auditLogsRef.document(), audit);
        return null;
    }).get();

    // Side effects outside tx (D9)
    firebaseAuth.updateUser(new UserRecord.UpdateRequest(uid).setDisabled(true));
    firebaseAuth.revokeRefreshTokens(uid);

    // Cache eviction (D4)
    cacheManager.getCache("userStatus").evict(uid);
}
```

Action tags per method:
- `USER_BLOCKED` (block), `USER_UNBLOCKED` (unblock)
- `USER_SOFT_DELETED` (softDelete), `USER_RECOVERED` (recover)
- `USER_ROLE_CHANGED` (updateUserRoleAsActor) — details include `fromRole` / `toRole`
- `USER_BACKFILLED` / `USER_BACKFILL_RUN` (migration; per-user + summary)

### Last-admin guard scope

Triggers on:
- `updateUserRoleAsActor` when target was `ADMIN` and `newRole != ADMIN`.
- `blockUser`, `softDeleteUser` when `target.isAdmin()`.
- Self-action on admin: blocked unconditionally (regardless of count).

Does NOT trigger on:
- Promote-to-admin.
- `unblockUser`, `recoverUser` (always safe — increases admin count).
- Lifecycle changes on non-admins.

### Failure semantics

| Failure | Outcome |
|---|---|
| Last-admin guard | `LastAdminException` → 409 `LAST_ADMIN_PROTECTED`. No writes. No Firebase Auth side effects. |
| Firestore tx failure | `ExecutionException` → 503. No partial writes. |
| Audit save inside tx | Whole tx aborts. 503. User state unchanged. |
| Firebase Auth `updateUser` after tx commit | Idempotent — admin re-runs; tx already at desired state, no re-write; Firebase Auth call retries. Filter still rejects via status check. |
| `revokeRefreshTokens` after tx commit | Existing tokens valid until ~1h; filter still rejects via status. Idempotent retry. |
| Cache evict | Stale cache for ≤60s. Acceptable per D4. |

### Cache wiring (D4)

The existing `backend/src/main/java/com/albunyaan/tube/config/CacheConfig.java` constructs a `CaffeineCacheManager` with cache-name varargs and a single shared 1h Caffeine spec. To add `userStatus` with its own 60s TTL (different from the 1h default), use `CaffeineCacheManager.registerCustomCache(...)`:

```java
@Bean
public CacheManager cacheManager() {
    CaffeineCacheManager mgr = new CaffeineCacheManager(
        "youtubeChannelSearch", "youtubePlaylistSearch", "youtubeVideoSearch",
        "userStatus");
    mgr.setCaffeine(Caffeine.newBuilder()
        .expireAfterWrite(1, TimeUnit.HOURS)
        .maximumSize(1_000));
    // Override default for userStatus only
    mgr.registerCustomCache("userStatus",
        Caffeine.newBuilder()
            .expireAfterWrite(60, TimeUnit.SECONDS)
            .maximumSize(5_000)
            .build());
    return mgr;
}
```

`UserRepository.findByUid(String)` gets `@Cacheable("userStatus", key="#uid")`. Each lifecycle method appends, AFTER `runTransaction.get()` returns:

```java
Cache cache = cacheManager.getCache("userStatus");
if (cache != null) cache.evict(uid);  // null-safe in case the bean isn't yet wired
```

`AuthService` adds `CacheManager` as a constructor dependency (Task 5 Step 8 below makes the constructor change). The auth filter calls `userRepository.findByUid(uid)` and transparently benefits from the cache; no explicit cache lookup needed in the filter.

**Spec note:** the design spec uses `userRepository.findById(...)` for readability. The codebase repo method is named `findByUid(...)`; treat every spec reference to `findById` as `findByUid`. No spec amendment required.

### Filter exception handling (Task 5 detail)

`OncePerRequestFilter` exceptions DO NOT reach `@ControllerAdvice` — Spring's exception advice engages only after `DispatcherServlet` accepts the request. A throw from inside the filter produces a generic 500 with no JSON body. The integration tests in Task 5 Step 1 (asserting `$.code == "ACCOUNT_BLOCKED"` / `"ACCOUNT_NOT_FOUND"`) require the filter to write the JSON response body directly via Jackson (NOT manual `String.format` escaping — admin-set `blockReason` may contain `\`, control chars, or unicode escapes that break naive escaping).

Inject `ObjectMapper` into the filter (existing project-wide bean) and add:

```java
private void writeError(HttpServletResponse response, int status,
                        String code, String message) throws IOException {
    writeError(response, status, code, message, Map.of());
}

private void writeError(HttpServletResponse response, int status,
                        String code, String message,
                        Map<String, Object> extra) throws IOException {
    response.setStatus(status);
    response.setContentType("application/json");
    Map<String, Object> body = new HashMap<>(extra);
    body.put("code", code);
    body.put("message", message);
    objectMapper.writeValue(response.getWriter(), body);
}
```

Inside the filter, on the status checks:

```java
if (u.isDeleted()) {
    writeError(response, 401, "ACCOUNT_NOT_FOUND",
        "Your account has been deleted.");
    return;  // do NOT call chain.doFilter
}
if (u.isBlocked()) {
    writeError(response, 403, "ACCOUNT_BLOCKED",
        "Your account is blocked.",
        Map.of("reason", u.getBlockReason() != null ? u.getBlockReason() : "policy-violation"));
    return;
}
```

`AccountBlockedException` / `AccountDeletedException` from Task 4 are still used by `AuthService` lifecycle methods (controllers throw them and `@ControllerAdvice` catches them in the controller path). The filter writes the response directly.

### Filter scope (clarifying note from review)

Plan A does NOT widen `FirebaseAuthFilter.shouldNotFilter`. `/api/v1/*` remains exempt because no current public-API route depends on authenticated user-state — blocking a user provides no privilege change there. **Plan B (Android sync), Plan C (account bootstrap), and Plan D (sync engine) MUST widen `shouldNotFilter` to drop the `/api/v1/*` exemption** when they add user-bound endpoints. Plan A's status check fires on `/api/admin/*` only, which is sufficient for the admin lifecycle ops it defines.

### Deferred / known follow-ups (from round-2 review)

The following items are known and intentionally deferred — track them as follow-up work (not in Plan A):

- **Spec §13 Micrometer metrics** (`albunyaan_admin_lifecycle_total`, `albunyaan_user_status_cache_total`, `albunyaan_last_admin_guard_total`, `albunyaan_admin_lifecycle_duration_seconds`) — not added in Plan A. Suggested follow-up: a small Plan A.1 ticket that adds `MetricsConfig` instrumentation around lifecycle methods and the cache-hit path.
- **Migration stuck-lock TTL** — the lock at `system_settings/migration_user_backfill` has no TTL or heartbeat. JVM crash mid-migration leaves `running=true`. Recovery requires manual Firestore-console deletion of the doc. Acceptable for current scale (single dev SRE, low migration frequency); add a `claimedAt` + `staleAfter > 30 min` heuristic in a future ticket if frequency increases.
- **`whereIn` whitelist fragility** — Task 10 GET filter hard-codes the four live statuses (`active`, `blocked`, `pending_profile`). If Plans C/D/E introduce a fifth status (e.g., `pending_email_verification`), this filter silently excludes it. Add a `UserStatus` lookup-driven query helper to make this self-extending.
- **Public API filter scope** — Plan A keeps `FirebaseAuthFilter.shouldNotFilter` exempting `/api/v1/*`. Plans B (Android sync), C (account bootstrap), D (sync engine) MUST widen the filter when they add user-state-dependent endpoints. Tracked in those plans' decision lists.
- **Backfill async per-user audit** — `USER_BACKFILLED` per-user audits stay async (`logSystem`) and silently swallow Firestore errors. The synchronous `USER_BACKFILL_RUN` summary captures the run-level totals but cannot detect per-user audit gaps. Acceptable per user decision (round 1, item A); spec §9 carves out backfill audit as "best-effort" rather than transactional.

### Concurrent-demote correctness (clarifying note from review)

The Firestore Java Admin SDK's `Transaction.get(Query)` adds matched-document IDs to the read-set. On commit, Firestore's optimistic concurrency check rejects the commit if any read-set document was modified by another transaction. Two simultaneous demote/block transactions targeting different admin docs each read the full active-admin set; the loser's commit fails because the winner mutated a doc in the loser's read-set; the loser retries, sees the new count, and throws `LastAdminException`. **D2's atomicity guarantee holds.** Phantom inserts (a NEW admin doc created during a parallel demote) are NOT in either read-set and may commit — but Plan A's promote-to-admin path is itself transactional and reads the admin set, so the typical concurrent flow is covered. Cross-tx phantom inserts during demote-while-promote are an acceptable operational anomaly (the new admin remains; the demoted admin is demoted; net count is preserved or higher).

### GET filter (D3)

`UserController.listUsers(...)` accepts `@RequestParam(required = false, defaultValue = "false") boolean includeDeleted`. The repository query layer receives the flag and applies a `whereIn("status", List.of("active","blocked","pending_profile"))` predicate when `includeDeleted == false`. The `whereIn` whitelist is preferred over `whereNotEqualTo("status","deleted")` because Firestore `!=` excludes documents where the field is missing or null entirely — pre-backfill legacy docs have `status: null` and would silently disappear from admin listings. Tests cover both paths. Task 10 Step 1b is the canonical implementation.

### Migration HTTP trigger (D7)

`UserController` (or a dedicated `MigrationController`) exposes:

```java
@PostMapping("/api/admin/migrations/user-backfill")
@PreAuthorize("hasRole('ADMIN')")
public ResponseEntity<Map<String, Object>> runUserBackfill() throws Exception {
    if (!backfillEnabled) {
        return ResponseEntity.status(503)
            .body(Map.of("code", "MIGRATION_DISABLED",
                "hint", "Set app.migrations.user-backfill.enabled=true"));
    }
    UserBackfillMigration.RunSummary summary = migration.run();
    return ResponseEntity.ok(Map.of(
        "scanned", summary.scanned(),
        "updated", summary.updated()));
}
```

`UserBackfillMigration.runIfEnabled` is removed; the flag check moves into the controller. The migration class only exposes `run()` returning a `RunSummary` record.

---

## Pre-flight

- [ ] **Step 1: Confirm P0 SA scrub is done.** Run BOTH greps:

  ```bash
  git log --all --oneline -- backend/src/main/resources/firebase-service-account.json
  git log --all --oneline -- '*service-account*.json' '*firebase-key*.json' '*service_account*.json'
  ```

  Expected: empty output from both. If either is non-empty, halt — execute `docs/superpowers/plans/2026-05-10-p0-firebase-sa-scrub.md` first. The second grep catches alternate paths (e.g., a developer accidentally re-introducing the SA at `backend/firebase-key.json` or under `service_account.json`).

- [ ] **Step 2: Confirm baseline tests pass.**

  ```bash
  cd backend && ./gradlew test
  ```

  Expected: build successful. Note any pre-existing failures so they aren't attributed to this plan.

- [ ] **Step 3: Confirm Firebase Emulator works for integration tests.**

  ```bash
  cd backend && ./gradlew test -Pintegration=true --tests "com.albunyaan.tube.integration.RbacWorkflowIntegrationTest"
  ```

  Expected: passes. Plan A relies on the same emulator harness.

---

## File Structure

**New (production):**
- `backend/src/main/java/com/albunyaan/tube/model/Role.java`
- `backend/src/main/java/com/albunyaan/tube/model/UserStatus.java`
- `backend/src/main/java/com/albunyaan/tube/exception/AccountBlockedException.java`
- `backend/src/main/java/com/albunyaan/tube/exception/AccountDeletedException.java`
- `backend/src/main/java/com/albunyaan/tube/exception/LastAdminException.java`
- `backend/src/main/java/com/albunyaan/tube/util/UserBackfillMigration.java`
- `backend/src/main/java/com/albunyaan/tube/controller/MigrationController.java` *(D7 — HTTP trigger for backfill)*

**New (tests):**
- `backend/src/test/java/com/albunyaan/tube/model/RoleTest.java`
- `backend/src/test/java/com/albunyaan/tube/model/UserStatusTest.java`
- `backend/src/test/java/com/albunyaan/tube/service/AuthServiceSoftDeleteTest.java`
- `backend/src/test/java/com/albunyaan/tube/service/AuthServiceBlockTest.java`
- `backend/src/test/java/com/albunyaan/tube/service/AuthServiceLastAdminTest.java`
- `backend/src/test/java/com/albunyaan/tube/integration/AccountStatusFilterIntegrationTest.java`
- `backend/src/test/java/com/albunyaan/tube/util/UserBackfillMigrationTest.java`

**Modified:**
- `backend/src/main/java/com/albunyaan/tube/model/User.java` — lifecycle fields + typed accessors
- `backend/src/main/java/com/albunyaan/tube/security/FirebaseAuthFilter.java` — `"user"` in allowlist, status check via cached `findByUid`, conditional revocation on `/api/admin/*`
- `backend/src/main/java/com/albunyaan/tube/service/AuthService.java` — `softDeleteUser`, `recoverUser`, `blockUser`, `unblockUser`, `updateUserRoleAsActor` — all transactional with inline last-admin guard + sync audit
- `backend/src/main/java/com/albunyaan/tube/service/AuditLogService.java` — `buildBlock`, `buildUnblock`, `buildSoftDelete`, `buildRecover`, `buildRoleChange` builders (writes happen inside `AuthService` transactions; existing async log methods unchanged)
- `backend/src/main/java/com/albunyaan/tube/repository/UserRepository.java` — `@Cacheable("userStatus")` on `findByUid`; `findAll(includeDeleted, ...)` filter
- `backend/src/main/java/com/albunyaan/tube/config/CacheConfig.java` — register `userStatus` Caffeine cache (60s TTL, max 5,000 entries)
- `backend/src/main/java/com/albunyaan/tube/exception/GlobalExceptionHandler.java` — handlers for the 3 new exceptions (`AccountBlockedException` → 403, `AccountDeletedException` → 401, `LastAdminException` → 409)
- `backend/src/main/java/com/albunyaan/tube/controller/UserController.java` — `block`, `unblock`, `recover` endpoints; soft-delete `DELETE`; `GET` `?includeDeleted` filter
- `backend/src/main/resources/firestore.rules` — `isUser()` helper; deny self-block / self-delete patterns
- `backend/src/main/resources/firestore.indexes.json` — composite index on `users.(role, status)`
- `backend/src/main/resources/application.yml` — `app.migrations.user-backfill.enabled=false` flag

---

## Task 1: `Role` enum

**Files:**
- Create: `backend/src/main/java/com/albunyaan/tube/model/Role.java`
- Test: `backend/src/test/java/com/albunyaan/tube/model/RoleTest.java`

- [ ] **Step 1: Write failing tests**

```java
// RoleTest.java
package com.albunyaan.tube.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RoleTest {

    @Test void getValue_isLowercase() {
        assertEquals("user", Role.USER.getValue());
        assertEquals("moderator", Role.MODERATOR.getValue());
        assertEquals("admin", Role.ADMIN.getValue());
    }

    @Test void fromString_caseInsensitive() {
        assertEquals(Role.ADMIN, Role.fromString("admin"));
        assertEquals(Role.ADMIN, Role.fromString("ADMIN"));
        assertEquals(Role.ADMIN, Role.fromString("Admin"));
        assertEquals(Role.MODERATOR, Role.fromString("moderator"));
        assertEquals(Role.USER, Role.fromString("user"));
    }

    @Test void fromString_unknownDefaultsToUser() {
        assertEquals(Role.USER, Role.fromString("god"));
        assertEquals(Role.USER, Role.fromString(""));
        assertEquals(Role.USER, Role.fromString(null));
        assertEquals(Role.USER, Role.fromString("   "));
    }

    @Test void includesEqualOrAbove_orderingMatchesPrivilege() {
        assertTrue(Role.ADMIN.includesEqualOrAbove(Role.MODERATOR));
        assertTrue(Role.ADMIN.includesEqualOrAbove(Role.USER));
        assertTrue(Role.MODERATOR.includesEqualOrAbove(Role.USER));
        assertFalse(Role.USER.includesEqualOrAbove(Role.MODERATOR));
        assertFalse(Role.MODERATOR.includesEqualOrAbove(Role.ADMIN));
    }
}
```

- [ ] **Step 2: Verify the tests fail to compile**

```bash
cd backend && ./gradlew compileTestJava
```

Expected: failure with "cannot find symbol: class Role".

- [ ] **Step 3: Implement `Role`**

```java
// Role.java
package com.albunyaan.tube.model;

import java.util.Locale;

public enum Role {
    USER("user", 0),
    MODERATOR("moderator", 1),
    ADMIN("admin", 2);

    private final String value;
    private final int rank;

    Role(String value, int rank) {
        this.value = value;
        this.rank = rank;
    }

    public String getValue() {
        return value;
    }

    public boolean includesEqualOrAbove(Role other) {
        return this.rank >= other.rank;
    }

    public static Role fromString(String value) {
        if (value == null) return USER;
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return USER;
        return switch (trimmed.toLowerCase(Locale.ROOT)) {
            case "admin" -> ADMIN;
            case "moderator" -> MODERATOR;
            case "user" -> USER;
            default -> USER;
        };
    }
}
```

- [ ] **Step 4: Run tests; expect green**

```bash
cd backend && ./gradlew test --tests "com.albunyaan.tube.model.RoleTest"
```

Expected: 4 tests passing.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/albunyaan/tube/model/Role.java \
        backend/src/test/java/com/albunyaan/tube/model/RoleTest.java
git commit -m "[FEAT]: add typed Role enum with lowercase wire format"
```

---

## Task 2: `UserStatus` enum

**Files:**
- Create: `backend/src/main/java/com/albunyaan/tube/model/UserStatus.java`
- Test: `backend/src/test/java/com/albunyaan/tube/model/UserStatusTest.java`

- [ ] **Step 1: Write failing tests**

```java
package com.albunyaan.tube.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UserStatusTest {

    @Test void getValue_isLowercaseSnake() {
        assertEquals("active", UserStatus.ACTIVE.getValue());
        assertEquals("blocked", UserStatus.BLOCKED.getValue());
        assertEquals("deleted", UserStatus.DELETED.getValue());
        assertEquals("pending_profile", UserStatus.PENDING_PROFILE.getValue());
    }

    @Test void fromString_caseInsensitiveAndTolerantOfHyphens() {
        assertEquals(UserStatus.ACTIVE, UserStatus.fromString("active"));
        assertEquals(UserStatus.BLOCKED, UserStatus.fromString("BLOCKED"));
        assertEquals(UserStatus.PENDING_PROFILE, UserStatus.fromString("pending_profile"));
        assertEquals(UserStatus.PENDING_PROFILE, UserStatus.fromString("pending-profile"));
        assertEquals(UserStatus.PENDING_PROFILE, UserStatus.fromString("PendingProfile"));
    }

    @Test void fromString_unknownDefaultsToActive() {
        assertEquals(UserStatus.ACTIVE, UserStatus.fromString(null));
        assertEquals(UserStatus.ACTIVE, UserStatus.fromString(""));
        assertEquals(UserStatus.ACTIVE, UserStatus.fromString("inactive")); // legacy value
        assertEquals(UserStatus.ACTIVE, UserStatus.fromString("anything-weird"));
    }

    @Test void canTokensFromBlockedOrDeletedAccounts_isFalse() {
        assertTrue(UserStatus.ACTIVE.allowsAuth());
        assertTrue(UserStatus.PENDING_PROFILE.allowsAuth());
        assertFalse(UserStatus.BLOCKED.allowsAuth());
        assertFalse(UserStatus.DELETED.allowsAuth());
    }
}
```

- [ ] **Step 2: Verify failure**

```bash
cd backend && ./gradlew compileTestJava
```

Expected: cannot find symbol UserStatus.

- [ ] **Step 3: Implement**

```java
package com.albunyaan.tube.model;

import java.util.Locale;

public enum UserStatus {
    ACTIVE("active"),
    BLOCKED("blocked"),
    DELETED("deleted"),
    PENDING_PROFILE("pending_profile");

    private final String value;

    UserStatus(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public boolean allowsAuth() {
        return this == ACTIVE || this == PENDING_PROFILE;
    }

    public static UserStatus fromString(String value) {
        if (value == null) return ACTIVE;
        String normalized = value.trim()
            .replaceAll("([a-z])([A-Z])", "$1_$2")  // split camelCase BEFORE lowercasing
            .toLowerCase(Locale.ROOT)
            .replace('-', '_');
        if (normalized.isEmpty()) return ACTIVE;
        return switch (normalized) {
            case "active" -> ACTIVE;
            case "blocked" -> BLOCKED;
            case "deleted" -> DELETED;
            case "pending_profile" -> PENDING_PROFILE;
            default -> ACTIVE;
        };
    }
}
```

- [ ] **Step 4: Run tests**

```bash
cd backend && ./gradlew test --tests "com.albunyaan.tube.model.UserStatusTest"
```

Expected: 4 tests passing.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/albunyaan/tube/model/UserStatus.java \
        backend/src/test/java/com/albunyaan/tube/model/UserStatusTest.java
git commit -m "[FEAT]: add typed UserStatus enum (active|blocked|deleted|pending_profile)"
```

---

## Task 3: Extend `User` model with lifecycle fields

**Files:**
- Modify: `backend/src/main/java/com/albunyaan/tube/model/User.java`
- Test: `backend/src/test/java/com/albunyaan/tube/model/UserModelTest.java` (create)

- [ ] **Step 1: Write failing tests for new fields and helpers**

```java
package com.albunyaan.tube.model;

import com.google.cloud.Timestamp;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UserModelTest {

    @Test void defaultStatusIsActive_andRoleIsModerator_legacyContract() {
        User u = new User();
        assertEquals("active", u.getStatus());
        assertEquals("moderator", u.getRole());
    }

    @Test void typedAccessorsRoundTrip() {
        User u = new User();
        u.setRoleEnum(Role.USER);
        assertEquals("user", u.getRole());
        assertEquals(Role.USER, u.getRoleEnum());

        u.setStatusEnum(UserStatus.BLOCKED);
        assertEquals("blocked", u.getStatus());
        assertEquals(UserStatus.BLOCKED, u.getStatusEnum());
    }

    @Test void blockedAndDeletedFlagsReflectStatus() {
        User u = new User();
        u.setStatusEnum(UserStatus.BLOCKED);
        assertTrue(u.isBlocked());
        assertFalse(u.isDeleted());
        assertFalse(u.isActive());

        u.setStatusEnum(UserStatus.DELETED);
        assertTrue(u.isDeleted());
        assertFalse(u.isBlocked());
    }

    @Test void recordBlock_setsAuditFieldsAndStatus() {
        User u = new User();
        u.recordBlock("admin-uid", "spam");
        assertEquals("blocked", u.getStatus());
        assertEquals("admin-uid", u.getBlockedBy());
        assertEquals("spam", u.getBlockReason());
        assertNotNull(u.getBlockedAt());
    }

    @Test void recordSoftDelete_setsAuditFieldsAndStatus() {
        User u = new User();
        u.recordSoftDelete("admin-uid", "user-request");
        assertEquals("deleted", u.getStatus());
        assertEquals("admin-uid", u.getDeletedBy());
        assertEquals("user-request", u.getDeleteReason());
        assertNotNull(u.getDeletedAt());
    }

    @Test void recordRecover_clearsDeletionAndReactivates() {
        User u = new User();
        u.recordSoftDelete("a", "r");
        u.recordRecover("admin-uid");
        assertEquals("active", u.getStatus());
        assertEquals("admin-uid", u.getRecoveredBy());
        assertNotNull(u.getRecoveredAt());
        assertNull(u.getDeletedAt());
        assertNull(u.getDeletedBy());
        assertNull(u.getDeleteReason());
    }

    @Test void recordUnblock_clearsBlockFieldsAndReactivates() {
        User u = new User();
        u.recordBlock("admin-1", "spam");
        u.recordUnblock("admin-2");
        assertEquals("active", u.getStatus());
        assertNull(u.getBlockedAt());
        assertNull(u.getBlockedBy());
        assertNull(u.getBlockReason());
    }
}
```

- [ ] **Step 2: Run tests; expect failures**

```bash
cd backend && ./gradlew test --tests "com.albunyaan.tube.model.UserModelTest"
```

Expected: compile errors (`setRoleEnum`, `getRoleEnum`, `setStatusEnum`, `getStatusEnum`, `isBlocked`, `isDeleted`, `recordBlock`, `recordSoftDelete`, `recordRecover`, `getBlockedBy` etc. unknown).

- [ ] **Step 3: Edit `User.java`**

Add these private fields just below `private String createdBy;` (after line ~40):

```java
    // Block lifecycle (BACKEND-ACCT-FOUND)
    private Timestamp blockedAt;
    private String blockedBy;
    private String blockReason;

    // Soft-delete lifecycle (BACKEND-ACCT-FOUND)
    private Timestamp deletedAt;
    private String deletedBy;
    private String deleteReason;

    // Recovery lifecycle (BACKEND-ACCT-FOUND)
    private Timestamp recoveredAt;
    private String recoveredBy;

    // Profile completion (Plan C will populate; Plan A only stores)
    private Timestamp profileCompletedAt;
```

Add typed accessors next to existing `getRole`/`getStatus` (preserve the existing String accessors — they are the Firestore round-trip path):

```java
    public Role getRoleEnum() {
        return Role.fromString(role);
    }

    public void setRoleEnum(Role role) {
        this.role = role.getValue();
    }

    public UserStatus getStatusEnum() {
        return UserStatus.fromString(status);
    }

    public void setStatusEnum(UserStatus status) {
        this.status = status.getValue();
    }
```

Replace `isActive()` with the case-insensitive form so it tolerates legacy values:

```java
    public boolean isActive() {
        return UserStatus.ACTIVE == getStatusEnum();
    }

    public boolean isBlocked() {
        return UserStatus.BLOCKED == getStatusEnum();
    }

    public boolean isDeleted() {
        return UserStatus.DELETED == getStatusEnum();
    }

    public boolean isPendingProfile() {
        return UserStatus.PENDING_PROFILE == getStatusEnum();
    }
```

Add lifecycle methods at the bottom of the class (above the closing brace):

```java
    public void recordBlock(String byUid, String reason) {
        this.status = UserStatus.BLOCKED.getValue();
        this.blockedAt = Timestamp.now();
        this.blockedBy = byUid;
        this.blockReason = reason;
        touch();
    }

    // byUid is captured by AuditLogService.buildUnblock (Task 9, D5) — the
    // audit trail lives in the auditLogs collection, not on the User doc.
    // The parameter stays here for API symmetry with recordBlock.
    public void recordUnblock(String byUid) {
        this.status = UserStatus.ACTIVE.getValue();
        this.blockedAt = null;
        this.blockedBy = null;
        this.blockReason = null;
        touch();
    }

    public void recordSoftDelete(String byUid, String reason) {
        this.status = UserStatus.DELETED.getValue();
        this.deletedAt = Timestamp.now();
        this.deletedBy = byUid;
        this.deleteReason = reason;
        touch();
    }

    public void recordRecover(String byUid) {
        this.status = UserStatus.ACTIVE.getValue();
        this.deletedAt = null;
        this.deletedBy = null;
        this.deleteReason = null;
        this.recoveredAt = Timestamp.now();
        this.recoveredBy = byUid;
        touch();
    }
```

Add getters/setters for all new fields (Firestore needs them for serialization):

```java
    public Timestamp getBlockedAt() { return blockedAt; }
    public void setBlockedAt(Timestamp t) { this.blockedAt = t; }
    public String getBlockedBy() { return blockedBy; }
    public void setBlockedBy(String s) { this.blockedBy = s; }
    public String getBlockReason() { return blockReason; }
    public void setBlockReason(String s) { this.blockReason = s; }

    public Timestamp getDeletedAt() { return deletedAt; }
    public void setDeletedAt(Timestamp t) { this.deletedAt = t; }
    public String getDeletedBy() { return deletedBy; }
    public void setDeletedBy(String s) { this.deletedBy = s; }
    public String getDeleteReason() { return deleteReason; }
    public void setDeleteReason(String s) { this.deleteReason = s; }

    public Timestamp getRecoveredAt() { return recoveredAt; }
    public void setRecoveredAt(Timestamp t) { this.recoveredAt = t; }
    public String getRecoveredBy() { return recoveredBy; }
    public void setRecoveredBy(String s) { this.recoveredBy = s; }

    public Timestamp getProfileCompletedAt() { return profileCompletedAt; }
    public void setProfileCompletedAt(Timestamp t) { this.profileCompletedAt = t; }
```

- [ ] **Step 4: Run tests**

```bash
cd backend && ./gradlew test --tests "com.albunyaan.tube.model.UserModelTest"
```

Expected: all tests passing.

- [ ] **Step 5: Run full model test suite to confirm no regression**

```bash
cd backend && ./gradlew test --tests "com.albunyaan.tube.model.*"
```

Expected: green.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/albunyaan/tube/model/User.java \
        backend/src/test/java/com/albunyaan/tube/model/UserModelTest.java
git commit -m "[FEAT]: extend User with block/delete/recover lifecycle + typed accessors"
```

---

## Task 4: New exceptions + `GlobalExceptionHandler` wiring

**Files:**
- Create: `backend/src/main/java/com/albunyaan/tube/exception/AccountBlockedException.java`
- Create: `backend/src/main/java/com/albunyaan/tube/exception/AccountDeletedException.java`
- Create: `backend/src/main/java/com/albunyaan/tube/exception/LastAdminException.java`
- Modify: `backend/src/main/java/com/albunyaan/tube/exception/GlobalExceptionHandler.java`

- [ ] **Step 1: Read the current GlobalExceptionHandler to learn the pattern**

```bash
cd backend && grep -n "@ExceptionHandler\|ResponseEntity" src/main/java/com/albunyaan/tube/exception/GlobalExceptionHandler.java | head -30
```

- [ ] **Step 2: Create the three exceptions**

```java
// AccountBlockedException.java
package com.albunyaan.tube.exception;

public class AccountBlockedException extends RuntimeException {
    private final String uid;
    private final String reason;

    public AccountBlockedException(String uid, String reason) {
        super("Account is blocked: " + uid);
        this.uid = uid;
        this.reason = reason;
    }

    public String getUid() { return uid; }
    public String getReason() { return reason; }
}
```

```java
// AccountDeletedException.java
package com.albunyaan.tube.exception;

public class AccountDeletedException extends RuntimeException {
    private final String uid;

    public AccountDeletedException(String uid) {
        super("Account does not exist: " + uid);
        this.uid = uid;
    }

    public String getUid() { return uid; }
}
```

```java
// LastAdminException.java
package com.albunyaan.tube.exception;

public class LastAdminException extends RuntimeException {
    public LastAdminException(String message) {
        super(message);
    }
}
```

- [ ] **Step 3: Add handlers in `GlobalExceptionHandler`**

Match the existing handler shape (`{timestamp, status, error, message, path}`) used by sibling handlers (e.g., `handleResourceNotFoundException`, `handleInvalidTokenException`). Extend the shape with two new keys: `code` (always) and `reason` (conditional). This preserves backward-compatibility for any existing client that reads `timestamp`/`status`/`error`/`path`, while adding structured `code` for richer client error handling.

```java
    @ExceptionHandler(AccountBlockedException.class)
    public ResponseEntity<Object> handleAccountBlocked(
            AccountBlockedException ex, WebRequest request) {
        logger.warn("Account blocked: uid={}, reason={}", ex.getUid(), ex.getReason());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", LocalDateTime.now());
        body.put("status", HttpStatus.FORBIDDEN.value());
        body.put("error", "Forbidden");
        body.put("code", "ACCOUNT_BLOCKED");
        body.put("message", "This account has been blocked.");
        if (ex.getReason() != null) body.put("reason", ex.getReason());
        body.put("path", request.getDescription(false).replace("uri=", ""));

        return new ResponseEntity<>(body, HttpStatus.FORBIDDEN);
    }

    @ExceptionHandler(AccountDeletedException.class)
    public ResponseEntity<Object> handleAccountDeleted(
            AccountDeletedException ex, WebRequest request) {
        // Log the uid server-side for audit, but DO NOT include it in the
        // response body — exfiltration safety (don't reveal which uid is gone).
        logger.warn("Account not found / deleted: uid={}", ex.getUid());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", LocalDateTime.now());
        body.put("status", HttpStatus.UNAUTHORIZED.value());
        body.put("error", "Unauthorized");
        body.put("code", "ACCOUNT_NOT_FOUND");
        body.put("message", "Authentication failed.");
        body.put("path", request.getDescription(false).replace("uri=", ""));

        return new ResponseEntity<>(body, HttpStatus.UNAUTHORIZED);
    }

    @ExceptionHandler(LastAdminException.class)
    public ResponseEntity<Object> handleLastAdmin(
            LastAdminException ex, WebRequest request) {
        logger.warn("Last-admin protection: {}", ex.getMessage());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", LocalDateTime.now());
        body.put("status", HttpStatus.CONFLICT.value());
        body.put("error", "Conflict");
        body.put("code", "LAST_ADMIN_PROTECTED");
        body.put("message", ex.getMessage() != null ? ex.getMessage() : "Cannot remove the last admin.");
        body.put("path", request.getDescription(false).replace("uri=", ""));

        return new ResponseEntity<>(body, HttpStatus.CONFLICT);
    }
```

The existing imports in `GlobalExceptionHandler.java` (HttpStatus, ResponseEntity, LinkedHashMap, Map, WebRequest, LocalDateTime, Logger/LoggerFactory) cover everything; add only the three new exception types as imports.

- [ ] **Step 4: Add a quick handler test**

Create `backend/src/test/java/com/albunyaan/tube/exception/GlobalExceptionHandlerAccountTest.java`. Tests use a Mockito mock for `WebRequest` (the project already uses Mockito broadly).

```java
package com.albunyaan.tube.exception;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.WebRequest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GlobalExceptionHandlerAccountTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private static WebRequest mockRequest(String uri) {
        WebRequest req = Mockito.mock(WebRequest.class);
        Mockito.when(req.getDescription(false)).thenReturn("uri=" + uri);
        return req;
    }

    @Test void blockedReturns403WithCodeAndReason() {
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) handler
            .handleAccountBlocked(new AccountBlockedException("u1", "spam"), mockRequest("/api/v1/me"))
            .getBody();
        assertEquals("ACCOUNT_BLOCKED", body.get("code"));
        assertEquals("spam", body.get("reason"));
        assertEquals("/api/v1/me", body.get("path"));
        assertEquals("Forbidden", body.get("error"));
        assertEquals(HttpStatus.FORBIDDEN.value(), body.get("status"));
    }

    @Test void blockedWithoutReason_omitsReasonField() {
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) handler
            .handleAccountBlocked(new AccountBlockedException("u1", null), mockRequest("/api/v1/me"))
            .getBody();
        assertFalse(body.containsKey("reason"));
        assertEquals("ACCOUNT_BLOCKED", body.get("code"));
    }

    @Test void deletedReturns401WithoutLeakingUid() {
        ResponseEntity<Object> r = handler
            .handleAccountDeleted(new AccountDeletedException("u1"), mockRequest("/api/v1/me"));
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) r.getBody();
        assertEquals(HttpStatus.UNAUTHORIZED, r.getStatusCode());
        assertEquals("ACCOUNT_NOT_FOUND", body.get("code"));
        assertFalse(body.containsKey("uid"), "uid must NOT be in response body");
    }

    @Test void lastAdminReturns409Conflict() {
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) handler
            .handleLastAdmin(new LastAdminException("Cannot demote the last admin."), mockRequest("/api/admin/users/u1"))
            .getBody();
        assertEquals("LAST_ADMIN_PROTECTED", body.get("code"));
        assertEquals("Cannot demote the last admin.", body.get("message"));
        assertEquals(HttpStatus.CONFLICT.value(), body.get("status"));
    }
}
```

- [ ] **Step 5: Run tests**

```bash
cd backend && ./gradlew test --tests "com.albunyaan.tube.exception.GlobalExceptionHandlerAccountTest"
```

Expected: green.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/albunyaan/tube/exception/AccountBlockedException.java \
        backend/src/main/java/com/albunyaan/tube/exception/AccountDeletedException.java \
        backend/src/main/java/com/albunyaan/tube/exception/LastAdminException.java \
        backend/src/main/java/com/albunyaan/tube/exception/GlobalExceptionHandler.java \
        backend/src/test/java/com/albunyaan/tube/exception/GlobalExceptionHandlerAccountTest.java
git commit -m "[FEAT]: add Account/LastAdmin exceptions with structured error bodies"
```

---

## Task 5: `FirebaseAuthFilter` — accept USER, status check, conditional revocation

**Files:**
- Modify: `backend/src/main/java/com/albunyaan/tube/security/FirebaseAuthFilter.java`
- Test: `backend/src/test/java/com/albunyaan/tube/integration/AccountStatusFilterIntegrationTest.java` (create)

- [ ] **Step 1: Write the integration test**

Pattern: extends `BaseIntegrationTest`; uses Firebase Emulator + MockMvc. Look at `RbacWorkflowIntegrationTest` first to match style.

```java
package com.albunyaan.tube.integration;

import com.albunyaan.tube.model.User;
import com.albunyaan.tube.model.UserStatus;
import com.albunyaan.tube.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AccountStatusFilterIntegrationTest extends BaseIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository userRepository;

    @Test void blockedUser_getsAccountBlocked403() throws Exception {
        String uid = createEmulatorUser("blocked@test", "moderator");
        markStatus(uid, UserStatus.BLOCKED, "policy violation");
        String token = mintIdToken(uid);

        mvc.perform(get("/api/admin/users/me").header("Authorization", "Bearer " + token))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("ACCOUNT_BLOCKED"));
    }

    @Test void deletedUser_gets401() throws Exception {
        String uid = createEmulatorUser("deleted@test", "moderator");
        markStatus(uid, UserStatus.DELETED, null);
        String token = mintIdToken(uid);

        mvc.perform(get("/api/admin/users/me").header("Authorization", "Bearer " + token))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
    }

    @Test void userRole_isAcceptedByFilter() throws Exception {
        String uid = createEmulatorUser("regular@test", "user");
        // status defaults to active
        String token = mintIdToken(uid);

        // /api/v1/* is the public surface; we only check the filter accepts the role
        mvc.perform(get("/api/v1/categories").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk());
    }

    @Test void pendingProfileUser_isAcceptedByFilter() throws Exception {
        String uid = createEmulatorUser("incomplete@test", "user");
        markStatus(uid, UserStatus.PENDING_PROFILE, null);
        String token = mintIdToken(uid);

        mvc.perform(get("/api/v1/categories").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk());
    }

    private void markStatus(String uid, UserStatus status, String reason) throws Exception {
        User u = userRepository.findByUid(uid).orElseThrow();
        if (status == UserStatus.BLOCKED) u.recordBlock("system-test", reason);
        else if (status == UserStatus.DELETED) u.recordSoftDelete("system-test", reason);
        else u.setStatusEnum(status);
        userRepository.save(u);
    }

    // createEmulatorUser and mintIdToken come from BaseIntegrationTest;
    // if absent, add them as helpers there (see Task 5 sub-step).
}
```

- [ ] **Step 2: Add `createEmulatorUser` and `mintIdToken` helpers to `BaseIntegrationTest`**

`BaseIntegrationTest` does not yet expose these helpers, and `RbacWorkflowIntegrationTest` works directly against `ApprovalService` rather than going through MockMvc + a real ID token. The next 6 integration tests across Tasks 5/6/7/8/10/12 all depend on these helpers, so add them once here.

Add `MockMvc` autoconfiguration to the base:

```java
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "app.firebase.emulator.enabled=true",
    "spring.cache.type=caffeine"
})
public abstract class BaseIntegrationTest {

    @Autowired protected MockMvc mvc;
    @Autowired protected FirebaseAuth firebaseAuth;
    @Autowired protected Firestore firestore;
    @Autowired protected UserRepository userRepository;
    @Autowired protected RestTemplate restTemplate;  // for emulator REST handshake

    @Value("${app.firebase.emulator.host:localhost:9099}")
    private String emulatorHost;

    @Value("${app.firebase.project-id:demo-albunyaan}")
    private String projectId;

    /** Creates a user in the Firebase Auth Emulator AND seeds the Firestore user doc. */
    protected String createEmulatorUser(String email, String role) throws Exception {
        UserRecord rec = firebaseAuth.createUser(new UserRecord.CreateRequest()
            .setEmail(email)
            .setEmailVerified(true)
            .setPassword("test-password-" + email.hashCode())
            .setDisplayName(email.split("@")[0]));
        firebaseAuth.setCustomUserClaims(rec.getUid(),
            Map.of("role", role.toLowerCase(Locale.ROOT)));

        // Seed the Firestore user doc that AuthService and UserRepository expect
        User u = new User();
        u.setUid(rec.getUid());
        u.setEmail(email);
        u.setRole(role.toLowerCase(Locale.ROOT));
        u.setStatus("active");
        u.setCreatedAt(com.google.cloud.Timestamp.now());
        u.setUpdatedAt(u.getCreatedAt());
        userRepository.save(u);

        return rec.getUid();
    }

    /** Mints a verifiable ID token via the Firebase Auth Emulator REST endpoint. */
    protected String mintIdToken(String uid) throws Exception {
        String customToken = firebaseAuth.createCustomToken(uid);

        // POST to emulator's signInWithCustomToken endpoint to exchange custom → ID token
        String url = String.format(
            "http://%s/identitytoolkit.googleapis.com/v1/accounts:signInWithCustomToken?key=fake-api-key",
            emulatorHost);
        Map<String, Object> body = Map.of(
            "token", customToken,
            "returnSecureToken", true);

        @SuppressWarnings("unchecked")
        Map<String, Object> response = restTemplate.postForObject(url, body, Map.class);
        if (response == null || !response.containsKey("idToken")) {
            throw new IllegalStateException("Emulator did not return idToken: " + response);
        }
        return (String) response.get("idToken");
    }

    /** Cleans the Firestore emulator between tests. */
    @AfterEach
    void cleanFirestore() throws Exception {
        // The Firebase Emulator clears via REST DELETE /emulator/v1/projects/{projectId}/databases/(default)/documents
        String url = String.format(
            "http://%s/emulator/v1/projects/%s/databases/(default)/documents",
            firestoreEmulatorHost(), projectId);
        try {
            restTemplate.delete(url);
        } catch (Exception ignore) { /* idempotent best-effort cleanup */ }
    }

    private String firestoreEmulatorHost() {
        return System.getenv().getOrDefault("FIRESTORE_EMULATOR_HOST", "localhost:8080");
    }
}
```

Imports added: `MockMvc`, `AutoConfigureMockMvc`, `Firestore`, `FirebaseAuth`, `UserRecord`, `RestTemplate`, `Locale`, `Map`, `User`, `UserRepository`, `AfterEach`, `Value`. The `RestTemplate` bean must be declared in test config (or use `@TestConfiguration` to provide one).

Verify the helper compiles before moving on:

```bash
cd backend && ./gradlew compileTestJava
```

If there's no existing `RestTemplate` bean, add `@TestConfiguration` with `@Bean public RestTemplate restTemplate() { return new RestTemplate(); }` adjacent to `BaseIntegrationTest`.

- [ ] **Step 3: Run the test; expect failure**

```bash
cd backend && ./gradlew test -Pintegration=true \
  --tests "com.albunyaan.tube.integration.AccountStatusFilterIntegrationTest"
```

Expected: 4 tests, at least 3 fail. The blocked/deleted ones fail because the filter currently accepts the request; `userRole_isAcceptedByFilter` fails because `"user"` isn't in `VALID_ROLES`.

- [ ] **Step 4: Update `FirebaseAuthFilter`**

In `backend/src/main/java/com/albunyaan/tube/security/FirebaseAuthFilter.java`:

a) Widen the allowlist (line 41):

```java
    private static final Set<String> VALID_ROLES = Set.of("admin", "moderator", "user");
```

b) Inject `UserRepository`:

```java
    private final UserRepository userRepository;

    public FirebaseAuthFilter(FirebaseAuth firebaseAuth, UserRepository userRepository) {
        this.firebaseAuth = firebaseAuth;
        this.userRepository = userRepository;
    }
```

(Replace any existing constructor / field for `firebaseAuth` accordingly. If it was field-injected with `@Autowired`, switch to constructor injection — Spring 6 prefers it.)

c) Add the status check inside `doFilterInternal`, **after** `verifyIdToken` succeeds and **before** `setAuthentication`. **Use the in-filter `writeError(...)` helper from the Spec alignment §Filter exception handling section** — do NOT throw `AccountBlockedException` / `AccountDeletedException` from the filter, because `OncePerRequestFilter` exceptions never reach `@ControllerAdvice` (they propagate to the servlet container and produce a generic 500 with no JSON body, breaking the integration tests' `$.code` assertions).

```java
                String uid = decodedToken.getUid();
                boolean isAdminPath = request.getRequestURI().startsWith("/api/admin/");

                // Conditional revocation check on admin paths (avoid double verify)
                if (isAdminPath) {
                    decodedToken = firebaseAuth.verifyIdToken(token, true);
                }

                // Server-authoritative status check via cached findByUid
                Optional<User> userOpt = userRepository.findByUid(uid);
                if (userOpt.isPresent()) {
                    User u = userOpt.get();
                    if (u.isDeleted()) {
                        writeError(response, 401, "ACCOUNT_NOT_FOUND",
                            "Your account has been deleted.");
                        return;  // do NOT call chain.doFilter
                    }
                    if (u.isBlocked()) {
                        writeError(response, 403, "ACCOUNT_BLOCKED",
                            "Your account is blocked.",
                            Map.of("reason", u.getBlockReason() != null ? u.getBlockReason() : "policy-violation"));
                        return;
                    }
                }
                // No Firestore doc yet (first request after Firebase signup) — allow,
                // bootstrap will create it. Plan C handles this explicitly.
```

The `writeError(...)` helper uses Jackson `ObjectMapper` (not manual `String.format` escaping) so it's safe under arbitrary admin-set reason strings:

```java
private final ObjectMapper objectMapper;  // injected; existing project bean

private void writeError(HttpServletResponse response, int status,
                        String code, String message) throws IOException {
    writeError(response, status, code, message, Map.of());
}

private void writeError(HttpServletResponse response, int status,
                        String code, String message,
                        Map<String, Object> extra) throws IOException {
    response.setStatus(status);
    response.setContentType("application/json");
    Map<String, Object> body = new HashMap<>(extra);
    body.put("code", code);
    body.put("message", message);
    objectMapper.writeValue(response.getWriter(), body);
}
```

`AccountBlockedException` and `AccountDeletedException` (from Task 4) are still useful — they fire from `AuthService` when a controller-level path (e.g., admin acting on their own account) needs to surface the same code. `@RestControllerAdvice` catches them in the controller path. They are NOT thrown from the filter.

d) Add imports: `Optional`, `User`, `UserRepository`, `Map`, `HashMap`, `IOException`, `ObjectMapper`, `HttpServletResponse`.

e) The filter currently catches `FirebaseAuthException` and returns 401. Keep that catch; add a separate try/catch around the cached `findByUid(uid)` if needed (Firestore outage → 503 written via `writeError`). The status-check branch never throws — it writes the response and returns.

- [ ] **Step 5: Run integration test; expect green**

```bash
cd backend && ./gradlew test -Pintegration=true \
  --tests "com.albunyaan.tube.integration.AccountStatusFilterIntegrationTest"
```

Expected: 4 passing.

- [ ] **Step 6: Run the existing RBAC test to confirm no regression**

```bash
cd backend && ./gradlew test -Pintegration=true \
  --tests "com.albunyaan.tube.integration.RbacWorkflowIntegrationTest"
```

Expected: green.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/albunyaan/tube/security/FirebaseAuthFilter.java \
        backend/src/test/java/com/albunyaan/tube/integration/AccountStatusFilterIntegrationTest.java \
        backend/src/test/java/com/albunyaan/tube/integration/BaseIntegrationTest.java
git commit -m "[FEAT]: enforce account status server-side; widen role allowlist to include 'user'"
```

- [ ] **Step 8: Wire the `userStatus` Caffeine cache + inject `CacheManager` into `AuthService` (D4)**

The existing `backend/src/main/java/com/albunyaan/tube/config/CacheConfig.java` returns a `CaffeineCacheManager` constructed with cache-name varargs and a single shared 1h Caffeine spec. Modify the existing bean (do not create a second `CacheManager`):

```java
@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager mgr = new CaffeineCacheManager(
            "youtubeChannelSearch", "youtubePlaylistSearch", "youtubeVideoSearch",
            "userStatus");
        mgr.setCaffeine(Caffeine.newBuilder()
            .expireAfterWrite(1, TimeUnit.HOURS)
            .maximumSize(1_000));
        // Override default for userStatus only — 60s TTL per D4
        mgr.registerCustomCache("userStatus",
            Caffeine.newBuilder()
                .expireAfterWrite(60, TimeUnit.SECONDS)
                .maximumSize(5_000)
                .build());
        return mgr;
    }
}
```

Annotate `UserRepository.findByUid(String)`:

```java
@Cacheable(value = "userStatus", key = "#uid")
public Optional<User> findByUid(String uid) throws ... {
    // existing impl unchanged
}
```

**Inject `CacheManager`, `Firestore`, `AuditLogRepository`, and `FirestoreTimeoutProperties` into `AuthService`** so the lifecycle methods (Tasks 6, 7, 8) can evict on commit, run transactions, and write audit-log docs with the correct timeout. Update the constructor:

```java
public AuthService(FirebaseAuth firebaseAuth,
                   UserRepository userRepository,
                   AuditLogService auditLogService,
                   AuditLogRepository auditLogRepository,  // tx.set(auditLogsCollection().document(), audit) — see Task 9 Step 1
                   Firestore firestore,                      // for runTransaction(...)
                   CacheManager cacheManager,                // for cache.evict(uid) post-tx
                   FirestoreTimeoutProperties timeoutProperties) {  // for tx.get(...).get(timeoutProperties.getWrite(), TimeUnit.SECONDS)
    this.firebaseAuth = firebaseAuth;
    this.userRepository = userRepository;
    this.auditLogService = auditLogService;
    this.auditLogRepository = auditLogRepository;
    this.firestore = firestore;
    this.cacheManager = cacheManager;
    this.timeoutProperties = timeoutProperties;
}
```

`FirestoreTimeoutProperties` is the existing bean used by `UserRepository` and other Firestore-touching services; it exposes `getRead()`, `getWrite()`, and `getBulkQuery()`. `AuthService` uses `getWrite()` for the entire `runTransaction` lambda + commit because the lambda contains writes; reads inside the lambda inherit the same timeout (acceptable tradeoff documented in the alignment section).

**Update existing `AuthService` constructor call sites.** The old 3-arg signature (`firebaseAuth, userRepository, auditLogService`) referenced in legacy Mockito tests (Tasks 6 Step 1, 7 Step 1, 8 Step 2 — all marked reference-only) no longer compiles. Production callers use Spring's constructor injection so they don't need code changes; verify no other test class still uses `new AuthService(...)` with the old shape:

```bash
grep -rn "new AuthService(" backend/src/
```

If any test instantiates `AuthService` manually, update the signature.

Add an integration test that confirms cache hits — assert via the Micrometer `cache.gets` counter (which Spring's Caffeine cache instrumentation emits with a `result=hit|miss` tag). Asserting only `getUid()` equality between two calls is tautological (the same input returns the same output regardless of cache state):

```java
// backend/src/test/java/com/albunyaan/tube/repository/UserStatusCacheTest.java
@Autowired MeterRegistry meterRegistry;

@Test void findByUid_secondCall_isCacheHit() throws Exception {
    String uid = createEmulatorUser("cache@t", "moderator");

    // First call → miss (loads + caches)
    userRepository.findByUid(uid).orElseThrow();

    double hitsBefore = meterRegistry.counter("cache.gets",
        "result", "hit", "name", "userStatus").count();

    // Second call → must hit cache, not Firestore
    userRepository.findByUid(uid).orElseThrow();

    double hitsAfter = meterRegistry.counter("cache.gets",
        "result", "hit", "name", "userStatus").count();

    assertEquals(1.0, hitsAfter - hitsBefore,
        "Second findByUid call must be served from userStatus cache");
}
```

Verify Micrometer-Caffeine instrumentation is on the classpath (`org.springframework.boot:spring-boot-starter-actuator` brings it in transitively).

Lifecycle methods (Tasks 6, 7, 8) MUST call the null-safe evict pattern AFTER `runTransaction.get()` returns:

```java
Cache cache = cacheManager.getCache("userStatus");
if (cache != null) cache.evict(uid);
```

The Canonical lifecycle method in Spec alignment shows the placement.

Run:

```bash
cd backend && ./gradlew test -Pintegration=true \
  --tests "com.albunyaan.tube.repository.UserStatusCacheTest"
```

Expected: passing. Then commit:

```bash
git add backend/src/main/java/com/albunyaan/tube/config/CacheConfig.java \
        backend/src/main/java/com/albunyaan/tube/repository/UserRepository.java \
        backend/src/main/java/com/albunyaan/tube/service/AuthService.java \
        backend/src/test/java/com/albunyaan/tube/repository/UserStatusCacheTest.java
git commit -m "[PERF]: add userStatus Caffeine cache, 60s TTL, evicted on lifecycle mutation"
```

- [ ] **Step 9: Lowercase the `role` custom claim in existing `AuthService` calls (D6)**

`AuthService.createUser` (line 92) and `updateUserRole` (line 112) currently set `claims.put("role", role.toUpperCase())`. Per D6, wire format is lowercase across both Firestore and JWT claims (Plan B Android client expects `claims.role == "admin"`). Replace `.toUpperCase()` with `.toLowerCase(Locale.ROOT)` in both call sites. Add unit-test coverage:

```java
@Test void createUser_setsLowercaseRoleClaim() throws Exception {
    User u = svc.createUser("e@t", "Test", "ADMIN", "actor");
    ArgumentCaptor<Map<String, Object>> claims = ArgumentCaptor.forClass(Map.class);
    verify(firebaseAuth).setCustomUserClaims(eq(u.getUid()), claims.capture());
    assertEquals("admin", claims.getValue().get("role"));
}
```

Existing custom-claim consumers (`FirebaseAuthFilter` line 73 already lowercases on read) tolerate this change — the filter's `toLowerCase(Locale.ROOT)` becomes a no-op, not a behavior change. Plan B Android client now sees lowercase claims as the spec mandates.

---

## Task 6: `AuthService.softDeleteUser` + `recoverUser`

> **DO NOT START Task 6 until Task 5 Step 8 + Task 9 are committed.** Task 6 references symbols those tasks add: `firestore`, `cacheManager`, `auditLogRepository`, `timeoutProperties` injected into `AuthService` (Task 5 Step 8); `auditLogRepository.auditLogsCollection()` and `auditLogService.buildSoftDelete(...)` / `buildRecover(...)` (Task 9). Running Task 6 first will not compile.

**Files:**
- Modify: `backend/src/main/java/com/albunyaan/tube/service/AuthService.java`
- Modify: `backend/src/main/java/com/albunyaan/tube/controller/UserController.java`
- Test: `backend/src/test/java/com/albunyaan/tube/service/AuthServiceSoftDeleteTest.java` (create)

> **Note:** Step 1 below shows a Mockito-based unit test for the original non-transactional design. **Treat Step 1 as reference-only for assertion shape** (status enum values, helper-method names like `recordSoftDelete` / `recordRecover`). The actual failing test for this task is the **Firebase Emulator integration test in Step 5** — Mockito cannot faithfully replicate `runTransaction` read-set tracking, retries, or optimistic concurrency, and a fake `Transaction` mock provides false confidence. The implementation in Step 3 is the **canonical transactional pattern** matching Spec alignment §Canonical lifecycle method.

- [ ] **Step 1: Write failing test**

```java
package com.albunyaan.tube.service;

import com.albunyaan.tube.model.User;
import com.albunyaan.tube.model.UserStatus;
import com.albunyaan.tube.repository.UserRepository;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.UserRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

// REFERENCE ONLY — DO NOT COMPILE OR RUN. The actual failing test is the
// integration test in Step 5; this class uses an obsolete 3-arg AuthService
// constructor and stubs `userRepo.countActiveAdmins()` which no longer exists.
class AuthServiceSoftDeleteTest {

    private FirebaseAuth firebaseAuth;
    private UserRepository userRepo;
    private AuditLogService auditLog;
    private AuthService svc;

    @BeforeEach void setUp() {
        firebaseAuth = mock(FirebaseAuth.class);
        userRepo = mock(UserRepository.class);
        auditLog = mock(AuditLogService.class);
        svc = new AuthService(firebaseAuth, userRepo, auditLog /* + any other current deps */);
    }

    @Test void softDelete_marksUserDeletedAndDisablesFirebaseAuth() throws Exception {
        User existing = newUser("u1", "moderator");
        when(userRepo.findByUid("u1")).thenReturn(Optional.of(existing));
        when(userRepo.countActiveAdmins()).thenReturn(2L);

        svc.softDeleteUser("u1", "admin-uid", "user-request");

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepo).save(saved.capture());
        assertEquals(UserStatus.DELETED, saved.getValue().getStatusEnum());
        assertEquals("admin-uid", saved.getValue().getDeletedBy());
        assertEquals("user-request", saved.getValue().getDeleteReason());

        ArgumentCaptor<UserRecord.UpdateRequest> req =
            ArgumentCaptor.forClass(UserRecord.UpdateRequest.class);
        verify(firebaseAuth).updateUser(req.capture());
        // disabled flag is on the request; assert via toString or reflection if needed

        verify(auditLog).logSoftDelete(eq("u1"), eq("admin-uid"), eq("user-request"));
    }

    @Test void recoverUser_clearsDeletionAndReenablesAuth() throws Exception {
        User existing = newUser("u1", "moderator");
        existing.recordSoftDelete("admin-old", "old-reason");
        when(userRepo.findByUid("u1")).thenReturn(Optional.of(existing));

        svc.recoverUser("u1", "admin-new");

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepo).save(saved.capture());
        assertEquals(UserStatus.ACTIVE, saved.getValue().getStatusEnum());
        assertEquals("admin-new", saved.getValue().getRecoveredBy());
        assertNull(saved.getValue().getDeletedAt());

        verify(firebaseAuth).updateUser(any(UserRecord.UpdateRequest.class));
        verify(auditLog).logRecover(eq("u1"), eq("admin-new"));
    }

    @Test void softDelete_unknownUid_throws() {
        when(userRepo.findByUid("nope")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,
            () -> svc.softDeleteUser("nope", "admin-uid", "x"));
    }

    private User newUser(String uid, String role) {
        User u = new User();
        u.setUid(uid);
        u.setEmail(uid + "@test");
        u.setRole(role);
        return u;
    }
}
```

- [ ] **Step 2: Run; expect compile failure** (`softDeleteUser` and `recoverUser` don't exist; `countActiveAdmins` doesn't exist either — Task 8 adds it; for Task 6 stub it on the mock)

- [ ] **Step 3: Implement `softDeleteUser` and `recoverUser` using the canonical lifecycle pattern**

Replace the existing `deleteUser(String uid)` method (line 153) with both lifecycle methods. Note: Task 9 (audit builders + repo accessor) and Task 5 Step 8 (CacheManager injection) MUST land first — Tasks 6/7 reference symbols those tasks add.

```java
public void softDeleteUser(String uid, String actorUid, String reason)
        throws Exception {
    firestore.runTransaction(tx -> {
        DocumentReference userRef = firestore.collection("users").document(uid);
        DocumentSnapshot snap = tx.get(userRef).get(timeoutProperties.getWrite(), TimeUnit.SECONDS);
        if (!snap.exists()) {
            throw new IllegalArgumentException("User not found: " + uid);
        }
        User target = snap.toObject(User.class);

        // Last-admin guard (D2)
        if (target.isAdmin()) {
            if (uid.equals(actorUid)) {
                throw new LastAdminException("Admins cannot delete themselves.");
            }
            QuerySnapshot admins = tx.get(firestore.collection("users")
                .whereEqualTo("role", "admin")
                .whereEqualTo("status", "active"))
                .get(timeoutProperties.getWrite(), TimeUnit.SECONDS);
            if (admins.size() <= 1) {
                throw new LastAdminException("Cannot delete the last active admin.");
            }
        }

        target.recordSoftDelete(actorUid, reason);

        AuditLog audit = auditLogService.buildSoftDelete(uid, actorUid, reason);

        tx.set(userRef, target);
        tx.set(auditLogRepository.auditLogsCollection().document(), audit);
        return null;
    }).get(timeoutProperties.getWrite(), TimeUnit.SECONDS);

    // D9 — outside the tx, idempotent
    firebaseAuth.updateUser(new UserRecord.UpdateRequest(uid).setDisabled(true));
    firebaseAuth.revokeRefreshTokens(uid);

    // D4 — cache evict
    Cache cache = cacheManager.getCache("userStatus");
    if (cache != null) cache.evict(uid);

    logger.info("Soft-deleted user uid={} actor={}", uid, actorUid);
}

public void recoverUser(String uid, String actorUid) throws Exception {
    firestore.runTransaction(tx -> {
        DocumentReference userRef = firestore.collection("users").document(uid);
        DocumentSnapshot snap = tx.get(userRef).get(timeoutProperties.getWrite(), TimeUnit.SECONDS);
        if (!snap.exists()) {
            throw new IllegalArgumentException("User not found: " + uid);
        }
        User target = snap.toObject(User.class);
        if (!target.isDeleted()) {
            throw new IllegalStateException("User is not in DELETED status: " + uid);
        }

        target.recordRecover(actorUid);

        AuditLog audit = auditLogService.buildRecover(uid, actorUid);

        tx.set(userRef, target);
        tx.set(auditLogRepository.auditLogsCollection().document(), audit);
        return null;
    }).get(timeoutProperties.getWrite(), TimeUnit.SECONDS);

    firebaseAuth.updateUser(new UserRecord.UpdateRequest(uid).setDisabled(false));

    Cache cache = cacheManager.getCache("userStatus");
    if (cache != null) cache.evict(uid);

    logger.info("Recovered user uid={} actor={}", uid, actorUid);
}
```

Recover does not trigger the last-admin guard (recovery only increases admin count). It does require the target be in `DELETED` status — `INVALID_TRANSITION` 409 if the controller catches `IllegalStateException` and maps it.

- [ ] **Step 4: Update `UserController.deleteUser`**

The route at `UserController.java:183` currently calls `authService.deleteUser`. Replace it:

```java
@DeleteMapping("/{uid}")
@PreAuthorize("hasRole('ADMIN')")
public ResponseEntity<Void> deleteUser(
        @PathVariable String uid,
        @AuthenticationPrincipal FirebaseUserDetails actor,
        @RequestParam(required = false, defaultValue = "admin-action") String reason) throws Exception {
    if (actor == null) return ResponseEntity.status(401).build();
    authService.softDeleteUser(uid, actor.getUid(), reason);
    return ResponseEntity.noContent().build();
}

@PostMapping("/{uid}/recover")
@PreAuthorize("hasRole('ADMIN')")
public ResponseEntity<Void> recoverUser(
        @PathVariable String uid,
        @AuthenticationPrincipal FirebaseUserDetails actor) throws Exception {
    if (actor == null) return ResponseEntity.status(401).build();
    authService.recoverUser(uid, actor.getUid());
    return ResponseEntity.noContent().build();
}
```

- [ ] **Step 5: Replace the Mockito Step-1 test with an integration test**

The Mockito test in Step 1 does not faithfully replicate `runTransaction` semantics. Delete `AuthServiceSoftDeleteTest.java` and create `AuthServiceSoftDeleteIntegrationTest.java`:

```java
package com.albunyaan.tube.integration;

import com.albunyaan.tube.exception.LastAdminException;
import com.albunyaan.tube.model.User;
import com.albunyaan.tube.model.UserStatus;
import com.albunyaan.tube.repository.AuditLogRepository;
import com.albunyaan.tube.repository.UserRepository;
import com.albunyaan.tube.service.AuthService;
import com.google.cloud.firestore.QuerySnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.*;

class AuthServiceSoftDeleteIntegrationTest extends BaseIntegrationTest {

    @Autowired AuthService authService;
    @Autowired UserRepository userRepo;
    @Autowired AuditLogRepository auditRepo;

    @Test void softDelete_marksDeletedAndWritesAudit() throws Exception {
        String adminUid = createEmulatorUser("a@t", "admin");
        String adminUid2 = createEmulatorUser("a2@t", "admin"); // ensure not last
        String targetUid = createEmulatorUser("u@t", "moderator");

        authService.softDeleteUser(targetUid, adminUid, "policy-violation");

        User after = userRepo.findByUid(targetUid).orElseThrow();
        assertEquals(UserStatus.DELETED, after.getStatusEnum());
        assertEquals(adminUid, after.getDeletedBy());
        assertEquals("policy-violation", after.getDeleteReason());

        QuerySnapshot audits = auditRepo.auditLogsCollection()
            .whereEqualTo("action", "USER_SOFT_DELETED")
            .whereEqualTo("entityId", targetUid)
            .get().get();
        assertEquals(1, audits.size());
    }

    @Test void recover_clearsDeletionAndWritesAudit() throws Exception {
        String adminUid = createEmulatorUser("a3@t", "admin");
        String adminUid2 = createEmulatorUser("a4@t", "admin");
        String targetUid = createEmulatorUser("u2@t", "moderator");

        authService.softDeleteUser(targetUid, adminUid, "test");
        authService.recoverUser(targetUid, adminUid);

        User after = userRepo.findByUid(targetUid).orElseThrow();
        assertTrue(after.isActive());

        QuerySnapshot recoveryAudits = auditRepo.auditLogsCollection()
            .whereEqualTo("action", "USER_RECOVERED")
            .whereEqualTo("entityId", targetUid)
            .get().get();
        assertEquals(1, recoveryAudits.size());
    }

    @Test void softDeleteLastAdmin_throws() throws Exception {
        String soloAdmin = createEmulatorUser("solo@t", "admin");
        // No other admins active

        assertThrows(LastAdminException.class,
            () -> authService.softDeleteUser(soloAdmin, soloAdmin, "test"));
    }

    @Test void softDeleteUnknownUid_throws() {
        assertThrows(IllegalArgumentException.class,
            () -> authService.softDeleteUser("nope", "admin", "x"));
    }
}
```

- [ ] **Step 6: Run tests**

```bash
cd backend && ./gradlew test -Pintegration=true \
  --tests "com.albunyaan.tube.integration.AuthServiceSoftDeleteIntegrationTest"
```

Expected: 4 passing.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/albunyaan/tube/service/AuthService.java \
        backend/src/main/java/com/albunyaan/tube/controller/UserController.java \
        backend/src/test/java/com/albunyaan/tube/integration/AuthServiceSoftDeleteIntegrationTest.java
git commit -m "[FEAT]: replace hard-delete with transactional soft-delete + recover; sync audit"
```

---

## Task 7: `AuthService.blockUser` / `unblockUser`

> **DO NOT START Task 7 until Task 5 Step 8 + Task 9 are committed.** Same reason as Task 6: this task uses `firestore`, `cacheManager`, `auditLogRepository`, `timeoutProperties`, plus `auditLogService.buildBlock` / `buildUnblock`.

**Files:**
- Modify: `backend/src/main/java/com/albunyaan/tube/service/AuthService.java`
- Modify: `backend/src/main/java/com/albunyaan/tube/controller/UserController.java`
- Test: `backend/src/test/java/com/albunyaan/tube/service/AuthServiceBlockTest.java` (create)

> **Note:** Step 1 below is a Mockito unit test from the original non-transactional design — **treat as reference-only for assertion shape**. The actual failing test is the **Firebase Emulator integration test in Step 4** (renumbered: this task no longer uses the legacy Step-2/Step-4 stub-injection sequence). The Step 2 implementation is the **canonical transactional pattern** matching Spec alignment §Canonical lifecycle method.

- [ ] **Step 1: Write failing test**

```java
package com.albunyaan.tube.service;

import com.albunyaan.tube.model.User;
import com.albunyaan.tube.model.UserStatus;
import com.albunyaan.tube.repository.UserRepository;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.UserRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

// REFERENCE ONLY — DO NOT COMPILE OR RUN. The actual failing test is the
// integration test in Step 4 (AuthServiceBlockIntegrationTest); this class
// uses an obsolete 3-arg AuthService constructor and stubs `userRepo.countActiveAdmins()`.
class AuthServiceBlockTest {

    private FirebaseAuth firebaseAuth;
    private UserRepository userRepo;
    private AuditLogService auditLog;
    private AuthService svc;

    @BeforeEach void setUp() {
        firebaseAuth = mock(FirebaseAuth.class);
        userRepo = mock(UserRepository.class);
        auditLog = mock(AuditLogService.class);
        svc = new AuthService(firebaseAuth, userRepo, auditLog);
    }

    @Test void block_marksBlocked_disablesAuth_revokesTokens_audits() throws Exception {
        User u = newUser("u1", "moderator");
        when(userRepo.findByUid("u1")).thenReturn(Optional.of(u));
        when(userRepo.countActiveAdmins()).thenReturn(2L);

        svc.blockUser("u1", "admin-uid", "spam");

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepo).save(saved.capture());
        assertEquals(UserStatus.BLOCKED, saved.getValue().getStatusEnum());
        assertEquals("spam", saved.getValue().getBlockReason());

        verify(firebaseAuth).updateUser(any(UserRecord.UpdateRequest.class));
        verify(firebaseAuth).revokeRefreshTokens("u1");
        verify(auditLog).logBlock("u1", "admin-uid", "spam");
    }

    @Test void unblock_marksActive_reenablesAuth_audits() throws Exception {
        User u = newUser("u1", "moderator");
        u.recordBlock("admin-old", "old-reason");
        when(userRepo.findByUid("u1")).thenReturn(Optional.of(u));

        svc.unblockUser("u1", "admin-uid");

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepo).save(saved.capture());
        assertEquals(UserStatus.ACTIVE, saved.getValue().getStatusEnum());
        assertNull(saved.getValue().getBlockedAt());

        verify(firebaseAuth).updateUser(any(UserRecord.UpdateRequest.class));
        verify(auditLog).logUnblock("u1", "admin-uid");
    }

    @Test void block_alreadyBlocked_isIdempotent() throws Exception {
        User u = newUser("u1", "moderator");
        u.recordBlock("admin-old", "old");
        when(userRepo.findByUid("u1")).thenReturn(Optional.of(u));
        when(userRepo.countActiveAdmins()).thenReturn(2L);

        svc.blockUser("u1", "admin-uid", "new-reason");

        // No state change, but audit still fires (track repeat blocks)
        verify(auditLog).logBlock("u1", "admin-uid", "new-reason");
    }

    private User newUser(String uid, String role) {
        User u = new User();
        u.setUid(uid);
        u.setEmail(uid + "@test");
        u.setRole(role);
        return u;
    }
}
```

- [ ] **Step 2: Implement `blockUser` and `unblockUser` using the canonical lifecycle pattern**

```java
public void blockUser(String uid, String actorUid, String reason) throws Exception {
    firestore.runTransaction(tx -> {
        DocumentReference userRef = firestore.collection("users").document(uid);
        DocumentSnapshot snap = tx.get(userRef).get(timeoutProperties.getWrite(), TimeUnit.SECONDS);
        if (!snap.exists()) {
            throw new IllegalArgumentException("User not found: " + uid);
        }
        User target = snap.toObject(User.class);

        // Last-admin guard (D2)
        if (target.isAdmin()) {
            if (uid.equals(actorUid)) {
                throw new LastAdminException("Admins cannot block themselves.");
            }
            QuerySnapshot admins = tx.get(firestore.collection("users")
                .whereEqualTo("role", "admin")
                .whereEqualTo("status", "active"))
                .get(timeoutProperties.getWrite(), TimeUnit.SECONDS);
            if (admins.size() <= 1) {
                throw new LastAdminException("Cannot block the last active admin.");
            }
        }

        target.recordBlock(actorUid, reason);

        AuditLog audit = auditLogService.buildBlock(uid, actorUid, reason);

        tx.set(userRef, target);
        tx.set(auditLogRepository.auditLogsCollection().document(), audit);
        return null;
    }).get(timeoutProperties.getWrite(), TimeUnit.SECONDS);

    firebaseAuth.updateUser(new UserRecord.UpdateRequest(uid).setDisabled(true));
    firebaseAuth.revokeRefreshTokens(uid);

    Cache cache = cacheManager.getCache("userStatus");
    if (cache != null) cache.evict(uid);

    logger.info("Blocked user uid={} actor={} reason={}", uid, actorUid, reason);
}

public void unblockUser(String uid, String actorUid) throws Exception {
    firestore.runTransaction(tx -> {
        DocumentReference userRef = firestore.collection("users").document(uid);
        DocumentSnapshot snap = tx.get(userRef).get(timeoutProperties.getWrite(), TimeUnit.SECONDS);
        if (!snap.exists()) {
            throw new IllegalArgumentException("User not found: " + uid);
        }
        User target = snap.toObject(User.class);
        if (!target.isBlocked()) {
            throw new IllegalStateException("User is not in BLOCKED status: " + uid);
        }

        target.recordUnblock(actorUid);

        AuditLog audit = auditLogService.buildUnblock(uid, actorUid);

        tx.set(userRef, target);
        tx.set(auditLogRepository.auditLogsCollection().document(), audit);
        return null;
    }).get(timeoutProperties.getWrite(), TimeUnit.SECONDS);

    firebaseAuth.updateUser(new UserRecord.UpdateRequest(uid).setDisabled(false));

    Cache cache = cacheManager.getCache("userStatus");
    if (cache != null) cache.evict(uid);

    logger.info("Unblocked user uid={} actor={}", uid, actorUid);
}
```

`unblockUser` does not trigger the last-admin guard (unblock only increases active-admin count). Calling unblock on a non-blocked user yields `IllegalStateException` → controller maps to 409 `INVALID_TRANSITION`.

- [ ] **Step 3: Add controller routes**

In `UserController.java`:

```java
@PostMapping("/{uid}/block")
@PreAuthorize("hasRole('ADMIN')")
public ResponseEntity<Void> block(
        @PathVariable String uid,
        @AuthenticationPrincipal FirebaseUserDetails actor,
        @RequestBody Map<String, String> body) throws Exception {
    if (actor == null) return ResponseEntity.status(401).build();
    String reason = body.getOrDefault("reason", "policy-violation");
    authService.blockUser(uid, actor.getUid(), reason);
    return ResponseEntity.noContent().build();
}

@PostMapping("/{uid}/unblock")
@PreAuthorize("hasRole('ADMIN')")
public ResponseEntity<Void> unblock(
        @PathVariable String uid,
        @AuthenticationPrincipal FirebaseUserDetails actor) throws Exception {
    if (actor == null) return ResponseEntity.status(401).build();
    authService.unblockUser(uid, actor.getUid());
    return ResponseEntity.noContent().build();
}
```

- [ ] **Step 4: Replace the Mockito Step-1 test with an integration test**

Delete `AuthServiceBlockTest.java`. Create:

```java
package com.albunyaan.tube.integration;

import com.albunyaan.tube.exception.LastAdminException;
import com.albunyaan.tube.model.User;
import com.albunyaan.tube.repository.AuditLogRepository;
import com.albunyaan.tube.repository.UserRepository;
import com.albunyaan.tube.service.AuthService;
import com.google.cloud.firestore.QuerySnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.*;

class AuthServiceBlockIntegrationTest extends BaseIntegrationTest {

    @Autowired AuthService authService;
    @Autowired UserRepository userRepo;
    @Autowired AuditLogRepository auditRepo;

    @Test void block_marksBlockedAndDisablesAuth() throws Exception {
        String adminUid = createEmulatorUser("a@t", "admin");
        String adminUid2 = createEmulatorUser("a2@t", "admin");
        String targetUid = createEmulatorUser("u@t", "moderator");

        authService.blockUser(targetUid, adminUid, "spam");

        User after = userRepo.findByUid(targetUid).orElseThrow();
        assertTrue(after.isBlocked());
        assertEquals("spam", after.getBlockReason());

        QuerySnapshot audits = auditRepo.auditLogsCollection()
            .whereEqualTo("action", "USER_BLOCKED")
            .whereEqualTo("entityId", targetUid)
            .get().get();
        assertEquals(1, audits.size());
    }

    @Test void unblock_marksActive() throws Exception {
        String adminUid = createEmulatorUser("a3@t", "admin");
        String adminUid2 = createEmulatorUser("a4@t", "admin");
        String targetUid = createEmulatorUser("u2@t", "moderator");

        authService.blockUser(targetUid, adminUid, "test");
        authService.unblockUser(targetUid, adminUid);

        assertTrue(userRepo.findByUid(targetUid).orElseThrow().isActive());
    }

    @Test void blockLastAdmin_throws() throws Exception {
        String soloAdmin = createEmulatorUser("solo@t", "admin");

        assertThrows(LastAdminException.class,
            () -> authService.blockUser(soloAdmin, soloAdmin, "test"));
    }

    @Test void unblockNonBlocked_throws() throws Exception {
        String adminUid = createEmulatorUser("a5@t", "admin");
        String adminUid2 = createEmulatorUser("a6@t", "admin");
        String targetUid = createEmulatorUser("u3@t", "moderator");

        assertThrows(IllegalStateException.class,
            () -> authService.unblockUser(targetUid, adminUid));
    }
}
```

- [ ] **Step 5: Run tests**

```bash
cd backend && ./gradlew test -Pintegration=true \
  --tests "com.albunyaan.tube.integration.AuthServiceBlockIntegrationTest"
```

Expected: 4 passing.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/albunyaan/tube/service/AuthService.java \
        backend/src/main/java/com/albunyaan/tube/controller/UserController.java \
        backend/src/test/java/com/albunyaan/tube/integration/AuthServiceBlockIntegrationTest.java
git commit -m "[FEAT]: transactional blockUser/unblockUser with sync audit + last-admin guard"
```

---

## Task 8: `updateUserRoleAsActor` + last-admin guard wiring

> **DO NOT START Task 8 until Task 5 Step 8 + Task 9 are committed.** Same reason as Tasks 6 and 7.

**Files:**
- Modify: `backend/src/main/java/com/albunyaan/tube/service/AuthService.java` — add `updateUserRoleAsActor(String uid, String newRole, String actorUid)`
- Modify: `backend/src/main/java/com/albunyaan/tube/controller/UserController.java` — point `PATCH /role` at `updateUserRoleAsActor`
- Test: `backend/src/test/java/com/albunyaan/tube/integration/AuthServiceLastAdminIntegrationTest.java` (create)

> **SUPERSEDED by Spec alignment (D2):** The original Task 8 added `UserRepository.countActiveAdmins()` and a `guardLastAdminProtection(...)` private helper called BEFORE the user save. That pattern is racy under concurrent demotes/blocks/deletes (two simultaneous calls each see count == 2, both succeed, leaving zero admins). The replacement is: **the last-admin check lives inline inside each lifecycle method's `firestore.runTransaction(...)` block.** Tasks 6, 7, and the new `updateUserRoleAsActor` already include the inline guard per the Canonical lifecycle method.
>
> **What this task now does:**
> 1. Adds `updateUserRoleAsActor(String uid, String newRole, String actorUid)` to `AuthService` using the Canonical lifecycle pattern (transactional read of admin count when demoting from admin, audit doc inside the same tx, cache evict after).
> 2. Updates `UserController.updateUserRole` to call the actor-aware method (`actor.getUid()` from `@AuthenticationPrincipal`). The deprecated 2-arg `updateUserRole` is removed.
> 3. Adds an integration test (Firebase Emulator) covering: demote-last-admin throws 409, demote-non-last-admin succeeds, self-demote-of-admin throws 409 even with 5 admins (per spec §7 Last-admin guard scope).
>
> `UserRepository.countActiveAdmins()` is **not added** (the inline transactional `tx.get(adminQuery)` replaces it). The Mockito-based `AuthServiceLastAdminTest.java` shown below is replaced by the integration test above.

- [ ] **Step 1: Inspect current repo and find admin counting**

```bash
cd backend && grep -n "interface UserRepository\|findById\|count\|@Query" src/main/java/com/albunyaan/tube/repository/UserRepository.java
```

Note the existing query style. Firestore queries here probably go through `Firestore` directly, not Spring Data — so `countActiveAdmins()` will issue an `whereEqualTo` query.

- [ ] **Step 2: Write failing test**

```java
package com.albunyaan.tube.service;

import com.albunyaan.tube.exception.LastAdminException;
import com.albunyaan.tube.model.User;
import com.albunyaan.tube.repository.UserRepository;
import com.google.firebase.auth.FirebaseAuth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

// REFERENCE ONLY — DO NOT COMPILE OR RUN. The actual failing test is the
// integration test in Step 5 (AuthServiceLastAdminIntegrationTest); this class
// references the removed 2-arg `updateUserRole` and the removed `countActiveAdmins()` method.
class AuthServiceLastAdminTest {

    private FirebaseAuth firebaseAuth;
    private UserRepository userRepo;
    private AuditLogService auditLog;
    private AuthService svc;

    @BeforeEach void setUp() {
        firebaseAuth = mock(FirebaseAuth.class);
        userRepo = mock(UserRepository.class);
        auditLog = mock(AuditLogService.class);
        svc = new AuthService(firebaseAuth, userRepo, auditLog);
    }

    @Test void demotingLastAdmin_throws() {
        User admin = adminUser("u1");
        when(userRepo.findByUid("u1")).thenReturn(Optional.of(admin));
        when(userRepo.countActiveAdmins()).thenReturn(1L);

        assertThrows(LastAdminException.class,
            () -> svc.updateUserRole("u1", "moderator"));
    }

    @Test void demotingNonLastAdmin_succeeds() throws Exception {
        User admin = adminUser("u1");
        when(userRepo.findByUid("u1")).thenReturn(Optional.of(admin));
        when(userRepo.countActiveAdmins()).thenReturn(2L);

        svc.updateUserRole("u1", "moderator");
        verify(userRepo).save(any(User.class));
    }

    @Test void blockingLastAdmin_throws() {
        User admin = adminUser("u1");
        when(userRepo.findByUid("u1")).thenReturn(Optional.of(admin));
        when(userRepo.countActiveAdmins()).thenReturn(1L);

        assertThrows(LastAdminException.class,
            () -> svc.blockUser("u1", "self", "x"));
    }

    @Test void softDeletingLastAdmin_throws() {
        User admin = adminUser("u1");
        when(userRepo.findByUid("u1")).thenReturn(Optional.of(admin));
        when(userRepo.countActiveAdmins()).thenReturn(1L);

        assertThrows(LastAdminException.class,
            () -> svc.softDeleteUser("u1", "self", "x"));
    }

    @Test void selfDemotion_throws_evenWithMultipleAdmins() {
        User admin = adminUser("u1");
        when(userRepo.findByUid("u1")).thenReturn(Optional.of(admin));
        when(userRepo.countActiveAdmins()).thenReturn(5L);

        assertThrows(LastAdminException.class,
            () -> svc.updateUserRoleAsActor("u1", "moderator", "u1"));
    }

    private User adminUser(String uid) {
        User u = new User();
        u.setUid(uid);
        u.setRole("admin");
        return u;
    }
}
```

- [ ] **Step 3: Implement `updateUserRoleAsActor` using the canonical lifecycle pattern**

```java
public User updateUserRoleAsActor(String uid, String newRoleStr, String actorUid)
        throws Exception {
    Role newRole = Role.fromString(newRoleStr);
    final String[] previousRole = new String[1];

    User updated = firestore.runTransaction(tx -> {
        DocumentReference userRef = firestore.collection("users").document(uid);
        DocumentSnapshot snap = tx.get(userRef).get(timeoutProperties.getWrite(), TimeUnit.SECONDS);
        if (!snap.exists()) {
            throw new IllegalArgumentException("User not found: " + uid);
        }
        User target = snap.toObject(User.class);
        previousRole[0] = target.getRole();

        // Last-admin guard (D2) — inline, transactional
        if (target.isAdmin() && newRole != Role.ADMIN) {
            if (uid.equals(actorUid)) {
                throw new LastAdminException("Admins cannot demote themselves. Ask another admin.");
            }
            QuerySnapshot admins = tx.get(firestore.collection("users")
                .whereEqualTo("role", "admin")
                .whereEqualTo("status", "active"))
                .get(timeoutProperties.getWrite(), TimeUnit.SECONDS);
            if (admins.size() <= 1) {
                throw new LastAdminException("Cannot demote the last active admin.");
            }
        }

        target.setRole(newRole.getValue());
        target.setUpdatedAt(Timestamp.now());

        AuditLog audit = auditLogService.buildRoleChange(uid, actorUid,
            previousRole[0], newRole.getValue());

        tx.set(userRef, target);
        tx.set(auditLogRepository.auditLogsCollection().document(), audit);
        return target;
    }).get(timeoutProperties.getWrite(), TimeUnit.SECONDS);

    // Update Firebase Auth custom claims OUTSIDE the tx (D9)
    Map<String, Object> claims = new HashMap<>();
    claims.put("role", newRole.getValue());  // lowercase per D6 (Step 9 of Task 5)
    firebaseAuth.setCustomUserClaims(uid, claims);

    // Cache eviction (D4)
    Cache cache = cacheManager.getCache("userStatus");
    if (cache != null) cache.evict(uid);

    return updated;
}
```

`timeoutProperties` is `FirestoreTimeoutProperties` injected into the constructor (the same bean the existing `UserRepository` uses). `getWrite()` is the conservative choice for transactional reads + writes; using `getWrite()` uniformly across `tx.get(...).get(...)` and `runTransaction(...).get(...)` keeps the failure window aligned with the longest expected operation in the lambda. If you want tighter read timeouts, swap individual `tx.get(...).get(timeoutProperties.getWrite(), ...)` lines to `getRead()`. The `FirestoreTimeoutProperties` injection is added by Task 5 Step 8. The deprecated 2-arg `updateUserRole(uid, newRole)` is **removed** — production callers must pass the actor.

- [ ] **Step 4: Update the controller**

In `UserController.updateUserRole`:

```java
@PatchMapping("/{uid}/role")
@PreAuthorize("hasRole('ADMIN')")
public ResponseEntity<User> updateRole(
        @PathVariable String uid,
        @RequestBody Map<String, String> body,
        @AuthenticationPrincipal FirebaseUserDetails actor) throws Exception {
    if (actor == null) {
        return ResponseEntity.status(401).build();
    }
    String role = body.get("role");
    return ResponseEntity.ok(authService.updateUserRoleAsActor(uid, role, actor.getUid()));
}
```

- [ ] **Step 5: Integration test (emulator-backed)**

```java
package com.albunyaan.tube.integration;

import com.albunyaan.tube.exception.LastAdminException;
import com.albunyaan.tube.model.User;
import com.albunyaan.tube.repository.UserRepository;
import com.albunyaan.tube.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.*;

class AuthServiceLastAdminIntegrationTest extends BaseIntegrationTest {

    @Autowired AuthService authService;
    @Autowired UserRepository userRepo;

    @Test void demoteLastAdmin_throws() throws Exception {
        String soloAdmin = createEmulatorUser("solo@t", "admin");
        String otherAdmin = createEmulatorUser("a2@t", "admin"); // 2 admins total

        // Block otherAdmin first so soloAdmin is the only ACTIVE admin
        authService.blockUser(otherAdmin, soloAdmin, "test-setup");

        assertThrows(LastAdminException.class,
            () -> authService.updateUserRoleAsActor(soloAdmin, "moderator", soloAdmin));
    }

    @Test void demoteNonLastAdmin_succeeds() throws Exception {
        String adminA = createEmulatorUser("aA@t", "admin");
        String adminB = createEmulatorUser("aB@t", "admin");

        authService.updateUserRoleAsActor(adminA, "moderator", adminB);

        assertEquals("moderator", userRepo.findByUid(adminA).orElseThrow().getRole());
    }

    @Test void selfDemoteAdmin_throws_evenWithMultipleAdmins() throws Exception {
        String adminA = createEmulatorUser("a1@t", "admin");
        createEmulatorUser("a2@t", "admin");
        createEmulatorUser("a3@t", "admin");
        createEmulatorUser("a4@t", "admin");
        createEmulatorUser("a5@t", "admin");  // 5 admins total

        assertThrows(LastAdminException.class,
            () -> authService.updateUserRoleAsActor(adminA, "moderator", adminA));
    }
}
```

- [ ] **Step 6: Run tests**

```bash
cd backend && ./gradlew test -Pintegration=true \
  --tests "com.albunyaan.tube.integration.AuthServiceLastAdminIntegrationTest"
```

Expected: 3 passing.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/albunyaan/tube/service/AuthService.java \
        backend/src/main/java/com/albunyaan/tube/controller/UserController.java \
        backend/src/test/java/com/albunyaan/tube/integration/AuthServiceLastAdminIntegrationTest.java
git commit -m "[FEAT]: updateUserRoleAsActor with transactional last-admin guard"
```

---

## Task 9: `AuditLogService` builders + repo accessor + `AuditLog.of` factory

**Files:**
- Modify: `backend/src/main/java/com/albunyaan/tube/service/AuditLogService.java` — add builders
- Modify: `backend/src/main/java/com/albunyaan/tube/repository/AuditLogRepository.java` — expose `auditLogsCollection()`
- Modify: `backend/src/main/java/com/albunyaan/tube/model/AuditLog.java` — add static `of(...)` factory
- Test: `backend/src/test/java/com/albunyaan/tube/service/AuditLogServiceBuildersTest.java` (create)

**This task replaces the previous async-with-swallow design.** The Spec alignment Canonical lifecycle method calls `tx.set(auditLogRepository.auditLogsCollection().document(), AuditLog.of(...))` inside the same `runTransaction` as the user write. The pieces — collection accessor and static factory — must exist before Tasks 6/7/8 can compile, so this task may be reordered to land just after Task 5.

**Existing async methods are untouched.** `log`, `logApproval`, `logRejection` continue to serve approval/rejection flows that do not require transactional audit consistency. Plan A only adds the builders for lifecycle ops.

- [ ] **Step 1: Expose `auditLogsCollection()` on `AuditLogRepository`**

The existing `AuditLogRepository` keeps its `CollectionReference` private (only accessible via private `getCollection()`). The lifecycle transactions need direct access to call `tx.set(collection.document(), audit)`:

```java
// AuditLogRepository.java — add:
private static final String COLLECTION_NAME = "audit_logs";  // existing — confirm name

public CollectionReference auditLogsCollection() {
    return firestore.collection(COLLECTION_NAME);
}
```

The collection name is the existing constant in the repo (`audit_logs`, snake_case). Do not introduce a competing camelCase variant.

- [ ] **Step 2: Add static `AuditLog.of(...)` factory**

`backend/src/main/java/com/albunyaan/tube/model/AuditLog.java` already has a 4-arg constructor and `addDetail(key, value)`. Add a static factory that the canonical lifecycle method uses:

```java
public static AuditLog of(String action, String entityType, String entityId,
                          String actorUid, Map<String, Object> details) {
    AuditLog log = new AuditLog();
    log.setAction(action);
    log.setEntityType(entityType);
    log.setEntityId(entityId);
    log.setActorUid(actorUid);
    log.setTimestamp(com.google.cloud.Timestamp.now());
    if (details != null) {
        details.forEach(log::addDetail);
    }
    return log;
}
```

- [ ] **Step 3: Add lifecycle builders to `AuditLogService`**

These return an `AuditLog` instance — they do **not** persist. The `tx.set(...)` write happens inside `AuthService`'s transaction.

```java
public AuditLog buildBlock(String targetUid, String actorUid, String reason) {
    return AuditLog.of("USER_BLOCKED", "user", targetUid, actorUid,
        reason != null ? Map.of("reason", reason) : Map.of());
}

public AuditLog buildUnblock(String targetUid, String actorUid) {
    return AuditLog.of("USER_UNBLOCKED", "user", targetUid, actorUid, Map.of());
}

public AuditLog buildSoftDelete(String targetUid, String actorUid, String reason) {
    return AuditLog.of("USER_SOFT_DELETED", "user", targetUid, actorUid,
        reason != null ? Map.of("reason", reason) : Map.of());
}

public AuditLog buildRecover(String targetUid, String actorUid) {
    return AuditLog.of("USER_RECOVERED", "user", targetUid, actorUid, Map.of());
}

public AuditLog buildRoleChange(String targetUid, String actorUid,
                                String fromRole, String toRole) {
    return AuditLog.of("USER_ROLE_CHANGED", "user", targetUid, actorUid,
        Map.of("fromRole", fromRole, "toRole", toRole));
}

// Synchronous summary audit for the backfill migration (Task 12)
public AuditLog buildBackfillRun(int scanned, int updated, String actorUid) {
    return AuditLog.of("USER_BACKFILL_RUN", "system", "user-backfill", actorUid,
        Map.of("scanned", scanned, "updated", updated));
}
```

The existing `@Async log`, `logApproval`, `logRejection`, `logSystem` methods are **NOT modified**. They keep their async + exception-swallow behavior because approval/rejection flows do not require transactional audit consistency.

- [ ] **Step 4: Write unit tests for the builders**

```java
package com.albunyaan.tube.service;

import com.albunyaan.tube.model.AuditLog;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AuditLogServiceBuildersTest {

    private final AuditLogService svc = new AuditLogService(null /* repo not used by builders */);

    @Test void buildBlock_setsActionAndReason() {
        AuditLog log = svc.buildBlock("u1", "actor", "spam");
        assertEquals("USER_BLOCKED", log.getAction());
        assertEquals("user", log.getEntityType());
        assertEquals("u1", log.getEntityId());
        assertEquals("actor", log.getActorUid());
        assertEquals("spam", log.getDetails().get("reason"));
        assertNotNull(log.getTimestamp());
    }

    @Test void buildUnblock_omitsReason() {
        AuditLog log = svc.buildUnblock("u1", "actor");
        assertEquals("USER_UNBLOCKED", log.getAction());
        assertNull(log.getDetails().get("reason"));
    }

    @Test void buildSoftDelete_setsActionAndReason() {
        AuditLog log = svc.buildSoftDelete("u1", "actor", "user-request");
        assertEquals("USER_SOFT_DELETED", log.getAction());
        assertEquals("user-request", log.getDetails().get("reason"));
    }

    @Test void buildRecover_simple() {
        AuditLog log = svc.buildRecover("u1", "actor");
        assertEquals("USER_RECOVERED", log.getAction());
    }

    @Test void buildRoleChange_capturesFromTo() {
        AuditLog log = svc.buildRoleChange("u1", "actor", "moderator", "admin");
        assertEquals("USER_ROLE_CHANGED", log.getAction());
        assertEquals("moderator", log.getDetails().get("fromRole"));
        assertEquals("admin", log.getDetails().get("toRole"));
    }
}
```

- [ ] **Step 5: Run tests**

```bash
cd backend && ./gradlew test --tests "com.albunyaan.tube.service.AuditLogServiceBuildersTest"
cd backend && ./gradlew test --tests "com.albunyaan.tube.model.AuditLogTest"  # if exists
```

Expected: 5 builder tests passing. Existing `log` / `logApproval` / `logRejection` tests untouched.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/albunyaan/tube/model/AuditLog.java \
        backend/src/main/java/com/albunyaan/tube/repository/AuditLogRepository.java \
        backend/src/main/java/com/albunyaan/tube/service/AuditLogService.java \
        backend/src/test/java/com/albunyaan/tube/service/AuditLogServiceBuildersTest.java
git commit -m "[FEAT]: audit log builders + collection accessor for transactional lifecycle audit"
```

---

## Task 10: `UserController` route map + admin-only `@PreAuthorize`

**Files:**
- Modify: `backend/src/main/java/com/albunyaan/tube/controller/UserController.java`
- Test: `backend/src/test/java/com/albunyaan/tube/integration/UserControllerLifecycleIntegrationTest.java` (create)

- [ ] **Step 1: Confirm the route map**

The endpoints touched in this plan, all under `/api/admin/users` unless noted:

| Method | Path | Purpose | Auth | Notes |
|--------|------|---------|------|-------|
| `GET` | `/api/admin/users` | List users | ADMIN | **Filters `status="deleted"` by default**; `?includeDeleted=true` opts in (D3) |
| `GET` | `/api/admin/users/{uid}` | Show one user | ADMIN | Unchanged |
| `PATCH` | `/api/admin/users/{uid}/role` | Change role | ADMIN | Routes to `updateUserRoleAsActor` (Task 8) |
| `DELETE` | `/api/admin/users/{uid}` | Soft-delete | ADMIN | Body-less; `?reason=...` query param |
| `POST` | `/api/admin/users/{uid}/recover` | Recover | ADMIN | |
| `POST` | `/api/admin/users/{uid}/block` | Block (body: `{reason}`) | ADMIN | |
| `POST` | `/api/admin/users/{uid}/unblock` | Unblock | ADMIN | |
| `POST` | `/api/admin/migrations/user-backfill` | Run migration | ADMIN | Gated by `app.migrations.user-backfill.enabled` (D7) |

- [ ] **Step 1b: Update `GET /api/admin/users` to filter soft-deleted by default (D3)**

In `UserController.listUsers(...)` (or whatever the existing `GET /api/admin/users` handler is named):

```java
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<User>> listUsers(
            @RequestParam(required = false, defaultValue = "false") boolean includeDeleted,
            @RequestParam(required = false) String role,
            // ... other existing query params (page, size, etc.) ...
    ) throws Exception {
        List<User> users = userRepository.findAll(includeDeleted, role, /* ... */);
        return ResponseEntity.ok(users);
    }
```

`UserRepository.findAll(includeDeleted, ...)` uses `whereIn("status", List.of("active","blocked","pending_profile"))` when `includeDeleted == false`. **Do not use `whereNotEqualTo("status","deleted")`** — Firestore's `!=` predicate excludes documents where the field is missing or null entirely. Pre-backfill legacy user docs may have `status: null` and would silently disappear from admin listings. The `whereIn` whitelist is exhaustive over the four valid live statuses; anything else (including null) is invisible until the migration normalizes it.

**Deploy ordering caveat:** Even with `whereIn`, the filter is only safe to deploy AFTER the backfill has run. Do not deploy this controller change to production until: (1) Task 11 indexes are live, (2) Task 12 migration has been run via `POST /api/admin/migrations/user-backfill`, (3) admin spot-check confirms no pre-backfill users still have null `status` (`firestore.collection("users").whereNotEqualTo("status", "active").get()` should return only blocked/deleted/pending_profile docs). Document this in the deploy runbook.

Add an integration test in `UserControllerLifecycleIntegrationTest` (or a new test class):

```java
@Test void listUsers_excludesDeletedByDefault() throws Exception {
    String adminUid = createEmulatorUser("a@t", "admin");
    String live = createEmulatorUser("live@t", "moderator");
    String dead = createEmulatorUser("dead@t", "moderator");
    String adminToken = mintIdToken(adminUid);

    // Soft-delete one
    mvc.perform(delete("/api/admin/users/" + dead + "?reason=test")
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isNoContent());

    // Default: deleted user excluded
    mvc.perform(get("/api/admin/users")
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.uid=='" + dead + "')]").doesNotExist());

    // Opt-in: deleted user included
    mvc.perform(get("/api/admin/users?includeDeleted=true")
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.uid=='" + dead + "')]").exists());
}
```

- [ ] **Step 1c: Add `POST /api/admin/migrations/user-backfill` endpoint (D7)**

In a new `MigrationController` (or appended to `UserController` if conventions allow):

```java
@RestController
@RequestMapping("/api/admin/migrations")
public class MigrationController {

    private final UserBackfillMigration migration;

    @Value("${app.migrations.user-backfill.enabled:false}")
    private boolean backfillEnabled;

    public MigrationController(UserBackfillMigration migration) {
        this.migration = migration;
    }

    @PostMapping("/user-backfill")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> runUserBackfill(
            @AuthenticationPrincipal FirebaseUserDetails actor) throws Exception {
        if (actor == null) {
            return ResponseEntity.status(401).body(Map.of("code", "UNAUTHENTICATED"));
        }
        if (!backfillEnabled) {
            return ResponseEntity.status(503).body(Map.of(
                "code", "MIGRATION_DISABLED",
                "hint", "Set app.migrations.user-backfill.enabled=true in the active profile."));
        }
        try {
            UserBackfillMigration.RunSummary summary = migration.run(actor.getUid());
            logger.info("Migration user-backfill triggered by uid={} scanned={} updated={} skipped={}",
                actor.getUid(), summary.scanned(), summary.updated(), summary.skipped());
            return ResponseEntity.ok(Map.of(
                "scanned", summary.scanned(),
                "updated", summary.updated(),
                "skipped", summary.skipped(),
                "startedAt", summary.startedAt(),
                "completedAt", summary.completedAt()));
        } catch (IllegalStateException e) {
            // Lock CAS failed — another run in progress
            return ResponseEntity.status(409).body(Map.of(
                "code", "MIGRATION_RUNNING",
                "message", e.getMessage()));
        }
    }
}
```

Integration test (Firebase Emulator):

```java
@Test void migrationEndpoint_disabled_returns503() throws Exception {
    String adminToken = mintIdToken(createEmulatorUser("a@t", "admin"));
    // app.migrations.user-backfill.enabled defaults to false
    mvc.perform(post("/api/admin/migrations/user-backfill")
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("MIGRATION_DISABLED"));
}

@Test void migrationEndpoint_enabled_runsAndReturnsSummary() throws Exception {
    // Use @TestPropertySource("app.migrations.user-backfill.enabled=true") on the test class
    String adminToken = mintIdToken(createEmulatorUser("a@t", "admin"));
    seedLegacyUserDocs(); // 3 docs missing status field
    mvc.perform(post("/api/admin/migrations/user-backfill")
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.updated").value(3));
}

@Test void migrationEndpoint_nonAdmin_returns403() throws Exception {
    String modToken = mintIdToken(createEmulatorUser("m@t", "moderator"));
    mvc.perform(post("/api/admin/migrations/user-backfill")
            .header("Authorization", "Bearer " + modToken))
        .andExpect(status().isForbidden());
}
```

(The migration controller and tests reuse `BaseIntegrationTest` helpers; the migration class itself is created in Task 12.)

- [ ] **Step 2: Write integration test**

```java
package com.albunyaan.tube.integration;

import com.albunyaan.tube.model.User;
import com.albunyaan.tube.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class UserControllerLifecycleIntegrationTest extends BaseIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository userRepository;

    @Test void admin_canBlockAndUnblockUser() throws Exception {
        String adminUid = createEmulatorUser("admin@t", "admin");
        String targetUid = createEmulatorUser("victim@t", "moderator");
        String adminToken = mintIdToken(adminUid);

        mvc.perform(post("/api/admin/users/" + targetUid + "/block")
                .header("Authorization", "Bearer " + adminToken)
                .contentType("application/json")
                .content("{\"reason\":\"spam\"}"))
            .andExpect(status().isNoContent());

        User after = userRepository.findByUid(targetUid).orElseThrow();
        org.junit.jupiter.api.Assertions.assertTrue(after.isBlocked());
        org.junit.jupiter.api.Assertions.assertEquals("spam", after.getBlockReason());

        mvc.perform(post("/api/admin/users/" + targetUid + "/unblock")
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isNoContent());

        User reactivated = userRepository.findByUid(targetUid).orElseThrow();
        org.junit.jupiter.api.Assertions.assertTrue(reactivated.isActive());
    }

    @Test void admin_cannotBlockSelf_returns409() throws Exception {
        String adminUid = createEmulatorUser("solo-admin@t", "admin");
        String adminToken = mintIdToken(adminUid);

        mvc.perform(post("/api/admin/users/" + adminUid + "/block")
                .header("Authorization", "Bearer " + adminToken)
                .contentType("application/json")
                .content("{\"reason\":\"oops\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("LAST_ADMIN_PROTECTED"));
    }

    @Test void moderator_cannotCallAdminEndpoints() throws Exception {
        String adminUid = createEmulatorUser("a@t", "admin");
        String modUid = createEmulatorUser("m@t", "moderator");
        String modToken = mintIdToken(modUid);

        mvc.perform(post("/api/admin/users/" + adminUid + "/block")
                .header("Authorization", "Bearer " + modToken)
                .contentType("application/json")
                .content("{\"reason\":\"x\"}"))
            .andExpect(status().isForbidden());
    }

    @Test void admin_canSoftDeleteAndRecover() throws Exception {
        String adminUid = createEmulatorUser("a2@t", "admin");
        String adminUid2 = createEmulatorUser("a3@t", "admin"); // ensure not last
        String targetUid = createEmulatorUser("u@t", "moderator");
        String adminToken = mintIdToken(adminUid);

        mvc.perform(delete("/api/admin/users/" + targetUid + "?reason=test")
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isNoContent());

        org.junit.jupiter.api.Assertions.assertTrue(
            userRepository.findByUid(targetUid).orElseThrow().isDeleted());

        mvc.perform(post("/api/admin/users/" + targetUid + "/recover")
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isNoContent());

        org.junit.jupiter.api.Assertions.assertTrue(
            userRepository.findByUid(targetUid).orElseThrow().isActive());
    }
}
```

- [ ] **Step 3: Run integration tests**

```bash
cd backend && ./gradlew test -Pintegration=true \
  --tests "com.albunyaan.tube.integration.UserControllerLifecycleIntegrationTest"
```

Expected: 4 passing.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/albunyaan/tube/controller/UserController.java \
        backend/src/test/java/com/albunyaan/tube/integration/UserControllerLifecycleIntegrationTest.java
git commit -m "[TEST]: integration coverage for admin block/unblock/soft-delete/recover"
```

---

## Task 11: Firestore rules + composite index

**Files:**
- Modify: `backend/src/main/resources/firestore.rules`
- Modify: `backend/src/main/resources/firestore.indexes.json`

- [ ] **Step 1: Add `isUser()` helper and tighten user-doc writes**

In `firestore.rules`, replace the existing `match /users/{userId}` block:

```
    function isUser() {
      return isAuthenticated() &&
             exists(/databases/$(database)/documents/users/$(request.auth.uid));
    }

    function isSelf(userId) {
      return isAuthenticated() && request.auth.uid == userId;
    }

    function isActiveAccount() {
      return isAuthenticated() &&
             get(/databases/$(database)/documents/users/$(request.auth.uid)).data.status == 'active';
    }

    match /users/{userId} {
      // Self read; admin read; everyone else denied
      allow read: if isSelf(userId) || isAdmin();
      // Self can update only specific fields (Plan C will tighten); admin writes anything
      // Plan A: admin-only writes — Plan C will narrow self-write to profile sub-fields
      allow write: if isAdmin();
    }
```

- [ ] **Step 2: Add composite index for `(role, status)`**

In `backend/src/main/resources/firestore.indexes.json`, add (within the `indexes` array):

```json
{
  "collectionGroup": "users",
  "queryScope": "COLLECTION",
  "fields": [
    { "fieldPath": "role", "order": "ASCENDING" },
    { "fieldPath": "status", "order": "ASCENDING" }
  ]
}
```

- [ ] **Step 3: Deploy rules + indexes against the emulator and verify**

If the project uses `firebase deploy --only firestore:rules,firestore:indexes` for production, the emulator-based integration tests will pick up `firestore.rules` automatically when started. Confirm:

```bash
cd backend && ./gradlew test -Pintegration=true \
  --tests "com.albunyaan.tube.integration.RbacWorkflowIntegrationTest"
```

Expected: green (no rule regression).

- [ ] **Step 4: Add a rules-specific test if one doesn't exist yet**

If `RbacWorkflowIntegrationTest` does not already exercise `match /users` with the new helpers, add a focused test covering: regular user reads only their own doc; admin reads any; moderator denied other users' docs. (Skip if existing tests already cover this matrix.)

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/resources/firestore.rules \
        backend/src/main/resources/firestore.indexes.json
git commit -m "[FEAT]: firestore rules — isUser() helper and tightened /users access; add (role,status) index"
```

---

## Task 12: Backfill migration

**Files:**
- Create: `backend/src/main/java/com/albunyaan/tube/util/UserBackfillMigration.java`
- Test: `backend/src/test/java/com/albunyaan/tube/util/UserBackfillMigrationTest.java` (create)
- Modify: `backend/src/main/resources/application.yml` (or `application.properties`) — feature flag

> **Required adjustments per Spec alignment (D7) and review findings:**
>
> 1. **Remove `runIfEnabled(boolean)`** entirely. The flag check lives in the controller (Task 10 Step 1c).
>
> 2. **Change `run()` to return a `RunSummary` record:**
>
>    ```java
>    public record RunSummary(int scanned, int updated, int skipped, String startedAt, String completedAt) {}
>
>    public RunSummary run() throws Exception {
>        // ... existing pagination + normalize loop ...
>        return new RunSummary(scanned, updated, skipped, started, completed);
>    }
>    ```
>
> 3. **Add a Firestore-backed run lock to prevent concurrent runs.** Reuse `SystemSettingsRepository` (existing at `backend/src/main/java/com/albunyaan/tube/repository/SystemSettingsRepository.java`):
>
>    ```java
>    public RunSummary run() throws Exception {
>        DocumentReference lockRef = firestore.collection("system_settings").document("migration_user_backfill");
>
>        // Atomic CAS: claim lock or fail
>        Boolean claimed = firestore.runTransaction(tx -> {
>            DocumentSnapshot snap = tx.get(lockRef).get();
>            if (snap.exists() && Boolean.TRUE.equals(snap.getBoolean("running"))) {
>                return false;
>            }
>            tx.set(lockRef, Map.of(
>                "running", true,
>                "startedAt", Timestamp.now(),
>                "claimedBy", InetAddress.getLocalHost().getHostName()));
>            return true;
>        }).get();
>
>        if (!claimed) {
>            throw new IllegalStateException(
>                "Backfill is already running. Wait for completion or clear system_settings/migration_user_backfill.");
>        }
>
>        try {
>            // ... existing pagination + normalize loop ...
>            return summary;
>        } finally {
>            // Always release the lock
>            lockRef.set(Map.of("running", false, "completedAt", Timestamp.now()), SetOptions.merge()).get();
>        }
>    }
>    ```
>
>    The controller maps `IllegalStateException` to 409 `MIGRATION_RUNNING`. Crash recovery: a stuck lock (running == true but no host process) requires manual clear via Firestore console — document this in the deploy runbook. Two simultaneous admin clicks now race only on the lock CAS; loser sees 409.
>
> 4. **Per-user audit `USER_BACKFILLED` stays `@Async`** (existing `logSystem` semantics). Volume-driven decision: synchronous transactional audit on N user docs would require N transactions, multiplying cost. Per-user audit is best-effort; the synchronous summary `USER_BACKFILL_RUN` (next item) is the durable record. **Open spec deviation:** flag this in the design spec follow-up — D5's "lifecycle audit is synchronous" applies to admin-triggered single-user lifecycle ops; backfill is a different operational class.
>
> 5. **Synchronous summary audit `USER_BACKFILL_RUN`.** Add a `buildBackfillRun(int scanned, int updated, String actorUid)` builder to `AuditLogService` (extension of Task 9). Migration writes it transactionally inside the lock-release CAS so a successful run guarantees a summary row:
>
>    ```java
>    } finally {
>        firestore.runTransaction(tx -> {
>            tx.set(lockRef, Map.of("running", false, "completedAt", Timestamp.now()), SetOptions.merge());
>            tx.set(auditLogRepository.auditLogsCollection().document(),
>                auditLogService.buildBackfillRun(scanned, updated, actorUid));
>            return null;
>        }).get();
>    }
>    ```
>
> 6. **Pass `actorUid` to `run()`** so the synchronous summary audit captures who triggered it. Controller signature becomes `migration.run(actor.getUid())`.
>
> 7. **Drop the `disabledFlag_skipsMigration` test** in `UserBackfillMigrationTest`. The `migrationEndpoint_disabled_returns503` test in Task 10 covers the disabled-flag path at the controller layer where it now belongs.
>
> 8. **No `ApplicationReadyEvent` listener.** The migration class is a `@Component` bean only — it does not auto-run at boot.
>
> 9. **Per-user `logSystem` arg shape.** The existing `logSystem(action, entityType, entityId, actorDescription)` signature is 4-arg. The original Task 12 code passed a formatted summary string as `actorDescription`, which is incorrect. The migration now uses `logSystem("USER_BACKFILLED", "user", u.getUid(), "UserBackfillMigration")` for per-user audit (no detail map; the change facts are derivable from the user-doc state). The summary detail (scanned/updated counts) lives in the synchronous `buildBackfillRun(...)` audit, not in `logSystem`.
>
> 10. **Re-issue lowercase Firebase Auth custom claims for legacy users.** Existing user docs in production were created by `AuthService.createUser` / `updateUserRole` BEFORE the lowercase fix in Task 5 Step 9. Their Firebase Auth custom claims store `role: "ADMIN"` or `"MODERATOR"` (uppercase). The backend filter tolerates both via `toLowerCase(Locale.ROOT)`, but Plan B's Android client expects lowercase. The migration adds a second pass (after the Firestore normalize loop) that calls `firebaseAuth.setCustomUserClaims(uid, Map.of("role", role.toLowerCase(Locale.ROOT)))` for every user. The call is idempotent — already-lowercase claims re-set to the same value, no behavior change. This is added in Step 3's `run(...)` method body before the `finally` block:
>
>     ```java
>     // After the user-doc normalize loop, re-issue lowercase claims
>     for (User u : userRepository.findAll(true /* includeDeleted */, null)) {
>         if (u.getRole() != null && !u.getRole().isBlank()) {
>             firebaseAuth.setCustomUserClaims(u.getUid(),
>                 Map.of("role", u.getRole().toLowerCase(Locale.ROOT)));
>         }
>     }
>     ```
>
>     `findAll(true, null)` returns all users including deleted (so we re-issue claims even on deleted accounts — defensive; if they're recovered, claims are correct). This pass is O(n) Firebase Auth calls; for production scale of <1000 users it's a few seconds. Add `firebaseAuth` to the constructor dependency list.

- [ ] **Step 1: Define the migration's contract**

The migration **only** normalizes data; it must be:
- Idempotent (running twice is a no-op).
- Bounded (paginates so it never OOMs on large datasets).
- Audit-logged (one entry per user changed, plus a summary entry).
- Feature-flagged (`app.migrations.user-backfill.enabled=false` by default).

Specific normalizations:
- Missing `status` → `"active"`
- Legacy status `"inactive"` → `"blocked"` with `blockReason: "legacy-inactive"` and `blockedAt = createdAt` (or now if missing)
- Missing `createdAt` → now
- Missing `updatedAt` → `createdAt`
- Missing `role` → `"user"` (defensive — legacy admin docs always have role set, but new flow may produce uid-only docs)

The migration **does not**:
- Change role values (no case conversion — wire format stays lowercase).
- Add profile fields (Plan C).
- Touch the audit log collection.

- [ ] **Step 2: Write failing tests**

```java
package com.albunyaan.tube.util;

import com.albunyaan.tube.integration.BaseIntegrationTest;
import com.albunyaan.tube.model.User;
import com.albunyaan.tube.repository.UserRepository;
import com.google.cloud.Timestamp;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.*;

class UserBackfillMigrationTest extends BaseIntegrationTest {

    @Autowired UserBackfillMigration migration;
    @Autowired UserRepository repo;

    @Test void backfillSetsMissingDefaults() throws Exception {
        User legacy = new User();
        legacy.setUid("legacy-1");
        legacy.setEmail("l1@t");
        legacy.setRole("moderator");
        legacy.setStatus(null);
        legacy.setCreatedAt(null);
        legacy.setUpdatedAt(null);
        repo.saveRaw(legacy);

        UserBackfillMigration.RunSummary summary = migration.run("test-actor");

        User after = repo.findByUid("legacy-1").orElseThrow();
        assertEquals("active", after.getStatus());
        assertNotNull(after.getCreatedAt());
        assertNotNull(after.getUpdatedAt());
        assertEquals(1, summary.updated());
    }

    @Test void inactiveStatusBecomesBlockedWithReason() throws Exception {
        User legacy = new User();
        legacy.setUid("legacy-2");
        legacy.setEmail("l2@t");
        legacy.setRole("moderator");
        legacy.setStatus("inactive");
        legacy.setCreatedAt(Timestamp.now());
        repo.saveRaw(legacy);

        migration.run("test-actor");

        User after = repo.findByUid("legacy-2").orElseThrow();
        assertEquals("blocked", after.getStatus());
        assertEquals("legacy-inactive", after.getBlockReason());
        assertNotNull(after.getBlockedAt());
    }

    @Test void runningTwiceIsIdempotent() throws Exception {
        User legacy = new User();
        legacy.setUid("legacy-3");
        legacy.setRole("user");
        legacy.setStatus(null);
        repo.saveRaw(legacy);

        migration.run("test-actor");
        Timestamp updatedAfterFirst = repo.findByUid("legacy-3").orElseThrow().getUpdatedAt();

        UserBackfillMigration.RunSummary second = migration.run("test-actor");
        Timestamp updatedAfterSecond = repo.findByUid("legacy-3").orElseThrow().getUpdatedAt();

        assertEquals(updatedAfterFirst, updatedAfterSecond,
            "Idempotent run must not touch already-normalized docs");
        assertEquals(0, second.updated(),
            "Second run scans but updates nothing");
    }

    @Test void concurrentRun_throwsMigrationRunning() throws Exception {
        // Pre-claim the lock to simulate a parallel run
        firestore.collection("system_settings").document("migration_user_backfill")
            .set(java.util.Map.of("running", true, "claimedBy", "other-host")).get();

        assertThrows(IllegalStateException.class,
            () -> migration.run("test-actor"));

        // Cleanup
        firestore.collection("system_settings").document("migration_user_backfill").delete().get();
    }

    @Test void summaryAuditWritten() throws Exception {
        // Seed one legacy user
        User legacy = new User();
        legacy.setUid("legacy-summary");
        legacy.setStatus(null);
        repo.saveRaw(legacy);

        UserBackfillMigration.RunSummary summary = migration.run("test-actor");

        assertEquals(1, summary.updated());
        // Verify USER_BACKFILL_RUN audit doc exists
        QuerySnapshot audits = firestore.collection("audit_logs")
            .whereEqualTo("action", "USER_BACKFILL_RUN")
            .whereEqualTo("entityId", "user-backfill")
            .get().get();
        assertTrue(audits.size() >= 1);
    }
}
```

The disabled-flag test is **NOT** in this class — it lives in `Task 10`'s `MigrationControllerIntegrationTest` (the flag check is at the controller layer, not in the migration class). `saveRaw` is a thin helper on `UserRepository` that bypasses the model's defaulting constructor; implement using a direct Firestore `set` of a `Map<String,Object>`.

- [ ] **Step 3: Implement migration**

```java
package com.albunyaan.tube.util;

import com.albunyaan.tube.model.AuditLog;
import com.albunyaan.tube.model.User;
import com.albunyaan.tube.model.UserStatus;
import com.albunyaan.tube.repository.AuditLogRepository;
import com.albunyaan.tube.repository.UserRepository;
import com.albunyaan.tube.service.AuditLogService;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.SetOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.List;
import java.util.Map;

@Component
public class UserBackfillMigration {

    private static final Logger logger = LoggerFactory.getLogger(UserBackfillMigration.class);
    private static final int BATCH_SIZE = 200;

    public record RunSummary(int scanned, int updated, int skipped,
                             String startedAt, String completedAt) {}

    private final Firestore firestore;
    private final FirebaseAuth firebaseAuth;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;
    private final AuditLogRepository auditLogRepository;

    public UserBackfillMigration(Firestore firestore,
                                 FirebaseAuth firebaseAuth,
                                 UserRepository userRepository,
                                 AuditLogService auditLogService,
                                 AuditLogRepository auditLogRepository) {
        this.firestore = firestore;
        this.firebaseAuth = firebaseAuth;
        this.userRepository = userRepository;
        this.auditLogService = auditLogService;
        this.auditLogRepository = auditLogRepository;
    }

    public RunSummary run(String actorUid) throws Exception {
        DocumentReference lockRef = firestore.collection("system_settings")
            .document("migration_user_backfill");

        // Atomic CAS: claim lock or fail
        boolean claimed = firestore.runTransaction(tx -> {
            DocumentSnapshot snap = tx.get(lockRef).get();
            if (snap.exists() && Boolean.TRUE.equals(snap.getBoolean("running"))) {
                return false;
            }
            tx.set(lockRef, Map.of(
                "running", true,
                "startedAt", Timestamp.now(),
                "claimedBy", InetAddress.getLocalHost().getHostName(),
                "claimedByUid", actorUid));
            return true;
        }).get();

        if (!claimed) {
            throw new IllegalStateException(
                "Backfill is already running. Wait for completion or clear "
                + "system_settings/migration_user_backfill if the previous run crashed.");
        }

        String startedAt = Timestamp.now().toString();
        int scanned = 0;
        int updated = 0;
        int skipped = 0;
        String cursor = null;

        try {
            while (true) {
                List<User> page = userRepository.findAfter(cursor, BATCH_SIZE);
                if (page.isEmpty()) break;

                for (User u : page) {
                    scanned++;
                    if (normalize(u)) {
                        userRepository.save(u);
                        // Per-user audit stays @Async per spec deviation note in Task 12 prologue;
                        // failures are logged + counted at WARN, not surfaced as errors.
                        auditLogService.logSystem("USER_BACKFILLED", "user", u.getUid(),
                            "UserBackfillMigration");
                        updated++;
                    } else {
                        skipped++;
                    }
                }
                cursor = page.get(page.size() - 1).getUid();
                if (page.size() < BATCH_SIZE) break;
            }

            // Re-issue lowercase Firebase Auth custom claims for legacy uppercase claims (D6)
            for (User u : userRepository.findAll(true, null)) {
                if (u.getRole() != null && !u.getRole().isBlank()) {
                    firebaseAuth.setCustomUserClaims(u.getUid(),
                        java.util.Map.of("role", u.getRole().toLowerCase(java.util.Locale.ROOT)));
                }
            }
        } finally {
            // Release lock + write synchronous summary audit in one transaction
            String completedAt = Timestamp.now().toString();
            final int finalScanned = scanned;
            final int finalUpdated = updated;
            firestore.runTransaction(tx -> {
                tx.set(lockRef, Map.of(
                    "running", false,
                    "completedAt", Timestamp.now(),
                    "lastScanned", finalScanned,
                    "lastUpdated", finalUpdated), SetOptions.merge());
                AuditLog summary = auditLogService.buildBackfillRun(
                    finalScanned, finalUpdated, actorUid);
                tx.set(auditLogRepository.auditLogsCollection().document(), summary);
                return null;
            }).get();
        }

        logger.info("UserBackfillMigration: scanned={} updated={} skipped={}",
            scanned, updated, skipped);
        return new RunSummary(scanned, updated, skipped, startedAt, Timestamp.now().toString());
    }

    private boolean normalize(User u) {
        boolean changed = false;
        Timestamp now = Timestamp.now();

        if (u.getStatus() == null) {
            u.setStatus(UserStatus.ACTIVE.getValue());
            changed = true;
        } else if ("inactive".equalsIgnoreCase(u.getStatus())) {
            u.setStatus(UserStatus.BLOCKED.getValue());
            if (u.getBlockReason() == null) u.setBlockReason("legacy-inactive");
            if (u.getBlockedAt() == null) {
                u.setBlockedAt(u.getCreatedAt() != null ? u.getCreatedAt() : now);
            }
            changed = true;
        }

        if (u.getRole() == null || u.getRole().isBlank()) {
            u.setRole("user");
            changed = true;
        }

        if (u.getCreatedAt() == null) {
            u.setCreatedAt(now);
            changed = true;
        }

        if (u.getUpdatedAt() == null) {
            u.setUpdatedAt(u.getCreatedAt());
            changed = true;
        }

        return changed;
    }
}
```

Notes:
- Add `userRepository.findAfter(cursor, batchSize)` if it doesn't exist — uses `orderBy("uid").startAfter(cursor).limit(batchSize)`.
- The `finally` block uses `runTransaction` to atomically release the lock AND write the summary audit. This is the **single canonical lock-release pattern** — there is no other variant elsewhere in this task.
- Stuck-lock recovery: if the JVM crashes between claim and finally, `running` stays `true` until manually cleared via Firestore console or by deleting the doc programmatically. Document this in the deploy runbook (Task 10 deploy-ordering caveat).
- `RunSummary.startedAt` / `completedAt` are exposed via the controller response (Task 10 Step 1c — update the controller response to include all five fields, not just `scanned`/`updated`).

- [ ] **Step 4: Add the feature flag (default off)**

In `backend/src/main/resources/application.yml`:

```yaml
app:
  migrations:
    user-backfill:
      enabled: false
```

- [ ] **Step 5: Run integration tests**

```bash
cd backend && ./gradlew test -Pintegration=true \
  --tests "com.albunyaan.tube.util.UserBackfillMigrationTest"
```

Expected: 4 passing.

- [ ] **Step 6: Plan-time only — do not execute against production data here.**

Operational rollout (record this in `docs/status/PROJECT_STATUS.md` under "Plan A operations"):
1. Deploy code with the flag off.
2. Inspect a sample of user docs in Firestore for shape.
3. Toggle the flag on in dev/staging only; restart; observe audit log entries.
4. Toggle off after staging run.
5. Repeat in prod with a maintenance window.
6. After successful prod run, remove the flag in a follow-up commit.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/albunyaan/tube/util/UserBackfillMigration.java \
        backend/src/main/java/com/albunyaan/tube/repository/UserRepository.java \
        backend/src/main/resources/application.yml \
        backend/src/test/java/com/albunyaan/tube/util/UserBackfillMigrationTest.java
git commit -m "[FEAT]: idempotent user-backfill migration (status defaults; legacy 'inactive' → 'blocked')"
```

---

## Final verification

- [ ] **Step 1: Run the full test matrix**

```bash
cd backend && ./gradlew test
cd backend && ./gradlew test -Pintegration=true
```

Expected: green across the board. Note any pre-existing failing tests; this plan should not have introduced new ones.

- [ ] **Step 2: Smoke-run the backend**

```bash
export GOOGLE_APPLICATION_CREDENTIALS=$HOME/.config/albunyaan/firebase-service-account.json
cd backend && ./gradlew bootRun &
sleep 30
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/actuator/health
kill %1
```

Expected: `200`.

- [ ] **Step 3: Verify no API contract drift for existing clients**

Confirm the existing public Android API at `/api/v1/*` and admin frontend calls at `/api/admin/users` (GET/POST/PATCH for profile, role) still work. Plan A:
- Did **not** add or remove any GET/PATCH endpoint already in use.
- Did add three new POST endpoints (`/{uid}/block`, `/{uid}/unblock`, `/{uid}/recover`).
- Did change `DELETE /{uid}` semantics from hard-delete to soft-delete (status flips, doc is preserved).

If the admin frontend currently expects a deleted user's doc to vanish, Plan F will surface them as "deleted but recoverable." Until Plan F lands, the admin user list still contains soft-deleted users — call this out in `docs/status/PROJECT_STATUS.md`.

- [ ] **Step 4: Update `docs/status/TRUE_PROJECT_STATUS.md` and `docs/status/PROJECT_STATUS.md`**

Per `.claude/rules/workflow.md`, mark `[BACKEND-ACCT-FOUND]` complete and add notes for Plans B–F dependencies on the new fields/exceptions.

- [ ] **Step 5: Final commit (docs)**

```bash
git add docs/status/TRUE_PROJECT_STATUS.md docs/status/PROJECT_STATUS.md
git commit -m "[DOCS]: Plan A complete — backend account foundation"
```

---

## Self-review

**Spec coverage:**
- USER role added → Tasks 1, 5 (allowlist), 11 (rules helper).
- Typed status enum with ACTIVE/BLOCKED/DELETED/PENDING_PROFILE → Task 2.
- Soft-delete + recover → Task 6.
- Block + unblock → Task 7.
- Last-admin guard runs inside `firestore.runTransaction` (D2). The transaction reads the admin-count query and writes the user doc in one atomic block — concurrent demotes/blocks/deletes cannot leave zero admins. The earlier non-transactional `countActiveAdmins()` pattern shown in Task 8 below is **superseded** by the canonical lifecycle method in the **Spec alignment** section.
- Self-block / self-demote / self-delete prevention → Task 8.
- Server-side BLOCKED/DELETED enforcement → Task 5.
- Token revocation on `/api/admin/*` only → Task 5.
- Audit log entries → Task 9, wired in Tasks 6, 7, 8.
- Firestore rules helpers + composite index → Task 11.
- Backfill migration → Task 12.
- COPPA compliance: Plan A does not collect age / demographic data; it only adds the `PENDING_PROFILE` slot. The actual under-13 + parental-consent flow is Plan C's responsibility — Plan A explicitly stops short.

**Placeholder scan:** No "TBD" / "implement later" steps remain. Stub methods in `AuditLogService` (Tasks 6/7) are explicitly replaced in Task 9.

**Type consistency:**
- `softDeleteUser(String uid, String byUid, String reason)` — used in Tasks 6, 8, 10 ✓
- `recoverUser(String uid, String byUid)` — Tasks 6, 10 ✓
- `blockUser(String uid, String byUid, String reason)` — Tasks 7, 8, 10 ✓
- `unblockUser(String uid, String byUid)` — Tasks 7, 10 ✓
- `countActiveAdmins()` returns `long` — Task 8 ✓
- `Role.fromString` / `getValue` — Tasks 1, 3 ✓
- `UserStatus.fromString` / `getValue` — Tasks 2, 3 ✓
- Audit log action strings: `USER_BLOCKED`, `USER_UNBLOCKED`, `USER_SOFT_DELETED`, `USER_RECOVERED`, `USER_ROLE_CHANGED`, `USER_BACKFILLED`, `USER_BACKFILL_RUN` — used consistently in Tasks 9, 12.

**Out of scope (deferred to Plans B–F):**
- Profile fields (name, birthYear/ageBracket, gender, originCountryCode), provider tracking, photoUrl — Plan C.
- `POST /api/account/bootstrap` and account self-service — Plan C.
- Android Firebase Auth integration — Plan B.
- Sync API — Plan D.
- `submittedByDisplayName` / `submittedByEmail` on `PendingApprovalDto`, `REQUEST_CHANGES` status — Plan E.
- Admin frontend UI for block/unblock/recover, paginated user search by status, password-reset email delivery via SES/SendGrid — Plan F.
- Hard-delete (GDPR purge) endpoint — out of scope for Plan A; future task once retention requirements are clear.

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-05-10-plan-a-backend-account-foundation.md`. Two execution options:

**1. Subagent-Driven (recommended)** — I dispatch a fresh subagent per task (Tasks 1–12 above), review between tasks, fast iteration. Each subagent gets only its task's context.

**2. Inline Execution** — Execute tasks in this session using `superpowers:executing-plans`, batch with checkpoints for review.

Which approach?
