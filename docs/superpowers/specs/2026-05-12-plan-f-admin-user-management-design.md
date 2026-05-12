# Plan F — Admin User Management (ADMIN-USER-01) Design

> **Status:** Approved 2026-05-12
> **Owner:** Plan F implementation team
> **Predecessor:** Plan E (moderator submission workflow, merged in `develop` at commit `b1feec51`)
> **Successor (anticipated):** Plan G — audit-log retention / TTL scheduler (out of scope here)

---

## 1. Goal

Close the remaining gaps in the FitrahTube admin user-management surface. After Plans A–E, admins already have CRUD over users, role assignment, soft-delete + recover, audit logging, and `?includeDeleted` filtering. **Plan F adds four missing pillars:**

1. **Outbound email** — password-reset link must actually reach the user's inbox (today `AuthService.sendPasswordResetEmail` only logs the link).
2. **Stand-alone force-logout (session revocation)** — admins can revoke a user's refresh tokens without blocking/deleting. Today revocation only fires as a side-effect of block/delete flows.
3. **Bulk actions** — block / soft-delete / recover / revoke-sessions can each act on up to 100 uids in a single call with best-effort per-row result reporting.
4. **Audit-log cursor pagination** — replace the existing hard-coded 100-row limit with cursor-based "Load more" so older history is reachable.

A fifth, lower-cost change is folded in: auto-revoke refresh tokens when an admin changes a user's role (so the new role takes effect immediately rather than waiting for the existing JWT to expire).

---

## 2. Locked Decisions

Each prefixed `F#`, referenced by the implementation plan.

**F1. Email transport:** Microsoft Graph API + OAuth2 client credentials. NOT SMTP AUTH. Justification: Microsoft has deprecated SMTP basic auth since 2022; new tenants block it by default. Graph API is the supported future-proof path.

**F2. Email content:** Plaintext only, no HTML template. Admin-triggered, low volume, simple body avoids spam-filter rendering issues.

**F3. From address:** `noreply@fitrahtube.com` (tentative — final spelling of the domain to be confirmed by Farouq before deployment). Display name: `FitrahTube`. No-reply disclaimer in body. Email lands in the user's inbox via Microsoft Graph "sendMail" against the `noreply` mailbox in the FitrahTube tenant.

**F4. Bulk action contract:** best-effort with per-row results. Backend processes each uid independently inside a try/catch, returns `{successes: [...uids], failures: [{uid, reason}, ...]}` with HTTP 200 regardless of mixed outcomes. Bean Validation enforces `1 ≤ uids.size() ≤ 100`. Frontend renders a toast summarizing both buckets with an expandable failure detail.

**F5. Self-protection:** the requesting admin's own uid is silently bucketed as `failure {reason: "self_action_forbidden"}`. For `bulk-block`, `bulk-delete`, `bulk-revoke-sessions`, targets whose role is `ADMIN` are bucketed as `failure {reason: "admin_target_forbidden"}`. `bulk-recover` may target admins (required for unblocking deleted admin accounts).

**F6. Force-logout surface:** three triggers.
- `POST /api/admin/users/{uid}/revoke-sessions` (single, body: optional `reason`)
- `POST /api/admin/users/bulk-revoke-sessions` (bulk, same contract as F4)
- **Implicit, auto-fired**: when an admin changes a user's role via `PUT /api/admin/users/{uid}/role`, `revokeRefreshTokens(uid)` is invoked async post-commit, with audit entry `USER_SESSIONS_REVOKED_AUTO`.

**F7. Email-failure UX:** `MailService.sendPasswordResetEmail` runs `@Async`. SMTP/Graph errors are logged + counter `email.send.failure` + audit entry `USER_PASSWORD_RESET_EMAIL_FAILED` written. The admin's "Reset Password" action returns immediately (no waiting on Graph round-trip) and shows a green toast. Silent failures are accepted as a known tradeoff; admin can re-trigger if user reports.

**F8. Audit cursor format:** opaque base64url-encoded JSON `{"ts": "...", "id": "..."}`. Firestore query uses `.startAfter(documentSnapshot)` for tiebreak by document ID. Backwards-compatible: omitted cursor returns first page + `nextCursor`. Default `limit=50`, cap `limit=200`. Invalid/malformed cursor → 400.

**F9. Frontend bulk-select UX:** checkbox column on `UsersManagementView` table. When ≥1 row checked, sticky toolbar above the table appears with [Block] [Delete] [Recover] [Force Logout] buttons and a "N selected" counter. "Force Logout" also appears in each row's existing action dropdown for the single-user case.

**F10. Audit log retention / TTL:** **out of scope.** `audit_logs` collection continues to grow unbounded. Flagged as Plan G entry point — a weekly TTL job mirroring the tombstone-GC pattern from Plan D, with configurable retention window (suggested 365 days).

