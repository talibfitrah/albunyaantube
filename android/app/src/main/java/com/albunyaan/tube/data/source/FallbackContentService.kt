package com.albunyaan.tube.data.source

import android.util.Log
import com.albunyaan.tube.data.filters.FilterState
import com.albunyaan.tube.data.model.ContentItem
import com.albunyaan.tube.data.model.ContentType
import com.albunyaan.tube.data.model.CursorResponse

class FallbackContentService(
    private val primary: ContentService,
    private val fallback: ContentService
) : ContentService {
    override suspend fun fetchContent(
        type: ContentType,
        cursor: String?,
        pageSize: Int,
        filters: FilterState
    ): CursorResponse = try {
        Log.d(TAG, "Trying primary backend for type=$type")
        primary.fetchContent(type, cursor, pageSize, filters).also {
            Log.d(TAG, "✅ Primary backend SUCCESS: returned ${it.items.size} items for type=$type")
        }
    } catch (e: Throwable) {
        Log.e(TAG, "❌ Primary backend FAILED for type=$type: ${e.message}", e)
        Log.d(TAG, "Falling back to fake content service for type=$type")
        fallback.fetchContent(type, cursor, pageSize, filters).also {
            Log.d(TAG, "Fallback returned ${it.items.size} items for type=$type")
        }
    }

    override suspend fun search(query: String, type: String?, limit: Int): List<ContentItem> = try {
        Log.d(TAG, "Trying primary backend for search: query=$query")
        primary.search(query, type, limit).also {
            Log.d(TAG, "✅ Primary search SUCCESS: returned ${it.size} items")
        }
    } catch (e: Throwable) {
        Log.e(TAG, "❌ Primary search FAILED: ${e.message}", e)
        Log.d(TAG, "Falling back to fake search")
        fallback.search(query, type, limit).also {
            Log.d(TAG, "Fallback search returned ${it.size} items")
        }
    }

    override suspend fun fetchCategories(): List<com.albunyaan.tube.ui.categories.Category> = try {
        Log.d(TAG, "Trying primary backend for categories")
        primary.fetchCategories().also {
            Log.d(TAG, "✅ Primary categories SUCCESS: returned ${it.size} categories")
        }
    } catch (e: Throwable) {
        Log.e(TAG, "❌ Primary categories FAILED: ${e.message}", e)
        Log.d(TAG, "Falling back to fake categories")
        fallback.fetchCategories().also {
            Log.d(TAG, "Fallback categories returned ${it.size} categories")
        }
    }

    override suspend fun fetchSubcategories(parentId: String): List<com.albunyaan.tube.ui.categories.Category> = try {
        Log.d(TAG, "Trying primary backend for subcategories: parentId=$parentId")
        primary.fetchSubcategories(parentId).also {
            Log.d(TAG, "✅ Primary subcategories SUCCESS: returned ${it.size} subcategories")
        }
    } catch (e: Throwable) {
        Log.e(TAG, "❌ Primary subcategories FAILED: ${e.message}", e)
        Log.d(TAG, "Falling back to fake subcategories")
        fallback.fetchSubcategories(parentId).also {
            Log.d(TAG, "Fallback subcategories returned ${it.size} subcategories")
        }
    }

    companion object {
        private const val TAG = "FallbackContentService"
    }
}
