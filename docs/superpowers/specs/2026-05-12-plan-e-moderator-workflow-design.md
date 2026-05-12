# Plan E — Moderator Submission Workflow — Design Spec

**Status:** Draft, awaiting user review.
**Date:** 2026-05-12
**Ticket:** MODERATOR-01
**Position in series:** Plan E of six. Depends on Plans A–C (account foundation, auth, profile bootstrap) merged to develop.

---

## 1. Goal

Round out the moderator content-submission loop:

- Add a third approval status `REQUEST_CHANGES` so admins can send a submission back to the moderator with a note instead of binary approve/reject.
- Enrich `PendingApprovalDto` with `submittedByDisplayName` + `submittedByEmail` so the admin review surface no longer shows a raw uid.
- Rate-limit moderator submissions (50/24h per uid) to prevent accidental floods.
- Ship an Android "My Submissions" experience: a Me-tab section that lists the moderator's own pending/approved/rejected/request_changes items + a + button to submit a new content suggestion.

Frontend admin dashboard already has the submit + history surfaces; not touched here.

---

## 2. Why this is a separate plan

The backend already has most of the moderator surface (per `feature/SYNC-01-engine` discoveries):
- `GET /api/admin/approvals/my-submissions` — scopes to the caller's submittedBy.
- `POST /api/admin/approvals/{id}/approve|reject` — ADMIN-only.
- `RegistryController` POSTs accept moderator submissions.
- `PendingApprovalDto` has `submittedBy`, `reviewNotes`, `rejectionReason`, `status`.

Plan E adds the missing third status (`REQUEST_CHANGES` + endpoint), display-name enrichment, a rate limit, and the Android user-facing surfaces. Each is small in isolation; together they form a coherent moderator product update.

---

## 3. Locked design decisions

| # | Decision | Rationale |
|---|---|---|
| E1 | Third approval status string is `REQUEST_CHANGES`. Stored as the entity's `status` field exactly like PENDING/APPROVED/REJECTED. | Mirrors existing string-based status scheme; no enum required. |
| E2 | Endpoint shape: `POST /api/admin/approvals/{id}/request-changes` with body `{ "note": "...", "contentType": "channel|playlist|video" }`. ADMIN-only via `@PreAuthorize("hasRole('ADMIN')")`. | Matches `/approve` and `/reject` route style. |
| E3 | When admin requests changes, the entity transitions `PENDING → REQUEST_CHANGES`. `reviewNotes` field is set to the note. Re-submission flips it back to `PENDING`. | Simple state machine; the existing CAS pattern `saveIfStatus(..., "PENDING")` extends to allow `REQUEST_CHANGES → PENDING` on moderator edit. |
| E4 | Notification: store-only. No FCM / email push when status flips. Moderator sees it when they refresh their "My Submissions" view. | Per user: matches MVP scope; revisit when Plan F adds email delivery. |
| E5 | Rate limit: 50 submissions / 24h per moderator uid. Applied at the RegistryController POST layer. HTTP 429 with `{code: "RATE_LIMIT", retryAfterSeconds: N}` when over. | User-chosen Q1 answer. |
| E6 | Rate-limit implementation: in-memory `ConcurrentHashMap<String, Deque<Instant>>` keyed by uid, prune timestamps older than 24h on every request. Single-instance backend; if we go multi-instance, swap to Redis later. | YAGNI for the ≤20-user scale. |
| E7 | `submittedByDisplayName` + `submittedByEmail` lookup: when `getPendingApprovals` / `getMySubmissions` builds DTOs, look up the user doc by `submittedBy` uid via `userRepository.findByUid(uid)`. Cache by uid for the duration of the request (avoid N+1). | Plan A's `UserRepository` already has `@Cacheable` on `loadByUid`, so the second call hits the cache. |
| E8 | Android "My Submissions" lives in the Me tab as a new section with a `+` FAB. List shows: status badge, title, category, submittedAt, reviewNotes when present. Pull-to-refresh + auto-refresh on ON_RESUME. | User-chosen Q2/Q3 answers. |
| E9 | Submission flow on Android: + FAB → "Suggest content" sheet with two input modes: (a) paste a YouTube URL (channel/playlist/video), (b) search query → results list → pick one. Then category picker → submit. | Mirrors the existing admin-frontend submit UX. |
| E10 | Re-submission after REQUEST_CHANGES: the same row is edited in place (status flips back to PENDING, reviewNotes cleared) rather than creating a new submission. Re-submission counts against the rate limit. | Simpler than spawning a fresh row; preserves audit trail. |

---

## 4. Scope

### In scope

- Backend:
  - `REQUEST_CHANGES` status string added to `VALID_STATUSES` in `ApprovalService`.
  - `POST /api/admin/approvals/{id}/request-changes` endpoint.
  - Update `getPendingApprovals` + `getMySubmissions` to enrich DTOs with `submittedByDisplayName` + `submittedByEmail`.
  - Add `submittedByDisplayName: String?` + `submittedByEmail: String?` fields to `PendingApprovalDto`.
  - New `SubmissionRateLimiter` component, applied to `RegistryController` POST handlers via a Spring HandlerInterceptor or `@Aspect`.
  - Update API spec (`docs/architecture/api-specification.yaml`).
