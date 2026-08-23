#!/bin/bash
set -e

# ==============================================================================
# Generate OpenAPI DTOs for all platforms (TypeScript + Kotlin)
# ==============================================================================

echo "🚀 Generating OpenAPI DTOs from api-specification.yaml"
echo ""

# Get the root directory of the project
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# 1. Generate TypeScript DTOs for frontend
echo "📦 Generating TypeScript DTOs for frontend..."
cd "$ROOT_DIR/frontend"
npm run generate:api
echo "✅ TypeScript DTOs generated to: frontend/src/generated/api/schema.ts"
echo ""

# 2. Generate Kotlin DTOs for Android
echo "📦 Generating Kotlin DTOs for Android..."
cd "$ROOT_DIR/backend"
./gradlew generateKotlinDtos --quiet
echo "✅ Kotlin DTOs generated to: android/app/src/main/java/com/albunyaan/tube/data/model/api/models/"
echo ""

# 3. Generate Swift client for iOS
echo "📦 Generating Swift client for iOS..."
# `swift --version`, not `command -v swift` (gate wave-4 V10): /usr/bin/swift is the xcrun shim
# and exists on every Mac even with no usable toolchain, so the guard always passed and `set -e`
# aborted the whole pipeline -- after the TS and Kotlin steps had already run -- instead of
# printing the skip below. Running it is the only check that the toolchain actually resolves.
if swift --version >/dev/null 2>&1; then
    "$ROOT_DIR/ios/scripts/generate-swift-dtos.sh"
else
    echo "⚠️  swift not found — skipping iOS client generation"
fi
echo ""

echo "🎉 All DTOs generated successfully!"
echo ""
echo "⚠️  IMPORTANT: Do not manually edit generated files."
echo "   To regenerate, run: ./scripts/generate-openapi-dtos.sh"
