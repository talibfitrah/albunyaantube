# Phase 2 research — Channel Detail

Scope: `ChannelDetailFragment` + `ChannelDetailViewModel`, the five tab fragments
(Videos / Live / Shorts / Playlists / About), `NewPipeChannelDetailRepository`, adapters,
layouts for all width classes, and every navigation entry point.
Every claim cites `android/app/src/main/**` file:line. Behavioural contract only — no Swift.

Style/precedent: Phase 1 corpus `docs/superpowers/plans/2026-08-23-ios-phase1-research/content-lists.md`
(shared cells `item_video_list` / `item_playlist`, `CountFormat`, empty/error patterns are specified there §5).

---

## 0. TL;DR for the implementer

1. **This screen does not use the backend for content.** Header and all four list tabs come
   straight from NewPipeExtractor (`ChannelDetailFragment.kt:56-57`,
   `NewPipeChannelDetailRepository.kt:44-45`). The backend is consulted only for an
   **availability gate** (`ChannelDetailViewModel.kt:135-147`) and never for items.
2. **Five fixed tabs, always all five**: VIDEOS, LIVE, SHORTS, PLAYLISTS, ABOUT
   (`ChannelDetailModels.kt:169-175`); no Posts/Community tab — NewPipe cannot extract it
   (`ChannelDetailFragment.kt:59-60`). Tabs are not hidden when empty; each shows its own
   empty state instead.
3. **Pagination is per-tab with a 1 s rate limit, opaque NewPipe `Page` cursors (not string
   cursors), a threshold of 5, and a hard autofill cap** (1 page on phone, 2 on ≥600 dp) after
   which a "Load more" footer button appears (`ChannelDetailViewModel.kt:1137-1143`,
   `BaseChannelListTabFragment.kt:399-415`). This is a different machine from the Phase 1 tabs.
4. **The Videos tab has a dual-path fetch** (channel-tab vs. UU uploads playlist) with
   cursor-family provenance tracking, a Room pre-paint cache, and client-side Shorts
   filtering (`ChannelDetailViewModel.kt:390-492`, `NewPipeChannelDetailRepository.kt:184-359`).
5. **In-header search is client-side only**: it filters already-loaded items per tab, disables
   pagination while active, and never issues a network request
   (`ChannelDetailViewModel.kt:92-100`, `BaseChannelListTabFragment.kt:269-294`).

---

## 1. Navigation into the screen

### 1.1 Destination + arguments

Nav graph `res/navigation/main_tabs_nav.xml:108-133`:

| Arg | Type | Default |
|---|---|---|
| `channelId` | string | required |
| `channelName` | string? | — |
| `channelAvatarUrl` | string? | `@null` |
| `excluded` | boolean | `false` |

Deep links on the destination (`main_tabs_nav.xml:126-132`):
`albunyaantube://channel/{channelId}`, `https://app.fitrahtube.com/channel/{channelId}`,
`https://app.fitrahtube.com/api/channel/{channelId}`.

Two routes: `action_channelsFragment_to_channelDetailFragment` (`:33-34`, used only by the
Channels tab) and global `action_global_channelDetailFragment` (`:308-309`, everyone else).

### 1.2 Every entry point (grep over `main/java`, exhaustive)

| Caller | Args passed | Citation |
|---|---|---|
| Channels tab row | id, name, avatarUrl, `excluded=false` | `ChannelsFragmentNew.kt:136-143` |
| Home channel card | id, name, avatarUrl | `HomeFragment.kt:138-145` |
| Featured list channel row | id, name, avatarUrl | `FeaturedListFragment.kt:93-101` |
| Search result channel | id, name, avatarUrl | `SearchFragment.kt:300-309` |
| Me tab (avatar on video card) | id, name (chip label), avatarUrl (chip image), `excluded=false` | `MeFragment.kt:701-712` |
| Shorts player (channel tap) | id, name, avatarUrl; no-ops if `channelId` blank | `ShortsPlayerFragment.kt:631-642` |
| Playlist detail (uploader name tap) | **id + name only** — no avatar | `PlaylistDetailFragment.kt:756-762`; gated by `header.isChannelLinkable` (`:530-547`) |

`excluded` is defaulted `false` by every caller; **no call site ever passes `true`**
(grep for `ARG_EXCLUDED to true` / `putBoolean("excluded", true` finds nothing). The exclusion
banner (§4.6) is therefore reachable only if a future caller sets it.

Arg name constants: `ChannelDetailFragment.kt:517-520`.

### 1.3 Argument use

- `channelName` seeds the toolbar title before the header loads: `title = channelName ?: channelId`
  (`ChannelDetailFragment.kt:147`); replaced by `header.title` on success (`:364`).
- `channelAvatarUrl` is the **fallback** avatar: repo avatar loads first; on image-load error the
  arg URL is retried (`ChannelDetailFragment.kt:455-488`). Blank arg is normalised to nil (`:77-79`).
- Malformed `channelId` guard for subscribe only: `^[A-Za-z0-9_-]{3,64}$`
  (`ChannelDetailFragment.kt:309-312,516`).

---

## 2. Data layer

### 2.1 Availability gate (the only backend involvement)

Before any NewPipe work, `HEAD` channel-availability via
`contentService.verifyAvailable(CHANNEL, channelId)` (`ChannelDetailViewModel.kt:137-147`).
Semantics (`RetrofitContentService.kt:106-121`): 2xx → available; **410 → blocked, hard stop**;
**404 → fail-open** (not in registry, NewPipe may resolve); other codes throw. Transport
exceptions **fail open** (`ChannelDetailViewModel.kt:139-142`) so offline users are not blocked.
Unavailable → `HeaderState.ContentUnavailable`, terminal, no retry (`:143-147`, `:1084-1085`).
The gate runs **twice** — once in `loadHeader` and once inside every `loadInitial`, because the
two race in parallel from init (`ChannelDetailViewModel.kt:204-221`).

