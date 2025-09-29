# Phase 1 — Backend Foundations Task Breakdown

Phase 1 focuses on creating the baseline services required to authenticate administrators and manage curated content metadata. The work is organized into the following streams to support iterative delivery and parallelism.

## 1. Platform & Tooling
- Scaffold Spring Boot project with Gradle 8+, Java 17, and shared build configuration.
- Configure dependency management for Web, Security, Data JPA, Validation, Flyway, Redis, and actuator modules.
- Generate Gradle wrapper for reproducible builds.
- Establish application configuration defaults (`application.yml`) aligned with local Postgres/Redis instances.
- Wire structured test logging and JUnit 5 defaults.

## 2. Authentication & RBAC Core
- Define domain model for `User`, `Role`, and `UserStatus` entities with auditing metadata.
- Create repositories for loading users and roles and initial service for admin provisioning.
- Configure password hashing via `BCryptPasswordEncoder` and stateless security filter chain (placeholder HTTP Basic until JWT layer is implemented in Phase 1.2).
- Seed mandatory RBAC data (ADMIN, MODERATOR roles) and bootstrap a default super-admin account via Flyway.

## 3. Persistence Baseline
- Introduce Flyway migrations for UUID/citext extensions, auth tables, and locale-aware category store (JSONB payload).
- Implement reusable JSON locale map converter to hydrate JPA entities.
- Seed canonical categories required by client applications (Quran, Seerah, Kids, Lectures) in three launch locales (en/ar/nl).
- Add auditing triggers for automatic `updated_at` maintenance.

## 4. Next Steps Checklist
- Implement JWT issuance/refresh endpoints backed by Redis blacklist.
- Add integration tests using Testcontainers for auth flows and Flyway migrations.
- Expose admin management API endpoints for creating/modifying moderators.
- Document local docker-compose setup for Postgres/Redis.
- Configure CI pipeline to run unit/integration test suite on PRs.

Each task maps back to backlog items BACK-01 (Auth & JWT rotation) and BACK-02 (Category seeding) to ensure traceability into delivery tracking.
