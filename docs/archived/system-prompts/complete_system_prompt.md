# Complete System Prompt: Islamic Content Platform - Backend Admin & Native Android App

---

## PROJECT OVERVIEW

Build a complete Islamic content curation platform consisting of:
1. **Backend Admin Platform** - Web-based content management system
2. **Native Android App** - Mobile/TV content consumption app

The backend admin allows curators to search YouTube content, manage categories/subcategories, and approve content. The mobile app consumes this curated content via REST API (filtered by selected category/subcategory) and streams videos using NewPipeExtractor.

---

# PART 1: BACKEND ADMIN PLATFORM

## Core Purpose
Admin system to curate and manage YouTube content IDs (channels, playlists, videos) that will be consumed by a mobile app via REST API. Content is organized into hierarchical categories (with optional subcategories).

## Technical Stack
- **Backend**: Java Spring Boot
- **Frontend**: Vue.js
- **Database**: Firebase (Firestore + Firebase Authentication)
- **External API**: YouTube Data API v3

---

## USER ROLES & AUTHENTICATION

**Firebase Authentication with Custom Claims:**

**Admin Role:**
- Full CRUD access for users, categories, subcategories, and all content
- Approves moderator submissions
- Can exclude specific content from channels/playlists
- Manages user accounts

**Moderator Role:**
- Suggests content for admin approval
- Read-only access to approved content
- Cannot publish directly
- Cannot manage users or categories

**Implementation:**
```javascript
// Set custom claims via Firebase Admin SDK
admin.auth().setCustomUserClaims(uid, { role: 'admin' });
```

---

## KEY FEATURES

### 1. CONTENT MANAGEMENT

**YouTube Content Search Interface:**

**Search Bar:**
- Input field for YouTube search queries
- Search type selector: Channels | Playlists | Videos
- Advanced filters: Upload date, duration, sort by relevance/date/views
- Search button with loading state

**Search Results Display:**

**For Channels:**
- Grid/List view of channel results (toggle button)
- Each result card shows:
  - Channel thumbnail (circular, 80px)
  - Channel name (bold, 18px)
  - Subscriber count (gray text)
  - YouTube channel ID (small, monospace, gray)
  - "View Channel" button (secondary, opens expansion)
  - "Add to Master List" button (primary, teal)
  
**Channel Expansion View (CRITICAL FEATURE):**
When "View Channel" is clicked, expand inline or open modal/drawer showing:

**Layout:**
- **Header**: Channel banner, avatar, name, subscribers
- **Tabs (horizontal):** Videos | Live | Shorts | Playlists | Posts
- Load actual content from YouTube API for each tab
- Paginated results (20 items per tab initially, load more button)

**Videos Tab:**
- Grid layout (3-4 columns)
- Each video shows:
  - Thumbnail (16:9, 150px wide)
  - Title (2 lines max, truncate with ellipsis)
  - Duration badge (bottom-right on thumbnail)
  - Views and upload date (gray, small)
  - Video ID (monospace, very small, gray)
  - **Exclusion checkbox** (top-right corner or overlay)
- "Select All" / "Deselect All" buttons (top of grid)
- Search within channel videos (filter input)

**Live Tab:**
- Same layout as Videos
- LIVE badge on thumbnails (red)
- Each has exclusion checkbox

**Shorts Tab:**
- Grid layout (4-5 columns for vertical thumbnails)
- Vertical thumbnails (9:16 ratio)
- "Shorts" badge on thumbnail
- Exclusion checkboxes

**Playlists Tab:**
- Grid layout (3 columns)
- Playlist thumbnail, title, video count
- Playlist ID
- Exclusion checkboxes

**Posts Tab:**
- List view
- Post content preview (text + image if applicable)
- Post date and engagement (likes/comments if available)
- Post ID
- Exclusion checkboxes

**Bottom Actions:**
- "Cancel" button
- "Add Channel with Selected Exclusions" button (primary, teal)
  - Opens category assignment modal
  - Saves channel with excluded items

**For Playlists:**
- Grid/List view of playlist results
- Each result card shows:
  - Playlist thumbnail (16:9 ratio, 200px wide)
  - Playlist title (bold)
  - Channel name (gray, clickable - opens channel view)
  - Video count (e.g., "25 videos")
  - YouTube playlist ID (monospace, small)
  - "View Playlist" button (secondary, opens expansion)
  - "Add to Master List" button (primary, teal)

**Playlist Expansion View (CRITICAL FEATURE):**
When "View Playlist" is clicked:

**Layout:**
- Modal or inline expansion
- Playlist header: thumbnail, title, description (expandable), channel name
- List/Grid of all videos in the playlist

**Each video shows:**
- Thumbnail (small, 120px wide, 16:9)
- Title (2 lines max)
- Duration badge
- Video position in playlist (e.g., "#1", "#2")
- Video ID (small, monospace)
- **Exclusion checkbox**

**Top Actions:**
- Search within playlist videos
- "Select All" / "Deselect All"
- Sort by: Position | Date Added | Title

**Bottom Actions:**
- "Cancel" button
- "Add Playlist with Selected Exclusions" button (primary)
  - Opens category assignment modal
  - Saves playlist with excluded video IDs

**For Videos:**
- Grid/List view of video results (3-4 columns)
- Each result card shows:
  - Video thumbnail with duration badge (16:9, 200px wide)
  - Video title (bold, 2 lines max)
  - Channel name (gray, small, clickable)
  - View count and upload date (gray, small)
  - YouTube video ID (monospace, very small)
  - "Add to Master List" button (primary, teal)
- No expansion needed (individual videos have no sub-content)

**Adding Content to Master List:**

**Category Assignment Modal (MANDATORY):**
Appears when clicking "Add to Master List" or after selecting exclusions:

**Modal Layout:**
- Title: "Assign Categories"
- Content preview: Small thumbnail + title
- **Category Selection:**
  - Hierarchical tree view OR cascading dropdowns
  - Shows categories and their subcategories
  - Multi-select checkboxes (can assign multiple categories/subcategories)
  - Must select at least one category OR subcategory
  - Example structure:
    ```
    ☐ Quran
      ☐ Quran Recitation
      ☐ Tafsir
    ☐ Hadith
      ☐ Sahih Bukhari
      ☐ Sahih Muslim
    ☐ Islamic History
    ☐ Fiqh
      ☐ Prayer
      ☐ Fasting
      ☐ Zakat
    ```
- Validation: "Please select at least one category"
- **Status:**
  - If moderator: Status automatically set to "Pending Admin Approval"
  - If admin: Option to set as "Approved" immediately or "Pending"
- Buttons:
  - "Cancel" (secondary)
  - "Save" (primary, teal, disabled until category selected)

**After Saving:**
- Success notification
- Content added to master list
- Moderators see "Submitted for approval"
- Admins see content in approved list (if approved) or pending list

**Content with Exclusions:**
- Channel: Save with `excludedItems.videos[]`, `excludedItems.liveStreams[]`, `excludedItems.shorts[]`, `excludedItems.playlists[]`, `excludedItems.posts[]`
- Playlist: Save with `excludedItems.videos[]`
- Store exclusions in Firestore document
- Display exclusion count in master list (e.g., "5 items excluded")

---

### 2. CATEGORY SYSTEM WITH SUBCATEGORIES

**Category Management Interface:**

**Category List View:**
- Hierarchical tree structure (expandable/collapsible)
- Indentation shows parent-child relationships
- Each category shows:
  - Expand/collapse icon (if has subcategories)
  - Category name
  - Badge: "X items" (count of associated content)
  - Badge: "Y subcategories" (if applicable)
  - Edit icon button (pencil)
  - Delete icon button (trash) - admin only
- "Add Category" button (top-right, primary)
- Search categories input (top)

**Example Display:**
```
📁 Quran (150 items, 2 subcategories)          [Edit] [Delete]
  └─ 📄 Quran Recitation (80 items)            [Edit] [Delete]
  └─ 📄 Tafsir (70 items)                      [Edit] [Delete]
📁 Hadith (200 items, 2 subcategories)         [Edit] [Delete]
  └─ 📄 Sahih Bukhari (100 items)              [Edit] [Delete]
  └─ 📄 Sahih Muslim (100 items)               [Edit] [Delete]
📄 Islamic History (120 items)                 [Edit] [Delete]
📁 Fiqh (180 items, 4 subcategories)           [Edit] [Delete]
  └─ 📄 Prayer (50 items)                      [Edit] [Delete]
  └─ 📄 Fasting (40 items)                     [Edit] [Delete]
  └─ 📄 Zakat (45 items)                       [Edit] [Delete]
  └─ 📄 Hajj (45 items)                        [Edit] [Delete]
```

**Add/Edit Category Form:**

**Modal or Side Panel:**
- Title: "Add Category" or "Edit Category"
- Fields:
  - **Category Name** (required, text input)
  - **Parent Category** (optional dropdown)
    - Options: "None (Top-level category)" or list of existing categories
    - If subcategory is selected as parent, show warning: "This will create a nested subcategory"
  - **Icon/Image** (optional file upload)
    - Small preview if uploaded
  - **Display Order** (optional number, for sorting)
- Validation:
  - Category name required
  - Check for duplicates
  - Prevent circular parent relationships
- Buttons:
  - "Cancel" (secondary)
  - "Save" (primary, teal)

**Delete Category:**
- Confirmation dialog: "Delete [Category Name]?"
- Warning if category has content: "This category has X items associated. Deleting will unassign them."
- Warning if category has subcategories: "This category has Y subcategories. Delete them too?"
- Options:
  - "Cancel"
  - "Delete category only" (keeps subcategories, makes them top-level)
  - "Delete category and subcategories" (if applicable)

**Category Structure Examples:**

**With Subcategories:**
- Quran
  - Quran Recitation
  - Tafsir
  - Quran Memorization
- Hadith
  - Sahih Bukhari
  - Sahih Muslim
  - Sunan Abu Dawood
- Fiqh
  - Prayer (Salah)
  - Fasting (Sawm)
  - Zakat
  - Hajj
  - Islamic Law

**Without Subcategories (flat):**
- Islamic History
- Seerah
- Aqeedah
- Islamic Manners
- Islamic Stories
- Islamic Lectures
- Kids Content

**Important Notes:**
- A category can have 0 or more subcategories
- Content can be assigned to:
  - Parent category only (shows in parent filter)
  - Subcategory only (shows in subcategory AND parent filter)
  - Multiple categories/subcategories
- Maximum depth: 2 levels (Category → Subcategory, no sub-subcategories)

---

### 3. CONTENT APPROVAL WORKFLOW

**Pending Approvals Dashboard (Admin Only):**

**Layout:**
- Tabs: All (badge with count) | Channels | Playlists | Videos
- Filters:
  - Date submitted (date range)
  - Submitted by (moderator dropdown)
  - Category filter
- Sort by: Newest | Oldest | Most items

**Each pending item shows:**
- Card layout with thumbnail
- Content type badge (Channel/Playlist/Video)
- Title and basic metadata
- Categories assigned (as tags/chips)
- Subcategories assigned (nested under parent, if applicable)
- Submitted by (moderator name + avatar)
- Submitted date (relative, e.g., "2 days ago")
- Exclusion count (if applicable): "5 videos excluded"
- Actions:
  - **Preview** button (opens YouTube in new tab)
  - **View Details** button (opens detail modal)
  - **Edit** button (modify categories/exclusions)
  - **Approve** button (green, checkmark icon)
  - **Reject** button (red, X icon)
- Bulk selection checkbox

**Bulk Actions Bar (when items selected):**
- "X items selected"
- "Approve All" button
- "Reject All" button
- "Clear Selection"

**Approval Actions:**
- **Approve**: 
  - Move to approved status
  - Content becomes visible in API
  - Notify moderator (optional)
- **Reject**: 
  - Modal appears: "Reject [Content Title]?"
  - Optional reason textarea
  - "Cancel" / "Reject" buttons
  - Notify moderator with reason
- **Edit**: 
  - Open category assignment modal
  - Allow modifying categories/subcategories
  - Allow modifying exclusions
  - "Save & Approve" or "Save as Pending"

**Detail Modal:**
- Full content information
- YouTube metadata
- Assigned categories/subcategories (hierarchical view)
- Exclusions list (expandable)
  - "5 videos excluded: [Show]"
  - Clicking shows list of excluded items
- Activity history:
  - Submitted by [User] on [Date]
  - Modified by [User] on [Date] (if edited)
- Approve/Reject buttons at bottom

**Notification System:**
- In-app notifications (bell icon in top bar)
- Email notifications (optional):
  - Moderator notified when submission approved
  - Moderator notified when submission rejected (with reason)
  - Admin notified when moderator submits content

---

### 4. MASTER CONTENT LIST

**Content Management Table:**

