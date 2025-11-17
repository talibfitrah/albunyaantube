# Android Data Models

## Architecture Pattern

**Rule**: OpenAPI DTOs (in `api/models/`) are **transport types** only. Domain/UI models (in this directory) stay separate and are fed via mappers.

### Directory Structure

```text
data/model/
├── README.md              ← You are here
├── ContentItem.kt         ← Domain model (sealed class for UI)
├── Category.kt            ← Domain model (UI-specific)
├── CursorResponse.kt      ← Domain model (pagination wrapper)
├── api/
│   └── models/           ← Generated DTOs (DO NOT EDIT)
│       ├── ContentItem.kt   ← Generated from OpenAPI spec
│       ├── Category.kt      ← Generated from OpenAPI spec
│       ├── ...              ← Other generated DTOs
└── mappers/
    └── ApiMappers.kt     ← Mapper functions (to be created)
```

### Generated DTOs (`api/models/`)

- **Source**: Auto-generated from `docs/architecture/api-specification.yaml`
- **Command**: `cd backend && ./gradlew generateKotlinDtos`
- **Annotations**: Moshi-annotated for JSON serialization
- **Usage**: Use in Retrofit interfaces **only**
- **DO NOT** manually edit these files — they are regenerated on every build

### Domain Models (this directory)

- **Purpose**: UI/business layer models optimized for app needs
- **Examples**:
  - `ContentItem.kt`: Sealed class for type-safe UI rendering
  - `Category.kt`: UI-specific category representation
  - `CursorResponse.kt`: Pagination wrapper for infinite scroll
- **Usage**: Use in ViewModels, UI, and Repository return types

### Mapping Pattern

Create extension functions to map from API DTOs to domain models:

**Example** (`mappers/ApiMappers.kt`):
```kotlin
import com.albunyaan.tube.data.model.api.models.ContentItem as ApiContentItem
import com.albunyaan.tube.data.model.api.models.Category as ApiCategory

// Map generated DTO to domain model
fun ApiContentItem.toDomain(): ContentItem = when (this) {
    is ApiContentItem.VideoItem -> ContentItem.Video(
        id = this.id,
        title = this.title,
        // ... other fields
    )
    is ApiContentItem.ChannelItem -> ContentItem.Channel(
        id = this.id,
        title = this.title,
        // ... other fields
    )
    is ApiContentItem.PlaylistItem -> ContentItem.Playlist(
        id = this.id,
        title = this.title,
        // ... other fields
    )
}

fun ApiCategory.toDomain(): Category = Category(
    id = this.id,
    name = this.name,
    slug = this.slug ?: this.id,
    parentCategoryId = this.parentCategoryId,
    // ... other fields
)
```

**In Repository**:
```kotlin
import com.albunyaan.tube.data.model.api.models.CursorPageDto
import com.albunyaan.tube.data.model.api.models.ContentItem as ApiContentItem

class ContentRepository(private val api: AlbunyaanApiService) {
    suspend fun getContent(cursor: String?): CursorResponse<ContentItem> {
        // Retrofit returns generated DTO
        val response: CursorPageDto<ApiContentItem> = api.getContent(cursor)

        // Map to domain models for UI
        return CursorResponse(
            items = response.items.map { it.toDomain() },
            nextCursor = response.nextCursor,
            hasMore = response.hasMore
        )
    }
}
```

### When to Create a Domain Model

**Create a domain model** when:
- API DTO shape doesn't match UI needs (e.g., flat structure → nested tree)
- You need computed properties (e.g., `displayTitle`, `isExpired`)
- You need sealed classes for type-safe rendering
- You need to combine multiple API DTOs into one UI model

**Use API DTO directly** when:
- API shape matches UI needs exactly
- No computed fields needed
- Simple POJO with no special behavior

### Regenerating DTOs

**After updating** `docs/architecture/api-specification.yaml`:
```bash
# From repo root
./scripts/generate-openapi-dtos.sh

# Or just Android:
cd backend && ./gradlew generateKotlinDtos
```

**CI automatically regenerates** DTOs before each build.

### See Also

- Frontend migration guide: `frontend/src/types/API_MIGRATION_GUIDE.md`
- Development guide: `docs/status/DEVELOPMENT_GUIDE.md`
- OpenAPI spec: `docs/architecture/api-specification.yaml`
