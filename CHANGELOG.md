# Changelog

All notable changes to FitrahTube. Versions are tagged on the `develop` branch
during the beta program.

## [Unreleased]

## [1.0.0-beta.26] - 2026-06-06

### Android

- **Fixed videos never playing — the "Resolving stream…" (حل البث) loop — at the
  actual root cause.** YouTube now requires a **GVS PO Token** (a BotGuard
  proof-of-origin token) on its googlevideo stream URLs. The app extracts with the
  iOS client (which returns the full HD/4K ladder), but with no poToken every stream
  URL returned HTTP 403, so playback fell into the reactive 403-refresh loop. The
  beta.25 change (matching the data-source `User-Agent` to the iOS client) treated a
  red herring — its on-device "verification" was a false positive (synthetic DASH
  happened to succeed that run and never exercised the 403 path). Confirmed with
  yt-dlp against the live site: the iOS client's https formats are skipped with
  "require a GVS PO Token … may yield HTTP Error 403".
- **Fix:** ported NewPipe's (GPLv3) WebView poToken generator into the app
  (`data/extractor/potoken/`). A hidden, network-blocked `WebView` runs Google's
  BotGuard challenge to mint a poToken, wired into NewPipeExtractor via
  `YoutubeStreamExtractor.setPoTokenProvider(...)`. The token is returned from every
  streaming-client getter; pinned extractor 0.26.2 consumes it through
  `getIosClientPoToken` and appends it to the iOS client's stream URLs. The RxJava
  reference was adapted to Kotlin coroutines.
- Why the iOS client (not web/android): on 0.26.2 the web client serves no stream
  URLs and the android client is SABR-restricted to a single 360p muxed stream — only
  the iOS client returns the full adaptive ladder. iOS client fetch is the build
  default again (`ENABLE_NPE_IOS_FETCH=true`).
- Verified on-device (Android 9 emulator, instrumented test
  `PoTokenStreamResolutionTest`): the user-reported failing videos now resolve
  iOS-client streams carrying the `pot=` token, with the full ladder (1080p; 2160p on
  4K content) and HTTP 206 fetches, zero 403s.
- Requires a working system WebView (present on all standard devices); falls back to
  tokenless extraction if the WebView is broken.

## [1.0.0-beta.25] - 2026-06-06

### Android

- **Fixed videos never playing — endless "Resolving stream…" (حل البث) loop.**
  Root cause (reproduced on a Huawei Honor Play, EMUI 9 / Android 9): streams are
  extracted with the iOS client (default), but the progressive/DASH data source
  hardcoded the Android `User-Agent`. YouTube returns HTTP 403 for googlevideo URLs
  fetched with a UA that doesn't match the extracting client. HLS already used the
  iOS UA; the progressive/DASH path did not — so when synthetic-DASH failed and
  playback fell back to a raw progressive stream, the iOS URL + Android UA 403'd, and
  the reactive 403-refresh re-resolved into the same 403 indefinitely. The
  progressive/DASH/synthetic-DASH data source now uses the iOS UA when iOS-fetch is
  enabled, matching the extraction client (consistent with the existing HLS path).
  Confirmed on-device. Known follow-up: when iOS extraction fails and rotates to the
  Android client, the UA should track the per-stream source client.

## [1.0.0-beta.24] - 2026-06-06

### Android

- **Silent video fixed (picture, no sound).** When YouTube returned no separate
  audio stream, the extractor fabricated a fake audio track from a *video-only*
  URL, so ExoPlayer played a silent stream as the audio. It now sources fallback
  audio from a muxed track (which carries audio), or none — never a
  guaranteed-silent track. Affects both the regular and Shorts players.
- **Shorts no longer freeze on a playback error.** The Shorts player had no
  `onPlayerError` handling: a hard failure (decoder fault, dead URL after retries)
  dropped the shared player into an unwatched IDLE state and froze forever. It now
  refreshes the stream, then auto-skips to the next short, then surfaces a retry —
  bounded per-short so a broken short can't loop. Plus a stale-URL guard that
  re-resolves expired progressive URLs to avoid a frozen first frame on re-bind.
