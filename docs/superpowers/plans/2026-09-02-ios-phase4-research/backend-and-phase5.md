# Phase 4 research — backend contract + Phase 5 (§12) status

Paths relative to `backend/src/main/java/com/albunyaan/tube/` unless stated. Read at `cac46c11`.
**Nothing was run** (no `./gradlew`, no network); every claim is from source.

---

## 1. The single biggest finding: the OpenAPI spec has NO `/account` paths

`docs/architecture/api-specification.yaml` is 4 837 lines and declares **no path beginning
`/account`** (grep for `/account` over the file: zero hits). The declared paths are `/v1/*`,
`/admin/*`, `/downloads/*` — enumerate them with `grep -n '^  /' docs/architecture/api-specification.yaml`.

Consequences for Phase 4:
- `ios/Packages/FitrahAPI` cannot generate a single account/sync/import operation. The generator
  config already narrows to 8 paths (`ios/Packages/FitrahAPI/Sources/FitrahAPI/openapi-generator-config.yaml:6-15`:
  `/v1/content`, `/v1/categories`, `/v1/home`, `/v1/channels/{channelId}`, `/v1/playlists/{playlistId}`,
  `/v1/videos/{videoId}`, `/v1/search`, `/v1/reports`), so even widening the filter buys nothing.
- Every Phase 4 endpoint is therefore either (a) **added to the spec first**, then regenerated, or
  (b) **hand-written over `HTTPTransport`** in the shipped `PublicHeaders`/`OfflineGateClient` style.
  This is fork F1.
- `X-Device-Id` appears **nowhere** in the spec either (zero hits) — `DeviceIdMiddleware` adds it
  unconditionally anyway (`DeviceIdMiddleware.swift:21`).

Partial exceptions worth knowing: `/admin/approvals/my-submissions` **is** specced
(`api-specification.yaml:1916-1966`) and so are `POST /admin/registry/{channels|playlists|videos}`
(`:943`, `:1061`, `:1238`) and `GET /admin/youtube/search` (`:1425`). But
`PATCH …/{id}/submitter-note` and `DELETE …/{id}/submission` are **not** (grep: zero hits), and the
specced `my-submissions` `status` enum lists only `[PENDING, APPROVED, REJECTED]` (`:1929-1933`)
while `PendingApprovalDto` carries a fourth value `REQUEST_CHANGES`
(`android/.../data/approvals/dto/ApprovalDtos.kt:21`).

---

## 2. Endpoints Phase 4 needs — every one exists server-side

### 2.1 `AccountController` (`controller/AccountController.java`, `@RequestMapping("/api/account")`)

| Method | Path | Notes |
|---|---|---|
| POST | `/profile` (`:69-87`) | Requires `principal.isEmailVerified()`, else **403 `{code: EMAIL_NOT_VERIFIED}`** (`:78-83`) — a server-side gate the client's EmailVerification screen only mirrors (`:75-77`). Returns `AccountMeResponse`. |
| PUT | `/profile` (`:90-97`) | Partial update for an ACTIVE user. |
| POST | `/send-verification-email` (`:99-133`) | 200 if already verified (`:103-105`); 400 `{code: NO_EMAIL}`; **429 `{code: RATE_LIMITED}`** inside a per-uid 60 s window (`VERIFICATION_COOLDOWN_MS = 60_000`, `:48`, `:112-116`); 500 `{code: VERIFICATION_EMAIL_FAILED}`. |
| GET | `/me` (`:135-207`) | **Lazy-creates** the Firestore doc atomically on first call, seeding `role` from the verified ID token's custom claim through `Role.fromString` (`:164-167`, `:175-197`) and `status = PENDING_PROFILE` (`:195`). |
| **DELETE** | **`/me`** (`:220-226`) | Self-serve permanent deletion → `authService.deleteAccountPermanently(uid)`; **204** on first call and on idempotent retry; `LastAdminException` → **409**. |

