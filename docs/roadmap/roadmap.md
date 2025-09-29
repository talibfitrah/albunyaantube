# Phased Roadmap

This roadmap aligns with the phased deliverables outlined in the program brief. Each phase lists objectives, key artifacts (with repo links), and exit criteria.

| Phase | Objective | Key Deliverables | Exit Criteria |
| --- | --- | --- | --- |
| 0 — Discovery & Contracts | Establish product vision, architecture outline, UX contract, localization approach. | - [`docs/vision/vision.md`](../vision/vision.md) <br> - [`docs/api/openapi-draft.yaml`](../api/openapi-draft.yaml) <br> - [`docs/architecture/solution-architecture.md`](../architecture/solution-architecture.md) <br> - [`docs/ux/ui-spec.md`](../ux/ui-spec.md) <br> - [`docs/i18n/strategy.md`](../i18n/strategy.md) | Stakeholder sign-off on scope and contracts. |
| 1 — Backend Foundations | Plan authentication, RBAC, schema baseline, migrations, seeds. | - Auth/RBAC implementation plan (see [`docs/architecture/solution-architecture.md`](../architecture/solution-architecture.md#security-architecture)) <br> - Testing approach (see [`docs/testing/test-strategy.md`](../testing/test-strategy.md)) | Engineering backlog for Auth/Categories ready. |
| 2 — Registry & Moderation | Finalize domain model, pagination, caching, moderation flows. | - Data model schematics (see [`docs/data/json-schemas`](../data/json-schemas)) <br> - Moderation sequence diagram (see [`docs/architecture/diagrams/moderation-sequence.md`](../architecture/diagrams/moderation-sequence.md)) | Tickets with acceptance criteria approved. |
| 3 — Admin UI MVP | IA, routing, state, localization wiring for admin console. | - Admin UI flows (see [`docs/ux/ui-spec.md`](../ux/ui-spec.md#admin-flows)) <br> - Task list in [`docs/backlog/product-backlog.csv`](../backlog/product-backlog.csv) | MVP scope frozen and estimated. |
| 4 — Admin UI Complete | Add exclusions editor, user management, audit viewer, a11y polish. | - Updated backlog stories <br> - Accessibility guidelines (see [`docs/ux/ui-spec.md`](../ux/ui-spec.md#accessibility)) | Feature-complete admin console spec. |
| 5 — Android Skeleton | Navigation graph, onboarding, bottom nav, category sheet, locale switch. | - Android architecture plan (see [`docs/architecture/solution-architecture.md`](../architecture/solution-architecture.md#android-architecture)) <br> - Screen specs (see [`docs/ux/ui-spec.md`](../ux/ui-spec.md#android-screens)) | UI kit approved. |
| 6 — Lists & Home Rules | Paging, “3-latest” logic, error handling. | - Paging contract (see [`docs/api/openapi-draft.yaml`](../api/openapi-draft.yaml#components-schemas-CursorPagination)) <br> - Test cases (see [`docs/testing/test-strategy.md`](../testing/test-strategy.md#android-client)) | QA checklist ready. |
| 7 — Channel/Playlist Details | Tab behaviors, deep links, exclusions enforcement. | - Sequence diagrams (see [`docs/architecture/diagrams/channel-tabs-sequence.md`](../architecture/diagrams/channel-tabs-sequence.md)) | Test matrix in [`docs/testing/test-strategy.md`](../testing/test-strategy.md). |
| 8 — Player & Background Audio | ExoPlayer, PiP, captions, quality selector, background controls. | - Media session plan (see [`docs/architecture/solution-architecture.md`](../architecture/solution-architecture.md#playback-engine)) | Reliability scenarios documented. |
| 9 — Downloads & Offline | Queue, notifications, policy gating, storage quotas. | - Offline policy (see [`docs/security/threat-model.md`](../security/threat-model.md#policy-controls)) <br> - Storage strategy in [`docs/architecture/solution-architecture.md`](../architecture/solution-architecture.md#offline-downloads) | Storage & policy checklist complete. |
| 10 — Perf & Security Hardening | Caching, performance budgets, pen-test prep. | - Perf plan (see [`docs/architecture/solution-architecture.md`](../architecture/solution-architecture.md#performance)) <br> - Security checklist (see [`docs/security/threat-model.md`](../security/threat-model.md#controls)) | Sign-off by security & performance leads. |
| 11 — i18n & Accessibility Polish | Localization QA, bidi, numeral handling, TalkBack. | - Localization QA guide (see [`docs/i18n/strategy.md`](../i18n/strategy.md#qa-guidelines)) | Compliance report delivered. |
| 12 — Beta & Launch | Telemetry, crash reporting, rollout & rollback plan. | - Launch checklist (see [`docs/testing/test-strategy.md`](../testing/test-strategy.md#release-management)) | Go-live approval. |

## Dependencies & Milestones
- Phase 1 depends on Phase 0 sign-off.
- Android Phases (5–9) depend on backend API stability from Phases 1–2.
- Localization polish (Phase 11) requires UI text freeze.
- Launch (Phase 12) depends on prior phase exit criteria being met.

See [`docs/backlog/product-backlog.csv`](../backlog/product-backlog.csv) for story-level planning.
