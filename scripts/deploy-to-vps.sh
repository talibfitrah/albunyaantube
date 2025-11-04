#!/bin/bash
set -e

# ============================================================================
# Albunyaan Tube Backend - VPS Deployment Script
# ============================================================================
# This script automates the deployment of the backend to a VPS server
# Usage: ./scripts/deploy-to-vps.sh <server-ip> [--seed]
# ============================================================================

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Configuration
REMOTE_USER="${REMOTE_USER:-root}"
REMOTE_HOST="$1"
DEPLOY_DIR="/opt/albunyaan"
SERVICE_NAME="albunyaan-backend"
SEED_FLAG="$2"

# Check arguments
if [ -z "$REMOTE_HOST" ]; then
    echo -e "${RED}Error: Server IP/hostname required${NC}"
    echo "Usage: $0 <server-ip> [--seed]"
    echo "Example: $0 192.168.1.100 --seed"
    exit 1
fi

echo -e "${GREEN}============================================${NC}"
echo -e "${GREEN}Albunyaan Tube - VPS Deployment${NC}"
echo -e "${GREEN}============================================${NC}"
echo ""
echo "Target: ${REMOTE_USER}@${REMOTE_HOST}"
echo "Deploy Dir: ${DEPLOY_DIR}"
echo "Seed Data: $([ "$SEED_FLAG" = "--seed" ] && echo "YES" || echo "NO")"
echo ""

# Step 1: Build the JAR locally
echo -e "${YELLOW}[1/7] Building JAR file...${NC}"
cd "$(dirname "$0")/../backend"
./gradlew clean bootJar

if [ ! -f "build/libs/backend-0.0.1-SNAPSHOT.jar" ]; then
    echo -e "${RED}Error: JAR file not found after build${NC}"
    exit 1
fi
echo -e "${GREEN}✓ JAR built successfully${NC}"

# Step 2: Check Firebase credentials
echo -e "${YELLOW}[2/7] Checking Firebase credentials...${NC}"
if [ ! -f "src/main/resources/firebase-service-account.json" ]; then
    echo -e "${RED}Error: firebase-service-account.json not found${NC}"
    echo "Please place your Firebase service account key at:"
    echo "  backend/src/main/resources/firebase-service-account.json"
    exit 1
fi
echo -e "${GREEN}✓ Firebase credentials found${NC}"

# Step 3: Prepare server
echo -e "${YELLOW}[3/7] Preparing VPS server...${NC}"
ssh "${REMOTE_USER}@${REMOTE_HOST}" << 'ENDSSH'
    # Update system
    sudo apt-get update

    # Install Java 17 if not installed
    if ! command -v java &> /dev/null; then
        echo "Installing Java 17..."
        sudo apt-get install -y openjdk-17-jdk
    fi

    # Create deployment directory
    sudo mkdir -p /opt/albunyaan
    sudo chown $USER:$USER /opt/albunyaan

    # Create logs directory
    mkdir -p /opt/albunyaan/logs

    echo "✓ Server prepared"
ENDSSH
echo -e "${GREEN}✓ Server prepared${NC}"

# Step 4: Copy files to server
echo -e "${YELLOW}[4/7] Copying files to server...${NC}"
scp build/libs/backend-0.0.1-SNAPSHOT.jar "${REMOTE_USER}@${REMOTE_HOST}:${DEPLOY_DIR}/"
scp src/main/resources/firebase-service-account.json "${REMOTE_USER}@${REMOTE_HOST}:${DEPLOY_DIR}/"
echo -e "${GREEN}✓ Files copied${NC}"

# Step 5: Check environment variables on server
echo -e "${YELLOW}[5/7] Setting up environment...${NC}"
read -p "Enter your YouTube API Key: " YOUTUBE_API_KEY
read -p "Enter your Firebase Project ID [albunyaan-tube]: " FIREBASE_PROJECT_ID
FIREBASE_PROJECT_ID=${FIREBASE_PROJECT_ID:-albunyaan-tube}

# Create environment file
cat > /tmp/albunyaan.env << EOF
GOOGLE_APPLICATION_CREDENTIALS=${DEPLOY_DIR}/firebase-service-account.json
FIREBASE_PROJECT_ID=${FIREBASE_PROJECT_ID}
YOUTUBE_API_KEY=${YOUTUBE_API_KEY}
FIREBASE_SERVICE_ACCOUNT_PATH=${DEPLOY_DIR}/firebase-service-account.json
EOF

scp /tmp/albunyaan.env "${REMOTE_USER}@${REMOTE_HOST}:${DEPLOY_DIR}/.env"
rm /tmp/albunyaan.env
echo -e "${GREEN}✓ Environment configured${NC}"

