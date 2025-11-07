# Android App - Backend Configuration Guide

This guide covers connecting the Albunyaan Tube Android app to backend servers (local, VPS, or production).

---

## Current Configuration (November 5, 2025)

### Active Setup
- **Backend URL**: `http://192.168.1.167:8080/`
- **Configuration Method**: `local.properties`
- **Target**: Physical device/tablet testing on same network
- **APK Status**: ✅ Built and ready (`android/app/build/outputs/apk/debug/app-debug.apk`, 17MB)

### Features Enabled
- ✅ Responsive UI for tablets and TV (Full HD 1080p)
- ✅ Player controls auto-hide after 5 seconds
- ✅ Dynamic grid layouts (2-6 columns)
- ✅ NavigationRail for tablets
- ✅ Connected to VPS backend

---

## Configuration Methods

The Android app supports two configuration approaches:

### Method 1: Using `local.properties` (Recommended)

**Location**: `android/local.properties`

```properties
sdk.dir=/home/farouq/Android/Sdk

# API Base URL - Configure based on your setup
api.base.url=http://192.168.1.167:8080/
```

**How it works**:
- The value is read from `local.properties` during build time
- Compiled into APK as `BuildConfig.API_BASE_URL`
- Default fallback: `http://10.0.2.2:8080/` (emulator localhost)

**Build Configuration** (`android/app/build.gradle.kts:44`):
```kotlin
val apiBaseUrl = localProperties.getProperty("api.base.url", "http://10.0.2.2:8080/")
buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
```

### Method 2: Direct `build.gradle.kts` Edit

**Location**: `android/app/build.gradle.kts`

```kotlin
android {
    defaultConfig {
        buildConfigField("String", "API_BASE_URL", "\"http://YOUR_IP:8080/\"")
    }
}
```

**Note**: `local.properties` overrides this value if present.

---

## Configuration Scenarios

### 1. Emulator Testing (Localhost)

**`local.properties`**:
```properties
api.base.url=http://10.0.2.2:8080/
```

- `10.0.2.2` is the Android emulator's special alias for `localhost`
- Use when testing with emulator and backend running on same machine

### 2. Physical Device on Same Network (Current Setup)

**`local.properties`**:
```properties
api.base.url=http://192.168.1.167:8080/
```

- Use for testing on physical phones/tablets
- Device must be on same WiFi network as backend server
- **Current configuration** ✅

**Additional Step**: Update Network Security Config to allow HTTP.

Edit `android/app/src/main/res/xml/network_security_config.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <!-- Allow cleartext (HTTP) traffic for development -->
    <domain-config cleartextTrafficPermitted="true">
        <!-- Localhost for emulator -->
        <domain includeSubdomains="true">10.0.2.2</domain>
        <domain includeSubdomains="true">localhost</domain>
        <domain includeSubdomains="true">127.0.0.1</domain>

        <!-- Local network IP for physical device -->
        <domain includeSubdomains="true">192.168.1.167</domain>
    </domain-config>

    <!-- Block HTTP for all other domains -->
    <base-config cleartextTrafficPermitted="false" />
</network-security-config>
```

⚠️ **CRITICAL**: Android blocks HTTP traffic to non-localhost domains by default. You must update this file.

### 3. Remote VPS Testing (HTTP)

**`local.properties`**:
```properties
api.base.url=http://72.60.179.47:8080/
```

**Network Security Config**:
```xml
<domain includeSubdomains="true">72.60.179.47</domain>
```

### 4. Production Server (HTTPS)

**`local.properties`**:
```properties
api.base.url=https://api.yourdomain.com/
```

**Network Security Config**:
```xml
<!-- Remove domain-config, rely on base-config only -->
<base-config cleartextTrafficPermitted="false" />
```

No special configuration needed for HTTPS - Android trusts system certificates by default.

---

## Building and Installing

### After Configuration Changes

```bash
# Rebuild APK
cd android
./gradlew clean assembleDebug

# Install on connected device
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

APK location: `android/app/build/outputs/apk/debug/app-debug.apk`

### Release Build (Production)

```bash
./gradlew assembleRelease
```

---

## Testing Checklist

### Prerequisites
- [ ] Backend running and accessible at configured URL
- [ ] Device/emulator connected via ADB
- [ ] Network security config updated (for HTTP)
- [ ] Device on same network as backend (for local testing)

### Installation
```bash
# Check device is connected
adb devices

# Install APK
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

### Verification
After installation, test these features:

- [ ] Home tab loads with mixed content
- [ ] Channels tab shows all channels
- [ ] Videos tab shows all videos
- [ ] Playlists tab shows playlists
- [ ] Search returns results
- [ ] Channel detail page loads when tapping channel
- [ ] Video player starts when tapping video
- [ ] Categories filter works
- [ ] Thumbnails load correctly
- [ ] App doesn't crash on network errors

### Check Network Requests (Optional)
```bash
adb logcat | grep "http://192.168.1.167:8080"
```

