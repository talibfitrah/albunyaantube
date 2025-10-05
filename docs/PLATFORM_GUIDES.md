# Platform Implementation Guides

> Technical guides for Backend, Frontend, and Android platforms

## Backend (Spring Boot + Firebase)

### Firebase Setup

**Prerequisites**:
- Google Cloud project created
- Firebase project enabled
- Firestore database in Standard mode
- Service account key downloaded

**Configuration**:
```bash
# 1. Download service account key
# Go to: Firebase Console > Project Settings > Service Accounts
# Click "Generate New Private Key"
# Save as: backend/src/main/resources/albunyaan-tube-firebase-key.json

# 2. Set environment variable
export GOOGLE_APPLICATION_CREDENTIALS="backend/src/main/resources/albunyaan-tube-firebase-key.json"

# 3. Run backend
cd backend
./gradlew bootRun
```

**Firestore Indexes**:
```bash
# Deploy composite indexes
cd backend
firebase deploy --only firestore:indexes

# View index status
firebase firestore:indexes --project albunyaan-tube
```

**Required Indexes** (defined in `backend/firestore.indexes.json`):
1. `categories`: displayOrder ASC, name ASC
2. `categories`: parentCategoryId ASC, displayOrder ASC
3. `channels`: status ASC, subscribers DESC
4. `playlists`: status ASC, itemCount DESC
5. `videos`: status ASC, uploadedAt DESC

### API Endpoints

**Public Endpoints** (no auth required):
```
GET /api/v1/content?type={HOME|VIDEOS|CHANNELS|PLAYLISTS}&limit={n}
GET /api/v1/categories
GET /api/v1/categories/{id}
```

**Admin Endpoints** (Firebase Auth required):
```
# User Management
GET    /api/v1/admin/users
POST   /api/v1/admin/users
PUT    /api/v1/admin/users/{id}
DELETE /api/v1/admin/users/{id}

# Content Management
GET    /api/v1/admin/channels
POST   /api/v1/admin/channels
PUT    /api/v1/admin/channels/{id}
DELETE /api/v1/admin/channels/{id}

# Similar for playlists, videos, categories
# See: docs/architecture/solution-architecture.md
```

### Status Value Convention

**IMPORTANT**: Always use uppercase "APPROVED"
- ✅ Database: `status = "APPROVED"`
- ✅ Repositories: `whereEqualTo("status", "APPROVED")`
- ✅ Models: Use `equalsIgnoreCase()` for robustness

### Data Seeding

Run seeder on application startup:
```java
// backend/src/main/java/com/albunyaan/tube/util/FirestoreDataSeeder.java
// Automatically runs if collections are empty
```

---

## Frontend (Vue 3 + Firebase)

### Firebase Configuration

**File**: `frontend/src/config/firebase.ts`
```typescript
import { initializeApp } from 'firebase/app'
import { getAuth } from 'firebase/auth'

const firebaseConfig = {
  apiKey: import.meta.env.VITE_FIREBASE_API_KEY,
  authDomain: import.meta.env.VITE_FIREBASE_AUTH_DOMAIN,
  projectId: import.meta.env.VITE_FIREBASE_PROJECT_ID,
  // ... other config
}

export const app = initializeApp(firebaseConfig)
export const auth = getAuth(app)
```

### Authentication Flow

1. User logs in via Firebase Auth UI
2. Frontend gets ID token: `await user.getIdToken()`
3. Send token in Authorization header: `Bearer <token>`
4. Backend validates token via Firebase Admin SDK

### Admin UI

**Development**:
```bash
cd frontend
npm install
npm run dev
# Access: http://localhost:5173
```

**Build**:
```bash
npm run build
# Output: frontend/dist/
```

### Theme Tokens

Dark mode CSS variables defined in `frontend/src/assets/main.css`:
```css
:root {
  --color-background: #ffffff;
  --color-text: #2c3e50;
  /* ... */
}

@media (prefers-color-scheme: dark) {
  :root {
    --color-background: #1a1a1a;
    --color-text: #e0e0e0;
    /* ... */
  }
}
```

---

## Android (Kotlin + Jetpack)

### Development Setup

