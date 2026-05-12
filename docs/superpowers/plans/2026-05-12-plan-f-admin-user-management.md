# Plan F — Admin User Management Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire outbound email delivery, stand-alone session revocation, bulk admin actions, and audit-log cursor pagination into the existing FitrahTube admin surface.

**Architecture:** Approach 1 (Layered) per spec §3 — new `MailService` and `BulkUserService` classes, four new bulk endpoints, modified audit endpoints with cursor support, Vue UI for bulk select + load-more.

**Tech Stack:** Spring Boot 3, Java 17, Firestore, Microsoft Graph API (msal4j + microsoft-graph SDK), Firebase Admin SDK; Vue 3 + Pinia + Vitest frontend; Firebase emulator for IT.

**Spec:** `docs/superpowers/specs/2026-05-12-plan-f-admin-user-management-design.md`. Read it first.

**Ticket prefix:** `ADMIN-USER-01`. Branch: `feature/ADMIN-USER-01-management`. PR target: `develop`.

---

## File Structure

### Backend — create
| Path | Responsibility |
|---|---|
| `backend/src/main/java/com/albunyaan/tube/config/MailProperties.java` | `@ConfigurationProperties("mail")` — enabled, fromAddress, fromDisplayName |
| `backend/src/main/java/com/albunyaan/tube/config/AzureProperties.java` | `@ConfigurationProperties("azure")` — tenantId, clientId, clientSecret |
| `backend/src/main/java/com/albunyaan/tube/service/MailService.java` | Wraps GraphServiceClient. `@Async sendPasswordResetEmail(to, link)`. Feature-gated by `mail.enabled`. |
| `backend/src/main/java/com/albunyaan/tube/service/MailServiceStartupCheck.java` | `ApplicationRunner` that verifies the configured from-address mailbox is reachable when `mail.enabled=true` (spec risk §11.3) |
| `backend/src/main/java/com/albunyaan/tube/service/BulkUserService.java` | Best-effort per-row processor. `execute(BulkAction, uids, actorUid, reason?) → BulkUserActionResult` |
| `backend/src/main/java/com/albunyaan/tube/service/BulkAction.java` | Enum: BLOCK, DELETE, RECOVER, REVOKE_SESSIONS |
| `backend/src/main/java/com/albunyaan/tube/dto/BulkUserActionRequest.java` | `{uids: List<String> @Size(1..100), reason: String?}` |
| `backend/src/main/java/com/albunyaan/tube/dto/BulkUserActionResult.java` | `{successes, failures}` with nested `FailureEntry` record |
| `backend/src/main/java/com/albunyaan/tube/dto/RevokeSessionsRequest.java` | `{reason: String?}` for single-user revoke endpoint |
| `backend/src/main/java/com/albunyaan/tube/util/AuditCursor.java` | base64url JSON {ts,id} cursor encode/decode |
| `backend/src/main/java/com/albunyaan/tube/dto/PaginatedAuditLog.java` | `{items, nextCursor}` response wrapper |

### Backend — modify
| Path | Change |
|---|---|
| `backend/build.gradle.kts` | + `msal4j`, `microsoft-graph`, `azure-identity` deps |
| `backend/src/main/resources/application.yml` | + `mail.*` and `azure.*` blocks |
| `backend/src/test/resources/application-test.yml` | + `mail.enabled: false`, dummy azure values |
| `backend/src/main/resources/firestore.indexes.json` | + 3 composite indexes for `audit_logs` |
| `backend/src/main/java/com/albunyaan/tube/service/AuthService.java` | (a) inject MailService, replace TODO at L689 in `sendPasswordResetEmail`; (b) extract public `revokeSessions(uid, actorUid, reason)`; (c) auto-revoke + audit `USER_SESSIONS_REVOKED_AUTO` from `updateUserRoleAsActor` |
| `backend/src/main/java/com/albunyaan/tube/service/AuditLogService.java` | + `findPaginated(filter, limit, cursor) → PaginatedAuditLog` |
| `backend/src/main/java/com/albunyaan/tube/controller/UserController.java` | + 5 endpoints: revoke-sessions (single), bulk-block, bulk-delete, bulk-recover, bulk-revoke-sessions |
| `backend/src/main/java/com/albunyaan/tube/controller/AuditLogController.java` | + `cursor` + `limit` query params on 3 GET endpoints |

### Backend — tests
| Path | Coverage |
|---|---|
| `backend/src/test/java/com/albunyaan/tube/service/MailServiceTest.java` | Mock GraphServiceClient. Disabled path no-op. Happy path. Failure path → audit + counter. |
| `backend/src/test/java/com/albunyaan/tube/service/BulkUserServiceTest.java` | Happy path, self-protection, admin-target protection, all classify(...) branches, summary audit |
| `backend/src/test/java/com/albunyaan/tube/util/AuditCursorTest.java` | encode↔decode roundtrip, malformed input rejection |
| `backend/src/test/java/com/albunyaan/tube/integration/BulkUserActionIT.java` | 5 seeded users → bulk-block returns mixed; per-success + summary audits written |
| `backend/src/test/java/com/albunyaan/tube/integration/RevokeSessionsIT.java` | Single + bulk revoke; tokensValidAfterTime advances; audit captured |
| `backend/src/test/java/com/albunyaan/tube/integration/AutoRevokeOnRoleChangeIT.java` | PUT role → auto-revoke + `USER_SESSIONS_REVOKED_AUTO` audit |
| `backend/src/test/java/com/albunyaan/tube/integration/AuditPaginationIT.java` | 250 rows, 5 pages of 50, no dupes/omissions |

### Frontend — modify
| Path | Change |
|---|---|
| `frontend/src/services/adminUsers.ts` | + `bulkBlock`, `bulkDelete`, `bulkRecover`, `bulkRevokeSessions`, `forceLogout` |
| `frontend/src/services/adminAudit.ts` | Replace page-number paginator with cursor; expose `nextCursor` |
| `frontend/src/views/UsersManagementView.vue` | Checkbox column, sticky bulk-action toolbar, "Force logout" per-row action, bulk-result toast |
| `frontend/src/views/AuditLogView.vue` | Replace pagination with "Load more"; filter change resets cursor |
| `frontend/src/locales/messages.ts` | New keys (spec §9) in `en`, `ar`, `nl` |

### Frontend — tests
| Path | Coverage |
|---|---|
| `frontend/tests/UsersManagementView.spec.ts` | Select 3 → bulk-block → mock mixed 200 → toast renders summary + expandable failure |
| `frontend/tests/AuditLogView.spec.ts` | Initial load → "Load more" → 2nd page appended (not replaced); filter change resets state |

### Documentation — new
| Path | Purpose |
|---|---|
| `docs/deployment/azure-app-registration.md` | Step-by-step Azure AD app + Mail.Send + scope restriction |
| `docs/deployment/azure-secret-rotation.md` | Secret rotation procedure |

---

# Phase 1 — Build + Config Foundations

## Task 1: Add Microsoft Graph dependencies

**Files:** modify `backend/build.gradle.kts`

Per spec §6 build dependencies block + F11 / risk §11.8 (loose version pin acceptable for now).

- [ ] **Step 1: Append three implementation lines to the `dependencies { … }` block**

Locate the existing block (around line 24 onward). After the last `implementation(...)` line under `dependencies {` (before any `testImplementation` lines), add:

```kotlin
    // Plan F (ADMIN-USER-01) — Microsoft Graph for outbound mail
    implementation("com.microsoft.azure:msal4j:1.16.+")
    implementation("com.microsoft.graph:microsoft-graph:6.+")
    implementation("com.azure:azure-identity:1.+")
```

- [ ] **Step 2: Resolve dependencies + verify build still compiles**

Run:
```bash
cd backend && ./gradlew --refresh-dependencies compileJava
```
Expected: BUILD SUCCESSFUL. If any class conflict between SDK transitive Netty pins and existing Netty `constraints` block, prefer the existing pin (do NOT relax it).

- [ ] **Step 3: Commit**

```bash
git add backend/build.gradle.kts
git commit -m "[FEAT-ADMIN-USER-01-T1]: add microsoft-graph + msal4j + azure-identity deps"
```

---

## Task 2: `MailProperties` + `AzureProperties` `@ConfigurationProperties`

**Files:**
- Create: `backend/src/main/java/com/albunyaan/tube/config/MailProperties.java`
- Create: `backend/src/main/java/com/albunyaan/tube/config/AzureProperties.java`
- Modify: `backend/src/main/java/com/albunyaan/tube/AlbunyaanTubeApplication.java` (register via `@EnableConfigurationProperties`)

Per spec §4 (backend new) + §6 application.yml block (F1).

- [ ] **Step 1: Create `MailProperties.java`**

```java
package com.albunyaan.tube.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Plan F (ADMIN-USER-01) — outbound mail feature flag + identity.
 */
@ConfigurationProperties(prefix = "mail")
public class MailProperties {
    private boolean enabled = false;
    private String fromAddress;
    private String fromDisplayName;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getFromAddress() { return fromAddress; }
    public void setFromAddress(String fromAddress) { this.fromAddress = fromAddress; }
    public String getFromDisplayName() { return fromDisplayName; }
    public void setFromDisplayName(String fromDisplayName) { this.fromDisplayName = fromDisplayName; }
}
```

- [ ] **Step 2: Create `AzureProperties.java`**

```java
package com.albunyaan.tube.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Plan F (ADMIN-USER-01) — Azure AD app credentials for Microsoft Graph.
 */
@ConfigurationProperties(prefix = "azure")
public class AzureProperties {
    private String tenantId;
    private String clientId;
    private String clientSecret;

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }
    public String getClientSecret() { return clientSecret; }
    public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }
}
```

- [ ] **Step 3: Register both properties classes on the main application**

In `backend/src/main/java/com/albunyaan/tube/AlbunyaanTubeApplication.java`, locate the existing annotations (`@SpringBootApplication`, `@EnableCaching`, `@EnableScheduling`). Add or extend an `@EnableConfigurationProperties(...)` annotation. Look for an existing one — if there is one, add the two new classes to its array; if there is none, add the annotation. Resulting set of annotations should include:

```java
@EnableConfigurationProperties({
    com.albunyaan.tube.config.MailProperties.class,
    com.albunyaan.tube.config.AzureProperties.class
    // ... merge with any existing classes already listed
})
```

> **Note:** If `MailProperties`/`AzureProperties` are picked up by classpath scanning (some projects use `@ConfigurationPropertiesScan`), Spring will still wire them. The explicit `@EnableConfigurationProperties` is the safe path and matches Plan D's approach.

- [ ] **Step 4: Compile**

```bash
cd backend && ./gradlew compileJava
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/albunyaan/tube/config/MailProperties.java \
        backend/src/main/java/com/albunyaan/tube/config/AzureProperties.java \
        backend/src/main/java/com/albunyaan/tube/AlbunyaanTubeApplication.java
git commit -m "[FEAT-ADMIN-USER-01-T2]: MailProperties + AzureProperties config beans"
```

---

## Task 3: `MailService` skeleton with disabled-path short-circuit (TDD)

**Files:**
- Create: `backend/src/main/java/com/albunyaan/tube/service/MailService.java`
- Test:   `backend/src/test/java/com/albunyaan/tube/service/MailServiceTest.java`

Per spec §6 MailService skeleton (F1, F2, F3, F7).

- [ ] **Step 1: Write the failing test**

