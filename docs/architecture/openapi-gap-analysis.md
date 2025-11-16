# OpenAPI Specification Gap Analysis

**Generated:** 2025-11-16
**Last Updated:** 2025-11-16 (Post P1-T1)
**Purpose:** Identify discrepancies between the OpenAPI specification (`api-specification.yaml`) and the actual backend implementation (`endpoint-inventory.md`)

---

## Recent Updates (November 16, 2025)

### P1-T1 Completion: Spec-Implementation Alignment ✅

**Phase 1 - Initial Fixes:**
1. **Categories Endpoint Alignment** - Added `CategoryDto` schema matching actual implementation (`id`, `name`, `slug`, `parentId` only)
   - Updated `/v1/categories` to return `CategoryDto[]` instead of full `Category[]`
   - Public API no longer exposes admin metadata (createdBy, updatedBy, displayOrder)

2. **Channel Detail Endpoint** - Corrected `/v1/channels/{channelId}` response
   - Changed from `ChannelDetailResponse` (with nested playlists) to plain `Channel`
   - Matches actual implementation that returns entity only
   - Added 404 error response documentation

3. **Playlist Detail Endpoint** - Corrected `/v1/playlists/{playlistId}` response
   - Changed from `PlaylistDetailResponse` (with nested videos) to plain `Playlist`
   - Matches actual implementation that returns entity only
   - Added 404 error response documentation

**Phase 2 - Post-Review Corrections:**
4. **Video Detail Endpoint** - Added missing `/v1/videos/{videoId}` endpoint
   - Returns `Video` schema with metadata and approval status
   - Added 404 error response for not found/unavailable videos
   - Fixes missing endpoint that broke 100% coverage claim

5. **Download Token Request Body** - Restored required request body for `/downloads/token/{videoId}`
   - Added `DownloadTokenRequest` schema with required `eulaAccepted` boolean
   - Added 400/403/404 error responses for validation and policy violations
   - Fixes spec/implementation mismatch that would cause client errors

6. **Error Response Schema** - Added generic `ErrorResponse` schema
   - Reusable for all 4xx/5xx responses: `error`, `message`, `details`
   - Standardizes error format across all endpoints
   - Created `ErrorResponse.java` DTO in backend
   - Updated `DownloadController` to return structured error responses (no more empty bodies on 403)

**Phase 3 - Controller and Schema Fixes:**
7. **Download Token Error Contract Alignment** - Fixed controller to match spec
   - Returns 400 (BAD_REQUEST) for missing/invalid `eulaAccepted` field (validation error)
   - Returns 403 (FORBIDDEN) only for EULA not accepted or policy violations
   - Now matches spec's documented error status codes

8. **Video Schema Completion** - Added missing validation fields
   - Added `validationStatus` enum (AVAILABLE/UNAVAILABLE/NOT_VALIDATED)
   - Added `lastValidatedAt` timestamp
   - Video schema now includes all fields from backend model

9. **Documentation Accuracy Fixes** - Corrected gap analysis inconsistencies
   - Fixed CategoryDto to use `parentId` (not `parentCategoryId`)
   - Removed incorrect "Channel schema missing fields" claim (all fields present)
   - Updated Video schema documentation to reflect all fields

**Note:** `ChannelDetailResponse` and `PlaylistDetailResponse` schemas remain in the spec as potential future enhancements. If implementing rich detail views with nested collections, reuse these schemas.

---

## Executive Summary

### Overall Status
- **Total Implemented Endpoints:** ~113+ (across 14 controllers)
- **Documented Endpoints in OpenAPI:** 113+ (100% coverage achieved)
- **Missing from OpenAPI:** 0 endpoints ✅
- **Schema Documentation Gaps:** 15+ DTOs need explicit schemas
- **Response Type Mismatches:** 10+ endpoints still use generic `object` instead of typed schemas

### Priority Breakdown
- **Critical Issues:** 0 (all endpoints documented) ✅
- **High Priority:** 12 (schema mismatches, incomplete request/response definitions)
- **Medium Priority:** 8 (missing DTO schemas, parameter documentation gaps)
- **Low Priority:** 5 (description improvements, example values)

