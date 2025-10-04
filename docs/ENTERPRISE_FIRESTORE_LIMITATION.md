# Enterprise Firestore Limitation - BACKEND-005

## Status: BLOCKED ⛔

## Issue Summary

The backend cannot execute Firestore queries because the database is **Enterprise Edition**, which has limited API support in the Firebase Admin SDK.

## Error Details

```
FAILED_PRECONDITION: Service not implemented for Enterprise DB: RunQuery
```

**Location**: All repository `findAll()` methods that use Firestore queries

**Root Cause**: Firebase Admin SDK's Firestore client does not support the `RunQuery` operation on Enterprise Firestore databases. This is a fundamental API limitation, not a configuration issue.

## Database Configuration

- **Database ID**: `default`
- **Database Type**: Enterprise Edition
- **Location**: `eur3` (Europe multi-region)
- **Project**: `albunyaan-tube`

## Affected Code

All Firestore repository query operations are affected:

```java
// CategoryRepository.java - findAll()
public List<Category> findAll() throws ExecutionException, InterruptedException {
    ApiFuture<QuerySnapshot> query = getCollection()  // ❌ Uses RunQuery internally
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .get();
    return query.get().toObjects(Category.class);
}
```

**Affected Repositories:**
- CategoryRepository.findAll()
- ChannelRepository.findAll()
- PlaylistRepository.findAllByOrderByItemCountDesc()
- VideoRepository.findAllByOrderByUploadedAtDesc()
- All search and filter methods

## Impact

1. **Data Seeding**: Cannot populate Firestore with sample data
2. **Content API**: `/api/v1/content` endpoint will fail on query operations
3. **Category API**: `/api/v1/categories` endpoint will fail
4. **Backend-Android Integration**: Cannot test end-to-end with real data

## Resolution Options

### Option 1: Switch to Standard Firestore (RECOMMENDED)

**Action**: Recreate the Firestore database in Standard/Native mode

**Pros:**
- Full Firebase Admin SDK support
- All existing code will work without changes
- Best long-term compatibility

**Cons:**
- Requires database recreation
- Data migration needed if any data exists
- Brief downtime during switch

**Steps:**
1. Export any existing data (if present)
2. Delete Enterprise database
3. Create new Standard Firestore database in same location
4. Run data seeder
5. Test all endpoints

### Option 2: Use Google Cloud Datastore Client

**Action**: Replace Firebase Admin SDK Firestore with Google Cloud Datastore client library

**Pros:**
- Works with Enterprise Firestore
- No database recreation needed

**Cons:**
- Different API - requires refactoring all repositories
- Lose Firebase-specific features
- More complex migration path

**Steps:**
1. Add Datastore dependency to build.gradle.kts
2. Rewrite all repositories to use Datastore API
3. Update FirebaseConfig
4. Test thoroughly

### Option 3: Direct Document Access Only

**Action**: Modify repositories to avoid query operations, use direct document access only

**Pros:**
- No database changes
- Minimal dependency changes

**Cons:**
- Severely limited functionality
- No filtering, sorting, or search
- Requires pre-known document IDs
- Not suitable for content discovery app

**Steps:**
1. Remove all query methods
2. Add methods like `findById(String id)`
3. Redesign API to work without queries
4. Significant app architecture changes needed

### Option 4: Defer Issue - Continue with Mock Data

**Action**: Skip Firestore data seeding, continue development with FakeContentService

**Pros:**
- No immediate changes needed
- Can continue with Phases 6-12
- Defer decision until later

**Cons:**
- Backend-Android integration incomplete
- Cannot test real data flows
- Technical debt accumulates

**Steps:**
1. Keep using FakeContentService in Android app
2. Continue with enhanced features (Phase 6-12)
3. Revisit database decision before production

## Current State

- ✅ Backend compiles successfully
- ✅ Backend starts and runs on port 8080
- ✅ Firestore connection established
- ✅ RTL fixes complete (BACKEND-002)
- ✅ Redis removed from architecture (BACKEND-004)
- ❌ Cannot execute Firestore queries
- ❌ Cannot seed database with sample data
- ❌ Cannot test content API with real data

## Recommendation

**Switch to Standard Firestore (Option 1)** because:
1. Aligns with Firebase Admin SDK best practices
2. No code changes required
3. Full query support for content discovery
4. Database appears empty (minimal migration needed)

## Files Involved

**Created:**
- `FirestoreDataSeeder.java` - Ready to use once database is switched

**Modified:**
- `application.yml` - Database ID corrected to "default"
- `FirebaseConfig.java` - Explicit FirestoreOptions configuration

## Next Actions Required

**User must choose** which option to proceed with:
- Option 1: Recreate as Standard Firestore ✅ RECOMMENDED
- Option 2: Switch to Datastore client
- Option 3: Direct document access only
- Option 4: Defer and continue with mock data

---

**Created**: 2025-10-04
**Ticket**: BACKEND-005
**Status**: Awaiting user decision
