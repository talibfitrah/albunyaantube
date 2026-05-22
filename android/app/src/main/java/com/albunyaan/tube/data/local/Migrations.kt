package com.albunyaan.tube.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // ANDROID-PERSONAL-03 round 8 [field-bug]: legacy databases from a
        // pre-MIGRATION_1_2 build had a `followed_channels` table (the prior
        // name for what is now `subscribed_channels`). Android Auto-Backup
        // can restore such a DB onto a fresh install of a much newer code
        // path, leaving Room with both the legacy table AND its expected
        // new schema. Room's post-migration validator then sees an
        // unexpected `followed_channels` and crashes the app with
        // `Migration didn't properly handle: subscribed_channels`. Dropping
        // the legacy table here is destructive (legacy followed_channels
        // rows are lost), but the legacy code path has been gone long
        // enough that any user hitting this was already on stale data —
        // and the alternative is a hard crash on every cold start.
        db.execSQL("DROP TABLE IF EXISTS followed_channels")
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
        // ANDROID-PERSONAL-03 round 8 [field-bug]: same legacy-DB recovery
        // as in MIGRATION_1_2. A user whose DB is stuck at user_version=2
        // (typically because Android Auto-Backup restored a DB created by
        // a build from before MIGRATION_1_2 was written) lands here with a
        // legacy `followed_channels` table and missing v2-era tables. Drop
        // the legacy table and self-heal the v2 schema before continuing
        // to the v3 work below.
        db.execSQL("DROP TABLE IF EXISTS followed_channels")
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

