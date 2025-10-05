# Albunyaan Tube Documentation

> Comprehensive documentation for the Albunyaan Tube video platform

**Last Updated**: 2025-10-05
**Current Phase**: Phase 6 Complete ✅

---

## Quick Start

- **Project Status**: [PROJECT_STATUS.md](PROJECT_STATUS.md) - Current phase, completed tickets, next steps
- **Platform Guides**: [PLATFORM_GUIDES.md](PLATFORM_GUIDES.md) - Backend, Frontend, Android setup & troubleshooting
- **Roadmap**: [roadmap/roadmap.md](roadmap/roadmap.md) - Phased delivery plan with status tracking

---

## Core Documentation (Keep)

### 1. Vision & Strategy
- **[vision/vision.md](vision/vision.md)** - Product vision, mission, success metrics, constraints
- **[roadmap/roadmap.md](roadmap/roadmap.md)** - Phased delivery plan (Phases 0-12)
- **[PROJECT_STATUS.md](PROJECT_STATUS.md)** - Current status, completed work, next steps

### 2. Architecture & Design
- **[architecture/solution-architecture.md](architecture/solution-architecture.md)** - System architecture, tech stack, design patterns
- **[architecture/diagrams/](architecture/diagrams/)** - C4 diagrams (Context, Container, Component)
  - `context.md` - System context
  - `container.md` - Container diagram
  - `backend-components.md` - Backend component details
  - `moderation-sequence.md` - Moderation workflow
  - `channel-tabs-sequence.md` - Channel tab interactions

### 3. UX & Design
- **[ux/design.md](ux/design.md)** - Design system, UI specifications
- **[ux/mockups/](ux/mockups/)** - Figma mockups and screenshots
- **[i18n/strategy.md](i18n/strategy.md)** - Internationalization strategy (en/ar/nl), RTL support

### 4. Development Guides
- **[PLATFORM_GUIDES.md](PLATFORM_GUIDES.md)** - All platform setup, operations, troubleshooting
  - Backend: Firebase, Spring Boot, API endpoints
  - Frontend: Vue 3, Firebase Auth, Admin UI
  - Android: Kotlin, MVVM, Navigation, Performance
  - Operations: Runbooks, workflows, debugging

### 5. Requirements & Planning
- **[acceptance/criteria.md](acceptance/criteria.md)** - Acceptance criteria with traceability
- **[backlog/product-backlog.csv](backlog/product-backlog.csv)** - Story backlog with estimates
- **[backlog/ac-traceability.csv](backlog/ac-traceability.csv)** - AC to story mapping

### 6. Testing & Quality
- **[testing/test-strategy.md](testing/test-strategy.md)** - Comprehensive test strategy
- **[testing/android-macrobenchmark.md](testing/android-macrobenchmark.md)** - Android performance benchmarking
- **[testing/playlist-findings.md](testing/playlist-findings.md)** - Performance profiling notes

### 7. Security & Risk
- **[security/threat-model.md](security/threat-model.md)** - Security threats, mitigations
- **[risk-register.md](risk-register.md)** - Project risks with likelihood/impact

### 8. AI Assistance
- **[prompt/complete_system_prompt.md](prompt/complete_system_prompt.md)** - AI agent system prompt for development

---

## Documentation Structure

```
docs/
├── README.md                    # This file - documentation index
├── PROJECT_STATUS.md            # Current status & completed work
├── PLATFORM_GUIDES.md           # All platform setup & operations
│
├── roadmap/
│   └── roadmap.md               # Phased delivery plan (Phases 0-12)
│
├── architecture/
│   ├── solution-architecture.md # System architecture
│   └── diagrams/                # C4 diagrams
│
├── ux/
│   ├── design.md                # Design system & UI specs
│   └── mockups/                 # Screenshots & Figma exports
│
├── i18n/
│   └── strategy.md              # Internationalization strategy
│
├── prompt/
│   └── complete_system_prompt.md # AI agent system prompt
│
├── vision/
│   └── vision.md                # Product vision & metrics
│
├── acceptance/
│   └── criteria.md              # Acceptance criteria
│
├── backlog/
│   ├── product-backlog.csv      # Story backlog
│   └── ac-traceability.csv      # AC traceability
│
├── testing/
│   ├── test-strategy.md         # Test strategy
│   ├── android-macrobenchmark.md # Android performance
│   └── playlist-findings.md     # Performance notes
│
├── security/
│   └── threat-model.md          # Security threats
│
├── risk-register.md             # Risk register
│
└── api/                         # (Future: OpenAPI specs)
```

