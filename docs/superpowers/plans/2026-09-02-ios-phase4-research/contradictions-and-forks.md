# Phase 4 research — contradictions in the inputs, and the forks the plan must decide

Same shape as the Phase 3 plan's "Contradictions in the inputs" + "Forks" sections. Contradictions
are stated, not silently resolved; forks are stated **default + override + cost if wrong**.

---

## Part A — Contradictions

### C1. Spec §13 says the bootstrap phone is optional and regex-validated; Android requires it and uses libphonenumber

Spec §13: *"phone optional (E.164 regex + country hint, no libphonenumber)"*.
Android `ui/bootstrap/ProfileBootstrapViewModel.kt:100-102`:

```kotlin
if (s.phoneCountry.isNullOrBlank())  return BootstrapError.INVALID_PHONE_COUNTRY
PhoneFormat.formatE164(appContext, s.phoneCountry, s.phoneNumber) ?: return BootstrapError.INVALID_PHONE
```

— both country and number are **mandatory**, and `util/PhoneFormat.kt:4-5,30-37` uses
`io.michaelrocks.libphonenumber.android` with `isValidNumberForRegion`. The backend's
`CompleteProfileRequest` also carries `phoneNumber` (`android/.../data/account/CompleteProfileRequestDto.kt:11`;
`AccountController.java:85` passes it through).

**Two separate deviations, and the backend settles one of them.**
`backend/.../dto/CompleteProfileRequest.java:21-23`:

```java
@NotBlank
@Pattern(regexp = "^\\+[1-9]\\d{7,14}$", message = "must be E.164 format")
private String phoneNumber;
```

So **phone is required server-side** and spec §13's "optional" is simply wrong — an optional iOS
field would 400 on every empty submission. The other half of the spec line is *right and better
than it knew*: the backend already validates with exactly the regex the spec asked for, so iOS can
use `^\+[1-9]\d{7,14}$` verbatim and match server validation byte-for-byte with no libphonenumber.
(`UpdateProfileRequest`'s `phoneNumber` — the Profile screen's edit sheet — **is** nullable, meaning
"no change", with the same `@Pattern`, `dto/UpdateProfileRequest.java:25-29`. Two different
nullabilities for two different endpoints.) Fork F5 is now a decision about the country picker only.

### C2. Spec §12 says `DELETE /api/account`; the endpoint is `DELETE /api/account/me`

`AccountController.java:220-226` is `@DeleteMapping("/me")` under `@RequestMapping("/api/account")`.
Android already calls it that way (`data/account/AccountService.kt:31-32`). Spec §8's endpoint list
also says `DELETE api/account`. **The code wins**; the spec path is wrong. Related: §12 says "reuse
the revoke + disable + soft-delete path of `AccountProfileService` (`:112-183`)" — that path is
`rejectUnderAge` (the under-13 gate); self-deletion uses `AuthService.deleteAccountPermanently`
(`:849-965`) and reuses none of it. Both spec sentences are stale; the feature is done and richer
than described.

### C3. Spec §12 row 3 prescribes a change to a mechanism the backend does not have

"`VideoValidationScheduler`: add `status` to `part`; persist `madeForKids`, `embeddable`,
`contentRating.ytRating`". Video validation runs through **NewPipeExtractor**
(`ContentValidationService.java:659` → `ChannelOrchestrator.batchValidateVideosDtoWithDetails`,
`ChannelOrchestrator.java:1301-1313`, over `StreamInfo`), not the YouTube Data API. There is no
`part=` anywhere in `backend/src/main/java/**/service/`, and NewPipe 0.26.5's `StreamInfo` exposes
none of the three fields. The row cannot be implemented as written. See `backend-and-phase5.md` §3
row 3; fork F8 decides the disposition.

### C4. Spec §13's role gate reads narrower than the code

Spec §13: *"kebab Profile / My Submissions / Suggest Content (role ∈ {moderator, admin})"* — the
parenthetical reads as though it qualifies Suggest Content only. `MeFragment.kt:270-273` gates
**both** `action_my_submissions` and `action_suggest_content` on `isModerator`. The code wins: a
plain `user` sees only Profile / Import from YouTube / Sign out.