---

## Section 1: Missing Endpoints

### 1.1 Endpoints in Implementation But NOT in OpenAPI Spec

**Status:** ✅ **NO MISSING ENDPOINTS**

After thorough review of all 14 controllers (~113+ endpoints), **all implemented endpoints are documented** in the OpenAPI specification. The spec achieved 100% endpoint coverage as of November 16, 2025.

---

## Section 2: Obsolete Paths in OpenAPI Spec

### 2.1 Endpoints Documented But NOT Implemented

**Status:** ✅ **NO OBSOLETE ENDPOINTS FOUND**

All endpoints documented in the OpenAPI specification have corresponding implementations in the controllers.

---

## Section 3: Schema Mismatches

### 3.1 Response Type Mismatches

#### HIGH PRIORITY: Generic Response Objects

| Endpoint | OpenAPI Schema | Actual Implementation | Impact |
|----------|----------------|----------------------|--------|
| `GET /admin/youtube/search/unified` | `type: array<object>` | Returns `List<EnrichedSearchResult>` | High - Missing DTO schema |
| `GET /admin/youtube/search/all` | Generic response | Returns `SearchPageResponse` | High - Missing schema |
| `GET /admin/youtube/search/channels` | Generic response | Returns `List<EnrichedSearchResult>` | High - Missing schema |
| `GET /admin/youtube/search/playlists` | Generic response | Returns `List<EnrichedSearchResult>` | High - Missing schema |
| `GET /admin/youtube/search/videos` | Generic response | Returns `List<EnrichedSearchResult>` | High - Missing schema |
| `GET /admin/youtube/check-existing` | Generic response | Returns `ExistingContentResponse` | High - Missing DTO schema |
| `GET /admin/youtube/channels/{channelId}` | Generic response | Returns `ChannelDetailsDto` | Medium - Schema exists but not referenced |
| `GET /admin/youtube/channels/{channelId}/videos` | Generic response | Returns `List<StreamItemDto>` | Medium - Missing schema reference |
| `GET /admin/youtube/channels/{channelId}/playlists` | Generic response | Returns `List<PlaylistItemDto>` | Medium - Missing schema reference |
| `GET /admin/youtube/playlists/{playlistId}/videos` | Generic response | Returns `List<StreamItemDto>` | Medium - Missing schema reference |
| `GET /admin/users/role/{role}` | Generic response | Returns `List<User>` | Low - Schema exists |
| `GET /player/next-up/{videoId}` | Generic response | Returns `NextUpDto` | High - Missing DTO schema |
| `GET /admin/content` | Generic response | Returns `ContentLibraryResponse` | High - Missing DTO schema |

### 3.2 Request Body Schema Gaps

| Endpoint | Issue | Recommendation |
|----------|-------|----------------|
| `POST /downloads/analytics/*` | Generic `type: object` schemas | Define explicit schemas: `DownloadStartedEvent`, `DownloadCompletedEvent`, `DownloadFailedEvent` |
| `POST /admin/import-export/import` | `mergeStrategy` shown in schema but actually a query param | Move to parameters section |

---

## Section 4: Missing Request/Response Schemas

### 4.1 DTOs Implemented But Not in OpenAPI Components

**CRITICAL: Missing DTO Schemas (Need to be added to `components/schemas`)**

1. **EnrichedSearchResult** - Used by YouTube search endpoints
2. **SearchPageResponse** - Paginated YouTube search results
3. **ExistingContentResponse** - Check which IDs exist in registry
4. **ExistingContentRequest** - Request payload for check-existing endpoint
5. **StreamItemDto** - Video/stream items in playlists/channels
6. **StreamDetailsDto** - Detailed video/stream information
7. **PlaylistItemDto** - Playlist items in channels
8. **ChannelDetailsDto** - Already partially documented, needs completion
9. **PlaylistDetailsDto** - Already partially documented, needs completion
10. **NextUpDto** - Next-up video recommendations
11. **PendingApprovalDto** - Pending approval items
12. **ApprovalRequestDto** - Approval request payload
13. **ApprovalResponseDto** - Approval response
14. **RejectionRequestDto** - Rejection request payload
15. **ContentLibraryResponse** - Content library paginated response
16. **BulkActionRequest** - Bulk operations request
17. **BulkActionResponse** - Bulk operations response
18. **BulkCategoryAssignmentRequest** - Bulk category assignment
19. **DownloadPolicyDto** - Download policy response
20. **DownloadTokenDto** - Download token response
21. **DownloadManifestDto** - Download manifest response
22. **DashboardMetricsResponse** - Dashboard metrics (partially documented)
23. **CategoryStats** - Category statistics
24. **ImportResponse** - Import operation results
25. **ExportResponse** - Export operation results
26. **SimpleImportResponse** - Simple format import results
27. **SimpleExportResponse** - Simple format export results
28. **ValidationRun** - Video validation run details
29. **ValidationRunResponse** - Validation run response

