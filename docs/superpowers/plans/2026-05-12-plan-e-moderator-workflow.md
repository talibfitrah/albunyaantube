# Plan E — Moderator Submission Workflow — Implementation Plan

> **For agentic workers:** Use superpowers:subagent-driven-development to execute this plan.

**Goal:** Add REQUEST_CHANGES status + endpoint, enrich PendingApprovalDto with submittedBy display fields, add a per-uid submission rate limit, and ship an Android "My Submissions" Me-tab section with a + bottom sheet for paste-URL / search submissions.

**Architecture:** Backend changes layer onto existing `ApprovalController` / `ApprovalService` / `RegistryController` without restructuring. Re-submission of a REQUEST_CHANGES row uses the existing `POST /api/admin/registry/{type}` endpoint with widened semantics (flip status PENDING + clear reviewNotes if existing row is REQUEST_CHANGES AND same submittedBy). Android adds new repository + ViewModel + Fragment + BottomSheet in the existing Me-tab nav graph.

**Tech Stack:** Spring Boot 3 + Firestore; Android Hilt + Retrofit + Kotlin Coroutines + Material 3.

**Spec:** `docs/superpowers/specs/2026-05-12-plan-e-moderator-workflow-design.md`. Read it first.

**Ticket prefix:** `MODERATOR-01`.

---

## File Structure

### Backend — create
| Path | Responsibility |
|---|---|
| `backend/src/main/java/com/albunyaan/tube/dto/RequestChangesRequest.java` | POST body for the new endpoint |
| `backend/src/main/java/com/albunyaan/tube/service/SubmissionRateLimiter.java` | In-memory sliding-window rate limiter |
| `backend/src/main/java/com/albunyaan/tube/security/SubmissionRateLimitInterceptor.java` | HandlerInterceptor wiring the limiter onto `/api/admin/registry/{type}` POSTs |

### Backend — modify
| Path | Change |
|---|---|
| `backend/src/main/java/com/albunyaan/tube/service/ApprovalService.java` | Add `REQUEST_CHANGES` to `VALID_STATUSES`; new `requestChanges(id, note, contentType, actor)` method; enrich DTOs in `getPendingApprovals` + `getMySubmissions` with submittedByDisplayName/email |
| `backend/src/main/java/com/albunyaan/tube/controller/ApprovalController.java` | New `POST /{id}/request-changes` route (ADMIN-only) |
| `backend/src/main/java/com/albunyaan/tube/dto/PendingApprovalDto.java` | Add `submittedByDisplayName: String?` + `submittedByEmail: String?` fields |
| `backend/src/main/java/com/albunyaan/tube/controller/RegistryController.java` | POST `/channels|/playlists|/videos` widen 409 logic to upsert REQUEST_CHANGES rows back to PENDING when submittedBy matches |
| `backend/src/main/java/com/albunyaan/tube/config/WebConfig.java` (existing or new) | Register the rate-limit interceptor |

### Backend — tests
| Path | Coverage |
|---|---|
| `backend/src/test/java/com/albunyaan/tube/service/SubmissionRateLimiterTest.java` | Sliding window correctness |
| `backend/src/test/java/com/albunyaan/tube/service/ApprovalServiceRequestChangesTest.java` | Unit (Mockito) |
| `backend/src/test/java/com/albunyaan/tube/integration/RequestChangesIT.java` | Emulator: full state machine |
| `backend/src/test/java/com/albunyaan/tube/integration/MySubmissionsEnrichmentIT.java` | Emulator: DTO includes displayName + email |
| `backend/src/test/java/com/albunyaan/tube/integration/SubmissionRateLimitIT.java` | Emulator: 51st submission → 429 |

### Android — create
| Path | Responsibility |
|---|---|
| `android/app/src/main/java/com/albunyaan/tube/data/approvals/ApprovalApi.kt` | Retrofit interface for `/my-submissions` + RegistryController POSTs |
| `android/app/src/main/java/com/albunyaan/tube/data/approvals/dto/ApprovalDtos.kt` | Moshi DTOs: PendingApprovalDto, ApprovalResponseDto, etc. |
| `android/app/src/main/java/com/albunyaan/tube/data/approvals/MySubmissionsRepository.kt` | Hilt-injected repo |
| `android/app/src/main/java/com/albunyaan/tube/ui/me/submissions/MySubmissionsFragment.kt` | List screen with SwipeRefresh + FAB |
| `android/app/src/main/java/com/albunyaan/tube/ui/me/submissions/MySubmissionsViewModel.kt` | UI state |
| `android/app/src/main/java/com/albunyaan/tube/ui/me/submissions/MySubmissionAdapter.kt` | RecyclerView adapter |
| `android/app/src/main/java/com/albunyaan/tube/ui/me/submissions/SubmitContentBottomSheet.kt` | + FAB target |
| `android/app/src/main/res/layout/fragment_my_submissions.xml` | List layout |
| `android/app/src/main/res/layout/item_my_submission.xml` | Row layout |
| `android/app/src/main/res/layout/bottom_sheet_submit_content.xml` | Submit sheet layout |
| `android/app/src/main/res/values/strings.xml` (modify) | New string keys |

### Android — modify
| Path | Change |
|---|---|
| `android/app/src/main/java/com/albunyaan/tube/auth/AccountState.kt` | Add `role: String` to `Loaded` |
| `android/app/src/main/java/com/albunyaan/tube/auth/AccountRepositoryImpl.kt` | Map `/me` response `role` field into `AccountState.Loaded.role` |
| `android/app/src/main/java/com/albunyaan/tube/ui/me/MeFragment.kt` (or wherever Me-tab navigation lives) | Show "My Submissions" entry only when role == "moderator" or "admin" |
| `android/app/src/main/res/navigation/main_graph.xml` | Add navigation action to `myBundleFragment` |