**F11. Branch & commit prefix:** branch `feature/ADMIN-USER-01-management`, commit prefix `[FEAT-ADMIN-USER-01-T#]` for tasks. PR targets `develop`.

---

## 3. Architecture

```
                   ┌─────────────────────────────────────┐
                   │ Frontend (Vue3)                     │
                   │  UsersManagementView.vue            │
                   │   ├─ checkbox column                │
                   │   ├─ sticky bulk-action toolbar     │
                   │   └─ per-user "Force logout" action │
                   │  AuditLogView.vue                   │
                   │   └─ cursor "Load more" replaces page│
                   └────────────┬────────────────────────┘
                                │ HTTPS
                                ▼
       ┌────────────────────────────────────────────────────┐
       │ Backend (Spring Boot 3, Java 17)                   │
       │                                                    │
       │  UserController     ──┐                            │
       │   • +5 routes         │                            │
       │  AuditController    ──┤                            │
       │   • +cursor params    │                            │
       │  AuthService        ──┤                            │
       │   • auto-revoke on    │                            │
       │     role change       │                            │
       │   • inject MailService│                            │
       │                       │                            │
       │  ┌────────────────────▼─────────────────────┐      │
       │  │ BulkUserService                          │      │
       │  │   execute(action, uids, actorUid) → BulkResult  │
       │  │   reuses existing single-user methods    │      │
       │  └──────────┬───────────────────────────────┘      │
       │             │                                      │
       │  ┌──────────▼──────────┐  ┌──────────────────────┐ │
       │  │ MailService          │  │ AuditLogService     │ │
       │  │  @Async send via     │  │  +findPaginated(    │ │
       │  │  Microsoft Graph SDK │  │    filter, cursor)  │ │
       │  └──────────┬───────────┘  └──────────┬──────────┘ │
       │             │                          │           │
       └─────────────┼──────────────────────────┼───────────┘
                     │                          │
                     ▼                          ▼
            Microsoft Graph API           Firestore (audit_logs)
            (sendMail w/ OAuth2)
```

---

## 4. File Inventory

### Backend — new (7)

| File | Purpose |
|---|---|
| `backend/src/main/java/com/albunyaan/tube/service/MailService.java` | Wraps `GraphServiceClient`. Exposes `sendPasswordResetEmail(to, link)`. `@Async`. Feature-gated by `mail.enabled`. |
| `backend/src/main/java/com/albunyaan/tube/service/BulkUserService.java` | Best-effort per-row processor. Single public method `execute(BulkAction, List<String> uids, String actorUid, String reason?) → BulkUserActionResult`. Delegates to existing `AuthService` single-user methods. `reason` only consumed by `REVOKE_SESSIONS`; ignored otherwise but written to the `USER_BULK_ACTION` summary audit entry regardless. |
| `backend/src/main/java/com/albunyaan/tube/dto/BulkUserActionRequest.java` | `{ uids: List<String> @Size(min=1, max=100), reason: String? }`. Optional `reason` recorded in the per-batch summary audit entry for all four actions. |
| `backend/src/main/java/com/albunyaan/tube/dto/BulkUserActionResult.java` | `{ successes: List<String>, failures: List<FailureEntry> }` where `FailureEntry = {uid, reason}`. |
| `backend/src/main/java/com/albunyaan/tube/dto/RevokeSessionsRequest.java` | `{ reason: String? }` optional. Used by the single-user `POST /users/{uid}/revoke-sessions`. Captured in per-action audit `details`. |
| `backend/src/main/java/com/albunyaan/tube/config/MailProperties.java` | `@ConfigurationProperties("mail")`: `enabled`, `fromAddress`, `fromDisplayName`. |
| `backend/src/main/java/com/albunyaan/tube/config/AzureProperties.java` | `@ConfigurationProperties("azure")`: `tenantId`, `clientId`, `clientSecret`. |

### Backend — modified (3)

| File | Change |
|---|---|
| `AuthService.java` | `sendPasswordResetEmail()` L686 — call `mailService.sendPasswordResetEmail(...)` after generating link. `updateUserRole()` — post-commit, async `revokeRefreshTokens(uid)` + audit `USER_SESSIONS_REVOKED_AUTO`. |
| `UserController.java` | Add 5 new routes (see §5). Wire `BulkUserService`. |
| `AuditController.java` | Add `?cursor=` and `?limit=` to GET `/admin/audit`, `/admin/audit/actor/{actorUid}`, `/admin/audit/action/{action}`. |
| `AuditLogService.java` | New `findPaginated(filter, limit, cursor?)` method returning `{items, nextCursor?}`. |

### Backend — build & config

