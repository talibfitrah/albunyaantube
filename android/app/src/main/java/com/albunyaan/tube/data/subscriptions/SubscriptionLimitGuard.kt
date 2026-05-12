package com.albunyaan.tube.data.subscriptions

import androidx.room.withTransaction
import com.albunyaan.tube.auth.AccountRepository
import com.albunyaan.tube.auth.currentUid
import com.albunyaan.tube.data.local.AppDatabase
import com.albunyaan.tube.data.local.SubscribedChannel
import com.albunyaan.tube.data.local.SubscribedChannelDao
import com.albunyaan.tube.data.sync.SyncManager
import javax.inject.Inject
import javax.inject.Singleton

sealed class SubscribeResult {
    object Success : SubscribeResult()

    /**
     * Subscription was refused because the channel cap has been reached.
     * [current] is the count at refusal time; [cap] is the hard limit.
     */
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
    private val accountRepository: AccountRepository,
    private val syncManager: SyncManager,
) {
    suspend fun trySubscribe(channel: SubscribedChannel): SubscribeResult {
        val uid = accountRepository.currentUid()
        val result = db.withTransaction {
            val existing = channels.getById(uid = uid, id = channel.channelId)
            if (existing != null) {
                channels.upsert(channel.copy(user_id = uid, dirty = true, deleted = false))
                return@withTransaction SubscribeResult.Success
            }
            val current = channels.count(uid = uid)
            if (current >= CAP) return@withTransaction SubscribeResult.LimitReached(current, CAP)
            channels.upsert(channel.copy(user_id = uid, dirty = true, deleted = false))
            SubscribeResult.Success
        }
        if (result is SubscribeResult.Success) syncManager.pushDirtyAsync(uid)
        return result
    }

    companion object {
        const val CAP = 30
    }
}
