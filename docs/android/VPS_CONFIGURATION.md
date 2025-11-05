# Android App VPS Backend Configuration

## Current Configuration

The Android app is currently configured to connect to the VPS backend for physical device testing.

### Backend URL
- **VPS IP**: `192.168.1.167`
- **Port**: `8080`
- **Full URL**: `http://192.168.1.167:8080/`

### Configuration File
**Location**: `android/local.properties`

**Content**:
```properties
sdk.dir=/home/farouq/Android/Sdk

# API Base URL - Point to VPS backend
# For physical device or tablet testing on same network
api.base.url=http://192.168.1.167:8080/
```

## How It Works

The Android app reads the `api.base.url` property from `local.properties` during build time. This value is compiled into the APK as `BuildConfig.API_BASE_URL`.

**Build Configuration** (`android/app/build.gradle.kts:44`):
```kotlin
val apiBaseUrl = localProperties.getProperty("api.base.url", "http://10.0.2.2:8080/")
buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
```

## Configuration Options

### 1. For Emulator Testing (Localhost)
```properties
api.base.url=http://10.0.2.2:8080/
```
- `10.0.2.2` is the Android emulator's special alias for `localhost`
- Use this when testing with emulator and backend running on same machine

### 2. For Physical Device on Same Network (VPS)
```properties
api.base.url=http://192.168.1.167:8080/
```
- Use this for testing on physical phones/tablets
- Device must be on same WiFi network as VPS
- **Current configuration** ✅

### 3. For Remote/Production Server
```properties
api.base.url=https://your-domain.com/
```
- Use this when deploying to production
- Should use HTTPS in production

## Rebuilding APK After Configuration Change

After modifying `local.properties`, rebuild the APK:

```bash
cd android
./gradlew clean assembleDebug
```

The new APK will be at:
```
android/app/build/outputs/apk/debug/app-debug.apk
```

## Testing Checklist

### Prerequisites
- ✅ Backend running on VPS at `http://192.168.1.167:8080`
- ✅ Device/tablet on same network as VPS
- ✅ CORS configured to allow all origins (already done)

### Installation
```bash
# Install on connected device
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

### Verification
1. Open app on device
2. Navigate to Videos/Channels tabs
3. Check if content loads from VPS
4. Check logcat for network requests:
   ```bash
   adb logcat | grep "http://192.168.1.167:8080"
   ```

## Troubleshooting

### App shows empty screens
**Possible causes**:
1. Device not on same network as VPS
2. Backend not running on VPS
3. Firewall blocking port 8080
4. Wrong IP address configured

**Solution**:
- Verify VPS backend is accessible from device:
  ```bash
  # On device (using terminal app or browser)
  curl http://192.168.1.167:8080/api/v1/categories
  ```

### Connection refused errors
**Solution**:
- Check VPS firewall allows port 8080
- Verify backend is running: `curl http://192.168.1.167:8080/api/v1/categories`

## Current APK Status

**Last Built**: November 5, 2025 21:38
**Configuration**: VPS Backend (192.168.1.167:8080)
**APK Size**: 17MB
**Location**: `android/app/build/outputs/apk/debug/app-debug.apk`

**Features**:
- ✅ Responsive UI for tablets and TV (Full HD 1080p)
- ✅ Player controls auto-hide after 5 seconds
- ✅ Dynamic grid layouts (2-6 columns)
- ✅ NavigationRail for tablets
- ✅ Connected to VPS backend

## Notes

- `local.properties` is gitignored (not committed to repository)
- Each developer needs to configure their own `local.properties`
- Default fallback is `http://10.0.2.2:8080/` (emulator localhost)
- APK must be rebuilt after changing `local.properties`

---

**Last Updated**: November 5, 2025
**VPS IP**: 192.168.1.167:8080
**Status**: ✅ Configured and APK rebuilt
