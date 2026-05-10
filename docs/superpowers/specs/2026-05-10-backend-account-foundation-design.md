# Backend Account Foundation — Design Spec

**Status:** Approved 2026-05-10 via brainstorming dialogue
**Plan:** [`docs/superpowers/plans/2026-05-10-plan-a-backend-account-foundation.md`](../plans/2026-05-10-plan-a-backend-account-foundation.md)
**P0 prerequisite:** [`docs/superpowers/plans/2026-05-10-p0-firebase-sa-scrub.md`](../plans/2026-05-10-p0-firebase-sa-scrub.md)
**Position in series:** Plan A of six (B–F follow). Other plans depend on the data model, exceptions, and filter behavior locked in here.

---

## 1. Goal

Add a `USER` role and a typed account lifecycle (status, soft-delete, block, recover, last-admin guard, audit logging, server-side status enforcement) to the Spring Boot backend so that subsequent plans (Android auth, account bootstrap, sync, moderator workflow enhancement, admin UI) can build on a hardened foundation. **No new public API surface for mobile/anonymous clients.** All new endpoints live under `/api/admin/*` and require `ADMIN`.

## 2. Why this is a separate plan

The original "user accounts + sync" architecture doc was at least six plans bundled together. Plan A is the single backend foundation — pure data model + service layer + admin endpoints + filter — with zero client-visible change to mobile and zero new behavior for non-admin clients. It is the only plan that can ship without coordinating Android, admin frontend, or product/legal decisions on profile fields. Plans B–F all depend on it.

## 3. Locked design decisions

These were decided in brainstorming (2026-05-10) and are inputs to the implementation plan:

| # | Decision | Rationale |
|---|---|---|
| D1 | Endpoints ship in Plan A. | Service methods are useless until callable; admin frontend in Plan F depends on a stable API surface. |
| D2 | Last-admin guard wraps `count + write` in a Firestore transaction. | Two admins demoting/blocking each other simultaneously cannot leave zero admins. ~30 LOC per guarded action; Firestore handles retries. |
| D3 | `GET /api/admin/users` filters `status=deleted` by default; opt-in via `?includeDeleted=true`. | Existing admin frontend keeps working post-Plan A; deleted users don't appear as ghost rows. |
| D4 | Cache `userRepository.findById(uid)` with Caffeine, 60s TTL. Lifecycle methods evict the entry. | ~30–100ms saved per authenticated request. Worst-case stale window: 60s post-block, with `revokeRefreshTokens` cutting that further. |
| D5 | Audit log writes are **synchronous** and inside the same Firestore transaction as the user write. Audit failure rolls back the admin action and returns 503. | No silent audit gaps; audit becomes a hard dependency for admin lifecycle ops. Acceptable: lifecycle ops are infrequent; audit collection write is cheap. |
| D6 | Wire format stays **lowercase** (`role: "admin"`, `status: "active"`). Type safety added at the Java layer only. | Zero data migration. Existing Firestore rules and JWT custom claim consumers unchanged. |
| D7 | Backfill migration is HTTP-triggered (`POST /api/admin/migrations/user-backfill`), not on app startup. Feature-flagged. | Ops controls timing; boot stays fast; one-off run from a maintenance window. |
| D8 | DELETE `/api/admin/users/{uid}` semantics change from hard-delete to soft-delete (existing route, new behavior). | Old hard-delete had no recoverability; soft-delete gives admin recovery and the recover endpoint becomes meaningful. |
| D9 | Block/soft-delete also `setDisabled(true)` and `revokeRefreshTokens` on Firebase Auth, **outside** the Firestore transaction (Firebase Auth is non-transactional). | Status check is server-authoritative; Firebase Auth disable is defense-in-depth. Both writes are idempotent — re-running the action recovers from any partial failure. |
| D10 | `PENDING_PROFILE` is stored in Plan A but only enforced by Plan E (associate submission endpoints). Plan A's filter does not block it. | Decision 2(d) from product brainstorming: incomplete profile blocks moderator submission only, not general app usage. |

