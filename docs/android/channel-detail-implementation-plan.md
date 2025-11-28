# Channel Detail Screen – NewPipe Implementation Plan

> Albunyaan Tube Android app – Channel Detail (Videos / Live / Shorts / Playlists / About)
> Scope: Native Android, NewPipeExtractor only (no backend calls for this screen)

---

## 1. Architecture & Data Flow

### 1.1 Overview

- **Host fragment**: `ChannelDetailFragment`
  - Owns toolbar, shared channel header, `TabLayout` + `ViewPager2`.
  - Hosts five tab fragments via `ChannelDetailPagerAdapter`.
- **Tabs (fragments)**:
  - `ChannelVideosTabFragment`
  - `ChannelLiveTabFragment`
  - `ChannelShortsTabFragment`
  - `ChannelPlaylistsTabFragment`
  - `ChannelAboutTabFragment`
  - All use the same `ChannelDetailViewModel` scoped to `ChannelDetailFragment`.
- **Data source**:
  - Dedicated `ChannelDetailRepository` that talks **only** to `NewPipeExtractorClient`.
  - No backend/API calls for this screen, except the existing `channelId` coming from the rest of the app.

### 1.2 Domain Models

Create channel‑specific domain models (Kotlin data classes) that map the raw NewPipe data into UI‑friendly shapes:

- `ChannelHeader`
  - `id: String`
  - `title: String`
  - `avatarUrl: String?`
  - `bannerUrl: String?`
  - `subscriberCount: Long?`
  - `shortDescription: String?` (short teaser text under the title)
  - `summaryLine: String?` (e.g., “Shaikh Mishary Bin Rashid AlAfasy, Kuwait”)
  - `fullDescription: String?`
  - `links: List<ChannelLink>` (name + URL)
  - `location: String?`
  - `joinedDate: Instant?`
  - `totalViews: Long?`

- `ChannelVideo`
  - Wraps the data we already show in `ContentItem.Video`: id, title, duration, views, uploaded time, category, thumbnail.

- `ChannelShort`
  - `id`, `title`, `thumbnailUrl`, `viewCount`, `durationSeconds`, `publishedTime`.
  - Optimized for Shorts grid layout (9:16 thumbnails).

- `ChannelLiveStream`
  - `id`, `title`, `thumbnailUrl`, `isLiveNow`, `isUpcoming`, `scheduledStartTime`, `viewCount`.

- `ChannelPlaylist`
  - Mirrors `ContentItem.Playlist`: id, title, item count, category, thumbnail.

- `ChannelPage<T>`
  - `items: List<T>`
  - `nextCursor: String?` (cursor/token/url returned by NewPipe)
  - `fromCache: Boolean` (optional; helpful for footer messaging later).

### 1.3 Repository Interface

`ChannelDetailRepository` (in `data/channel/` or similar):

```kotlin
interface ChannelDetailRepository {
    suspend fun getChannelHeader(channelId: String, forceRefresh: Boolean = false): ChannelHeader

    suspend fun getVideos(channelId: String, cursor: String?): ChannelPage<ChannelVideo>
    suspend fun getLiveStreams(channelId: String, cursor: String?): ChannelPage<ChannelLiveStream>
    suspend fun getShorts(channelId: String, cursor: String?): ChannelPage<ChannelShort>
    suspend fun getPlaylists(channelId: String, cursor: String?): ChannelPage<ChannelPlaylist>

    suspend fun getAbout(channelId: String, forceRefresh: Boolean = false): ChannelHeader
}
```

Implementation details:

- Backed by `NewPipeExtractorClient`.
- Uses NewPipe's `ChannelInfo` + related list API to fetch:
  - Full channel metadata (header + about).
  - Tab‑specific lists (videos, shorts, live, playlists).
- Optional in‑memory per‑channel cache:
  - Cache header + first page per tab keyed by `channelId`.
  - TTL ~30 minutes (aligned with `STREAM_CACHE_TTL_MILLIS`).

### 1.4 ViewModel & State

Refactor `ChannelDetailViewModel` to depend on `ChannelDetailRepository` (not `ContentService`):

- Constructor:

