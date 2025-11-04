# VPS Deployment Checklist

Use this checklist to ensure smooth deployment of Albunyaan Tube backend to your VPS.

---

## Pre-Deployment Checklist

### Firebase Setup
- [ ] Production Firebase project created
- [ ] Firestore enabled in **production mode** (not test mode)
- [ ] Firebase service account key downloaded
- [ ] Service account JSON saved at `backend/src/main/resources/firebase-service-account.json`
- [ ] Firebase Authentication enabled
- [ ] Firestore security rules deployed

### API Keys
- [ ] YouTube Data API v3 enabled in Google Cloud Console
- [ ] YouTube API key created with sufficient quota
- [ ] API key tested and working

### VPS Server
- [ ] VPS provisioned (minimum 1GB RAM, 1 vCPU)
- [ ] Ubuntu 20.04+ or Debian 11+ installed
- [ ] SSH access configured
- [ ] Public IP address obtained
- [ ] Domain name configured (optional, for SSL)

### Local Development
- [ ] Backend builds successfully: `cd backend && ./gradlew bootJar`
- [ ] Firebase credentials file exists and is valid
- [ ] `.gitignore` includes `firebase-service-account.json`

---

## Deployment Checklist

### Step 1: Initial Deployment
- [ ] Run deployment script: `./scripts/deploy-to-vps.sh YOUR_SERVER_IP --seed`
- [ ] Entered YouTube API key when prompted
- [ ] Entered Firebase Project ID when prompted
- [ ] Seed process completed successfully (logs show "seeding completed")
- [ ] Backend service started: `systemctl status albunyaan-backend`

### Step 2: Verification
- [ ] Health endpoint responds: `curl http://YOUR_SERVER_IP:8080/actuator/health`
- [ ] Categories API returns data: `curl http://YOUR_SERVER_IP:8080/api/v1/categories | jq '. | length'` (should return 19)
- [ ] Channels API returns data: `curl http://YOUR_SERVER_IP:8080/api/v1/content/channels | jq '. | length'` (should return 20)
- [ ] Videos API returns data: `curl http://YOUR_SERVER_IP:8080/api/v1/content/videos | jq '. | length'` (should return 76)

### Step 3: Nginx Setup (Recommended)
- [ ] Nginx installed on VPS
- [ ] Nginx config copied from `scripts/nginx/albunyaan-backend.conf`
- [ ] Updated `server_name` directive with your domain
- [ ] Nginx configuration tested: `sudo nginx -t`
- [ ] Nginx reloaded: `sudo systemctl reload nginx`
- [ ] Backend accessible via nginx: `curl http://YOUR_SERVER_IP/actuator/health`

### Step 4: Firewall Configuration
- [ ] UFW enabled: `sudo ufw enable`
- [ ] SSH allowed: `sudo ufw allow ssh`
- [ ] HTTP allowed: `sudo ufw allow 80/tcp`
- [ ] HTTPS allowed: `sudo ufw allow 443/tcp`
- [ ] Port 8080 allowed (if not using nginx): `sudo ufw allow 8080/tcp`
- [ ] Firewall status verified: `sudo ufw status`

---

## Post-Deployment Checklist

### SSL/HTTPS Setup (If Using Domain)
- [ ] Domain DNS A record points to VPS IP
- [ ] DNS propagation verified (wait 5-60 minutes)
- [ ] Certbot installed: `sudo apt-get install certbot python3-certbot-nginx`
- [ ] SSL certificate obtained: `sudo certbot --nginx -d api.albunyaan.tube`
- [ ] Auto-renewal tested: `sudo certbot renew --dry-run`
- [ ] HTTPS endpoint works: `curl https://api.albunyaan.tube/actuator/health`

### Android App Configuration
- [ ] Updated API URL in `android/app/build.gradle.kts`
- [ ] New debug APK built: `cd android && ./gradlew assembleDebug`
- [ ] APK installed on test device: `adb install -r app/build/outputs/apk/debug/app-debug.apk`
- [ ] App connects to backend successfully
- [ ] Categories load in app
- [ ] Videos load in app
- [ ] Playback works

### Monitoring & Logging
- [ ] Log rotation configured: `/etc/logrotate.d/albunyaan`
- [ ] Logs accessible: `tail -f /opt/albunyaan/logs/app.log`
- [ ] Systemd logs accessible: `sudo journalctl -u albunyaan-backend -f`
- [ ] Health check cron job set up (optional)

