package com.albunyaan.tube.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "subscribed_channels")
data class SubscribedChannel(
    @PrimaryKey val channelId: String,
    val channelUrl: String,
    val name: String,
    val avatarUrl: String?,
    val subscribedAt: Long = System.currentTimeMillis(),
    // Plan D — sync metadata
    val user_id: String = "",
    val updated_at: Long = 0L,
    val deleted: Boolean = false,
    val dirty: Boolean = false,
    // Import metadata (ANDROID-IMPORT-01)
    @ColumnInfo(name = "approval_status") val approvalStatus: String = "APPROVED",
    @ColumnInfo(name = "source") val source: String? = null,
    @ColumnInfo(name = "imported_at") val importedAt: Long? = null,
)
