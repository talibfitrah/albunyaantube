# Phase 3 — Admin UI Implementation (Design-Aligned)

> Last Updated: 2025-10-03
> Based on: Design mockups + `docs/ux/admin-ui-design-spec.md`

## Execution Metadata
- **Status**: In Progress (Backend complete, Frontend partial)
- **Dependencies**: Phase 1 (Backend) ✅ Complete
- **Owners**: Frontend Team
- **Design Spec**: `docs/ux/admin-ui-design-spec.md`
- **Sprint Plan**: 5 sprints total

---

## Overview

Phase 3 implements the complete admin UI based on the provided design mockups. The backend API is complete (33 endpoints), and this phase focuses on building the frontend to match the exact design specifications.

### Current Status:
- ✅ Backend API (33 endpoints across 6 controllers)
- ✅ Firebase Authentication integration
- ✅ Basic auth store and routing
- ❌ UI components matching design mockups
- ❌ Complete navigation and layout structure
- ❌ All admin screens implementation

---

## Sprint 1: Core Layout & Navigation (Week 1-2)

### UI-001: Main Layout & Sidebar Navigation
**Status**: To Do
**Estimate**: 3 days
**Priority**: P0 (Blocker)

**Requirements**:
- Implement sidebar navigation (260px fixed width)
- Logo placement and branding
- Navigation menu items with icons:
  - Dashboard
  - Content Search
  - Content Management
  - Pending Approvals
  - Categories
  - Users
  - Activity Log
  - Settings
- Active state highlighting (teal background)
- User profile section at bottom (avatar + name + role + logout)
- Responsive: Collapse to hamburger menu on mobile

**Files**:
- `frontend/src/layouts/AdminLayout.vue`
- `frontend/src/components/navigation/SidebarNav.vue`
- `frontend/src/components/navigation/UserProfile.vue`

**Design Reference**: All mockups show consistent sidebar

**Acceptance Criteria**:
- [ ] Sidebar matches design exactly (colors, spacing, typography)
- [ ] Active route highlights correctly
- [ ] Mobile hamburger menu works
- [ ] User can logout
- [ ] Navigation persists scroll position

---

### UI-002: Login Page
**Status**: Partial (needs design alignment)
**Estimate**: 1 day
**Priority**: P0

**Requirements**:
- Centered card layout (max-width: 480px)
- Logo at top ("Albunyaan Admin")
- Email input field
- Password input field
- "Remember me" checkbox
- "Forgot password?" link
- "Sign In" button (full-width, teal)
- Loading state during authentication
- Error message display below form
- Redirect to Dashboard on success

**Files**:
- `frontend/src/views/auth/LoginView.vue`

**Design Reference**: Login mockup

**Acceptance Criteria**:
- [ ] Matches design pixel-perfect
- [ ] Firebase auth integration works
- [ ] Form validation shows errors
- [ ] Loading spinner on submit
- [ ] Remembers email if checkbox checked
- [ ] Forgot password link functional

---

### UI-003: Dashboard View
**Status**: Backend ready, UI To Do
**Estimate**: 2 days
**Priority**: P0

**Requirements**:
- **Metrics Cards** (4-column grid):
  - Total Content (categories/channels count)
  - Pending Approvals
  - Active Users
  - (Flexible 4th metric)
- **Recent Activity Table**:
  - Columns: User, Action, Timestamp
  - Last 10 activities from audit log
  - Link to "View All" → Activity Log page
- Breadcrumb: "Home > Dashboard"
- Search bar in header
- Notifications icon
- Profile dropdown

**Files**:
- `frontend/src/views/DashboardView.vue`
- `frontend/src/components/dashboard/MetricCard.vue`
- `frontend/src/components/dashboard/RecentActivityTable.vue`

**API Endpoint**: `GET /api/admin/dashboard`

**Design Reference**: Dashboard mockup

**Acceptance Criteria**:
- [ ] Metrics cards display correct data from API
- [ ] Cards have hover/shadow effects as per design
- [ ] Recent activity table shows audit logs
- [ ] Activity links to detail pages
- [ ] Responsive layout (stacks on mobile)

---

## Sprint 2: Content Discovery & Categories (Week 3-4)

### UI-004: Content Search (YouTube-style)
**Status**: To Do
**Estimate**: 4 days
**Priority**: P1

**Requirements**:
- Prominent search bar (full-width) with placeholder "Search YouTube for Islamic content..."
- Tabs: Channels | Playlists | Videos
- Advanced Filters (collapsible accordion):
  - Category dropdown
  - Video Length (SHORT/MEDIUM/LONG)
  - Date (LAST_24_HOURS/LAST_7_DAYS/LAST_30_DAYS/ANYTIME)
  - Sort (RECENT/POPULAR)
