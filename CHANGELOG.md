# Changelog

All notable changes to FitrahTube. Versions are tagged on the `develop` branch
during the beta program.

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
