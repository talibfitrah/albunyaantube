# Implementation Plan: Exclusions & Content Library Finalization

## Overview

Finalize the exclusions management and content library features in the frontend with a focus on simplicity and user experience.

## Phase 1: Backend API Endpoints

### 1.1 Channel Exclusions API

**File:** `backend/src/main/java/com/albunyaan/tube/controller/ChannelController.java`

Add the following endpoints:
- `GET /api/admin/channels/{id}/exclusions` - Get all exclusions for a channel
- `POST /api/admin/channels/{id}/exclusions/{type}/{youtubeId}` - Add exclusion (type: video, playlist, livestream, short, post)
- `DELETE /api/admin/channels/{id}/exclusions/{type}/{youtubeId}` - Remove exclusion

**Implementation notes:**
- Use existing `Channel.ExcludedItems` model (already has lists for videos, playlists, livestreams, shorts, posts)
- Return the updated `ExcludedItems` object after add/remove operations
- Update `channel.updatedAt` timestamp on changes

### 1.2 Playlist Exclusions API

**File:** `backend/src/main/java/com/albunyaan/tube/controller/RegistryController.java`

Add the following endpoints:
- `GET /api/admin/playlists/{id}/exclusions` - Get excluded video IDs
- `POST /api/admin/playlists/{id}/exclusions/{videoId}` - Add video exclusion
- `DELETE /api/admin/playlists/{id}/exclusions/{videoId}` - Remove video exclusion

**Implementation notes:**
- Use existing `playlist.excludedVideoIds` list
- Return the updated list after operations

### 1.3 YouTube API Enhancements

**File:** `backend/src/main/java/com/albunyaan/tube/service/YouTubeService.java`

Add search support to existing methods:
- `getChannelVideos(channelId, pageToken, searchQuery)` - Overload existing method
- `getPlaylistVideos(playlistId, pageToken, searchQuery)` - Overload existing method

**File:** `backend/src/main/java/com/albunyaan/tube/controller/YouTubeSearchController.java`

Update existing endpoints to accept search parameter:
- `GET /api/admin/youtube/channels/{channelId}/videos?pageToken&q` - Add q param
- `GET /api/admin/youtube/playlists/{playlistId}/videos?pageToken&q` - Add q param

**Implementation notes:**
- For channels: Pass `q` parameter directly to YouTube API (server-side search)
- For playlists: Client-side filtering since YouTube API doesn't support playlist search
- Keep backward compatibility by making `q` parameter optional

## Phase 2: Frontend - Shared Composables

### 2.1 Infinite Scroll Composable

**File:** `frontend/src/composables/useInfiniteScroll.ts` (new file)

```typescript
interface InfiniteScrollOptions {
  threshold?: number;        // Distance from bottom (default: 200px)
  throttleMs?: number;       // Throttle delay (default: 500ms)
  onLoadMore: () => void;    // Callback when scroll triggers
}

interface InfiniteScrollReturn {
  containerRef: Ref<HTMLElement | null>;
  isLoading: Ref<boolean>;
  hasMore: Ref<boolean>;
}
```

**Features:**
- Detect scroll to bottom with configurable threshold
- Throttle scroll events to prevent excessive API calls
- Track loading state to prevent duplicate requests
- Expose container ref for attaching to scroll container

**Implementation approach:**
- Use `useThrottleFn` from VueUse or implement simple throttle
- Add scroll event listener on container
- Calculate: `scrollTop + clientHeight >= scrollHeight - threshold`
- Only trigger if `!isLoading.value && hasMore.value`

## Phase 3: Frontend - Detail Modals

### 3.1 Channel Detail Modal

**File:** `frontend/src/components/exclusions/ChannelDetailModal.vue` (new file)

**UI Structure:**
```
┌─────────────────────────────────────────┐
│ [Channel Thumbnail] Channel Name        │
│ Subscribers: X | Videos: Y              │
├─────────────────────────────────────────┤
│ [Tabs: Videos | Playlists]              │
├─────────────────────────────────────────┤
│ [Search: _______________] [🔍]          │
├─────────────────────────────────────────┤
│ ┌───────────────────────────────────┐   │
│ │ [Thumbnail] Video Title           │   │
│ │ Published: Date | [Exclude]       │   │
│ ├───────────────────────────────────┤   │
│ │ [Thumbnail] Video Title           │   │
│ │ Published: Date | [Remove Exclusion]  │
│ │                ...                │   │
│ │         [Loading more...]         │   │
│ └───────────────────────────────────┘   │
│                                         │
│                    [Close]              │
└─────────────────────────────────────────┘
```