- Results grid (3 columns on desktop)
- Grid/List view toggle
- Result cards:
  - Thumbnail (16:9 ratio)
  - Title
  - Metadata (subscribers, video count, etc.)
  - "Add to Master List" button (teal)
- Pagination ("Showing 1-6 of 2,412 results")
- Loading skeleton states

**Files**:
- `frontend/src/views/ContentSearchView.vue`
- `frontend/src/components/search/SearchBar.vue`
- `frontend/src/components/search/SearchFilters.vue`
- `frontend/src/components/search/SearchResults.vue`
- `frontend/src/components/search/ContentCard.vue`

**API Endpoints**:
- `GET /api/admin/youtube/search/channels?query=...`
- `GET /api/admin/youtube/search/playlists?query=...`
- `GET /api/admin/youtube/search/videos?query=...`

**Design Reference**: Content Search mockup

**Acceptance Criteria**:
- [ ] Search works with YouTube API
- [ ] Tabs switch content types
- [ ] Filters apply correctly
- [ ] Grid/List toggle works
- [ ] "Add to Master List" submits channel/playlist/video for approval
- [ ] Pagination works
- [ ] Loading states shown

---

### UI-005: Categories Management (Hierarchical Tree)
**Status**: To Do
**Estimate**: 5 days
**Priority**: P1

**Requirements**:
- Hierarchical tree view of categories
- Drag handles (⋮⋮) for each item
- Expand/collapse arrows (›) for parent categories
- Category icons
- Display: Name | Item Count | Subcategory Count | Actions
- Actions: Edit (pencil) | Delete (trash)
- "+ Add Category" button (top-right, teal)
- Drag-and-drop reordering (updates `displayOrder`)
- Expand/collapse state persistence
- Nested indentation visual hierarchy

**Example Structure**:
```
⋮⋮ › 🛡️ Aqeedah      10 Items  2 Sub  ✏️ 🗑️
    ⋮⋮ 📖 Tawheed    5 Items        ✏️ 🗑️
    ⋮⋮ 🚫 Shirk      5 Items        ✏️ 🗑️
⋮⋮ › ⚖️ Fiqh         15 Items       ✏️ 🗑️
```

**Files**:
- `frontend/src/views/CategoriesView.vue`
- `frontend/src/components/categories/CategoryTree.vue`
- `frontend/src/components/categories/CategoryTreeItem.vue`
- `frontend/src/components/categories/CategoryFormModal.vue`

**API Endpoints**:
- `GET /api/admin/categories`
- `GET /api/admin/categories/{id}/subcategories`
- `POST /api/admin/categories`
- `PUT /api/admin/categories/{id}`
- `DELETE /api/admin/categories/{id}`

**Design Reference**: Categories mockup

**Acceptance Criteria**:
- [ ] Tree structure matches API data
- [ ] Expand/collapse works smoothly
- [ ] Drag-and-drop reorders and saves
- [ ] Edit modal pre-fills data
- [ ] Delete confirms and prevents if has children
- [ ] Add category modal validates parent selection
- [ ] Icons display correctly

---

## Sprint 3: User Management & Approvals (Week 5-6)

### UI-006: Users Management
**Status**: Backend ready, UI To Do
**Estimate**: 3 days
**Priority**: P1

**Requirements**:
- Search box: "Search users..."
- "+ Add User" button (top-right, teal)
- Table columns:
  - Checkbox (for bulk actions)
  - Avatar (40px circle) + Name + Email
  - Role (Admin/Editor badge - colored pill)
  - Status (Active/Inactive - green/orange dot)
  - Last Login timestamp
  - Created Date
  - Actions menu (⋮)
- Actions menu:
  - Edit Role
  - Change Status (Activate/Deactivate)
  - Reset Password
  - Delete User
- Bulk actions (when items selected)
- Add/Edit User modal with form validation

**Files**:
- `frontend/src/views/UsersManagementView.vue`
- `frontend/src/components/users/UsersTable.vue`
- `frontend/src/components/users/UserFormModal.vue`
- `frontend/src/components/users/UserActionsMenu.vue`

**API Endpoints**:
- `GET /api/admin/users`
- `POST /api/admin/users`
- `PUT /api/admin/users/{uid}/role`
- `PUT /api/admin/users/{uid}/status`
- `DELETE /api/admin/users/{uid}`
- `POST /api/admin/users/{uid}/reset-password`

**Design Reference**: Users mockup

