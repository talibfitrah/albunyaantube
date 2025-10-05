# Parallel Work Prompts - Albunyaan Tube (Sprint 2)

**Date**: 2025-10-05
**Sprint**: Sprint 2 - Performance, Integration, & Polish
**Status**: Ready for parallel execution
**Git Branch Strategy**: Each engineer creates new feature branch from `main`

---

## 📊 Current Project Status

### ✅ Completed in Sprint 1 (MERGED TO MAIN)
- ✅ **Phase 8**: Player & Background Audio (Android)
- ✅ **Phase 9**: Downloads & Offline (Backend + Android)
- ✅ **Phase 2**: Registry & Category Management API (Backend)
- ✅ **Phase 3**: Admin UI MVP with mock data (Frontend)
- ✅ **Infrastructure**: CI/CD pipelines, Docker, Developer scripts

### 🎯 Sprint 2 Focus
- **Backend**: Approval workflow API, Performance optimization, Testing infrastructure
- **Frontend**: Connect mock services to real backend, Performance optimization
- **Android**: Performance optimization, Accessibility improvements, Testing

---

## 🔴 PROMPT 1: Backend Engineer - Approval Workflow & Performance

### **Your Mission**
Complete Phase 2 approval workflow API and implement Phase 10 performance improvements.

### **Branch**: `feature/backend-approval-performance`

### **Your Boundaries** ✋
- **YOU OWN**: `backend/` directory ONLY
- **DO NOT TOUCH**: `android/`, `frontend/` directories
- **DO NOT MODIFY**: Any Android ViewModels, Fragments, or Frontend components
- **SHARED FILES TO AVOID**: `docs/PROJECT_STATUS.md` (update only YOUR section)

### **Context: What's Already Done**
✅ Registry & Category endpoints (BACKEND-REG-01)
✅ Downloads API (BACKEND-DL-01, BACKEND-DL-02)
✅ Firebase Firestore + Authentication
✅ YouTube Data API integration

### **Tasks Breakdown**

#### **BACKEND-APPR-01: Approval Workflow API** (Week 1 - 3 days)
**Ticket Code**: `BACKEND-APPR-01`

**User Story**: As a moderator, I need to approve/reject channels and playlists submitted via the admin UI, so that only curated content appears in the Android app.

**Implementation**:
1. **Approval Endpoints**
   - `GET /api/admin/approvals/pending` - List pending approvals with filters
     ```json
     Query params: ?type=CHANNEL|PLAYLIST&category=Quran&limit=20&cursor=abc
     Response: {
       "items": [{
         "id": "approval_123",
         "type": "CHANNEL",
         "entityId": "channel_xyz",
         "title": "Nouman Ali Khan",
         "category": "Tafsir",
         "submittedAt": "2025-10-05T12:00:00Z",
         "submittedBy": "admin@example.com",
         "metadata": { "subscriberCount": "1.2M", "videoCount": 450 }
       }],
       "nextCursor": "def",
       "total": 42
     }
     ```

   - `POST /api/admin/approvals/{id}/approve` - Approve item
     ```json
     Body: {
       "reviewNotes": "High quality Quran recitation",
       "categoryOverride": "Quran" // optional
     }
     Response: { "status": "APPROVED", "approvedAt": "...", "approvedBy": "..." }
     ```

   - `POST /api/admin/approvals/{id}/reject` - Reject item
     ```json
     Body: {
       "reason": "NOT_ISLAMIC|LOW_QUALITY|DUPLICATE|OTHER",
       "reviewNotes": "Content not aligned with platform guidelines"
     }
     Response: { "status": "REJECTED", "rejectedAt": "...", "rejectedBy": "..." }
     ```

2. **Status Transition Logic**
   - Update channel/playlist `status` field: `PENDING → APPROVED | REJECTED`
   - Store approval metadata (reviewer, timestamp, notes)
   - Create audit log entry for compliance

