# Phase 2 research — Player

Scope: `PlayerFragment`, `PlayerViewModel`, ExoPlayer setup, quality selection, audio-only,
background playback (`PlaybackService` / MediaSession / notification), fullscreen + landscape
(incl. the 1.0.0-beta.44 landscape-fullscreen work), gestures/overlay, subtitles, speed,
watch progress, queue/autoplay, related videos, metadata panel, SafeMode, error UI, layouts.
Every claim cites `android/app/src/main/**` file:line. Behavioural contract for the iOS port —
no Swift here. Downloads and Cast behaviour are Phase 3; only their *player-surface presence*
is recorded. Shorts player (`ShortsPlayerFragment`) is out of scope here.

---

## 0. TL;DR for the implementer

1. **There is no watch-progress persistence anywhere.** Position survives only in-memory for
   quality switches / error recovery (`PlayerFragment.kt:127-129,2912-2917`); across process
   death it is deliberately lost (`PlaybackService.kt:986-995`). §11.
2. **No related videos.** Up Next is populated only in playlist mode; a single video plays with
   an empty queue (`stubUpNextItems() = emptyList()`, `PlayerViewModel.kt:2218`). §12.
3. **SafeMode, the Settings "audio only" and "background play" toggles are all dead** — written
   by `SettingsFragment`, read by nothing in the playback stack (§15). Background playback is
   unconditionally on; audio-only is a per-session player toggle.
4. **Playback speed is only what Media3's stock controller settings menu offers** — zero custom
   code, nothing persisted (no `setPlaybackSpeed`/`PlaybackParameters` call sites in `java/`). §10.
5. Half the fragment (~3 000 lines) is Android/NewPipe-specific stream-resolution *recovery*
   (403 refresh, stall watchdogs, synthetic-DASH TTL, codec step-down). Port the **state machine
   and UI contract** (§2, §16), not the mechanism — iOS resolves streams its own way
   (see memory: iOS client findings).

---

## 1. Entry points and navigation arguments

Nav destination `playerFragment` with declared args `videoId` (default `""`) and `playlistId`
(nullable) plus deep links `albunyaantube://video/{videoId}`,
`https://app.fitrahtube.com/watch/{videoId}`, `.../api/watch/{videoId}`
(`res/navigation/main_tabs_nav.xml:245-263`). All other args arrive as raw Bundle extras.

`onViewCreated` reads (`PlayerFragment.kt:402-417`):

| Arg | Type | Use |
|---|---|---|
| `playlistId` | String? | non-empty → playlist mode `loadPlaylist(playlistId, targetVideoId, startIndex, shuffled)` (`:420-424`) |
| `videoId` | String? | else single-video `loadVideo(...)` fast path (`:425-437`) |
| `startIndex` | Int (0) | index hint |
| `shuffled` | Bool (false) | shuffle queue |
| `targetVideoId` | String? | authoritative start video (PR6.6) |
| `title` | String, default `player_default_title` = **"Video"** (`strings.xml:77`) | fast-path metadata |
| `channelName` | String "" | author line (Phase 1 Q4: callers pass `category` here from Videos/Featured) |
| `thumbnailUrl`, `description` | String? | metadata |
| `durationSeconds` | Int 0 | metadata |
| `viewCount` | Long, `-1` sentinel → nil (`:416`) | stats line |
| `channelId` | String? | `sourceChannelId` for availability check + report parent (`:417`, `ChannelVideosTabFragment.kt:60-72`) |
| `contentSubType` | String? (`"SHORT"`/`"LIVESTREAM"`) | report sheet subtype only (`:1839-1841`, `ChannelShortsTabFragment.kt:101`, `ChannelLiveTabFragment.kt:69`) |

Callers: Videos tab / Featured / channel tabs use `R.id.action_global_playerFragment`
(`VideosFragmentNew.kt:191`, `FeaturedListFragment.kt:70`, `ChannelVideosTabFragment.kt:61`,
playlist "play/shuffle" adds `targetVideoId`/`startIndex`/`shuffled`
(`PlaylistDetailFragment.kt:748-753`)); MeFragment navigates by destination id
(`MeFragment.kt:636,656`). Notification tap → `MainActivity` `ACTION_OPEN_PLAYER`: if player
already showing, just foreground; else navigate with `PlaybackService.activeVideoId`; null
(process death) → foreground only, no navigation (`MainActivity.kt:182,203-208`,
`PlaybackService.kt:997-1000`).

No arguments at all → the VM's `hydrateQueue()` runs with a **stub queue that is empty**
(`PlayerViewModel.kt:284-288,1112-1141`, `stubUpNextItems() = emptyList()` `:2218`).

---

## 2. `PlayerViewModel` — state shape

File: `ui/player/PlayerViewModel.kt`.

### 2.1 Published state

```
PlayerState(audioOnly=false, hasVideoTrack=true, currentItem: UpNextItem?,
            upNext: [UpNextItem], excludedItems: [UpNextItem], currentDownload: DownloadEntry?,
            streamState: StreamState = .Idle, selectedSubtitle: SubtitleTrack?,
            lastAnalyticsEvent, hasNext=false, hasPrevious=false, retryCount=0,
            isFavorite=false)                                    // :2058-2074

StreamState = Idle | Loading
            | Ready(streamId, selection: PlaybackSelection)
            | Error(@StringRes messageRes)
            | ContentUnavailable                                  // archived; NO extraction allowed
            | RecoveryExhausted(streamId, selection)              // manual-retry escape hatch
                                                                  // :2090-2108
UpNextItem(id, title, channelName, durationSeconds, isExcluded=false, exclusionReason?,
           streamId, thumbnailUrl?, description?, viewCount?, sourceChannelId?)  // :2076-2088
```

Plus one-shot `uiEvents: SharedFlow<PlayerUiEvent>` (`FavoriteToggleFailed(messageRes)`,
`LiveStreamRefreshReady`, `AudioTrackSwapReady`, `DubAudioResolveFailed`) with
`extraBufferCapacity=1, DROP_OLDEST` (`:174-178,2224-2263`), and
`analyticsEvents: SharedFlow<PlaybackAnalyticsEvent>` (capacity 8) (`:268-273`).

### 2.2 Resolve pipeline (`resolveStreamFor` → `resolveWithRetry`, `:632-1569`)

1. Emit `Loading`, cancel prior resolve job (`:637-645`).
2. Unless force-refresh: consume **tap-prefetch** (`StreamPrefetchService.awaitOrConsumePrefetch`,
   awaits in-flight up to **3 s**, result TTL **30 s** — `StreamPrefetchService.kt:118-133`), then
   the VM's own **queue-prefetch cache** (TTL **30 s**, `PREFETCH_CACHE_TTL_MS` `:1799`).