```kotlin
class ChannelDetailViewModel @AssistedInject constructor(
    private val repository: ChannelDetailRepository,
    @Assisted private val channelId: String
) : ViewModel()
```

- Exposed state:

```kotlin
sealed class HeaderState {
    object Loading : HeaderState()
    data class Success(val header: ChannelHeader) : HeaderState()
    data class Error(val message: String) : HeaderState()
}

sealed class PaginatedState<T> {
    object Idle : PaginatedState<Nothing>()
    object LoadingInitial : PaginatedState<Nothing>()
    data class Loaded<T>(
        val items: List<T>,
        val nextCursor: String?,
        val isAppending: Boolean = false
    ) : PaginatedState<T>()
    object Empty : PaginatedState<Nothing>()
    data class ErrorInitial(val message: String) : PaginatedState<Nothing>()
    data class ErrorAppend<T>(
        val message: String,
        val items: List<T>,
        val nextCursor: String?
    ) : PaginatedState<T>()
}
```

- State flows (one shared VM per channel):

```kotlin
val headerState: StateFlow<HeaderState>

val videosState: StateFlow<PaginatedState<ChannelVideo>>
val liveState: StateFlow<PaginatedState<ChannelLiveStream>>
val shortsState: StateFlow<PaginatedState<ChannelShort>>
val playlistsState: StateFlow<PaginatedState<ChannelPlaylist>>
val aboutState: StateFlow<HeaderState> // reuse header data for About tab
```

- Public API:
  - `fun loadHeader(forceRefresh: Boolean = false)`
  - `fun loadInitial(tab: ChannelTab, forceRefresh: Boolean = false)`
  - `fun loadNextPage(tab: ChannelTab)`
  - `fun retryInitial(tab: ChannelTab)`
  - `fun retryAppend(tab: ChannelTab)`

### 1.5 Pagination & Rate Limiting

- Maintain a `TabPaginationController` per tab inside the ViewModel:

```kotlin
private data class TabPaginationController(
    var isInitialLoading: Boolean = false,
    var isAppending: Boolean = false,
    var nextCursor: String? = null,
    var hasReachedEnd: Boolean = false,
    var lastAppendRequestMs: Long = 0L
)
```

- Pagination logic in `loadNextPage(tab)`:
  - Return early if:
    - `controller.isAppending` is true.
    - `controller.hasReachedEnd` is true.
    - `controller.nextCursor == null`.
    - `now - lastAppendRequestMs < MIN_APPEND_INTERVAL_MS` (e.g., 1000 ms).
  - Otherwise:
    - Set `isAppending = true`, update `lastAppendRequestMs`.
    - Call the repository with current `nextCursor`.
    - On success:
      - Append new items to existing list.
      - Update `nextCursor` (null if no more pages; set `hasReachedEnd`).
    - On failure:
      - Emit `ErrorAppend` with current items and cursor.
      - Set `isAppending = false`.

- Scroll events are forwarded from tab fragments:
  - Each list tab calls `viewModel.onListScrolled(tab, lastVisibleItem, totalCount)` and the ViewModel decides when to call `loadNextPage`.
  - Threshold: trigger when `totalCount - lastVisibleItem <= 5`.

---

## 2. UI & Layout

### 2.1 Channel Header (Phone – `layout/fragment_channel_detail.xml`)

**Implemented Structure (using CoordinatorLayout for collapsing header):**

```
CoordinatorLayout
├── AppBarLayout
│   ├── CollapsingToolbarLayout (scroll|exitUntilCollapsed)
│   │   ├── headerContent (LinearLayout, collapseMode="parallax")
│   │   │   ├── exclusionBanner (visibility=gone by default)
│   │   │   ├── FrameLayout
│   │   │   │   ├── channelBanner (ImageView, @dimen/channel_banner_height)
│   │   │   │   └── bannerGradient (View, @drawable/banner_gradient_overlay)
│   │   │   └── ConstraintLayout (channel info)
│   │   │       ├── channelAvatar (ShapeableImageView, circular)
│   │   │       ├── channelNameContainer (LinearLayout, horizontal)
│   │   │       │   ├── channelNameText
│   │   │       │   └── verifiedBadge
│   │   │       ├── subscriberCountText
│   │   │       └── channelSummaryText
│   │   └── toolbar (MaterialToolbar, collapseMode="pin")
│   └── tabLayout (TabLayout, sticky)
├── contentContainer (FrameLayout, appbar_scrolling_view_behavior)
│   ├── viewPager (ViewPager2)
│   ├── contentSkeleton (loading indicator)
│   └── contentErrorState (include @layout/error_state)
└── headerSkeleton (LinearLayout, overlays AppBarLayout during loading)
```

