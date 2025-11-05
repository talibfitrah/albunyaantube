#!/bin/bash
set -e

# Usage: ./run-diagnostic.sh <server>
# Environment: REMOTE_USER (default: root)
SERVER="${1:-}"
USER="${REMOTE_USER:-root}"

if [ -z "$SERVER" ]; then
    echo "Usage: $0 <server>"
    echo "Example: $0 192.168.1.100"
    echo "Environment: REMOTE_USER (default: root)"
    exit 1
fi

echo "=== Running Firestore Diagnostic on $SERVER ==="
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
