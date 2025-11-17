# Development Guide
**Last Updated**: 2025-11-16
**Status**: Active - Verified dependency versions and build procedures (P0-T1 completed, Netty updated to 4.1.118.Final in additional fixes)

---

## Prerequisites

### Required Software

| Tool | Version | Notes |
|------|---------|-------|
| **JDK** | 17.0.16+ | Backend + Android (configured via toolchain) |
| **Gradle** | 8.5 (backend), 8.14 (Android) | Managed by wrapper scripts |
| **Kotlin** | 1.9.22 | Android plugin version |
| **Node.js** | 18+ LTS (tested with 22.20.0) | Frontend (Vue 3 + Vite) |
| **npm** | 9+ (tested with 10.9.3) | Package manager (comes with Node.js) |
| **Git** | 2.40+ | Version control |
| **Firebase CLI** | Latest | Optional for emulator testing |

### Dependency Versions (Verified 2025-11-16 via P0-T1)

**Backend** (`backend/build.gradle.kts`):
```groovy
Gradle: 8.5
Java: 17.0.16 (Ubuntu 17.0.16+8-Ubuntu-0ubuntu124.04.1)
Spring Boot: 3.2.5
Firebase Admin SDK: 9.2.0
NewPipeExtractor: v0.24.8
OkHttp: 4.12.0
Netty: 4.1.118.Final (enforced via dependency constraints in build.gradle.kts, updated 2025-11-16 for CVE fixes)
Kotlin (Gradle wrapper): 1.9.20
Caffeine Cache: Latest (managed by Spring Boot BOM)
```

**Android** (`android/app/build.gradle.kts`):
```groovy
Gradle: 8.14
Java: 17.0.16 (Ubuntu 17.0.16+8-Ubuntu-0ubuntu124.04.1)
Android Gradle Plugin: 8.2.2
Compile SDK: 34
Min SDK: 26
Target SDK: 34
Kotlin: 1.9.22 (plugin version)
NewPipeExtractor: v0.24.8 (same as backend)
OkHttp: 4.12.0 (same as backend)
ExoPlayer: 2.19.1
Retrofit: 2.9.0
Kotlinx Coroutines: 1.7.3
```

**Frontend** (`frontend/package.json`):
```json
Node.js: 22.20.0 (tested, 18+ LTS required)
npm: 10.9.3
Vue: 3.4.21
TypeScript: 5.4.2
Vite: 5.2.0
Pinia: 2.1.7
Firebase: 12.3.0
Axios: 1.6.8
Vue Router: 4.3.2
Vitest: 1.4.0
```

---

## OpenAPI Code Generation (P1-T2)

**As of P1-T2**, all client DTOs (TypeScript + Kotlin) are auto-generated from the OpenAPI specification.

### Generating Client DTOs

**Generate all platforms**:
```bash
./scripts/generate-openapi-dtos.sh
```

**Frontend TypeScript only**:
```bash
cd frontend
npm run generate:api
# Output: frontend/src/generated/api/schema.ts
# Note: npm dev/test/build scripts auto-run this generation step
```

**Android Kotlin only**:
```bash
cd backend
./gradlew generateKotlinDtos
# Output: android/app/src/main/java/com/albunyaan/tube/data/model/api/models/
```

### Important Notes

- **DO NOT** manually edit generated files
- Regenerate DTOs after updating `docs/architecture/api-specification.yaml`
- CI automatically regenerates DTOs before each build
- Migration guide: `frontend/src/types/API_MIGRATION_GUIDE.md`

### Tools Used

- **TypeScript**: `openapi-typescript` (types-only generator)
- **Kotlin**: `openapi-generator-cli` with Moshi serialization
- **Source**: `docs/architecture/api-specification.yaml`

### DTO Architecture Pattern

**Rule**: OpenAPI DTOs are **transport types** only. UI/domain models stay separate and are fed via mappers.

