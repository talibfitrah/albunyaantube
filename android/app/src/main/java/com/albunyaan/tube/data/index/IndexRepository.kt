package com.albunyaan.tube.data.index

import android.util.Log
import com.albunyaan.tube.data.source.api.IndexApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class IndexRepository @Inject constructor(
    private val api: IndexApi,
    @Named("applicationScope") private val appScope: CoroutineScope,
) {

    fun indexChannelStreams(channelId: String, items: List<StreamIndexItem>) {
        if (items.isEmpty()) return
        appScope.launch {
            runCatching {
                val response = api.indexStreams(IndexStreamsRequest("CHANNEL", channelId, items))
                if (!response.isSuccessful) {
                    Log.w(TAG, "Channel stream indexing failed: channel=$channelId code=${response.code()} items=${items.size}")
                }
            }.onFailure { e ->
                Log.w(TAG, "Channel stream indexing failed: channel=$channelId items=${items.size}", e)
            }
        }
    }

    fun indexPlaylistStreams(playlistId: String, items: List<StreamIndexItem>) {
        if (items.isEmpty()) return
        appScope.launch {
            runCatching {
                val response = api.indexStreams(IndexStreamsRequest("PLAYLIST", playlistId, items))
                if (!response.isSuccessful) {
                    Log.w(TAG, "Playlist stream indexing failed: playlist=$playlistId code=${response.code()} items=${items.size}")
                }
            }.onFailure { e ->
                Log.w(TAG, "Playlist stream indexing failed: playlist=$playlistId items=${items.size}", e)
            }
        }
    }

    companion object {
        private const val TAG = "IndexRepository"
    }
}
