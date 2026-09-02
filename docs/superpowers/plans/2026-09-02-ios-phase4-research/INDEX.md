# Phase 4 (Accounts) iOS research corpus — Index

Built 2026-09-02 against `feature/ios-app` at **`cac46c11`**, read-only. iOS files were read via
`git show cac46c11:<path>` (the working tree is mid-edit by another agent); Android and backend
files were read from the working tree, which is clean for those paths. **Nothing was built, run, or
fetched** — no `xcodebuild`, no `gradlew`, no network. Every unverifiable claim is marked.

Baseline: spec `docs/superpowers/specs/2026-08-23-ios-app-design.md` §13 (`:265-278`), §12
(`:251-264`), §15 rows 4/5/6, §5, §6, §8, §16, §17, §18. Predecessors:
`docs/superpowers/plans/2026-08-23-ios-phase2-research/{INDEX,AUDIT,RULINGS,phase2-inventory,PHASE2-CARRYFORWARDS}.md`
and `docs/superpowers/plans/2026-09-01-ios-phase3-offline-cast.md`.

---

## 1. The briefs

### `android-accounts.md`
The behavioural source of truth for spec §13 bullets 1–4 and 7–8: auth state types and the 13
`AuthErrorCode`s, `AuthErrorMapper`'s 10-branch table, both OkHttp interceptors (host scoping,
3 s/5 s token budgets, single 401 retry with a cross-account guard; the 403 envelope allowlist),
`AccountRepositoryImpl`'s retry budget and 422 parsing, `LocalAccountDataWiper`, sign-in (6-char
minimum, pre-network shape gates, the credential-job cancellation caveat), email verification
(auto-send-once latch, 60 s cooldown, backend-then-Firebase send), profile bootstrap (name ≤40,
local under-13 gate, mandatory libphonenumber phone, ≥8 password, two-phase commit), the Me tab
(chips, week feed, Content/Pending tabs, kebab with a **both-items** role gate, one-time import
offer, 20-tile favorites row), the `channel_feed_refresh_state` engine (TTL, two backoff ladders,
timeout-is-not-an-error), profile edit sheets and account deletion, submissions/suggest, and the
30-channel cap.

### `android-sync-import.md`
`SyncManager` in full: the single mutex and why two failed, `bind`'s four-branch matrix and the
all-or-nothing account-switch transaction, `runMerge`'s four steps, `pullAll`'s compound cursor,
dirty-wins conflict rule, tombstone monotonicity, the **stalled-cursor guard** (a production
incident: 3 req/s spin that pinned the splash screen), `pushDirty`'s five-outcome classifier
including success-with-null-body, jittered backoff, and the exact wire DTOs. Then the Room v11
columns the SwiftData models must mirror (and the three the iOS models are missing), the five
triggers, and the whole YouTube import flow: OAuth scope, the three paginators (`MAX_PAGES = 40`,
seen-token guard, **no API key**), Room dedupe, 200-item batching, 429-stops-the-loop, the
APPROVED/PENDING/REJECTED write matrix, `ImportUiState`'s transitions, and the Sharī'ah caution
gate.

### `backend-and-phase5.md`
Every endpoint Phase 4 needs, with status codes; `FirebaseAuthFilter`'s status enforcement and
fail-open/fail-closed split; the import rate limiter (200/request, 1 000/user/day). Then the §12
row-by-row Phase 5 verdict — and the headline finding: **the OpenAPI spec declares no `/account/*`
path at all**, so `FitrahAPI` can generate nothing for this phase.

### `ios-seams.md`
What Phase 4 plugs into: `AppContainer`'s 20 members and its `fake()`/`FixedStatusTransport`
pattern, the 13-line `SplashRouter` that says in its own doc that Phase 4 extends it,
`FitrahTubeApp`'s launch hooks and the 15-min foreground hook, `Route`'s 11 cases (**eight more
needed**) and the exhaustive `MainShellView.destination(for:)` switch, `MeGuestView`'s disabled
sign-in button, `FitrahAPI`'s single middleware and the hand-written-client house style, the
SwiftData V1→V4 migration ladder and the **incomplete** `SubscribedChannel`/`SavedPlaylist` models,
`SettingsStore.importOfferShown`, the **total absence of anything Firebase**, the privacy manifest
that will need new entries, the design-system inventory (no `MeChip`), the 780-key string catalog
(~150 account strings **already ported**), and the test/screenshot rig conventions.

### `dependencies-and-blockers.md`
Firebase Auth + GoogleSignIn-iOS SPM (URLs stated, versions deliberately unpinned — resolve once at
plan time), Sign in with Apple's entitlement, and the three Google Sign-In configuration pieces.
Then the precise line between what compiles and tests **today against fakes** and what is
USER-BLOCKED on one thing: the Apple Team ID + Firebase iOS registration (spec §18 item 1 / D6).
Includes the six checks to run the day the plist arrives.

