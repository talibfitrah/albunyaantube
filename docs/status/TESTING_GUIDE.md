# Testing Guide

**Last Updated**: November 16, 2025
**Status**: Active - Production testing strategy

This guide covers testing strategy, execution, and verification for the Albunyaan Tube platform across all components (Backend, Frontend, Android).

---

## Table of Contents

1. [Testing Philosophy](#testing-philosophy)
2. [Test Timeout Policy](#test-timeout-policy)
3. [Backend Testing](#backend-testing)
4. [Frontend Testing](#frontend-testing)
5. [Android Testing](#android-testing)
6. [Continuous Integration](#continuous-integration)
7. [Performance Metrics](#performance-metrics)
8. [Flakiness Policy](#flakiness-policy)
9. [Troubleshooting](#troubleshooting)

---

## Testing Philosophy

### Core Principles
1. **Fast feedback**: Unit tests should complete in < 10 seconds
2. **Isolation**: Tests must not depend on external services (use mocks)
3. **Determinism**: Tests must produce the same result every time
4. **Clarity**: Test names describe what they test and expected outcome
5. **Coverage**: Aim for 80%+ code coverage on critical paths

### Test Pyramid
```
       /\        E2E (Few) - Critical user journeys
      /  \
     /____\      Integration (Some) - API contracts, Firebase
    /      \
   /________\    Unit (Many) - Business logic, models, services
```

---

## Test Timeout Policy

**Global Policy** (from [AGENTS.md](../../AGENTS.md) and [CLAUDE.md](../../CLAUDE.md)):
- **Maximum test suite runtime**: 300 seconds (5 minutes)
- **Recommended per-test timeout**: 30 seconds
- **Integration tests**: Excluded by default, require explicit flag

### Rationale
- Prevents hanging tests from blocking CI/CD
- Ensures fast feedback loops for developers
- Detects infinite loops and deadlocks early

### Enforcement
- **Backend**: JUnit timeout annotations + CI timeout
- **Frontend**: Vitest config + npm script wrapper
- **Android**: Gradle test timeout + CI timeout

---

## Backend Testing

### Tech Stack
- **Framework**: JUnit 5
- **Mocking**: Mockito
- **Coverage**: JaCoCo
- **Build Tool**: Gradle 8.5

### Test Categories

#### 1. Unit Tests (Default)
**Purpose**: Test business logic in isolation

**Location**: `backend/src/test/java/`

**Execution**:
```bash
cd backend
./gradlew test
```

**Characteristics**:
- All external dependencies mocked (Firestore, Firebase Auth, NewPipe)
- No network calls
- Fast execution (< 10 seconds for 144 tests)
- Deterministic results

**Example Test Classes**:
- `ApprovalControllerTest` - Approval workflow endpoints
- `DownloadServiceTest` - Download policy and token logic
- `CategoryModelTest` - Category hierarchy validation

#### 2. Pagination Integration Tests (P2-T4)
**Purpose**: Test cursor-based pagination at scale across all content types

**Location**: `backend/src/test/java/com/albunyaan/tube/integration/PaginationIntegrationTest.java`

**Execution**:
```bash
# Start Firebase emulators (Terminal 1)
firebase emulators:start --only firestore

# Run pagination tests (Terminal 2)
cd backend
./gradlew test --tests '*PaginationIntegrationTest*' -Pintegration=true
```

**Test Scenarios**:
- **Public content pagination**: CHANNELS, PLAYLISTS, VIDEOS with various page sizes (3, 10, 20, 50)
- **Pending approvals**: Mixed type pagination with category filters
- **Edge cases**: Empty datasets, last page detection, small/large page sizes
- **Ordering validation**: Monotonic order for subscribers, itemCount, uploadedAt
- **Duplicate detection**: No duplicates across pages
- **Concurrent reads**: Multiple traversals don't corrupt each other

**Test Data Seeding**:
- Uses deterministic IDs and timestamps (e.g., `channel-001`, `channel-002`)
- Seeder methods in test class create 50-120 items per content type
- Categories created in `@BeforeEach` for filtering tests

**Expected Runtime**: ~60-90 seconds with Firestore emulator

**What it validates**:
1. Cursor encoding/decoding works correctly (opaque base64 tokens)
2. `hasNext` accurately reflects remaining items
3. Page boundaries are correct (no gaps, no duplicates)
4. Ordering is maintained across page fetches
5. Category filtering works with pagination

#### 3. Integration Tests (Optional)
**Purpose**: Test Firebase integration with emulator

**Execution**:
```bash
# Start Firebase emulators (Terminal 1)
firebase emulators:start --only firestore,auth --import=firebase-data

# Run integration tests (Terminal 2)
cd backend
./gradlew test -Pintegration=true
```

**Requirements**:
- Firebase CLI installed: `npm install -g firebase-tools`
- Emulator ports available: 8090 (Firestore), 9099 (Auth)
- Test data seeded in `firebase-data/` directory

**Characteristics**:
- Real Firestore operations (local emulator)
- Slower execution (30-60 seconds)
- Requires emulator startup

### Test Configuration

#### `application-test.yml`
```yaml
spring:
  cache:
    type: none  # Disable caching in tests

server:
  port: 0  # Random port prevents conflicts

app:
  firebase:
    firestore:
      emulator:
        enabled: true
        host: localhost
        port: 8090  # Firestore emulator port
```

**Key Settings**:
- Cache disabled to prevent cross-test contamination
- Random server port to avoid conflicts in parallel runs
- Firestore emulator configured for integration tests

### Running Tests

#### Quick Test (Unit Only)
```bash
cd backend
./gradlew test
```
**Expected**: ✅ 144 tests pass in ~8-10 seconds

#### Full Build with Tests
```bash
cd backend
./gradlew clean build
```
**Expected**: ✅ Compile + test + package in ~15 seconds

#### With Coverage Report
```bash
cd backend
./gradlew test jacocoTestReport
```
**Report**: `backend/build/reports/jacoco/test/html/index.html`

#### Integration Tests
```bash
# Terminal 1: Start emulators
firebase emulators:start --only firestore,auth

# Terminal 2: Run tests
cd backend
./gradlew test -Pintegration=true
```

### Timeout Enforcement

#### Per-Test Timeout (JUnit)
```java
@Test
@Timeout(30)  // 30 seconds max per test
void testMethod() {
    // test code
}
```

#### Suite Timeout (Gradle)
```kotlin
tasks.test {
    timeout.set(Duration.ofMinutes(5))  // 300 seconds
}
```

#### CI Timeout (GitHub Actions)
```bash
timeout 300 ./gradlew clean build
```

### Test Structure Example

```java
@ExtendWith(MockitoExtension.class)
class DownloadServiceTest {
    @Mock private VideoRepository videoRepository;
    @Mock private DownloadTokenService tokenService;
    @InjectMocks private DownloadService downloadService;

    @BeforeEach
    void setUp() {
        // Initialize test data
    }

    @Test
    void checkDownloadPolicy_shouldAllowDownload_whenVideoApproved() {
        // Given
        when(videoRepository.findByYoutubeId("video-1"))
            .thenReturn(Optional.of(approvedVideo));

        // When
        DownloadPolicyDto policy = downloadService.checkDownloadPolicy("video-1");

        // Then
        assertTrue(policy.isAllowed());
    }
}
```

### Current Test Suite Metrics
- **Total tests**: 144
- **Test classes**: 13
- **Pass rate**: 100% (verified over 3 runs)
- **Average runtime**: 8.67 seconds
- **Flaky tests**: 0
- **Coverage**: ~75% (estimated)

See [P0-T2-TEST-STABILITY-REPORT.md](P0-T2-TEST-STABILITY-REPORT.md) for detailed analysis.

---

## Frontend Testing

### Tech Stack
- **Framework**: Vitest
- **Component Testing**: Vue Test Utils + @testing-library/vue
- **Coverage**: Vitest built-in coverage (c8)
- **E2E**: Playwright (optional)

### Test Categories

#### 1. Unit Tests (Components, Services, Stores)
**Location**: `frontend/tests/`

**Execution**:
```bash
cd frontend
npm test
```

**Timeout Enforcement**:
```json
// package.json
{
  "scripts": {
    "test": "timeout 300s vitest run"  // 300s hard limit
  }
}
```

#### 2. E2E Tests (Optional)
**Framework**: Playwright

**Execution**:
```bash
cd frontend
npm run test:e2e
```

### Running Tests

#### Quick Test
```bash
cd frontend
npm test
```

#### With Coverage
```bash
cd frontend
npm test -- --coverage
```
**Report**: `frontend/coverage/index.html`

#### Watch Mode (Development)
```bash
cd frontend
npm test -- --watch
```

#### E2E Tests
```bash
cd frontend
npm run test:e2e
```

### Test Structure Example

```typescript
import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ChannelDetailModal from '@/components/exclusions/ChannelDetailModal.vue'

describe('ChannelDetailModal', () => {
  it('displays channel information correctly', () => {
    const wrapper = mount(ChannelDetailModal, {
      props: {
        channel: {
          id: 'channel-1',
          title: 'Test Channel',
          status: 'APPROVED'
        }
      }
    })

    expect(wrapper.text()).toContain('Test Channel')
    expect(wrapper.text()).toContain('APPROVED')
  })
})
```

### Vitest Configuration

```typescript
// vitest.config.ts
export default defineConfig({
  test: {
    globals: true,
    environment: 'jsdom',
    testTimeout: 30000,  // 30s per test
    hookTimeout: 10000,  // 10s for setup/teardown
    teardownTimeout: 5000,
    coverage: {
      provider: 'c8',
      reporter: ['text', 'html', 'json'],
      exclude: ['node_modules/', 'dist/', 'tests/']
    }
  }
})
```

### Current Test Suite Metrics
- **Total tests**: ~50+ (varies as features added)
- **Pass rate**: 100%
- **Average runtime**: < 60 seconds
- **Coverage target**: 80%+

---

## Android Testing

### Tech Stack
- **Framework**: JUnit 4
- **Mocking**: Mockito
- **Instrumentation**: AndroidX Test
- **Build Tool**: Gradle 8.14

### Test Categories

#### 1. Unit Tests
**Location**: `android/app/src/test/`

**Execution**:
```bash
cd android
./gradlew test
```

**Characteristics**:
- JVM-based (no emulator needed)
- Fast execution
- Mock Android framework classes

#### 2. Instrumentation Tests
**Location**: `android/app/src/androidTest/`

**Execution**:
```bash
cd android
./gradlew connectedAndroidTest
```

**Requirements**:
- Android device or emulator running
- ADB connection established

#### 3. Hilt Test Isolation

The Android app uses Hilt for dependency injection. Instrumentation tests are isolated from the real backend using `@TestInstallIn` modules.

**Test Modules**:
- `TestNetworkModule`: Replaces `NetworkModule` with fake API implementations
  - `FakeContentApi`: Returns empty lists for content/categories/search
  - `FakeDownloadApi`: Returns stub download policies/tokens/manifests

**How to Write Isolated Tests**:

```kotlin
@HiltAndroidTest
@UninstallModules(DownloadModule::class)  // Optional: if you need to replace specific modules
@RunWith(AndroidJUnit4::class)
class MyFragmentTest {

    @get:Rule
    var hiltRule = HiltAndroidRule(this)

    // Override specific dependencies with @BindValue
    @BindValue
    @JvmField
    val fakeRepository: MyRepository = FakeMyRepository()

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    @Test
    fun myTest() {
        // Test code using fake dependencies
    }
}
```

**Key Files**:
- `androidTest/java/com/albunyaan/tube/di/TestNetworkModule.kt`
- `androidTest/java/com/albunyaan/tube/HiltTestActivity.kt`
- `androidTest/java/com/albunyaan/tube/HiltTestRunner.kt`

**Note**: Tests automatically receive fake implementations without hitting the real backend. Use `@BindValue` to provide custom test doubles for specific test scenarios.

### Running Tests

#### Unit Tests
```bash
cd android
./gradlew test
```

#### Instrumentation Tests
```bash
# Start emulator first
emulator -avd Pixel_5_API_31 &

# Run tests
cd android
./gradlew connectedAndroidTest
```

#### Lint Checks
```bash
cd android
./gradlew lint
```
**Report**: `android/app/build/reports/lint-results-debug.html`

### Build APK
```bash
cd android
./gradlew assembleDebug
```
**Output**: `android/app/build/outputs/apk/debug/app-debug.apk`

---

## Continuous Integration

All CI workflows enforce the **300-second (5-minute) test timeout** policy defined in [AGENTS.md](../../AGENTS.md).

### CI Workflows

#### Backend CI (`.github/workflows/backend-ci.yml`)

**Current Implementation** (✅ As of P0-T3):
```yaml
jobs:
  build:
    runs-on: ubuntu-latest
    timeout-minutes: 10  # Job-level timeout
    steps:
      - name: Run backend build & tests (canonical with 300s timeout)
        run: timeout 300 ./gradlew clean build

      - name: Upload test reports
        if: failure()
        uses: actions/upload-artifact@v3
        with:
          name: backend-test-reports
          path: |
            backend/build/reports/tests/
            backend/build/test-results/
          retention-days: 7
```

**What it does**:
1. Checkout code
2. Setup Java 17 with Gradle cache
3. Run `timeout 300 ./gradlew clean build` (compile + test in ≤5 minutes)
4. Upload test reports on failure for debugging
5. Fail if tests fail or timeout

**Enforcement**:
- ✅ Explicit 300s timeout on test command
- ✅ Job-level 10-minute timeout as safety net
- ✅ Test artifacts uploaded on failure

#### Frontend CI (`.github/workflows/frontend-ci.yml`)

**Current Implementation** (✅ As of P0-T3):
```yaml
jobs:
  build:
    runs-on: ubuntu-latest
    timeout-minutes: 10  # Job-level timeout
    steps:
      - name: Run tests (with 300s timeout)
        run: timeout 300 npm test -- --coverage

      - name: Build
        run: npm run build

      - name: Upload coverage report
        if: always()
        uses: actions/upload-artifact@v3
        with:
          name: coverage-report
          path: frontend/coverage/
          retention-days: 7
```

**What it does**:
1. Checkout code
2. Setup Node.js 18 with npm cache
3. Install dependencies (`npm ci`)
4. Run linter, type check, tests (≤5 minutes)
5. Build production bundle
6. Upload coverage reports (always, even on success)

**Enforcement**:
- ✅ Explicit 300s timeout on test command
- ✅ Job-level 10-minute timeout as safety net
- ✅ Coverage artifacts uploaded always

#### Android CI (`.github/workflows/android-ci.yml`)

**Current Implementation** (✅ As of P0-T3):
```yaml
jobs:
  build:
    runs-on: ubuntu-latest
    timeout-minutes: 20  # Job-level timeout (larger due to Gradle overhead)
    steps:
      - name: Build debug APK (canonical)
        run: ./gradlew assembleDebug

      - name: Run unit tests (with 300s timeout)
        run: timeout 300 ./gradlew test

      - name: Run lint checks
        run: ./gradlew lint

      - name: Upload build reports
        if: failure()
        uses: actions/upload-artifact@v3
        with:
          name: build-reports
          path: |
            android/app/build/reports/
            android/app/build/test-results/
```

**What it does**:
1. Checkout code
2. Setup Java 17 with Gradle cache
3. Build debug APK (no timeout, usually fast)
4. Run unit tests with 300s timeout
5. Run lint checks (no timeout, usually fast)
6. Upload reports on failure

**Enforcement**:
- ✅ Explicit 300s timeout on test command only
- ✅ Job-level 20-minute timeout (accounts for Gradle downloads, builds)
- ✅ Build/test artifacts uploaded on failure

### Timeout Enforcement Strategy

**Two-Layer Defense** (Implemented in P0-T3):
1. **Command-level timeout** (`timeout 300 <command>`): Enforces 5-minute limit on test execution
2. **Job-level timeout** (`timeout-minutes`): Safety net for entire job (includes setup, cleanup)

**Why Both?**:
- Command timeout: Precise control over test execution time
- Job timeout: Prevents entire job from hanging due to setup issues

**Rationale for Job Timeouts**:
- Backend: 10 minutes (tests ~10s, leaves time for Gradle downloads, compilation)
- Frontend: 10 minutes (tests ~60s, leaves time for npm install, linting, build)
- Android: 20 minutes (tests ~30s, but Gradle cold start + APK build is slow)

### Artifact Uploads

#### Backend CI:
- **On Failure**: Test reports (`build/reports/tests/`, `build/test-results/`)
- **Retention**: 7 days

#### Frontend CI:
- **Always**: Coverage reports (`coverage/`)
- **On Success**: Production build (`dist/`)
- **Retention**: 7 days

#### Android CI:
- **On Failure**: Build reports (`app/build/reports/`, `app/build/test-results/`)
- **Always**: Debug APK (`app/build/outputs/apk/debug/app-debug.apk`)
- **Always**: Lint report (`app/build/reports/lint-results-debug.html`)
- **Retention**: 7 days

### How to Verify CI Timeout Locally

Replicate CI behavior on your machine:

```bash
# Backend (should complete in ~10s, fail if > 300s)
cd backend && timeout 300 ./gradlew clean build

# Frontend (should complete in <60s, fail if > 300s)
cd frontend && timeout 300 npm test -- --coverage

# Android (should complete in <30s, fail if > 300s)
cd android && timeout 300 ./gradlew test
```

If any command times out locally, investigate:
- Slow tests (add `@Timeout(30)` annotations)
- Missing mocks (real network calls)
- Inefficient test data generation
- Resource leaks (unclosed connections)

---

## Performance Metrics

### Expected Test Runtimes

| Component | Unit Tests | Integration Tests | Total Build |
|-----------|-----------|-------------------|-------------|
| Backend | < 10s | 30-60s | < 20s |
| Frontend | < 60s | - | < 120s |
| Android | < 0.3s | 2-5min | < 30s |

### Benchmarks (November 2025)

#### Backend
```
./gradlew test
- Tests: 144
- Runtime: 8.67s avg (3 runs)
- Pass rate: 100%
```

#### Frontend
```
npm test
- Tests: ~50
- Runtime: < 60s
- Pass rate: 100%
```

#### Android
```
./gradlew test
- Tests: 14 (across 3 variants: debug, release, benchmark)
- Runtime: 0.268s (debug variant), 26s (full clean build)
- Pass rate: 100%
- Flakiness: 0 flaky tests detected (3 consecutive runs)
- Test classes:
  - PlayerViewModelTest: 6 tests (0.120s)
  - MetadataHydratorTest: 4 tests (0.120s)
  - MetadataCacheTest: 3 tests (0.012s)
  - DownloadStorageJvmTest: 1 test (0.016s)
```

---

## Flakiness Policy

### Definition
A test is **flaky** if it exhibits non-deterministic behavior:
- Fails intermittently on the same code
- Depends on timing/sleep statements
- Relies on external services without mocking
- Has race conditions or shared mutable state

### Detection Strategy
1. Run test suite 3× consecutively
2. Any failure in 1/3 runs = flaky
3. Investigate and fix immediately

### Current Status
- **Backend**: 0 flaky tests (verified Nov 16, 2025)
- **Frontend**: 0 known flaky tests
- **Android**: Not yet verified

### Resolution Process
1. Identify flaky test via CI logs or local runs
2. Add `@Disabled` annotation with ticket reference
3. Investigate root cause:
   - Add proper mocking
   - Remove sleep/timing dependencies
   - Fix race conditions
   - Ensure test isolation
4. Re-enable test and verify 3× runs
5. Document fix in commit message

---

## Troubleshooting

### Backend

#### Problem: Tests fail with "Firestore not initialized"
**Solution**:
```bash
# Check application-test.yml exists
cat backend/src/test/resources/application-test.yml

# Ensure mocking is correct
@Mock private Firestore firestore;
```

#### Problem: Tests timeout
**Solution**:
```bash
# Check for slow operations
grep -r "Thread.sleep" backend/src/test/

# Increase timeout per test
@Timeout(60)  // seconds
```

#### Problem: Port conflict errors
**Solution**:
```yaml
# application-test.yml
server:
  port: 0  # Use random port
```

### Frontend

#### Problem: Tests fail with "Cannot find module"
**Solution**:
```bash
# Reinstall dependencies
cd frontend
rm -rf node_modules package-lock.json
npm install
```

#### Problem: Vitest hangs
**Solution**:
```bash
# Check for infinite loops in tests
npm test -- --reporter=verbose

# Force exit after timeout
timeout 60s npm test
```

#### Problem: Component tests fail with "document is not defined"
**Solution**:
```typescript
// vitest.config.ts
export default defineConfig({
  test: {
    environment: 'jsdom'  // Ensure jsdom is set
  }
})
```

### Android

#### Problem: `./gradlew test` fails with "SDK not found"
**Solution**:
```bash
# Set ANDROID_HOME
export ANDROID_HOME=$HOME/Android/Sdk
echo "sdk.dir=$ANDROID_HOME" > android/local.properties
```

#### Problem: Tests fail with "Could not resolve dependencies"
**Solution**:
```bash
cd android
./gradlew clean
./gradlew --refresh-dependencies test
```

### CI/CD

#### Problem: CI times out after 10 minutes
**Solution**:
```yaml
jobs:
  build:
    timeout-minutes: 15  # Increase if needed
```

#### Problem: Tests pass locally but fail in CI
**Causes**:
- Different environment variables
- Missing dependencies
- Port conflicts
- File system differences

**Solution**:
```bash
# Replicate CI environment locally
docker run -it ubuntu:latest
# Install dependencies and run tests
```

---

## Firebase Emulator Setup

### Installation
```bash
# Install Firebase CLI
npm install -g firebase-tools

# Login to Firebase
firebase login

# Initialize emulators
firebase init emulators
```

### Configuration (`firebase.json`)
```json
{
  "emulators": {
    "firestore": {
      "port": 8090
    },
    "auth": {
      "port": 9099
    },
    "ui": {
      "enabled": true,
      "port": 4000
    }
  }
}
```

### Starting Emulators
```bash
# Start all emulators
firebase emulators:start

# Start specific emulators
firebase emulators:start --only firestore,auth

# With data import
firebase emulators:start --import=./firebase-data

# With data export on shutdown
firebase emulators:start --export-on-exit=./firebase-data
```

### Emulator UI
- **URL**: http://localhost:4000
- **Features**:
  - Browse Firestore collections
  - View Auth users
  - Monitor requests
  - Clear data between tests

### Test Data Seeding
```bash
# Export current emulator state
firebase emulators:export ./firebase-data

# Import data on startup
firebase emulators:start --import=./firebase-data
```

### Integration Test Example

```java
@ExtendWith(MockitoExtension.class)
@Tag("integration")
class CategoryRepositoryIntegrationTest {
    private Firestore firestore;
    private CategoryRepository repository;

    @BeforeEach
    void setUp() {
        // Connect to emulator
        FirestoreOptions options = FirestoreOptions.newBuilder()
            .setProjectId("test-project")
            .setHost("localhost:8090")
            .setCredentials(NoCredentials.getInstance())
            .build();

        firestore = options.getService();
        repository = new CategoryRepository(firestore);
    }

    @AfterEach
    void tearDown() {
        // Clean up test data
        firestore.collection("categories").listDocuments()
            .forEach(doc -> doc.delete());
    }

    @Test
    void save_shouldPersistCategoryToFirestore() {
        Category category = new Category("Test Category");
        repository.save(category);

        Optional<Category> retrieved = repository.findById(category.getId());
        assertTrue(retrieved.isPresent());
        assertEquals("Test Category", retrieved.get().getName());
    }
}
```

---

## Best Practices

### General
1. **Write tests first** (TDD) for critical business logic
2. **Keep tests small** - one assertion per test (when possible)
3. **Use descriptive names** - `shouldReturnError_whenUserNotFound()`
4. **Avoid test interdependence** - each test should be runnable in isolation
5. **Mock external dependencies** - databases, APIs, file systems
6. **Clean up resources** - use `@AfterEach` to prevent test pollution

### Backend (JUnit)
1. Use `@BeforeEach` for common setup
2. Use `@Mock` for dependencies, `@InjectMocks` for system under test
3. Prefer `assertThrows()` for exception testing
4. Use `ArgumentCaptor` to verify mock interactions
5. Tag integration tests with `@Tag("integration")`

### Frontend (Vitest)
1. Use `describe` blocks to group related tests
2. Mock API calls with `vi.mock()`
3. Test user interactions with `@testing-library` queries
4. Avoid testing implementation details (internal state)
5. Use snapshot testing sparingly

### Android
1. Prefer unit tests over instrumentation tests (faster)
2. Use Robolectric for Android framework testing without emulator
3. Test ViewModels separately from UI
4. Mock repositories in ViewModel tests
5. Use Espresso for UI testing only when necessary

---

## References

- [AGENTS.md](../../AGENTS.md) - Test timeout policy
- [CLAUDE.md](../../CLAUDE.md) - Development guide
- [P0-T2-TEST-STABILITY-REPORT.md](P0-T2-TEST-STABILITY-REPORT.md) - Backend test analysis
- [JUnit 5 Documentation](https://junit.org/junit5/docs/current/user-guide/)
- [Vitest Documentation](https://vitest.dev/)
- [Mockito Documentation](https://javadoc.io/doc/org.mockito/mockito-core/latest/org/mockito/Mockito.html)
- [Firebase Emulator Suite](https://firebase.google.com/docs/emulator-suite)

---

**Last Updated**: November 16, 2025
**Maintainer**: Development Team
**Status**: Active