3. **Firestore Queries**
   - Composite index on `status` + `type` + `submittedAt`
   - Pagination using cursor-based approach
   - Filter by category using `categoryId`

**Files to Create/Modify**:
```
backend/src/main/java/com/albunyaan/tube/
  ├── controller/ApprovalController.java (new)
  ├── service/ApprovalService.java (new)
  ├── model/ApprovalRequest.java (new)
  ├── model/ApprovalMetadata.java (new)
  └── repository/ApprovalRepository.java (new)

backend/src/test/java/com/albunyaan/tube/
  └── controller/ApprovalControllerTest.java (new)
```

**Acceptance Criteria**:
- [ ] Pending approvals endpoint returns paginated results
- [ ] Approve endpoint updates status to APPROVED
- [ ] Reject endpoint updates status to REJECTED
- [ ] Audit log captures all approval actions
- [ ] Filters work for type, category, date range
- [ ] Unit tests cover all endpoints (>80% coverage)
- [ ] Integration tests verify Firestore updates

**Commit Format**:
```
BACKEND-APPR-01: Implement approval workflow API

- Approval endpoints for pending/approve/reject operations
- Status transition logic (PENDING → APPROVED/REJECTED)
- Audit logging for compliance
- Composite Firestore indexes for efficient queries
- Pagination with cursor-based approach
- Filter by type, category, date range
- Unit and integration tests

Files Created:
- ApprovalController.java: REST endpoints
- ApprovalService.java: Business logic
- ApprovalRequest/ApprovalMetadata models
- ApprovalControllerTest.java: Test coverage

Related: FRONTEND-ADMIN-03 (approval queue UI)

🤖 Generated with [Claude Code](https://claude.com/claude-code)

Co-Authored-By: Claude <noreply@anthropic.com>
```

---

#### **BACKEND-PERF-01: Performance Optimization** (Week 2 - 2 days)
**Ticket Code**: `BACKEND-PERF-01`

**User Story**: As a user, I need fast API responses (<200ms p95), so that the app feels responsive.

**Implementation**:
1. **Redis Caching Layer**
   - Cache category tree (TTL: 1 hour)
   - Cache approved channels/playlists (TTL: 15 minutes)
   - Cache video metadata (TTL: 5 minutes)
   - Implement cache invalidation on updates

2. **Query Optimization**
   - Add composite Firestore indexes for common queries
   - Implement field masking (only fetch needed fields)
   - Use batch reads where appropriate

3. **API Response Compression**
   - Enable Gzip compression in Spring Boot
   - Optimize JSON serialization (exclude nulls)

4. **Metrics & Monitoring**
   - Add Micrometer metrics for all endpoints
   - Track: response time, cache hit rate, error rate
   - Expose metrics at `/actuator/prometheus`

**Files to Modify**:
```
backend/src/main/java/com/albunyaan/tube/
  ├── config/CacheConfig.java (new)
  ├── service/CategoryService.java (add caching)
  ├── service/ContentService.java (add caching)
  └── config/WebConfig.java (add compression)

backend/src/main/resources/
  └── application.yml (Redis config, compression)
```

**Acceptance Criteria**:
- [ ] Category endpoint p95 < 50ms (with cache)
- [ ] Content list endpoint p95 < 200ms
- [ ] Cache hit rate > 80% for category tree
- [ ] Gzip reduces response size by >60%
- [ ] Prometheus metrics exposed and accurate

**Commit Format**:
```
BACKEND-PERF-01: Add caching and performance optimization

- Redis caching for categories, channels, playlists
- Composite Firestore indexes for common queries
- Gzip compression for API responses
- Micrometer metrics for monitoring
- Cache invalidation on updates

Performance Improvements:
- Category endpoint: 150ms → 20ms (p95)
- Content list: 350ms → 180ms (p95)
- Response size: -65% with Gzip

Files Modified:
- CacheConfig.java: Redis setup
- CategoryService/ContentService: Caching logic
- WebConfig.java: Compression enabled
- application.yml: Redis + metrics config

🤖 Generated with [Claude Code](https://claude.com/claude-code)

Co-Authored-By: Claude <noreply@anthropic.com>
```