**Key implementation details:**

- `CollapsingToolbarLayout` with `scroll|exitUntilCollapsed` scroll flags
- `headerContent` uses `collapseMode="parallax"` for smooth collapse effect
- `toolbar` uses `collapseMode="pin"` to stay visible when fully collapsed
- `tabLayout` placed outside `CollapsingToolbarLayout` but inside `AppBarLayout` for sticky behavior
- `contentContainer` uses `appbar_scrolling_view_behavior` to coordinate with AppBar
- `headerSkeleton` positioned with `layout_marginTop="?attr/actionBarSize"` to appear below pinned toolbar

**Note:** The "General" button was removed from the initial implementation to simplify the header. It may be added later for category indication.

### 2.2 Channel Header (Tablet – `layout-sw600dp/fragment_channel_detail.xml`) ✅ IMPLEMENTED

Uses the **same CoordinatorLayout structure** as phone layout with identical view IDs. Key differences:

- Uses `@dimen/channel_banner_height_tablet` for banner height
- Uses `@dimen/channel_avatar_size_tablet` for larger avatar
- Increased paddings via device-specific dimens in `values-sw600dp/dimens.xml`
- All text uses `textAlignment="viewStart"` and `start`/`end` constraints for RTL support

### 2.3 Channel Header (Large Tablet / TV – `layout-sw720dp/fragment_channel_detail.xml`) ✅ IMPLEMENTED

Uses the **same CoordinatorLayout structure** with identical view IDs. Key differences:

- Uses `@dimen/channel_banner_height_tv` for larger banner
- Uses `@dimen/channel_avatar_size_tv` for larger avatar
- More generous horizontal margins via `values-sw720dp/dimens.xml`
- Tab labels remain at readable size for TV distance
- All focusable elements (tabs, list items) support D-pad/remote navigation

### 2.4 Tab Container Layouts

#### Base list tab layout (Videos, Live, Playlists)

Replace the placeholder `fragment_channel_detail_tab.xml` with a reusable list container:

- Root: `FrameLayout`
  - `SwipeRefreshLayout`
    - `RecyclerView` `@id/tabRecycler`
      - `LinearLayoutManager` vertical by default.
      - On tablet we can swap to grid if desired (using `calculateGridSpanCount` util).
  - Overlays:
    - Skeleton container `@id/tabSkeletonContainer`
      - Vertical column of 4–6 `include` of `@layout/skeleton_content_item`.
    - `<include layout="@layout/empty_state" android:id="@+id/tabEmptyState" android:visibility="gone" />`
    - `<include layout="@layout/error_state" android:id="@+id/tabErrorState" android:visibility="gone" />`

This layout is shared by `ChannelVideosTabFragment`, `ChannelLiveTabFragment`, `ChannelPlaylistsTabFragment`.

#### Shorts tab layout

Create `fragment_channel_shorts_tab.xml`:

- Root: `SwipeRefreshLayout`
  - `RecyclerView` `@id/shortsRecycler`
    - `GridLayoutManager`.
    - Phone: 2 columns.
    - Tablet: 4–5 columns using `calculateGridSpanCount(itemMinWidthDp = 160)` or similar.
- Overlays similar to base list layout:
  - `@id/shortsSkeletonContainer` with grid of card skeletons.
  - `shortsEmptyState`, `shortsErrorState`.

#### About tab layout

Create `fragment_channel_about_tab.xml`:

