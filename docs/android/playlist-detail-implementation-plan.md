# Playlist Detail Screen – Implementation Plan (Albunyaan Tube)

## 0. Critical Review & High‑Level Decisions

### 0.1 Current Implementation (What Exists Today)

Files:
- `android/app/src/main/java/com/albunyaan/tube/ui/detail/PlaylistDetailFragment.kt`
- `android/app/src/main/java/com/albunyaan/tube/ui/detail/PlaylistDetailViewModel.kt`
- `android/app/src/main/res/layout/fragment_playlist_detail.xml`
- Navigation: `android/app/src/main/res/navigation/main_tabs_nav.xml`

Key characteristics:
- Uses `ContentService` (backend) and `ContentItem` instead of NewPipeExtractor.
- Playlist lookup is done by fetching a page of `PLAYLISTS` then `firstOrNull { it.id == playlistId }`.
- Videos are fetched via `ContentType.VIDEOS` with no real playlist filtering.
- No pagination: all videos are loaded in a single call with `pageSize = 20`.
- UI:
  - Simple `LinearLayout` with `MaterialToolbar`, `NestedScrollView`, vertical content.
  - Hero is a placeholder `FrameLayout` with a single initial letter.
  - Single “Download Playlist” button with TODO; no quality selection.
  - Loading and error states use `ProgressBar` and `TextView` (no skeletons).
  - Hardcoded spacing (`16dp`, `200dp`, `20sp`) and colors instead of design tokens.
  - No tablet (`sw600dp`) or large tablet (`sw720dp`) variants.

### 0.2 Gaps vs PRD & Design/Test Rules

Bugs / violations / limitations:
- **Data source violation**: Uses backend `ContentService` instead of NewPipeExtractor for playlist metadata and items.
- **No playlist‑scoped videos**: Videos aren’t filtered by playlist; order and membership can’t be trusted.
- **No pagination**: Large playlists can’t scale; cannot meet performance and memory goals for big lists.
- **No skeletons**: Uses spinners instead of header/list skeleton placeholders.
- **Weak offline integration**:
  - No playlist download flow.
  - No per‑video download indication in list.
  - No linkage to `DownloadRepository` / `DownloadScheduler`.
- **Design system violations**:
  - Hardcoded `dp/sp` and colors instead of `@dimen/spacing_*`, `@dimen/text_*`, `?attr/color*`.
  - No tablet (`layout-sw600dp`) or large tablet (`layout-sw720dp`) layouts for Playlist Detail, despite Channel Detail having both.
- **RTL & accessibility**:
  - Text views lack explicit `android:textAlignment="viewStart"` in several places.
- **Testing**:
  - No dedicated `PlaylistDetailViewModel` unit tests.
  - No UI tests for Playlist Detail on phone/tablet, nor RTL.

### 0.3 High‑Level Implementation Strategy

Decisions:
1. **Architecture parity with Channel Detail**
   - Introduce `PlaylistDetailRepository` backed by `NewPipeExtractorClient`, similar to `NewPipeChannelDetailRepository`.
   - Introduce playlist domain models (`PlaylistHeader`, `PlaylistItem`, `PlaylistPage`) analogous to `ChannelHeader`, `ChannelVideo`, `ChannelPage`.
   - Implement `PlaylistDetailViewModel` with:
     - `HeaderState` (`Loading`, `Success`, `Error`).
     - `PaginatedState<PlaylistItem>` (copied or refactored from `ChannelDetailViewModel`).
   - Use rate‑limited pagination with a `PaginationController` (single in‑flight append, min interval).

2. **UI parity with Channel Detail**
   - Replace `LinearLayout + NestedScrollView` with `CoordinatorLayout + AppBarLayout + CollapsingToolbarLayout`.
   - Single scrollable surface: hero header in `AppBarLayout` + `RecyclerView` for playlist items.
   - Sticky toolbar and bottom nav preserved via `appbar_scrolling_view_behavior` (same pattern as Channel Detail).

3. **Multi‑device layouts**
   - Implement:
     - `res/layout/fragment_playlist_detail.xml` (phone).
     - `res/layout-sw600dp/fragment_playlist_detail.xml` (tablet, content width constrained, adjusted paddings).
     - `res/layout-sw720dp/fragment_playlist_detail.xml` (large tablet/TV, mirrored from Channel Detail’s `sw720dp`).
   - Keep **identical view IDs** across all variants.

4. **Skeletons over spinners**
   - Header skeleton layout inspired by `fragment_channel_detail.xml`’s `headerSkeleton`.
   - List skeleton rows reuse `@layout/skeleton_content_item` and the pattern from `fragment_channel_list_tab.xml`.

5. **Playlist download integration**
   - Extend download domain to support playlist context:
     - `DownloadRequest` extended with `playlistId`, `playlistTitle`, `playlistQualityLabel`, `indexInPlaylist`, `playlistSize`.
   - Implement playlist‑level enqueue helper using existing `DownloadRepository` + `DownloadScheduler`.
   - Aggregate progress in Downloads tab based on grouped entries.
   - Prevent duplicate jobs per `(playlistId, quality)`.

