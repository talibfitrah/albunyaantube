# Android UI Implementation Guide

> **Last Updated**: 2025-10-04
> **Status**: ✅ Phase 4 UI Complete
> **Authority**: This document reflects the **current implemented UI code** as the source of truth

---

## Overview

This guide documents the **actual implemented Android UI** for Albunyaan Tube. All design decisions, layouts, and components described here match the current codebase. The implemented UI supersedes any previous mockups or specifications.

---

## Design System

### Color Palette

**Primary Colors**:
- `primary_green`: `#35C491` - Main brand color (teal/green)
- `background_gray`: `#F5F5F5` - Light gray for backgrounds
- `icon_gray`: `#9E9E9E` - Gray for secondary text and icons
- `surface_variant`: Light gray for chips and secondary surfaces

**System Colors**:
- White (`#FFFFFF`) - Card backgrounds, toolbar backgrounds
- Black (`#000000`) - Primary text

### Spacing System (8dp Grid)

```xml
<dimen name="spacing_xs">4dp</dimen>
<dimen name="spacing_sm">8dp</dimen>
<dimen name="spacing_md">16dp</dimen>
<dimen name="spacing_lg">24dp</dimen>
<dimen name="spacing_xl">32dp</dimen>
```

### Corner Radius

```xml
<dimen name="corner_radius_small">8dp</dimen>
<dimen name="corner_radius_medium">16dp</dimen>
```

### Typography

- **Headline**: 18sp, bold - Section titles
- **Title**: 16sp, bold - Item titles, card headers
- **Body**: 14-16sp, regular - Descriptions, counts
- **Caption**: 14sp, regular - Subtitles, metadata

### Touch Targets

- Minimum touch target: 48dp (Material Design standard)
- Button height: 48dp
- FAB size: 56dp

---

## Navigation Structure

### Bottom Navigation Bar

**Implementation**: [fragment_main_shell.xml](../../android/app/src/main/res/layout/fragment_main_shell.xml)

**Design**:
- White background with elevation (8dp)
- 4 tabs: Home, Channels, Playlists, Videos
- Icons change color on selection (not background)
- Selection color: `primary_green`
- Unselected: White with 60% opacity

**Tab Icons**:
1. **Home** (`ic_home.xml`): House icon
2. **Channels** (`ic_channels.xml`): Play button inside rectangle frame
3. **Playlists** (`ic_playlists.xml`): Horizontal lines (list view)
4. **Videos** (`ic_videos.xml`): 2x2 grid

**State Selector**: [bottom_nav_item_color.xml](../../android/app/src/main/res/color/bottom_nav_item_color.xml)
```xml
<selector>
    <item android:color="@color/primary_green" android:state_checked="true"/>
    <item android:color="@android:color/white" android:alpha="0.6"/>
</selector>
```

---

## Screen Implementations

### 1. Home Screen

**Fragment**: [HomeFragmentNew.kt](../../android/app/src/main/java/com/albunyaan/tube/ui/home/HomeFragmentNew.kt)
**Layout**: [fragment_home_new.xml](../../android/app/src/main/res/layout/fragment_home_new.xml)

**Structure**:
```
├── Header (White card)
│   ├── App logo + "Albunyaan" title
│   ├── Category chip (clickable filter)
│   └── Search button + Kebab menu (3 dots rotated 90°)
├── Channels Section
│   ├── "Channels" title + "See all" link
│   └── Horizontal RecyclerView
├── Playlists Section
│   ├── "Playlists" title + "See all" link
│   └── Horizontal RecyclerView
└── Videos Section
    ├── "Videos" title + "See all" link
    └── Horizontal RecyclerView
```

**Kebab Menu** ([home_menu.xml](../../android/app/src/main/res/menu/home_menu.xml)):
- Settings
- Downloads

**Key Features**:
- Pull-to-refresh
- Horizontal scrolling for each content type
- Category filter integration
- Navigation to Settings and Downloads screens

---

### 2. Channels Screen

**Fragment**: [ChannelsFragmentNew.kt](../../android/app/src/main/java/com/albunyaan/tube/ui/ChannelsFragmentNew.kt)
**Layout**: [fragment_channels_new.xml](../../android/app/src/main/res/layout/fragment_channels_new.xml)