- Root: `NestedScrollView`
  - `LinearLayout` vertical with sections:
    - **Description**
      - Section title text “Description”.
      - `TextView` for full channel description, `textAlignment="viewStart"`, multi‑line.
    - **Links**
      - Section title “Links”.
      - For each `ChannelLink`:
        - Row with icon (e.g., globe / Instagram) + link name and URL (underlined).
    - **More info**
      - Section title “More info”.
      - Rows for:
        - Website.
        - Location.
        - Joined date.
        - Total views.
- Skeleton:
  - A vertical stack of shimmer lines at the top while `headerState` for About is still `Loading`.

### 2.5 Item Layout Reuse & New Items

- **Videos tab**:
  - Reuse `res/layout/item_video_list.xml`.
  - Adapter uses existing binding logic (title, meta text, chip for category, thumbnail via `ImageLoading`).

- **Live tab**:
  - Base item: `item_video_list.xml`.
  - Add a “LIVE” badge:
    - Either overlay a `TextView` on the thumbnail (bottom‑left) or next to title.
    - Background: solid red (`@color/accent_red`), text “LIVE” in white, all caps.

- **Playlists tab**:
  - Reuse `res/layout/item_playlist.xml` + `PlaylistAdapter`.

- **Shorts tab**:
  - New layout `item_channel_short.xml`:
    - Root `FrameLayout` with `ConstraintLayout` inside.
    - Thumbnail 9:16, full height of card.
    - Title overlay at top (2 lines).
    - Views label at bottom (e.g., "13K views").
  - Skeleton: card with black rectangle shimmer and short text shimmer overlays.

### 2.6 RTL & Localization

- Use `android:textAlignment="viewStart"` on all text views instead of `center`/`left`.
- Use `layout_marginStart` / `layout_marginEnd` and `app:layout_constraintStart_toStartOf`, etc., never `left`/`right`.
- All labels should come from resources with translations for English, Arabic (RTL), Dutch.
- Numeric formatting:
  - Use `NumberFormat.getInstance(Locale.getDefault())` for subscriber counts, views, etc.
- Dates / “time ago”:
  - Reuse existing duration/time utilities used by `VideoGridAdapter` or Home lists to ensure localized strings.

---

## 3. State Handling

### 3.1 Header State

- Initial:
  - `headerState = Loading`.
  - Show header skeleton; hide real header content and header error text.
- On success:
  - `headerState = Success(header)`.
  - Populate:
    - Toolbar title with `header.title`.
    - Avatar + banner images using existing image loading utilities.
    - Subscriber count text with formatted number (`"11,700,000 subscribers"`).
    - Summary line (name + nationality etc.) if available.
  - Hide header skeleton; show header content.
- On error:
  - `headerState = Error(message)`.
  - Hide header skeleton and content container.
  - Show `errorText` with localized message and possibly a “Retry” action or button.

### 3.2 Tab State

Per list tab (Videos, Live, Shorts, Playlists):

- `Idle`:
  - Tab not yet requested (e.g., user hasn’t visited).
  - Fragment displays skeleton only when we explicitly start loading.
- `LoadingInitial`:
  - Show tab skeleton container.
  - Hide RecyclerView, empty state, and error state.
- `Loaded` (first page):
  - Hide skeleton.
  - Show RecyclerView with items.
  - Hide empty/error overlays.
- `Empty`:
  - Hide skeleton and list.
  - Show `empty_state` include with tab‑specific copy:
    - E.g., “This channel has no videos yet” (Videos).
    - E.g., “No upcoming or live streams” (Live).
- `ErrorInitial`:
  - Hide skeleton and list.
  - Show `error_state` include with retry button wired to `retryInitial(tab)`.
- `Appending` (while `Loaded.isAppending = true`):
  - Keep list visible.
  - Show footer skeleton rows (e.g., adapter adds extra skeleton items at end).
- `ErrorAppend`:
  - Keep existing items visible.
  - Hide footer skeleton.
  - Show small inline error message at bottom or Snackbar “Couldn’t load more. Tap to retry.”

### 3.3 Concurrency & Scroll Abuse Protection

- All loading operations are funneled through `ChannelDetailViewModel` (tabs never call repository directly).
- Guards:
  - One active request per tab at a time.
  - Debounce `loadNextPage` calls with minimum interval.
  - Do not retry append automatically in a tight loop; always require user action (scroll or explicit retry).