6. **Testing and performance**
   - Add ViewModel unit tests mirroring `ChannelDetailViewModelTest`.
   - Add Robolectric/Espresso UI tests for:
     - Phone + tablet layouts.
     - Arabic locale (RTL).
     - Skeletons, empty/error states.
   - Optional integration test that verifies basic NewPipe playlist extraction (tagged or separated per AGENTS policy).

---

## 1. Architecture & Data Flow

### 1.1 New Domain Models (Playlist Detail)

Create new models in `com.albunyaan.tube.data.playlist`:

1. `PlaylistHeader`
   - Fields (NewPipe + Albunyaan metadata):
     - `id: String` (YouTube playlist ID).
     - `title: String`.
     - `thumbnailUrl: String?` (hero banner thumbnail).
     - `channelId: String?`.
     - `channelName: String?`.
     - `itemCount: Int?`.
     - `totalDurationSeconds: Long?` (optional; if NewPipe can provide or approximated).
     - `description: String?`.
     - `tags: List<String>`.
     - `category: String?` (from Albunyaan `ContentItem.Playlist` via nav args).
     - `excluded: Boolean` (from backend via nav args).
     - `downloadPolicy: DownloadPolicy` (Albunyaan specific – already used in fragment).
   - Rationale: encapsulates everything needed to build the hero header, actions, and download copy without re‑querying backend.

2. `PlaylistItem`
   - Represents a video inside a playlist, in order:
     - `position: Int` (1‑based index).
     - `videoId: String`.
     - `title: String`.
     - `thumbnailUrl: String?`.
     - `durationSeconds: Int?`.
     - `viewCount: Long?`.
     - `publishedTime: String?` (“1 year ago” etc).
     - `channelId: String?`, `channelName: String?` (optional).
   - Download‑related fields derived in UI layer from `DownloadRepository` state, not stored here.

3. `PlaylistPage<T>`
   - Similar to `ChannelPage<T>`:
     - `items: List<T>`.
     - `nextPage: Page?` (reuse existing `Page` type from `data.channel` to wrap NewPipe’s `Page`).
     - `fromCache: Boolean = false`.

### 1.2 Playlist Detail Repository

Create `PlaylistDetailRepository` interface in `com.albunyaan.tube.data.playlist`:

- Methods:
  - `suspend fun getHeader(playlistId: String, forceRefresh: Boolean = false): PlaylistHeader`
  - `suspend fun getItems(playlistId: String, page: Page?): PlaylistPage<PlaylistItem>`

Implementation: `NewPipePlaylistDetailRepository` backed by `NewPipeExtractorClient`.

1. **Header loading**
   - Use `NewPipeExtractorClient` and NewPipe’s `PlaylistInfo`:
     - Build playlist link handler similar to `loadPlaylistMetadata` (`createPlaylistLinkHandler` already exists).
     - Call `fetchPage()` then `PlaylistInfo.getInfo(extractor)` for metadata + initial related items.
   - Map to `PlaylistHeader`:
     - `title`, `description`, `thumbnailUrl`, `itemCount`.
     - If `PlaylistInfo` gives uploader/channel, map `channelId` and `channelName`.
   - Incorporate Albunyaan metadata passed from backend (category, excluded, downloadPolicy) via parameters from ViewModel.

2. **Items loading + pagination**
   - For `page == null`:
     - Use `PlaylistInfo`’s `relatedItems` list as first page.
     - Map each `InfoItem`/`StreamInfoItem` to `PlaylistItem` in canonical playlist order.
     - Capture `nextPage` token using `Page.fromNewPipePage(playlistInfo.nextPage)`.
   - For `page != null`:
     - Use `PlaylistInfo.getMoreItems(youtubeService, handler, page.toNewPipePage())` pattern (similar to `fetchTabContent` in channel repo) to load subsequent items.
     - Map to `PlaylistItem` and return `PlaylistPage`.
   - Ensure:
     - Exclude unavailable/removed items gracefully.
     - Maintain stable order using the `position` from playlist or computed sequentially across pages.

3. **Error handling & metrics**
   - Mirror `NewPipeChannelDetailRepository` error handling:
     - Network/Extraction exceptions propagated; others wrapped in `ExtractionException`.
   - Use `ExtractorMetricsReporter` (if appropriate) to record playlist extraction metrics.

4. **DI wiring**
   - Add binding in a new `PlaylistModule` or extend existing DI module to provide:
     - `NewPipePlaylistDetailRepository` as `PlaylistDetailRepository`.
   - Inject into `PlaylistDetailViewModel` via `@AssistedInject`.

### 1.3 ViewModel – `PlaylistDetailViewModel` Rework

Refactor `PlaylistDetailViewModel` in `com.albunyaan.tube.ui.detail`:

1. **Constructor & dependencies**
   - Replace `ContentService` with:
     - `PlaylistDetailRepository`.
     - `DownloadRepository` (for downloads integration).
     - `SettingsPreferences` (for default quality, wifi‑only rules).
     - Assisted args:
       - `playlistId: String`.
       - Optional `initialTitle`, `initialCategory`, `initialCount`, `downloadPolicy`, `excluded`.
   - Move download‑related policy enums to a shared location if needed.

