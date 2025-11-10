# Albunyaan Tube Documentation

> Simplified documentation organized into 4 core categories

**Last Updated**: November 7, 2025
**Status**: ~60% Complete - Backend & Frontend operational, Android ready for testing

**📋 Product Requirements**: See [PRD.md](PRD.md) for complete product vision, features, user stories, and success metrics.

---

## 📂 Documentation Structure

```
docs/
├── PRD.md           # Product Requirements Document (vision, features, roadmap)
├── design/          # UX, i18n, design system
├── architecture/    # Technical architecture, API, security
├── status/          # Current status & operational guides
└── archived/        # Historical documents
```

---

## 🎨 Design

**What's Here**: User experience, design system, internationalization strategy

| File | Description |
|------|-------------|
| **[design-system.md](design/design-system.md)** | UI specifications, design tokens, component library, WCAG 2.1 AA compliance |
| **[i18n-strategy.md](design/i18n-strategy.md)** | Internationalization (en/ar/nl), RTL support, ICU MessageFormat plurals |
| **[mockups/](design/mockups/)** | Screenshots and Figma exports (see mockups/README.md for access details) |

**Note**: Design tokens (CSS variables) are implemented in `../frontend/src/assets/main.css`

---

## 🏗️ Architecture

**What's Here**: Technical system design, API specifications, security

| File | Description |
|------|-------------|
| **[overview.md](architecture/overview.md)** | System architecture, tech stack (Spring Boot + Vue 3 + Kotlin), design decisions |
| **[api-specification.yaml](architecture/api-specification.yaml)** | OpenAPI 3.0 REST API specification (67 endpoints across 11 controllers) |
| **[security.md](architecture/security.md)** | Threat model, security controls, Firebase Auth + RBAC (ADMIN/MODERATOR roles) |
| **[diagrams/](architecture/diagrams/)** | C4 diagrams (context, container, component, sequence) |

---

## ✅ Status

**What's Here**: Current project status and operational guides

| File | Description |
|------|-------------|
| **[PROJECT_STATUS.md](status/PROJECT_STATUS.md)** | Current completion ~60%, known blockers, next steps |
| **[DEVELOPMENT_GUIDE.md](status/DEVELOPMENT_GUIDE.md)** | Local dev setup (backend + frontend + Firebase), troubleshooting, environment variables |
| **[ANDROID_GUIDE.md](status/ANDROID_GUIDE.md)** | Android configuration (API URL, build variants), ExoPlayer integration, testing on device/emulator |

**Note**: For testing strategy and deployment procedures, see [PRD.md](PRD.md) for requirements and performance budgets.

---

## 🔍 Quick Navigation

### I want to...

| Task | Where to Look |
|------|---------------|
| **Understand the product vision** | [PRD.md](PRD.md) - Complete product requirements |
| **See the roadmap & release plan** | [PRD.md](PRD.md#release-plan-mvp--v11) - MVP → v1.1 → v1.2+ |
| **Read user stories & acceptance criteria** | [PRD.md](PRD.md#user-stories--acceptance-criteria) |
| **Check project risks** | [PRD.md](PRD.md#risks--open-questions) |
| **Set up development environment** | [status/DEVELOPMENT_GUIDE.md](status/DEVELOPMENT_GUIDE.md) |
| **Check current project status** | [status/PROJECT_STATUS.md](status/PROJECT_STATUS.md) |
| **Understand the architecture** | [architecture/overview.md](architecture/overview.md) |
| **View API specification** | [architecture/api-specification.yaml](architecture/api-specification.yaml) |
| **Configure Android app** | [status/ANDROID_GUIDE.md](status/ANDROID_GUIDE.md) |
| **Check design system** | [design/design-system.md](design/design-system.md) |
| **Understand i18n strategy** | [design/i18n-strategy.md](design/i18n-strategy.md) |

---

## 📦 Archived

**What's Here**: Historical documents not actively maintained

| Folder | Contents |
|--------|----------|
| **[archived/sessions/](archived/sessions/)** | Development session notes |
| **[archived/planning/](archived/planning/)** | Historical planning documents |
| **[archived/android-player/](archived/android-player/)** | Player development work logs |
| **[archived/performance-profiling/](archived/performance-profiling/)** | Historical performance notes |
| **[archived/features/](archived/features/)** | Feature-specific implementation docs |
| **[archived/system-prompts/](archived/system-prompts/)** | AI agent system prompts |

---

## 📝 Documentation Standards

### When to Update

- **After completing a feature**: Update [PROJECT_STATUS.md](status/PROJECT_STATUS.md)
- **After architectural changes**: Update [architecture/overview.md](architecture/overview.md)
- **After adding setup steps**: Update [DEVELOPMENT_GUIDE.md](status/DEVELOPMENT_GUIDE.md)
- **Before starting a phase**: Review [PRD.md](PRD.md) release plan and user stories

### File Organization Rules

✅ **DO**:
- Keep docs concise and actionable
- Use relative links for navigation
- Update date stamps on modified files
- Archive old session notes to `archived/`

❌ **DON'T**:
- Create new top-level folders (stick to 4 categories)
- Duplicate information across files
- Leave outdated status information
- Mix planning docs with operational guides

### Commit Message Format

```
[DOCS]: Brief description

- What was updated
- Why it was needed

🤖 Generated with [Claude Code](https://claude.com/claude-code)

Co-Authored-By: Claude <noreply@anthropic.com>
```

---

## 🧹 Recent Changes

### November 7, 2025 - Radical Simplification

**Restructured** from 22 directories to 4 core categories:

- **Consolidated**:
  - Multiple Android docs → `status/ANDROID_GUIDE.md`
  - Development setup docs → `status/DEVELOPMENT_GUIDE.md`
  - UX + i18n → `design/`
  - Architecture + security + API → `architecture/`
  - Product requirements, roadmap, testing → `PRD.md` (single source of truth)

- **Archived**: Implementation details, session notes, planning docs to `archived/`

- **Result**:
  - Before: 31 files, 22 directories
  - After: 4 core categories + PRD.md
  - Reduction: 45% fewer files, 82% fewer directories

---

## ❓ Getting Help

- **Setup Issues**: [status/DEVELOPMENT_GUIDE.md](status/DEVELOPMENT_GUIDE.md) - Troubleshooting section
- **Architecture Questions**: [architecture/overview.md](architecture/overview.md)
- **Current Status**: [status/PROJECT_STATUS.md](status/PROJECT_STATUS.md)
- **For Contributors**: See [../CLAUDE.md](../CLAUDE.md) - Complete developer guide

---

**Last Simplified**: November 7, 2025
**Next Review**: December 7, 2025
**Maintainer**: See [../README.md](../README.md)
