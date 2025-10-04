# Albunyaan Tube Android App

> **Status**: Phase 5 Sprint 1 & 2 Complete ✅
> **Last Updated**: 2025-10-04

Production-ready Android client for Albunyaan Tube - a family-safe Islamic educational video platform.

## Current Implementation

### Completed Screens (13 total)
- ✅ **Splash Screen** - Auto-navigation with onboarding check
- ✅ **Onboarding** - 3 swipeable pages with working indicators
- ✅ **Home** - Content sections with menu navigation
- ✅ **Channels** - Vertical list with categories FAB
- ✅ **Playlists** - Grid layout with metadata
- ✅ **Videos** - 2-column grid with thumbnails
- ✅ **Channel Detail** - Header with Videos/Playlists tabs
- ✅ **Playlist Detail** - Video list with metadata
- ✅ **Categories** - Hierarchical navigation
- ✅ **Subcategories** - Dynamic subcategory display
- ✅ **Settings** - Complete preferences with locale support
- ✅ **Downloads & Library** - Download manager with storage info
- ✅ **Player** - Modern UI with ExoPlayer integration

### Key Features
- ✅ Bottom navigation with icon-only selection (`primary_green` #35C491)
- ✅ Nested navigation with proper back button handling
- ✅ ExoPlayer video playback with NewPipe extractor
- ✅ Custom player controls with gesture support (brightness, volume, seek)
- ✅ Picture-in-Picture with dynamic aspect ratio
- ✅ Expandable video descriptions
- ✅ Share functionality
- ✅ Audio-only mode
- ✅ Landscape fullscreen player
- ✅ Multi-language support (English, Arabic, Dutch) with RTL
- ✅ Settings persistence with DataStore
- ✅ Material Design 3 components

### Architecture
- **Language**: Kotlin
- **UI**: XML layouts with Material Design 3
- **Navigation**: Android Navigation Component (nested graphs)
- **Video**: ExoPlayer with NewPipe extractor
- **Networking**: Retrofit + OkHttp
- **Image Loading**: Coil
- **Storage**: DataStore for preferences
- **DI**: Service Locator pattern

## Project Structure
```
app/src/main/
├── java/com/albunyaan/tube/
│   ├── ui/                          # UI layer (fragments, activities)
│   │   ├── home/                    # Home screen
│   │   ├── detail/                  # Channel/Playlist details
│   │   ├── categories/              # Category browsing
│   │   ├── settings/                # Settings & About
│   │   ├── download/                # Downloads management
│   │   ├── player/                  # Video player
│   │   ├── adapters/                # RecyclerView adapters
│   │   ├── MainActivity.kt          # Single-activity host
│   │   ├── MainShellFragment.kt     # Bottom nav container
│   │   ├── SplashFragment.kt        # Splash screen
│   │   └── OnboardingFragment.kt    # Onboarding flow
│   ├── data/                        # Data layer
│   │   ├── model/                   # Data models
│   │   ├── api/                     # Retrofit API clients
│   │   ├── repository/              # Repository pattern
│   │   └── extractor/               # NewPipe integration
│   ├── locale/                      # Multi-language support
│   ├── onboarding/                  # Onboarding components
│   ├── player/                      # Playback service
│   └── ServiceLocator.kt            # Dependency injection
└── res/
    ├── layout/                      # XML layouts (23+ files)
    ├── navigation/                  # Navigation graphs
    │   ├── app_nav_graph.xml        # App-level navigation
    │   └── main_tabs_nav.xml        # Bottom nav tabs
    ├── drawable/                    # Icons and graphics
    ├── values/                      # Colors, strings, dimensions
    └── values-ar/                   # Arabic translations (RTL)
```

## Building the App

### Prerequisites
- Android Studio Hedgehog (2023.1.1) or later
- JDK 17
- Android SDK 34

### Build Commands
```sh
# From android/ directory
./gradlew assembleDebug          # Build debug APK
./gradlew installDebug            # Install on connected device
./gradlew test                    # Run unit tests
./gradlew connectedAndroidTest    # Run instrumentation tests
```

### Build Variants
- **debug**: Development build with logging
- **release**: Production build (requires signing config)

## Navigation Flow
```
Splash → Onboarding (first launch) → Main Shell (Bottom Nav)
      ↘ Main Shell (returning users)

Main Shell Tabs:
├── Home → Settings | Downloads
├── Channels → Channel Detail → Categories → Subcategories
├── Playlists → Playlist Detail
└── Videos

Any Video → Player (fullscreen, PiP, gestures)
```

## Design System
- **Primary Color**: `#35C491` (Teal/Green)
- **Spacing**: 8dp baseline grid
- **Corner Radius**: 8-16dp
- **Touch Targets**: Minimum 48dp
- **Typography**: Material Design 3 type scale

## Recent Bug Fixes
- ✅ Fixed video click navigation crash (parent nav controller)
- ✅ Fixed back button to navigate to Home tab (not exit)
- ✅ Fixed green colors consistency (primary_green)
- ✅ Fixed onboarding indicator dots not updating
- ✅ Fixed icon rendering issues

## What's Next (Sprint 3)
- [ ] Search implementation
- [ ] Download manager with WorkManager
- [ ] Offline playback
- [ ] Connect to real backend API
- [ ] RTL polish and accessibility

## Documentation
- **Design Spec**: `../../docs/ux/design.md`
- **Phase Roadmap**: `../../docs/roadmap/phases.md`
- **API Docs**: `../../docs/api/openapi-draft.yaml`

---

**Version**: 1.0.0-sprint2
**Min SDK**: 24 (Android 7.0)
**Target SDK**: 34 (Android 14)
