# Solution Architecture Overview

This document summarizes the end-to-end architecture for Albunyaan Tube, an ad-free, admin-curated YouTube client delivering safe Islamic content through native mobile apps and a web-based moderation dashboard.

## Related Documentation
- **C4 Diagrams**: [`diagrams/`](diagrams/) - Context and component diagrams
- **API Specification**: [`api-specification.yaml`](api-specification.yaml) - OpenAPI REST API spec
- **Security**: [`security.md`](security.md) - Threat model and security controls
- **PRD**: [`../PRD.md`](../PRD.md) - Product requirements document

---

## System Overview

**Albunyaan Tube** is a 3-tier architecture:
1. **Android Mobile App** (Kotlin + Jetpack) - Public content consumption
2. **Admin Web Dashboard** (Vue 3 + TypeScript) - Content curation and moderation
3. **Spring Boot Backend** (Java 17) - REST API + Firebase Firestore

**Key Principles**:
- **Privacy-first**: Never use official YouTube API in mobile app (NewPipeExtractor only)
- **Human moderation**: All content manually approved by admins before public visibility
- **Multilingual**: English, Arabic (RTL), Dutch across all platforms
- **Offline-first**: Android app supports downloads
- **No user accounts**: Mobile app is public access only (admin dashboard requires Firebase Auth)

---

## Backend Architecture

### Technology Stack
- **Framework**: Spring Boot 3.2.5, Java 17
- **Database**: Firebase Firestore (NoSQL document database)
- **Authentication**: Firebase Authentication with custom claims (ADMIN, MODERATOR roles)
- **Caching**:
  - **Dev**: Caffeine (in-memory, 1-hour TTL)
  - **Prod (Optional)**: Redis (5-minute TTL, commented out in application.yml)
