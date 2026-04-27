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
 *
 * ANDROID-PERSONAL-03 round 5 [field-bug]: an older revision of
 * [MIGRATION_1_2] shipped without creating `channel_feed_refresh_state`
 * (the table was added later, but devices that migrated through the old
 * 1→2 path don't have it). On those devices the v2 schema is missing this
 * table entirely, and a naive `ALTER TABLE ... ADD COLUMN` crashes with
 * `no such table`. We self-heal: if the table is missing, create it with
 * the full v3 column list; otherwise apply the additive ALTERs as before.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val tableExists = db.query(
            "SELECT name FROM sqlite_master WHERE type='table' AND name='channel_feed_refresh_state'"
        ).use { it.moveToFirst() }
        if (!tableExists) {
            db.execSQL(
                """CREATE TABLE channel_feed_refresh_state (
                    channelId TEXT NOT NULL PRIMARY KEY,
                    lastSuccessfulFetchAt INTEGER NOT NULL,
                    lastAttemptAt INTEGER NOT NULL,
                    lastErrorMessage TEXT,
                    etag TEXT,
                    lastModified TEXT,
                    consecutiveErrorCount INTEGER NOT NULL DEFAULT 0,
                    consecutiveEmptyCount INTEGER NOT NULL DEFAULT 0,
                    backoffUntilMs INTEGER
                )"""
            )
        } else {
            db.execSQL("ALTER TABLE channel_feed_refresh_state ADD COLUMN etag TEXT")
            db.execSQL("ALTER TABLE channel_feed_refresh_state ADD COLUMN lastModified TEXT")
            db.execSQL("ALTER TABLE channel_feed_refresh_state ADD COLUMN consecutiveErrorCount INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE channel_feed_refresh_state ADD COLUMN consecutiveEmptyCount INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE channel_feed_refresh_state ADD COLUMN backoffUntilMs INTEGER")
        }
    }
}

/**
 * v3 -> v4: NewPipe deep-paging columns on `channel_feed_refresh_state`.
 *
 * Additive only — both columns are nullable TEXT with implicit-null defaults
 * so existing rows continue to work without explicit DEFAULT clauses.
 * See [ChannelFeedRefreshState] for column-level documentation.
 *
 * ANDROID-PERSONAL-03 / T2.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Defensive (mirrors MIGRATION_2_3 self-heal): if the table is
        // somehow still missing on a v3 install, recreate it with the full
        // v4 schema so the ALTERs below would be no-ops.
        val tableExists = db.query(
            "SELECT name FROM sqlite_master WHERE type='table' AND name='channel_feed_refresh_state'"
        ).use { it.moveToFirst() }
        if (!tableExists) {
            db.execSQL(
                """CREATE TABLE channel_feed_refresh_state (
                    channelId TEXT NOT NULL PRIMARY KEY,
                    lastSuccessfulFetchAt INTEGER NOT NULL,
                    lastAttemptAt INTEGER NOT NULL,
                    lastErrorMessage TEXT,
                    etag TEXT,
                    lastModified TEXT,
                    consecutiveErrorCount INTEGER NOT NULL DEFAULT 0,
                    consecutiveEmptyCount INTEGER NOT NULL DEFAULT 0,
                    backoffUntilMs INTEGER,
                    deepPageUrl TEXT,
                    deepPageCookiesJson TEXT
                )"""
            )
        } else {
            db.execSQL("ALTER TABLE channel_feed_refresh_state ADD COLUMN deepPageUrl TEXT")
            db.execSQL("ALTER TABLE channel_feed_refresh_state ADD COLUMN deepPageCookiesJson TEXT")
        }
    }
}

/**
 * v4 → v5: clear stale deep-paging tokens.
 *
 * ANDROID-PERSONAL-03 round 8 [field-bug]: existing `deepPageUrl` /
 * `deepPageCookiesJson` values were captured by either:
 *  - the old [com.albunyaan.tube.data.me.ChannelDeepPaginator] channel-tab
 *    code path (now bypassed because of a NewPipe NPE — see commit
 *    ae9539c), so the continuation tokens belong to a different extractor
 *    and are unusable in the new uploads-playlist path; AND/OR
 *  - the old [com.albunyaan.tube.data.me.ChannelDeepPaginator.SerializedPage]
 *    that dropped NewPipe's `body` byte[]. YouTube's playlist continuation
 *    token lives in `body`, so even tokens captured by the new code path
 *    are missing it before this migration's date.
 *
 * Clearing both columns forces the next deep-page call to start from page 1
 * of the uploads playlist and re-capture the full SerializedPage (body
 * included). No schema change — only a data wipe of the two columns.
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val tableExists = db.query(
            "SELECT name FROM sqlite_master WHERE type='table' AND name='channel_feed_refresh_state'"
        ).use { it.moveToFirst() }
        if (tableExists) {
            db.execSQL(
                "UPDATE channel_feed_refresh_state SET deepPageUrl = NULL, deepPageCookiesJson = NULL"
            )
        }
    }
}

/**
 * v5 → v6: unstick channels falsely marked end-of-channel.
 *
 * ANDROID-PERSONAL-03 round 8 [field-bug]: the ATOM refresh path was
 * wiping deepPageUrl on every successful refresh by constructing a fresh
 * [ChannelFeedRefreshState] without preserving the deep-page columns.
 * Channels that had already deep-paged once would lose their continuation
 * token, then on the next deep-page round the empty token returned an
 * empty page → falsely marked [DEEP_PAGE_EOF_SENTINEL]. The cache fix
 * (use upsertAll + copy() in refreshOne) prevents future occurrences,
 * but existing rows are still stuck. Clear the sentinel so they can
 * re-attempt.
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val tableExists = db.query(
            "SELECT name FROM sqlite_master WHERE type='table' AND name='channel_feed_refresh_state'"
        ).use { it.moveToFirst() }
        if (tableExists) {
            db.execSQL(
                "UPDATE channel_feed_refresh_state SET deepPageUrl = NULL, deepPageCookiesJson = NULL WHERE deepPageUrl = 'https://yt-eof'"
            )
        }
    }
}
