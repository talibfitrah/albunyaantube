# Phase 1 research — Search, Categories, Subcategories, Featured "See all"

Behavioural contract for the SwiftUI port. Every claim cites `file:line`. Paths relative to repo root
`/Users/farouqabouumar/Development/albunyaantube`.

Design-system anchors read first: spec §6 Navigation and §7 Design system
(`docs/superpowers/specs/2026-08-23-ios-app-design.md:103-171`). Routes `search`, `categories`,
`subcategories(parent)`, `featured(mode)` are already declared there
(`docs/superpowers/specs/2026-08-23-ios-app-design.md:113`), as is the FilterState contract:
"category chosen from Categories/Subcategories (`Parent > Sub`, pops back to origin)"
(`docs/superpowers/specs/2026-08-23-ios-app-design.md:123`).

---

## 1. Search

### 1.1 Entry / exit

- **Single entry point.** Only the Home screen's search button navigates to search:
  `findNavController().navigate(R.id.searchFragment)` — `android/app/src/main/java/com/albunyaan/tube/ui/HomeFragment.kt:215`.
  `searchButton` exists only in `res/layout/fragment_home_new.xml`, `res/layout-sw600dp/fragment_home_new.xml`,
  `res/layout-sw720dp/fragment_home_new.xml`. There is no search field on Channels/Playlists/Videos/Me.
- **Destination has no arguments**: `res/navigation/main_tabs_nav.xml:226-228` (`android:label="Search"`).
- **Back**: toolbar navigation icon → `findNavController().navigateUp()` —
  `android/app/src/main/java/com/albunyaan/tube/ui/SearchFragment.kt:83-85`.
- **Exit on result tap** — three global destinations, no back-stack popping
  (`android/app/src/main/java/com/albunyaan/tube/ui/SearchFragment.kt:283-322`):
  - Video → `action_global_playerFragment` with args `videoId`, `title`, `channelName` (= `item.category`, **not**
    `item.channelName`), `thumbnailUrl`, `description`, `durationSeconds`, `viewCount` (`-1L` when null)
    (`SearchFragment.kt:287-298`). A prefetch is fired first: `prefetchService.triggerPrefetch(item.id, …)`
    (`SearchFragment.kt:286`).
  - Channel → `action_global_channelDetailFragment` with `ARG_CHANNEL_ID`, `ARG_CHANNEL_NAME`,
    `ARG_CHANNEL_AVATAR_URL` (`SearchFragment.kt:300-309`).
  - Playlist → `action_global_playlistDetailFragment` with `playlistId`, `playlistTitle`, `playlistCategory`,
    `playlistCount` (`SearchFragment.kt:310-320`).
  - iOS note: this is the same 7-of-12 `PlayerArgs` subset the spec calls the "metadata fast path"
    (`docs/superpowers/specs/2026-08-23-ios-app-design.md:113`); `playlistId`/`startIndex`/`shuffled`/
    `targetVideoId`/`channelId` are **not** passed from search.

### 1.2 Search field behaviour

- Auto-focus on appear: `requestFocus()` + `isIconified = false` — `SearchFragment.kt:91-92`. iOS: `@FocusState`
  focused on `.onAppear` so the keyboard is up immediately.
- Hint/placeholder: `search_hint` = `"Search…"` (U+2026 ellipsis) —
  `android/app/src/main/res/values/strings.xml:571`.
- **Debounce = 500 ms**, min length = **2 characters**, cancel-previous on each keystroke
  (`SearchFragment.kt:105-120`):
  1. `searchJob?.cancel()` on every text change (`SearchFragment.kt:107`).
  2. If text is null/blank → immediately `showSearchHistory()`, i.e. `viewModel.clearResults()` → `Empty`
     (`SearchFragment.kt:109-110`, `:216-218`).
  3. Else launch a job: `delay(500)`, then `if (newText.length >= 2) performSearch(newText)`
     (`SearchFragment.kt:112-117`).
- **Submit** (keyboard return): runs the search **immediately, no debounce, no min-length**, and is the *only*
  path that writes history — `SearchFragment.kt:95-103`. `it.isNotBlank()` is the only guard, so a 1-character
  submit both searches and is stored.
- **Edge case to replicate or deliberately fix:** a 1-character non-blank query (`"a"`) cancels the prior job,
  starts a new one, waits 500 ms and then does *nothing* — the previous state (results / no-results / error)
  stays on screen and history is **not** re-shown (`SearchFragment.kt:109-118`). Only fully clearing the field
  restores history. Recommend matching Android unless the parent decides otherwise; flag as an open question.
- No result-list debounce cancellation on navigation-away beyond `onDestroyView` → `searchJob?.cancel()`
  (`SearchFragment.kt:327`).

### 1.3 Search history store

- Backing store: `SharedPreferences`, file name **`"search_prefs"`**, key **`"search_history"`**, max
  **10** entries — `SearchFragment.kt:56-58`.
- Serialisation: a single `String`, entries joined with the **`|`** character —
  `SearchFragment.kt:247`, `:256`. Read back with `split("|").filter { it.isNotBlank() }`
  (`SearchFragment.kt:226`). **Consequence: a query containing `|` is silently split into multiple entries on
  the next read.** iOS should use `UserDefaults` with a `[String]` array (no delimiter bug) but keep the same
  key names for parity/debuggability; note the migration is one-way (no Android data to import).
- Insert semantics (`saveToHistory`, `SearchFragment.kt:233-250`):
  1. `remove(query)` — exact-string dedupe, case-sensitive, no trimming.
  2. `add(0, query)` — most-recent-first.
  3. `while (size > 10) removeLast()` — drop oldest.
  4. Persist, then `submitList(copy)`.
- Delete one (`removeFromHistory`, `SearchFragment.kt:252-260`): remove, persist, resubmit list, then
  `showSearchHistory()` → **clears any showing results and returns the screen to the history/empty state**.
  The search field text is *not* cleared, so the field can still hold a query while results are gone.
- Clear all (`clearHistory`, `SearchFragment.kt:262-270`): `clear()`, `prefs.edit().remove(KEY)` (removes the
  key rather than writing `""`), resubmit empty list, `showSearchHistory()`. **No confirmation dialog.**
- Load on appear: `loadSearchHistory()` before `observeSearchResults()` — `SearchFragment.kt:78-79`.
- Tapping a history row: `binding?.searchView?.setQuery(query, true)` — `SearchFragment.kt:130-132`. The `true`
  means *submit*, so it runs the search **and** re-saves the term (bumping it to position 0).

### 1.4 Screen states and layout metrics

Layout: `res/layout/fragment_search.xml`; tablet variant `res/layout-sw600dp/fragment_search.xml` (identical
except the content column gets `paddingStart/End = spacing_lg`, `res/layout-sw600dp/fragment_search.xml:44-45`).

- Root background `@color/background_gray`; AppBar background pure white with `elevation 2dp`
  (`res/layout/fragment_search.xml:6,10-12`).