**Structure**:
```
├── Vertical RecyclerView (channels list)
└── FloatingActionButton (Categories access)
    └── Icon: Sort/filter icon
    └── Background: primary_green
```

**Channel Item** ([item_channel.xml](../../android/app/src/main/res/layout/item_channel.xml)):
```
├── Circular Avatar (48dp)
├── Channel Name (16sp, bold, max 2 lines)
├── Subscriber Count (14sp, green)
└── Category Chip (below subscriber count)
    └── Format: "Dawah +9" (first category + count)
```

**Adapter**: [ChannelAdapter.kt](../../android/app/src/main/java/com/albunyaan/tube/ui/adapters/ChannelAdapter.kt)

**Key Features**:
- Channel names wrap to 2 lines when needed (no gap if 1 line)
- Categories displayed below subscriber count
- Single category shown with "+N" indicator for multiple categories
- Circular avatar with placeholder
- Click navigation to Channel Detail

---

### 3. Playlists Screen

**Fragment**: [PlaylistsFragmentNew.kt](../../android/app/src/main/java/com/albunyaan/tube/ui/PlaylistsFragmentNew.kt)
**Layout**: Similar to Channels (vertical RecyclerView)

**Playlist Item** ([item_playlist.xml](../../android/app/src/main/res/layout/item_playlist.xml)):
```
├── Square Thumbnail (80dp)
├── Playlist Title (16sp, bold)
├── Video Count (14sp, green)
└── Category Chip
```

**Adapter**: [PlaylistAdapter.kt](../../android/app/src/main/java/com/albunyaan/tube/ui/adapters/PlaylistAdapter.kt)

---

### 4. Videos Screen

**Fragment**: [VideosFragmentNew.kt](../../android/app/src/main/java/com/albunyaan/tube/ui/VideosFragmentNew.kt)
**Layout**: Grid layout

**Video Item** ([item_video_grid.xml](../../android/app/src/main/res/layout/item_video_grid.xml)):
```
├── 16:9 Thumbnail
├── Duration Badge (bottom-right corner)
├── Video Title (14sp, max 2 lines)
├── Channel Name (12sp, gray)
└── Views + Date (12sp, gray)
```

**Adapter**: [VideoGridAdapter.kt](../../android/app/src/main/java/com/albunyaan/tube/ui/adapters/VideoGridAdapter.kt)

**Layout**: GridLayoutManager with 2 columns

---

### 5. Channel Detail Screen

**Fragment**: [ChannelDetailFragment.kt](../../android/app/src/main/java/com/albunyaan/tube/ui/detail/ChannelDetailFragment.kt)
**Layout**: [fragment_channel_detail.xml](../../android/app/src/main/res/layout/fragment_channel_detail.xml)

**Structure**:
```
├── Toolbar (with back button)
├── Channel Header
│   ├── Banner image
│   ├── Circular avatar (overlap)
│   ├── Channel name
│   └── Subscriber count
├── TabLayout (Videos | Playlists)
└── ViewPager2 (content)
```

**Key Features**:
- Back navigation using `findNavController().navigateUp()`
- Tab switching between videos and playlists
- Deep link support: `albunyaantube://channel/{channelId}`

---

### 6. Categories Screen

**Fragment**: [CategoriesFragment.kt](../../android/app/src/main/java/com/albunyaan/tube/ui/categories/CategoriesFragment.kt)
**Layout**: [fragment_categories.xml](../../android/app/src/main/res/layout/fragment_categories.xml)

**Structure**:
```
├── Toolbar ("Categories")
└── White Card Container
    └── RecyclerView (categories list)
```

**Category Item** ([item_category.xml](../../android/app/src/main/res/layout/item_category.xml)):
```
├── Circular Icon Background (primary_green)
├── Category Name (16sp, black)
└── Chevron (right arrow, only if hasSubcategories)
```

**Mock Categories** (10 items):
1. Quran (has subcategories)
2. Hadith
3. Islamic History (has subcategories)
4. Dawah
5. Fiqh (has subcategories)
6. Tafsir
7. Arabic Language
8. Islamic Finance
9. Family & Relationships
10. Youth & Education

