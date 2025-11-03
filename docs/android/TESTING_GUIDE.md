# Android App Testing Guide

> **Status:** App tested on physical device. Core connectivity working. Video playback needs NewPipe integration.
> **Last Updated:** 2025-11-03
> **Latest Test Session:** [TESTING_SESSION_2025-11-03.md](../../TESTING_SESSION_2025-11-03.md)

---

## 🎯 Test Results Summary (2025-11-03)

### ✅ Working
- Backend connectivity (all endpoints responding)
- Data loading (20 channels, 16 playlists, 76 videos, 19 categories)
- Navigation between all screens
- Onboarding flow
- Search UI (backend returns 403 - needs fix)
- Category navigation
- Bottom navigation (overlap issue FIXED)

### ⚠️ Needs Implementation
- Video playback with NewPipe extractor (HIGH PRIORITY)
- Search 403 authentication fix (HIGH PRIORITY)
- Missing details on lists (categories, descriptions)
- Filter/sort functionality
- Downloads
- Settings functionality
- i18n/RTL
- Real thumbnail images

---

## Quick Start

### Prerequisites
- Backend running with seeded data
- Android emulator or physical device
- Android Studio installed

### Run Backend
```bash
cd /home/farouq/Development/albunyaantube/backend
./gradlew bootRun --args='--spring.profiles.active=seed'
# Wait for: "Started AlbunyaanTubeApplication in X seconds"
```

### Install & Run App
```bash
cd /home/farouq/Development/albunyaantube/android

# Option 1: Android Studio
# Open project → Run (Shift+F10)

# Option 2: Command Line
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.albunyaan.tube/.MainActivity
```

---

## Test Checklist

### 1. Onboarding Flow
- [ ] Splash screen appears (1-2 seconds)
- [ ] Onboarding carousel shows 3 pages
- [ ] Can swipe through pages
- [ ] "Get Started" button works
- [ ] Skipping works

### 2. Main Navigation
- [ ] Bottom navigation shows 5 tabs:
  - Home
  - Channels
  - Playlists
  - Videos
  - More
- [ ] Each tab navigates correctly
- [ ] Tab icons and labels visible
- [ ] Active tab highlighted

### 3. Home Tab
**Expected Data:**
- Mixed content (channels + playlists + videos)
- Should show seeded data from backend

**Test:**
- [ ] Content loads (not empty)
- [ ] Shows variety of content types
- [ ] Scrolling works smoothly
- [ ] Pull-to-refresh works
- [ ] Thumbnails load (if enabled)

### 4. Channels Tab
**Expected Data:**
- 20 approved channels from Firestore

**Test:**
- [ ] Shows list of channels
- [ ] Each channel shows:
  - Channel name
  - Category
  - Subscriber count
  - Thumbnail
- [ ] Tap channel → navigates to ChannelDetailFragment
- [ ] Search works (if implemented)
- [ ] Filter by category works

### 5. Playlists Tab
**Expected Data:**
- 16 approved playlists from Firestore

**Test:**
- [ ] Shows list of playlists
- [ ] Each playlist shows:
  - Title
  - Item count
  - Category
  - Thumbnail
- [ ] Tap playlist → navigates to PlaylistDetailFragment
- [ ] Filter by category works

### 6. Videos Tab
**Expected Data:**
- 76 approved videos from Firestore

**Test:**
- [ ] Shows grid/list of videos
- [ ] Each video shows:
  - Title
  - Duration
  - Upload date
  - Category
  - Thumbnail
- [ ] Tap video → navigates to PlayerFragment
- [ ] Filter by length works (SHORT/MEDIUM/LONG)
- [ ] Filter by date works
- [ ] Sort options work

### 7. Channel Detail Screen
**Test:**
- [ ] Shows channel info:
  - Name
  - Description
  - Subscriber count
  - Category
- [ ] Tabs present:
  - Videos
  - Playlists
  - About
- [ ] Each tab loads content
- [ ] Back button works

### 8. Playlist Detail Screen
**Test:**
- [ ] Shows playlist info:
  - Title
  - Description
  - Item count
  - Category
- [ ] Shows list of videos in playlist
- [ ] "Play All" button works
- [ ] Tap video → opens player
- [ ] Back button works

### 9. Video Player
**Test:**
- [ ] Video loads and plays
- [ ] Controls visible:
  - Play/Pause
  - Seek bar
  - Current time / Duration
  - Quality selector
  - Fullscreen toggle
