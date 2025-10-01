package com.albunyaan.tube

import android.content.Context
import com.albunyaan.tube.data.filters.FilterManager
import com.albunyaan.tube.data.paging.ContentPagingRepository
import com.albunyaan.tube.data.paging.DefaultContentPagingRepository
import com.albunyaan.tube.data.source.ContentService
import com.albunyaan.tube.data.source.FakeContentService

object ServiceLocator {

    private lateinit var appContext: Context

    private val filterManager: FilterManager by lazy { FilterManager() }
    private val contentService: ContentService by lazy { FakeContentService() }
    private val pagingRepository: ContentPagingRepository by lazy { DefaultContentPagingRepository(contentService) }

    fun init(context: Context) {
        if (!::appContext.isInitialized) {
            appContext = context.applicationContext
        }
    }

    fun provideFilterManager(): FilterManager = filterManager

    fun provideContentRepository(): ContentPagingRepository = pagingRepository
}