**Top Bar:**
- Search input (searches title, channel name, YouTube ID)
- "Add Content" button (opens YouTube search interface)
- View toggle: Table | Grid
- Filters button (opens filter sidebar)

**Filters Sidebar/Panel:**
- **Status:** All | Approved | Pending | Rejected (radio buttons)
- **Type:** All | Channels | Playlists | Videos (checkboxes)
- **Category:** Hierarchical tree checkboxes (same as category list)
  - Can select parent categories or subcategories
  - Selecting parent includes all subcategories
- **Date Range:** Created between [date] and [date]
- **Created By:** Dropdown of users (admins + moderators)
- **Has Exclusions:** Yes | No | All
- "Apply Filters" button
- "Clear Filters" button

**Table View:**

**Columns:**
1. **Thumbnail** (80px square)
2. **Title** (sortable, clickable - opens detail)
3. **Type** (badge: Channel/Playlist/Video)
4. **Categories** (tags/chips, show parent > subcategory)
5. **Status** (badge: Approved/Pending/Rejected)
6. **Exclusions** (count, e.g., "5 items" or "—")
7. **Created By** (user name)
8. **Created Date** (sortable, relative)
9. **Actions** (dropdown menu icon)

**Grid View:**
- Cards with thumbnail, title, type badge, categories, status badge
- 3-4 columns
- Hover shows actions overlay

**Actions Dropdown:**
- View Details
- Edit (opens category/exclusion editor)
- View on YouTube (new tab)
- Duplicate (create copy with different categories)
- Delete (soft delete with confirmation)

**Bulk Actions:**
- Select multiple items (checkboxes)
- Bulk actions bar appears:
  - Change Status
  - Add to Category
  - Remove from Category
  - Delete Selected
  - Export Selected (CSV)

**Content Detail View Modal:**

**Tabs:**
- **Overview**
- **Categories**
- **Exclusions** (if applicable)
- **Metadata**
- **History**

**Overview Tab:**
- Large thumbnail
- Full title and description
- YouTube link (clickable)
- Type, status badges
- View count, subscribers (if channel), video count (if channel/playlist)
- Published date

**Categories Tab:**
- Hierarchical display of assigned categories
- "Edit Categories" button
- Shows: Parent Category > Subcategory

**Exclusions Tab (if channel or playlist):**
- Tabs: Videos | Live | Shorts | Playlists | Posts (for channels)
- List of excluded items with thumbnails
- "Edit Exclusions" button (opens YouTube content viewer again)
- Count summary: "5 of 120 videos excluded"

**Metadata Tab:**
- All YouTube metadata (formatted JSON or key-value pairs)
- YouTube IDs
- Timestamps

**History Tab:**
- Activity timeline:
  - Created by [User] on [Date]
  - Approved by [User] on [Date]
  - Modified by [User] on [Date]
  - Categories changed: [Old] → [New]
  - Exclusions modified: [Count]

---

### 5. EXCLUSION MANAGEMENT

**For Channels:**

**Exclusions Interface (in detail modal or dedicated page):**
- Header: "Manage Exclusions for [Channel Name]"
- "Refresh from YouTube" button (fetches latest content)
- Last refreshed: [timestamp]

**Tabs:** Videos | Live | Shorts | Playlists | Posts

**Each Tab Layout:**
- Search/filter input (filter within tab)
- "Select All" / "Deselect All" toggles
- Grid of items (3-4 columns)
- Each item:
  - Thumbnail
  - Title
  - Metadata (views, date, duration)
  - **Exclusion toggle/checkbox** (on/off state)
    - Checked = Excluded (red tint overlay on thumbnail)
    - Unchecked = Included (normal)
- Pagination (load more button)
- Save button (sticky at bottom)

**Visual Feedback:**
- Excluded items have red border or red overlay
- Count at top: "5 of 120 videos excluded"

**Data Structure:**
```javascript
{
  contentType: 'channel',
  youtubeId: 'UCxxxxx',
  excludedItems: {
    videos: ['videoId1', 'videoId2', 'videoId3'],
    liveStreams: ['liveId1'],
    shorts: ['shortId1', 'shortId2'],
    playlists: ['playlistId1'],
    posts: ['postId1']
  }
}
```

**For Playlists:**

