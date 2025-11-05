#!/bin/bash
set -e

# AlBunyaan Tube - Development Environment Setup
# This script automates first-time setup for developers

echo "🚀 AlBunyaan Tube - Development Setup"
echo "======================================"
echo ""

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Function to print colored output
print_success() { echo -e "${GREEN}✓${NC} $1"; }
print_error() { echo -e "${RED}✗${NC} $1"; }
print_info() { echo -e "${YELLOW}ℹ${NC} $1"; }

# Function to check if command exists
command_exists() {
    command -v "$1" >/dev/null 2>&1
}

# Track if any errors occurred
ERRORS=0

echo "Step 1: Checking prerequisites..."
echo "-----------------------------------"

# Check Java
if command_exists java; then
    JAVA_VERSION=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}')
    if [[ "$JAVA_VERSION" == 17.* ]] || [[ "$JAVA_VERSION" == 1.8.* ]]; then
        print_success "Java $JAVA_VERSION installed"
    else
        print_error "Java 17 required, found $JAVA_VERSION"
        ERRORS=$((ERRORS + 1))
    fi
else
    print_error "Java not found. Install JDK 17: https://adoptium.net/"
    ERRORS=$((ERRORS + 1))
fi

# Check Node.js
if command_exists node; then
    NODE_VERSION=$(node -v | cut -d'v' -f2)
    MAJOR_VERSION=$(echo "$NODE_VERSION" | cut -d'.' -f1)
    if [ "$MAJOR_VERSION" -ge 18 ]; then
        print_success "Node.js $NODE_VERSION installed"
    else
        print_error "Node.js 18+ required, found $NODE_VERSION"
        ERRORS=$((ERRORS + 1))
    fi
else
    print_error "Node.js not found. Install from: https://nodejs.org/"
    ERRORS=$((ERRORS + 1))
fi

# Check npm
if command_exists npm; then
    NPM_VERSION=$(npm -v)
    print_success "npm $NPM_VERSION installed"
else
    print_error "npm not found"
    ERRORS=$((ERRORS + 1))
fi

# Check Git
if command_exists git; then
    GIT_VERSION=$(git --version | awk '{print $3}')
    print_success "Git $GIT_VERSION installed"
else
    print_error "Git not found"
    ERRORS=$((ERRORS + 1))
fi

# Check Android SDK (optional for backend/frontend devs)
if [ -n "$ANDROID_HOME" ] && [ -d "$ANDROID_HOME" ]; then
    print_success "Android SDK found at $ANDROID_HOME"
    ANDROID_AVAILABLE=true
else
    print_info "Android SDK not found (only needed for Android development)"
    ANDROID_AVAILABLE=false
fi

# Check Firebase CLI (optional)
if command_exists firebase; then
    FIREBASE_VERSION=$(firebase --version)
    print_success "Firebase CLI $FIREBASE_VERSION installed"
else
    print_info "Firebase CLI not found (optional for emulator management)"
fi

echo ""

# Exit if critical errors
if [ $ERRORS -gt 0 ]; then
    print_error "$ERRORS critical dependencies missing. Please install them and run again."
    exit 1
fi

echo "Step 2: Setting up environment..."
echo "-----------------------------------"

# Create .env file if it doesn't exist
if [ ! -f .env ]; then
    print_info "Creating .env file from template..."
    cp .env.example .env
    print_success ".env file created"
    print_info "⚠️  Edit .env and add your YOUTUBE_API_KEY"
else
    print_info ".env file already exists"
fi

echo ""

echo "Step 3: Backend setup..."
echo "-----------------------------------"

cd backend

# Make gradlew executable
if [ -f gradlew ]; then
    chmod +x gradlew
    print_success "Made gradlew executable"
fi

# Download dependencies
print_info "Downloading backend dependencies (this may take a few minutes)..."
./gradlew dependencies --no-daemon > /dev/null 2>&1
print_success "Backend dependencies downloaded"

# Build backend
print_info "Building backend..."
./gradlew build -x test --no-daemon > /dev/null 2>&1
print_success "Backend built successfully"

cd ..

echo ""

echo "Step 4: Frontend setup..."
echo "-----------------------------------"

cd frontend

# Install dependencies
print_info "Installing frontend dependencies (this may take a few minutes)..."
npm ci --silent
print_success "Frontend dependencies installed"

# Build frontend
print_info "Building frontend..."
npm run build > /dev/null 2>&1
print_success "Frontend built successfully"

cd ..

echo ""

echo "Step 5: Android setup..."
echo "-----------------------------------"

if [ "$ANDROID_AVAILABLE" = true ]; then
    cd android

    chmod +x gradlew
    print_success "Made Android gradlew executable"

    print_info "Downloading Android dependencies..."
    ./gradlew dependencies --no-daemon > /dev/null 2>&1
    print_success "Android dependencies downloaded"

    cd ..
else
    print_info "Skipping Android setup (ANDROID_HOME not set)"
fi

echo ""

echo "Step 6: Firebase setup..."
echo "-----------------------------------"

# Check if firebase.json exists
if [ -f firebase.json ]; then
    print_success "Firebase configuration found"

    # Create firebase-data directory for emulator persistence
    mkdir -p firebase-data
    print_success "Created firebase-data directory for emulator"
else
    print_info "firebase.json not found (will be needed for emulators)"
fi

echo ""

echo "════════════════════════════════════"
echo "✅ Setup complete!"
echo "════════════════════════════════════"
echo ""
echo "Next steps:"
echo "🔧 Run services manually"
echo ""
echo "   Backend:"
echo "   cd backend && ./gradlew bootRun"
echo ""
echo "   Frontend:"
echo "   cd frontend && npm run dev"
echo ""

if [ "$ANDROID_AVAILABLE" = true ]; then
    echo "   Android:"
    echo "   Open android/ in Android Studio"
    echo ""
fi

echo "📚 Documentation:"
echo "   - Quick Start: docs/QUICK_START_PARALLEL_WORK.md"
echo "   - Platform Guides: docs/PLATFORM_GUIDES.md"
echo ""

print_info "Remember to edit .env and add your YOUTUBE_API_KEY!"
