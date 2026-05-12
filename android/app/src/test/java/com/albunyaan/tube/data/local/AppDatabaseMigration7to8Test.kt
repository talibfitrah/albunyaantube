package com.albunyaan.tube.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Plan D / T18 — verifies v7 → v8 migration preserves existing rows and
 * adds the four sync columns with correct defaults plus two new tables.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class AppDatabaseMigration7to8Test {

    private val DB = "migration-7-8-test.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        listOf(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate_7_to_8_preserves_existing_rows_and_adds_defaults() {
        helper.createDatabase(DB, 7).use { v7 ->
            v7.execSQL(
                "INSERT INTO subscribed_channels (channelId, channelUrl, name, avatarUrl, subscribedAt) " +
                    "VALUES ('UC1', 'https://yt/UC1', 'Name1', NULL, 1000)"
            )
            v7.execSQL(
                "INSERT INTO saved_playlists (playlistId, playlistUrl, name, thumbnailUrl, uploaderName, savedAt) " +
                    "VALUES ('PL1', 'https://yt/PL1', 'PL Name', NULL, NULL, 2000)"
            )
            v7.execSQL(
                "INSERT INTO favorite_videos (videoId, title, channelName, thumbnailUrl, durationSeconds, addedAt) " +
                    "VALUES ('V1', 'Title', 'Channel', NULL, 90, 3000)"
            )
        }

        helper.runMigrationsAndValidate(DB, 8, true, MIGRATION_7_8).use { v8 ->
            v8.query(
                "SELECT channelId, user_id, updated_at, deleted, dirty FROM subscribed_channels WHERE channelId='UC1'"
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("UC1", c.getString(0))
                assertEquals("",   c.getString(1))      // user_id default
                assertEquals(0L,   c.getLong(2))        // updated_at default
                assertEquals(0,    c.getInt(3))         // deleted default
                assertEquals(0,    c.getInt(4))         // dirty default
            }
            v8.query("SELECT user_id, deleted, dirty FROM saved_playlists WHERE playlistId='PL1'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("", c.getString(0))
                assertEquals(0,  c.getInt(1))
                assertEquals(0,  c.getInt(2))
            }
            v8.query("SELECT user_id, deleted, dirty FROM favorite_videos WHERE videoId='V1'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("", c.getString(0))
                assertEquals(0,  c.getInt(1))
                assertEquals(0,  c.getInt(2))
            }
            v8.query("SELECT COUNT(*) FROM sync_state").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(0, c.getInt(0))
            }
            v8.query("SELECT COUNT(*) FROM account_binding").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(0, c.getInt(0))
            }
        }
    }

    @Test
    fun migrate_7_to_8_allows_writing_into_new_columns_and_tables() {
        helper.createDatabase(DB, 7).use { /* empty */ }
        helper.runMigrationsAndValidate(DB, 8, true, MIGRATION_7_8).use { v8 ->
            v8.execSQL(
                "INSERT INTO subscribed_channels (channelId, channelUrl, name, avatarUrl, subscribedAt, user_id, updated_at, deleted, dirty) " +
                    "VALUES ('UC2', 'u', 'n', NULL, 1, 'uid-x', 99, 1, 1)"
            )
            v8.execSQL("INSERT INTO sync_state VALUES ('subscriptions', 'uid-x', 99, 100)")
            v8.execSQL("INSERT INTO account_binding VALUES ('uid-x', 50, 1)")

            v8.query("SELECT deleted FROM subscribed_channels WHERE channelId='UC2'").use { c ->
                assertTrue(c.moveToFirst()); assertEquals(1, c.getInt(0))
            }
            v8.query("SELECT last_cursor FROM sync_state WHERE entityType='subscriptions'").use { c ->
                assertTrue(c.moveToFirst()); assertEquals(99L, c.getLong(0))
            }
            v8.query("SELECT initial_merge_done FROM account_binding WHERE user_id='uid-x'").use { c ->
                assertTrue(c.moveToFirst()); assertEquals(1, c.getInt(0))
            }
        }
    }
}
