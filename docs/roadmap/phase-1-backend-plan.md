# Phase 1 — Backend Foundations Ticket Breakdown

Phase 1 converts the discovery artifacts into actionable backend tickets. Each ticket observes the format `Estimate → Goals → Propose diff → Tests → Implement → Reflect` so implementation teams inherit ready-to-execute guidance without additional scoping. Reference materials: `docs/architecture/solution-architecture.md`, `docs/api/openapi-draft.yaml`, `docs/testing/test-strategy.md`, and `docs/security/threat-model.md`.

## BACK-PLAN-01 — Platform & Tooling Baseline
**Estimate**: 2 days (1 backend engineer).

**Goals**
- Establish repeatable Spring Boot project scaffolding with Gradle 8, Java 17, and shared configuration.
- Document local developer workflow including Docker Compose for PostgreSQL/Redis.
- Ensure logging and testing defaults align with observability strategy.

**Propose diff**
- `backend/build.gradle.kts`, `settings.gradle.kts`, `gradle/libs.versions.toml` (new) to encapsulate dependency management.
- `backend/gradle/wrapper/*` (new) for reproducible builds.
- `backend/src/main/resources/application.yml`: populate environment-agnostic defaults matching design decisions.
- `docker-compose.yml`: add services for Postgres 15 + Redis with volumes/secrets placeholders.
- `docs/platform/docker-compose.md`: usage notes + troubleshooting.

**Tests**
- Run `./gradlew help` to confirm build bootstraps.
- Validate Docker Compose stack brings up databases with expected ports/credentials.
- Confirm logging pattern and timezone settings match observability requirements.

**Implement**
- Scaffold Gradle project structure, applying quality plugins (Spotless, Checkstyle) as per architecture doc.
- Configure Spring profiles (`local`, `test`) and align Flyway + Redis settings.
- Document run instructions + environment variables in repository README and platform doc.

**Reflect**
- Note outstanding tooling (e.g., linting CI job) and create backlog entries.
- Update risk register if local environment hurdles remain.

## BACK-PLAN-02 — Authentication & RBAC Blueprint
**Estimate**: 3 days (backend engineer + security reviewer).

**Goals**
- Define domain model for `User`, `Role`, and audit metadata.
- Specify JWT access/refresh flow with rotation + Redis blacklist.
- Plan bootstrap path for initial admin account and password policy.

**Propose diff**
- `backend/src/main/java/com/albunyaan/tube/security/*` (new packages) describing controllers, services, and filters.
- `docs/api/openapi-draft.yaml`: annotate `/auth/*` endpoints with request/response examples, rate limits, and RBAC notes.
- `docs/security/threat-model.md`: expand STRIDE mitigations for auth flows.
- `docs/data/json-schemas/auth-*.json`: finalize claim structure and error schema alignment.

**Tests**
- Define integration test cases using Testcontainers covering login, refresh rotation, logout blacklist.
- Security review checkpoint ensuring token TTL and blacklist semantics satisfy compliance.
- Validate password hashing and account lockout strategy via tabletop exercise.

**Implement**
- Draft UML or pseudo-ER diagrams for auth entities in architecture doc.
- Ticketize implementation subtasks (controller, service, repository, Flyway migrations) with acceptance IDs (AC-SEC-001).
- Coordinate with DevOps on secret management strategy for JWT signing keys.

**Reflect**
- Capture open questions (e.g., device binding, MFA roadmap) and log in backlog/risk register.
- Update acceptance criteria cross-links verifying coverage.

## BACK-PLAN-03 — Persistence & Seed Data Plan
**Estimate**: 2 days (backend engineer).

**Goals**
- Finalize Flyway migration order for auth, categories, and localization tables.
- Define converters for `{ locale: string }` maps and ensure `en` fallback mandatory.
- Specify seed data for categories and initial admin roles.

**Propose diff**
- `backend/src/main/resources/db/migration/V1__baseline.sql` (new) describing schema creation.
- `docs/data/json-schemas/category-*.json`: enforce `en` presence and ≥1 category relationships.
- `docs/architecture/solution-architecture.md`: document migration naming/rollback policy.
- `docs/backlog/product-backlog.csv`: update BACK-02 story details and acceptance IDs.

**Tests**
- Migration linting (Flyway dry run) to ensure repeatability.
- JSON schema validation for category payloads including `en` fallback.
- Manual review verifying seed categories align with UX filters and localization requirements.

**Implement**
- Compose migration outline covering extensions (uuid, citext), auth tables, categories, audit metadata.
- Enumerate seed categories in all launch locales with placeholder copy.
- Document migration execution + rollback plan in architecture doc for ops handoff.

**Reflect**
- Flag any outstanding localization assets needed before seed finalization.
- Update risk register if schema complexity or locale handling introduces new concerns.

## BACK-PLAN-04 — Quality Gates & CI Readiness
**Estimate**: 2 days (backend engineer + DevOps partner).

**Goals**
- Define automated test strategy (unit, integration, contract) for Phase 1 scope.
- Specify CI pipeline steps (build, test, static analysis) and artifact publishing.
- Establish local developer checklists before pushing code.

**Propose diff**
- `docs/testing/test-strategy.md`: expand backend section with concrete toolchain + coverage targets.
- `.github/workflows/backend-ci.yml` (new) outlining Gradle build, Testcontainers, dependency scan.
- `docs/acceptance/criteria.md`: map AC-BE-001/AC-SEC-001 to planned tests.
- `README.md`: add quickstart + CI badges placeholder.

**Tests**
- Dry-run CI pipeline locally or via draft PR to ensure Testcontainers works in headless mode.
- Verify dependency scan outputs actionable reports (OWASP/Dependency-Check).
- Confirm coverage thresholds align with engineering standards (e.g., 80% unit coverage for auth module).

**Implement**
- Author CI workflow YAML referencing Gradle tasks (`check`, `jacoco`), caching, and artifact uploads.
- Document local pre-commit steps (formatting, unit tests) to keep contributions consistent.
- Coordinate with security team on vulnerability scanning cadence.

**Reflect**
- Record CI gaps (e.g., code quality gates not yet configured) and add backlog items.
- Update risk register if pipeline stability risks remain (Testcontainers flakiness, secret handling).

## BACK-PLAN-05 — Backlog & Traceability Refresh
**Estimate**: 1 day (product + engineering pairing).

**Goals**
- Ensure all Phase 1 tasks have estimates, dependencies, and acceptance IDs in backlog.
- Align backlog wording with updated roadmap + design artifacts.
- Cross-link tickets with documentation for traceability.

**Propose diff**
- `docs/backlog/product-backlog.csv`: enrich BACK-* rows with clearer descriptions, dependencies, owners.
- `docs/acceptance/criteria.md`: ensure AC references reflect Phase 1 scope.
- `docs/risk-register.md`: update mitigation links to backlog items.

**Tests**
- Backlog review session with product + engineering leads verifying completeness.
- Spot check traceability matrix ensures each AC has at least one planned task/test.
- Confirm roadmap-to-backlog mapping covers exit criteria for Phase 1.

**Implement**
- Update CSV with effort, sequencing, and new ticket IDs if needed.
- Annotate acceptance criteria with ticket references (e.g., AC-SEC-001 ↔ BACK-01).
- Schedule backlog grooming review and capture minutes in docs if required.

**Reflect**
- Identify scope risks or dependencies on other teams and log follow-ups.
- Note improvements for future phase planning cadence.
