# Physical Device Testing Guide

**Last Updated**: October 4, 2025
**Your Computer IP**: `192.168.1.167`

## ✅ **Quick Start - App is Already Installed!**

The app is **already installed** on your device (XTX7N18806000846) and ready to use!

### **What You Need to Do:**

1. **Make sure backend is running:**
   ```bash
   cd /home/farouq/Development/albunyaantube/backend
   ./gradlew bootRun
   ```

2. **Ensure your phone is on the same WiFi network as your computer**
   - Both must be connected to the same WiFi (e.g., your home WiFi)

3. **Open the app on your phone:**
   - Look for "Albunyaan Tube" app
   - Tap to open
   - Skip the onboarding screens
   - Navigate to the **Home** tab

4. **You should see real data!** 🎉
   - Channels section (horizontal scroll)
   - Playlists section (horizontal scroll)
   - Videos section (horizontal scroll)

---

## 🔧 Configuration Details

### **Current Setup:**
- **Backend URL**: `http://192.168.1.167:8080`
- **Your Computer IP**: `192.168.1.167`
- **Backend Port**: `8080`
- **Device**: XTX7N18806000846 (Connected)

### **Network Requirements:**
- ✅ Phone and computer on **same WiFi network**
- ✅ Backend running on your computer
- ✅ Firewall allows port 8080 (usually no issue on home networks)

---

## 🐛 Troubleshooting

### **Problem: App shows empty screens or loading forever**

**Solution 1: Check WiFi**
- Make sure your phone is on the same WiFi as your computer
- Try opening `http://192.168.1.167:8080/api/v1/content?type=HOME` in your phone's browser
  - If it shows JSON data → Backend is reachable ✅
  - If it times out → Network issue ❌

**Solution 2: Check Backend**
```bash
# On your computer, verify backend is running:
curl http://192.168.1.167:8080/api/v1/content?type=HOME

# Should return JSON with videos/channels/playlists
```

**Solution 3: Restart the App**
```bash
# Force stop and restart:
adb -s XTX7N18806000846 shell am force-stop com.albunyaan.tube
adb -s XTX7N18806000846 shell am start -n com.albunyaan.tube/.ui.MainActivity
```

**Solution 4: Check Logs**
```bash
# See what the app is doing:
adb -s XTX7N18806000846 logcat | grep -E "HomeFragment|Retrofit|OkHttp"
```

---

## 📱 Reinstalling the App

If you need to reinstall:

```bash
cd /home/farouq/Development/albunyaantube/android
./gradlew assembleDebug
adb -s XTX7N18806000846 install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 🔄 Switching Between Emulator and Physical Device

### **For Emulator:**
Edit `android/app/build.gradle.kts`:
```kotlin
buildConfigField("String", "API_BASE_URL", "\"http://10.0.2.2:8080/\"")
```

### **For Physical Device:**
Edit `android/app/build.gradle.kts`:
```kotlin
buildConfigField("String", "API_BASE_URL", "\"http://192.168.1.167:8080/\"")
```

After changing, rebuild:
```bash
cd android && ./gradlew assembleDebug
```

---

## 🌐 **Alternative: Use Mock Data (No Backend)**

The app has a built-in fallback mechanism. If it can't reach the backend, it will automatically show **mock data** instead.

To test this:
1. Turn off WiFi on your phone
2. Open the app
3. You'll see placeholder content (still functional)

This is handled by `FallbackContentService` which tries the real backend first, then falls back to fake data.

---

## 📊 What Data You'll See

When connected to the backend, the **Home screen** shows:

**Channels Section:**
- "Quran Recitation - Sample" (100K subscribers)
- "Hadith Studies - Sample" (75K subscribers)

**Playlists Section:**
- "Complete Quran - Sample" (30 items)
- "Sahih Bukhari - Sample" (20 items)

**Videos Section:**
- "Surah Al-Fatiha - Sample" (5 min, 50K views)
- "40 Hadith - Lesson 1" (15 min, 25K views)
- "Islamic History - The Golden Age" (30 min, 35K views)

All data comes from **Firestore** → **Spring Boot** → **Android App**

---

## ✅ Current Status

- [x] Backend running on `http://192.168.1.167:8080`
- [x] APK configured with correct IP address
- [x] App installed on device (XTX7N18806000846)
- [x] App launched successfully
- [x] Ready to test with real data!

---

## 🚀 Next Steps

1. **Open the app on your phone** and navigate to the Home tab
2. **Verify real data is loading** (you should see the content listed above)
3. **Test scrolling** in the horizontal lists
4. **Try "See all" buttons** (they navigate to the full lists)

**Enjoy testing with real backend data!** 🎉

---

**Note**: If your computer's IP address changes (after reboot or network change), you'll need to:
1. Update the IP in `android/app/build.gradle.kts`
2. Rebuild the APK
3. Reinstall on your device

To check your current IP: `hostname -I | awk '{print $1}'`
