# FitrahTube - Claude Developer Guide

Ad-free, admin-curated halal YouTube client with native Android app and web moderation dashboard.

> **Branding Note**: The user-facing app name is **FitrahTube**. Internal code identifiers (package `com.albunyaan.tube`, project directory `albunyaantube/`, deep link scheme `albunyaantube://`, class names like `AlbunyaanTubeApplication`) retain the original "albunyaan" naming for backward compatibility. Do not rename these without a coordinated migration.

**Full Requirements**: See [docs/PRD.md](docs/PRD.md) for product requirements, user stories, and acceptance criteria.

## Library Documentation
@docs/library-guides/newpipe-extractor.md

---

## 🛠️ **DEVELOPMENT WORKFLOW (MANDATORY)**

1. **Before you begin working, check in with me** and I will verify the plan
2. **Then, begin working on the todo items**, marking them as complete as you go
3. **Please, at every step, provide a high-level explanation of the changes you made.**
4. **Make all fixes and code changes as simple as possible and limit them to the minimum necessary code.**
5. **DO NOT BE LAZY. NEVER BE LAZY.** Find root causes and fix them. No temporary fixes.

---

## Quick Reference

| Aspect | Details |
|--------|---------|
| **Type** | Full-stack monorepo (backend, frontend, Android) |
| **Tech** | Spring Boot (Java 17), Vue 3 (TypeScript), Kotlin, Firebase |
| **Status** | ~60% complete |
| **i18n** | English, Arabic (RTL), Dutch |

### Critical Policies
- **Test Timeout**: 300 seconds max; 30s per test method
- **Documentation**: DO NOT create new .md files unless explicitly requested
- **Hilt DI (Android)**: MANDATORY - no manual service locators
- **UI Preservation**: NEVER remove or break existing working UI components. Before modifying any UI code, verify the change doesn't regress other screens or device variants. Always test/review changes across ALL device layouts (phone, tablet, TV).
- **Edge-to-Edge (Android 15+)**: App targets SDK 35. All `fragment_main_shell.xml` variants use `fitsSystemWindows="true"` to handle system bar insets. Do NOT remove this attribute.
- **Pagination on Large Screens**: Grid layouts on tablet/TV can fit an entire page of items without scrolling. All list fragments must auto-trigger `loadMore()` when items fit on screen (the scroll listener alone is insufficient).
- **Navigation Icons (Android)**: NEVER use notification-style drawables (`ic_stat_*`) or system drawables (`@android:drawable/*`) for navigation bar icons. These have vector-level `android:tint` attributes or manufacturer-specific implementations that conflict with `BottomNavigationView`/`NavigationRailView` tinting on certain devices (especially Samsung Android 15+). Always use project-local vector drawables from `res/drawable/` with no vector-level tint and hardcoded `fillColor` that responds to external `itemIconTint`.

---

## Multi-Device Design Workflow (MANDATORY)

### Core Principles
1. **Device-Agnostic First**: Consider ALL form factors from the start
2. **No Partial Implementations**: Feature incomplete until works on ALL devices
3. **Consistent Visual Language**: Same design tokens across all layouts

### Android Layout Qualifiers
| Qualifier | Device | Width |
|-----------|--------|-------|
| `layout/` | Phone | < 600dp |
| `layout-sw600dp/` | Tablet | ≥ 600dp |
| `layout-sw720dp/` | Large tablet/TV | ≥ 720dp |

### Design Tokens
| Token | Purpose |
|-------|---------|
| `@dimen/spacing_xs` | 4dp - Tight margins |
| `@dimen/spacing_sm` | 8dp - Default padding |
| `@dimen/spacing_md` | 16dp - Section spacing |
| `@dimen/spacing_lg` | 24dp - Major sections |

### RTL Support
```xml
<!-- CORRECT -->
android:textAlignment="viewStart"
<!-- INCORRECT -->
android:gravity="left"
```

### Visual PR Checklist
- [ ] Tested on phone emulator
- [ ] Tested on tablet emulator (if layout-sw600dp exists)
- [ ] Verified RTL with Arabic locale
- [ ] Used design tokens (no hardcoded dp/sp)
- [ ] Same IDs across layout variants

---

## Build Commands

### Backend (Spring Boot)
```bash
cd backend
./gradlew bootRun              # Start on :8080
./gradlew test                 # Unit tests only
./gradlew test -Pintegration=true  # Include integration tests
./gradlew clean build
```

### Frontend (Vue 3 + Vite)
```bash
cd frontend
npm ci && npm run dev          # Start on :5173
npm test                       # Vitest (300s timeout)
npm run build
```

### Android (Kotlin)
```bash
cd android
./gradlew assembleDebug
./gradlew test
./gradlew connectedAndroidTest
```

### Service Ports
- Backend: `http://localhost:8080`
- Frontend: `http://localhost:5173`
- Firebase Emulator: `http://localhost:4000`

---

## Architecture

### Project Structure
```
albunyaantube/
├── backend/          # Spring Boot REST API
├── frontend/         # Vue 3 Admin Dashboard
├── android/          # Kotlin Native App
├── docs/
│   ├── PRD.md        # Product Requirements
│   ├── design/       # UI specs, i18n, tokens
│   ├── architecture/ # API spec, security, diagrams
│   ├── plan/         # Roadmap, acceptance criteria
│   └── status/       # Guides (dev, android, testing, deployment)
└── scripts/          # Setup utilities
```

