# ExoPlayer Quality Selection Implementation

**Date**: 2025-11-04
**Status**: Implemented - Ready for testing

## Overview

Implemented proper quality selection for the Android player by converting NewPipe's discrete quality URLs into a multi-track MediaSource that ExoPlayer can present in its native settings menu.

---

## The Problem

### Previous Implementation
- Quality selection was in a custom toolbar button (top-right overlay)
- User had to tap a separate quality button outside the player controls
- Not integrated with ExoPlayer's native UI
- User feedback: "Quality should be in ExoPlayer's settings gear icon"

### Core Issue
- **NewPipe Extractor provides discrete URLs** - each quality (360p, 720p, 1080p) has a separate video file URL
- **ExoPlayer was fed one URL at a time** - player only knew about the currently selected quality
- **ExoPlayer's settings menu only shows tracks from the current MediaSource** - since we loaded single progressive streams, there were no alternative tracks to display

---

## The Solution

### Architecture Change

**Before:**
```kotlin
// Old approach - single URL MediaItem
val mediaItem = MediaItem.Builder()
    .setUri(singleQualityUrl)  // Only ONE quality visible to ExoPlayer
    .setMimeType(mimeType)
    .build()
player.setMediaItem(mediaItem)
```

**After:**
```kotlin
// New approach - multi-quality MediaSource
val mediaSource = MultiQualityMediaSourceFactory(context)
    .createMediaSource(
        resolved = allQualitiesFromNewPipe,
        audioOnly = false,
        selectedQuality = currentQuality
    )
player.setMediaSource(mediaSource)  // ExoPlayer sees ALL qualities
```

### Implementation Components

#### 1. MultiQualityMediaSourceFactory.kt
**Location**: `android/app/src/main/java/com/albunyaan/tube/player/MultiQualityMediaSourceFactory.kt`

**Purpose**: Converts NewPipe's discrete quality URLs into ExoPlayer's multi-track format

**How it works**:
- Takes `ResolvedStreams` (all qualities from NewPipe)
- Creates separate `ProgressiveMediaSource` for each quality level
- Merges video + audio tracks using `MergingMediaSource`
- Attaches `QualityTag` metadata to each MediaItem for identification

**Key methods**:
- `createMediaSource()` - Main entry point, handles audio-only vs video mode
- `createVideoMediaSource()` - Creates MediaSource for specific quality with merged audio
- `createAudioOnlySource()` - Creates audio-only MediaSource

#### 2. QualityTrackSelector.kt
**Location**: `android/app/src/main/java/com/albunyaan/tube/player/QualityTrackSelector.kt`

**Purpose**: Custom TrackSelector that properly labels and selects quality tracks

**Features**:
- Extends `DefaultTrackSelector` with quality-specific configuration
- Provides `selectQuality(height)` method for programmatic quality changes
- Configures adaptive track selection parameters
- Generates human-readable quality labels ("720p", "1080p", etc.)

**Configuration**:
```kotlin
parameters = buildUponParameters()
    .setAllowVideoMixedMimeTypeAdaptiveness(true)
    .setAllowVideoNonSeamlessAdaptiveness(true)
    .setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE)
    .setForceHighestSupportedBitrate(false)
    .build()
```

#### 3. PlayerFragment.kt Updates
**Changes**:
1. **Player initialization** - Uses `QualityTrackSelector` instead of default
2. **Stream preparation** - Uses `MediaSourceFactory` instead of single MediaItem
3. **Removed custom quality UI** - Deleted toolbar quality button, `showQualitySelector()` method
4. **Menu cleanup** - Removed quality menu item (R.id.action_quality)

**Code changes**:
```kotlin
// Setup player with custom track selector
private fun setupPlayer(binding: FragmentPlayerBinding) {
    val trackSelector = QualityTrackSelector.createForDiscreteQualities(requireContext())

    val player = ExoPlayer.Builder(requireContext())
        .setTrackSelector(trackSelector)  // Enable quality selection
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .build().also { this.player = it }

    binding.playerView.player = player
    player.addListener(viewModel.playerListener)
}

// Prepare stream with multi-quality MediaSource
private fun maybePrepareStream(state: PlayerState) {
    // ... position/state management ...

    val mediaSource = mediaSourceFactory.createMediaSource(
        resolved = streamState.selection.resolved,
        audioOnly = state.audioOnly,
        selectedQuality = streamState.selection.video
    )

    player?.setMediaSource(mediaSource)
    player?.prepare()
}
```

#### 4. Layout Changes
**File**: `fragment_player.xml`

**Removed**:
- Quality button from toolbar overlay (lines 48-56)
- Settings button from toolbar overlay (lines 68-76)

**Kept**:
- Chromecast button (still useful for casting)
- Fullscreen button (bottom-right)
- Minimize button (top-left)

**ExoPlayer configuration**:
```xml
<com.google.android.exoplayer2.ui.StyledPlayerView
    app:show_subtitle_button="true"
    app:show_vr_button="false"
    app:show_shuffle_button="false"
    app:controller_layout_click_listener="true" />
```

---

## How It Works Now

### User Experience

1. **User plays a video** → PlayerFragment loads video metadata
2. **NewPipe resolves streams** → Extracts all available qualities (360p, 480p, 720p, 1080p, etc.)
3. **MediaSourceFactory creates multi-track source** → All qualities packaged into one MediaSource
4. **ExoPlayer prepares** → Player sees multiple video tracks
5. **User taps video** → ExoPlayer controls appear
6. **User taps settings gear icon** → Settings menu shows quality options
7. **User selects desired quality** → ExoPlayer switches tracks seamlessly

### Technical Flow