2. **State flows**

   Header:
   - `sealed class HeaderState { object Loading; data class Success(val header: PlaylistHeader); data class Error(val message: String) }`
   - Private `_headerState: MutableStateFlow<HeaderState> = MutableStateFlow(Loading)`.
   - Public `headerState: StateFlow<HeaderState>`.

   Items (paginated):
   - Reuse the `PaginatedState<T>` contract from `ChannelDetailViewModel`:
     - `Idle`, `LoadingInitial`, `Loaded(items, nextPage, isAppending)`, `Empty`, `ErrorInitial`, `ErrorAppend(items, nextPage)`.
   - **Decision (Option B)**: Extract `PaginatedState<T>` into a shared pagination package (e.g., `ui.common.pagination`) and update both Channel and Playlist ViewModels to use this generic type, avoiding duplication.
   - Private `_itemsState: MutableStateFlow<PaginatedState<PlaylistItem>> = MutableStateFlow(Idle)`.
   - Public `itemsState: StateFlow<PaginatedState<PlaylistItem>>`.

   Downloads:
   - `data class PlaylistDownloadUiState(...)` with:
     - `isDownloading: Boolean`.
     - `downloadedCount: Int`.
     - `totalCount: Int`.
     - `progressPercent: Int`.
     - `currentQualityLabel: String?`.
     - `errorMessage: String?`.
   - Expose as `StateFlow<PlaylistDownloadUiState>` derived from `DownloadRepository.downloads` (mapping on each emission).

3. **Pagination controller & methods**
   - Add `PaginationController` similar to `TabPaginationController`:
     - `isInitialLoading: Boolean`.
     - `isAppending: Boolean`.
     - `nextPage: Page?`.
     - `hasReachedEnd: Boolean`.
     - `lastAppendRequestMs: Long`.
   - Methods:
     - `fun loadHeader(forceRefresh: Boolean = false)`
     - `fun loadInitial()`
     - `fun loadNextPage()`
     - `fun retryInitial()`
     - `fun retryAppend()`
     - `fun onListScrolled(lastVisible: Int, totalCount: Int)` – triggers `loadNextPage()` when `totalCount - lastVisible <= PAGINATION_THRESHOLD`.
   - Rate‑limit:
     - Use `MIN_APPEND_INTERVAL_MS = 1000L` as in `ChannelDetailViewModel`.
     - Guard: no concurrent appends; skip when `nextPage == null` or `hasReachedEnd`.

4. **Lifecycle and load sequence**
   - In `init`:
     - Kick off `loadHeader()`.
   - `loadHeader()`:
     - Emit `HeaderState.Loading`.
     - Call `repository.getHeader(playlistId, forceRefresh)`; incorporate initial category/count/excluded/downloadPolicy.
     - On success:
       - Emit `HeaderState.Success(header)`.
       - Trigger `loadInitial()` once.
     - On failure:
       - Emit `HeaderState.Error(message)`.
       - Do NOT keep skeleton in `Loading`; error state should show header error UI.
   - `loadInitial()`:
     - Skip if controller `isInitialLoading`.
     - Reset pagination fields, emit `PaginatedState.LoadingInitial`.
     - Call `repository.getItems(playlistId, null)`.
     - Map:
       - Empty items → `PaginatedState.Empty`.
       - Non‑empty → `PaginatedState.Loaded(items, nextPage)`.
   - `loadNextPage()`:
     - Guard using controller flags + rate limit.
     - On success:
       - Append items to existing state (`Loaded` or `ErrorAppend.items`).
     - On failure:
       - Emit `PaginatedState.ErrorAppend(message, currentItems, currentNextPage)`.
     - Never leave state in a perpetual “appending” mode.

5. **Download actions in ViewModel**

   **Event model (single pattern)**:
   - Use a `MutableSharedFlow<PlaylistUiEvent>` for all one‑shot UI events.
   - Example sealed class:
     - `NavigateToPlayer(startIndex: Int, shuffled: Boolean)`
     - `ShowDownloadQualitySheet(playlistId: String, itemCount: Int, suggestedQuality: PlaylistQualityOption)`
     - `ShowError(message: String)`

   API exposed to Fragment:
   - `fun onPlayAllClicked(startIndex: Int = 0)`
     - Emits `PlaylistUiEvent.NavigateToPlayer(startIndex, shuffled = false)`.
   - `fun onShuffleClicked()`
     - Emits `PlaylistUiEvent.NavigateToPlayer(startIndex = 0, shuffled = true)`, letting the player handle shuffled queue semantics.
   - `fun onDownloadPlaylistClicked()`
     - Emits `PlaylistUiEvent.ShowDownloadQualitySheet(...)`, carrying enough context for the bottom sheet (playlist ID, title, item count, default quality).
   - `fun startPlaylistDownload(selectedQuality: PlaylistQualityOption)`
     - Launches a background coroutine on `Dispatchers.IO` that:
       1. Iteratively calls `repository.getItems(playlistId, page)` starting with `page = null`, accumulating items in order until `nextPage == null` or `accumulator.size >= maxDownloadItemsCap` (default 500 per section 7.1).
       2. Applies light backpressure between page requests (reuse pagination controller delay) to avoid extractor overload; surfaces the first error and aborts the loop with `PlaylistDownloadUiState.errorMessage` + `PlaylistUiEvent.ShowError(...)`.
       3. On success: trims to the cap, maps to `List<DownloadRequest>` with `indexInPlaylist` preserved, and delegates to the playlist enqueue helper (section 5.2); then updates `PlaylistDownloadUiState`.

   This ensures navigation and download flows are consistently initiated from the Fragment in response to ViewModel‑emitted events, without the ViewModel owning direct NavController references.

