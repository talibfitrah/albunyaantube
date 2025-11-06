# Phase 2 Remaining Work

## Overview
Phase 1 (Backend APIs + Frontend Infrastructure) is complete and pushed. This document outlines remaining Phase 2 work.

## Tasks Remaining

### 1. Wire Modals to ContentLibraryView

**File:** `frontend/src/views/ContentLibraryView.vue`

**Changes needed:**
```vue
<script setup>
// Add imports
import ChannelDetailModal from '@/components/exclusions/ChannelDetailModal.vue'
import PlaylistDetailModal from '@/components/exclusions/PlaylistDetailModal.vue'

// Add state
const channelModalOpen = ref(false)
const playlistModalOpen = ref(false)
const selectedItemForModal = ref<any>(null)

// Add functions
function openChannelDetails(item: any) {
  selectedItemForModal.value = item
  channelModalOpen.value = true
}

function openPlaylistDetails(item: any) {
  selectedItemForModal.value = item
  playlistModalOpen.value = true
}

function handleModalUpdated() {
  // Refresh content list after exclusion changes
  loadContent()
}
</script>

<template>
  <!-- In the content items loop, add "View Details" button -->
  <button
    v-if="item.type === 'channel'"
    @click="openChannelDetails(item)"
    class="btn-details"
  >
    View Details
  </button>

  <button
    v-if="item.type === 'playlist'"
    @click="openPlaylistDetails(item)"
    class="btn-details"
  >
    View Details
  </button>

  <!-- Add modals at end of template -->
  <ChannelDetailModal
    :open="channelModalOpen"
    :channel-id="selectedItemForModal?.id"
    :channel-youtube-id="selectedItemForModal?.youtubeId"
    @close="channelModalOpen = false"
    @updated="handleModalUpdated"
  />

  <PlaylistDetailModal
    :open="playlistModalOpen"
    :playlist-id="selectedItemForModal?.id"
    :playlist-youtube-id="selectedItemForModal?.youtubeId"
    @close="playlistModalOpen = false"
    @updated="handleModalUpdated"
  />
</template>
```

### 2. Implement Content Library Bulk Actions

**File:** `frontend/src/views/ContentLibraryView.vue`

**Replace console.log stubs (lines 513-574) with:**

```typescript
import { bulkApprove, bulkReject, bulkDelete, bulkAssignCategories } from '@/services/contentLibrary'

async function showAssignCategoriesModal() {
  // Show category selection modal
  // On confirm, call bulkAssignCategories
}

async function showExclusionsModal() {
  console.warn('Exclusions modal not implemented')
}

async function deleteItems() {
  if (!confirm(`Delete ${selectedItems.value.length} items?`)) return

  try {
    const items = selectedItems.value.map(item => ({
      type: item.type,
      id: item.id
    }))

    await bulkDelete(items)
    showToast('Items deleted successfully')
    clearSelection()
    loadContent()
  } catch (error) {
    showToast('Error deleting items', 'error')
  }
}

async function bulkApproveItems() {
  try {
    const items = selectedItems.value.map(item => ({
      type: item.type,
      id: item.id
    }))

    const result = await bulkApprove(items)
    showToast(`Approved ${result.successCount} items`)
    clearSelection()
    loadContent()
  } catch (error) {
    showToast('Error approving items', 'error')
  }
}

async function bulkRejectItems() {
  if (!confirm(`Reject ${selectedItems.value.length} items?`)) return

  try {
    const items = selectedItems.value.map(item => ({
      type: item.type,
      id: item.id
    }))

    const result = await bulkReject(items)
    showToast(`Rejected ${result.successCount} items`)
    clearSelection()
    loadContent()
  } catch (error) {
    showToast('Error rejecting items', 'error')
  }
}

function showToast(message: string, type: 'success' | 'error' = 'success') {
  // Implement toast notification
  // Could use existing notification system or add simple alert for now
  alert(message)
}
```

**Create service file:** `frontend/src/services/contentLibrary.ts`

