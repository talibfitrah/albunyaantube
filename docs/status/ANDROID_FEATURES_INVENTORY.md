# Albunyaan Tube - Android App: Complete Feature Inventory

## Overview
- **Total Fragments/Screens**: 19 implemented
- **Architecture**: MVVM (Jetpack Architecture)
- **Key Libraries**: ExoPlayer, NewPipe Extractor, Retrofit, Jetpack Compose, Material Design 3
- **Languages**: Kotlin
- **Android SDK**: Min API 24+

---

## SECTION 1: NAVIGATION & SCREENS

### 1.1 Core Navigation Structure
- **App Flow**: Splash → Onboarding (optional) → Main Shell with Bottom Navigation
- **Deep Links**: Supported for channels, playlists, and player
  - `albunyaantube://channel/{channelId}`
  - `albunyaantube://playlist/{playlistId}`

### 1.2 Main Screens (5 Tabs in Bottom Navigation)

#### Tab 1: Home Screen
- **Fragment**: `HomeFragmentNew`
- **Features**:
  - Horizontal carousel of trending channels
  - Horizontal carousel of featured playlists
  - Horizontal carousel of recommended videos
  - Category quick-access chip
  - Search button
  - Menu button
  - "See All" buttons for each section
  - No pagination (fixed carousel views)

#### Tab 2: Channels Screen
- **Fragment**: `ChannelsFragmentNew`
- **Features**:
  - Full list of all channels (paginated)
  - Click-to-view channel details
  - Channel thumbnail, name, subscriber count
  - Search/filtering capability

#### Tab 3: Playlists Screen
- **Fragment**: `PlaylistsFragmentNew`
- **Features**:
  - Full list of all playlists (paginated)
  - Playlist thumbnails and metadata
  - Item count display
  - Click-to-view playlist details
  - Category information

#### Tab 4: Videos Screen
- **Fragment**: `VideosFragmentNew`
- **Features**:
  - Full list of all videos (paginated)
  - Video thumbnails, titles, channel names
  - View count display
  - Video duration badges
  - Click-to-play video

#### Tab 5: More/Downloads Screen
- **Fragment**: `DownloadsFragment`
- **Features**:
  - Downloads library management
  - Download progress tracking
  - Pause/Resume individual downloads
  - Cancel downloads
  - Open downloaded files in external player
  - Storage quota display (used/total)
  - Quick library sections: Saved, Recently Watched, History

---

## SECTION 2: DETAILED SCREEN FEATURES

### 2.1 Splash & Onboarding
- **SplashFragment**: Loads on app startup
- **OnboardingFragment**: 3-page carousel (optional, skippable)
  - Introduction slides
  - Navigate to main app after completion

### 2.2 Channel Detail Screen
- **Fragment**: `ChannelDetailFragment`
- **Features**:
  - Channel banner/thumbnail
  - Channel name and subscriber count (formatted: 1.2M format)
  - Channel description
  - Category chips (multi-category support)
  - **Tabbed content**:
    - Videos tab: List of channel videos
    - Playlists tab: Channel's playlists
    - Details tab: Additional info
  - Exclusion banner (if channel excluded from app)
  - ViewPager with TabLayout for tab navigation

### 2.3 Playlist Detail Screen
- **Fragment**: `PlaylistDetailFragment`
- **Features**:
  - Playlist metadata (title, category, item count)
  - List of videos in playlist (paginated)
  - Video grid layout with thumbnails
  - Download policy information
  - Exclusion banner (if playlist excluded)
  - Click-to-play any video in list

### 2.4 Categories Screen
- **Fragment**: `CategoriesFragment`
- **Features**:
  - Top-level categories list
  - Click-to-expand hierarchy
  - Subcategories navigation

#### Subcategories Screen
- **Fragment**: `SubcategoriesFragment`
- **Features**:
  - Content filtered by selected category
  - Videos grouped by subcategory
  - Click-to-view category details

### 2.5 Search Screen
- **Fragment**: `SearchFragment`
- **Features**:
  - Backend-integrated search (API endpoint `/api/v1/search`)
  - **Search History**:
    - Persisted in SharedPreferences
    - Max 10 queries retained
    - Click history to re-search
    - Delete individual history items
    - Clear all history button
  - **Real-time Search**:
    - 500ms debounce on text input
    - Minimum 2 characters required
    - Loading state indicator
    - Empty state message
    - Error handling
  - **Search Results**:
    - Shows channels, playlists, videos
    - Click results to navigate to detail or player