### Android — tests
| Path | Coverage |
|---|---|
| `android/app/src/test/java/com/albunyaan/tube/data/approvals/MySubmissionsRepositoryTest.kt` | 429 → RateLimitError, 2xx → list mapping |
| `android/app/src/test/java/com/albunyaan/tube/ui/me/submissions/MySubmissionsViewModelTest.kt` | State machine |

---

# Phase 1 — Backend

## Task 1: Widen RegistryController POST to upsert REQUEST_CHANGES rows

**Files:** modify `backend/src/main/java/com/albunyaan/tube/controller/RegistryController.java`

The current POST `/channels` (and `/playlists`, `/videos`) returns 409 CONFLICT if `youtubeId` already exists. Plan E needs an exception: when the existing row's `status == "REQUEST_CHANGES"` AND `submittedBy == caller.uid`, flip it back to PENDING + clear `reviewNotes` + return 200.

- [ ] **Step 1: Find the three POST handlers**

```bash
grep -n '@PostMapping("/channels")\|@PostMapping("/playlists")\|@PostMapping("/videos")' \
  backend/src/main/java/com/albunyaan/tube/controller/RegistryController.java
```

Expect three lines around 142, 320, 590.

- [ ] **Step 2: Refactor the existing-row check in each handler**

For `POST /channels`, replace the existing block:
```java
if (channel.getYoutubeId() != null) {
    var existing = channelRepository.findByYoutubeId(channel.getYoutubeId());
    if (existing.isPresent()) {
        return ResponseEntity.status(HttpStatus.CONFLICT).build();
    }
}
```

With:
```java
if (channel.getYoutubeId() != null) {
    var existing = channelRepository.findByYoutubeId(channel.getYoutubeId());
    if (existing.isPresent()) {
        Channel ex = existing.get();
        if ("REQUEST_CHANGES".equals(ex.getStatus()) && user.getUid().equals(ex.getSubmittedBy())) {
            // Plan E: re-submit of an admin-bounced row. Flip back to PENDING.
            ex.setStatus("PENDING");
            ex.setReviewNotes(null);
            ex.setUpdatedAt(com.google.cloud.Timestamp.now());
            // Apply incoming category/displayOrder updates if provided
            if (channel.getCategoryIds() != null) ex.setCategoryIds(channel.getCategoryIds());
            channelRepository.save(ex);
            auditLogService.log("channel_resubmitted_after_changes", "channel", ex.getId(), user);
            return ResponseEntity.ok(ex);
        }
        return ResponseEntity.status(HttpStatus.CONFLICT).build();
    }
}
```

Mirror the same change in `POST /playlists` (around line 320) and `POST /videos` (around line 590) — substitute `playlistRepository` / `videoRepository` and `Playlist` / `Video` accordingly.

- [ ] **Step 3: Compile**

```bash
cd backend && ./gradlew compileJava
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/albunyaan/tube/controller/RegistryController.java
git commit -m "[FEAT-BACKEND-MODERATOR-01-T1]: registry POST upserts REQUEST_CHANGES rows back to PENDING"
```

---

## Task 2: REQUEST_CHANGES status + service method

**Files:** modify `backend/src/main/java/com/albunyaan/tube/service/ApprovalService.java`

- [ ] **Step 1: Add REQUEST_CHANGES to VALID_STATUSES**

Find the `VALID_STATUSES` constant:
```java
private static final java.util.Set<String> VALID_STATUSES = java.util.Set.of("PENDING", "APPROVED", "REJECTED");
```

Replace with:
```java
private static final java.util.Set<String> VALID_STATUSES = java.util.Set.of("PENDING", "APPROVED", "REJECTED", "REQUEST_CHANGES");
```

- [ ] **Step 2: Add `requestChanges(id, note, contentType, actor)` service method**

Pattern after the existing `reject(...)` method (search `public ApprovalResponseDto reject` — should be around line 706). Mirror its structure but write `REQUEST_CHANGES` instead of `REJECTED` and set `reviewNotes` instead of `rejectionReason`. CAS guard: `saveIfStatus(entity, "PENDING")`. Build and return an `ApprovalResponseDto` matching the reject response shape.

Key signature:
```java
public ApprovalResponseDto requestChanges(String id, String note, String contentType,
                                          String actorUid, String actorDisplayName)
    throws ExecutionException, InterruptedException, TimeoutException
```

Validate that `note` is non-blank; throw `IllegalArgumentException` if blank.

- [ ] **Step 3: Compile + write failing unit test**

Create `backend/src/test/java/com/albunyaan/tube/service/ApprovalServiceRequestChangesTest.java` with at least:
- `requestChanges_setsStatusAndNote_returns200Dto` — happy path
- `requestChanges_blankNote_throws` — validation
- `requestChanges_nonPendingItem_throws` — CAS failure

Run: `cd backend && ./gradlew test --tests "com.albunyaan.tube.service.ApprovalServiceRequestChangesTest"` → expect PASS.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/albunyaan/tube/service/ApprovalService.java \
       backend/src/test/java/com/albunyaan/tube/service/ApprovalServiceRequestChangesTest.java
git commit -m "[FEAT-BACKEND-MODERATOR-01-T2]: REQUEST_CHANGES service method + tests"
```

---

## Task 3: REQUEST_CHANGES controller endpoint

**Files:** create `backend/src/main/java/com/albunyaan/tube/dto/RequestChangesRequest.java`; modify `backend/src/main/java/com/albunyaan/tube/controller/ApprovalController.java`

- [ ] **Step 1: Create the request DTO**

```java
package com.albunyaan.tube.dto;

import jakarta.validation.constraints.NotBlank;

public class RequestChangesRequest {
    @NotBlank private String note;
    @NotBlank private String contentType;   // "channel" | "playlist" | "video"