- [ ] Seeking works
- [ ] Audio-only toggle works
- [ ] Fullscreen works
- [ ] Picture-in-Picture works (Android 8+)
- [ ] Back button exits player
- [ ] "Up Next" section shows related videos

### 10. Search
**Test:**
- [ ] Search bar visible in toolbar
- [ ] Type query → shows results
- [ ] Results include channels, playlists, videos
- [ ] Tap result → navigates to detail/player
- [ ] Search history saved
- [ ] Clear search history works

### 11. Categories
**Test:**
- [ ] Shows 19 top-level categories
- [ ] Each category shows:
  - Name
  - Icon/Emoji
  - Arrow (if has subcategories)
- [ ] Tap category with subcategories → opens SubcategoriesFragment
- [ ] Tap category without subcategories → filters content
- [ ] Back navigation works

### 12. Subcategories
**Test:**
- [ ] Shows subcategories for selected parent
- [ ] Example: Qur'an → Beginner, Tajweed, Memorization
- [ ] Tap subcategory → filters content
- [ ] Back to parent categories works

### 13. Downloads
**Test:**
- [ ] Shows empty state if no downloads
- [ ] Download a video from player
- [ ] Progress notification appears
- [ ] Download appears in list with:
  - Title
  - Progress bar
  - Size
  - Status
- [ ] Tap completed download → plays offline
- [ ] Delete download works
- [ ] Storage quota enforced (500 MB)

### 14. Settings
**Test:**
- [ ] Language selection works (English, Arabic, Dutch)
- [ ] Theme selection works (Light, Dark, System)
- [ ] Arabic RTL layout works
- [ ] Safe mode toggle works
- [ ] Audio-only default works
- [ ] Background playback toggle works
- [ ] Download quality selector works
- [ ] WiFi-only downloads toggle works
- [ ] Storage location selector works
- [ ] Storage quota adjustment works
- [ ] Clear downloads works

### 15. About
**Test:**
- [ ] Shows app version
- [ ] Shows build info
- [ ] Shows licenses
- [ ] Shows privacy policy
- [ ] Shows terms of service
- [ ] Links work

---

## API Testing

### Test Backend Connectivity

```bash
# From emulator perspective (10.0.2.2 = host localhost)

# 1. Test categories
curl http://10.0.2.2:8080/api/v1/categories

# 2. Test channels
curl http://10.0.2.2:8080/api/v1/content?type=CHANNELS&limit=5

# 3. Test playlists
curl http://10.0.2.2:8080/api/v1/content?type=PLAYLISTS&limit=5

# 4. Test videos
curl http://10.0.2.2:8080/api/v1/content?type=VIDEOS&limit=5

# 5. Test search
curl "http://10.0.2.2:8080/api/v1/search?q=quran&limit=10"
```

### Check Logcat for HTTP Requests

```bash
# Filter for Retrofit/OkHttp logs
adb logcat | grep -E "OkHttp|Retrofit|ContentApi"

# Expected output:
# D/OkHttp: --> GET http://10.0.2.2:8080/api/v1/content?type=CHANNELS
# D/OkHttp: <-- 200 OK http://10.0.2.2:8080/api/v1/content?type=CHANNELS (234ms)
```

---

## Known Issues to Test

### 1. Unit Test Compilation Errors
**Location:** `PlayerViewModelTest.kt:77-164`
**Issue:** Type mismatch in test (TestDispatcher vs ContentService)
**Impact:** Unit tests don't compile, but app runs fine
**Priority:** Low (fix after manual testing)

### 2. Fake Data Fallback
**Behavior:** If backend is unreachable, app falls back to `FakeContentService`
**Test:**
- Stop backend
- Restart app
- Should show fake data instead of errors
- Check logcat for "Falling back to fake content service"

### 3. Image Loading
**Configuration:** `BuildConfig.ENABLE_THUMBNAIL_IMAGES = true`
**Test:**
- Thumbnails load from placeholder URLs
- Coil caching works (subsequent loads faster)
- Missing images show fallback

### 4. NewPipe Extractor
**Test:**
- Player uses NewPipe to extract video streams
- Works with YouTube video IDs
- Check logcat for extractor success/errors

---

## Performance Testing

