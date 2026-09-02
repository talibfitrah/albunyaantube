# Phase 4 research — Android accounts surface (auth, verification, bootstrap, Me, profile)

Behavioural source of truth for spec §13's first four bullets. Every path below is relative to
`android/app/src/main/java/com/albunyaan/tube/` unless stated; resources are under
`android/app/src/main/res/`. Read at HEAD `cac46c11`.

---

## 1. Auth foundation

### 1.1 State types

`auth/AuthState.kt:15-21` — `sealed interface AuthState { SignedOut; SignedIn(user, uid) }`. The
doc is explicit that this is a 1:1 mirror of Firebase's `AuthStateListener` and that **operation
loading/error state never lives here** — it belongs on the caller's UI state (`:11-12`). Port that
rule: an iOS `AuthState` enum with exactly two cases, and per-screen `isLoading`/`error`.

`auth/AuthState.kt:31-45` — `enum AuthErrorCode`, **13 members**: INVALID_EMAIL, WRONG_PASSWORD,
USER_NOT_FOUND, USER_DISABLED, EMAIL_ALREADY_IN_USE, WEAK_PASSWORD, NETWORK, TOO_MANY_REQUESTS,
INVALID_CREDENTIAL, GOOGLE_SIGN_IN_FAILED, MICROSOFT_SIGN_IN_FAILED, PASSWORD_RESET_FAILED,
UNKNOWN. Spec §13's "the 13 codes in `AuthErrorMapper.kt:14-33`" is imprecise: the mapper produces
only **10** of them (see 1.2); the other three (GOOGLE_SIGN_IN_FAILED, MICROSOFT_SIGN_IN_FAILED,
PASSWORD_RESET_FAILED) are synthesised by call sites, and the mapper's own KDoc says so
(`auth/AuthState.kt:23-30`).

`auth/AccountStatus.kt:19-28` — `enum AccountStatus(wire)`: ACTIVE `"active"`, PENDING_PROFILE
`"pending_profile"`, BLOCKED `"blocked"`, DELETED `"deleted"`. `fromWire` maps **unknown → BLOCKED**
(`:26-27`), deliberately (`:14-18`: BLOCKED routes to sign-in rather than trapping the user in the
bootstrap form, which 409s on repeat entry).

`auth/AccountState.kt:8-42` — `NotSignedIn | Loading | Failed(httpCode, message, cause) | Loaded(uid,
email, displayName, dateOfBirth, phoneNumber, status, role)`. `Failed` deliberately stores a
lightweight value, never the raw `HttpException` (`:16-27`: the retained OkHttp `Response`/
`ResponseBody` pinned a connection-pool slot for the life of the hot StateFlow). iOS has no OkHttp
pool, but the shape (code + message, cause only for IO errors) is worth keeping — it is also what
the UI actually needs.

`auth/AccountStatusEvent.kt:11-21` — `Blocked | Deleted | SignedOut`. Emitted on a buffered
DROP_OLDEST `SharedFlow` so the interceptor thread never blocks (`:8-9`). `SignedOut` exists purely
so `SyncManager.unbind()` and other per-account subsystems can release state without a Hilt cycle
(`:14-20`).

### 1.2 `AuthErrorMapper.kt` — the whole file is 34 lines

`auth/AuthErrorMapper.kt:14-34`, a top-level extension `Throwable.toAuthErrorCode()`:

| Input | → |
|---|---|
| `FirebaseNetworkException` | NETWORK (`:15`) |
| `FirebaseTooManyRequestsException` | TOO_MANY_REQUESTS (`:16`) |
| `FirebaseAuthException` errorCode `"ERROR_INVALID_EMAIL"` | INVALID_EMAIL (`:18`) |
| `"ERROR_WRONG_PASSWORD"` | WRONG_PASSWORD (`:19`) |
| `"ERROR_USER_NOT_FOUND"` | USER_NOT_FOUND (`:20`) |
| `"ERROR_USER_DISABLED"` | USER_DISABLED (`:21`) |
| `"ERROR_EMAIL_ALREADY_IN_USE"` | EMAIL_ALREADY_IN_USE (`:22`) |
| `"ERROR_WEAK_PASSWORD"` | WEAK_PASSWORD (`:23`) |
| `"ERROR_INVALID_CREDENTIAL"` \| `"ERROR_INVALID_USER_TOKEN"` | INVALID_CREDENTIAL (`:24`) |
| `"ERROR_NETWORK_REQUEST_FAILED"` | NETWORK (`:30`, added because Firebase surfaces some transient failures as a *string* code, not an exception type) |
| anything else | UNKNOWN (`:31,33`) |

