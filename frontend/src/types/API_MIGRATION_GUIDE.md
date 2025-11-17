# API Type Migration Guide

This guide explains how to migrate from hand-rolled types to generated OpenAPI types.

## Overview

As of P1-T2, all API DTOs are auto-generated from `docs/architecture/api-specification.yaml`.

**Architecture Pattern**: OpenAPI DTOs are **transport types only**. UI/domain models stay separate and are fed via mappers.

**DO NOT** manually edit:
- `frontend/src/generated/api/schema.ts` (regenerated via `npm run generate:api`)
- `android/app/src/main/java/com/albunyaan/tube/data/model/api/` (regenerated via `./gradlew generateKotlinDtos`)

**DO** create mapper functions when API DTOs differ from UI needs.

## Frontend Migration Pattern

### Step 1: Import from generated types

**Before:**
```typescript
// Old hand-rolled type
interface CategoryResponse {
  id: string;
  name: string;
  parentCategoryId: string | null;
  // ...
}
```

**After:**
```typescript
// Use generated type from api.ts (re-export wrapper)
import type { Category } from '@/types/api'

// OR import directly from generated schema
import type { components } from '@/generated/api/schema'
type Category = components['schemas']['Category']
```

### Step 2: Keep UI/View Models Separate

Generated DTOs represent the API contract. You may need separate UI models for display logic.

**Example:**
```typescript
import type { Category } from '@/types/api'

// UI-specific model (not from API)
export interface CategoryOption {
  id: string;
  slug: string;
  label: string;
  subcategories?: CategoryOption[];
}

// Mapper function: DTO → UI model
function toCategoryOption(cat: Category): CategoryOption {
  return {
    id: cat.id,
    slug: cat.slug ?? cat.id,
    label: cat.name,
    subcategories: []
  }
}

export async function fetchAllCategories(): Promise<CategoryOption[]> {
  const categories = await authorizedJsonFetch<Category[]>('/api/admin/categories')
  return buildHierarchy(categories.map(toCategoryOption))
}
```

### Step 3: Use Operation Types for Request/Response

**Before:**
```typescript
function searchChannels(query: string): Promise<any[]>
```

**After:**
```typescript
import type { YoutubeSearchChannelsParams } from '@/types/api'

async function searchChannels(params: YoutubeSearchChannelsParams) {
  // params is strongly typed: { query: string }
  const response = await fetch(`/api/admin/youtube/search/channels?query=${params.query}`)
  return response.json()
}
```

### Step 4: Migrate Service-by-Service

Don't try to migrate everything at once. Pick one service (e.g., `categories.ts`) and:

1. Replace interface imports with generated types
2. Keep mapper functions for UI models
3. Verify tests still pass
4. Remove unused hand-rolled types when migration is complete

## Android Migration Pattern

### Step 1: Use Generated DTOs in Retrofit

**Before:**
```kotlin
// Hand-rolled DTO
data class Category(
    val id: String,
    val name: String,
    val slug: String? = null
)
```

**After:**
```kotlin
// Use generated DTO from com.albunyaan.tube.data.model.api.models
import com.albunyaan.tube.data.model.api.models.Category

interface ApiService {
    @GET("/v1/categories")
    suspend fun getCategories(): List<Category>
}
```

### Step 2: Map to Domain/UI Models

**Example:**
```kotlin
import com.albunyaan.tube.data.model.api.models.Category as ApiCategory

// Domain model for UI
data class CategoryUiModel(
    val id: String,
    val name: String,
    val slug: String
)

// Mapper extension
fun ApiCategory.toUiModel() = CategoryUiModel(
    id = this.id,
    name = this.name,
    slug = this.slug ?: this.id
)

// In Repository
class CategoryRepository(private val api: ApiService) {
    suspend fun getCategories(): List<CategoryUiModel> {
        return api.getCategories().map { it.toUiModel() }
    }
}
```

## Checklist for Migration

### Frontend
- [ ] Remove hand-rolled DTO from `frontend/src/types/*.ts`
- [ ] Replace with imports from `@/types/api`
- [ ] Keep UI/view models separate (not from API)
- [ ] Add mapper functions (DTO → UI model)
- [ ] Update tests to use generated types
- [ ] Verify `npm test` and `npm run build` pass

### Android
- [ ] Remove hand-rolled DTO from `android/.../data/model/*.kt`
- [ ] Replace with imports from `com.albunyaan.tube.data.model.api.models.*`
- [ ] Add mapper extensions (DTO → Domain/UI model)
- [ ] Update Retrofit service interfaces
- [ ] Update Repository layer
- [ ] Verify `./gradlew test` passes

## Regenerating DTOs

### Frontend
```bash
cd frontend
npm run generate:api
```

### Android/Kotlin
```bash
cd backend
./gradlew generateKotlinDtos
```

### Both
```bash
./scripts/generate-openapi-dtos.sh
```

## Benefits

1. **Single Source of Truth**: API spec is the canonical contract
2. **Type Safety**: Compile-time errors if API changes
3. **No Manual Sync**: Regenerate on spec changes
4. **Reduced Duplication**: No hand-rolled DTOs to maintain
5. **CI Integration**: Auto-generated before builds

## Gotchas

- **Nullable fields**: OpenAPI `nullable: true` → TypeScript `| null`, Kotlin `?`
- **Enums**: Uppercase by default (can configure in generator)
- **Date fields**: ISO 8601 strings (not Date objects)
- **Nested objects**: Flattened by default (can configure depth)
- **oneOf/anyOf**: Union types in TS, sealed classes in Kotlin

## Troubleshooting

**"Property does not exist on type"**
→ Check if the field is defined in `api-specification.yaml`. If missing, add it to the spec and regenerate.

**"Cannot find module '@/generated/api/schema'"**
→ Run `npm run generate:api` first.

**"Package com.albunyaan.tube.data.model.api.models does not exist"**
→ Run `./gradlew generateKotlinDtos` first.

**"Generated types are wrong"**
→ Check the OpenAPI spec. The generator is correct; the spec may be outdated.