**Frontend** (TypeScript):
- **Generated DTOs**: Import from `@/types/api` (re-exports `frontend/src/generated/api/schema.ts`)
- **Domain Models**: Keep in `frontend/src/types/*.ts` for UI-specific shapes (e.g., enriched view models with computed fields)
- **Mapping**: Create explicit mapper functions when DTO shape differs from UI needs
- **Example**:
  ```typescript
  // Use generated DTO for API calls
  import type { ContentItem } from '@/types/api'

  // Map to view model if needed
  interface ContentListItem {
    ...ContentItem,
    displayTitle: string  // computed field
  }

  function mapToViewModel(dto: ContentItem): ContentListItem {
    return { ...dto, displayTitle: dto.title || 'Untitled' }
  }
  ```

**Android** (Kotlin):
- **Generated DTOs**: Under `com.albunyaan.tube.data.model.api.models/` (Moshi-annotated)
- **Domain Models**: Keep in `com.albunyaan.tube.data.model/` (UI-layer classes like `ContentItem`, `Category`)
- **Mapping**: Create extension functions in `data/model/mappers/ApiMappers.kt`
- **Example**:
  ```kotlin
  // Use generated DTO for Retrofit
  @GET("/api/v1/content")
  suspend fun getContent(): CursorPageDto<ContentItemDto>

  // Map to domain model
  fun ContentItemDto.toDomain(): ContentItem = when (this) {
    is ContentItemDto.VideoItem -> ContentItem.Video(...)
    is ContentItemDto.ChannelItem -> ContentItem.Channel(...)
    is ContentItemDto.PlaylistItem -> ContentItem.Playlist(...)
  }
  ```

**Backend** (Java):
- DTOs in `com.albunyaan.tube.dto/` are **manually maintained** and must match OpenAPI spec
- When adding/changing endpoints:
  1. Update controller method signature
  2. Update matching DTO in `backend/src/main/java/com/albunyaan/tube/dto/`
  3. Update `docs/architecture/api-specification.yaml` with matching schema
  4. Regenerate client DTOs: `./scripts/generate-openapi-dtos.sh`

---

## Quick Start

### 1. Clone Repository

```bash
git clone https://github.com/YOUR_USERNAME/albunyaantube.git
cd albunyaantube
```

### 2. Backend Setup

```bash
cd backend

# Verify JDK 17 is active
java -version  # Should show "17.x.x"

# Run tests (excludes integration tests by default)
./gradlew test

# Start backend server (http://localhost:8080)
./gradlew bootRun
```

**Environment Variables**:
```bash
# backend/src/main/resources/application.yml handles Firebase config
# Copy template and configure:
cp backend/src/main/resources/firebase-service-account.json.template \
   backend/src/main/resources/firebase-service-account.json

# Edit firebase-service-account.json with your Firebase project credentials
```

### 3. Frontend Setup

```bash
cd frontend

# Install dependencies
npm ci  # or npm install

# Start dev server (http://localhost:5173)
npm run dev

# Run tests
npm test

# Build for production
npm run build
```

**Environment Variables**:
```bash
# frontend/.env.local (create this file)
VITE_API_BASE_URL=http://localhost:8080/api/v1
VITE_FIREBASE_API_KEY=YOUR_API_KEY
VITE_FIREBASE_AUTH_DOMAIN=your-project.firebaseapp.com
VITE_FIREBASE_PROJECT_ID=your-project
```

### 4. Android Setup

```bash
cd android

# Configure API base URL (create local.properties)
echo "api.base.url=http://10.0.2.2:8080/" > local.properties

# Build debug APK
./gradlew assembleDebug

# Run unit tests
./gradlew test

# APK output:
# android/app/build/outputs/apk/debug/app-debug.apk
```

**Notes**:
- `10.0.2.2` is the emulator's gateway to host `localhost`
- For physical devices: Use your machine's local IP (e.g., `http://192.168.1.167:8080/`)

---

## Development Workflow

### Running the Full Stack Locally

**Terminal 1 - Backend**:
```bash
cd backend
./gradlew bootRun
# Backend will start on http://localhost:8080
```

