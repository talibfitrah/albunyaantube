# Phase 0: Discovery and Baseline Audit

> Generated: 2026-02-17 | Branch: `feature/rbac-youtube-split-cleanup`

---

## 1. Role-Feature Permission Matrix

### Roles
- **ADMIN** - Full system access
- **MODERATOR** - Limited admin functions (search, submit, view queues)

### Backend Endpoint Authorization (SecurityConfig.java)

| Endpoint Pattern | ADMIN | MODERATOR | Notes |
|---|:---:|:---:|---|
| `/api/public/**` | Public | Public | No auth |
| `/api/v1/**` | Public | Public | Mobile app APIs |
| `/api/auth/login` (POST) | Public | Public | Login |
| `/actuator/**` | Yes | **No** | Spring actuator |
| `/api/admin/users/**` | Yes | **No** | User management |
| `/api/admin/categories/**` (POST/PUT/DELETE) | Yes | **No** | Category CRUD |
| `/api/admin/categories/**` (GET) | Yes | Yes | Category read (cached, public) |
| `/api/admin/**` (DELETE) | Yes | **No** | All admin delete |
| `/api/admin/**` (GET/POST/PUT) | Yes | Yes | Catch-all for admin |

### Per-Controller Authorization (via @PreAuthorize)

| Controller | Base Path | ADMIN | MODERATOR | Key Gap |
|---|---|:---:|:---:|---|
| PublicContentController | `/api/v1` | Public | Public | - |
| PlayerController | `/api/player` | Public | Public | - |
| DownloadController | `/api/downloads` | Public | Public | - |
| DashboardController | `/api/admin/dashboard` | Yes | Yes | Moderator sees ALL metrics |
| YouTubeSearchController | `/api/admin/youtube` | Yes | Yes | - |
| RegistryController (GET/POST/PUT) | `/api/admin/registry` | Yes | Yes | Moderator submissions NOT scoped to own |
| RegistryController (PATCH toggle/DELETE) | `/api/admin/registry` | Yes | **No** | - |
| ApprovalController | `/api/admin/approvals` | Yes | Yes | Moderator sees ALL pending, can approve/reject |
| ContentLibraryController | `/api/admin/content` | Yes | Yes | Moderator sees ALL content |
| CategoryController (GET) | `/api/admin/categories` | Yes | Yes | Public/cached |
| CategoryController (POST/PUT/DELETE) | `/api/admin/categories` | Yes | **No** | - |
| SortOrderController | `/api/admin/sort` | Yes | **No** | Admin only |
| UserController | `/api/admin/users` | Yes | **No** | Admin only |
| AuditLogController | `/api/admin/audit` | Yes | **No** | Admin only |
| ContentValidationController (trigger) | `/api/admin/content-validation` | Yes | **No** | - |
| ContentValidationController (query) | `/api/admin/content-validation` | Yes | Yes | - |
| VideoValidationController | `/api/admin/videos` | Yes | Mixed | Trigger=admin, query=both |
| ImportExportController | `/api/admin/import-export` | Yes | **No** | Admin only |
| ExclusionsWorkspaceController | `/api/admin/exclusions` | Yes | **No** | Admin only |
| ChannelController | `/api/admin/channels` | Yes | Mixed | CRUD=both, approve/reject/delete=admin |

### Frontend Route Authorization

| Route | Path | ADMIN | MODERATOR | Current Guard |
|---|---|:---:|:---:|---|
| Dashboard | `/` | Yes | Yes | Auth only |
| Content Search | `/content-search` | Yes | Yes | Auth only |
| Categories | `/categories` | Yes | **Should block** | **None** |
| Pending Approvals | `/approvals` | Yes | **Should restrict** | **None** |
| Content Library | `/content-library` | Yes | **Should block** | **None** |
| Content Sorting | `/content-sorting` | Yes | **Should block** | **None** |
| Exclusions | `/exclusions` | Yes | **Should block** | **None** |
| Bulk Import/Export | `/bulk-import-export` | Yes | **Should block** | **None** |
| Video Validation | `/video-validation` | Yes | **Should block** | **None** |
| Archived Content | `/archived-content` | Yes | **Should restrict** | **None** |
| Users | `/users` | Yes | **Should block** | **None** |
| Audit Log | `/audit` | Yes | **Should block** | **None** |
| Activity Log | `/activity` | Yes | **Should block** | **None** |
| Settings: Profile | `/settings/profile` | Yes | Yes | Auth only |
| Settings: Notifications | `/settings/notifications` | Yes | Yes | Auth only |
| Settings: System | `/settings/system` | Yes | **Should block** | **None** |

