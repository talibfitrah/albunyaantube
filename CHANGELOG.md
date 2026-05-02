# Changelog

All notable changes to FitrahTube. Versions are tagged on the `develop` branch
during the beta program.

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