| File | Change |
|---|---|
| `backend/build.gradle.kts` | + `implementation("com.microsoft.azure:msal4j:1.16.+")` and `implementation("com.microsoft.graph:microsoft-graph:6.+")`. Pin minor versions in commit; renovate handles patch bumps. |
| `backend/src/main/resources/application.yml` | New `mail.*` and `azure.*` blocks (see §6). |
| `backend/src/main/resources/application-test.yml` | `mail.enabled: false` so unit/integration tests don't try to talk to Graph. |

### Frontend — modified (4)

| File | Change |
|---|---|
| `frontend/src/views/UsersManagementView.vue` | Checkbox column, sticky bulk-action toolbar, per-user "Force logout" action, bulk-result toast. |
| `frontend/src/views/AuditLogView.vue` | Replace page-number paginator with cursor-driven "Load more" button. Filter change resets cursor. |
| `frontend/src/services/adminUsers.ts` | + `bulkBlock`, `bulkDelete`, `bulkRecover`, `bulkRevokeSessions`, `forceLogout`. |
| `frontend/src/services/adminAudit.ts` | Pass `cursor` through to backend, expose `nextCursor` in response. |
| `frontend/src/locales/messages.ts` | New i18n strings: bulk-toolbar buttons, force-logout confirmations, failure-reason translations (`user_not_found`, `already_blocked`, etc.), audit "Load more". |

### Tests — new

| Layer | File | Coverage |
|---|---|---|
| Backend unit | `MailServiceTest.java` | Mock GraphServiceClient. Verify sendMail invoked with expected message + recipients. Verify `mail.enabled=false` short-circuit. Verify failure path logs + audit. |
| Backend unit | `BulkUserServiceTest.java` | Happy path (3 succeed). Self-action filtered. Admin target filtered (where applicable). Already-blocked / not-blocked / not-found classified correctly. |
| Backend unit | `AuditCursorTest.java` | Encode → decode roundtrip. Reject malformed. Reject expired (stale ts field). |
| Backend IT | `BulkUserActionIT.java` | Firebase emulator. Seeds 5 users in various states; calls bulk-block; asserts 200 + 3 successes + 2 failures with correct reasons. Asserts 3 new audit log entries written. |
| Backend IT | `RevokeSessionsIT.java` | Single + bulk revoke. Asserts `tokensValidAfterTime` advances. Asserts audit entries `USER_SESSIONS_REVOKED` with reason captured. |
| Backend IT | `AutoRevokeOnRoleChangeIT.java` | PUT role → assert `revokeRefreshTokens` was called + `USER_SESSIONS_REVOKED_AUTO` audit entry exists. |
| Backend IT | `AuditPaginationIT.java` | Seed 250 audit rows. Walk 5 pages of 50. Assert no row duplication or omission across cursors. Last page returns `nextCursor=null`. |
| Frontend Vitest | `UsersManagementView.spec.ts` | Select 3 → click bulk-block → mock 200 mixed → toast shows "2 succeeded, 1 failed (already blocked)" with detail expandable. |
| Frontend Vitest | `AuditLogView.spec.ts` | Load more → second page appended (not replaced). Filter change resets list. |

### Documentation — new

| File | Purpose |
|---|---|
| `docs/deployment/azure-app-registration.md` | Step-by-step for Azure AD app: create app, grant Mail.Send, run PowerShell to scope to single mailbox, rotate secrets. |
| `docs/deployment/azure-secret-rotation.md` | Reminder: Azure client secrets expire ≤ 2 years. Procedure for rotating without downtime. |

---

## 5. New Endpoints

All `@PreAuthorize("hasRole('ADMIN')")`. All return JSON.

```
POST   /api/admin/users/{uid}/revoke-sessions
  body:  RevokeSessionsRequest { reason?: String }
  resp:  204 No Content
  audit: USER_SESSIONS_REVOKED { reason, actorUid }

POST   /api/admin/users/bulk-block
  body:  BulkUserActionRequest { uids: [String], reason?: String }
  resp:  200 OK + BulkUserActionResult
  audit: USER_BLOCKED × N (existing) — one per success
         USER_BULK_ACTION { action: "block", successes: N, failures: M, actorUid, reason? }

POST   /api/admin/users/bulk-delete
  body:  BulkUserActionRequest { uids, reason? }
  resp:  200 OK + BulkUserActionResult
  audit: USER_SOFT_DELETED × N (existing)
         USER_BULK_ACTION { action: "delete", ..., reason? }

POST   /api/admin/users/bulk-recover
  body:  BulkUserActionRequest { uids, reason? }
  resp:  200 OK + BulkUserActionResult
  audit: USER_RECOVERED × N (existing)
         USER_BULK_ACTION { action: "recover", ..., reason? }

POST   /api/admin/users/bulk-revoke-sessions
  body:  BulkUserActionRequest { uids, reason? }
  resp:  200 OK + BulkUserActionResult
  audit: USER_SESSIONS_REVOKED × N — `reason` (if present) propagates to each per-uid audit entry
         USER_BULK_ACTION { action: "revoke_sessions", ..., reason? }
```

