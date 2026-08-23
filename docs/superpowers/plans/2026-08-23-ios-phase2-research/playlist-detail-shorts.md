# Phase 2 research — Playlist Detail & Shorts

Scope: `PlaylistDetailFragment` / `PlaylistDetailViewModel` / `NewPipePlaylistDetailRepository` /
`PlaylistVideosAdapter`, and `ShortsPlayerFragment` / `ShortsPlayerViewModel` /
`ShortsFeedRepository` / `ShortsPagerAdapter` / `ShortsPageViewHolder` / `PlayerBinder`.
Every claim cites `android/app/src/main/**` file:line. Behavioural contract for the SwiftUI port —
no Swift code here. Phase 3 items (downloads) are recorded factually and flagged, not designed.

---

## 0. TL;DR for the implementer

1. **Playlist detail never calls the backend for content.** Header + items come straight from
   NewPipeExtractor (`PlaylistDetailViewModel.kt:35`, `NewPipePlaylistDetailRepository.kt:36`).
   The backend is only consulted as an availability gate and a channel-approval gate.
2. **Playlist search is client-side filtering** of already-fetched pages — it does not hit the
   network and it disables pagination while active (§3.5).
3. **Shorts is a pager that cannot be swiped.** `isUserInputEnabled = false` is a deliberate
   anti-doom-scrolling product decision (`ShortsPlayerFragment.kt:322-329`). One short loops
   forever (`REPEAT_MODE_ONE`, `PlayerBinder.kt:154`); the user backs out and taps another.
4. **Shorts "feed" = `/api/v1/content?type=VIDEOS&length=SHORT` page size 10** when launched
   without a channel; channel-scoped shorts come from the channel repo (§7.2).
5. **Item long-press does not exist** on either screen. Playlist rows and shorts pages are
   tap-only; overflow actions live in the toolbar kebab (playlist: Share/Report; shorts:
   Quality/Report).

---

# PART A — PLAYLIST DETAIL

## 1. Entry points and arguments

Nav destination `playlistDetailFragment` (`res/navigation/main_tabs_nav.xml`, destination block):
args `playlistId: String` (required), `playlistTitle: String?`, `playlistCategory: String?`,
`playlistCount: Int = 0`, `downloadPolicy: String = "ENABLED"`, `excluded: Boolean = false`.
Deep links: `albunyaantube://playlist/{playlistId}`,
`https://app.fitrahtube.com/playlist/{playlistId}`, `https://app.fitrahtube.com/api/playlist/{playlistId}`.

Callers (grep `playlistDetailFragment`): `PlaylistsFragmentNew.kt:134` (id+title only),
`SearchFragment.kt:312`, `ChannelPlaylistsTabFragment.kt:44`, `HomeFragment.kt:127`,
`MeFragment.kt:582`, `FeaturedListFragment.kt:84` (Featured also passes category+count — Phase 1
brief §4.10). Arg names are constants at `PlaylistDetailFragment.kt:788-793`.

Arg parsing: unknown `downloadPolicy` string falls back to `ENABLED` (`PlaylistDetailFragment.kt:86-93`).
All six args are fed into the ViewModel via an assisted factory (`:96-109`,
`PlaylistDetailViewModel.kt:658-668`).

## 2. Data layer — `NewPipePlaylistDetailRepository`

File: `data/playlist/NewPipePlaylistDetailRepository.kt` (@Singleton, `:41-48`).

### 2.1 Header

- `getHeader(playlistId, forceRefresh, category, excluded, downloadPolicy)` →
  NewPipe `PlaylistInfo` mapped to `PlaylistHeader` (`:117-128`, `:310-340`).
- `PlaylistHeader` fields (`data/playlist/PlaylistDetailModels.kt:13-43`): `id, title,
  thumbnailUrl, bannerUrl, channelId, channelName, itemCount: Long?, totalDurationSeconds,
  description, tags, category, excluded, downloadPolicy, isChannelLinkable = false,
  parentChannelUrl`.
- **`totalDurationSeconds` is always nil** — "Not directly available from PlaylistInfo"
  (`NewPipePlaylistDetailRepository.kt:329`); `tags` always empty (`:331`). So the
  "N videos • 2h 5m" metadata variant is currently dead (§3.4).
- Thumbnail choice: highest image by height (fallback width) from NewPipe's list (`:358-369`).
- `itemCount = streamCount.takeIf { it >= 0 }` (`:328`).
- Info cache: in-memory LRU, **max 100 entries, TTL 30 min**, mutex-guarded (`:64-73`, `:539-542`).
  `forceRefresh` bypasses the read but still writes (`:234-243`, `:261-263`).

### 2.2 Items

- `getItems(playlistId, page, itemOffset)` → `PlaylistPage<PlaylistItem>` (`:130-223`).
- Initial page = `PlaylistInfo.relatedItems` + `info.nextPage`; **silent-swallow rescue**: if
  `relatedItems` is empty but `streamCount > 0`, re-extract the initial page directly so a parse
  failure surfaces instead of rendering a wrong empty state (`:147-171`).
- Later pages: `PlaylistInfo.getMoreItems(service, "https://www.youtube.com/playlist?list=$id", page)`
  (`:181-199`).
- Items are `StreamInfoItem`s mapped to `PlaylistItem(position, videoId, title, thumbnailUrl,
  durationSeconds, viewCount, publishedTime, channelId, channelName, uploadedAtMillis)`
  (`PlaylistDetailModels.kt:48-67`, mapping `:342-356`). **`position` is 1-based** and computed
  from the running `itemOffset` (`:175-177`, `nextItemOffset = itemOffset + items.size` `:211`).
  Items whose video id can't be parsed are dropped (`:343`).
- `durationSeconds` only kept when `1..Int.MAX_VALUE` (`:349`); `viewCount` only when `>= 0` (`:350`).
- Fetched items are piggyback-indexed into `IndexRepository` fire-and-forget; index errors are
  swallowed (`:202-208`).
- Errors: `CancellationException` rethrown; `IOException`/`ExtractionException` rethrown as-is;
  anything else wrapped in `ExtractionException` (`:213-221`).
- ID normalization: raw ids starting `PL|UU|OL|RD` are retried as `playlist?list=<id>` when the
  first `fromId` fails (`:283-306`).

### 2.3 Canonical channel-id resolution (linkable-channel gate support)

