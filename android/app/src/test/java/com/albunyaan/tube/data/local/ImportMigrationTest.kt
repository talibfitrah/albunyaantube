package com.albunyaan.tube.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ANDROID-IMPORT-01 — verifies v10 → v11 migration adds:
 *  - approval_status TEXT NOT NULL DEFAULT 'APPROVED'
 *  - source TEXT (nullable)
 *  - imported_at INTEGER (nullable)
 *
 * to subscribed_channels, saved_playlists, and favorite_videos.
 *
 * Uses [MigrationTestHelper] + [RobolectricTestRunner] (JVM-runnable),
 * mirroring the pattern established in [AppDatabaseMigration8to9Test].
 * `validate = true` in [MigrationTestHelper.runMigrationsAndValidate] cross-
 * checks the resulting schema against the exported 11.json asset.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class ImportMigrationTest {

    private val DB = "migration-10-11-test.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        listOf(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    // ─── subscribed_channels ─────────────────────────────────────────────────

    @Test
    fun migrate_10_to_11_subscribed_channels_gets_approval_status_default() {
        helper.createDatabase(DB, 10).use { db ->
            db.execSQL(
                "INSERT INTO subscribed_channels " +
                    "(channelId, channelUrl, name, avatarUrl, subscribedAt, " +
                    " user_id, updated_at, deleted, dirty) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf("UCtest1", "https://yt.com/c/test1", "Test Chan", null,
                    1000L, "", 0L, 0, 0)
            )
        }

        helper.runMigrationsAndValidate(DB, 11, true, MIGRATION_10_11).use { db ->
            db.query(
                "SELECT approval_status, source, imported_at " +
                    "FROM subscribed_channels WHERE channelId='UCtest1'"
            ).use { c ->
                assertEquals(1, c.count)
                c.moveToFirst()
                assertEquals("APPROVED", c.getString(0))
                assertNull(c.getString(1))   // source nullable → NULL
                val importedAtIdx = c.getColumnIndexOrThrow("imported_at")
                assert(c.isNull(importedAtIdx)) { "imported_at must be NULL for pre-existing row" }
            }
        }
    }

    // ─── saved_playlists ──────────────────────────────────────────────────────

    @Test
    fun migrate_10_to_11_saved_playlists_gets_approval_status_default() {
        helper.createDatabase(DB, 10).use { db ->
            db.execSQL(
                "INSERT INTO saved_playlists " +
                    "(playlistId, playlistUrl, name, thumbnailUrl, uploaderName, " +
                    " savedAt, user_id, updated_at, deleted, dirty) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf("PLtest1", "https://yt.com/playlist?list=PLtest1",
                    "Test Playlist", null, null, 2000L, "", 0L, 0, 0)
            )
        }

        helper.runMigrationsAndValidate(DB, 11, true, MIGRATION_10_11).use { db ->
            db.query(
                "SELECT approval_status, source, imported_at " +
                    "FROM saved_playlists WHERE playlistId='PLtest1'"
            ).use { c ->
                assertEquals(1, c.count)
                c.moveToFirst()
                assertEquals("APPROVED", c.getString(0))
                assertNull(c.getString(1))
                assert(c.isNull(c.getColumnIndexOrThrow("imported_at"))) {
                    "imported_at must be NULL for pre-existing row"
                }
            }
        }
    }

    // ─── favorite_videos ──────────────────────────────────────────────────────

    @Test
    fun migrate_10_to_11_favorite_videos_gets_approval_status_default() {
        helper.createDatabase(DB, 10).use { db ->
            db.execSQL(
                "INSERT INTO favorite_videos " +
                    "(videoId, title, channelName, thumbnailUrl, durationSeconds, " +
                    " addedAt, user_id, updated_at, deleted, dirty) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf("vid1", "Test Video", "Test Channel", null,
                    120, 3000L, "", 0L, 0, 0)
            )
        }

        helper.runMigrationsAndValidate(DB, 11, true, MIGRATION_10_11).use { db ->
            db.query(
                "SELECT approval_status, source, imported_at " +
                    "FROM favorite_videos WHERE videoId='vid1'"
            ).use { c ->
                assertEquals(1, c.count)
                c.moveToFirst()
                assertEquals("APPROVED", c.getString(0))
                assertNull(c.getString(1))
                assert(c.isNull(c.getColumnIndexOrThrow("imported_at"))) {
                    "imported_at must be NULL for pre-existing row"
                }
            }
        }
    }
}
