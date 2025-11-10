# Albunyaan Tube Backend - Comprehensive Feature & Endpoint Analysis

**Date:** November 10, 2025
**Total Controllers:** 14
**Total Endpoints:** 67+
**Scope:** Complete REST API feature inventory

---

## Executive Summary

The backend implements a sophisticated content management system with features far exceeding basic CRUD operations. Key discoveries:

- **Advanced Bulk Operations:** Transactional batch updates with Firestore WriteBatch (max 500 items/batch)
- **Sophisticated Import/Export:** Two formats (full JSON + simple format), validation, dry-runs, merge strategies
- **Content Filtering:** Multi-dimensional filtering (types, status, category, search, sorting)
- **Exclusions Management:** Channel content exclusions (videos, playlists, livestreams, shorts, posts) + playlist video exclusions
- **Video Validation:** Automated availability checking with validation run history
- **Download Management:** Policy enforcement, token generation, analytics tracking
- **Audit Trail:** Complete action logging with actor, entity, and action filters
- **Player Recommendations:** Next-up video suggestions for continuous viewing
- **Performance Optimization:** Caching strategy (Caffeine/Redis), compression, pagination

---

## 1. ApprovalController
**Base Path:** `/api/admin/approvals`
**Access:** Admin/Moderator only

### Advanced Features:
- **Pending Approvals with Cursor Pagination:** Supports type/category/limit/cursor filters
- **Approval with Review Notes:** Optional category override on approval
- **Rejection Workflow:** Predefined rejection reasons (NOT_ISLAMIC, LOW_QUALITY, DUPLICATE, OTHER) with custom notes
- **Rejection Reasons Support:** Structured rejection with categorized reasons

### Endpoints:
1. `GET /pending` - List pending approvals (filtered, paginated)
   - Query params: type, category, limit, cursor
2. `POST /{id}/approve` - Approve pending item with optional category override
   - Body: {reviewNotes, categoryOverride}
3. `POST /{id}/reject` - Reject pending item with reason
   - Body: {reason, reviewNotes}

---

## 2. AuditLogController
**Base Path:** `/api/admin/audit`
**Access:** Admin only

### Advanced Features:
- **Multi-Dimension Audit Querying:** Filter by actor, entity type, or action
- **Audit Limit Control:** Configurable result limit (default 100)
- **Action History Tracking:** Complete audit trail for compliance

### Endpoints:
1. `GET /` - Get recent audit logs (limit configurable, default 100)
2. `GET /actor/{actorUid}` - Filter logs by actor (who performed action)
3. `GET /entity-type/{entityType}` - Filter logs by entity type (channel, playlist, video, category, user)
4. `GET /action/{action}` - Filter logs by action type (created, updated, deleted, approved, rejected, etc.)

---

## 3. CategoryController
**Base Path:** `/api/admin/categories`
**Access:** Read by all, write by Admin only

### Advanced Features:
- **Hierarchical Category Structure:** Parent-child relationships with validation
- **Circular Reference Prevention:** Validates against self-reference and descendant cycles
- **Top-Level Categories:** Dedicated endpoint for root categories only
- **Subcategories Retrieval:** Get children of specific parent
- **Localized Names:** Support for multiple language names (en, ar, nl)
- **Cache Management:** Automatic cache eviction on create/update/delete
- **Category Hierarchy Validation:** Prevents circular references when moving categories

### Endpoints:
1. `GET /` - Get all categories (cached)
2. `GET /top-level` - Get root-level categories only (cached)
3. `GET /{parentId}/subcategories` - Get children of category (cached)
4. `GET /{id}` - Get category by ID (cached)
5. `POST /` - Create new category (admin only, evicts cache)
   - Body: {name, parentCategoryId, icon, displayOrder, localizedNames}
6. `PUT /{id}` - Update category (admin only, evicts cache)
   - Validation: parent exists, prevents self-reference, prevents circular refs
7. `DELETE /{id}` - Delete category (admin only, evicts cache)
   - Validation: fails if category has subcategories

---

## 4. ChannelController
**Base Path:** `/api/admin/channels`
**Access:** Admin/Moderator (with different permissions)

### Advanced Features:
- **Granular Exclusion Management:** Exclude specific content types by YouTube ID
  - Types: video, playlist, livestream, short, post
  - Add/remove individual exclusions
  - Bulk updates to exclusion sets
- **Status-Based Filtering:** APPROVED, PENDING, REJECTED
- **Category-Based Filtering:** Find channels by assigned category
- **Admin Auto-Approval:** Admins automatically approve, moderators submit as pending
- **Duplicate Detection:** Prevents duplicate YouTube IDs
- **Cache Optimization:** Category and channel caching (15 minutes)

