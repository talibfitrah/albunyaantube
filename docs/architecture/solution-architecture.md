# Solution Architecture Overview

This document summarizes the end-to-end architecture for Albunyaan Tube. Detailed diagrams reside in [`docs/architecture/diagrams`](diagrams). API contracts are defined in [`../api/openapi-draft.yaml`](../api/openapi-draft.yaml). Security considerations are elaborated in [`../security/threat-model.md`](../security/threat-model.md).

## C4 Summary
- **Context**: See [`diagrams/context.md`](diagrams/context.md) for relationships among actors (Android client, Admin UI, Spring Boot backend, PostgreSQL, Redis, external NewPipeExtractor and YouTube).
- **Container**: [`diagrams/container.md`](diagrams/container.md) describes deployment units (Android app, Admin SPA, Backend service, Databases, CDN).
- **Component**: [`diagrams/backend-components.md`](diagrams/backend-components.md) details backend layers (API, Service, Repository, Integration).
- **Sequence**: Interaction flows for moderation approvals in [`diagrams/moderation-sequence.md`](diagrams/moderation-sequence.md) and channel tab retrieval in [`diagrams/channel-tabs-sequence.md`](diagrams/channel-tabs-sequence.md).

## Backend Architecture
- **Language/Framework**: Java 17, Spring Boot 3 with Web, Security, Validation, Data JPA modules.
- **Persistence**: PostgreSQL 15 (primary data store) managed via Flyway migrations; Redis for caching, rate limiting, JWT blacklist. Channel/Playlist/Video tables store Albunyaan-controlled data only (YouTube IDs, category links, inclusion/exclusion state, optional localized overrides) and maintain excluded item ID lists instead of mirroring remote metadata blobs.
- **Integration**: NewPipeExtractor ships with the Android client to resolve metadata and stream URLs at runtime for allow-listed IDs. The backend uses the YouTube Data API solely for admin search previews and never persists remote metadata.
- **API Style**: REST with JSON; localized responses based on `Accept-Language` with fallback to English. Metadata fields returned by read APIs combine Albunyaan overrides with runtime extractor payloads provided by clients when available.
- **Caching Strategy**: Redis caches popular list queries keyed by locale + category + cursor. Time-to-live 5 minutes with cache stampede protection (locking). Downloadable assets served via signed URLs; consider CDN for thumbnails.
- **Security Architecture**: JWT-based auth with rotation and blacklist (see [`../security/threat-model.md`](../security/threat-model.md#controls)). RBAC roles ADMIN and MODERATOR enforced through Spring Security method-level annotations. Rate limiting via Redis sliding window.
- **Observability**: Structured logging (JSON), distributed tracing with OpenTelemetry, metrics exported to Prometheus. Error taxonomy defined in [`../testing/test-strategy.md`](../testing/test-strategy.md#error-taxonomy).

## Domain Model
Entities defined in [`../data/json-schemas`](../data/json-schemas):
- User, Role (RBAC)
- Category (i18n map, slug, optional subcategories array with localized names)
- Channel, Playlist, Video (store YouTube IDs + Albunyaan overrides, with Channel tracking `excludedVideoIds`/`excludedPlaylistIds` and Playlist tracking `excludedVideoIds`)
- ModerationItem, Exclusion, AuditLog
These schemas inform JPA entities and API payloads.

## Android Architecture
- **Layers**: Presentation (Jetpack ViewModel), Domain (use cases), Data (Repository → Retrofit/OkHttp for backend, Room for offline downloads metadata).
- **Metadata Pipeline**: Repositories use NewPipeExtractor to hydrate channel/playlist/video metadata at runtime, merging Albunyaan overrides from backend responses before caching locally.
- **Navigation (Phase 5 skeleton)**: Single-activity pattern with Navigation Component. `MainActivity` hosts `NavHostFragment` for `app_nav_graph.xml`, driving Splash → Onboarding → Main shell flow. Bottom navigation tabs (Home, Channels, Playlists, Videos) retain per-tab back stacks via `NavController` state saving. Deep links map to channel/playlist/video destinations and bypass onboarding once the DataStore flag `onboarding_completed` is set.
- **Paging & Caching (Phase 6 foundation)**: `CursorPagingSource` bridges backend cursor pagination with Paging 3. `ContentPagingRepository` emits PagingData streams for Home/Channels/Playlists/Videos, coordinating Room-backed cache (RemoteMediator) with network fetches. Global filters (category/search) invalidate the pager via `Pager.refresh`. Cached pages expire using a 10-minute timeout aligning with backend Redis TTL.
- **Filter State (Phase 6)**: `FilterManager` holds shared filter state (category, length, date, sort) as a `StateFlow`. UI chips/dropdowns update it, triggering repository refresh; DataStore will persist selections for session continuity. Filter metadata mirrors admin registry semantics to keep cross-platform parity.
- **List States & Metrics**: List views use `ListStateView` (error/empty/skeleton container) and `ListFooterView` (pagination + freshness). Repository layer emits cache freshness timestamps used by footer; analytics track retry taps and offline fallback activations.
- **Playback Engine**: ExoPlayer integrated with MediaSession + Notification for background playback. Audio-only toggle selects audio stream variant from backend-provided manifest. PiP supported via Android 12 APIs. See Phase 8 plan in [`../testing/test-strategy.md`](../testing/test-strategy.md#player-reliability).
- **Downloads**: Foreground service with WorkManager orchestrating downloads; store files in app-private storage with quotas (see [`../security/threat-model.md`](../security/threat-model.md#policy-controls)).
- **Localization**: Locale switcher per [`../i18n/strategy.md`](../i18n/strategy.md#android-implementation); full RTL mirroring.
- **Locale Switcher Skeleton**: `LocaleManager` (DataStore-backed) and locale settings fragment provide the in-app language chooser. Applying locales uses `AppCompatDelegate.setApplicationLocales` to avoid process restarts and ensures numeral shaping aligns with the chosen locale.

## Admin Frontend Architecture
- Vue 3 + Vite + TypeScript, Pinia for state management, Vue Router for routing, vue-i18n for localization.
- Modules: Auth shell, Registry management, Moderation queue, Users, Audit logs.
- API client generated from OpenAPI via `openapi-typescript`. State stores align with pagination contract (cursor-based).
- Search & Import workspace consumes blended `/admin/search` response, renders tri-state include/exclude toggles, and batches mutations to backend bulk endpoints.
- Registry filter store centralizes search query, category, video length/date/sort preferences and fans updates out to all tabs with debounced API calls.
- Security: JWT stored in HTTP-only cookies; CSRF tokens for state-changing operations.

### Dashboard Metrics
- `/admin/dashboard` aggregates counts for pending moderation proposals, total allow-listed categories, and active moderators.
- Snapshot produced via SQL CTE using moderation proposal status + age filters (<48h SLA) and `users` table role flags; results cached in Redis (`admin:dashboard:<timeframe>`) for 60s with background refresh to keep load low.
- Only `ADMIN` and `MODERATOR` roles may call the endpoint; backend enforces RBAC via method-level annotations and returns `403` on violation.
- Response includes comparison against previous timeframe to power trend arrows; backend computes previous window in the same query to keep data consistent.
- Observability: emit `admin.dashboard.generated` metric with latency + cache hit label, and log structured event carrying `timeframe`, counts, and `traceId` for auditing.
- Failure plan: fall back to stale Redis value when database unavailable (flagged via `warnings[]` array) and surface toast in UI; stale data older than 15 minutes invalidates cache and triggers error.

## Data Flow
1. Admin allow-lists content by storing Channel/Playlist/Video entries with categories and optional localized overrides.
2. The admin console performs live previews by calling `/admin/search`, while persisted records retain only YouTube IDs and Albunyaan overrides; no remote metadata is stored server-side.
3. Android client requests lists with cursor pagination; backend enforces category filters and 3-latest rule on home while returning only IDs and policy fields. The client resolves full metadata on-device via NewPipeExtractor, merging overrides when provided.
4. Playback requests return signed stream manifests referencing YouTube sources; download requests ensure policy compliance without persisting media.
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
