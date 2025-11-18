# Albunyaan Tube - Claude Developer Guide

This document provides essential information for AI assistants working in the Albunyaan Tube codebase. It covers the project architecture, development workflow, build commands, and key patterns to understand this multi-platform ad-free, admin-curated halal YouTube client.

---

## 🎯 **WHERE TO START - CURRENT SESSION STATUS**

**Date:** November 18, 2025
**Status:** Phase 5 code review fixes complete (service layer refactoring)
**Next Step:** Phase 6 or continue with pagination DTO standardization (P1-T4)

### **Recent Updates (Nov 18, 2025):**

1. **Phase 5 Code Review Fixes** ✅
   - **Service Layer Separation**: Refactored `approvalService.ts` to pure IO (68 lines, down from 168)
   - **Extracted Transformers**: Created `/frontend/src/utils/approvalTransformers.ts` with UI mapping logic
   - **Composable Integration**: Wired `useApprovals` composable into `PendingApprovalsView.vue`
   - **Test Coverage**: Added 13 tests for `useApprovals` composable
   - **DTO Aliasing Removal**: Eliminated all `a || b` patterns in categoryService, youtubeService
   - Validation: `npm run build` and `npm test` (165 passed, 1 skipped)

2. **Architectural Decisions (Phase 5)**:
   - **Services**: Pure IO only (API calls, return raw DTOs)
   - **Utils/Transformers**: Pure functions for DTO → UI model mapping
   - **Composables**: Domain logic, state management, side effects (toasts)
   - **Views**: Consume composables, minimal logic

### **Recent Updates (Nov 17, 2025):**

1. **Frontend Field Aliasing Removal (P1-T3)** ✅
   - Refactored frontend services to consume canonical OpenAPI-generated DTO fields only (no `a || b` aliasing)
   - `approvalService.ts`: parses formatted subscriber counts, maps playlist `itemCount` to UI `videoCount`, pulls categories from `dto.category`
   - Validation: `npm run build` and `npm test` (150 passed, 4 skipped) with regenerated OpenAPI types

2. **OpenAPI Code Generation (P1-T2)** ✅
   - Implemented TypeScript DTO generation with `openapi-typescript`
   - Implemented Kotlin DTO generation with `openapi-generator-cli`
   - Created unified generation script: `./scripts/generate-openapi-dtos.sh`
   - Integrated into CI pipelines (frontend, backend, Android)
   - Output paths:
     - TypeScript: `frontend/src/generated/api/schema.ts`
     - Kotlin: `android/app/src/main/java/com/albunyaan/tube/data/model/api/`
   - Decision: Single source of truth from `docs/architecture/api-specification.yaml`
   - Rationale: Type safety, reduced duplication, automatic sync with API spec
   - Migration guide: `frontend/src/types/API_MIGRATION_GUIDE.md`

### **Recent Updates (Nov 16, 2025):**

1. **CI/CD and Documentation Alignment** ✅
   - Removed `--coverage` from canonical frontend test command (aligned with CI workflow)
   - Decision: Coverage collection is not configured in vitest, so removed from docs to prevent confusion
   - Rationale: Keep documentation and CI consistent; coverage can be added later if needed

2. **Integration Test Separation** ✅
   - Backend CI now runs unit tests only by default (`-Pintegration=false`)
   - Added separate on-demand/nightly job for integration tests
   - Decision: Fast unit tests in every PR, integration tests on manual trigger or nightly schedule
   - Rationale: Unit tests run in <10s, integration tests need Firebase emulator and take 30-60s

3. **Frontend Test Artifacts** ✅
   - Added JUnit XML reporter to vitest configuration
   - CI now uploads test reports on failure to `frontend/test-results/`
   - Decision: Use JUnit format for consistency with backend and Android
   - Rationale: Standardized artifact format across all platforms for easier debugging

4. **Android Dependency Report** ✅
   - Removed `android/app/deps.txt` from repository (generated file)
   - Added to .gitignore with comment explaining how to regenerate
   - Decision: Generate dependency reports dynamically, not in source control
   - Rationale: Keeps repository clean, prevents merge conflicts on generated files

5. **Gradle Constraint Documentation** ✅
   - Added `because(...)` clauses to all Netty version constraints
   - Decision: Document all dependency version constraints consistently
   - Rationale: Helps future developers understand why versions are pinned

### **Recent Updates (Nov 7, 2025):**