**Prerequisites**:
- Android Studio Hedgehog or later
- JDK 17
- Android SDK 34
- Emulator or physical device

**Build & Run**:
```bash
cd android
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Network Configuration

**Emulator**: Uses special IP `10.0.2.2` to access host's localhost

**File**: `android/app/src/main/res/xml/network_security_config.xml`
```xml
<network-security-config>
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="true">10.0.2.2</domain>
    </domain-config>
</network-security-config>
```

**Note**: For production, remove cleartext and use HTTPS only.

### Backend Connection

**ServiceLocator** pattern provides ContentService:
```kotlin
// android/app/src/main/java/com/albunyaan/tube/ServiceLocator.kt
object ServiceLocator {
    val contentService: ContentService by lazy {
        FallbackContentService(
            retrofitContentService, // Primary: Real backend
            fakeContentService       // Fallback: Mock data
        )
    }
}
```

**Retrofit Configuration**:
```kotlin
// android/app/src/main/java/com/albunyaan/tube/data/remote/RetrofitContentService.kt
private val retrofit = Retrofit.Builder()
    .baseUrl("http://10.0.2.2:8080/api/v1/")
    .addConverterFactory(GsonConverterFactory.create())
    .build()
```

### Architecture Pattern: MVVM + StateFlow

**Example**: HomeViewModel
```kotlin
class HomeViewModel : ViewModel() {
    private val _homeContent = MutableStateFlow<ContentState>(ContentState.Loading)
    val homeContent: StateFlow<ContentState> = _homeContent.asStateFlow()

    init {
        loadHomeContent()
    }

    private fun loadHomeContent() {
        viewModelScope.launch {
            try {
                val response = contentService.fetchContent(
                    type = ContentType.HOME,
                    pageSize = 20
                )
                _homeContent.value = ContentState.Success(
                    channels = response.data.filterIsInstance<Channel>(),
                    playlists = response.data.filterIsInstance<Playlist>(),
                    videos = response.data.filterIsInstance<Video>()
                )
            } catch (e: Exception) {
                _homeContent.value = ContentState.Error(e.message)
            }
        }
    }
}
```

### Navigation Architecture

**Root Graph**: `app_nav_graph.xml`
- splashFragment
- onboardingFragment
- mainShellFragment (contains nested graph)

**Nested Graph**: `main_tabs_nav.xml`
- homeFragment (start destination)
- categoriesFragment
- subcategoriesFragment
- videosFragment
- playlistsFragment
- channelsFragment
- playerFragment

**Global Actions**: Use `action_global_player` for player navigation from any screen

### Bottom Navigation Visibility

Bottom navbar visible on: Home, Categories, Videos, Playlists, Channels, Player
Hidden on: Splash, Onboarding, Subcategories (detail screen)

```kotlin
// MainShellFragment.kt
navController.addOnDestinationChangedListener { _, destination, _ ->
    when (destination.id) {
        R.id.homeFragment,
        R.id.categoriesFragment,
        R.id.videosFragment,
        R.id.playlistsFragment,
        R.id.channelsFragment,
        R.id.playerFragment -> {
            binding.bottomNavigation.visibility = View.VISIBLE
        }
        else -> {
            binding.bottomNavigation.visibility = View.GONE
        }
    }
}
```

### Scroll Performance

**Disable nested scrolling** to prevent scroll jump:
```kotlin
binding.recyclerView.isNestedScrollingEnabled = false
```

**Prevent over-scroll glow**:
```xml
<RecyclerView
    android:overScrollMode="never" />
```

**Bottom padding for navbar**:
```xml
<RecyclerView
    android:paddingBottom="80dp"
    android:clipToPadding="false" />
```

### Testing on Physical Device

1. Enable USB debugging on device
2. Connect via USB
3. Update API base URL:
```kotlin
// Use computer's local IP (find with `ifconfig` or `ipconfig`)
.baseUrl("http://192.168.x.x:8080/api/v1/")
```

### Release Signing

**Keystore Location**: `android/app/release-keystore.jks`

**Build Release APK**:
```bash
./gradlew assembleRelease
# Output: app/build/outputs/apk/release/app-release.apk
```

**Sign APK**:
```bash
jarsigner -verbose -sigalg SHA256withRSA -digestalg SHA-256 \
  -keystore release-keystore.jks \
  app-release.apk release-key
