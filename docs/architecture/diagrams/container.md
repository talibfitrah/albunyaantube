# C4 Level 2 — Container Diagram

```mermaid
C4Container
    title Albunyaan Tube Container Diagram
    Person(muslimUser, "Android User")
    Person(adminUser, "Admin/Moderator")

    Container_Boundary(atube, "Albunyaan Tube Platform") {
        Container(androidApp, "Android App", "Android, Java", "UI, playback, downloads, offline cache")
        Container(adminSpa, "Admin SPA", "Vue 3, TypeScript", "Registry management UI")
        Container(backend, "Backend API", "Spring Boot", "REST APIs, business logic")
        ContainerDb(postgres, "PostgreSQL", "RDS", "Persistent storage for catalog")
        ContainerDb(redis, "Redis", "Managed Cache", "Caching, rate limit, JWT blacklist")
        Container(scheduler, "Worker / Scheduler", "Spring Boot", "Metadata refresh via NewPipeExtractor")
    }

    Container_Ext(newPipe, "NewPipeExtractor", "Java library", "YouTube metadata extraction")
    Container_Ext(youtubeCdn, "YouTube CDN", "HTTPS", "Video/audio delivery")
    Container_Ext(fcm, "FCM", "Google Cloud", "Notifications")

    Rel(muslimUser, androidApp, "Uses")
    Rel(adminUser, adminSpa, "Manages content")
    Rel(adminSpa, backend, "REST/JSON", "HTTPS + JWT + CSRF")
    Rel(androidApp, backend, "REST/JSON", "HTTPS + JWT")
    Rel(backend, postgres, "JDBC", "TLS")
    Rel(backend, redis, "Lettuce", "TLS")
    Rel(scheduler, newPipe, "Metadata fetch")
    Rel(backend, newPipe, "Ad-hoc fetch")
    Rel(backend, youtubeCdn, "Generate stream URLs")
    Rel(androidApp, youtubeCdn, "Stream playback")
    Rel(backend, fcm, "Send notifications")
```
