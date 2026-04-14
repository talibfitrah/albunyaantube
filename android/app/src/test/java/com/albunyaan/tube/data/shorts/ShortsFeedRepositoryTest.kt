package com.albunyaan.tube.data.shorts

import com.albunyaan.tube.data.filters.FilterState
import com.albunyaan.tube.data.filters.VideoLength
import com.albunyaan.tube.data.model.ContentType
import com.albunyaan.tube.data.model.CursorResponse
import com.albunyaan.tube.data.source.ContentService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify

class ShortsFeedRepositoryTest {

    private val contentService: ContentService = mock {
        onBlocking { fetchContent(any(), anyOrNull(), any(), any()) } doReturn CursorResponse(emptyList(), null)
    }
    private val repo = ShortsFeedRepository(contentService)

    @Test
    fun feedMode_passesShortFilter() = runBlocking {
        repo.loadFeedPage(cursor = null, pageSize = 10)

        val filterCaptor = argumentCaptor<FilterState>()
        val typeCaptor = argumentCaptor<ContentType>()
        verify(contentService).fetchContent(
            typeCaptor.capture(),
            anyOrNull(),
            eq(10),
            filterCaptor.capture()
        )
        assertEquals(ContentType.VIDEOS, typeCaptor.firstValue)
        assertEquals(VideoLength.UNDER_FOUR_MIN, filterCaptor.firstValue.videoLength)
    }
}