`resolveCanonicalChannelId(uploaderUrl)` (`:432-494`): `/channel/UC…` short-circuits; handle/name
URLs (`/@x`, `/c/x`, `/user/x`) need a NewPipe `ChannelInfo.getInfo` fetch. Canonical form:
`^UC[A-Za-z0-9_-]{22}$` (`:546`). Positive cache 24 h, negative cache 5 min, max 200 entries
(`:547-553`); single-flight dedupe on an independent supervisor scope so a caller cancellation
can't kill a fetch another caller awaits (`:96-115`, `:453-493`). A transient failure never
overwrites an expired-but-valid positive entry (`:511-529`).

## 3. `PlaylistDetailViewModel` — states and rules

File: `ui/detail/PlaylistDetailViewModel.kt`.

### 3.1 Published state

```
HeaderState = Loading | Success(header) | Error(message) | ContentUnavailable       // :589-595
PaginatedState<T> = Idle | LoadingInitial | Loaded(items, nextPage, isAppending)
                  | Empty | ErrorInitial(message) | ErrorAppend(message, items, nextPage)  // :597-612
searchQuery: StateFlow<String>                                                       // :65-66
downloadUiState: StateFlow<PlaylistDownloadUiState>                                  // :75-76  (Phase 3)
uiEvents: SharedFlow<PlaylistUiEvent>  // one-shot                                   // :78-80
```

### 3.2 Header load sequence

`init { loadHeader(); observeDownloads() }` (`:98-101`). `loadHeader(forceRefresh = false)` (`:106-165`):

1. Emit `Loading`.
2. **Availability gate**: `contentService.verifyAvailable(PLAYLIST, id)` — backend check before any
   NewPipe work; **fail-open on transport errors** (`:112-119`); `false` → `ContentUnavailable`,
   stop (`:120-124`).
3. `repository.getHeader(...)` → `Success(header)` (`:126-133`).
4. **Channel linkability resolved asynchronously, fail-closed** (`:136-155`, `:191-247`):
   canonicalize channel id → `contentService.isInApprovedRegistry(CHANNEL, ucId)` → only on
   approval, copy the header with `isChannelLinkable = true` + the canonical id, and only if the
   header is still the identical instance (`===` guard, `:238-246`). Any failure keeps the channel
   name hidden. A newer `loadHeader` cancels the in-flight gate (`:87-91`, `:147`).
5. `loadInitial()` is auto-called after header success (`:157-158`).
6. Any exception → `HeaderState.Error("Failed to load playlist: ${e.message}")` (`:159-163`).

### 3.3 Items pagination

- `loadInitial()` (`:260-313`): sync fast-exit if already `ContentUnavailable` (`:262`) or already
  loading (`:265-268`); then an **async re-check** of `verifyAvailable` (retry-path race,
  fail-open) (`:270-283`); resets `nextPage = null`, `nextItemOffset = 1`,
  `hasReachedEnd = false`; emits `LoadingInitial`; fetches with `itemOffset = 1`; empty page →
  `Empty`, else `Loaded` (`:285-311`). Failure → `ErrorInitial("Failed to load items: …")` (`:305-308`).
- `loadNextPage()` (`:318-376`) guards, in order: `isAppending` (`:320`), `hasReachedEnd` (`:324`),
  `nextPage == null` (`:328`), **rate limit `MIN_APPEND_INTERVAL_MS = 1000`** between append
  requests (`:333-338`, `:672`). Emits `Loaded(isAppending = true)` first (`:348`), appends, then
  `Loaded(newItems, nextPage)`. Failure → `ErrorAppend(message, existingItems, nextPage)` —
  items and cursor preserved (`:362-371`).
- Scroll trigger: fragment forwards every scroll to `onListScrolled(lastVisible, totalCount)`
  (`PlaylistDetailFragment.kt:281-288`); VM fires `loadNextPage()` when
  `totalCount - lastVisibleItem <= PAGINATION_THRESHOLD (5)` (`PlaylistDetailViewModel.kt:395-399`,
  `:673`). Note: unlike the Phase-1 tab screens there is **no `dy > 0` check and no autofill
  helper** — the threshold + rate-limit + guards are the only brakes.
- `retryInitial()` = `loadInitial()`; `retryAppend()` = `loadNextPage()` (`:381-390`). The retry
  button calls **both** `loadHeader(forceRefresh = true)` and `retryInitial()` (`PlaylistDetailFragment.kt:633-637`).

### 3.4 Play All / Shuffle

- `onPlayAllClicked(startIndex = 0)` emits `NavigateToPlayer(startIndex, shuffled = false)`
  (`:404-408`); `onShuffleClicked()` emits `NavigateToPlayer(0, shuffled = true)` (`:413-417`).
  Emission is unconditional — no check that items loaded; the player resolves the playlist itself.
- Fragment handles the event by navigating to the **global player** with bundle
  `{targetVideoId?: String, playlistId: String, startIndex: Int, shuffled: Bool}`
  (`PlaylistDetailFragment.kt:474-478`, `:745-754`). Play All also prefetches the first item's
  stream first (`:294-298`, `:737-743`); Shuffle deliberately doesn't ("can't prefetch shuffle
  since we don't know the order", `:300-303`).
- Row tap → same navigation with `targetVideoId = item.videoId` + `startIndex = adapterPosition`,
  after `prefetchService.triggerPrefetch(videoId)` (`:269-273`). Comment: targetVideoId is the
  authoritative identifier, startIndex an optimization hint (`:747`).

### 3.5 Search — client-side only

- Field debounce `SEARCH_DEBOUNCE_MS = 300` via Handler (`PlaylistDetailFragment.kt:202-213`, `:786`);
  IME Search action applies immediately (`:215-221`); clear button cancels timer, clears field,
  sets `""` (`:223-227`). VM `setSearchQuery` just sets the StateFlow — **no VM-side debounce and
  no refetch** (`PlaylistDetailViewModel.kt:70-72`).
- Filtering happens in the fragment by `combine(itemsState, searchQuery)`: case-insensitive
  (`Locale.ROOT`) substring match on `title` OR `channelName` (`PlaylistDetailFragment.kt:326-346`).
- Filtered `Loaded` gets **`nextPage = null`** (`:336`) so the near-end trigger can't paginate
  mid-search; zero matches → `PaginatedState.Empty` view (`:335`). `ErrorAppend` is filtered too
  but keeps its cursor (`:338-344`).
