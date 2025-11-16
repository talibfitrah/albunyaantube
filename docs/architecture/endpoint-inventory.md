# Backend API Endpoint Inventory
**Generated:** 2025-11-16
**Purpose:** Complete inventory of all backend REST API endpoints for comparison against OpenAPI specification

---

## Summary
- **Total Controllers:** 14
- **Total Endpoints:** ~90+
- **Base Paths:** `/api/v1` (public), `/api/admin` (admin), `/api/player` (player), `/api/downloads` (downloads)

---

## 1. PublicContentController (`/api/v1`)
**Purpose:** Public API for Android app (no authentication required)

| Method | Path | Query Params | Response | Notes |
|--------|------|--------------|----------|-------|
| GET | `/api/v1/content` | `type`, `cursor`, `limit`, `category`, `length`, `date`, `sort` | `CursorPageDto<ContentItemDto>` | Paginated content |
| GET | `/api/v1/categories` | - | `List<CategoryDto>` | Cached 1 hour |
| GET | `/api/v1/channels/{channelId}` | - | Channel details | - |
| GET | `/api/v1/playlists/{playlistId}` | - | Playlist details | - |
| GET | `/api/v1/videos/{videoId}` | - | Video details | - |
| GET | `/api/v1/search` | `q`, `type`, `limit` | Search results | - |

---

## 2. CategoryController (`/api/admin/categories`)
**Purpose:** Category CRUD (admin only)

| Method | Path | Query Params | Request Body | Response | Notes |
|--------|------|--------------|--------------|----------|-------|
| GET | `/api/admin/categories` | - | - | `List<Category>` | Cached 1 hour |
| GET | `/api/admin/categories/top-level` | - | - | `List<Category>` | Cached 1 hour |
| GET | `/api/admin/categories/{parentId}/subcategories` | - | - | `List<Category>` | Cached 1 hour |
| GET | `/api/admin/categories/{id}` | - | - | `Category` | Cached 1 hour |
| POST | `/api/admin/categories` | - | `Category` | `Category` | Admin only, evicts cache |
| PUT | `/api/admin/categories/{id}` | - | `Category` | `Category` | Admin only, evicts cache |
| DELETE | `/api/admin/categories/{id}` | - | - | `204 No Content` | Admin only, evicts cache |

---

## 3. ChannelController (`/api/admin/channels`)
**Purpose:** Channel management (admin/moderator)

| Method | Path | Query Params | Request Body | Response | Notes |
|--------|------|--------------|--------------|----------|-------|
| GET | `/api/admin/channels` | `status` | - | `List<Channel>` | - |
| GET | `/api/admin/channels/category/{categoryId}` | - | - | `List<Channel>` | Cached 15 min |
| GET | `/api/admin/channels/{id}` | - | - | `Channel` | Cached 15 min |
| GET | `/api/admin/channels/{id}/exclusions` | - | - | `ExcludedItems` | - |
| POST | `/api/admin/channels` | - | `Channel` | `Channel` | Evicts cache |
| POST | `/api/admin/channels/{id}/exclusions/{type}/{youtubeId}` | - | - | `ExcludedItems` | type: video/playlist/livestream/short/post |
| PUT | `/api/admin/channels/{id}/approve` | - | - | `Channel` | Admin only, evicts cache |
| PUT | `/api/admin/channels/{id}/reject` | - | - | `Channel` | Admin only, evicts cache |
| PUT | `/api/admin/channels/{id}/exclusions` | - | `ExcludedItems` | `Channel` | Admin only |
| DELETE | `/api/admin/channels/{id}` | - | - | `204 No Content` | Admin only |
| DELETE | `/api/admin/channels/{id}/exclusions/{type}/{youtubeId}` | - | - | `ExcludedItems` | - |

---

## 4. RegistryController (`/api/admin/registry`)
**Purpose:** Registry management for channels, playlists, videos (admin/moderator)

### Channels
| Method | Path | Query Params | Request Body | Response | Notes |
|--------|------|--------------|--------------|----------|-------|
| GET | `/api/admin/registry/channels` | `limit` (default: 100) | - | `List<Channel>` | - |
| GET | `/api/admin/registry/channels/status/{status}` | `limit` (default: 100) | - | `List<Channel>` | status: APPROVED/PENDING/REJECTED |
| GET | `/api/admin/registry/channels/{id}` | - | - | `Channel` | - |
| POST | `/api/admin/registry/channels` | - | `Channel` | `Channel` | 201 Created, 409 Conflict if exists |
| PUT | `/api/admin/registry/channels/{id}` | - | `Channel` | `Channel` | - |
| PATCH | `/api/admin/registry/channels/{id}/toggle` | - | - | `Channel` | Toggle APPROVED/PENDING |
| DELETE | `/api/admin/registry/channels/{id}` | - | - | `204 No Content` | Admin only |

