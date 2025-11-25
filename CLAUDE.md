# Albunyaan Tube - Claude Developer Guide

Ad-free, admin-curated halal YouTube client. Full-stack monorepo: Spring Boot (Java 17), Vue 3 (TypeScript), Kotlin Android.

## Quick Start

```bash
# Backend (port 8080)
cd backend && ./gradlew bootRun

# Frontend (port 5173)
cd frontend && npm run dev

# Run tests
cd backend && ./gradlew test          # excludes integration tests
cd frontend && npm test               # 300s timeout enforced
cd android && ./gradlew test
```

## Critical Policies

- **Test timeout**: 300 seconds max. 30s per test method.
- **No new docs**: Don't create .md files unless explicitly requested
- **i18n**: English, Arabic (RTL), Dutch supported

## Project Structure

```
backend/     Spring Boot REST API + Firebase Firestore
frontend/    Vue 3 admin dashboard
android/     Kotlin mobile app
docs/        design/ | architecture/ | plan/ | status/
```

## Key Documentation

| Doc | Purpose |
|-----|---------|
| [docs/status/PROJECT_STATUS.md](docs/status/PROJECT_STATUS.md) | Current completion & blockers |
| [docs/status/DEVELOPMENT_GUIDE.md](docs/status/DEVELOPMENT_GUIDE.md) | Setup & troubleshooting |
| [docs/architecture/api-specification.yaml](docs/architecture/api-specification.yaml) | OpenAPI spec (source of truth) |
| [docs/status/ANDROID_GUIDE.md](docs/status/ANDROID_GUIDE.md) | Android config & testing |

## Build Commands

### Backend
```bash
./gradlew test                    # unit tests only
./gradlew test -Pintegration=true # include integration tests
./gradlew bootJar                 # build JAR
./gradlew clean build             # full build
```

### Frontend
```bash
npm ci                  # install deps
npm test                # vitest (300s timeout)
npm run build           # production build
npm run test:e2e        # playwright
```

### Android
```bash
./gradlew assembleDebug        # debug APK
./gradlew connectedAndroidTest # instrumentation tests
```

## Architecture Overview

### Backend Layers
```
Controller → Service → Repository → Model (Firestore)
```

**Key Controllers**: `PublicContentController` (public API), `ApprovalController` (workflow), `YouTubeSearchController` (search)

**Key Services**: `YouTubeService` (NewPipeExtractor), `ApprovalService`, `AuthService` (Firebase)

### Frontend Layers
```
Views → Composables → Services (API) → DTOs (generated)
```

**Architectural decisions**:
- Services: Pure IO only (API calls, return raw DTOs)
- Utils/Transformers: Pure functions for DTO → UI mapping
- Composables: Domain logic, state, side effects
- Views: Consume composables, minimal logic

### Android (MVVM + Hilt)
```
Fragment → ViewModel → Repository → Retrofit
```

All dependencies via Hilt. No manual service locators.

## Approval Workflow

1. **Content Search**: Admin searches YouTube via backend
2. **Add for Approval**: Opens CategoryAssignmentModal
3. **Pending Approvals**: Admin reviews in PendingApprovalsView
4. **Approve/Reject**: Updates status, logs to audit
5. **Content Library**: Approved content visible to mobile app

## Key Files

### Backend
- `controller/PublicContentController.java` - public API
- `controller/ApprovalController.java` - approval workflow
- `service/YouTubeService.java` - NewPipeExtractor integration

### Frontend
- `src/views/` - page components
- `src/composables/useApprovals.ts` - approval logic
- `src/services/approvalService.ts` - API calls
- `src/generated/api/schema.ts` - OpenAPI types

### Android
- `ui/` - Fragments & ViewModels
- `data/service/` - Retrofit API
- `di/` - Hilt modules

## Environment Setup

```bash
cp .env.example .env
# Edit: FIREBASE_PROJECT_ID=albunyaan-tube
export GOOGLE_APPLICATION_CREDENTIALS="backend/src/main/resources/firebase-service-account.json"
```

**Ports**: Backend 8080, Frontend 5173, Firebase Emulator UI 4000

**Android emulator**: Use `http://10.0.2.2:8080` for backend

## Git Conventions

**Branch**: `feature/{issue-id}-{description}` or `fix/{issue-id}-{description}`

**Commit format**:
```
[PREFIX]: Description (under 50 chars)

[FEAT] New feature | [FIX] Bug fix | [REFACTOR] Refactoring
[PERF] Performance | [DOCS] Documentation | [TEST] Tests | [CHORE] Tooling
```

## Common Issues

| Issue | Solution |
|-------|----------|
| Firebase credentials not found | `export GOOGLE_APPLICATION_CREDENTIALS="backend/src/main/resources/firebase-service-account.json"` |
| Port 8080 in use | `lsof -ti:8080 \| xargs kill -9` |
| Frontend can't reach backend | Check `VITE_API_BASE_URL=http://localhost:8080/api/v1` |
| Android emulator can't reach backend | Use `http://10.0.2.2:8080/` |

## OpenAPI Code Generation

```bash
./scripts/generate-openapi-dtos.sh
```

Generates:
- TypeScript: `frontend/src/generated/api/schema.ts`
- Kotlin: `android/app/src/main/java/com/albunyaan/tube/data/model/api/`

## Testing Patterns

**Backend**: JUnit 5, mock Firestore repositories

**Frontend**: Vitest + Testing Library, mock axios

**Android**: Hilt test isolation with `@TestInstallIn`, `FakeContentApi`

## Current Status

**Phase 5 complete** - Service layer separation, DTO aliasing removal

**Next**: Phase 6 or pagination DTO standardization (P1-T4)

See [PROJECT_STATUS.md](docs/status/PROJECT_STATUS.md) for details.

---

**Last Updated**: November 19, 2025