---

## Recent Changes (Oct 2025)

### Documentation Cleanup (Oct 5, 2025)
- **Consolidated** 12 status files into single `PROJECT_STATUS.md`
- **Merged** backend, frontend, android guides into `PLATFORM_GUIDES.md`
- **Removed** 8 redundant folders: status/, runbooks/, backend/, frontend/, android/, platform/, accessibility/, agents/
- **Result**: 5 core folders, ~20 essential files (down from 14+ folders, 50+ files)

### Phase 6 Complete (Oct 4-5, 2025)
- ✅ Backend integration for all tabs (ANDROID-020 through ANDROID-025)
- ✅ Fixed scroll and navigation issues
- ✅ All tabs connected to Spring Boot API via Retrofit
- 📍 **Ready for Phase 7**: Channel & Playlist Details

---

## Finding Information

| What You Need | Where to Look |
|---------------|---------------|
| **Current project status** | [PROJECT_STATUS.md](PROJECT_STATUS.md) |
| **Setup backend/frontend/android** | [PLATFORM_GUIDES.md](PLATFORM_GUIDES.md) |
| **Troubleshooting** | [PLATFORM_GUIDES.md](PLATFORM_GUIDES.md) - Troubleshooting section |
| **What's next** | [PROJECT_STATUS.md](PROJECT_STATUS.md) - Next Phase section |
| **Overall roadmap** | [roadmap/roadmap.md](roadmap/roadmap.md) |
| **Architecture decisions** | [architecture/solution-architecture.md](architecture/solution-architecture.md) |
| **Design system** | [ux/design.md](ux/design.md) |
| **API contracts** | [api/](api/) (Future: OpenAPI specs) |
| **Acceptance criteria** | [acceptance/criteria.md](acceptance/criteria.md) |
| **Story backlog** | [backlog/product-backlog.csv](backlog/product-backlog.csv) |
| **Test strategy** | [testing/test-strategy.md](testing/test-strategy.md) |
| **Security** | [security/threat-model.md](security/threat-model.md) |
| **AI development prompt** | [prompt/complete_system_prompt.md](prompt/complete_system_prompt.md) |

---

## Contributing to Documentation

### When to Update

1. **After completing a ticket/sprint**: Update `PROJECT_STATUS.md` with progress
2. **After architectural changes**: Update `architecture/solution-architecture.md`
3. **After adding features**: Update `PLATFORM_GUIDES.md` if setup/ops changed
4. **Before starting a phase**: Review `roadmap/roadmap.md` and update estimates
5. **After discovering risks**: Update `risk-register.md`

### Commit Message Format

```
DOCS: Brief description of doc changes

Detailed explanation:
- What was updated
- Why it was needed
- Related tickets (e.g., ANDROID-025)

🤖 Generated with [Claude Code](https://claude.com/claude-code)

Co-Authored-By: Claude <noreply@anthropic.com>
```

### Guidelines

- ✅ Keep docs **concise and actionable**
- ✅ Use **relative links** for navigation
- ✅ Include **code examples** where helpful
- ✅ Add **commit references** for traceability
- ✅ Update **PROJECT_STATUS.md** after every sprint/ticket
- ❌ Don't create new folders without reviewing structure
- ❌ Don't duplicate information across files
- ❌ Don't leave outdated status files

---

## Maintenance

### Quarterly Review
- Review and archive outdated sections in `PROJECT_STATUS.md`
- Update phase estimates in `roadmap/roadmap.md`
- Refresh `PLATFORM_GUIDES.md` with new troubleshooting items
- Check all links still work

### Before Each Phase
- Review acceptance criteria in `acceptance/criteria.md`
- Update backlog in `backlog/product-backlog.csv`
- Ensure `PROJECT_STATUS.md` reflects actual state

---

## Questions?

- **Product/Vision**: See [vision/vision.md](vision/vision.md)
- **Technical Setup**: See [PLATFORM_GUIDES.md](PLATFORM_GUIDES.md)
- **Current Status**: See [PROJECT_STATUS.md](PROJECT_STATUS.md)
- **Roadmap**: See [roadmap/roadmap.md](roadmap/roadmap.md)