### Playlists
| Method | Path | Query Params | Request Body | Response | Notes |
|--------|------|--------------|--------------|----------|-------|
| GET | `/api/admin/registry/playlists` | `limit` (default: 100) | - | `List<Playlist>` | - |
| GET | `/api/admin/registry/playlists/status/{status}` | `limit` (default: 100) | - | `List<Playlist>` | status: APPROVED/PENDING/REJECTED |
| GET | `/api/admin/registry/playlists/{id}` | - | - | `Playlist` | - |
| GET | `/api/admin/registry/playlists/{id}/exclusions` | - | - | `List<String>` | Excluded video IDs |
| POST | `/api/admin/registry/playlists` | - | `Playlist` | `Playlist` | 201 Created, 409 Conflict if exists |
| POST | `/api/admin/registry/playlists/{id}/exclusions/{videoId}` | - | - | `List<String>` | Add video exclusion |
| PUT | `/api/admin/registry/playlists/{id}` | - | `Playlist` | `Playlist` | - |
| PATCH | `/api/admin/registry/playlists/{id}/toggle` | - | - | `Playlist` | Toggle APPROVED/PENDING |
| DELETE | `/api/admin/registry/playlists/{id}` | - | - | `204 No Content` | Admin only |
| DELETE | `/api/admin/registry/playlists/{id}/exclusions/{videoId}` | - | - | `List<String>` | Remove video exclusion |

### Videos
| Method | Path | Query Params | Request Body | Response | Notes |
|--------|------|--------------|--------------|----------|-------|
| GET | `/api/admin/registry/videos` | `limit` (default: 100) | - | `List<Video>` | - |
| GET | `/api/admin/registry/videos/status/{status}` | `limit` (default: 100) | - | `List<Video>` | status: APPROVED/PENDING/REJECTED |
| GET | `/api/admin/registry/videos/{id}` | - | - | `Video` | - |
| POST | `/api/admin/registry/videos` | - | `Video` | `Video` | 201 Created, 409 Conflict if exists |
| PUT | `/api/admin/registry/videos/{id}` | - | `Video` | `Video` | - |
| PATCH | `/api/admin/registry/videos/{id}/toggle` | - | - | `Video` | Toggle APPROVED/PENDING |
| DELETE | `/api/admin/registry/videos/{id}` | - | - | `204 No Content` | Admin only |

---

## 5. YouTubeSearchController (`/api/admin/youtube`)
**Purpose:** YouTube search integration (admin/moderator)

| Method | Path | Query Params | Request Body | Response | Notes |
|--------|------|--------------|--------------|----------|-------|
| GET | `/api/admin/youtube/search/unified` | `query` | - | `List<EnrichedSearchResult>` | Single API call, mixed results |
| GET | `/api/admin/youtube/search/all` | `query`, `pageToken` | - | `SearchPageResponse` | Paginated search |
| GET | `/api/admin/youtube/search/channels` | `query` | - | `List<EnrichedSearchResult>` | - |
| GET | `/api/admin/youtube/search/playlists` | `query` | - | `List<EnrichedSearchResult>` | - |
| GET | `/api/admin/youtube/search/videos` | `query` | - | `List<EnrichedSearchResult>` | - |
| GET | `/api/admin/youtube/channels/{channelId}` | - | - | `ChannelDetailsDto` | - |
| GET | `/api/admin/youtube/channels/{channelId}/videos` | `pageToken`, `q` | - | `List<StreamItemDto>` | Optional search filter |
| GET | `/api/admin/youtube/channels/{channelId}/playlists` | `pageToken` | - | `List<PlaylistItemDto>` | - |
| GET | `/api/admin/youtube/playlists/{playlistId}` | - | - | `PlaylistDetailsDto` | - |
| GET | `/api/admin/youtube/playlists/{playlistId}/videos` | `pageToken`, `q` | - | `List<StreamItemDto>` | Optional search filter |
| GET | `/api/admin/youtube/videos/{videoId}` | - | - | `StreamDetailsDto` | - |
| POST | `/api/admin/youtube/check-existing` | - | `ExistingContentRequest` | `ExistingContentResponse` | Check which IDs exist in registry |

---

## 6. UserController (`/api/admin/users`)
**Purpose:** User management (admin only)