### 2.2 Repository contract

`ChannelDetailRepository.kt:7-74` — `getChannelHeader(id, forceRefresh)`,
`getVideos(id, page)`, `getVideosViaChannelTab(id, page = null)`, `getLiveStreams`,
`getShorts`, `getPlaylists`, `getAbout` (= header, `NewPipeChannelDetailRepository.kt:409-412`).
Page type is a wrapper over NewPipe's `Page` (url/id/ids/cookies/ByteArray body) with
value-equality — an **opaque token, not a string cursor** (`ChannelDetailModels.kt:114-160`).
`ChannelPage<T> = (items, nextPage?, fromCache)` (`:102-106`).

### 2.3 Models

`ChannelDetailModels.kt`:

- `ChannelHeader(id, title, avatarUrl?, bannerUrl?, subscriberCount: Long?, shortDescription?,
  summaryLine?, fullDescription?, links: [ChannelLink], location?, joinedDate: Instant?,
  totalViews?, isVerified, tags)` (`:9-24`).
- `ChannelVideo(id, title, thumbnailUrl?, durationSeconds: Int?, viewCount: Long?,
  publishedTime: String?, uploaderName?)` (`:37-45`) — **`publishedTime` is NewPipe's textual
  date ("3 days ago"), not a day count** as on the Phase 1 tabs.
- `ChannelShort(id, title, thumbnailUrl?, viewCount?, durationSeconds?, publishedTime?)` (`:51-58`).
- `ChannelLiveStream(id, title, thumbnailUrl?, isLiveNow, isUpcoming, scheduledStartTime?,
  viewCount?, uploaderName?, durationSeconds?, publishedTime?)` (`:67-80`).
- `ChannelPlaylist(id, title, thumbnailUrl?, itemCount: Long?, description?, uploaderName?)` (`:85-92`).

### 2.4 Header fetch, caching, fallbacks

`NewPipeChannelDetailRepository.kt`:

- `ChannelInfo` in-memory cache: **LRU, max 100 channels, TTL 30 min**, mutex-guarded,
  single-flight dedup of concurrent fetches on a long-lived scope (`:78-116, 426-486, 893-896`).
  Stale entries evicted on read (`:434-443`).
- All NewPipe calls run at `Priority.VISIBLE_INTERACTIVE` and retry the internal rate-limiter
  timeout **2 attempts, delay 1000 ms × attempt** (`:591-616, 897-898`).
- Header mapping (`:715-758`): avatar/banner = largest image by height-then-width (`:833-844`);
  `subscriberCount` nil when `< 0`; `shortDescription` = description truncated to 200 chars
  with `"..."`; `links` = NewPipe `donationLinks` named by host sans `www.`;
  `summaryLine` = `parentChannelName` only; **`location`, `joinedDate`, `totalViews` are always
  nil** — NewPipe does not expose them (`:740-746`), so the About rows for them never render.
- **Header fallback**: if the channel page scrape fails and the id starts `UC`, the UU uploads
  playlist supplies a degraded header (title + avatar only; nil subscriberCount/banner/
  description, `isVerified=false`) (`:132-172`). If that also fails, the original error is thrown.

### 2.5 Videos dual path + cache

- `getVideos` pages the **UU uploads playlist** (`UU<id>`), ~100 items/page; UC-prefixed ids
  skip `getChannelInfo` entirely (fast path) (`:184-282`). Shorts are filtered client-side by
  NewPipe flag OR `/shorts/` URL — deliberately **not** the ≤180 s heuristic, to avoid hiding
  short legitimate videos (`:339-359`). Zero raw items + no continuation on the first page →
  `IOException` so the caller falls back (`:284-311`).
- `getVideosViaChannelTab` fetches the channel Videos tab (~30 items, smaller payload),
  filtering `isShortFormContent` (`:361-375`).
- Kept videos are **upserted into a Room cache** (`ChannelVideoCacheDao`) so a re-open can paint
  from disk (`:262-274`); tab items are also fed to a search index, failure tolerated (`:275-280`).
- Tab lookup uses a 4-strategy matcher (exact filter, case-insensitive, URL pattern, originalUrl)
  and returns an **empty page (no error)** when the channel simply lacks that tab (`:531-546, 634-671`).

---

## 3. `ChannelDetailViewModel` — state machines

File: `ui/detail/ChannelDetailViewModel.kt`. Scoped to the **parent fragment**; all five tab
fragments share the same instance via `ownerProducer = { requireParentFragment() }`
(`ChannelVideosTabFragment.kt:47-54` and peers). Assisted-injected with `channelId` (`:42-49, 1130-1133`).

### 3.1 Published state

```
HeaderState = Loading | Success(header) | Error(message) | ContentUnavailable   // :1080-1086

PaginatedState<T> =
  | Idle | LoadingInitial
  | Loaded(items, nextPage?, isAppending = false, showLoadMoreFooter = false)   // :1091-1100
  | Empty
  | ErrorInitial(message)
  | ErrorAppend(message, items, nextPage?, showLoadMoreFooter)                  // :1102-1108
```

Four independent tab flows (`videosState`, `liveState`, `shortsState`, `playlistsState`,
`:77-87`); `aboutState` **is** `headerState` (`:90`). Plus `searchQuery: StateFlow<String>`
(`:93-100`) and `selectedTab: StateFlow<Int>` (`:103-104`).

### 3.2 Per-tab private controller

`TabPaginationController(isInitialLoading, isAppending, nextPage, hasReachedEnd,
lastAppendRequestMs, videosUseChannelTab)` — one per tab (`:106-112, 1114-1128`).
`videosUseChannelTab` records which cursor family the Videos tab settled on; mixing families
silently breaks append (`:1120-1127`, `ChannelDetailRepository.kt:26-35`).

### 3.3 Lifecycle

- `init`: `loadHeader()` and `loadInitial(selectedTab)` fire **in parallel** (`:109-124`) —
  deliberately, to cut 300–600 ms off cold open.
