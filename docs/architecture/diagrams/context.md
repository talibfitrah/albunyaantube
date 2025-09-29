# C4 Level 1 — System Context

```mermaid
C4Context
    title Albunyaan Tube Context Diagram
    Person(muslimUser, "Android User", "Consumes halal video content")
    Person(adminUser, "Admin/Moderator", "Curates and moderates catalog")

    System_Boundary(atube, "Albunyaan Tube Platform") {
        System(backend, "Albunyaan Tube Backend", "Spring Boot service providing REST APIs")
        System(adminUi, "Admin Web Console", "Vue 3 SPA for registry management")
        System(androidApp, "Android Client", "Native Android app with ExoPlayer")
    }

    System_Ext(newPipe, "NewPipeExtractor", "Fetches stream metadata from YouTube")
    System_Ext(youtube, "YouTube CDN", "Delivers video/audio streams")
    System_Ext(push, "Firebase Cloud Messaging", "Delivers notifications")

    Rel(muslimUser, androidApp, "Uses", "HTTPS")
    Rel(adminUser, adminUi, "Manages content", "HTTPS + JWT")
    Rel(androidApp, backend, "Calls API", "REST/JSON")
    Rel(adminUi, backend, "Calls API", "REST/JSON + CSRF")
    Rel(backend, newPipe, "Retrieves metadata", "Library call")
    Rel(backend, youtube, "Provides signed stream URLs", "HTTPS")
    Rel(backend, push, "Sends download notifications", "HTTPS")
```