### 3.4 Partial Loads

- Header success, tab error:
  - Header remains visible.
  - Specific tab shows `error_state` while others can work normally.
- Header error, tab success:
  - If header fetch fails but list fetch works (unlikely but possible):
    - Show header error text while still allowing tab navigation.
    - Avoid blocking entire screen.

---

## 4. Navigation & State Restoration

### 4.1 Entry Points

- From `ChannelsFragmentNew`:
  - Already navigates to `ChannelDetailFragment` with args:
    - `channelId`, `channelName`, `excluded`.
- From Home sections (`HomeFragmentNew`):
  - Channels section, if present, should use the same arguments.
- From Search:
  - Channel search results should also route here.
- From Featured:
  - `FeaturedListFragment` already navigates to `ChannelDetailFragment` on channel tap.

Ensure all entry points pass at least:

- `channelId: String` (required for NewPipe lookups).
- `channelName: String?` (optional for toolbar placeholder).
- `excluded: Boolean` (for moderation banner).

### 4.2 Back Navigation

- Toolbar back button:
  - `toolbar.setNavigationOnClickListener { findNavController().navigateUp() }`.
- System back:
  - Default navigation stack behavior.

### 4.3 State Restoration

- `ChannelDetailViewModel` holds all header + tab data, so on configuration change:
  - Fragments re‑bind to flows and UI state is restored.
- Preserve selected tab:
  - Save `viewPager.currentItem` in `onSaveInstanceState`.
  - Or keep a `MutableStateFlow<Int>` in ViewModel and bind both `TabLayout` and `ViewPager2` to it:
    - On page change, update the flow.
    - On recreation, set `viewPager.currentItem` from the flow.
- RecyclerView scroll:
  - Rely on default `RecyclerView` state saving within each tab fragment (use standard fragment lifecycle + view binding).

### 4.4 Deep Links

- Keep or implement `albunyaantube://channel/{channelId}`:
  - Maps to `ChannelDetailFragment`.
  - Uses same NewPipe‑based header + tabs; no backend usage for details.

---

## 5. Testing

### 5.1 Unit Tests (ViewModel)

Use JUnit 5 + Coroutines test (`runTest`):

- Header loading:
  - Given repository success:
    - `headerState` emits `Loading` → `Success`.
  - Given repository failure:
    - `headerState` emits `Loading` → `Error(message)`.
- Videos tab:
  - Initial load (no cursor):
    - `videosState` emits `LoadingInitial` → `Loaded(items, nextCursor)`.
  - Empty channel:
    - Emits `LoadingInitial` → `Empty`.
  - Pagination:
    - With `nextCursor != null`, `loadNextPage` appends items and updates cursor.
    - When `nextCursor == null`, `loadNextPage` is a no‑op.
  - ErrorAppend:
    - On append failure, state becomes `ErrorAppend` with existing items preserved.
- Rate limiting:
  - Multiple rapid `loadNextPage` calls (within debounce window) yield **one** repository invocation.
- Other tabs:
  - Mirror tests for Live, Shorts, Playlists using dedicated fake repository responses.

### 5.2 UI Tests (Espresso / Robolectric)

**Phone:**

- Launch `ChannelDetailFragment` with a fake ViewModel:
  - Header:
    - When `HeaderState.Loading`, verify skeleton views are visible and content is hidden.
    - When `Success`, check banner, avatar, title, subscribers, summary show correctly.
    - When `Error`, verify error text is visible and skeleton is hidden.
  - Tabs:
    - Verify tab titles order: Videos, Live, Shorts, Playlists, About.
    - Videos tab:
      - Shows `item_video_list` rows.
      - Rendering of title, meta, thumbnail.
      - Empty and error states show corresponding layouts.
    - Live tab:
      - Verify “LIVE” badge visible for `isLiveNow` items.
    - Shorts tab:
      - RecyclerView uses grid with 2 columns.
      - Items use `item_channel_short` layout.
    - About tab:
      - Description, links, and “More info” rows rendered correctly.

**Tablet (`sw600dp`):**