3. Fresh resolve: up to **3 attempts**, per-attempt timeout **20 s** (`EXTRACTOR_TIMEOUT_MS`),
   backoff **1 s / 2 s / 4 s** (`:1465-1519`, constants `:1776-1778`).
4. `ContentUnavailableException` (backend availability gate, thrown from
   `GlobalStreamResolver`; 410 = admin block, 404 fail-open — `PlayerRepository.kt:6-27`,
   `PlayerViewModel.kt:1414-1431`) → **no retry**; playlist mode auto-skips (§12.3), else
   `StreamState.ContentUnavailable`.
5. Success → `Ready(streamId, selection)`; selection honours the session-scoped
   `stickyAudioLanguage` (`toSelectionWithPreferredAudio`, `:2208-2216`); fire-and-forget dub
   enumeration re-emits Ready with extra audio languages (`maybeEnumerateDubs`, `:99-165`).
6. Live streams: schedule proactive URL refresh before expiry; emits
   `LiveStreamRefreshReady` for a seamless source swap (`:1575-1651`).

Default quality selection (`toDefaultSelection`, `:2167-2206`): adaptive manifest present →
prefer **720p muxed**, then 480 muxed, 720 any, 480 any, else max; progressive-only → prefer
**480 muxed**, 360 muxed, 720 muxed, lowest muxed ≥240, 480, 360, else max. Audio = highest
bitrate.

### 2.3 Playlist queue

- `loadPlaylist` → deep-start scan for `targetVideoId`: first page always fetched (timeout
  **10 s**/page), then pages until found, bounded by **250 items** / **3 s** total
  (`:904-1031`, constants `:1782-1785`). Not found → fall back to `startIndexHint`.
- Shuffle: randomize but pin the tapped video first; **paging disabled when shuffled**
  (`:1044-1093`).
- Lazy paging: background fetch when queue ≤ **5** (`QUEUE_PREFETCH_THRESHOLD`), cooldown
  **2 s**, single-flight mutex; a failed page sets `pagingFailed` and stops paging
  (`:1911-2008`). `hasNext = queue.isNotEmpty() || hasMorePages` (`:1208-1222`).
- History (`previousItems`) capped at **100** (`:182,2011-2016`).
- **Channel-name gating**: in playlist mode the channel name is blanked everywhere
  (current item + up-next) unless the playlist's parent channel is APPROVED in the registry;
  approval resolves async (timeout 10 s), fail-closed (`isPlaylistChannelApproved` `:889-902`,
  `gateChannelName` `:1201-1206`).

### 2.4 Prefetch of next queue items

On first `isPlaying=true`, `prefetchNextItems()` resolves the next **2** queue items on IO at
`Priority.BACKGROUND_REFRESH`, rate-limiter kind `PREFETCH` (skipped, never waited on), cache
cap 4 entries, TTL 30 s (`PlayerFragment.kt:1167-1168`, `PlayerViewModel.kt:1703-1770`).

### 2.5 Rate limiting (all forced refreshes)

`ExtractionRateLimiter`: min interval **30 s**/video/kind, **3** attempts per video per **5 min**,
global **10 per min**; `AUTO_RECOVERY` and `PROACTIVE_TTL_REFRESH` each have **2 reserved**
attempts outside the global cap; MANUAL backoff base **2 s**, max **60 s**
(`ExtractionRateLimiter.kt:50-89`). Blocked manual refresh → toast
`player_refresh_rate_limited` = **"Please wait a moment before refreshing again"**
(`strings.xml:103`, `PlayerFragment.kt:603-605`).

---

## 3. ExoPlayer setup (Android-specific; port the *behavioural* bits)

`setupPlayer` (`PlayerFragment.kt:945-1093`):

- `AdaptiveBufferPolicy` load control by device memory class (<128 MB low / ≥256 high):
  steady buffer 30/50/50 s, start-playback buffer 2/1.5/1.5 s, after-rebuffer 3.5/3/4 s,
  back-buffer 30/45/45 s (`AdaptiveBufferPolicy.kt:51-84`).
- `QualityTrackSelector` (CAP / LOCK-as-CAP_STRICT modes, §4.3).
- **Cellular network ceiling applied up-front and re-checked every prepare**: LTE/5G →
  ≤720p / 2.5 Mbps; 3G/metered → ≤480p / 1.2 Mbps; WiFi/offline → none
  (`ColdStartQualityChooser.kt:73-74,383-390`, `PlayerFragment.kt:959-962,2815-2817,4040-4047`).
- `setHandleAudioBecomingNoisy(true)`, `WAKE_MODE_NETWORK`, seek increments **10 000 ms**
  back/forward (`:974-977`).
- `playWhenReady = false` initially; set true on prepare for new videos (§3.1).
- Subtitle view style + cue normalization (§9).
- **A/V-sync mute**: on a fresh (non-switch) prepare, volume 0 until `onRenderedFirstFrame`,
  safety unmute after **3 s** (`:3124-3157,1259-1273`). Video-render watchdog: audio playing
  but no frame after **5 s** → re-resolve (`:3146-3157`).
- `keepScreenOn` on the PlayerView (`fragment_player.xml:38`).

### 3.1 Prepare semantics (`maybePrepareStream`, `:2786-3203`)

- Source identity key = `(streamId, audioOnly)` + actual source URL; a repeat `Ready` emission
  with the same identity is a **cache hit — no re-prepare** (`checkCacheHit` `:4103-4226`,
  `CacheHitDecider` for synthetic DASH).
- Quality-cap-only changes on adaptive streams update the track selector **without rebuilding**
  the source (`:2850-2861`).
- Position preserved on quality switch / audio-mode flip / pending recovery resume; **new video
  always autoplays** (`shouldPlay` `:2913-2926,3160`).
- New stream resets resize mode to FIT, clears cached video dims and per-stream flags
  (`:2872-2903`).
- MediaSession metadata force-synced after every prepare (`:3182-3187`, §6.3).

### 3.2 Recovery machinery (record; do not port mechanism 1:1)

- Single re-resolve path `requestStreamRefreshAndResume(reason)`: saves position +
  playWhenReady, invalidates cached MPD, stops player, VM re-resolves, prepare path resumes at
  position (`:2159-2228`).
- Error-code handling table in §16.2.
- Buffering-stall watchdog: armed only after first READY of the stream; VOD **6 s**,
  live **45 s**; only fires if the buffered position has NOT advanced (slow-but-working
  networks re-arm instead); suppressed **20 s** after a web-dub swap (`:2237-2285`,
  constants `:4264-4270`).
