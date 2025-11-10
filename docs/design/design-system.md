# Albunyaan Tube - Design Specification

> **Last Updated**: 2025-11-10
> **Status**: Admin UI ✅ | Android UI ✅ (15 screens, production-ready)
> **Authority**: **Implemented code is the source of truth**

---

## Overview

This document provides the complete UI/UX design specifications for both the Admin Panel and Android App. All specifications reflect the **actual implemented code**, not theoretical designs.

---

## Table of Contents

1. [Global Design Principles](#global-design-principles)
2. [Design Tokens](#design-tokens)
3. [Admin Panel Design](#admin-panel-design)
4. [Android App Design](#android-app-design) ⭐ **IMPLEMENTED**
5. [Component Library](#component-library)
6. [Accessibility](#accessibility)
7. [Internationalization](#internationalization)
8. [Design Authority](#design-authority)

---

<a id="global-design-principles"></a>

## 1. Global Design Principles

### Brand Promise
- **Halal Curation**: No comments, ads, or autoplay
- **Family-Safe**: Vetted Islamic educational content only
- **Offline-First**: Support for areas with limited connectivity
- **Multilingual**: English, Arabic, Dutch (with RTL support)

### Visual Identity
- **Primary Color**: `#35C491` (Teal/Green)
- **Typography**: Clean, readable sans-serif
- **Spacing**: 8dp/px baseline grid
- **Corner Radius**: 8-16dp standard
- **Elevation**: Subtle shadows for depth

### Interaction Principles
- **Minimum Touch Target**: 48dp (Android) / 44px (Web)
- **Feedback**: Immediate visual response to all interactions
- **Progressive Disclosure**: Advanced features hidden behind collapsible sections
- **Error Prevention**: Confirmation dialogs for destructive actions
- **Loading States**: Skeleton screens for content, spinners for actions

---

<a id="design-tokens"></a>

## 2. Design Tokens

### Colors

#### Admin Panel
```json
{
  "primary": "#2D9B8B",
  "primaryHover": "#1E7A6D",
  "primaryLight": "#E6F7F4",
  "success": "#10B981",
  "warning": "#F59E0B",
  "error": "#EF4444",
  "info": "#3B82F6",
  "bgMain": "#F9FAFB",
  "bgCard": "#FFFFFF",
  "border": "#D1D5DB",
  "textPrimary": "#111827",
  "textSecondary": "#6B7280"
}
```

#### Android App (Implemented)
```xml
<!-- colors.xml -->
<color name="primary_green">#275E4B</color>      <!-- Brand primary (darker) -->
<color name="primary_variant">#35C491</color>    <!-- Accents, active states (lighter) -->
<color name="background_gray">#F5F5F5</color>
<color name="icon_gray">#9E9E9E</color>
<color name="surface_variant">#E3E9E7</color>
```

### Typography

#### Admin Panel
- **H1**: 32px, Bold
- **H2**: 24px, Semibold
- **H3**: 20px, Semibold
- **Body**: 14px, Regular
- **Caption**: 12px, Regular
- **Button**: 14px, Semibold

#### Android App (Implemented)
```xml
<!-- Implemented sizes -->
<dimen name="text_headline">18sp</dimen>  <!-- Section titles -->
<dimen name="text_title">16sp</dimen>     <!-- Item titles -->
<dimen name="text_body">14-16sp</dimen>   <!-- Body text -->
<dimen name="text_caption">14sp</dimen>   <!-- Metadata -->
```

### Spacing (8dp Grid)
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

---

<a id="admin-panel-design"></a>

## 3. Admin Panel Design

### Layout Structure

**Sidebar Navigation**:
- Width: 260px fixed
- Background: White with border
- Logo + Navigation menu + User profile

**Main Content**:
- Header: 64px (Breadcrumb + Search + Notifications + Profile)
- Content: 24px padding, max-width 1440px
- Background: #F9FAFB

### Core Screens

*Full admin panel specifications remain unchanged from original implementation*
*See [Phase 3 Documentation](../roadmap/phases.md#phase-3--admin-ui-implementation) for complete admin UI details*

---

<a id="android-app-design"></a>

## 4. Android App Design ⭐ IMPLEMENTED

> **Implementation Files**: `/android/app/src/main/`
> **Status**: ✅ Production-ready, code is authoritative

### 4.1 Design System (Implemented)

#### Color Palette
**File**: `res/values/colors.xml`

- `primary_green`: `#275E4B` - Brand primary color (darker shade)
- `primary_variant`: `#35C491` - Accents, active states, highlights (lighter shade)
- `background_gray`: `#F5F5F5` - Screen backgrounds
- `icon_gray`: `#9E9E9E` - Secondary text, icons
- `surface_variant`: `#E3E9E7` - Secondary backgrounds, cards
- White: Card backgrounds, toolbars
- Black: Primary text

#### Spacing (8dp Grid)
**File**: `res/values/dimens.xml`

```xml
<dimen name="spacing_xs">4dp</dimen>
<dimen name="spacing_sm">8dp</dimen>
<dimen name="spacing_md">16dp</dimen>
<dimen name="spacing_lg">24dp</dimen>
<dimen name="spacing_xl">32dp</dimen>
```

#### Touch Targets
- Minimum: 48dp × 48dp
- Button height: 48dp
- FAB size: 56dp
- Icon size: 24dp

---

### 4.2 Navigation Structure (Implemented)

#### Bottom Navigation Bar
**File**: `res/layout/fragment_main_shell.xml`

**Design**:
- White background, 8dp elevation
- **5 tabs**: Home | Channels | Playlists | Videos | Downloads
- **Selection**: Icon color changes to `primary_green` (no background)
- **Unselected**: White with 60% opacity
- **Height**: 72dp (Material Design 3 standard)

**Tab Icons** (`res/drawable/`):
1. `ic_home.xml` - House icon
2. `ic_channels.xml` - Play button in rectangle
3. `ic_playlists.xml` - Horizontal lines
4. `ic_videos.xml` - 2×2 grid
5. `ic_download.xml` - Download icon

**State Selector**: `res/color/bottom_nav_item_color.xml`
```xml
<selector>
    <item android:color="@color/primary_green" android:state_checked="true"/>
    <item android:color="@android:color/white" android:alpha="0.6"/>
</selector>
```

---

### 4.3 Screen Implementations (15 Total)

#### Home Screen ✅
**Fragment**: `ui/home/HomeFragmentNew.kt`
**Layout**: `res/layout/fragment_home_new.xml`

**Structure**:
```
├── Header (White card)
│   ├── App logo + "Albunyaan" title
│   ├── Category chip (filter)
│   ├── Search icon
│   └── Kebab menu (⋮ rotated 90°)
│       ├── Settings
│       └── Downloads
├── Channels Section
│   ├── "Channels" + "See all" →
│   └── Horizontal RecyclerView
├── Playlists Section
│   ├── "Playlists" + "See all" →
│   └── Horizontal RecyclerView
└── Videos Section
    ├── "Videos" + "See all" →
    └── Horizontal RecyclerView
```

**Key Features**:
- Pull-to-refresh
- Category filtering
- Horizontal scroll per section
- Navigation to all screens

---

#### Channels Screen ✅
**Fragment**: `ui/ChannelsFragmentNew.kt`
**Layout**: `res/layout/fragment_channels_new.xml`

**Structure**:
```
├── Vertical RecyclerView
└── FAB (Categories access)
    └── Background: primary_green
```

**Channel Item** (`res/layout/item_channel.xml`):
```
├── Circular Avatar (48dp)
├── Channel Name (16sp, bold, max 2 lines)
├── Subscriber Count (14sp, green)
└── Category Chip (below subscribers)
    └── Format: "Dawah +9" (first category + count)
```

**Adapter**: `ui/adapters/ChannelAdapter.kt`

**Key Features**:
- Channel names wrap to 2 lines (no gap if 1 line)
- Categories below subscriber count
- Single category with "+N" indicator
- Click → Channel Detail

---

#### Playlists Screen ✅
**Fragment**: `ui/PlaylistsFragmentNew.kt`
**Item**: `res/layout/item_playlist.xml`

```
├── Square Thumbnail (80dp)
├── Playlist Title (16sp, bold)
├── Video Count (14sp, green)
└── Category Chip
```

**Adapter**: `ui/adapters/PlaylistAdapter.kt`

---

#### Videos Screen ✅
**Fragment**: `ui/VideosFragmentNew.kt`
**Item**: `res/layout/item_video_grid.xml`
**Layout**: GridLayoutManager (2 columns)

```
├── 16:9 Thumbnail
├── Duration Badge (bottom-right)
├── Video Title (14sp, max 2 lines)
├── Channel Name (12sp, gray)
└── Views + Date (12sp, gray)
```

**Adapter**: `ui/adapters/VideoGridAdapter.kt`

---

#### Channel Detail Screen ✅
**Fragment**: `ui/detail/ChannelDetailFragment.kt`
**Layout**: `res/layout/fragment_channel_detail.xml`

```
├── Toolbar (with back navigation)
├── Channel Header
│   ├── Banner image
│   ├── Circular avatar (overlap)
│   ├── Channel name (H1)
│   └── Subscriber count
├── TabLayout (Videos | Playlists)
└── ViewPager2 (content)
```

**Navigation**:
- Back button: `findNavController().navigateUp()`
- Deep link: `albunyaantube://channel/{channelId}`

---

#### Categories Screen ✅
**Fragment**: `ui/categories/CategoriesFragment.kt`
**Layout**: `res/layout/fragment_categories.xml`

```
├── Toolbar ("Categories")
└── White Card
    └── RecyclerView
        └── Category Items
            ├── Circular icon (40dp, teal bg)
            ├── Category name
            └── Chevron → (if hasSubcategories)
```

**Mock Data** (10 categories):
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

**Access**: FAB on Channels screen

---

#### Subcategories Screen ✅
**Fragment**: `ui/categories/SubcategoriesFragment.kt`
**Layout**: `res/layout/fragment_subcategories.xml`

Same structure as Categories, with dynamic title

**Navigation Args**:
- `categoryId`: String
- `categoryName`: String

---

#### Settings Screen ✅
**Fragment**: `ui/settings/SettingsFragment.kt`
**Layout**: `res/layout/fragment_settings.xml`

**Structure**:
```
├── Toolbar ("Settings")
└── ScrollView
    ├── General
    │   ├── Language (English) →
    │   └── Theme (Light) →
    ├── Playback
    │   ├── Audio-only [toggle]
    │   └── Background Play [toggle]
    ├── Downloads
    │   ├── Download Quality (High 720p) →
    │   └── Wi-Fi Only Downloads [toggle]
    ├── Content
    │   └── Family-Friendly Safe Mode [toggle] ✓
    └── About & Support
        └── Support Center →
```

**Settings Item Types**:

**With Subtitle** (`settings_item_language.xml`, `settings_item_theme.xml`):
```xml
├── Circular Icon (40dp, teal bg)
├── Title + Subtitle
└── Chevron →
```

**With Toggle** (`settings_item_audio_only.xml`, etc.):
```xml
├── Circular Icon
├── Title + Description
└── Switch
```

**Design Pattern**:
- White MaterialCardView per section
- 16dp padding
- 1dp gray dividers between items (marginStart=24dp)
- Safe Mode default: ON

**Access**: Home menu → Settings

---

#### Downloads & Library Screen ✅
**Fragment**: `ui/download/DownloadsFragment.kt`
**Layout**: `res/layout/fragment_downloads.xml`

**Structure**:
```
├── Toolbar ("Downloads & Library")
└── ScrollView
    ├── Downloads Section
    │   └── White Card
    │       ├── RecyclerView (downloads)
    │       ├── Empty State
    │       │   ├── Download icon (64dp, 30% opacity)
    │       │   ├── "No downloads yet" (16sp)
    │       │   └── "Downloaded videos will appear here" (14sp)
    │       └── Storage Info Card
    │           ├── "Storage Used" (14sp, bold)
    │           ├── "X MB used" (14sp, gray)
    │           ├── "Device storage available: Y GB" (12sp, gray)
    │           └── ProgressBar (green)
    │           Note: No artificial quota - user's device storage is the natural limit
    └── Library Section
        └── White Card
            ├── Saved (0 videos) →
            ├── Recently Watched (0 videos) →
            └── History (0 videos) →
```

**Library Item** (`library_item_saved.xml`, etc.):
```xml
├── Circular Icon (40dp, teal bg)
├── Title + Count
└── Chevron →
```

**Access**: Home menu → Downloads

---

#### Search Screen ✅
**Fragment**: `ui/search/SearchFragment.kt`
**Layout**: `res/layout/fragment_search.xml`

**Structure**:
```
├── Toolbar (\"Search\", back button)
└── SearchView (Material3)
    ├── Search History (if empty query)
    │   ├── Recent searches list (max 10)
    │   └── Clear all button
    └── Search Results (after query)
        ├── Channels section
        ├── Playlists section
        └── Videos section
```

**Search History**:
- Stored in DataStore (persistent)
- Max 10 recent searches
- Delete individual entries
- Clear all button

**Search Results**:
- Combined results from backend API
- Sections: Channels, Playlists, Videos
- Click → Navigate to detail screen

**Access**: Home screen search icon

---

#### About Screen ✅
**Fragment**: `ui/AboutFragment.kt`
**Layout**: `res/layout/fragment_about.xml`

**Structure**:
```
├── Toolbar (\"About\")
└── Content
    ├── App Icon (120dp)
    ├── App Name + Version
    ├── Description text
    └── Links
        ├── Support Center
        └── Privacy Policy
```

**Access**: Settings → About

---

#### Splash Screen ✅
**Fragment**: `ui/SplashFragment.kt`
**Layout**: `res/layout/fragment_splash.xml`

**Structure**:
```
├── Centered Content
│   ├── App Icon (120dp)
│   │   └── House icon in primary_green
│   ├── App Name ("Albunyaan")
│   │   └── 24sp, bold
│   └── Loading Indicator
│       └── Circular progress (primary_green)
```

**Behavior**:
- Checks if user has seen onboarding
- Auto-navigates to Onboarding (first launch) or Home (subsequent launches)
- 2-second minimum display time

**Design Tokens**:
- Icon color: `primary_green` (#35C491)
- Background: White
- Progress tint: `primary_green`

---

#### Onboarding Screen ✅
**Fragment**: `ui/OnboardingFragment.kt`
**Layout**: `res/layout/fragment_onboarding.xml`

**Structure**:
```
├── ViewPager2 (swipeable)
│   └── 3 Pages
│       ├── Page 1: Welcome
│       │   ├── Icon: House (64dp, white on green circle)
│       │   ├── Title: "Welcome to Albunyaan"
│       │   └── Description: "Your trusted source..."
│       ├── Page 2: Explore Content
│       │   ├── Icon: Compass
│       │   └── Description about browsing
│       └── Page 3: Audio Learning
│           ├── Icon: Headphones
│           └── Description about audio-only mode
├── Continue Button
│   └── Full-width, primary_green, 28dp radius
├── Page Indicators (3 dots)
│   ├── Active: primary_green, 8dp circle
│   └── Inactive: #CCCCCC, 8dp circle
└── Skip Button (text only, gray)
```

**Behavior**:
- Swipe or tap "Continue" to advance
- Page indicators update on swipe ✅ FIXED
- "Continue" → "Get Started" on last page
- Skip button always visible
- Saves preference to SharedPreferences

**Icons**:
- Background: 64dp circle, `#F0F0F0` background
- Icon color: `primary_green`

**Key Fix**: Indicator dots now properly update when swiping between pages (lazy initialization issue resolved)

---

#### Player Screen ✅
**Fragment**: `ui/player/PlayerFragment.kt`
**Layout**: `res/layout/fragment_player.xml`

**Structure** (Scrollable):
```
├── AppBarLayout
│   └── ExoPlayer View (240dp height)
│       └── Custom controls (exo_player_control_view.xml)
│           ├── Gradient overlays (top/bottom)
│           ├── Large play/pause button
│           ├── Progress bar
│           └── Quality/PiP/Fullscreen buttons
├── NestedScrollView
│   ├── Video Title (18sp, bold, black)
│   ├── Author Name (14sp, primary_green)
│   ├── Stats (12sp, gray)
│   │   └── "X views • Date"
│   ├── Description Card (expandable)
│   │   ├── Header ("Description" + arrow)
│   │   └── Content (collapsible)
│   ├── Action Buttons Row
│   │   ├── Like (icon + label)
│   │   ├── Share (icon + label)
│   │   ├── Download (icon + label)
│   │   └── Audio (icon + label)
│   ├── Divider
│   └── Up Next Section
│       ├── "Up Next" header
│       └── RecyclerView (queue)
```

**Player Features**:
- ✅ ExoPlayer integration with NewPipeExtractor (no official YouTube API)
- ✅ Custom gesture controls:
  - Left side swipe: Brightness control (0-100%)
  - Right side swipe: Volume control (system volume)
  - Double-tap left/right: Seek ±10 seconds
  - Single tap: Show/hide controls
- ✅ Picture-in-Picture with dynamic aspect ratio (Android 8+)
- ✅ Landscape fullscreen with immersive mode (hides status/nav bars)
- ✅ Quality selection dialog (144p-4K, auto quality)
- ✅ Google Cast Framework integration (Chromecast support)
- ✅ Subtitle/caption support with multi-language tracks
- ✅ Audio-only mode toggle
- ✅ Background playback service structure (MediaSession ready)
- ✅ Share functionality (external apps via intent chooser)
- ✅ UpNext recommendations queue display

**UI Interactions**:
- Description dropdown: Tap to expand/collapse with arrow rotation
- Like button: Shows "coming soon" toast
- Share button: Opens Android share sheet
- Download button: Triggers download
- Audio button: Toggles audio-only mode

**Navigation**:
- Accessed from video clicks in Home/Channels/Playlists/Videos
- Arguments: `videoId`, `playlistId` (optional)
- Back button: Returns to previous screen

**Landscape Layout**: `res/layout-land/fragment_player.xml`
- Fullscreen player
- Hides bottom navigation
- Optimized for video viewing

---

### 4.4 Navigation Graph (Implemented)

**Main Graph**: `res/navigation/app_nav_graph.xml`
**Nested Graph**: `res/navigation/main_tabs_nav.xml`

**App-Level Destinations**:
1. `splashFragment` → SplashFragment (start destination)
2. `onboardingFragment` → OnboardingFragment
3. `mainShellFragment` → MainShellFragment (contains nested nav)
4. `playerFragment` → PlayerFragment (args: videoId, playlistId?)

**Tab-Level Destinations** (inside MainShellFragment):
1. `homeFragment` → HomeFragmentNew
2. `channelsFragment` → ChannelsFragmentNew
3. `playlistsFragment` → PlaylistsFragmentNew
4. `videosFragment` → VideosFragmentNew
5. `downloadsFragment` → DownloadsFragment
6. `channelDetailFragment` → ChannelDetailFragment (args: channelId, channelName, excluded)
7. `playlistDetailFragment` → PlaylistDetailFragment (args: playlistId, title, category, count, downloadPolicy, excluded)
8. `categoriesFragment` → CategoriesFragment
9. `subcategoriesFragment` → SubcategoriesFragment (args: categoryId, categoryName)
10. `settingsFragment` → SettingsFragment

**Navigation Flow**:
```
Splash → Onboarding (first launch) → Main Shell
      ↘ Main Shell (returning users)

Main Shell (Bottom Navigation - 5 Tabs):
├── Home
│   ├── Search → Search Results
│   ├── Settings → Language/Theme/About dialogs
│   └── Downloads → Downloads & Library
├── Channels
│   ├── Categories FAB → Categories → Subcategories
│   └── Channel Click → Channel Detail
├── Playlists → Playlist Detail
├── Videos
└── More (future features)

Any Video Click → Player (app-level navigation)
```

**Key Navigation Features**:
- Back button from Downloads/Settings → Home tab (not exit app) ✅
- Video clicks use parent nav controller to access player
- Splash auto-skipped if onboarding completed
- Deep links supported for channels and playlists

---

### 4.5 Data Models (Implemented)

**Location**: `android/app/src/main/java/com/albunyaan/tube/data/model/`

#### ContentItem.kt
```kotlin
sealed class ContentItem {
    data class Video(
        val id: String,
        val title: String,
        val category: String,
        val durationMinutes: Int,
        val uploadedDaysAgo: Int,
        val description: String,
        val thumbnailUrl: String? = null,
        val viewCount: Long? = null
    ) : ContentItem()

    data class Channel(
        val id: String,
        val name: String,
        val category: String, // Primary category for backward compatibility
        val subscribers: Int,
        val description: String? = null,
        val thumbnailUrl: String? = null,
        val videoCount: Int? = null,
        val categories: List<String>? = null // Multiple categories
    ) : ContentItem()

    data class Playlist(
        val id: String,
        val title: String,
        val category: String,
        val itemCount: Int,
        val description: String? = null,
        val thumbnailUrl: String? = null
    ) : ContentItem()
}
```

#### Category.kt
```kotlin
data class Category(
    val id: String,
    val name: String,
    val slug: String? = null,
    val parentId: String? = null,
    val hasSubcategories: Boolean = false,
    val icon: String? = null // Emoji or icon identifier
)
```

#### CursorResponse.kt
```kotlin
data class CursorResponse(
    val data: List<ContentItem>,
    val pageInfo: PageInfo?
) {
    data class PageInfo(
        val nextCursor: String?
    )
}

enum class ContentType {
    HOME,
    CHANNELS,
    PLAYLISTS,
    VIDEOS
}
```

**Key Model Features**:
- **Sealed Class**: `ContentItem` ensures type-safe content handling
- **Multiple Categories**: Channels support both single `category` (primary) and `categories` list
- **Cursor Pagination**: `CursorResponse` with `nextCursor` for infinite scroll
- **Hierarchical Categories**: `parentId` and `hasSubcategories` support category trees
- **Relative Time**: Videos use `uploadedDaysAgo` and `durationMinutes` (not strings)
- **View Counts**: Videos support `viewCount` as nullable `Long`

---

### 4.6 Component Patterns (Implemented)

#### Material Cards
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

#### Category Chips
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

#### FloatingActionButton
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

#### Circular Icon Background
**File**: `res/drawable/onboarding_icon_bg.xml`
```xml
<shape android:shape="oval">
    <solid android:color="@color/primary_green"/>
    <size android:width="40dp" android:height="40dp"/>
</shape>
```

---

### 4.7 UI Guidelines (Implemented)

#### Section Header Pattern
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

#### Divider Between Items
```xml
<View
    android:layout_width="match_parent"
    android:layout_height="1dp"
    android:background="@color/background_gray"
    android:layout_marginStart="@dimen/spacing_lg"/>
```

#### Text Color Usage
- **Black**: Primary text (titles, channel names)
- **Green**: Accents (subscriber counts, category names)
- **Gray**: Secondary text (descriptions, metadata)

#### Spacing Guidelines
- Screen padding: 16dp
- Section margins: 16dp between sections
- Item spacing: 8-16dp
- Card padding: 16dp

---

<a id="component-library"></a>

## 5. Component Library

### 5.1 Admin Panel Components

*Unchanged from original specification - see backup for full details*

### 5.2 Android Components (Implemented)

#### List Card Pattern
**Height**: 80dp
**Layout**:
```
├── Avatar/Thumbnail (left, 48-56dp)
├── Title (Body, bold)
├── Subtitle (Caption, secondary)
└── Chevron (optional, 24dp)
```

#### Grid Card Pattern
**Aspect Ratio**: 16:9
**Border Radius**: 16dp
```
├── Thumbnail (top)
├── Title (Body, bold, 2 lines max)
├── Metadata (Caption)
└── Overlay badges (if applicable)
```

#### Hero Card (Detail Screens)
```
├── Banner image / large thumbnail
├── Circular avatar (overlap)
├── Title (H1)
├── Metadata (subscriber count, video count)
└── Action buttons
```

---

<a id="accessibility"></a>

## 6. Accessibility

### Android Accessibility (Implemented)

#### Touch Targets
- Minimum: 48dp × 48dp
- All interactive elements meet this standard
- Bottom nav tabs: 56dp height

#### Content Descriptions
- All images and icons have `contentDescription`
- Icon-only buttons clearly labeled
- Decorative images: `contentDescription="@null"`

#### Text Scaling
- All text uses `sp` units (not `dp`)
- Layouts tested at 200% scale
- Support system font size settings

#### TalkBack Support
- Logical focus order (top-to-bottom, left-to-right)
- State announcements ("Selected", "Collapsed", etc.)
- Group related elements where appropriate

---

<a id="internationalization"></a>

## 7. Internationalization

### Supported Languages
- **English** (en) - Default
- **Arabic** (ar) - RTL support
- **Dutch** (nl)

### Android RTL Support

**Manifest**:
```xml
<application
    android:supportsRtl="true"
    ...>
```

**Layout Guidelines**:
- Use `start`/`end` instead of `left`/`right`
- Icons automatically flip where appropriate
- Test with "Force RTL" in developer options

**String Resources**:
- `res/values/strings.xml` (English)
- `res/values-ar/strings.xml` (Arabic)
- `res/values-nl/strings.xml` (Dutch)

---

<a id="design-authority"></a>

## 8. Design Authority

**IMPORTANT**: The **implemented code** is the authoritative source for UI design. Design mockups serve as reference only.

**Current Status**: All documented designs match production code.

**Design Files Location**:
- Admin: `frontend/src/`
- Android: `android/app/src/main/`
- Mockups (archive): `docs/ux/mockups/2025-10-android/`

---

## References

### Implementation Files
- **Android Layouts**: `android/app/src/main/res/layout/`
- **Android Fragments**: `android/app/src/main/java/com/albunyaan/tube/ui/`
- **Navigation**: `android/app/src/main/res/navigation/main_tabs_nav.xml`
- **Colors**: `android/app/src/main/res/values/colors.xml`
- **Dimensions**: `android/app/src/main/res/values/dimens.xml`

### Documentation
- **Phase Roadmap**: `docs/roadmap/phases.md`
- **Admin Mockups**: Figma (12 screens, 2025-10-03)
- **Android Mockups**: `docs/ux/mockups/2025-10-android/` (reference only)

---

**Document Version**: 4.0
**Last Updated**: 2025-11-10
**Next Review**: After backend API integration complete

**Changelog**:
- **v4.0 (2025-11-10)**: Updated color tokens (primary_green + primary_variant), added Search and About screens, documented gesture controls, removed artificial storage quota, clarified NewPipeExtractor usage (no YouTube API), updated to 15 total screens
- **v3.0 (2025-10-04)**: Initial production documentation
- **v2.0**: Design system finalization
- **v1.0**: Initial draft