**Acceptance Criteria**:
- [ ] Table displays all users from API
- [ ] Search filters users client-side
- [ ] Add User creates Firebase user + Firestore record
- [ ] Edit Role updates both Firebase claims and Firestore
- [ ] Status toggle works (active/inactive)
- [ ] Reset password sends Firebase email
- [ ] Delete confirms and removes from both systems
- [ ] Avatars show placeholders if not uploaded

---

### UI-007: Pending Approvals Workflow
**Status**: To Do
**Estimate**: 4 days
**Priority**: P1

**Requirements**:
- **Metrics Bar** (4 cards):
  - Total Pending (count)
  - Channels (count)
  - Playlists (count)
  - Videos (count)
- Tabs: All | Channels | Playlists | Videos
- **Filters**:
  - Date Submitted (date picker)
  - Submitter (dropdown)
  - Category (dropdown)
  - Sort By (Newest First/Oldest First)
- **Content Cards Grid**:
  - Thumbnail
  - Title + metadata
  - Categories (colored pills)
  - Exclusions indicator ("Yes" in red if has exclusions)
  - Approve (green) / Reject (red) buttons
- Bulk approve/reject (checkbox selection)

**Files**:
- `frontend/src/views/PendingApprovalsView.vue`
- `frontend/src/components/approvals/ApprovalCard.vue`
- `frontend/src/components/approvals/ApprovalFilters.vue`

**API Endpoints**:
- `GET /api/admin/channels?status=pending`
- `PUT /api/admin/channels/{id}/approve`
- `PUT /api/admin/channels/{id}/reject`

**Design Reference**: Pending Approvals mockup

**Acceptance Criteria**:
- [ ] Metrics show correct counts
- [ ] Tabs filter by content type
- [ ] Filters work and persist
- [ ] Approve button updates status to "approved"
- [ ] Reject button prompts for reason
- [ ] Bulk operations work
- [ ] Cards show all metadata correctly

---

## Sprint 4: Content Library & Details (Week 7-8)

### UI-008: Content Management/Library
**Status**: To Do
**Estimate**: 5 days
**Priority**: P2

**Requirements**:
- **Sidebar Filters** (left column):
  - Status (Published/Draft/Archived checkboxes)
  - Type (Channel/Playlist/Video)
  - Categories (hierarchical tree checkboxes)
  - Date Range (date picker)
  - Created By (dropdown)
  - Has Exclusions (toggle)
  - Apply Filters / Clear All buttons
- **Main Area**:
  - Search content bar
  - Active filters (removable chips)
  - **Bulk Selection Bar** (when items selected):
    - "2 items selected"
    - Change Status | Add to Category | Remove from Category | Delete
    - Deselect All
  - Grid/List toggle
  - **Table columns**:
    - Checkbox | Thumbnail | Title | Type | Categories | Status | Exclusions | Created By | Created Date | Actions

**Files**:
- `frontend/src/views/ContentLibraryView.vue`
- `frontend/src/components/content/ContentFilters.vue`
- `frontend/src/components/content/ContentTable.vue`
- `frontend/src/components/content/BulkActionsBar.vue`

**API Endpoints**:
- `GET /api/admin/channels`
- (Future: playlists/videos endpoints)

**Design Reference**: Content Management mockup

**Acceptance Criteria**:
- [ ] Filters work and are applied via API
- [ ] Active filter chips show and are removable
- [ ] Bulk selection enables bulk actions bar
- [ ] Bulk operations execute correctly
- [ ] Table/Grid toggle preserves state
- [ ] Pagination works
- [ ] Actions menu per item works

---

### UI-009: Channel Details Modal
**Status**: To Do
**Estimate**: 4 days
**Priority**: P2

**Requirements**:
- Modal overlay (centered, max-width: 1200px)
- Header: "Channel" badge | Channel Name | Close (X)
- Channel ID display
- **Tabs**:
  1. **Overview**:
     - Large thumbnail/logo
     - Status badge (Active/Pending/Rejected)
     - Metadata box: Videos, Subscribers, Imported At, Last Sync
     - Actions: Edit | Delete
     - Description text
  2. **Categories**:
     - Assigned categories (colored chips)
     - "+ Assign Categories" button → opens Category Assignment modal
  3. **Exclusions**:
     - Videos excluded (list with remove option)
     - Playlists excluded
     - Shorts excluded
     - "+ Add Exclusion" functionality
  4. **Metadata**:
     - YouTube ID
     - Custom tags
     - Notes (textarea)
     - Import source
  5. **History**:
     - Audit log filtered for this channel
     - Timeline of changes