- Seek-transient gate: an error within **12 s** of a user seek on a multi-quality adaptive
  stream gets up to **3** budget-free refreshes instead of degrading quality
  (`SeekTransientErrorGate.kt:96-98`).
- Synthetic-DASH MPD TTL **15 min**; proactive refresh at **90 %** TTL
  (`SyntheticDashMpdRegistry.kt:43`, `MpdTtlWatcher.kt:18,30`).
- Decoder failure → same-resolution codec swap (H.264 preferred) else next lower resolution;
  clamps the quality cap; toast `player_decoder_stepping_down` = **"Video codec not supported.
  Switching to %1$s…"** (`:2554-2784`, `strings.xml:58`).

---

## 4. Quality selection UI + persistence

### 4.1 Picker

`qualityButton` (overlay top bar) → `showQualitySelector()` (`PlayerFragment.kt:583-585,
1886-1935`): `MaterialAlertDialogBuilder` single-choice, title `player_quality_dialog_title` =
**"Video Quality"** (`strings.xml:65`), sorted **highest → lowest**, label
`"{qualityLabel} ({width}x{height})"` when dims known, current selection pre-checked, Cancel
button. Selecting → `viewModel.selectQuality(track)` + toast `player_quality_switching` =
**"Switching to %1$s…"** (`strings.xml:67`).

- List sourced from **ready-OR-recovering** state so the user can drop quality during a stall
  (`PlayerViewModel.kt:451-456,611-617`).
- Empty list → toast `player_quality_unavailable` = "No quality options available" if Ready,
  else `player_video_not_ready` = "Video not ready yet" (`:1895-1903`, `strings.xml:68,73`).
- When the active source is **not adaptive**, the menu collapses to exactly the one track that
  would actually be served (highest muxed, else highest video-only) — offering the ladder would
  be a lie (`buildQualityOptions`, `PlayerViewModel.kt:1819-1848`). Dedup by height, muxed
  preferred, then bitrate.

### 4.2 Semantics of a pick

User pick = **cap, not exact lock**: `setUserQualityCap` debounced **300 ms** (rapid taps
coalesce; discarded if the video changed during debounce) (`PlayerViewModel.kt:477-511`,
`QUALITY_SWITCH_DEBOUNCE_MS` `:1779`). Selection origin drives constraint mode: MANUAL →
"LOCK" (implemented as CAP_STRICT: max height, force highest bitrate, **no minimum** so it can
never fall to audio-only), AUTO/AUTO_RECOVERY → CAP (ABR may go lower)
(`PlayerFragment.kt:4058-4068`, `QualityTrackSelector.kt:74-115`). Network ceiling caps CAP
mode but a MANUAL pick may exceed it (`QualityTrackSelector.kt:44`).

### 4.3 Persistence

**The user's quality pick is not persisted** — not across videos, not across sessions (it lives
in the per-stream `PlaybackSelection`). The only persisted quality signal is
`ColdStartQualityChooser`'s `last_successful_height` in SharedPreferences `cold_start_quality`
(versioned, cleared on app update) used to seed cold-start AUTO quality
(`ColdStartQualityChooser.kt:54-56,419`).

---

## 5. Audio-only mode

- UI: `audioButton` ("Audio", headphones icon) in the action row toggles a **hidden** `Switch`
  `audioOnlyToggle` (0dp, `fragment_player.xml:646-650`), whose change listener calls
  `viewModel.setAudioOnly(isChecked)` (`PlayerFragment.kt:460-462,501-503`).
- `setAudioOnly` no-ops when unchanged, publishes `AudioOnlyToggled` (`PlayerViewModel.kt:349-353`).
- Effect: prepare key `(streamId, audioOnly)` changes → MediaSource rebuilt audio-only (highest
  bitrate audio track, `buildMediaSourceResultViaDash` `PlayerFragment.kt:922-928`), position
  preserved (audio-mode change counts as a quality switch, `:2841-2842`).
- Status text (hidden view): `player_status_audio_only` = "Playing audio-only stream."
  (`:2086-2088`, `strings.xml:48`).
- Toggle disabled while in PiP (`:1993-1996`).
- **Not persisted**; resets with the ViewModel. The Settings screen's separate audio-only
  preference is never read by the player (§15).

---

## 6. Background playback — `PlaybackService`, MediaSession, notification

`player/PlaybackService.kt` (Media3 `MediaSessionService`). The **fragment owns the player**;
the service owns only the session.

### 6.1 Lifecycle contract

- Fragment `onStart`: bind (`ACTION_LOCAL_BIND`, once per view lifecycle) + start foreground
  service; `setPlayerUiVisible(true)`; restore `userWantsToPlay` (playWhenReady preserved
  across stop/start — user pause survives backgrounding) (`PlayerFragment.kt:659-677,281-282,
  1251-1257`).
- Fragment `onStop`: `setPlayerUiVisible(false)`; **playback deliberately not paused** —
  "allow background audio" (`:679-689`).
- Fragment `onDestroyView`: `releaseSession()` before releasing the player; player released
  **async** on its own looper to dodge `ExoTimeoutException` (`:834-884,3235-3256`).
- Service startForeground called in `onCreate` with a placeholder notification
  (`player_notification_loading` = "Loading…") to satisfy the 5-second FGS rule
  (`PlaybackService.kt:161-193,301-309`).

### 6.2 Notification policy

Hide notification **iff app foreground AND player UI visible**; background + playing → FGS with
MediaStyle notification; background + paused → demote from FGS but keep a dismissible
notification (swipe-dismiss stops the service, token-validated) (`updateForegroundState`
`:836-867`, `ACTION_DISMISS` `:271-284,625-634`). Notification: channel `playback`
IMPORTANCE_LOW, no badge/vibration/sound, public lockscreen (`:876-906`); actions
prev / play-pause / next in compact view (`:461-493`); prev falls back to `seekTo(0)` when no
previous item (`:253-262`). Tap → `ACTION_OPEN_PLAYER` (§1).

### 6.3 Metadata

`MediaSessionMetadataManager` sets title/artist(=channelName)/artworkUri, loads artwork via
Coil off-thread, caches last PNG bytes, hands bitmap to the service for `setLargeIcon`
(`MediaSessionMetadataManager.kt:96-120`, `PlaybackService.kt:409-417`). Synced per item and
force-resynced after every source swap (`PlayerFragment.kt:4239-4258`).

### 6.4 External controllers