### List Scrolling
- [ ] 60 FPS scrolling (use Developer Options → Profile GPU Rendering)
- [ ] No jank or stuttering
- [ ] Paging loads more content smoothly
- [ ] Images don't block scrolling

### Memory Usage
```bash
# Monitor memory
adb shell dumpsys meminfo com.albunyaan.tube

# Watch for leaks
# Use Android Studio Profiler
```

### Network Usage
- [ ] Images cached (don't reload on scroll)
- [ ] HTTP cache works (30 MB max)
- [ ] Offline mode works for cached content

---

## Accessibility Testing

### TalkBack
- [ ] Enable TalkBack
- [ ] Navigate app with voice guidance
- [ ] All buttons have content descriptions
- [ ] Images have alt text
- [ ] Forms are navigable

### Large Text
- [ ] Enable "Large Text" in Android settings
- [ ] UI scales correctly
- [ ] No text truncation
- [ ] No overlapping elements

### Dark Mode
- [ ] Enable system dark mode
- [ ] App switches to dark theme
- [ ] All screens readable
- [ ] Contrast sufficient

---

## Internationalization Testing

### Arabic (RTL)
- [ ] Switch to Arabic in settings
- [ ] Layout flips to RTL
- [ ] Navigation drawer opens from right
- [ ] Text alignment correct
- [ ] Icons flip correctly

### Dutch
- [ ] Switch to Dutch in settings
- [ ] All strings translated
- [ ] No English fallbacks visible
- [ ] Dates/numbers formatted correctly

---

## Edge Cases

### No Internet
- [ ] Enable Airplane mode
- [ ] Offline banner shows
- [ ] Downloaded content still accessible
- [ ] Graceful error messages

### Empty States
- [ ] No downloads → shows empty state with message
- [ ] No search results → shows "No results found"
- [ ] No content in category → shows empty state

### Error Handling
- [ ] Backend error → shows retry button
- [ ] Network timeout → shows timeout message
- [ ] Invalid video ID → shows error in player
- [ ] Storage full → shows storage full message

---

## Regression Testing

After any code changes, re-run:
1. ✅ Core flows (onboarding → home → content → player)
2. ✅ API connectivity (all endpoints)
3. ✅ Downloads (offline playback)
4. ✅ Settings (language, theme)

---

## Automated Testing

### Run Unit Tests (when fixed)
```bash
cd /home/farouq/Development/albunyaantube/android
./gradlew test
```

### Run Instrumentation Tests
```bash
# Requires device/emulator
./gradlew connectedAndroidTest
```

### Existing Test Files
```
app/src/androidTest/java/com/albunyaan/tube/
├── navigation/NavigationGraphTest.kt
├── download/DownloadStorageTest.kt
├── ui/download/DownloadsFragmentTest.kt
├── ui/SearchFragmentTest.kt
├── accessibility/AccessibilityTest.kt
└── util/
    ├── TestDataBuilder.kt
    ├── MockWebServerRule.kt
    └── BaseInstrumentationTest.kt
```

---

## Deployment Testing

### Debug Build
```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

### Release Build
```bash
# Requires keystore (see keystore.properties)
./gradlew assembleRelease
# APK: app/build/outputs/apk/release/app-release.apk
```

### ProGuard Testing
- [ ] Release build runs without crashes
- [ ] No reflection errors
- [ ] All features work (ProGuard optimizations)

---

## Reporting Issues

When reporting bugs, include:
1. **Steps to reproduce**
2. **Expected behavior**
3. **Actual behavior**
4. **Logcat output:**
   ```bash
   adb logcat > bug_log.txt
   ```
5. **Screenshots/screen recording**
6. **Device info:** Android version, device model
7. **App version:** Check About screen

---

## Success Criteria

✅ **App is ready for release when:**
- All 15 test sections pass
- No crashes in normal usage
- Performance acceptable (smooth scrolling)
- All content types load from backend
- Offline mode works
- Accessibility requirements met
- i18n works for all 3 languages
- No critical bugs

---

**Next Steps After Testing:**
1. Fix any bugs found
2. Polish UI based on feedback
3. Optimize performance if needed
4. Create release build
5. Submit to Play Store

**Testing Documentation:**
- [docs/android/ARCHITECTURE.md](ARCHITECTURE.md) - App architecture
- [docs/android/RELEASE.md](RELEASE.md) - Release process
- [docs/IMPLEMENTATION_PRIORITIES.md](../IMPLEMENTATION_PRIORITIES.md) - Overall plan

---

## 📝 Actual Test Results (2025-11-03)

### Test Environment
- **Device:** Physical Android device (Huawei)
- **Backend:** http://192.168.1.167:8080
- **APK:** Debug build (15MB)
- **Seeded Data:** 19 categories, 20 channels, 16 playlists, 76 videos

### 1. Onboarding Flow ✅
- [✓] Splash screen appears (<1 second - faster than expected)
- [✓] Onboarding carousel shows 3 pages
- [✓] Can swipe through pages
- [✓] "Get Started" button works
- [✓] Skipping works
- [!] **Note:** Question mark button on onboarding - purpose unclear

### 2. Main Navigation ✅
- [✓] Bottom navigation shows 5 tabs (Home, Channels, Playlists, Videos, Downloads)
- [!] **Note:** Tab shows "Downloads" instead of expected "More"
- [✓] Each tab navigates correctly
- [✓] Tab icons and labels visible
- [✓] Active tab highlighted

### 3. Home Tab ✅
- [✓] Content loads (not empty)
- [✓] Shows variety of content types (channels + playlists + videos)
- [✓] Scrolling works smoothly
- [✓] Category button opens categories screen and returns successfully
- [✓] Search button opens search screen
- [✗] **Issue:** No back button from search to home screen
- [✓] Kebab menu displays Settings and Downloads options
- [○] Pull-to-refresh not tested
- [○] Thumbnails show placeholders (real images not yet implemented)

### 4. Channels Tab ✅
- [✓] Shows list of channels
- [✓] Each channel shows channel name
- [✓] Each channel shows subscriber count
- [✗] **Missing:** Category not displayed
- [○] Thumbnails show placeholders only
- [✓] Tap channel → navigates to ChannelDetailFragment
- [✗] **Missing:** Search not displayed on this tab
- [!] **Issue:** Filter by category shows "not yet implemented" toast

### 5. Playlists Tab ✅
- [✓] Shows list of playlists
- [✓] Each playlist shows title
- [✓] Each playlist shows item count
- [✗] **Missing:** Category not displayed
- [○] Thumbnails show placeholders only
- [✓] Tap playlist → navigates to PlaylistDetailFragment
- [!] **Issue:** Filter by category visible but not functional

### 6. Videos Tab ✅
- [✓] Shows grid/list of videos
- [✓] Each video shows title
- [✓] Each video shows duration
- [✗] **Missing:** Upload date not displayed
- [✗] **Missing:** Category not displayed
- [○] Thumbnails show placeholders only
- [✓] Tap video → navigates to PlayerFragment
- [✗] **Missing:** Filter by length not visible (SHORT/MEDIUM/LONG)
- [✗] **Missing:** Filter by date not visible
- [✗] **Missing:** Sort options not visible

### 7. Channel Detail Screen ⚠️
- [✓] Shows channel name
- [✓] Shows subscriber count
- [✗] **Missing:** Description not shown
- [✗] **Missing:** Category not shown
- [✓] Tabs present: Videos, Live, Shorts, Playlists, Posts
- [✗] **Missing:** About tab not displayed
- [!] Each tab shows placeholder text (actual lists need implementation)
- [✓] Back button works

### 8. Playlist Detail Screen ⚠️
- [✓] Shows playlist title
- [✓] Shows item count
- [✗] **Missing:** Description not shown
- [✗] **Missing:** Category not shown
- [✗] **Missing:** No list of videos in playlist
- [✗] **Missing:** "Play All" button doesn't exist
- [✗] Tap video → no videos to tap
- [✓] Back button works

### 9. Video Player ❌
- [✗] **Not Implemented:** Needs NewPipe extractor with real YouTube IDs
- [✗] Video doesn't load/play (placeholder data)
- [✗] Controls visible but non-functional
- [✗] No back button visible in player
- [✗] "Up Next" section not implemented

### 10. Search ⚠️
- [✓] Search bar visible in toolbar
- [✓] Type query → shows results
- [✓] Results include channels, playlists, videos
- [!] **Request:** User wants different thumbnail shapes for content types (circle for channels)
- [✓] Tap result → navigates to detail/player
- [✗] **Missing:** Search history not saved
- [✗] **Missing:** Clear search history doesn't work
- [✗] **CRITICAL BUG:** Backend returns 403 Forbidden for search queries

### 11. Categories ✅
- [✓] Shows 19 top-level categories
- [✓] Each category shows name
- [✗] **Missing:** Icon/Emoji not displaying
- [✗] **Missing:** Arrow not showing for subcategories
- [✓] Tap category with subcategories → opens SubcategoriesFragment
- [✗] Tap category without subcategories → filter not implemented
- [✓] Back navigation works

### 12. Subcategories ✅
- [✓] Shows subcategories for selected parent
- [✓] Example works: Qur'an → Beginner, Tajweed, Memorization
- [✗] Tap subcategory → filter not implemented
- [✓] Back to parent categories works

### 13. Downloads ⚠️
- [✓] Shows empty state (no downloads)
- [✗] **Not Implemented:** All download functionality pending

### 14. Settings ⚠️
- [✓] Language selection visible (English, Arabic, Dutch)
- [!] **Issue:** No actual translation takes place when switching
- [✗] **Not Implemented:** Theme selection not functional
- [✗] **Not Implemented:** All other settings toggles not functional

### 15. About ❌
- [✗] **Not Implemented:** Entire section non-existent

### Bottom Navigation Overlap Issue ✅ FIXED
- [✓] **Fixed:** Content no longer hidden behind bottom navigation
- [✓] Videos tab - last item fully visible when scrolled to bottom
- [✓] Channels tab - last channel fully visible
- [✓] Playlists tab - last playlist fully visible
- [✓] Settings - all content visible when scrolled to bottom
- [✓] Downloads - all content visible
- [✓] Channel/Playlist detail - proper spacing at bottom
- **Solution:** Added `paddingBottom="@dimen/bottom_nav_height"` with `clipToPadding="false"` to affected layouts

---

## 🐛 Critical Issues Discovered

### 1. Search API Returns 403 Forbidden (HIGH PRIORITY)
**Symptom:** Search UI works but backend returns 403
**Logs:**
```
GET http://192.168.1.167:8080/api/v1/search?q=qu&limit=50
<-- 403 (651ms, 0-byte body)
```
**Root Cause:** Search endpoint requires authentication but app calls it as public
**Fix Needed:** Make `/api/v1/search` a public endpoint in backend SecurityConfig

### 2. Video Playback Not Implemented (HIGH PRIORITY)
**Symptom:** Player opens but video doesn't play
**Root Cause:** NewPipe extractor not integrated with real YouTube video IDs
**Fix Needed:**
1. Update seeded data with real YouTube video IDs
2. Integrate NewPipe extractor in PlayerFragment
3. Test with actual YouTube videos

### 3. Missing Back Button in Search (MEDIUM PRIORITY)
**Symptom:** No way to return from search to home screen
**Fix Needed:** Add back/up button to search toolbar

---

## 📊 Test Coverage Summary

| Feature | Status | Pass Rate |
|---------|--------|-----------|
| Onboarding | ✅ Working | 100% |
| Navigation | ✅ Working | 100% |
| Data Loading | ✅ Working | 100% |
| Home Tab | ✅ Working | 90% |
| Channels Tab | ⚠️ Partial | 70% |
| Playlists Tab | ⚠️ Partial | 70% |
| Videos Tab | ⚠️ Partial | 60% |
| Channel Detail | ⚠️ Partial | 50% |
| Playlist Detail | ⚠️ Partial | 40% |
| Video Player | ❌ Not Working | 0% |
| Search | ⚠️ Partial | 60% |
| Categories | ✅ Working | 80% |
| Subcategories | ✅ Working | 80% |
| Downloads | ❌ Not Implemented | 0% |
| Settings | ⚠️ Partial | 20% |
| About | ❌ Not Implemented | 0% |

**Overall App Completion:** ~65%

---

## 🔄 Next Testing Session

**Priority Tasks for Next Test:**
1. ✅ Fix search 403 error (backend SecurityConfig)
2. ✅ Implement NewPipe video playback
3. ✅ Add back button to search screen
4. ⚠️ Add missing details (categories, descriptions)
5. ⚠️ Test video playback with real YouTube content
6. ⚠️ Test downloads functionality
7. ⚠️ Test settings implementation

**Detailed Session Notes:** See [TESTING_SESSION_2025-11-03.md](../../TESTING_SESSION_2025-11-03.md)