| Method | Path | Query Params | Request Body | Response | Notes |
|--------|------|--------------|--------------|----------|-------|
| GET | `/api/admin/users` | - | - | `List<User>` | Admin only |
| GET | `/api/admin/users/{uid}` | - | - | `User` | Admin only |
| GET | `/api/admin/users/role/{role}` | - | - | `List<User>` | Admin only |
| POST | `/api/admin/users` | - | `CreateUserRequest` | `User` | Admin only, 201 Created |
| POST | `/api/admin/users/{uid}/reset-password` | - | - | `200 OK` | Admin only, sends email |
| PUT | `/api/admin/users/{uid}/role` | - | `UpdateRoleRequest` | `User` | Admin only |
| PUT | `/api/admin/users/{uid}/status` | - | `UpdateStatusRequest` | `User` | Admin only |
| DELETE | `/api/admin/users/{uid}` | - | - | `204 No Content` | Admin only |

---

## 7. AuditLogController (`/api/admin/audit`)
**Purpose:** Audit log viewing (admin only)

| Method | Path | Query Params | Request Body | Response | Notes |
|--------|------|--------------|--------------|----------|-------|
| GET | `/api/admin/audit` | `limit` (default: 100) | - | `List<AuditLog>` | Admin only |
| GET | `/api/admin/audit/actor/{actorUid}` | `limit` (default: 100) | - | `List<AuditLog>` | Admin only |
| GET | `/api/admin/audit/entity-type/{entityType}` | `limit` (default: 100) | - | `List<AuditLog>` | Admin only |
| GET | `/api/admin/audit/action/{action}` | `limit` (default: 100) | - | `List<AuditLog>` | Admin only |

---

## 8. DashboardController (`/api/admin/dashboard`)
**Purpose:** Dashboard metrics (admin/moderator)

| Method | Path | Query Params | Request Body | Response | Notes |
|--------|------|--------------|--------------|----------|-------|
| GET | `/api/admin/dashboard` | `timeframe` (default: LAST_7_DAYS) | - | `DashboardMetricsResponse` | - |
| GET | `/api/admin/dashboard/stats/by-category` | - | - | `Map<String, CategoryStats>` | - |

---

## 9. ApprovalController (`/api/admin/approvals`)
**Purpose:** Approval workflow (admin/moderator)

| Method | Path | Query Params | Request Body | Response | Notes |
|--------|------|--------------|--------------|----------|-------|
| GET | `/api/admin/approvals/pending` | `type`, `category`, `limit`, `cursor` | - | `CursorPageDto<PendingApprovalDto>` | - |
| POST | `/api/admin/approvals/{id}/approve` | - | `ApprovalRequestDto` | `ApprovalResponseDto` | - |
| POST | `/api/admin/approvals/{id}/reject` | - | `RejectionRequestDto` | `ApprovalResponseDto` | - |

---

## 10. PlayerController (`/api/player`)
**Purpose:** Player features (next-up recommendations)

| Method | Path | Query Params | Request Body | Response | Notes |
|--------|------|--------------|--------------|----------|-------|
| GET | `/api/player/next-up/{videoId}` | `userId` (optional) | - | `NextUpDto` | - |

---

## 11. DownloadController (`/api/downloads`)
**Purpose:** Download management

| Method | Path | Query Params | Request Body | Response | Notes |
|--------|------|--------------|--------------|----------|-------|
| GET | `/api/downloads/policy/{videoId}` | - | - | `DownloadPolicyDto` | - |
| GET | `/api/downloads/manifest/{videoId}` | `token` | - | `DownloadManifestDto` | Requires valid token |
| POST | `/api/downloads/token/{videoId}` | - | `{eulaAccepted: boolean}` | `DownloadTokenDto` | 403 if not allowed |
| POST | `/api/downloads/analytics/download-started` | - | `{videoId, quality, deviceType}` | `201 Created` | - |
| POST | `/api/downloads/analytics/download-completed` | - | `{videoId, quality, fileSize, deviceType}` | `201 Created` | - |
| POST | `/api/downloads/analytics/download-failed` | - | `{videoId, errorReason, deviceType}` | `201 Created` | - |

---

## 12. ImportExportController (`/api/admin/import-export`)
**Purpose:** Bulk import/export (admin only)