Modified routes (additive — query params):

```
GET /api/admin/audit?cursor=<base64url>&limit=<int>
GET /api/admin/audit/actor/{actorUid}?cursor=...&limit=...
GET /api/admin/audit/action/{action}?cursor=...&limit=...
```

`limit` default 50, capped at 200. Response shape:
```json
{ "items": [...], "nextCursor": "<base64>" }   // null when no more pages
```

Existing single-user endpoints unchanged.

---

## 6. Email Configuration (Microsoft Graph)

### Build dependencies

```kotlin
// backend/build.gradle.kts
implementation("com.microsoft.azure:msal4j:1.16.+")
implementation("com.microsoft.graph:microsoft-graph:6.+")
implementation("com.azure:azure-identity:1.+")  // for ClientSecretCredential
```

Lock minor versions in committed `build.gradle.kts`; allow patch bumps via dependabot/renovate.

### application.yml block

```yaml
mail:
  enabled: ${MAIL_ENABLED:true}
  from-address: ${MAIL_FROM_ADDRESS:noreply@fitrahtube.com}
  from-display-name: ${MAIL_FROM_DISPLAY_NAME:FitrahTube}

azure:
  tenant-id: ${AZURE_TENANT_ID}
  client-id: ${AZURE_CLIENT_ID}
  client-secret: ${AZURE_CLIENT_SECRET}
```

### Environment variables (production VM)

| Variable | Source | Notes |
|---|---|---|
| `AZURE_TENANT_ID` | Azure Portal → Entra ID → Overview → Tenant ID | UUID |
| `AZURE_CLIENT_ID` | Azure Portal → App Registration → Overview → Application (client) ID | UUID |
| `AZURE_CLIENT_SECRET` | Azure Portal → App Registration → Certificates & secrets → New | **Show once at creation. Save to vault immediately.** Renew before expiry. |
| `MAIL_ENABLED` | `true` in prod, `false` in test profile | Off in CI |
| `MAIL_FROM_ADDRESS` | `noreply@fitrahtube.com` | Must exist as a real mailbox in M365 tenant |
| `MAIL_FROM_DISPLAY_NAME` | `FitrahTube` | |

### Azure AD app registration steps (one-time)

1. Azure Portal → **Microsoft Entra ID** → **App registrations** → **New registration**
   - Name: `FitrahTube Backend`
   - Supported account types: **Accounts in this organizational directory only (single tenant)**
   - Redirect URI: leave blank
2. Note **Tenant ID** and **Application (client) ID** from the new app's Overview page.
3. **Certificates & secrets** → **New client secret** → 24-month expiry → **Copy the Value field immediately** (it never displays again). Save to deployment vault.
4. **API permissions** → **Add a permission** → **Microsoft Graph** → **Application permissions** → check `Mail.Send` → **Add**.
5. Click **Grant admin consent for <tenant>**. (Requires Global Admin role on the tenant.)
6. **Critical security step** — restrict the app to send ONLY from `noreply@fitrahtube.com`. Otherwise it could send mail as ANY user in the tenant.

   Open Exchange Online PowerShell and run:
   ```powershell
   Connect-ExchangeOnline
   New-DistributionGroup -Name "FitrahTubeAppSenders" -Members "noreply@fitrahtube.com"
   New-ApplicationAccessPolicy `
       -AppId <client-id> `
       -PolicyScopeGroupId FitrahTubeAppSenders `
       -AccessRight RestrictAccess `
       -Description "FitrahTube Backend may only send from noreply mailbox"
   ```
7. Verify with `Test-ApplicationAccessPolicy -AppId <client-id> -Identity noreply@fitrahtube.com` → `AccessCheckResult: Granted`.

The deployment doc `docs/deployment/azure-app-registration.md` repeats this verbatim plus screenshots.

### MailService skeleton