- Run tests under tablet configuration:
  - Header:
    - Avatar and text layout horizontally as per tablet design.
    - Tab layout spans full width.
  - Shorts grid:
    - Column count > 2 (e.g., 4–5).

**Large tablet / TV (`sw720dp`):**

- Run tests with `sw720dp` qualifier (large tablet or TV target):
  - Header:
    - Banner height uses TV/large‑screen dimen.
    - Avatar, title, subscribers, summary, and “General” button positioned according to the large‑screen layout (centered content, generous side margins).
  - Tabs:
    - Tab labels remain readable at TV distance (check text size).
    - Focus navigation with D‑pad/keyboard moves predictably between tabs and list content.
  - Content:
    - Shorts grid uses higher column count (e.g., 5–6 on wide TV).
    - Videos/Playlists lists remain legible and not overly dense.

**RTL:**

- Force Arabic locale:
  - Verify:
    - Text alignment is `viewStart`.
    - Avatar + header flip correctly (using `start` / `end` constraints).
    - Tab text order matches RTL expectations.

### 5.3 Integration Tests (NewPipeExtractor)

- NewPipe integration (instrumented or JVM with network):
  - For a known public channel ID:
    - `getChannelHeader` returns non‑null name + avatar + subscriber count.
    - `getVideos` returns first page with items and (if popular) a non‑null `nextCursor`.
    - `getPlaylists`, `getShorts`, `getLiveStreams` either return items or cleanly return empty lists when not supported.
  - Simulate network/Extractor errors:
    - Ensure exceptions are mapped to `ErrorInitial` or `ErrorAppend` in the ViewModel.

---

## 6. Risks & Edge Cases

### 6.1 Missing Content

- Channels with:
  - No videos → Videos tab shows empty state.
  - No playlists → Playlists tab empty state.
  - No shorts → Shorts tab shows empty state.
  - No description → About tab hides the Description section and shows a short "No description available" message.

### 6.2 Extractor & Network Issues

- If NewPipe breaks for a specific feed:
  - Repository returns empty lists, not crashes; we surface empty states rather than infinite spinners.
- Network timeouts / `ExtractionException`:
  - Mapped to `ErrorInitial` or `ErrorAppend` with retry entry points.

### 6.3 Placeholders Getting “Stuck”

- Rules:
  - Every try/catch path in the ViewModel must end in a non‑Loading state.
  - Skeleton visibility is inferred from state only:
    - `LoadingInitial` → skeleton visible.
    - All other states → skeleton hidden.
  - On error:
    - Show `error_state` or `ErrorAppend`, never leave skeletons on screen.

### 6.4 Performance

- Respect PRD performance requirements:
  - Initial header + first tab data should appear quickly:
    - Fetch header and first videos page in parallel when possible.
  - Use modest page sizes (10–20 items) to keep memory usage low.
  - Rely on NewPipe’s streaming and metadata caches for repeat views.
- Ensure no tight request loops:
  - Debounce pagination.
  - Guard against multiple concurrent loads per tab.

---

## 7. Implementation Checklist

1. **Data layer** ✅ COMPLETE
   - ✅ `ChannelDetailRepository` interface and `NewPipeChannelDetailRepository` implementation
   - ✅ Channel feed extraction (videos, live, shorts, playlists, about)
   - ✅ In-memory caching for header and first page of each tab
2. **ViewModel** ✅ COMPLETE
   - ✅ `ChannelDetailViewModel` with `HeaderState` and `PaginatedState` sealed classes
   - ✅ `TabPaginationController` for rate limiting and state management
   - ✅ Assisted injection with `@AssistedFactory` for channelId parameter
3. **Fragments** ✅ COMPLETE
   - ✅ Five concrete tab fragments: `ChannelVideosTabFragment`, `ChannelLiveTabFragment`, `ChannelShortsTabFragment`, `ChannelPlaylistsTabFragment`, `ChannelAboutTabFragment`
   - ✅ `BaseChannelListTabFragment` for shared list behavior
   - ✅ All fragments share the parent-scoped ViewModel
