# Albunyaan Tube - System Prompt

## Project Overview

**Albunyaan Tube** is an ad-free, admin-curated Islamic content platform delivering safe YouTube content through:
1. **Spring Boot Backend** (Java 17) - REST API with Firebase Firestore
2. **Vue 3 Admin Dashboard** (TypeScript) - Content moderation interface
3. **Android Mobile App** (Kotlin) - Public content consumption

**Vision**: Trusted global platform for halal YouTube content where Muslims can access safe Islamic content without compromising values or exposure to inappropriate material.

**Key Principles**:
- **Privacy-first**: Never use official YouTube API (NewPipeExtractor only)
- **Human moderation**: All content manually approved before public visibility
- **Multilingual**: English, Arabic (RTL), Dutch
- **Offline-first**: Android downloads with device storage management (no artificial quotas)
- **No user accounts**: Mobile app is public access only

---

## Architecture

### Backend (Spring Boot 3.2.5 + Firebase)

**Technology Stack**:
- Java 17, Spring Boot 3.2.5
- Firebase Firestore (NoSQL), Firebase Authentication
- NewPipeExtractor (YouTube metadata extraction - NO official API)
- Caffeine cache (dev), optional Redis (prod, 5-min TTL)

**Controllers** (14 total):
- PublicContentController - Mobile API (`/api/v1/*`)
- ApprovalController - Approval workflow
- CategoryController - Category CRUD
- ChannelController, PlaylistController, VideoController - Content management
- RegistryController - Internal pending queue API
- YouTubeSearchController - YouTube search integration
- VideoValidationController - Detect unavailable videos
- ImportExportController - Bulk operations (full JSON + simple formats)
- DashboardController - Metrics with trend analysis
- UserController - User management
- PlayerController - UpNext recommendations
- DownloadController - Download tokens

**Firestore Collections**:
- `categories` - Hierarchical categories (max 2 levels: parent → subcategory)
- `channels`, `playlists`, `videos` - Content with approval metadata
- `users` - Admin/moderator accounts
- `audit_logs` - Immutable action trail
- `validation_runs` - Video availability check history

**Key Features**:
- Role-Based Access Control: ADMIN (full permissions, direct approval, user management), MODERATOR (submit for review only)
- Approval workflow: submission → pending → approved/rejected
- Granular exclusions: Exclude specific videos/playlists within approved channels
- Hierarchical categories: Parent categories + optional subcategories
- Video validation: Detect deleted/unavailable YouTube videos
- Audit trail: All approval actions logged with actor, timestamp, reason

**Security (MVP)**:
- Firebase Authentication (email/password) for admin dashboard
- Firebase custom claims for RBAC (validated on every request)
- HTTPS/TLS in production (localhost HTTP acceptable for dev)
- No artificial storage quotas - device storage is natural limit
- Basic input validation (defer comprehensive JSON schema to v1.1+)

### Frontend (Vue 3 + Vite + TypeScript)

**State Management**: Pinia (auth, preferences, filters)
**Key Modules**:
- Content Search - YouTube search with NewPipeExtractor
- Pending Approvals - Review and approve/reject submissions
- Content Library - Advanced filtering, multi-select, bulk actions
- Exclusions Workspace - Manage excluded items with parent context
- Bulk Import/Export - Full JSON + simple formats, selective export
- Video Validation - Manual trigger, history tracking, status panel
- Dashboard - Metrics with trend indicators, timeframe selector
- Categories - Hierarchical tree management (parent → subcategory)
- User Management - ADMIN creates/assigns roles
- Audit Logs - Filter by actor, action, date range

**Performance Budgets**:
- Time to Interactive < 3s (desktop Chrome)
- First Contentful Paint < 1.5s
- Bundle size < 500KB gzipped

### Android (Kotlin + Jetpack)

**Architecture**: MVVM, Jetpack Compose + Material Design 3
**Key Features**:
- Browse by category (hierarchical: parent/subcategory)
- Video player (ExoPlayer + NewPipeExtractor):
  - Quality selection (144p-4k), audio-only mode, subtitles
  - Picture-in-Picture, fullscreen, gesture controls
  - Chromecast support, UpNext queue, external sharing
