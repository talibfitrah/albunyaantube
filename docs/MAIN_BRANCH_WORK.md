# Main Branch Infrastructure Work

> **Purpose**: Safe infrastructure tasks that can run in parallel with feature branch development without causing merge conflicts.

## Strategy

While engineers work on feature branches (`feature/android-downloads`, `feature/backend-registry-downloads`, `feature/frontend-registry-downloads`), we can safely improve infrastructure, tooling, and documentation on `main` branch.

These tasks touch only:
- CI/CD configuration files (`.github/workflows/`)
- Developer tooling scripts (`scripts/`)
- Documentation (`docs/`)
- Configuration files (`docker-compose.yml`, `.env.example`)
- Test infrastructure (`android/app/src/test/`, `backend/src/test/`)

They **never** touch feature code, ensuring zero merge conflicts.

---

## ✅ Completed Tasks

### Priority 1: CI/CD (Week 1)

- ✅ **INFRA-01**: Android CI pipeline (Completed: 2025-10-05)
  - File: [`.github/workflows/android-ci.yml`](../.github/workflows/android-ci.yml)
  - Features: Automated build, test, lint on every push/PR
  - Artifacts: APK, test results, lint reports
  - Commit: `f3b3f4a`

- ✅ **INFRA-02**: Frontend CI pipeline (Completed: 2025-10-05)
  - File: [`.github/workflows/frontend-ci.yml`](../.github/workflows/frontend-ci.yml)
  - Features: npm ci, lint, type-check, test with coverage
  - Artifacts: Build output, coverage reports
  - Commit: `f3b3f4a`

---

## 🚧 Pending Tasks

### Priority 1: CI/CD & Infrastructure (Week 1)

- ⏸️ **INFRA-03**: Docker Compose for local development
  - File: `docker-compose.yml`
  - Services: Backend, Firebase Emulator Suite
  - Purpose: One-command local environment setup
  - Estimated: 2 hours

- ⏸️ **INFRA-04**: Backend deployment config
  - Files: `Dockerfile.backend`, `.dockerignore`
  - Purpose: Production-ready containerization
  - Estimated: 1 hour

### Priority 2: Testing Infrastructure (Week 2)

- ⏸️ **TEST-01**: Android instrumented test setup
  - Files: `android/app/src/androidTest/`
  - Purpose: UI testing framework with Espresso
  - Estimated: 3 hours

- ⏸️ **TEST-02**: Backend integration test utilities
  - Files: `backend/src/test/java/.../util/`
  - Purpose: Test data builders, Firestore mocks
  - Estimated: 2 hours

- ⏸️ **TEST-03**: Frontend testing utilities
  - Files: `frontend/src/test/utils/`
  - Purpose: React Testing Library helpers, mock services
  - Estimated: 2 hours

### Priority 3: Documentation (Week 2-3)

- ⏸️ **DOCS-01**: API documentation with OpenAPI/Swagger
  - Files: `docs/api/openapi.yml`, backend annotations
  - Purpose: Interactive API documentation
  - Estimated: 3 hours

- ⏸️ **DOCS-02**: Architecture decision records (ADRs)
  - Files: `docs/architecture/decisions/`
  - Purpose: Document key technical decisions
  - Estimated: 2 hours

- ⏸️ **DOCS-03**: Contributing guide
  - File: `CONTRIBUTING.md`
  - Purpose: Developer onboarding and workflow
  - Estimated: 1 hour

### Priority 4: Code Quality (Week 3)

- ⏸️ **QUALITY-01**: Detekt for Android (Kotlin linting)
  - Files: `android/detekt.yml`, `android/build.gradle`
  - Purpose: Enforce Kotlin code style
  - Estimated: 1 hour

- ⏸️ **QUALITY-02**: ESLint + Prettier enforcement
  - Files: `.eslintrc.json`, `.prettierrc`
  - Purpose: Consistent frontend code style
  - Estimated: 1 hour

- ⏸️ **QUALITY-03**: Pre-commit hooks
  - Files: `.husky/pre-commit`, `package.json`
  - Purpose: Prevent bad commits (linting, formatting)
  - Estimated: 1 hour

### Priority 5: Developer Experience (Week 4)

- ⏸️ **DX-01**: Development setup script
  - File: `scripts/setup-dev.sh`
  - Purpose: Automated first-time setup
  - Estimated: 2 hours

- ⏸️ **DX-02**: Environment validation script
  - File: `scripts/validate-env.sh`
  - Purpose: Check all required tools/configs
  - Estimated: 1 hour

- ⏸️ **DX-03**: Debugging guides
  - Files: `docs/debugging/`
  - Purpose: Common issues and solutions
  - Estimated: 2 hours

### Priority 6: Internationalization (Week 4)

- ⏸️ **I18N-01**: Translation workflow documentation
  - File: `docs/i18n/translation-workflow.md`
  - Purpose: Guide for adding new languages
  - Estimated: 1 hour

- ⏸️ **I18N-02**: String extraction scripts
  - File: `scripts/extract-strings.sh`
  - Purpose: Automated translation file generation
  - Estimated: 2 hours

---

## Work Principles

1. **Zero Conflicts**: Only touch infrastructure files, never feature code
2. **Self-Contained**: Each task is independent and can be completed in one session
3. **Immediately Useful**: Every task adds value to all engineers
4. **Well-Documented**: Each PR includes clear documentation of changes
5. **CI-Verified**: All changes pass existing CI checks

---

## Progress Tracking

Track progress in [PROJECT_STATUS.md](PROJECT_STATUS.md) under "Main Branch Work" section.

Update status after each commit:
- ⏸️ Not Started
- 🚧 In Progress
- ✅ Completed
- ⏭️ Skipped (with reason)

---

## Estimated Timeline

- **Week 1**: CI/CD & Docker (4 tasks, ~6 hours)
- **Week 2**: Testing & Documentation (5 tasks, ~10 hours)
- **Week 3**: Code Quality (3 tasks, ~3 hours)
- **Week 4**: Developer Experience & i18n (5 tasks, ~8 hours)

**Total**: 17 tasks, ~27 hours over 4 weeks
