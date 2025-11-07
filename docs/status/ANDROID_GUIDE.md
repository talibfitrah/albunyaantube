# Android App Development Guide

Complete guide for Android app configuration, testing, troubleshooting, and player development.

**Last Updated**: November 7, 2025

---

## Table of Contents
1. [Configuration](#configuration)
2. [Testing](#testing)
3. [Troubleshooting](#troubleshooting)
4. [Player Development Status](#player-development-status)

---

## Configuration

### Current Setup (November 7, 2025)

- **Backend URL**: `http://192.168.1.167:8080/`
- **Configuration Method**: `local.properties`
- **APK Status**: ✅ Built (`android/app/build/outputs/apk/debug/app-debug.apk`, 17MB)
- **Seeded Data**: 13 channels, 6 playlists, 173 videos, 19 categories

### Backend Configuration Methods

#### Method 1: Using `local.properties` (Recommended)

**Location**: `android/local.properties`

```properties
sdk.dir=/home/farouq/Android/Sdk

# API Base URL - Configure based on your setup
api.base.url=http://192.168.1.167:8080/
```

**How it works**:
- Value read from `local.properties` during build time
- Compiled into APK as `BuildConfig.API_BASE_URL`
- Default fallback: `http://10.0.2.2:8080/` (emulator localhost)

#### Method 2: Direct `build.gradle.kts` Edit

**Location**: `android/app/build.gradle.kts`

```kotlin
android {
    defaultConfig {
        buildConfigField("String", "API_BASE_URL", "\"http://YOUR_IP:8080/\"")
    }
}
```

### Configuration Scenarios

| Scenario | `api.base.url` | Network Security Config |
|----------|----------------|------------------------|
| **Emulator** | `http://10.0.2.2:8080/` | Default |
| **Physical Device (Same WiFi)** | `http://192.168.1.XXX:8080/` | Add local IP to whitelist |
| **VPS Testing (HTTP)** | `http://YOUR_VPS_IP:8080/` | Add VPS IP to whitelist |
| **Production (HTTPS)** | `https://api.yourdomain.com/` | Default (no HTTP) |

### Network Security Config

For HTTP connections (development/testing), update `android/app/src/main/res/xml/network_security_config.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="true">10.0.2.2</domain>
        <domain includeSubdomains="true">192.168.1.167</domain>
    </domain-config>
    <base-config cleartextTrafficPermitted="false" />
</network-security-config>
```

⚠️ **CRITICAL**: Android blocks HTTP traffic by default. You must update this file.

### Building & Installing

```bash
# Rebuild APK after configuration changes
cd android
./gradlew clean assembleDebug

# Install on connected device
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## Testing

### Test Results Summary (November 7, 2025)

#### ✅ Working
- Backend connectivity (all endpoints responding)
- Data loading (13 channels, 6 playlists, 173 videos, 19 categories)
- Navigation between all screens
- Onboarding flow
- Category navigation
- Bottom navigation

#### ⚠️ Needs Implementation
- Video playback quality selector integration
- Search authentication (403 error)
- Missing list details (categories, descriptions)
- Filter/sort functionality
- Downloads
- Settings functionality
- i18n/RTL support

### Quick Start Testing

#### 1. Run Backend
```bash
cd backend
./gradlew bootRun
# Wait for: "Started AlbunyaanTubeApplication"
```

#### 2. Install & Run App
```bash
cd android
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

#### 3. Verify Backend Connection
```bash
# From device
adb logcat | grep "http://192.168.1.167:8080"
```

### Testing Checklist

**Prerequisites:**
- [ ] Backend running at configured URL
- [ ] Device/emulator connected via ADB
- [ ] Network security config updated (for HTTP)

**Core Functionality:**
- [ ] Home tab loads mixed content
- [ ] Channels tab shows all channels
- [ ] Videos tab shows all videos
- [ ] Playlists tab shows playlists
- [ ] Search returns results
- [ ] Channel detail page loads
- [ ] Video player starts
- [ ] Categories filter works
- [ ] App doesn't crash on network errors

---

## Troubleshooting

### Quick Diagnostics

#### 1. Check Backend is Running

```bash
# Check backend process
ps aux | grep AlbunyaanTubeApplication

# Test endpoint
curl http://192.168.1.167:8080/api/v1/categories
# Should return JSON array with 19 categories
```

#### 2. Verify Device Connectivity

```bash
# From device (via terminal app or browser)
curl http://192.168.1.167:8080/api/v1/categories
```

#### 3. Check Android Logs

```bash
adb logcat | grep -i "albunyaan\|retrofit\|network\|cleartext"
```

### Common Issues

#### Issue: App Shows Empty Screens

**Symptoms**: All tabs empty, no error messages, loading indicators disappear quickly

**Diagnosis**:
1. Check backend is accessible: `curl http://192.168.1.167:8080/api/v1/categories`
2. Verify data exists: `curl http://192.168.1.167:8080/api/v1/content?type=CHANNELS&limit=5`
3. Check Android logs for errors

**Common Causes**:

**A. Network Security Config Not Updated**
```
Error: CLEARTEXT communication to YOUR_IP not permitted by network security policy
```
Fix: Add backend IP to `network_security_config.xml`

**B. Wrong API Base URL**
Check `android/local.properties` and verify IP address matches backend server.

**C. Device Not on Same Network**
Ensure device and backend server are on same WiFi network.

**D. Backend Not Running**
```bash
cd backend
./gradlew bootRun
```

**E. Firewall Blocking Port 8080**
```bash
sudo ufw allow 8080/tcp
```

**F. CORS Issues (Fixed Oct 31, 2025)**
Backend now allows all origins via `setAllowedOriginPatterns("*")` in `SecurityConfig.java:87-91`

### Testing Network Connectivity

```bash
# Check device can reach backend
adb shell ping -c 3 192.168.1.167

# Check port 8080 is open
adb shell "echo test | nc -w 5 192.168.1.167 8080"
```

---

## Player Development Status

### Current Status (November 4, 2025)

#### ✅ Implemented Features
- ExoPlayer integration with NewPipe extractor
- Quality selection (all qualities displayed)
- Seamless quality switching (preserves position)
- Fullscreen mode with bottom navigation hide
- Player controls auto-hide after 5 seconds
- Audio-only mode toggle
- Picture-in-Picture (PiP) mode (Android 8+)
- Subtitle/caption selector
- Share functionality
- Download support

#### ⚠️ Known Issues

**1. Quality Button Placement**
- **Current**: Quality button in toolbar overlay (top-right)
- **User Expectation**: Quality in ExoPlayer's native controls (gear icon)
- **Status**: Needs integration with ExoPlayer's settings menu
- **Reference**: https://stackoverflow.com/questions/48748657/exoplayer-hls-quality

**2. Player Controls Visibility**
- **Issue**: ExoPlayer's default controls may be broken after toolbar addition
- **Status**: Under investigation

### Player Architecture

```
PlayerFragment (UI)
  ↓
NewPipeExtractorClient (Stream extraction)
  ↓
ExoPlayer (Media playback)
```

**Key Files**:
- `app/src/main/java/com/albunyaan/tube/ui/player/PlayerFragment.kt` - Player UI & controls
- `app/src/main/java/com/albunyaan/tube/data/extractor/NewPipeExtractorClient.kt` - YouTube stream extraction
- `app/src/main/res/layout/fragment_player.xml` - Player layout

### Player Features

**Quality Selection**:
- Location: `PlayerFragment.kt:309-339`
- All available qualities displayed (sorted lowest to highest)
- Seamless switching with position preservation

**Fullscreen**:
- Location: `MainActivity.kt:82-87`
- Hides bottom navigation in fullscreen mode
- Restores navigation on exit

**PiP Mode**:
- Supports Android 8+ (API 26+)
- Continues playback in picture-in-picture window

**Audio-Only**:
- Toggle to play audio without video stream
- Saves bandwidth

---

## Production Deployment

### HTTPS Configuration

1. **Set up SSL certificate** (Let's Encrypt with nginx)
2. **Update Android configuration**:
   ```properties
   api.base.url=https://api.yourdomain.com/
   ```
3. **Remove cleartext traffic permission**:
   ```xml
   <base-config cleartextTrafficPermitted="false" />
   ```
4. **Build release APK**:
   ```bash
   ./gradlew assembleRelease
   ```

---

## Additional Resources

- **Project Status**: `docs/status/PROJECT_STATUS.md`
- **Development Guide**: `docs/status/DEVELOPMENT_GUIDE.md`
- **Architecture**: `docs/architecture/overview.md`
- **API Specification**: `docs/architecture/api-specification.yaml`

---

**Last Updated**: November 7, 2025
**Consolidated From**: BACKEND_CONFIGURATION.md, CONNECTIVITY_TROUBLESHOOTING.md, PLAYER_DEVELOPMENT.md, TESTING_GUIDE.md
