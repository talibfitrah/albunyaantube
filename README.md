# Albunyaan Tube

Welcome to **Albunyaan Tube**, an ad-free, admin-curated halal YouTube client delivering safe Islamic content to Muslim families and students of knowledge through native mobile apps and a web-based moderation dashboard.

**Vision**: Become the trusted global platform for halal YouTube content where every Muslim can confidently access safe Islamic content without compromising values or exposure to inappropriate material.

This repository contains the complete codebase (Android app, admin dashboard, backend API) along with all design artifacts, architectural decisions, and planning documentation organized by discipline to keep product, engineering, and design aligned.

**See**: [`docs/PRD.md`](docs/PRD.md) for complete product requirements and feature specifications.

## How to Navigate

**📌 Start Here:**
- **Complete Product Requirements**: [`docs/PRD.md`](docs/PRD.md) - Vision, features, user stories, success metrics
- **Documentation Hub**: [`docs/README.md`](docs/README.md) - Complete navigation for all documentation
- **Developer Guide**: [`CLAUDE.md`](CLAUDE.md) - Essential guide for AI assistants and developers

**🎨 Design & UX:**
- Design system, UI specifications: [`docs/design/design-system.md`](docs/design/design-system.md)
- Internationalization strategy (en/ar/nl): [`docs/design/i18n-strategy.md`](docs/design/i18n-strategy.md)
- Design tokens (CSS variables): [`frontend/src/assets/main.css`](frontend/src/assets/main.css)

**🏗️ Architecture:**
- System architecture overview: [`docs/architecture/overview.md`](docs/architecture/overview.md)
- REST API specification (OpenAPI): [`docs/architecture/api-specification.yaml`](docs/architecture/api-specification.yaml)
- Security & threat model: [`docs/architecture/security.md`](docs/architecture/security.md)
- C4 diagrams: [`docs/architecture/diagrams/`](docs/architecture/diagrams/)

