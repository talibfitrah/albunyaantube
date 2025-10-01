# Exclusions Endpoint Checklist

Use this checklist to confirm frontend and backend alignment for `/api/v1/exclusions`.

## API Contract
- [ ] Backend exposes `GET /api/v1/exclusions` with cursor pagination (`cursor`, `limit`) and optional `parentType`, `excludeType`, and `search` query params.
- [ ] `PATCH /api/v1/exclusions/{id}` persists reason updates, returning the updated exclusion payload.
- [ ] Schema responses match [`docs/data/json-schemas/exclusion-page.json`](../data/json-schemas/exclusion-page.json) and [`../data/json-schemas/exclusion-update-request.json`](../data/json-schemas/exclusion-update-request.json).
- [ ] Audit events emitted on create/update/delete operations include exclusion identifiers and actor metadata.

## QA / Testing
- [ ] Contract tests (Vitest) cover GET, POST, PATCH, DELETE request shapes (`frontend/tests/exclusionsService.spec.ts`).
- [ ] Playwright e2e (`frontend/tests/e2e/exclusions.e2e.spec.ts`) validates search, edit, deletion, creation, and axe accessibility scan via mocked responses.
- [ ] Backend integration tests verify RBAC enforcement and audit log persistence for manual exclusions.

## Coordination Notes
- Backend owners: ______________________________
- Frontend owners: ______________________________
- Meeting / Slack thread link: __________________
- Pending follow-ups:
  - [ ] Cursor format & stability agreement.
  - [ ] Error payload localization strategy.
  - [ ] Bulk include/exclude batching contract (if required in later phases).
