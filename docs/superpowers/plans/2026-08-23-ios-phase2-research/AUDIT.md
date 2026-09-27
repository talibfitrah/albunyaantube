# Phase-2 research corpus — completeness audit

Audited 2026-08-23 against `android/app/src/main/**` (and backend/spec where cited).
Method: full read of all 7 briefs; targeted greps for player/detail/share/config behaviours
(PiP, notification actions, headphone/interruption, orientation, analytics, rate limits,
deep-link edges, player-launched dialogs); 5 spot-checked citations per brief (35 total),
opened in source.

---

## 1. Missing (no brief covers it)

### M1 — Audio focus / interruption posture is undocumented (biggest gap)
Grep for `setAudioAttributes|AudioAttributes|AudioFocus|handleAudioFocus` over `java/` →
**zero hits**. The app never requests Android audio focus: the only interruption handling
anywhere is `setHandleAudioBecomingNoisy(true)` (`ui/player/PlayerFragment.kt:974`,
`ui/shorts/ShortsPlayerViewModel.kt:101`), which player.md §3 does record. Consequences no
brief states: the app does **not** pause for phone calls or other apps' playback, does not
duck, and can play simultaneously with another audio app. On iOS an AVAudioSession
category + interruption policy is unavoidable and there is no Android contract to mirror —
this needs an explicit ruling (mirror the no-focus behaviour is not even possible on iOS).

### M2 — Main-player audio-language (dub) picker flow
player.md mentions only the globe button's visibility (§8.2, `PlayerFragment.kt:1520-1525`)
and names the `AudioTrackSwapReady`/`DubAudioResolveFailed` events (§2.1). The actual
main-player flow is unspecced: `showAudioLanguagePicker()` requires `Ready` + ≥2 languages
and labels the original track `shorts_audio_track_original_prefix` "Original: %1$s"
(`PlayerFragment.kt:1952-1966`); the Fragment-Result listener maps the picked code back to a
representative track and calls `viewModel.selectAudioTrack` (`:512-536`); swap is applied by
the `AudioTrackSwapReady` handler (`:1572-1589`) and failure by `DubAudioResolveFailed`
(`:1659`). The dialog (`ui/shorts/AudioLanguageDialog.kt:22`) is specced only for Shorts
(playlist-detail-shorts §9.4) — the regular player reuses it and no brief says so.

### M3 — System back while fullscreen leaves the player, it does not exit fullscreen
`PlayerFragment` registers **no** `OnBackPressedCallback` (grep); back is handled solely by
`MainActivity.kt:81-107`, which pops the nested nav stack. So back in fullscreen pops the
player destination entirely (fullscreen cleanup then rides `onDestroyView`,
`PlayerFragment.kt:822-829`). player.md §7 covers button-exit and rotation-exit but never
the system-back/gesture path — a YouTube-style "back exits fullscreen first" is what users
expect, and iOS must decide. Not in the §19 behavioural checklist either.

### M4 — Task-removed (swipe from recents) contract for background playback
`PlaybackService.onTaskRemoved` (`player/PlaybackService.kt:684-690`) stops the service only
when nothing is actively playing (`!playWhenReady || mediaItemCount == 0`); with active
playback the service — and the audio — **survives the user swiping the app away**.
player.md §6.1's lifecycle contract covers fragment onStart/onStop/onDestroyView but not
this, and it is exactly the kind of background-audio behaviour iOS must consciously map.

### M5 — Shorts screens have no keepScreenOn
The main player pins the screen (`res/layout/fragment_player.xml:38`,
`android:keepScreenOn="true"`, recorded in player.md §3). Grep over
`item_shorts_page.xml`, `fragment_shorts_player.xml` and `ui/shorts/` → zero `keepScreenOn`
hits, so the display can time out mid-loop on a Short. Neither the shorts brief (§9) nor its
defect list records this asymmetry.

Checked and genuinely covered (no gap found): PiP enter/param/toggle-disable
(`PlayerFragment.kt:1993-2016` — matches player.md §7.4, and there is no
`onUserLeaveHint`/auto-PiP, as claimed); notification actions prev/play-pause/next +
compact view + dismiss (`PlaybackService.kt:461-493` matches §6.2); orientation locks
(all 7 `requestedOrientation` sites fall inside player.md §7 / shorts §9.5); analytics
(dormant readout + log-only telemetry owned by phase2-inventory §6.3/§6.5); rate-limit
machinery (extraction §6 + player.md §2.5); player-launched dialogs (quality, subtitles,
download, report — all specced; audio-language is M2); deep-link set (manifest filters and
nav `<deepLink>`s in share brief §3.1-3.2 are complete against `AndroidManifest.xml` /
`main_tabs_nav.xml`).

---

## 2. Contradictions

