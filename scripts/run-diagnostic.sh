#!/bin/bash
set -e

SERVER="72.60.179.47"
USER="root"

echo "=== Running Firestore Diagnostic ==="
echo "1. Copying JAR..."
cd "$(dirname "$0")/../backend"
JAR=$(find build/libs -name "*.jar" -type f | head -1)
scp "$JAR" "${USER}@${SERVER}:/opt/albunyaan/backend.jar"

echo "2. Running diagnostic on server..."
ssh "${USER}@${SERVER}" << 'EOF'
cd /opt/albunyaan
source .env
sudo systemctl stop albunyaan-backend

# Run the diagnostic
java -jar backend.jar \
    --spring.profiles.active=diagnostic \
    --server.port=8080 \
    --spring.config.location=file:/opt/albunyaan/application-prod.yml

# Restart backend
sudo systemctl start albunyaan-backend
EOF

echo "Done!"
