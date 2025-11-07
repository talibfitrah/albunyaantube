# Exclusions and Content Library Feature Documentation

**Feature Status:** ✅ Complete (Phase 1 & 2)
**Date Completed:** 2025-11-07
**Branch:** `claude/exclusions-content-library-011CUqLa9evW4sMZ1LKgkoDW`
**Latest Commit:** `97cdb6c`

---

## Overview

The Exclusions and Content Library feature provides administrators with fine-grained control over approved content by:

1. **Managing exclusions** - Exclude specific videos or playlists from approved channels
2. **Viewing content details** - Drill down into channels and playlists with infinite scroll
3. **Bulk content management** - Approve, reject, or delete multiple items at once
4. **Multi-language support** - Full i18n for English, Arabic (RTL), and Dutch

---

## Architecture

### Backend Components

#### Controllers

**ChannelController** (`backend/src/main/java/com/albunyaan/tube/controller/ChannelController.java`)
- `GET /api/admin/channels/{id}/exclusions` - Get all exclusions for a channel
- `POST /api/admin/channels/{id}/exclusions/{type}/{youtubeId}` - Add exclusion (video or playlist)
- `DELETE /api/admin/channels/{id}/exclusions/{type}/{youtubeId}` - Remove exclusion

**RegistryController** (`backend/src/main/java/com/albunyaan/tube/controller/RegistryController.java`)
- `GET /api/admin/registry/playlists/{id}/exclusions` - Get playlist video exclusions
- `POST /api/admin/registry/playlists/{id}/exclusions/{videoId}` - Exclude video from playlist
- `DELETE /api/admin/registry/playlists/{id}/exclusions/{videoId}` - Remove video exclusion

**YouTubeSearchController** (Enhanced)
- `GET /api/admin/youtube/channels/{channelId}/videos?q={searchQuery}&pageToken={token}` - Search channel videos
- `GET /api/admin/youtube/channels/{channelId}/playlists?pageToken={token}` - Get channel playlists
- `GET /api/admin/youtube/playlists/{playlistId}/videos?q={searchQuery}&pageToken={token}` - Search playlist videos

**ContentLibraryController** (New)
- `POST /api/admin/content/bulk/approve` - Bulk approve content items
- `POST /api/admin/content/bulk/reject` - Bulk reject content items
- `POST /api/admin/content/bulk/delete` - Bulk delete content items
- `POST /api/admin/content/bulk/assign-categories` - Bulk assign categories

#### Services

**YouTubeService** (Enhanced)
- Added search parameter support for `getChannelVideos()` and `getPlaylistVideos()`
- Maintains YouTube Data API v3 quota efficiency
- Returns paginated results with `nextPageToken`

**Security**
- All endpoints require `ADMIN` role via `@PreAuthorize("hasRole('ADMIN')")`
- YouTube ID validation prevents injection attacks
- Firestore document ID sanitization

### Frontend Components

#### New Components

**ChannelDetailModal** (`frontend/src/components/exclusions/ChannelDetailModal.vue`)
- **Purpose:** Display channel details with tabbed interface for videos and playlists
- **Features:**
  - Two tabs: Videos | Playlists
  - Infinite scroll with 500ms throttling
  - Search within videos (server-side) and playlists (client-side)
  - Add/remove exclusions with optimistic UI updates
  - Loading states and error handling
- **Props:**
  - `open: boolean` - Modal visibility
  - `channelId: string` - Firestore document ID
  - `channelYoutubeId: string` - YouTube channel ID
- **Events:**
  - `close` - Modal close requested
  - `updated` - Exclusions changed (triggers parent refresh)

**PlaylistDetailModal** (`frontend/src/components/exclusions/PlaylistDetailModal.vue`)
- **Purpose:** Display playlist details with video list
- **Features:**
  - Infinite scroll for playlist videos
  - Search within playlist videos
  - Add/remove video exclusions
  - Loading states and error handling
- **Props:**
  - `open: boolean` - Modal visibility
  - `playlistId: string` - Firestore document ID
  - `playlistYoutubeId: string` - YouTube playlist ID
- **Events:**
  - `close` - Modal close requested
  - `updated` - Exclusions changed

#### Composables

**useInfiniteScroll** (`frontend/src/composables/useInfiniteScroll.ts`)
- **Purpose:** Reusable infinite scroll logic with throttling
- **Options:**
  - `threshold: number` - Pixels from bottom to trigger load (default: 200px)
  - `throttleMs: number` - Throttle delay (default: 500ms)
  - `onLoadMore: () => void` - Callback for loading more items