## 4. Scope

### In scope (this plan)

- Typed `Role` and `UserStatus` enums with case-insensitive `fromString` and lowercase `getValue()`.
- New lifecycle fields on `User`: `blockedAt/By/Reason`, `deletedAt/By/Reason`, `recoveredAt/By`, `profileCompletedAt`.
- Three new exceptions: `AccountBlockedException` (403), `AccountDeletedException` (401), `LastAdminException` (409).
- `FirebaseAuthFilter` widening: accept `USER` role; status check; conditional revocation on `/api/admin/*`.
- `AuthService` lifecycle methods: `softDeleteUser`, `recoverUser`, `blockUser`, `unblockUser`, `updateUserRoleAsActor` — all transactional with last-admin guard and synchronous audit.
- `UserRepository` extensions: `countActiveAdmins`, `findAllActive`, `findAfter` (cursor for migration).
- `UserController` route additions: `POST /{uid}/block`, `POST /{uid}/unblock`, `POST /{uid}/recover`. Modified: `DELETE /{uid}` (now soft-delete), `GET /` (filter deleted).
- `AuditLogService` extensions: `logBlock`, `logUnblock`, `logSoftDelete`, `logRecover`, `logRoleChange` — synchronous variants.
- `firestore.rules`: `isUser()` helper; tighten `/users` reads to self-or-admin.
- `firestore.indexes.json`: composite `(role, status)` index.
- `CacheConfig`: new cache name `userStatus` (60s TTL).
- `GlobalExceptionHandler`: handlers for the three new exceptions.
- HTTP-triggered idempotent backfill migration with feature flag and audit.
- Test coverage at unit (Mockito), integration (Firebase emulator), and rules levels.
- Rollout doc (in `docs/status/PROJECT_STATUS.md`) covering ordering and reversibility.

### Out of scope (assigned to other plans)

- **Plan B** — Android Firebase Auth integration (dependencies, `google-services.json`, sign-in screen, token interceptor).
- **Plan C** — Account bootstrap + profile (`/api/account/*` endpoints, profile fields, COPPA under-13 + parental consent flow, onboarding UI, `PENDING_PROFILE` enforcement on submission endpoints, widening Firestore rules to allow self-write of profile sub-fields).
- **Plan D** — Sync engine (sync API, Room v8 migration, anonymous→account merge, archive/availability integration, sync-on-resume, tombstones).
- **Plan E** — Moderator workflow enhancement (`submittedByDisplayName` / `submittedByEmail` on `PendingApprovalDto`, `REQUEST_CHANGES` status + endpoint, per-uid submission rate limit).
- **Plan F** — Admin UI expansion (paginated user search, block/recover UI, `?includeDeleted` toggle, password-reset email delivery via SES/SendGrid, associate submission history view).
- **Future** — Hard-delete (GDPR purge); Microsoft Auth provider wiring; multi-factor auth; account merge on duplicate email; GDPR data export.

## 5. Data model

### `User.java` (modified)

Existing fields preserved as-is. Added (all optional / nullable):

```java
private Timestamp blockedAt;
private String    blockedBy;
private String    blockReason;
private Timestamp deletedAt;
private String    deletedBy;
private String    deleteReason;
private Timestamp recoveredAt;
private String    recoveredBy;
private Timestamp profileCompletedAt;  // populated by Plan C
```

Typed accessors round-trip through existing `String role` / `String status`:

```java
public Role getRoleEnum()             { return Role.fromString(role); }
public void setRoleEnum(Role r)       { this.role = r.getValue(); }
public UserStatus getStatusEnum()     { return UserStatus.fromString(status); }
public void setStatusEnum(UserStatus s) { this.status = s.getValue(); }
```

State helpers (`isBlocked`, `isDeleted`, `isPendingProfile`) plus lifecycle recorders (`recordBlock`, `recordUnblock`, `recordSoftDelete`, `recordRecover`).

