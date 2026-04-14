package com.albunyaan.tube.data.shorts

import com.albunyaan.tube.data.channel.ChannelDetailRepository
import com.albunyaan.tube.data.channel.ChannelPage
import com.albunyaan.tube.data.channel.ChannelShort
import com.albunyaan.tube.data.channel.Page
import com.albunyaan.tube.data.filters.FilterState
import com.albunyaan.tube.data.filters.VideoLength
import com.albunyaan.tube.data.model.ContentType
import com.albunyaan.tube.data.model.CursorResponse
import com.albunyaan.tube.data.source.ContentService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify

class ShortsFeedRepositoryTest {

    private val contentService: ContentService = mock {
        onBlocking { fetchContent(any(), anyOrNull(), any(), any()) } doReturn CursorResponse(emptyList(), null)
    }
    private val channelDetailRepository: ChannelDetailRepository = mock()
    private val repo = ShortsFeedRepository(contentService, channelDetailRepository)

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

    @Test
    fun channelMode_fetchesFromChannelDetailRepository() = runBlocking {
        val channelShort = ChannelShort(
            id = "short-1",
            title = "Hello",
            thumbnailUrl = "thumb.jpg",
            viewCount = 100,
            durationSeconds = 45,
            publishedTime = "2 days ago"
        )
        org.mockito.kotlin.whenever(
            channelDetailRepository.getShorts(eq("UC1"), anyOrNull())
        ).thenReturn(ChannelPage(items = listOf(channelShort), nextPage = null))

        val result = repo.loadChannelShortsPage(channelId = "UC1", cursor = null, pageSize = 10)

        verify(channelDetailRepository).getShorts(eq("UC1"), isNull())
        assertEquals(1, result.items.size)
        val item = result.items[0]
        assertEquals("short-1", item.id)
        assertEquals("Hello", item.title)
        assertEquals("thumb.jpg", item.thumbnailUrl)
        assertEquals(45, item.durationSeconds)
        // ViewModel fills channel metadata from the header fetch — repo leaves blank.
        assertEquals("", item.channelId)
        assertEquals("", item.channelName)
        assertNull(item.channelAvatarUrl)
        assertNull(result.nextCursor)
    }

    @Test
    fun channelMode_returnsCursorTokenWhenNextPageExists() = runBlocking {
        val nextPage = Page(url = "https://next", id = null, ids = null, cookies = null, body = null)
        org.mockito.kotlin.whenever(
            channelDetailRepository.getShorts(eq("UC1"), anyOrNull())
        ).thenReturn(ChannelPage(items = emptyList(), nextPage = nextPage))

        val first = repo.loadChannelShortsPage(channelId = "UC1", cursor = null)

        assertNotNull("expected a cursor token when nextPage is non-null", first.nextCursor)

        // Feeding the token back should translate to the stored Page.
        org.mockito.kotlin.whenever(
            channelDetailRepository.getShorts(eq("UC1"), eq(nextPage))
        ).thenReturn(ChannelPage(items = emptyList(), nextPage = null))

        val second = repo.loadChannelShortsPage(channelId = "UC1", cursor = first.nextCursor)
        assertNull(second.nextCursor)
        verify(channelDetailRepository).getShorts(eq("UC1"), eq(nextPage))
        Unit
    }

    @Test
    fun channelMode_durationNullDefaultsToZero() = runBlocking {
        val channelShort = ChannelShort(
            id = "short-x",
            title = "t",
            thumbnailUrl = null,
            viewCount = null,
            durationSeconds = null,
            publishedTime = null
        )
        org.mockito.kotlin.whenever(
            channelDetailRepository.getShorts(eq("UC1"), anyOrNull())
        ).thenReturn(ChannelPage(items = listOf(channelShort), nextPage = null))

        val result = repo.loadChannelShortsPage(channelId = "UC1", cursor = null)

        assertEquals(0, result.items[0].durationSeconds)
    }
}