### C1 — Update system / remote config: the two briefs give opposite iOS guidance (major)
`remote-config-safemode.md` checklist §7 tells the iOS implementer to reproduce the update
pipeline (items 3–8: semver comparator "iOS must reproduce this comparator exactly",
Available Updates rows, prompt cadence) and says "No remote config fetch exists; **do not
invent one**" (item 1) and Q3 "Confirm iOS mirrors the absence (nothing to build)".
`phase2-inventory.md` §5 rules the **entire** update system **D3-EXCLUDED** from iOS and
says the replacement is an iOS-new P2 RemoteConfig with a `minAppVersion` "update required"
screen. The inventory is the one aligned with the baseline: spec
`docs/superpowers/specs/2026-08-23-ios-app-design.md:22` (D3) and `:193` (RemoteConfig
schema: bundled default, last-known-good, fetch on launch + willEnterForeground ≥15 min
spacing, ≤64 KiB, `minAppVersion` gate), and `2026-08-23-ios-phase1-research/RULINGS.md:10`
(ruling 6: "minAppVersion gate: phase 2"). The remote-config brief never cites D3/spec:193;
~60% of it (§3–§6) documents excluded scope as if it were the port contract, and its Q1/Q3
re-open decisions the spec already made. Planning from that brief alone builds the wrong
thing.

### C2 — Availability gate verb: GET vs HEAD
channel-detail.md §2.1: "Before any NewPipe work, **`GET`** channel-availability…".
extraction.md §5.2 and §16: `@HEAD api/v1/channels/{id}` etc. Code:
`data/source/api/ContentApi.kt:46-53` — all three are `@HEAD`. extraction.md is right.

### C3 — `views_count_billions` "unreachable"
player.md §13: "The `views_count_billions` string exists but is unreachable (the `when`
tops out at millions)." True for the player stats line (`PlayerFragment.kt:1486-1490`,
verified) but the string **is** reached from the channel Shorts grid —
`ChannelShortsAdapter.kt:65-68` (verified), which channel-detail.md §6.3 itself cites. The
player.md claim needs scoping to "unreachable from the player".

### C4 — PlayerFragment size
phase2-inventory.md §2: "`ui/player/PlayerFragment.kt` (~2000 lines)". player.md §0 calls
~3000 lines "half the fragment". Actual: **4278 lines** (`wc -l`). The inventory figure is
off by >2×; player.md's framing is roughly right.

### C5 — Which Settings update rows hide on Play-Store installs
phase2-inventory.md §5 table: "Settings rows: manual 'check for update' + 'Available
updates' (**hidden on Play-Store installs**…) | SettingsFragment.kt:375-381" — implies both
hide. remote-config-safemode.md §3.4 (correct, verified): only the Available-updates row +
divider hide (`SettingsFragment.kt:381-393`); the manual check row stays and can only toast
"up to date".

### C6 — player.md internal: sw720 action-row icon size
player.md §8.3: sw600 `icon_medium` 36dp, "sw720 fallback of that file: **36dp** — no sw720
variant". Wrong: `icon_medium` is a dimen overridden per width class — 32/36/**40** dp
(`res/values/dimens.xml:56`, `values-sw600dp/dimens.xml:42`, `values-sw720dp/dimens.xml:45`),
so a sw720 device resolves 40dp even through the sw600 layout file. player.md §17
("icon_medium 36→40 dp") and playlist-detail-shorts §4.3 ("sw720 icon_medium 40 dp") are
both correct; §8.3 contradicts them.

---

## 3. Failed citations

No spot-checked citation pointed at code that says something else. The claim-level errors
are C2 (GET vs HEAD), C4 (~2000 lines), C5 (both rows hidden), C6 (36dp on sw720) above.
Minor line drift found (content correct, off by ≤1 line — no action needed):

- share-report-links.md `ContentReportService.java:30` for `RATE_LIMIT_MAX = 5` — actually
  line 29.
- player.md `StreamPrefetchService.kt:118-133` for the 3 s await / 30 s TTL — the TTL
  constant sits at :134.
- phase2-inventory.md `SyncManager.kt:153-155` for the three entity types — the cursors map
  spans :151-155.

---

## 4. Verified-OK sample (5 per brief, opened in source)

**player.md**
1. `PlayerViewModel.kt:2218` — `stubUpNextItems(): List<UpNextItem> = emptyList()`. ✓
2. `PlaybackService.kt:986-995` — comment: position not persisted, intentional, "not
   currently implemented". ✓
3. `strings.xml:103` — `player_refresh_rate_limited` = "Please wait a moment before
   refreshing again". ✓
4. `AspectPolicy.kt` 5 % default / 20 % generous crop budget +
   `PlaybackFeatureFlags.kt:163-173` S25 Ultra (SM-S938*) build default. ✓
5. `PlayerGestureDetector.kt:43-55` — zones from actual view width thirds
   (split-screen-safe), double-tap seek. ✓
   (Also re-verified §15's grep claim: `preferences.audioOnly`/`backgroundPlay` read only by
   `SettingsFragment.kt:269-270`. ✓)

**extraction.md**
1. `NewPipeExtractorClient.kt:68-69` — `Localization.fromLocale(Locale.US)` +
   `ContentCountry("US")`. ✓
2. `YoutubeClientRotator.kt:20` — `ROTATION_ORDER = listOf(Client.IOS, Client.ANDROID)`. ✓
3. `StreamPrefetchService.kt:118-134` — 8000 ms prefetch timeout, 3000 ms await, 5 cached,
   30 s TTL with the archive-bypass-window rationale. ✓