```java
@Service
public class MailService {
    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    private final GraphServiceClient<?> graph;
    private final String fromAddress;
    private final String fromDisplayName;
    private final boolean enabled;
    private final MeterRegistry meters;
    private final AuditLogService auditLog;

    public MailService(MailProperties mail,
                       AzureProperties azure,
                       MeterRegistry meters,
                       AuditLogService auditLog) {
        this.enabled         = mail.isEnabled();
        this.fromAddress     = mail.getFromAddress();
        this.fromDisplayName = mail.getFromDisplayName();
        this.meters          = meters;
        this.auditLog        = auditLog;

        if (enabled) {
            ClientSecretCredential cred = new ClientSecretCredentialBuilder()
                .tenantId(azure.getTenantId())
                .clientId(azure.getClientId())
                .clientSecret(azure.getClientSecret())
                .build();
            TokenCredentialAuthProvider auth =
                new TokenCredentialAuthProvider(List.of("https://graph.microsoft.com/.default"), cred);
            this.graph = GraphServiceClient.builder().authenticationProvider(auth).buildClient();
        } else {
            this.graph = null;
        }
    }

    @Async
    public void sendPasswordResetEmail(String to, String resetLink) {
        if (!enabled) {
            log.info("mail.disabled to={}", to);
            return;
        }
        try {
            Message msg = buildPasswordResetMessage(to, resetLink);
            graph.users(fromAddress)
                 .sendMail(UserSendMailParameterSet.newBuilder()
                     .withMessage(msg)
                     .withSaveToSentItems(false)
                     .build())
                 .buildRequest()
                 .post();
            meters.counter("email.send.success", "type", "password_reset").increment();
            log.info("password_reset_email.sent to={}", to);
        } catch (Exception e) {
            log.error("password_reset_email.failed to={}", to, e);
            meters.counter("email.send.failure", "type", "password_reset").increment();
            auditLog.log("USER_PASSWORD_RESET_EMAIL_FAILED", "user", to,
                         "system", "system",
                         Map.of("error", e.getClass().getSimpleName()));
        }
    }

    private Message buildPasswordResetMessage(String to, String link) {
        Message m = new Message();
        m.subject = "Reset your FitrahTube password";
        ItemBody body = new ItemBody();
        body.contentType = BodyType.TEXT;
        body.content =
            "Hi,\n\n"
          + "We received a request to reset your FitrahTube password.\n"
          + "Click the link below to set a new password:\n\n"
          + link + "\n\n"
          + "This link expires in 1 hour. If you didn't request a reset, ignore this email — your account is safe.\n\n"
          + "This is an automated message from " + fromDisplayName + ". Replies to this address are not monitored.\n";
        m.body = body;

        Recipient r = new Recipient();
        EmailAddress e = new EmailAddress();
        e.address = to;
        r.emailAddress = e;
        m.toRecipients = List.of(r);

        return m;
    }
}
```

Replace existing TODO at `AuthService.java:686`:
```java
// before
String link = firebaseAuth.generatePasswordResetLink(email);
logger.info("Password reset link generated for: {}", email);
// TODO: Integrate with email service

// after
String link = firebaseAuth.generatePasswordResetLink(email);
mailService.sendPasswordResetEmail(email, link);
```

---

## 7. BulkUserService Design

```java
public enum BulkAction { BLOCK, DELETE, RECOVER, REVOKE_SESSIONS }

@Service
public class BulkUserService {
    private final AuthService authService;
    private final UserRepository userRepo;
    private final AuditLogService auditLog;

    public BulkUserActionResult execute(BulkAction action, List<String> uids, String actorUid, String reason) {
        List<String> successes = new ArrayList<>();
        List<FailureEntry> failures = new ArrayList<>();

        for (String uid : uids) {
            // F5 self-protection
            if (uid.equals(actorUid)) {
                failures.add(new FailureEntry(uid, "self_action_forbidden"));
                continue;
            }
            // F5 admin-target protection (block/delete/revoke only)
            if (action != BulkAction.RECOVER && isAdmin(uid)) {
                failures.add(new FailureEntry(uid, "admin_target_forbidden"));
                continue;
            }
            try {
                switch (action) {
                    case BLOCK            -> authService.blockUser(uid, actorUid);
                    case DELETE           -> authService.softDeleteUser(uid, actorUid);
                    case RECOVER          -> authService.recoverUser(uid, actorUid);
                    case REVOKE_SESSIONS  -> authService.revokeSessions(uid, actorUid, reason);
                }
                successes.add(uid);
            } catch (UserRecordNotFoundException e) {
                failures.add(new FailureEntry(uid, "user_not_found"));
            } catch (IllegalStateException e) {
                failures.add(new FailureEntry(uid, classify(e.getMessage())));
            } catch (Exception e) {
                log.error("bulk.action.error uid={} action={}", uid, action, e);
                failures.add(new FailureEntry(uid, "firebase_error"));
            }
        }

        auditLog.log("USER_BULK_ACTION", "user", "(batch)", actorUid, actorUid,
                     Map.of("action", action.name().toLowerCase(),
                            "successes", successes.size(),
                            "failures", failures.size()));

        return new BulkUserActionResult(successes, failures);
    }

    private String classify(String msg) {
        if (msg.contains("already blocked"))   return "already_blocked";
        if (msg.contains("not blocked"))       return "not_blocked";
        if (msg.contains("already deleted"))   return "already_deleted";
        if (msg.contains("not deleted"))       return "not_deleted";
        return "invalid_state";
    }
}
```

