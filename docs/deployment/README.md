# Deployment Documentation

Complete deployment guides for Albunyaan Tube backend.

---

## Available Deployment Options

### 1. VPS Deployment (Recommended for Getting Started) ✅

Deploy to any VPS (DigitalOcean, Linode, AWS EC2, etc.) with automated scripts.

**Documentation:** [VPS_DEPLOYMENT.md](VPS_DEPLOYMENT.md)

**Quick Start:**
```bash
./scripts/deploy-to-vps.sh YOUR_SERVER_IP --seed
```

**Best For:**
- Full control over infrastructure
- Cost-effective for small to medium traffic
- Learning and experimentation
- Custom configurations

---

### 2. Google Cloud Run (Serverless)

Deploy to Google Cloud Platform's serverless container platform.

**Best For:**
- Auto-scaling workloads
- Pay-per-use pricing
- Zero server management
- Native Firebase integration

**Quick Start:**
```bash
gcloud run deploy albunyaan-tube-backend \
  --image gcr.io/albunyaan-tube/backend:latest \
  --platform managed
```

See [VPS_DEPLOYMENT.md](VPS_DEPLOYMENT.md) for full Cloud Run instructions.

---

### 3. Docker Compose on VPS

Deploy using Docker containers for easier management.

**Best For:**
- Containerized environments
- Easier updates and rollbacks
- Consistent across environments

**Quick Start:**
```bash
docker-compose -f docker-compose.prod.yml up -d
```

---

### 4. Kubernetes (Advanced)

Deploy to Kubernetes cluster (GKE, EKS, AKS).

**Best For:**
- High-traffic applications
- Multi-region deployments
- Advanced orchestration needs
- Microservices architecture

---

## Quick Reference

### Initial Deployment (with seeded data)

```bash
# VPS
./scripts/deploy-to-vps.sh YOUR_SERVER_IP --seed

# Cloud Run
gcloud run deploy albunyaan-tube-backend \
  --image gcr.io/albunyaan-tube/backend:latest \
  --set-env-vars "SPRING_PROFILES_ACTIVE=real-seed"
```

### Updates (no seeding)

```bash
# VPS quick update
./scripts/update-vps.sh YOUR_SERVER_IP

# VPS full redeploy
./scripts/deploy-to-vps.sh YOUR_SERVER_IP

# Cloud Run
gcloud run deploy albunyaan-tube-backend \
  --image gcr.io/albunyaan-tube/backend:latest
```

---

## Documentation Files

| File | Description |
|------|-------------|
| [VPS_DEPLOYMENT.md](VPS_DEPLOYMENT.md) | Complete VPS deployment guide |
| [DEPLOYMENT_CHECKLIST.md](DEPLOYMENT_CHECKLIST.md) | Step-by-step checklist |
| [../../DEPLOYMENT.md](../../DEPLOYMENT.md) | Quick reference guide |

---

## Deployment Scripts

| Script | Purpose |
|--------|---------|
| [scripts/deploy-to-vps.sh](../../scripts/deploy-to-vps.sh) | Full VPS deployment (first time or complete redeploy) |
| [scripts/update-vps.sh](../../scripts/update-vps.sh) | Quick update (for code changes) |
| [scripts/setup-nginx.sh](../../scripts/setup-nginx.sh) | Set up nginx reverse proxy |

---

## Prerequisites

All deployment options require:

- ✅ Firebase production project with Firestore enabled
- ✅ Firebase service account JSON key
- ✅ YouTube Data API v3 key
- ✅ Backend builds successfully locally

---

## Recommended Deployment Flow

### For First-Time Deployment:

1. **Choose deployment target** (VPS recommended for simplicity)
2. **Prepare Firebase:** Create production project, download service account key
3. **Get API keys:** YouTube Data API v3
4. **Run deployment script:** `./scripts/deploy-to-vps.sh SERVER_IP --seed`
5. **Set up SSL:** Use Let's Encrypt for HTTPS
6. **Configure Android app:** Update API URL
7. **Test:** Verify all endpoints work

### For Updates:

1. **Make code changes** locally
2. **Test locally** with `./gradlew test`
3. **Deploy update:** `./scripts/update-vps.sh SERVER_IP`
4. **Verify:** Test health endpoint and key APIs

---

## Environment Variables Required

All deployments need these environment variables:

```bash
FIREBASE_PROJECT_ID=albunyaan-tube
YOUTUBE_API_KEY=your_youtube_api_key
GOOGLE_APPLICATION_CREDENTIALS=/path/to/firebase-service-account.json
```

For seeding (first time only):
```bash
SPRING_PROFILES_ACTIVE=real-seed
```

---

## Post-Deployment Steps

1. **SSL/HTTPS Setup** (recommended)
   ```bash
   sudo certbot --nginx -d api.albunyaan.tube
   ```

2. **Update Android App**
   - Update API URL in `android/app/build.gradle.kts`
   - Rebuild APK

3. **Monitor Logs**
   ```bash
   tail -f /opt/albunyaan/logs/app.log  # VPS
   gcloud run logs tail albunyaan-tube-backend  # Cloud Run
   ```

4. **Set Up Monitoring** (optional)
   - Uptime monitoring (UptimeRobot, Pingdom)
   - Error tracking (Sentry)
   - Performance monitoring (Prometheus + Grafana)

---

## Security Considerations

- ✅ **Never commit** `firebase-service-account.json` to Git
- ✅ **Use environment variables** for secrets
- ✅ **Enable firewall** on VPS
- ✅ **Set up SSL/HTTPS** for production
- ✅ **Change default admin password** after first deployment
- ✅ **Keep system updated** with security patches

---

## Cost Estimates

### VPS Options:

| Provider | Plan | RAM | Storage | Monthly Cost |
|----------|------|-----|---------|--------------|
| DigitalOcean | Basic Droplet | 1GB | 25GB | $6 |
| DigitalOcean | Standard | 2GB | 50GB | $12 |
| Linode | Nanode | 1GB | 25GB | $5 |
| AWS EC2 | t3.micro | 1GB | 8GB | ~$8 |
| Hetzner | CX11 | 2GB | 20GB | €3.79 (~$4) |

### Serverless Options:

| Provider | Pricing Model | Free Tier | Est. Monthly |
|----------|---------------|-----------|--------------|
| Cloud Run | Per request + time | 2M requests/month | $0-10 (low traffic) |
| AWS Lambda | Per request + duration | 1M requests/month | $0-15 (low traffic) |

### Additional Costs:

- **Firebase:** Free tier sufficient for small apps, $25-100/month for production
- **YouTube API:** Free (10,000 units/day), paid plans available

---

## Troubleshooting

See [VPS_DEPLOYMENT.md → Troubleshooting](VPS_DEPLOYMENT.md#troubleshooting) for:

- Service won't start
- Cannot connect from Android app
- Seed data not loading
- SSL certificate issues
- High memory usage

---

## Support

For issues or questions:

1. Check [DEPLOYMENT_CHECKLIST.md](DEPLOYMENT_CHECKLIST.md)
2. Review [VPS_DEPLOYMENT.md](VPS_DEPLOYMENT.md) troubleshooting section
3. Check logs on your deployment
4. Review Firebase Console for Firestore issues

---

**Last Updated:** 2025-11-04