### `contradictions-and-forks.md`
Thirteen contradictions between spec §13/§12, the Android code, the backend and the shipped iOS
build — including the phone-optional/required split, the wrong `DELETE` path, a §12 row prescribing
a mechanism that does not exist, the role gate reading narrower than the code, the guest-Me
asymmetry, the missing revoke source, the owner's Favorites/History ruling, and the two Phase-0
seams (Firebase plist, `/account/*` in the spec) that were promised and never shipped. Then
**twelve forks** in default/override/cost form.

---

## 2. Readiness verdict

**Phase 4 is ready to plan, and every one of its gates ("tests against fakes") is reachable today.**
The backend is done and richer than §12 described — `/api/account/{me,profile,send-verification-email,
sync,subscriptions,playlists,favorites,import/resolve}` plus `DELETE /api/account/me` all ship, with
the status/blocked/deleted enforcement the client depends on. The iOS side has the seams (a
composition root with a proven fake pattern, an exhaustive route switch, a three-stage SwiftData
migration ladder, four hand-written HTTP clients to copy, ~150 of the ~160 account strings already
in the catalog, a 300 s test gate and a screenshot rig). Three things are genuinely missing and must
be planned, not assumed: **(1)** nothing Firebase exists at all — no SPM package, no plist, no
`copy-firebase-plist.sh`, no build phase, no entitlement — so Phase 4 pays Phase 0's debt exactly as
Phase 3 paid the Cast-script debt; **(2)** the OpenAPI spec has **zero** `/account/*` paths, so
every Phase 4 endpoint is hand-written or the spec is extended first; **(3)** the shipped
`SubscribedChannel`/`SavedPlaylist` SwiftData models are missing the URL and import columns the sync
wire requires, so a `FavoritesSchemaV5` migration is on the critical path. The standing
USER-BLOCKED wall is unchanged and bounded: end-to-end auth needs the Team ID and the Firebase
registration; nothing else does.

---

## 3. Recommended task split for the plan

Sized against the Phase 3 plan's granularity (8 tasks, one gate each). **This assumes fork F2's
default — a 4a/4b split.** If the owner overrides to one phase, concatenate.

### Phase 4a — auth, Me shell, profile

| # | Task | Gate |
|---|---|---|
| **1** | **Phase-0 debt: the Firebase seam.** `project.yml` gains the two SPM packages; `ios/scripts/copy-firebase-plist.sh` + a committed placeholder `GoogleService-Info.plist`; `FirebaseApp.configure()` behind an options-file check in `FitrahTubeApp.init`; the reversed-client-id URL type; `GIDSignIn.handle(url)` ahead of the deep-link parser; `FitrahTube.entitlements` with `com.apple.developer.applesignin`; `test.sh` pre-stage so the first SPM resolve happens outside the 300 s watchdog. **Day-1 risk task, exactly like Phase 3's Task 1.** | `test.sh` green with the placeholder plist; a Release build compiles |
| **2** | **`AuthClient` seam + error mapping.** One protocol (`signIn/signUp/sendPasswordReset/sendVerificationEmail/reload/reauthenticate/updatePassword/verifyBeforeUpdateEmail/delete/signOut` + an `AuthState` stream), a `FirebaseAuthClient` that is the ONLY file naming Firebase types, a `FakeAuthClient` in `Support/TestDoubles.swift`, and the `AuthErrorCode` table rewritten against `FirebaseAuth.AuthErrorCode` (C12). Google + Apple providers behind their own protocols, each hidden when its prerequisite is absent (F11). | `AuthErrorCode` mapping tests; container test that `fake()` never builds a Firebase client |
| **3** | **`FitrahAPI` auth middleware + `AccountClient`.** Second `ClientMiddleware` (Bearer only when host matches, single 401 retry on `WWW-Authenticate: Bearer`) plus a shared authorized transport for the hand-written clients (F12); `AccountClient` over `/api/account/{me,profile,send-verification-email}` and `DELETE /me`, with the `{code}` envelope → `AccountStatusEvent` mapping (403 `ACCOUNT_BLOCKED`/`ACCOUNT_DELETED`). | `RecordingTransport` tests: host scoping, one-retry-only, the three error shapes, 403 envelope |
| **4** | **Routes + `SplashRouter` matrix + account-status alert.** Eight new `Route` cases; `MainShellView.destination(for:)` arms; `SplashRouter.destination(onboardingCompleted:signedIn:status:)` per spec §6 (guest, never forced); terminal alert on Blocked/Deleted with a guest reset; wire the disabled button at `MeGuestView.swift:56-61`. | `SplashRouterTests` matrix, `MainShellRoutingTests` arms |
| **5** | **Sign-in / verification / bootstrap / age-ineligible screens.** Four screens + ViewModels against `FakeAuthClient`; the pure validators ported verbatim (`isEmailShape`, `firstValidationError`, `isUnderMinimumAge`, the 60 s cooldown, the `profileSaved` latch); phone per fork F5. | ViewModel tests; screenshots on 3 sims × en/ar |
| **6** | **Me tab (signed-in) + user scoping.** `UserScoped` protocol so the container can set `currentUserId` on all three stores; the Me shell — chips (merged, add-time descending), 20-tile favorites row, Content/Pending tabs hidden at zero, kebab with the both-items role gate, one-time import offer reading `SettingsStore.importOfferShown`; the Atom/week feed per fork F4; a link to Saved; no History rows (F10). | `shouldShowFeedEmptyState`/`spanFor`/`WeekBucket` tests; feed refresh-gate tests with injected clocks; screenshots |
| **7** | **Profile + edit sheets + account deletion.** Changed-fields-only PUT, the 422/429/400 mapping, the three sheets (re-auth → `verifyBeforeUpdateEmail` / `updatePassword` / phone), delete → `DELETE /api/account/me` → 204/409/403 → Firebase `delete()` → wipe → guest. **Do not port CF-G-5/6/4** (C13). | ViewModel tests; a wipe test asserting favorites + subscriptions + playlists + search history + offline store are all cleared |