**Exclusions Interface:**
- Header: "Manage Exclusions for [Playlist Name]"
- "Refresh from YouTube" button
- List/Grid of all videos in playlist
- Each video:
  - Position number (#1, #2, etc.)
  - Thumbnail
  - Title
  - Duration
  - **Exclusion toggle/checkbox**
- Search within playlist
- "Select All" / "Deselect All"
- Save button

**Visual Feedback:**
- Excluded videos have red border
- Count: "3 of 25 videos excluded"

**Data Structure:**
```javascript
{
  contentType: 'playlist',
  youtubeId: 'PLxxxxx',
  excludedItems: {
    videos: ['videoId1', 'videoId2', 'videoId3']
  }
}
```

**Important Notes:**
- Exclusions are stored in Firestore
- API automatically filters excluded items
- Moderators can set exclusions when adding content
- Admins can modify exclusions anytime
- Refresh fetches latest content from YouTube (doesn't auto-update)

---

### 6. USER MANAGEMENT (ADMIN ONLY)

**User List Page:**

**Layout:**
- Table view
- Search users (by name or email)
- "Add User" button (top-right)

**Table Columns:**
1. Avatar (small, 40px)
2. Name
3. Email
4. Role (badge: Admin/Moderator)
5. Status (badge: Active/Inactive)
6. Last Login (relative time)
7. Created Date
8. Actions (dropdown)

**Actions Dropdown:**
- Edit User
- Change Role
- Activate/Deactivate
- Reset Password
- View Activity
- Delete User (with confirmation)

**Add User Modal:**
- **Email** (required, validated)
- **Display Name** (required)
- **Role** (dropdown: Admin | Moderator)
- **Send Welcome Email** (checkbox, checked by default)
- Buttons: "Cancel" / "Create User"
- If "Send Welcome Email" checked:
  - Sends email with temporary password
  - User must reset on first login

**Edit User Modal:**
- Edit display name
- Change role (dropdown)
- Change status (Active/Inactive toggle)
- "Reset Password" button (sends reset email)
- "Save Changes" button

**User Activity Log (per user):**
- Modal or dedicated page
- Timeline of actions:
  - Content added (with links)
  - Content approved/rejected (if admin)
  - Categories created/modified
  - Login events
- Filters: Date range, action type
- Export to CSV

**Global Activity Log (for all users):**
- Accessible from main navigation
- Same timeline format
- Filter by user, action type, date
- Export functionality

---

## FIRESTORE DATABASE STRUCTURE

```javascript
users/
  {uid}
    - email: string
    - displayName: string
    - role: 'admin' | 'moderator'
    - createdAt: timestamp
    - lastLogin: timestamp
    - isActive: boolean
    - createdBy: string (uid of admin who created this user)

categories/
  {categoryId}
    - name: string
    - parentCategoryId: string | null  // null if top-level category
    - icon: string (URL) | null
    - displayOrder: number  // for sorting
    - createdAt: timestamp
    - createdBy: string (uid)
    - updatedAt: timestamp | null

content/
  {contentId}
    - youtubeId: string
    - type: 'channel' | 'playlist' | 'video'
    - title: string
    - description: string
    - thumbnailUrl: string
    - channelName: string (for playlists/videos)
    - channelId: string (for playlists/videos)
    - duration: number | null (for videos, in seconds)
    - viewCount: number | null
    - subscriberCount: number | null (for channels)
    - videoCount: number | null (for channels/playlists)
    - publishedAt: timestamp
    
    // Category assignments - array of category/subcategory IDs
    - categoryIds: array<string>  // Can include both parent and subcategory IDs
    
    - status: 'pending' | 'approved' | 'rejected'
    - contentTypes: array<string> | null  // for channels: ['videos', 'live', 'shorts', 'playlists', 'posts']
    
    // Exclusions object
    - excludedItems: {
        videos: array<string> | null,
        liveStreams: array<string> | null,
        shorts: array<string> | null,
        playlists: array<string> | null,
        posts: array<string> | null
      } | null
    
    - createdBy: string (uid)
    - createdAt: timestamp
    - approvedBy: string (uid) | null
    - approvedAt: timestamp | null
    - rejectedBy: string (uid) | null
    - rejectedAt: timestamp | null
    - rejectedReason: string | null
    - isActive: boolean
    - lastModifiedBy: string (uid) | null
    - lastModifiedAt: timestamp | null

activityLog/
  {logId}
    - userId: string (uid)
    - userEmail: string
    - userName: string
    - action: string ('add_content', 'approve_content', 'reject_content', 'edit_content', 'delete_content', 'create_category', 'edit_category', 'delete_category')
    - contentId: string | null
    - categoryId: string | null
    - details: object  // Additional context about the action
    - timestamp: timestamp
```

**Important Query Indexes (Firestore):**
```javascript
// For content queries
content: [status, type, createdAt]
content: [status, categoryIds (array-contains), createdAt]
content: [createdBy, status, createdAt]

// For category hierarchy
categories: [parentCategoryId, displayOrder]

// For activity log
activityLog: [userId, timestamp]
activityLog: [action, timestamp]
```

---

## API ENDPOINTS (FOR MOBILE APP)

**Public Endpoints (No Auth Required):**

### Categories

```
GET /api/v1/categories
Response: {
  success: true,
  data: {
    categories: [
      {
        id: "cat1",
        name: "Quran",
        parentId: null,
        icon: "https://...",
        displayOrder: 1,
        subcategories: [
          {
            id: "subcat1",
            name: "Quran Recitation",
            parentId: "cat1",
            icon: null,
            displayOrder: 1
          },
          {
            id: "subcat2",
            name: "Tafsir",
            parentId: "cat1",
            icon: null,
            displayOrder: 2
          }
        ]
      },
      {
        id: "cat2",
        name: "Islamic History",
        parentId: null,
        icon: "https://...",
        displayOrder: 2,
        subcategories: []  // No subcategories
      }
    ]
  }
}
```

### Home Content (Recent)

```
GET /api/v1/home/recent?page=1&limit=20&categoryId={categoryId}
Query Params:
- page: number (default 1)
- limit: number (default 20, max 50)
- categoryId: string (optional - filters by category or subcategory)

Response: {
  success: true,
  data: {
    channels: [
      {
        id: "ch1",
        youtubeId: "UCxxxx",
        name: "Al-Madinah TV",
        thumbnail: "https://...",
        subscribers: 1000000,
        categoryIds: ["cat1", "subcat1"],
        recentVideos: [
          {id: "v1", youtubeId: "vid1", title: "...", thumbnail: "...", duration: 600}
          // ... up to 3 most recent
        ]
      }
    ],
    playlists: [
      {
        id: "pl1",
        youtubeId: "PLxxxx",
        name: "Islamic Lectures",
        thumbnail: "https://...",
        videoCount: 25,
        channelName: "Islamic Lectures",
        categoryIds: ["cat2"],
        recentVideos: [
          // ... up to 3 most recent
        ]
      }
    ],
    videos: [
      {
        id: "v1",
        youtubeId: "vid1",
        title: "The Essence of Faith",
        thumbnail: "https://...",
        duration: 600,
        channelName: "Sheikh Omar",
        channelId: "UCyyyy",
        views: 100000,
        uploadedAt: "2024-01-15T10:30:00Z",
        categoryIds: ["cat1", "subcat2"]
      }
    ]
  },
  pagination: {
    page: 1,
    limit: 20,
    totalPages: 10,
    totalItems: 200
  }
}

Note: If categoryId is provided:
- Returns only content assigned to that specific category OR its subcategories
- If categoryId is a parent category, includes content from all its subcategories
- If categoryId is a subcategory, returns only that subcategory's content
```

### Channels

```
GET /api/v1/channels?page=1&limit=20&categoryId={categoryId}&sort={sort}
Query Params:
- page: number
- limit: number
- categoryId: string (optional - filters by category/subcategory)
- sort: string (optional: 'name' | 'subscribers' | 'recent', default 'recent')

Response: {
  success: true,
  data: {
    channels: [
      {
        id: "ch1",
        youtubeId: "UCxxxx",
        name: "Al-Madinah TV",
        thumbnail: "https://...",
        subscribers: 1000000,
        categoryIds: ["cat1", "subcat1"],  // Shows assigned categories
        videoCount: 1500
      }
    ]
  },
  pagination: {...}
}
```

### Channel Detail

```
GET /api/v1/channels/{channelId}
Response: {
  success: true,
  data: {
    id: "ch1",
    youtubeId: "UCxxxx",
    name: "Al-Madinah TV",
    thumbnail: "https://...",
    banner: "https://...",
    description: "...",
    subscribers: 1000000,
    videoCount: 1500,
    categoryIds: ["cat1", "subcat1"],
    categories: [
      {id: "cat1", name: "Quran", parentId: null},
      {id: "subcat1", name: "Quran Recitation", parentId: "cat1"}
    ]
  }
}
```

### Channel Content (Videos, Live, Shorts, Playlists, Posts)

```
GET /api/v1/channels/{channelId}/videos?page=1&limit=20
GET /api/v1/channels/{channelId}/live?page=1&limit=20
GET /api/v1/channels/{channelId}/shorts?page=1&limit=20
GET /api/v1/channels/{channelId}/playlists?page=1&limit=20
GET /api/v1/channels/{channelId}/posts?page=1&limit=20

Response: Paginated list of respective content type
Note: Automatically excludes items in channel's excludedItems array
```

### Playlists

```
GET /api/v1/playlists?page=1&limit=20&categoryId={categoryId}&sort={sort}
Query Params:
- categoryId: filters by category/subcategory
- sort: 'recent' | 'name' | 'videoCount'

Response: {
  success: true,
  data: {
    playlists: [
      {
        id: "pl1",
        youtubeId: "PLxxxx",
        name: "Islamic Lectures",
        thumbnail: "https://...",
        videoCount: 25,
        channelName: "Channel Name",
        categoryIds: ["cat2", "subcat5"]
      }
    ]
  },
  pagination: {...}
}
```

### Playlist Detail

```
GET /api/v1/playlists/{playlistId}
GET /api/v1/playlists/{playlistId}/videos?page=1&limit=20

Response: Playlist info and videos (excluding items in excludedItems.videos array)
```

### Videos

```
GET /api/v1/videos?page=1&limit=20&categoryId={categoryId}&sort={sort}&duration={duration}
Query Params:
- categoryId: filters by category/subcategory
- sort: 'recent' | 'popular' | 'duration'
- duration: 'short' (<5min) | 'medium' (5-20min) | 'long' (>20min)

Response: {
  success: true,
  data: {
    videos: [
      {
        id: "v1",
        youtubeId: "vid1",
        title: "...",
        thumbnail: "...",
        duration: 600,
        channelName: "...",
        views: 100000,
        uploadedAt: "...",
        categoryIds: ["cat3"]
      }
    ]
  },
  pagination: {...}
}
```

### Video Detail

```
GET /api/v1/videos/{videoId}
Response: {
  success: true,
  data: {
    id: "v1",
    youtubeId: "vid1",
    title: "The Essence of Faith",
    description: "Full description...",
    thumbnail: "https://...",
    duration: 600,
    channelName: "Sheikh Omar",
    channelId: "UCyyyy",
    views: 100000,
    uploadedAt: "2024-01-15T10:30:00Z",
    categoryIds: ["cat1", "subcat2"],
    categories: [
      {id: "cat1", name: "Quran", parentId: null},
      {id: "subcat2", name: "Tafsir", parentId: "cat1"}
    ]
  }
}
```

### Search

```
GET /api/v1/search?q={query}&type={type}&page=1&limit=20&categoryId={categoryId}
Query Params:
- q: string (search query)
- type: 'all' | 'video' | 'channel' | 'playlist'
- page: number
- limit: number
- categoryId: string (optional - filter results by category/subcategory)

Response: {
  success: true,
  data: {
    channels: [...],  // if type is 'all' or 'channel'
    playlists: [...], // if type is 'all' or 'playlist'
    videos: [...]     // if type is 'all' or 'video'
  },
  pagination: {...}
}
```

**Important API Notes:**
- All endpoints return only approved content (`status = 'approved'` and `isActive = true`)
- Exclusions are automatically filtered (excluded videos/shorts/live/playlists/posts not returned)
- Category filtering:
  - If `categoryId` is a parent category: returns content assigned to parent OR any of its subcategories
  - If `categoryId` is a subcategory: returns only content assigned to that subcategory
  - Content can be assigned to multiple categories/subcategories, so may appear in multiple filters
- Pagination uses offset-based pagination
- All timestamps in ISO 8601 format
- Thumbnails are YouTube CDN URLs

---

## ADMIN API ENDPOINTS (AUTH REQUIRED)

**Authentication:** All admin endpoints require Firebase ID token in Authorization header:
```
Authorization: Bearer {firebaseIdToken}
```

### Content Management

```
POST /api/v1/admin/content
Headers: Authorization: Bearer {token}
Body: {
  youtubeId: string,
  type: 'channel' | 'playlist' | 'video',
  categoryIds: array<string>,  // Array of category/subcategory IDs
  excludedItems: {
    videos: array<string>,
    liveStreams: array<string>,
    shorts: array<string>,
    playlists: array<string>,
    posts: array<string>
  } | null
}
Response: {
  success: true,
  data: {contentId: "...", status: "pending" | "approved"}
}
Notes:
- Fetches metadata from YouTube API
- If user is moderator: status = 'pending'
- If user is admin: status = 'approved' (or can be set in request)

PUT /api/v1/admin/content/{contentId}
Body: {
  categoryIds: array<string>,
  excludedItems: object,
  status: 'pending' | 'approved' | 'rejected'
}
Response: {success: true, data: {updated content}}

DELETE /api/v1/admin/content/{contentId}
Response: {success: true}
Notes: Soft delete (sets isActive = false)

GET /api/v1/admin/content/pending?page=1&limit=20
Response: Paginated list of pending content
Auth: Admin only

POST /api/v1/admin/content/{contentId}/approve
Response: {success: true}
Auth: Admin only

POST /api/v1/admin/content/{contentId}/reject
Body: {reason: string (optional)}
Response: {success: true}
Auth: Admin only

GET /api/v1/admin/content?status={status}&type={type}&categoryId={categoryId}&page=1
Query: Filter and search all content
Response: Paginated content list with filters applied
```

### Category Management

```
GET /api/v1/admin/categories
Response: Full category hierarchy (same as public endpoint but includes inactive)

POST /api/v1/admin/categories
Body: {
  name: string,
  parentCategoryId: string | null,
  icon: string | null,
  displayOrder: number
}
Response: {success: true, data: {categoryId: "..."}}
Auth: Admin only

PUT /api/v1/admin/categories/{categoryId}
Body: {name, parentCategoryId, icon, displayOrder}
Response: {success: true, data: {updated category}}
Auth: Admin only

DELETE /api/v1/admin/categories/{categoryId}?deleteSubcategories={boolean}
Response: {success: true}
Notes: 
- If category has content, unassigns it
- If deleteSubcategories=true, deletes subcategories too
- If deleteSubcategories=false, makes subcategories top-level
Auth: Admin only
```

### User Management

```
GET /api/v1/admin/users?page=1&limit=20&role={role}&status={status}
Response: Paginated user list
Auth: Admin only

POST /api/v1/admin/users
Body: {
  email: string,
  displayName: string,
  role: 'admin' | 'moderator',
  sendWelcomeEmail: boolean
}
Response: {success: true, data: {userId: "..."}}
Auth: Admin only

PUT /api/v1/admin/users/{userId}
Body: {displayName, role, isActive}
Response: {success: true}
Auth: Admin only

POST /api/v1/admin/users/{userId}/reset-password
Response: {success: true}
Notes: Sends password reset email
Auth: Admin only

DELETE /api/v1/admin/users/{userId}
Response: {success: true}
Auth: Admin only
```

### Activity Log

```
GET /api/v1/admin/activity?userId={userId}&action={action}&startDate={date}&endDate={date}&page=1
Response: Paginated activity log
Auth: Admin only

GET /api/v1/admin/activity/export?format=csv&filters={...}
Response: CSV file download
Auth: Admin only
```

### YouTube Integration

```
POST /api/v1/admin/youtube/search
Body: {
  query: string,
  type: 'channel' | 'playlist' | 'video',
  maxResults: number (default 20, max 50)
}
Response: YouTube search results with thumbnails and metadata
Auth: Required (admin or moderator)

GET /api/v1/admin/youtube/channel/{youtubeChannelId}/content?type={type}
Query: type = 'videos' | 'live' | 'shorts' | 'playlists' | 'posts'
Response: Paginated channel content from YouTube API
Auth: Required

GET /api/v1/admin/youtube/playlist/{youtubePlaylistId}/videos
Response: All videos in playlist from YouTube API
Auth: Required
```

---

## UI/UX REQUIREMENTS FOR ADMIN

**Design System:**
- Clean, professional dashboard
- Responsive (desktop-first, 1920x1080 optimal, but works on 1366x768)
- Color scheme: Professional with teal accents (#2D7A6B to match mobile app)
- White/light gray background (#F5F5F5)
- Typography: Clear, readable (Roboto or Inter)
- Consistent spacing (8px grid system)

**Dashboard Layout:**

**Sidebar Navigation (left, 240px wide, fixed):**
- App logo/name at top
- Navigation items:
  - 📊 Dashboard (home)
  - 🔍 Content Search (YouTube search interface)
  - 📁 Content Management
  - ⏳ Pending Approvals (badge with count if admin)
  - 🗂️ Categories
  - 👥 Users (admin only)
  - 📝 Activity Log
  - ⚙️ Settings
- User section at bottom:
  - User avatar and name
  - Role badge
  - Logout button

**Top Bar (full width, 64px height, fixed):**
- Breadcrumbs (left)
- Search bar (center, global search)
- Notifications bell icon with badge (right)
- User profile dropdown (right)

**Main Content Area:**
- Padding: 24px
- Max width: 1400px (centered)
- Contextual based on navigation selection
- Page title (H1, 32px, bold)
- Action buttons (top-right, aligned with title)

**Key UI Components:**

**Content Search Interface:**

**Search Section:**
- Large search bar (prominent, centered)
- Type selector buttons below: [Channels] [Playlists] [Videos]
- Advanced filters (collapsible):
  - Date range picker
  - Duration filter (for videos)
  - Sort by dropdown
- "Search YouTube" button (primary, teal)
- Loading state: Spinner with "Searching YouTube..."
- Empty state: Magnifying glass icon + "Enter a search term to find content"

**Results Section:**
- View toggle: [Grid] [List] (top-right)
- Results count: "120 results for 'Islamic history'"
- Pagination controls (top and bottom)

**Result Cards (Grid View):**
- Card: White background, 12px border radius, subtle shadow
- Thumbnail (top, full width, 16:9 for playlists/videos, 1:1 for channels)
- Content type badge (top-left of thumbnail): [Channel] [Playlist] [Video]
- Title (bold, 16px, 2 lines max)
- Metadata (gray, 14px): Subscribers/videos/views, date
- YouTube ID (monospace, 12px, gray)
- Actions (bottom):
  - "View Content" button (secondary, if channel/playlist)
  - "Add to Master List" button (primary, teal, full width)

**Result Cards (List View):**
- Horizontal layout
- Thumbnail (left, 120px wide)
- Info (middle, flex)
- Actions (right, fixed width)

**Channel/Playlist Expansion Modal:**
- Large modal (80% viewport width, 80% height)
- Header:
  - Channel/playlist info (banner, avatar, name, metadata)
  - Close button (X, top-right)
- Tab bar: [Videos] [Live] [Shorts] [Playlists] [Posts]
- Search within content (top-right)
- Content grid (3-4 columns)
- Each item:
  - Thumbnail with metadata overlay
  - Exclusion checkbox (top-right corner, large, clear)
  - Red border/tint if excluded
- Bulk actions bar (sticky bottom):
  - "X items excluded" counter
  - "Select All" / "Deselect All" buttons
  - "Cancel" button
  - "Add with Exclusions" button (primary, disabled if no category)
- Pagination (load more button or infinite scroll)

**Category Assignment Modal:**
- Medium modal (600px width)
- Title: "Assign Categories"
- Content preview (small thumbnail + title)
- Category tree (hierarchical checkboxes):
  ```
  ☐ Quran
    ☐ Quran Recitation
    ☐ Tafsir
  ☐ Hadith
  ☐ Islamic History
  ```
- Expand/collapse icons for parent categories
- Indentation shows hierarchy
- Must select at least one (validation message if not)
- For admins: "Approve immediately" checkbox
- Buttons:
  - "Cancel" (secondary, left)
  - "Save" (primary, teal, right, disabled until category selected)

**Category Management:**

**Category Tree View:**
- Hierarchical list with indentation
- Each category row:
  - Expand/collapse icon (if has subcategories)
  - Drag handle icon (for reordering)
  - Category icon (if set)
  - Category name (bold if parent, normal if subcategory)
  - Badges: [X items] [Y subcategories]
  - Actions: [Edit] [Delete] icons
- "Add Category" button (top-right, primary)
- Drag and drop to reorder
- Nested indentation for subcategories

**Add/Edit Category Modal:**
- Form fields:
  - Category Name (text input, required)
  - Parent Category (dropdown, optional, shows "None" or category list)
  - Icon (file upload, shows preview)
  - Display Order (number input)
- Live preview of hierarchy position
- "Save" / "Cancel" buttons

**Content Management Table:**

**Filters Sidebar (left, 280px, collapsible):**
- Filter sections (expandable):
  - **Status**: Radio buttons (All/Approved/Pending/Rejected)
  - **Type**: Checkboxes (All/Channels/Playlists/Videos)
  - **Categories**: Hierarchical tree checkboxes
    - Parent categories with expand/collapse
    - Subcategories indented
    - Selecting parent selects all subcategories
  - **Date Range**: Date pickers (from/to)
  - **Created By**: User dropdown
  - **Has Exclusions**: Radio (All/Yes/No)
- Buttons:
  - "Apply Filters" (primary)
  - "Clear All" (secondary)
- Active filters summary (chips) at top

**Main Table Area:**
- Top bar:
  - Search input (left, 40% width)
  - View toggle: [Table] [Grid] (right)
  - "Add Content" button (right, primary)
- Table (sortable columns):
  - Bulk select checkbox column
  - Thumbnail (80px square)
  - Title (truncate with tooltip on hover)
  - Type badge
  - Categories (chips, show "Parent > Sub")
  - Status badge (colored: green=approved, yellow=pending, red=rejected)
  - Exclusions (count or "—")
  - Created By (user name + avatar)
  - Created Date (relative)
  - Actions (⋮ menu icon)
- Row hover: Slight background color change
- Bulk actions bar (appears when items selected):
  - "X items selected"
  - "Change Status" dropdown
  - "Add to Category" button
  - "Remove from Category" button
  - "Delete" button (red)
  - "Clear Selection"
- Pagination controls (bottom): ← 1 2 3 ... 10 →

**Grid View:**
- 3-4 columns
- Cards similar to search results
- Shows thumbnail, title, type, categories, status
- Actions on hover overlay

**Content Detail Modal:**
- Large modal (full screen or 90% viewport)
- Header: Content type badge, title, close button
- Tabs: [Overview] [Categories] [Exclusions] [Metadata] [History]
- **Overview Tab:**
  - Large thumbnail
  - Full description (expandable)
  - Metadata grid: views, subscribers, duration, etc.
  - "View on YouTube" link button
  - Status badge
  - Edit/Delete buttons (if permissions)
- **Categories Tab:**
  - Hierarchical list of assigned categories:
    ```
    Quran > Quran Recitation
    Fiqh > Prayer
    Islamic History
    ```
  - "Edit Categories" button
- **Exclusions Tab:**
  - Sub-tabs: Videos | Live | Shorts | Playlists | Posts
  - Summary: "5 of 120 videos excluded"
  - List of excluded items (thumbnail + title)
  - "Edit Exclusions" button (opens YouTube content viewer)
- **Metadata Tab:**
  - Formatted JSON viewer or key-value table
  - YouTube IDs, timestamps, all metadata
- **History Tab:**
  - Timeline:
    - Icon + "Created by [User] on [Date]"
    - Icon + "Approved by [User] on [Date]"
    - Icon + "Modified by [User] on [Date]"
    - Details of changes (categories changed, exclusions modified)

**Pending Approvals Dashboard:**

**Stats Cards (top):**
- 4 cards showing:
  - Total Pending (number + icon)
  - Pending Channels
  - Pending Playlists
  - Pending Videos
- Cards are teal with white text, clickable to filter

**Tabs:** [All] [Channels] [Playlists] [Videos]
- Badge with count on each tab

**Filters:**
- Date submitted (date range)
- Submitted by (user dropdown)
- Category (dropdown with hierarchy)
- Sort: Newest | Oldest | Most items

**Pending Items Grid/List:**
- Each item card:
  - Thumbnail (left)
  - Content info (middle):
    - Type badge
    - Title (bold)
    - Metadata (channel name, video count, etc.)
    - Categories (chips showing parent > sub)
    - Exclusions badge: "5 items excluded" (if applicable)
  - Submitter info (middle):
    - Avatar + name
    - "Submitted 2 days ago"
  - Actions (right):
    - "Preview" icon button (opens YouTube)
    - "View Details" button
    - "Edit" button
    - "✓ Approve" button (green)
    - "✗ Reject" button (red)
  - Bulk select checkbox (left)
- Bulk actions bar (when items selected):
  - "X items selected"
  - "Approve All" (green)
  - "Reject All" (red)
  - "Clear Selection"

**Approve Confirmation:**
- Toast notification: "✓ [Content Title] approved successfully"

**Reject Modal:**
- Title: "Reject [Content Title]?"
- Reason textarea (optional but recommended)
- "Cancel" / "Reject" buttons
- After rejection: Notification sent to moderator

**User Management:**

**User List:**
- Table with columns:
  - Avatar
  - Name
  - Email
  - Role badge (Admin/Moderator)
  - Status badge (Active/Inactive)
  - Last Login (relative time)
  - Created Date
  - Actions (⋮ menu)
- "Add User" button (top-right)
- Search users (top-left)

**Add User Modal:**
- Form fields:
  - Email (validated)
  - Display Name
  - Role (dropdown)
  - "Send Welcome Email" checkbox (checked by default)
- "Create User" button

**Edit User Modal:**
- Edit name, role, status
- "Reset Password" button (sends email)
- "View Activity" button
- "Save Changes" button

**Activity Log:**
- Filterable timeline:
  - User filter (dropdown)
  - Action type filter (checkboxes)
  - Date range
- Each entry:
  - Timestamp
  - User avatar + name
  - Action description (e.g., "Added channel 'Al-Madinah TV' to Quran > Recitation")
  - Link to content (if applicable)
- Export to CSV button

**Notifications Panel (dropdown from bell icon):**
- List of recent notifications:
  - "[Moderator Name] submitted new content" (timestamp)
  - "[Admin Name] approved your submission" (timestamp)
  - System notifications
- "Mark all as read" button
- "View all" link to dedicated page

**Settings Page:**
- Sections:
  - **Profile**: Edit name, email, password
  - **Notifications**: Toggle email notifications for various events
  - **YouTube API**: API key configuration (admin only)
  - **System**: App settings, cache management (admin only)

**Empty States (consistent across app):**
- Centered layout
- Relevant icon (64px, gray)
- Heading (20px, bold)
- Description (14px, gray)
- Call-to-action button (if applicable)
- Examples:
  - "No pending approvals" + checkmark icon
  - "No content found" + folder icon + "Add content to get started"
  - "No categories yet" + grid icon + "Create your first category"

**Loading States:**
- Skeleton loaders for lists/tables
- Spinner for buttons/actions
- Progress bar for long operations (YouTube API calls)
- Overlay with spinner for modal content loading

**Error States:**
- Toast notifications for errors (red, dismissible)
- Inline error messages for form validation (red text below field)
- Error page for API failures (with retry button)

**Success States:**
- Toast notifications (green, auto-dismiss after 3s)
- Success icon animations
- Optimistic UI updates where possible

---

## SECURITY REQUIREMENTS

**Firebase Security Rules:**
```javascript
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    
    // Helper function to check if user is admin
    function isAdmin() {
      return request.auth != null && request.auth.token.role == 'admin';
    }
    
    // Helper function to check if user is authenticated
    function isAuthenticated() {
      return request.auth != null;
    }
    
    // Users collection - only admins can write
    match /users/{userId} {
      allow read: if isAuthenticated();
      allow write: if isAdmin();
    }
    
    // Categories - admins write, all authenticated users read
    match /categories/{categoryId} {
      allow read: if isAuthenticated();
      allow create, update, delete: if isAdmin();
    }
    
    // Content - role-based access
    match /content/{contentId} {
      allow read: if isAuthenticated();
      allow create: if isAuthenticated(); // Moderators can create
      allow update: if isAdmin() || resource.data.createdBy == request.auth.uid;
      allow delete: if isAdmin();
    }
    
    // Activity log - write by authenticated, read by admins
    match /activityLog/{logId} {
      allow read: if isAdmin();
      allow create: if isAuthenticated();
      allow update, delete: if false; // Logs are immutable
    }
  }
}
```

**Backend Security (Spring Boot):**

**Firebase Token Verification:**
```java
@Component
public class FirebaseAuthFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, 
                                    HttpServletResponse response, 
                                    FilterChain filterChain) {
        String token = extractToken(request);
        FirebaseToken decodedToken = FirebaseAuth.getInstance().verifyIdToken(token);
        // Set authentication in SecurityContext
        // Continue filter chain
    }
}
```

**Role-Based Authorization:**
```java
@PreAuthorize("hasRole('ADMIN')")
@PostMapping("/api/v1/admin/users")
public ResponseEntity<?> createUser(@RequestBody UserDto user) {
    // Only admins can access
}

@PreAuthorize("hasAnyRole('ADMIN', 'MODERATOR')")
@PostMapping("/api/v1/admin/content")
public ResponseEntity<?> addContent(@RequestBody ContentDto content) {
    // Admins and moderators can access
}
```

**Security Measures:**
- HTTPS only in production (enforce via load balancer/reverse proxy)
- CORS configuration (whitelist admin frontend domain and mobile app)
- Rate limiting:
  - YouTube API: 100 searches per user per hour
  - Content creation: 50 per user per hour
  - Login attempts: 5 per IP per 15 minutes
- Input validation:
  - Validate YouTube IDs format
  - Sanitize all user inputs (XSS prevention)
  - Validate file uploads (size, type for icons)
- SQL injection prevention:
  - Use Firestore SDK (no SQL)
  - Parameterized queries if using any SQL database
- YouTube API key:
  - Store in environment variables (not in code)
  - Rotate periodically
  - Monitor quota usage
- Session management:
  - Firebase tokens expire after 1 hour
  - Refresh tokens automatically on frontend
  - Force logout on role change
- Audit logging:
  - Log all admin actions (create, update, delete)
  - Log authentication events (login, logout, failed attempts)
  - Store logs securely, retain for 90 days

---

## PERFORMANCE & OPTIMIZATION

**Frontend (Vue.js):**
- **Code splitting**: Lazy load routes and heavy components
- **Virtual scrolling**: For long lists (search results, content tables)
- **Debounce**: Search inputs (300ms), filter changes (500ms)
- **Image optimization**:
  - Lazy load images with Intersection Observer
  - Use YouTube thumbnail CDN
  - Show blur placeholder while loading
- **Caching**:
  - Cache category list (rarely changes)
  - Cache user info (invalidate on change)
  - Use Vue's keep-alive for expensive components
- **Build optimization**:
  - Minification
  - Tree shaking
  - Gzip compression
  - CSS purging (remove unused)
- **State management**: Vuex or Pinia for shared state
- **API request optimization**:
  - Batch requests where possible
  - Cancel pending requests on navigation
  - Show stale data while revalidating

**Backend (Spring Boot):**
- **Connection pooling**: For Firebase Admin SDK
- **Caching**:
  - Redis for frequently accessed data (categories, approved content counts)
  - Cache YouTube API responses (1 hour TTL)
  - Cache user roles/permissions
- **Async processing**:
  - YouTube API calls (use CompletableFuture)
  - Bulk operations (approve multiple items)
  - Email notifications (queue-based)
- **Pagination**:
  - Limit results to 50 max per page
  - Use cursor-based pagination for better performance (optional)
- **Database optimization**:
  - Proper Firestore indexes (see database structure section)
  - Batch reads where possible
  - Use select fields (don't fetch full documents if not needed)
- **API optimization**:
  - Gzip compression for responses
  - ETags for conditional requests
  - Response caching headers
- **Monitoring**:
  - Log slow queries (>1s)
  - Monitor API error rates
  - Track Firebase read/write counts

**YouTube API Optimization:**
- **Quota management**:
  - Track daily quota usage
  - Warn admins at 80% usage
  - Implement fallback (cached data) at 95%
- **Caching strategy**:
  - Cache channel/playlist/video metadata (24 hours)
  - Cache search results (1 hour)
  - Cache channel content (videos, playlists) (6 hours)
  - Invalidate on manual refresh
- **Batch requests**: Use YouTube batch API where possible
- **Error handling**:
  - Graceful degradation if YouTube API is down
  - Show cached data with "Last updated" timestamp
  - Retry logic with exponential backoff

**Firestore Optimization:**
- **Indexes** (see database structure for specifics)
- **Batch operations**: Use batched writes for bulk updates
- **Pagination**: Use startAfter for cursor-based pagination
- **Denormalization**: Store category names with content (avoid joins)
- **Subcollections**: Consider for channel exclusions if lists are very long
- **Read optimization**:
  - Fetch only required fields
  - Use shallow queries where possible
  - Limit query results to needed amount

---

## TESTING REQUIREMENTS

**Functional Testing:**
- **Authentication & Authorization**:
  - Admin login and access to all features
  - Moderator login with restricted access
  - Role-based endpoint protection
  - Token expiration and refresh
- **YouTube Content Search**:
  - Search channels, playlists, videos
  - Pagination of search results
  - Filters work correctly
  - Handle API errors gracefully
- **Content Management**:
  - Add content with categories
  - Add content with exclusions
  - Edit categories and exclusions
  - Delete content (soft delete)
  - View content details
- **Category System**:
  - Create parent categories
  - Create subcategories
  - Edit categories
  - Delete categories (with/without subcategories)
  - Category hierarchy displayed correctly
  - Filter content by parent category shows subcategory content
  - Filter by subcategory shows only subcategory content
- **Approval Workflow**:
  - Moderator submits content (status = pending)
  - Admin sees pending items
  - Admin approves content
  - Admin rejects content with reason
  - Notifications sent correctly
- **API Endpoints**:
  - All public endpoints return only approved content
  - Category filtering works correctly (parent includes subcategories)
  - Exclusions are filtered from results
  - Pagination works correctly
  - Search returns relevant results
- **User Management**:
  - Create users
  - Edit user roles
  - Deactivate users
  - Reset passwords
  - View activity logs

**Edge Cases:**
- **YouTube API Failures**:
  - API key invalid
  - Rate limit exceeded
  - Network timeout
  - Video/channel not found or deleted
  - Private or age-restricted content
- **Data Validation**:
  - Invalid YouTube IDs
  - Duplicate content submissions
  - Missing required fields
  - Invalid category assignments (non-existent category)
  - Circular parent category relationships
- **Permissions**:
  - Moderator tries to access admin-only features
  - Unauthenticated access attempts
  - Token expiration during operation
  - Role change while user is active
- **Content Issues**:
  - Content deleted from YouTube after adding
  - Channel exclusions exceed available content
  - Empty playlists
  - Content assigned to deleted category
- **UI Edge Cases**:
  - Very long titles/descriptions
  - Missing thumbnails
  - Slow network connections
  - Large datasets (1000+ items)
  - No search results
  - Empty states (no content, no categories)

**Browser Testing:**
- **Primary**: Chrome, Firefox (latest versions)
- **Secondary**: Safari, Edge
- **Minimum**: Chrome 90+, Firefox 88+
- Test responsive behavior (1366x768 to 1920x1080)
- Test keyboard navigation
- Test screen readers (WCAG 2.1 AA compliance)

**Load Testing (optional):**
- 50 concurrent users adding content
- 100 YouTube API searches per minute
- Large dataset rendering (1000+ items in table)

---

---

# PART 2: NATIVE ANDROID APP

## Core Purpose
Deliver curated Islamic educational content (videos, channels, playlists) without ads, with background audio playback, offline downloads, Chromecast support, and full Android TV Box compatibility. Content is filterable by hierarchical categories with subcategories.

## Technical Stack
- Native Android (Java)
- NewPipeExtractor: https://github.com/TeamNewPipe/NewPipeExtractor
- Backend REST API (Spring Boot + Firebase)
- Architecture: MVVM with Repository pattern
- Google Cast SDK (Chromecast - MANDATORY)
- Room Database for caching
- Retrofit for API calls
- Glide for image loading
- ExoPlayer for video playback
- Minimum SDK: API 24 (Android 7.0)
- Target SDK: API 34 (Android 14)

---

## CRITICAL: ANDROID TV BOX SUPPORT

**Important Context:**
This app will be used on Android TV boxes (generic Android boxes connected to TVs), NOT Google TV or Android TV OS. The app is primarily a mobile app but must be fully responsive and usable on large TV screens via these boxes.

**Responsive Design Requirements:**

**Screen Size Support:**
- Mobile phones: 5"-7" (primary target)
- Tablets: 7"-12"
- TV screens via Android boxes: 32"-75+"
- Handle all screen densities (mdpi to xxxhdpi)

**Layout Adaptations for TV (sw600dp+):**
- Increase grid columns: Videos 3-4 (vs 1-2), Playlists 3-4 (vs 2), Channels 2-3 (vs 1)
- Increase spacing: 24dp (vs 16dp)
- Larger touch targets: 72dp minimum (vs 48dp)
- Increase font sizes by 20-30%
- Side margins: 48dp (vs 16dp)

**Navigation for TV:**
- D-pad/remote control navigation
- Focus states with teal border (3dp)
- Logical tab order
- Back button works with remote
- Long-press for context menus

**Video Player on TV:**
- Default to fullscreen
- Larger controls (72dp minimum)
- Remote-friendly button sizes
- Clear visual feedback on focus

**Implementation Strategy:**

**Resource Qualifiers:**
```
res/
  layout/                    # Phone (default)
  layout-sw600dp/           # Tablets & TV boxes
  layout-sw720dp/           # Large TVs
  values/dimens.xml         # Default dimensions
  values-sw600dp/dimens.xml # Tablet/TV dimensions
```xml
  values-sw720dp/dimens.xml # Large TV dimensions
```

**Dimension Examples:**
```xml
<!-- values/dimens.xml (Mobile) -->
<dimen name="grid_columns">2</dimen>
<dimen name="card_padding">16dp</dimen>
<dimen name="screen_margin">16dp</dimen>
<dimen name="touch_target_min">48dp</dimen>
<dimen name="text_header">24sp</dimen>
<dimen name="text_title">16sp</dimen>
<dimen name="text_body">14sp</dimen>
<dimen name="text_caption">12sp</dimen>

<!-- values-sw600dp/dimens.xml (Tablets/Small TVs) -->
<dimen name="grid_columns">3</dimen>
<dimen name="card_padding">20dp</dimen>
<dimen name="screen_margin">32dp</dimen>
<dimen name="touch_target_min">60dp</dimen>
<dimen name="text_header">28sp</dimen>
<dimen name="text_title">20sp</dimen>
<dimen name="text_body">16sp</dimen>
<dimen name="text_caption">14sp</dimen>

<!-- values-sw720dp/dimens.xml (Large TVs) -->
<dimen name="grid_columns">4</dimen>
<dimen name="card_padding">24dp</dimen>
<dimen name="screen_margin">48dp</dimen>
<dimen name="touch_target_min">72dp</dimen>
<dimen name="text_header">32sp</dimen>
<dimen name="text_title">22sp</dimen>
<dimen name="text_body">18sp</dimen>
<dimen name="text_caption">14sp</dimen>
```

**Focus Management:**
```xml
<!-- Focus drawable (res/drawable/focus_border.xml) -->
<shape xmlns:android="http://schemas.android.com/apk/res/android">
    <stroke android:width="3dp" android:color="#2D7A6B"/>
    <corners android:radius="12dp"/>
</shape>

<!-- Apply to focusable views -->
<style name="FocusableCard">
    <item name="android:focusable">true</item>
    <item name="android:focusableInTouchMode">false</item>
    <item name="android:background">@drawable/card_focus_selector</item>
</style>
```

**Remote Control Mapping:**
- D-pad: Navigation
- Center button: Select/Enter
- Back button: Previous screen
- Menu button: Options menu
- Play/Pause: Video control

**Performance Considerations for TV Boxes:**
- Reduce image quality for TV (use lower resolution from Glide)
- Aggressive caching strategy
- Optimize RecyclerView with ViewHolder pattern
- Lazy load images
- Handle low RAM gracefully

**TV-Specific Features:**
- Screen saver after 5 minutes idle
- Volume control integration
- HDMI-CEC support (optional)
- Auto-detect TV mode and adjust UI

---

## DESIGN SYSTEM

**Color Palette:**
```xml
<!-- res/values/colors.xml -->
<color name="primary">#2D7A6B</color>
<color name="primary_light">#4A9B8E</color>
<color name="primary_lighter">#A8D5CE</color>
<color name="surface">#FFFFFF</color>
<color name="background">#F5F5F5</color>
<color name="text_primary">#1A1A1A</color>
<color name="text_secondary">#4A4A4A</color>
<color name="text_tertiary">#4A9B8E</color>
<color name="border">#E0E0E0</color>
<color name="focus_border">#2D7A6B</color>
<color name="error">#D32F2F</color>
<color name="success">#388E3C</color>
```

**Typography:**
```xml
<!-- res/values/styles.xml -->
<style name="TextAppearance.Header" parent="TextAppearance.AppCompat">
    <item name="android:textSize">24sp</item>
    <item name="android:textStyle">bold</item>
    <item name="android:textColor">@color/text_primary</item>
</style>

<style name="TextAppearance.Title" parent="TextAppearance.AppCompat">
    <item name="android:textSize">16sp</item>
    <item name="android:textStyle">normal</item>
    <item name="android:textColor">@color/text_primary</item>
</style>

<style name="TextAppearance.Body" parent="TextAppearance.AppCompat">
    <item name="android:textSize">14sp</item>
    <item name="android:textColor">@color/text_secondary</item>
</style>

<style name="TextAppearance.Caption" parent="TextAppearance.AppCompat">
    <item name="android:textSize">12sp</item>
    <item name="android:textColor">@color/text_tertiary</item>
</style>

<!-- TV overrides in values-sw600dp/styles.xml -->
<style name="TextAppearance.Header" parent="TextAppearance.AppCompat">
    <item name="android:textSize">32sp</item>
    <item name="android:textStyle">bold</item>
</style>
```

**Component Styles:**

**Cards:**
```xml
<style name="CardStyle">
    <item name="cardBackgroundColor">@color/surface</item>
    <item name="cardCornerRadius">12dp</item>
    <item name="cardElevation">2dp</item>
    <item name="contentPadding">16dp</item>
</style>
```

**Buttons:**
```xml
<style name="Button.Primary">
    <item name="android:background">@drawable/button_primary_bg</item>
    <item name="android:textColor">@android:color/white</item>
    <item name="android:minHeight">@dimen/touch_target_min</item>
</style>

<style name="Button.Secondary">
    <item name="android:background">@drawable/button_secondary_bg</item>
    <item name="android:textColor">@color/primary</item>
</style>
```

**Category Tags:**
```xml
<shape xmlns:android="http://schemas.android.com/apk/res/android">
    <solid android:color="@android:color/transparent"/>
    <stroke android:width="1dp" android:color="@color/primary"/>
    <corners android:radius="16dp"/>
</shape>
```

**Islamic Design Elements:**
- Geometric patterns in empty states
- Mosque illustrations for splash/onboarding
- Subtle patterns in backgrounds
- Teal color scheme consistent with Islamic aesthetic

---

## APP ARCHITECTURE

**MVVM Pattern:**

```
app/
├── data/
│   ├── model/         # Data classes (Video, Channel, Playlist, Category)
│   ├── local/         # Room database, DAOs
│   ├── remote/        # Retrofit API interfaces
│   └── repository/    # Repository pattern
├── ui/
│   ├── home/          # Home screen (ViewModel, Fragment)
│   ├── channels/      # Channels screen
│   ├── playlists/     # Playlists screen
│   ├── videos/        # Videos screen
│   ├── player/        # Video player
│   ├── categories/    # Category selection
│   └── common/        # Shared UI components
├── utils/             # Utility classes
└── Application.java   # Application class
```

**Data Models:**

```java
// Category.java
public class Category {
    private String id;
    private String name;
    private String parentId;  // null if parent category
    private String icon;
    private List<Category> subcategories;  // Populated on client
    
    // Getters/setters
    public boolean isParentCategory() {
        return parentId == null;
    }
    
    public boolean hasSubcategories() {
        return subcategories != null && !subcategories.isEmpty();
    }
}

// Content.java (base class)
public abstract class Content {
    private String id;
    private String youtubeId;
    private String title;
    private String thumbnail;
    private List<String> categoryIds;
    private List<Category> categories;  // Populated from categoryIds
}

// Video.java
public class Video extends Content {
    private int duration;
    private String channelName;
    private String channelId;
    private long views;
    private String uploadedAt;
}

// Channel.java
public class Channel extends Content {
    private long subscribers;
    private int videoCount;
    private String banner;
    private String description;
}

// Playlist.java
public class Playlist extends Content {
    private int videoCount;
    private String channelName;
    private String channelId;
}
```

**Room Database:**

```java
@Database(entities = {VideoEntity.class, ChannelEntity.class, 
                      PlaylistEntity.class, CategoryEntity.class}, 
          version = 1)
public abstract class AppDatabase extends RoomDatabase {
    public abstract VideoDao videoDao();
    public abstract ChannelDao channelDao();
    public abstract PlaylistDao playlistDao();
    public abstract CategoryDao categoryDao();
}

@Entity(tableName = "categories")
public class CategoryEntity {
    @PrimaryKey
    @NonNull
    private String id;
    private String name;
    private String parentId;
    private String icon;
    private long cachedAt;
    
    // Getters/setters
}

// DAO with category hierarchy queries
@Dao
public interface CategoryDao {
    @Query("SELECT * FROM categories WHERE parentId IS NULL ORDER BY name")
    LiveData<List<CategoryEntity>> getParentCategories();
    
    @Query("SELECT * FROM categories WHERE parentId = :parentId ORDER BY name")
    LiveData<List<CategoryEntity>> getSubcategories(String parentId);
    
    @Query("SELECT * FROM categories")
    LiveData<List<CategoryEntity>> getAllCategories();
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertCategories(List<CategoryEntity> categories);
}
```

**Repository Pattern:**

```java
public class CategoryRepository {
    private CategoryDao categoryDao;
    private ApiService apiService;
    
    public LiveData<List<Category>> getCategories() {
        // Return cached categories
        LiveData<List<CategoryEntity>> cached = categoryDao.getAllCategories();
        
        // Refresh from API if stale (>24 hours)
        refreshCategoriesIfNeeded();
        
        // Transform entities to domain models with hierarchy
        return Transformations.map(cached, this::buildCategoryHierarchy);
    }
    
    private List<Category> buildCategoryHierarchy(List<CategoryEntity> entities) {
        // Build tree structure: parents with nested subcategories
        Map<String, Category> categoryMap = new HashMap<>();
        List<Category> parents = new ArrayList<>();
        
        // First pass: create all categories
        for (CategoryEntity entity : entities) {
            Category cat = entityToModel(entity);
            categoryMap.put(cat.getId(), cat);
        }
        
        // Second pass: build hierarchy
        for (Category cat : categoryMap.values()) {
            if (cat.isParentCategory()) {
                parents.add(cat);
            } else {
                Category parent = categoryMap.get(cat.getParentId());
                if (parent != null) {
                    parent.getSubcategories().add(cat);
                }
            }
        }
        
        return parents;
    }
}

public class ContentRepository {
    private ApiService apiService;
    private VideoDao videoDao;
    
    public LiveData<PaginatedResponse<Video>> getVideos(int page, String categoryId) {
        // Fetch from API with category filter
        // Cache results
        // Return LiveData
    }
    
    public LiveData<PaginatedResponse<Channel>> getChannels(int page, String categoryId) {
        // Similar implementation
    }
}
```

**Retrofit API Interface:**

```java
public interface ApiService {
    @GET("categories")
    Call<ApiResponse<List<Category>>> getCategories();
    
    @GET("home/recent")
    Call<ApiResponse<HomeContent>> getHomeContent(
        @Query("page") int page,
        @Query("limit") int limit,
        @Query("categoryId") String categoryId
    );
    
    @GET("channels")
    Call<ApiResponse<List<Channel>>> getChannels(
        @Query("page") int page,
        @Query("limit") int limit,
        @Query("categoryId") String categoryId,
        @Query("sort") String sort
    );
    
    @GET("channels/{id}")
    Call<ApiResponse<Channel>> getChannelDetail(@Path("id") String channelId);
    
    @GET("channels/{id}/videos")
    Call<ApiResponse<List<Video>>> getChannelVideos(
        @Path("id") String channelId,
        @Query("page") int page,
        @Query("limit") int limit
    );
    
    // Similar for playlists, videos, search, etc.
}
```

---

## APP SCREENS & NAVIGATION

### 1. SPLASH SCREEN

**Layout (activity_splash.xml):**
```xml
<androidx.constraintlayout.widget.ConstraintLayout>
    <ImageView
        android:id="@+id/logo"
        android:layout_width="@dimen/splash_logo_size"
        android:layout_height="@dimen/splash_logo_size"
        android:src="@drawable/app_logo"
        app:layout_constraintTop_toTopOf="parent"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent"/>
    
    <TextView
        android:id="@+id/appName"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="@string/app_name"
        android:textSize="@dimen/splash_app_name_size"
        android:textStyle="bold"
        app:layout_constraintTop_toBottomOf="@id/logo"
        android:layout_marginTop="16dp"/>
    
    <TextView
        android:id="@+id/tagline"
        android:text="@string/tagline"
        android:textSize="@dimen/splash_tagline_size"
        android:textColor="@color/text_secondary"
        android:layout_marginTop="8dp"/>
    
    <ProgressBar
        android:id="@+id/loadingIndicator"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        app:layout_constraintBottom_toBottomOf="parent"
        android:layout_marginBottom="48dp"/>
</androidx.constraintlayout.widget.ConstraintLayout>
```

**SplashActivity.java:**
```java
public class SplashActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);
        
        // Initialize NewPipeExtractor
        NewPipe.init(DownloaderImpl.init(null));
        
        // Initialize Room Database
        AppDatabase.getInstance(this);
        
        // Check if first launch
        SharedPreferences prefs = getSharedPreferences("app_prefs", MODE_PRIVATE);
        boolean isFirstLaunch = prefs.getBoolean("is_first_launch", true);
        
        // Detect screen size for TV mode
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int widthDp = (int) (metrics.widthPixels / metrics.density);
        boolean isTVMode = widthDp >= 600;
        prefs.edit().putBoolean("tv_mode", isTVMode).apply();
        
        new Handler().postDelayed(() -> {
            Intent intent;
            if (isFirstLaunch) {
                intent = new Intent(this, OnboardingActivity.class);
            } else {
                intent = new Intent(this, MainActivity.class);
            }
            startActivity(intent);
            finish();
        }, 2000);
    }
}
```

---

### 2. ONBOARDING SCREENS

**Layout (fragment_onboarding.xml):**
```xml
<androidx.constraintlayout.widget.ConstraintLayout>
    <!-- Progress dots -->
    <LinearLayout
        android:id="@+id/dotsIndicator"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        app:layout_constraintTop_toTopOf="parent"
        android:layout_marginTop="24dp"/>
    
    <!-- Skip button -->
    <TextView
        android:id="@+id/skipButton"
        android:text="@string/skip"
        android:textColor="@color/primary"
        android:padding="@dimen/touch_target_min"
        app:layout_constraintTop_toTopOf="parent"
        app:layout_constraintEnd_toEndOf="parent"/>
    
    <!-- Illustration -->
    <ImageView
        android:id="@+id/illustration"
        android:layout_width="@dimen/onboarding_illustration_size"
        android:layout_height="@dimen/onboarding_illustration_size"
        app:layout_constraintTop_toTopOf="parent"
        app:layout_constraintBottom_toBottomOf="parent"/>
    
    <!-- Title -->
    <TextView
        android:id="@+id/title"
        android:textSize="@dimen/onboarding_title_size"
        android:textStyle="bold"
        android:gravity="center"
        app:layout_constraintTop_toBottomOf="@id/illustration"/>
    
    <!-- Description -->
    <TextView
        android:id="@+id/description"
        android:textSize="@dimen/onboarding_description_size"
        android:textColor="@color/text_secondary"
        android:gravity="center"
        android:layout_marginTop="16dp"/>
    
    <!-- Next button -->
    <Button
        android:id="@+id/nextButton"
        android:text="@string/next"
        style="@style/Button.Primary"
        android:layout_width="match_parent"
        android:layout_height="@dimen/touch_target_min"
        app:layout_constraintBottom_toBottomOf="parent"
        android:layout_margin="@dimen/screen_margin"/>
</androidx.constraintlayout.widget.ConstraintLayout>
```

**OnboardingActivity.java:**
```java
public class OnboardingActivity extends AppCompatActivity {
    private ViewPager2 viewPager;
    private OnboardingAdapter adapter;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_onboarding);
        
        viewPager = findViewById(R.id.viewPager);
        adapter = new OnboardingAdapter(this, getOnboardingPages());
        viewPager.setAdapter(adapter);
        
        // Handle next/skip buttons
        // Update dots indicator
        // Last page: "Get Started" -> MainActivity
    }
    
    private List<OnboardingPage> getOnboardingPages() {
        return Arrays.asList(
            new OnboardingPage(R.drawable.ic_compass, 
                              "Browse", 
                              "Explore a diverse collection of Islamic videos..."),
            new OnboardingPage(R.drawable.ic_headphones,
                              "Listen in background",
                              "Continue listening to lectures..."),
            new OnboardingPage(R.drawable.ic_download,
                              "Download & Watch Offline",
                              "Save videos to watch later...")
        );
    }
}
```

---

### 3. MAIN SCREEN WITH BOTTOM NAVIGATION

**activity_main.xml:**
```xml
<androidx.constraintlayout.widget.ConstraintLayout>
    <!-- Toolbar -->
    <androidx.appcompat.widget.Toolbar
        android:id="@+id/toolbar"
        android:layout_width="match_parent"
        android:layout_height="?attr/actionBarSize"
        android:background="@color/surface"
        android:elevation="4dp">
        
        <TextView
            android:id="@+id/toolbarTitle"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_gravity="center"
            android:text="@string/app_name"
            android:textSize="20sp"
            android:textStyle="bold"/>
        
        <ImageView
            android:id="@+id/searchIcon"
            android:layout_width="@dimen/touch_target_min"
            android:layout_height="@dimen/touch_target_min"
            android:layout_gravity="end"
            android:src="@drawable/ic_search"
            android:padding="12dp"
            android:background="?attr/selectableItemBackgroundBorderless"/>
    </androidx.appcompat.widget.Toolbar>
    
    <!-- Category button -->
    <Button
        android:id="@+id/categoryButton"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="@string/category"
        android:drawableStart="@drawable/ic_category"
        android:drawablePadding="8dp"
        android:background="@color/primary_lighter"
        android:textColor="@color/text_primary"
        android:gravity="center_vertical"
        android:padding="12dp"
        android:layout_margin="@dimen/screen_margin"
        app:layout_constraintTop_toBottomOf="@id/toolbar"/>
    
    <!-- Fragment container -->
    <androidx.fragment.app.FragmentContainerView
        android:id="@+id/fragmentContainer"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        app:layout_constraintTop_toBottomOf="@id/categoryButton"
        app:layout_constraintBottom_toTopOf="@id/bottomNav"/>
    
    <!-- Bottom Navigation -->
    <com.google.android.material.bottomnavigation.BottomNavigationView
        android:id="@+id/bottomNav"
        android:layout_width="match_parent"
        android:layout_height="@dimen/bottom_nav_height"
        android:background="@color/surface"
        android:elevation="8dp"
        app:layout_constraintBottom_toBottomOf="parent"
        app:menu="@menu/bottom_nav_menu"
        app:itemIconTint="@color/bottom_nav_selector"
        app:itemTextColor="@color/bottom_nav_selector"
        app:labelVisibilityMode="labeled"/>