- `loadHeader(forceRefresh)`: gate → `getChannelHeader` → `Success`, then `ensureTabLoaded`
  for the currently selected tab (`:129-164`). Failure → `Error("Failed to load channel: <msg>")`
  (`:158-162`).
- `setSelectedTab(pos)` stores the index and lazily loads that tab **only after** header
  Success (`:374-379`); `ensureTabLoaded` no-ops unless the tab is `Idle` (`:381-386`);
  tab fragments also call `loadInitial` from `onResume` when still Idle
  (`BaseChannelListTabFragment.kt:371-379`).
- `loadInitial(tab)`: refuses when `ContentUnavailable`; guard `isInitialLoading` (flag set
  *before* launch); resets `hasReachedEnd`/`nextPage`/`videosUseChannelTab`; re-runs the
  availability gate; emits `LoadingInitial`; dispatches per tab (`:179-242`). Errors →
  `degradeToErrorPreservingCachedItems`: if the tab is already `Loaded` (Videos cache
  pre-paint) convert to `ErrorAppend` keeping items, else `ErrorInitial` (`:989-1045`).
- `loadNextPage(tab) -> Bool` guards, in order: `isAppending`; restore `nextPage` from an
  `ErrorAppend` state (retry path, `:260-269`); `hasReachedEnd`; `nextPage == null`;
  **rate limit: ≥ 1000 ms since last accepted append** (`:283-289`, `MIN_APPEND_INTERVAL_MS`
  `:1137`). Returns whether the request was accepted — callers use it to schedule delayed
  re-checks.
- `onListScrolled(tab, lastVisible, total)`: paginate when `total - lastVisible <= 5`
  (`PAGINATION_THRESHOLD`, `:364-369, 1138`).

### 3.4 Videos initial load (the one complex path)

`loadVideosInitial` (`:390-492`), in order:

1. **Cache pre-paint**: Room-cached non-Short videos are emitted immediately as
   `Loaded(cached, nextPage=nil)` before any network (`:396-411, 1053-1062`); cached rows have
   `publishedTime = nil` (the cache stores millis, not the textual form — `:1064-1076`).
2. Fetch **channel-tab** path first (`getVideosViaChannelTab`); non-empty → Loaded with
   `videosUseChannelTab = true` (`:421-434`).
3. Channel-tab empty/failed → **UU playlist** page 1 fallback (`videosUseChannelTab = false`)
   (`:435-453`).
4. Both failed → throw (→ ErrorInitial, or ErrorAppend when cache painted) (`:456-459`);
   both empty but cache shown → keep cached state (`:461-466`); both empty, no cache → `Empty`
   (`:467-469`).

Videos append continues **on the same path** the initial settled on (`:527-531`), loops through
up to **5** empty-page continuations per append (`MAX_APPEND_EMPTY_PAGE_FETCHES`, `:521, 1143`),
and shows the Load-More footer when a continuation exists but the batch added nothing (`:554-558`).

### 3.5 Live / Shorts / Playlists loads

Identical machine per tab (`:582-638`, `:692-748`, `:802-858` initial; `:640-690`, `:750-800`,
`:860-910` append): initial fetches **one page** (`MAX_INITIAL_EMPTY_PAGE_FETCHES = 1`, `:1142`);
empty + no continuation → `Empty`; empty + continuation → `Loaded([], nextPage,
showLoadMoreFooter = true)`; append loops ≤ 5 empty continuations; append transitions through
`Loaded(items, isAppending: true)` first (`:656`, `:766`, `:876`). Append errors →
`ErrorAppend(message, items, nextPage, showLoadMoreFooter)` keeping everything (`:951-979`).
Telemetry (`recordChannelTabLoad`) wraps every Videos/Live/Shorts/Playlists initial load and
Videos append (`:479-490` etc.).

### 3.6 Client-side search

`setSearchQuery` just sets the flow — **no debounce in the VM, no reload** (`:98-100`).
Filtering happens in the fragments (§5.4). `isSearchActive = query non-empty` (`:96`).

---

## 4. Container screen (`ChannelDetailFragment` + `fragment_channel_detail.xml`)

### 4.1 Structure

CoordinatorLayout: `AppBarLayout` → `CollapsingToolbarLayout` (`scroll|exitUntilCollapsed`,
`contentScrim ?colorSurface`, parallax 0.5 on the header content) containing exclusion banner +
banner image + channel info block + pinned toolbar; then a **sticky** `TabLayout` below the
collapsing part; content area = search bar + `ViewPager2` (all 5 tabs,
`FragmentStateAdapter`, swipe enabled) (`fragment_channel_detail.xml:10-286`,
`ChannelDetailFragment.kt:186-210, 530-546`).

### 4.2 Toolbar

- Back arrow `ic_arrow_back` → `navigateUp()` (`ChannelDetailFragment.kt:146-152`).
- Kebab menu `menu_detail_kebab`: **Share** and **Report** only
  (`res/menu/menu_detail_kebab.xml`), icons shown via `showIcons()` (`:148-149`).
- **Scroll-reactive tinting**: expanded (over banner) → `colorOnPrimary` (white); collapsed →
  `colorOnSurface`; applied to nav icon, title, and overflow icon on every offset change
  (`:167-179, 490-492`). Toolbar background is a top-down black→transparent scrim gradient
  `#99000000 → #00000000` (`res/drawable/toolbar_scrim_gradient.xml`).
- Title: `channelName ?: channelId` until header loads, then `header.title` (`:147, 364`).

### 4.3 Header content

`fragment_channel_detail.xml` (phone):

- Banner: full-width, height `channel_banner_height` **180 dp (220 / 280)**
  (`values/dimens.xml:173`, `values-sw600dp/dimens.xml:79`, `values-sw720dp/dimens.xml:83`),
  `centerCrop`, placeholder `thumbnail_placeholder` (`:56-63`). When `bannerUrl` present, a
  bottom-heavy gradient overlay `#00000000 → #33000000 → #99000000` is shown; absent → static
  placeholder, gradient hidden (`ChannelDetailFragment.kt:367-377`,
  `res/drawable/banner_gradient_overlay.xml`).