- Toolbar height `?attr/actionBarSize` (56 dp phone) with back chevron; `SearchView` fills the toolbar,
  `iconifiedByDefault=false`, transparent background (`res/layout/fragment_search.xml:16-27`).
- Scroll container is a `NestedScrollView` with `paddingBottom = bottom_nav_height` (72 dp phone,
  **0 dp** on sw600 because tablets use a NavigationRail) — `res/layout/fragment_search.xml:34`,
  `res/values/dimens.xml:26`, `res/values-sw600dp/dimens.xml:6`.

Four mutually-exclusive regions, driven by `SearchState` (`SearchFragment.kt:167-214`):

| `SearchState` | loadingState | emptyState | searchHistorySection | searchResultsList |
|---|---|---|---|---|
| `Empty` | gone | gone | visible **iff** `searchHistory.isNotEmpty()` | gone |
| `Loading` | visible | gone | gone | gone |
| `Success` | gone | gone | gone | visible |
| `NoResults(query)` | gone | visible | gone | gone |
| `Error(message)` | gone | visible | gone | gone |

- **`Empty` with no history → nothing at all is shown** (a blank screen under the search bar). There is no
  "start typing" zero state (`SearchFragment.kt:171-178`). iOS should consider a real `EmptyState`; call it out
  as a deliberate improvement, not silent drift.
- **Loading**: centred column, `padding = spacing_xl` (32 dp phone / 48 dp sw600), indeterminate
  `ProgressBar` sized `icon_large` (48 dp phone / 56 dp sw600) tinted `@color/primary_green`, `spacing_md`
  (16/20 dp) gap, label `search_loading` = `"Searching…"` at 14 sp secondary
  (`res/layout/fragment_search.xml:99-121`, `res/values/dimens.xml:57`, `res/values-sw600dp/dimens.xml:43`,
  `res/values/strings.xml:580`).
- **Empty/error block**: centred column, `padding = spacing_xl`, `ic_search` at `icon_xlarge` **96 dp** with
  `alpha 0.3` tinted secondary, `spacing_md` gap, title 16 sp bold primary, `spacing_sm` (8 dp) gap, message
  14 sp secondary centred (`res/layout/fragment_search.xml:124-161`, `res/values/dimens.xml:58`).
  Spec §7 `EmptyState` is 96 pt icon + 20 bold title; **Android search uses 16 sp bold here, not 20** —
  reconcile toward the spec token, noting the drift.
- **No results** copy: title `search_no_results` = `"No results found"`; message
  `search_try_different` = `"Try different keywords for \"%1$s\""` interpolated with the *query*
  (`SearchFragment.kt:200-201`, `res/values/strings.xml:575-576`). The layout's design-time default message is
  `search_try_different_hint` = `"Try different keywords"` (`res/values/strings.xml:577`), used only as the XML
  default.
- **Error** copy: title `error_title` = `"Error"`, message `search_error_generic` =
  `"Search failed. Please try again."` — the real exception message is only `Log.e`'d, never shown
  (`SearchFragment.kt:206-210`, `:272-281`, `res/values/strings.xml:579`, `:581`).
  `search_error` = `"Search failed: %1$s"` (`res/values/strings.xml:578`) exists but is **dead** — do not port.
  **There is no retry button on the search error state** — the user must edit the query. Divergence candidate.
- No toast, no snackbar, no dialog anywhere in the search flow.

### 1.5 Result list — no grouping, no paging

- **Results are a flat, heterogeneous `LinearLayoutManager` list in backend order** — `SearchResultsAdapter`
  picks a row layout per item type via `getItemViewType`
  (`android/app/src/main/java/com/albunyaan/tube/ui/SearchResultsAdapter.kt:32-38`). There are **no section
  headers, no "Channels / Playlists / Videos" grouping, and no type filter chips** on Android. Any grouping in
  the visual output is an artefact of the backend's ordering (see §1.7).
- **No divider decoration** on the results list (item layouts carry their own spacing) —
  `SearchFragment.kt:159`. The *history* list does get a `DividerItemDecoration` (`SearchFragment.kt:141`).
- **No pagination.** One request, `limit = 50`, no cursor, no infinite scroll, no pull-to-refresh
  (`android/app/src/main/java/com/albunyaan/tube/ui/SearchViewModel.kt:37`). iOS: no `onScrollGeometryChange`
  auto-fill needed here, unlike the grid screens in spec §7.
- Predictive prefetch (feature-flagged): `PredictivePrefetchController` attached to the results
  `RecyclerView`; resolves a position to a video id only for `ContentItem.Video`
  (`SearchFragment.kt:68-77`). Detached in `onDestroyView` (`SearchFragment.kt:325-326`). Optional on iOS.

Row layouts reused verbatim from Featured (`SearchResultsAdapter.kt:22-25`), so iOS reuses spec §7
`VideoRow` / `ChannelRow` / `PlaylistRow`:

- **Video row** (`res/layout/item_video_list.xml`): row padding `spacing_md` horizontal / `spacing_sm`
  vertical (16/8 dp); thumbnail `video_list_thumbnail_width` **140 dp**, corner radius
  `home_thumbnail_corner_radius` **12 dp**, 16:9; duration chip `text_duration` **11 sp** with
  6 dp/3 dp padding and `spacing_xs` margin; title `text_subtitle` **16 sp** at `spacing_md` start margin;
  meta `home_item_meta_size` **13 sp**; category chip below at `spacing_xs` top margin
  (`res/layout/item_video_list.xml:6-101`, `res/values/dimens.xml:85,127,133-134,141,164,195`).
  `text_subtitle` becomes 18 sp on sw720 (`res/values-sw720dp/dimens.xml:100`).
- **Channel row** (`res/layout/item_channel.xml`): `padding = spacing_md`; avatar `avatar_medium`
  **56 / 64 / 72 dp** (phone / sw600 / sw720), circle-cropped, placeholder `onboarding_icon_bg`; name
  16 sp; subscriber line `text_body` **14 sp** (16 sp on sw720)
  (`res/layout/item_channel.xml:5-64`, `res/values/dimens.xml:63`, `res/values-sw600dp/dimens.xml:47`,
  `res/values-sw720dp/dimens.xml:51,101`, `SearchResultsAdapter.kt:86-94`).
- **Playlist row** (`res/layout/item_playlist.xml`): padding 16/8 dp; square thumbnail
  `playlist_thumbnail_size` **80 / 100 / 120 dp**, radius 12 dp; title 16 sp; meta 13 sp
  (`res/layout/item_playlist.xml:6-79`, `res/values/dimens.xml:84`, `res/values-sw600dp/dimens.xml:30`,
  `res/values-sw720dp/dimens.xml:29`).

Per-row content rules (all in `SearchResultsAdapter.kt`):

- Every row appends exactly **one** `Chip` to `categoryChipsContainer`, background `@color/surface_variant`,
  text colour `@color/primary_green`, non-clickable — matches spec §7 `CategoryChip`
  (`SearchResultsAdapter.kt:108-116`, `:146-154`, `:201-209`).
