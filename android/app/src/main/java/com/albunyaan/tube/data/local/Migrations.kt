package com.albunyaan.tube.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS subscribed_channels (
                channelId TEXT NOT NULL PRIMARY KEY,
                channelUrl TEXT NOT NULL,
                name TEXT NOT NULL,
                avatarUrl TEXT,
                subscribedAt INTEGER NOT NULL)"""
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS saved_playlists (
                playlistId TEXT NOT NULL PRIMARY KEY,
                playlistUrl TEXT NOT NULL,
                name TEXT NOT NULL,
                thumbnailUrl TEXT,
                uploaderName TEXT,
                savedAt INTEGER NOT NULL)"""
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS channel_video_cache (
                videoId TEXT NOT NULL PRIMARY KEY,
                channelId TEXT NOT NULL,
                channelName TEXT NOT NULL,
                title TEXT NOT NULL,
                thumbnailUrl TEXT,
                durationSeconds INTEGER,
                viewCount INTEGER,
                uploadedAt INTEGER,
                isShort INTEGER NOT NULL,
                fetchedAt INTEGER NOT NULL)"""
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_channel_video_cache_channelId ON channel_video_cache(channelId)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_channel_video_cache_uploadedAt ON channel_video_cache(uploadedAt)"
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS channel_feed_refresh_state (
                channelId TEXT NOT NULL PRIMARY KEY,
                lastSuccessfulFetchAt INTEGER NOT NULL,
                lastAttemptAt INTEGER NOT NULL,
                lastErrorMessage TEXT)"""
        )
    }
}

/**
 * v2 -> v3: ATOM-refresh columns on `channel_feed_refresh_state`.
 *
 * Additive only — every new column is nullable or has a `DEFAULT 0`, so
 * existing rows continue to work and the migration is trivially reversible.
 * See [ChannelFeedRefreshState] for column-level documentation.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE channel_feed_refresh_state ADD COLUMN etag TEXT")
        db.execSQL("ALTER TABLE channel_feed_refresh_state ADD COLUMN lastModified TEXT")
        db.execSQL("ALTER TABLE channel_feed_refresh_state ADD COLUMN consecutiveErrorCount INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE channel_feed_refresh_state ADD COLUMN consecutiveEmptyCount INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE channel_feed_refresh_state ADD COLUMN backoffUntilMs INTEGER")
    }
}