### 2.6 Settings Screen
- **Fragment**: `SettingsFragment`
- **Features**:
  - **Playback Settings**:
    - Audio-only mode toggle (persist to DataStore)
    - Background play toggle
  - **Download Settings**:
    - Download quality selector (low/medium/high)
    - WiFi-only downloads toggle
  - **Content Settings**:
    - Safe mode toggle (family-friendly filtering)
  - **Storage Management**:
    - Storage quota display (used/total 500MB)
    - Storage progress bar
    - Storage location selector
    - Clear all downloads button with confirmation
  - **Language Selection**: Dialog for en/ar/nl with locale persistence
  - **Theme Setting**: Coming soon placeholder
  - **Support Center**: Links to About screen

### 2.7 About Screen
- **Fragment**: `AboutFragment`
- **Features**:
  - App version info
  - Support/help information
  - Legal/attribution info

---

## SECTION 3: PLAYER FEATURES

### 3.1 Player Screen Architecture
- **Fragment**: `PlayerFragment` with ExoPlayer integration
- **Layout**: AppBar + PlayerView + Metadata + UpNext queue
- **Service**: `PlaybackService` (foreground service for background audio)
- **Repository**: `PlayerRepository` with NewPipe stream extraction

### 3.2 Core Playback Features
- **Video Format Support**:
  - H.264/H.265 video codecs
  - AAC/MP3/Opus audio codecs
  - Subtitle/caption support
- **Stream Resolution**:
  - Multi-quality support (144p-4K if available)
  - Quality seamless switching (resume at same position)
- **Audio-only Mode**:
  - Toggle in UI (switch control)
  - Persists to settings
  - Reduces bandwidth consumption

### 3.3 Playback Controls
- **Basic Controls** (ExoPlayer built-in):
  - Play/Pause
  - Seek bar (scrubbing)
  - Volume control
  - Fullscreen toggle
- **Advanced Controls** (Custom UI):
  - 10-second forward button
  - 10-second rewind button
  - Auto-hide controls (5-second timeout)
  - Manual control visibility toggle

### 3.4 Gesture Controls (PlayerGestureDetector)
- **Brightness Control**: Swipe up/down on left side
- **Volume Control**: Swipe up/down on right side
- **Seek Shortcuts**:
  - Double-tap left side: Seek back 10s
  - Double-tap right side: Seek forward 10s
- **Smooth gesture handling**: No UI popups

### 3.5 Quality Selection
- **Feature**: Quality selector dialog
- **Implementations**:
  - Dropdown showing all available qualities (1080p, 720p, 480p, etc.)
  - Qualities sorted highest to lowest
  - Resolution info displayed (e.g., "1080p (1920x1080)")
  - Current quality highlighted
  - Seamless quality switching
  - Toast notification on quality change

### 3.6 Subtitle/Caption Support
- **Caption Selector Dialog**:
  - List of available subtitle tracks
  - "Off" option to disable captions
  - Auto-generated caption labels marked
  - Current subtitle highlighted
  - Language-based selection
- **Caption Display**: Rendered on ExoPlayer via SubtitleTrack

### 3.7 Video Metadata Display
- **Currently Playing Section**:
  - Video title
  - Channel/author name
  - View count (formatted: 1.2M)
  - Upload/publish time (humanized: "2 days ago")
- **Description Section**:
  - Expandable/collapsible description text
  - Click description header to toggle
  - Animated arrow indicator

### 3.8 Player Actions
- **Like Button**: Placeholder (coming soon)
- **Share Button**:
  - Share via intent chooser
  - Text includes: title, description, YouTube URL, app promotion
  - Truncates long content (max 2 lines ~160 chars)
- **Complete Button**: Mark video as watched, auto-play next
- **Download Button**:
  - Download current video
  - State-aware button (different action per status):
    - COMPLETED: Open file in external player
    - RUNNING: Disabled
    - QUEUED: Disabled
    - Otherwise: Start new download
  - EULA acceptance required

### 3.9 Chromecast/Cast Support
- **Cast Button**: MediaRouter button in player
- **Cast Features**:
  - Detect available Chromecast devices
  - Send current video to Cast device
  - Pass metadata (title, subtitle, thumbnail)
  - Pause local playback when casting
  - Resume local playback on disconnect
- **Library**: Google Play Services Cast Framework

### 3.10 Picture-in-Picture (PiP) Mode
- **Menu Option**: "Enter PiP" action menu item
- **Features**:
  - Auto-enter fullscreen in landscape
  - Auto-exit fullscreen in portrait
  - Aspect ratio calculation from video dimensions (fallback 16:9)
  - Minimal controls in PiP (default ExoPlayer behavior)