# Step 6: Run seed if requested
if [ "$SEED_FLAG" = "--seed" ]; then
    echo -e "${YELLOW}[6/7] Seeding database (this may take a few minutes)...${NC}"
    ssh "${REMOTE_USER}@${REMOTE_HOST}" << ENDSSH
        cd ${DEPLOY_DIR}
        source .env
        echo "Starting seed process..."
        timeout 600 java -jar backend-0.0.1-SNAPSHOT.jar \
            --spring.profiles.active=real-seed \
            --server.port=8080 2>&1 | tee logs/seed.log || {
            if grep -q "Started AlbunyaanTubeApplication" logs/seed.log; then
                echo "✓ Seed completed successfully"
                pkill -f backend-0.0.1-SNAPSHOT.jar
            else
                echo "✗ Seed failed - check logs/seed.log"
                exit 1
            fi
        }
ENDSSH
    echo -e "${GREEN}✓ Database seeded${NC}"
else
    echo -e "${YELLOW}[6/7] Skipping database seed${NC}"
fi

# Step 7: Set up systemd service
echo -e "${YELLOW}[7/7] Setting up systemd service...${NC}"

# Copy systemd service file
cd "$(dirname "$0")"
if [ ! -f "systemd/albunyaan-backend.service" ]; then
    echo -e "${YELLOW}Creating systemd service file...${NC}"
    mkdir -p systemd
    cat > systemd/albunyaan-backend.service << 'EOF'
[Unit]
Description=Albunyaan Tube Backend API
After=network.target

[Service]
Type=simple
User=root
WorkingDirectory=/opt/albunyaan
EnvironmentFile=/opt/albunyaan/.env
ExecStart=/usr/bin/java -Xmx512m -Xms256m -jar /opt/albunyaan/backend-0.0.1-SNAPSHOT.jar --server.port=8080
StandardOutput=append:/opt/albunyaan/logs/app.log
StandardError=append:/opt/albunyaan/logs/error.log
Restart=on-failure
RestartSec=10

[Install]
WantedBy=multi-user.target
EOF
fi

scp systemd/albunyaan-backend.service "${REMOTE_USER}@${REMOTE_HOST}:/tmp/"

ssh "${REMOTE_USER}@${REMOTE_HOST}" << 'ENDSSH'
    # Stop existing service if running
    sudo systemctl stop albunyaan-backend 2>/dev/null || true

    # Install new service
    sudo mv /tmp/albunyaan-backend.service /etc/systemd/system/
    sudo systemctl daemon-reload
    sudo systemctl enable albunyaan-backend
    sudo systemctl start albunyaan-backend

    # Wait for service to start
    sleep 5

    # Check status
    if sudo systemctl is-active --quiet albunyaan-backend; then
        echo "✓ Service started successfully"
    else
        echo "✗ Service failed to start"
        sudo systemctl status albunyaan-backend
        exit 1
    fi
ENDSSH

echo -e "${GREEN}✓ Service configured and started${NC}"

# Final verification
echo ""
echo -e "${GREEN}============================================${NC}"
echo -e "${GREEN}Deployment Complete!${NC}"
echo -e "${GREEN}============================================${NC}"
echo ""
echo "Verifying deployment..."
sleep 10

# Get server IP
SERVER_IP=$(ssh "${REMOTE_USER}@${REMOTE_HOST}" "curl -s ifconfig.me" 2>/dev/null || echo "$REMOTE_HOST")

echo ""
echo "Backend URL: http://${SERVER_IP}:8080"
echo ""
echo "Testing endpoints..."

# Test health endpoint
if curl -sf "http://${SERVER_IP}:8080/actuator/health" > /dev/null; then
    echo -e "${GREEN}✓ Health check: OK${NC}"
else
    echo -e "${YELLOW}⚠ Health check: Not responding yet (may need a few more seconds)${NC}"
fi

# Test categories
CATEGORIES=$(curl -sf "http://${SERVER_IP}:8080/api/v1/categories" 2>/dev/null | jq '. | length' 2>/dev/null || echo "0")
if [ "$CATEGORIES" -gt 0 ]; then
    echo -e "${GREEN}✓ Categories API: ${CATEGORIES} categories found${NC}"
else
    echo -e "${YELLOW}⚠ Categories API: No data yet${NC}"
fi

echo ""
echo "Useful commands:"
echo "  View logs:        ssh ${REMOTE_USER}@${REMOTE_HOST} 'tail -f ${DEPLOY_DIR}/logs/app.log'"
echo "  Service status:   ssh ${REMOTE_USER}@${REMOTE_HOST} 'sudo systemctl status albunyaan-backend'"
echo "  Restart service:  ssh ${REMOTE_USER}@${REMOTE_HOST} 'sudo systemctl restart albunyaan-backend'"
echo "  Stop service:     ssh ${REMOTE_USER}@${REMOTE_HOST} 'sudo systemctl stop albunyaan-backend'"
echo ""
echo -e "${GREEN}Next steps:${NC}"
echo "  1. Set up nginx reverse proxy (see docs/deployment/VPS_DEPLOYMENT.md)"
echo "  2. Configure SSL with Let's Encrypt"
echo "  3. Set up firewall rules"
echo "  4. Update Android app API URL to: http://${SERVER_IP}:8080"
echo ""