---

## Troubleshooting

### Problem: App Shows Empty Screens

**Symptoms**:
- All tabs (Home, Channels, Videos) are empty
- No error messages visible
- Loading indicators disappear quickly

**Diagnosis Steps**:

1. **Check backend is accessible from device:**
   ```bash
   # From your dev machine
   curl http://192.168.1.167:8080/actuator/health

   # Should return: {"status":"UP"}
   ```

2. **Verify data exists:**
   ```bash
   curl http://192.168.1.167:8080/api/v1/content?type=CHANNELS&limit=5

   # Should return JSON with "data" array containing channels
   ```

3. **Check Android logs:**
   ```bash
   adb logcat | grep -i "albunyaan\|retrofit\|network\|cleartext"
   ```

**Common Causes & Fixes**:

#### A. Network Security Config Not Updated

**Error in logcat**:
```
CLEARTEXT communication to YOUR_IP not permitted by network security policy
```

**Fix**: Add backend IP to `network_security_config.xml` (see Configuration Scenarios above).

#### B. Wrong API Base URL

**Check**: Open `android/local.properties` and verify IP address matches backend server.

**Fix**: Update `api.base.url` and rebuild APK.

#### C. Device Not on Same Network

**Check**: Ensure device and backend server are on same WiFi network.

**Fix**:
- Connect device to same WiFi as backend server
- Or use VPN if connecting remotely

#### D. Backend Not Running

**Check on backend server**:
```bash
curl http://localhost:8080/actuator/health
```

**Fix**:
```bash
# If using systemd
sudo systemctl start albunyaan-backend

# Or run manually
cd backend
./gradlew bootRun
```

#### E. Firewall Blocking Port 8080

**Check on backend server**:
```bash
sudo ufw status
# Port 8080 should be allowed
```

**Fix**:
```bash
sudo ufw allow 8080/tcp
```

#### F. API Response Structure Mismatch

The Android app expects this JSON structure:
```json
{
  "data": [
    {
      "id": "...",
      "type": "CHANNEL",
      "name": "...",
      ...
    }
  ],
  "pageInfo": {
    "nextCursor": "..."
  }
}
```

**Check backend returns correct structure**:
```bash
curl http://192.168.1.167:8080/api/v1/content?type=CHANNELS&limit=1 | jq '.'
```

If you see `{"items": [...]}` instead of `{"data": [...]}`, your backend is outdated. Update to latest code.

### Problem: Connection Refused Errors

**Solution**:
- Verify backend is running: `curl http://BACKEND_IP:8080/api/v1/categories`
- Check firewall allows port 8080
- Check device can ping backend server

### Problem: CORS Errors (Should Not Happen on Mobile)

**Note**: Mobile apps don't have CORS restrictions (that's a browser security feature). If you see CORS errors, you're likely testing in a browser, not the Android app.

---

## Production Deployment

For production, follow these steps:

### 1. Set Up HTTPS

Use Let's Encrypt with nginx:
```bash
sudo certbot --nginx -d api.yourdomain.com
```

### 2. Update Android Configuration

**`local.properties` (or `build.gradle.kts`)**:
```kotlin
buildConfigField("String", "API_BASE_URL", "\"https://api.yourdomain.com/\"")
```

### 3. Remove Cleartext Traffic Permission

**`network_security_config.xml`**:
```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <!-- Only HTTPS allowed in production -->
    <base-config cleartextTrafficPermitted="false" />
</network-security-config>
```

### 4. Build Release APK

```bash
./gradlew assembleRelease
```

---

## Quick Reference Table

| Scenario | `api.base.url` | Network Security Config | Notes |
|----------|----------------|------------------------|-------|
| **Emulator** | `http://10.0.2.2:8080/` | Default | Backend on same machine |
| **Physical Device (Same WiFi)** | `http://192.168.1.XXX:8080/` | Add local IP to whitelist | Current setup ✅ |
| **VPS Testing (HTTP)** | `http://YOUR_VPS_IP:8080/` | Add VPS IP to whitelist | Temporary for testing |
| **Production (HTTPS)** | `https://api.yourdomain.com/` | Default (no HTTP) | Recommended |

---

## Support

If issues persist:

1. Check [CONNECTIVITY_TROUBLESHOOTING.md](CONNECTIVITY_TROUBLESHOOTING.md) for detailed debugging
2. Check [VPS_DEPLOYMENT.md](../deployment/VPS_DEPLOYMENT.md) - Android App Configuration section
3. Review backend logs on server
4. Check Android logcat for errors

---

## Notes

- `local.properties` is gitignored (not committed to repository)
- Each developer needs to configure their own `local.properties`
- Default fallback is `http://10.0.2.2:8080/` (emulator localhost)
- APK must be rebuilt after changing `local.properties`
- Network security config changes require rebuilding APK

---

**Last Updated**: November 7, 2025
**Current Backend**: 192.168.1.167:8080
**Status**: ✅ Configured and tested
