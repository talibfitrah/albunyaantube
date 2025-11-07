# Albunyaan Tube Documentation

> Simplified documentation organized into 4 core categories

**Last Updated**: November 7, 2025
**Status**: ~60% Complete - Backend & Frontend operational, Android ready for testing

---

## 📂 Documentation Structure

```
docs/
├── design/          # UX, i18n, design system
├── architecture/    # Technical architecture, API, security
├── plan/            # Roadmap, acceptance criteria, backlog
├── status/          # Current status & operational guides
└── archived/        # Historical documents
```

---

## 🎨 Design

**What's Here**: User experience, design system, internationalization strategy

| File | Description |
|------|-------------|
| **[design-system.md](design/design-system.md)** | UI specifications, design tokens, component library |
| **[i18n-strategy.md](design/i18n-strategy.md)** | Internationalization (en/ar/nl), RTL support |
| **[design-tokens.json](design/design-tokens.json)** | CSS tokens, colors, typography |
| **[mockups/](design/mockups/)** | Screenshots and Figma exports |

---

## 🏗️ Architecture

**What's Here**: Technical system design, API specifications, security

| File | Description |
|------|-------------|
| **[overview.md](architecture/overview.md)** | System architecture, tech stack, design decisions |
| **[api-specification.yaml](architecture/api-specification.yaml)** | OpenAPI REST API specification |
| **[security.md](architecture/security.md)** | Threat model, security considerations |
| **[diagrams/](architecture/diagrams/)** | C4 diagrams (context, container, component, sequence) |

---

## 📋 Plan

**What's Here**: Project planning, requirements, roadmap

| File | Description |
|------|-------------|
| **[roadmap.md](plan/roadmap.md)** | Phased delivery plan (Phases 0-12) |
| **[acceptance-criteria.md](plan/acceptance-criteria.md)** | Acceptance criteria with traceability |
| **[risk-register.md](plan/risk-register.md)** | Project risks with likelihood/impact |
| **[backlog/](plan/backlog/)** | Product backlog CSVs (stories, estimates) |

---

## ✅ Status

**What's Here**: Current project status and operational guides

| File | Description |
|------|-------------|
| **[PROJECT_STATUS.md](status/PROJECT_STATUS.md)** | Current completion %, blockers, next steps |
| **[DEVELOPMENT_GUIDE.md](status/DEVELOPMENT_GUIDE.md)** | Setup instructions, troubleshooting |
| **[ANDROID_GUIDE.md](status/ANDROID_GUIDE.md)** | Android configuration, testing, player development |
| **[TESTING_GUIDE.md](status/TESTING_GUIDE.md)** | Testing strategy, data verification, performance |
| **[DEPLOYMENT_GUIDE.md](status/DEPLOYMENT_GUIDE.md)** | VPS deployment, HTTPS setup, monitoring |

---

## 🔍 Quick Navigation

### I want to...

| Task | Where to Look |
|------|---------------|
| **Set up development environment** | [status/DEVELOPMENT_GUIDE.md](status/DEVELOPMENT_GUIDE.md) |
| **Check current project status** | [status/PROJECT_STATUS.md](status/PROJECT_STATUS.md) |
| **Understand the architecture** | [architecture/overview.md](architecture/overview.md) |
| **Configure Android app** | [status/ANDROID_GUIDE.md](status/ANDROID_GUIDE.md) |
| **Deploy to VPS** | [status/DEPLOYMENT_GUIDE.md](status/DEPLOYMENT_GUIDE.md) |
| **Run tests** | [status/TESTING_GUIDE.md](status/TESTING_GUIDE.md) |
| **See the roadmap** | [plan/roadmap.md](plan/roadmap.md) |
| **View API specification** | [architecture/api-specification.yaml](architecture/api-specification.yaml) |
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
- **Before starting a phase**: Review [roadmap.md](plan/roadmap.md)

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
  - 4 Android docs → `ANDROID_GUIDE.md`
  - 4 Testing docs → `TESTING_GUIDE.md`
  - 3 Deployment docs → `DEPLOYMENT_GUIDE.md`
  - UX + i18n → `design/`
  - Architecture + security + API → `architecture/`
  - Roadmap + acceptance + backlog → `plan/`

- **Archived**: Implementation details, session notes, planning docs

- **Result**:
  - Before: 31 files, 22 directories
  - After: 17 files, 4 categories
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
