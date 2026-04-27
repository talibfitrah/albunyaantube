package com.albunyaan.tube.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Per-subscribed-channel refresh bookkeeping.
 *
 * v2 (initial): tracks last successful/attempted fetch + last error so the
 * Me Tab refresh layer can apply TTL gating and surface failures.
 *
 * v3 (ANDROID-PERSONAL-02 / ATOM refresh): adds the columns below to support
 * conditional GETs against YouTube's ATOM feed and per-channel exponential
 * backoff. All v3 fields are additive with safe defaults so the v2 -> v3
 * migration is non-destructive (see [MIGRATION_2_3]).
 *
 * - [etag] / [lastModified]: cached `If-None-Match` / `If-Modified-Since`
 *   headers. Most ticks return HTTP 304 with zero body, dramatically
 *   reducing bandwidth and the chance of triggering YouTube's anti-bot.
 * - [consecutiveErrorCount] / [consecutiveEmptyCount]: telemetry counters
 *   driven by T9's reset rules (items > 0 OR 304 -> reset both; empty entries
 *   -> reset error only; any error -> increment error).
 * - [backoffUntilMs]: per-channel cooldown after 429 (1h/4h/24h escalation)
 *   or 5xx (5min/30min/2h). Workers must skip the channel until this passes.
 */
@Entity(tableName = "channel_feed_refresh_state")
data class ChannelFeedRefreshState(
    @PrimaryKey val channelId: String,
    val lastSuccessfulFetchAt: Long,
    val lastAttemptAt: Long,
    val lastErrorMessage: String?,
    val etag: String? = null,
    val lastModified: String? = null,
    val consecutiveErrorCount: Int = 0,
    val consecutiveEmptyCount: Int = 0,
    val backoffUntilMs: Long? = null,
)