</androidx.constraintlayout.widget.ConstraintLayout>
```

**bottom_nav_menu.xml:**
```xml
<menu xmlns:android="http://schemas.android.com/apk/res/android">
    <item
        android:id="@+id/nav_home"
        android:icon="@drawable/ic_home"
        android:title="@string/home"/>
    <item
        android:id="@+id/nav_channels"
        android:icon="@drawable/ic_channels"
        android:title="@string/channels"/>
    <item
        android:id="@+id/nav_playlists"
        android:icon="@drawable/ic_playlists"
        android:title="@string/playlists"/>
    <item
        android:id="@+id/nav_videos"
        android:icon="@drawable/ic_videos"
        android:title="@string/videos"/>
</menu>
```

**MainActivity.java:**
```java
public class MainActivity extends AppCompatActivity {
    private BottomNavigationView bottomNav;
    private Button categoryButton;
    private TextView toolbarTitle;
    private CategoryManager categoryManager;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        categoryManager = CategoryManager.getInstance();
        
        bottomNav = findViewById(R.id.bottomNav);
        categoryButton = findViewById(R.id.categoryButton);
        toolbarTitle = findViewById(R.id.toolbarTitle);
        
        // Load default fragment
        loadFragment(new HomeFragment());
        
        // Bottom navigation listener
        bottomNav.setOnItemSelectedItem(item -> {
            Fragment fragment = null;
            switch (item.getItemId()) {
                case R.id.nav_home:
                    fragment = new HomeFragment();
                    toolbarTitle.setText(R.string.app_name);
                    break;
                case R.id.nav_channels:
                    fragment = new ChannelsFragment();
                    toolbarTitle.setText(R.string.channels);
                    break;
                case R.id.nav_playlists:
                    fragment = new PlaylistsFragment();
                    toolbarTitle.setText(R.string.playlists);
                    break;
                case R.id.nav_videos:
                    fragment = new VideosFragment();
                    toolbarTitle.setText(R.string.videos);
                    break;
            }
            return loadFragment(fragment);
        });
        
