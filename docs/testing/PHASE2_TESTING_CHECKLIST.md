# Phase 2 Testing Checklist - Content Library & Exclusions

**Date Created:** 2025-11-07
**Feature Branch:** `claude/exclusions-content-library-011CUqLa9evW4sMZ1LKgkoDW`
**Commit:** `97cdb6c`

## Overview

This document provides a comprehensive testing checklist for Phase 2 features:
- Channel and Playlist detail modals with exclusion management
- Bulk actions in Content Library (approve, reject, delete)
- Internationalization support (en, ar, nl)

## Prerequisites

### Backend Setup
```bash
cd backend
./gradlew bootRun
# Verify backend running at http://localhost:8080
curl http://localhost:8080/api/v1/categories
```

### Frontend Setup
```bash
cd frontend
npm install
npm run dev
# Frontend should start at http://localhost:5173
```

### Test Data
Ensure backend has seeded data:
```bash
# Run backend with seed profile
./gradlew bootRun --args='--spring.profiles.active=seed'
```

Expected seed data:
- 20 channels
- 16 playlists
- 76 videos
- 19 categories

---

## Test Plan

### 1. Content Library - Modal Integration

#### 1.1 Channel Detail Modal

**Test Case 1.1.1: Open Channel Modal**
- [ ] Navigate to Content Library
- [ ] Filter to show only Channels
- [ ] Click "View Details" on any channel
- [ ] **Expected:** ChannelDetailModal opens with channel thumbnail, title, and subscriber count
- [ ] **Expected:** Modal shows two tabs: "Videos" and "Playlists"

**Test Case 1.1.2: Channel Videos Tab**
- [ ] With channel modal open, ensure "Videos" tab is active
- [ ] **Expected:** List of channel videos displayed with thumbnails and titles
- [ ] Scroll to bottom of list
- [ ] **Expected:** "Loading more..." appears and additional videos load (infinite scroll)
- [ ] **Expected:** No duplicate API calls (check Network tab - should be throttled)

**Test Case 1.1.3: Channel Videos Search**
- [ ] In Videos tab, type search query in search box
- [ ] Wait 500ms (debounce delay)
- [ ] **Expected:** Results filtered to match search query
- [ ] Clear search
- [ ] **Expected:** All videos reappear

**Test Case 1.1.4: Channel Playlists Tab**
- [ ] Click "Playlists" tab
- [ ] **Expected:** List of channel playlists displayed
- [ ] Scroll to bottom
- [ ] **Expected:** Infinite scroll loads more playlists

**Test Case 1.1.5: Add Video Exclusion**
- [ ] In Videos tab, click "Exclude" button on a video
- [ ] **Expected:** Button changes to "Remove Exclusion"
- [ ] **Expected:** Success feedback shown
- [ ] Close modal and reopen
- [ ] **Expected:** Video still shows as excluded

**Test Case 1.1.6: Add Playlist Exclusion**
- [ ] In Playlists tab, click "Exclude" button on a playlist
- [ ] **Expected:** Button changes to "Remove Exclusion"
- [ ] **Expected:** Success feedback shown

**Test Case 1.1.7: Remove Exclusion**
- [ ] Click "Remove Exclusion" on an excluded item
- [ ] **Expected:** Button changes back to "Exclude"
- [ ] **Expected:** Success feedback shown

**Test Case 1.1.8: Modal Close**
- [ ] Click outside modal (overlay)
- [ ] **Expected:** Modal closes
- [ ] Click "View Details" again and click X button
- [ ] **Expected:** Modal closes

#### 1.2 Playlist Detail Modal

**Test Case 1.2.1: Open Playlist Modal**
- [ ] Navigate to Content Library
- [ ] Filter to show only Playlists
- [ ] Click "View Details" on any playlist
- [ ] **Expected:** PlaylistDetailModal opens with playlist thumbnail, title, and video count