- Search covers **only pages already fetched** — items not yet paginated in are invisible to search.

### 3.6 Save (favorites integration — Phase 1 store)

- Saved state observed from `subscriptions.isPlaylistSaved(playlistId)`; drives label
  `playlist_save` → **"Save"** / `playlist_unsave` → **"Saved"** (`strings.xml:180-181`),
  icon `ic_favorite_border` ↔ `ic_favorite`, `isSelected` (`PlaylistDetailFragment.kt:140-158`).
- Toggle (`:160-196`): refuses malformed ids (`^[A-Za-z0-9_-]{3,128}$`, `:787`); captures direction
  at click time, disables the button during IO, re-enables in `finally`; saves
  `SavedPlaylist(playlistId, playlistUrl = "https://www.youtube.com/playlist?list=$id", name,
  thumbnailUrl, uploaderName)`; failures logged only, no user-visible error.

### 3.7 Downloads (record only — **Phase 3 on iOS**)

Download button honours `downloadPolicy`: ENABLED → label "Download" active unless `excluded`;
QUEUED → "Download queued" disabled; DISABLED → "Download unavailable" disabled
(`PlaylistDetailFragment.kt:668-684`, `strings.xml:40-42`). Disabled look = alpha 0.4
(`:686-691`). Tap → quality dialog (Audio Only/144p/360p/720p/1080p/4K, default 360p —
`PlaylistDetailViewModel.kt:691-702`, `:431`) → VM paginates the whole playlist (cap **500 items**,
500 ms between pages — `:670-675`) → enqueues with request id scheme
`"playlistId|qualityLabel|videoId"` (`PlaylistDetailFragment.kt:651-656`). Per-item download badges in
the list (`PlaylistVideosAdapter.kt:110-161`). Aggregate button state and per-quality grouping:
`PlaylistDetailViewModel.kt:523-585`.

## 4. Screen behaviour and view-state mapping

### 4.1 Structure

CoordinatorLayout: AppBarLayout ▸ CollapsingToolbarLayout (`scroll|exitUntilCollapsed`,
`titleEnabled=false`, contentScrim `?colorSurface`) containing the header content + a pinned
MaterialToolbar; below, a content FrameLayout with search bar + RecyclerView, list skeleton,
empty state, error state (`fragment_playlist_detail.xml:3-531`). The header **collapses with
parallax 0.5** (`:36-37`).

### 4.2 Toolbar

- Back arrow `ic_arrow_back` → `navigateUp()` (`PlaylistDetailFragment.kt:232-237`).
- Kebab menu `menu_detail_kebab` = Share (`action_share` → "Share", `strings.xml:281`) + Report
  (`action_report` → "Report", `strings.xml:621`) with icons forced visible
  (`res/menu/menu_detail_kebab.xml:5-15`, `PlaylistDetailFragment.kt:233-250`).
- Title = `playlistTitleArg ?: app_name` immediately (`:251`), replaced by the loaded header title
  (`:496`). Toolbar background is a scrim gradient; icon/title colour is **white while expanded,
  `?colorOnSurface` when collapsed**, driven by an offset listener
  (`:253-264`, `fragment_playlist_detail.xml:406-418`).
- Share composes: title + "Open this playlist in FitrahTube:" (`strings.xml:284`) + share URL +
  "Get FitrahTube for ad-free Islamic content!" (`:285`), chooser title "Share playlist" (`:288`);
  publishes share metadata first (`PlaylistDetailFragment.kt:580-617`; URL scheme
  `share/ShareLinks.kt:40-54`). Report opens `ContentReportBottomSheet(PLAYLIST, id)` (`:619-623`).

### 4.3 Hero (header content, top→bottom)

`fragment_playlist_detail.xml:39-402`:

1. **Exclusion banner** — `accent_red` #D32F2F bg, white text, `detail_excluded_banner` = "This
   item is currently unavailable due to policy restrictions." (`strings.xml:44`), padding
   `spacing_sm`; visible iff `header.excluded` (`PlaylistDetailFragment.kt:575-577`).
2. **Hero block**, height `playlist_hero_height` **200 (280 / 320) dp**: blurred full-bleed
   background copy of the thumbnail at alpha 0.6 (Coil `size(320,180)` + `RenderEffect` blur 30 px
   on API 31+ — `PlaylistDetailFragment.kt:505-518`), then `hero_overlay` #40000000 colour wash,
   a bottom-up gradient, a top scrim (`banner_gradient_top_height` 80/80/96 dp), and a centred
   sharp 16:9 `MaterialCardView` thumbnail — width **75 % / 60 % / 50 %** of the container capped
   at `playlist_hero_thumbnail_max_width` **320 / 480 / 560 dp**, corner radius 12
   (`home_thumbnail_corner_radius`), elevation 2, margin `spacing_lg` (phone/sw600) / `spacing_xl`
   (sw720) (`fragment_playlist_detail.xml:53-129`; sw-diffs; dimens
   `values/dimens.xml:185-186`, `values-sw600dp/dimens.xml:94-95`, `values-sw720dp/dimens.xml:95-96`).
   Fallback image: `thumbnailUrl ?: bannerUrl`, placeholder `thumbnail_placeholder` (`PlaylistDetailFragment.kt:502-525`).
3. **Info block**, horizontal padding `playlist_header_horizontal_margin` **16 / 32 / 48 dp**,
   vertical `spacing_lg` (sw720: `spacing_xl`):
   - Title: `text_headline` **20sp (24sp on sw720)** bold, maxLines 2, ellipsize end
     (`fragment_playlist_detail.xml:150-164`).
   - Channel name: `text_body` 14sp (sw720: `text_subtitle`), `primary_green` #275E4B, ripple,
     hidden unless `isChannelLinkable && channelName != nil && channelId != nil`; tap →
     channel detail with `{channelId, channelName}` (`:166-183`,
     `PlaylistDetailFragment.kt:536-547`, `:756-762`).
   - Metadata: `playlist_metadata_duration_format` "%1$d videos • %2$s" when a total duration
     exists, else `playlist_metadata_format` "%1$d videos" (`strings.xml:436-437`,
     `PlaylistDetailFragment.kt:549-556`; duration formatted "Xh Ym"/"Ym" `:764-771` —
     currently always the count-only variant, §2.1).
   - Category chip: single non-clickable chip, bg `surface_variant` #E3E9E7, text
     `primary_green`; shown iff `header.category` non-empty (`:558-573`) — i.e. only when the
     caller passed `playlistCategory`.
