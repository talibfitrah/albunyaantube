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

/**
 * Verifies the v2 -> v3 schema migration that adds ATOM-refresh columns to
 * `channel_feed_refresh_state`:
 *   - etag (TEXT, nullable)
 *   - lastModified (TEXT, nullable)
 *   - consecutiveErrorCount (INTEGER NOT NULL DEFAULT 0)
 *   - consecutiveEmptyCount (INTEGER NOT NULL DEFAULT 0)
 *   - backoffUntilMs (INTEGER, nullable)
 *
 * The migration is additive only — pre-existing rows must survive with safe
 * defaults so the rest of the Me Tab refresh stack can rely on these fields.
 */
@RunWith(RobolectricTestRunner::class)
class AppDatabaseMigration2to3Test {

    private val DB_NAME = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        listOf(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate_2_to_3_adds_atom_columns_with_safe_defaults() {
        helper.createDatabase(DB_NAME, 2).use { v2 ->
            v2.execSQL(
                "INSERT INTO channel_feed_refresh_state " +
                    "(channelId, lastSuccessfulFetchAt, lastAttemptAt, lastErrorMessage) " +
                    "VALUES ('UCabc', 1000, 1000, NULL)"
            )
        }

        helper.runMigrationsAndValidate(DB_NAME, 3, true, MIGRATION_2_3).use { v3 ->
            v3.query(
                "SELECT etag, lastModified, consecutiveErrorCount, consecutiveEmptyCount, backoffUntilMs " +
                    "FROM channel_feed_refresh_state WHERE channelId = 'UCabc'"
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertNull(c.getString(0)) // etag
                assertNull(c.getString(1)) // lastModified
                assertEquals(0, c.getInt(2)) // consecutiveErrorCount
                assertEquals(0, c.getInt(3)) // consecutiveEmptyCount
                // backoffUntilMs (Long, nullable). Cursor#isNull is the
                // canonical NULL check for nullable INTEGER columns.
                assertTrue(c.isNull(4))
            }
        }
    }
}
