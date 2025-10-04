# Production Release Checklist

Complete this checklist before submitting to Google Play Store.

## 📋 Pre-Release Tasks

### Code & Build Configuration

- [x] **Version updated** in `app/build.gradle.kts`
  - `versionCode = 1` ✅
  - `versionName = "1.0.0"` ✅
  - Min SDK: 26 (Android 8.0) ✅
  - Target SDK: 34 (Android 14) ✅

- [x] **R8 minification enabled** ✅
  - `isMinifyEnabled = true`
  - `isShrinkResources = true`
  - ProGuard rules configured

- [x] **Release signing configured** ✅
  - See `RELEASE_SIGNING.md` for keystore setup
  - Production keystore created (NOT in git)
  - `keystore.properties` file created
  - Keystore backed up securely

- [ ] **Production API URL** updated
  - Update `API_BASE_URL` in `build.gradle.kts`
  - Current: `http://localhost:8080/` (development)
  - Production: Update to actual backend URL

- [x] **Debug features disabled**
  - No debug logging in production ✅
  - No test data visible ✅
  - StrictMode disabled ✅

### Testing

- [ ] **Install and test release build**
  ```bash
  ./gradlew installRelease
  ```
  - App launches successfully
  - No crashes on startup
  - All features work as expected

- [ ] **Test core features**
  - [ ] Video playback (online)
  - [ ] Search functionality
  - [ ] Downloads (start, pause, resume, cancel)
  - [ ] Offline playback
  - [ ] Settings (language change, quality, safe mode)
  - [ ] Navigation between all screens
  - [ ] Back button behavior
  - [ ] Deep links (if applicable)

- [ ] **Test on multiple devices/OS versions**
  - [ ] Android 8.0 (API 26) - Minimum
  - [ ] Android 10.0 (API 29)
  - [ ] Android 12.0 (API 31)
  - [ ] Android 14.0 (API 34) - Target
  - [ ] Different screen sizes (phone, tablet)

- [ ] **Test RTL (Arabic)**
  - [ ] Change language to Arabic in settings
  - [ ] Verify all screens flip to RTL
  - [ ] Text displays correctly
  - [ ] No layout issues

- [ ] **Test accessibility**
  - [ ] Enable TalkBack
  - [ ] Navigate through main screens
  - [ ] All buttons have content descriptions
  - [ ] Navigation is logical

- [ ] **Test offline mode**
  - [ ] Enable airplane mode
  - [ ] Offline banner appears
  - [ ] Downloaded videos play
  - [ ] Appropriate error messages for network features

- [ ] **Test error scenarios**
  - [ ] No internet connection
  - [ ] Server timeout
  - [ ] Invalid video URL
  - [ ] Storage full (downloads)

### Performance

- [x] **APK/AAB size optimized** ✅
  - Release APK: 6.0 MB ✅
  - App Bundle: 9.4 MB ✅
  - Under 50MB threshold ✅

- [ ] **App startup time**
  - Cold start < 3 seconds
  - Warm start < 1 second

- [ ] **Memory usage**
  - No memory leaks
  - Smooth scrolling in lists
  - Video playback doesn't cause ANRs

- [x] **DiffUtil in all adapters** ✅
  - Efficient RecyclerView updates

## 📱 Play Store Assets

### Required Assets

- [x] **App Icon**
  - 512 x 512 PNG (with transparency)
  - Already configured: `ic_launcher.xml` ✅

- [x] **Feature Graphic**
  - 1024 x 500 PNG or JPEG
  - No transparency
  - ⚠️ TODO: Create feature graphic with logo + tagline

- [ ] **Screenshots** (Phone - Required)
  - Minimum 2, maximum 8
  - Size: 1080 x 1920 or device native
  - See `SCREENSHOTS_GUIDE.md`
  - Recommended 6 screenshots:
    - [ ] 1. Home screen
    - [ ] 2. Video player
    - [ ] 3. Search results
    - [ ] 4. Channel detail
    - [ ] 5. Downloads
    - [ ] 6. Settings

- [ ] **Screenshots** (Tablet - Optional)
  - 7-inch: 1200 x 1920
  - 10-inch: 1600 x 2560

### Store Listing Text

- [x] **App title** ✅
  - "Albunyaan Tube"
  - Max 50 characters ✅

- [x] **Short description** ✅
  - "Your trusted source for Islamic content - videos, channels & playlists"
  - Max 80 characters ✅
  - File: `play-store/description.md`

- [x] **Full description** ✅
  - Max 4000 characters
  - Includes features, requirements, benefits
  - File: `play-store/description.md`

- [x] **Release notes** ✅
  - v1.0.0 initial release
  - File: `play-store/release-notes.md`

### Categorization & Ratings

- [x] **App category** ✅
  - Primary: Education
  - Secondary: Video Players & Editors

- [ ] **Content rating**
  - Complete questionnaire on Play Console
  - Expected: Everyone / PEGI 3
  - No ads, no violence, family-friendly

- [x] **Tags/Keywords** ✅
  - Islamic, Education, Videos, Lectures, Quran, Hadith, Fiqh

### Privacy & Legal

- [ ] **Privacy Policy URL**
  - Create privacy policy
  - Host at: https://albunyaantube.com/privacy
  - Required even if no data collection

- [ ] **Terms of Service** (Optional but recommended)
  - Host at: https://albunyaantube.com/terms

- [ ] **Data Safety section** (Play Console)
  - Declare data collection practices
  - No data shared with third parties
  - No data collected (if applicable)

