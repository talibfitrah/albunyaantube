# Home Screen & Onboarding Improvements - Execution Plan

## ✅ COMPLETION STATUS (Phase A, B & All Fixes Complete)

**Date Completed**: November 27, 2025

### Phases Completed
- ✅ **Phase A: Card Standardization + Responsive Layout** - 100% Complete
- ✅ **Phase B: Data Limits + Loading States** - 100% Complete
- ✅ **Bug Fixes & Improvements** - 100% Complete (Nov 27, 2025)
- ⏸️ **Phase C: Featured Section** - Not Started (requires backend category)
- ⏸️ **Phase D: Onboarding Alignment** - Not Started (secondary priority)

### What Was Implemented

**Phase A & B (Nov 26):**
1. ✅ Base card styles (`Widget.Albunyaan.MediaCard` and variants)
2. ✅ Shared image loading utility ([ImageLoading.kt](../android/app/src/main/java/com/albunyaan/tube/util/ImageLoading.kt))
3. ✅ Device-aware data limits ([DeviceConfig.kt](../android/app/src/main/java/com/albunyaan/tube/util/DeviceConfig.kt))
4. ✅ Dynamic card width calculation based on screen size
5. ✅ TV focus states for all cards ([card_focus_state.xml](../android/app/src/main/res/drawable/card_focus_state.xml))
6. ✅ Skeleton loading states ([item_home_skeleton.xml](../android/app/src/main/res/layout/item_home_skeleton.xml))
7. ✅ Empty section handling (hides entire section including header)
8. ✅ Accessibility content descriptions for all adapters
9. ✅ Responsive dimensions for phone/tablet/TV (sw600dp, sw720dp)
10. ✅ HomeViewModel uses DeviceConfig for dynamic data limits (10 non-TV, 20 TV)

**Bug Fixes & Improvements (Nov 27 - Round 1):**
11. ✅ **Per-section loading states** - Each section (channels/playlists/videos) loads independently with separate StateFlows
12. ✅ **Data ordering** - Videos sorted by recency (uploadedDaysAgo) before limiting
13. ✅ **Optimized data fetching** - Now requests only the needed pageSize (10 or 20) instead of always 20
14. ✅ **Enhanced image loading** - ImageLoading.kt now handles null/blank URLs by showing placeholder immediately
15. ✅ **Localized accessibility** - All adapters now use string resources (a11y_video_item, a11y_channel_item, a11y_playlist_item) for proper localization

**Bug Fixes & Improvements (Nov 27 - Round 2):**
16. ✅ **YouTube-style error handling** - Failed sections show empty list with "See All" still visible, matching YouTube UX
17. ✅ **Fixed layout params warnings** - All adapters now properly reassign layoutParams after modifying width
18. ✅ **Skeleton accessibility** - Skeleton loaders marked with importantForAccessibility="no" to avoid TalkBack spam
19. ✅ **Backend dependency documented** - Added comment noting channels/playlists rely on backend order (no timestamp fields)

**Bug Fixes & Improvements (Nov 27 - Round 3):**
20. ✅ **Per-type API fetching** - Each section now fetches its own content type independently (CHANNELS, PLAYLISTS, VIDEOS) instead of sharing from a single mixed pool. Ensures each section gets full 10/20 items.
21. ✅ **Inline error UI with retry** - Created [home_section_error.xml](../android/app/src/main/res/layout/home_section_error.xml) with Material Design error card, message, and retry button
22. ✅ **Per-section retry buttons** - Each section can be retried independently via dedicated `loadChannels()`, `loadPlaylists()`, `loadVideos()` methods
23. ✅ **Independent error handling** - Failure in one section no longer blanks the entire home screen; sections are isolated
24. ✅ **Defensive take(limit)** - All sections now explicitly call `take(limit)` after filtering/sorting to guard against backend returning more than pageSize
25. ✅ **Dark mode error colors** - Added theme-aware error colors in values-night/colors.xml (home_error_bg, home_error_text, home_error_icon)
26. ✅ **Error UI polish** - Error card uses MaterialCardView with rounded corners matching other cards
27. ✅ **New icons created** - [ic_error.xml](../android/app/src/main/res/drawable/ic_error.xml), [ic_refresh.xml](../android/app/src/main/res/drawable/ic_refresh.xml)

