# ANDROID-SHORTS-01 — Custom Shorts Player Design

**Status**: Approved (user granted full autonomy — implementation proceeds without pause)
**Date**: 2026-04-14
**Branch**: `feature/ANDROID-SHORTS-01-custom-shorts-player`

## Goal
Dedicated vertical Shorts player screen matching YouTube Shorts visually, scoped to this app's actual features. Remove Comments and Remix entirely; remove Dislike. Keep Like, Share, Subscribe + channel overlay + title.

## Non-Goals
- Modifying the existing 3000-line `PlayerFragment.kt` (risk of regression; `ebbc8ca` player improvements just merged).
- Introducing a second media stack (reuse Media3 ExoPlayer).
- Any server-side like/follow sync — no backend exists for those actions.

## Architecture

### New surfaces
| File | Purpose |
|------|---------|
| `ui/shorts/ShortsPlayerFragment.kt` | Fullscreen vertical player host |
| `ui/shorts/ShortsPlayerViewModel.kt` | Holds ExoPlayer, paginated shorts list, Like/Follow toggles |
| `ui/shorts/ShortsPagerAdapter.kt` | RecyclerView adapter, one page per short |
| `ui/shorts/ShortsPageViewHolder.kt` | Per-page overlay + inactive PlayerView placeholder |
| `ui/shorts/PlayerBinder.kt` | Swaps single `Player` instance between visible holders |
| `data/local/FollowedChannelsRepository.kt` + `FollowedChannel` entity + DAO | Local follow state |
| `data/shorts/ShortsFeedRepository.kt` | Cursor pagination adapter over `ContentService.fetchContent(type=VIDEO, videoLength=UNDER_FOUR_MIN)` |
| `res/layout{,-sw600dp,-sw720dp}/fragment_shorts_player.xml` | Host fragment — a single `ViewPager2` |
| `res/layout{,-sw600dp,-sw720dp}/item_shorts_page.xml` | Per-page PlayerView + right action rail + bottom channel overlay |
| Nav entry: `action_global_shortsPlayerFragment` in `main_tabs_nav.xml` | |

### Reused
- `ExoPlayer` construction pattern (single instance owned by ViewModel, survives config change).
- `MultiRepSyntheticDashMediaSourceFactory` for adaptive quality.
- `StreamPrefetchService.triggerPrefetch(id, scope)` for next-page warm-up.
- `FavoritesRepository` doubles as the Like store (favorite ≡ liked — semantically aligned, no new table needed).
- Edge-to-edge pattern from `PlayerFragment`: `WindowCompat.setDecorFitsSystemWindows(window, false)` on enter, restore on exit.
- `MediaSessionMetadataManager` for current short's metadata.
- Existing `ChannelDetailRepository` / `ChannelHeader` for avatar + handle + verified badge.

## Player lifecycle (single-audio guarantee)

1. `ShortsPlayerViewModel` owns exactly one `ExoPlayer` instance (`@Inject PlayerFactory`).
2. `repeatMode = REPEAT_MODE_ONE` — auto-loop.
3. `ViewPager2.OnPageChangeCallback.onPageSelected(pos)`:
   - Detach `PlayerView` from previous holder (`holder.binding.playerView.player = null`).
   - Build new `MediaSource` for shorts[pos] via factory.
   - `player.setMediaSource(mediaSource); player.prepare(); player.play()`.
   - Attach `PlayerView` of new holder.
   - `prefetchService.triggerPrefetch(shorts[pos+1].id, viewModelScope)`.
4. Tap anywhere on the video surface → `player.playWhenReady = !player.playWhenReady`.
5. `onPause()` → `player.pause()`; `onResume()` → resume if previously playing.
6. `onDestroyView()` → detach `PlayerView`. `ViewModel.onCleared()` → `player.release()`.

### Why not N players? 
Single player enforces single-audio-stream guarantee from ANDROID-MULTI-01. ViewPager2 with multiple players is a well-known audio-bleed source.

## UI composition (per-page)

```
┌─────────────────────────────┐
│  [back]      [search] [⋮]  │ ← top bar (reuse system toolbar overlay, 56dp)
├─────────────────────────────┤
│                             │
│                             │
│                       ┌───┐ │
│                       │ ♥ │ │ ← Like (favorite)
│       PlayerView      │123│ │
│       (9:16 frame,    ├───┤ │
│        letterboxed    │ ⤴ │ │ ← Share (ShareCompat)
│        on tablet/TV)  │   │ │
│                       └───┘ │
│                             │
├─────────────────────────────┤
│ [avatar] @handle [Subscribe]│ ← channel overlay
│ Video title (2-line max)    │
└─────────────────────────────┘
```

- Right rail horizontally mirrored to the **left** when `View.layoutDirection == LAYOUT_DIRECTION_RTL`.
- Avatar + @handle tap → `action_global_channelDetailFragment`.
- Subscribe button toggles `FollowedChannelsRepository.toggleFollow(channelId, ...)`.
- Tablet/TV (`sw600dp`/`sw720dp`): center the 9:16 video frame with black letterbox bars on either side. Action rail + bottom overlay sit inside the 9:16 column, not the letterbox, so they track the video edge.

## Data flow

```
ShortsPlayerFragment
   ├ args: initialShortId, sourceContext? (enum: FEED | CHANNEL:{id})
   └ ViewModel
        ├ FeedLoader (ShortsFeedRepository)
        │   - sourceContext=FEED  → ContentService.fetchContent(VIDEO, length=SHORT, cursor)
        │   - sourceContext=CHANNEL → ChannelDetailRepository.fetchShorts(channelId, cursor)
        ├ Player (ExoPlayer, singleton-per-VM)
        ├ FavoritesRepository  (like toggle)
        └ FollowedChannelsRepository  (subscribe toggle)
```