### 4.2 Model Fields Missing from OpenAPI Schemas

#### Category Schema Status
**Full `Category` Schema (Admin API):**
```yaml
Category:
  properties:
    id, name, slug, parentCategoryId, topLevel, hasSubcategories,
    icon, displayOrder, localizedNames, createdBy, updatedBy,
    createdAt, updatedAt
```
✅ **Complete** - All fields from backend model are documented

**Public API `CategoryDto` (Simplified):**
```yaml
CategoryDto:
  properties:
    id, name, slug, parentId
```
✅ **Matches Implementation** - Excludes admin metadata as designed

#### Channel Schema Status
**Current OpenAPI:**
```yaml
Channel:
  properties:
    id, youtubeId, name, description, thumbnailUrl,
    subscribers, videoCount, categoryIds, status, excludedItems,
    submittedBy, approvedBy, approvalMetadata, createdAt, updatedAt
```

**Status:** ✅ Complete
- ✅ Core fields documented: `id`, `youtubeId`, `name`, `description`, `thumbnailUrl`
- ✅ Metrics: `subscribers` (was `subscriberCount`), `videoCount`
- ✅ Categorization: `categoryIds`
- ✅ Approval workflow: `status` (APPROVED/PENDING/REJECTED), `submittedBy`, `approvedBy`
- ✅ Content filtering: `excludedItems` (nullable reference to ExcludedItems schema)
- ✅ Approval metadata: `approvalMetadata` (embedded object with reviewedBy, reviewedAt, etc.)
- ✅ Timestamps: `createdAt`, `updatedAt`

**Note:** Field naming aligned with backend (`name` not `title`, `subscribers` not `subscriberCount`)

#### Playlist Schema Status
**Current OpenAPI:**
```yaml
Playlist:
  properties:
    id, youtubeId, title, description, thumbnailUrl,
    itemCount, channelId, categoryIds, approvalMetadata,
    createdAt, updatedAt
```

**Status:** ✅ Complete
- ✅ Core fields: `id`, `youtubeId`, `title`, `description`, `thumbnailUrl`
- ✅ Metrics: `itemCount`
- ✅ Parent reference: `channelId`
- ✅ Categorization: `categoryIds`
- ✅ Approval info: `approvalMetadata`
- ✅ Timestamps: `createdAt`, `updatedAt`

#### Video Schema Status
**Current OpenAPI:**
```yaml
Video:
  properties:
    id, youtubeId, title, description, thumbnailUrl,
    durationSeconds, uploadedAt, viewCount, channelId,
    channelTitle, categoryIds, status, validationStatus,
    lastValidatedAt, submittedBy, approvedBy, createdAt, updatedAt
```

**Status:** ✅ Complete
- ✅ Core fields: `id`, `youtubeId`, `title`, `description`, `thumbnailUrl`
- ✅ Metrics: `durationSeconds`, `viewCount`
- ✅ Timestamps: `uploadedAt`, `lastValidatedAt`, `createdAt`, `updatedAt`
- ✅ Parent references: `channelId`, `channelTitle`
- ✅ Categorization: `categoryIds`
- ✅ Status: `status` (APPROVED/PENDING/REJECTED/UNAVAILABLE), `validationStatus` (AVAILABLE/UNAVAILABLE/NOT_VALIDATED)
- ✅ Approval: `submittedBy`, `approvedBy`