4. **Action bar** — 4 equal-weight icon+caption cells (icon `icon_small` 24 dp — sw720
   `icon_medium` 40 dp; caption `text_caption` 12sp — sw720 `text_body`), all tinted/coloured
   `primary_green`: **Play all** (`playlist_play_all`, `strings.xml:434`), **Shuffle** (`:435`),
   **Download** (Phase 3), **Save/Saved** (`fragment_playlist_detail.xml:220-386`).
5. Download policy hint text `playlist_detail_download_hint` (`:388-400`, `strings.xml:43`) — Phase 3.

### 4.4 View-state → UI

Header (`PlaylistDetailFragment.kt:369-403`):

| HeaderState | headerSkeleton | headerContent | other |
|---|---|---|---|
| Loading | visible | hidden | — |
| Success | hidden | visible, bind | — |
| Error | hidden | hidden | full error state with message + Retry |
| ContentUnavailable | hidden | hidden | error state, headline `content_unavailable_title` "Content not available", body `content_unavailable_message` (`strings.xml:207-208`), **Retry hidden** |

Header skeleton = hero-height block + title 24 dp (70 % width) + 150×16 + 100×16 lines + a
`touch_target_button`-height action-row block, all `skeleton_shimmer` (static fill), overlaying the
app bar with elevation `elevation_lg` 8 dp (`fragment_playlist_detail.xml:533-604`).
Defect: the **sw720 layout ships `headerSkeleton` visible by default** (`layout-sw720dp` diff vs
sw600 at the skeleton root: `android:visibility="visible"`) while phone/sw600 default to gone;
code sets it on every state so this only matters pre-first-emission.

Items (`PlaylistDetailFragment.kt:405-449`):

| PaginatedState | skeleton | list | empty | error | extra |
|---|---|---|---|---|---|
| Idle | — | — | — | — | nothing |
| LoadingInitial | visible | hidden | hidden | hidden | list skeleton = **5** `skeleton_content_item` rows (6 on sw720) (`fragment_playlist_detail.xml:499-513`) |
| Loaded | hidden | visible | hidden | hidden | submit items |
| Empty | hidden | hidden | visible | hidden | |
| ErrorInitial | hidden | hidden | hidden | visible (message + Retry) | |
| ErrorAppend | — | keep items | — | — | `Toast(message)` LENGTH_SHORT (`:441-446`) |

- Empty state layout is the shared `empty_state.xml` (body-anchored, icon `icon_xlarge`, headline
  20sp bold, hidden CTA button — `res/layout/empty_state.xml:11-77`). **The fragment never sets
  icon/headline/body**, so it renders the generic headline `empty_state_generic_headline`
  "No content yet" (`strings.xml:819`) with a blank body and blank icon — defect: unconfigured
  empty state (both for a genuinely empty playlist and for zero search matches).
- Error state layout `error_state.xml`: icon tint `accent_red`, headline
  `error_state_generic_headline` "Something went wrong" (`strings.xml:820`), body = raw error
  message, Retry button `retry` = "Retry" (`strings.xml:194`) (`res/layout/error_state.xml:9-71`;
  wiring `PlaylistDetailFragment.kt:629-639`).
- **No pull-to-refresh** on this screen; recovery is the Retry button (which force-refreshes the
  header) only.
- `PaginatedState.Loaded.isAppending` is set by the VM (`PlaylistDetailViewModel.kt:348`) but the
  fragment renders **no pagination spinner** — defect/omission: appending is visually silent.

### 4.5 List + search bar chrome

- Search bar identical to the tab screens: `OutlinedBox.Dense`, hint `search_hint` "Search…"
  (`strings.xml:571`), leading `ic_search`, clear button `icon_small` 24 dp with
  `search_clear` "Clear search" (`fragment_playlist_detail.xml:439-482`).
- RecyclerView: **`LinearLayoutManager` — single column at every width class**
  (`PlaylistDetailFragment.kt:277`); `clipToPadding=false`,
  `paddingBottom = bottom_nav_height` (72 dp phone / 0 dp sw600+) (`fragment_playlist_detail.xml:485-494`).
- sw600/sw720: the whole content area (search + list) is wrapped in a container padded by
  `playlist_header_horizontal_margin` 32/48 dp and centred (`layout-sw600dp` diff, contentContainer
  block) — no max-width cap, no grid.
- Predictive prefetch controller attached when the feature flag is on
  (`PlaylistDetailFragment.kt:126-133`).

## 5. `item_playlist_video.xml` — row cell

`res/layout/item_playlist_video.xml` (no sw600/sw720 variant):

- Root ConstraintLayout, padding `spacing_md`, ripple, whole-row tap target (`:6-11`).
- **Position number column**: width `list_position_width` **32 dp**, centred vertically against the
  thumbnail, `text_body` 14sp, `?colorOnSurfaceVariant` (`:14-25`); text = 1-based
  `item.position` (`PlaylistVideosAdapter.kt:58`).
- Thumbnail card: width `video_list_thumbnail_width` **140 dp**, ratio 16:9, radius 12, elevation 0,
  `marginStart spacing_sm` after the number (`:28-38`); centerCrop, bg `surface_variant`,
  placeholder `thumbnail_placeholder` (`:45-53`, `PlaylistVideosAdapter.kt:82-86`).