### C5. Spec §13's "Me tab" is the signed-in screen; Android has no guest Me at all

`ui/SplashRouter.kt:26-33` routes a signed-out user to sign-in — there is no Android guest state to
port. Spec D11 and §6 give iOS a guest Me (favorites + sign-in card), already shipped as
`MeGuestView.swift`, and RULING 31 kept it through P2/P3. Phase 4's Me tab is therefore **two
screens behind one tab root**, not one screen. `MeGuestView.swift:56-61`'s disabled sign-in button
is the seam.

### C6. Spec §13's "revocable from the Import screen" has no Android source

Grep for `revoke|clearToken` over `android/.../data/youtube/` and `.../ui/me/importflow/`: only a
doc-comment mention at `YouTubeAuthManager.kt:30`. There is no revoke affordance. The spec line is
an iOS addition; it needs a new string in all three locales and a decision on what "revoke" means
(`GIDSignIn.sharedInstance.disconnect()` revokes **all** granted scopes including the sign-in one,
which would sign the Google user out — that is probably not what is wanted). Fork F9.

### C7. Owner ruling 2026-09-01 already moved Favorites / Recently watched / History to the Me tab — Phase 4 owns them

Phase 3 plan contradiction 1: spec §11 kept Android's Library rows on the Downloads screen, but the
`ios-app-plan.md` §7 downloads row (`:284`) moved the Favorites/history links to the Me tab, and
**the ruling won**; the Saved screen ships without them. So Phase 4's Me tab must carry:
- **Favorites** — already there in `MeGuestView.favoritesSection` (`:70-91`);
- **Recently watched / History** — spec §3 lists these as **Out** ("beyond Android's coming-soon
  rows"), and Android's own rows are coming-soon toasts (`DownloadsFragment.kt:115-146`). So the
  ruling moved *links*, and the destination is out of scope. **Decision needed:** show nothing (the
  honest reading of §3), or show Android's coming-soon rows on the Me tab. Fork F10.
- A link to **Saved** (the Phase 3 screen) probably belongs there too — today it is reachable only
  from the Home overflow and Settings, and Phase 3 removed the Library rows from it.

### C8. "Downloads" wording — the ban is absolute and Phase 4 inherits it

Owner ruling 2026-09-01: user-facing copy says "Save for offline", never "Download".
`convert-strings.py:24-31` `REFUSE`s the five settings keys and re-authors them;
`LocalizationTests.swift:112-136` pins that **no** rendered string contains "Download" or "ad-free".
Phase 4 adds ~150 new rendered strings (auth, bootstrap, profile, submissions, suggest, import) —
the Android originals include `import_youtube_*` copy that is safe, but the plan must keep the
pin green and must not port `download_*`/`downloads_*` keys to any new caller.

### C9. `isRemoved` vs `deleted` — settled, but the codec must be explicit

`FavoriteVideo.swift:12-18` records the controlled A/B test: a `@Model` property literally named
`deleted` mutates in memory and is silently reverted by the next `ModelContext.save()` (Core Data
KVC `isDeleted`). The Swift property is `isRemoved`; **the wire name stays `deleted`** on every DTO
(`SyncDtos.kt:23,38,54` and the Put bodies). Not a contradiction — a trap that must be encoded once,
in the sync codec, with a test.

### C10. `SettingsStore.appLocale`'s doc says phase 4 restores a language picker; §14 says otherwise

`SettingsStore.swift:10-14`: *"restored together by phase 4's picker"*. Spec §14: the Settings
"Language" row **deep-links to iOS per-app language**; RULING 33 removed the picker. The spec wins;
the doc comment is stale. Do not build a picker.

### C11. Two seams the spec assumes exist and do not

- **Firebase plist build phase** — spec §15 row 0 lists it as a Phase 0 deliverable and §4 names
  `ios/scripts/copy-firebase-plist.sh`. Neither the script, the plist, the SPM packages, nor any
  `project.yml` reference exists (`ios-seams.md` §7). Exactly the shape of Phase 3 contradiction 3
  (the Cast fetch script). **Phase 4 pays Phase 0's debt.**
- **`/account/*` in the OpenAPI spec** — spec §8 lists nine account/sync/import endpoints as if
  `FitrahAPI` could generate them; `docs/architecture/api-specification.yaml` declares none
  (`backend-and-phase5.md` §1). Fork F1.

### C12. Spec §13 says "the 13 codes in `AuthErrorMapper.kt:14-33`"; the mapper produces 10

The **enum** has 13 members (`auth/AuthState.kt:31-45`); the mapper (`AuthErrorMapper.kt:14-34`)
produces 10 of them, and the remaining three (`GOOGLE_SIGN_IN_FAILED`, `MICROSOFT_SIGN_IN_FAILED`,
`PASSWORD_RESET_FAILED`) are synthesised at call sites. Cosmetic, but it matters for the port: the
Android mapper's inputs are Firebase **Android** string codes and do not exist on iOS, so the
mapping table is rewritten against `FirebaseAuth.AuthErrorCode` while the output enum ports verbatim
(minus `MICROSOFT_SIGN_IN_FAILED`, spec §3 Out; plus, probably, an `APPLE_SIGN_IN_FAILED`).

### C13. Known Android defects Phase 4 should NOT port

- **CF-G-5**: `DeleteAccountViewModel.kt:47-90` runs the post-204 `wipe()` → `signOut()` cleanup
  cancellably in `viewModelScope`; backing out mid-cleanup strands a signed-in session on a deleted
  account. iOS: run it in a detached, uncancelled Task.
- **CF-G-6**: `LocalAccountDataWiper.kt` clears `device_prefs` but not search history. iOS: wipe
  `SearchHistoryStore` too.
- **CF-G-4**: the wiper races in-flight downloads. iOS: cancel `OfflineManager` work and clear
  `OfflineStore` before wiping.
- `MySubmissionsFragment.kt:70` — the `Error` arm is an unimplemented `TODO`; iOS should render an
  `ErrorState`.
- `MeFeedRepository`'s `deepPageUrl`/`deepPageCookiesJson` are NewPipe `Page` state with no
  InnerTubeKit analogue; the iOS `channel_feed_refresh_state` mirror needs only the first seven
  columns.

---

## Part B — Forks the plan must decide

Format: **Default** (take unless there is a reason) / **Override** / **Cost if wrong**.

### F1. Account endpoints: hand-written clients, or extend the OpenAPI spec first?

The spec has no `/account/*` paths at all, and the generator config filters to 8 public paths
(`openapi-generator-config.yaml:6-15`).
**Default: hand-written clients** in the shipped `PublicHeaders` / `OfflineGateClient` style — one
`AccountClient`, one `SyncClient`, one `ImportClient`, one `ApprovalsClient`, each decoding only the
fields it reads, each with explicit status-code semantics. Rationale: it is the pattern this
codebase already uses four times (`PublicHeaders`, `IndexClient`, `ReportClient`,
`OfflineGateClient`), it sidesteps BACKEND item 1 (Firestore `Timestamp` objects break generated
decodes on any DTO carrying a timestamp — and `PendingApprovalDto.submittedAt` is exactly that), and
it keeps Phase 4 off the backend's critical path.
**Override:** add ~12 paths + schemas to `api-specification.yaml`, widen the generator filter,
regenerate. Buys type-safety from one source of truth and pays down CF-CL BACKEND item 2.
**Cost if wrong:** hand-written clients drift from the server; mitigate exactly as Phase 3 did —
one pin test per client asserting the shape it decodes, so the day the spec catches up the test
tells you.

### F2. Does sync ship in Phase 4, or split into 4a (auth+Me+profile) and 4b (sync+import)?

Phase 4 as specced is the largest phase in the plan: auth (3 screens) + bootstrap + Me (feed, chips,
tabs, awaiting) + profile (4 sheets + deletion) + sync (a 636-line engine with a documented history
of six correctness incidents) + submissions + suggest + import (5 states, 3 paginators, batching).
**Default: split.** 4a = Firebase seam, sign-in/verification/bootstrap, `SplashRouter` matrix,
account-status events, Me tab signed-in shell (chips + favorites + feed) reading **local** stores,
profile + deletion. 4b = `SyncManager` port + schema V5 + submissions/suggest/import. Rationale: 4a
is independently shippable and independently reviewable; sync's merge matrix deserves its own gate,
and it is the part with the highest regression cost (it can silently transfer one user's library to
another — the exact bug `SyncManager.kt:99-112` records).
**Override:** one phase, as specced.
**Cost if wrong:** a split adds one PR and one review pipeline; a single phase risks a 3 000-line
diff nobody can review carefully.

### F3. Where do `sync_state` and `account_binding` live on iOS?

**Default: SwiftData `@Model`s in `FavoritesSchemaV5`**, alongside the three synced entities.
Rationale: the cursor write must be **atomic with the row writes in the same transaction**
(`SyncManager.kt:207-325` puts them in one `db.withTransaction` deliberately; splitting them is what
SYNC-CURSOR-PERSIST-01 fixed). `UserDefaults` cannot participate in a `ModelContext` save.
**Override:** UserDefaults (simpler, and the binding row is a single record).
**Cost if wrong:** a torn cursor/row pair reintroduces the post-restart row-drop the compound cursor
exists to prevent.

### F4. Me feed engine: full port, or reuse InnerTubeKit's Atom fetcher and skip deep paging?

Android's `MeFeedRepository` is 1 278 lines: Atom refresh with per-channel ETag/backoff, NewPipe
deep-paging with a continuation token persisted in Room, a 30-iteration search loop, an opportunistic
background fill, a 14-day window, week bucketing to 5 000 weeks back.
**Default: port the Atom half and the week bucketing; do NOT port deep paging in Phase 4.**
InnerTubeKit already ships `AtomFeedFetcher.swift` with conditional GET (spec §9, built for exactly
this), `WeekBucket` is a pure 120-line file, and the `channel_feed_refresh_state` TTL/backoff logic
is ~80 lines of pure decision. Deep paging exists to surface content *older* than the Atom feed's 15
most recent — a "load more weeks" affordance, not the first screen. Ship weeks 0..N-from-Atom, and
`reachedEnd` when the cache runs out.
**Override:** full port, using `BrowseClient`'s continuation instead of NewPipe's `Page`.
**Cost if wrong:** users with few uploads see a short feed and a premature end state. Recoverable in
a follow-up; the cache and week model do not change.

### F5. Bootstrap phone: how much of the country picker do we build?

**Settled, not a fork: the field is REQUIRED** (`CompleteProfileRequest.java:21`, `@NotBlank`), and
the validation regex is `^\+[1-9]\d{7,14}$` (`:22`) — use it verbatim so client and server agree.
Spec §13's "optional" is wrong (C1). What is still open is the input affordance:

**Default: a country picker seeded from `Locale.Region.isoRegions`, showing the localized country
name and dial code**, sorted by display name in the device locale (Android's `countryRows`,
`PhoneFormat.kt:63-73`), storing `"+<dial><digits>"` and validating with the backend regex. Dial
codes are a static ~250-row table in the app — the one thing libphonenumber gave Android that
`Foundation` does not.
**Override:** a single free-text `+…` field with the regex and a `.phonePad` keyboard, no picker.
**Cost if wrong:** the picker is ~40 lines plus a table; without it, users must know their own
country code. Note either way that Android's `parseDisplay`/`formatInternational`
(`PhoneFormat.kt:43-51,79-85`) render a stored E.164 value prettily on the Profile screen; without
libphonenumber, iOS renders the raw `+31612345678`. Acceptable, and stated here so it is not
mistaken for a bug.

### F6. Background Me refresh: `BGAppRefreshTask`, or foreground-only?

Android runs an hourly WorkManager job plus a foreground burst plus pull-to-refresh
(`RefreshScheduler.kt:51-143`).
**Default: foreground-only** — the burst-if-stale (30 min threshold) on Me-tab appearance and
pull-to-refresh, wired into the existing `refreshRemoteConfigIfDue` hook shape
(`FitrahTubeApp.swift:98-132`). Rationale: `BGAppRefreshTask` needs a new
`UIBackgroundModes: processing/fetch` entry, an `BGTaskSchedulerPermittedIdentifiers` Info.plist
key, is unschedulable in the simulator, and iOS decides *if* it ever runs. It also multiplies the
YouTube-side request footprint the Atom backoff ladder exists to contain.
**Override:** add `BGAppRefreshTask` for the hourly cadence.
**Cost if wrong:** a user who opens the Me tab rarely sees a slightly staler feed on open — bounded
by the 30 min TTL, then refreshed while they look at it.

### F7. `MeTelemetryLogDialog` + `MeRefreshTelemetry` (phase-2 inventory Q1, still unruled)

**Default: drop both** — Android operator tooling, the developer dialog already carries resolver
counters and cooldown state (RULING 35/62), and telemetry that leaves the device does not exist on
iOS (RULING 26).
**Override:** surface Atom refresh outcomes in the existing `DeveloperDialog`.
**Cost if wrong:** debugging a stuck Me feed on a device needs a log-file read instead of a screen.

### F8. §12 row 3 (`madeForKids`/`embeddable`/`ytRating`)

**Default: descope from Phase 4 entirely**, record as a standing backend item. Rationale: it is not
on Phase 4's path (it is a resolver-ladder optimisation), the prescribed mechanism does not exist
(C3), and the Phase 2 probe measured **zero gap across all 18 known kids/UNPLAYABLE catalog ids** —
`androidItag18` already covers them (`probe-2026-08-23.md:92`).
**Override:** implement it against a YouTube Data API `videos.list?part=status` call added to the
backend (new dependency: a Data API key on the server).
**Cost if wrong:** the resolver keeps trying a rung that will fail for a small set of videos —
measured today as the empty set.

### F9. What does "revoke YouTube access" do (spec §13, no Android source)?

**Default: forget the token locally** — drop the in-memory access token and reset the import screen,
with copy that says what happened ("FitrahTube no longer has access to your YouTube data") and a
link to Google's account permissions page. Rationale:
`GIDSignIn.sharedInstance.disconnect()` revokes **every** granted scope for the app, which signs the
Google user out of the app entirely — a surprising side effect from a button on the Import screen.
**Override:** call `disconnect()` and explicitly warn that it also signs the user out.
**Cost if wrong:** the "revoke" is weaker than a user might assume; the copy has to be honest about
that, and the Google link is the real remedy.

### F10. Me tab: does it show "Recently watched / History" rows?

Ruling 2026-09-01 moved the Favorites/history *links* off the Saved screen to the Me tab; spec §3
lists Recently watched / History as **Out** of scope beyond Android's coming-soon toasts.
**Default: no History/Recently-watched rows at all** — Me shows Favorites (real), Subscriptions and
Saved playlists (as chips, real), plus a link to **Saved** (Phase 3's offline library). Rationale:
Phase 3 already refused to ship dead affordances ("dead buttons are worse than absent ones", RULING
28); a row that toasts "coming soon" is the same thing.
**Override:** port Android's two coming-soon rows for literal parity.
**Cost if wrong:** a user looking for history finds nothing rather than a promise — the better
failure.

### F11. Sign in with Apple — build it in Phase 4, or defer with Google?

Both are blocked on the same registration (§3 of `dependencies-and-blockers.md`).
**Default: build both behind protocols in Phase 4, ship them disabled-by-capability** — the button
renders only when its prerequisite is present (Google: a `GIDClientID`; Apple: the entitlement),
exactly as `SignInFragment.kt:220-226` degrades when `default_web_client_id` is missing. Email/
password works with the plist alone.
**Override:** ship email/password only in Phase 4 and add the providers in Phase 6.
**Cost if wrong:** the buttons are untested until the plist lands either way; building them now
means the plan's first end-to-end run tests everything at once, which is the cheaper sequencing.

### F12. Auth as a middleware in `FitrahAPI`, or per-client?

**Default: a second `ClientMiddleware` in `FitrahAPI` beside `DeviceIdMiddleware`**, plus a shared
`AuthorizedTransport` wrapper for the hand-written clients so both paths share one token source, one
host check, and one 401-retry implementation.
**Override:** each hand-written client attaches its own header.
**Cost if wrong:** three copies of the host-scoping rule, and spec §8's "never leak the Bearer to a
third-party host" is the kind of rule that must exist once (`FirebaseAuthInterceptor.kt:46-68`
records why Android scoped it).