### Contact Information

- [ ] **Developer name**
  - Albunyaan Foundation (or your name/org)

- [ ] **Email address**
  - support@albunyaantube.com
  - Must be accessible for user support

- [ ] **Website** (Optional)
  - https://albunyaantube.com

- [ ] **Phone number** (Optional)
  - For Google Play support communication

## 🔧 Technical Requirements

### Permissions

- [ ] **Review permissions** in AndroidManifest.xml
  - INTERNET ✅ (streaming)
  - WRITE_EXTERNAL_STORAGE (downloads, API < 29)
  - READ_EXTERNAL_STORAGE (downloads, API < 29)
  - FOREGROUND_SERVICE (downloads)
  - POST_NOTIFICATIONS (API 33+)

- [ ] **Request permissions at runtime**
  - Storage permissions (if needed)
  - Notification permissions (Android 13+)

### APIs & Services

- [ ] **Backend API** is production-ready
  - SSL/HTTPS enabled
  - Rate limiting configured
  - Error responses are appropriate
  - Content is moderated

- [ ] **NewPipe Extractor** tested
  - Video URLs resolve correctly
  - No copyright violations
  - Fallback mechanisms work

- [ ] **Firebase/Analytics** (if used)
  - google-services.json configured
  - Analytics opt-out available
  - GDPR compliant

### Security

- [ ] **No hardcoded secrets**
  - API keys in BuildConfig or secure storage
  - No passwords in code
  - No sensitive data in logs

- [ ] **Network security config**
  - HTTPS only for production
  - Certificate pinning (if applicable)

- [ ] **ProGuard/R8 rules**
  - All keep rules tested ✅
  - No crashes from obfuscation

## 📦 Build & Upload

### Final Build

- [ ] **Clean build**
  ```bash
  cd android
  ./gradlew clean
  ./gradlew bundleRelease
  ```

- [ ] **Verify signing**
  ```bash
  jarsigner -verify -verbose -certs \
    app/build/outputs/bundle/release/app-release.aab
  ```
  - Should show "jar verified."

- [ ] **Check file size**
  ```bash
  ls -lh app/build/outputs/bundle/release/app-release.aab
  ```
  - Should be < 150MB (our target: ~9-10 MB) ✅

### Upload to Play Console

- [ ] **Create app on Play Console**
  - https://play.google.com/console
  - Create new app
  - Fill in basic details

- [ ] **Upload App Bundle**
  - Go to "Production" → "Create new release"
  - Upload `app-release.aab`
  - Add release notes

- [ ] **Fill out store listing**
  - App details
  - Graphics (screenshots, icon, feature graphic)
  - Categorization

- [ ] **Complete content rating**
  - Answer questionnaire honestly
  - Submit for rating

- [ ] **Set up pricing & distribution**
  - Free app (or paid)
  - Select countries
  - Opt in/out of Google Play for Families

- [ ] **Complete data safety form**
  - Data collection practices
  - Data sharing
  - Security practices

### Pre-Launch Testing (Optional but recommended)

- [ ] **Internal testing**
  - Create internal testing track
  - Upload build
  - Test with team (up to 100 testers)

- [ ] **Closed alpha/beta testing**
  - Get feedback from trusted users
  - Fix critical issues before production

## 🚀 Launch Day

### Pre-Launch

- [ ] **Final smoke test**
  - Install production build
  - Test critical paths
  - No show-stopper bugs

- [ ] **Support ready**
  - Email support@albunyaantube.com monitored
  - Documentation available
  - FAQ prepared

- [ ] **Monitoring setup**
  - Google Play Console crash reports enabled
  - Analytics dashboard ready (if used)

### Submit for Review

- [ ] **Submit app for review**
  - Click "Send for review" on Play Console
  - Estimated review time: 3-7 days
  - Be prepared to answer questions

### Post-Launch (First 24-48 hours)

- [ ] **Monitor crash reports**
  - Check Play Console → Quality → Crashes
  - Address critical crashes immediately

- [ ] **Monitor reviews**
  - Respond to user reviews promptly
  - Note common issues or requests

- [ ] **Check analytics**
  - Install rate
  - Uninstall rate
  - Crash-free users percentage

- [ ] **Update backend if needed**
  - Monitor API performance
  - Scale if necessary

## 📊 Success Metrics

Track these metrics after launch:

- **Installs**: Target 100+ in first week
- **Rating**: Maintain > 4.0 stars
- **Crash-free users**: > 99%
- **Uninstall rate**: < 5%
- **User retention**: Day 1, Day 7, Day 30

## 🐛 Common Issues & Solutions

### Issue: App rejected for copyright
**Solution**: Ensure all content is properly licensed, add content attribution

### Issue: Crashes on older Android versions
**Solution**: Test on minimum SDK device, add compatibility checks

### Issue: Large APK/AAB size
**Solution**: Already optimized with R8 ✅, remove unused resources

### Issue: Store listing rejected
**Solution**: Ensure screenshots show real app, no misleading claims

## ✅ Final Sign-Off

Before clicking "Submit for review":

- [ ] All checklist items above completed
- [ ] App tested thoroughly on multiple devices
- [ ] Store listing accurate and complete
- [ ] Support infrastructure ready
- [ ] Team informed of launch

**Signed off by**: _________________
**Date**: _________________

---

**Good luck with the launch! 🚀**

*JazakAllahu Khairan for bringing Islamic knowledge to millions through technology!* 🕌
