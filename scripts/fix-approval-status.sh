#!/bin/bash
set -e

# ============================================================================
# Fix Approval Status on VPS
# ============================================================================
# Updates all seeded content to have status="APPROVED"
# Usage: ./scripts/fix-approval-status.sh <server-ip>
# ============================================================================

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

REMOTE_USER="${REMOTE_USER:-root}"
REMOTE_HOST="$1"
DEPLOY_DIR="/opt/albunyaan"

if [ -z "$REMOTE_HOST" ]; then
    echo "Usage: $0 <server-ip>"
    exit 1
fi

echo -e "${GREEN}Fix Approval Status - Albunyaan Tube Backend${NC}"
echo "Target: ${REMOTE_USER}@${REMOTE_HOST}"
echo ""

# Step 1: Build JAR (already done in previous step)
echo -e "${YELLOW}[1/4] Checking JAR file...${NC}"
cd "$(dirname "$0")/../backend"
JAR_FILE=$(find build/libs -name "*.jar" -type f | head -1)
if [ -z "$JAR_FILE" ]; then
    echo -e "${RED}Error: JAR file not found. Run: ./gradlew bootJar${NC}"
    exit 1
fi
echo "Found JAR: $(basename "$JAR_FILE")"
echo -e "${GREEN}✓ JAR ready${NC}"

# Step 2: Stop backend service
echo -e "${YELLOW}[2/4] Stopping backend service...${NC}"
ssh "${REMOTE_USER}@${REMOTE_HOST}" "sudo systemctl stop albunyaan-backend"
echo -e "${GREEN}✓ Service stopped${NC}"

# Step 3: Copy new JAR
echo -e "${YELLOW}[3/4] Copying new JAR...${NC}"
scp "$JAR_FILE" "${REMOTE_USER}@${REMOTE_HOST}:${DEPLOY_DIR}/backend.jar"
echo -e "${GREEN}✓ JAR copied${NC}"

# Step 4: Run fix-approval profile
echo -e "${YELLOW}[4/4] Running approval status fix...${NC}"
ssh "${REMOTE_USER}@${REMOTE_HOST}" << 'ENDSSH'
    cd ${DEPLOY_DIR}
    source .env

    echo "Starting fix process..."
    timeout 300 java -jar backend.jar \
        --spring.profiles.active=fix-approval \
        --server.port=8080 \
        --spring.config.location=file:/opt/albunyaan/application-prod.yml \
        2>&1 | tee logs/fix-approval.log || {
        if grep -q "Approval Status Fix Complete" logs/fix-approval.log; then
            echo "✓ Fix completed successfully"
            pkill -f backend.jar
        else
            echo "✗ Fix failed - check logs/fix-approval.log"
            exit 1
        fi
    }

    # Wait for process to exit cleanly
    sleep 3

    # Restart the backend service
    sudo systemctl start albunyaan-backend
    sleep 5

    if sudo systemctl is-active --quiet albunyaan-backend; then
        echo "✓ Backend service restarted"
    else
        echo "✗ Service failed to restart"
        exit 1
    fi
ENDSSH

echo -e "${GREEN}✓ Fix complete!${NC}"
echo ""
echo "Testing API..."
sleep 5

# Test the API
SERVER_IP=$(ssh "${REMOTE_USER}@${REMOTE_HOST}" "curl -s ifconfig.me" 2>/dev/null || echo "$REMOTE_HOST")

CHANNELS=$(curl -sf "http://${SERVER_IP}:8080/api/v1/content/channels?type=CHANNELS&limit=50" 2>/dev/null | jq '. | if type=="object" then .items | length else length end' 2>/dev/null || echo "0")
PLAYLISTS=$(curl -sf "http://${SERVER_IP}:8080/api/v1/content/playlists?type=PLAYLISTS&limit=50" 2>/dev/null | jq '. | if type=="object" then .items | length else length end' 2>/dev/null || echo "0")
VIDEOS=$(curl -sf "http://${SERVER_IP}:8080/api/v1/content/videos?type=VIDEOS&limit=50" 2>/dev/null | jq '. | if type=="object" then .items | length else length end' 2>/dev/null || echo "0")

echo ""
echo -e "${GREEN}✓ Channels: ${CHANNELS}${NC}"
echo -e "${GREEN}✓ Playlists: ${PLAYLISTS}${NC}"
echo -e "${GREEN}✓ Videos: ${VIDEOS}${NC}"
echo ""

if [ "$CHANNELS" -gt 0 ] || [ "$VIDEOS" -gt 0 ]; then
    echo -e "${GREEN}🎉 Success! Content is now visible in the API${NC}"
else
    echo -e "${YELLOW}⚠ Warning: API still returning empty. Check logs:${NC}"
    echo "ssh ${REMOTE_USER}@${REMOTE_HOST} 'cat ${DEPLOY_DIR}/logs/fix-approval.log'"
fi
