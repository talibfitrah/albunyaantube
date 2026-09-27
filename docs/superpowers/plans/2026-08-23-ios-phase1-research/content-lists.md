# Phase 1 research — Content list screens (Channels / Playlists / Videos / Featured)

Scope: `ChannelsFragmentNew`, `PlaylistsFragmentNew`, `VideosFragmentNew`, `ContentListViewModel`,
paging stack, `FilterManager`/`FilterState`, `FeaturedListFragment`/`FeaturedListViewModel`,
item layouts + adapters. Every claim cites `android/app/src/main/**` file:line.
Behavioural contract for the SwiftUI port — no Swift code here.

Spec context read first: `docs/superpowers/specs/2026-08-23-ios-app-design.md:103-171` (§6 Navigation, §7 Design system).

---

## 0. TL;DR for the implementer

1. **Do not port Paging 3.** `CursorPagingSource` + `DefaultContentPagingRepository` are dead code
   (§3.4). The three tab screens hand-roll pagination inside `ContentListViewModel`.
2. **One shared ViewModel, three instances.** Same class, differing only by `ContentType`
   (`ContentListViewModel.kt:30-33`). Screens differ only in column rule, cell layout, tap target,
   empty-state copy, and whether a Categories FAB exists.
3. **Search debounce is 600 ms, not 300.** Two 300 ms timers compose (§4.1). Decide deliberately.
4. **Only `category` is ever set on `FilterState`.** Length / date / sort are persisted, sent to the
   backend, and never mutated by any UI (§6.3).

---

## 1. Backend contract

### 1.1 `GET /api/v1/content`

`ContentApi.kt:16-26`

| Query param | Source | Notes |
|---|---|---|
| `type` | `ContentType.name` | `CHANNELS` / `PLAYLISTS` / `VIDEOS`; **omitted (null) for `ALL`** — `RetrofitContentService.kt:32` |
| `cursor` | opaque `String?` | `null` for first page |
| `limit` | page size | 20 for tabs, 50 for Featured flat list |
| `category` | `FilterState.category` | Firestore doc id |
| `length` | `FilterState.videoLength` | `null` \| `SHORT` \| `MEDIUM` \| `LONG` — `RetrofitContentService.kt:48-53` |
| `date` | `FilterState.publishedDate` | `null` \| `LAST_24_HOURS` \| `LAST_7_DAYS` \| `LAST_30_DAYS` — `:55-60` |
| `sort` | `FilterState.sortOption` | `null` \| `MOST_POPULAR` \| `NEWEST` — `:62-66` |
| `q` | search query | omitted when null/blank — `RetrofitContentService.kt:41` |

`ANY` / `DEFAULT` enum values map to **omitted param**, not an explicit value
(`RetrofitContentService.kt:49,56,63`). iOS must omit, not send `"ANY"`.

Response shape: `{ data: [ContentItemDto], pageInfo: { nextCursor } }`
(`ContentApi.kt:60-64` → `CursorResponse.kt:3-10`).

**`hasMore` is derived, never sent**: `hasMoreData = nextCursor != null`
(`ContentListViewModel.kt:117,167,218`). There is no `hasNext` on this endpoint's `pageInfo`
as consumed here (contrast `/api/v1/home`, which does expose `hasNext` — `RetrofitContentService.kt:79`).

### 1.2 `GET /api/v1/home` (Featured only)

`ContentApi.kt:38-44` — params `cursor`, `categoryLimit`, `contentLimit`, `category`.
Returns `{ data: [HomeSectionDto], pageInfo: { nextCursor, hasNext } }`.

### 1.3 Domain models

`ContentItem.kt:3-35` — sealed union:

- `Video(id, title, category, durationSeconds: Int, uploadedDaysAgo: Int, description, thumbnailUrl?, viewCount: Long?, channelName?)`
- `Channel(id, name, category, subscribers: Int, description?, thumbnailUrl?, videoCount?, categories: [String]?)`
- `Playlist(id, title, category, itemCount: Int, description?, thumbnailUrl?)`

`ContentType`: `HOME, CHANNELS, PLAYLISTS, VIDEOS, ALL` (`CursorResponse.kt:18-24`).
`HomeSection(categoryId, categoryName, categorySlug?, localizedNames: [String:String]?, icon?, items, totalItemCount)` (`HomeSection.kt:7-15`).

---

## 2. `ContentListViewModel` — state shape and rules

File: `android/app/src/main/java/com/albunyaan/tube/ui/ContentListViewModel.kt`

### 2.1 Published state

```
ContentState =
  | Loading(type: LoadingType)                                    // :258
  | Success(items, hasMoreData = true, paginationError: String? = nil,
            isSearchActive = false)                               // :259-264
  | Error(message)                                                // :265

LoadingType = INITIAL | REFRESH | PAGINATION                      // :246-250
```

Plus a **second** published stream: `searchQuery: StateFlow<String>` (`:49-50`), deliberately kept
out of `FilterState` "to avoid DataStore persistence" (`:48`). Search text must **not** persist on iOS.

### 2.2 Private state (must exist on iOS, must not be published)

`nextCursor: String?`, `hasMoreData: Bool = true`, `isLoadingMore: Bool`, `isRefreshing: Bool`,
`loadJob: Job?`, `currentFilters: FilterState`, `searchJob: Job?`, `allItems: [ContentItem]`
(`:39-51,71`).

Derived, read by the view: `canLoadMore = !isLoadingMore && !isRefreshing && hasMoreData` (`:67-68`);
`isSearchActive = searchQuery.isNotEmpty()` (`:53-54`).

### 2.3 Lifecycle and transitions

| Trigger | Behaviour |
|---|---|
| init | `loadContent()` fires immediately, no explicit `onAppear` call (`:73-75`) |
| `setFilters(f)` | **no-op if `f == currentFilters`** (value equality on the whole struct); else assign + `loadContent()` (`:81-87`) |
| `setSearchQuery(q)` | **no-op if unchanged** (`:57`); else set, cancel prior `searchJob`, `delay(300)`, `loadContent()` (`:59-63`) |
| `loadContent()` | cancels `loadJob`; resets cursor/hasMore/flags; **clears `allItems`**; emits `Loading(INITIAL)`; fetch cursor=nil (`:92-130`) |
| `refresh()` | early-return if already refreshing (`:138-141`); sets `isRefreshing`; cancels job; resets cursor + clears items; emits `Loading(REFRESH)`; `finally { isRefreshing = false }` (`:136-182`) |
| `loadMore()` | early-return if `isLoadingMore \|\| isRefreshing \|\| !hasMoreData` (`:193-196`); sets flag; emits `Loading(PAGINATION)`; **cancels `loadJob` then reassigns** (`:204-205`); appends |

### 2.4 Page size

`PAGE_SIZE = 20` (`ContentListViewModel.kt:283`). Same constant duplicated at
`DefaultContentPagingRepository.kt:10` (dead path).

### 2.5 Error semantics — three distinct outcomes

- **Initial/refresh failure** → `ContentState.Error(e.message ?: "Unknown error")`, items lost
  (`:125-128`, `:175-177`).