        // Category button
        categoryButton.setOnClickListener(v -> {
            Intent intent = new Intent(this, CategoryActivity.class);
            startActivityForResult(intent, REQUEST_CATEGORY);
        });
        
        // Update category button text if category selected
        updateCategoryButton();
        
        // Search icon
        findViewById(R.id.searchIcon).setOnClickListener(v -> {
            Intent intent = new Intent(this, SearchActivity.class);
            startActivity(intent);
        });
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CATEGORY && resultCode == RESULT_OK) {
            // Category selected, refresh content
            updateCategoryButton();
            refreshCurrentFragment();
        }
    }
    
    private void updateCategoryButton() {
        Category selected = categoryManager.getSelectedCategory();
        if (selected != null) {
            // Show "Parent > Subcategory" or just "Category"
            String text = selected.isParentCategory() ? 
                          selected.getName() : 
                          selected.getParentName() + " > " + selected.getName();
            categoryButton.setText(text);
        } else {
            categoryButton.setText(R.string.category);
        }
    }
    
    private boolean loadFragment(Fragment fragment) {
        if (fragment != null) {
            getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragmentContainer, fragment)
                .commit();
            return true;
        }
        return false;
    }
}
```

---

### 4. CATEGORY SELECTION SCREEN

**activity_category.xml:**
```xml
<androidx.constraintlayout.widget.ConstraintLayout>
    <!-- Toolbar -->
    <androidx.appcompat.widget.Toolbar
        android:id="@+id/toolbar"
        android:layout_width="match_parent"
        android:layout_height="?attr/actionBarSize"
        android:background="@color/surface"
        android:elevation="4dp">
        
        <ImageView
            android:id="@+id/backButton"
            android:layout_width="@dimen/touch_target_min"
            android:layout_height="@dimen/touch_target_min"
            android:src="@drawable/ic_back"
            android:padding="12dp"/>
        
        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_gravity="center"
            android:text="@string/categories"
            android:textSize="20sp"
            android:textStyle="bold"/>
    </androidx.appcompat.widget.Toolbar>
    
    <!-- RecyclerView for categories -->
    <androidx.recyclerview.widget.RecyclerView
        android:id="@+id/categoriesRecyclerView"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:padding="@dimen/screen_margin"
        app:layout_constraintTop_toBottomOf="@id/toolbar"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layoutManager="androidx.recyclerview.widget.LinearLayoutManager"/>
    
    <!-- Loading indicator -->
    <ProgressBar
        android:id="@+id/loadingIndicator"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:visibility="gone"
        app:layout_constraintTop_toTopOf="parent"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent"/>