- Duration chip bottom-end of thumbnail: `duration_badge_background` (#CC000000, r=4), padding 6h/3v,
  `text_duration` 11sp bold white (`:56-70`); hidden when duration is nil or 0
  (`PlaylistVideosAdapter.kt:64-65`). Format `Locale.US` `h>0 ? "%d:%02d:%02d" : "%d:%02d"`
  (`:163-172`) — same as Phase 1 §5.6.
- Download indicator overlay bottom-start (badge icon `badge_icon_size` 20/24/28 dp; circular
  progress 16 dp) — Phase 3 (`:72-108`).
- Title: `marginStart spacing_md`, `text_body` 14sp **bold**, maxLines 2, ellipsize end (`:115-129`).
- Meta line: `text_caption` 12sp, secondary colour, maxLines 1 (`:132-145`). Composition
  (`PlaylistVideosAdapter.kt:67-79`): `channel • views`, either alone if the other is missing, empty
  if both missing. Views = `CountFormat.compact(viewCount, appLocale)` into `video_views_format`
  "%s views" (`strings.xml:389`) — note this is the **plain string, not the plural** used elsewhere.
- Download status badge text row — Phase 3 (`:148-164`, `strings.xml:455-457`).
- A11y: row `contentDescription` = `a11y_playlist_video` "Position %1$d, %2$s, Duration: %3$s, %4$s"
  (`strings.xml:454`, `PlaylistVideosAdapter.kt:99-107`).
- Diffing: identity by `videoId`, contents by whole `PlaylistVideoUiItem` (`:193-204`).
- Click delivers `(item, bindingAdapterPosition)` with `NO_POSITION` guard (`:91-97`).

---

# PART B — SHORTS

## 6. Entry points and arguments

Nav destination `shortsPlayerFragment` (`res/navigation/main_tabs_nav.xml:265-303`): args
`initialShortId: String?`, `channelId: String?`, `initialShortTitle: String?`,
`initialChannelName: String?`, `initialThumbnailUrl: String?`, `initialChannelAvatarUrl: String?`,
`initialDurationSeconds: Int = 0`; deep link `albunyaantube://shorts/{initialShortId}`.
Global action `action_global_shortsPlayerFragment` (`:318-321`).

Callers:

- **Channel detail → Shorts tab** grid cell (`ChannelShortsTabFragment.kt:86-104`): passes all
  args + `contentSubType = "SHORT"` (routes a Report to the channel's shorts exclusion bucket —
  comment `:97-100`). The grid itself is `channel_shorts_span_count` **2 / 4 / 5** columns of 9:16
  cards (`values/dimens.xml:200`, `values-sw600dp/dimens.xml:86`, `values-sw720dp/dimens.xml:105`;
  cell `item_channel_short.xml:19-32`) — full grid contract belongs to the channel-detail brief.
- **Me tab** feed rows where `video.isShort` (`MeFragment.kt:612-628`) — omits blank
  thumbnail/avatar rather than passing "".
- **Deep link** — the only path where `channelId` is null → global-feed mode.

There is **no bottom-tab / home entry** for a standalone shorts feed.

## 7. Data — `ShortsItem` and `ShortsFeedRepository`

### 7.1 Model

`ShortsItem(id, title, channelId, channelName, channelAvatarUrl, thumbnailUrl, durationSeconds)`
(`data/shorts/ShortsItem.kt:3-10`); `ShortsPage(items, nextCursor)` (`:32`). `canonicalShareUrl`
(`:23-25`) has **no callers** — sharing goes through `ShareLinks.video` instead (§9.4); dead code.

### 7.2 Feeds (`data/shorts/ShortsFeedRepository.kt`, @Singleton)

- **Global feed** `loadFeedPage(cursor, pageSize = 10)`: `contentService.fetchContent(VIDEOS,
  cursor, pageSize, FilterState(videoLength = UNDER_FOUR_MIN))` — i.e. the Phase-1
  `/api/v1/content` endpoint with `type=VIDEOS&length=SHORT` (`:47-62`; enum→param mapping per
  Phase-1 brief §1.1). Items map with **blank `channelId`/`channelName` and nil avatar** (`:53-56`)
  — in global-feed mode the channel row never populates (§9.2).
- **Channel feed** `loadChannelShortsPage(channelId, cursor, pageSize = 10)`:
  `channelDetailRepository.getShorts(channelId, page)` (`:80-113`). Cursors are synthetic UUID
  tokens mapped to NewPipe `Page` objects in a 32-entry access-order LRU (`:39-45`, `:133-142`);
  an evicted/unknown token silently **restarts from page 1** and relies on DiffUtil id-dedupe
  (`:69-93`). Channel metadata is left blank per page and decorated by the VM (§8.2).
- `DEFAULT_PAGE_SIZE = 10` (`:134`).

## 8. `ShortsPlayerViewModel`

File: `ui/shorts/ShortsPlayerViewModel.kt`. Assisted-injected with all 7 nav args (`:44-60`).

### 8.1 Player ownership

The **ExoPlayer lives in the ViewModel** (survives rotation; released once in `onCleared`
`:313-316`). Built lazily with: legacy-subtitle renderers factory + decoder fallback,
QualityTrackSelector (never-freeze variant behind a flag), a cellular network quality ceiling,
`AdaptiveBufferPolicy` load control, `handleAudioBecomingNoisy`, `WAKE_MODE_NETWORK` (`:82-104`).
Quality cap API: `applyQualityCap(height)` — 0 = AUTO (network ceiling re-applied, not cleared to
max), else CAP-mode constraint (`:115-131`); ladder for the picker is hard-coded
2160→144 (`:149-158`).

### 8.2 Feed state

- `items: StateFlow<List<ShortsItem>>` (`:162-163`); one-shot `events: SharedFlow<LoadEvent>`
  (buffer 4, DROP_OLDEST) with `SkipCurrent(shortId)` / `LoadError(message)` (`:165-169`, `:333-336`).
- `init`: **seed the initial short synchronously** from nav args (only if id AND title non-blank —
  `:296-311`), then `loadNextPage()` (`:183-186`).
- `loadNextPage()` (`:219-267`): `Mutex.tryLock` serializes (second caller drops); channel mode vs
  global mode by `channelId != null`; items decorated with the channel header when available;
  `appendDistinct` de-dupes by id (`:327-331`); **initial-short reordering** — until the seeded id
  is found in a fetched page, the matching item is moved to index 0 (`:241-252`);
  `exhausted = nextCursor == null`; failures (non-cancellation) emit `LoadError` (`:256-262`).
- Prefetch trigger: `onPageChanged(index)` loads more when `index >= items.size - 3`
  (`PREFETCH_THRESHOLD = 3`, `:188-192`, `:352`).
- Channel header decoration (`:269-294`): fetched once, cached; on late success it retroactively
  fills any items with blank channel fields.
- `toggleLike(index)` → `favorites.toggleFavorite(id, title, channelName, thumbnailUrl,
  durationSeconds)` — the Phase-1 favorites store (`:194-205`); `isLikedFlow(videoId)` (`:207`).
- `onPlaybackError(index)` emits `SkipCurrent` for the item's id (`:214-217`).

## 9. `ShortsPlayerFragment` — the player screen

File: `ui/shorts/ShortsPlayerFragment.kt`.

### 9.1 Pager

- Vertical `ViewPager2`, `offscreenPageLimit = 1`, adapter = `ShortsPagerAdapter`
  (`:293-321`). **`isUserInputEnabled = false`** — swiping is deliberately disabled
  ("product decision to NOT contribute to doom-scrolling… tap a Short, press back, tap another");
  the pager is retained for programmatic `setCurrentItem` (`:322-329`). Programmatic advance
  happens only on `SkipCurrent` (`:424-433`).
- Page selection → `viewModel.onPageChanged` + `bindPageWhenReady(position, id, channelId)`
  (`:331-339`). First non-empty submit binds page 0 once (`:403-415`). `bindPageWhenReady`
  retries via `post` with three staleness guards (pager moved, item replaced, view gone)
  (`:552-584`).
- `SkipCurrent` on the **last** item → toast `shorts_error_unavailable` "Couldn't play this short.
  Skipping…" (`strings.xml:647`, `:428-432`). `LoadError` → toast `shorts_error_feed_empty`
  "No shorts available" (`strings.xml:648`, `:434-436`) — note the copy is wrong for transport
  errors (any load failure reads as an empty feed); defect: misleading error copy.

### 9.2 Per-page overlay (`item_shorts_page.xml`)

Full-screen black FrameLayout per page:

- `PlayerView`: `resize_mode="zoom"` (fill, crop), `use_controller="false"`, texture view (`:10-16`).
- Full-screen invisible tap target → toggle play/pause (`:18-21`; handler
  `ShortsPlayerFragment.kt:300-307`).
- Centre **play/pause flash indicator**: 112×112 dp, padded circle bg; on resume shows pause glyph
  then fades after **600 ms** over **250 ms**; on pause the play glyph stays visible persistently
  (`item_shorts_page.xml:23-34`, `ShortsPageViewHolder.kt:124-141`, `:151-154`).
- **Action rail**, gravity end|bottom, buttons 56×56 dp spaced `spacing_md`, white tint,
  `marginBottom = shorts_action_rail_bottom_margin` **176 / 112 / 128 dp** (phone/sw600/sw720 —
  `values/dimens.xml:40`, `values-sw600dp/dimens.xml:15`, `values-sw720dp/dimens.xml:12`),
  top→bottom (`item_shorts_page.xml:36-102`):
  1. Like — heart toggles `ic_shorts_like` ↔ `_filled` from the favorites flow
     (`ShortsPageViewHolder.kt:62-64`); cd `shorts_like_cd` "Like".
  2. Share — cd `shorts_share_cd` "Share".
  3. Audio language (globe) — **gone unless ≥ 2 audio languages** resolve (`:89-94`,
     `ShortsPagerAdapter.kt:109-116`); cd `shorts_audio_track_cd` "Audio language".
  4. CC — gone unless subtitle tracks exist (`:96-97`, adapter `:118-125`); cd
     `subtitle_picker_cd` "Subtitles / CC".
  5. Download — always visible; **Phase 3** (`item_shorts_page.xml:92-101`).
  - sw600/sw720 insert an extra **Report** button (`shortReportBtn`) between Share and the globe
    (`layout-sw600dp/item_shorts_page.xml:69-79`) — defect: **no code references `shortReportBtn`**
    (grep), so on tablets it renders but does nothing.
- **Time bar**: `DefaultTimeBar` full width, height 24 dp, bar 3 dp bottom-gravity,
  `marginBottom = bottom_nav_height` (72 dp phone / 0 sw600+), scrubber hidden at rest
  (enabled/disabled size 0) and 16 dp while dragged, played/scrubber `primary_green`, unplayed
  #4DFFFFFF, buffered #80FFFFFF (`item_shorts_page.xml:104-118`). Driven by a fragment-side ticker
  every **250 ms** targeting the current page (`ShortsPlayerFragment.kt:857-879`, `:1010`); scrub
  preview does nothing until release, seek fires on `onScrubStop` unless cancelled (`:841-853`).
- **Bottom overlay** (scrim gradient, padding `spacing_md`, same `bottom_nav_height` margin):
  channel avatar 36 dp circle + handle text (bold white) — both hidden when `channelName` is blank
  ("feed mode often lacks channelName… Subscribe UX lives on the channel detail screen" —
  `ShortsPageViewHolder.kt:44-60`); handle string `shorts_channel_handle` "@%1$s"
  (`strings.xml:659`; Arabic prepends LRM `values-ar/strings.xml:641`); title below, maxLines 2,
  white with shadow (`item_shorts_page.xml:120-170`). Avatar/handle tap → channel detail with
  `{channelId, channelName, channelAvatarUrl}`, no-op when channelId blank
  (`ShortsPlayerFragment.kt:631-642`).

### 9.3 Screen chrome (`fragment_shorts_player.xml`)

- Root black, `fitsSystemWindows=false`. Back button 48 dp top|start and kebab 48 dp top|end,
  white, margins `spacing_md` top / `spacing_sm` side (`:24-46`). Kebab cd is hardcoded
  "More options" (`:44`) — defect: not a string resource.
- Kebab popup = `menu_shorts_kebab`: **Quality** (`player_action_quality` "Quality") + **Report**
  (`report_content` "Report") (`res/menu/menu_shorts_kebab.xml:5-15`,
  `ShortsPlayerFragment.kt:765-787`). Quality → shared `QualityPickerDialog` with the VM ladder and
  current cap pre-checked (`:825-831`); result applies `applyQualityCap` — pure track selection, no
  source rebuild (`:349-371`). Report → `ContentReportBottomSheet(VIDEO, videoId,
  parentType = CHANNEL when channelId present, parentId = channelId, contentSubType = SHORT)`;
  "video not ready" toast when no id (`:795-817`).
- **sw600/sw720**: the pager is wrapped in a centred ConstraintLayout box constrained to
  **9:16 (`W,9:16`)** on a black stage — pillar-boxed portrait player on tablets
  (`layout-sw600dp/fragment_shorts_player.xml` diff, lines 12-29). Phone is full-bleed.
- `shortsErrorContainer` FrameLayout exists but nothing ever populates it (`:18-22`) — dead view.

### 9.4 Actions

- **Share** (`:594-629`): `ShareLinks.video(id, …)` → app watch URL with deep-link fallback
  `albunyaantube://video/{id}` (`share/ShareLinks.kt:8-22`); message = title + "Watch in
  FitrahTube:" (`strings.xml:282`) + URL + "Get FitrahTube for ad-free Islamic content!" (`:285`);
  publishes share metadata first.
- **Download** (Phase 3): requires resolved streams else toast `shorts_download_preparing`
  "Preparing, tap again in a moment" (`strings.xml:650`); `DownloadQualityDialog` → enqueue with id
  `"<videoId>_<timestamp>"`, toast `download_started` (`:743-753`, `:380-401`).
- **Audio language picker** (`:711-724`, dialog result `:471-521`): options labelled
  `shorts_audio_track_original_prefix` "Original: %1$s" for the original track; picking a lazy
  web-dub resolves the URL off-main then swaps audio without tearing the player down; stale
  results dropped by a latest-pick-wins map; failure → `player_stream_error` toast.
- **Subtitle picker** (`:731-741`, result `:525-540`): Off = disable text track type; else set
  preferred text language.

### 9.5 Lifecycle / system UI

- `onResume`: hide system bars (immersive) + **lock portrait**, saving the previous orientation
  (`:886-903`). `onPause`: restore orientation policy (`:905-914`). `onStop`: pause playback,
  remember `wasPlayingBeforeStop`, restore bars (`:916-928`); `onStart`: auto-resume iff it was
  playing (`:930-938`). Bars restored in `onDestroyView`/`onStop`, deliberately not `onPause`,
  to avoid flicker on transient dialogs (`:62-65`, `:950-952`).
- The app's own bottom nav is **not** hidden for this destination (no destination-based hiding in
  `MainShellFragment.kt`; the 72 dp `bottom_nav_height` margins on the time bar and overlay exist
  precisely to clear it).