**Files**:
- `frontend/src/components/ChannelDetailsModal.vue`
- `frontend/src/components/channel-details/OverviewTab.vue`
- `frontend/src/components/channel-details/CategoriesTab.vue`
- `frontend/src/components/channel-details/ExclusionsTab.vue`
- `frontend/src/components/channel-details/MetadataTab.vue`
- `frontend/src/components/channel-details/HistoryTab.vue`

**API Endpoints**:
- `GET /api/admin/channels/{id}`
- `PUT /api/admin/channels/{id}/exclusions`
- `GET /api/admin/audit/entity-type/channel?entityId={id}`

**Design Reference**: Channel Details Modal mockup

**Acceptance Criteria**:
- [ ] Modal opens/closes smoothly
- [ ] Tabs switch content correctly
- [ ] Overview shows all metadata
- [ ] Categories tab allows assignment
- [ ] Exclusions can be added/removed
- [ ] Metadata is editable and saves
- [ ] History shows audit trail

---

## Sprint 5: Activity Log & Settings (Week 9-10)

### UI-010: Activity Log (Enhanced)
**Status**: Backend ready, UI needs enhancement
**Estimate**: 3 days
**Priority**: P2

**Requirements**:
- **Filters**:
  - All Users (dropdown)
  - All Action Types (dropdown - category_created, user_created, channel_approved, etc.)
  - Date Range picker
  - "Export to CSV" button
- **Timeline View** (grouped by date):
  - Date header
  - Action entries:
    - Icon (color-coded: ➕ green for create, ✏️ blue for edit, 🗑️ red for delete, 🔑 orange for auth)
    - Actor name
    - Action description
    - Timestamp
    - "Show full metadata" expandable section
- Infinite scroll or pagination

**Files**:
- `frontend/src/views/ActivityLogView.vue`
- `frontend/src/components/activity/ActivityFilters.vue`
- `frontend/src/components/activity/ActivityTimeline.vue`
- `frontend/src/components/activity/ActivityEntry.vue`

**API Endpoints**:
- `GET /api/admin/audit?limit=100`
- `GET /api/admin/audit/actor/{actorUid}`
- `GET /api/admin/audit/entity-type/{type}`
- `GET /api/admin/audit/action/{action}`

**Design Reference**: Activity Log mockup

**Acceptance Criteria**:
- [ ] Filters work and fetch from API
- [ ] Timeline groups by date
- [ ] Icons match action types
- [ ] Expandable metadata works
- [ ] Export to CSV downloads file
- [ ] Pagination/infinite scroll works

---

### UI-011: Settings Pages
**Status**: To Do
**Estimate**: 3 days
**Priority**: P3

**Requirements**:
- **Tabs** (sidebar or top):
  - Profile
  - Notifications
  - YouTube API
  - System
- **Profile Tab**:
  - Avatar upload (circle, 120px) with preview
  - Display Name input
  - Email (read-only, shown from Firebase)
  - Change Password button (triggers Firebase password reset email)
  - "Save Changes" button (bottom-right, teal)
- **Notifications Tab**:
  - Toggle switches for:
    - Email notifications
    - Browser notifications
    - New content alerts
    - Approval reminders
- **YouTube API Tab**:
  - API Key input (password field)
  - "Test Connection" button
  - Usage statistics (if available)
- **System Tab**:
  - Language selector (en/ar/nl)
  - Timezone selector
  - Theme toggle (Light/Dark - future)

**Files**:
- `frontend/src/views/SettingsView.vue`
- `frontend/src/components/settings/ProfileTab.vue`
- `frontend/src/components/settings/NotificationsTab.vue`
- `frontend/src/components/settings/YouTubeAPITab.vue`
- `frontend/src/components/settings/SystemTab.vue`

**Design Reference**: Settings mockup

**Acceptance Criteria**:
- [ ] Tabs switch correctly
- [ ] Profile updates save to Firebase/Firestore
- [ ] Avatar upload works (Firebase Storage)
- [ ] Change password sends reset email
- [ ] Notification preferences save
- [ ] YouTube API key validates
- [ ] Language switch works (i18n)

---

### UI-012: Notifications Panel
**Status**: To Do
**Estimate**: 2 days
**Priority**: P3

**Requirements**:
- Dropdown panel (triggered by bell icon in header)
- Max-width: 400px
- Header: "Notifications" + "Mark all as read" link
- **Notification Items**:
  - Icon (bell)
  - Message text
  - Timestamp ("1 hour ago", "2 hours ago", etc.)
  - Unread indicator (blue dot)
- "View all" link at bottom
- Notification types:
  - Account created successfully
  - Welcome to Albunyaan!
  - New content is available for you to explore
  - Approval required
  - Content approved/rejected