</androidx.constraintlayout.widget.ConstraintLayout>
```

**item_category.xml:**
```xml
<androidx.cardview.widget.CardView
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:layout_marginBottom="12dp"
    app:cardCornerRadius="12dp"
    app:cardElevation="2dp"
    android:foreground="?attr/selectableItemBackground">
    
    <androidx.constraintlayout.widget.ConstraintLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:padding="16dp">
        
        <TextView
            android:id="@+id/categoryName"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:textSize="18sp"
            android:textStyle="bold"
            android:textColor="@color/text_primary"
            app:layout_constraintStart_toStartOf="parent"
            app:layout_constraintEnd_toStartOf="@id/chevron"
            app:layout_constraintTop_toTopOf="parent"/>
        
        <ImageView
            android:id="@+id/chevron"
            android:layout_width="24dp"
            android:layout_height="24dp"
            android:src="@drawable/ic_chevron_right"
            android:visibility="gone"
            app:layout_constraintEnd_toEndOf="parent"
            app:layout_constraintTop_toTopOf="parent"
            app:layout_constraintBottom_toBottomOf="parent"/>
    </androidx.constraintlayout.widget.ConstraintLayout>
</androidx.cardview.widget.CardView>
```

**CategoryActivity.java:**
```java
public class CategoryActivity extends AppCompatActivity {
    private RecyclerView recyclerView;
    private CategoryAdapter adapter;
    private CategoryViewModel viewModel;
    private List<Category> currentLevel;  // Current category level being displayed
    private Category currentParent;  // null if showing top-level
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_category);
        
        recyclerView = findViewById(R.id.categoriesRecyclerView);
        viewModel = new ViewModelProvider(this).get(CategoryViewModel.class);
        
        adapter = new CategoryAdapter(this::onCategoryClick);
        recyclerView.setAdapter(adapter);
        
        // Load categories from ViewModel
        viewModel.getCategories().observe(this, categories -> {
            if (currentParent == null) {
                // Show top-level categories
                adapter.setCategories(categories);
            }
        });
        
        findViewById(R.id.backButton).setOnClickListener(v -> onBackPressed());
    }
    
    private void onCategoryClick
    ```java
    private void onCategoryClick(Category category) {
        if (category.hasSubcategories()) {
            // Navigate to subcategories screen
            Intent intent = new Intent(this, SubcategoryActivity.class);
            intent.putExtra("parent_category", category);
            startActivityForResult(intent, REQUEST_SUBCATEGORY);
        } else {
            // Select this category (no subcategories)
            selectCategory(category);
        }
    }
    
    private void selectCategory(Category category) {
        CategoryManager.getInstance().setSelectedCategory(category);
        setResult(RESULT_OK);
        finish();
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_SUBCATEGORY && resultCode == RESULT_OK) {
            // Subcategory was selected, pass result back
            setResult(RESULT_OK);
            finish();
        }
    }
}
```

