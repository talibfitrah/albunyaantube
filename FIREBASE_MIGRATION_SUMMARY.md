# Firebase Migration Summary

## Overview

This document summarizes the complete migration from PostgreSQL/JPA to Firebase (Firestore + Authentication) completed under tickets **FIREBASE-MIGRATE-01** through **FIREBASE-MIGRATE-04**.

---

## Migration Scope

### What Changed

| Component | Before | After |
|-----------|--------|-------|
| **Database** | PostgreSQL 15 + JPA | Firebase Firestore |
| **Authentication** | Custom JWT + Spring Security | Firebase Authentication + Spring Security |
| **Category Model** | Embedded subcategories in JSONB | Parent-child with `parentCategoryId` |
| **YouTube Integration** | Not implemented | YouTube Data API v3 fully integrated |
| **User Management** | JPA entities + database tables | Firebase Auth + Firestore sync |

### What Stayed the Same

- Spring Boot 3 framework
- Redis for caching
- REST API architecture
- Vue.js admin frontend (needs Firebase SDK integration)
- Android app architecture (no changes needed)

---

## Architecture Changes

### Backend Structure

**Old Stack:**
```
Spring Boot + PostgreSQL + Flyway + JPA
├── JPA Entities (User, Category, Channel, etc.)
├── JPA Repositories
├── Custom JWT authentication
└── Flyway migrations
```

**New Stack:**
```
Spring Boot + Firebase (Firestore + Auth) + YouTube API
├── Firestore Document Models
├── Firestore Repositories (async with ApiFuture)
├── Firebase Authentication (token verification)
├── YouTube Data API client
└── No migrations needed (schema-less)
```

### Data Model Changes

#### Category Hierarchy

**Before (Embedded):**
```java
class Category {
    UUID id;
    String slug;
    List<Subcategory> subcategories; // Embedded list
}
```

**After (Parent-Child):**
```java
class Category {
    String id;
    String name;
    String parentCategoryId; // null for top-level
    Map<String, String> localizedNames;
}
```

#### Channel Exclusions

**Before:**
```java
class Channel {
    List<String> excludedVideoIds;
    List<String> excludedPlaylistIds;
}
```

**After:**
```java
class Channel {
    ExcludedItems excludedItems {
        List<String> videos;
        List<String> liveStreams;
        List<String> shorts;
        List<String> playlists;
        List<String> posts;
    }
}
```

---

## Implementation Details

### FIREBASE-MIGRATE-01: Project Setup

**Files Created:**
- `backend/FIREBASE_SETUP.md` - Complete Firebase setup guide
- `backend/src/main/resources/firebase-service-account.json.template`
- `backend/src/main/java/com/albunyaan/tube/config/FirebaseConfig.java`
- `backend/src/main/java/com/albunyaan/tube/config/FirebaseProperties.java`

**Dependencies Added:**
```gradle
implementation("com.google.firebase:firebase-admin:9.2.0")
implementation("com.google.cloud:google-cloud-firestore:3.15.8")
implementation("com.google.apis:google-api-services-youtube:v3-rev20240916-2.0.0")
```

**Dependencies Removed:**
```gradle
implementation("org.springframework.boot:spring-boot-starter-data-jpa")
implementation("org.flywaydb:flyway-core:10.14.0")
implementation("org.postgresql:postgresql")
implementation("io.jsonwebtoken:jjwt-api:0.12.5")
```

### FIREBASE-MIGRATE-02: Authentication

**Files Created:**
- `backend/src/main/java/com/albunyaan/tube/security/FirebaseAuthFilter.java`
- `backend/src/main/java/com/albunyaan/tube/security/FirebaseUserDetails.java`
- `backend/src/main/java/com/albunyaan/tube/security/SecurityConfig.java`
- `backend/src/main/java/com/albunyaan/tube/service/AuthService.java`

**Key Features:**
- Validates Firebase ID tokens on each request
- Extracts custom claims (`role: admin|moderator`)
- Auto-creates initial admin user on startup
- Syncs user data between Firebase Auth and Firestore

### FIREBASE-MIGRATE-03: Data Models & Repositories

**Models Created:**
- `Category.java` - With parentCategoryId hierarchy
- `Channel.java` - With excludedItems structure
- `Playlist.java` - With excludedVideoIds
- `Video.java` - Individual video entries
- `User.java` - User metadata (synced with Firebase Auth)

**Repositories Created:**
- `CategoryRepository.java` - Hierarchical category queries
- `ChannelRepository.java` - Status-based filtering
- `UserRepository.java` - UID-based lookups