### Backend Layers
```
Controller → Service → Repository → Model (Firestore)
```

**Key Controllers** (11 total, 67 endpoints):
- `PublicContentController`: `/api/v1` - Mobile app content
- `ApprovalController`: `/api/admin/approvals` - Workflow
- `YouTubeSearchController`: `/api/admin/youtube` - Search
- `RegistryController`: `/api/admin/registry` - Add to pending queue

**Key Services**:
- `YouTubeService`: NewPipeExtractor integration (no API key needed)
- `ApprovalService`: Approval workflow, auto-approval rules
- `PublicContentService`: Content for mobile app

### Frontend (Vue 3 + Pinia)
- **Stores**: `auth.ts`, `preferences.ts`, `registryFilters.ts`
- **Services**: `approvalService.ts`, `youtubeService.ts`, `categoryService.ts`
- **i18n**: `src/locales/messages.ts` (en, ar, nl)

### Android (MVVM + Hilt)
```
Fragment → ViewModel → Repository → RetrofitService → Backend
```

**Hilt Modules** (MANDATORY):
- `NetworkModule`: Retrofit, OkHttpClient
- `DownloadModule`: Download repository
- `AppModule`: Application-scoped deps

**Screens**: Home, Channels, Playlists, Videos, Player, Downloads, Settings, Search

---

## Key Patterns

### Approval Workflow
1. Admin searches YouTube via `YouTubeSearchController`
2. Click "Add for Approval" → `CategoryAssignmentModal`
3. Assign categories → Submit via `RegistryController` (status: PENDING)
4. Admin reviews in `PendingApprovalsView`
5. Approve/Reject → Content Library → Public API

### OpenAPI Code Generation
```bash
./scripts/generate-openapi-dtos.sh
```
- Source: `docs/architecture/api-specification.yaml`
- TypeScript: `frontend/src/generated/api/schema.ts`
- Kotlin: `android/app/src/main/java/com/albunyaan/tube/data/model/api/`

### Caching
- **Dev**: Caffeine (in-memory, 1-hour TTL)
- **Prod**: Redis
- **Caches**: `youtubeChannelSearch`, `youtubePlaylistSearch`, `youtubeVideoSearch`

### Content Seeding
```bash
./gradlew bootRun --args='--spring.profiles.active=seed'
```

---

## Git Conventions

### Commit Format
```
[PREFIX]: Description (under 50 chars)

Optional detailed explanation
```

**Prefixes**: `[FEAT]`, `[FIX]`, `[REFACTOR]`, `[PERF]`, `[DOCS]`, `[TEST]`, `[CHORE]`

### Branches
- `main`: Production-ready
- `feature/{issue-id}-{description}`
- `fix/{issue-id}-{description}`

---

## Testing

### Backend (JUnit 5)
- Unit tests mock Firestore repositories
- Integration tests require Firebase Emulator

### Frontend (Vitest)
- 300-second timeout enforced
- Component tests with `@testing-library/vue`

### Android (Hilt Test Isolation)
```kotlin
@HiltAndroidTest
@UninstallModules(SomeModule::class)
class MyFragmentTest {
    @get:Rule var hiltRule = HiltAndroidRule(this)
    @BindValue @JvmField val customDep: MyDependency = FakeMyDependency()
}
```

---

## Common Workarounds

**Firebase credentials not found**:
```bash
export GOOGLE_APPLICATION_CREDENTIALS="backend/src/main/resources/firebase-service-account.json"
```

**Port 8080 in use**:
```bash
lsof -ti:8080 | xargs kill -9
```

**Android emulator can't reach backend**:
```kotlin
// Use 10.0.2.2 (emulator's host gateway)
buildConfigField("String", "API_BASE_URL", "\"http://10.0.2.2:8080/\"")
```

---

## Documentation Links

| Category | File |
|----------|------|
| **Requirements** | [docs/PRD.md](docs/PRD.md) |
| **Project Status** | [docs/status/PROJECT_STATUS.md](docs/status/PROJECT_STATUS.md) |
| **Dev Setup** | [docs/status/DEVELOPMENT_GUIDE.md](docs/status/DEVELOPMENT_GUIDE.md) |
| **Android Guide** | [docs/status/ANDROID_GUIDE.md](docs/status/ANDROID_GUIDE.md) |
| **Testing** | [docs/status/TESTING_GUIDE.md](docs/status/TESTING_GUIDE.md) |
| **Deployment** | [docs/status/DEPLOYMENT_GUIDE.md](docs/status/DEPLOYMENT_GUIDE.md) |
| **Architecture** | [docs/architecture/overview.md](docs/architecture/overview.md) |
| **API Spec** | [docs/architecture/api-specification.yaml](docs/architecture/api-specification.yaml) |
| **Design System** | [docs/design/design-system.md](docs/design/design-system.md) |
| **i18n Strategy** | [docs/design/i18n-strategy.md](docs/design/i18n-strategy.md) |
