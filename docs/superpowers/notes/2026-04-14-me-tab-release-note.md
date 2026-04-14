# ANDROID-PERSONAL-01 — Me Tab Release Note

**Branch:** `feature/ANDROID-PERSONAL-01-me-tab`
**Landed:** ready for user review (NOT yet merged into `develop`)
**Scope:** Android app only. No backend / frontend changes.

## Summary

Replaces the **Downloads** bottom-nav tab with a new **Me** tab — a YouTube-style personal feed aggregating content from subscribed channels and saved playlists. Downloads is preserved and reachable from **Settings → Library → Downloads library**. A new **Settings → Library → Favorites** row exposes the existing favorites surface too.

## What ships

1. **Subscriptions model (local only, Room v2)**
   - `SubscribedChannel`, `SavedPlaylist`, `ChannelVideoCache`, `ChannelFeedRefreshState` entities.
   - Additive `MIGRATION_1_2`; no existing data touched.
   - `SubscriptionRepository` and DAOs behind `DatabaseModule`/`MeModule`.
2. **Me feed orchestration**
   - `MeFeedRepository` fetches per subscribed channel via on-device NewPipeExtractor.
   - Rate-limit friendly: Semaphore(4), 250 ms stagger, 30-min per-channel TTL, 50-channel cap per refresh, ≤30 items per channel, 14-day upload-date window.
   - Per-channel failures never abort siblings.
3. **Me UI** (`ui/me/`)
   - `MeFragment` + `MeViewModel` + `MeChipsAdapter` + `MeShortsAdapter` + `MeVideosAdapter`.
   - Layouts in `layout/`, `layout-sw600dp/`, `layout-sw720dp/` (identical IDs).
   - ConcatAdapter composes chips row + shorts strip + videos list.
   - Empty state, SwipeRefresh, auto-loadMore guard, tap-chip-to-filter.
4. **Navigation**
   - `bottom_nav_menu.xml`: Downloads item replaced with Me (new `ic_nav_me.xml`, project-local vector, no vector tint — follows CLAUDE.md Samsung S25 Ultra rules).
   - `main_tabs_nav.xml`: `meFragment` destination + `action_settingsFragment_to_downloadsFragment` + `action_settingsFragment_to_favoritesFragment`.
5. **Settings → Library**
   - New Library section above Playback in all three `fragment_settings.xml` variants.
   - Rows: Downloads library, Favorites.
   - `SettingsFragment` routes taps with guarded `currentDestination` navigation.
6. **Subscribe / Save toggles**
   - `ChannelDetailFragment`: subscribe toggle below header summary.
   - `PlaylistDetailFragment`: save toggle in the actions row.
   - Both reflect state from `SubscriptionRepository` flows and flip on tap.
7. **i18n**
   - New strings added to `values/`, `values-ar/`, `values-nl/` (nav, empty state, section headers, subscribe/save, settings library).

## Deferred / explicit non-goals

- **Community posts.** NewPipeExtractor v0.26.0 exposes channel tabs for Videos / Shorts / Livestreams / Playlists / Channels — posts are not part of the stable surface for YouTube. Deferred. The Me layout is structured to accept a posts section later without restructuring.
- **Backend sync of subscriptions.** Local only, per prompt.
- **Long-press on list items for quick subscribe.** Detail-screen toggles were deemed sufficient for v1.
- **"Saved Videos" surface on the Me screen.** Favorites remain reachable via Settings → Library → Favorites to keep the Me screen focused on the Subscriptions-style feed pattern (screenshots 3–4).

## Automated verification — green

- `./gradlew --offline clean assembleDebug`
- `./gradlew --offline testDebugUnitTest` — 24 new unit tests pass:
  - `SubscriptionDaoTest` (6)
  - `ChannelVideoCacheDaoTest` (5)
  - `SubscriptionRepositoryTest` (3)
  - `MeFeedRepositoryTest` (7)
  - `MeViewModelTest` (3)
- `./gradlew --offline :app:lintDebug`

## Manual QA — user to verify

Automated emulator coverage was not run from this session (no devices connected). Please verify the following visually:

### Devices

- [ ] **Pixel 7 (phone, API 34+)** — BottomNavigationView shows Me icon tinted correctly; Me screen renders empty state; subscribe a channel → chip appears in row; videos show in vertical list.
- [ ] **Pixel Tablet (tablet, API 34+)** — NavigationRailView shows Me; videos render in 2-column grid; chips row + shorts strip unchanged.
- [ ] **Android TV 1080p (TV, API 34+)** — NavigationRailView shows Me with TV padding; 3-column video grid; D-pad navigation works across chips → shorts → videos.
- [ ] **Samsung S25 Ultra (Android 15, SDK 35) — canary** — Me nav icon is **visible** (not washed out by double-tint). No double-inset at bottom nav. Edge-to-edge system bars correct. `fragment_main_shell` unchanged.
- [ ] **Huawei Honor Play (Android 14)** — system bars OK; Arabic RTL renders correctly; Me icon visible.

### Feature golden paths

- [ ] Bottom nav swap: Downloads icon is gone; Me icon appears in its slot.
- [ ] Tap Me with no subscriptions → empty-state CTA appears; tapping CTA navigates to Channels tab.
- [ ] Open a channel → subscribe button toggles label (Subscribe ↔ Subscribed). Back to Me → channel chip appears.
- [ ] Tap the chip → shorts + videos filter to that channel; tapping the chip again clears the filter.
- [ ] Open a playlist → save button toggles (Save ↔ Saved) with heart icon change. Back to Me → playlist chip appears; tapping navigates to playlist detail.
- [ ] Settings → Library → Downloads library → opens DownloadsFragment (existing, unchanged).
- [ ] Settings → Library → Favorites → opens FavoritesFragment (existing, unchanged).
- [ ] Pull-to-refresh on Me → triggers a forced refresh; progress indicator shows; feed updates.
- [ ] Subscribe to a known-good channel with very recent uploads → wait 30s → pull to refresh → videos within last 14 days appear.
- [ ] RTL: set device locale to Arabic → Me screen mirrors correctly; textAlignment respects viewStart; chip avatar + label order remains readable.

### Rate-limit sanity

- [ ] Subscribe to 10+ channels → open Me → watch logcat: no more than 4 concurrent channel fetches; fetches staggered by ~250 ms; no YouTube 429 errors.
- [ ] Close + reopen app within 30 min → refresh skips already-cached channels (no network calls).

## Rollback

Safe. Additive schema migration (v1 → v2) creates new tables only; no existing tables touched. Reverting the branch restores Downloads to the bottom nav. Users on v2 DB downgrading would hit schema mismatch — same risk as any Room migration. Destructive fallback is still enabled in debug builds per the existing DatabaseModule.

## Key docs

- Spec: `docs/superpowers/specs/2026-04-14-me-tab-design.md`
- Plan: `docs/superpowers/plans/2026-04-14-me-tab-implementation.md`