- Info block padding: `spacing_md` all sides on phone (`:79-82`);
  `channel_header_horizontal_margin` **16 / 32 / 48 dp** horizontal + `spacing_lg` (sw600) /
  `spacing_xl` (sw720) vertical (`layout-sw600dp/…:78-81`, sw720 diff).
- Avatar: circular (`cornerSize 50%`, `styles.xml:110-113`), `channel_avatar_size`
  **80 / 96 / 112 dp**, stroke `channel_avatar_stroke` **3 / 4 / 4 dp** in `?colorSurface`,
  background `skeleton_background` #E0E0E0 (`:85-97`; dims `values/dimens.xml:174-175`,
  `sw600:80-81`, `sw720:84-85`). Load: repo URL → arg URL fallback on error → placeholder;
  memory/disk/network caches enabled (`ChannelDetailFragment.kt:455-488`).
- Name: `text_headline` **20 sp (24 sp sw720)** bold, `?colorOnSurface`, maxLines 2, ellipsize
  end (`:112-123`; `values/dimens.xml:193`, `sw720:99`).
- Verified badge: `ic_verified` tinted `primary_green` #275E4B, size `badge_icon_size`
  **20 / 24 / 28 dp**, `marginStart spacing_xs` (sw600: `spacing_sm`), visible iff
  `header.isVerified`; a11y label `channel_verified` = "Verified" (`:126-135`,
  `ChannelDetailFragment.kt:386`; dims `values/dimens.xml:139`, `sw600:44`, `sw720:48`).
- Subscriber count: `text_body` 14 sp (sw720: `text_subtitle`), color `primary_green`,
  `marginTop spacing_xs` (sw720 `spacing_sm`). Text: count > 0 →
  `channel_subscribers_format` = "%s subscribers" with `CountFormat.compact(count, appLocale)`;
  nil or 0 → `channel_subscribers_unknown` = **"–"** (still visible)
  (`:140-151`, `ChannelDetailFragment.kt:389-397`, `strings.xml:321,327`).
- Summary line: `summaryLine ?? shortDescription`, `text_body`, `?colorOnSurfaceVariant`,
  maxLines **2** (3 on sw600/sw720), hidden when blank (`:154-169`,
  `ChannelDetailFragment.kt:400-406`). Given §2.4, `summaryLine` is just the parent-channel
  name, so this usually shows the truncated description.
- Subscribe button (§4.7) below the summary (`:172-180`).

### 4.4 Tabs

Titles: `channel_tab_videos/live/shorts/playlists/about` = "Videos" / "Live" / "Shorts" /
"Playlists" / "About" (`strings.xml:30-34`; localized ar/nl exist, e.g.
`values-ar/strings.xml:22`, `values-nl/strings.xml:23`). Order = enum order
(`ChannelDetailModels.kt:169-175`).
Phone: `tabMode scrollable`, gravity center, padding `spacing_md`
(`fragment_channel_detail.xml:201-213`); sw600: `fixed`/`fill`, padding `spacing_lg`
(D-pad focus); sw720: `fixed`, `minHeight touch_target_button` 56 dp, `tabMinWidth 48dp`,
padding `spacing_xl` (layout diffs). Selected color + indicator `primary_green`, unselected
`?colorOnSurfaceVariant`, indicator not full width.
Selected tab index is reported to the VM on page change (`ChannelDetailFragment.kt:203-208`)
and restored from instance state (`:107-109, 494-499`).

### 4.5 Header view-state mapping

`updateHeaderUI` (`ChannelDetailFragment.kt:230-287`); AppBar stays visible in all states so
back always works (`:233`):

| HeaderState | headerSkeleton | headerContent | tabs+pager | contentSkeleton | contentErrorState |
|---|---|---|---|---|---|
| Loading | visible | hidden | hidden | visible (spinner + "Loading…") | hidden |
| Success | hidden | visible | visible | hidden | hidden |
| Error | hidden | hidden | hidden | hidden | visible, body = message, **Retry** → `loadHeader(forceRefresh: true)` |
| ContentUnavailable | hidden | hidden | hidden | hidden | visible, headline `content_unavailable_title` = "Content not available", body `content_unavailable_message`, **no Retry** |

Content "skeleton" is actually a centred `CircularProgressIndicator` (green) + "Loading…"
label (`fragment_channel_detail.xml:289-312`). The header skeleton is a real skeleton: banner
block, circle avatar, name/subs/summary bars, and a fake tab row; sits at
`marginTop ?actionBarSize`, elevation `elevation_lg` 8 dp (`:325-430`; phone bar heights
20/14/14 dp, sw600 24/16/16, sw720 28/20/20 per the layout diffs). `skeleton_shimmer` is the
same static fill as Phase 1 (§4.7 there).

### 4.6 Exclusion banner

`exclusionBanner` at the very top of the collapsing header: `?colorError` background,
`?colorOnError` text, `detail_excluded_banner` = "This item is currently unavailable due to
policy restrictions.", padding `spacing_md`; visible iff the `excluded` arg
(`fragment_channel_detail.xml:40-49`, `ChannelDetailFragment.kt:181-182`). Per §1.2 no caller
sets it true today.

### 4.7 Subscribe affordance (guest-local, no account needed)

- Room-backed observation: `subscriptions.isChannelSubscribed(channelId)` drives the button:
  text `channel_subscribe` = "Subscribe" / `channel_unsubscribe` = **"Subscribed"**, plus
  `isSelected` state (`ChannelDetailFragment.kt:289-301`, `strings.xml:178-179`). The flow is
  keyed by a uid derived from local account state (`SubscriptionRepository.kt:107-110`) —
  works signed-out.