---

## 2. UI & Layout

### 2.1 Layout Files Overview

Create / refactor:

1. `res/layout/fragment_playlist_detail.xml` (phone)
2. `res/layout-sw600dp/fragment_playlist_detail.xml` (tablet)
3. `res/layout-sw720dp/fragment_playlist_detail.xml` (large tablet/TV)

Constraints:
- **Identical view IDs** across all three files.
- Use design tokens:
  - Spacing: `@dimen/spacing_*`.
  - Typography: `@dimen/text_headline`, `@dimen/text_subtitle`, `@dimen/text_body`, `@dimen/text_caption`.
  - Colors: `?attr/colorSurface`, `?attr/colorOnSurface`, `?attr/colorOnSurfaceVariant`, `@color/primary_green`, etc.
- Use `android:textAlignment="viewStart"` and `start`/`end` constraints for RTL.

### 2.2 Phone Layout Structure (`layout/fragment_playlist_detail.xml`)

Root: `CoordinatorLayout` similar to Channel Detail.

1. **AppBar & Hero**

   - `AppBarLayout` with:
     - `CollapsingToolbarLayout` (`app:layout_scrollFlags="scroll|exitUntilCollapsed"`, `app:titleEnabled="false"`).
     - Inside:
       - `LinearLayout` (`id=headerContent`, vertical, `app:layout_collapseMode="parallax"`):
         1. Optional exclusion banner (`TextView`, `id=exclusionBanner`, reusing style from channel).
         2. **Playist banner** (`FrameLayout`):
            - `ImageView` `id=playlistBanner`:
              - Height: new `@dimen/playlist_banner_height` (added to `dimens.xml` and tablet variants).
              - `centerCrop`, placeholder `thumbnail_placeholder`.
            - Gradient overlay `View` `id=bannerGradient` using `@drawable/banner_gradient_overlay`.
         3. **Header info** (`ConstraintLayout`):
            - **Thumbnail/avatar + overlay** (optional):
              - Could show small playlist cover overlaying banner bottom (like Channel avatar).
            - `TextView` `id=playlistTitle`:
              - `textSize="@dimen/text_headline"`, `maxLines=2`, `ellipsize="end"`.
            - `TextView` `id=channelName`:
              - Tapable – navigate to `ChannelDetailFragment`.
              - Underline or subtle accent color.
            - `TextView` `id=playlistMetadata`:
              - Combined “N videos • total duration” line.
            - `ChipGroup` `id=categoryChipsContainer`:
              - Contains “General” or category chip (non‑clickable for now).
         4. **Actions row**:
            - Use `ConstraintLayout` or `Flow` to handle narrow / wide.
            - Primary actions:
              - `MaterialButton` `id=playAllButton` (primary, full width on phone).
              - `MaterialButton` `id=shuffleButton` (outlined/secondary).
              - `MaterialButton` `id=downloadPlaylistButton` (primary/emphasized; ensure safe touch target).
            - On narrow screens:
              - Use vertical stack: Play All (full width), then Shuffle + Download horizontally beneath or both stacked.
            - On wide screens:
              - Use horizontal row with equal‑weight, ensuring `minHeight="@dimen/touch_target_button"`.
         5. **Download policy text**:
            - `TextView` `id=downloadPolicyText`:
              - Small muted text, reused from current “Downloads respect policy AC‑DL‑001…” string.
              - `textAlignment="viewStart"`, uses localized string with placeholders if needed.

     - `MaterialToolbar` `id=toolbar`:
       - `app:layout_collapseMode="pin"`.
       - `app:navigationIcon="?attr/homeAsUpIndicator"`.
       - `app:titleTextColor="?attr/colorOnSurface"`.

2. **Content (Videos list)**
   - `FrameLayout` `id=contentContainer` with `app:layout_behavior="@string/appbar_scrolling_view_behavior"`.
   - Children:
     1. `RecyclerView` `id=videosRecyclerView` (main content):
        - `clipToPadding=false`.
        - `paddingBottom="@dimen/bottom_nav_height"` on phone to account for bottom nav.
     2. `LinearLayout` `id=listSkeletonContainer`:
        - Initially `gone`.
        - Contains several `include` `@layout/skeleton_content_item` rows.
     3. `include` `@layout/empty_state` `id=emptyState` (overlay).
     4. `include` `@layout/error_state` `id=errorState` (overlay).
     5. Optional pagination footer skeleton overlay or integrated as extra items in adapter.

3. **Header Skeleton Overlay**
   - `LinearLayout` `id=headerSkeleton` overlayed at top (similar to channel):
     - Visible while `HeaderState.Loading`.
     - Contains:
       - Skeleton banner `View` of `@dimen/playlist_banner_height`.
       - Skeleton title + channel lines.
       - Skeleton for buttons (rectangular shimmer views).

### 2.3 Tablet Layout (`layout-sw600dp/fragment_playlist_detail.xml`)

Based on phone, but with tablet‑specific adjustments:

- Keep root `CoordinatorLayout` + `AppBarLayout` structure identical to phone.
- Differences:
  - Use `@dimen/channel_header_horizontal_margin` for header paddings (or a new `playlist_header_horizontal_margin` if needed).
  - Increase `@dimen/playlist_banner_height` from phone value.
  - Layout hero content in a more horizontal fashion:
    - Thumbnail and actions side‑by‑side when space allows.
  - Buttons row:
    - Use horizontal layout with equal widths and spacing `@dimen/spacing_lg`.
  - Content width:
    - Constrain `videosRecyclerView` within `android:width="0dp"` and `app:layout_constraintWidth_max="@dimen/content_max_width"` (or wrap in a center container) to avoid overly wide lines.
- Ensure `bottom_nav_height` is `0dp` (per `values-sw600dp/dimens.xml`) so we don’t add extra bottom padding; tablet shell uses navigation rail.

### 2.4 Large Tablet/TV Layout (`layout-sw720dp/fragment_playlist_detail.xml`)

- Start from `layout-sw600dp` version.
- Adjust typography and spacing using `values-sw720dp/dimens.xml` tokens (`spacing_md`, `text_headline`, etc.).
- Ensure focus order and larger touch targets for TV / D‑pad:
  - Buttons with `minHeight="@dimen/touch_target_button"`.
  - Consider `android:focusable="true"` on list rows and actions, similar to Channel Detail’s large‑screen behavior.

### 2.5 Video List Item Reuse & Download Status

1. **Adapter**
   - Create `PlaylistVideosAdapter` in `com.albunyaan.tube.ui.detail.adapters`:
     - Reuse `ItemVideoListBinding` (`@layout/item_video_list`).
     - Show `PlaylistItem` data (title, duration, metadata).
     - Add:
       - Leading index “1.”, “2.” etc (optional; small `TextView` overlay or inside metadata).
       - Download status icon:
         - Either:
           - Reuse existing download icons from Downloads list layout; or
           - Add a small icon on right (inside list item) to indicate:
             - Not downloaded: outline/cloud icon.
             - Downloading: progress indicator / animated icon.
             - Downloaded: filled icon.
     - DiffUtil uses `videoId` + `position`.

2. **Skeleton & pagination**
   - For initial load:
     - Fragment sets `listSkeletonContainer.isVisible = true` while `PaginatedState.LoadingInitial` or `Idle`.
   - For pagination:
     - Option A: Use a footer view type that shows `skeleton_content_item` repeated 2–3 times when `isAppending = true`.
     - Option B: Use `ConcatAdapter` with a footer adapter that toggles skeleton visibility.
   - On `ErrorAppend`, keep existing list visible and show an inline text or snackbar; do not re‑show skeleton overlay.

### 2.6 App Bar, Bottom Navigation & Scroll Behavior

- Ensure `videosRecyclerView`:
  - Is inside a container with `app:layout_behavior="@string/appbar_scrolling_view_behavior"` via `FrameLayout`.
  - Works with bottom nav and navigation rail per existing shell layouts.

### 2.7 RTL & Localization

- All `TextView`s in header and list should have:
  - `android:textAlignment="viewStart"`.
- Use `layout_marginStart`/`layout_marginEnd` instead of `left/right`.
- For metadata strings, keep them localizable:
  - Example: `"%1$d videos • %2$s"` should be a string resource with placeholders, not a concatenated string.
- Ensure download policy text, buttons (“Play all”, “Shuffle”, “Download playlist”) are localized via `strings.xml`.
- In UI tests, verify Arabic locale and that:
  - Text aligns correctly.
  - Icons maintain visual consistency in RTL.

---

## 3. State Handling

### 3.1 Header States

States:
- `Loading`: header skeleton visible, toolbar title may show placeholder.
- `Success(header)`:
  - Hide skeleton.
  - Bind:
    - Banner image.
    - Title, channel, metadata line.
    - Category chip(s).
    - Download policy text (from resources).
  - Enable actions based on `downloadPolicy` and `excluded`.
- `Error(message)`:
  - Hide skeleton.
  - Show inline error area below toolbar:
    - Error message and "Retry" button that calls `viewModel.loadHeader(forceRefresh = true)`.
  - Keep toolbar/back navigation working so user can exit.

### 3.2 Items States (`PaginatedState<PlaylistItem>`)

Mapping:

- `Idle` / `LoadingInitial`:
  - Hide list, empty, and error overlay.
  - Show list skeleton overlay.
- `Loaded(items, nextPage, isAppending=false)`:
  - Show list, hide skeleton, empty, error overlays.
  - Submit list to adapter.
- `Loaded(isAppending=true)`:
  - List visible.
  - Adapter shows footer skeleton or appends 2–3 placeholder rows.
- `Empty`:
  - Hide skeleton and list.
  - Show `empty_state` overlay with playlist‑specific message:
    - “This playlist has no videos yet.”
- `ErrorInitial(message)`:
  - Hide skeleton and list.
  - Show `error_state` overlay.
  - Retry button calls `viewModel.retryInitial()`.
- `ErrorAppend(message, items, nextPage)`:
  - Keep list visible with existing items.
  - Hide skeleton and overlays.
  - Show snackbar or inline message with "Retry" that calls `viewModel.retryAppend()`.