**Testing (Nov 27 - Round 4):**
28. ✅ **DeviceConfigTest.kt** - Unit tests for TV detection and data limits using Robolectric (6 tests)
29. ✅ **HomeViewModelTest.kt** - Comprehensive tests for per-section loading, error isolation, retry functionality, video sorting by recency, and defensive take(limit) behavior using FakeContentService (7 tests)
30. ✅ **ImageLoadingTest.kt** - Unit tests calling actual ImageLoading helper functions (getPlaceholderForItem, getUrlForItem, isUrlValid, shouldApplyCircleCrop) to catch regressions (13 tests)
31. ✅ **ImageLoading.kt refactored** - Exposed helper functions as public methods for unit testing while maintaining the loadThumbnail extension function

**Duration API Migration (Nov 27 - Round 5):**
32. ✅ **API field rename**: Backend `durationMinutes` → `durationSeconds` for accurate duration display
33. ✅ **Backend ContentItemDto.java** - Field renamed to `durationSeconds`, now returns actual seconds
34. ✅ **Backend PublicContentService.java** - No longer divides by 60
35. ✅ **OpenAPI spec updated** - `api-specification.yaml` updated with `durationSeconds`
36. ✅ **Android DTOs regenerated** - `ContentItemDto.kt` and mappers updated
37. ✅ **FakeContentService.kt** - Length filter thresholds updated (4min=240s, 20min=1200s)
38. ✅ **SearchResultsAdapter.kt** - Duration formatting changed from `"${seconds/60} min"` to proper `formatDuration()` (mm:ss or HH:mm:ss)
39. ✅ **VideoGridAdapter.kt** - Duration formatting changed from `"${seconds}:00"` to proper `formatDuration()`
40. ✅ **HomeVideoAdapter.kt** - Already had proper formatDuration() function
41. ✅ **Card content height** - Increased from 88dp to 100dp for 2-line title + 2-line metadata
42. ✅ **Documentation updated** - `docs/architecture/overview.md` and `docs/design/design-system.md` updated

### Build Status
- ✅ Compilation successful (tested with `./gradlew compileDebugKotlin` on Nov 27)
- ✅ All Kotlin code compiles without errors
- ✅ All unit tests pass (tested with `./gradlew testDebugUnitTest` on Nov 27)
- ⚠️ Player warnings (unrelated to this work)

### Remaining Work (Future Phases)
**Phase C (Featured Section) - Requires Backend Setup:**
1. Seed "Featured" category in backend with known ID
2. Create `HomeFeaturedAdapter.kt` for mixed content types
3. Create `item_home_featured.xml` with type badge
4. Add Featured section to HomeFragment above Channels
5. Create `FeaturedListFragment.kt` for "See All" with filter chips

**Phase D (Onboarding Alignment) - Lower Priority:**
1. Add responsive onboarding dimensions (sw600dp, sw720dp)
2. Update `page_onboarding_item.xml` with design system tokens
3. Add TV focus states to onboarding buttons
4. Test on phone/tablet/TV emulators

**Other Future Enhancements:**
- **Manual testing**: Verification on phone/tablet/TV emulators with TalkBack
- **Placeholder assets**: Design proper branded placeholders for video/playlist/channel (currently using solid color backgrounds)
- **Backend enhancement**: Add timestamp fields to Channel/Playlist models for client-side sorting