    public String getNote()              { return note; }
    public void setNote(String v)        { this.note = v; }
    public String getContentType()       { return contentType; }
    public void setContentType(String v) { this.contentType = v; }
}
```

- [ ] **Step 2: Add the controller route**

In `ApprovalController.java`, after the `reject` handler (around line 220), add:

```java
@PreAuthorize("hasRole('ADMIN')")
@PostMapping("/{id}/request-changes")
public ResponseEntity<ApprovalResponseDto> requestChanges(
        @PathVariable String id,
        @Valid @RequestBody RequestChangesRequest req,
        @AuthenticationPrincipal FirebaseUserDetails user) {
    try {
        ApprovalResponseDto response = approvalService.requestChanges(
                id, req.getNote(), req.getContentType(), user.getUid(), user.getDisplayName());
        publicContentCacheService.evictPublicContentCaches();
        return ResponseEntity.ok(response);
    } catch (IllegalArgumentException e) {
        return ResponseEntity.badRequest().build();
    } catch (IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).build();
    } catch (Exception e) {
        log.error("Failed to request-changes id={}", id, e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
}
```

Imports: `jakarta.validation.Valid`, `com.albunyaan.tube.dto.RequestChangesRequest`.

- [ ] **Step 3: Compile + run controller test (existing AccountControllerTest pattern)**

Verify no regressions: `cd backend && ./gradlew test --tests "com.albunyaan.tube.controller.*"`.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/albunyaan/tube/dto/RequestChangesRequest.java \
       backend/src/main/java/com/albunyaan/tube/controller/ApprovalController.java
git commit -m "[FEAT-BACKEND-MODERATOR-01-T3]: POST /api/admin/approvals/{id}/request-changes endpoint"
```

---

## Task 4: Enrich PendingApprovalDto with submitter display fields

**Files:** modify `backend/src/main/java/com/albunyaan/tube/dto/PendingApprovalDto.java`; modify `backend/src/main/java/com/albunyaan/tube/service/ApprovalService.java`

- [ ] **Step 1: Add two nullable fields to `PendingApprovalDto`**

After the `private String submittedBy;` declaration, add:

```java
private String submittedByDisplayName;
private String submittedByEmail;

public String getSubmittedByDisplayName()           { return submittedByDisplayName; }
public void setSubmittedByDisplayName(String v)     { this.submittedByDisplayName = v; }
public String getSubmittedByEmail()                 { return submittedByEmail; }
public void setSubmittedByEmail(String v)           { this.submittedByEmail = v; }
```

- [ ] **Step 2: Enrich in `ApprovalService.getPendingApprovals` + `getMySubmissions`**

Locate the DTO-build loop in both methods. Inject `UserRepository` (already a Spring bean) into `ApprovalService` if not present. For each DTO built:

```java
if (dto.getSubmittedBy() != null) {
    userRepository.findByUid(dto.getSubmittedBy()).ifPresent(u -> {
        dto.setSubmittedByDisplayName(u.getDisplayName());
        dto.setSubmittedByEmail(u.getEmail());
    });
}
```

UserRepository.loadByUid is `@Cacheable` so the N+1 cost is bounded.

- [ ] **Step 3: Compile**

```bash
cd backend && ./gradlew compileJava
```

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/albunyaan/tube/dto/PendingApprovalDto.java \
       backend/src/main/java/com/albunyaan/tube/service/ApprovalService.java
git commit -m "[FEAT-BACKEND-MODERATOR-01-T4]: enrich PendingApprovalDto with submitter displayName + email"
```

---

## Task 5: SubmissionRateLimiter + HandlerInterceptor

**Files:** create `backend/src/main/java/com/albunyaan/tube/service/SubmissionRateLimiter.java`, `backend/src/main/java/com/albunyaan/tube/security/SubmissionRateLimitInterceptor.java`, and a `WebConfig` to register it.

- [ ] **Step 1: Create `SubmissionRateLimiter.java`**

```java
package com.albunyaan.tube.service;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Plan E — per-uid sliding-window rate limiter for moderator submissions.
 * 50 submissions per 24h. In-memory only; will not survive process restart
 * (acceptable for ≤20-user pre-release scale; migrate to Redis if needed).
 */
@Component
public class SubmissionRateLimiter {
    public static final int LIMIT = 50;
    public static final Duration WINDOW = Duration.ofHours(24);

    private final Clock clock;
    private final Map<String, Deque<Instant>> hits = new ConcurrentHashMap<>();

    public SubmissionRateLimiter(Clock clock) { this.clock = clock; }

    /** Returns null if allowed; otherwise seconds until the oldest hit ages out. */
    public Long tryAcquire(String uid) {
        if (uid == null || uid.isEmpty()) return null;
        Instant now = clock.instant();
        Instant cutoff = now.minus(WINDOW);
        Deque<Instant> dq = hits.computeIfAbsent(uid, k -> new ArrayDeque<>());
        synchronized (dq) {
            while (!dq.isEmpty() && dq.peekFirst().isBefore(cutoff)) dq.pollFirst();
            if (dq.size() >= LIMIT) {
                Instant oldest = dq.peekFirst();
                return oldest.plus(WINDOW).getEpochSecond() - now.getEpochSecond();
            }
            dq.addLast(now);
            return null;
        }
    }
}
```

- [ ] **Step 2: Write failing test**

`backend/src/test/java/com/albunyaan/tube/service/SubmissionRateLimiterTest.java`:

```java
package com.albunyaan.tube.service;

import org.junit.jupiter.api.Test;

import java.time.*;

import static org.junit.jupiter.api.Assertions.*;

class SubmissionRateLimiterTest {

    @Test void allowsUpToLimit() {
        Clock fixed = Clock.fixed(Instant.parse("2026-05-12T10:00:00Z"), ZoneOffset.UTC);
        var rl = new SubmissionRateLimiter(fixed);
        for (int i = 0; i < SubmissionRateLimiter.LIMIT; i++) {
            assertNull(rl.tryAcquire("uid"));
        }
    }

    @Test void rejectsAtLimit() {
        Clock fixed = Clock.fixed(Instant.parse("2026-05-12T10:00:00Z"), ZoneOffset.UTC);
        var rl = new SubmissionRateLimiter(fixed);
        for (int i = 0; i < SubmissionRateLimiter.LIMIT; i++) rl.tryAcquire("uid");
        Long retry = rl.tryAcquire("uid");
        assertNotNull(retry);
        assertEquals(86400L, retry);  // exactly 24h since the first hit (all 50 stamped at the same fixed clock)
    }

    @Test void slidingWindowReleasesOldHits() {
        java.util.concurrent.atomic.AtomicReference<Instant> nowRef =
            new java.util.concurrent.atomic.AtomicReference<>(Instant.parse("2026-05-12T10:00:00Z"));
        Clock mut = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return nowRef.get(); }
        };
        var rl = new SubmissionRateLimiter(mut);
        for (int i = 0; i < SubmissionRateLimiter.LIMIT; i++) rl.tryAcquire("uid");
        // Advance 25 hours — all hits age out
        nowRef.set(nowRef.get().plus(Duration.ofHours(25)));
        assertNull(rl.tryAcquire("uid"));
    }

    @Test void perUidIsolation() {
        Clock fixed = Clock.fixed(Instant.parse("2026-05-12T10:00:00Z"), ZoneOffset.UTC);
        var rl = new SubmissionRateLimiter(fixed);
        for (int i = 0; i < SubmissionRateLimiter.LIMIT; i++) rl.tryAcquire("uid-A");
        assertNull(rl.tryAcquire("uid-B"));
    }
}
```

Run: `cd backend && ./gradlew test --tests "com.albunyaan.tube.service.SubmissionRateLimiterTest"` → 4 PASS.

- [ ] **Step 3: Create `SubmissionRateLimitInterceptor.java`**

```java
package com.albunyaan.tube.security;