**Test Case 1.2.2: Playlist Videos List**
- [ ] **Expected:** List of playlist videos displayed
- [ ] Scroll to bottom
- [ ] **Expected:** Infinite scroll loads more videos

**Test Case 1.2.3: Playlist Videos Search**
- [ ] Type search query in search box
- [ ] Wait 500ms
- [ ] **Expected:** Results filtered by search query
- [ ] Clear search
- [ ] **Expected:** All videos reappear

**Test Case 1.2.4: Add Video Exclusion**
- [ ] Click "Exclude" on a video
- [ ] **Expected:** Button changes to "Remove Exclusion"
- [ ] **Expected:** Success feedback shown
- [ ] Close and reopen modal
- [ ] **Expected:** Exclusion persists

**Test Case 1.2.5: Remove Video Exclusion**
- [ ] Click "Remove Exclusion" on excluded video
- [ ] **Expected:** Button changes to "Exclude"
- [ ] **Expected:** Success feedback shown

---

### 2. Content Library - Bulk Actions

#### 2.1 Selection Management

**Test Case 2.1.1: Select Single Item**
- [ ] Navigate to Content Library
- [ ] Click checkbox on one content item
- [ ] **Expected:** Checkbox checked, selection count shows "1 selected"
- [ ] **Expected:** "Clear Selection" and "Bulk Actions" buttons appear

**Test Case 2.1.2: Select Multiple Items**
- [ ] Select 3 different items (mix of channels, playlists, videos)
- [ ] **Expected:** Selection count shows "3 selected"
- [ ] **Expected:** All selected items have checked checkboxes

**Test Case 2.1.3: Clear Selection**
- [ ] With items selected, click "Clear Selection"
- [ ] **Expected:** All checkboxes unchecked
- [ ] **Expected:** Selection count disappears
- [ ] **Expected:** Bulk action buttons hidden

**Test Case 2.1.4: Deselect Individual Item**
- [ ] Select 3 items
- [ ] Uncheck one item
- [ ] **Expected:** Selection count shows "2 selected"

#### 2.2 Bulk Approve

**Test Case 2.2.1: Bulk Approve Pending Items**
- [ ] Filter to show "Pending" status items
- [ ] Select 2-3 pending items
- [ ] Click "Bulk Actions" > "Approve Selected"
- [ ] **Expected:** Success message: "Success - X items approved"
- [ ] **Expected:** Content list refreshes
- [ ] **Expected:** Selected items now show "Approved" status
- [ ] **Expected:** Selection cleared

**Test Case 2.2.2: Bulk Approve with Error**
- [ ] Stop backend server
- [ ] Select items and try bulk approve
- [ ] **Expected:** Error message displayed
- [ ] **Expected:** Selection not cleared (user can retry)

#### 2.3 Bulk Reject

**Test Case 2.3.1: Bulk Reject Items**
- [ ] Filter to show "Pending" or "Approved" items
- [ ] Select 2-3 items
- [ ] Click "Bulk Actions" > "Mark as Pending" (which calls reject)
- [ ] **Expected:** Success message: "Success - X items rejected"
- [ ] **Expected:** Content list refreshes
- [ ] **Expected:** Items status updated

#### 2.4 Bulk Delete

**Test Case 2.4.1: Bulk Delete Confirmation**
- [ ] Select 3 items
- [ ] Click "Bulk Actions" > "Delete Selected"
- [ ] **Expected:** Confirmation dialog: "Are you sure you want to delete 3 items?"

**Test Case 2.4.2: Bulk Delete Cancel**
- [ ] In confirmation dialog, click "Cancel"
- [ ] **Expected:** No deletion occurs
- [ ] **Expected:** Items still selected

**Test Case 2.4.3: Bulk Delete Confirm**
- [ ] Select 2 items
- [ ] Click "Delete Selected" and confirm
- [ ] **Expected:** Success message: "Success - 2 items deleted"
- [ ] **Expected:** Content list refreshes
- [ ] **Expected:** Deleted items no longer visible
- [ ] **Expected:** Selection cleared

