# Screenshots Guide for Play Store

This guide explains how to capture and prepare screenshots for the Google Play Store listing.

## 📋 Requirements

### Phone Screenshots (Required)
- **Minimum**: 2 screenshots
- **Maximum**: 8 screenshots
- **Size**: 1080 x 1920 pixels (9:16 ratio) or 1080 x 2340 for taller screens
- **Format**: PNG or JPEG (24-bit, no alpha)
- **File size**: 8MB max per screenshot

### 7-inch Tablet Screenshots (Optional)
- **Size**: 1200 x 1920 pixels
- **Format**: PNG or JPEG

### 10-inch Tablet Screenshots (Optional)
- **Size**: 1600 x 2560 pixels
- **Format**: PNG or JPEG

## 🎬 Recommended Screenshots

Create screenshots for these 6 key screens in order:

### 1. **Home Screen** (Priority: High)
**What to show**:
- Top app bar with "Albunyaan Tube" title and menu icons
- Category filter chip
- Three content sections:
  - Channels (horizontal carousel)
  - Playlists (horizontal carousel)
  - Videos (horizontal carousel)
- Bottom navigation bar

**How to capture**:
1. Launch app and wait for content to load
2. Ensure you're on Home tab (first tab in bottom nav)
3. Take screenshot (Power + Volume Down)

**Caption**: "Browse thousands of Islamic videos, channels, and playlists"

---

### 2. **Video Player** (Priority: High)
**What to show**:
- Video playing in fullscreen/portrait
- Player controls visible
- Video title, author, stats
- Action buttons (Like, Share, Download, Audio)

**How to capture**:
1. Tap any video to open player
2. Wait for video to start playing
3. Tap screen to show controls
4. Take screenshot immediately (controls auto-hide)

**Caption**: "Modern player with PiP, gestures, and background playback"

---

### 3. **Search Results** (Priority: Medium)
**What to show**:
- Search bar at top with query entered
- Search results list (videos/channels/playlists)
- Search history visible if possible

**How to capture**:
1. Tap search icon on Home screen
2. Type query like "Quran" or "Hadith"
3. Wait for results to load
4. Take screenshot

**Caption**: "Powerful search with history and suggestions"

---

### 4. **Channel Detail** (Priority: Medium)
**What to show**:
- Channel header with avatar, name, subscriber count
- Tabs (Videos, Live, Shorts, Playlists)
- Content grid/list below tabs

**How to capture**:
1. From Home, tap any channel
2. Wait for channel detail to load
3. Take screenshot

**Caption**: "Explore channels from renowned Islamic scholars"

---

### 5. **Downloads Screen** (Priority: Medium)
**What to show**:
- Downloads tab active in bottom navigation
- List of downloaded videos (or empty state)
- Download progress if possible
- Library sections (History, Recently Watched, Saved)

**How to capture**:
1. Tap Downloads tab (4th tab in bottom nav)
2. If empty, download a video first from player
3. Take screenshot showing downloads

**Caption**: "Download videos for offline viewing"

---

### 6. **Settings Screen** (Priority: Low)
**What to show**:
- Settings sections:
  - General (Language, Theme)
  - Playback (Quality, Audio-only, Background)
  - Downloads (Quality, WiFi-only)
  - Privacy (Safe mode)
  - About

**How to capture**:
1. Tap menu icon (three dots) on Home
2. Select "Settings"
3. Take screenshot

**Caption**: "Customize your experience with powerful settings"

---

## 🛠️ Capture Methods

### Method 1: Physical Device (Recommended)
```bash
# 1. Connect device via USB with ADB enabled
# 2. Install debug APK:
cd android
./gradlew installDebug

# 3. Open app and navigate to each screen
# 4. Take screenshots using device (Power + Volume Down)
# 5. Pull screenshots from device:
adb pull /sdcard/Pictures/Screenshots/ ./play-store/screenshots/
```