- Toggle (`:303-357`): captures direction at click time, disables the button for the call,
  runs on IO. Unsubscribe is direct; subscribe goes through `SubscriptionLimitGuard`
  enforcing a **30-channel cap** (`SubscriptionLimitGuard.kt:26,73`); `LimitReached` →
  `Snackbar.LENGTH_LONG` with `me_subscription_cap_reached` = "You're following 30 channels
  (the limit). Unsubscribe one to follow this channel." (`strings.xml:183`). Room failures are
  logged, not surfaced. The stored row is
  `SubscribedChannel(channelId, channelUrl = "https://www.youtube.com/channel/<id>", name,
  avatarUrl)` (`:332-339`). Requires a loaded header (`latestHeader ?: return`, `:304`) and a
  well-formed id (§1.3).

### 4.8 Share

Kebab → Share (`:153-158, 410-447`):

- URL: `ShareLinks.channel(...)` = `<SHARE_BASE_URL>/api/channel/<id>`, falling back to
  `albunyaantube://channel/<id>` when the base URL is blank (`share/ShareLinks.kt:24-38, 56-73`).
- Before opening the sheet, metadata (`type "channel"`, id, title, image, description) is
  POSTed via `ShareMetadataPublisher.publish` (`:426`, `share/ShareMetadataPublisher.kt:32-40`).
- Share text = title + "\n\n" + `share_channel_in_app` ("Open this channel in FitrahTube:") +
  "\n" + URL + "\n\n" + `share_app_promo` ("Get FitrahTube for ad-free Islamic content!");
  chooser title `share_channel_chooser` = "Share channel" (`:429-446`, `strings.xml:283-287`).
- Title precedence: header title → arg name → id; image: avatar → banner; description:
  summary → short → full (`:413-418`). No-op when `channelId` blank (`:411`).

### 4.9 Report

Kebab → Report opens `ContentReportBottomSheet(ReportTargetType.CHANNEL, channelId)`
(`:159-162, 449-453`); menu label `report_content` = "Report" (`strings.xml:621`).

### 4.10 In-header search field

Between AppBar and pager, always visible, **not** part of the collapsing region
(`fragment_channel_detail.xml:232-275`): `OutlinedBox.Dense` field, hint `search_hint`
("Search…"), leading `ic_search`, single-line, `imeOptions actionSearch`; clear button
`ic_close` `icon_small` 24 dp shown iff non-empty. Behaviour (`ChannelDetailFragment.kt:112-142`):
**single 300 ms debounce** (`SEARCH_DEBOUNCE_MS`, `:522`) — unlike the Phase 1 tabs' composed
600 ms; IME-Search applies immediately; clear cancels the timer, empties the field, and applies
`""` immediately. What the query does is §5.4.

---

## 5. List tabs — shared behaviour (`BaseChannelListTabFragment` + `fragment_channel_list_tab.xml`)

Applies to Videos, Live, Playlists (Shorts duplicates the same logic with a grid, §6.3).

### 5.1 Layout

`fragment_channel_list_tab.xml`: SwipeRefreshLayout wrapping a `LinearLayoutManager`
RecyclerView (`paddingTop/Bottom spacing_sm`, `clipToPadding=false`, nested scrolling on,
`:9-25`); overlays: skeleton = **exactly 6 × `skeleton_content_item`** (`:27-43`, same rows as
Phase 1 §4.7), `empty_state`, `error_state` (`:45-59`). Same file serves all width classes
(no sw600/sw720 variant) — **all list tabs stay 1 column even on tablets**.

### 5.2 Pagination wiring

- Adapter = `ConcatAdapter(contentAdapter, ListFooterAdapter)` (`BaseChannelListTabFragment.kt:61-73`).
- Scroll listener: `dy > 0` only → `viewModel.onListScrolled(tab, lastVisible, total)`;
  a rejected request schedules a **delayed re-check after 1100 ms** (rate-limit window + 100 ms),
  max **1** retry, retrying from `Loaded` (nextPage, not appending) or `ErrorAppend`
  (`:76-112, 410-415`).
- **Autofill** when content can't scroll (`checkAutofillPagination`, `:123-175`): needs
  `nextPage != nil`, not appending; capped at **1 attempt on phones, 2 on
  `smallestScreenWidthDp ≥ 600`** (`:231-234, 399-404`); when capped with more pages,
  `setShowLoadMoreFooter(tab, true)` surfaces the footer button (`:134-141`,
  `ChannelDetailViewModel.kt:331-359`); post-layout `canScrollVertically` check with
  lifecycle guards; rejected (rate-limited) attempts re-check after 1100 ms, max 1 retry
  (`:184-211`). Counter resets when the list becomes scrollable or pages run out (`:127-130, 170-173`).
- `resetAutofillCounter()` (also clears the footer flag) runs on pull-to-refresh, initial-error
  retry, and Load-More tap (`:216-225, 240-243, 252-265`).

### 5.3 Footer (`ListFooterAdapter` + `item_list_footer.xml`)

States Hidden / LoadMore / Loading / Error (`ListFooterAdapter.kt:40-49`):

- **LoadMore**: text button `load_more` = "Load more" with trailing `ic_expand_more`; tap →
  reset counter + `loadNextPage` (`item_list_footer.xml:13-24`,
  `BaseChannelListTabFragment.kt:240-243`).
- **Loading**: 32 dp circular indicator, track 3 dp (`:26-37`; `values/dimens.xml:205-206`).
- **Error**: message in `?colorError` (custom message, else `load_more_error` = "Failed to load
  more. Tap to retry.") + "Retry" text button → `retryAppend` (`:39-66`,
  `ListFooterAdapter.kt:116-126`, `strings.xml:347`).
- Footer padding: `spacing_md` horizontal, `spacing_lg` vertical; buttons 56 dp tall.
- Driven from state: `isAppending` → Loading; `showLoadMoreFooter && nextPage != nil` →
  LoadMore; else Hidden; `ErrorAppend` → Error(message)
  (`BaseChannelListTabFragment.kt:326-332, 357-366`).