---

#### **BACKEND-TEST-01: Integration Test Suite** (Week 2 - 2 days)
**Ticket Code**: `BACKEND-TEST-01`

**User Story**: As a developer, I need comprehensive integration tests, so that regressions are caught before production.

**Implementation**:
1. **Firestore Emulator Tests**
   - Test all repository methods with real Firestore emulator
   - Verify composite indexes work correctly
   - Test concurrent writes and transactions

2. **API Integration Tests**
   - Test complete request/response flows
   - Verify authentication and authorization
   - Test error handling (400, 401, 403, 404, 500)

3. **Test Utilities**
   - Test data builders for common entities
   - Firestore emulator setup/teardown helpers
   - Mock Firebase Admin SDK for CI

**Files to Create**:
```
backend/src/test/java/com/albunyaan/tube/
  ├── integration/
  │   ├── CategoryIntegrationTest.java
  │   ├── ApprovalIntegrationTest.java
  │   └── DownloadIntegrationTest.java
  ├── util/
  │   ├── TestDataBuilder.java
  │   └── FirestoreTestHelper.java
  └── resources/
      └── application-test.yml
```

**Acceptance Criteria**:
- [ ] All repository methods have integration tests
- [ ] All API endpoints have integration tests
- [ ] Tests run against Firestore emulator
- [ ] Test coverage > 80% for service layer
- [ ] CI pipeline runs tests successfully

**Commit & Push**

---

### **Success Criteria**
- All endpoints documented in `docs/api/openapi-draft.yaml`
- Unit + integration test coverage > 80%
- Performance targets met (p95 < 200ms)
- Redis caching working correctly
- Each ticket committed separately with proper format
- Update `docs/PROJECT_STATUS.md` after each ticket

### **Communication Protocol**
**Before starting**:
> "🔴 Backend Engineer: Starting BACKEND-APPR-01 on branch `feature/backend-approval-performance`. Will touch `backend/` only. ETA: 3 days."

**After each commit**:
> "🔴 Backend: ✅ BACKEND-APPR-01 complete and pushed. Approval API ready for frontend integration."

---

## 🟢 PROMPT 2: Frontend Engineer - Backend Integration & Performance

### **Your Mission**
Replace mock services with real backend APIs and implement performance optimizations.

### **Branch**: `feature/frontend-backend-integration`

### **Your Boundaries** ✋
- **YOU OWN**: `frontend/` directory ONLY
- **DO NOT TOUCH**: `android/`, `backend/src/` directories
- **DO NOT MODIFY**: Backend controllers, services, or Android code
- **SHARED FILES TO AVOID**: `docs/PROJECT_STATUS.md` (update only YOUR section)

### **Context: What's Already Done**
✅ Admin UI with mock services (YouTube search, categories, approvals)
✅ Firebase Auth integration
✅ Tokenized dark theme
✅ Backend APIs ready: registry, categories, approvals (being built in parallel)

### **Tasks Breakdown**

#### **FRONTEND-INT-01: Replace Mock Services with Real Backend** (Week 1 - 4 days)
**Ticket Code**: `FRONTEND-INT-01`

**User Story**: As an admin, I need the UI to use real backend data, so that I can actually manage content.

**Implementation**:
1. **Replace Mock YouTube Service**
   - Delete `mockYouTubeService.ts`
   - Create `youtubeService.ts` calling `/api/admin/youtube/search`
   - Handle pagination, loading states, errors
   - Add retry logic for failed requests