4. **Layouts** ✅ COMPLETE
   - ✅ `fragment_channel_detail.xml` with CoordinatorLayout + CollapsingToolbarLayout
   - ✅ `layout-sw600dp/fragment_channel_detail.xml` for tablets
   - ✅ `layout-sw720dp/fragment_channel_detail.xml` for large tablets/TV
   - ✅ `fragment_channel_list_tab.xml` for Videos, Live, Playlists tabs
   - ✅ `fragment_channel_shorts_tab.xml` for Shorts grid
   - ✅ `fragment_channel_about_tab.xml` for About section
   - ✅ Header skeleton + content skeleton with proper loading states
5. **Adapters & UI** ✅ COMPLETE
   - ✅ `ChannelVideoAdapter` reusing `item_video_list.xml` pattern
   - ✅ `ChannelLiveAdapter` with LIVE/UPCOMING badges
   - ✅ `ChannelShortsAdapter` with 9:16 grid items
   - ✅ `ChannelPlaylistsAdapter` reusing existing playlist pattern
   - ✅ About tab with description, links, and channel info sections
6. **Navigation & State** ✅ COMPLETE
   - ✅ Navigation from Channels list, Home, Featured, and Search
   - ✅ Tab selection persisted via `onSaveInstanceState`
   - ✅ RecyclerView scroll positions restored via default fragment lifecycle
7. **Testing** ✅ COMPLETE
   - ✅ `ChannelDetailViewModelTest.kt` - unit tests for all states and pagination
   - ✅ `ChannelDetailFragmentTest.kt` - UI tests for core functionality
   - ✅ `ChannelDetailDeviceTest.kt` - device-specific layout tests
   - ✅ `ChannelDetailRtlTest.kt` - RTL layout verification
   - ✅ `FakeChannelDetailRepository` + `TestChannelDetailModule` for test isolation

This document should be treated as the authoritative implementation plan for the NewPipe‑backed Channel Detail screen. Any deviations during implementation should be reflected by updating this file.

---

## 8. Implementation Notes & Known Limitations

### 8.1 Posts Tab (Community Posts) - REMOVED

The Posts tab has been **fully removed** from the implementation because NewPipeExtractor does not support YouTube Community Posts extraction. The `ChannelTabs` class only supports:
- VIDEOS, TRACKS, SHORTS, LIVESTREAMS, CHANNELS, PLAYLISTS, ALBUMS, LIKES

There is no POSTS/COMMUNITY constant. Rather than showing an empty tab, the Posts tab was removed entirely to avoid user confusion.

Reference: https://teamnewpipe.github.io/NewPipeExtractor/javadoc/org/schabi/newpipe/extractor/channel/tabs/ChannelTabs.html

**Removed files:**
- `ChannelPostsTabFragment.kt`
- `ChannelPostsAdapter.kt`
- `item_channel_post.xml`
- `ChannelPost` data class
- Repository `getPosts()` method
- ViewModel `postsState` StateFlow

**Tabs now supported:** Videos, Live, Shorts, Playlists, About (5 tabs total)

### 8.2 About Tab Data Availability