#### 2.5 Bulk Assign Categories

**Test Case 2.5.1: Bulk Category Assignment**
- [ ] Select 2-3 items
- [ ] Click "Bulk Actions" > "Assign Categories"
- [ ] **Expected:** Category selection modal opens
- [ ] Select 1-2 categories
- [ ] Confirm assignment
- [ ] **Expected:** Success message
- [ ] **Expected:** Items now show assigned categories

---

### 3. Internationalization (i18n)

#### 3.1 English Locale

**Test Case 3.1.1: English UI**
- [ ] Set locale to English (en)
- [ ] Open Channel Detail Modal
- [ ] **Expected:** Tab labels show "Videos" and "Playlists"
- [ ] **Expected:** Search placeholder: "Search within channel..."
- [ ] **Expected:** Button text: "Exclude" / "Remove Exclusion"
- [ ] **Expected:** Loading text: "Loading more..."
- [ ] **Expected:** No results text: "No results found"

**Test Case 3.1.2: English Bulk Actions**
- [ ] In Content Library, select items
- [ ] **Expected:** Button text: "Clear Selection", "Bulk Actions"
- [ ] Open bulk menu
- [ ] **Expected:** Menu items: "Approve Selected", "Mark as Pending", "Assign Categories", "Delete Selected"
- [ ] Perform bulk approve
- [ ] **Expected:** Success message: "Success - X items approved"

#### 3.2 Arabic Locale (RTL)

**Test Case 3.2.1: Arabic UI**
- [ ] Set locale to Arabic (ar)
- [ ] **Expected:** UI layout switches to RTL (right-to-left)
- [ ] Open Channel Detail Modal
- [ ] **Expected:** Tab labels: "الفيديوهات" and "قوائم التشغيل"
- [ ] **Expected:** Search placeholder: "البحث داخل القناة..."
- [ ] **Expected:** Button text: "استبعاد" / "إزالة الاستثناء"
- [ ] **Expected:** Loading text: "جارٍ تحميل المزيد..."

**Test Case 3.2.2: Arabic Bulk Actions**
- [ ] Select items in Content Library
- [ ] **Expected:** Button text: "مسح التحديد", "إجراءات جماعية"
- [ ] Open bulk menu
- [ ] **Expected:** Menu items in Arabic
- [ ] Perform action
- [ ] **Expected:** Success message in Arabic: "نجاح - X عنصر معتمد"

**Test Case 3.2.3: Arabic RTL Layout**
- [ ] Verify modal opens from right side (RTL)
- [ ] Verify scroll direction is RTL
- [ ] Verify checkboxes align to right
- [ ] Verify text alignment is right-aligned

#### 3.3 Dutch Locale

**Test Case 3.3.1: Dutch UI**
- [ ] Set locale to Dutch (nl)
- [ ] Open Channel Detail Modal
- [ ] **Expected:** Tab labels: "Video's" and "Afspeellijsten"
- [ ] **Expected:** Search placeholder: "Zoeken binnen kanaal..."
- [ ] **Expected:** Button text: "Uitsluiten" / "Uitzondering verwijderen"
- [ ] **Expected:** Loading text: "Meer laden..."

**Test Case 3.3.2: Dutch Bulk Actions**
- [ ] Select items
- [ ] **Expected:** Button text: "Selectie wissen", "Bulkacties"
- [ ] Open bulk menu
- [ ] **Expected:** Menu items: "Geselecteerde goedkeuren", "Markeren als in behandeling", etc.
- [ ] Perform action
- [ ] **Expected:** Success message: "Succes - X items goedgekeurd"

---

### 4. Error Handling

#### 4.1 Network Errors

**Test Case 4.1.1: Modal Load Error**
- [ ] Stop backend
- [ ] Try to open Channel Detail Modal
- [ ] **Expected:** Error message shown
- [ ] **Expected:** "Retry" button available
- [ ] Restart backend and click "Retry"
- [ ] **Expected:** Data loads successfully

