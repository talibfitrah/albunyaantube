# Agents Policy - Testing and Build Standards

**Last Updated**: November 16, 2025
**Status**: Active

This document defines the testing and build policies for AI agents and developers working on the Albunyaan Tube project.

---

## Implementation Status Summary

| Platform | Gradle/Build Timeout | CI Timeout | Per-Test Timeout | Status |
|----------|---------------------|------------|------------------|--------|
| Backend | ✅ 300s (build.gradle.kts) | ✅ 300s | ✅ 30s (JUnit systemProperty) | COMPLETE |
| Frontend | ✅ 300s (npm script) | ✅ 300s | ✅ 30s (vitest.config.ts) | COMPLETE |
| Android | ❌ Not configured | ✅ 300s | ❌ Not configured | CI-ONLY |

**Two-Layer Defense**:
- Backend/Frontend: Gradle/npm timeout + CI timeout (belt-and-suspenders)
- Android: CI timeout only (recommended to add Gradle-level timeout)

---

## Test Timeout Policy

### Global Test Timeout

**MANDATORY**: All test suites must complete within **300 seconds (5 minutes)**.

This policy applies to:
- Backend test suites (JUnit)
- Frontend test suites (Vitest)
- Android test suites (JUnit + Gradle)
- CI/CD pipeline test execution

### Rationale

1. **Prevent hanging tests**: Detect infinite loops and deadlocks early
2. **Fast feedback loops**: Ensure developers get quick test results
3. **CI/CD reliability**: Prevent pipelines from hanging indefinitely
4. **Resource efficiency**: Avoid wasting CI/CD resources on stuck tests

### Per-Test Timeout Recommendation

While the global timeout is mandatory, individual tests should ideally complete much faster:
- **Recommended per-test timeout**: 30 seconds maximum
- **Typical test execution**: < 1 second for unit tests
- **Integration test tolerance**: Up to 10 seconds acceptable

### Enforcement

#### Backend (JUnit + Gradle)
**Status**: ✅ IMPLEMENTED (backend/build.gradle.kts:82-87)

```kotlin
// build.gradle.kts (ALREADY IMPLEMENTED)
tasks.test {
    // AGENTS.md: Enforce 300-second (5-minute) timeout for all tests
    timeout.set(Duration.ofSeconds(300))

    // Set test timeouts to prevent hanging
    systemProperty("junit.jupiter.execution.timeout.default", "30s")
    systemProperty("junit.jupiter.execution.timeout.testable.method.default", "30s")
}
```

```java
// Individual test timeout (optional, already set globally via systemProperty above)
@Test
@Timeout(30)  // 30 seconds - explicit override if needed
void testMethod() {
    // test code
}
```

#### Frontend (Vitest + npm)
```json
// package.json
{
  "scripts": {
    "test": "timeout 300s vitest run"
  }
}
```

```typescript
// vitest.config.ts
export default defineConfig({
  test: {
    testTimeout: 30000,  // 30 seconds per test
  }
})
```

#### Android (Gradle)
**Status**: Currently enforced via CI timeout only. Gradle-level timeout recommended but not yet implemented.

