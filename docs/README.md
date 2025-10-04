# Albunyaan Tube Documentation

## Quick Links

### Getting Started
- [Main README](../README.md) - Project overview and setup
- [Roadmap](roadmap/roadmap.md) - Development phases and timeline
- [Vision](vision/vision.md) - Product vision and goals

## Architecture & Design

### Architecture
- [Solution Architecture](architecture/solution-architecture.md) - System design and components
- [Architecture Diagrams](architecture/diagrams/) - Visual architecture documentation
  - [Context Diagram](architecture/diagrams/context.md)
  - [Container Diagram](architecture/diagrams/container.md)
  - [Backend Components](architecture/diagrams/backend-components.md)
  - [Moderation Sequence](architecture/diagrams/moderation-sequence.md)
  - [Channel Tabs Sequence](architecture/diagrams/channel-tabs-sequence.md)

### UX & Design
- [Design Guidelines](ux/design.md) - UI/UX design principles
- [Mockups](ux/mockups/) - Design mockups and wireframes

## Development

### Backend
- [Firebase Setup](backend/FIREBASE_SETUP.md) - Firebase project configuration
- [Backend Integration Status](status/backend-integration.md) - Current backend status

### Frontend (Admin Dashboard)
- [Frontend README](frontend/README.md) - Admin dashboard setup
- [Dashboard Metrics Plan](frontend/dashboard-metrics-plan.md) - Metrics implementation

### Android
- [Android README](android/README.md) - Android app setup and development
- [Release Checklist](android/RELEASE_CHECKLIST.md) - Pre-release validation
- [Release Signing](android/RELEASE_SIGNING.md) - App signing configuration
- [Play Store](android/play-store/) - Play Store assets and guidelines
  - [Description](android/play-store/description.md)
  - [Release Notes](android/play-store/release-notes.md)
  - [Screenshots Guide](android/play-store/SCREENSHOTS_GUIDE.md)

## Testing & Quality

### Testing
- [Test Strategy](testing/test-strategy.md) - Overall testing approach
- [Android Macrobenchmark](testing/android-macrobenchmark.md) - Performance testing
- [Playlist Findings](testing/playlist-findings.md) - API performance findings

### Accessibility
- [WCAG AA Audit](accessibility/wcag-aa-audit.md) - Accessibility compliance
- [RTL Polish Audit](accessibility/rtl-polish-audit.md) - RTL layout review

### Security
- [Threat Model](security/threat-model.md) - Security analysis and mitigations

## Status & Progress

### Current Status
- [Backend Integration](status/backend-integration.md) - Latest backend status (✅ Complete)
- [Production Ready](status/PRODUCTION_READY.md) - Production release summary
- [RTL Fixes](status/RTL_FIXES.md) - RTL layout fixes
- [Architecture Alignment](status/ARCHITECTURE_ALIGNMENT.md) - Architecture cleanup

### Firebase Migration (Historical)
- [Firebase Migration Summary](status/FIREBASE_MIGRATION_SUMMARY.md)
- [Firebase Migration Complete](status/FIREBASE_MIGRATION_COMPLETE.md)
- [Firebase Integration Status](status/FIREBASE_INTEGRATION_STATUS.md)
- [Enterprise Firestore Limitation](ENTERPRISE_FIRESTORE_LIMITATION.md)

## Operations

### Platform
- [Docker Compose](platform/docker-compose.md) - Local development setup

### Runbooks
- [Admin Login](runbooks/admin-login.md) - Admin authentication guide
- [Admin Onboarding](runbooks/admin-onboarding.md) - Admin onboarding process
- [Agent Ticket Prompt](runbooks/agent-ticket-prompt.md) - Development workflow
- [Backend Search Alignment](runbooks/backend-search-alignment.md) - Search implementation
- [Exclusions Endpoint Checklist](runbooks/exclusions-endpoint-checklist.md) - API checklist
- [Moderator Search Walkthrough](runbooks/moderator-search-walkthrough.md) - Moderation workflow
- [Playlist Hydration Performance](runbooks/perf-playlist-hydration.md) - Performance tuning
- [Roadmap Sync](runbooks/roadmap-sync.md) - Roadmap management

## Planning

### Requirements
- [Acceptance Criteria](acceptance/criteria.md) - Feature acceptance criteria
- [Risk Register](risk-register.md) - Project risks and mitigations

### Internationalization
- [i18n Strategy](i18n/strategy.md) - Internationalization approach

### Prompts & Templates
- [Complete System Prompt](prompt/complete_system_prompt.md) - AI assistant context

## Documentation Structure

```
docs/
├── README.md (this file)
├── acceptance/          # Acceptance criteria
├── accessibility/       # Accessibility audits
├── android/            # Android app documentation
├── architecture/       # System architecture
├── backend/            # Backend documentation
├── frontend/           # Frontend documentation
├── i18n/              # Internationalization
├── platform/          # Infrastructure & deployment
├── prompt/            # AI prompts and templates
├── roadmap/           # Project roadmap
├── runbooks/          # Operational guides
├── security/          # Security documentation
├── status/            # Status updates (historical)
├── testing/           # Testing documentation
├── ux/                # UX/UI design
└── vision/            # Product vision
```

## Contributing

When adding new documentation:
1. Place it in the appropriate category folder
2. Update this index
3. Use clear, descriptive filenames
4. Include a brief summary at the top of each document
5. Link to related documents

## Archive Policy

Historical status documents are kept in `status/` for reference. Active documentation is updated in place.
