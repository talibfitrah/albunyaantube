# Implementation Priorities - Updated Oct 29, 2025

> **Decision:** Complete Android app first, then Import/Export, then remaining admin features

---

## Priority 1: Complete Android Mobile App (4-6 days)

### Current Status
- **UI:** 16 screens fully built ✅
- **Backend Integration:** Categories, Search, Public Content API connected ✅
- **Data:** 20 channels, 16 playlists, 76 videos seeded ✅
- **Status:** Likely working, needs verification

### What's Already Working
1. ✅ Splash Screen - Auto-navigation
2. ✅ Onboarding - 3-page carousel
3. ✅ Main Shell - Bottom navigation (5 tabs)
4. ✅ Settings - DataStore preferences
5. ✅ Categories - Backend connected (shows 19 categories)
6. ✅ Subcategories - Hierarchical navigation
7. ✅ Search - Searches all content via `/api/v1/search`
8. ✅ Downloads - Infrastructure (WorkManager)

### What Needs Verification/Completion

#### Step 1: Verify Data Appears (Day 1 - 4 hours)
**Task:** Build and run Android app to confirm seeded data appears

**Test Checklist:**
- [ ] Home tab shows mixed content (channels + playlists + videos)
- [ ] Channels tab shows 20 approved channels
- [ ] Playlists tab shows 16 approved playlists
- [ ] Videos tab shows 76 videos
- [ ] Category filtering works
- [ ] Search functionality works
- [ ] Can navigate to detail screens

**Expected Result:** App should show all seeded data from Firestore

**If Data Doesn't Appear:**
- Check Android API base URL (should be `http://10.0.2.2:8080` for emulator)
- Test `/api/v1/content?type=CHANNELS` endpoint manually
- Verify PublicContentService returns approved content only
- Check Retrofit configuration in Android app

#### Step 2: Fix Content Loading Issues (Days 1-2 - 1-2 days)
**Potential Issues to Fix:**

1. **PublicContentService Filtering**
   - Location: `backend/src/main/java/com/albunyaan/tube/service/PublicContentService.java`
   - Verify: Returns only approved content (status = "APPROVED")
   - Verify: Pagination cursor logic works correctly
   - Verify: HOME type returns mixed content

2. **Android Retrofit Configuration**
   - Location: `android/app/src/main/java/com/albunyaan/tube/data/service/`
   - Verify: Base URL correct for emulator (`http://10.0.2.2:8080`)
   - Verify: JSON parsing handles backend response format
   - Verify: Error handling shows useful messages

3. **Content Adapters**
   - Location: `android/app/src/main/java/com/albunyaan/tube/ui/`
   - Verify: RecyclerView adapters handle empty states
   - Verify: Thumbnail loading works (Glide/Coil)
   - Verify: Click listeners navigate correctly

#### Step 3: Complete Detail Screens (Day 3 - 1 day)

1. **Channel Detail Screen**
   - Endpoint: `/api/v1/channels/{id}`
   - Shows: Channel info, playlists, recent videos
   - Actions: Subscribe, view playlists

2. **Playlist Detail Screen**
   - Endpoint: `/api/v1/playlists/{id}`
   - Shows: Playlist info, video list
   - Actions: Play all, download

#### Step 4: Complete Video Player (Day 4 - 1 day)

1. **Player Integration**
   - Current: NewPipe extractor exists
   - Needs: Test with real video IDs from seeded data
   - Test: Playback controls, seeking, quality selection
   - Test: Picture-in-Picture mode
   - Test: Background audio playback

2. **Download Functionality**
   - Current: WorkManager infrastructure exists
   - Needs: Wire up to player
   - Test: Download video for offline viewing
   - Test: Download progress notifications
   - Test: Offline playback from downloads

#### Step 5: Polish & End-to-End Testing (Days 5-6 - 2 days)

**Test Flows:**
1. Category → Subcategory → Filtered Content → Player
2. Search → Result → Player
3. Home → Channel Detail → Playlist → Player
4. Downloads → Offline Player
5. Settings → Language change → UI updates

**Polish:**
- Loading states (skeletons, shimmer effects)
- Empty states (no content messages)
- Error states (network errors, API failures)
- RTL support for Arabic
- Accessibility (content descriptions, screen reader support)

---

## Priority 2: Build Bulk Import/Export Feature (3 days)

### Current Status
- **Frontend:** UI exists but calls no backend ❌
- **Backend:** No endpoints ❌
- **Status:** Needs complete implementation

### Implementation Plan

#### Backend Endpoints (Days 1-2)

**Create:** `BulkImportExportController.java`

##### Import Endpoints
```
POST /api/admin/import/channels
POST /api/admin/import/categories
POST /api/admin/import/playlists
POST /api/admin/import/videos
```

