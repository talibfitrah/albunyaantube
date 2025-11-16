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

echo "🎉 All DTOs generated successfully!"
echo ""
echo "⚠️  IMPORTANT: Do not manually edit generated files."
echo "   To regenerate, run: ./scripts/generate-openapi-dtos.sh"