### 3.3 Preventing “Stuck Skeleton” States

Rules:
- Any transition into `Error` states must also set skeleton visibility to `false`.
- After pagination completes or fails:
  - `isAppending` must be reset to `false` in state.
- Fragment UI logic:
  - Derive skeleton visibility exclusively from state type; never manually toggle outside `updateUI()` to avoid desync.

### 3.4 Combined Header + List Interaction

- Initial load:
  1. Header enters `Loading`.
  2. Items state is `Idle` → show both header skeleton and list skeleton.
  3. On header success:
     - Hide header skeleton; items still `LoadingInitial` until first page arrives.
  4. On first page success:
     - Hide list skeleton; show list.
- Error paths:
  - Header error:
    - Show header error area; do not attempt to load items.
  - Items initial error:
    - Show `ErrorInitial` overlay and retry button.

---

## 4. Navigation

### 4.1 Entry Points & Arguments

Existing entry points (must remain functional):
- `HomeFragment` (`HomeFeaturedAdapter`, `HomePlaylistAdapter`).
- `FeaturedListFragment`.
- `SearchFragment`.
- `PlaylistsFragmentNew`.
- `ChannelPlaylistsTabFragment`.

Navigation args (already defined in `main_tabs_nav.xml`):
- `playlistId: String` (required).
- `playlistTitle: String?` (optional).
- `playlistCategory: String?` (optional).
- `playlistCount: Int` (default `0`).
- `downloadPolicy: String` (enum name, default `ENABLED`).
- `excluded: Boolean` (default `false`).

Plan:
- `PlaylistDetailFragment` should:
  - Continue to resolve these via constants `ARG_PLAYLIST_*`.
  - Pass relevant values into `PlaylistDetailViewModel` constructor (assisted factory).

Improvements:
- Where callers use raw strings (`"playlistId"`, `"playlistTitle"`), refactor to use `PlaylistDetailFragment` constants to avoid mismatches (optional cleanup).

### 4.2 Back Navigation & State Restoration

- `PlaylistDetailFragment` uses standard nav controller `navigateUp()` from toolbar.
- With ViewModel scoped to fragment navigation graph:
  - On configuration change, state flows preserve header + items.
  - Fragment’s `onViewCreated` must **not** trigger additional `loadHeader()` or `loadInitial()` beyond ViewModel’s `init`.
- Scroll position:
  - **Do NOT use `setHasFixedSize(true)`** for paginated lists—items are appended during pagination and `fixedSize` prevents proper layout updates.
  - Enable `adapter.setHasStableIds(true)` and implement `getItemId()` to return a stable, unique ID per item (e.g., `videoId.hashCode().toLong()` or a combination of `videoId` and position).
  - With stable IDs, `RecyclerView` can correctly restore scroll position across configuration changes.

### 4.3 Deep Links

- Keep `albunyaantube://playlist/{playlistId}` deep link.
- For deep links that only provide `playlistId`:
  - UI initially shows skeleton.
  - Header acquires title/channel/metadata from NewPipe.
  - If extraction fails but we have `playlistTitle` arg, use that as fallback in error state text.

---

## 5. Downloads Integration

### 5.1 Extending Download Models for Playlist Context

Modify `DownloadRequest` in `DownloadModels.kt`:

- Add optional playlist fields:
  - `playlistId: String? = null`
  - `playlistTitle: String? = null`
  - `playlistQualityLabel: String? = null` (e.g., "360p").
  - `indexInPlaylist: Int? = null`
  - `playlistSize: Int? = null`
- Maintain backward compatibility for existing video downloads.

Update `DownloadScheduler.schedule()`:
- Include new fields in `Data`:
  - `KEY_PLAYLIST_ID`, `KEY_PLAYLIST_TITLE`, `KEY_QUALITY_LABEL`, `KEY_INDEX_IN_PLAYLIST`, etc.
- Add corresponding constants.

Update `DownloadWorker`:
- Read new keys for logging and metrics only; core behavior (resolving streams, downloading) remains per‑video.

### 5.2 Playlist‑Level Enqueue Helper

Add helper to `DownloadRepository` or a new `PlaylistDownloadManager` class:

- API:
  ```kotlin
  fun enqueuePlaylist(
      playlistId: String,
      playlistTitle: String,
      qualityLabel: String,
      items: List<PlaylistItem>,
      audioOnly: Boolean
  )
  ```
- Implementation:
  - Build deterministic request IDs per video, e.g.:
    - `"$playlistId|$qualityLabel|${item.videoId}"`.
  - Before enqueuing each:
    - Check `downloads` state for existing entries with same request ID in non‑terminal state; skip duplicates.
  - Enqueue each via `DownloadRepository.enqueue()`.
- Aggregation:
  - Playlist progress = average progress of all active entries for that `(playlistId, qualityLabel)`.

### 5.3 Quality Selection Bottom Sheet

Implement new bottom sheet fragment, e.g. `PlaylistDownloadBottomSheetFragment`:

- Inputs:
  - `playlistId`, `playlistTitle`, `itemCount`, optional `estimatedDurations`.
- UI:
  - List of qualities: `144p`, `360p`, `720p`, `1080p`, `4K` (subset depending on availability).
  - Each row shows:
    - Quality label.
    - Approximate total size (e.g., “~1.2 GB”).