- **External APIs**:
  - **NewPipeExtractor** (https://github.com/TeamNewPipe/NewPipeExtractor) - Extract YouTube metadata and search WITHOUT official API
  - **Fallback (Deferred to v1.1+)**: FreeTube (https://github.com/FreeTubeApp/FreeTube) or Invidious (https://github.com/iv-org/invidious) when NewPipe breaks
  - **Note**: NEVER use official YouTube Data API v3 (preserves privacy, no API keys, no tracking)

### Controllers (14 Total)
1. **PublicContentController** - Mobile app API (`/api/v1/*`)
2. **ApprovalController** - Approval workflow (`/api/admin/approvals`)
3. **CategoryController** - Category CRUD (`/api/admin/categories`)
4. **ChannelController** - Channel management (`/api/admin/channels`)
5. **ContentLibraryController** - Content library (`/api/admin/content`)
6. **RegistryController** - Internal workflow for adding to pending queue (`/api/admin/registry`)
7. **YouTubeSearchController** - YouTube search (`/api/admin/youtube`)
8. **VideoValidationController** - Validate video availability (`/api/admin/validation`)
9. **ImportExportController** - Bulk operations (`/api/admin/import`, `/api/admin/export`)
10. **DashboardController** - Metrics (`/api/admin/dashboard`)
11. **AuditLogController** - Audit trail (`/api/admin/audit`)
12. **UserController** - User management (`/api/admin/users`)
13. **DownloadController** - Download tokens (`/api/downloads`)
14. **PlayerController** - Recommendations (`/api/player`)

### Services (13 Total)
- **PublicContentService** - Mobile app content queries
- **ApprovalService** - Approval workflow logic
- **YouTubeService** - YouTube API integration with caching
- **VideoValidationService** - Detect unavailable YouTube videos
- **ImportExportService**, **SimpleImportService**, **SimpleExportService** - Bulk operations
- **DownloadService**, **DownloadTokenService** - Download management
- **PlayerService** - UpNext recommendations
- **AuditLogService** - Immutable audit trail
- **AuthService** - Firebase Auth wrapper
- **CategoryMappingService** - Category hierarchy utilities

### Repositories (7 Total)
All use Firestore SDK directly (no Spring Data JPA):
- **CategoryRepository**, **ChannelRepository**, **PlaylistRepository**, **VideoRepository**
- **UserRepository**, **AuditLogRepository**, **ValidationRunRepository**

### Firestore Collections

#### categories/{id}
- **name**: Category name (English)
- **nameAr**, **nameNl**: Arabic and Dutch translations
- **parentCategoryId**: Parent ID for hierarchical structure (max 2 levels)
- **topLevel**: Boolean flag for root-level categories
- **displayOrder**: Integer for sorting
- **createdAt**, **updatedAt**, **createdBy**, **updatedBy**: Audit fields

#### channels/{id}
- **youtubeId**: YouTube channel ID
- **categoryIds**: Array of assigned categories
- **pending**, **approved**: Boolean flags for approval workflow
- **excludedVideoIds**, **excludedPlaylistIds**: Arrays of excluded items
- **submittedBy**, **approvedBy**, **rejectedBy**: User IDs
- **createdAt**, **updatedAt**: Timestamps

#### playlists/{id}
- **youtubeId**: YouTube playlist ID
- **categoryIds**: Array of assigned categories
- **pending**, **approved**: Boolean flags
- **excludedVideoIds**: Array of excluded videos
- **submittedBy**, **approvedBy**, **rejectedBy**: User IDs
- **createdAt**, **updatedAt**: Timestamps

#### videos/{id}
- **youtubeId**: YouTube video ID
- **categoryIds**: Array of assigned categories
- **pending**, **approved**: Boolean flags
- **submittedBy**, **approvedBy**, **rejectedBy**: User IDs
- **createdAt**, **updatedAt**: Timestamps

#### users/{uid}
- **uid**: Firebase UID (document ID)
- **email**, **displayName**: Profile
- **role**: "ADMIN" | "MODERATOR" (mirrored in Firebase custom claims)
- **createdAt**, **updatedAt**, **lastLoginAt**: Timestamps

#### audit_logs/{id}
- **action**: e.g., "APPROVE_CHANNEL", "REJECT_VIDEO"
- **actorUid**: User who performed action
- **entityType**: "CHANNEL" | "PLAYLIST" | "VIDEO" | "CATEGORY"
- **entityId**: Affected document ID
- **metadata**: Additional context (categories, reason, etc.)
- **timestamp**: When action occurred

#### validation_runs/{id}
- **status**: "NEVER_RUN" | "RUNNING" | "COMPLETED" | "FAILED" | "ERROR"
- **startedAt**, **completedAt**: Timestamps
- **videosChecked**, **videosUnavailable**: Counts
- **errors**: Array of error messages
- **triggeredBy**: User ID

### Security (MVP Scope)
- **Authentication**: Firebase Authentication (email/password) for admin dashboard
- **Authorization**: Custom claims (ADMIN, MODERATOR) validated via Spring Security `@PreAuthorize`
- **Transport**: HTTPS/TLS for production (localhost HTTP acceptable for dev)
- **Mobile**: Public access only, no authentication required
- **Downloads**: App-private storage with Android OS-level encryption

**Deferred to v1.1+**:
- Advanced rate limiting, token refresh rotation, CSRF protection
- Comprehensive JSON schema validation, Firestore security rules hardening
- Argon2id password hashing (Firebase default sufficient for MVP)
- Audit log immutable storage, signed download URLs, HSTS headers

### Observability (MVP Scope)
- **Logging**: Console output with Spring Boot default formatting
- **Metrics**: Spring Boot Actuator with Prometheus endpoint (`/actuator/prometheus`)
- **Deferred to v1.1+**: Grafana dashboards, automated alerting, Firebase Crashlytics

### Performance Budgets (PRD Requirements)
- **API**: p95 latency < 200ms, payload ≤ 80KB per page (limit=20 items)
- **Cache hit ratio**: ≥ 85% for list queries
- **Database queries**: < 10ms for indexed queries

---

## API Contract & Client Integration

### OpenAPI Specification Status
- **Version**: OpenAPI 3.0.3 (downgraded from 3.1.0 for wider tool compatibility)
- **Validation**: ✅ Valid spec with 0 errors (134 warnings - acceptable)
- **Endpoints**: 113+ endpoints across 14 controllers fully documented
- **Schemas**: 60+ DTOs defined including models, requests, and responses

### DTO Alignment Strategy

**Dual-Pattern Response Architecture**:
The API uses two response patterns depending on endpoint purpose:

1. **Detail Endpoints** (Channel/Playlist/Video detail by ID):
   - Return raw **model objects** with full Firestore fields
   - Examples: `/v1/channels/{channelId}`, `/v1/playlists/{playlistId}`
   - Schema names: `Channel`, `Playlist`, `Video`
   - Purpose: Complete data for admin dashboard and deep linking

2. **Public Content Listing** (Mobile app browsing):
   - Returns unified **ContentItemDto** for simplified client consumption
   - Examples: `/v1/content` (cursor-paginated)
   - Polymorphic fields: `type` discriminator (CHANNEL/PLAYLIST/VIDEO)
   - Purpose: Optimized payloads for infinite scroll

**Field Name Conventions**:
- Channels: Use `name` (not `title`), `subscribers` (not `subscriberCount`)
- Playlists & Videos: Use `title`
- Videos: Duration in `durationSeconds` (models) vs `durationMinutes` (ContentItemDto)
- Timestamps: ISO 8601 format (`uploadedAt` field name in models)

**Status Enum Migration**:
- Models use `status` enum (APPROVED/PENDING/REJECTED/UNAVAILABLE)
- Legacy boolean flags (`pending`, `approved`) remain for backwards compatibility
- Clients should reference `status` field going forward

**Metadata Patterns**:
- Individual metadata fields: `createdAt`, `updatedAt`, `submittedBy`, `approvedBy`
- Embedded approval metadata: `approvalMetadata` object (Channel/Playlist only)
  - Contains: `reviewedBy`, `reviewerDisplayName`, `reviewedAt`, `reviewNotes`, `rejectionReason`

### Pagination Standards

**Cursor-Based Pagination**:
- Response wrapper: `CursorPageDto<T>`
- Structure: `{data: T[], pageInfo: {nextCursor: string|null}}`
- Implemented on: `/v1/content` (mobile app listing)
- Query params: `cursor` (opaque string), `limit` (1-50, default 20)

**Non-Paginated Responses**:
- `/v1/search`: Returns simple array `ContentItemDto[]` (limit-only, no cursor)
- Rationale: Search is limited by relevance (top N results), not suitable for infinite scroll

### Code Generation Approach

**Planned Generator Setup** (Phase 1 - Task 2):
1. **Frontend (TypeScript)**: `openapi-generator-cli` → `src/generated/api/`
2. **Android (Kotlin)**: `openapi-generator-cli` → `app/src/main/kotlin/generated/`
3. **CI Integration**: Pre-build step regenerates on spec changes
4. **Migration Path**: Replace hand-rolled DTOs incrementally per module

**Benefits**:
- Single source of truth (api-specification.yaml)
- Compile-time type safety across all 3 platforms
- Automatic field mapping and validation
- Reduces field aliasing bugs (e.g., `subscriberCount` vs `subscribers`)

### Version Compatibility

**Breaking Changes Policy** (v1.1+):
- Major version bump (e.g., `/v2/...`) for incompatible changes
- Deprecation warnings via `deprecated: true` in spec
- 6-month support window for deprecated endpoints

**Current Status**:
- API version: `v1` (stable since Phase 0 completion)
- Next breaking change target: v1.1 (auto-approval rules, extractor fallback)

### Related Files
- [api-specification.yaml](api-specification.yaml) - Complete OpenAPI spec
- [endpoint-inventory.md](endpoint-inventory.md) - Controller-by-controller endpoint list
- `backend/src/main/java/com/albunyaan/tube/dto/` - Backend DTO implementations

---

## Android Architecture

### Technology Stack
- **Language**: Kotlin
- **Architecture**: MVVM (Model-View-ViewModel)
- **UI**: Jetpack Compose + Material Design 3
- **Navigation**: Navigation Component (single-activity pattern)
- **Networking**: Retrofit + OkHttp
- **Storage**: DataStore (preferences), app-private files (downloads)
- **Video Player**: ExoPlayer + NewPipeExtractor
- **Background Work**: WorkManager (downloads)

### Key Screens (16 Total)
- **Onboarding**: 3-page carousel (Browse, Listen in background, Download for offline)
- **Main Shell**: Bottom navigation with 5 tabs
  - Home (category filter + 3 horizontal lists: Channels, Playlists, Videos + search and settings buttons)
  - Channels (vertical list)
  - Playlists (vertical list)
  - Videos (vertical list)
  - Downloads
- **Detail Screens**: Channel, Playlist detail
- **Player**: Full-screen video player
- **Search**: Backend-integrated search
- **Categories**: Category picker with hierarchical structure
- **Downloads**: Manage offline downloads
- **Settings**: Locale switcher, safe mode toggle

### Video Player Features (ExoPlayer)
- **Quality selection**: 144p-4k with resolution info display
- **Audio-only mode**: Toggle for audio stream only
- **Subtitles**: Multi-language tracks, auto-generated detection, "Off" option
- **Picture-in-Picture**: Android 8+ with auto aspect ratio
- **Fullscreen**: Immersive UI (hides status/navigation bars)
- **Gesture controls**: Swipe for brightness/volume, double-tap to seek ±10s
- **Chromecast**: Cast button, device detection, metadata passthrough
- **UpNext queue**: Backend recommendations API integration
- **Share**: External apps via intent chooser

### Offline Downloads
- **Quality selector**: 144p-1080p, 4k if available
- **Audio-only option**: Download audio stream only
- **Stream merging**: Separate audio/video for HD (>480p) following NewPipe implementation
- **WorkManager**: Foreground notifications with progress, pause/resume/cancel
- **Storage**: App-private directory, 30-day expiry, device storage managed by Android OS
- **Playlist downloads**: Bulk download with aggregated progress
- **Storage info**: Shows estimated file size and available device storage before download

### Live Stream Support
- **"LIVE" badge**: Visual indicator on active streams
- **Disabled seek bar**: During live playback
- **Auto-transition**: Converts to VOD when stream ends (detected via NewPipeExtractor)

### Localization
- **Languages**: English, Arabic (RTL), Dutch
- **Locale switcher**: Settings screen
- **RTL mirroring**: Layout direction, directional icons, text alignment
- **Locale-specific numerals**: Eastern Arabic for Arabic
- **Date formats**: Gregorian calendar

### Backend Integration
- **Base URL**:
  - Emulator: `http://10.0.2.2:8080/api` (Android emulator's gateway to host localhost)
  - Device: `http://192.168.1.167:8080/api` (or configured backend IP)
  - Production: `https://api.albunyaan.tube/api`
- **API Client**: Retrofit with JSON payloads
- **Authentication**: None (public content access)
- **NewPipeExtractor**: Stream URL resolution on-device (never official YouTube API)

### Performance Budgets (PRD Requirements)
- **Cold start**: < 2.5s on Pixel 4a (mid-range device, API 31)
- **Frame time**: ≤ 11ms for 90% of frames during scroll
- **Video playback start**: p50 < 1.2s from tap to first frame
- **Memory usage**: Average < 150MB, peak < 250MB

---

## Admin Frontend Architecture

### Technology Stack
- **Framework**: Vue 3 + Vite + TypeScript
- **State Management**: Pinia
- **Routing**: Vue Router
- **Localization**: vue-i18n (English, Arabic RTL, Dutch)
- **HTTP Client**: Axios
- **UI**: Custom components with design tokens

### Key Modules
- **Authentication**: Firebase Auth (email/password login)
- **Content Search**: YouTube search integration
- **Pending Approvals**: Review and approve/reject submissions
- **Content Library**: Manage all approved content with advanced filtering
  - Dual layout (desktop sidebar + mobile bottom sheet)
  - Multi-select with bulk actions (approve, delete, assign categories)
  - Advanced filtering (type, status, category, date, search)
  - Sort options (newest, oldest, alphabetical)
  - Inline editing and exclusion management
- **Exclusions Workspace**: Dedicated UI for managing excluded items
  - Search and type filtering
  - Bulk removal operations
  - Parent context display (shows which channel/playlist)
  - Reason tracking and audit trail
- **Bulk Import/Export**:
  - Full format: Complete JSON backup with merge strategies
  - Simple format: JSON-like format with downloadable templates
  - Selective export (categories, channels, playlists, videos)
  - Option to exclude unavailable videos
- **Video Validation**: Manual trigger to detect unavailable YouTube videos
  - Validation history tracking with statistics
  - Status tracking (NEVER_RUN, RUNNING, COMPLETED, FAILED, ERROR)
  - Dashboard panel showing status and unavailable counts
- **Dashboard Metrics**:
  - Pending queue depth, content totals, moderator activity
  - Comparison to previous timeframe with trend indicators
  - Timeframe selector (Last 24h / Last 7 Days / Last 30 Days)
  - Validation status panel with error counts
- **Audit Logs**: Filter by actor, action, date range
- **User Management**: Create/assign ADMIN/MODERATOR roles

### Security
- **Authentication**: Firebase JWT tokens in Authorization header
- **RBAC**: ADMIN (full permissions), MODERATOR (submit for review only)
- **CORS**: Allowed origins: `http://localhost:5173`, `http://127.0.1.1:5173`

### Performance Budgets (PRD Requirements)
- **Time to Interactive**: < 3s on desktop Chrome
- **First Contentful Paint**: < 1.5s
- **Bundle size**: < 500KB gzipped (initial load)

---

## Approval Workflow

### User-Facing Screens
1. **Content Search** - Search YouTube and add content for approval
2. **Pending Approvals** - Review and approve/reject submissions
3. **Content Library** - Manage all approved content

### Backend Flow
1. **Content Search**: Admin searches YouTube via `YouTubeSearchController`
2. **Add for Approval**: Click "Add for Approval" → Opens `CategoryAssignmentModal`
3. **Assign Categories**: Select one or more categories
4. **Submit to Pending**: Content added via `RegistryController` with `pending=true`, `approved=false`
5. **Pending Approvals**: Admin reviews items in Pending Approvals view
6. **Approve/Reject**: Admin decision updates flags (`approved=true` or rejected)
7. **Content Library**: Approved content appears in library for management
8. **Public API**: Approved content (`approved=true`) served to Android app

### Role Differences
- **ADMIN**: Can directly approve content, manage users, access all features
- **MODERATOR**: Can only submit content for admin review, view dashboard metrics

**Note**: "Registry" is backend-only internal naming (`RegistryController`), not exposed in UI.

---

## Data Flow

1. **Admin curates content**: Searches YouTube, adds channels/playlists/videos with categories
2. **Approval workflow**: Pending items reviewed by admins, assigned categories, approved/rejected
3. **Mobile app fetches**: Android requests lists with filters (`/api/v1/content`), backend returns approved content only
4. **Stream resolution**: Android uses NewPipeExtractor to resolve stream URLs (never official YouTube API)
5. **Downloads**: Mobile requests download, backend issues token, Android downloads via WorkManager
6. **Audit trail**: All approval actions logged to `audit_logs` collection

---

## Deployment & Operations

### Environments
- **Dev**: Local development (Caffeine cache, localhost backend)
- **Staging**: VPS deployment (optional Redis, production Firebase)
- **Prod**: VPS deployment with HTTPS/TLS

### CI/CD (GitHub Actions)
- **Backend**: `./gradlew test build`
- **Frontend**: `npm test && npm run build`
- **Android**: `./gradlew test assembleDebug`

### Monitoring (MVP)
- **Backend**: Spring Boot Actuator health checks, Prometheus metrics endpoint
- **Frontend**: Browser DevTools, Vue DevTools
- **Android**: Logcat, Android Studio profiler
- **Deferred to v1.1+**: Grafana dashboards, automated alerts, Firebase Crashlytics

---

## Performance & Scaling

### Caching Strategy
- **Backend**: Caffeine (dev) / Redis (prod optional) with 5-min TTL, cache stampede protection
- **Cache keys**: YouTube search results (channels, playlists, videos)
- **Target**: ≥85% cache hit ratio

### Indexing (Firestore)
- Composite indexes on: category + approval status, published date + status
- Single-field indexes: `youtubeId`, `pending`, `approved`, `topLevel`

### Scalability Levers (Future)
- **CDN**: CloudFront for thumbnails (reduce cold-start bandwidth)
- **Kubernetes HPA**: Scale pods on CPU and queue depth
- **Circuit breakers**: Fall back to stale cache when Redis down
- **Database sharding**: Firestore auto-scales, but consider Supabase migration if costs exceed $500/month

---

## Testing Strategy

### Backend (JUnit 5)
- **Unit tests**: Mock Firestore repositories
- **Integration tests**: Firebase Emulator Suite
- **Performance tests**: Gatling load testing (200 RPS, p95 < 200ms)
- **Scope**: Test only `/backend` directory (exclude `/frontend` and `/android`)

### Frontend (Vitest + Playwright)
- **Unit tests**: Component tests with `@testing-library/vue`
- **E2E tests**: Playwright (approval workflow, category assignment)
- **Timeout**: 300 seconds (5 minutes) enforced per AGENTS.md policy
- **Scope**: Test only `/frontend` directory (Vue 3 Admin Dashboard)
- **IMPORTANT**: Exclude `/android` directory from frontend test scope (Android has separate native testing)

### Android (Kotlin Native Testing)
- **Unit tests**: `./gradlew test` (JUnit, Mockito)
- **Instrumentation tests**: `./gradlew connectedAndroidTest` (Espresso)
- **Manual testing**: Device/emulator with real backend
- **Scope**: Native Kotlin/Gradle testing separate from frontend

### TestSprite Integration
- **Frontend Testing**: Test only `/frontend` directory (exclude `/android`)
- **Backend Testing**: Test only `/backend` directory (exclude `/frontend` and `/android`)
- Android app has separate native testing tooling

See [../status/TESTING_GUIDE.md](../status/TESTING_GUIDE.md) for detailed testing procedures.

---

## Known Limitations & Future Work

### MVP Limitations
- **iOS**: Android-only for MVP (iOS in v1.2+)
- **User accounts**: Mobile app is public access only (no login, no profiles)
- **Community suggestions**: No end-user content submission (admin-curated only)
- **AI moderation**: Manual human review only (auto-approval rules in v1.1)
- **Advanced analytics**: Basic event tracking only (watch time heatmaps deferred)
- **Social features**: No comments, likes, in-app discussions

### Future Enhancements (v1.1+)
- **Extractor fallback**: FreeTube/Invidious when NewPipe breaks
- **Auto-approval**: Trusted channels skip manual review
- **Volunteer moderators**: Onboarding program for community moderators
- **Advanced monitoring**: Grafana, alerting, Firebase Crashlytics
- **Platform expansion**: iOS, Desktop, Smart TVs (Samsung, LG, Google TV)

---

## Traceability
- **PRD**: [../PRD.md](../PRD.md) - Product requirements
- **API Spec**: [api-specification.yaml](api-specification.yaml) - OpenAPI 3.0.3 contract (113+ endpoints, 60+ schemas)
- **Security**: [security.md](security.md) - Threat model
- **C4 Diagrams**: [diagrams/](diagrams/) - Context, component diagrams
- **Testing**: [../status/TESTING_GUIDE.md](../status/TESTING_GUIDE.md) - Test strategy
- **Deployment**: [../status/DEPLOYMENT_GUIDE.md](../status/DEPLOYMENT_GUIDE.md) - Ops guide
- **Android**: [../status/ANDROID_GUIDE.md](../status/ANDROID_GUIDE.md) - Android setup

---

**Last Updated**: November 16, 2025
**Status**: ~60% complete - Backend & frontend working, Android ready for testing