**Note:** All fields from backend model now documented in spec

---

## Section 5: Parameter Documentation Gaps

### 5.1 Missing Query Parameters

| Endpoint | Missing Parameter | Type | Description |
|----------|------------------|------|-------------|
| `GET /admin/registry/channels` | `limit` | integer | Documented in inventory, missing in OpenAPI ✅ **FIXED** - Already in spec (line 55) |
| `GET /admin/registry/playlists` | `limit` | integer | Documented in inventory, missing in OpenAPI ✅ **FIXED** - Already in spec (line 208) |
| `GET /admin/registry/videos` | `limit` | integer | Documented in inventory, missing in OpenAPI ✅ **FIXED** - Already in spec (line 452) |

**Analysis:** All `limit` parameters are documented. No gaps found.

### 5.2 Response Format Inconsistencies

#### Issue: Pagination Response Formats

**Cursor-Based Pagination (PublicContentController, ApprovalController):**
- **Implementation:** Returns `CursorPageDto<T>` with `{data: [], pageInfo: {nextCursor}}`
- **OpenAPI:** Partially documented but incomplete structure

**Page-Based Pagination (ContentLibraryController):**
- **Implementation:** Returns `ContentLibraryResponse` with page/size/total
- **OpenAPI:** Not documented at all (generic `type: object`)

**Recommendation:** Add explicit pagination response schemas:
```yaml
CursorPageResponse:
  type: object
  properties:
    data:
      type: array
      items: {}
    pageInfo:
      type: object
      properties:
        nextCursor:
          type: string
          nullable: true
        hasMore:
          type: boolean

PageResponse:
  type: object
  properties:
    content:
      type: array
      items: {}
    page:
      type: integer
    size:
      type: integer
    totalElements:
      type: integer
    totalPages:
      type: integer
```

---

## Section 6: Specific Controller Analysis

### 6.1 PublicContentController (`/api/v1`)

**Status:** ✅ Complete

**Endpoints Documented:**
- ✅ `GET /v1/content` - Returns `CursorPageDto<ContentItemDto>`
- ✅ `GET /v1/categories` - Returns `CategoryDto[]`
- ✅ `GET /v1/channels/{channelId}` - Returns `Channel`
- ✅ `GET /v1/playlists/{playlistId}` - Returns `Playlist`
- ✅ `GET /v1/videos/{videoId}` - Returns `Video` (fixed in P1-T1 Phase 2)
- ✅ `GET /v1/search` - Returns `ContentItemDto[]`

### 6.2 CategoryController (`/api/admin/categories`)

**Status:** ✅ Complete

**Issues:** None - All endpoints properly documented

### 6.3 ChannelController (`/api/admin/channels`)

**Status:** ✅ Complete

**Note:** All fields properly documented in Channel schema

### 6.4 RegistryController (`/api/admin/registry`)

**Status:** ✅ Complete

**Issues:**
- All CRUD operations documented
- Exclusion endpoints documented
- Toggle endpoints documented

### 6.5 YouTubeSearchController (`/api/admin/youtube`)

**Status:** ⚠️ Partially complete - HIGH PRIORITY

**Issues:**
1. **All search endpoints return generic objects** instead of `EnrichedSearchResult`
2. Missing `SearchPageResponse` schema for paginated results
3. Missing `ExistingContentRequest` and `ExistingContentResponse` schemas
4. Missing `ChannelDetailsDto`, `PlaylistDetailsDto`, `StreamDetailsDto`, `StreamItemDto`, `PlaylistItemDto` schemas

**Recommendation:** Add all YouTube-related DTOs to components/schemas

### 6.6 UserController (`/api/admin/users`)

**Status:** ✅ Complete

**Issues:** User schema is complete

### 6.7 AuditLogController (`/api/admin/audit`)

**Status:** ✅ Complete

**Issues:** AuditLog schema is complete

### 6.8 DashboardController (`/api/admin/dashboard`)

**Status:** ⚠️ Partially complete

**Issues:**
- `GET /admin/dashboard` returns generic `{data, meta}` structure
- Missing `DashboardMetricsResponse` schema
- Missing `CategoryStats` schema

