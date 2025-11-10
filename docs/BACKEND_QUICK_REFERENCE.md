# Backend API Quick Reference

## Core Endpoints Summary

### Admin Content Management

**Search & Discovery:**
- `GET /api/admin/youtube/search/unified` - Fast mixed search
- `GET /api/admin/youtube/search/all` - Paginated search (all types)
- `GET /api/admin/youtube/search/{channels,playlists,videos}` - Type-specific
- `POST /api/admin/youtube/check-existing` - Check registry for YouTube IDs

**Content Registry (CRUD):**
- `GET|POST /api/admin/registry/{channels,playlists,videos}` - List/Create
- `GET|PUT|PATCH|DELETE /api/admin/registry/{type}/{id}` - Read/Update/Toggle/Delete
- `GET|POST|DELETE /api/admin/registry/{channels|playlists}/{id}/exclusions/{id}` - Manage exclusions

**Content Library (Bulk Operations):**
- `GET /api/admin/content` - Search all content with filtering
- `POST /api/admin/content/bulk/{approve,reject,delete}` - Transactional bulk ops
- `POST /api/admin/content/bulk/assign-categories` - Bulk category assignment

**Approval Workflow:**
- `GET /api/admin/approvals/pending` - List pending items (filtered, paginated)
- `POST /api/admin/approvals/{id}/{approve,reject}` - Approve/reject with reasons

**Categories:**
- `GET /api/admin/categories` - All categories (hierarchical, cached)
- `GET /api/admin/categories/top-level` - Root categories only
- `GET /api/admin/categories/{id}/subcategories` - Get children
- `POST|PUT|DELETE /api/admin/categories/{id}` - CRUD (with validation)

**Channels (Approval Flow):**
- `GET /api/admin/channels` - All channels (optional status filter)
- `POST|PUT|DELETE /api/admin/channels/{id}` - CRUD
- `PUT /api/admin/channels/{id}/{approve,reject}` - Approve/reject
- `GET|PUT|POST|DELETE /api/admin/channels/{id}/exclusions/{type}/{id}` - Manage exclusions (5 types)

**Import/Export:**
- `GET /api/admin/import-export/export{/simple}` - Full or simple export
- `GET /api/admin/import-export/export/{categories,channels,playlists,videos}` - Type-specific
- `POST /api/admin/import-export/import{/simple}{/validate}` - Import or validate (dry-run)

**Admin Users:**
- `GET|POST /api/admin/users` - List/Create
- `GET|PUT|DELETE /api/admin/users/{uid}` - Read/Update/Delete
- `GET /api/admin/users/role/{role}` - Filter by role
- `PUT /api/admin/users/{uid}/{role,status}` - Change role/status
- `POST /api/admin/users/{uid}/reset-password` - Send reset email

**Dashboard & Analytics:**
- `GET /api/admin/dashboard` - Metrics & stats
- `GET /api/admin/dashboard/stats/by-category` - Category breakdown

**Audit Log:**
- `GET /api/admin/audit` - Recent logs
- `GET /api/admin/audit/{actor}/{actorUid}` - By who
- `GET /api/admin/audit/entity-type/{type}` - By what
- `GET /api/admin/audit/action/{action}` - By action

**Video Validation:**
- `POST /api/admin/videos/validate` - Trigger validation
- `GET /api/admin/videos/validation-{status,history,latest}` - Check validation

### Public API (Mobile App)

**Content Discovery:**
- `GET /api/v1/content` - Paginated content (cursor-based, 6 filters)
- `GET /api/v1/categories` - Category tree (cached)
- `GET /api/v1/{channels,playlists}/{id}` - Details with nested content
- `GET /api/v1/search` - Full-text search

**Player:**
- `GET /api/player/next-up/{videoId}` - Next-up recommendations

**Downloads:**
- `GET /api/downloads/policy/{videoId}` - Check download eligibility
- `POST /api/downloads/token/{videoId}` - Generate download token
- `GET /api/downloads/manifest/{videoId}` - Get download URLs
- `POST /api/downloads/analytics/{download-started,download-completed,download-failed}` - Track usage

---

## Advanced Features by Topic

