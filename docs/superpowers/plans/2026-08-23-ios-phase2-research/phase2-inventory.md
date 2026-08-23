# Phase-2 research — Android surface inventory and phase classification

Scope: every Android screen, dialog, menu, and user-visible behaviour, walked from both
navigation graphs (`res/navigation/app_nav_graph.xml`, `res/navigation/main_tabs_nav.xml`),
all 53 Fragment/Activity/Dialog classes, all 11 `res/menu/*.xml`, `AndroidManifest.xml`,
and the full `java/com/albunyaan/tube/**` tree. Every claim cites
`android/app/src/main/**` file:line. Classification baseline:
spec `docs/superpowers/specs/2026-08-23-ios-app-design.md` §15 (delivery phases) and §2
(decisions D1–D16); phase-1 rulings in
`docs/superpowers/plans/2026-08-23-ios-phase1-research/RULINGS.md`.

Phase key: **P1** = iOS phase 1, shipped (catalog UI). **P2** = player / Shorts /
detail / report / share / links / remote config / Safe Mode. **P3** = downloads / Cast /
AirPlay. **P4** = accounts / Me / sync / submissions / suggest / import. **D3-EXCLUDED** =
in-app update system, ruled out of iOS entirely (spec §2 D3). **UNASSIGNED** = fits no
phase — the deliverable of this document (§6).

Assigned rows are one-liners on purpose; deep-dives belong to the per-phase research
files. UNASSIGNED rows carry the detail.

---

## 0. TL;DR

1. The sweep found **no hidden major feature**: no watch history, no continue-watching, no
   widgets, no notification-preferences screen, no licenses screen. Notifications exist
   only for downloads (P3) and the media session (P2).
2. **Nine UNASSIGNED items** (§6): four unowned artefacts (Me-telemetry log dialog, dead
   `FollowedChannel` store, dormant player analytics readout, stream-indexing side
   channel, log-only telemetry pipeline) and four **phase-boundary affordances** — buttons
   that live on Phase-2 screens but belong to P3/P4 features (subscribe/save, download,
   Cast, share-metadata publish).
