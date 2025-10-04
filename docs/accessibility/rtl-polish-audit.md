# RTL Layout Polish Audit

> **Date**: 2025-10-04
> **Phase**: Phase 4 - POL-002
> **Target**: Complete RTL support for Arabic

## RTL Requirements Checklist

### 1. Layout Direction ✅ COMPLETE
- [x] `dir="rtl"` attribute set on html/body when Arabic is selected
- [x] Grid layouts flip correctly
- [x] Flexbox layouts reverse appropriately
- [x] Absolute positioning adjusted for RTL

### 2. Text Alignment 📋 TO REVIEW
- [ ] Text aligns right in RTL mode
- [ ] Headings align correctly
- [ ] Form labels align properly
- [ ] Button text aligns correctly

### 3. Icons & Visual Elements ⏳ IN PROGRESS
- [ ] Directional icons flip (arrows, chevrons)
- [ ] Non-directional icons remain unchanged
- [ ] Badges position correctly
- [ ] Status indicators in proper location

### 4. Spacing & Margins 📋 TO REVIEW
- [ ] Padding flips (padding-left ↔ padding-right)
- [ ] Margins flip (margin-left ↔ margin-right)
- [ ] Border-radius corners flip correctly
- [ ] Box shadows flip direction

### 5. Navigation & Menus 📋 TO REVIEW
- [ ] Sidebar navigation works in RTL
- [ ] Dropdown menus align correctly
- [ ] Breadcrumbs display right-to-left
- [ ] Tabs order correctly

### 6. Forms & Inputs 📋 TO REVIEW
- [ ] Input fields align right
- [ ] Labels position correctly
- [ ] Checkboxes/radios on correct side
- [ ] Select dropdowns arrow on left
- [ ] Error messages align properly

### 7. Tables & Lists 📋 TO REVIEW
- [ ] Table columns order correctly
- [ ] List items indent from right
- [ ] Tree structures expand from right

### 8. Modals & Overlays 📋 TO REVIEW
- [ ] Modal positioning correct
- [ ] Close buttons on left side
- [ ] Action buttons order reversed
- [ ] Tooltips position correctly

## Components to Audit

### Core Layout
- [x] **AdminLayout.vue** - Desktop sidebar flips correctly
- [ ] **AdminLayout.vue** - Mobile sidebar needs review
- [ ] **AdminLayout.vue** - Topbar spacing

### Views
- [ ] **DashboardView.vue** - Metric cards, charts
- [ ] **ContentSearchView.vue** - Search bar, filters, tabs
- [ ] **CategoriesView.vue** - Tree structure, action buttons
- [ ] **PendingApprovalsView.vue** - Card layout, actions
- [ ] **ContentLibraryView.vue** - Filters sidebar, table
- [ ] **UsersManagementView.vue** - Table, dialogs
- [ ] **AuditLogView.vue** - Table, filters
- [ ] **ActivityLogView.vue** - Timeline view
- [ ] **Settings Pages** - All 4 settings views

### Components
- [ ] **NotificationsPanel.vue** - Dropdown position, items
- [ ] **CategoryAssignmentModal.vue** - Tree picker
- [ ] **ChannelDetailsModal.vue** - Tabs, content
- [ ] **CategoryTreeItem.vue** - Expand icons

## Known Issues

### Issue 1: Select Dropdown Arrow Position ✅ FIXED
**Status**: Fixed in previous sprint
**Component**: All select elements
**Fix Applied**: Global CSS rules for RTL arrow positioning

### Issue 2: Sidebar Width in RTL ✅ FIXED
**Status**: Fixed in previous sprint
**Component**: AdminLayout.vue
**Fix Applied**: Changed from grid column flip to `direction: rtl`

### Issue 3: Notification Badge Position
**Status**: TO FIX
**Component**: NotificationsPanel.vue
**Details**: Badge may not flip correctly in RTL
**Priority**: Medium

### Issue 4: Tree Expand Icons
**Status**: TO FIX
**Component**: CategoryTreeItem.vue, CategoryAssignmentModal.vue
**Details**: Chevron/arrow icons should flip direction
**Priority**: High

### Issue 5: Modal Close Button
**Status**: TO FIX
**Component**: All modal components
**Details**: Close (×) button should be on left in RTL
**Priority**: High

### Issue 6: Form Button Order
**Status**: TO FIX
**Component**: Settings pages, modal footers
**Details**: Primary button should be on left in RTL (currently on right)
**Priority**: Medium

## RTL Best Practices

### CSS Properties That Auto-Flip
These flip automatically with `dir="rtl"`:
- `text-align: left` → `text-align: right`
- `float: left` → `float: right`
- Flexbox `flex-direction: row` reverses
- Grid columns reverse

### Logical Properties (Recommended)
Use these for automatic RTL support:
- `margin-inline-start` instead of `margin-left`
- `margin-inline-end` instead of `margin-right`
- `padding-inline-start` instead of `padding-left`
- `border-inline-start` instead of `border-left`

### Manual RTL Overrides
Use `[dir="rtl"]` selector for:
- Absolute positioning (`left` ↔ `right`)
- Transform translate values
- Border radius (specific corners)
- Box shadows
- Background positions
- Directional icons/images

### Icons Guidelines
- **Flip**: Arrows, chevrons, back/forward, undo/redo
- **Don't Flip**: Play/pause, search, settings, close (×), checkmarks

## Testing Plan

### Phase 1: Visual Review (Current)
- [ ] Switch to Arabic in every view
- [ ] Screenshot each view in RTL
- [ ] Compare with LTR screenshots
- [ ] Document all visual issues

### Phase 2: Interactive Testing
- [ ] Navigate using keyboard in RTL
- [ ] Test all forms and inputs
- [ ] Test all modals and dropdowns
- [ ] Verify all click/hover states

### Phase 3: Edge Cases
- [ ] Long text overflow behavior
- [ ] Mixed LTR/RTL content (URLs, code)
- [ ] Bidirectional text (Bidi)
- [ ] Numeric values and dates

## Success Criteria

- [ ] All text aligns correctly in RTL
- [ ] All interactive elements positioned properly
- [ ] All directional icons flip appropriately
- [ ] No horizontal scrolling issues
- [ ] No overlapping elements
- [ ] Consistent spacing and padding
- [ ] All modals and dropdowns work correctly
- [ ] Navigation flows naturally right-to-left
