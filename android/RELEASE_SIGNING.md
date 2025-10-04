# Release Signing Guide

This guide explains how to set up release signing for the Albunyaan Tube Android app.

## ⚠️ Security Warning

**NEVER commit the production keystore or its passwords to version control!**

Add these to `.gitignore`:
- `*.keystore`
- `*.jks`
- `keystore.properties`
- `release-keystore.properties`

## 📝 Step 1: Create Production Keystore

Run this command to generate a production keystore:

```bash
keytool -genkey -v -keystore albunyaantube-release.keystore \
  -alias albunyaantube \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000
```

You'll be prompted for:
- **Keystore password**: Choose a strong password (save it securely!)
- **Key password**: Can be the same as keystore password
- **Your name**: Albunyaan Foundation (or your organization)
- **Organizational unit**: Development
- **Organization**: Albunyaan
- **City**: Your city
- **State**: Your state
- **Country code**: Your 2-letter country code (e.g., NL, SA, US)

**Important**: Save the keystore file and passwords in a secure location (password manager, encrypted backup). If you lose them, you cannot update your app on Play Store!

## 📋 Step 2: Create Keystore Properties File

Create `android/keystore.properties` (NOT committed to git):

```properties
storePassword=YOUR_KEYSTORE_PASSWORD
keyPassword=YOUR_KEY_PASSWORD
keyAlias=albunyaantube
storeFile=/absolute/path/to/albunyaantube-release.keystore
```

Or for relative path:
```properties
storePassword=YOUR_KEYSTORE_PASSWORD
keyPassword=YOUR_KEY_PASSWORD
keyAlias=albunyaantube
storeFile=../albunyaantube-release.keystore
```

## 🔧 Step 3: Update build.gradle.kts

The signing configuration is already set up to read from `keystore.properties`.

Add this to `android/build.gradle.kts` (before `android {}`):

```kotlin
// Load keystore properties
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()

if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

android {
    // ... existing config ...

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")  // Updated this line
            buildConfigField("boolean", "ENABLE_THUMBNAIL_IMAGES", "true")
        }
    }
}
```

## 🏗️ Step 4: Build Release

### Build APK:
```bash
cd android
./gradlew assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk`

### Build App Bundle (Recommended for Play Store):
```bash
cd android
./gradlew bundleRelease
```

Output: `app/build/outputs/bundle/release/app-release.aab`

**App Bundle (.aab) is recommended** because:
- Google Play generates optimized APKs for each device
- Smaller download sizes for users
- Supports dynamic delivery
- Required for apps over 150MB

## ✅ Step 5: Verify Signature

Verify the APK/AAB is signed correctly:

```bash
# For APK:
jarsigner -verify -verbose -certs app/build/outputs/apk/release/app-release.apk

# Check signature:
keytool -printcert -jarfile app/build/outputs/apk/release/app-release.apk
```

You should see:
- "jar verified."
- Certificate details matching your keystore

## 📊 Step 6: Check APK/AAB Size

```bash
# APK size:
ls -lh app/build/outputs/apk/release/app-release.apk

# AAB size:
ls -lh app/build/outputs/bundle/release/app-release.aab

# Analyze AAB contents:
bundletool build-apks --bundle=app/build/outputs/bundle/release/app-release.aab \
  --output=app.apks \
  --mode=universal

unzip -l app.apks
```

## 🔐 Security Best Practices

1. **Store keystore securely**:
   - Keep a backup in encrypted cloud storage (Google Drive encrypted, 1Password, etc.)
   - Store passwords in password manager
   - Never commit to git

2. **Limit access**:
   - Only trusted team members should have keystore access
   - Use environment variables in CI/CD pipelines
   - Rotate passwords periodically

3. **Document recovery**:
   - Document where keystore is stored
   - Share with at least 2 trusted people
   - Include in organization's disaster recovery plan

## 🚀 CI/CD Configuration

For GitHub Actions or other CI/CD:

1. Store keystore as base64:
```bash
base64 albunyaantube-release.keystore > keystore.base64
```

2. Add as GitHub Secret: `KEYSTORE_BASE64`

3. Add other secrets:
   - `KEYSTORE_PASSWORD`
   - `KEY_PASSWORD`
   - `KEY_ALIAS`

4. In workflow, decode and use:
```yaml
- name: Decode Keystore
  run: echo "${{ secrets.KEYSTORE_BASE64 }}" | base64 -d > release.keystore

- name: Build Release
  env:
    KEYSTORE_PASSWORD: ${{ secrets.KEYSTORE_PASSWORD }}
    KEY_PASSWORD: ${{ secrets.KEY_PASSWORD }}
  run: ./gradlew bundleRelease
```

## 📱 Testing Release Build

Before uploading to Play Store, test the release build:

```bash
# Install release APK on device:
adb install app/build/outputs/apk/release/app-release.apk

# Or install from AAB (using bundletool):
bundletool build-apks --bundle=app/build/outputs/bundle/release/app-release.aab \
  --output=app.apks \
  --connected-device

bundletool install-apks --apks=app.apks
```

Test all critical features:
- Video playback
- Downloads
- Search
- Settings
- Language switching
- Offline mode

## 📋 Pre-Upload Checklist

- [ ] Production keystore created and backed up
- [ ] keystore.properties configured
- [ ] Release build successful
- [ ] Signature verified
- [ ] Release APK/AAB tested on device
- [ ] All features working
- [ ] Version code incremented
- [ ] Version name updated
- [ ] Release notes prepared
- [ ] Screenshots captured
- [ ] Play Store listing complete

## 🎯 Version Management

Update version in `app/build.gradle.kts`:

```kotlin
defaultConfig {
    applicationId = "com.albunyaan.tube"
    minSdk = 26
    targetSdk = 34
    versionCode = 1      // Increment for each release
    versionName = "1.0.0" // Semantic versioning
}
```

Version naming:
- Major.Minor.Patch (e.g., 1.0.0)
- Increment `versionCode` for every release
- Increment `versionName` semantically:
  - Major: Breaking changes
  - Minor: New features
  - Patch: Bug fixes

## 🔄 Updating the App

For app updates:
1. Increment `versionCode`
2. Update `versionName`
3. Build release with same keystore
4. Upload new AAB to Play Store

**Never change keystore** - once an app is published, you must use the same keystore for all updates!