- Android:
  - New `MySubmissionsRepository` + Retrofit `ApprovalApi` interface for `/my-submissions`, `/registry/{type}` POSTs.
  - New `MySubmissionsFragment` in Me-tab navigation graph, accessible via a Me-tab list entry visible only to MODERATOR-role users.
  - New `SubmitContentBottomSheet` for paste-URL / search → category-picker → submit flow.
  - Pull-to-refresh + ON_RESUME refresh.
- Tests:
  - Backend unit: REQUEST_CHANGES endpoint validation, rate-limiter behaviour.
  - Backend integration (Firestore emulator): full state machine PENDING → REQUEST_CHANGES → PENDING → APPROVED.
  - Android unit: MySubmissionsRepository state mapping, ApprovalApi DTO round-trip, rate-limit error mapping.

### Out of scope

- Frontend admin dashboard changes (already has submit + history per user).
- Email / FCM notification when REQUEST_CHANGES fires (deferred to Plan F).
- "All submissions" admin view (admin already has full pending list).
- Moderator notification badge in Android nav (could be a follow-up; not blocking).

---

## 5. Backend data model

No new collections. Existing entity status field gains a new permitted value.

### Status state machine

```
PENDING ─approve→ APPROVED                    (admin)
PENDING ─reject→ REJECTED                     (admin)
PENDING ─request_changes→ REQUEST_CHANGES     (admin, new in Plan E)
REQUEST_CHANGES ─edit→ PENDING                (moderator resubmits)
APPROVED, REJECTED: terminal
```

### `PendingApprovalDto` (modified)

Add two nullable fields:

```java
private String submittedByDisplayName;   // populated server-side from user doc
private String submittedByEmail;         // populated server-side from user doc
```

The existing `submittedBy: String` (uid) stays.

### `ApprovalService.VALID_STATUSES` (modified)

```java
private static final java.util.Set<String> VALID_STATUSES =
    java.util.Set.of("PENDING", "APPROVED", "REJECTED", "REQUEST_CHANGES");
```

---

## 6. API surface

### REQUEST_CHANGES endpoint

```
POST /api/admin/approvals/{id}/request-changes
  Authorization: Bearer <admin-token>
  Body:  { "note": "Wrong category, should be Quran", "contentType": "channel" }
  → 200 ApprovalResponseDto (status=REQUEST_CHANGES, reviewNotes=note)
  → 400 if note is blank
  → 404 if id not found
  → 409 if entity is not in PENDING state
  → 403 if caller is not ADMIN
```

`contentType` mirrors the existing `/approve` and `/reject` request bodies (channel/playlist/video — needed because approvals live across three repositories).

Mutates: `entity.status = "REQUEST_CHANGES"`, `entity.reviewNotes = note`. CAS guard: `saveIfStatus(entity, "PENDING")`.

### Moderator resubmit (existing endpoint, new behaviour)

`POST /api/admin/registry/{type}` already handles new submissions. For resubmit of an existing REQUEST_CHANGES row, the moderator submits via the same endpoint with the same youtubeId + categoryId; the service detects the existing row by `(submittedBy, youtubeId)` and flips it back to PENDING + clears reviewNotes. **Investigation note:** verify whether RegistryController already handles re-submit of an existing row, or always inserts new. If always-insert, add an upsert-on-existing-pending-or-request-changes path.

### My submissions (existing endpoint, enriched response)

