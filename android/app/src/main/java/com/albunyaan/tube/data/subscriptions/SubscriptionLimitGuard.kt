package com.albunyaan.tube.data.subscriptions

import androidx.room.withTransaction
import com.albunyaan.tube.data.local.AppDatabase
import com.albunyaan.tube.data.local.SubscribedChannel
import com.albunyaan.tube.data.local.SubscribedChannelDao
import javax.inject.Inject
import javax.inject.Singleton

sealed class SubscribeResult {
    object Success : SubscribeResult()
    data class LimitReached(val current: Int, val cap: Int) : SubscribeResult()
}

/**
 * Enforces the 30-channel subscription cap. Playlists are unlimited and pass
 * through SubscriptionRepository without a cap check.
 *
 * Use this from UI layers (ChannelDetailFragment etc.) instead of calling
 * [SubscriptionRepository.subscribe] directly. The repository's subscribe is
 * still public so existing tests continue to compile, but the guard is the
 * canonical entry point for new subscriptions.
 */
@Singleton
class SubscriptionLimitGuard @Inject constructor(
    private val channels: SubscribedChannelDao,
    private val db: AppDatabase,
) {
    suspend fun trySubscribe(channel: SubscribedChannel): SubscribeResult =
        db.withTransaction {
            val existing = channels.getById(channel.channelId)
            if (existing != null) {
                channels.upsert(channel) // idempotent metadata refresh
                return@withTransaction SubscribeResult.Success
            }
            val current = channels.count()
            if (current >= CAP) return@withTransaction SubscribeResult.LimitReached(current, CAP)
            channels.upsert(channel)
            SubscribeResult.Success
        }

    companion object {
        const val CAP = 30
    }
}