- Channel chip text: first category; if `categories.size - 1 > 0` uses
  `category_with_overflow` = `"%1$s +%2$s"` with the overflow count formatted via
  `NumberFormat.getNumberInstance(appLocale)` (`SearchResultsAdapter.kt:96-107`,
  `res/values/strings.xml:325`). **In practice search never triggers this** — the mapper always sets
  `categories = null` for `ContentItemDto` (`data/model/mappers/ApiMappers.kt:58-59`), so the chip is always the
  single `category` string.
- Subscribers: `CountFormat.compact(subscribers, appLocale)` into
  `channel_subscribers_format` (`SearchResultsAdapter.kt:79-84`).
- Playlist meta: `R.plurals.playlist_item_count` quantity string (`SearchResultsAdapter.kt:133-137`).
- Video meta: `"<views> • <timeAgo>"`, or just `<timeAgo>` when `viewCount == null`
  (`SearchResultsAdapter.kt:177-192`). Views use `CountFormat.compact` + `R.plurals.video_views` with the
  quantity clamped to `Int.MAX_VALUE` (`SearchResultsAdapter.kt:177-184`, `:253-259`).
- Duration format: `H:MM:SS` when ≥1 h else `M:SS`, forced `Locale.US` digits
  (`SearchResultsAdapter.kt:216-225`) — note this bypasses locale digit shaping even in Arabic.
- Time-ago ladder: `<= 0` → `video_uploaded_today`; `< 7` → days plural; `< 30` → `daysAgo / 7` weeks;
  `< 365` → `daysAgo / 30` months; else `daysAgo / 365` years (`SearchResultsAdapter.kt:227-245`).
- Diffing: identity by `id` **within the same type**; cross-type same-id pairs are treated as different items
  (`SearchResultsAdapter.kt:261-272`).

### 1.6 History row layout

`res/layout/item_search_history.xml`: horizontal row, `minHeight 48dp`, padding 16 dp horizontal / 12 dp
vertical; leading 24 dp history glyph tinted secondary with 16 dp end margin and
`importantForAccessibility="no"`; single-line ellipsised label at 14 sp; trailing 48×48 dp delete button with
12 dp padding, borderless ripple (`res/layout/item_search_history.xml:11-49`). iOS: `Image(systemName:
"clock.arrow.circlepath")` + trailing `xmark` button (or a swipe-to-delete `.swipeActions` — but keep the
always-visible button for parity with the a11y label below).

Section header (`res/layout/fragment_search.xml:52-81`): `padding = spacing_md` (16/20 dp) row; left label
`search_recent` = `"Recent searches"` at 14 sp bold secondary, `textAlignment=viewStart` (RTL-aware); right
`clearHistoryButton` label `search_clear_history` = `"Clear"` at 14 sp `@color/primary_green`,
`padding = spacing_sm` (`res/values/strings.xml:573-574`).

Accessibility strings to port:
- Row content description `a11y_search_history` = `"Recent search: %1$s"` applied to the whole row
  (`android/app/src/main/java/com/albunyaan/tube/ui/SearchHistoryAdapter.kt:38-42`,
  `res/values/strings.xml:385`).
- Delete button `cd_delete_search_history` = `"Delete search history item"`
  (`res/layout/item_search_history.xml:48`, `res/values/strings.xml:367`).
- `search_clear` = `"Clear search"` (`res/values/strings.xml:572`) is defined but unreferenced in the search
  layouts — it belongs to the platform `SearchView`'s built-in clear affordance. iOS gets this free from
  `.searchable`, so map it there.

### 1.7 ViewModel state shape and backend call

`android/app/src/main/java/com/albunyaan/tube/ui/SearchViewModel.kt:24-63`:

```
StateFlow<SearchState>, initial = Empty
sealed SearchState:
  Empty
  Loading
  Success(results: List<ContentItem>)
  NoResults(query: String)
  Error(message: String)
```

- `search(query)`: blank → `Empty` and return, no request (`SearchViewModel.kt:28-31`). Otherwise
  `Loading` → suspend call → `NoResults(query)` when the list is empty, else `Success`
  (`SearchViewModel.kt:33-45`). Any `Exception` → `Error(e.message ?: "Unknown error")`
  (`SearchViewModel.kt:46-49`).
- **No in-flight cancellation inside the ViewModel** — each `search()` starts a fresh `viewModelScope.launch`
  with no job handle, so two overlapping searches can land out of order and the *later-returning* one wins.
  The Fragment's 500 ms debounce is the only serialisation. iOS should hold the `Task` and cancel the previous
  one (structured concurrency makes this free); note it as a deliberate correctness fix.
- `clearResults()` → `Empty` (`SearchViewModel.kt:53-55`).

**Backend call.** `contentService.search(query = query, type = null, limit = 50)`
(`SearchViewModel.kt:37`) →
`android/app/src/main/java/com/albunyaan/tube/data/source/RetrofitContentService.kt:83-87` →
`GET api/v1/search` with query params `q`, `type`, `limit`
(`android/app/src/main/java/com/albunyaan/tube/data/source/api/ContentApi.kt:31-36`).

- Android always sends `type=null` (Retrofit omits a null `@Query`), so the wire request is
  **`GET /api/v1/search?q=<raw query>&limit=50`**.
- Response: a bare JSON **array** of `ContentItemDto` (not a `CursorPage`) — `ContentApi.kt:36`.
- Mapping `ContentItemDto` → `ContentItem` (`data/model/mappers/ApiMappers.kt:36-68`):
  - `VIDEO` → `title ?: ""`, `category ?: "General"`, `durationSeconds ?: 0`, `uploadedDaysAgo ?: 0`,
    `description ?: ""`, `thumbnailUrl`, `viewCount`, `channelName = channelTitle`.
  - `CHANNEL` → `name ?: title ?: ""`, `category ?: "General"`, `subscribers?.toInt() ?: 0`,
    `videoCount`, `categories = null` (hard-coded).
  - `PLAYLIST` → `title ?: ""`, `category ?: "General"`, `itemCount ?: 0`.
  - The `"General"` fallback is what the category chip shows for uncategorised results.

**Server contract** (`backend/src/main/java/com/albunyaan/tube/controller/PublicContentController.java:168-176`):

- `q` **required**; `type` optional; `limit` default 20, clamped to `[1, 50]` (`:173`).
- Service `search(query, type, limit)`
  (`backend/src/main/java/com/albunyaan/tube/service/PublicContentService.java:1042-1064`):
  - null/blank `q` → empty list (`:1049-1051`).
  - Query is trimmed + `toLowerCase(Locale.ROOT)` (`:1053`).
  - **YouTube URL/ID fast path first**: `parseYouTubeIdentifier(query)`; if it parses, a single-document lookup
    by YouTube id is done and returns 0 or 1 item, honouring `type` (`:1055-1058`, `:1071-1104`). Pasting a
    YouTube link into the iOS search field must behave identically.
  - Otherwise, text search only when the normalized query is **≥ 2 characters**; shorter → empty list
    (`:1058-1061`).
  - Response cached (`@Cacheable`) keyed on `trimmed+lowercased q` + `type` + `limit`, only when
    `q.trim().length() >= 2` (`:1039-1041`).