Own app + legacy controller (system/Bluetooth/lockscreen) get FULL commands; **everything else
(Android Auto, Assistant, third-party) is RESTRICTED** to play/pause/seek/stop/metadata —
no queue skip, no speed (`PlaybackService.kt:921-952,1041-1082`).

---

## 7. Fullscreen + landscape (beta.44 work)

State: `isFullscreen`, `userDismissedFullscreen`, `pendingFullscreenExit`,
`weLockedOrientation`/`targetOrientationIsLandscape`, `fullscreenResizeMode`,
`userToggledResizeMode`, cached rotation-corrected video dims (`PlayerFragment.kt:131-171`).

### 7.1 Entering / leaving

- **Auto-enter on rotation to landscape** (`onConfigurationChanged`, `:691-777`) unless: the
  video is portrait (9:16 — rotation-tag-aware detection `:1290-1303`), or the user explicitly
  exited via button while landscape (`userDismissedFullscreen`, consumed after exactly one
  suppressed auto-enter `:719-731`).
- **Opening the player while already landscape** enters fullscreen immediately — phones only,
  not tablets ("tablets rest in landscape"), not in multi-window/PiP, not for known portrait
  sources (`:639-657`).
- Fullscreen **button** (`:579-581`, `toggleFullscreen` `:3382-3471`): entering locks
  orientation `SENSOR_LANDSCAPE` (or `SENSOR_PORTRAIT` for portrait videos — portrait videos
  fullscreen **in portrait**) and the lock **stays until exit**; exiting forces PORTRAIT then
  unlocks to UNSPECIFIED after **500 ms** (reached target) / **3 000 ms** fallback
  (`ORIENTATION_UNLOCK_*` `:4272-4276`). Exit while still landscape is **deferred** until the
  portrait config change arrives (safety timeout 3 500 ms) so restoration never runs against
  landscape measurements (`:3448-3470,769-776`).
  `ponytail:` note in source: one swallowed rotate-to-landscape after button-exit is a known
  accepted cost (`:726-729`).
- Icon swaps `ic_fullscreen` ↔ `ic_fullscreen_exit` (`:3705,3812`).

### 7.2 Fullscreen UI mutation (`updateFullscreenUi`, `:3559-3824`)

Enter: hide bottom nav via `MainActivity.setBottomNavVisibility(false)`; shell root
`fitsSystemWindows=false`, padding 0, background BLACK (original saved once, restored on exit);
cutout `SHORT_EDGES`; hide system bars (API 30+ insets controller with **150 ms** OEM retry;
legacy immersive-sticky flags with **200 ms** retry + visibility listener re-hide after 75 ms);
hide `playerScrollView`; AppBar/CollapsingToolbar/playerContainer → MATCH_PARENT with
`scrollFlags = 0` (swipe cannot collapse the video); PlayerView 0dp constrained to all edges,
`dimensionRatio = null`.

Exit: restore bars, cutout DEFAULT, bottom nav visible, shell root restored,
scroll flags `scroll|exitUntilCollapsed|snap` (the YouTube-style collapse-on-scroll
mini-player behaviour in portrait), PlayerView re-constrained to `16:9`, resize FIT,
`requestApplyInsets()`.

Leaving the screen while fullscreen: `onDestroyView` restores system UI + nav + shell
unconditionally so fullscreen never leaks (`:822-829,3832-3885`).

### 7.3 Aspect policy (fill vs fit)

`AspectPolicy.computeResizeMode(viewportW, viewportH, videoW, videoH, PAR, cropBudget)` →
ZOOM iff crop ≤ budget else FIT; budget **5 %** default, **20 %** "generous" — build-default
true only on Samsung S25 Ultra (SM-S938*) (`AspectPolicy.kt:24-32,47-68`,
`PlaybackFeatureFlags.kt:163-173`). Recomputed on `onVideoSizeChanged` and on fullscreen entry
using orientation-correct screen dims (`PlayerFragment.kt:1315-1335,3686-3702`). Manual
override: **centre double-tap in fullscreen** toggles ZOOM/FIT, toast `player_resize_mode_zoom`
= "Fill screen" / `player_resize_mode_fit` = "Fit to screen", sticky per stream
(`:3525-3547`, `strings.xml:293-294`). One-time snackbar hint on first fullscreen:
`player_fullscreen_zoom_hint` = **"Double-tap to toggle fit/zoom"**, flag
`fullscreen_zoom_hint_shown` in SharedPreferences `player_prefs` (`:3473-3484`,
`strings.xml:295`).

### 7.4 PiP

Menu item `action_enter_pip` only (`player_menu.xml:8-12`); `enterPictureInPictureMode` with
the video's aspect ratio (16:9 fallback) (`:1998-2016`); audio-only toggle disabled while in
PiP (`:1993-1996`). No auto-PiP on home press.

---

## 8. Gestures and controls overlay

### 8.1 Gestures (`PlayerGestureDetector.kt`)

Zones by **actual view width** thirds (split-screen-safe, `:43-45,52-55`):
double-tap left → seek **−10 s** (floor 0); right → **+10 s** (capped at duration; no-op when
duration unknown ≤0); centre → resize toggle, only consumed in fullscreen (returns false
otherwise — no dead zone) (`:49-82`). Single taps fall through to the Media3 controller
(`onTouchListener` returns false, `PlayerFragment.kt:1089-1092`). Brightness/volume gestures
deliberately removed (`PlayerGestureDetector.kt:16-17`).

### 8.2 Media3 controller

`controllerShowTimeoutMs = 5000`, auto-show, hide-on-touch, fast-forward/prev/next buttons
shown (`PlayerFragment.kt:1044-1049`; XML mirrors: `show_timeout=5000`, `show_buffering=always`,
`shutter black`, `use_artwork=false`, `show_subtitle_button=true`, `show_shuffle_button=false`
— `fragment_player.xml:44-53`). Media3's internal prev/next buttons are kept **always visible**;
disabled state = alpha **0.3** + no-op (Next when exhausted toasts `player_up_next_empty` =
"Queue is empty.") (`:449-458,3998-4032`, `strings.xml:84`). Skip actions route through the VM
queue, not ExoPlayer's playlist (`:4009,4023`).

Custom overlay `playerOverlayControls` mirrors controller visibility (`:1052-1071`); visible
initially, auto-hidden **3 s** after playback first starts (`:1162-1184`). Top bar (48dp
touch targets, white tint): back/minimize (`navigateUp`), spacer, audio-language globe
(visible iff ≥2 audio languages, `:1520-1525`), CC button (visible iff subtitles exist,
`:1526-1527`), quality, fullscreen, Cast `MediaRouteButton`
(`fragment_player.xml:207-292`). Toolbar menu: Captions / PiP / Report
(`player_menu.xml`, handler `:1792-1814`).