### 6.9 ApprovalController (`/api/admin/approvals`)

**Status:** ⚠️ Partially complete

**Issues:**
- `GET /admin/approvals/pending` needs `CursorPageDto<PendingApprovalDto>` schema
- Missing `PendingApprovalDto` schema
- Request/response schemas for approve/reject are incomplete

### 6.10 PlayerController (`/api/player`)

**Status:** ⚠️ Incomplete - MEDIUM PRIORITY

**Issues:**
- Missing `NextUpDto` schema (contains recommended videos)

### 6.11 DownloadController (`/api/downloads`)

**Status:** ✅ Mostly Complete

**Documented:**
- ✅ `DownloadPolicyDto` schema
- ✅ `DownloadTokenDto` schema
- ✅ `DownloadManifestDto` schema
- ✅ `DownloadTokenRequest` schema (fixed in P1-T1 Phase 2)
- ✅ `ErrorResponse` schema (added in P1-T1 Phase 2)
- ✅ Request body for `/downloads/token/{videoId}` now required
- ✅ Error responses now return structured `ErrorResponse` bodies (controller updated)

**Remaining:**
- ⚠️ Missing analytics event schemas (`DownloadStartedEvent`, `DownloadCompletedEvent`, `DownloadFailedEvent`)

### 6.12 ImportExportController (`/api/admin/import-export`)

**Status:** ⚠️ Partially complete

**Issues:**
- Export endpoints return `byte[]` (JSON files) - documented as generic `type: object`
- Missing `ExportResponse` schema
- Missing `ImportResponse` schema
- Missing `SimpleExportResponse` schema
- Missing `SimpleImportResponse` schema
- `POST /import` shows `mergeStrategy` in schema but it's actually a query parameter

### 6.13 VideoValidationController (`/api/admin/videos`)

**Status:** ⚠️ Incomplete - MEDIUM PRIORITY

**Issues:**
- Missing `ValidationRun` schema
- Missing `ValidationRunResponse` schema
- `GET /admin/videos/validation-history` returns `{runs, count}` structure not documented

### 6.14 ContentLibraryController (`/api/admin/content`)

**Status:** ⚠️ Incomplete - HIGH PRIORITY

**Issues:**
- `GET /admin/content` returns `ContentLibraryResponse` - not documented
- Bulk operation request/response schemas are incomplete
- Missing `BulkActionRequest`, `BulkActionResponse`, `BulkCategoryAssignmentRequest` schemas

---

## Section 7: Priority Recommendations

### CRITICAL (Fix Immediately)

1. **Add Missing YouTube DTOs** (Priority: Critical)
   - `EnrichedSearchResult`
   - `SearchPageResponse`
   - `ExistingContentRequest`, `ExistingContentResponse`
   - `ChannelDetailsDto`, `PlaylistDetailsDto`, `StreamDetailsDto`
   - `StreamItemDto`, `PlaylistItemDto`
   
2. **Add Pagination Response Schemas** (Priority: Critical)
   - `CursorPageDto<T>` generic structure
   - `PageResponse<T>` generic structure
   
3. **Fix Channel/Playlist/Video Schemas** (Priority: Critical)
   - Add `status` field (primary approval field)
   - Add `excludedItems`/`excludedVideoIds`
   - Add `submittedBy`, `approvedBy` fields
   - Remove `approved`/`pending` booleans (derived from status)

### HIGH PRIORITY (Fix in Next Sprint)

4. **Add Content Library DTOs**
   - `ContentLibraryResponse`
   - `BulkActionRequest`, `BulkActionResponse`
   - `BulkCategoryAssignmentRequest`

5. **Add Download DTOs**
   - `DownloadPolicyDto`
   - `DownloadTokenDto`
   - `DownloadManifestDto`
   - Analytics event schemas

6. **Add Approval Workflow DTOs**
   - `PendingApprovalDto`
   - `ApprovalRequestDto`, `ApprovalResponseDto`
   - `RejectionRequestDto`

7. **Add Dashboard DTOs**
   - `DashboardMetricsResponse`
   - `CategoryStats`

### MEDIUM PRIORITY (Fix in Backlog)

