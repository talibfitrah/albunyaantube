# P1-T4 Completion Report: Standardize Pagination DTOs Across API

**Task ID**: P1-T4
**Completed**: 2025-11-17
**Complexity**: Medium
**Status**: Complete

---

## Summary
Standardized cursor-based pagination DTOs across the API by adding `hasNext` to `PageInfo`, aligning `NextUpDto` with `CursorPageDto` pattern, updating OpenAPI schemas with explicit response types, and ensuring frontend services consume the standardized format.

## What Was Implemented

### Backend Changes
- **CursorPageDto.PageInfo** ([backend/src/main/java/com/albunyaan/tube/dto/CursorPageDto.java](backend/src/main/java/com/albunyaan/tube/dto/CursorPageDto.java))
  - Added `hasNext` boolean field computed from `nextCursor != null`
  - Updated setters to auto-compute `hasNext` when `nextCursor` changes
  - Wire format: `{ data: [], pageInfo: { nextCursor: string|null, hasNext: boolean } }`

- **NextUpDto** ([backend/src/main/java/com/albunyaan/tube/dto/NextUpDto.java](backend/src/main/java/com/albunyaan/tube/dto/NextUpDto.java))
  - Aligned with CursorPageDto pattern: renamed `items` to `data`, replaced `nextCursor` with `pageInfo`
  - Reuses `CursorPageDto.PageInfo` for consistency
  - Added `@JsonGetter("items")` and `@JsonGetter("nextCursor")` for backward-compatible serialization
  - Added `@JsonSetter` for backward-compatible deserialization
  - JSON response includes both old (`items`, `nextCursor`) and new (`data`, `pageInfo`) field names

### OpenAPI Schema Updates ([docs/architecture/api-specification.yaml](docs/architecture/api-specification.yaml))
- **PageInfo schema** (line 3161): Added required `hasNext` boolean field
- **/v1/content** (line 127): Updated to explicitly use `CursorPageDto` with `ContentItemDto` items
- **/admin/approvals/pending** (line 1629): Added response schema with `CursorPageDto<PendingApprovalDto>`
- **/player/next-up/{videoId}** (line 1719): Updated with `NextUpDto` response schema (pagination not yet implemented in controller)
- **NextUpDto schema** (line 3187): Updated to use `data` and `pageInfo` instead of `items` and `nextCursor`

### Frontend Changes
- **pagination.ts** ([frontend/src/types/pagination.ts](frontend/src/types/pagination.ts))
  - Made `cursor` and `limit` optional in `CursorPageInfo` (computed client-side)
  - Required fields: `nextCursor` and `hasNext`

- **approvalService.ts** ([frontend/src/services/approvalService.ts](frontend/src/services/approvalService.ts))
  - Updated to expect `CursorPage<PendingApprovalDto>` instead of raw array
  - Extracts data from `response.data.data`

- **Test updates** ([frontend/tests/approvalService.spec.ts](frontend/tests/approvalService.spec.ts))
  - Updated all mocks to return CursorPage format with `pageInfo`

## Validation
- **Backend tests**: Pass — 143 tests across all controllers and services
- **Frontend tests**: Pass — 150 tests passed, 4 skipped
- **OpenAPI generation**: Pass — TypeScript and Kotlin DTOs regenerated successfully

## Architectural Notes

### Standardized Wire Contract
All cursor-paginated endpoints now return:
```json
{
  "data": [...items...],
  "pageInfo": {
    "nextCursor": "string or null",
    "hasNext": true/false
  }
}
```

### Endpoint Classification
- **CursorPageDto endpoints** (standardized):
  - `/v1/content` - Public content feed
  - `/admin/approvals/pending` - Pending approvals
  - `/player/next-up/{videoId}` - Video recommendations

- **Other page wrappers** (documented variants):
  - `ContentLibraryResponse` - Admin content library (page/size based)
  - `SearchPageResponse` - YouTube search (pageToken based)

- **Intentionally unpaginated lists** (bounded datasets):
  - Category tree endpoints (`/v1/categories`, `/admin/categories/*`)
  - Admin user list (`/admin/users`)
  - Registry listings (`/admin/registry/*`)

### Backward Compatibility
- `NextUpDto` serializes both old and new field names via `@JsonGetter`:
  ```json
  {
    "data": [...],
    "pageInfo": { "nextCursor": null, "hasNext": false },
    "items": [...],       // deprecated, same as data
    "nextCursor": null    // deprecated, same as pageInfo.nextCursor
  }
  ```
- Deprecated `@JsonSetter` methods allow deserialization from old field names
- Frontend composable `useCursorPagination` continues to work with minimal changes

## Files Changed

| File | Change Type |
|------|-------------|
| `backend/src/main/java/com/albunyaan/tube/dto/CursorPageDto.java` | Modified |
| `backend/src/main/java/com/albunyaan/tube/dto/NextUpDto.java` | Modified |
| `frontend/src/types/pagination.ts` | Modified |
| `frontend/src/services/approvalService.ts` | Modified |
| `frontend/tests/approvalService.spec.ts` | Modified |
| `docs/architecture/api-specification.yaml` | Modified |

## Next Steps
- **P2-T2** (Real Firestore cursor pagination): Services now have a clear interface where `DocumentSnapshot`-based cursor logic can be plugged in without changing outward DTOs.
- Optional: Extend CursorPageDto usage to audit history endpoints and `/v1/search` for consistent infinite scroll semantics.
- Optional: UI smoke test pending approvals and next-up features to verify pagination works end-to-end.
