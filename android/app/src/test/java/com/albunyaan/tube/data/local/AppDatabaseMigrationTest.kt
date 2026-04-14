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
 * JVM unit test for [AppDatabase.MIGRATION_1_2].
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
 *  1. Creating a file-backed v1 database via Room with a local `AppDatabaseV1`
 *     stub that matches schema `app/schemas/…/1.json` (favorite_videos only).
 *  2. Seeding a row so we can confirm favorites survive the upgrade.
 *  3. Closing, reopening with [AppDatabase] + [AppDatabase.MIGRATION_1_2].
 *  4. Asserting the DB opens without error, favorites row is preserved, and
 *     the `followed_channels` table exists and accepts inserts.
 *
 * Schema 1.json exists in `app/schemas/…/` so this could alternatively use
 * `MigrationTestHelper` — we chose the direct approach to stay on the JVM
 * test path as documented above.
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
    fun migration_1_to_2_addsFollowedChannelsTableAndPreservesFavorites() {
        // --- Step 1: open at schema v1 by using an SQLite helper that only
        // knows favorite_videos, so Room doesn't try to validate against v2.
        val v1Factory = FrameworkSQLiteOpenHelperFactory()
        val v1Config = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration
            .builder(context)
            .name(dbFile.absolutePath)
            .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    // Schema v1: favorite_videos only (per 1.json).
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `favorite_videos` " +
                            "(`videoId` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                            "`channelName` TEXT NOT NULL, `thumbnailUrl` TEXT, " +
                            "`durationSeconds` INTEGER NOT NULL, " +
                            "`addedAt` INTEGER NOT NULL, PRIMARY KEY(`videoId`))"
                    )
                    // Room's identity marker — populated by Room at v1 creation
                    // time. Use the exact identityHash from schemas/…/1.json so
                    // Room won't complain about an untracked DB on upgrade.
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS room_master_table " +
                            "(id INTEGER PRIMARY KEY, identity_hash TEXT)"
                    )
                    db.execSQL(
                        "INSERT OR REPLACE INTO room_master_table " +
                            "(id, identity_hash) VALUES (42, " +
                            "'c8d80707f46ba7d171331cef89f2fd2a')"
                    )
                    db.execSQL("PRAGMA user_version = 1")
                }

                override fun onUpgrade(
                    db: androidx.sqlite.db.SupportSQLiteDatabase,
                    oldVersion: Int,
                    newVersion: Int
                ) { /* no-op */ }
            })
            .build()

        val v1Helper = v1Factory.create(v1Config)
        v1Helper.writableDatabase.use { db ->
            // Seed a favorite so we can verify it survives the migration.
            db.execSQL(
                "INSERT INTO favorite_videos " +
                    "(videoId, title, channelName, thumbnailUrl, durationSeconds, addedAt) " +
                    "VALUES ('v1', 'Test', 'Channel', NULL, 30, 123)"
            )
        }
        v1Helper.close()

        // --- Step 2: reopen with the real AppDatabase + MIGRATION_1_2.
        val roomDb = Room.databaseBuilder(context, AppDatabase::class.java, dbFile.absolutePath)
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()

        try {
            // Trigger open + migration + schema validation.
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
                assertTrue("followed_channels table must exist after migration", it.moveToFirst())
            }

            // Sanity: DAO works end-to-end on the migrated DB.
            roomDb.openHelper.writableDatabase.execSQL(
                "INSERT INTO followed_channels (channelId, title, avatarUrl, followedAt) " +
                    "VALUES ('UC1', 'T', NULL, 1)"
            )
            val countCursor = roomDb.query("SELECT COUNT(*) FROM followed_channels", emptyArray())
            countCursor.use {
                assertTrue(it.moveToFirst())
                assertTrue("insert into migrated table must succeed", it.getInt(0) == 1)
            }
        } finally {
            roomDb.close()
        }
    }
}
