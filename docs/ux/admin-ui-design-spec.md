# Admin UI Design Specification

> Based on mockup designs provided 2025-10-03

## Overview

This document defines the UI/UX requirements for the Albunyaan Tube admin panel based on the provided design mockups. All frontend implementation must match these specifications.

---

## Color Palette

**Primary Brand Color**: Teal/Green (`#2D9B8B`, `#1E7A6D`)
**Status Colors**:
- Active/Published: Green (`#10B981`)
- Pending/Draft: Yellow (`#F59E0B`)
- Inactive/Archived: Red (`#EF4444`)

**Neutrals**:
- Background: Light gray (`#F9FAFB`)
- Card background: White (`#FFFFFF`)
- Text primary: Dark gray (`#111827`)
- Text secondary: Medium gray (`#6B7280`)

---

## Layout Structure

### Sidebar Navigation
- **Width**: 260px fixed
- **Background**: White with subtle border-right
- **Logo**: Top (64px height)
- **Navigation items**: Icon + Label
- **Active state**: Teal background with rounded corners
- **User profile**: Bottom section with avatar + name + role

### Main Content Area
- **Header**: Breadcrumb + Search + Notifications + Profile
- **Content**: Padded (24px) with white background

---

## Core Screens & Components

### 1. Login Page
**File**: `frontend/src/views/LoginView.vue`

**Layout**:
- Centered card (max-width: 480px)
- Logo at top
- Title: "Albunyaan Admin"
- Email input
- Password input
- "Remember me" checkbox + "Forgot password?" link
- "Sign In" button (full width, teal)

**Requirements**:
- Use Firebase Authentication
- Show loading state on button during auth
- Display error messages below form
- Redirect to Dashboard on success

---

### 2. Dashboard
**File**: `frontend/src/views/DashboardView.vue`
**API**: `GET /api/admin/dashboard`

**Components**:
1. **Metrics Cards** (4 columns)
   - Total Content
   - Pending Approvals
   - Active Users
   - (flexible 4th metric)

2. **Recent Activity Table**
   - Columns: User, Action, Timestamp
   - Show last 10 activities
   - Link to full Activity Log

**Design Pattern**:
- Cards with subtle shadow
- Large numbers (48px font)
- Icons for each metric
- Activity table with alternating row colors

---

### 3. Content Search (YouTube-style)
**File**: `frontend/src/views/ContentSearchView.vue`
**API**: `GET /api/admin/youtube/search/*`

**Layout**:
- Search bar at top (prominent, full-width)
- Tabs: Channels | Playlists | Videos
- Advanced Filters (collapsible)
- Grid/List toggle
- Results grid (3 columns)

**Card Design**:
- Thumbnail image (16:9 ratio)
- Title
- Metadata (subscriber count, video count, etc.)
- "Add to Master List" button (teal)

**Filters**:
- Category dropdown
- Video length (SHORT/MEDIUM/LONG)
- Date range (LAST_24_HOURS/LAST_7_DAYS/LAST_30_DAYS/ANYTIME)
- Sort (RECENT/POPULAR)

---

### 4. Categories Management
**File**: `frontend/src/views/CategoriesView.vue`
**API**: `GET /api/admin/categories`

**Design Requirements**:
- Hierarchical tree view
- Drag handles (⋮⋮) for reordering
- Expand/collapse arrows (›)
- Icons for each category
- Item count + subcategory count
- Edit (pencil) + Delete (trash) actions
- "+ Add Category" button (top-right, teal)

**Tree Structure**:
```
⋮⋮ › 🛡️ Aqeedah                    10 Items  2 Sub  ✏️ 🗑️
    ⋮⋮ 📖 Tawheed                  5 Items        ✏️ 🗑️
    ⋮⋮ 🚫 Shirk                    5 Items        ✏️ 🗑️
⋮⋮ › ⚖️ Fiqh                       15 Items       ✏️ 🗑️
⋮⋮ › 📜 Seerah                     22 Items       ✏️ 🗑️
```

**Interactions**:
- Click expand/collapse to show/hide children
- Drag to reorder (update displayOrder)
- Click edit to open edit modal
- Click delete to confirm deletion (prevent if has subcategories)

---

### 5. Users Management
**File**: `frontend/src/views/UsersManagementView.vue`
**API**: `GET /api/admin/users`