Exception handlers on the same controller: `AGE_INELIGIBLE` → **422** (`:266-271`);
`AGE_INELIGIBLE_ABORTED` → 500 (`:273-280`); `PROFILE_ALREADY_COMPLETED` → **409** (`:282-287`);
`USER_NOT_FOUND` → 404; `VALIDATION` → 400 with `"<field>: <reason>"` (`:296-301`) — the exact shape
`AccountUpdateRepository.splitFieldMessage` parses; `LAZY_CREATE_TIMEOUT` → 504,
`LAZY_CREATE_FAILED` → 500 (`:250-262`).

`dto/AccountMeResponse.java` ← the Android DTO `AccountMeResponseDto`
(`android/.../data/account/AccountMeResponseDto.kt:6-15`): `{uid, email?, displayName?,
dateOfBirth?, phoneNumber?, status, role?, profileCompletedAt?}`.

### 2.2 `SyncController` (`controller/SyncController.java`, `@RequestMapping("/api/account")`)

- `GET /sync` (`:41-64`) — params `subs`, `playlists`, `favorites` (long, default 0) and
  `subs_id`, `playlists_id`, `favorites_id` (nullable). Cursor ids validated (`:60-63` →
  `isValidCursorId`, `:68-82`): ≤ **1 500 UTF-8 bytes** (`MAX_CURSOR_ID_BYTES`, `:66`), no `/`, no
  control chars, and the Firestore-reserved `"."`, `".."` and `__…__` forms rejected; invalid → 400.
- `PUT`/`DELETE /subscriptions/{id}` (`:86-105`), `/playlists/{id}` (`:107-126`),
  `/favorites/{id}` (`:128-147`). Both verbs answer the corresponding `*SyncDto`.
- DTOs: `dto/sync/{SyncResponseDto, SyncPageDto, SyncRowDto, SubscriptionSyncDto, PlaylistSyncDto,
  FavoriteSyncDto, PutSubscriptionRequest, PutPlaylistRequest, PutFavoriteRequest, SyncCursors}` —
  the Kotlin mirrors are byte-compatible (`android-sync-import.md` §A6).
- Service: `service/sync/SyncService.java`, with `service/sync/ArchiveProjector.java` behind the
  "archive echo" (`deleted: true` on a PUT response) the client applies as a tombstone.

### 2.3 `ImportController` (`controller/ImportController.java`, `@RequestMapping("/api/account/import")`)

- `POST /resolve` (`:78-129`). Request `ImportResolveRequest(items)` with
  `@NotEmpty @Size(max = 200)` (`dto/importflow/ImportResolveRequest.java:8-9`) — exactly Android's
  `BATCH_SIZE`.
- **Rate limit before any work** (`:83-98`): `SubmissionRateLimiter.tryAcquireImport(uid, itemCount)`
  — sliding 24 h window (`WINDOW`, `:32`), **1 000 items/user/day**
  (`IMPORT_DAILY_ITEM_BUDGET = 1000`, `:31`), all-or-nothing (`:138-150`: over budget consumes
  nothing and returns `retryAfterSec`), keyed `"import:" + uid` so it never shares the moderator
  submission bucket (`:119-123`). Over budget → `ImportRateLimitedException` → **429**.
- Per item (`:100-128`): existing registry doc → `UserImportSubmissionService.dispositionForExisting(
  status, visibility, personalGrants, uid)` — **grant-aware**: a PERSONAL item is APPROVED only for a
  grantee, otherwise PENDING with no DTO (`:107-112`). Absent → `submissions.submit(item, uid)`.
  A per-item exception yields `ERROR` for that item alone, deliberately and with a test
  (`:120-127`).
- `service/ImportGraduationService.java` — when an admin later approves/rejects the submitted id,
  `onApproved`/`onApprovedPersonal`/`onRejected` flip every user's AWAITING per-user row
  (`:70`, `:81`, `:120`). This is why an imported item's `approvalStatus` changes underneath the
  client and why the Me tab's Pending tab is a live count, not a snapshot.