Page size 10. Autofill not needed — ViewPager2 preloads the next page ±1 offscreen. Load next page when `currentPosition >= items.size - 3`.

## Entry points

1. `ChannelShortsTabFragment` (ui/detail/tabs/) — change its `onClick` from `action_global_playerFragment` to `action_global_shortsPlayerFragment` with `sourceContext=CHANNEL:{channelId}` and `initialShortId=short.id`.
2. Future: home-feed shorts row (out of scope this ticket — leave the tap path easy to wire).

## Share button integration
`ShareCompat.IntentBuilder(context).setType("text/plain").setText("https://www.youtube.com/shorts/$id").startChooser()`. No stub needed — canonical URL is deterministic from video ID.

## Dislike decision
**Removed.** Rationale: app has no dislike backend, YouTube itself hides dislike counts globally since 2021, a display-only dead button is worse UX than absence. Freed vertical space tightens the rail.

## Subscribe decision
Local-only follow state, Room-backed (`FollowedChannelsRepository` mirrors `FavoritesRepository` pattern — `@Entity FollowedChannel(channelId PK, title, avatarUrl, followedAt)`). Tap on Subscribe toggles. Tap on avatar/handle navigates to channel detail (where user can also unfollow).

## Edge-to-edge / fullscreen

- `fragment_shorts_player.xml` has `android:fitsSystemWindows="false"` — shorts go truly full-bleed behind status bar. Top bar manually adds a `paddingTop="@dimen/status_bar_height_placeholder"` via `ViewCompat.setOnApplyWindowInsetsListener`.
- On `onResume()`: `WindowCompat.setDecorFitsSystemWindows(window, false)` + `window.insetsController?.hide(systemBars)`.
- On `onPause()` and view destruction: restore `setDecorFitsSystemWindows(window, true)` and `show(systemBars)` — matches PlayerFragment pattern (PlayerFragment.kt:3199/3303).
- Shell fragment's `fitsSystemWindows="true"` is NOT touched.

## Multi-device behavior

| Device | Layout | Video frame | Rail |
|--------|--------|-------------|------|
| Phone (< 600dp) | `layout/` | Fills width, centered vertically | Overlays right edge |
| Tablet (`-sw600dp`) | `layout-sw600dp/` | 9:16 column centered, black letterbox sides | Inside the 9:16 column |
| TV (`-sw720dp`) | `layout-sw720dp/` | 9:16 column centered, wider letterbox | Inside the 9:16 column; D-pad focusable actions |

TV D-pad: rail items are `focusable="true"` with visible focus highlight. Up/Down cycles rail actions. Left/Right on rail → back to video. DPAD_CENTER on video → play/pause.

## RTL

`layoutDirection="locale"` on root; right rail uses `layout_gravity="end"` so it auto-mirrors to the left in Arabic. Bottom overlay uses `textAlignment="viewStart"`. Verified against the two pattern files cited in the survey (fragment_player.xml:296, fragment_home_new.xml).

## Samsung S25 Ultra specifics

- Do not use any `ic_stat_*` or `@android:drawable/*` icons on the rail. Use new project-local vectors `ic_shorts_like`, `ic_shorts_share`, `ic_shorts_subscribed` with no vector-level `android:tint`, fillColor driven by `app:tint` from the ImageButton.
- No BottomNavigationView / NavigationRailView is introduced inside the shorts screen, so the double-inset trap does not apply.

## Error handling

| Error | UX |
|-------|----|
| Empty feed (no shorts) | Full-page empty state ("No shorts available") with back arrow |
| Extraction error on current short | Inline overlay "Couldn't play this short" + retry + skip to next |
| Network error on pagination | Inline footer-ish toast "Load more failed — tap to retry" |
| Age/private/geo-blocked | Auto-skip forward to next short, log analytics |

## Testing strategy

**Unit (JVM):**
- `ShortsPlayerViewModelTest` — page pagination, like toggle propagation, follow toggle propagation, auto-skip on ContentNotAvailable, list exhaustion state.
- `ShortsFeedRepositoryTest` — cursor handling, filter param passthrough, channel-vs-feed routing.
- `FollowedChannelsRepositoryTest` — Room-based (in-memory DB) toggle/isFollowed/count.

**Instrumented (Hilt):**
- `ShortsPlayerFragmentTest` — verifies:
  - Tap toggles play/pause.
  - Swipe-up advances page and rebinds player.
  - Like button reflects `FavoritesRepository` state.
  - Share button fires `ACTION_SEND` intent (captured via IntentsRule).
  - Subscribe button toggles follow.
  - Back press restores system bars.
  - Entry from ChannelShortsTab passes channel context.

Fake dependencies via `@BindValue` per existing pattern (NavigationGraphTest.kt:37 style). `FakeContentService` extended with shorts page fixtures.

**Manual QA (mandatory — list at end of work):** Samsung S25 Ultra Android 15, Huawei Honor Play Android 14, Pixel Tablet AVD, Android TV AVD.

## Performance targets
- Swipe → first-frame ≤ 800 ms on warm prefetch, ≤ 2 s cold.
- Steady 60 fps during swipe and action rail animations. No jank on page change.
- Profile with `dumpsys gfxinfo` + Android Studio profiler if any frame stutters.

## Rollout
- Atomic commits per module (ViewModel → Repository → Fragment → Nav → Tests).
- Merge target: `develop` only. Never `main`. Never `--no-verify`.

## Definition of done
All tests green, `./gradlew assembleDebug` + `./gradlew test` + lint green, code-reviewed (code-reviewer subagent + /codex), status docs updated. Only gap allowed at "done": human on-device visual QA across the four device profiles.
