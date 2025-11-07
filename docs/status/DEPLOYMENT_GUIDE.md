# Deployment Guide

Complete guide for deploying Albunyaan Tube to VPS and production environments.

**Last Updated**: November 7, 2025

---

## Table of Contents
1. [VPS Deployment](#vps-deployment)
2. [Prerequisites](#prerequisites)
3. [Quick Deploy](#quick-deploy)
4. [Manual Deployment](#manual-deployment)
5. [HTTPS Setup](#https-setup)
6. [Monitoring](#monitoring)
7. [Deployment Checklist](#deployment-checklist)

---

## VPS Deployment

### Prerequisites

**Local Machine**:
- SSH access to VPS
- Java 17 (for building)
- Firebase service account JSON file
- YouTube Data API v3 key

**VPS Server**:
- Ubuntu 20.04+ or Debian 11+
- Minimum 1GB RAM (2GB+ recommended)
- 5GB+ free disk space
- Public IP with ports 80/443 accessible

**Firebase**:
- Production Firebase project
- Firestore enabled
- Service account key (JSON)

---

## Quick Deploy

### Automated Deployment (Recommended)

```bash
# From project root
./scripts/deploy-to-vps.sh YOUR_SERVER_IP --seed
```

**What it does**:
1. Builds JAR file locally
2. Prepares VPS (installs Java, creates directories)
3. Copies JAR and Firebase credentials
4. Sets up environment variables
5. Seeds database (if --seed flag)
6. Installs systemd service
7. Verifies deployment

**Example**:
```bash
./scripts/deploy-to-vps.sh 192.168.1.100 --seed
```

You'll be prompted for:
- YouTube API Key
- Firebase Project ID (default: albunyaan-tube)

---

## Manual Deployment

### 1. Build JAR

```bash
cd backend
./gradlew clean bootJar

# Verify JAR exists
ls -lh build/libs/*.jar
```

### 2. Prepare VPS

```bash
# SSH into server
ssh root@YOUR_SERVER_IP

# Install Java 17
sudo apt-get update
sudo apt-get install -y openjdk-17-jdk

# Verify
java -version

# Create deployment directory
sudo mkdir -p /opt/albunyaan
sudo chown $USER:$USER /opt/albunyaan
mkdir -p /opt/albunyaan/logs
```

### 3. Copy Files

From local machine:

```bash
cd backend

# Copy JAR
scp build/libs/backend-0.0.1-SNAPSHOT.jar root@YOUR_SERVER_IP:/opt/albunyaan/

# Copy Firebase credentials
scp src/main/resources/firebase-service-account.json root@YOUR_SERVER_IP:/opt/albunyaan/
```

### 4. Configure Environment

On VPS, create `/opt/albunyaan/.env`:

```bash
GOOGLE_APPLICATION_CREDENTIALS=/opt/albunyaan/firebase-service-account.json
FIREBASE_PROJECT_ID=albunyaan-tube
YOUTUBE_API_KEY=YOUR_YOUTUBE_API_KEY_HERE
FIREBASE_SERVICE_ACCOUNT_PATH=/opt/albunyaan/firebase-service-account.json
```

### 5. Create Systemd Service

Create `/etc/systemd/system/albunyaan-backend.service`:

```ini
[Unit]
Description=Albunyaan Tube Backend
After=network.target

[Service]
Type=simple
User=root
WorkingDirectory=/opt/albunyaan
EnvironmentFile=/opt/albunyaan/.env
ExecStart=/usr/bin/java -jar /opt/albunyaan/backend-0.0.1-SNAPSHOT.jar
Restart=on-failure
RestartSec=10
StandardOutput=append:/opt/albunyaan/logs/app.log
StandardError=append:/opt/albunyaan/logs/error.log

[Install]
WantedBy=multi-user.target
```

### 6. Start Service

```bash
sudo systemctl daemon-reload
sudo systemctl enable albunyaan-backend
sudo systemctl start albunyaan-backend

# Check status
sudo systemctl status albunyaan-backend
```

### 7. Verify Deployment

```bash
# Wait 30 seconds for startup

# Test health endpoint
curl http://localhost:8080/actuator/health

# Test categories endpoint
curl http://localhost:8080/api/v1/categories | jq length
# Should return: 19
```

---

## HTTPS Setup

### Using Let's Encrypt with Nginx

#### 1. Install Nginx

```bash
sudo apt-get install -y nginx certbot python3-certbot-nginx
```

#### 2. Configure Nginx

Create `/etc/nginx/sites-available/albunyaan`:

```nginx
server {
    listen 80;
    server_name api.yourdomain.com;

    location / {
        proxy_pass http://localhost:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

Enable site:
```bash
sudo ln -s /etc/nginx/sites-available/albunyaan /etc/nginx/sites-enabled/
sudo nginx -t
sudo systemctl restart nginx
```

#### 3. Get SSL Certificate

```bash
sudo certbot --nginx -d api.yourdomain.com
```

Certbot will automatically:
- Obtain certificate
- Configure HTTPS in Nginx
- Set up auto-renewal

#### 4. Verify HTTPS

```bash
curl https://api.yourdomain.com/actuator/health
```

---

## Monitoring

### Check Service Status

```bash
# Service status
sudo systemctl status albunyaan-backend

# View logs (last 50 lines)
tail -n 50 /opt/albunyaan/logs/app.log

# Follow logs in real-time
tail -f /opt/albunyaan/logs/app.log

# Check for errors
grep ERROR /opt/albunyaan/logs/error.log
```

### Common Management Commands

```bash
# Restart service
sudo systemctl restart albunyaan-backend

# Stop service
sudo systemctl stop albunyaan-backend

# Start service
sudo systemctl start albunyaan-backend

# View recent logs
journalctl -u albunyaan-backend -n 100 -f
```

### Resource Monitoring

```bash
# Check disk space
df -h

# Check memory usage
free -h

# Check CPU usage
top

# Check port 8080 is listening
sudo netstat -tulpn | grep 8080
```

---

## Deployment Checklist

### Pre-Deployment

- [ ] Firebase project created and configured
- [ ] Service account JSON downloaded
- [ ] YouTube API key obtained and tested
- [ ] VPS provisioned with required specs
- [ ] SSH access to VPS configured
- [ ] Domain name configured (for HTTPS)

### Build & Deploy

- [ ] Backend JAR built successfully (`./gradlew bootJar`)
- [ ] JAR and credentials copied to VPS
- [ ] Environment variables configured
- [ ] Systemd service created and enabled
- [ ] Service started successfully
- [ ] Firewall allows ports 80/443/8080

### Verification

- [ ] Health endpoint returns `{"status":"UP"}`
- [ ] Categories endpoint returns 19 items
- [ ] Content endpoint returns data
- [ ] Search endpoint works
- [ ] Logs show no critical errors
- [ ] Service survives server reboot

### Post-Deployment

- [ ] HTTPS configured with Let's Encrypt
- [ ] SSL certificate auto-renewal enabled
- [ ] Monitoring alerts configured
- [ ] Backup strategy implemented
- [ ] Documentation updated

### Android App Configuration

- [ ] Update `android/local.properties`:
  ```properties
  api.base.url=https://api.yourdomain.com/
  ```
- [ ] Remove cleartext traffic permission from `network_security_config.xml`
- [ ] Build release APK
- [ ] Test app with production backend

---

## Troubleshooting

### Service Won't Start

```bash
# Check logs for errors
journalctl -u albunyaan-backend -n 100

# Common issues:
# 1. Port 8080 already in use
sudo lsof -i :8080

# 2. Firebase credentials not found
ls -la /opt/albunyaan/firebase-service-account.json

# 3. Environment variables not set
cat /opt/albunyaan/.env
```

### Can't Connect to Backend

```bash
# Check if service is running
sudo systemctl status albunyaan-backend

# Check if port is open
sudo ufw allow 8080/tcp

# Test from server
curl http://localhost:8080/actuator/health

# Test from outside
curl http://YOUR_SERVER_IP:8080/actuator/health
```

### SSL Certificate Issues

```bash
# Test SSL certificate
sudo certbot certificates

# Renew certificate manually
sudo certbot renew --dry-run

# Check Nginx configuration
sudo nginx -t
```

### Performance Issues

```bash
# Check Java process
ps aux | grep java

# Check memory usage
free -h

# Check disk space
df -h

# View slow queries in logs
grep "SlowQuery" /opt/albunyaan/logs/app.log
```

---

## Additional Resources

- **Project Status**: `docs/status/PROJECT_STATUS.md`
- **Development Guide**: `docs/status/DEVELOPMENT_GUIDE.md`
- **Android Guide**: `docs/status/ANDROID_GUIDE.md`
- **Architecture**: `docs/architecture/overview.md`

---

**Last Updated**: November 7, 2025
**Consolidated From**: VPS_DEPLOYMENT.md, DEPLOYMENT_CHECKLIST.md, deployment/README.md
