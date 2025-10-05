# Quick Start: Parallel Work Guide (Sprint 2)

**👥 3 Engineers | 🌳 3 Branches | ⏱️ 2 Weeks | 🎯 Zero Conflicts**

**Sprint**: Sprint 2 - Performance, Integration, & Polish
**Date**: 2025-10-05

---

## 🎉 Sprint 1 Success!

**✅ MERGED TO MAIN:**
- Phase 8: Player & Background Audio
- Phase 9: Downloads & Offline
- Phase 2: Registry & Category APIs
- Phase 3: Admin UI with mock data
- Infrastructure: CI/CD, Docker, Scripts

**Sprint 1 Stats**: 9 tickets, 3 engineers, 3 weeks, **zero conflicts!** 🚀

---

## 🚀 Getting Started - Sprint 2 (5 minutes)

### 1️⃣ Choose Your Role

| Role | You Build | Your Directory | Your Branch | Your Color |
|------|-----------|----------------|-------------|------------|
| **Backend Engineer** | Approval API & Performance | `backend/` | `feature/backend-approval-performance` | 🔴 |
| **Frontend Engineer** | Backend Integration & Performance | `frontend/` | `feature/frontend-backend-integration` | 🟢 |
| **Android Engineer** | Performance & Accessibility | `android/` | `feature/android-performance-a11y` | 🔵 |

### 2️⃣ Create Your Branch

```bash
cd /path/to/albunyaantube
git checkout main
git pull origin main
git checkout -b feature/YOUR-BRANCH-NAME
```

### 3️⃣ Announce in Team Chat

> "🔴 Backend Engineer: Starting work on `feature/backend-approval-performance`. Will only touch `backend/` directory. Starting with BACKEND-APPR-01."

---

## 📋 Your First Ticket - Sprint 2

### Step-by-Step

**1. Read Your Prompt**
```bash
cat docs/PARALLEL_WORK_PROMPTS.md
# Find your section (🔴 Backend, 🟢 Frontend, or 🔵 Android)
```

**2. Start First Ticket**
- 🔴 Backend: `BACKEND-APPR-01: Approval Workflow API`
- 🟢 Frontend: `FRONTEND-INT-01: Replace Mock Services with Real Backend`
- 🔵 Android: `ANDROID-PERF-01: Performance Optimization`

**3. Code & Test**
```bash
# Write code in YOUR directory only
# Run tests: npm test / ./gradlew test / ./mvnw test
```

**4. Commit with Template**
```bash
git add -A
git commit -m "TICKET-CODE: Short description

- Feature 1
- Feature 2
- Feature 3

Files Modified:
- path/to/file1
- path/to/file2

🤖 Generated with [Claude Code](https://claude.com/claude-code)

Co-Authored-By: Claude <noreply@anthropic.com>"
```

**5. Push to Remote**
```bash
git push origin feature/YOUR-BRANCH-NAME
```

**6. Update Docs**
```bash
# Edit docs/PROJECT_STATUS.md
# Add your ticket as ✅ completed
git add docs/PROJECT_STATUS.md
git commit -m "DOCS: Update progress for TICKET-CODE"
git push origin feature/YOUR-BRANCH-NAME
```

**7. Announce Completion**
> "🔴 Backend: ✅ BACKEND-REG-01 complete and pushed. Registry endpoints ready."

---

## 🚨 Golden Rules

### ✅ DO

1. **Stay in Your Lane**
   - 🔴 Backend: Touch ONLY `backend/`
   - 🟢 Frontend: Touch ONLY `frontend/`
   - 🔵 Android: Touch ONLY `android/`

2. **Commit After EVERY Ticket**
   - Don't batch multiple tickets
   - Push immediately after commit

3. **Update Docs After EVERY Commit**
   - Edit `docs/PROJECT_STATUS.md`
   - Mark ticket as ✅

4. **Communicate**
   - Before starting: "Starting TICKET-X"
   - After completing: "✅ TICKET-X done"
   - If blocked: "🚨 Blocked on TICKET-X because..."

### ❌ DON'T

1. **Never Touch Other Directories**
   - Backend engineer: Don't touch `android/` or `frontend/`
   - Frontend engineer: Don't touch `backend/` or `android/`
   - Android engineer: Don't touch `backend/` or `frontend/`

2. **Never Batch Commits**
   - ❌ Bad: Complete 3 tickets, commit once
   - ✅ Good: Complete 1 ticket, commit, complete next ticket, commit

3. **Never Work on Main**
   - Always work on your feature branch

4. **Never Merge Without Team**
   - Wait for merge day
   - Follow merge protocol

---

## 🔄 Daily Workflow

### Morning Standup (15 min)

Each engineer reports:

**🔴 Backend**: "Yesterday ✅ BACKEND-APPR-01. Today working on BACKEND-PERF-01. No blockers."

**🟢 Frontend**: "Yesterday ✅ FRONTEND-INT-01. Today working on FRONTEND-PERF-01. All mock services replaced."

**🔵 Android**: "Yesterday ✅ ANDROID-PERF-01. Today working on ANDROID-A11Y-01. Startup time: 1.8s."

### During the Day

```bash
# Morning: Pull latest from your branch
git pull origin feature/YOUR-BRANCH-NAME

# Work on 1 ticket at a time
# Test thoroughly
# Commit & push

# Afternoon: Check team progress
cat docs/PROJECT_STATUS.md
```

### End of Day

```bash
# Push your work
git push origin feature/YOUR-BRANCH-NAME

# Update status doc
# Announce progress in chat
```

---

## 🎯 Using Mock Data (Until Dependencies Ready)