### 8.3 Below-player action row

5 equal-weight buttons, icon `icon_small` **24dp** (sw600: `icon_medium`, which is a *dimen*
overridden per width class — 32/**36**/**40** dp — `values/dimens.xml:56`,
`values-sw600dp/dimens.xml:42`, `values-sw720dp/dimens.xml:45`; sw720 resolves **40dp** even
though it reuses the sw600 layout file, since there is no sw720 layout variant), green
`primary_green` tint + 12sp caption:
Share, Favorite (icon/label flip on state + toast added/removed), Download, Audio, Report
(`fragment_player.xml:418-600`, handlers `:477-514`). Share builds title (truncated 160 chars)
+ watch-page link + promo lines (`:3314-3368`).

---

## 9. Subtitles

- Availability: `resolved.subtitleTracks`; CC button + menu item open `SubtitlePickerDialog` —
  single-choice, **"Off" always first**, labels `languageName` with
  `player_captions_auto_generated` = "%1$s (Auto-generated)" suffix, title
  `player_captions_dialog_title` = "Select Captions", result via Fragment Result API
  (`SubtitlePickerDialog.kt:22-64`, `strings.xml:70-72`).
- Apply: off → disable `TRACK_TYPE_TEXT`; else enable + `setPreferredTextLanguage(code)`;
  VM records `selectedSubtitle` (`PlayerFragment.kt:537-558`). Current selection derived from
  `trackSelectionParameters` (`:1985-1988`). **Not persisted** across videos or sessions.
- Rendering: embedded styles off, fixed **18 sp** text; every cue re-pinned bottom-anchored at
  line fraction **0.85**, centre-aligned, full width (YouTube auto-gen cues arrive top-anchored
  with geometry that makes SubtitlePainter skip them) (`SubtitleStyle.kt:32-46,53-80`,
  registration-order-dependent listener `PlayerFragment.kt:986-1008`).
  `LegacySubtitleRenderersFactory` re-enables legacy TTML/VTT decoding (`:964-969`).

---

## 10. Playback speed

No app code: zero `setPlaybackSpeed` / `PlaybackParameters` call sites under `java/`. Speed is
reachable only through Media3's stock controller settings menu (the one
`patchExoSettingsRecyclerView` crash-guards, `PlayerFragment.kt:293-326`). Nothing persisted;
external controllers are explicitly denied SET_SPEED (`PlaybackService.kt:1066-1082`).

---

## 11. Watch-progress persistence

