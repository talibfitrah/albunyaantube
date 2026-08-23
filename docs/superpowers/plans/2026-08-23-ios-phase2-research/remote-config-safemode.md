# Phase 2 research — Remote config, Safe Mode, update prompt flow, Available Updates

Scope: remote-config surface (or its absence), the Safe Mode setting, `UpdatePromptFlow` and its
splash gate, `UpdateChecker` / `ReleaseCatalogCache` / `ReleaseSummaryFetcher`, the Available
Updates screen, and the `releases-meta.json` contract. Every claim cites
`android/app/src/main/**` (or `android/app/build.gradle.kts` / repo root) file:line.
Behavioural contract for the SwiftUI port — no Swift code here. Phase 3 (downloads/Cast) and
Phase 4 (accounts) excluded.

---

## BASELINE — READ THIS FIRST (binding, not open for re-derivation)

The iOS update / remote-config surface is **already decided** by the spec and the phase-1
ruling. This brief's job is to record Android behaviour as context, not to propose the iOS
contract from scratch:

- Spec `docs/superpowers/specs/2026-08-23-ios-app-design.md:22` (**D3**): "Downloads/offline,
  Chromecast and AirPlay are all in scope. In-app update is not (App Store handles updates;
  `minAppVersion` in remote config shows an 'update required' screen)."
- Spec `docs/superpowers/specs/2026-08-23-ios-app-design.md:193` (RemoteConfig schema):
  "`RemoteConfig`: schema from plan §6.13; bundled default; last-known-good; fetched on launch
  and `willEnterForeground` with ≥15 min spacing; body ≤64 KiB; `minAppVersion` gate."
- Phase-1 `docs/superpowers/plans/2026-08-23-ios-phase1-research/RULINGS.md:10` (ruling 6):
  "`minAppVersion` gate: phase 2 (arrives with remote config) — cost: none now."

**What this means for the sections below:**
- §§1–2 (no remote config exists on Android today; Safe Mode is a dormant, unenforced toggle)
  are accurate Android-behaviour record and still matter: §1 is why iOS is building a
  `RemoteConfig` fetcher from nothing rather than porting one, and §2 is the starting point for
  wiring Safe Mode for real on iOS (spec D12).
- **§§3–6 are ANDROID RECORD ONLY — excluded from the iOS port (D3).** The GitHub-releases
  update-check pipeline, its semver comparator, `releases-meta.json`, `UpdatePromptFlow`'s
  splash gate, and the Available Updates screen are **not** iOS scope. iOS instead builds the
  spec:193 `RemoteConfig` fetch (bundled default + last-known-good + launch/foreground fetch +
  size cap) and, when `minAppVersion` trips the gate, an "update required" screen that
  deep-links to the App Store — nothing else from §§3–6 carries over.
- The corrected iOS checklist is §7 below. §8 questions Q1 and Q3, which previously re-opened
  this decision, are marked RESOLVED there.

---

## 0. TL;DR for the implementer

1. **There is no remote config.** No Firebase Remote Config, no backend config endpoint, no
   `minAppVersion`, no fleet kill switches (§1). What exists: compile-time `BuildConfig` flags,
   a per-device SharedPreferences override layer (`PlaybackFeatureFlags`), a hardcoded featured
   category id, and hardcoded resolver-cooldown constants.
2. **Safe Mode is a dormant toggle.** It persists a boolean (default **true**) and *nothing in
   the app reads it* — not lists, not search, not the player, and it is never sent to the
   backend (§2.3). Backend has no safe-mode handling either.
3. On Android, the only runtime-fetched, config-shaped data is the **update subsystem**: the
   GitHub releases API (which build is newest) + `releases-meta.json` from
   raw.githubusercontent.com (localized one-liners). Both cached 5 min, independently (§4).
   **ANDROID RECORD ONLY — D3-excluded from iOS (see baseline above, §7).**
4. The update prompt fires **at most once per cold start**, gates the splash for at most
   2750 ms probe + 500 ms grace, and is always dismissible — there is **no forced update on
   Android** (§5). Play-Store installs suppress the whole GitHub pipeline (§3.4). **ANDROID
   RECORD ONLY — D3-excluded from iOS**; iOS instead has a `minAppVersion` gate that *can*
   block entry (spec:22, spec:193).
5. The Android install half (APK download, `REQUEST_INSTALL_PACKAGES`, PackageInstaller,
   `LastInstallAttempt`) has no iOS equivalent and needs none — iOS distribution is the App
   Store (D3). Formerly flagged as open question Q1; **RESOLVED**, see §8.

