# Play Store Assets

> **Rebranding Note**: The app has been rebranded from "Albunyaan Tube" to **FitrahTube**. Domain names (`albunyaantube.com`), support email (`support@albunyaantube.com`), keystore/alias names (`albunyaantube-release.keystore` / `albunyaantube`), and the deep link scheme (`albunyaantube://`) intentionally retain the original "albunyaan" naming for backward compatibility and infrastructure continuity. Only the user-facing app title, descriptions, and store listing text use "FitrahTube".

This directory contains all assets and metadata for the Google Play Store listing.

## 📝 Text Content

- **description.md** - Full Play Store description with features list
- **release-notes.md** - Release notes for version 1.0.0

## 🎨 Required Assets (To Be Created)

### App Icon
- ✅ **Adaptive Icon** - Already configured in `mipmap-*/ic_launcher.xml`
  - Background: Green (#35C491)
  - Foreground: Movie/play icon

### Screenshots (Required)
Create screenshots for the following screen sizes:

**Phone Screenshots** (minimum 2, maximum 8):
- Recommended size: 1080x1920 (9:16 ratio)
- Screenshots needed:
  1. Home screen with content sections
  2. Video player with controls
  3. Channel detail page
  4. Downloads screen
  5. Settings screen (optional)
  6. Search results (optional)

**7-inch Tablet Screenshots** (optional but recommended):
- Recommended size: 1200x1920

**10-inch Tablet Screenshots** (optional but recommended):
- Recommended size: 1600x2560

### Feature Graphic
- **Size**: 1024x500 pixels
- **Format**: PNG or JPEG
- **Content**: App logo + tagline "Your trusted source for Islamic content"
- **Background**: Use primary green (#35C491) or gradient

### Promo Video (Optional)
- YouTube URL showcasing app features
- Duration: 30 seconds to 2 minutes

## 📋 Play Store Listing Checklist

### Required:
- [x] App title: "FitrahTube"
- [x] Short description (80 chars): "Your trusted source for Islamic content - videos, channels & playlists"
- [x] Full description (4000 chars max)
- [x] App icon (512x512 PNG)
- [ ] Feature graphic (1024x500)
- [ ] At least 2 phone screenshots
- [x] App category: Education
- [x] Content rating: Everyone
- [x] Privacy policy URL: albunyaantube.com/privacy

### Optional but Recommended:
- [ ] Promo video
- [ ] Tablet screenshots
- [ ] TV banner (1280x720)

## 🏷️ Store Listing Details

**Category**: Education
**Tags**: Islamic, Education, Videos, Lectures, Quran, Hadith
**Content Rating**: Everyone
**Target Audience**: 13+

**Website**: https://albunyaantube.com
**Email**: support@albunyaantube.com
**Privacy Policy**: https://albunyaantube.com/privacy
**Terms of Service**: https://albunyaantube.com/terms

## 🚀 Release Configuration

**Version Code**: 1
**Version Name**: 1.0.0
**Min SDK**: 26 (Android 8.0)
**Target SDK**: 34 (Android 14)

**Build Type**: Release
**Signed**: Yes (debug keystore for testing, production keystore for release)
**Minified**: Yes (R8)
**Optimized**: Yes

## 📱 Testing Before Submission

1. Install release APK on test devices
2. Test all major features:
   - Content browsing
   - Search functionality
   - Video playback
   - Downloads
   - Settings changes
   - Language switching
3. Verify deep links work
4. Test on different screen sizes
5. Check accessibility with TalkBack
6. Verify RTL layout for Arabic

## 🔐 Release Signing

**For Production Release**:
1. Generate production keystore:
   ```bash
   keytool -genkey -v -keystore albunyaantube-release.keystore \
     -alias albunyaantube -keyalg RSA -keysize 2048 -validity 10000
   ```

2. Update `build.gradle.kts` with production signing config
3. Build signed release:
   ```bash
   ./gradlew assembleRelease
   ```

4. Verify signature:
   ```bash
   jarsigner -verify -verbose -certs app-release.apk
   ```

## 📊 App Bundle (Recommended for Play Store)

Build App Bundle instead of APK:
```bash
./gradlew bundleRelease
```

Output: `app/build/outputs/bundle/release/app-release.aab`

## 🌐 Localization

Store listing should be translated to:
- **English (US)** - Primary
- **Arabic (ar)** - Full translation
- **Dutch (nl)** - Full translation

Each language needs:
- Title
- Short description
- Full description
- Screenshots with localized UI (if possible)