**📋 Planning:**
- Product requirements & roadmap: [`docs/PRD.md`](docs/PRD.md) - Includes phased delivery plan, user stories, and acceptance criteria
- Risk analysis: See [`docs/PRD.md#risks--open-questions`](docs/PRD.md#risks--open-questions) - Content moderation scalability, NewPipe breakage, Firebase costs

**✅ Current Status:**
- Project status & completion: [`docs/status/PROJECT_STATUS.md`](docs/status/PROJECT_STATUS.md)
- Development setup guide: [`docs/status/DEVELOPMENT_GUIDE.md`](docs/status/DEVELOPMENT_GUIDE.md)
- Android app guide: [`docs/status/ANDROID_GUIDE.md`](docs/status/ANDROID_GUIDE.md)
- Testing & deployment: See [`docs/PRD.md`](docs/PRD.md) for testing requirements and performance budgets

## Key Features

**Content Curation:**
- YouTube content search via NewPipeExtractor (no API key required)
- Three-stage approval workflow: submission → pending → approved/rejected
- ADMIN role: direct approval; MODERATOR role: submission for review
- Hierarchical category assignment (max 2 levels)
- Granular exclusions (specific videos/playlists within approved channels)
- Live stream support with automatic status updates
- Bulk import/export system with merge strategies
- Video validation system to detect unavailable content

**Mobile Experience (Android):**
- Browse approved content by category, channel, playlist, video
- Advanced video player: quality selection, audio-only mode, subtitles, PiP, Chromecast
- Offline downloads with 30-day expiry and audio-only option
- Search with persistent history (max 10 items)
- Safe mode toggle for family-friendly filtering
- Multi-language: English, Arabic (RTL), Dutch

**Admin Dashboard (Web):**
- Content search and preview
- Pending approvals queue with inline category assignment
- Advanced content library with filters and bulk actions
- Exclusions workspace for granular content management
- Metrics dashboard with trend analysis and validation status
- User management (ADMIN/MODERATOR roles)
- Audit log with comprehensive filtering

## Traceability
Every document references related artifacts to ensure consistency:
- Requirements → APIs → Acceptance criteria are linked through user stories in [`docs/PRD.md`](docs/PRD.md)
- Security controls and threat model documented in [`docs/architecture/security.md`](docs/architecture/security.md)
- Data models and API contracts specified in [`docs/architecture/api-specification.yaml`](docs/architecture/api-specification.yaml)
- Internationalization strategy detailed in [`docs/design/i18n-strategy.md`](docs/design/i18n-strategy.md)

## Project Components

### Android App
- **Location**: `android/` directory
- **Tech Stack**: Kotlin, Jetpack Compose, Material Design 3, ExoPlayer
- **Features**: RTL support, offline-first architecture, background downloads, advanced video player
- **Documentation**: See [`docs/status/ANDROID_GUIDE.md`](docs/status/ANDROID_GUIDE.md) for configuration, testing, and troubleshooting
- **Current Status**: Phase 5 complete - Production ready with signed AAB and RTL polish
- **Build**: `cd android && ./gradlew assembleDebug` (outputs to `app/build/outputs/apk/debug/`)
- **Backend URL**: Configure in `app/build.gradle.kts` (default: `http://192.168.1.167:8080/` for testing)

### Backend API
- **Location**: `backend/` directory
- **Tech Stack**: Spring Boot 3.2.5, Java 17, Firebase Firestore, NewPipeExtractor
- **Features**: REST API (67 endpoints across 11 controllers), role-based auth, caching, audit logging
- **Documentation**: See [`docs/architecture/api-specification.yaml`](docs/architecture/api-specification.yaml) for complete API spec
- **Current Status**: ✅ Integrated with Firestore, seeded with sample data (173 videos, 13 channels, 6 playlists, 19 categories)
- **Run**: `cd backend && ./gradlew bootRun` (starts on `http://localhost:8080`)
- **Test**: `./gradlew test` for JUnit tests

### Admin Frontend
- **Location**: `frontend/` directory
- **Tech Stack**: Vue 3, TypeScript, Vite, Pinia, Vue Router, Vue-i18n
- **Features**: Content search, approval workflow, category management, metrics dashboard, bulk operations
- **Documentation**: See [`docs/status/DEVELOPMENT_GUIDE.md`](docs/status/DEVELOPMENT_GUIDE.md) for setup
- **Current Status**: Phase 3 complete - Content library, moderation queue, and dashboard implemented
- **Run**: `cd frontend && npm run dev` (starts on `http://localhost:5173`)
- **Test**: `npm test` for Vitest unit tests (300s timeout enforced)

## Development Workflow

**Quick Start:**
```bash
# 1. Clone and setup
git clone <repository-url>
cd albunyaantube
cp .env.example .env  # Configure Firebase credentials

# 2. Start backend (Terminal 1)
cd backend
./gradlew bootRun  # Runs on http://localhost:8080

# 3. Start frontend (Terminal 2)
cd frontend
npm install
npm run dev  # Runs on http://localhost:5173

# 4. Build Android app (Terminal 3)
cd android
./gradlew assembleDebug  # APK in app/build/outputs/apk/debug/
```

**Testing:**
```bash
# Backend tests
cd backend && ./gradlew test

# Frontend tests (300s timeout enforced)
cd frontend && npm test

# Android tests
cd android && ./gradlew test
```

**Documentation Updates:**
1. Update relevant documentation in `docs/` directory
2. Ensure cross-references remain valid
3. Update `docs/status/PROJECT_STATUS.md` after completing features
4. Follow commit message format from [`CLAUDE.md`](CLAUDE.md)

**For Contributors:**
- See [`CLAUDE.md`](CLAUDE.md) for complete developer guide
- See [`docs/status/DEVELOPMENT_GUIDE.md`](docs/status/DEVELOPMENT_GUIDE.md) for troubleshooting
- See [`docs/PRD.md`](docs/PRD.md) for feature requirements and acceptance criteria

## Dark Mode Tokens
| Token | Light | Dark | Component Mapping |
| --- | --- | --- | --- |
| `--color-bg` | `#f5f8f6` | `#041712` | Workspace backgrounds, admin layout main area |
| `--color-surface` | `#ffffff` | `#0b231c` | Panels, tables, cards, login form |
| `--color-surface-alt` | `#e8f1ec` | `#102c24` | Filter controls, table headers, muted tags |
| `--color-text-primary` | `#132820` | `#eef6f2` | Headings, entity names |
| `--color-text-secondary` | `#4f665c` | `#9ec9b4` | Body copy, helper text, empty states |
| `--color-brand` | `#16835a` | `#35c491` | Primary actions, active chips, progress accents |
| `--color-accent` | `#2fa172` | `#7cdcb3` | Hover states for actions, tab highlights |
| `--color-danger` | `#dc2626` | `#f87171` | Error banners, moderation rejection badges |

The token definitions live in [`frontend/src/assets/main.css`](frontend/src/assets/main.css) and are referenced by all Vue views and registry tables. These tokens are the source of truth for both implementation and design mockups (see [`docs/design/mockups/`](docs/design/mockups/) and [`docs/design/design-system.md`](docs/design/design-system.md)).

## Canonical Navigation Tabs
The end-user app shell uses a single source of truth for the Home/Channels/Playlists/Videos tabs defined in `frontend/src/constants/tabs.ts`. A reusable navigation component (`frontend/src/components/navigation/MainTabBar.vue`) consumes that config so every surface renders the same iconography and labels.