1. **Radical Documentation Simplification** ✅
   - Consolidated from 22 directories → 4 core categories
   - Merged 11 files into 3 comprehensive guides
   - Archived implementation details
   - Result: 45% fewer files, 82% fewer directories

2. **New 4-Category Structure** ✅
   - **design/**: UX, i18n, design system
   - **architecture/**: Technical architecture, API, security
   - **plan/**: Roadmap, acceptance criteria, backlog
   - **status/**: Current status & operational guides

3. **Android Configuration** ✅
   - Backend: `http://192.168.1.167:8080/`
   - APK: `android/app/build/outputs/apk/debug/app-debug.apk` (17MB)
   - Seeded data: 13 channels, 6 playlists, 173 videos, 19 categories

### **Your Immediate Tasks:**

```bash
# 1. Review simplified documentation structure
cat docs/README.md

# 2. Check current project status
cat docs/status/PROJECT_STATUS.md

# 3. For development setup
cat docs/status/DEVELOPMENT_GUIDE.md

# 4. For Android work
cat docs/status/ANDROID_GUIDE.md
```

**Quick Links:**
- **Documentation Index**: [docs/README.md](docs/README.md)
- **Current Status**: [docs/status/PROJECT_STATUS.md](docs/status/PROJECT_STATUS.md)
- **Dev Setup**: [docs/status/DEVELOPMENT_GUIDE.md](docs/status/DEVELOPMENT_GUIDE.md)
- **Android Guide**: [docs/status/ANDROID_GUIDE.md](docs/status/ANDROID_GUIDE.md)
- **Testing Guide**: [docs/status/TESTING_GUIDE.md](docs/status/TESTING_GUIDE.md)
- **Deployment**: [docs/status/DEPLOYMENT_GUIDE.md](docs/status/DEPLOYMENT_GUIDE.md)
- **Architecture**: [docs/architecture/overview.md](docs/architecture/overview.md)

**Recent Git Commits:**
- `5a61121` - [DOCS]: Radical simplification to 4 core categories
- `0c63060` - [DOCS]: Update CLAUDE.md to reflect documentation cleanup
- `685b70d` - [DOCS]: Clean up and reorganize documentation structure

---

## Quick Reference

### Project Overview
- **Type**: Full-stack monorepo with admin UI, REST backend, and mobile app
- **Technologies**: Spring Boot (Java 17), Vue 3 (TypeScript), Kotlin (Android), Firebase
- **Purpose**: Ad-free, admin-curated Islamic content platform with approval workflows
- **Status**: ~60% complete - Backend & frontend working, Android ready for testing

### Critical Policy
- **Test Timeout**: All tests must complete in 300 seconds (5 minutes)
  - Backend: Integration tests excluded by default (require Firebase emulator)
  - Run `./gradlew test -Pintegration=true` to include integration tests
  - Enforce 30-second timeout per test method to prevent hanging
- **Documentation Files**: **DO NOT** create documentation files (.md, .txt, .rst, etc.) unless:
  - User explicitly requests it, OR
  - Strongly suggested and user gives approval first
  - Exception: Updating existing documentation files is allowed when needed
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

# Run tests (JUnit 5) - excludes integration tests by default
./gradlew test

# Run ALL tests including integration tests (requires Firebase emulator)
./gradlew test -Pintegration=true

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

### Local Service Startup

```bash
# Start backend (Spring Boot API)
cd backend
./gradlew bootRun

# In a separate terminal, start the frontend dev server
cd frontend
npm run dev

# Optional: launch Firebase emulators (requires Firebase CLI)
firebase emulators:start --only firestore,auth --import=firebase-data
```

**Service Ports**:
- Backend: `http://localhost:8080`
- Frontend: `http://localhost:5173`
- Firebase Emulator UI: `http://localhost:4000`
- Firestore Emulator: `localhost:8082`
- Auth Emulator: `localhost:9099`

### Environment Setup

**Files**:
- `.env.example`: Template for environment variables
- `.env`: Local configuration (not in git)

**Required for Local Dev**:
```bash
# Copy template and configure Firebase
cp .env.example .env

# Then edit .env with:
FIREBASE_PROJECT_ID=albunyaan-tube  # Usually local/testing project
# Note: No YouTube API key required - uses NewPipeExtractor
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
├── docs/                       # Documentation (4 core categories)
│   ├── README.md              # Documentation index & navigation
│   │
│   ├── design/                # UX, i18n, design system
│   │   ├── design-system.md   # UI specs, tokens, components
│   │   ├── i18n-strategy.md   # Internationalization (en/ar/nl)
│   │   ├── design-tokens.json # CSS tokens
│   │   └── mockups/           # Screenshots & Figma
│   │
│   ├── architecture/          # Technical architecture
│   │   ├── overview.md        # System architecture & vision
│   │   ├── api-specification.yaml # OpenAPI REST API spec
│   │   ├── security.md        # Threat model & security
│   │   └── diagrams/          # C4 diagrams
│   │
│   ├── plan/                  # Planning & requirements
│   │   ├── roadmap.md         # Phased delivery plan
│   │   ├── acceptance-criteria.md # Acceptance criteria
│   │   ├── risk-register.md   # Risk register
│   │   └── backlog/           # Product backlog CSVs
│   │
│   ├── status/                # Current status & guides
│   │   ├── PROJECT_STATUS.md  # Current completion status
│   │   ├── DEVELOPMENT_GUIDE.md # Setup & troubleshooting
│   │   ├── ANDROID_GUIDE.md   # Android config, testing, player
│   │   ├── TESTING_GUIDE.md   # Testing strategy & verification
│   │   └── DEPLOYMENT_GUIDE.md # VPS deployment & monitoring
│   │
│   └── archived/              # Historical documents
│       ├── sessions/          # Development session notes
│       ├── planning/          # Historical planning docs
│       ├── android-player/    # Player development work logs
│       ├── performance-profiling/ # Historical performance notes
│       ├── features/          # Feature implementation docs
│       └── system-prompts/    # AI agent system prompts
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
- `YouTubeService`: NewPipeExtractor integration for YouTube content discovery (channel/playlist/video search with caching, no API key required)
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

**Dependency Injection** (Hilt - MANDATORY):
- All dependencies MUST be provided via Hilt modules
- **DO NOT** use manual service locators or static factories
- Previous `ServiceLocator` pattern has been removed and replaced with Hilt
- Module organization:
  - `NetworkModule`: Retrofit, OkHttpClient, API interfaces
  - `DownloadModule`: Download repository, storage
  - `AppModule`: Application-scoped dependencies
- ViewModels use `@HiltViewModel` with `@Inject constructor()`
- Fragments annotated with `@AndroidEntryPoint`
- Test isolation via `@TestInstallIn` modules (see Testing section)

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
- **Hilt Test Isolation**:
  - Use `@HiltAndroidTest` annotation on test classes
  - `TestNetworkModule` (`@TestInstallIn`) replaces production `NetworkModule`
  - Provides `FakeContentApi` and `FakeDownloadApi` returning empty/stub responses
  - Override with `@BindValue` for custom test behavior
  - Example:
    ```kotlin
    @HiltAndroidTest
    @UninstallModules(SomeModule::class)
    class MyFragmentTest {
        @get:Rule var hiltRule = HiltAndroidRule(this)

        @BindValue @JvmField
        val customDep: MyDependency = FakeMyDependency()
    }
    ```

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
- [ ] Backend: `cd backend && ./gradlew bootRun`
- [ ] Frontend: `cd frontend && npm run dev`
- [ ] Android: Open `android/` in Android Studio
- [ ] Test: Run suite for your platform (see Part 1 commands)

---

## Part 5: Important Files and Where to Look

### Configuration Files

| File | Purpose | Key Contents |
|------|---------|--------------|
| `backend/build.gradle.kts` | Backend dependencies & build | Spring Boot, Firebase, NewPipeExtractor, Gradle plugins |
| `backend/src/main/resources/application.yml` | Spring configuration | Cache, security, Firebase paths, CORS origins |
| `frontend/package.json` | Frontend dependencies | Vue, Vite, Pinia, Firebase, testing libs |
| `frontend/vite.config.ts` | Vite bundler config | Code splitting, minification, dev server |
| `frontend/tsconfig.json` | TypeScript config | @ alias for src/, strict mode |
| `android/build.gradle.kts` | Android app build | SDK versions, dependencies, build flavors, NewPipeExtractor |
| `.env.example` | Environment template | FIREBASE_PROJECT_ID (no API keys needed) |
| `.github/workflows/` | CI/CD pipelines | Build, test, lint steps for each platform |

### Documentation Files (Simplified Structure)

**Core Documentation**:
| File | Purpose |
|------|---------|
| `README.md` | Project overview |
| `AGENTS.md` | Policy for AI agents (test timeout!) |
| `docs/README.md` | Documentation index (start here!) |

**Design** (`docs/design/`):
| File | Purpose |
|------|---------|
| `design-system.md` | UI specifications, design tokens, components |
| `i18n-strategy.md` | Internationalization (en/ar/nl), RTL |
| `design-tokens.json` | CSS tokens, colors, typography |
| `mockups/` | Screenshots & Figma exports |

**Architecture** (`docs/architecture/`):
| File | Purpose |
|------|---------|
| `overview.md` | System architecture, tech stack, vision |
| `api-specification.yaml` | OpenAPI REST API specification |
| `security.md` | Threat model & security considerations |
| `diagrams/` | C4 diagrams (context, container, component) |

**Plan** (`docs/plan/`):
| File | Purpose |
|------|---------|
| `roadmap.md` | Phased delivery plan (Phases 0-12) |
| `acceptance-criteria.md` | Acceptance criteria with traceability |
| `risk-register.md` | Project risks with likelihood/impact |
| `backlog/` | Product backlog CSVs (stories, estimates) |

**Status** (`docs/status/`):
| File | Purpose |
|------|---------|
| `PROJECT_STATUS.md` | Current completion %, blockers, next steps |
| `DEVELOPMENT_GUIDE.md` | Setup instructions, troubleshooting |
| `ANDROID_GUIDE.md` | Android: config, testing, player, troubleshooting |
| `TESTING_GUIDE.md` | Testing strategy, data verification, performance |
| `DEPLOYMENT_GUIDE.md` | VPS deployment, HTTPS setup, monitoring |

**Archived** (`docs/archived/`):
| Folder | Contents |
|--------|----------|
| `sessions/` | Development session notes |
| `planning/` | Historical planning documents |
| `android-player/` | Player development work logs |
| `performance-profiling/` | Historical performance notes |
| `features/` | Feature implementation docs |
| `system-prompts/` | AI agent system prompts |

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
   - Bulk import/export: ✅ COMPLETE (both simple and full formats implemented)

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

**📌 Start Here**:
- **Documentation Index**: `docs/README.md` - Navigation hub for all docs

**✅ Status & Guides** (`docs/status/`):
- **Project Status**: `PROJECT_STATUS.md` - Current completion & blockers
- **Development Guide**: `DEVELOPMENT_GUIDE.md` - Setup & troubleshooting
- **Android Guide**: `ANDROID_GUIDE.md` - Config, testing, player, troubleshooting
- **Testing Guide**: `TESTING_GUIDE.md` - Testing strategy & verification
- **Deployment Guide**: `DEPLOYMENT_GUIDE.md` - VPS deployment & monitoring

**🏗️ Architecture** (`docs/architecture/`):
- **Overview**: `overview.md` - System architecture, tech stack, vision
- **API Specification**: `api-specification.yaml` - OpenAPI REST API spec
- **Security**: `security.md` - Threat model & security
- **Diagrams**: `diagrams/` - C4 diagrams

**🎨 Design** (`docs/design/`):
- **Design System**: `design-system.md` - UI specs, tokens, components
- **i18n Strategy**: `i18n-strategy.md` - Internationalization (en/ar/nl)

**📋 Plan** (`docs/plan/`):
- **Roadmap**: `roadmap.md` - Phased delivery plan
- **Acceptance Criteria**: `acceptance-criteria.md` - Requirements traceability
- **Risk Register**: `risk-register.md` - Project risks

**📦 Archived**: Historical documents in `docs/archived/` (sessions, planning, features)

**Note**: Documentation was radically simplified on Nov 7, 2025 into 4 core categories. See `docs/README.md` for complete navigation.

### External Links
- [Spring Boot Docs](https://spring.io/projects/spring-boot)
- [Vue 3 Docs](https://vuejs.org/)
- [Pinia State Management](https://pinia.vuejs.org/)
- [Firebase Admin SDK](https://firebase.google.com/docs/admin/setup)
- [Firestore Documentation](https://firebase.google.com/docs/firestore)
- [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) - YouTube content extraction library
- [Android Jetpack](https://developer.android.com/jetpack)
- [Kotlin Coroutines](https://kotlinlang.org/docs/coroutines-overview.html)

### Tools
- **git**: Version control ([Git Docs](https://git-scm.com/doc))
- **Gradle**: Build automation (backend & Android)
- **npm**: Package manager (frontend)
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
3. Start backend (`./gradlew bootRun`) and frontend (`npm run dev`)
4. Run one platform's tests to verify setup
5. Pick a feature from PROJECT_STATUS.md and contribute

---

**Last Updated**: November 7, 2025
**For Questions**: See [docs/README.md](docs/README.md) for navigation or review existing code patterns
