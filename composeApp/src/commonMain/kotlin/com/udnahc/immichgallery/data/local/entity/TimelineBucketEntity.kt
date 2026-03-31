package com.udnahc.immichgallery.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "timeline_buckets")
data class TimelineBucketEntity(
    @PrimaryKey val timeBucket: String,
    val displayLabel: String,
    val count: Int,
    val sortOrder: Int
)