### Frontend Engineer (Waiting for Backend APIs)

```typescript
// frontend/src/services/mockYouTubeService.ts
export const mockYouTubeService = {
  async search(query: string) {
    // Return fake data for now
    return [
      { id: '1', title: 'Test Channel', subscribers: 1000 }
    ]
  }
}

// Later when backend ready:
// Replace with: import { youtubeService } from './youtubeService'
```

### Android Engineer (Waiting for Backend APIs)

```kotlin
// android/.../download/MockDownloadService.kt
class MockDownloadService {
    fun downloadVideo(videoId: String): Flow<DownloadProgress> {
        // Simulate download progress
        return flow {
            for (i in 0..100) {
                delay(100)
                emit(DownloadProgress(i))
            }
        }
    }
}

// Later when backend ready:
// Replace with real DownloadService
```

---

## 📊 Progress Tracking

### Check Team Progress Anytime

```bash
cat docs/PROJECT_STATUS.md | grep "Active Parallel Work" -A 20
```

### Update Your Section

```markdown
## Active Parallel Work - Sprint 2 (2025-10-05)

### 🔴 Backend Engineer: Approval Workflow & Performance
Branch: `feature/backend-approval-performance`
- ✅ BACKEND-APPR-01: Approval workflow API (2025-10-06) ← ADD THIS
- ⏳ BACKEND-PERF-01: Performance optimization (In Progress) ← UPDATE THIS
- ⏸️ BACKEND-TEST-01: Integration tests (Not Started)
```

---

## 🔀 Merge Day (End of Sprint)

### Day Before Merge

```bash
# Pull latest main
git checkout main
git pull origin main

# Rebase your branch
git checkout feature/YOUR-BRANCH-NAME
git rebase main

# Fix any conflicts
# Run ALL tests
# Announce: "Ready to merge feature/YOUR-BRANCH-NAME"
```

### Merge Order

1. **First**: 🔴 Backend merges (most isolated)
2. **Second**: 🟢 Frontend merges (may depend on backend)
3. **Last**: 🔵 Android merges (may depend on backend)

### Your Merge

```bash
# Wait for your turn (see order above)
git checkout main
git pull origin main
git merge feature/YOUR-BRANCH-NAME --no-ff -m "Merge YOUR-BRANCH-NAME to main

✅ TICKET-1 complete
✅ TICKET-2 complete
✅ TICKET-3 complete

All tests passing."

git push origin main

# Announce in chat
# "✅ [YOUR-COLOR] Merged to main. Tests passing."
```

---

## 🆘 Help & Troubleshooting

### "I accidentally edited someone else's file!"

```bash
# Immediately revert
git revert HEAD

# Announce in chat
# "🚨 Accidentally touched [FILE]. Reverted. Sorry!"

# Create new commit with only your changes
```

### "I have merge conflicts!"

1. Don't panic
2. Contact the affected engineer in chat
3. Schedule quick video call
4. Resolve together
5. Document resolution in commit message

### "I need an API that's not ready yet!"

Use mock data (see examples above):
- Frontend: `mockYouTubeService.ts`
- Android: `MockDownloadService.kt`

Work independently, swap mocks for real services later.

### "I'm blocked!"

Post in chat immediately:
> "🚨 [YOUR-COLOR] Blocked on TICKET-X because [REASON]. Need help from [OTHER-ENGINEER]."

Don't wait until standup!

---

## 📞 Communication Templates

### Starting a Ticket
> "🔴 Backend: Starting BACKEND-APPR-01. ETA: 3 days."

### Completing a Ticket
> "🔴 Backend: ✅ BACKEND-APPR-01 complete. Pushed to `feature/backend-approval-performance`. All tests passing."

### Blocked
> "🟢 Frontend: 🚨 Blocked on FRONTEND-INT-01. Need approval endpoints from backend."

### Ready to Merge
> "🔵 Android: Ready to merge `feature/android-performance-a11y`. All 3 tickets complete. Tests passing. Waiting for backend and frontend to merge first."

---

## ✅ Checklist: Before You Start

- [ ] Read full prompt in `docs/PARALLEL_WORK_PROMPTS.md`
- [ ] Understand your boundaries (which directory you own)
- [ ] Created your feature branch
- [ ] Announced in team chat
- [ ] Know your first ticket code
- [ ] Have commit message template ready
- [ ] Know how to update docs after each commit

---

## 📚 Full Documentation

- **Full Prompts**: `docs/PARALLEL_WORK_PROMPTS.md`
- **Project Status**: `docs/PROJECT_STATUS.md`
- **Roadmap**: `docs/roadmap/roadmap.md`
- **Architecture**: `docs/architecture/solution-architecture.md`

---

## 🎉 Sprint 2 Success Metrics

By end of 2 weeks, you will have:

- **🔴 Backend**: 3 tickets complete (Approval API, Performance, Tests)
- **🟢 Frontend**: 3 tickets complete (Backend Integration, Performance, Tests)
- **🔵 Android**: 3 tickets complete (Performance, Accessibility, Tests)

**Sprint 2 Goals**:
- ✅ Approval workflow fully functional
- ✅ All mock services replaced with real APIs
- ✅ Performance optimized (startup <2s, 60fps scrolling)
- ✅ Accessibility complete (TalkBack, contrast)
- ✅ Test coverage >70% across all platforms
- ✅ Production-ready codebase

**Total**: 9 tickets, zero conflicts, clean merge to main! 🚀

---

**Questions?** Check `docs/PARALLEL_WORK_PROMPTS.md` or ask in team chat!