- Estimation strategy (performance‑safe):
  - Avoid resolving streams for all videos; expensive.
  - Use heuristics based on average bitrate per quality and total duration:
    - Derive approximate total duration from:
      - Sum of `PlaylistItem.durationSeconds` for currently loaded items.
      - Or, sample a subset and extrapolate.
  - Document approximation in code comments and be conservative to avoid underestimation.

- Communication with ViewModel:
  - Bottom sheet calls `viewModel.startPlaylistDownload(selectedQuality)`.

### 5.4 Per‑Video Download Indicators

- In `PlaylistVideosAdapter.bind()`:
  - Subscribe to `PlaylistDownloadUiState` via fragment and pass per‑video status (or a map) to adapter.
  - For each item:
    - Determine status:
      - `Downloaded` if there is a `DownloadEntry` with `status == COMPLETED` for that `videoId`.
      - `Downloading` if `QUEUED` or `RUNNING`.
    - Display:
      - Small icon overlay on thumbnail or at end of metadata line.
      - Optional progress text “Downloading 3/30” for the playlist header.

### 5.5 Error Handling & No Duplicate Jobs

- Quota / policy errors from backend or network:
  - Leverage existing error handling in `DownloadWorker` + `DownloadRepository` (notifications, messages).
  - Reflect errors in `PlaylistDownloadUiState` to show inline message in header (“Download failed, tap to retry”).
- Storage full:
  - Rely on OS; show friendly message when WorkManager reports failure.
- No duplicate playlist jobs:
  - At playlist level:
    - Before starting `enqueuePlaylist`, check if there is at least one active entry with `playlistId` and selected `qualityLabel`; if so, show message (“Playlist already downloading at 360p”) and do not enqueue.
- Downloads tab aggregation:
  - Update Downloads UI (separate feature, but plan aware) to:
    - Group entries by playlist.
    - Show aggregated progress as required (“Downloading 1/30”).

---

## 6. Testing

### 6.1 Unit Tests – PlaylistDetailViewModel

Add `PlaylistDetailViewModelTest` in `app/src/test/java/com/albunyaan/tube/ui/detail`:

- Use patterns from `ChannelDetailViewModelTest`:
  - `StandardTestDispatcher`, `runTest`, `advanceUntilIdle`.
- Create a `FakePlaylistDetailRepository` and `FakeDownloadRepository`.

Test cases:

1. Header:
   - `header loading emits Loading then Success`.
   - `header loading emits Error when repository throws`.
   - `header retry reloads header data`.

2. Items pagination:
   - Initial load:
     - `loadInitial emits LoadingInitial then Loaded`.
     - `Empty` when no items.
   - Pagination:
     - `loadNextPage appends items`.
     - Stops when `nextPage == null`.
     - `ErrorInitial` when first load fails.
     - `ErrorAppend` preserves existing items on append failure.
   - Rate limiting:
     - Rapid calls to `loadNextPage` result in 1 repository call.

3. Scroll triggers:
   - `onListScrolled` with `total - lastVisible <= threshold` triggers `loadNextPage`.
   - With `hasReachedEnd == true`, `loadNextPage` is not called.

4. Download state mapping:
   - When `DownloadRepository` emits entries with `playlistId`, ViewModel aggregates correctly:
     - `progressPercent`, `downloadedCount`, `totalCount` stay within expected ranges.
   - When there are no entries for playlist, `PlaylistDownloadUiState` defaults to idle.

Constraints (per AGENTS.md):
- Keep tests deterministic; no network or real NewPipe calls in unit tests.
- Use timeouts from global policy (already enforced via Gradle).

### 6.2 UI Tests – Phone & Tablet

Use Robolectric and/or Espresso (AndroidX test) for:

1. Layout verification (phone, default locale):
   - Header shows title, channel, metadata, actions, policy text when `HeaderState.Success`.
   - Video list renders items using `item_video_list`.
   - Scrolling collapses header, toolbar stays sticky.

2. Skeleton states:
   - When `HeaderState.Loading` and items `LoadingInitial`, header skeleton and list skeleton visible, no spinners.
   - After success, skeletons hidden.

3. Error & empty:
   - Header error: error message & retry visible, list not loaded.
   - Items `Empty`: empty state message “This playlist has no videos yet” appears.

4. RTL (Arabic locale):
   - Launch in Arabic; verify:
     - Titles are aligned start.
     - Buttons order remains logical but mirrored where appropriate.
     - No “left/right” alignment issues.

5. Tablet layouts (`sw600dp`, `sw720dp`):
   - Use appropriate device qualifiers or Robolectric configuration.
   - Verify:
     - Different paddings and content width constraints.
     - Buttons layout horizontally when space allows.
     - Navigation rail and bottom padding interplay.

### 6.3 Integration Tests (Optional but Recommended)

Integration test (may run as slower test set, honoring 300s suite timeout):

- Scope:
  - Use `NewPipeExtractorClient` against known public playlist ID (test‑only).
  - Verify:
    - `PlaylistDetailRepository.getHeader()` returns non‑empty title and item count.
    - `getItems` preserves order and returns non‑empty list.
- Implementation:
  - Mark as integration (e.g., custom JUnit category or package).
  - Ensure:
    - Per‑test timeout ≤ 30s.
    - Skipped in offline CI environments if needed.

