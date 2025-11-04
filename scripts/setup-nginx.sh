#!/bin/bash
set -e

# ============================================================================
# Nginx Setup Script for Albunyaan Tube Backend
# ============================================================================
# This script sets up nginx as a reverse proxy for the backend
# Usage: Run this on your VPS server
# ============================================================================

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

echo -e "${GREEN}Setting up Nginx reverse proxy...${NC}"

# Install nginx if not installed
if ! command -v nginx &> /dev/null; then
    echo -e "${YELLOW}Installing nginx...${NC}"
    sudo apt-get update
    sudo apt-get install -y nginx
fi

# Copy nginx configuration
echo -e "${YELLOW}Configuring nginx...${NC}"
sudo cp "$(dirname "$0")/nginx/albunyaan-backend.conf" /etc/nginx/sites-available/albunyaan-backend

# Create symlink
sudo ln -sf /etc/nginx/sites-available/albunyaan-backend /etc/nginx/sites-enabled/

# Remove default site if exists
sudo rm -f /etc/nginx/sites-enabled/default

# Test configuration
echo -e "${YELLOW}Testing nginx configuration...${NC}"
sudo nginx -t

# Reload nginx
echo -e "${YELLOW}Reloading nginx...${NC}"
sudo systemctl reload nginx

echo -e "${GREEN}✓ Nginx configured successfully${NC}"
echo ""
echo "Your backend is now accessible via nginx on port 80"
echo "Test with: curl http://localhost/actuator/health"
echo ""
echo "To set up SSL with Let's Encrypt, run:"
echo "  sudo apt-get install certbot python3-certbot-nginx"
echo "  sudo certbot --nginx -d api.albunyaan.tube"
