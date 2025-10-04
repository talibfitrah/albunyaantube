# Firestore Composite Indexes

## Overview

Firestore requires composite indexes for queries that filter or sort on multiple fields. This document explains how to deploy the required indexes for the Albunyaan Tube backend.

## Required Indexes

The indexes are defined in `backend/src/main/resources/firestore.indexes.json`:

- **Categories**: `displayOrder` + `name`, `parentCategoryId` + `displayOrder`
- **Channels**: `status` + `subscribers` (DESC)
- **Playlists**: `status` + `itemCount` (DESC)
- **Videos**: `status` + `uploadedAt` (DESC)

## Deployment Methods

### Method 1: Firebase CLI (Recommended)

1. Install Firebase CLI:
   ```bash
   npm install -g firebase-tools
   ```

2. Login to Firebase:
   ```bash
   firebase login
   ```

3. Initialize Firestore in your project (if not already done):
   ```bash
   cd backend
   firebase init firestore
   # Select your project: albunyaan-tube
   # Use default for firestore.rules
   # Use src/main/resources/firestore.indexes.json for indexes file
   ```

4. Deploy indexes:
   ```bash
   firebase deploy --only firestore:indexes
   ```

5. Wait for index creation (can take several minutes):
   - Check status in Firebase Console > Firestore Database > Indexes tab
   - Or use: `firebase firestore:indexes`

### Method 2: Firebase Console (Manual)

1. Go to [Firebase Console](https://console.firebase.google.com/)
2. Select project: `albunyaan-tube`
3. Navigate to Firestore Database > Indexes
4. Click "Create Index" for each required index:

   **Categories Index 1:**
   - Collection: `categories`
   - Fields:
     - `displayOrder`: Ascending
     - `name`: Ascending
   - Query scope: Collection

   **Categories Index 2:**
   - Collection: `categories`
   - Fields:
     - `parentCategoryId`: Ascending
     - `displayOrder`: Ascending
   - Query scope: Collection

   **Channels Index:**
   - Collection: `channels`
   - Fields:
     - `status`: Ascending
     - `subscribers`: Descending
   - Query scope: Collection

   **Playlists Index:**
   - Collection: `playlists`
   - Fields:
     - `status`: Ascending
     - `itemCount`: Descending
   - Query scope: Collection

   **Videos Index:**
   - Collection: `videos`
   - Fields:
     - `status`: Ascending
     - `uploadedAt`: Descending
   - Query scope: Collection

### Method 3: Use Index Creation Links from Errors

When you run a query that requires an index, Firestore provides a link in the error message:

```
FAILED_PRECONDITION: The query requires an index. You can create it here: https://console.firebase.google.com/...
```

Click the link to automatically create the required index.

## Verification

After deploying indexes, verify they're active:

1. **Firebase Console**:
   - Go to Firestore Database > Indexes
   - Check all indexes show status: "Enabled"

2. **Test API**:
   ```bash
   curl http://localhost:8080/api/v1/content?type=HOME&limit=10
   curl http://localhost:8080/api/v1/categories
   ```

3. **Check Backend Logs**:
   - No "FAILED_PRECONDITION" errors
   - Successful query execution

## Troubleshooting

### Index Creation Taking Too Long
- Small datasets (< 1000 docs): Usually< 5 minutes
- Larger datasets: Can take 30+ minutes
- Check Firebase Console for progress

### Index Creation Failed
- Verify project ID matches in firebase.json
- Check Firebase billing is enabled
- Ensure you have Owner/Editor role in Firebase project

### Query Still Failing After Index Creation
- Wait a few minutes for index to finish building
- Clear backend cache: Restart Spring Boot app
- Verify index is "Enabled" not "Building" in console

## Index Maintenance

### When to Add New Indexes

Add indexes when you see errors like:
- `FAILED_PRECONDITION: The query requires an index`
- `io.grpc.StatusRuntimeException: FAILED_PRECONDITION`

### How to Add New Indexes

1. Update `backend/src/main/resources/firestore.indexes.json`
2. Deploy: `firebase deploy --only firestore:indexes`
3. Wait for index creation
4. Test the query

### Removing Unused Indexes

1. Remove from `firestore.indexes.json`
2. Deploy: `firebase deploy --only firestore:indexes`
3. Manually delete from Firebase Console (optional cleanup)

## Performance Impact

**Index Creation:**
- No downtime required
- Background process
- Existing queries continue to work

**Index Usage:**
- Improves query performance significantly
- Required for compound queries
- Minimal storage overhead

## Current Status

✅ Indexes defined in `firestore.indexes.json`
⏳ **Action Required**: Deploy indexes using Method 1 (Firebase CLI)

## Next Steps

1. Deploy indexes: `firebase deploy --only firestore:indexes`
2. Wait for completion (check Firebase Console)
3. Test backend API endpoints
4. Verify Android app integration

---

**Last Updated**: 2025-10-04
**File**: `backend/src/main/resources/firestore.indexes.json`