### Endpoints:
1. `GET /` - Get all channels (with optional status filter, defaults to approved)
2. `GET /category/{categoryId}` - Get channels by category (cached)
3. `GET /{id}` - Get channel by ID (cached, admin/mod only)
4. `POST /` - Submit channel for approval (auto-approves if admin)
5. `PUT /{id}/approve` - Approve pending channel (admin only)
6. `PUT /{id}/reject` - Reject channel (admin only)
7. `PUT /{id}/exclusions` - Bulk update exclusions
8. `GET /{id}/exclusions` - Get all exclusions for channel
9. `POST /{id}/exclusions/{type}/{youtubeId}` - Add single exclusion (video, playlist, livestream, short, post)
10. `DELETE /{id}/exclusions/{type}/{youtubeId}` - Remove single exclusion
11. `DELETE /{id}` - Delete channel (admin only)

---

## 5. ContentLibraryController
**Base Path:** `/api/admin/content`
**Access:** Admin/Moderator only

### Advanced Features:
- **Unified Multi-Type Search:** Search across channels, playlists, and videos simultaneously
- **Advanced Filtering:** Type, status, category, search text
- **Flexible Sorting:** Newest/oldest date ordering
- **Pagination Support:** Page number + size (configurable)
- **Firestore Batch Operations:** Atomic transactional updates (max 500 items per batch)
- **Merge Strategies:** SKIP, OVERWRITE, MERGE for conflict handling
- **Bulk Actions with Atomic Guarantees:** All-or-nothing batch updates

### Endpoints:
1. `GET /` - Get all content with filtering
   - Query params: types, status, category, search, sort, page, size
   - Features: Multi-type search, full-text search, sorting, pagination
   - Response: {content[], totalItems, currentPage, pageSize, totalPages}

**Bulk Operations (with Firestore WriteBatch):**
2. `POST /bulk/approve` - Bulk approve content items (up to 500/batch)
3. `POST /bulk/reject` - Bulk reject content items (up to 500/batch)
4. `POST /bulk/delete` - Bulk delete content items (up to 500/batch)
5. `POST /bulk/assign-categories` - Bulk assign categories to content (up to 500/batch)

All bulk operations:
- Validate request structure
- Process in chunks respecting Firestore limits
- Return success count + error list
- Use atomic batch writes for transactional safety
- Support all three content types (channel, playlist, video)

---

## 6. DashboardController
**Base Path:** `/api/admin/dashboard`
**Access:** Admin/Moderator only

### Advanced Features:
- **Dashboard Metrics:** Pending moderation count, category count, moderator count
- **Validation Metrics:** Latest validation run with video availability stats
- **Trend Analysis:** UP/DOWN/FLAT trend detection
- **Category Statistics:** Breakdown by category with approval ratios
- **Optimized Queries:** Database-level count queries (not loading all records)
- **Timeframe Support:** Configurable time ranges for metrics

### Endpoints:
1. `GET /` - Get dashboard metrics
   - Query params: timeframe (default LAST_7_DAYS)
   - Response: {data: {pendingModeration, categories, moderators, videoValidation}, meta: {generatedAt, timeRange, cacheTtl}}
2. `GET /stats/by-category` - Get statistics grouped by category
   - Response: {categoryId: {totalChannels, approvedChannels, pendingChannels}}

---

## 7. DownloadController
**Base Path:** `/api/downloads`
**Access:** Public/Authenticated

### Advanced Features:
- **Download Policy Enforcement:** Check download eligibility by video
- **Token-Based Access Control:** Generate time-limited download tokens
- **EULA Acceptance Tracking:** Require user acceptance before download
- **Download Analytics:** Track started, completed, failed downloads
- **Quality Tracking:** Record requested quality levels
- **Device Type Tracking:** Categorize downloads by device (mobile, desktop, etc.)
- **File Size Tracking:** Record actual downloaded file sizes

### Endpoints:
1. `GET /policy/{videoId}` - Check if video can be downloaded
2. `POST /token/{videoId}` - Generate download token
   - Body: {eulaAccepted: boolean}
3. `GET /manifest/{videoId}` - Get download manifest with URLs
   - Query param: token
4. `POST /analytics/download-started` - Track download start
   - Body: {videoId, quality, deviceType}
5. `POST /analytics/download-completed` - Track completion
   - Body: {videoId, quality, fileSize, deviceType}
