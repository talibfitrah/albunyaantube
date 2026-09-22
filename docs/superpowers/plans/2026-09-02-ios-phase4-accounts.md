# Accounts, Me, Sync & Import Implementation Plan (iOS Phase 4)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Close the spec §15 row 4 gate — "Accounts: auth, verification, bootstrap, Me, profile + deletion, sync, submissions, suggest, import; tests against fakes; end-to-end once Firebase plist exists". Phase 4 also pays **Phase 0's Firebase debt** (spec §15 row 0 promised the SPM packages, `copy-firebase-plist.sh` and the plist build phase; none exists), exactly as Phase 3 paid the Cast-script debt — and it is **Task 1**, the day-1 risk task, for the same reason Phase 3 put its `AVAssetDownloadTask` spike there. The phase ships as **one plan in two parts** with a review gate between them (ruling F2): **Part A (4a)**, Tasks 1–19 = the Firebase seam, auth, sign-in/verification/bootstrap, the `SplashRouter` matrix, account-status events, the signed-in Me shell over **local** stores, profile and account deletion. **Part B (4b)**, Tasks 20–31 = schema V5, the `SyncManager` port, submissions, suggest and YouTube import.

**Architecture:** No new package (spec D14 named none for this phase). Auth is a set of small protocols on `AppContainer` with **Firebase types confined to one file per concern** (`FirebaseBootstrap`, `FirebaseAuthClient`, `GoogleAuthProvider`, `AppleAuthProvider`, `GoogleYouTubeAuthorizer`) and a fake for each — `FakeAuthClient` in the **app** target under `#if DEBUG` (the `ParkedOfflineEngine`/`FixedStatusTransport` precedent at `AppContainer.swift:121-130,364-391`, because the screenshot rig needs it), the rest in `ios/FitrahTubeTests/Support/`. Backend access is **hand-written clients** in the `PublicHeaders`/`OfflineGateClient` style, because `docs/architecture/api-specification.yaml` declares **zero** `/account/*` paths and the generator filter lists only 8 public ones (ruling F1, amended by review #1 to **five** clients). One token source feeds both HTTP worlds: `AuthTokenProviding` + `BearerScope` + `BearerRetry` in `FitrahAPI`, consumed by an `AuthMiddleware` (the generated OpenAPI client) and an `AuthorizedTransport` (the hand-written ones) — one host rule, **one** retry state machine (ruling F12). Sync is a thin actor over pure `nonisolated` decision types; its cursor and rows commit in one `ModelContext` save via SwiftData `SyncState`/`AccountBinding` models (ruling F3). The Me feed reuses InnerTubeKit's shipped `AtomFeedFetcher` (ruling F4) — no deep paging, no new item cache.

**Tech Stack:** Swift 6, SwiftUI, `@Observable`, Swift Testing (`@Test`/`#expect`); app target `ios/FitrahTube`; two InnerTubeKit files edited (`AtomFeedFetcher.swift`, `BrowseClient.swift` — Task 14); `ios/Packages/FitrahAPI` gains one middleware and one shared retry. New SPM dependencies: **Firebase iOS SDK (`FirebaseAuth` product only)** and **GoogleSignIn-iOS** (spec D8 authorises exactly these). Gate: `KEEP_RESULTS=1 bash ios/scripts/test.sh` from the repo root (300 s wall, 60 s per test). Screenshots: `ios/scripts/screenshots.sh`.

**Spec:** `docs/superpowers/specs/2026-08-23-ios-app-design.md` — D6 (simulator-first, plist at `~/.config/albunyaan/`), D8 (Firebase Auth + Google Sign-In via SPM), D9 (`AppContainer`, fakes, no DI framework), D11 (guest mode, never a forced sign-in), §5 (DI + the isolation rule), §6 (`SplashRouter` matrix, the eight new routes, the sheet inventory), §8 (backend client: `X-Device-Id` always, Bearer only on the API host, single 401 retry on `WWW-Authenticate: Bearer`, the 403 `{code}` envelope, the three error shapes, `GET api/admin/youtube/search`), §12 (backend additions — status below), §13 (the whole phase), §14 (i18n/RTL/accessibility/iPad/privacy manifest), §15 row 4 (the gate), §16 (`SplashRouter` matrix, `SyncManager` merge matrix, ViewModels against `AppContainer.fake()`, 300 s / 60 s limits), §17 (the Firebase-unverifiable risk row), §18 item 1 (Team ID + Firebase registration).

**THE RULINGS (dispatcher, 2026-09-02, plus the 08:10 amendments), binding on every task** — `.superpowers/sdd/2026-09-02-ios-phase4-accounts/rulings.md`. Authority order: spec → rulings → research. Where the shipped backend makes the spec unimplementable, the code wins and the ruling says so. Rulings are **not re-opened** by any task. The three amendments are applied throughout: **F1 gains a fifth client** (`YouTubeSearchClient`, Task 26); **the Firebase seam is Task 1** and the copy-net task is Task 3; **every task is ≤ ~6 files / one subsystem** (plan review #1 I12).

**Behavioural source (Android, cited file:line, paths under `android/app/src/main/java/com/albunyaan/tube/`):** `auth/AuthState.kt:15-45` (state types, the 13 error codes), `auth/AuthErrorMapper.kt:14-34` (the 10-branch table), `auth/AccountStatus.kt:19-28` (wire values, unknown→BLOCKED), `auth/FirebaseAuthInterceptor.kt:77-87,117-192` (host scoping, 3 s/5 s budgets, single 401 retry, cross-account guard), `auth/AccountStatusInterceptor.kt:46,74-78,106-137` (403 only, four path prefixes, 1024-byte peek, signOut-then-emit), `auth/AccountRepositoryImpl.kt:111-173,234-259` (retry budget, 422 parsing, the `"code"\s*:\s*"X"` regex), `data/account/LocalAccountDataWiper.kt:29-64`, `ui/auth/SignInViewModel.kt:37-178` + `util/EmailShape.kt:9-15`, `ui/auth/EmailVerificationViewModel.kt:34-142`, `ui/bootstrap/ProfileBootstrapViewModel.kt:22-217`, `ui/me/MeFragment.kt:58-59,153,184,270-273,352-445,667-676` + `MeViewModel.kt:52-67,397-438,458-461`, `data/me/AtomChannelFeedFetcher.kt:56-89`, `data/me/MeFeedRepository.kt:141-208,778-905`, `data/me/WeekBucket.kt:44-90`, `ui/me/profile/*` (`ProfileViewModel.kt:44-133`, `DeleteAccountViewModel.kt:43-116`, the three edit sheets), `data/account/AccountUpdateRepository.kt:30-91`, `data/sync/SyncManager.kt:31-634` + `SyncBackoff.kt:18-35` + `dto/SyncDtos.kt:6-105`, `data/subscriptions/SubscriptionLimitGuard.kt:26-73`, `data/approvals/*`, `ui/me/suggest/SuggestContentViewModel.kt:39-189` + `data/search/YouTubeSearchApi.kt:10-15`, `data/youtube/{YouTubeAuthManager.kt:17-100,YouTubeImportApi.kt:13-59,YouTubeImportRemoteSource.kt:25-153}`, `data/importflow/YouTubeImportRepository.kt:49-297`, `ui/me/importflow/{ImportUiState.kt:14-115,ImportViewModel.kt:82-213,ImportFromYouTubeFragment.kt:54-217}`.

**Predecessors:** every plan under `docs/superpowers/plans/2026-08-*-ios-*` and `2026-09-01-ios-phase3-offline-cast.md`; carry-forward ledgers `docs/superpowers/plans/2026-08-23-ios-phase2-research/PHASE2-CARRYFORWARDS.md` and Phase 3's CF-D-* section. **Phase 3's adversarial fix batch is landing on this branch as Phase 4 starts** (`.superpowers/sdd/2026-09-01-ios-phase3-offline-cast/adversarial-fix-offline-brief.md`) — Task 3 depends on its item AC-P1-1 and says so.

---

## Contradictions in the inputs (flagged, resolved here, not silently)

1. **Spec §13 says the bootstrap phone is "optional"; the backend rejects an empty one.** `backend/src/main/java/com/albunyaan/tube/dto/CompleteProfileRequest.java:21-23` is `@NotBlank @Pattern("^\\+[1-9]\\d{7,14}$")`. **Ruling C1: phone is REQUIRED at bootstrap**, validated with that regex verbatim, entered in ONE free-text field with a fixed leading `+` and a `.phonePad` keyboard. **No country picker, no dial-code table** — the spec's "country hint" is the placeholder. Android's `PhoneFormat`/libphonenumber is not ported. `UpdateProfileRequest.phoneNumber` (the Profile edit sheet) *is* nullable = "no change" (`dto/UpdateProfileRequest.java:25-29`). Consequence stated so it is not filed as a bug: iOS renders a stored E.164 raw (`+31612345678`), where Android pretty-printed it.
2. **Spec §8/§12 say `DELETE /api/account`; the endpoint is `DELETE /api/account/me`** (`controller/AccountController.java:220-226`, `@DeleteMapping("/me")` under `@RequestMapping("/api/account")`). Ruling C2: the code wins. 204 on first call **and** on retry; `LastAdminException` → 409; a tombstoned user's next request 403s `ACCOUNT_DELETED` from `security/FirebaseAuthFilter.java:135-147`, not from the controller. §12's "reuse `AccountProfileService` (`:112-183`)" points at `rejectUnderAge`, a different path; self-deletion is `AuthService.deleteAccountPermanently` (`:849-965`).
3. **Spec §12 row 3 prescribes a mechanism the backend does not have.** Video validation runs through NewPipeExtractor (`ContentValidationService.java:659` → `ChannelOrchestrator.java:1301-1313` over `StreamInfo`); there is no `part=` anywhere in `backend/src/main/java/**/service/`, and NewPipe 0.26.5 exposes no `madeForKids`/`embeddable`/`ytRating`. Ruling C3: **DESCOPED**, recorded as CF-A-1. The Phase 2 probe measured a zero gap across all 18 known kids/UNPLAYABLE catalog ids (`ios/Packages/InnerTubeKit/probes/probe-2026-08-23.md:92`).
4. **Spec §13's role gate reads narrower than the code.** `MeFragment.kt:270-273` gates **both** `action_my_submissions` **and** `action_suggest_content` on `role ∈ {moderator, admin}` (case-insensitive). Ruling C4: a plain user's kebab is Profile / Import from YouTube / Sign out.
5. **Spec D6 says a placeholder `GoogleService-Info.plist` is committed; ruling C11 says the app must build, test and run WITHOUT one.** A committed placeholder makes `FirebaseApp.configure()` *succeed* with fake credentials and every later call fail obscurely. **The ruling wins: no placeholder is committed.** `FirebaseBootstrap.configureIfPossible()` returns false when the options file is absent, `FirebaseAuthClient` is never built, the container serves a signed-out-forever `UnavailableAuthClient`, and every sign-in affordance is hidden by capability check (Tasks 2, 4, 5). The plist stays git-ignored and USER-BLOCKED.
6. **Spec §4 puts `copy-firebase-plist.sh` in a build phase; `project.yml:30` sets `ENABLE_USER_SCRIPT_SANDBOXING: YES`.** A sandboxed script phase cannot read `$HOME/.config/albunyaan/`, and declaring a non-existent input file is a hard Xcode error — which is exactly why the obvious "declare the input to escape the sandbox" workaround is unavailable. **Decision (Task 1): the script is a pre-stage, not a build phase** — it copies the plist into the git-ignored `ios/FitrahTube/Resources/GoogleService-Info.plist`, which the existing `sources: - path: FitrahTube` glob (`project.yml:43-46`) already bundles. `test.sh` runs it as stage 0, exactly as it heals `Vendor/GoogleCast.xcframework` (`test.sh:75`). No `project.yml` script phase, no sandbox exemption. **Cost, stated: a developer who opens Xcode directly without running `test.sh` never gets the copy** — the remedy is running the script by hand, and Task 1 says so in the script's own usage line.
7. **`AtomFeedFetcher` returns no upload date.** `VideoItem` (`ios/Packages/InnerTubeKit/Sources/InnerTubeKit/BrowseClient.swift:29-51`) carries `publishedText: String?`, which `AtomFeedFetcher.humanizePublished` (`:114-120`) has already reduced to "7 days ago" — locale-dependent relative prose. Week bucketing needs a real `Date`. Task 14 adds `public var publishedAt: Date?` to `VideoItem` and a `cached(_:)` reader to `AtomFeedFetcher`, and — the load-bearing half — adds `publishedAt` to `AtomFeedFetcher.CachedItem` (`:72-88`), because a 304 returns `cached?.items.map(\.videoItem)` (`:50`) and would otherwise silently empty the Me feed.
8. **`AtomFeedFetcher.maxItems = 15`, Android's `MAX_ITEMS = 30`.** Accepted under ruling F4 (Atom-only, no deep paging); the feed is shorter than Android's for a heavy-upload channel. Recorded as CF-A-5.
9. **The catalog ships 53 values containing "Download"/"ad-free". 52 have no Swift caller and stay orphaned** — the Phase 3 converter ruling ("pruning them is a converter change with its own blast radius — out of scope"), which the landed caller-aware net now *depends on*: `LocalizationTests.theOrphanedAndroidDownloadKeysAreOutsideTheNet` asserts the orphan set is non-empty, so refusing them at the converter would **break** it. The 53rd, `profile_delete_account_dialog_message`, goes live in Task 18 and fails the net in en, nl **and** ar (`تنزيل`); Task 3 re-authors it. **`onboarding_page3_title` was already fixed at `a99a107e`** and must not be touched.

---

## Forks — the rulings, applied (do not re-decide; recorded so an implementer knows the shape and its cost)

| # | Ruling | Shape in this plan | Cost if wrong |
|---|---|---|---|
| **F1** | Hand-written clients, no OpenAPI extension. **Amended by review #1: FIVE clients, not four** — `YouTubeSearchClient` (Task 26) is the fifth; no OpenAPI path covers `GET /api/admin/youtube/search` and the Suggest screen needs it | `AccountClient`, `SyncClient`, `ApprovalsClient`, `YouTubeSearchClient`, `ImportClient` over `HTTPTransport`, decoding only what they read, explicit status semantics, **ONE shape-pin test per client** | drift from the server, caught by the pin tests |
| **F2** | Split 4a/4b, one plan file, a gate between | Tasks 1–18 (Part A) → **Task 19 gate** → Tasks 20–30 (Part B) → **Task 31 gate** | one extra review pipeline |
| **F3** | `SyncState`/`AccountBinding` are SwiftData `@Model`s in V5 | cursor + rows in one `ModelContext.save()` (Tasks 20, 23) | a torn cursor = the SYNC-CURSOR-PERSIST-01 bug |
| **F4** | Me feed = Atom half + week bucketing, **no deep paging** | reuse `AtomFeedFetcher`; `reachedEnd` when the per-channel cache runs out (Tasks 14–16) | short feeds for low-upload channels; additive later |
| **F5** | Phone required, one free-text `+` field, no picker | `BootstrapValidator.phonePattern` = the backend regex (Task 12) | users must know their dial code |
| **F6** | Foreground-only Me refresh | burst-if-stale 30 min on Me appearance + pull-to-refresh; **no `BGAppRefreshTask`** (Task 16) | a slightly staler feed on open |
| **F7** | Drop `MeTelemetryLogDialog` + `MeRefreshTelemetry` | not built (CF-A-3) | debugging a stuck feed needs a log read |
| **F8** | §12 row 3 descoped | CF-A-1 | a resolver rung retries for a set measured today as empty |
| **F9** | Revoke = forget the token locally + Google permissions link | Task 29; **never `GIDSignIn.disconnect()`** (it signs the Google user out) | weaker than a user may assume; the copy is honest |
| **F10** | No History / Recently-watched rows on Me | Task 13 ships Favorites, chips, the feed and a Saved link — nothing else (RULING 28) | a user looking for history finds nothing rather than a promise |
| **F11** | Google + Apple behind protocols, each rendered only when its prerequisite exists | Task 5's `SignInCapabilities`; email/password works with the plist alone | untested until the plist lands either way |
| **F12** | ONE token source | `AuthTokenProviding` + `BearerScope` + **`BearerRetry`** (Task 4/6) behind `AuthMiddleware` and `AuthorizedTransport` — one host rule, one retry state machine, not two copies | three copies of the host rule |

**Two forks this plan adds (defaults chosen; work proceeds unless overridden):**

- **F13. `channel_feed_refresh_state` lives in a `KeyValueStore`, not SwiftData.** Ruling F3's atomicity argument is specific to the sync cursor, which must commit with the rows it advances past. Per-channel TTL/backoff bookkeeping has no such coupling, and putting it in SwiftData would force a schema version into Part A, which the rulings place in Part B. **Default: `UserDefaults`-backed `KeyValueStore` (the `UserDefaultsKeyValueStore` seam already in `AppContainer.swift:41-50`).** Override: fold it into V5 and move Task 20 ahead of Task 16.
- **F14. The Content/Pending tabs ship in Part B, not Part A.** Nothing in Part A can produce an `approvalStatus == "AWAITING"` row (`SubscribedChannel`/`SavedPlaylist` do not even have the column until V5), so a Part-A tab bar would be permanent chrome over a permanently empty queue — RULING 28's "dead affordances" again, and Android hides it at zero anyway (`MeFragment.kt:360-368`). **Default: Task 30 adds the awaiting count and the tabs with the import flow that fills them.** Override: build the tabs in Task 13 rendering a hard zero.

---

## Global Constraints

Implementers inherit nothing from earlier plans. All of the following are binding on every task:

- **Swift 6, `SWIFT_STRICT_CONCURRENCY: complete`, `SWIFT_DEFAULT_ACTOR_ISOLATION: MainActor`** on the app and unit-test targets (`ios/project.yml:18,63,129`). Pure decision types tests construct off the main actor are `nonisolated`. Per spec §5's isolation rule, a non-`Sendable` SDK object (Firebase `Auth`, `GIDSignIn`) is wrapped behind a `Sendable` protocol or a `@MainActor` store so `AppContainer`'s stored properties keep their current shape — the trade `CastController` already made (`AppContainer.swift:132-137`).
- **Hilt-free.** The composition root is `AppContainer` (`ios/FitrahTube/App/AppContainer.swift:61`); ViewModels take dependencies in `init`; views read `@Environment(\.container)`. No singletons, no service locator. Every new dependency is a `private(set) lazy var` on the container with a fake reachable from `AppContainer.fake()`.
- **One implementer at a time on the iOS build slot.** `ios/DerivedData` is shared; never run two `xcodebuild`s concurrently.
- **Gate for every task:** `KEEP_RESULTS=1 bash ios/scripts/test.sh` from the repo root. Stages: `copy-firebase-plist.sh` (Task 1 adds it) → `convert-strings.py --check` → Cast-SDK heal → `xcodegen generate` → `xcodebuild test` on iPhone 17 + iPad Pro 13-inch (M5) → `swift test` in both packages → a Release build. **300 s wall-clock, 60 s per test** (`test.sh:2-13`; CLAUDE.md's 30 s per method is not expressible on iOS — XCTest rounds `defaultTestExecutionTimeAllowance` up to 60 s and Swift Testing's `timeLimit` floor is one minute; `FitrahTube.xctestplan` sets 60). A task is not done until its gate is green. If a stage trips the watchdog, **report it — never silently raise the timeout**.
- **Never delete `ios/DerivedData/SourcePackages`.** SPM checkouts live there and `ios/FitrahTube.xcodeproj/` is git-ignored (`.gitignore:199,204`), so wiping DerivedData discards the Firebase + GoogleSignIn checkouts and the next `xcodebuild` re-resolves them **inside** the 300 s watchdog — a guaranteed exit 124 with no diagnosis. A "clean build" means `rm -rf ios/DerivedData/Build`, nothing more. If a full clean is genuinely needed, run `xcodebuild -resolvePackageDependencies` by hand first (Task 1's procedure), then gate.
- **Test-count baseline: re-measure at Task 1.** Run `grep -rho '@Test' ios/FitrahTubeTests/*.swift | wc -l` at the tip of the branch and record the number in Task 1's commit body; Phase 3's final fix batches are landing on this branch and move it (881 at `86affdc8`, 891 at `a99a107e`, higher after the adversarial batch). **Each task states a *delta*, never an absolute.** Two caveats when reading `test.sh`'s summary: it prints the count **doubled** (two destinations in one invocation), and a `@Test(arguments:)` declaration contributes **one** to the count but runs once per argument.
- **Tests run against fakes only. No Firebase, no network, no wall-clock sleeps as a mechanism.** Every Phase 4 gate is hermetic. Injected clocks and the `Gate` rendezvous actor (`ios/FitrahTubeTests/Support/TestDoubles.swift:33-53`) replace waiting; `ScriptedTransport` (Task 7) is the ONE canned `HTTPTransport` and `RecordingTransport` (`ios/Packages/FitrahAPI/Tests/FitrahAPITests/RecordingTransport.swift`, already on the app test target's source list, `project.yml:114-115`) is the OpenAPI-side double. **If a Firebase type appears in a ViewModel, the gate is unmeetable** — Firebase names live in `FirebaseBootstrap.swift`, `FirebaseAuthClient.swift`, `GoogleAuthProvider.swift`, `AppleAuthProvider.swift` and `GoogleYouTubeAuthorizer.swift`, nowhere else.
- **All user-visible strings go through `ios/scripts/convert-strings.py`. Never hand-edit `ios/FitrahTube/Resources/Localizable.xcstrings`** — `test.sh` stage `convert-strings.py --check` fails if you do. `InfoPlist.xcstrings` is the one hand-authored exception. Every task names the Android keys it ports and every key it refuses or re-authors.
- **Copy rules, absolute.** Never **"Download"**, never **"ad-free"**, in any locale. This is enforced by `LocalizationTests.noKeyWithASwiftCallerCarriesABannedStemInAnyLocale`, which landed with Phase 3 and checks every catalog key with a Swift caller, in en/ar/nl, against `bannedStems` = ASCII `download`/`ad-free`/`ad free`, Arabic `حمّل`/`حمل`/`تنزيل`, Dutch `advertentievrij`/`reclamevrij`, Arabic `بدون إعلانات`. **Every Phase 4 key is picked up automatically the moment its screen lands** — do not add keys to any allowlist. Refusal copy says **WHAT, never WHY** ("This video can't be played in the app" — never "YouTube blocked us", never "the admin hasn't allowed it"). **Never a button, link or redirect to YouTube** (owner directive 2026-08-27). A Google OAuth consent screen for the `youtube.readonly` scope is Google sign-in, not a YouTube redirect — allowed; the Google account-permissions link in F9 — allowed; `GET /api/admin/youtube/search` is a *backend* call — allowed.
- **No music-video ids** in tests, fixtures, docs or live checks (owner directive): never any music-video id (the one that prompted the directive is on the owner's record; do not spell it in docs). APPROVED ids: video `xc7keR2piUM`, channel `UCmMcOjsVehVlEOteyrhjI2Q` (Alafasy, the Phase 3 fixture channel), playlist `PL6SWGxz3wzpSrxgiBj2PCuEf-MenhYTCc`, or synthetic tokens.
- **`GoogleService-Info.plist` is git-ignored and USER-BLOCKED.** **NEVER touch, read, copy or commit `firebase-service-account.json`** (it lives outside the repo, at `$HOME/.config/albunyaan/`, and is the backend's admin credential).
- **New `Route` cases land with their screen**, never ahead of it: `MainShellView.destination(for:)` (`ios/FitrahTube/Features/Shell/MainShellView.swift:169-196`) is an exhaustive switch with **no `default:` arm**, so each added case is exactly one compile error, fixed in the same task, plus one `MainShellRoutingTests` arm pinned by the `leafTypeName(for:)` walker (`MainShellRoutingTests.swift:12-23`). Phase 4 adds eight cases across Tasks 10, 11, 12 (two), 17, 25, 27 and 29.
- **No new files need project edits** — `project.yml:43-46` globs `FitrahTube/`, and `xcodegen generate` is a gate stage. The exceptions are Task 1's `packages:`/`dependencies:` entries and Task 2's entitlements / URL type / Info.plist key.
- **Accessibility floor** (spec §14): ≥44 pt targets, Dynamic Type everywhere, single column at `.accessibility1+`, label **and** value on every stateful control, RTL through leading/trailing only, every number and date through `Format` (`ios/FitrahTube/Catalog/Formatting.swift`), `\u{2068}…\u{2069}` isolation in composite strings.
- **Pagination rule (CLAUDE.md):** every list that can exceed a screen auto-triggers `loadMore()` when the loaded items already fit — `PaginationGuard` (`ios/FitrahTube/Features/Lists/PaginationGuard.swift`), not a scroll listener alone. Applies to the Me feed (Task 16), My Submissions (Task 25) and Suggest results (Task 27). It does **not** apply to the chips row or the favorites row, which are complete local lists.
- **No new `.md` files; this plan is the only document Phase 4 creates.** Never `git add` a path a task's commit step does not name.
- **Commits:** `[PREFIX]: Description` (≤50 chars) with `[FEAT]` `[FIX]` `[REFACTOR]` `[PERF]` `[DOCS]` `[TEST]` `[CHORE]`, body listing the changes, and the trailer:
  ```
  Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
  ```
  **Stage explicit paths only** (`git add <path> <path>`), never `git add -A`/`.`. **Never push.**
- **Simulator vs device honesty.** Everything USER-BLOCKED is listed in Acceptance Tier 3; do not claim a device or live-Firebase behaviour from a simulator run against fakes.

---

# PART A (4a) — Firebase seam, auth, Me shell, profile

## Task 1: Firebase SPM packages + the plist pre-stage (day-1 risk task)

**Goal:** Get the two dependencies into the graph, pinned, and prove the app still builds and gates green with **no** `GoogleService-Info.plist`. No Swift code. This is Task 1 for the reason Phase 3's `AVAssetDownloadTask` spike was: four unknowns land here (does the Firebase graph compile under Xcode 26.3 with strict concurrency; what does linking cost against the 300 s watchdog; what versions resolve; does the pre-stage no-op cleanly), and every one is cheap now and expensive at Task 18.

**Files:**
- Modify: `ios/project.yml` (`packages:` ×2, the app target's `dependencies:` ×2)
- Create: `ios/scripts/copy-firebase-plist.sh`
- Modify: `ios/scripts/test.sh` (stage 0), `ios/scripts/screenshots.sh` (same pre-stage), `.gitignore`

**Interfaces consumed:** none — this is the first task.

**Interfaces produced:** no Swift API. A build graph containing `FirebaseAuth` and `GoogleSignIn`, and a bundled `GoogleService-Info.plist` **iff** one exists at `$HOME/.config/albunyaan/`.

- [ ] **Step 1: Record the test-count baseline.** `grep -rho '@Test' ios/FitrahTubeTests/*.swift | wc -l` at the branch tip; put the number in this task's commit body. Every later task's delta is measured against a re-run of the same command, never against a number written in this plan.
- [ ] **Step 2: Add the packages.** In `ios/project.yml` under `packages:`:
  ```yaml
    Firebase:
      url: https://github.com/firebase/firebase-ios-sdk
      from: "11.0.0"
    GoogleSignIn:
      url: https://github.com/google/GoogleSignIn-iOS
      from: "8.0.0"
  ```
  and on the `FitrahTube` target's `dependencies:`: `- package: Firebase` / `product: FirebaseAuth`, and `- package: GoogleSignIn` / `product: GoogleSignIn`. **Pull `FirebaseAuth` only — never Firestore** (it drags abseil/gRPC and multiplies resolve time). `FitrahTubeTests` gets **nothing**: no test-visible interface may name a Firebase type.
- [ ] **Step 3: Resolve once, outside the gate, then pin exactly.** Run by hand (multi-minute, network-dependent; the 300 s watchdog would kill it):
  ```
  cd ios && PATH="$HOME/.local/bin:$PATH" xcodegen generate \
    && xcodebuild -resolvePackageDependencies -project FitrahTube.xcodeproj
  ```
  Read `ios/FitrahTube.xcodeproj/project.xcworkspace/xcshareddata/swiftpm/Package.resolved` and **replace both `from:` floors with `exactVersion:` at the versions that actually resolved**, recording them in a `project.yml` comment with the date. This is not prudence, it is a constraint: `/ios/FitrahTube.xcodeproj/` is git-ignored (`.gitignore:199`) and `Package.resolved` lives inside it, so **`project.yml` is the only pin that survives a fresh checkout** — a floating `from:` makes the gate's reproducibility a function of the day it runs, on every machine.
  **If the resolved 11.x fails to build under Xcode 26.3 or trips `SWIFT_STRICT_CONCURRENCY: complete`** (`from: "11.0.0"` means `>=11.0.0, <12.0.0`, and Firebase may be several majors past that): raise the major floor, re-resolve, pin again, and record the version **and the reason** in the `project.yml` comment. Do **not** work around it inside `FirebaseAuthClient`.
- [ ] **Step 4: The plist pre-stage** (contradiction 6). Write `ios/scripts/copy-firebase-plist.sh`: if `$HOME/.config/albunyaan/GoogleService-Info.plist` exists, copy it to `ios/FitrahTube/Resources/GoogleService-Info.plist` (**unconditionally when the source is newer** — not `[ -f dest ] ||` guarded like the Cast heal, or a refreshed source never propagates) and echo the fact; otherwise echo a one-line notice and **exit 0** — never fail a build for a USER-BLOCKED file. The script's usage comment must say: *"Xcode does not run this. A developer opening the project directly gets no plist until they run this script or `ios/scripts/test.sh`."* Add `/ios/FitrahTube/Resources/GoogleService-Info.plist` to `.gitignore`.
- [ ] **Step 5: Wire the pre-stage.** In `test.sh`, insert `bash ios/scripts/copy-firebase-plist.sh` as the **first** line of `run_all()` — before `convert-strings.py --check` (`test.sh:67-68`), because `run_all()` starts repo-root-relative and only `cd`s to `ios/` at `:70`, and before `xcodegen generate` (`:77`), which is what makes the `sources` glob pick the file up. Add the same call to `screenshots.sh` beside its Cast pre-stage.
- [ ] **Step 6: Gate** (`KEEP_RESULTS=1 bash ios/scripts/test.sh`, expected **+0 declarations**). **Measure and report the wall time** — this is the number every later task's margin depends on. Record it in the commit body as `GATE_BASELINE=<seconds>`. The decision rule, so it is not re-litigated per task: **under 210 s** → nothing changes. **210–260 s** → every task's gate drops to one destination (`IPHONE_SIM` only — `test.sh:27-28` already takes `IPAD_SIM` from the environment, so this is `IPAD_SIM=""`-shaped, **not a script edit**), and Tasks 19/31 keep both. **Over 260 s** → stop and report **before Task 2**: the phase needs a `test.sh` change (per-target `-only-testing:` shards, or moving the Release stage to the gate tasks only), which is a decision for the dispatcher, not a task. **Never raise the 300 s watchdog.** Commit: `[CHORE]: Firebase + GoogleSignIn SPM, plist pre-stage`.

**Acceptance:** a clean checkout with **no** plist resolves, builds and gates green; both versions are pinned with `exactVersion:` in `project.yml`; the measured gate wall time is recorded.

---

## Task 2: `FirebaseBootstrap`, entitlements, URL type, privacy manifest

**Goal:** One guarded configure hook, the Google URL handoff, the Apple entitlement, the build-time Apple capability flag, and the four privacy declarations Phase 4 owes.

**Files:**
- Create: `ios/FitrahTube/App/FirebaseBootstrap.swift` (the ONLY file in this task naming a Firebase type)
- Create: `ios/FitrahTube/FitrahTube.entitlements`
- Modify: `ios/project.yml` (`CODE_SIGN_ENTITLEMENTS`, the reversed-client-id URL type, `FITRAH_APPLE_SIGNIN`), `ios/Config/Debug.xcconfig` + `Release.xcconfig` (`GID_REVERSED_CLIENT_ID = ` empty)
- Modify: `ios/FitrahTube/App/FitrahTubeApp.swift` (`init()` warm-up; `.onOpenURL` gives Google first refusal), `ios/FitrahTube/Resources/PrivacyInfo.xcprivacy`
- Create: `ios/FitrahTubeTests/FirebaseSeamTests.swift`

**Interfaces consumed:** Task 1's build graph (`import FirebaseCore`, `import GoogleSignIn` resolve).

**Interfaces produced:**
```swift
/// The ONE file that imports FirebaseCore. Every member is safe to call with no options file —
/// that is the USER-BLOCKED state (contradiction 5), in which the app runs as a guest.
nonisolated enum FirebaseBootstrap {
    static var optionsFileExists: Bool          // Bundle.main.path(forResource: "GoogleService-Info", ofType: "plist") != nil
    static var googleClientID: String?          // the plist's CLIENT_ID, nil when absent — F11's Google capability
    /// IDEMPOTENT and SELF-CALLING. Returns true iff Firebase is configured.
    @discardableResult static func configureIfPossible() -> Bool
    /// True when the URL was a Google Sign-In callback and was consumed.
    static func handleOpenURL(_ url: URL) -> Bool
}
```

- [ ] **Step 1: Write the failing tests** (`FirebaseSeamTests.swift`):
  - `#expect(FirebaseBootstrap.configureIfPossible() == false)` — CI has no plist, and this flips to a real assertion the day one lands with **no test edit**. Separately, pin the flag to the bundle rather than to itself: `#expect(FirebaseBootstrap.optionsFileExists == (Bundle.main.path(forResource: "GoogleService-Info", ofType: "plist") != nil))`.
  - Calling `configureIfPossible()` twice does not trap (idempotence is load-bearing — see Step 3).
  - `handleOpenURL(URL(string: "albunyaantube://video/xc7keR2piUM")!)` returns **false**, so `DeepLinkParser` still gets it.
  - Read `PrivacyInfo.xcprivacy` from the bundle and assert `NSPrivacyCollectedDataTypes` contains `NSPrivacyCollectedDataTypeEmailAddress`, `…Name`, `…PhoneNumber`, `…OtherDataTypes` and `…UserID`, each `Linked = true`, `Tracking = false`, purpose `AppFunctionality`, plus the pre-existing `…DeviceID`.
  - `Bundle.main.object(forInfoDictionaryKey: "FITRAH_APPLE_SIGNIN")` exists and is `""` with no Team ID.
- [ ] **Step 2: Implement `FirebaseBootstrap`.** `configureIfPossible()` reads the options file, calls `FirebaseApp.configure()` once behind a `dispatch_once`-shaped latch, and returns whether Firebase is configured. `googleClientID` reads `CLIENT_ID` from the plist and, when non-nil, sets `GIDSignIn.sharedInstance.configuration = GIDConfiguration(clientID:)`. `handleOpenURL` forwards to `GIDSignIn.sharedInstance.handle(url)`.
- [ ] **Step 3: Wire the hooks — and understand why idempotence matters.** `FitrahTubeApp.init()`'s body calls `configureIfPossible()` as an eager warm-up, **but that is not what makes auth work**: `@State private var container = … AppContainer.live(…)` (`FitrahTubeApp.swift:16-19,28`) is a **stored-property initializer**, and Swift evaluates every stored-property initializer *before* the body of `init()` runs. So `AppContainer.live()` completes first. **The auth builder therefore calls `configureIfPossible()` itself** (Task 4). Never gate a container member on "configure already ran" — on the real launch path it has not. Then `.onOpenURL` (`:68`) becomes `.onOpenURL { if !FirebaseBootstrap.handleOpenURL($0) { router.open($0) } }`.
- [ ] **Step 4: Entitlements, URL type, capability flag.** `FitrahTube.entitlements` with `com.apple.developer.applesignin = ["Default"]`; `project.yml` sets `CODE_SIGN_ENTITLEMENTS: FitrahTube/FitrahTube.entitlements`. Add a second `CFBundleURLTypes` entry named `com.googleusercontent.apps` whose `CFBundleURLSchemes` is `[$(GID_REVERSED_CLIENT_ID)]`, with `GID_REVERSED_CLIENT_ID = ` (empty) in **both** xcconfigs and a comment that `Local.xcconfig` supplies the real value the day the plist lands. Add the Info.plist property `FITRAH_APPLE_SIGNIN: $(FITRAH_TEAM_ID)` — the build-time flag Task 5's `SignInCapabilities.apple` reads, because **there is no runtime API to read your own entitlements** and an unsigned simulator build carries none at all. **Verify the Release simulator stage stays green**: with `"CODE_SIGNING_ALLOWED[sdk=iphonesimulator*]": NO` (`project.yml:26`) there is no `CodeSign` step, so `CODE_SIGN_ENTITLEMENTS` is never consumed and this is expected to be a non-issue; if it somehow is not, scope it as `"CODE_SIGN_ENTITLEMENTS[sdk=iphoneos*]"` and say so in the commit body.
- [ ] **Step 5: Extend the privacy manifest** with `NSPrivacyCollectedDataTypeEmailAddress`, `…Name`, **`…PhoneNumber`** (the bootstrap/profile phone — Contact Info has a dedicated type; `OtherUserContactInfo` is for channels with no listed type), **`…OtherDataTypes`** (date of birth — not contact info at all) and `…UserID` (the Firebase uid) — each `Linked = true`, `Tracking = false`, purpose `AppFunctionality`, keeping the existing `…DeviceID` entry and both `NSPrivacyAccessedAPITypes` entries (`CA92.1`, `35F9.1`) untouched.
- [ ] **Step 6: Gate** (expected **+6 declarations**). Commit: `[FEAT]: Firebase bootstrap, entitlements, privacy manifest`.

**Acceptance:** the app launches into guest mode with no plist; the configure hook is idempotent and callable from a stored-property initializer; the privacy manifest declares what Phase 4 collects with the right Apple types.

---

## Task 3: Copy net — extend Phase 3's caller-aware net, re-author the one key Phase 4 makes live

**Goal:** Make the two-key change Phase 4 owes to the ban, and **nothing else**. This task is a three-step, two-file no-op if the Phase 3 adversarial batch has not landed.

**Files:**
- Modify: `ios/scripts/convert-strings.py` (`REFUSE` += six keys; `EXTRA_KEYS` += one re-authored key)
- Regenerated (never hand-edited): `ios/FitrahTube/Resources/Localizable.xcstrings`

**Interfaces consumed:** `LocalizationTests.{bannedStems, referencedKeys(), noKeyWithASwiftCallerCarriesABannedStemInAnyLocale, theOrphanedAndroidDownloadKeysAreOutsideTheNet, compiledKeys(locale:)}` — landed with Phase 3 at **`743425ad`** ("[FIX]: Gate every start, caller-aware copy net, ar promo"). Note the two commits: `onboarding_page3_title`'s copy fix is the *earlier* `a99a107e`; the net itself is `743425ad`.

**Interfaces produced:** none. Catalog values only.

- [ ] **Step 1: Read the net that already landed; do not replace it.** `LocalizationTests.swift` **still carries `offlineRulingKeys`, but no longer as the ban's mechanism**: it is now a supplementary per-key pin (it gained `onboarding_page3_title`/`_desc` in the same batch) beside the caller-aware net. **Leave both alone.** The Phase 3 adversarial batch (`adversarial-fix-offline-brief.md` item **AC-P1-1 [P1]**, commit `743425ad`) added the **caller-aware** whole-catalog net: `referencedKeys()` scans every `*.swift` under `ios/FitrahTube` for plain string literals that name a catalog key, and `noKeyWithASwiftCallerCarriesABannedStemInAnyLocale` checks each one's en/ar/nl value against `bannedStems` = ASCII `download`/`ad-free`/`ad free`, Arabic `حمّل`/`حمل`/`تنزيل`, Dutch `advertentievrij`/`reclamevrij`, Arabic `بدون إعلانات`. **Read that test before touching anything.** Phase 4 adds **nothing** to it: every key Phase 4 renders is a Swift string literal, so the net picks each one up automatically the moment its screen lands. There is no new test to write.
  **If the Phase 3 batch has not landed when this task starts, stop and say so.** Task 3 is a no-op until it does, and re-authoring the net here would be a second, weaker copy.
- [ ] **Step 2: Re-author the one key Phase 4 makes live.** The catalog carries **53** values with a banned stem. **52 have no Swift caller** (verified 2026-09-02) and are therefore invisible to the net — leave them orphaned, exactly as `convert-strings.py`'s Phase 3 comment ruled ("pruning them is a converter change with its own blast radius — out of scope"), and note that `theOrphanedAndroidDownloadKeysAreOutsideTheNet` asserts the orphan set is **non-empty**, so refusing them would break a shipped test. **`onboarding_page3_title` is already fixed** (commit `a99a107e`: en "Save for offline" / ar "احفظ للمشاهدة بدون إنترنت" / nl "Bewaar voor offline") — do not touch it. The 53rd, `profile_delete_account_dialog_message`, goes live in Task 18 and fails the net in en, nl **and** ar (`تم تنزيله` matches `تنزيل`). Re-author it under `EXTRA_KEYS` with a comment naming ruling C8:

```python
    # Task 3 + Task 18 (ruling C8): the delete-account confirmation, live from Task 18. Android's
    # sentence verbatim except "every video downloaded on this device" -> the offline wording, in
    # all three locales — the ar `تم تنزيله` is a banned stem the net catches the moment
    # DeleteAccountViewModel names this key.
    "profile_delete_account_dialog_message": {
        "en": "This permanently deletes your FitrahTube account. Your name, email, phone number and date of birth are erased, along with your subscriptions, saved playlists, favourites and every video saved for offline on this device. This cannot be undone.",
        "ar": "سيؤدي هذا إلى حذف حسابك في فطرة تيوب نهائيًا. سيتم محو اسمك وبريدك الإلكتروني ورقم هاتفك وتاريخ ميلادك، إلى جانب اشتراكاتك وقوائم التشغيل المحفوظة والمفضلة وكل فيديو محفوظ دون اتصال على هذا الجهاز. لا يمكن التراجع عن هذا الإجراء.",
        "nl": "Hiermee wordt je FitrahTube-account definitief verwijderd. Je naam, e-mailadres, telefoonnummer en geboortedatum worden gewist, samen met je abonnementen, opgeslagen afspeellijsten, favorieten en elke video die op dit apparaat offline is opgeslagen. Dit kan niet ongedaan worden gemaakt.",
    },
```

  Then add to `REFUSE`, for spec §3 Out / ruling C1 — ported but permanently unreachable, so dead copy: `"auth_microsoft_button"`, `"auth_microsoft_unavailable_tv"`, `"auth_error_microsoft"`, `"bootstrap_phone_country_label"`, `"bootstrap_error_invalid_phone_country"`, **`"edit_phone_country"`** (the profile phone sheet is one free-text `+` field, same as bootstrap).
- [ ] **Step 3: Regenerate, check, gate.** `python3 ios/scripts/convert-strings.py` then `--check`. Inspect `git diff --stat ios/FitrahTube/Resources/Localizable.xcstrings` — six keys removed, one rewritten, nothing else. Gate (expected **+0 declarations**). Commit: `[FIX]: Re-author delete copy, refuse dead auth keys` — stage `ios/scripts/convert-strings.py ios/FitrahTube/Resources/Localizable.xcstrings`.

**Acceptance:** the landed net is untouched and still green; the one key Task 18 will render no longer carries a banned stem in any locale; six permanently unreachable keys are out of the catalog.

---

## Task 4: `AuthClient` seam, the `AuthErrorCode` table, `AuthTokenProviding` + `BearerScope`

**Goal:** The auth state/token seam with Firebase in exactly one file, the error table rewritten against the iOS SDK (ruling C12), and the two twelve-line types the whole token story rests on. No providers, no UI.

**Files:**
- Create: `ios/FitrahTube/Features/Auth/AuthClient.swift` (`AuthState`, `AuthUser`, `AuthErrorCode`, `AuthClient`, `UnavailableAuthClient`)
- Create: `ios/FitrahTube/Features/Auth/FirebaseAuthClient.swift`
- Create: `ios/FitrahTube/App/FakeAuthClient.swift` (**app target, `#if DEBUG`**)
- Create: `ios/Packages/FitrahAPI/Sources/FitrahAPI/AuthTokenProviding.swift` (`AuthTokenProviding` + `BearerScope`)
- Modify: `ios/FitrahTube/App/AppContainer.swift` (`auth`)
- Create: `ios/FitrahTubeTests/AuthErrorMappingTests.swift`, `ios/FitrahTubeTests/AuthClientTests.swift`

**Interfaces consumed:** `FirebaseBootstrap.{configureIfPossible(), optionsFileExists}` (Task 2).

**Interfaces produced:**
```swift
// --- ios/Packages/FitrahAPI/Sources/FitrahAPI/AuthTokenProviding.swift ---
public protocol AuthTokenProviding: Sendable {
    /// nil = send unsigned and let the backend's 401 drive the refresh
    /// (`FirebaseAuthInterceptor.kt:95-103`, a 3 s budget on the fetch).
    func idToken(forceRefresh: Bool) async -> String?
}

/// Spec §8: the Bearer never leaves the configured API host. Scope is per-HOST — port and scheme
/// are deliberately ignored (`FirebaseAuthInterceptor.kt:54-59`). ONE copy, used by
/// `AuthMiddleware` (against its `baseURL`) and by `AuthorizedTransport` (against each request URL).
public nonisolated enum BearerScope {
    public static func allows(_ url: URL, apiHost: String) -> Bool {
        url.host()?.lowercased() == apiHost.lowercased()
    }
}

// --- ios/FitrahTube/Features/Auth/AuthClient.swift ---
nonisolated struct AuthUser: Sendable, Equatable {
    var uid: String
    var email: String?
    var isEmailVerified: Bool
    /// Firebase provider ids: "password", "google.com", "apple.com".
    var providerIDs: [String]
    var hasPasswordProvider: Bool { providerIDs.contains("password") }
}

/// 1:1 with Firebase's auth-state listener. Operation loading/error state NEVER lives here —
/// it belongs on the calling screen's UI state (`AuthState.kt:11-12`).
nonisolated enum AuthState: Sendable, Equatable { case signedOut, signedIn(AuthUser) }

/// Android's 13 codes minus MICROSOFT_SIGN_IN_FAILED (spec §3 Out) plus appleSignInFailed.
nonisolated enum AuthErrorCode: String, Sendable, Equatable, CaseIterable {
    case invalidEmail, wrongPassword, userNotFound, userDisabled, emailAlreadyInUse, weakPassword
    case network, tooManyRequests, invalidCredential
    case googleSignInFailed, appleSignInFailed, passwordResetFailed, unknown

    /// The input mapping is rewritten against the iOS SDK (ruling C12): Firebase iOS raises
    /// `NSError` in `AuthErrors.domain` whose `code` is an `AuthErrorCode` raw Int, NOT Android's
    /// "ERROR_INVALID_EMAIL" strings. Taking an Int keeps this table — and its test — free of any
    /// Firebase import, which is what lets the test target name nothing Firebase-side.
    init(firebaseCode: Int)

    var messageKey: String {
        switch self {
        case .invalidEmail: "auth_error_invalid_email"
        case .wrongPassword: "auth_error_wrong_password"
        case .userNotFound: "auth_error_user_not_found"
        case .userDisabled: "auth_error_user_disabled"
        case .emailAlreadyInUse: "auth_error_email_in_use"
        case .weakPassword: "auth_error_weak_password"
        case .network: "auth_error_network"
        case .tooManyRequests: "auth_error_too_many_requests"
        case .invalidCredential: "auth_error_invalid_credential"
        case .googleSignInFailed: "auth_error_google"
        case .appleSignInFailed: "auth_error_apple"      // authored in Task 10
        case .passwordResetFailed: "auth_error_password_reset_failed"
        case .unknown: "auth_error_generic"
        }
    }
}

/// Refines `AuthTokenProviding` so ONE token source really is one type: `AppContainer.auth` is
/// handed straight to `AuthMiddleware` (Task 6) and to `AuthorizedTransport` (Task 7) with no
/// adapter (ruling F12). A matching method signature does NOT create conformance in Swift, so the
/// refinement is declared, not assumed. `import FitrahAPI` is required in this file.
nonisolated protocol AuthClient: AuthTokenProviding {
    var state: AsyncStream<AuthState> { get }
    func currentUser() async -> AuthUser?
    func signIn(email: String, password: String) async throws(AuthErrorCode) -> AuthUser
    func signUp(email: String, password: String) async throws(AuthErrorCode) -> AuthUser
    func signIn(with credential: OAuthCredential) async throws(AuthErrorCode) -> AuthUser
    func sendPasswordReset(email: String) async throws(AuthErrorCode)
    func sendVerificationEmail() async throws(AuthErrorCode)
    func reload() async throws(AuthErrorCode) -> AuthUser
    func reauthenticate(password: String) async throws(AuthErrorCode)
    func updatePassword(_ new: String) async throws(AuthErrorCode)
    func verifyBeforeUpdateEmail(_ new: String) async throws(AuthErrorCode)
    func deleteUser() async throws(AuthErrorCode)
    func signOut()
    // `idToken(forceRefresh:)` is inherited from AuthTokenProviding — do not redeclare it.
}

/// What a provider hands back. Declared here (Task 4 owns the file); POPULATED by Task 5's
/// providers. Opaque above this layer.
nonisolated struct OAuthCredential: Sendable {
    let providerID: String; let idToken: String; let accessTokenOrNonce: String?
}

/// The no-plist conformer: `state` yields `.signedOut` once and completes, every operation throws
/// `.unknown`, `idToken` returns nil. This is what makes contradiction 5 true.
/// `state` yields `.signedOut` once and finishes; `currentUser()` and `idToken` are nil; every
/// operation throws `.unknown`; `signOut()` is a no-op. Nine lines, no branches.
nonisolated struct UnavailableAuthClient: AuthClient {
    var state: AsyncStream<AuthState> { AsyncStream { $0.yield(.signedOut); $0.finish() } }
    func currentUser() async -> AuthUser? { nil }
    func idToken(forceRefresh: Bool) async -> String? { nil }
    func signOut() {}
    // Every remaining requirement: `throw AuthErrorCode.unknown`.
}
```

- [ ] **Step 1: Write the failing tests.** `AuthErrorMappingTests`: a table over `AuthErrorCode(firebaseCode:)` for each raw Int Android's table covers — `.invalidEmail`, `.wrongPassword`, `.userNotFound`, `.userDisabled`, `.emailAlreadyInUse`, `.weakPassword`, `.invalidCredential`, `.invalidUserToken`, `.networkError`, `.tooManyRequests` (write the raw Ints as named constants in the test with the SDK case name in a comment) — plus an unlisted Int → `.unknown`, and `#expect(AuthErrorCode.allCases.count == 13)`. A second test asserts every `messageKey` resolves in en/ar/nl (value ≠ key). `AuthClientTests`: `FakeAuthClient(state: .signedOut)` yields `.signedOut` then `.signedIn` when scripted; `UnavailableAuthClient.idToken(forceRefresh:)` is nil and every operation throws `.unknown`. `AppContainerTests` gains `#expect(AppContainer.fake().auth is FakeAuthClient)` — the app-target DEBUG type; a fixture container provably builds no Firebase object.
- [ ] **Step 2: Implement.** `FirebaseAuthClient` is the only conformer importing `FirebaseAuth`; it converts `(error as NSError).code` → `AuthErrorCode(firebaseCode:)`. **`FakeAuthClient` lives in the app target under `#if DEBUG`** — the `ParkedOfflineEngine`/`FixedStatusTransport` precedent (`AppContainer.swift`) — because Task 13's `-fitrah-fake-auth` screenshot hook and `AppContainer.fake()` both need it and neither can see `FitrahTubeTests`. Signature: `init(state: AuthState, user: AuthUser? = nil, scriptedErrors: [AuthErrorCode] = [])`, plus `var nextError: AuthErrorCode?` for the per-call failure leg. **`AppContainer.fake()` gains `auth: any AuthClient = FakeAuthClient(state: .signedOut)` and `accountStatusJSON: String? = nil`** so Task 13's launch hook can select a state at construction time without mutating a built container.
- [ ] **Step 3: Wire the container.** `AppContainer`'s `auth` **lazy var calls `FirebaseBootstrap.configureIfPossible()` itself** and builds `FirebaseAuthClient` on `true`, `UnavailableAuthClient` on `false` — idempotent, and required because `AppContainer.live()` runs from a stored-property initializer *before* `FitrahTubeApp.init()`'s body (Task 2 Step 3). `fake()` passes a `FakeAuthClient` explicitly, the `injectedBrowse` idiom (`AppContainer.swift:222`).
- [ ] **Step 4: Gate** (expected **+2 package declarations, +12 app declarations**). Commit: `[FEAT]: iOS auth client seam + error mapping`.

**Acceptance:** the 13-code table is pinned with no Firebase import in the test target; `AppContainer.fake()` provably builds no Firebase object; `AuthClient` *is* an `AuthTokenProviding` by declaration, not by coincidence.

---

## Task 5: OAuth providers + `SignInCapabilities`

**Goal:** Google and Apple behind protocols, each hidden when its prerequisite is absent (ruling F11).

**Files:**
- Create: `ios/FitrahTube/Features/Auth/OAuthSignInProvider.swift` (protocol + `SignInCapabilities`)
- Create: `ios/FitrahTube/Features/Auth/GoogleAuthProvider.swift`, `AppleAuthProvider.swift`
- Modify: `ios/FitrahTube/App/AppContainer.swift` (`googleSignIn`, `appleSignIn`, `capabilities`), `ios/FitrahTubeTests/Support/TestDoubles.swift` (`FakeOAuthProvider`)
- Create: `ios/FitrahTubeTests/SignInCapabilitiesTests.swift`

**Interfaces consumed:** `OAuthCredential`, `AuthErrorCode` (Task 4); `FirebaseBootstrap.{optionsFileExists, googleClientID}` (Task 2).

**Interfaces produced:**
```swift
@MainActor protocol OAuthSignInProvider: AnyObject {
    var isAvailable: Bool { get }
    func presentSignIn() async throws(AuthErrorCode) -> OAuthCredential
}

/// F11: a button renders only when its prerequisite exists. Google needs a client id from the
/// plist. Apple's entitlement CANNOT be read at runtime — there is no public API, and an unsigned
/// simulator build (`CODE_SIGNING_ALLOWED[sdk=iphonesimulator*]: NO`, `project.yml:26`) carries no
/// embedded entitlements at all — so `apple` is a BUILD-TIME flag: Task 2 writes
/// `FITRAH_APPLE_SIGNIN: $(FITRAH_TEAM_ID)` into Info.plist, and a non-empty value means a Team ID
/// signed this build and the App ID capability could be present. With no Team ID (today) it is
/// empty and the button is hidden — the honest answer. First real verification: Tier 3 item 6.
nonisolated struct SignInCapabilities: Sendable, Equatable {
    var emailPassword: Bool     // FirebaseBootstrap.optionsFileExists
    var google: Bool            // emailPassword && FirebaseBootstrap.googleClientID != nil
    var apple: Bool             // emailPassword && Info.plist FITRAH_APPLE_SIGNIN is non-empty
    static func current() -> SignInCapabilities
}
```

- [ ] **Step 1: Write the failing tests.** `SignInCapabilities.current()` is all-false in the gate (no plist); a pure `SignInCapabilities(emailPassword:google:apple:)` table drives `visibleProviders(_:) -> [Provider]` (Task 10 renders it) with all eight combinations, and `google`/`apple` are false whenever `emailPassword` is false — a provider button without Firebase configured is a trap, not a degraded path. `FakeOAuthProvider` returns a canned `OAuthCredential` and, when `isAvailable == false`, is never asked.
- [ ] **Step 2: Implement.** `GoogleAuthProvider` wraps `GIDSignIn.sharedInstance.signIn(withPresenting:)` and reports `isAvailable = FirebaseBootstrap.googleClientID != nil` — a missing client id **disables the button, never traps** (`SignInFragment.kt:220-226`). `AppleAuthProvider` wraps `ASAuthorizationAppleIDProvider` with a SHA-256 nonce (the nonce is hashed for the request and sent raw to Firebase). Both are `@MainActor` and neither appears in any test-visible signature; `AppContainer` exposes them as `any OAuthSignInProvider`.
- [ ] **Step 3: Gate** (expected **+9 declarations**). Commit: `[FEAT]: iOS Google + Apple sign-in providers`.

**Acceptance:** both providers compile with no plist and report unavailable; the capability table is pinned; no provider type is named outside its own file.

---

## Task 6: `BearerRetry` + `AuthMiddleware` (the `FitrahAPI` half)

**Goal:** The 401 dance, **once**, and the generated client's adapter over it. Ruling F12's cost-if-wrong is copies of the host rule; two copies of the retry *state machine* kept in sync by a comment is the same defect deferred, so it is written once and adapted twice.

**Files:**
- Create: `ios/Packages/FitrahAPI/Sources/FitrahAPI/BearerRetry.swift`, `AuthMiddleware.swift`
- Modify: `ios/Packages/FitrahAPI/Sources/FitrahAPI/FitrahAPIClient.swift` (`make` gains `tokens:`)
- Create: `ios/Packages/FitrahAPI/Tests/FitrahAPITests/BearerRetryTests.swift`, `AuthMiddlewareTests.swift`

**Interfaces consumed:** `AuthTokenProviding`, `BearerScope` (Task 4).

**Interfaces produced:**
```swift
/// The 401 dance, ONCE. Both callers are a thin adapter: `AuthMiddleware` passes the OpenAPI `next`
/// closure, `AuthorizedTransport` (Task 7) passes its wrapped `HTTPTransport.send`. Generic over
/// the caller's request/response pair so neither transport world leaks into the other.
public nonisolated enum BearerRetry {
    /// `send` is called at most twice: once signed (or unsigned when `token(false)` is nil), and
    /// once more ONLY if the first answered 401 with `WWW-Authenticate: Bearer`. A refresh
    /// returning nil re-sends the SIGNED original so the 401 surfaces honestly
    /// (`FirebaseAuthInterceptor.kt:161-175`) — never unsigned, which would hide the real cause.
    public static func send<Req, Res>(
        signed request: Req,
        allowed: Bool,
        token: @Sendable (_ forceRefresh: Bool) async -> String?,
        sign: @Sendable (Req, String) -> Req,
        isUnauthorizedBearer: @Sendable (Res) -> Bool,
        send: @Sendable (Req) async throws -> Res
    ) async rethrows -> Res
}

/// Installed beside `DeviceIdMiddleware`, never instead of it.
public struct AuthMiddleware: ClientMiddleware {
    public init(apiHost: String, tokens: any AuthTokenProviding)
}

// FitrahAPIClient.make gains `tokens: (any AuthTokenProviding)? = nil` — a DEFAULT is required,
// or `AppContainer.live()`'s existing call site breaks. nil = no AuthMiddleware installed.
//
// NOT INSTALLED IN PRODUCTION, deliberately. The generated client has one call site
// (`AppContainer.swift:247` -> `LiveCatalogClient`) and serves only public `/api/v1/*` paths; every
// Phase 4 endpoint is a hand-written client over `AuthorizedTransport` (Task 7). Passing `tokens:`
// here would also mean building `auth` before `AppContainer.init` — `make` runs inside `live()`,
// before the container exists — which is C5's trap in a new place. Ruling F12 requires the
// middleware to exist beside `DeviceIdMiddleware`; it is wired the day a signed-in endpoint is
// generated (spec §8's `POST /api/share-metadata/*`, which iOS has not built). See CF-A-17.
```

- [ ] **Step 1: Write the failing `BearerRetryTests`** over a trivial `(Int, Bool)` request/response pair, transport-agnostic — seven behaviours, each its own `@Test`: (1) `allowed == true` and a token → `sign` is applied once and `send` called once; (2) `allowed == false` → `sign` is never called and the request goes unsigned; (3) `token(false) == nil` → unsigned, one send, no failure; (4) a 401 **with** `WWW-Authenticate: Bearer` → exactly one `token(forceRefresh: true)` and exactly two sends, the second carrying the refreshed token; (5) a 401 **without** that header → one send, zero refreshes; (6) a second 401 after the retry → returned as-is, never a third send; (7) the refresh returning nil → the **signed** original is re-sent, not an unsigned one.
- [ ] **Step 2: Write the failing `AuthMiddlewareTests`** over `RecordingTransport` — only the two adapter facts, because the state machine is already pinned: the Bearer reaches the API host and is **absent** for a request to `https://www.youtube.com/…`, and `X-Device-Id` survives on every request (`DeviceIdMiddleware` still runs first).
- [ ] **Step 3: Implement**; `make(baseURL:deviceId:tokens:transport:)` appends `AuthMiddleware(apiHost: baseURL.host() ?? "", tokens:)` only when `tokens != nil`, keeping `DeviceIdMiddleware` first.
- [ ] **Step 4: Gate** (expected **+9 package declarations**). Commit: `[FEAT]: FitrahAPI bearer retry + auth middleware`.

**Acceptance:** the retry state machine exists in exactly one place with seven tests; the Bearer provably never leaves the API host; the existing `make` call site still compiles.

---
## Task 7: `ScriptedTransport`, `AuthorizedTransport`, `AccountClient`, `AccountStatusCenter`

**Goal:** The app-side half of the token story, the ONE canned transport eight later tasks depend on, and the hand-written account client with explicit status semantics (ruling F1).

**Files:**
- Create: `ios/FitrahTube/App/ScriptedTransport.swift` (**app target, `#if DEBUG`** — see Step 0)
- Modify: `ios/FitrahTubeTests/AppContainerTests.swift`
- Create: `ios/FitrahTube/Features/Auth/AuthorizedTransport.swift`, `AccountClient.swift`, `AccountStatusCenter.swift`
- Modify: `ios/FitrahTube/App/AppContainer.swift` (`accountStatus`, `authorizedTransport`, `account`)
- Create: `ios/FitrahTubeTests/AuthorizedTransportTests.swift`, `ios/FitrahTubeTests/AccountClientTests.swift`

**Interfaces consumed:** `BearerRetry`, `BearerScope`, `AuthTokenProviding` (Tasks 4, 6); `AuthClient` as the live token source (Task 4); `HTTPTransport`/`HTTPRequest`/`HTTPResponse` (InnerTubeKit, `HTTPTransport.swift:3-31`); `DeviceId` (FitrahAPI).

**Interfaces produced:**
```swift
/// The ONE canned `HTTPTransport` every hand-written-client test uses (Tasks 7, 9, 10, 11, 12,
/// 16, 17, 22, 25, 26, 28). `FixedStatusTransport` (AppContainer.swift, DEBUG) answers one status
/// forever and cannot express a 401-then-200; `RecordingTransport` is an OpenAPI `ClientTransport`
/// over `HTTPTypes` and is the wrong protocol entirely. Do not write a tenth copy of this.
nonisolated final class ScriptedTransport: HTTPTransport, @unchecked Sendable {
    /// Responses are consumed in order; the queue running dry is a test failure, not a repeat.
    init(_ responses: [HTTPResponse])
    static func json(_ status: Int, _ body: String, headers: [String: String] = [:]) -> HTTPResponse
    /// The transport-error leg: `send` throws this instead of answering.
    static func failing(_ error: Error) -> HTTPResponse
    private(set) var sent: [HTTPRequest]                  // every request, in order, for assertions
    var remaining: Int { get }
    /// Peak simultaneous in-flight `send`s. Task 16 asserts the Me feed's `TaskGroup` respects
    /// `MeFeedRefreshGate.maxConcurrent`; every other caller ignores it. Kept here rather than in a
    /// subclass because this type is `final` on purpose — one canned transport, no variants.
    private(set) var peakConcurrency: Int
    func send(_ request: HTTPRequest) async throws -> HTTPResponse
}

/// The hand-written clients' half of ruling F12: same host rule, same retry, different transport
/// world. Also the ONE place a 403 status envelope becomes an event.
nonisolated struct AuthorizedTransport: HTTPTransport {
    init(base: any HTTPTransport, apiHost: String, tokens: any AuthTokenProviding,
         onStatusEvent: @escaping @Sendable (AccountStatusEvent) -> Void)
}

nonisolated enum AccountStatus: String, Sendable, CaseIterable {
    case active = "active", pendingProfile = "pending_profile", blocked = "blocked", deleted = "deleted"
    /// Unknown -> .blocked, deliberately: BLOCKED drops to guest, where PENDING_PROFILE would trap
    /// the user in a bootstrap form the backend 409s on re-entry (`AccountStatus.kt:14-27`).
    static func fromWire(_ raw: String?) -> AccountStatus { AccountStatus(rawValue: raw?.lowercased() ?? "") ?? .blocked }
}

nonisolated struct AccountMe: Sendable, Equatable {
    var uid: String; var email: String?; var displayName: String?
    var dateOfBirth: String?      // ISO "yyyy-MM-dd", the wire shape
    var phoneNumber: String?; var status: AccountStatus
    var role: String              // (raw ?? "user").lowercased() — `AccountRepositoryImpl.kt:223`
    var isModerator: Bool { role == "moderator" || role == "admin" }
}

nonisolated enum AccountError: Error, Equatable {
    case ageIneligible                       // 422 + {"code":"AGE_INELIGIBLE"}
    case profileAlreadyCompleted             // 409
    case rateLimited(retryAfterSeconds: Int) // 429; body retryAfterSeconds -> Retry-After -> 60
    case validation(field: String?, message: String)  // 400/422 "<field>: <reason>"
    case emailNotVerified                    // 403 {"code":"EMAIL_NOT_VERIFIED"} on POST /profile
    case blocked, deletedAccount             // 403 envelope
    case lastAdmin                           // 409 on DELETE /me
    case network, unknown(status: Int)
}

nonisolated struct AccountClient: Sendable {
    init(transport: any HTTPTransport, baseURL: URL, deviceId: DeviceId)
    func me() async throws(AccountError) -> AccountMe                       // GET  api/account/me
    func completeProfile(displayName: String, dateOfBirth: String, phoneNumber: String) async throws(AccountError) -> AccountMe   // POST api/account/profile
    func updateProfile(displayName: String?, dateOfBirth: String?, phoneNumber: String?) async throws(AccountError) -> AccountMe  // PUT  api/account/profile (nil = no change)
    func sendVerificationEmail() async throws(AccountError)                 // POST api/account/send-verification-email
    func deleteAccount() async throws(AccountError)                        // DELETE api/account/me -> 204 | 409 | 403
}

nonisolated enum AccountStatusEvent: Sendable, Equatable { case blocked, deleted, signedOut }

@MainActor @Observable final class AccountStatusCenter {
    private(set) var pending: AccountStatusEvent?
    /// Buffered, drop-oldest: a transport thread never blocks on the UI.
    nonisolated func post(_ event: AccountStatusEvent)
    func consume() -> AccountStatusEvent?
}
```

- [ ] **Step 0: Write `ScriptedTransport` first, in the APP target under `#if DEBUG`.** It has no test of its own beyond the ones that use it; every later client task consumes it and **must not redefine it**. It lives in `ios/FitrahTube/App/`, not `ios/FitrahTubeTests/Support/`, because Step 3 hands it to `AppContainer.fake()` — which lives in `AppContainer.swift` and cannot see the test target. Same move Task 4 already made for `FakeAuthClient`, same reason. Every consuming test still names it directly: `FitrahTubeTests` declares `- target: FitrahTube` (`project.yml:109-117`), so the bundle links the Debug app and `@testable import FitrahTube` resolves `#if DEBUG` symbols — the same way `AppContainerTests.swift:96` already names `FixedStatusTransport`, which lives under `#if DEBUG` inside `AppContainer.swift`. The Release stage compiles it out and no test references it there. **Do not add a second copy under `FitrahTubeTests/Support/` because the import "looks wrong" — it is not.**
- [ ] **Step 1: Write the failing `AuthorizedTransportTests`.** Two adapter facts only — the Bearer reaches the API host and **not** `https://www.youtube.com/…`, and `X-Device-Id` survives — because `BearerRetryTests` (Task 6) already pins the state machine and this transport uses the same `BearerRetry.send`. Then the 403 leg, which is this file's own: a 403 whose body is `{"code":"ACCOUNT_BLOCKED"}` posts exactly one `.blocked` event **and still returns the response to the caller**; `{"code":"ACCOUNT_DELETED"}` posts `.deleted`; any other 403 body posts nothing; the body peek stops at **1024 bytes** (`MAX_PEEK_BYTES`, `AccountStatusInterceptor.kt:137`); and the envelope check runs only for the four path prefixes Android actually uses — `/api/admin/`, `/api/v1/`, `/api/account/`, `/api/share-metadata/` (`:74-78`; spec §8's list is close but not the code's).
- [ ] **Step 2: Write the failing `AccountClientTests` — the F1 shape pin.** A canned `GET /api/account/me` body decodes into `AccountMe` with every field, and an unknown `status` string reads as `.blocked`; `POST /profile` sends exactly `{displayName, dateOfBirth, phoneNumber}`; `PUT /profile` **omits** nil fields rather than sending JSON null; 422 `{"code":"AGE_INELIGIBLE"}` → `.ageIneligible` while `{"validationField":"AGE_INELIGIBLE_input"}` does **not** (match the `"code"\s*:\s*"X"` regex, capped at 4096 bytes — `AccountRepositoryImpl.kt:226-259`); 429 takes `retryAfterSeconds` from the body, else the `Retry-After` header, else 60; 400 `"displayName: too long"` → `.validation(field: "displayName", …)` while `"Error: HTTP 500"` → `.validation(field: nil, …)` (only `displayName`, `dateOfBirth`, `phoneNumber` are honoured — `AccountUpdateRepository.kt:81-91`); `DELETE /me` 204 → success, 409 → `.lastAdmin`, 403 `{"code":"ACCOUNT_DELETED"}` → `.deletedAccount`; every request carries `X-Device-Id`.
- [ ] **Step 3: Implement, wire the container** (`authorizedTransport` wraps `URLSessionTransport()` in `live()` and a **`ScriptedTransport` in `fake()`** — **R5-1, exactly**: `AppContainer.swift`'s own comment records what happened the last time a fixture container built a real client against `AppConfig.apiBaseURL` (a real 404 per seeded row deleted the whole screenshot fixture before the rig could photograph it). Under `isFixture`, build it over `ScriptedTransport([.json(200, accountStatusJSON ?? defaultFakeMeJSON)])`, the `gateTransport: FixedStatusTransport(status: 503)` precedent — `accountStatusJSON` is Task 4's parameter and this is what it feeds. Add to `AppContainerTests`, beside the existing `gateTransport` pin (`:96`): `#expect(AppContainer.fake().authorizedTransport is ScriptedTransport)` — a fixture container makes **zero** account requests), gate (expected **+17 declarations**). Commit: `[FEAT]: iOS AuthorizedTransport + AccountClient`.

**Acceptance:** one scripted transport for the whole phase; `AccountClient`'s decode shape is pinned; 403 envelopes become events without swallowing the response.

---

## Task 8: `SplashRouter` matrix (pure)

**Goal:** Launch routing per spec §6 **and** §13 bullet 1's post-sign-in rule, as one pure function with no dependencies but its inputs. This is the §16-named "`SplashRouter` matrix".

**Files:**
- Modify: `ios/FitrahTube/App/SplashRouter.swift`
- Modify: `ios/FitrahTube/App/RootView.swift` (**two new switch arms only — no behaviour**)
- Modify: `ios/FitrahTubeTests/SplashRouterTests.swift`

**Interfaces consumed:** `AccountStatus` (Task 7); `AccountStatusEvent` (Task 7).

**Interfaces produced:**
```swift
nonisolated enum SplashDestination: Equatable { case onboarding, main, profileBootstrap, emailVerification }

nonisolated struct SplashOutcome: Equatable {
    var destination: SplashDestination
    var signOut: Bool
    var alert: AccountStatusEvent?
}

/// Spec §6 + §13 bullet 1, with the forced sign-in removed (D11 / RULING 31):
///   !onboardingCompleted                       -> onboarding
///   signed out                                 -> main (guest)
///   me == nil (network)                        -> main (guest; caller retries fetchMe)
///   password provider AND !emailVerified       -> emailVerification   (§13, AHEAD of status)
///   ACTIVE                                     -> main
///   PENDING_PROFILE                            -> profileBootstrap
///   BLOCKED / DELETED                          -> sign out -> main (guest) + terminal alert
nonisolated enum SplashRouter {
    static func destination(onboardingCompleted: Bool) -> SplashDestination   // kept: Phase 1's callers
    static func outcome(onboardingCompleted: Bool, signedIn: Bool,
                        hasPasswordProvider: Bool, isEmailVerified: Bool,
                        status: AccountStatus?) -> SplashOutcome
}
```

- [ ] **Step 1: Write the failing matrix.** The existing two cases stay. Add: onboarding-incomplete wins over every signed-in state; signed out → `.main`, no sign-out, no alert; signed in with `status == nil` → `.main` (guest render, background retry), no alert; `.active` → `.main`; `.pendingProfile` → `.profileBootstrap`; `.blocked` → `.main` **with** `signOut == true` and `alert == .blocked`; `.deleted` likewise. Then the §13 rows: signed in + `hasPasswordProvider` + `!isEmailVerified` → **`.emailVerification`, whatever the status**, including `nil` and `.pendingProfile`; the same user with `isEmailVerified` → the status arm; a Google-only account (`hasPasswordProvider == false`) with `!isEmailVerified` → the status arm, **never** `.emailVerification`; and blocked/deleted still sign out even when unverified (a blocked account must not be routed into a verification loop). Plus `AccountStatus.fromWire("PENDING_PROFILE")` is case-insensitive and `fromWire("something_new")`/`fromWire(nil)` → `.blocked`.
- [ ] **Step 1b: Keep `RootView` compiling.** `RootView.destinationView` (`RootView.swift:60-70`, the switch at `:64-68`) switches over `SplashDestination` with **no `default:` arm**, so the two added cases are exactly two compile errors — the same discipline the Global Constraints apply to `Route`. Add both arms here rendering `MainShellView()` behind `// Tasks 11/12 replace this arm with the real screen`; Task 9 replaces the whole switch with `SplashRouter.outcome(...)`. Do **not** add a `default:` arm — the exhaustiveness is what makes the next case a compile error instead of a silent fall-through.
- [ ] **Step 2: Implement, gate** (expected **+14 declarations**). Commit: `[FEAT]: iOS splash routing matrix`.

**Acceptance:** the §16 matrix test exists and covers all six spec §6 rows plus §13's verification rule; no path forces sign-in.

---

## Task 9: `AccountSession`, `UserScoped`, the terminal alert, `RootView`

**Goal:** One holder for account state that re-scopes the three local stores on every uid change, and the blocked/deleted terminal alert.

**Files:**
- Create: `ios/FitrahTube/Features/Auth/AccountSession.swift`, `UserScoped.swift`, `AccountStatusAlert.swift`
- Modify: `ios/FitrahTube/App/RootView.swift`, `ios/FitrahTube/App/AppContainer.swift` (`session`, `userScopedStores`)
- Modify: `ios/FitrahTube/Persistence/FavoritesStore.swift`, `ios/FitrahTube/Catalog/SubscriptionsStore.swift`, `ios/FitrahTube/Catalog/SavedPlaylistsStore.swift` (**protocol conformance only, no behaviour change**)
- Create: `ios/FitrahTubeTests/AccountSessionTests.swift`, `ios/FitrahTubeTests/RootViewDestinationTests.swift`

**Interfaces consumed:** `SplashRouter.outcome`, `SplashOutcome`, `SplashDestination` (Task 8); `AccountClient`, `AccountMe`, `AccountError`, `AccountStatusEvent`, `AccountStatusCenter` (Task 7); `AuthClient.state`/`currentUser()` (Task 4); `ScriptedTransport` (Task 7).

**Strings (already in the catalog):** `account_blocked_title`, `account_blocked_body`, `account_deleted_title`, `account_deleted_body`.

**Interfaces produced:**
```swift
/// The seam Phase 1 promised but could not declare: `currentUserId` lives on the concrete stores
/// (`FavoritesStore.swift:24`, `SubscriptionsStore.swift:52`, `SavedPlaylistsStore.swift:51`) but
/// not on their protocols, so the container — which hands out `any FavoritesStore` — could not set
/// it. One protocol, three conformances, zero behaviour change.
@MainActor protocol UserScoped: AnyObject { var currentUserId: String { get set } }

nonisolated enum AccountState: Sendable, Equatable {
    case signedOut, loading
    /// A lightweight (code, message) pair, never a raw response object — Android's comment
    /// (`AccountState.kt:16-27`) records a retained `ResponseBody` pinning a connection-pool slot
    /// for the life of a hot flow. iOS has no such pool, but code+message is what the UI needs.
    case failed(code: Int?, message: String)
    case loaded(AccountMe)
    /// Callers write `session.state.me?.isModerator` — there is no `.loaded?` shorthand in Swift.
    var me: AccountMe? { if case .loaded(let me) = self { me } else { nil } }
}

@MainActor @Observable final class AccountSession {
    init(auth: any AuthClient, account: AccountClient, stores: [any UserScoped],
         status: AccountStatusCenter, sleep: @escaping @Sendable (Duration) async -> Void)
    private(set) var state: AccountState
    /// "" when signed out — the anon sentinel every store already defaults to, so nothing
    /// downstream needs an optional.
    var uid: String { state.me?.uid ?? "" }
    /// Observes `AuthClient.state`; on each change sets every store's `currentUserId` FIRST, then refreshes.
    func start() async
    /// `MAX_ATTEMPTS = 3`, linear backoff `1 s * attempt`; IOException retries, 4xx/5xx NEVER
    /// (`AccountRepositoryImpl.kt:111-147`). The splash calls it with `maxAttempts: 1`.
    func refresh(maxAttempts: Int = 3) async
    func signOut()
    /// .blocked -> signOut; .deleted -> signOut (Task 18 adds the wipe here); .signedOut -> signOut.
    func handle(_ event: AccountStatusEvent)
}

/// The seam `RootViewDestinationTests` walks, mirroring `MainShellView.destination(for:)`
/// (`MainShellRoutingTests.swift:12-23`). `destinationView` is a `private var` with no argument, so
/// that walker cannot reach it — the switch is extracted into an internal method taking the
/// outcome, and the arms become testable without a running scene.
extension RootView {
    @ViewBuilder func destination(for outcome: SplashOutcome) -> some View
}
```

- [ ] **Step 1: Write the failing tests** with `FakeAuthClient` + a `ScriptedTransport`-backed `AccountClient`: a uid change sets `currentUserId` on **all three** stores (spy conformers) **before** `refresh()` runs; signing out sets all three back to `""`; `refresh(maxAttempts: 3)` retries a transport error exactly twice more with **no real sleeping** (the injected `sleep` records durations) and **never** retries a 4xx or a 5xx; `handle(.blocked)` signs out and leaves local data intact — a block is reversible and an ordinary sign-out deliberately keeps the library (`AccountRepositoryImpl.kt:44-49`); `handle(.deleted)` signs out and, in **this** task, does nothing else (a `// Task 18 adds the wipe here` comment marks the seam; Task 18's Files include this file and its test asserts the added call); `AccountStatusCenter.post` from a non-main context is delivered once and `consume()` clears it. Then `RootViewDestinationTests`: it reuses `MainShellRoutingTests`' `_ConditionalContent` walker (**copy the 10-line helper — it is a test-local mirror, not a shared utility**) and pins `.onboarding → "OnboardingView"`, `.main → "MainShellView"`, and — until Tasks 11/12 land — `.profileBootstrap → "MainShellView"` and `.emailVerification → "MainShellView"`, each with a `// Task 11/12 flips this` comment so the swap is a named test edit, not a silent one.
- [ ] **Step 2: Implement,** then extract `RootView.destinationView`'s switch (`RootView.swift:60-70`, the switch at `:64-68`) into the internal `destination(for outcome:)` above and drive it from `SplashRouter.outcome(...)`, with `.profileBootstrap` → `ProfileBootstrapScreen()` and `.emailVerification` → `EmailVerificationScreen()`. **Those two screens land in Tasks 11 and 12**, so this task's arms render `MainShellView()` behind a `// Tasks 11/12 replace this arm` comment and Tasks 11/12 each swap in their screen and flip their `RootViewDestinationTests` row — never a placeholder view (RULING 28). Attach `AccountStatusAlert`: a non-dismissible `.alert` over the root whose single button drops to guest and pops every tab to root.
- [ ] **Step 3: Gate** (expected **+14 declarations** — the four `RootViewDestinationTests` pins fit one `@Test`). Commit: `[FEAT]: iOS account session + user scoping`.

**Acceptance:** every store re-scopes on auth change, in the right order; the retry budget is a function of an injected sleep; the terminal alert cannot be dismissed away.

---

## Task 10: Sign-in screen (`Route.signIn`) + the guest Me button

**Goal:** The first user-visible auth surface: email/password with a sign-up toggle and forgot-password, the capability-filtered provider buttons, and the post-sign-in landing decision spec §13 requires.

**Files:**
- Create: `ios/FitrahTube/Features/Auth/EmailShape.swift`, `SignInViewModel.swift`, `SignInScreen.swift`
- Modify: `ios/FitrahTube/App/Route.swift` (`case signIn`), `Features/Shell/MainShellView.swift` (the arm), `Features/Me/MeGuestView.swift` (`:56-61`)
- Modify: `ios/scripts/convert-strings.py`; create `ios/FitrahTubeTests/SignInViewModelTests.swift`; modify `ios/FitrahTubeTests/MainShellRoutingTests.swift`

**Interfaces consumed:** `AuthClient`, `AuthErrorCode`, `AuthUser`, `OAuthCredential` (Task 4); `OAuthSignInProvider`, `SignInCapabilities` (Task 5); `SplashRouter.outcome`, `SplashOutcome` (Task 8); `AccountSession` (Task 9); `FakeAuthClient` (Task 4), `FakeOAuthProvider` (Task 5); `ScriptedTransport` (Task 7 — the tests build an `AccountSession`, which needs an `AccountClient`, which needs a transport).

**Strings:** ports (already in the catalog): `auth_sign_in_title`, `auth_sign_up_title`, `auth_email_hint`, `auth_password_hint`, `auth_sign_in_button`, `auth_sign_up_button`, `auth_create_account_link`, `auth_have_account_link`, `auth_forgot_password`, `auth_password_reset_sent`, `auth_divider_or`, `auth_google_button`, and the 12 surviving `auth_error_*`. **Authored under `EXTRA_KEYS`** (iOS-new — Android ships no Apple button):
```python
    "auth_apple_button": {"en": "Sign in with Apple", "ar": "تسجيل الدخول باستخدام Apple", "nl": "Inloggen met Apple"},
    # WHAT, never why.
    "auth_error_apple": {
        "en": "Couldn't sign in with Apple",
        "ar": "تعذّر تسجيل الدخول باستخدام Apple",
        "nl": "Inloggen met Apple is mislukt",
    },
```
Already refused in Task 3: `auth_microsoft_button`, `auth_microsoft_unavailable_tv`, `auth_error_microsoft`.

**Interfaces produced:** `SignInViewModel` — `Mode { signIn, signUp }`; `UiState(mode, email, password, isLoading, error: AuthErrorCode?, passwordResetSent, capabilities)`; `submit()`, `toggleMode()`, `forgotPassword()`, `signIn(with: any OAuthSignInProvider)`; the pure `visibleProviders(_ capabilities: SignInCapabilities) -> [Provider]`. Plus `EmailShape.isValid(_:) -> Bool` — one `@`, non-empty local part, a domain containing a `.` that neither starts nor ends with one (`EmailShape.kt:9-15`, 7 lines, ports verbatim).

- [ ] **Step 1: Write the failing tests.** `submit()` with a malformed email sets `.invalidEmail` **and makes no client call**; a password shorter than **6** sets `.weakPassword` and makes none (`MIN_PASSWORD_LENGTH = 6`, `SignInViewModel.kt:115` — it mirrors Firebase's own minimum so the client never rejects what Firebase accepts, and stops malformed attempts burning the IP throttle); a second `submit()` while `isLoading` is a no-op; a `FakeAuthClient` error surfaces as its `AuthErrorCode` and `isLoading` returns to false; `forgotPassword()` on a blank email sets `.invalidEmail` with no network call, a failure sets `.passwordResetFailed`, success sets `passwordResetSent`; `visibleProviders` follows `SignInCapabilities`. **The landing decision:** a successful sign-in as a password user with `isEmailVerified == false` yields `.emailVerification`; the same user verified with `.pendingProfile` yields `.profileBootstrap`; verified + `.active` dismisses to the shell — asserted by driving `SplashRouter.outcome` through the view model, not by re-deriving the rule. `EmailShape` gets its own 8-row table (`a@b.c` ✓, `a@b` ✗, `@b.c` ✗, `a@.c` ✗, `a@b.` ✗, `a@@b.c` ✗, empty ✗, `a.b@c.d` ✓). `MainShellRoutingTests`: `leafTypeName(for: .signIn) == "SignInScreen"`.
- [ ] **Step 2: Implement.** `state → view`: the two fields, the primary button (spinner while `isLoading`), the mode-toggle link, the forgot-password link, an inline `BannerMessage` for `error?.messageKey` and for `passwordResetSent`, then `auth_divider_or` and the capability-filtered provider buttons. Reuse `StateButton`/`BannerMessage` from `DesignSystem`. **On success the screen asks `SplashRouter.outcome(...)` through `AccountSession` with the fresh `AuthUser`'s `hasPasswordProvider` and `isEmailVerified`, and pushes `.emailVerification` / `.profileBootstrap` or dismisses to the shell** — this is what makes spec §13's post-sign-in rule real and `Route.emailVerification` reachable. Then enable the guest CTA: `MeGuestView.swift:56-61` loses the `ponytail:` note and `.disabled(true)` and pushes `.signIn`.
- [ ] **Step 3: Regenerate strings** (`python3 ios/scripts/convert-strings.py`), gate (expected **+16 declarations**), commit `[FEAT]: iOS sign-in screen`.

**Acceptance:** the guest Me button opens a real screen; every pre-network gate fires without a client call; the post-sign-in branch is pinned against the Task 8 matrix rather than re-implemented.

---

## Task 11: Email verification screen (`Route.emailVerification`)

**Files:** Create `ios/FitrahTube/Features/Auth/EmailVerificationViewModel.swift`, `EmailVerificationScreen.swift`; modify `App/Route.swift`, `Features/Shell/MainShellView.swift`, `App/RootView.swift` (swap the Task 9 arm), `ios/FitrahTubeTests/MainShellRoutingTests.swift`, `ios/FitrahTubeTests/RootViewDestinationTests.swift` (flip the `.emailVerification` row); create `ios/FitrahTubeTests/EmailVerificationViewModelTests.swift`.

**Interfaces consumed:** `AuthClient.{sendVerificationEmail, reload, signOut, currentUser}`, `AuthErrorCode` (Task 4); `AccountClient.sendVerificationEmail()`, `AccountError` (Task 7); `SplashRouter.outcome` (Task 8); `AccountSession` (Task 9); `ScriptedTransport`, `FakeAuthClient`.

**Strings (all already in the catalog):** `email_verification_title`, `_body`, `_resend`, `_check_now`, `_last_sent`, `_not_yet`, `_rate_limited`, `_network_error`, `_use_different`.

**Interfaces produced:** `EmailVerifyError { notYetVerified, rateLimited, network, unknown }`; `UiState(email, isChecking, isResending, lastSentAt: Date?, error)`; `send()`, `resend()`, `checkNow() async -> Bool`, `signOut()`. Constant `cooldown: TimeInterval = 60` (`EmailVerificationViewModel.kt:142`; the backend enforces the same 60 s per uid, `AccountController.java:48,112-116`).

- [ ] **Step 1: Write the failing tests** with an injected `now: () -> Date` — never a real wait. **Auto-send fires exactly once**: construct the VM twice from the same persisted `lastSentAt` and assert the second sends nothing (the latch survives process death, `:59-63`). `resend()` inside 60 s sets `.rateLimited` with no call; at 60 s + 1 it sends. The send path calls the **backend first** and falls back to `AuthClient.sendVerificationEmail()` **only when the backend response was not successful** (`:115-118`) — assert both orders with a scripted pair. A backend 429 maps to `.rateLimited`, a transport failure to `.network`. `checkNow()` calls `reload()` then returns `isEmailVerified`, setting `.notYetVerified` when false. `CancellationError` is rethrown, never swallowed into `.unknown`. `MainShellRoutingTests`: `leafTypeName(for: .emailVerification) == "EmailVerificationScreen"`.
- [ ] **Step 2: Implement.** `state → view`: title/body over the email; a "Resend" button disabled with a live countdown while inside the cooldown (formatted through `Format`); "I've verified" running `checkNow()` and, on true, re-running `SplashRouter.outcome`; a back affordance that **signs out** (spec §13). Persist `lastSentAt` in `UserDefaults` under `email_verification_last_sent_at`. Swap `RootView`'s `.emailVerification` arm to the real screen.
- [ ] **Step 3: Gate** (expected **+12 declarations**), commit `[FEAT]: iOS email verification screen`.

**Acceptance:** exactly one auto-send per account per install; the cooldown is a pure function of injected time; the backend-then-Firebase order is pinned; the route is reachable from Task 10 and from the splash.

---

## Task 12: Profile bootstrap + age-ineligible (`Route.profileBootstrap`, `Route.ageIneligible`)

**Goal:** The mandatory-profile form, its single validator, the two-phase commit, and the terminal under-13 screen.

**Files:** Create `ios/FitrahTube/Features/Bootstrap/BootstrapValidator.swift`, `ProfileBootstrapViewModel.swift`, `ProfileBootstrapScreen.swift`, `AgeIneligibleScreen.swift`; modify `App/Route.swift`, `Features/Shell/MainShellView.swift`, `App/RootView.swift`, `ios/FitrahTubeTests/MainShellRoutingTests.swift`, `ios/FitrahTubeTests/RootViewDestinationTests.swift` (flip the `.profileBootstrap` row); create `ios/FitrahTubeTests/BootstrapValidatorTests.swift`, `ProfileBootstrapViewModelTests.swift`.

**Interfaces consumed:** `AccountClient.completeProfile`, `AccountError.{ageIneligible, profileAlreadyCompleted}` (Task 7); `AuthClient.{updatePassword, deleteUser, currentUser}`, `AuthUser.hasPasswordProvider` (Task 4); `AccountSession` (Task 9); `ScriptedTransport`, `FakeAuthClient`.

**Strings:** ports `bootstrap_title`, `bootstrap_display_name_label/_hint`, `bootstrap_dob_label/_hint`, `bootstrap_phone_label/_hint`, `bootstrap_password_label/_confirm_label/_explainer`, `bootstrap_submit_button`, `bootstrap_error_invalid_name/_invalid_dob/_under_age/_invalid_phone/_invalid_password/_password_mismatch/_password_set_failed/_save_failed`, `age_ineligible_title/_body/_ok_button`. Refused in Task 3: `bootstrap_phone_country_label`, `bootstrap_error_invalid_phone_country`.

**Interfaces produced:**
```swift
nonisolated enum BootstrapError: Sendable, Equatable {
    case invalidName, invalidDOB, underAge, invalidPhone, invalidPassword, passwordMismatch
    case passwordSetFailed, saveFailed
}

/// ONE validator, TWO consumers — the submit button's enabled state and submit()'s error
/// dispatch — so they cannot drift (`ProfileBootstrapViewModel.kt:80-108`).
nonisolated enum BootstrapValidator {
    static let maxNameLength = 40
    static let minAgeYears = 13          // mirrors AccountProfileService.MIN_AGE
    static let minPasswordLength = 8
    /// CompleteProfileRequest.java:22, verbatim — client and server agree byte-for-byte.
    static let phonePattern = /^\+[1-9]\d{7,14}$/

    static func isUnderMinimumAge(dob: Date, today: Date, calendar: Calendar = .current) -> Bool
    static func firstError(name: String, dob: Date?, phone: String, password: String,
                           passwordConfirm: String, passwordRequired: Bool,
                           today: Date, calendar: Calendar = .current) -> BootstrapError?
}
```
Field order in `firstError`: trimmed name non-blank and ≤40 → `.invalidName`; dob non-nil → `.invalidDOB`; **local under-13 gate** → `.underAge`; phone matches `phonePattern` → `.invalidPhone`; then, iff `passwordRequired`, length ≥8 → `.invalidPassword` and equality → `.passwordMismatch`.

> **The local age gate is not a nicety and its comment must survive the port** (`:93-98`): the server's rejection is *permanent* — it revokes refresh tokens, disables the Firebase account and tombstones the Firestore doc. A mistyped year would destroy the account with no recovery. Failing locally keeps an honest mistake a correctable form error.

- [ ] **Step 1: Write the failing validator tests.** The field-order table, asserting explicitly that the non-nil DOB check precedes the age check on the same field; `maxNameLength` at 40/41 on the **trimmed** value; `isUnderMinimumAge` on the exact 13th birthday (**not** under age), the day before (under), and across a leap-year birthday, with a fixed `Calendar(identifier: .gregorian)` and `timeZone` injected — never `Calendar.current`; the phone table — `+31612345678` ✓, `+0123456789` ✗ (leading zero), `+1234567` ✗ (7 digits), `+123456789012345` ✓ (15), `+1234567890123456` ✗ (16), `0031612345678` ✗, `+31 612345678` ✗ (space); `passwordRequired == false` skips both password rules.
- [ ] **Step 2: Write the failing view-model tests.** `passwordRequired` derives from `AuthUser.hasPasswordProvider == false` (a Google-only account is asked to attach a password so the same email can later reach the admin dashboard, `:54-61`); **two-phase commit** — after a successful `completeProfile` the `profileSaved` latch is set, so a failing `updatePassword` and a retry re-run **only** the password step (assert the client saw exactly one profile POST across two submits, `:161-217`); `.ageIneligible` routes to `.ageIneligible` and every other error maps to `.saveFailed`; a missing current user at the password step yields `.passwordSetFailed`; DOB is sent as ISO `yyyy-MM-dd` through a **fixed** `en_US_POSIX`/UTC formatter — never `Locale.current`, which would emit Arabic-Indic digits for an `ar` user and 400.
- [ ] **Step 3: Implement.** `state → view`: name field (40-char cap), a `DatePicker` (`.date`, range `…today`), one phone field prefixed with a fixed `+` and `.keyboardType(.phonePad)` whose placeholder is the E.164 shape (the spec's "country hint"), the two password fields shown iff `passwordRequired`, an inline error, and a submit button gated by `firstError(...) == nil`. `AgeIneligibleScreen` is terminal: title, body, one button that deletes the Firebase user, drops to guest and pops to root. Swap `RootView`'s `.profileBootstrap` arm; add both `MainShellRoutingTests` arms.
- [ ] **Step 4: Gate** (expected **+25 declarations**), commit `[FEAT]: iOS profile bootstrap + age gate`.

**Acceptance:** one validator drives both consumers; the two-phase commit never re-POSTs a saved profile; the phone regex matches the server's byte-for-byte.

---

## Task 13: Signed-in Me shell — `MeChip`, chips, favorites row, kebab, Saved link

**Goal:** Two screens behind one tab root (ruling C5): the guest `MeGuestView` stays; a new `MeSignedInView` renders over **local** stores only. No feed yet (Tasks 14–16), no History rows (F10), no Content/Pending tabs (F14).

**Files:**
- Create: `ios/FitrahTube/DesignSystem/MeChip.swift`, `ios/FitrahTube/Features/Me/MeSignedInView.swift`, `MeViewModel.swift`, `MeTabRoot.swift`
- Modify: `ios/FitrahTube/Features/Shell/MainShellView.swift` (`rootView(for:)`'s `.me` arm → `MeTabRoot()`), `ios/FitrahTube/Persistence/SettingsStore.swift` (**doc comment only**), `ios/FitrahTube/App/FitrahTubeApp.swift` (`-fitrah-fake-auth`)
- Create: `ios/FitrahTubeTests/MeViewModelTests.swift`; modify `ios/FitrahTubeUITests/ScreenshotTests.swift`

**Interfaces consumed:** `AccountSession.state`, `AccountState.me` (Task 9); `AccountMe.isModerator` (Task 7); `FakeAuthClient` (Task 4); `FavoritesStore`, `SubscriptionsStore`, `SavedPlaylistsStore` (Phase 1/C); `Route.offline` (Phase 3); `SectionHeader`, `VideoRow`, `EmptyStateView` (DesignSystem).

**Strings (already in the catalog):** `me_favorites`, `me_see_all`, `me_empty_title/_subtitle/_cta`, `me_kebab_profile/_suggest_content/_import_youtube/_sign_out`, `my_submissions_title`, `me_subscription_cap_reached`, `me_channel_avatar_content_description`, `me_remove_from_favorites`, `settings_account_header`, `settings_account_signed_in_as`, `settings_account_signed_in_default`, `settings_account_sign_out`, `settings_account_sign_out_confirm_title`, `settings_account_sign_out_confirm_body`, `settings_account_sign_out_confirm_action`, **`settings_account_sign_out_cancel`** (note: no `_confirm_` in that fourth one). The Saved link reuses Phase 3's `offline_saved_title` — **do not author a fourth spelling of "Saved"**. No new keys.

**Interfaces produced:**
```swift
/// Spec §7's missing component: r28, 1 pt outline, 32 pt avatar. `HomeChannelItem` is the closest
/// existing shape but is a vertical tile, not a chip.
struct MeChip: View { let title: String; let avatarURL: URL?; let isSelected: Bool; let action: () -> Void }

/// Ruling C5's seam: the ONE thing `MainShellView.rootView(for: .me)` renders. Picks
/// `MeGuestView` or `MeSignedInView` from `AccountSession.state`.
struct MeTabRoot: View {}

/// Extracted from `MeGuestView.favoritesSection` (`:70-91`) rather than copied — the wave-2 W9
/// lesson. Both Me screens render it.
struct MeFavoritesSection: View { let maxRows: Int; let onSeeAll: () -> Void }

nonisolated struct MeChipItem: Sendable, Equatable, Identifiable {
    enum Kind: Sendable { case channel, playlist }
    var id: String; var title: String; var avatarURL: URL?; var addedAt: Date; var kind: Kind
}

/// Menu order = `res/menu/menu_me_kebab.xml`. There is deliberately NO `.history` and no
/// `.recentlyWatched` case: ruling F10 / RULING 28 — a row that promises "coming soon" is a dead
/// affordance, and this task's test pins the ABSENCE.
nonisolated enum MeKebabItem: Sendable, Equatable, CaseIterable {
    case profile, mySubmissions, suggestContent, importYouTube, signOut
    var titleKey: String {
        switch self {
        case .profile: "me_kebab_profile"
        case .mySubmissions: "my_submissions_title"
        case .suggestContent: "me_kebab_suggest_content"
        case .importYouTube: "me_kebab_import_youtube"
        case .signOut: "me_kebab_sign_out"
        }
    }
    /// BOTH moderator items move together (ruling C4).
    static func items(isModerator: Bool) -> [MeKebabItem]
}

extension MeViewModel {
    /// Which kebab rows are tappable *today*. At Task 13 this is exactly `[.signOut]`; each later
    /// task that lands a destination adds its own case, so every re-enable is a named test edit
    /// rather than a silent one (Task 17 `.profile`, 25 `.mySubmissions`, 27 `.suggestContent`,
    /// 29 `.importYouTube`). This is the **rendered** set, not a decoration on top of the full one.
    var enabledKebabItems: [MeKebabItem] { get }
}

@MainActor @Observable final class MeViewModel {
    /// Channels + playlists MERGED and sorted by add time, descending — never segregated.
    /// Android's comment (`MeViewModel.kt:406-411`) records why: splitting them pushed a
    /// freshly-saved playlist off-screen in RTL.
    var chips: [MeChipItem] { get }
    var selectedChipId: String?
    static let maxFavoriteTiles = 20      // `MeFavoritesAdapter:19-20,45`, plus a trailing "See all"
    var favoriteTiles: [FavoriteVideo] { get }
    var showsModeratorItems: Bool { get }  // session.state.me?.isModerator == true
    func setFilter(_ chipId: String?)
}
```

- [ ] **Step 1: Write the failing tests.** `chips` merges the two stores and sorts by add time descending (assert a playlist saved after a channel comes first); chip filtering is a pure function over the merged list; `favoriteTiles` caps at 20 given 25 rows and is empty at 0; `showsModeratorItems` is true for `"admin"` and `"moderator"` **case-insensitively**, false for `"user"`, `""` and a nil session; `MeKebabItem.items(isModerator:)` returns **exactly** `[.profile, .importYouTube, .signOut]` for false and `[.profile, .mySubmissions, .suggestContent, .importYouTube, .signOut]` for true; `MeKebabItem.allCases.count == 5` — F10's pin that no History case exists (the absence is the requirement); every `titleKey` resolves in en/ar/nl; and `enabledKebabItems == [.signOut]` at Task 13, so each later task's re-enable is a named test edit.
- [ ] **Step 2: Implement** `MeChip`, `MeFavoritesSection` (extracted, with `MeGuestView` switched to it), `MeViewModel`, `MeSignedInView` (chips row, favorites section, one row linking to `Route.offline` labelled `offline_saved_title`, a toolbar kebab), and `MeTabRoot`. **Only `.signOut` has a destination in this task, so the kebab renders `enabledKebabItems`, not `items(isModerator:)`** — `ForEach(model.enabledKebabItems)`, no `.disabled(true)` anywhere. The role gate stays fully tested through the pure `MeKebabItem.items(isModerator:)` (Step 1), which needs no rendering; `enabledKebabItems` is `items(isModerator:)` filtered to what has landed, and each of Tasks 17/25/27/29 widens the filter with its own assertion. A greyed row is still a visible promise — three of the four (`.mySubmissions`, `.suggestContent`, `.importYouTube`) are Part B, so they would sit on the signed-in Me screen through the whole of Part A *and* Task 19's screenshot matrix, which is RULING 28 arriving as review noise instead of being designed out. The menu grows as the phase does; nothing is ever tappable-into-nothing, and nothing is ever greyed. Sign-out is a confirmation `.alert` (never a `confirmationDialog` — CF-B3-11). Add the Settings `settings_account_header` section (signed-in-as row + sign-out). **Fix the stale `appLocale` doc comment** (`SettingsStore.swift:10-15`, ruling C10): replace *"restored together by phase 4's picker"* with *"RULING 33 is final: there is no picker in any phase; both are kept as Android-parity persisted state, and spec §14 routes the Settings Language row to iOS per-app language."* **Change no code there.**
- [ ] **Step 3: Screenshots.** Add `-fitrah-fake-auth <signedOut|active|pendingProfile|blocked|deleted>` at **container-construction time**, the `-fitrah-fake-container` shape (`FitrahTubeApp.swift:16-19`) — **not** the `-fitrah-seed-subscriptions` shape, which runs in the scene's `.task` after the container exists and works by writing through a store. Auth is not a store: `auth` is a `private(set) lazy var` (Global Constraints), so nothing post-construction can swap it. `AppContainer.sharedFake` reads `LaunchArguments.debug` and passes `FakeAuthClient(state:)` (and, for `pendingProfile`/`blocked`/`deleted`, a `ScriptedTransport`-backed `AccountClient` answering that `status`) — which is why Task 4 put `FakeAuthClient` in the **app** target. Add a `me-signed-in` row to `ScreenshotTests.screens` anchored on `.button("Seeded Favorite 1")`. Run the block by hand with a single `-only-testing:` invocation; eyeball en + ar, phone + iPad.
- [ ] **Step 4: Gate** (expected **+15 declarations**), commit `[FEAT]: iOS signed-in Me shell`.

**Acceptance:** a signed-in user sees chips, favorites, the kebab and a Saved link; a plain user's kebab has three items; nothing renders a History affordance; ruling C10's comment is fixed.

---
## Task 14: InnerTubeKit — `VideoItem.publishedAt` + `AtomFeedFetcher.cached(_:)`

**Goal:** The two additive package changes the Me feed cannot exist without (contradiction 7). One subsystem, one package, three files.

**Files:**
- Modify: `ios/Packages/InnerTubeKit/Sources/InnerTubeKit/BrowseClient.swift`, `AtomFeedFetcher.swift`
- Modify: `ios/Packages/InnerTubeKit/Tests/InnerTubeKitTests/AtomFeedFetcherTests.swift`

**Interfaces consumed:** none outside the package.

**Interfaces produced:**
```swift
// BrowseClient.swift: `VideoItem` gains ONE stored property, appended after `badge`. The init is
// HAND-WRITTEN (`:38-50`), not synthesized — add the parameter AND the assignment there, in
// trailing position with a `= nil` default so every existing call site compiles unchanged.
public var publishedAt: Date? = nil
// Set by AtomParserDelegate from the <published> element it ALREADY collects (`:143,:167,:181`).
// `BrowseClient` leaves it nil: YouTube hands browse a relative string, never an instant.

// AtomFeedFetcher.swift — actor-isolated, so callers write `await fetcher.cached(id)`. Declare it
// in this file so it can reach `readCache`, which is `private`.
extension AtomFeedFetcher {
    /// The per-channel cache with NO network call — what the Me feed renders between refreshes.
    public func cached(_ channelId: String) -> [VideoItem]
}
```

- [ ] **Step 1: Write the failing package tests** in `AtomFeedFetcherTests`: the existing captured Atom fixture yields a `publishedAt` `Date` matching its `<published>` instant; `publishedText` still humanizes as before; **a 304 replay returns the cached items with `publishedAt` intact** — the load-bearing one, because `CachedItem` (`:72-88`) persists only four fields today and `latest` returns `cached?.items.map(\.videoItem)` on a 304 (`:50`), so without adding `publishedAt` to `CachedItem` every replay would silently empty the Me feed, and the file's own comment (`:18-23`) records that the real endpoint sends **no** validators, which makes that a heisenbug rather than a visible break; `cached(_:)` returns the same list with **zero** transport sends (assert against a counting stub); `cached(_:)` on an unknown channel is `[]`.
- [ ] **Step 2: Implement.** Parse the ISO string **once**: `humanizePublished` (`:114-120`) already does `ISO8601DateFormatter().date(from: raw)`, so `didEndElement` parses to a `Date` and passes it to both `publishedAt` and a `humanizePublished(from date: Date?)` overload — the raw-string entry point stays for the existing tests. Add `publishedAt` to `CachedItem` and its two converters.
- [ ] **Step 3: Gate** (expected **+5 package declarations**; `swift test` in `Packages/InnerTubeKit` is a `test.sh` stage). Commit: `[FEAT]: InnerTubeKit publishedAt + Atom cache reader`.

**Acceptance:** an Atom item carries a real instant; a 304 replay keeps it; the Me feed can render from cache without a request.

---

## Task 15: `WeekBucket` + `MeFeedRefreshGate` + `ChannelRefreshState` (pure)

**Goal:** Every Me-feed decision as `nonisolated` value types with exhaustive tests. No network, no repository, no view.

**Files:** Create `ios/FitrahTube/Features/Me/WeekBucket.swift`, `MeFeedRefreshGate.swift`; create `ios/FitrahTubeTests/WeekBucketTests.swift`, `MeFeedRefreshGateTests.swift`.

**Interfaces consumed:** none — every type here is pure. (`ChannelRefreshState` is `Codable` so Task 16 can put it in a `KeyValueStore` under fork F13; this task never touches one.)

**Interfaces produced:**
```swift
nonisolated enum WeekBucket {
    static let maxWeeksBack = 5_000                       // WeekBucket.kt:44
    static func weekIndexOf(_ uploadedAt: Date, now: Date, calendar: Calendar) -> Int?   // nil beyond the cap
    /// 0 -> me_week_this, 1 -> me_week_last, n -> me_week_n_ago (argument = n).
    static func headerKey(weekIndex: Int) -> (key: String, argument: Int?)
}

/// Fork F13: per-channel bookkeeping in a `KeyValueStore`, not SwiftData — no atomicity coupling
/// with row writes, and it keeps a schema version out of Part A. ETag/Last-Modified are NOT here:
/// `AtomFeedFetcher` owns them (`AtomFeedFetcher.swift:36-63`).
nonisolated struct ChannelRefreshState: Codable, Sendable, Equatable {
    var lastSuccessfulFetchAt: Date?
    var lastAttemptAt: Date?
    var lastErrorMessage: String?
    var consecutiveErrorCount: Int
    var backoffUntil: Date?
}

nonisolated enum MeFeedRefreshGate {
    static let ttl: TimeInterval = 30 * 60                // CACHE_TTL_MS
    static let perChannelTimeout: Duration = .seconds(15) // PER_CHANNEL_TIMEOUT_MS
    static let maxConcurrent = 4                          // MAX_CONCURRENT
    static let rateLimitedBackoffs: [TimeInterval] = [3_600, 14_400, 86_400]   // ATOM_429_BACKOFFS
    static let serverErrorBackoffs: [TimeInterval] = [300, 1_800, 7_200]       // ATOM_5XX_BACKOFFS

    enum Decision: Equatable { case fetch, skipFresh, skipBackoff }
    /// Order: TTL freshness, then backoff — both bypassed by `force`. While a backoff is active NO
    /// field is written at all (`MeFeedRepository.kt:802-816`).
    static func decide(_ state: ChannelRefreshState?, now: Date, force: Bool) -> Decision

    enum Outcome: Equatable { case success, timeout, httpError(Int), transport }
    /// A timeout is a SOFT failure: record lastAttemptAt + the message, and do NOT increment
    /// consecutiveErrorCount — ambient network jitter must never push a user onto a 24 h cooldown
    /// (`:851-861`).
    static func apply(_ outcome: Outcome, to state: ChannelRefreshState?, now: Date) -> ChannelRefreshState
}
```

- [ ] **Step 1: Write the failing tests.** `WeekBucket`: index 0 for today, 1 for eight days ago **at the calendar's own week boundary**, nil beyond 5 000 weeks, header key/argument per index — all with a fixed `Calendar(identifier: .gregorian)` plus a fixed `timeZone` and `firstWeekday`, injected, never `Calendar.current`. `MeFeedRefreshGate`: fresh-inside-TTL → `.skipFresh`; `force` overrides both skips; inside `backoffUntil` → `.skipBackoff` **and `apply` is never called** (assert the state object is byte-identical); `.timeout` leaves `consecutiveErrorCount` unchanged while setting `lastAttemptAt` and the message; `.httpError(429)` walks `[1 h, 4 h, 24 h]` across three consecutive failures and **stays** at 24 h on the fourth (`(errCount - 1).coerceAtMost(lastIndex)`); `.httpError(503)` walks the 5xx ladder; `.transport` walks the 5xx ladder too; `.success` clears the error count, the message and the backoff.
- [ ] **Step 2: Implement, gate** (expected **+16 declarations**). Commit: `[FEAT]: iOS Me feed week buckets + refresh gate`.

**Acceptance:** both backoff ladders and the TTL are pinned with injected clocks; a timeout provably never escalates a user onto a 24 h cooldown.

---

## Task 16: `MeFeedRepository` + the Me feed section

**Goal:** The glue: fan out over subscribed channels under the gate, bucket by week, render, refresh.

**Files:** Create `ios/FitrahTube/Features/Me/MeFeedRepository.swift`; modify `ios/FitrahTube/Features/Me/MeViewModel.swift`, `MeSignedInView.swift`, `ios/FitrahTube/App/AppContainer.swift` (`meFeed`); create `ios/FitrahTubeTests/MeFeedRepositoryTests.swift`.

**Interfaces consumed:** `AtomFeedFetcher.{latest, cached}`, `VideoItem.publishedAt` (Task 14); `WeekBucket`, `MeFeedRefreshGate`, `ChannelRefreshState` (Task 15); `MeViewModel`, `MeSignedInView` (Task 13); `SubscriptionsStore.items` (Phase C); `KeyValueStore`/`UserDefaultsKeyValueStore` (`AppContainer.swift:41-50`); `PaginationGuard`; `ScriptedTransport` (Task 7), `MemoryKV` (`TestDoubles.swift:78`).

**Strings (already in the catalog):** `me_week_this`, `me_week_last`, `me_week_n_ago`, `me_section_videos`, `me_section_shorts`, `me_refresh_error`, `me_empty_title/_subtitle/_cta`. No new keys.

**Interfaces produced:**
```swift
nonisolated struct WeekSection: Sendable, Equatable, Identifiable {
    var index: Int; var items: [VideoItem]
    var id: Int { index }
}

@MainActor @Observable final class MeFeedRepository {
    init(atom: AtomFeedFetcher, refreshState: any KeyValueStore,
         now: @escaping () -> Date, calendar: Calendar)
    private(set) var weeks: [WeekSection]
    private(set) var reachedEnd: Bool
    private(set) var lastError: String?
    /// `force` bypasses TTL and backoff (pull-to-refresh); otherwise `MeFeedRefreshGate.decide` rules.
    func refresh(channelIds: [String], force: Bool) async
    /// Re-buckets from `cached(_:)` with NO fetch — the chip filter and `loadMoreWeeks` path.
    func rebucket(filter channelId: String?) async
    func loadMoreWeeks()
}
```

- [ ] **Step 1: Write the failing tests** with a **real `AtomFeedFetcher` over a canned transport** — `AtomFeedFetcher(transport: ScriptedTransport([…]), keyValueStore: MemoryKV())`, the shape `BrowseFallbackTests.swift:44` and `PlaylistDetailViewModelTests.swift:480` already use. `AtomFeedFetcher` is a concrete `public actor` with no protocol (`AtomFeedFetcher.swift:24-34`); do **not** introduce one for the tests' sake — scripting the transport gives the same control for zero production surface. The cases: a channel whose gate says `.skipFresh` is served from `cached(_:)` with **zero** transport sends; items with a nil `publishedAt` are **dropped** from bucketing rather than piled into week 0; `reachedEnd` is true once the deepest non-empty week index has been emitted (F4 — no deep paging, no "load more" past the cache); a chip filter re-buckets without re-fetching; a subscription-count change resets the loaded weeks (`MeViewModel.kt:181-195`, on the **unfiltered** count deliberately); `setFilter` clears the loaded week indices **synchronously before** flipping the filter, so the transient empty render reads as loading, not "no results" (`:377-395`); at most `maxConcurrent` channels are in flight at once (assert `transport.peakConcurrency <= MeFeedRefreshGate.maxConcurrent` after a refresh over eight channels — `ScriptedTransport` tracks it, and it is `final` on purpose; do **not** subclass it or write a second transport).
- [ ] **Step 2: Implement.** Fan out with a `TaskGroup` bounded at `MeFeedRefreshGate.maxConcurrent`, each channel under `perChannelTimeout`. The 250 ms stagger is a `ponytail:`-commented thin glue detail, **not** part of any pure decision. `MeSignedInView` renders week sections with `PaginationGuard` on the last row (the CLAUDE.md rule — a tablet grid must auto-`loadMore` when the loaded weeks already fit) and a `.refreshable` calling `refresh(force: true)`. The Me tab's `.task` fires the burst-if-stale (F6); **no `BGAppRefreshTask`, no `UIBackgroundModes` change**.
- [ ] **Step 3: Gate** (expected **+13 declarations**). Commit: `[FEAT]: iOS Me feed repository + section`.

**Acceptance:** the feed renders from cache with no network; the fan-out respects the concurrency bound; a nil upload date never lands in "This week".

---

## Task 17: Profile screen + the three edit sheets (`Route.profile`)

**Files:** Create `ios/FitrahTube/Features/Profile/ProfileViewModel.swift`, `ProfileScreen.swift`, `EditEmailSheet.swift`, `EditPasswordSheet.swift`, `EditPhoneSheet.swift`; modify `App/Route.swift`, `Features/Shell/MainShellView.swift`, `ios/FitrahTubeTests/MainShellRoutingTests.swift`; create `ios/FitrahTubeTests/ProfileViewModelTests.swift`.

**Interfaces consumed:** `AccountClient.updateProfile`, `AccountMe`, `AccountError.{rateLimited, validation, ageIneligible}` (Task 7); `AuthClient.{reauthenticate, updatePassword, verifyBeforeUpdateEmail}`, `AuthErrorCode` (Task 4); `AccountSession.state`, `AccountState.me` (Task 9); `BootstrapValidator.{phonePattern, isUnderMinimumAge}` (Task 12); `ScriptedTransport`, `FakeAuthClient`.

**Strings (all already in the catalog):** the 28 `profile_*` keys; the 8 `edit_email_*` (`_title`, `_new_email`, `_current_password`, `_send`, `_sent`, `_invalid`, `_in_use`, `_wrong_password`); the 9 `edit_password_*` (`_title`, `_current`, `_new`, `_confirm`, `_update`, `_updated`, `_weak`, `_mismatch`, `_wrong_current`); the 4 surviving `edit_phone_*` (`_title`, `_number`, `_save`, `_updated`). **`edit_phone_country` is refused in Task 3** (ruling C1, no picker) — the phone sheet is one free-text `+` field, same as bootstrap. No new keys.

**Interfaces produced:** `ProfileUiState { loading | editing(original: ProfileFields, draft: ProfileFields, saving: Bool, error: AccountError?) | signedOut }`; `ProfileFields(displayName, dateOfBirth: Date?, emailReadOnly: String?, phoneNumber: String?, hasPasswordProvider: Bool)`; `save()`, `beginEdit(_:)`, `confirmAgeIneligibleSignOut()`.

- [ ] **Step 1: Write the failing tests.** `save()` is **changed-fields-only** — a draft differing in `displayName` alone sends `{displayName: …}` with `dateOfBirth` and `phoneNumber` **absent from the JSON**, and `phoneNumber` is never in this request at all (it has its own sheet, `ProfileViewModel.kt:129-133`); an unchanged draft makes no call; the `AccountSession` observer reconciles **only** externally-editable fields (phone, email) into an in-progress draft and never overwrites `displayName`/`dateOfBirth` the user is typing (`:44-58`); `.ageIneligible` **stops at a dialog-trigger state** and the sign-out happens only on `confirmAgeIneligibleSignOut()` — staged deliberately, because a conflating observable would swallow it (`:103-108,124-127`); `.rateLimited(retryAfterSeconds:)` renders `profile_error_rate_limited` with the seconds through `Format`; `.validation(field:)` attaches the message to that field; a DOB younger than 13 is refused by `BootstrapValidator.isUnderMinimumAge` **before** the PUT, for the same permanent-rejection reason as Task 12. **The kebab's `.profile` is now enabled and pushes `Route.profile`** — `enabledKebabItems` grows to `[.profile, .signOut]` (Task 13 N-I7).
- [ ] **Step 2: Write the failing sheet tests.** Email: re-authenticate with the current password, then **`verifyBeforeUpdateEmail(newEmail)`** — assert an `updateEmail`-shaped call is **never** made (`EditEmailViewModel.kt:62-71`). Password: local checks first (length ≥ 8, then equality), then re-auth, then `updatePassword`; an invalid-credential error from the re-auth maps to `.wrongPassword`, not `.unknown` (`EditPasswordViewModel.kt:68-70`). Phone: `BootstrapValidator.phonePattern` locally, then `PUT /api/account/profile` with `phoneNumber` only; **clearing the field sends nothing** (nil = no change, contradiction 1) — there is no "delete my phone number" affordance, and the test pins that absence.
- [ ] **Step 3: Implement.** `state → view`: inline name and DOB with a Save disabled while unchanged or saving; a read-only email row with an Edit affordance; a phone row (`profile_phone_unset` when nil); a password row shown iff `hasPasswordProvider`; then the destructive **Delete account** row, which Task 18 wires. Sheets are `.sheet` with `DragHandle`, each its own small view model.
- [ ] **Step 4: Gate** (expected **+22 declarations**), commit `[FEAT]: iOS profile screen + edit sheets`.

**Acceptance:** a partial PUT is provably partial; `verifyBeforeUpdateEmail` is the only email path; the age gate runs client-side on both entry points.

---

## Task 18: Account deletion + the local wiper

**Goal:** `DELETE /api/account/me` → 204 → Firebase `delete()` → wipe → guest, with the three Android defects ruling C13 names deliberately **not** ported.

**Files:** Create `ios/FitrahTube/Features/Profile/DeleteAccountViewModel.swift`, `ios/FitrahTube/Features/Auth/LocalAccountWiper.swift`; modify `Features/Profile/ProfileScreen.swift`, `Features/Auth/AccountSession.swift` (`handle(.deleted)` calls the wiper), `Features/Offline/OfflineManager.swift` (two protocol requirements); create `ios/FitrahTubeTests/DeleteAccountTests.swift`, `LocalAccountWiperTests.swift`.

**Interfaces consumed:** `AccountClient.deleteAccount()`, `AccountError.{lastAdmin, network}`, `AccountStatusEvent.deleted`, `AccountStatusCenter` (Task 7); `AuthClient.{deleteUser, signOut}` (Task 4); `AccountSession.handle` (Task 9); `OfflineSaving`, `OfflineStore` (Phase 3, `OfflineManager.swift:14-25`); `SearchHistoryStore.clear()` (Phase 1); `ScriptedTransport`, `FakeAuthClient`.

**Strings (already in the catalog; `profile_delete_account_dialog_message` was re-authored in Task 3):** `profile_delete_account`, `profile_delete_account_confirm`, `profile_delete_account_deleting`, `profile_delete_account_dialog_title`, `profile_delete_account_dialog_message`, `profile_delete_account_error_last_admin`, `_error_network`, `_error_unknown`.

**Interfaces produced:**
```swift
/// No success state — the terminal alert owns the screen from there (`DeleteAccountViewModel.kt:29-31`).
nonisolated enum DeleteAccountState: Equatable { case idle, deleting, failedLastAdmin, failedNetwork, failedUnknown }

@MainActor struct LocalAccountWiper {
    init(offline: any OfflineSaving, offlineStore: OfflineStore, modelContainer: ModelContainer,
         searchHistory: any SearchHistoryStore, defaults: UserDefaults)
    /// Ruling C13, in this ORDER. Steps 1-2 come first because CF-G-4 is a race: the wipe must not
    /// run while a background save is still writing into the directory it is deleting.
    ///  1. `await offline.cancelAll()`
    ///  2. `await offline.deleteAll(offlineStore.allIds())` — files AND rows, through the manager,
    ///     never `FileManager` (Phase 3's Global Constraint)
    ///  3. every SwiftData row of FavoriteVideo / SavedPlaylist / SubscribedChannel, ALL userIds
    ///     (this is a device wipe, not a per-user one)
    ///  4. `searchHistory.clear()`              (CF-G-6 — Android's wiper misses this)
    ///  5. `URLCache.shared.removeAllCachedResponses()` + the image cache
    ///  6. the persisted device id (`com.albunyaan.tube.deviceId`) — Android wipes it
    ///     (`LocalAccountDataWiper.kt:48-51`); the next request mints a new one (CF-A-9)
    func wipe() async
}
```

- [ ] **Step 1: Write the failing tests.** `delete()` → 204 → the wiper runs → `AuthClient.deleteUser()` → `signOut()` → an `AccountStatusEvent.deleted` is posted (the same terminal path an admin-side deletion takes); **409 → `.failedLastAdmin` and NOTHING local is touched** (spy the wiper: zero calls) — likewise `.failedNetwork` and `.failedUnknown` (`:61-63`); **the post-204 cleanup runs in a detached, uncancelled `Task`** (CF-G-5's fix) — cancel the calling task mid-flight and assert the wipe still completed; the wiper's step order is pinned by a recording spy (offline cancel **before** offline delete **before** the SwiftData clears); `searchHistory.entries` is empty afterwards; a `FavoriteVideo` belonging to a *different* `userId` is also gone; `AccountSession.handle(.deleted)` now calls the wiper exactly once (the seam Task 9 left with a comment).
- [ ] **Step 2: Implement.** Add **`cancelAll()` and `deleteAll(_ ids: [String])` to the `OfflineSaving` protocol** (`OfflineManager.swift:14-25`): `deleteAll` already exists on the concrete actor (`:235`) and only needs the requirement; `cancelAll` is a loop over non-terminal rows, not a new mechanism. Add `OfflineStore.allIds()` if the store has no such reader. The confirmation is an `.alert` with a destructive role.
- [ ] **Step 3: Gate** (expected **+14 declarations**), commit `[FEAT]: iOS account deletion + local wipe`.

**Acceptance:** a failed delete leaves the device untouched; a successful one leaves no favorites, playlists, subscriptions, saved files, search history or device id; the cleanup cannot be cancelled.

---

## Task 19: PART A GATE — whole-branch review + the 9-stage pipeline

**Goal:** Part A must be gate-clean before Part B's first task. This task writes no product code.

**Interfaces consumed:** everything Tasks 1–18 produced.

- [x] **Step 1:** Run the mandatory 9-stage review pipeline from `AGENTS.md` ("Mandatory Post-Coding Review Pipeline") over the **whole Part A diff** (Tasks 1–18), not per-task. If a stage cannot run, say which and why — never skip silently.
- [x] **Step 2:** `KEEP_RESULTS=1 bash ios/scripts/test.sh` from a clean **build**, not a clean `DerivedData`: `rm -rf ios/DerivedData/Build` only. **Never delete `ios/DerivedData/SourcePackages`** — that discards the pinned SPM checkouts and the next run re-resolves Firebase inside the 300 s watchdog, a guaranteed exit 124. If a full clean is genuinely needed, run `xcodebuild -resolvePackageDependencies` by hand first (Task 1 Step 3), then gate. Report the measured wall time against Task 1's recorded `GATE_BASELINE`. **Both destinations, whatever the per-task rule became.** If Task 1 recorded `GATE_BASELINE` over 260 s, the sharding decision was made before Task 2 — apply it, do not re-derive it here.
- [x] **Step 3:** `bash ios/scripts/screenshots.sh`; review every Phase 4 screen on iPhone 17 / iPad mini (A17 Pro) / iPad Pro 13-inch (M5) × {en-light, ar-dark} × {portrait, landscape} — spec §14's matrix. Check RTL mirroring on the chips row, Dynamic Type at `.accessibility3`, and ≥44 pt targets on every new control.
- [x] **Step 4:** Fix everything the pipeline raises, re-gate, commit the fixes with `[FIX]:` prefixes.

**Acceptance:** the pipeline is clean, the gate is green under 300 s, the screenshot matrix is reviewed, and no Part B task has started.

**Gate record (2026-09-04/05):** Part A base `950b8309`; gate sha `997e3af1`; stages 1-8 reports in the `.superpowers/sdd/2026-09-02-ios-phase4-accounts/` workspace (`task-19-stage*.md`). Fix commits: `cadd7c9b` (backend 403 envelope), `80a3449b`, `187b2e94`, `cc84dbfc`, `cd734522`, `017f22b4`, `b0d08057`, `2562d919`, `37c4a62c`, `073961a6`, `891d4ae5`, `5455bf66`, `4e811b40`, `59aaea9b`. Stage 9: gstack `/review` could not run non-interactively (no binary; the skill needs interactive tools) — substitute was Cubic (`cubic review -b 950b8309 -j`, valid `issues` payloads) plus a full read of each fix diff. Cubic rounds 1-3 each surfaced one real P1 (federated re-auth swapping the session before DELETE; a late `/me` answer published for a stale identity; the bare-401 terminal verdict dead under Firebase 11.15.0's force sign-out), each fixed; rounds 4 and 5 ran in parallel on `37c4a62c` — round 4 clean, round 5 one real P1 (Google button gated on the plist alone while the callback scheme comes from `Local.xcconfig`; a mismatch is an uncatchable GoogleSignIn exception), fixed in `073961a6`; rounds 6 and 7 on `073961a6`: round 6 clean, round 7 three new real P1s in code untouched since round 1 (cast re-arm under a push on iPad; phone feed paging stalls after one page; the age-ineligible screen torn down by the foreground refresh) — fixed in `5455bf66` + `4e811b40`; rounds 8 and 9 on `4e811b40` (after a two-day credit outage): round 9 clean, round 8 one real P1 (the name field capped in graphemes while the gate counted UTF-16 — a silent disabled Continue on the unskippable profile screen) plus round 9's lost wipe trigger for an expired cached token — fixed in `59aaea9b`; rounds 10 and 11 on `59aaea9b`: BOTH CLEAN (0 P0/P1; residue = accepted carry-forwards and cosmetic P3s recorded below). Gate closed 2026-09-09. Cubic's coverage varied per run on byte-identical trees (round 2 and round 5 findings existed at the previous round's HEAD), which is why the two-consecutive-clean-rounds bar matters. Debug gate ≈140-300 s under host load; Release gate green at `80a3449b`, `b0d08057`. Screenshot matrix: 5 of 8 Phase 4 screens photographed (CF-A-22).

---
# PART B (4b) — schema V5, sync, submissions, suggest, import

## Task 20: Schema V5 — the URL and import columns, `SyncState`, `AccountBinding`

**Goal:** Make the two incomplete models mirror Room v11 and add the two sync bookkeeping models (ruling F3), on the existing three-stage lightweight ladder.

**Files:** Modify `ios/FitrahTube/Persistence/FavoriteVideo.swift` (V5 + the stage), `Catalog/SubscriptionsStore.swift`, `Catalog/SavedPlaylistsStore.swift`; create `ios/FitrahTube/Sync/SyncModels.swift`; modify `App/AppContainer.swift:313` (the schema constant); create `ios/FitrahTubeTests/SchemaV5MigrationTests.swift`.

**Interfaces consumed:** `FavoritesSchemaV4`, `FavoritesMigrationPlan`, `AppContainer.makeModelContainer` (Phase 3, `FavoriteVideo.swift:43-55`, `AppContainer.swift:312-345`).

**Interfaces produced:**
```swift
enum FavoritesSchemaV5: VersionedSchema {
    static let versionIdentifier = Schema.Version(5, 0, 0)
    static var models: [any PersistentModel.Type] {
        [FavoriteVideo.self, SavedPlaylist.self, SubscribedChannel.self, OfflineItem.self,
         SyncState.self, AccountBinding.self]
    }
}
// FavoritesMigrationPlan.schemas += V5; stages += .lightweight(fromVersion: V4, toVersion: V5)

// SubscribedChannel gains (all defaulted or optional -> lightweight-migratable):
//   var channelUrl: String = ""          // Room `channelUrl`; the wire requires it
//   var approvalStatus: String = "APPROVED"
//   var source: String?                  // "USER_IMPORT" for imported rows
//   var importedAt: Date?
// SavedPlaylist gains: playlistUrl (""), uploaderName (String?), approvalStatus ("APPROVED"),
//   source (String?), importedAt (Date?).  `itemCount` STAYS — no sync DTO carries it, and it is
//   what PlaylistRow's count chip renders locally.
// Name drift kept DELIBERATELY (renaming a @Model property is NOT a lightweight migration):
//   SubscribedChannel.title/followedAt  == Room name/subscribedAt
//   SavedPlaylist.addedAt               == Room savedAt
//   *.isRemoved                         == wire "deleted"   (contradiction C9, Task 21's codec)

@Model final class SyncState {
    #Unique<SyncState>([\.entityType, \.userId])       // Room's composite PK
    var entityType: String        // SyncEntityType.rawValue: "subscriptions" | "playlists" | "favorites"
    var userId: String
    var lastCursor: Int           // epoch millis; 0 = never synced
    var lastDocId: String?        // the compound cursor's second half
    var lastSyncAt: Date
}

@Model final class AccountBinding {
    #Unique<AccountBinding>([\.userId])                // Android's single-row table
    var userId: String
    var boundAt: Date
    var initialMergeDone: Bool
}
```

- [ ] **Step 1: Write the failing migration test.** Build a **V4** store in a temp file with one favorite, one saved playlist (non-zero `itemCount`) and one subscribed channel; close it; reopen under `Schema(versionedSchema: FavoritesSchemaV5.self)` + `FavoritesMigrationPlan`; assert all three rows survive with their values intact, that `channelUrl == ""` and `approvalStatus == "APPROVED"` on the migrated channel, that `uploaderName == nil` on the migrated playlist, and that a `SyncState` and an `AccountBinding` insert and re-fetch. Extend `AppContainerTests`' existing recovery coverage; do not duplicate it. **And** that a `SubscribedChannel` with `approvalStatus == "AWAITING"` is absent from `items` but still answers `isSubscribed(_:) == true` — same for `SavedPlaylist`/`isSaved(_:)`. Both assertions go in **`SchemaV5MigrationTests.swift`**, which this task already creates — they are V5-column behaviour, the same subject as the migration itself. Do **not** create `SavedPlaylistsStoreTests.swift`: it does not exist, and only `FavoritesStoreTests.swift` and `SubscriptionsStoreTests.swift` do. Leave both alone.
- [ ] **Step 2: Implement** the two model extensions, the two new models, V5, the stage, and the `AppContainer.makeModelContainer` constant flip. Then **bring the two stores' `items` in line with `SwiftDataFavoritesStore`**, which has filtered by `approvalStatus` since Phase 1 (`FavoritesStore.swift:7-11,110-112`): add `&& $0.approvalStatus != "AWAITING"` to `SwiftDataSubscriptionsStore.refresh()`'s and `SwiftDataSavedPlaylistsStore.refresh()`'s fetch predicates (`SubscriptionsStore.swift:109-117`, `SavedPlaylistsStore.swift:106-114`). Without it, Task 28's PENDING import rows render as ordinary chips (Task 13) and count against the 30-channel cap through `items.count` (`SubscriptionsStore.swift:88`). **`isSubscribed`/`isSaved` stay unfiltered**, matching `isFavorite` — a re-add of an awaiting row must not create a duplicate.
- [ ] **Step 3: Gate** (expected **+8 declarations**), commit `[FEAT]: iOS SwiftData schema V5`.

**Acceptance:** a V4 store migrates without data loss; the two synced models now carry every column the wire needs.

---

## Task 21: Sync DTOs, the codec, and the pure decision half

**Goal:** Every interesting sync decision as `nonisolated` value types with exhaustive tests — the §16-named "`SyncManager` merge matrix" — before any actor or transport exists.

**Files:** Create `ios/FitrahTube/Sync/SyncDTOs.swift`, `SyncCodec.swift`, `SyncDecisions.swift`, `SyncBackoff.swift`; create `ios/FitrahTubeTests/SyncCodecTests.swift`, `SyncDecisionTests.swift`.

**Interfaces consumed:** `FavoriteVideo`, `SavedPlaylist`, `SubscribedChannel` with their V5 columns (Task 20).

**Interfaces produced:**
```swift
/// Declared HERE, not with the client, because `SyncState.entityType` (Task 20) is this enum's
/// `rawValue` and Task 23's decisions key on it.
nonisolated enum SyncEntityType: String, Sendable, CaseIterable {
    case subscriptions, playlists, favorites
    /// The PUT/DELETE path segment: api/account/{subscriptions|playlists|favorites}/{id}.
    var path: String { rawValue }
    /// The GET query name, which is NOT the path segment for subscriptions
    /// (`SyncController.java:41-64` takes `subs`/`subs_id`). `SyncState.entityType` uses
    /// `rawValue` — do not confuse the two.
    var queryName: String { self == .subscriptions ? "subs" : rawValue }
}

nonisolated struct SyncPage<T: Decodable & Sendable>: Decodable, Sendable {
    var items: [T]; var nextCursor: Int?; var nextCursorId: String?
}
nonisolated struct SyncResponse: Decodable, Sendable {
    var subscriptions: SyncPage<SubscriptionSyncDTO>
    var playlists: SyncPage<PlaylistSyncDTO>
    var favorites: SyncPage<FavoriteSyncDTO>
}

/// Wire names verbatim (`SyncDtos.kt:21-65`). `deleted` is the WIRE name; the SwiftData property
/// is `isRemoved` because a @Model property literally named `deleted` is silently reverted by the
/// next ModelContext.save() (Core Data KVC `isDeleted` collision, `FavoriteVideo.swift:12-18`).
/// `SyncCodec` below is the ONE place that bridges the two names (ruling C9).
nonisolated struct SubscriptionSyncDTO: Codable, Sendable, Equatable {
    var entityId: String; var deleted: Bool; var updatedAt: Int
    var channelUrl: String; var name: String; var avatarUrl: String?; var subscribedAt: Int
    var approvalStatus: String?; var source: String?; var importedAt: Int?
}
nonisolated struct PlaylistSyncDTO: Codable, Sendable, Equatable {
    var entityId: String; var deleted: Bool; var updatedAt: Int
    var playlistUrl: String; var name: String; var thumbnailUrl: String?; var uploaderName: String?
    var savedAt: Int; var approvalStatus: String?; var source: String?; var importedAt: Int?
}
nonisolated struct FavoriteSyncDTO: Codable, Sendable, Equatable {
    var entityId: String; var deleted: Bool; var updatedAt: Int
    var title: String; var channelName: String; var thumbnailUrl: String?
    var durationSeconds: Int; var addedAt: Int; var approvalStatus: String?
    var source: String?; var importedAt: Int?
}

nonisolated enum SyncCodec {
    /// A null server approvalStatus defaults to "APPROVED" (`SyncManager.kt:379,397,415`).
    @MainActor static func apply(_ dto: SubscriptionSyncDTO, to row: SubscribedChannel)
    @MainActor static func apply(_ dto: PlaylistSyncDTO, to row: SavedPlaylist)
    @MainActor static func apply(_ dto: FavoriteSyncDTO, to row: FavoriteVideo)
    @MainActor static func body(for row: SubscribedChannel) -> Data   // PutSubscriptionRequest
    @MainActor static func body(for row: SavedPlaylist) -> Data       // PutPlaylistRequest
    @MainActor static func body(for row: FavoriteVideo) -> Data       // PutFavoriteRequest
}

nonisolated enum BindAction: Equatable {
    case merge                                  // no binding row
    case pullThenPush                           // same uid, initial merge done
    case switchAccount(previousUid: String)     // different uid -> the atomic transaction, then merge
}

nonisolated enum SyncDecisions {
    static func bind(binding: (userId: String, initialMergeDone: Bool)?, uid: String) -> BindAction

    /// Pull, per row: a tombstone applies under a monotonicity guard (an older tombstone can never
    /// resurrect a newer row); otherwise the server row is SKIPPED whenever the local row is dirty.
    /// `dirty == true` alone is the conflict signal — a `local.updatedAt > row.updatedAt` clause
    /// would be vacuous, because local writes never bump `updatedAt` (it is server-stamped on push
    /// success) (`SyncManager.kt:208-267`).
    enum RowAction: Equatable { case applyTombstone, applyRow, skipDirty, skipStaleTombstone }
    static func rowAction(serverDeleted: Bool, serverUpdatedAt: Int,
                          localExists: Bool, localDirty: Bool, localUpdatedAt: Int) -> RowAction

    /// The STALLED-CURSOR GUARD. A production incident: a stored `updatedAt` with sub-millisecond
    /// precision the millisecond cursor could not express meant `startAfter()` never passed the
    /// row; the loop ran unthrottled at ~3 req/s, starved the shared HTTP client and pinned the app
    /// on the splash screen (`SyncManager.kt:326-363`). DO NOT PORT THE LOOP WITHOUT THIS.
    enum PageDecision: Equatable { case advance, stalled, exhausted }
    static func page(mintedCursor: Bool,
                     cursorsBefore: [String: Int], cursorsAfter: [String: Int],
                     idsBefore: [String: String?], idsAfter: [String: String?]) -> PageDecision

    /// Push classifier (`SyncManager.kt:575-617`).
    enum PushOutcome: Equatable { case ok, authFailed, permanentFailure, transientFailure }
    static func push(status: Int, hasBody: Bool) -> PushOutcome
}

nonisolated struct SyncBackoff {
    /// base 1 s doubling to a 60 s cap, then EQUAL JITTER: wait ∈ [base/2, base]. Added because a
    /// fleet-wide outage produced synchronised reconnections (`SyncBackoff.kt:18-35`).
    init(random: @escaping @Sendable (ClosedRange<Double>) -> Double)
    mutating func next() -> Duration
    mutating func reset()
}
```
`push` table, exactly: 2xx with a body → `.ok`; **2xx with a nil body → `.transientFailure`** (the R-final7 P0 fix — returning OK meant `clearDirty` never ran and the row re-pushed forever, `:588-605`); 404 → `.ok` (an idempotent DELETE); 401 / 403 → `.authFailed`, which breaks the drain; **400 / 409 / 422 → `.permanentFailure`**, which clears dirty with a local warning so a malformed row cannot block pulls forever (`:610-614`); everything else — 5xx, 429, transport — → `.transientFailure`.

- [ ] **Step 1: Write the failing codec tests (ruling C9's pin).** Encode a `SubscriptionSyncDTO` and assert the JSON key is **`"deleted"`** and that **no** key `"isRemoved"` appears; decode `{"entityId":"UC…","deleted":true,…}` and `apply` it, then assert `row.isRemoved == true`; round-trip all three DTOs; a null `approvalStatus` yields `"APPROVED"`; `body(for:)` emits Room's field names (`channelUrl`, `subscribedAt`, `savedAt`, `addedAt`, `durationSeconds`), **not** the Swift property names (`followedAt`, and `addedAt` on the playlist, which is Room's `savedAt`).
- [ ] **Step 2: Write the failing decision tests.** `bind`: the four-row matrix — absent → `.merge`; same uid + done → `.pullThenPush`; same uid + **not** done → `.merge` again, because a prior merge crashed mid-way; different uid → `.switchAccount(previousUid:)`. `rowAction`: the 8-row truth table, including an older tombstone against a newer row → `.skipStaleTombstone`, and a dirty local row against any server row → `.skipDirty`. `page`: minted + advanced → `.advance`; **minted + not advanced → `.stalled`** (the guard); not minted → `.exhausted`; a type that advanced only its `lastDocId` still counts as advanced. `push`: all nine rows above. `SyncBackoff`: with a stub RNG returning the range's lower bound, waits are 0.5, 1, 2, 4, … capped at 30 s (half the 60 s cap); `reset()` returns to the base.
- [ ] **Step 3: Implement, gate** (expected **+32 declarations**), commit `[FEAT]: iOS sync codec + decision types`.

**Acceptance:** the §16 merge-matrix tests exist and pass; the stalled-cursor guard and the null-body push are pinned; the `isRemoved`↔`deleted` bridge exists in exactly one place.

---

## Task 22: `SyncTransporting` + `SyncClient`

**Files:** Create `ios/FitrahTube/Sync/SyncClient.swift`; create `ios/FitrahTubeTests/SyncClientTests.swift`.

**Interfaces consumed:** `SyncResponse`, `SyncPage`, the three DTOs, `SyncEntityType` (Task 21); `AuthorizedTransport`, `ScriptedTransport` (Task 7); `DeviceId` (FitrahAPI).

**Interfaces produced:**
```swift
/// The seam Task 23's tests script. `SyncClient` is the one live conformer — a bare struct cannot
/// be substituted, and Task 23 needs to drive every status path without a network.
nonisolated protocol SyncTransporting: Sendable {
    func pull(cursors: [String: Int], ids: [String: String?]) async throws -> SyncResponse
    func put(_ type: SyncEntityType, id: String, body: Data) async throws -> (status: Int, dto: SyncRowEcho?)
    func delete(_ type: SyncEntityType, id: String) async throws -> Int
}

nonisolated struct SyncClient: SyncTransporting, Sendable {
    init(transport: any HTTPTransport, baseURL: URL, deviceId: DeviceId)
    /// GET api/account/sync?subs&playlists&favorites&subs_id&playlists_id&favorites_id
    /// (`SyncController.java:41-64`). Cursor ids are validated CLIENT-SIDE with the server's own
    /// rule (`:66-82`): ≤1500 UTF-8 bytes, no "/", no control characters, and not ".", ".." or a
    /// __reserved__ form. An invalid one is a guaranteed 400, so it is dropped to nil and the page
    /// re-fetched from the timestamp alone rather than spent on an error.
}

/// The ARCHIVE ECHO (SYNC-ECHO-01): a PUT that answers `deleted: true` means the server's
/// projection knows a parent was archived, and the row is tombstoned locally rather than merely
/// cleared (`SyncManager.kt:454-458`).
nonisolated struct SyncRowEcho: Decodable, Sendable { var deleted: Bool; var updatedAt: Int }
```

- [ ] **Step 1: Write the failing tests (the F1 shape pin).** The GET's query carries exactly `subs`, `playlists`, `favorites`, `subs_id`, `playlists_id`, `favorites_id` — **`subs`, not `subscriptions`** — and omits an id parameter whose value is nil; a canned three-page body decodes into `SyncResponse` with `nextCursor`/`nextCursorId` intact; a 1501-byte cursor id, one containing `/`, one containing `\u{0001}`, `"."`, `".."` and `"__x__"` are each dropped to nil **before** the request; the PUT path is `api/account/subscriptions/{id}` and its body is the codec's bytes verbatim; a PUT answering `{"deleted":true,"updatedAt":123}` surfaces as an echo; the DELETE returns its raw status so `SyncDecisions.push` can classify it; every request carries `X-Device-Id`.
- [ ] **Step 2: Implement, gate** (expected **+12 declarations**), commit `[FEAT]: iOS SyncClient`.

**Acceptance:** the wire shape is pinned; an invalid cursor id never reaches the server; Task 23 has a protocol to script.

---

## Task 23: `SyncManager` — the actor, the exclusion, bind/merge/pull/push

**Goal:** The thin actor over Task 21's decisions. **The highest-risk task in the plan** — it hand-rolls an async exclusion and demands an all-or-nothing SwiftData transaction, so its mutex gets its own suite before anything else is written.

**Files:** Create `ios/FitrahTube/Sync/SyncManager.swift`; create `ios/FitrahTubeTests/SyncMutexTests.swift`, `SyncManagerTests.swift`.

**Interfaces consumed:** `SyncTransporting`, `SyncRowEcho` (Task 22); `SyncDecisions`, `SyncCodec`, `SyncBackoff`, `SyncEntityType`, the three DTOs (Task 21); `SyncState`, `AccountBinding`, the V5 columns (Task 20); the three stores' `ModelContainer` (Phase 1/C).

**Interfaces produced:**
```swift
actor SyncManager {
    init(client: any SyncTransporting, modelContainer: ModelContainer,
         backoff: SyncBackoff, sleep: @escaping @Sendable (Duration) async -> Void)
    /// ONE exclusion serialising bind, pull AND push. Two separate mutexes let a pull read the
    /// server `updatedAt` while a push wrote concurrently, so the pull persisted a stale cursor
    /// tail (`SyncManager.kt:44-56`). On Swift an `actor` alone does NOT give this: every `await`
    /// inside a critical section is a reentrancy point. The exclusion is an explicit `inFlight`
    /// flag plus a FIFO of `CheckedContinuation<Void, Never>`, released in `defer` on EVERY exit
    /// path including cancellation — see Step 0.
    func bind(uid: String) async
    func unbind() async          // cancels any pending retry FIRST, under the same exclusion
    func pushDirty(uid: String) async
    func pullAll(uid: String) async
    func syncNow(uid: String) async         // pull then push — the foreground trigger
}
```

- [ ] **Step 0: Pin the exclusion first, with its own three-test suite** (`SyncMutexTests`) — this is the construct that produces deadlocks and lost wake-ups, and nothing else in the task is safe until it holds: (1) two concurrent `syncNow()` calls produce **one** pull (a counting scripted client, the `Gate` rendezvous actor to hold the first inside its critical section, **no sleeping**); (2) `unbind()` during an in-flight `pullAll` cancels the pending retry and returns — it does **not** deadlock (assert both tasks complete); (3) a caller cancelled mid-`await` does **not** strand `inFlight` — a subsequent `syncNow()` still runs. Implement the flag + continuation FIFO with a `defer`-released critical section, then move on.
- [ ] **Step 1: Write the failing manager tests** against a scripted `SyncTransporting` and an in-memory `ModelContainer`. `bind` on an absent binding runs merge in order — **tag anonymous rows (`userId == ""`) to the uid → pull → push → mark merge done** (`:136-147`), asserted by a recording spy on the exact call order. `bind` with a **different** uid runs the account-switch work **in one `ModelContext.save()`**: tag the previous uid's anon rows → wipe all three types for the previous uid → clear its `SyncState` → clear the binding → insert the new one. Assert that a throw partway leaves the store byte-identical **via `ModelContext.rollback()`** — SwiftData offers no stronger transaction primitive, so the test is the specification. **The stop condition, precisely:** write the rollback assertion as *"after a throw injected between the `SyncState` clear and the `AccountBinding` insert, re-fetching every `FavoriteVideo`, `SavedPlaylist`, `SubscribedChannel`, `SyncState` and `AccountBinding` in a **fresh `ModelContext` over the same container** returns byte-identical values to a snapshot taken before `bind` was called."* A fresh context is load-bearing: the throwing context's in-memory state is not the question, the store is. That injection point is the hard case because `#Unique<AccountBinding>([\.userId])` upserts at **save** time, not insert time. If the assertion cannot be made to pass with `rollback()` alone, **stop and report** — naming which entity survived and at which injection point — rather than adding a compensating write or widening the test. CF-A-16 is where the answer goes. (The two bugs `:92-112` records: a wipe that skipped `userId == ""` rows let the next user's merge re-tag user A's data to user B, and doing the tagging outside the transaction left a crash window.) The cursor and the rows it advances past commit in **one** save (ruling F3) — assert a single `save()` per page. A dirty local row survives a pull that would overwrite it. A push drains **subscriptions → playlists → favorites** in that fixed order and keeps going past a transient failure, leaving the failing row dirty, while an `.authFailed` **breaks** the drain. `clearDirty` is where `updatedAt` gets its value — assert a pushed row's `updatedAt` equals the server's response value, never a local clock. A PUT answering an archive echo tombstones locally instead of merely clearing.
- [ ] **Step 2: Implement, gate** (expected **+3 mutex declarations, +19 manager declarations**), commit `[FEAT]: iOS SyncManager actor`.

**Acceptance:** the exclusion is pinned before the logic that needs it; the account-switch write is all-or-nothing; the pull loop cannot spin.

---

## Task 24: Sync triggers

**Goal:** The five sites that call the manager, each with a test.

**Files:** Modify `ios/FitrahTube/App/AppContainer.swift` (`sync`), `App/FitrahTubeApp.swift` (foreground), `Features/Auth/AccountSession.swift` (bind/unbind), `Persistence/FavoritesStore.swift`, `Catalog/SubscriptionsStore.swift`, `Catalog/SavedPlaylistsStore.swift` (push-on-change); create `ios/FitrahTubeTests/SyncTriggerTests.swift`.

**Interfaces consumed:** `SyncManager.{bind, unbind, pushDirty, syncNow}` (Task 23); `AccountSession.{state, uid}`, `AccountState` (Task 9); `NetworkMonitor` (Phase 1, `AppContainer.swift:99`); `FitrahTubeApp.isRemoteConfigRefreshDue` (`FitrahTubeApp.swift:98-132`).

**Interfaces produced:** no new types — five wiring sites plus one pure `shouldSyncOnForeground(state:now:last:spacing:) -> Bool`.

- [ ] **Step 1: Write the failing tests.** Sign-in → `bind(uid)` **off** the splash critical path (assert the splash decision does not await it, `SplashFragment.kt:129-141`); foreground → `syncNow()` behind the **same** ≥15 min due-decision as `refreshRemoteConfigIfDue`, and **skipped entirely while `AccountSession.state == .loading` past a 10 s budget** (`AlBunyaanApplication.kt:167-185` — unbounded waiters were accumulating one per foreground), with the budget as an injected duration and no sleeping; a store write calls `pushDirty` exactly once per toggle (all three stores); an `NWPathMonitor` transition to satisfied calls `pushDirty`, and a transition to unsatisfied calls nothing; sign-out calls `unbind`. Extract the foreground rule as the pure `shouldSyncOnForeground` so the view-layer glue is one line (the `isRemoteConfigRefreshDue` idiom).
- [ ] **Step 2: Implement, gate** (expected **+9 declarations**), commit `[FEAT]: iOS sync triggers`.

**Acceptance:** every trigger has a test; the foreground path cannot accumulate waiters; the splash never blocks on a bind.

---

## Task 25: `ApprovalsClient` + My Submissions (`Route.mySubmissions`)

**Files:** Create `ios/FitrahTube/Features/Submissions/ApprovalsClient.swift`, `MySubmissionsViewModel.swift`, `MySubmissionsScreen.swift`, `EditSubmissionSheet.swift`; modify `App/Route.swift`, `Features/Shell/MainShellView.swift`, `Features/Me/MeSignedInView.swift` (the kebab target), `ios/FitrahTubeTests/MainShellRoutingTests.swift`; create `ios/FitrahTubeTests/ApprovalsClientTests.swift`, `MySubmissionsViewModelTests.swift`.

**Interfaces consumed:** `AuthorizedTransport`, `ScriptedTransport`, `AccountError.rateLimited` shape (Task 7); `MeKebabItem.mySubmissions` (Task 13); `ErrorState`, `EmptyStateView`, `PaginationGuard` (DesignSystem/Lists); `Format` (Catalog).

**Strings (all already in the catalog):** the 20 `my_submissions_*` keys. No new keys.

**Interfaces produced:**
```swift
nonisolated struct ApprovalsClient: Sendable {
    init(transport: any HTTPTransport, baseURL: URL, deviceId: DeviceId)
    /// GET api/admin/approvals/my-submissions?status&cursor&limit — TWO shape traps spec §8 names:
    /// the array key is `data`, not `items` (`ApprovalDtos.kt:30-32`), and `submittedAt` is a
    /// Firestore Timestamp OBJECT `{seconds,nanos}` that must also tolerate a plain number or null
    /// (`:48-77`). Android never paginates it (limit 100, cursor nil); this client accepts a cursor
    /// so the screen can, and Android's shape is the initial call.
    func mySubmissions(status: String?, cursor: String?, limit: Int) async throws -> SubmissionPage
    func submit(type: SubmissionType, youtubeId: String, note: String?) async throws   // POST api/admin/registry/{type}
    func updateNote(type: SubmissionType, id: String, note: String) async throws       // PATCH …/{id}/submitter-note
    func deleteSubmission(type: SubmissionType, id: String) async throws               // DELETE …/{id}/submission
}

nonisolated enum SubmissionType: String, Sendable { case channels, playlists, videos }

nonisolated enum SubmissionStatus: String, Sendable, CaseIterable {
    /// FOUR values. The OpenAPI `status` query enum lists only three
    /// (`api-specification.yaml:1929-1933`); the DTO carries the fourth (`ApprovalDtos.kt:21`).
    case pending = "PENDING", approved = "APPROVED", rejected = "REJECTED", requestChanges = "REQUEST_CHANGES"
    static func fromWire(_ raw: String?) -> SubmissionStatus { SubmissionStatus(rawValue: raw ?? "") ?? .pending }
    var labelKey: String {
        switch self {
        case .pending: "my_submissions_status_pending"
        case .approved: "my_submissions_status_approved"
        case .rejected: "my_submissions_status_rejected"
        case .requestChanges: "my_submissions_status_request_changes"
        }
    }
}

nonisolated struct Submission: Sendable, Equatable, Identifiable {
    var id: String; var type: SubmissionType; var title: String?; var thumbnailUrl: String?
    var status: SubmissionStatus; var submitterNote: String?; var submittedAt: Date?
}
nonisolated struct SubmissionPage: Sendable, Equatable { var items: [Submission]; var nextCursor: String? }

nonisolated enum MySubmissionsUiState: Equatable { case loading, loaded([Submission]), empty, error }
```

- [ ] **Step 1: Write the failing client tests (the F1 shape pin).** A canned body whose array key is `data` decodes; one keyed `items` decodes to **empty**, not an error, so the pin says which shape shipped; `submittedAt` decodes from `{"seconds":1756800000,"nanos":0}`, from `1756800000`, and from `null` → nil; an unknown `status` string → `.pending`; the four known ones round-trip; `updateNote` PATCHes `api/admin/registry/videos/{id}/submitter-note`; a 429 surfaces `retryAfterSeconds`; every request carries `X-Device-Id` and the Bearer.
- [ ] **Step 2: Write the failing view-model tests.** Loading → loaded/empty/error; **the `Error` arm renders `ErrorState`** (ruling C13 — Android's is an unimplemented `TODO` at `MySubmissionsFragment.kt:70`); a delete that returns "already reviewed" shows `my_submissions_already_reviewed` and refreshes rather than removing the row optimistically; a successful delete shows `my_submissions_delete_success` and refreshes; `onAppear` refreshes; `PaginationGuard` fires `loadMore` when the loaded rows already fit the screen; `MainShellRoutingTests`: `leafTypeName(for: .mySubmissions) == "MySubmissionsScreen"`. **The kebab's `.mySubmissions` is now enabled and pushes `Route.mySubmissions`** — assert it joins `enabledKebabItems` (Task 13 N-I7).
- [ ] **Step 3: Implement, gate** (expected **+20 declarations**), commit `[FEAT]: iOS My Submissions`.

**Acceptance:** the `data`-key and Timestamp traps are pinned; the Error arm is a real screen, not a TODO.

---
## Task 26: `YouTubeSearchClient` — ruling F1's fifth client

**Goal:** The backend search the Suggest screen runs on. Ruling F1 named four clients; review #1 found this fifth need and the ruling was amended. **This is a backend call to `/api/admin/youtube/search` — never a YouTube redirect.**

**Files:** Create `ios/FitrahTube/Features/Suggest/YouTubeSearchClient.swift`; create `ios/FitrahTubeTests/YouTubeSearchClientTests.swift`.

**Interfaces consumed:** `AuthorizedTransport`, `ScriptedTransport` (Task 7); `DeviceId` (FitrahAPI).

**Interfaces produced:**
```swift
/// F1's FIFTH hand-written client (the ruling named four; no OpenAPI path covers this one).
/// GET api/admin/youtube/search?q&type&pageToken (`YouTubeSearchController.java:26-27` for the
/// mapping + the `@PreAuthorize("hasAnyRole('ADMIN','MODERATOR')")` that makes 403 the role gate,
/// and `:51-56` for the three params), over
/// `AuthorizedTransport` — an admin path, so the Bearer is required and a 403 is the role gate
/// answering, not a network fault.
nonisolated struct YouTubeSearchClient: Sendable {
    init(transport: any HTTPTransport, baseURL: URL, deviceId: DeviceId)
    func search(q: String, type: SuggestType, pageToken: String?) async throws(SuggestError) -> SuggestPage
}

nonisolated enum SuggestType: String, Sendable, CaseIterable {
    case all = "ALL", channels = "CHANNEL", playlists = "PLAYLIST", videos = "VIDEO"
    var labelKey: String {
        switch self {
        case .all: "suggest_type_all"
        case .channels: "suggest_type_channels"
        case .playlists: "suggest_type_playlists"
        case .videos: "suggest_type_videos"
        }
    }
}

nonisolated struct SuggestItem: Sendable, Equatable, Identifiable {
    var youtubeId: String; var type: SuggestType; var title: String
    var thumbnailUrl: String?; var channelTitle: String?
    /// The registry state the backend already knows, so a row renders
    /// suggest_already_in_registry / _already_pending / _already_rejected instead of a Submit button.
    var registryState: String?
    var id: String { youtubeId }
}
nonisolated struct SuggestPage: Sendable, Equatable { var items: [SuggestItem]; var nextPageToken: String? }

nonisolated enum SuggestError: Error, Equatable {
    case forbidden                            // 403 -> suggest_error_not_allowed (the role gate)
    case rateLimited(retryAfterSeconds: Int)  // 429
    case network                              // transport
    case server(status: Int)                  // any other non-2xx -> suggest_error_server
}
```

- [ ] **Step 1: Write the failing shape-pin test (F1).** One canned body decodes into `SuggestPage`; the query carries exactly `q`, `type` and `pageToken`, and **omits `pageToken` when nil**; `type: .all` sends `ALL`; an item with an unknown `type` string is **dropped**, not thrown on (one bad row must not lose the page); 403 → `.forbidden`; 429 carries `retryAfterSeconds` from the body, else the `Retry-After` header, else 60; a 500 → `.server(status: 500)`; a transport throw → `.network`; every request carries `X-Device-Id` **and** the Bearer (`AuthorizedTransport` supplies it — assert the header is present when the host matches). Server-side, `q` is `@NotBlank @Size(max = 200)` and `pageToken` `@Size(max = 2048)` (`YouTubeSearchController.java:52-54`). **The client truncates neither** — an over-long query surfaces as the server's 400, mapped to `.server(status: 400)`, not as a silent trim. Task 22's cursor-id validation is **not** the precedent here: that one exists because an invalid id is a *guaranteed* 400 the client can avoid spending a page on; a long query is real user input whose rejection the user should see.
- [ ] **Step 2: Implement, gate** (expected **+9 declarations**), commit `[FEAT]: iOS YouTube search client`.

**Acceptance:** the fifth client's shape is pinned like the other four; a malformed row never loses a page.

---

## Task 27: Suggest Content + `SubmitContentSheet` (`Route.suggestContent`)

**Files:** Create `ios/FitrahTube/Features/Suggest/YouTubeURLParser.swift`, `SuggestContentViewModel.swift`, `SuggestContentScreen.swift`, `SubmitContentSheet.swift`; modify `App/Route.swift`, `Features/Shell/MainShellView.swift`, `Features/Me/MeSignedInView.swift`, `ios/FitrahTubeTests/MainShellRoutingTests.swift`; create `ios/FitrahTubeTests/YouTubeURLParserTests.swift`, `SuggestContentViewModelTests.swift`.

**Interfaces consumed:** `YouTubeSearchClient`, `SuggestType`, `SuggestItem`, `SuggestPage`, `SuggestError` (Task 26); **`ApprovalsClient.submit(type:youtubeId:note:)` and `SubmissionType` (Task 25 — this task must land after Task 25)**; `MeKebabItem.suggestContent` (Task 13); `ScriptedTransport` (Task 7); `PaginationGuard`, `ErrorState`, `EmptyStateView`.

**Strings (all already in the catalog):** the 15 `suggest_*` keys **and** the 15 `submit_content_*` keys (`_sheet_title`, `_url_hint`, `_invalid_url`, `_detected_video`, `_detected_channel`, `_detected_playlist`, `_pick_category`, `_category_hint`, `_note_hint`, `_note_helper`, `_submit_button`, `_success`, `_conflict`, `_rate_limited`, `_error_generic`). No new keys.

**Interfaces produced:**
```swift
/// `SuggestContentViewModel.kt:76-112`. Only http(s); hosts youtu.be, youtube.com,
/// youtube-nocookie.com and their subdomains; PRECEDENCE v -> list -> /channel/<id> ->
/// /shorts/<id> -> @handle; anything else falls through to a plain (ALL, rawQuery) search.
/// This is NOT a link to YouTube — it parses a URL a user pastes; nothing here navigates.
nonisolated enum YouTubeURLParser {
    enum Parsed: Equatable { case video(String), playlist(String), channel(String), handle(String), query(String) }
    static func parse(_ raw: String) -> Parsed
}
nonisolated enum SuggestUiState: Equatable {
    case idle, loading, results([SuggestItem]), empty, rateLimited(Int), error(messageKey: String)
}
```

- [ ] **Step 1: Write the failing parser table** — 12 rows, each an explicit precedence assertion: `https://www.youtube.com/watch?v=xc7keR2piUM&list=PLx` → `.video` (v beats list); `https://www.youtube.com/playlist?list=PLx` → `.playlist`; `https://youtu.be/xc7keR2piUM` → `.video`; `https://m.youtube.com/channel/UC…` → `.channel`; `https://www.youtube.com/shorts/abc` → `.video`; `https://www.youtube.com/@handle` → `.handle`; `https://www.youtube-nocookie.com/watch?v=x` → `.video`; `ftp://youtube.com/watch?v=x` → `.query` (non-http); `https://notyoutube.com/watch?v=x` → `.query`; **`https://evil.com/youtube.com/watch?v=x` → `.query`** (host suffix matching must be on a dot boundary, never `contains`); a bare word → `.query`; empty → `.query("")`.
- [ ] **Step 2: Write the failing view-model tests** with a stub clock. The query debounce is **300 ms** with `distinctUntilChanged` semantics — assert one search for three keystrokes inside the window, driven by the injected clock, **no real waiting**; a blank query returns to `.idle` with no call; **type chips filter client-side** (`:155-161`) and the previous filter carries into a new search **only** when the backend was asked for `ALL` (`:53-60`); `loadMore` re-checks query/type/token after the suspend and drops a stale generation (`:163-189`); error mapping — `.forbidden` → `suggest_error_not_allowed`, `.rateLimited(s)` → `.rateLimited(s)`, `.network` → `suggest_error_network`, `.server` → `suggest_error_server`. `SubmitContentSheet`: a parsed URL preselects the type and shows the matching `submit_content_detected_*`; an unparseable one shows `submit_content_invalid_url` and disables Submit; a successful `ApprovalsClient.submit` shows `submit_content_success`; a 409 shows `submit_content_conflict`; a 429 shows `submit_content_rate_limited`. `MainShellRoutingTests`: `leafTypeName(for: .suggestContent) == "SuggestContentScreen"`. **The kebab's `.suggestContent` is now enabled and pushes `Route.suggestContent`** — assert it joins `enabledKebabItems` (Task 13 N-I7).
- [ ] **Step 3: Implement, gate** (expected **+24 declarations**), commit `[FEAT]: iOS Suggest Content`.

**Acceptance:** the precedence table is pinned including the host-suffix trap; the debounce is a function of an injected clock; the sheet submits through Task 25's client, not a second one.

---

## Task 28: Import engine — authorizer, paginators, `ImportClient`, the pipeline

**Goal:** Everything the Import screen needs, with **no** Firebase and **no** network in the tests.

**Files:** Create `ios/FitrahTube/Features/Import/YouTubeAuthorizer.swift`, `GoogleYouTubeAuthorizer.swift`, `YouTubeImportSource.swift`, `ImportClient.swift`, `ImportPipeline.swift`; modify `ios/FitrahTubeTests/Support/TestDoubles.swift` (`FakeYouTubeAuthorizer`); create `ios/FitrahTubeTests/ImportEngineTests.swift`.

**Interfaces consumed:** `AuthorizedTransport`, `ScriptedTransport` (Task 7); `FavoritesStore`, `SubscriptionsStore`, `SavedPlaylistsStore` **with V5's `approvalStatus`/`source`/`importedAt`/`channelUrl`/`playlistUrl`** (Task 20); `FirebaseBootstrap.googleClientID` (Task 2).

**Interfaces produced:**
```swift
/// The seam Android built for exactly this reason (`YouTubeAuthManager.kt:44-71`): the layer above
/// tests against a fake with no Google SDK. INCREMENTAL — the scope is requested when the user
/// starts an import, NEVER at sign-in (`:45-46`).
@MainActor protocol YouTubeAuthorizer: AnyObject {
    static var scope: String { get }   // "https://www.googleapis.com/auth/youtube.readonly"
    var isAvailable: Bool { get }
    func authorize() async throws -> String     // an access token
    /// F9: drop the in-memory token. NEVER `GIDSignIn.disconnect()` — that revokes every granted
    /// scope and signs the Google user out of the app.
    func forget()
}

nonisolated enum CandidateType: String, Sendable, CaseIterable {
    case channel = "CHANNEL", playlist = "PLAYLIST", video = "VIDEO"
}
nonisolated struct ImportCandidate: Sendable, Equatable, Identifiable {
    var type: CandidateType; var youtubeId: String; var title: String
    var thumbnailUrl: String?; var channelId: String?
    var id: String { youtubeId }
}

/// YouTube Data API v3 with the OAuth token per-call as `Authorization: Bearer …` and NO api key
/// anywhere (`YouTubeImportApi.kt:16-18` — confirmed; the fork is closed). Three INDEPENDENT
/// paginators; one type's 403 must not suppress the other two (`:36-66`).
nonisolated struct YouTubeImportSource: Sendable {
    init(transport: any HTTPTransport)
    static let maxPages = 40                    // <= 2 000 items per type
    func fetchAll(accessToken: String) async -> (candidates: [ImportCandidate], failedTypes: Set<CandidateType>)
}

nonisolated enum Disposition: String, Sendable {
    case approved = "APPROVED", pending = "PENDING", rejected = "REJECTED", error = "ERROR"
    /// An unknown disposition writes nothing and counts as rejectedOrError, never as approved.
    static func fromWire(_ raw: String?) -> Disposition { Disposition(rawValue: raw ?? "") ?? .error }
}
/// The backend's `ContentItemDto` narrowed to what a local row needs; canonical metadata,
/// preferred over the candidate's when present (`YouTubeImportRepository.kt:187-242`).
nonisolated struct ImportedContent: Decodable, Sendable, Equatable {
    var title: String?; var thumbnailUrl: String?; var channelTitle: String?; var durationSeconds: Int?
}
nonisolated struct ImportResult: Sendable, Equatable {
    var youtubeId: String; var type: CandidateType
    var disposition: Disposition
    var content: ImportedContent?               // non-nil ONLY for APPROVED (`ImportDtos.kt:38-46`)
}

nonisolated struct ImportClient: Sendable {
    init(transport: any HTTPTransport, baseURL: URL, deviceId: DeviceId)
    static let batchSize = 200                  // == the server's @Size(max = 200)
    /// POST api/account/import/resolve. 429 carries `retryAfterSec` (a 1 000 items/user/day sliding
    /// window, `SubmissionRateLimiter.java:31-32`).
    func resolve(_ items: [ImportCandidate]) async throws -> [ImportResult]
}

nonisolated enum ImportPhase: Sendable, Equatable { case resolving, writing, done }
nonisolated struct ImportSummary: Sendable, Equatable {
    var added: Int; var sentForReview: Int; var skipped: Int; var alreadyPresent: Int
    // Amended by Task 28's fix round (review I1) and its re-review: the "was this run complete?"
    // signal used to exist ONLY in the transient DONE `progress` emission, so a screen that keeps
    // the summary and drops the last callback would tell a user whose connection died after chunk
    // one "200 added" and nothing else. `total` is the FRESH count (`candidates.count -
    // alreadyPresent`), so the predicate is `processed < total` and needs nothing the caller holds.
    var processed: Int; var total: Int
    var rateLimited: Bool
}

@MainActor struct ImportPipeline {
    init(client: ImportClient, favorites: any FavoritesStore, subscriptions: any SubscriptionsStore,
         playlists: any SavedPlaylistsStore, now: @escaping () -> Date)
    /// Dedupes, chunks at `ImportClient.batchSize`, writes per the APPROVED/PENDING matrix, stops
    /// on 429. `progress` is called with the ACTUAL processed count, never the total (`:152-154`).
    func run(_ candidates: [ImportCandidate],
             progress: @MainActor (ImportPhase, Int, Int) -> Void) async -> ImportSummary
}
```

- [ ] **Step 1: Write the failing paginator tests.** A paginator stops at `maxPages = 40`; a server repeating a `pageToken` is stopped by the **seen-token set**, not by the page cap; a 403 on `playlists` leaves `failedTypes == [.playlist]` while subscriptions and videos still return their items; the three mappings are pinned — subscriptions take `snippet.resourceId.channelId` (**not** `item.id`, which is the *subscription* id), playlists take `item.id`, liked videos take `item.id` with `snippet.channelId`; the request paths are `subscriptions?part=snippet&mine=true&maxResults=50`, `playlists?part=snippet&mine=true&maxResults=50`, `videos?part=snippet&myRating=like&maxResults=50`; **no request carries a `key=` query parameter** (assert the absence).
- [ ] **Step 2: Write the failing pipeline tests.** Dedupe against the local stores uses a deleted-agnostic, status-agnostic existence check (a soft-deleted row still counts as present) and those items are **never sent to the backend**, counting as `alreadyPresent`; 401 candidates chunk into 200 + 200 + 1; **a 429 breaks the loop** (`rateLimited = true`) and chunks already written persist and dedupe on retry; per result — `APPROVED` writes with the canonical `content` metadata preferred over the candidate's, `approvalStatus = "APPROVED"`, `source = "USER_IMPORT"`, `importedAt = now`; `PENDING` writes with candidate metadata and `approvalStatus = "AWAITING"`, incrementing `sentForReview` **only when a row was actually written**; `REJECTED`/`ERROR`/unknown write nothing; the canonical URLs stored are `https://www.youtube.com/channel/<id>` and `https://www.youtube.com/playlist?list=<id>` (**stored data, never a navigable affordance**); a PENDING video's `channelName` is deliberately `""`, **never** the `UC…` id (`:233,288`); the final progress emission uses the **actual** processed count, so a 429-truncated run is not painted as complete; the subscription cap is **bypassed for the import path only** (`SubscriptionRepository.kt:121-136`) — assert an import can exceed 30 while a manual subscribe at 30 still throws `.capReached`.
- [ ] **Step 3: Implement.** `GoogleYouTubeAuthorizer` wraps `GIDSignIn.sharedInstance.addScopes([Self.scope], presenting:)` then reads `user.accessToken.tokenString`. The token is held **in memory only** — an access token expires within the hour, and the Keychain buys persistence nothing here wants (spec §13's "in memory/keychain" is satisfied by the first; CF-A-10). `forget()` clears it.
- [ ] **Step 4: Gate** (expected **+27 declarations**), commit `[FEAT]: iOS YouTube import engine`.

**Acceptance:** dedupe, chunking and the 429 stop are pinned against fakes; no API key exists anywhere; the authorizer is fully fakeable.

---

## Task 29: Import screen, the caution gate, revoke (`Route.importFromYouTube`)

**Files:** Create `ios/FitrahTube/Features/Import/ImportUiState.swift`, `ImportViewModel.swift`, `ImportFromYouTubeScreen.swift`; modify `App/Route.swift`, `Features/Shell/MainShellView.swift`, `Features/Me/MeSignedInView.swift` (the kebab target), `ios/scripts/convert-strings.py`, `ios/FitrahTubeTests/MainShellRoutingTests.swift`; create `ios/FitrahTubeTests/ImportViewModelTests.swift`.

**Interfaces consumed:** `YouTubeAuthorizer`, `YouTubeImportSource`, `ImportPipeline`, `ImportCandidate`, `CandidateType`, `ImportSummary`, `ImportPhase` (Task 28); `FakeYouTubeAuthorizer` (Task 28); `MeKebabItem.importYouTube` (Task 13); `ErrorState`.

**Strings:** ports the 26 `import_*` keys. **Authored under `EXTRA_KEYS`** (iOS-new — Android has no revoke affordance at all, ruling F9; WHAT never why; the link is to **Google**, never YouTube):
```python
    "import_revoke_action": {"en": "Remove YouTube access", "ar": "إزالة وصول يوتيوب", "nl": "YouTube-toegang intrekken"},
    "import_revoke_done": {
        "en": "FitrahTube no longer has access to your YouTube data.",
        "ar": "لم يعد لدى فطرة تيوب صلاحية الوصول إلى بيانات يوتيوب الخاصة بك.",
        "nl": "FitrahTube heeft geen toegang meer tot je YouTube-gegevens.",
    },
    "import_revoke_manage_link": {
        "en": "Manage app permissions in your Google Account",
        "ar": "إدارة أذونات التطبيقات في حساب Google الخاص بك",
        "nl": "Beheer app-machtigingen in je Google-account",
    },
```

**Interfaces produced:** `ImportUiState { idle | authorizing | needsConsent | fetching | review(candidates: [ImportCandidate], selected: Set<String>, partialFailures: Set<CandidateType>) | importing(ImportPhase, processed: Int, total: Int) | done(ImportSummary) | error(messageKey: String, retryable: Bool) }`; `ImportViewModel` — `start()`, `toggle(_:)`, `setGroupSelected(_:_:)`, `confirmImport()`, `retry()`, `revoke()`.

- [ ] **Step 1: Write the failing state tests.** `selected` starts as **every** `youtubeId` (`ImportViewModel.kt:209-213`); **zero candidates → `.error("No items found", retryable: !failedTypes.isEmpty)`, never an empty `.review`** (`ImportUiState.kt:22-26`); `confirmImport()` twice while a job is active runs once; the first progress emission is a **fresh zero**, not the pipeline's last value, so a re-import does not flash the previous run's DONE frame (`:141-144`); `needsConsent` is **sticky** across a state restore so a second consent prompt cannot be launched (`:82-86`); `CancellationError` is rethrown; `retry()` == `start()`; **`revoke()` calls `authorizer.forget()` and NEVER `disconnect()`** — a spy asserts the absence, because ruling F9's whole point is that `disconnect()` would sign the Google user out.
- [ ] **Step 2: Write the failing caution-gate test.** The **Sharī'ah caution gate** is shown before `confirmImport()` runs, and dismissing it leaves the state at `.review` with **no** import started (`ImportFromYouTubeFragment.kt:199-217`). `import_caution_title/_message/_continue` is substantive religious copy and is **not** chrome to skip.
- [ ] **Step 3: Implement.** `state → view`: idle/authorizing/fetching → a spinner with the matching `import_youtube_loading_*` caption; review → grouped sections with per-group and per-item checkboxes, an `import_youtube_partial_failure` banner when `partialFailures` is non-empty, and an Import button behind the caution `.alert`; importing → a progress view with the phase caption; done → the summary plus `import_youtube_done_rate_limited` when truncated; error → `ErrorState` with a retry when `retryable`. A "Remove YouTube access" row at the bottom whose confirmation shows `import_revoke_done` and a `Link` to `https://myaccount.google.com/permissions` — **a Google URL, never a YouTube one**. `MainShellRoutingTests`: `leafTypeName(for: .importFromYouTube) == "ImportFromYouTubeScreen"`. **The kebab's `.importYouTube` is now enabled and pushes `Route.importFromYouTube`** — `enabledKebabItems` reaches its final set (Task 13 N-I7).
- [ ] **Step 4: Regenerate strings, gate** (expected **+19 declarations**), commit `[FEAT]: iOS Import from YouTube screen`.

**Acceptance:** the caution gate cannot be bypassed; revoke never signs the user out; every state has a rendered arm.

---

## Task 30: The one-time import offer + the Content/Pending tabs (fork F14)

**Goal:** The two Me-tab surfaces that only become honest once something can produce an `AWAITING` row.

**Files:** Modify `ios/FitrahTube/Features/Me/MeViewModel.swift`, `MeSignedInView.swift`, `ios/FitrahTube/Persistence/FavoritesStore.swift`, `ios/FitrahTube/Catalog/SubscriptionsStore.swift`, `ios/FitrahTube/Catalog/SavedPlaylistsStore.swift` (one protocol requirement each); create `ios/FitrahTubeTests/MeAwaitingTabsTests.swift`; modify `ios/FitrahTubeUITests/ScreenshotTests.swift`, `ios/scripts/screenshots.sh`.

**Interfaces consumed:** `MeViewModel`, `MeSignedInView` (Task 13); `SettingsStore.importOfferShown` (`SettingsStore.swift:23,44,57` — Phase 1 already persists it); `Route.importFromYouTube` (Task 29); the V5 `approvalStatus` column on all three models (Task 20); `MeFeedRepository` (Task 16).

**Strings (all already in the catalog):** `me_awaiting_count`, `me_awaiting_pending_label`, `me_awaiting_section_title`, `me_tab_content`, `me_tab_pending`, `import_offer_title`, `import_offer_message`, `import_offer_positive`, `import_offer_negative`. No new keys.

**Interfaces produced:**
```swift
/// One requirement on each of the three store protocols (`FavoritesStore`, `SubscriptionsStore`,
/// `SavedPlaylistsStore`). `items` deliberately EXCLUDES awaiting rows (`FavoritesStore.swift:7-11`
/// since Phase 1, and Task 20's matching filter on the other two), so the count CANNOT be derived
/// from it — this is a separate `fetchCount` over the same uid with
/// `approvalStatus == "AWAITING" && isRemoved == false`. Three lines per store, no new type.
var awaitingCount: Int { get }

extension MeViewModel {
    /// The sum of the three stores' `awaitingCount` — a LIVE count, not a snapshot:
    /// `ImportGraduationService` flips these server-side when an admin reviews the id
    /// (`ImportGraduationService.java:70,81,120`), and the next sync brings the change down.
    var awaitingCount: Int { get }
    /// The tab bar is hidden ENTIRELY at zero — with an empty queue a two-tab bar is permanent
    /// chrome over nothing (`MeFragment.kt:352-368`).
    var showsTabs: Bool { awaitingCount > 0 }
    var selectedTab: MeTab      // .content | .pending
}
nonisolated enum MeTab: Int, Sendable { case content = 0, pending = 1 }

/// Pure, with its own rationale on Android (`MeFragment.kt:51-59`): an empty feed under the
/// Pending tab is not the feed's empty state.
nonisolated func shouldShowFeedEmptyState(feedIsEmpty: Bool, selectedTab: MeTab) -> Bool
```

- [ ] **Step 1: Write the failing tests.** The offer is shown only when signed in and only when `SettingsStore.importOfferShown == false`, and the flag is **set immediately, before presenting**, so a second launch cannot double-fire (`MeFragment.kt:428-445`, `:433`); accepting it pushes `Route.importFromYouTube` and declining does not. `showsTabs` is false at zero awaiting rows and true at one; the label carries the count through `Format`; `shouldShowFeedEmptyState` is the 4-row truth table; switching tabs preserves each tab's scroll offset and a same-tab reassignment is a **no-op**, so a background sync landing the first pending item cannot jerk the feed to the top (`:387-406`). Each store's `awaitingCount` is scoped to `currentUserId`, ignores `isRemoved` rows, and is zero for a store holding only APPROVED rows; `MeViewModel.awaitingCount` is their sum.
- [ ] **Step 2: Implement.** The offer is an `.alert` fired from the Me tab's `.task`, after the burst-if-stale. Tabs are a segmented control above the feed, rendered only when `showsTabs`.
- [ ] **Step 3: Screenshots.** Add `-fitrah-seed-submissions` to the DEBUG launch hooks (the `-fitrah-seed-offline` shape) and an `import-review` plus a `me-pending-tab` row to `ScreenshotTests.screens`; run the block by hand; eyeball en + ar.
- [ ] **Step 4: Gate** (expected **+14 declarations**), commit `[FEAT]: iOS import offer + Me pending tabs`.

**Acceptance:** the offer fires at most once, ever; the Pending tab appears only when there is something pending.

---

## Task 31: PART B GATE — whole-branch review + the 9-stage pipeline

**Interfaces consumed:** everything Tasks 1–30 produced.

- [x] **Step 1:** Run the 9-stage review pipeline from `AGENTS.md` over the **whole Phase 4 diff** (Tasks 1–30). If a stage cannot run, say which and why.
- [x] **Step 2:** `KEEP_RESULTS=1 bash ios/scripts/test.sh` from a clean **build** — `rm -rf ios/DerivedData/Build` only, **never `SourcePackages`** (Task 19 Step 2's reasoning). Report the measured wall time against Task 1's `GATE_BASELINE`. **Both destinations, whatever the per-task rule became.** If Task 1 recorded `GATE_BASELINE` over 260 s, the sharding decision was made before Task 2 — apply it, do not re-derive it here.
- [x] **Step 3:** `bash ios/scripts/screenshots.sh`; review every Phase 4 screen across the spec §14 matrix.
- [x] **Step 4:** Walk the Acceptance tiers below and record, for each Tier 2 item, whether it was run and what was observed. Fix, re-gate, commit `[FIX]:`.

**Acceptance:** the pipeline is clean; the gate is green under 300 s; Tier 3 is honestly reported as USER-BLOCKED, never claimed.

**Gate record (2026-09-11):** Part B base `583017c2`; gate sha `d641cac8`; stage reports in the `.superpowers/sdd/2026-09-02-ios-phase4-accounts/` workspace (`task-31-stage*.md`, `task-31-stage9-round-N.md`, `cubic-partb-round-N.json`). Stage 1 (bloat pre) 0/2/17; stage 3 (cold review) 0/6/7; stage 4 (security) 0/3/6; stage 5 (Codex 0.153.4 adversarial + a whole-branch pass) 2/7/9; stage 6 consolidation `task-31-stage6-consolidated.md`; stage 7 fix commits `5a183593`, `c849d4b2`, `c79cc4b7`; stage 8 (bloat post) 0/1/10, all taken into `c79cc4b7`. Stage 9: gstack `/review` has no non-interactive entry point (substitute: a diff-scoped read of the stage 7 commits each round); Cubic 8 rounds: round 1 at `c849d4b2` 1 P1 (the DELETE echo, fixed in `c79cc4b7`); round 2 at `c79cc4b7` 1 P1 (a regression of round 1's P3 fix, fixed in `a56bbbdb`); rounds 3 and 4 at `a56bbbdb` CLEAN of P0/P1 (their P2 residue → `5cf72946`); round 5 at `5cf72946` 1 P1 (the device-wide Google SDK session, fixed in `ae98dae7`), round 6 CLEAN; **rounds 7 and 8 at `ae98dae7` both CLEAN of P0/P1 — two consecutive clean rounds on the final tree, the bar met**. Every round's P2/P3 residue is ruled in `task-31-stage9-round-N.md` and carried as CF-B-13..39 below. Stage 2: the warm Debug gate ran 182-263 s on every fix commit and `RELEASE=1` built (276 s incl. its Debug leg); the CLEAN-build Debug gate (`rm -rf ios/DerivedData/Build`) hit the 300 s watchdog on all five attempts, including one on a QUIET host (load 1.8 at start): measured outside the watchdog, the raw clean `build-for-testing` is 153 s and the warm iPhone-only unit pass 171 s, so the clean-build gate is ~320-340 s on this host today against Part A's recorded 141 s (CF-B-22). Step 3: the full `screenshots.sh` matrix was run at `c849d4b2` up to the Phase 2c block (19 blocks SUCCEEDED, 1 Phase 2 player assertion FAILED — `testPlayerB1Task10IPhone`, untouched by Part B, re-run alone at the end), then stopped for the window budget; the Phase 4 + Part B blocks (incl. the new end-to-end Continue tap, `testImportCautionContinueStartsTheRun`) ran at the final sha. Step 4: Tier 2 NOT RUN — no `ACCOUNT_LIVE` harness exists in the tree, no Firebase CLI, no service-account file (CF-B-21); Tier 3 unchanged, nothing claimed.

---

## Task 32: CF-A-21 — the Firebase iOS SDK bump (11.15.0 → 12.19.1)

**Interfaces consumed:** `FirebaseBootstrap` and `FirebaseAuthClient` — the only two files in the app that name a Firebase type — and the `AuthErrorCode` table the second feeds.

- [x] **Step 1:** Choose the target from the CHANGELOG delta, not from "latest". Read every FirebaseAuth and FirebaseCore entry between 11.15.0 and the candidate and check each against what this app actually calls.
- [x] **Step 2:** Re-verify, by reading the SDK sources, every claim the app makes about Firebase **internals** (CF-A-35). A bump is the only moment these get checked: no local test reaches them.
- [x] **Step 3:** Bump `exactVersion`, re-resolve by hand — `project.yml`'s own instruction, because a cold resolve does not fit the watchdog — and commit the resulting `Package.resolved`.
- [x] **Step 4:** Warm BOTH derived-data trees outside the watchdog (Debug and `DerivedData-Release`), then gate 2× with `RELEASE=1`.
- [x] **Step 5:** Run the 9-stage pipeline over the diff.

**Acceptance:** the suite is green with no source change forced by the SDK; every SDK-internal claim in the code is re-read and stamped with the version it was verified at; Debug and Release both build.

**Task record (2026-09-14/15):** Base `5cdcde6d`; task commit **`e820d01d`**. Target chosen from the CHANGELOG delta, not from "latest": 12.19.0 is the last planned 12.x minor and .1 its artifact-only patch; no 13.x tag exists. Toolchain floors cleared — Xcode 26.3 / Swift 6.2.4 against 12.12.0's Xcode 26.2 + Swift 6.2.3 and 12.15.0's swift-tools 6.1, iOS 18 against 12.0.0's iOS 15. **Stage 1** (bloat pre): nothing to strip — one version string, a regenerated lockfile, two comment edits. **Stage 2**: warm Debug `build-for-testing` 1 m 53 s with zero errors; Release 2 m 21 s; direct `xcodebuild test` on both destinations TEST SUCCEEDED, 0 failures — **Firebase 12.19.1 forced no app source change at all**. The hand re-resolve took 81 s and moved the pin graph by exactly four entries (Firebase 11.15.0→12.19.1, GoogleAppMeasurement 11.15.0→12.19.0, ads-on-device-conversion 2.3.0→3.7.0, swift-protobuf dropping out — 26 pins, was 27); GoogleSignIn held at 8.0.0. **Stages 3 / 4 / 5** (cold review, security/CSO, Codex 0.153.4 adversarial) all ran against the commit and all three independently confirmed the four claims they were asked to refute — the security pass proved the coalescer's account isolation structurally (`TokenRefreshCoalescer` is a `private let` of `SecureTokenServiceInternal`, one per `SecureTokenService`, which is 1:1 with a `User` and so with a uid). **The bump introduced exactly one real defect**, fixed at **stage 7**: 12.12.0 turned `User.uid` from a stored property into `propertyAccessQueue.sync { _uid }`, and `idToken(forceRefresh:)` was reading it inside `lastRefusal.withLock` — blocking inside an `os_unfair_lock` critical section, parking the one process-global lock that guards the ruling-C13 verdict on a queue this app does not own. The uid is now read once, before the lock. Review also caught four factual errors in my own prose, each re-verified before accepting: `Auth.swift` was never renamed (already at `Sources/Swift/Auth/` in 11.15.0), `currentUser` is not unconditionally nil after a forced sign-out (`signOutByForce` guards on the uid), 12.19.1 is a patch not a minor, and "eleven" sat above a ten-member enum. **Stage 8** (bloat post): the fix round added no code and replaced two property reads with one. **Stage 9**: gstack `/review` still has no non-interactive entry point (substitute: the three reviewer passes above, each diff-scoped); Cubic round 1 at `e820d01d` **clean**, round 2 **0 P0/P1** with one P2 — that this very document still called CF-A-21 an open HOLD at 11.15.0 — fixed by this commit, then re-run. **Gate: EXIT 0 twice on `e820d01d`** (4 m 25 s, 5 m 09 s), `RELEASE: built` both times, `Package.resolved` drift clean. Two earlier attempts hit the 300 s watchdog, both being the FIRST run after a source change: the module recompile plus two cold simulator boots does not fit, the warm re-run does — a second data point for CF-B-22.

**Runtime proof, past compile-and-link.** The 1532-test suite runs inside the FitrahTube **app host**, so every test loaded the binary with FirebaseAuth 12.19.1 linked and dyld resolved every symbol. Beyond that, a **throwaway, never-committed** test (deleted before the final gate) called `FirebaseApp.configure(options:)` with synthetic options and exercised `Auth.auth()`, `currentUser`, `signOut()` with nobody signed in, `addStateDidChangeListener` and `AuthErrors.domain` in the simulator: TEST SUCCEEDED. That is the first time Firebase code has **executed** in this project (CF-A-28). It uses no real project and proves nothing about a real sign-in, which stays USER-BLOCKED.

**Not fixed here, deliberately** — all pre-existing, all unreachable while `UnavailableAuthClient` is what the app builds without a plist: CF-A-44 (the wipe's delivery side is untagged), CF-A-45 (a consumed verdict is dropped when the retry throws), CF-A-46 (12.19.0's locked-keychain behaviour). Threading identity through the wipe delivery path is a design change with its own test surface and its own gate — the mirror image of "do not bump inside a feature task".

---

## Task 33: CF-A-45 + CF-A-44 (first half) — the terminal verdict keeps its account

Both defects were surfaced by Task 32's review and are on the ruling-C13 wipe path. **CF-A-45** (`d82bfd27`): the verdict was consumed inside the token closure but published only after `BearerRetry.send` returned, so a throwing re-send discarded it — now published from a `defer`. **CF-A-44, transport route** (`3631a7d5`, residue `af49e500`): the verdict was uid-tagged where RECORDED and untagged where DELIVERED — `AccountStatusSignal{event, uid}`, `AuthorizedTransport` publishes its `signedFor`, `AccountSession.handle(_:for:)` refuses a mismatch. **The first cut of that guard was wrong in the dangerous direction** (it compared against `user`, which Firebase has already nil'd inside the refused mint, so it refused exactly the deletions the wipe exists for); three reviewers briefed to BREAK the fix caught it and `lastKnownUid` repaired it. Pins `4719c529`, final P3s `1db706a2`. Pushed 2026-09-20 with the rest of the branch.

---

## Task 34: CF-A-44 (second half) — a pending wipe is redeemed against the right device

**Interfaces consumed:** `AccountSession.resumePendingDeletion()`, `DeletionMarking`, `LocalAccountWiper`, `RootView`.

- [x] **Item 1:** a DURABLE `lastSignedInUid`, because the in-memory `lastKnownUid` is nil at launch — exactly when redemption runs (`576a08ac`).
- [x] **Item 2:** the `land()` window — a verdict arriving before `start()` has observed the sign-in is no longer refused (`f0f79d14`).
- [x] **Item 3:** CF-A-48 — `RootView.route`, a static sibling of the existing testable `act(on:session:alert:)` (`57a8b75d`).
- [x] **Pipeline + three fix rounds:** `38f70350`, `fa89c293`, `ef190518`; test-wait root cause `42400df7`, `c64cf55c`.

**Acceptance:** with a pending marker for A, no launch state destroys another account's or the guest's library; a deleted account's own rows are always reachable; every guard is red-captured in BOTH directions.

**Task record (2026-09-20).** Base `1db706a2`. **The rule that finally held:** the device-wide `wipe()` runs ONLY on POSITIVE evidence that the pending account holds the device — `(signedIn ?? lastSignedInUid) == pending`; every other case, a nil holder included, takes `LocalAccountWiper.wipeRows(of:)`, a uid-scoped delete of four models that cannot hurt anyone else's data. It took three fix rounds to get there because the first design made the device-wide wipe the FALL-THROUGH DEFAULT, so every path that left the holder empty inherited permission to destroy everything: round 1 dropped the marker where it should have deleted by uid (the rows carry `userId`; "cannot be performed any more" was false); round 2, on my instruction, nil'd the holder in `terminateAgeIneligible()` and thereby routed a stale marker to a device-wide wipe of a live library; round 3 inverted the default. **Two mistakes of mine, both caught by review and both now pinned:** I removed the implementer's `user == nil` conjunct from the `fetch` seed reasoning only about the refusal direction, which let a resumed seed overwrite the latch with a REPLACED account and admit its verdict; and I asserted a "locked device" rationale without a probe (now one sentence labelled UNVERIFIED — see CF-A-46). **Verified, not assumed:** `SyncManager.switchAccount` already deletes the previous uid's rows on the next account's first bind, so `wipeRows` is the durable BACKSTOP for when that bind never runs, not the only mechanism; `wipeRows` must NOT delete `AccountBinding`, because with no binding `SyncDecisions.bind` returns `.merge`, which tags guest-era anonymous rows to the NEW uid and uploads them; and a batch `delete(model:where:)` hits the store before `save()` and `rollback()` cannot undo it (settled by a probe). **Pipeline:** stage 1/8 bloat audit (`code-upgrade`), stage 3 cold review, stage 4 security — three read-only agents, none allowed to build, all briefed to break the change; **stage 5 BLOCKED** — Codex 0.153.4 hit its usage limit (reset 2026-09-21), substitute = a fresh read-only adversarial agent run against the post-fix tree, which found the round-2 regression; stage 9 Cubic below. Every commit gated; final tree 2× EXIT 0 with `RELEASE: built`.

**The gate went unstable mid-task and the cause is worth keeping.** After fix round 2, two of three gate runs failed — a different test each time, always `wipes → 0`. My theory (main-actor contention) was REFUTED by measurement: the main actor is idle in the gate shape (~0.08 ms/turn). The actual mechanism: **a yield COUNT is a wall-clock deadline in disguise** — 500 yields on an idle main actor is ~20-100 ms — and a DETACHED task needs a cooperative-pool thread whose start latency, with in-process parallel tests across two simulators, has a 2.5-9 ms tail per hop. Discriminated by serialising Swift Testing (`TEST_RUNNER_SWT_NO_PARALLEL=1`; `-parallel-testing-enabled NO` does NOT serialise it): the failures vanish. Fixed in the shared helper as two shapes — `yieldUntil(timeout:)` for a POSITIVE wait (condition first, clock as the ceiling) and `yieldExpectingNothing(_:)` for a NEGATIVE one (cheap, count-bounded). NOT explained: what moved the rate from 0 in ~23 runs to 2 in 3. Also learned: `-only-testing:FitrahTubeTests` on ONE simulator is a different regime (a main-actor turn costs SECONDS) and cannot reproduce gate failures.

---

## Task 35: CF-A-53 — a running deletion never harms the next user

**Why now:** the owner's `GoogleService-Info.plist` landed 2026-09-20 16:46, so the app builds the real `FirebaseAuthClient` and every defect parked as "unreachable without the plist" became reachable that afternoon. Commit `f2347541`.

**Record (2026-09-20).** The DETACHED `performDeletion` awaited the unbind, the device-wide wipe, `deleteUser()` and the session drop with NO identity check; if B signed in while A's cleanup was suspended, A's task wiped B's device, signed B out and — proven by test — deleted B's Firebase CREDENTIAL (`deletingFirebase` is a session-level flag never cleared by `.signedIn`). Now, after every `await`, the cleanup re-asks "has a DIFFERENT account taken over since the latch?" from three reads — who Firebase holds NOW, `user`, `lastKnownUid` — and on a takeover pays the debt with `wipeRows(owed)` only. **The front door** (found by the adversarial review, invisible to those checks): `DeleteAccountViewModel` named nobody after the server's 204, and that DELETE outlives its screen, so A-confirms → A-signs-out → B-signs-in → 204-lands took a FRESH latch for B. The view model now captures the uid before the await, and `handleDeletion` refuses a named account that is neither `user` nor `lastKnownUid`. The cleanup's final announcement is ATTRIBUTED, reversing a pin from `4719c529` whose reasoning predated `lastKnownUid`. Every guard red-captured in BOTH directions. Pipeline: gate 2× EXIT 0 with `RELEASE: built`; stages 3+4+5 by one read-only adversarial agent (Codex still usage-limited), whose findings became round 2; Cubic below. Same day: `b748fd5b` landed the owner's nineteen report-screen phrases in Arabic and Dutch (done in a second session, left uncommitted, verified and committed unchanged).

## Task 36: CF-A-55 (a) + (c) — the wiper re-checks the holder before it deletes

**Record (2026-09-21).** Commit `55d893f4`. **(c)** `LocalAccountWiper.wipe()` is device-wide and its two offline awaits sat between the caller's takeover check and its synchronous deletes, so an account that signed in, BOUND and pulled inside them lost its rows, `SyncState` and `AccountBinding` with `boundUid` still naming it. The wiper is now `wipe(unlessTakenOver:)` — NO default, so skipping the check does not compile — and asks immediately after its last await with no suspension before the deletes; taken over, it deletes nothing and the caller pays by uid. The session's synchronous half is `observedTakeover(of:)` (`user`, `lastKnownUid`; the owed account coming back is NOT a takeover). **Round 2, found independently by BOTH reviewers:** the first cut handed the launch-path `resumePendingDeletion` a constant `{ false }`, leaving the identical hole one function over (`start()` runs from `RootView`'s `.task` with the UI live); it now passes the same check and falls through to the existing by-uid arm. This only ever DOWNGRADES a device wipe; the positive-evidence rule is untouched. **(a)** the refusal arm records its debt BEFORE the scoped delete (the file's marker-before-work convention), only into a free slot, never an empty uid. **Not built, argued and attacked:** the forced re-bind — binding requires `.signedIn` to have been observed, which sets `user`/`lastKnownUid`, so a bound account always trips the check; the cold reviewer could not break it. **Residue, documented inline:** an account Firebase holds that the session has not yet observed is invisible to the synchronous check (it has not bound, so no sync hole, first bind refills from cursor 0); the offline library is gone before the check (CF-A-50). Pipeline: gate EXIT 0, `RELEASE: built`, 1567 tests / 0 failures; every guard red-captured and mutation-checked incl. a compile-failure mutation for the removed default; stages 3 and 4+5 by two read-only agents (Codex usage-limited until 13:07), stage 7/8 re-review clean; Cubic 2 rounds, 0 P0/P1 (one P3 split doc comment, fixed).

---

## Task 37 + Task 38: App Store readiness quick fixes, and the verification mail the server never sent

**Record (2026-09-22).** Task 37 = `a21d6b1a`: About links → `app.fitrahtube.com` (owner directive 2026-09-21: only fitrahtube.com exists; website row removed as on Android), `PrivacyInfo.xcprivacy` disk-space E174.1, `associated-domains` entitlement (portal capability still OWNER), AASA advertises the `/api/` share forms (`*` spans slashes; the parser rejects deeper URLs), `ios/scripts/write-local-xcconfig.sh` (never echoes a value; refuses trackable paths). **THE FIRST REAL SIGN-IN RAN the same night:** Google → favourites + playlists → sign out → sign in → preferences retained, on the live server. Two things broke first and are now recorded: the gate's simulator build is UNSIGNED so Firebase's keychain fails (-34018) and every provider says "Something went wrong" — a manual test needs an ad-hoc signed build; and the gate's `xcodebuild test` installs its test host over the owner's simulator app mid-test. Task 38 = `f5a54084`: `POST /api/account/send-verification-email` answered 200 from a disabled/failing mailer, so neither app ever fell back to Firebase's mailer and the owner's email account sat unverified; now 503 `MAIL_UNAVAILABLE`, cooldown recorded either way. Effective on DEPLOY. Pipeline: gate EXIT 0 `RELEASE: built` 1569/0 (first run EXIT 124, zero failures); backend 997/0; two read-only review rounds; Cubic below.

## Task 39–41: CF-A-51, CF-A-55 (g), CF-A-50, CF-A-57 — the last of the deletion carry-forwards

**Record (2026-09-22).** **Task 39** `f3a42e7a`+`4f931652`: `marker.lastSignedInUid` is written once, after `/me` succeeds (trade stated inline: an account whose `/me` never succeeded here and which signed out before a relaunch is not a holder). **Task 40** `ba3a9c7a`: an in-wipe takeover abort skips only the row/binding/re-scope step and still sweeps history, feed caches, image caches and the device id; the newcomer's per-uid resend-cooldown key is spared. **Task 41** `be06e16c`: `FavoritesSchemaV6` gives `OfflineItem` an owner (guest `""`; the default MUST be a property initializer or the lightweight migration fails validation, NSCocoaErrorDomain 134110 — proved by an on-disk V5→V6 test through the real plan); `wipeRows(of:)` is async and pays that uid's offline rows and files first; a failed id fetch keeps the marker. Library stays device-wide. **CF-A-57** `e07ffb57` (backend): admin password-reset answers 503 `MAIL_UNAVAILABLE` truthfully, no link minted with mail off, verification pre-checks the mailer. Each: adversarial review (safe 80–88 %), Cubic two rounds with no P0/P1. **Cubic P2 at Task 41, ruled CF-A-49:** the device-wide offline teardown that precedes the takeover check still removes an arriving account's downloads — the shared-device product question, owner-only; the check must stay after the last await (CF-A-55 (c)).

## Acceptance tiers — what proves what

**Tier 1 — hermetic, the gate on every task.** `KEEP_RESULTS=1 bash ios/scripts/test.sh`, tests against fakes only: no Firebase, no network, no wall-clock sleeps. This is spec §15 row 4's stated gate ("tests against fakes") and it is reachable **today**, with no plist and no Team ID. It covers: the copy net; the `AuthErrorCode` table; `BearerRetry`'s seven behaviours plus the two adapter facts on each transport; each of the five hand-written clients' shape pins; the `SplashRouter` matrix including §13's verification rule; every ViewModel against `AppContainer.fake()`; the bootstrap validators; the wipe order; the V5 migration; the whole sync decision surface incl. the stalled-cursor guard, the null-body push, the `isRemoved`↔`deleted` codec and the actor exclusion; import dedupe/chunking/429-stop and the `ImportUiState` transitions; the `Route`/`MainShellView` arms; and the screenshot rig on all three simulators in en/ar.

**Tier 2 — live, needs only the dev backend on `localhost:8080` with the Firebase emulator.** Gated by an env var (`ACCOUNT_LIVE=1`, the `OFFLINE_LIVE` precedent), **skipped by default so the gate stays hermetic**. Run at Tasks 7, 9, 24 and once at Task 31:
1. `GET /api/account/me` against a real backend decodes into `AccountMe`, including a lazily created doc's `PENDING_PROFILE` (`AccountController.java:135-207`).
2. `POST /api/account/profile` with a DOB under 13 returns **422 `AGE_INELIGIBLE`**, and with a valid one returns an `AccountMe` — the two branches Task 12 maps.
3. `POST /api/account/send-verification-email` twice inside 60 s returns **429 `RATE_LIMITED`** (`AccountController.java:48,112-116`).
4. A `GET /api/account/sync` round trip: PUT three rows, pull them back, assert cursors advance and the loop terminates; then re-pull with the persisted cursor and assert **zero** rows come back (the exhausted branch, not the stalled one).
5. An **archive echo**: PUT a row whose parent is archived and assert the response's `deleted: true` tombstones locally.
6. `POST /api/account/import/resolve` with 200 items succeeds and with 201 returns 400 (`@Size(max = 200)`); exceeding the daily budget returns 429 with `retryAfterSec`.
7. `GET /api/admin/youtube/search` as a non-moderator returns **403**, which the Suggest screen must render as `suggest_error_not_allowed` and not as a network fault.
8. `DELETE /api/account/me` returns **204**, and the next authenticated request **403 `ACCOUNT_DELETED`** — end to end into the terminal alert.

> **Live-ops note.** Tier 2 mutates real account rows. Use the Firebase **emulator** and a throwaway uid, never a real curated account. Never point these at production.

**Tier 3 — USER-BLOCKED (14 items).** Everything below reduces to spec §18 open question 1 / D6: **provisioning under the owner's Apple team (Team ID `72PF8SBQR6`, known since 2026-09-02; the App ID `com.albunyaan.tube` is registered by Xcode automatic signing once the owner signs Xcode in) and the Firebase iOS app registration for `com.albunyaan.tube` in project `albunyaan-tube`**. `DEVELOPMENT_TEAM: $(FITRAH_TEAM_ID)` is empty (`ios/Config/Debug.xcconfig:3`, `Release.xcconfig:1`). Do not claim any of these from a simulator run.

| # | Item | Unblocked by |
|---|---|---|
| 1 | `FirebaseApp.configure()` actually succeeding; `currentUser` surviving relaunch via the Keychain (the app has never used the Keychain — the default access group needs no entitlement) | the real `GoogleService-Info.plist` |
| 2 | Email/password **sign-up** and **sign-in** end to end | the plist |
| 3 | A verification email actually arriving (delivery is a *backend* concern — `generateEmailVerificationLink` + `MailService`, `AccountController.java:117-121`; the Firebase-only fallback fires when that endpoint fails) | the plist + backend mail config |
| 4 | Password reset, re-auth, `updatePassword`, `verifyBeforeUpdateEmail`, `delete()` | the plist |
| 5 | Google Sign-In consent → `signInWithCredential` → a fresh account routing to ProfileBootstrap | `GIDClientID` **and** the reversed-client-id URL scheme (both from the plist) |
| 6 | **Sign in with Apple** end to end — and the first real answer to whether `ASAuthorizationController`'s sheet completes at all (CF-A-14) | Team ID + the App ID capability + Apple enabled as a Firebase provider (a Services ID + key) |
| 7 | Apple's private-relay email round-tripping through `POST /api/account/profile` (the backend requires `principal.isEmailVerified()`; Apple tokens report verified) | item 6 |
| 8 | The `youtube.readonly` incremental prompt appearing **only** on the Import screen, never at sign-in | the plist + the scope enabled on the OAuth consent screen |
| 9 | A real YouTube import (subscriptions / playlists / liked videos) against a live Google account | item 8 |
| 10 | Any on-device run: signing, the entitlements file actually resolving, Keychain persistence across a real install | Team ID |
| 11 | Sync against **production** (as opposed to the emulator) | all of the above |
| 12 | §12 row 1 `WellKnownController` / AASA — SHIPPED (`1e8de0f0`, `13ec5bed`; the 403 was Spring Security, never Cloudflare). What remains: deploying the backend and Apple fetching the file for the registered App ID | the App ID registration + a backend deploy (outside the repo) |
| 13 | The standing Phase 2/3 device checklists this phase inherits (`PHASE2-CARRYFORWARDS.md` USER-BLOCKED sections; Phase 3 Tier 3 items 1–6) | Team ID |
| 14 | The iPad rail visibility wire (pre-task 0a): `MainShellView.railStacks` publishes `tabIsSelected` through the environment and a pushed `PlayerScreen` reconciles `.appear`/`.disappear` on its `.onChange` — both ends are unit-pinned, the SwiftUI propagation is not. One sequence on an iPad with a real receiver: cast on tab A → switch to tab B → power the receiver off → silence until tab A is selected again. Known, deliberate: a hidden screen may still START a cast (claim, pause, load); only the audio is gated | Team ID + a receiver on the owner's network |

---

## Out of scope (deliberate, say-so-in-review items)

- **Deep paging of the Me feed** (Android's `MAX_DEEP_PAGE_ITERATIONS = 30`, the persisted continuation, the opportunistic background fill) — ruling F4; CF-A-5.
- **`BGAppRefreshTask`** and the hourly WorkManager cadence — ruling F6; CF-A-4.
- **A country picker / dial-code table** for the bootstrap and profile phone fields — ruling C1/F5; CF-A-2.
- **`MeTelemetryLogDialog` + `MeRefreshTelemetry`** — ruling F7; CF-A-3.
- **A language picker.** Spec §14 keeps the Settings "Language" row as a deep link to iOS per-app language; RULING 33 removed the picker. `SettingsStore.swift:10-15`'s "restored together by phase 4's picker" is a **stale doc comment** — Task 13 fixes the comment and builds nothing (ruling C10).
- **Microsoft sign-in** — spec §3 Out; its three keys are refused in Task 3.
- **Pruning the 52 caller-less `download_*` catalog keys** — the Phase 3 converter ruling, and `LocalizationTests.theOrphanedAndroidDownloadKeysAreOutsideTheNet` now depends on them existing.
- **Extending `api-specification.yaml` with the `/account/*` paths** — ruling F1; CF-A-7.
- **§12 row 3** (`madeForKids`/`embeddable`/`ytRating`) — ruling C3/F8; CF-A-1.
- **Android's `strings.xml:285` "ad-free"** and the two backend legal-page prose hits — an OWNER item on an Android/backend surface, not on this branch; CF-A-8.
- **History / Recently watched** anywhere — spec §3 Out, ruling F10.

---

## Carry-forwards out of Phase 4 (CF-A-*)

- **CF-A-1 (BACKEND, standing):** §12 row 3 is descoped (ruling C3). The prescribed mechanism does not exist — validation runs through NewPipeExtractor, not the Data API, and NewPipe 0.26.5 exposes none of the three fields. **Revisit when** a resolver rung measurably fails for a kids/UNPLAYABLE set; today that set is empty (`probe-2026-08-23.md:92`). The fix needs a Data API key on the server, a new dependency with its own decision.
- **CF-A-2:** no country picker for the bootstrap or profile phone (ruling C1). Users must know their own dial code, and a stored E.164 renders raw. **Revisit when** support sees phone-entry failures; the picker is ~40 lines plus a static ~250-row table.
- **CF-A-3:** `MeRefreshTelemetry`/`MeTelemetryLogDialog` dropped (F7). **Revisit when** a stuck Me feed needs on-device diagnosis; the cheaper first step is surfacing Atom outcomes in the existing `DeveloperDialog`.
- **CF-A-4:** foreground-only Me refresh (F6). **Revisit when** users report a stale feed on open despite the 30 min TTL; `BGAppRefreshTask` needs a `UIBackgroundModes` entry, a `BGTaskSchedulerPermittedIdentifiers` key, is unschedulable in the simulator, and multiplies the YouTube request footprint the backoff ladder exists to contain.
- **CF-A-5:** no deep paging (F4). Feeds end when the Atom cache runs out, and `AtomFeedFetcher` caps at **15** items per channel where Android's fetcher took 30 (contradiction 8). **Revisit when** a user with few uploads reports a premature end state; the cache and week model do not change.
- **CF-A-6:** the Me feed's per-channel refresh state lives in a `KeyValueStore`, not SwiftData (F13). **Revisit if** it ever needs to commit atomically with row writes — then fold it into the next schema version.
- **CF-A-7 (BACKEND):** `docs/architecture/api-specification.yaml` still declares **no** `/account/*` path, and `PATCH …/submitter-note` / `DELETE …/submission` are unspecced while the `my-submissions` `status` enum is missing `REQUEST_CHANGES` (`:1929-1933` vs `ApprovalDtos.kt:21`). Phase 4 ships five hand-written clients with one shape-pin test each (F1). **Revisit when** the spec catches up — the pin tests are what will tell you.
- **CF-A-8 (OWNER, off-branch):** `android/app/src/main/res/values/strings.xml:285` still says "ad-free", as do `backend/.../controller/LegalPagesController.java:176` and `:384` (privacy-policy and delete-account HTML prose). Flagged, not silently changed — the legal pages are prose about the product, not a share-sheet claim. Needs an Android-capable session.
- **CF-A-9:** the `X-Device-Id` is wiped on account deletion (Android parity, `LocalAccountDataWiper.kt:48-51`), which resets the device's report/index rate-limit identity. Accepted as the privacy-correct behaviour. **Revisit if** it is ever abused as a rate-limit reset.
- **CF-A-10:** the import access token is held **in memory only** (Task 28), so a backgrounded app re-prompts for the scope on the next import. **Revisit if** the re-prompt is judged noisy; the Keychain is the upgrade and the token still expires within the hour.
- **CF-A-11:** `SubscriptionRepository`'s cap bypass exists **for the import path only**. No other caller may use it. A future bulk-add surface must not reach for it without its own ruling.
- **CF-A-12:** the sync foreground trigger rides the same untested view-layer glue CF-D-13 already records for the offline sweep — deleting the line fails no test. The due-decision itself is pinned as `shouldSyncOnForeground` (Task 24). **Revisit** together with CF-D-13, via a launch-hook seam, not separately.
- **CF-A-13:** `AccountBinding` is modelled as a `#Unique` single-row `@Model` because Android's table is a single-row one. Nothing in SwiftData *enforces* the single row — the store's clear-then-insert does. **Revisit if** a second row is ever observed; the fix is a fetch-and-assert in the manager, not a schema change.
- **CF-A-14 (device QA):** whether `ASAuthorizationController`'s sheet completes on a **simulator** without a provisioned App ID is unverified, and `SignInCapabilities.apple` is a *build-time* proxy (`FITRAH_APPLE_SIGNIN`) because no public API can read the app's own entitlements and an unsigned simulator build carries none. The first real answer is Tier 3 item 6.
- **CF-A-15:** `SyncManager`'s exclusion is a hand-rolled `inFlight` flag plus a continuation FIFO (Task 23 Step 0), because an `actor` alone does not serialise across `await`. It is pinned by three tests, but hand-rolled async mutexes are where deadlocks live. **Revisit if** Swift ships a first-party async mutex, or if a fourth failure mode appears — then replace the construct, not patch it.
- **CF-A-17 (AMENDED at the Part A gate, ruling F12 amended):** `AuthMiddleware` was REMOVED in `187b2e94` (stage 7 bloat B2): it had nine tests and no production caller, and two token sources (middleware + `AuthorizedTransport`) were one more than the app needs. `AuthorizedTransport` is the single token source. **Revisit when** a signed-in operation is generated rather than hand-written — rebuild the middleware then, beside `DeviceIdMiddleware`, with `auth` constructed before `AppContainer.init`.
- **CF-A-16:** the account-switch transaction's all-or-nothing guarantee rests on `ModelContext.rollback()`, which is the strongest primitive SwiftData offers. Task 23's test is the specification, **and the stop condition is Task 23 Step 1's fresh-context snapshot assertion**: if `rollback()` proves not to cover a `#Unique` upsert followed by a delete, the task is instructed to **report and stop** — naming the surviving entity and the injection point — rather than compensate. **Revisit** with whatever that investigation finds.
- **CF-A-18 (OWNER — translation):** the Phase 4 `auth_*` keys ported from Android carry English strings in the `ar`/`nl` columns (Android never localised them). They render English on Arabic/Dutch devices. **Revisit** with a translation pass; `convert-strings.py` cannot detect it because the columns are equal by design on Android.
- **CF-A-19:** `convert-strings.py --check` has no cross-locale equality rule (would have caught CF-A-18 and the twelve refused Task 17 keys earlier). **Revisit** when the next batch of keys is authored; the rule is one comparison per key.
- **CF-A-20 (test hygiene):** four pre-existing wall-clock tests (`SplashTimelineTests` ×3, `BackendAvailabilityGateTests.aHangingBackendExpiresAndFailsOpenWithinTheBudget`) assert 1-2 s budgets and measure 6-7.7 s under host load (1-min load > 80); green in every gate run but they WILL fail on loaded CI. Also four Part A tests are flaky when run alone (stage 2 list), plus `ChannelDetailViewModelTests.theHeaderAndTheFirstTabLoadInParallel` (bounded yield, iPad-only under load; seen once at the chore-2 gate). (The 60 s hang of `aRefreshForTheSameAccountKeepsTheAccountRendered` seen once at the gate was NOT load: the `inFlight` slot outlived its round by one main-actor turn, so a follower joined a finished round and fetched nothing — root-caused and fixed in `2562d919`.) **Revisit** before any CI is wired: inject the clock or widen the budgets.
- **CF-A-21 (DONE at Task 32, 2026-09-15):** Firebase iOS SDK 11.15.0 → **12.19.1** in `e820d01d`. The feared `FirebaseAuth` API removals all missed this app (no Dynamic Links API named, already on the `AuthProviderID` overload, no TOTP), so no app logic changed to make it build. What the bump did cost is the CF-A-35 re-read and one real concurrency fix — see Task 32. **Revisit at 13.0.0**, which is SPM/binary-only; re-read the CF-A-35 chain and the raw `AuthErrorMappingTests` values again then, because neither is reachable from a test.
- **CF-A-22 (OWNER — plan gap):** the screenshot rig can photograph five of the eight Phase 4 screens after stage 7 commit 3 (`me-signed-in`, `profile-bootstrap`, `account-blocked`, `profile`, `settings-account`); the sign-in form (needs a fake-capabilities launch hook — without the plist `SignInCapabilities.current()` is all-false and the F11 empty state renders), the email-verification and age-ineligible states, the three edit sheets and the delete dialog have no fixture state or view harness and stay unphotographed (spec §14 cells). Tasks 10/11/12/17 carried no screenshot obligation. **Revisit** with a view-harness rig, or accept device QA (Tier 3).
- **CF-A-23 (Phase 1):** a dimmed ghost navigation rail shows on iPad landscape in the `me-signed-in` capture — the Phase 1 tab shell, not Phase 4. Also the Phase 1-3 tail of the rig (blocks 9-22) was NOT re-verified at the gate commit (killed to free the seat after 8/8 completed blocks green).
- **CF-A-24 (NOT PINNED, ruled):** S3-M2a (a second `refresh()` during an in-flight refresh joins rather than restarts) and S3-M3 (peak fan-out ≤ `maxConcurrent`) are asserted weakly because pinning them needs a suspension seam that would exist only for the test; the 250 ms stagger makes peak 4 unreachable in practice. Ruled: no DEBUG-only rendezvous hook. Also NOT PINNED: `/me` refresh on scenePhase `.active` (S5-C2.1/C4.2 — `App` has no seam). 
- **CF-A-25 (BACKEND monitoring):** since `cadd7c9b`, blocked/deleted accounts hitting `/api/admin/*` and `/api/account/*` get the 403 `ACCOUNT_DELETED`/`ACCOUNT_BLOCKED` envelope where they got a bare 401; dashboards keyed on 401 counts shift. A hard-deleted Firestore user doc still yields a bare 401 — iOS maps a bare 401 refresh failure `userNotFound` → `.deleted` as defence in depth (the one place a local Firebase error triggers an irreversible local wipe; exposure narrow: only code 17011).
- **CF-A-26 (OWNER questions, pre-existing bloat, NOT changed):** P1 `SettingsStore.appLocale` is never written since ruling C10 (language follows the device) — delete the setting or wire a picker; P2 two humanisers exist (`humanizePublished(from:)` in InnerTubeKit and the app's relative formatter) — pick one.
- **CF-A-27:** `AccountSession.init` has eight parameters (`marker`, `wipe`, `providers` added at the gate). If Part B adds one, fold `marker` + `wipe` into one `AccountCleanup` seam. The delete-confirmation password field lives in an `.alert`; Dynamic Type and RTL inside it are unverified (Tier 3).
- **CF-A-28 (Tier 3):** no real Firebase sign-in has ever executed (plist USER-BLOCKED); Apple sign-in gated by `FITRAH_APPLE_SIGNIN_REGISTERED`; the Firebase-console half of S4-I2 (email-enumeration protection) is USER-BLOCKED.
- **CF-A-29 (OWNER, off-branch — Android):** the music-video id the owner banned still appears in `android/app/src/main/java/com/albunyaan/tube/util/ThumbnailUrlHelper.kt:174-177`, `ImportApiTest.kt` and `YouTubeVideoIdRegexTest.kt` (10 hits). Untouched on this branch (CF-A-8 precedent); needs an Android-capable session and the approved fixture ids.
- **CF-A-30:** `AppContainer.session` eagerly constructs `meFeed` (hence `innerTube.atom`) and both OAuth providers, against the file's own lazy discipline for `offlineManager`. No correctness impact; add a lazy indirection if the InnerTube client ever gains a session or background task. Also `swift test --disable-automatic-resolution` is applied to the FitrahAPI stage only — document at the InnerTubeKit line if that package ever gains a dependency.
- **CF-A-31 (rig):** the `phase4-accounts` screenshot block's iPad Pro 13-inch leg is written but was never run at the gate (foreground cap); the rig's `.buttonID` anchors couple it to production accessibility identifiers (`settings.signOut`) that no unit test asserts. Run the full rig before the Part B gate.
- **CF-A-32 (build hygiene):** `ios/Packages/FitrahAPI/Package.resolved` is tracked at the app's 26 pins (27 until the Task 32 bump dropped swift-protobuf out of the graph), but a COLD build of the package alone (the OpenAPI generator plugin resolving) still rewrites it to the package's own 10 pins even with `--disable-automatic-resolution`, and the gate does not restore it. Any developer running `swift build` inside the package dirties the file. **Revisit** by giving the package its own resolution (stop mirroring the app graph into it) or by adding a `git diff --exit-code` on the file to `test.sh`'s pre-stage.
- **CF-A-33:** `AccountError.rateLimited(retryAfterSeconds:)` carries the server's true window but `EmailVerifyError` discards it; the local resend cooldown anchors at the refusal and can stretch toward 2× the server's. Plumb `retryAfterSeconds` through when the verification screen is next touched.
- **CF-A-34 (OWNER — style):** Cubic objects that `AccountSession`, `AuthorizedTransport`, `OfflineManager` and `PlayerViewModel` carry comments keyed to review identifiers ("Stage 9 round 2 / P1", "Fix round 1 / I2") rather than to the invariant. Accepted at the gate as archaeology; a rewrite pass to invariant-first prose is an owner call.
- **CF-A-35 (Tier 3, re-verified at Task 32):** the bare-401 terminal verdict (`refreshRefusal()` recording the mint failure) is implemented against the Firebase **12.19.1** source trace (was 11.15.0; re-read at the Task 32 bump and found byte-identical) and is NOT unit-testable; first real proof is still an admin-side delete against a signed-in device. **This is the obligation every Firebase major inherits:** re-read `User.internalGetTokenAsync` → `signOutIfTokenIsInvalid` → `signOutByForce` → `updateCurrentUser(_:byForce:savingToDisk:)`, plus `Auth.signOut()`'s contrasting `byForce: false`, and re-stamp the version in `FirebaseAuthClient.swift`. Task 32 also found the cheap way to do it: diff the symbol bodies between the two tags rather than read either whole.
- **CF-A-36:** `AtomFeedFetcher`'s persisted `CachedItem` gained `publishedAt` (Task 14) with no cache-key version; a blob from a pre-Task-14 build decodes with `publishedAt == nil` and its rows are dropped by `rebucket` until the next refresh. Dormant today (the Atom endpoint sends no validators, so the 304 replay never fires) and the app is unreleased. A key-prefix bump would orphan old rows from `LocalAccountWiper`'s prefix sweep — if versioning is added, sweep both prefixes.
- **CF-A-37 (Tier 3):** the bare-401 terminal verdict is uid-tagged and answered only for the account the request was signed for; since the chore commit after Cubic round 9 the pre-attempt uid is captured before the first mint and a terminal code is recorded on ANY mint (an expired cached token makes Firebase force-refresh inside the UNFORCED mint), so an admin-side deletion reaches the wipe even when both mints fail. Do NOT rely on the 403 envelope as the primary trigger — `FirebaseAuthFilter` revokes the refresh token first, so the 401 arm wins on the account paths (`AuthorizedTransport`'s own doc). First real proof is an admin-side delete against a signed-in device.
- **CF-A-38 (design notes from fix round 6):** `AccountSession.isAgeIneligible` is a presentation flag living on session state (smallest thing that survives the destination flip; a view concern in the wrong layer — fold into a `RootView` presentation model if a second terminal cover ever appears); `MeSignedInView`'s `compactAutoFills` opt-in means "phones never autofill" now has one exception — the next list must re-ask, not copy the flag; `OfflineSaving.deleteAll` is the protocol's only throwing member (its one caller, the wiper, is the only one that can act on failure); the superseded-round banner pin separates two refresh channels via the 5xx backoff, so a `MeFeedRefreshGate.decide` change can silently stop it discriminating.
- **CF-A-39 (from Cubic round 9, real P3s not fixed at the gate):** federated re-auth refusal on the delete path leaves the picked provider's token in the Keychain (`DeleteAccountViewModel`); a follower's `channelIds` are discarded when it joins a running feed round; `OfflineManager.deleteAll` error propagation has no manager-level test; `DeletionMarking` + two conformers wrap a single `String?` the `KeyValueStore` already covers; `terminateAgeIneligible`/`performDeletion` and `.signedOut`/`dropSession()` spell two teardowns; `ScriptedTransport` (a parking test double) is compiled into the Debug app for one fixture; the password re-auth prelude is duplicated across `EditEmailSheet`/`EditPasswordSheet`. Bloat items for the Part B gate's stage 1.
- **CF-A-40 (BACKEND):** `FirebaseAuthFilter.writeLifecycleVerdict` (from `cadd7c9b`) duplicates the in-band `ACCOUNT_DELETED`/`ACCOUNT_BLOCKED` 403 arms verbatim, and its comment at `:268` still claims iOS gates its retry on `WWW-Authenticate: Bearer` (iOS stopped requiring it at the Part A gate). Fold + fix the comment in the next backend touch.
- **CF-A-41 (edge, recoverable):** `BootstrapValidator.clamped(name:)` clamps to UTF-16 units on grapheme boundaries, so a single grapheme wider than 40 units (pasted Zalgo, a long ZWJ sequence) clamps to "" and Continue disables with no message; retyping recovers. Also the bootstrap password-failure sign-out exit hides whenever a field edit clears the error (one Continue tap brings it back), and two `MeFeedRepositoryTests` doc lines still describe the removed `setFilter(nil)` write-back.
- **CF-A-42 (cosmetic, Part B gate bloat):** `ProfileViewModel`'s display-name field has no client-side length rule while the bootstrap sibling clamps via `BootstrapValidator.clamped(name:)` (the server's `@Size(min=1,max=40)` 400 renders inline, so no dead end) — one call to make for symmetry; `SignInViewModel.land(_ user:)` carries a dead parameter.
- **CF-A-43 (ruled at Task 32, NOT mapped):** FirebaseAuth 12.18.0 added `passwordDoesNotMeetRequirements` (17211), raised when a **project password policy** is violated — a different condition from `weakPassword` (17026, still present and still mapped). It is deliberately left to the `.unknown` arm: `auth_error_weak_password` reads "Password must be at least 6 characters", and a policy can require anything, so mapping 17211 to that string would tell the user a rule they may not have broken. **There is also no regression to repair:** at 11.15.0 the server string `PASSWORD_DOES_NOT_MEET_REQUIREMENTS` had no case at all in `AuthBackend` and fell through its `default:` arm, so the app already rendered `auth_error_generic` for it. **Revisit if** a password policy is ever configured in the Firebase console. The policy's own wording IS reachable — `AuthErrorUtils.passwordDoesNotMeetRequirementsError(message:)` puts the server's message in `NSLocalizedDescriptionKey` — but it arrives as a server-supplied English string, so surfacing it means rendering un-localized backend copy in an Arabic or Dutch UI. That is the decision, not the plumbing.
- **CF-A-44 (CLOSED at Task 34, `ef190518` — both routes; the text below is the Task 33 state, kept for the record):** the ruling-C13 terminal verdict is uid-tagged at the RECORD end and **untagged at the DELIVERY end**. `AuthorizedTransport.send` posts a bare `.deleted`/`.blocked`, and `AccountSession.handleDeletion()` then stamps `marker.pendingUid` from `user?.uid ?? state.me?.uid` — whoever is signed in **now** — and wipes the current scope. A verdict recorded for A and delivered after B has completed a sign-in therefore wipes **B's** library and writes B's uid into A's marker. The record side was hardened twice (Cubic round 6 / P2b, round 4 / NB-A); the delivery side never was. A second route to the same end: `resumePendingDeletion()` reads a nil `currentUser` as "the deleted account is signed out" and redeems the marker, but a LOCKED-device launch also reports nil while a user IS stored (true at 11.15.0 and 12.19.1 alike), so a marker owed to A can be redeemed against B's rows. **Unreachable today** — with no plist the app builds `UnavailableAuthClient`, nothing ever mints a bearer, and nothing can reach `handleDeletion()`. **Done at Task 33:** the verdict now carries the uid it was minted for, `AccountSession.handle(_:for:)` refuses a mismatch, and the verdict's own uid (not `user`'s) names the marker — red captured in both directions. **STILL OPEN, and Task 33 NEWLY FEEDS IT (Cubic round 1 P2 — recorded, not fixed):** the `resumePendingDeletion()` route. Passing the verdict's uid to `handleDeletion` means the bare-401 path now WRITES a durable marker where previously both `user` and `state.me` were nil and no marker was written at all. That is right on its own terms — an interrupted wipe for a deleted account should be resumable — but it hands a marker to a redemption rule that cannot scope it. A durable marker owed to A is still redeemed against whatever library is on the device whenever `currentUser` is nil, which a locked-device launch also reports while a user IS stored. Fix it by distinguishing "no user" from "cannot tell yet" (`UIApplication.isProtectedDataAvailable`), and by refusing to redeem a marker whose owner cannot be confirmed — which needs a DURABLE last-signed-in uid, since `lastKnownUid` is in-memory and nil at launch, exactly when the redemption runs. Deliberately not attempted inside Task 33: a new durable key on the account-deletion path is its own task with its own red captures. See also CF-A-49: narrowing the TRIGGER is not the same as scoping the DAMAGE.
- **CF-A-45 (DONE at Task 33, `d82bfd27`):** a terminal verdict is CONSUMED by `refreshRefusal(signedFor:)` inside `AuthorizedTransport`'s token closure — the read clears the box — but only PUBLISHED after `BearerRetry.send` returns. If the retried request throws (network drop, cancellation) the `refused` box is discarded with the verdict already spent. Not permanent loss: the next 401 re-mints and re-records. But when Firebase force-signed the user out inside that same refused mint, `idToken` returns at its `currentUser` guard for the rest of the process, so nothing records again and `.deleted` waits for the next launch's bare 401. **Fixed** by publishing from a `defer`. Red captured with one scripted 401, so the re-send finds `ScriptedTransport`'s queue dry and throws: unfixed, `events.posted → []` where `[.deleted]` was expected.
- **CF-A-46 (Tier 3 — new SDK behaviour at 12.19.0, unverifiable locally):** locked-device keychain reads changed from *return nil* to *throw `keychainError`*, so `Auth.protectedDataInitialization()` now installs a `protectedDataDidBecomeAvailable` observer and re-runs the user load at unlock. Two consequences no hermetic test can settle. (1) The auth-state stream can now emit `.signedOut` and then `.signedIn(user)` on a prewarm launch — a transition that did not exist at 11.15.0, where the process stayed signed out for its whole life. `AccountSession.start()` handles it as an ordinary sign-in: `deletion = nil`, re-scope every store, and a `/me` refresh fired from a backgrounded process. The direction is right (11.15.0's behaviour was the bug being fixed) but it is new. (2) The accessibility probe `SecItemAdd`s a permanent item (`firebase_auth_keychain_accessibility_check`) it never deletes, and treats any status except `errSecInteractionNotAllowed` as accessible — so if a locked add returns `errSecDuplicateItem` once that item exists, the 12.19.0 fix silently disarms itself after the first launch. **Revisit** on a device: sign in, reboot, cold-launch while locked, confirm the session survives. **Also unverified (Task 34):** whether a locked-device launch can reach `resumePendingDeletion()` with a readable marker at all — if Firebase's keychain items are AfterFirstUnlock, `currentUser == nil`-while-locked exists only before first unlock, when UserDefaults is likely unreadable too and the function returns early. The same device probe settles both.
- **CF-A-47 (the deletion path's remaining UNATTRIBUTED re-entries, surfaced at Task 33):** three ways a `.deleted` still reaches `handleDeletion()` without naming an account. (1) `performDeletion` posts its own completion `.deleted` with no uid AFTER `dropSession()`; if a new account has signed in by the time `RootView` consumes it, `deletion` has been reset by the `.signedIn` arm and the announcement starts a SECOND wipe that stamps the new account into the marker. (2) `performDeletion` is detached by design (CF-G-5) and can outlive the account it started for — it awaits an unbind and a wipe, and nothing fences an account switch against it. (3) A 403 lifecycle envelope answered to an UNSIGNED request publishes a nil uid, which `handle` honours; not reachable against today's backend (`FirebaseAuthFilter.shouldNotFilter` skips `/api/v1/` and the `/api/account/` arms all need a decoded uid) but the code no longer has a reason to believe it cannot happen. A fourth shape, and the attribution guard's own residual hole (Cubic round 3 P2): a verdict that lands BEFORE `AccountSession.start()` has processed the `.signedIn` transition is refused, because `user` and `lastKnownUid` are both still nil. `SignInViewModel.land()` is exactly that window — it calls `session.refresh()` on a transition `start()` has not observed, and `AccountSession` documents `user` as nil there ON PURPOSE. So an account deleted between its sign-in and its first `/me` has its verdict discarded, where before Task 33 it was honoured. Narrow, and unreachable without the plist, but it is a REFUSAL the guard introduced rather than one it inherited. **Fix direction:** latch the identity when a refresh round STARTS for an account (`startedFor` already knows it) rather than only when `start()` observes the transition, so the guard has an answer during `land()`'s window. Deliberately not attempted at the end of Task 33: the first cut of this guard was wrong in exactly this direction, and a second unreviewed edit to the same predicate is how that happens twice. A fifth, narrower shape (Cubic round 1 P3): a tie between two ATTRIBUTED signals is still last-writer-wins, so a stale `(.deleted, A)` landing on a live `(.deleted, B)` evicts B's real deletion and is then refused — before attribution either survivor wiped. Needs two accounts' deletion verdicts in one process. **Fix direction:** capture the attribution once, before teardown, and make a COMPLETION announcement update presentation only — never re-enter destructive command handling; require attribution for a destructive transport verdict.
- **CF-A-48 (CLOSED at Task 34, `57a8b75d` — with one honest limit: the tests pin `RootView.route`, NOT the `.onChange` closure that calls it, which a test cannot construct):** `RootView`'s `consume()` → `session.handle(_:for:)` → alert is the last link in the verdict chain and nothing constructs it. Task 33's chain test drives the real `AuthorizedTransport` into the real `AccountStatusCenter` and then does what `RootView` does BY HAND, so a regression in `RootView.swift` itself — dropping the uid, or un-gating the alert — is invisible to the suite. **Fix** with a view harness, or by moving those two lines into a testable seam on the container.
- **CF-A-49 (by design, recorded so it is not mistaken for scoping):** `LocalAccountWiper` deletes `FavoriteVideo`, `SavedPlaylist`, `SubscribedChannel`, `SyncState` and `AccountBinding` with **no uid predicate**, then clears search history, sweeps three `UserDefaults` prefixes and removes the device id. It is a DEVICE wipe and deliberately so (its own comment argues against Android's per-uid scoping). Task 33 narrowed which verdict may TRIGGER it; it did not narrow what it destroys. So A signing in on a device that already holds B's guest library, then being deleted, still takes B's rows with it. **Revisit** only with the owner: per-uid scoping is a product decision about what a shared device means, not a bug fix.
- **CF-A-50 — CLOSED at Task 41 (`be06e16c`, 2026-09-22): `FavoritesSchemaV6` gives `OfflineItem` a `userId` (guest `""`, lightweight V5→V6, on-disk migration test); ownership is used for DELETION only (`wipeRows(of:)` is async and pays that uid's offline rows AND files first); the Offline library stays device-wide (CF-A-49 is the product question). Pre-upgrade downloads become guest-owned: intact, visible to every account, only a device wipe removes them. Original text:** (OWNER — schema decision, surfaced by two reviewers at Task 34): `OfflineItem` has NO owner column. If a deleted account's ORIGINAL wipe failed on an offline file, the scoped arm later finds no rows for that uid, reports success, and the marker is cleared — so that account's downloads stay on disk with nothing left to retry them AND appear in the next user's Offline library. No worse than the old drop-the-marker behaviour, but the code now books the debt as PAID. Fixing it means an owner column on `OfflineItem` (a schema version bump), which is not a review-fix.
- **CF-A-51 — CLOSED by Task 39 (`f3a42e7a`, 2026-09-22): `marker.lastSignedInUid` is written once, after `/me` succeeds; a blocked account, a failed `/me` or a server-deleted account never becomes the holder. Stated trade: an account whose `/me` never succeeded here and which signed out before a relaunch is not a holder, so rows it wrote in that window go with the previous account's device wipe (while signed in, Firebase's own answer protects it). Original text:** (OWNER — semantics, stage-5 I2): `lastSignedInUid` flips at Firebase SIGN-IN, not at USE — `start()`'s `.signedIn` arm and the `fetch` seed both write it before `/me` answers. So a sign-in that is then blocked, fails `/me`, or is a brand-new account made right after self-deleting the old one still makes the newcomer the "holder", and the previous account's pending device-wide wipe is downgraded to the row-only delete: its search history, Atom/Me-feed caches (whose key names enumerate what it followed), offline files and device id are never swept. Option: write the record only after `/me` succeeds. Asked of the owner 2026-09-20; unanswered at close.
- **CF-A-52 (the single-slot marker, Task 34):** `pendingUid` holds ONE uid, and three narrow shapes follow. (a) Cubic P3: `performDeletion` clears the marker only when THIS deletion wrote it (`owed`), so a successful device-wide wipe with no uid known leaves an older marker behind and the next launch may wipe again — taking guest rows made since. Round 2 narrowed it deliberately (b): a completing deletion must not clear the marker of ANOTHER deletion still in flight. Both need near-unreachable preconditions (an unattributed deletion with nobody signed in; two overlapping deletions) and they trade against each other, so the predicate was NOT edited a fourth time for a P3. (c) If A's and B's wipes BOTH error, B's marker overwrites A's and A's rows have nothing left to reach them once a third account signs in. A real fix is a SET of owed uids, not another condition.
- **CF-A-53 (CLOSED at Task 35, `f2347541` — the text below is the pre-fix state, kept for the record):** the in-process DETACHED `performDeletion` has NO identity guard, while the launch path now has three. It awaits `unbindSync()`, then `wipe()`, then `deleteUser()`, then `dropSession()` without checking who is signed in by then, and `deletion = nil` on the next `.signedIn` reopens sync while the wipe is still running. Trace: A's 403 raises the alert; the wipe parks behind a slow pull; the user taps OK and B signs in, binds and pulls; A's task resumes and the device-wide wipe deletes B's rows, then `dropSession()` signs B out. **Fix direction:** capture the uid at the latch and re-check it before each destructive step, or fence account replacement until the cleanup completes.
- **CF-A-54 (test hygiene, extends CF-B-39 with MEASUREMENTS):** count-based POSITIVE waits remain in `RootViewDestinationTests:88` and 14 `AccountSessionTests`; none has failed, but they share the defect Task 34 root-caused. Replace with a deadline when next touched. Separately, four wall-clock-budget tests (CF-A-20's) fail 4/4 under `-only-testing:FitrahTubeTests` on a single simulator and never in the gate shape — a different regime, not a regression.
- **CF-A-55 — (a) and (c) CLOSED by Task 36 (`55d893f4`, 2026-09-21); (g) CLOSED by Task 40 (`ba3a9c7a`, 2026-09-22: a takeover abort skips only the row/binding/re-scope step, still sweeps history, feed caches, image caches and the device id, and leaves the newcomer's per-uid resend-cooldown key alone; Cubic P3 accepted: the newcomer's per-channel refresh backoff is reset → one refetch); (b), (d), (e), (f) OPEN. (g) as first asked 2026-09-21: on an in-wipe abort the departed account's search history, feed-cache keys (they name its subscriptions), URLCache and device id pass to the newcomer permanently; evaluated from the code, the later device-wide sweeps touch no rows/`SyncState`/`AccountBinding`/marker and cost a bound newcomer only seconds-old searches, a reset resend cooldown, re-fetched caches and a rotated device id — recommended: still run them on abort.** Original text (residue of Task 35 — Cubic round 1, all ruled NOT fixed at the end of a window, because each is one more edit to a wipe predicate that has been wrong four times):** (a) **P2, one-line fix known:** `handleDeletion`'s refusal arm runs `wipeRows(verdictUid)` and returns WITHOUT writing a marker, so if that delete fails (full/corrupt store) nothing durable records the debt and `resumePendingDeletion` never retries — write `marker.pendingUid = verdictUid` on failure when the slot is free. (b) **P2:** attributing the completion `.deleted` defeats `AccountStatusCenter`'s tie rule, which only protects an UNATTRIBUTED held signal — a late stale attributed `.deleted` can evict A's, is then refused, and A never sees the terminal alert (the wipe and sign-out have already happened; the alert is what is lost). Real fix is a per-uid buffer — see CF-A-52. (c) **P2 + review I3:** a takeover DURING `wipe()` — its destructive half is two awaits past the last check. B's rows, `SyncState` and binding are deleted; recovery only re-scopes the stores, `boundUid` is still B so nothing re-binds, and B sees an empty library until a foreground `syncNow` (≥15 min apart). Worse, a pull already in flight re-persists an advanced cursor over the deleted pages, leaving a permanent HOLE. Fix: re-check takeover INSIDE `LocalAccountWiper.wipe()` between its awaits and its synchronous deletes, and force a re-bind for the arrived account. (d) **P3:** the delete screen's nil-uid fallback still takes an unattributed latch; reachable only with no account on a screen that requires one. (e) `deleteUser()` has a check-then-act gap that needs an auth API taking the expected uid. (f) **OWNER:** when a takeover precedes the wipe the departed account's non-row data (search history, caches, downloads, device id) is never swept — right if B has held the device for days, arguably wrong if B arrived seconds ago.
- **CF-A-57 — CLOSED (`e07ffb57`, 2026-09-22): `sendPasswordResetEmail` is synchronous and returns whether Graph took the message; the admin reset endpoint answers 503 `MAIL_UNAVAILABLE` before the success audit; no link is minted with mail disabled; send-verification-email pre-checks `isEnabled()` (cooldown still recorded); dead `mailExecutor` removed. Original text:** (backend, Cubic at Task 38, P2, ruled out of scope): `MailService.sendPasswordResetEmail` is `@Async void`, so the "not sent" outcome Task 38 surfaces for verification is still swallowed on the password-reset path; the caller (`AuthService`) cannot act on it without becoming synchronous. Same family, separate ticket.
- **CF-A-58 (Phase 4 gate, partially met 2026-09-22):** end-to-end sign-in ran for GOOGLE only. EMAIL is blocked until the owner deploys Task 38 (and either sets `MAIL_ENABLED` + `AZURE_*` or relies on Firebase's mailer); APPLE needs a signed device build. CF-A-28 stays open for those two.
- **CF-A-56 (tooling):** `convert-strings.py` marks any ar/nl value identical to English as `needs_review`, so the deliberately-identical Dutch `report_reason_shirk` ("Shirk", a loanword the owner kept) stays in Xcode's review backlog and is re-flagged on every regeneration. Needs an allow-list in the converter; cannot be flipped by hand. Same family as CF-A-19.

- **CF-B-1 (Tier 3, schema):** the per-version frozen schema ladder (`e55008b8`) is byte-faithful to the `git show` of each version's introducing commit, not to a store written by a shipped binary — none exists (unreleased). The first real proof is opening a store from a TestFlight build after the next column change. `everyFrozenVersionKeepsItsHistoricalColumnSet` is the whole enforcement: a column added to a live entity without a new version turns it red.
- **CF-B-2 (schema guard scope):** `everyFrozenVersionKeepsItsHistoricalColumnSet` pins column NAMES per version only — not `#Unique` constraints or attribute types, which is the realistic drift given `#Unique<FavoriteVideo>` was once edited retroactively (`2c611683`). Extend the pin to the unique set when V6 lands. Also the plan's Task 20 step text still prescribes `items` filtering with `!= "AWAITING"`; the ruling is fail-closed `== "APPROVED"` (landed in Task 23).
- **CF-B-3 (cross-repo):** `SyncClient.isValidCursorId` transcribes `SyncController.java:66-82`'s cursor-id rule by hand; both tables pin Swift against the transcription, not the Java, and the Java rule has already moved once. A shared fixture (or a backend contract test that emits the rule) is the fix; until then any change to the Java rule must update the Swift copy in the same PR.
- **CF-B-4 (sync triggers, accepted):** the `!isAgeIneligible` arm of `AccountSession.syncableUid` has no test (its window is one un-parkable await inside the fake's `deleteUser()`); the sign-in bind does not stamp `lastSync`, so a sign-in followed at once by a foreground costs two pull+push cycles (the coupling to fix it is worse than the cost); the two view-layer glue lines (scene-phase `syncOnForegroundIfDue()`, `.onChange(of: network.isOnline)`) are untested per the CF-A-12 precedent — every decision they make is pinned directly.
- **CF-B-5 (sync diagnostics):** `SyncManager`'s incident ring is in-memory and capped at 50 entries, and the one `SyncStore.save(_:_:)` helper reports a failed save into it but does not retry — so a `clearDirty` that fails to persist is diagnosable only while the process lives (the durable trace is the `print`). Surface the ring in `DeveloperDialog` (the CF-A-3 precedent) before any TestFlight build that exercises sync.
- **CF-B-6 (sync, minor residue from Task 23's fix round):** dropped rows are counted even if their save threw; a cancelled merge marks merge-done without pushing; the M3 save-failure pin injects at every save rather than only `clearDirty`. Also the plan's Task 23 step 0 says two concurrent `syncNow()` calls produce ONE pull (coalescing) while the manager ships serialisation (two pulls, ruled correct) with coalescing living in `AppContainer.pushDirtySoon` — correct the plan text.
- **CF-B-7 (BACKEND):** `SyncController.java:47-50`'s comment about a "whereGreaterThan drop risk" is stale and argues against the `startAfter(sinceTs, "")` null-id behaviour (`SyncRepository.pull:70-86`) that the iOS cursor-drop cost analysis depends on. Fix the comment in the next backend touch.
- **CF-B-8 (search client, minor residue):** `YouTubeSearchClient`'s hand-rolled query encoder (needed because Foundation leaves `+` literal and Tomcat reads it as a space) has two fallback branches that silently restore the literal-`+` behaviour; the test helper coalesces duplicate param names. Replace the fallbacks with a precondition when the client is next touched.
- **CF-B-9 (OWNER — translation, extends CF-A-18):** the Suggest screen's 401 arm renders `auth_error_invalid_credential`, whose ar/nl are `needs_review` English; the only alternative key (`splash_couldnt_connect`) is untranslated and has no caller. Same translation pass as CF-A-18. Also a submit from the Suggest screen does not refresh My Submissions (the + entry on that screen does) — one `refresh()` on dismiss if it bothers users.
- **CF-B-10 (Suggest Content, recorded at its fix round):** the category picker offers top-level categories only (`CategoriesCache.all` is the flat list; an admin re-categorises at approval — `PENDING` guarantees that step); `DesignSystem/Components.swift` gained a shared helper outside the task's file list.
- **CF-B-11 (plan amendment):** Task 28's `ImportSummary` interface block pinned five fields with no partial-run signal; the fix round (`c5ff049a`) added `processed: Int` and Task 29 adds `total: Int` (additive; seven fields) so the Import screen computes the partial arm from the summary plus the selection it sent. Amend the plan's Task 28 "Interfaces produced" block; also the plan's `GIDSignIn.currentUser` availability premise was wrong — the SDK never restores it at launch, the shipped predicate is `hasPreviousSignIn()` + `restorePreviousSignIn()`. Also the plan's `GIDSignIn.addScopes` spelling is wrong — the API lives on `GIDGoogleUser` in GoogleSignIn 8.0.0.
- **CF-B-12 (Suggest Content, residue from its fix round):** `SuggestError.server(status:)` carries an HTTP status nothing reads or logs; `SuggestContentViewModel.markSubmitted` stamps only the page in hand (a page fetched before the submit and still on screen elsewhere keeps its `+`); `Router.submissionsToken` is a broadcast with one observer — scope it if a second list ever subscribes.
- **CF-B-13 (ruled at the Part B gate):** per-tab scroll offsets on the Me tab are NOT implemented; the same-tab no-op guard stays as one line, measured vacuous (SwiftUI has no adapter swap). **Revisit** only if a list that empties as admins review it ever needs `ScrollViewReader` bookkeeping.
- **CF-B-14 (ruled):** `.importing` has no Cancel; `revoke()` is the only stop (Android parity; chunks are durable and dedupe on retry; `isPartial` is honest). Stage 4 dissented on security grounds (Cancel ≠ Remove access); with the revoke copy now true (F9 keychain half) the over-claim half of that argument is gone. **Revisit** if support sees the DONE screen's "N of M imported" as a complaint.
- **CF-B-15 (sync residue):** `clearDirty` skips when the row's `isRemoved` differs from the pushed bit, so a toggle made DURING its PUT keeps its dirt; two flips inside one round trip (removed and resurrected, new snapshot fields) still read as unchanged and clear. **Revisit** with a `localVersion` column at V6.
- **CF-B-16 (test seam):** `SyncManager.assumeBound(uid:)` is `#if DEBUG`, the `@TaskLocal` seams' sibling: 21 direct `pullAll`/`pushDirty`/`syncNow` test calls would otherwise have to script a whole merge to satisfy the identity fence. Not a production path.
- **CF-B-17 (deleted at the gate, stage 1 B1):** the My Submissions paging engine (cursor, `loadMore`, `rowAppeared`, footer spinner, `PaginationGuard` wiring, 4 tests) was working machinery behind a cursor the all-statuses backend branch never mints — the `AuthMiddleware` precedent. `mySubmissions(limit:)` only; the list's reach is the server's 100-row cap. **Revisit** with the first status filter, which is the first backend branch that pages.
- **CF-B-18 (OWNER questions, stage 1 batch B — never deleted without a yes):** (B8/P1) five spellings of "send a backend request" and three impossible-failure `encode` guards across the hand-written clients; the hoist touches `AccountClient` (Part A). (B9) four enums for the channel/playlist/video triple with three `fromWire` switches and an unreachable `SuggestType.all` arm on hits — one `ContentKind` is a six-file consolidation. (B11) each store's `import*` re-spells `toggle`'s fetch/upsert/save-or-rollback (~25 lines × 3); `toggle` is Phase 3 code. (P2/P3/P4) CF-A-39/42 stand.
- **CF-B-19 (accepted edges):** Codex 8 — a 429 retry re-spends the daily budget on a REJECTED prefix (rejected ids are not persisted locally; Android parity; fix is a persisted rejected-id set). Codex 14 — a client-side chip filter that empties the Suggest list leaves no row to trigger phone pagination although a next page exists. Stage 5 M4 — `isAvailable` reads the Keychain (`hasPreviousSignIn`) on every Me-screen body evaluation.
- **CF-B-20 (view-layer glue, CF-A-12/CF-D-13 precedent):** an ordinary sign-out now pops every tab whose path holds an account route (`Route.requiresAccount`; `Router.dropAccountRoutes()` is pinned) — Codex 11's iPad case (a retained My Submissions stack rendering A's rows to a signed-out device when a refresh fails offline). The `RootView` `.onChange` line that calls it is untested, like the foreground and connectivity hooks.
- **CF-B-21 (OWNER — Tier 2 never runnable):** the live tier (`ACCOUNT_LIVE=1`, eight backend items) has NO harness in the tree, no Firebase CLI on this machine, no service-account file and no emulator wiring in the backend; it was not run at Tasks 7, 9, 24 or 31. Reported as blocked, never claimed.
- **CF-B-22 (gate timing, OWNER):** the clean-build Debug gate (`rm -rf ios/DerivedData/Build`) hit the 300 s watchdog on all five attempts at this gate — under host load 150-460 (reviewers, Codex/Cubic, the simulator boot storm, a Playwright Chrome renderer) AND once on a quiet host. Measured outside the watchdog: raw clean `build-for-testing` (iPhone) 153 s, warm iPhone-only unit pass 171 s — the clean gate is ~320-340 s on this host today; the warm gate ran 167-263 s on every fix commit. Part A recorded 141 s for the same clean gate. Either the watchdog gets a clean-build allowance, or the clean-build proof moves out of the per-task gate — an owner call (CF-A-20's CI caveat applies to the build too).
- **CF-B-23 (Cubic round 1, ruled at the gate):** `ApprovalsClient.FirestoreTimestamp` decodes `{}` to 1970 — Android's adapter does the same (`ApprovalDtos.kt:58-70`) and Jackson always emits both fields, so no live trigger; accepted as parity. A PUT that 404s is classified `.permanentFailure` (dirt dropped, noted) rather than stamped with the device clock as Android's `on404` does — gate wave-2 W12's reason; the backend's PUT is an upsert, so no live trigger either.
- **CF-B-24 (measured):** `SyncStore.tagAnonRows` now DELETES a guest row whose id the account already holds instead of retagging it (Cubic round 1 P2). Measured by revert-and-run: SwiftData resolves the `#Unique` clash at save by clobbering, not by throwing, so the plain retag produced the same one-row outcome — the explicit delete buys a deterministic winner (the account's row), not a fix for a failure. The pin says so.
- **CF-B-25 (Task 22 ruling F1, amended):** "a tombstone needs nothing from the body" was wrong: the DELETE echo's `updatedAt` is the server's tombstone time, and a row cleared without it kept its last PUT's stamp — the pull of that very tombstone then wiped a re-add made in between (Cubic round 1 P1; Android stamps it). `SyncTransporting.delete` returns the echo like `put`; a 404 answers none and the stamp stays.
- **CF-B-26 (stage 7 regression, closed in commit 3):** the container-owned `ImportViewModel` (the I-5 fix) outlived a sign-out and could hand account A's fetch or review list to account B; `AppContainer.endImportRun()` resets it from the same sign-out hook that pops the account routes. The `RootView` line is view glue (CF-B-20); `ImportViewModel.reset()` is pinned.
- **CF-B-27 (perf, Cubic round 2 P2):** every imported row goes through its store's `import*`, which saves, `reload()`s (two full-table fetches) and calls `onDirty` PER ROW — 200 times per chunk on the main thread. Correct, and the same shape a manual toggle has; a large liked-videos list will feel it. **Revisit** with a batch `import*` per store (one save, one reload, one `onDirty` per chunk) when the first such report arrives.
- **CF-B-28 (Cubic round 2, ruled):** a terminal (401/403) pull leaves the manager's identity fence UP — the round-1 P3 that cleared it was a regression (sync dead for the process; a queued bind refused). Cost: one PUT under a refused bearer per trigger, ending on `.authFailed` with no ladder. A 2xx DELETE whose echo does not decode clears the row without a stamp (no live trigger; the backend always echoes the DTO).
- **CF-B-29 (rig, Phase 2 player, NOT Part B):** `ScreenshotTests.testPlayerB1Task10IPhone` failed in the full-matrix pass at `c849d4b2` ("b1-task10 iphone en landscape: the toolbar must stay visible") under host load 100+; re-run alone on a QUIET host at `a56bbbdb` with the shots directory set it FAILS the same way (`ScreenshotTests.swift:802`), so it is deterministic, not load. Part B touched no player file; the block's last recorded pass is the Part A gate. The Phase 4 blocks all pass on both devices.
- **CF-B-30 (OWNER — sync design, Cubic round 3):** a local row that has never been pushed carries `updatedAt` 0, and the merge PULLS before it pushes, so a guest add of an id the account once removed loses to the server's tombstone (`.applyTombstone` — "dirty does NOT protect from a newer tombstone", Task 21's port of Android's rule) and is silently dropped. Android has the same shape. **Revisit** if a user reports a guest favorite vanishing at sign-in; the fix is a `localVersion`/first-write stamp at V6, or pushing dirty rows before the merge pull.
- **CF-B-31 (doc precision, Cubic rounds 3/4):** `ApprovalsClient.mySubmissions` and `YouTubeSearchClient.search` say "a 200 that is not a page is a failure"; the decoders make the page KEY optional (a renamed key decodes to an empty list — the deliberate, pinned Task 25 "shape trap"), so the sentence holds for a non-object body only. Reword when either client is next touched.
- **CF-B-32 (Cubic round 5 P1, the ideal half):** Import is now gated on the account's own `google.com` provider AND the SDK session (`MeViewModel.canImport`), and a Firebase refusal after a provider sign-in forgets the SDK session. The stricter binding — `authorize()` comparing the SDK user's identity against the Firebase user's `google.com` provider data before extending a scope — needs the Firebase user inside the authorizer; add it when the authorizer next changes.
- **CF-B-33 (accepted):** a fresh Import screen instance restarts a model left in `.review`/`.done`; the container-owned model exists for the RUN in flight, not the selection. Keep the selection only if users ask.
- **CF-B-34 (BACKEND/OWNER, Cubic round 6):** `FavoritesStore.toggle` stores `channelTitle ?? ""` (Phase 3) and `PutFavoriteRequest.channelName` is `@NotBlank`, so a favorite from a catalog item with no channel title 400s on push, is classified permanent, loses its dirt and never syncs. Relax the backend constraint (the registry knows the uploader) or refuse the blank at the toggle — a contract decision, not a gate change.
- **CF-B-35 (OWNER, Task 27 scope):** `YouTubeURLParser` requires an explicit scheme and recognises fewer shapes than Android's `VIDEO_ID_REGEX` (`/embed/`, `/live/`, scheme-less hosts), so those pastes land as a text query. Widen when the Suggest screen is next touched.
- **CF-B-36 (minor residue, Cubic round 6):** a Google 401/403 on all three import lists renders as "no content" with a Retry that re-fails (map the auth statuses to the auth arm); Suggest's appended pages are not deduped by id (commit 5's one-line shape); an in-flight category load renders the sheet's error card; three `YouTubeAuthorizerTests` cases assert only the fake (the production class is SDK-bound — Tier 3).
- **CF-B-37 (Cubic round 7):** `SignInViewModel.presented(_:)` folds `.invalidCredential` into `.wrongPassword` for every entry point; Firebase's 17004 is also its code for a malformed or expired OAuth credential, so a failed Google/Apple sign-in can render wrong-password copy on a flow with no password. Limit the fold to the email/password arm when the sign-in screen is next touched.
- **CF-B-38 (OWNER — design, Cubic round 7):** a manual re-subscribe (or re-save, re-favorite) of a tombstoned AWAITING import resurrects the row with its import provenance intact, so it stays under Pending and pushes as AWAITING. A manual toggle is not a curated approval either; decide whether the resurrect branch should reset the row to an ordinary APPROVED-visible row or keep the review gate.
- **CF-B-39 (hygiene, Cubic rounds 7/8):** the sync/session suites synchronise cross-actor hops with bounded `Task.yield()` budgets in several places (CF-A-20's class — a loaded CI can exhaust a budget; the drain-then-assert-nothing variants can only false-pass); `SyncManager.swift` is ~1 000 lines holding the actor, `SyncStore`, `SyncableRow` and `SyncURL` (split when next touched); `MeViewModel.subscribedChannelIds`' doc claims the feed fans out over the unfiltered subscriptions while it reads the approved-only `items` (fix the sentence).

## Plan-text amendments recorded at the Part B gate (the step texts above are left as written; these are the corrections)
- Task 20 step text: `items` filters `== "APPROVED"` (fail closed), not `!= "AWAITING"` (landed Task 23).
- Task 23 step 0: two concurrent `syncNow()` calls are SERIALISED (two pulls); coalescing lives in `AppContainer.pushDirtySoon`. Add: the manager carries an IDENTITY FENCE (`boundUid` + `epoch`, taken before the lock) — every operation is refused for any uid but the bound one, and a run in flight stops at its next request/page write/`clearDirty` when the identity moves (Part B gate).
- Task 28 "Interfaces produced": `ImportSummary` has SEVEN fields (`processed`, `total` added); `ImportCandidate` carries `channelTitle` for videos; `GIDSignIn.currentUser` is never restored at launch (`hasPreviousSignIn()` + `restorePreviousSignIn()`); `addScopes` lives on `GIDGoogleUser`. Nothing is cached above the SDK (CF-A-10 closed by construction).
- Task 25 / Global Constraints pagination rule: My Submissions does NOT page — the all-statuses backend branch (`ApprovalService.java:513`) takes no cursor and answers `nextCursor: null`; the paging engine was deleted at the Part B gate (CF-B-17) and returns with the first status filter.
- Ruling F9 wording: "forget the token locally (in-memory/keychain)" — the keychain half is `GIDSignIn.signOut()` (local; `disconnect()` stays forbidden). Consequence: `isAvailable` reads false until the next Google sign-in, so the offer is ABSENT after a revoke (RULING 28).
- Task 29/30: the `ImportViewModel` is CONTAINER-owned (`AppContainer.importViewModel`), and the Sharī'ah caution alert's binding setter is a deliberate no-op (both buttons write the model); the screenshot rig taps Continue end to end (`testImportCautionContinueStartsTheRun`).