---

## 1. Remote config — what actually exists

### 1.1 Nothing is fetched remotely

- Gradle pulls only `firebase-bom` + `firebase-auth` (`android/app/build.gradle.kts:389-390`);
  no `firebase-config` artifact anywhere.
- `grep -ri "RemoteConfig|minAppVersion|forceUpdate"` over `android/app/src/main` → zero hits.
- The Retrofit API surface is `CategoryResponse` / `ContentApi` / `DownloadApi` /
  `HomeFeedResponse` / `IndexApi` / `ReportApi` (`data/source/api/`); none is a config endpoint.

### 1.2 Build-time flags (`local.properties` → `BuildConfig`)

`android/app/build.gradle.kts:55-123`, all in `defaultConfig`:

| BuildConfig field | Default | Property key |
|---|---|---|
| `API_BASE_URL` | `http://10.0.2.2:8080/` | `api.base.url` (`:55-57`) |
| `ENABLE_THUMBNAIL_IMAGES` | `true` (`false` in one build type, `:190`) | — (`:58`) |
| `AUTH_EMULATOR_HOST` / `AUTH_EMULATOR_PORT` | `""` / `9099` | `auth.emulator.*` (`:66-69`) |
| `SHARE_BASE_URL` | `https://app.fitrahtube.com` | `share.base.url` (`:74-76`) |
| `ENABLE_NPE_IOS_FETCH` | `true` | `npe.ios.fetch.enabled` (`:85-86`) |
| `ENABLE_MPD_PREFETCH` | `true` | `playback.mpd.prefetch.enabled` (`:103-104`) |
| `ENABLE_CLIENT_ROTATION` | `true` | `playback.client.rotation.enabled` (`:106-107`) |
| `ENABLE_PREDICTIVE_PREFETCH` | **`false`** | `playback.predictive.prefetch.enabled` (`:116-117`) |
| `ENABLE_SEGMENT_PRELOAD` | `true` | `playback.segment.preload.enabled` (`:118,121`) |
| `ENABLE_NEVER_FREEZE_ABR` | `true` | `playback.never.freeze.abr.enabled` (`:119,122`) |
| `ENABLE_TTL_WATCHER` | `true` | `playback.ttl.watcher.enabled` (`:120,123`) |

The rollout-policy comment (`:92-100`) states outright: for fleet-wide control, "integrate
PlaybackFeatureFlags with Firebase Remote Config or similar" — i.e. **not done**; there is no
remote kill switch today.

### 1.3 `PlaybackFeatureFlags` — per-device runtime overrides only

`player/PlaybackFeatureFlags.kt`:

- SharedPreferences file `playback_feature_flags`; 8 keys mirroring the BuildConfig flags plus
  `generous_crop_budget` (companion constants block). Doc header: "These are **NOT** fleet-wide
  kill switches."
- Tri-state stored as Int: `-1` use-default / `0` disabled / `1` enabled.
- On app-version change (`KEY_PREFS_VERSION` vs `BuildConfig.VERSION_CODE`), **all overrides are
  cleared** (`migratePreferencesIfNeeded`).
- `generous_crop_budget`'s build default is device-sniffed: model contains `SM-S938`
  (Samsung S25 Ultra) → true (`isSamsungS25Ultra()`).
- Hidden entry point: **About screen → tap the version text 7× within 3000 ms** →
  `DeveloperSettingsDialog` with `SwitchMaterial` toggles; countdown toasts on the last 3 taps
  (`ui/settings/AboutFragment.kt:24-25,39-42`, `handleDeveloperOptionsTap`;
  `ui/settings/DeveloperSettingsDialog.kt:259-293`). Tap counter survives rotation via
  `onSaveInstanceState` and uses `SystemClock.elapsedRealtime()`.

These flags gate the Android playback stack (prefetch, ABR, client rotation) — a player-subsystem
concern, recorded here only as "the closest thing to config that exists".

### 1.4 Hardcoded values a remote config would normally own

- **Featured category**: `FEATURED_CATEGORY_ID = "itirf9pGpAvoBT5VSkEc"` — a production
  Firestore doc id compiled into the app (`ui/FeaturedListViewModel.kt:199`, used at `:61` when
  the nav arg is empty). Already Phase 1 open question Q6.
