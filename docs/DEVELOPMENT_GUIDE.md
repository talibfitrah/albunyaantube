# Development Guide

Complete guide for setting up and developing AlBunyaan Tube across all platforms.

---

## Table of Contents

1. [Quick Start](#quick-start)
2. [Docker Setup](#docker-setup)
3. [Platform Guides](#platform-guides)
   - [Backend (Spring Boot + Firebase)](#backend)
   - [Frontend (Vue.js)](#frontend)
   - [Android (Kotlin)](#android)
4. [Testing](#testing)
5. [CI/CD](#cicd)
6. [Deployment](#deployment)

---

## Quick Start

### Prerequisites
- **Java 17** (backend)
- **Node.js 18+** (frontend)
- **Android Studio** (Android, optional)
- **Docker** (recommended)
- **Git**

### First-Time Setup

```bash
# 1. Clone repository
git clone https://github.com/your-org/albunyaantube.git
cd albunyaantube

# 2. Run setup script
./scripts/setup-dev.sh

# 3. Configure environment
cp .env.example .env
nano .env  # Add your YOUTUBE_API_KEY

# 4. Validate setup
./scripts/validate-env.sh

# 5. Start with Docker (recommended)
docker-compose up -d

# OR start manually
# Backend: cd backend && ./gradlew bootRun
# Frontend: cd frontend && npm run dev
```

---

## Docker Setup

### Quick Start with Docker

```bash
# Start all services
docker-compose up -d

# View logs
docker-compose logs -f backend

# Stop services
docker-compose down
```

### Services

| Service | URL | Purpose |
|---------|-----|---------|
| Backend | `http://localhost:8080` | Spring Boot API |
| Frontend | `http://localhost:5173` | Vue.js UI |
| Firebase UI | `http://localhost:4000` | Emulator dashboard |
| Firestore | Port 8082 | Database emulator |
| Auth | Port 9099 | Auth emulator |

### Architecture

```
Android App (Emulator: 10.0.2.2)
    ↓
Backend Container (localhost:8080)
    ↓
Firebase Emulator (localhost:4000)
    ├─ Firestore (8082)
    ├─ Auth (9099)
    └─ Storage (9199)
```

### Docker Commands

```bash
# Restart specific service
docker-compose restart backend

# Rebuild after dependency changes
docker-compose build backend
docker-compose up -d backend

# View service logs
docker-compose logs -f backend

# Clean up everything
docker-compose down -v  # WARNING: Deletes data
```

---

## Platform Guides

### Backend

**Tech Stack**: Spring Boot 3, Java 17, Firebase (Firestore + Auth)

#### Setup

```bash
cd backend

# Download dependencies
./gradlew dependencies

# Run backend
./gradlew bootRun

# Run tests
./gradlew test

# Build JAR
./gradlew bootJar
```

#### Firebase Configuration

1. **Service Account Key**:
   ```bash
   # Download from: Firebase Console > Project Settings > Service Accounts
   # Save as: backend/src/main/resources/albunyaan-tube-firebase-key.json
   ```

2. **Environment Variable**:
   ```bash
   export GOOGLE_APPLICATION_CREDENTIALS="backend/src/main/resources/albunyaan-tube-firebase-key.json"
   ```

3. **Firestore Indexes**:
   ```bash
   firebase deploy --only firestore:indexes
   ```

#### Key Endpoints

**Public** (no auth):
- `GET /api/v1/content` - List content (videos, channels, playlists)
- `GET /api/v1/categories` - List categories
- `GET /api/v1/content/{id}` - Get content details

**Admin** (requires auth):
- `GET /api/admin/categories` - Manage categories
- `GET /api/admin/approvals/pending` - Pending approvals
- `POST /api/admin/approvals/{id}/approve` - Approve content

**Downloads**:
- `GET /api/downloads/policy/{videoId}` - Check download policy
- `POST /api/downloads/token/{videoId}` - Generate download token
- `GET /api/downloads/manifest/{videoId}` - Get download manifest

---

### Frontend

**Tech Stack**: Vue 3, TypeScript, Vite, Pinia, Firebase Auth

#### Setup

```bash
cd frontend

# Install dependencies
npm ci

# Run dev server
npm run dev

# Run tests
npm test

# Build for production
npm run build

# Preview production build
npm run preview
```

#### Environment Configuration

Create `frontend/.env.local`:
```bash
VITE_API_BASE_URL=http://localhost:8080/api/v1
VITE_FIREBASE_API_KEY=your_firebase_api_key
VITE_FIREBASE_PROJECT_ID=albunyaan-tube
```

#### Project Structure

```
frontend/
├── src/
│   ├── components/     # Vue components
│   ├── views/          # Page views
│   ├── stores/         # Pinia stores
│   ├── services/       # API services
│   ├── router/         # Vue Router
│   └── assets/         # Static assets
├── tests/              # Vitest tests
└── vite.config.ts      # Vite configuration
```

---

### Android

**Tech Stack**: Kotlin, Jetpack (Navigation, ViewModel, DataStore), Retrofit, ExoPlayer

#### Setup

1. **Open in Android Studio**:
   ```bash
   # Open the 'android' directory in Android Studio
   ```

2. **Configure Backend URL**:
   ```kotlin
   // android/app/build.gradle.kts
   buildConfigField("String", "API_BASE_URL", "\"http://10.0.2.2:8080/\"")
   // Use 10.0.2.2 for emulator, or your local IP for physical device
   ```

3. **Sync Gradle**:
   - Android Studio → File → Sync Project with Gradle Files

4. **Run App**:
   - Select device/emulator
   - Click Run (▶️)

#### Key Commands

```bash
cd android

# Build debug APK
./gradlew assembleDebug

# Run unit tests
./gradlew test

# Run instrumentation tests
./gradlew connectedAndroidTest

# Run lint
./gradlew lint
```

#### Project Structure

```
android/app/src/main/java/com/albunyaan/tube/
├── ui/                 # Fragments, ViewModels
│   ├── home/
│   ├── player/
│   ├── downloads/
│   └── settings/
├── data/               # Data layer
│   ├── model/          # Domain models
│   ├── repository/     # Repositories
│   └── service/        # API services
├── di/                 # Dependency injection
└── player/             # ExoPlayer integration
```

---

## Testing

### Backend Tests

```bash
cd backend

# Unit tests
./gradlew test

# Integration tests (with Firestore emulator)
./gradlew integrationTest

# Test coverage
./gradlew jacocoTestReport
```

### Frontend Tests

```bash
cd frontend

# Run all tests
npm test

# Watch mode
npm run test:watch

# Coverage
npm run test:coverage
```

### Android Tests

```bash
cd android

# Unit tests
./gradlew test

# Instrumentation tests (requires device/emulator)
./gradlew connectedAndroidTest
```

#### Test Utilities

**Android**: See `android/app/src/androidTest/README.md`
- MockWebServerRule for API mocking
- TestDataBuilder for test data
- BaseInstrumentationTest base class

**Frontend**: See `frontend/tests/utils/README.md`
- mockApiClient for Axios mocking
- mockFirebaseAuth for auth mocking
- testData builders

---

## CI/CD

### GitHub Actions Workflows

All workflows run on push to `main` and on pull requests:

1. **Android CI** (`.github/workflows/android-ci.yml`)
   - Build APK
   - Run unit tests
   - Run lint checks
   - Upload artifacts (APK, test results, lint reports)

2. **Frontend CI** (`.github/workflows/frontend-ci.yml`)
   - Install dependencies
   - Run linter
   - Run type checks
   - Run tests with coverage
   - Build production bundle

3. **Backend CI** (`.github/workflows/backend-ci.yml`)
   - Build with Gradle
   - Run unit tests
   - Run integration tests
   - Test coverage report

### Running CI Locally

```bash
# Install act (GitHub Actions local runner)
brew install act  # macOS
# or: https://github.com/nektos/act

# Run Android CI
act -j build -W .github/workflows/android-ci.yml

# Run Frontend CI
act -j build -W .github/workflows/frontend-ci.yml
```

---

## Deployment

### Backend Deployment

**Production Checklist**:
- [ ] Configure production Firebase project
- [ ] Set up Cloud Firestore (not emulator)
- [ ] Configure environment variables
- [ ] Deploy to Cloud Run / GKE
- [ ] Set up monitoring (Prometheus/Grafana)
- [ ] Configure DNS and SSL

**Docker Production Build**:
```bash
cd backend
docker build -t albunyaan-tube-backend:latest .
docker run -p 8080:8080 \
  -e GOOGLE_APPLICATION_CREDENTIALS=/app/key.json \
  -e YOUTUBE_API_KEY=your_key \
  albunyaan-tube-backend:latest
```

### Frontend Deployment

```bash
cd frontend

# Build for production
npm run build

# Deploy to Vercel/Netlify/Firebase Hosting
# Option 1: Vercel
vercel deploy

# Option 2: Firebase Hosting
firebase deploy --only hosting

# Option 3: Netlify
netlify deploy --prod
```

### Android Deployment

1. **Generate Release APK**:
   ```bash
   cd android
   ./gradlew assembleRelease
   ```

2. **Sign APK**:
   - Configure `keystore.properties`
   - APK location: `android/app/build/outputs/apk/release/`

3. **Upload to Play Store**:
   - Google Play Console
   - Internal/Alpha/Beta testing
   - Production release

---

## Troubleshooting

### Backend Issues

**"Firebase credentials not found"**:
```bash
export GOOGLE_APPLICATION_CREDENTIALS="backend/src/main/resources/albunyaan-tube-firebase-key.json"
```

**Port 8080 already in use**:
```bash
lsof -ti:8080 | xargs kill -9
```

### Frontend Issues

**"Cannot find module '@/...'"**:
- Check `vite.config.ts` alias configuration
- Restart dev server

**Firebase auth errors**:
- Verify Firebase config in `.env.local`
- Check Firebase project settings

### Android Issues

**"Unable to connect to backend"**:
- Emulator: Use `http://10.0.2.2:8080`
- Physical device: Use your local IP (e.g., `http://192.168.1.167:8080`)
- Check network security config (`res/xml/network_security_config.xml`)

**Gradle sync failed**:
```bash
./gradlew clean
# Android Studio → File → Invalidate Caches / Restart
```

---

## Additional Resources

- **Architecture**: See `docs/architecture/solution-architecture.md`
- **Roadmap**: See `docs/roadmap/roadmap.md`
- **Testing Strategy**: See `docs/testing/test-strategy.md`
- **Security**: See `docs/security/threat-model.md`
- **i18n**: See `docs/i18n/strategy.md`

---

## Support

- **Issues**: https://github.com/anthropics/claude-code/issues
- **Documentation**: https://docs.claude.com/en/docs/claude-code