3. The whole in-app update system (12 classes + 2 dialogs + 1 screen + splash probe +
   2 Settings rows + 2 manifest permissions) is **D3-excluded**, replaced on iOS by the
   P2 remote-config `minAppVersion` screen (RULINGS.md #6).
4. Three menu XMLs are dead (never inflated): `detail_share_menu.xml`, `menu_report.xml`,
   `filter_menu.xml`.
5. Sync covers exactly three entity types — `favorites`, `subscriptions`, `playlists`
   (`SyncManager.kt:153-155`) — so the subscribe/save buttons on the two P2 detail
   screens write **P4-synced** stores. P2 must decide what those buttons do before P4
   exists (§6.6).

---

## 1. Navigation-graph destinations, classified

### 1.1 `app_nav_graph.xml` (pre-shell)

| Destination | Class | Phase | Citation |
|---|---|---|---|
| `splashFragment` (start) | `ui/SplashFragment.kt` + `ui/SplashRouter.kt` | **P1 done** (its update probe at `SplashFragment.kt:147-152` is D3-EXCLUDED) | `app_nav_graph.xml:7-34` |
| `onboardingFragment` | `ui/OnboardingFragment.kt`, `onboarding/*` | **P1 done** | `app_nav_graph.xml:36-55` |
| `signInFragment` | `ui/auth/SignInFragment.kt` | **P4** | `app_nav_graph.xml:58-78` |
| `emailVerificationFragment` | `ui/auth/EmailVerificationFragment.kt` | **P4** | `app_nav_graph.xml:81-99` |
| `mainShellFragment` | `ui/MainShellFragment.kt` (tabs, offline banner, fullscreen nav-hide) | **P1 done** | `app_nav_graph.xml:101-104` |
| `profileBootstrapFragment` | `ui/bootstrap/ProfileBootstrapFragment.kt` | **P4** | `app_nav_graph.xml:107-129` |
| `ageIneligibleFragment` | `ui/bootstrap/AgeIneligibleFragment.kt` (terminal) | **P4** | `app_nav_graph.xml:132-143` |

`MainActivity` (`ui/MainActivity.kt`) splits across phases: shell host + back handling
**P1 done**; `ACTION_VIEW` deep-link dispatch (`:183,267`) and the
`PlaybackService.ACTION_OPEN_PLAYER` notification-tap reopen (`:182,203-208`) **P2**;
account-status terminal dialog (`:118-153`) **P4**.

### 1.2 `main_tabs_nav.xml` (inside the shell)

| Destination | Class | Phase | Citation |
|---|---|---|---|
| `homeFragment` (start) | `ui/HomeFragment.kt` (+ kebab popup `R.menu.home_menu`, `HomeFragment.kt:300`: Downloads / Settings) | **P1 done** (Downloads item targets a P3 screen — §7) | `main_tabs_nav.xml:8-26` |
| `channelsFragment` | `ui/ChannelsFragmentNew.kt` | **P1 done** | `:28-35` |
| `playlistsFragment` | `ui/PlaylistsFragmentNew.kt` | **P1 done** | `:37-44` |
| `videosFragment` | `ui/VideosFragmentNew.kt` | **P1 done** | `:46-49` |
| `meFragment` | `ui/me/MeFragment.kt` + `MeViewModel` + chips/feed/awaiting adapters + `menu_me_kebab` (Profile / My Submissions / Suggest / Import / Sign out) + one-time import offer | **P4** (spec §13) | `:51-68` |
| `profileFragment` | `ui/me/profile/ProfileFragment.kt` | **P4** | `:70-74` |
| `mySubmissionsFragment` | `ui/me/submissions/MySubmissionsFragment.kt` + overflow menu (`MySubmissionsFragment.kt:93`) | **P4** | `:76-80` |
| `suggestContentFragment` | `ui/me/suggest/SuggestContentFragment.kt` (+ `data/search/YouTubeSearchRepository.kt`, consumed only by `SuggestContentViewModel.kt`) | **P4** | `:82-86` |
| `importFromYouTubeFragment` | `ui/me/importflow/ImportFromYouTubeFragment.kt` + `data/importflow/*`, `data/youtube/*` | **P4** | `:88-92` |
| `downloadsFragment` | `ui/download/DownloadsFragment.kt` (+ its action to Favorites `:98-100`) | **P3** | `:94-101` |
| `favoritesFragment` | `ui/favorites/FavoritesFragment.kt` + `favorites_menu` clear-all | **P1 done** | `:103-106` |
| `channelDetailFragment` | `ui/detail/ChannelDetailFragment.kt` + 5 tab fragments + kebab (`:148`, share/report) + deep links | **P2** | `:108-134` |
| `playlistDetailFragment` | `ui/detail/PlaylistDetailFragment.kt` + kebab (`:233`) + deep links; carries `downloadPolicy` arg (`:156-158`) | **P2** (download bits → §6.7) | `:136-169` |
| `categoriesFragment` / `subcategoriesFragment` | `ui/categories/*` | **P1 done** | `:171-190` |
| `settingsFragment` | `ui/settings/SettingsFragment.kt` + Language/Theme/Quality dialogs | **P1 done** (update rows `SettingsFragment.kt:375-381` D3-EXCLUDED; Downloads row targets P3) | `:192-213` |
| `aboutFragment` | `ui/settings/AboutFragment.kt` (7-tap dev gate) | **P1 done** | `:215-218` |
| `availableVersionsFragment` | `ui/settings/availableversions/*` | **D3-EXCLUDED** | `:220-223` |
| `searchFragment` | `ui/SearchFragment.kt` + history/results adapters | **P1 done** | `:225-228` |
| `featuredListFragment` | `ui/FeaturedListFragment.kt` | **P1 done** | `:230-242` |
| `playerFragment` | `ui/player/PlayerFragment.kt` + deep links | **P2** | `:244-263` |
| `shortsPlayerFragment` | `ui/shorts/ShortsPlayerFragment.kt` + deep link | **P2** | `:265-304` |
| global actions (channel/playlist detail, player, shorts) | — | **P2** | `:306-321` |

---

## 2. Phase 2 — assigned surface (enumeration only)

Spec §15 row 2: "InnerTubeKit, player, Shorts, channel/playlist detail, report, share +
metadata publish, deep/universal links, remote config, Safe Mode."

- **Player screen** `ui/player/PlayerFragment.kt` (4278 lines, `wc -l`) + `PlayerViewModel` +
  `UpNextAdapter` + `PlayerGestureDetector` + `PlayerDescriptions`. Visible controls
  (`res/layout/fragment_player.xml` ids): quality / subtitle / audio-track /
  audio-language (dub) buttons, audio-only toggle, fullscreen, minimize, share, report,
  favorite (writes the P1 `FavoriteVideo` store), download (§6.7), cast (§6.8), up-next
  list + empty state, description card, `excludedMessage`, error + recovery overlays
  with retry/refresh-stream buttons. Toolbar menu `player_menu.xml`: captions, enter
  PiP, report (`PlayerFragment.kt:1794,1804`).
- **PiP**: `AndroidManifest.xml:44` (`supportsPictureInPicture`),
  `PlayerFragment.kt:1804,1993-1995`. Assigned to the P2 player by spec (spec:212).
- **Background playback / media session**: `player/PlaybackService.kt` (Media3
  `MediaSessionService`, manifest `:133-140`) + `MediaSessionMetadataManager.kt`;
  notification tap reopens the player (`MainActivity.kt:182,203-208`). Spec:211-213.
- **Sheets/dialogs**: `ui/shared/QualityPickerDialog.kt`, `ui/shared/SubtitlePickerDialog.kt`,
  `ui/shorts/AudioLanguageDialog.kt`.
- **Shorts** `ui/shorts/ShortsPlayerFragment.kt` + `ShortsPlayerViewModel` +
  `PlayerBinder` + pager; kebab `menu_shorts_kebab.xml` = quality + report
  (`ShortsPlayerFragment.kt:771`); download entry → §6.7;
  feed `data/shorts/ShortsFeedRepository.kt`.
- **Channel detail** `ui/detail/ChannelDetailFragment.kt`: collapsing header (banner,
  avatar, verified badge, subscriber count), in-channel search bar, exclusion banner,
  tabs Videos / Shorts / Live / Playlists / About (`ui/detail/tabs/*`,
  `ui/detail/adapters/*`), kebab share + report (`:148`, `menu_detail_kebab.xml`),
  subscribe button → §6.6.
- **Playlist detail** `ui/detail/PlaylistDetailFragment.kt`: hero header, play-all,
  shuffle, in-playlist search, exclusion banner, kebab (`:233`), save button → §6.6,
  download-all + policy label → §6.7.
- **Report** `ui/report/ContentReportBottomSheet.kt` + `ReportViewModel` +
  `data/report/*` + `data/source/api/ReportApi.kt`.
- **Share**: `share/ShareLinks.kt`; `share/ShareMetadataPublisher.kt` → §6.9.
- **Deep/universal links**: manifest intent filters `AndroidManifest.xml:52-129`
  (`albunyaantube://{channel|playlist|video}` + `https://app.fitrahtube.com/...`),
  nav-graph `deepLink` elements (`main_tabs_nav.xml:128-133,163-168,257-262,303`),
  `MainActivity.handleDeepLink` (`:267`).
- **Extraction engine** (`InnerTubeKit` equivalent): `data/extractor/*`
  (NewPipe client, PoToken WebView provider, nsig solver, client rotator, dub audio,
  rate limiters, metadata cache), `player/*` engine files (synthetic DASH, HLS poison
  registry, Cronet, pre-buffer, ABR/track-selection policies,
  `GlobalStreamResolver.kt`, `PlayerRepository.kt`), prefetch
  (`player/StreamPrefetchService.kt`, `player/PredictivePrefetchController.kt`),
  `player/PlaybackFeatureFlags.kt`.
- **Developer dialog P2 additions** (RULINGS.md #35): resolver feature-flag toggles and
  cooldown trip/reset (`ui/settings/DeveloperSettingsDialog.kt:100-204`).
- **Remote config + Safe Mode**: no Android counterpart exists — zero `RemoteConfig`
  hits in `java/**`; `safe_mode` is stored (default `true`) and read by nothing
  (spec §2 D12). Both are iOS-new P2 work by spec definition.

---

## 3. Phase 3 — assigned surface (enumeration only)

- `ui/download/DownloadsFragment.kt` + `DownloadViewModel` + `DownloadsAdapter`;
  nav action Downloads → Favorites (`main_tabs_nav.xml:98-100`).
- `ui/player/DownloadQualityDialog.kt` (download quality/audio-only picker; invoked
  from Player `PlayerFragment.kt:562-566` and Shorts `ShortsPlayerFragment.kt:751-752`).
- `download/*`: `DownloadWorker`, `DownloadScheduler`, `DownloadStorage`,
  `DownloadNotifications.kt` (the app's only notification channel besides the media
  session), `DownloadExpiryWorker`/`DownloadExpiryPolicy`, `FFmpegMerger`,
  `DownloadRepository`, error types; `di/DownloadModule.kt`.
- `data/source/RetrofitDownloadService.kt`, `data/source/DownloadStreamSelector.kt`,
  `data/source/api/DownloadApi.kt`, `data/model/api/models/Download*.kt`.
- Manifest: FileProvider for completed files (`AndroidManifest.xml:142-150`),
  WorkManager foreground service (`:185-189`), `POST_NOTIFICATIONS` (`:16`).
- **Cast**: `player/CastOptionsProvider.kt` + manifest meta-data
  (`AndroidManifest.xml:169-171`); `castButton` in `fragment_player.xml` → §6.8.

## 4. Phase 4 — assigned surface (enumeration only)

- Auth: `ui/auth/*`, `auth/*` (repos, `FirebaseAuthInterceptor`,
  `AccountStatusInterceptor`, `AuthErrorMapper`), `util/EmailShape.kt`, `util/PhoneFormat.kt`.
- Bootstrap: `ui/bootstrap/*` (+ 422 → AgeIneligible, `app_nav_graph.xml:118-121`).
- Me tab: `ui/me/*` (fragment, view model, chips / favorites-row / week-section /
  awaiting-imports adapters, `menu_me_kebab.xml` incl. role-gated hidden items),
  feed engine `data/me/*` (Atom fetch/parse, `MeFeedRepository`, `WeekBucket`,
  `ChannelDeepPaginator`, `MeRefreshTelemetry`), background refresh
  `data/me/work/RefreshScheduler.kt` + `RefreshSubscriptionsWorker.kt`,
  `app/AppLifecycleTracker.kt` (foreground trigger).
- Profile: `ui/me/profile/*` incl. the three edit bottom sheets; account deletion;
  `data/account/*`.
- Submissions: `ui/me/submissions/*` (list, overflow menu, Submit/Edit bottom sheets),
  `data/approvals/*`.
- Suggest: `ui/me/suggest/*` + `data/search/*` (YouTube search used only here).
- Import: `ui/me/importflow/*`, `data/importflow/*`, `data/youtube/*`.
- Sync: `data/sync/*` (`SyncManager.kt` — entity types `favorites`, `subscriptions`,
  `playlists`, `:153-155`), `data/local/AccountBinding*`, `SyncState*`; synced stores
  `SubscribedChannel`, `SavedPlaylist`, `PlaylistVideoLink` (+ `FavoriteVideo`, whose
  UI shipped in P1); `data/subscriptions/SubscriptionRepository.kt` +
  `SubscriptionLimitGuard.kt`.
- Account-status terminal dialog: `MainActivity.kt:118-153`.

## 5. D3-EXCLUDED — the in-app update system

Ruled out of iOS by spec §2 D3 ("In-app update is not [in scope]; App Store handles
updates; `minAppVersion` in remote config shows an 'update required' screen" — the
replacement is P2 work, RULINGS.md #6). Complete Android component list, so nothing in
it is mistaken for unported scope later:

| Component | Citation |
|---|---|
| `update/` package: `UpdateChecker`, `UpdatePromptFlow`, `ApkInstaller`, `InstallSource`, `InstallStatusActivity`, `ReleaseCatalogCache`, `ReleaseSummaryFetcher`, `ReleaseRow`, `SemverDisplay`, `LastInstallAttempt`, `CallExtensions` | `java/com/albunyaan/tube/update/*` |
| Available Updates screen | `ui/settings/availableversions/*`, `main_tabs_nav.xml:220-223` |
| Splash update probe (parallel with animation, bounded timeout) | `SplashFragment.kt:147-152` |
| Settings rows: manual "check for update" (always visible, toasts "up to date" if no newer release) + "Available updates" row + divider (hidden on Play-Store installs via `InstallSource`; manual-check row does **not** hide) | `SettingsFragment.kt:375-393` |
| Update prompt + progress dialogs | `res/layout/dialog_update_available.xml`, `res/layout/dialog_update_progress.xml` |
| Install trampoline activity | `AndroidManifest.xml:161-166` |
| Install permissions | `AndroidManifest.xml:20-27` (`REQUEST_INSTALL_PACKAGES`, `UPDATE_PACKAGES_WITHOUT_USER_ACTION`) |

---

## 6. UNASSIGNED — the deliverable

Items that fit no phase as the spec and rulings stand. 6.1–6.5 are unowned artefacts;
6.6–6.9 are phase-boundary affordances sitting **on Phase-2 screens** and therefore
block the Phase-2 plan until decided.

### 6.1 Me-telemetry log dialog

`ui/settings/MeTelemetryLogDialog.kt` (factory `:74`) — a developer-only viewer for the
Me-feed refresh telemetry ring buffer (`data/me/MeRefreshTelemetry`), opened from the
developer dialog's "Show telemetry" button (`DeveloperSettingsDialog.kt:236-246`).
RULINGS.md #35 assigns the developer dialog's phase-2 additions as "resolver
counters/cooldown" only; the Me-feed log viewer diagnoses a **P4** subsystem, and no
phase names it.

### 6.2 `FollowedChannel` store — dead code

`data/local/FollowedChannel.kt`, `FollowedChannelDao.kt`,
`data/local/FollowedChannelsRepository.kt`: provided by `di/DatabaseModule.kt` and
declared in `data/local/AppDatabase.kt`, but **zero consumers** — no UI reads or writes
it and `SyncManager.kt` never touches it (the subscribe feature uses
`SubscribedChannel` instead, `ChannelDetailFragment.kt:333`). Defect: dead table +
repository. Undecided whether the iOS SwiftData mirror of "Room v11 columns"
(spec:272) reproduces it or drops it.

### 6.3 Player analytics readout — dormant UI

`res/layout/fragment_player.xml:677-686`: `analyticsHeader` and `analyticsStatus` are
`visibility="gone"` and nothing ever shows them, yet `PlayerFragment.kt:1547` binds a
rendered event string on every state emission (`renderAnalytics`,
`PlayerFragment.kt:2030-2049`; strings `player_analytics_none`, `player_event_*`;
source `analytics/PlaybackMetricsCollector.kt`). Defect: dead UI kept warm. No ruling
on port vs drop for the P2 player.

### 6.4 Stream-indexing side channel

`data/index/IndexRepository.kt:17-45` fire-and-forgets
`POST` bodies (`IndexStreamsRequest("CHANNEL"|"PLAYLIST", id, items)`) through
`data/source/api/IndexApi.kt` whenever the NewPipe detail repositories extract lists
(`data/channel/NewPipeChannelDetailRepository.kt`,
`data/playlist/NewPipePlaylistDetailRepository.kt`); failures log-and-drop. It ships
with the P2 detail screens on Android, but neither spec §15's P2 scope line nor the
§12 backend-additions table names it. Undecided: port with the P2 detail port, or drop.

### 6.5 Local telemetry pipeline

`telemetry/TelemetryClient.kt` defines structured events (`download.started/progress/
completed/failed`, `favorite.toggle_failed`); the only binding is the log-only
`LogTelemetryClient` (`di/DataModule.kt:160-161`), with
`analytics/TelemetryExtractorMetricsReporter.kt` funnelling extractor metrics into it
and `player/StreamRequestTelemetry.kt` keeping an in-memory 403-failure ring
(`:32-40`). Nothing leaves the device. No phase decides whether iOS gets an
equivalent (the P2 dev-dialog counters are the only named consumer of anything similar).

### 6.6 Subscribe / save-playlist buttons on P2 detail screens (boundary)

- Channel detail subscribe: writes `SubscribedChannel` through
  `SubscriptionLimitGuard.trySubscribe` (30-channel cap; cap breach shows snackbar
  `me_subscription_cap_reached`) — `ChannelDetailFragment.kt:320-350` (write at `:333`).
- Playlist detail save: writes `SavedPlaylist` (unlimited, no guard) —
  `PlaylistDetailFragment.kt:165-196` (write at `:180`).

Both work for **guests** on Android (local Room rows, tagged to the account and synced
only after sign-in — `SyncManager.kt:140-155`). The screens are P2; the stores, the
library UI that displays them (Me tab), and sync are P4. Undecided: ship the buttons in
P2 working locally (Android guest behaviour), or omit them until P4.

### 6.7 Download affordances on P2 screens (boundary)

- Player: `downloadButton` + `downloadStatus` (`res/layout/fragment_player.xml`),
  wired to `DownloadQualityDialog` (`PlayerFragment.kt:562-566`).
- Playlist detail: `downloadPlaylistButton` + `downloadPolicyText`
  (`res/layout/fragment_playlist_detail.xml`) and the `downloadPolicy` nav argument
  (default `"ENABLED"`, `main_tabs_nav.xml:156-158`).
- Shorts: download action opens `DownloadQualityDialog`
  (`ShortsPlayerFragment.kt:751-752`).
- P1-done screens already link to the Downloads screen: Home kebab
  (`HomeFragment.kt:300`, `res/menu/home_menu.xml`) and the Settings row
  (`main_tabs_nav.xml:203-207`).

All of this chrome sits on P2 (or shipped-P1) screens while the feature is P3.
Undecided: hidden, disabled-with-explanation, or deferred layout in P2.

### 6.8 Cast button in the player layout (boundary)

`castButton` in `res/layout/fragment_player.xml`; Cast SDK wiring is P3
(`player/CastOptionsProvider.kt`, `AndroidManifest.xml:169-171`). Extra wrinkle: the
iOS P2 player host is `AVPlayerViewController` with stock transport that includes an
**AirPlay route picker by default** (spec:202), while D3/§15 place AirPlay in P3 —
the P2 plan must either suppress it or accept AirPlay arriving one phase early.

### 6.9 Share-metadata publish requires auth (sequencing gap)

`share/ShareMetadataPublisher.kt:44-47`: publish is **silently skipped when no Firebase
user is signed in** (the backend requires a Bearer token; the share URL still renders a
registry-backed card via GET fallback). Spec §15 puts "share + metadata publish" in P2,
but sign-in does not exist until P4 — so on iOS the publish path is unreachable for the
whole of P2/P3. Undecided: build it dormant in P2, or move the publisher to P4.

---

## 7. Dead code and factual defect notes from the sweep

- Menus never inflated (only six `R.menu.*` call sites exist — grep over `java/**`):
  `res/menu/detail_share_menu.xml`, `res/menu/menu_report.xml`,
  `res/menu/filter_menu.xml` (the last already recorded in
  `2026-08-23-ios-phase1-research/content-lists.md` §6.3).
- Defect: dead `FollowedChannel` table/DAO/repository (§6.2).
- Defect: dormant player analytics rows (§6.3).
- Paging 3 stack dead — already ruled not-ported (RULINGS.md #21).
- No watch history, continue-watching, resume-position persistence, widgets, or
  app-shortcuts anywhere in `java/**` / `res/**` (case-insensitive grep for
  watch-history/continue-watching returned zero; no `appwidget` resources; no
  `shortcuts.xml`).

---

## 8. Open questions

**Q1 — Me-telemetry log dialog (§6.1).** Which phase owns
`MeTelemetryLogDialog` — bundle with the P4 Me feed it diagnoses, add to the P2
dev-dialog additions, or drop as Android-only operator tooling?

**Q2 — `FollowedChannel` store (§6.2).** Dead on Android. Does the iOS SwiftData
schema mirror reproduce the table for wire parity, or omit it?

**Q3 — Player analytics readout (§6.3).** Views permanently `gone` but state still
rendered. Port the readout (as a debug feature), or drop views + `renderAnalytics`
work from the iOS player?

**Q4 — Stream indexing (§6.4).** Should the iOS P2 detail port replicate the
`POST` stream-index side channel (unnamed in spec §12/§15), or is it Android-only?

**Q5 — Telemetry pipeline (§6.5).** Log-only on Android. Any iOS equivalent, or none?

**Q6 — Subscribe/save in P2 (§6.6).** Do the P2 channel/playlist detail screens ship
the subscribe (30-cap) and save-playlist buttons working against local storage for
guests, as Android does, or hide them until P4?

**Q7 — Download chrome in P2 (§6.7).** What do the player/playlist/Shorts download
affordances (and the `downloadPolicy` label) show while P3 doesn't exist?

**Q8 — AirPlay leakage into P2 (§6.8).** `AVPlayerViewController` stock transport
includes AirPlay; D3 schedules AirPlay for P3. Suppress in P2 or accept early?

**Q9 — Share-metadata publish before accounts (§6.9).** Publish is auth-gated and
auth is P4. Build dormant in P2 (spec's phase) or move to P4?

**Q10 — Guest Me tab between P1 and P4.** The shipped P1 shell has five tabs (D11)
including Me, but every Me behaviour is P4 (`main_tabs_nav.xml:51-68`, spec §13).
Confirm what the iOS Me tab shows during P2/P3 (Android guest Me = favorites +
sign-in card — spec §13) and whether any of it moves earlier.