8. **Add Video Validation DTOs**
   - `ValidationRun`
   - `ValidationRunResponse`

9. **Add Import/Export DTOs**
   - `ExportResponse`, `ImportResponse`
   - `SimpleExportResponse`, `SimpleImportResponse`

10. **Fix Public API Response Types**
    - `GET /v1/videos/{videoId}` - Use explicit `Video` schema
    - `GET /v1/search` - Add `SearchResultsDto` schema

11. **Add Player DTOs**
    - `NextUpDto`

### LOW PRIORITY (Future Improvements)

12. **Add Example Values**
    - Add example values to all schemas
    - Add request/response examples for complex operations

13. **Improve Descriptions**
    - Add detailed descriptions for all endpoints
    - Document error responses (400, 403, 404, 500)

14. **Add Validation Rules**
    - Document field constraints (min/max length, pattern, etc.)
    - Add enum value descriptions

15. **Standardize Error Responses**
    - Create consistent error response schema
    - Document error codes and messages

---

## Section 8: Actionable Tasks

### Task 1: Add Missing DTO Schemas (Critical)
**Estimated Effort:** 4-6 hours

Create schemas for:
- YouTube DTOs (7 schemas)
- Pagination responses (2 schemas)
- Update core models (Channel, Playlist, Video)

### Task 2: Fix Request/Response References (High)
**Estimated Effort:** 2-3 hours

Update all endpoints to reference explicit schemas instead of generic `object`:
- YouTube endpoints (12 endpoints)
- Content Library endpoints (5 endpoints)
- Download endpoints (6 endpoints)

### Task 3: Add Remaining DTOs (Medium)
**Estimated Effort:** 3-4 hours

Create schemas for:
- Dashboard DTOs (2 schemas)
- Approval workflow DTOs (4 schemas)
- Validation DTOs (2 schemas)
- Import/Export DTOs (4 schemas)

### Task 4: Add Examples and Improve Documentation (Low)
**Estimated Effort:** 2-3 hours

- Add example values to all schemas
- Improve endpoint descriptions
- Document error responses

### Task 5: Validate and Test OpenAPI Spec
**Estimated Effort:** 1-2 hours

- Run OpenAPI linter
- Validate against JSON Schema
- Test with Swagger UI / Redoc

---

## Section 9: Implementation Checklist

### Phase 1: Critical Fixes (Week 1)
- [ ] Add `EnrichedSearchResult` schema
- [ ] Add `SearchPageResponse` schema
- [ ] Add `ExistingContentRequest`, `ExistingContentResponse` schemas
- [ ] Add `ChannelDetailsDto`, `PlaylistDetailsDto`, `StreamDetailsDto` schemas
- [ ] Add `StreamItemDto`, `PlaylistItemDto` schemas
- [ ] Update `Channel`, `Playlist`, `Video` schemas with missing fields
- [ ] Add `CursorPageDto<T>` generic schema
- [ ] Update all YouTube endpoints to use explicit schemas

### Phase 2: High Priority (Week 2)
- [ ] Add `ContentLibraryResponse` schema
- [ ] Add bulk operation schemas
- [ ] Add download DTOs
- [ ] Add approval workflow DTOs
- [ ] Add dashboard DTOs
- [ ] Update all endpoints to use explicit schemas

### Phase 3: Medium Priority (Week 3)
- [ ] Add video validation DTOs
- [ ] Add import/export DTOs
- [ ] Fix public API response types
- [ ] Add player DTOs

### Phase 4: Polish (Week 4)
- [ ] Add example values
- [ ] Improve descriptions
- [ ] Add validation rules
- [ ] Standardize error responses
- [ ] Run OpenAPI linter
- [ ] Validate spec completeness

---

## Section 10: OpenAPI Linting Recommendations