**Layout**:
- Search box (top-left)
- "+ Add User" button (top-right, teal)
- Table with columns:
  - Avatar + Name + Email
  - Role (Admin/Editor badge)
  - Status (Active/Inactive dot)
  - Last Login
  - Created Date
  - Actions (⋮ menu)

**Actions Menu**:
- Edit Role
- Change Status (Activate/Deactivate)
- Reset Password
- Delete User

**Design**:
- Avatar circles (40px)
- Role badges (colored pills)
- Status dots (green/orange)
- Actions menu opens on click

---

### 6. Content Management (Master List)
**File**: `frontend/src/views/ContentLibraryView.vue`
**API**: `GET /api/admin/channels`, etc.

**Sidebar Filters**:
- Status (checkbox list)
- Type (Channel/Playlist/Video)
- Categories (hierarchical checkboxes)
- Date Range (date picker)
- Created By (dropdown)
- Has Exclusions (toggle)
- Apply Filters / Clear All buttons

**Main Area**:
- Search bar
- Active filters chips (removable)
- Bulk selection bar when items selected:
  - "2 items selected"
  - Change Status | Add to Category | Remove from Category | Delete
  - Deselect All
- Table/Grid toggle
- Content cards/rows

**Table Columns**:
- Checkbox | Thumbnail | Title | Type | Categories | Status | Exclusions | Created By | Created Date | Actions

---

### 7. Pending Approvals
**File**: `frontend/src/views/PendingApprovalsView.vue`
**API**: `GET /api/admin/channels?status=pending`

**Metrics Bar**:
- Total Pending
- Channels (count)
- Playlists (count)
- Videos (count)

**Tabs**: All | Channels | Playlists | Videos

**Filters**:
- Date Submitted (date picker)
- Submitter (dropdown)
- Category (dropdown)
- Sort By (Newest First/Oldest First)

**Content Cards**:
- Thumbnail
- Title + metadata
- Categories (pills)
- Exclusions (if any)
- Approve / Reject buttons

---

### 8. Activity Log
**File**: `frontend/src/views/ActivityLogView.vue`
**API**: `GET /api/admin/audit`

**Filters**:
- All Users (dropdown)
- All Action Types (dropdown)
- Date Range picker
- "Export to CSV" button

**Timeline View**:
- Grouped by date
- Each entry:
  - Icon (color-coded by action type)
  - User + Action description
  - Timestamp
  - "Show full metadata" expand button

**Action Icons**:
- ➕ Green: Created/Added
- ✏️ Blue: Updated/Edited
- 🗑️ Red: Deleted
- 🔑 Orange: Auth action (login, etc.)

---

### 9. Settings
**File**: `frontend/src/views/SettingsView.vue`

**Tabs**:
- Profile
- Notifications
- YouTube API
- System

**Profile Tab**:
- Avatar upload (circle, 120px)
- Display Name input
- Email (read-only)
- Change Password button
- "Save Changes" button (bottom-right, teal)

**Notifications Tab**:
- Toggle switches for notification preferences

**YouTube API Tab**:
- API Key input
- Test Connection button
- Usage statistics

**System Tab**:
- Language selector
- Timezone
- Theme (future)

---

### 10. Channel Details Modal
**Component**: `frontend/src/components/ChannelDetailsModal.vue`

**Tabs**:
- Overview
- Categories
- Exclusions
- Metadata
- History

**Overview Tab**:
- Large thumbnail/logo
- Status badge (Active/Pending/Rejected)
- Metadata box:
  - Videos count
  - Subscribers count
  - Imported At date
  - Last Sync date
- Actions: Edit | Delete buttons
- Description text

**Categories Tab**:
- Assigned categories (chips)
- "+ Assign Categories" button
- Shows hierarchical category selector modal

**Exclusions Tab**:
- Videos excluded list
- Playlists excluded list
- Shorts excluded list
- "+ Add Exclusion" functionality

**Metadata Tab**:
- YouTube ID
- Custom tags
- Notes
- Import source

**History Tab**:
- Audit log specific to this channel
- Who created, who approved, changes made

---

### 11. Notifications Panel
**Component**: `frontend/src/components/NotificationsPanel.vue`

