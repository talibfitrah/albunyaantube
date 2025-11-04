# Android App - VPS Connection Guide

This guide explains how to connect the Albunyaan Tube Android app to a live VPS backend.

---

## Prerequisites

- Backend deployed to VPS and running
- Backend accessible at `http://YOUR_IP:8080`
- Android device with USB debugging enabled
- ADB installed on development machine

---

## Step-by-Step Configuration

### 1. Update API Base URL

Edit **`android/app/build.gradle.kts`** and change the API_BASE_URL:

```kotlin
android {
    defaultConfig {
        // Change this line to your VPS IP
        buildConfigField("String", "API_BASE_URL", "\"http://YOUR_VPS_IP:8080/\"")
    }
}
```

**Example:**
```kotlin
buildConfigField("String", "API_BASE_URL", "\"http://72.60.179.47:8080/\"")
```

### 2. Allow HTTP Traffic (Network Security Config)

**⚠️ CRITICAL:** Android blocks HTTP traffic to non-localhost domains by default.

Edit **`android/app/src/main/res/xml/network_security_config.xml`**:

```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <!-- Allow cleartext (HTTP) traffic for VPS -->
    <domain-config cleartextTrafficPermitted="true">
        <!-- Localhost for emulator -->
        <domain includeSubdomains="true">10.0.2.2</domain>
        <domain includeSubdomains="true">localhost</domain>
        <domain includeSubdomains="true">127.0.0.1</domain>

        <!-- YOUR VPS IP HERE -->
        <domain includeSubdomains="true">YOUR_VPS_IP</domain>
    </domain-config>

    <!-- Block HTTP for all other domains -->
    <base-config cleartextTrafficPermitted="false" />
</network-security-config>
```

**Example:**
```xml
<domain includeSubdomains="true">72.60.179.47</domain>
```

### 3. Build and Install APK

```bash
# Build debug APK
cd android
./gradlew assembleDebug

# Install on connected device
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 4. Verify Connection

Open the app and navigate through tabs:
- **Home**: Should show mixed content (channels, videos)
- **Channels**: Should show all channels with thumbnails
- **Videos**: Should show all videos
- **Search**: Should return results

---

## Troubleshooting

### Problem: App Shows Empty Screens

**Symptoms:**
- All tabs (Home, Channels, Videos) are empty
- No error messages visible
- Loading indicators disappear quickly

**Diagnosis:**

1. **Check backend is accessible from device:**
   ```bash
   # From your dev machine
   curl http://YOUR_VPS_IP:8080/actuator/health

   # Should return: {"status":"UP"}
   ```

2. **Verify data exists:**
   ```bash
   curl http://YOUR_VPS_IP:8080/api/v1/content?type=CHANNELS&limit=5

   # Should return JSON with "data" array containing channels
   ```

3. **Check Android logs:**
   ```bash
   adb logcat | grep -i "albunyaan\|retrofit\|network\|cleartext"
   ```

**Common Causes:**

#### A. Network Security Config Not Updated

**Error in logcat:**
```
CLEARTEXT communication to YOUR_IP not permitted by network security policy
```

**Fix:** Add VPS IP to `network_security_config.xml` (see Step 2 above).

#### B. Wrong API Base URL

**Check:** Open `android/app/build.gradle.kts` and verify:
```kotlin
buildConfigField("String", "API_BASE_URL", "\"http://72.60.179.47:8080/\"")
//                                                 ^^^^^^^^^^^^^^
//                                                 Must match your VPS IP
```

#### C. Backend Not Running

**Check on VPS:**
```bash
ssh root@YOUR_VPS_IP
sudo systemctl status albunyaan-backend
```

**Fix:**
```bash
sudo systemctl start albunyaan-backend
```

#### D. Firewall Blocking Port 8080

**Check on VPS:**
```bash
sudo ufw status
# Port 8080 should be allowed
```

**Fix:**
```bash
sudo ufw allow 8080/tcp
```

#### E. API Response Structure Mismatch

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

**Check backend returns correct structure:**
```bash
curl http://YOUR_VPS_IP:8080/api/v1/content?type=CHANNELS&limit=1 | jq '.'
```

If you see `{"items": [...]}` instead of `{"data": [...]}`, your backend is outdated. Update to latest code.

---

## Testing Checklist

After deploying, test these features:

- [ ] Home tab loads with mixed content
- [ ] Channels tab shows all channels
- [ ] Videos tab shows all videos
- [ ] Playlists tab shows playlists (if any)
- [ ] Search returns results for query
- [ ] Channel detail page loads when tapping channel
- [ ] Video player starts when tapping video
- [ ] Categories filter works
- [ ] App doesn't crash on network errors

---

## Production Deployment

For production, you should:

1. **Set up HTTPS** with Let's Encrypt:
   ```bash
   sudo certbot --nginx -d api.yourdomain.com
   ```

2. **Update Android app to use HTTPS:**
   ```kotlin
   buildConfigField("String", "API_BASE_URL", "\"https://api.yourdomain.com/\"")
   ```

3. **Remove cleartext traffic permission:**
   ```xml
   <!-- Remove domain-config, only keep base-config -->
   <base-config cleartextTrafficPermitted="false" />
   ```

4. **Build release APK:**
   ```bash
   ./gradlew assembleRelease
   ```

---

## Quick Reference

### Local Development
```kotlin
// For emulator
buildConfigField("String", "API_BASE_URL", "\"http://10.0.2.2:8080/\"")

// For physical device on same WiFi
buildConfigField("String", "API_BASE_URL", "\"http://192.168.1.XXX:8080/\"")
```

### VPS Testing (HTTP)
```kotlin
buildConfigField("String", "API_BASE_URL", "\"http://YOUR_VPS_IP:8080/\"")
```
+ Add VPS IP to `network_security_config.xml`

### Production (HTTPS)
```kotlin
buildConfigField("String", "API_BASE_URL", "\"https://api.yourdomain.com/\"")
```
+ Remove cleartext traffic permission

---

## Support

If issues persist:

1. Check [VPS_DEPLOYMENT.md](../deployment/VPS_DEPLOYMENT.md) - Android App Configuration section
2. Check [CONNECTIVITY_TROUBLESHOOTING.md](CONNECTIVITY_TROUBLESHOOTING.md) for detailed debugging
3. Review backend logs: `ssh root@VPS_IP 'tail -f /opt/albunyaan/logs/app.log'`

---

**Last Updated:** 2025-11-04
**Status:** Verified working with VPS deployment at 72.60.179.47