### 5.4 Client-side search filtering

`combine(state, searchQuery)` (`:269-294`): trimmed, lowercased query; empty → passthrough.
On `Loaded`: filter by `matchesQuery`; empty result → **`Empty` state** (the tab's generic
empty message — there is no search-specific copy here, unlike Phase 1 §4.6); non-empty →
`copy(items: filtered, nextPage: nil)` — **pagination disabled while searching**. On
`ErrorAppend`: items filtered, nextPage kept. Matchers: title OR uploaderName contains
(Videos `ChannelVideosTabFragment.kt:84-86`, Live `ChannelLiveTabFragment.kt:80-82`,
Playlists `ChannelPlaylistsTabFragment.kt:58-60`); Shorts: title only
(`ChannelShortsTabFragment.kt:246-249`). About ignores the query entirely.
Note the filter only sees **loaded pages** — a match further down the channel's uploads is
invisible; defect-adjacent by design.

### 5.5 View-state → UI

`updateUI` (`:297-369`); `swipeRefresh.isRefreshing = false` on every emission (`:299` — the
refresh spinner is cleared by the next state, `LoadingInitial` shows the skeleton instead):

| State | skeleton | list | empty | error overlay | footer |
|---|---|---|---|---|---|
| Idle / LoadingInitial | visible | hidden | hidden | hidden | — |
| Loaded | hidden | visible | hidden | hidden | per §5.3 |
| Empty | hidden | hidden | visible, body = per-tab string | hidden | — |
| ErrorInitial | hidden | hidden | hidden | visible, body = message or `channel_tab_error_generic` ("Couldn't load this tab. Please check your connection and try again.", `strings.xml:815`), Retry → `retryInitial` | — |
| ErrorAppend | hidden | **visible with items** | hidden | hidden | Error(message) |

- `empty_state.xml` / `error_state.xml` are **body-anchored**: the body text is vertically
  centred and always visible; icon/headline clip from the top in short containers (the tab
  area under an expanded header is ~200 dp tall) — a deliberate design note in both files
  (`error_state.xml:2-8`, `empty_state.xml:2-10`). Headlines: `error_state_generic_headline`
  = "Something went wrong", `empty_state_generic_headline` = "No content yet"
  (`strings.xml:819-820`). Retry button 56 dp tall, min width 120 dp.
- Empty-state icons are never set by these tabs (only `emptyBody` text is assigned,
  `:342`) — the `emptyIcon` ImageView has no `src` outside tools, so **no icon renders**.
- Pull-to-refresh: green spinner, handler = reset autofill + `loadInitial(tab, forceRefresh:
  true)` (`:252-258`). **Not disabled during search** (unlike Phase 1 tabs §4.4 there).

Empty-state copy (`strings.xml:340-343`): Videos "This channel has no videos yet"; Live
"No live or upcoming streams"; Shorts "No Shorts available"; Playlists "No playlists available".

---

## 6. Per-tab specifics

### 6.1 Videos tab

`ChannelVideosTabFragment.kt`. Cell = **`item_video_list`** — the 140 dp 16:9 thumb row
specified in Phase 1 §5.3 (`ChannelVideoAdapter.kt:24-31`; `video_list_thumbnail_width`
`values/dimens.xml:85`). Binding (`:44-93`):

- Duration `h>0 ? "%d:%02d:%02d" : "%d:%02d"`, `Locale.US` (`:84-93`); empty string when nil.
- Meta = `"<views> • <publishedTime>"`, either half dropped when absent (`:60-66`); views =
  plural `video_views` with `CountFormat.compact` + `compactPluralCount` (same rules as
  Phase 1 §5.6); `publishedTime` is NewPipe's textual date **used verbatim** — it arrives in
  NewPipe's extraction locale, not necessarily the app locale.
- Thumbnail via `loadYouTubeThumbnail(primary, videoId, isShort: false)` (fallback chain helper).
- Category chips container force-hidden (`:76-77`). Diff: id / whole-value (`:97-103`).

Tap → global `playerFragment` with `videoId`, `title`, **`channelId`** (for report scoping),
`channelName = uploaderName ?? ""`, `thumbnailUrl ?? ""`, `durationSeconds ?? 0`,
`viewCount ?? -1`; prefetch fires first (`ChannelVideosTabFragment.kt:56-73`). Note: unlike the
Phase 1 tabs (which pass `category` as `channelName` — their Q4), this tab passes the real
uploader name, and no `description`.
`PredictivePrefetchController` attaches when the feature flag is on (`:88-97`).

### 6.2 Live tab

`ChannelLiveTabFragment.kt`, cell `item_channel_live.xml`: row with 140 dp 16:9 card
(radius 12), title `text_subtitle` bold maxLines 2, meta 13 sp maxLines 2; padding
`spacing_md` h / `spacing_sm` v (`:6-120`). Badges bottom-start of the thumb, margin
`spacing_xs`: LIVE = red #F44336, UPCOMING = blue #2196F3, both radius 4, white bold 10 sp
all-caps, padding 6×2 (`res/values/styles.xml:153-167`, `res/drawable/bg_live_badge.xml`,
`bg_upcoming_badge.xml`; text `live_badge` = "LIVE", `upcoming_badge` = "UPCOMING",
`strings.xml:351-352`). Duration chip bottom-end for **past** streams only
(`ChannelLiveAdapter.kt:52-78`).

Meta line (`ChannelLiveAdapter.kt:102-146`): live → plural `live_watching_count` ("%s watching");
upcoming → localized medium-date + short-time of `scheduledStartTime`, else "" — but the repo
**always maps `scheduledStartTime` to nil** (`NewPipeChannelDetailRepository.kt:812`), so
upcoming rows show an empty meta; past → `live_past_meta` = "%1$s • %2$s" of views-plural and
`publishedTime`. Upcoming detection is a heuristic: `streamType == NONE && duration <= 0`
(`:800-804`).