**Terminal 2 - Frontend**:
```bash
cd frontend
npm run dev
# Frontend will start on http://localhost:5173
```

**Terminal 3 - Firebase Emulator** (Optional):
```bash
# Start Firestore + Auth emulators
firebase emulators:start --only firestore,auth --import=firebase-data

# Emulator UI: http://localhost:4000
# Firestore: localhost:8082
# Auth: localhost:9099
```

**Android Studio**:
- Open `android/` folder
- Configure `local.properties` with backend URL
- Run on emulator or physical device

---

## Build Commands

### Backend

```bash
cd backend

# Clean build (removes old artifacts)
./gradlew clean build

# Run tests only (excludes integration tests)
./gradlew test

# Run ALL tests including integration tests (requires Firebase emulator)
./gradlew test -Pintegration=true

# Build JAR for deployment
./gradlew bootJar
# Output: backend/build/libs/albunyaan-tube-backend-0.0.1-SNAPSHOT.jar

# Check dependencies
./gradlew dependencies --configuration runtimeClasspath
```

### Frontend

```bash
cd frontend

# Development server with HMR
npm run dev

# Run unit tests (Vitest)
npm test

# Run E2E tests (Playwright)
npm run test:e2e

# Type checking
npm run type-check

# Build for production
npm run build
# Output: frontend/dist/

# Preview production build
npm run preview
```

### Android

```bash
cd android

# Debug build
./gradlew assembleDebug

# Release build (requires keystore)
./gradlew assembleRelease

# Run unit tests
./gradlew test

# Run instrumentation tests (requires device/emulator)
./gradlew connectedAndroidTest

# Lint checks
./gradlew lint

# Clean build
./gradlew clean assembleDebug
```

---

## Testing

### Backend Tests (JUnit 5)

**Default**: Excludes integration tests (no Firebase emulator required)
```bash
cd backend
./gradlew test
# 144 tests should pass in ~7s
```

**With Integration Tests**: Requires Firebase emulator
```bash
# Terminal 1: Start emulator
firebase emulators:start --only firestore,auth

# Terminal 2: Run tests with integration tag
cd backend
./gradlew test -Pintegration=true
```

**Test Timeout Policy** (AGENTS.md):
- Global test timeout: 300 seconds (5 minutes)
- Per-test method timeout: 30 seconds
- Enforced via `build.gradle.kts` systemProperty

### Frontend Tests

```bash
cd frontend

# Unit tests (Vitest)
npm test
# Enforced timeout: 300 seconds (hardcoded in package.json)

# E2E tests (Playwright)
npm run test:e2e

# Coverage report
npm run test:coverage
```

### Android Tests

```bash
cd android

# Unit tests (Robolectric)
./gradlew test

# Instrumentation tests (requires device)
./gradlew connectedAndroidTest
```

---

## Troubleshooting

### Backend Issues

#### Error: "java.lang.UnsupportedClassVersionError"
**Cause**: Wrong JDK version (need JDK 17)
**Fix**:
```bash
# Check current version
java -version

# Install JDK 17 (Debian/Ubuntu)
sudo apt install openjdk-17-jdk

# Set JAVA_HOME
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
```

#### Error: "NoSuchMethodError: io.netty.*"
**Cause**: Netty version conflict
**Status**: ✅ RESOLVED (Netty pinned to 4.1.118.Final via dependency constraints in `backend/build.gradle.kts`)
**Verification**:
```bash
./gradlew dependencies --configuration runtimeClasspath | grep netty-common
# Should show single version: 4.1.118.Final
```

#### Error: "Firebase credentials not found"
**Fix**:
```bash
# Set environment variable
export GOOGLE_APPLICATION_CREDENTIALS="backend/src/main/resources/firebase-service-account.json"

# Or update application.yml:
# firebase.credentials.path: classpath:firebase-service-account.json
```

#### Error: "Port 8080 already in use"
**Fix**:
```bash
# Find and kill process
lsof -ti:8080 | xargs kill -9

# Or change port in application.yml:
server:
  port: 8081
```

