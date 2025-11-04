#!/bin/bash
set -e

# ============================================================================
# Quick Update Script for VPS Deployment
# ============================================================================
# Updates the backend on an already-deployed VPS
# Usage: ./scripts/update-vps.sh <server-ip>
# ============================================================================

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

REMOTE_USER="${REMOTE_USER:-root}"
REMOTE_HOST="$1"
DEPLOY_DIR="/opt/albunyaan"

if [ -z "$REMOTE_HOST" ]; then
    echo "Usage: $0 <server-ip>"
    exit 1
fi

echo -e "${GREEN}Quick Update - Albunyaan Tube Backend${NC}"
echo "Target: ${REMOTE_USER}@${REMOTE_HOST}"
echo ""

# Step 1: Build JAR
echo -e "${YELLOW}[1/4] Building new JAR...${NC}"
cd "$(dirname "$0")/../backend"
./gradlew clean bootJar

# Find the JAR file (name may vary)
JAR_FILE=$(find build/libs -name "*.jar" -type f | head -1)
if [ -z "$JAR_FILE" ]; then
    echo -e "${RED}Error: JAR file not found after build${NC}"
    exit 1
fi
JAR_NAME=$(basename "$JAR_FILE")
echo "Found JAR: $JAR_NAME"
echo -e "${GREEN}✓ Build complete${NC}"

# Step 2: Backup current JAR on server
echo -e "${YELLOW}[2/4] Backing up current version...${NC}"
ssh "${REMOTE_USER}@${REMOTE_HOST}" << 'ENDSSH'
    if [ -f /opt/albunyaan/backend.jar ]; then
        cp /opt/albunyaan/backend.jar \
           /opt/albunyaan/backend.jar.bak
        echo "✓ Backup created"
    fi
ENDSSH

# Step 3: Copy new JAR
echo -e "${YELLOW}[3/4] Deploying new version...${NC}"
scp "$JAR_FILE" "${REMOTE_USER}@${REMOTE_HOST}:${DEPLOY_DIR}/backend.jar"
echo -e "${GREEN}✓ Files copied${NC}"

# Step 4: Restart service
echo -e "${YELLOW}[4/4] Restarting service...${NC}"
ssh "${REMOTE_USER}@${REMOTE_HOST}" << 'ENDSSH'
    sudo systemctl restart albunyaan-backend
    sleep 5
    if sudo systemctl is-active --quiet albunyaan-backend; then
        echo "✓ Service restarted successfully"
    else
        echo "✗ Service failed to restart"
        echo "Rolling back..."
        mv /opt/albunyaan/backend.jar.bak \
           /opt/albunyaan/backend.jar
        sudo systemctl restart albunyaan-backend
        exit 1
    fi
ENDSSH

echo -e "${GREEN}✓ Update complete!${NC}"
echo ""
echo "Testing deployment..."
sleep 5

SERVER_IP=$(ssh "${REMOTE_USER}@${REMOTE_HOST}" "curl -s ifconfig.me" 2>/dev/null || echo "$REMOTE_HOST")

if curl -sf "http://${SERVER_IP}:8080/actuator/health" > /dev/null; then
    echo -e "${GREEN}✓ Backend is healthy${NC}"
else
    echo -e "${YELLOW}⚠ Health check failed (may need more time)${NC}"
fi

echo ""
echo "View logs: ssh ${REMOTE_USER}@${REMOTE_HOST} 'tail -f ${DEPLOY_DIR}/logs/app.log'"