- **Returns:**
  - `scrollContainerRef: Ref<HTMLElement | null>` - Ref to attach to scroll container
  - `cleanup: () => void` - Manual cleanup function
- **Behavior:**
  - Auto-attaches scroll event listener on mount
  - Auto-cleanup on unmount
  - Prevents duplicate calls during loading

#### Services

**exclusions.ts** (`frontend/src/services/exclusions.ts`)
- `fetchChannelExclusions(channelId)` - Get all exclusions for a channel
- `addChannelExclusion(channelId, type, youtubeId)` - Add channel exclusion
- `removeChannelExclusion(channelId, type, youtubeId)` - Remove channel exclusion
- `fetchPlaylistExclusions(playlistId)` - Get playlist video exclusions
- `addPlaylistExclusion(playlistId, videoId)` - Add playlist video exclusion
- `removePlaylistExclusion(playlistId, videoId)` - Remove playlist video exclusion

**contentLibrary.ts** (`frontend/src/services/contentLibrary.ts`) - NEW
- `bulkApprove(items: BulkActionItem[])` - Approve multiple items
- `bulkReject(items: BulkActionItem[])` - Reject multiple items
- `bulkDelete(items: BulkActionItem[])` - Delete multiple items
- `bulkAssignCategories(items, categoryIds)` - Assign categories to multiple items
- Returns `BulkActionResponse` with `successCount` and `errors[]`

**youtubeService.ts** (Enhanced)
- `getChannelVideos(channelId, pageToken?, searchQuery?)` - Get/search channel videos
- `getChannelPlaylists(channelId, pageToken?)` - Get channel playlists
- `getPlaylistDetails(playlistId)` - Get playlist metadata
- `getPlaylistVideos(playlistId, pageToken?, searchQuery?)` - Get/search playlist videos

#### Views

**ContentLibraryView** (Enhanced - `frontend/src/views/ContentLibraryView.vue`)
- **Modal Integration:**
  - Opens ChannelDetailModal for channel content
  - Opens PlaylistDetailModal for playlist content
  - Refreshes content list after exclusion changes
- **Bulk Actions:**
  - Select multiple items via checkboxes
  - Bulk approve (calls `bulkApprove()`)
  - Bulk reject (calls `bulkReject()`)
  - Bulk delete (calls `bulkDelete()` with confirmation)
  - Clear selection after successful operations
  - Error handling with user feedback

---

## Data Flow

### Exclusion Management Flow

```
1. User opens ContentLibraryView
2. Clicks "View Details" on a channel
3. ChannelDetailModal opens
   ↓
4. Modal fetches channel metadata (youtubeService.getChannelDetails)
5. Modal fetches existing exclusions (exclusions.fetchChannelExclusions)
6. Modal fetches channel videos (youtubeService.getChannelVideos)
   ↓
7. User clicks "Exclude" on a video
8. Frontend calls exclusions.addChannelExclusion()
   ↓
9. Backend validates ADMIN role
10. Backend saves exclusion to Firestore: channels/{id}/excludedItems
11. Backend returns success
   ↓
12. Frontend updates UI optimistically (button → "Remove Exclusion")
13. Modal emits "updated" event
14. ContentLibraryView refreshes content list
```

### Bulk Action Flow

```
1. User selects multiple items in ContentLibraryView (checkboxes)
2. Clicks "Bulk Actions" → "Approve Selected"
   ↓
3. Frontend maps selected IDs to BulkActionItem[] with types
4. Frontend calls contentLibrary.bulkApprove(items)
   ↓
5. Backend receives POST /api/admin/content/bulk/approve
6. Backend validates ADMIN role
7. Backend processes each item:
   - Fetches document from Firestore
   - Updates approvalMetadata.approved = true
   - Saves document
   - Logs success/error
   ↓
8. Backend returns BulkActionResponse:
   {
     successCount: 5,
     errors: []
   }
   ↓
9. Frontend shows success alert: "Success - 5 items approved"
10. Frontend refreshes content list
11. Frontend clears selection
12. Frontend closes bulk actions menu
```

### Infinite Scroll Flow

```
1. User scrolls to bottom of video list in ChannelDetailModal
   ↓
2. useInfiniteScroll detects scroll position < threshold (200px)
3. useInfiniteScroll checks throttle (500ms since last call)
4. useInfiniteScroll calls onLoadMore callback
   ↓
5. Modal checks loading state (prevents duplicate calls)
6. Modal calls youtubeService.getChannelVideos(channelId, nextPageToken)
   ↓
7. Backend calls YouTube Data API v3:
   GET https://www.googleapis.com/youtube/v3/search
   ?part=snippet
   &channelId={channelId}
   &maxResults=20
   &pageToken={token}
   &q={searchQuery}  // if searching
   ↓
8. Backend returns:
   {
     items: [...],
     nextPageToken: "CAUQAA"
   }
   ↓
9. Frontend appends items to existing list
10. Frontend updates nextPageToken
11. Frontend sets loading = false
12. User continues scrolling → repeat from step 1
```

