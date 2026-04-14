package com.albunyaan.tube.data.local

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Unit tests for [FollowedChannelsRepositoryImpl].
 *
 * Verifies the repository is a thin delegation layer over [FollowedChannelDao]:
 * - Flow-returning methods are forwarded unchanged.
 * - `toggleFollow` builds a [FollowedChannel] with the provided fields and
 *   returns the DAO's new-follow-state boolean.
 *
 * Note: the plan specifies mockk, but this project uses mockito-kotlin
 * (already on the test classpath). mockito-kotlin covers the same intent
 * (delegation verification) without adding a new test dependency.
 */
class FollowedChannelsRepositoryTest {

    private val dao: FollowedChannelDao = mock()
    private val repo = FollowedChannelsRepositoryImpl(dao)

    @Test
    fun `getAllFollowed forwards dao flow`() = runTest {
        val channel = FollowedChannel("UC1", "Name", "avatar.jpg", 1_000L)
        whenever(dao.getAllFollowed()).thenReturn(flowOf(listOf(channel)))

        val result = repo.getAllFollowed()

        assertEquals(listOf(channel), result.let { flow ->
            var captured: List<FollowedChannel> = emptyList()
            flow.collect { captured = it }
            captured
        })
        verify(dao).getAllFollowed()
    }

    @Test
    fun `isFollowed forwards dao flow`() = runTest {
        whenever(dao.isFollowed("UC1")).thenReturn(flowOf(true))

        var captured = false
        repo.isFollowed("UC1").collect { captured = it }

        assertTrue(captured)
        verify(dao).isFollowed("UC1")
    }

    @Test
    fun `isFollowedOnce delegates to dao`() = runTest {
        whenever(dao.isFollowedOnce("UC1")).thenReturn(true)

        val result = repo.isFollowedOnce("UC1")

        assertTrue(result)
        verify(dao).isFollowedOnce("UC1")
    }

    @Test
    fun `toggleFollow delegates to dao with constructed channel and returns true`() = runTest {
        whenever(dao.toggleFollow(any())).thenReturn(true)

        val result = repo.toggleFollow("UC1", "Name", "avatar.jpg")

        assertTrue(result)
        val captor = argumentCaptor<FollowedChannel>()
        verify(dao).toggleFollow(captor.capture())
        val captured = captor.firstValue
        assertEquals("UC1", captured.channelId)
        assertEquals("Name", captured.title)
        assertEquals("avatar.jpg", captured.avatarUrl)
    }

    @Test
    fun `toggleFollow returns false when dao reports unfollow`() = runTest {
        whenever(dao.toggleFollow(any())).thenReturn(false)

        val result = repo.toggleFollow("UC2", "Other", null)

        assertFalse(result)
        val captor = argumentCaptor<FollowedChannel>()
        verify(dao).toggleFollow(captor.capture())
        val captured = captor.firstValue
        assertEquals("UC2", captured.channelId)
        assertEquals("Other", captured.title)
        assertEquals(null, captured.avatarUrl)
    }
}
