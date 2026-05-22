package com.albunyaan.tube.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PlaylistVideoLinkDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(links: List<PlaylistVideoLink>)

    /**
     * Replaces the full set of links for a playlist with the supplied list.
     * Used when a playlist's first page is re-fetched so videos removed from
     * the playlist drop out of the Me feed on the next refresh.
     */
    @androidx.room.Transaction
    suspend fun replaceForPlaylist(playlistId: String, links: List<PlaylistVideoLink>) {
        deleteForPlaylist(playlistId)
        if (links.isNotEmpty()) upsertAll(links)
    }

    @Query("DELETE FROM playlist_video_link WHERE playlistId = :playlistId")
    suspend fun deleteForPlaylist(playlistId: String)

    /**
     * Drop link rows whose playlist is no longer active in saved_playlists
     * (either soft-deleted or wiped during an account switch). The read
     * paths already filter to the current user's `deleted=0` saved set
     * via [SavedPlaylistDao.observeAll], so these orphans are not user-
     * visible — but they pin metadata rows in [ChannelVideoCache] under
     * the same exemption logic and accumulate over time. Called on every
     * playlist unsave and inside the account-switch transaction.
     */
    @Query(
        """DELETE FROM playlist_video_link
           WHERE playlistId NOT IN (
               SELECT playlistId FROM saved_playlists WHERE deleted = 0
           )"""
    )
    suspend fun pruneOrphans()
}