import com.albunyaan.tube.service.SubmissionRateLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;

@Component
public class SubmissionRateLimitInterceptor implements HandlerInterceptor {

    private final SubmissionRateLimiter limiter;
    private final ObjectMapper json;

    public SubmissionRateLimitInterceptor(SubmissionRateLimiter limiter, ObjectMapper json) {
        this.limiter = limiter;
        this.json = json;
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws Exception {
        if (!"POST".equals(req.getMethod())) return true;
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return true;
        Object principal = auth.getPrincipal();
        if (!(principal instanceof FirebaseUserDetails fud)) return true;
        Long retryAfter = limiter.tryAcquire(fud.getUid());
        if (retryAfter == null) return true;
        res.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        res.setHeader("Retry-After", String.valueOf(retryAfter));
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        json.writeValue(res.getWriter(), Map.of(
            "code", "RATE_LIMIT",
            "retryAfterSeconds", retryAfter,
            "message", "Daily submission limit reached. Try again later."
        ));
        return false;
    }
}
```

- [ ] **Step 4: Register interceptor via WebConfig**

Find existing `WebConfig` (or any `WebMvcConfigurer` class). If none exists, create:

`backend/src/main/java/com/albunyaan/tube/config/WebConfig.java`:

```java
package com.albunyaan.tube.config;

import com.albunyaan.tube.security.SubmissionRateLimitInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final SubmissionRateLimitInterceptor rateLimit;

    public WebConfig(SubmissionRateLimitInterceptor rateLimit) { this.rateLimit = rateLimit; }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimit)
                .addPathPatterns(
                    "/api/admin/registry/channels",
                    "/api/admin/registry/playlists",
                    "/api/admin/registry/videos"
                );
    }
}
```

If a WebConfig already exists, append `.addInterceptor(rateLimit).addPathPatterns(...)` to its existing `addInterceptors` method.

- [ ] **Step 5: Compile + commit**

```bash
cd backend && ./gradlew compileJava
cd /home/farouq/Development/albunyaantube
git add backend/src/main/java/com/albunyaan/tube/service/SubmissionRateLimiter.java \
       backend/src/main/java/com/albunyaan/tube/security/SubmissionRateLimitInterceptor.java \
       backend/src/main/java/com/albunyaan/tube/config/WebConfig.java \
       backend/src/test/java/com/albunyaan/tube/service/SubmissionRateLimiterTest.java
git commit -m "[FEAT-BACKEND-MODERATOR-01-T5]: SubmissionRateLimiter (50/24h) + interceptor"
```

---

## Task 6: Backend integration tests

**Files:** create three IT files.

- [ ] **Step 1: `RequestChangesIT.java`**

Follow `SyncControllerIT` pattern (extends BaseIntegrationTest, @MockBean FirebaseAuth, seedUser/stubToken helpers). Test the state machine:

```java
// 1. Moderator submits a channel via POST /api/admin/registry/channels → 201, status=PENDING
// 2. Admin requests changes via POST /api/admin/approvals/{id}/request-changes → 200, status=REQUEST_CHANGES, reviewNotes set
// 3. Moderator re-submits same youtubeId via POST /api/admin/registry/channels → 200, status flipped back to PENDING, reviewNotes cleared
// 4. Admin approves → 200, status=APPROVED
```

Use the existing `seedUser(uid, email, role, status)` helper from `AccountStatusFilterIntegrationTest`.

- [ ] **Step 2: `MySubmissionsEnrichmentIT.java`**

```java
// Seed moderator with displayName="Test Mod", email="mod@test.com"
// POST a channel as moderator
// GET /api/admin/approvals/my-submissions as the moderator
// Assert response items include submittedByDisplayName="Test Mod" and submittedByEmail="mod@test.com"
```

- [ ] **Step 3: `SubmissionRateLimitIT.java`**

```java
// Seed moderator
// Loop 50 times: POST /api/admin/registry/channels with unique youtubeIds → expect 201 each time
// 51st POST → expect 429 with Retry-After header and { code: "RATE_LIMIT", retryAfterSeconds: ... } body
```

- [ ] **Step 4: Run + commit**

```bash
cd backend
firebase emulators:start --only firestore,auth --project demo-test &  # if not already running
./gradlew test -Pintegration=true \
    --tests "com.albunyaan.tube.integration.RequestChangesIT" \
    --tests "com.albunyaan.tube.integration.MySubmissionsEnrichmentIT" \
    --tests "com.albunyaan.tube.integration.SubmissionRateLimitIT"