**Navigation**: Accessible via FAB on Channels screen

---

### 7. Subcategories Screen

**Fragment**: [SubcategoriesFragment.kt](../../android/app/src/main/java/com/albunyaan/tube/ui/categories/SubcategoriesFragment.kt)
**Layout**: [fragment_subcategories.xml](../../android/app/src/main/res/layout/fragment_subcategories.xml)

**Structure**: Same as Categories, with dynamic title from parent category

**Navigation Arguments**:
- `categoryId`: String
- `categoryName`: String

---

### 8. Settings Screen

**Fragment**: [SettingsFragment.kt](../../android/app/src/main/java/com/albunyaan/tube/ui/settings/SettingsFragment.kt)
**Layout**: [fragment_settings.xml](../../android/app/src/main/res/layout/fragment_settings.xml)

**Structure**:
```
├── Toolbar ("Settings")
└── ScrollView
    ├── General Section
    │   ├── Language (English)
    │   └── Theme (Light)
    ├── Playback Section
    │   ├── Audio-only (toggle)
    │   └── Background Play (toggle)
    ├── Downloads Section
    │   ├── Download Quality (High 720p)
    │   └── Wi-Fi Only Downloads (toggle)
    ├── Content Section
    │   └── Family-Friendly Safe Mode (toggle, default ON)
    └── About & Support Section
        └── Support Center (link with chevron)
```

**Settings Item Layouts**:
- `settings_item_language.xml` - With subtitle and chevron
- `settings_item_theme.xml` - With subtitle and chevron
- `settings_item_audio_only.xml` - With switch
- `settings_item_background_play.xml` - With switch
- `settings_item_download_quality.xml` - With subtitle and chevron
- `settings_item_wifi_only.xml` - With switch
- `settings_item_safe_mode.xml` - With switch (default checked)
- `settings_item_support.xml` - With chevron

**Design Pattern**:
- White MaterialCardView per section
- Circular icon backgrounds (teal)
- Dividers between items (gray, 1dp, marginStart=24dp)
- Consistent 16dp padding

**Navigation**: Accessible via Home menu → Settings

---

### 9. Downloads & Library Screen

**Fragment**: [DownloadsFragment.kt](../../android/app/src/main/java/com/albunyaan/tube/ui/download/DownloadsFragment.kt)
**Layout**: [fragment_downloads.xml](../../android/app/src/main/res/layout/fragment_downloads.xml)

**Structure**:
```
├── Toolbar ("Downloads & Library")
└── ScrollView
    ├── Downloads Section
    │   └── White Card
    │       ├── RecyclerView (downloads list)
    │       ├── Empty State (icon + text)
    │       └── Storage Info Card
    │           ├── "Storage Used" label
    │           ├── "0 MB of 500 MB" text
    │           └── ProgressBar (green)
    └── Library Section
        └── White Card
            ├── Saved (0 videos) →
            ├── Recently Watched (0 videos) →
            └── History (0 videos) →
```

**Empty State**:
- Download icon (64dp, 30% opacity)
- "No downloads yet" (16sp)
- "Downloaded videos will appear here" (14sp, gray)

**Library Item Layouts**:
- `library_item_saved.xml`
- `library_item_recently_watched.xml`
- `library_item_history.xml`

**Design Pattern**:
- Circular icon + title + count + chevron
- Clickable with toast messages (ready for navigation)

**Navigation**: Accessible via Home menu → Downloads

---

## Component Library

### 1. Material Cards

**Usage**: All section containers

```xml
<com.google.android.material.card.MaterialCardView
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    app:cardBackgroundColor="@android:color/white"
    app:cardCornerRadius="@dimen/corner_radius_medium"
    app:cardElevation="0dp">
    <!-- Content -->
</com.google.android.material.card.MaterialCardView>
```

### 2. Chips

**Usage**: Category tags, filters

```kotlin
val chip = Chip(context).apply {
    text = chipText
    isClickable = false
    chipBackgroundColor = ColorStateList.valueOf(
        context.getColor(R.color.surface_variant)
    )
    setTextColor(context.getColor(R.color.primary_green))
}
```