**Controllers Created:**
- `CategoryController.java` - Category CRUD with RBAC
- `ChannelController.java` - Channel approval workflow
- `YouTubeSearchController.java` - YouTube search/preview

### FIREBASE-MIGRATE-04: YouTube Integration

**Files Created:**
- `backend/src/main/java/com/albunyaan/tube/service/YouTubeService.java`

**Capabilities:**
- Search channels, playlists, videos
- Get channel details and content tabs (videos, playlists, live, shorts)
- Get playlist details and videos
- Get video metadata
- Pagination support with page tokens

---

## Configuration Changes

### application.yml

**Removed:**
```yaml
datasource:
  url: jdbc:postgresql://localhost:5432/albunyaan
  username: albunyaan
  password: changeme
jpa:
  hibernate:
    ddl-auto: validate
flyway:
  enabled: true
security:
  jwt:
    secret: ...
```

**Added:**
```yaml
firebase:
  service-account-path: ${FIREBASE_SERVICE_ACCOUNT_PATH:classpath:firebase-service-account.json}
  project-id: ${FIREBASE_PROJECT_ID:albunyaan-tube}
  firestore:
    database-id: ${FIRESTORE_DATABASE_ID:(default)}
youtube:
  api-key: ${YOUTUBE_API_KEY:}
  application-name: Albunyaan Tube Admin
```

---

## API Endpoints

### New Endpoints

**Categories:**
- `GET /api/admin/categories` - List all categories
- `GET /api/admin/categories/top-level` - Get top-level categories
- `GET /api/admin/categories/{parentId}/subcategories` - Get subcategories
- `POST /api/admin/categories` - Create category (admin only)
- `PUT /api/admin/categories/{id}` - Update category (admin only)
- `DELETE /api/admin/categories/{id}` - Delete category (admin only)

**Channels:**
- `GET /api/admin/channels?status=pending` - List channels by status
- `GET /api/admin/channels/category/{categoryId}` - Get channels by category
- `POST /api/admin/channels` - Submit channel (moderator/admin)
- `PUT /api/admin/channels/{id}/approve` - Approve channel (admin only)
- `PUT /api/admin/channels/{id}/reject` - Reject channel (admin only)
- `PUT /api/admin/channels/{id}/exclusions` - Update exclusions (admin only)

**YouTube Search:**
- `GET /api/admin/youtube/search/channels?query=...`
- `GET /api/admin/youtube/search/playlists?query=...`
- `GET /api/admin/youtube/search/videos?query=...`
- `GET /api/admin/youtube/channels/{channelId}`
- `GET /api/admin/youtube/channels/{channelId}/videos`
- `GET /api/admin/youtube/channels/{channelId}/playlists`
- `GET /api/admin/youtube/playlists/{playlistId}`
- `GET /api/admin/youtube/playlists/{playlistId}/videos`
- `GET /api/admin/youtube/videos/{videoId}`

---

## Security Model

### Authentication Flow

1. User logs in via Firebase Auth (email/password)
2. Firebase returns ID token (JWT)
3. Frontend sends ID token in `Authorization: Bearer <token>` header
4. `FirebaseAuthFilter` validates token via Firebase Admin SDK
5. Extracts custom claims (`role: admin|moderator`)
6. Sets Spring Security authentication context
7. Controllers enforce RBAC via `@PreAuthorize`

### Role-Based Access

| Endpoint | Admin | Moderator | Public |
|----------|-------|-----------|--------|
| Category Read | ✅ | ✅ | ✅ |
| Category Write | ✅ | ❌ | ❌ |
| Channel Submit | ✅ | ✅ | ❌ |
| Channel Approve | ✅ | ❌ | ❌ |
| YouTube Search | ✅ | ✅ | ❌ |
| User Management | ✅ | ❌ | ❌ |

---

## Migration Steps for Deployment

### 1. Firebase Project Setup

Follow instructions in [backend/FIREBASE_SETUP.md](backend/FIREBASE_SETUP.md):
- Create Firebase project
- Enable Firestore
- Enable Firebase Authentication
- Generate service account key
- Enable YouTube Data API

### 2. Environment Variables

Set these in production:
```bash
export FIREBASE_SERVICE_ACCOUNT_PATH=/path/to/firebase-service-account.json
export FIREBASE_PROJECT_ID=albunyaan-tube
export YOUTUBE_API_KEY=your-youtube-api-key
```

### 3. Data Migration (if migrating from PostgreSQL)

