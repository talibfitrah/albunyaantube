# Testing Guide

Complete testing strategy and procedures for backend, frontend, and Android platforms.

**Last Updated**: November 7, 2025

---

## Table of Contents
1. [Test Strategy Overview](#test-strategy-overview)
2. [Backend Testing](#backend-testing)
3. [Frontend Testing](#frontend-testing)
4. [Android Testing](#android-testing)
5. [Data Verification](#data-verification)
6. [Performance Testing](#performance-testing)

---

## Test Strategy Overview

### Guiding Principles
- Automated coverage for every acceptance criterion
- Real dependencies via Testcontainers for backend persistence
- Localized fixtures (en/ar/nl) to verify translations
- Security regression tests for JWT, RBAC, and authentication

### Test Pyramid

```
        ┌─────────────┐
        │     E2E     │  (Playwright, Espresso)
        └─────────────┘
       ┌───────────────┐
       │  Integration  │  (API tests, UI integration)
       └───────────────┘
      ┌─────────────────┐
      │      Unit       │  (Jest, JUnit, isolated logic)
      └─────────────────┘
```

---

## Backend Testing

### Test Layers

| Layer | Tools | Scope |
|-------|-------|-------|
| **Unit** | JUnit 5, Mockito | Service logic, pagination, download policies |
| **Integration** | Spring Boot Test + Testcontainers | Auth flows, repository queries, caching |
| **Contract** | Spring Cloud Contract / OpenAPI | Response schema validation |
| **Performance** | Gatling | List endpoints, 200 RPS, <150ms response |
| **Security** | OWASP ZAP, dependency scanning | Vulnerabilities, TLS enforcement |

### Running Tests

```bash
cd backend

# Run all unit tests
./gradlew test

# Run integration tests
./gradlew integrationTest

# Run specific test
./gradlew test --tests CategoryServiceTest

# Run with coverage
./gradlew test jacocoTestReport

# View coverage report
open build/reports/jacoco/test/html/index.html
```

### Test Data

- **Seed data**: Via Spring profile `seed`
- **Test fixtures**: Located in `src/test/resources/fixtures/`
- **Synthetic data**: Use fake thumbnails, avoid hitting YouTube

### Performance Budgets

- **API latency**: p95 < 150ms
- **Payload size**: < 80KB per page
- **Cache hit ratio**: > 85%

---

## Frontend Testing

### Test Layers

| Type | Tools | Scope |
|------|-------|-------|
| **Unit** | Vitest | Components, stores, utilities |
| **Integration** | Vitest + Testing Library | Component interactions, API mocking |
| **E2E** | Playwright | Full user flows, accessibility |
| **Accessibility** | @axe-core/playwright | WCAG AA compliance |

### Running Tests

```bash
cd frontend

# Run all unit tests (300s timeout enforced)
npm test

# Run specific test
npm test -- CategoryAssignmentModal.spec.ts

# Run E2E tests
npm run test:e2e

# Run with coverage
npm test -- --coverage

# View coverage report
open coverage/index.html
```

### Test Coverage Areas

**Core Functionality**:
- Component rendering and props
- Store state management (Pinia)
- API service mocking (axios)
- Routing and navigation

**UI Features**:
- Form validation
- Modal interactions
- Table sorting/filtering
- Search debouncing
- Internationalization (en/ar/nl)

**E2E Scenarios** (Playwright):
- Login → Dashboard navigation
- Content search → Add for approval → Approve
- Category management (create, edit, delete)
- User management workflows
- Audit log pagination

### Accessibility Testing

```bash
# Run axe-core checks
npm run test:e2e -- --grep @accessibility

# Check specific component
npm test -- --grep "accessibility" CategoryTree.spec.ts
```

**Requirements**:
- WCAG AA compliance
- Keyboard navigation
- Focus management in modals
- Screen reader announcements (Arabic RTL)

---

## Android Testing

### Test Layers

| Type | Tools | Scope |
|------|-------|-------|
| **Unit** | JUnit + Mockito | ViewModels, use cases, repositories |
| **Instrumentation** | Espresso | Navigation, UI interactions |
| **Integration** | Room + Retrofit mocks | Data layer, API calls |
| **Performance** | Macrobenchmark | Startup time, scroll performance |

### Running Tests

```bash
cd android

# Run unit tests
./gradlew test

# Run instrumentation tests (requires device/emulator)
./gradlew connectedAndroidTest

# Run specific test
./gradlew test --tests PlayerViewModelTest

# Run macrobenchmarks
./gradlew :app:connectedMacrobenchmark
```

### Test Coverage Areas

**Core Functionality**:
- ViewModel state management
- Repository data fetching
- Navigation flows (onboarding, tabs, player)
- Paging 3 integration
- Download queue management

**UI Testing** (Espresso):
- Bottom navigation
- Search and filtering
- Video player controls
- Category selection
- Settings screens

**Player Testing**:
- Quality selection
- Seamless switching
- Audio-only mode
- PiP (Picture-in-Picture)
- Background playback

### Performance Metrics

**Macrobenchmark Targets**:
- **Cold start**: < 2.5s (Pixel 4a)
- **Scroll jank**: < 5% frames dropped
- **Download notification**: < 3s from enqueue

**Monitoring**:
- Firebase Crashlytics (≥99% crash-free)
- ANR rate (< 0.5%)
- Memory usage trends

---

## Data Verification

### Backend Data Seeding

#### Seed with Real YouTube Data

```bash
cd backend

# Run with seed profile
./gradlew bootRun --args='--spring.profiles.active=seed'

# Or via script
./scripts/seed-data.sh
```

**Expected Results** (November 7, 2025):
- **Channels**: 13 (all real YouTube channels)
- **Playlists**: 6 (all real YouTube playlists)
- **Videos**: 173 (all real YouTube videos)
- **Categories**: 19 (hierarchical category structure)

#### Verify Seeded Data

```bash
# Check categories
curl http://localhost:8080/api/v1/categories | jq 'length'
# Expected: 19

# Check channels
curl 'http://localhost:8080/api/v1/content?type=CHANNELS&limit=50' | jq '.data | length'
# Expected: 13

# Check videos
curl 'http://localhost:8080/api/v1/content?type=VIDEOS&limit=50' | jq '.data | length'
# Expected: 50+ (paginated)
```

### Real Channel List

All channels are legitimate Islamic content creators:

1. Hadith Disciple - UCKi_blYOU7xF9Xd_ev6vy7w
2. Mufti Menk - UCTX8ZbNDi_HBoyjTWRw9fAg
3. One Islam Productions - UCTX8ZbNDi_HBoyjTWRw9fAg
4. El Sheikh Islam Sobhy - UC1QzVyZX-LUIgyA6Qs3J51Q
5. برنامج أكاديمية زاد - Zad academy - UCBoe29aQT-zMECFyyyO7H4Q
6. قناة زاد العلمية - UCOll3M-P7oKs5cSrQ9ytt6g
7. Rachids Welt - UCq_38-upzmQVmF7tmL-n3SA
8. Learn with Zakaria - UCtlcIZVBdFPSAtCoNZsTusg
9. مجموعة زاد - UCw0OFJrMMH6N5aTyeOTTWZQ
10. The Authentic Hadiths - UC9NKXRcqSamfwpqvOtPuagw
11. Islam Sobhi - Topic - UCoKmbrhoPpSs4jHyGuKHRjg
12. Inspire by Mufti Menk - UCbeyGB9CYUwoedGyZz-Q2qQ
13. NOOR - Holy Quran - UCtEAHShGkRAPDcvHAT720FA

### Data Cleanup

Remove old stub data (if needed):

```bash
# Via Firebase Console
# 1. Go to Firestore Database
# 2. Filter by createdBy = "seed-script@albunyaan.tube"
# 3. Delete old documents
# 4. Keep documents with createdBy = "real-seed-script@albunyaan.tube"
```

---

## Performance Testing

### Backend Performance

#### Gatling Tests

```bash
cd backend

# Run performance tests
./gradlew gatlingRun

# View reports
open build/reports/gatling/index.html
```

**Performance Budgets**:
- **p95 latency**: < 150ms
- **Payload size**: < 80KB per page
- **Throughput**: 200 RPS sustained
- **Cache hit ratio**: > 85%

### Android Performance

#### Macrobenchmarks

```bash
cd android

# Run startup benchmark
./gradlew :app:connectedMacrobenchmark -Pandroid.testInstrumentationRunnerArguments.class=com.albunyaan.tube.benchmark.StartupBenchmark

# Run scroll benchmark
./gradlew :app:connectedMacrobenchmark -Pandroid.testInstrumentationRunnerArguments.class=com.albunyaan.tube.benchmark.ScrollBenchmark
```

**Results Location**: `android/app/build/outputs/connected_android_test_additional_output/`

**Performance Targets**:
- **Cold start**: < 2.5s
- **Scroll jank**: < 5% dropped frames
- **Memory**: < 150MB average usage

### Monitoring

**Backend (Prometheus)**:
- API response times
- Error rates
- Cache hit ratios
- Database query performance

**Android (Firebase)**:
- Crash-free sessions (≥99%)
- ANR rate (< 0.5%)
- App startup time
- Network request latency

---

## Release Testing

### Pre-Release Checklist

**Backend**:
- [ ] All unit tests pass
- [ ] Integration tests pass
- [ ] Performance tests within budgets
- [ ] Security scan clean
- [ ] API documentation updated

**Frontend**:
- [ ] Unit tests pass (300s timeout)
- [ ] E2E tests pass
- [ ] Accessibility tests pass (axe-core)
- [ ] i18n translations complete (en/ar/nl)
- [ ] Production build successful

**Android**:
- [ ] Unit tests pass
- [ ] Instrumentation tests pass
- [ ] Macrobenchmarks within targets
- [ ] Firebase Crashlytics ≥99% crash-free
- [ ] Release APK builds successfully

### Smoke Tests (Post-Deployment)

```bash
# Backend health
curl https://api.yourdomain.com/actuator/health

# Categories endpoint
curl https://api.yourdomain.com/api/v1/categories | jq 'length'

# Content endpoint
curl 'https://api.yourdomain.com/api/v1/content?type=CHANNELS&limit=5'

# Search endpoint
curl 'https://api.yourdomain.com/api/v1/search?query=quran&limit=5'
```

---

## Additional Resources

- **Test Strategy (Detailed)**: `docs/archived/testing/test-strategy-detailed.md`
- **Android Macrobenchmark**: `docs/archived/testing/android-macrobenchmark.md`
- **Project Status**: `docs/status/PROJECT_STATUS.md`
- **Architecture**: `docs/architecture/overview.md`

---

**Last Updated**: November 7, 2025
**Consolidated From**: test-strategy.md, DATA_VERIFICATION.md, PHASE2_TESTING_CHECKLIST.md, android-macrobenchmark.md