Tap → global `playerFragment` with `videoId`, `title`, `channelId`, `channelName`,
`thumbnailUrl`, **`contentSubType = "LIVESTREAM"`**, `viewCount ?? -1` — no duration arg
(`ChannelLiveTabFragment.kt:55-74`).

### 6.3 Shorts tab

`ChannelShortsTabFragment.kt` — standalone copy of the base logic (not a subclass) with a
**GridLayoutManager**: span `channel_shorts_span_count` = **2 / 4 / 5**
(`values/dimens.xml:200`, `values-sw600dp/dimens.xml:86`, `values-sw720dp/dimens.xml:105`);
footer spans the full row (`:125-152`). Layout `fragment_channel_shorts_tab.xml`: recycler
padding `spacing_sm`; **the skeleton container is a RecyclerView with only `tools:` attributes
and no adapter ever set — defect: LoadingInitial shows a blank area, not skeleton cards**
(`fragment_channel_shorts_tab.xml:28-39`; no adapter assignment anywhere in the fragment).
`skeleton_channel_short.xml` (9:16 block + two bars) exists but is only referenced by tools.

Cell `item_channel_short.xml`: card, margin `spacing_xs`, `corner_radius_small` 12 dp,
elevation 2; 9:16 `centerCrop` thumb; 80 dp bottom gradient; overlaid white bold 13 sp title
(maxLines 2, shadow) + 11 sp `#CCFFFFFF` views line (`:3-79`). Views formatting is **local**,
not `CountFormat`: `views_count_billions/millions/thousands` = "%.1fB/M/K views", else
"%d views" (`ChannelShortsAdapter.kt:62-79`, `strings.xml:428-431`) — inconsistent with every
other surface (defect: not locale-compacted, no plurals).

Tap → global `shortsPlayerFragment` with `initialShortId`, `channelId`, `initialShortTitle`,
`initialChannelName` (parent arg), `initialThumbnailUrl`, `initialChannelAvatarUrl` (parent
arg), `initialDurationSeconds ?? 0`, `contentSubType = "SHORT"`
(`ChannelShortsTabFragment.kt:86-105`). State handling matches §5.5 except: `Idle` triggers
`loadInitial` directly from `updateUI` (`:266-268`), and `ErrorInitial` sets **no error text**
(generic-fallback assignment missing — defect vs. base `:354-355`) (`:301-306`).

### 6.4 Playlists tab

`ChannelPlaylistsTabFragment.kt`, cell = shared `item_playlist` (Phase 1 §5.2). Meta uses the
plural `video_count` ("%d video(s)") plus `" • <uploaderName>"` when present
(`ChannelPlaylistsAdapter.kt:39-54`); nil `itemCount` renders as 0. A11y description
`a11y_playlist_item` (`:64-68`). Thumbnail via plain Coil (no YouTube fallback helper, `:57-61`).
Tap → global `playlistDetailFragment` with `playlistId`, `playlistTitle`, `excluded = false`
**only** — no category/count (`ChannelPlaylistsTabFragment.kt:40-52`).

### 6.5 About tab

`ChannelAboutTabFragment.kt` observes `aboutState` (= header state). NestedScrollView content
(`fragment_channel_about_tab.xml`):