- `onDestroyView` (`:940-989`): unregister current MPD, remove stall listener from the VM-owned
  player, detach + cancel binder scope. Per-video option maps are intentionally **not** cleared
  (rotation would otherwise open empty pickers; `activeLanguageByVideoId` preserves the user's
  language selection).

### 9.6 Stall & error recovery

- **Stall watchdog**: entering `STATE_BUFFERING` snapshots the visible short id and schedules a
  **6 s** (`STALL_RECOVERY_MS`) runnable; if still buffering on the same short, unregister its MPD
  and `forceRefreshCurrent(expectedVideoId)` (`:180-226`, `:1011-1012`).
- **Hard player errors**: per-short budget `MAX_ERROR_RECOVERIES = 2` refresh attempts, then
  `onPlaybackError` → `SkipCurrent`; the budget resets when the failing short changes, deliberately
  **not** on READY (anti-flap) (`:163-268`, `:1014-1020`).
- Binder-level resolution failures also flow to `onPlaybackError` (`:445-452`).

## 10. `PlayerBinder` — bind/resolve contract

File: `ui/shorts/PlayerBinder.kt`.

- `bind(playerView, videoId, sourceChannelId)`: attach view, **stop + clearMediaItems** so the
  previous short never bleeds (`:305-315`), cancel the previous resolve, bump a generation counter;
  stale resolutions abort at three gates (`:47-55`, `:258-330`, `:412`, `:476-479`).