2. **Replace Mock Category Service**
   - Delete `mockCategoryService.ts`
   - Create `categoryService.ts` calling:
     - `GET /api/admin/categories` - List all
     - `POST /api/admin/categories` - Create
     - `PUT /api/admin/categories/{id}` - Update
     - `DELETE /api/admin/categories/{id}` - Delete
   - Handle hierarchical category tree updates
   - Optimistic UI updates with rollback on error

3. **Replace Mock Approval Service**
   - Delete `mockApprovalsService.ts`
   - Create `approvalService.ts` calling:
     - `GET /api/admin/approvals/pending`
     - `POST /api/admin/approvals/{id}/approve`
     - `POST /api/admin/approvals/{id}/reject`
   - Real-time updates using polling (every 30s)
   - Toast notifications for approve/reject actions

4. **Error Handling**
   - Global error interceptor for 401/403
   - User-friendly error messages
   - Retry mechanism for network failures
   - Loading skeletons during API calls

**Files to Modify/Create**:
```
frontend/src/services/
  ├── youtubeService.ts (replace mock)
  ├── categoryService.ts (replace mock)
  ├── approvalService.ts (replace mock)
  └── api/
      ├── client.ts (Axios instance with interceptors)
      └── errorHandler.ts (Error handling utilities)

frontend/src/stores/
  ├── youtubeSearch.ts (update to use real service)
  ├── categories.ts (update to use real service)
  └── approvals.ts (update to use real service)

frontend/src/components/admin/
  ├── YouTubeSearch.vue (remove mock data references)
  ├── CategoriesView.vue (handle real errors)
  └── PendingApprovalsView.vue (add polling)
```

**Acceptance Criteria**:
- [ ] All mock services removed
- [ ] All API calls use real backend endpoints
- [ ] Error handling works (401, 403, 404, 500)
- [ ] Loading states display correctly
- [ ] Optimistic UI updates with rollback
- [ ] Retry logic handles network failures
- [ ] Toast notifications show success/error

**Commit Format**:
```
FRONTEND-INT-01: Replace mock services with real backend APIs

- YouTubeService: Real YouTube search via backend
- CategoryService: Full CRUD operations
- ApprovalService: Approve/reject workflow
- Global error handling with interceptors
- Optimistic UI updates with rollback
- Loading states and error boundaries
- Retry logic for failed requests

Files Removed:
- mockYouTubeService.ts
- mockCategoryService.ts
- mockApprovalsService.ts

Files Created:
- youtubeService.ts, categoryService.ts, approvalService.ts
- api/client.ts: Axios instance with auth
- api/errorHandler.ts: Error utilities

Files Modified:
- All admin views updated to use real services
- Stores updated with real API calls

Depends on: BACKEND-APPR-01, BACKEND-REG-01

🤖 Generated with [Claude Code](https://claude.com/claude-code)

Co-Authored-By: Claude <noreply@anthropic.com>
```

---

#### **FRONTEND-PERF-01: Performance Optimization** (Week 2 - 2 days)
**Ticket Code**: `FRONTEND-PERF-01`

**User Story**: As an admin, I need fast page loads (<2s), so that I can work efficiently.

**Implementation**:
1. **Code Splitting**
   - Lazy load admin routes
   - Dynamic imports for heavy components
   - Separate chunks for admin vs public views

2. **Asset Optimization**
   - Image lazy loading
   - Use WebP format for images
   - SVG icon sprites

3. **API Optimizations**
   - Request deduplication
   - Response caching (SWR pattern)
   - Debounce search inputs (300ms)
   - Pagination for large lists

4. **Bundle Size**
   - Tree shaking unused dependencies
   - Analyze bundle with Vite analyzer
   - Target: <500KB initial bundle

**Acceptance Criteria**:
- [ ] Initial page load < 2s (3G network)
- [ ] Admin route lazy loaded
- [ ] Bundle size < 500KB
- [ ] Images use lazy loading
- [ ] Search debounced properly

**Commit & Push**

---

