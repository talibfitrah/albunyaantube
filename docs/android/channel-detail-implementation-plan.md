# Channel Detail Screen – NewPipe Implementation Plan

> Albunyaan Tube Android app – Channel Detail (Videos / Live / Shorts / Playlists / Posts / About)  
> Scope: Native Android, NewPipeExtractor only (no backend calls for this screen)

---

## 1. Architecture & Data Flow

### 1.1 Overview

- **Host fragment**: `ChannelDetailFragment`
  - Owns toolbar, shared channel header, `TabLayout` + `ViewPager2`.
  - Hosts six tab fragments via `ChannelDetailPagerAdapter`.
- **Tabs (fragments)**:
  - `ChannelVideosTabFragment`
  - `ChannelLiveTabFragment`
  - `ChannelShortsTabFragment`
  - `ChannelPlaylistsTabFragment`
  - `ChannelPostsTabFragment`
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

- `ChannelPost`
  - `id`, `text`, `timestamp`, `imageUrl: String?`, `likeCount: Long?`, `commentCount: Long?`.

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
    suspend fun getPosts(channelId: String, cursor: String?): ChannelPage<ChannelPost>

    suspend fun getAbout(channelId: String, forceRefresh: Boolean = false): ChannelHeader
}
```

Implementation details:

- Backed by `NewPipeExtractorClient`.
- Uses NewPipe’s `ChannelInfo` + related list API to fetch:
  - Full channel metadata (header + about).
  - Tab‑specific lists (videos, shorts, live, playlists, posts).
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
val postsState: StateFlow<PaginatedState<ChannelPost>>
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

**Structure (inside `contentContainer`):**

- `MaterialToolbar` (existing) with:
  - Back navigation icon.
  - Title = `channelName` argument until header loads, then real name.

- Header container (new):
  - `FrameLayout` for banner:
    - `ImageView` `@id/channelBanner`
      - `layout_width="match_parent"`
      - `layout_height="0dp"` + `app:layout_constraintDimensionRatio="16:9"` or fixed height dimen.
      - `scaleType="centerCrop"`
      - Placeholder background (from `thumbnail_placeholder` or new banner placeholder).
    - Optional gradient overlay `View` for legibility.

  - `ConstraintLayout` overlapping banner bottom:
    - `ImageView` `@id/channelAvatar`
      - Circular avatar using existing avatar dimens and rounded background.
      - Constrained to `start|bottom` in LTR and `end|bottom` in RTL (use `layout_constraintStart_toStartOf="parent"` etc.).
    - Text + button column constrained to `end` of avatar:
      - `TextView` `@id/channelNameText`
        - `textSize="@dimen/text_headline"`, `textStyle="bold"`, `textAlignment="viewStart"`.
      - `TextView` `@id/subscriberCountText`
        - Green text (`@color/primary_green`), caption size.
      - `TextView` `@id/channelSummaryText`
        - 2–3 lines max, ellipsized, `textAlignment="viewStart"`.
      - `MaterialButton` `@id/channelPrimaryButton`
        - Text “General” (string resource).
        - Style: primary filled, `backgroundTint="@color/primary_green"`.

- Header skeleton:
  - A dedicated `LinearLayout` / `ConstraintLayout` `@id/headerSkeleton` stacked over the real header.
  - Contains:
    - Banner stub rectangle with `@drawable/skeleton_shimmer`.
    - Circle stub for avatar.
    - 2–3 full‑width shimmer lines (for name and summary).
  - Visible while `HeaderState.Loading`.

- Tabs:
  - `TabLayout` `@id/tabLayout`
    - `app:tabMode="scrollable"`.
    - `app:tabSelectedTextColor="@color/primary_green"`.
    - Six tabs with titles from string resources:
      - Videos, Live, Shorts, Playlists, Posts, About.
  - `ViewPager2` `@id/viewPager`
    - `layout_height="0dp"` + `layout_weight="1"` to fill remaining space.

### 2.2 Channel Header (Tablet – `layout-sw600dp/fragment_channel_detail.xml`)

Create a separate layout file for tablets with **identical IDs**:

- Keep the banner full width (16:9) at top.
- Below banner, use a wider `ConstraintLayout`:
  - Avatar pinned to the start (or end in RTL).
  - Text block and primary button arranged horizontally:
    - Name + subscribers stacked.
    - Summary text + “General” button aligned in a second column.
- Increase paddings using `@dimen/spacing_xl`.
- Ensure `textAlignment="viewStart"` for all text views and use `start` / `end` constraints only (no `left`/`right`), to fully support RTL.

### 2.3 Tab Container Layouts

#### Base list tab layout (Videos, Live, Playlists, Posts)

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

This layout is shared by `ChannelVideosTabFragment`, `ChannelLiveTabFragment`, `ChannelPlaylistsTabFragment`, `ChannelPostsTabFragment`.

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

### 2.4 Item Layout Reuse & New Items

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
    - Views label at bottom (e.g., “13K views”).
  - Skeleton: card with black rectangle shimmer and short text shimmer overlays.

- **Posts tab**:
  - New layout `item_channel_post.xml` inspired by YouTube Community posts:
    - Header row:
      - Avatar (small circle), channel name, `timeAgo`.
    - Body:
      - Multi-line text, `maxLines` collapsed with “...Read more” styling.
    - Media (optional):
      - Large image (16:9) beneath text.
    - Footer:
      - Row of icons (like, dislike, comment, share) with counts.
  - Skeleton: simple vertical placeholders (avatar circle + 2–3 lines + full‑width image stub).

### 2.5 RTL & Localization

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

Per list tab (Videos, Live, Shorts, Playlists, Posts):

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
  - Mirror tests for Live, Shorts, Playlists, Posts using dedicated fake repository responses.

### 5.2 UI Tests (Espresso / Robolectric)

**Phone:**

- Launch `ChannelDetailFragment` with a fake ViewModel:
  - Header:
    - When `HeaderState.Loading`, verify skeleton views are visible and content is hidden.
    - When `Success`, check banner, avatar, title, subscribers, summary show correctly.
    - When `Error`, verify error text is visible and skeleton is hidden.
  - Tabs:
    - Verify tab titles order: Videos, Live, Shorts, Playlists, Posts, About.
    - Videos tab:
      - Shows `item_video_list` rows.
      - Rendering of title, meta, thumbnail.
      - Empty and error states show corresponding layouts.
    - Live tab:
      - Verify “LIVE” badge visible for `isLiveNow` items.
    - Shorts tab:
      - RecyclerView uses grid with 2 columns.
      - Items use `item_channel_short` layout.
    - Posts tab:
      - Post layout matches header + text + image design.
      - Long text truncated with “Read more”.
    - About tab:
      - Description, links, and “More info” rows rendered correctly.

**Tablet (`sw600dp`):**

- Run tests under tablet configuration:
  - Header:
    - Avatar and text layout horizontally as per tablet design.
    - Tab layout spans full width.
  - Shorts grid:
    - Column count > 2 (e.g., 4–5).

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
    - `getPlaylists`, `getShorts`, `getLiveStreams`, `getPosts` either return items or cleanly return empty lists when not supported.
  - Simulate network/Extractor errors:
    - Ensure exceptions are mapped to `ErrorInitial` or `ErrorAppend` in the ViewModel.

---

## 6. Risks & Edge Cases

### 6.1 Missing Content

- Channels with:
  - No videos → Videos tab shows empty state.
  - No playlists → Playlists tab empty state.
  - No shorts/posts → Respective tabs show empty states instead of errors.
  - No description → About tab hides the Description section and shows a short “No description available” message.

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

1. **Data layer**
   - Add `ChannelDetailRepository` and implementation backed by `NewPipeExtractorClient`.
   - Implement channel feed extraction (videos, live, shorts, playlists, posts, about).
2. **ViewModel**
   - Refactor `ChannelDetailViewModel` to use repository and new state models.
   - Add pagination controllers and rate limiting.
3. **Fragments**
   - Replace generic `ChannelDetailTabFragment` with six concrete tab fragments.
   - Wire each to the shared ViewModel and base tab layouts.
4. **Layouts**
   - Redesign `fragment_channel_detail.xml` + add `layout-sw600dp` variant.
   - Replace `fragment_channel_detail_tab.xml` with reusable list tab layout.
   - Add `fragment_channel_shorts_tab.xml` and `fragment_channel_about_tab.xml`.
   - Add skeleton header + tab skeletons reusing `skeleton_content_item` and `home_section_skeleton` patterns.
5. **Adapters & UI**
   - Reuse `item_video_list.xml` and `item_playlist.xml` with suitable adapters.
   - Implement new adapters for Live, Shorts, Posts, and About.
6. **Navigation & State**
   - Confirm all navigation paths into `ChannelDetailFragment` pass the required args.
   - Persist selected tab + scroll positions across rotations.
7. **Testing**
   - Add ViewModel unit tests for header + tab states, pagination, and rate limiting.
   - Add UI tests for phone + tablet + RTL, covering skeletons, empty, and error states.
   - Add integration tests (or strong fakes) for the NewPipe repository.

This document should be treated as the authoritative implementation plan for the NewPipe‑backed Channel Detail screen. Any deviations during implementation should be reflected by updating this file.

