# Android App Testing Guide

> **Status:** App fully implemented with 94 Kotlin files, 19 fragments, 57 layouts. Ready for testing!
> **Last Updated:** 2025-10-30

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