- **Pagination failure** → stays `Success`, **keeps accumulated items**, sets
  `paginationError = e.message ?: "Failed to load more"` (`:226-235`). `hasMoreData` is *not*
  cleared, so the cursor survives and a later scroll can retry.
- **Cancellation** is rethrown, never converted to an error (`:227`). Swift: check `Task.isCancelled` /
  rethrow `CancellationError` before the catch-all.

`paginationError` is a **transient** field on a `Success` value: the fragment shows a snackbar on
every emission carrying it (§4.5). On iOS the equivalent must fire once per occurrence — either a
one-shot event stream or an id-stamped value — or the toast repeats on every unrelated re-render.

### 2.6 Race-condition caveat to preserve (or fix deliberately)

`loadMore()` cancels `loadJob` at `:204` — the same field `loadContent()`/`refresh()` use. If a
refresh is in flight when `loadMore` slips past the guard, the refresh coroutine is killed and
`isRefreshing` never resets (its `finally` at `:178-180` does not run on cancellation of the
enclosing job in the way `loadMore`'s does). The `isRefreshing` guard at `:193` makes this rare but
not impossible. On iOS, model this as **one `Task` per load-kind** rather than one shared handle.

---

## 3. Paging infrastructure

### 3.1 `CursorPagingSource`

`data/paging/CursorPagingSource.kt:17-31` — `params.key` is the cursor, `prevKey` always `nil`,
`nextKey = response.pageInfo?.nextCursor`, `getRefreshKey` returns `nil` (always restart from head).
Note it calls `fetchContent` **without** a `query` argument (`:20`) — the paging path never supported search.

### 3.2 `DefaultContentPagingRepository`

`data/paging/DefaultContentPagingRepository.kt:16-20` — `PagingConfig(pageSize = 20, enablePlaceholders = false)`.

### 3.3 `ContentPagingRepository`

`data/paging/ContentPagingRepository.kt:8-10` — single method `pager(type, filters)`.

### 3.4 **This whole stack is dead code**

Provided by Hilt at `di/DataModule.kt:325-326`, but `grep '\.pager('` across
`android/app/src` returns **zero call sites**. No screen consumes it. Do not port it; port
§2 instead. (Flagged as an open question — see §10.)

---

## 4. The three tab screens

### 4.1 Search — inline, in-header, per-screen

Identical in all three: `ChannelsFragmentNew.kt:72-106`, `PlaylistsFragmentNew.kt:71-105`,
`VideosFragmentNew.kt:88-122`.

- **No minimum character count.** Any non-empty string triggers a fetch; `q` is dropped only when
  blank (`ContentListViewModel.kt:112`).
- **Debounce is composed twice.** Fragment posts a `SEARCH_DEBOUNCE_MS = 300L` handler on every
  keystroke (`ChannelsFragmentNew.kt:277,82-86`), which then calls `setSearchQuery`, which itself
  applies `delay(300L)` before fetching (`ContentListViewModel.kt:61`). **Effective latency from last
  keystroke to request ≈ 600 ms.** iOS: pick one. Recommend a single 300 ms debounce and record the
  deviation.
- **Clear button** visible iff query non-empty (`:81`); tapping cancels the pending timer, clears the
  field, resets the autofill helper, and sets query `""` immediately, i.e. **no debounce on clear**
  (`:100-105`).
- **Keyboard "Search" action** (`IME_ACTION_SEARCH`) cancels the timer and applies immediately —
  bypasses the fragment debounce but still hits the VM's 300 ms (`:90-98`).
- Field is single-line, `inputType="text"`, `imeOptions="actionSearch"`
  (`fragment_simple_list.xml:32-39`), hint `search_hint` = **"Search…"** (`strings.xml:571`),
  leading `ic_search` icon (`:29`).
- **Search resets pagination state**: `setSearchQuery` → `loadContent()` → cursor nil, items cleared.
- **Every entry point resets `autofillHelper`** before mutating query (`:84,94,103`).

### 4.2 Column rules (differ per screen — this is the main layout fork)

| Screen | Rule | Citation |
|---|---|---|
| Channels | `isTablet() ? gridSpanCountDefault.coerceIn(2,4) : 1` (LinearLayoutManager = 1 column) | `ChannelsFragmentNew.kt:149-154` |
| Playlists | identical rule | `PlaylistsFragmentNew.kt:140-145` |
| Videos | **always grid**: `calculateGridSpanCount(itemMinWidthDp = 180)` | `VideosFragmentNew.kt:153-154` |

- `calculateGridSpanCount(min)` = `(screenWidthDp / min).toInt().coerceIn(2, 8)`
  (`ui/utils/ViewExtensions.kt:32-39`). Uses **`displayMetrics.widthPixels / density`**, i.e. current
  width — so it reacts to orientation. iOS: recompute on size-class/geometry change.
- `isTablet()` = `R.bool.is_tablet` — `false` in `values/dimens.xml:114`, `true` in
  `values-sw600dp/dimens.xml:51` and `values-sw720dp/dimens.xml:55`. Note this is
  **smallestScreenWidth**, so it does not flip on rotation.
- `grid_span_count_default`: **2** (`values/dimens.xml:74`), **3** (`values-sw600dp/dimens.xml:20`),
  **4** (`values-sw720dp/dimens.xml:19`). Coerced to 2..4, so the value is used as-is.

**Divergence from spec §7**: the spec states "channels/playlists 2/3/4 columns by width class"
(`2026-08-23-ios-app-design.md:167`). Android is **1 / 3 / 4** — phones render a full-width
`item_channel` / `item_playlist` *row* (avatar or square thumb + text column), not a 2-up grid.
See §10 Q1.

### 4.3 Auto-load rules — two independent mechanisms

**(a) Scroll threshold.** Identical in all three
(`ChannelsFragmentNew.kt:158-179`, `PlaylistsFragmentNew.kt:149-170`, `VideosFragmentNew.kt:158-175`):

1. ignore `dy <= 0` (**scroll-up never paginates**);
2. early-exit `if (!viewModel.canLoadMore)`;
3. `if (lastVisibleItemPosition >= itemCount - LOAD_MORE_THRESHOLD) loadMore()`, with
   `LOAD_MORE_THRESHOLD = 5` (`:276 / :267 / :308`).

**(b) `AutofillPaginationHelper`** — "content fits, so the scroll listener never fires"
(`ui/utils/AutofillPaginationHelper.kt`). Run from the `submitList` completion callback after every
`Success` (`ChannelsFragmentNew.kt:225-236` and peers). Guards, in order (`:52-90`):

| # | Guard | Line |
|---|---|---|
| 1 | `smallestScreenWidthDp < 600` → return (**phones never autofill**) | `:52` |
| 2 | `!hasMoreData` → `reset()` and return | `:54-57` |
| 3 | `hasPaginationError` → return (no retry storm) | `:60-63` |
| 4 | `attempts >= 5` (`DEFAULT_MAX_ATTEMPTS`) → return | `:66-69` |
| 5 | `attempts > 0 && itemCount <= lastItemCount` → return (progress invariant) | `:72-75` |
| 6 | after layout (`rv.post`): view still active, `!canScrollVertically(1)`, `canLoadMore()` → `loadMore()`; otherwise `reset()` | `:78-90` |

`reset()` is called on: search change, clear, filter change, pull-to-refresh, and `onDestroyView`
(`ChannelsFragmentNew.kt:84,94,103,111-112,130,282`).

iOS mapping: spec §7 already prescribes `.onAppear` on the last row **plus**
`onScrollGeometryChange` with an in-flight guard (`2026-08-23-ios-app-design.md:171`). Port guards
1–6 verbatim, including the ≥600 pt gate (or drop the gate and rely on 4+5 — but then say so).

### 4.4 Pull-to-refresh

- `SwipeRefreshLayout` wraps the list (`fragment_simple_list.xml:73-87`).
- Handler: `autofillHelper.reset(); viewModel.refresh()` (`ChannelsFragmentNew.kt:129-132`).
- **Disabled while search is active**: `swipeRefresh.isEnabled = !state.isSearchActive`
  (`ChannelsFragmentNew.kt:215`, `PlaylistsFragmentNew.kt:205`, `VideosFragmentNew.kt:229`).
  iOS: suppress `.refreshable` when the search field is non-empty.

### 4.5 View-state → UI mapping (per `LoadingType`)

| VM state | swipeRefresh | skeleton | list | loadingMore | emptyState |
|---|---|---|---|---|---|
| `Loading(INITIAL)` | hidden, spinner off | **visible** | — | hidden | hidden |
| `Loading(REFRESH)` | visible, spinner **on** | — | visible | hidden | hidden¹ |
| `Loading(PAGINATION)` | spinner off | — | visible | **visible** | — |
| `Success` | visible, spinner off, enabled = !searchActive | hidden | visible iff items | hidden | visible iff items empty |
| `Error`, list empty | hidden | **visible (skeleton stays)** | hidden | hidden | hidden² |
| `Error`, list non-empty | visible | hidden | visible | hidden | hidden² |

Citations: `ChannelsFragmentNew.kt:187-268`, `PlaylistsFragmentNew.kt:178-259`,
`VideosFragmentNew.kt:198-300`.

¹ Channels (`:200`) and Videos (`:213`) hide `emptyState` on REFRESH; **Playlists does not**
(`PlaylistsFragmentNew.kt:188-191` omits it) — an Android inconsistency. iOS should hide it in all three.

² Channels explicitly hides `emptyState` on Error (`:259`) and Videos does too (`:285,290`);
**Playlists does not touch it** (`:246-257`). Same fix.

**Deliberate design note in the source**: on initial-load failure the screen keeps the skeleton
rather than showing an error, because "the global offline banner from MainShellFragment communicates
the cause" (`VideosFragmentNew.kt:281-282`, `PlaylistsFragmentNew.kt:250-251`). iOS must ship the
offline banner (spec §6, `2026-08-23-ios-app-design.md:117`) *before* this behaviour is acceptable;
otherwise a failed first load looks like an infinite loading state.

**Toasts / snackbars** (all `Snackbar.LENGTH_SHORT`, anchored to the fragment root):

- Pagination error, **all three screens**: message = `paginationError` if non-blank else
  `list_error_title` (`ChannelsFragmentNew.kt:218-221`, `PlaylistsFragmentNew.kt:208-211`,
  `VideosFragmentNew.kt:233-240`).
- Terminal `Error` **with existing content**: **Videos only** shows a snackbar
  (`VideosFragmentNew.kt:291-296`). Channels and Playlists show nothing — the user sees stale content
  with no feedback. iOS: unify, show it everywhere.

`list_error_title` = **"Unable to load content"** (`res/values/strings_list_states.xml:3`;
nl `values-nl/strings_list_states.xml:3`, ar `values-ar/strings_list_states.xml:3`).

### 4.6 Empty states

Two variants, chosen by `state.isSearchActive` (`ChannelsFragmentNew.kt:241-249` and peers):

| Condition | Icon | Title key → English | Subtitle key → English |
|---|---|---|---|
| search active | `ic_search` | `search_no_results` → **"No results found"** (`strings.xml:575`) | `search_try_different_hint` → **"Try different keywords"** (`:577`) |
| Channels, no search | `ic_channels` | `channels_empty_title` → **"No channels yet"** (`:250`) | `channels_empty_subtitle` → **"Approved channels will appear here"** (`:251`) |
| Playlists, no search | `ic_playlists` | `playlists_empty_title` → **"No playlists yet"** (`:252`) | `playlists_empty_subtitle` → **"Approved playlists will appear here"** (`:253`) |
| Videos, no search | `ic_videos` | `videos_empty_title` → **"No videos yet"** (`:248`) | `videos_empty_subtitle` → **"Approved videos will appear here"** (`:249`) |

The empty view **replaces** the list (`recyclerView.visibility = GONE`) rather than sitting inside a
scroll view (`:239-240`) — so pull-to-refresh is unreachable while empty. iOS: put the empty state
inside the refreshable scroll container instead, and note the deviation.

Layout (`fragment_simple_list.xml:96-135`): centred column, padding `spacing_lg`; icon `icon_xlarge`
**96dp** (128dp on sw720, `values-sw720dp/dimens.xml:47`), tint `home_empty_icon` **#9E9E9E**
(`colors.xml:51`); title `marginTop spacing_md`, `text_section_title` **18sp bold**,
`home_text_primary` **#1A1A1A** (`colors.xml:34`); subtitle `marginTop spacing_sm`, `text_body`
**14sp** (16sp on sw720), `home_empty_text` **#757575** (`colors.xml:50`). Both centre-aligned.

No retry button on these three screens — refresh is pull-to-refresh only.

### 4.7 Skeleton

`view_list_skeleton.xml:16-21` — **exactly 6** copies of `skeleton_content_item`, vertical,
`paddingTop spacing_sm`, `paddingBottom bottom_nav_height`, `importantForAccessibility="no"`.

`skeleton_content_item.xml`: padding `spacing_md` horizontal / `spacing_sm` vertical; thumb block
**120×90 dp** (`:15-16`); title line 1 height **16dp**, `marginStart spacing_md`, `marginTop spacing_xs`
(`:24-27`); title line 2 height **16dp**, **width 70 %** (`:35-42`); metadata height **12dp**,
**width 50 %**, `marginTop spacing_sm` (`:47-53`). Fill = `skeleton_shimmer`, which is a **static**
shape — `corners radius corner_radius (20dp)` + `solid @color/surface_variant #E3E9E7`
(`drawable/skeleton_shimmer.xml`, `values/dimens.xml:16`, `colors.xml:5`). **There is no shimmer
animation on Android despite the name.** Spec §7 lists `SkeletonList` with `skeleton`/`skeletonShimmer`
tokens (`2026-08-23-ios-app-design.md:157,169`) — see §10 Q3.

Note the skeleton is a fixed 6-row **list** even on the Videos grid and on tablets, where the real
content is 2–8 columns.

### 4.8 Categories FAB — Channels only

`fragment_channels_new.xml:148-158`: `FloatingActionButton`, gravity `bottom|end`,
`marginEnd spacing_lg` (24/32/40 dp), `marginBottom fab_margin_bottom` **80dp**
(`values/dimens.xml:88`), icon `ic_category`, backgroundTint `primary_green` **#275E4B**,
tint `onPrimary`, `contentDescription = categories` → **"Categories"** (`strings.xml:255`).
Tap → `navigate(R.id.categoriesFragment)` (`ChannelsFragmentNew.kt:122-126`).

`fragment_simple_list.xml` (Playlists **and** Videos) has **no FAB** — those two screens have no way
to reach Categories except via Home. Inconsistent; see §10 Q2.

### 4.9 Filter chip (active-category indicator)

`fragment_simple_list.xml:55-66` / `fragment_channels_new.xml:54-65`: a Material `Chip`,
`layout_margin spacing_sm` (8dp), `visibility gone` by default, `closeIconVisible=true`,
`chipIcon = ic_filter`, `contentDescription = category_filter_active` →
**"Active category filter. Double tap to clear."** (`strings.xml:27`).

Driven by `Chip?.updateCategoryFilter(categoryId, categoryName, onClear)`
(`ui/utils/ViewExtensions.kt:74-87`):

- `categoryId` null/empty → `GONE`;
- else `VISIBLE`, `text = getString(filtering_by_category, categoryName ?: categoryId)` where
  `filtering_by_category` = **"Category: %1$s"** (`strings.xml:29`);
- close icon → `filterManager.setCategory(null)` (`ChannelsFragmentNew.kt:114-117`).

The chip sits **between** the search bar and the list, pushing content down when present (it is a
sibling in the vertical `LinearLayout`, not an overlay).

### 4.10 Tap targets

| Screen | Destination | Arguments passed |
|---|---|---|
| Channels | `channelDetailFragment` | `channelId`, `channelName`, `channelAvatarUrl` (= `thumbnailUrl`), `excluded = false` — `ChannelsFragmentNew.kt:136-144`; arg names `ChannelDetailFragment.kt:517-520` |
| Playlists | `playlistDetailFragment` | `playlistId`, `playlistTitle` **only** — `PlaylistsFragmentNew.kt:129-135`; arg names `PlaylistDetailFragment.kt:788-791` |
| Videos | global `playerFragment` | `videoId`, `title`, `channelName`, `thumbnailUrl`, `description`, `durationSeconds`, `viewCount` (**`-1L` when nil**) — `VideosFragmentNew.kt:181-191` |

Two details worth carrying over consciously:

- **Videos prefetches before navigating**: `prefetchService.triggerPrefetch(video.id, scope)`
  (`VideosFragmentNew.kt:180`), gated by `featureFlags.isPredictivePrefetchEnabled` for the
  scroll-driven `PredictivePrefetchController` (`:74-81`).
- **`channelName` is populated from `video.category`, not `video.channelName`**
  (`VideosFragmentNew.kt:185`), even though `ContentItem.Video.channelName` exists
  (`ContentItem.kt:13`). Same in Featured (`FeaturedListFragment.kt:74`). The player therefore shows a
  category where a channel name belongs. Almost certainly an Android bug — see §10 Q4.
- Playlists omits `playlistCategory` / `playlistCount`, which Featured *does* pass
  (`FeaturedListFragment.kt:85-90`), so `PlaylistDetailFragment` falls back to `0` (`:85`).

Row hit area = whole cell (`root.setOnClickListener`, e.g. `ChannelAdapter.kt:81-83`), with
`selectableItemBackground` ripple and `clickable/focusable=true` on the item root
(`item_channel.xml:8-10`).

---

## 5. Item layouts (dp; sw600 / sw720 overrides in parentheses)

Spacing scale (`values/dimens.xml:4-12`, `values-sw600dp/dimens.xml:36-39`,
`values-sw720dp/dimens.xml:35-38`):
`xxs 2`, `xs 4`, `sm 8`, `md 16 (20 / 24)`, `lg 24 (32 / 40)`, `xl 32 (48 / 64)`, `xxl 48`,
`xxxl 96 (112 / 128)`.

Type (`values/dimens.xml:141,163,164,194-196`, `values-sw720dp/dimens.xml:100-101`):
`text_section_title 18sp`, `text_subtitle 16sp (→18 on sw720)`, `text_body 14sp (→16 on sw720)`,
`text_duration 11sp`, `home_item_title_size 15sp`, `home_item_meta_size 13sp`.

Radii: `home_thumbnail_corner_radius 12dp` (`values/dimens.xml:127`),
`home_duration_chip_radius 4dp` (`:132`), `corner_radius 20dp` (`:16`).

### 5.1 `item_channel.xml` — full-width row

- root `padding spacing_md`, `selectableItemBackground`, clickable+focusable (`:7-10`).
- avatar `avatar_medium` **56 (64 / 72)** (`values/dimens.xml:63`, `sw600:47`, `sw720:51`),
  `scaleType centerCrop`, circular crop applied in code (`ChannelAdapter.kt:52-56`),
  placeholder `onboarding_icon_bg`, `importantForAccessibility="no"` (`:19-20`).
- name: `marginStart spacing_md`, `marginEnd spacing_sm`, `text_subtitle` **bold**,
  `?colorOnSurface`, `maxLines 2`, ellipsize end (`:30-37`).
- subscriber count: `marginTop spacing_xs`, `text_body`, **`primary_green` #275E4B**, `maxLines 1`
  (`:48-53`). Text = `channel_subscribers_format` → **"%s subscribers"** (`strings.xml:321`) with
  `CountFormat.compact(subscribers, appLocale)` (`ChannelAdapter.kt:45-49`).
- category `ChipGroup`, `marginTop spacing_xs`, `singleLine` (`:60-68`).

### 5.2 `item_playlist.xml` — full-width row

- root padding: horizontal `spacing_md`, vertical `spacing_sm` (`:8-11`).
- thumbnail: square `playlist_thumbnail_size` **80 (100 / 120)** (`values/dimens.xml:84`,
  `sw600:30`, `sw720:29`), corner radius **12**, elevation 0, stroke 0,
  `centerCrop`, placeholder bg `surface_variant` (`:19-39`).
- title: `marginStart spacing_md`, `text_subtitle` bold, `maxLines 2` (`:46-52`).
- meta: `marginTop spacing_xs`, `home_item_meta_size` **13sp**, `?colorOnSurfaceVariant`,
  `maxLines 1` (`:63-68`).
- `contentDescription` on root = `a11y_playlist_item` → **"Playlist: %1$s, %2$d items"**
  (`strings.xml:384`, set at `PlaylistAdapter.kt:43-47`).

**Localization bug**: `PlaylistAdapter.kt:40` hardcodes `"${playlist.itemCount} items"`. The Featured
screen uses the plural resource `playlist_item_count` → `"%d item"` / `"%d items"`
(`strings.xml:401-404`, `FeaturedListAdapter.kt:127-131`). **iOS must use the plural everywhere.**

### 5.3 `item_video_list.xml` — used by **Featured only**, not by the Videos tab

- root padding horizontal `spacing_md`, vertical `spacing_sm` (`:8-11`).
- thumbnail card: width `video_list_thumbnail_width` **140dp** (`values/dimens.xml:85`, no
  sw600/sw720 override), `dimensionRatio 16:9`, corner radius **12** (`:19-26`).
- duration chip: `bottom|end`, `margin spacing_xs`, padding **6h / 3v**
  (`home_duration_chip_padding_horizontal/vertical`, `values/dimens.xml:133-134`),
  bg `duration_badge_background` = `#CC000000`, radius **4dp** (`drawable/duration_badge_background.xml`),
  `text_duration` **11sp bold**, `?colorOnPrimary` (`:43-57`).
- title: `marginStart spacing_md`, `text_subtitle` bold, `maxLines 2` (`:68-73`).
- meta: `marginTop spacing_xs`, **13sp**, `?colorOnSurfaceVariant`, `maxLines 2` (`:85-90`).

### 5.4 `item_video_grid.xml` — used by the Videos tab

- root padding: `spacing_sm` start/end/top, **`spacing_md` bottom** (`:8-11`).
- thumbnail: full cell width, `dimensionRatio 16:9`, radius **12**, bg `surface_variant` (`:17-44`).
- duration chip anchored `end|bottom` of the thumbnail, `margin spacing_xs`, same 6/3 padding,
  **`@android:color/white`** text here vs `?colorOnPrimary` in the list variant (`:57`).
- **`contentArea` has a FIXED height `home_card_content_height` = 100dp**
  (`values/dimens.xml:159`, `:72`), `marginTop spacing_sm` — this is what keeps grid rows aligned.
  iOS: reserve a fixed-height meta block, do not let the cell size to content.
- title: `home_item_title_size` **15sp bold**, **`minLines 2` and `maxLines 2`** (`:83-89`) — always
  occupies two lines.
- meta: `marginTop spacing_xs`, **13sp**, `maxLines 2` (`:100-105`).
- category `ChipGroup` sits **below** the fixed content area, `marginTop spacing_xs` (`:114-122`).

### 5.5 Category chip inside every cell

Built in code, identical in all four adapters
(`ChannelAdapter.kt:71-79`, `PlaylistAdapter.kt:59-67`, `VideoGridAdapter.kt:71-79`,
`FeaturedListAdapter.kt:102-110,141-149,190-198`):
`isClickable = false`, background `surface_variant` **#E3E9E7**, text colour `primary_green`
**#275E4B**. Container is `singleLine` so overflow is clipped, not wrapped.

Channels only: shows the **first** category plus an overflow marker when
`categories.size > 1` — `category_with_overflow` → **"%1$s +%2$s"** (`strings.xml:325`), count
formatted with `NumberFormat.getNumberInstance(appLocale)` (`ChannelAdapter.kt:60-69`).
`categories ?: [category]` fallback (`:60`).

### 5.6 Text formatting rules (must match exactly)

**Duration** (`VideoGridAdapter.kt:89-98`, `FeaturedListAdapter.kt:205-214`) — `Locale.US` always:
`h > 0 ? "%d:%02d:%02d" : "%d:%02d"`. So `0:07`, `12:05`, `1:02:03`. Not `00:07`.

**Relative upload date** from `uploadedDaysAgo: Int` (`VideoGridAdapter.kt:100-118`):

| Range | Output |
|---|---|
| `<= 0` | `video_uploaded_today` → **"Today"** (`strings.xml:390`) |
| `< 7` | `video_uploaded_days_ago` → "%d day ago" / "%d days ago" (`:391-394`) |
| `< 30` | `time_ago_weeks`, `days / 7` → "%d week ago" / "%d weeks ago" (`:409-412`) |
| `< 365` | `time_ago_months`, `days / 30` → "%d month ago" / "%d months ago" (`:413-416`) |
| else | `time_ago_years`, `days / 365` → "%d year ago" / "%d years ago" (`:417-420`) |

Integer division, no rounding.

**View count**: `CountFormat.compact(viewCount, appLocale)` fed into the plural `video_views` →
"%s view" / "%s views" (`strings.xml:405-408`), with the pluralisation *quantity* deliberately
forced to `other` for any compacted magnitude ≥ 1000 via `compactPluralCount`
(`util/CountFormat.kt:58`, rationale at `:49-57` — Arabic counted-noun agreement). iOS must
replicate: use the compact style for the number **and** the plural-"other" form once ≥ 1000.

**Meta line composition**: `views.isNotEmpty() ? "$views • $timeAgo" : timeAgo`
(`VideoGridAdapter.kt:60-64`) — literal `" • "` separator, views omitted entirely when
`viewCount == nil`.

`CountFormat.compact` uses ICU `CompactDecimalFormat` SHORT with `maximumFractionDigits = 1`
(`util/CountFormat.kt:40-46`) → `1.2K`, `12.7M`. iOS: `Measurement`/`NumberFormatter` with
`.notation = .compactName`, `maximumFractionDigits = 1`.

### 5.7 Diffing

All adapters: `areItemsTheSame` compares `id` only; `areContentsTheSame` compares the whole value
(`ChannelAdapter.kt:88-98`, `PlaylistAdapter.kt:76-86`, `VideoGridAdapter.kt:126-136`).
`FeaturedListAdapter.kt:246-263` additionally returns `false` when the two items are different
subtypes. SwiftUI: `Identifiable` on `id`, `Equatable` on the whole struct.

`ContentListViewModel` returns **all** items and the fragment filters by subtype:
`state.items.filterIsInstance<ContentItem.Channel>()` (`ChannelsFragmentNew.kt:209`),
`.Playlist` (`PlaylistsFragmentNew.kt:199`), `.Video` (`VideosFragmentNew.kt:223`).
Consequence: if the backend ever returns a mixed page for a typed request, the **rendered count can
be smaller than the fetched count** while `hasMoreData` and the autofill progress invariant still
use the *filtered* count (`:226-231`). Keep the filter, but compute the autofill invariant from the
filtered count as Android does.

---

## 6. Filters

### 6.1 `FilterState`

`data/filters/FilterState.kt:7-22`

```
FilterState(category: String? = nil, categoryName: String? = nil,
            videoLength: VideoLength = .any,
            publishedDate: PublishedDate = .any,
            sortOption: SortOption = .default)
  hasActiveFilters: Bool  // :14-15

VideoLength   = ANY | UNDER_FOUR_MIN | FOUR_TO_TWENTY_MIN | OVER_TWENTY_MIN   // :18
PublishedDate = ANY | LAST_24_HOURS | LAST_7_DAYS | LAST_30_DAYS              // :20
SortOption    = DEFAULT | MOST_POPULAR | NEWEST                               // :22
```

Value type — `ContentListViewModel.setFilters` relies on whole-struct equality (`:82`).

### 6.2 `FilterManager` — app-wide singleton, DataStore-backed

`data/filters/FilterManager.kt`

- Publishes `state: StateFlow<FilterState>`, seeded with defaults (`:20-21`), replaced on every
  DataStore emission (`:24-36`). Read errors fall back to `emptyPreferences()` (`:26`) → all defaults.
- Preference keys (**string values, enum `.name`**): `filter_category`, `filter_category_name`,
  `filter_length`, `filter_date`, `filter_sort` (`:82-86`).
- Unknown persisted enum name → the default, not a crash (`enumOrNull`, `:88-90` used at `:31-33`).
  iOS must be equally tolerant of stale `UserDefaults`.
- `setCategory(nil)` **removes both** `filter_category` and `filter_category_name` (`:49-51`).
- `setCategory(id, name = nil)` stores the id and **removes** the name (`:54`) — so the chip falls
  back to showing the raw id (`ViewExtensions.kt:84`).
- `setCategoryAndAwait` is the suspending variant used where the caller must navigate only after the
  write lands (`:43-45`) — the toast-then-pop flow depends on this ordering.

Spec §6 says `FilterState` lives in `UserDefaults` and is shared by Home/Channels/Playlists/Videos
(`2026-08-23-ios-app-design.md:127`) — matches.

### 6.3 **Only `category` is ever mutated**

`grep` over `android/app/src/main` finds **zero** call sites for `setVideoLength`,
`setPublishedDate`, `setSortOption`, or `FilterManager.clearAll()`. `res/menu/filter_menu.xml` is
never inflated. Consequences for iOS:

- **Sort order is always the backend default** — `sort` is never sent.
- Length/date filters are unreachable UI.
- Only the Categories → Subcategories flow writes filter state.

Do not build length/date/sort UI unless explicitly asked; see §10 Q5.

### 6.4 Filter propagation into the three screens

`observeFilters()`, identical shape in all three
(`ChannelsFragmentNew.kt:108-120`, `PlaylistsFragmentNew.kt:107-119`, `VideosFragmentNew.kt:124-136`):

1. `collectLatest` on `filterManager.state`;
2. `autofillHelper.reset()`;
3. `viewModel.setFilters(filterState)` (no-op if unchanged — so the first emission after process
   start does not double-fetch on top of `init`'s load, provided it equals `FilterState()`);
4. update the chip.

**Edge case**: on cold start the VM fires `loadContent()` in `init` with `FilterState()` defaults
(`ContentListViewModel.kt:74`) *before* DataStore has replayed a persisted category. When the
persisted value arrives, `setFilters` sees a difference and **re-fetches**. iOS: either read the
persisted filter synchronously before the first fetch, or accept and mirror the double request.

### 6.5 Categories / Subcategories flow (the only writer)

`CategoriesFragment.kt:55-98`:

- List of top-level categories only — `fetchCategories()` filters `parentId == nil` and computes
  `hasSubcategories` client-side (`RetrofitContentService.kt:89-97`).
- `hasSubcategories` → push Subcategories with `categoryId` + localized `categoryName`
  (`:58-71`; arg names `SubcategoriesFragment.kt:110-111`).
- else → `setCategoryAndAwait(id, localizedName)`, **Toast** `category_filter_applied` →
  **"Filtering by: %1$s"** (`strings.xml:23`), then `navigateUp()` (`:77-85`).
- Failure → Toast `category_filter_error` → **"Failed to apply category filter"** (`strings.xml:25`).
- Load failure → `submitList(emptyList())`, **no error UI at all** (`:113-117`).

`SubcategoriesFragment.kt:52-80`:

- Display name is composed as **`"$categoryName > $subName"`** (`:56`) — this exact string, with
  spaces around `>`, is what the filter chip renders. Spec §6 already notes it
  (`2026-08-23-ios-app-design.md:127`).
- Localized sub-name: `subcategory.localizedNames?[currentLanguage] ?: subcategory.name` (`:55`).
- Then toast, then **`popBackStack(categoriesFragment, inclusive = true)`** (`:68`) — pops *past*
  Categories back to whichever screen opened it. iOS: pop the pushed stack back to the origin route,
  not a single `dismiss`.
- Load failure → empty list, no error UI (`:95-99`).

---

## 7. Featured list screen

Files: `ui/FeaturedListFragment.kt`, `ui/FeaturedListViewModel.kt`,
`ui/adapters/FeaturedListAdapter.kt`, `res/layout/fragment_featured_list.xml`.

### 7.1 Entry and arguments

Nav destination `featuredListFragment`, args `categoryId: String = ""` and
`categoryName: String = ""` (`res/navigation/main_tabs_nav.xml:230-242`).

`categoryId` resolution: empty/nil arg → the hardcoded constant
`FEATURED_CATEGORY_ID = "itirf9pGpAvoBT5VSkEc"` (`FeaturedListViewModel.kt:58-62,199`).
**A production Firestore document id compiled into the app.** iOS must decide: mirror it, move it to
remote config, or accept it. See §10 Q6.

Toolbar title = `categoryName` when non-empty, else `section_featured` → **"Featured"**
(`FeaturedListFragment.kt:59-62`, `fragment_featured_list.xml:18`, `strings.xml:242`).
Back = `navigateUp()` (`:56-58`).

### 7.2 Two display modes, chosen by a probe

`FeaturedListViewModel.loadFeatured()` (`:64-109`):

1. Reset all cursors/collections, `lastLoadFailed = false`, emit `Loading`.
2. **Probe** `fetchHomeFeed(cursor: nil, categoryLimit: 10, contentLimit: 20, category: categoryId)`
   (`:78-83`).
3. `hasSubcategories = sections.any { it.categoryId != categoryId }` (`:85`).
   - **true** → **Sections mode**: store sections + `nextCursor`, emit
     `Sections(sections, isLoadingMore: false)` (`:88-91`).
   - **false** → fall through to flat.
4. Any probe exception (except cancellation) → also fall through to flat (`:95-99`).
5. Flat: `fetchContent(type: .ALL, cursor: nil, pageSize: 50, filters: FilterState(category: categoryId))`
   (`:113-118`) → `FlatList(items, isLoadingMore: false)`.
   Failure here → `FeaturedState.Error(message)` (`:103-106`).

**Note: the flat path builds a *fresh* `FilterState(category:)` — it deliberately ignores the global
filter's length/date/sort** (`:117`, `:173`).

Constants (`:196-199`): `FLAT_PAGE_SIZE = 50`, `SECTION_PAGE_SIZE = 10` (categoryLimit),
`CONTENT_PER_SECTION = 20` (contentLimit).

### 7.3 State shape

```
FeaturedState =
  | Loading                                                       // :188
  | Sections(sections: [HomeSection], isLoadingMore: Bool)         // :189
  | FlatList(items: [ContentItem], isLoadingMore: Bool)            // :190
  | Error(message: String)                                         // :191
```

Private: `flatNextCursor`, `flatItems`, `sectionsNextCursor`, `allSections`, `loadJob`,
`lastLoadFailed` (`:33-44`).

`canLoadMore` is **mode-dependent** (`:46-51`):
`Sections → sectionsNextCursor != nil && !lastLoadFailed`;
`FlatList → flatNextCursor != nil && !lastLoadFailed`; anything else `false`.

### 7.4 Pagination and the retry latch

- Scroll listener: ignore `dy <= 0`; **`viewModel.clearLoadError()` on every downward scroll**
  (`FeaturedListFragment.kt:151`) — manual scrolling is treated as intent to retry
  (`FeaturedListViewModel.kt:53-56`); then `lastVisible >= itemCount - 5 && canLoadMore → loadMore()`
  (`:155-157`). Threshold 5, same as the tabs, but **inline, not a named constant**.
- `loadMore()` dispatches by current mode (`:125-131`).
- Both `loadMoreSections` (`:133-158`) and `loadMoreFlat` (`:160-185`):
  - return early if the cursor is nil or `isLoadingMore` is already true;
  - **cancel `loadJob` and reassign** — same shared-handle hazard as §2.6;
  - emit the current list with `isLoadingMore = true`, then again with `false`;
  - on error: set `lastLoadFailed = true`, **keep the cursor**, re-emit the unchanged list with
    `isLoadingMore = false`. **No user-visible message at all** — the failure is silent.
- **Auto-fill on large screens** (`FeaturedListFragment.kt:193-200` for Sections, `:214-221` for
  FlatList): after `submitList`, `post { if canLoadMore && !rv.canScrollVertically(1) { loadMore() } }`.
  Unlike the tabs there is **no `AutofillPaginationHelper`**: no ≥600dp gate, no 5-attempt cap, no
  progress invariant. The only brake is `lastLoadFailed`. On a large iPad a short page could loop.
  **iOS should reuse the tab screens' guarded autofill here instead.**

### 7.5 Layout and states

`fragment_featured_list.xml`:

- Toolbar `?attr/actionBarSize`, bg `?colorSurface`, elevation `elevation_sm` **2dp**
  (`values/dimens.xml:119`), nav icon `ic_arrow_back`, title style Headline6 (`:10-20`).
- List: `LinearLayoutManager`, always single column (`FeaturedListFragment.kt:145`).
  Padding: top/start/end `spacing_md`, bottom `bottom_nav_height` (`:70-73`).
- `Loading` → centre `ProgressBar`, `marginTop spacing_xxxl` (96/112/128 dp); list hidden;
  error hidden (`FeaturedListFragment.kt:174-179`).
- `Error` → full-screen centred container, padding `spacing_lg`; message text in
  `accent_red`; `MaterialButton` TextButton `retry` → **"Retry"** (`strings.xml:194`),
  `marginTop spacing_md`; tap → `loadFeatured()` (`fragment_featured_list.xml:33-60`,
  `FeaturedListFragment.kt:163-167,223-229`).
- `isLoadingMore` → bottom `ProgressBar`, `indeterminateTint primary_green`,
  `marginBottom bottom_nav_height` (`:80-89`).
- **No empty state.** A successful response with zero items renders a blank screen —
  `state.items` empty just yields an empty list, no branch handles it
  (`FeaturedListFragment.kt:202-222`). iOS should add one; see §10 Q7.
- **No pull-to-refresh.** Recovery is the Retry button, which only appears on terminal `Error`.
- Adapter is swapped in place (`recyclerView.adapter = flatAdapter / sectionAdapter`) when the mode
  changes (`:187-189`, `:209-211`).
- Observation is `repeatOnLifecycle(STARTED)` (`:171`) — unlike the three tabs, which use a plain
  `lifecycleScope.launch` (`ChannelsFragmentNew.kt:184`). iOS: `.task` / `.onDisappear` cancellation.

### 7.6 Sections-mode card widths

`FeaturedListFragment.kt:131-140,241-244`, computed once on `root.post`:

```
cardWidth(screenWidth, n, margin, spacing)
  = ((screenWidth - 2*margin - (n-1)*spacing) / n) * 0.98
```

with `margin = home_horizontal_margin` **16 (24 / 32)** (`values/dimens.xml:124`,
`sw600:56`, `sw720:60`), `spacing = home_card_spacing` **12 (16 / 20)**
(`values/dimens.xml:156`, `sw600:62`, `sw720:66`), and `n` from
`home_cards_visible_videos` **2 / 3 / 5** and `home_cards_visible_channels` **2 / 4 / 6**
(`values/dimens.xml:168-170`, `sw600:65-67`, `sw720:69-71`;
`home_cards_visible_playlists` = **2 / 3 / 5**).
The `* 0.98` is a deliberate peek-the-next-card fudge. Playlist width is **not** set here — only
`videoCardWidth` and `channelCardWidth` (`:138-139`).

Spec §7 lists these as "carousel visible cards ch/pl/vid 2/2/2 → 4/3/3 → 6/5/5"
(`2026-08-23-ios-app-design.md:168`) — matches the resources.

### 7.7 Tap targets

`FeaturedListFragment.handleItemClick` (`:65-104`):

- **Video** → prefetch, then global `playerFragment` with `videoId`, `title`,
  `channelName` (**again `item.category`** — `:74`), `thumbnailUrl`, `description`,
  `durationSeconds`, `viewCount ?: -1L`.
- **Playlist** → global `playlistDetailFragment` with `playlistId`, `playlistTitle`,
  `playlistCategory`, `playlistCount` (`:82-92`) — **more args than the Playlists tab passes**.
- **Channel** → global `channelDetailFragment` with `channelId`, `channelName`,
  `channelAvatarUrl` (**no `excluded` arg** — defaults false via `getBoolean(…, false)`,
  `ChannelDetailFragment.kt:80`).
- **"See all"** on a section header → navigates to `featuredListFragment` **by destination id, not
  action id**, explicitly so it works from Home *and* from Featured itself (comment at `:112-115`),
  passing `categoryId = section.categoryId` and
  `categoryName = section.localizedNames?[locale.language] ?: section.categoryName` (`:120-126`).
  This makes Featured **recursively pushable**. iOS: `Route.featured(mode)` must be pushable onto
  its own stack (spec §6 lists `featured(mode)` — `2026-08-23-ios-app-design.md:121`).

### 7.8 `FeaturedListAdapter` — mixed types, reusing the tab cells

`FeaturedListAdapter.kt:31-56`: view types `CHANNEL = 0`, `PLAYLIST = 1`, `VIDEO = 2`, bound to
`item_channel`, `item_playlist`, **`item_video_list`** (the 140dp-thumb horizontal row, *not*
`item_video_grid`). Binding logic is a copy of the per-type adapters (§5), with the plural fix noted
in §5.2. `layout` preview attribute in the XML says `item_video_grid` (`fragment_featured_list.xml:76`)
but that is `tools:` only — ignore it.

---

## 8. Layout metrics — screen chrome

Search bar (`fragment_simple_list.xml:11-53`, `fragment_channels_new.xml:10-52`):
`paddingStart spacing_md` (16/20/24), `paddingEnd spacing_sm` (8), `paddingTop/Bottom spacing_sm` (8),
`gravity center_vertical`. Text field is `OutlinedBox.Dense`, weight 1. Clear button
`icon_small` **24dp** (`values/dimens.xml:55`), `marginStart spacing_sm`, borderless ripple,
`contentDescription search_clear` → **"Clear search"** (`strings.xml:572`).

List padding:
- Channels (`fragment_channels_new.xml:81-84`): `padding spacing_md` on **all** sides +
  `paddingBottom bottom_nav_height`, `clipToPadding=false`, `overScrollMode=never`.
- Playlists/Videos (`fragment_simple_list.xml:82-85`): **`paddingTop spacing_sm` only**, no
  horizontal padding (the item layouts supply it), `paddingBottom bottom_nav_height`.

`bottom_nav_height` = **72dp** on phones (`values/dimens.xml:26`) and **0dp** on sw600/sw720
(`values-sw600dp/dimens.xml:6`, `values-sw720dp/dimens.xml:6`, "Use NavigationRail instead").
iOS: this is the tab-bar inset — use safe-area insets, and on iPad regular width with the
`sidebarAdaptable` sidebar the inset is 0, matching Android.

Bottom pagination spinner: `gravity bottom|center_horizontal`, `marginBottom bottom_nav_height`,
indeterminate, `contentDescription loading_more` → **"Loading…"** (`strings.xml:348`)
(`fragment_simple_list.xml:137-146`). (Distinct string `home_loading_more` = "Loading more…",
`strings.xml:312`, is not used here.)

Touch targets: `touch_target_min` **48dp**, `touch_target_button` **56dp**
(`values/dimens.xml:20-22`).

---

## 9. Behavioural checklist for the iOS implementer

Ordered as a contract; each line is testable.

**Data**
1. `GET /api/v1/content` with `type`/`cursor`/`limit`/`category`/`length`/`date`/`sort`/`q`; omit any
   param whose value is nil/default/blank.
2. `limit = 20` on tabs; `limit = 50` on Featured flat; Featured probe uses
   `/api/v1/home?categoryLimit=10&contentLimit=20&category=<id>`.
3. `hasMore = (nextCursor != nil)`.

**Loading**
4. Fetch on first appearance, without waiting for user action.
5. Filter change → full reset + refetch, but only when the filter value actually differs.
6. Search change → 300 ms debounce (Android's effective 600 ms is an artefact — deviate deliberately);
   clear button and keyboard-submit bypass the debounce.
7. Pull-to-refresh resets the cursor and clears items; disabled while search is non-empty; ignored
   while a refresh is in flight.
8. Load more when the last visible row is within **5** of the end **and** `!isLoadingMore &&
   !isRefreshing && hasMore`; never on upward scroll.
9. Autofill when the content does not fill the viewport, with all six guards from §4.3(b).

**States**
10. Initial load → 6-row skeleton, no list, no empty state.
11. Refresh → keep list visible with the refresh indicator; hide the empty state.
12. Pagination → bottom spinner only.
13. Success + empty → icon/title/subtitle per §4.6; search variant when the query is non-empty.
14. Initial-load error → keep the skeleton, rely on the global offline banner (requires the banner
    to exist).
15. Error with existing content → keep the list and show a transient message (unify across all three;
    Android only does this on Videos).
16. Pagination error → keep items, keep the cursor, show a transient message once.

**Navigation**
17. Channel row → channel detail with `id`, `name`, `avatarUrl`, `excluded = false`.
18. Playlist row → playlist detail with `id`, `title` (+ `category`, `count` from Featured).
19. Video cell → player with the 7 metadata args; `viewCount` sentinel `-1` when unknown.
20. Categories FAB (Channels; consider adding to Playlists/Videos) → Categories.
21. Category with subcategories → Subcategories; leaf category → set filter, toast
    "Filtering by: X", pop to origin (past Categories).
22. Featured "See all" → another Featured, pushable onto its own stack.

---

## 10. Open questions

**Q1 — Channels/Playlists column count on compact width.** Spec §7 says "2/3/4 by width class"
(`2026-08-23-ios-app-design.md:167`); Android is **1/3/4** — phones show a full-width row
(`ChannelsFragmentNew.kt:149-154`, `PlaylistsFragmentNew.kt:140-145`). `item_channel` (56dp circle +
2-line name + subs + chip) and `item_playlist` (80dp square + title + count + chip) are row layouts;
2-up would need new cells. Confirm: keep Android's 1-column rows on iPhone, or build grid cells?

**Q2 — Categories FAB on Playlists and Videos.** Only Channels has one
(`fragment_channels_new.xml:148-158`; `fragment_simple_list.xml` has none). Add to all three on iOS
(a toolbar button suits iOS better than a FAB), or mirror the asymmetry?

**Q3 — Skeleton shimmer.** Android's `skeleton_shimmer` is a static flat shape
(`drawable/skeleton_shimmer.xml`); spec §7 defines both `skeleton` and `skeletonShimmer` tokens
(`2026-08-23-ios-app-design.md:157`). Animate on iOS, or match Android's static blocks?
Also: should the skeleton mirror the grid shape (2–8 columns) instead of a fixed 6-row list?

**Q4 — `channelName` populated from `category`.** `VideosFragmentNew.kt:185` and
`FeaturedListFragment.kt:74` pass `item.category` as the player's `channelName`, ignoring
`ContentItem.Video.channelName` (`ContentItem.kt:13`). Looks like a bug. Fix on iOS (fall back to
`category` only when `channelName` is nil), or replicate for parity?

**Q5 — Length / date / sort filters.** Persisted and wired to query params, but no UI mutates them
(§6.3), so sort is always the backend default. Spec §6 lists `FilterState` as "category id + name,
length, date, sort" (`2026-08-23-ios-app-design.md:127`). Build the UI on iOS (a `.searchable`
scope bar or a filter sheet is cheap), or ship the same dormant fields?

**Q6 — `FEATURED_CATEGORY_ID = "itirf9pGpAvoBT5VSkEc"`** hardcoded at
`FeaturedListViewModel.kt:199`. Hardcode on iOS too, source it from remote config, or add a backend
endpoint?

**Q7 — Featured has no empty state and no pull-to-refresh.** Zero items = blank screen
(`FeaturedListFragment.kt:202-222`); load-more failures are entirely silent
(`FeaturedListViewModel.kt:151-156,178-183`). Add both on iOS?

**Q8 — Dead paging stack.** `CursorPagingSource` / `DefaultContentPagingRepository` /
`ContentPagingRepository` have no callers (§3.4) yet were named in the task scope. Confirm the iOS
port skips them entirely and models pagination on `ContentListViewModel`.

**Q9 — Search debounce.** 300 ms (fragment) + 300 ms (VM) = ~600 ms
(`ChannelsFragmentNew.kt:86` + `ContentListViewModel.kt:61`). Single 300 ms on iOS?
And should a minimum character count be introduced (Android has none)?

**Q10 — Empty state blocks refresh.** Android hides the list when empty
(`ChannelsFragmentNew.kt:239-240`), so pull-to-refresh is unreachable. Put the iOS empty state inside
the refreshable scroll view?
