#!/bin/bash

# AlBunyaan Tube - Environment Validation Script
# Checks that all required tools and configurations are present

set -e

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

print_success() { echo -e "${GREEN}✓${NC} $1"; }
print_error() { echo -e "${RED}✗${NC} $1"; }
print_warning() { echo -e "${YELLOW}⚠${NC} $1"; }
print_info() { echo -e "${YELLOW}ℹ${NC} $1"; }

command_exists() {
    command -v "$1" >/dev/null 2>&1
}

echo "🔍 AlBunyaan Tube - Environment Validation"
echo "==========================================="
echo ""

ERRORS=0
WARNINGS=0

# Java validation
echo "Java Environment:"
if command_exists java; then
    JAVA_VERSION=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}')
    MAJOR_VERSION=$(echo "$JAVA_VERSION" | cut -d'.' -f1)

    if [ "$MAJOR_VERSION" -eq 17 ]; then
        print_success "Java $JAVA_VERSION (Required for backend)"
    elif [ "$MAJOR_VERSION" -eq 8 ]; then
        print_warning "Java $JAVA_VERSION (Java 17 recommended)"
        WARNINGS=$((WARNINGS + 1))
    else
        print_error "Java $JAVA_VERSION (Java 17 required)"
        ERRORS=$((ERRORS + 1))
    fi

    if [ -n "$JAVA_HOME" ]; then
        print_success "JAVA_HOME: $JAVA_HOME"
    else
        print_warning "JAVA_HOME not set"
        WARNINGS=$((WARNINGS + 1))
    fi
else
    print_error "Java not installed"
    ERRORS=$((ERRORS + 1))
fi
echo ""

# Node.js validation
echo "Node.js Environment:"
if command_exists node; then
    NODE_VERSION=$(node -v)
    MAJOR_VERSION=$(echo "$NODE_VERSION" | cut -d'v' -f2 | cut -d'.' -f1)

    if [ "$MAJOR_VERSION" -ge 18 ]; then
        print_success "Node.js $NODE_VERSION (Required for frontend)"
    else
        print_error "Node.js $NODE_VERSION (v18+ required)"
        ERRORS=$((ERRORS + 1))
    fi
else
    print_error "Node.js not installed"
    ERRORS=$((ERRORS + 1))
fi

if command_exists npm; then
    NPM_VERSION=$(npm -v)
    print_success "npm $NPM_VERSION"
else
    print_error "npm not installed"
    ERRORS=$((ERRORS + 1))
fi
echo ""

# Git validation
echo "Version Control:"
if command_exists git; then
    GIT_VERSION=$(git --version | awk '{print $3}')
    print_success "Git $GIT_VERSION"

    # Check git config
    GIT_USER=$(git config user.name 2>/dev/null || echo "")
    GIT_EMAIL=$(git config user.email 2>/dev/null || echo "")

    if [ -n "$GIT_USER" ] && [ -n "$GIT_EMAIL" ]; then
        print_success "Git configured: $GIT_USER <$GIT_EMAIL>"
    else
        print_warning "Git user not configured (run: git config --global user.name/user.email)"
        WARNINGS=$((WARNINGS + 1))
    fi
else
    print_error "Git not installed"
    ERRORS=$((ERRORS + 1))
fi
echo ""

# Project structure validation
echo "Project Structure:"
REQUIRED_DIRS=("backend" "frontend" "android" "docs")
for dir in "${REQUIRED_DIRS[@]}"; do
    if [ -d "$dir" ]; then
        print_success "$dir/ directory exists"
    else
        print_error "$dir/ directory missing"
        ERRORS=$((ERRORS + 1))
    fi
done
echo ""

# Environment files validation
echo "Configuration Files:"
if [ -f .env ]; then
    print_success ".env file exists"

    # Check for required variables
    if grep -q "YOUTUBE_API_KEY=" .env; then
        if grep -q "YOUTUBE_API_KEY=your_youtube_api_key_here" .env; then
            print_warning "YOUTUBE_API_KEY not configured in .env"
            WARNINGS=$((WARNINGS + 1))
        else
            print_success "YOUTUBE_API_KEY configured"
        fi
    else
        print_error "YOUTUBE_API_KEY missing from .env"
        ERRORS=$((ERRORS + 1))
    fi
else
    print_error ".env file missing (copy from .env.example)"
    ERRORS=$((ERRORS + 1))
fi

# Gradle wrapper validation
echo "Build Tools:"
if [ -f backend/gradlew ]; then
    if [ -x backend/gradlew ]; then
        print_success "backend/gradlew executable"
    else
        print_warning "backend/gradlew not executable (run: chmod +x backend/gradlew)"
        WARNINGS=$((WARNINGS + 1))
    fi
else
    print_error "backend/gradlew missing"
    ERRORS=$((ERRORS + 1))
fi

if [ -f android/gradlew ]; then
    if [ -x android/gradlew ]; then
        print_success "android/gradlew executable"
    else
        print_warning "android/gradlew not executable (run: chmod +x android/gradlew)"
        WARNINGS=$((WARNINGS + 1))
    fi
else
    print_error "android/gradlew missing"
    ERRORS=$((ERRORS + 1))
fi

if [ -f frontend/package.json ]; then
    print_success "frontend/package.json exists"

    # Check if node_modules exists
    if [ -d frontend/node_modules ]; then
        print_success "frontend/node_modules installed"
    else
        print_warning "frontend/node_modules missing (run: cd frontend && npm ci)"
        WARNINGS=$((WARNINGS + 1))
    fi
else
    print_error "frontend/package.json missing"
    ERRORS=$((ERRORS + 1))
fi
echo ""

# Summary
echo "==========================================="
if [ $ERRORS -eq 0 ] && [ $WARNINGS -eq 0 ]; then
    print_success "All checks passed! ✨"
    echo ""
    echo "You're ready to start development!"
    echo "Start backend: cd backend && ./gradlew bootRun"
    echo "Start frontend: cd frontend && npm run dev"
    exit 0
elif [ $ERRORS -eq 0 ]; then
    print_warning "$WARNINGS warning(s) found"
    echo ""
    echo "You can proceed, but consider fixing the warnings above."
    exit 0
else
    print_error "$ERRORS error(s) and $WARNINGS warning(s) found"
    echo ""
    echo "Please fix the errors above before proceeding."
    echo "Run: ./scripts/setup-dev.sh to install missing dependencies"
    exit 1
fi
