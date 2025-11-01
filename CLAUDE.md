# Albunyaan Tube - Claude Developer Guide

This document provides essential information for AI assistants working in the Albunyaan Tube codebase. It covers the project architecture, development workflow, build commands, and key patterns to understand this multi-platform ad-free, admin-curated halal YouTube client.

---

## 🎯 **WHERE TO START - CURRENT SESSION STATUS**

**Date:** October 31, 2025
**Status:** Android connectivity issue FIXED, ready for device testing
**Next Step:** Install APK on physical device and verify data loads

### **What Just Happened (Oct 31, 2025):**

1. **CORS Issue Identified & Fixed** ✅
   - Problem: Backend CORS only allowed web frontend, blocking mobile app requests
   - Solution: Added `setAllowedOriginPatterns("*")` in `SecurityConfig.java:88`
   - File: [backend/src/main/java/com/albunyaan/tube/security/SecurityConfig.java](backend/src/main/java/com/albunyaan/tube/security/SecurityConfig.java#L80-L97)

2. **Android APK Rebuilt** ✅
   - Configured for user's device IP: `http://192.168.1.167:8080/`
   - File: [android/app/build.gradle.kts:35](android/app/build.gradle.kts#L35)
   - APK: `android/app/build/outputs/apk/debug/app-debug.apk` (15MB)

3. **Backend Running with CORS Fix** ✅
   - Accessible at `http://192.168.1.167:8080`
   - All endpoints tested and working
   - Seeded data: 20 channels, 16 playlists, 76 videos, 19 categories

### **Your Immediate Task:**

```bash
# 1. Verify backend is accessible
curl http://192.168.1.167:8080/api/v1/categories | jq '. | length'
# Should return: 19

# 2. Install APK on device
adb install -r /home/farouq/Development/albunyaantube/android/app/build/outputs/apk/debug/app-debug.apk

# 3. Test app and verify data loads from backend
# See: docs/android/TESTING_GUIDE.md for complete checklist
```

**If Issues Occur:**
- See: [docs/android/CONNECTIVITY_TROUBLESHOOTING.md](docs/android/CONNECTIVITY_TROUBLESHOOTING.md)
- Check: [CONTEXT_RESUME.md](CONTEXT_RESUME.md) for latest session state

**Git Commits:**
- `efb0205` - [FIX]: Enable Android app connectivity to backend
- `d301616` - [DOCS]: Add context resume for session continuity

---

## Quick Reference

### Project Overview
- **Type**: Full-stack monorepo with admin UI, REST backend, and mobile app
- **Technologies**: Spring Boot (Java 17), Vue 3 (TypeScript), Kotlin (Android), Firebase
- **Purpose**: Ad-free, admin-curated Islamic content platform with approval workflows
- **Status**: ~60% complete - Backend & frontend working, Android ready for testing

### Critical Policy
- **Test Timeout**: All tests must complete in 300 seconds (5 minutes) - see AGENTS.md
- **Internationalization**: English, Arabic (RTL), Dutch supported across all platforms
- **Firebase**: Firestore (database), Auth (authentication), Cloud Storage (downloads)

---

## Part 1: Build, Test, and Development Commands

### Backend (Spring Boot + Java 17)

**Location**: `/home/farouq/Development/albunyaantube/backend`

```bash
# Install dependencies
cd backend
./gradlew dependencies

# Run backend locally (starts on http://localhost:8080)
./gradlew bootRun

# Run tests (JUnit 5)
./gradlew test

# Build JAR artifact
./gradlew bootJar

# Clean build
./gradlew clean build

# View available tasks
./gradlew tasks
```

**Key Gradle Plugins**:
- Spring Boot 3.2.5 (`org.springframework.boot`)
- Gatling performance testing (`io.gatling.gradle`)

### Frontend (Vue 3 + TypeScript + Vite)

**Location**: `/home/farouq/Development/albunyaantube/frontend`

```bash
# Install dependencies
cd frontend
npm ci  # or npm install

# Run dev server (starts on http://localhost:5173)
npm run dev

# Run tests (Vitest with 300s timeout enforced)
npm test

# Run E2E tests (Playwright)
npm run test:e2e

# Build for production
npm run build

# Preview production build locally
npm run preview

# Run TypeScript type checking
npm run build  # includes vue-tsc --noEmit
```

**Key npm Scripts**:
- `dev`: Vite development server with HMR
- `build`: TypeScript check + Vite production bundle
- `test`: `timeout 300s vitest run` (hardcoded 5-minute limit)
- `test:e2e`: Playwright tests

### Android (Kotlin + Gradle)

**Location**: `/home/farouq/Development/albunyaantube/android`

```bash
# Build debug APK
cd android
./gradlew assembleDebug

# Run unit tests
./gradlew test

# Run instrumentation tests (requires device/emulator)
./gradlew connectedAndroidTest

# Build release APK
./gradlew assembleRelease

# Run lint checks
./gradlew lint

# Clean build
./gradlew clean build
```

### Docker-Compose (Recommended Local Setup)

**Location**: `/home/farouq/Development/albunyaantube/docker-compose.yml`

```bash
# Start all services (backend, firebase emulator)
docker-compose up -d

# View backend logs
docker-compose logs -f backend

# View firebase logs
docker-compose logs -f firebase-emulator

# Stop services
docker-compose down

# Full cleanup (warning: deletes data)
docker-compose down -v

# Restart specific service
docker-compose restart backend

# Rebuild after dependency changes
docker-compose build backend && docker-compose up -d backend
```

**Services**:
- Backend: `http://localhost:8080` (Spring Boot API)
- Frontend: `http://localhost:5173` (Vue dev server - run separately)
- Firebase Emulator UI: `http://localhost:4000`
- Firestore: `localhost:8082`
- Auth: `localhost:9099`

### Environment Setup

**Files**:
- `.env.example`: Template for environment variables
- `.env`: Local configuration (not in git)

**Required for Local Dev**:
```bash
# Copy template and add your keys
cp .env.example .env

# Then edit .env with:
FIREBASE_PROJECT_ID=albunyaan-tube  # Usually local/testing project
YOUTUBE_API_KEY=your_youtube_api_key_here
```

**Setup Scripts**:
```bash
# Run full setup
./scripts/setup-dev.sh

# Validate environment
./scripts/validate-env.sh
```

---

## Part 2: Architecture and Project Structure

### Monorepo Organization

```
albunyaantube/
├── backend/                    # Spring Boot REST API
│   ├── src/main/java/...      # Core application code
│   ├── src/test/java/...      # JUnit tests
│   ├── build.gradle.kts       # Gradle config (Java 17, Spring 3.2.5)
│   ├── Dockerfile            # Production container
│   └── src/main/resources/
│       ├── application.yml    # Spring configuration
│       └── firebase-service-account.json.template
│
├── frontend/                   # Vue 3 Admin Dashboard
│   ├── src/
│   │   ├── components/        # Vue SFC components (organized by feature)
│   │   ├── views/             # Page-level views
│   │   ├── stores/            # Pinia state management (auth, preferences, filters)
│   │   ├── services/          # API clients (axios-based)
│   │   ├── router/            # Vue Router config
│   │   ├── types/             # TypeScript interfaces
│   │   ├── utils/             # Utilities
│   │   ├── locales/           # i18n messages (en, ar, nl)
│   │   ├── constants/         # App constants
│   │   ├── assets/            # CSS tokens, images
│   │   └── main.ts            # Entry point
│   ├── tests/                 # Vitest test files
│   ├── package.json           # npm dependencies
│   ├── tsconfig.json          # TypeScript config (@ alias = src/)
│   ├── vite.config.ts         # Vite bundler config
│   ├── playwright.config.ts   # E2E test config
│   └── vitest.config.ts       # Unit test config (if separate)
│
├── android/                    # Kotlin Native App
│   ├── app/src/main/
│   │   ├── java/com/albunyaan/tube/
│   │   │   ├── ui/            # Fragments, ViewModels
│   │   │   ├── data/          # Repositories, API services
│   │   │   ├── di/            # Dependency injection
│   │   │   └── player/        # ExoPlayer integration
│   │   └── res/               # Resources (layouts, drawables, values)
│   ├── build.gradle.kts       # App build config
│   ├── settings.gradle.kts    # Module config
│   └── gradlew
│
├── docs/                       # Design & architecture docs
│   ├── vision/                # Product vision
│   ├── architecture/          # C4 diagrams, solution architecture
│   ├── api/                   # OpenAPI specs
│   ├── frontend/              # Frontend-specific docs
│   ├── backend/               # Backend setup & Firebase config
│   ├── android/               # Android-specific docs
│   └── acceptance/            # Test plans & criteria
│
├── .github/
│   └── workflows/             # CI/CD pipelines
│       ├── backend-ci.yml
│       ├── frontend-ci.yml
│       └── android-ci.yml
│
├── scripts/
│   ├── setup-dev.sh          # First-time setup
│   └── validate-env.sh       # Environment validation
│
├── docker-compose.yml         # Local dev infrastructure
├── .env.example              # Environment template
├── .gitignore                # Git exclusions
├── AGENTS.md                 # Policy for AI agents (test timeout!)
├── README.md                 # Project overview
├── CHANGELOG.md              # Version history
└── CLAUDE.md                 # This file
```

### Key Architectural Layers

#### Backend (Spring Boot 3 + Firebase Firestore)

**Layer Structure**:
```
Controller (REST endpoints)
  ↓ (request/response)
Service (business logic, Firestore queries)
  ↓ (CRUD operations)
Repository (Firestore SDK wrappers)
  ↓ (document operations)
Model (Firestore @DocumentId annotations)
```

**Controllers** (11 total, 67 endpoints):
- `PublicContentController`: Public API (videos, channels, playlists) - `/api/v1`
- `CategoryController`: Category CRUD - `/api/admin/categories`
- `ChannelController`: Channel management - `/api/admin/channels`
- `RegistryController`: Internal workflow API for adding content to pending queue - `/api/admin/registry`
  - **Note:** "Registry" is backend terminology only, not exposed in UI
  - Powers the "Add for Approval" workflow from Content Search
- `YouTubeSearchController`: YouTube search integration - `/api/admin/youtube`
- `UserController`: Admin/moderator user management - `/api/admin/users`
- `AuditLogController`: Action audit trail - `/api/admin/audit`
- `DashboardController`: Metrics & stats - `/api/admin/dashboard`
- `ApprovalController`: Content approval workflow - `/api/admin/approvals`
- `PlayerController`: Recommendations & next-up - `/api/player`
- `DownloadController`: Video download management - `/api/downloads`

**Key Services**:
- `YouTubeService`: YouTube Data API v3 integration (channel/playlist/video search with caching)
- `PublicContentService`: Content for mobile app
- `ApprovalService`: Approval workflow (auto-approval rules, pending queue)
- `AuditLogService`: Audit event logging
- `AuthService`: Firebase Auth + custom claims for roles
- `PlayerService`: Next-up recommendations
- `DownloadService`: Download token generation & policy checks

**Models** (Firestore documents with @DocumentId annotations):
- `Category`: Hierarchical categories (topLevel flag for root categories)
- `Channel`: YouTube channels with approval status
- `Playlist`: YouTube playlists
- `Video`: YouTube videos with approval metadata
- `User`: Admin/moderator accounts
- `AuditLog`: Action history
- `DownloadEvent`: Download tracking

**Caching** (BACKEND-PERF-01):
- Development: Caffeine (in-memory cache)
- Production: Redis (configure via spring.cache.type)
- Caches: `youtubeChannelSearch`, `youtubePlaylistSearch`, `youtubeVideoSearch`

**Security**:
- Firebase Authentication (JWT tokens via Firebase Admin SDK)
- Custom claims for role-based access control (ADMIN, MODERATOR)
- CORS configured for frontend localhost:5173

#### Frontend (Vue 3 + Pinia + Vite)

**Component Organization**:
```
components/
├── admin/              # Admin-specific UI (moderation, approval)
├── categories/         # Category tree, assignment modal
├── search/            # YouTube search, results
├── navigation/        # App shell, main tab bar
├── common/            # Shared UI (buttons, dialogs, etc.)
├── content/           # Content display components
└── ui/                # Basic UI primitives
```

**State Management** (Pinia stores):
- `auth.ts`: Firebase authentication (login, logout, currentUser)
- `preferences.ts`: User preferences (locale, dark mode)
- `registryFilters.ts`: Legacy store (may need renaming to contentFilters)

**Services** (API clients):
- `api/`: Base HTTP client with axios
- `approvalService.ts`: Approval workflow API calls
- `youtubeService.ts`: YouTube search integration
- `categoryService.ts`: Category API calls
- `registry.ts`: Content registry endpoints
- `adminUsers.ts`: User management
- `adminAudit.ts`: Audit log fetching

**Routing** (Vue Router):
- Login → Dashboard → Admin views (categories, registry, users, etc.)
- Role-based access control via auth store

**i18n** (Vue-i18n):
- Messages in `src/locales/messages.ts` (en, ar, nl)
- RTL support via `document.dir` attribute (Arabic)
- Locale picker in preferences store

**Styling**:
- CSS custom properties (design tokens) in `src/assets/main.css`
- Dark mode support via `--color-*` variables
- Tailwind-like approach with utility classes

#### Android (Kotlin + Jetpack)

**Architecture** (MVVM):
```
Fragment (UI)
  ↓ (observes)
ViewModel (business logic, state)
  ↓ (calls)
Repository (data source abstraction)
  ↓ (HTTP calls)
RetrofitService (API client)
  ↓
Spring Boot backend
```

**Key Features**:
- Material Design 3 with bottom navigation (5 tabs)
- RTL support for Arabic locale
- Offline-first architecture (DataStore preferences)
- ExoPlayer for video playback with NewPipe extractor
- WorkManager for background downloads
- Jetpack Navigation for fragment routing

**Screens** (16 total):
- Onboarding (3-page carousel)
- Main shell with tabs: Home, Channels, Playlists, Videos, More
- Channel/Playlist detail screens
- Video player
- Search (backend-integrated via `/api/v1/search`)
- Categories (backend-integrated via `/api/v1/categories`)
- Downloads
- Settings

**Data Layer**:
- Retrofit for HTTP calls (API_BASE_URL = `http://10.0.2.2:8080` for emulator)
- DataStore for preferences (replaces SharedPreferences)
- Repository pattern for data access

### Communication Between Components

**Frontend ↔ Backend**:
- HTTP/REST via axios (Vue) and Retrofit (Android)
- Base URL: `http://localhost:8080/api/v1` (frontend), `http://10.0.2.2:8080/api` (Android emulator)
- JSON request/response payloads
- Firebase JWT tokens in Authorization header

**Backend ↔ Firebase**:
- Firestore Admin SDK (Java) for document operations
- Firebase Authentication SDK for token validation
- Collections: categories, channels, playlists, videos, users, audit_logs, etc.

**Mobile ↔ Backend**:
- Same REST API as frontend (shared endpoints)
- Public endpoints: `/api/v1/content`, `/api/v1/categories`, `/api/v1/search`
- Admin endpoints: `/api/admin/*` (require authentication)

---

## Part 3: Key Architectural Patterns and Conventions

### Naming and Organization

**Controllers**:
- Endpoint pattern: `/api/{scope}/{resource}/{action}`
- Example: `/api/admin/approvals/{id}/approve` (scope=admin, resource=approvals, action=approve)
- RESTful methods: GET (fetch), POST (create), PUT (update), DELETE (remove)

**Services**:
- Business logic layer between controllers and repositories
- Naming: `{Entity}Service` (e.g., `YouTubeService`, `ApprovalService`)
- Dependency injection via Spring's `@Autowired` or constructor injection

**Repositories**:
- Firestore wrapper classes using Firebase Admin SDK
- Naming: `{Entity}Repository` (e.g., `CategoryRepository`)
- Methods: `findById()`, `findAll()`, `save()`, `delete()`, `query()`

**Pinia Stores** (Frontend):
- Naming: `{feature}.ts` (e.g., `auth.ts`, `registryFilters.ts`)
- State + Getters + Actions pattern
- Composable stores with `defineStore()`

**Vue Components**:
- Single-file components (`.vue`)
- Naming: PascalCase (e.g., `CategoryAssignmentModal.vue`)
- Script setup syntax with `<script setup>`
- TypeScript interfaces in separate `types/` folder

### Approval Workflow

**User-Facing Screens:**
1. **Content Search** - Search YouTube and add content for approval
2. **Pending Approvals** - Review and approve/reject submissions
3. **Content Library** - Manage all approved content

**Backend Flow** (implemented in BACKEND-FIX-01 and ADMIN-CAT-01):
1. **Content Search**: Admin searches YouTube via `YouTubeSearchController`
2. **Add for Approval**: Click "Add for Approval" → Opens `CategoryAssignmentModal`
3. **Assign Categories**: Select one or more categories for the content
4. **Submit to Pending**: Content added via `RegistryController` with status `PENDING`
5. **Pending Approvals**: Admin reviews items in `PendingApprovalsView`
6. **Approve/Reject**: Admin decision updates status to `APPROVED` or `REJECTED`
7. **Content Library**: Approved content appears in Content Library for management
8. **Public API**: Approved content served to Android app via `/api/v1/content`

**Models Involved**:
- `Channel`, `Playlist`, `Video` all have `ApprovalMetadata` with `approved`, `pending`, `categoryIds`
- `AuditLog` tracks all approvals (who, what, when)

**Note:** "Registry" is internal backend terminology (RegistryController) and is not exposed in the UI.

### Content Seeding

**Pattern** (BACKEND-PERF-01):
- Spring profile `seed` populates Firestore with baseline data
- Run: `./gradlew bootRun --args='--spring.profiles.active=seed'`
- Idempotent: existing docs are updated, new ones created
- Cleans up legacy seed documents (`createdBy` = `system`)
- Writes: 19 categories, 25 channels, 19 playlists, 76 videos

### Caching Strategy

**Backend** (application.yml):
- Caffeine for dev (maximumSize=1000, 1-hour TTL)
- Redis for prod (configure `spring.cache.type: redis`)
- Cached endpoints: YouTube searches (channels, playlists, videos)
- Cache names: `youtubeChannelSearch`, `youtubePlaylistSearch`, `youtubeVideoSearch`

**Frontend**:
- Axios interceptors can be added for client-side caching
- Pinia stores act as in-memory caches for app state

### Error Handling

**Backend**:
- Controller `@ExceptionHandler` methods return structured error responses
- HTTP status codes: 200 (ok), 400 (bad request), 401 (unauthorized), 403 (forbidden), 404 (not found), 500 (server error)
- FirebaseFirestoreException handling for document operations

**Frontend**:
- Axios interceptors catch HTTP errors
- User feedback via toast/snackbar notifications
- Error states in Pinia stores (e.g., `authError`, `loading`)

**Android**:
- Retrofit error handling in Repository layer
- LiveData/StateFlow for error state exposure to UI

### Internationalization (i18n)

**Supported Languages**: English (en), Arabic (ar), Dutch (nl)

**Frontend**:
- Messages defined in `src/locales/messages.ts` (nested key structure)
- Vue-i18n for translation lookups: `{{ $t('key.nested.path') }}`
- Locale persistence via `preferences.ts` store
- RTL support: `document.dir = locale === 'ar' ? 'rtl' : 'ltr'`

**Backend**:
- REST endpoints return JSON (language-agnostic)
- Frontend is responsible for localization

**Android**:
- Strings in `res/values/strings.xml` (en), `res/values-ar/` (ar), etc.
- Material Design 3 RTL support built-in

### Testing Patterns

**Backend** (JUnit 5):
- Unit tests mock Firestore repositories
- Integration tests use Firebase Emulator
- Test classes: `{Entity}ControllerTest`, `{Service}ServiceTest`

**Frontend** (Vitest + Testing Library):
- Component tests with `@testing-library/vue`
- Service/store tests with mocked axios
- 300-second timeout enforced (AGENTS.md policy)

**Android**:
- Unit tests: `src/test/`
- Instrumentation tests: `src/androidTest/` (requires device/emulator)
- MockWebServer for API mocking

### Performance Optimization

**Backend**:
- Firestore indexes for complex queries (composite indexes in `firestore.rules`)
- Caching for YouTube search results
- Response compression (gzip enabled in application.yml)
- Pagination for large result sets

**Frontend**:
- Code splitting: separate chunks for vue-core, firebase, utils, views
- Tree-shaking: unused code removed during build
- Minification: terser with 2 passes, drop_console in production
- CSS code splitting

**Android**:
- Lazy loading for Fragments
- Image caching via Glide/Coil (if used)
- Background work via WorkManager

---

## Part 4: Development Workflow and Git Conventions

### Branch Strategy

- **main**: Production-ready code
- **Feature branches**: `feature/{issue-id}-{description}` (created from main)
- **Bugfix branches**: `fix/{issue-id}-{description}` (created from main)

### Commit Message Format

```
[PREFIX]: Description (under 50 chars)

Optional detailed explanation (wrapped at 72 chars)

```

**Prefixes**:
- `[FEAT]`: New feature
- `[FIX]`: Bug fix
- `[REFACTOR]`: Code refactoring
- `[PERF]`: Performance improvement
- `[DOCS]`: Documentation update
- `[TEST]`: Test addition/update
- `[CHORE]`: Build, dependencies, tooling

**Example**:
```
[FEAT]: Add category assignment modal for content approval

- Implement CategoryAssignmentModal component
- Integrate with RegistryFilters store
- Add API call to update category on approval
- Add tests for component interaction

```

### Pull Request Process

1. **Create branch** from main
2. **Implement feature** with tests
3. **Run CI locally** (or wait for GitHub Actions):
   - Backend: `./gradlew test build`
   - Frontend: `npm test && npm run build`
   - Android: `./gradlew test assembleDebug`
4. **Push to remote** with descriptive commit messages
5. **Create PR** with summary of changes
6. **Address review feedback**
7. **Merge** when CI passes and approved

### Local Development Checklist

- [ ] Copy `.env.example` → `.env` and add API keys
- [ ] Run `./scripts/setup-dev.sh` for first-time setup
- [ ] Run `./scripts/validate-env.sh` to verify configuration
- [ ] Start Docker: `docker-compose up -d`
- [ ] Backend: `cd backend && ./gradlew bootRun`
- [ ] Frontend: `cd frontend && npm run dev`
- [ ] Android: Open `android/` in Android Studio
- [ ] Test: Run suite for your platform (see Part 1 commands)

---

## Part 5: Important Files and Where to Look

### Configuration Files

| File | Purpose | Key Contents |
|------|---------|--------------|
| `backend/build.gradle.kts` | Backend dependencies & build | Spring Boot, Firebase, YouTube API, Gradle plugins |
| `backend/src/main/resources/application.yml` | Spring configuration | Cache, security, Firebase paths, CORS origins |
| `frontend/package.json` | Frontend dependencies | Vue, Vite, Pinia, Firebase, testing libs |
| `frontend/vite.config.ts` | Vite bundler config | Code splitting, minification, dev server |
| `frontend/tsconfig.json` | TypeScript config | @ alias for src/, strict mode |
| `android/build.gradle.kts` | Android app build | SDK versions, dependencies, build flavors |
| `docker-compose.yml` | Local infrastructure | Backend, Firebase Emulator services & ports |
| `.env.example` | Environment template | FIREBASE_PROJECT_ID, YOUTUBE_API_KEY |
| `.github/workflows/` | CI/CD pipelines | Build, test, lint steps for each platform |

### Documentation Files

| File | Purpose |
|------|---------|
| `README.md` | Project overview, navigation guide |
| `AGENTS.md` | Policy for AI agents (test timeout!) |
| `docs/PROJECT_STATUS.md` | Current state, blockers, completion estimate |
| `docs/DEVELOPMENT_GUIDE.md` | Detailed setup, troubleshooting, deployment |
| `docs/architecture/` | C4 diagrams, solution architecture, decision records |
| `docs/api/openapi-draft.yaml` | REST API specification |
| `docs/frontend/dashboard-metrics-plan.md` | Dashboard design & metrics |
| `docs/backend/` | Firebase setup, data models |
| `docs/android/` | Android-specific setup & release guide |

### Key Source Files

**Backend** (understand these first):
- `backend/src/main/java/com/albunyaan/tube/controller/PublicContentController.java` (public API)
- `backend/src/main/java/com/albunyaan/tube/controller/ApprovalController.java` (approval flow)
- `backend/src/main/java/com/albunyaan/tube/service/YouTubeService.java` (YouTube integration)
- `backend/src/main/java/com/albunyaan/tube/model/` (all Firestore models)

**Frontend** (understand these first):
- `frontend/src/views/` (page-level views like Dashboard, PendingApprovals)
- `frontend/src/components/CategoryAssignmentModal.vue` (approval workflow UI)
- `frontend/src/services/youtubeService.ts` (search integration)
- `frontend/src/stores/auth.ts` (authentication state)
- `frontend/src/stores/registryFilters.ts` (content filtering)

**Android** (understand these first):
- `android/app/src/main/java/com/albunyaan/tube/ui/` (Fragments & ViewModels)
- `android/app/src/main/java/com/albunyaan/tube/data/service/` (Retrofit API client)
- `android/app/src/main/res/navigation/` (Navigation graph)

---

## Part 6: Common Tasks and How to Approach Them

### Adding a New API Endpoint

1. **Create Controller method** in `backend/src/main/java/com/albunyaan/tube/controller/`
   - Annotate with `@GetMapping`, `@PostMapping`, etc.
   - Use `@RequestParam`, `@PathVariable` for inputs
   - Return DTO object (in `backend/src/main/java/com/albunyaan/tube/dto/`)

2. **Create Service method** to handle business logic
   - Call Repository methods
   - Handle Firebase operations
   - Log actions via `AuditLogService`

3. **Create Repository method** if needed
   - Use Firebase Admin SDK: `db.collection("...").whereEqualTo(...).get()`

4. **Add security** (if admin-only)
   - Use Spring Security `@PreAuthorize("hasRole('ADMIN')")`
   - Or check `AuthService.getCurrentUserRole()`

5. **Test** with JUnit 5 in `backend/src/test/java/`
   - Mock Firestore repositories
   - Verify HTTP status codes and payloads

6. **Update frontend** to call new endpoint
   - Add method in `frontend/src/services/api.ts` or specific service
   - Call from component or store
   - Add error handling

### Modifying the Approval Workflow

1. **Backend**: Update `ApprovalController`, `ApprovalService`, `Channel/Playlist/Video` models
2. **Frontend**: Update `PendingApprovalsView.vue`, `approvalService.ts`, `registryFilters.ts`
3. **Database**: Ensure Firestore indexes exist for approval status queries
4. **Test**: Write E2E test covering search → approval → public visibility

### Adding Translation Support

1. **Frontend**: Add key/value to `frontend/src/locales/messages.ts` (all 3 languages)
2. **Vue template**: Use `{{ $t('key.path') }}`
3. **Backend**: Return English text; frontend handles localization

### Debugging Backend Issues

```bash
# View backend logs in docker-compose
docker-compose logs -f backend

# Run backend locally with debug logging
cd backend
LOGGING_LEVEL_COM_ALBUNYAAN=DEBUG ./gradlew bootRun

# Check Firebase Emulator UI
open http://localhost:4000  # View Firestore documents
```

### Debugging Frontend Issues

```bash
# Run dev server with verbose output
cd frontend
npm run dev

# Check browser DevTools (F12)
# - Network tab: API calls
# - Console: JavaScript errors
# - Application: Pinia stores (Vue DevTools)

# Run tests with debugging
npm test -- --reporter=verbose
```

### Debugging Android Issues

```bash
# Run emulator with API 31+
Android Studio → Device Manager → Create Virtual Device

# View Logcat in Android Studio
Android Studio → View → Tool Windows → Logcat

# Connect to local backend
# In android/app/build.gradle.kts:
buildConfigField("String", "API_BASE_URL", "\"http://10.0.2.2:8080/\"")
# (10.0.2.2 is the emulator's gateway to host localhost)
```

---

## Part 7: Known Issues and Workarounds

### Current Blockers (from PROJECT_STATUS.md)

1. **Firestore Model Warnings** (FIXED in latest)
   - Fields `topLevel`, `pending`, `approved` added to models
   - Deploy backend to verify warnings cleared

2. **Dashboard Metrics Structure** (FIXED in commit 87d4536)
   - Backend now returns proper `{data, meta}` structure
   - Frontend should load without errors

3. **Missing Backend Endpoints** (PARTIAL)
   - Settings persistence: Not implemented
   - Content library: Endpoints exist but incomplete
   - Exclusions: UI exists, backend missing
   - Bulk import/export: UI exists, backend missing

4. **Android Shows Empty Screens**
   - Categories API wired (✅ FIXED 2025-10-05)
   - Search API wired (✅ FIXED 2025-10-05)
   - Other tabs need data seeding verification

### Workarounds

**Issue**: Backend doesn't start - Firebase credentials not found
```bash
# Solution:
export GOOGLE_APPLICATION_CREDENTIALS="backend/src/main/resources/firebase-service-account.json"
./gradlew bootRun
```

**Issue**: Port 8080 already in use
```bash
# Solution:
lsof -ti:8080 | xargs kill -9
```

**Issue**: Frontend can't connect to backend API
```bash
# Solution: Check VITE_API_BASE_URL in frontend/.env.local
VITE_API_BASE_URL=http://localhost:8080/api/v1
```

**Issue**: Android emulator can't reach backend
```bash
# Solution: Use 10.0.2.2 (emulator's host gateway)
# In android/app/build.gradle.kts:
buildConfigField("String", "API_BASE_URL", "\"http://10.0.2.2:8080/\"")
```

---

## Part 8: Resources and References

### Documentation
- **Full architecture**: `docs/architecture/solution-architecture.md`
- **API spec**: `docs/api/openapi-draft.yaml` (OpenAPI format)
- **Testing strategy**: `docs/testing/test-strategy.md`
- **Security**: `docs/security/threat-model.md`
- **Internationalization**: `docs/i18n/strategy.md`
- **Roadmap**: `docs/roadmap/roadmap.md`

### External Links
- [Spring Boot Docs](https://spring.io/projects/spring-boot)
- [Vue 3 Docs](https://vuejs.org/)
- [Pinia State Management](https://pinia.vuejs.org/)
- [Firebase Admin SDK](https://firebase.google.com/docs/admin/setup)
- [Firestore Documentation](https://firebase.google.com/docs/firestore)
- [YouTube Data API v3](https://developers.google.com/youtube/v3)
- [Android Jetpack](https://developer.android.com/jetpack)
- [Kotlin Coroutines](https://kotlinlang.org/docs/coroutines-overview.html)

### Tools
- **git**: Version control ([Git Docs](https://git-scm.com/doc))
- **Gradle**: Build automation (backend & Android)
- **npm**: Package manager (frontend)
- **Docker**: Containerization
- **Firebase Emulator Suite**: Local Firebase testing
- **Vite**: Frontend bundler
- **Vitest**: Unit testing framework
- **Playwright**: E2E testing
- **GitHub Actions**: CI/CD pipelines

---

## Summary

This codebase is a **full-stack halal content platform** with:
- **Clear separation of concerns**: Controller → Service → Repository → Model
- **Consistent patterns**: Pinia for state, Retrofit for APIs, Spring for REST
- **Strong emphasis on i18n**: English, Arabic (RTL), Dutch across all platforms
- **Firebase-first architecture**: Firestore for data, Auth for users, Cloud Storage for downloads
- **Approval workflow**: Admins search YouTube, add to registry, assign categories, approve for public
- **Performance-conscious**: Caching (Caffeine/Redis), code splitting, lazy loading, compression

**Next Steps**:
1. Read AGENTS.md (test timeout policy!)
2. Read docs/PROJECT_STATUS.md (know what's broken)
3. Set up local environment with docker-compose
4. Run one platform's tests to verify setup
5. Pick a feature from PROJECT_STATUS.md and contribute

---

**Last Updated**: 2025-10-27
**For Questions**: See docs/ folder or review existing code patterns