**Manual migration required:**
1. Export PostgreSQL data
2. Transform to Firestore document format
3. Import using Firebase Admin SDK batch writes

**Categories:**
```javascript
// Transform from JPA entity to Firestore
{
  name: category.name,
  parentCategoryId: null, // or parent ID
  localizedNames: {"en": name, "ar": arabicName},
  displayOrder: 0,
  createdAt: Timestamp.now()
}
```

**Channels:**
```javascript
{
  youtubeId: channel.ytId,
  categoryIds: [categoryId1, categoryId2],
  status: "approved",
  excludedItems: {
    videos: [],
    liveStreams: [],
    shorts: [],
    playlists: [],
    posts: []
  }
}
```

### 4. Frontend Update

**Install Firebase SDK:**
```bash
cd frontend
npm install firebase
```

**Initialize Firebase:**
```typescript
import { initializeApp } from 'firebase/app';
import { getAuth } from 'firebase/auth';

const firebaseConfig = {
  apiKey: "...",
  authDomain: "albunyaan-tube.firebaseapp.com",
  projectId: "albunyaan-tube",
};

const app = initializeApp(firebaseConfig);
export const auth = getAuth(app);
```

**Update Auth Store:**
```typescript
import { signInWithEmailAndPassword, signOut } from 'firebase/auth';
import { auth } from '@/config/firebase';

async function login(email: string, password: string) {
  const userCredential = await signInWithEmailAndPassword(auth, email, password);
  const idToken = await userCredential.user.getIdToken();
  // Send idToken to backend in Authorization header
}
```

---

## Testing

### Manual Testing Checklist

**Backend:**
- [ ] Firebase initializes successfully
- [ ] Initial admin user created
- [ ] Authentication validates tokens correctly
- [ ] Category CRUD works with hierarchy
- [ ] Channel submission/approval workflow
- [ ] YouTube search returns results
- [ ] Firestore queries execute without errors

**Frontend:**
- [ ] Login with Firebase Auth
- [ ] ID token sent in API requests
- [ ] Admin can create categories
- [ ] Moderator can submit channels
- [ ] YouTube search UI functional
- [ ] Channel expansion shows YouTube content

**Integration:**
- [ ] End-to-end category creation
- [ ] End-to-end channel approval
- [ ] YouTube search → Add to master list → Approve

---

## Rollback Plan

If migration fails, revert to previous PostgreSQL-based system:

1. Checkout previous commit: `git checkout <commit-before-migration>`
2. Restore PostgreSQL database from backup
3. Restart backend with old configuration
4. Frontend continues to work (no changes made yet)

**Commit to rollback to:** `cb930f0` (before FIREBASE-MIGRATE-01)

---

## Known Issues & Limitations

1. **No data migration script:** Manual migration from PostgreSQL required
2. **Frontend not updated:** Still using old auth approach (needs Firebase SDK)
3. **No email service:** Password reset emails not sent (just logged)
4. **Async overhead:** Firestore queries use `ExecutionException` handling
5. **No batch operations:** Controllers don't support bulk imports yet

---

## Next Steps

### Immediate (Required for Launch)
1. Set up actual Firebase project
2. Update frontend with Firebase Auth SDK
3. Test end-to-end authentication flow
4. Implement user management UI
5. Add YouTube search UI in admin panel

### Short-term (Phase 2)
6. Implement batch channel/playlist import
7. Add activity logging (activityLogs collection)
8. Add moderation proposals workflow
9. Implement email notifications
10. Add Firestore indexes for performance

### Long-term (Phase 3)
11. Add Firebase Storage for category icons
12. Implement real-time listeners for live updates
13. Add Firebase Functions for background tasks
14. Optimize Firestore queries with composite indexes
15. Add Firebase Analytics for admin actions

---

## Support & Resources

- **Firebase Setup Guide:** [backend/FIREBASE_SETUP.md](backend/FIREBASE_SETUP.md)
- **System Prompt:** [docs/prompt/complete_system_prompt.md](docs/prompt/complete_system_prompt.md)
- **Architecture Docs:** [docs/architecture/solution-architecture.md](docs/architecture/solution-architecture.md)
- **Firebase Console:** https://console.firebase.google.com/
- **YouTube API Console:** https://console.cloud.google.com/

---

## Contributors

Migration completed under tickets FIREBASE-MIGRATE-01 through FIREBASE-MIGRATE-04.

**Commit:** `0f45261` (Firebase backend migration)
**Date:** 2025-10-03
**Branch:** `main`