#### **FRONTEND-TEST-01: Component Testing** (Week 2 - 2 days)
**Ticket Code**: `FRONTEND-TEST-01`

**User Story**: As a developer, I need component tests, so that UI changes don't break functionality.

**Implementation**:
1. **Vitest Setup**
   - Configure Vitest with Vue Test Utils
   - Mock API services
   - Mock Firebase Auth

2. **Component Tests**
   - YouTubeSearch component
   - CategoryTree component
   - ApprovalQueue component
   - Test user interactions (click, input, submit)

3. **Integration Tests**
   - Full workflow tests (search → preview → approve)
   - Error state rendering
   - Loading state rendering

**Acceptance Criteria**:
- [ ] All admin components have tests
- [ ] Test coverage > 70%
- [ ] Tests run in CI pipeline

**Commit & Push**

---

### **Success Criteria**
- All mock services replaced with real backend
- Performance targets met (< 2s load time)
- Test coverage > 70%
- Error handling comprehensive
- Each ticket committed separately
- Update `docs/PROJECT_STATUS.md` after each ticket

### **Communication Protocol**
**Before starting**:
> "🟢 Frontend Engineer: Starting FRONTEND-INT-01 on branch `feature/frontend-backend-integration`. Replacing mocks with real APIs. ETA: 4 days."

**After each commit**:
> "🟢 Frontend: ✅ FRONTEND-INT-01 complete. All mock services replaced. Ready for QA."

---

## 🔵 PROMPT 3: Android Engineer - Performance & Accessibility

### **Your Mission**
Implement performance optimizations and accessibility improvements for production readiness.

### **Branch**: `feature/android-performance-a11y`

### **Your Boundaries** ✋
- **YOU OWN**: `android/app/src/` directory ONLY
- **DO NOT TOUCH**: `backend/`, `frontend/` directories
- **DO NOT MODIFY**: Backend endpoints or Frontend components
- **SHARED FILES TO AVOID**: Any files outside `android/`

### **Context: What's Already Done**
✅ Phase 8: Player & Background Audio
✅ Phase 9: Downloads & Offline
✅ Phase 6: Backend Integration
✅ Phase 7: Channel & Playlist Details

### **Tasks Breakdown**

#### **ANDROID-PERF-01: Performance Optimization** (Week 1 - 3 days)
**Ticket Code**: `ANDROID-PERF-01`

**User Story**: As a user, I need smooth scrolling (60fps) and fast app startup (<2s), so that the app feels professional.

**Implementation**:
1. **RecyclerView Optimization**
   - Implement DiffUtil for all adapters
   - Use ViewHolder pattern correctly (already done, verify)
   - Implement pagination with Paging 3 library
   - Prefetch images with Coil

2. **App Startup Optimization**
   - Lazy initialization of heavy objects
   - Move Firebase init to background thread
   - Profile startup with Macrobenchmark
   - Target: Cold start < 2s, Warm start < 1s

3. **Memory Optimization**
   - Fix memory leaks (LeakCanary)
   - Optimize image loading (downsampling)
   - Clear old download data (LRU cache)
   - Reduce overdraw (Layout Inspector)

4. **Network Optimization**
   - Image caching with Coil disk cache
   - API response caching (OkHttp)
   - Retry with exponential backoff
   - Prefetch next page data

**Files to Modify/Create**:
```
android/app/src/main/java/com/albunyaan/tube/
  ├── ui/home/HomeAdapter.kt (add DiffUtil)
  ├── ui/videos/VideoListAdapter.kt (add DiffUtil)
  ├── ui/playlists/PlaylistAdapter.kt (add DiffUtil)
  ├── AlBunyaanApplication.kt (lazy init)
  ├── data/paging/
  │   ├── VideoPagingSource.kt (new)
  │   └── PlaylistPagingSource.kt (new)
  └── di/NetworkModule.kt (caching config)

android/app/build.gradle (add Paging 3)
```