Note: `authService.revokeSessions(uid, actorUid, reason)` is a new public method extracted from the existing inline `firebaseAuth.revokeRefreshTokens(uid)` calls in `softDeleteUser`/`blockUser`. It writes audit entry `USER_SESSIONS_REVOKED` with `{reason, actorUid}`.

---

## 8. Audit Cursor Pagination

### Cursor encoding

```java
public final class AuditCursor {
    private static final ObjectMapper M = new ObjectMapper();

    public static String encode(Instant ts, String docId) {
        try {
            byte[] json = M.writeValueAsBytes(Map.of("ts", ts.toString(), "id", docId));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(json);
        } catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }

    public static Decoded decode(String cursor) {
        try {
            byte[] json = Base64.getUrlDecoder().decode(cursor);
            Map<String, String> m = M.readValue(json, new TypeReference<>() {});
            return new Decoded(Instant.parse(m.get("ts")), m.get("id"));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid cursor", e);
        }
    }

    public record Decoded(Instant ts, String docId) {}
}
```

### AuditLogService change

```java
public PaginatedAuditLog findPaginated(AuditFilter filter, int limit, String cursor) {
    int effLimit = Math.min(Math.max(limit, 1), 200);
    Query q = collection
        .orderBy("timestamp", Direction.DESCENDING)
        .orderBy(FieldPath.documentId(), Direction.DESCENDING);
    if (filter.actor() != null)  q = q.whereEqualTo("actorUid", filter.actor());
    if (filter.action() != null) q = q.whereEqualTo("action", filter.action());

    if (cursor != null && !cursor.isBlank()) {
        AuditCursor.Decoded c = AuditCursor.decode(cursor);
        DocumentSnapshot snap = collection.document(c.docId()).get().get();
        if (snap.exists()) q = q.startAfter(snap);
        else throw new IllegalArgumentException("Cursor references a missing document");
    }

    QuerySnapshot snap = q.limit(effLimit + 1).get().get();
    List<AuditLog> rows = snap.getDocuments().stream()
        .limit(effLimit)
        .map(d -> d.toObject(AuditLog.class))
        .toList();
    boolean hasMore = snap.size() > effLimit;
    String nextCursor = hasMore
        ? AuditCursor.encode(rows.get(rows.size()-1).getTimestamp().toInstant(),
                             rows.get(rows.size()-1).getId())
        : null;
    return new PaginatedAuditLog(rows, nextCursor);
}
```

Firestore composite indexes (must be defined or query fails at runtime):
- `audit_logs` (timestamp DESC, __name__ DESC) — auto-created on first query of the unfiltered list
- `audit_logs` (actorUid ASC, timestamp DESC, __name__ DESC) — explicit, needs Firebase index file update
- `audit_logs` (action ASC, timestamp DESC, __name__ DESC) — explicit, needs index file update

Spec includes a task to update `backend/firestore.indexes.json` accordingly.

---

## 9. Frontend UX Detail

### UsersManagementView

```
┌─────────────────────────────────────────────────────────────────┐
│ [ Search ____________________ ]  Role ▾  Status ▾  ☐ deleted    │
├─────────────────────────────────────────────────────────────────┤
│ ► 3 selected   [Block] [Delete] [Recover] [Force Logout]        │  ← sticky toolbar appears when ≥1 row checked
├──┬──────────────────────┬───────┬──────────┬──────────┬─────────┤
│☐ │ Email                │ Role  │ Status   │ LastLogin│ Actions │
├──┼──────────────────────┼───────┼──────────┼──────────┼─────────┤
│☑ │ alice@example.com    │ user  │ ACTIVE   │ 2d ago   │   ⋮     │
│☑ │ bob@example.com      │ user  │ BLOCKED  │ 5d ago   │   ⋮     │
│☐ │ ...                                                  │       │
└──┴──────────────────────────────────────────────────────────────┘
```

Per-row action menu (`⋮`) now includes:
- Edit
- Reset Password
- Force Logout       ← new
- Deactivate / Activate (existing)
- Delete

Bulk-result toast example:
```
┌────────────────────────────────────────────┐
│ Block — 2 succeeded, 1 failed              │
│ ▾ Show details                             │
│   ✗ charlie@example.com: already blocked   │
│   ✓ alice@example.com                      │
│   ✓ bob@example.com                        │
└────────────────────────────────────────────┘
```

Confirmation modal triggers ONLY for bulk-delete (most destructive). Simple OK/Cancel — no typed confirmation. Block / Recover / Force-Logout fire immediately on toolbar click (all reversible).

### AuditLogView

Pagination replaced:
```
┌───────────────────────────┐
│ Audit Log                 │
│ Actor ▾  Action ▾         │
├───────────────────────────┤
│ <table rows>              │
│ ...                       │
├───────────────────────────┤
│        [ Load more ]      │  ← visible when nextCursor != null
└───────────────────────────┘
```