**Features:**
- Accept CSV file upload
- Parse CSV with headers: `youtubeId, name, description, categoryIds, status`
- Validate each row (check for duplicates, valid YouTube IDs)
- Return success/error report
- Log import via AuditLog

##### Export Endpoints
```
GET /api/admin/export/channels
GET /api/admin/export/categories
GET /api/admin/export/playlists
GET /api/admin/export/videos
```

**Features:**
- Query all items of given type
- Generate CSV with all fields
- Include metadata (approvedBy, createdAt, etc.)
- Return CSV file for download

#### Frontend Integration (Day 3)

**Update:** `frontend/src/views/ImportExportView.vue`

**Features:**
- File upload dropzone
- CSV template download
- Import progress indicator
- Success/error summary table
- Export button with file download

**CSV Format Example:**
```csv
youtubeId,name,description,categoryIds,status
UCxxxxxx,Quran Path,Daily Quranic recitations,"quran,tajweed",APPROVED
UCyyyyyy,Islamic Lectures,Scholars and lectures,hadith,PENDING
```

---

## Priority 3: Complete Remaining Admin Features (3-4 days)

### Feature 1: Settings Persistence (3 days)

**Missing:**
- Notifications Settings backend
- YouTube API Settings backend
- System Settings backend

**Implementation:**

1. **Create Settings Model**
   ```java
   // backend/src/main/java/com/albunyaan/tube/model/SystemSettings.java
   public class SystemSettings {
       @DocumentId private String id; // "system-config"
       private Map<String, Object> settings;
       private Timestamp updatedAt;
       private String updatedBy;
   }
   ```

2. **Create Settings Controller**
   ```
   GET /api/admin/settings/system
   PUT /api/admin/settings/system
   GET /api/admin/settings/notifications
   PUT /api/admin/settings/notifications
   GET /api/admin/settings/youtube-api
   PUT /api/admin/settings/youtube-api
   ```

3. **Wire Frontend**
   - Update: `frontend/src/views/SystemSettingsView.vue`
   - Update: `frontend/src/views/NotificationsSettingsView.vue`
   - Update: `frontend/src/views/YouTubeAPISettingsView.vue`
   - Replace localStorage with API calls

### Feature 2: Exclusions Management (3 days)

**Missing:**
- Complete backend (currently shows "not implemented" warnings)

**Implementation:**

1. **Exclusions Model Already Exists**
   - Channel.ExcludedItems has excludedVideos, excludedPlaylists
   - Just needs controller endpoints

2. **Create Exclusions Controller**
   ```
   GET /api/admin/channels/{channelId}/exclusions
   POST /api/admin/channels/{channelId}/exclusions/videos/{videoId}
   DELETE /api/admin/channels/{channelId}/exclusions/videos/{videoId}
   POST /api/admin/channels/{channelId}/exclusions/playlists/{playlistId}
   DELETE /api/admin/channels/{channelId}/exclusions/playlists/{playlistId}
   ```

3. **Wire Frontend**
   - Update: `frontend/src/views/ExclusionsView.vue`
   - Add API service calls
   - Remove "not implemented" warnings

---

## Timeline Summary

| Priority | Feature | Days | Cumulative |
|----------|---------|------|------------|
| **1** | Complete Android App | 4-6 | **6 days** |
| **2** | Bulk Import/Export | 3 | **9 days** |
| **3** | Settings Persistence | 3 | **12 days** |
| **3** | Exclusions Management | 3 | **15 days** |

**Total Time to Complete All Priorities: ~3 weeks**

---

## Success Criteria

### Android App Complete When:
- [ ] All 16 screens show real data
- [ ] Can browse categories → subcategories → content
- [ ] Can search and find content
- [ ] Can play videos with NewPipe extractor
- [ ] Can download videos for offline playback
- [ ] RTL support works for Arabic
- [ ] All navigation flows work end-to-end

### Import/Export Complete When:
- [ ] Can upload CSV with channels/playlists/videos
- [ ] Import validates data and reports errors
- [ ] Can export all content types to CSV
- [ ] CSV format is well-documented
- [ ] Import creates audit log entries

### Admin Features Complete When:
- [ ] Settings persist across sessions
- [ ] Can manage exclusions for channels
- [ ] All 17 admin views fully functional
- [ ] No "not implemented" warnings remain

---

## Next Immediate Step

**Start Here:** Verify Android app shows seeded data

1. Open Android Studio
2. Build and run app on emulator
3. Check all tabs (Home, Channels, Playlists, Videos)
4. Test search and category filtering
5. Report findings

If data appears: Move to Step 3 (detail screens)
If data doesn't appear: Debug PublicContentService and Android API integration

---

**Last Updated:** 2025-10-29
**Status:** Ready to begin Priority 1 (Android App)