6. `POST /analytics/download-failed` - Track failure
   - Body: {videoId, errorReason, deviceType}

---

## 8. ImportExportController
**Base Path:** `/api/admin/import-export`
**Access:** Admin only

### Advanced Features:

#### **Full Format (Complex):**
- **Selective Export:** Include/exclude categories, channels, playlists, videos
- **Video Filtering:** Option to exclude UNAVAILABLE videos
- **Merge Strategies:** SKIP (skip duplicates), OVERWRITE (replace existing), MERGE (smart merge)
- **Individual Type Exports:** Dedicated endpoints for each content type
- **Import Validation:** Dry-run capability before importing
- **Detailed Import Results:** Success count + error list with specific messages

#### **Simple Format (Lightweight):**
- **Compact Format:** {channelId: "Title|Cat1,Cat2"} format for easy bulk operations
- **YouTube ID Validation:** Verifies YouTube IDs still exist before importing
- **Duplicate Detection:** Skips content already in registry
- **Default Status:** Set approval status (APPROVED or PENDING) during import

### Endpoints:

**Full Format Endpoints:**
1. `GET /export` - Export all content (selective)
   - Query params: includeCategories, includeChannels, includePlaylists, includeVideos, excludeUnavailableVideos
2. `GET /export/categories` - Export only categories
3. `GET /export/channels` - Export only channels
4. `GET /export/playlists` - Export only playlists
5. `GET /export/videos` - Export only videos
6. `POST /import` - Import from JSON file
   - Params: file (multipart), mergeStrategy (SKIP/OVERWRITE/MERGE)
7. `POST /import/validate` - Validate import file without importing (dry-run)

**Simple Format Endpoints:**
8. `GET /export/simple` - Export in simple format [{channels}, {playlists}, {videos}]
9. `POST /import/simple` - Import from simple format
   - Params: file (multipart), defaultStatus (APPROVED/PENDING)
10. `POST /import/simple/validate` - Validate simple format import (dry-run)

---

## 9. PlayerController
**Base Path:** `/api/player`
**Access:** Public

### Advanced Features:
- **Next-Up Recommendations:** Suggest next videos based on current video
- **User Personalization:** Optional user ID for future personalized recommendations

### Endpoints:
1. `GET /next-up/{videoId}` - Get next video recommendations
   - Query param: userId (optional)
   - Response: {NextUpDto with recommended videos}

---

## 10. PublicContentController
**Base Path:** `/api/v1`
**Access:** Public (no authentication required)

### Advanced Features:
- **Cursor-Based Pagination:** Efficient pagination with cursor tokens
- **Content Type Filtering:** HOME (mixed), CHANNELS, PLAYLISTS, VIDEOS
- **Category Filtering:** Filter by category slug
- **Video Length Filtering:** SHORT, MEDIUM, LONG
- **Date Range Filtering:** LAST_24_HOURS, LAST_7_DAYS, LAST_30_DAYS
- **Sorting Options:** DEFAULT, MOST_POPULAR, NEWEST
- **Request Limiting:** Capped at 50 items max per request
- **Cache Strategy:** Category caching (1 hour TTL)

### Endpoints:
1. `GET /content` - Get paginated content
   - Query params: type, cursor, limit, category, length, date, sort
   - Features: Multi-dimensional filtering, cursor pagination
2. `GET /categories` - Get categories for filtering (cached)
3. `GET /channels/{channelId}` - Get channel details with playlists
4. `GET /playlists/{playlistId}` - Get playlist details with videos
5. `GET /search` - Search across all content
   - Query params: q (required), type, limit

---

## 11. RegistryController
**Base Path:** `/api/admin/registry`
**Access:** Admin/Moderator (with different permissions)