i18n new keys (English shown — `ar` and `nl` translations added):
```
users.bulk.block                  = "Block"
users.bulk.delete                 = "Delete"
users.bulk.recover                = "Recover"
users.bulk.revokeSessions         = "Force Logout"
users.bulk.selected               = "{n} selected"
users.bulk.toast.summary          = "{action} — {ok} succeeded, {fail} failed"
users.bulk.toast.details          = "Show details"
users.bulk.reason.user_not_found       = "User not found"
users.bulk.reason.already_blocked      = "Already blocked"
users.bulk.reason.not_blocked          = "Not blocked"
users.bulk.reason.already_deleted      = "Already deleted"
users.bulk.reason.not_deleted          = "Not deleted"
users.bulk.reason.self_action_forbidden  = "Cannot apply to your own account"
users.bulk.reason.admin_target_forbidden = "Cannot apply to admin accounts"
users.bulk.reason.firebase_error       = "Provider error"
users.confirmDelete.bulk          = "Soft-delete {n} users? They can be recovered later."
users.forceLogout.confirm         = "Force logout {email}? They will be signed out within an hour."
audit.loadMore                    = "Load more"
```

---

## 10. Testing Strategy

### Unit (`./gradlew test`)

- `MailServiceTest` — mock `GraphServiceClient`; assert `users(fromAddress).sendMail(msg)` invoked with subject + plaintext body + correct `to`. Verify disabled path is a no-op. Verify exception path increments `email.send.failure` and writes audit entry.
- `BulkUserServiceTest` — table-driven over the 4 actions and ~10 failure modes. Mocks `AuthService` to throw specific exceptions; asserts result buckets are correct.
- `AuditCursorTest` — encode→decode→re-encode is idempotent. Malformed base64 throws `IllegalArgumentException`. Missing fields throws.
- `AuditLogServiceTest` — query construction asserted via Firestore Java mock harness already used in the repo.

### Integration (`./gradlew test -Pintegration=true`)

Run against Firebase emulator with `mail.enabled=false` and a stub `GraphServiceClient`.

- `BulkUserActionIT` — seed 5 users (1 admin, 4 regular; one already blocked, one already deleted). Bulk-block targeting all 5. Assert 200 + 2 successes (the regular non-blocked) + 3 failures with correct reasons. Verify per-success `USER_BLOCKED` audit and 1 `USER_BULK_ACTION` summary entry.
- `RevokeSessionsIT` — single revoke + bulk revoke. Assert Firebase Admin SDK `getUser(uid).getTokensValidAfterTime()` advances past `Instant.now() - 1s`. Verify `USER_SESSIONS_REVOKED` audit with `details.reason` captured.
- `AutoRevokeOnRoleChangeIT` — PUT role from USER → MODERATOR. Assert role updated, revoke fired, `USER_SESSIONS_REVOKED_AUTO` audit with `details.oldRole=user, newRole=moderator`.
- `AuditPaginationIT` — seed 250 audit rows with monotone timestamps. Walk 5 pages of 50. Track seen ids; assert no duplicates, no omissions, last page returns `nextCursor=null`.

### Frontend (Vitest, `npm test`)

- `UsersManagementView.spec.ts` — check 3 rows, click bulk-block, mock backend response with 2 ok + 1 failure. Assert toast text matches "2 succeeded, 1 failed". Click "Show details" → assert failure row visible with reason.
- `AuditLogView.spec.ts` — initial load returns nextCursor. Click "Load more" → assert second request with `?cursor=<value>`. Assert items concatenated (not replaced). Filter change → assert state reset + cursor cleared.

### Manual smoke (after Azure provisioning by user)