---

## API Reference

### Exclusions API

#### Get Channel Exclusions
```http
GET /api/admin/channels/{channelId}/exclusions
Authorization: Bearer {firebaseToken}
```

**Response:**
```json
{
  "videos": ["video123", "video456"],
  "playlists": ["playlist789"],
  "totalExcludedCount": 3
}
```

#### Add Channel Exclusion
```http
POST /api/admin/channels/{channelId}/exclusions/{type}/{youtubeId}
Authorization: Bearer {firebaseToken}
```

**Path Parameters:**
- `channelId`: Firestore document ID
- `type`: `video` or `playlist`
- `youtubeId`: YouTube ID to exclude

**Response:**
```json
{
  "success": true,
  "message": "Exclusion added successfully"
}
```

#### Remove Channel Exclusion
```http
DELETE /api/admin/channels/{channelId}/exclusions/{type}/{youtubeId}
Authorization: Bearer {firebaseToken}
```

**Response:**
```json
{
  "success": true,
  "message": "Exclusion removed successfully"
}
```

### Bulk Actions API

#### Bulk Approve
```http
POST /api/admin/content/bulk/approve
Authorization: Bearer {firebaseToken}
Content-Type: application/json
```

**Request Body:**
```json
[
  {"type": "channel", "id": "doc123"},
  {"type": "playlist", "id": "doc456"},
  {"type": "video", "id": "doc789"}
]
```

**Response:**
```json
{
  "successCount": 3,
  "errors": []
}
```

**Error Response:**
```json
{
  "successCount": 2,
  "errors": [
    "Failed to approve video doc789: Document not found"
  ]
}
```

---

## Internationalization

### Translation Keys

All translation keys are nested under their respective sections in `frontend/src/locales/messages.ts`:

#### exclusions.channelDetail
- `title` - "Channel Details"
- `subscribers` - "{count} subscribers"
- `videos` - "{count} videos"
- `tabs.videos` - "Videos"
- `tabs.playlists` - "Playlists"
- `search` - "Search within channel..."
- `exclude` - "Exclude"
- `removeExclusion` - "Remove Exclusion"
- `loadingMore` - "Loading more..."
- `noResults` - "No results found"
- `noMoreItems` - "No more items"
- `excludeError` - "Error adding exclusion"
- `removeError` - "Error removing exclusion"
- `itemCount` - "{count} items"

#### exclusions.playlistDetail
- `title` - "Playlist Details"
- `totalVideos` - "{count} videos"
- `search` - "Search within playlist..."
- (Same action keys as channelDetail)

#### contentLibrary
- `success` - "Success"
- `itemsApproved` - "items approved"
- `itemsRejected` - "items rejected"
- `itemsDeleted` - "items deleted"
- `errorBulkAction` - "Error performing bulk action"
- (70+ additional keys for filters, columns, etc.)

#### common
- `loading` - "Loading..."
- `error` - "An error occurred"
- `retry` - "Retry"
- `close` - "Close"

### RTL Support (Arabic)

The Arabic locale includes full RTL (right-to-left) support:

- Modal layout flips to RTL
- Scroll direction reverses
- Text alignment: right-aligned
- Checkboxes align to right
- All UI elements mirror properly

**Testing RTL:**
1. Set locale to `ar` in preferences
2. Verify `document.dir = 'rtl'`
3. Check modal opens from right
4. Verify text flows right-to-left

---

## Performance Optimizations

### Infinite Scroll Throttling

**Problem:** Rapid scrolling causes excessive API calls
**Solution:** `useInfiniteScroll` composable with 500ms throttle

```typescript
// Only calls onLoadMore every 500ms maximum
const throttledScroll = throttle(() => {
  if (scrollPosition < threshold && !loading) {
    onLoadMore();
  }
}, 500);
```

### Search Debouncing

**Problem:** Search API called on every keystroke
**Solution:** 500ms debounce delay

```typescript
const debouncedSearch = debounce((query: string) => {
  performSearch(query);
}, 500);
```

### Loading State Management

**Problem:** Duplicate API calls while data is loading
**Solution:** Loading flag prevents concurrent requests