**Acceptance Criteria**:
- [ ] Cold app startup < 2s (measured with Macrobenchmark)
- [ ] RecyclerView scroll at 60fps (no jank)
- [ ] Pagination loads smoothly
- [ ] No memory leaks (LeakCanary clean)
- [ ] Images cached properly (Coil disk cache)
- [ ] API responses cached (OkHttp)

**Commit Format**:
```
ANDROID-PERF-01: Performance optimization for production readiness

- RecyclerView: DiffUtil for efficient updates
- Paging 3: Infinite scroll for videos/playlists
- Startup: Lazy initialization, background Firebase init
- Memory: Fixed leaks, optimized image loading
- Network: Coil disk cache, OkHttp caching, retry logic

Performance Improvements:
- Cold startup: 3.2s → 1.8s
- Warm startup: 1.5s → 0.9s
- RecyclerView scroll: 60fps (was 45fps)
- Memory usage: -30% reduction

Files Modified:
- All RecyclerView adapters: DiffUtil added
- AlBunyaanApplication: Lazy initialization
- VideoPagingSource/PlaylistPagingSource: Paging 3
- NetworkModule: Caching configuration

Measured with: Macrobenchmark, Profiler, LeakCanary

🤖 Generated with [Claude Code](https://claude.com/claude-code)

Co-Authored-By: Claude <noreply@anthropic.com>
```

---

#### **ANDROID-A11Y-01: Accessibility Improvements** (Week 2 - 2 days)
**Ticket Code**: `ANDROID-A11Y-01`

**User Story**: As a visually impaired user, I need TalkBack support and proper accessibility labels, so that I can use the app independently.

**Implementation**:
1. **Content Descriptions**
   - Add contentDescription to all ImageViews
   - Add contentDescription to all IconButtons
   - Use meaningful descriptions (not "image" or "button")

2. **TalkBack Optimization**
   - Logical focus order for all screens
   - Group related elements (ViewCompat.setScreenReaderFocusable)
   - Announce dynamic content changes (LiveRegion)
   - Test all screens with TalkBack enabled

3. **Touch Target Sizes**
   - Ensure all clickable elements ≥ 48dp
   - Add padding where needed
   - Fix small download buttons

4. **Contrast & Colors**
   - Verify all text meets WCAG AA (4.5:1 ratio)
   - Add focus indicators for keyboard navigation
   - Support high contrast mode

**Files to Modify**:
```
android/app/src/main/res/
  ├── layout/*.xml (add contentDescription)
  ├── values/strings.xml (accessibility labels)
  └── values/dimens.xml (touch target sizes)

android/app/src/main/java/com/albunyaan/tube/
  └── ui/**/*.kt (announceForAccessibility)
```

**Acceptance Criteria**:
- [ ] All images have contentDescription
- [ ] All interactive elements ≥ 48dp
- [ ] TalkBack navigation is logical
- [ ] Dynamic content changes announced
- [ ] Contrast ratio ≥ 4.5:1 for all text
- [ ] Focus indicators visible

**Commit & Push**

---

#### **ANDROID-TEST-01: Instrumentation Tests** (Week 2 - 2 days)
**Ticket Code**: `ANDROID-TEST-01`

**User Story**: As a developer, I need UI tests, so that critical user flows are regression-tested.

**Implementation**:
1. **Espresso Setup**
   - Configure Espresso with Hilt
   - Mock backend with MockWebServer
   - Test data builders

2. **Critical Flow Tests**
   - Home screen loads content
   - Video playback starts
   - Download queue works
   - Settings update persists
   - Navigation flows work

3. **Accessibility Tests**
   - TalkBack tests with Accessibility Test Framework
   - Touch target size tests
   - Contrast tests