cd /home/farouq/Development/albunyaantube
git add backend/src/test/java/com/albunyaan/tube/integration/RequestChangesIT.java \
       backend/src/test/java/com/albunyaan/tube/integration/MySubmissionsEnrichmentIT.java \
       backend/src/test/java/com/albunyaan/tube/integration/SubmissionRateLimitIT.java
git commit -m "[TEST-BACKEND-MODERATOR-01-T6]: state machine + enrichment + rate limit ITs"
```

---

# Phase 2 — Android

## Task 7: AccountState.Loaded.role + repository mapping

**Files:** modify `android/app/src/main/java/com/albunyaan/tube/auth/AccountState.kt`; modify `android/app/src/main/java/com/albunyaan/tube/auth/AccountRepositoryImpl.kt` (or wherever the /me response is parsed).

- [ ] **Step 1: Add `role: String` to `AccountState.Loaded`**

```kotlin
data class Loaded(
    val uid: String,
    val email: String?,
    val displayName: String?,
    val status: AccountStatus,
    val role: String,     // "user" | "moderator" | "admin"
) : AccountState
```

- [ ] **Step 2: Map role from `/me` response in `AccountRepositoryImpl`**

Find the `AccountMeResponse` Moshi DTO — it should already have a `role` field from Plan A. If not, add it. Then when building the Loaded state, pass `role = response.role ?: "user"`.

- [ ] **Step 3: Compile and fix any callers**

Any existing test fixtures that construct `AccountState.Loaded` directly need the new `role` arg. Default to `"user"` in those tests.

```bash
cd android && ./gradlew :app:assembleDebug 2>&1 | tail -30
```

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/com/albunyaan/tube/auth/AccountState.kt \
       android/app/src/main/java/com/albunyaan/tube/auth/AccountRepositoryImpl.kt \
       android/app/src/main/java/com/albunyaan/tube/data/account/AccountService.kt
# add any test fixture files that need defaults
git commit -m "[FEAT-ANDROID-MODERATOR-01-T7]: AccountState.Loaded.role field + /me mapping"
```

---

## Task 8: ApprovalApi + DTOs

**Files:** create
- `android/app/src/main/java/com/albunyaan/tube/data/approvals/ApprovalApi.kt`
- `android/app/src/main/java/com/albunyaan/tube/data/approvals/dto/ApprovalDtos.kt`

- [ ] **Step 1: `ApprovalDtos.kt`**

```kotlin
package com.albunyaan.tube.data.approvals.dto

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class PendingApprovalDto(
    val id: String,
    val type: String,                    // "channel" | "playlist" | "video"
    val entityId: String,                // youtubeId
    val title: String?,
    val category: String?,
    val submittedAt: Long?,              // millis-since-epoch
    val submittedBy: String?,            // uid
    val submittedByDisplayName: String?,
    val submittedByEmail: String?,
    val status: String,                  // "PENDING" | "APPROVED" | "REJECTED" | "REQUEST_CHANGES"
    val rejectionReason: String? = null,
    val reviewNotes: String? = null,
)

@JsonClass(generateAdapter = true)
data class CursorPageDto<T>(
    val items: List<T>,
    val nextCursor: String? = null,
)

@JsonClass(generateAdapter = true)
data class SubmitChannelRequest(
    val youtubeId: String,
    val categoryIds: List<String>,
)

@JsonClass(generateAdapter = true)
data class SubmitPlaylistRequest(
    val youtubeId: String,
    val categoryIds: List<String>,
)

@JsonClass(generateAdapter = true)
data class SubmitVideoRequest(
    val youtubeId: String,
    val categoryIds: List<String>,
)
```

- [ ] **Step 2: `ApprovalApi.kt`**

```kotlin
package com.albunyaan.tube.data.approvals

import com.albunyaan.tube.data.approvals.dto.*
import retrofit2.Response
import retrofit2.http.*

interface ApprovalApi {

    @GET("api/admin/approvals/my-submissions")
    suspend fun mySubmissions(
        @Query("status") status: String? = null,
        @Query("cursor") cursor: String? = null,
        @Query("limit")  limit: Int = 50,
    ): Response<CursorPageDto<PendingApprovalDto>>

    @POST("api/admin/registry/channels")
    suspend fun submitChannel(@Body body: SubmitChannelRequest): Response<PendingApprovalDto>

    @POST("api/admin/registry/playlists")
    suspend fun submitPlaylist(@Body body: SubmitPlaylistRequest): Response<PendingApprovalDto>

    @POST("api/admin/registry/videos")
    suspend fun submitVideo(@Body body: SubmitVideoRequest): Response<PendingApprovalDto>
}
```

> **Verify:** the actual POST body shape on the backend may differ — `RegistryController.POST /channels` takes the full `Channel` model. Check by reading the controller; if the body is the full Channel-shaped object, expand the DTO. If only youtubeId+categoryIds are required and the rest is server-filled, the trimmed DTO above is enough.

- [ ] **Step 3: Provide `ApprovalApi` in `NetworkModule`**

```kotlin
@Provides
@Singleton
fun provideApprovalApi(retrofit: Retrofit): ApprovalApi = retrofit.create(ApprovalApi::class.java)
```

- [ ] **Step 4: Compile + commit**