### Security Hardening
- [ ] Firewall enabled and configured
- [ ] SSH key-based authentication enabled
- [ ] Root login disabled (optional but recommended)
- [ ] Automatic security updates enabled: `sudo dpkg-reconfigure unattended-upgrades`
- [ ] Fail2ban installed (optional): `sudo apt-get install fail2ban`
- [ ] Default admin password changed in Firebase Console

---

## Production Optimization Checklist

### Performance
- [ ] Redis installed for caching (optional): `sudo apt-get install redis-server`
- [ ] Backend configured to use Redis cache
- [ ] Nginx caching enabled (optional)
- [ ] Java heap size optimized for server RAM
- [ ] Firestore indexes deployed: `firebase deploy --only firestore:indexes`

### Backup & Recovery
- [ ] Firestore backup schedule configured
- [ ] Service account key backed up securely (NOT in git)
- [ ] Environment variables documented
- [ ] Deployment runbook created

### Monitoring
- [ ] Prometheus metrics enabled (optional)
- [ ] Grafana dashboard set up (optional)
- [ ] Uptime monitoring configured (e.g., UptimeRobot, Pingdom)
- [ ] Error alerting configured

---

## Troubleshooting Checklist

If deployment fails, check:

### Backend Not Starting
- [ ] Java 17 installed: `java -version`
- [ ] JAR file exists: `ls -l /opt/albunyaan/backend-0.0.1-SNAPSHOT.jar`
- [ ] Environment file exists: `cat /opt/albunyaan/.env`
- [ ] Firebase credentials exist: `ls -l /opt/albunyaan/firebase-service-account.json`
- [ ] Service logs checked: `sudo journalctl -u albunyaan-backend -n 50`
- [ ] Port 8080 not in use: `sudo lsof -i :8080`

### Data Not Loading
- [ ] Seed process completed: `grep "seeding completed" /opt/albunyaan/logs/app.log`
- [ ] Firestore accessible from VPS (not blocked by firewall)
- [ ] YouTube API key valid and has quota
- [ ] Firebase project ID correct in .env file

### Android App Can't Connect
- [ ] Backend accessible from public internet: `curl http://PUBLIC_IP:8080/actuator/health`
- [ ] Firewall allows connections: `sudo ufw status`
- [ ] Correct IP/domain in Android app config
- [ ] CORS enabled in backend (already configured)
- [ ] Network reachable from mobile device

---

## Maintenance Checklist (Weekly/Monthly)

### Weekly
- [ ] Check disk space: `df -h`
- [ ] Review error logs: `grep -i error /opt/albunyaan/logs/error.log`
- [ ] Verify service is running: `sudo systemctl status albunyaan-backend`
- [ ] Test health endpoint

### Monthly
- [ ] Update system packages: `sudo apt-get update && sudo apt-get upgrade`
- [ ] Review Firestore usage and costs in Firebase Console
- [ ] Check YouTube API quota usage in Google Cloud Console
- [ ] Rotate logs manually if needed
- [ ] Review and update Firestore security rules
- [ ] Test backup/restore procedure

---

## Quick Commands Reference

```bash
# Check service status
sudo systemctl status albunyaan-backend

# View live logs
tail -f /opt/albunyaan/logs/app.log

# Restart service
sudo systemctl restart albunyaan-backend

# Test endpoints
curl http://localhost:8080/actuator/health
curl http://localhost:8080/api/v1/categories

# Check disk space
df -h

# Check memory usage
free -h

# Check Java process
ps aux | grep java

# Update deployment
./scripts/deploy-to-vps.sh YOUR_SERVER_IP
```

---

## Rollback Procedure

If deployment fails and you need to rollback:

1. **Stop new service:**
   ```bash
   ssh root@YOUR_SERVER_IP 'sudo systemctl stop albunyaan-backend'
   ```

2. **Restore previous JAR** (if backed up):
   ```bash
   ssh root@YOUR_SERVER_IP 'mv /opt/albunyaan/backend-0.0.1-SNAPSHOT.jar.bak /opt/albunyaan/backend-0.0.1-SNAPSHOT.jar'
   ```

3. **Restart service:**
   ```bash
   ssh root@YOUR_SERVER_IP 'sudo systemctl start albunyaan-backend'
   ```

4. **Verify:**
   ```bash
   curl http://YOUR_SERVER_IP:8080/actuator/health
   ```

---

## Support Resources

- **Full Documentation:** [docs/deployment/VPS_DEPLOYMENT.md](VPS_DEPLOYMENT.md)
- **Quick Reference:** [DEPLOYMENT.md](../../DEPLOYMENT.md)
- **Troubleshooting:** See VPS_DEPLOYMENT.md → Troubleshooting section
- **Scripts:** [scripts/](../../scripts/)

---

**Last Updated:** 2025-11-04