- **Result ordering explains the apparent "grouping"** (`PublicContentService.java:1114-1147`): for
  `type == null` (or `"ALL"`) the server appends **channels, then playlists, then videos, then stream results**,
  each capped at `limit / 3` (`= 16` for `limit=50`), videos taking the remainder, stream results de-duplicated
  by id and capped at `limit / 2`, and the whole list truncated to `limit`. So iOS gets a type-ordered flat
  array for free — do **not** re-sort or re-group client side or the ordering intent is lost.
- Text matching is prefix-on-`nameLower` + legacy `name` + exact-keyword, with a bounded full-scan fallback
  when all indexed queries come back empty (`PublicContentService.java:1168-1230`). Relevant to iOS only as
  latency expectation: a cold/legacy query can be slow, so the 500 ms debounce + a visible `Loading` state both
  matter.

---

## 2. Categories

### 2.1 Entry / exit

- From **Home**: tapping the category pill card →
  `action_homeFragment_to_categoriesFragment`
  (`android/app/src/main/java/com/albunyaan/tube/ui/HomeFragment.kt:196-199`; action + shared enter/exit anims
  at `res/navigation/main_tabs_nav.xml:12-18`).
- From **Channels**: a FAB (`categoriesFab`, `res/layout/fragment_channels_new.xml:149`) navigating by
  destination id, not action: `findNavController().navigate(R.id.categoriesFragment)`
  (`android/app/src/main/java/com/albunyaan/tube/ui/ChannelsFragmentNew.kt:122-126`).
- Destination + single action:
  `res/navigation/main_tabs_nav.xml:171-178` (`categoriesFragment` → `action_categoriesFragment_to_subcategoriesFragment`).
- Back: toolbar nav icon → `navigateUp()` (`ui/categories/CategoriesFragment.kt:46-48`).
- Home's category pill also carries a **clear** button that resets the filter to null with no confirmation and
  no toast (`HomeFragment.kt:201-206`).

### 2.2 Layout metrics

`res/layout/fragment_categories.xml`:
- Root background `?attr/colorSurfaceVariant`; `MaterialToolbar` at `?attr/actionBarSize`, background
  `?attr/colorSurface`, `elevation_sm` **2 dp**, title `@string/categories` = `"Categories"`, style
  `TextAppearance.MaterialComponents.Headline6` (`res/layout/fragment_categories.xml:7-19`,
  `res/values/strings.xml:255`, `res/values/dimens.xml:119`).
- List: vertical `LinearLayoutManager`, padding top/start/end `spacing_md` (16 dp phone / 20 dp sw600),
  bottom `bottom_nav_height` (72 / 0 dp), `clipToPadding=false`, `overScrollMode=never`
  (`res/layout/fragment_categories.xml:22-31`, `ui/categories/CategoriesFragment.kt:100-103`).

Row (`res/layout/item_category.xml`) — spec §7 has no exact analogue; treat as a card-style
`NavigationLink` row:
- `MaterialCardView`, `layout_marginBottom = spacing_md` (16 dp), corner radius
  `corner_radius_medium` **16 dp**, `cardElevation 0dp`, `strokeWidth 0dp`, background **hard-coded
  `@android:color/white`** (`res/layout/item_category.xml:2-15`). **Dark-mode bug: the card stays white in
  night mode** — on iOS use the `homeCard` token from spec §7 (`#FFFFFF` / `#1A231F`) instead.
- Inner padding `spacing_lg` **24 dp** (32 dp sw600, `res/values-sw600dp/dimens.xml:37`)
  (`res/layout/item_category.xml:20`).
- Label 16 sp, `textAlignment=viewStart`, colour **hard-coded `@android:color/black`** — same dark-mode
  problem; use `textPrimary` (`res/layout/item_category.xml:37-42`).
- Trailing chevron `ic_chevron_right` 24×24 dp tinted `@color/icon_gray`, **visible only when the category has
  subcategories** (`res/layout/item_category.xml:45-56`, `ui/categories/CategoryAdapter.kt:47-51`).
  iOS: `chevron.forward` per spec §7's direction-sensitive icon rule.
- **The emoji icon is deliberately hidden** in this list: `binding.categoryIcon.visibility = View.GONE` with the
  rationale comment "Icons are surfaced on the home screen section headers, not in the categories list — keep
  this row clean (icon + label is redundant here and crowds long localized names)"
  (`ui/categories/CategoryAdapter.kt:41-44`). Port the decision, not just the field. The icon *is* shown on
  home section headers at 20 sp (`res/layout/item_home_section.xml:19-30`,
  `ui/adapters/HomeSectionAdapter.kt:73-79`).
- Root has `layoutDirection="locale"` (`res/layout/item_category.xml:8`) — RTL mirroring is expected.
- Diffing by `id`, contents by data-class equality (`ui/categories/CategoryAdapter.kt:60-68`).

### 2.3 Localisation of category names

- Display name = `category.localizedNames?.get(currentLocaleLanguage) ?: category.name`
  (`ui/categories/CategoryAdapter.kt:37-39`). The key is the **ISO-639-1 language code only**
  (`Locale.language`), not a BCP-47 tag — `locale/LocaleManager.kt:157-172`.
- Supported languages: `"en"`, `"ar"`, `"nl"` (`locale/LocaleManager.kt:38-42`); `"system"` resolves to one of
  them. iOS equivalent: `Locale.current.language.languageCode?.identifier`.
- The **same** resolution is repeated at three call sites and must stay consistent:
  `CategoryAdapter.kt:37-39`, `CategoriesFragment.kt:57-60` (navigation arg), `CategoriesFragment.kt:74`
  (filter label), `SubcategoriesFragment.kt:54-55`, `HomeSectionAdapter.kt:67-70`,
  `FeaturedListFragment.kt:118-120`. On iOS extract one `Category.displayName(for:)` helper.

### 2.4 Loading / error / empty

`ui/categories/CategoriesFragment.kt:106-119`:

- Fire-and-forget on `onViewCreated`; **no loading indicator, no skeleton, no error UI, no retry, no
  pull-to-refresh**.
- Failure path is `Log.e` + `adapter.submitList(emptyList())` (`:113-117`) — the user sees an empty screen
  indistinguishable from "no categories". Comment in-code even says "Show error state or fallback to empty
  list" (`:115`). **Recommend iOS adds a real `ErrorState` with retry (spec §7 `ErrorState`, retry button
  56/56/64) and an `EmptyState`; flag as an intentional improvement.**
- There is no ViewModel — the fragment injects `ContentService` and `FilterManager` directly
  (`CategoriesFragment.kt:28-33`). For iOS define a small `@Observable` model with
  `enum State { loading, loaded([Category]), failed(Error) }`.

### 2.5 Tap behaviour

`ui/categories/CategoriesFragment.kt:55-98`:

- **Has subcategories** → push `subcategories`, passing `categoryId` and the **already-localised**
  `categoryName` (`:58-71`). Args are declared required strings
  (`res/navigation/main_tabs_nav.xml:180-190`). The navigate call is wrapped in try/catch that only logs —
  a failed push is silent (`:65-71`).