**iOS note.** The Firebase iOS SDK does not use these string codes — it uses `AuthErrorCode`
(`FirebaseAuth.AuthErrorCode.invalidEmail`, `.wrongPassword`, `.userNotFound`, `.userDisabled`,
`.emailAlreadyInUse`, `.weakPassword`, `.invalidCredential`/`.invalidUserToken`,
`.networkError`, `.tooManyRequests`). The *output* enum ports verbatim; the *input* mapping must be
rewritten against the iOS SDK's error domain. That is a per-code mapping table plus a test — the
one place the Android file is not a line-for-line port. String keys for every output code are already
in the iOS catalog (see §5).

### 1.3 Interceptors (spec §8's contract, verified)

`auth/FirebaseAuthInterceptor.kt`:
- Host scoping (`:77-87`): `apiHost` computed once from `BuildConfig.API_BASE_URL`; **any request
  whose host differs is passed through unsigned**. Fails fast at init on a scheme-less URL
  (`:78-81`). Scope is per-HOST, not per-origin — port and scheme are deliberately ignored
  (`:54-59`).
- Token fetch bounded at **3 000 ms** (`TOKEN_FETCH_TIMEOUT_MS`, `:191`); on timeout the request is
  sent unsigned and the backend's 401 drives the refresh (`:95-103`).
- 401 retry (`:117-177`): only when `WWW-Authenticate` contains `Bearer` (`:184-185`); force-refresh
  bounded at **5 000 ms** (`TOKEN_REFRESH_TIMEOUT_MS`, `:192`), single-flighted through a static
  `Mutex` (`:200`), with a **cross-account guard** — if `auth.currentUser?.uid != user.uid` the
  refresh returns null rather than replaying under a different identity (`:142-143`). On refresh
  failure it re-runs the *signed* request so the 401 surfaces honestly rather than replaying
  unsigned (`:161-175`).

`auth/AccountStatusInterceptor.kt`:
- Fires only on **403** (`:46`), and only for paths starting `/api/admin/`, `/api/v1/`,
  `/api/account/`, `/api/share-metadata/` (`:74-78`). Spec §8 lists `/api/account`, `/api/admin`,
  `/api/v1/reports`, `/api/v1/index` — close but not the actual allowlist; the real one is the
  four **prefixes** above.
- Body peek bounded at **1024 bytes** (`MAX_PEEK_BYTES`, `:137`).
- Envelope `{code, message}` (`:132-133`); `ACCOUNT_BLOCKED` → `Blocked`, `ACCOUNT_DELETED` →
  `Deleted`, anything else → pass through (`:106-110`).
- Order: `firebaseAuth.signOut()` **then** `emitter.emit(event)` (`:124-128`), best-effort only —
  the UI must tolerate a racing emission (`:112-123`).

### 1.4 `AccountRepositoryImpl`

`auth/AccountRepositoryImpl.kt`:
- `fetchMe(maxAttempts)` (`:111-147`): `MAX_ATTEMPTS = 3` (`:258`), linear backoff `backoffMs = 1000`
  (`:24`). IOException retries; **4xx/5xx never retry** (`:124-136`). Splash calls it with
  `maxAttempts = 1` so the route decision is fast (`:101-110`).
- `completeProfile` (`:149-173`): `POST /api/account/profile` with `{displayName, dateOfBirth
  (ISO-8601 "YYYY-MM-DD"), phoneNumber (E.164)}`; **422 + body code `AGE_INELIGIBLE` →
  `AgeIneligibleError`** (`:165-166`), everything else bubbles.
- `bodyHasCode` (`:234-254`): error body read capped at **4 096 bytes** (`MAX_ERROR_BODY_BYTES`,
  `:259`), matched with the regex `"code"\s*:\s*"<CODE>"` — a substring match was misrouting on
  values like `validationField: "AGE_INELIGIBLE_input"` (`:226-233`).
- Terminal-event collector (`:64-96`): `Blocked → signOut()`; `Deleted → signOut() + wipeLocalData()`;
  `SignedOut → signOut()`. The `when` is an expression bound to `val unused: Unit` so a new event
  variant is a compile error (`:69-70`).
- `role` normalised with `(role ?: "user").lowercase()` (`:223`).

### 1.5 `LocalAccountDataWiper` (deletion only)

`data/account/LocalAccountDataWiper.kt:29-64`. Runs on `AccountStatusEvent.Deleted` ONLY — a block is
reversible and an ordinary sign-out deliberately keeps the library (`AccountRepositoryImpl.kt:44-49`).
Wipes: every Room table (`database.clearAllTables()`, `:33`), the downloads directory + recreated
`metadata/` (`:38-40`), the `device_prefs`/`device_id` UUID (`:48-51`), and Coil's disk **and**
memory caches (`:60-61`). Known gap already recorded as CF-G-6: search history
(`search_prefs`/`search_history`) survives.

---

## 2. Sign-in (`ui/auth/SignInViewModel.kt`, `SignInFragment.kt`)

