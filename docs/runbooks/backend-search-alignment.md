# Backend Alignment — Search & Exclusion API

## Purpose
Prepare engineering consensus on the blended `/admin/search` contract, include/exclude batching semantics, and persistence expectations before Phase 3 implementation kicks off.

## Pre-Reads
- [`docs/api/openapi-draft.yaml`](../api/openapi-draft.yaml) — `/admin/search`, `/channels`, `/playlists`, `/exclusions` endpoints.
- [`docs/data/json-schemas`](../data/json-schemas) — `admin-search-*`, `channel-detail.json`, `playlist-detail.json`.
- [`docs/ux/ui-spec.md`](../ux/ui-spec.md#search--import-workspace) — admin Search & Import UX.
- [`docs/architecture/solution-architecture.md`](../architecture/solution-architecture.md) — metadata + exclusion storage model.

## Attendees
- Backend lead (facilitator)
- Admin frontend lead
- Moderation product owner
- Principal engineer (scribe)
- QA representative

## Agenda (45 min)
1. **Problem Statement (5 min)** — Recap blended search requirements and exclusion persistence.
2. **Contract Review (15 min)**
   - Response shape (`includeState`, `excludedItemCounts`).
   - Bulk mutation expectations (batch include/exclude endpoint sketch).
   - Error taxonomy + rate limiting.
3. **Persistence & Transactions (10 min)**
   - Ensure channel/playlists store only YouTube IDs and excluded child IDs.
   - Discuss transaction boundaries for bulk updates and audit logging.
4. **Open Questions (10 min)**
   - Debounce/search throttling limits.
   - Moderator vs admin permissions for batching.
   - Pagination / caching strategies.
5. **Decisions & Next Steps (5 min)** — Assign action items.

## Required Decisions
- Final path/payload for bulk include/exclude mutations (single endpoint vs separate).
- Audit log fields for batch operations.
- Validation rules (max batch size, conflicts when items already excluded).
- Search throttling parameters and Redis key design.

## Action Items Template
| Owner | Action | Due |
| --- | --- | --- |
| Backend lead | Draft bulk mutation OpenAPI spec | YYYY-MM-DD |
| Principal engineer | Update architecture doc with transaction plan | YYYY-MM-DD |
| QA rep | Outline integration test coverage for include/exclude flows | YYYY-MM-DD |

## Notes
Capture meeting minutes below once alignment occurs.

---

- _Date:_
- _Summary:_
- _Key Decisions:_
- _Follow-ups:_