- **OS Support**: Android 8+ (API 26+)

### 3.11 Fullscreen Mode
- **Toggle**: Fullscreen button in player overlay
- **Fullscreen Behavior**:
  - Hides system status bar
  - Hides system navigation bar (immersive sticky)
  - Expands player to full screen
  - Hides scrollable metadata content
  - Centering of player with aspect ratio preservation
- **Exit**: Click fullscreen button again or back button

### 3.12 UpNext Queue
- **Queue Display**:
  - RecyclerView below player
  - List of next-to-play videos
  - Click to jump to video
  - Shows excluded items count
- **Queue Management**:
  - Hydrated from backend recommendations (next-up API)
  - Auto-advance on completion
  - Manual selection support
- **Empty State**: "No more videos" message

### 3.13 Analytics & Events
- **Events Tracked**:
  - Queue Hydrated: Total items and excluded count
  - Playback Started: Item and reason (USER_SELECTED, AUTO, RESUME)
  - Playback Completed: Item name
  - Audio-only toggled: On/off status
  - Stream Resolved: Quality label
  - Stream Failed: Error message
  - Quality Changed: New quality label
  - Subtitle Changed: Language name or "Off"
- **Display**: Real-time event log in player state

---

## SECTION 4: DOWNLOAD FEATURES

### 4.1 Download Architecture
- **Manager**: `DownloadRepository` with WorkManager backend
- **Storage**: Internal app-specific files directory
- **Worker**: `DownloadWorker` for background processing
- **Database**: `DownloadStorage` for persistence

### 4.2 Download UI (DownloadsFragment)
- **Downloads List**:
  - RecyclerView with download entries
  - Dividers between items
  - Empty state message
  - Storage quota display
- **Download Item Controls**:
  - Pause/Resume button (changes based on status)
  - Cancel button
  - Open button (when complete)
  - Progress bar
  - Status text

### 4.3 Download Statuses
- **QUEUED**: Waiting to start
- **RUNNING**: Currently downloading
- **PAUSED**: User paused
- **COMPLETED**: Ready to play/share
- **FAILED**: Error occurred
- **CANCELED**: User canceled

### 4.4 Download Options
- **Quality Selector**: Pre-download quality selection
  - Low (360p)
  - Medium (720p) - default
  - High (1080p)
- **Format Selection**:
  - Video + Audio
  - Audio-only (for music/podcasts)
  - Persisted preference in settings

### 4.5 Download Policies
- **WiFi-only Toggle**: Settings option

### 4.6 Downloaded File Management
- **File Opening**: Via FileProvider and intent chooser
- **Player Support**: Opens in default video player
- **Permissions**: Temporary read permission grants
- **Storage Path**: `context.filesDir/downloads/`

### 4.7 Storage Management (Settings)
- **Quota System**:
  - Default 500MB limit
  - Progress bar visualization
  - Used/Total display (formatted: "250 MB of 500 MB")
- **Clear Downloads**:
  - Confirmation dialog
  - Recursive directory deletion
  - Count feedback ("Cleared 45 files")

### 4.8 Quick Library Sections (Placeholders)
- **Saved**: Navigate to saved videos (TODO)
- **Recently Watched**: History of watched videos (TODO)
- **History**: Full watch history (TODO)

---

## SECTION 5: SETTINGS & PREFERENCES

### 5.1 Preference Storage (DataStore-based)
**File**: `SettingsPreferences.kt`

**Locale Settings**:
- Current locale (en/ar/nl)
- Persistent across app restarts
- RTL support for Arabic

**Playback Preferences**:
- Audio-only mode toggle (default: false)
- Background playback toggle (default: true)

**Download Preferences**:
- Download quality (low/medium/high, default: medium)
- WiFi-only downloads toggle (default: false)

**Content Preferences**:
- Safe mode toggle (default: true)
- Family-friendly filtering

**Theme Settings**:
- Theme choice (light/dark/system, default: system)
- For future implementation

### 5.2 Language Selection Dialog
- **Supported Languages**: en, ar, nl
- **Dialog Type**: Single-choice selection
- **Persistence**: Via LocaleManager
- **App Restart**: Recommended (snackbar message)
- **RTL Support**: Automatic for Arabic

### 5.3 Download Quality Dialog
- **Options**: 
  - Low (360p)
  - Medium (720p)
  - High (1080p)
- **Selection**: Single-choice dialog
- **Feedback**: Toast notification with selected quality

---

## SECTION 6: INTERNATIONALIZATION (i18n)

### 6.1 Language Support
- **English** (en): Default
- **Arabic** (ar): Right-to-left, full UI support
- **Dutch** (nl): Additional language

