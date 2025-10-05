# Workflow Rules - MUST FOLLOW

## Documentation Updates

### Single Source of Truth
- **PRIMARY:** `docs/TRUE_PROJECT_STATUS.md` - Comprehensive detailed status
- **SECONDARY:** `docs/PROJECT_STATUS.md` - Executive summary version
- **DO NOT CREATE:** Additional session summaries, next steps files, or duplicate status docs
- **ALWAYS UPDATE:** Both status docs after completing work

### After Every Task/Ticket/Sprint

1. **Commit Changes**
   ```bash
   git add .
   git commit -m "[TICKET-CODE]: Brief description of what was done"
   ```

2. **Update Status Documents**
   - Update `docs/TRUE_PROJECT_STATUS.md` with:
     - Mark completed items with ✅
     - Update percentage complete
     - Add new findings/issues
     - Update "What Actually Works" section

   - Update `docs/PROJECT_STATUS.md` with:
     - Update executive summary if needed
     - Mark completed blockers
     - Update phase completion checkboxes

3. **Push Changes**
   ```bash
   git push origin main
   ```

## Commit Message Format

### Format
```
[TICKET-CODE]: Brief description

Detailed changes:
- Change 1
- Change 2
- Change 3

Updated: TRUE_PROJECT_STATUS.md, PROJECT_STATUS.md
```

### Examples
```
[ANDROID-CAT-01]: Wire categories endpoint to Android app

Detailed changes:
- Added fetchCategories() to ContentService interface
- Implemented in RetrofitContentService, FakeContentService, FallbackContentService
- Updated CategoriesFragment to fetch from backend
- Removed hardcoded category data

Files modified:
- android/app/src/main/java/com/albunyaan/tube/data/source/*.kt (4 files)
- android/app/src/main/java/com/albunyaan/tube/ui/categories/CategoriesFragment.kt

Updated: TRUE_PROJECT_STATUS.md, PROJECT_STATUS.md
```

## Ticket Naming Convention

### Format: `[PLATFORM-FEATURE-NUMBER]`

**Platforms:**
- `BACKEND` - Backend API work
- `ADMIN` - Admin dashboard work
- `ANDROID` - Android app work
- `DOCS` - Documentation only
- `INFRA` - Infrastructure/DevOps

**Examples:**
- `ANDROID-CAT-01` - Android categories integration #1
- `BACKEND-DASH-01` - Backend dashboard metrics fix
- `ADMIN-SETTINGS-01` - Admin settings persistence
- `BACKEND-SEED-01` - Backend data seeding

## When to Update Docs

### Update IMMEDIATELY after:
- ✅ Completing a feature/ticket
- ✅ Fixing a blocker
- ✅ Discovering new issues
- ✅ Marking items complete in status docs

### What to Update:
1. Mark checkboxes as complete `[x]`
2. Update "What Actually Works" section
3. Update "What's Broken" section (remove fixed items)
4. Update completion percentage
5. Add any new findings to appropriate sections
6. Update effort estimates if changed

## NO Additional Files

### DO NOT CREATE:
- ❌ `SESSION_SUMMARY_*.md`
- ❌ `NEXT_STEPS.md` (use status docs instead)
- ❌ `ANDROID_WALKTHROUGH.md` (use status docs instead)
- ❌ Any other duplicate status files

### Exceptions (OK to create):
- ✅ Specific technical guides (e.g., `CATEGORY_MANAGEMENT.md`)
- ✅ Architecture docs (e.g., `ARCHITECTURE.md`)
- ✅ API docs (e.g., `API_REFERENCE.md`)
- ✅ Development setup (e.g., `DEVELOPMENT_GUIDE.md`)

## Workflow Example

```bash
# 1. Do work
# ... make code changes ...

# 2. Test changes
./gradlew test  # or whatever testing needed

# 3. Update status docs
# Edit docs/TRUE_PROJECT_STATUS.md
# Edit docs/PROJECT_STATUS.md

# 4. Commit with ticket code
git add .
git commit -m "[ANDROID-CAT-01]: Wire categories endpoint to Android app

- Added fetchCategories() to all service implementations
- Updated CategoriesFragment to fetch from backend
- Verified logs show successful API calls

Updated: TRUE_PROJECT_STATUS.md, PROJECT_STATUS.md"

# 5. Push
git push origin main
```

## Before Starting New Work

1. Read `docs/TRUE_PROJECT_STATUS.md` to understand current state
2. Check "Required Work to Complete" section
3. Pick next priority item (usually from Phase A)
4. Create ticket code for tracking
5. Do the work
6. Follow workflow above

## Summary

**ALWAYS:**
- Use ticket codes in commits
- Update both status docs after completing work
- Commit and push after each completed task
- Keep status docs as single source of truth

**NEVER:**
- Create duplicate status tracking files
- Skip status doc updates
- Commit without ticket code
- Push without updating docs
