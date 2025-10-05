# Parallel Work Prompts - Albunyaan Tube

**Date**: 2025-10-05
**Status**: Ready for parallel execution
**Git Branch Strategy**: Each engineer creates feature branch from `main`

---

## 🔴 PROMPT 1: Backend Engineer - Phase 2 & Downloads API

### **Your Mission**
Complete Phase 2 (Registry & Moderation) backend endpoints and Phase 9 (Downloads) backend infrastructure.

### **Branch**: `feature/backend-registry-downloads`

### **Your Boundaries** ✋
- **YOU OWN**: `backend/` directory ONLY
- **DO NOT TOUCH**: `android/`, `frontend/` directories
- **DO NOT MODIFY**: Any Android ViewModels, Fragments, or UI code
- **SHARED FILES TO AVOID**: `docs/PROJECT_STATUS.md` (update only YOUR section), `docs/roadmap/roadmap.md` (read-only)

### **Dependencies You Need**
- Phase 1 backend ✅ (already complete - Firebase Firestore, Auth)
- Phase 8 player ✅ (already complete - provides download requirements)

### **Tasks Breakdown**

#### **BACKEND-REG-01: Registry & Category Management API** (Week 1)
**Ticket Code**: `BACKEND-REG-01`

1. **Category CRUD Endpoints**
   - `GET /api/admin/categories` - List all categories with hierarchy
   - `POST /api/admin/categories` - Create new category
   - `PUT /api/admin/categories/{id}` - Update category
   - `DELETE /api/admin/categories/{id}` - Delete category
   - Support hierarchical parentCategoryId structure

2. **Registry Endpoints**
   - `GET /api/admin/registry/channels` - List all channels in registry
   - `GET /api/admin/registry/playlists` - List all playlists in registry
   - `POST /api/admin/registry/channels` - Add channel to registry
   - `POST /api/admin/registry/playlists` - Add playlist to registry
   - Include/exclude toggle state in responses

3. **Testing**
   - Unit tests for all endpoints
   - Integration tests with Firebase
   - Postman/curl examples in docs

**Commit Format**:
```
BACKEND-REG-01: Implement category and registry management endpoints

- Added CategoryController with CRUD operations
- Added RegistryController for channel/playlist management
- Hierarchical category support with parentCategoryId
- Include/exclude state tracking
- Unit and integration tests

Files Modified:
- backend/src/main/java/com/albunyaan/tube/controller/CategoryController.java
- backend/src/main/java/com/albunyaan/tube/controller/RegistryController.java
- backend/src/main/java/com/albunyaan/tube/service/CategoryService.java
- backend/src/test/java/com/albunyaan/tube/controller/CategoryControllerTest.java

🤖 Generated with [Claude Code](https://claude.com/claude-code)

Co-Authored-By: Claude <noreply@anthropic.com>
```

**After Commit**:
1. Push to remote: `git push origin feature/backend-registry-downloads`
2. Update `docs/PROJECT_STATUS.md` section:
   ```markdown
   ### Backend Engineer Progress
   - ✅ BACKEND-REG-01: Category and registry endpoints (Committed: 2025-10-05)
   ```

---

#### **BACKEND-DL-01: Downloads Backend Infrastructure** (Week 2)
**Ticket Code**: `BACKEND-DL-01`

1. **Download Policy Endpoint**
   - `GET /api/downloads/policy/{videoId}` - Check if video allows downloads
   - Return: `{ "allowed": boolean, "reason": string, "requiresEula": boolean }`

2. **Download Token Generation**
   - `POST /api/downloads/token/{videoId}` - Generate signed download token
   - Include expiration timestamp
   - Validate EULA acceptance

3. **Download Manifest Endpoint**
   - `GET /api/downloads/manifest/{videoId}?token=xyz` - Get download streams
   - Return video/audio track URLs with expiration
   - Include quality options

4. **Download Analytics**
   - `POST /api/analytics/download-started` - Track download start
   - `POST /api/analytics/download-completed` - Track completion
   - Store in Firestore for metrics

**Commit Format**:
```
BACKEND-DL-01: Implement downloads backend API

- Download policy check endpoint
- Signed token generation for secure downloads
- Manifest endpoint with stream URLs
- Download analytics tracking
- EULA validation integration

Files Created:
- backend/src/main/java/com/albunyaan/tube/controller/DownloadController.java
- backend/src/main/java/com/albunyaan/tube/service/DownloadService.java
- backend/src/main/java/com/albunyaan/tube/service/DownloadTokenService.java

🤖 Generated with [Claude Code](https://claude.com/claude-code)

Co-Authored-By: Claude <noreply@anthropic.com>
```

---