```
NewPipe Extractor
    ↓ (resolves all qualities)
ResolvedStreams {
    videoTracks: [
        VideoTrack(url="...", height=360, qualityLabel="360p"),
        VideoTrack(url="...", height=720, qualityLabel="720p"),
        VideoTrack(url="...", height=1080, qualityLabel="1080p")
    ],
    audioTracks: [...]
}
    ↓ (converted by)
MultiQualityMediaSourceFactory
    ↓ (creates)
MergingMediaSource [
    ProgressiveMediaSource(360p video + audio),
    ProgressiveMediaSource(720p video + audio),
    ProgressiveMediaSource(1080p video + audio)
]
    ↓ (fed to)
ExoPlayer with QualityTrackSelector
    ↓ (presents in)
ExoPlayer Settings Menu → Quality Selection → [360p, 720p, 1080p]
```

---

## Files Modified

### New Files
1. `android/app/src/main/java/com/albunyaan/tube/player/MultiQualityMediaSourceFactory.kt` - MediaSource factory
2. `android/app/src/main/java/com/albunyaan/tube/player/QualityTrackSelector.kt` - Custom track selector

### Modified Files
3. `android/app/src/main/java/com/albunyaan/tube/ui/player/PlayerFragment.kt`
   - Added `mediaSourceFactory` lazy property
   - Updated `setupPlayer()` to use `QualityTrackSelector`
   - Updated `maybePrepareStream()` to use `MediaSource` instead of `MediaItem`
   - Removed `showQualitySelector()` method
   - Removed `showPlayerSettingsMenu()` method
   - Removed quality button click listener

4. `android/app/src/main/res/layout/fragment_player.xml`
   - Removed quality button (ImageButton with id `qualityButton`)
   - Removed settings button (ImageButton with id `settingsButton`)
   - Added ExoPlayer attributes: `show_subtitle_button`, `show_vr_button`, `show_shuffle_button`

5. `android/app/src/main/res/menu/player_menu.xml`
   - Removed `action_quality` menu item

---

## Testing Checklist

### Basic Playback
- [ ] Video loads and plays correctly
- [ ] Audio-only mode works
- [ ] Seamless switching between audio/video modes

### Quality Selection
- [ ] Tap video → ExoPlayer controls appear
- [ ] Tap settings gear icon → Settings menu appears
- [ ] Settings menu shows "Quality" option
- [ ] Tapping Quality shows all available qualities (360p, 480p, 720p, 1080p, etc.)
- [ ] Selecting a quality switches seamlessly without restarting video
- [ ] Playback position preserved during quality change
- [ ] Selected quality persists across app restarts (if implemented)

### Player Controls
- [ ] Play/pause works
- [ ] Seek bar works
- [ ] Fullscreen button works
- [ ] Minimize button works
- [ ] Chromecast button appears (if Cast device available)
- [ ] Caption button works (from menu)
- [ ] Picture-in-picture works (from menu)

### Edge Cases
- [ ] Videos with limited qualities (only 360p available) - quality menu should still work or show "Auto"
- [ ] Audio-only content - quality menu should not appear
- [ ] Network issues during quality switch - graceful degradation
- [ ] Orientation changes - quality selection persists

---

## Known Limitations

1. **Adaptive Streaming**: This implementation uses discrete progressive streams, not true adaptive streaming (HLS/DASH). Each quality change requires loading a new video file, not just switching bitrate within the same stream.

2. **Bandwidth Detection**: ExoPlayer won't automatically select quality based on network conditions (unless we extend the TrackSelector further). Quality selection is manual.

3. **Quality Labels**: Quality labels are based on video height (e.g., "720p"). If NewPipe doesn't provide `qualityLabel`, we fall back to height/width/bitrate.

4. **MergingMediaSource Overhead**: Creating separate MediaSources for each quality adds memory overhead. For videos with 6+ qualities, this could be significant.

---

## Future Enhancements

### Potential Improvements
1. **Auto quality based on bandwidth**
   - Extend `QualityTrackSelector` to monitor bandwidth
   - Automatically select appropriate quality on playback start
   - Show "Auto" option in quality menu

2. **Quality persistence**
   - Save user's preferred quality in DataStore
   - Apply preferred quality on app start
   - "Remember for this resolution" option

3. **Pre-buffer next quality**
   - When user hovers over quality option, start buffering that quality
   - Reduces quality switch delay

4. **Quality change animations**
   - Show loading spinner during quality switch
   - Toast notification: "Switched to 1080p"

---

## References

- **ExoPlayer Track Selection**: https://exoplayer.dev/track-selection.html
- **ExoPlayer UI Components**: https://exoplayer.dev/ui-components.html
- **Media3 Downloading**: https://developer.android.com/media/media3/exoplayer/downloading-media
- **NewPipe Extractor**: https://github.com/TeamNewPipe/NewPipeExtractor

---

## Rollback Plan

If issues arise, revert to previous implementation:

1. **Restore quality button**:
   - Uncomment quality button in `fragment_player.xml`
   - Restore `showQualitySelector()` method
   - Restore `qualityButton.setOnClickListener()`

2. **Revert PlayerFragment changes**:
   ```bash
   git diff HEAD~1 android/app/src/main/java/com/albunyaan/tube/ui/player/PlayerFragment.kt
   git checkout HEAD~1 -- android/app/src/main/java/com/albunyaan/tube/ui/player/PlayerFragment.kt
   ```

3. **Remove new files**:
   - Delete `MultiQualityMediaSourceFactory.kt`
   - Delete `QualityTrackSelector.kt`

---

**Last Updated**: 2025-11-04
**Tested**: Pending APK installation
**Status**: Ready for device testing
