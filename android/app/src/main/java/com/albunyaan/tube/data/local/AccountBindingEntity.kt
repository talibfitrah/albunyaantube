package com.albunyaan.tube.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Plan D — single-row table tracking which uid this device is bound to,
 * and whether the one-time additive merge has completed.
 */
@Entity(tableName = "account_binding")
data class AccountBindingEntity(
    @PrimaryKey val user_id: String,
    val bound_at: Long,
    val initial_merge_done: Boolean,
)
