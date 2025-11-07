# Albunyaan Tube Documentation

> Comprehensive documentation for the Albunyaan Tube Islamic content platform

**Last Updated**: November 7, 2025
**Current Status**: ~60% Complete - Backend & Frontend operational, Android ready for testing

---

## 📋 Quick Start

New to the project? Start here:

1. **[CLAUDE.md](../CLAUDE.md)** - Complete developer guide with build commands, architecture overview, and workflow
2. **[PROJECT_STATUS.md](PROJECT_STATUS.md)** - Current completion status, blockers, and next steps
3. **[DEVELOPMENT_GUIDE.md](DEVELOPMENT_GUIDE.md)** - Detailed setup instructions and troubleshooting

---

## 📂 Documentation Structure

### Essential Documents

| Document | Purpose | Audience |
|----------|---------|----------|
| **[PROJECT_STATUS.md](PROJECT_STATUS.md)** | Current phase, completion %, blockers | All |
| **[DEVELOPMENT_GUIDE.md](DEVELOPMENT_GUIDE.md)** | Setup, configuration, troubleshooting | Developers |
| **[CATEGORY_MANAGEMENT.md](CATEGORY_MANAGEMENT.md)** | Category system overview | Admins, Developers |
| **[REAL_YOUTUBE_DATA_INTEGRATION.md](REAL_YOUTUBE_DATA_INTEGRATION.md)** | YouTube data integration guide | Backend Developers |

### Architecture & Design

| Folder/File | Contents |
|-------------|----------|
| **[architecture/](architecture/)** | System architecture, C4 diagrams, design decisions |
| ├─ **[solution-architecture.md](architecture/solution-architecture.md)** | Overall system design, tech stack, patterns |
| └─ **[diagrams/](architecture/diagrams/)** | Context, container, component, sequence diagrams |
| **[ux/](ux/)** | Design system, mockups, design tokens |
| ├─ **[design.md](ux/design.md)** | UI specifications, component library |
| └─ **[mockups/](ux/mockups/)** | Screenshots and Figma exports |

### Platform-Specific Guides

| Folder/File | Contents |
|-------------|----------|
| **[android/](android/)** | Android app configuration and testing |
| ├─ **[BACKEND_CONFIGURATION.md](android/BACKEND_CONFIGURATION.md)** | Connecting app to backend (local/VPS/production) |
| ├─ **[CONNECTIVITY_TROUBLESHOOTING.md](android/CONNECTIVITY_TROUBLESHOOTING.md)** | Network and API troubleshooting |
| ├─ **[PLAYER_DEVELOPMENT.md](android/PLAYER_DEVELOPMENT.md)** | Video player implementation status |
| └─ **[TESTING_GUIDE.md](android/TESTING_GUIDE.md)** | Android testing procedures |
| **[deployment/](deployment/)** | Deployment guides and checklists |
| ├─ **[VPS_DEPLOYMENT.md](deployment/VPS_DEPLOYMENT.md)** | VPS deployment instructions |
| └─ **[DEPLOYMENT_CHECKLIST.md](deployment/DEPLOYMENT_CHECKLIST.md)** | Pre-deployment verification |

### Features & Testing

| Folder/File | Contents |
|-------------|----------|
| **[features/](features/)** | Feature-specific documentation |
| └─ **[EXCLUSIONS_AND_CONTENT_LIBRARY.md](features/EXCLUSIONS_AND_CONTENT_LIBRARY.md)** | Exclusions & Content Library features |
| **[testing/](testing/)** | Test strategies and verification |
| ├─ **[test-strategy.md](testing/test-strategy.md)** | Overall testing approach |
| ├─ **[DATA_VERIFICATION.md](testing/DATA_VERIFICATION.md)** | Backend data verification & seeding results |
| ├─ **[PHASE2_TESTING_CHECKLIST.md](testing/PHASE2_TESTING_CHECKLIST.md)** | Phase 2 testing procedures |
| └─ **[android-macrobenchmark.md](testing/android-macrobenchmark.md)** | Android performance benchmarking |