**Props:**
```typescript
interface Props {
  channelId: string;
  channelYoutubeId: string;
  open: boolean;
}
```

**Features:**
- Header: Show channel thumbnail, name, subscriber count, video count
- Tabs: "Videos" and "Playlists" tabs
- Search bar: Input with debounce (500ms), search button
- Infinite scroll: Load more on scroll bottom (500ms throttle)
- Item display: Thumbnail, title, published date, exclude/remove button
- Excluded items: Show with different styling (grayed out) and "Remove Exclusion" button
- Loading states: Spinner while fetching, "Load more" indicator at bottom

**API Calls:**
- Fetch channel details: `GET /api/admin/youtube/channels/{youtubeId}`
- Fetch videos: `GET /api/admin/youtube/channels/{youtubeId}/videos?pageToken&q`
- Fetch playlists: `GET /api/admin/youtube/channels/{channelId}/playlists?pageToken`
- Get exclusions: `GET /api/admin/channels/{id}/exclusions`
- Add exclusion: `POST /api/admin/channels/{id}/exclusions/{type}/{youtubeId}`
- Remove exclusion: `DELETE /api/admin/channels/{id}/exclusions/{type}/{youtubeId}`

**State Management:**
```typescript
const activeTab = ref<'videos' | 'playlists'>('videos')
const searchQuery = ref('')
const items = ref<any[]>([])
const nextPageToken = ref<string | null>(null)
const excludedIds = ref<string[]>([])
const { containerRef, isLoading, hasMore } = useInfiniteScroll({
  onLoadMore: () => loadMore()
})
```

### 3.2 Playlist Detail Modal

**File:** `frontend/src/components/exclusions/PlaylistDetailModal.vue` (new file)

**UI Structure:**
```
┌─────────────────────────────────────────┐
│ [Playlist Thumbnail] Playlist Name      │
│ Total Videos: X                         │
├─────────────────────────────────────────┤
│ [Search: _______________] [🔍]          │
├─────────────────────────────────────────┤
│ ┌───────────────────────────────────┐   │
│ │ [Thumbnail] Video Title           │   │
│ │ Position: #N | [Exclude]          │   │
│ ├───────────────────────────────────┤   │
│ │ [Thumbnail] Video Title           │   │
│ │ Position: #N | [Remove Exclusion] │   │
│ │                ...                │   │
│ │         [Loading more...]         │   │
│ └───────────────────────────────────┘   │
│                                         │
│                    [Close]              │
└─────────────────────────────────────────┘
```

**Props:**
```typescript
interface Props {
  playlistId: string;
  playlistYoutubeId: string;
  open: boolean;
}
```

**Features:**
- Header: Show playlist thumbnail, title, total video count
- Search bar: Input with debounce (500ms), search button
- Infinite scroll: Load more on scroll bottom (500ms throttle)
- Item display: Video thumbnail, title, position in playlist, exclude/remove button
- Excluded items: Show with different styling and "Remove Exclusion" button
- Loading states: Same as channel modal

**API Calls:**
- Fetch playlist details: `GET /api/admin/youtube/playlists/{youtubeId}`
- Fetch videos: `GET /api/admin/youtube/playlists/{youtubeId}/videos?pageToken&q`
- Get exclusions: `GET /api/admin/playlists/{id}/exclusions`
- Add exclusion: `POST /api/admin/playlists/{id}/exclusions/{videoId}`
- Remove exclusion: `DELETE /api/admin/playlists/{id}/exclusions/{videoId}`

## Phase 4: Frontend - Wire Exclusions View

### 4.1 Update Exclusions Workspace View

**File:** `frontend/src/views/ExclusionsWorkspaceView.vue`

**Changes needed:**
- Add "View Details" button to each row in the table
- Import and register both detail modals
- Add modal state management:
```typescript
const channelModalOpen = ref(false)
const playlistModalOpen = ref(false)
const selectedItem = ref<{id: string, youtubeId: string} | null>(null)
```
- Wire "View Details" button click:
  - For channels: Open `ChannelDetailModal` with channel ID and YouTube ID
  - For playlists: Open `PlaylistDetailModal` with playlist ID and YouTube ID
