# Docker Development Setup

> **Purpose**: Run the complete AlBunyaan Tube backend infrastructure locally using Docker Compose.

## Quick Start

```bash
# 1. Copy environment template
cp .env.example .env

# 2. Edit .env with your YouTube API key
nano .env

# 3. Start all services
docker-compose up -d

# 4. View logs
docker-compose logs -f backend

# 5. Stop all services
docker-compose down
```

## Services

### Backend (`http://localhost:8080`)
- Spring Boot application
- Auto-reloads on code changes (volume mounted)
- Health check: `http://localhost:8080/actuator/health`

### Firebase Emulator Suite (`http://localhost:4000`)
- **Emulator UI**: `http://localhost:4000`
- **Firestore**: Port 8082
- **Auth**: Port 9099
- **Storage**: Port 9199
- **Functions**: Port 5001

Data is persisted in `./firebase-data/` and automatically imported/exported.

## Architecture

```
┌─────────────────────────────────────────────────┐
│  Android App (Emulator: 10.0.2.2)              │
│  http://10.0.2.2:8080                           │
└─────────────────────┬───────────────────────────┘
                      │
                      ▼
┌─────────────────────────────────────────────────┐
│  Backend Container                              │
│  - Spring Boot (Port 8080)                      │
│  - Gradle build cache                           │
│  - Auto-reload enabled                          │
└─────────────────────┬───────────────────────────┘
                      │
                      ▼
┌─────────────────────────────────────────────────┐
│  Firebase Emulator Container                    │
│  - Firestore (Port 8082)                        │
│  - Auth (Port 9099)                             │
│  - Storage (Port 9199)                          │
│  - Emulator UI (Port 4000)                      │
│  - Data persistence: ./firebase-data/           │
└─────────────────────────────────────────────────┘
```

## Commands

### Start Services
```bash
# Start in background
docker-compose up -d

# Start with logs
docker-compose up

# Start specific service
docker-compose up backend
```

### View Logs
```bash
# All services
docker-compose logs -f

# Specific service
docker-compose logs -f backend
docker-compose logs -f firebase-emulator
```

### Restart Services
```bash
# Restart all
docker-compose restart

# Restart specific service
docker-compose restart backend
```

### Stop Services
```bash
# Stop all (keeps containers)
docker-compose stop

# Stop and remove containers
docker-compose down

# Stop and remove volumes (CAUTION: deletes data)
docker-compose down -v
```

### Rebuild
```bash
# Rebuild backend after dependency changes
docker-compose build backend

# Rebuild and restart
docker-compose up -d --build backend
```

## Development Workflow

### Backend Development
1. Start services: `docker-compose up -d`
2. Edit code in `backend/src/`
3. Changes auto-reload (Spring DevTools)
4. View logs: `docker-compose logs -f backend`

### Database Management
1. Access Firestore UI: `http://localhost:4000/firestore`
2. Data is automatically persisted to `./firebase-data/`
3. To reset data: `rm -rf firebase-data && docker-compose restart firebase-emulator`

### Debugging
```bash
# Access backend container
docker-compose exec backend sh

# Check backend health
curl http://localhost:8080/actuator/health

# Check Firebase emulator
curl http://localhost:4000
```

## Troubleshooting

### Backend won't start
```bash
# Check logs
docker-compose logs backend

# Rebuild
docker-compose build backend
docker-compose up -d backend
```

### Firebase emulator issues
```bash
# Restart emulator
docker-compose restart firebase-emulator

# Clear data and restart
rm -rf firebase-data
docker-compose restart firebase-emulator
```

### Port conflicts
If ports are already in use, edit `docker-compose.yml`:
```yaml
ports:
  - "8081:8080"  # Change host port (left side)
```

### Gradle build fails
```bash
# Clear Gradle cache
docker-compose down
docker volume rm albunyaantube_backend-cache
docker-compose up -d --build backend
```

## Android App Configuration

When running Android app in emulator with Docker backend:

### Update `ContentServiceModule.kt`:
```kotlin
private const val BASE_URL = "http://10.0.2.2:8080/api/v1/"
```

### Add network security config:
```xml
<!-- android/app/src/main/res/xml/network_security_config.xml -->
<network-security-config>
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="true">10.0.2.2</domain>
    </domain-config>
</network-security-config>
```

## Production Deployment

For production deployment, see [DEPLOYMENT.md](DEPLOYMENT.md) (TODO).

Key differences:
- Use Google Cloud Firestore (not emulator)
- Use Cloud Run or GKE
- Enable HTTPS
- Configure production Firebase project

## Resources

- [Docker Compose Documentation](https://docs.docker.com/compose/)
- [Firebase Emulator Suite](https://firebase.google.com/docs/emulator-suite)
- [Spring Boot with Docker](https://spring.io/guides/gs/spring-boot-docker/)