**None.** Position is kept in-memory only for quality switches, audio-mode flips and error
recovery within one screen instance (`pendingResumePositionMs` `PlayerFragment.kt:127-129`,
`savedPosition` `:2912-2917`). Leaving the player, process death, and reopening a video all
start at 0. Deliberate per `PlaybackService.kt:986-995` ("Fragment's ViewModel doesn't persist
playback position … not currently implemented"). No history/continue-watching store exists
(no WatchProgress/WatchHistory types in `java/`).

---

## 12. Queue / Up Next / autoplay

### 12.1 Population

Playlist mode only (§2.3). Single-video mode: queue empty → `upNextList` hidden, hidden
`upNextEmpty` "shown" (it is a 0dp invisible view — effectively the section shows only the
"Up next" header) (`PlayerFragment.kt:1534-1536`, `fragment_player.xml:608-625,670-674`).
**There is no related-videos source anywhere in the player.**

### 12.2 Autoplay

`STATE_ENDED` → `markCurrentComplete()` → advance queue with reason AUTO; if advanced,
`playWhenReady = true` (`PlayerFragment.kt:1242-1248`, `PlayerViewModel.kt:389,1866-1924`).
Queue empty + more pages → async page fetch then continue; queue empty + no pages →
`StreamState.Idle` (playback simply stops). Prev restores from history (`skipToPrevious`
`:393-411`).

### 12.3 Auto-skip

Unplayable item in playlist mode → skip forward, max **3 consecutive**, analytics event
`VideoSkipped` ("Skipped unavailable video (n/3)" is rendered only into the hidden
`analyticsStatus` view); past the cap → the real error state shows (`:1349-1366`, render
`PlayerFragment.kt:2047`).

### 12.4 Up-next list UI

Phone: vertical `LinearLayoutManager` + dividers; tablet (`isTablet()`): **2-column grid**, no
dividers (`PlayerFragment.kt:890-906`). Cell `item_up_next.xml`: root padding `spacing_md`,
thumbnail `video_list_thumbnail_width` **140dp** 16:9 rounded (SmallComponent), duration chip
bottom-end (`home_duration_chip_*` paddings 6/3, 11sp bold), title 14sp bold max 2 lines,
meta 12sp `"{channel} • {views}"` (` • ` join; parts dropped when blank) (`item_up_next.xml:8-82`,
`UpNextAdapter.kt:40-58`). Duration `m:ss` via `player_duration_minutes_seconds` = "%1$d:%2$02d"
— **hours are never rendered** (90-min video → "90:00") (`UpNextAdapter.kt:60-69`,
`strings.xml:107`); view count `String.format("%.1fB/M/K")` **not locale-aware and not the
list-screens' ICU compact format** (`UpNextAdapter.kt:71-79`). Tap → `viewModel.playItem`
(id-matched against the queue; ungated original replayed) (`PlayerFragment.kt:113`,
`PlayerViewModel.kt:355-387`).

---

## 13. Metadata panel (below player, phone `NestedScrollView`)

Order (`fragment_player.xml:298-625`): title (16→18sp bold via `text_section_title` 18sp,
padding `spacing_md`), author (`primary_green`, 14sp), stats (12sp secondary), description
card, action row, 1dp divider, "Up next" header (16sp bold), list. sw600dp adds
`paddingStart/End spacing_lg` on the scroll view and constrains the column to
`content_max_width` **1200dp** (sw720 file absent → 1200dp there too; the *dimen* sw720
override 1600dp is unused by this layout since the sw600 layout resolves sw720's dimens —
`content_max_width` = 1600dp on sw720 — `values-sw720dp/dimens.xml:32`).

- Title/author/stats/description bound from `currentItem` (`PlayerFragment.kt:1480-1505`);
  no item → `player_no_current_item` = "No video is currently playing."
- **Stats line = view count only.** `views_count_millions` "%.1fM views" /
  `_thousands` "%.1fK views" / `views_count` "%d views"; nil → `player_no_views` =
  "No views yet" (`:1484-1493`, `strings.xml:78,428-431`). The `views_count_billions` string
  is **unreachable from the player** (this `when` tops out at millions) — but it **is** reached
  elsewhere: the channel Shorts grid formats view counts up to billions
  (`ChannelShortsAdapter.kt:65-68`, cited by channel-detail.md §6.3). **No upload date** despite the
  layout's `tools:text="0 views • Jan 1, 2025"` (`fragment_player.xml:348`).
- **No like count / like button** — only orphan strings `player_action_like`,
  `player_like_coming_soon` (`strings.xml:274,114`); nothing renders them.
- Description: collapsed by default; header tap toggles visibility and rotates the chevron
  0↔180° (`:468-472`); content is NewPipe HTML rendered via `PlayerDescriptions.render` —
  `HtmlCompat` COMPACT, trimmed, `URLSpan`s filtered to **http/https only** (deep-link/intent
  scheme smuggling defence), `LinkMovementMethod` for tappable links; empty →
  `player_no_description` = "No description available" (`PlayerDescriptions.kt:35-62`,
  `PlayerFragment.kt:473-474,1494-1496`).
- Metadata hydration: if any of title("Video"/blank)/thumb/description/viewCount/duration/
  channelName is missing, fetch `extractorClient.fetchVideoMetadata` (20 s cap) and fill only
  the blanks (`PlayerViewModel.kt:1143-1193`).

---

## 14. Favorites inside the player

Reactive `isFavorite` via `FavoritesRepository.isFavorite(videoId)` flatMapLatest
(`PlayerViewModel.kt:296-312`); toggle stores id/title/channelName/thumb/duration
(`:318-347`); UI: icon `ic_favorite`/`ic_favorite_border`, label `player_action_favorited` =
"Favorited" / `player_action_favorite` = "Favorite", toast `player_added_to_favorites` /
`player_removed_from_favorites` (`PlayerFragment.kt:485-495,1507-1513`, `strings.xml:275-278`).
Failure → `FavoriteToggleFailed` event → toast `player_favorite_toggle_error` (`strings.xml:279`).

---

## 15. SafeMode (and other Settings toggles) inside the player

`SettingsPreferences` exposes `safeMode` (`SettingsPreferences.kt:256-258`), `backgroundPlay`
(`:223-225`) and a settings-level `audioOnly` (`:212-214`). **Grep over `java/` finds no reader
of any of them outside `SettingsFragment` itself** (`SettingsFragment.kt:254-272,402-414`).
Facts for iOS:

- **SafeMode has zero effect inside the player** (and the playback stack generally). Content
  curation happens server-side; the toggle is UI-only today. (defect: dead setting)
- **Background playback is unconditionally enabled** regardless of the toggle (§6). (defect)
- The Settings audio-only preference does not seed the player's audio-only toggle. (defect)

---

## 16. Error UI — unavailable / restricted streams

### 16.1 `streamState` → UI (`updatePlayerStatus`, `PlayerFragment.kt:2051-2103`)

| State | errorOverlay | recoveryOverlay | Buttons |
|---|---|---|---|
| Idle / Loading | gone | gone | — |
| `Error(res)` | **visible**: title `player_error_title` = "Unable to play video", message = the state's string | gone | **Retry** (`retryCurrentStream`, cache-allowed) + **Refresh Stream** (force, MPD invalidated, rate-limit toast on block) (`:590-606`) |
| `ContentUnavailable` | **visible**: `content_unavailable_title` = "Content not available", `content_unavailable_message` = "This content is no longer available. It may have been removed by an admin or by YouTube." | gone | **both buttons hidden** — no retry offered (`:2074-2084`) |
| `Ready` | gone | gone | — |
| `RecoveryExhausted` | gone | **visible**: message `player_recovery_exhausted_message` = "Unable to recover playback automatically. Please try again.", spinner hidden | **Retry** (clears state, invalidates MPD, force-refresh) (`:608-624,2093-2101`) |

Both overlays are 16:9 boxes layered over the player area: error bg `player_error_overlay`,
white 20sp bold title (`text_headline`), 14sp message, filled Retry + outlined Refresh
(`fragment_player.xml:57-197`).

- `Error` also stops the player and clears prepared state (`:2789-2798`).
- Ready self-heals RecoveryExhausted (VM transition only from Ready — `PlayerViewModel.kt:2039-2046`).

### 16.2 Player-error handling (`onPlayerError`, `:1338-1468`; toasts in `strings.xml:52-60`)

| ExoPlayer error | Behaviour |
|---|---|
| network failed/timeout/IO unspecified | re-`prepare()` retries ×3 with 1.5 s·n backoff → then re-resolve ×2 → `surfaceRecoveryExhausted()` + toast `player_stream_error` = "Playback unavailable. Please try again later." |
| bad HTTP status | 403 classified via telemetry: **GEO_RESTRICTED** → stop, toast + `Error(player_geo_restricted` = "This video is not available in your region.") — terminal; **RATE_LIMITED** → toast `player_rate_limited`, backoff `retry-after` header or 2·2ⁿ s (cap 32 s) then re-resolve; **URL_EXPIRED** → toast `player_stream_expired` = "Stream expired. Refreshing…", immediate re-resolve; **UNKNOWN/HTTP/NETWORK** → 1.5 s·(n+1) backoff re-resolve; all capped at 2 refreshes → exhausted + toast `player_stream_unavailable` = "This video is not available for playback." (`handle403OrHttpError` `:2321-2491`) |
| file-not-found / read-out-of-range / malformed container/manifest | re-resolve ×2 → exhausted + `player_stream_unavailable` toast |
| decoder errors (5 codes) | codec-swap / step-down (§3.2); nothing lower → stop + `Error(player_decoder_no_compatible_quality)`; audio-renderer decoder failure → toast only (`player_decoder_audio_error`) |
| BEHIND_LIVE_WINDOW | seek to live edge + re-prepare, lifetime cap 3 → exhausted + toast "…: {errorCodeName}" |
| everything else | generic re-resolve, lifetime cap 3 → exhausted + toast "…: {errorCodeName}" |

`streamRefreshCount` resets on every successful resume (budget is per failure-episode, not
per video — `:1154-1160`); lifetime counters reset only on stream change (`:1102-1110`).
Every re-resolve shows toast `player_status_resolving` = "Resolving stream…" (`:2225-2227`).

Resolution-side failures (before ExoPlayer): 3 resolve attempts exhausted →
`Error(player_stream_error)`; null streams → `Error(player_stream_unavailable)`; no selection →
same; playlist mode tries auto-skip first (§12.3) (`PlayerViewModel.kt:1496-1546`).

---

## 17. Layout inventory + dimensions

