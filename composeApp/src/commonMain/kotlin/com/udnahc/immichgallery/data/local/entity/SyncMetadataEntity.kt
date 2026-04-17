package com.udnahc.immichgallery.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sync_metadata")
data class SyncMetadataEntity(
    @PrimaryKey val scope: String,
    val lastSyncedAt: Long
)