- Live stream support: "LIVE" badge, disabled seek bar, auto-transition to VOD
- Offline downloads:
  - Quality selector (144p-1080p, 4k), audio-only option
  - WorkManager with foreground notifications (progress, pause/resume/cancel)
  - 30-day expiry, device storage management (Android OS handles warnings)
  - Playlist bulk download with aggregated progress
- Search with persistent history (max 10 items)
- Safe mode toggle for family-friendly filtering
- Multi-language: English, Arabic (RTL), Dutch

**Backend Integration**:
- Emulator: `http://10.0.2.2:8080/api`
- Device: `http://192.168.1.167:8080/api`
- Production: `https://api.albunyaan.tube/api`
- NewPipeExtractor: Stream URL resolution on-device (never official YouTube API)

**Performance Budgets**:
- Cold start < 2.5s (Pixel 4a, API 31)
- Video playback start p50 < 1.2s
- Memory usage average < 150MB, peak < 250MB

---

## Data Flow

1. **Admin curates content**: Search YouTube via NewPipeExtractor, assign categories, set exclusions
2. **Approval workflow**:
   - ADMIN: Direct approval → content immediately visible in mobile app
   - MODERATOR: Submit for review → pending queue → admin approval
3. **Mobile app fetches**: Android requests `/api/v1/content` with category filters
4. **Stream resolution**: Android uses NewPipeExtractor on-device (no official YouTube API)
5. **Downloads**: Backend issues token, Android downloads via WorkManager
6. **Audit trail**: All approval actions logged with actorUid, timestamp, metadata

---

## Key Technical Details

### Category System
- **Hierarchical structure**: Parent categories (e.g., "Quran") + optional subcategories (e.g., "Quran Recitation", "Tafsir")
- **Max depth**: 2 levels (Category → Subcategory, no sub-subcategories)
- **Assignment**: Content can be assigned to multiple categories/subcategories
- **Filtering**: Selecting parent category includes all subcategory content

### Exclusions
- **Channels**: Exclude specific videos, live streams, shorts, playlists, posts
- **Playlists**: Exclude specific videos
- **Storage**: `excludedItems` object in Firestore document
- **API filtering**: Excluded items automatically filtered from public API responses

### Approval Workflow States
- **pending**: Submitted for review (MODERATOR submissions or ADMIN saves as pending)
- **approved**: Visible in mobile app API
- **rejected**: Hidden from API, reason tracked in audit log

### Caching Strategy
- **YouTube searches**: 1 hour TTL (Caffeine dev, optional Redis prod)
- **Content lists**: 5 minute TTL with cache stampede protection
- **Target**: ≥85% cache hit ratio

### NewPipeExtractor Usage
- **Admin**: Backend uses NewPipeExtractor for YouTube search (channels, playlists, videos)
- **Mobile**: Android uses NewPipeExtractor on-device for stream URL resolution
- **Never use**: Official YouTube Data API v3 (preserves privacy, no tracking, no API keys)
- **Fallback (v1.1+)**: FreeTube or Invidious when NewPipe breaks (NEVER official YouTube API)

### Storage Management (ANDROID-DL-03)
- **No artificial quota**: Device storage is the natural limit
- **Android OS**: Handles low storage warnings and cancellation
- **Display**: Shows "Downloads: X MB • Available: Y GB of Z GB"
- **Progress bar**: Reflects total device usage (not downloads)
- **30-day expiry**: Automatic cleanup with 7-day advance notification

---

## API Endpoints

### Public API (Mobile - No Auth)
- `GET /api/v1/categories` - Hierarchical category tree
- `GET /api/v1/content` - Channels, playlists, videos (approved only, exclusions filtered)
- `GET /api/v1/channels/{id}` - Channel detail
- `GET /api/v1/playlists/{id}` - Playlist detail
- `GET /api/v1/videos/{id}` - Video detail
- `GET /api/v1/search?q={query}&type={type}&categoryId={id}` - Search approved content