- **Main player recovers from more stalls.** Several `onPlayerError` codes
  (timeout, behind-live-window, cleartext, DRM, unspecified) previously showed a
  toast and left a frozen surface; they now route to recovery, bounded by a
  per-video lifetime counter so a partial-playback error can't loop. A long video
  that recovers from several *separate* stalls no longer hits a false "can't
  recover" screen — the recovery budget replenishes after sustained healthy
  playback.
- **Update install fixed on older Android (8.0–8.1).** APKs are now signed with
  v1+v2+v3, so the pre-install signature check can read the certificate via the
  legacy `GET_SIGNATURES` path on pre-P devices; the installer also fails open on
  pre-P when it still can't read a v2-only cert (the OS enforces signature match on
  update regardless). Android 9+ already read the cert via `apkContentsSigners`.
  The updater now also refuses non-HTTPS APK URLs.

## [1.0.0-beta.23] - 2026-06-05

### Android

- **Import flow now has a Save button.** After selecting subscriptions, playlists,
  or favorites to import, a clear submit action commits the selection (it was
  previously hidden behind the bottom navigation bar).
- **Pre-import halal-content reminder.** A confirmation dialog before importing
  advises choosing only halal-compliant content, per ahl al-sunnah, and to fear
  Allah in the selection. Strings localized in en/ar/nl.
- **Seek no longer collapses 4K to 360p.** YouTube client rotation is now
  failure-driven instead of refresh-driven: a post-seek URL refresh stays on the
  IOS client (full adaptive ladder) rather than downgrading to the ANDROID
  muxed-only 360p client. Only a genuine extraction failure arms the fallback.

### Backend

- **Per-user vs public approval.** Admins can approve imported content either
  publicly (into the curated catalog) or for the requesting user only
  (`visibility` PUBLIC/PERSONAL + `personalGrants`). PERSONAL items are gated out
  of every public read path through a single `VisibilityPolicy`, and the approve
  write is atomic + compare-and-set safe (no stranding, no reject-race). The
  download manifest is bound to the calling user, not just the token.

## [1.0.0-beta.18] - 2026-05-25

### Android

- **Phone number is now a mandatory field at signup.** First-run profile
  bootstrap gains a country dropdown (populated from libphonenumber-android's
  supported regions, display names from the device locale in en/ar/nl) and a
  number field validated as E.164 via per-country length rules. The backend
  re-validates with a `^\+[1-9]\d{7,14}$` regex as defence-in-depth and rejects
  malformed input with a field-routed `ProfileValidationException`. Trust-based
  collection — no OTP / SMS verification.

- **Email verification gates email/password sign-ups.** A new
  `EmailVerificationFragment` sits between sign-in and splash for any
  password-provider Firebase user with `isEmailVerified=false`. The screen
  sends a verification email on enter (deduplicated across rotations via
  `SavedStateHandle`), surfaces a 60-second cooldown on resend, and routes
  through the splash router only after `currentUser.reload()` reports verified.
  Google and Microsoft sign-ins skip the gate — their tokens already carry
  `email_verified=true`. The backend now reads the `email_verified` claim from
  the Firebase ID token in `FirebaseUserDetails` and `POST /api/account/profile`
  rejects unverified password-provider users with `403 EMAIL_NOT_VERIFIED`, so
  a raw `curl` cannot bypass the client-side gate.

- **Editable email, password, and phone on Personal Info.** The previously
  read-only email row gains an Edit button (hidden for OAuth-only users until
  a follow-up wires their re-auth path). The new password and phone rows each
  open a Material bottom sheet with field-routed errors. Email change uses
  Firebase's `verifyBeforeUpdateEmail`, so the current email stays valid until
  the user clicks the verification link on the new address. Password and phone
  use re-auth + `updatePassword` / `PUT /api/account/profile` respectively.

- **Backend `PUT /api/account/profile` now requires `status=ACTIVE`.** A
  PENDING_PROFILE user can no longer write a phone via the partial-update path
  to skip the age gate enforced by `completeProfile`. The new `phoneNumber`
  field follows the same field-level merge pattern as `displayName` / DOB —
  same-value retries are idempotent (no write, no audit), and changes emit a
  sentinel `"phoneNumber": "changed"` in the audit log (PII-safe; no raw values
  stored).

## [1.0.0-beta.17] - 2026-05-25

### Android