### Bulk Operations
- Max 500 items per Firestore batch
- Automatic chunking for larger sets
- Atomic transaction guarantees
- Four operations: approve, reject, delete, assign-categories
- Comprehensive error reporting

### Filtering & Search
- **Content Types:** channel, playlist, video, home (mixed)
- **Status:** all, approved, pending, rejected
- **Category:** by ID or slug
- **Full-Text:** search title + description
- **Video Length:** short, medium, long
- **Date Range:** last 24h, 7d, 30d
- **Sorting:** newest, oldest, popular

### Exclusions
- Channel: 5 types (video, playlist, livestream, short, post)
- Playlist: video IDs
- Add/remove individual or bulk
- YouTube ID validation

### Pagination
- **Cursor-based:** /pending, /content (public) - memory efficient
- **Offset-based:** /admin/content - traditional page numbers
- **Next Page Token:** YouTube style - /search endpoints

### Import/Export Formats
- **Full:** Complete entity structure, merge strategies, validation
- **Simple:** Compact {id: "Title|Cat1,Cat2"} format

### Caching (Caffeine/Redis)
- Categories: 1 hour
- Channels by category: 15 minutes
- Auto-evict on mutations

### Audit Trail
- Filter by: actor, entity type, action
- Logs all CRUD + approvals
- Tracks who, what, when
- Configurable result limit

---

## Key Request/Response Patterns

### Bulk Operation Request
```json
{
  "items": [
    {"type": "channel|playlist|video", "id": "..."},
    ...
  ]
}
```
Response: `{successCount: N, errors: [...]}`

### Bulk Category Assignment
```json
{
  "items": [{type, id}, ...],
  "categoryIds": ["cat1", "cat2", ...]
}
```

### Search Filters
```
?types=channel,playlist,video
&status=approved
&category=cat-id
&search=query
&sort=newest|oldest
&page=0
&size=20
```

### Exclusion Management
```
POST /api/admin/channels/{id}/exclusions/{type}/{youtubeId}
Types: video, playlist, livestream, short, post
```

### Import/Export Selection
```
?includeCategories=true
&includeChannels=true
&includePlaylists=true
&includeVideos=true
&excludeUnavailableVideos=true
```

---

## Performance Tips

1. **Use cursor pagination** for large result sets (content list)
2. **Bulk operations** instead of multiple single updates
3. **Selective exports** - only include needed types
4. **Check existing** before importing to find duplicates
5. **Limit results** - max 50 for public API, 100+ for admin APIs
6. **Cache categories** - 1 hour TTL (auto-invalidated on changes)
7. **Batch validation** - validate before importing

---

## Error Codes

| Code | Meaning | Common Causes |
|------|---------|---------------|
| 200 | Success | N/A |
| 201 | Created | Resource created successfully |
| 204 | No Content | Delete successful |
| 400 | Bad Request | Invalid params, validation failed |
| 401 | Unauthorized | Missing/invalid auth token |
| 403 | Forbidden | Insufficient permissions |
| 404 | Not Found | Resource doesn't exist |
| 409 | Conflict | Duplicate YouTube ID, circular ref |
| 500 | Server Error | Database error, uncaught exception |

---

## Authentication

All `/api/admin/*` and some `/api/downloads/*` endpoints require:
- Firebase JWT token in Authorization header
- Valid user with ADMIN or MODERATOR role
- Custom claims for role checking

Public endpoints (`/api/v1/*`, `/api/player/*`):
- No authentication required
- Content filtering based on approval status

---

## File Locations

| Feature | Location |
|---------|----------|
| Controllers | `backend/src/main/java/.../controller/` |
| Services | `backend/src/main/java/.../service/` |
| Repositories | `backend/src/main/java/.../repository/` |
| Models | `backend/src/main/java/.../model/` |
| DTOs | `backend/src/main/java/.../dto/` |
| Config | `backend/src/main/resources/application.yml` |

---

## Configuration Keys

```yaml
app.security.cors.allowed-origins: http://localhost:5173
spring.cache.type: caffeine (dev) or redis (prod)
spring.jpa.hibernate.ddl-auto: validate
firebase.credentials: path/to/service-account.json
# youtube.api.key not required - using NewPipeExtractor
```

---

Generated: November 10, 2025