4. `CooldownState.kt:24-34,64` — 1 h/4 h/12 h/24 h escalation, 7-day clean reset. ✓
5. `GlobalNewPipeRateLimiter.kt:135-140` — 20 tokens, 30 s refill, 0 ms background timeout,
   5 reserved. ✓

**channel-detail.md**
1. `ChannelDetailModels.kt:169-175` — exactly VIDEOS/LIVE/SHORTS/PLAYLISTS/ABOUT. ✓
2. `NewPipeChannelDetailRepository.kt:339-360` — shorts filter = NewPipe flag OR `/shorts/`
   URL, duration heuristic deliberately dropped (Mufti Menk reminders rationale in-source). ✓
3. `SubscriptionLimitGuard.kt` — `CAP = 30`, playlists uncapped per KDoc. ✓
4. `ChannelDetailViewModel.kt:1137-1143` — `MIN_APPEND_INTERVAL_MS = 1000`,
   `PAGINATION_THRESHOLD = 5`, `MAX_INITIAL_EMPTY_PAGE_FETCHES = 1`, `MAX_APPEND… = 5`. ✓
5. `fragment_channel_shorts_tab.xml:28-39` — skeleton RecyclerView carries only `tools:`
   attributes; grep confirms no adapter is ever set (only `isVisible` writes in
   `ChannelShortsTabFragment.kt`). Defect claim holds. ✓

**playlist-detail-shorts.md**
1. `ShortsPlayerFragment.kt:320-329` — `isUserInputEnabled = false` + verbatim
   anti-doom-scrolling comment. ✓
2. `NewPipePlaylistDetailRepository.kt:329` — `totalDurationSeconds = null, // Not directly
   available from PlaylistInfo`. ✓
3. `PlayerBinder.kt:154` — `repeatMode = Player.REPEAT_MODE_ONE`. ✓
4. `shorts_action_rail_bottom_margin` = 176/112/128 dp (`values/dimens.xml:40`,
   `values-sw600dp/dimens.xml:15`, `values-sw720dp/dimens.xml:12`). ✓
5. `ShortsFeedRepository.kt:47-62` — global feed via `fetchContent(VIDEOS, …,
   FilterState(videoLength = UNDER_FOUR_MIN))`, blank channelId/name; wire mapping
   `UNDER_FOUR_MIN → "SHORT"` confirmed (`RetrofitContentService.kt:50`). ✓

**share-report-links.md**
1. `ShareLinks.kt:56-73` — `publicShareUrl` builds `{base}/api/{type}/{id}`, blank base →
   custom-scheme fallback. ✓
2. `ContentReportService.java:29` — `RATE_LIMIT_MAX = 5` (per-device Caffeine bucket). ✓
3. `NetworkModule.kt:115-122` — `UUID.randomUUID()` persisted in `device_prefs`/`device_id`. ✓
4. `main_tabs_nav.xml:303` — `albunyaantube://shorts/{initialShortId}` deep link; grep of
   `AndroidManifest.xml` for "shorts" → zero hits. Defect claim holds. ✓
5. `PlayerDescriptions.kt:26-62` — URLSpans filtered to an allowed-scheme list, non-http(s)
   spans removed but text kept visible, with the smuggling-defence comment. ✓

**remote-config-safemode.md**
1. `SettingsPreferences.kt:172` — `DEFAULT_SAFE_MODE = true`. ✓
2. `ReleaseSummaryFetcher.kt:114-124` — `META_URL` pinned to `/develop/` with the
   flip-to-main TODO and the guard-test note. ✓
3. `UpdateChecker.kt:143,187` — GitHub **list** endpoint `…/repos/$GITHUB_REPO/releases?per_page=`,
   `GITHUB_REPO = "talibfitrah/albunyaantube"`. ✓
4. `SplashFragment.kt:81-97` — `SPLASH_PRE_AWAIT_MS = 600 + 400×3 + 150 + 800 = 2750`. ✓
5. Safe-mode zero-reader claim — repo-wide grep: only `SettingsPreferences.kt` and
   `SettingsFragment.kt` reference it. ✓

**phase2-inventory.md**
1. `SyncManager.kt:151-155` — cursors for exactly `subscriptions`/`playlists`/`favorites`. ✓
2. `FollowedChannel` dead-store claim — grep: only entity/DAO/repository +
   `DatabaseModule.kt`/`AppDatabase.kt`; zero UI or sync consumers. ✓
3. `res/layout/fragment_player.xml:677-686` — `analyticsHeader`/`analyticsStatus` both 0dp
   `visibility="gone"`. ✓
4. `IndexRepository.kt:17-45` — fire-and-forget `IndexStreamsRequest("CHANNEL"|"PLAYLIST", …)`
   with log-and-drop failures. ✓
5. Dead-menu claim — grep for `R.menu.detail_share_menu|R.menu.menu_report|R.menu.filter_menu`
   over `java/` → zero call sites. ✓