**Test Case 4.1.2: Exclusion Add Error**
- [ ] Stop backend
- [ ] Try to add exclusion
- [ ] **Expected:** Error message: "Error adding exclusion"
- [ ] **Expected:** Button state doesn't change (user can retry)

**Test Case 4.1.3: Bulk Action Error**
- [ ] Stop backend
- [ ] Try bulk approve
- [ ] **Expected:** Error alert: "Error performing bulk action: [error message]"
- [ ] **Expected:** Selection not cleared

#### 4.2 Empty States

**Test Case 4.2.1: Channel with No Videos**
- [ ] Open channel that has no videos
- [ ] **Expected:** "No results found" message

**Test Case 4.2.2: Channel with No Playlists**
- [ ] Switch to Playlists tab for channel without playlists
- [ ] **Expected:** "No results found" message

**Test Case 4.2.3: Search No Results**
- [ ] Search for non-existent term
- [ ] **Expected:** "No results found" message
- [ ] **Expected:** Clear search option available

---

### 5. Performance Testing

#### 5.1 Infinite Scroll Performance

**Test Case 5.1.1: Throttling**
- [ ] Open Channel Detail Modal
- [ ] Scroll rapidly to bottom multiple times
- [ ] Open Network tab in DevTools
- [ ] **Expected:** API calls throttled to 500ms intervals
- [ ] **Expected:** No duplicate simultaneous requests

**Test Case 5.1.2: Large Dataset**
- [ ] Open channel with 100+ videos
- [ ] Scroll through all videos
- [ ] **Expected:** Smooth scrolling performance
- [ ] **Expected:** Memory usage stable (check DevTools Performance)

#### 5.2 Search Debouncing

**Test Case 5.2.1: Search Debounce**
- [ ] Type search query quickly
- [ ] **Expected:** API call only after 500ms of no typing
- [ ] **Expected:** No API call for every keystroke

#### 5.3 Bulk Action Performance

**Test Case 5.3.1: Large Bulk Operation**
- [ ] Select 20+ items
- [ ] Perform bulk approve
- [ ] **Expected:** Operation completes within reasonable time (<5s)
- [ ] **Expected:** Success/error feedback for all items

---

### 6. Cross-Browser Testing

Test all above scenarios in:
- [ ] Chrome (latest)
- [ ] Firefox (latest)
- [ ] Safari (latest)
- [ ] Edge (latest)

---

### 7. Responsive Design Testing

Test on different screen sizes:
- [ ] Desktop (1920x1080)
- [ ] Laptop (1366x768)
- [ ] Tablet (768x1024)
- [ ] Mobile (375x667)

**Expected:**
- Modals resize appropriately
- Buttons remain accessible
- Scroll works on touch devices
- No horizontal overflow

---

## Bug Report Template

If issues are found, report using this format:

```markdown
**Bug ID:** PHASE2-XXX
**Severity:** Critical / High / Medium / Low
**Test Case:** [Test case ID]
**Environment:** Browser version, OS, screen size

**Steps to Reproduce:**
1.
2.
3.

**Expected Result:**


**Actual Result:**


**Screenshots/Video:**
[Attach if applicable]

**Console Errors:**
[Paste console output]

**Network Tab:**
[Paste failed API calls]
```

---

## Completion Criteria

Phase 2 is considered fully tested when:
- [ ] All test cases in sections 1-7 pass
- [ ] No critical or high-severity bugs remain
- [ ] All three locales (en, ar, nl) display correctly
- [ ] Performance meets expectations (no lag, smooth scrolling)
- [ ] Cross-browser compatibility verified
- [ ] Responsive design works on all target screen sizes

---

## Sign-Off

**Tester:** ___________________
**Date:** ___________________
**Status:** ☐ Pass  ☐ Fail  ☐ Pass with minor issues

**Notes:**