```bash
cd android && ./gradlew :app:kspDebugKotlin
cd /home/farouq/Development/albunyaantube
git add android/app/src/main/java/com/albunyaan/tube/data/approvals/ \
       android/app/src/main/java/com/albunyaan/tube/di/NetworkModule.kt
git commit -m "[FEAT-ANDROID-MODERATOR-01-T8]: ApprovalApi + Moshi DTOs"
```

---

## Task 9: MySubmissionsRepository

**Files:** create `android/app/src/main/java/com/albunyaan/tube/data/approvals/MySubmissionsRepository.kt`

- [ ] **Step 1: Repository implementation**

```kotlin
package com.albunyaan.tube.data.approvals

import com.albunyaan.tube.data.approvals.dto.*
import javax.inject.Inject
import javax.inject.Singleton

class RateLimitError(val retryAfterSeconds: Long) : RuntimeException("rate-limited")

@Singleton
class MySubmissionsRepository @Inject constructor(
    private val api: ApprovalApi,
) {
    suspend fun fetchMySubmissions(status: String? = null): Result<List<PendingApprovalDto>> = runCatching {
        val resp = api.mySubmissions(status = status, cursor = null, limit = 100)
        if (!resp.isSuccessful) error("HTTP ${resp.code()}")
        resp.body()?.items ?: emptyList()
    }

    suspend fun submitChannel(youtubeId: String, categoryIds: List<String>): Result<Unit> = submit {
        api.submitChannel(SubmitChannelRequest(youtubeId, categoryIds))
    }

    suspend fun submitPlaylist(youtubeId: String, categoryIds: List<String>): Result<Unit> = submit {
        api.submitPlaylist(SubmitPlaylistRequest(youtubeId, categoryIds))
    }

    suspend fun submitVideo(youtubeId: String, categoryIds: List<String>): Result<Unit> = submit {
        api.submitVideo(SubmitVideoRequest(youtubeId, categoryIds))
    }

    private suspend inline fun submit(crossinline call: suspend () -> retrofit2.Response<*>): Result<Unit> {
        return runCatching {
            val resp = call()
            when {
                resp.isSuccessful -> Unit
                resp.code() == 429 -> throw RateLimitError(resp.headers()["Retry-After"]?.toLongOrNull() ?: 0L)
                resp.code() == 409 -> error("Already exists")
                else -> error("HTTP ${resp.code()}")
            }
        }
    }
}
```

- [ ] **Step 2: Write failing test**

`android/app/src/test/java/com/albunyaan/tube/data/approvals/MySubmissionsRepositoryTest.kt`:

```kotlin
package com.albunyaan.tube.data.approvals

import com.albunyaan.tube.data.approvals.dto.*
import kotlinx.coroutines.test.runTest
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response

class MySubmissionsRepositoryTest {

    @Test fun rateLimitedSubmitReturnsRateLimitError() = runTest {
        val api = object : ApprovalApi {
            override suspend fun mySubmissions(status: String?, cursor: String?, limit: Int) =
                Response.success(CursorPageDto<PendingApprovalDto>(emptyList(), null))
            override suspend fun submitChannel(body: SubmitChannelRequest): Response<PendingApprovalDto> {
                val rb = "".toResponseBody("application/json".toMediaType())
                return Response.error(
                    rb,
                    okhttp3.Response.Builder()
                        .request(okhttp3.Request.Builder().url("http://test/").build())
                        .protocol(okhttp3.Protocol.HTTP_1_1)
                        .code(429).message("Too Many Requests")
                        .headers(Headers.headersOf("Retry-After", "3600"))
                        .build()
                )
            }
            override suspend fun submitPlaylist(body: SubmitPlaylistRequest) = error("n/a")
            override suspend fun submitVideo(body: SubmitVideoRequest) = error("n/a")
        }
        val repo = MySubmissionsRepository(api)

        val result = repo.submitChannel("UC1", listOf("cat-1"))

        assertTrue(result.isFailure)
        val err = result.exceptionOrNull()
        assertTrue(err is RateLimitError)
        assertEquals(3600L, (err as RateLimitError).retryAfterSeconds)
    }
}
```

- [ ] **Step 3: Compile + run test + commit**

```bash
cd android && ./gradlew :app:testDebugUnitTest --tests "com.albunyaan.tube.data.approvals.MySubmissionsRepositoryTest"
cd /home/farouq/Development/albunyaantube
git add android/app/src/main/java/com/albunyaan/tube/data/approvals/MySubmissionsRepository.kt \
       android/app/src/test/java/com/albunyaan/tube/data/approvals/MySubmissionsRepositoryTest.kt
git commit -m "[FEAT-ANDROID-MODERATOR-01-T9]: MySubmissionsRepository + rate-limit error mapping"
```

---

## Task 10: MySubmissionsFragment + ViewModel + Adapter + layouts

**Files:** create the fragment / viewmodel / adapter / two layouts.

- [ ] **Step 1: ViewModel**

```kotlin
package com.albunyaan.tube.ui.me.submissions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.albunyaan.tube.data.approvals.MySubmissionsRepository
import com.albunyaan.tube.data.approvals.dto.PendingApprovalDto
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface MySubmissionsUiState {
    data object Loading : MySubmissionsUiState
    data class Loaded(val items: List<PendingApprovalDto>) : MySubmissionsUiState
    data object Empty : MySubmissionsUiState
    data class Error(val message: String) : MySubmissionsUiState
}

@HiltViewModel
class MySubmissionsViewModel @Inject constructor(
    private val repo: MySubmissionsRepository,
) : ViewModel() {
    private val _state = MutableStateFlow<MySubmissionsUiState>(MySubmissionsUiState.Loading)
    val state: StateFlow<MySubmissionsUiState> = _state

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _state.value = MySubmissionsUiState.Loading
            repo.fetchMySubmissions().fold(
                onSuccess = { items -> _state.value = if (items.isEmpty()) MySubmissionsUiState.Empty else MySubmissionsUiState.Loaded(items) },
                onFailure = { e -> _state.value = MySubmissionsUiState.Error(e.message ?: "Failed to load") },
            )
        }
    }
}
```