Files: `res/layout/fragment_player.xml` (692 lines) and `res/layout-sw600dp/fragment_player.xml`
(691). **No `layout-sw720dp/fragment_player.xml` and no `layout-land` variant** — sw720
devices use the sw600 layout (with sw720 dimens), landscape is handled programmatically (§7).

Structure (both): `CoordinatorLayout` (bg `?colorSurface`) → `AppBarLayout` (black, elevation 0)
→ `CollapsingToolbarLayout` (`minHeight player_min_height` **120 / 180 / 240 dp**
(`values/dimens.xml:78`, `sw600:24`, `sw720:23`), scrollFlags `scroll|exitUntilCollapsed|snap`,
black contentScrim) containing: 16:9 `playerContainer`+`PlayerView`, 16:9 error overlay, 16:9
recovery overlay, invisible pinned toolbar placeholder, `playerOverlayControls`; then
`NestedScrollView` (`paddingBottom bottom_nav_height` 72/0/0 dp) with the §13 column.
The collapsing toolbar gives the portrait "scroll content up, video shrinks to
`player_min_height`" behaviour; fullscreen zeroes the scroll flags (§7.2).

sw600dp deltas only: outer scroll padding `spacing_lg`; content column wrapped in a
ConstraintLayout capped at `content_max_width` (1200/1600 dp); description card corner
`corner_radius_medium` (vs phone `thumbnail_corner_radius` 8dp) and `spacing_md` paddings;
description body 12sp (`text_caption`) vs phone 14sp (`text_body`) — (diff of the two files);
action-row icons `icon_medium` 36→40 dp vs phone `icon_small` 24dp; button padding `spacing_md`.

Key dimens: `touch_target_min` 48dp (sw720: 56 — `values-sw720dp/dimens.xml:41`),
`icon_large` 48/56/64 dp, `text_headline` 20sp (sw720 24), `text_caption` 12sp (sw720 14).

---

## 18. Phase 3 surfaces present in the player (record-only)

- **Download** button states by `DownloadEntry.status`: COMPLETED → opens the file via
  FileProvider ACTION_VIEW chooser; RUNNING/QUEUED → disabled; else → `DownloadQualityDialog`
  (audio-only entry first, then unique heights descending; result via Fragment Result API;
  toast `download_started`) (`PlayerFragment.kt:2105-2137,560-572,3258-3312`,
  `DownloadQualityDialog.kt:26-120`).
- **Cast**: `MediaRouteButton` in the overlay; session listeners load a single progressive URL
  + metadata to the receiver, pause local, toast `player_cast_started` (`:364-384,3922-3985`).
  Casting an adaptive-only stream sends `selectTrack`'s progressive pick — a Phase 3 concern.

---

## 19. Behavioural checklist (testable contract)

1. Player opens from 7-arg fast path and renders title/author/stats instantly; missing fields
   hydrate in the background without overwriting present ones.
2. New video always autoplays; audio is muted until the first video frame (≤3 s cap).
3. Quality picker: highest-first, current checked, cap semantics, 300 ms coalescing, works
   during a stall, collapses to one entry on non-adaptive sources.
4. Audio-only toggle rebuilds source at the same position; per-session only.
5. Backgrounding never pauses; notification appears iff (app background OR player screen left)
   and reflects play/pause; tap returns to the same player; user pause survives background.
6. Rotate to landscape → fullscreen (phones, landscape-shot video); rotate back → exit; button
   exit in landscape forces portrait and suppresses exactly one auto-re-enter; portrait videos
   fullscreen in portrait. Fullscreen never leaks past the screen.
7. Fill-vs-fit auto per video (5 % crop budget), centre double-tap overrides per stream, hint
   snackbar once ever.
8. Double-tap thirds seek ±10 s; controls auto-hide 5 s (3 s after first play).
9. Subtitles: Off-first picker, auto-gen suffix, 18 sp bottom-centred cues, per-video reset.
10. Playlist: deep-start ≤250 items/3 s, lazy paging at ≤5 remaining, shuffle pins tapped item
    and disables paging, ended → auto-advance, ≤3 auto-skips for dead items, prev from history
    (≤100), channel names blanked until parent channel approval confirms.
11. Errors: overlay with Retry+Refresh for generic errors; ContentUnavailable overlay with **no**
    retry; RecoveryExhausted overlay with Retry after auto-recovery gives up; geo-restriction is
    terminal; every forced refresh is rate-limited with a "wait a moment" toast.
12. No position is remembered after leaving the player.

---

## 20. Open questions

**Q1 — Watch progress.** Android persists nothing (`PlaybackService.kt:986-995`), by explicit
comment. iOS parity = no resume-where-you-left. Ship parity, or is Phase 2 the moment to add
per-video resume (it changes data model + Continue Watching expectations)?

**Q2 — Related videos / empty Up Next.** Single-video mode shows an "Up next" header over an
empty list (`PlayerViewModel.kt:2218`, `PlayerFragment.kt:1534-1536`). Mirror the empty
section, hide it, or add a related-videos source (none exists server-side today)?

**Q3 — Dead settings.** `safeMode`, `backgroundPlay`, settings-`audioOnly` are written but
never read (§15). Should iOS wire them (background-play OFF actually pausing on background;
settings audio-only seeding the toggle), or replicate the dead switches for parity?

**Q4 — Playback speed.** Android exposes speed only through Media3's stock settings menu, not
persisted (§10). Does the iOS player expose a speed control at all, and if so is it per-video
or persisted?

**Q5 — Quality persistence.** The user's pick evaporates per stream; only
`last_successful_height` seeds cold-start AUTO (§4.3). Persist a user quality preference on
iOS, or mirror?

**Q6 — View-count / duration formatting drift.** Player stats use `%.1fM views` +
"No views yet" with an unreachable billions branch (`PlayerFragment.kt:1484-1493`); up-next
uses non-localized `String.format("%.1fK")` and an `m:ss` formatter that never emits hours
(`UpNextAdapter.kt:60-79`); list screens use ICU compact + plurals (Phase 1 §5.6). One
formatter on iOS — which?

**Q7 — Stats line has no upload date** though the layout placeholder shows one
(`fragment_player.xml:348`), and **no like count** (orphan strings `strings.xml:114,274`;
NewPipe exposes like counts). Add on iOS or mirror the omission?

**Q8 — `channelName` arg carries `category`** from Videos/Featured (Phase 1 Q4), so the green
author line under the title shows a category name for those entry points. Fix on iOS or
replicate?

