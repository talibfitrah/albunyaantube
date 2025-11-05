# VPS Deployment Guide - Albunyaan Tube Backend

Complete guide for deploying the Albunyaan Tube backend to a VPS (Virtual Private Server) with seeded data.

---

## Table of Contents

1. [Prerequisites](#prerequisites)
2. [Quick Start](#quick-start)
3. [Manual Deployment](#manual-deployment)
4. [Post-Deployment Configuration](#post-deployment-configuration)
5. [SSL/HTTPS Setup](#sslhttps-setup)
6. [Monitoring & Maintenance](#monitoring--maintenance)
7. [Troubleshooting](#troubleshooting)

---

## Prerequisites

### Local Machine Requirements
- **SSH access** to your VPS
- **Java 17** (for building)
- **Gradle** (included via gradlew)
- **Firebase service account JSON** file

### VPS Server Requirements
- **OS**: Ubuntu 20.04+ or Debian 11+ (recommended)
- **RAM**: Minimum 1GB, recommended 2GB+
- **Disk**: At least 5GB free space
- **CPU**: 1 vCPU minimum, 2+ recommended
- **Network**: Public IP address with port 80/443 accessible

### Firebase Requirements
- Production Firebase project created
- Firestore enabled in production mode
- Service account key downloaded (JSON file)

### API Keys
- **YouTube Data API v3** key with quota enabled

---

## Quick Start

### Option 1: Automated Deployment Script ✅ (Recommended)

This single command deploys everything:

```bash
# From your local machine
cd /home/farouq/Development/albunyaantube

# Deploy with seeded data
./scripts/deploy-to-vps.sh YOUR_SERVER_IP --seed

# Deploy without seeding (if data already exists)
./scripts/deploy-to-vps.sh YOUR_SERVER_IP
```

**What it does:**
1. ✅ Builds the JAR file locally
2. ✅ Prepares the VPS server (installs Java, creates directories)
3. ✅ Copies JAR and Firebase credentials to server
4. ✅ Sets up environment variables
5. ✅ Seeds database (if --seed flag provided)
6. ✅ Installs and starts systemd service
7. ✅ Verifies deployment

**Example:**
```bash
./scripts/deploy-to-vps.sh 192.168.1.100 --seed
```

You'll be prompted for:
- YouTube API Key
- Firebase Project ID (default: albunyaan-tube)

---

## Manual Deployment

If you prefer manual control or the script doesn't work:

### Step 1: Build JAR Locally

```bash
cd /home/farouq/Development/albunyaantube/backend
./gradlew clean bootJar

# Verify JAR exists (name may vary)
ls -lh build/libs/*.jar
```

### Step 2: Prepare VPS Server

SSH into your server:

```bash
ssh root@YOUR_SERVER_IP
```

Install Java 17:

```bash
sudo apt-get update
sudo apt-get install -y openjdk-17-jdk

# Verify installation
java -version
# Should show: openjdk version "17.x.x"
```

Create deployment directory:

```bash
sudo mkdir -p /opt/albunyaan
sudo chown $USER:$USER /opt/albunyaan
mkdir -p /opt/albunyaan/logs
```

### Step 3: Copy Files to Server

From your **local machine**:

```bash
cd /home/farouq/Development/albunyaantube/backend

# Copy JAR
scp build/libs/backend-0.0.1-SNAPSHOT.jar root@YOUR_SERVER_IP:/opt/albunyaan/

# Copy Firebase credentials
scp src/main/resources/firebase-service-account.json root@YOUR_SERVER_IP:/opt/albunyaan/
```

### Step 4: Set Up Environment Variables

On the **VPS server**, create environment file:

```bash
nano /opt/albunyaan/.env
```

Add the following:

```bash
GOOGLE_APPLICATION_CREDENTIALS=/opt/albunyaan/firebase-service-account.json
FIREBASE_PROJECT_ID=albunyaan-tube
YOUTUBE_API_KEY=YOUR_YOUTUBE_API_KEY_HERE
FIREBASE_SERVICE_ACCOUNT_PATH=/opt/albunyaan/firebase-service-account.json
```

Save and exit (Ctrl+X, Y, Enter).

### Step 5: Seed Database (First Time Only)

Run the backend with seed profile:

```bash
cd /opt/albunyaan
source .env

java -jar backend-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=real-seed \
  --server.port=8080
```

**Wait for seed to complete**. You'll see logs like:

```
✅ Seeded 19 categories
✅ Seeded 20 channels
✅ Seeded 16 playlists
✅ Seeded 76 videos
🎉 Real YouTube data seeding completed successfully!
```

Once you see `Started AlbunyaanTubeApplication`, press **Ctrl+C** to stop.

### Step 6: Set Up Systemd Service

Copy the systemd service file from your local machine:

```bash
# From local machine
cd /home/farouq/Development/albunyaantube
scp scripts/systemd/albunyaan-backend.service root@YOUR_SERVER_IP:/tmp/
```

On the **VPS server**:

```bash
# Install service
sudo mv /tmp/albunyaan-backend.service /etc/systemd/system/
sudo systemctl daemon-reload

# Enable and start service
sudo systemctl enable albunyaan-backend
sudo systemctl start albunyaan-backend

# Check status
sudo systemctl status albunyaan-backend
```

You should see `active (running)` status.

### Step 7: Verify Deployment

Test the backend:

```bash
# Health check
curl http://localhost:8080/actuator/health

# Should return: {"status":"UP"}

# Check categories
curl http://localhost:8080/api/v1/categories | jq '. | length'

# Should return: 19

# Check channels
curl http://localhost:8080/api/v1/content/channels | jq '. | length'

# Should return: 20
```

---

## Post-Deployment Configuration

### Set Up Nginx Reverse Proxy

Nginx provides:
- SSL/HTTPS support
- Better performance
- Load balancing capabilities
- Static file serving

#### Automated Setup

```bash
# Copy nginx setup script to server
scp scripts/setup-nginx.sh root@YOUR_SERVER_IP:/tmp/
scp scripts/nginx/albunyaan-backend.conf root@YOUR_SERVER_IP:/tmp/

# SSH into server and run
ssh root@YOUR_SERVER_IP
cd /tmp
chmod +x setup-nginx.sh
./setup-nginx.sh
```

#### Manual Nginx Setup

Install nginx:

```bash
sudo apt-get install -y nginx
```

Create nginx config:

```bash
sudo nano /etc/nginx/sites-available/albunyaan-backend
```

Copy the contents from [scripts/nginx/albunyaan-backend.conf](../../scripts/nginx/albunyaan-backend.conf).

**Important:** Update the `server_name` directive:

```nginx
server_name YOUR_DOMAIN.com;  # e.g., api.albunyaan.tube
```

Enable the site:

```bash
sudo ln -s /etc/nginx/sites-available/albunyaan-backend /etc/nginx/sites-enabled/
sudo rm /etc/nginx/sites-enabled/default  # Remove default site
sudo nginx -t  # Test configuration
sudo systemctl reload nginx
```

Test nginx:

```bash
curl http://localhost/actuator/health
```

### Configure Firewall

Allow HTTP/HTTPS traffic:

```bash
# Using UFW (Ubuntu Firewall)
sudo ufw allow 'Nginx Full'
sudo ufw allow ssh
sudo ufw enable

# Or manually with iptables
sudo iptables -A INPUT -p tcp --dport 80 -j ACCEPT
sudo iptables -A INPUT -p tcp --dport 443 -j ACCEPT
```

### Update Android App Configuration

Update the API URL in your Android app to point to your VPS:

```kotlin
// In android/app/build.gradle.kts
buildConfigField("String", "API_BASE_URL", "\"http://YOUR_SERVER_IP:8080/\"")

// Or if using nginx on port 80:
buildConfigField("String", "API_BASE_URL", "\"http://YOUR_SERVER_IP/\"")

// With domain:
buildConfigField("String", "API_BASE_URL", "\"https://api.albunyaan.tube/\"")
```

Rebuild the Android APK:

```bash
cd android
./gradlew assembleDebug
```

---

## SSL/HTTPS Setup

### Using Let's Encrypt (Free SSL)

Install Certbot:

```bash
sudo apt-get install -y certbot python3-certbot-nginx
```

Obtain SSL certificate:

```bash
# Replace with your domain
sudo certbot --nginx -d api.albunyaan.tube

# Follow the prompts:
# - Enter your email
# - Agree to Terms of Service
# - Choose whether to redirect HTTP to HTTPS (recommended: yes)
```

Certbot will:
1. Verify domain ownership
2. Obtain SSL certificate
3. Automatically update nginx configuration
4. Set up auto-renewal

Test auto-renewal:

```bash
sudo certbot renew --dry-run
```

### Manual SSL Configuration

If you have your own SSL certificates:

1. Place certificates in `/etc/ssl/certs/`:
   ```bash
   sudo cp your-cert.crt /etc/ssl/certs/albunyaan.crt
   sudo cp your-key.key /etc/ssl/private/albunyaan.key
   ```

2. Update nginx configuration:
   ```nginx
   ssl_certificate /etc/ssl/certs/albunyaan.crt;
   ssl_certificate_key /etc/ssl/private/albunyaan.key;
   ```

3. Reload nginx:
   ```bash
   sudo systemctl reload nginx
   ```

---

## Monitoring & Maintenance

### View Logs

Application logs:

```bash
# Real-time logs
tail -f /opt/albunyaan/logs/app.log

# Error logs
tail -f /opt/albunyaan/logs/error.log

# Systemd logs
sudo journalctl -u albunyaan-backend -f
```

### Service Management

```bash
# Start service
sudo systemctl start albunyaan-backend

# Stop service
sudo systemctl stop albunyaan-backend

# Restart service
sudo systemctl restart albunyaan-backend

# Check status
sudo systemctl status albunyaan-backend

# View service logs
sudo journalctl -u albunyaan-backend --since today
```

### Update Deployment

To deploy a new version:

```bash
# From local machine
./scripts/deploy-to-vps.sh YOUR_SERVER_IP

# This will:
# 1. Build new JAR
# 2. Copy to server
# 3. Restart service automatically
```

Or manually:

```bash
# Build new JAR locally
cd backend && ./gradlew bootJar

# Copy to server
scp build/libs/backend-0.0.1-SNAPSHOT.jar root@YOUR_SERVER_IP:/opt/albunyaan/

# Restart on server
ssh root@YOUR_SERVER_IP "sudo systemctl restart albunyaan-backend"
```

### Health Monitoring

Set up a cron job to monitor health:

```bash
# On VPS server
crontab -e
```

Add:

```bash
# Check backend health every 5 minutes
*/5 * * * * curl -f http://localhost:8080/actuator/health || systemctl restart albunyaan-backend
```

### Database Backup

Firestore data is automatically backed up by Firebase, but you can export:

```bash
# From local machine with Firebase CLI
firebase firestore:export gs://albunyaan-tube-backups/$(date +%Y%m%d)
```

### Disk Space Management

Monitor disk usage:

```bash
# Check disk space
df -h

# Check log sizes
du -sh /opt/albunyaan/logs/*

# Rotate logs (create logrotate config)
sudo nano /etc/logrotate.d/albunyaan
```

Add:

```
/opt/albunyaan/logs/*.log {
    daily
    rotate 14
    compress
    delaycompress
    notifempty
    missingok
    copytruncate
}
```

---

## Troubleshooting

### Service Won't Start

Check logs:

```bash
sudo journalctl -u albunyaan-backend -n 50
```

Common issues:

**Java not found:**
```bash
# Install Java 17
sudo apt-get install -y openjdk-17-jdk
```

**Firebase credentials error:**
```bash
# Verify file exists
ls -l /opt/albunyaan/firebase-service-account.json

# Check environment file
cat /opt/albunyaan/.env
```

**Port 8080 already in use:**
```bash
# Find process using port
sudo lsof -i :8080

# Kill it
sudo kill -9 <PID>

# Restart service
sudo systemctl restart albunyaan-backend
```

### Cannot Connect from Android App

**Firewall blocking:**
```bash
# Check firewall status
sudo ufw status

# Allow port 8080
sudo ufw allow 8080/tcp
```

**Wrong IP in Android app:**
```bash
# Verify server's public IP
curl ifconfig.me

# Update android/app/build.gradle.kts with this IP
```

**CORS errors:**
Backend already allows all origins via `setAllowedOriginPatterns("*")` in [SecurityConfig.java:88](../../backend/src/main/java/com/albunyaan/tube/security/SecurityConfig.java#L88).

### Seed Data Not Loading

**Check if seed ran:**
```bash
grep "seeding completed" /opt/albunyaan/logs/app.log
```

**Re-run seed manually:**
```bash
sudo systemctl stop albunyaan-backend

cd /opt/albunyaan
source .env
java -jar backend.jar --spring.profiles.active=real-seed

# Wait for completion, then restart service
sudo systemctl start albunyaan-backend
```

### API Returns Empty Results Despite Data in Firestore

**Symptoms:**
- Seeder shows items already exist and skips them
- `curl http://localhost:8080/api/v1/content?type=CHANNELS` returns `{"data":[]}`
- Firestore console shows data exists

**Root Cause:**
Seeded data is missing required fields for Firestore queries (e.g., `subscribers` field for `orderBy("subscribers")` queries).

**Solution:**
Run the approval status fixer to add missing fields:

```bash
cd /opt/albunyaan
source .env
sudo systemctl stop albunyaan-backend

# Run the fixer
java -jar backend.jar \
    --spring.profiles.active=fix-approval \
    --server.port=8080 \
    --spring.config.location=file:/opt/albunyaan/application-prod.yml

# Wait for "Approval Status Fix Complete" message
# Then restart
sudo systemctl start albunyaan-backend
```

Verify the fix:
```bash
curl http://localhost:8080/api/v1/content?type=CHANNELS&limit=10 | jq '.data | length'
# Should return > 0
```

### Android App Shows Empty Screens

**Symptoms:**
- Backend API works: `curl http://YOUR_IP:8080/api/v1/categories` returns data
- Android app shows empty home/channels/videos tabs

**Common Causes:**

1. **Network Security Config** (most common):
   - Android blocks HTTP to unlisted IPs
   - Fix: Add VPS IP to `network_security_config.xml` (see [Android App Configuration](#android-app-configuration))

2. **Wrong API Base URL**:
   - Check `android/app/build.gradle.kts` has correct IP
   - Should be: `buildConfigField("String", "API_BASE_URL", "\"http://YOUR_IP:8080/\"")`

3. **API Response Structure Mismatch**:
   - Android expects `{"data": [...], "pageInfo": {...}}`
   - Verify backend returns correct structure: `curl http://YOUR_IP:8080/api/v1/content?type=CHANNELS&limit=1`

**Debug Steps:**
```bash
# Check Android logs
adb logcat | grep -i "albunyaan\|retrofit\|network"

# Test backend from Android device's network
# (if on same WiFi as dev machine)
curl http://YOUR_VPS_IP:8080/actuator/health
```

### High Memory Usage

Adjust Java heap size in systemd service:

```bash
sudo nano /etc/systemd/system/albunyaan-backend.service
```

Change `JAVA_OPTS`:

```ini
Environment="JAVA_OPTS=-Xmx512m -Xms256m -XX:+UseG1GC"
```

Reload:

```bash
sudo systemctl daemon-reload
sudo systemctl restart albunyaan-backend
```

### SSL Certificate Issues

**Certificate expired:**
```bash
# Renew with Certbot
sudo certbot renew
```

**Certificate not trusted:**
```bash
# Verify certificate chain
openssl s_client -connect api.albunyaan.tube:443 -showcerts
```

---

## Performance Optimization

### Enable Redis Caching (Production)

Install Redis:

```bash
sudo apt-get install -y redis-server
sudo systemctl enable redis-server
sudo systemctl start redis-server
```

Update backend configuration (on server):

```bash
nano /opt/albunyaan/application-prod.yml
```

Add:

```yaml
spring:
  cache:
    type: redis
  data:
    redis:
      host: localhost
      port: 6379
```

Restart backend with production profile:

```bash
# Update .env
echo "SPRING_PROFILES_ACTIVE=production" >> /opt/albunyaan/.env

sudo systemctl restart albunyaan-backend
```

### Nginx Caching

Add to nginx config:

```nginx
# Cache zone
proxy_cache_path /var/cache/nginx levels=1:2 keys_zone=api_cache:10m max_size=1g inactive=60m;

# In location block
proxy_cache api_cache;
proxy_cache_valid 200 5m;
proxy_cache_key "$scheme$request_method$host$request_uri";
```

---

## Security Best Practices

### 1. Change Default Admin Password

After first login, change the default admin password in Firebase Console.

### 2. Enable Firewall

```bash
sudo ufw enable
sudo ufw allow ssh
sudo ufw allow 'Nginx Full'
sudo ufw status
```

### 3. Disable Root Login

```bash
# Create non-root user
sudo adduser albunyaan
sudo usermod -aG sudo albunyaan

# Disable root SSH login
sudo nano /etc/ssh/sshd_config
# Set: PermitRootLogin no

sudo systemctl restart sshd
```

### 4. Keep System Updated

```bash
# Set up automatic security updates
sudo apt-get install -y unattended-upgrades
sudo dpkg-reconfigure --priority=low unattended-upgrades
```

### 5. Monitor Failed Login Attempts

```bash
# Install fail2ban
sudo apt-get install -y fail2ban
sudo systemctl enable fail2ban
sudo systemctl start fail2ban
```

---

## Next Steps

After deployment:

1. ✅ **Test all API endpoints** from Android app
2. ✅ **Set up monitoring** (optional: Prometheus + Grafana)
3. ✅ **Configure backup strategy** for Firestore
4. ✅ **Set up CI/CD** for automated deployments (GitHub Actions)
5. ✅ **Monitor logs** for errors and performance issues

---

## Support

If you encounter issues:

1. Check [Troubleshooting](#troubleshooting) section
2. Review logs: `/opt/albunyaan/logs/app.log`
3. Check systemd status: `sudo systemctl status albunyaan-backend`
4. Verify Firestore data in Firebase Console

---

---

## Android App Configuration

After deploying the backend to VPS, you must configure the Android app to connect to your server.

**See the "Update Android App Configuration" section above** (lines 306-326) for detailed instructions on:
- Setting the API base URL via `local.properties`
- Configuring network security for HTTP/HTTPS
- Rebuilding and installing the APK

**Security Note:** The app now uses `local.properties` to configure the server URL, so production IPs are never committed to version control. For production deployments, always use HTTPS.

---

**Last Updated:** 2025-11-05
**Tested On:** Ubuntu 22.04 LTS, Debian 11