### 6.2 RTL Support
- **Automatic**: Android handles RTL layout direction
- **Locale Manager**: `LocaleManager.kt` handles locale switching
- **Document Direction**: RTL applied dynamically

### 6.3 String Resources
- Located in: `res/values/strings.xml`, `res/values-ar/strings.xml`, `res/values-nl/strings.xml`
- All UI labels support all 3 languages

---

## SECTION 7: ADVANCED FEATURES

### 7.1 Filtering & Sorting
**FilterState Data Class**:
- Category filter (by category ID)
- Video Length filter (ANY, UNDER_4_MIN, 4-20_MIN, OVER_20_MIN)
- Published Date filter (ANY, LAST_24_HOURS, LAST_7_DAYS, LAST_30_DAYS)
- Sort Option (DEFAULT, MOST_POPULAR, NEWEST)
- Persistence: Via DataStore in FilterManager
- Applied globally across tabs

### 7.2 Paging & Pagination
**Cursor-based Pagination**:
- `ContentPagingRepository`: Manages pagination state
- `CursorPagingSource`: Cursor-based navigation
- **Behavior**:
  - Loads content in chunks
  - Cursor points to last item
  - Next page uses cursor offset
  - Infinite scroll support

### 7.3 Content Models
**Main Content Types**:
- Channel: ID, name, description, thumbnailUrl, subscribers, categories
- Playlist: ID, title, category, itemCount, thumbnail, downloadPolicy, excluded
- Video: ID, title, channelName, description, duration, thumbnail, viewCount, streamId

### 7.4 Content Service Integration
**RetrofitContentService**: 
- Base URL: `http://10.0.2.2:8080/api` (for emulator)
- Available Endpoints:
  - GET `/v1/channels` - List channels
  - GET `/v1/playlists` - List playlists
  - GET `/v1/videos` - List videos
  - GET `/v1/search` - Search content
  - GET `/v1/categories` - List categories
  - GET `/v1/player/queue` - Next-up recommendations

### 7.5 Analytics & Metrics
**Telemetry System**:
- `ListMetricsReporter`: Tracks list scrolling metrics
- `ExtractorMetricsReporter`: Monitors stream extraction performance
- `TelemetryExtractorMetricsReporter`: Aggregates metrics
- **Events**: Load times, extraction failures, quality selection changes

### 7.6 Stream Extraction & Resolution
**NewPipe Integration**:
- `NewPipeExtractorClient`: Extracts available streams from YouTube
- **Extracted Data**:
  - Video/audio tracks with bitrates, MIME types, qualityLabels
  - Subtitle tracks with language codes
  - Combined into `ResolvedStreams` for player

**Stream Models**:
- `VideoTrack`: Resolution, bitrate, qualityLabel, mime type
- `AudioTrack`: Bitrate, language, mime type, isDefault
- `SubtitleTrack`: Language, isAutoGenerated, format

### 7.7 Image Loading
**Implementation**:
- Conditional image loading (enabled/disabled via ServiceLocator)
- Support for Glide/Coil (pluggable)
- Thumbnail caching

### 7.8 Accessibility
**Test Suite**: `AccessibilityTest.kt`
- Content description testing
- Contrast ratio verification
- Touch target size validation
- Keyboard navigation testing

### 7.9 Performance Optimization
- **Code Splitting**: Macro benchmarks for app startup
- **Memory**: Optimized image loading, lazy fragment loading
- **Network**: Cursor-based pagination, response compression
- **ExoPlayer Configuration**:
  - Custom load control (fast startup, adequate buffering)
  - Network wake mode enabled
  - Seek increments: 10s back/forward
  - 2.5s min buffer for fast start, 60s max buffer

---

## SECTION 8: UI COMPONENTS & ADAPTERS

### 8.1 RecyclerView Adapters
- **ChannelAdapter**: Channel list items
- **PlaylistAdapter**: Playlist list items
- **VideoGridAdapter**: Video grid items
- **HomeChannelAdapter**: Carousel channel items
- **HomePlaylistAdapter**: Carousel playlist items
- **HomeVideoAdapter**: Carousel video items
- **ContentAdapter**: Generic content list
- **DownloadsAdapter**: Download items with pause/resume/cancel/open
- **SearchResultsAdapter**: Search result items
- **SearchHistoryAdapter**: Search history with delete option
- **UpNextAdapter**: Player queue items

### 8.2 Dialog Components
- **QualitySelectionDialog** (in settings): Download quality
- **QualitySelectionDialog** (in player): Playback quality
- **LanguageSelectionDialog**: Language selection
- **Caption Selector**: Built-in player captions