**Recommended** (to match backend's two-layer defense):
```kotlin
// app/build.gradle.kts
android {
  testOptions {
    unitTests {
      all {
        timeout = java.time.Duration.ofSeconds(300)  // 300 seconds
      }
    }
  }
}
```

**Current**: Enforced only via CI `timeout 300 ./gradlew test` (see CI/CD section below)

#### CI/CD (GitHub Actions)
```yaml
# Explicit timeout wrapper (belt-and-suspenders approach)
- name: Run tests with timeout
  run: timeout 300 ./gradlew test
```

---

## Integration Test Policy

### Separation of Unit and Integration Tests

**Default behavior**: Integration tests are **excluded** from standard test runs.

#### Why Separate?

1. **Speed**: Unit tests run in < 10 seconds; integration tests take 30-60 seconds
2. **Dependencies**: Integration tests require Firebase emulator, external services
3. **CI efficiency**: Fast unit tests in every PR; integration tests on-demand or nightly

#### Integration Test Requirements

Integration tests require:
- Firebase emulator running (Firestore + Auth)
- Test data seeding
- Network connectivity (for external API tests)
- Additional setup time

#### Running Integration Tests

**Backend**:
```bash
# Start Firebase emulators (Terminal 1)
firebase emulators:start --only firestore,auth --import=firebase-data

# Run integration tests (Terminal 2)
cd backend
./gradlew test -Pintegration=true
```

**Tagging Integration Tests**:
```java
@Test
@Tag("integration")
void testFirestoreIntegration() {
    // Integration test code
}
```

---

## Flakiness Policy

### Definition

A test is **flaky** if it exhibits non-deterministic behavior:
- Fails intermittently on the same code
- Depends on timing/sleep statements
- Relies on external services without proper mocking
- Has race conditions or shared mutable state

### Zero Tolerance for Flakiness

**Policy**: Flaky tests are unacceptable in the main test suite.

### Detection Strategy

1. Run test suite 3× consecutively
2. Any failure in 1/3 runs = flaky
3. Investigate and fix immediately

### Resolution Process

When a flaky test is detected:

1. **Disable immediately**:
   ```java
   @Test
   @Disabled("Flaky - see ticket PROJ-123")
   void testFlaky() {
       // ...
   }
   ```

2. **Investigate root cause**:
   - Add proper mocking for external dependencies
   - Remove sleep/timing dependencies
   - Fix race conditions
   - Ensure test isolation (no shared state)

3. **Fix and verify**:
   - Implement fix
   - Re-enable test
   - Verify 3× consecutive runs pass

4. **Document**:
   - Commit message describes fix
   - Update test documentation

---

## Test Isolation Requirements

### Mandatory Isolation Practices

1. **Mock external dependencies**:
   - Firestore/Firebase
   - HTTP clients (Retrofit, Axios)
   - File systems
   - Network services (NewPipe, YouTube)

2. **Use random ports**:
   ```yaml
   # application-test.yml
   server:
     port: 0  # Random port prevents conflicts
   ```

3. **Disable caching**:
   ```yaml
   # application-test.yml
   spring:
     cache:
       type: none  # Prevents cross-test contamination
   ```

4. **Clean up after each test**:
   ```java
   @AfterEach
   void tearDown() {
       // Clean up test data
       // Reset mocks
       // Close resources
   }
   ```

5. **Avoid shared mutable state**:
   - Use `@BeforeEach` for test setup
   - Each test gets fresh instances
   - No static state unless truly immutable

---

## CI/CD Test Execution

### Canonical Commands

**Backend**:
```bash
timeout 300 ./gradlew clean build
```

**Frontend**:
```bash
timeout 300 npm test -- --coverage
```

**Android**:
```bash
timeout 300 ./gradlew test
```

### Artifact Requirements

**On test failure**, CI must upload:
- Test reports (JUnit XML, HTML)
- Build logs
- Coverage reports (if applicable)
- Lint reports (Android)

**Example** (GitHub Actions):
```yaml
- name: Upload test reports
  if: failure()
  uses: actions/upload-artifact@v3
  with:
    name: test-reports
    path: |
      build/reports/tests/
      build/test-results/
    retention-days: 7
```

### Job Timeout

While test commands have explicit 300s timeouts, CI jobs should have generous overall timeouts to account for:
- Dependency downloads
- Compilation
- Artifact uploads

**Recommended job timeouts**:
- Backend: 10 minutes
- Frontend: 10 minutes
- Android: 20 minutes (slower Gradle builds)

---

## Performance Expectations

### Expected Test Runtimes

| Component | Unit Tests | Integration Tests | Full Build |
|-----------|-----------|-------------------|------------|
| Backend | < 10s | 30-60s | < 20s |
| Frontend | < 60s | - | < 120s |
| Android | < 30s | 2-5min | < 3min |

### Performance Regression Detection

If test runtimes exceed these baselines by >50%, investigate:
- New tests with slow operations
- Missing mocks (actual network calls)
- Inefficient test data generation
- Resource leaks

---

## Developer Workflow

### Before Committing

```bash
# 1. Run tests locally with timeout
cd backend && timeout 300 ./gradlew test
cd frontend && timeout 300 npm test
cd android && timeout 300 ./gradlew test

# 2. Verify no flakiness (run 3×)
./gradlew test && ./gradlew test && ./gradlew test

# 3. Check coverage (backend example)
./gradlew test jacocoTestReport
open build/reports/jacoco/test/html/index.html
```

### When Tests Fail in CI But Pass Locally

Common causes:
- Environment variable differences
- Missing dependencies in CI
- Different Java/Node versions
- File system case sensitivity (Linux vs macOS)
- Timezone differences

**Solution**: Replicate CI environment locally using Docker.

---

## Compliance Checklist

Before merging any PR:

- [ ] All tests pass locally (3× consecutive runs)
- [ ] No flaky tests detected
- [ ] Test execution completes in < 300 seconds
- [ ] All external dependencies properly mocked
- [ ] No sleep/timing dependencies in tests
- [ ] Test data is deterministic
- [ ] CI passes with explicit timeout enforcement
- [ ] Test artifacts uploaded on failure (if applicable)

---

## References

- [CLAUDE.md](CLAUDE.md) - Development guide
- [TESTING_GUIDE.md](docs/status/TESTING_GUIDE.md) - Comprehensive testing procedures
- [P0-T2-TEST-STABILITY-REPORT.md](docs/status/P0-T2-TEST-STABILITY-REPORT.md) - Test stability analysis
- [JUnit 5 Documentation](https://junit.org/junit5/docs/current/user-guide/)
- [Vitest Documentation](https://vitest.dev/)

---

**Maintained by**: Development Team
**Last Verified**: November 16, 2025
**Enforcement**: Mandatory for all test code