- After modal closes and exclusions change: Refresh current page data

### 4.2 Update Exclusions Service

**File:** `frontend/src/services/exclusions.ts`

Replace stub implementations with real API calls:

```typescript
export async function fetchChannelExclusions(channelId: string) {
  return api.get(`/admin/channels/${channelId}/exclusions`)
}

export async function addChannelExclusion(channelId: string, type: string, youtubeId: string) {
  return api.post(`/admin/channels/${channelId}/exclusions/${type}/${youtubeId}`)
}

export async function removeChannelExclusion(channelId: string, type: string, youtubeId: string) {
  return api.delete(`/admin/channels/${channelId}/exclusions/${type}/${youtubeId}`)
}

export async function fetchPlaylistExclusions(playlistId: string) {
  return api.get(`/admin/playlists/${playlistId}/exclusions`)
}

export async function addPlaylistExclusion(playlistId: string, videoId: string) {
  return api.post(`/admin/playlists/${playlistId}/exclusions/${videoId}`)
}

export async function removePlaylistExclusion(playlistId: string, videoId: string) {
  return api.delete(`/admin/playlists/${playlistId}/exclusions/${videoId}`)
}
```

## Phase 5: Frontend - Content Library Bulk Actions

### 5.1 Update Content Library View

**File:** `frontend/src/views/ContentLibraryView.vue`

Replace console.log stubs with real implementations:

**Current stubs (lines 513-574):**
- `showAssignCategoriesModal()` - Line 513
- `showExclusionsModal()` - Line 517
- `deleteItems()` - Line 521
- `showBulkMenu()` - Line 550
- `bulkApprove()` - Line 553
- `bulkReject()` - Line 556
- `bulkDelete()` - Line 559
- `bulkAssignCategories()` - Line 562
- `exportSelected()` - Line 565

**Implementation approach:**
- Create API service methods for bulk operations
- Show confirmation dialogs for destructive actions (delete, reject)
- Show success/error toast notifications
- Refresh content list after operations complete
- Clear selection after successful operations

**New API endpoints needed (backend):**
- `POST /api/admin/content/bulk/approve` - Bulk approve items
- `POST /api/admin/content/bulk/reject` - Bulk reject items
- `POST /api/admin/content/bulk/delete` - Bulk delete items
- `POST /api/admin/content/bulk/assign-categories` - Bulk category assignment

## Phase 6: Internationalization

### 6.1 Add i18n Messages

**File:** `frontend/src/locales/messages.ts`

Add translations for new UI elements:

```typescript
exclusions: {
  channelDetail: {
    title: 'Channel Details',
    tabs: {
      videos: 'Videos',
      playlists: 'Playlists'
    },
    search: 'Search within channel...',
    exclude: 'Exclude',
    removeExclusion: 'Remove Exclusion',
    loadingMore: 'Loading more...',
    noResults: 'No results found'
  },
  playlistDetail: {
    title: 'Playlist Details',
    search: 'Search within playlist...',
    totalVideos: 'Total Videos'
  }
},
contentLibrary: {
  bulkActions: {
    approve: 'Approve Selected',
    reject: 'Reject Selected',
    delete: 'Delete Selected',
    assignCategories: 'Assign Categories',
    confirmDelete: 'Are you sure you want to delete {count} items?'
  }
}
```

Provide translations for English, Arabic (RTL), and Dutch.

## Phase 7: Testing

### 7.1 Manual Testing Checklist

- [ ] Channel detail modal opens correctly
- [ ] Channel videos load with pagination
- [ ] Channel playlists load with pagination
- [ ] Search within channel videos works
- [ ] Exclude video from channel works
- [ ] Remove video exclusion from channel works
- [ ] Exclude playlist from channel works
- [ ] Playlist detail modal opens correctly
- [ ] Playlist videos load with pagination
- [ ] Search within playlist works
- [ ] Exclude video from playlist works
- [ ] Remove video exclusion from playlist works
- [ ] Infinite scroll triggers at correct threshold
- [ ] Scroll throttling prevents excessive requests
- [ ] Loading states display correctly
- [ ] Content library bulk approve works
- [ ] Content library bulk reject works
- [ ] Content library bulk delete works
- [ ] All i18n translations display correctly
- [ ] RTL layout works for Arabic