- **No subcategories** → apply the filter directly (`:72-97`):
  1. `filterManager.setCategoryAndAwait(category.id, displayName)` — suspends until the DataStore write
     completes (`data/filters/FilterManager.kt:43-57`). Note the stored value is the **category id**, and the
     name is stored separately under `KEY_CATEGORY_NAME`; passing a null/empty id removes both keys
     (`FilterManager.kt:48-56`).
  2. **Toast** `category_filter_applied` = `"Filtering by: %1$s"` with the localised display name,
     `Toast.LENGTH_SHORT` (`:79-83`, `res/values/strings.xml:23`).
  3. `findNavController().navigateUp()` — pops back to whichever screen opened Categories
     (`:85`), guarded by `if (!isAdded || view == null) return@launch` (`:84`).
  4. On exception: **toast** `category_filter_error` = `"Failed to apply category filter"`, and the screen
     stays put (`:86-95`, `res/values/strings.xml:25`).
- iOS has no toasts. Spec §6 lists no toast component. Recommend a brief non-modal overlay (or rely on the
  Home category pill visibly updating on return, which is the actual state change) — decision needed; listed as
  an open question.

### 2.6 Backend: `GET /api/v1/categories`

- Retrofit: `@GET("api/v1/categories") suspend fun fetchCategories(): List<CategoryResponse>` — **no
  parameters, no pagination** (`data/source/api/ContentApi.kt:28-29`).
- The client fetches the **entire flat list once and derives the tree in memory**
  (`data/source/RetrofitContentService.kt:89-104`):
  - `fetchCategories()` → keep `parentId == null`, and for each compute
    `hasSubcategories = response.any { it.parentId == cat.id }` (`:89-97`).
  - `fetchSubcategories(parentId)` → **re-fetches the whole list** and filters `parentId == parentId`
    (`:99-103`). Two full round-trips for one drill-down. iOS: fetch once, cache the array, derive both levels
    (the response is cached server-side for an hour anyway — see below).
  - Note `hasSubcategories` is only computed for top-level categories; sub-categories always come back with
    `hasSubcategories = false` (`:99-103` never `.copy()`s it), so a third level would be invisible.
    `ApiMappers.kt:21-31` hard-codes `hasSubcategories = false` with the comment "Computed by
    RetrofitContentService".
- Server: `PublicContentController.getCategories()`
  (`backend/src/main/java/com/albunyaan/tube/controller/PublicContentController.java:118-122`) —
  `@Cacheable(CACHE_CATEGORY_TREE, key = "'public-categories'")`, no query params, returns
  `List<CategoryDto>`. No `Cache-Control` header is set on this endpoint (contrast `/api/v1/home`, which sets
  `max-age=5min, public` — `PublicContentController.java:61-64`), so iOS should apply its own TTL if it caches.
- Server filtering (`backend/src/main/java/com/albunyaan/tube/service/PublicContentService.java:404-439`):
  only categories that have **at least one APPROVED, publicly-visible** channel/playlist/video, **plus all
  ancestors of such categories**, are returned (`:406-436`). So a parent can appear with no directly-attached
  content — its purpose is navigation into children. Cached under
  `CACHE_PUBLIC_CONTENT / 'active-categories'` (`:404`).
- **Ordering is not guaranteed.** `getCategories` streams `categoryRepository.findAll()` and never sorts by
  `displayOrder` (`PublicContentService.java:434-438`), and the client never sorts either
  (`RetrofitContentService.kt:89-104`). If the iOS list should be stable, sort by
  `displayOrder ?? Int.max`, then `name` — call this out as a deliberate change.

### 2.7 CategoryDto: real Java class vs OpenAPI — **drift confirmed**

| Field | Java `CategoryDto` | OpenAPI `CategoryDto` | Android `CategoryResponse` |
|---|---|---|---|
| `id` | yes, `String` | yes, required | yes |
| `name` | yes, `String` | yes, required | yes |
| `slug` | yes, `String` | yes, **required** | yes, **nullable, default null** |
| `parentId` | yes, `String` | yes, nullable | yes |
| `displayOrder` | yes, `Integer` | **missing** | yes, `Int = 0` (non-null) |
| `localizedNames` | yes, `Map<String,String>` | **missing** | yes, nullable |
| `icon` | yes, `String` | **missing** | yes, nullable |

- Java DTO: `backend/src/main/java/com/albunyaan/tube/dto/CategoryDto.java:11-18`; 7-arg constructor at
  `:30-39`; `@JsonInclude(NON_NULL)` at `:10` means **null fields are omitted from the JSON entirely** — the
  iOS decoder must treat every optional as absent-or-null.
- OpenAPI: `docs/architecture/api-specification.yaml:3065-3084` — `required: [id, name, slug]`, properties
  `id`, `name`, `slug`, `parentId` only. **`displayOrder`, `localizedNames`, `icon` are absent from the spec.**
- Android already documented this drift and hand-wrote a replacement DTO rather than using the generated one:
  "Matches the backend public API CategoryDto JSON shape. The generated CategoryDto is missing
  displayOrder/localizedNames/icon, and the generated Category uses parentCategoryId instead of parentId"
  (`android/app/src/main/java/com/albunyaan/tube/data/source/api/CategoryResponse.kt:6-10`, fields at `:12-20`).
- **Implication for iOS.** Spec §8 says DTOs are generated from `api-specification.yaml` by
  `swift-openapi-generator` (`docs/superpowers/specs/2026-08-23-ios-app-design.md:175-176`). The generated
  `CategoryDto` would silently drop `localizedNames` and `icon`, breaking Arabic/Dutch category names and the
  home section emoji. **Categories must be a hand-written wrapper**, exactly like the two exceptions the spec
  already carves out (`my-submissions` `data` key, Firestore `{seconds,nanos}` —
  `docs/superpowers/specs/2026-08-23-ios-app-design.md:177-178`). Alternatively, fix the YAML — recommended,
  but out of phase-1 scope.
- Two further drift notes:
  - The domain model's `parentId` is fed from `CategoryDto.getParentId()`, which the server populates from
    `Category.getParentId()` (`PublicContentService.java:930-938`), while the **admin** model/controller uses
    `parentCategoryId` (`backend/.../controller/CategoryController.java:119-120,154-174`). Two names for one
    concept; the public API is `parentId`. iOS should follow the public name.
  - `slug` is `required` in the OpenAPI spec but the server derives it as
    `slug != null ? slug : name.toLowerCase().replace(" ", "-")` (`PublicContentService.java:934`) — it is
    always present in practice, but the Android client still models it as optional
    (`CategoryResponse.kt:15`). Model it optional on iOS.
  - `displayOrder` is `Integer` (nullable) server-side but non-null `Int = 0` on Android
    (`CategoryResponse.kt:17`) — a missing value silently becomes `0`. Keep it `Int?` on iOS.

---

## 3. Subcategories

`android/app/src/main/java/com/albunyaan/tube/ui/categories/SubcategoriesFragment.kt`