- **In-app installer migrated to `PackageInstaller` API.** The legacy
  `Intent.ACTION_INSTALL_PACKAGE` was deprecated in API 14 and is silently
  dropped by some OEM-modified Androids (Huawei EMUI 9 in particular —
  beta.15 in-app install attempts never moved `lastUpdateTime` in
  `dumpsys`). The new path streams the APK into a `PackageInstaller` session
  and registers a `PendingIntent` so the OS delivers an explicit
  `STATUS_SUCCESS` / `STATUS_FAILURE_*` callback to a new
  `InstallStatusReceiver`. Failure codes are persisted with the OS-provided
  message so we can surface a real reason instead of a generic toast.
- **"Last update didn't complete" banner on splash.** New
  `LastInstallAttempt` DataStore tracks the target version + status (PENDING /
  SUCCESS / FAILURE / ABANDONED) of the most recent in-app install. When the
  splash detects a non-success record for the same version it's about to
  re-offer, a toast appears before the dialog: "Last update attempt didn't
  complete (reason). Tap Install again or try ADB sideload if it keeps
  failing." A PENDING record older than 24 hours is auto-promoted to
  ABANDONED on read — covers the case where the OS killed our process
  before the callback could fire. Localized in en/ar/nl.
- **Picker row stays in sync with reality.** When the install eventually
  succeeds (cold start sees `BuildConfig.VERSION_NAME` matching the recorded
  target), the LastInstallAttempt record is cleared at read time so the
  banner never lingers after a successful update.

## [1.0.0-beta.16] - 2026-05-25

### Android

- **Auth subsystem now starts on pre-Android-12 devices.** Forced
  `kotlinx-serialization-core` to 1.7.3. The transitive 1.6.x pulled in by
  Firebase + kotlin-reflect installs a `ClassValueReferences` subclass of
  `java.lang.ClassValue` at startup; despite Android's SDK declaring
  ClassValue from API 26, ART only fully implements it from API 31, so on
  Android 9–11 (Huawei EMUI especially) the class-init throws
  `NoClassDefFoundError` inside `FirebaseApp.initializeApp`, cascading into
  a silently-failed Firebase Auth bootstrap — the user appears "logged out"
  with personal lists missing. 1.7.0+ added a runtime ART probe instead of
  the bare SDK_INT check.
- **Available Updates: tapping the row body installs.** Previously only the
  small right-side "Install" button was clickable on newer-than-installed
  rows; the row body registered as a no-op. Both now route to the same
  install flow.

## [1.0.0-beta.15] - 2026-05-25

### Android

- **Update prompt now appears before the sign-in screen.** The "new version
  available" dialog reliably surfaces during the splash on cold start instead
  of racing the sign-in screen. Beta-13 users on devices that recreated the
  activity at launch (theme/locale verification) could land on the sign-in
  screen without ever being notified of beta-14; the splash now gates routing
  on the dialog so the prompt is in front of the user before they can sign in.
- **In-app update reliably applies on Samsung One UI.** After the user taps
  Install, the app process exits cleanly ~2 s after the system installer
  takes over so Samsung's aggressive process retention cannot restore the
  prior DEX on next launch (which previously made the install appear to do
  nothing). If the user backs out of the system installer the kill is
  skipped, so an unintentional cancel doesn't yank them out of the app.
- **Settings → Available updates.** New screen lists the last 5 GitHub
  releases with localized one-line summaries (en/ar/nl), tagged "Installed"
  for your current build and "Install" for newer versions. The screen
  reuses the same 5-minute release cache as the splash gate so it costs
  zero extra network calls on a cold start.