- **Resolver cooldowns** (NewPipe extraction paths, player exempt): escalation 1 h → 4 h →
  12 h → 24 h by trip count in a 24 h window; 7 clean days resets
  (`data/extractor/CooldownState.kt:64-66`, doc `:17-35`). Persisted in its own DataStore keys
  `cooldown_until_ms` / `cooldown_last_trip_ms` / `cooldown_clean_streak_start_ms` (`:58-61`).
- **Global NewPipe rate limiter**: 20 tokens per 30 s refill, 30 s acquire timeout, background
  acquire timeout 0 with 5 tokens reserved for foreground
  (`data/extractor/GlobalNewPipeRateLimiter.kt:136-140`).

All compile-time constants. Nothing remote can change them.

---

## 2. Safe Mode

### 2.1 Storage

`preferences/SettingsPreferences.kt`:

- Key `booleanPreferencesKey("safe_mode")` (`:83`), in the shared `settings` DataStore (`:24`).
- **`DEFAULT_SAFE_MODE = true`** (`:172`); `safeMode: Flow<Boolean>` falls back to it (`:256-258`);
  `setSafeMode(enabled)` writes it (`:260-264`).

### 2.2 UI

- Settings screen, its own "Content" section card containing exactly one row
  (`res/layout/fragment_settings.xml:252-275`, section header string `settings_content` →
  **"Content"**, `strings.xml:516`).
- Row layout `res/layout/settings_item_safe_mode.xml`: horizontal row, `padding spacing_md`;
  `ic_shield` icon in an `onboarding_icon_bg` circle, tint `?attr/colorPrimary`,
  `contentDescription settings_icon_safe_mode` → **"Safe mode setting icon"** (`strings.xml:567`);
  title `settings_safe_mode` → **"Safe Mode"** (`:517`) at `text_subtitle`; subtitle
  `settings_safe_mode_desc` → **"Show only family-friendly content"** (`:518`) at `text_body`;
  trailing `SwitchMaterial` id `safeModeSwitch`, `android:checked="true"` in XML.
- Load: `safeModeSwitch.isChecked = preferences.safeMode.first()`
  (`ui/settings/SettingsFragment.kt:272`). Write: toggle listener →
  `preferences.setSafeMode(isChecked)` (`:414-418`). No toast, no confirmation, no restart.

### 2.3 Enforcement — there is none

- Project-wide grep for `safeMode|SafeMode|safe_mode` in `android/app/src/main` matches **only**
  `SettingsFragment.kt` (`:256,272,350,414,416`) and `SettingsPreferences.kt` (`:83,256,260`).
  No ViewModel, repository, adapter, player, or search path reads it.
- It is **not** a request parameter: `data/source/RetrofitContentService.kt` and
  `data/source/api/ContentApi.kt` contain no `safe` token (grep, zero matches).
- Backend: `grep -r "safeMode|safe_mode" backend/src/main` → zero files. The server has no
  safe-mode concept; content filtering is purely the admin-curation approval flow.
- **Defect (factual):** the switch persists state that gates nothing. Toggling it (either
  direction) has no observable effect anywhere in the app. Default true + no reader means the
  subtitle's promise ("show only family-friendly content") is implemented solely by curation,
  not by this setting.

---

## 3. Update check — data layer

> **ANDROID RECORD ONLY — excluded from the iOS port (D3).** iOS builds the spec:193
> `RemoteConfig` fetch + `minAppVersion` "update required" screen instead. See baseline above.

### 3.1 `UpdateChecker` (GitHub releases API)

`update/UpdateChecker.kt`:

- Endpoint: `GET https://api.github.com/repos/talibfitrah/albunyaantube/releases?per_page=N`
  with headers `Accept: application/vnd.github+json`, `X-GitHub-Api-Version: 2022-11-28`
  (`:143-148`; repo constant `GITHUB_REPO :187`). Deliberately the **list** endpoint, not
  `/releases/latest` — every beta is a GitHub prerelease and `/latest` ignored them (`:80-85`).
- Per-page envelope: `limit × 2` on prerelease builds, `× 6` on stable, capped at 100
  (`:135,141-142`); `currentIsPrerelease = versionName.contains('-')` (`:135`).
- Row filters: drop prereleases **only when the running build is stable** (`:161`); require an
  asset whose name ends `.apk` case-insensitive (`:163-165`); `versionName` = tag with leading
  `v`/`V` stripped and trimmed (`:167`); `publishedAt` parsed leniently to `Instant?` (`:171-173`).
- `checkForUpdate()` = first of the newest 5 (`LATEST_SCAN_LIMIT :191`) strictly newer than
  `BuildConfig.VERSION_NAME` (`:86-95`).
