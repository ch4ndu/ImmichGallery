package com.udnahc.immichgallery.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

// Kept apart from timeline_buckets because bucket metadata sync replaces that table wholesale.
@Entity(tableName = "timeline_bucket_snapshots")
data class TimelineBucketSnapshotEntity(
    @PrimaryKey val timeBucket: String,
    val assetCount: Int,
    val fingerprintHash: String,
    val updatedAt: Long
)
