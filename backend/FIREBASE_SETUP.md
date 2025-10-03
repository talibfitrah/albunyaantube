# Firebase Setup Guide

## FIREBASE-MIGRATE-01: Firebase Project Configuration

This guide walks through setting up Firebase for the Albunyaan Tube backend.

## Prerequisites
- Google Cloud Platform account
- Firebase Console access

## Step 1: Create Firebase Project

1. Go to [Firebase Console](https://console.firebase.google.com/)
2. Click "Add Project"
3. Enter project name: `albunyaan-tube`
4. Disable Google Analytics (optional for admin-only backend)
5. Click "Create Project"

## Step 2: Enable Firestore Database

1. In Firebase Console, navigate to **Firestore Database**
2. Click "Create Database"
3. Choose production mode (we'll configure rules later)
4. Select a location (e.g., `us-central1` or closest to your users)
5. Click "Enable"

## Step 3: Enable Firebase Authentication

1. Navigate to **Authentication** in Firebase Console
2. Click "Get Started"
3. Enable **Email/Password** provider
4. Click "Save"

## Step 4: Generate Service Account Key

1. In Firebase Console, click the gear icon (⚙️) → **Project Settings**
2. Navigate to the **Service Accounts** tab
3. Click "Generate New Private Key"
4. Download the JSON file
5. **IMPORTANT**: Keep this file secure - it grants admin access to your Firebase project

## Step 5: Configure Backend

1. Rename the downloaded file to `firebase-service-account.json`
2. Place it in `backend/src/main/resources/` directory
3. **NEVER commit this file to version control**
4. Update `.gitignore` to exclude it:
   ```
   **/firebase-service-account.json
   ```

### Environment Variables (Production)

For production deployments, use environment variables instead of the JSON file:

```bash
export FIREBASE_SERVICE_ACCOUNT_PATH=/path/to/firebase-service-account.json
export FIREBASE_PROJECT_ID=albunyaan-tube
export YOUTUBE_API_KEY=your-youtube-api-key
```

## Step 6: Enable YouTube Data API

1. Go to [Google Cloud Console](https://console.cloud.google.com/)
2. Select your Firebase project
3. Navigate to **APIs & Services** → **Library**
4. Search for "YouTube Data API v3"
5. Click "Enable"
6. Navigate to **APIs & Services** → **Credentials**
7. Click "Create Credentials" → "API Key"
8. Copy the API key
9. Set the `YOUTUBE_API_KEY` environment variable or update `application.yml`

## Step 7: Set Up Initial Admin User

The backend will automatically create an initial admin user on first startup using Firebase Authentication.

Default credentials (configured in `application.yml`):
- Email: `admin@albunyaan.tube`
- Password: `ChangeMe!123`

**IMPORTANT**: Change these credentials immediately after first login.

## Step 8: Configure Firestore Security Rules

In Firebase Console → Firestore Database → Rules, update with:

```javascript
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    // Helper functions
    function isAuthenticated() {
      return request.auth != null;
    }

    function isAdmin() {
      return isAuthenticated() &&
             get(/databases/$(database)/documents/users/$(request.auth.uid)).data.role == 'admin';
    }

    function isModerator() {
      return isAuthenticated() &&
             (get(/databases/$(database)/documents/users/$(request.auth.uid)).data.role == 'moderator' ||
              get(/databases/$(database)/documents/users/$(request.auth.uid)).data.role == 'admin');
    }

    // Users collection
    match /users/{userId} {
      allow read: if isAuthenticated();
      allow write: if isAdmin();
    }

    // Categories collection
    match /categories/{categoryId} {
      allow read: if true; // Public read for mobile app
      allow write: if isAdmin();
    }

    // Content collections
    match /channels/{channelId} {
      allow read: if true;
      allow create: if isModerator();
      allow update, delete: if isAdmin();
    }

    match /playlists/{playlistId} {
      allow read: if true;
      allow create: if isModerator();
      allow update, delete: if isAdmin();
    }

    match /videos/{videoId} {
      allow read: if true;
      allow create: if isModerator();
      allow update, delete: if isAdmin();
    }

    // Moderation proposals
    match /moderationProposals/{proposalId} {
      allow read: if isModerator();
      allow create: if isModerator();
      allow update, delete: if isAdmin();
    }

    // Activity logs
    match /activityLogs/{logId} {
      allow read: if isAdmin();
      allow write: if false; // Only backend can write
    }
  }
}
```

## Step 9: Verify Setup

1. Start the backend application:
   ```bash
   cd backend
   ./gradlew bootRun
   ```

2. Check logs for Firebase initialization:
   ```
   INFO  c.a.t.config.FirebaseConfig - Firebase initialized successfully for project: albunyaan-tube
   ```

3. Test authentication endpoint:
   ```bash
   curl -X POST http://localhost:8080/api/auth/login \
     -H "Content-Type: application/json" \
     -d '{"email":"admin@albunyaan.tube","password":"ChangeMe!123"}'
   ```

## Troubleshooting

### Error: "Failed to initialize Firebase"
- Verify `firebase-service-account.json` is in the correct location
- Check file permissions
- Ensure JSON is valid

### Error: "Invalid API key"
- Verify YouTube Data API is enabled
- Check API key is correctly set
- Ensure API key has no restrictions conflicting with server IP

### Error: "Permission denied" in Firestore
- Review security rules
- Verify user has correct role claims
- Check authentication token is valid

## Next Steps

After Firebase is configured:
1. Run backend and verify initialization
2. Create initial categories via admin UI
3. Add moderator users
4. Test YouTube search integration
5. Configure frontend Firebase SDK

## Related Tickets

- FIREBASE-MIGRATE-01: Firebase project setup (this document)
- FIREBASE-MIGRATE-02: Authentication migration
- FIREBASE-MIGRATE-03: Firestore data models
- FIREBASE-MIGRATE-04: YouTube API integration