### 2.4 Approvals / registry / YouTube search (role-gated)

`controller/…` for `GET /api/admin/approvals/my-submissions`,
`POST /api/admin/registry/{channels|playlists|videos}`,
`PATCH /api/admin/registry/{type}/{id}/submitter-note`,
`DELETE /api/admin/registry/{type}/{id}/submission`,
`GET /api/admin/youtube/search` — all exercised today by the Android client
(`ApprovalApi.kt:9-50`, `YouTubeSearchApi.kt:10-15`). No change needed for Phase 4.

`controller/UserController.java` is `@RequestMapping("/api/admin/users")` — pure admin-dashboard
surface (list/get/create/role/status/delete/recover/block/unblock/reset-password/revoke-sessions/
bulk-*, `:36-373`). **Nothing in Phase 4 calls it**; it is in the brief only because the mobile
`/api/account/*` endpoints are frequently confused with it.

### 2.5 Auth filter and status enforcement

`security/FirebaseAuthFilter.java`:
- `shouldNotFilter` (`:265-272`): skips `/api/public/`, **`/api/v1/`**, and `/api/auth/login`. So
  every `/api/account/*` and `/api/admin/*` request is filtered.
- `checkRevoked = requestURI.startsWith("/api/admin/") || startsWith("/api/account/")` (`:110-111`)
  — token revocation is consulted on exactly those two namespaces; `/api/v1/*` keeps the cheap path
  (`:100-109` records the COPPA rationale and the cost trade).
- Status read is **uncached** (`findByUidUncached`, `:132`) because the 60 s Caffeine cache let a
  user blocked on node A keep authenticating on node B (`:117-131`).
- `u.isDeleted()` → **403 `ACCOUNT_DELETED`** (`:135-147`); `u.isBlocked()` → **403
  `ACCOUNT_BLOCKED`** (`:148-159`), never echoing `blockReason` (`:149-155`). No Firestore doc →
  allow (first-time user, `:160`).
- Firestore timeout → 503 `SERVICE_UNAVAILABLE` (`:161-175`). Unexpected `RuntimeException`:
  **fail-closed** for every write method (POST/PUT/PATCH/DELETE) and for `/api/admin/`,
  `/api/account/`, `/api/v1/me`, `/api/v1/me/`; **fail-open** elsewhere (`:176-208`).

There is **no separate `X-Device-Id` filter class**. The header is read at the controllers that
need it — `IndexController`, `ContentReportController`, `WatchPageController`,
`LegalPagesController` — and `security/SecurityConfig.java` permits those routes anonymously
(`:87-103`), except `POST /api/share-metadata/**`, which is `.authenticated()` (`:97`) precisely
because a rotating `X-Device-Id` was a preview-spoofing primitive (`:90-96`).

---

## 3. Phase 5 (§12) status — row by row

### Row 1 — `WellKnownController` (AASA + assetlinks): **NOT DONE**

Grep for `well-known|WellKnown|assetlinks|apple-app-site-association` over `backend/src` and
`docs/architecture/api-specification.yaml`: **zero hits**. No controller, no
`app.ios.team-id`/`app.ios.bundle-id`/`app.android.sha256-fingerprints` keys in `application.yml`,
no `SecurityConfig` permit rule, no spec entry.