- Failure semantics: HTTP non-2xx **throws** inside the Result so the cache never stickies an
  empty answer (`:151-157`); a genuine 2xx-empty is `success(emptyList())` (`:104-111`).
- Version comparison `isNewerVersion` (`:213-278`) is full semver-2.0.0 precedence: numeric core
  left-to-right (missing segments = 0), release > same-core prerelease, prerelease identifiers
  compared per §11 (numeric < alphanumeric, ASCII lex, longer list wins), build metadata after
  `+` stripped (`:234-245`). Malformed input never throws (`:210-212`). **iOS must reproduce
  this comparator exactly** — the Available Updates row states depend on it (§6).

### 3.2 `UpdateInfo`

`UpdateChecker.kt:21-27`: `versionName`, `releaseName` (GitHub release name ?: tag), `apkUrl`,
`apkSizeBytes`, `publishedAt: Instant?`.

### 3.3 `ReleaseCatalogCache` — 5-minute TTL, two independent caches

`update/ReleaseCatalogCache.kt`:

- `TTL_MS = 5 min` (`:128`); rationale: GitHub anonymous limit is 60 req/h/IP, and splash +
  settings + rotation must share one call (`:30-33`).
- Releases list and summaries are cached **independently** — a slow/blocked
  raw.githubusercontent.com must never suppress update detection (`:16-25`).
- Read path: lock-free `AtomicReference` read within TTL; refresh is mutex-coalesced with a
  double-check (`:111-125`). **Failed fetches are not cached**; successful-empty is (`:107-109`).
- `latest()` = first cached release strictly newer than installed (`:83-88`); `list(limit)`
  requires `limit ≤ 5` (`INTERNAL_REFRESH_LIMIT :134`).

### 3.4 Play-Store suppression

- `InstallSource.isPlayStore()`: installer package == `com.android.vending`, via
  `getInstallSourceInfo` on API 30+ else deprecated `getInstallerPackageName`
  (`update/InstallSource.kt:21-35`).
- Short-circuits: `UpdateChecker.checkForUpdate` → `success(null)` (`:76-79`);
  `listReleases` → `success(emptyList())` before the network (`:127`);
  `ReleaseCatalogCache.cached` → `null` before any fetch (`:116`, memoized boolean `:57`).
- Settings hides the "Available updates" row **and its divider** on Play installs
  (`ui/settings/SettingsFragment.kt:381-393`).
- The manual "Check for updates" row is **not** hidden on Play installs; tapping it runs the
  check, which short-circuits to null → toast **"FitrahTube is up to date."** even when a newer
  GitHub build exists. Factual asymmetry to decide on for the App Store build (§8 Q6).

---

## 4. `releases-meta.json` contract

> **ANDROID RECORD ONLY — excluded from the iOS port (D3).** iOS builds the spec:193
> `RemoteConfig` fetch + `minAppVersion` "update required" screen instead. See baseline above.

### 4.1 Fetcher

`update/ReleaseSummaryFetcher.kt`:

- URL: `https://raw.githubusercontent.com/talibfitrah/albunyaantube/develop/releases-meta.json`
  (`META_URL :122-123`). **Pinned to `develop`**, with an in-code TODO to flip to `main` on the
  first stable release (`:114-115`) and a unit test asserting a stable `VERSION_NAME` is never
  paired with a `/develop/` URL (`:117-120`).
  **Divergence:** the repo's `CLAUDE.md` release checklist says the screen reads
  `releases-meta.json` "from `main`" — the code reads `develop` today. Note factually.
- Body cap 64 KiB (`MAX_META_BODY_BYTES :102`) — oversize → `success(emptyMap())`, not failure
  (`:80-83`, rationale `:56-58`). Per-string cap 160 chars (`MAX_SUMMARY_CHARS :106`, applied
  `:90-92`).
- HTTP non-2xx / IOException → `Result.failure` so the cache retries after transient errors
  (`:60-69`); 404/parse failures degrade to an empty map at the caller
  (`ReleaseCatalogCache.summaries :94-95` falls back to `ReleaseSummaries(emptyMap())`).

### 4.2 File shape and resolution

Repo root `releases-meta.json:1-12`: top-level object keyed by **bare versionName** (no `v`
prefix, e.g. `"1.0.0-beta.45"`), each value an object of locale → one-line string with locales
`en`, `ar`, `nl`. Authoring rule (CLAUDE.md): ≤120 chars per line, missing locales silently OK.