### Planning & Requirements

| Folder/File | Contents |
|-------------|----------|
| **[vision/](vision/)** | Product vision, mission, success metrics |
| **[roadmap/](roadmap/)** | Phased delivery plan (Phases 0-12) |
| **[acceptance/](acceptance/)** | Acceptance criteria with traceability |
| **[backlog/](backlog/)** | Product backlog CSVs (stories, estimates) |
| **[i18n/](i18n/)** | Internationalization strategy (en/ar/nl, RTL) |
| **[security/](security/)** | Threat model, security considerations |
| **[risk-register.md](risk-register.md)** | Project risks with likelihood/impact |

### API Documentation

| Folder/File | Contents |
|-------------|----------|
| **[api/](api/)** | API specifications |
| └─ **[openapi-draft.yaml](api/openapi-draft.yaml)** | OpenAPI specification (draft) |

### Archived Documentation

| Folder | Contents |
|--------|----------|
| **[archived/](archived/)** | Historical documents (not actively maintained) |
| ├─ **[sessions/](archived/sessions/)** | Development session notes |
| ├─ **[planning/](archived/planning/)** | Historical planning documents |
| ├─ **[android-player/](archived/android-player/)** | Old player development work logs |
| ├─ **[performance-profiling/](archived/performance-profiling/)** | Historical performance notes |
| └─ **[system-prompts/](archived/system-prompts/)** | AI agent system prompts |

---

## 🔍 Finding Information

### Common Questions

| What You Need | Where to Look |
|---------------|---------------|
| **Setup instructions** | [DEVELOPMENT_GUIDE.md](DEVELOPMENT_GUIDE.md) |
| **Build commands** | [CLAUDE.md](../CLAUDE.md) - Part 1 |
| **Project architecture** | [architecture/solution-architecture.md](architecture/solution-architecture.md) |
| **Current status & blockers** | [PROJECT_STATUS.md](PROJECT_STATUS.md) |
| **What's next** | [PROJECT_STATUS.md](PROJECT_STATUS.md) - Next Steps |
| **Roadmap & phases** | [roadmap/roadmap.md](roadmap/roadmap.md) |
| **Android app configuration** | [android/BACKEND_CONFIGURATION.md](android/BACKEND_CONFIGURATION.md) |
| **Network troubleshooting** | [android/CONNECTIVITY_TROUBLESHOOTING.md](android/CONNECTIVITY_TROUBLESHOOTING.md) |
| **API contracts** | [api/openapi-draft.yaml](api/openapi-draft.yaml) |
| **Test strategy** | [testing/test-strategy.md](testing/test-strategy.md) |
| **Security** | [security/threat-model.md](security/threat-model.md) |
| **Acceptance criteria** | [acceptance/criteria.md](acceptance/criteria.md) |
| **Design system** | [ux/design.md](ux/design.md) |

### By Role

**New Developer**:
1. Read [CLAUDE.md](../CLAUDE.md) for complete overview
2. Follow [DEVELOPMENT_GUIDE.md](DEVELOPMENT_GUIDE.md) for setup
3. Review [architecture/solution-architecture.md](architecture/solution-architecture.md) for system design

**Backend Developer**:
- [CLAUDE.md](../CLAUDE.md) - Part 1 (Backend commands)
- [REAL_YOUTUBE_DATA_INTEGRATION.md](REAL_YOUTUBE_DATA_INTEGRATION.md)
- [api/openapi-draft.yaml](api/openapi-draft.yaml)

**Frontend Developer**:
- [CLAUDE.md](../CLAUDE.md) - Part 1 (Frontend commands)
- [ux/design.md](ux/design.md)
- [i18n/strategy.md](i18n/strategy.md)

**Android Developer**:
- [CLAUDE.md](../CLAUDE.md) - Part 1 (Android commands)
- [android/BACKEND_CONFIGURATION.md](android/BACKEND_CONFIGURATION.md)
- [android/TESTING_GUIDE.md](android/TESTING_GUIDE.md)
- [android/PLAYER_DEVELOPMENT.md](android/PLAYER_DEVELOPMENT.md)