```typescript
async function loadMore() {
  if (loading.value || !nextPageToken.value) return;
  loading.value = true;
  try {
    const result = await fetchData(nextPageToken.value);
    items.value.push(...result.items);
    nextPageToken.value = result.nextPageToken;
  } finally {
    loading.value = false;
  }
}
```

### Client-Side Playlist Search

**Problem:** YouTube API doesn't support search within playlists
**Solution:** Client-side filtering of loaded results

```typescript
const filteredPlaylists = computed(() => {
  if (!searchQuery.value) return allPlaylists.value;
  const query = searchQuery.value.toLowerCase();
  return allPlaylists.value.filter(p =>
    p.title.toLowerCase().includes(query)
  );
});
```

---

## Security Considerations

### Role-Based Access Control

All endpoints require ADMIN role:
```java
@PreAuthorize("hasRole('ADMIN')")
@PostMapping("/channels/{id}/exclusions/{type}/{youtubeId}")
```

### Input Validation

YouTube IDs validated against pattern:
```java
if (!youtubeId.matches("^[a-zA-Z0-9_-]+$")) {
  throw new IllegalArgumentException("Invalid YouTube ID");
}
```

### Firestore Security

Document IDs sanitized before Firestore operations:
```java
String sanitizedId = channelId.replaceAll("[^a-zA-Z0-9_-]", "");
DocumentReference docRef = db.collection("channels").document(sanitizedId);
```

### XSS Prevention

All user input sanitized in Vue templates:
```vue
<!-- Automatically escaped by Vue -->
<div>{{ channel.title }}</div>
```

---

## Testing

### Automated Testing

**Backend:**
- Unit tests for controllers (MockMvc)
- Service layer tests with mocked Firestore
- Integration tests with Firebase Emulator

**Frontend:**
- Component tests with Vitest + Testing Library
- Service tests with mocked axios
- E2E tests with Playwright (pending)

### Manual Testing

See [PHASE2_TESTING_CHECKLIST.md](../testing/PHASE2_TESTING_CHECKLIST.md) for comprehensive manual testing guide.

**Key Test Scenarios:**
1. Open channel modal → verify tabs, search, infinite scroll
2. Add/remove exclusions → verify persistence
3. Bulk approve/reject/delete → verify success messages
4. Switch locales → verify translations (en, ar, nl)
5. Test error handling → stop backend, verify error messages
6. Performance test → rapid scrolling, verify throttling

---

## Known Limitations

1. **Playlist Search** - Client-side only (YouTube API limitation)
   - Impact: Only searches loaded items, not entire playlist
   - Workaround: Scroll to load more items before searching

2. **YouTube API Quota** - 10,000 units per day
   - Channel videos: 100 units per request
   - Playlist items: 1 unit per request
   - Mitigation: Backend caching (Caffeine/Redis) reduces quota usage

3. **Bulk Action Size** - No hard limit, but large operations may timeout
   - Recommendation: Limit to 50 items per bulk action
   - Future: Implement batch processing with progress indicator

4. **Modal Accessibility** - Basic keyboard support
   - Tab navigation works
   - Missing: Focus trap, Escape key handling
   - Future: Implement full WCAG 2.1 AA compliance

---

## Future Enhancements

### Phase 3 (Planned)

1. **Advanced Filtering**
   - Filter exclusions by reason
   - Filter by exclusion date
   - Export exclusions to CSV

2. **Batch Import**
   - Upload CSV of exclusions
   - Validate before import
   - Show import results

3. **Exclusion Reasons**
   - Add optional reason when excluding
   - Display reason in exclusion list
   - Search/filter by reason

4. **Undo Functionality**
   - Undo last exclusion
   - Undo bulk action
   - Undo history (last 10 actions)

5. **Performance Improvements**
   - Virtual scrolling for large lists (1000+ items)
   - Progressive loading with skeleton screens
   - Optimistic UI for all actions

6. **Accessibility**
   - Full keyboard navigation
   - Screen reader announcements
   - Focus trap in modals
   - ARIA labels for all interactive elements

---

## Troubleshooting

### Modal Doesn't Open

**Symptoms:** Click "View Details" but modal doesn't appear

**Possible Causes:**
1. Modal state not updating
2. JavaScript error in console
3. CSS z-index issue

**Debug Steps:**
```javascript
// Check Vue DevTools
// ContentLibraryView -> data -> channelModalOpen should be true

// Check console for errors
// Look for "TypeError" or "ReferenceError"

// Check computed properties
// selectedItemForModal should contain channel data
```