- [ ] **Step 2: Adapter**

Use ListAdapter<PendingApprovalDto, …> with DiffUtil based on `id`. Bind: status badge (use 4 drawables for 4 status colours), title, category, submittedAt formatted via DateUtils.getRelativeTimeSpanString, reviewNotes shown only when status == "REQUEST_CHANGES".

- [ ] **Step 3: Fragment + layouts**

`fragment_my_submissions.xml`:
- ConstraintLayout (or CoordinatorLayout for FAB)
- MaterialToolbar with title "My Submissions"
- SwipeRefreshLayout
- RecyclerView
- FloatingActionButton at bottom-end with `+` icon

`item_my_submission.xml`:
- 4dp left strip coloured by status
- Title (medium emphasis text)
- Type · Category (secondary text)
- submittedAt (caption, right-aligned)
- reviewNotes (visibility=gone unless REQUEST_CHANGES)

Fragment:
- @AndroidEntryPoint
- Observe `viewModel.state` and render via the adapter
- SwipeRefresh → `viewModel.refresh()`
- FAB → `SubmitContentBottomSheet().show(...)` (T11)

- [ ] **Step 4: Add string resources + commit**

```xml
<string name="my_submissions_title">My Submissions</string>
<string name="my_submissions_empty">No submissions yet. Tap + to suggest content for the library.</string>
<string name="my_submissions_status_pending">Pending</string>
<string name="my_submissions_status_approved">Approved</string>
<string name="my_submissions_status_rejected">Rejected</string>
<string name="my_submissions_status_request_changes">Changes requested</string>
```

```bash
cd android && ./gradlew :app:assembleDebug 2>&1 | tail -20

cd /home/farouq/Development/albunyaantube
git add android/app/src/main/java/com/albunyaan/tube/ui/me/submissions/ \
       android/app/src/main/res/layout/fragment_my_submissions.xml \
       android/app/src/main/res/layout/item_my_submission.xml \
       android/app/src/main/res/values/strings.xml
git commit -m "[FEAT-ANDROID-MODERATOR-01-T10]: MySubmissionsFragment + ViewModel + Adapter + layouts"
```

---

## Task 11: SubmitContentBottomSheet

**Files:** create `SubmitContentBottomSheet.kt`, `bottom_sheet_submit_content.xml`.

- [ ] **Step 1: Layout**

Material BottomSheetDialogFragment with:
- TabLayout: "Paste URL" / "Search"
- ViewPager2 holding two child fragments (or two visibility-toggled inner layouts in one fragment to avoid nested fragment plumbing)
- Category dropdown (AutoCompleteTextView backed by Material 3 ExposedDropdownMenu)
- Submit Button

For Plan E v1, use a single fragment with two LinearLayouts (`paste_url_section`, `search_section`) toggled via TabLayout listener — simpler than ViewPager2 nested in a sheet.

- [ ] **Step 2: Submit flow**

URL parsing: if available, reuse the project's URL classifier (search for `parseYoutubeUrl` or `detectType`). Otherwise inline a regex-based classifier:
- `youtube.com/watch?v=XXX` or `youtu.be/XXX` → video, extract `XXX`
- `youtube.com/playlist?list=XXX` → playlist
- `youtube.com/channel/XXX` or `/c/XXX` or `/@XXX` → channel (the @handle case needs an extra resolution step via NewPipe; for v1, accept channel-id-style URLs only and show "Please paste a channel URL with /channel/UCxxx" error for unsupported variants)

Search flow: call existing search API the frontend uses; if uncertain which API powers admin search, the simpler approach for v1 is **omit the search tab** and ship only the Paste-URL tab. Mark search as a follow-up.

> **Decision for v1:** ship Paste-URL only. Search tab → "Coming soon" placeholder. Reduces scope without losing the core submit loop.

- [ ] **Step 3: Categories load**

Reuse the existing `CategoriesService` / `Category` model from the home screen. Inject and call its observable. Bind to the dropdown.

- [ ] **Step 4: Submission**

On Submit:
- Validate URL parsed cleanly
- Validate a category is selected
- Disable button + show progress
- Call `viewModel.submit(type, youtubeId, categoryIds)` → ViewModel calls repository
- On success: dismiss sheet, refresh parent fragment list, snackbar "Submitted for review"
- On `RateLimitError`: snackbar "Daily submission limit hit. Try again in $hours h."
- On other error: snackbar with generic message

- [ ] **Step 5: Commit**

```bash
cd android && ./gradlew :app:assembleDebug 2>&1 | tail -10

cd /home/farouq/Development/albunyaantube
git add android/app/src/main/java/com/albunyaan/tube/ui/me/submissions/SubmitContentBottomSheet.kt \
       android/app/src/main/res/layout/bottom_sheet_submit_content.xml
git commit -m "[FEAT-ANDROID-MODERATOR-01-T11]: SubmitContentBottomSheet (paste-URL tab; search deferred)"
```

---

## Task 12: Wire into Me-tab navigation (role-gated)

**Files:** modify `MeFragment.kt` (or wherever Me-tab list is built) + `main_graph.xml`.

- [ ] **Step 1: Add nav action**

In `android/app/src/main/res/navigation/main_graph.xml`, add a `<fragment>` entry for `MySubmissionsFragment` and an `<action>` from Me-tab fragment to it.

- [ ] **Step 2: Conditional list entry in Me tab**

Find where Me-tab list items are constructed. After observing `accountStateFlow`, only show "My Submissions" when role is `moderator` or `admin`:

```kotlin
val role = (accountState as? AccountState.Loaded)?.role ?: "user"
val showSubmissions = role == "moderator" || role == "admin"
binding.mySubmissionsEntry.isVisible = showSubmissions
binding.mySubmissionsEntry.setOnClickListener {
    findNavController().navigate(R.id.action_me_to_mySubmissions)
}
```

(Adjust to the actual binding shape — the Me-tab may use a RecyclerView of MeFeedItems, in which case append a new item type.)

- [ ] **Step 3: Compile + manual smoke test (instructions only)**

```bash
cd android && ./gradlew :app:assembleDebug
```

Install and verify that signing in as a `user` does NOT show the entry; signing in as a `moderator` or `admin` does show it.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/res/navigation/main_graph.xml \
       android/app/src/main/java/com/albunyaan/tube/ui/me/   # MeFragment + any binding files
git commit -m "[FEAT-ANDROID-MODERATOR-01-T12]: Me-tab entry for moderators/admins → MySubmissions"
```

---

## Task 13: Android unit tests

**Files:** create `MySubmissionsViewModelTest.kt`.

- [ ] **Step 1: Write test**

```kotlin
package com.albunyaan.tube.ui.me.submissions

import com.albunyaan.tube.data.approvals.MySubmissionsRepository
import com.albunyaan.tube.data.approvals.dto.PendingApprovalDto
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class MySubmissionsViewModelTest {

    @Test fun loadingThenLoaded() = runTest {
        val repo: MySubmissionsRepository = mock()
        whenever(repo.fetchMySubmissions(null)).thenReturn(Result.success(
            listOf(PendingApprovalDto(
                id = "a", type = "channel", entityId = "UC1", title = "X", category = "Quran",
                submittedAt = 1000L, submittedBy = "uid",
                submittedByDisplayName = "Test", submittedByEmail = "t@x",
                status = "PENDING",
            ))
        ))
        val vm = MySubmissionsViewModel(repo)
        advanceUntilIdle()
        val state = vm.state.value
        assertTrue(state is MySubmissionsUiState.Loaded)
        assertEquals(1, (state as MySubmissionsUiState.Loaded).items.size)
    }

    @Test fun emptyResultsState() = runTest {
        val repo: MySubmissionsRepository = mock()
        whenever(repo.fetchMySubmissions(null)).thenReturn(Result.success(emptyList()))
        val vm = MySubmissionsViewModel(repo)
        advanceUntilIdle()
        assertTrue(vm.state.value is MySubmissionsUiState.Empty)
    }

    @Test fun errorState() = runTest {
        val repo: MySubmissionsRepository = mock()
        whenever(repo.fetchMySubmissions(null)).thenReturn(Result.failure(RuntimeException("boom")))
        val vm = MySubmissionsViewModel(repo)
        advanceUntilIdle()
        assertTrue(vm.state.value is MySubmissionsUiState.Error)
    }
}
```

- [ ] **Step 2: Run + commit**

```bash
cd android && ./gradlew :app:testDebugUnitTest --tests "com.albunyaan.tube.ui.me.submissions.*"

cd /home/farouq/Development/albunyaantube
git add android/app/src/test/java/com/albunyaan/tube/ui/me/submissions/
git commit -m "[TEST-ANDROID-MODERATOR-01-T13]: MySubmissionsViewModel state machine tests"
```

---

## Task 14: E2E manual + PR

- [ ] **Step 1: Full test suites**

```bash
cd backend && ./gradlew test
cd android && ./gradlew :app:testDebugUnitTest
```

Both BUILD SUCCESSFUL.

- [ ] **Step 2: Push + PR**

```bash
cd /home/farouq/Development/albunyaantube
git push -u origin feature/MODERATOR-01-workflow

gh pr create --base develop --title "[FEAT-MODERATOR-01]: Moderator submission workflow (Plan E)" --body "$(cat <<'EOF'
## Summary

Plan E rounds out the moderator submission loop:
- Backend: REQUEST_CHANGES status + endpoint, PendingApprovalDto submitter-name enrichment, 50/24h rate limit.
- Android: "My Submissions" Me-tab section (role-gated) + Paste-URL submit bottom sheet.
- Frontend: untouched (already in place).

## Spec & Plan
- Spec: docs/superpowers/specs/2026-05-12-plan-e-moderator-workflow-design.md
- Plan: docs/superpowers/plans/2026-05-12-plan-e-moderator-workflow.md

## Test plan
- [x] Backend unit + integration tests
- [x] Android unit tests
- [ ] Manual: moderator signs in, submits a channel, admin requests changes, moderator sees note, re-submits, admin approves
- [ ] Manual: rate-limit 51st submission returns 429 with retry-after

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

---

## Self-Review

- Every spec section has a task.
- One scope reduction: SubmitContentBottomSheet ships Paste-URL only; Search tab deferred to follow-up.
- Type consistency: backend `REQUEST_CHANGES` string ↔ Android status comparison string, both uppercase exact.
- `getById` regression risk from Plan D not touched here.

---

## 2026-05-19 follow-up — Plan G

The "Search tab" deferred from `SubmitContentBottomSheet` was replaced in Plan G by a dedicated `SuggestContentFragment` reachable from the Me-tab kebab. Search hits a new server-side `GET /api/admin/youtube/search` (NewPipe-Extractor backed; reuses the existing `CacheConfig.CACHE_NEWPIPE_SEARCH_RESULTS` cache). The bottom sheet remains URL-paste and now also accepts a `prefillUrl` argument from search results — `SuggestContentFragment` taps a result, builds the canonical URL, and shows the existing bottom sheet pre-filled. The submission rate-limit (50/24h per uid) still applies; a separate 10/hour rate limit was added in Plan G for `PUT /api/account/profile` only. See `docs/superpowers/plans/2026-05-19-plan-g-profile-edit-and-suggest-search.md`.