**Files to Create**:
```
android/app/src/androidTest/java/com/albunyaan/tube/
  ├── ui/
  │   ├── HomeScreenTest.kt
  │   ├── PlayerTest.kt
  │   ├── DownloadsTest.kt
  │   └── NavigationTest.kt
  ├── util/
  │   ├── MockWebServerRule.kt
  │   └── TestDataBuilder.kt
  └── accessibility/
      └── AccessibilityTest.kt
```

**Acceptance Criteria**:
- [ ] Critical flows have Espresso tests
- [ ] Tests run in CI pipeline
- [ ] Accessibility tests pass
- [ ] Tests use mock backend data

**Commit & Push**

---

### **Success Criteria**
- Performance targets met (startup < 2s, 60fps scroll)
- All accessibility requirements met (TalkBack, contrast)
- Critical flows have UI tests
- Build succeeds after each commit
- Update `docs/PROJECT_STATUS.md` after each ticket

### **Communication Protocol**
**Before starting**:
> "🔵 Android Engineer: Starting ANDROID-PERF-01 on branch `feature/android-performance-a11y`. Will touch `android/` only. ETA: 3 days."

**After each commit**:
> "🔵 Android: ✅ ANDROID-PERF-01 complete. App startup 1.8s, 60fps scroll. Build passing."

---

## 📋 Merge Protocol (End of Sprint 2)

### **Day Before Merge**
Each engineer:
1. Pull latest `main`: `git checkout main && git pull origin main`
2. Rebase your branch: `git checkout your-branch && git rebase main`
3. Resolve any conflicts
4. Run full test suite
5. Announce: "Ready to merge [BRANCH-NAME]"

### **Merge Day**
**Order** (to minimize conflicts):
1. 🔴 Backend merges first (APIs needed by frontend)
2. 🟢 Frontend merges second (depends on backend APIs)
3. 🔵 Android merges last (independent optimizations)

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
2. Update `docs/PROJECT_STATUS.md` with "Sprint 2 Complete"
3. Team standup to review metrics (performance gains, test coverage)

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
## Active Parallel Work - Sprint 2 (2025-10-05)

### 🔴 Backend Engineer: Approval Workflow & Performance
Branch: `feature/backend-approval-performance`
- ✅ BACKEND-APPR-01: Approval workflow API (2025-10-06)
- ⏳ BACKEND-PERF-01: Performance optimization (In Progress)
- ⏸️ BACKEND-TEST-01: Integration tests (Not Started)

### 🟢 Frontend Engineer: Backend Integration & Performance
Branch: `feature/frontend-backend-integration`
- ✅ FRONTEND-INT-01: Replace mock services (2025-10-07)
- ⏳ FRONTEND-PERF-01: Performance optimization (In Progress)
- ⏸️ FRONTEND-TEST-01: Component testing (Not Started)

### 🔵 Android Engineer: Performance & Accessibility
Branch: `feature/android-performance-a11y`
- ✅ ANDROID-PERF-01: Performance optimization (2025-10-06)
- ⏳ ANDROID-A11Y-01: Accessibility improvements (In Progress)
- ⏸️ ANDROID-TEST-01: Instrumentation tests (Not Started)
```

**Update this section after EVERY commit!**

---

## ✅ Sprint 2 Summary

**3 Engineers working in parallel:**
- 🔴 **Backend**: Approval API + Performance + Tests
- 🟢 **Frontend**: Real backend integration + Performance + Tests
- 🔵 **Android**: Performance + Accessibility + Tests

**No conflicts because:**
- Each owns separate directory
- Clear boundaries defined
- Commit and push frequently
- Communicate in team chat
- Update docs after each ticket

**Timeline**: 2 weeks, 9 tickets total (3 per engineer)

**End Result**:
- Approval workflow complete (backend + frontend connected)
- All mock services replaced with real APIs
- Performance optimized across all platforms
- Accessibility requirements met
- Comprehensive test coverage
- Production-ready codebase

---

**Questions?** Ask in team chat before starting!