Lookup (`ReleaseSummaries.summaryFor`, `ReleaseSummaryFetcher.kt:133-140`): exact
`(version, locale)` → else that version's `"en"` → else `null` (row renders no subtitle).
Entries authored before the tag exists are harmless orphans (`:29-33`).

---

## 5. Update prompt flow (`UpdatePromptFlow.kt`)

> **ANDROID RECORD ONLY — excluded from the iOS port (D3).** iOS builds the spec:193
> `RemoteConfig` fetch + `minAppVersion` "update required" screen instead. See baseline above.

### 5.1 Entry points

1. **Splash cold-start gate**: `checkForUpdate()` + `showUpdateDialogAndAwait()` (§5.2).
2. **Settings → "Check for updates"** (`settings_item_update_check.xml`, `ic_refresh` +
   chevron, title `settings_check_for_updates` → **"Check for updates"**, `strings.xml:215`):
   `runCheck` always surfaces an outcome — dialog, or toast `update_check_failed` →
   **"Couldn't check for updates. Please try again."** (`:225`), or `update_check_up_to_date` →
   **"FitrahTube is up to date."** (`:224`) (`UpdatePromptFlow.kt:113-123`). Bypasses the
   once-per-process guard by design (`:102-104`).
3. **Available Updates picker Install** → `showPickerInstallDialog` — also bypasses the guard
   ("picker taps are explicit user actions", `:189-233`); double-taps coalesced by
   `pickerInstallMutex.tryLock` inside the coroutine (`:66-85,213`).

### 5.2 Splash gate — exact timing contract

`ui/SplashFragment.kt` + `UpdatePromptFlow.kt`:

- The probe (`updatePromptFlow.checkForUpdate()`) is launched at t=0 in parallel with the
  splash animation (`SplashFragment.kt:152-154`).
- Splash unconditional pre-await time: `600 + 400×3 + 150 + 800 = 2750 ms`
  (`SPLASH_PRE_AWAIT_MS`, `:81-97`). Probe budget `CHECK_TIMEOUT_MS = 2750 ms` is deliberately
  equal, pinned by a unit test (`UpdatePromptFlow.kt:547-564`) — the probe costs zero cold-start
  latency.
- After the animation, the await itself is bounded by `UPDATE_AWAIT_GRACE_MS = 500 ms`
  (`SplashFragment.kt:105,236-238`) — worst case 500 ms extra splash, never a stalled socket's
  15 s+ (`:225-235`). A late probe result still warms the 5-min cache.
- **Deep-link launches skip the prompt entirely** and cancel the probe
  (`SplashFragment.kt:156-165`).
- Timed-out / failed / no-newer probe → `null` → splash routes normally.
- Non-null → `showUpdateDialogAndAwait` **suspends routing until the user dismisses the
  dialog** (Later, Install, back, outside tap) so the prompt fronts the sign-in screen
  (`UpdatePromptFlow.kt:236-315`, `SplashFragment.kt:252-261`). The dialog is shown on the
  **activity** (not the fragment) so an in-flight download survives the splash→signIn
  navigation (`SplashFragment.kt:245-251`).
- `promptDismissedThisProcess` (`UpdatePromptFlow.kt:87-106`): set only by real user action;
  makes the prompt at-most-once per process and short-circuits later probes (`:133-137`).
  Lifecycle teardown (rotation/theme) clears the dismiss listener first so the flag stays
  false and a recreated splash can re-show (`:291-313`).

### 5.3 The dialog

`res/layout/dialog_update_available.xml` + `UpdatePromptFlow.showUpdateDialog` (`:323-411`):

- Custom `MaterialCardView` in a transparent window; width = `min(85% screen, 480dp)`, centered
  (`:401-409`). Corner `corner_radius_large`, elevation `elevation_lg`.
- Green `?attr/colorPrimary` header: `ic_update` icon (`icon_large`) + title
  `update_available_title` → **"New version available"** (`strings.xml:216`) in `colorOnPrimary`.
- Body: `update_version_ready` → **"Version %1$s is ready to download."** filled with
  `info.releaseName` (`:334-335`); then generic `update_body_generic` → **"A new version is
  available. We recommend installing it — older versions may not perform as well as you're used
  to."** (`strings.xml:221`). Release notes are deliberately **not** rendered — they are
  English-only GitHub text (`:356-360`).