#### Warning: "Encountered duplicate path BOOT-INF/lib/NewPipeExtractor-v0.24.8.jar"
**Status**: Cosmetic warning (does not affect runtime)
**Cause**: JitPack multi-module artifact structure
**Action**: No fix required (DuplicatesStrategy.WARN)

---

### Frontend Issues

#### Error: "Cannot connect to backend API"
**Fix**:
```bash
# 1. Verify backend is running
curl http://localhost:8080/api/actuator/health

# 2. Check VITE_API_BASE_URL in frontend/.env.local
echo "VITE_API_BASE_URL=http://localhost:8080/api/v1" >> frontend/.env.local

# 3. Restart dev server
npm run dev
```

#### Error: "CORS policy: No 'Access-Control-Allow-Origin' header"
**Fix**: Update `backend/src/main/resources/application.yml`:
```yaml
cors:
  allowed-origins:
    - "http://localhost:5173"
    - "http://localhost:3000"
```

#### Error: "npm ERR! code ERESOLVE"
**Fix**:
```bash
# Force clean install
rm -rf node_modules package-lock.json
npm install --legacy-peer-deps
```

---

### Android Issues

#### Error: "Unable to connect to backend"
**Fix** (Emulator):
```bash
# Use 10.0.2.2 instead of localhost
echo "api.base.url=http://10.0.2.2:8080/" > android/local.properties
```

**Fix** (Physical Device):
```bash
# Get your machine's local IP
ip addr show | grep "inet 192"

# Update local.properties
echo "api.base.url=http://192.168.1.XXX:8080/" > android/local.properties

# Ensure backend allows CORS from device IP
```

#### Error: "Kotlin version conflict"
**Status**: ✅ RESOLVED (auto-resolved to 1.9.22)
**Verification**:
```bash
cd android
./gradlew dependencies --configuration releaseRuntimeClasspath | grep kotlin-stdlib
# Should show: 1.9.22
```

#### Error: "NewPipeExtractor: No extractor found"
**Cause**: YouTube URL parsing issue
**Status**: Known limitation of NewPipeExtractor v0.24.8
**Workaround**: Test with supported URL formats (channel, playlist, video)

#### Warning: "Gradle 9.0 deprecation warnings"
**Status**: Cosmetic (build succeeds)
**Action**: Address in Phase 0 Task 3 (CI pipeline cleanup)

---

## Environment Configuration

### Backend (`application.yml`)

```yaml
server:
  port: 8080

spring:
  cache:
    type: caffeine  # Use 'redis' for production

firebase:
  credentials:
    path: classpath:firebase-service-account.json

cors:
  allowed-origins:
    - "http://localhost:5173"  # Frontend dev
    - "http://localhost:3000"
    - "http://192.168.1.0/24"  # Local network (Android physical devices)

logging:
  level:
    com.albunyaan: INFO
```

### Frontend (`.env.local`)

```bash
VITE_API_BASE_URL=http://localhost:8080/api/v1
VITE_FIREBASE_API_KEY=YOUR_API_KEY
VITE_FIREBASE_AUTH_DOMAIN=your-project.firebaseapp.com
VITE_FIREBASE_PROJECT_ID=your-project
VITE_FIREBASE_STORAGE_BUCKET=your-project.appspot.com
VITE_FIREBASE_MESSAGING_SENDER_ID=123456789
VITE_FIREBASE_APP_ID=1:123456789:web:abc123
```

### Android (`local.properties`)

```properties
# API base URL for backend
api.base.url=http://10.0.2.2:8080/

# For physical device (use your machine's IP)
# api.base.url=http://192.168.1.167:8080/

# SDK paths (auto-generated by Android Studio)
sdk.dir=/home/user/Android/Sdk
```

### Android Release Signing (`keystore.properties`)

```properties
storeFile=../release.keystore
storePassword=YOUR_KEYSTORE_PASSWORD
keyAlias=albunyaan-tube
keyPassword=YOUR_KEY_PASSWORD
```

---

## IDE Setup

### IntelliJ IDEA / Android Studio