- **Arguments**: `ARG_CATEGORY_ID = "categoryId"`, `ARG_CATEGORY_NAME = "categoryName"`, both required
  strings, read via `requireArguments().getString(...).orEmpty()` (`:32-33`, `:108-112`;
  nav declaration `res/navigation/main_tabs_nav.xml:180-190`).
- **Toolbar title is the passed-in `categoryName`**, i.e. the already-localised parent name
  (`:40-45`). The layout's static title `subcategories_title` = `"Subcategories"`
  (`res/values/strings.xml:256`, `res/layout/fragment_subcategories.xml:18`) is only the pre-load default and
  is replaced on `onViewCreated`. If `categoryName` is empty, the title becomes empty (not "Subcategories") —
  minor edge case.
- Layout is byte-for-byte the Categories layout with a different RecyclerView id
  (`res/layout/fragment_subcategories.xml:1-33`) — same 2 dp elevation toolbar, same 16 dp list padding, same
  `bottom_nav_height` bottom inset, same `CategoryAdapter` rows.
- **Load**: `contentService.fetchSubcategories(categoryId)` on `onViewCreated`; same silent-failure pattern —
  `Log.e` + `submitList(emptyList())`, no loading/error/empty UI (`:88-101`).
- **Tap** (`:52-80`):
  1. `subName = subcategory.localizedNames?.get(lang) ?: subcategory.name` (`:54-55`).
  2. `displayName = "$categoryName > $subName"` — literal ASCII `" > "` separator, **not** localised and
     **not** RTL-mirrored (`:56`). This is the label spec §6 refers to as `"Parent > Sub"`
     (`docs/superpowers/specs/2026-08-23-ios-app-design.md:123`). In Arabic this renders with bidi reordering
     around the `>`; consider `"\(parent) › \(sub)"` with explicit direction or a localised format string —
     open question.
  3. `filterManager.setCategoryAndAwait(subcategory.id, displayName)` — the **subcategory's** id is stored
     (`:59`).
  4. Toast `category_filter_applied` = `"Filtering by: %1$s"` (`:61-65`).
  5. **`popBackStack(R.id.categoriesFragment, inclusive = true)`** — pops both Subcategories *and*
     Categories, landing on whichever screen opened Categories (`:68`), guarded by
     `if (!isAdded || view == null)` (`:67`). SwiftUI: truncate the `NavigationStack` path back to the
     screen that pushed `.categories`, not just one `pop`.
  6. On exception: toast `category_filter_error`, stay put (`:69-78`).
- Subcategory rows never show a chevron because `hasSubcategories` is always `false` at this level
  (`RetrofitContentService.kt:99-103`, `CategoryAdapter.kt:47-51`) — a third hierarchy level is unreachable.

---

## 4. Featured "See all"

### 4.1 Section header ("See all" affordance)

`res/layout/item_home_section.xml` (spec §7 `SectionHeader`):
- Header row `minHeight = touch_target_min`, horizontal padding `home_horizontal_margin`
  (`:12-17`); section top margin `home_vertical_section_spacing` (`:9`).
- Emoji icon 20 sp, `spacing_md` end margin, **gone unless `section.icon` is non-blank**
  (`:19-30`, `ui/adapters/HomeSectionAdapter.kt:73-79`).
- Title `TextAppearance.Home.SectionTitle`, `maxLines=1`, ellipsised, `textAlignment=viewStart` (`:32-46`),
  and additionally **hard-truncated to 18 characters in code** —
  `MAX_TITLE_CHARS = 18` / `truncateLabel(...)` (`ui/adapters/HomeSectionAdapter.kt:71`, `:97-100`). iOS can
  drop the manual truncation and rely on `.lineLimit(1)` + `.truncationMode(.tail)`; note the drift.
- "See all" label `@string/see_all` = `"See all"` (`res/values/strings.xml:246`), style
  `TextAppearance.Home.SeeAll`, `padding = spacing_sm`, borderless ripple, trailing
  `ic_chevron_right` tinted `@color/primary_green` with `spacing_xs` drawable padding
  (`res/layout/item_home_section.xml:48-62`).
- **"See all" is always shown** — never gated on `totalItemCount` vs displayed count
  (`HomeSectionAdapter.kt:86-88`).
- A11y: `sectionSeeAll.contentDescription = home_see_all_category` = `"See all content in %1$s"` with the
  localised section name (`HomeSectionAdapter.kt:90-92`, `res/values/strings.xml:310`).
  `home_see_all_featured` / `_channels` / `_playlists` / `_videos` also exist
  (`res/values/strings.xml:306-309`) as do `me_see_all` = `"See all"` (`:163`).
- Root `layoutDirection="locale"` (`res/layout/item_home_section.xml:8`).

### 4.2 Navigation

- **From Home**: `action_homeFragment_to_featuredListFragment` with
  `"categoryId" to section.categoryId` and `"categoryName" to section.categoryName` — note this passes the
  **raw, un-localised** `categoryName` (`android/app/src/main/java/com/albunyaan/tube/ui/HomeFragment.kt:105-114`).
- **From Featured itself** (nested sections): navigates by **destination id**
  `R.id.featuredListFragment` (deliberately, so it works from either origin —
  comment at `ui/FeaturedListFragment.kt:112-115`) with
  `"categoryName" to (localizedName ?: section.categoryName)` — this one **is** localised
  (`ui/FeaturedListFragment.kt:116-126`).
- **Inconsistency to fix on iOS**: Home passes the raw name, Featured passes the localised name, so the
  toolbar title of the same screen differs by entry path. Resolve to always-localised.
- Destination args: `categoryId` and `categoryName`, both `string` with `defaultValue=""`
  (`res/navigation/main_tabs_nav.xml:230-240`) — i.e. **optional**, unlike Subcategories' required args.
- Toolbar: default title `@string/section_featured` = `"Featured"`
  (`res/layout/fragment_featured_list.xml:18`, `res/values/strings.xml:242`), overridden with `categoryName`
  when non-empty (`ui/FeaturedListFragment.kt:56-62`). Back → `navigateUp()` (`:57-59`).

### 4.3 ViewModel state and dual-mode load

`android/app/src/main/java/com/albunyaan/tube/ui/FeaturedListViewModel.kt`:

```
StateFlow<FeaturedState>, initial = Loading
sealed FeaturedState:
  Loading
  Sections(sections: [HomeSection], isLoadingMore: Bool)
  FlatList(items: [ContentItem], isLoadingMore: Bool)
  Error(message: String)
```
(`:30-31`, `:187-192`)

- `categoryId` comes from `SavedStateHandle["categoryId"]`, falling back to
  **`FEATURED_CATEGORY_ID = "itirf9pGpAvoBT5VSkEc"`** when null/empty — a hard-coded Firestore document id
  (`:58-62`, `:199`). This is the "Featured" pseudo-category used when Home's generic "See all featured"
  entry is taken. **Port the constant verbatim; it is environment-coupled** (see open questions).