### Known Limitations & Design Decisions
- **Error handling**: Per-section inline error panels with retry buttons. Users see clear error message and can retry each section independently.
- **Sorting**: Channels and playlists use backend order (API doesn't provide timestamp fields); only videos are sorted by uploadedDaysAgo.
- **Placeholders**: Using simple solid color placeholders (home_thumbnail_bg, home_channel_avatar_bg) until branded assets are designed.
- **Defensive limits**: All sections apply `take(limit)` after filtering/sorting to guard against backend returning more than requested.
- **Unit tests added**: DeviceConfigTest, HomeViewModelTest, and ImageLoadingTest cover the core logic.

---

## Decisions Made
- **Featured Section**: Use existing category system (filter by "Featured" category)
- **Onboarding**: Keep illustrative, apply consistent spacing/typography only
- **TV Support**: Basic responsive (dimension qualifiers + focus states)
- **Section Order**: Featured → Channels → Playlists → Videos
- **Phasing**: A (cards) → B (data/loading) → C (featured) → D (onboarding)

---

## Current State Summary

### Home Screen Architecture
- **Files**: `HomeFragment.kt`, `HomeViewModel.kt`, `fragment_home_new.xml`
- **Sections**: Channels, Playlists, Videos (10 items on phone/tablet, 20 items on TV)
- **Cards**: Three separate layouts (`item_home_channel.xml`, `item_home_playlist.xml`, `item_home_video.xml`)
- **Data Flow**: ContentService → ViewModel (per-type fetching with StateFlow) → Fragment → Adapters
- **Loading**: Per-section independent loading states with skeleton loaders and inline error UI
- **Image Loading**: Coil with shared `ImageLoading.kt` utility and content-type specific placeholders
- **Responsive**: Dimension qualifiers for sw600dp (tablet) and sw720dp (large/TV)
- **Device Detection**: `DeviceConfig.kt` determines TV vs non-TV for data limits (20 vs 10 items)

### Onboarding Architecture
- **Files**: `OnboardingFragment.kt`, `OnboardingPagerAdapter.kt`, `fragment_onboarding.xml`
- **Content**: 3 illustrative pages (icons + text only, NO live content)
- **Navigation**: ViewPager2 carousel with dot indicators

### Design Tokens (Existing)
- Primary: `#275E4B` (green), Accent: `#35C491`
- Corner radius: 16dp (cards), 12dp (thumbnails)
- Elevation: 2dp (cards)
- Spacing: 8dp grid (xs=4, sm=8, md=16, lg=24, xl=32)

---

## Implementation Plan

### Phase 1: Standardize Card Components

#### 1.1 Create Base Media Card Style
**Files to create/modify:**
- `android/app/src/main/res/values/styles.xml` - Add base card styles

```
Widget.Albunyaan.MediaCard (base style)
├── cornerRadius: @dimen/home_card_corner_radius (16dp)
├── elevation: @dimen/home_card_elevation (2dp)
├── background: @color/home_card_background
└── contentPadding: 0dp (thumbnail edge-to-edge)

Widget.Albunyaan.MediaCard.Title
├── textAppearance: @style/TextAppearance.Home.ItemTitle
├── maxLines: 2
├── ellipsize: end
└── minHeight: wrap_content

Widget.Albunyaan.MediaCard.Metadata
├── textAppearance: @style/TextAppearance.Home.ItemMeta
├── maxLines: 1
└── ellipsize: end
```

#### 1.2 Unify Card Layouts
**Files to modify:**
- `item_home_video.xml` - Apply base styles
- `item_home_playlist.xml` - Apply base styles
- `item_home_channel.xml` - Wrap in MaterialCardView for consistency

**Structural standardization:**
```
MaterialCardView (Widget.Albunyaan.MediaCard)
├── Thumbnail (16:9 or circular for channels)
│   └── Badge overlay (duration/count) - bottom corner
├── Content area (LinearLayout, vertical)
│   ├── Title (Widget.Albunyaan.MediaCard.Title)
│   └── Metadata (Widget.Albunyaan.MediaCard.Metadata)
└── Optional: Type badge for Featured section
```

#### 1.3 Create Shared Image Loading Utility
**File to create:**
- `android/app/src/main/java/com/albunyaan/tube/util/ImageLoading.kt`

```kotlin
object ImageLoading {
    fun ImageView.loadThumbnail(
        url: String?,
        contentType: ContentType,
        crossfade: Boolean = true
    ) {
        val placeholder = when (contentType) {
            ContentType.VIDEO, ContentType.PLAYLIST -> R.drawable.home_thumbnail_bg
            ContentType.CHANNEL -> R.drawable.home_channel_avatar_bg
        }
        load(url) {
            placeholder(placeholder)
            error(placeholder)
            if (crossfade) crossfade(true)
        }
    }
}
```

**Files to modify:**
- `HomeVideoAdapter.kt` - Use shared utility
- `HomePlaylistAdapter.kt` - Use shared utility
- `HomeChannelAdapter.kt` - Use shared utility

---

### Phase 2: Responsive Layout System

#### 2.1 Define Breakpoints & Cards-Per-Row
**Files to modify:**
- `values/dimens.xml` (phone baseline)
- `values-sw600dp/dimens.xml` (tablet)
- `values-sw720dp/dimens.xml` (large tablet/TV)

| Dimension | Phone | Tablet | TV |
|-----------|-------|--------|-----|
| `home_cards_visible_channels` | 3 | 4 | 6 |
| `home_cards_visible_playlists` | 2 | 3 | 5 |
| `home_cards_visible_videos` | 2 | 3 | 5 |

**Data limit (code-based, not dimen-based):** TV = 20, non-TV (phone/tablet) = 10

#### 2.2 Update Card Widths for Visibility
Calculate card widths to show desired number + partial peek:
- Formula: `(screenWidth - 2*margin - (n-1)*spacing) / n * 0.95`
- Calculate in `HomeFragment.kt` and set `layoutParams.width` on each ViewHolder root

**Files to modify:**
- `HomeFragment.kt` - Calculate card width based on screen width and apply to adapters
- `fragment_home_new.xml` - Remove fixed `layout_width` from card references (will be set programmatically)

#### 2.3 TV Focus States
**Files to create:**
- `drawable/card_focus_state.xml` - Focus ring/scale selector

**Files to modify:**
- `item_home_video.xml` - Add `android:focusable="true"`, focus drawable
- `item_home_playlist.xml` - Add focus handling
- `item_home_channel.xml` - Add focus handling
- `HomeFragment.kt` - Ensure D-pad navigation works between sections

---

### Phase 3: Data Layer Updates

#### 3.1 Device-Aware Data Limits
**File to create:**
- `android/app/src/main/java/com/albunyaan/tube/util/DeviceConfig.kt`

```kotlin
object DeviceConfig {
    /** TV gets 20 items, all other devices (phone/tablet) get 10 */
    fun getHomeDataLimit(context: Context): Int =
        if (isTV(context)) 20 else 10

    fun isTV(context: Context): Boolean {
        val uiModeManager = context.getSystemService<UiModeManager>()
        return uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    }
}
```

**Note:** Data limit is TV-only logic (20) vs non-TV (10), NOT dimension-based. This ensures large tablets still get 10 items. The `home_cards_visible_*` dimens remain per sw600/sw720 for layout/card sizing only.

#### 3.2 Update ViewModel Data Fetching
**File to modify:**
- `HomeViewModel.kt`

```kotlin
// Change from:
val channels = response.data.filterIsInstance<ContentItem.Channel>().take(3)

// To:
val limit = DeviceConfig.getHomeDataLimit(application)
val channels = response.data.filterIsInstance<ContentItem.Channel>().take(limit)
```

#### 3.3 Section Loading States
**File to modify:**
- `HomeViewModel.kt` - Per-section loading states
- `HomeFragment.kt` - Show skeleton loaders while loading

**Files to create:**
- `item_home_skeleton.xml` - Shimmer placeholder card

---

### Phase 4: Featured Section (Category-Based)

#### 4.1 Approach: Use Existing Category System
- Filter content by a "Featured" category (admin assigns via existing approval workflow)
- No backend model changes needed - leverages existing `categoryIds` field
- Mixed content types (channels, playlists, videos) in one section

#### 4.2 Backend: Ensure "Featured" Category Exists
**Files to verify/seed:**
- Category seeder should include a "Featured" category with known ID
- Verify `/api/v1/content?category=featured` returns mixed content types

#### 4.3 Android UI Implementation
**Files to create:**
- `HomeFeaturedAdapter.kt` - Adapter for mixed content types
- `item_home_featured.xml` - Card with type badge (reuses base card structure)

**Files to modify:**
- `HomeViewModel.kt` - Add `featuredContent: StateFlow<List<ContentItem>>`

```kotlin
// Featured section uses same 10/20 rule, sorted by priority then date
val limit = DeviceConfig.getHomeDataLimit(app)
val featured = allItems
    .filter { it.categoryIds.contains(FEATURED_CATEGORY_ID) }
    .sortedWith(compareByDescending<ContentItem> { it.updatedAt ?: it.createdAt })
    .take(limit)
```

- `HomeFragment.kt` - Add Featured RecyclerView at top of sections
- `fragment_home_new.xml` - Add Featured section above Channels

**Featured card structure:**
```
MaterialCardView (same as video/playlist cards)
├── Thumbnail (16:9)
│   └── Type badge (bottom-left): "Video" / "Playlist" / "Channel"
├── Title (2 lines, ellipsized)
└── Metadata (context-dependent)
```

#### 4.4 See All for Featured
**Files to create:**
- `FeaturedListFragment.kt` - Full list with filter chips (All/Videos/Playlists/Channels)

**Files to modify:**
- `app_nav_graph.xml` - Add FeaturedListFragment destination

**Important:** "See all" uses standard paging (no hard 10/20 limit), same sort order (updatedAt desc). Do NOT reuse the home data limit here.

---

### Phase 5: Thumbnail, Placeholder & Accessibility Improvements

#### 5.1 Content-Type Specific Placeholders
**Files to verify/create:**
- `drawable/placeholder_video.xml` - Video frame with play icon
- `drawable/placeholder_playlist.xml` - Stacked cards icon
- `drawable/placeholder_channel.xml` - Avatar silhouette (existing: `home_channel_avatar_bg`)

**Placeholder behavior:** Show placeholder both when URL is null/empty AND when loading fails (Coil's `error(placeholder)` handles both cases).

#### 5.2 Offline/Error Handling
**File to modify:**
- `ImageLoading.kt` - Handle null URLs and load failures

```kotlin
fun ImageView.loadThumbnail(url: String?, contentType: ContentType, crossfade: Boolean = true) {
    val placeholder = when (contentType) {
        ContentType.VIDEO, ContentType.PLAYLIST -> R.drawable.home_thumbnail_bg
        ContentType.CHANNEL -> R.drawable.home_channel_avatar_bg
    }
    if (url.isNullOrBlank()) {
        setImageResource(placeholder)
        return
    }
    load(url) {
        placeholder(placeholder)
        error(placeholder)  // Shows on load failure
        if (crossfade) crossfade(true)
    }
}
```

#### 5.3 Accessibility - Content Descriptions
**Files to modify:**
- `HomeVideoAdapter.kt`, `HomePlaylistAdapter.kt`, `HomeChannelAdapter.kt`, `HomeFeaturedAdapter.kt`

Set meaningful content descriptions on cards for TalkBack/screen readers:
```kotlin
// In ViewHolder.bind()
itemView.contentDescription = buildString {
    append(item.title)
    append(", ")
    append(when (item) {
        is Video -> "Video, ${item.viewCount} views"
        is Playlist -> "Playlist, ${item.itemCount} videos"
        is Channel -> "Channel, ${item.subscriberCount} subscribers"
    })
}
```

#### 5.4 Empty Section Handling
**Files to modify:**
- `HomeFragment.kt` - Hide sections with no content

```kotlin
// For each section (including Featured)
if (items.isEmpty()) {
    sectionContainer.visibility = View.GONE  // Hide entire section including header
} else {
    sectionContainer.visibility = View.VISIBLE
    adapter.submitList(items)
}
```

**Rule:** When a section has 0 items, hide it entirely (no empty header, no blank space). Do NOT show "No items yet" message on Home—just hide the section.

---

### Phase 6: Onboarding Alignment (Styling Only)

#### 6.1 Apply Design System Consistency
Keep onboarding illustrative (no content cards). Apply consistent spacing/typography.

**Files to modify:**
- `page_onboarding_item.xml` - Use design system dimensions
- `fragment_onboarding.xml` - Ensure consistent button styling

#### 6.2 Responsive Onboarding Dimensions
**Files to modify:**
- `values/dimens.xml` - Add baseline onboarding dimensions
  ```xml
  <dimen name="onboarding_icon_container_size">160dp</dimen>
  <dimen name="onboarding_icon_size">80dp</dimen>
  <dimen name="onboarding_title_size">28sp</dimen>
  <dimen name="onboarding_description_size">16sp</dimen>
  ```
- `values-sw600dp/dimens.xml` - Tablet sizes (icon: 200dp/100dp, title: 32sp)
- `values-sw720dp/dimens.xml` - TV sizes (icon: 240dp/120dp, title: 36sp)

#### 6.3 TV Focus States
**Files to modify:**
- `fragment_onboarding.xml` - Add `android:focusable="true"` to buttons
- Ensure Skip/Next/Get Started buttons have visible focus indicators

---

### Phase 7: Testing & Verification

#### 7.1 Unit Tests
**Files to create:**
- `DeviceConfigTest.kt` - Test data limit logic
- `ImageLoadingTest.kt` - Test placeholder selection

#### 7.2 UI Tests
**Files to create:**
- `HomeFragmentTest.kt` - Verify section rendering, card counts
- `OnboardingFragmentTest.kt` - Verify navigation flow

#### 7.3 Manual Testing Checklist

**Home Screen:**
- [ ] Phone portrait: 2-3 cards visible per section
- [ ] Phone landscape: Cards adjust appropriately
- [ ] Tablet: 3-4 cards visible per section
- [ ] TV: 5-6 cards visible, D-pad navigation works
- [ ] Titles truncate at 2 lines with ellipsis
- [ ] Placeholders show when URL is null AND on load failure
- [ ] Empty sections are hidden (no blank headers)
- [ ] RTL layout mirrors correctly (Arabic)
- [ ] Dark mode colors applied correctly

**Onboarding:**
- [ ] Responsive sizing on phone/tablet/TV
- [ ] RTL layout mirrors correctly (Arabic)
- [ ] Large text accessibility setting doesn't break layout
- [ ] TV focus states visible on buttons

**Accessibility:**
- [ ] TalkBack reads card content descriptions on phone
- [ ] TalkBack/screen reader works on TV
- [ ] Focus order is logical (section title → See all → cards L→R)

---

## Critical Files Summary

### Must Modify
| File | Changes |
|------|---------|
| `HomeViewModel.kt` | Device-aware limits, featured state, per-section loading |
| `HomeFragment.kt` | Dynamic card widths, skeleton loaders, featured section |
| `fragment_home_new.xml` | Add featured section, remove fixed widths |
| `item_home_channel.xml` | Wrap in MaterialCardView, add focus state |
| `item_home_video.xml` | Apply base styles, add focus state |
| `item_home_playlist.xml` | Apply base styles, add focus state |
| `styles.xml` | Add base card styles |
| `dimens.xml` (all variants) | Add responsive card counts and onboarding dimensions |

### Must Create
| File | Purpose |
|------|---------|
| `ImageLoading.kt` | Shared image loading utility |
| `DeviceConfig.kt` | Device type detection (TV vs non-TV), data limits |
| `HomeFeaturedAdapter.kt` | Adapter for mixed content types in Featured section |
| `item_home_skeleton.xml` | Loading placeholder |
| `item_home_featured.xml` | Featured card with type badge |
| `card_focus_state.xml` | TV focus indicator |
| `FeaturedListFragment.kt` | "See All" screen for Featured with filter chips |

---

## Execution Order Summary

### Phase A: Card Standardization + Responsive Layout (Foundation)
1. Create base card styles in `styles.xml`
2. Create `ImageLoading.kt` utility
3. Update all three card layouts to use base styles
4. Add accessibility content descriptions to adapters
5. Add TV focus states to cards
6. Update dimension files for responsive card widths
7. Test on phone/tablet/TV emulators (including TalkBack)

### Phase B: Data Limits + Loading States (Polish)
1. Create `DeviceConfig.kt` utility (TV-only 20 vs non-TV 10)
2. Update `HomeViewModel.kt` for dynamic limits
3. Create skeleton loader layout
4. Add per-section loading states
5. Add empty section handling (hide when 0 items)
6. Test data fetching with limits on phone and TV

### Phase C: Featured Section (New Feature)
1. Verify/create "Featured" category in backend seeder
2. Create `HomeFeaturedAdapter.kt`
3. Create `item_home_featured.xml` with type badge
4. Add Featured section to `HomeFragment`
5. Create `FeaturedListFragment.kt` for See All
6. Test Featured section rendering

### Phase D: Onboarding Alignment (Secondary)
1. Add onboarding dimensions to all dimens files
2. Update `page_onboarding_item.xml` with responsive dimensions
3. Add focus states to onboarding buttons
4. Test on phone/tablet/TV

### Final Verification
- Manual testing across all device types
- RTL layout verification (Arabic)
- Dark mode verification
- Performance profiling (60fps scroll target)