### Admin API (Auth Required - Firebase JWT)
- `POST /api/admin/content` - Add content (MODERATOR: pending, ADMIN: can approve immediately)
- `PUT /api/admin/content/{id}` - Update categories/exclusions/status
- `POST /api/admin/content/{id}/approve` - Approve (ADMIN only)
- `POST /api/admin/content/{id}/reject` - Reject with reason (ADMIN only)
- `GET /api/admin/content/pending` - Pending approvals queue (ADMIN only)
- `POST /api/admin/categories` - Create category (ADMIN only)
- `POST /api/admin/users` - Create user (ADMIN only)
- `POST /api/admin/youtube/search` - YouTube search via NewPipeExtractor
- `POST /api/admin/validation/trigger` - Trigger video validation
- `POST /api/admin/import` - Bulk import (full JSON or simple format)
- `GET /api/admin/export` - Bulk export (selective, exclude unavailable option)

---

## Testing Strategy

### Backend (JUnit 5)
- Unit tests: Mock Firestore repositories
- Integration tests: Firebase Emulator Suite
- Performance tests: Gatling (200 RPS, p95 < 200ms)
- **Scope**: Test only `/backend` directory (exclude `/frontend` and `/android`)

### Frontend (Vitest + Playwright)
- Unit tests: `@testing-library/vue`
- E2E tests: Playwright (approval workflow, category assignment)
- Timeout: 300 seconds (5 minutes) enforced per AGENTS.md policy
- **Scope**: Test only `/frontend` directory (exclude `/android` - has separate native testing)

### Android (Kotlin Native)
- Unit tests: `./gradlew test` (JUnit, Mockito)
- Instrumentation tests: `./gradlew connectedAndroidTest` (Espresso)
- Manual testing: Device/emulator with real backend
- **Scope**: Native Kotlin/Gradle testing separate from frontend

### TestSprite Integration
- **Frontend**: Test only `/frontend` directory
- **Backend**: Test only `/backend` directory
- **Android**: Separate native testing tooling (exclude from TestSprite)

---

## MVP Scope & Limitations

**In Scope**:
- Spring Boot backend with 14 controllers, 67 endpoints
- Vue 3 admin dashboard with approval workflow, category management, bulk operations
- Android app with video player, offline downloads, live streams, Chromecast
- Firebase Firestore (7 collections) + Firebase Auth (ADMIN/MODERATOR roles)
- NewPipeExtractor for all YouTube interactions (no official API)
- Multi-language: English, Arabic (RTL), Dutch
- Audit logging, video validation, metrics dashboard

**Out of Scope (Deferred to v1.1+)**:
- iOS app (Android-only for MVP)
- User accounts for mobile viewers (public access only)
- Community content suggestions from end-users
- Automated moderation via ML/AI
- Advanced rate limiting, token refresh rotation, CSRF protection
- Comprehensive JSON schema validation, Firestore security rules hardening
- Audit log immutable storage, signed download URLs, HSTS headers
- FreeTube/Invidious fallback (only when NewPipe breaks)
- Volunteer moderator program, advanced analytics
- CDN for thumbnails, Kubernetes HPA, circuit breakers

**Performance Targets**:
- API p95 latency < 200ms at 200 RPS
- Mobile cold start < 2.5s (Pixel 4a)
- Admin dashboard Time to Interactive < 3s
- Cache hit ratio ≥ 85%

---

## Critical Notes

1. **NEVER use official YouTube Data API v3** - Use NewPipeExtractor exclusively (privacy-first, no tracking)
2. **No artificial storage quotas** - Device storage is natural limit, Android OS handles warnings
3. **Role enforcement**: ADMIN can approve directly, MODERATOR can only submit for review
4. **Category filtering**: Parent category includes all subcategory content
5. **Exclusions**: Automatically filtered from public API responses
6. **Testing scope**: Frontend tests exclude `/android`, backend tests exclude `/frontend` and `/android`
7. **Security**: Firebase custom claims validated on every request, basic input validation (MVP)
8. **Audit trail**: All approval actions logged immutably with actorUid, timestamp, metadata

---

**Last Updated**: November 10, 2025
**Status**: ~60% complete - Backend & frontend working, Android ready for testing
