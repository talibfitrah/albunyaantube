#!/bin/bash
set -e

# Simple approval fixer - just copy and run
SERVER="72.60.179.47"
USER="root"

echo "=== Fixing Approval Status ==="
echo "1. Copying JAR..."
cd "$(dirname "$0")/../backend"
JAR=$(find build/libs -name "*.jar" -type f | head -1)
scp "$JAR" "${USER}@${SERVER}:/opt/albunyaan/backend.jar"

echo "2. Running fix on server..."
ssh "${USER}@${SERVER}" << 'EOF'
cd /opt/albunyaan
source .env
sudo systemctl stop albunyaan-backend

# Run the fixer
java -jar backend.jar \
    --spring.profiles.active=fix-approval \
    --server.port=8080 \
    --spring.config.location=file:/opt/albunyaan/application-prod.yml \
    2>&1 | tee logs/fix-approval.log &

# Wait for fix to complete (look for completion message)
for i in {1..60}; do
    if grep -q "Approval Status Fix Complete" logs/fix-approval.log 2>/dev/null; then
        echo "✅ Fix completed!"
        pkill -f "backend.jar.*fix-approval"
        break
    fi
    echo "Waiting for fix to complete... ($i/60)"
    sleep 2
done

# Restart backend
sudo systemctl start albunyaan-backend
sleep 5
sudo systemctl status albunyaan-backend --no-pager
EOF

echo "3. Testing API..."
sleep 5
curl -s "http://${SERVER}:8080/api/v1/content?type=CHANNELS&limit=10" | jq '.items | length'
curl -s "http://${SERVER}:8080/api/v1/content?type=VIDEOS&limit=10" | jq '.items | length'

echo "Done!"