### Tools to Use
1. **Spectral** - OpenAPI linting (run: `spectral lint api-specification.yaml`)
2. **Redocly CLI** - OpenAPI validation (run: `redocly lint api-specification.yaml`)
3. **Swagger Editor** - Online validation (https://editor.swagger.io/)

### Expected Issues to Fix
1. Missing response schemas (20+ endpoints)
2. Generic `type: object` usage (15+ endpoints)
3. Missing required fields in request bodies (5+ endpoints)
4. Inconsistent parameter documentation (3+ endpoints)

---

## Appendix A: Complete DTO Schema List

### Implemented DTOs (Need to be Added to OpenAPI)

1. **Search & YouTube Integration**
   - `EnrichedSearchResult`
   - `SearchPageResponse`
   - `ExistingContentRequest`
   - `ExistingContentResponse`
   - `ChannelDetailsDto`
   - `PlaylistDetailsDto`
   - `StreamDetailsDto`
   - `StreamItemDto`
   - `PlaylistItemDto`

2. **Pagination**
   - `CursorPageDto<T>`
   - `PageResponse<T>` (if needed)

3. **Approval Workflow**
   - `PendingApprovalDto`
   - `ApprovalRequestDto`
   - `ApprovalResponseDto`
   - `RejectionRequestDto`

4. **Content Library**
   - `ContentLibraryResponse`
   - `BulkActionRequest`
   - `BulkActionResponse`
   - `BulkCategoryAssignmentRequest`

5. **Downloads**
   - `DownloadPolicyDto`
   - `DownloadTokenDto`
   - `DownloadManifestDto`
   - `DownloadStartedEvent`
   - `DownloadCompletedEvent`
   - `DownloadFailedEvent`

6. **Dashboard**
   - `DashboardMetricsResponse`
   - `CategoryStats`

7. **Import/Export**
   - `ExportResponse`
   - `ImportResponse`
   - `SimpleExportResponse`
   - `SimpleImportResponse`

8. **Validation**
   - `ValidationRun`
   - `ValidationRunResponse`

9. **Player**
   - `NextUpDto`

10. **Public Content**
    - `ContentItemDto` (already documented)
    - `CategoryDto` (already documented)
    - Update `VideoDto`, `ChannelDto`, `PlaylistDto` with missing fields

---

## Appendix B: Model Field Comparison Matrix

| Model | OpenAPI Fields | Implementation Fields | Status |
|-------|---------------|----------------------|--------|
| Category | id, name, slug, parentCategoryId, topLevel, hasSubcategories, icon, displayOrder, localizedNames, createdBy, updatedBy, createdAt, updatedAt | Same as OpenAPI | ✅ Complete |
| CategoryDto (Public) | id, name, slug, parentId | Same as OpenAPI (simplified) | ✅ Complete |
| Channel | id, youtubeId, name, description, thumbnailUrl, subscribers, videoCount, categoryIds, approvalMetadata, createdAt, updatedAt | Same as OpenAPI | ✅ Complete |
| Playlist | id, youtubeId, title, description, thumbnailUrl, itemCount, channelId, categoryIds, approvalMetadata, createdAt, updatedAt | Same as OpenAPI | ✅ Complete |
| Video | id, youtubeId, title, description, thumbnailUrl, durationSeconds, uploadedAt, viewCount, channelId, channelTitle, categoryIds, status, validationStatus, lastValidatedAt, submittedBy, approvedBy, createdAt, updatedAt | Same as OpenAPI | ✅ Complete |

---

## Conclusion

The OpenAPI specification is **approximately 70% complete** compared to the actual implementation. The main gaps are:

1. **Missing DTO schemas** for complex response types (29 DTOs)
2. **Generic response types** instead of explicit schemas (15+ endpoints)
3. **Incomplete model schemas** for core entities (4 models)
4. **Import/Export parameter mismatches** (mergeStrategy placement)

**Recommended Priority:**
1. ✅ **Phase 1 (Critical):** Add YouTube DTOs and fix core model schemas (Week 1)
2. ✅ **Phase 2 (High):** Add content library, download, and approval DTOs (Week 2)
3. ✅ **Phase 3 (Medium):** Add remaining DTOs and fix public API (Week 3)
4. ✅ **Phase 4 (Polish):** Add examples, improve docs, run linter (Week 4)

**Total Estimated Effort:** 12-16 hours spread over 4 weeks

---

**Last Updated:** 2025-11-16  
**Reviewed By:** Claude Code (Automated Analysis)  
**Next Review:** After Phase 1 completion