- **Description**: full → short → italic `channel_about_no_description` ("No description
  available") (`ChannelAboutTabFragment.kt:100-112`); body 14 sp, line-spacing ×1.3.
- **Links**: rows inflated from `item_channel_link.xml` (20 dp `ic_link` green, bold 14 sp
  name, 12 sp green URL, chevron); tap → `ACTION_VIEW`, failures swallowed silently
  (`:114-134, 201-208`). Section + divider hidden when no links. Links = donation links only (§2.4).
- **More info** rows, each `24 dp icon + 14 sp label`, `paddingVertical spacing_sm`:
  subscribers (`channel_subscribers_format`, compact), total views (`channel_total_views` =
  "%s total views"), location, joined (`channel_joined_date` = "Joined %s", localized medium
  date), verified (`:136-189`). Because the repo never populates views/location/joined (§2.4),
  **only subscribers and verified can ever appear**; section header hides only if all are
  absent (`:187-188`).
- States: Loading → skeleton bars (`fragment_channel_about_tab.xml:277-331`); Error → error
  overlay with Retry → `loadHeader(forceRefresh: true)` (`:77-86`); ContentUnavailable →
  everything hidden (parent shows the message) (`:87-93`).
- Defect: section titles and the empty/description text hardcode `@android:color/black` /
  `darker_gray` (`fragment_channel_about_tab.xml:32,41,54,76,108` etc.) — same for
  `empty_state.xml:24,42` — illegible in dark theme, unlike the attr-based colors elsewhere.

---

## 7. Cross-cutting layout / RTL

- RTL: `layoutDirection="locale"` on the header content and the content container
  (`fragment_channel_detail.xml:34,222`); every text uses `textAlignment="viewStart"`;
  badges anchor with start/end gravities (`item_channel_live.xml:48,71`).
- The screen has no bottom-nav inset handling of its own — it is pushed as a full-screen
  destination; list padding is just `spacing_sm` top/bottom (`fragment_channel_list_tab.xml:21-22`),
  so on Android the last row can sit under system bars (contrast Phase 1's
  `bottom_nav_height` padding).
- sw720 additions: `focusable="true"` on avatar, toolbar, and pager for D-pad (layout diff).
- Colors reused: `primary_green` #275E4B, `surface_variant` #E3E9E7, `accent_red` #D32F2F,
  `skeleton_background` #E0E0E0, `divider_light` #1A000000, `live_badge_bg` #F44336,
  `upcoming_badge_bg` #2196F3 (`values/colors.xml:3-6,57,69,73-74`).

---

## 8. Behavioural checklist for the iOS implementer

**Entry**
1. Route args: `channelId` (required), optional `name`, `avatarUrl`, `excluded=false`; accept
   the three deep-link URL shapes (§1.1).
2. Seed the toolbar/header with the arg name + avatar; swap to fetched values on load; keep
   the arg avatar as an error fallback.

**Data**
3. Availability gate first (fail-open on transport, hard-stop on 410-style "blocked");
   unavailable is terminal, no retry.
4. Header from NewPipe ChannelInfo, 30-min/100-entry LRU cache, single-flight dedup; UU
   playlist degraded-header fallback for `UC…` ids.
5. Videos: channel-tab first paint, UU fallback, cursor-family provenance on append,
   Shorts filtered by flag + `/shorts/` URL only; Room pre-paint cache; initial failure with
   cache showing degrades to append-error, not a blank screen.
6. Live/Shorts/Playlists: one page initial; empty+continuation → Load-More footer, not Empty;
   append walks ≤ 5 empty continuations.
7. Pagination: threshold 5 from end, downward only; 1 s min interval between appends;
   rejected triggers re-check once after 1.1 s; autofill ≤ 1 page (phone) / 2 (regular),
   then a Load-More button.

**UI**
8. Collapsing banner header (180/220/280 pt) with gradient scrim, circular avatar
   (80/96/112 pt), verified badge, green compact subscriber line ("–" when unknown),
   2–3-line summary, subscribe button; toolbar icons flip white↔onSurface with collapse.
9. Sticky tab bar with all five tabs; selected tab restored across recreation; tabs load
   lazily on first visibility (Videos eagerly at open).
10. Per-tab states per §5.5, including the body-anchored empty/error layouts and the
    footer's LoadMore/Loading/Error trio.
11. In-header search: 300 ms debounce, client-side filter of loaded items per tab
    (title/uploader), pagination suppressed while active, About unaffected.
12. Subscribe: local persistence, 30-channel cap with the cap message; button disabled
    during the write.
13. Share: `<base>/api/channel/<id>` URL, metadata pre-publish, composed message; Report:
    channel-scoped report sheet.
14. Taps: video → player (with `channelId` + real uploader name), live → player with
    `contentSubType LIVESTREAM`, short → shorts player with `contentSubType SHORT`,
    playlist → playlist detail (id + title only).

**Android defects observed (record, don't auto-copy)**
- Shorts skeleton never renders (no adapter) — blank loading state (§6.3).
- Shorts `ErrorInitial` shows no message text (§6.3).
- Shorts view-count formatting bypasses `CountFormat`/plurals (§6.3).
- About tab + shared `empty_state` hardcode black/gray text — broken in dark mode (§6.5, §5.5).
- Upcoming streams always show an empty meta line (`scheduledStartTime` never populated) (§6.2).
- Search-filtered empty shows the generic tab empty copy, not a "no results" variant (§5.4).
- Playlist-detail entry passes no avatar; Home/Featured/Search pass no `excluded` (harmless,
  defaults, but inconsistent arg discipline) (§1.2).

---

## 9. Open questions

**Q1 — Live tab on iOS at all?** The tab machinery is fully built, but livestream *playback*
may interact with Phase 3 scope. Android treats a live row as a normal player launch with
`contentSubType = "LIVESTREAM"` (`ChannelLiveTabFragment.kt:61-72`). Confirm the Live tab ships
in Phase 2 with playback, ships list-only, or is deferred.

**Q2 — NewPipeExtractor on iOS.** The entire screen is built on NewPipe (Java). iOS needs an
equivalent extraction path (own scraper, server-side proxy, or a port). This brief records the
*behaviour* (paths, fallbacks, cursors, rate-limit retries); the mechanism is an architecture
decision this document deliberately does not make.

**Q3 — Videos dual-path complexity.** The channel-tab/UU split exists to work around specific
NewPipe v0.26 pagination bugs (`ChannelDetailViewModel.kt:413-420`,
`NewPipeChannelDetailRepository.kt:184-196`). If iOS's extraction layer doesn't share those
bugs, is a single reliable path acceptable, with the provenance flag dropped?

**Q4 — Room video cache pre-paint.** Port the disk cache for instant re-open paint
(`ChannelDetailViewModel.kt:396-411`), or accept a skeleton on every open in v1? Cached rows
lose `publishedTime` (§3.4) — worth replicating that quirk?

**Q5 — In-header search semantics.** Client-side filtering of loaded pages only, with
pagination disabled and generic empty copy (§5.4), is arguably surprising (matches deeper in
the channel are invisible). Mirror exactly, or add a "no results in loaded items" copy /
server-side search? Android leaves this ambiguous.

**Q6 — `excluded` arg is dead.** No caller passes `true` (§1.2). Port the banner + arg for
deep-link parity, or drop until a caller exists?

**Q7 — About tab's permanently-nil rows.** `location` / `joinedDate` / `totalViews` can never
render (§2.4) yet the layout and strings exist. Build the rows on iOS (future-proofing) or
omit?

**Q8 — Subscriber "–" placeholder.** Unknown/zero subscribers shows a bare en-dash
(`channel_subscribers_unknown`, §4.3). Intentional design or placeholder — hide the line
instead?

**Q9 — Tab-bar overflow behaviour.** Phone uses scrollable tabs, ≥600 dp fixed/fill (§4.4).
iOS has no `TabLayout`; confirm the segmented/scrollable control choice per size class, and
whether swipe-between-tabs (ViewPager2 `isUserInputEnabled = true`, `ChannelDetailFragment.kt:196`)
must be preserved.

**Q10 — Autofill caps differ from Phase 1.** These tabs cap autofill at 1–2 pages then show a
button (`BaseChannelListTabFragment.kt:399-404`); Phase 1 tabs allow 5 silent attempts and gate
phones out entirely (Phase 1 §4.3). Unify on one model for iOS, or keep both machines?

**Q11 — Shorts skeleton.** Fix the blank loading state on iOS (render the 9:16 skeleton grid
that `skeleton_channel_short.xml` intends), or mirror the blank?
