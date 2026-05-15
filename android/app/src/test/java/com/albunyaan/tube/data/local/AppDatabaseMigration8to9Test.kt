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
 * SYNC-CURSOR-PERSIST-01 (Cubic R7 P1) — verifies v8 → v9 migration adds
 * the `last_doc_id` column to sync_state with NULL default, and that
 * existing rows are preserved.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class AppDatabaseMigration8to9Test {

    private val DB = "migration-8-9-test.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        listOf(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate_8_to_9_adds_last_doc_id_with_null_default() {
        helper.createDatabase(DB, 8).use { db ->
            db.execSQL(
                "INSERT INTO sync_state(entityType, user_id, last_cursor, last_sync_at) " +
                    "VALUES (?, ?, ?, ?)",
                arrayOf("subscriptions", "u1", 12345L, System.currentTimeMillis())
            )
        }

        helper.runMigrationsAndValidate(DB, 9, true, MIGRATION_8_9).use { db ->
            db.query("SELECT user_id, last_cursor, last_doc_id FROM sync_state WHERE user_id='u1'").use {
                assertEquals(1, it.count)
                assertTrue(it.moveToFirst())
                assertEquals("u1", it.getString(0))
                assertEquals(12345L, it.getLong(1))
                assertNull(it.getString(2))   // new column defaults to NULL
            }
        }
    }

    @Test
    fun migrate_8_to_9_allows_writing_last_doc_id_after_migration() {
        helper.createDatabase(DB, 8).close()
        helper.runMigrationsAndValidate(DB, 9, true, MIGRATION_8_9).use { db ->
            db.execSQL(
                "INSERT INTO sync_state(entityType, user_id, last_cursor, last_doc_id, last_sync_at) " +
                    "VALUES (?, ?, ?, ?, ?)",
                arrayOf("favorites", "u2", 99L, "doc-xyz", System.currentTimeMillis())
            )
            db.query("SELECT last_doc_id FROM sync_state WHERE user_id='u2'").use {
                assertTrue(it.moveToFirst())
                assertEquals("doc-xyz", it.getString(0))
            }
        }
    }
}