**Backend**:
1. Open `backend/` as Gradle project
2. SDK: JDK 17
3. Enable Gradle auto-import
4. Run configuration: `bootRun` task

**Frontend**:
1. Open `frontend/` folder
2. Enable TypeScript service
3. Configure Vite dev server run configuration

**Android**:
1. Open `android/` folder
2. Sync Gradle
3. Configure `local.properties` with API URL
4. Select `app` run configuration

---

## Common Development Tasks

### Seed Database with Test Data

```bash
cd backend
./gradlew bootRun --args='--spring.profiles.active=seed'

# Seeds:
# - 19 categories
# - 25 channels
# - 19 playlists
# - 76 videos
```

### Update OpenAPI Specification

```bash
# Generate from controllers (not yet implemented)
# Will be part of Phase 1 Task 1 (P1-T1)

# For now, manually update:
# docs/architecture/api-specification.yaml
```

### Run Performance Tests (Gatling)

```bash
cd backend
./gradlew gatlingRun
```

### Check Code Quality

**Backend**:
```bash
./gradlew check  # Includes tests + static analysis
```

**Frontend**:
```bash
npm run lint     # ESLint
npm run type-check  # TypeScript
```

**Android**:
```bash
./gradlew lint   # Android Lint
```

---

## Git Workflow

### Commit Message Format

```bash
[PREFIX]: Brief description (under 50 chars)

Optional detailed explanation (wrapped at 72 chars)

🤖 Generated with [Claude Code](https://claude.com/claude-code)

Co-Authored-By: Claude <noreply@anthropic.com>
```

**Prefixes**: `[FEAT]`, `[FIX]`, `[REFACTOR]`, `[PERF]`, `[DOCS]`, `[TEST]`, `[CHORE]`

### Branch Strategy

- `main`: Production-ready code
- `feature/{description}`: New features
- `fix/{description}`: Bug fixes

---

## CI/CD

### GitHub Actions Workflows

**Backend CI** (`.github/workflows/backend-ci.yml`):
- Runs on: Push to `main`, PRs
- Steps: Build, test (excludes integration tests)
- Timeout: 600 seconds (timeout-minutes: 10)

**Frontend CI** (`.github/workflows/frontend-ci.yml`):
- Runs on: Push to `main`, PRs
- Steps: Install, lint, type-check, test, build
- Timeout: 600 seconds (timeout-minutes: 10)

**Android CI** (`.github/workflows/android-ci.yml`):
- Runs on: Push to `main`, PRs
- Steps: Build debug APK, run unit tests
- Timeout: 1200 seconds (timeout-minutes: 20)

---

## Performance Budgets

| Metric | Target | Enforcement |
|--------|--------|-------------|
| **Backend tests** | 300s total | Gradle timeout |
| **Frontend tests** | 300s total | npm script timeout |
| **Backend startup** | <10s | Manual monitoring |
| **Frontend bundle** | <500KB gzip | Vite config |
| **API response** | <200ms p95 | Gatling tests |

---

## Next Steps

- [ ] Set up local Firebase project
- [ ] Configure environment variables
- [ ] Run backend tests (`./gradlew test`)
- [ ] Run frontend dev server (`npm run dev`)
- [ ] Build Android APK (`./gradlew assembleDebug`)
- [ ] Review [PROJECT_STATUS.md](PROJECT_STATUS.md) for current blockers
- [ ] Review [../PRD.md](../PRD.md) for product requirements

---

## Additional Resources

- **Architecture**: [../architecture/overview.md](../architecture/overview.md)
- **API Spec**: [../architecture/api-specification.yaml](../architecture/api-specification.yaml)
- **Design System**: [../design/design-system.md](../design/design-system.md)
- **Android Guide**: [ANDROID_GUIDE.md](ANDROID_GUIDE.md)
- **PRD**: [../PRD.md](../PRD.md)

---

**Last Verified**: 2025-11-15 (Dependency versions confirmed via P0-T1 audit)
**Maintainer**: See [../../CLAUDE.md](../../CLAUDE.md)