**Fix:**
```vue
<!-- Ensure teleport target exists -->
<div id="app"></div>

<!-- Modal teleport -->
<Teleport to="body">
  <div v-if="open" class="modal-overlay">...</div>
</Teleport>
```

### Infinite Scroll Doesn't Load More

**Symptoms:** Scroll to bottom but no more items load

**Possible Causes:**
1. `nextPageToken` is null (end of results)
2. Loading state stuck as true
3. Scroll container ref not attached
4. API error not handled

**Debug Steps:**
```javascript
// Check nextPageToken
console.log('nextPageToken:', nextPageToken.value);
// Should be a string or null

// Check loading state
console.log('loading:', loading.value);
// Should be false when idle

// Check scroll container ref
console.log('scrollContainer:', scrollContainerRef.value);
// Should be HTMLDivElement
```

**Fix:**
```typescript
// Ensure loading is always reset
try {
  await loadMore();
} finally {
  loading.value = false;  // Always reset
}

// Ensure ref is attached
<div ref="scrollContainerRef" class="scroll-container">
```

### Bulk Actions Fail Silently

**Symptoms:** Click bulk approve but nothing happens

**Possible Causes:**
1. No items selected
2. API endpoint returns error
3. Error not displayed to user

**Debug Steps:**
```javascript
// Check selected items
console.log('selectedItems:', selectedItems.value);
// Should be array of IDs

// Check API response
// Network tab -> POST /api/admin/content/bulk/approve
// Check status code and response body
```

**Fix:**
```typescript
// Add error handling
try {
  const result = await bulkApprove(items);
  alert(`Success - ${result.successCount} items approved`);
} catch (err) {
  alert(`Error: ${err.message}`);  // Show error to user
}
```

### Translations Missing

**Symptoms:** UI shows translation keys instead of text (e.g., `exclusions.channelDetail.title`)

**Possible Causes:**
1. Translation key not defined in messages.ts
2. Wrong locale selected
3. i18n plugin not initialized

**Debug Steps:**
```javascript
// Check current locale
console.log($i18n.locale);
// Should be 'en', 'ar', or 'nl'

// Check translation exists
console.log($i18n.messages.en.exclusions.channelDetail.title);
// Should be "Channel Details"
```

**Fix:**
```typescript
// Ensure key exists in all locales
export default {
  en: {
    exclusions: {
      channelDetail: {
        title: 'Channel Details'
      }
    }
  },
  ar: {
    exclusions: {
      channelDetail: {
        title: 'تفاصيل القناة'
      }
    }
  }
}
```

---

## Changelog

### Version 1.0.0 (2025-11-07) - Phase 1 & 2 Complete

**Phase 1:**
- ✅ Backend exclusion endpoints (Channel & Playlist)
- ✅ YouTube API enhancements (search support)
- ✅ Bulk action endpoints (approve, reject, delete, assign categories)
- ✅ ChannelDetailModal component
- ✅ PlaylistDetailModal component
- ✅ useInfiniteScroll composable
- ✅ Exclusion service implementation
- ✅ YouTube service enhancements

**Phase 2:**
- ✅ Modal integration in ContentLibraryView
- ✅ Content library service (bulk actions)
- ✅ Bulk action handlers in ContentLibraryView
- ✅ Complete i18n translations (en, ar, nl)
- ✅ Comprehensive testing checklist
- ✅ Feature documentation

**Commits:**
- `2953a6e` - [FEAT]: Implement exclusions and content library bulk actions (Phase 1)
- `97cdb6c` - [FEAT]: Complete Phase 2 - Wire modals and implement bulk actions

---

## Contributing

When working on this feature:

1. **Backend Changes:**
   - Add tests for new endpoints
   - Update OpenAPI spec
   - Run `./gradlew test` before committing

2. **Frontend Changes:**
   - Add component tests
   - Update i18n for all 3 locales
   - Run `npm test` before committing

3. **Documentation:**
   - Update this file with new features
   - Update testing checklist
   - Add troubleshooting for new issues

4. **Pull Requests:**
   - Reference issue number
   - Include screenshots for UI changes
   - Add testing notes

---

## References

- [YouTube Data API v3](https://developers.google.com/youtube/v3)
- [Firebase Firestore](https://firebase.google.com/docs/firestore)
- [Vue 3 Composition API](https://vuejs.org/guide/extras/composition-api-faq.html)
- [Vue i18n](https://vue-i18n.intlify.dev/)
- [Testing Checklist](../testing/PHASE2_TESTING_CHECKLIST.md)
- [Project Status](../PROJECT_STATUS.md)
