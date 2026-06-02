package com.albunyaan.tube.data.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.albunyaan.tube.data.local.AppDatabase
import com.albunyaan.tube.data.local.SubscribedChannel
import com.albunyaan.tube.data.local.SavedPlaylist
import com.albunyaan.tube.data.local.FavoriteVideo
import com.albunyaan.tube.data.sync.dto.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response

/**
 * B4 — verifies that approvalStatus/source/importedAt are carried through
 * sync in both directions (pull: server→entity; push: entity→request body)
 * for all three entity types (subscription, playlist, favorite).
 *
 * Pull-null default: when the server omits approvalStatus (null/absent),
 * the entity lands with approvalStatus = "APPROVED" — mirroring the
 * server-side default.
 *
 * Graduation: when the backend flips AWAITING→APPROVED and bumps updatedAt,
 * the pull upsert updates the local row's approvalStatus to APPROVED.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class SyncImportFieldMappingTest {

    private lateinit var db: AppDatabase
    private lateinit var api: FakeSyncApi
    private lateinit var sm: SyncManager

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        api = FakeSyncApi()
        sm = SyncManager(
            api, db,
            db.subscribedChannelDao(), db.savedPlaylistDao(),
            db.favoriteVideoDao(), db.syncStateDao(),
            db.accountBindingDao(), db.playlistVideoLinkDao(),
        )
    }

    @After fun tearDown() = db.close()

    // ── Pull: subscription ────────────────────────────────────────────────

    @Test fun pull_subscription_withImportFields_landsOnEntity() = runTest {
        val dto = SubscriptionSyncDto(
            entityId = "UC_IMPORT", deleted = false, updatedAt = 50L,
            channelUrl = "https://yt.com/c/import", name = "Import Chan",
            avatarUrl = null, subscribedAt = 1L,
            approvalStatus = "AWAITING", source = "YOUTUBE_IMPORT", importedAt = 123L,
        )
        api.pullResponse = { singleSubPage(dto) }

        sm.pullAll("uid")

        val row = db.subscribedChannelDao().getByIdAny("uid", "UC_IMPORT")
        assertNotNull("row must exist after pull", row)
        assertEquals("AWAITING",        row!!.approvalStatus)
        assertEquals("YOUTUBE_IMPORT",  row.source)
        assertEquals(123L,              row.importedAt)
    }

    @Test fun pull_subscription_nullApprovalStatus_defaultsToApproved() = runTest {
        val dto = SubscriptionSyncDto(
            entityId = "UC_NULL", deleted = false, updatedAt = 60L,
            channelUrl = "https://yt.com/c/null", name = "Null Chan",
            avatarUrl = null, subscribedAt = 1L,
            approvalStatus = null, source = null, importedAt = null,
        )
        api.pullResponse = { singleSubPage(dto) }

        sm.pullAll("uid")

        val row = db.subscribedChannelDao().getByIdAny("uid", "UC_NULL")
        assertNotNull(row)
        assertEquals("APPROVED", row!!.approvalStatus)
        assertNull(row.source)
        assertNull(row.importedAt)
    }

    @Test fun pull_subscription_graduation_awaitingBecomesApproved() = runTest {
        // Seed a local AWAITING row at updatedAt=100
        db.subscribedChannelDao().upsert(
            SubscribedChannel(
                channelId = "UC_GRAD", channelUrl = "u", name = "n", avatarUrl = null,
                user_id = "uid", updated_at = 100L,
                approvalStatus = "AWAITING", source = "YOUTUBE_IMPORT", importedAt = 10L,
            )
        )

        // Server flips to APPROVED at updatedAt=200
        val dto = SubscriptionSyncDto(
            entityId = "UC_GRAD", deleted = false, updatedAt = 200L,
            channelUrl = "u", name = "n", avatarUrl = null, subscribedAt = 1L,
            approvalStatus = "APPROVED", source = "YOUTUBE_IMPORT", importedAt = 10L,
        )
        api.pullResponse = { singleSubPage(dto) }

        sm.pullAll("uid")

        val row = db.subscribedChannelDao().getByIdAny("uid", "UC_GRAD")
        assertNotNull(row)
        assertEquals("APPROVED", row!!.approvalStatus)
    }

    // ── Pull: playlist ────────────────────────────────────────────────────

    @Test fun pull_playlist_withImportFields_landsOnEntity() = runTest {
        val dto = PlaylistSyncDto(
            entityId = "PL_IMPORT", deleted = false, updatedAt = 70L,
            playlistUrl = "https://yt.com/playlist?list=PL_IMPORT",
            name = "Import PL", thumbnailUrl = null, uploaderName = null, savedAt = 2L,
            approvalStatus = "AWAITING", source = "YOUTUBE_IMPORT", importedAt = 456L,
        )
        api.pullResponse = { singlePlaylistPage(dto) }

        sm.pullAll("uid")

        val row = db.savedPlaylistDao().getByIdAny("uid", "PL_IMPORT")
        assertNotNull(row)
        assertEquals("AWAITING",        row!!.approvalStatus)
        assertEquals("YOUTUBE_IMPORT",  row.source)
        assertEquals(456L,              row.importedAt)
    }

    @Test fun pull_playlist_nullApprovalStatus_defaultsToApproved() = runTest {
        val dto = PlaylistSyncDto(
            entityId = "PL_NULL", deleted = false, updatedAt = 80L,
            playlistUrl = "https://yt.com/playlist?list=PL_NULL",
            name = "Null PL", thumbnailUrl = null, uploaderName = null, savedAt = 2L,
            approvalStatus = null, source = null, importedAt = null,
        )
        api.pullResponse = { singlePlaylistPage(dto) }

        sm.pullAll("uid")

        val row = db.savedPlaylistDao().getByIdAny("uid", "PL_NULL")
        assertNotNull(row)
        assertEquals("APPROVED", row!!.approvalStatus)
    }

    // ── Pull: favorite ────────────────────────────────────────────────────

    @Test fun pull_favorite_withImportFields_landsOnEntity() = runTest {
        val dto = FavoriteSyncDto(
            entityId = "VID_IMPORT", deleted = false, updatedAt = 90L,
            title = "Import Vid", channelName = "Chan", thumbnailUrl = null,
            durationSeconds = 42, addedAt = 3L,
            approvalStatus = "AWAITING", source = "YOUTUBE_IMPORT", importedAt = 789L,
        )
        api.pullResponse = { singleFavPage(dto) }

        sm.pullAll("uid")

        val row = db.favoriteVideoDao().getByIdAny("uid", "VID_IMPORT")
        assertNotNull(row)
        assertEquals("AWAITING",        row!!.approvalStatus)
        assertEquals("YOUTUBE_IMPORT",  row.source)
        assertEquals(789L,              row.importedAt)
    }

    @Test fun pull_favorite_nullApprovalStatus_defaultsToApproved() = runTest {
        val dto = FavoriteSyncDto(
            entityId = "VID_NULL", deleted = false, updatedAt = 95L,
            title = "Null Vid", channelName = "Chan", thumbnailUrl = null,
            durationSeconds = 10, addedAt = 3L,
            approvalStatus = null, source = null, importedAt = null,
        )
        api.pullResponse = { singleFavPage(dto) }

        sm.pullAll("uid")

        val row = db.favoriteVideoDao().getByIdAny("uid", "VID_NULL")
        assertNotNull(row)
        assertEquals("APPROVED", row!!.approvalStatus)
    }

    // ── Push: subscription ────────────────────────────────────────────────

    @Test fun push_subscription_awaitingEntity_sendsImportFieldsInRequest() = runTest {
        db.subscribedChannelDao().upsert(
            SubscribedChannel(
                channelId = "UC_PUSH", channelUrl = "https://yt.com/c/push",
                name = "Push Chan", avatarUrl = null, user_id = "uid",
                dirty = true, updated_at = 0L,
                approvalStatus = "AWAITING", source = "YOUTUBE_IMPORT", importedAt = 111L,
            )
        )

        var capturedReq: PutSubscriptionRequest? = null
        api.putSubResponse = { id, req ->
            capturedReq = req
            Response.success(
                SubscriptionSyncDto(id, false, 999L, req.channelUrl, req.name,
                    req.avatarUrl, req.subscribedAt,
                    approvalStatus = req.approvalStatus, source = req.source,
                    importedAt = req.importedAt)
            )
        }

        sm.pushDirty("uid")

        assertNotNull("PUT must have been called", capturedReq)
        assertEquals("AWAITING",       capturedReq!!.approvalStatus)
        assertEquals("YOUTUBE_IMPORT", capturedReq!!.source)
        assertEquals(111L,             capturedReq!!.importedAt)
    }

    // ── Push: playlist ────────────────────────────────────────────────────

    @Test fun push_playlist_awaitingEntity_sendsImportFieldsInRequest() = runTest {
        db.savedPlaylistDao().upsert(
            SavedPlaylist(
                playlistId = "PL_PUSH",
                playlistUrl = "https://yt.com/playlist?list=PL_PUSH",
                name = "Push PL", thumbnailUrl = null, uploaderName = null,
                user_id = "uid", dirty = true, updated_at = 0L,
                approvalStatus = "AWAITING", source = "YOUTUBE_IMPORT", importedAt = 222L,
            )
        )

        var capturedReq: PutPlaylistRequest? = null
        api.putPlaylistResponse = { id, req ->
            capturedReq = req
            Response.success(
                PlaylistSyncDto(id, false, 999L, req.playlistUrl, req.name,
                    req.thumbnailUrl, req.uploaderName, req.savedAt,
                    approvalStatus = req.approvalStatus, source = req.source,
                    importedAt = req.importedAt)
            )
        }

        sm.pushDirty("uid")

        assertNotNull("PUT must have been called", capturedReq)
        assertEquals("AWAITING",       capturedReq!!.approvalStatus)
        assertEquals("YOUTUBE_IMPORT", capturedReq!!.source)
        assertEquals(222L,             capturedReq!!.importedAt)
    }

    // ── Push: favorite ────────────────────────────────────────────────────

    @Test fun push_favorite_awaitingEntity_sendsImportFieldsInRequest() = runTest {
        db.favoriteVideoDao().upsertFavorite(
            FavoriteVideo(
                videoId = "VID_PUSH", title = "Push Vid", channelName = "Chan",
                thumbnailUrl = null, durationSeconds = 60,
                user_id = "uid", dirty = true, updated_at = 0L,
                approvalStatus = "AWAITING", source = "YOUTUBE_IMPORT", importedAt = 333L,
            )
        )

        var capturedReq: PutFavoriteRequest? = null
        api.putFavoriteResponse = { id, req ->
            capturedReq = req
            Response.success(
                FavoriteSyncDto(id, false, 999L, req.title, req.channelName,
                    req.thumbnailUrl, req.durationSeconds, req.addedAt,
                    approvalStatus = req.approvalStatus, source = req.source,
                    importedAt = req.importedAt)
            )
        }

        sm.pushDirty("uid")

        assertNotNull("PUT must have been called", capturedReq)
        assertEquals("AWAITING",       capturedReq!!.approvalStatus)
        assertEquals("YOUTUBE_IMPORT", capturedReq!!.source)
        assertEquals(333L,             capturedReq!!.importedAt)
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun singleSubPage(dto: SubscriptionSyncDto) = Response.success(
        SyncResponseDto(
            SyncPageDto(listOf(dto), null),
            SyncPageDto(emptyList(), null),
            SyncPageDto(emptyList(), null),
        )
    )

    private fun singlePlaylistPage(dto: PlaylistSyncDto) = Response.success(
        SyncResponseDto(
            SyncPageDto(emptyList(), null),
            SyncPageDto(listOf(dto), null),
            SyncPageDto(emptyList(), null),
        )
    )

    private fun singleFavPage(dto: FavoriteSyncDto) = Response.success(
        SyncResponseDto(
            SyncPageDto(emptyList(), null),
            SyncPageDto(emptyList(), null),
            SyncPageDto(listOf(dto), null),
        )
    )
}