- Inline previous-attempt warning (`update_previous_attempt_warning`, `colorError` italic,
  hidden by default): shown when `LastInstallAttempt` holds a FAILURE or ABANDONED record for
  the **same target version** (`:340-355`); text `update_previous_attempt_failed[_with_reason]`
  → **"Last update attempt didn't complete (%1$s). Tap Install again or try ADB sideload if it
  keeps failing."** (`strings.xml:233-234`). Inline, not a toast — a toast fired 50-100 ms
  before the dialog was swallowed by dialog focus (comment `:336-339`).
- **"View full changelog →"** (`update_view_full_changelog`, `strings.xml:220`, primary bold):
  opens `https://github.com/talibfitrah/albunyaantube/releases/tag/v<sanitized>`; the tag is
  passed through `sanitizeSemverDisplay()` (ASCII letters/digits + `.-+_` only,
  `update/SemverDisplay.kt:17-19`) and the tap is a no-op if sanitization empties it
  (`:370-386`).
- Buttons: text button `@string/cancel` → **"Cancel"** (`strings.xml:568`, id
  `update_btn_later`) and filled `update_download_and_install` → **"Update now"** (`:223`).
  Later → dismiss + toast `update_cancelled_warning` → **"Update skipped. The app may not
  perform as well as you're used to until you update."** (`:222`, code `:387-391`).
  There is **no forced-update path**: the dialog is always dismissible (back/outside tap too,
  via the dismiss listener `:369`).

### 5.4 Install pipeline (Android-specific; record only)

- Permission gate: if `REQUEST_INSTALL_PACKAGES` not granted, a second dialog
  (`update_permission_title/message/grant_permission`, `strings.xml:235-237`) routes to the
  system settings page (`UpdatePromptFlow.kt:413-434`).
- Download serialized by `downloadMutex`; a second concurrent attempt just toasts
  `update_downloading` → **"Downloading update…"** (`:441-445,64`). Progress dialog shows
  percent (`update_progress_percent` "%1$d%%"), flips to indeterminate `update_preparing` →
  **"Preparing to install…"** at 100% (`:447-477`, `strings.xml:226-227`).
- `ApkInstaller.download`: HTTPS-only asserted (`ApkInstaller.kt:75-77`); target
  `cacheDir/updates/fitrahtube-update.apk`, prior downloads deleted (`:79-82`); cloned OkHttp
  client with 60 s read / 20 min call timeout (`:96-99`); byte-size verified against the GitHub
  asset size (`:125-130`).
- Signing cert of the downloaded APK verified against the installed app on IO before handoff
  (`UpdatePromptFlow.kt:478-484`); mismatch → `SecurityException` → `recordFailure("signature
  mismatch")` + toast `update_signature_mismatch` (`:511-524`, `strings.xml:229`). Any other
  failure → `recordFailure(message.take(120))` + `update_download_failed` (`:525-531`).
- `LastInstallAttempt` (`update/LastInstallAttempt.kt`): own DataStore (`updateDataStore`),
  statuses PENDING/SUCCESS/FAILURE + synthetic **ABANDONED** for a PENDING older than 24 h
  (`STALE_PENDING_THRESHOLD_MS :139`, promotion `:117-121`); `snapshot()` auto-clears when the
  recorded target equals the running version (`:111-116`). `recordPending` fires immediately
  before the PackageInstaller handoff, inside an alive-activity guard (`UpdatePromptFlow.kt:485-500`).
- `InstallStatusActivity` (transparent trampoline for PackageInstaller callbacks — Activity
  PendingIntent to dodge Huawei/Honor BAL blocks, `InstallStatusActivity.kt:22-27`):
  `STATUS_PENDING_USER_ACTION` launches the OS confirm intent (missing intent → recorded
  failure "missing install confirmation", `:51-72`); `STATUS_SUCCESS` → `recordSuccess`
  (`:73-78`) and a self-kill on OEMs that keep the old process alive (`:165`); failures map to
  human reasons — "cancelled", "blocked by system", "package conflict", "incompatible",
  "invalid APK", "out of storage", "failed" (`:184-190`); user-aborted installs are not nagged
  (`:92-93`).

None of this maps to iOS distribution and none is needed — iOS distribution is the App Store
(D3). Formerly open question Q1; **RESOLVED**, see §8.

---

## 6. Available Updates screen

> **ANDROID RECORD ONLY — excluded from the iOS port (D3).** iOS builds the spec:193
> `RemoteConfig` fetch + `minAppVersion` "update required" screen instead. See baseline above.

### 6.1 Entry