**Design**:
- Dropdown panel (max-width: 400px)
- Header: "Notifications" + "Mark all as read" link
- Notification items:
  - Icon (bell)
  - Message text
  - Timestamp
  - Unread indicator (blue dot)
- "View all" link at bottom

**Notification Types**:
- Account created
- Welcome message
- New content available
- Approval required
- Content approved/rejected

---

### 12. Category Assignment Modal
**Component**: `frontend/src/components/CategoryAssignmentModal.vue`

**Design**:
- Content preview (thumbnail + title + description)
- Hierarchical category checkboxes (tree view)
- "Approve immediately" checkbox
- Cancel | Save buttons

**Validation**:
- "Please select at least one category to save" error message if empty

---

## Design Patterns & Components

### Buttons
- **Primary**: Teal background, white text, rounded (8px)
- **Secondary**: White background, teal border, teal text
- **Danger**: Red background, white text
- **Text**: No background, teal text

### Form Inputs
- Border: 1px solid gray (#D1D5DB)
- Focus: 2px teal ring
- Border radius: 6px
- Padding: 12px 16px
- Font size: 14px

### Cards
- Background: White
- Border radius: 12px
- Shadow: `0 1px 3px rgba(0,0,0,0.1)`
- Padding: 24px

### Tables
- Striped rows (alternate background)
- Hover: Light teal background
- Header: Medium gray background, bold text
- Cell padding: 12px 16px

### Status Badges
- Pill shape (full rounded)
- Small text (12px)
- Color-coded backgrounds
- Examples: Published (green), Draft (yellow), Archived (red)

### Icons
- Size: 20px (in navigation), 16px (inline)
- Style: Outlined/stroke (not filled)
- Color: Inherit from parent or brand teal

---

## Responsive Behavior

### Breakpoints
- Desktop: 1280px+
- Tablet: 768px - 1279px
- Mobile: < 768px

### Mobile Adaptations
- Sidebar collapses to hamburger menu
- Grid views become single column
- Table scrolls horizontally
- Filters move to bottom sheet

---

## Accessibility Requirements

- **WCAG 2.1 AA Compliance**
- Keyboard navigation for all interactions
- Focus indicators (2px teal ring)
- ARIA labels for icon-only buttons
- Semantic HTML structure
- Color contrast ratios: 4.5:1 minimum
- Screen reader announcements for dynamic content

---

## Animation & Transitions

- **Duration**: 200ms (standard)
- **Easing**: `ease-in-out`
- **Hover states**: Scale 1.02 on cards
- **Loading states**: Spinner (teal) or skeleton loaders
- **Page transitions**: Fade in (150ms)

---

## Missing/To-Build Features

Based on design mockups, the following features need implementation:

### High Priority
1. ✅ Dashboard metrics (IMPLEMENTED)
2. ✅ User management CRUD (IMPLEMENTED)
3. ✅ Activity log (IMPLEMENTED)
4. ❌ Categories hierarchical tree with drag-drop
5. ❌ Content Search (YouTube API integration UI)
6. ❌ Channel Details modal with tabs
7. ❌ Pending Approvals workflow UI
8. ❌ Content Management/Library view with filters
9. ❌ Category Assignment modal
10. ❌ Notifications panel

### Medium Priority
11. ❌ Settings tabs (Profile/Notifications/YouTube API/System)
12. ❌ Bulk operations UI
13. ❌ Export functionality (CSV)
14. ❌ Advanced filters sidebar

### Low Priority
15. ❌ Grid/List view toggle
16. ❌ Drag-drop reordering
17. ❌ RTL support for Arabic
18. ❌ Dark mode (future)

---

## Implementation Priority

**Sprint 1**: Core Navigation + Dashboard + Users
**Sprint 2**: Categories + Content Search
**Sprint 3**: Pending Approvals + Activity Log (enhanced)
**Sprint 4**: Content Management + Bulk Operations
**Sprint 5**: Settings + Notifications + Polish

---

## References

- Design Mockups: Provided 2025-10-03
- Design Tokens: `docs/ux/design-tokens.json`
- Component Library: TBD (consider Headless UI or shadcn/vue)
- Icons: Heroicons or similar outlined icon set

---

**Last Updated**: 2025-10-03
**Status**: Design spec complete, implementation in progress