It is also **still blocked twice over**: the AASA content needs the Apple Team ID (D6/§18 item 1),
and Cloudflare returns 403 for `/.well-known/*` (spec §12 closing line — outside the repo, the
user's task). CF-B3-6 records a related, independent backend gap: nothing is served at
`https://app.fitrahtube.com/embed`, the origin the embed rung declares.

Phase 4 does **not** need this — Universal Links are a Phase 2 carry-forward (RULING 65, already
USER-BLOCKED). Sign in with Apple does not require AASA (it needs the capability + Team ID, and a
Service ID only for the *web* flow).

### Row 2 — `DELETE /api/account`: **DONE, at a different path**

Implemented as **`DELETE /api/account/me`** (`AccountController.java:220-226`), not
`DELETE /api/account`. It is materially richer than §12 described:
- `AuthService.deleteAccountPermanently(uid)` (`:849-965`) runs one lifecycle transaction:
  not-found → `UserNotFoundException`; already a tombstone → `RESUME_SWEEP` when it was a
  self-delete whose purge never finished, else `ALREADY_DONE` (`:858-870`); **last-active-admin
  guard** with a sentinel read/write → `LastAdminException` → **409** (`:872-886`); then a full
  anonymise (email, displayName, dateOfBirth, phoneNumber, lastLoginAt, profileCompletedAt,
  createdBy, recoveredAt/By, blockedAt/By/Reason) before `recordSoftDelete(uid, SELF_DELETE_REASON)`
  and `purgeCompleted = false` (`:888-919`).
- `destroyAuthRecord` (`:986-994`) revokes refresh tokens and hard-deletes the Firebase Auth record,
  treating USER_NOT_FOUND as success so a resumed sweep can finish.
- Answers **204** on both first call and retry.

§12's "reuse the revoke + disable + soft-delete path of `AccountProfileService` (`:112-183`)" is a
**different** path — that is `rejectUnderAge` (`service/AccountProfileService.java:127-184`), the
under-13 gate: `revokeRefreshTokens` → `updateUser(setDisabled(true))` → `recordSoftDelete(uid,
"age-ineligible")`, with `AgeIneligibleAbortedException` on any step and audit rows for the
half-states (`:154-170`, `:171-180`). Self-deletion reuses none of it. Known backend defect
CF-G-8: the `RESUME_SWEEP` branch is unreachable because `FirebaseAuthFilter.java:135-147` 403s a
tombstoned user before the controller runs.

§12's "subsequent calls 403 `ACCOUNT_DELETED`" is satisfied — not by the controller, but by that
same filter branch.

**Action for the plan: none on the backend. Point the iOS client at `DELETE /api/account/me` and
handle 204 / 409 / 403.**

### Row 3 — `VideoValidationScheduler` `status`/`madeForKids`/`embeddable`/`ytRating`: **NOT DONE, and the prescription doesn't fit the code**

- Grep for `madeForKids|embeddable|ytRating|contentRating` over `backend/src`, `frontend/src` and
  `docs/architecture/api-specification.yaml`: **zero hits** (the only repo hits are iOS probe notes
  and a vendored OpenAPIKit test fixture).
- `dto/ContentItemDto.java:13-40` has no such fields.
- **The mechanism §12 names does not exist.** `scheduler/VideoValidationScheduler.java:233,327`
  calls `ContentValidationService.validateVideos`, which reaches
  `ChannelOrchestrator.batchValidateVideosDtoWithDetails` (`ContentValidationService.java:659`),
  which is `BatchValidationResult<StreamInfo>` → `StreamDetailsDto`
  (`ChannelOrchestrator.java:1301-1313`) — i.e. **NewPipeExtractor**, not the YouTube Data API.
  There is no `part=` parameter anywhere in `service/` (grep for `part`: only unrelated string
  splitting in `TagEnrichmentService.java:376-377` and prose in `AuthService.java:777`). "Add
  `status` to `part`" has no site.
- NewPipe 0.26.5's `StreamInfo` surface (`docs/library-guides/newpipe-extractor.md`) exposes no
  `madeForKids`, no `embeddable`, no `contentRating.ytRating`. Getting these needs either a YouTube
  Data API key (which the backend does not use for validation) or an InnerTube `player`-response
  field the backend does not fetch.

This is a **real blocker for a real feature**: spec §9 says "when the catalog item carries
`madeForKids`/`embeddable` (§12) rungs known to fail are skipped" — the resolver's rung-skipping
optimisation. It is not on Phase 4's critical path, and the Phase 2 probe already measured that the
`androidItag18` fallback showed **zero gap across all 18 known kids/UNPLAYABLE catalog ids**
(`ios/Packages/InnerTubeKit/probes/probe-2026-08-23.md:92`), so the optimisation buys little today.
Recommend descoping it out of Phase 4 and re-opening it as its own backend ticket once the data
source is decided (fork F8).

### Row 4 — Swift codegen step: **DONE**

`scripts/generate-openapi-dtos.sh:30-37` guards on `swift --version` (not `command -v swift`, which
matches the `xcrun` shim) and invokes `ios/scripts/generate-swift-dtos.sh`, printing a skip warning
when Swift is absent. `ios/scripts/generate-swift-dtos.sh:11-14` copies the spec into the package
(the SwiftPM plugin sandbox cannot read outside it), runs
`swift package plugin --allow-writing-to-package-directory generate-code-from-openapi --target FitrahAPI`,
and removes the copy on exit. Caveat: the generator's `filter.paths` list
(`openapi-generator-config.yaml:6-15`) is the real scope, and it contains no account paths (§1).

### Row 5 — `share_app_promo` drops "ad-free": **DONE on iOS, NOT DONE on Android**

- iOS: `ios/scripts/convert-strings.py:18` puts the key in `REFUSE` and `:78-82` re-authors all
  three locales without the phrase ("Get FitrahTube for curated Islamic content!"; the nl verb is
  "Haal", not "download"). Pinned by `ShareLinksTests.swift:46-50` and
  `LocalizationTests.swift:158-161`, and a repo-wide ban is pinned at
  `LocalizationTests.swift:69,136`.
- Android: `android/app/src/main/res/values/strings.xml:285` still reads
  `"Get FitrahTube for ad-free Islamic content!"`.
- Backend: `controller/LegalPagesController.java:176` and `:384` describe FitrahTube as "ad-free" in
  the privacy-policy and delete-account HTML. §12 says "backend string if any" — these two are it.
  Judgement: the legal pages are prose about the product, not a share-sheet claim; the 5.2.3 concern
  was the promo copy. Flag, do not silently change.

**Row 5 residue is Android + backend prose, on a machine with no Android SDK** — same class as
CF-G-4..7 (deferred to an Android-capable session).

---

## 4. Summary table for the plan

| §12 row | Status | Where |
|---|---|---|
| `WellKnownController` (AASA/assetlinks) | **NOT DONE**, and doubly USER-BLOCKED (Team ID + Cloudflare) | no file exists |
| `DELETE /api/account` | **DONE** as `DELETE /api/account/me` | `AccountController.java:220-226`, `AuthService.java:849-965` |
| `madeForKids`/`embeddable`/`ytRating` | **NOT DONE**; prescription targets a Data API call the backend does not make | `ContentItemDto.java:13-40`, `ChannelOrchestrator.java:1301` |
| Swift codegen step | **DONE** | `scripts/generate-openapi-dtos.sh:30-37`, `ios/scripts/generate-swift-dtos.sh` |
| `share_app_promo` "ad-free" | **DONE on iOS**, open on Android `strings.xml:285` (+ two backend legal-page prose hits) | `convert-strings.py:18,78-82` |

**Unlisted-but-required backend work Phase 4 surfaces:** the OpenAPI spec must gain the
`/account/*` paths (or the plan accepts hand-written clients — fork F1); `PATCH …/submitter-note`
and `DELETE …/submission` are also unspecced; the `my-submissions` `status` enum is missing
`REQUEST_CHANGES`; and BACKEND item 1 from Plan C (Firestore `Timestamp` objects where the spec says
`date-time`) still forces `PublicHeaders` to exist and will bite any generated account DTO carrying
a timestamp.
