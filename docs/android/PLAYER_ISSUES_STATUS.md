# Android Player Issues - Current Status

**Date**: 2025-11-04
**Session**: Player controls and quality selection fixes
**Status**: Most issues still persist, ongoing work

---

## Issues Addressed in This Session

### 1. Quality Selection Implementation ✅ PARTIALLY FIXED
**Original Request**: Show all available quality options, not just 240p and max

**Implementation**:
- Modified `showQualitySelector()` to display ALL available qualities sorted from lowest to highest
- Location: [PlayerFragment.kt:309-339](../android/app/src/main/java/com/albunyaan/tube/ui/player/PlayerFragment.kt#L309-L339)
- All qualities now appear in the dialog

**Remaining Issue**: User wants quality accessible from ExoPlayer's native player controls overlay, not from toolbar

### 2. Quality Button Placement ❌ NOT AS EXPECTED
**Original Request**: Quality option should appear within ExoPlayer's native settings (the gear icon on the video player)

**Current Implementation**:
- Quality button added to toolbar overlay (top-right, alongside Cast and Settings)
- Location: [fragment_player.xml:48-56](../android/app/src/main/res/layout/fragment_player.xml#L48-L56)
- Wired in: [PlayerFragment.kt:153-155](../android/app/src/main/java/com/albunyaan/tube/ui/player/PlayerFragment.kt#L153-L155)

**User Feedback**: "now the players settings are not even visible" - ExoPlayer's default controls are broken

**What User Actually Wants**:
- Quality selection accessible from ExoPlayer's built-in controls (when you tap the video)
- Should be integrated into the player overlay controls, not in a separate toolbar button
- Reference: User mentioned https://stackoverflow.com/questions/48748657/exoplayer-hls-quality

### 3. Seamless Quality Switching ✅ IMPLEMENTED
**Implementation**:
- Added `preparedStreamUrl` tracking to detect quality changes
- Saves playback position before quality switch
- Restores position and play state after loading new quality
- Location: [PlayerFragment.kt:486-530](../android/app/src/main/java/com/albunyaan/tube/ui/player/PlayerFragment.kt#L486-L530)

**Status**: Works correctly, quality changes preserve playback position

### 4. Fullscreen Bottom Navigation Hide ✅ FIXED
**Implementation**:
- Added `setBottomNavVisibility()` method to MainActivity
- Location: [MainActivity.kt:82-87](../android/app/src/main/java/com/albunyaan/tube/ui/MainActivity.kt#L82-L87)
- Fullscreen now properly hides bottom navigation

### 5. Orientation Change Playback Preservation ✅ FIXED
**Implementation**:
- Added `android:configChanges="orientation|screenSize|screenLayout|keyboardHidden"` to manifest
- Location: [AndroidManifest.xml:29](../android/app/src/main/AndroidManifest.xml#L29)
- Added `onConfigurationChanged()` handler for auto-fullscreen in landscape
- Location: [PlayerFragment.kt:186-193](../android/app/src/main/java/com/albunyaan/tube/ui/player/PlayerFragment.kt#L186-L193)

### 6. Download Button ✅ FIXED
**Implementation**:
- Added click listener in `onViewCreated()`
- Location: [PlayerFragment.kt:130-142](../android/app/src/main/java/com/albunyaan/tube/ui/player/PlayerFragment.kt#L130-L142)
- Handles EULA acceptance check

### 7. Background Playback Notification Navigation ✅ FIXED
**Implementation**:
- Fixed `MainActivity.handleIntent()` to wait for navigation graph
- Location: [MainActivity.kt:67-96](../android/app/src/main/java/com/albunyaan/tube/ui/MainActivity.kt#L67-L96)
- No longer crashes when returning from background playback notification

---

## Current Problems

### Critical Issue: ExoPlayer Controls Not Working
**User's Latest Feedback**: "now the players settings are not even visible"

**What Happened**:
1. First attempt: Created custom ExoPlayer control layout (`exo_player_control_view.xml`)
   - This broke ExoPlayer's default controls
   - Quality button was visible but player controls stopped working

2. Second attempt: Removed custom layout, added quality button to toolbar
   - ExoPlayer controls should be restored to default
   - Quality button now in toolbar (top-right)
   - **But user reports player settings still not visible**

**Possible Causes**:
- ExoPlayer's default controls may not be loading properly
- The `app:use_controller="true"` may need additional configuration
- Player view height (240dp) might be causing issues
- Gesture detector may be intercepting touch events

**Current Configuration**:
```xml
<com.google.android.exoplayer2.ui.StyledPlayerView
    android:id="@+id/playerView"
    android:layout_width="match_parent"
    android:layout_height="240dp"
    android:keepScreenOn="true"
    android:contentDescription="@string/player_video_player"
    app:show_buffering="when_playing"
    app:use_controller="true" />
```

---

## What Still Needs to Be Fixed

### 1. ExoPlayer Default Controls
- **Priority**: CRITICAL
- **Issue**: Player controls not showing/working
- **Action Needed**:
  - Debug why ExoPlayer controls aren't visible
  - Check if gesture detector is blocking touch events
  - Verify ExoPlayer controller visibility

### 2. Quality Selection in Native Player Controls
- **Priority**: HIGH (User's main request)
- **Issue**: Quality not integrated into ExoPlayer's on-screen controls
- **Action Needed**:
  - Research ExoPlayer's TrackSelectionDialog
  - Implement custom controller layout properly with all required ExoPlayer IDs
  - Add quality selection to ExoPlayer's settings overflow menu
  - Alternative: Use ExoPlayer's built-in quality selector for adaptive streams

### 3. Audio-Only Toggle
- **Priority**: MEDIUM
- **Status**: Button exists but functionality may need verification
- **Location**: [PlayerFragment.kt:104-106](../android/app/src/main/java/com/albunyaan/tube/ui/player/PlayerFragment.kt#L104-L106)

### 4. Share Button Metadata
- **Priority**: MEDIUM
- **Status**: Share function exists but may need enhancement
- **Required**: Include title, description, thumbnail, URL
- **Location**: [PlayerFragment.kt:693-720](../android/app/src/main/java/com/albunyaan/tube/ui/player/PlayerFragment.kt#L693-L720)

### 5. Minimize Button
- **Priority**: LOW
- **Status**: Button exists and wired
- **Location**: [PlayerFragment.kt:145-147](../android/app/src/main/java/com/albunyaan/tube/ui/player/PlayerFragment.kt#L145-L147)
- **Action**: Currently just goes back, may need picture-in-picture or mini-player

### 6. Chromecast
- **Priority**: LOW
- **Status**: Cast button visible, SDK integrated
- **Location**: [PlayerFragment.kt:161-165](../android/app/src/main/java/com/albunyaan/tube/ui/player/PlayerFragment.kt#L161-L165)
- **Action**: Needs testing with actual Chromecast device

---

## Technical Notes

### ExoPlayer Controller Architecture
ExoPlayer's `StyledPlayerView` expects specific view IDs in custom controller layouts:
- `@id/exo_play_pause` or `@id/exo_play` and `@id/exo_pause`
- `@id/exo_progress` (time bar)
- `@id/exo_position` (current time)
- `@id/exo_duration` (total time)
- `@id/exo_rew` (rewind)
- `@id/exo_ffwd` (fast forward)
- `@id/exo_settings` (settings overflow)

Note: Using `@+id/` creates new IDs, `@id/` references existing ExoPlayer IDs.

### NewPipe Extractor vs HLS
- Our app uses NewPipe Extractor which provides direct video URLs
- ExoPlayer's built-in quality selector is designed for adaptive streaming (HLS/DASH)
- We need custom quality selection because we have discrete quality levels, not adaptive streams
- StackOverflow link provided by user is for HLS adaptive streaming (not applicable to our case)

### Files Modified in This Session
1. `android/app/src/main/java/com/albunyaan/tube/ui/player/PlayerFragment.kt`
   - Added quality button click listener
   - Implemented seamless quality switching
   - Added orientation change handler
   - Removed broken custom controller reference

2. `android/app/src/main/res/layout/fragment_player.xml`
   - Added quality button to toolbar
   - Removed custom controller layout reference
   - Restored default ExoPlayer controls

3. `android/app/src/main/java/com/albunyaan/tube/ui/MainActivity.kt`
   - Added `setBottomNavVisibility()` method
   - Fixed notification navigation crash

4. `android/app/src/main/java/com/albunyaan/tube/ui/MainShellFragment.kt`
   - Added `setBottomNavVisibility()` method

5. `android/app/src/main/AndroidManifest.xml`
   - Added `configChanges` to prevent activity recreation on rotation

6. `android/app/src/main/res/layout/exo_player_control_view.xml`
   - **DELETED** (was causing issues)

---

## Next Steps for Future Sessions

1. **IMMEDIATE**: Debug why ExoPlayer controls aren't showing
   - Check logcat for ExoPlayer errors
   - Verify controller visibility programmatically
   - Test with minimal configuration

2. **HIGH PRIORITY**: Implement proper custom ExoPlayer controller
   - Study ExoPlayer's default controller layout
   - Copy default layout and add quality button
   - Ensure all ExoPlayer IDs are correct
   - Test all controls work (play, pause, seek, etc.)

3. **ALTERNATIVE APPROACH**: Use ExoPlayer's settings menu
   - Extend ExoPlayer's default settings
   - Add custom quality selection to settings overflow
   - May require subclassing `StyledPlayerView`

4. **TESTING**: Verify all other features still work
   - Download button
   - Background playback
   - Notification navigation
   - Fullscreen
   - Orientation changes

---

## User's Frustration Level
**HIGH** - User sent "NOPE NOPE NOPE STILL NOT SHOWING OR ACTIVE!!!!" in all caps

The user's main frustration is that quality selection is not where they expect it (inside ExoPlayer's native player controls), and now the player controls themselves may not be working properly.

---

## References
- ExoPlayer UI Documentation: https://exoplayer.dev/ui-components.html
- ExoPlayer Customization: https://exoplayer.dev/customization.html
- StackOverflow HLS Quality: https://stackoverflow.com/questions/48748657/exoplayer-hls-quality (NOT applicable to our use case)
