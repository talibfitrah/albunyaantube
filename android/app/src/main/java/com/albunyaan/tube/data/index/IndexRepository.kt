package com.albunyaan.tube.data.index

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
                api.indexStreams(IndexStreamsRequest("CHANNEL", channelId, items))
            }
        }
    }

    fun indexPlaylistStreams(playlistId: String, items: List<StreamIndexItem>) {
        if (items.isEmpty()) return
        appScope.launch {
            runCatching {
                api.indexStreams(IndexStreamsRequest("PLAYLIST", playlistId, items))
            }
        }
    }
}