- Resolution: `playerRepository.resolveStreams(videoId, forceRefresh, sourceChannelId)`; if a
  cached progressive result looks URL-expired, one automatic fresh re-resolve (`:397-433`). Total
  failure → `failureEvents.tryEmit(videoId)` (`:435-438`).
- Source selection: lean `DashSourceBuilder` first (local multi-rep DASH/ABR); progressive
  fallback capped at **480p startup** (`SHORTS_FALLBACK_STARTUP_MAX_HEIGHT`, `:757-759`) over the
  cached/UA-correct transport (`:466-478`, `:504-548`).
- Apply: `setMediaSource` → **`repeatMode = REPEAT_MODE_ONE`** (each short loops) → `prepare` →
  `playWhenReady = true` (`:477-481`, `:154`).
- `resolvedEvents` feeds the fragment's audio-language/subtitle flows; `resolvedStreamsFor` cache
  holds the last **8** resolutions for the download picker (`:228-239`, `:332-345`, `:753`).
- Sticky audio language: first resolve pins ORIGINAL (or first) language so later re-resolves
  can't flip dubs mid-stall (`:444-460`).
- Captions: side-loaded style + a cue-rewrite listener that forces cues to the bottom with a
  reserved clearance `shorts_caption_bottom_clearance` **200 dp** above the nav/title band
  (`:160-195`; `values/dimens.xml:47`).
- Optional `MpdTtlWatcher` refreshes local-DASH URLs at 90 % TTL behind a feature flag (`:482-501`).
- Controls: `togglePlayPause` / `isPlaying` / `pause` / `resume` are plain `playWhenReady` flips
  (`:695-715`); `detach`/`cancelScope` for view teardown (`:717-751`).

## 11. Intentionally absent vs the regular player (recorded, with citations)

- Vertical swipe between shorts — disabled on purpose (`ShortsPlayerFragment.kt:322-329`).
- Subscribe button — "Subscribe is intentionally absent — that UX lives on the channel detail
  screen" (`ShortsPagerAdapter.kt:44-46`; unused strings `shorts_subscribe`/`shorts_subscribed`
  remain at `strings.xml:645-646`).
- Landscape — portrait-locked (`ShortsPlayerFragment.kt:895-902`).
- PlayerView controller chrome (`use_controller="false"`, `item_shorts_page.xml:15`) — no
  ±seek buttons, no fullscreen toggle, no queue; only the custom tap-toggle + scrub bar.
- Description panel, related items, comments — no views for them exist in `item_shorts_page.xml`.
- Kebab is Quality+Report only (`menu_shorts_kebab.xml`) vs the detail screens' Share+Report.

## 12. Behavioural checklist for the iOS implementer

**Playlist detail**
1. Args: id required; title/category/count/downloadPolicy/excluded optional with the defaults in §1.
2. Header via NewPipe with 30-min/100-entry cache; availability gate first (fail-open), backend-
   confirmed unavailable → terminal "Content not available" state with no retry.
3. Channel link hidden by default; shown only after canonical-id + registry approval resolve;
   stale resolutions must not overwrite a newer header.
4. Items: 1-based positions carried across pages via `nextItemOffset`; empty-but-nonzero-count
   pages re-extracted once.
