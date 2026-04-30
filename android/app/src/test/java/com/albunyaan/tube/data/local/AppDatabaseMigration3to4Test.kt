package com.albunyaan.tube.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ANDROID-PERSONAL-03 / T2: verifies the v3 -> v4 schema migration that adds
 * NewPipe deep-paging columns to `channel_feed_refresh_state`:
 *   - deepPageUrl (TEXT, nullable)
 *   - deepPageCookiesJson (TEXT, nullable)
 *
 * Both columns default to NULL on existing rows (no DEFAULT clause needed
 * because nullable TEXT columns implicitly accept NULL during ALTER TABLE).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class AppDatabaseMigration3to4Test {

    private val DB_NAME = "migration-test-3-4.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        listOf(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate_3_to_4_adds_deep_page_columns_with_null_defaults() {
        helper.createDatabase(DB_NAME, 3).use { v3 ->
            // Insert a fully-populated v3 row to prove the migration preserves
            // ALL prior columns and only widens with the new nullable ones.
            v3.execSQL(
                "INSERT INTO channel_feed_refresh_state " +
                    "(channelId, lastSuccessfulFetchAt, lastAttemptAt, lastErrorMessage, " +
                    "etag, lastModified, consecutiveErrorCount, consecutiveEmptyCount, backoffUntilMs) " +
                    "VALUES ('UCabc', 1000, 1100, NULL, 'W/\"e1\"', 'Mon, 31 Mar 2025', 2, 1, 9000)"
            )
        }

        helper.runMigrationsAndValidate(DB_NAME, 4, true, MIGRATION_3_4).use { v4 ->
            v4.query(
                "SELECT lastSuccessfulFetchAt, etag, consecutiveErrorCount, " +
                    "deepPageUrl, deepPageCookiesJson " +
                    "FROM channel_feed_refresh_state WHERE channelId = 'UCabc'"
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(1000L, c.getLong(0))                      // lastSuccessfulFetchAt preserved
                assertEquals("W/\"e1\"", c.getString(1))               // etag preserved
                assertEquals(2, c.getInt(2))                           // consecutiveErrorCount preserved
                assertTrue("deepPageUrl must be NULL on migrated rows", c.isNull(3))
                assertTrue("deepPageCookiesJson must be NULL on migrated rows", c.isNull(4))
            }
        }
    }

    @Test
    fun migrate_3_to_4_allows_writing_deep_page_columns_after_migration() {
        helper.createDatabase(DB_NAME, 3).use { v3 ->
            v3.execSQL(
                "INSERT INTO channel_feed_refresh_state " +
                    "(channelId, lastSuccessfulFetchAt, lastAttemptAt, lastErrorMessage, " +
                    "etag, lastModified, consecutiveErrorCount, consecutiveEmptyCount, backoffUntilMs) " +
                    "VALUES ('UCabc', 1000, 1000, NULL, NULL, NULL, 0, 0, NULL)"
            )
        }

        helper.runMigrationsAndValidate(DB_NAME, 4, true, MIGRATION_3_4).use { v4 ->
            // Write into the new columns to confirm they round-trip after migration.
            v4.execSQL(
                "UPDATE channel_feed_refresh_state " +
                    "SET deepPageUrl = ?, deepPageCookiesJson = ? WHERE channelId = 'UCabc'",
                arrayOf("https://www.youtube.com/continuation/abc", "{\"CONSENT\":\"YES+1\"}")
            )
            v4.query(
                "SELECT deepPageUrl, deepPageCookiesJson FROM channel_feed_refresh_state WHERE channelId = 'UCabc'"
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("https://www.youtube.com/continuation/abc", c.getString(0))
                assertEquals("{\"CONSENT\":\"YES+1\"}", c.getString(1))
            }
        }
    }
}