```java
package com.albunyaan.tube.service;

import com.albunyaan.tube.config.AzureProperties;
import com.albunyaan.tube.config.MailProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MailServiceTest {

    @Test
    void disabledMail_shortCircuits_andDoesNotInitializeGraph() {
        MailProperties mail = new MailProperties();
        mail.setEnabled(false);
        mail.setFromAddress("noreply@fitrahtube.com");
        mail.setFromDisplayName("FitrahTube");
        AzureProperties azure = new AzureProperties();
        MeterRegistry meters = new SimpleMeterRegistry();
        AuditLogService auditLog = mock(AuditLogService.class);

        MailService svc = new MailService(mail, azure, meters, auditLog);

        // Should not throw, should not call Graph (no Graph client constructed at all).
        svc.sendPasswordResetEmail("user@example.com", "https://reset/link");

        verifyNoInteractions(auditLog);
        assertEquals(0.0, meters.counter("email.send.success", "type", "password_reset").count());
        assertEquals(0.0, meters.counter("email.send.failure", "type", "password_reset").count());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd backend && ./gradlew test --tests "com.albunyaan.tube.service.MailServiceTest"
```
Expected: FAIL with "cannot resolve symbol MailService" (class doesn't exist yet).

- [ ] **Step 3: Implement minimal `MailService` (disabled path only)**

```java
package com.albunyaan.tube.service;

import com.albunyaan.tube.config.AzureProperties;
import com.albunyaan.tube.config.MailProperties;
import com.azure.identity.ClientSecretCredential;
import com.azure.identity.ClientSecretCredentialBuilder;
import com.microsoft.graph.authentication.TokenCredentialAuthProvider;
import com.microsoft.graph.requests.GraphServiceClient;
import io.micrometer.core.instrument.MeterRegistry;
import okhttp3.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Plan F (ADMIN-USER-01) — Microsoft Graph mail sender.
 * Feature-gated by mail.enabled. When disabled, all sends are no-ops.
 * Failures are logged + audited; the caller is never blocked.
 */
@Service
public class MailService {
    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    private final GraphServiceClient<Request> graph; // null when disabled
    private final String fromAddress;
    private final String fromDisplayName;
    private final boolean enabled;
    private final MeterRegistry meters;
    private final AuditLogService auditLog;

    public MailService(MailProperties mail,
                       AzureProperties azure,
                       MeterRegistry meters,
                       AuditLogService auditLog) {
        this.enabled = mail.isEnabled();
        this.fromAddress = mail.getFromAddress();
        this.fromDisplayName = mail.getFromDisplayName();
        this.meters = meters;
        this.auditLog = auditLog;

        if (enabled) {
            ClientSecretCredential cred = new ClientSecretCredentialBuilder()
                    .tenantId(azure.getTenantId())
                    .clientId(azure.getClientId())
                    .clientSecret(azure.getClientSecret())
                    .build();
            TokenCredentialAuthProvider auth = new TokenCredentialAuthProvider(
                    List.of("https://graph.microsoft.com/.default"), cred);
            this.graph = GraphServiceClient.builder()
                    .authenticationProvider(auth)
                    .buildClient();
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
        // Happy path + failure path implemented in T4 + T5.
        throw new UnsupportedOperationException("implemented in T4");
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
cd backend && ./gradlew test --tests "com.albunyaan.tube.service.MailServiceTest"
```
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/albunyaan/tube/service/MailService.java \
        backend/src/test/java/com/albunyaan/tube/service/MailServiceTest.java
git commit -m "[FEAT-ADMIN-USER-01-T3]: MailService skeleton with disabled-path no-op"
```

---

## Task 4: `MailService` happy-path send (TDD)

**Files:**
- Modify: `backend/src/main/java/com/albunyaan/tube/service/MailService.java`
- Modify: `backend/src/test/java/com/albunyaan/tube/service/MailServiceTest.java`

Per spec §6 — F2 plaintext body, `saveToSentItems=false`, F3 subject + display name.

> **SDK note (2026-05-12, post-T3 amendment):** the resolved `microsoft-graph:6.+` dependency uses the Kiota-generated v6 client. The original code below was written against v5 syntax; this section is updated for v6. Key differences:
> - Use setters (`msg.setSubject(...)`, `body.setContentType(...)`, `r.setEmailAddress(...)`) instead of public-field assignment.
> - `BodyType.Text` (camel case) replaces `BodyType.TEXT`.
> - Send via `SendMailPostRequestBody` + `graph.users().byUserId(fromAddress).sendMail().post(body)` instead of `UserSendMailParameterSet` + `.buildRequest().post()`.
> - `GraphServiceClient` no longer has a generic type parameter (the field type in `MailService` is plain `GraphServiceClient`).

- [ ] **Step 1: Add failing happy-path test**

Append to `MailServiceTest.java`:

```java
    @Test
    void enabledMail_buildsCorrectMessage_andSends() throws Exception {
        // We don't unit-test the actual Graph HTTP call (mocking the SDK chain is brittle);
        // we test the buildPasswordResetMessage helper instead.
        MailProperties mail = new MailProperties();
        mail.setEnabled(false); // skip Graph init in constructor
        mail.setFromAddress("noreply@fitrahtube.com");
        mail.setFromDisplayName("FitrahTube");
        AzureProperties azure = new AzureProperties();
        MeterRegistry meters = new SimpleMeterRegistry();
        AuditLogService auditLog = mock(AuditLogService.class);

        MailService svc = new MailService(mail, azure, meters, auditLog);

        com.microsoft.graph.models.Message msg = svc.buildPasswordResetMessage(
                "user@example.com", "https://app.fitrahtube.com/reset/abc");

        assertEquals("Reset your FitrahTube password", msg.getSubject());
        assertEquals(com.microsoft.graph.models.BodyType.Text, msg.getBody().getContentType());
        assertTrue(msg.getBody().getContent().contains("https://app.fitrahtube.com/reset/abc"));
        assertTrue(msg.getBody().getContent().contains("This link expires in 1 hour"));
        assertTrue(msg.getBody().getContent().contains("FitrahTube")); // display name
        assertEquals(1, msg.getToRecipients().size());
        assertEquals("user@example.com",
                msg.getToRecipients().get(0).getEmailAddress().getAddress());
    }
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd backend && ./gradlew test --tests "com.albunyaan.tube.service.MailServiceTest"
```
Expected: FAIL with "cannot find symbol method buildPasswordResetMessage".

- [ ] **Step 3: Implement happy-path send + helper**

In `MailService.java`, replace the `sendPasswordResetEmail` body and add `buildPasswordResetMessage`:

```java
    @Async
    public void sendPasswordResetEmail(String to, String resetLink) {
        if (!enabled) {
            log.info("mail.disabled to={}", to);
            return;
        }
        try {
            com.microsoft.graph.models.Message msg = buildPasswordResetMessage(to, resetLink);
            com.microsoft.graph.users.item.sendmail.SendMailPostRequestBody body =
                    new com.microsoft.graph.users.item.sendmail.SendMailPostRequestBody();
            body.setMessage(msg);
            body.setSaveToSentItems(false);
            graph.users().byUserId(fromAddress).sendMail().post(body);
            meters.counter("email.send.success", "type", "password_reset").increment();
            log.info("password_reset_email.sent to={}", to);
        } catch (Exception e) {
            // Implemented fully in T5.
            log.error("password_reset_email.failed to={}", to, e);
            meters.counter("email.send.failure", "type", "password_reset").increment();
        }
    }

    /** Package-private for unit-testability. */
    com.microsoft.graph.models.Message buildPasswordResetMessage(String to, String link) {
        com.microsoft.graph.models.Message m = new com.microsoft.graph.models.Message();
        m.setSubject("Reset your FitrahTube password");

        com.microsoft.graph.models.ItemBody body = new com.microsoft.graph.models.ItemBody();
        body.setContentType(com.microsoft.graph.models.BodyType.Text);
        body.setContent(
                "Hi,\n\n"
              + "We received a request to reset your FitrahTube password.\n"
              + "Click the link below to set a new password:\n\n"
              + link + "\n\n"
              + "This link expires in 1 hour. If you didn't request a reset, ignore this email — "
              + "your account is safe.\n\n"
              + "This is an automated message from " + fromDisplayName
              + ". Replies to this address are not monitored.\n");
        m.setBody(body);

        com.microsoft.graph.models.Recipient r = new com.microsoft.graph.models.Recipient();
        com.microsoft.graph.models.EmailAddress addr = new com.microsoft.graph.models.EmailAddress();
        addr.setAddress(to);
        r.setEmailAddress(addr);
        java.util.LinkedList<com.microsoft.graph.models.Recipient> toRecipients = new java.util.LinkedList<>();
        toRecipients.add(r);
        m.setToRecipients(toRecipients);

        return m;
    }
```

- [ ] **Step 4: Run test to verify it passes**

```bash
cd backend && ./gradlew test --tests "com.albunyaan.tube.service.MailServiceTest"
```
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/albunyaan/tube/service/MailService.java \
        backend/src/test/java/com/albunyaan/tube/service/MailServiceTest.java
git commit -m "[FEAT-ADMIN-USER-01-T4]: MailService happy-path send + buildPasswordResetMessage"
```

---

## Task 5: `MailService` failure-path → audit + counter (TDD)

**Files:**
- Modify: `backend/src/main/java/com/albunyaan/tube/service/MailService.java`
- Modify: `backend/src/test/java/com/albunyaan/tube/service/MailServiceTest.java`

Per spec F7 — silent failure + audit `USER_PASSWORD_RESET_EMAIL_FAILED`.

> **Audit signature amendment (2026-05-12, post-AuditLogService recon):** The existing `AuditLogService` API (Plan E) has no overload matching the spec §6 example call `log(action, "user", to, "system", "system", Map.of(...))`. The available overloads are:
> - `log(String action, String entityType, String entityId, FirebaseUserDetails actor)` — requires non-null actor
> - `log(String action, String entityType, String entityId, FirebaseUserDetails actor, Map<String, Object> details)` — requires non-null actor
> - `logSystem(String action, String entityType, String entityId, String actorDescription)` — no details Map
>
> Per the original plan directive "If the existing signature differs, adapt this call site (DO NOT add an overload)", T5 uses `logSystem(...)`. The error class metadata is captured in the application log (`log.error(..., e)`) rather than the audit Map. Acceptable trade: audit captures WHO + WHEN; app log captures stack-trace diagnostics. If admins later need error-class breakdowns in audit, that's a follow-up to extend `AuditLogService` (deferred).

- [ ] **Step 1: Add failing failure-path test**

Append to `MailServiceTest.java`:

```java
    @Test
    void enabledMail_whenSendThrows_logsCountsAndAudits() {
        // The exception path is exercised via a subclass that exposes the package-private
        // handleSendFailure helper. We don't need real Graph/HTTP — we just need to prove
        // the handler increments the failure counter and emits the system audit log.
        MailProperties mail = new MailProperties();
        mail.setEnabled(false); // skip Graph init in constructor
        mail.setFromAddress("noreply@fitrahtube.com");
        mail.setFromDisplayName("FitrahTube");
        AzureProperties azure = new AzureProperties();
        MeterRegistry meters = new SimpleMeterRegistry();
        AuditLogService auditLog = mock(AuditLogService.class);

        // Subclass-with-accessor pattern: exposes the package-private helper via a public
        // simulateFailure(...) entry point so the test doesn't need reflection.
        class TestableMailService extends MailService {
            TestableMailService() { super(mail, azure, meters, auditLog); }
            void simulateFailure(String to) {
                handleSendFailure(to, new RuntimeException("graph 503"));
            }
        }
        TestableMailService svc = new TestableMailService();

        svc.simulateFailure("user@example.com");

        assertEquals(1.0, meters.counter("email.send.failure", "type", "password_reset").count());
        verify(auditLog).logSystem(
                eq("USER_PASSWORD_RESET_EMAIL_FAILED"),
                eq("user"),
                eq("user@example.com"),
                anyString());
    }
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd backend && ./gradlew test --tests "com.albunyaan.tube.service.MailServiceTest"
```
Expected: FAIL with "cannot find symbol method handleSendFailure".

- [ ] **Step 3: Extract failure handler in `MailService`**

Replace the catch block in `sendPasswordResetEmail`, and add a package-private helper:

```java
        } catch (Exception e) {
            handleSendFailure(to, e);
        }
    }

    /** Package-private for unit test override. */
    void handleSendFailure(String to, Exception e) {
        log.error("password_reset_email.failed to={}", to, e);
        meters.counter("email.send.failure", "type", "password_reset").increment();
        auditLog.logSystem(
                "USER_PASSWORD_RESET_EMAIL_FAILED",
                "user",
                to,
                "mail-service: error=" + e.getClass().getSimpleName());
    }
```

> The `actorDescription` argument is repurposed to carry the error class (`mail-service: error=…`) since `logSystem` has no details Map. App log captures the full stack trace separately at ERROR level.

- [ ] **Step 4: Run test to verify it passes**

```bash
cd backend && ./gradlew test --tests "com.albunyaan.tube.service.MailServiceTest"
```
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/albunyaan/tube/service/MailService.java \
        backend/src/test/java/com/albunyaan/tube/service/MailServiceTest.java
git commit -m "[FEAT-ADMIN-USER-01-T5]: MailService failure path logs + counter + audit"
```

---

## Task 6: Wire `MailService` into `AuthService.sendPasswordResetEmail`

**Files:** modify `backend/src/main/java/com/albunyaan/tube/service/AuthService.java`

Per spec §6 — replace TODO at L689.

- [ ] **Step 1: Inject `MailService` into `AuthService`**

Locate the existing constructor (or constructor-injected fields). Add a `private final MailService mailService;` field, accept it in the constructor, assign it.

- [ ] **Step 2: Replace the TODO in `sendPasswordResetEmail` (around line 685–690)**

Find the existing method:
```java
public void sendPasswordResetEmail(String email) throws FirebaseAuthException {
    String link = firebaseAuth.generatePasswordResetLink(email);
    logger.info("Password reset link generated for: {}", email);
    // TODO: Integrate with email service
}
```

Replace the body's tail with:
```java
public void sendPasswordResetEmail(String email) throws FirebaseAuthException {
    String link = firebaseAuth.generatePasswordResetLink(email);
    logger.info("Password reset link generated for: {}", email);
    mailService.sendPasswordResetEmail(email, link);
}
```

> **No new audit entry needed here** — the existing reset-password flow's audit log is written by the controller, not by this helper.

- [ ] **Step 3: Compile**

```bash
cd backend && ./gradlew compileJava
```
Expected: BUILD SUCCESSFUL. Existing tests that instantiate `AuthService` directly (without Spring) will fail to compile — update those test fixtures to pass a mocked `MailService`.

- [ ] **Step 4: Re-run existing AuthService tests**

```bash
cd backend && ./gradlew test --tests "com.albunyaan.tube.service.AuthService*Test"
```
Expected: all green. Fix any constructor-arg breakage by wiring `mock(MailService.class)`.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/albunyaan/tube/service/AuthService.java \
        backend/src/test/java/com/albunyaan/tube/service/   # any test fixture updates
git commit -m "[FEAT-ADMIN-USER-01-T6]: wire MailService into AuthService.sendPasswordResetEmail"
```

---

## Task 7: Application config — `mail.*` + `azure.*` blocks

**Files:**
- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/src/test/resources/application-test.yml`

Per spec §6 — `MAIL_ENABLED` defaults true in prod, false in test.

- [ ] **Step 1: Append to `backend/src/main/resources/application.yml`**

Add a top-level block (alongside `spring:`, `server:` etc.). Do NOT nest under `spring:`:

```yaml
# Plan F (ADMIN-USER-01) — outbound mail via Microsoft Graph
mail:
  enabled: ${MAIL_ENABLED:true}
  from-address: ${MAIL_FROM_ADDRESS:noreply@fitrahtube.com}
  from-display-name: ${MAIL_FROM_DISPLAY_NAME:FitrahTube}

azure:
  tenant-id: ${AZURE_TENANT_ID:}
  client-id: ${AZURE_CLIENT_ID:}
  client-secret: ${AZURE_CLIENT_SECRET:}
```

- [ ] **Step 2: Append to `backend/src/test/resources/application-test.yml`**

```yaml
# Plan F — disable outbound mail in tests
mail:
  enabled: false
  from-address: noreply@test.fitrahtube.com
  from-display-name: FitrahTube Test

azure:
  tenant-id: test-tenant
  client-id: test-client
  client-secret: test-secret
```

- [ ] **Step 3: Compile + run a quick sanity test**

```bash
cd backend && ./gradlew test --tests "com.albunyaan.tube.service.MailServiceTest"
```
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/resources/application.yml \
        backend/src/test/resources/application-test.yml
git commit -m "[FEAT-ADMIN-USER-01-T7]: application.yml mail.* + azure.* config"
```

---

## Task 8: Mail startup health check

**Files:** create `backend/src/main/java/com/albunyaan/tube/service/MailServiceStartupCheck.java`

Per spec risk §11.3 — refuse to start if `mail.enabled=true` and the configured from-address mailbox is unreachable.

- [ ] **Step 1: Implement the startup runner**

```java
package com.albunyaan.tube.service;

import com.albunyaan.tube.config.MailProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Plan F (ADMIN-USER-01) risk §11.3 — when mail is enabled, prove the configured
 * from-address mailbox is reachable via Graph at startup. Hard-fail if not, so the
 * operator catches the misconfiguration immediately (rather than discovering it
 * the first time an admin clicks "Reset password").
 */
@Component
public class MailServiceStartupCheck implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(MailServiceStartupCheck.class);

    private final MailProperties mail;
    private final MailService mailService;
    private final boolean failOnError;

    public MailServiceStartupCheck(MailProperties mail,
                                    MailService mailService,
                                    @Value("${mail.startup-check.fail-on-error:true}") boolean failOnError) {
        this.mail = mail;
        this.mailService = mailService;
        this.failOnError = failOnError;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!mail.isEnabled()) {
            log.info("mail.startup-check.skipped (mail.enabled=false)");
            return;
        }
        try {
            mailService.verifyFromMailboxReachable();
            log.info("mail.startup-check.ok from={}", mail.getFromAddress());
        } catch (Exception e) {
            log.error("mail.startup-check.failed from={} error={}",
                    mail.getFromAddress(), e.getMessage(), e);
            if (failOnError) {
                throw new IllegalStateException(
                        "Mail startup check failed for " + mail.getFromAddress(), e);
            }
        }
    }
}
```

- [ ] **Step 2: Add `verifyFromMailboxReachable()` to `MailService`**

Append to `MailService.java`:

```java
    /** Plan F risk §11.3 — Graph users.byUserId(fromAddress).get() smoke call. */
    public void verifyFromMailboxReachable() {
        if (!enabled) return;
        graph.users().byUserId(fromAddress).get();
    }
```

> **SDK note:** v6 Kiota syntax — `graph.users().byUserId(id).get()` replaces v5's `graph.users(id).buildRequest().get()`.

- [ ] **Step 3: Compile**

```bash
cd backend && ./gradlew compileJava
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/albunyaan/tube/service/MailServiceStartupCheck.java \
        backend/src/main/java/com/albunyaan/tube/service/MailService.java
git commit -m "[FEAT-ADMIN-USER-01-T8]: mail startup health check (verify from-address reachable)"
```

---

# Phase 2 — Force-Logout (Single + Auto)

## Task 9: Extract `AuthService.revokeSessions(uid, actor, reason)`

**Files:**
- Modify: `backend/src/main/java/com/albunyaan/tube/service/AuthService.java`
- Test:   `backend/src/test/java/com/albunyaan/tube/service/AuthServiceRevokeSessionsTest.java`

Per spec §7 note + F6 — new public method extracted from existing inline `firebaseAuth.revokeRefreshTokens(uid)` calls.

> **API alignment amendment (2026-05-12, post-AuditLogService recon):** The existing `AuditLogService.log(...)` overload signature is `log(String action, String entityType, String entityId, FirebaseUserDetails actor[, Map<String,Object> details])` — no overload takes separate `(actorUid, actorEmail)` string pair. Plan F uses the existing 5-arg overload. The service method therefore accepts `FirebaseUserDetails actor` (not `String actorUid`); the controller (T10) extracts it from SecurityContext. Auto-revoke (T11) deals with the `updateUserRoleAsActor` string-actor case via a synthetic `FirebaseUserDetails` constructed from the actorUid for low-fidelity attribution (uid populated, email/role null) — see T11 for details.

- [ ] **Step 1: Write the failing test**

```java
package com.albunyaan.tube.service;

import com.albunyaan.tube.security.FirebaseUserDetails;
import com.google.firebase.auth.FirebaseAuth;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthServiceRevokeSessionsTest {

    @Test
    void revokeSessions_callsFirebase_andAuditsWithReason() throws Exception {
        FirebaseAuth firebaseAuth = mock(FirebaseAuth.class);
        AuditLogService auditLog = mock(AuditLogService.class);
        FirebaseUserDetails actor = new FirebaseUserDetails("admin-uid", "admin@fitrahtube.com", "admin");
        AuthService svc = AuthServiceTestFactory.with(firebaseAuth, auditLog);

        svc.revokeSessions("target-uid", actor, "user reported phishing");

        verify(firebaseAuth).revokeRefreshTokens("target-uid");

        ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
        verify(auditLog).log(
                eq("USER_SESSIONS_REVOKED"),
                eq("user"), eq("target-uid"),
                eq(actor),
                details.capture());
        assertEquals("user reported phishing", details.getValue().get("reason"));
    }

    @Test
    void revokeSessions_nullReason_auditsWithoutReasonKey() throws Exception {
        FirebaseAuth firebaseAuth = mock(FirebaseAuth.class);
        AuditLogService auditLog = mock(AuditLogService.class);
        FirebaseUserDetails actor = new FirebaseUserDetails("admin-uid", "admin@fitrahtube.com", "admin");
        AuthService svc = AuthServiceTestFactory.with(firebaseAuth, auditLog);

        svc.revokeSessions("target-uid", actor, null);

        verify(firebaseAuth).revokeRefreshTokens("target-uid");
        verify(auditLog).log(
                eq("USER_SESSIONS_REVOKED"),
                eq("user"), eq("target-uid"),
                eq(actor),
                argThat(m -> !m.containsKey("reason") || m.get("reason") == null));
    }
}
```

> **Note:** `AuthServiceTestFactory` is a small helper that builds an AuthService with default mocks for everything except the two args you care about. Create one in this task if no equivalent exists; otherwise reuse what `AuthServiceBlockIntegrationTest` (or similar) already provides.

- [ ] **Step 2: Run test to verify it fails**

```bash
cd backend && ./gradlew test --tests "com.albunyaan.tube.service.AuthServiceRevokeSessionsTest"
```
Expected: FAIL with "cannot find symbol method revokeSessions".

- [ ] **Step 3: Implement `revokeSessions`**

In `AuthService.java`, after the existing `recoverUser` method (around line 472+), add:

```java
    /**
     * Plan F (ADMIN-USER-01, F6) — stand-alone refresh-token revocation.
     * Extracted from the inline calls in {@link #blockUser} / {@link #softDeleteUser}
     * so admins can force-logout a user without changing their account state.
     *
     * @param uid     target user
     * @param actor   admin performing the action (SecurityContext principal)
     * @param reason  optional free-text reason captured in audit details
     */
    public void revokeSessions(String uid,
                               com.albunyaan.tube.security.FirebaseUserDetails actor,
                               String reason)
            throws com.google.firebase.auth.FirebaseAuthException {
        firebaseAuth.revokeRefreshTokens(uid);
        java.util.Map<String, Object> details = new java.util.HashMap<>();
        if (reason != null && !reason.isBlank()) details.put("reason", reason);
        auditLogService.log(
                "USER_SESSIONS_REVOKED",
                "user", uid,
                actor,
                details);
    }
```

- [ ] **Step 4: Run test to verify it passes**

```bash
cd backend && ./gradlew test --tests "com.albunyaan.tube.service.AuthServiceRevokeSessionsTest"
```
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/albunyaan/tube/service/AuthService.java \
        backend/src/test/java/com/albunyaan/tube/service/AuthServiceRevokeSessionsTest.java
git commit -m "[FEAT-ADMIN-USER-01-T9]: AuthService.revokeSessions(uid, actor, reason) + audit"
```

---

## Task 10: `RevokeSessionsRequest` DTO + single revoke-sessions endpoint

**Files:**
- Create: `backend/src/main/java/com/albunyaan/tube/dto/RevokeSessionsRequest.java`
- Modify: `backend/src/main/java/com/albunyaan/tube/controller/UserController.java`

Per spec §5 — `POST /api/admin/users/{uid}/revoke-sessions` → 204.

- [ ] **Step 1: Create the DTO**

```java
package com.albunyaan.tube.dto;

/**
 * Plan F (ADMIN-USER-01) — body for POST /api/admin/users/{uid}/revoke-sessions.
 * F6 — reason is optional, captured in audit details when present.
 */
public class RevokeSessionsRequest {
    private String reason;

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
```

- [ ] **Step 2: Add the endpoint to `UserController.java`**

After the existing `/{uid}/reset-password` handler (around line 266), append:

```java
    /**
     * Plan F (ADMIN-USER-01, F6) — stand-alone force-logout.
     * Revokes the target user's refresh tokens. They stay signed in for up to
     * one hour (until their current JWT expires) but cannot mint a new one.
     */
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{uid}/revoke-sessions")
    public ResponseEntity<Void> revokeSessions(
            @PathVariable String uid,
            @RequestBody(required = false) RevokeSessionsRequest body,
            @AuthenticationPrincipal com.albunyaan.tube.security.FirebaseUserDetails actor) {
        try {
            String reason = body != null ? body.getReason() : null;
            authService.revokeSessions(uid, actor, reason);
            return ResponseEntity.noContent().build();
        } catch (com.google.firebase.auth.FirebaseAuthException e) {
            log.error("revoke-sessions failed uid={}", uid, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
```

Imports to add at the top of the file (if not already present): `com.albunyaan.tube.dto.RevokeSessionsRequest`, `org.springframework.web.bind.annotation.RequestBody`.

- [ ] **Step 3: Compile**

```bash
cd backend && ./gradlew compileJava
```

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/albunyaan/tube/dto/RevokeSessionsRequest.java \
        backend/src/main/java/com/albunyaan/tube/controller/UserController.java
git commit -m "[FEAT-ADMIN-USER-01-T10]: POST /api/admin/users/{uid}/revoke-sessions endpoint"
```

---

## Task 11: Auto-revoke on role change

**Files:**
- Modify: `backend/src/main/java/com/albunyaan/tube/service/AuthService.java`

Per spec F6 (implicit, auto-fired) + risk §11.6 (never block role change on async cleanup).

- [ ] **Step 1: Locate `updateUserRoleAsActor`**

In `AuthService.java`, around line 210, the method is:
```java
public User updateUserRoleAsActor(String uid, String newRoleStr, String actorUid)
```

Identify the success branch (where the transaction commits and the user is returned).

- [ ] **Step 2: Fire async auto-revoke after the commit**

> **AuditLogService signature note (post-T9 amendment):** `auditLogService.log(...)` takes a `FirebaseUserDetails` actor, not separate `(actorUid, actorEmail)` strings. The deep-stack location here has only the `String actorUid` (Plan A's pre-existing `updateUserRoleAsActor` signature). We construct a synthetic `FirebaseUserDetails(actorUid, null, "admin")` solely for the audit call — this preserves the structured `details` Map (which T12's IT asserts) and the admin's UID in the audit row; the only loss is `actorDisplayName` (null instead of the admin's email). Acceptable trade vs. rippling a `FirebaseUserDetails actor` parameter through Plan A's API.

Inside `updateUserRoleAsActor`, after the existing `auditLogService.log("USER_ROLE_UPDATED", ...)` call (find it by grep — should already exist from Plan A), append:

```java
        // Plan F (ADMIN-USER-01, F6) — auto-revoke refresh tokens so the new role
        // takes effect immediately rather than after the existing JWT expires.
        // Errors are absorbed: the role change has already committed. Audit entry
        // distinguishes the auto-fire from an admin-triggered revoke.
        com.albunyaan.tube.security.FirebaseUserDetails actor =
                new com.albunyaan.tube.security.FirebaseUserDetails(actorUid, null, "admin");
        try {
            firebaseAuth.revokeRefreshTokens(uid);
            auditLogService.log(
                    "USER_SESSIONS_REVOKED_AUTO",
                    "user", uid,
                    actor,
                    java.util.Map.of(
                            "oldRole", existingRole == null ? "" : existingRole,
                            "newRole", newRoleStr,
                            "trigger", "role_change"));
        } catch (Exception e) {
            // F6 + risk §11.6: log + audit-failure, never throw.
            logger.error("auto-revoke after role change failed uid={}", uid, e);
            auditLogService.log(
                    "USER_SESSIONS_REVOKED_AUTO_FAILED",
                    "user", uid,
                    actor,
                    java.util.Map.of("error", e.getClass().getSimpleName()));
        }
```

> **`existingRole` variable:** if the surrounding code already captures the prior role into a local (search for `existingRole` or `oldRole` in the method), reuse it. If not, fetch it from the user object at method entry: `String existingRole = existing.getRole();` before the role mutation.

- [ ] **Step 3: Compile**

```bash
cd backend && ./gradlew compileJava
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/albunyaan/tube/service/AuthService.java
git commit -m "[FEAT-ADMIN-USER-01-T11]: auto-revoke refresh tokens on role change"
```

---

## Task 12: Auto-revoke IT (`AutoRevokeOnRoleChangeIT`)

**Files:** create `backend/src/test/java/com/albunyaan/tube/integration/AutoRevokeOnRoleChangeIT.java`

Per spec §10 — PUT role from USER → MODERATOR; assert audit entry with details.

- [ ] **Step 1: Write the integration test**

```java
package com.albunyaan.tube.integration;

import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.QuerySnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Plan F (ADMIN-USER-01) — verify that PUT /api/admin/users/{uid}/role fires
 * USER_SESSIONS_REVOKED_AUTO with the role transition details.
 */
public class AutoRevokeOnRoleChangeIT extends BaseIntegrationTest {

    @Test
    void putRole_firesAutoRevoke_andAudits() throws Exception {
        String adminUid = seedAdmin("admin@test.com", "Admin");
        String targetUid = seedUser("target@test.com", "Target", "user", "active");
        String adminToken = stubIdToken(adminUid);

        MvcResult result = mvc.perform(put("/api/admin/users/" + targetUid + "/role")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content("{\"role\":\"moderator\"}"))
                .andExpect(status().isOk())
                .andReturn();

        // Allow async + post-commit work to complete.
        Thread.sleep(500);

        // Assert audit entry written.
        QuerySnapshot snap = firestore.collection("audit_logs")
                .whereEqualTo("action", "USER_SESSIONS_REVOKED_AUTO")
                .whereEqualTo("entityId", targetUid)
                .get().get();

        List<QueryDocumentSnapshot> docs = snap.getDocuments();
        assertEquals(1, docs.size(), "expected exactly one USER_SESSIONS_REVOKED_AUTO entry");

        java.util.Map<String, Object> details =
                (java.util.Map<String, Object>) docs.get(0).get("details");
        assertEquals("user", details.get("oldRole"));
        assertEquals("moderator", details.get("newRole"));
        assertEquals("role_change", details.get("trigger"));
    }
}
```

> **Helper assumptions:** `seedAdmin`, `seedUser`, `stubIdToken` follow patterns from `AccountStatusFilterIntegrationTest`. If signatures differ in this codebase, adapt accordingly — the spec test description in §10 is the authoritative behaviour: "PUT role from USER → MODERATOR. Assert role updated, revoke fired, `USER_SESSIONS_REVOKED_AUTO` audit with `details.oldRole=user, newRole=moderator`."

- [ ] **Step 2: Run the IT**

```bash
cd backend && ./gradlew test -Pintegration=true \
    --tests "com.albunyaan.tube.integration.AutoRevokeOnRoleChangeIT"
```
Expected: PASS. Requires `firebase emulators:start --only firestore,auth --project demo-test` running.

- [ ] **Step 3: Commit**

```bash
git add backend/src/test/java/com/albunyaan/tube/integration/AutoRevokeOnRoleChangeIT.java
git commit -m "[TEST-ADMIN-USER-01-T12]: AutoRevokeOnRoleChangeIT covers F6 auto-revoke path"
```

---

# Phase 3 — Bulk Actions

## Task 13: `BulkAction` enum + bulk DTOs

**Files:**
- Create: `backend/src/main/java/com/albunyaan/tube/service/BulkAction.java`
- Create: `backend/src/main/java/com/albunyaan/tube/dto/BulkUserActionRequest.java`
- Create: `backend/src/main/java/com/albunyaan/tube/dto/BulkUserActionResult.java`

Per spec §4 + §7 (F4 contract: best-effort, 1..100 uids).

- [ ] **Step 1: Create `BulkAction.java`**

```java
package com.albunyaan.tube.service;

/**
 * Plan F (ADMIN-USER-01) — bulk user-management action discriminator.
 * RECOVER deliberately permits admin targets (re-activating a soft-deleted admin).
 * BLOCK / DELETE / REVOKE_SESSIONS refuse admin targets — see F5.
 */
public enum BulkAction {
    BLOCK,
    DELETE,
    RECOVER,
    REVOKE_SESSIONS
}
```

- [ ] **Step 2: Create `BulkUserActionRequest.java`**

```java
package com.albunyaan.tube.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Plan F (ADMIN-USER-01, F4) — request body for the four bulk endpoints.
 * Bean Validation enforces 1 ≤ uids.size() ≤ 100.
 */
public class BulkUserActionRequest {

    @NotNull
    @Size(min = 1, max = 100, message = "uids must contain 1 to 100 entries")
    private List<String> uids;

    private String reason; // optional, recorded in summary audit

    public List<String> getUids() { return uids; }
    public void setUids(List<String> uids) { this.uids = uids; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
```

- [ ] **Step 3: Create `BulkUserActionResult.java`**

```java
package com.albunyaan.tube.dto;

import java.util.List;

/**
 * Plan F (ADMIN-USER-01, F4) — bulk action response.
 * Always HTTP 200 regardless of mixed success/failure. Per-uid outcome lives here.
 */
public class BulkUserActionResult {

    public record FailureEntry(String uid, String reason) {}

    private final List<String> successes;
    private final List<FailureEntry> failures;

    public BulkUserActionResult(List<String> successes, List<FailureEntry> failures) {
        this.successes = successes;
        this.failures = failures;
    }

    public List<String> getSuccesses() { return successes; }
    public List<FailureEntry> getFailures() { return failures; }
}
```

- [ ] **Step 4: Compile**

```bash
cd backend && ./gradlew compileJava
```

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/albunyaan/tube/service/BulkAction.java \
        backend/src/main/java/com/albunyaan/tube/dto/BulkUserActionRequest.java \
        backend/src/main/java/com/albunyaan/tube/dto/BulkUserActionResult.java
git commit -m "[FEAT-ADMIN-USER-01-T13]: BulkAction enum + BulkUserActionRequest/Result DTOs"
```

---

## Task 14: `BulkUserService` happy path (TDD)

**Files:**
- Create: `backend/src/main/java/com/albunyaan/tube/service/BulkUserService.java`
- Test:   `backend/src/test/java/com/albunyaan/tube/service/BulkUserServiceTest.java`

Per spec §7 BulkUserService design.

- [ ] **Step 1: Write the failing happy-path test**

```java
package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.BulkUserActionResult;
import com.albunyaan.tube.model.User;
import com.albunyaan.tube.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BulkUserServiceTest {

    @Test
    void happyPath_block_threeUsersSucceed() throws Exception {
        AuthService authService = mock(AuthService.class);
        UserRepository userRepo = mock(UserRepository.class);
        AuditLogService auditLog = mock(AuditLogService.class);

        // None are admins.
        when(userRepo.findByUid(anyString())).thenReturn(Optional.of(userWithRole("user")));

        BulkUserService svc = new BulkUserService(authService, userRepo, auditLog);

        BulkUserActionResult result = svc.execute(
                BulkAction.BLOCK,
                List.of("u1", "u2", "u3"),
                "admin-uid",
                "policy violation");

        assertEquals(List.of("u1", "u2", "u3"), result.getSuccesses());
        assertTrue(result.getFailures().isEmpty());

        verify(authService).blockUser("u1", "admin-uid", "policy violation");
        verify(authService).blockUser("u2", "admin-uid", "policy violation");
        verify(authService).blockUser("u3", "admin-uid", "policy violation");

        verify(auditLog).log(
                eq("USER_BULK_ACTION"),
                eq("user"), eq("(batch)"),
                eq("admin-uid"), eq("admin-uid"),
                argThat(m -> "block".equals(m.get("action"))
                        && Integer.valueOf(3).equals(m.get("successes"))
                        && Integer.valueOf(0).equals(m.get("failures"))));
    }

    private static User userWithRole(String role) {
        User u = new User();
        u.setRole(role);
        return u;
    }
}
```

> **`AuditLogService.log(...)` signature mismatch:** if the existing signature differs from the 6-arg form above (e.g., no `details` map), match whatever pattern Plan E's `ApprovalService.requestChanges` uses for its audit entries. Consistency with existing rows wins.

- [ ] **Step 2: Run test to verify it fails**

```bash
cd backend && ./gradlew test --tests "com.albunyaan.tube.service.BulkUserServiceTest"
```
Expected: FAIL with "cannot resolve symbol BulkUserService".

- [ ] **Step 3: Implement `BulkUserService` (happy path only)**

```java
package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.BulkUserActionResult;
import com.albunyaan.tube.dto.BulkUserActionResult.FailureEntry;
import com.albunyaan.tube.model.User;
import com.albunyaan.tube.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Plan F (ADMIN-USER-01) — best-effort bulk processor.
 * Each uid processed independently in its own try/catch (F4).
 * Self-action and admin-target rules (F5) applied before the underlying call.
 */
@Service
public class BulkUserService {
    private static final Logger log = LoggerFactory.getLogger(BulkUserService.class);

    private final AuthService authService;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;

    public BulkUserService(AuthService authService,
                            UserRepository userRepository,
                            AuditLogService auditLogService) {
        this.authService = authService;
        this.userRepository = userRepository;
        this.auditLogService = auditLogService;
    }

    public BulkUserActionResult execute(BulkAction action,
                                         List<String> uids,
                                         String actorUid,
                                         String reason) {
        List<String> successes = new ArrayList<>();
        List<FailureEntry> failures = new ArrayList<>();

        for (String uid : uids) {
            try {
                switch (action) {
                    case BLOCK            -> authService.blockUser(uid, actorUid, reason);
                    case DELETE           -> authService.softDeleteUser(uid, actorUid, reason);
                    case RECOVER          -> authService.recoverUser(uid, actorUid);
                    case REVOKE_SESSIONS  -> authService.revokeSessions(uid, actorUid, reason);
                }
                successes.add(uid);
            } catch (Exception e) {
                log.error("bulk.action.error uid={} action={}", uid, action, e);
                failures.add(new FailureEntry(uid, "firebase_error"));
            }
        }

        Map<String, Object> details = new HashMap<>();
        details.put("action", action.name().toLowerCase());
        details.put("successes", successes.size());
        details.put("failures", failures.size());
        if (reason != null && !reason.isBlank()) details.put("reason", reason);

        auditLogService.log(
                "USER_BULK_ACTION",
                "user", "(batch)",
                actorUid, actorUid,
                details);

        return new BulkUserActionResult(successes, failures);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
cd backend && ./gradlew test --tests "com.albunyaan.tube.service.BulkUserServiceTest"
```
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/albunyaan/tube/service/BulkUserService.java \
        backend/src/test/java/com/albunyaan/tube/service/BulkUserServiceTest.java
git commit -m "[FEAT-ADMIN-USER-01-T14]: BulkUserService happy path + summary audit"
```

---

## Task 15: BulkUserService self-protection (F5)

**Files:**
- Modify: `backend/src/main/java/com/albunyaan/tube/service/BulkUserService.java`
- Modify: `backend/src/test/java/com/albunyaan/tube/service/BulkUserServiceTest.java`

Per spec F5 — requesting admin's own uid → `self_action_forbidden`.

- [ ] **Step 1: Add failing self-protection test**

Append to `BulkUserServiceTest.java`:

```java
    @Test
    void selfAction_isBucketedAsFailure_andDoesNotCallAuth() throws Exception {
        AuthService authService = mock(AuthService.class);
        UserRepository userRepo = mock(UserRepository.class);
        AuditLogService auditLog = mock(AuditLogService.class);

        when(userRepo.findByUid("other-uid")).thenReturn(Optional.of(userWithRole("user")));

        BulkUserService svc = new BulkUserService(authService, userRepo, auditLog);

        BulkUserActionResult result = svc.execute(
                BulkAction.BLOCK,
                List.of("admin-uid", "other-uid"),
                "admin-uid",
                null);

        assertEquals(List.of("other-uid"), result.getSuccesses());
        assertEquals(1, result.getFailures().size());
        assertEquals("admin-uid", result.getFailures().get(0).uid());
        assertEquals("self_action_forbidden", result.getFailures().get(0).reason());

        verify(authService, never()).blockUser(eq("admin-uid"), any(), any());
        verify(authService).blockUser("other-uid", "admin-uid", null);
    }
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd backend && ./gradlew test --tests "com.albunyaan.tube.service.BulkUserServiceTest"
```
Expected: FAIL — current loop blindly processes every uid.

- [ ] **Step 3: Add self-protection guard at top of loop**

In `BulkUserService.execute`, immediately after `for (String uid : uids) {`, insert:

```java
            if (uid.equals(actorUid)) {
                failures.add(new FailureEntry(uid, "self_action_forbidden"));
                continue;
            }
```

- [ ] **Step 4: Run test to verify it passes**

```bash
cd backend && ./gradlew test --tests "com.albunyaan.tube.service.BulkUserServiceTest"
```
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/albunyaan/tube/service/BulkUserService.java \
        backend/src/test/java/com/albunyaan/tube/service/BulkUserServiceTest.java
git commit -m "[FEAT-ADMIN-USER-01-T15]: BulkUserService self-action protection (F5)"
```

---

## Task 16: BulkUserService admin-target protection (F5)

**Files:**
- Modify: `backend/src/main/java/com/albunyaan/tube/service/BulkUserService.java`
- Modify: `backend/src/test/java/com/albunyaan/tube/service/BulkUserServiceTest.java`

Per spec F5 — BLOCK / DELETE / REVOKE_SESSIONS refuse admin targets. RECOVER permits them.

- [ ] **Step 1: Add failing tests**

Append to `BulkUserServiceTest.java`:

```java
    @Test
    void blockAdminTarget_isRejected_withAdminTargetForbidden() throws Exception {
        AuthService authService = mock(AuthService.class);
        UserRepository userRepo = mock(UserRepository.class);
        AuditLogService auditLog = mock(AuditLogService.class);

        when(userRepo.findByUid("admin-target")).thenReturn(Optional.of(userWithRole("admin")));
        when(userRepo.findByUid("user-target")).thenReturn(Optional.of(userWithRole("user")));

        BulkUserService svc = new BulkUserService(authService, userRepo, auditLog);

        BulkUserActionResult result = svc.execute(
                BulkAction.BLOCK,
                List.of("admin-target", "user-target"),
                "actor",
                null);

        assertEquals(List.of("user-target"), result.getSuccesses());
        assertEquals(1, result.getFailures().size());
        assertEquals("admin_target_forbidden", result.getFailures().get(0).reason());
        verify(authService, never()).blockUser(eq("admin-target"), any(), any());
    }

    @Test
    void recoverAdminTarget_isAllowed() throws Exception {
        AuthService authService = mock(AuthService.class);
        UserRepository userRepo = mock(UserRepository.class);
        AuditLogService auditLog = mock(AuditLogService.class);

        when(userRepo.findByUid("admin-target")).thenReturn(Optional.of(userWithRole("admin")));

        BulkUserService svc = new BulkUserService(authService, userRepo, auditLog);

        BulkUserActionResult result = svc.execute(
                BulkAction.RECOVER,
                List.of("admin-target"),
                "actor",
                null);

        assertEquals(List.of("admin-target"), result.getSuccesses());
        assertTrue(result.getFailures().isEmpty());
        verify(authService).recoverUser("admin-target", "actor");
    }
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd backend && ./gradlew test --tests "com.albunyaan.tube.service.BulkUserServiceTest"
```
Expected: FAIL on `blockAdminTarget_isRejected_withAdminTargetForbidden`.

- [ ] **Step 3: Add admin-target guard**

In `BulkUserService.execute`, immediately after the self-action guard, insert:

```java
            if (action != BulkAction.RECOVER) {
                Optional<User> u = userRepository.findByUid(uid);
                if (u.isPresent() && "admin".equalsIgnoreCase(u.get().getRole())) {
                    failures.add(new FailureEntry(uid, "admin_target_forbidden"));
                    continue;
                }
            }
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
cd backend && ./gradlew test --tests "com.albunyaan.tube.service.BulkUserServiceTest"
```
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/albunyaan/tube/service/BulkUserService.java \
        backend/src/test/java/com/albunyaan/tube/service/BulkUserServiceTest.java
git commit -m "[FEAT-ADMIN-USER-01-T16]: BulkUserService admin-target protection (F5)"
```

---

## Task 17: BulkUserService error classification

**Files:**
- Modify: `backend/src/main/java/com/albunyaan/tube/service/BulkUserService.java`
- Modify: `backend/src/test/java/com/albunyaan/tube/service/BulkUserServiceTest.java`

Per spec §7 `classify` helper — map `IllegalStateException` messages to stable reason codes.

- [ ] **Step 1: Add failing classification tests**

Append to `BulkUserServiceTest.java`:

```java
    @Test
    void alreadyBlockedException_classifiedAsAlreadyBlocked() throws Exception {
        AuthService authService = mock(AuthService.class);
        UserRepository userRepo = mock(UserRepository.class);
        AuditLogService auditLog = mock(AuditLogService.class);

        when(userRepo.findByUid("u1")).thenReturn(Optional.of(userWithRole("user")));
        doThrow(new IllegalStateException("User is already blocked"))
                .when(authService).blockUser("u1", "actor", null);

        BulkUserService svc = new BulkUserService(authService, userRepo, auditLog);

        BulkUserActionResult result = svc.execute(
                BulkAction.BLOCK, List.of("u1"), "actor", null);

        assertEquals("already_blocked", result.getFailures().get(0).reason());
    }

    @Test
    void notFoundException_classifiedAsUserNotFound() throws Exception {
        AuthService authService = mock(AuthService.class);
        UserRepository userRepo = mock(UserRepository.class);
        AuditLogService auditLog = mock(AuditLogService.class);

        when(userRepo.findByUid("u1")).thenReturn(Optional.of(userWithRole("user")));
        doThrow(new com.albunyaan.tube.service.UserNotFoundException("u1"))
                .when(authService).blockUser("u1", "actor", null);

        BulkUserService svc = new BulkUserService(authService, userRepo, auditLog);

        BulkUserActionResult result = svc.execute(
                BulkAction.BLOCK, List.of("u1"), "actor", null);

        assertEquals("user_not_found", result.getFailures().get(0).reason());
    }

    @Test
    void illegalStateAlreadyDeleted_classified() throws Exception {
        AuthService authService = mock(AuthService.class);
        UserRepository userRepo = mock(UserRepository.class);
        AuditLogService auditLog = mock(AuditLogService.class);

        when(userRepo.findByUid("u1")).thenReturn(Optional.of(userWithRole("user")));
        doThrow(new IllegalStateException("User already deleted"))
                .when(authService).softDeleteUser("u1", "actor", null);

        BulkUserService svc = new BulkUserService(authService, userRepo, auditLog);

        BulkUserActionResult result = svc.execute(
                BulkAction.DELETE, List.of("u1"), "actor", null);

        assertEquals("already_deleted", result.getFailures().get(0).reason());
    }

    @Test
    void unrecognizedIllegalStateMessage_classifiedAsInvalidState() throws Exception {
        AuthService authService = mock(AuthService.class);
        UserRepository userRepo = mock(UserRepository.class);
        AuditLogService auditLog = mock(AuditLogService.class);

        when(userRepo.findByUid("u1")).thenReturn(Optional.of(userWithRole("user")));
        doThrow(new IllegalStateException("some weird business rule"))
                .when(authService).blockUser("u1", "actor", null);

        BulkUserService svc = new BulkUserService(authService, userRepo, auditLog);

        BulkUserActionResult result = svc.execute(
                BulkAction.BLOCK, List.of("u1"), "actor", null);

        assertEquals("invalid_state", result.getFailures().get(0).reason());
    }
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd backend && ./gradlew test --tests "com.albunyaan.tube.service.BulkUserServiceTest"
```
Expected: FAIL — current catch lumps everything into `firebase_error`.

- [ ] **Step 3: Replace the catch block with classified handling**

In `BulkUserService.execute`, replace the single `catch (Exception e)` with:

```java
            } catch (com.albunyaan.tube.service.UserNotFoundException e) {
                failures.add(new FailureEntry(uid, "user_not_found"));
            } catch (IllegalStateException e) {
                failures.add(new FailureEntry(uid, classify(e.getMessage())));
            } catch (Exception e) {
                log.error("bulk.action.error uid={} action={}", uid, action, e);
                failures.add(new FailureEntry(uid, "firebase_error"));
            }
```

And add the helper method to the class:

```java
    private static String classify(String msg) {
        if (msg == null) return "invalid_state";
        String m = msg.toLowerCase();
        if (m.contains("already blocked")) return "already_blocked";
        if (m.contains("not blocked"))     return "not_blocked";
        if (m.contains("already deleted")) return "already_deleted";
        if (m.contains("not deleted"))     return "not_deleted";
        return "invalid_state";
    }
```

> **Note on `UserNotFoundException`:** the existing class is `com.albunyaan.tube.service.UserNotFoundException`. If `blockUser` etc. actually throw `FirebaseAuthException` with `getAuthErrorCode() == USER_NOT_FOUND`, add a second catch for that and map to `user_not_found` too.

- [ ] **Step 4: Run all BulkUserServiceTest tests**

```bash
cd backend && ./gradlew test --tests "com.albunyaan.tube.service.BulkUserServiceTest"
```
Expected: PASS (8 tests).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/albunyaan/tube/service/BulkUserService.java \
        backend/src/test/java/com/albunyaan/tube/service/BulkUserServiceTest.java
git commit -m "[FEAT-ADMIN-USER-01-T17]: BulkUserService error classification (F4)"
```

---

## Task 18: Four bulk endpoints in `UserController`

**Files:** modify `backend/src/main/java/com/albunyaan/tube/controller/UserController.java`

Per spec §5 — POST /bulk-block, /bulk-delete, /bulk-recover, /bulk-revoke-sessions.

- [ ] **Step 1: Inject `BulkUserService`**

Add a `private final BulkUserService bulkUserService;` field and wire it in the constructor.

- [ ] **Step 2: Add the four endpoints**

After the single `revoke-sessions` endpoint (added in T10), append:

```java
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/bulk-block")
    public ResponseEntity<BulkUserActionResult> bulkBlock(
            @Valid @RequestBody BulkUserActionRequest req,
            @AuthenticationPrincipal com.albunyaan.tube.security.FirebaseUserDetails actor) {
        return ResponseEntity.ok(bulkUserService.execute(
                BulkAction.BLOCK, req.getUids(), actor.getUid(), req.getReason()));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/bulk-delete")
    public ResponseEntity<BulkUserActionResult> bulkDelete(
            @Valid @RequestBody BulkUserActionRequest req,
            @AuthenticationPrincipal com.albunyaan.tube.security.FirebaseUserDetails actor) {
        return ResponseEntity.ok(bulkUserService.execute(
                BulkAction.DELETE, req.getUids(), actor.getUid(), req.getReason()));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/bulk-recover")
    public ResponseEntity<BulkUserActionResult> bulkRecover(
            @Valid @RequestBody BulkUserActionRequest req,
            @AuthenticationPrincipal com.albunyaan.tube.security.FirebaseUserDetails actor) {
        return ResponseEntity.ok(bulkUserService.execute(
                BulkAction.RECOVER, req.getUids(), actor.getUid(), req.getReason()));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/bulk-revoke-sessions")
    public ResponseEntity<BulkUserActionResult> bulkRevokeSessions(
            @Valid @RequestBody BulkUserActionRequest req,
            @AuthenticationPrincipal com.albunyaan.tube.security.FirebaseUserDetails actor) {
        return ResponseEntity.ok(bulkUserService.execute(
                BulkAction.REVOKE_SESSIONS, req.getUids(), actor.getUid(), req.getReason()));
    }
```

Imports to add: `com.albunyaan.tube.dto.BulkUserActionRequest`, `com.albunyaan.tube.dto.BulkUserActionResult`, `com.albunyaan.tube.service.BulkAction`, `com.albunyaan.tube.service.BulkUserService`, `jakarta.validation.Valid`.

- [ ] **Step 3: Compile**

```bash
cd backend && ./gradlew compileJava
```

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/albunyaan/tube/controller/UserController.java
git commit -m "[FEAT-ADMIN-USER-01-T18]: 4 bulk endpoints (block/delete/recover/revoke-sessions)"
```

---

## Task 19: Bulk + revoke-sessions integration tests

**Files:**
- Create: `backend/src/test/java/com/albunyaan/tube/integration/BulkUserActionIT.java`
- Create: `backend/src/test/java/com/albunyaan/tube/integration/RevokeSessionsIT.java`

Per spec §10.

- [ ] **Step 1: `BulkUserActionIT.java`**

```java
package com.albunyaan.tube.integration;

import com.google.cloud.firestore.QueryDocumentSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Plan F (ADMIN-USER-01) — bulk-block over 5 mixed users.
 * Spec §10: 5 seeded users (1 admin, 4 regular; one already blocked, one already deleted).
 * Bulk-block targeting all 5 → 200 + 2 successes + 3 failures with correct reasons.
 */
public class BulkUserActionIT extends BaseIntegrationTest {

    @Test
    void bulkBlock_5users_returnsMixedResultWithCorrectReasons() throws Exception {
        String adminUid = seedAdmin("admin@test.com", "Admin");
        String adminToken = stubIdToken(adminUid);

        String otherAdminUid = seedUser("admin2@test.com", "Admin2", "admin", "active");
        String regularActive1 = seedUser("u1@test.com", "U1", "user", "active");
        String regularActive2 = seedUser("u2@test.com", "U2", "user", "active");
        String regularBlocked = seedUser("u3@test.com", "U3", "user", "blocked");
        String regularDeleted = seedUser("u4@test.com", "U4", "user", "deleted");

        String body = String.format(
                "{\"uids\":[\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\"],\"reason\":\"audit\"}",
                adminUid, otherAdminUid, regularActive1, regularActive2,
                regularBlocked, regularDeleted);

        MvcResult res = mvc.perform(post("/api/admin/users/bulk-block")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();

        com.fasterxml.jackson.databind.JsonNode json =
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(res.getResponse().getContentAsString());

        // 2 successes (u1, u2), 4 failures (self, other admin, already blocked, already deleted).
        assertEquals(2, json.get("successes").size());
        assertEquals(4, json.get("failures").size());

        java.util.Set<String> reasons = new java.util.HashSet<>();
        json.get("failures").forEach(n -> reasons.add(n.get("reason").asText()));
        assertTrue(reasons.contains("self_action_forbidden"));
        assertTrue(reasons.contains("admin_target_forbidden"));
        assertTrue(reasons.contains("already_blocked"));
        // already-deleted target may surface as "already_deleted" or "invalid_state"
        // depending on AuthService.blockUser's behaviour; both are acceptable.
        assertTrue(reasons.contains("already_deleted") || reasons.contains("invalid_state"));

        // Allow async audit writes to settle.
        Thread.sleep(300);

        // 2 USER_BLOCKED + 1 USER_BULK_ACTION summary expected.
        long blockedCount = firestore.collection("audit_logs")
                .whereEqualTo("action", "USER_BLOCKED").get().get().size();
        long summaryCount = firestore.collection("audit_logs")
                .whereEqualTo("action", "USER_BULK_ACTION").get().get().size();
        assertEquals(2L, blockedCount);
        assertEquals(1L, summaryCount);
    }
}
```

- [ ] **Step 2: `RevokeSessionsIT.java`**

```java
package com.albunyaan.tube.integration;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.UserRecord;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Plan F (ADMIN-USER-01) — single + bulk revoke-sessions.
 * Spec §10: assert tokensValidAfterTime advances past Instant.now() - 1s.
 */
public class RevokeSessionsIT extends BaseIntegrationTest {

    @Test
    void singleRevokeSessions_advancesTokensValidAfterTime_andAudits() throws Exception {
        String adminUid = seedAdmin("admin@test.com", "Admin");
        String adminToken = stubIdToken(adminUid);
        String targetUid = seedUser("target@test.com", "Target", "user", "active");

        long before = System.currentTimeMillis() - 1000;

        mvc.perform(post("/api/admin/users/" + targetUid + "/revoke-sessions")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content("{\"reason\":\"reported phishing\"}"))
                .andExpect(status().isNoContent());

        Thread.sleep(300);

        // Assert Firebase Admin SDK recorded the revocation.
        UserRecord user = FirebaseAuth.getInstance().getUser(targetUid);
        Date validAfter = new Date(user.getTokensValidAfterTimestamp());
        assertTrue(validAfter.getTime() >= before,
                "tokensValidAfterTime should advance past " + new Date(before));

        // Audit entry with reason.
        var snap = firestore.collection("audit_logs")
                .whereEqualTo("action", "USER_SESSIONS_REVOKED")
                .whereEqualTo("entityId", targetUid)
                .get().get();
        assertEquals(1, snap.size());
        var details = (java.util.Map<String, Object>) snap.getDocuments().get(0).get("details");
        assertEquals("reported phishing", details.get("reason"));
    }

    @Test
    void bulkRevokeSessions_audits3perUid_plusSummary() throws Exception {
        String adminUid = seedAdmin("admin@test.com", "Admin");
        String adminToken = stubIdToken(adminUid);
        String u1 = seedUser("u1@test.com", "U1", "user", "active");
        String u2 = seedUser("u2@test.com", "U2", "user", "active");
        String u3 = seedUser("u3@test.com", "U3", "user", "active");

        String body = String.format(
                "{\"uids\":[\"%s\",\"%s\",\"%s\"],\"reason\":\"sweep\"}", u1, u2, u3);

        mvc.perform(post("/api/admin/users/bulk-revoke-sessions")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk());

        Thread.sleep(400);

        long perUid = firestore.collection("audit_logs")
                .whereEqualTo("action", "USER_SESSIONS_REVOKED").get().get().size();
        long summary = firestore.collection("audit_logs")
                .whereEqualTo("action", "USER_BULK_ACTION").get().get().size();
        assertEquals(3L, perUid);
        assertEquals(1L, summary);
    }
}
```

- [ ] **Step 3: Run both ITs**

```bash
cd backend && ./gradlew test -Pintegration=true \
    --tests "com.albunyaan.tube.integration.BulkUserActionIT" \
    --tests "com.albunyaan.tube.integration.RevokeSessionsIT"
```
Expected: PASS. Firebase emulator must be running.

- [ ] **Step 4: Commit**

```bash
git add backend/src/test/java/com/albunyaan/tube/integration/BulkUserActionIT.java \
        backend/src/test/java/com/albunyaan/tube/integration/RevokeSessionsIT.java
git commit -m "[TEST-ADMIN-USER-01-T19]: BulkUserActionIT + RevokeSessionsIT"
```

---

# Phase 4 — Audit Cursor Pagination

## Task 20: `AuditCursor` utility (TDD)

**Files:**
- Create: `backend/src/main/java/com/albunyaan/tube/util/AuditCursor.java`
- Test:   `backend/src/test/java/com/albunyaan/tube/util/AuditCursorTest.java`

Per spec §8 cursor encoding + F8 — base64url JSON `{ts, id}`.

- [ ] **Step 1: Write the failing test**

```java
package com.albunyaan.tube.util;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class AuditCursorTest {

    @Test
    void encodeThenDecode_returnsOriginalValues() {
        Instant ts = Instant.parse("2026-05-12T10:15:30.123Z");
        String docId = "abc-123";

        String cursor = AuditCursor.encode(ts, docId);
        AuditCursor.Decoded out = AuditCursor.decode(cursor);

        assertEquals(ts, out.ts());
        assertEquals(docId, out.docId());
    }

    @Test
    void encode_producesUrlSafeBase64() {
        Instant ts = Instant.parse("2026-05-12T10:15:30.123Z");
        String cursor = AuditCursor.encode(ts, "abc-123");
        assertFalse(cursor.contains("+"));
        assertFalse(cursor.contains("/"));
        assertFalse(cursor.contains("="));
    }

    @Test
    void decode_malformedBase64_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> AuditCursor.decode("!!!not base64!!!"));
    }

    @Test
    void decode_validBase64ButNotJson_throwsIllegalArgument() {
        String junk = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("not json".getBytes());
        assertThrows(IllegalArgumentException.class, () -> AuditCursor.decode(junk));
    }

    @Test
    void decode_missingFields_throwsIllegalArgument() {
        String missing = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"ts\":\"2026-05-12T10:00:00Z\"}".getBytes());
        assertThrows(IllegalArgumentException.class, () -> AuditCursor.decode(missing));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd backend && ./gradlew test --tests "com.albunyaan.tube.util.AuditCursorTest"
```
Expected: FAIL with "cannot resolve symbol AuditCursor".

- [ ] **Step 3: Implement `AuditCursor`**

```java
package com.albunyaan.tube.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Base64;
import java.util.Map;

/**
 * Plan F (ADMIN-USER-01, F8) — opaque base64url cursor for audit log pagination.
 * Encodes {"ts": ISO-8601, "id": Firestore document id} for Firestore's
 * .startAfter(documentSnapshot) tiebreak.
 */
public final class AuditCursor {
    private static final ObjectMapper M = new ObjectMapper();

    private AuditCursor() {}

    public static String encode(Instant ts, String docId) {
        try {
            byte[] json = M.writeValueAsBytes(Map.of("ts", ts.toString(), "id", docId));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(json);
        } catch (Exception e) {
            throw new IllegalStateException("Cursor encode failed", e);
        }
    }

    public static Decoded decode(String cursor) {
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(cursor);
            Map<String, String> m = M.readValue(bytes, new TypeReference<>() {});
            String ts = m.get("ts");
            String id = m.get("id");
            if (ts == null || id == null) {
                throw new IllegalArgumentException("Cursor missing ts or id field");
            }
            return new Decoded(Instant.parse(ts), id);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid cursor", e);
        }
    }

    public record Decoded(Instant ts, String docId) {}
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
cd backend && ./gradlew test --tests "com.albunyaan.tube.util.AuditCursorTest"
```
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/albunyaan/tube/util/AuditCursor.java \
        backend/src/test/java/com/albunyaan/tube/util/AuditCursorTest.java
git commit -m "[FEAT-ADMIN-USER-01-T20]: AuditCursor utility (base64url JSON)"
```

---

## Task 21: `PaginatedAuditLog` + `AuditLogService.findPaginated`

**Files:**
- Create: `backend/src/main/java/com/albunyaan/tube/dto/PaginatedAuditLog.java`
- Modify: `backend/src/main/java/com/albunyaan/tube/service/AuditLogService.java`

Per spec §8 AuditLogService change.

- [ ] **Step 1: Create `PaginatedAuditLog.java`**

```java
package com.albunyaan.tube.dto;

import com.albunyaan.tube.model.AuditLog;

import java.util.List;

/**
 * Plan F (ADMIN-USER-01, F8) — cursor-paginated audit log page.
 * nextCursor is null on the last page.
 */
public class PaginatedAuditLog {
    private final List<AuditLog> items;
    private final String nextCursor;

    public PaginatedAuditLog(List<AuditLog> items, String nextCursor) {
        this.items = items;
        this.nextCursor = nextCursor;
    }

    public List<AuditLog> getItems() { return items; }
    public String getNextCursor() { return nextCursor; }
}
```

> **Verify model name:** open `backend/src/main/java/com/albunyaan/tube/model/AuditLog.java` and confirm the class is named `AuditLog`. If the existing model is `AuditLogEntry` or similar, change the import + field type accordingly.

- [ ] **Step 2: Add `findPaginated` to `AuditLogService`**

In `AuditLogService.java`, add (consult existing imports / fields for the Firestore collection name + how queries are built):

```java
    /**
     * Plan F (ADMIN-USER-01, F8) — cursor pagination.
     * @param actorUid   optional actor filter
     * @param action     optional action filter
     * @param limit      page size, clamped 1..200, default 50 applied by caller
     * @param cursor     opaque base64url cursor; null/blank → first page
     */
    public com.albunyaan.tube.dto.PaginatedAuditLog findPaginated(
            String actorUid, String action, int limit, String cursor)
            throws java.util.concurrent.ExecutionException, InterruptedException {
        int effLimit = Math.min(Math.max(limit, 1), 200);
        com.google.cloud.firestore.Query q = firestore.collection("audit_logs")
                .orderBy("timestamp", com.google.cloud.firestore.Query.Direction.DESCENDING)
                .orderBy(com.google.cloud.firestore.FieldPath.documentId(),
                        com.google.cloud.firestore.Query.Direction.DESCENDING);

        if (actorUid != null && !actorUid.isBlank()) q = q.whereEqualTo("actorUid", actorUid);
        if (action != null && !action.isBlank())     q = q.whereEqualTo("action", action);

        if (cursor != null && !cursor.isBlank()) {
            com.albunyaan.tube.util.AuditCursor.Decoded c =
                    com.albunyaan.tube.util.AuditCursor.decode(cursor);
            com.google.cloud.firestore.DocumentSnapshot snap = firestore.collection("audit_logs")
                    .document(c.docId()).get().get();
            if (!snap.exists()) {
                throw new IllegalArgumentException("Cursor references missing document");
            }
            q = q.startAfter(snap);
        }

        com.google.cloud.firestore.QuerySnapshot snapAll = q.limit(effLimit + 1).get().get();
        var docs = snapAll.getDocuments();
        java.util.List<com.albunyaan.tube.model.AuditLog> rows =
                docs.stream()
                    .limit(effLimit)
                    .map(d -> d.toObject(com.albunyaan.tube.model.AuditLog.class))
                    .toList();

        boolean hasMore = docs.size() > effLimit;
        String nextCursor = null;
        if (hasMore) {
            var lastDoc = docs.get(effLimit - 1);
            com.albunyaan.tube.model.AuditLog last = lastDoc.toObject(com.albunyaan.tube.model.AuditLog.class);
            java.time.Instant ts = last.getTimestamp() != null
                    ? last.getTimestamp().toInstant()
                    : java.time.Instant.now();
            nextCursor = com.albunyaan.tube.util.AuditCursor.encode(ts, lastDoc.getId());
        }
        return new com.albunyaan.tube.dto.PaginatedAuditLog(rows, nextCursor);
    }
```

> **Field-name verification:** confirm `AuditLog.getTimestamp()` returns a `com.google.cloud.Timestamp` (or `Date`, or `Instant`). Adapt the `.toInstant()` call to whatever the model exposes. Same for the field name on the Firestore document (`timestamp` vs `createdAt`) — match the existing schema (Plan A already populates audit rows).

- [ ] **Step 3: Compile**

```bash
cd backend && ./gradlew compileJava
```

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/albunyaan/tube/dto/PaginatedAuditLog.java \
        backend/src/main/java/com/albunyaan/tube/service/AuditLogService.java
git commit -m "[FEAT-ADMIN-USER-01-T21]: AuditLogService.findPaginated(cursor, limit)"
```

---

## Task 22: Firestore composite indexes for audit pagination

**Files:** modify `backend/src/main/resources/firestore.indexes.json`

Per spec §8 — `audit_logs` (actorUid, timestamp DESC, __name__ DESC) and (action, timestamp DESC, __name__ DESC) must be declared explicitly. The unfiltered (timestamp DESC, __name__ DESC) index is auto-created on first query.

- [ ] **Step 1: Append three index entries**

Open `backend/src/main/resources/firestore.indexes.json`. Locate the closing `]` of the `"indexes"` array. Before that closing bracket, append (mind trailing comma on the existing last entry):

```json
    {
      "collectionGroup": "audit_logs",
      "queryScope": "COLLECTION",
      "fields": [
        { "fieldPath": "timestamp", "order": "DESCENDING" },
        { "fieldPath": "__name__",  "order": "DESCENDING" }
      ]
    },
    {
      "collectionGroup": "audit_logs",
      "queryScope": "COLLECTION",
      "fields": [
        { "fieldPath": "actorUid",  "order": "ASCENDING"  },
        { "fieldPath": "timestamp", "order": "DESCENDING" },
        { "fieldPath": "__name__",  "order": "DESCENDING" }
      ]
    },
    {
      "collectionGroup": "audit_logs",
      "queryScope": "COLLECTION",
      "fields": [
        { "fieldPath": "action",    "order": "ASCENDING"  },
        { "fieldPath": "timestamp", "order": "DESCENDING" },
        { "fieldPath": "__name__",  "order": "DESCENDING" }
      ]
    }
```

- [ ] **Step 2: Validate JSON**

```bash
python3 -c "import json; json.load(open('backend/src/main/resources/firestore.indexes.json'))"
```
Expected: no output (valid JSON).

- [ ] **Step 3: Note operator action required for production**

After this commit lands and the PR merges, the operator must run `firebase deploy --only firestore:indexes` to push the new index entries to Firestore. If skipped, the `actorUid` and `action` filter queries will throw `FAILED_PRECONDITION` at runtime with a link to auto-create the index. This is acceptable for the staging emulator (auto-creates) but a hard requirement for production.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/resources/firestore.indexes.json
git commit -m "[FEAT-ADMIN-USER-01-T22]: composite indexes for audit_logs cursor pagination"
```

---

## Task 23: Modify `AuditLogController` for cursor params

**Files:** modify `backend/src/main/java/com/albunyaan/tube/controller/AuditLogController.java`

> **Spec note:** the spec refers to `AuditController.java`; the actual file in this codebase is `AuditLogController.java`. Path used here is the actual one.

Per spec §5 — `?cursor=...&limit=...` on three GET endpoints; response shape `{items, nextCursor}`.

- [ ] **Step 1: Modify each of the three GET handlers**

Existing endpoints (verify by grep `@GetMapping`):
- `GET /api/admin/audit`
- `GET /api/admin/audit/actor/{actorUid}`
- `GET /api/admin/audit/action/{action}`

For each, replace the existing body with a call to `auditLogService.findPaginated(...)`. Example for the unfiltered handler:

```java
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping
    public ResponseEntity<com.albunyaan.tube.dto.PaginatedAuditLog> list(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false, defaultValue = "50") int limit) {
        try {
            return ResponseEntity.ok(auditLogService.findPaginated(null, null, limit, cursor));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            log.error("audit.list failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
```

For the actor-scoped handler:

```java
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/actor/{actorUid}")
    public ResponseEntity<com.albunyaan.tube.dto.PaginatedAuditLog> listByActor(
            @PathVariable String actorUid,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false, defaultValue = "50") int limit) {
        try {
            return ResponseEntity.ok(auditLogService.findPaginated(actorUid, null, limit, cursor));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            log.error("audit.byActor failed actor={}", actorUid, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
```

For the action-scoped handler:

```java
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/action/{action}")
    public ResponseEntity<com.albunyaan.tube.dto.PaginatedAuditLog> listByAction(
            @PathVariable String action,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false, defaultValue = "50") int limit) {
        try {
            return ResponseEntity.ok(auditLogService.findPaginated(null, action, limit, cursor));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            log.error("audit.byAction failed action={}", action, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
```

> **`/entity-type/{entityType}` endpoint** (line 56 in current file): leave it untouched — spec only covers the three listed endpoints, and entity-type filtering is not in scope for cursor pagination (spec §5).

- [ ] **Step 2: Compile**

```bash
cd backend && ./gradlew compileJava
```

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/albunyaan/tube/controller/AuditLogController.java
git commit -m "[FEAT-ADMIN-USER-01-T23]: AuditLogController accepts cursor + limit query params"
```

---

## Task 24: Audit pagination IT

**Files:** create `backend/src/test/java/com/albunyaan/tube/integration/AuditPaginationIT.java`

Per spec §10 — 250 rows, 5 pages of 50, no dupes or omissions.

- [ ] **Step 1: Write the IT**

```java
package com.albunyaan.tube.integration;

import com.google.cloud.Timestamp;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Plan F (ADMIN-USER-01) — cursor pagination walks 250 rows in 5 pages of 50.
 * No duplicates, no omissions, last page returns null cursor.
 */
public class AuditPaginationIT extends BaseIntegrationTest {

    @Test
    void walk250Rows_5pages_noDupesNoOmissions() throws Exception {
        String adminUid = seedAdmin("admin@test.com", "Admin");
        String adminToken = stubIdToken(adminUid);

        // Seed 250 rows with strictly-decreasing timestamps (newest first).
        Instant base = Instant.parse("2026-05-12T00:00:00Z");
        for (int i = 0; i < 250; i++) {
            Map<String, Object> doc = Map.of(
                    "action",    "TEST_PAGINATION",
                    "entityType","user",
                    "entityId",  "u-" + i,
                    "actorUid",  adminUid,
                    "timestamp", Timestamp.ofTimeSecondsAndNanos(
                            base.minusSeconds(i).getEpochSecond(), 0)
            );
            firestore.collection("audit_logs").add(doc).get();
        }

        Set<String> seenIds = new HashSet<>();
        String cursor = null;
        int pages = 0;
        com.fasterxml.jackson.databind.ObjectMapper jsonM = new com.fasterxml.jackson.databind.ObjectMapper();

        do {
            String url = "/api/admin/audit/action/TEST_PAGINATION?limit=50"
                    + (cursor != null ? "&cursor=" + cursor : "");
            var res = mvc.perform(get(url)
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andReturn();

            com.fasterxml.jackson.databind.JsonNode body =
                    jsonM.readTree(res.getResponse().getContentAsString());
            com.fasterxml.jackson.databind.JsonNode items = body.get("items");
            assertNotNull(items);
            items.forEach(n -> assertTrue(seenIds.add(n.get("entityId").asText()),
                    "duplicate entityId: " + n.get("entityId").asText()));
            cursor = body.hasNonNull("nextCursor") ? body.get("nextCursor").asText() : null;
            pages++;
        } while (cursor != null && pages < 10);

        assertEquals(5, pages, "expected 5 pages of 50");
        assertEquals(250, seenIds.size(), "expected 250 unique ids across all pages");
        assertNull(cursor, "last page should return null nextCursor");
    }
}
```

- [ ] **Step 2: Run the IT**

```bash
cd backend && ./gradlew test -Pintegration=true \
    --tests "com.albunyaan.tube.integration.AuditPaginationIT"
```
Expected: PASS. The Firestore emulator will auto-create the (action, timestamp, __name__) index on first query.

- [ ] **Step 3: Commit**

```bash
git add backend/src/test/java/com/albunyaan/tube/integration/AuditPaginationIT.java
git commit -m "[TEST-ADMIN-USER-01-T24]: AuditPaginationIT walks 5 pages of 50 with no dupes"
```

---

# Phase 5 — Frontend

## Task 25: Frontend admin user service — bulk + force-logout

**Files:** modify `frontend/src/services/adminUsers.ts`

Per spec §4 + §5 — new client methods.

- [ ] **Step 1: Add type definitions + 5 new methods**

Open `frontend/src/services/adminUsers.ts`, identify the existing `http` import + base URL pattern, and append:

```typescript
// Plan F (ADMIN-USER-01) — bulk + force-logout

export type BulkAction = 'block' | 'delete' | 'recover' | 'revokeSessions';

export interface FailureEntry {
  uid: string;
  reason: string;
}

export interface BulkUserActionResult {
  successes: string[];
  failures: FailureEntry[];
}

export interface BulkUserActionRequest {
  uids: string[];
  reason?: string;
}

async function postBulk(path: string, req: BulkUserActionRequest): Promise<BulkUserActionResult> {
  const { data } = await http.post<BulkUserActionResult>(`/api/admin/users/${path}`, req);
  return data;
}

export const bulkBlock          = (req: BulkUserActionRequest) => postBulk('bulk-block', req);
export const bulkDelete         = (req: BulkUserActionRequest) => postBulk('bulk-delete', req);
export const bulkRecover        = (req: BulkUserActionRequest) => postBulk('bulk-recover', req);
export const bulkRevokeSessions = (req: BulkUserActionRequest) => postBulk('bulk-revoke-sessions', req);

export async function forceLogout(uid: string, reason?: string): Promise<void> {
  await http.post(`/api/admin/users/${uid}/revoke-sessions`, { reason });
}
```

> **`http` reference:** match whatever the existing file uses (`import { http } from './http'` or similar). Reuse, don't add a new HTTP client.

- [ ] **Step 2: Compile (TypeScript check)**

```bash
cd frontend && npm run build
```
Expected: BUILD SUCCESSFUL (or `vue-tsc` passes).

- [ ] **Step 3: Commit**

```bash
git add frontend/src/services/adminUsers.ts
git commit -m "[FEAT-ADMIN-USER-01-T25]: adminUsers service bulkBlock/Delete/Recover/RevokeSessions + forceLogout"
```

---

## Task 26: Frontend admin audit service — cursor pagination

**Files:** modify `frontend/src/services/adminAudit.ts`

Per spec §5 — `nextCursor` in response, `?cursor=...&limit=...` on requests.

- [ ] **Step 1: Add cursor types + update methods**

Open `frontend/src/services/adminAudit.ts`, append/replace:

```typescript
// Plan F (ADMIN-USER-01) — cursor pagination types

export interface PaginatedAuditLog<T> {
  items: T[];
  nextCursor: string | null;
}

export interface AuditQuery {
  actorUid?: string;
  action?: string;
  cursor?: string | null;
  limit?: number;
}

export async function fetchAuditPage(q: AuditQuery): Promise<PaginatedAuditLog<AuditLogEntry>> {
  const params: Record<string, string> = {};
  if (q.cursor) params.cursor = q.cursor;
  if (q.limit)  params.limit  = String(q.limit);
  let path = '/api/admin/audit';
  if (q.actorUid) path = `/api/admin/audit/actor/${encodeURIComponent(q.actorUid)}`;
  else if (q.action) path = `/api/admin/audit/action/${encodeURIComponent(q.action)}`;
  const { data } = await http.get<PaginatedAuditLog<AuditLogEntry>>(path, { params });
  return data;
}
```

> **`AuditLogEntry` type:** if this type is not already declared in the file, define it as a passthrough interface matching the backend's `AuditLog` shape (action, entityType, entityId, actorUid, timestamp, details). Reuse the existing type if present; do not introduce a duplicate.

- [ ] **Step 2: Update any existing callers**

Search `frontend/src` for previous calls like `fetchAuditPage({ page: 1 })` or similar and migrate them to `{ cursor: null, limit: 50 }`. If the prior signature differed, this task may need to deprecate the old method and add the new one rather than replace it — preserve backward compat for any in-flight call sites.

- [ ] **Step 3: Compile**

```bash
cd frontend && npm run build
```

- [ ] **Step 4: Commit**

```bash
git add frontend/src/services/adminAudit.ts
git commit -m "[FEAT-ADMIN-USER-01-T26]: adminAudit cursor pagination support"
```

---

## Task 27: `UsersManagementView.vue` — checkbox column + bulk toolbar + force logout (TDD)

**Files:**
- Modify: `frontend/src/views/UsersManagementView.vue`
- Modify: `frontend/tests/UsersManagementView.spec.ts`

Per spec §9 — checkbox column, sticky toolbar, per-row Force Logout, bulk-result toast.

- [ ] **Step 1: Add failing Vitest case**

Open `frontend/tests/UsersManagementView.spec.ts`. Add (or replace existing) a test like:

```typescript
import { describe, it, expect, vi } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import UsersManagementView from '@/views/UsersManagementView.vue';

vi.mock('@/services/adminUsers', async (orig) => ({
  ...(await orig() as any),
  fetchUsers: vi.fn().mockResolvedValue({
    items: [
      { uid: 'a', email: 'alice@x.com',   role: 'user', status: 'active'  },
      { uid: 'b', email: 'bob@x.com',     role: 'user', status: 'blocked' },
      { uid: 'c', email: 'charlie@x.com', role: 'user', status: 'active'  }
    ],
    nextCursor: null
  }),
  bulkBlock: vi.fn().mockResolvedValue({
    successes: ['a'],
    failures: [
      { uid: 'b', reason: 'already_blocked' },
      { uid: 'c', reason: 'firebase_error'  }
    ]
  })
}));

describe('UsersManagementView — Plan F bulk', () => {
  it('renders summary toast after bulk-block with mixed result', async () => {
    const wrapper = mount(UsersManagementView);
    await flushPromises();

    // Select 3 rows
    const checkboxes = wrapper.findAll('input[type="checkbox"][data-test="row-select"]');
    expect(checkboxes.length).toBeGreaterThanOrEqual(3);
    for (const cb of checkboxes) await cb.setValue(true);

    // Click bulk-block
    await wrapper.find('[data-test="bulk-block"]').trigger('click');
    await flushPromises();

    // Toast renders summary
    const toast = wrapper.find('[data-test="bulk-result-toast"]');
    expect(toast.exists()).toBe(true);
    expect(toast.text()).toContain('1 succeeded');
    expect(toast.text()).toContain('2 failed');
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd frontend && npm test -- UsersManagementView
```
Expected: FAIL — no `data-test="bulk-block"` or `data-test="bulk-result-toast"` in the current template.

- [ ] **Step 3: Update the Vue template + script setup**

In `UsersManagementView.vue`:

1. Add a `selected: Set<string>` ref in `<script setup>` and a method that toggles uid membership when a row checkbox is clicked. Add a header-cell "select all" checkbox that toggles every visible row.

2. Add a sticky toolbar (e.g. `position: sticky; top: 0;`) that is `v-if="selected.size >= 1"` and contains four buttons:

```vue
<div class="bulk-toolbar" v-if="selected.size >= 1" data-test="bulk-toolbar">
  <span>{{ t('users.bulk.selected', { n: selected.size }) }}</span>
  <button data-test="bulk-block"  @click="runBulk('block')">{{ t('users.bulk.block') }}</button>
  <button data-test="bulk-delete" @click="runBulkDelete">{{ t('users.bulk.delete') }}</button>
  <button data-test="bulk-recover"        @click="runBulk('recover')">{{ t('users.bulk.recover') }}</button>
  <button data-test="bulk-revoke-sessions" @click="runBulk('revokeSessions')">{{ t('users.bulk.revokeSessions') }}</button>
</div>
```

3. The `runBulk(action)` handler calls the matching service method from T25, then pushes a `{ ok, fail, action, failures }` object onto a `lastResult` ref. The `runBulkDelete` variant first prompts a simple `confirm()` dialog (F9 — confirmation only for bulk-delete).

4. Render the toast wherever toasts live in the existing app (or a fresh `<div>` if no toast component exists):

```vue
<div v-if="lastResult" data-test="bulk-result-toast" class="bulk-result-toast">
  {{ t('users.bulk.toast.summary', {
       action: t(`users.bulk.${lastResult.action}`),
       ok:    lastResult.ok,
       fail:  lastResult.fail
     }) }}
  <button @click="lastResult.expand = !lastResult.expand">
    {{ t('users.bulk.toast.details') }}
  </button>
  <ul v-if="lastResult.expand">
    <li v-for="f in lastResult.failures" :key="f.uid">
      {{ f.uid }}: {{ t('users.bulk.reason.' + f.reason) }}
    </li>
  </ul>
</div>
```

5. Add a "Force Logout" entry to the per-row action menu (existing dropdown). Click handler:

```typescript
async function forceLogoutRow(uid: string) {
  await forceLogout(uid);
  toast(t('users.bulk.toast.summary', { action: t('users.bulk.revokeSessions'), ok: 1, fail: 0 }));
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
cd frontend && npm test -- UsersManagementView
```
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/views/UsersManagementView.vue \
        frontend/tests/UsersManagementView.spec.ts
git commit -m "[FEAT-ADMIN-USER-01-T27]: UsersManagementView checkbox + bulk toolbar + force logout"
```

---

## Task 28: `AuditLogView.vue` — "Load more" cursor (TDD)

**Files:**
- Modify: `frontend/src/views/AuditLogView.vue`
- Modify: `frontend/tests/AuditLogView.spec.ts`

Per spec §9 — replace page-number paginator with cursor "Load more"; filter change resets cursor.

- [ ] **Step 1: Add failing Vitest case**

Open `frontend/tests/AuditLogView.spec.ts`. Add (or update) a test:

```typescript
import { describe, it, expect, vi } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import AuditLogView from '@/views/AuditLogView.vue';

const firstPage = {
  items: Array.from({ length: 50 }, (_, i) => ({
    action: 'X', entityId: `e-${i}`, actorUid: 'a', timestamp: 0, entityType: 'user'
  })),
  nextCursor: 'CURSOR-1'
};
const secondPage = {
  items: Array.from({ length: 30 }, (_, i) => ({
    action: 'X', entityId: `f-${i}`, actorUid: 'a', timestamp: 0, entityType: 'user'
  })),
  nextCursor: null
};

vi.mock('@/services/adminAudit', async (orig) => ({
  ...(await orig() as any),
  fetchAuditPage: vi.fn()
    .mockResolvedValueOnce(firstPage)
    .mockResolvedValueOnce(secondPage)
}));

describe('AuditLogView — Plan F cursor pagination', () => {
  it('appends second page on Load more click', async () => {
    const wrapper = mount(AuditLogView);
    await flushPromises();

    expect(wrapper.findAll('[data-test="audit-row"]').length).toBe(50);
    expect(wrapper.find('[data-test="audit-load-more"]').exists()).toBe(true);

    await wrapper.find('[data-test="audit-load-more"]').trigger('click');
    await flushPromises();

    expect(wrapper.findAll('[data-test="audit-row"]').length).toBe(80);
    expect(wrapper.find('[data-test="audit-load-more"]').exists()).toBe(false);
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd frontend && npm test -- AuditLogView
```
Expected: FAIL — no `data-test="audit-load-more"` button in current template.

- [ ] **Step 3: Update `AuditLogView.vue`**

Replace the existing page-paginator block with a `Load more` button. In `<script setup>`:

```typescript
const items = ref<AuditLogEntry[]>([]);
const cursor = ref<string | null>(null);
const loading = ref(false);
const filter = reactive<{ actorUid?: string; action?: string }>({});

async function load(reset = false) {
  if (loading.value) return;
  loading.value = true;
  if (reset) { items.value = []; cursor.value = null; }
  const page = await fetchAuditPage({
    actorUid: filter.actorUid,
    action:   filter.action,
    cursor:   cursor.value,
    limit:    50
  });
  items.value = items.value.concat(page.items);
  cursor.value = page.nextCursor;
  loading.value = false;
}

onMounted(() => load(true));
watch(() => [filter.actorUid, filter.action], () => load(true));
```

In the template, replace the paginator with:

```vue
<table>
  <tr v-for="(row, i) in items" :key="i" data-test="audit-row">…</tr>
</table>

<button
  v-if="cursor !== null"
  data-test="audit-load-more"
  :disabled="loading"
  @click="load(false)">
  {{ t('audit.loadMore') }}
</button>
```

- [ ] **Step 4: Run test to verify it passes**

```bash
cd frontend && npm test -- AuditLogView
```
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/views/AuditLogView.vue \
        frontend/tests/AuditLogView.spec.ts
git commit -m "[FEAT-ADMIN-USER-01-T28]: AuditLogView cursor Load-more pagination"
```

---

## Task 29: i18n keys for bulk + audit

**Files:** modify `frontend/src/locales/messages.ts`

Per spec §9 — add new keys in `en`, `ar`, `nl`. Translations provided complete (Arabic/Dutch reviewed by native-speaker recommended but acceptable as-is).

- [ ] **Step 1: Append the new keys to each language**

In each language section's `users:` block (find existing `users: { …` at L794 for `en`, L2179 for `ar`, L3395 for `nl`), append a `bulk:` sub-block and a `forceLogout` entry. Append `loadMore` to the `audit:` block (L906 en, L2291 ar, L?? nl).

**English (`en`):**
```typescript
    users: {
      // …existing keys…
      bulk: {
        block: 'Block',
        delete: 'Delete',
        recover: 'Recover',
        revokeSessions: 'Force Logout',
        selected: '{n} selected',
        toast: {
          summary: '{action} — {ok} succeeded, {fail} failed',
          details: 'Show details'
        },
        reason: {
          user_not_found: 'User not found',
          already_blocked: 'Already blocked',
          not_blocked: 'Not blocked',
          already_deleted: 'Already deleted',
          not_deleted: 'Not deleted',
          self_action_forbidden: 'Cannot apply to your own account',
          admin_target_forbidden: 'Cannot apply to admin accounts',
          firebase_error: 'Provider error',
          invalid_state: 'Invalid state for this action'
        }
      },
      confirmDelete: {
        bulk: 'Soft-delete {n} users? They can be recovered later.'
      },
      forceLogout: {
        confirm: 'Force logout {email}? They will be signed out within an hour.'
      }
    },
    audit: {
      // …existing keys…
      loadMore: 'Load more'
    }
```

**Arabic (`ar`):**
```typescript
    users: {
      // …existing keys…
      bulk: {
        block: 'حظر',
        delete: 'حذف',
        recover: 'استعادة',
        revokeSessions: 'تسجيل خروج إجباري',
        selected: 'تم تحديد {n}',
        toast: {
          summary: '{action} — نجح {ok}، فشل {fail}',
          details: 'عرض التفاصيل'
        },
        reason: {
          user_not_found: 'المستخدم غير موجود',
          already_blocked: 'محظور بالفعل',
          not_blocked: 'غير محظور',
          already_deleted: 'محذوف بالفعل',
          not_deleted: 'غير محذوف',
          self_action_forbidden: 'لا يمكن تطبيق هذا الإجراء على حسابك',
          admin_target_forbidden: 'لا يمكن تطبيق هذا الإجراء على حسابات المسؤولين',
          firebase_error: 'خطأ في الموفر',
          invalid_state: 'الحالة غير صالحة لهذا الإجراء'
        }
      },
      confirmDelete: {
        bulk: 'حذف {n} مستخدمين مبدئياً؟ يمكن استعادتهم لاحقاً.'
      },
      forceLogout: {
        confirm: 'تسجيل خروج إجباري لـ {email}؟ سيتم تسجيل خروجه خلال ساعة.'
      }
    },
    audit: {
      // …existing keys…
      loadMore: 'تحميل المزيد'
    }
```

**Dutch (`nl`):**
```typescript
    users: {
      // …existing keys…
      bulk: {
        block: 'Blokkeren',
        delete: 'Verwijderen',
        recover: 'Herstellen',
        revokeSessions: 'Geforceerd afmelden',
        selected: '{n} geselecteerd',
        toast: {
          summary: '{action} — {ok} geslaagd, {fail} mislukt',
          details: 'Details tonen'
        },
        reason: {
          user_not_found: 'Gebruiker niet gevonden',
          already_blocked: 'Al geblokkeerd',
          not_blocked: 'Niet geblokkeerd',
          already_deleted: 'Al verwijderd',
          not_deleted: 'Niet verwijderd',
          self_action_forbidden: 'Kan niet op je eigen account toepassen',
          admin_target_forbidden: 'Kan niet op beheerdersaccounts toepassen',
          firebase_error: 'Providerfout',
          invalid_state: 'Ongeldige status voor deze actie'
        }
      },
      confirmDelete: {
        bulk: '{n} gebruikers tijdelijk verwijderen? Ze kunnen later worden hersteld.'
      },
      forceLogout: {
        confirm: 'Geforceerd afmelden van {email}? Ze worden binnen een uur uitgelogd.'
      }
    },
    audit: {
      // …existing keys…
      loadMore: 'Meer laden'
    }
```

- [ ] **Step 2: Compile**

```bash
cd frontend && npm run build
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add frontend/src/locales/messages.ts
git commit -m "[FEAT-ADMIN-USER-01-T29]: i18n keys for bulk actions + audit Load more (en/ar/nl)"
```

---

# Phase 6 — Documentation

## Task 30: Azure deployment docs

**Files:**
- Create: `docs/deployment/azure-app-registration.md`
- Create: `docs/deployment/azure-secret-rotation.md`

Per spec §6 (Azure AD app steps) + risk §11.2 (secret rotation).

- [ ] **Step 1: Create `docs/deployment/azure-app-registration.md`**

```markdown
# Azure AD App Registration for FitrahTube Mail

> **Plan F (ADMIN-USER-01).** One-time setup required before flipping
> `MAIL_ENABLED=true` in production.

## Prerequisites
- Microsoft 365 tenant with a real `noreply@fitrahtube.com` mailbox.
- Global Administrator role on the tenant (required for step 6 PowerShell scoping).
- Exchange Online PowerShell module installed locally.

## Step 1 — Create the app registration
1. Azure Portal → **Microsoft Entra ID** → **App registrations** → **New registration**.
2. Name: `FitrahTube Backend`.
3. Supported account types: **Accounts in this organizational directory only (single tenant)**.
4. Redirect URI: leave blank.
5. Click **Register**.

## Step 2 — Record IDs
On the new app's Overview page, copy:
- **Application (client) ID** → save as `AZURE_CLIENT_ID`.
- **Directory (tenant) ID** → save as `AZURE_TENANT_ID`.

## Step 3 — Create the client secret
1. **Certificates & secrets** → **Client secrets** → **New client secret**.
2. Description: `FitrahTube backend (rotates YYYY-MM-DD)`.
3. Expires: **24 months**.
4. Click **Add**.
5. **Copy the Value field immediately** — it never displays again.
6. Save as `AZURE_CLIENT_SECRET` in the deployment secret vault.

## Step 4 — Grant Mail.Send (Application permission)
1. **API permissions** → **Add a permission** → **Microsoft Graph** → **Application permissions**.
2. Search `Mail.Send`, check it, click **Add permissions**.
3. Click **Grant admin consent for <tenant>** (requires Global Admin).

## Step 5 — Restrict scope to the noreply mailbox (DEFERRED — Path A, 2026-05-12)

> **Status:** DEFERRED. The original Plan F spec marked this CRITICAL; we accepted
> running unscoped because connecting Exchange Online PowerShell from the only
> available host (Linux, unregistered device) is blocked by Microsoft Entra
> Conditional Access / Security Defaults (`AADSTS53003`).
>
> **What this means:** `Mail.Send` (Application permission) currently allows the
> backend to send mail as ANY mailbox in the tenant. Blast radius is bounded:
> single-admin tenant, ≤2 mailboxes total, no end-user mailboxes.
>
> **MUST DO BEFORE:** adding additional staff mailboxes, before onboarding any
> user-facing mailbox, before rotating the secret to a longer-lived one. Single
> PowerShell command, no code change.

When ready (from a registered Windows device or after CA exclusions are configured),
open Exchange Online PowerShell:

```powershell
Connect-ExchangeOnline

New-DistributionGroup -Name "FitrahTubeAppSenders" `
    -Members "noreply@fitrahtube.com"

New-ApplicationAccessPolicy `
    -AppId <AZURE_CLIENT_ID> `
    -PolicyScopeGroupId FitrahTubeAppSenders `
    -AccessRight RestrictAccess `
    -Description "FitrahTube Backend may only send from noreply mailbox"
```

## Step 6 — Verify the policy (after Step 5 is eventually applied)
```powershell
Test-ApplicationAccessPolicy -AppId <AZURE_CLIENT_ID> -Identity noreply@fitrahtube.com
# AccessCheckResult: Granted

Test-ApplicationAccessPolicy -AppId <AZURE_CLIENT_ID> -Identity some-other@fitrahtube.com
# AccessCheckResult: Denied
```

## Step 7 — Populate environment variables on the prod VM
| Variable | Value |
|---|---|
| `AZURE_TENANT_ID` | from step 2 |
| `AZURE_CLIENT_ID` | from step 2 |
| `AZURE_CLIENT_SECRET` | from step 3 |
| `MAIL_FROM_ADDRESS` | `noreply@fitrahtube.com` |
| `MAIL_FROM_DISPLAY_NAME` | `FitrahTube` |
| `MAIL_ENABLED` | `true` |

## Step 8 — Restart the backend
The backend runs a startup health check that calls `graph.users(fromAddress).get()`.
If the mailbox isn't reachable (wrong tenant, wrong scope, expired secret),
the backend refuses to start with a clear error. Investigate before retrying.

## Step 9 — Smoke test
From the admin UI, pick a throwaway test account and click **Reset password**.
The email should arrive within ~30 seconds. Inspect the `From:` header:

```
FitrahTube <noreply@fitrahtube.com>
```

If you see anything else, re-check step 5 (the scoping policy).
```

- [ ] **Step 2: Create `docs/deployment/azure-secret-rotation.md`**

```markdown
# Azure Client Secret Rotation

> **Plan F (ADMIN-USER-01)** risk §11.2 — Azure client secrets expire after at
> most 24 months. Rotate **at least 30 days before expiry** to avoid silent
> password-reset failures.

## When to rotate
- Schedule a calendar reminder for the 1st of the month, 30 days before the
  recorded expiry date.
- If the daily `mail.startup-check` ever logs token-acquisition errors, treat
  it as an emergency rotation and follow this procedure now.

## Zero-downtime rotation (recommended)

1. **Create the new secret in Azure Portal** (do not delete the old one yet):
   - **Certificates & secrets** → **New client secret** → 24-month expiry.
   - Description: `FitrahTube backend (rotates YYYY-MM-DD)`.
   - Copy the **Value** field immediately.

2. **Update the deployment vault**:
   - Add the new value alongside the old one (`AZURE_CLIENT_SECRET_NEW`).

3. **Deploy with the new secret**:
   - SSH to the prod VM.
   - `export AZURE_CLIENT_SECRET=<new value>` (or update the env-file).
   - Restart the backend. Watch logs for `mail.startup-check.ok`.

4. **Verify**:
   - Trigger a password reset on a throwaway account; confirm email arrives.

5. **Delete the old secret in Azure**:
   - Only after step 4 confirms the new secret works.
   - **Certificates & secrets** → click the trash can next to the old entry.

6. **Update CALENDAR + this doc** with the next rotation date.

## Emergency rotation (secret compromised)

1. Immediately delete the compromised secret in Azure Portal.
2. Create a new secret following the standard procedure (steps 1–4 above).
3. Audit `audit_logs` for `USER_PASSWORD_RESET_EMAIL_FAILED` entries in the
   compromise window — those may be the attacker's reconnaissance.

## Verification commands

```bash
# Confirm backend is using the new secret
ssh prod 'sudo journalctl -u fitrahtube-backend | grep mail.startup-check.ok | tail -1'

# Trigger a Graph call manually to test token acquisition
ssh prod 'curl -s http://localhost:8080/actuator/health | jq .components.mail'
```

## Rotation log

| Rotation date | Old secret expiry | New secret expiry | Operator |
|---|---|---|---|
| YYYY-MM-DD | YYYY-MM-DD | YYYY-MM-DD | name |
```

- [ ] **Step 3: Commit**

```bash
mkdir -p docs/deployment
git add docs/deployment/azure-app-registration.md \
        docs/deployment/azure-secret-rotation.md
git commit -m "[DOCS-ADMIN-USER-01-T30]: Azure app registration + secret rotation guides"
```

---

# Phase 7 — Verification + PR

## Task 31: Full test suites + open PR

- [ ] **Step 1: Run the full backend unit suite**

```bash
cd backend && ./gradlew test
```
Expected: BUILD SUCCESSFUL. If a test fixture predates Plan F's constructor change to `AuthService` (now requires `MailService`), update the fixture to pass `mock(MailService.class)`.

- [ ] **Step 2: Run all Plan F integration tests**

```bash
firebase emulators:start --only firestore,auth --project demo-test &
sleep 5

cd backend && ./gradlew test -Pintegration=true \
    --tests "com.albunyaan.tube.integration.BulkUserActionIT" \
    --tests "com.albunyaan.tube.integration.RevokeSessionsIT" \
    --tests "com.albunyaan.tube.integration.AutoRevokeOnRoleChangeIT" \
    --tests "com.albunyaan.tube.integration.AuditPaginationIT"
```
Expected: 4 ITs PASS.

- [ ] **Step 3: Run the full frontend test suite**

```bash
cd frontend && npm test
```
Expected: all green.

- [ ] **Step 4: Push the branch + open the PR**

```bash
cd /home/farouq/Development/albunyaantube
git push -u origin feature/ADMIN-USER-01-management

gh pr create --base develop --title "[FEAT-ADMIN-USER-01]: Admin user management (Plan F)" --body "$(cat <<'EOF'
## Summary

Plan F (ADMIN-USER-01) closes the remaining admin user-management gaps:

- **Outbound email** — `MailService` via Microsoft Graph API; `AuthService.sendPasswordResetEmail` now actually delivers (F1, F2, F3, F7).
- **Stand-alone force-logout** — `POST /api/admin/users/{uid}/revoke-sessions` (single) and `bulk-revoke-sessions`; auto-revoke on role change (F6).
- **Bulk actions** — `bulk-block`, `bulk-delete`, `bulk-recover`, `bulk-revoke-sessions` with best-effort per-row results (F4, F5).
- **Audit cursor pagination** — base64url `{ts,id}` cursor on the three audit GETs; default limit 50, cap 200 (F8).
- **Frontend UX** — checkbox column + sticky bulk toolbar on Users; "Load more" on Audit Log (F9).
- **Docs** — Azure AD app registration + secret rotation procedures.

## Spec & Plan
- Spec: `docs/superpowers/specs/2026-05-12-plan-f-admin-user-management-design.md`
- Plan: `docs/superpowers/plans/2026-05-12-plan-f-admin-user-management.md`

## Test plan
- [x] Backend unit (`MailServiceTest`, `BulkUserServiceTest`, `AuditCursorTest`, `AuthServiceRevokeSessionsTest`)
- [x] Backend integration (`BulkUserActionIT`, `RevokeSessionsIT`, `AutoRevokeOnRoleChangeIT`, `AuditPaginationIT`)
- [x] Frontend Vitest (`UsersManagementView.spec.ts`, `AuditLogView.spec.ts`)
- [ ] Manual (after merge + Azure provisioning): trigger a reset email to a real address, verify From-header + delivery within 30s
- [ ] Manual: trigger a bulk-block on 3 mixed users; verify toast renders "1 succeeded, 2 failed" with expandable details

## Rollout (per spec §11)
1. Merge with `MAIL_ENABLED=false` — endpoints live, mail no-ops.
2. Operator provisions Azure AD app per `docs/deployment/azure-app-registration.md`.
3. Set env vars + flip `MAIL_ENABLED=true`, restart backend.
4. Deploy Firestore indexes: `firebase deploy --only firestore:indexes`.

Backout: set `MAIL_ENABLED=false` and revert frontend bundle.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

- [ ] **Step 5: Capture the PR URL**

The `gh pr create` output prints the PR URL. Paste it into the conversation so the user can review.

---

## Self-Review

**Spec coverage (F1–F11):**
- F1 (Graph API transport): T1 deps, T3 client init, T4 send call.
- F2 (plaintext body): T4 buildPasswordResetMessage uses `BodyType.TEXT`.
- F3 (from address + display name + no-reply disclaimer): T2 MailProperties, T4 body text.
- F4 (best-effort 1..100): T13 `@Size(1,100)`, T14 per-row try/catch, T17 classification.
- F5 (self + admin protection): T15 self guard, T16 admin guard, RECOVER exempt.
- F6 (3 force-logout triggers): T10 single endpoint, T18 bulk endpoint, T11 auto on role change.
- F7 (email failure UX): T5 silent failure path + audit, T10 endpoint returns 204 immediately.
- F8 (cursor format): T20 AuditCursor base64url, T21 `findPaginated`, T22 indexes, T23 controller, T24 IT.
- F9 (frontend bulk UX): T27 checkbox + toolbar + force-logout row action + toast.
- F10 (TTL out of scope): noted in PR body / spec only; no tasks.
- F11 (branch + commit prefix): used `feature/ADMIN-USER-01-management` and `[FEAT-ADMIN-USER-01-T#]` throughout.

**Spec section coverage:**
- §1 Goal — all 4 pillars + auto-revoke covered.
- §2 Locked decisions F1–F11 mapped above.
- §3 Architecture — `MailService` (T3–T8), `BulkUserService` (T13–T17), controller wiring (T10, T18, T23), `AuditLogService` extension (T21).
- §4 File inventory — every "new" + "modified" file appears in the File Structure table at the top.
- §5 New endpoints — T10 (single revoke), T18 (4 bulk), T23 (audit cursor params).
- §6 Email config — T1 (deps), T2 (props), T3-T5 (MailService), T6 (wiring), T7 (yaml), T8 (startup check); deployment doc in T30.
- §7 BulkUserService design — T13 enum/DTOs, T14 happy path, T15-T17 guards + classify.
- §8 Audit cursor — T20 utility, T21 service, T22 indexes, T23 controller, T24 IT.
- §9 Frontend UX — T27 Users view, T28 Audit view, T29 i18n.
- §10 Testing strategy — unit ITs covered (T3-T5, T9, T12, T14-T17, T19, T20, T24, T27, T28).
- §11 Risks/rollout — T8 (risk §11.3 startup check), T22 note (risk §11.5 partial — manifest), T30 (risk §11.2 rotation procedure). PR body covers rollout phases.
- §12 Out of scope — explicitly not addressed (TTL, HTML mail, SMTP fallback, etc.).
- §13 Open items — covered in deployment doc (T30) which the operator follows before flipping `MAIL_ENABLED=true`.

**Placeholder scan:** all step bodies contain concrete code or commands. References like "match the existing pattern" or "verify the signature" are intentional implementer guidance, not deferrals.

**Type consistency:**
- `BulkAction` enum values BLOCK / DELETE / RECOVER / REVOKE_SESSIONS used identically across T13/T14/T18.
- Failure reason strings (`self_action_forbidden`, `admin_target_forbidden`, `already_blocked`, `not_blocked`, `already_deleted`, `not_deleted`, `user_not_found`, `firebase_error`, `invalid_state`) consistent between T15/T16/T17 and i18n keys in T29.
- Audit action strings (`USER_SESSIONS_REVOKED`, `USER_SESSIONS_REVOKED_AUTO`, `USER_SESSIONS_REVOKED_AUTO_FAILED`, `USER_BULK_ACTION`, `USER_PASSWORD_RESET_EMAIL_FAILED`) used identically in service + IT assertions.
- Endpoint paths match spec §5 verbatim.

**Slicing decisions:**
- Mail service split into 3 tasks (skeleton + happy path + failure path) rather than one mega-task, since the spec's `MailService` class is ~80 LoC and each phase has its own test.
- Bulk service split into 4 tasks (happy / self / admin / classify) for clear TDD red-green per behaviour.
- `MailServiceTest` reflective workaround in T5 is documented; if the team prefers package-private overrides, refactor after.
- One scope reduction vs spec narrative: the `application-test.yml` already exists, so T7 just appends rather than creating it.
- Spec mentions `AuditController.java`; corrected to actual `AuditLogController.java` throughout.
- TODO line in `AuthService.sendPasswordResetEmail` is L689 (spec said L686 approximate); T6 references the correct surrounding code.
