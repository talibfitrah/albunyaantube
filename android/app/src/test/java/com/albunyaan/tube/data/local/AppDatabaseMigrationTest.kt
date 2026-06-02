package com.albunyaan.tube.data.local

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * JVM unit test for [MIGRATION_6_7].
 *
 * Tests that upgrading from DB version 6 (me-tab schema) to version 7 correctly
 * adds the `followed_channels` table required by the Shorts player feature
 * (ANDROID-SHORTS-01), while preserving all existing v6 data.
 *
 * ### Why not [androidx.room.testing.MigrationTestHelper]?
 *
 * `MigrationTestHelper` needs the exported schema JSON on the test classpath
 * via `InstrumentationRegistry.getInstrumentation().context.assets`, which
 * implies an instrumented (androidTest) run. Running this under JVM +
 * Robolectric is substantially faster and keeps the migration covered by
 * `./gradlew :app:testDebugUnitTest`.
 *
 * We exercise the migration's SQL directly by:
 *
 *  1. Creating a file-backed v6 database via an SQLite helper that sets up
 *     the full v6 schema (all 5 me-tab tables + indices).
 *  2. Seeding a favorite_videos row to confirm data survives the upgrade.
 *  3. Closing, reopening with [AppDatabase] + [MIGRATION_6_7].
 *  4. Asserting the DB opens without error, the seeded row is preserved, and
 *     the `followed_channels` table exists and accepts inserts.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class AppDatabaseMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dbName = "migration-test-${System.nanoTime()}.db"
    private val dbFile: File = context.getDatabasePath(dbName)

    @Before
    fun setup() {
        dbFile.parentFile?.mkdirs()
        if (dbFile.exists()) dbFile.delete()
    }

    @After
    fun tearDown() {
        if (dbFile.exists()) dbFile.delete()
    }

    @Test
    fun migration_6_to_7_addsFollowedChannelsTableAndPreservesExistingData() {
        // --- Step 1: Create a v6 database using a raw SQLite helper so Room
        // doesn't try to validate against v7 during setup.
        val v6Factory = FrameworkSQLiteOpenHelperFactory()
        val v6Config = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration
            .builder(context)
            .name(dbFile.absolutePath)
            .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(6) {
                override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    // v6 schema: all 5 me-tab tables (mirrors schemas/…/6.json).
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `favorite_videos` " +
                            "(`videoId` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                            "`channelName` TEXT NOT NULL, `thumbnailUrl` TEXT, " +
                            "`durationSeconds` INTEGER NOT NULL, " +
                            "`addedAt` INTEGER NOT NULL, PRIMARY KEY(`videoId`))"
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `subscribed_channels` " +
                            "(`channelId` TEXT NOT NULL, `channelUrl` TEXT NOT NULL, " +
                            "`name` TEXT NOT NULL, `avatarUrl` TEXT, " +
                            "`subscribedAt` INTEGER NOT NULL, PRIMARY KEY(`channelId`))"
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `saved_playlists` " +
                            "(`playlistId` TEXT NOT NULL, `playlistUrl` TEXT NOT NULL, " +
                            "`name` TEXT NOT NULL, `thumbnailUrl` TEXT, " +
                            "`uploaderName` TEXT, `savedAt` INTEGER NOT NULL, " +
                            "PRIMARY KEY(`playlistId`))"
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `channel_video_cache` " +
                            "(`videoId` TEXT NOT NULL, `channelId` TEXT NOT NULL, " +
                            "`channelName` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                            "`thumbnailUrl` TEXT, `durationSeconds` INTEGER, " +
                            "`viewCount` INTEGER, `uploadedAt` INTEGER, " +
                            "`isShort` INTEGER NOT NULL, `fetchedAt` INTEGER NOT NULL, " +
                            "PRIMARY KEY(`videoId`))"
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_channel_video_cache_channelId` " +
                            "ON `channel_video_cache` (`channelId`)"
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_channel_video_cache_uploadedAt` " +
                            "ON `channel_video_cache` (`uploadedAt`)"
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `channel_feed_refresh_state` " +
                            "(`channelId` TEXT NOT NULL, `lastSuccessfulFetchAt` INTEGER NOT NULL, " +
                            "`lastAttemptAt` INTEGER NOT NULL, `lastErrorMessage` TEXT, " +
                            "`etag` TEXT, `lastModified` TEXT, " +
                            "`consecutiveErrorCount` INTEGER NOT NULL, " +
                            "`consecutiveEmptyCount` INTEGER NOT NULL, " +
                            "`backoffUntilMs` INTEGER, `deepPageUrl` TEXT, " +
                            "`deepPageCookiesJson` TEXT, PRIMARY KEY(`channelId`))"
                    )
                    // Room identity marker at v6 (from schemas/…/6.json identityHash).
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS room_master_table " +
                            "(id INTEGER PRIMARY KEY, identity_hash TEXT)"
                    )
                    db.execSQL(
                        "INSERT OR REPLACE INTO room_master_table " +
                            "(id, identity_hash) VALUES (42, " +
                            "'1751aa73b80717be0bc60c6bde429c2a')"
                    )
                    db.execSQL("PRAGMA user_version = 6")
                }

                override fun onUpgrade(
                    db: androidx.sqlite.db.SupportSQLiteDatabase,
                    oldVersion: Int,
                    newVersion: Int
                ) { /* no-op */ }
            })
            .build()

        val v6Helper = v6Factory.create(v6Config)
        v6Helper.writableDatabase.use { db ->
            // Seed a favorite so we can verify it survives the migration.
            db.execSQL(
                "INSERT INTO favorite_videos " +
                    "(videoId, title, channelName, thumbnailUrl, durationSeconds, addedAt) " +
                    "VALUES ('v1', 'Test Video', 'Test Channel', NULL, 60, 1000)"
            )
        }
        v6Helper.close()

        // --- Step 2: Reopen with the real AppDatabase + the migration chain up
        // to the current DB version. AppDatabase is now at v9 (SYNC-CURSOR-PERSIST-01
        // added last_doc_id), so the chain must include MIGRATION_7_8 +
        // MIGRATION_8_9 — both are additive ALTER ADD COLUMN steps and leave
        // the v6→v7 assertions below intact.
        val roomDb = Room.databaseBuilder(context, AppDatabase::class.java, dbFile.absolutePath)
            .addMigrations(MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11)
            .allowMainThreadQueries()
            .build()

        try {
            // Trigger open + migration.
            val favCursor = roomDb.query("SELECT videoId FROM favorite_videos", emptyArray())
            favCursor.use {
                assertTrue("favorite_videos row must survive upgrade", it.moveToFirst())
                assertFalse("only one seeded row expected", it.moveToNext())
            }

            // The new table must exist.
            val tblCursor = roomDb.query(
                "SELECT name FROM sqlite_master WHERE type='table' AND name='followed_channels'",
                emptyArray()
            )
            tblCursor.use {
                assertTrue("followed_channels table must exist after MIGRATION_6_7", it.moveToFirst())
            }

            // Sanity: DAO works end-to-end on the migrated DB.
            roomDb.openHelper.writableDatabase.execSQL(
                "INSERT INTO followed_channels (channelId, title, avatarUrl, followedAt) " +
                    "VALUES ('UCtest123', 'Test Channel', NULL, 9999)"
            )
            val countCursor = roomDb.query("SELECT COUNT(*) FROM followed_channels", emptyArray())
            countCursor.use {
                assertTrue(it.moveToFirst())
                assertTrue("insert into migrated followed_channels must succeed", it.getInt(0) == 1)
            }
        } finally {
            roomDb.close()
        }
    }
}