- `loadFeatured()` (`:64-109`) resets *all* paging state (`flatItems`, `flatNextCursor`, `allSections`,
  `sectionsNextCursor`, `lastLoadFailed`), sets `Loading`, then:
  1. **Probe** `GET /api/v1/home` via `fetchHomeFeed(cursor = null, categoryLimit = 10, contentLimit = 20,
     category = categoryId)` (`:78-83`; constants `SECTION_PAGE_SIZE = 10`, `CONTENT_PER_SECTION = 20` at
     `:197-198`; wire params `cursor`, `categoryLimit`, `contentLimit`, `category` —
     `data/source/api/ContentApi.kt:38-44`).
  2. `hasSubcategories = homeFeed.sections.any { it.categoryId != categoryId }` (`:85`). If true → **Sections
     mode**, store `nextCursor`, emit `Sections` (`:86-91`).
  3. Otherwise, or on any exception (`CancellationException` rethrown), set `probeFailed` and fall through
     (`:92-99`).
  4. **Flat mode**: `fetchContent(type = ContentType.ALL, cursor = null, pageSize = 50,
     filters = FilterState(category = categoryId))` → `GET /api/v1/content` with `type`, `cursor`, `limit`,
     `category`, `length`, `date`, `sort`, `q` (`:111-123`, `FLAT_PAGE_SIZE = 50` at `:196`;
     `data/source/api/ContentApi.kt:16-25`). Server clamps `limit` to `[1,50]`
     (`backend/.../PublicContentController.java:102`).
  5. Only a flat-list failure surfaces `Error(e.message ?: "Unknown error")` (`:100-107`) — a probe failure is
     invisible.
- `canLoadMore` (`:46-51`): `nextCursor != null && !lastLoadFailed`, per current mode; `false` in
  `Loading`/`Error`.
- `lastLoadFailed` is the **auto-retry-loop guard**: a failed load-more sets it (`:154`, `:181`) and only a
  manual downward scroll clears it via `viewModel.clearLoadError()` (`:53-56`,
  `ui/FeaturedListFragment.kt:148-149`). Reproduce this on iOS — spec §7's "content fits and hasMore →
  loadMore()" auto-fill rule will otherwise spin forever on a failing endpoint.
- Load-more keeps the cursor on failure and re-emits the *same* list with `isLoadingMore = false`
  (`:151-156`, `:178-183`) — **no error toast, no inline error row; the failure is completely silent**.
- `loadMoreSections()` / `loadMoreFlat()` both `loadJob?.cancel()` first and early-return when
  `isLoadingMore` is already true (`:136-139`, `:163-166`) — that in-flight guard is the same one spec §7
  requires (`docs/superpowers/specs/2026-08-23-ios-app-design.md:167`).

### 4.4 Featured screen UI

`ui/FeaturedListFragment.kt` + `res/layout/fragment_featured_list.xml`:

- Two adapters swapped on the same `RecyclerView` by state: `sectionAdapter` (`HomeSectionAdapter`, nested
  horizontal carousels) for `Sections`, `flatAdapter` (`FeaturedListAdapter`) for `FlatList`; the swap is
  identity-checked to avoid churn (`:184-187`, `:200-203`).
- List padding: top/start/end `spacing_md`, bottom `bottom_nav_height`
  (`res/layout/fragment_featured_list.xml:70-73`).
- **Loading** → centred `progressBar`, list hidden (`:172-177`).
- **Error** → `errorContainer` visible with `errorText` = the raw exception message and a
  `retry` = `"Retry"` text button calling `loadFeatured()` (`:216-222`, `:163-167`,
  `res/layout/fragment_featured_list.xml:39-59`, `res/values/strings.xml:194`).
  **Note the asymmetry with Search**, which hides the message and offers no retry.
- **No empty state**: an empty `FlatList`/`Sections` renders an empty list with no message
  (`:181-214` — no `isEmpty` branch anywhere). Add an `EmptyState` on iOS (spec §7).