Settings → "About & Support" card (`settings_about_support` → **"About & Support"**,
`strings.xml:553`) → row `settings_item_available_versions.xml` (`ic_refresh` icon, title
`settings_available_updates` → **"Available updates"**, `strings.xml:805`, chevron). Row +
divider hidden on Play installs; otherwise navigates to `availableVersionsFragment`
(`SettingsFragment.kt:381-393`). The sibling "Check for updates" row sits in the same card
(`fragment_settings.xml:304-323`).

### 6.2 ViewModel contract

`ui/settings/availableversions/AvailableVersionsViewModel.kt`:

- `load()`: coalesces re-entries via an in-flight Job (`:82,92-93`); re-resolves the display
  locale **per call** — test override → `AppCompatDelegate.getApplicationLocales()[0]` →
  `Locale.getDefault()` (`:50-53,94-97`) — so an in-app language change is picked up without VM
  recreation.
- Fetches `catalog.list(limit = 5)` (`PICKER_PAGE_SIZE :133`) and `catalog.summaries()`
  **concurrently** (`:101-107`).
- Each row: `ReleaseRow(info, localizedSummary, state)` (`update/ReleaseRow.kt:10-14`) with
  `state` = `Current` (exact string match on trimmed versionName), `Newer`
  (`UpdateChecker.isNewerVersion`), else `Older` (`:122-126`).
- `loading` starts **true** so the first render shows the spinner, not a flicker of the empty
  state (`:71-76`).

### 6.3 Screen and row rendering

`res/layout/fragment_available_versions.xml` — mirrors Settings exactly: `background_gray`
root, `MaterialToolbar` (`?actionBarSize`, surface bg, `elevation_sm`, `ic_arrow_back`, title
"Available updates", Headline6), ScrollView with `padding spacing_md` +
`paddingBottom bottom_nav_height`, one `MaterialCardView` (`corner_radius_medium`, elevation 0)
wrapping a non-nested-scrolling RecyclerView. Centered overlay `ProgressBar` and empty-state
TextView `available_versions_empty` → **"No releases available right now. Try again later."**
(`strings.xml:810`).

Fragment glue (`AvailableVersionsFragment.kt`):

- Row dividers: `MaterialDividerItemDecoration`, `dividerInsetStart = spacing_lg`, last item
  undecorated (`:70-75`).
- Visibility is computed from `rows combine loading` (StateFlow dedup made a rows-only
  collector miss the empty case, `:81-101`): card GONE when rows empty; empty state visible iff
  `rows.isEmpty() && !loading`; spinner iff loading.
- `load()` fires once in `onViewCreated` (`:104`). **No pull-to-refresh**; refresh = leave and
  re-enter (cache TTL 5 min applies).

Row (`item_available_version.xml` + `AvailableVersionsAdapter.kt`):

- Version title: `"v" + sanitizeSemverDisplay(versionName)`, fallback **"v?"** when sanitization
  empties it (`:55-60`); `text_subtitle` bold.
- Date line: `publishedAt` formatted `FormatStyle.LONG` in the in-app locale
  (`AppCompatDelegate.getApplicationLocales()[0]` ?: system), system zone; hidden when absent
  (`:62-76`).
- Summary: the `releases-meta.json` line, `maxLines 2`, hidden when null/blank (`:78-79`).
- Affordances by state (`:81-109`):
  - **Newer** → "Install" button (`available_versions_install`, `strings.xml:806`) *and* the
    whole row tap both call `onInstallClick` → `showPickerInstallDialog` (same dialog as the
    splash gate, including the previous-attempt warning; `AvailableVersionsFragment.kt:42-52`).
  - **Current** → "Installed" chip (`available_versions_installed`, `:807`), row not clickable.
  - **Older** → italic note `available_versions_downgrade_deferred` → **"Downgrade not
    available"** (`:808`); row tap → snackbar `available_versions_downgrade_snackbar` →
    **"Downgrading older versions isn't supported yet. Coming in a future update."** (`:809`,
    fragment `:54-60`).
- Diff: identity by `info.versionName`, contents by whole value (`:113-118`).

Translations: `values-ar/strings.xml` and `values-nl/strings.xml` also matched the
update/safe-mode key grep; per-key coverage not audited here.

---

## 7. Behavioural checklist for the iOS implementer

**Binding baseline: spec:22 (D3) + spec:193 (RemoteConfig schema) + RULINGS.md ruling 6 — see
the BASELINE banner at the top of this brief. Items 1–3 are what the iOS implementer actually
builds; item 4 records what §§3–6's checklist would have said and why none of it ports.**

