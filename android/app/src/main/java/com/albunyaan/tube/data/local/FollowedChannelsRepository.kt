package com.albunyaan.tube.data.local

import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Interface for managing followed channels.
 *
 * Provides a local "follow" replacement for YouTube's subscribe action so users
 * can track channels without requiring a Google account.
 */
interface FollowedChannelsRepository {
    /**
     * Get all followed channels as a reactive Flow, ordered most-recent first.
     */
    fun getAllFollowed(): Flow<List<FollowedChannel>>

    /**
     * Reactive check for whether a given channel is followed.
     */
    fun isFollowed(channelId: String): Flow<Boolean>

    /**
     * One-shot check for whether a given channel is followed.
     */
    suspend fun isFollowedOnce(channelId: String): Boolean

    /**
     * Toggle the follow state for a channel.
     *
     * @return true if the channel is now followed, false if it was unfollowed.
     */
    suspend fun toggleFollow(
        channelId: String,
        title: String,
        avatarUrl: String?
    ): Boolean
}

/**
 * Default implementation backed by [FollowedChannelDao].
 */
@Singleton
class FollowedChannelsRepositoryImpl @Inject constructor(
    private val dao: FollowedChannelDao
) : FollowedChannelsRepository {

    override fun getAllFollowed(): Flow<List<FollowedChannel>> = dao.getAllFollowed()

    override fun isFollowed(channelId: String): Flow<Boolean> = dao.isFollowed(channelId)

    override suspend fun isFollowedOnce(channelId: String): Boolean =
        dao.isFollowedOnce(channelId)

    override suspend fun toggleFollow(
        channelId: String,
        title: String,
        avatarUrl: String?
    ): Boolean = dao.toggleFollow(FollowedChannel(channelId, title, avatarUrl))
}