#### **BACKEND-DL-02: /next-up Endpoint** (Week 3)
**Ticket Code**: `BACKEND-DL-02`

1. **Up Next Queue Endpoint**
   - `GET /api/player/next-up/{videoId}?userId={userId}` - Get recommended next videos
   - Return list of video items for player queue
   - Basic recommendation logic (same category, same channel)

2. **Response Format**
   ```json
   {
     "items": [
       {
         "id": "video_id",
         "title": "Video Title",
         "channelName": "Channel Name",
         "durationSeconds": 360,
         "thumbnailUrl": "https://...",
         "category": "Tafsir"
       }
     ],
     "nextCursor": "cursor_token"
   }
   ```

**Commit & Push after each ticket**

---

### **Success Criteria**
- All endpoints documented in `docs/api/openapi-draft.yaml`
- Unit test coverage > 80%
- Integration tests pass
- Postman collection in `backend/docs/postman/`
- Each ticket committed separately with proper format
- Update `docs/PROJECT_STATUS.md` after each ticket

### **Communication Protocol**
**Before starting**: Announce in team chat:
> "🔴 Backend Engineer: Starting BACKEND-REG-01 on branch `feature/backend-registry-downloads`. Will touch `backend/` only. ETA: 2 days."

**After each commit**: Announce:
> "🔴 Backend: ✅ BACKEND-REG-01 complete and pushed. Registry endpoints live."

**Daily Standups**: Report:
- What I completed yesterday (ticket code)
- What I'm working on today (ticket code)
- Any blockers

---

## 🟢 PROMPT 2: Frontend Engineer - Phase 3 Admin UI

### **Your Mission**
Complete Phase 3 Admin UI MVP - YouTube search/preview, category management, and approval queue.

### **Branch**: `feature/frontend-admin-ui`

### **Your Boundaries** ✋
- **YOU OWN**: `frontend/` directory ONLY
- **DO NOT TOUCH**: `android/`, `backend/src/` directories
- **DO NOT MODIFY**: Backend controllers, services, or Android code
- **SHARED FILES TO AVOID**: `docs/PROJECT_STATUS.md` (update only YOUR section)

### **Dependencies You Need**
- Phase 1 backend ✅ (Firebase Auth, Firestore)
- Phase 2 backend endpoints ⏳ (Backend Engineer is building - use mock data until ready)

### **Tasks Breakdown**

#### **FRONTEND-ADMIN-01: YouTube Search & Preview UI** (Week 1)
**Ticket Code**: `FRONTEND-ADMIN-01`

1. **Search Interface**
   - Search input with YouTube icon
   - Loading state during search
   - Results grid showing channels/playlists
   - Thumbnail, title, subscriber count display

2. **Preview Drawer**
   - Click video/channel to open drawer
   - Show videos/playlists/shorts tabs
   - Include/exclude toggle buttons
   - Category selector dropdown

3. **Mock Backend Integration**
   - Create `frontend/src/services/mockYouTubeService.ts`
   - Mock YouTube search responses
   - Replace with real backend when `BACKEND-REG-01` is complete

**Files to Create/Modify**:
```
frontend/src/components/admin/
  ├── YouTubeSearch.vue (or .tsx)
  ├── SearchResults.vue
  ├── ChannelDrawer.vue
  └── PlaylistDrawer.vue

frontend/src/services/
  └── mockYouTubeService.ts (temporary)

frontend/src/stores/
  └── youtubeSearch.ts
```

**Commit Format**:
```
FRONTEND-ADMIN-01: YouTube search and preview UI

- YouTube search interface with real-time results
- Channel/playlist preview drawer with tabs
- Include/exclude toggle functionality
- Category selector dropdown
- Mock service for development (replace when backend ready)

Components:
- YouTubeSearch.vue: Main search interface
- ChannelDrawer.vue: Channel preview with video list
- Mock data service for testing

🤖 Generated with [Claude Code](https://claude.com/claude-code)

Co-Authored-By: Claude <noreply@anthropic.com>
```

**After Commit**:
```bash
git push origin feature/frontend-admin-ui
```

Update docs:
```markdown
### Frontend Engineer Progress
- ✅ FRONTEND-ADMIN-01: YouTube search UI (Committed: 2025-10-05)
- Uses mock data until backend endpoints ready
```

---

#### **FRONTEND-ADMIN-02: Category Management UI** (Week 2)
**Ticket Code**: `FRONTEND-ADMIN-02`

1. **Category Tree View**
   - Hierarchical category display
   - Expand/collapse nodes
   - Drag-and-drop to reorganize (bonus)

2. **Category CRUD**
   - Add category dialog
   - Edit category inline
   - Delete with confirmation
   - Parent category selector

