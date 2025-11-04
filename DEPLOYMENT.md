# Quick Deployment Guide

This is a quick reference for deploying Albunyaan Tube backend to production.

For detailed instructions, see [docs/deployment/VPS_DEPLOYMENT.md](docs/deployment/VPS_DEPLOYMENT.md).

---

## Prerequisites

- VPS server (Ubuntu/Debian) with SSH access
- Firebase service account JSON in `backend/src/main/resources/firebase-service-account.json`
- YouTube API key

---

## Deploy to VPS (Recommended)

### One-Command Deployment

```bash
# Deploy with seeded data (first time)
./scripts/deploy-to-vps.sh YOUR_SERVER_IP --seed

# Deploy without seeding (updates)
./scripts/deploy-to-vps.sh YOUR_SERVER_IP
```

**Example:**
```bash
./scripts/deploy-to-vps.sh 192.168.1.100 --seed
```

This automatically:
- ✅ Builds JAR file
- ✅ Sets up server (installs Java)
- ✅ Copies files
- ✅ Seeds database (if --seed flag used)
- ✅ Installs systemd service
- ✅ Starts backend

---

## Post-Deployment

### Set Up Nginx (Optional but Recommended)

On your VPS server:

```bash
# Copy setup script
scp scripts/setup-nginx.sh root@YOUR_SERVER_IP:/tmp/
scp scripts/nginx/albunyaan-backend.conf root@YOUR_SERVER_IP:/tmp/

# SSH and run
ssh root@YOUR_SERVER_IP
cd /tmp
chmod +x setup-nginx.sh
./setup-nginx.sh
```

### Set Up SSL/HTTPS

```bash
# On VPS server
sudo apt-get install -y certbot python3-certbot-nginx
sudo certbot --nginx -d api.albunyaan.tube
```

### Update Android App

Update API URL in [android/app/build.gradle.kts](android/app/build.gradle.kts):

```kotlin
buildConfigField("String", "API_BASE_URL", "\"http://YOUR_SERVER_IP:8080/\"")
```

Rebuild APK:

```bash
cd android
./gradlew assembleDebug
```

---

## Useful Commands

```bash
# View logs
ssh root@YOUR_SERVER_IP 'tail -f /opt/albunyaan/logs/app.log'

# Service status
ssh root@YOUR_SERVER_IP 'sudo systemctl status albunyaan-backend'

# Restart service
ssh root@YOUR_SERVER_IP 'sudo systemctl restart albunyaan-backend'

# Test backend
curl http://YOUR_SERVER_IP:8080/actuator/health
curl http://YOUR_SERVER_IP:8080/api/v1/categories | jq '. | length'
```

---

## Troubleshooting

**Service won't start:**
```bash
ssh root@YOUR_SERVER_IP 'sudo journalctl -u albunyaan-backend -n 50'
```

**Re-run seed:**
```bash
ssh root@YOUR_SERVER_IP << 'EOF'
  sudo systemctl stop albunyaan-backend
  cd /opt/albunyaan
  source .env
  java -jar backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=real-seed
  sudo systemctl start albunyaan-backend
EOF
```

**Port 8080 blocked:**
```bash
ssh root@YOUR_SERVER_IP 'sudo ufw allow 8080/tcp'
```

---

## Alternative Deployment Options

See [docs/deployment/](docs/deployment/) for:
- Cloud Run (Google Cloud Platform)
- Docker Compose on VPS
- Kubernetes (GKE/EKS)

---

For complete documentation, see [docs/deployment/VPS_DEPLOYMENT.md](docs/deployment/VPS_DEPLOYMENT.md).