### 3. FloatingActionButton

**Usage**: Categories access on Channels screen

```xml
<com.google.android.material.floatingactionbutton.FloatingActionButton
    android:id="@+id/categoriesFab"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:layout_gravity="bottom|end"
    android:layout_margin="@dimen/spacing_lg"
    android:src="@android:drawable/ic_menu_sort_by_size"
    app:backgroundTint="@color/primary_green"
    app:tint="@android:color/white"/>
```

### 4. Circular Icon Backgrounds

**Drawable**: [onboarding_icon_bg.xml](../../android/app/src/main/res/drawable/onboarding_icon_bg.xml)

```xml
<shape android:shape="oval">
    <solid android:color="@color/primary_green"/>
    <size android:width="40dp" android:height="40dp"/>
</shape>
```

**Usage**: Category icons, settings icons, library icons

---

## Navigation Graph

**File**: [main_tabs_nav.xml](../../android/app/src/main/res/navigation/main_tabs_nav.xml)

**Destinations**:
1. `homeFragment` - HomeFragmentNew
2. `channelsFragment` - ChannelsFragmentNew
3. `playlistsFragment` - PlaylistsFragmentNew
4. `videosFragment` - VideosFragmentNew
5. `downloadsFragment` - DownloadsFragment
6. `channelDetailFragment` - ChannelDetailFragment (with arguments)
7. `playlistDetailFragment` - PlaylistDetailFragment (with arguments)
8. `categoriesFragment` - CategoriesFragment
9. `subcategoriesFragment` - SubcategoriesFragment (with arguments)
10. `settingsFragment` - SettingsFragment

**Navigation Actions**:
- Channels → Channel Detail
- Channels → Categories → Subcategories
- Playlists → Playlist Detail
- Home Menu → Settings
- Home Menu → Downloads

---

## Data Models

### ContentItem (Sealed Class)

**File**: [ContentItem.kt](../../android/app/src/main/java/com/albunyaan/tube/data/model/ContentItem.kt)

**Channel**:
```kotlin
data class Channel(
    val id: String,
    val name: String,
    val category: String,
    val subscribers: Int,
    val description: String? = null,
    val thumbnailUrl: String? = null,
    val videoCount: Int? = null,
    val categories: List<String>? = null // Multiple categories support
)
```

**Playlist**:
```kotlin
data class Playlist(
    val id: String,
    val title: String,
    val category: String,
    val videoCount: Int,
    val thumbnailUrl: String? = null
)
```

**Video**:
```kotlin
data class Video(
    val id: String,
    val title: String,
    val channelName: String,
    val views: Int,
    val uploadDate: String,
    val duration: String,
    val thumbnailUrl: String? = null
)
```

### Category

**File**: [CategoriesFragment.kt](../../android/app/src/main/java/com/albunyaan/tube/ui/categories/CategoriesFragment.kt)

```kotlin
data class Category(
    val id: String,
    val name: String,
    val hasSubcategories: Boolean = false
)
```

---

## Adapters

### ChannelAdapter

**File**: [ChannelAdapter.kt](../../android/app/src/main/java/com/albunyaan/tube/ui/adapters/ChannelAdapter.kt)

**Key Features**:
- Single category display with "+N" indicator
- Circular avatar with Coil loading
- Subscriber count formatting
- Click listener for navigation

### PlaylistAdapter

**File**: [PlaylistAdapter.kt](../../android/app/src/main/java/com/albunyaan/tube/ui/adapters/PlaylistAdapter.kt)

**Key Features**:
- Square thumbnail
- Video count display
- Category chip

### VideoGridAdapter

**File**: [VideoGridAdapter.kt](../../android/app/src/main/java/com/albunyaan/tube/ui/adapters/VideoGridAdapter.kt)

**Key Features**:
- 16:9 thumbnail aspect ratio
- Duration badge overlay
- Views + date formatting

### CategoryAdapter

**File**: [CategoryAdapter.kt](../../android/app/src/main/java/com/albunyaan/tube/ui/categories/CategoryAdapter.kt)

