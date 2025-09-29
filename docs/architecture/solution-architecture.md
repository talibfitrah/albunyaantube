# Solution Architecture Overview

This document summarizes the end-to-end architecture for Albunyaan Tube. Detailed diagrams reside in [`docs/architecture/diagrams`](diagrams). API contracts are defined in [`../api/openapi-draft.yaml`](../api/openapi-draft.yaml). Security considerations are elaborated in [`../security/threat-model.md`](../security/threat-model.md).

## C4 Summary
- **Context**: See [`diagrams/context.md`](diagrams/context.md) for relationships among actors (Android client, Admin UI, Spring Boot backend, PostgreSQL, Redis, external NewPipeExtractor and YouTube).
- **Container**: [`diagrams/container.md`](diagrams/container.md) describes deployment units (Android app, Admin SPA, Backend service, Databases, CDN).
- **Component**: [`diagrams/backend-components.md`](diagrams/backend-components.md) details backend layers (API, Service, Repository, Integration).
- **Sequence**: Interaction flows for moderation approvals in [`diagrams/moderation-sequence.md`](diagrams/moderation-sequence.md) and channel tab retrieval in [`diagrams/channel-tabs-sequence.md`](diagrams/channel-tabs-sequence.md).

## Backend Architecture
- **Language/Framework**: Java 17, Spring Boot 3 with Web, Security, Validation, Data JPA modules.
- **Persistence**: PostgreSQL 15 (primary data store) managed via Flyway migrations; Redis for caching, rate limiting, JWT blacklist.
- **Integration**: NewPipeExtractor library used via dedicated integration component to fetch metadata and stream URLs for allow-listed IDs.
- **API Style**: REST with JSON; localized responses based on `Accept-Language` with fallback to English.
- **Caching Strategy**: Redis caches popular list queries keyed by locale + category + cursor. Time-to-live 5 minutes with cache stampede protection (locking). Downloadable assets served via signed URLs; consider CDN for thumbnails.
- **Security Architecture**: JWT-based auth with rotation and blacklist (see [`../security/threat-model.md`](../security/threat-model.md#controls)). RBAC roles ADMIN and MODERATOR enforced through Spring Security method-level annotations. Rate limiting via Redis sliding window.
- **Observability**: Structured logging (JSON), distributed tracing with OpenTelemetry, metrics exported to Prometheus. Error taxonomy defined in [`../testing/test-strategy.md`](../testing/test-strategy.md#error-taxonomy).

## Domain Model
Entities defined in [`../data/json-schemas`](../data/json-schemas):
- User, Role (RBAC)
- Category (i18n map, slug)
- Channel, Playlist, Video (each referencing categories and i18n fields)
- ModerationItem, Exclusion, AuditLog
These schemas inform JPA entities and API payloads.

## Android Architecture
- **Layers**: Presentation (Jetpack ViewModel), Domain (use cases), Data (Repository → Retrofit/OkHttp for backend, Room for offline downloads metadata).
- **Navigation**: Single-activity pattern with Navigation Component; bottom nav (Home, Channels, Playlists, Videos). Onboarding flow controlled via DataStore preference.
- **Playback Engine**: ExoPlayer integrated with MediaSession + Notification for background playback. Audio-only toggle selects audio stream variant from backend-provided manifest. PiP supported via Android 12 APIs. See Phase 8 plan in [`../testing/test-strategy.md`](../testing/test-strategy.md#player-reliability).
- **Downloads**: Foreground service with WorkManager orchestrating downloads; store files in app-private storage with quotas (see [`../security/threat-model.md`](../security/threat-model.md#policy-controls)).
- **Localization**: Locale switcher per [`../i18n/strategy.md`](../i18n/strategy.md#android-implementation); full RTL mirroring.

## Admin Frontend Architecture
- Vue 3 + Vite + TypeScript, Pinia for state management, Vue Router for routing, vue-i18n for localization.
- Modules: Auth shell, Registry management, Moderation queue, Users, Audit logs.
- API client generated from OpenAPI via `openapi-typescript`. State stores align with pagination contract (cursor-based).
- Security: JWT stored in HTTP-only cookies; CSRF tokens for state-changing operations.

## Data Flow
1. Admin allow-lists content by storing Channel/Playlist/Video entries with categories.
2. Backend sync job (WorkManager or scheduled) enriches metadata via NewPipeExtractor when new IDs added.
3. Android client requests lists with cursor pagination; backend enforces category filters and 3-latest rule on home.
4. Playback requests return stream URLs and caption metadata (localized when available). Download requests ensure policy compliance.
5. Moderation proposals flow through dedicated endpoints with audit logging.

## Performance & Scaling
- Target 200 RPS sustained on backend with autoscaling (Kubernetes future). Redis reduces list query latency to <50ms.
- Database indexing on ytId, category relationships, publishedAt for sorting.
- Image assets served via CloudFront-like CDN to minimize cold start downloads.
- Android caches responses using Room + Paging 3 to minimize network calls.

## Deployment & Ops
- Docker Compose for local dev: services for backend, Postgres, Redis (see future `/ops/docker-compose.yml`).
- CI/CD: GitHub Actions building backend (Gradle), admin (Vite), Android (Gradle). Automated tests with Testcontainers for backend integration.
- Environments: dev, staging, prod with separate Redis namespaces and JWT signing keys stored in secrets manager.

## Traceability
- OpenAPI endpoints cross-referenced in [`../api/openapi-draft.yaml`](../api/openapi-draft.yaml).
- Security decisions documented in [`../security/threat-model.md`](../security/threat-model.md).
- Performance budgets and SLIs recorded in [`../testing/test-strategy.md`](../testing/test-strategy.md#performance-metrics).