NewPipe's `ChannelInfo` provides:
- ✅ Name, description, avatars, banners
- ✅ Subscriber count
- ✅ Verification status
- ✅ Donation links (external URLs like Patreon, PayPal)
- ✅ Tags
- ❌ Location (not available from YouTube's public channel page)
- ❌ Join date (not exposed by the channel extractor)
- ❌ Total views (YouTube removed this from public channel pages)

The About tab shows available data and hides rows for unavailable fields.

### 8.3 NewPipe Initialization

The `NewPipeChannelDetailRepository` injects `NewPipeExtractorClient` to ensure NewPipe is properly initialized with:
- Shared OkHttpDownloader instance
- US localization and content country
- Metrics reporting integration

This prevents issues when the repository is accessed before the player/stream resolver.

### 8.4 Double Initial Load Prevention

The Videos tab is pre-loaded by `ChannelDetailViewModel.loadHeader()`. The `BaseChannelListTabFragment` now loads tabs in `onResume()` only if state is `Idle`, preventing duplicate requests for the Videos tab while ensuring other tabs load when first viewed.

### 8.5 Unit Test Coverage (Added)

Unit tests for `ChannelDetailViewModel` cover:
- Header loading (success, error, retry)
- Videos tab pagination and rate limiting
- Empty state handling for all tabs
- Error handling (ErrorInitial, ErrorAppend)
- Scroll-triggered pagination
- Tab selection state

Located at: `android/app/src/test/java/com/albunyaan/tube/ui/detail/ChannelDetailViewModelTest.kt`

### 8.6 Collapsing Toolbar with Sticky Tabs (Added Nov 2025)

The layout uses `CoordinatorLayout` + `AppBarLayout` + `CollapsingToolbarLayout` for smooth scrolling behavior:

**Structure:**
```
CoordinatorLayout
├── AppBarLayout
│   ├── CollapsingToolbarLayout (scroll|exitUntilCollapsed)
│   │   ├── headerContent (LinearLayout, collapseMode="parallax")
│   │   │   ├── exclusionBanner
│   │   │   ├── channelBanner + bannerGradient
│   │   │   └── channelAvatar, name, subscribers, summary
│   │   └── toolbar (collapseMode="pin")
│   └── tabLayout (sticky, does not scroll)
├── contentContainer (appbar_scrolling_view_behavior)
│   ├── viewPager
│   ├── contentSkeleton
│   └── contentErrorState
└── headerSkeleton (overlays AppBarLayout during loading)
```

**Key behaviors:**
- Banner and channel info collapse with parallax effect when scrolling
- Toolbar pins at top when fully collapsed (back button always accessible)
- TabLayout remains sticky below the toolbar
- Tab content (RecyclerViews) scrolls independently with proper nested scrolling

**View binding renames (Nov 2025):**
- `contentContainer` → wraps ViewPager, skeleton, and error states
- `headerContent` → the actual header content (banner, avatar, info)
- `contentSkeleton` → loading indicator for content area
- `contentErrorState` → error state shown in content area (not full screen)

### 8.7 Loading State Improvements (Added Nov 2025)

The loading and error states were improved to keep the toolbar accessible at all times:

**AppBarLayout always visible:** During loading and error states, the `AppBarLayout` (containing the toolbar with back button) remains visible. This ensures users can always navigate back, even if the channel fails to load.

**Separate skeleton areas:**
1. `headerSkeleton` - Overlays the header area below the toolbar, shows shimmer placeholders for banner, avatar, name, subscribers, and tabs
2. `contentSkeleton` - Shown in the content area below AppBar, displays a centered progress indicator with "Loading…" text

**State visibility matrix:**

| State | headerSkeleton | headerContent | tabLayout | viewPager | contentSkeleton | contentErrorState |
|-------|----------------|---------------|-----------|-----------|-----------------|-------------------|
| Loading | ✅ visible | ❌ gone | ❌ gone | ❌ gone | ✅ visible | ❌ gone |
| Success | ❌ gone | ✅ visible | ✅ visible | ✅ visible | ❌ gone | ❌ gone |
| Error | ❌ gone | ❌ gone | ❌ gone | ❌ gone | ❌ gone | ✅ visible |

**Multi-device layouts:** All three layout variants (phone, tablet `sw600dp`, large tablet/TV `sw720dp`) follow this same structure with identical view IDs for binding compatibility.

### 8.8 UI Tests (Added Nov 2025)

Comprehensive UI tests were added covering all device configurations and states:

**Test classes:**
- `ChannelDetailFragmentTest.kt` - Core functionality tests (header states, tab navigation, data binding)
- `ChannelDetailDeviceTest.kt` - Device-specific tests (phone, tablet, large tablet layouts)
- `ChannelDetailRtlTest.kt` - RTL layout tests for Arabic locale

**Test utilities:**
- `LocaleTestRule.kt` - JUnit rule for temporarily switching locale in tests
- `ViewStateIdlingResource.kt` - Espresso idling resource for waiting on StateFlow emissions
- `FakeChannelDetailRepository.kt` - Configurable fake for controlling test scenarios
- `TestChannelDetailModule.kt` - Hilt test module providing fake repository

**Coverage areas:**
- Loading, success, and error states for header
- Tab navigation and content display
- Skeleton visibility during loading
- Error retry functionality
- RTL text alignment and layout mirroring
- Device-specific layout assertions (column counts, spacing)
