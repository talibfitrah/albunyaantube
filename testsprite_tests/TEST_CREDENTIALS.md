# Test Credentials for TestSprite

This document contains the credentials used for automated testing with TestSprite.

## Admin User

- **Email:** `admin@albunyaan.tube`
- **Password:** `ChangeMe!123`
- **Role:** ADMIN
- **Firebase UID:** Created via initial admin setup in `application.yml`

## Moderator User

- **Email:** `moderator@albunyaan.tube`
- **Password:** `ModeratorPass123!`
- **Role:** MODERATOR
- **Firebase UID:** Must be created manually in Firebase Console

## Creating the MODERATOR User

### Option 1: Firebase Console (Recommended for TestSprite)

1. Go to [Firebase Console](https://console.firebase.google.com)
2. Select project: `albunyaan-tube`
3. Navigate to **Authentication** → **Users**
4. Click **Add User**
5. Enter:
   - Email: `moderator@albunyaan.tube`
   - Password: `ModeratorPass123!`
6. After user is created, set custom claims via Firebase Admin SDK or Cloud Functions:
   ```json
   {
     "role": "MODERATOR"
   }
   ```

### Option 2: Backend API (Programmatic)

Use the User Management API (requires ADMIN authentication):

```bash
# First, login as admin to get token
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@albunyaan.tube","password":"ChangeMe!123"}'

# Then create moderator user
curl -X POST http://localhost:8080/api/admin/users \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <admin-token>" \
  -d '{
    "email": "moderator@albunyaan.tube",
    "password": "ModeratorPass123!",
    "displayName": "Test Moderator",
    "role": "MODERATOR"
  }'
```

### Option 3: Test Configuration

For automated test environments, add to `backend/src/test/resources/application-test.yml`:

```yaml
app:
  security:
    initial-moderator:
      email: test-moderator@albunyaan.tube
      password: TestPassword123!
      display-name: Test Moderator
```

## Test Environment

- **Frontend:** `http://localhost:5173`
- **Backend:** `http://localhost:8080`

## Notes

- These credentials are for **testing only** and should **never** be used in production
- The initial admin password should be changed after first login in production
- For security, consider using environment variables for test credentials in CI/CD pipelines