**Key Features**:
- Conditional chevron display
- Click listener for navigation
- ListAdapter with DiffUtil

---

## String Resources

**File**: [strings.xml](../../android/app/src/main/res/values/strings.xml)

**Key Strings**:
```xml
<string name="app_name">Albunyaan Tube</string>
<string name="nav_home">Home</string>
<string name="nav_channels">Channels</string>
<string name="nav_playlists">Playlists</string>
<string name="nav_videos">Videos</string>
<string name="categories">Categories</string>
<string name="see_all">See all</string>
<string name="menu">Menu</string>
<string name="search">Search</string>
```

---

## UI Guidelines

### 1. Layout Patterns

**Section Header**:
```xml
<TextView
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:text="@string/section_title"
    android:textSize="18sp"
    android:textStyle="bold"
    android:textColor="@android:color/black"
    android:layout_marginBottom="@dimen/spacing_sm"/>
```

**White Card Container**:
```xml
<com.google.android.material.card.MaterialCardView
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:layout_marginBottom="@dimen/spacing_md"
    app:cardBackgroundColor="@android:color/white"
    app:cardCornerRadius="@dimen/corner_radius_medium"
    app:cardElevation="0dp">
    <!-- Content -->
</com.google.android.material.card.MaterialCardView>
```

**Divider Between Items**:
```xml
<View
    android:layout_width="match_parent"
    android:layout_height="1dp"
    android:background="@color/background_gray"
    android:layout_marginStart="@dimen/spacing_lg"/>
```

### 2. Icon Guidelines

- All icons should be tinted with `primary_green` or white
- Use circular backgrounds for feature icons
- Navigation icons should be 24dp
- Feature icons (in circles) should be 40dp containers with 8dp padding

### 3. Text Guidelines

- **Black** for primary text (channel names, titles)
- **Green** for accents (subscriber counts, category names)
- **Gray** for secondary text (descriptions, metadata)
- **Bold** for titles and section headers
- **Regular** for body text

### 4. Spacing Guidelines

- Screen padding: 16dp
- Section margins: 16dp between sections
- Item spacing in RecyclerView: 8-16dp
- Card internal padding: 16dp

---

## Testing Checklist

### Visual Verification

- [ ] All screens match the implemented design (not mockups)
- [ ] Consistent spacing throughout
- [ ] Proper color usage (green accents, gray backgrounds)
- [ ] Icons display correctly
- [ ] Text wrapping works properly
- [ ] Chips display correctly
- [ ] Empty states show properly

### Functional Verification

- [ ] Bottom navigation switches tabs
- [ ] Tab icons change color on selection
- [ ] Home menu shows Settings and Downloads
- [ ] Settings navigation works
- [ ] Downloads navigation works
- [ ] Categories FAB opens Categories screen
- [ ] Categories navigate to Subcategories
- [ ] Channel items navigate to detail
- [ ] Back navigation works throughout
- [ ] Library items are clickable

### Accessibility

- [ ] All interactive elements have content descriptions
- [ ] Minimum touch targets (48dp)
- [ ] Color contrast meets WCAG AA
- [ ] Screen reader friendly

---

## Future Enhancements

**Phase 5 Pending**:
- [ ] Splash screen implementation
- [ ] Fix onboarding screen swiping/pagination
- [ ] Connect adapters to real API data
- [ ] Implement search functionality
- [ ] Add loading/error states
- [ ] Implement video playback
- [ ] Add download functionality
- [ ] Implement actual settings persistence

---

## Design Authority

**IMPORTANT**: The **implemented code** is the source of truth for UI design. Any mockups or design documents should be updated to match the current implementation, not the other way around.

**Current Implementation Status**: ✅ Complete and production-ready

**Last Code Review**: 2025-10-04

---

## Related Documentation

- [Phase 5 Sprint Plan](../roadmap/phases.md#phase-5--android-mvp-in-progress)
- [Navigation Graph](../../android/app/src/main/res/navigation/main_tabs_nav.xml)
- [Design Mockups Archive](../ux/mockups/2025-10-android/) - Reference only, code is authoritative

---

**Document Version**: 1.0
**Maintained By**: Development Team
**Review Frequency**: After each UI sprint