```

### Play Store Preparation

**Required Assets**:
- App icon: 512x512 PNG
- Feature graphic: 1024x500 PNG
- Screenshots: 5-8 images per device type
- Privacy policy URL
- Store listing description (en/ar/nl)

**Checklist**:
- [ ] Version code incremented
- [ ] Version name updated
- [ ] Release notes written
- [ ] ProGuard enabled
- [ ] APK signed with release key
- [ ] All strings translated
- [ ] Screenshots captured
- [ ] Privacy policy published

See: `docs/android/` folder for detailed guides (to be merged here)

---

## Operations & Runbooks

### Admin Login Flow

1. Navigate to admin UI: http://localhost:5173
2. Click "Sign in with Google" or email/password
3. Frontend calls Firebase Auth
4. On success, get ID token: `user.getIdToken()`
5. Store token in auth store
6. Include in all API requests: `Authorization: Bearer <token>`

### Admin Onboarding

**First-Time Setup**:
1. Create Firebase project
2. Enable Firestore, Authentication
3. Add first admin user via Firebase Console
4. Set custom claim: `role: "admin"`
5. Admin can now create other users via UI

### Performance: Playlist Hydration

**Issue**: Slow playlist queries with large item counts

**Solution**:
- Use composite index: `status ASC, itemCount DESC`
- Implement cursor pagination
- Cache in Redis with 5-minute TTL
- Lazy-load playlist items (don't fetch all at once)

### Backend Search Alignment

**Admin Search** should return blended results:
- Channels, Playlists, Videos in single response
- Include/exclude state for each item
- Support bulk actions
- Respect YouTube API quotas

**Implementation**: Pending (Phase 3)

---

## Development Workflow

### Starting Backend
```bash
# Terminal 1
cd backend
export GOOGLE_APPLICATION_CREDENTIALS="src/main/resources/albunyaan-tube-firebase-key.json"
./gradlew bootRun
# Server: http://localhost:8080
```

### Starting Frontend
```bash
# Terminal 2
cd frontend
npm run dev
# Admin UI: http://localhost:5173
```

### Starting Android
```bash
# Terminal 3 - Start emulator
/home/farouq/Android/Sdk/emulator/emulator -avd Pixel_7_API_33

# Terminal 4 - Build & install
cd android
./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk

# Launch app
adb shell am start -n com.albunyaan.tube/.ui.MainActivity
```

### Quick Smoke Test

**Backend**:
```bash
curl http://localhost:8080/api/v1/content?type=HOME&limit=3
# Should return 3 items (channels/playlists/videos)
```

**Android**:
```bash
adb logcat | grep -i "HomeFragment\|ContentService"
# Should see successful API calls and data loading
```

---

## Troubleshooting

### Backend Issues

**Problem**: "Failed to initialize Firestore"
- Check `GOOGLE_APPLICATION_CREDENTIALS` env var
- Verify service account key file exists
- Ensure Firebase project ID matches

**Problem**: "Index not found" errors
- Run `firebase deploy --only firestore:indexes`
- Wait for indexes to reach READY state (5-10 min)

**Problem**: "Status APPROVED not found"
- Check status values in Firestore (must be uppercase)
- Re-run seeder or manually update documents

### Android Issues

**Problem**: "Unable to resolve host 10.0.2.2"
- Check backend is running on localhost:8080
- Verify network_security_config.xml allows cleartext
- Ensure INTERNET permission in AndroidManifest.xml

**Problem**: "Scroll jump on RecyclerView"
- Disable nested scrolling: `isNestedScrollingEnabled = false`
- Check parent layout isn't also scrollable

**Problem**: "Bottom navbar covering content"
- Add `paddingBottom="80dp"` to scrollable view
- Set `clipToPadding="false"`

### Frontend Issues

**Problem**: "Firebase auth not working"
- Check Firebase config in `.env` file
- Verify auth domain matches Firebase Console
- Clear browser cache/cookies

**Problem**: "Token expired" errors
- Implement token refresh logic
- Call `user.getIdToken(true)` to force refresh