/**
 * v6 → v7: adds the `followed_channels` table for the Shorts player feed.
 *
 * ANDROID-SHORTS-01: the Shorts player needs a local list of channels the
 * user follows to build the shorts feed without a round-trip to the backend.
 * This table was originally the shorts branch's MIGRATION_1_2, renumbered
 * to 6→7 when merging with the me-tab branch (which occupies versions 2–6).
 *
 * Note: earlier migrations (MIGRATION_1_2, MIGRATION_2_3) drop any legacy
 * `followed_channels` table that may exist from a pre-v2 install, so by the
 * time this migration runs the table is guaranteed absent and the
 * CREATE TABLE IF NOT EXISTS is safe.
 */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `followed_channels` " +
                "(`channelId` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                "`avatarUrl` TEXT, `followedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`channelId`))"
        )
    }
}

/**
 * MIGRATION_7_8 — Plan D sync engine.
 *
 * Adds four sync metadata columns (user_id, updated_at, deleted, dirty) to
 * the three account-scoped tables (subscribed_channels, saved_playlists,
 * favorite_videos), and creates the two new sync tables (sync_state,
 * account_binding).
 *
 * All ALTER TABLE ADD COLUMN statements use NOT NULL DEFAULT 0 / '' so that
 * existing rows acquire safe defaults without needing rewrites. Rows whose
 * user_id is '' after this migration are "anon-era" — the bind/runMerge flow
 * in SyncManager tags them with the user's uid and marks them dirty for push.
 *
 * Defensive self-heal: CREATE TABLE IF NOT EXISTS mirrors the existing
 * migration style. ALTER TABLE … ADD COLUMN does NOT support IF NOT EXISTS
 * in SQLite, so re-applying this migration will throw if the columns are
 * already present — Room never re-applies a successful migration, so this
 * is acceptable.
 */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Cubic R-final5 P0 — defensive ALTER TABLE wrapper.
        //
        // Pre-fix each ALTER ran unguarded. A device whose v7 schema is
        // missing any of these three tables (Auto-Backup partial restore,
        // sideload, manual sqlite intervention) threw "no such table" mid-
        // migration → Room bumped to destructiveMigration → ALL local
        // tables wiped. The try/catch below makes per-table ALTER failures
        // local: a missing table loses its own data only (already lost
        // since the table didn't exist), other tables' data is preserved.
        //
        // SQLite's ALTER TABLE … ADD COLUMN doesn't support IF NOT EXISTS,
        // so a re-run on a partially-applied v7→v8 (e.g., crash mid-batch)
        // would also throw — the try/catch absorbs that too.
        fun tryAlter(table: String, ddl: String) {
            try { db.execSQL(ddl) } catch (e: android.database.SQLException) {
                android.util.Log.w("Migrations",
                    "MIGRATION_7_8: skipping ALTER on $table — likely missing/already-applied: ${e.message}")
            }
        }

        // subscribed_channels
        tryAlter("subscribed_channels", "ALTER TABLE subscribed_channels ADD COLUMN user_id    TEXT    NOT NULL DEFAULT ''")
        tryAlter("subscribed_channels", "ALTER TABLE subscribed_channels ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0")
        tryAlter("subscribed_channels", "ALTER TABLE subscribed_channels ADD COLUMN deleted    INTEGER NOT NULL DEFAULT 0")
        tryAlter("subscribed_channels", "ALTER TABLE subscribed_channels ADD COLUMN dirty      INTEGER NOT NULL DEFAULT 0")

        // saved_playlists
        tryAlter("saved_playlists", "ALTER TABLE saved_playlists ADD COLUMN user_id    TEXT    NOT NULL DEFAULT ''")
        tryAlter("saved_playlists", "ALTER TABLE saved_playlists ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0")
        tryAlter("saved_playlists", "ALTER TABLE saved_playlists ADD COLUMN deleted    INTEGER NOT NULL DEFAULT 0")
        tryAlter("saved_playlists", "ALTER TABLE saved_playlists ADD COLUMN dirty      INTEGER NOT NULL DEFAULT 0")

        // favorite_videos
        tryAlter("favorite_videos", "ALTER TABLE favorite_videos ADD COLUMN user_id    TEXT    NOT NULL DEFAULT ''")
        tryAlter("favorite_videos", "ALTER TABLE favorite_videos ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0")
        tryAlter("favorite_videos", "ALTER TABLE favorite_videos ADD COLUMN deleted    INTEGER NOT NULL DEFAULT 0")
        tryAlter("favorite_videos", "ALTER TABLE favorite_videos ADD COLUMN dirty      INTEGER NOT NULL DEFAULT 0")

        // sync_state — composite PK (entityType, user_id)
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS sync_state (
              entityType   TEXT    NOT NULL,
              user_id      TEXT    NOT NULL,
              last_cursor  INTEGER NOT NULL,
              last_sync_at INTEGER NOT NULL,
              PRIMARY KEY (entityType, user_id)
            )
        """.trimIndent())

        // account_binding — single-row table
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS account_binding (
              user_id            TEXT    NOT NULL PRIMARY KEY,
              bound_at           INTEGER NOT NULL,
              initial_merge_done INTEGER NOT NULL
            )
        """.trimIndent())
    }
}

/**
 * MIGRATION_8_9 — SYNC-CURSOR-PERSIST-01 (Cubic R7 P1).
 *
 * Adds `last_doc_id TEXT NULL` to sync_state so the compound-cursor
 * tiebreaker docId survives process death. Pre-migration the docId
 * lived only in SyncManager's `lastIds` map; the first pull after
 * restart could drop rows tied on the same `updated_at` value.
 */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE sync_state ADD COLUMN last_doc_id TEXT DEFAULT NULL")
    }
}

/**
 * MIGRATION_9_10 — Me-tab playlist videos.
 *
 * Adds the `playlist_video_link` table: a many-to-many mapping between
 * saved playlists and the videos they contain, so [MeFeedRepository]
 * can union playlist videos into the same weekly-bucketed feed as
 * subscribed-channel uploads. Metadata for the videos themselves lives
 * in `channel_video_cache` keyed by videoId; playlist refresh upserts
 * those rows alongside the link rows here.
 *
 * Schema mirrors what Room generates for the @Entity declaration
 * (composite PK on playlistId+videoId, indexes on each column).
 */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS playlist_video_link (
                playlistId TEXT NOT NULL,
                videoId TEXT NOT NULL,
                PRIMARY KEY (playlistId, videoId)
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_playlist_video_link_playlistId ON playlist_video_link(playlistId)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_playlist_video_link_videoId ON playlist_video_link(videoId)"
        )
    }
}