### CRITICAL GAP: No Frontend Role Enforcement
- **Auth store** (`stores/auth.ts`) stores Firebase User but **no role information**
- **Router** (`router/index.ts`) has only `requiresAuth` checks, **no role-based guards**
- **Navigation** (`constants/navigation.ts`) shows ALL items to ALL authenticated users
- **API client** (`services/api/client.ts`) shows 403 toast but doesn't prevent navigation
- Moderator sees all 19 nav items and can navigate to all pages (backend returns 403 on action)

---

## 2. YouTube Integration Boundary Map

### Current Architecture (Fully Coupled)
```
Frontend Admin Dashboard
  └── youtubeService.ts (14 API methods)
        └── HTTP calls to backend
              └── YouTubeSearchController (14 endpoints)
                    └── YouTubeService (facade)
                          ├── SearchOrchestrator → YouTubeGateway → NewPipe SearchExtractor
                          └── ChannelOrchestrator → YouTubeGateway → NewPipe ChannelInfo/PlaylistInfo/StreamInfo
```

### Frontend → Backend YouTube Endpoints

| Frontend Method | Backend Endpoint | Data Returned |
|---|---|---|
| `searchYouTube(query, 'all')` | `GET /api/admin/youtube/search/all` | EnrichedSearchResult[] with thumbnails, titles, counts |
| `searchYouTube(query, 'channels')` | `GET /api/admin/youtube/search/channels` | Channel search results with metadata |
| `searchYouTube(query, 'playlists')` | `GET /api/admin/youtube/search/playlists` | Playlist search results |
| `searchYouTube(query, 'videos')` | `GET /api/admin/youtube/search/videos` | Video search results |
| `getChannelDetails(id)` | `GET /api/admin/youtube/channels/{id}` | ChannelDetailsDto (name, subs, banner, etc.) |
| `getChannelVideos(id)` | `GET /api/admin/youtube/channels/{id}/videos` | PaginatedResponse<StreamItemDto> |
| `getChannelShorts(id)` | `GET /api/admin/youtube/channels/{id}/shorts` | PaginatedResponse<StreamItemDto> |
| `getChannelLiveStreams(id)` | `GET /api/admin/youtube/channels/{id}/livestreams` | PaginatedResponse<StreamItemDto> |
| `getChannelPlaylists(id)` | `GET /api/admin/youtube/channels/{id}/playlists` | PaginatedResponse<PlaylistItemDto> |
| `getPlaylistDetails(id)` | `GET /api/admin/youtube/playlists/{id}` | PlaylistDetailsDto |
| `getPlaylistVideos(id)` | `GET /api/admin/youtube/playlists/{id}/videos` | PaginatedResponse<StreamItemDto> |
| `getVideoDetails(id)` | `GET /api/admin/youtube/videos/{id}` | StreamDetailsDto |
| `checkExisting(ids)` | `POST /api/admin/youtube/check-existing` | Map<id, exists> |
| `addToPendingApprovals()` | `POST /api/admin/registry/{type}` | Registry item created |

### Backend-Only YouTube Usage (Keep with NewPipe)

| Service | Usage | Keep? |
|---|---|---|
| ContentValidationService | Batch validate channels/playlists/videos exist | Yes |
| ImportExportController (async) | Validate YouTube IDs during import | Yes |
| RegistryController (POST) | Auto-enrich metadata on add | Yes |
| ChannelController (approve) | Fetch latest metadata on approval | Yes |

### Frontend YouTube Search + Browse (Move to YouTube Data API)

| Use Case | Current Flow | Target Flow |
|---|---|---|
| Search content | Frontend → Backend → NewPipe | Frontend → YouTube Data API v3 |
| Browse channel videos | Frontend → Backend → NewPipe | Frontend → YouTube Data API v3 |
| Browse playlists | Frontend → Backend → NewPipe | Frontend → YouTube Data API v3 |
| Display thumbnails | Backend extracts from NewPipe | YouTube thumbnail URLs (direct) |
| View counts/durations | Backend extracts from NewPipe | YouTube Data API v3 response |

### Transactional Payload (Frontend → Backend on Submit)
Current submission payload to `POST /api/admin/registry/{type}`:
```json
{
  "youtubeId": "string",        // Required - YouTube content ID
  "categoryIds": ["string"],    // Required - assigned categories
  "notes": "string"             // Optional - moderator notes
}
```
This is already transactional. Backend re-enriches metadata from NewPipe on approval.

### Rate Limiting & Caching (Backend)
- YouTubeThrottler: Spaces out requests
- YouTubeCircuitBreaker: Detects rate limiting
- Executor pool size: 1 (sequential)
- Cache TTL: 1 hour (Caffeine in dev, Redis in prod)
- 11 cache regions for different data types

---

## 3. Exemplary Code Remediation Backlog

### P0: User-Facing Production Behavior

