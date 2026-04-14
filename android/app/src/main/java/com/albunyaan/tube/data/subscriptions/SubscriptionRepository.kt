package com.albunyaan.tube.data.subscriptions

import com.albunyaan.tube.data.local.SavedPlaylist
import com.albunyaan.tube.data.local.SavedPlaylistDao
import com.albunyaan.tube.data.local.SubscribedChannel
import com.albunyaan.tube.data.local.SubscribedChannelDao
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

@Singleton
class SubscriptionRepository @Inject constructor(
    private val channels: SubscribedChannelDao,
    private val playlists: SavedPlaylistDao,
) {
    fun observeSubscribedChannels(): Flow<List<SubscribedChannel>> = channels.observeAll()

    fun observeSavedPlaylists(): Flow<List<SavedPlaylist>> = playlists.observeAll()

    suspend fun getSubscribedChannels(): List<SubscribedChannel> = channels.getAll()

    fun isChannelSubscribed(id: String): Flow<Boolean> = channels.observeIsSubscribed(id)

    fun isPlaylistSaved(id: String): Flow<Boolean> = playlists.observeIsSaved(id)

    suspend fun subscribe(channel: SubscribedChannel) = channels.upsert(channel)

    suspend fun unsubscribe(channelId: String) = channels.delete(channelId)

    suspend fun savePlaylist(playlist: SavedPlaylist) = playlists.upsert(playlist)

    suspend fun unsavePlaylist(playlistId: String) = playlists.delete(playlistId)
}