### `Role` enum (new)

```
USER       (lowercase value: "user",       rank: 0)
MODERATOR  (lowercase value: "moderator",  rank: 1)
ADMIN      (lowercase value: "admin",      rank: 2)
```

`fromString` is case-insensitive and tolerates null/blank; unknown values default to `USER`. `includesEqualOrAbove(Role)` uses rank for privilege comparison.

### `UserStatus` enum (new)

```
ACTIVE           (value: "active")
BLOCKED          (value: "blocked")
DELETED          (value: "deleted")
PENDING_PROFILE  (value: "pending_profile")
```

`fromString` accepts `"PendingProfile"`, `"pending-profile"`, `"PENDING_PROFILE"`, `"inactive"` (legacy → `ACTIVE` since Plan A's backfill rewrites `inactive` to `blocked`; remaining `inactive` strings are tolerated as `ACTIVE`). `allowsAuth()` returns `true` only for `ACTIVE` and `PENDING_PROFILE`.

### Firestore indexes (additive)

```json
{
  "collectionGroup": "users",
  "queryScope": "COLLECTION",
  "fields": [
    { "fieldPath": "role",   "order": "ASCENDING" },
    { "fieldPath": "status", "order": "ASCENDING" }
  ]
}
```

Required for `countActiveAdmins()` inside the last-admin transaction.

### Firestore rules (additive)

```
function isUser() {
  return isAuthenticated() &&
         exists(/databases/$(database)/documents/users/$(request.auth.uid));
}

function isSelf(userId) {
  return isAuthenticated() && request.auth.uid == userId;
}

match /users/{userId} {
  allow read: if isSelf(userId) || isAdmin();
  allow write: if isAdmin();   // Plan C will widen to self-write of profile sub-fields
}
```

## 6. Auth filter

### Behavior

```
1. Extract Bearer token; reject if absent on protected paths.
2. Verify token:
     - On /api/admin/* → firebaseAuth.verifyIdToken(token, /*checkRevoked*/ true)
     - Elsewhere       → firebaseAuth.verifyIdToken(token)
3. Look up user in cache:
     userRepository.findById(uid)  ← @Cacheable("userStatus", key="#uid"), 60s TTL
4. Status gate:
     - User exists and isDeleted()  → throw AccountDeletedException(uid)
     - User exists and isBlocked()  → throw AccountBlockedException(uid, blockReason)
     - User exists and active/pending → continue
     - User doc absent              → continue (Plan C bootstrap creates on first /api/account/bootstrap)
5. Build SecurityContext as before; ROLE_USER / ROLE_MODERATOR / ROLE_ADMIN.
```

### Allowlist widening

```java
private static final Set<String> VALID_ROLES = Set.of("admin", "moderator", "user");
```

Existing case-normalization preserved.

## 7. Service layer

### Canonical lifecycle method (block; others mirror)

```java
public void blockUser(String uid, String actorUid, String reason) throws Exception {
    firestore.runTransaction(tx -> {
        // Reads first
        DocumentSnapshot doc = tx.get(usersRef.document(uid)).get();
        if (!doc.exists()) throw new IllegalArgumentException("User not found: " + uid);
        User target = doc.toObject(User.class);

        // Last-admin guard
        if (target.isAdmin()) {
            if (uid.equals(actorUid)) throw new LastAdminException("Admins cannot block themselves.");
            QuerySnapshot admins = tx.get(usersRef
                .whereEqualTo("role", "admin")
                .whereEqualTo("status", "active")).get();
            if (admins.size() <= 1) throw new LastAdminException("Cannot block the last active admin.");
        }

        // Mutate in memory
        target.recordBlock(actorUid, reason);

        // Audit entry built in same tx
        AuditLog audit = AuditLog.of("USER_BLOCKED", "user", uid, actorUid,
            Map.of("reason", reason));

        // Writes
        tx.set(usersRef.document(uid), target);
        tx.set(auditLogsRef.document(), audit);
        return null;
    }).get();

    // Side effects outside tx (Firebase Auth is non-transactional)
    firebaseAuth.updateUser(new UserRecord.UpdateRequest(uid).setDisabled(true));
    firebaseAuth.revokeRefreshTokens(uid);

    // Cache eviction
    cacheManager.getCache("userStatus").evict(uid);
}
```

`unblockUser`, `softDeleteUser`, `recoverUser`, `updateUserRoleAsActor` follow the same pattern. Each has its own audit action tag (`USER_UNBLOCKED`, `USER_SOFT_DELETED`, `USER_RECOVERED`, `USER_ROLE_CHANGED`).

### Last-admin guard scope

Triggers on:
- `updateUserRoleAsActor` when target was `ADMIN` and `newRole != ADMIN`.
- `blockUser`, `softDeleteUser` when `target.isAdmin()`.
- Self-action on admin: blocked unconditionally (block/soft-delete/demote).

Does **not** trigger on:
- Promote-to-admin.
- Unblock or recover (always safe).
- Lifecycle changes on non-admins.

### Failure semantics

| Failure | Outcome |
|---|---|
| Last-admin guard | `LastAdminException` → 409. No writes. No Firebase Auth side effects. |
| Firestore tx failure | `ExecutionException` → 503. No partial writes. |
| Audit save inside tx | Whole tx aborts. 503. User state unchanged. |
| Firebase Auth `updateUser` after tx commit | Idempotent — admin re-runs; tx already at desired state, no re-write; Firebase Auth call retries. Filter still rejects affected user via status. |
| `revokeRefreshTokens` after tx commit | Existing tokens valid until ~1h; filter still rejects via status. Idempotent retry. |
| Cache evict | Stale cache for ≤60s. Acceptable. |

## 8. API surface

All under `/api/admin/users` (existing controller). All require `ROLE_ADMIN`.

| Method | Path | Body | Behavior change |
|---|---|---|---|
| `GET` | `/api/admin/users` | — | **Filters `status=deleted`** unless `?includeDeleted=true` |
| `GET` | `/api/admin/users/{uid}` | — | Unchanged — admins always see full record |
| `PATCH` | `/api/admin/users/{uid}/role` | `{role}` | **Now transactional + last-admin guard** |
| `DELETE` | `/api/admin/users/{uid}?reason=…` | — | **Now soft-delete** (was hard-delete) |
| `POST` | `/api/admin/users/{uid}/recover` | — | **New** |
| `POST` | `/api/admin/users/{uid}/block` | `{reason}` | **New** |
| `POST` | `/api/admin/users/{uid}/unblock` | — | **New** |
| `POST` | `/api/admin/migrations/user-backfill` | — | **New** — gated by `app.migrations.user-backfill.enabled` |

### Response codes

| Status | When |
|---|---|
| `204 No Content` | Lifecycle action succeeded |
| `404 Not Found` | Target uid does not exist |
| `409 Conflict` + `LAST_ADMIN_PROTECTED` | Last-admin guard tripped (incl. self-action on admin) |
| `409 Conflict` + `INVALID_TRANSITION` | Recover called on non-deleted; unblock called on non-blocked |
| `503 Service Unavailable` | Firestore tx or audit write failed |
| `403 Forbidden` + `ACCOUNT_BLOCKED` | Caller's own account is blocked (filter-level) |
| `401 Unauthorized` + `ACCOUNT_NOT_FOUND` | Caller's own account is soft-deleted (filter-level) |

## 9. Audit log

Audit entries are written **synchronously** inside the same Firestore transaction as the user-doc write. The existing `AuditLog` model is unchanged; new action tags:

| Tag | Emitted by |
|---|---|
| `USER_BLOCKED` | `AuthService.blockUser` |
| `USER_UNBLOCKED` | `AuthService.unblockUser` |
| `USER_SOFT_DELETED` | `AuthService.softDeleteUser` |
| `USER_RECOVERED` | `AuthService.recoverUser` |
| `USER_ROLE_CHANGED` | `AuthService.updateUserRoleAsActor` (details: `fromRole`, `toRole`) |
| `USER_BACKFILLED` | `UserBackfillMigration.normalize` (per changed user) |
| `USER_BACKFILL_RUN` | `UserBackfillMigration.run` (summary) |

`AuditLogService` exposes synchronous helpers (`logBlock`, `logUnblock`, `logSoftDelete`, `logRecover`, `logRoleChange`) that build `AuditLog` instances; the actual write happens inside `AuthService`'s transaction. Existing async log methods (`log`, `logApproval`, `logRejection`) are untouched — Plan A does not change their semantics.

## 10. Caching

`CacheConfig` adds:

```
userStatus  →  Caffeine, expireAfterWrite 60s, max 5_000 entries
```

`UserRepository.findById(String)` annotated `@Cacheable("userStatus", key="#uid")`. Lifecycle methods (`blockUser`, `unblockUser`, `softDeleteUser`, `recoverUser`, `updateUserRoleAsActor`) call `cacheManager.getCache("userStatus").evict(uid)` after the Firestore transaction commits.

The auth filter reads via `userRepository.findById(uid)` so it transparently benefits from the cache.

## 11. Backfill migration

### Trigger

```
POST /api/admin/migrations/user-backfill   (ADMIN, gated by app.migrations.user-backfill.enabled=false)
```

### Behavior

1. Cursor-paginate `users` (200 per batch).
2. Per doc, normalize:
   - Missing `status` → `"active"`.
   - Legacy `status="inactive"` → `"blocked"` + `blockReason="legacy-inactive"` + `blockedAt = createdAt ?? now`.
   - Missing `role` → `"user"` (defensive).
   - Missing `createdAt` → `now`.
   - Missing `updatedAt` → `createdAt`.
3. Save the doc only if any field changed (idempotency).
4. Emit one `USER_BACKFILLED` audit per changed doc + a single `USER_BACKFILL_RUN` summary at the end.

The migration **does not** convert role casing — wire format stays lowercase.

### Rollout sequence

1. Deploy code with flag off; deploy `firestore.indexes.json` (wait for index ready).
2. Deploy updated `firestore.rules`. Verify with rules emulator.
3. Smoke-test on staging: full lifecycle (block/unblock/soft-delete/recover); last-admin guard.
4. Toggle flag on, hit migration endpoint once, verify audit + sample docs.
5. Toggle flag off.
6. Production: same sequence in maintenance window.

## 12. Testing

### Unit (Mockito, no Firestore)

- `RoleTest`, `UserStatusTest` — round-trip + edge cases.
- `UserModelTest` — state helpers, `recordBlock`, `recordUnblock`, `recordSoftDelete`, `recordRecover`.
- `AuthServiceLastAdminTest` — guard branches with mocked `firestore.runTransaction` (use a stub that immediately calls the lambda once).
- `AuthServiceSoftDeleteTest`, `AuthServiceBlockTest` — happy path + idempotency + Firebase Auth side effects.
- `AuditLogServiceAccountTest` — synchronous variants build correct entries.
- `GlobalExceptionHandlerAccountTest` — three new exceptions map to the right status codes.

### Integration (Firebase emulator, real Firestore)

- `AccountStatusFilterIntegrationTest` — BLOCKED → 403; DELETED → 401; USER role accepted; PENDING_PROFILE accepted (Plan A only stores it).
- `UserControllerLifecycleIntegrationTest` — block/unblock/soft-delete/recover round-trip; self-block on last admin → 409; moderator calling admin endpoint → 403.
- `AuthServiceLastAdminContentionTest` — two concurrent `blockUser` attempts on the same admin (multi-thread); exactly one succeeds, one gets `LastAdminException` or transaction retry exhaustion.
- `UserRepositoryAdminCountTest` — `countActiveAdmins` excludes blocked/deleted admins.
- `UserBackfillMigrationTest` — idempotency, legacy `inactive` → `blocked` with reason, cursor pagination across batches, feature flag respected.
- `UserStatusCacheTest` — block evicts; subsequent request sees BLOCKED; 61s without eviction shows the same.

### Rules (emulator)

- User reads own doc; not others'.
- Moderator denied reads on `/users/{not-self}`.
- Admin reads any.
- Non-admin writes denied.

### Performance

- Filter overhead with cache hit: target <5ms additional latency vs current.
- Filter overhead with cache miss: target <100ms (one Firestore read).
- Block→subsequent-request status enforcement: target <1s end-to-end.

## 13. Observability

### Logs

Structured INFO log per lifecycle method: `action=USER_BLOCKED uid=… by=… reason=…` (similar for others).

### Audit collection

`auditLogs` is the durable source of truth. Existing `AuditLogController` exposes admin queries.

### Metrics (Micrometer, via existing `MetricsConfig`)

| Metric | Tags | Use |
|---|---|---|
| `albunyaan_admin_lifecycle_total` | `action`, `outcome=ok\|guard_tripped\|tx_failed\|audit_failed` | Trace lifecycle action volume + failure modes |
| `albunyaan_admin_lifecycle_duration_seconds` | `action` | p99 lifecycle latency |
| `albunyaan_user_status_cache_total` | `result=hit\|miss` | Cache effectiveness |
| `albunyaan_last_admin_guard_total` | — | Counter for guard trips (forensic signal) |

## 14. Rollback

| Change | Reversibility |
|---|---|
| Code | Revert PR; redeploy. Pre-existing user docs unaffected (only nullable fields added). |
| Firestore rules | Revert PR; redeploy. Old rules still allow admin everything. |
| Firestore index | Harmless to leave in place if rolled back; no query depends on it post-rollback. |
| Backfill migration | Irreversible (it's normalization). Reverting code ignores normalized fields. |
| Cache | Disable via Spring profile if it causes a problem; fall back to direct Firestore reads. |

## 15. Risks

| Risk | Mitigation |
|---|---|
| Audit Firestore write failure cascades to admin lifecycle 503 | Inherent to D5; trade-off accepted. Mitigated by short Firestore timeout + Spring retry on tx. |
| Cache and Firebase Auth `disabled` flag drift if a non-Plan-A code path mutates Firestore directly | New code paths are tightly scoped; auditing ensures any direct write is rare and visible. |
| Two-phase consistency between Firestore (transactional) and Firebase Auth (non-transactional) | Documented in §7; idempotency on retry is the primary mitigation. |
| 60s cache window allows blocked user some access to non-admin endpoints | Acceptable: revoke-refresh-tokens fires on block, so token-issuance is shut down immediately; only currently-held ID tokens have the up-to-60s window. Sensitive endpoints (`/api/admin/*`) bypass via the revocation check. |
| `firestore.runTransaction` retry loop on contention adds latency under storms of admin actions | Admin actions are low-volume; storm scenario is unrealistic for AlbunyaanTube. Metrics will surface if it happens. |
| Plan F's password-reset email delivery isn't yet wired; admins can't actually reset passwords post-Plan A | Plan A doesn't change the existing reset-link generation; SES/SendGrid wiring is Plan F's responsibility. |

## 16. Open questions (resolved or deferred)

- **Hard-delete (GDPR purge):** deferred. Not part of any current plan. Note in `docs/status/PROJECT_STATUS.md` as future work.
- **Multi-factor auth:** deferred.
- **Account merge for duplicate-email collisions:** deferred to Plan C provider-linking work (per decision 6: verify-then-link covers most cases).
- **Min-admins floor (>1):** rejected during brainstorming as unnecessary for current scale; revisit if Plan F surfaces operational complaints.