- **Load-more triggers, both present** (matching spec §7's two-trigger rule):
  - scroll: `dy > 0` and `lastVisible >= totalItemCount - 5` and `canLoadMore` → `loadMore()`; the same
    handler first calls `clearLoadError()` (`:147-156`).
  - content-fits: after each state emission, `recyclerView.post { if (canLoadMore &&
    !rv.canScrollVertically(1)) loadMore() }` (`:194-199`, `:209-213`).
- Card widths for nested carousels are computed from measured width:
  `((screenWidth - 2*margin - (n-1)*spacing) / n * 0.98)`, with `n` from
  `R.integer.home_cards_visible_videos` / `_channels`, `margin = home_horizontal_margin`,
  `spacing = home_card_spacing` (`:130-143`, `:240-243`; identical helper in `HomeFragment.kt:97-100`).
  Spec §7 already fixes the visible-card counts per width class
  (`docs/superpowers/specs/2026-08-23-ios-app-design.md:165`) — prefer those over re-deriving.
- Item taps use exactly the same three global destinations and argument sets as Search (`:64-101`), including
  `"channelName" to item.category`.
- `HomeSection` domain shape (`data/model/HomeSection.kt:7-15`): `categoryId`, `categoryName`,
  `categorySlug?`, `localizedNames?`, `icon?`, `items`, `totalItemCount`; mapped from
  `HomeCategorySection` where `totalItemCount = totalContentCount`
  (`data/model/mappers/ApiMappers.kt:79-89`).

---

## 5. Cross-cutting notes for the iOS implementer

1. **Two full `/api/v1/categories` fetches per drill-down** (`RetrofitContentService.kt:89-103`). Fetch once,
   keep the flat array, derive parents/children/`hasSubcategories` locally.
2. **Filter writes are id-based, labels are display-only.** `FilterManager` stores `KEY_CATEGORY` = category
   **id** and `KEY_CATEGORY_NAME` = the localised label (`data/filters/FilterManager.kt:43-57`). The label is
   frozen at write time, so switching app language later leaves a stale label on the Home pill. iOS: store the
   id only and re-derive the label from the cached category list at render time — a real fix, flag it.
3. **Category display-name resolution is duplicated in six places** (§2.3). One helper.
4. **All three screens ignore Dynamic Type ceilings**: `item_category.xml` centres a single-line label in a
   card, `item_home_section.xml` hard-truncates to 18 chars. Spec §7 says "No `minimumScaleFactor`"
   (`docs/superpowers/specs/2026-08-23-ios-app-design.md:150`), so rows must be allowed to grow.
5. **Hard-coded colours to replace with tokens**: `item_category.xml:13` `cardBackgroundColor` white and
   `:39` text black (breaks dark mode); `fragment_search.xml:10` AppBar white. Use spec §7
   `homeCard`, `textPrimary`, `background`.
6. **`bottom_nav_height` list insets (72 dp phone / 0 dp sw600)** appear on every screen here
   (`res/values/dimens.xml:26`, `res/values-sw600dp/dimens.xml:6`). On iOS the `TabView` handles safe-area
   insets — drop the manual padding rather than porting the constant.
7. **No unit tests exist** for `SearchViewModel`, `CategoriesFragment`, `SubcategoriesFragment` or
   `FeaturedListViewModel` — `android/app/src/test/java/com/albunyaan/tube/` only has `HomeViewModelTest.kt`,
   `ContentListViewModelTest.kt`, `FakeContentService.kt`, `GlobalStreamResolverTest.kt`. Spec §16 testing
   applies with no Android reference tests to port.

---

## 6. Exact string keys (English)

| Key | English | Cite |
|---|---|---|
| `search_hint` | `Search…` | `res/values/strings.xml:571` |
| `search_clear` | `Clear search` | `:572` (SearchView built-in; unused in layout) |
| `search_recent` | `Recent searches` | `:573` |
| `search_clear_history` | `Clear` | `:574` |
| `search_no_results` | `No results found` | `:575` |
| `search_try_different` | `Try different keywords for "%1$s"` | `:576` |
| `search_try_different_hint` | `Try different keywords` | `:577` (XML default only) |
| `search_error` | `Search failed: %1$s` | `:578` — **dead, do not port** |
| `search_error_generic` | `Search failed. Please try again.` | `:579` |
| `search_loading` | `Searching…` | `:580` |
| `error_title` | `Error` | `:581` |
| `a11y_search_history` | `Recent search: %1$s` | `:385` |
| `cd_delete_search_history` | `Delete search history item` | `:367` |
| `categories` | `Categories` | `:255` |
| `subcategories_title` | `Subcategories` | `:256` |
| `category_filter_applied` | `Filtering by: %1$s` | `:23` |
| `category_filter_error` | `Failed to apply category filter` | `:25` |
| `category_with_overflow` | `%1$s +%2$s` | `:325` |
| `see_all` | `See all` | `:246` |
| `me_see_all` | `See all` | `:163` |
| `home_see_all_featured` | `See all featured content` | `:306` |
| `home_see_all_channels` | `See all channels` | `:307` |
| `home_see_all_playlists` | `See all playlists` | `:308` |
| `home_see_all_videos` | `See all videos` | `:309` |
| `home_see_all_category` | `See all content in %1$s` | `:310` |
| `section_featured` | `Featured` | `:242` |
| `retry` | `Retry` | `:194` |
| `channel_subscribers_format` | (subscriber line, see file) | used at `SearchResultsAdapter.kt:81-84` |
| `playlist_item_count` (plural) | — | `SearchResultsAdapter.kt:133-137` |
| `video_views` (plural) | — | `SearchResultsAdapter.kt:179-183` |
| `video_uploaded_today` / `video_uploaded_days_ago` / `time_ago_weeks` / `time_ago_months` / `time_ago_years` | — | `SearchResultsAdapter.kt:230-243` |

Arabic and Dutch translations live in `res/values-ar/strings.xml` and `res/values-nl/strings.xml`.

---

## 7. Backend call summary

| Screen | Call | Params | Response |
|---|---|---|---|
| Search | `GET /api/v1/search` | `q` (required, raw), `type` (omitted by Android), `limit=50` (server clamps 1..50) | bare `[ContentItemDto]`, server-ordered channels → playlists → videos → streams |
| Categories | `GET /api/v1/categories` | none | `[CategoryDto]` flat; client filters `parentId == nil`, derives `hasSubcategories` |
| Subcategories | `GET /api/v1/categories` (again) | none | same list, client filters `parentId == <id>` |
| Featured probe | `GET /api/v1/home` | `cursor=nil`, `categoryLimit=10`, `contentLimit=20`, `category=<categoryId>` | `HomeFeedResponse` (`data` + `pageInfo.nextCursor/hasNext`) |
| Featured flat | `GET /api/v1/content` | `type=ALL`, `cursor`, `limit=50`, `category=<categoryId>`, `length/date/sort/q` nil | `CursorPage` |

Server-side caps worth knowing: `/api/v1/home` clamps `categoryLimit` to `[1,10]` and `contentLimit` to
`[1,20]` (`backend/.../PublicContentController.java:53-54`); `/api/v1/content` clamps `limit` to `[1,50]` and
truncates `q` to 128 chars after trim (`:101-105`); `/api/v1/search` clamps `limit` to `[1,50]` (`:173`).

---

## 8. Addendum — resolved dimen values used above

Phone (`res/values/dimens.xml`) → sw600 (`res/values-sw600dp/dimens.xml`) → sw720
(`res/values-sw720dp/dimens.xml`); a dash means no override.

| Dimen | Phone | sw600 | sw720 | Cite |
|---|---|---|---|---|
| `spacing_xxs` | 2 dp | — | — | `values/dimens.xml:5` |
| `spacing_xs` | 4 dp | — | — | `:6` |
| `spacing_sm` | 8 dp | — | — | `:7` |
| `spacing_md` | 16 dp | 20 dp | — | `:8`, `values-sw600dp/dimens.xml:36` |
| `spacing_lg` | 24 dp | 32 dp | — | `:9`, `values-sw600dp/dimens.xml:37` |
| `spacing_xl` | 32 dp | 48 dp | — | `:10`, `values-sw600dp/dimens.xml:38` |
| `corner_radius_medium` | 16 dp | — | — | `:18` |
| `touch_target_min` | 48 dp | — | — | `:22` |
| `bottom_nav_height` | 72 dp | **0 dp** | — | `:26`, `values-sw600dp/dimens.xml:6` |
| `icon_large` | 48 dp | 56 dp | — | `:57`, `values-sw600dp/dimens.xml:43` |
| `icon_xlarge` | 96 dp | — | — | `:58` |
| `avatar_medium` | 56 dp | 64 dp | 72 dp | `:63`, `values-sw600dp/dimens.xml:47`, `values-sw720dp/dimens.xml:51` |
| `playlist_thumbnail_size` | 80 dp | 100 dp | 120 dp | `:84`, `values-sw600dp/dimens.xml:30`, `values-sw720dp/dimens.xml:29` |
| `video_list_thumbnail_width` | 140 dp | — | — | `:85` |
| `elevation_sm` | 2 dp | — | — | `:119` |
| `home_horizontal_margin` | 16 dp | — | — | `:124` |
| `home_vertical_section_spacing` | 24 dp | — | — | `:125` |
| `home_thumbnail_corner_radius` | 12 dp | — | — | `:127` |
| `home_duration_chip_padding_horizontal` | 6 dp | — | — | `:133` |
| `home_duration_chip_padding_vertical` | 3 dp | — | — | `:134` |
| `text_duration` | 11 sp | — | — | `:141` |
| `home_card_spacing` | 12 dp | — | — | `:156` |
| `home_item_meta_size` | 13 sp | — | — | `:164` |
| `text_subtitle` | 16 sp | — | 18 sp | `:195`, `values-sw720dp/dimens.xml:100` |
| `text_body` | 14 sp | — | 16 sp | `:196`, `values-sw720dp/dimens.xml:101` |

Two string values referenced but not spelled out above:
`channel_subscribers_format` = `"%s subscribers"` (`res/values/strings.xml:321`).
Translations exist under `res/values-ar/` and `res/values-nl/`.