`SignInViewModel.kt`:
- `Mode { SIGN_IN, SIGN_UP }` (`:37`), one toggle (`:67-75`).
- `UiState(mode, email, password, isLoading, error, passwordResetSent)` (`:39-46`).
- `submit()` (`:77-112`): double-tap de-dupe on `isLoading` (`:79`); **client-side shape gates before
  the network** — `isEmailShape` else INVALID_EMAIL (`:90-93`), `password.length < 6` else
  WEAK_PASSWORD (`:94-97`). `MIN_PASSWORD_LENGTH = 6` (`:115`). Rationale at `:81-89`: these mirror
  Firebase's own minimums so the client never rejects what Firebase would accept, and stop malformed
  attempts burning the IP-based throttle quota.
- `onCredential(credential, fallbackError)` (`:118-147`): **no** `isLoading` re-entrancy guard (the
  call site is the system's `ActivityResultLauncher`); instead the prior credential coroutine is
  cancelled (`:133`). Documented caveat at `:125-132`: Firebase's `Task.await()` does not propagate
  cancellation, so `AuthState` follows whichever Task finishes last — acceptable only because the
  launcher redelivers the same credential.
- `forgotPassword()` (`:162-178`): blank email → INVALID_EMAIL without a network call (`:166-169`);
  failure → PASSWORD_RESET_FAILED (`:175`); success sets `passwordResetSent`.

`util/EmailShape.kt:9-15` — `isEmailShape`: one `@`, non-empty local part, domain contains a `.` and
neither starts nor ends with one. Pure, 7 lines, ports verbatim.

`SignInFragment.kt`:
- Google (`:212-230+`): reads `default_web_client_id` **by resource identifier lookup**, and when it
  is absent (Google sign-in not enabled in the Firebase console) surfaces
  `GOOGLE_SIGN_IN_FAILED` instead of crashing (`:220-226`). Re-entrancy guard on
  `viewModel.ui.value.isLoading` (`:215-216`).
- Microsoft is **hidden at both the XML and code layers** (`:205-211`, `microsoftButton.visibility =
  View.GONE`, `microsoftUnavailableTv.visibility = View.GONE`) pending ANDROID-AUTH-02. Spec §3 puts
  it Out of scope for iOS — consistent.

**Sign in with Apple has no Android counterpart.** Spec §13 adds it
(`ASAuthorizationAppleIDProvider` → Firebase `OAuthProvider("apple.com")` with nonce). It is
iOS-new work with no behavioural source, and — because Google sign-in ships — it is an App Store
**requirement** (Guideline 4.8 / 5.1.1: an app offering a third-party login must also offer an
equivalent private option, and Sign in with Apple satisfies it). Entitlement + Team ID needed; see
`dependencies-and-blockers.md`.

---

## 3. Email verification (`ui/auth/EmailVerificationViewModel.kt`, 144 lines)

- `UiState(email, isChecking, isResending, lastSentAtMs, error)` (`:34-40`); `email` seeded from
  `firebaseAuth.currentUser?.email` (`:50`).
- `EmailVerifyError { NOT_YET_VERIFIED, RATE_LIMITED, NETWORK, UNKNOWN }` (`:19-24`).
- **Auto-send once**: `init` calls `sendVerificationEmail()` iff `SavedStateHandle["lastSentAtMs"]`
  is null (`:59-63`) — the latch survives process death, not just recomposition.
- **60 s cooldown**: `COOLDOWN_MS = 60_000L` (`:142`); `resend()` refuses inside the window with
  RATE_LIMITED (`:96-101`).
- Send path (`:105-132`): backend first (`accountService.sendVerificationEmail()`), **Firebase
  fallback only when the backend response is not successful** (`:115-118`). `CancellationException`
  is rethrown (`:122-123`); `IOException` and `FirebaseTooManyRequestsException` map to NETWORK /
  RATE_LIMITED.
- `checkNow()` (`:69-93`): `user.reload().await()` then branch on `isEmailVerified` →
  `Nav.NavigateToSplash`, else NOT_YET_VERIFIED. `FirebaseTooManyRequestsException` → RATE_LIMITED.
- `signOut()` → `Nav.NavigateToSignIn` (`:134-139`). Spec §13's "back = sign out" is the Fragment's
  binding of this.

Backend cooldown mirrors the client: `AccountController.java:48` `VERIFICATION_COOLDOWN_MS =
60_000L`, enforced per-uid in a `ConcurrentHashMap` (`:50, :112-116`) returning **429**
`{code: RATE_LIMITED}`.

---

## 4. Profile bootstrap (`ui/bootstrap/ProfileBootstrapViewModel.kt`, 232 lines)

- `BootstrapError { INVALID_NAME, INVALID_DOB, UNDER_AGE, INVALID_PHONE_COUNTRY, INVALID_PHONE,
  INVALID_PASSWORD, PASSWORD_MISMATCH, PASSWORD_SET_FAILED, SAVE_FAILED }` (`:22-32`).
- `UiState(displayName, dateOfBirth, phoneCountry, phoneNumber, password, passwordConfirm,
  passwordRequired, profileSaved, isLoading, error)` (`:47-72`).
- **One validator, two consumers** — `firstValidationError(s)` (`:89-108`) drives both the submit
  button's enabled state (`isFormValid`, `:111`) and the error dispatch in `submit()` (`:164-168`),
  so they cannot drift (`:80-88`).
- Rules, in field order:
  1. `name.trim()` non-blank and **≤ 40** chars, else INVALID_NAME (`:91`).
  2. DOB non-null, else INVALID_DOB (`:92`).
  3. **Local under-13 gate** (`:99`) — `isUnderMinimumAge(dob, today) = dob.isAfter(today.minusYears(13))`,
     `MIN_AGE_YEARS = 13` (`:223`, mirrors the backend's `AccountProfileService.MIN_AGE`). The
     comment at `:93-98` is the design rationale and must survive the port: the server's rejection is
     *permanent* (revokes tokens, disables the Firebase account, tombstones the Firestore doc), so a
     mistyped year would destroy the account with no recovery. Failing locally keeps an honest
     mistake a correctable form error.
  4. **Phone country required** (`:100`) → INVALID_PHONE_COUNTRY.
  5. **Phone required and must validate for that region** (`:101-102`) → INVALID_PHONE.
  6. If `passwordRequired`: `password.length < 8` → INVALID_PASSWORD; mismatch → PASSWORD_MISMATCH
     (`:103-106`). `MIN_PASSWORD_LENGTH = 8` (`:220`).
- `passwordRequired` is set by the Fragment after inspecting `providerData` (`:117-125`): a
  Google-only account is asked to attach a password so the same email can later sign into the admin
  dashboard (`:54-61`).
- **Two-phase commit** (`:161-217`): `profileSaved` latches after a successful `completeProfile`
  (`:180`) so a failed `updatePassword` can be retried without re-POSTing the profile (which the
  backend may reject as a duplicate, `:63-70`, `:177`). `AgeIneligibleError` →
  `BootstrapNav.NavigateToAgeIneligible` (`:183-184`); anything else → SAVE_FAILED.
- No `currentUser` at the password step → PASSWORD_SET_FAILED with the "profile already committed
  backend-side" note (`:195-201`).

**Phone is MANDATORY on Android and uses libphonenumber** — `util/PhoneFormat.kt` wraps
`io.michaelrocks.libphonenumber.android` (`:4-5`): `formatE164(ctx, region, national)` requires
`isValidNumberForRegion` (`:30-37`); `parseDisplay` (`:43-51`) and `countryRows` (`:63-73`, ISO +
localized country name + `(+dialcode)`, sorted by display name) back the country picker;
`formatInternational` (`:79-85`) renders the stored value. Spec §13 says "phone optional (E.164
regex + country hint, no libphonenumber)" — **two deviations**, recorded in
`contradictions-and-forks.md` §C1.

`ui/bootstrap/AgeIneligibleFragment.kt` / `AgeIneligibleViewModel.kt` (63 / 48 lines) — terminal
screen; strings `age_ineligible_title/body/ok_button` already in the iOS catalog.

---

## 5. Me tab (`ui/me/MeFragment.kt` 730, `MeViewModel.kt` 479, five adapters)

**Android has no guest Me tab.** `ui/SplashRouter.kt:26-33` routes a signed-out user to
`action_splash_to_signIn` — there is no guest path at all. Spec §6 drops the forced sign-in and D11
gives iOS a guest Me (favorites + sign-in card), which Phase 1 already shipped as
`MeGuestView.swift`. Every behaviour below is therefore the **signed-in** Me.

### 5.1 `MeViewModel`

- `state: StateFlow<MeFeedState>` is a 5-way `combine` (`:52-67`) of approved subscribed channels,
  approved saved playlists, the cached feed, the chip filter, and approved favorites, `stateIn`'d
  with `WhileSubscribed(5_000)`.
- `buildState` (`:397-438`): empty channels **and** playlists → `MeFeedState.Empty` (`:404`). Chips
  are channels + playlists **merged and sorted by add-time descending** (`:412-417`) — the comment
  (`:406-411`) records why: segregating them pushed a freshly-saved playlist off-screen in RTL.
  `scoped` filters by `channelId` when a chip is selected (`:419`); items split on `isShort`
  (`:420-421`).
- Week paging (`:97-112`, `:227-358`): `loadedWeekIndices` is a list of **indices**, and `weeks` is
  `combine(indices.map { feed.observeWeek(idx, filterId) })` so a cache mutation re-emits into an
  already-rendered week (`:78-96`, the "Bug 2" fix). `loadNextWeek` walks from
  `(last ?: -1) + 1`, asks `findNextNonEmptyWeekIndex`, fires **one** opportunistic background
  `fillWeekIfNeeded` when a hit exists (guarded by `opportunisticFillJob`, `:281-285`), and when
  there is no hit loops `fillWeekIfNeeded` up to `MAX_DEEP_PAGE_ITERATIONS = 30` (`:477`),
  breaking early when a round adds zero rows (`:314-329`).
- Reset triggers: filter change (`:164-168`, `.drop(1)`) and **subscription-count change**
  (`:181-195`, `.drop(1)`, unfiltered count deliberately, `:182-186`) both call
  `resetLoadedWeeksAndRestart()`.
- `setFilter` clears `loadedWeekIndices` **synchronously before** flipping the filter (`:377-395`) so
  the transient empty render reads as loading, not "no results".
- `awaiting: StateFlow<AwaitingImports>` from `feed.observeAwaiting()` (`:129-134`).
- `snapshotRole()` (`:458-461`): one-shot read of `AccountState.Loaded.role`, else `""`.

### 5.2 `MeFragment`

- Kebab `res/menu/menu_me_kebab.xml` — five items in order: `action_profile`
  (`me_kebab_profile`), `action_my_submissions` (`my_submissions_title`, `android:visible="false"`),
  `action_suggest_content` (`me_kebab_suggest_content`, `android:visible="false"`),
  `action_import_youtube` (`me_kebab_import_youtube`), `action_sign_out` (`me_kebab_sign_out`).
- **Role gate covers BOTH My Submissions and Suggest Content** (`MeFragment.kt:270-273`):
  `isModerator = role == "moderator" || role == "admin"` (case-insensitive), then
  `action_my_submissions.isVisible = isModerator` **and** `action_suggest_content.isVisible =
  isModerator`. Spec §13's parenthetical reads as if only Suggest is gated — see
  `contradictions-and-forks.md` §C4.
- Sign-out is a confirmation dialog (`:302-311`), strings
  `settings_account_sign_out_confirm_title/body/action` + `..._cancel`, then
  `signOutAndNavigateToSignIn()` (`:320-346`) which pops the whole `app_nav_graph` inclusive
  (`:337-342`) and swallows a racing destination change (`:344-345`).
- **Content / Pending tabs** (`renderTabs`, `:358-385`): the `TabLayout` is **hidden entirely while
  `pendingCount == 0`** (`:360-368`) — with an empty queue a two-tab bar would be permanent chrome
  over nothing (`:352-357`). When it appears, tab 0 = `me_tab_content`, tab 1 =
  `me_tab_pending(count)`. Re-adding tabs is avoided on count change; only the label is rewritten
  (`:373-375`). `TAB_CONTENT = 0`, `TAB_PENDING = 1` (`:48-49`).
- `showTab` (`:387-406`) swaps the single RecyclerView's adapter and **stashes/restores each tab's
  scroll position** (`:397-401`), because `setAdapter` resets scroll. Same-adapter reassignment is
  short-circuited (`:393-394`) so a background sync landing the first pending item cannot jerk the
  feed to the top.
- `shouldShowFeedEmptyState(feedIsEmpty, selectedTab) = feedIsEmpty && selectedTab != TAB_PENDING`
  (`:58-59`) — a pure function with its own rationale (`:51-57`) and an obvious Swift port.
- `onResume` (`:409-421`): `refreshScheduler.enqueueForegroundBurstIfStale()` **and**
  `maybeShowImportOffer()`.
- **One-time import offer** (`:428-445`): only when signed in; gated on
  `settingsPreferences.shouldShowImportOffer()`; **marks shown immediately, before showing**
  (`:433`) so a second launch cannot double-fire; strings `import_offer_title/message/positive
  ("Import")/negative ("Not now")`. The key is `SettingsPreferences.IMPORT_OFFER_SHOWN_KEY`; iOS
  already persists it as `SettingsStore.importOfferShown` (`SettingsStore.swift:23,44,57`).
- Favorites row: `MeFavoritesAdapter` caps at **`MAX_TILES = 20`** (`:184`, `items.take(MAX_TILES)`
  at `:45`) plus a trailing "See all" tile, hidden at 0 favorites (`:19-20`). Long-press → remove
  with a snackbar undo affordance (`MeFragment.kt:667-676`).
- Grid: `MeFragment.kt:153` + `spanFor(viewType, spanCount)` (`:62-63`) — only feed video tiles
  occupy one cell; everything else spans the row. `PREFETCH_DISTANCE = 10` (`:727`).

### 5.3 Me feed engine (`data/me/*`, the `channel_feed_refresh_state` logic)

`data/local/ChannelFeedRefreshState.kt:38-51` — PK `channelId`; columns `lastSuccessfulFetchAt`,
`lastAttemptAt`, `lastErrorMessage`, `etag`, `lastModified`, `consecutiveErrorCount`,
`consecutiveEmptyCount`, `backoffUntilMs`, `deepPageUrl`, `deepPageCookiesJson`. **Only the first
seven matter for iOS** — `deepPageUrl`/`deepPageCookiesJson` are NewPipe `Page` continuation state
(`:29-36`) and InnerTubeKit's `BrowseClient` has its own continuation model.

`data/me/AtomChannelFeedFetcher.kt` — `https://www.youtube.com/feeds/videos.xml?channel_id=<id>`
(`:56`), `Accept: application/atom+xml` (`:60`), conditional GET via `If-None-Match` /
`If-Modified-Since` (`:61-62`), **`MAX_ITEMS = 30`** (`:80`, `:71`), 304 → `NotModified` (`:68`),
2xx → `Items` (`:69-73`), anything else → `IOException("HTTP $code")` (`:74`) — that message string
is what the backoff regexes match. `CHANNEL_ID_REGEX = /channel/(UC[A-Za-z0-9_-]{22})` (`:89`).
**InnerTubeKit already ships `AtomFeedFetcher.swift` with conditional GET** (Phase 2, spec §9), so
this is a reuse, not a port.

`data/me/MeFeedRepository.kt` constants (`:141-208`):
`CACHE_TTL_MS = 30 min`, `FEED_WINDOW_MS = 14 days`, `MAX_CONCURRENT = 4`, `STAGGER_MS = 250`,
`MAX_CHANNELS_PER_REFRESH = 50`, `MAX_ITEMS_PER_CHANNEL = 30`, `PER_CHANNEL_TIMEOUT_MS = 15 000`,
`DEEP_PAGE_TIMEOUT_MS = 60 000`, `DEEP_PAGE_EOF_SENTINEL = "https://yt-eof"`.

`refreshOne` gate order (`:778-905`):
1. **TTL freshness** — skip if `now - lastSuccessfulFetchAt < CACHE_TTL_MS`, bypassed by
   `force = true` (`:786-798`).
2. **Per-channel backoff** — skip if `now < backoffUntilMs`, bypassed by `force` (`:802-816`); when
   active, **no field is written at all**.
3. Conditional GET under `withTimeout(PER_CHANNEL_TIMEOUT_MS)` (`:819-825`).
4. Inner timeout → soft failure: record `lastAttemptAt` + `lastErrorMessage`, **do not** increment
   `consecutiveErrorCount` (`:851-861`, rationale: ambient network jitter must not push a user onto
   a 24 h cooldown). Outer cancellation is rethrown (`:845-847`).
5. Hard error → `errCount = prev + 1`, then the ladders (`:886-901`):
   - `HTTP_429_REGEX = /HTTP 429\b|\b429\b/` (`:195`) → `ATOM_429_BACKOFFS = [1 h, 4 h, 24 h]` (`:178-182`)
   - `HTTP_5XX_REGEX = /HTTP 5\d{2}/` (`:194`) → `ATOM_5XX_BACKOFFS = [5 min, 30 min, 2 h]` (`:186-190`)
   - step index = `(errCount - 1).coerceAtMost(lastIndex)`.

`data/me/work/RefreshScheduler.kt` — three triggers: `enqueuePeriodic()` hourly
(`PERIODIC_INTERVAL_MIN = 60`, `:155`; unique name `me_refresh_periodic`, `:146`, `KEEP` policy
`:66`); `enqueueForegroundBurstIfStale(staleThresholdMs = DEFAULT_STALE_THRESHOLD_MS = 30 min)`
(`:86, :162`); `enqueuePullToRefresh()` (`:128`, force). On iOS the hourly WorkManager job has no
equivalent worth building — `BGAppRefreshTask` is a separate decision (fork F6).

`data/me/WeekBucket.kt` — `MAX_WEEKS_BACK = 5_000` (`:44`), `forIndex(weekIndex, now)` (`:56`),
`headerLabel(weekIndex)` (`:78`), `weekIndexOf(uploadedAt, now)` (`:90`). Pure; a direct Swift port.

`data/me/MeRefreshTelemetry.kt` (154) + `ui/settings/MeTelemetryLogDialog.kt` — Android operator
tooling; phase-2 inventory Q1 left it unruled. Recommend: drop (fork F7).

---

## 6. Profile (`ui/me/profile/*`)

`ProfileViewModel.kt`:
- `ProfileUiState { Loading | Editing(original, draft, saving, error) | SignedOut }`
  (`ui/me/profile/ProfileUiState.kt`, 30 lines).
- `loadFromAccount()` (`:63-78`) builds `ProfileFields(displayName, dateOfBirth, emailReadOnly,
  phoneNumber, hasPasswordProvider)`; `hasPasswordProvider` = any `providerData.providerId ==
  EmailAuthProvider.PROVIDER_ID` (`:66-68`).
- The `accountState` collector reconciles **only** externally-editable fields (phone, email) into an
  in-progress draft, never `displayName`/`dateOfBirth` (`:44-58`).
- `save()` (`:88-120`) is **changed-fields-only**: `buildRequest` sends `displayName`/`dateOfBirth`
  only when they differ from `original` (`:129-133`); `phoneNumber` is never in this request (it has
  its own sheet). Result mapping: Success → `applyProfileUpdate` + reset original/draft;
  RateLimited(retryAfterSec); AgeIneligible → **stops at the dialog-trigger state**, the Fragment
  calls `confirmAgeIneligibleSignOut()` on OK (`:103-108`, `:124-127` — staged deliberately because
  StateFlow conflation would swallow it); ValidationFailed(field, message); NetworkError; Unknown.

`data/account/AccountUpdateRepository.kt` — `PUT /api/account/profile` with all-nullable
`UpdateProfileRequestDto(displayName, dateOfBirth, phoneNumber)` (`dto/UpdateProfileRequestDto.kt:10-14`,
"null = no change"). Status mapping (`:30-52`): 2xx+body → Success; **429** → `RateLimited`, seconds
from the body's `retryAfterSeconds`, else the `Retry-After` header, else **60** (`:54-60`); **422** →
`AGE_INELIGIBLE` if the body's `code` says so, else ValidationFailed (`:62-74`); **400** →
ValidationFailed; 401 → `Unknown(401)`. `splitFieldMessage` parses `"<field>: <reason>"` but honours
**only** the field names `displayName`, `dateOfBirth`, `phoneNumber` (`:81-91`) so an unrelated colon
(`"Error: HTTP 500"`) is not read as a field.

Edit sheets:
- `EditEmailViewModel.kt` — re-authenticate with `EmailAuthProvider.getCredential(currentEmail,
  currentPassword)` (`:62`) then **`user.verifyBeforeUpdateEmail(newEmail)`** (`:71`), never
  `updateEmail`.
- `EditPasswordViewModel.kt` — `MIN_PASSWORD_LENGTH = 8` (`:86`); local checks first (weak `:49-52`,
  mismatch `:53-56`), then re-auth (`:67`), then `updatePassword` (`:76`).
  `FirebaseAuthInvalidCredentialsException` → WRONG_PASSWORD (`:68-70`).
- `EditPhoneViewModel.kt` — country required (`:54-57`), `PhoneFormat.formatE164` (`:58-62`), then
  `PUT /api/account/profile` with `phoneNumber` only (`:65`).

**Delete account** — `ui/me/profile/DeleteAccountViewModel.kt`:
- `DeleteAccountState { Idle | Deleting | FailedLastAdmin | FailedNetwork | FailedUnknown }`
  (`:108-116`); **no success state** — the terminal dialog owns the screen (`:29-31`).
- `delete()` (`:43-93`): `service.deleteAccount()` = **`DELETE api/account/me`**
  (`data/account/AccountService.kt:31-32`); `IOException` → FailedNetwork; other → FailedUnknown.
  Non-2xx: **409 → FailedLastAdmin** (`HTTP_CONFLICT = 409`, `:103`), else FailedUnknown — and
  **nothing local is touched on any failure path** (`:61-63`).
- On success: `wiper.wipe()` (rethrowing `CancellationException`, `:83-89`), `authRepository.signOut()`,
  then `statusEmitter.emit(AccountStatusEvent.Deleted)` (`:90-91`) — reusing the exact terminal path
  an admin-side deletion takes.
- Known Android defect CF-G-5: this cleanup runs cancellable in `viewModelScope`.
  **The iOS port should not inherit it** — run the post-204 wipe+signOut in a detached,
  non-cancellable Task.

Strings already in the iOS catalog: `profile_delete_account`, `profile_delete_account_confirm`,
`profile_delete_account_deleting`, `profile_delete_account_dialog_message`, plus 24 more
`profile_*`.

---

## 7. Submissions and Suggest (role-gated)

`ui/me/submissions/MySubmissionsFragment.kt` (133):
- List + `SwipeRefreshLayout` (`:39`) + FAB opening `SubmitContentBottomSheet` (`:40-42`).
- Per-row overflow `res/menu/menu_my_submission_overflow.xml`: `action_edit_note` →
  `EditSubmissionBottomSheet.newInstance(id, type, currentNote)` (`:96-101`);
  `action_delete_submission` → confirm dialog (`:113-122`) → `viewModel.deleteSubmission(id, type)`.
- `EditSubmissionBottomSheet` reports back by Fragment result with outcomes
  `OUTCOME_EDIT_SUCCESS` / `OUTCOME_EDIT_ALREADY_REVIEWED` (`:49-58`) → snackbar + refresh.
- `MySubmissionsActionEvent { DeleteSuccess | DeleteAlreadyReviewed | DeleteFailed }` (`:80-84`).
- `onResume` refreshes (`:124-127`).
- `MySubmissionsUiState { Loading | Loaded(items) | Empty | Error }` (`:64-72`); the Error arm is an
  unimplemented `TODO` on Android (`:70`) — iOS should show something.

`data/approvals/ApprovalApi.kt` — 10 operations:
`GET api/admin/approvals/my-submissions?status&cursor&limit` (`:9-14`, default limit 50);
`POST api/admin/registry/{channels|playlists|videos}` (`:16-23`);
`PATCH api/admin/registry/{type}/{id}/submitter-note` (`:25-41`);
`DELETE api/admin/registry/{type}/{id}/submission` (`:43-50`).
`MySubmissionsRepository.kt:22-23` calls `mySubmissions(status, cursor = null, limit = 100)` — it
**never paginates**; `RateLimitError(retryAfterSeconds)` at `:7`.

`data/approvals/dto/ApprovalDtos.kt` — the two divergences spec §8 already names:
`CursorPageDto` uses `@Json(name = "data")` for the array (`:31`), and `submittedAt` is a Firestore
`Timestamp` object flattened by `FirestoreTimestampAdapter` (`:48-77`, tolerating a plain number or
null). `PendingApprovalDto` (`:11-27`) carries `status ∈ {PENDING, APPROVED, REJECTED,
REQUEST_CHANGES}` (`:21`) — note **four** values, while the OpenAPI `status` query enum offers only
three (`api-specification.yaml:1929-1933`).

`ui/me/suggest/SuggestContentViewModel.kt`:
- Query debounce **300 ms** + `distinctUntilChanged` + `flatMapLatest` (`:39-40`); blank → Idle.
- `parseYouTubeUrl` (`:76-112`) — only `http(s)` inputs; hosts `youtu.be`, `youtube.com`,
  `youtube-nocookie.com` and their subdomains; precedence `v` → `list` → `/channel/<id>` →
  `/shorts/<id>` → `@handle`; anything else → `(ALL, rawQuery)`.
- Type chips filter **client-side** (`onTypeChange`, `:155-161`); the previous filter is carried
  across a new search **only** when the backend was asked for `ALL` (`:53-60`).
- `loadMore` (`:163-189`) re-checks query/type/token after the suspend (a generation guard).
- Result mapping (`:130-147`): `Forbidden → suggest_error_not_allowed`,
  `RateLimited(retryAfterSec) → SuggestUiState.RateLimited`, `NetworkError → suggest_error_network`,
  `Unknown(code) → suggest_error_server`.
- `data/search/YouTubeSearchApi.kt:10-15` — `GET api/admin/youtube/search?q&type&pageToken`.
  `YouTubeSearchRepository.kt:27-45` maps 403 → Forbidden, 429 → RateLimited, other non-2xx →
  Unknown(code), IOException → NetworkError.

---

## 8. Subscription cap

`data/subscriptions/SubscriptionLimitGuard.kt`:
- `CAP = 30` (`:73`); **playlists are uncapped** and bypass the guard entirely (`:26-27`).
- `trySubscribe` (`:53-70`): `capMutex.withLock { db.withTransaction { … } }` — the Mutex exists
  because Room's transaction alone does not serialise two coroutines, and two concurrent calls at
  count 29 both passed (`:41-50`).
- An **existing** row (including a soft-deleted one) is re-upserted with `dirty = true, deleted =
  false` **without a cap check** (`:57-61`) — resubscribing never trips the cap.
- On success, `syncManager.pushDirtyAsync(uid)` (`:68`).
- `SubscribeResult { Success | LimitReached(current, cap) }` (`:15-23`); the UI shows
  `me_subscription_cap_reached`.
- `SubscriptionRepository.subscribe` bypasses the cap **by design, for the import flow only**
  (`:121-136`); no other production caller may use it.

iOS already ships the guest-local half: `SwiftDataSubscriptionsStore.cap = 30`
(`SubscriptionsStore.swift:49`), id regex `^[A-Za-z0-9_-]{3,64}$` (`:64`), throwing
`SubscriptionsError.capReached` (`:88`).

---

## 9. Account-status events → UI

`ui/MainActivity.kt:118-153` collects `AuthRepository.accountStatusEvents` and shows a terminal,
non-dismissible dialog, then routes to sign-in. Strings already in the iOS catalog:
`account_blocked_title/body`, `account_deleted_title/body`. iOS equivalent: an alert presented from
the root, plus a guest reset (spec §6 — iOS never forces sign-in, so "route to sign-in" becomes
"drop to guest and show the alert").