**Files**:
- `frontend/src/components/NotificationsPanel.vue`
- `frontend/src/components/notifications/NotificationItem.vue`

**API Endpoint**: (Future - notifications system not yet implemented)

**Design Reference**: Notifications mockup

**Acceptance Criteria**:
- [ ] Panel opens/closes on icon click
- [ ] Notifications display correctly
- [ ] Unread indicator shows
- [ ] Mark all as read works
- [ ] Timestamps show relative time
- [ ] "View all" navigates to full list

---

### UI-013: Category Assignment Modal
**Status**: To Do
**Estimate**: 2 days
**Priority**: P2

**Requirements**:
- Modal overlay
- **Content Preview**:
  - Thumbnail
  - Title
  - Type badge (Article/Video/Channel)
  - Description
- **Category Selection**:
  - Hierarchical checkboxes (tree structure)
  - Example:
    ```
    ☐ Quran
      ☐ Tafsir
      ☐ Hadith
    ☐ Islamic History
    ☐ Seerah
    ☐ Fiqh
    ☐ Aqeedah
    ```
- "Approve immediately" checkbox (for pending items)
- Validation: "Please select at least one category to save" error
- Cancel | Save buttons

**Files**:
- `frontend/src/components/CategoryAssignmentModal.vue`
- `frontend/src/components/category-assignment/CategoryTree.vue`

**API Endpoints**:
- `GET /api/admin/categories` (to build tree)
- `PUT /api/admin/channels/{id}` (to save assigned categories)

**Design Reference**: Category Assignment mockup

**Acceptance Criteria**:
- [ ] Modal opens with content preview
- [ ] Category tree loads from API
- [ ] Checkboxes work hierarchically
- [ ] Validation prevents empty save
- [ ] "Approve immediately" updates status
- [ ] Save updates channel categories

---

## Shared Components & Utilities

### UI-SHARED-01: Design System Components
**Estimate**: 5 days (ongoing)

**Components to Build**:
- Button (primary, secondary, danger, text variants)
- Input (text, email, password, textarea)
- Select/Dropdown
- Checkbox, Radio, Toggle/Switch
- Modal/Dialog
- Badge/Pill
- Card
- Table (with sorting, filtering)
- Tabs
- Breadcrumb
- Avatar
- Icon wrapper
- Loading Spinner/Skeleton
- Toast/Alert notifications
- Pagination
- Date Picker
- Search Input

**Files**:
- `frontend/src/components/ui/*` (component library)
- `frontend/src/composables/useModal.ts`
- `frontend/src/composables/useToast.ts`

**Design Tokens**:
- Colors (brand teal, status colors, neutrals)
- Typography scale
- Spacing scale
- Border radius
- Shadows
- Transitions

---

## Testing Strategy

### Unit Tests
- All composables (stores, utilities)
- Individual components (Button, Input, etc.)
- Form validation logic

### Integration Tests
- API service calls
- Component interactions
- Form submissions
- Routing

### E2E Tests (Playwright)
- Login flow
- Create category
- Approve channel
- User management CRUD
- Content search and add

---

## Performance Requirements

- **Initial Load**: < 2s
- **Route Changes**: < 500ms
- **API Responses**: Loading states for > 300ms
- **Images**: Lazy load below fold
- **Code Splitting**: Route-based chunks
- **Bundle Size**: < 500KB (gzipped)

---

## Accessibility (WCAG 2.1 AA)

- Keyboard navigation for all interactions
- Focus indicators (2px teal ring)
- ARIA labels for icon-only buttons
- Semantic HTML (nav, main, article, etc.)
- Color contrast: 4.5:1 minimum
- Screen reader announcements for dynamic content
- Skip to content link
- Form labels and error associations

---

## i18n Support

**Languages**: English (en), Arabic (ar), Dutch (nl)

**Implementation**:
- Use Vue I18n
- Message bundles: `frontend/src/locales/{locale}.json`
- RTL support for Arabic (CSS logical properties)
- Date/number formatting per locale
- Language switcher in Settings

---

## Documentation Deliverables

- Component Storybook (visual documentation)
- API integration guide
- Deployment guide
- User manual (for admins)
- Developer setup guide

---

## Definition of Done

For each ticket:
- [ ] Design matches mockup pixel-perfect
- [ ] Responsive on mobile/tablet/desktop
- [ ] Accessibility tested (keyboard, screen reader)
- [ ] Unit tests written and passing
- [ ] Integration tests for API calls
- [ ] Code reviewed and approved
- [ ] Deployed to staging
- [ ] QA tested and signed off
- [ ] Documentation updated

---

**Last Updated**: 2025-10-03
**Next Review**: End of Sprint 1