1. **Build a `RemoteConfig` fetcher per spec:193**: bundled default JSON shipped in the app;
   last-known-good cache; fetch on launch and on `willEnterForeground`; ≥15 min spacing between
   fetches; response body capped at 64 KiB; schema includes a `minAppVersion` field.
2. **`minAppVersion` gate**: when the running app version is below the fetched
   `minAppVersion`, show a blocking "update required" screen linking to the App Store listing.
   This is iOS-new behaviour (D3, RULINGS.md ruling 6, phase 2) — Android has **no**
   forced-update equivalent (§5 records why: the prompt is always declinable, no version is
   ever blocked).
3. Safe Mode: port the persisted boolean (default ON, "Show only family-friendly content"
   label) as the setting's presentation, but wire it to real filtering on iOS per spec D12 —
   Android's version is enforced nowhere, client or server (§2.3), and reproducing that as-is
   would ship a placebo (§8 Q2).
4. Do **not** build: a GitHub-releases semver comparator, an Available Updates screen, an
   update-prompt splash gate/probe budget, an APK/PackageInstaller-shaped install pipeline, or
   Play-Store-install suppression logic. All of §§3–6 is Android record excluded by D3 — none
   of it is a checklist item for the iOS implementer.

---

## 8. Open questions

**Q1 — RESOLVED: nothing survives of the update flow.** Previously asked what, if anything, of
the install half (APK download, `REQUEST_INSTALL_PACKAGES`, PackageInstaller session, signature
verify, `LastInstallAttempt` banner, `InstallStatusActivity`) or the read-only update-check UI
(Available Updates, `releases-meta.json`) ports to iOS. Spec D3 (`ios-app-design.md:22`) +
RULINGS.md ruling 6 settle it: **none of it ports.** iOS's only related surface is the
spec:193 `RemoteConfig` fetch + `minAppVersion` gate (§7 items 1–2) — no Available Updates
screen, no App Store deep link on a "Newer" row, no read-only what's-new list. A future phase
adding release notes would be new scope, not a port of §§3–6.

**Q2 — Safe Mode: port, wire, or drop?** Android ships a default-ON switch that gates nothing
(§2.3) and a backend with no safe-mode parameter. Porting it as-is reproduces a placebo;
wiring it to real filtering changes behaviour vs Android and needs a backend contract that does
not exist. Which?

**Q3 — RESOLVED: iOS does NOT mirror the absence.** Previously asked whether iOS should mirror
Android's total absence of a `minAppVersion`/forced-update gate (Android's prompt is always
dismissible, no version is ever blocked). It should not: spec:22 (D3) and spec:193 explicitly
add a `minAppVersion` gate that Android never had, and RULINGS.md ruling 6 confirms it as
phase-2, iOS-new work. This is a deliberate iOS/Android divergence, not parity — see §7
items 1–2.

**Q4 — `releases-meta.json` branch pin.** Code reads `develop` (`ReleaseSummaryFetcher.kt:122-123`,
with a TODO + test to flip on first stable); CLAUDE.md says the screen reads from `main`. If iOS
consumes this file, which branch — and does the flip-on-stable rule apply to both platforms?

**Q5 — Play-parity asymmetry on the manual check row.** Play-Store installs hide "Available
updates" but keep "Check for updates", which can then only ever toast "up to date"
(§3.4). For an App Store build: hide both, keep the asymmetry, or repurpose the row?

**Q6 — Splash-timing invariant.** Android pins probe budget = splash pre-await (2750 ms) +
500 ms grace, with a test enforcing the equality (`UpdatePromptFlow.kt:547-564`). The iOS splash
(if any) has different timing; the invariant "probe budget ≥ unconditional splash time, small
bounded grace after" needs re-deriving rather than copying 2750/500.

**Q7 — Developer menu.** The hidden 7-tap dev dialog toggles Android playback flags
(`PlaybackFeatureFlags`) that have no iOS counterpart in Phase 2. Skip entirely, or reserve the
gesture for an iOS diagnostics panel?

**Q8 — `FEATURED_CATEGORY_ID` hardcode** (`FeaturedListViewModel.kt:199`) — duplicate of
Phase 1 Q6, kept here because it is the one value a real remote config would obviously own.

**Q9 — Semver display sanitizer.** Android strips non-ASCII from version tags at every display
and URL site (`SemverDisplay.kt:17-19`) as a homoglyph defence. Carry the same allowlist on iOS,
or trust the release pipeline?