`GET /api/admin/approvals/my-submissions` — unchanged behavior; DTOs now include `submittedByDisplayName` + `submittedByEmail` (always the caller's own values for this route, but consistent with `/pending` shape).

### Rate-limit response

When a moderator exceeds 50 submissions / 24h:

```
POST /api/admin/registry/channels
  → 429 Too Many Requests
       { "code": "RATE_LIMIT", "retryAfterSeconds": 7200 }
       Retry-After: 7200
```

`retryAfterSeconds` = seconds until the oldest tracked submission falls out of the 24h window.

---

## 7. Rate limiter

### `SubmissionRateLimiter` (new `@Component`)

```java
@Component
public class SubmissionRateLimiter {
    public static final int LIMIT = 50;
    public static final Duration WINDOW = Duration.ofHours(24);

    private final Clock clock;
    private final Map<String, Deque<Instant>> hits = new ConcurrentHashMap<>();

    /** Returns null if the request is allowed; otherwise returns seconds to retry. */
    public Long tryAcquire(String uid) {
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

Applied via a Spring HandlerInterceptor that runs on `/api/admin/registry/{type}` POST paths. Reads the caller's uid from `SecurityContextHolder`. On reject, the interceptor writes the 429 response directly.

Single-process map: this is in-memory only. If we deploy multi-instance later, swap for Redis. Documented as a known limitation in §13.

---

## 8. Android — `MySubmissionsFragment`

### Navigation

Add a new entry "My Submissions" to the Me tab list, visible only when `accountState.value` is `Loaded` and `role == "moderator"` (role currently lives on the User doc; AccountRepository's `AccountState.Loaded` will need a `role: String` field if not already present — verify).

Tapping it opens `MySubmissionsFragment` with the navigation graph entry.

### `MySubmissionsFragment` layout

- Toolbar with title + back arrow
- SwipeRefreshLayout wrapping a RecyclerView
- FloatingActionButton (+) at bottom-end → opens `SubmitContentBottomSheet`
- Empty state: "No submissions yet. Tap + to suggest content."

### Row layout

```
[Status badge]  [Title]                        [submittedAt timeAgo]
                [Type · Category]
                [If REQUEST_CHANGES: italic review note]
```

Status badge colours:
- PENDING: amber
- APPROVED: green
- REJECTED: red
- REQUEST_CHANGES: blue

### `SubmitContentBottomSheet`

Two-tab layout (TabLayout + ViewPager2):

**Tab 1: Paste URL**
- Text input for YouTube URL
- Validate as channel / playlist / video (use existing URL parser if available, else NewPipeService.detectType)
- Category dropdown
- Submit button

**Tab 2: Search**
- Search input + "Search" button → results list
- Each result: thumbnail, title, type chip
- Tap to select → highlights and unlocks Category dropdown + Submit

Submit → `POST /api/admin/registry/{type}` → on success, refresh `MySubmissionsFragment` list and dismiss sheet. On 429 → toast "You've hit the daily submission limit. Try again in X hours."

### Repository

```kotlin
@Singleton
class MySubmissionsRepository @Inject constructor(
    private val api: ApprovalApi,
    private val accountRepository: AccountRepository,
) {
    suspend fun fetchMySubmissions(status: String? = null): Result<List<PendingApprovalDto>> = …
    suspend fun submitChannel(youtubeId: String, categoryId: String): Result<Unit> = …
    suspend fun submitPlaylist(youtubeId: String, categoryId: String): Result<Unit> = …
    suspend fun submitVideo(youtubeId: String, categoryId: String): Result<Unit> = …
}
```

Maps 429 → `RateLimitError(retryAfterSeconds: Long)` so the bottom sheet shows the correct message.

---

## 9. Testing

### Backend — unit
- `ApprovalServiceTest`: `requestChanges` flips status + sets reviewNotes; rejects non-PENDING with 409.
- `SubmissionRateLimiterTest`: under-limit allows, at-limit blocks, sliding window correctness with Clock fake.

### Backend — integration (Firestore emulator)
- `RequestChangesIT`: full state machine — submit (PENDING) → request-changes (REQUEST_CHANGES + note) → resubmit (PENDING, note cleared) → approve (APPROVED).
- `MySubmissionsEnrichmentIT`: verify DTO includes submittedByDisplayName + submittedByEmail.
- `RateLimitIT`: 51st submission within 24h returns 429 with retryAfterSeconds.

### Android — unit
- `MySubmissionsRepositoryTest`: HTTP 429 → `RateLimitError`; happy path returns mapped list.
- `MySubmissionsViewModelTest`: state machine for loading / loaded / empty / error.

---

## 10. Observability

- Logs: `account.approvals.request_changes uid=… approvalId=… type=… result=2xx|4xx`.
- Metric: `account.approvals.request_changes.count{type}`.
- Metric: `account.submissions.rate_limited.count{uid}` — emit on every 429 for visibility.

---

## 11. Rollout

1. Backend first (REQUEST_CHANGES status + rate limiter + enrichment + tests).
2. Android second (MySubmissions UI + submit flow).
3. No feature flag needed (≤20 users; failure mode is a rollback redeploy).

---

## 12. Risks

| risk | mitigation |
|---|---|
| In-memory rate limiter doesn't survive process restart | Acceptable trade-off for ≤20 users; restart effectively resets the window — moderate risk of brief burst-after-restart abuse. Migrate to Redis if it becomes a real issue. |
| `getById` on FavoriteVideoDao (Plan D toggleFavorite path) regression risk — unrelated to Plan E | Plan E does not touch FavoriteVideoDao; pre-existing concern noted in Plan D review. |
| Moderator role lookup adds N reads per pending-list page | UserRepository.loadByUid is `@Cacheable`; per-request dedup via a local Map within `enrichDtoBatch`. |
| Re-submit of an existing REQUEST_CHANGES item — RegistryController behaviour unknown | T1 of the implementation plan investigates and either reuses existing upsert semantics or adds them. |

---

## 13. Open questions

- **Re-submit semantics:** verify whether existing `POST /api/admin/registry/{type}` is upsert-on-existing-row or always-insert. Fix at T1 if needed.
- **Multi-instance rate limit:** in-memory map is single-process. If we deploy to multiple backend nodes later (Plan A spec didn't call this out, and the scheduler is currently single-instance), revisit.
- **Moderator-role gating on Me tab:** the existing `AccountState.Loaded` may or may not expose the user's role. Verify and add a `role` field if absent — this is small but is a Plan A/B/C carry-over that should land cleanly with Plan E.