```typescript
import { authorizedJsonFetch } from '@/services/http'

export interface BulkActionItem {
  type: string
  id: string
}

export interface BulkActionResponse {
  successCount: number
  errors: string[]
}

export async function bulkApprove(items: BulkActionItem[]): Promise<BulkActionResponse> {
  return await authorizedJsonFetch('/admin/content/bulk/approve', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ items })
  })
}

export async function bulkReject(items: BulkActionItem[]): Promise<BulkActionResponse> {
  return await authorizedJsonFetch('/admin/content/bulk/reject', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ items })
  })
}

export async function bulkDelete(items: BulkActionItem[]): Promise<BulkActionResponse> {
  return await authorizedJsonFetch('/admin/content/bulk/delete', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ items })
  })
}

export async function bulkAssignCategories(
  items: BulkActionItem[],
  categoryIds: string[]
): Promise<BulkActionResponse> {
  return await authorizedJsonFetch('/admin/content/bulk/assign-categories', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ items, categoryIds })
  })
}
```

### 3. Add i18n Translations

**File:** `frontend/src/locales/messages.ts`

**Add these keys:**

```typescript
// English
exclusions: {
  channelDetail: {
    title: 'Channel Details',
    subscribers: '{count} subscribers',
    videos: '{count} videos',
    tabs: {
      videos: 'Videos',
      playlists: 'Playlists'
    },
    search: 'Search within channel...',
    exclude: 'Exclude',
    removeExclusion: 'Remove Exclusion',
    loadingMore: 'Loading more...',
    noResults: 'No results found',
    noMoreItems: 'No more items',
    excludeError: 'Error adding exclusion',
    removeError: 'Error removing exclusion',
    itemCount: '{count} items'
  },
  playlistDetail: {
    title: 'Playlist Details',
    totalVideos: '{count} videos',
    search: 'Search within playlist...',
    exclude: 'Exclude',
    removeExclusion: 'Remove Exclusion',
    loadingMore: 'Loading more...',
    noResults: 'No results found',
    noMoreItems: 'No more items',
    excludeError: 'Error adding exclusion',
    removeError: 'Error removing exclusion',
    position: 'Position #{position}'
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
},
common: {
  loading: 'Loading...',
  error: 'An error occurred',
  retry: 'Retry',
  close: 'Close'
}

// Arabic (RTL)
exclusions: {
  channelDetail: {
    title: 'تفاصيل القناة',
    subscribers: '{count} مشترك',
    videos: '{count} فيديو',
    tabs: {
      videos: 'الفيديوهات',
      playlists: 'قوائم التشغيل'
    },
    search: 'البحث في القناة...',
    exclude: 'استبعاد',
    removeExclusion: 'إزالة الاستبعاد',
    loadingMore: 'جارٍ تحميل المزيد...',
    noResults: 'لا توجد نتائج',
    noMoreItems: 'لا يوجد المزيد',
    excludeError: 'خطأ في إضافة الاستبعاد',
    removeError: 'خطأ في إزالة الاستبعاد',
    itemCount: '{count} عنصر'
  },
  playlistDetail: {
    title: 'تفاصيل قائمة التشغيل',
    totalVideos: '{count} فيديو',
    search: 'البحث في قائمة التشغيل...',
    exclude: 'استبعاد',
    removeExclusion: 'إزالة الاستبعاد',
    loadingMore: 'جارٍ تحميل المزيد...',
    noResults: 'لا توجد نتائج',
    noMoreItems: 'لا يوجد المزيد',
    excludeError: 'خطأ في إضافة الاستبعاد',
    removeError: 'خطأ في إزالة الاستبعاد',
    position: 'الموضع #{position}'
  }
},
contentLibrary: {
  bulkActions: {
    approve: 'الموافقة على المحدد',
    reject: 'رفض المحدد',
    delete: 'حذف المحدد',
    assignCategories: 'تعيين الفئات',
    confirmDelete: 'هل أنت متأكد أنك تريد حذف {count} عنصر؟'
  }
},
common: {
  loading: 'جارٍ التحميل...',
  error: 'حدث خطأ',
  retry: 'إعادة المحاولة',
  close: 'إغلاق'
}

// Dutch
exclusions: {
  channelDetail: {
    title: 'Kanaaldetails',
    subscribers: '{count} abonnees',
    videos: '{count} video\'s',
    tabs: {
      videos: 'Video\'s',
      playlists: 'Afspeellijsten'
    },
    search: 'Zoeken in kanaal...',
    exclude: 'Uitsluiten',
    removeExclusion: 'Uitsluiting verwijderen',
    loadingMore: 'Meer laden...',
    noResults: 'Geen resultaten gevonden',
    noMoreItems: 'Geen items meer',
    excludeError: 'Fout bij toevoegen uitsluiting',
    removeError: 'Fout bij verwijderen uitsluiting',
    itemCount: '{count} items'
  },
  playlistDetail: {
    title: 'Afspeellijst details',
    totalVideos: '{count} video\'s',
    search: 'Zoeken in afspeellijst...',
    exclude: 'Uitsluiten',
    removeExclusion: 'Uitsluiting verwijderen',
    loadingMore: 'Meer laden...',
    noResults: 'Geen resultaten gevonden',
    noMoreItems: 'Geen items meer',
    excludeError: 'Fout bij toevoegen uitsluiting',
    removeError: 'Fout bij verwijderen uitsluiting',
    position: 'Positie #{position}'
  }
},
contentLibrary: {
  bulkActions: {
    approve: 'Geselecteerde goedkeuren',
    reject: 'Geselecteerde afwijzen',
    delete: 'Geselecteerde verwijderen',
    assignCategories: 'Categorieën toewijzen',
    confirmDelete: 'Weet je zeker dat je {count} items wilt verwijderen?'
  }
},
common: {
  loading: 'Laden...',
  error: 'Er is een fout opgetreden',
  retry: 'Opnieuw proberen',
  close: 'Sluiten'
}
```