5. Pagination: threshold 5 from end, 1 s minimum between appends, guards for
   appending/end/no-cursor; append failure keeps items+cursor and toasts.
6. Search: 300 ms debounce, client-side title/channel substring filter, pagination suppressed,
   zero matches → empty state.
7. Play all / shuffle / row tap → player with `{targetVideoId?, playlistId, startIndex, shuffled}`;
   prefetch first (play-all) / tapped (row) video.
8. Kebab: Share (app link + promo copy), Report (playlist target).
9. Save toggle persists `SavedPlaylist` locally, label Save/Saved, malformed ids refused.
10. Hero/collapse metrics per §4.3; single-column list at all width classes.

**Shorts**
11. Launch seeded with the tapped short; feed = channel shorts (channelId) or global
    `type=VIDEOS&length=SHORT` (no channelId), page size 10, prefetch 3 from end, id-dedupe,
    initial short forced to index 0.
12. No user swiping; auto-advance only when a short is skipped after failed recovery.
13. Each short loops (repeat-one), plays immediately after resolve, tap toggles pause with the
    112 dp flash indicator (600 ms/250 ms).
14. Overlay: rail Like/Share/(globe ≥2 langs)/(CC if subs)/(Download=Phase 3); channel
    avatar+@handle+title bottom-left, hidden without channel info; 3 dp scrub bar seek-on-release,
    250 ms tick.
15. Stall: 6 s buffering → refresh streams; hard error: 2 refreshes then skip; last-item skip →
    "Couldn't play this short" toast.
16. Immersive system bars + portrait lock while on screen; pause on background, resume iff it was
    playing.
17. Favorites-backed like state, live per-item.
18. Tablets: pillar-boxed 9:16 stage, rail margins per §9.2.

## 13. `keepScreenOn` asymmetry (defect)

The main player pins the screen awake: `android:keepScreenOn="true"` on the `PlayerView`
(`res/layout/fragment_player.xml:38`, recorded in player.md §3). Grep for `keepScreenOn` over
`item_shorts_page.xml`, `fragment_shorts_player.xml` (all three width-class variants:
`layout/`, `layout-sw600dp/`, `layout-sw720dp/`) and every file under `ui/shorts/` → **zero
hits**. Shorts pins nothing, so the display can time out and lock mid-loop on a Short even
though playback (audio, and video via the wake lock implied by ExoPlayer's own state) keeps
running underneath — the opposite of the intended "still watching" experience a looping short
should give. Neither §9 (this brief's own Shorts screen section) nor the Open Questions §Q7/§Q8
defect entries record this asymmetry; it is a plain omission, not a documented product
decision. iOS should not port the gap — the fix is to keep the display awake on the Shorts
screen the same way the main player does.

---

## Open questions

**Q1 — Playlist empty state is unconfigured.** `PaginatedState.Empty` shows the shared
`empty_state.xml` with generic headline "No content yet", blank body, blank icon
(`PlaylistDetailFragment.kt:427-433`, `empty_state.xml:19-58`) — for both an empty playlist and
zero search matches. Should iOS design proper copy (and a distinct search-no-results variant, as
the tab screens have)?

**Q2 — Append is visually silent.** The VM emits `Loaded(isAppending = true)`
(`PlaylistDetailViewModel.kt:348`) but the fragment renders no footer spinner (§4.4). Mirror the
silence or add the tab-style bottom spinner?

**Q3 — `video_views_format` vs plural.** Playlist rows use the plain "%s views" string
(`PlaylistVideosAdapter.kt:71`, `strings.xml:389`) while Phase 1 screens use the `video_views`
plural with the Arabic compact-count rule. Unify on the plural on iOS?

**Q4 — Playlist total duration is dead.** `totalDurationSeconds` is always nil
(`NewPipePlaylistDetailRepository.kt:329`) so "%1$d videos • %2$s" never renders. Drop the variant
on iOS, or compute a total from fetched items?

**Q5 — Shorts swipe-off is load-bearing product policy.** `isUserInputEnabled = false`
(`ShortsPlayerFragment.kt:329`). Confirm iOS must equally reject vertical paging gestures (i.e.
not a `TabView`/paging scroll view with gestures enabled).

**Q6 — Global-feed shorts have no channel attribution.** Feed mode leaves
channelId/channelName blank forever (`ShortsFeedRepository.kt:53-56`; overlay hides the row,
`ShortsPageViewHolder.kt:48-50`), and only the deep link reaches feed mode. Is feed mode in iOS
scope at all, or is shorts always channel/Me-launched?

**Q7 — defect: like toggle hides the globe/CC buttons.** The liked-flow re-bind calls
`bindItem(item, liked, hasMultipleAudioTracks = false)` (`ShortsPagerAdapter.kt:99-107`) which
resets both rail buttons to gone (`ShortsPageViewHolder.kt:92-97`); the count StateFlows don't
re-emit an unchanged count, so the buttons stay hidden until the next stream resolution.
Fix on iOS (drive visibility solely from the count publishers)?

**Q8 — defect: dead tablet Report button.** sw600/sw720 `item_shorts_page.xml` adds
`shortReportBtn` (`layout-sw600dp/item_shorts_page.xml:69-79`) that no code wires (grep: zero
Kotlin references). Include a working rail Report on iPad, or keep Report kebab-only everywhere?

**Q9 — LoadError copy.** Any shorts feed failure toasts "No shorts available"
(`ShortsPlayerFragment.kt:434-436`, `strings.xml:648`), conflating network errors with an empty
feed. Keep for parity or split the copy?

**Q10 — Playlist pagination has no upward-scroll guard and no autofill.** Unlike the tabs
(Phase 1 §4.3), `onListScrolled` fires on any scroll (`PlaylistDetailFragment.kt:281-288`) and
there is no fits-on-screen autofill — on a tall iPad a short first page may never trigger page 2
without scrolling. Adopt the Phase-1 guarded autofill here?

**Q11 — Shorts rail Download button (Phase 3) placement.** The button is always visible on
Android (`item_shorts_page.xml:92-101`) even though downloads are Phase 3 on iOS. Hide it until
Phase 3, or ship disabled?

**Q12 — Bottom nav behind the shorts player.** Android keeps the shell bottom nav visible on
phones (margins sized to clear it, §9.5) while hiding the *system* bars. Should iOS keep its tab
bar visible on the shorts screen (Android parity) or go truly full-screen?
