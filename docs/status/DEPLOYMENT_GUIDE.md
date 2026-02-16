# FitrahTube Deployment Guide

Production deployment for a two-machine setup: a **host machine** (public, port 443 only) and a **VPS** (private network, outbound internet only).

```
Browser ──HTTPS:443──▶ Host Machine ──HTTP:80──▶ VPS (private)
                       (reverse proxy)          (backend + frontend)
```

---

## Prerequisites

| Machine | Requirement |
|---------|-------------|
| **VPS** | Ubuntu/Debian, Java 17+, Node.js 18+, Git, Nginx |
| **Host machine** | Ubuntu/Debian, Nginx, SSL certificate |
| **Your laptop** | SSH access to host machine |

### Access pattern

```
Your laptop ──SSH──▶ Host machine ──SSH──▶ VPS
```

---

## Part 1: VPS Setup (one-time)

SSH into the VPS (through the host machine):

```bash
ssh youruser@host-machine-ip
ssh albunyaan@VPS-INTERNAL-IP
```

### 1.1 Install system dependencies

```bash
sudo apt update
sudo apt install -y openjdk-17-jre-headless nginx git curl

# Install Node.js 20 (for building the frontend)
curl -fsSL https://deb.nodesource.com/setup_20.x | sudo -E bash -
sudo apt install -y nodejs
```

### 1.2 Create the application user and directories

```bash
sudo useradd -r -m -s /bin/bash albunyaan
sudo mkdir -p /opt/albunyaan/logs
sudo mkdir -p /var/www/fitrahtube
sudo chown -R albunyaan:albunyaan /opt/albunyaan
sudo chown -R albunyaan:albunyaan /var/www/fitrahtube
```

### 1.3 Clone the repository

```bash
sudo -u albunyaan git clone https://github.com/talibfitrah/albunyaantube.git /opt/albunyaan/repo
```

### 1.4 Create the backend environment file

```bash
sudo nano /opt/albunyaan/.env
```

Paste and fill in your real values:

```bash
# Firebase
FIREBASE_SERVICE_ACCOUNT_PATH=/opt/albunyaan/firebase-service-account.json
FIREBASE_PROJECT_ID=your-firebase-project-id

# Security - CHANGE THESE
DOWNLOAD_TOKEN_SECRET_KEY=generate-a-long-random-string-here

# CORS - your public domain
APP_SECURITY_CORS_ALLOWED_ORIGINS=https://yourdomain.com

# Optional: environment tag for metrics
ENVIRONMENT=prod
```

### 1.5 Upload the Firebase service account key

On your laptop, copy the key through both hops:

```bash
scp firebase-service-account.json youruser@host-machine-ip:/tmp/
```

Then from the host machine:

```bash
scp /tmp/firebase-service-account.json albunyaan@VPS-INTERNAL-IP:/opt/albunyaan/
rm /tmp/firebase-service-account.json
```

On the VPS, lock down permissions:

```bash
sudo chmod 600 /opt/albunyaan/firebase-service-account.json
sudo chown albunyaan:albunyaan /opt/albunyaan/firebase-service-account.json
```

### 1.6 Build and install the backend

```bash
sudo -u albunyaan bash -c '
  cd /opt/albunyaan/repo/backend
  ./gradlew clean build -x test
  cp build/libs/tube-0.0.1-SNAPSHOT.jar /opt/albunyaan/backend.jar
'
```

### 1.7 Install the backend systemd service

```bash
sudo cp /opt/albunyaan/repo/backend/scripts/systemd/albunyaan-backend.service \
        /etc/systemd/system/albunyaan-backend.service
sudo systemctl daemon-reload
sudo systemctl enable albunyaan-backend
sudo systemctl start albunyaan-backend
```

Verify it's running:

```bash
sudo systemctl status albunyaan-backend
curl -s http://localhost:8080/actuator/health
```

You should see `{"status":"UP"}`.

### 1.8 Build and install the frontend

```bash
sudo -u albunyaan bash -c '
  cd /opt/albunyaan/repo/frontend
  cp .env.example .env
'
```

Edit the frontend `.env`:

```bash
sudo -u albunyaan nano /opt/albunyaan/repo/frontend/.env
```

Set:

```
VITE_FIREBASE_API_KEY=your-real-api-key
VITE_FIREBASE_AUTH_DOMAIN=your-project.firebaseapp.com
VITE_FIREBASE_PROJECT_ID=your-project-id
VITE_FIREBASE_STORAGE_BUCKET=your-project.appspot.com
VITE_FIREBASE_MESSAGING_SENDER_ID=your-sender-id
VITE_FIREBASE_APP_ID=your-app-id
VITE_API_BASE_URL=https://yourdomain.com
```

Build and deploy:

```bash
sudo -u albunyaan bash -c '
  cd /opt/albunyaan/repo/frontend
  npm ci
  npm run build
  cp -r dist/* /var/www/fitrahtube/
'
```

### 1.9 Configure Nginx on the VPS

```bash
sudo nano /etc/nginx/sites-available/fitrahtube
```

Paste:

```nginx
server {
    listen 80;
    server_name _;

    # Frontend (static files)
    root /var/www/fitrahtube;
    index index.html;

    # Gzip compression
    gzip on;
    gzip_types text/plain text/css application/json application/javascript text/xml;
    gzip_min_length 1000;

    # Cache static assets (JS/CSS have hashes in filenames, safe to cache long)
    location /assets/ {
        expires 1y;
        add_header Cache-Control "public, immutable";
    }

    # SPA fallback - all routes serve index.html
    location / {
        try_files $uri $uri/ /index.html;
    }

    # Proxy API requests to the backend
    location /api/ {
        proxy_pass http://localhost:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_read_timeout 60s;
    }

    # Proxy actuator endpoints (health checks, metrics)
    location /actuator/ {
        proxy_pass http://localhost:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        # Restrict to internal network only
        allow 10.0.0.0/8;
        allow 172.16.0.0/12;
        allow 192.168.0.0/16;
        allow 127.0.0.1;
        deny all;
    }
}
```