---

## 7. Risks & Edge Cases

### 7.1 Large Playlists (hundreds/thousands of items)

Risks:
- Memory usage if all items kept in a single list.
- Long initial load or download queue building.

Mitigations:
- Paginate UI strictly, only keeping items already seen in memory (which is fine for RecyclerView).
- For playlist download:
  - Build download list lazily:
    - Either load items page by page in a background coroutine to avoid blocking UI.
    - Or limit max playlist items for download according to a PRD or app setting (e.g., cap at 500 items).
- Monitor performance: ensure app remains responsive and respects overall performance requirements in `PRD.md`.

### 7.2 Unavailable / Removed Videos

NewPipe may return placeholders or fail for certain items.

Strategy:
- In `PlaylistDetailRepository`:
  - Filter out entries that don’t map cleanly to `PlaylistItem` (missing `url` or `videoId`).
- In UI:
  - Accept potential gaps in position numbering; or recompute `position` based on filtered items.
- For downloads:
  - Skip items lacking valid stream info; show aggregated error message at playlist level.

### 7.3 Missing Metadata

Cases:
- No channel info, no description, unknown item count.

Handling:
- Use fallback strings:
  - Title only, no channel line if channel name unavailable.
  - Hide description view when null/blank.
  - Replace “N videos” with “Videos” when count is unknown.
- Avoid showing placeholders that cannot be localized.

### 7.4 NewPipe Failures / Network Errors

- Header extraction failure:
  - Show header error state but keep navigation functional.
  - Provide “Retry” that re‑invokes `loadHeader(forceRefresh = true)`.
- Items extraction failure:
  - For initial page: show `ErrorInitial` overlay and retry button.
  - For pagination: `ErrorAppend` while keeping already loaded items; allow manual retry.

### 7.5 Download Pipeline Failures

- Quota / policy errors from backend or network:
  - Leverage existing error handling in `DownloadWorker` + `DownloadRepository` (notifications, messages).
  - Reflect errors in `PlaylistDownloadUiState` to show inline message in header (“Download failed, tap to retry”).
- Storage full:
  - Rely on OS; show friendly message when WorkManager reports failure.

### 7.6 Performance & Smooth Scrolling

- Ensure `videosRecyclerView`:
  - Uses `adapter.setHasStableIds(true)` with `getItemId()` implemented for scroll restoration across config changes (see 4.2 for guidance).
  - Uses Coil with appropriate placeholder and `crossfade(true)` but not heavy transformations.
- Debounce pagination:
  - Rate limit `loadNextPage` to once per second and check near‑end threshold.
- Avoid heavy work on main thread:
  - All NewPipe calls and playlist download list building in `Dispatchers.IO`.

---

## 8. Suggested Implementation Steps (Engineer Checklist)

1. **Domain & Repository**
   1.1 Add playlist models (`PlaylistHeader`, `PlaylistItem`, `PlaylistPage`) in `data.playlist`.
   1.2 Implement `PlaylistDetailRepository` + `NewPipePlaylistDetailRepository`.
   1.3 Wire DI for `PlaylistDetailRepository`.

2. **ViewModel**
   2.1 Refactor `PlaylistDetailViewModel` to use new repository, header/items state flows, and pagination controller.
   2.2 Integrate `DownloadRepository` and expose `PlaylistDownloadUiState`.
   2.3 Implement `loadHeader`, `loadInitial`, `loadNextPage`, retry methods, scroll handling.
   2.4 Add playlist download actions (`startPlaylistDownload`, etc.).

3. **Download System**
   3.1 Extend `DownloadRequest`, `DownloadScheduler`, and `DownloadWorker` with playlist metadata fields.
   3.2 Implement `enqueuePlaylist` helper and duplicate‑prevention logic.
   3.3 Update Downloads tab (if needed) to show aggregated playlist progress.

4. **UI Layouts**
   4.1 Replace `fragment_playlist_detail.xml` with CoordinatorLayout + Collapsing header + RecyclerView + skeletons.
   4.2 Create `layout-sw600dp` and `layout-sw720dp` variants mirroring Channel Detail patterns.
   4.3 Ensure design tokens and RTL support across layouts.

5. **Adapters & Fragment**
   5.1 Create `PlaylistVideosAdapter` reusing `item_video_list`, adding download indicators.
   5.2 Update `PlaylistDetailFragment`:
       - Bind header/list states and skeletons.
       - Wire toolbar back, channel click, action buttons.
       - Wire download bottom sheet.
   5.3 Remove direct `ContentService` usage from fragment & ViewModel.

6. **Testing**
   6.1 Implement `PlaylistDetailViewModelTest` (cover loading, pagination, errors, rate‑limit, downloads state).
   6.2 Add UI tests for phone/tablet layouts and RTL.
   6.3 Optional: integration test(s) for `PlaylistDetailRepository` using NewPipe.

7. **Manual QA**
   7.1 Test on phone, tablet (sw600dp), and large tablet/TV (sw720dp).
   7.2 Verify:
       - Multi‑device design checklist in AGENTS.md.
       - Performance (scroll smoothness, load times).
       - Offline playlist download flow end‑to‑end (including Downloads tab progress).