**Product Owner**:
- [PROJECT_STATUS.md](PROJECT_STATUS.md)
- [roadmap/roadmap.md](roadmap/roadmap.md)
- [vision/vision.md](vision/vision.md)

---

## 📝 Contributing to Documentation

### When to Update

1. **After completing a feature**: Update PROJECT_STATUS.md with progress
2. **After architectural changes**: Update architecture/solution-architecture.md
3. **After adding setup steps**: Update DEVELOPMENT_GUIDE.md or platform-specific guides
4. **Before starting a phase**: Review roadmap/roadmap.md and update estimates
5. **After discovering issues**: Update relevant troubleshooting sections

### Guidelines

✅ **DO**:
- Keep docs **concise and actionable**
- Use **relative links** for navigation between docs
- Include **code examples** where helpful
- Add **date stamps** to status documents
- Update **PROJECT_STATUS.md** after every milestone

❌ **DON'T**:
- Create new top-level folders without discussion
- Duplicate information across multiple files
- Leave outdated status information
- Create session notes in main docs (use archived/)
- Mix planning docs with current operational docs

### Commit Message Format

```
[DOCS]: Brief description of doc changes

- What was updated
- Why it was needed
- Related work (if applicable)

🤖 Generated with [Claude Code](https://claude.com/claude-code)

Co-Authored-By: Claude <noreply@anthropic.com>
```

---

## 🧹 Recent Cleanup (November 7, 2025)

### Documents Removed
- ❌ `TRUE_PROJECT_STATUS.md` - Outdated duplicate (superseded by PROJECT_STATUS.md)
- ❌ `PARALLEL_WORK_PROMPTS.md` - Misleading "production ready" claims

### Documents Archived
- 📁 Session notes → `archived/sessions/`
- 📁 Planning documents → `archived/planning/`
- 📁 Android player work logs → `archived/android-player/`
- 📁 Performance profiling notes → `archived/performance-profiling/`
- 📁 System prompts → `archived/system-prompts/`

### Documents Consolidated
- ✅ VPS configuration docs → `android/BACKEND_CONFIGURATION.md`
- ✅ Testing verification docs → `testing/DATA_VERIFICATION.md`
- ✅ Player development docs → `android/PLAYER_DEVELOPMENT.md`

### Result
- **Before**: 49 files across 18+ directories
- **After**: 30 active files in 22 directories (with clean archived/ structure)
- **Reduction**: ~40% fewer active docs, much clearer organization

---

## 🔧 Maintenance

### Monthly Review
- [ ] Update PROJECT_STATUS.md with current completion %
- [ ] Review and archive outdated sections
- [ ] Update roadmap estimates if needed
- [ ] Check all internal links still work

### Before Each Phase
- [ ] Review acceptance criteria in acceptance/criteria.md
- [ ] Update backlog in backlog/product-backlog.csv
- [ ] Ensure PROJECT_STATUS.md reflects actual state
- [ ] Update relevant troubleshooting guides

### Archiving Guidelines

Move documents to `archived/` when:
- They're session notes from completed work
- They're planning documents for completed phases
- They're work logs no longer actively referenced
- They're historical performance/profiling notes

**DO NOT** archive:
- Core architectural documentation
- Active troubleshooting guides
- Current status documents
- Essential setup/configuration guides

---

## ❓ Questions?

- **Setup Issues**: Check [DEVELOPMENT_GUIDE.md](DEVELOPMENT_GUIDE.md) troubleshooting section
- **Architecture Questions**: See [architecture/solution-architecture.md](architecture/solution-architecture.md)
- **Current Status**: See [PROJECT_STATUS.md](PROJECT_STATUS.md)
- **Feature Documentation**: Check [features/](features/) folder

---

**Last Cleanup**: November 7, 2025
**Next Review**: December 7, 2025