### Advanced Features:
- **Comprehensive CRUD:** Full lifecycle management for channels, playlists, videos
- **Status Toggle:** Quick APPROVED ↔ PENDING state transitions
- **Playlist Video Exclusions:** Exclude specific videos from playlists
- **Channel Content Exclusions:** Managed in ChannelController (see #4)
- **Auto-Approval Logic:** Admins auto-approve, moderators submit as pending
- **Audit Logging:** All operations logged for compliance
- **Duplicate Detection:** Prevents duplicate YouTube IDs

### Endpoints:

**Channel Endpoints:**
1. `GET /channels` - Get all channels (any status)
2. `GET /channels/status/{status}` - Filter channels by status
3. `GET /channels/{id}` - Get specific channel
4. `POST /channels` - Add channel to registry (auto-approves if admin)
5. `PUT /channels/{id}` - Update channel
6. `PATCH /channels/{id}/toggle` - Toggle APPROVED ↔ PENDING
7. `DELETE /channels/{id}` - Delete channel

**Playlist Endpoints:**
8. `GET /playlists` - Get all playlists
9. `GET /playlists/status/{status}` - Filter playlists by status
10. `GET /playlists/{id}` - Get specific playlist
11. `POST /playlists` - Add playlist to registry
12. `PUT /playlists/{id}` - Update playlist
13. `PATCH /playlists/{id}/toggle` - Toggle APPROVED ↔ PENDING
14. `DELETE /playlists/{id}` - Delete playlist
15. `GET /playlists/{id}/exclusions` - Get excluded video IDs
16. `POST /playlists/{id}/exclusions/{videoId}` - Exclude specific video
17. `DELETE /playlists/{id}/exclusions/{videoId}` - Remove video exclusion

**Video Endpoints:**
18. `GET /videos` - Get all videos
19. `GET /videos/status/{status}` - Filter videos by status
20. `GET /videos/{id}` - Get specific video
21. `POST /videos` - Add video to registry
22. `PUT /videos/{id}` - Update video
23. `PATCH /videos/{id}/toggle` - Toggle APPROVED ↔ PENDING
24. `DELETE /videos/{id}` - Delete video

---

## 12. UserController
**Base Path:** `/api/admin/users`
**Access:** Admin only

### Advanced Features:
- **User Lifecycle Management:** Create, read, update, delete users
- **Role-Based Access:** ADMIN and MODERATOR roles
- **User Status Control:** Activate/deactivate users
- **Firebase Integration:** Leverages Firebase Auth for secure user management
- **Password Reset:** Send password reset emails
- **Audit Logging:** All user operations logged

### Endpoints:
1. `GET /` - List all users
2. `GET /{uid}` - Get user by UID
3. `GET /role/{role}` - Get users by role (admin, moderator)
4. `POST /` - Create new user
   - Body: {email, password, displayName, role}
5. `PUT /{uid}/role` - Update user role
6. `PUT /{uid}/status` - Update user status (active/inactive)
7. `DELETE /{uid}` - Delete user
8. `POST /{uid}/reset-password` - Send password reset email

---

## 13. VideoValidationController
**Base Path:** `/api/admin/videos`
**Access:** Admin/Moderator (based on endpoint)

### Advanced Features:
- **Manual Validation Trigger:** Admins can manually validate video availability
- **Validation Run History:** Track all validation runs with results
- **Video Availability Checking:** Detect and mark unavailable videos
- **Error Tracking:** Record validation errors for analysis
- **Validation Status:** Track status of validation runs (COMPLETED, FAILED, IN_PROGRESS, etc.)
- **Batch Validation:** Limit videos checked (1-1000 per run)

### Endpoints:
1. `POST /validate` - Trigger manual video validation
   - Query param: maxVideos (1-1000)
   - Response: {success, message, data: ValidationRun}
2. `GET /validation-status/{runId}` - Get validation run status (admin/mod)
3. `GET /validation-history` - Get validation run history (admin/mod)
   - Query param: limit (1-100)
4. `GET /validation-latest` - Get latest validation run (admin/mod)

---

## 14. YouTubeSearchController
**Base Path:** `/api/admin/youtube`
**Access:** Admin/Moderator only

### Advanced Features:
- **Unified Search:** Single API call returning mixed content types (FAST!)
- **Paginated Search:** Support for pagination with nextPageToken
- **Type-Specific Search:** Dedicated endpoints for channels, playlists, videos
- **Enriched Metadata:** Enhanced search results with YouTube details
- **Channel Videos:** Get videos from specific channel (with optional search filter)
- **Channel Playlists:** Get playlists from specific channel
- **Playlist Videos:** Get videos in playlist (with optional search filter)
- **Existing Content Detection:** Check which YouTube IDs already in registry
- **Detailed Content Info:** Get full details for channels, playlists, videos

### Endpoints:
1. `GET /search/unified` - Unified search (mixed results, fast)
   - Query param: query
2. `GET /search/all` - Search all types with pagination
   - Query params: query, pageToken
   - Response: {results[], nextPageToken}
3. `GET /search/channels` - Search channels only
4. `GET /search/playlists` - Search playlists only
5. `GET /search/videos` - Search videos only
6. `POST /check-existing` - Check which YouTube IDs already exist
   - Body: {channelIds[], playlistIds[], videoIds[]}
   - Response: {existingChannels, existingPlaylists, existingVideos}
7. `GET /channels/{channelId}` - Get channel details
8. `GET /channels/{channelId}/videos` - Get videos from channel
   - Query params: pageToken, q (search filter)
9. `GET /channels/{channelId}/playlists` - Get playlists from channel
   - Query param: pageToken
10. `GET /playlists/{playlistId}` - Get playlist details
11. `GET /playlists/{playlistId}/videos` - Get videos in playlist
    - Query params: pageToken, q (search filter)
12. `GET /videos/{videoId}` - Get video details

---

## Advanced Feature Categories

### 1. Bulk Operations (ContentLibraryController)
- Firestore WriteBatch for atomic transactional updates
- Max 500 items per batch with automatic chunking
- Four bulk operations: approve, reject, delete, assign-categories
- Request validation and error handling
- Success count + detailed error reporting

### 2. Import/Export (ImportExportController)
- Two complete format systems (full + simple)
- Validation before import (dry-run)
- Merge strategies for conflict resolution
- Selective content inclusion
- Type-specific export endpoints
- File download with proper headers

### 3. Filtering & Search
- Multi-dimensional filtering (ContentLibraryController, PublicContentController)
- Full-text search support
- Category-based filtering
- Status-based filtering
- Type-based filtering
- Date range filtering (PublicContentController)
- Video length filtering (PublicContentController)

### 4. Exclusions Management
- Channel content exclusions (5 types: video, playlist, livestream, short, post)
- Playlist video exclusions
- Add/remove individual exclusions
- Bulk exclusion updates
- Validation for YouTube ID format

### 5. Pagination
- Cursor-based pagination (ApprovalController, PublicContentController) - memory efficient
- Offset-based pagination (ContentLibraryController) - traditional
- Next page token support (YouTubeSearchController) - YouTube API style

### 6. Caching Strategy
- Caffeine (dev) / Redis (prod) support
- Named cache keys for granular control
- Automatic cache eviction on mutations
- 1-hour TTL for categories
- 15-minute TTL for channels/other content

### 7. Audit & Compliance
- Complete audit trail of all actions
- Actor-based audit queries
- Entity-type based audit queries
- Action-based audit queries
- Audit logging on critical operations

### 8. Validation & Data Integrity
- Circular reference detection (categories)
- Duplicate prevention (by YouTube ID)
- Parent existence validation
- Descendant cycle prevention
- Batch validation before import
- Request structure validation
- Status-based approval flows

### 9. Security & Access Control
- Role-based endpoint access (ADMIN vs MODERATOR)
- Granular permission levels
- Firebase Auth integration
- Custom claims for roles
- Endpoint-level @PreAuthorize annotations

---

## Statistics

| Category | Count |
|----------|-------|
| Controllers | 14 |
| Total Endpoints | 67+ |
| Bulk Operations | 4 |
| Export Formats | 2 (full + simple) |
| Exclusion Types | 5 |
| Filter Dimensions | 6+ |
| Cache Strategies | 2 |
| User Roles | 2 |
| Content Types | 3 |
| Validation Features | 4 |

---

## Key Implementation Patterns

1. **Repository Pattern:** All data access through specialized repositories
2. **Service Layer:** Business logic separated from controllers
3. **DTO Pattern:** Consistent request/response objects
4. **Error Handling:** Structured error responses with meaningful messages
5. **Logging:** Comprehensive logging at controller and service levels
6. **Validation:** Pre-validation in controllers, deep validation in services
7. **Async/Await:** ExecutionException, InterruptedException handling for async Firestore
8. **Transactional Safety:** WriteBatch for multi-item operations
9. **Caching:** @Cacheable and @CacheEvict annotations
10. **Audit Trail:** AuditLogService for compliance tracking

---

## Performance Optimizations

1. **Database Query Optimization:** Count operations instead of fetching all records
2. **Pagination:** Cursor-based (efficient) and offset-based pagination options
3. **Caching:** Multi-level caching with TTL and selective eviction
4. **Batch Operations:** Chunk large operations (max 500/batch for Firestore)
5. **Selective Exports:** Only export needed data types
6. **Request Limiting:** Cap result sets (max 50 for public API)
7. **Compression:** Response compression enabled

---

## Compliance & Audit Features

1. **Complete Audit Trail:** Who did what, when, on which resource
2. **Rejection Reasons:** Structured rejection workflow with categorized reasons
3. **Status Tracking:** All content has approval status
4. **User Management:** Track moderators and admins
5. **Operation Logging:** All CRUD operations logged
6. **Download Analytics:** Track user downloads
7. **Validation History:** Track video availability checks