### Phase 4b — sync, submissions, suggest, import

| # | Task | Gate |
|---|---|---|
| **8** | **Schema V5.** Add `channelUrl`/`playlistUrl`/`uploaderName`/`approvalStatus`/`source`/`importedAt` to the two incomplete models; add `SyncState` and `AccountBinding` `@Model`s (fork F3); one `.lightweight` stage. | migration test on a V4 store |
| **9** | **`SyncManager` port — the pure half first.** `bind` matrix, merge order, the pull page-decision (advance / stall / exhausted), the push classifier, `SyncBackoff` with jitter, and the `isRemoved ↔ "deleted"` codec, all as `nonisolated` value types testable off the main actor. Then the thin actor that owns the mutex and the DAO writes. | the §16-named "`SyncManager` merge matrix" tests, plus a stalled-cursor test and a null-body-push test |
| **10** | **`SyncClient` + triggers.** `GET /api/account/sync` with the six params and the id validation, the six PUT/DELETE routes, archive-echo handling; triggers = sign-in `bind`, foreground (into the existing `refreshRemoteConfigIfDue` shape), push-on-change from the three stores, `NWPathMonitor` restore, sign-out `unbind`. | transport tests; a trigger test per source |
| **11** | **Submissions + Suggest.** `ApprovalsClient` (note `data` as the array key and the Firestore `Timestamp` flattening), the list + overflow + submit/edit sheets, the suggest search with 300 ms debounce, client-side type filtering and URL parsing, and an `ErrorState` where Android has a TODO. | ViewModel tests incl. the URL-parse precedence table; screenshots |
| **12** | **Import.** `YouTubeAuthorizer` protocol + `GIDSignIn.addScopes` impl + fake; the three paginators; `ImportClient`; the dedupe/chunk/429 pipeline; the five-state UI with the caution gate; revoke per fork F9. | `ImportUiState` transition tests, dedupe/chunk/429 tests against fakes; screenshots |

**Cross-cutting, every task:** all strings through `convert-strings.py` `EXTRA_KEYS` (never
hand-edit the catalog); the "Download"/"ad-free" ban stays pinned; ≥44 pt targets, Dynamic Type,
RTL via leading/trailing, `Format` for every number; no new `.md` files; approved catalog ids only
in fixtures (`xc7keR2piUM`); one implementer on the iOS build slot at a time; `PrivacyInfo.xcprivacy`
updated once (Task 1 or 2, not last).

---

## 4. Open questions the plan cannot answer from the repo

1. **Team ID + Firebase iOS registration** (spec §18 item 1 / D6) — blocks every end-to-end item,
   Sign in with Apple's entitlement, and §12 row 1's AASA content.
2. ~~Is `CompleteProfileRequest.phoneNumber` nullable server-side?~~ **CLOSED during this pass** —
   `backend/.../dto/CompleteProfileRequest.java:21-23` is `@NotBlank` + `@Pattern("^\\+[1-9]\\d{7,14}$")`.
   Phone is required at bootstrap (spec §13's "optional" is wrong), and that regex is the one iOS
   should use verbatim. `UpdateProfileRequest.phoneNumber` (the edit sheet) *is* nullable = "no
   change", same pattern (`dto/UpdateProfileRequest.java:25-29`).
3. **Cloudflare `/.well-known/*` exemption** (spec §18 item 3) — outside the repo.
4. **Owner ruling on C7/F10**: does the Me tab show Recently watched / History at all?
5. **Whether `RemoteConfig.requiresUpdate`'s owner decision** (the standing bloat-audit item at the
   end of `PHASE2-CARRYFORWARDS.md`) is now settled — `FitrahTubeApp.swift:87-89,125-127` shows the
   gate *is* wired, so that carry-forward appears closed; confirm before repeating it.