3. **Integration**
   - Connect to `GET /api/admin/categories` (when available)
   - Use mock data initially: `frontend/src/services/mockCategoryService.ts`

**Commit & Push**

---

#### **FRONTEND-ADMIN-03: Approval Queue Interface** (Week 3)
**Ticket Code**: `FRONTEND-ADMIN-03`

1. **Queue Table**
   - Pending approvals list
   - Filter by category/type
   - Sort by date submitted

2. **Approval Actions**
   - Approve button (green)
   - Reject button (red)
   - View details modal

**Commit & Push**

---

### **Success Criteria**
- All components use TypeScript/Vue3 or React (match existing)
- Storybook stories for each component
- Unit tests with Vitest/Jest
- Responsive design (mobile + desktop)
- Dark mode support using existing tokens
- i18n ready (en/ar/nl)
- Each ticket committed separately
- Update `docs/PROJECT_STATUS.md` after each ticket

### **Communication Protocol**
**Before starting**:
> "🟢 Frontend Engineer: Starting FRONTEND-ADMIN-01 on branch `feature/frontend-admin-ui`. Will touch `frontend/` only. Using mock backend data. ETA: 3 days."

**After each commit**:
> "🟢 Frontend: ✅ FRONTEND-ADMIN-01 complete. YouTube search UI ready with mocks."

---

## 🔵 PROMPT 3: Android Engineer - Phase 9 Downloads

### **Your Mission**
Implement Phase 9 Downloads & Offline functionality in the Android app.

### **Branch**: `feature/android-downloads`

### **Your Boundaries** ✋
- **YOU OWN**: `android/app/src/` directory ONLY
- **DO NOT TOUCH**: `backend/`, `frontend/` directories
- **DO NOT MODIFY**: Backend endpoints or Frontend components
- **SHARED FILES TO AVOID**: Any files outside `android/`

### **Dependencies You Need**
- Phase 8 player ✅ (already complete - PlayerViewModel, DownloadRepository exist)
- Phase 9 backend API ⏳ (Backend Engineer is building - use mock until ready)

### **Tasks Breakdown**

#### **ANDROID-DL-01: Download Queue UI** (Week 1)
**Ticket Code**: `ANDROID-DL-01`

1. **Downloads Fragment**
   - Create `DownloadsFragment.kt`
   - RecyclerView with download list
   - Download status (queued, downloading, paused, completed, failed)
   - Progress bar for active downloads
   - Pause/Resume/Cancel buttons

2. **Download Adapter**
   - `DownloadListAdapter.kt`
   - Show video thumbnail, title, size, progress
   - Click to play (if completed)
   - Long-press for options menu

3. **Navigation**
   - Add downloads tab to bottom navigation
   - Icon: download icon
   - Global navigation action

**Files to Create**:
```
android/app/src/main/java/com/albunyaan/tube/ui/downloads/
  ├── DownloadsFragment.kt
  ├── DownloadsViewModel.kt
  └── DownloadListAdapter.kt

android/app/src/main/res/layout/
  ├── fragment_downloads.xml
  └── item_download.xml

android/app/src/main/res/navigation/
  └── main_tabs_nav.xml (add downloads destination)
```

**Commit Format**:
```
ANDROID-DL-01: Implement downloads queue UI

- Created DownloadsFragment with RecyclerView
- DownloadListAdapter for download items
- Progress bar and status display
- Pause/Resume/Cancel actions
- Added downloads tab to bottom navigation

Files Created:
- DownloadsFragment.kt: Main downloads screen
- DownloadsViewModel.kt: State management
- DownloadListAdapter.kt: RecyclerView adapter
- fragment_downloads.xml: Layout
- item_download.xml: List item layout

Files Modified:
- main_tabs_nav.xml: Added downloads destination
- bottom_nav_menu.xml: Added downloads tab

🤖 Generated with [Claude Code](https://claude.com/claude-code)

Co-Authored-By: Claude <noreply@anthropic.com>
```

**After Commit**:
```bash
./gradlew assembleDebug  # Ensure build succeeds
git add -A
git commit (use format above)
git push origin feature/android-downloads
```

Update docs:
```markdown
### Android Engineer Progress
- ✅ ANDROID-DL-01: Downloads queue UI (Committed: 2025-10-05)
- Ready to integrate with backend API when available
```

---

#### **ANDROID-DL-02: Download Service & Notifications** (Week 2)
**Ticket Code**: `ANDROID-DL-02`

1. **Download Worker**
   - Create `DownloadWorker.kt` (WorkManager)
   - Download video/audio streams
   - Save to app storage
   - Handle interruptions