| # | File | Lines | Issue | Remediation |
|---|---|---|---|---|
| P0-1 | `frontend/src/components/NotificationsPanel.vue` | 44-73 | `loadNotifications()` returns hardcoded mock notifications: "Islamic History 101", "ahmad@example.com". Comment says "Mock data - replace with actual API call" | Replace with real notification API or disable feature |
| P0-2 | `frontend/src/components/GlobalSearchModal.vue` | 265-310 | `performSearch()` returns hardcoded mock results: "Islamic Lectures Channel", "admin@example.com". Comment says "Simulate API search - replace with actual API call" | Replace with real search API or disable feature |

### P1: Internal but Reachable Paths

| # | File | Lines | Issue | Remediation |
|---|---|---|---|---|
| P1-1 | `backend/.../util/FirestoreDataSeeder.java` | 88-660 | Seed profile uses fake YouTube IDs (`UC0000...01`), `via.placeholder.com` thumbnail URLs, `admin@albunyaan.tube` emails. Activated via `--spring.profiles.active=seed` | Only runs under `seed` profile - acceptable for dev. Ensure profile never activates in prod |
| P1-2 | `backend/.../util/DataCleanupSeeder.java` | 1-209 | Cleanup tool to remove fake seeded data. Activated via `--spring.profiles.active=cleanup` | Keep as dev utility. Ensure profile never activates in prod |
| P1-3 | `backend/.../controller/DashboardController.java` | 79-94 | TODO stubs for period comparison values: `0` for previous period, `"FLAT"` for trend. Comments: "TODO: Calculate previous period value" | Implement real period comparison or remove comparison metrics |
| P1-4 | `backend/.../controller/ImportExportController.java` | 658-666 | Failed items use `"Retry Import\|Global"` as placeholder title/categories | Acceptable - used for retry exports of failed items. Low priority |
| P1-5 | `backend/.../service/AuthService.java` | 173-178 | `sendPasswordResetEmail()` generates link but doesn't send. Comment: "TODO: Integrate with email service" | Implement email service or document as known limitation |

### P2: Dead Code or Non-Critical

| # | File | Lines | Issue | Remediation |
|---|---|---|---|---|
| P2-1 | `android/.../data/source/FakeContentService.kt` | All | Full fake content service for Android. Used as fallback/test implementation | Verify not used in production Hilt module. If only in test DI, acceptable |
| P2-2 | `backend/.../scheduler/VideoValidationScheduler.java` | 122-124 | Fallback instance ID with UUID when hostname unavailable | Acceptable defensive coding |

### Summary

| Priority | Count | Action Required |
|---|:---:|---|
| P0 | 2 | Must fix before production - user-facing mock data |
| P1 | 5 | Should fix - reachable paths with stubs/TODOs |
| P2 | 2 | Low priority - acceptable for now |

---

## 4. Key Architectural Findings

### Finding 1: No Frontend RBAC
The frontend has zero role-based access control. All authenticated users see all navigation items and can navigate to all pages. Authorization is purely backend-enforced via 403 responses. This means moderators see admin-only pages and get error toasts when they try to act.

### Finding 2: Moderator Scope Not Enforced
When moderators access the approval queue (`/api/admin/approvals/pending`), they see ALL pending items from ALL users - not just their own submissions. The `ApprovalController` does not filter by submitter. The `RegistryController` similarly shows all items.

### Finding 3: YouTube Integration is Fully Backend-Proxied
All YouTube data flows through the backend via NewPipe. The frontend makes zero direct YouTube API calls. This creates unnecessary backend load for admin search/browse operations that could use the YouTube Data API directly.

### Finding 4: Firebase Custom Claims Already Working
The backend correctly sets `role` in Firebase custom claims (`AuthService.java:92`) and reads it in `FirebaseAuthFilter.java:67`. The frontend could extract role from the ID token's custom claims to enable client-side RBAC, but currently doesn't.

### Finding 5: Auto-Approval for Admins
When an admin adds content via `RegistryController.POST`, it's auto-approved (status=APPROVED). Moderator submissions go to PENDING. This logic is correct but not documented in the frontend.

---

## 5. Recommended Execution Priority

1. **Frontend RBAC** (Phase 1) - Extract role from Firebase token, add route guards and nav filtering
2. **Moderator Submission Scoping** (Phase 2) - Add `submittedBy` filtering to approval/registry queries
3. **Mock Code Removal** (Phase 4, P0 items) - Fix NotificationsPanel and GlobalSearchModal
4. **YouTube Split** (Phase 3) - Move search/browse to YouTube Data API in frontend
5. **Dashboard TODOs** (Phase 4, P1 items) - Fix period comparison stubs
6. **Testing** (Phase 5) - Add RBAC tests, workflow tests
7. **Rollout** (Phase 6) - Feature flags, migration plan