## Testing Checklist

After implementing Phase 2:

- [ ] Test channel detail modal opens with videos tab
- [ ] Test channel detail modal playlists tab
- [ ] Test search within channel
- [ ] Test add/remove video exclusion in channel
- [ ] Test add/remove playlist exclusion in channel
- [ ] Test playlist detail modal opens
- [ ] Test search within playlist
- [ ] Test add/remove video exclusion in playlist
- [ ] Test bulk approve in content library
- [ ] Test bulk reject in content library
- [ ] Test bulk delete in content library (with confirmation)
- [ ] Test bulk assign categories
- [ ] Test all translations display correctly (en, ar, nl)
- [ ] Test RTL layout for Arabic
- [ ] Test mobile responsive design for modals
- [ ] Test infinite scroll throttling
- [ ] Test search debouncing

## Commit Message Template

```
[FEAT]: Complete exclusions and content library features (Phase 2)

UI Integration:
- Wire ChannelDetailModal and PlaylistDetailModal to ContentLibraryView
- Add "View Details" button for channels and playlists
- Handle modal open/close events and list refresh

Bulk Actions:
- Implement bulkApprove, bulkReject, bulkDelete UI handlers
- Create contentLibrary service with API calls
- Add confirmation dialogs for destructive actions
- Display success/error feedback

Internationalization:
- Add complete translations for English
- Add complete translations for Arabic (RTL)
- Add complete translations for Dutch
- All new UI elements fully localized

Testing:
- Manual testing completed
- All features working end-to-end
- Mobile responsive verified

This completes the full exclusions and content library feature set.
```

## Next Steps

1. Implement changes in order above
2. Test thoroughly
3. Commit and push Phase 2
4. Merge both PRs

## Estimated Time

- Wire modals: 1 hour
- Bulk actions: 1 hour
- i18n: 30 minutes
- Testing: 1 hour
**Total: ~3.5 hours**