### Export Endpoints
| Method | Path | Query Params | Response | Notes |
|--------|------|--------------|----------|-------|
| GET | `/api/admin/import-export/export` | `includeCategories`, `includeChannels`, `includePlaylists`, `includeVideos`, `excludeUnavailableVideos` | JSON file download | All content |
| GET | `/api/admin/import-export/export/categories` | - | JSON file download | Categories only |
| GET | `/api/admin/import-export/export/channels` | - | JSON file download | Channels only |
| GET | `/api/admin/import-export/export/playlists` | - | JSON file download | Playlists only |
| GET | `/api/admin/import-export/export/videos` | `excludeUnavailableVideos` | JSON file download | Videos only |
| GET | `/api/admin/import-export/export/simple` | `includeChannels`, `includePlaylists`, `includeVideos` | JSON file download | Simple format |

### Import Endpoints
| Method | Path | Query Params | Request Body | Response | Notes |
|--------|------|--------------|--------------|----------|-------|
| POST | `/api/admin/import-export/import` | `mergeStrategy` (default: SKIP) | `multipart/form-data` (JSON file) | `ImportResponse` | Full format |
| POST | `/api/admin/import-export/import/validate` | - | `multipart/form-data` (JSON file) | `ImportResponse` | Dry-run validation |
| POST | `/api/admin/import-export/import/simple` | `defaultStatus` (default: APPROVED) | `multipart/form-data` (JSON file) | `SimpleImportResponse` | Simple format |
| POST | `/api/admin/import-export/import/simple/validate` | - | `multipart/form-data` (JSON file) | `SimpleImportResponse` | Simple format dry-run |

---

## 13. VideoValidationController (`/api/admin/videos`)
**Purpose:** Video validation (admin/moderator)

| Method | Path | Query Params | Request Body | Response | Notes |
|--------|------|--------------|--------------|----------|-------|
| GET | `/api/admin/videos/validation-status/{runId}` | - | - | `ValidationRun` | - |
| GET | `/api/admin/videos/validation-history` | `limit` (default: 20, max: 100) | - | `{runs, count}` | - |
| GET | `/api/admin/videos/validation-latest` | - | - | `ValidationRun` | - |
| POST | `/api/admin/videos/validate` | `maxVideos` (1-1000) | - | `ValidationRunResponse` | Admin only, manual trigger |

---

## 14. ContentLibraryController (`/api/admin/content`)
**Purpose:** Unified content management (admin/moderator)

| Method | Path | Query Params | Request Body | Response | Notes |
|--------|------|--------------|--------------|----------|-------|
| GET | `/api/admin/content` | `types`, `status`, `category`, `search`, `sort`, `page`, `size` | - | `ContentLibraryResponse` | Unified view |
| POST | `/api/admin/content/bulk/approve` | - | `BulkActionRequest` | `BulkActionResponse` | Admin only, batched |
| POST | `/api/admin/content/bulk/reject` | - | `BulkActionRequest` | `BulkActionResponse` | Admin only, batched |
| POST | `/api/admin/content/bulk/delete` | - | `BulkActionRequest` | `BulkActionResponse` | Admin only, batched |
| POST | `/api/admin/content/bulk/assign-categories` | - | `BulkCategoryAssignmentRequest` | `BulkActionResponse` | Admin only, batched |

---

## Notes on Implementation Patterns

### Pagination
- **CursorPageDto** used in: PublicContentController (`/content`), ApprovalController (`/pending`)
- **List responses** used in: Most other endpoints (no pagination)
- **Fake cursor logic** exists in some services (needs real DocumentSnapshot-based pagination per P2-T2)

### Authentication & Authorization
- Public endpoints: `/api/v1/**` (no auth)
- Admin/Moderator endpoints: `/api/admin/**` (requires `@PreAuthorize`)
- Player endpoints: `/api/player/**` (no auth currently)
- Download endpoints: `/api/downloads/**` (mixed - policy check doesn't need auth, token/manifest require user)

### Response Patterns
- **Direct entity returns**: Many controllers return raw entities (Channel, Category, etc.)
- **Envelope patterns**: DashboardController uses `{data, meta}` structure
- **Error responses**: Mostly standard HTTP status codes (404, 400, 403, 500), no standardized error DTO

### Caching
- Categories: 1 hour TTL
- Channels: 15 minute TTL
- Public categories: 1 hour TTL

---

## Comparison TODO

1. ✅ Inventory complete
2. ⏳ Diff against `docs/architecture/api-specification.yaml`
3. ⏳ Identify missing endpoints
4. ⏳ Identify obsolete paths
5. ⏳ Identify schema mismatches
6. ⏳ Add missing schemas
7. ⏳ Run OpenAPI linter
8. ⏳ Update `docs/architecture/overview.md`