- **Videos / Live channel tabs now show content again.** Bumped
  NewPipeExtractor to v0.26.2 (upstream PR #1492) — fixes empty
  Videos/Live tabs after YouTube's `lockupViewModel` response-shape
  rollout. Same bug surfaced as empty Live pickers and missing video
  thumbnails across multiple Detail screens.
- **Empty / error states on channel tabs no longer disappear.** The
  short ~200 dp tab area below an expanded channel header used to clip
  the icon + headline + body + button stack from the bottom, so the
  user saw nothing. Layout now anchors on the body text and clips
  gracefully from the top — the message always renders. A localized
  generic fallback shows up when the upstream surfaces a blank error
  string, so the user never sees a centered icon over white space.
- **Sign-up submit button activates only when the form is valid.**
  Submit was previously enabled the instant loading finished, even on
  a blank form — users on Samsung S25 / Android 16 reported "the
  button does nothing" because the underlying validation toast was
  being missed. The button now greys out until name, date of birth,
  and (for password sign-ups) password+confirm all pass client-side
  checks. Backend re-validates regardless.
- **APK update downloads now verify the signing certificate AND
  package name** against the currently-installed app before handing
  off to the system installer, so a compromised release-server APK
  fails earlier with a clear "signature mismatch" toast instead of an
  opaque OEM-styled "App not installed" dialog. Same backstop as the
  system installer; the in-app check is defense in depth.
- **`releases-meta.json` parse is now bounded.** Caps at 64 KiB body
  and 160 characters per summary string, so a tampered or runaway
  metadata file can no longer OOM the parse or render a phishing-style
  line in the picker.
- **Picker install path survives navigation.** Tapping Install in
  Available Updates and then rotating, navigating back, or
  backgrounding no longer cancels the in-flight APK download — the
  install coroutine is now scoped to the Activity, not the view.
- **Splash gate releases the IO thread on timeout.** The 2-second
  cold-start budget for the GitHub release check now actually cancels
  the underlying OkHttp socket on timeout instead of holding an IO
  dispatcher thread for OkHttp's default 10-second socket timeout.
- **Picker dates and summaries follow the in-app language setting.**
  Previously the date format read the system locale and the summary
  lookup read a different source — users on system-English / app-Arabic
  saw an inconsistent mix. Both now resolve from
  `AppCompatDelegate.getApplicationLocales`.
- **Firebase Bearer token is now scoped to the API host.** Pre-fix the
  token was attached to every outbound request, including GitHub raw
  fetches and image CDNs. Now host-scoped to the configured
  `API_BASE_URL`; no Bearer leaves the trust boundary.

### Internal

- 7-stage review pipeline (bloat-audit + superpowers code-reviewer + cso
  + codex challenge + gstack /review with 4 specialists + 7 cubic rounds)
  ran across the full delta. All P0/P1/P2 findings closed; 12 new
  tests covering HTTP failure semantics, body cap boundaries, cert
  pinning across API 26 / API 28+ signing paths, coroutine cancellation,
  per_page envelope, and form-validation truth table.

## [1.0.0-beta.14] - 2026-05-24

### Android

- **Sign-in screen logo no longer shows a white square.** The sign-in and
  sign-up form (phone, tablet, TV) now displays the transparent splash logo
  on top of the surface, instead of the launcher icon (which had a baked-in
  white square that clashed with dark themes and surface tints).
- **Adaptive launcher icon background is transparent.** The launcher mark now
  sits directly on the OEM mask shape — no white frame leaking behind it on
  Samsung One UI 6.1, Pixel Launcher, or third-party launchers.
- **Auth buttons keep a consistent height across device sizes.** Profile
  bootstrap submit button now uses the `auth_button_height` token on phone,
  tablet, and TV, matching the sign-in button.
- **Sign-in surface respects the active theme.** Sign-in scroll background
  now binds `?android:colorBackground` instead of inheriting from the
  parent, so light/dark mode and surface tints render correctly.
- **Playback hardening follow-up.** Logs swallowed extraction errors so
  silent fallbacks are diagnosable, and the MPD TTL watcher now fires
  unconditionally (single-shot timer cannot re-arm after a paused-state
  skip, so we trust generation guards downstream).
- **Watchdog race fix in Shorts.** Stall recovery now verifies the video
  still bound at refresh time matches the one that began buffering, so a
  fast swipe past a stalled short does not refresh the wrong stream.

### Admin Dashboard

- **Sign-in panel simplified.** Removed the green gradient backdrop and the
  vignette pseudo-element; the panel now sits on the application surface
  with a softer shadow, which matches the rest of the admin UI.

### Infra

- **CI Firebase config materialization hardened.** Main-branch builds now
  fail fast if the `GOOGLE_SERVICES_JSON` secret is missing or structurally
  invalid (wrong project, missing API key, missing OAuth client). PRs
  continue to compile against the non-functional stub.

[1.0.0-beta.14]: https://github.com/talibfitrah/albunyaantube/releases/tag/v1.0.0-beta.14

## [1.0.0-beta.13] - 2026-05-24

### Android

- **Playback starts faster and stays quieter during recovery.** Automatic
  retries no longer expose attempt counters to users. Shorts keep the current
  video visible while fresh streams resolve, and fallback playback now reuses
  the cached Cronet/Media3 pipeline instead of dropping to an uncached path.
- **Shorts refreshes the right video.** Force-refresh now tracks the bound
  short instead of refreshing the last cached stream entry after several swipes.
- **Long sessions get fewer avoidable stream refreshes.** Synthetic DASH MPDs
  live longer, proactive refreshes use their own rate-limit lane, and the
  Media3 cache is larger on capable devices.
- **Playback dependency refresh.** Media3 moves to 1.10.1, which includes the
  upstream HLS crash fix that made the earlier 1.10.0 upgrade unsafe.

### Admin Dashboard

- **Dependency security refresh.** Production frontend dependencies are patched
  and the dashboard ships with a clean npm audit.

[1.0.0-beta.13]: https://github.com/talibfitrah/albunyaantube/releases/tag/v1.0.0-beta.13

## [1.0.0-beta.12] - 2026-05-03

### Android

- **Silent first recovery attempt.** Transient playback hiccups used to flash
  a "Restoring… (1 of 5)" overlay before the first retry even completed,
  alarming users for stalls that resolved in a fraction of a second. The
  overlay now stays hidden on attempt 1 and only appears from attempt 2
  onwards, so persistent failures still get visible feedback.
- **Update dialog body is now localized.** The dialog used to render the
  GitHub release notes verbatim — always English. It now shows a generic,
  localized "new version available" message in the user's language with a
  recommendation to install. Curious users can still tap "View full
  changelog" for the release page. Cancelling the prompt now shows a brief
  warning that older versions may not perform as expected.

[1.0.0-beta.12]: https://github.com/talibfitrah/albunyaantube/releases/tag/v1.0.0-beta.12

## [1.0.0-beta.11] - 2026-05-03

### Android

- **Update dialog stays compact.** Long, multi-language release notes used
  to fill the entire screen — pushing the action buttons off the bottom and
  making the dialog impossible to dismiss or accept on phones. The dialog
  now shows a short bullet summary of the user's locale, hard-caps the
  release-notes box at 200 dp, and offers a "View full changelog" link
  that opens the GitHub release page in the browser.

[1.0.0-beta.11]: https://github.com/talibfitrah/albunyaantube/releases/tag/v1.0.0-beta.11

## [1.0.0-beta.10] - 2026-05-03

### Android

- **Real fix for the playback crash.** beta.9 removed one trigger but the
  underlying bug in the video engine (Media3 1.10.0, androidx/media#3161)
  still crashed the player on its own whenever an HLS chunk failed to load.
  Pinned the engine to 1.9.3, the last stable release before the regression
  was introduced. The crash path no longer exists.

[1.0.0-beta.10]: https://github.com/talibfitrah/albunyaantube/releases/tag/v1.0.0-beta.10

## [1.0.0-beta.9] - 2026-05-03

### Android

- **Player no longer crashes after a few videos.** A Media3 1.10.0 regression
  (androidx/media#3161) made `HlsChunkSource` crash with an array
  out-of-bounds exception whenever an HLS chunk failed to load — usually
  after switching audio language. The audio-language path now rebuilds the
  stream on a non-HLS source so the buggy code path is no longer reachable.
- **Audio language switching keeps working on HLS streams.** When you pick a
  different language on a video that's playing as HLS, the player now
  briefly reloads the stream as DASH (which honors the chosen track) instead
  of silently staying on the original audio.

[1.0.0-beta.9]: https://github.com/talibfitrah/albunyaantube/releases/tag/v1.0.0-beta.9

## [1.0.0-beta.8] - 2026-05-03

### Android

- **Audio language switching works again.** Picking a different audio track
  from the player menu now actually changes the language. For HLS streams a
  `preferredAudioLanguage` hint steers the renderer natively (no source
  rebuild, no stall). For synthetic-DASH streams the cached MPD is
  invalidated and rebuilt around the chosen audio track.
- **Onboarding shows once, not every cold start.** The "onboarding
  completed" flag was being written to DataStore on the view-lifecycle
  scope and the navigate fired synchronously after — destroying the
  fragment cancelled the in-flight write before it committed. Persist now
  awaits completion before navigating, so the flag survives the next cold
  start.
- **Category icons moved to home section headers.** Icons used to render
  twice — once next to the home section title and once again in the full
  categories list. They now appear only on the home screen (left of the
  section title with a 16 dp gap). Long category labels are truncated to
  18 characters with `…` so they can't crowd the "See all" button on
  narrow phones.

[1.0.0-beta.8]: https://github.com/talibfitrah/albunyaantube/releases/tag/v1.0.0-beta.8

## [1.0.0-beta.7] - 2026-05-03

### Android — Update dialog scrollability

- **Update dialog no longer hides its buttons.** When a release had a long
  changelog, the release-notes text expanded the dialog past the screen
  height and the Update / Cancel buttons fell off the bottom — users had
  no way to install or dismiss. Release notes now scroll inside a capped
  280 dp box; the header and action buttons stay anchored.

[1.0.0-beta.7]: https://github.com/talibfitrah/albunyaantube/releases/tag/v1.0.0-beta.7

## [1.0.0-beta.6] - 2026-05-03

### Android — Channel detail reliability and speed

- **Empty videos on first open is fixed.** When NewPipe occasionally returned
  an empty UU-playlist response for an active channel, the app degraded to an
  empty screen — even when 100 cached videos were already on display from a
  previous open. The fresh fetch no longer wipes the cached emission, and an
  empty UU response now falls through to the channel-tab path instead of
  surfacing the empty state.
- **Cold first-open is much faster.** Previously the videos fetch waited on
  the channel-info HTTP before the playlist request even started. Two
  improvements: (1) the videos fetch now derives the UU playlist URL directly
  from the channel ID and runs in parallel with the header; (2) a hybrid
  race fires the smaller channel-tab response alongside the larger UU
  response — whichever returns first paints, then UU's deeper-pagination
  result wins the final state. First-paint speed is back to roughly beta.2
  levels for big channels without re-introducing beta.2's ~30-item scroll
  cap.
- **Stale cooldown no longer locks user-foreground taps.** A persisted
  rate-limiter cooldown could carry over across app starts and gate every
  channel tap. The cooldown read is now scoped to background refresh only;
  user gestures bypass the gate, while 429 / ReCaptcha responses still
  trip cooldown to throttle autonomous traffic.
- **NewPipe gets its own OkHttp dispatcher.** Background ATOM refresh was
  consuming 4 of 5 youtube.com slots on the shared dispatcher and starving
  user gestures. The NewPipe stack now has its own dispatcher (shared
  connection pool, separate slot quota), restoring user-tap throughput.

### Backend / Frontend

- No code changes — version not bumped.

[1.0.0-beta.6]: https://github.com/talibfitrah/albunyaantube/releases/tag/v1.0.0-beta.6

## [1.0.0-beta.5] - 2026-05-01

### Android
- **Kebab menu icons** — overflow menus on Home, Channel detail, Playlist detail
  and Shorts player now show icons next to each label (Downloads, Settings,
  Share, Report, Quality). Adds the `ic_hd` vector for the Shorts quality
  action.
- **Playlist detail action bar** — replaced the stacked Play / Shuffle /
  Download / Save buttons with a single evenly-spread action bar matching the
  regular video player (icon + caption per cell, `weight=1`). The videos list
  is now visible without scrolling on phones. Applied across phone, `sw600dp`
  tablet, and `sw720dp` TV layouts.
- **Save button accessibility** — TalkBack now announces the correct state
  ("Save" vs "Saved") because content description toggles with the saved
  state.

### Backend
- Channel + Playlist validation schedulers (introduced in beta.4) continue
  refreshing cached metadata daily on staggered, rate-safe schedules.
- ContentValidationService uses type-specific `maxItems` fallbacks so
  channels and playlists honour their own configured caps.
- Validation lock TTLs raised to 120 minutes; dedicated scheduler thread pool
  prevents starvation when refresh runs overlap.

### Frontend (Admin Dashboard)
- Version bump only — no behavioural changes since beta.2.

### Player resilience (carried from PLAYER-RESILIENCE-01)
- MPD TTL watcher wired into the regular video player.
- Offline banner emits aggregate online state and dismisses correctly when
  any network transport recovers.
- Predictive prefetch default disabled to fix a 57-second tap-lockout caused
  by rate-limit exhaustion on slow networks.

[1.0.0-beta.5]: https://github.com/talibfitrah/albunyaantube/releases/tag/v1.0.0-beta.5
