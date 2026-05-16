package com.albunyaan.tube.data.subscriptions

import androidx.room.withTransaction
import com.albunyaan.tube.auth.AccountRepository
import com.albunyaan.tube.auth.currentUid
import com.albunyaan.tube.data.local.AppDatabase
import com.albunyaan.tube.data.local.SubscribedChannel
import com.albunyaan.tube.data.local.SubscribedChannelDao
import com.albunyaan.tube.data.sync.SyncManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    /**
     * Cubic R-final7 P2 — serialize cap-check + upsert across coroutines.
     *
     * <p>Room's {@code db.withTransaction} guarantees ACID per transaction but
     * does not serialize two coroutines that each open their own tx. With
     * two concurrent {@code trySubscribe} calls at count=29, both can read
     * count=29 in their own tx, both find 29 < CAP, both upsert — count
     * ends at 31, exceeding the cap. The Mutex below enforces a single
     * coroutine in the count→upsert critical section.
     */
    private val capMutex = Mutex()

    suspend fun trySubscribe(channel: SubscribedChannel): SubscribeResult {
        val uid = accountRepository.currentUid()
        val result = capMutex.withLock {
            db.withTransaction {
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
        }
        if (result is SubscribeResult.Success) syncManager.pushDirtyAsync(uid)
        return result
    }

    companion object {
        const val CAP = 30
    }
}