Enable and start:

```bash
sudo ln -sf /etc/nginx/sites-available/fitrahtube /etc/nginx/sites-enabled/
sudo rm -f /etc/nginx/sites-enabled/default
sudo nginx -t
sudo systemctl restart nginx
sudo systemctl enable nginx
```

Test from the VPS itself:

```bash
curl -s http://localhost/ | head -5
curl -s http://localhost/api/v1/categories | head -20
```

---

## Part 2: Host Machine Setup (one-time)

SSH into the host machine:

```bash
ssh youruser@host-machine-ip
```

### 2.1 Install Nginx

```bash
sudo apt update
sudo apt install -y nginx
```

### 2.2 Configure Nginx as HTTPS reverse proxy

```bash
sudo nano /etc/nginx/sites-available/fitrahtube
```

Paste (replace `yourdomain.com`, `VPS-INTERNAL-IP`, and cert paths):

```nginx
server {
    listen 443 ssl;
    server_name yourdomain.com;

    ssl_certificate     /path/to/your/fullchain.pem;
    ssl_certificate_key /path/to/your/privkey.pem;

    # Modern SSL settings
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_ciphers HIGH:!aNULL:!MD5;
    ssl_prefer_server_ciphers on;

    # Forward everything to the VPS
    location / {
        proxy_pass http://VPS-INTERNAL-IP:80;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto https;
        proxy_read_timeout 60s;
    }
}
```

### 2.3 SSL certificate

Since only port 443 is open, use the DNS challenge:

```bash
sudo apt install -y certbot
sudo certbot certonly --manual --preferred-challenges dns -d yourdomain.com
```

Follow the prompts to add a DNS TXT record. Once verified, update the Nginx config with the cert paths certbot gives you (usually `/etc/letsencrypt/live/yourdomain.com/fullchain.pem` and `privkey.pem`).

If you already have certs from your hosting provider, just point to those files instead.

### 2.4 Enable and start

```bash
sudo ln -sf /etc/nginx/sites-available/fitrahtube /etc/nginx/sites-enabled/
sudo rm -f /etc/nginx/sites-enabled/default
sudo nginx -t
sudo systemctl restart nginx
sudo systemctl enable nginx
```

### 2.5 Verify end-to-end

From your laptop browser, visit `https://yourdomain.com`. You should see the FitrahTube Admin login page.

---

## Updating the Application

SSH all the way to the VPS:

```bash
ssh youruser@host-machine-ip
ssh albunyaan@VPS-INTERNAL-IP
```

### Update frontend only

```bash
cd /opt/albunyaan/repo
git pull
cd frontend && npm ci && npm run build
cp -r dist/* /var/www/fitrahtube/
```

No restart needed.

### Update backend only

```bash
cd /opt/albunyaan/repo
git pull
cd backend && ./gradlew clean build -x test
cp build/libs/tube-0.0.1-SNAPSHOT.jar /opt/albunyaan/backend.jar
sudo systemctl restart albunyaan-backend
```

### Update both

```bash
cd /opt/albunyaan/repo
git pull

# Backend
cd backend && ./gradlew clean build -x test
cp build/libs/tube-0.0.1-SNAPSHOT.jar /opt/albunyaan/backend.jar
sudo systemctl restart albunyaan-backend

# Frontend
cd ../frontend && npm ci && npm run build
cp -r dist/* /var/www/fitrahtube/
```

---

## Monitoring & Troubleshooting

### Check service status

```bash
sudo systemctl status albunyaan-backend
sudo systemctl status nginx
```

### View backend logs

```bash
# Live log stream
tail -f /opt/albunyaan/logs/app.log

# Error log
tail -f /opt/albunyaan/logs/error.log
```

### Health check

```bash
curl http://localhost:8080/actuator/health
```

### Restart services

```bash
# Backend
sudo systemctl restart albunyaan-backend

# Nginx (VPS)
sudo systemctl restart nginx
```

### Common issues

| Problem | Fix |
|---------|-----|
| Backend won't start | Check `journalctl -u albunyaan-backend -n 50` and `/opt/albunyaan/logs/error.log` |
| Frontend shows blank page | Check browser console for errors; verify `VITE_API_BASE_URL` is correct |
| API calls fail with CORS | Add your domain to `APP_SECURITY_CORS_ALLOWED_ORIGINS` in `/opt/albunyaan/.env` and restart backend |
| 502 Bad Gateway on host | VPS Nginx or backend is down; SSH to VPS and check both services |
| SSL cert expired | Re-run `certbot certonly` on host machine, restart Nginx |
| Java out of memory | Increase `-Xmx512m` in the systemd service file, then `daemon-reload` and restart |

---

## Architecture Summary

```
Internet
   │
   │ HTTPS :443
   ▼
┌─────────────────────┐
│   Host Machine      │
│   Nginx (SSL)       │
│   Reverse proxy     │
└────────┬────────────┘
         │ HTTP :80 (private network)
         ▼
┌─────────────────────────────────────┐
│   VPS                               │
│                                     │
│   Nginx (:80)                       │
│   ├── /          → /var/www/fitrahtube (static files)  │
│   ├── /api/      → localhost:8080 (backend)            │
│   └── /actuator/ → localhost:8080 (internal only)      │
│                                     │
│   Backend (:8080)                   │
│   └── Spring Boot JAR              │
│       └── Firebase / Firestore     │
└─────────────────────────────────────┘
```