### 8.3 Material Design 3 Components
- BottomNavigationView for main tabs
- AppBarLayout for screen headers
- MaterialAlertDialogBuilder for dialogs
- SwitchMaterial for toggle settings
- Chips for category display
- TabLayout with ViewPager2

### 8.4 Menu Resources
- **bottom_nav_menu.xml**: 5 bottom nav items
- **home_menu.xml**: Home screen menu (settings, language)
- **player_menu.xml**: Player menu (captions, PiP)
- **filter_menu.xml**: Content filtering options

---

## SECTION 9: CONTENT EXCLUSION & FILTERING

### 9.1 Excluded Content
- **Purpose**: Allow admins to hide content from app users
- **Types**: Can mark channels or playlists as excluded
- **Display**: 
  - Exclusion banner shown on detail screen
  - Excluded items hidden from lists
  - Count shown in player queue ("N excluded items")

### 9.2 Filter Application
- Stored in `FilterState`
- Applied to content queries
- Persisted across tab switches
- Reset via filter UI

---

## SECTION 10: BACKGROUND SERVICES & WORKERS

### 10.1 PlaybackService
- Foreground service for background audio playback
- MediaSession for media controls
- Notification with play/pause action
- Handles "Allow background playback" toggle

### 10.2 DownloadWorker
- WorkManager-based background download processor
- Respects WiFi-only settings
- Handles pause/resume queue
- Updates download status in real-time

### 10.3 File Provider
- Provides secure URI access to downloaded files
- Authority: `${applicationId}.downloads.provider`
- Used for opening downloads in external players

---

## SECTION 11: NOT YET IMPLEMENTED (Placeholders)

- Watch history persistence (UI placeholder exists)
- Bookmarks/Saved videos (UI placeholder exists)
- User accounts/signin
- Subscription management
- Notifications
- Offline sync
- Smart playlists
- Recommendation algorithm
- User preferences (theme selection shows "coming soon")

---

## SECTION 12: NAVIGATION FLOW DIAGRAM

```
Splash → Onboarding (optional)
         ↓
    Main Shell (5 tabs)
    ├─ Home
    │  ├─ Channel Detail → Videos/Playlists/Details
    │  ├─ Playlist Detail → Video list
    │  ├─ Player ← Video click or deep link
    │  ├─ Categories → Subcategories
    │  └─ Search → Search results → Detail/Player
    │
    ├─ Channels (tab)
    │  └─ Channel Detail (same as above)
    │
    ├─ Playlists (tab)
    │  └─ Playlist Detail (same as above)
    │
    ├─ Videos (tab)
    │  └─ Player (video click)
    │
    └─ Downloads/More (tab)
       ├─ Downloads list
       ├─ Saved (TODO)
       ├─ Recently Watched (TODO)
       ├─ History (TODO)
       ├─ Settings
       │  ├─ Language Dialog
       │  ├─ Theme Dialog
       │  ├─ Download Quality Dialog
       │  └─ Storage Management
       └─ About
```

---

## SECTION 13: SUMMARY STATISTICS

| Category | Count | Details |
|----------|-------|---------|
| UI Fragments | 19 | 6 main screens + 13 detail/dialog screens |
| Screens/Tabs | 5 | Home, Channels, Playlists, Videos, Downloads |
| Player Features | 13 | Quality, captions, PiP, fullscreen, cast, etc. |
| Download Features | 8 | Quality, pause/resume, storage, EULA, etc. |
| Settings Options | 7 | Locale, audio-only, background, quality, WiFi, safe mode, storage |
| Supported Languages | 3 | English, Arabic (RTL), Dutch |
| Gesture Controls | 5 | Brightness, volume, 2x seek forward/backward |
| Content Filters | 4 | Category, video length, publish date, sort order |
| RecyclerView Adapters | 11 | Various content and UI adapters |

---

## SECTION 14: KEY FILES REFERENCE

| Component | File Path |
|-----------|-----------|
| Settings | `.../preferences/SettingsPreferences.kt` |
| Downloads | `.../download/{DownloadRepository,DownloadWorker,DownloadStorage}.kt` |
| Player | `.../ui/player/PlayerFragment.kt` |
| Search | `.../ui/SearchFragment.kt` |
| Localization | `.../locale/LocaleManager.kt` |
| Stream Extraction | `.../data/extractor/NewPipeExtractorClient.kt` |
| Filtering | `.../data/filters/{FilterManager,FilterState}.kt` |
| Navigation | `.../res/navigation/{app_nav_graph,main_tabs_nav}.xml` |

