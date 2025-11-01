# Android App Connectivity Troubleshooting

> **Context:** Backend CORS issue fixed on Oct 31, 2025. This guide helps diagnose remaining connectivity issues.

---

## ✅ **What Was Fixed (Oct 31, 2025)**

### **Problem: CORS Blocking Mobile Requests**
- Backend CORS configuration only allowed web frontend (`localhost:5173`)
- Mobile apps don't send `Origin` header like browsers
- Android app HTTP requests were being rejected

### **Solution Applied:**
- **File:** `backend/src/main/java/com/albunyaan/tube/security/SecurityConfig.java:87-91`
- Added `configuration.setAllowedOriginPatterns(List.of("*"))` for mobile compatibility
- Changed `configuration.setAllowCredentials(false)` (mobile apps don't use credentials)
- Backend rebuilt and restarted with fix

---

## 🔍 **Quick Diagnostics**

### **1. Check Backend is Running**

```bash
# Check if backend process is running
ps aux | grep AlbunyaanTubeApplication

# Expected output: Should show Java process with AlbunyaanTubeApplication

# If not running, start it:
cd /home/farouq/Development/albunyaantube/backend
./gradlew bootRun --args='--spring.profiles.active=seed' &
```

### **2. Verify Backend Accessibility**

```bash
# Test from localhost
curl http://localhost:8080/api/v1/categories
# Should return: JSON array with 19 categories

# Test from your device IP (192.168.1.167)
curl http://192.168.1.167:8080/api/v1/categories
# Should return: Same JSON array

# If this fails, check:
# - Firewall blocking port 8080
# - Backend not listening on 0.0.0.0 (all interfaces)
```

### **3. Check Android APK Configuration**

```bash
# Verify API_BASE_URL in build.gradle.kts
grep -A1 "API_BASE_URL" /home/farouq/Development/albunyaantube/android/app/build.gradle.kts

# Should show:
# buildConfigField("String", "API_BASE_URL", "\"http://192.168.1.167:8080/\"")

# If wrong, rebuild:
cd /home/farouq/Development/albunyaantube/android
./gradlew assembleDebug
```

### **4. Monitor Android App Logs**

```bash
# Connect device and watch logs
adb devices
adb logcat -c  # Clear old logs
adb logcat | grep -E "OkHttp|Retrofit|ContentApi|CORS|Connection"

# Expected successful logs:
# D/OkHttp: --> GET http://192.168.1.167:8080/api/v1/categories
# D/OkHttp: <-- 200 OK http://192.168.1.167:8080/api/v1/categories (234ms)

# Problem indicators:
# - ConnectException: Connection refused → Backend not reachable
# - UnknownHostException: Unable to resolve host → DNS/network issue
# - 403 Forbidden → CORS or security issue
# - Timeout → Firewall or network latency
```

---

## 🐛 **Common Issues & Solutions**

### **Issue 1: "Connection Refused" / "Unable to Connect"**

**Symptoms:**
- App shows empty screens or "No connection" message
- Logcat shows: `ConnectException: Connection refused`

**Diagnosis:**
```bash
# 1. Check if backend is running
curl http://192.168.1.167:8080/api/v1/categories

# 2. Check if backend is listening on all interfaces
netstat -tuln | grep 8080
# Should show: 0.0.0.0:8080 (not 127.0.0.1:8080)

# 3. Check firewall
sudo ufw status
```

**Solutions:**
- **Backend not running:** Start with `./gradlew bootRun`
- **Firewall blocking:** `sudo ufw allow 8080/tcp`
- **Backend only listening on localhost:**
  Check `application.yml` for `server.address: 0.0.0.0` (default is all interfaces)

---

### **Issue 2: "403 Forbidden" / CORS Errors**

**Symptoms:**
- Backend logs show: `❌ ACCESS DENIED to /api/v1/content`
- Logcat shows: `HTTP 403 Forbidden`

**Diagnosis:**
```bash
# Test CORS with mobile origin
curl -H "Origin: http://android-app" \
     -H "Access-Control-Request-Method: GET" \
     -X OPTIONS \
     http://192.168.1.167:8080/api/v1/categories

# Should return:
# Access-Control-Allow-Origin: *
# Access-Control-Allow-Methods: GET, POST, PUT, DELETE, PATCH, OPTIONS
```

**Solutions:**
- **CORS issue:** Verify `SecurityConfig.java` has `setAllowedOriginPatterns("*")`
- **Backend not restarted:** Kill backend and restart with new config
- **Old APK installed:** Rebuild APK and reinstall

---

### **Issue 3: Wrong Network / Device Not on Same WiFi**

**Symptoms:**
- `UnknownHostException` or timeout errors
- Backend works on localhost but not from device

**Diagnosis:**
```bash
# Check your computer's IP address
ip addr show | grep "inet.*192.168"
# Should show: 192.168.1.167 (or similar)

# From Android device (using terminal app):
ping 192.168.1.167
# Should respond with: 64 bytes from 192.168.1.167

# Check device is on same network:
adb shell ip addr | grep "inet.*192.168"
# Should show device IP like: 192.168.1.xxx
```

**Solutions:**
- **Device on wrong network:** Connect to same WiFi as computer
- **IP changed:** Update `android/app/build.gradle.kts` with new IP and rebuild
- **Router isolating clients:** Disable "AP Isolation" in router settings

---

### **Issue 4: App Shows Fake Data Instead of Backend Data**

**Symptoms:**
- App works but shows placeholder/hardcoded data
- Logcat shows: "Falling back to fake content service"

**Diagnosis:**
```bash
# Check if RetrofitContentService is being used
adb logcat | grep -E "FallbackContentService|RetrofitContentService"

# Should see:
# "Using RetrofitContentService"
# NOT "Falling back to FakeContentService"
```

**Cause:**
- App has `FallbackContentService` that uses `FakeContentService` if backend fails
- This is a safety feature but shouldn't trigger if backend is working

**Solutions:**
- **Backend unreachable:** Fix connectivity (see issues above)
- **Backend returning errors:** Check backend logs for exceptions
- **Wrong API endpoint:** Verify `API_BASE_URL` in `build.gradle.kts`

---

### **Issue 5: Slow Loading / Timeouts**

**Symptoms:**
- App takes very long to load data
- Timeout errors in logcat

**Diagnosis:**
```bash
# Test backend response time
time curl http://192.168.1.167:8080/api/v1/categories

# Should complete in < 1 second
# If > 5 seconds, backend is slow or network has issues

# Check OkHttp timeout settings in ServiceLocator.kt
grep -A3 "connectTimeout" /home/farouq/Development/albunyaantube/android/app/src/main/java/com/albunyaan/tube/ServiceLocator.kt
```

**Solutions:**
- **Slow backend:** Check backend CPU/memory usage
- **Network latency:** Use WiFi instead of mobile data
- **Increase timeouts:** Adjust OkHttp timeouts in `ServiceLocator.kt`

---

## 📱 **Device-Specific Issues**

### **Emulator:**
- Use `http://10.0.2.2:8080/` (special alias for host's localhost)
- Update `build.gradle.kts` and rebuild APK

### **Physical Device via USB:**
- Use computer's local network IP (192.168.1.167)
- Ensure device is on same WiFi network

### **Physical Device via WiFi ADB:**
- Same as USB, use local network IP
- Connect: `adb connect <device-ip>:5555`

---

## 🔧 **Complete Reset Procedure**

If all else fails, try this complete reset:

```bash
# 1. Stop all processes
pkill -f AlbunyaanTubeApplication
pkill -f gradlew

# 2. Verify your IP hasn't changed
ip addr show | grep "inet.*192.168"

# 3. Update Android build config if IP changed
# Edit: android/app/build.gradle.kts:35
# Set: buildConfigField("String", "API_BASE_URL", "\"http://<YOUR-IP>:8080/\"")

# 4. Clean build Android
cd /home/farouq/Development/albunyaantube/android
./gradlew clean
./gradlew assembleDebug

# 5. Rebuild backend with CORS fix
cd ../backend
./gradlew clean build -x test

# 6. Start backend fresh
./gradlew bootRun --args='--spring.profiles.active=seed' &

# 7. Wait for backend to start (check logs)
tail -f /tmp/backend.log  # If logging to file
# Or: ps aux | grep AlbunyaanTubeApplication

# 8. Verify backend works
curl http://192.168.1.167:8080/api/v1/categories | jq '. | length'
# Should return: 19

# 9. Uninstall old app from device
adb uninstall com.albunyaan.tube

# 10. Install new APK
adb install /home/farouq/Development/albunyaantube/android/app/build/outputs/apk/debug/app-debug.apk

# 11. Launch app and monitor logs
adb logcat -c
adb logcat | grep -E "OkHttp|ContentApi"
```

---

## ✅ **Verification Checklist**

After fixing connectivity, verify these work:

- [ ] Backend accessible: `curl http://192.168.1.167:8080/api/v1/categories`
- [ ] CORS allows mobile: `curl -H "Origin: http://android-app" http://192.168.1.167:8080/api/v1/categories`
- [ ] Android device on same network: `adb shell ip addr | grep 192.168`
- [ ] APK has correct IP: `grep API_BASE_URL android/app/build.gradle.kts`
- [ ] App shows backend data (not fake data)
- [ ] All tabs load: Home, Channels, Playlists, Videos
- [ ] Search works
- [ ] Categories work

---

## 📞 **Getting Help**

If issues persist:

1. **Check Backend Logs:**
   ```bash
   # Look for errors in backend output
   grep -i "error\|exception\|failed" /tmp/backend.log
   ```

2. **Check Android Logs:**
   ```bash
   # Save full logcat
   adb logcat > /tmp/android-logcat.txt
   # Search for: ConnectException, CORS, 403, 404, timeout
   ```

3. **Provide Debugging Info:**
   - Backend version: `git log -1 --oneline`
   - Android APK build time: `ls -lh android/app/build/outputs/apk/debug/app-debug.apk`
   - Your IP: `ip addr show | grep "inet.*192.168"`
   - Network test: `curl http://192.168.1.167:8080/api/v1/categories`
   - Logcat errors: `adb logcat | grep -E "ERROR|Exception"`

---

**Last Updated:** 2025-10-31
**CORS Fix Commit:** `efb0205` [FIX]: Enable Android app connectivity to backend