### 7.2 Unit Tests

- Add tests for `useInfiniteScroll` composable
- Add tests for `ChannelDetailModal` component
- Add tests for `PlaylistDetailModal` component
- Update tests for `ExclusionsWorkspaceView`
- Add tests for exclusions service methods

## Key Design Decisions

### 1. Modal vs Separate Page

**Decision:** Use modals for detail views

**Rationale:**
- Simpler navigation (no route changes)
- Maintains context (stays in exclusions workspace)
- Faster interaction (no full page load)
- Less code to maintain

### 2. Infinite Scroll Throttling

**Decision:** 500ms throttle + loading state check

**Rationale:**
- Prevents rapid-fire API requests during scroll
- YouTube API has quota limits
- Better user experience (prevents UI jank)
- Loading state prevents duplicate requests

### 3. Search Implementation

**Decision:** Server-side for channels, client-side for playlists

**Rationale:**
- YouTube API supports channel video search natively
- YouTube API does NOT support playlist video search
- Client-side filtering acceptable for playlists (max 20 items per page)

### 4. Scroll Threshold

**Decision:** 200px from bottom

**Rationale:**
- Loads next page before user reaches absolute bottom
- Feels more responsive
- Standard UX pattern

### 5. Simple Code Approach

**Principles:**
- No complex state machines
- Direct API calls (no caching layers)
- Standard Vue composition patterns
- Minimal abstractions
- Clear component boundaries

## Implementation Order

1. Backend APIs (Phase 1) - Foundation for everything
2. Infinite Scroll Composable (Phase 2) - Shared utility
3. Channel Detail Modal (Phase 3.1) - Most complex component
4. Playlist Detail Modal (Phase 3.2) - Similar to channel modal
5. Wire Exclusions View (Phase 4) - Connect everything
6. Content Library Bulk Actions (Phase 5) - Complete the feature
7. i18n (Phase 6) - Add translations
8. Testing (Phase 7) - Verify everything works

## Estimated Effort

| Phase | Estimated Time |
|-------|----------------|
| Backend APIs | 3 hours |
| Infinite Scroll Composable | 1 hour |
| Channel Detail Modal | 4 hours |
| Playlist Detail Modal | 3 hours |
| Wire Exclusions View | 2 hours |
| Content Library Bulk Actions | 3 hours |
| i18n Translations | 1 hour |
| Testing & Polish | 3 hours |
| **Total** | **20 hours** |

## Dependencies

- **Existing:** `YouTubeService` methods for fetching channel/playlist content
- **Existing:** `Channel.ExcludedItems` model structure
- **Existing:** `Playlist.excludedVideoIds` list
- **Existing:** Exclusions workspace UI (just needs wiring)
- **Existing:** Content library UI (just needs bulk action implementations)

## Review Questions & Answers

### 1. Modal Size
**Answer:** Full-screen on mobile for better infinite scroll UX and content visibility. Desktop: 80vw width, centered overlay.

### 2. Search Debounce
**Answer:** 500ms to be more conservative with YouTube API quota (better than 300ms for rate limiting).

### 3. Pagination Size
**Answer:** Keep 20 items - matches YouTube API standard and existing codebase patterns.

### 4. Exclusion Confirmation
**Answer:**
- Single exclusions in modal: No confirmation (easily reversible)
- Bulk operations: Yes, always confirm with count display

### 5. Bulk Action Backend
**Answer:** Yes, implement both backend and frontend for complete functionality.

## Additional Recommendations for Production Quality

### Security
- All endpoints use `@PreAuthorize("hasRole('ADMIN')")`
- Validate YouTube IDs format before API calls
- Rate limiting on YouTube API calls (already cached)

### Performance
- Use existing cache infrastructure (Caffeine/Redis)
- Debounce search queries
- Throttle infinite scroll
- Batch API calls where possible

### Error Handling
- Try-catch all YouTube API calls
- User-friendly error messages
- Retry logic for transient failures
- Loading states prevent duplicate requests

### Future-Proofing
- Composable patterns allow reuse
- TypeScript interfaces prevent type errors
- Audit logging for exclusion changes
- Extensible to other content types