**SubcategoryActivity.java:**
```java
public class SubcategoryActivity extends AppCompatActivity {
    private RecyclerView recyclerView;
    private CategoryAdapter adapter;
    private Category parentCategory;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_category);  // Reuse same layout
        
        parentCategory = getIntent().getParcelableExtra("parent_category");
        
        // Update toolbar title
        TextView title = findViewById(R.id.toolbarTitle);
        title.setText(parentCategory.getName() + " - Subcategories");
        
        recyclerView = findViewById(R.id.categoriesRecyclerView);
        adapter = new CategoryAdapter(this::onSubcategoryClick);
        recyclerView.setAdapter(adapter);
        
        // Load subcategories
        adapter.setCategories(parentCategory.getSubcategories());
        
        findViewById(R.id.backButton).setOnClickListener(v -> finish());
    }
    
    private void onSubcategoryClick(Category subcategory) {
        // Select this subcategory
        CategoryManager.getInstance().setSelectedCategory(subcategory);
        setResult(RESULT_OK);
        finish();
    }
}
```

**CategoryAdapter.java:**
```java
public class CategoryAdapter extends RecyclerView.Adapter<CategoryAdapter.ViewHolder> {
    private List<Category> categories = new ArrayList<>();
    private OnCategoryClickListener listener;
    
    public interface OnCategoryClickListener {
        void onCategoryClick(Category category);
    }
    
    public CategoryAdapter(OnCategoryClickListener listener) {
        this.listener = listener;
    }
    
    public void setCategories(List<Category> categories) {
        this.categories = categories;
        notifyDataSetChanged();
    }
    
    @Override
    public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_category, parent, false);
        return new ViewHolder(view);
    }
    
    @Override
    public void onBindViewHolder(ViewHolder holder, int position) {
        Category category = categories.get(position);
        holder.categoryName.setText(category.getName());
        
        // Show chevron if category has subcategories
        holder.chevron.setVisibility(
            category.hasSubcategories() ? View.VISIBLE : View.GONE
        );
        
        holder.itemView.setOnClickListener(v -> listener.onCategoryClick(category));
    }
    
    @Override
    public int getItemCount() {
        return categories.size();
    }
    
    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView categoryName;
        ImageView chevron;
        
        ViewHolder(View itemView) {
            super(itemView);
            categoryName = itemView.findViewById(R.id.categoryName);
            chevron = itemView.findViewById(R.id.chevron);
        }
    }
}
```

**CategoryManager.java (Singleton):**
```java
public class CategoryManager {
    private static CategoryManager instance;
    private Category selectedCategory;
    private List<CategoryChangeListener> listeners = new ArrayList<>();
    
    public interface CategoryChangeListener {
        void onCategoryChanged(Category category);
    }
    
    public static CategoryManager getInstance() {
        if (instance == null) {
            instance = new CategoryManager();
        }
        return instance;
    }
    
    public void setSelectedCategory(Category category) {
        this.selectedCategory = category;
        notifyListeners();
    }
    
    public Category getSelectedCategory() {
        return selectedCategory;
    }
    
    public String getSelectedCategoryId() {
        return selectedCategory != null ? selectedCategory.getId() : null;
    }
    
    public void clearSelectedCategory() {
        this.selectedCategory = null;
        notifyListeners();
    }
    
    public void addListener(CategoryChangeListener listener) {
        listeners.add(listener);
    }
    
    public void removeListener(CategoryChangeListener listener) {
        listeners.remove(listener);
    }
    
    private void notifyListeners() {
        for (CategoryChangeListener listener : listeners) {
            listener.onCategoryChanged(selectedCategory);
        }
    }
}
```

---

### 5. HOME FRAGMENT

**fragment_home.xml:**
```xml
<androidx.core.widget.NestedScrollView
    android:layout_width="match_parent"
    android:layout_height="match_parent">
    
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical">
        
        <!-- Channels Section -->
        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="@string/channels"
            android:textSize="@dimen/text_header"
            android:textStyle="bold"
            android:layout_marginStart="@dimen/screen_margin"
            android:layout_marginTop="@dimen/screen_margin"/>
        
        <TextView
            android:id="@+id/seeAllChannels"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="@string/see_all"
            android:textColor="@color/primary"
            android:layout_gravity="end"
            android:layout_marginEnd="@dimen/screen_margin"
            android:padding="8dp"/>
        
        <androidx.recyclerview.widget.RecyclerView
            android:id="@+id/channelsRecyclerView"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="horizontal"
            app:layoutManager="androidx.recyclerview.widget.LinearLayoutManager"
            android:clipToPadding="false"
            android:paddingStart="@dimen/screen_margin"
            android:paddingEnd="@dimen/screen_margin"/>
        
        <!-- Playlists Section -->
        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="@string/playlists"
            android:textSize="@dimen/text_header"
            android:textStyle="bold"
            android:layout_marginStart="@dimen/screen_margin"
            android:layout_marginTop="24dp"/>
        
        <TextView
            android:id="@+id/seeAllPlaylists"
            android:text="@string/see_all"
            android:textColor="@color/primary"
            android:layout_gravity="end"
            android:layout_marginEnd="@dimen/screen_margin"
            android:padding="8dp"/>
        
        <androidx.recyclerview.widget.RecyclerView
            android:id="@+id/playlistsRecyclerView"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="horizontal"
            app:layoutManager="androidx.recyclerview.widget.LinearLayoutManager"
            android:clipToPadding="false"
            android:paddingStart="@dimen/screen_margin"
            android:paddingEnd="@dimen/screen_margin"/>
        
        <!-- Videos Section -->
        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="@string/videos"
            android:textSize="@dimen/text_header"
            android:textStyle="bold"
            android:layout_marginStart="@dimen/screen_margin"
            android:layout_marginTop="24dp"/>
        
        <TextView
            android:id="@+id/seeAllVideos"
            android:text="@string/see_all"
            android:textColor="@color/primary"
            android:layout_gravity="end"
            android:layout_marginEnd="@dimen/screen_margin"
            android:padding="8dp"/>
        
        <androidx.recyclerview.widget.RecyclerView
            android:id="@+id/videosRecyclerView"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            app:layoutManager="androidx.recyclerview.widget.LinearLayoutManager"
            android:nestedScrollingEnabled="false"
            android:paddingStart="@dimen/screen_margin"
            android:paddingEnd="@dimen/screen_margin"/>
    </LinearLayout>
</androidx.core.widget.NestedScrollView>
```

**HomeFragment.java:**
```java
public class HomeFragment extends Fragment implements CategoryManager.CategoryChangeListener {
    private HomeViewModel viewModel;
    private ChannelAdapter channelAdapter;
    private PlaylistAdapter playlistAdapter;
    private VideoAdapter videoAdapter;
    
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_home, container, false);
        
        viewModel = new ViewModelProvider(this).get(HomeViewModel.class);
        
        // Setup RecyclerViews
        RecyclerView channelsRv = view.findViewById(R.id.channelsRecyclerView);
        channelAdapter = new ChannelAdapter(this::onChannelClick, true); // true = horizontal
        channelsRv.setAdapter(channelAdapter);
        
        RecyclerView playlistsRv = view.findViewById(R.id.playlistsRecyclerView);
        playlistAdapter = new PlaylistAdapter(this::onPlaylistClick, true);
        playlistsRv.setAdapter(playlistAdapter);
        
        RecyclerView videosRv = view.findViewById(R.id.videosRecyclerView);
        videoAdapter = new VideoAdapter(this::onVideoClick);
        videosRv.setAdapter(videoAdapter);
        
        // Observe data
        observeData();
        
        // Load initial data
        loadData();
        
        // See all buttons
        view.findViewById(R.id.seeAllChannels).setOnClickListener(v -> 
            navigateToTab(R.id.nav_channels));
        view.findViewById(R.id.seeAllPlaylists).setOnClickListener(v -> 
            navigateToTab(R.id.nav_playlists));
        view.findViewById(R.id.seeAllVideos).setOnClickListener(v -> 
            navigateToTab(R.id.nav_videos));
        
        // Register for category changes
        CategoryManager.getInstance().addListener(this);
        
        return view;
    }
    
    private void observeData() {
        viewModel.getHomeContent().observe(getViewLifecycleOwner(), homeContent -> {
            if (homeContent != null) {
                channelAdapter.setChannels(homeContent.getChannels());
                playlistAdapter.setPlaylists(homeContent.getPlaylists());
                videoAdapter.setVideos(homeContent.getVideos());
            }
        });
    }
    
    private void loadData() {
        String categoryId = CategoryManager.getInstance().getSelectedCategoryId();
        viewModel.loadHomeContent(categoryId);
    }
    
    @Override
    public void onCategoryChanged(Category category) {
        // Reload data with new category filter
        loadData();
    }
    
    private void onChannelClick(Channel channel) {
        Intent intent = new Intent(getActivity(), ChannelDetailActivity.class);
        intent.putExtra("channel_id", channel.getId());
        startActivity(intent);
    }
    
    private void onPlaylistClick(Playlist playlist) {
        Intent intent = new Intent(getActivity(), PlaylistDetailActivity.class);
        intent.putExtra("playlist_id", playlist.getId());
        startActivity(intent);
    }
    
    private void onVideoClick(Video video) {
        Intent intent = new Intent(getActivity(), VideoPlayerActivity.class);
        intent.putExtra("video_id", video.getId());
        startActivity(intent);
    }
    
    private void navigateToTab(int tabId) {
        BottomNavigationView bottomNav = getActivity().findViewById(R.id.bottomNav);
        bottomNav.setSelectedItemId(tabId);
    }
    
    @Override
    public void onDestroyView() {
        super.onDestroyView();
        CategoryManager.getInstance().removeListener(this);
    }
}
```