### Method 2: Android Emulator
```bash
# 1. Create Pixel 6 emulator (1080x2340) in Android Studio
# 2. Launch emulator
# 3. Install APK:
adb install app/build/outputs/apk/debug/app-debug.apk

# 4. Navigate and use emulator screenshot button (camera icon)
# 5. Screenshots saved to: ~/Library/Android/sdk/screenshots/
```

### Method 3: Android Studio Layout Editor
- Open layout XML files
- Use "Preview" tab
- Click camera icon to capture
- Less realistic, not recommended for Play Store

## 🎨 Screenshot Editing (Optional)

### Add Device Frame:
Use Android Studio's "Device Art Generator":
1. Open Android Studio
2. Tools → Device Art Generator
3. Select device (Pixel 6)
4. Load screenshot
5. Export with frame

### Add Text Captions:
Tools:
- **Canva** (free, online)
- **Figma** (free, online)
- **Photoshop/GIMP** (desktop)

Template size: 1080 x 1920
- Add white bar at top (1080 x 200)
- Add caption text (bold, 48-60px)
- Use brand color (#35C491) for accents

## 📁 File Organization

Organize screenshots in this structure:

```
android/play-store/screenshots/
├── phone/
│   ├── en/
│   │   ├── 01-home.png
│   │   ├── 02-player.png
│   │   ├── 03-search.png
│   │   ├── 04-channel.png
│   │   ├── 05-downloads.png
│   │   └── 06-settings.png
│   ├── ar/
│   │   ├── 01-home.png
│   │   ├── 02-player.png
│   │   └── ... (same screens in Arabic)
│   └── nl/
│       ├── 01-home.png
│       └── ... (same screens in Dutch)
├── tablet-7/
│   └── en/
│       └── ... (if applicable)
└── tablet-10/
    └── en/
        └── ... (if applicable)
```

## ✅ Pre-Upload Checklist

Before uploading to Play Store:

- [ ] At least 2 phone screenshots captured
- [ ] Screenshots are 1080 x 1920 (or device native resolution)
- [ ] Screenshots show actual app content (not mockups)
- [ ] No personal information visible
- [ ] Content is appropriate and accurate
- [ ] Screenshots are high quality (no blur, no artifacts)
- [ ] File sizes under 8MB
- [ ] Files named descriptively (01-home.png, 02-player.png)
- [ ] Screenshots ordered logically (most important first)
- [ ] Captions prepared for each screenshot (optional but recommended)

## 🌍 Localized Screenshots

For Arabic (ar):
1. Change device/emulator language to Arabic
2. Launch app (UI should flip to RTL)
3. Capture same screens
4. Upload to "ar" locale on Play Console

For Dutch (nl):
1. Change device language to Dutch
2. Launch app
3. Capture same screens
4. Upload to "nl" locale on Play Console

**Note**: Localized screenshots are optional but highly recommended for better conversion rates.

## 📊 Screenshot Performance Tips

1. **First screenshot is most important** - Use Home screen
2. **Show actual features** - Don't use mockups or placeholder data
3. **Highlight unique value** - Focus on Islamic content curation
4. **Use captions** - Explain features briefly
5. **Keep consistent** - Use same device/style for all screenshots
6. **Test on small screens** - Ensure text is readable

## 🚀 Quick Start

Fastest way to get screenshots:

```bash
# 1. Install app on device
cd android
./gradlew installDebug

# 2. Open app and navigate to 6 screens listed above
# 3. Take screenshots (Power + Volume Down on most devices)
# 4. Pull screenshots from device
adb pull /sdcard/DCIM/Screenshots/ ./play-store/screenshots/phone/en/

# 5. Rename files:
mv Screenshot_*.png 01-home.png
mv Screenshot_*.png 02-player.png
# ... etc

# 6. Upload to Play Console
```

Done! You now have screenshots ready for Play Store submission.