1. With real Azure AD app credentials in env, real `noreply@fitrahtube.com` mailbox, run backend.
2. From admin UI: pick a test user (Farouq's own throwaway address), click "Reset password".
3. Verify email arrives within ~30 seconds. Inspect "From:" — should read `FitrahTube <noreply@fitrahtube.com>`. Click link, verify it lands on Firebase reset page.
4. Run PowerShell `Get-MailFlowReport` (or check sent items NOT recorded — `saveToSentItems=false`).

### Coverage gates

- Backend test coverage of `BulkUserService` and `MailService`: ≥ 90% line. (Wired into existing JaCoCo report.)
- No new files dip below repo-wide minimum (currently 75%).

---

## 11. Risks & Rollout

### Risks

1. **Microsoft Graph SDK 6.x is breaking-change-prone** — major version bumps in `microsoft-graph` Java SDK have happened with little warning. Mitigation: pin to `6.+` minor in spec, lock exact resolved version via Gradle dependency-lock once stable.
2. **Azure client secret expiry** — secrets cap at 24 months. If forgotten, password resets silently fail. Mitigation: a Spring `@Scheduled` health check runs daily, calls Graph `/me` with the cached token, fails loudly (logs + counter `azure.token.invalid`) when token acquisition errors. Also `docs/deployment/azure-secret-rotation.md` documents the 30-day-before-expiry rotation procedure.
3. **Mail.Send scoping** — if step 6 in §6 is skipped, the app can send mail as ANY mailbox in the tenant. Spec marks this as a deployment blocker. Backend startup should refuse to start if the configured `from-address` mailbox isn't reachable (a one-shot startup check calling `graph.users(fromAddress).get()`).
4. **Bulk-revoke latency** — 100 sequential `revokeRefreshTokens` calls take ~3–6s. Within controller timeout (default 30s) but visibly slow. Acceptable for an admin bulk operation; if it becomes a problem, parallelize in a Plan-G follow-up.
5. **Audit log unbounded growth** — `audit_logs` collection has no retention today. With Plan E + F, it grows ~10–50 rows/day at current scale. Out of scope for Plan F. Documented as Plan G entry point.
6. **Race on auto-revoke during role change** — if `revokeRefreshTokens` errors out, role change still commits (per F6 design). User keeps elevated access until JWT expires (≤1h). Tradeoff explicitly chosen: never block a role change on async cleanup. Audit log captures the auto-revoke failure if it occurs.
7. **`@Async` executor** — `MailService.sendPasswordResetEmail` and the auto-revoke-on-role-change call both run on Spring's default `SimpleAsyncTaskExecutor` unless overridden. For now this is fine (low call volume). If we ever batch-send emails or auto-revoke frequently, configure a dedicated `ThreadPoolTaskExecutor` bean in a follow-up. Spec does NOT introduce a custom executor.
8. **Graph SDK loose version pin** — spec writes `"6.+"` (Gradle dynamic range). The implementation plan will resolve to a specific minor version at build time and commit `gradle/dependency-locks/*.lockfile` to ensure reproducibility across machines and CI.

### Rollout (zero-downtime)

1. **Phase 1: deploy code with `MAIL_ENABLED=false`**. New endpoints live, bulk + audit-cursor functional, password-reset emails still no-op (audit-only). Verify endpoints + frontend, no behavior change for users.
2. **Phase 2: provision Azure AD app** (Farouq does Azure Portal steps + PowerShell). Save tenant/client/secret to vault, populate env vars on prod VM.
3. **Phase 3: flip `MAIL_ENABLED=true`**, restart backend. Click reset on a throwaway test account, verify mail arrives.
4. **Phase 4: announce to admins** — bulk actions now available.

Backout: set `MAIL_ENABLED=false` and revert frontend bundle. Audit pagination is backward compatible; existing clients without cursor still work.

---

## 12. Out of Scope / Deferred

- **Audit log TTL / retention scheduler** — Plan G candidate. Pattern mirrors Plan D's `TombstoneGcScheduler`.
- **Email templates for other events** (welcome on signup, role-change notification to user, block notice). MailService is designed to extend cleanly but content + i18n + opt-out belong in a separate plan.
- **HTML / branded email** — plaintext only per F2. Upgrade path: add a Thymeleaf template and switch `body.contentType` to `HTML`.
- **OAuth2 SASL XOAUTH2 SMTP fallback** — chose Graph API per F1; SMTP path not implemented as backup.
- **Per-action confirmation strength** (typed-confirmation modal for bulk-delete) — F9 keeps it simple (OK/Cancel). Add typed-confirm in follow-up if real admins hit accidental deletes.
- **Selection persistence across pagination** — bulk-select is reset when admin navigates to next page. Cross-page selection is a usability nice-to-have; deferred.
- **Live "active session" listing per user** — Firebase Admin SDK doesn't expose active-session metadata. Force-logout is fire-and-forget; admin doesn't see "user has 3 active sessions". Out of scope.
- **Rate-limiting on bulk endpoints** — Plan E's `SubmissionRateLimitInterceptor` covers registry POSTs, not user-management POSTs. Admin bulk actions are inherently rate-limited by being admin-only. If admins ever script abuse, revisit.

---

## 13. Open Items (to resolve before implementation kicks off)

1. **Confirm domain spelling** — spec assumes `fitrahtube.com`. If actual domain is different (e.g., `fitrahtube.app`, `albunyaantube.com`), update §2 F3 and §6 examples before tasks T1 onward.
2. **Azure AD app provisioning** — must happen before Phase 3 of rollout. Farouq to follow §6 steps and provide three env values (tenant, client, secret).
3. **Exchange admin access** — running `New-ApplicationAccessPolicy` requires Global Admin in the M365 tenant. Confirm Farouq has this role, or coordinate with the tenant admin.

---

End of design.