**HomeViewModel.java:**
```java
public class HomeViewModel extends ViewModel {
    private ContentRepository repository;
    private MutableLiveData<HomeContent> homeContentLiveData = new MutableLiveData<>();
    
    public HomeViewModel() {
        repository = new ContentRepository();
    }
    
    public LiveData<HomeContent> getHomeContent() {
        return homeContentLiveData;
    }
    
    public void loadHomeContent(String categoryId) {
        repository.getHomeContent(1, 20, categoryId, new Callback<HomeContent>() {
            @Override
            public void onSuccess(HomeContent content) {
                homeContentLiveData.postValue(content);
            }
            
            @Override
            public void onError(String error) {
                // Handle error
            }
        });
    }
}
```

---

### 6. CHANNELS FRAGMENT

**fragment_channels.xml:**
```xml
<androidx.constraintlayout.widget.ConstraintLayout>
    <!-- Filter chips (optional) -->
    <HorizontalScrollView
        android:id="@+id/filterScroll"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:scrollbars="none"
        app:layout_constraintTop_toTopOf="parent">
        
        <com.google.android.material.chip.ChipGroup
            android:id="@+id/filterChips"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:padding="8dp"
            app:singleSelection="true"/>
    </HorizontalScrollView>
    
    <!-- RecyclerView -->
    <androidx.recyclerview.widget.RecyclerView
        android:id="@+id/channelsRecyclerView"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:padding="@dimen/screen_margin"
        app:layout_constraintTop_toBottomOf="@id/filterScroll"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layoutManager="androidx.recyclerview.widget.LinearLayoutManager"/>
    
    <!-- Loading indicator -->
    <ProgressBar
        android:id="@+id/loadingIndicator"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:visibility="gone"
        app:layout_constraintTop_toTopOf="parent"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent"/>
    
    <!-- Empty state -->
    <include
        android:id="@+id/emptyState"
        layout="@layout/empty_state"
        android:visibility="gone"/>
</androidx.constraintlayout.widget.ConstraintLayout>
```

**item_channel.xml:**
```xml
<androidx.cardview.widget.CardView
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:layout_marginBottom="12dp"
    app:cardCornerRadius="12dp"
    app:cardElevation="2dp"
    android:foreground="?attr/selectableItemBackground">
    
    <androidx.constraintlayout.widget.ConstraintLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:padding="16dp">
        
        <!-- Channel avatar -->
        <com.google.android.material.imageview.ShapeableImageView
            android:id="@+id/channelAvatar"
            android:layout_width="64dp"
            android:layout_height="64dp"
            app:shapeAppearanceOverlay="@style/CircleImageView"
            android:scaleType="centerCrop"
            app:layout_constraintStart_toStartOf="parent"
            app:layout_constraintTop_toTopOf="parent"/>
        
        <!-- Channel info -->
        <TextView
            android:id="@+id/channelName"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:textSize="18sp"
            android:textStyle="bold"
            android:maxLines="1"
            android:ellipsize="end"
            android:layout_marginStart="12dp"
            app:layout_constraintStart_toEndOf="@id/channelAvatar"
            app:layout_constraintEnd_toStartOf="@id/chevron"
            app:layout_constraintTop_toTopOf="@id/channelAvatar"/>
        
        <TextView
            android:id="@+id/subscriberCount"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:textSize="12sp"
            android:textColor="@color/text_tertiary"
            android:layout_marginStart="12dp"
            android:layout_marginTop="4dp"
            app:layout_constraintStart_toEndOf="@id/channelAvatar"
            app:layout_constraintTop_toBottomOf="@id/channelName"/>
        
        <!-- Category tags -->
        <com.google.android.material.chip.ChipGroup
            android:id="@+id/categoryChips"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_marginStart="12dp"
            android:layout_marginTop="8dp"
            app:layout_constraintStart_toEndOf="@id/channelAvatar"
            app:layout_constraintEnd_toStartOf="@id/chevron"
            app:layout_constraintTop_toBottomOf="@id/subscriberCount"
            app:chipSpacingHorizontal="4dp"/>
        
        <!-- Chevron -->
        <ImageView
            android:id="@+id/chevron"
            android:layout_width="24dp"
            android:layout_height="24dp"
            android:src="@drawable/ic_chevron_right"
            app:layout_constraintEnd_toEndOf="parent"
            app:layout_constraintTop_toTopOf="parent"
            app:layout_constraintBottom_toBottomOf="parent"/>
    </androidx.constraintlayout.widget.ConstraintLayout>
</androidx.cardview.widget.CardView>
```

**ChannelsFragment.java:**
```java
public class ChannelsFragment extends Fragment implements CategoryManager.CategoryChangeListener {
    private ChannelsViewModel viewModel;
    private ChannelAdapter adapter;
    private RecyclerView recyclerView;
    private int currentPage = 1;
    private boolean isLoading = false;
    private boolean hasMore = true;
    
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_channels, container, false);
        
        viewModel = new ViewModelProvider(this).get(ChannelsViewModel.class);
        
        recyclerView = view.findViewById(R.id.channelsRecyclerView);
        adapter = new ChannelAdapter(this::onChannelClick, false); // false = vertical
        recyclerView.setAdapter(adapter);
        
        // Pagination
        recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView rv, int dx, int dy) {
                LinearLayoutManager layoutManager = (LinearLayoutManager) rv.getLayoutManager();
                int lastVisible = layoutManager.findLastVisibleItemPosition();
                int total = layoutManager.getItemCount();
                
                if (!isLoading && hasMore && lastVisible >= total - 5) {
                    loadMore();
                }
            }
        });
        
        // Observe data
        viewModel.getChannels().observe(getViewLifecycleOwner(), channels -> {
            isLoading = false;
            if (channels != null && !channels.isEmpty()) {
                if (currentPage == 1) {
                    adapter.setChannels(channels);
                } else {
                    adapter.addChannels(channels);
                }
                hasMore = channels.size() >= 20; // Assume page size is 20
            } else {
                hasMore = false;
            }
        });
        
        // Load initial data
        loadData();
        
        // Register for category changes
        CategoryManager.getInstance().addListener(this);
        
        return view;
    }
    
    private void loadData() {
        currentPage = 1;
        hasMore = true;
        String categoryId = CategoryManager.getInstance().getSelectedCategoryId();
        viewModel.loadChannels(currentPage, categoryId);
    }
    
    private void loadMore() {
        isLoading = true;
        currentPage++;
        String categoryId = CategoryManager.getInstance().getSelectedCategoryId();
        viewModel.loadChannels(currentPage, categoryId);
    }
    
    @Override
    public void onCategoryChanged(Category category) {
        loadData();
    }
    
    private void onChannelClick(Channel channel) {
        Intent intent = new Intent(getActivity(), ChannelDetailActivity.class);
        intent.putExtra("channel_id", channel.getId());
        startActivity(intent);
    }
    
    @Override
    public void onDestroyView() {
        super.onDestroyView();
        CategoryManager.getInstance().removeListener(this);
    }
}
```

---

Due to the length and complexity of this prompt, I'll provide the key remaining sections in summary form:

### 7-13. REMAINING SCREENS (Summary)

**Playlists Fragment:** Similar structure to Channels, grid layout, category filtering

**Videos Fragment:** Grid layout, filter chips (duration, sort), pagination

**Channel Detail Activity:** Tabs (Videos/Live/Shorts/Playlists/Posts), subscribe button, channel info

**Playlist Detail Activity:** Header with thumbnail, video list, download playlist button

**Video Player Activity:** ExoPlayer integration, controls, chromecast button, "Up Next" queue

**Search Activity:** Search input, results by type (tabs), category filtering

**Downloads & Library Activity:** Download manager, downloaded content, library sections (saved, history)

**Settings Activity:** Grouped settings (General, Playback, Downloads, Content, About)

---

## NEWPIPEEXTRACTOR INTEGRATION

```java
public class NewPipeService {
    public static void initialize() {
        NewPipe.init(DownloaderImpl.init(null));
    }
    
    public static StreamInfo getVideoInfo(String youtubeId) throws Exception {
        String url = "https://www.youtube.com/watch?v=" + youtubeId;
        StreamingService service = ServiceList.YouTube;
        StreamExtractor extractor = service.getStreamExtractor(url);
        extractor.fetchPage();
        
        return StreamInfo.getInfo(extractor);
    }
    
    public static List<VideoStream> getVideoStreams(String youtubeId) throws Exception {
        StreamInfo info = getVideoInfo(youtubeId);
        return info.getVideoStreams();
    }
    
    public static List<AudioStream> getAudioStreams(String youtubeId) throws Exception {
        StreamInfo info = getVideoInfo(youtubeId);
        return info.getAudioStreams();
    }
}
```

---

## CHROMECAST INTEGRATION (MANDATORY)

```java
public class CastOptionsProvider implements OptionsProvider {
    @Override
    public CastOptions getCastOptions(Context context) {
        return new CastOptions.Builder()
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            .build();
    }
}

// In VideoPlayerActivity
private CastContext castContext;
private CastSession castSession;

@Override
protected void onCreate(Bundle savedInstanceState) {
    // Initialize Cast
    castContext = CastContext.getSharedInstance(this);
    castSession = castContext.getSessionManager().getCurrentCastSession();
    
    // Add Cast button to toolbar
    MediaRouteButton castButton = findViewById(R.id.castButton);
    CastButtonFactory.setUpMediaRouteButton(this, castButton);
}

private void castVideo() {
    if (castSession != null && castSession.isConnected()) {
        MediaInfo mediaInfo = buildMediaInfo();
        RemoteMediaClient remoteMediaClient = castSession.getRemoteMediaClient();
        remoteMediaClient.load(mediaInfo);
    }
}
```

---

## ROOM DATABASE CACHING

```java
@Entity(tableName = "videos")
public class VideoEntity {
    @PrimaryKey
    @NonNull
    private String id;
    private String youtubeId;
    private String title;
    private String thumbnail;
    private int duration;
    private String channelName;
    private long cachedAt;
    
    // Category IDs as JSON string
    private String categoryIds;
}

@Dao
public interface VideoDao {
    @Query("SELECT * FROM videos WHERE categoryIds LIKE '%' || :categoryId || '%' LIMIT :limit OFFSET :offset")
    LiveData<List<VideoEntity>> getVideosByCategory(String categoryId, int limit, int offset);
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertVideos(List<VideoEntity> videos);
    
    @Query("DELETE FROM videos WHERE cachedAt < :expiryTime")
    void deleteExpiredVideos(long expiryTime);
}
```

---

## FINAL IMPLEMENTATION NOTES

**Priority Order:**
1. Core navigation + category system
2. Content display (Home, Channels, Playlists, Videos)
3. Video player + NewPipeExtractor
4. Category filtering (CRITICAL - affects all screens)
5. Chromecast integration
6. Background playback
7. Downloads
8. Search
9. Settings
10. TV optimizations

**Key Points:**
- Category filtering is CRITICAL - every API call must include categoryId parameter
- Handle parent/subcategory hierarchy correctly (parent includes all subcategories)
- Test thoroughly on both mobile and TV
- Ensure focus states work on TV
- Cache aggressively to reduce API calls
- Handle offline mode gracefully
- Follow Material Design 3 guidelines
- Test with different category structures (with/without subcategories)