2. **Download Notification**
   - Foreground notification during download
   - Progress notification
   - Completion notification
   - Tap to open downloads screen

3. **Mock Backend**
   - `MockDownloadService.kt` for testing
   - Simulates download from URL
   - Replace when backend endpoints ready

**Files to Create**:
```
android/app/src/main/java/com/albunyaan/tube/download/
  ├── DownloadWorker.kt
  ├── DownloadNotificationManager.kt
  └── MockDownloadService.kt (temporary)
```

**Commit & Push**

---

#### **ANDROID-DL-03: Storage Management** (Week 3)
**Ticket Code**: `ANDROID-DL-03`

1. **Storage Settings**
   - Settings screen for download preferences
   - Storage location selector
   - Storage quota display
   - Clear downloads option

2. **Downloaded Videos Screen**
   - Filter by completed downloads
   - Play offline videos
   - Delete downloaded videos

**Commit & Push**

---

### **Success Criteria**
- All downloads work offline
- Notifications follow Android best practices
- Storage managed efficiently
- Unit tests for ViewModels
- Instrumentation tests for UI
- Build succeeds after each commit
- Update `docs/PROJECT_STATUS.md` after each ticket

### **Communication Protocol**
**Before starting**:
> "🔵 Android Engineer: Starting ANDROID-DL-01 on branch `feature/android-downloads`. Will touch `android/` only. Using mock backend. ETA: 2 days."

**After each commit**:
> "🔵 Android: ✅ ANDROID-DL-01 complete. Downloads UI ready. Build passing."

---

## 📋 Merge Protocol (End of Sprint)

### **Day Before Merge**
Each engineer:
1. Pull latest `main`: `git checkout main && git pull origin main`
2. Rebase your branch: `git checkout your-branch && git rebase main`
3. Resolve any conflicts
4. Run full test suite
5. Announce: "Ready to merge [BRANCH-NAME]"

### **Merge Day**
**Order** (to minimize conflicts):
1. 🔴 Backend merges first (most isolated)
2. 🟢 Frontend merges second (depends on backend)
3. 🔵 Android merges last (may depend on backend)

**Each Merge**:
```bash
git checkout main
git pull origin main
git merge feature/your-branch --no-ff
git push origin main
```

Post in chat:
> "✅ [YOUR-COLOR] Merged to main. All tests passing."

### **Post-Merge**
1. Delete feature branch: `git branch -d feature/your-branch`
2. Update `docs/PROJECT_STATUS.md` with final status
3. Team standup to review what shipped

---

## 🚨 Conflict Resolution

**If you accidentally touch someone else's code:**
1. Immediately announce in chat
2. Revert your commit: `git revert HEAD`
3. Create new commit with only your files

**If merge conflict occurs:**
1. Don't panic
2. Contact affected engineer
3. Pair debug on video call
4. Document resolution in commit message

---

## 📊 Progress Tracking

**docs/PROJECT_STATUS.md Structure**:
```markdown
## Active Parallel Work (2025-10-05)

### 🔴 Backend Engineer: Phase 2 & Downloads API
Branch: `feature/backend-registry-downloads`
- ✅ BACKEND-REG-01: Registry endpoints (2025-10-05)
- ⏳ BACKEND-DL-01: Downloads API (In Progress)
- ⏸️ BACKEND-DL-02: /next-up endpoint (Not Started)

### 🟢 Frontend Engineer: Admin UI
Branch: `feature/frontend-admin-ui`
- ✅ FRONTEND-ADMIN-01: YouTube search UI (2025-10-05)
- ⏳ FRONTEND-ADMIN-02: Category management (In Progress)
- ⏸️ FRONTEND-ADMIN-03: Approval queue (Not Started)

### 🔵 Android Engineer: Downloads
Branch: `feature/android-downloads`
- ✅ ANDROID-DL-01: Downloads UI (2025-10-05)
- ⏳ ANDROID-DL-02: Download service (In Progress)
- ⏸️ ANDROID-DL-03: Storage management (Not Started)
```

**Update this section after EVERY commit!**

---

## ✅ Summary

**3 Engineers working in parallel:**
- 🔴 **Backend**: Builds APIs in `backend/`
- 🟢 **Frontend**: Builds admin UI in `frontend/`
- 🔵 **Android**: Builds downloads in `android/`

**No conflicts because:**
- Each owns separate directory
- Clear boundaries defined
- Commit and push frequently
- Communicate in team chat
- Update docs after each ticket

**Timeline**: 3 weeks, 9 tickets total (3 per engineer)

**End Result**:
- Registry & moderation backend complete
- Admin UI MVP ready
- Android downloads fully functional
- All merged to main with zero conflicts

---

**Questions?** Ask in team chat before starting!