**Q9 — Stream resolution stack.** §3's recovery machinery is NewPipe/ExoPlayer-specific
(synthetic DASH, 403 refresh ladders, HLS poisoning). Prior iOS probing found different
constraints (VISIONOS streams w/o pot; IOS client 403s ~60 s; itag18 fallback — memory
`ios-youtube-client-findings-2026-08`). What is the Phase 2 acceptance bar: full ladder parity,
or "plays reliably with position-preserving refresh on failure" as the contract (§16 table)?

**Q10 — Excluded-items scaffolding.** `excludedItems`/`excludedMessage`/`analyticsStatus`/
`playerStatus`/`currentlyPlaying` are all invisible 0dp views fed by live code
(`fragment_player.xml:627-686`, `PlayerFragment.kt:1540-1547`) and the stub queue is empty.
Skip this machinery on iOS entirely?

**Q11 — Tablet fullscreen.** Auto-fullscreen-on-landscape is phone-only (`PlayerFragment.kt:648`);
tablets fullscreen only via the button. iPad: mirror (button-only) or use standard iOS
full-screen presentation on rotate?

**Q12 — PiP.** Android offers PiP only from an overflow menu item, no auto-PiP (§7.4). iOS
convention is automatic PiP via AVKit. Mirror the manual trigger, or adopt platform-standard
auto-PiP (interacts with Q3's background-play question)?

---

## 21. Audio focus / interruption posture

**NO ANDROID CONTRACT — iOS ruling required.**

Grep for `setAudioAttributes|AudioAttributes|AudioFocus|handleAudioFocus` over `java/` →
**zero hits**. The app never requests Android audio focus. The only interruption handling
anywhere is `setHandleAudioBecomingNoisy(true)` on both ExoPlayer builders — the main player
(`PlayerFragment.kt:974`) and Shorts (`ui/shorts/ShortsPlayerViewModel.kt:101`) — which only
pauses on headphone/output-device disconnect, already recorded at §3.

Consequences that follow from the zero-hit grep, and that no other section of this brief
states: the app does **not** pause for phone calls or other apps' playback, does not duck for
notifications/Siri/navigation prompts, and can play simultaneously with another audio app.

iOS has no equivalent gap to mirror: `AVAudioSession` requires a category and an interruption
policy — there is no "do nothing" option comparable to Android's no-focus-request behaviour.
This needs an explicit product ruling on iOS audio session category (e.g. `.playback` with
`.mixWithOthers` to approximate Android's laissez-faire posture, vs. standard interruption
handling that pauses on calls/other audio) rather than a port, since Android supplies no
contract to port.

---

## 22. Main-player audio-language (dub) picker flow

§8.2 records only the globe/audio-language button's visibility rule
(`PlayerFragment.kt:1520-1525`: shown iff `availableAudioLanguages().size >= 2`), and §2.1
names the `AudioTrackSwapReady`/`DubAudioResolveFailed` events without describing the flow
that produces them. The full main-player flow, undocumented elsewhere in this brief:

- **Opening the picker** — `showAudioLanguagePicker()` (`PlayerFragment.kt:1952-1966`) requires
  `StreamState.Ready` and ≥2 languages (re-guarded here even though the button is already
  hidden below that threshold, against races between the tap and the menu opening). The
  original track is labelled via `shorts_audio_track_original_prefix` = "Original: %1$s"; every
  other track shows its plain display name.
- **The dialog** — `ui/shorts/AudioLanguageDialog.kt:22` (`class AudioLanguageDialog :
  DialogFragment()`). This dialog is specced only for Shorts elsewhere in this corpus
  (playlist-detail-shorts.md §9.4) — **the main player reuses the same dialog class**, which no
  brief states until now.
- **Picking a language** — the Fragment-Result listener (`PlayerFragment.kt:512-536`) reads the
  selected language code off `AudioLanguageDialog.REQUEST_KEY`, maps it back to a representative
  track in `availableAudioLanguages()`, and calls `viewModel.selectAudioTrack(chosen.representative)`.
- **Applying the swap** — the `PlayerUiEvent.AudioTrackSwapReady` handler
  (`PlayerFragment.kt:1572-1589`) rebuilds the `MediaSource` around the new selection for every
  adaptive type (HLS/DASH/SYNTH_ADAPTIVE) — a deliberate rebuild-path choice, not native HLS
  audio steering, because native steering hit a Media3 1.10.0 regression
  (`androidx/media#3161`) that could kill the playback thread on a multi-rendition HLS group.
- **Failure** — `PlayerUiEvent.DubAudioResolveFailed` (`PlayerFragment.kt:1659`) shows a
  `player_stream_error` toast; no further recovery.

---

## 23. System back while fullscreen

**iOS ruling required.**

`PlayerFragment` registers **no** `OnBackPressedCallback` (grep, zero hits). System back is
handled solely by `MainActivity.kt:81-107`'s `onBackPressedDispatcher` callback, which pops the
nested nav host's back stack first, then falls back to `navController.navigateUp()` / `finish()`
— it has no fullscreen awareness at all. So a system-back or gesture-back press while the
player is fullscreen pops the player destination **entirely**; fullscreen cleanup then rides
the fragment's `onDestroyView` path (`PlayerFragment.kt:822-829`: orientation reset, system-bar
restore, shell-root restore).

§7 of this brief covers button-driven exit and rotation-driven exit but never this
system-back/gesture path, and it is not in the §19 behavioural checklist either. A
YouTube-style "back exits fullscreen first, a second back leaves the player" is the common
user expectation and is **not** what Android does today — iOS must decide whether to mirror
Android's single-step exit (defect, by common convention) or implement the two-step
fullscreen-then-leave pattern, which has no Android contract to copy.

---

## 24. Task-removed (swipe from recents) contract

`PlaybackService.onTaskRemoved` (`player/PlaybackService.kt:684-690`):

```
override fun onTaskRemoved(rootIntent: Intent?) {
    val player = mediaSession?.player
    if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
        stopSelf()
    }
}
```

The service stops itself only when nothing is actively playing. With active playback
(`playWhenReady == true` and a non-empty queue), the service — and the audio — **survives the
user swiping the app away from Recents**. §6.1's lifecycle contract covers fragment
onStart/onStop/onDestroyView but not this task-removed path.

This is exactly the kind of background-audio behaviour iOS must consciously map: `AVAudioSession`
`.playback` category playback normally continues in the background regardless of whether the
app's UI is "removed" from the app switcher (iOS has no direct analogue to a swiped-away task
killing a foreground service), so the practical iOS question is narrower — whether to keep
playing after an explicit user swipe-to-dismiss the way Android does when something is playing,
which needs a ruling rather than an assumption of parity.
